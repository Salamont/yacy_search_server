#!/usr/bin/env python3
"""Read-only SEO API checks against seo-live-smoke.py fixtures; agent creation is disposable only. GPL-2.0-or-later."""
import sys
sys.dont_write_bytecode = True
import json
import unittest
import urllib.parse
from test_agent_api import BASE, admin_opener, http, agent_call, create_agent

class SeoApi(unittest.TestCase):
    def get(self, path, query=None):
        status, _, text = http('GET', BASE + '/scoutro/api/v1/seo/' + path + ('?' + urllib.parse.urlencode(query) if query else ''), opener=admin_opener())
        return status, json.loads(text)
    def test_host_summary_and_statuses(self):
        status, data = self.get('hosts/a.example'); self.assertEqual(status,200)
        self.assertEqual(data['indexed_pages'],29); c=data['citation']
        self.assertEqual([c[k] for k in ['processed_pages','pending_pages','unavailable_pages','unknown_pages']],[26,1,1,1])
        self.assertEqual([c[k] for k in ['references_total','references_internal','references_external']],[7,4,3]); self.assertNotIn('external_hosts',c)
    def test_pages_detail_and_zero(self):
        _, data=self.get('hosts/a.example/pages',{'limit':100}); self.assertEqual(len(data['items']),29)
        for p in data['items']:
            status, detail=self.get('pages/'+p['id']); self.assertEqual(status,200); self.assertEqual(detail['citation'],p['citation'])
            if p['citation']['status']!='processed': self.assertIsNone(p['citation']['references_total'])
        zero=next(p for p in data['items'] if p['url'].endswith('/zero')); self.assertEqual(zero['citation']['references_total'],0)
    def test_controlled_queries_and_auth(self):
        for q in [{'fq':'*:*'},{'fl':'*'},{'sort':'random()'},{'limit':101}]: self.assertEqual(self.get('hosts/a.example/pages',q)[0],400)
        self.assertEqual(self.get('hosts/absent.example')[0],404)
        self.assertEqual(http('GET',BASE+'/scoutro/api/v1/seo/hosts')[0],401)
        self.assertEqual(http('POST',BASE+'/scoutro/api/v1/seo/hosts',opener=admin_opener())[0],405)
    def test_explicit_agent_grant_and_scope(self):
        token,_,_=create_agent('SEO disposable',collections=('visible',),extra_actions=('seo.read',))
        status,data,_=agent_call(token,'GET','/seo/hosts'); self.assertEqual(status,200); self.assertEqual([p['host'] for p in data['items']],['a.example'])
        status,data,_=agent_call(token,'GET','/seo/hosts/a.example'); self.assertEqual(status,200); self.assertEqual(data['indexed_pages'],28)
        self.assertEqual(agent_call(token,'GET','/seo/hosts/b.example')[0],404)
        self.assertEqual(agent_call(token,'GET','/seo/hosts',{'collection':'secret'})[0],403)
        _,data=self.get('hosts/a.example/pages',{'limit':100})
        private=next(p for p in data['items'] if p['url'].endswith('/inconsistent'))
        self.assertEqual(agent_call(token,'GET','/seo/pages/'+private['id'])[0],404)
        zero=next(p for p in data['items'] if p['url'].endswith('/zero'))
        status,data,_=agent_call(token,'GET','/seo/pages/'+zero['id']); self.assertEqual(status,200); self.assertEqual(data['collections'],['visible']); self.assertIsNone(data['citation']['host_extent'])
        plain,_,_=create_agent('No SEO disposable',collections=('visible',))
        self.assertEqual(agent_call(plain,'GET','/seo/hosts')[0],403)
        self.assertEqual(agent_call(None,'GET','/seo/hosts')[0],401)

if __name__=='__main__': unittest.main()
