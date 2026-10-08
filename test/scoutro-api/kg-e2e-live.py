#!/usr/bin/env python3
"""End-to-end test of the knowledge graph on NEW disposable DATA only (package 5). GPL-2.0-or-later.

Peer 1 crawls two local fixture sites (resolved through a private JVM hosts
file; nothing leaves the machine) into the collections e2e-a and e2e-b and
walks the 16 steps: crawl, JSON-LD, facts, entity, evidence, API, UI, agent
and chat, change and recrawl, update, deleted source, reconcile, restart,
re-check, backup. Peer 2 is a fresh test environment that restores the
downloaded backup (step 16). Peer 3 checks the limits: a disk reserve the
graph cannot meet and a tiny JSON-LD budget while the crawl goes on, a queue
overflow during a pause, and a hard kill in the middle of processing.

Edge cases on the way: several collections and two organisations of the same
name, a large page, invalid JSON-LD, the model unreachable at first, a
reconcile cut off by a hard kill. No existing peer or DATA is touched.

Requires `ant compile`, a JDK, Node with Playwright and Chromium. Optional:
JAVA, NODE_PATH, SCOUTRO_CHROMIUM_PATH, SCOUTRO_E2E_REPORT (a JSON file for
the timings and counts).
"""
import html
import http.server
import json
import os
from pathlib import Path
import re
import shutil
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
sys.dont_write_bytecode = True


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


SITE_PORT, LLM_PORT = free_port(), free_port()
HOST_A = "www.lindenhof-pflege.test"
HOST_B = "www.lindenhof-nord.test"
HOST_C = "www.limits-pflege.test"
ORG = "Lindenhof Pflege gGmbH"
DIRECTOR = "Erika Musterfrau"
REPORT = {"steps": {}, "timings": {}}
CHECKS = [0]


def check(condition, message):
    assert condition, message
    CHECKS[0] += 1


# ------------------------------------------------------------------ the sites

def ld(obj):
    return '<script type="application/ld+json">' + json.dumps(obj, ensure_ascii=False) + "</script>"


def org_ld(host, phone, vat, city, plz, extra=None):
    o = {"@context": "https://schema.org", "@type": "MedicalOrganization", "name": ORG, "legalName": ORG,
         "url": f"http://{host}:{SITE_PORT}/", "telephone": phone, "vatID": vat,
         "email": ["info@" + host[4:], DIRECTOR.lower().replace(" ", ".") + "@" + host[4:]],
         "address": {"@type": "PostalAddress", "streetAddress": "Lindenallee 5", "postalCode": plz, "addressLocality": city}}
    o.update(extra or {})
    return o


def house_ld(host, name, phone, city, plz):
    return {"@context": "https://schema.org", "@type": "NursingHome", "name": name, "telephone": phone,
            "parentOrganization": {"@type": "Organization", "name": ORG},
            "address": {"@type": "PostalAddress", "streetAddress": "Gartenweg 2", "postalCode": plz, "addressLocality": city}}


def doc(title, head, body, links=()):
    nav = " ".join(f'<a href="{l}">{l}</a>' for l in links)
    return f"<html><head><title>{title}</title>{head}</head><body><nav>{nav}</nav>{body}</body></html>"


class SiteState:
    """The fixture sites; revision 1 is the recrawl with changes."""

    def __init__(self):
        self.rev = 0
        self.lock = threading.Lock()

    def pages(self, host):
        if host == HOST_A:
            return self.site_a()
        if host == HOST_B:
            return self.site_b()
        if host == HOST_C:
            return self.site_c()
        return {}

    def site_a(self):
        phone = "030 1234567" if self.rev == 0 else "030 7654321"
        links = ["/impressum", "/kontakt", "/standorte/haus-lindenhof", "/standorte/haus-birkenhof", "/gross", "/kaputt"] + [
            f"/aktuelles/{i}" for i in range(1, 6)]
        p = {
            "/": doc(ORG, ld(org_ld(HOST_A, phone, "DE811111111", "Berlin", "10115")),
                     f"<h1>{ORG}</h1><p>Willkommen bei der {ORG} in Berlin.</p>", links),
            "/impressum": doc("Impressum – " + ORG, "",
                              f"<h1>Impressum</h1><p>Angaben gemäß § 5 DDG</p><p>{ORG}<br>Lindenallee 5<br>10115 Berlin</p>"
                              f"<p>Telefon: {phone}</p><p>E-Mail: {DIRECTOR.lower().replace(' ', '.')}@{HOST_A[4:]}, info@{HOST_A[4:]}</p>"
                              f"<p>Vertreten durch die Geschäftsführerin {DIRECTOR}</p><p>Registergericht: Amtsgericht Charlottenburg</p>"
                              "<p>Registernummer: HRB 98765 B</p><p>USt-IdNr.: DE811111111</p>", ["/"]),
            "/kontakt": doc("Kontakt – " + ORG, "", f"<h1>Kontakt</h1><p>Rufen Sie uns an: {phone}</p>", ["/"]),
            "/standorte/haus-lindenhof": doc("Haus Lindenhof – " + ORG, ld(house_ld(HOST_A, "Haus Lindenhof", "030 1111111", "Berlin", "10115")),
                                             f"<h1>Haus Lindenhof</h1><p>Die {ORG} betreibt das Haus Lindenhof in Berlin. "
                                             "Das Haus Lindenhof bietet Tagespflege an.</p>", ["/"]),
            "/gross": doc("Große Seite – " + ORG, ld(org_ld(HOST_A, phone, "DE811111111", "Berlin", "10115"))
                          + ld({"@context": "https://schema.org", "@type": "FAQPage", "mainEntity": [
                              {"@type": "Question", "name": f"Frage {i}?", "acceptedAnswer": {"@type": "Answer", "text": "Antwort " * 60}}
                              for i in range(60)]}),
                          "<h1>Große Seite</h1>" + "<p>" + ("Pflege Betreuung Beratung Wohnen Alltag " * 4000) + "</p>", ["/"]),
            "/kaputt": doc("Kaputt – " + ORG, '<script type="application/ld+json">{"@type":"Organization","name":"Kaputt',
                           "<h1>Kaputtes JSON-LD</h1><p>Diese Seite hat fehlerhafte strukturierte Daten.</p>", ["/"]),
        }
        if self.rev == 0:
            p["/standorte/haus-birkenhof"] = doc("Haus Birkenhof – " + ORG, ld(house_ld(HOST_A, "Haus Birkenhof", "030 2222222", "Berlin", "10117")),
                                                 f"<h1>Haus Birkenhof</h1><p>Die {ORG} betreibt das Haus Birkenhof in Berlin.</p>", ["/"])
        for i in range(1, 6):
            p[f"/aktuelles/{i}"] = doc(f"Aktuelles {i}", "", f"<p>Neuigkeit {i}: Sommerfest und Tag der offenen Tür.</p>", ["/"])
        return p

    def site_b(self):
        return {
            "/": doc(ORG + " Nord", ld(org_ld(HOST_B, "040 5555555", "DE822222222", "Hamburg", "20095")),
                     f"<h1>{ORG}</h1><p>Unser Haus in Hamburg.</p>", ["/impressum"]),
            "/impressum": doc("Impressum", "", f"<h1>Impressum</h1><p>Angaben gemäß § 5 DDG</p><p>{ORG}<br>Hafenstraße 1<br>20095 Hamburg</p>"
                              "<p>Telefon: 040 5555555</p><p>USt-IdNr.: DE822222222</p>", ["/"]),
        }

    def site_c(self):
        p = {"/": doc("Limits", ld(org_ld(HOST_C, "0221 333333", "DE833333333", "Köln", "50667")), "<h1>Limits</h1>",
                      [f"/seite/{i}" for i in range(40)])}
        for i in range(40):
            p[f"/seite/{i}"] = doc(f"Seite {i}", ld({"@context": "https://schema.org", "@type": "FAQPage", "mainEntity": [
                {"@type": "Question", "name": f"Frage {i}.{j}?", "acceptedAnswer": {"@type": "Answer", "text": "Antwort " * 40}}
                for j in range(30)]}), f"<p>Seite {i} mit vielen Fragen.</p>", ["/"])
        return p


