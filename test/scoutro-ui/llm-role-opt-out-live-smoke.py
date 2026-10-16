#!/usr/bin/env python3
"""Focused role/persistence regression: new /tmp peer, three fake models, no external LLM.

Requires ant compile, a non-root POSIX user, JDK 17+, Playwright and Chromium.
Uses the existing access peer/HTTP helper and UI fixture. No production arguments.
Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
"""
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPO = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location('access', REPO / 'test/scoutro-access/access-live-smoke.py')
access = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(access)
CALLS = []
checks = 0


def check(condition, message):
    global checks
    assert condition, message
    checks += 1


class Model(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.answer({'data': [{'id': f'fixture-{i}'} for i in range(3)]})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
        CALLS.append(body)
        if body.get('stream'):
            data = b'data: {"choices":[{"delta":{"content":"ok"}}]}\n\ndata: [DONE]\n\n'
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.answer({'choices': [{'message': {'role': 'assistant', 'content': '["unfindableword"]'}}]})

    def answer(self, body):
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def settings(config):
    # Match FileUtils.loadMap escaping, not Python's .properties/JSON escaping.
    return {line.split('=', 1)[0]: line.split('=', 1)[1].replace('\\\\', '\\').replace('\\n', '\n').replace('\\=', '=')
            for line in config.read_text().splitlines() if '=' in line and not line.startswith('#')}


def stop(process):
    process.terminate()
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)


def restart(root, port):
    log = (root / 'restarted-peer.log').open('a')
    process = subprocess.Popen([access.JAVA, '-Xmx768m', '-Djava.awt.headless=true', '-cp', access.CLASSPATH,
                               'net.yacy.yacy', '-startup', str(root)], cwd=REPO, stdout=log, stderr=log)
    log.close()
    base = f'http://127.0.0.1:{port}'
    deadline = time.monotonic() + 120
    while process.poll() is None and time.monotonic() < deadline:
        try:
            if access.Client(base).call('GET', '/scoutro/api/v1/auth/session')[0] == 200:
                return process
        except OSError:
            pass
        time.sleep(0.25)
    stop(process)
    raise AssertionError('Isolated peer did not restart')


def browser(base, directory, mode):
    env = dict(os.environ, SCOUTRO_URL=base, SCOUTRO_SETTINGS_DIR=str(directory), SCOUTRO_ROLE_TEST_MODE=mode)
    subprocess.run(['node', str(REPO / 'test/scoutro-ui/llm-role-opt-out-ui-test.mjs')],
                   cwd=REPO, env=env, check=True, timeout=180)


def long_chat(client):
    before = len(CALLS)
    body = {'model': 'chat', 'stream': True, 'messages': [{'role': 'user', 'search': 'local',
            'content': ' '.join('unfindableconcept' + chr(97 + i % 26) for i in range(48))}]}
    status, _, _ = client.call('POST', '/v1/chat/completions', body, headers={'Origin': client.base})
    check(status == 200, 'Real long-question chat endpoint works')
    return CALLS[before:]


