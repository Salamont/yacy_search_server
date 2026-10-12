#!/usr/bin/env python3
"""Disposable local Scoutro dashboard harness. GPL-2.0-or-later.

Seeds a NEW temporary embedded index offline, runs the peer, verifies dashboard
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
import urllib.parse
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

with tempfile.TemporaryDirectory(prefix="scoutro-dashboard-") as temporary:
    root = Path(temporary)
    (root / ".scoutro-dashboard-disposable").touch()
    config = root / "DATA/SETTINGS/yacy.conf"
    config.parent.mkdir(parents=True)
    # Offline peer: YaCy fetches the donation frame and OAI ListFriends lists at startup
    # and stores failed loads as index documents. donation.iframesource= and cached empty lists prevent both.
    (root / "DATA/DICTIONARIES/harvesting").mkdir(parents=True, exist_ok=True)
    for friends in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
        (root / "DATA/DICTIONARIES/harvesting" / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
    config.write_text("\n".join([
        f"port={PORT}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
        "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
        "network.unit.definition=defaults/yacy.network.webportal.unit", "browserPopUpTrigger=false",
        "autocrawl=false", "server.https=false", "locale.language=default", "upnp.enabled=false", "donation.iframesource=",
        "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
        "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
    ]) + "\n")
    subprocess.run([JAVA, "-cp", CLASSPATH, str(REPO / "test/scoutro-ui/DashboardFixture.java"), str(root)], cwd=REPO, check=True, timeout=60)
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
            # Measure both a cold aggregate request and cached requests.
            with client.open(BASE + "/scoutro-dashboard.html", timeout=15) as response:
                html = response.read().decode()
            assert 'data-metric="pages">7<' in html, html[:1000]
            assert 'data-metric="hosts">2<' in html, html[:1000]
            for url in ["/scoutro-dashboard.html", "/scoutro-dashboard.html?delete=true&crawlstart=1&restart=1"]:
                with client.open(BASE + url, timeout=15) as response: assert response.status == 200
            after = [digests(folder) for folder in tracked]
            changed = [f"{folder.name}/{name}" for folder, old, new in zip(tracked, before, after)
                       for name in old.keys() | new.keys() if old.get(name) != new.get(name)]
            assert before == after, "Dashboard mutated settings, index or crawl state: " + ", ".join(changed)
            print("PASS: 4 live dashboard/non-mutation checks", flush=True)
            env = {**os.environ, "SCOUTRO_URL": BASE, "SCOUTRO_ADMIN_USER": "admin", "SCOUTRO_ADMIN_PASSWORD": "yacy",
                   "SCOUTRO_EXPECT_PAGES": "7", "SCOUTRO_EXPECT_HOSTS": "2", "SCOUTRO_EXPECT_COLLECTIONS": json.dumps({
                       "edelsenior-web": 3, "checkthecoach-web": 0, "stackfinder-web": 2, "bauteamcheck-web": 2})}
            shots = os.environ.get("SCOUTRO_SCREENSHOTS", "/tmp/scoutro-dashboard-shots")
            for script in ["dashboard-ui-test.mjs", "scoutro-ui-test.mjs"]:
                subprocess.run(["node", str(REPO / "test/scoutro-ui" / script), "--screenshots", shots], cwd=REPO, env=env, check=True)
            def call(method, path, body=None, authenticated=True, headers=None):
                hs = {"Accept": "application/json", **({"Content-Type": "application/json"} if body is not None else {}), **(headers or {})}
                request = urllib.request.Request(BASE + "/scoutro/api/v1/" + path, method=method, headers=hs,
                                                 data=None if body is None else json.dumps(body).encode())
                opener = client if authenticated else urllib.request.build_opener(urllib.request.ProxyHandler({}))
                try:
                    response = opener.open(request, timeout=15)
                except urllib.error.HTTPError as e:
                    response = e
                with response:
                    raw = response.read().decode()
                    return response.status, json.loads(raw) if raw.startswith("{") else raw

            def code(result):
                return result[1]["error"]["code"] if isinstance(result[1], dict) and "error" in result[1] else None

            # 0.10.3: a collection with pages that no Discovery profile names (the shipped profiles name the four fixture
            # collections): test-web gets the pages of edel.example out of edelsenior-web (pages 0-2, its own) and joins the
            # pages of bau.example (4: stackfinder-web, 5 and 6: bauteamcheck-web, shared), by the reassignment of 0.10.1
            assert call("POST", "collections", {"id": "test-web", "name": "Testseiten"})[0] == 201
            for move in ({"domain": "edel.example", "add": ["test-web"], "remove": ["edelsenior-web"]}, {"domain": "bau.example", "add": ["test-web"]}):
                preview = call("POST", "collections/reassign", move)
                assert preview[0] == 200 and preview[1].get("token"), preview
                assert call("POST", "collections/reassign", {**move, "confirm": preview[1]["token"]})[0] == 200
            # the catalog keeps the page counts of the index for ten seconds
            deadline = time.monotonic() + 30
            while {c["id"]: c for c in call("GET", "collections")[1]["collections"]}.get("test-web", {}).get("documents") != 6:
                assert time.monotonic() < deadline, "test-web has its six pages in the catalog"
                time.sleep(1)
            # "+" and "−" of the collection list (administrator): the browser flow changes only its own collection
            index = digests(root / "DATA/INDEX/webportal/SEGMENTS")
            subprocess.run(["node", str(REPO / "test/scoutro-ui/dashboard-collections-ui-test.mjs"), "--screenshots", shots], cwd=REPO, env=env, check=True)

            counted = [0]
            def check(value, label):
                assert value, label
                counted[0] += 1
            check(call("POST", "collections", {"id": "nutzung-web", "name": "Nutzung"})[0] == 201, "API: create a collection")
            check(call("PATCH", "access", {"guestCollections": ["nutzung-web"]})[0] == 200, "API: the guest access names it")
            refused = call("DELETE", "collections/nutzung-web", {})
            check(refused[0] == 409 and code(refused) == "collection_in_use" and refused[1]["error"]["details"]["uses"] == {"guest": 1},
                  f"API: a collection the guest access names stays {refused}")
            check(call("PATCH", "access", {"guestCollections": []})[0] == 200, "API: guest access released")
            check(call("DELETE", "collections/nutzung-web", None)[0] == 415, "API: removal needs a JSON body")
            check(code(call("DELETE", "collections/nutzung-web", {"force": True})) == "invalid_request", "API: no field accepted")
            check(call("DELETE", "collections/nutzung-web", {}, headers={"Origin": "https://foreign.invalid"})[0] in (401, 403), "API: cross origin refused")
            check(call("DELETE", "collections/nutzung-web", {}, authenticated=False)[0] == 401, "API: anonymous refused")
            check(code(call("DELETE", "collections/stackfinder-web", {})) == "collection_has_documents", "API: a collection with pages stays")
            check(code(call("DELETE", "collections/robot_snippet", {})) == "collection_not_found", "API: YaCy's own collections are not removable")
            removed = call("DELETE", "collections/nutzung-web", {})
            check(removed[0] == 200 and removed[1]["removed"] and removed[1]["collection"]["id"] == "nutzung-web", f"API: removed {removed}")
            check(code(call("DELETE", "collections/nutzung-web", {})) == "collection_not_found", "API: a second removal changes nothing")
            listed = [c["id"] for c in call("GET", "collections")[1]["collections"]]
            check("nutzung-web" not in listed and "ui-test-portal" not in listed and "stackfinder-web" in listed, f"API: catalog {listed}")
            registry = json.loads((root / "DATA/SCOUTRO/collections.json").read_text())
            check(all(c["id"] not in ("nutzung-web", "ui-test-portal") for c in registry["collections"]), "API: the list on disk")
            audit = [json.loads(line) for line in (root / "DATA/SETTINGS/scoutro-user-audit.jsonl").read_text().splitlines() if line.strip()]
            removals = [a for a in audit if a.get("action") == "api.collections DELETE" and a.get("result") == "ok"]
            check(sorted(a.get("target") for a in removals) == ["nutzung-web", "ui-test-portal"] and all(a.get("actor") == "digest:admin" for a in removals),
                  f"API: removals audited with the collection as target {removals}")
            check(digests(root / "DATA/INDEX/webportal/SEGMENTS") == index, "API: no page of the index changed")
            print(f"PASS: {counted[0]} live collection +/− API checks", flush=True)

            # 0.10.3: hide, show again and delete pages, against the real index
            counted[0] = 0
            def solr(q):
                with client.open(BASE + "/solr/select?" + urllib.parse.urlencode({"q": q, "rows": 50, "wt": "json", "fl": "sku,collection_sxt"}), timeout=15) as r:
                    return {d["sku"]: sorted(d.get("collection_sxt", [])) for d in json.loads(r.read().decode())["response"]["docs"]}
            before_pages = solr("*:*")
            check(before_pages["https://bau.example/page-4"] == ["stackfinder-web", "test-web"] and before_pages["https://edel.example/page-0"] == ["test-web"],
                  f"test-web: own and shared pages {before_pages}")
            check(code(call("POST", "collections/stackfinder-web/hide", {})) == "collection_from_profile", "hide: a collection of a Discovery profile stays")
            check(call("POST", "collections/test-web/hide", None)[0] == 415, "hide: needs a JSON body")
            check(code(call("POST", "collections/test-web/hide", {"pages": "keep"})) == "invalid_request", "hide: no field accepted")
            check(call("POST", "collections/test-web/hide", {}, authenticated=False)[0] == 401, "hide: anonymous refused")
            check(call("POST", "collections/test-web/hide", {}, headers={"Origin": "https://foreign.invalid"})[0] in (401, 403), "hide: cross origin refused")
            check(call("GET", "collections/test-web/hide")[0] == 405, "hide: POST only")
            hidden = call("POST", "collections/test-web/hide", {})
            check(hidden[0] == 200 and hidden[1]["hidden"] and hidden[1]["keptPages"] == 6 and hidden[1]["graph"] == {"switchedOff": False},
                  f"hide: hidden, its pages kept, the graph did not follow it {hidden}")
            entry = {c["id"]: c for c in call("GET", "collections")[1]["collections"]}["test-web"]
            check(entry["hidden"] and not entry["selectable"] and not entry["crawlTarget"], f"hide: listed as hidden, in no choice {entry}")
            with client.open(BASE + "/scoutro-dashboard.html", timeout=15) as response:
                check('data-collection="test-web"' not in response.read().decode(), "hide: no dashboard card")
            check(solr("*:*") == before_pages, "hide: no page changed")
            stored = json.loads((root / "DATA/SCOUTRO/collections-hidden.json").read_text())
            check([h["id"] for h in stored["hidden"]] == ["test-web"], f"hide: kept in collections-hidden.json {stored}")
            check(code(call("POST", "collections", {"id": "test-web", "name": "Neu"})) == "collection_hidden", "hide: no new collection over it")
            shown = call("POST", "collections/test-web/show", {})
            check(shown[0] == 200 and shown[1]["shown"] and shown[1]["collection"]["selectable"], f"show: offered again {shown}")
            check(code(call("POST", "collections/test-web/show", {})) == "collection_not_hidden", "show: only a hidden one")
            mismatch = call("POST", "collections/test-web/delete-pages", {"confirm": "Test-web"})
            check(mismatch[0] == 400 and code(mismatch) == "confirm_mismatch", f"delete: the id confirms exactly {mismatch}")
            check(code(call("POST", "collections/test-web/delete-pages", {"confirm": "test-web", "all": True})) == "invalid_request", "delete: no other field")
            check(call("PATCH", "access", {"guestCollections": ["test-web"]})[0] == 200, "delete: the guest access names it")
            check(code(call("POST", "collections/test-web/delete-pages", {"confirm": "test-web"})) == "collection_in_use", "delete: refused while in use")
            check(call("PATCH", "access", {"guestCollections": []})[0] == 200, "delete: guest access released")
            check(solr("*:*") == before_pages, "delete: refusals deleted nothing")
            deleted = call("POST", "collections/test-web/delete-pages", {"confirm": "test-web"})
            check(deleted[0] == 200 and deleted[1]["deletedPages"] == 3 and deleted[1]["keptPages"] == 3 and deleted[1]["hidden"]
                  and deleted[1]["sharedWith"] == {"bauteamcheck-web": 2, "stackfinder-web": 1}, f"delete: only the pages of no other collection {deleted}")
            after_pages = solr("*:*")
            expected = {k: v for k, v in before_pages.items() if k not in {f"https://edel.example/page-{i}" for i in range(3)}}
            check(after_pages == expected, f"delete: exactly pages 0-2 gone, every other page and its collections unchanged {after_pages}")
            entry = {c["id"]: c for c in call("GET", "collections")[1]["collections"]}["test-web"]
            check(entry["hidden"] and entry["documents"] == 3, f"delete: the shared pages keep it hidden {entry}")
            # an empty collection created here: nothing to delete, it goes from the lists
            check(call("POST", "collections", {"id": "wegwerf-web", "name": "Wegwerf"})[0] == 201, "delete: create an empty one")
            gone = call("POST", "collections/wegwerf-web/delete-pages", {"confirm": "wegwerf-web"})
            check(gone[0] == 200 and gone[1]["removed"] and gone[1]["deletedPages"] == 0, f"delete: an empty one is removed {gone}")
            audit = [json.loads(line) for line in (root / "DATA/SETTINGS/scoutro-user-audit.jsonl").read_text().splitlines() if line.strip()]
            done = {(a.get("action"), a.get("target")) for a in audit if a.get("result") == "ok"}
            check({("api.collections.hide POST", "test-web"), ("api.collections.show POST", "test-web"),
                   ("api.collections.delete-pages POST", "test-web"), ("api.collections.delete-pages POST", "wegwerf-web")} <= done,
                  f"audit: each step with the collection as target {sorted(a for a in done if a[0] and 'collections' in a[0])}")
            print(f"PASS: {counted[0]} live hide/show/delete-pages checks", flush=True)
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try: process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            print("Disposable peer stopped; temporary DATA removed", flush=True)
