#!/usr/bin/env python3
"""Live smoke test of the knowledge graph foundation (package 1) on a disposable peer.

Starts YaCy/Scoutro five times on a temporary DATA directory (no existing peer,
DATA directory or external state is touched):

1. graph disabled (default): status "disabled", no graph directory, control 409,
   no access without the administrator account;
2. graph enabled: status "running", database files, pause persisted;
3. clean restart (SIGTERM): no unclean start, pause still active, resume;
4. hard kill (SIGKILL) and restart: unclean start detected, graph writes held
   back until the integrity check (quick_check) has passed;
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

            # 2. enabled
            write_config(root, ["scoutro.kg.enabled=true"])
            process, client = start(root, log)
            s = status(client)
            assert s["state"] == "running", s
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

            # 3. clean restart keeps the pause and reports no crash
            process, client = start(root, log)
            s = status(client)
            assert s["state"] == "running" and s["store"]["uncleanStartDetected"] is False, s["store"]
            assert s["store"]["manualPause"] is True, s["store"]
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
            checks += 5
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
        finally:
            if process is not None:
                stop(process)
            print("Disposable peer stopped; temporary DATA removed", flush=True)
