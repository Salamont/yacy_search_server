#!/usr/bin/env python3
"""System chat/API/MCP checks on the disposable offline SEO fixtures. GPL-2.0-or-later."""
import json
from pathlib import Path
import runpy
import unittest
import urllib.parse
from test_agent_api import BASE, admin_opener, http, agent_call, create_agent
ROOT=Path(__file__).resolve().parents[2]
MCP=runpy.run_path(str(ROOT/'tools/scoutro/scoutro-mcp'))
CATALOG=json.loads((ROOT/'htroot/env/scoutro/api/actions.json').read_text())

class SystemApi(unittest.TestCase):
    def get(self,path,query=None):
        status,_,text=http('GET',BASE+'/scoutro/api/v1/'+path+('?' + urllib.parse.urlencode(query) if query else ''),opener=admin_opener())
        return status,json.loads(text)
    def chat(self,text,token=None,stream=False,collection=None,admin=False,search='global'):
        body={'messages':[{'role':'user','content':text,'search':search}], 'stream':stream, 'model':'no-function-calling-fixture'}
        if collection is not None:body['collection']=collection
        headers={'Content-Type':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        status,_,raw=http('POST',BASE+'/v1/chat/completions',json.dumps(body).encode(),headers,opener=admin_opener() if admin else None)
        return status,raw
    def test_admin_metrics_and_structured_actions(self):
        status,data=self.get('index/metrics',{'collection':'visible'});self.assertEqual(status,200);self.assertEqual((data['documents'],data['pages'],data['hosts']),(28,28,1))
        status,data=self.get('system/questions',{'q':'Wie viele Hosts sind im System?','collection':'visible'});self.assertEqual(status,200);self.assertEqual(data['action'],'index.metrics');self.assertEqual(data['facts']['hosts'],1)
        for question,action in [('Welche Crawls laufen?','crawl.list'),('Ist Discovery aktiv?','discovery.status'),('Welche Collections gibt es?','collections.list'),('Analysiere Host a.example','seo.read')]:
            status,data=self.get('system/questions',{'q':question});self.assertEqual(status,200,(question,data));self.assertEqual(data['action'],action);self.assertIn('facts',data)
        self.assertEqual(self.get('index/metrics',{'fq':'*:*'})[0],400)
        self.assertEqual(self.get('system/questions',{'q':'/system unknown'})[0],400)
        self.assertEqual(self.get('system/questions',{'q':'What is a galaxy?'})[0],400)
        self.assertEqual(self.get('system/questions',{'q':'Analysiere Host localhost'})[0],400)
        self.assertEqual(http('GET',BASE+'/scoutro/api/v1/index/metrics')[0],401)
    def test_scope_and_grants_for_all_questions(self):
        token,_,_=create_agent('System data disposable',collections=('visible',),extra_actions=('index.metrics','collections.list','seo.read'))
        for question in ['Wie viele Seiten?','Welche Collections gibt es?','Analysiere Host a.example']:
            status,data,_=agent_call(token,'GET','/system/questions',{'q':question});self.assertEqual(status,200,data)
            self.assertEqual(agent_call(token,'GET','/system/questions',{'q':question,'collection':'secret'})[0],403)
        status,data,_=agent_call(token,'GET','/system/questions',{'q':'Wie viele Seiten?'});self.assertEqual(data['facts']['documents'],28)
        self.assertEqual(agent_call(token,'GET','/system/questions',{'q':'Ist Discovery aktiv?'})[0],403)
        self.assertEqual(agent_call(token,'GET','/index/metrics',{'fq':'*:*'})[0],400)
        plain,_,_=create_agent('Search only disposable',collections=('visible',))
        for question in ['Wie viele Seiten?','Ist Discovery aktiv?','Welche Crawls laufen?','Welche Collections gibt es?','Analysiere Host a.example']:
            self.assertEqual(agent_call(plain,'GET','/system/questions',{'q':question})[0],403)
        status,data,_=agent_call(token,'GET','/system/questions',{'q':'Analysiere Host b.example'});self.assertEqual(status,200);self.assertFalse(data['facts']['indexed'])
        self.assertEqual(agent_call(token,'POST','/system/questions',body={})[0],405)
    def test_chat_intercepts_before_model_and_search_for_digest_and_bearer(self):
        token,_,_=create_agent('Chat metrics disposable',collections=('visible',),extra_actions=('index.metrics',))
        status,raw=self.chat('Wie viele Seiten?',token=token);self.assertEqual(status,200,raw)
        data=json.loads(raw);self.assertEqual(data['model'],'scoutro-system-actions');self.assertEqual(data['scoutro']['facts']['pages'],28);self.assertNotIn('tool_calls',data['choices'][0]['message'])
        status,raw=self.chat('How many hosts are there?',token=token,stream=True);self.assertEqual(status,200,raw);self.assertTrue(raw.endswith('data: [DONE]\n\n'))
        chunks=[json.loads(line[6:]) for line in raw.splitlines() if line.startswith('data: {')]
        self.assertEqual(chunks[0]['scoutro']['facts']['hosts'],1);self.assertEqual(chunks[-1]['choices'][0]['finish_reason'],'stop')
        status,raw=self.chat('Wie viele Seiten?',admin=True,collection='visible');self.assertEqual(status,200,raw);self.assertEqual(json.loads(raw)['scoutro']['facts']['pages'],28)
        self.assertEqual(self.chat('Wie viele Seiten?')[0],401)
        self.assertEqual(self.chat('Wie viele Seiten?',token='sca_invalid.invalid')[0],401)
        self.assertEqual(self.chat('Wie viele Seiten?',token=token,collection='secret')[0],403)
        self.assertEqual(self.chat('/system unknown',token=token)[0],400)
        self.assertEqual(self.chat('Ist Discovery aktiv?',token=token)[0],403)
        self.assertEqual(self.chat('Wie viele Seiten?',token=token,collection='absent')[0],403)
    def test_real_mcp_tools_use_scoped_actions_and_do_not_expose_admin_mappings(self):
        token,_,_=create_agent('MCP metrics disposable',collections=('visible',),extra_actions=('index.metrics','seo.read'))
        adapter=MCP['Adapter'](BASE,token,CATALOG)
        names=adapter.available();self.assertIn('scoutro_index_metrics',names);self.assertIn('scoutro_system_questions',names)
        self.assertNotIn('scoutro_config_set',names);self.assertNotIn('scoutro_crawl_start',names);self.assertNotIn('scoutro_discovery_status',names)
        result=adapter.call('scoutro_index_metrics',{});self.assertFalse(result['isError']);self.assertEqual(json.loads(result['content'][0]['text'])['hosts'],1)
        result=adapter.call('scoutro_system_questions',{'q':'Wie viele Seiten?'});self.assertFalse(result['isError']);self.assertEqual(json.loads(result['content'][0]['text'])['facts']['pages'],28)
        result=adapter.call('scoutro_index_metrics',{'collection':'secret'});self.assertTrue(result['isError']);self.assertEqual(json.loads(result['content'][0]['text'])['error']['code'],'collection_not_in_scope')
        result=adapter.call('scoutro_system_questions',{'q':'Ist Discovery aktiv?'});self.assertTrue(result['isError'])

if __name__=='__main__':unittest.main()
