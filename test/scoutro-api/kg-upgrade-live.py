#!/usr/bin/env python3
"""Upgrade of a Scoutro 0.7 knowledge graph to vocabulary 2, and the way back, on NEW disposable DATA only. GPL-2.0-or-later.

The sequence of docs/SCOUTRO_KNOWLEDGE.md, 9.4 and 9.5:

1. Scoutro 0.7 (schema 3) indexes a care operator's site (home page, two homes,
   a price list, a careers page) and builds its graph;
2. this version starts on the same DATA: quick_check, a verified copy
   backup/graph-<UTC>-before-upgrade.db, the additive migration to schema 4, the
   re-extraction with vocabulary 2: prices, a job posting, the operator of both
   homes, its industry, and the derived layer (the two homes share an operator);
3. 0.7 on the upgraded DATA: it refuses schema 4 and leaves the file untouched,
   search and indexing go on without the graph;
4. the way back: the copy before the upgrade put in place, 0.7 starts its graph
   again with the facts it had;
5. this version again: a second upgrade with a new copy, the business facts back.

Usage (a build of 0.7, e.g. a worktree of the release compiled with javac into a
class directory):

    SCOUTRO_OLD_ROOT=<0.7 worktree> SCOUTRO_OLD_CLASSES=<its classes> python3 test/scoutro-api/kg-upgrade-live.py

Nothing leaves the machine; no existing peer or DATA directory is touched.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = Path(__file__).resolve().parents[2]
JAVA = os.environ.get("JAVA", "java")
OLD_ROOT = Path(os.environ["SCOUTRO_OLD_ROOT"])
OLD_CLASSES = Path(os.environ["SCOUTRO_OLD_CLASSES"])
NEW_CP = f"{REPO}/build/classes/java/main:{REPO}/lib/*"
OLD_CP = f"{OLD_CLASSES}:{OLD_ROOT}/lib/*:{REPO}/lib/*"
sys.dont_write_bytecode = True
CHECKS = [0]
COLLECTION = "edelsenior-web"
SITE = "https://www.lindenhof-upgrade.test"


def check(condition, message):
    assert condition, message
    CHECKS[0] += 1


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


PORT = free_port()
BASE = f"http://127.0.0.1:{PORT}"
pw = urllib.request.HTTPPasswordMgrWithDefaultRealm()
pw.add_password(None, BASE, "admin", "yacy")
ADMIN = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(pw))


def call(method, path, body=None, headers=None):
    request = urllib.request.Request(BASE + path, method=method, headers=dict(headers or {}), data=body)
    try:
        with ADMIN.open(request, timeout=120) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def get(path, **query):
    status, raw = call("GET", path + ("?" + urllib.parse.urlencode(query) if query else ""))
    assert status == 200, (path, status, raw[:300])
    return json.loads(raw)


def control(action):
    status, raw = call("POST", "/scoutro/api/v1/kg/control", json.dumps({"action": action}).encode(),
                       {"Content-Type": "application/json", "Origin": BASE})
    assert status == 200, (action, status, raw[:300])
    return json.loads(raw)


def push(url, html):
    boundary = f"----scoutro{time.time_ns()}"
    fields = {"count": "1", "synchronous": "true", "commit": "true", "url-0": url, "collection-0": COLLECTION,
              "contentType-0": "text/html", "lastModified-0": "Tue, 15 Nov 1994 12:45:26 GMT", "responseHeader-0": ""}
    body = b"".join(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode() for k, v in fields.items())
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"data-0\"; filename=\"page.html\"\r\n"
             "Content-Type: text/html\r\n\r\n").encode() + html.encode() + f"\r\n--{boundary}--\r\n".encode()
    status, raw = call("POST", "/api/push_p.json", body, {"Content-Type": f"multipart/form-data; boundary={boundary}"})
    assert status == 200 and json.loads(raw).get("countsuccess") == 1, (status, raw[:300])


def solr(q, fl="id", rows=50):
    return get("/solr/select", q=q, fl=fl, rows=str(rows), wt="json")["response"]


def html(title, text, ld=None):
    script = f'<script type="application/ld+json">{json.dumps(ld, ensure_ascii=False)}</script>' if ld else ""
    return f"<html><head><title>{title}</title>{script}</head><body><h1>{title}</h1><p>{text}</p></body></html>"


OPERATOR = {"@context": "https://schema.org", "@type": "Organization", "name": "Lindenhof Pflege gGmbH", "url": SITE + "/",
            "telephone": "0221 4711000", "vatID": "DE111222333",
            "address": {"@type": "PostalAddress", "streetAddress": "Lindenstraße 12", "postalCode": "50674", "addressLocality": "Köln"}}


def home(name, street):
    return {"@context": "https://schema.org", "@type": "NursingHome", "name": name,
            "address": {"@type": "PostalAddress", "streetAddress": street, "postalCode": "50674", "addressLocality": "Köln"},
            "parentOrganization": {"@type": "Organization", "name": "Lindenhof Pflege gGmbH"}}


PAGES = [
    # the homes come first: their parent organisation joins the operator when its page is processed
    ("/haus-birke", "Haus Birke", "Haus Birke bietet 80 Plätze in der stationären Pflege.", home("Haus Birke", "Birkenweg 3")),
    ("/haus-eiche", "Haus Eiche", "Haus Eiche hat 60 Plätze und einen Garten.", home("Haus Eiche", "Eichenallee 7")),
    ("/", "Lindenhof Pflege gGmbH", "Wir begleiten ältere Menschen in Köln mit stationärer Pflege, Kurzzeitpflege und Tagespflege.", OPERATOR),
    ("/preise", "Preise", "Preise. Kurzzeitpflege: 89,90 € pro Tag. Tagespflege ab 49 € pro Tag. Stand: 08/2026.", OPERATOR),
    ("/karriere", "Karriere", "Karriere. Pflegefachkraft (m/w/d) in Vollzeit. Vergütung: 3.400 – 3.900 € brutto monatlich. "
                              "Bewerbungsfrist: 31.03.2027.", OPERATOR),
]


class Peer:
    def __init__(self, root, classpath, cwd, label):
        self.root, self.classpath, self.cwd, self.label = root, classpath, cwd, label
        self.process = None

    def start(self):
        self.log = (self.root / f"{self.label}.log").open("w")
        env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
        self.process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", self.classpath, "net.yacy.yacy",
                                         "-startup", str(self.root)], cwd=self.cwd, stdout=self.log, stderr=self.log, env=env)
        deadline = time.monotonic() + 180
        while True:
            if self.process.poll() is not None:
                raise RuntimeError(self.text()[-5000:])
            try:
                if call("GET", "/api/status_p.json")[0] == 200:
                    return
            except (OSError, urllib.error.URLError):
                pass
            if time.monotonic() > deadline:
                raise RuntimeError(f"{self.label} did not start\n" + self.text()[-5000:])
            time.sleep(0.5)

    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=120)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.log.close()

    def text(self):
        return (self.root / f"{self.label}.log").read_text(errors="replace")


def status():
    return get("/scoutro/api/v1/kg/status")


def settled(timeout=240):
    deadline = time.monotonic() + timeout
    while True:
        s = status()
        sync = s.get("sync") or {}
        lag = sync.get("lag") or {}
        rec = sync.get("reconcile") or {}
        if s.get("state") == "running" and sync.get("initialized") and lag.get("pending") == 0 and not lag.get("reconcile_pending") \
                and rec.get("current") is None:
            return s
        assert time.monotonic() < deadline, f"the graph did not settle: {json.dumps(s)[:3000]}"
        time.sleep(0.5)


def operator_id():
    items = get("/scoutro/api/v1/kg/entities", q="Lindenhof Pflege", type="organization", limit=10)["items"]
    return [i["id"] for i in items]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    with tempfile.TemporaryDirectory(prefix="scoutro-kg-upgrade-") as temporary:
        root = Path(temporary)
        settings = root / "DATA/SETTINGS"
        settings.mkdir(parents=True)
        (root / "DATA/DICTIONARIES/harvesting").mkdir(parents=True, exist_ok=True)
        for friends in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
            (root / "DATA/DICTIONARIES/harvesting" / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
        (root / "DATA/LOCALE/htroot/de").mkdir(parents=True, exist_ok=True)
        # the fixture domain .test is not a global domain; YaCy's parser drops it in the default network unit
        network = root / "fixture.network.unit"
        network.write_text((REPO / "defaults/yacy.network.webportal.unit").read_text().replace(
            "network.unit.domain = global", "network.unit.domain = any"))
        (settings / "yacy.conf").write_text("\n".join([
            f"port={PORT}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
            "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
            f"network.unit.definition={network}", "browserPopUpTrigger=false",
            "autocrawl=false", "server.https=false", "locale.language=default", "upnp.enabled=false", "donation.iframesource=",
            "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
            "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
            f"scoutro.kg.enabled=true", f"scoutro.kg.collections={COLLECTION}", "scoutro.kg.jsonld.enabled=true",
            f"scoutro.kg.jobs.collections={COLLECTION}",
        ]) + "\n")
        kg = root / "DATA/SCOUTRO/knowledge"
        peers = []

        def peer(classpath, cwd, label):
            p = Peer(root, classpath, cwd, label)
            peers.append(p)
            return p

        try:
            # 1. Scoutro 0.7 builds its graph (schema 3)
            old = peer(OLD_CP, OLD_ROOT, "old-1")
            old.start()
            for path, title, text, ld in PAGES:
                push(SITE + path, html(title, text, ld))
            s = settled()
            check(s["store"]["schemaVersion"] == 3, f"0.7 runs schema 3: {s.get('store')}")
            before = get("/scoutro/api/v1/kg/entities", limit=50)
            check(before["total"] >= 3, f"0.7 has its graph: {before['total']} entities")
            v1_ids = sorted(i["id"] for i in before["items"])
            old.stop()
            check(not list(kg.glob("backup/*-before-upgrade.db")), "no copy before the upgrade yet")

            # 2. this version: integrity check, a verified copy, the migration, the re-extraction with vocabulary 2
            new = peer(NEW_CP, REPO, "new-1")
            new.start()
            s = settled()
            check(s["store"]["schemaVersion"] == 4, f"this version migrated to schema 4: {s.get('store')}")
            up = s.get("upgrade") or {}
            check(up.get("from") == 3 and up.get("to") == 4 and up.get("quickCheck") == "ok", f"the upgrade: {up}")
            copy = kg / "backup" / str(up.get("backup"))
            check(copy.is_file() and copy.name.endswith("-before-upgrade.db"), f"a copy before the upgrade: {up.get('backup')}")
            listed = {b["file"]: b for b in get("/scoutro/api/v1/kg/backups")["items"]}
            check(listed.get(copy.name, {}).get("kind") == "before_upgrade", f"the backup list names its kind: {list(listed.values())[:3]}")
            copy_sha = sha256(copy)
            check(not up.get("hold"), "room for the copy: nothing waits")
            # the re-extraction with vocabulary 2 has run once the graph settled; the facts of 0.7 kept their IDs
            after = get("/scoutro/api/v1/kg/entities", limit=100)
            check(set(v1_ids) <= {i["id"] for i in after["items"]}, "the entities of 0.7 keep their IDs")
            ops = operator_id()
            check(len(ops) == 1, f"one operator: {ops}")
            view = get(f"/scoutro/api/v1/kg/entities/{ops[0]}/business")
            prices = {p["service_name"]: p for p in view.get("prices", [])}
            check("Kurzzeitpflege" in prices and prices["Kurzzeitpflege"]["value"].get("amount") == "89.90", f"prices: {list(prices)}")
            check(prices["Kurzzeitpflege"]["value"].get("as_of") == "2026-08", "the price's date as the page wrote it")
            jobs = (view.get("jobs") or {}).get("items", [])
            check(any(j["title"].startswith("Pflegefachkraft") for j in jobs), f"the job posting: {jobs}")
            rel_text = json.dumps(view.get("relations"), ensure_ascii=False)
            check("Haus Birke" in rel_text and "Haus Eiche" in rel_text, "the operator of both homes")
            # residential care (87.10) and day care (88.10), equally supported: their common level is the main industry
            industry = view.get("industry") or {}
            codes = [industry.get("main", {}).get("code")] + [i.get("code") for i in industry.get("secondary", [])]
            check(industry.get("main", {}).get("code") in ("R", "87", "87.10") and "87.10" in codes, f"the industry: {industry}")
            before_run = (status().get("derived") or {}).get("lastRun") or 0
            control("derive")
            deadline = time.monotonic() + 180
            while ((status().get("derived") or {}).get("lastRun") or 0) <= before_run:
                assert time.monotonic() < deadline, f"no derive pass: {status().get('derived')}"
                time.sleep(1)
            derived = get("/scoutro/api/v1/kg/derived", collection=COLLECTION)
            check(any(d["kind"] == "same_operator" for d in derived["items"]), f"the two homes share an operator: {derived}")
            new.stop()
            check(sha256(copy) == copy_sha, "the copy is unchanged")

            # 3. 0.7 on the upgraded DATA: it refuses schema 4, the file stays as it is, search goes on
            db = kg / "graph.db"
            upgraded_sha = sha256(db)
            old = peer(OLD_CP, OLD_ROOT, "old-2")
            old.start()
            deadline = time.monotonic() + 60
            while status().get("state") == "starting":
                assert time.monotonic() < deadline
                time.sleep(0.5)
            s = status()
            check(s.get("state") != "running" and "schema" in json.dumps(s).lower(), f"0.7 refuses schema 4: {json.dumps(s)[:600]}")
            check(solr("*:*")["numFound"] >= len(PAGES), "search goes on without the graph")
            push(SITE + "/aktuelles", html("Aktuelles", "Sommerfest im Haus Birke."))
            check(solr("Sommerfest")["numFound"] >= 1, "and indexing")
            old.stop()
            check(sha256(db) == upgraded_sha, "0.7 left the upgraded graph untouched")

            # 4. the way back: the copy before the upgrade in place, 0.7 starts its graph again
            for suffix in ("", "-wal", "-shm"):
                f = kg / f"graph.db{suffix}"
                if f.exists():
                    f.rename(root / f"upgraded-graph.db{suffix}")
            shutil.copyfile(copy, db)
            old = peer(OLD_CP, OLD_ROOT, "old-3")
            old.start()
            s = settled()
            check(s["state"] == "running", "0.7 runs on the copy")
            back = get("/scoutro/api/v1/kg/entities", limit=100)
            check(set(v1_ids) <= {i["id"] for i in back["items"]}, "with the entities it had")
            check(get("/scoutro/api/v1/kg/sources/" + solr('sku:"' + SITE + '/aktuelles"')["docs"][0]["id"])["source"]["state"] == "active",
                  "and takes in the page indexed meanwhile")
            old.stop()

            # 5. this version again: a second upgrade with a new copy
            new = peer(NEW_CP, REPO, "new-2")
            new.start()
            s = settled()
            up2 = s.get("upgrade") or {}
            check(up2.get("from") == 3 and up2.get("backup") and up2["backup"] != copy.name, f"a second upgrade, a new copy: {up2}")
            ops = operator_id()
            view = get(f"/scoutro/api/v1/kg/entities/{ops[0]}/business")
            check(any(p["service_name"] == "Kurzzeitpflege" for p in view.get("prices", [])), "the business facts are back")
            new.stop()
        except BaseException:
            for p in peers:
                try:
                    print(f"--- {p.label} log ---\n" + p.text()[-15000:])
                except OSError:
                    pass
            raise
        finally:
            for p in peers:
                if p.process and p.process.poll() is None:
                    p.process.kill()
    print(f"PASS: {CHECKS[0]} upgrade checks (0.7 → upgrade with a copy → 0.7 refuses → the copy back in 0.7 → upgrade again)", flush=True)


if __name__ == "__main__":
    main()
