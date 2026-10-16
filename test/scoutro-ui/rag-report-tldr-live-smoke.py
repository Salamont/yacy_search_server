#!/usr/bin/env python3
"""Disposable Scoutro + local fake LLM; never accepts a production URL/DATA directory."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import html
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = Path(__file__).resolve().parents[2]
JAVA = os.environ.get('JAVA', 'java')
CP = f'{REPO}/build/classes/java/main:{REPO}/lib/*'
CALLS = []
INVALID = False
checks = 0


def check(value, message):
    global checks
    assert value, message
    checks += 1


class FakeLLM(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.answer({'data': [], 'models': []})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
        messages = body.get('messages', [])
        instruction = messages[0].get('content', '') if messages else ''
        kind = 'report' if 'runtime logs' in instruction else 'tldr' if body.get('response_format') else 'chat'
        CALLS.append((kind, body))
        if body.get('stream'):
            content = '# Testbericht\nBeobachtungen aus dem Test.' if kind == 'report' else 'Keine belegten Quellen.'
            data = ('data: ' + json.dumps({'choices': [{'delta': {'content': content}, 'finish_reason': 'stop'}]})
                    + '\n\ndata: [DONE]\n\n').encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.answer({'model': 'fixture', 'choices': [{'message': {'role': 'assistant',
                        'content': 'not json' if INVALID else '["Wärmedämmung","Köln"]'}, 'finish_reason': 'stop'}],
                         'usage': {'prompt_tokens': 31, 'completion_tokens': 9}})

    def answer(self, value):
        data = json.dumps(value).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def settings(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line and not line.startswith('#'))


def main():
    global INVALID
    upstream = ThreadingHTTPServer(('127.0.0.1', 0), FakeLLM)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    stub = f'http://127.0.0.1:{upstream.server_port}'
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    process = None
    try:
        with tempfile.TemporaryDirectory(prefix='scoutro-controls-') as temporary:
            root = Path(temporary)
            (root / '.scoutro-dashboard-disposable').touch()
            config = root / 'DATA/SETTINGS/yacy.conf'
            config.parent.mkdir(parents=True)
            cache = root / 'DATA/DICTIONARIES/harvesting'
            cache.mkdir(parents=True)
            for name in ('export_roar_ROAR_ListFriends.xml', 'ListFriends.xml'):
                (cache / name).write_text('<?xml version="1.0" encoding="UTF-8"?><BaseURLs/>')
            row = dict(service='OPENAI', hoststub=stub, model='fixture', api_key='sk-local-sentinel',
                       max_tokens='1024', chat=True, tldr=True, logreport=True)
            config.write_text('\n'.join([
                f'port={port}', 'adminAccountForLocalhost=false', 'adminAccountAllPages=false',
                'adminAccountUserName=admin', 'adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c',
                'network.unit.definition=defaults/yacy.network.webportal.unit', 'browserPopUpTrigger=false',
                'autocrawl=false', 'server.https=false', 'upnp.enabled=false', 'donation.iframesource=',
                'locale.language=de', 'ai.logreport.enabled=false', 'ai.tldr.enabled=false',
                'resource.disk.free.min.steadystate=1', 'resource.disk.free.min.undershot=1',
                'resource.disk.used.max.steadystate=1000000000000', 'resource.disk.used.max.overshot=1000000000000',
                'ai.production_models=' + json.dumps([row]), 'ai.service_num_ctx=' + json.dumps({stub: 8192}),
                'ai.model_capabilities=' + json.dumps({f'OPENAI|{stub}|fixture': dict(thinking='unsupported', tooling='unsupported', vision='unsupported', format='unsupported', format_probe=4)}),
                'ai.inference_system=' + json.dumps(dict(service='OPENAI', hoststub=stub)),
            ]) + '\n')
            state = root / 'DATA/SCOUTRO/discovery/state.json'
            state.parent.mkdir(parents=True)
            state.write_text('{"state_version":2,"domains":{}}\n')
            sentinel = hashlib.sha256(state.read_bytes()).hexdigest()
            subprocess.run([JAVA, '-cp', CP, str(REPO / 'test/scoutro-ui/DashboardFixture.java'), str(root)],
                           cwd=REPO, check=True, timeout=60, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            passwords = urllib.request.HTTPPasswordMgrWithDefaultRealm()
            passwords.add_password(None, base, 'admin', 'yacy')
            client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(passwords))
            guest = urllib.request.build_opener(urllib.request.ProxyHandler({}))

            def call(path, data=None, auth=True, method=None, content_type=None, headers=None):
                if isinstance(data, dict):
                    data = urllib.parse.urlencode(data).encode()
                    content_type = 'application/x-www-form-urlencoded'
                request = urllib.request.Request(base + path, data=data, method=method,
                                                 headers=dict({} if content_type is None else {'Content-Type': content_type}, **(headers or {})))
                try:
                    with (client if auth else guest).open(request, timeout=45) as response:
                        return response.status, response.read()
                except urllib.error.HTTPError as error:
                    return error.code, error.read()

            def token(path):
                status, body = call(path)
                check(status == 200, 'admin page ' + path)
                found = re.search(rb'name="transactionToken" value="([^"]+)"', body)
                check(found is not None, 'CSRF token ' + path)
                return html.unescape(found[1].decode())

            def post(path, data):
                values = dict(data, transactionToken=token(path))
                return call(path, values)

            def chat():
                body = json.dumps({'model': 'chat', 'search': 'local', 'stream': True,
                                   'messages': [{'role': 'user', 'search': 'local', 'content': ' '.join(['Weltraumsonderfrage'] * 45)}]}).encode()
                return call('/v1/chat/completions', body, content_type='application/json', headers={'X-Forwarded-For': '198.51.100.23'})

            def start():
                nonlocal process
                logfile = (root / 'peer.log').open('a')
                process = subprocess.Popen([JAVA, '-Xmx768m', '-Djava.awt.headless=true', '-cp', CP,
                                            'net.yacy.yacy', '-startup', str(root)], cwd=REPO, stdout=logfile, stderr=logfile)
                logfile.close()
                deadline = time.monotonic() + 60
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError((root / 'peer.log').read_text()[-3000:])
                    try:
                        if call('/api/version.xml')[0] == 200:
                            return
                    except OSError:
                        pass
                    time.sleep(.3)
                raise RuntimeError('Disposable peer startup timed out')

            def stop():
                if process and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()

            try:
                start()
                first_log_minute = int(time.time()) // 60
                for page, action in [('/LLMSelection_p.html', 'testTldr'), ('/LogReports_p.html', 'runReportNow'), ('/LogReports_p.html', 'saveReportSettings')]:
                    check(call(page, auth=False)[0] == 401, 'admin protection ' + page)
                    status = call(page, {action: '1'})[0]
                    check(status in (400, 403), 'missing CSRF ' + page + ': ' + str(status))
                    check(call(page + '?' + urllib.parse.urlencode({action: '1', 'transactionToken': token(page)}))[0] == 405,
                          'GET mutation refused ' + page)
                check(not CALLS, 'page views/security failures must not infer')
                check(chat()[0] == 200, 'normal chat while TLDR disabled')
                check(sum(k == 'tldr' for k, _ in CALLS) == 0, 'disabled TLDR was called')
                status, body = post('/LLMSelection_p.html', {'testTldr': '1', 'tldrQuestion': 'Welche Wärmedämmung gibt es in Köln?'})
                check(status == 200 and b'tldr-test-result' in body, 'manual test page')
                check('Wärmedämmung' in html.unescape(body.decode()) and b'promptTokens' in body and b'not json' not in body,
                      'raw answer, terms, usage displayed: ' + html.unescape(body.decode().split('id=\"tldr-test-result\"')[-1][:1600]))
                check(sum(k == 'tldr' for k, _ in CALLS) == 1, 'manual test once while disabled')
                check(settings(config)['ai.tldr.enabled'] == 'false', 'manual test toggled automatic mode')
                INVALID = True
                status, body = post('/LLMSelection_p.html', {'testTldr': '1', 'tldrQuestion': 'Rechner Köln'})
                check(status == 200 and b'not json' in body and b'Fehler' in body, 'malformed raw answer visible')
                INVALID = False
                post('/LLMSelection_p.html', {'saveTldrSettings': '1', 'tldrEnabled': 'on', 'tldrDiagnostics': 'on'})
                before = sum(k == 'tldr' for k, _ in CALLS)
                status, body = chat()
                check(status == 200 and sum(k == 'tldr' for k, _ in CALLS) == before + 1, 'automatic fallback once after enable: status=%s kinds=%s body=%s' % (status, [k for k,_ in CALLS], body[:600]))
                status, body = call('/LLMSelection_p.html')
                check(b'long-question-without-sources' in body and b'sourcesBefore' in body and b'Weltraumsonderfrage' in body,
                      'admin diagnostic contents and source counts')
                post('/LLMSelection_p.html', {'saveTldrSettings': '1'})
                status, body = call('/LLMSelection_p.html')
                check(b'Weltraumsonderfrage' not in body, 'disable clears diagnostic content')
                # No automatic log requests while disabled, including manual TLDR/chart work above.
                check(sum(k == 'report' for k, _ in CALLS) == 0, 'disabled reports were generated')
                # Use only actual standard logs. Wait for their minute to be past, without adding a private log source.
                deadline = time.monotonic() + 65
                while int(time.time()) // 60 <= first_log_minute and time.monotonic() < deadline:
                    time.sleep(.25)
                check(int(time.time()) // 60 > first_log_minute, 'completed standard-log minute available')
                reports = root / 'DATA/REPORTS/log'
                check(not (reports / '.operational-statistics.gz').exists(), 'no statistics store while disabled')
                _, manual_page = call('/LogReports_p.html')
                manual_values = {key: html.unescape(re.search(fr'name="{key}"[^>]*value="([^"]+)"'.encode(), manual_page)[1].decode())
                                 for key in ('reportStart', 'reportEnd')}
                status, _ = post('/LogReports_p.html', dict(manual_values, runReportNow='1'))
                check(status == 200, 'manual report request')
                deadline = time.monotonic() + 15
                outcome = 0
                while time.monotonic() < deadline:
                    result = json.loads(call('/api/logreportstatus.json')[1])
                    if result['outcome']:
                        outcome = result['outcome']
                        break
                    time.sleep(.2)
                check(outcome == 1, 'manual report success: ' + str(result))
                reports = root / 'DATA/REPORTS/log'
                files = list(reports.glob('report-*.md'))
                check(files and sum(k == 'report' for k, _ in CALLS) == 1, 'manual report persisted while automatic off')
                snapshot = {p.name: p.read_bytes() for p in files}
                before = len(CALLS)
                post('/LogReports_p.html', dict(manual_values, runReportNow='1'))
                time.sleep(.3)
                check(len(CALLS) == before, 'unconfirmed report recreation made another paid call')
                post('/LogReports_p.html', dict(manual_values, runReportNow='1', recreateReport='on'))
                deadline = time.monotonic() + 10
                while sum(k == 'report' for k, _ in CALLS) < 2 and time.monotonic() < deadline:
                    time.sleep(.1)
                check(sum(k == 'report' for k, _ in CALLS) == 2, 'explicit report recreation')
                for mode in ('hourly', 'daily', 'weekly', 'monthly'):
                    for hours in (1, 2, 4, 6, 12) if mode == 'hourly' else (1,):
                        status, body = post('/LogReports_p.html', {'saveReportSettings': '1', 'scheduleMode': mode,
                            'scheduleHours': str(hours), 'scheduleTime': '06:30', 'scheduleWeekday': '3',
                            'scheduleMonthday': '0', 'scheduleZone': 'Europe/Berlin'})
                        stored = settings(config)['ai.logreport.schedule']
                        check(status == 200 and stored == f'1|{mode}|{hours}|06:30|3|0|Europe/Berlin', 'schedule persistence')
                post('/LogReports_p.html', {'saveReportSettings': '1', 'scheduleMode': 'off', 'scheduleHours': '2',
                    'scheduleTime': '06:30', 'scheduleZone': 'Europe/Berlin'})
                check(settings(config)['ai.logreport.enabled'] == 'false', 'disable reports persisted')
                status, _ = post('/LogReports_p.html', {'runReportNow': '1', 'reportStart': '2026-01-01T00:00', 'reportEnd': '2026-01-01T00:00'})
                check(status == 400, 'invalid manual period rejected before any model call')
                check(all((reports / name).exists() for name in snapshot), 'settings deleted existing reports')
                if os.environ.get('NODE_PATH'):
                    subprocess.run(['node', str(REPO / 'test/scoutro-ui/rag-report-tldr-ui-test.mjs')], cwd=REPO,
                                   env=dict(os.environ, SCOUTRO_URL=base), check=True, timeout=90)
                before = len(CALLS)
                stop()
                start()
                check(settings(config)['ai.tldr.enabled'] == 'false' and settings(config)['ai.logreport.enabled'] == 'false',
                      'toggles survive restart')
                check(settings(config)['ai.logreport.schedule'] == '1|off|2|06:30|1|0|Europe/Berlin',
                      'schedule survives restart')
                check(all((reports / name).exists() for name in snapshot), 'restart deleted existing reports')
                check(len(CALLS) == before, 'restart silently generated reports')
                check(not (reports / '.operational-statistics.gz').exists(), 'restart introduced statistics collection')
                check(not list(reports.glob('.statistics-*')), 'no statistics checkpoint files')
                check(hashlib.sha256(state.read_bytes()).hexdigest() == sentinel, 'discovery state changed')
                check(json.loads(call('/scoutro/api/v1/crawls')[1])['crawls'] == [], 'crawler was started')
                print(f'PASS: {checks} isolated control/security/restart checks; only local fake-model requests', flush=True)
            except Exception:
                print((root / 'peer.log').read_text()[-2500:])
                raise
            finally:
                stop()
    finally:
        upstream.shutdown()
        upstream.server_close()


if __name__ == '__main__':
    main()
