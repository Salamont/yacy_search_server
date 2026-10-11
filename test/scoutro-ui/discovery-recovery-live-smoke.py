#!/usr/bin/env python3
"""Deferral of an unconfirmed Discovery crawl start on a NEW disposable peer; no crawls, no network, no LLM.
GPL-2.0-or-later. Run after ant compile; JAVA/NODE_PATH/SCOUTRO_CHROMIUM_PATH optional.
DiscoveryReviewFixture.java builds the production case first (batch in needs_review, codecamp-n.com submitted_unknown,
no crawl profile with its marker); the peer then serves the API and the page the administrator uses.
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
import urllib.request
import urllib.error
import uuid

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
    port = probe.getsockname()[1]
    base = f'http://127.0.0.1:{port}'

with tempfile.TemporaryDirectory(prefix='scoutro-discovery-recovery-') as temp:
    root = Path(temp).resolve()
    (root / '.scoutro-dashboard-disposable').touch()
    settings = root / 'DATA/SETTINGS/yacy.conf'
    settings.parent.mkdir(parents=True)
    (root / 'DATA/DICTIONARIES/harvesting').mkdir(parents=True, exist_ok=True)
    for friends in ('export_roar_ROAR_ListFriends.xml', 'ListFriends.xml'):
        (root / 'DATA/DICTIONARIES/harvesting' / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
    runtime = root / 'DATA/SCOUTRO/config'
    runtime.mkdir(parents=True)
    (runtime / 'profiles.json').write_text(json.dumps({'profiles': {'new_profile': {'collection': 'stackfinder-web'}}}))
    (runtime / 'profiles.conf').write_text('new_profile = Beratung;Fachbetrieb\n')
    (runtime / 'osm_profiles.json').write_text(json.dumps({'extract': 'nwr[website]', 'profiles': {'new_profile': {'text_any': ['Beratung']}}}))
    (runtime / 'osm_regions.txt').write_text('test-region\n')
    (runtime / 'regions.txt').write_text('Teststadt\n')
    state = root / 'DATA/SCOUTRO/discovery'
    state.mkdir(parents=True)
    origin = [{'source': 'osm', 'source_region': 'test-region'}]
    # the worker lost its own mark of the start (no automation_attempt): only the deferral may keep it out of selection
    (state / 'state.json').write_text(json.dumps({'state_version': 2, 'paused': False, 'domains': {
        'codecamp-n.com': {'profiles': {'new_profile': {'collection': 'stackfinder-web', 'candidate_url': 'https://codecamp-n.com/', 'origins': origin}}},
        'firm-b.de': {'profiles': {'new_profile': {'collection': 'stackfinder-web', 'candidate_url': 'https://firm-b.de/', 'origins': origin}}}}}))
    firm_before = json.loads((state / 'state.json').read_text())['domains']['firm-b.de']
    runtime_before = digests(runtime)
    (root / 'DATA/LOCALE/htroot/de').mkdir(parents=True)
    (root / 'DATA/LOCALE/htroot/de/version').write_text('1.942\n')
    settings.write_text('\n'.join([f'port={port}', 'adminAccountForLocalhost=false', 'adminAccountAllPages=false', 'adminAccountUserName=admin', 'adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c', 'network.unit.definition=defaults/yacy.network.webportal.unit', 'browserPopUpTrigger=false', 'autocrawl=false', 'server.https=false', 'locale.language=browser', 'upnp.enabled=false', 'donation.iframesource=', 'resource.disk.free.min.steadystate=1', 'resource.disk.free.min.undershot=1', 'resource.disk.used.max.steadystate=1000000000000', 'resource.disk.used.max.overshot=1000000000000'])+'\n')
    subprocess.run([JAVA, '-cp', CP, str(REPO / 'test/scoutro-ui/DashboardFixture.java'), str(root)], cwd=REPO, check=True, timeout=60, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    seeded = subprocess.run([JAVA, '-cp', CP, str(REPO / 'test/scoutro-ui/DiscoveryReviewFixture.java'), str(root)], cwd=REPO, check=True, timeout=120, capture_output=True, text=True)
    blocked = json.loads(seeded.stdout.strip().splitlines()[-1])
    run_id, attempt_id = blocked['run_id'], blocked['attempt_id']
    passwords = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    passwords.add_password(None, base, 'admin', 'yacy')
    anon = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(path, method='GET', body=None, revision=None, authenticated=True, headers=None):
        hs = {'Accept': 'application/json'}
        if body is not None: hs['Content-Type'] = 'application/json'
        if revision is not None: hs['If-Match'] = str(revision)
        hs.update(headers or {})
        req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None, method=method, headers=hs)
        try: response = (urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(passwords)) if authenticated else anon).open(req, timeout=30)
        except urllib.error.HTTPError as e: response = e
        with response:
            raw = response.read().decode()
            try: value = json.loads(raw)
            except ValueError: value = raw
            return response.status, value, dict(response.headers)
    api = '/scoutro/api/v1/discovery/'
    defer = f'{api}attempts/{attempt_id}/defer'
    jobstore = settings.parent / 'scoutro-discovery-jobs.json'
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
            status = request(api + 'status')[1]
            batch = status['active_batch']
            check(batch and batch['id'] == run_id and batch['phase'] == 'needs_review' and batch['error'] == 'submitted_unknown', f'Production case: batch blocked in review {batch}')
            check([(i['attempt_id'], i['domain'], i['state']) for i in batch['review']] == [(attempt_id, 'codecamp-n.com', 'submitted_unknown')], 'Review names the unconfirmed start')
            check(not status['enabled'] and not status['heartbeat']['installed'], 'Automation disabled, no scheduler')
            revision = status['revision']
            tracked = [root / 'DATA/INDEX/webportal/SEGMENTS', root / 'DATA/QUEUES']
            before = [digests(folder) for folder in tracked]
            store_before = jobstore.read_bytes()
            # every refusal leaves the store and the candidate state as they are
            code, value, _ = request(defer, 'POST', {'run_id': run_id}, revision, authenticated=False)
            check(code == 401, f'Anonymous deferral refused: {code}')
            code, value, _ = request(defer, 'POST', {'run_id': run_id})
            check(code == 428 and value['error']['code'] == 'revision_required', f'If-Match required: {code} {value}')
            code, value, _ = request(defer, 'POST', {'run_id': run_id}, revision - 1)
            check(code == 409, f'Stale revision refused: {code} {value}')
            code, value, _ = request(defer, 'POST', {'run_id': str(uuid.uuid4())}, revision)
            check(code == 409 and value['error']['code'] == 'stale_run', f'Foreign run refused: {code} {value}')
            code, value, _ = request(defer, 'POST', {'run_id': run_id, 'state': 'not_submitted'}, revision)
            check(code == 400, f'Unknown body field refused: {code} {value}')
            code, value, _ = request(f'{api}attempts/{"0" * 32}/defer', 'POST', {'run_id': run_id}, revision)
            check(code == 409 and value['error']['code'] == 'attempt_unknown', f'Unknown attempt refused: {code} {value}')
            code, value, _ = request(f'{api}attempts/not-an-id/defer', 'POST', {'run_id': run_id}, revision)
            check(code == 400, f'Malformed attempt id refused: {code} {value}')
            code, value, _ = request(defer, 'POST', {'run_id': run_id}, revision, headers={'Origin': 'https://foreign.invalid'})
            check(code in (401, 403), f'Cross-origin deferral refused: {code} {value}')
            check(jobstore.read_bytes() == store_before, 'Refusals changed no job store')
            check('automation_attempt' not in json.loads((state / 'state.json').read_text())['domains']['codecamp-n.com']['profiles']['new_profile'], 'Refusals changed no candidate')
            subprocess.run(['node', str(REPO / 'test/scoutro-ui/discovery-recovery-ui-test.mjs')], cwd=REPO, env={**os.environ, 'SCOUTRO_URL': base}, check=True, timeout=120)
            status = request(api + 'status')[1]
            check(status['active_batch'] is None, f'Batch no longer blocks: {status["active_batch"]}')
            jobs = request(api + 'jobs')[1]
            runtime_job = jobs['jobs'][0]['runtime']
            held = runtime_job['held_attempts']
            check(len(held) == 1 and held[0]['attempt_id'] == attempt_id and held[0]['domain'] == 'codecamp-n.com' and held[0]['state'] == 'deferred', f'Deferral kept durably {held}')
            decision = held[0]['decision']
            check(decision['action'] == 'defer' and decision['outcome'] == 'unknown' and decision['by'] == 'digest:admin' and decision['run_id'] == run_id and decision['marker_found'] is False and decision['candidate_held'] is True, f'Decision recorded {decision}')
            last = status['last_run']
            check(last and last['id'] == run_id and last['phase'] == 'completed' and last.get('deferred_count') == 1 and not last.get('error'), f'Batch completed with one deferred start {last}')
            entry = json.loads((state / 'state.json').read_text())['domains']
            mark = entry['codecamp-n.com']['profiles']['new_profile']['automation_attempt']
            check(mark['id'] == attempt_id and mark['state'] == 'deferred_unknown' and mark['outcome'] == 'unknown' and mark['decided_by'] == 'digest:admin', f'Candidate held in the worker state {mark}')
            check('status' not in entry['codecamp-n.com']['profiles']['new_profile'], 'Candidate never recorded as crawled or not submitted')
            check(entry['firm-b.de'] == firm_before, 'Other candidates untouched')
            code, value, _ = request(defer, 'POST', {'run_id': run_id}, request(api + 'status')[1]['revision'])
            check(code == 409 and value['error']['code'] == 'stale_run', f'Repeated deferral changes nothing: {code} {value}')
            check(json.loads((state / 'state.json').read_text())['domains'] == entry, 'Repeated deferral left the candidate state as it was')
            audit = [json.loads(line) for line in (settings.parent / 'scoutro-user-audit.jsonl').read_text().splitlines() if line.strip()]
            defers = [a for a in audit if a.get('action') == 'api.discovery.attempts.defer POST']
            check(any(a.get('result') == 'ok' and a.get('actor') == 'digest:admin' and attempt_id in a.get('target', '') for a in defers), f'Deferral in the audit log {defers}')
            check(sum(1 for a in defers if a.get('result') == 'ok') == 1, 'Exactly one successful deferral')
            check(before == [digests(folder) for folder in tracked], 'No crawl started; index and crawl queues unchanged')
            check(digests(runtime) == runtime_before, 'Runtime config unchanged')
            check(not request(api + 'status')[1]['enabled'], 'Automation still disabled')
            print(f'PASS: {checks} live deferral checks on a disposable peer', flush=True)
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try: process.wait(timeout=30)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
            if os.environ.get('SCOUTRO_SCREENSHOTS'):
                output = Path(os.environ['SCOUTRO_SCREENSHOTS']); output.mkdir(parents=True, exist_ok=True)
                (output / 'discovery-recovery-peer.log').write_text((root / 'peer.log').read_text())
            print('Disposable peer stopped; temporary DATA removed', flush=True)
