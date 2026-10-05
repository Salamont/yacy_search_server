#!/usr/bin/env python3
"""Rollback from the knowledge graph release to a version without it, on NEW disposable DATA only. GPL-2.0-or-later.

The sequence of docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 13 ("Rollback"):

1. the new version indexes pages with JSON-LD (ld_json_txt) and builds its graph;
2. the old version starts on the same DATA: it writes its own solrconfig.xml and
   schema.xml, every document is still there and searchable, it indexes a new
   page, and its log shows no error about the unknown field;
3. a partial (atomic) update with the OLD classes and the OLD core
   configuration on a document that carries ld_json_txt succeeds and keeps the
   field (YaCy's postprocessing updates documents this way). The old schema maps
   the name to the dynamic *_txt field; Lucene accepts that only because the new
   type text_stored has the same index options (and emits no token);
4. the new version starts again on the same DATA: the graph is intact, reconciles
   with the index and takes in the old version's page, and the new version
   rewrites the page the old version updated.

Usage (an old build, e.g. a worktree of the version before the graph, compiled
with javac into a class directory):

    SCOUTRO_OLD_ROOT=<old worktree> SCOUTRO_OLD_CLASSES=<its classes> python3 test/scoutro-api/kg-rollback-live.py

Nothing leaves the machine; no existing peer or DATA directory is touched.
"""
import json
import os
from pathlib import Path
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
OLD_CP = f"{OLD_CLASSES}:{REPO}/lib/*"
sys.dont_write_bytecode = True
CHECKS = [0]


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


def push(url, html, collection):
    boundary = f"----scoutro{time.time_ns()}"
    fields = {"count": "1", "synchronous": "true", "commit": "true", "url-0": url, "collection-0": collection,
              "contentType-0": "text/html", "lastModified-0": "Tue, 15 Nov 1994 12:45:26 GMT", "responseHeader-0": ""}
    body = b"".join(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode() for k, v in fields.items())
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"data-0\"; filename=\"page.html\"\r\n"
             "Content-Type: text/html\r\n\r\n").encode() + html.encode() + f"\r\n--{boundary}--\r\n".encode()
    status, raw = call("POST", "/api/push_p.json", body, {"Content-Type": f"multipart/form-data; boundary={boundary}"})
    assert status == 200 and json.loads(raw).get("countsuccess") == 1, (status, raw[:300])


def solr(q, fl="id", rows=50):
    return get("/solr/select", q=q, fl=fl, rows=str(rows), wt="json")["response"]


def searchable(n, timeout=60):
    """A search sees new pages once a new searcher opens (soft commit); the graph reads through real-time get."""
    deadline = time.monotonic() + timeout
    while (found := solr("*:*")["numFound"]) < n:
        assert time.monotonic() < deadline, f"{found} of {n} pages searchable"
        time.sleep(0.5)
    return found


def with_ld():
    """ld_json_txt is stored, not searchable: read it back instead of querying it."""
    return [d for d in solr("*:*", fl="id,sku,ld_json_txt", rows=200)["docs"] if d.get("ld_json_txt")]


def ld_hits():
    """Documents whose ld_json_txt has the term "organization" in the index: a term query, because the
    new type's query analyzer emits no token either."""
    return solr("{!term f=ld_json_txt}organization")["numFound"]


def page(name, phone):
    return (f'<html><head><title>{name}</title><script type="application/ld+json">{{"@context":"https://schema.org",'
            f'"@type":"Organization","name":"{name}","url":"https://www.rollback.test/","telephone":"{phone}"}}</script></head>'
            f"<body><h1>{name}</h1><p>Impressum der {name}.</p></body></html>")


class Peer:
    def __init__(self, root, classpath, cwd, label):
        self.root, self.classpath, self.cwd, self.label = root, classpath, cwd, label
        self.process = None

    def start(self):
        self.log = (self.root / f"{self.label}.log").open("w")
        env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
        self.process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", self.classpath, "net.yacy.yacy",
                                         "-startup", str(self.root)], cwd=self.cwd, stdout=self.log, stderr=self.log, env=env)
        deadline = time.monotonic() + 120
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


ATOMIC = r'''
import java.io.File;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;

public class AtomicUpdate {
    public static void main(String[] a) {
        try {
            run(a);
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    static void run(String[] a) throws Exception {
        EmbeddedInstance solr = new EmbeddedInstance(new File(a[0]), new File(a[1]), "collection1", new String[] {"collection1", "webgraph"});
        SolrClient c = solr.getDefaultServer();
        SolrInputDocument d = new SolrInputDocument();
        d.setField("id", a[2]);
        d.setField("title", Map.of("set", java.util.List.of("Partial update by the old version")));
        c.add(d);
        c.commit();
        SolrDocument got = c.getById(a[2]);
        System.out.println("RESULT " + new org.json.JSONObject().put("title", String.valueOf(got.getFieldValue("title")))
                .put("ld_json_txt", String.valueOf(got.getFieldValue("ld_json_txt"))));
        solr.close();
    }
}
'''


