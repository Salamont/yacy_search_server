#!/usr/bin/env python3
"""Disposable local Scoutro SEO harness. GPL-2.0-or-later.

Seeds a NEW temporary embedded index offline, runs the peer, verifies SEO
non-mutation and UI checks, and stops the peer in a finally block. No crawling,
autocrawler, existing DATA directory or external state is involved.

Requires a compiled repository, JDK 17+, Playwright and Chromium. Optional:
JAVA, NODE_PATH, SCOUTRO_CHROMIUM_PATH, SCOUTRO_SMOKE_PORT, SCOUTRO_SCREENSHOTS.
"""
import hashlib
import json
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
BASE = f"http://127.0.0.1:{PORT}"


def digests(folder):
    return {str(p.relative_to(folder)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in folder.rglob("*") if p.is_file()}


with socket.socket() as probe:
    probe.bind(("127.0.0.1", PORT))  # Refuse to touch an existing peer.
    PORT = probe.getsockname()[1]
    BASE = f"http://127.0.0.1:{PORT}"

with tempfile.TemporaryDirectory(prefix="scoutro-seo-") as temporary:
    root = Path(temporary)
    (root / ".scoutro-seo-disposable").touch()
    config = root / "DATA/SETTINGS/yacy.conf"
    config.parent.mkdir(parents=True)
    config.write_text("\n".join([
        f"port={PORT}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
        "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
        "network.unit.definition=defaults/yacy.network.webportal.unit", "browserPopUpTrigger=false",
        "autocrawl=false", "server.https=false", "locale.language=browser", "search.verify=false", "core.service.citation.tmp=false", "core.service.webgraph.tmp=false", "postprocessing.minimum_ram=999999999999", "upnp.enabled=false",
        "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
        "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
    ]) + "\n")
    (root / "DATA/LOCALE/htroot/de").mkdir(parents=True)
    subprocess.run([JAVA, "-cp", CLASSPATH, str(REPO / "test/scoutro-ui/SeoFixture.java"), str(root)], cwd=REPO, check=True, timeout=60)
    process = None
    with (root / "peer.log").open("w") as log:
        try:
            process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", CLASSPATH,
                                        "net.yacy.yacy", "-startup", str(root)], cwd=REPO, stdout=log, stderr=log)
            password = urllib.request.HTTPPasswordMgrWithDefaultRealm()
            password.add_password(None, BASE, "admin", "yacy")
            client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(password))
            deadline = time.monotonic() + 60
            while True:
                if process.poll() is not None:
                    raise RuntimeError((root / "peer.log").read_text()[-5000:])
                try:
                    with client.open(BASE + "/api/version.xml", timeout=2) as response:
                        if response.status == 200: break
                except (OSError, urllib.error.URLError):
                    if time.monotonic() > deadline: raise RuntimeError((root / "peer.log").read_text()[-5000:])
                    time.sleep(0.5)
            tracked = [root / "DATA/SETTINGS", root / "DATA/INDEX/webportal/SEGMENTS", root / "DATA/QUEUES"]
            # YaCy finishes creating its queue files after HTTP starts. Wait for
            # persistent state to settle before measuring dashboard requests.
            deadline = time.monotonic() + 30
            before = [digests(folder) for folder in tracked]
            consecutive_stable = 0
            while consecutive_stable < 2:
                time.sleep(0.5)
                stable = [digests(folder) for folder in tracked]
                consecutive_stable = consecutive_stable + 1 if stable == before else 0
                assert time.monotonic() < deadline, "Disposable peer did not settle"
                before = stable
            config_before = config.read_text()
            # Admin SEO GETs cannot alter persistent settings/index/crawl queues.
            for url in ["/ScoutroSEO_p.html", "/scoutro/api/v1/seo/hosts", "/scoutro/api/v1/seo/hosts/a.example", "/scoutro/api/v1/seo/hosts/a.example/pages?sort=references_external&order=desc"]:
                with client.open(BASE + url, timeout=15) as response: assert response.status == 200
            after = [digests(folder) for folder in tracked]
            changed = [f"{folder.name}/{name}" for folder, old, new in zip(tracked, before, after) for name in old.keys() | new.keys() if old.get(name) != new.get(name)]
            def settings(text): return dict(line.split("=",1) for line in text.splitlines() if "=" in line and not line.startswith("#"))
            a, b = settings(config_before), settings(config.read_text())
            if a != b: print("Changed setting names:", [key for key in a.keys() | b.keys() if a.get(key) != b.get(key)], flush=True)
            assert before == after, "SEO GET mutated settings, index or crawl queues: " + ", ".join(changed)
            print("PASS: 5 live authenticated/non-mutation checks", flush=True)
            env = {**os.environ, "SCOUTRO_URL": BASE, "SCOUTRO_ADMIN_USER": "admin", "SCOUTRO_ADMIN_PASSWORD": "yacy"}
            shots = os.environ.get("SCOUTRO_SCREENSHOTS", "/tmp/scoutro-seo-shots")
            subprocess.run(["node", str(REPO / "test/scoutro-ui/seo-ui-test.mjs"), "--screenshots", shots], cwd=REPO, env=env, check=True)
            subprocess.run(["python3", str(REPO / "test/scoutro-api/test_seo_api.py"), "-v"], cwd=REPO, env=env, check=True)
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try: process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            print("Disposable peer stopped; temporary DATA removed", flush=True)
