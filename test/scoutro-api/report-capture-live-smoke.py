#!/usr/bin/env python3
"""Crawl report capture end to end on NEW disposable DATA only.

Starts a local fixture site (127.0.0.1, resolved through a private JVM hosts file),
a disposable peer, one Scoutro crawl through the API, and checks the captured
scoutro_domains row after the peer has stopped. No public network, no automation,
no existing DATA. GPL-2.0-or-later.
"""
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = Path(__file__).resolve().parents[2]
JAVA = os.environ.get("JAVA", "java")
CLASSPATH = f"{REPO}/build/classes/java/main:{REPO}/lib/*"
HOST = "www.report.test"
sys.dont_write_bytecode = True

PAGES = {
    "/robots.txt": (200, "text/plain", "User-agent: *\nAllow: /\n"),
    "/": (200, "text/html", '<html><head><title>Report fixture</title></head><body><h1>Fixture</h1>'
          '<a href="/a.html">a</a> <a href="/b.html">b</a> <a href="/noindex.html">n</a>'
          ' <a href="/missing.html">m</a> <a href="/moved">r</a></body></html>'),
    "/a.html": (200, "text/html", "<html><head><title>Page A</title></head><body><p>alpha page text</p></body></html>"),
    "/b.html": (200, "text/html", "<html><head><title>Page B</title></head><body><p>beta page text</p></body></html>"),
    "/noindex.html": (200, "text/html", '<html><head><title>Hidden</title><meta name="robots" content="noindex"></head>'
                      "<body><p>not for the index</p></body></html>"),
}


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class Site(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == "/moved":
            self.send_response(301)
            self.send_header("Location", "/b.html")
            self.end_headers()
            return
        status, kind, body = PAGES.get(path, (404, "text/html", "<html><body>missing</body></html>"))
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", kind + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


def main():
    port, site_port = free_port(), free_port()
    base = f"http://127.0.0.1:{port}"
    site = http.server.ThreadingHTTPServer(("127.0.0.1", site_port), Site)
    threading.Thread(target=site.serve_forever, daemon=True).start()
    checks = 0
    process = None
    with tempfile.TemporaryDirectory(prefix="scoutro-report-capture-") as temporary:
        root = Path(temporary)
        settings = root / "DATA/SETTINGS"
        settings.mkdir(parents=True)
        (root / "hosts").write_text(f"127.0.0.1 {HOST}\n")
        network = root / "fixture.network.unit"
        network.write_text((REPO / "defaults/yacy.network.webportal.unit").read_text().replace(
            "network.unit.domain = global", "network.unit.domain = any"))
        (settings / "yacy.conf").write_text("\n".join([
            f"port={port}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
            "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
            f"network.unit.definition={network}", "browserPopUpTrigger=false",
            "autocrawl=false", "server.https=false", "locale.language=default", "upnp.enabled=false",
            "scoutro.discovery.enabled=false", "search.verify=false",
            "90_cleanup_idlesleep=2000", "90_cleanup_busysleep=2000",
            "scoutro.report.captureIntervalSeconds=5", "scoutro.report.settleSeconds=5",
            "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
            "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
        ]) + "\n")
        password = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        password.add_password(None, base, "admin", "yacy")
        client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(password))

        def api(method, path, body=None, headers=None):
            request = urllib.request.Request(base + "/scoutro/api/v1" + path, method=method,
                                             data=None if body is None else json.dumps(body).encode(),
                                             headers=dict({"Content-Type": "application/json"} if body is not None else {}, **(headers or {})))
            with client.open(request, timeout=30) as response:
                return response.status, json.loads(response.read().decode())

        env = {k: v for k, v in os.environ.items() if k != "JAVA_TOOL_OPTIONS"}
        with (root / "peer.log").open("w") as log:
            try:
                process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", f"-Djdk.net.hosts.file={root / 'hosts'}",
                                            "-cp", CLASSPATH, "net.yacy.yacy", "-startup", str(root)],
                                           cwd=REPO, stdout=log, stderr=log, env=env)
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError((root / "peer.log").read_text()[-4000:])
                    try:
                        api("GET", "/health")
                        break
                    except (OSError, urllib.error.URLError):
                        if time.monotonic() >= deadline:
                            raise RuntimeError((root / "peer.log").read_text()[-4000:])
                        time.sleep(0.5)
                time.sleep(12)  # two capture intervals: the ErrorCache baseline exists before the crawl starts
                status, started = api("POST", "/crawls", {"url": f"http://{HOST}:{site_port}/", "collection": "report",
                                                          "depth": 2, "maxPages": 15, "scope": "domain"},
                                      {"Idempotency-Key": "report-capture-smoke"})
                assert status in (200, 201), started
                crawl_id = started["id"]
                checks += 1
                deadline = time.monotonic() + 180
                while True:
                    _, crawl = api("GET", "/crawls/" + crawl_id)
                    if crawl["state"] == "terminated":
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError("crawl did not terminate: %s" % crawl)
                    time.sleep(2)
                checks += 1
                time.sleep(25)  # settle delay plus two capture intervals
                select = urllib.parse.urlencode({"q": f'host_s:"{HOST}"', "rows": 50, "wt": "json",
                                                 "fl": "sku,httpstatus_i,failtype_s,failreason_s,collection_sxt"})
                with client.open(f"{base}/solr/select?{select}", timeout=30) as response:
                    documents = json.loads(response.read().decode())["response"]["docs"]
            finally:
                if process is not None and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=60)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                site.shutdown()
        dump = subprocess.run([JAVA, "-cp", CLASSPATH, str(REPO / "test/scoutro-api/ReportTableDump.java"), str(root)],
                              cwd=REPO, check=True, timeout=120, capture_output=True, text=True, env=env).stdout
        rows = [json.loads(line) for line in dump.splitlines() if line.startswith("{")]
        log_tail = [line for line in (root / "peer.log").read_text().splitlines() if "SCOUTRO-REPORT" in line][-10:]
        assert not any("step failed" in line for line in log_tail), log_tail
        scoutro = sorted(str(p.relative_to(root)) for p in (root / "DATA").rglob("*")
                         if "SCOUTRO" in str(p) or "scoutro-discovery" in p.name)
        assert not any(name.startswith("DATA/SCOUTRO/reports/rollups/") for name in scoutro), scoutro  # no Discovery job: no rollup
        assert len(rows) == 1, {"rows": rows, "log": log_tail}
        row = rows[0]
        print(json.dumps({"row": row, "documents": documents, "scoutro_files": scoutro}, indent=1, sort_keys=True), flush=True)
        assert row["host"] == HOST and row["collection"] == "report" and row["v"] == "1", row
        assert row["crawl_id"] == crawl_id and len(row.get("start_marker", "")) == 32, row
        assert int(row["ended_at"]) >= int(row["started_at"]), row
        assert "prev_crawl_id" not in row and not any(k.startswith("pc_") for k in row), row
        checks += 1
        assert row["n_pages_ok"] == "3", row               # /, /a.html, /b.html
        assert row["n_pages_redirect"] == "1", row         # /moved, followed by the crawler
        assert row["n_pages_failed"] == "1", row           # only /missing.html; the redirect is no failure
        assert row["n_pages_client_error"] == "1", row     # /missing.html
        assert row["n_excl_noindex"] == "1", row           # /noindex.html, from the ErrorCache
        assert row["n_depth"] == "2" and row["n_max_pages"] == "15", row
        checks += 1
        assert row["s_outcome"] == "partial", row          # pages loaded, one failure (404)
        assert row["s_coverage"] == "complete", row
        checks += 1
        print(f"PASS: {checks} crawl report capture checks on a disposable peer", flush=True)


if __name__ == "__main__":
    main()
