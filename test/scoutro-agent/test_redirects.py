#!/usr/bin/env python3
"""Credentials never follow redirects (research worker and model clients).

Copyright (C) 2026 Scoutro contributors
Scoutro is an independent community project based on YaCy.
Licensed under the GNU General Public License, version 2 or (at your option)
any later version.

Two local origins: A answers every request with a redirect to B; B records
whatever reaches it. No client may send anything to B, so no Scoutro token,
Clustro key or model key can leave its configured origin. Only artificial
markers are used as credentials.

    python3 test/scoutro-agent/test_redirects.py -v
"""

import os
import sys
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_bridge import Server, bridge, sc  # noqa: E402

TOKEN = "sca_" + "m" * 16 + "." + "M" * 43           # artificial markers only
KEY = "ak_" + "9" * 64
MODEL_KEY = "model-key-marker-0000"
CODES = (301, 302, 303, 307, 308)


class RedirectTest(unittest.TestCase):

    def setUp(self):
        self.received_b = []
        self.received_a = []
        self.b = Server(self.record_b)
        self.code = 302
        self.a = Server(self.redirect)

    def tearDown(self):
        self.a.close()
        self.b.close()

    def record_b(self, method, path, query, headers, body):
        self.received_b.append((method, path, dict(headers)))
        return 200, {"Content-Type": "application/json"}, '{"ok": true}'

    def redirect(self, method, path, query, headers, body):
        self.received_a.append((method, path, headers.get("Authorization")))
        return self.code, {"Location": self.b.url + path}, ""

    def assert_nothing_reached_b(self):
        self.assertEqual(self.received_b, [], "a credential-bearing request followed the redirect")
        self.assertTrue(self.received_a, "the first origin must have been asked")

    def test_scoutro_agent_api(self):
        for code in CODES:
            self.code = code
            client = bridge.Scoutro(self.a.url, TOKEN)
            for method, path, body in (("GET", "/capabilities", None), ("POST", "/crawls", {"url": "https://x.example/"}),
                                       ("POST", "/heartbeat", {})):
                with self.assertRaises(bridge.RemoteError) as ctx:
                    client.call(method, path, body=body)
                self.assertEqual(ctx.exception.code, "redirect_refused", (code, path))
                self.assertEqual(ctx.exception.status, code)
        self.assert_nothing_reached_b()
        self.assertEqual({a[2] for a in self.received_a}, {"Bearer " + TOKEN})

    def test_clustro_mcp_and_webhook(self):
        for code in CODES:
            self.code = code
            client = bridge.Clustro(self.a.url, KEY, "ws", "conn")
            with self.assertRaises(bridge.RemoteError) as ctx:
                client.runs("created")
            self.assertEqual(ctx.exception.code, "redirect_refused")
            with self.assertRaises(bridge.RemoteError):
                client.webhook("run_started", "run1")
            self.assertEqual(client.last_ok, 0, "a redirect is not a successful Clustro call")
        self.assert_nothing_reached_b()

    def test_model_summary_client(self):
        for code in CODES:
            self.code = code
            cfg = sc.LlmConfig(base_url=self.a.url, model="m", api_key=MODEL_KEY, retries=2, timeout=5)
            with self.assertRaises(sc.LlmError) as ctx:
                bridge.chat_json(cfg, [{"role": "user", "content": "x"}])
            self.assertIn("redirect", str(ctx.exception))
        self.assert_nothing_reached_b()
        self.assertEqual(len(self.received_a), len(CODES), "redirects are not retried")

    def test_shared_classifier_client(self):
        for code in CODES:
            self.code = code
            cfg = sc.LlmConfig(base_url=self.a.url, model="m", api_key=MODEL_KEY, retries=2, timeout=5)
            with self.assertRaises(sc.LlmError) as ctx:
                sc.call_llm(cfg, [{"role": "user", "content": "x"}])
            self.assertEqual(ctx.exception.kind, "llm_http_error")
            self.assertIn("redirect", str(ctx.exception))
            # the classifier turns it into a fail-safe UNSURE record, never a follow-up request
            clf = sc.Classifier(sc.load_profile_rules(os.path.join(os.path.dirname(sc.__file__), "discovery",
                                                                     "profiles.json")), backend="llm", llm=cfg)
            rec = clf.classify("edelsenior", "x.example", [{"url": "https://x.example/", "title": "Pflegeheim",
                                                            "snippet": "Pflegeheim und Kurzzeitpflege"}])
            self.assertEqual(rec["verdict"], "UNSURE")
        self.assert_nothing_reached_b()

    def test_https_downgrade_is_refused(self):
        handler = sc.NoRedirectHandler()
        for code in CODES:
            req = urllib.request.Request("https://model.example/v1/chat/completions",
                                         headers={"Authorization": "Bearer " + MODEL_KEY})
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                handler.redirect_request(req, None, code, "Found", {}, "http://model.example/v1/chat/completions")
            self.assertEqual(ctx.exception.code, code)
            # same-origin redirects are refused as well: there is no allowlist to get wrong
            with self.assertRaises(urllib.error.HTTPError):
                handler.redirect_request(req, None, code, "Found", {}, "https://model.example/other")

    def test_openers_have_no_default_redirect_handler(self):
        handlers = sc.no_redirect_opener().handlers
        redirect = [h for h in handlers if isinstance(h, urllib.request.HTTPRedirectHandler)]
        self.assertEqual(len(redirect), 1)
        self.assertIsInstance(redirect[0], sc.NoRedirectHandler)


if __name__ == "__main__":
    unittest.main()
