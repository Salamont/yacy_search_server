#!/usr/bin/env python3
"""Check the access surface of a Scoutro server from an agent's position.

Copyright (C) 2026 Scoutro contributors
Scoutro is an independent community project based on YaCy.
Licensed under the GNU General Public License, version 2 or (at your option)
any later version.

Run it on a machine (or in a network namespace) where an agent would run,
against a disposable or the intended server - it only reads:

    SCOUTRO_AGENT_BASE=http://192.0.2.2:8095 \
    SCOUTRO_DIRECT=192.0.2.2:8090,192.0.2.2:8443 \
    SCOUTRO_AGENT_TOKEN=sca_... \
    python3 test/scoutro-server/check_agent_access.py

SCOUTRO_AGENT_BASE   the address agents are given (agent listener of the proxy)
SCOUTRO_DIRECT       YaCy ports as seen from the agent; they must be unreachable
SCOUTRO_AGENT_TOKEN  optional: a valid agent token; without it only the
                     refusals are checked

Every path outside the agent API must be refused (status >= 400) with and
without the token, including path tricks that resolve to other endpoints,
and the YaCy ports must refuse connections. Exit status 1 lists violations.
"""

import http.client
import json
import os
import socket
import sys
import urllib.parse

BASE = os.environ.get("SCOUTRO_AGENT_BASE", "http://127.0.0.1:8095").rstrip("/")
DIRECT = [d for d in os.environ.get("SCOUTRO_DIRECT", "").split(",") if d.strip()]
TOKEN = os.environ.get("SCOUTRO_AGENT_TOKEN", "")

RPC = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                  "params": {"name": "search", "arguments": {"query": "pflege"}}})
CHAT = json.dumps({"model": "x", "messages": [{"role": "user", "content": "pflege"}]})

# (method, raw path, body, what it would expose)
BLOCKED = [
    ("GET", "/solr/select?q=*:*&wt=json", None, "Solr: complete index"),
    ("GET", "/solr/collection1/select?q=*:*&wt=json", None, "Solr core"),
    ("GET", "/solr/webgraph/select?q=*:*&wt=json", None, "Solr webgraph"),
    ("GET", "/gsa/search?q=pflege", None, "GSA search"),
    ("GET", "/yacysearch.json?query=pflege", None, "native search JSON"),
    ("GET", "/yacysearch.html?query=pflege", None, "native search page"),
    ("GET", "/yacysearch.rss?query=pflege", None, "native search RSS"),
    ("GET", "/suggest.json?q=pfl", None, "suggestions"),
    ("GET", "/yacy/search.html?query=pflege", None, "P2P search API"),
    ("POST", "/tools", RPC, "native MCP search"),
    ("POST", "/tools/call", RPC, "native MCP search"),
    ("POST", "/v1/chat/completions", CHAT, "RAG chat"),
    ("GET", "/v1/models", None, "model list"),
    ("GET", "/api/tags", None, "model list"),
    ("GET", "/proxy.html?url=http://example.com/", None, "URL proxy"),
    ("GET", "/scoutro/api/v1/search?q=pflege", None, "admin API"),
    ("GET", "/scoutro/api/v1/health", None, "admin API"),
    ("GET", "/scoutro/api/v1/index", None, "admin API"),
    ("GET", "/api/status_p.xml", None, "status"),
    ("GET", "/Crawler_p.json", None, "crawler"),
    ("GET", "/ScoutroAgents_p.html", None, "agent administration"),
    ("GET", "/index.html", None, "web interface"),
    ("GET", "/", None, "web interface"),
    # path tricks that would resolve to other endpoints behind the proxy
    ("GET", "/scoutro/api/agent/v1/../../../solr/select?q=*:*&wt=json", None, "dot segments"),
    ("GET", "/scoutro/api/agent/v1/%2e%2e/%2e%2e/%2e%2e/solr/select?q=*:*&wt=json", None, "encoded dot segments"),
    ("GET", "/scoutro/api/agent/v1/..%2f..%2f..%2fsolr/select?q=*:*&wt=json", None, "encoded slashes"),
    ("GET", "/scoutro/api/agent/v1/..;/..;/..;/solr/select?q=*:*&wt=json", None, "path parameters"),
    ("GET", "//solr/select?q=*:*&wt=json", None, "double slash"),
    ("GET", "/scoutro/api/agent/../v1/search?q=pflege", None, "dot segment into the admin API"),
    ("GET", "/SCOUTRO/api/agent/v1/../../v1/search?q=pflege", None, "case variant"),
]


def request(method, path, body=None, token=None):
    u = urllib.parse.urlsplit(BASE)
    conn_cls = http.client.HTTPSConnection if u.scheme == "https" else http.client.HTTPConnection
    conn = conn_cls(u.hostname, u.port or (443 if u.scheme == "https" else 80), timeout=20)
    headers = {"Accept": "application/json, text/event-stream"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
    try:
        conn.putrequest(method, path, skip_accept_encoding=True)  # the raw path, not normalized by the client
        for k, v in headers.items():
            conn.putheader(k, v)
        data = body.encode() if body is not None else b""
        conn.putheader("Content-Length", str(len(data)))
        conn.endheaders(data)
        r = conn.getresponse()
        return r.status, r.read(4000).decode("utf-8", "replace")
    except OSError as e:
        return 0, str(e)
    finally:
        conn.close()


def reachable(hostport):
    host, port = hostport.rsplit(":", 1)
    try:
        with socket.create_connection((host.strip("[]"), int(port)), timeout=5):
            return True
    except OSError:
        return False


def main():
    failures = []

    def check(ok, label):
        print(("PASS " if ok else "FAIL ") + label)
        if not ok:
            failures.append(label)

    status, body = request("GET", "/scoutro/api/agent/v1/capabilities")
    check(status == 401 and "missing_bearer" in body, "agent API without token -> 401 missing_bearer (got %s)" % status)
    if TOKEN:
        status, body = request("GET", "/scoutro/api/agent/v1/capabilities", token=TOKEN)
        check(status == 200 and '"actions"' in body, "agent API with token -> 200 capabilities (got %s)" % status)
        status, body = request("GET", "/scoutro/api/agent/v1/search?q=pflege", token=TOKEN)
        check(status in (200, 403), "agent search with token answered by the agent API (got %s)" % status)
    for token in ([None, TOKEN] if TOKEN else [None]):
        who = "with token" if token else "without token"
        for method, path, body, what in BLOCKED:
            status, text = request(method, path, body, token)
            check(status >= 400, "%s %s %s -> refused (%s; got %s)" % (method, path, who, what, status))
    for d in DIRECT:
        check(not reachable(d), "direct YaCy port %s is not reachable from here" % d)
    print("\n%d violation(s)" % len(failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
