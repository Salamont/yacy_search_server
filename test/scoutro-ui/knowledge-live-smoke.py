#!/usr/bin/env python3
"""Disposable live harness for the knowledge graph interface (packages 3, 6 and 6.1). GPL-2.0-or-later.

Starts a NEW temporary peer with the graph following the collections kga and
kgb (LLM tier for kga, fake OpenAI-compatible model on 127.0.0.1), indexes
three pages and a small business set (an operator with two homes, prices and
a job in kga; a software firm in kgb; "SAP" of three providers in kga and kgb;
a followed collection kgc without a vocabulary) through YaCy's parser (api/push_p),
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

# package 6.1: "SAP" of three providers (two in kgb, one in kga), each its own service; a parent company for depth 2;
# a followed collection kgc without a vocabulary (generic facts only)
HOST_E, HOST_F, HOST_G, HOST_H = "www.ctcon-ui.de", "www.beta-it-ui.de", "www.gamma-ui.de", "www.neuportal-ui.de"
HOST_I = "www.zimmerei-boehmer-ui.de"


def sap_provider(name, host, extra, price):
    offer = '{"@type":"Offer","itemOffered":{"@type":"Service","name":"SAP"}' + (f',"price":"{price}","priceCurrency":"EUR"' if price else '') + '}'
    return ('{"@context":"https://schema.org","@type":"Organization","name":"' + name + '","url":"https://' + host + '/"' + extra
            + ',"makesOffer":[' + offer + ',{"@type":"Offer","itemOffered":{"@type":"Service","name":"Cloud-Migration"}}]}')


SERVICES = [
    (f"https://{HOST_E}/leistungen", "kgb", business("Leistungen CTcon", sap_provider("CTcon UI GmbH", HOST_E,
        ',"address":{"streetAddress":"Hafenstraße 1","postalCode":"20457","addressLocality":"Hamburg"},'
        '"parentOrganization":{"@type":"Organization","name":"CT Holding UI AG","url":"https://www.ct-holding-ui.de/"}', "120"), "Leistungen.")),
    (f"https://{HOST_F}/angebot", "kgb", business("Angebot Beta", sap_provider("Beta IT UI AG", HOST_F,
        ',"address":{"streetAddress":"Ring 2","postalCode":"80331","addressLocality":"München"}', None), "Angebot.")),
    (f"https://{HOST_G}/sap", "kga", business("SAP Gamma", sap_provider("Gamma Pflege-IT UI GmbH", HOST_G, "", "99"), "SAP.")),
    # a services page whose site declares no operator: an organisation without a name (the domain_operator placeholder)
    (f"https://{HOST_I}/leistungen", "kgb", business("Leistungen", '{"@type":"WebPage","name":"Leistungen"}',
                                                    "Unsere Leistungen: Cloud-Migration.")),
    (f"https://{HOST_H}/", "kgc", business("Neuportal", '{"@type":"Organization","name":"Neuportal UI GmbH","url":"https://www.neuportal-ui.de/",'
                                           '"telephone":"030 5550000"}', "Unsere Leistungen: Tagespflege und SAP-Beratung.")),
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
        "scoutro.kg.enabled=true", "scoutro.kg.collections=kga,kgb,kgc", "scoutro.kg.jsonld.enabled=true",
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
            for url, collection, html in BUSINESS + SERVICES + PAGES:
                push(c, url, html, collection)
            total = len(BUSINESS) + len(SERVICES) + len(PAGES)
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
            assert [f["code"] for f in facets["customer_types"]] == ["b2b"] and {f["code"] for f in facets["categories"]} == {"software/sap", "software/cloud"} \
                and facets["counts"]["jobs"] == 0, facets
            assert {d["kind"] for d in get(c, "/scoutro/api/v1/kg/derived?collection=kga")["items"]} == {"same_operator"}
            assert get(c, "/scoutro/api/v1/kg/derived?kind=suggested_customer&collection=kgb")["total"] == 0
            checks += 16
            # package 6.1: every provider's SAP is its own service, listed with its provider and domain; groups count them
            hits = get(c, "/scoutro/api/v1/kg/entities?q=SAP&type=service&collection=kgb")["items"]
            seen = sorted((h["context"]["providers"][0]["name"], h["context"]["providers"][0]["hosts"][0]) for h in hits)
            assert seen == [("Beta IT UI AG", HOST_F), ("CTcon UI GmbH", HOST_E)] and len({h["id"] for h in hits}) == 2, hits
            assert all(h["context"]["collections"] == ["kgb"] and h["context"]["provider_count"] == 1 for h in hits), hits
            sap = next(h for h in hits if h["context"]["providers"][0]["name"] == "CTcon UI GmbH")
            group = lambda q: get(c, "/scoutro/api/v1/kg/services?q=SAP" + q)["items"][0]
            assert (group("&collection=kgb")["providers"], group("&collection=kga")["providers"], group("")["providers"]) == (2, 1, 3)
            assert [x["name"] for x in group("&collection=kgb")["collections"]] == ["kgb"] and group("&collection=kgb")["with_price"] == 1
            rows = get(c, "/scoutro/api/v1/kg/services/providers?name=SAP&collection=kgb")
            assert rows["total"] == 2 and sorted(r["prices"]["current"] for r in rows["items"]) == [0, 1], rows
            assert "Gamma" not in json.dumps(rows) and "kga" not in json.dumps(rows["items"]), "no other collection in the group"
            # package 6.2: the network of all providers of the name; each line names its provider's own service, nothing merged
            gnet = get(c, "/scoutro/api/v1/kg/services/network?name=SAP&collection=kgb")
            assert gnet["aggregated"] and gnet["center"] == "service_group:sap" and gnet["neighbours"] == 2, gnet
            assert sorted(e["service"]["id"] for e in gnet["edges"]) == sorted(h["id"] for h in hits), gnet["edges"]
            assert all(e["to"] == gnet["center"] and e["type"] == "offers" and e["direction"] == "in" for e in gnet["edges"]), gnet["edges"]
            assert "Gamma" not in json.dumps(gnet) and "kga" not in json.dumps(gnet["edges"]), "no other collection in the network of the name"
            assert get(c, "/scoutro/api/v1/kg/services/network?name=SAP")["neighbours"] == 3
            assert status(c, "/scoutro/api/v1/kg/services/network?name=SAP&collection=kgc") == 404
            # package 6.2: the knowledge graph settings list every collection of the catalog, never robot_*; the catalog reads the
            # committed index (cached for 10 s), so it lists kgb and kgc once a search sees their pages
            deadline = time.monotonic() + 120
            while {"kga", "kgb", "kgc"} - {x["id"] for x in get(c, "/scoutro/api/v1/collections")["collections"]}:
                assert time.monotonic() < deadline, "the catalog never listed kga, kgb and kgc"
                time.sleep(2)
            rows_s = get(c, "/scoutro/api/v1/kg/collections")["collections"]
            names_s = [r["collection"] for r in rows_s]
            assert {"kga", "kgb", "kgc"} <= set(names_s) and not any(n.startswith("robot_") for n in names_s), names_s
            assert all(r["inCatalog"] and r["active"] for r in rows_s if r["collection"] in ("kga", "kgb", "kgc")), rows_s
            checks += 6
            ctcon = sap["context"]["providers"][0]["id"]
            snet = get(c, f"/scoutro/api/v1/kg/entities/{sap['id']}/neighborhood?collection=kgb&depth=2")
            assert any(e["from"] == ctcon and e["to"] == sap["id"] and e["type"] == "offers" and e["direction"] == "in" for e in snet["edges"]), snet["edges"]
            labels_2 = {n["label"] for n in snet["nodes"]}
            assert "CT Holding UI AG" in labels_2 and "Cloud-Migration" not in labels_2, labels_2
            assert next(n for n in snet["nodes"] if n["id"] == ctcon)["hosts"] == [HOST_E]
            assert status(c, f"/scoutro/api/v1/kg/entities/{sap['id']}/neighborhood?collection=kga") == 404
            # package 6.1: an organisation without a stated name is shown by its domain, never by its ID
            orgs_b = get(c, "/scoutro/api/v1/kg/entities?collection=kgb&type=organization&limit=100")["items"]
            unnamed = [o for o in orgs_b if o["name"] is None]
            assert unnamed and unnamed[0]["display_name"] == "Zimmerei Boehmer Ui" and unnamed[0]["display_name_source"] == "domain" \
                and unnamed[0]["display_host"] == HOST_I[4:], orgs_b
            assert all(o["display_name"] and not o["display_name"].startswith("kge_") for o in orgs_b), orgs_b
            unnamed_net = get(c, f"/scoutro/api/v1/kg/entities/{unnamed[0]['id']}/neighborhood?collection=kgb")
            assert not any((n.get("display_name") or "").startswith("kge_") for n in unnamed_net["nodes"]), unnamed_net["nodes"]
            # a followed collection without a vocabulary: listed, said so, generic facts only
            # the document counts of the status are at most 10 s old
            s_c = wait(c, "the document count of kgc", lambda s: any(r["collection"] == "kgc" and r["documents"] == 1 for r in s["collections"]), 30)
            rows_c = {r["collection"]: r for r in s_c["collections"]}
            kgc = rows_c["kgc"]
            assert kgc["followed"] and kgc["vocabulary"] is None and kgc["vocabularySource"] == "none" and not kgc["jobs"] and kgc["documents"] == 1, kgc
            assert rows_c["kga"]["vocabulary"] == "care" and rows_c["kga"]["jobs"] and rows_c["kgb"]["vocabularySource"] == "setting", rows_c
            assert get(c, "/scoutro/api/v1/kg/facets?collection=kgc")["categories"] == [], "no guessed category for kgc"
            checks += 16
            print(f"PASS: {checks} live knowledge read API checks (collection isolation, vocabulary 2, services across providers)", flush=True)
            env = {**os.environ, "SCOUTRO_URL": BASE, "SCOUTRO_KG_ENTITY": org["id"], "SCOUTRO_KG_HOST": HOST_A,
                   "SCOUTRO_KG_ONLY_B": b_only["id"], "SCOUTRO_KG_OPERATOR": operator["id"], "SCOUTRO_KG_SOFT": soft["id"],
                   "SCOUTRO_KG_SAP": sap["id"], "SCOUTRO_KG_CTCON": ctcon, "SCOUTRO_KG_UNNAMED": unnamed[0]["id"]}
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
