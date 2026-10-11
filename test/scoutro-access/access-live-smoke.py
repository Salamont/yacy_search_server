#!/usr/bin/env python3
"""Disposable multi-user access check for Scoutro accounts. GPL-2.0-or-later.

Starts a NEW temporary offline peer (seeded with four collections by
test/scoutro-ui/DashboardFixture.java), then checks sign-in, sessions, CSRF,
the protected access mode, native and old routes, rights changes, locking,
password changes, the last-administrator guard, sign-in limits, the audit log
and that HTTP Digest automation keeps working (docs/SCOUTRO_USERS_ACCESS.md).
The peer is stopped and its DATA removed in a finally block. It never touches
an existing peer or DATA directory and starts no crawl.

Requires a compiled repository and a JDK 17+. Optional: JAVA,
SCOUTRO_SMOKE_PORT, SCOUTRO_KEEP_PEER=1 (leave the peer running for manual
checks; prints its port and DATA directory).
"""
import http.cookiejar
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

REPO = Path(__file__).resolve().parents[2]
JAVA = os.environ.get("JAVA", "java")
CLASSPATH = f"{REPO}/build/classes/java/main:{REPO}/lib/*"
RESULTS = {"pass": 0, "fail": []}


def check(condition, label, detail=""):
    if condition:
        RESULTS["pass"] += 1
    else:
        RESULTS["fail"].append(f"{label} {detail}".strip())
        print("FAIL:", label, detail, flush=True)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Client:
    """One browser-like identity: its own cookie jar, optional Digest credentials."""

    def __init__(self, base, digest=None):
        self.base = base
        self.jar = http.cookiejar.CookieJar()
        handlers = [urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.jar), NoRedirect()]
        if digest:
            manager = urllib.request.HTTPPasswordMgrWithDefaultRealm()
            manager.add_password(None, base, *digest)
            handlers.append(urllib.request.HTTPDigestAuthHandler(manager))
        self.opener = urllib.request.build_opener(*handlers)
        self.csrf = ""

    def call(self, method, path, body=None, headers=None, csrf=True):
        h = dict(headers or {})
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            h.setdefault("Content-Type", "application/json")
        if csrf and self.csrf and method not in ("GET", "HEAD"):
            h.setdefault("X-Scoutro-CSRF", self.csrf)
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            with self.opener.open(request, timeout=60) as response:
                return response.status, self._json(response.read()), dict(response.headers)
        except urllib.error.HTTPError as e:
            return e.code, self._json(e.read()), dict(e.headers)

    @staticmethod
    def _json(raw):
        try:
            return json.loads(raw)
        except ValueError:
            return raw.decode("utf-8", "replace")[:400]

    def login(self, username, password):
        status, body, _ = self.call("POST", "/scoutro/api/v1/auth/login", {"username": username, "password": password})
        if status == 200:
            self.csrf = body.get("csrf", "")
        return status, body

    def code(self, body):
        return body.get("error", {}).get("code") if isinstance(body, dict) else None