STATE = SiteState()


class Site(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        host = (self.headers.get("Host") or "").split(":")[0]
        path = urllib.parse.urlsplit(self.path).path
        if path == "/robots.txt":
            body, status = "User-agent: *\nAllow: /\n", 200
        else:
            with STATE.lock:
                pages = STATE.pages(host)
            body = pages.get(path)
            status = 200 if body is not None else 404
            body = body or "<html><body>Nicht gefunden</body></html>"
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", ("text/plain" if path == "/robots.txt" else "text/html") + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        # the recrawl must see the change: no copy in YaCy's HTCache
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


# ------------------------------------------------------------------ the model

CHAT_PROMPTS = []
GRAPH_ENTRY = re.compile(r"\[(\d+)\] Scoutro knowledge graph: ")


class FakeModel(http.server.BaseHTTPRequestHandler):
    """Extraction: reads 'betreibt das Haus X' with a verbatim quote. Chat: cites the first graph entry."""

    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        if body.get("stream"):
            prompt = body["messages"][-1]["content"]
            CHAT_PROMPTS.append(prompt)
            m = GRAPH_ENTRY.search(prompt)
            answer = f"Laut Scoutro-Wissensgraph [{m.group(1)}]." if m else "Keine Graph-Fakten."
            data = ("data: " + json.dumps({"choices": [{"delta": {"content": answer}}]}) + "\n\ndata: [DONE]\n\n").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        user = body["messages"][1]["content"]
        answer = {"entities": [], "claims": []}
        m = re.search(r"betreibt das (Haus \w+) in (\w+)", user)
        k = re.search(r'(k\d+): organization "' + re.escape(ORG) + '"', user)
        if m and k:
            answer = {"entities": [{"id": "e1", "type": "facility", "name": m.group(1), "kind": "nursinghome",
                                    "quote": f"betreibt das {m.group(1)} in {m.group(2)}"}],
                      "claims": [{"subject": k.group(1), "predicate": "operates", "object": "e1", "quote": f"{ORG} betreibt das {m.group(1)}"}]}
            o = re.search(r"Das (Haus \w+) bietet (\w+) an", user)
            if o:
                answer["entities"].append({"id": "s1", "type": "service", "name": o.group(2), "quote": f"bietet {o.group(2)} an"})
                answer["claims"].append({"subject": "e1", "predicate": "offers", "object": "s1",
                                         "quote": f"Das {o.group(1)} bietet {o.group(2)} an"})
        if self.path == "/api/chat":  # an OLLAMA model's knowledge extraction: Ollama's native answer
            out = json.dumps({"model": body.get("model"), "done": True, "done_reason": "stop",
                              "message": {"role": "assistant", "content": json.dumps(answer)}}).encode()
        else:
            out = json.dumps({"choices": [{"message": {"content": json.dumps(answer)}, "finish_reason": "stop"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)


# --------------------------------------------------------------- the peers

ANON = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PEERS = []


class Peer:
    def __init__(self, root, settings, name):
        self.root = Path(root)
        self.port = free_port()
        self.base = f"http://127.0.0.1:{self.port}"
        self.name = name
        self.process = None
        self.settings = settings
        conf = self.root / "DATA/SETTINGS/yacy.conf"
        conf.parent.mkdir(parents=True, exist_ok=True)
        (self.root / "DATA/DICTIONARIES/harvesting").mkdir(parents=True, exist_ok=True)
        for friends in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
            (self.root / "DATA/DICTIONARIES/harvesting" / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
        (self.root / "DATA/LOCALE/htroot/de").mkdir(parents=True, exist_ok=True)
        (self.root / "hosts").write_text(f"127.0.0.1 {HOST_A}\n127.0.0.1 {HOST_B}\n127.0.0.1 {HOST_C}\n")
        network = self.root / "fixture.network.unit"
        network.write_text((REPO / "defaults/yacy.network.webportal.unit").read_text().replace(
            "network.unit.domain = global", "network.unit.domain = any"))
        models = [{"service": "OLLAMA", "model": "fixture", "hoststub": f"http://127.0.0.1:{LLM_PORT}", "api_key": "",
                   "max_tokens": "512", "chat": True, "tldr": False, "logreport": False, "knowledge": True}]
        conf.write_text("\n".join([
            f"port={self.port}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
            "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
            f"network.unit.definition={network}", "browserPopUpTrigger=false",
            "autocrawl=false", "server.https=false", "locale.language=browser", "upnp.enabled=false", "donation.iframesource=",
            "scoutro.discovery.enabled=false", "search.verify=false", "ai.shield.allow-nonlocalhost=true",
            # the cleanup job ends finished crawls (one crawl per host at a time); every 2 s instead of minutes
            "90_cleanup_idlesleep=2000", "90_cleanup_busysleep=2000",
            "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
            "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
            "ai.production_models=" + json.dumps(models, separators=(",", ":")),
        ] + settings) + "\n")
        pw = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        pw.add_password(None, self.base, "admin", "yacy")
        self.admin = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(pw))

    def start(self):
        env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
        self.log = (self.root / "peer.log").open("a")
        t0 = time.monotonic()
        self.process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", f"-Djdk.net.hosts.file={self.root / 'hosts'}",
                                         "-cp", CLASSPATH, "net.yacy.yacy", "-startup", str(self.root)],
                                        cwd=REPO, stdout=self.log, stderr=self.log, env=env)
        PEERS.append(self.process)
        deadline = time.monotonic() + 120
        while True:
            if self.process.poll() is not None:
                raise RuntimeError(self.tail())
            try:
                self.get("/scoutro/api/v1/kg/status")
                break
            except (OSError, urllib.error.URLError, AssertionError):
                if time.monotonic() > deadline:
                    raise RuntimeError("peer did not start\n" + self.tail())
                time.sleep(0.5)
        return time.monotonic() - t0

    def stop(self):
        t0 = time.monotonic()
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=120)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.log.close()
        return time.monotonic() - t0

    def kill(self):
        self.process.kill()
        self.process.wait()
        self.log.close()

    def tail(self, n=20000):
        try:
            return (self.root / "peer.log").read_text(errors="replace")[-n:]
        except OSError:
            return ""

    def call(self, method, path, body=None, headers=None, opener=None):
        request = urllib.request.Request(self.base + path, method=method, headers=dict(headers or {}),
                                         data=None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode()))
        if body is not None and not isinstance(body, bytes) and "Content-Type" not in (headers or {}):
            request.add_header("Content-Type", "application/json")
        try:
            with (opener or self.admin).open(request, timeout=120) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def get(self, path, **query):
        status, _, raw = self.call("GET", path + ("?" + urllib.parse.urlencode(query) if query else ""))
        assert status == 200, (path, status, raw[:500])
        return json.loads(raw)

    def kg(self, path, **query):
        return self.get("/scoutro/api/v1/kg" + path, **query)

    def control(self, action, **extra):
        status, _, raw = self.call("POST", "/scoutro/api/v1/kg/control", dict({"action": action}, **extra))
        return status, json.loads(raw) if raw.strip().startswith(b"{") else raw

    def status(self):
        return self.kg("/status")

    def wait(self, what, predicate, timeout=300):
        deadline = time.monotonic() + timeout
        t0 = time.monotonic()
        while True:
            try:
                s = self.status()
                if predicate(s):
                    return s, time.monotonic() - t0
            except (OSError, urllib.error.URLError, AssertionError, KeyError, TypeError):
                s = None
            if time.monotonic() > deadline:
                raise AssertionError(f"{self.name}: timed out waiting for {what}: sync {json.dumps((s or {}).get('sync'))[:4000]}"
                                     f" state {json.dumps({k: (s or {}).get(k) for k in ('state', 'reason', 'store')})[:1500]}")
            time.sleep(0.5)

    def settled(self, s):
        sy = s.get("sync") or {}
        lag = sy.get("lag") or {}
        rec = sy.get("reconcile") or {}
        return s.get("state") == "running" and sy.get("initialized") and lag.get("pending") == 0 and not lag.get("reconcile_pending") \
            and rec.get("current") is None

    def ensure_collections(self, collections):
        # package 6.1: crawls and grants use existing collections only; a missing one is created first, as the administrator would
        have = {x["id"] for x in self.get("/scoutro/api/v1/collections")["collections"]}
        for c in collections:
            if c not in have:
                status, _, raw = self.call("POST", "/scoutro/api/v1/collections", {"id": c, "name": c})
                assert status == 201, (c, status, raw[:300])

    def crawl(self, url, collection, depth=2, max_pages=60, wait=True):
        self.ensure_collections([collection])
        status, _, raw = self.call("POST", "/scoutro/api/v1/crawls", {"url": url, "collection": collection, "depth": depth,
                                                                        "maxPages": max_pages, "scope": "domain"},
                                   {"Idempotency-Key": f"e2e-{time.time_ns()}", "Content-Type": "application/json"})
        assert status in (200, 201), (status, raw[:500])
        crawl_id = json.loads(raw)["id"]
        if not wait:
            return json.loads(raw)
        deadline = time.monotonic() + 420
        while True:
            c = self.get("/scoutro/api/v1/crawls/" + crawl_id)
            if c["state"] == "terminated":
                return c
            if time.monotonic() > deadline:
                raise AssertionError(f"crawl did not terminate: {c}")
            time.sleep(1)

    def push(self, docs, collection):
        """docs: [(url, html)], through YaCy's parser (api/push_p)."""
        boundary = f"----scoutro{time.time_ns()}"
        fields = {"count": str(len(docs)), "synchronous": "true", "commit": "true"}
        parts = []
        for k, v in fields.items():
            parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode())
        for i, (url, text) in enumerate(docs):
            for k, v in {f"url-{i}": url, f"collection-{i}": collection, f"contentType-{i}": "text/html",
                         f"lastModified-{i}": "Tue, 15 Nov 1994 12:45:26 GMT", f"responseHeader-{i}": ""}.items():
                parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode())
            parts.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"data-{i}\"; filename=\"p{i}.html\"\r\n"
                          "Content-Type: text/html\r\n\r\n").encode() + text.encode() + b"\r\n")
        body = b"".join(parts) + f"--{boundary}--\r\n".encode()
        status, _, raw = self.call("POST", "/api/push_p.json", body, {"Content-Type": f"multipart/form-data; boundary={boundary}"})
        assert status == 200 and json.loads(raw).get("countsuccess") == len(docs), (status, raw[:300])

    def solr_count(self, collection):
        status, _, raw = self.call("GET", "/solr/select?" + urllib.parse.urlencode(
            {"q": "*:*", "fq": f"collection_sxt:{collection}", "rows": "0", "wt": "json"}))
        assert status == 200, (status, raw[:300])
        return json.loads(raw)["response"]["numFound"]

    def doc_id(self, url, timeout=120):
        deadline = time.monotonic() + timeout
        while True:
            status, _, raw = self.call("GET", "/solr/select?" + urllib.parse.urlencode({"q": f'sku:"{url}"', "fl": "id", "wt": "json"}))
            assert status == 200, (status, raw[:300])
            docs = json.loads(raw)["response"]["docs"]
            if docs:
                return docs[0]["id"]
            if time.monotonic() > deadline:
                status, _, raw = self.call("GET", "/solr/select?" + urllib.parse.urlencode(
                    {"q": "sku:*" + urllib.parse.urlsplit(url).path.replace("/", "\\/"), "fl": "id,sku", "wt": "json"}))
                raise AssertionError(f"not in the index: {url}; similar: {raw[:500]}")
            time.sleep(2)

    def transaction_post(self, page, fields):
        status, headers, _ = self.call("GET", "/" + page)
        assert status == 200, status
        form = dict(fields, transactionToken=headers.get("X-YaCy-Transaction-Token"))
        status, _, raw = self.call("POST", "/" + page, urllib.parse.urlencode(form).encode(),
                                   {"Content-Type": "application/x-www-form-urlencoded"})
        assert status == 200, (status, raw[:300])
        return raw.decode(errors="replace")

    def create_agent(self, name, actions, collections):
        self.ensure_collections(collections)
        status, headers, _ = self.call("GET", "/ScoutroAgentWizard_p.html")
        token, draft = headers.get("X-YaCy-Transaction-Token"), ""

        def post(step, fields):
            nonlocal token, draft
            form = dict({"transactionToken": token, "draft": draft, "step": str(step), "next": "Next"}, **fields)
            status, h, raw = self.call("POST", "/ScoutroAgentWizard_p.html", urllib.parse.urlencode(form).encode(),
                                       {"Content-Type": "application/x-www-form-urlencoded"})
            assert status == 200, status
            text = raw.decode()
            m = re.search(r'name="draft" value="([^"]*)"', text)
            draft = html.unescape(m.group(1)) if m else ""
            token = h.get("X-YaCy-Transaction-Token", token)
            return text

        post(1, {"name": name, "description": "e2e", "kind": "external"})
        post(2, dict({"scopeForm": "1"}, **{"col_" + c: "on" for c in collections}))
        post(3, dict({"actionsForm": "1", "preset": "custom"}, **{"act_" + a: "on" for a in actions}))
        post(4, {"limitsForm": "1", "domains": "", "maxDepth": "1", "maxPages": "20", "maxParallelCrawls": "1",
                 "requestsPerMinute": "600", "maxTaskSeconds": "300"})
        result = post(5, {"expiresInDays": "30"})
        m = re.search(r'id="createdToken" readonly="readonly" size="70" value="([^"]+)"', result)
        assert m, "no token"
        return html.unescape(m.group(1))

    def agent(self, token, path, **query):
        status, _, raw = self.call("GET", "/scoutro/api/agent/v1" + path + ("?" + urllib.parse.urlencode(query) if query else ""),
                                   headers={"Authorization": "Bearer " + token}, opener=ANON)
        return status, json.loads(raw) if raw.strip().startswith(b"{") else raw

    def chat(self, question, collection):
        body = {"model": "chat", "stream": True, "collection": collection,
                "messages": [{"role": "user", "content": question, "search": "local"}]}
        status, _, raw = self.call("POST", "/v1/chat/completions", body, opener=ANON)
        assert status == 200, (status, raw[:500])
        meta = {}
        for line in raw.decode().splitlines():
            if line.startswith("data: {"):
                chunk = json.loads(line[6:])
                for key in ("scoutro-graph", "scoutro-sources"):
                    if key in chunk:
                        meta[key] = chunk[key]
        return meta, CHAT_PROMPTS[-1] if CHAT_PROMPTS else ""


