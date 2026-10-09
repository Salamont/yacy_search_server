#!/usr/bin/env python3
"""Offline CLI and generated-description agreement; no HTTP or credentials. GPL-2.0-or-later."""
import sys
sys.dont_write_bytecode=True
import contextlib,io,json,runpy,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
CLI=runpy.run_path(str(ROOT/'tools/scoutro/scoutroctl'))
class Contracts(unittest.TestCase):
 def test_matching_details_keep_read_grants_and_export_contracts(self):
  spec=json.loads((ROOT/'htroot/env/scoutro/api/openapi.json').read_text())
  catalog=json.loads((ROOT/'htroot/env/scoutro/api/actions.json').read_text())
  suffix='/kg/entities/{id}/suggestions/{proposal}/contributions'
  admin=spec['paths']['/v1'+suffix]['get'];agent=spec['paths']['/agent/v1'+suffix]['get']
  self.assertEqual(admin['operationId'],'kg.entity.contributions')
  self.assertEqual(agent['x-scoutro-agent-grants'],['kg.read'])
  self.assertEqual({p['name'] for p in admin['parameters']},{'id','proposal','offset','limit','collection'})
  action=next(a for a in catalog['actions'] if a['name']=='kg.entity.contributions')
  self.assertTrue(action['agent']['grantable']);self.assertFalse(action['mutating'])
  schemas=spec['components']['schemas']
  self.assertIn('match_contribution',schemas['KgChange']['properties']['kind']['enum'])
  self.assertIn('match_contribution',schemas['KgExportRecord']['properties']['record']['enum'])
  self.assertEqual(schemas['KgSuggestion']['properties']['contributions']['maxItems'],25)
  self.assertIn('contributions_path',schemas['KgSuggestion']['properties'])
  self.assertEqual(schemas['KgMatchContribution']['properties']['evidence_strength']['enum'],['stated','qualified','weak'])
 def setUp(self):
  self.openapi=json.loads((ROOT/'htroot/env/scoutro/api/openapi.json').read_text())
  self.actions=json.loads((ROOT/'htroot/env/scoutro/api/actions.json').read_text())
 def test_collection_is_required_without_default_in_both_paths(self):
  for schema in ['CrawlStart','AgentCrawlStart']:
   spec=self.openapi['components']['schemas'][schema]
   self.assertIn('collection',spec['required']); self.assertNotIn('default',spec['properties']['collection'])
  action=next(x for x in self.actions['actions'] if x['name']=='crawl.start')
  self.assertTrue(action['parameters']['collection']['required'])
 def test_cli_refuses_missing_collection_before_client(self):
  with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit) as result:
   CLI['build_parser']().parse_args(['crawl','start','https://example.com/'])
  self.assertEqual(result.exception.code,2)
 def test_new_actions_and_agent_paths_are_consistent(self):
  for name,path in [('host.resolve','hosts/resolve'),('collections.list','collections'),('discovery.status','discovery/status')]:
   action=next(x for x in self.actions['actions'] if x['name']==name)
   self.assertFalse(action['mutating']); self.assertTrue(action['agent']['grantable']); self.assertFalse(action['agent']['presetable'])
   self.assertEqual(action['http']['path'],'/scoutro/api/v1/'+path)
   self.assertEqual(action['agent']['http']['path'],'/scoutro/api/agent/v1/'+path)
   self.assertIn('/agent/v1/'+path,self.openapi['paths'])
 def test_generated_descriptions_are_reproducible(self):
  with tempfile.TemporaryDirectory() as folder,patch.object(sys,'argv',['generator',folder]),contextlib.redirect_stdout(io.StringIO()):
   runpy.run_path(str(ROOT/'tools/scoutro/generate_api_description.py'),run_name='__main__')
   for name in ['openapi.json','actions.json']: self.assertEqual((Path(folder)/name).read_bytes(),(ROOT/'htroot/env/scoutro/api'/name).read_bytes())
 def test_durable_history_routes_share_read_grant_and_export_is_separate(self):
  for name in ['kg.history','kg.entity.history','kg.observation','kg.observation.history']:
   action=next(x for x in self.actions['actions'] if x['name']==name)
   self.assertFalse(action['mutating']);self.assertEqual('kg.read',action['agent']['grant'])
   self.assertIn(action['agent']['http']['path'].removeprefix('/scoutro/api'),self.openapi['paths'])
  export=next(x for x in self.actions['actions'] if x['name']=='kg.export')
  self.assertEqual('kg.export',export['agent']['grant']);self.assertIn('history',export['parameters']['include']['enum'])
  policies=self.openapi['components']['schemas']['KgCollectionChange']['properties']
  for field in ['jobsExtraction','jobsDisplay','jobsMatching']:self.assertEqual('boolean',policies[field]['type'])
 def test_cli_history_export_and_download_send_explicit_contract(self):
  args,kwargs=self.call(['kg','export','--history','--collection','kga'],token='agent')
  self.assertEqual('/agent/v1/kg/export',args[1]);self.assertEqual('history',args[2]['include'])
  args,kwargs=self.call(['kg','download','--history','--collection','kga'])
  self.assertEqual('/v1/kg/export/download',args[1]);self.assertEqual('history',args[2]['include']);self.assertTrue(kwargs['raw'])
 def call(self,args,token=''):
  requests=[]
  class Client:
   def __init__(self,*_):self.v1='/agent/v1' if token else '/v1'
   def call(self,*args,**kwargs):requests.append((args,kwargs));return {}
  globals=CLI['main'].__globals__
  with patch.dict(globals,Client=Client,password=lambda:'',agent_token=lambda:token),contextlib.redirect_stdout(io.StringIO()):CLI['main'](args)
  return requests[0]
 def test_admin_cli_sends_explicit_collection_and_idempotency(self):
  args,kwargs=self.call(['crawl','start','https://example.com/','--collection','test-web','--idempotency-key','same','--max-pages','15'])
  self.assertEqual(args,('POST','/v1/crawls'));self.assertEqual(kwargs['body']['collection'],'test-web');self.assertEqual(kwargs['extra_headers'],{'Idempotency-Key':'same'})
 def test_agent_cli_uses_same_contract(self):
  args,kwargs=self.call(['crawl','start','https://example.com/','--collection','test-web'],token='test')
  self.assertEqual(args,('POST','/agent/v1/crawls'));self.assertEqual(kwargs['body']['collection'],'test-web')
 def test_cli_host_collections_and_status_select_auth_path(self):
  for token in ['','test']:
   prefix='/agent/v1' if token else '/v1'
   for argv,path in [(['host','resolve','https://EXAMPLE.com/path','--collection','test-web'],'/hosts/resolve'),(['collections'],'/collections'),(['automation','status'],'/discovery/status')]:
    args,_=self.call(argv,token);self.assertEqual(args[:2],('GET',prefix+path))
 def test_system_questions_and_metrics_are_shared_by_cli_and_descriptions(self):
  grants={g['name']:g for g in self.actions['agentAccess']['grants']}
  self.assertTrue(grants['index.metrics']['scoped']);self.assertFalse(grants['index.metrics']['presetable']);self.assertNotIn('system.questions',grants)
  questions=next(a for a in self.actions['actions'] if a['name']=='system.questions')
  self.assertEqual(questions['agent']['delegatesTo'],['index.metrics','crawl.list','discovery.status','collections.list','seo.read'])
  for token in ['','test']:
   prefix='/agent/v1' if token else '/v1'
   for argv,path in [(['index','metrics','--collection','visible'],'/index/metrics'),(['ask','Wie viele Seiten?','--collection','visible'],'/system/questions')]:
    args,_=self.call(argv,token);self.assertEqual(args[:2],('GET',prefix+path));self.assertEqual(args[2]['collection'],'visible')
  self.assertEqual(self.actions['mcpAdapter']['transport'],'stdio')
  chat=self.openapi['paths']['/v1/chat/completions']['post']
  self.assertEqual(chat['servers'][0]['url'],'/')
  self.assertIn('text/event-stream',chat['responses']['200']['content'])
  self.assertFalse(any(a['name']=='chat.completions' for a in self.actions['actions']))
 def test_discovery_schema_separates_batch_worker_and_automation(self):
  properties=self.openapi['components']['schemas']['DiscoveryStatus']['properties']
  self.assertEqual(properties['automation_status']['enum'],['active','paused','disabled'])
  for field in ['running','active_batch','job_name','collection','phase','started_at','waiting_reason','allowed_actions']:self.assertIn(field,properties)
 def test_existing_discovery_actions_are_preserved(self):
  names={action['name'] for action in self.actions['actions']}
  for suffix in ['catalog','export','jobs.list','jobs.create','jobs.get','jobs.update','jobs.delete','jobs.run','enable','disable','pause','resume']: self.assertIn('discovery.'+suffix,names)
 def test_crawl_progress_expresses_unknowns(self):
  properties=self.openapi['components']['schemas']['Crawl']['properties']
  for field in ['url','collection','scope','startedAt','endedAt','lastError']:self.assertIn('null',properties[field]['type'])
 def test_index_browser_cli_and_catalog_share_scoped_contract(self):
  action=next(x for x in self.actions['actions'] if x['name']=='index.browse')
  self.assertFalse(action['mutating']);self.assertTrue(action['agent']['grantable']);self.assertTrue(action['agent']['scoped']);self.assertFalse(action['agent']['presetable'])
  self.assertEqual(set(action['parameters']),{'q','collection','limit','offset'})
  for token in ['','test']:
   args,kwargs=self.call(['index','browse','a.example','--collection','visible','--limit','5','--offset','10'],token)
   self.assertEqual(args[:2],('GET',('/agent/v1' if token else '/v1')+'/index/browse'))
   self.assertEqual(args[2],{'q':'a.example','collection':'visible','limit':5,'offset':10})
if __name__=='__main__':unittest.main()
