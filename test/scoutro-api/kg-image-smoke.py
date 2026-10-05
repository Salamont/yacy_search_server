#!/usr/bin/env python3
"""Knowledge graph smoke test in a built Scoutro image (docker/Dockerfile.scoutro layout).

Runs the image four times on a temporary DATA volume (no existing container,
volume or peer is touched):

1. graph disabled (default): state disabled, no graph directory, no ScoutroKG thread;
2. graph enabled with a followed collection and the JSON-LD capture: database
   file, watchdog, maintenance and sync threads, the start backfill, a page
   pushed through the parser and index path (api/push_p) captured with its
   JSON-LD and published, a page of another collection not tracked;
3. docker stop / start (SIGTERM to the exec'd java process): clean shutdown, the
   start reconcile keeps the document;
4. docker kill / start: unclean start detected, integrity check ok, then the
   reconcile of the unclean start.

Usage:  python3 test/scoutro-api/kg-image-smoke.py IMAGE
GPL-2.0-or-later.
"""

import json
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

IMAGE = sys.argv[1] if len(sys.argv) > 1 else "scoutro:local"
NAME = "scoutro-kg-image-smoke"
PORT = 18091
BASE = f"http://127.0.0.1:{PORT}"


def sh(*cmd, check=True):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"{cmd}: {r.stderr}")
    return r.stdout


def client():
    pw = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    pw.add_password(None, BASE, "admin", "yacy")
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(pw))


def status(c):
    with c.open(BASE + "/scoutro/api/v1/kg/status", timeout=15) as r:
        return json.loads(r.read())


def wait_up(c):
    deadline = time.monotonic() + 180
    while True:
        try:
            return status(c)
        except Exception:
            if time.monotonic() > deadline:
                print(sh("docker", "logs", "--tail", "60", NAME, check=False))
                raise
            time.sleep(1)


def wait_status(c, what, predicate, timeout=120):
    deadline = time.monotonic() + timeout
    while True:
        s = status(c)
        if predicate(s):
            return s
        if time.monotonic() > deadline:
            raise AssertionError(f"timed out waiting for {what}: {json.dumps(s.get('sync'))[:3000]}")
        time.sleep(1)


def reconciled(s, reason):
    r = s.get("sync", {}).get("reconcile", {})
    last = r.get("last") or {}
    return not r.get("pending", True) and last.get("reason") == reason and last.get("state") == "completed"


def threads():
    out = sh("docker", "exec", NAME, "sh", "-c",
             "jcmd 1 Thread.print 2>/dev/null | grep -o 'ScoutroKG[.a-z]*' | sort -u", check=False)
    return sorted(set(out.split()))