def find(peer, name, collection, type_=None):
    items = peer.kg("/entities", q=name, collection=collection, limit=50)["items"]
    return [e for e in items if e["name"] == name and (type_ is None or e["type"] == type_)]


def val(statement):
    return (statement.get("object") or {}).get("value")


def statements(peer, entity, collection=None, **extra):
    q = dict(limit=100, **extra)
    if collection:
        q["collection"] = collection
    return peer.kg(f"/entities/{entity}/statements", **q)["items"]


def step(n, title):
    print(f"-- step {n}: {title}", flush=True)
    REPORT["steps"][str(n)] = title


def timing(key, seconds):
    REPORT["timings"][key] = round(seconds, 2)


# --------------------------------------------------------------- scenario

def peer_one(root, llm):
    p = Peer(root, ["scoutro.kg.enabled=true", "scoutro.kg.collections=e2e-a,e2e-b", "scoutro.kg.jsonld.enabled=true",
                    "scoutro.kg.llm.collections=e2e-a", "scoutro.kg.llm.kinds.e2e-a=nursinghome", "scoutro.kg.chat.timeoutMs=2000",
                    "scoutro.kg.llm.breakerFailures=2", "scoutro.kg.reconcile.debounceSeconds=5"], "peer 1")
    timing("peer1.start_s", p.start())
    p.wait("the start backfill", lambda s: (s.get("sync") or {}).get("reconcile", {}).get("last") is not None)

    step(1, "crawl a new domain")
    t0 = time.monotonic()
    crawl = p.crawl(f"http://{HOST_A}:{SITE_PORT}/", "e2e-a")
    p.crawl(f"http://{HOST_B}:{SITE_PORT}/", "e2e-b")
    timing("crawl_s", time.monotonic() - t0)
    p.doc_id(f"http://{HOST_A}:{SITE_PORT}/")  # the start page in Solr: YaCy may still hold a page in its own index queue
    indexed = p.solr_count("e2e-a")
    check(indexed >= 10, f"pages of {HOST_A} in the index: {indexed} ({crawl})")
    REPORT["indexed_a"] = indexed

    def followed(s):
        # settled, and every page of e2e-a that Solr holds is processed by the graph (not only as many publications)
        if not (p.settled(s) and s["sync"]["processed"]["published"] >= indexed):
            return False
        _, _, raw = p.call("GET", "/solr/select?" + urllib.parse.urlencode({"q": "*:*", "fq": "collection_sxt:e2e-a", "rows": "200", "fl": "id",
                                                                         "wt": "json"}))
        for d in json.loads(raw)["response"]["docs"]:
            status, _, body = p.call("GET", f"/scoutro/api/v1/kg/sources/{d['id']}")
            if status != 200 or not json.loads(body).get("source", {}).get("processed_at"):
                return False
        return True

    _, took = p.wait("the graph to follow the crawl", followed)
    timing("graph_after_crawl_s", took)

    step(2, "JSON-LD captured in the index")
    s = p.status()
    check((s.get("jsonld") or {}).get("estimatedBytes", 0) > 0 or s["sync"]["processed"].get("published", 0) > 0, "JSON-LD accounted")
    orgs = find(p, ORG, "e2e-a", "organization")
    check(len(orgs) == 1, f"one organisation in e2e-a: {orgs}")
    org = orgs[0]["id"]
    facts = statements(p, org, "e2e-a")
    phone = [f for f in facts if f["predicate"] == "phone"]
    check(phone and "jsonld" in phone[0]["kinds"], f"phone from JSON-LD: {phone}")

    step(3, "facts")
    preds = {f["predicate"] for f in facts}
    check({"phone", "address", "identifier:vat", "identifier:register", "email"} <= preds, f"facts of the organisation: {sorted(preds)}")
    vat = next(f for f in facts if f["predicate"] == "identifier:vat")
    check(vat["quality"] == "supported" and "DE811111111" in json.dumps(vat), vat)
    mails = [val(f) for f in facts if f["predicate"] == "email"]
    check(mails == ["info@lindenhof-pflege.test"], f"only the role mailbox (O7): {mails}")

    step(4, "entities")
    houses = find(p, "Haus Lindenhof", "e2e-a", "facility")
    check(len(houses) == 1, f"facility Haus Lindenhof: {houses}")
    birken = find(p, "Haus Birkenhof", "e2e-a", "facility")
    check(len(birken) == 1, "facility Haus Birkenhof")
    REPORT["entities_a"] = p.kg("/entities", collection="e2e-a", limit=1)["total"]

    step(5, "evidence")
    ev = p.kg(f"/statements/{vat['id']}/evidence")["items"]
    check(ev and ev[0]["url"].startswith(f"http://{HOST_A}") and ev[0]["excerpt"], f"evidence with page and excerpt: {ev[:1]}")
    everything = json.dumps(p.kg("/export/download", format="json", include="evidence"))
    check(DIRECTOR not in everything and "musterfrau" not in everything.lower(), "no person name or personal e-mail anywhere (O7)")
    src = p.kg(f"/sources/{ev[0]['doc_id']}")
    check(src["source"]["state"] == "active" and 1 in src["source"]["tiers"], src["source"])

    step(6, "API")
    detail = p.kg(f"/entities/{org}", collection="e2e-a")
    check(detail["name"] == ORG and "DE811111111" in json.dumps(detail["identifiers"]), detail)
    # the host route finds a host by name on the ports 80 and 443; the fixture runs on another port (a known limit),
    # so only the shape is checked here (the host lookup itself: KgReaderTest, knowledge-live-smoke.py)
    host = p.kg(f"/hosts/{HOST_A}/entities", collection="e2e-a")
    check(host["host"] == HOST_A and "items" in host, host)
    check(p.call("GET", "/scoutro/api/v1/kg/entities", opener=ANON)[0] == 401, "no anonymous access")
    # several collections, two organisations of the same name: separate, and each collection sees only its own
    b_orgs = find(p, ORG, "e2e-b", "organization")
    check(len(b_orgs) == 1 and b_orgs[0]["id"] != org, f"same name, other VAT ID: separate entity {b_orgs}")
    check(p.call("GET", f"/scoutro/api/v1/kg/entities/{b_orgs[0]['id']}?collection=e2e-a")[0] == 404, "e2e-b's entity not in e2e-a")
    check("DE822222222" not in json.dumps(p.kg(f"/entities/{org}/statements", collection="e2e-a", limit=100)), "no e2e-b fact in e2e-a")
    both = p.kg("/entities", q=ORG, limit=50)["items"]
    check(len([e for e in both if e["name"] == ORG and e["type"] == "organization"]) == 2, f"the administrator sees both: {both}")
    # a large page (300 KB of text, a 36 KB FAQ graph) and a page with invalid JSON-LD: processed, bounded, nothing failed
    big = p.kg(f"/sources/{p.doc_id(f'http://{HOST_A}:{SITE_PORT}/gross')}")["source"]
    check(big["state"] == "active" and big["jsonld_bytes"] <= 16384, f"large page bounded: {big}")
    broken = p.kg(f"/sources/{p.doc_id(f'http://{HOST_A}:{SITE_PORT}/kaputt')}")["source"]
    check(broken["state"] == "active", f"invalid JSON-LD processed: {broken}")
    check(p.status()["sync"]["processed"]["failedDocs"] == 0, "no failed document")
    REPORT["large_page"], REPORT["invalid_jsonld_page"] = big, broken

    step(7, "UI")
    env = {**os.environ, "SCOUTRO_URL": p.base, "SCOUTRO_KG_ENTITY": org, "SCOUTRO_KG_NAME": ORG, "SCOUTRO_KG_COLLECTION": "e2e-a"}
    ui = subprocess.run(["node", str(REPO / "test/scoutro-ui/kg-e2e-ui.mjs")], cwd=REPO, env=env, capture_output=True, text=True, timeout=600)
    print(ui.stdout.strip(), flush=True)
    check(ui.returncode == 0, "UI: " + ui.stdout[-2000:] + ui.stderr[-2000:])

    step(8, "agent and chat")
    # the model was unreachable until now: tiers 1 and 2 went on, the LLM tier failed and its breaker opened
    s = p.status()
    llm_state = s["llm"]
    check(llm_state["processed"]["callFailures"] >= 1 and s["sync"]["processed"]["published"] >= indexed,
          f"model unreachable: tiers 1 and 2 published, LLM calls failed: {json.dumps(llm_state)[:600]}")
    REPORT["llm_unreachable"] = {"state": llm_state.get("state"), "reason": llm_state.get("reason"), "breaker": llm_state.get("breaker")}
    llm.start()
    status, _ = p.control("llm_retry")
    check(status == 200, "llm_retry")
    try:
        _, took = p.wait("the LLM tier", lambda s: any(f["predicate"] == "operates" and "llm" in f["kinds"]
                                                        for f in statements(p, org, "e2e-a")), timeout=300)
    except AssertionError:
        raise AssertionError("LLM tier: " + json.dumps(p.status().get("llm"))[:3000])
    timing("llm_after_retry_s", took)
    operates = [f for f in statements(p, org, "e2e-a") if f["predicate"] == "operates"]
    # the model confirms the relation that the JSON-LD already states: one statement, evidence of both tiers, still supported
    check(any(set(f["kinds"]) >= {"jsonld", "llm"} and f["quality"] == "supported" for f in operates), f"LLM evidence: {operates}")
    token = p.create_agent("e2e reader", ["kg.read"], ["e2e-a"])
    status, page = p.agent(token, "/kg/entities", q=ORG, limit=50)
    check(status == 200 and [e["id"] for e in page["items"] if e["name"] == ORG] == [org], f"agent sees only e2e-a: {page}")
    check(p.agent(token, f"/kg/entities/{b_orgs[0]['id']}")[0] == 404, "agent: e2e-b entity not found")
    check(p.agent(token, "/kg/export")[0] == 403, "agent without kg.export")
    meta, prompt = p.chat(f"Welche Telefonnummer hat die {ORG}?", "e2e-a")
    graph = meta.get("scoutro-graph", {})
    check(graph.get("used") and graph.get("collection") == "e2e-a", f"chat with graph facts: {graph}")
    check("DE822222222" not in prompt and "Hamburg" not in prompt.split("Scoutro knowledge graph", 1)[-1], "chat in e2e-a without e2e-b")

    step(9, "change and recrawl")
    before_ids = {e["id"] for e in p.kg("/entities", collection="e2e-a", limit=100)["items"]}
    with STATE.lock:
        STATE.rev = 1
    t0 = time.monotonic()
    for path in ["/", "/impressum", "/kontakt"]:
        p.crawl(f"http://{HOST_A}:{SITE_PORT}{path}", "e2e-a", depth=0, max_pages=1)
    # the facility page is gone (404): YaCy refuses a crawl that starts there
    status, _, raw = p.call("POST", "/scoutro/api/v1/crawls", {"url": f"http://{HOST_A}:{SITE_PORT}/standorte/haus-birkenhof",
                                                                "collection": "e2e-a", "depth": 0, "maxPages": 1, "scope": "domain"},
                            {"Idempotency-Key": f"e2e-{time.time_ns()}", "Content-Type": "application/json"})
    check(status == 422 and b"404" in raw, f"a crawl of the vanished page is refused: {status}")
    p.wait("the recrawl", lambda s: p.settled(s) and any(val(f) == "+49307654321" for f in statements(p, org, "e2e-a")
                                                         if f["predicate"] == "phone"))
    timing("recrawl_s", time.monotonic() - t0)

    step(10, "update")
    phones = {val(f): f["quality"] for f in statements(p, org, "e2e-a", include="stale") if f["predicate"] == "phone"}
    check(phones.get("+49307654321") == "supported", f"new phone supported: {phones}")
    check(phones.get("+49301234567") in (None, "stale", "conflicting", "supported"), phones)
    REPORT["phones_after_recrawl"] = phones

    step(11, "deleted source")
    # the vanished page: YaCy removes a start URL before it reloads it, so the refused recrawl took it out of the
    # index, and the graph followed: the facility had no other source
    p.wait("the vanished page", lambda s: p.settled(s) and not find(p, "Haus Birkenhof", "e2e-a", "facility"))
    check(p.call("GET", f"/scoutro/api/v1/kg/entities/{birken[0]['id']}?collection=e2e-a")[0] == 404, "the facility without a source is gone")
    check(any(f["predicate"] == "operates" for f in statements(p, org, "e2e-a")), "the other relations stay")
    # a page removed by the administrator: the organisation's facts lose one of their sources and stay
    # the VAT ID: on the home page, the large page (both JSON-LD) and the imprint (rules)
    phone_before = next(f for f in statements(p, org, "e2e-a") if f["predicate"] == "identifier:vat")
    t0 = time.monotonic()
    p.transaction_post("IndexControlURLs_p.html", {"urlstring": f"http://{HOST_A}:{SITE_PORT}/gross", "urldelete": "Delete"})
    p.wait("the deletion", lambda s: p.settled(s) and next((f["sources"] for f in statements(p, org, "e2e-a")
                                                            if f["id"] == phone_before["id"]), 0) < phone_before["sources"])
    timing("delete_source_s", time.monotonic() - t0)
    phone_after = next(f for f in statements(p, org, "e2e-a") if f["id"] == phone_before["id"])
    check(phone_after["quality"] == "supported" and phone_after["sources"] == phone_before["sources"] - 1,
          f"one source less, still supported: {phone_before['sources']} -> {phone_after}")
    REPORT["docs_after_delete"] = p.status()["sync"]["processed"]

    step(12, "reconcile")
    last = p.status()["sync"]["reconcile"].get("lastCompletedAt") or 0
    t0 = time.monotonic()
    check(p.control("reconcile")[0] == 200, "reconcile")
    s, took = p.wait("the reconcile", lambda s: (s["sync"]["reconcile"].get("lastCompletedAt") or 0) > last and p.settled(s))
    timing("reconcile_s", took)
    run = s["sync"]["reconcile"]["last"]
    check(run["state"] == "completed" and run["deleted"] >= 0, run)
    REPORT["reconcile"] = run
    ids_before_restart = {e["id"] for e in p.kg("/entities", collection="e2e-a", limit=100)["items"]}
    check(org in ids_before_restart and before_ids & ids_before_restart, "IDs stable through the recrawl")

    step(13, "restart")
    timing("peer1.stop_s", p.stop())
    timing("peer1.restart_s", p.start())
    s, took = p.wait("the start reconcile", lambda s: p.settled(s) and s["sync"]["reconcile"].get("lastCompletedAt"))
    timing("start_reconcile_s", took)

    step(14, "re-check")
    check(s["store"]["integrity"]["state"] in ("not_required", "ok"), s["store"]["integrity"])
    check({e["id"] for e in p.kg("/entities", collection="e2e-a", limit=100)["items"]} == ids_before_restart, "same entities after the restart")
    check(any(val(f) == "+49307654321" for f in statements(p, org, "e2e-a") if f["predicate"] == "phone"), "facts after the restart")

    step(15, "backup")
    t0 = time.monotonic()
    check(p.control("backup")[0] == 200, "backup")
    s, _ = p.wait("the backup", lambda s: s["backup"]["state"] == "idle" and (s["backup"].get("last") or {}).get("result") == "created")
    timing("backup_s", time.monotonic() - t0)
    name = s["backup"]["last"]["file"]
    status, headers, data = p.call("GET", "/scoutro/api/v1/kg/backups/" + name)
    check(status == 200 and data[:15] == b"SQLite format 3", f"backup download {status}")
    REPORT["backup"] = s["backup"]["last"]
    export_a = p.kg("/export/download", format="json", collection="e2e-a")
    # identity rebuild on the real data: same entity IDs, previous graph kept
    t0 = time.monotonic()
    check(p.control("rebuild")[0] == 200, "rebuild")
    s, _ = p.wait("the rebuild", lambda s: (s.get("rebuild") or {}).get("phase") in ("done", "failed", "awaiting_confirmation"), timeout=600)
    timing("rebuild_s", time.monotonic() - t0)
    check(s["rebuild"]["phase"] == "done", s["rebuild"])
    p.wait("after the rebuild", lambda s: p.settled(s))
    # the stored ID still leads to the organisation: itself, or a redirect to the entity that now holds its keys
    after = p.kg(f"/entities/{org}", collection="e2e-a")
    if "redirect" in after:
        REPORT["rebuild_redirect"] = {"from": org, "to": after["redirect"]}
        after = p.kg(f"/entities/{after['redirect']}", collection="e2e-a")
    check(after.get("name") == ORG and "DE811111111" in json.dumps(after.get("identifiers")), f"the organisation's ID after the rebuild: {after}")
    org_before_rebuild, org = org, after["id"]
    REPORT["rebuild"] = s["rebuild"]

    # hard kill in the middle of processing, cutting off a reconcile
    many = [(f"http://{HOST_A}:{SITE_PORT}/archiv/{i}", doc(f"Archiv {i}", ld(house_ld(HOST_A, f"Haus Archiv{i}", "030 4444444", "Berlin", "10115")),
                                                             f"<p>Archivseite {i}</p>")) for i in range(120)]
    p.control("pause")
    for i in range(0, len(many), 40):
        p.push(many[i:i + 40], "e2e-a")
    p.control("reconcile")
    p.control("resume")
    p.wait("processing under way", lambda s: s["sync"]["queue"]["items"] or s["sync"]["reconcile"].get("current"), timeout=60)
    p.kill()
    t0 = time.monotonic()
    timing("peer1.after_kill_start_s", p.start())
    s, took = p.wait("recovery after the hard kill", lambda s: p.settled(s) and s["store"]["integrity"]["state"] in ("ok", "not_required"),
                     timeout=600)
    timing("recovery_after_kill_s", time.monotonic() - t0)
    check(s["store"]["integrity"].get("trigger") in ("unclean_start", None), s["store"]["integrity"])
    tracked = p.kg("/entities", collection="e2e-a", q="Haus Archiv", limit=1)["total"]
    check(tracked >= 100, f"the pages pushed before the kill are in the graph: {tracked}")
    REPORT["hard_kill"] = {"integrity": s["store"]["integrity"], "archiveEntities": tracked, "reconcile": s["sync"]["reconcile"].get("last")}
    p.stop()
    return name, data, export_a, org_before_rebuild


