#!/usr/bin/env python3
"""Disposable live harness for knowledge graph package 4: agents, export, change feed, chat. GPL-2.0-or-later.

Starts a NEW temporary peer that follows the collections kga and kgb (LLM tier
for kga, fake OpenAI-compatible model on 127.0.0.1 for extraction and chat),
indexes three pages through YaCy's parser (api/push_p) and checks:

* agents created in the wizard: kg.read and kg.export are separate grants, an
  agent of kga reads nothing of kgb in the read routes, the export and the
  change feed, foreign collections are 403, administrator routes 404;
* scoutroctl and the MCP adapter with the agent token;
* the administrator download (NDJSON and JSON) and that it needs the login;
* the RAG chat: graph facts as labelled, numbered sources for local access in
  the requested collection only, none for an AI Shield guest; then the chat
  page in Chromium (test/scoutro-ui/chat-graph-ui-test.mjs).

Nothing leaves the machine; no existing peer or DATA directory is touched.
Requires `ant compile`, a JDK, Node with Playwright and Chromium. Optional:
JAVA, NODE_PATH, SCOUTRO_CHROMIUM_PATH.
"""
import html
import http.server
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
import urllib.parse
import urllib.request

REPO = Path(__file__).resolve().parents[2]
JAVA = os.environ.get("JAVA", "java")
CLASSPATH = f"{REPO}/build/classes/java/main:{REPO}/lib/*"


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


PORT, LLM_PORT = free_port(), free_port()
BASE = f"http://127.0.0.1:{PORT}"
HOST_A = "www.muster-pflege-ag.de"
HOST_B = "www.nur-bee-ag.de"
SECRETS_B = ["Geheime Holding", "DE123456789", "+49409999999", "Nur Bee", "nur-bee", HOST_B, "ueber-uns", '"kgb"']
CHAT_PROMPTS = []
GRAPH_ENTRY = re.compile(r"\[(\d+)\] Scoutro knowledge graph: ")


class FakeModel(http.server.BaseHTTPRequestHandler):
    """Extraction (no stream): reads 'operates Haus Lindenhof'. Chat (stream): cites the first graph entry."""

    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        if body.get("stream"):
            prompt = body["messages"][-1]["content"]
            CHAT_PROMPTS.append(prompt)
            m = GRAPH_ENTRY.search(prompt)
            answer = f"Laut Scoutro-Wissensgraph betreibt sie das Haus Lindenhof [{m.group(1)}]." if m else "Keine Graph-Fakten."
            data = ("data: " + json.dumps({"choices": [{"delta": {"content": answer}}]}) + "\n\ndata: [DONE]\n\n").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        user = body["messages"][1]["content"]
        answer = {"entities": [], "claims": []}
        if "Haus Lindenhof" in user:
            answer = {"entities": [{"id": "e1", "type": "facility", "name": "Haus Lindenhof", "kind": "nursinghome",
                                    "quote": "betreibt das Haus Lindenhof in Berlin"}],
                      "claims": [{"subject": "k1", "predicate": "operates", "object": "e1",
                                  "quote": "Die Muster Pflege gGmbH betreibt das Haus Lindenhof"}]}
        out = json.dumps({"choices": [{"message": {"content": json.dumps(answer)}, "finish_reason": "stop"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)


def page(name, host, extra, text, phone="030 1234567", title="Impressum"):
    return f"""<html><head><title>{title} {name}</title>
<script type="application/ld+json">{{"@context":"https://schema.org","@type":"Organization","name":"{name}",
"url":"https://{host}/","telephone":"{phone}"{extra}}}</script></head>
<body><h1>Impressum</h1><p>{text}</p></body></html>"""


PAGES = [
    (f"https://{HOST_A}/impressum", "kga", page("Muster Pflege gGmbH", HOST_A, "",
                                                 "Die Muster Pflege gGmbH betreibt das Haus Lindenhof in Berlin.")),
    (f"https://{HOST_A}/ueber-uns", "kgb", page("Muster Pflege gGmbH", HOST_A,
                                                 ',"alternateName":"Geheime Holding","vatID":"DE123456789"', "Über uns.", phone="040 9999999")),
    (f"https://{HOST_B}/impressum", "kgb", page("Nur Bee GmbH", HOST_B, "", "Die Nur Bee GmbH berät.")),
]


def admin():
    pw = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    pw.add_password(None, BASE, "admin", "yacy")
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(pw))


ANON = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(opener, method, path, body=None, headers=None):
    request = urllib.request.Request(BASE + path, method=method, headers=dict(headers or {}),
                                     data=None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode()))
    if body is not None and not isinstance(body, bytes):
        request.add_header("Content-Type", "application/json")
    try:
        with opener.open(request, timeout=60) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def get(c, path):
    status, _, raw = call(c, "GET", path)
    assert status == 200, (path, status, raw[:500])
    return json.loads(raw)


def agent(token, path, **query):
    url = "/scoutro/api/agent/v1" + path + ("?" + urllib.parse.urlencode(query) if query else "")
    status, _, raw = call(ANON, "GET", url, headers={"Authorization": "Bearer " + token})
    return status, json.loads(raw) if raw.strip().startswith(b"{") else raw


def push(c, url, html_text, collection):
    boundary = f"----scoutro{time.time_ns()}"
    fields = {"count": "1", "synchronous": "true", "commit": "true", "url-0": url, "collection-0": collection,
              "contentType-0": "text/html", "lastModified-0": "Tue, 15 Nov 1994 12:45:26 GMT", "responseHeader-0": ""}
    body = b"".join(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
                    for k, v in fields.items())
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"data-0\"; filename=\"page.html\"\r\n"
             "Content-Type: text/html\r\n\r\n").encode() + html_text.encode() + f"\r\n--{boundary}--\r\n".encode()
    status, _, raw = call(c, "POST", "/api/push_p.json", body, {"Content-Type": f"multipart/form-data; boundary={boundary}"})
    assert status == 200 and json.loads(raw).get("countsuccess") == 1, (status, raw[:300])


