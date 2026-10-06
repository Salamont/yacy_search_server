#!/usr/bin/env python3
"""Disposable live harness for the knowledge graph interface (packages 3 and 6). GPL-2.0-or-later.

Starts a NEW temporary peer with the graph following the collections kga and
kgb (LLM tier for kga, fake OpenAI-compatible model on 127.0.0.1), indexes
three pages and a small business set (an operator with two homes, prices and
a job in kga; a software firm in kgb) through YaCy's parser (api/push_p),
waits for the graph and the derived layer, checks the read API, the business
view, network, comparison, facets and derived rows and their collection
isolation, then runs the Playwright test
test/scoutro-ui/knowledge-ui-test.mjs. Nothing leaves the machine; no
existing peer or DATA directory is touched.

Requires `ant compile`, a JDK, Node with Playwright and Chromium. Optional:
JAVA, NODE_PATH, SCOUTRO_CHROMIUM_PATH, SCOUTRO_SCREENSHOTS.
"""
import http.server
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


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


PORT, LLM_PORT = free_port(), free_port()
BASE = f"http://127.0.0.1:{PORT}"
HOST_A = "www.muster-pflege-ui.de"
HOST_B = "www.nur-bee-ui.de"


class FakeModel(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
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


def page(name, host, extra, text):
    return f"""<html><head><title>Impressum {name}</title>
<script type="application/ld+json">{{"@context":"https://schema.org","@type":"Organization","name":"{name}",
"url":"https://{host}/","telephone":"030 1234567"{extra}}}</script></head>
<body><h1>Impressum</h1><p>{text}</p></body></html>"""


HOST_C = "www.lindenhof-ui.de"
HOST_D = "www.pflegesoft-ui.de"
OP_C = '{"@type":"Organization","name":"Lindenhof Pflege gGmbH","url":"https://www.lindenhof-ui.de/"}'


def business(title, jsonld, text):
    return f"""<html><head><title>{title}</title>
<script type="application/ld+json">{jsonld}</script></head>
<body><h1>{title}</h1><p>{text}</p></body></html>"""


def home(name, street):
    return ('{"@context":"https://schema.org","@type":"NursingHome","name":"' + name + '","address":{"streetAddress":"' + street
            + '","postalCode":"10115","addressLocality":"Berlin"},"parentOrganization":' + OP_C + '}')


# vocabulary 2 (package 6): an operator with two homes, prices and jobs in kga (care); a software firm of kgb (software)
# naming a customer and the kind of homes it serves; suggestions between them only for a viewer of both collections
BUSINESS = [
    (f"https://{HOST_C}/", "kga", business("Lindenhof Pflege", OP_C[:-1] + ',"telephone":"030 7654321","faxNumber":"030 7654322"}',
                                            "Willkommen bei der Lindenhof Pflege gGmbH.")),
    (f"https://{HOST_C}/haus-birke", "kga", business("Haus Birke", home("Haus Birke", "Birkenweg 1"), "Haus Birke.")),
    (f"https://{HOST_C}/haus-eiche", "kga", business("Haus Eiche", home("Haus Eiche", "Eichenweg 1"), "Haus Eiche.")),
    (f"https://{HOST_C}/preise", "kga", business("Preise", OP_C,
                                                 "Preise. Tagespflege ab 49 € pro Tag. Kurzzeitpflege: 89,90 €/Tag. Stand: 09/2026")),
    (f"https://{HOST_C}/kosten", "kga", business("Kosten", OP_C, "Kosten. Tagespflege ab 59 € pro Tag.")),
    (f"https://{HOST_C}/karriere", "kga", business("Karriere", OP_C,
                                                   "Karriere. Pflegefachkraft (m/w/d) in Vollzeit. Vergütung: 3.400 – 3.900 € brutto monatlich. "
                                                   "Bewerbungsfrist: 31.03.2027.")),
    (f"https://{HOST_D}/fuer-wen", "kgb", business("Für wen", '{"@type":"Organization","name":"PflegeSoft UI GmbH","url":"https://www.pflegesoft-ui.de/"}',
                                                   "Für wen? Wir unterstützen Pflegeeinrichtungen bundesweit, nur für Geschäftskunden. "
                                                   "Unsere Kunden: Muster Klinikum UI GmbH.")),
]

PAGES = [
    (f"https://{HOST_A}/impressum", "kga", page("Muster Pflege gGmbH", HOST_A, "",
                                                 "Die Muster Pflege gGmbH betreibt das Haus Lindenhof in Berlin.")),
    (f"https://{HOST_A}/ueber-uns", "kgb", page("Muster Pflege gGmbH", HOST_A,
                                                 ',"alternateName":"Geheime Holding","vatID":"DE123456789"', "Über uns.")),
    (f"https://{HOST_B}/impressum", "kgb", page("Nur Bee GmbH", HOST_B, "", "Die Nur Bee GmbH berät.")),
]


def client():
    pw = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    pw.add_password(None, BASE, "admin", "yacy")
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(pw))