def main():
    with tempfile.TemporaryDirectory(prefix="scoutro-kg-rollback-") as temporary:
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
            "scoutro.kg.enabled=true", "scoutro.kg.collections=rb", "scoutro.kg.jsonld.enabled=true",
        ]) + "\n")
        new = Peer(root, NEW_CP, REPO, "new-1")
        old = Peer(root, OLD_CP, OLD_ROOT, "old")
        try:
            # 1. the new version writes documents with ld_json_txt and builds its graph
            new.start()
            for i in range(3):
                push(f"https://www.rollback.test/impressum-{i}", page(f"Rollback Test {i} GmbH", f"030 100000{i}"), "rb")
            deadline = time.monotonic() + 120
            while get("/scoutro/api/v1/kg/status")["sync"]["processed"]["published"] < 3:
                assert time.monotonic() < deadline, "graph did not publish"
                time.sleep(0.5)
            docs = searchable(3)
            carrying = with_ld()
            check(docs >= 3 and len(carrying) >= 3, f"documents with ld_json_txt: {docs}, {carrying}")
            check(ld_hits() == 0, "the field costs no index space (no token)")
            entities = get("/scoutro/api/v1/kg/entities", limit=50)["total"]
            check(entities >= 3, f"graph entities: {entities}")
            new.stop()

            # 2. the old version on the same DATA
            old.start()
            check(solr("*:*")["numFound"] == docs, "every document is still there")
            check(len(with_ld()) >= 3, "the field is still readable")
            check(solr("Rollback", fl="id,title")["numFound"] >= 3, "and searchable")
            push("https://www.rollback.test/neu", page("Rollback Neu GmbH", "030 2000000"), "rb")
            check(searchable(docs + 1) == docs + 1, "the old version indexes")
            old_page = solr('sku:"https://www.rollback.test/neu"')["docs"][0]["id"]
            old.stop()
            log = old.text()
            bad = [l for l in log.splitlines() if ("ld_json_txt" in l or "unknown field" in l.lower()) and ("SEVERE" in l or "ERROR" in l or "Exception" in l)]
            check(not bad, "no error about ld_json_txt in the old version's log: " + "\n".join(bad[:5]))
            conf = next((p for p in (root / "DATA/INDEX").rglob("collection1/conf/schema.xml")), None)
            check(conf is not None and "ld_json_txt" not in conf.read_text(), "the old version wrote its own schema")

            # 3. a partial update with the old classes and the old core configuration
            container = conf.parent.parent.parent
            src = root / "AtomicUpdate.java"
            src.write_text(ATOMIC)
            env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
            out = root / "atomic.log"
            with out.open("w") as o:
                job = subprocess.Popen([JAVA, "-cp", OLD_CP, str(src), str(OLD_ROOT / "defaults/solr"), str(container), carrying[0]["id"]],
                                       stdout=o, stderr=subprocess.STDOUT, text=True, cwd=OLD_ROOT, env=env)
                try:
                    job.wait(timeout=300)
                except subprocess.TimeoutExpired:
                    subprocess.run(["jcmd", str(job.pid), "Thread.print"], stdout=o, stderr=subprocess.STDOUT, timeout=60)
                    job.kill()
                    job.wait()
            text = out.read_text(errors="replace")
            line = next((l for l in text.splitlines() if l.startswith("RESULT ")), None)
            check(job.returncode == 0 and line, "atomic update: " + text[-6000:])
            got = json.loads(line[7:])
            check("Partial update" in got["title"] and "Organization" in got["ld_json_txt"], f"field kept by the partial update: {got}")

            # 4. the new version again: the graph is intact and follows the index
            new = Peer(root, NEW_CP, REPO, "new-2")
            new.start()
            deadline = time.monotonic() + 180
            while True:
                s = get("/scoutro/api/v1/kg/status")
                rec = s["sync"]["reconcile"]
                if s["state"] == "running" and rec.get("lastCompletedAt") and rec.get("current") is None and s["sync"]["lag"]["pending"] == 0:
                    break
                assert time.monotonic() < deadline, f"no reconcile: {json.dumps(s)[:2000]}"
                time.sleep(0.5)
            check(get("/scoutro/api/v1/kg/entities", limit=50)["total"] >= entities, "the graph is intact")
            check(get(f"/scoutro/api/v1/kg/sources/{old_page}")["source"]["state"] == "active", "and took in the old version's page")
            check(ld_hits() == 1, "the old version indexed the field of the page it updated as text")
            # the new version rewrites that page: same field options, the new type again (no token)
            url = carrying[0]["sku"]
            push(url, page("Rollback Test 0 GmbH", "030 3000000"), "rb")
            deadline = time.monotonic() + 120
            while "3000000" not in json.dumps(get(f"/scoutro/api/v1/kg/sources/{carrying[0]['id']}")):
                assert time.monotonic() < deadline, "the new version's rewrite did not reach the graph"
                time.sleep(0.5)
            check(True, "the new version rewrote the page and the graph followed")
            deadline = time.monotonic() + 60
            while ld_hits() != 0:
                assert time.monotonic() < deadline, "the rewritten page still has tokens in ld_json_txt"
                time.sleep(0.5)
            check(True, "the rewritten page has no token in the field again")
            new.stop()
        except BaseException:
            for p in (new, old):
                try:
                    print(f"--- {p.label} log ---\n" + p.text()[-15000:])
                except OSError:
                    pass
            raise
        finally:
            for p in (new, old):
                if p.process and p.process.poll() is None:
                    p.process.kill()
    print(f"PASS: {CHECKS[0]} rollback checks (new version → old version → partial update → new version)", flush=True)


if __name__ == "__main__":
    main()
