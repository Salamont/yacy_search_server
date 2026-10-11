#!/usr/bin/env python3
"""Disposable check: an agent's crawl stays on exactly its start host. GPL-2.0-or-later.

YaCy's domain crawl filter is a host prefix ((www.)?host.*): a crawl of
agent-host.example would also follow links to agent-host.example.test, as a
crawl of example.com would follow example.com.au. An agent limited to one
collection and one allowed domain must neither reach such a host (outside its
domain allowlist) nor re-index its pages into the agent's collection, the
more so when the host already has documents in a collection outside the
agent's scope (docs/SCOUTRO_USERS_ACCESS.md).

Starts two local HTTP servers (allowed start host agent-host.example on
127.0.0.1, look-alike foreign host agent-host.example.test on 127.0.0.10,
which already has a document in a foreign collection; both names resolve
only inside the peer JVM through a temporary jdk.net.hosts.file) and a NEW
temporary peer in the "intranet" network (local addresses may be crawled),
creates a collection-limited agent with a crawl grant for agent-host.example,
lets it crawl its start host with scope "domain" and checks which requests
reached the foreign host and which documents landed in the agent's
collection. Peer, servers and DATA are removed in a finally block.

Requires a compiled repository and a JDK 17+. Optional: JAVA.
"""
import http.server
import importlib.util
import json
import os
import shutil
import socket
import sys
import tempfile
import threading
import time
import urllib.parse
from pathlib import Path

sys.dont_write_bytecode = True  # imported helper modules leave no __pycache__
HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("access_smoke", HERE.parent / "scoutro-access/access-live-smoke.py")
smoke = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(smoke)
check = smoke.check

OWN, FOREIGN = "stackfinder-web", "edelsenior-web"  # fixture collections (DashboardFixture)
# reserved names (RFC 2606) with TLDs YaCy does not know, so it classifies them by their loopback address as local
OWN_HOST, FOREIGN_HOST = "agent-host.example", "agent-host.example.test"
ADDRESS = {OWN_HOST: "127.0.0.1", FOREIGN_HOST: "127.0.0.10"}
REQUESTS = {OWN_HOST: [], FOREIGN_HOST: []}