def get(c, path):
    with c.open(BASE + path, timeout=30) as r:
        return json.loads(r.read())


def push(c, url, html, collection):
    boundary = f"----scoutro{time.time_ns()}"
    fields = {"count": "1", "synchronous": "true", "commit": "true", "url-0": url, "collection-0": collection,
              "contentType-0": "text/html", "lastModified-0": "Tue, 15 Nov 1994 12:45:26 GMT", "responseHeader-0": ""}
    body = b"".join(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
                    for k, v in fields.items())
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"data-0\"; filename=\"page.html\"\r\n"
             "Content-Type: text/html\r\n\r\n").encode() + html.encode() + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(BASE + "/api/push_p.json", data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with c.open(req, timeout=120) as r:
        assert json.loads(r.read()).get("countsuccess") == 1


def post(c, path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "Origin": BASE})
    with c.open(req, timeout=30) as r:
        return json.loads(r.read())


def status(c, path):
    try:
        with c.open(BASE + path, timeout=30) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def wait(c, what, predicate, timeout=180):
    deadline = time.monotonic() + timeout
    while True:
        s = get(c, "/scoutro/api/v1/kg/status")
        if predicate(s):
            return s
        if time.monotonic() > deadline:
            raise AssertionError(f"timed out waiting for {what}: {json.dumps(s.get('sync', {}).get('processed'))} {json.dumps(s.get('llm'))[:800]}")
        time.sleep(0.5)


