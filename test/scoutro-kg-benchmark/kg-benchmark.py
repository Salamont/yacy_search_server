#!/usr/bin/env python3
"""Knowledge graph A/B benchmark: what the chat model receives with and without graph facts (package 6).

Starts a NEW disposable peer (temporary DATA, 127.0.0.1 only), indexes corpus.json through
YaCy's parser (api/push_p), waits for the knowledge graph and its derived layer, then asks
every question of catalog.json through the real chat endpoint (/v1/chat/completions, in the
question's collection) twice: with scoutro.kg.chat.enabled=false and =true. Between the two
the peer is restarted on the same DATA, so index, catalog, model settings and data are
identical; the setting is read before and restored exactly afterwards (its line in
yacy.conf, byte for byte). The chat model is a local recording endpoint: the harness keeps
exactly the messages the model would have received, and the time Scoutro needed to build
them. A model answers them afterwards (answer.py prepares the tasks, score.py scores them).

Usage: python3 test/scoutro-kg-benchmark/kg-benchmark.py --out contexts.json
No production URL, DATA or credential is used or accepted; nothing is fetched from the web.
GPL-2.0-or-later.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
JAVA = os.environ.get("JAVA", "java")
CLASSPATH = f"{REPO}/build/classes/java/main:{REPO}/lib/*"
SYSTEM = "You are a smart and helpful chatbot. If possible, use friendly emojies."  # the chat page's default
CHAT_KEY = "scoutro.kg.chat.enabled"
recorded = []


class ModelEndpoint(BaseHTTPRequestHandler):
    """Records every chat request and answers with a fixed text (the answers come later from a real model)."""
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        recorded.append((time.monotonic(), body))
        chunks = [{"choices": [{"delta": {"role": "assistant", "content": "recorded"}}]},
                  {"choices": [{"delta": {}, "finish_reason": "stop"}]}]
        data = "".join("data: " + json.dumps(c) + "\n\n" for c in chunks).encode() + b"data: [DONE]\n\n"
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class Peer:
    def __init__(self, root, port):
        self.root, self.port, self.base, self.process = root, port, f"http://127.0.0.1:{port}", None
        pw = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        pw.add_password(None, self.base, "admin", "yacy")
        self.admin = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(pw))
        self.local = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def start(self):
        env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
        self.log = (self.root / "peer.log").open("a")
        self.process = subprocess.Popen([JAVA, "-Xmx1g", "-Djava.awt.headless=true", "-cp", CLASSPATH, "net.yacy.yacy", "-startup",
                                         str(self.root)], cwd=REPO, stdout=self.log, stderr=self.log, env=env)
        deadline = time.monotonic() + 120
        while True:
            if self.process.poll() is not None:
                raise RuntimeError((self.root / "peer.log").read_text()[-4000:])
            try:
                s = self.get("/scoutro/api/v1/kg/status")
                if s.get("state") == "running" and (s.get("sync") or {}).get("initialized"):
                    return s
            except (OSError, urllib.error.URLError, ValueError):
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("peer did not start: " + (self.root / "peer.log").read_text()[-4000:])
            time.sleep(0.5)

    def stop(self):
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=90)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.process = None
        self.log.close()

    def get(self, path):
        with self.admin.open(self.base + path, timeout=60) as r:
            return json.loads(r.read())

    def post(self, path, body):
        req = urllib.request.Request(self.base + path, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "Origin": self.base})
        with self.admin.open(req, timeout=60) as r:
            return json.loads(r.read())

    def push(self, url, html, collection):
        boundary = f"----scoutro{time.time_ns()}"
        fields = {"count": "1", "synchronous": "true", "commit": "true", "url-0": url, "collection-0": collection,
                  "contentType-0": "text/html", "lastModified-0": "Tue, 15 Nov 1994 12:45:26 GMT", "responseHeader-0": ""}
        body = b"".join(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode() for k, v in fields.items())
        body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"data-0\"; filename=\"page.html\"\r\n"
                 "Content-Type: text/html\r\n\r\n").encode() + html.encode() + f"\r\n--{boundary}--\r\n".encode()
        req = urllib.request.Request(self.base + "/api/push_p.json", data=body, method="POST",
                                     headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
        with self.admin.open(req, timeout=120) as r:
            assert json.loads(r.read()).get("countsuccess") == 1, url

    def wait(self, what, predicate, timeout=300):
        deadline = time.monotonic() + timeout
        while True:
            s = self.get("/scoutro/api/v1/kg/status")
            if predicate(s):
                return s
            if time.monotonic() > deadline:
                raise AssertionError(f"timed out waiting for {what}: {json.dumps(s.get('sync', {}).get('processed'))}")
            time.sleep(0.5)

    def chat(self, question, collection):
        """One question as the chat page asks it (local request: the graph facts are allowed); returns what the model got."""
        body = {"model": "chat", "stream": True, "collection": collection,
                "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": question, "search": "local"}]}
        req = urllib.request.Request(self.base + "/v1/chat/completions", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
        before = len(recorded)
        start = time.monotonic()
        with self.local.open(req, timeout=300) as r:
            stream = r.read().decode()
        meta = {}
        for line in stream.splitlines():
            if line.startswith("data:") and "[DONE]" not in line:
                try:
                    event = json.loads(line[5:].strip())
                except ValueError:
                    continue
                for key in ("scoutro-sources", "scoutro-graph", "scoutro-retrieval"):
                    if key in event:
                        meta[key] = event[key]
        if len(recorded) <= before:
            raise AssertionError("the model was not called for: " + question)
        at, sent = recorded[before]
        return sent, meta, round((at - start) * 1000)


def page(d):
    return (f"<html><head><title>{d['title']}</title>\n<script type=\"application/ld+json\">{d['jsonld']}</script></head>\n"
            f"<body><h1>{d['title']}</h1><p>{d['text']}</p></body></html>")


def config_lines(port, stub, chat, network):
    row = {"service": "OLLAMA", "model": "kg-benchmark", "hoststub": stub, "api_key": "", "max_tokens": "1024",
           "chat": True, "tldr": False, "logreport": False, "search": False, "translation": False, "classification": False,
           "query": False, "qapairs": False, "thinking": False, "tooling": False, "vision": False, "format": False, "knowledge": False}
    return [f"port={port}", "adminAccountForLocalhost=false", "adminAccountAllPages=false", "adminAccountUserName=admin",
            "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c", f"network.unit.definition={network}",
            "browserPopUpTrigger=false", "autocrawl=false", "server.https=false", "locale.language=default", "upnp.enabled=false",
            "donation.iframesource=", "scoutro.discovery.enabled=false", "search.verify=false",
            "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
            "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
            "scoutro.kg.enabled=true", "scoutro.kg.collections=edelsenior-web,stackfinder-web,bauteamcheck-web,checkthecoach-web",
            "scoutro.kg.jsonld.enabled=true", "scoutro.kg.jobs.collections=edelsenior-web,bauteamcheck-web",
            "ai.production_models=" + json.dumps([row], separators=(",", ":")), "ai.service_num_ctx=" + json.dumps({stub: 8192})] + (
            [] if chat is None else [f"{CHAT_KEY}={'true' if chat else 'false'}"])


def set_chat(config, value):
    """Sets the chat switch in yacy.conf (the peer is stopped); returns the previous line or None."""
    lines = config.read_text().splitlines()
    previous = next((l for l in lines if l.startswith(CHAT_KEY + "=")), None)
    lines = [l for l in lines if not l.startswith(CHAT_KEY + "=")] + [f"{CHAT_KEY}={value}"]
    config.write_text("\n".join(lines) + "\n")
    return previous


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    corpus = json.loads((HERE / "corpus.json").read_text())["documents"]
    catalog = json.loads((HERE / "catalog.json").read_text())
    model = ThreadingHTTPServer(("127.0.0.1", 0), ModelEndpoint)
    threading.Thread(target=model.serve_forever, daemon=True).start()
    stub = f"http://127.0.0.1:{model.server_port}"
    port = free_port()
    out = {"note": "What the chat model receives per question, with the graph facts off and on; same peer, index and settings.",
           "corpus_documents": len(corpus), "questions": []}
    with tempfile.TemporaryDirectory(prefix="scoutro-kg-benchmark-") as temporary:
        root = Path(temporary)
        config = root / "DATA/SETTINGS/yacy.conf"
        config.parent.mkdir(parents=True)
        (root / "DATA/DICTIONARIES/harvesting").mkdir(parents=True)
        for friends in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
            (root / "DATA/DICTIONARIES/harvesting" / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
        # the .example hosts are no global domains: the fixture network accepts any domain (like the RAG catalog)
        network = root / "fixture.network.unit"
        network.write_text((REPO / "defaults/yacy.network.webportal.unit").read_text().replace(
            "network.unit.domain = global", "network.unit.domain = any"))
        config.write_text("\n".join(config_lines(port, stub, None, network)) + "\n")
        original = config.read_bytes()
        peer = Peer(root, port)
        try:
            # 1. index the corpus once, with the switch as configured (absent: the default), and wait for the graph
            peer.start()
            status = peer.get("/scoutro/api/v1/kg/status")
            out["chat_setting_before"] = status["config"]["chat"]["enabled"]
            for d in corpus:
                peer.push(d["url"], page(d), d["collection"])
            peer.wait("the graph", lambda s: s["sync"]["processed"]["published"] >= len(corpus)
                      and s["sync"]["lag"]["pending"] == 0 and not s["sync"]["lag"]["reconcile_pending"])
            before = (peer.get("/scoutro/api/v1/kg/status").get("derived") or {}).get("lastRun") or 0
            peer.post("/scoutro/api/v1/kg/control", {"action": "derive"})
            s = peer.wait("the derived layer", lambda s: ((s.get("derived") or {}).get("lastRun") or 0) > before)
            out["graph"] = {"entities": peer.get("/scoutro/api/v1/kg/entities?limit=1")["total"], "derived": s["derived"]["last"],
                            "vocabulary": s["vocabulary"]["collections"]}
            peer.stop()
            # 2. the same peer, index and settings, the graph facts off and then on
            runs = {}
            for mode, value in (("off", "false"), ("on", "true")):
                set_chat(config, value)
                peer.start()
                assert peer.get("/scoutro/api/v1/kg/status")["config"]["chat"]["enabled"] == (value == "true")
                # YaCy's search feed is asynchronous on its first request: warm it up
                for _ in range(20):
                    sent, _, _ = peer.chat("Pflege Köln", "edelsenior-web")
                    if "Source:" in json.dumps(sent):
                        break
                    time.sleep(0.5)
                runs[mode] = {}
                for qu in catalog["questions"]:
                    sent, meta, millis = peer.chat(qu["question"], qu["collection"])
                    runs[mode][qu["id"]] = {"messages": sent["messages"], "context_ms": millis,
                                            "graph": meta.get("scoutro-graph"), "sources": meta.get("scoutro-sources"),
                                            "retrieval": meta.get("scoutro-retrieval")}
                peer.stop()
            # 3. the switch exactly as before
            config.write_bytes(original)
            assert config.read_bytes() == original
            out["chat_setting_restored"] = True
        except BaseException:
            print("--- end of peer.log ---\n" + (root / "peer.log").read_text(errors="replace")[-20000:], flush=True)
            raise
        finally:
            if peer.process is not None:
                peer.stop()
            model.shutdown()
    for qu in catalog["questions"]:
        out["questions"].append({"id": qu["id"], "collection": qu["collection"], "question": qu["question"],
                                 "off": runs["off"][qu["id"]], "on": runs["on"][qu["id"]]})
    Path(args.out).write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
    facts_on = sum((q["on"]["graph"] or {}).get("facts", 0) for q in out["questions"])
    facts_off = sum((q["off"]["graph"] or {}).get("facts", 0) for q in out["questions"])
    print(f"PASS: {len(out['questions'])} questions recorded twice (graph facts off: {facts_off}, on: {facts_on}); "
          f"setting before: {out['chat_setting_before']}, restored: {out['chat_setting_restored']}")


if __name__ == "__main__":
    main()
