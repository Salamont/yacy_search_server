#!/usr/bin/env python3
"""Host/crawl API smoke: temporary SEO peer only; crawl requests are INVALID. GPL-2.0-or-later."""
import sys
sys.dont_write_bytecode=True
import json,unittest,urllib.parse
from test_agent_api import BASE,admin_opener,http,agent_call,create_agent
class FlowApi(unittest.TestCase):
 def admin(self,method,path,body=None):
  status,_,text=http(method,BASE+'/scoutro/api/v1/'+path,json.dumps(body).encode() if body is not None else None,{'Content-Type':'application/json'} if body is not None else {},admin_opener())
  return status,json.loads(text)
 def test_normalized_known_and_unknown(self):
  for url,indexed,host in [('HTTPS://A.EXAMPLE/path?q=1#f',True,'a.example'),('https://www.livaid.example/',False,'www.livaid.example')]:
   status,data=self.admin('GET','hosts/resolve?'+urllib.parse.urlencode({'input':url}));self.assertEqual(status,200);self.assertEqual(data['host'],host);self.assertEqual(data['indexed'],indexed);self.assertTrue(data['crawl']['collectionRequired'])
 def test_auth_and_invalid_host(self):
  self.assertEqual(http('GET',BASE+'/scoutro/api/v1/hosts/resolve?input=a.example')[0],401)
  for target in ['ftp://a.example/','https://u:p@a.example/','https://[::1]/']:
   self.assertEqual(self.admin('GET','hosts/resolve?'+urllib.parse.urlencode({'input':target}))[0],400)
 def test_missing_empty_invalid_collection_never_starts(self):
  before=self.admin('GET','crawls')[1]
  for body in [{'url':'https://a.example/'},*({'url':'https://a.example/','collection':c} for c in ['',None,'bad collection','foreign:bad'])]:
   status,data=self.admin('POST','crawls',body);self.assertEqual(status,400);self.assertEqual(data['error']['code'],'invalid_request')
  self.assertEqual(self.admin('GET','crawls')[1],before)
 def test_catalog_and_disabled_discovery_projection(self):
  status,data=self.admin('GET','collections');self.assertEqual(status,200);self.assertTrue(any(c['id']=='visible' for c in data['collections']))
  status,data=self.admin('GET','discovery/status');self.assertEqual(status,200);self.assertEqual(data['automation_status'],'disabled');self.assertFalse(data['running']);self.assertIsNone(data['active_batch']);self.assertEqual(data['allowed_actions'],['enable'])
 def test_agent_explicit_scope_and_foreign_visibility(self):
  token,_,_=create_agent('Flow disposable',collections=('visible',),extra_actions=('host.resolve','collections.list'))
  status,data,_=agent_call(token,'GET','/hosts/resolve',{'input':'https://b.example/path'});self.assertEqual(status,200);self.assertFalse(data['indexed']);self.assertFalse(data['crawl']['canRequest'])
  self.assertEqual(agent_call(token,'GET','/hosts/resolve',{'input':'a.example','collection':'secret'})[0],403)
  status,data,_=agent_call(token,'GET','/collections');self.assertEqual(status,200);self.assertEqual([c['id'] for c in data['collections']],['visible']);self.assertFalse(data['allowNew'])
  self.assertEqual(agent_call(token,'GET','/discovery/status')[0],403)
  self.assertEqual(agent_call(token,'POST','/crawls',body={'url':'https://a.example/','collection':'visible'})[0],403)
 def test_granted_agent_cannot_start_foreign_collection(self):
  token,_,_=create_agent('Crawl validation disposable',collections=('visible',),preset='research_crawl',domains='a.example')
  self.assertEqual(agent_call(token,'POST','/crawls',body={'url':'https://a.example/','collection':'secret'})[0],403)
  self.assertEqual(agent_call(token,'POST','/crawls',body={'url':'https://a.example/'})[0],400)
if __name__=='__main__':unittest.main()
