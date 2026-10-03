#!/usr/bin/env python3
"""Transport gap reproduction: real urllib connection selection, fake DNS/socket only. GPL-2.0-or-later."""
import importlib.machinery,importlib.util,socket,sys,unittest,urllib.request
from pathlib import Path
from unittest.mock import patch
TOOLS=Path(__file__).resolve().parents[2]/'tools/scoutro'
sys.path.insert(0,str(TOOLS))
loader=importlib.machinery.SourceFileLoader('dns_gap_discovery',str(TOOLS/'scoutro-discovery'))
spec=importlib.util.spec_from_loader(loader.name,loader);discovery=importlib.util.module_from_spec(spec);loader.exec_module(discovery)
class DnsGap(unittest.TestCase):
    def test_validation_and_http_connection_use_separate_dns_answers(self):
        attempts=[]
        def answer(ip):return [(socket.AF_INET,socket.SOCK_STREAM,socket.IPPROTO_TCP,'',(ip,80))]
        class FakeSocket:
            def __init__(self,*args,**kwargs):pass
            def settimeout(self,*args):pass
            def connect(self,address):
                attempts.append(address)
                raise RuntimeError('stop before connection: fake socket')
            def close(self):pass
        with patch('socket.getaddrinfo',side_effect=[answer('8.8.8.8'),answer('127.0.0.1')]) as dns,patch('socket.socket',FakeSocket):
            target=discovery.public_robots_url('http://rebinding-fixture.test/robots.txt')
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with self.assertRaisesRegex(RuntimeError,'fake socket'):opener.open(target,timeout=1)
            self.assertEqual(dns.call_count,2)
        self.assertEqual(attempts,[('127.0.0.1',80)])
        # This is evidence for the documented open gap, not a security guarantee.
if __name__=='__main__':unittest.main()