def peer_two(root, backup_name, backup, export_a, org):
    step(16, "restore in a test environment")
    p = Peer(root, ["scoutro.kg.enabled=true", "scoutro.kg.collections=e2e-a,e2e-b", "scoutro.kg.jsonld.enabled=true"], "peer 2")
    p.start()
    p.wait("the start backfill", lambda s: (s.get("sync") or {}).get("reconcile", {}).get("last") is not None)
    target = p.root / "DATA/SCOUTRO/knowledge/backup" / backup_name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(backup)
    t0 = time.monotonic()
    status, body = p.control("restore", backup=backup_name)
    check(status == 200, f"restore: {status} {str(body)[:500]}")
    s, _ = p.wait("the restored graph", lambda s: s["state"] == "running" and s["sync"]["reconcile"].get("awaitingConfirmation")
                  or p.settled(s), timeout=300)
    timing("restore_s", time.monotonic() - t0)
    restored = p.kg("/export/download", format="json", collection="e2e-a")

    def ids(e):
        return sorted(x["id"] for x in e["items"] if x.get("record") == "entity")
    check(ids(restored) == ids(export_a) and org in json.dumps(restored), "the restored graph has the same entities and IDs")
    # this environment's index is empty: the reconcile's brake keeps the graph instead of deleting it
    check(s["sync"]["reconcile"].get("awaitingConfirmation") is True, f"brake against the empty index: {s['sync']['reconcile']}")
    REPORT["restore"] = {"entities": len(ids(restored)), "brake": s["sync"]["reconcile"].get("last")}
    p.stop()


