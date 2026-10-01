#!/usr/bin/env python3
"""Real startup/language-management checks with NEW disposable DATA only.

Uses the offline dashboard fixture; never starts crawling or creates agents.
Requires ant compile, JDK 17+, Playwright and Chromium. GPL-2.0-or-later.
Optional environment: JAVA, NODE_PATH, SCOUTRO_CHROMIUM_PATH,
SCOUTRO_SCREENSHOTS. No production URL or DATA path is accepted.
"""
import hashlib
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time
import urllib.request

REPO = Path(__file__).resolve().parents[2]
JAVA = os.environ.get("JAVA", "java")
CLASSPATH = f"{REPO}/build/classes/java/main:{REPO}/lib/*"
PAGES = ["env/templates/header.template", "scoutro-dashboard.html", "ScoutroAgents_p.html", "ScoutroAgentWizard_p.html"]
checks = 0


def check(value, message):
    global checks
    assert value, message
    checks += 1


def digests(folder):
    return {str(p.relative_to(folder)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in folder.rglob("*") if p.is_file()}


def settings(config):
    return dict(line.split("=", 1) for line in config.read_text().splitlines() if "=" in line and not line.startswith("#"))


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def run(mode, fail=False):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))  # Refuse to use an existing peer.
        port = probe.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    with tempfile.TemporaryDirectory(prefix="scoutro-locale-refresh-") as temporary:
        root = Path(temporary)
        (root / ".scoutro-dashboard-disposable").touch()
        config = root / "DATA/SETTINGS/yacy.conf"
        config.parent.mkdir(parents=True)
        config.write_text("\n".join([
            f"port={port}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
            "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
            "network.unit.definition=defaults/yacy.network.webportal.unit", "browserPopUpTrigger=false",
            "autocrawl=false", "server.https=false", f"locale.language={mode}", "upnp.enabled=false",
            "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
            "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
        ]) + "\n")
        # Agent-page GETs must not have to initialize a pepper file during the non-mutation check.
        pepper = config.parent / "scoutro-agent-pepper"
        pepper.write_text("01" * 32)
        locale = root / "DATA/LOCALE/htroot"
        for language in ["de", "fr"]:
            for name in PAGES:
                target = locale / language / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("stale generated template")
        (locale / "de/version").write_text("1.942\n")  # French deliberately has no marker.
        overrides = locale.parent / "de.lng"
        overrides.write_text("#File: env/templates/header.template\nAdministration==Meine Verwaltung\n")
        override_bytes = overrides.read_bytes()
        if fail:
            missing_source = root / "empty-locales"
            missing_source.mkdir()
            with config.open("a") as conf:
                conf.write(f"locale.source={missing_source}\n")
        subprocess.run([JAVA, "-cp", CLASSPATH, str(REPO / "test/scoutro-ui/DashboardFixture.java"), str(root)],
                       cwd=REPO, check=True, timeout=60, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        password = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        password.add_password(None, base, "admin", "yacy")
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(password))

        def get(path):
            request = urllib.request.Request(base + path, headers={"Accept-Language": "de-DE,de;q=0.9"})
            with client.open(request, timeout=15) as response:
                check(response.status == 200, path + " HTTP status")
                return response.read().decode()

        def start(log):
            process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", CLASSPATH,
                                        "net.yacy.yacy", "-startup", str(root)], cwd=REPO, stdout=log, stderr=log)
            deadline = time.monotonic() + 60
            while True:
                if process.poll() is not None:
                    raise RuntimeError((root / "peer.log").read_text()[-5000:])
                try:
                    with client.open(base + "/api/version.xml", timeout=2) as response:
                        if response.status == 200:
                            return process
                except OSError:
                    if time.monotonic() > deadline:
                        stop(process)
                        raise RuntimeError((root / "peer.log").read_text()[-5000:])
                    time.sleep(0.5)

        process = None
        with (root / "peer.log").open("w") as log:
            try:
                process = start(log)
                check(settings(config)["locale.language"] == mode, "Startup changed the language mode")
                if fail:
                    check((locale / "de/version").read_text() == "1.942\n", "Failed regeneration stamped marker")
                    check(not (locale / "fr/version").exists(), "Failed regeneration created missing marker")
                    check((locale / "de/env/templates/header.template").read_text() == "stale generated template", "Failed regeneration deleted old copies")
                    get("/api/version.xml")  # Translation failure did not prevent actual HTTP startup.
                    print("PASS: regeneration failure keeps old copies/markers and HTTP startup", flush=True)
                    return
                revision = (locale / "de/version").read_text()
                check(re.fullmatch(r"locale-v2:[0-9a-f]{64}\n", revision), "Release/build marker shape")
                check((locale / "fr/version").read_text() == revision, "All generated languages use the same marker")
                header = (locale / "de/env/templates/header.template").read_text()
                check('href="scoutro-dashboard.html"' in header and 'href="ScoutroAgents_p.html"' in header, "Startup retained old navigation")
                check("Meine Verwaltung" in header, "Startup lost the user translation")
                if mode == "default":
                    get("/ConfigLanguage_p.html?language=de.lng&use_button=Use")
                    check((locale / "de/version").read_text() == revision, "Language management uses a different marker")
                # YaCy records the first visit of each responder in server.servlets.called.
                # Warm those existing navigation records before the read-only comparison.
                for name in PAGES[1:] + ["env/style.css"]:
                    get("/" + name)
                tracked = [root / "DATA/INDEX/webportal/SEGMENTS", root / "DATA/QUEUES", root / "DATA/SETTINGS"]
                deadline = time.monotonic() + 30
                before = [digests(folder) for folder in tracked]
                stable_count = 0
                while stable_count < 2:
                    time.sleep(0.5)
                    now = [digests(folder) for folder in tracked]
                    stable_count = stable_count + 1 if before == now else 0
                    assert time.monotonic() < deadline, "Disposable peer did not settle"
                    before = now
                before_config = settings(config)
                dashboard = get("/scoutro-dashboard.html")
                check('data-metric="pages">7<' in dashboard and 'data-metric="hosts">2<' in dashboard, "Fixture index changed")
                subprocess.run(["node", str(REPO / "test/scoutro-ui/locale-refresh-ui-test.mjs")], cwd=REPO,
                               env={**os.environ, "SCOUTRO_URL": base, "SCOUTRO_LOCALE_MODE": mode}, check=True, timeout=90)
                after = [digests(folder) for folder in tracked]
                changed = [f"{folder.name}/{name}" for folder, old, new in zip(tracked, before, after)
                           for name in old.keys() | new.keys() if old.get(name) != new.get(name)]
                after_config = settings(config)
                changed_keys = [key for key in before_config.keys() | after_config.keys()
                                if before_config.get(key) != after_config.get(key)]
                called = set(after_config.get("server.servlets.called", "").split(",")) - set(before_config.get("server.servlets.called", "").split(","))
                check(before_config == after_config, "Configuration values changed: " + ", ".join(changed_keys) + "; new responder paths: " + ", ".join(sorted(called)))
                check(all(name == "SETTINGS/yacy.conf" for name in changed), "Page GETs mutated persistent files: " + ", ".join(changed))
                if changed:
                    print("YaCy rewrote the configuration file; all configuration values unchanged", flush=True)
                if mode == "default":
                    get("/ConfigLanguage_p.html?language=default&use_button=Use")
                check(settings(config)["locale.language"] == mode, "Original language mode not preserved/restored")
                check(overrides.read_bytes() == override_bytes, "User translation file changed")
                check(pepper.read_text() == "01" * 32, "Agent pepper changed")
                check(not (config.parent / "scoutro-agents.json").exists(), "Smoke created an agent")
                if mode == "de":
                    # A second genuine startup must skip current generated copies.
                    before_locales = {str(p.relative_to(locale)): (p.read_bytes(), p.stat().st_mtime_ns)
                                      for p in locale.rglob("*") if p.is_file()}
                    stop(process)
                    process = start(log)
                    check(before_locales == {str(p.relative_to(locale)): (p.read_bytes(), p.stat().st_mtime_ns)
                                             for p in locale.rglob("*") if p.is_file()}, "Unchanged startup refreshed locale copies")
                print(f"PASS: live startup/language management/non-mutation, mode={mode}", flush=True)
            finally:
                stop(process)
                print("Disposable peer stopped; temporary DATA removed", flush=True)


if __name__ == "__main__":
    for selected_mode in ["de", "default", "browser"]:
        run(selected_mode)
    run("browser", fail=True)
    print(f"PASS: {checks} live locale checks + German desktop/mobile UI in all three modes", flush=True)
