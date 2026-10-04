#!/usr/bin/env python3
"""Disposable local Scoutro crawl report harness. GPL-2.0-or-later.

Seeds a NEW temporary DATA directory offline (index documents, scoutro_domains
rows, rollups, one Discovery job), runs the peer, verifies that report GETs
change nothing, runs the report API and UI checks and stops the peer in a
finally block. No crawling, autocrawler, existing DATA directory or external
state is involved.

Requires a compiled repository, JDK 17+, Playwright and Chromium. Optional:
JAVA, NODE_PATH, SCOUTRO_CHROMIUM_PATH, SCOUTRO_SMOKE_PORT, SCOUTRO_SCREENSHOTS.
"""
import hashlib
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request

REPO = Path(__file__).resolve().parents[2]
JAVA = os.environ.get("JAVA", "java")
CLASSPATH = f"{REPO}/build/classes/java/main:{REPO}/lib/*"
PORT = int(os.environ.get("SCOUTRO_SMOKE_PORT", "0"))
JOB, ORPHAN = "5b0f4c1e-7a2d-4c6b-9f1e-2d3c4b5a6f70", "8e1d2c3b-4a5f-4e6d-8c7b-9a0f1e2d3c4b"


def digests(folder):
    return {str(p.relative_to(folder)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in folder.rglob("*") if p.is_file()}


with socket.socket() as probe:
    probe.bind(("127.0.0.1", PORT))  # Refuse to touch an existing peer.
    PORT = probe.getsockname()[1]
    BASE = f"http://127.0.0.1:{PORT}"

with tempfile.TemporaryDirectory(prefix="scoutro-report-") as temporary:
    root = Path(temporary)
    (root / ".scoutro-report-disposable").touch()
    config = root / "DATA/SETTINGS/yacy.conf"
    config.parent.mkdir(parents=True)
    config.write_text("\n".join([
        f"port={PORT}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
        "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
        "network.unit.definition=defaults/yacy.network.webportal.unit", "browserPopUpTrigger=false",
        "autocrawl=false", "server.https=false", "locale.language=browser", "search.verify=false", "core.service.citation.tmp=false", "core.service.webgraph.tmp=false", "postprocessing.minimum_ram=999999999999", "upnp.enabled=false",
        "scoutro.discovery.enabled=false",
        "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
        "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
    ]) + "\n")
    (root / "DATA/LOCALE/htroot/de").mkdir(parents=True)
    subprocess.run([JAVA, "-cp", CLASSPATH, str(REPO / "test/scoutro-ui/ReportFixture.java"), str(root)], cwd=REPO, check=True, timeout=120)
    process = None
    with (root / "peer.log").open("w") as log:
        try:
            process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", CLASSPATH,
                                        "net.yacy.yacy", "-startup", str(root)], cwd=REPO, stdout=log, stderr=log)
            password = urllib.request.HTTPPasswordMgrWithDefaultRealm()
            password.add_password(None, BASE, "admin", "yacy")
            client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(password))
            deadline = time.monotonic() + 90
            while True:
                if process.poll() is not None:
                    raise RuntimeError((root / "peer.log").read_text()[-5000:])
                try:
                    with client.open(BASE + "/api/version.xml", timeout=2) as response:
                        if response.status == 200: break
                except (OSError, urllib.error.URLError):
                    if time.monotonic() > deadline: raise RuntimeError((root / "peer.log").read_text()[-5000:])
                    time.sleep(0.5)
            # Warm servlet shells and the capture thread (its first daily step finds every
            # rollup present) before the strict settings/index/queue/table/rollup baseline.
            readonly_urls = ["/ScoutroSEO_p.html", "/ScoutroSEO_p.html?view=report", "/scoutro/api/v1/collections",
                             "/scoutro/api/v1/reports/jobs", "/scoutro/api/v1/reports/jobs/" + JOB,
                             "/scoutro/api/v1/reports/collections/visible", "/scoutro/api/v1/reports/collections/visible/hosts?filter=stale",
                             "/scoutro/api/v1/reports/hosts/a.example?collection=visible", "/scoutro/api/v1/seo/hosts/a.example"]
            for path in readonly_urls:
                with client.open(BASE + path, timeout=15) as response: assert response.status == 200, path
            time.sleep(25)  # one capture tick (default 20 s)
            tracked = [root / "DATA/SETTINGS", root / "DATA/INDEX/webportal/SEGMENTS", root / "DATA/QUEUES", root / "DATA/SCOUTRO", root / "DATA/WORK"]
            deadline = time.monotonic() + 30
            before = [digests(folder) for folder in tracked]
            consecutive_stable = 0
            while consecutive_stable < 2:
                time.sleep(0.5)
                stable = [digests(folder) for folder in tracked]
                consecutive_stable = consecutive_stable + 1 if stable == before else 0
                assert time.monotonic() < deadline, "Disposable peer did not settle"
                before = stable
            for url in readonly_urls:
                with client.open(BASE + url, timeout=15) as response: assert response.status == 200, url
            time.sleep(25)  # and one more tick: viewing reports never writes rollups or rows
            after = [digests(folder) for folder in tracked]
            changed = [f"{folder.name}/{name}" for folder, old, new in zip(tracked, before, after) for name in old.keys() | new.keys() if old.get(name) != new.get(name)]
            assert before == after, "Report GET mutated settings, index, queues, table or rollups: " + ", ".join(changed)
            jobs = {p.parent.name for p in (root / "DATA/SCOUTRO/reports/rollups").rglob("*.ndjson")}
            assert jobs == {JOB, ORPHAN}, jobs
            print(f"PASS: {len(readonly_urls)} live authenticated report reads, no state change over two capture ticks", flush=True)
            env = {**os.environ, "SCOUTRO_URL": BASE, "SCOUTRO_ADMIN_USER": "admin", "SCOUTRO_ADMIN_PASSWORD": "yacy"}
            shots = os.environ.get("SCOUTRO_SCREENSHOTS", "/tmp/scoutro-report-shots")
            subprocess.run(["node", str(REPO / "test/scoutro-ui/report-ui-test.mjs"), "--screenshots", shots], cwd=REPO, env=env, check=True)
            subprocess.run(["python3", str(REPO / "test/scoutro-api/test_report_api.py"), "-v"], cwd=REPO, env=env, check=True)
            assert not (root / "DATA/SCOUTRO/crawls.ndjson").exists(), "Report reads must not create a crawl ledger"
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try: process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            print("Disposable peer stopped; temporary DATA removed", flush=True)