def faq_page(title, n):
    """A page with an FAQ graph of about 12 KB (below jsonld.maxBytesPerDoc, so it is captured)."""
    return doc(title, ld({"@context": "https://schema.org", "@type": "FAQPage", "mainEntity": [
        {"@type": "Question", "name": f"Frage {n}.{j}?", "acceptedAnswer": {"@type": "Answer", "text": "Antwort " * 40}}
        for j in range(30)]}), f"<p>{title} mit vielen Fragen.</p>", ["/"])


def peer_three(root):
    """Limits: a disk reserve the graph cannot meet, a tiny JSON-LD budget, the queue overflowing during a pause."""
    print("-- limits: low disk, JSON-LD budget, queue overflow", flush=True)
    p = Peer(root, ["scoutro.kg.enabled=true", "scoutro.kg.collections=e2e-c", "scoutro.kg.jsonld.enabled=true",
                    "scoutro.kg.reconcile.debounceSeconds=5",
                    "scoutro.kg.jsonld.maxTotalBytes=1048576", "scoutro.kg.disk.reserveBytes=17592186044416",
                    "scoutro.kg.capture.maxPending=1000", "scoutro.kg.queue.maxItems=1000"], "peer 3")
    p.start()
    s, _ = p.wait("the start", lambda s: s["state"] == "running" and s.get("jsonld", {}).get("state") == "paused")
    check("disk_reserve" in json.dumps(s["storage"].get("reasons")), f"simulated low disk: growth paused {s['storage'].get('reasons')}")
    check(s["jsonld"]["reason"] == "disk_reserve", f"the JSON-LD capture pauses too: {s['jsonld']}")
    p.crawl(f"http://{HOST_C}:{SITE_PORT}/", "e2e-c", depth=1, max_pages=60)
    indexed = p.solr_count("e2e-c")
    check(indexed >= 30, f"the crawl was not blocked by the graph's limits: {indexed} pages indexed")
    s = p.status()
    check(s["sync"]["processed"].get("published", 0) == 0, "no graph growth below the disk reserve")
    REPORT["limits"] = {"indexed_low_disk": indexed, "storage": s["storage"].get("reasons"), "jsonld_low_disk": s.get("jsonld")}
    p.stop()
    # the same peer with a reachable reserve: the graph catches up
    conf = p.root / "DATA/SETTINGS/yacy.conf"
    conf.write_text(conf.read_text().replace("scoutro.kg.disk.reserveBytes=17592186044416", "scoutro.kg.disk.reserveBytes=0"))
    p.start()
    s, took = p.wait("catching up", lambda s: p.settled(s) and s["sync"]["processed"]["published"] >= indexed, timeout=600)
    timing("catch_up_after_low_disk_s", took)
    check(True, "the graph caught up once the reserve allowed it")
    # the JSON-LD budget (1 MiB): about 12 KB per page; the capture pauses at the brake, the pages are still indexed
    pushed = 0
    for batch in range(6):
        p.push([(f"http://{HOST_C}:{SITE_PORT}/faq/{batch}-{i}", faq_page(f"FAQ {batch}-{i}", batch * 100 + i)) for i in range(20)], "e2e-c")
        pushed += 20
        p.wait("the graph", lambda s: p.settled(s), timeout=300)
        time.sleep(31)  # the capture policy is evaluated every 30 s
    s = p.status()
    jl = s["jsonld"]
    REPORT["limits"]["jsonld_budget"] = jl
    check(jl["level"] in ("brake", "full") and jl["state"] == "paused" and jl["reason"] == "jsonld_budget",
          f"the JSON-LD budget brakes the capture: {jl}")
    check(p.solr_count("e2e-c") == indexed + pushed, "every page is indexed, with or without its JSON-LD")
    check(jl["capture"]["skippedDocs"] > 0, f"later pages indexed without JSON-LD: {jl['capture']}")
    # the queue overflows during a pause (caps 1 000): the reconcile finds what the queue could not hold
    p.control("pause")
    masse = [(f"http://{HOST_C}:{SITE_PORT}/masse/{i}", doc(f"Masse {i}", "", f"<p>Seite {i}</p>")) for i in range(1100)]
    for i in range(0, len(masse), 100):
        p.push(masse[i:i + 100], "e2e-c")
    s = p.status()
    REPORT["limits"]["overflow"] = {"changes": s["sync"].get("changes"), "queue": s["sync"].get("queue"), "lag": s["sync"].get("lag"),
                                    "reconcile": s["sync"]["reconcile"].get("reason")}
    p.control("resume")
    last = p.doc_id(f"http://{HOST_C}:{SITE_PORT}/masse/1099")
    first = p.doc_id(f"http://{HOST_C}:{SITE_PORT}/masse/0")

    def tracked(doc_id):
        status, _, raw = p.call("GET", f"/scoutro/api/v1/kg/sources/{doc_id}")
        return status == 200 and json.loads(raw)["source"]["state"] == "active"

    s, took = p.wait("the overflow to be reconciled", lambda s: p.settled(s) and tracked(first) and tracked(last), timeout=900)
    timing("overflow_recovery_s", took)
    check(True, "every page of the overflow is tracked after the reconcile")
    p.stop()


