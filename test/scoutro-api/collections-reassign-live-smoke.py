#!/usr/bin/env python3
"""Live smoke test of collections.reassign on a disposable peer (2026-10-08, yowea.com).

Starts YaCy/Scoutro on a temporary DATA directory with the knowledge graph following stackfinder-web and
checkthecoach-web, indexes pages through YaCy's parser and index path (api/push_p) and then:

1. checks the access rules (no administrator 401, foreign origin 403, GET 405, a large body 413) and the validation;
2. previews the move of yowea.com from stackfinder-web to checkthecoach-web: exactly the domain's pages, nothing
   written, a token;
3. refuses a wrong token (409 reassign_preview_stale) and changes nothing;
4. applies it: only the domain's pages change their collection, their text and JSON-LD stay, other domains and the
   page of another collection keep theirs;
5. the knowledge graph takes it over through its capture: the domain's organisation is visible in checkthecoach-web,
   no longer in stackfinder-web, without a new extraction; the other domain's organisation stays in stackfinder-web;
6. a second preview finds nothing more to move.

Run after `ant compile`:  python3 test/scoutro-api/collections-reassign-live-smoke.py
GPL-2.0-or-later.
"""

import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = Path(__file__).resolve().parents[2]
JAVA = os.environ.get("JAVA", "java")
CLASSPATH = f"{REPO}/build/classes/java/main:{REPO}/lib/*"

with socket.socket() as probe:
    probe.bind(("127.0.0.1", int(os.environ.get("SCOUTRO_SMOKE_PORT", "0"))))  # refuse to touch an existing peer
    PORT = probe.getsockname()[1]
BASE = f"http://127.0.0.1:{PORT}"
REASSIGN = "/scoutro/api/v1/collections/reassign"


def write_config(root, extra):
    config = root / "DATA/SETTINGS/yacy.conf"
    config.parent.mkdir(parents=True, exist_ok=True)
    (root / "DATA/DICTIONARIES/harvesting").mkdir(parents=True, exist_ok=True)
    for friends in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
        (root / "DATA/DICTIONARIES/harvesting" / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
    lines = [f"port={PORT}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
             "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
             "network.unit.definition=defaults/yacy.network.webportal.unit", "browserPopUpTrigger=false",
             "autocrawl=false", "server.https=false", "locale.language=default", "upnp.enabled=false",
             "donation.iframesource=", "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
             "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000"]
    config.write_text("\n".join(lines + extra) + "\n")


def admin():
    password = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    password.add_password(None, BASE, "admin", "yacy")
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(password))


