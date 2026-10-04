#!/usr/bin/env python3
"""Real urllib/http.client dial selection, with fake DNS, sockets and TLS only."""
import importlib.machinery
import importlib.util
import io
import socket
import ssl
import sys
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[2] / 'tools/scoutro'
sys.path.insert(0, str(TOOLS))
loader = importlib.machinery.SourceFileLoader('dns_gap_discovery', str(TOOLS / 'scoutro-discovery'))
spec = importlib.util.spec_from_loader(loader.name, loader)
discovery = importlib.util.module_from_spec(spec)
loader.exec_module(discovery)
HOST = 'rebinding-fixture.test'
URL = 'http://' + HOST + '/robots.txt'
BODY = b'User-agent: *\nAllow: /\n'


def answers(*ips):
    return [(socket.AF_INET6 if ':' in ip else socket.AF_INET, socket.SOCK_STREAM,
             socket.IPPROTO_TCP, '', (ip, 0)) for ip in ips]


def response(code=200, location=None):
    headers = b'HTTP/1.1 ' + str(code).encode() + b' Fake\r\nContent-Length: ' + str(len(BODY)).encode()
    if location:
        headers += b'\r\nLocation: ' + location.encode()
    return headers + b'\r\nConnection: keep-alive\r\n\r\n' + BODY


