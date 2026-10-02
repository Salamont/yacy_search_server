#!/usr/bin/env python3
"""Admin/API/UI smoke using NEW temporary DATA and an offline index; no crawls/LLM.
GPL-2.0-or-later. Run after ant compile; JAVA/NODE_PATH/SCOUTRO_CHROMIUM_PATH optional.
No production URL or DATA override is accepted.
"""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
import urllib.error

REPO = Path(__file__).resolve().parents[2]
JAVA = os.environ.get('JAVA', 'java')
CP = f'{REPO}/build/classes/java/main:{REPO}/lib/*'
checks = 0

def check(value, label):
    global checks
    assert value, label
    checks += 1

def digests(root):
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*') if p.is_file()}

with socket.socket() as probe:
    probe.bind(('127.0.0.1', 0))
    base = f'http://127.0.0.1:{probe.getsockname()[1]}'
    port = probe.getsockname()[1]

with tempfile.TemporaryDirectory(prefix='scoutro-discovery-v1-') as temp:
    root = Path(temp)
    (root / '.scoutro-dashboard-disposable').touch()
    settings = root / 'DATA/SETTINGS/yacy.conf'
    settings.parent.mkdir(parents=True)
    runtime = root / 'DATA/SCOUTRO/config'
    runtime.mkdir(parents=True)
    (runtime / 'profiles.json').write_text(json.dumps({'profiles': {'new_profile': {'collection': 'custom-index'}, 'unsupported': {'collection': 'other-index'}}}))
    (runtime / 'profiles.conf').write_text('new_profile = Beratung;Fachbetrieb\n')
    (runtime / 'osm_profiles.json').write_text(json.dumps({'extract': 'nwr[website]', 'profiles': {'new_profile': {'text_any': ['Beratung']}}}))
    (runtime / 'osm_regions.txt').write_text('test-region\nsecond-region\n')
    (runtime / 'regions.txt').write_text('Teststadt\nAndere Stadt\n')
    state = root / 'DATA/SCOUTRO/discovery'
    state.mkdir(parents=True)
    (state / 'state.json').write_text(json.dumps({'state_version': 2, 'paused': False, 'domains': {'example.com': {'profiles': {'new_profile': {'collection': 'custom-index', 'candidate_url': 'https://example.com/', 'origins': [{'source': 'osm', 'source_region': 'test-region'}]}, 'other': {'classification': {'decision': 'KEEP'}, 'status': 'crawled'}}}}}))
    state_before = digests(state)
    runtime_before = digests(runtime)
    locale = root / 'DATA/LOCALE/htroot/de'
    locale.mkdir(parents=True)
    (locale / 'version').write_text('1.942\n')
    pepper = settings.parent / 'scoutro-agent-pepper'
    pepper.write_text('01' * 32)
    settings.write_text('\n'.join([f'port={port}', 'adminAccountForLocalhost=false', 'adminAccountAllPages=false', 'adminAccountUserName=admin', 'adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c', 'network.unit.definition=defaults/yacy.network.webportal.unit', 'browserPopUpTrigger=false', 'autocrawl=false', 'server.https=false', 'locale.language=browser', 'upnp.enabled=false', 'resource.disk.free.min.steadystate=1', 'resource.disk.free.min.undershot=1', 'resource.disk.used.max.steadystate=1000000000000', 'resource.disk.used.max.overshot=1000000000000'])+'\n')
    subprocess.run([JAVA, '-cp', CP, str(REPO / 'test/scoutro-ui/DashboardFixture.java'), str(root)], cwd=REPO, check=True, timeout=60, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    passwords = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    passwords.add_password(None, base, 'admin', 'yacy')
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(passwords))
    anon = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(path, method='GET', body=None, revision=None, authenticated=True, headers=None):
        hs = {'Accept': 'application/json'}
        if body is not None: hs['Content-Type'] = 'application/json'
        if revision is not None: hs['If-Match'] = str(revision)
        hs.update(headers or {})
        req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None, method=method, headers=hs)
        try: response = (urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(passwords)) if authenticated else anon).open(req, timeout=15)
        except urllib.error.HTTPError as e: response = e
        with response:
            raw = response.read().decode()
            try: value = json.loads(raw)
            except ValueError: value = raw
            return response.status, value, dict(response.headers)
    api = '/scoutro/api/v1/discovery/'
    process = None
    with (root / 'peer.log').open('w') as log:
        try:
            process = subprocess.Popen([JAVA, '-Xmx768m', '-Djava.awt.headless=true', '-cp', CP, 'net.yacy.yacy', '-startup', str(root)], cwd=REPO, stdout=log, stderr=log)
            deadline = time.monotonic() + 60
            while True:
                if process.poll() is not None: raise RuntimeError((root / 'peer.log').read_text()[-5000:])
                try:
                    if request('/api/version.xml')[0] == 200: break
                except OSError: pass
                if time.monotonic() > deadline: raise RuntimeError((root / 'peer.log').read_text()[-5000:])
                time.sleep(.5)
            for path in ['ScoutroDiscovery_p.html', 'ScoutroDiscoveryTick_p.json', 'scoutro/api/v1/discovery/catalog', 'scoutro/api/v1/discovery/jobs', 'scoutro/api/v1/discovery/status']:
                check(request('/'+path, authenticated=False)[0] == 401, 'Admin auth '+path)
                code, value, _ = request('/'+path)
                check(code == 200, f'Authenticated {path}: {code} {str(value)[:600]}')
            status = request(api+'status')[1]
            check(not status['enabled'] and not status['heartbeat']['installed'], 'Disabled/no scheduler after installation')
            check(not (settings.parent / 'scoutro-discovery-jobs.json').exists(), 'GET creates no jobstore')
            catalog = request(api+'catalog')[1]
            check([p['id'] for p in catalog['profiles']] == ['new_profile','unsupported'], 'Dynamic profile catalog')
            check(catalog['regions']['osm'] == ['test-region','second-region'], 'Dynamic OSM regions')
            check(catalog['regions']['freeworld'] == ['Teststadt','Andere Stadt'], 'Separate Freeworld regions')
            definition = {'name': 'Disposable job', 'profile': 'new_profile', 'sources': {'osm': {'regions': ['test-region']}, 'freeworld': {'regions': ['Teststadt']}}}
            code, result, headers = request(api+'jobs','POST', definition, status['revision'])
            check(code == 201 and 'Location' in headers, 'Create job')
            job = result['jobs'][0]['definition']; job_id = job['id']
            check(not job['enabled'] and 'collection' not in job, 'Defaults/collection owned by profile')
            revision = result['revision']
            check(request(api+'jobs/'+job_id,'PATCH',{'name':'Changed'},revision-1)[0] == 409, 'Concurrent revision rejected')
            check(request(api+'jobs/'+job_id,'PATCH',{'name':'Changed'})[0] == 428, 'Revision required')
            code, result, _ = request(api+'jobs/'+job_id,'PATCH',{'name':'Changed'},revision)
            check(code == 200 and result['jobs'][0]['definition']['name'] == 'Changed', 'Patch job')
            for bad in [dict(definition, force=True),dict(definition,collection='evil'),dict(definition,batch={'max_domains':501}),dict(definition,schedule={'every_minutes':9})]:
                check(request(api+'jobs','POST',bad)[0] == 400, 'Reject unsafe job field/limit')
            check(request(api+'jobs','POST',dict(definition,sources={'osm':{'regions':['Teststadt']}}))[0] == 409,'Do not map Freeworld city to OSM')
            check(request(api+'jobs/'+job_id+'/run','POST',{'request_id':'test-disabled'})[0] == 409,'Disabled global prevents Run once')
            code, value, _ = request(api+'jobs','POST',definition,headers={'Origin':'https://foreign.invalid'})
            check(code in (401,403),f'Origin guard: {code} {value}')
            check(len(request(api+'jobs')[1]['jobs'])==1,'Cross-origin request created no job')
            # Explicit temporary heartbeat activation, with global PAUSE already set.
            for action in ['pause','enable','enable','disable','resume']:
                revision = request(api+'jobs')[1]['revision']
                code, response, _ = request(api+action,'POST',{},revision)
                check(code == 200, 'Global control '+action)
                if action == 'enable': check(response['heartbeat']['count']==1 and response['heartbeat']['interval_minutes']==10,'Unique >=10 minute heartbeat')
            code, token, _ = request('/ScoutroDiscoveryTick_p.json')
            check(code == 200 and token['reason']=='token_only','GET tick is read-only')
            form = urllib.parse.urlencode({'tick':'1','transactionToken':token['transactionToken']}).encode()
            req = urllib.request.Request(base+'/ScoutroDiscoveryTick_p.json', data=form, headers={'Content-Type':'application/x-www-form-urlencoded'})
            with client.open(req,timeout=10) as response:
                tick=json.loads(response.read());check(not tick['accepted'] and tick['reason']=='disabled','POST disabled tick refuses dispatch')
            check(request(api+'export')[1]['jobs'][0]['name']=='Changed','Export definitions')
            tracked = [root/'DATA/INDEX/webportal/SEGMENTS',root/'DATA/QUEUES']
            before = [digests(folder) for folder in tracked]
            subprocess.run(['node',str(REPO/'test/scoutro-ui/discovery-ui-test.mjs')],cwd=REPO,env={**os.environ,'SCOUTRO_URL':base},check=True,timeout=100)
            check(before == [digests(folder) for folder in tracked],'UI does not mutate index/crawl queues')
            check(digests(state)==state_before,'Candidate state unchanged')
            check(digests(runtime)==runtime_before,'Runtime config unchanged')
            check(pepper.read_text()=='01'*32 and not (settings.parent/'scoutro-agents.json').exists(),'Agents/credentials unchanged')
            check(not request(api+'status')[1]['enabled'],'Automation still disabled')
            print(f'PASS: {checks} live API/state checks; temporary heartbeat explicitly tested and disabled',flush=True)
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:process.wait(timeout=30)
                except subprocess.TimeoutExpired:process.kill();process.wait()
            if os.environ.get('SCOUTRO_SCREENSHOTS'):
                output = Path(os.environ['SCOUTRO_SCREENSHOTS']); output.mkdir(parents=True,exist_ok=True)
                (output/'discovery-peer.log').write_text((root/'peer.log').read_text())
            print('Disposable peer stopped; temporary DATA removed',flush=True)
