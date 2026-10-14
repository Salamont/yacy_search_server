#!/usr/bin/env python3
"""Quality check of the LLM selection on NEW disposable DATA and a fake model endpoint only.

Requires ant compile, JDK 17+, Playwright and Chromium. No production URL or DATA parameter is accepted; nothing is
crawled. The fake endpoint answers every reference page with an empty reading (deviations) and lists a price; the
browser test runs one check, cancels the switch of the knowledge model and confirms it only into an intercepted save.
Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
"""
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
ACTIVE_SECRET = "sk-active-sentinel-0123456789"
CANDIDATE_SECRET = "sk-quality-sentinel-0123456789"
EMPTY = '{"entities":[],"claims":[],"values":[]}'
checks = 0
chat = []
lists = []


def check(value, message):
    global checks
    assert value, message
    checks += 1


class ModelFixture(BaseHTTPRequestHandler):
    """An OpenAI-compatible endpoint: an empty reading for every request, and a model list with prices per token."""

    def log_message(self, *args):
        pass

    def answer(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        lists.append((self.path, self.headers.get("Authorization", "")))
        if self.path.endswith("/v1/models"):
            return self.answer(200, {"data": [{"id": "quality-fixture", "pricing": {"prompt": "0.000001", "completion": "0.000004"}},
                                              {"id": "active-fixture"}]})
        self.send_error(404)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        chat.append((self.path, body, self.headers.get("Authorization", "")))
        if not self.path.endswith("/v1/chat/completions"):
            self.send_error(404)
            return
        return self.answer(200, {"model": "fixture/served", "provider": "FixtureProvider",
                                 "choices": [{"message": {"content": EMPTY}, "finish_reason": "stop"}],
                                 "usage": {"prompt_tokens": 900, "completion_tokens": 20}})


def settings(config):
    return dict(line.split("=", 1) for line in config.read_text().splitlines() if "=" in line and not line.startswith("#"))


def main():
    upstream = ThreadingHTTPServer(("127.0.0.1", 0), ModelFixture)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    stub = f"http://127.0.0.1:{upstream.server_port}"
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    process = None
    try:
        with tempfile.TemporaryDirectory(prefix="scoutro-llm-quality-") as temporary:
            root = Path(temporary)
            config = root / "DATA/SETTINGS/yacy.conf"
            config.parent.mkdir(parents=True)
            (root / "DATA/DICTIONARIES/harvesting").mkdir(parents=True, exist_ok=True)
            for friends in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
                (root / "DATA/DICTIONARIES/harvesting" / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
            rows = [
                {"service": "OPENROUTER", "model": "active-fixture", "hoststub": stub, "api_key": ACTIVE_SECRET, "max_tokens": "512",
                 "chat": False, "tldr": False, "logreport": False, "knowledge": True},
                {"service": "OPENROUTER", "model": "quality-fixture", "hoststub": stub, "api_key": CANDIDATE_SECRET, "max_tokens": "512",
                 "chat": False, "tldr": False, "logreport": False, "knowledge": False},
            ]
            # known capabilities (current probe versions): the page probes nothing, the endpoint sees the check's requests only
            known = {"thinking": "unsupported", "tooling": "unsupported", "vision": "unsupported", "format": "supported", "format_probe": 2}
            capabilities = {f"OPENROUTER|{stub}|active-fixture": known, f"OPENROUTER|{stub}|quality-fixture": known}
            config.write_text("\n".join([
                f"port={port}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
                "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
                "network.unit.definition=defaults/yacy.network.webportal.unit", "browserPopUpTrigger=false",
                "autocrawl=false", "server.https=false", "locale.language=browser", "upnp.enabled=false", "donation.iframesource=",
                "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
                "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
                # the graph runs (its prompt is what the check reads with) but has no LLM collection: its own model is never asked
                "scoutro.kg.enabled=true", "scoutro.kg.collections=kga",
                "ai.production_models=" + json.dumps(rows, separators=(",", ":")),
                "ai.model_capabilities=" + json.dumps(capabilities, separators=(",", ":")),
                "ai.service_num_ctx=" + json.dumps({stub: 8192}, separators=(",", ":")),
                "ai.inference_system=" + json.dumps({"service": "OPENROUTER", "hoststub": stub, "api_key": ""}),
            ]) + "\n")
            for dictionary in (REPO / "locales").glob("*.lng"):
                locale = root / "DATA/LOCALE/htroot" / dictionary.stem
                locale.mkdir(parents=True)
                (locale / "version").write_text("stale fixture\n")
            password = urllib.request.HTTPPasswordMgrWithDefaultRealm()
            password.add_password(None, base, "admin", "yacy")
            client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(password))

            def call(path, body=None):
                request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                                 headers={"Content-Type": "application/json"} if body is not None else {})
                try:
                    with client.open(request, timeout=30) as response:
                        return response.status, response.read()
                except urllib.error.HTTPError as error:
                    return error.code, error.read()

            with (root / "peer.log").open("w") as log:
                try:
                    process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", CLASSPATH,
                                                "net.yacy.yacy", "-startup", str(root)], cwd=REPO, stdout=log, stderr=log)
                    deadline = time.monotonic() + 120
                    while True:
                        if process.poll() is not None:
                            raise RuntimeError((root / "peer.log").read_text()[-5000:])
                        try:
                            status, body = call("/scoutro/api/v1/kg/llm-quality")
                            if status == 200 and json.loads(body).get("graphRunning"):
                                break
                        except OSError:
                            pass
                        if time.monotonic() > deadline:
                            raise RuntimeError("graph not running: " + (root / "peer.log").read_text()[-3000:])
                        time.sleep(0.5)
                    before = {k: v for k, v in settings(config).items() if k.startswith("ai.")}
                    status, body = call("/scoutro/api/v1/kg/llm-quality")
                    state = json.loads(body)
                    check(status == 200 and len(state["candidates"]) == 2, "candidates: %s" % body[:300])
                    check(ACTIVE_SECRET.encode() not in body and CANDIDATE_SECRET.encode() not in body, "a key in the status")
                    check([c["status"] for c in state["candidates"]] == ["unchecked", "unchecked"], "not unchecked at the start")
                    check(state["candidates"][0]["activeForKnowledge"] and not state["candidates"][1]["activeForKnowledge"], "active row")
                    key = state["candidates"][1]["key"]
                    # refusals before anything is sent
                    status, body = call("/scoutro/api/v1/kg/llm-quality", {"action": "start", "candidate": key})
                    check(status == 400 and json.loads(body)["error"]["code"] == "cost_unknown", "start without price: %s %s" % (status, body[:200]))
                    status, body = call("/scoutro/api/v1/kg/llm-quality/plan?candidate=" + urllib.parse.quote(key, safe="")
                                        + "&inputPrice=1&outputPrice=4&currency=USD&priceSource=provider_list")
                    plan = json.loads(body)
                    check(status == 200 and plan["calls"]["planned"] == 10 and plan["calls"]["worstCase"] == 20, "plan: %s" % body[:300])
                    check(plan["window"] == {"tokens": 8192, "source": "configured"}, "window: %s" % plan["window"])
                    check(plan["cost"]["known"] and float(plan["cost"]["worstCase"]) > 0, "worst-case cost: %s" % plan["cost"])
                    check(chat == [], "the plan sent a request: %s" % chat[:1])
                    env = dict(os.environ, SCOUTRO_URL=base, SCOUTRO_QUALITY_STUB=stub, SCOUTRO_QUALITY_KEY=key)
                    subprocess.run(["node", str(REPO / "test/scoutro-ui/llm-quality-ui-test.mjs")], cwd=REPO, env=env, check=True, timeout=420)
                    # the browser test ran exactly one check: ten requests of the candidate, with the graph's prompt and schema
                    check(len(chat) == 10, "requests: %d" % len(chat))
                    for path, body, auth in chat:
                        check(body.get("model") == "quality-fixture" and auth == "Bearer " + CANDIDATE_SECRET, "request of another model or key")
                        check(body["messages"][0]["content"].startswith("You extract organisations"), "not the knowledge prompt")
                        check(body.get("response_format", {}).get("type") == "json_schema" and body.get("max_tokens") == 512, "not the graph's request")
                    check(before == {k: v for k, v in settings(config).items() if k.startswith("ai.")}, "the check or the browser changed a setting")
                    status, body = call("/scoutro/api/v1/kg/llm-quality")
                    after = json.loads(body)
                    check(after["candidates"][1]["status"] == "deviations" and after["candidates"][0]["status"] == "unchecked", "statuses: %s"
                          % [c["status"] for c in after["candidates"]])
                    check(after["candidates"][0]["activeForKnowledge"], "the active model was deactivated")
                    status, body = call("/scoutro/api/v1/kg/llm-quality/result?candidate=" + urllib.parse.quote(key, safe=""))
                    result = json.loads(body)
                    check(result["technical"]["calls"] == 10 and result["technical"]["tokens"]["prompt"] == 9000, "technical: %s" % result["technical"])
                    check(result["provenance"]["served"]["providers"] == {"FixtureProvider": 10}, "served: %s" % result["provenance"]["served"])
                    check(not result["stale"], "stale right after the check")
                    stored = list((root / "DATA/SCOUTRO/llm-quality").glob("*.json"))
                    check(len(stored) == 1, "result files: %s" % stored)
                    text = stored[0].read_text()
                    check(CANDIDATE_SECRET not in text and ACTIVE_SECRET not in text and "You extract organisations" not in text, "key or prompt stored")
                    # nothing of the graph: no entity, no document
                    status, body = call("/scoutro/api/v1/kg/entities?limit=5")
                    check(status == 200 and json.loads(body)["items"] == [], "the check wrote entities: %s" % body[:200])
                    status, body = call("/scoutro/api/v1/crawls")
                    check(status == 200 and json.loads(body)["crawls"] == [], "a crawl was started")
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
    print(f"PASS: {checks} live quality-check checks; temporary DATA and fake model removed", flush=True)


if __name__ == "__main__":
    main()