class PinnedRobotsDialTest(unittest.TestCase):
    def setUp(self):
        self.attempts, self.sockets, self.requests = [], [], []
        self.replies = [response()]
        self.failures = set()
        self.peer_override = None
        case = self

        class FakeSocket:
            def __init__(self, *args, **kwargs):
                self.closed = False
                case.sockets.append(self)
            def settimeout(self, *args):
                pass
            def connect(self, address):
                case.attempts.append(address)
                if address[0] in case.failures:
                    raise OSError('fake unreachable')
                self.peer = address
            def getpeername(self):
                return case.peer_override or self.peer
            def sendall(self, data):
                case.requests.append(data)
            def makefile(self, *args):
                return io.BytesIO(case.replies.pop(0))
            def close(self):
                self.closed = True

        for item in (patch('socket.socket', FakeSocket),
                     patch('socket.create_connection', side_effect=AssertionError('second resolver forbidden')),
                     patch('urllib.request.getproxies', return_value={})):
            item.start()
            self.addCleanup(item.stop)

    def read(self, url=URL):
        with discovery.robots_opener().open(url, timeout=1) as result:
            return result.read()

    def test_rebinding_does_not_change_dial_and_preserves_host(self):
        for later in ('127.0.0.1', '10.0.0.1', '169.254.169.254', '8.8.4.4'):
            with self.subTest(later=later):
                self.attempts.clear()
                self.replies = [response()]
                with patch('socket.getaddrinfo', side_effect=[answers('8.8.8.8'), answers(later)]) as dns:
                    self.assertEqual(self.read(), BODY)
                    self.assertEqual(dns.call_count, 1)
                self.assertEqual(self.attempts, [('8.8.8.8', 80)])
                self.assertIn(('Host: ' + HOST + '\r\n').encode(), self.requests[-1])
                self.assertIn(b'Connection: close\r\n', self.requests[-1])

    def test_public_ipv6_literal_and_port(self):
        with patch('socket.getaddrinfo', return_value=answers('2001:4860:4860::8888')):
            self.assertEqual(self.read('http://[2001:4860:4860::8888]:8081/robots.txt'), BODY)
        self.assertEqual(self.attempts, [('2001:4860:4860::8888', 8081, 0, 0)])
        self.assertIn(b'Host: [2001:4860:4860::8888]:8081\r\n', self.requests[-1])

    def test_fallback_uses_only_validated_ipv4_ipv6_snapshot(self):
        self.failures.add('2001:4860:4860::8888')
        with patch('socket.getaddrinfo', side_effect=[answers('2001:4860:4860::8888', '8.8.8.8'),
                                                    answers('127.0.0.1')]) as dns:
            self.assertEqual(self.read(), BODY)
            self.assertEqual(dns.call_count, 1)
        self.assertEqual(self.attempts, [('2001:4860:4860::8888', 80, 0, 0), ('8.8.8.8', 80)])
        self.assertTrue(self.sockets[0].closed)

    def test_mixed_dns_and_dns_failure_never_connect(self):
        for value in (answers('8.8.8.8', '::1'), answers('2001:4860:4860::8888%lo'),
                      [], socket.gaierror('fake unavailable')):
            with patch('socket.getaddrinfo', side_effect=value if isinstance(value, Exception) else None,
                       return_value=value):
                with self.assertRaises(discovery.RobotsTargetError):
                    self.read()
        self.assertEqual(self.attempts, [])

    def test_redirect_revalidates_and_does_not_reuse_connection(self):
        self.replies = [response(302, '/policy'), response()]
        with patch('socket.getaddrinfo', side_effect=[answers('8.8.8.8'), answers('8.8.4.4')]) as dns:
            self.assertEqual(self.read(), BODY)
            self.assertEqual(dns.call_count, 2)
        self.assertEqual(self.attempts, [('8.8.8.8', 80), ('8.8.4.4', 80)])
        self.assertEqual(len(self.sockets), 2)
        self.assertTrue(all(item.closed for item in self.sockets))

    def test_redirect_to_rebound_private_address_stops_before_dial(self):
        self.replies = [response(302, '/policy')]
        with patch('socket.getaddrinfo', side_effect=[answers('8.8.8.8'), answers('127.0.0.1')]):
            with self.assertRaises(discovery.RobotsTargetError):
                self.read()
        self.assertEqual(self.attempts, [('8.8.8.8', 80)])

    def test_new_request_has_fresh_validation_without_pool_reuse(self):
        opener = discovery.robots_opener()
        with patch('socket.getaddrinfo', side_effect=[answers('8.8.8.8'), answers('127.0.0.1')]):
            with opener.open(URL) as result:
                self.assertEqual(result.read(), BODY)
            with self.assertRaises(discovery.RobotsTargetError):
                opener.open(URL)
        self.assertEqual(self.attempts, [('8.8.8.8', 80)])

    def test_proxy_refused_without_direct_bypass(self):
        with patch('urllib.request.getproxies', return_value={'http': 'http://proxy-fixture.test:8080'}), \
                patch('urllib.request.proxy_bypass', return_value=False), \
                patch('socket.getaddrinfo', return_value=answers('8.8.8.8')):
            with self.assertRaisesRegex(discovery.RobotsTargetError, 'robots-proxy-unsupported'):
                self.read()
        self.assertEqual(self.attempts, [])

    def test_proxy_bypass_still_uses_pinned_connector(self):
        with patch('urllib.request.getproxies', return_value={'http': 'http://proxy-fixture.test:8080'}), \
                patch('urllib.request.proxy_bypass', return_value=True), \
                patch('socket.getaddrinfo', return_value=answers('8.8.8.8')):
            self.assertEqual(self.read(), BODY)
        self.assertEqual(self.attempts, [('8.8.8.8', 80)])

    def test_connected_peer_must_equal_validated_address(self):
        self.peer_override = ('127.0.0.1', 80)
        with patch('socket.getaddrinfo', return_value=answers('8.8.8.8')):
            with self.assertRaisesRegex(discovery.RobotsTargetError, 'robots-peer-mismatch'):
                self.read()
        self.assertTrue(self.sockets[-1].closed)
        self.assertEqual(self.requests, [])

    def test_https_uses_original_sni_and_verified_context(self):
        context = ssl.create_default_context()
        self.assertTrue(context.check_hostname)
        self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
        with patch('ssl.create_default_context', return_value=context), \
                patch.object(context, 'wrap_socket', side_effect=lambda sock, **kw: sock) as wrap, \
                patch('socket.getaddrinfo', return_value=answers('8.8.8.8')):
            self.assertEqual(self.read('https://' + HOST + ':8443/robots.txt'), BODY)
        self.assertEqual(wrap.call_args.kwargs, {'server_hostname': HOST})
        self.assertEqual(self.attempts, [('8.8.8.8', 8443)])
        self.assertIn(('Host: ' + HOST + ':8443\r\n').encode(), self.requests[-1])

    def test_certificate_failure_closes_socket_without_http_send(self):
        context = ssl.create_default_context()
        with patch('ssl.create_default_context', return_value=context), \
                patch.object(context, 'wrap_socket', side_effect=ssl.SSLCertVerificationError('fake mismatch')), \
                patch('socket.getaddrinfo', return_value=answers('8.8.8.8')):
            with self.assertRaises(urllib.error.URLError):
                self.read('https://' + HOST + '/robots.txt')
        self.assertEqual(self.requests, [])
        self.assertTrue(self.sockets[-1].closed)


if __name__ == '__main__':
    unittest.main()