def push(c, url, html, collection):
    boundary = f"----scoutro{time.time_ns()}"
    fields = {"count": "1", "synchronous": "true", "commit": "true", "url-0": url, "collection-0": collection,
              "contentType-0": "text/html", "lastModified-0": "Tue, 15 Nov 1994 12:45:26 GMT", "responseHeader-0": ""}
    body = b"".join(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
                    for k, v in fields.items())
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"data-0\"; filename=\"page.html\"\r\n"
             "Content-Type: text/html\r\n\r\n").encode() + html.encode() + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(BASE + "/api/push_p.json", data=body, method="POST",
                                     headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with c.open(request, timeout=120) as response:
        answer = json.loads(response.read())
    assert answer.get("countsuccess") == 1, answer


PAGE = """<html><head><title>Impressum Muster Pflege</title>
<script type="application/ld+json">{"@context":"https://schema.org","@type":"Organization","name":"Muster Pflege gGmbH",
"url":"https://www.muster-pflege-scoutro-smoke.de/","telephone":"030 1234567","vatID":"DE123456789"}</script></head>
<body><h1>Impressum</h1><p>Muster Pflege gGmbH, Lindenallee 3, 12345 Berlin</p></body></html>"""


def config(data, extra):
    conf = data / "SETTINGS/yacy.conf"
    conf.parent.mkdir(parents=True, exist_ok=True)
    harv = data / "DICTIONARIES/harvesting"
    harv.mkdir(parents=True, exist_ok=True)
    for f in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
        (harv / f).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
    lines = [l for l in (conf.read_text().splitlines() if conf.exists() else []) if not l.startswith("scoutro.kg.")]
    if not lines:
        lines = ["port=8090", "adminAccountForLocalhost=false", "adminAccountAllPages=false", "adminAccountUserName=admin",
                 "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
                 "network.unit.definition=defaults/yacy.network.webportal.unit",
                 "browserPopUpTrigger=false", "autocrawl=false", "server.https=false", "upnp.enabled=false",
                 "donation.iframesource=", "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
                 "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000"]
    conf.write_text("\n".join(lines + extra) + "\n")
    subprocess.run(["chmod", "-R", "a+rwX", str(data)], check=True)


checks = 0
data = Path(tempfile.mkdtemp(prefix="scoutro-kg-image-")) / "DATA"
sh("docker", "rm", "-f", NAME, check=False)
try:
    config(data, [])
    sh("docker", "run", "-d", "--name", NAME, "-p", f"127.0.0.1:{PORT}:8090",
       "-v", f"{data}:/opt/yacy_search_server/DATA", IMAGE)
    c = client()
    s = wait_up(c)
    assert s["state"] == "disabled" and s["enabled"] is False, s
    assert not (data / "SCOUTRO/knowledge").exists(), "graph directory while disabled"
    assert threads() == [], threads()
    print("1 disabled: state disabled, no graph directory, no ScoutroKG thread", flush=True)
    checks += 3
    sh("docker", "stop", "-t", "60", NAME)

    config(data, ["scoutro.kg.enabled=true", "scoutro.kg.collections=kgsmoke", "scoutro.kg.jsonld.enabled=true"])
    sh("docker", "start", NAME)
    s = wait_up(c)
    assert s["state"] == "running", s
    assert (data / "SCOUTRO/knowledge/graph.db").is_file()
    assert threads() == ["ScoutroKG.maintenance", "ScoutroKG.sync", "ScoutroKG.watchdog"], threads()
    s = wait_status(c, "the start backfill", lambda x: reconciled(x, "start"))
    assert s["jsonld"]["state"] == "active", s["jsonld"]
    push(c, "https://www.muster-pflege-scoutro-smoke.de/impressum", PAGE, "kgsmoke")
    s = wait_status(c, "the pushed page in the graph", lambda x: x["sync"]["processed"]["published"] >= 1)
    assert s["jsonld"]["capture"]["capturedDocs"] >= 1, s["jsonld"]
    push(c, "https://www.andere-scoutro-smoke.de/impressum", PAGE, "otherstuff")
    s = wait_status(c, "the page of another collection", lambda x: x["sync"]["processed"]["untracked"] >= 1)
    assert s["sync"]["processed"]["published"] == 1, s["sync"]["processed"]
    print("2 enabled: graph.db, watchdog + maintenance + sync threads, backfill, JSON-LD captured and published,"
          " other collection not tracked", flush=True)
    checks += 7
    sh("docker", "stop", "-t", "60", NAME)

    sh("docker", "start", NAME)
    s = wait_up(c)
    assert s["store"]["uncleanStartDetected"] is False, s["store"]
    s = wait_status(c, "the reconcile of the clean start", lambda x: reconciled(x, "start"))
    last = s["sync"]["reconcile"]["last"]
    assert last["kind"] == "reconcile" and last["tracked"] == 1 and last["deleted"] == 0, last
    print("3 docker stop/start: clean shutdown, start reconcile keeps the document", flush=True)
    checks += 2
    sh("docker", "kill", "-s", "KILL", NAME)

    sh("docker", "start", NAME)
    s = wait_up(c)
    assert s["store"]["uncleanStartDetected"] is True, s["store"]
    s = wait_status(c, "the integrity check", lambda x: x["store"]["integrity"]["state"] not in ("pending", "running"), 90)
    assert s["store"]["integrity"]["state"] == "ok", s["store"]
    s = wait_status(c, "the reconcile of the unclean start", lambda x: reconciled(x, "unclean_start"))
    last = s["sync"]["reconcile"]["last"]
    assert last["tracked"] == 1 and last["deleted"] == 0, last
    print("4 docker kill/start: unclean start, integrity check ok, reconcile keeps the document", flush=True)
    checks += 3
    sh("docker", "stop", "-t", "60", NAME)
    java = sh("docker", "run", "--rm", "--entrypoint", "java", IMAGE, "-version", check=False)
    java += subprocess.run(["docker", "run", "--rm", "--entrypoint", "java", IMAGE, "-version"],
                           capture_output=True, text=True).stderr
    print("image JVM: " + next((l for l in java.splitlines() if "version" in l), "?"), flush=True)
    print(f"PASS: {checks} image checks", flush=True)
finally:
    sh("docker", "rm", "-f", NAME, check=False)
    subprocess.run(["rm", "-rf", str(data.parent)])
