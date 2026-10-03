#!/usr/bin/env python3
"""LLM UI/admin discovery regression on NEW disposable DATA + fake Ollama only.

Requires ant compile, JDK 17+, Playwright and Chromium. No production URL or
DATA parameter is accepted. Uses existing offline Solr fixture; never crawls.
Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
"""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = Path(__file__).resolve().parents[2]
JAVA = os.environ.get("JAVA", "java")
CLASSPATH = f"{REPO}/build/classes/java/main:{REPO}/lib/*"
checks = 0
requests = []


def check(value, message):
    global checks
    assert value, message
    checks += 1


class OllamaFixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        requests.append(("POST", self.path))
        self.send_error(405)

    def do_GET(self):
        requests.append(("GET", self.path))
        variant = self.path.split("/")[1]
        status = {"http503": 503, "http502": 502, "auth": 401}.get(variant, 200)
        payload = {"models": [{"model": "fixture-model:latest"}]}
        if self.path.endswith("/v1/models"):
            payload = {"data": [{"id": "fixture-openai"}]}
        elif variant == "empty":
            payload = {"models": []}
        elif variant == "name-only":
            payload = {"models": [{"name": "name-only:latest"}]}
        elif variant == "invalid-shape":
            payload = {"error": "fixture-secret-sentinel"}
        elif variant == "invalid-entry":
            payload = {"models": [None]}
        if status != 200:
            payload = {"error": "fixture-secret-sentinel"}
        body = b"<html>fixture-secret-sentinel</html>" if variant == "invalid-json" else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    upstream = ThreadingHTTPServer(("127.0.0.1", 0), OllamaFixture)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    stub = f"http://127.0.0.1:{upstream.server_port}"
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    process = None
    try:
        with tempfile.TemporaryDirectory(prefix="scoutro-llm-selection-") as temporary:
            root = Path(temporary)
            (root / ".scoutro-dashboard-disposable").touch()
            config = root / "DATA/SETTINGS/yacy.conf"
            config.parent.mkdir(parents=True)
            inference = {"service": "OLLAMA", "hoststub": stub, "api_key": ""}
            capabilities = {f"OLLAMA|{stub}|fixture-model:latest":
                            dict.fromkeys(["thinking", "tooling", "vision", "format"], "unsupported")}
            config.write_text("\n".join([
                f"port={port}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
                "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
                "network.unit.definition=defaults/yacy.network.webportal.unit", "browserPopUpTrigger=false",
                "autocrawl=false", "server.https=false", "locale.language=browser", "upnp.enabled=false",
                "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
                "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
                "ai.production_models=[]", "ai.inference_system=" + json.dumps(inference),
                "ai.model_capabilities=" + json.dumps(capabilities),
            ]) + "\n")
            for dictionary in (REPO / "locales").glob("*.lng"):
                locale = root / "DATA/LOCALE/htroot" / dictionary.stem
                locale.mkdir(parents=True)
                (locale / "version").write_text("stale fixture\n")
            state = root / "DATA/SCOUTRO/discovery/state.json"
            state.parent.mkdir(parents=True)
            state.write_text('{"state_version":2,"domains":{}}\n')
            sentinel = hashlib.sha256(state.read_bytes()).hexdigest()
            subprocess.run([JAVA, "-cp", CLASSPATH, str(REPO / "test/scoutro-ui/DashboardFixture.java"), str(root)],
                           cwd=REPO, check=True, timeout=60, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            password = urllib.request.HTTPPasswordMgrWithDefaultRealm()
            password.add_password(None, base, "admin", "yacy")
            client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(password))

            def get(path):
                try:
                    with client.open(base + path, timeout=15) as response:
                        return response.status, response.read()
                except urllib.error.HTTPError as error:
                    return error.code, error.read()

            with (root / "peer.log").open("w") as log:
                try:
                    process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", CLASSPATH,
                                                "net.yacy.yacy", "-startup", str(root)], cwd=REPO, stdout=log, stderr=log)
                    deadline = time.monotonic() + 60
                    while True:
                        if process.poll() is not None:
                            raise RuntimeError((root / "peer.log").read_text()[-5000:])
                        try:
                            if get("/api/version.xml")[0] == 200:
                                break
                        except OSError:
                            pass
                        if time.monotonic() > deadline:
                            raise RuntimeError((root / "peer.log").read_text()[-5000:])
                        time.sleep(0.5)
                    query = urllib.parse.quote(stub, safe="")
                    try:
                        no_auth = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                        no_auth.open(base + "/api/tags?hoststub=" + query, timeout=10)
                        raise AssertionError("Discovery admin authentication bypassed")
                    except urllib.error.HTTPError as error:
                        check(error.code == 401, "Unauthenticated discovery must be 401")
                    status, body = get("/api/tags?hoststub=" + query)
                    check(status == 200 and json.loads(body)["models"][0]["model"] == "fixture-model:latest", "Configured hoststub returns Ollama models")
                    for variant, expected in [("http503", 503), ("auth", 401), ("http502", 502)]:
                        status, _ = get("/api/tags?hoststub=" + urllib.parse.quote(stub + "/" + variant, safe=""))
                        check(status == expected, "Proxy lost upstream status " + variant)
                    status, body = get("/api/tags")
                    check(status == 200 and all(m["model"] != "fixture-model:latest" for m in json.loads(body)["models"]), "No-hoststub endpoint contains virtual usages only")
                    get("/LLMSelection_p.html")  # Warm the responder navigation record before reading settings.
                    before = {k: v for k, v in settings(config).items() if k.startswith("ai.")}
                    env = dict(os.environ, SCOUTRO_URL=base, SCOUTRO_LLM_FIXTURE_STUB=stub)
                    subprocess.run(["node", str(REPO / "test/scoutro-ui/llm-selection-ui-test.mjs")], cwd=REPO, env=env, check=True, timeout=180)
                    check(before == {k: v for k, v in settings(config).items() if k.startswith("ai.")}, "Browser test unexpectedly changed inference/production configuration")
                    check(hashlib.sha256(state.read_bytes()).hexdigest() == sentinel, "Discovery state changed")
                    check(all(method == "GET" and path.endswith(("/api/tags", "/v1/models")) for method, path in requests), "Unexpected inference, pull or delete request")
                    status, body = get("/scoutro/api/v1/crawls")
                    check(status == 200 and json.loads(body)["crawls"] == [], "Smoke started a crawl")
                finally:
                    if process is not None and process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=30)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait()
    finally:
        upstream.shutdown()
        upstream.server_close()
    print(f"PASS: {checks} live LLM HTTP/non-mutation checks; temporary DATA and fake Ollama removed", flush=True)


def settings(config):
    return dict(line.split("=", 1) for line in config.read_text().splitlines() if "=" in line and not line.startswith("#"))


if __name__ == "__main__":
    main()
