#!/usr/bin/env python3
"""Offline MCP protocol/schema/auth boundaries; fakes only. GPL-2.0-or-later."""
import io,json,os,runpy,subprocess,sys,unittest,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
MODULE=runpy.run_path(str(ROOT/'tools/scoutro/scoutro-mcp'))
CATALOG=json.loads((ROOT/'htroot/env/scoutro/api/actions.json').read_text())
TOKEN='sca_disposable.placeholder'
class FakeResponse(io.BytesIO):
    def __init__(self,data,status=200):super().__init__(json.dumps(data).encode());self.status=status
class FakeOpener:
    def __init__(self):self.calls=[];self.grants=['index.metrics','seo.read'];self.status=200
    def open(self,request,timeout):
        self.calls.append(request)
        self.assert_timeout=timeout
        if request.full_url.endswith('/capabilities'):return FakeResponse({'actions':[{'name':n} for n in self.grants]},self.status)
        return FakeResponse({'documents':3,'pages':2,'hosts':1})
class Mcp(unittest.TestCase):
    def setUp(self):self.opener=FakeOpener();self.adapter=MODULE['Adapter']('http://127.0.0.1:1',TOKEN,CATALOG,self.opener)
    def request(self,method,params=None):return self.adapter.dispatch({'jsonrpc':'2.0','id':1,'method':method,'params':params or {}})
    def test_initialize_notifications_ping_and_parse(self):
        self.assertEqual(self.request('initialize',{'protocolVersion':'2025-06-18'})['result']['protocolVersion'],'2025-06-18')
        self.assertEqual(self.request('ping')['result'],{})
        self.assertIsNone(self.adapter.dispatch({'jsonrpc':'2.0','method':'notifications/initialized'}))
        self.assertIn('error',self.adapter.dispatch([]));self.assertIn('error',self.request('missing'))
        self.assertEqual(self.request('tools/list',[])['result']['tools'][0]['annotations']['readOnlyHint'],True)
    def test_tools_list_uses_capabilities_and_exact_catalog_schemas(self):
        names={t['name']:t for t in self.request('tools/list')['result']['tools']}
        self.assertIn('scoutro_index_metrics',names);self.assertNotIn('scoutro_system_status',names);self.assertNotIn('scoutro_crawl_start',names)
        self.assertFalse(names['scoutro_index_metrics']['inputSchema']['additionalProperties'])
        self.assertEqual(names['scoutro_index_metrics']['inputSchema']['properties']['collection']['pattern'],'^[A-Za-z0-9_-]{1,64}$')
    def test_token_is_only_sent_to_fixed_agent_path_and_not_stdio(self):
        result=self.request('tools/call',{'name':'scoutro_index_metrics','arguments':{'collection':'visible'}})
        self.assertNotIn(TOKEN,json.dumps(result));self.assertEqual(self.opener.calls[-1].full_url,'http://127.0.0.1:1/scoutro/api/agent/v1/index/metrics?collection=visible')
        self.assertEqual(self.opener.calls[-1].get_header('Authorization'),'Bearer '+TOKEN)
        self.assertEqual(self.opener.assert_timeout,15)
    def test_revocation_and_removed_grant_are_refreshed(self):
        self.assertIn('scoutro_index_metrics',self.adapter.available());self.opener.grants=[]
        self.assertIn('error',self.request('tools/call',{'name':'scoutro_index_metrics'}))
        self.opener.status=401;self.assertIn('error',self.request('tools/list'))
    def test_invalid_arguments_never_make_an_action_request(self):
        for args in [{'fq':'*:*'},{'collection':'bad" OR *:*'},{'collection':123}]:
            result=self.request('tools/call',{'name':'scoutro_index_metrics','arguments':args});self.assertIn('error',result)
        self.assertTrue(all(r.full_url.endswith('/capabilities') for r in self.opener.calls))
    def test_config_and_redirect_cannot_leak_bearer(self):
        for base in ['http://example.com','https://user:pass@example.com','file:///tmp/','https://example.com?token=x']:
            with self.assertRaises(MODULE['AdapterError']):MODULE['Adapter'](base,TOKEN,CATALOG)
        for token in ['', 'admin:password', 'sca_bad.abc\nHeader:evil']:
            with self.assertRaises(MODULE['AdapterError']):MODULE['Adapter']('https://fixture.invalid',token,CATALOG)
        with self.assertRaises(MODULE['AdapterError']):MODULE['NoRedirect']().redirect_request(None,None,302,'',{},'https://other.invalid')
        with self.assertRaises(MODULE['AdapterError']):self.adapter.get('/scoutro/api/v1/system')
    def test_knowledge_graph_tools_follow_their_two_grants(self):
        kg_read={'scoutro_kg_entities','scoutro_kg_entity','scoutro_kg_entity_statements','scoutro_kg_statement',
                 'scoutro_kg_statement_evidence','scoutro_kg_host_entities','scoutro_kg_source'}
        admin_only={'scoutro_kg_status','scoutro_kg_control','scoutro_kg_download'}
        self.opener.grants=['kg.read'];names=set(self.adapter.available())
        self.assertTrue(kg_read<=names);self.assertFalse(names&admin_only);self.assertFalse(names&{'scoutro_kg_export','scoutro_kg_changes'})
        self.opener.grants=['kg.export'];names=set(self.adapter.available())
        self.assertEqual({n for n in names if n.startswith('scoutro_kg_')},{'scoutro_kg_export','scoutro_kg_changes'})
        self.opener.grants=['kg.read','kg.export']
        self.request('tools/call',{'name':'scoutro_kg_entity','arguments':{'id':'kge_'+'a'*20,'collection':'visible'}})
        self.assertEqual(self.opener.calls[-1].full_url,'http://127.0.0.1:1/scoutro/api/agent/v1/kg/entities/kge_'+'a'*20+'?collection=visible')
        self.request('tools/call',{'name':'scoutro_kg_changes','arguments':{'expand':True,'limit':100}})
        self.assertEqual(self.opener.calls[-1].full_url,'http://127.0.0.1:1/scoutro/api/agent/v1/kg/changes?expand=true&limit=100')
        for args in [{'id':'../status'},{'id':'kge_x'}]:
            self.assertIn('error',self.request('tools/call',{'name':'scoutro_kg_entity','arguments':args}))
        self.assertTrue(self.opener.calls[-1].full_url.endswith('/capabilities'))
    def test_response_size_is_bounded(self):
        class Large:
            def open(self,*args,**kwargs):return FakeResponse({'data':'x'*(1024*1024)})
        self.adapter.opener=Large();self.assertIn('error',self.request('tools/list'))
    def test_actual_stdio_outputs_only_jsonrpc(self):
        env={**os.environ,'SCOUTRO_TOKEN':TOKEN,'SCOUTRO_URL':'http://127.0.0.1:1','PYTHONDONTWRITEBYTECODE':'1'}
        source='\n'.join([json.dumps({'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-03-26'}}),json.dumps({'jsonrpc':'2.0','method':'notifications/initialized'}),'{bad',json.dumps({'jsonrpc':'2.0','id':2,'method':'ping'})])+'\n'
        p=subprocess.run([sys.executable,str(ROOT/'tools/scoutro/scoutro-mcp')],input=source,text=True,capture_output=True,env=env,timeout=5)
        self.assertEqual(p.returncode,0);self.assertEqual(p.stderr,'');lines=[json.loads(l) for l in p.stdout.splitlines()]
        self.assertEqual(len(lines),3);self.assertEqual(lines[1]['error']['code'],-32700);self.assertNotIn(TOKEN,p.stdout)
if __name__=='__main__':unittest.main()
