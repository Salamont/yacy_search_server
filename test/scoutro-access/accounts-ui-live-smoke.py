#!/usr/bin/env python3
"""Disposable multi-user browser check for Scoutro accounts. GPL-2.0-or-later.

Starts a NEW temporary offline peer like access-live-smoke.py (four seeded
collections, German translation by browser language), switches on the
protected access mode, creates a Research, an Operator and an Administrator
account with passwords generated for this run, releases one collection for
guests and runs test/scoutro-ui/accounts-ui-test.mjs in Chromium (English and
German, 1280 and 390 px): sign-in with return target, navigation per role,
collection context, Scoutro search, knowledge page, 403 page, guests and
sign-out. The peer is stopped and its DATA removed afterwards; nothing is
crawled. Optional: SCOUTRO_SCREENSHOTS (directory), NODE_PATH and
SCOUTRO_CHROMIUM_PATH as for the other UI tests, SCOUTRO_KEEP_PEER=1.
"""
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile

REPO = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location("access_smoke", Path(__file__).with_name("access-live-smoke.py"))
smoke = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(smoke)


def password():
    return "t-" + secrets.token_urlsafe(18)


def prepare(base):
    admin = smoke.Client(base, digest=("admin", "yacy"))
    status, body, _ = admin.call("PATCH", "/scoutro/api/v1/access", {"protected": True})
    smoke.check(status == 200, "protected mode", str(body)[:200])
    users = {}
    for role, name, display, collections, export in (
            ("administrator", "ben", "Ben Berger", None, False),
            ("research", "anna", "Anna Albers", ["stackfinder-web"], False),
            ("operator", "otto", "Otto Olsen", ["edelsenior-web", "bauteamcheck-web"], True)):
        secret = password()
        spec = {"username": name, "displayName": display, "role": role, "password": secret, "mustChangePassword": False, "export": export}
        if collections:
            spec["collections"] = collections
        status, body, _ = admin.call("POST", "/scoutro/api/v1/users", spec)
        smoke.check(status == 201, "create " + role, str(body)[:200])
        users[role] = {"username": name, "password": secret, "displayName": display}
    status, body, _ = admin.call("PATCH", "/scoutro/api/v1/access", {"guest": True, "guestCollections": ["stackfinder-web"]})
    smoke.check(status == 200, "guest access with one released collection", str(body)[:200])
    return users


def main():
    port = int(os.environ.get("SCOUTRO_SMOKE_PORT", "0"))
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", port))
        port = probe.getsockname()[1]
    root = Path(tempfile.mkdtemp(prefix="scoutro-accounts-"))
    process = None
    keep = os.environ.get("SCOUTRO_KEEP_PEER") == "1"
    code = 1
    try:
        process, base = smoke.start_peer(root, port, extra=["locale.language=browser"], languages=["de"])
        users = prepare(base)
        env = dict(os.environ, SCOUTRO_URL=base, SCOUTRO_TEST_USERS=json.dumps(users), SCOUTRO_GUEST="1")
        result = subprocess.run(["node", str(REPO / "test/scoutro-ui/accounts-ui-test.mjs")], cwd=REPO, env=env)
        code = result.returncode
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
    print(f"setup: {smoke.RESULTS['pass']} passed, {len(smoke.RESULTS['fail'])} failed", flush=True)
    return 1 if smoke.RESULTS["fail"] or code else 0


if __name__ == "__main__":
    sys.exit(main())