ANON = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def start(root, log):
    process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", CLASSPATH,
                                "net.yacy.yacy", "-startup", str(root)], cwd=REPO, stdout=log, stderr=log)
    deadline = time.monotonic() + 90
    while True:
        if process.poll() is not None:
            raise RuntimeError((root / "peer.log").read_text()[-5000:])
        try:
            with admin().open(BASE + "/scoutro/api/v1/kg/status", timeout=2) as response:
                if response.status == 200:
                    return process
        except (OSError, urllib.error.URLError):
            if time.monotonic() > deadline:
                raise RuntimeError((root / "peer.log").read_text()[-5000:])
            time.sleep(0.5)


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def call(client, method, path, body=None, headers=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    request = urllib.request.Request(BASE + path, data=data, method=method,
                                     headers=dict({"Content-Type": "application/json"} if data is not None else {}, **(headers or {})))
    try:
        with client.open(request, timeout=60) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as e:
        text = e.read()
        return e.code, (json.loads(text) if text.startswith(b"{") else None)


def get(path):
    status, body = call(admin(), "GET", path)
    assert status == 200, (path, status, body)
    return body


def push(url, html, collection):
    boundary = f"----scoutro{time.time_ns()}"
    fields = {"count": "1", "synchronous": "true", "commit": "true", "url-0": url, "collection-0": collection,
              "contentType-0": "text/html", "lastModified-0": "Tue, 15 Nov 1994 12:45:26 GMT", "responseHeader-0": ""}
    body = b"".join(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode() for k, v in fields.items())
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"data-0\"; filename=\"page.html\"\r\n"
             "Content-Type: text/html\r\n\r\n").encode() + html.encode() + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(BASE + "/api/push_p.json", data=body, method="POST",
                                     headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with admin().open(request, timeout=120) as response:
        answer = json.loads(response.read())
    assert answer.get("countsuccess") == 1, answer


def page(name, body):
    return (f"<html><head><title>{name}</title><script type=\"application/ld+json\">"
            f"{{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"{name}\"}}</script></head>"
            f"<body><h1>{name}</h1><p>{body}</p><p><a href=\"https://partner-{len(name)}.example/\">Partner</a> "
            f"<a href=\"/kontakt\">Kontakt</a></p></body></html>")


def solr(host):
    q = urllib.parse.urlencode({"q": "host_s:" + host, "fl": "sku,collection_sxt,text_t,ld_json_txt", "wt": "json", "rows": 20})
    with admin().open(BASE + "/solr/select?" + q, timeout=30) as response:
        return json.loads(response.read())["response"]["docs"]


def edges(page_id):
    q = urllib.parse.urlencode({"q": "source_id_s:" + page_id, "fl": "id,collection_sxt", "wt": "json", "rows": 50})
    with admin().open(BASE + "/solr/webgraph/select?" + q, timeout=30) as response:
        return json.loads(response.read())["response"]["docs"]


def page_ids(host):
    q = urllib.parse.urlencode({"q": "host_s:" + host, "fl": "id", "wt": "json", "rows": 50})
    with admin().open(BASE + "/solr/select?" + q, timeout=30) as response:
        return [d["id"] for d in json.loads(response.read())["response"]["docs"]]


def kg_status():
    return get("/scoutro/api/v1/kg/status")


def visible(host, collection):
    return [e.get("name") for e in get(f"/scoutro/api/v1/kg/hosts/{host}/entities?collection={collection}").get("items", [])]


def wait(what, predicate, timeout=120):
    deadline = time.monotonic() + timeout
    while True:
        value = predicate()
        if value:
            return value
        if time.monotonic() > deadline:
            raise AssertionError("timed out waiting for " + what + ": " + json.dumps(kg_status().get("sync", {}).get("processed"))[:2000])
        time.sleep(0.5)


checks = 0
with tempfile.TemporaryDirectory(prefix="scoutro-reassign-") as temporary:
    root = Path(temporary)
    (root / ".scoutro-kg-disposable").touch()
    process = None
    with (root / "peer.log").open("w") as log:
        try:
            write_config(root, ["scoutro.kg.enabled=true", "scoutro.kg.collections=stackfinder-web,checkthecoach-web",
                                "scoutro.kg.jsonld.enabled=true", "core.service.webgraph.tmp=true"])
            process = start(root, log)
            wait("the start backfill", lambda: not kg_status()["sync"]["reconcile"].get("pending", True))
            push("https://yowea.com/", page("Yowea Coaching", "Business Coaching und Consulting für Führungskräfte."), "stackfinder-web")
            push("https://www.yowea.com/angebot", page("Yowea Angebot", "Teamcoaching und Persönlichkeitsentwicklung."), "stackfinder-web")
            push("https://notyowea.com/", page("Notyowea Systemhaus", "Software und Cloud-Betrieb."), "stackfinder-web")
            push("https://coach-anna.de/", page("Coach Anna", "Coaching für Führungskräfte."), "checkthecoach-web")
            wait("the four pages in the graph", lambda: kg_status()["sync"]["processed"]["published"] >= 4)
            assert visible("yowea.com", "stackfinder-web") and not visible("yowea.com", "checkthecoach-web")
            extractions = kg_status()["sync"]["processed"]["extractions"]
            checks += 2

            # YaCy writes pages through a queue; a search sees them once Solr committed them (the graph reads them earlier)
            wait("the four pages searchable", lambda: all(solr(h) for h in ("yowea.com", "www.yowea.com", "notyowea.com", "coach-anna.de")))
            # 1. access and validation
            body = {"domain": "yowea.com", "add": ["checkthecoach-web"], "remove": ["stackfinder-web"]}
            assert call(ANON, "POST", REASSIGN, body)[0] == 401
            assert call(admin(), "POST", REASSIGN, body, {"Origin": "https://evil.example"})[0] == 403
            assert call(admin(), "GET", REASSIGN)[0] == 405
            status, answer = call(admin(), "POST", REASSIGN, dict(body, domain="x" * 5000 + ".de"))
            assert status == 413 and answer["error"]["code"] == "payload_too_large", (status, answer)
            for bad, expected in [(dict(body, domain="www.yowea.com"), (400, "invalid_request")),
                                  (dict(body, add=["gibt-es-nicht"]), (400, "collection_unknown")),
                                  (dict(body, add=[], remove=["stackfinder-web"]), (422, "reassign_would_empty"))]:
                status, answer = call(admin(), "POST", REASSIGN, bad)
                assert (status, answer["error"]["code"]) == expected, (bad, status, answer)
            checks += 7

            # 2. preview
            before = {h: solr(h) for h in ("yowea.com", "www.yowea.com", "notyowea.com", "coach-anna.de")}
            status, preview = call(admin(), "POST", REASSIGN, body)
            assert status == 200 and preview["applied"] is False, (status, preview)
            assert preview["hosts"] == ["www.yowea.com", "yowea.com"] and preview["documents"] == 2 and preview["changes"] == 2, preview
            assert preview["before"] == {"stackfinder-web": 2} and preview["after"] == {"checkthecoach-web": 2}, preview
            assert preview["crawlRunning"] is False and len(preview["token"]) == 16, preview
            webgraph_live = preview["webgraph"]["written"]
            if webgraph_live:
                assert preview["webgraph"]["edges"] >= 2, preview["webgraph"]
            assert {h: solr(h) for h in before} == before, "a preview writes nothing"
            checks += 4

            # 3. a wrong token changes nothing
            status, answer = call(admin(), "POST", REASSIGN, dict(body, confirm="0" * 16))
            assert status == 409 and answer["error"]["code"] == "reassign_preview_stale", (status, answer)
            assert answer["error"]["details"]["token"] == preview["token"], answer
            assert {h: solr(h) for h in before} == before
            checks += 3

            # 4. apply
            status, applied = call(admin(), "POST", REASSIGN, dict(body, confirm=preview["token"]))
            assert status == 200 and applied["applied"] is True and applied["updated"] == 2 and applied["failed"] == [], (status, applied)
            for host in ("yowea.com", "www.yowea.com"):
                for old, new in zip(before[host], solr(host)):
                    assert new["collection_sxt"] == ["checkthecoach-web"], new
                    assert (new["sku"], new.get("text_t"), new.get("ld_json_txt")) == (old["sku"], old.get("text_t"), old.get("ld_json_txt")), (old, new)
                    assert old.get("ld_json_txt") and old.get("text_t"), old
            assert solr("notyowea.com") == before["notyowea.com"] and solr("coach-anna.de") == before["coach-anna.de"]
            checks += 4
            if webgraph_live:
                # every edge of the domain's pages has its page's collection, the other domain's edges keep theirs
                assert applied["webgraphUpdated"] == preview["webgraph"]["edges"] and applied["webgraphKept"] == 0, applied
                for host in ("yowea.com", "www.yowea.com"):
                    for pid in page_ids(host):
                        assert edges(pid) and all(e["collection_sxt"] == ["checkthecoach-web"] for e in edges(pid)), (pid, edges(pid))
                for pid in page_ids("notyowea.com"):
                    assert all(e["collection_sxt"] == ["stackfinder-web"] for e in edges(pid)), (pid, edges(pid))
                checks += 2

            # 5. the knowledge graph follows through its capture, without a new extraction
            wait("the domain in checkthecoach-web", lambda: visible("yowea.com", "checkthecoach-web") and not visible("yowea.com", "stackfinder-web"))
            assert visible("notyowea.com", "stackfinder-web") and not visible("notyowea.com", "checkthecoach-web")
            processed = kg_status()["sync"]["processed"]
            assert processed["extractions"] == extractions and processed["lifecycle"] >= 2, processed
            checks += 3

            # 6. nothing more to move
            status, again = call(admin(), "POST", REASSIGN, body)
            assert status == 200 and again["documents"] == 0, again
            checks += 1
            print(f"PASS: {checks} live collection reassignment checks", flush=True)
        except BaseException:
            log.flush()
            print("--- end of peer.log ---\n" + (root / "peer.log").read_text(errors="replace")[-40000:], flush=True)
            raise
        finally:
            if process is not None:
                stop(process)
            print("Disposable peer stopped; temporary DATA removed", flush=True)
