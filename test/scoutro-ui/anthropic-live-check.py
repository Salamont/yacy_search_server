#!/usr/bin/env python3
"""Isolated Anthropic live check of the ANTHROPIC service (docs/SCOUTRO_ANTHROPIC_LIVE_TEST.md).

A disposable peer on new temporary DATA talks to Anthropic only through a local "tap": the tap forwards each request,
counts it, checks the headers Scoutro sends, reads the token usage of each answer and refuses (HTTP 429, nothing
forwarded) any request whose worst case would exceed the cost cap or the request cap. Without --live the tap answers
itself like Anthropic's OpenAI-compatible API (dry run: no network, no key, no cost).

    python3 test/scoutro-ui/anthropic-live-check.py                     # dry run
    ANTHROPIC_API_KEY=... python3 test/scoutro-ui/anthropic-live-check.py --live --max-usd 0.25 \\
        --models claude-haiku-5-5 --out /tmp/anthropic-live-report.json

Requires ant compile and JDK 17+. Never uses a production URL or DATA; the key is read from the environment only,
lives in the temporary DATA for the run and is never printed or written to the report.
Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = Path(__file__).resolve().parents[2]
JAVA = os.environ.get("JAVA", "java")
CLASSPATH = f"{REPO}/build/classes/java/main:{REPO}/lib/*"
ANTHROPIC = "https://api.anthropic.com"
# USD per million tokens (input, output), Anthropic first-party list prices as of 2026-10-06; check
# https://www.anthropic.com/pricing before a live run and pass --price MODEL=IN,OUT for any change.
PRICES = {"claude-haiku-5-5": (0.10, 0.50), "claude-sonnet-5-5": (2.00, 10.00), "claude-opus-5-5": (4.00, 20.00)}
DRY_KEY = "sk-ant-dry-run-not-a-key"


class Tap:
    """The only way out of the peer: forwards, counts, checks and prices every request; refuses beyond the caps."""

    def __init__(self, upstream, max_usd, max_requests, prices, key):
        self.upstream, self.max_usd, self.max_requests, self.prices, self.key = upstream, max_usd, max_requests, prices, key
        self.lock = threading.Lock()
        self.records = []
        self.reserved = 0.0  # worst case of the requests in flight and done, before their actual usage is known
        self.spent = 0.0     # cost from the reported usage (estimated where an answer reports none)

    def worst_case(self, body):
        model = body.get("model", "")
        if model not in self.prices:
            return None
        price_in, price_out = self.prices[model]
        tokens_in = max(1, len(json.dumps(body.get("messages", []))) // 3)  # generous: about 3 characters per token
        tokens_out = int(body.get("max_tokens") or body.get("max_completion_tokens") or 4096)
        return (tokens_in * price_in + tokens_out * price_out) / 1e6

    def admit(self, method, path, body):
        with self.lock:
            if len(self.records) >= self.max_requests:
                return None, "request_cap"
            worst = 0.0
            if method == "POST":
                worst = self.worst_case(body)
                if worst is None:
                    return None, "unpriced_model"
                if self.reserved + worst > self.max_usd:
                    return None, "cost_cap"
            self.reserved += worst
            record = {"n": len(self.records) + 1, "method": method, "path": path, "worstCaseUsd": round(worst, 6)}
            self.records.append(record)
            return record, None

    def settle(self, record, body, usage, streamed_chars):
        model = body.get("model", "") if body else ""
        price_in, price_out = self.prices.get(model, (0.0, 0.0))
        if usage:
            cost = (usage.get("prompt_tokens", 0) * price_in + usage.get("completion_tokens", 0) * price_out) / 1e6
            record["usage"] = {k: usage.get(k) for k in ("prompt_tokens", "completion_tokens", "total_tokens")}
            record["costUsd"], record["costSource"] = round(cost, 6), "usage"
        elif body:
            # a streamed answer without usage: the worst case of its input and the characters it streamed
            cost = (max(1, len(json.dumps(body.get("messages", []))) // 3) * price_in + max(1, streamed_chars // 3) * price_out) / 1e6
            record["costUsd"], record["costSource"] = round(cost, 6), "estimate"
        else:
            cost = 0.0
            record["costUsd"], record["costSource"] = 0.0, "free"
        with self.lock:
            self.reserved += cost - record["worstCaseUsd"]
            self.spent += cost


def tap_handler(tap, live):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def headers_seen(self):
            h = {k.lower(): v for k, v in self.headers.items()}
            return {"anthropic-version": h.get("anthropic-version"), "anthropic-workspace-id": h.get("anthropic-workspace-id"),
                    "authorization": "Bearer <key>" if h.get("authorization", "").startswith("Bearer ") and h["authorization"][7:] == tap.key
                    else ("other" if "authorization" in h else None), "x-api-key": "present" if "x-api-key" in h else None}

        def refuse(self, reason):
            data = json.dumps({"error": {"type": "local_cap", "message": reason}}).encode()
            self.send_response(429)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def handle_any(self, method):
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0"))) if method == "POST" else b""
            body = json.loads(raw or b"{}") if method == "POST" else None
            record, refused = tap.admit(method, self.path, body or {})
            if refused:
                tap.records.append({"method": method, "path": self.path, "refused": refused})
                return self.refuse(refused)
            record["headers"] = self.headers_seen()
            if body is not None:
                record["model"] = body.get("model")
                record["temperatureSent"] = "temperature" in body
                record["stream"] = bool(body.get("stream"))
                record["maxTokens"] = body.get("max_tokens")
            status, ctype, data = (self.forward(method, raw) if live else self.fake(body))
            record["status"] = status
            usage, chars = None, 0
            text = data.decode("utf-8", "replace")
            if "event-stream" in ctype:
                for line in text.splitlines():
                    if line.startswith("data: ") and line[6:].strip() != "[DONE]":
                        try:
                            chunk = json.loads(line[6:])
                        except ValueError:
                            continue
                        usage = chunk.get("usage") or usage
                        for c in chunk.get("choices") or []:
                            chars += len(((c.get("delta") or {}).get("content")) or "")
            else:
                try:
                    usage = json.loads(text).get("usage")
                except ValueError:
                    usage = None
            tap.settle(record, body if status == 200 else None, usage if status == 200 else None, chars)
            if status != 200:
                record["error"] = text[:300].replace(tap.key, "<key>")
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def forward(self, method, raw):
            headers = {k: v for k, v in self.headers.items() if k.lower() in (
                "authorization", "anthropic-version", "anthropic-workspace-id", "content-type", "accept")}
            request = urllib.request.Request(ANTHROPIC + self.path, data=raw if method == "POST" else None, headers=headers, method=method)
            try:
                with urllib.request.urlopen(request, timeout=120, context=ssl.create_default_context()) as response:
                    return response.status, response.headers.get("Content-Type", "application/json"), response.read()
            except urllib.error.HTTPError as error:
                return error.code, error.headers.get("Content-Type", "application/json"), error.read()

        def fake(self, body):
            path = urllib.parse.urlsplit(self.path).path
            if body is None:
                if path != "/v1/models":
                    return 404, "application/json", b'{"error":{"type":"not_found_error"}}'
                return 200, "application/json", json.dumps({"data": [{"type": "model", "id": m} for m in PRICES],
                                                            "has_more": False}).encode()
            usage = {"prompt_tokens": max(1, len(json.dumps(body.get("messages", []))) // 4), "completion_tokens": 3}
            usage["total_tokens"] = usage["prompt_tokens"] + 3
            if body.get("stream"):
                return 200, "text/event-stream", ('data: {"choices":[{"index":0,"delta":{"content":"ok"}}]}\n\n'
                                                  'data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":'
                                                  + json.dumps(usage) + '}\n\ndata: [DONE]\n\n').encode()
            content = '{"result":"ok"}' if "response_format" in body else "ok"
            return 200, "application/json", json.dumps({"id": "msg_dry", "object": "chat.completion", "model": body.get("model"),
                                                        "choices": [{"index": 0, "message": {"role": "assistant", "content": content},
                                                                     "finish_reason": "stop"}], "usage": usage}).encode()

        def do_GET(self):
            self.handle_any("GET")

        def do_POST(self):
            self.handle_any("POST")

    return Handler


def start_peer(root, port):
    (root / ".scoutro-dashboard-disposable").touch()
    config = root / "DATA/SETTINGS/yacy.conf"
    config.parent.mkdir(parents=True)
    (root / "DATA/DICTIONARIES/harvesting").mkdir(parents=True, exist_ok=True)
    for friends in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
        (root / "DATA/DICTIONARIES/harvesting" / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
    config.write_text("\n".join([
        f"port={port}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
        "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
        "network.unit.definition=defaults/yacy.network.webportal.unit", "browserPopUpTrigger=false",
        "autocrawl=false", "server.https=false", "upnp.enabled=false", "donation.iframesource=",
        "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
        "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
        "ai.production_models=[]", "ai.inference_system={}", "ai.model_capabilities={}",
        # the knowledge graph stays off and no Discovery job exists: the model is called only where this run says so
        "scoutro.kg.enabled=false",
    ]) + "\n")
    subprocess.run([JAVA, "-cp", CLASSPATH, str(REPO / "test/scoutro-ui/DashboardFixture.java"), str(root)],
                   cwd=REPO, check=True, timeout=60, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    log = (root / "peer.log").open("w")
    return subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", CLASSPATH, "net.yacy.yacy", "-startup", str(root)],
                            cwd=REPO, stdout=log, stderr=log)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--live", action="store_true", help="forward to api.anthropic.com (costs money); default: dry run")
    parser.add_argument("--models", default="claude-haiku-5-5", help="comma-separated model ids (default: claude-haiku-5-5)")
    parser.add_argument("--max-usd", type=float, default=0.25, help="hard cost cap of the whole run (default 0.25)")
    parser.add_argument("--max-requests", type=int, default=12, help="hard cap of upstream requests (default 12)")
    parser.add_argument("--price", action="append", default=[], help="MODEL=IN,OUT in USD per million tokens")
    parser.add_argument("--out", help="write the JSON report here")
    args = parser.parse_args()
    prices = dict(PRICES)
    for p in args.price:
        model, value = p.split("=", 1)
        prices[model] = tuple(float(x) for x in value.split(","))
    models = [m.strip() for m in args.models.split(",") if m.strip()]
    for m in models:
        if m not in prices:
            sys.exit("no price for %s: pass --price %s=IN,OUT" % (m, m))
    key = os.environ.get("ANTHROPIC_API_KEY", "") if args.live else DRY_KEY
    if args.live and not key:
        sys.exit("--live needs ANTHROPIC_API_KEY in the environment")
    if args.max_usd <= 0 or args.max_usd > 5:
        sys.exit("--max-usd must be in (0, 5]")
    workspace = os.environ.get("ANTHROPIC_WORKSPACE_ID", "")

    tap = Tap(ANTHROPIC, args.max_usd, args.max_requests, prices, key)
    tap_server = ThreadingHTTPServer(("127.0.0.1", 0), tap_handler(tap, args.live))
    threading.Thread(target=tap_server.serve_forever, daemon=True).start()
    tap_stub = f"http://127.0.0.1:{tap_server.server_port}"
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    password = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    password.add_password(None, base, "admin", "yacy")
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(password))
    plain = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(path, body=None, headers=None, auth=True):
        request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                         headers=dict({"Content-Type": "application/json"} if body is not None else {}, **(headers or {})))
        try:
            with (client if auth else plain).open(request, timeout=180) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    report = {"mode": "live" if args.live else "dry-run", "models": models, "maxUsd": args.max_usd, "maxRequests": args.max_requests,
              "prices": {m: prices[m] for m in models}, "priceSource": "Anthropic list prices 2026-10-06 unless --price",
              "workspaceHeader": bool(workspace), "steps": [], "checks": []}
    failures = []

    def check(ok, what):
        report["checks"].append({"ok": bool(ok), "check": what})
        if not ok:
            failures.append(what)

    process = None
    try:
        with tempfile.TemporaryDirectory(prefix="scoutro-anthropic-live-") as temporary:
            root = Path(temporary)
            process = start_peer(root, port)
            if workspace:
                with (root / "DATA/SETTINGS/yacy.conf").open("a") as conf:
                    conf.write("ai.anthropic.workspace_id=" + workspace + "\n")
            deadline = time.monotonic() + 90
            while True:
                if process.poll() is not None:
                    raise RuntimeError("peer stopped: " + (root / "peer.log").read_text()[-3000:])
                try:
                    if call("/api/version.xml")[0] == 200:
                        break
                except OSError:
                    pass
                if time.monotonic() > deadline:
                    raise RuntimeError("peer did not start")
                time.sleep(0.5)
            q = "?hoststub=" + urllib.parse.quote(tap_stub, safe="")
            # 1. the model list through the admin passthrough, as the LLM selection page asks for it
            status, body = call("/v1/models" + q, headers={"X-LLM-Service": "ANTHROPIC", "X-LLM-Api-Key": key})
            ids = [m.get("id") for m in json.loads(body).get("data", [])] if status == 200 else []
            report["steps"].append({"step": "models", "status": status, "count": len(ids)})
            check(status == 200, "model list answered 200")
            for m in models:
                check(m in ids, "model list names " + m)
            for m in models:
                # 2. one saved production row per model (chat only), so the RAG chat routes to it
                row = {"service": "ANTHROPIC", "model": m, "hoststub": tap_stub, "api_key": key, "max_tokens": "1024",
                       "chat": True, "tldr": False, "logreport": False, "tooling": False}
                call("/LLMSelection_p.html", {"production_models": [row], "inference_system": {"service": "ANTHROPIC", "hoststub": tap_stub}})
                time.sleep(1.5)
                # 3. a plain chat probe (admin passthrough), 4. the format probe (response_format, which Anthropic ignores)
                probe = {"model": m, "max_tokens": 1024, "stream": False, "messages": [{"role": "user", "content": "Antworte nur mit: ok"}]}
                status, body = call("/v1/chat/completions" + q, probe)
                content = json.loads(body)["choices"][0]["message"].get("content", "") if status == 200 else ""
                report["steps"].append({"step": "chat", "model": m, "status": status, "chars": len(content)})
                check(status == 200 and content.strip(), m + ": chat answered")
                fmt = {"model": m, "max_tokens": 256, "stream": False, "messages": [{"role": "system", "content": "You are a test endpoint."},
                       {"role": "user", "content": "Return the required JSON object."}], "response_format": {"type": "json_schema",
                       "json_schema": {"name": "scoutro_format_probe", "strict": True, "schema": {"type": "object",
                       "properties": {"result": {"type": "string", "enum": ["ok"]}}, "required": ["result"], "additionalProperties": False}}}}
                status, body = call("/v1/chat/completions" + q, fmt)
                answer = json.loads(body)["choices"][0]["message"].get("content", "") if status == 200 else ""
                try:
                    compliant = json.loads(answer.strip()) == {"result": "ok"}
                except ValueError:
                    compliant = False
                report["steps"].append({"step": "format", "model": m, "status": status, "compliantAnswer": compliant,
                                        "scoutroShows": "ignored" if status == 200 else "unknown"})
                check(status in (200, 400), m + ": format probe answered (" + str(status) + ")")
                # 5. the RAG chat (public path, streamed, through ToolCallProtocol), without search
                status, body = call("/v1/chat/completions", {"model": "chat", "stream": True, "max_tokens": 1024, "messages": [
                    {"role": "user", "content": "Sag hallo in einem Wort.", "search": "no"}]}, auth=False)
                report["steps"].append({"step": "rag-chat", "model": m, "status": status, "done": b"[DONE]" in body})
                check(status == 200 and b"[DONE]" in body, m + ": RAG chat streamed")
            call("/LLMSelection_p.html", {"production_models": [], "inference_system": {}})
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
        tap_server.shutdown()
    upstream = [r for r in tap.records if "refused" not in r]
    for r in upstream:
        h = r.get("headers", {})
        check(h.get("anthropic-version") == "2023-06-01", "request %d carries anthropic-version" % r["n"])
        check(h.get("authorization") == "Bearer <key>" and h.get("x-api-key") is None, "request %d: Bearer key, no x-api-key" % r["n"])
        if r["method"] == "POST":
            check(not r.get("temperatureSent"), "request %d sends no temperature" % r["n"])
    check(not any("refused" in r for r in tap.records), "no request was refused by the local caps")
    report["upstream"] = tap.records
    report["costUsd"] = round(tap.spent, 6)
    report["result"] = "pass" if not failures else "fail"
    text = json.dumps(report, indent=2).replace(key, "<key>")
    if args.out:
        Path(args.out).write_text(text + "\n")
    print("%s: %s, %d upstream requests, %d checks, cost %.6f USD (cap %.4f)%s" % (
        report["mode"], report["result"].upper(), len(upstream), len(report["checks"]), tap.spent, args.max_usd,
        "" if not failures else "\nFAILED: " + "; ".join(failures)), flush=True)
    sys.exit(0 if not failures else 1)


if __name__ == "__main__":
    main()
