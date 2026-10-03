#!/usr/bin/env python3
"""Robots redirects use fake HTTP and DNS only; never open a real socket."""
import importlib.machinery
import importlib.util
import io
import os
import socket
import sys
import unittest
import urllib.request
from email.message import Message
from unittest.mock import patch
from urllib.response import addinfourl

TOOLS = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../tools/scoutro"))
sys.path.insert(0, TOOLS)
loader = importlib.machinery.SourceFileLoader("robots_security_discovery", os.path.join(TOOLS, "scoutro-discovery"))
spec = importlib.util.spec_from_loader(loader.name, loader)
disc = importlib.util.module_from_spec(spec)
loader.exec_module(disc)

ORIGIN = "crawl-fixture.test"
START = "https://" + ORIGIN + "/robots.txt"
BODY = b"User-agent: *\nAllow: /\n"


class RobotsRedirectSecurityTest(unittest.TestCase):
    def setUp(self):
        self.visits = []
        self.dns = []
        self.replies = {}
        self.ips = {ORIGIN: ["8.8.8.8"], "next-fixture.test": ["8.8.4.4"],
                    "xn--bcher-kva.test": ["8.8.4.4"]}
        case = self

        class FakeHTTP(urllib.request.HTTPHandler):
            def http_open(self, request):
                return case.reply(request)

        class FakeHTTPS(urllib.request.HTTPSHandler):
            def https_open(self, request):
                return case.reply(request)

        factory = urllib.request.build_opener
        def fake_opener(*handlers):
            return factory(urllib.request.ProxyHandler({}), FakeHTTP(), FakeHTTPS(), *handlers)

        self.patches = [
            patch("urllib.request.build_opener", side_effect=fake_opener),
            patch("socket.getaddrinfo", side_effect=self.resolve),
            patch("socket.create_connection", side_effect=AssertionError("real connection forbidden")),
            patch("socket.socket", side_effect=AssertionError("real socket forbidden")),
        ]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)

    def resolve(self, host, *_args, **_kwargs):
        self.dns.append(host)
        if host in self.ips:
            values = self.ips[host]
        else:
            try:
                values = [str(disc.ipaddress.ip_address(host))]
            except ValueError:
                raise AssertionError("unexpected DNS target: " + host)
        return [(socket.AF_INET6 if ":" in ip else socket.AF_INET, socket.SOCK_STREAM,
                 socket.IPPROTO_TCP, "", (ip, 0)) for ip in values]

    def reply(self, request):
        url = request.full_url
        self.visits.append(url)
        if url not in self.replies:
            raise AssertionError("unvalidated/unexpected HTTP target: " + url)
        code, location, body = self.replies[url]
        headers = Message()
        if location:
            headers["Location"] = location
        response = addinfourl(io.BytesIO(body), headers, url, code)
        response.msg = "Found" if location else "OK"
        return response

    def redirect(self, target, code=302):
        self.replies[START] = (code, target, b"")

    def assertBlocked(self, target, reason="robots-target-blocked"):
        self.redirect(target)
        self.assertEqual(disc.robots_allows(ORIGIN), (False, reason))
        self.assertEqual(self.visits, [START])

    def test_normal_robots_allowed(self):
        self.replies[START] = (200, None, BODY)
        self.assertEqual(disc.robots_allows(ORIGIN), (True, "robots-allow"))
        self.assertEqual(self.visits, [START])

    def test_public_redirect_codes_allowed(self):
        target = "https://next-fixture.test/policy.txt"
        for code in (301, 302, 303, 307, 308):
            with self.subTest(code=code):
                self.visits.clear()
                self.redirect(target, code)
                self.replies[target] = (200, None, BODY)
                self.assertTrue(disc.robots_allows(ORIGIN)[0])
                self.assertEqual(self.visits, [START, target])
        self.assertIn("next-fixture.test", self.dns)

    def test_ipv4_loopback_blocked(self):
        self.assertBlocked("http://127.0.0.1/internal")

    def test_private_ipv4_blocked(self):
        self.assertBlocked("http://10.0.0.10/internal")

    def test_link_local_blocked(self):
        self.assertBlocked("http://169.254.169.254/internal")

    def test_ipv6_loopback_blocked(self):
        self.assertBlocked("http://[::1]/internal")

    def test_localhost_blocked(self):
        self.assertBlocked("http://localhost/internal")

    def test_private_hostname_blocked(self):
        self.ips["next-fixture.test"] = ["10.0.0.10"]
        self.assertBlocked("http://next-fixture.test/internal")

    def test_mixed_public_private_dns_blocked(self):
        self.ips["next-fixture.test"] = ["8.8.4.4", "10.0.0.10"]
        self.assertBlocked("http://next-fixture.test/internal")

    def test_empty_dns_answer_blocked(self):
        self.ips["next-fixture.test"] = []
        self.assertBlocked("http://next-fixture.test/internal")

    def test_public_ipv6_redirect_allowed(self):
        self.ips["next-fixture.test"] = ["2001:4860:4860::8888"]
        target = "https://next-fixture.test/policy.txt"
        self.redirect(target)
        self.replies[target] = (200, None, BODY)
        self.assertTrue(disc.robots_allows(ORIGIN)[0])
        self.assertEqual(self.visits, [START, target])

    def test_same_host_redirect_resolves_again(self):
        self.redirect("/policy")
        original = self.reply
        def reply(request):
            response = original(request)
            self.ips[ORIGIN] = ["10.0.0.10"]
            return response
        with patch.object(self, "reply", side_effect=reply):
            self.assertEqual(disc.robots_allows(ORIGIN), (False, "robots-target-blocked"))
        self.assertEqual(self.visits, [START])
        self.assertEqual(self.dns, [ORIGIN, ORIGIN])

    def test_special_use_addresses_blocked(self):
        for ip in ("0.0.0.0", "224.0.0.1", "100.64.0.1", "198.18.0.1", "192.0.2.1", "::", "fd00::1"):
            with self.subTest(ip=ip):
                self.visits.clear()
                host = "[" + ip + "]" if ":" in ip else ip
                self.assertBlocked("http://" + host + "/internal")

    def test_late_private_redirect_blocked(self):
        middle = "https://next-fixture.test/policy"
        self.redirect(middle)
        self.replies[middle] = (302, "http://127.0.0.1/internal", b"")
        self.assertEqual(disc.robots_allows(ORIGIN), (False, "robots-target-blocked"))
        self.assertEqual(self.visits, [START, middle])

    def test_loop_bounded(self):
        self.redirect(START)
        self.assertEqual(disc.robots_allows(ORIGIN), (False, "robots-redirect-limit"))
        self.assertEqual(len(self.visits), disc.MAX_ROBOTS_REDIRECTS + 1)

    def test_long_chain_bounded(self):
        for number in range(disc.MAX_ROBOTS_REDIRECTS + 1):
            source = START if number == 0 else f"https://next-fixture.test/{number}"
            self.replies[source] = (302, f"https://next-fixture.test/{number + 1}", b"")
        self.assertEqual(disc.robots_allows(ORIGIN), (False, "robots-redirect-limit"))
        self.assertEqual(len(self.visits), disc.MAX_ROBOTS_REDIRECTS + 1)

    def test_scheme_and_credentials_refused(self):
        for target in ("ftp://next-fixture.test/robots.txt", "file:///etc/passwd",
                       "https://user:secret@next-fixture.test/policy"):
            with self.subTest(target=target):
                self.visits.clear()
                self.assertBlocked(target, "robots-target-invalid")

    def test_relative_redirect_and_idn_normalized(self):
        self.redirect("/other")
        self.replies["https://" + ORIGIN + "/other"] = (200, None, BODY)
        self.assertTrue(disc.robots_allows(ORIGIN)[0])
        self.visits.clear()
        self.redirect("https://BÜCHER.test/policy#part")
        self.replies["https://xn--bcher-kva.test/policy"] = (200, None, BODY)
        self.assertTrue(disc.robots_allows(ORIGIN)[0])
        self.assertEqual(self.visits[-1], "https://xn--bcher-kva.test/policy")

    def test_initial_private_target_never_fetched(self):
        self.assertEqual(disc.robots_allows("10.0.0.10"), (False, "robots-target-blocked"))
        self.assertEqual(self.visits, [])

    def test_disallow_and_transient_http_policy_retained(self):
        self.replies[START] = (200, None, b"User-agent: *\nDisallow: /\n")
        self.assertEqual(disc.robots_allows(ORIGIN), (False, "robots-disallow-all"))
        self.replies[START] = (503, None, b"")
        self.replies["http://" + ORIGIN + "/robots.txt"] = (503, None, b"")
        self.assertEqual(disc.robots_allows(ORIGIN), (None, "site-5xx:503"))


if __name__ == "__main__":
    unittest.main()