def server(name):
    class Site(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            REQUESTS[name].append(self.path)
            if self.path == "/robots.txt":
                body, kind = b"User-agent: *\nAllow: /\n", "text/plain"
            elif name == OWN_HOST:
                foreign = f"http://{FOREIGN_HOST}:{FOREIGN_PORT}"
                body = (f'<html><head><title>Start</title></head><body><p>Start page of the allowed host.</p>'
                        f'<a href="/own-page">own page</a> <a href="{foreign}/fresh">look-alike host</a></body></html>').encode()
                kind = "text/html"
            else:
                body, kind = b"<html><head><title>Foreign</title></head><body><p>FOREIGNTEXT of another collection.</p></body></html>", "text/html"
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    s = http.server.ThreadingHTTPServer((ADDRESS[name], 0), Site)
    threading.Thread(target=s.serve_forever, daemon=True).start()
    return s


def push(digest, url, html, collection):
    boundary = f"----scoutro{time.time_ns()}"
    fields = {"count": "1", "synchronous": "true", "commit": "true", "url-0": url, "collection-0": collection,
              "contentType-0": "text/html", "lastModified-0": "Tue, 15 Nov 1994 12:45:26 GMT", "responseHeader-0": ""}
    body = b"".join(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode() for k, v in fields.items())
    body += (f'--{boundary}\r\nContent-Disposition: form-data; name="data-0"; filename="page.html"\r\n'
             "Content-Type: text/html\r\n\r\n").encode() + html.encode() + f"\r\n--{boundary}--\r\n".encode()
    import urllib.request
    request = urllib.request.Request(digest.base + "/api/push_p.json", data=body, method="POST",
                                     headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with digest.opener.open(request, timeout=120) as response:
        return json.loads(response.read()).get("countsuccess") == 1


def docs(digest, query):
    status, body, _ = digest.call("GET", "/solr/select?wt=json&rows=50&fl=sku,collection_sxt&q=" + urllib.parse.quote(query))
    return body.get("response", {}).get("docs", []) if isinstance(body, dict) else []


def run(base, digest, agents):
    own_url = f"http://{OWN_HOST}:{OWN_PORT}/"
    check(push(digest, f"http://{FOREIGN_HOST}:{FOREIGN_PORT}/known", "<html><body>known page</body></html>", FOREIGN),
          "the look-alike host already has a document in a foreign collection")
    token, agent_id, _ = agents.create_agent("Host limit disposable", collections=(OWN,), preset="research_crawl",
                                             domains=OWN_HOST, max_pages="5")
    # the direct way is closed: the look-alike host is indexed elsewhere and not on the domain allowlist
    status, body, _ = agents.agent_call(token, "POST", "/crawls", body={"url": f"http://{FOREIGN_HOST}:{FOREIGN_PORT}/",
                                                                         "collection": OWN, "scope": "domain"},
                                        headers={"Idempotency-Key": "direct-1"})
    check(status == 403 and agents.error_code(body) == "limit_exceeded:domains", "a direct crawl of the look-alike host is refused",
          f"{status} {body}")
    status, body, _ = agents.agent_call(token, "POST", "/crawls", body={"url": own_url, "collection": OWN, "scope": "domain",
                                                                         "depth": 1, "maxPages": 5},
                                        headers={"Idempotency-Key": "own-1"})
    check(status == 201 and body.get("id"), "the agent starts a crawl of its allowed host", f"{status} {str(body)[:300]}")
    crawl = body.get("id") if isinstance(body, dict) else None
    status, again, _ = agents.agent_call(token, "POST", "/crawls", body={"url": own_url, "collection": OWN, "scope": "domain",
                                                                          "depth": 1, "maxPages": 5},
                                         headers={"Idempotency-Key": "own-1"})
    check(status == 200 and again.get("idempotentReplay") is True and again.get("id") == crawl, "the same key replays the crawl",
          f"{status} {str(again)[:200]}")
    # the crawl is done when the own pages are indexed; give a crawl that wrongly follows the link time to reach it
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        own = [d["sku"] for d in docs(digest, f'collection_sxt:{OWN} AND sku:"{own_url}own-page"')]
        if own:
            break
        time.sleep(1)
    check(bool(own), "the own pages are indexed into the agent's collection", str(REQUESTS))
    time.sleep(15)
    foreign_requests = [p for p in REQUESTS[FOREIGN_HOST] if p != "/known"]
    check(not foreign_requests, "the look-alike foreign host is never requested", str(foreign_requests))
    moved = [d["sku"] for d in docs(digest, f"collection_sxt:{OWN}") if FOREIGN_HOST in d["sku"]]
    check(not moved, "no page of the look-alike host lands in the agent's collection", str(moved))
    # the agent sees its crawl only while the profile's start marker is readable ("removed" otherwise)
    status, body, _ = agents.agent_call(token, "GET", "/crawls/" + str(crawl))
    check(status == 200 and body.get("id") == crawl and body.get("state") not in (None, "removed") and body.get("host") == OWN_HOST,
          "the crawl stays assigned to the agent by its start marker", f"{status} {str(body)[:300]}")
    status, profile = smoke.Client(base, digest=("admin", "yacy")).page("/CrawlProfileEditor_p.xml")
    check("(?!https?://(www\\.)?\\Q" + OWN_HOST + "\\E" in profile and "scoutro-start-" in profile,
          "the crawl profile carries the start marker and the exact-host filter")
    status, body, _ = agents.agent_call(token, "POST", "/crawls/" + str(crawl) + "/stop", body={}, headers={"Idempotency-Key": "stop-1"})
    check(status == 200, "the agent can stop its crawl (start marker matched)", f"{status} {str(body)[:200]}")


def main():
    global OWN_PORT, FOREIGN_PORT
    own, foreign = server(OWN_HOST), server(FOREIGN_HOST)
    OWN_PORT, FOREIGN_PORT = own.server_address[1], foreign.server_address[1]
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    root = Path(tempfile.mkdtemp(prefix="scoutro-agent-host-"))
    (root / "DATA/INDEX").mkdir(parents=True)
    (root / "DATA/INDEX/intranet").symlink_to(root / "DATA/INDEX/webportal")  # the fixture writes the webportal segment
    # the test names resolve only inside the peer JVM (and the fixture run), never through the system resolver
    hosts = root / "hosts"
    hosts.write_text("".join(f"{a} {n}\n" for n, a in ADDRESS.items())
                     + f"127.0.0.1 localhost {socket.gethostname()}\n::1 localhost\n")
    wrapper = root / "java"
    wrapper.write_text(f'#!/bin/sh\nexec "{smoke.JAVA}" -Djdk.net.hosts.file="{hosts}" "$@"\n')
    wrapper.chmod(0o700)
    smoke.JAVA = str(wrapper)
    process = None
    try:
        process, base = smoke.start_peer(root, port, extra=["network.unit.definition=defaults/yacy.network.intranet.unit"])
        os.environ["SCOUTRO_URL"] = base
        os.environ.setdefault("SCOUTRO_ADMIN_USER", "admin")
        os.environ.setdefault("SCOUTRO_ADMIN_PASSWORD", "yacy")
        spec = importlib.util.spec_from_file_location("agent_api", HERE / "test_agent_api.py")
        agents = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(agents)
        run(base, smoke.Client(base, digest=("admin", "yacy")), agents)
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except Exception:
                process.kill()
                process.wait()
        own.shutdown()
        foreign.shutdown()
        shutil.rmtree(root, ignore_errors=True)
    print(f"{smoke.RESULTS['pass']} passed, {len(smoke.RESULTS['fail'])} failed; requests: {REQUESTS}", flush=True)
    return 1 if smoke.RESULTS["fail"] else 0


if __name__ == "__main__":
    sys.exit(main())
