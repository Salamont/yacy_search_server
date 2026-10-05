#!/usr/bin/env python3
"""Live smoke test of the knowledge graph (packages 1 and 2a) on a disposable peer.

Starts YaCy/Scoutro five times on a temporary DATA directory (no existing peer,
DATA directory or external state is touched):

1. graph disabled (default): status "disabled", no graph directory, control 409,
   no access without the administrator account;
2. graph enabled: status "running", database files, the sync starts with a
   backfill, a page pushed through YaCy's parser and index path (push_p) is
   captured with its JSON-LD and published, a page of another collection is not
   tracked, pause persisted;
3. clean restart (SIGTERM): no unclean start, pause still active, the start
   runs a full reconcile, resume;
4. hard kill (SIGKILL) and restart: unclean start detected, graph writes held
   back until the integrity check (quick_check) has passed, then the reconcile
   of the unclean start;
5. SQLite native library not loadable: Scoutro starts, the graph is unavailable.

Run after `ant compile`:  python3 test/scoutro-api/kg-live-smoke.py
Another JDK:              JAVA=/path/to/bin/java python3 test/scoutro-api/kg-live-smoke.py
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
import urllib.request

REPO = Path(__file__).resolve().parents[2]
JAVA = os.environ.get("JAVA", "java")
CLASSPATH = f"{REPO}/build/classes/java/main:{REPO}/lib/*"

with socket.socket() as probe:
    probe.bind(("127.0.0.1", int(os.environ.get("SCOUTRO_SMOKE_PORT", "0"))))  # refuse to touch an existing peer
    PORT = probe.getsockname()[1]
BASE = f"http://127.0.0.1:{PORT}"


def write_config(root, extra):
    config = root / "DATA/SETTINGS/yacy.conf"
    config.parent.mkdir(parents=True, exist_ok=True)
    (root / "DATA/DICTIONARIES/harvesting").mkdir(parents=True, exist_ok=True)
    for friends in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
        (root / "DATA/DICTIONARIES/harvesting" / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
    lines = [line for line in (config.read_text().splitlines() if config.exists() else [])
             if not line.startswith("scoutro.kg.")]
    if not lines:
        lines = [f"port={PORT}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
                 "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
                 "network.unit.definition=defaults/yacy.network.webportal.unit", "browserPopUpTrigger=false",
                 "autocrawl=false", "server.https=false", "locale.language=default", "upnp.enabled=false",
                 "donation.iframesource=", "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
                 "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000"]
    config.write_text("\n".join(lines + extra) + "\n")


def admin_client():
    password = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    password.add_password(None, BASE, "admin", "yacy")
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(password))


ANONYMOUS = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def start(root, log, jvm=()):
    process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", *jvm, "-cp", CLASSPATH,
                                "net.yacy.yacy", "-startup", str(root)], cwd=REPO, stdout=log, stderr=log)
    client = admin_client()
    deadline = time.monotonic() + 90
    while True:
        if process.poll() is not None:
            raise RuntimeError((root / "peer.log").read_text()[-5000:])
        try:
            with client.open(BASE + "/scoutro/api/v1/kg/status", timeout=2) as response:
                if response.status == 200:
                    return process, client
        except (OSError, urllib.error.URLError):
            if time.monotonic() > deadline:
                raise RuntimeError((root / "peer.log").read_text()[-5000:])
            time.sleep(0.5)


def stop(process, kill=False):
    if process.poll() is not None:
        return
    if kill:
        process.kill()
    else:
        process.terminate()
    try:
        process.wait(timeout=60)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def status(client):
    with client.open(BASE + "/scoutro/api/v1/kg/status", timeout=15) as response:
        return json.loads(response.read())


def control(client, action, content_type="application/json"):
    request = urllib.request.Request(BASE + "/scoutro/api/v1/kg/control", data=json.dumps({"action": action}).encode(),
                                     headers={"Content-Type": content_type}, method="POST")
    try:
        with client.open(request, timeout=15) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as e:
        body = e.read()
        return e.code, (json.loads(body) if body.startswith(b"{") else None)


def wait_status(client, what, predicate, timeout=90):
    deadline = time.monotonic() + timeout
    while True:
        s = status(client)
        if predicate(s):
            return s
        if time.monotonic() > deadline:
            raise AssertionError(f"timed out waiting for {what}: {json.dumps(s.get('sync'))[:3000]}")
        time.sleep(0.5)


def push(client, url, html, collection):
    """Indexes one page through YaCy's parser, condenser and yacy2solr (api/push_p, synchronous, committed)."""
    boundary = f"----scoutro{time.time_ns()}"
    fields = {"count": "1", "synchronous": "true", "commit": "true", "url-0": url, "collection-0": collection,
              "contentType-0": "text/html", "lastModified-0": "Tue, 15 Nov 1994 12:45:26 GMT", "responseHeader-0": ""}
    body = b"".join(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
                    for k, v in fields.items())
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"data-0\"; filename=\"page.html\"\r\n"
             "Content-Type: text/html\r\n\r\n").encode() + html.encode() + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(BASE + "/api/push_p.json", data=body, method="POST",
                                     headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with client.open(request, timeout=120) as response:
        answer = json.loads(response.read())
    assert answer.get("countsuccess") == 1, answer
    return answer


PAGE = """<html><head><title>Impressum Muster Pflege</title>
<script type="application/ld+json">{"@context":"https://schema.org","@type":"Organization","name":"Muster Pflege gGmbH",
"url":"https://www.muster-pflege-scoutro-smoke.de/","telephone":"030 1234567","vatID":"DE123456789"}</script></head>
<body><h1>Impressum</h1><p>Muster Pflege gGmbH, Lindenallee 3, 12345 Berlin</p></body></html>"""


def published(s):
    return s.get("sync", {}).get("processed", {}).get("published", 0)


def reconciled(s, reason):
    r = s.get("sync", {}).get("reconcile", {})
    last = r.get("last") or {}
    return not r.get("pending", True) and last.get("reason") == reason and last.get("state") == "completed"


checks = 0
with tempfile.TemporaryDirectory(prefix="scoutro-kg-") as temporary:
    root = Path(temporary)
    (root / ".scoutro-kg-disposable").touch()
    graph_dir = root / "DATA/SCOUTRO/knowledge"
    process = None
    with (root / "peer.log").open("w") as log:
        try:
            # 1. disabled by default
            write_config(root, [])
            process, client = start(root, log)
            s = status(client)
            assert s["state"] == "disabled" and s["enabled"] is False, s
            assert not graph_dir.exists(), "graph directory created while disabled"
            code, body = control(client, "pause")
            assert code == 409 and body["error"]["code"] == "kg_disabled", (code, body)
            try:
                ANONYMOUS.open(BASE + "/scoutro/api/v1/kg/status", timeout=5)
                raise AssertionError("status without administrator account")
            except urllib.error.HTTPError as e:
                assert e.code == 401, e.code
            checks += 4
            stop(process)

            # 2. enabled, following the collection kgsmoke, with the JSON-LD capture
            write_config(root, ["scoutro.kg.enabled=true", "scoutro.kg.collections=kgsmoke", "scoutro.kg.jsonld.enabled=true"])
            process, client = start(root, log)
            s = status(client)
            assert s["state"] == "running", s
            s = wait_status(client, "the start backfill", lambda x: reconciled(x, "start"))
            assert s["sync"]["state"] == "running" and s["sync"]["reconcile"]["last"]["kind"] == "backfill", s["sync"]
            assert s["jsonld"]["state"] == "active" and s["jsonld"]["captureImplemented"] is True, s["jsonld"]
            push(client, "https://www.muster-pflege-scoutro-smoke.de/impressum", PAGE, "kgsmoke")
            s = wait_status(client, "the pushed page in the graph", lambda x: published(x) >= 1)
            assert s["jsonld"]["capture"]["capturedDocs"] >= 1, s["jsonld"]
            push(client, "https://www.andere-scoutro-smoke.de/impressum", PAGE, "otherstuff")
            s = wait_status(client, "the page of another collection", lambda x: x["sync"]["processed"]["untracked"] >= 1)
            assert published(s) == 1, s["sync"]["processed"]
            assert s["sync"]["lag"]["pending"] == 0, s["sync"]["lag"]
            checks += 6
            assert (graph_dir / "graph.db").is_file(), "no database file"
            assert s["store"]["uncleanStartDetected"] is False and s["store"]["startRecorded"] is True, s["store"]
            assert s["storage"]["growthAllowed"] is True, s["storage"]
            assert s["storage"]["pages"]["maxPageCount"] > 0, s["storage"]
            assert s["store"]["integrity"]["state"] == "not_required", s["store"]
            assert s["storage"]["files"]["tmpDirectory"] == "graph", s["storage"]["files"]
            code, _ = control(client, "pause", content_type="text/plain")
            assert code == 415, code
            code, body = control(client, "pause")
            assert code == 200 and body["storage"]["growthAllowed"] is False, (code, body)
            checks += 8
            stop(process)  # SIGTERM: Scoutro shuts down cleanly

            # 3. clean restart keeps the pause, reports no crash and reconciles (Solr may have changed meanwhile)
            process, client = start(root, log)
            s = status(client)
            assert s["state"] == "running" and s["store"]["uncleanStartDetected"] is False, s["store"]
            assert s["store"]["manualPause"] is True, s["store"]
            s = wait_status(client, "the reconcile of the clean start", lambda x: reconciled(x, "start"))
            last = s["sync"]["reconcile"]["last"]
            assert last["kind"] == "reconcile" and last["deleted"] == 0 and last["tracked"] == 1, last
            checks += 1
            code, body = control(client, "resume")
            assert code == 200 and body["storage"]["growthAllowed"] is True, (code, body)
            checks += 3
            stop(process, kill=True)  # SIGKILL: no clean shutdown

            # 4. restart after a hard kill
            process, client = start(root, log)
            s = status(client)
            assert s["state"] == "running" and s["store"]["uncleanStartDetected"] is True, s["store"]
            assert s["store"]["integrity"]["trigger"] == "unclean_start", s["store"]
            deadline = time.monotonic() + 30
            while status(client)["store"]["integrity"]["state"] in ("pending", "running") and time.monotonic() < deadline:
                time.sleep(0.5)
            s = status(client)
            assert s["store"]["integrity"]["state"] == "ok" and s["store"]["integrity"]["result"] == "ok", s["store"]
            assert s["store"]["integrity"]["blocksGraphWrites"] is False, s["store"]
            assert not [r for r in s["storage"]["reasons"] if r["code"].startswith("integrity")], s["storage"]["reasons"]
            codes = [e["code"] for e in s["events"]]
            assert "unclean_start" in codes and "integrity_ok" in codes, codes
            s = wait_status(client, "the reconcile of the unclean start", lambda x: reconciled(x, "unclean_start"))
            assert s["sync"]["reconcile"]["last"]["deleted"] == 0, s["sync"]["reconcile"]
            checks += 6
            stop(process)

            # 5. the SQLite native library cannot be extracted: Scoutro still starts, the graph reports why
            process, client = start(root, log, jvm=["-Dorg.sqlite.tmpdir=/proc/scoutro-kg-no-such-dir"])
            s = status(client)
            assert s["state"] == "unavailable" and s["reason"] == "native_library_unavailable", s
            with client.open(BASE + "/scoutro/api/v1/health", timeout=15) as response:
                assert response.status == 200
            code, body = control(client, "pause")
            assert code == 503 and body["error"]["code"] == "kg_unavailable", (code, body)
            checks += 3
            print(f"PASS: {checks} live knowledge graph checks", flush=True)
        except BaseException:
            log.flush()
            print("--- end of peer.log ---\n" + (root / "peer.log").read_text(errors="replace")[-60000:], flush=True)
            raise
        finally:
            if process is not None:
                stop(process)
            print("Disposable peer stopped; temporary DATA removed", flush=True)