def main():
    site = http.server.ThreadingHTTPServer(("127.0.0.1", SITE_PORT), Site)
    threading.Thread(target=site.serve_forever, daemon=True).start()

    class Llm:
        server = None

        def start(self):
            self.server = http.server.ThreadingHTTPServer(("127.0.0.1", LLM_PORT), FakeModel)
            threading.Thread(target=self.server.serve_forever, daemon=True).start()

    llm = Llm()
    with tempfile.TemporaryDirectory(prefix="scoutro-kg-e2e-") as temporary:
        root = Path(temporary)
        current = None
        try:
            only = os.environ.get("SCOUTRO_E2E_ONLY")  # "limits": peer 3 alone, while developing
            if only != "limits":
                current = root / "peer1"
                backup_name, backup, export_a, org = peer_one(current, llm)
                current = root / "peer2"
                peer_two(current, backup_name, backup, export_a, org)
            current = root / "peer3"
            peer_three(current)
        except BaseException:
            if current is not None and (current / "peer.log").exists():
                print("--- end of peer.log ---\n" + (current / "peer.log").read_text(errors="replace")[-30000:], flush=True)
            raise
        finally:
            for process in PEERS:
                if process.poll() is None:
                    process.kill()
                    process.wait()
            site.shutdown()
            if llm.server:
                llm.server.shutdown()
    REPORT["checks"] = CHECKS[0]
    if os.environ.get("SCOUTRO_E2E_REPORT"):
        Path(os.environ["SCOUTRO_E2E_REPORT"]).write_text(json.dumps(REPORT, indent=2))
    print(f"PASS: {CHECKS[0]} end-to-end checks of the knowledge graph (16 steps, restore in a fresh peer, limits)", flush=True)


if __name__ == "__main__":
    main()
