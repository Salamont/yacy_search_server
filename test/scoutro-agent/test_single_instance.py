#!/usr/bin/env python3
"""One research worker per Clustro connection, with real processes.

Copyright (C) 2026 Scoutro contributors
Scoutro is an independent community project based on YaCy.
Licensed under the GNU General Public License, version 2 or (at your option)
any later version.

Every worker process gets its own fake Scoutro, so it is visible which
process did any work. All processes share one fake Clustro and one lock
directory.

    python3 test/scoutro-agent/test_single_instance.py -v
"""

import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_bridge import BRIDGE, KEY, TOKEN, FakeClustro, FakeScoutro, bridge  # noqa: E402


class SingleInstanceTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.locks = os.path.join(self.tmp, "locks")
        self.clustro = FakeClustro()
        self.servers = [self.clustro.server]
        self.procs = []

    def tearDown(self):
        for p in self.procs:
            if p.poll() is None:
                p.kill()
                p.wait()
        for s in self.servers:
            s.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def secret(self, name, connection="conn1", base=None):
        path = os.path.join(self.tmp, name + ".secret")
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"scoutroToken": TOKEN, "clustroBaseUrl": base or self.clustro.server.url,
                       "clustroWorkspaceId": "ws1", "clustroConnectionId": connection, "clustroAgentKey": KEY}, f)
        return path

    def start(self, secret, name, once=False):
        scoutro = FakeScoutro()
        scoutro.results["edelsenior-web"] = [("https://a.example/", "A", "Pflege")]
        self.servers.append(scoutro.server)
        log = open(os.path.join(self.tmp, name + ".log"), "w", encoding="utf-8")
        env = dict(os.environ, SCOUTRO_AGENT_URL=scoutro.server.url, SCOUTRO_AGENT_LOCK_DIR=self.locks,
                   SCOUTRO_AGENT_STATE_DIR=os.path.join(self.tmp, name + ".state"), SCOUTRO_AGENT_POLL="0.5")
        cmd = [sys.executable, BRIDGE, "--secret-file", secret] + (["--once"] if once else [])
        p = subprocess.Popen(cmd, env=env, stdout=subprocess.DEVNULL, stderr=log)
        self.procs.append(p)
        return p, scoutro, os.path.join(self.tmp, name + ".log")

    @staticmethod
    def wait_for(predicate, timeout=20):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if predicate():
                return True
            time.sleep(0.1)
        return False

    @staticmethod
    def text(path):
        with open(path, encoding="utf-8") as f:
            return f.read()

    def test_second_process_for_the_same_connection_exits_before_any_work(self):
        a, sa, la = self.start(self.secret("a"), "a")
        self.assertTrue(self.wait_for(lambda: "worker_started" in self.text(la)), self.text(la))
        # another secret file, another Scoutro, spelling variant of the same Clustro URL
        variant = self.clustro.server.url.replace("http://", "HTTP://") + "/"
        b, sb, lb = self.start(self.secret("b", base=variant), "b")
        self.assertEqual(b.wait(timeout=20), 3)
        self.assertIn("already serves the Clustro connection", self.text(lb))
        self.assertNotIn("worker_started", self.text(lb))
        self.assertEqual(sb.calls, [], "the refused process must not call Scoutro")
        self.assertIsNone(a.poll(), "the first worker keeps running")

    def test_only_one_of_two_simultaneous_processes_works(self):
        self.clustro.add("run1", "Pflege")
        secret_a, secret_b = self.secret("a"), self.secret("b")
        a, sa, la = self.start(secret_a, "a", once=True)
        b, sb, lb = self.start(secret_b, "b", once=True)
        codes = sorted([a.wait(timeout=60), b.wait(timeout=60)])
        self.assertEqual(codes, [0, 3])
        self.assertEqual(len(self.clustro.tools("complete_run")), 1)
        self.assertEqual(self.clustro.runs["run1"]["status"], "completed")
        worked = [s for s in (sa, sb) if s.calls]
        self.assertEqual(len(worked), 1, "exactly one process may have called Scoutro")

    def test_independent_connection_runs_in_parallel(self):
        a, _, la = self.start(self.secret("a", connection="conn1"), "a")
        self.assertTrue(self.wait_for(lambda: "worker_started" in self.text(la)))
        c, sc_, lc = self.start(self.secret("c", connection="conn2"), "c")
        self.assertTrue(self.wait_for(lambda: "worker_started" in self.text(lc)), self.text(lc))
        self.assertTrue(self.wait_for(lambda: sc_.count("/capabilities") > 0))
        self.assertIsNone(a.poll())
        self.assertIsNone(c.poll())

    def test_normal_termination_releases_the_lock(self):
        secret = self.secret("a")
        a, _, la = self.start(secret, "a")
        self.assertTrue(self.wait_for(lambda: "worker_started" in self.text(la)))
        a.send_signal(signal.SIGTERM)
        self.assertEqual(a.wait(timeout=20), 0)
        b, sb, lb = self.start(secret, "b")
        self.assertTrue(self.wait_for(lambda: "worker_started" in self.text(lb)), self.text(lb))
        self.assertTrue(self.wait_for(lambda: sb.count("/capabilities") > 0))

    def test_crash_releases_the_lock(self):
        secret = self.secret("a")
        a, _, la = self.start(secret, "a")
        self.assertTrue(self.wait_for(lambda: "worker_started" in self.text(la)))
        a.kill()                       # SIGKILL: no cleanup code runs, the lock file stays
        a.wait(timeout=20)
        self.assertTrue(os.listdir(self.locks), "the lock file itself is left behind")
        b, sb, lb = self.start(secret, "b")
        self.assertTrue(self.wait_for(lambda: "worker_started" in self.text(lb)), self.text(lb))
        self.assertTrue(self.wait_for(lambda: sb.count("/capabilities") > 0))

    def test_unsafe_lock_directory_is_refused(self):
        os.makedirs(self.locks)
        os.chmod(self.locks, 0o777)
        b, sb, lb = self.start(self.secret("a"), "a", once=True)
        self.assertEqual(b.wait(timeout=20), 3)
        self.assertIn("must not be writable by others", self.text(lb))
        self.assertEqual(sb.calls, [])

    def test_identity_normalization(self):
        same = bridge.connection_identity("https://Clustro.example:443/", "ws", "c")
        self.assertEqual(same, bridge.connection_identity("https://clustro.example", "ws", "c"))
        self.assertNotEqual(same, bridge.connection_identity("http://clustro.example", "ws", "c"))
        self.assertNotEqual(same, bridge.connection_identity("https://clustro.example:8443", "ws", "c"))
        self.assertNotEqual(same, bridge.connection_identity("https://clustro.example", "ws", "c2"))
        self.assertNotEqual(same, bridge.connection_identity("https://clustro.example", "ws2", "c"))


if __name__ == "__main__":
    unittest.main()