def wait(c, what, predicate, timeout=180):
    deadline = time.monotonic() + timeout
    while True:
        s = get(c, "/scoutro/api/v1/kg/status")
        if predicate(s):
            return s
        if time.monotonic() > deadline:
            raise AssertionError(f"timed out waiting for {what}: {json.dumps(s.get('sync', {}).get('processed'))} {json.dumps(s.get('llm'))[:600]}")
        time.sleep(0.5)


class Wizard:
    """Walks through ScoutroAgentWizard_p.html like a browser (as test/scoutro-api/test_agent_api.py)."""

    def __init__(self, c):
        self.c = c
        status, headers, raw = call(c, "GET", "/ScoutroAgentWizard_p.html")
        assert status == 200, status
        self.token = headers.get("X-YaCy-Transaction-Token")
        self.draft = ""

    def post(self, step, fields):
        form = {"transactionToken": self.token, "draft": self.draft, "step": str(step), "next": "Next"}
        form.update(fields)
        status, headers, raw = call(self.c, "POST", "/ScoutroAgentWizard_p.html", urllib.parse.urlencode(form).encode(),
                                    {"Content-Type": "application/x-www-form-urlencoded"})
        assert status == 200, status
        text = raw.decode()
        m = re.search(r'name="draft" value="([^"]*)"', text)
        self.draft = html.unescape(m.group(1)) if m else ""
        self.token = headers.get("X-YaCy-Transaction-Token", self.token)
        return text


def create_agent(c, name, actions, kind="external", collections=("kga",)):
    w = Wizard(c)
    w.post(1, {"name": name, "description": "package 4 smoke", "kind": kind})
    w.post(2, {"scopeForm": "1", "extraCollections": ",".join(collections)})
    step3 = w.post(3, dict({"actionsForm": "1", "preset": "custom"}, **{"act_" + a: "on" for a in actions}))
    w.post(4, {"limitsForm": "1", "domains": "", "maxDepth": "1", "maxPages": "20", "maxParallelCrawls": "1",
               "requestsPerMinute": "600", "maxTaskSeconds": "300"})
    result = w.post(5, {"expiresInDays": "30"})
    m = re.search(r'id="createdToken" readonly="readonly" size="70" value="([^"]+)"', result)
    assert m, "no token for " + name + ":\n" + step3[-1500:] + result[-1500:]
    return html.unescape(m.group(1))