def main():
    assert os.geteuid() != 0, 'Read-only-directory check requires a non-root user'
    model = ThreadingHTTPServer(('127.0.0.1', 0), Model)
    threading.Thread(target=model.serve_forever, daemon=True).start()
    stub = f'http://127.0.0.1:{model.server_port}'
    rows = [dict(service='OPENAI', model=f'fixture-{i}', hoststub=stub, api_key=f'sentinel-key-{i}',
                 max_tokens='2048', tldr=i == 0, logreport=i == 0, knowledge=i == 0, chat=i == 1)
            for i in range(3)]
    caps = {f'OPENAI|{stub}|fixture-{i}': dict(thinking='unsupported', tooling='unsupported', vision='unsupported',
                                            format='unsupported', format_probe=2) for i in range(3)}
    with socket.socket() as available:
        available.bind(('127.0.0.1', 0))
        port = available.getsockname()[1]
    process = None
    try:
        with tempfile.TemporaryDirectory(prefix='scoutro-role-opt-out-') as temporary:
            root = Path(temporary)
            directory = root / 'DATA/SETTINGS'
            config = directory / 'yacy.conf'
            try:
                process, base = access.start_peer(root, port, extra=[
                    'ai.production_models=' + json.dumps(rows), 'ai.model_capabilities=' + json.dumps(caps),
                    'ai.inference_system=' + json.dumps(dict(service='OPENAI', hoststub=stub, api_key='sentinel-inference-key')),
                    'ai.logreport.initial_delay_minutes=100', 'ai.logreport.period_minutes=1',
                    'role.test.sentinel=keep-other-settings'])
                mode = directory.stat().st_mode & 0o777
                browser(base, directory, 'readonly')
                check(not os.access(directory, os.W_OK), 'Real configuration directory remains read-only')
                check(json.loads(settings(config)['ai.production_models'])[0]['tldr'], 'Failed save leaves persisted roles on')
                stop(process)
                process = None
                directory.chmod(mode)
                process = restart(root, port)
                client = access.Client(base, digest=('admin', 'yacy'))
                check(json.loads(settings(config)['ai.production_models'])[0]['logreport'], 'Full restart retains old state after failed shutdown')
                active = long_chat(client)
                check(len(active) == 2 and sum(not call.get('stream') for call in active) == 1,
                      'Positive control: long question really triggers the existing TLDR when assigned')
                off = [dict(row, api_key='', tldr=False, logreport=False) for row in rows]
                try:
                    directory.chmod(0o555)
                    failed = client.call('POST', '/LLMSelection_p.html', {'production_models': off})[0]
                finally:
                    directory.chmod(mode)
                succeeded = client.call('POST', '/LLMSelection_p.html', {'production_models': off})[0]
                check((failed, succeeded) == (500, 200), 'Real failed and successful saves immediately following each other')
                check(all(not row['tldr'] and not row['logreport'] for row in json.loads(settings(config)['ai.production_models'])),
                      'Success following failure publishes only the successful state')
                check(client.call('POST', '/LLMSelection_p.html', {'production_models': [dict(row, api_key='') for row in rows]})[0] == 200,
                      'Reset only the disposable matrix for the browser success scenario')
                browser(base, directory, 'success')
                saved = settings(config)
                stored = json.loads(saved['ai.production_models'])
                check(all(not row['tldr'] and not row['logreport'] for row in stored), 'All models disabled in persisted state')
                check(stored[0]['knowledge'], 'Knowledge preserved')
                check([row['api_key'] for row in stored] == [f'sentinel-key-{i}' for i in range(3)], 'All three stored API keys preserved')
                check(json.loads(saved['ai.inference_system'])['api_key'] == 'sentinel-inference-key', 'Inference key preserved')
                check(saved['role.test.sentinel'] == 'keep-other-settings', 'Unrelated settings preserved')
                disabled = long_chat(client)
                check(len(disabled) == 1 and disabled[0].get('stream'), 'No automatic TLDR call after deselection; chat still works')
                stop(process)
                process = None
                # Existing schedule only: immediate scheduler tick on restart, with absent roles.
                text = config.read_text().replace('ai.logreport.initial_delay_minutes=100', 'ai.logreport.initial_delay_minutes=0')
                config.write_text(text)
                before = len(CALLS)
                process = restart(root, port)
                deadline = time.monotonic() + 15
                while 'reason=no-logreport-model' not in (root / 'restarted-peer.log').read_text() and time.monotonic() < deadline:
                    time.sleep(0.1)
                check('reason=no-logreport-model' in (root / 'restarted-peer.log').read_text(), 'Real automatic report scheduler skips absent role after restart')
                check(len(CALLS) == before, 'No automatic LLM calls at restart/scheduler tick')
                client = access.Client(base, digest=('admin', 'yacy'))
                disabled = long_chat(client)
                check(len(disabled) == 1 and disabled[0].get('stream'), 'No TLDR call after full process restart')
                check(all(not row['tldr'] and not row['logreport'] for row in json.loads(settings(config)['ai.production_models'])), 'Roles remain off after restart')
                check(access.Client(base).call('POST', '/LLMSelection_p.html', {'production_models': []})[0] in (401, 403), 'Unauthenticated writes still denied')
                # No text/html Accept: urllib needs the Digest challenge rather than
                # the browser login redirect (browser tests use digest-signin.mjs).
                check(client.call('GET', '/ViewLog_p.html')[0] == 200, 'Normal YaCy logs remain accessible')
                check(client.call('GET', '/LogReports_p.html')[0] == 200, 'Existing report page remains accessible')
            finally:
                if process is not None and process.poll() is None:
                    stop(process)
                if directory.exists():
                    directory.chmod(0o755)
    finally:
        model.shutdown()
        model.server_close()
    print(f'PASS: {checks} real peer role/restart/model-call checks; only local fake models', flush=True)


if __name__ == '__main__':
    main()
