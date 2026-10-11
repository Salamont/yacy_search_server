#!/usr/bin/env python3
"""Sign-in limits and the session cookie behind a simulated Olares edge. GPL-2.0-or-later.

Starts NEW temporary offline peers like access-live-smoke.py and puts a local
TLS reverse proxy in front of them that behaves like the Olares entrance chain
in its least favourable form: it terminates HTTPS, connects to Scoutro from
loopback (a trusted proxy by default), passes the client's X-Real-IP on
unchanged, appends the client to X-Forwarded-For and sends
X-Forwarded-Proto: http. Checks at POST /scoutro/api/v1/auth/login
(docs/SCOUTRO_USERS_ACCESS.md, "Sign-in client"):

- forged X-Real-IP / X-Forwarded-For values rotated over 30 failures cannot
  leave the shared bucket proxy:127.0.0.1 (429), and the audit never records
  them; a direct client keeps its own bucket; the per-name limit still
  applies; HTTP Digest keeps working while the bucket waits;
- in auto mode the inner HTTP hop gets no Secure cookie; with
  scoutro.auth.cookie.secure=always the cookie is set, used and cleared with
  Secure over internal HTTP and through the edge, SameSite stays Strict;
- only scoutro.auth.limits.proxyAddress=x-real-ip counts X-Real-IP;
- Chromium signs in, navigates and signs out through the edge (skipped with
  SCOUTRO_SKIP_BROWSER=1, reported as skipped).

Peers, certificates and DATA live in a temporary directory removed afterwards.
Never prints cookie values, Authorization headers or passwords; only cookie
attributes. Optional: SCOUTRO_DIRECT_ADDRESS (a non-loopback address of this
host for the direct client), NODE_PATH, SCOUTRO_CHROMIUM_PATH,
SCOUTRO_SMOKE_REPORT (write the check list as JSON).
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.request

REPO = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("access_smoke", Path(__file__).with_name("access-live-smoke.py"))
smoke = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(smoke)
check = smoke.check
CHECKS = []
LOGIN = "/scoutro/api/v1/auth/login"
SHARED = "proxy:127.0.0.1"
HOP = {"connection", "keep-alive", "proxy-connection", "proxy-authenticate", "proxy-authorization", "te", "trailer",
       "transfer-encoding", "upgrade", "content-length"}


def record(condition, label, detail=""):
    CHECKS.append({"check": label, "ok": bool(condition)})
    check(condition, label, detail)


# ---------------------------------------------------------------------------
# the simulated edge
# ---------------------------------------------------------------------------

class EdgeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass  # never log requests: they carry cookies and credentials

    def forward(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        upstream = http.client.HTTPConnection("127.0.0.1", self.server.upstream_port, timeout=120)
        upstream.putrequest(self.command, self.path, skip_host=True, skip_accept_encoding=True)
        forwarded = None
        for key, value in self.headers.items():
            low = key.lower()
            if low in HOP or low == "x-forwarded-proto":
                continue
            if low == "x-forwarded-for":
                forwarded = value
                continue
            upstream.putheader(key, value)  # Host, cookies and a client X-Real-IP pass on unchanged
        upstream.putheader("X-Forwarded-For", (forwarded + ", " if forwarded else "") + self.client_address[0])
        upstream.putheader("X-Forwarded-Proto", "http")
        if body is not None:
            upstream.putheader("Content-Length", str(len(body)))
        upstream.endheaders(body)
        response = upstream.getresponse()
        data = response.read()
        self.send_response_only(response.status, response.reason)
        for key, value in response.getheaders():
            if key.lower() not in HOP:
                self.send_header(key, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)
        upstream.close()

    do_GET = do_POST = do_PATCH = do_PUT = do_DELETE = do_HEAD = do_OPTIONS = forward


class Edge(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, upstream_port, cert, key):
        super().__init__(("127.0.0.1", 0), EdgeHandler)
        self.upstream_port = upstream_port
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        self.socket = context.wrap_socket(self.socket, server_side=True)
        self.base = f"https://localhost:{self.server_address[1]}"
        threading.Thread(target=self.serve_forever, daemon=True).start()


def certificate(directory):
    cert, key = directory / "edge-cert.pem", directory / "edge-key.pem"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key), "-out", str(cert),
                    "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return cert, key


class EdgeClient(smoke.Client):
    """A browser-like client of the HTTPS edge."""

    def __init__(self, base, cafile, digest=None):
        super().__init__(base, digest)
        handlers = [urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(cafile))),
                    urllib.request.HTTPCookieProcessor(self.jar), smoke.NoRedirect()]
        if digest:
            manager = urllib.request.HTTPPasswordMgrWithDefaultRealm()
            manager.add_password(None, base, *digest)
            handlers.append(urllib.request.HTTPDigestAuthHandler(manager))
        self.opener = urllib.request.build_opener(*handlers)


# ---------------------------------------------------------------------------
# helpers: values stay in memory, only attributes are reported
# ---------------------------------------------------------------------------

def raw(client, method, path, body=None, headers=None):
    """status, JSON body and the full header message (several Set-Cookie headers stay apart)"""
    h = dict(headers or {})
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        h.setdefault("Content-Type", "application/json")
    request = urllib.request.Request(client.base + path, data=data, method=method, headers=h)
    try:
        with client.opener.open(request, timeout=120) as response:
            return response.status, smoke.Client._json(response.read()), response.headers
    except urllib.error.HTTPError as e:
        return e.code, smoke.Client._json(e.read()), e.headers


def session_cookie(message):
    """(value, attributes) of the scoutro_session Set-Cookie; the value is never printed"""
    for line in message.get_all("Set-Cookie") or []:
        parts = [p.strip() for p in line.split(";")]
        name, _, value = parts[0].partition("=")
        if name == "scoutro_session":
            attributes = {}
            for p in parts[1:]:
                k, _, v = p.partition("=")
                attributes[k.lower()] = v
            return value, attributes
    return None, {}


def shown(attributes):
    return ";".join(sorted(k + ("=" + v if v else "") for k, v in attributes.items()))


def audit(digest, who=None):
    path = "/scoutro/api/v1/access/audit?limit=500" + ("&who=" + who if who else "")
    status, body, _ = digest.call("GET", path)
    return body.get("entries", []) if status == 200 and isinstance(body, dict) else []


def direct_address():
    configured = os.environ.get("SCOUTRO_DIRECT_ADDRESS")
    if configured:
        return configured
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("192.0.2.1", 9))  # chooses the outgoing interface, sends nothing
        address = s.getsockname()[0]
    return None if ipaddress.ip_address(address).is_loopback else address


# ---------------------------------------------------------------------------
# peer 1: defaults (cookie auto, shared proxy bucket)
# ---------------------------------------------------------------------------

def limits(base, edge, cafile, direct):
    digest = smoke.Client(base, digest=("admin", "yacy"))
    via_edge = EdgeClient(edge.base, cafile)

    # auto mode: the edge's inner hop is HTTP with X-Forwarded-Proto: http, so no Secure
    status, body, message = raw(via_edge, "POST", LOGIN, {"username": "admin", "password": "yacy"})
    _, attributes = session_cookie(message)
    record(status == 200, "edge: built-in admin sign-in", str(status))
    record("secure" not in attributes and attributes.get("samesite") == "Strict",
           "auto: no Secure behind the TLS edge (inner hop HTTP)", shown(attributes))
    via_edge.csrf = body.get("csrf", "") if isinstance(body, dict) else ""
    record(via_edge.call("POST", "/scoutro/api/v1/auth/logout", {})[0] == 200, "edge: sign-out")

    # forged headers rotated over 30 failures, at most 4 per probe name
    statuses = set()
    for i in range(30):
        headers = {}
        if i % 3 != 1:
            headers["X-Real-IP"] = f"198.51.100.{i + 1}"
        if i % 3 != 0:
            headers["X-Forwarded-For"] = f"203.0.113.{i + 1}"
        status, body, _ = raw(EdgeClient(edge.base, cafile), "POST", LOGIN,
                              {"username": f"signin-probe-{i // 4:02d}", "password": f"wrong-{i}"}, headers)
        statuses.add((status, via_edge.code(body)))
    record(statuses == {(401, "invalid_credentials")}, "edge: 30 failures with rotating forged headers", str(statuses))
    status, body, message = raw(EdgeClient(edge.base, cafile), "POST", LOGIN, {"username": "signin-probe-fresh", "password": "x"},
                                {"X-Real-IP": "198.51.100.200", "X-Forwarded-For": "203.0.113.200"})
    record(status == 429 and via_edge.code(body) == "too_many_attempts" and (message.get("Retry-After") or "").isdigit(),
           "edge: fresh forged X-Real-IP/X-Forwarded-For stays in the shared bucket (429)", f"{status} {via_edge.code(body)}")
    status, body, _ = raw(EdgeClient(edge.base, cafile), "POST", LOGIN, {"username": "admin", "password": "yacy"},
                          {"X-Real-IP": "198.51.100.201"})
    record(status == 429, "edge: the shared bucket also holds a correct sign-in (documented trade-off)", str(status))

    # HTTP Digest is not counted by the sign-in limits
    record(digest.call("GET", "/scoutro/api/v1/system")[0] == 200, "digest via loopback works while the bucket waits")
    record(EdgeClient(edge.base, cafile, digest=("admin", "yacy")).call("GET", "/scoutro/api/v1/system")[0] == 200,
           "digest through the edge works while the bucket waits")

    # a direct client keeps its own bucket; the per-name limit still applies
    if direct:
        direct_base = base.replace("127.0.0.1", direct)
        status, body, _ = raw(smoke.Client(direct_base), "POST", LOGIN, {"username": "signin-direct-probe", "password": "x"},
                              {"X-Real-IP": "198.51.100.202"})
        record(status == 401 and via_edge.code(body) == "invalid_credentials", "direct client: own bucket", str(status))
        own = smoke.Client(direct_base)
        status, _ = own.login("admin", "yacy")
        record(status == 200, "direct client: correct sign-in", str(status))
        record(own.call("POST", "/scoutro/api/v1/auth/logout", {})[0] == 200, "direct client: sign-out")
        codes = [raw(smoke.Client(direct_base), "POST", LOGIN, {"username": "signin-account-probe", "password": f"w{i}"})[0]
                 for i in range(5)]
        status, body, _ = raw(smoke.Client(direct_base), "POST", LOGIN, {"username": "signin-account-probe", "password": "w5"})
        other, _, _ = raw(smoke.Client(direct_base), "POST", LOGIN, {"username": "signin-other-probe", "password": "w"})
        record(codes == [401] * 5 and status == 429 and other == 401, "per-name limit after 5 failures, other names unaffected",
               f"{codes} {status} {other}")
    else:
        record(False, "direct client: no non-loopback address found (set SCOUTRO_DIRECT_ADDRESS)")

    # a changing Scoutro API call is audited with the same client
    edge_digest = EdgeClient(edge.base, cafile, digest=("admin", "yacy"))
    status, _, _ = raw(edge_digest, "POST", "/scoutro/api/v1/collections/audit-probe-7", {}, {"X-Real-IP": "198.51.100.210"})
    record(status >= 400, "edge: changing API call refused (audited)", str(status))

    # the audit names the bucket, never a forged value
    entries = audit(digest)
    calls = [e for e in entries if e.get("target") == "audit-probe-7"]
    record(len(calls) == 1 and calls[0].get("client") == SHARED, "audit: API call counted as proxy:127.0.0.1",
           str([e.get("client") for e in calls]))
    probes = [e for e in entries if str(e.get("target", "")).startswith("signin-probe-")]
    record(len(probes) == 31 and {e.get("client") for e in probes} == {SHARED},
           "audit: every edge attempt counted as proxy:127.0.0.1", str(sorted({e.get('client') for e in probes}))[:200])
    record(any(e.get("target") == "signin-probe-fresh" and e.get("reason") == "too_many_attempts" for e in probes),
           "audit: the refusal is recorded")
    text = json.dumps(entries)
    record("198.51.100." not in text and "203.0.113." not in text, "audit: no forged address recorded")
    if direct:
        clients = {e.get("client") for e in entries if e.get("target") in ("signin-direct-probe", "signin-account-probe")}
        record(clients == {direct}, "audit: direct client by its socket address", str(clients))
    record("password" not in text.lower().replace("auth.password", "") and "wrong-" not in text, "audit: no password text")


# ---------------------------------------------------------------------------
# peer 2: scoutro.auth.cookie.secure=always, scoutro.auth.limits.proxyAddress=x-real-ip
# ---------------------------------------------------------------------------

def secure_cookie(base, edge, cafile, direct, user, password):
    digest = smoke.Client(base, digest=("admin", "yacy"))
    status, body, _ = digest.call("POST", "/scoutro/api/v1/users", {"username": user, "role": "administrator",
                                                                   "password": password, "mustChangePassword": False})
    record(status == 201, "create administrator account", str(status))

    # internal HTTP: set
    internal = smoke.Client(base)
    status, body, message = raw(internal, "POST", LOGIN, {"username": user, "password": password},
                                {"X-Forwarded-Proto": "http"})
    value, attributes = session_cookie(message)
    record(status == 200 and value, "internal HTTP: sign-in", str(status))
    record({"secure", "httponly"} <= set(attributes) and attributes.get("samesite") == "Strict"
           and attributes.get("path") == "/" and "max-age" not in attributes,
           "always: set cookie Secure, HttpOnly, SameSite=Strict, Path=/ over internal HTTP", shown(attributes))
    csrf = body.get("csrf", "") if isinstance(body, dict) else ""
    # internal HTTP: use (as the edge forwards the browser's cookie)
    cookie = {"Cookie": "scoutro_session=" + (value or "")}
    status, body, _ = raw(internal, "GET", "/scoutro/api/v1/auth/session", headers=cookie)
    record(status == 200 and body.get("authenticated") is True and body.get("user", {}).get("username") == user,
           "internal HTTP: the Secure cookie authenticates", str(status))
    status, _, _ = raw(internal, "GET", "/ConfigAccounts_p.html", headers=dict(cookie, Accept="text/html"))
    record(status == 200, "internal HTTP: administrator page with the cookie", str(status))
    # internal HTTP: clear
    status, body, message = raw(internal, "POST", "/scoutro/api/v1/auth/logout", {}, dict(cookie, **{"X-Scoutro-CSRF": csrf}))
    cleared, attributes = session_cookie(message)
    record(status == 200 and body.get("ok") is True, "internal HTTP: sign-out", str(status))
    record(cleared == "" and attributes.get("max-age") == "0" and "secure" in attributes and attributes.get("samesite") == "Strict"
           and "httponly" in attributes and attributes.get("path") == "/",
           "always: clearing cookie Max-Age=0 with Secure over internal HTTP", shown(attributes))
    status, body, _ = raw(internal, "GET", "/scoutro/api/v1/auth/session", headers=cookie)
    record(body.get("authenticated") is False, "internal HTTP: the old cookie no longer authenticates")

    # through the edge: the client keeps the Secure cookie and sends it on HTTPS only
    browser = EdgeClient(edge.base, cafile)
    status, body = browser.login(user, password)
    stored = [c for c in browser.jar if c.name == "scoutro_session"]
    record(status == 200 and len(stored) == 1 and stored[0].secure, "edge: Secure cookie stored by the HTTPS client", str(status))
    record(browser.call("GET", "/scoutro/api/v1/auth/session")[1].get("authenticated") is True, "edge: cookie used")
    status, body, message = raw(browser, "POST", "/scoutro/api/v1/auth/logout", {}, {"X-Scoutro-CSRF": browser.csrf})
    _, attributes = session_cookie(message)
    record(status == 200 and attributes.get("max-age") == "0" and "secure" in attributes, "edge: sign-out clears with Secure",
           shown(attributes))
    record(not [c for c in browser.jar if c.name == "scoutro_session"], "edge: client dropped the cookie")
    record(browser.call("GET", "/scoutro/api/v1/auth/session")[1].get("authenticated") is False, "edge: signed out")

    # the explicit opt-in is the only way to count X-Real-IP
    raw(EdgeClient(edge.base, cafile), "POST", LOGIN, {"username": "optin-probe-a", "password": "x"}, {"X-Real-IP": "198.51.100.77"})
    raw(EdgeClient(edge.base, cafile), "POST", LOGIN, {"username": "optin-probe-b", "password": "x"}, {"X-Forwarded-For": "203.0.113.77"})
    if direct:
        raw(smoke.Client(base.replace("127.0.0.1", direct)), "POST", LOGIN, {"username": "optin-probe-c", "password": "x"},
            {"X-Real-IP": "198.51.100.78"})
    clients = {e.get("target"): e.get("client") for e in audit(digest) if str(e.get("target", "")).startswith("optin-probe-")}
    record(clients.get("optin-probe-a") == "198.51.100.77", "x-real-ip opt-in: one X-Real-IP of the trusted proxy counts",
           str(clients.get("optin-probe-a")))
    record(clients.get("optin-probe-b") == SHARED, "x-real-ip opt-in: X-Forwarded-For alone stays shared",
           str(clients.get("optin-probe-b")))
    if direct:
        record(clients.get("optin-probe-c") == direct, "x-real-ip opt-in: an untrusted peer stays itself",
               str(clients.get("optin-probe-c")))


def browser_check(edge, user, password):
    if os.environ.get("SCOUTRO_SKIP_BROWSER") == "1":
        CHECKS.append({"check": "Chromium through the edge", "ok": None, "skipped": True})
        print("SKIPPED: Chromium through the edge", flush=True)
        return
    env = dict(os.environ, SCOUTRO_EDGE_URL=edge.base, SCOUTRO_TEST_USER=json.dumps({"username": user, "password": password}))
    result = subprocess.run(["node", str(REPO / "test/scoutro-ui/signin-proxy-browser-test.mjs")], cwd=REPO, env=env,
                            capture_output=True, text=True, timeout=300)
    lines = [line for line in result.stdout.splitlines() if line.startswith("{")]
    for line in lines:
        item = json.loads(line)
        record(item.get("ok"), "chromium: " + item.get("check", "?"), item.get("detail", ""))
    record(result.returncode == 0 and lines, "chromium run", (result.stderr or "")[-400:])


# ---------------------------------------------------------------------------

def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def main():
    work = Path(tempfile.mkdtemp(prefix="scoutro-signin-proxy-"))
    processes = []
    try:
        cert, key = certificate(work)
        direct = direct_address()
        print("direct client address:", direct or "none", flush=True)

        port = free_port()
        process, base = smoke.start_peer(work / "peer-default", port)
        processes.append(process)
        edge = Edge(port, cert, key)
        limits(base, edge, cert, direct)
        edge.shutdown()
        stop(process)

        port = free_port()
        process, base = smoke.start_peer(work / "peer-secure", port, extra=[
            "scoutro.auth.cookie.secure=always", "scoutro.auth.limits.proxyAddress=x-real-ip"])
        processes.append(process)
        edge = Edge(port, cert, key)
        user, password = "edge-admin", "t-" + secrets.token_urlsafe(18)
        secure_cookie(base, edge, cert, direct, user, password)
        browser_check(edge, user, password)
        edge.shutdown()
    finally:
        for process in processes:
            stop(process)
        shutil.rmtree(work, ignore_errors=True)
    report = os.environ.get("SCOUTRO_SMOKE_REPORT")
    if report:
        Path(report).write_text(json.dumps({"passed": smoke.RESULTS["pass"], "failed": smoke.RESULTS["fail"],
                                            "checks": CHECKS}, indent=2) + "\n")
    print(f"{smoke.RESULTS['pass']} passed, {len(smoke.RESULTS['fail'])} failed", flush=True)
    return 1 if smoke.RESULTS["fail"] else 0


if __name__ == "__main__":
    sys.exit(main())