def no_secrets(where, text):
    for secret in SECRETS_B:
        assert secret not in text, f"{where} reveals {secret}: {text[:1500]}"


def chat(question, collection=None, headers=None, opener=ANON):
    body = {"model": "chat", "stream": True, "messages": [{"role": "user", "content": question, "search": "local"}]}
    if collection:
        body["collection"] = collection
    status, _, raw = call(opener, "POST", "/v1/chat/completions", body, headers)
    assert status == 200, (status, raw[:500])
    meta = {}
    for line in raw.decode().splitlines():
        if line.startswith("data: {"):
            chunk = json.loads(line[6:])
            for key in ("scoutro-graph", "scoutro-sources", "scoutro-citations", "scoutro-retrieval"):
                if key in chunk:
                    meta[key] = chunk[key]
    return meta, CHAT_PROMPTS[-1] if CHAT_PROMPTS else ""


server = http.server.ThreadingHTTPServer(("127.0.0.1", LLM_PORT), FakeModel)
threading.Thread(target=server.serve_forever, daemon=True).start()
with tempfile.TemporaryDirectory(prefix="scoutro-kg-agents-") as temporary:
    root = Path(temporary)
    config = root / "DATA/SETTINGS/yacy.conf"
    config.parent.mkdir(parents=True)
    (root / "DATA/DICTIONARIES/harvesting").mkdir(parents=True, exist_ok=True)
    for friends in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
        (root / "DATA/DICTIONARIES/harvesting" / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
    models = [{"service": "OLLAMA", "model": "fixture", "hoststub": f"http://127.0.0.1:{LLM_PORT}", "api_key": "",
               "max_tokens": "512", "chat": True, "tldr": False, "logreport": False, "knowledge": True}]
    config.write_text("\n".join([
        f"port={PORT}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
        "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
        "network.unit.definition=defaults/yacy.network.webportal.unit", "browserPopUpTrigger=false",
        "autocrawl=false", "server.https=false", "locale.language=browser", "upnp.enabled=false", "donation.iframesource=",
        "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
        "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
        "ai.shield.allow-nonlocalhost=true", "ai.shield.guest-collections=kga",
        "scoutro.kg.enabled=true", "scoutro.kg.collections=kga,kgb", "scoutro.kg.jsonld.enabled=true",
        "scoutro.kg.llm.collections=kga", "scoutro.kg.llm.kinds.kga=nursinghome", "scoutro.kg.chat.timeoutMs=2000",
        "ai.production_models=" + json.dumps(models, separators=(",", ":")),
    ]) + "\n")
    (root / "DATA/LOCALE/htroot/de").mkdir(parents=True)
    process = None
    checks = 0
    with (root / "peer.log").open("w") as log:
        try:
            process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", CLASSPATH,
                                        "net.yacy.yacy", "-startup", str(root)], cwd=REPO, stdout=log, stderr=log)
            c = admin()
            deadline = time.monotonic() + 90
            while True:
                if process.poll() is not None:
                    raise RuntimeError((root / "peer.log").read_text()[-5000:])
                try:
                    get(c, "/scoutro/api/v1/kg/status")
                    break
                except (OSError, urllib.error.URLError, AssertionError):
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(0.5)
            wait(c, "the start backfill", lambda s: s.get("sync", {}).get("reconcile", {}).get("last") is not None)
            for url, collection, text in PAGES:
                push(c, url, text, collection)
            wait(c, "three published pages and the LLM result",
                 lambda s: s["sync"]["processed"]["published"] >= 3 and s["llm"]["processed"]["published"] >= 1)

            # ---- agents created in the wizard
            both = create_agent(c, "kg both", ["kg.read", "kg.export"])
            read_only = create_agent(c, "kg read", ["kg.read"])
            everything = create_agent(c, "kg all b", ["kg.read", "kg.export"], collections=("kga", "kgb"))
            status, caps = agent(both, "/capabilities")
            assert status == 200 and {"kg.read", "kg.export"} <= {a["name"] for a in caps["actions"]}, caps
            checks += 1

            status, page1 = agent(both, "/kg/entities", limit=100)
            assert status == 200 and "lag" not in page1, page1
            names = sorted(e["name"] for e in page1["items"])
            assert "Muster Pflege gGmbH" in names and "Nur Bee GmbH" not in names, names
            org = next(e for e in page1["items"] if e["name"] == "Muster Pflege gGmbH")
            seen = json.dumps(page1)
            for path in [f"/kg/entities/{org['id']}", f"/kg/entities/{org['id']}/statements", f"/kg/hosts/{HOST_A}/entities"]:
                status, body = agent(both, path)
                assert status == 200, (path, status, body)
                seen += json.dumps(body)
            status, facts = agent(both, f"/kg/entities/{org['id']}/statements", limit=100)
            operates = next(f for f in facts["items"] if f["predicate"] == "operates")
            assert operates["quality"] == "uncertain" and operates["kinds"] == ["llm"], operates
            status, ev = agent(both, f"/kg/statements/{operates['id']}/evidence")
            assert status == 200 and ev["items"][0]["extractor"].startswith("llm/") and "fixture" not in json.dumps(ev) \
                and "OLLAMA" not in json.dumps(ev), ev
            seen += json.dumps(ev)
            no_secrets("agent of kga (reads)", seen)
            # the administrator sees the model in the same evidence
            admin_ev = get(c, f"/scoutro/api/v1/kg/statements/{operates['id']}/evidence")
            assert "fixture" in admin_ev["items"][0]["extractor"], admin_ev
            checks += 6

            status, body = agent(both, "/kg/entities", collection="kgb")
            assert status == 403 and body["error"]["code"] == "collection_not_in_scope", body
            status, body = agent(read_only, "/kg/export")
            assert status == 403 and body["error"]["code"] == "action_not_granted", body
            status, body = agent(read_only, "/kg/changes")
            assert status == 403 and body["error"]["code"] == "action_not_granted", body
            for path in ["/kg/status", "/kg/control", "/kg/export/download"]:
                status, body = agent(both, path)
                assert status == 404, (path, status, body)
            status, body = agent(everything, "/kg/entities", q="Nur")
            assert status == 200 and body["total"] == 1, body
            bee = body["items"][0]["id"]
            status, body = agent(both, f"/kg/entities/{bee}")
            assert status == 404, (status, body)
            status, body = agent(both, "/kg/sources/AAAAAAAAAAAA")
            assert status == 404, (status, body)
            checks += 7

            # ---- export and change feed of the kga agent
            records, cursor, next_changes = [], None, None
            while True:
                query = {"limit": 1, "include": "evidence"}
                if cursor:
                    query["cursor"] = cursor
                status, p = agent(both, "/kg/export", **query)
                assert status == 200, p
                records += p["items"]
                next_changes = p["next_changes"]
                if p["complete"]:
                    break
                cursor = p["next"]
            assert any(r["record"] == "entity" and r["name"] == "Muster Pflege gGmbH" for r in records), records
            assert any(r["record"] == "statement" and r["evidence"] for r in records), records
            no_secrets("agent of kga (export)", json.dumps(records))
            push(c, f"https://{HOST_A}/neu", page("Neu Pflege Lindenhof GmbH", HOST_A, "", "Neu.", title="Neu"), "kga")
            push(c, f"https://{HOST_B}/impressum", page("Nur Bee GmbH", HOST_B, ',"vatID":"DE987654321"', "Die Nur Bee GmbH berät."), "kgb")
            wait(c, "the two changes", lambda s: s["sync"]["processed"]["published"] >= 5)
            status, ch = agent(both, "/kg/changes", cursor=next_changes, expand="true", limit=100)
            assert status == 200, ch
            text = json.dumps(ch)
            assert "Neu Pflege Lindenhof GmbH" in text and "DE987654321" not in text, text[:2000]
            no_secrets("agent of kga (changes)", text)
            status, chb = agent(everything, "/kg/changes", cursor=next_changes, expand="true", limit=100)
            assert "DE987654321" in json.dumps(chb), chb
            status, body = agent(both, "/kg/changes", cursor="0123456789abcdef:1")
            assert status == 410 and body["error"]["details"]["full_sync"] == "/scoutro/api/agent/v1/kg/export", body
            checks += 5

            # ---- scoutroctl and the MCP adapter with the agent token
            env = {**os.environ, "SCOUTRO_URL": BASE, "SCOUTRO_TOKEN": both, "NO_PROXY": "127.0.0.1", "no_proxy": "127.0.0.1"}
            ctl = subprocess.run([sys.executable, str(REPO / "tools/scoutro/scoutroctl"), "--compact", "kg", "entities", "--q", "Muster"],
                                 env=env, capture_output=True, text=True, timeout=60)
            assert ctl.returncode == 0 and "Muster Pflege gGmbH" in ctl.stdout and "Nur Bee" not in ctl.stdout, (ctl.stdout, ctl.stderr)
            ctl = subprocess.run([sys.executable, str(REPO / "tools/scoutro/scoutroctl"), "kg", "export", "--all", "--limit", "2"],
                                 env=env, capture_output=True, text=True, timeout=60)
            lines = [json.loads(l) for l in ctl.stdout.splitlines()]
            assert ctl.returncode == 0 and lines[-1]["record"] == "end" and len(lines) > 3, ctl.stdout[-500:]
            no_secrets("scoutroctl kg export", ctl.stdout)
            ctl = subprocess.run([sys.executable, str(REPO / "tools/scoutro/scoutroctl"), "kg", "download"], env=env,
                                 capture_output=True, text=True, timeout=60)
            assert ctl.returncode == 2 and "admin_only" in ctl.stdout, ctl.stdout
            mcp_in = "\n".join(json.dumps(m) for m in [
                {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}},
                {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "scoutro_kg_entities", "arguments": {"q": "Muster"}}}]) + "\n"
            mcp = subprocess.run([sys.executable, str(REPO / "tools/scoutro/scoutro-mcp")], input=mcp_in, env=env, capture_output=True,
                                 text=True, timeout=60)
            replies = {r["id"]: r for r in map(json.loads, mcp.stdout.splitlines())}
            tools = {t["name"] for t in replies[2]["result"]["tools"]}
            assert {"scoutro_kg_entities", "scoutro_kg_export", "scoutro_kg_changes"} <= tools, tools
            assert not tools & {"scoutro_kg_status", "scoutro_kg_control", "scoutro_kg_download"}, tools
            called = replies[3]["result"]["content"][0]["text"]
            assert not replies[3]["result"]["isError"] and "Muster Pflege gGmbH" in called, called
            no_secrets("MCP kg tool", called)
            checks += 5

            # ---- administrator download
            status, headers, raw = call(c, "GET", "/scoutro/api/v1/kg/export/download?include=evidence")
            lines = [json.loads(l) for l in raw.decode().splitlines()]
            assert status == 200 and headers.get("Content-Type", "").startswith("application/x-ndjson"), (status, headers)
            assert re.match(r'attachment; filename="scoutro-knowledge-all-\d{8}T\d{6}Z\.ndjson"', headers.get("Content-Disposition", "")), headers
            assert lines[0]["record"] == "header" and lines[-1]["record"] == "trailer" and lines[-1]["complete"], lines[-1]
            assert "Geheime Holding" in raw.decode() and lines[-1]["counts"]["entities"] >= 4, lines[-1]
            status, headers, raw = call(c, "GET", "/scoutro/api/v1/kg/export/download?format=json&collection=kga")
            whole = json.loads(raw)
            assert status == 200 and whole["header"]["collection"] == "kga" and whole["trailer"]["complete"], whole.get("trailer")
            no_secrets("kga download", raw.decode())
            assert call(ANON, "GET", "/scoutro/api/v1/kg/export/download")[0] == 401
            assert call(ANON, "GET", "/scoutro/api/v1/kg/export")[0] == 401
            checks += 4

            # ---- chat: graph facts for local access in the requested collection only
            meta, prompt = chat("Was betreibt die Muster Pflege gGmbH?", "kga")
            graph = meta.get("scoutro-graph", {})
            assert graph.get("used") and graph.get("collection") == "kga" and graph.get("facts", 0) >= 2, (graph, prompt[-2000:])
            assert "Scoutro knowledge graph: Muster Pflege gGmbH" in prompt and "not model knowledge" in prompt, prompt[-2000:]
            assert "Haus Lindenhof (uncertain: only read from the page text by a language model)" in prompt, prompt[-2000:]
            no_secrets("chat in kga (prompt)", prompt)
            kinds = [s for s in meta["scoutro-sources"] if s.get("kind") == "graph"]
            assert kinds and kinds[0]["title"].startswith("Scoutro knowledge graph: "), meta["scoutro-sources"]
            assert kinds[0]["id"] in meta.get("scoutro-citations", {}).get("valid", []), meta.get("scoutro-citations")
            meta, prompt = chat("Was ist die Muster Pflege gGmbH?", "kgb")
            assert "Geheime Holding" in prompt and "Haus Lindenhof" not in prompt.split("Scoutro knowledge graph", 1)[-1], prompt[-2000:]
            meta, prompt = chat("Was betreibt die Muster Pflege gGmbH?", "kga", {"X-Forwarded-For": "198.51.100.23"})
            assert meta.get("scoutro-graph", {}).get("reason") == "access" and "Scoutro knowledge graph" not in prompt, (meta, prompt[-1000:])
            # package 6.1: a guest may choose only the released kga; kgb and an unknown name get the same refusal, no name echoed
            for name in ("kgb", "nirgends-web"):
                body = {"model": "chat", "stream": True, "collection": name, "messages": [{"role": "user", "content": "Hallo", "search": "local"}]}
                status, _, raw = call(ANON, "POST", "/v1/chat/completions", body, {"X-Forwarded-For": "198.51.100.23"})
                error = json.loads(raw.decode())["error"]
                assert status == 403 and error["code"] == "collection_not_allowed" and name not in raw.decode(), (name, status, raw[:300])
            meta, prompt = chat("Hallo collection:kgb Muster Pflege", None, {"X-Forwarded-For": "198.51.100.25"})
            assert meta.get("scoutro-retrieval", {}).get("collection") is None, ("a guest's collection: modifier never scopes to kgb", meta)
            meta, prompt = chat("Hallo collection:kga Muster Pflege", None, {"X-Forwarded-For": "198.51.100.25"})
            assert meta.get("scoutro-retrieval", {}).get("collection") == "kga", ("the released kga by modifier", meta)
            def choices(page):
                select = re.search(r'<select id="collectionSelect"[^>]*>(.*?)</select>', page, re.S)
                assert select, page[:500]
                return re.findall(r'<option value="([^"]*)"', select.group(1))
            guest_names = choices(call(ANON, "GET", "/yacychat.html", None, {"X-Forwarded-For": "198.51.100.25"})[2].decode())
            assert guest_names == ["", "kga"], guest_names
            local_names = choices(call(ANON, "GET", "/yacychat.html")[2].decode())
            # local access: every collection of the index (the administrator's list without YaCy's robot_ collections), sorted
            index_names = sorted((x["id"] for x in get(c, "/scoutro/api/v1/collections")["collections"] if not x["id"].startswith("robot_")),
                                 key=lambda name: (name.lower(), name))
            assert "kga" in index_names and local_names == [""] + index_names, (local_names, index_names)
            checks += 10
            print(f"PASS: {checks} live checks of agents, export, change feed, download, scoutroctl, MCP and chat", flush=True)

            ui_env = {**os.environ, "SCOUTRO_URL": BASE}
            subprocess.run(["node", str(REPO / "test/scoutro-ui/chat-graph-ui-test.mjs")], cwd=REPO, env=ui_env, check=True, timeout=600)
        except BaseException:
            log.flush()
            print("--- end of peer.log ---\n" + (root / "peer.log").read_text(errors="replace")[-30000:], flush=True)
            raise
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            server.shutdown()
            print("Disposable peer stopped; temporary DATA removed", flush=True)