def start_peer(root, port):
    config = root / "DATA/SETTINGS/yacy.conf"
    config.parent.mkdir(parents=True)
    (root / ".scoutro-dashboard-disposable").touch()
    (root / "DATA/DICTIONARIES/harvesting").mkdir(parents=True, exist_ok=True)
    for friends in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
        (root / "DATA/DICTIONARIES/harvesting" / friends).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
    config.write_text("\n".join([
        f"port={port}", "adminAccountForLocalhost=false", "adminAccountAllPages=false",
        "adminAccountUserName=admin", "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c",
        "network.unit.definition=defaults/yacy.network.webportal.unit", "browserPopUpTrigger=false",
        "autocrawl=false", "server.https=false", "locale.language=default", "upnp.enabled=false", "donation.iframesource=",
        "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
        "resource.disk.used.max.steadystate=1000000000000", "resource.disk.used.max.overshot=1000000000000",
    ]) + "\n")
    subprocess.run([JAVA, "-cp", CLASSPATH, str(REPO / "test/scoutro-ui/DashboardFixture.java"), str(root)], cwd=REPO,
                   check=True, timeout=120, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    log = (root / "peer.log").open("w")
    process = subprocess.Popen([JAVA, "-Xmx768m", "-Djava.awt.headless=true", "-cp", CLASSPATH, "net.yacy.yacy", "-startup",
                                str(root)], cwd=REPO, stdout=log, stderr=log)
    base = f"http://127.0.0.1:{port}"
    deadline = time.monotonic() + 120
    while True:
        if process.poll() is not None:
            raise RuntimeError((root / "peer.log").read_text()[-4000:])
        try:
            status, _, _ = Client(base).call("GET", "/scoutro/api/v1/auth/session")
            if status == 200:
                return process, base
        except OSError:
            pass
        if time.monotonic() > deadline:
            raise RuntimeError((root / "peer.log").read_text()[-4000:])
        time.sleep(0.5)


def run(base):
    digest = Client(base, digest=("admin", "yacy"))
    anon = Client(base)

    # --- compatible mode: nothing changes for tools, browsers see the login page ---
    check(digest.call("GET", "/scoutro/api/v1/system")[0] == 200, "digest: Scoutro API")
    status, _, headers = anon.call("GET", "/scoutro-dashboard.html", headers={"Accept": "text/html,application/xhtml+xml"})
    check(status == 302 and headers.get("Location", "").startswith(base + "/scoutro-login.html?next=%2Fscoutro-dashboard.html")
          or headers.get("Location", "").startswith("/scoutro-login.html?next="), "browser navigation redirected to login", str(headers.get("Location")))
    status, _, headers = anon.call("GET", "/scoutro-dashboard.html")
    check(status == 401 and "Digest" in headers.get("WWW-Authenticate", ""), "tool request keeps Digest challenge", str(status))
    status, _, headers = anon.call("GET", "/scoutro/api/v1/system", headers={"Accept": "text/html"})
    check(status == 401 and "Digest" in headers.get("WWW-Authenticate", ""), "API never redirected", str(status))
    check(anon.call("GET", "/yacysearch.json?query=test")[0] == 200, "compatible mode: public search unchanged")
    check(anon.call("GET", "/scoutro-login.html")[0] == 200, "login page public")

    admin = Client(base)
    status, body = admin.login("admin", "yacy")
    check(status == 200 and body["user"]["builtin"] and "admin" in body["user"]["permissions"], "built-in admin login", str(body)[:200])
    check(body.get("warning") == "builtin_default_password", "default password warning")
    check(admin.call("GET", "/ConfigAccounts_p.html")[0] == 200, "admin session opens YaCy admin page")
    check(admin.call("GET", "/scoutro/api/v1/system")[0] == 200, "admin session uses Scoutro API")
    status, body, _ = admin.call("POST", "/scoutro/api/v1/users", {}, csrf=False)
    check(status == 403 and admin.code(body) == "csrf_failed", "session POST without CSRF token refused", str(status))
    status, body, _ = admin.call("POST", "/scoutro/api/v1/users", {"username": "x"}, headers={"Sec-Fetch-Site": "cross-site"})
    check(status == 403, "cross-site POST with token refused", str(status))

    status, body, _ = admin.call("POST", "/scoutro/api/v1/users", {"username": "ben", "displayName": "Ben", "role": "administrator",
                                                                  "password": "ben-admin-password", "mustChangePassword": False})
    check(status == 201, "create administrator account", str(body)[:200])
    status, body, _ = admin.call("POST", "/scoutro/api/v1/users", {"username": "anna", "role": "research",
                                                                  "collections": ["stackfinder-web"], "password": "anna-password-1"})
    check(status == 409 and admin.code(body) == "protected_mode_required", "restricted account needs protected mode", str(body)[:200])

    # --- protected mode ---
    status, body, _ = admin.call("PATCH", "/scoutro/api/v1/access", {"protected": True})
    check(status == 200 and body["protected"], "switch to protected mode")
    for user, role, collections in (("anna", "research", ["stackfinder-web"]), ("otto", "operator", ["edelsenior-web", "bauteamcheck-web"])):
        status, body, _ = admin.call("POST", "/scoutro/api/v1/users", {"username": user, "role": role, "collections": collections,
                                                                      "password": user + "-password-1", "mustChangePassword": False})
        check(status == 201 and body["role"] == role, "create " + role, str(body)[:200])

    anna = Client(base)
    status, body = anna.login("anna", "anna-password-1")
    check(status == 200 and body["user"]["role"] == "research" and "admin" not in body["user"]["permissions"], "research login")
    check(anna.call("GET", "/scoutro/api/v1/auth/session")[1].get("kind") == "account", "research session")
    for path in ("/ConfigAccounts_p.html", "/Status.html", "/Crawler_p.html", "/IndexControlURLs_p.html", "/yacysearch.json?query=a",
                 "/solr/select?q=*:*", "/suggest.json?q=a", "/tools/list", "/api/version.xml", "/ViewFile.html", "/yacydoc.html"):
        check(anna.call("GET", path)[0] == 403, "research refused on " + path)
    for path in ("/scoutro/api/v1/system", "/scoutro/api/v1/users", "/scoutro/api/v1/access", "/scoutro/api/v1/config",
                 "/scoutro/api/v1/kg/status"):
        status, body, _ = anna.call("GET", path)
        check(status == 403 and anna.code(body) == "forbidden", "research API refused " + path, str(status))
    status, body, _ = anna.call("POST", "/scoutro/api/v1/crawls", {"url": "https://example.org/", "collection": "stackfinder-web"})
    check(status == 403, "research cannot start crawls", str(status))
    status, body, _ = anna.call("PATCH", "/scoutro/api/v1/users/anna", {"role": "administrator"})
    check(status == 403, "research cannot change its own role", str(status))

    for path in ("/yacysearch.json?query=a", "/yacysearch.html?query=a", "/solr/select?q=*:*", "/solr/collection1/select?q=*:*",
                 "/suggest.json?q=a", "/tools/list", "/v1/chat/completions", "/v1/models", "/api/tags", "/index.html",
                 "/CrawlResults.html", "/yacydoc.html", "/ViewFile.html", "/api/version.xml", "/Status.html"):
        check(anon.call("GET", path)[0] == 401, "anonymous refused on " + path)
    for path in ("/scoutro-login.html", "/env/scoutro/scoutro.css", "/scoutro/api/v1/health", "/scoutro/api/openapi.json",
                 "/scoutro-about.html"):
        check(anon.call("GET", path)[0] == 200, "public in protected mode: " + path)
    check(anon.call("GET", "/scoutro/api/agent/v1/capabilities")[0] == 401, "agent path still needs its token")
    check(digest.call("GET", "/yacysearch.json?query=a")[0] == 200, "digest automation still searches")
    check(digest.call("GET", "/Status.html")[0] == 200, "digest admin pages unchanged")

    # --- rights changes and locking end sessions ---
    status, body, _ = admin.call("PATCH", "/scoutro/api/v1/users/anna", {"collections": ["stackfinder-web", "edelsenior-web"]})
    check(status == 200 and body["sessionsEnded"] >= 1, "scope change ends sessions", str(body)[:200])
    check(anna.call("GET", "/scoutro/api/v1/auth/session")[1].get("authenticated") is False, "old session gone after rights change")
    anna.login("anna", "anna-password-1")
    status, body, _ = admin.call("PATCH", "/scoutro/api/v1/users/anna", {"status": "locked"})
    check(status == 200 and body["status"] == "locked", "lock account")
    check(anna.call("GET", "/scoutro/api/v1/auth/session")[1].get("authenticated") is False, "locked session gone")
    status, body = anna.login("anna", "anna-password-1")
    check(status == 403 and anna.code(body) == "account_locked", "locked account cannot sign in")
    status, body = anna.login("anna", "wrong-password-x")
    check(status == 401 and anna.code(body) == "invalid_credentials", "locked account not revealed by wrong password")
    admin.call("PATCH", "/scoutro/api/v1/users/anna", {"status": "active"})

    # --- password reset and own change ---
    status, body, _ = admin.call("POST", "/scoutro/api/v1/users/anna/password", {"password": "temporary-pass-1"})
    check(status == 200 and body["user"]["mustChangePassword"], "admin password reset")
    status, body = anna.login("anna", "temporary-pass-1")
    check(status == 200 and body["user"]["mustChangePassword"] and body["user"]["permissions"] == [], "must change password first")
    status, body, _ = anna.call("GET", "/scoutro/api/v1/index/browse")
    check(status == 403, "no data before the password change", str(status))
    old_cookie = [c.value for c in anna.jar if c.name == "scoutro_session"]
    status, body, _ = anna.call("POST", "/scoutro/api/v1/auth/password", {"currentPassword": "temporary-pass-1", "newPassword": "anna-own-password-2"})
    check(status == 200 and not body["user"]["mustChangePassword"], "own password change", str(body)[:200])
    anna.csrf = body.get("csrf", anna.csrf)
    replay = Client(base)
    replay.jar.set_cookie(http.cookiejar.Cookie(0, "scoutro_session", old_cookie[0] if old_cookie else "x", None, False, "127.0.0.1",
                                                False, False, "/", True, False, None, False, None, None, {}))
    check(replay.call("GET", "/scoutro/api/v1/auth/session")[1].get("authenticated") is False, "old session cookie dead after password change")
    check(anna.call("GET", "/scoutro/api/v1/auth/session")[1].get("authenticated") is True, "renewed session works")

    # --- last administrator and mode guards ---
    status, body, _ = admin.call("PATCH", "/scoutro/api/v1/access", {"protected": False})
    check(status == 409 and admin.code(body) == "restricted_accounts_active", "cannot leave protected mode with active restricted accounts")
    ben = Client(base)
    ben.login("ben", "ben-admin-password")
    status, body, _ = ben.call("PATCH", "/scoutro/api/v1/access", {"builtinAdminLogin": False})
    check(status == 200 and body["builtinAdminLogin"] is False, "switch off built-in form sign-in")
    check(admin.call("GET", "/scoutro/api/v1/auth/session")[1].get("authenticated") is False, "built-in session ended")
    check(digest.call("GET", "/scoutro/api/v1/users")[0] == 200, "digest administrator unaffected")
    status, body, _ = ben.call("PATCH", "/scoutro/api/v1/users/ben", {"status": "locked"})
    check(status == 409 and ben.code(body) in ("own_account", "last_admin"), "own/last administrator not lockable", str(body)[:200])
    status, body, _ = digest.call("DELETE", "/scoutro/api/v1/users/ben", {})
    check(status == 409 and digest.code(body) == "last_admin", "last administrator not removable", str(body)[:200])
    status, body, _ = ben.call("PATCH", "/scoutro/api/v1/access", {"builtinAdminLogin": True})
    check(status == 200, "switch built-in form sign-in on again")

    # --- logout, limits, audit ---
    status, body, _ = ben.call("POST", "/scoutro/api/v1/auth/logout", {})
    check(status == 200 and body.get("ok"), "logout")
    check(ben.call("GET", "/scoutro/api/v1/auth/session")[1].get("authenticated") is False, "session ended by logout")
    last = None
    for i in range(6):
        last = Client(base).login("otto", "wrong-password-%d" % i)
    status, body = last
    check(status == 429, "sign-in limit", str(status))
    status, body, headers = Client(base).call("POST", "/scoutro/api/v1/auth/login", {"username": "otto", "password": "otto-password-1"})
    check(status == 429 and headers.get("Retry-After", "").isdigit(), "limit also for the right password, with Retry-After")
    status, body, _ = digest.call("GET", "/scoutro/api/v1/access/audit?limit=200")
    entries = body.get("entries", []) if isinstance(body, dict) else []
    text = json.dumps(entries)
    actions = {e["action"] for e in entries}
    check({"auth.login", "user.created", "user.updated", "user.locked", "user.unlocked", "user.password.reset",
           "auth.password.changed", "access.changed", "auth.logout"} <= actions, "audit actions", str(sorted(actions)))
    check("anna-password-1" not in text and "temporary-pass-1" not in text and "ben-admin-password" not in text, "audit without passwords")
    check(anon.call("GET", "/scoutro/api/v1/health")[0] == 200, "internal loopback calls work in protected mode")


def main():
    port = int(os.environ.get("SCOUTRO_SMOKE_PORT", "0"))
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", port))
        port = probe.getsockname()[1]
    root = Path(tempfile.mkdtemp(prefix="scoutro-access-"))
    process = None
    keep = os.environ.get("SCOUTRO_KEEP_PEER") == "1"
    try:
        process, base = start_peer(root, port)
        run(base)
    finally:
        if keep and process is not None:
            print(f"peer kept: {base} DATA {root} pid {process.pid}", flush=True)
        else:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            shutil.rmtree(root, ignore_errors=True)
    print(f"{RESULTS['pass']} passed, {len(RESULTS['fail'])} failed", flush=True)
    return 1 if RESULTS["fail"] else 0


if __name__ == "__main__":
    sys.exit(main())