server = http.server.ThreadingHTTPServer(("127.0.0.1", LLM_PORT), FakeModel)
threading.Thread(target=server.serve_forever, daemon=True).start()
with tempfile.TemporaryDirectory(prefix="scoutro-kg-ui-") as temporary:
    root = Path(temporary)
    config = root / "DATA/SETTINGS/yacy.conf"
    config.parent.mkdir(parents=True)
    (root / "DATA/DICTIONARIES/harvesting").mkdir(parents=True, exist_ok=True)
    for friends in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
        (root / "DATA/DICTIONARIES/harvesting" / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
    models = [{"service": "OLLAMA", "model": "fixture", "hoststub": f"http://127.0.0.1:{LLM_PORT}", "api_key": "",
               "max_tokens": "1024", "chat": False, "tldr": False, "logreport": False, "knowledge": True}]
    config.write_text("\n".join([
        f"port={PORT}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
        "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
        "network.unit.definition=defaults/yacy.network.webportal.unit", "browserPopUpTrigger=false",
        "autocrawl=false", "server.https=false", "locale.language=browser", "upnp.enabled=false", "donation.iframesource=",
        "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
        "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
        "scoutro.kg.enabled=true", "scoutro.kg.collections=kga,kgb", "scoutro.kg.jsonld.enabled=true",
        "scoutro.kg.llm.collections=kga", "scoutro.kg.llm.kinds.kga=nursinghome",
        "scoutro.kg.vocab.kga=care", "scoutro.kg.vocab.kgb=software", "scoutro.kg.jobs.collections=kga",
        "ai.production_models=" + json.dumps(models, separators=(",", ":")),
    ]) + "\n")
    (root / "DATA/LOCALE/htroot/de").mkdir(parents=True)
    process = None
    with (root / "peer.log").open("w") as log:
        try:
            process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", CLASSPATH,
                                        "net.yacy.yacy", "-startup", str(root)], cwd=REPO, stdout=log, stderr=log)
            c = client()
            deadline = time.monotonic() + 90
            while True:
                if process.poll() is not None:
                    raise RuntimeError((root / "peer.log").read_text()[-5000:])
                try:
                    get(c, "/scoutro/api/v1/kg/status")
                    break
                except (OSError, urllib.error.URLError):
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(0.5)
            wait(c, "the start backfill", lambda s: s.get("sync", {}).get("reconcile", {}).get("last") is not None)
            # the business pages first: the three pages of the isolation checks stay the newest objects
            for url, collection, html in BUSINESS + PAGES:
                push(c, url, html, collection)
            total = len(BUSINESS) + len(PAGES)
            wait(c, f"{total} published pages and the LLM result",
                 lambda s: s["sync"]["processed"]["published"] >= total and s["llm"]["processed"]["published"] >= 1)
            checks = 0
            # read API: collection isolation
            a = get(c, "/scoutro/api/v1/kg/entities?collection=kga&limit=100")
            names_a = sorted(e["name"] for e in a["items"])
            assert "Nur Bee GmbH" not in names_a and "Muster Pflege gGmbH" in names_a, names_a
            org = next(e for e in a["items"] if e["name"] == "Muster Pflege gGmbH")
            detail_a = get(c, f"/scoutro/api/v1/kg/entities/{org['id']}?collection=kga")
            assert "Geheime" not in json.dumps(detail_a) and detail_a["identifiers"] == [], detail_a
            detail = get(c, f"/scoutro/api/v1/kg/entities/{org['id']}")
            assert "Geheime Holding" in detail["aliases"] and detail["identifiers"], detail
            facts = get(c, f"/scoutro/api/v1/kg/entities/{org['id']}/statements?collection=kga&limit=100")["items"]
            assert any(f["predicate"] == "operates" and f["quality"] == "uncertain" and f["kinds"] == ["llm"] for f in facts), facts
            b_only = next(e for e in get(c, "/scoutro/api/v1/kg/entities?collection=kgb&q=Nur")["items"])
            try:
                get(c, f"/scoutro/api/v1/kg/entities/{b_only['id']}?collection=kga")
                raise AssertionError("B's entity visible to kga")
            except urllib.error.HTTPError as e:
                assert e.code == 404, e.code
            host_a = get(c, f"/scoutro/api/v1/kg/hosts/{HOST_B}/entities?collection=kga")
            assert host_a["total"] == 0, host_a
            checks += 7
            # vocabulary 2: the derived layer on request, then the business view, network, comparison, facets and
            # derived rows, each computed only over the viewer's collections (suggestions need both collections)
            post(c, "/scoutro/api/v1/kg/control", {"action": "derive"})
            wait(c, "the derived pass", lambda s: (((s.get("derived") or {}).get("last") or {}).get("computed") or 0) >= 4)
            operator = next(e for e in get(c, "/scoutro/api/v1/kg/entities?collection=kga&type=organization&limit=100")["items"]
                            if e["name"] == "Lindenhof Pflege gGmbH")
            soft = next(e for e in get(c, "/scoutro/api/v1/kg/entities?collection=kgb&type=organization&limit=100")["items"]
                        if e["name"] == "PflegeSoft UI GmbH")
            kga = get(c, f"/scoutro/api/v1/kg/entities/{operator['id']}/business?collection=kga")
            assert kga["industry"]["main"]["code"] in ("87.10", "88.10") and kga["industry"]["main"]["classification"] == "NACE Rev. 2.1 / WZ 2025", kga["industry"]
            assert sorted(sv["name"] for sv in kga["services"]) == ["Kurzzeitpflege", "Tagespflege"], kga["services"]
            day = [p for p in kga["prices"] if p["service_name"] == "Tagespflege"]
            assert sorted(p["value"]["amount"] for p in day) == ["49.00", "59.00"] and {p["status"] for p in day} == {"conflicting"}, day
            assert next(p for p in day if p["value"]["amount"] == "49.00")["as_of_basis"] == "stated", day
            job = kga["jobs"]["items"][0]
            assert job["title"] == "Pflegefachkraft (m/w/d)" and job["status"] == "open" and job["salary"][0]["value"]["unit"] == "month", job
            assert "suggested_matches" not in kga and "PflegeSoft" not in json.dumps(kga), "a kga viewer sees no kgb suggestion"
            full = get(c, f"/scoutro/api/v1/kg/entities/{operator['id']}/business")
            match = full["suggested_matches"]["as_possible_customer"][0]
            assert match["other"]["name"] == "PflegeSoft UI GmbH" and match["fact"] is False and match["label"] == "suggestion", match
            assert status(c, f"/scoutro/api/v1/kg/entities/{operator['id']}/business?collection=kgb") == 404
            assert status(c, f"/scoutro/api/v1/kg/entities/{soft['id']}/neighborhood?collection=kga") == 404
            net = get(c, f"/scoutro/api/v1/kg/entities/{operator['id']}/neighborhood?collection=kga&suggested=true&depth=2")
            assert {e["type"] for e in net["edges"]} >= {"operates", "offers", "same_operator"} and not any(
                e["status"] == "suggested" for e in net["edges"]), net["edges"]
            assert "PflegeSoft" not in json.dumps(net), "no kgb node in a kga network"
            both = get(c, f"/scoutro/api/v1/kg/entities/{operator['id']}/neighborhood?suggested=true")
            assert any(e["type"] == "suggested_customer" and e["fact"] is False for e in both["edges"]), both["edges"]
            cmp = get(c, "/scoutro/api/v1/kg/compare?category=care/tagespflege&collection=kga")
            assert [r["service"]["name"] for r in cmp["rows"]] == ["Tagespflege"] and len(cmp["rows"][0]["prices"]) == 2, cmp
            assert get(c, "/scoutro/api/v1/kg/compare?category=care/tagespflege&collection=kgb")["rows"] == []
            facets = get(c, "/scoutro/api/v1/kg/facets?collection=kgb")
            assert [f["code"] for f in facets["customer_types"]] == ["b2b"] and facets["categories"] == [] and facets["counts"]["jobs"] == 0, facets
            assert {d["kind"] for d in get(c, "/scoutro/api/v1/kg/derived?collection=kga")["items"]} == {"same_operator"}
            assert get(c, "/scoutro/api/v1/kg/derived?kind=suggested_customer&collection=kgb")["total"] == 0
            checks += 16
            print(f"PASS: {checks} live knowledge read API checks (collection isolation, vocabulary 2)", flush=True)
            env = {**os.environ, "SCOUTRO_URL": BASE, "SCOUTRO_KG_ENTITY": org["id"], "SCOUTRO_KG_HOST": HOST_A,
                   "SCOUTRO_KG_ONLY_B": b_only["id"], "SCOUTRO_KG_OPERATOR": operator["id"], "SCOUTRO_KG_SOFT": soft["id"]}
            subprocess.run(["node", str(REPO / "test/scoutro-ui/knowledge-ui-test.mjs")], cwd=REPO, env=env, check=True, timeout=900)
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
