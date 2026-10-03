#!/usr/bin/env python3
"""Citation schema + search/scope regression on NEW disposable DATA only.

No target URL/DATA option, no crawling, no inference or automation. Reuses the
offline Dashboard fixture and Agent wizard helpers. GPL-2.0-or-later.
"""
import importlib.util
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
CLASSPATH = f"{REPO}/build/classes/java/main:{REPO}/lib/*"
sys.dont_write_bytecode = True


def main():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    checks = 0
    process = None
    with tempfile.TemporaryDirectory(prefix="scoutro-citation-http-") as temporary:
        root = Path(temporary)
        (root / ".scoutro-dashboard-disposable").touch()
        settings = root / "DATA/SETTINGS"
        settings.mkdir(parents=True)
        # .example fixtures have no public DNS. Permit their offline metadata
        # in this disposable network only; keep all online verification off.
        network = root / "fixture.network.unit"
        network.write_text((REPO / "defaults/yacy.network.webportal.unit").read_text().replace(
            "network.unit.domain = global", "network.unit.domain = any"))
        (settings / "yacy.conf").write_text("\n".join([
            f"port={port}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
            "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
            f"network.unit.definition={network}", "browserPopUpTrigger=false",
            "autocrawl=false", "server.https=false", "locale.language=default", "upnp.enabled=false",
            "scoutro.discovery.enabled=false", "core.service.citation.tmp=true", "core.service.webgraph.tmp=false",
            "search.verify=false",
            "search.ranking.solr.collection.filterquery.tmpa.0=httpstatus_i:200",
            "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
            "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
        ]) + "\n")
        # Simulate existing DATA with the marker declaration absent, not just disabled.
        schema = settings / "solr.collection.schema"
        schema.write_text("\n".join(line for line in (REPO / "defaults/solr.collection.schema").read_text().splitlines()
                                   if line.strip().lstrip("#").split("#", 1)[0].strip() != "process_sxt") + "\n")
        subprocess.run([JAVA, "-cp", CLASSPATH, str(REPO / "test/scoutro-ui/DashboardFixture.java"), str(root)],
                       cwd=REPO, check=True, timeout=60, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        password = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        password.add_password(None, base, "admin", "yacy")
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(password))

        def get(path, query=None, as_json=True):
            url = base + path + ("?" + urllib.parse.urlencode(query, doseq=True) if query else "")
            with client.open(url, timeout=20) as response:
                assert response.status == 200
                text = response.read().decode()
                return json.loads(text) if as_json else text

        with (root / "peer.log").open("w") as log:
            try:
                process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", CLASSPATH,
                                            "net.yacy.yacy", "-startup", str(root)], cwd=REPO, stdout=log, stderr=log)
                deadline = time.monotonic() + 60
                while True:
                    if process.poll() is not None:
                        raise RuntimeError((root / "peer.log").read_text()[-4000:])
                    try:
                        get("/api/version.xml", as_json=False)
                        break
                    except (OSError, urllib.error.URLError):
                        if time.monotonic() >= deadline:
                            raise RuntimeError((root / "peer.log").read_text()[-4000:])
                        time.sleep(0.5)
                active = {line.split("#", 1)[0].split("=", 1)[0].strip() for line in schema.read_text().splitlines()
                          if line.strip() and not line.lstrip().startswith("#")}
                assert "process_sxt" in active, "Citation startup must enable the pending marker"
                assert "canonical_s" not in active and "cr_host_norm_i" not in active
                checks += 1

                scope = ["collection_sxt:edelsenior-web", "httpstatus_i:200", "-failtype_s:[* TO *]"]
                for q in ["dashboard disposable", "{!cache=false}text_t:dashboard", " \t{!cache=false}text_t:dashboard"]:
                    result = get("/solr/select", {"q": q, "fq": scope, "rows": 20, "wt": "json", "fl": "id,collection_sxt"})
                    assert result["response"]["numFound"] == 3, result
                    assert all("edelsenior-web" in doc["collection_sxt"] for doc in result["response"]["docs"])
                    checks += 1
                for rows in [0, 1, 20]:
                    result = get("/solr/select", {"q": "{!cache=false}collection_sxt:edelsenior-web AND httpstatus_i:200 AND -failtype_s:[* TO *]",
                                                  "rows": rows, "wt": "json", "fl": "id"})
                    assert result["response"]["numFound"] == 3, result
                    assert len(result["response"]["docs"]) == min(rows, 3)
                    checks += 1

                for q in ["dashboard disposable", "site:edel.example dashboard", "/date dashboard"]:
                    # YaCy's search feed is asynchronous on its first request.
                    deadline = time.monotonic() + 12
                    while True:
                        result = get("/scoutro/api/v1/search", {"q": q, "source": "local", "limit": 10})
                        if result["results"] or time.monotonic() >= deadline:
                            break
                        time.sleep(0.2)
                    assert result["results"], {
                        "response": result,
                        "search_log": [line for line in (root / "peer.log").read_text().splitlines()
                                       if "SEARCH failed" in line or "localpeer" in line or "Exception" in line
                                       or "Caused by" in line][-12:],
                    }
                    if q.startswith("site:"):
                        assert all(item["host"] == "edel.example" for item in result["results"])
                    checks += 1
                html = get("/yacysearch.html", {"query": "dashboard disposable", "resource": "local", "jsResort": "false"}, False)
                assert "dashboard" in html.lower() and "https://edel.example/" in html
                checks += 1

                # Use the actual existing wizard and Bearer API on this disposable peer.
                os.environ.update(SCOUTRO_URL=base, SCOUTRO_ADMIN_USER="admin", SCOUTRO_ADMIN_PASSWORD="yacy")
                spec = importlib.util.spec_from_file_location("citation_agent_helpers", REPO / "test/scoutro-api/test_agent_api.py")
                helpers = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(helpers)
                token, _, _ = helpers.create_agent("Citation scoped search fixture", collections=("edelsenior-web",))
                deadline = time.monotonic() + 12
                while True:
                    status, result, _ = helpers.agent_call(token, "GET", "/search", {"q": "site:edel.example dashboard disposable"})
                    if status != 200 or result["results"] or time.monotonic() >= deadline:
                        break
                    time.sleep(0.2)
                assert status == 200 and result["results"], result
                assert result["collections"] == ["edelsenior-web"], result
                assert all(item["url"].startswith("https://edel.example/page-") for item in result["results"]), result
                assert all(int(item["url"].rsplit("-", 1)[1]) < 3 for item in result["results"]), result
                checks += 1
                status, denied, _ = helpers.agent_call(token, "GET", "/search", {"q": "collection:bauteamcheck-web dashboard"})
                assert status == 400 and helpers.error_code(denied) == "query_modifier_not_allowed", denied
                checks += 1
                status, denied, _ = helpers.agent_call(token, "GET", "/search", {"q": "dashboard", "collection": "bauteamcheck-web"})
                assert status == 403 and helpers.error_code(denied) == "collection_not_in_scope", denied
                checks += 1
                raw = get("/solr/select", {"q": "*:*", "rows": 0, "wt": "json"})
                assert raw["response"]["numFound"] == 9, "Startup must not backfill/reset seeded documents"
                assert not get("/scoutro/api/v1/discovery/status")["enabled"]
                checks += 1
                print(f"PASS: {checks} Citation startup/search/Collection/Agent HTTP checks; no crawls", flush=True)
            finally:
                if process is not None and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                print("Disposable Citation peer stopped; temporary DATA removed", flush=True)


if __name__ == "__main__":
    main()
