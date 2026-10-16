#!/usr/bin/env python3
"""F1/F2 on a NEW temporary peer and counting localhost model. No production URL/DATA accepted."""
import hashlib,html,importlib.util,json,os,re,socket,subprocess,tempfile,threading,time,urllib.error,urllib.parse,urllib.request
from pathlib import Path
from http.server import ThreadingHTTPServer
REPO=Path(__file__).resolve().parents[2]
JAVA=os.environ.get('JAVA','java');CP=f'{REPO}/build/classes/java/main:{REPO}/lib/*'
spec=importlib.util.spec_from_file_location('controls',REPO/'test/scoutro-ui/rag-report-tldr-live-smoke.py')
common=importlib.util.module_from_spec(spec);spec.loader.exec_module(common)
checks=0

def check(value,label):
 global checks
 assert value,label
 checks+=1

def main():
 model=ThreadingHTTPServer(('127.0.0.1',0),common.FakeLLM)
 threading.Thread(target=model.serve_forever,daemon=True).start()
 with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
 base=f'http://127.0.0.1:{port}';stub=f'http://127.0.0.1:{model.server_port}'
 process=None
 with tempfile.TemporaryDirectory(prefix='scoutro-blockers-') as temporary:
  root=Path(temporary);config=root/'DATA/SETTINGS/yacy.conf';config.parent.mkdir(parents=True)
  (root/'.scoutro-dashboard-disposable').touch()
  dictionary=root/'DATA/DICTIONARIES/synonyms';dictionary.mkdir(parents=True)
  (dictionary/'fixture.txt').write_text('Computer,Rechner,Elektronengehirn\nAltenheim,Seniorenresidenz\nBeratung,Consulting,Besprechung\n')
  cache=root/'DATA/DICTIONARIES/harvesting';cache.mkdir(parents=True)
  for name in ('export_roar_ROAR_ListFriends.xml','ListFriends.xml'):(cache/name).write_text('<?xml version="1.0"?><BaseURLs/>')
  rows=[dict(service='OPENAI',model='shared',hoststub=stub,api_key='sk-fixture-secret-shared',max_tokens='1024',chat=True,tldr=True,logreport=True,knowledge=True)]
  for name in ('second','third'):rows.append(dict(rows[0],model=name,api_key='sk-fixture-secret-'+name,chat=False,tldr=False,logreport=False,knowledge=False))
  config.write_text('\n'.join([f'port={port}','adminAccountForLocalhost=false','adminAccountAllPages=false','adminAccountUserName=admin',
   'adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c','network.unit.definition=defaults/yacy.network.webportal.unit',
   'browserPopUpTrigger=false','autocrawl=false','server.https=false','upnp.enabled=false','donation.iframesource=','locale.language=de',
   'scoutro.kg.enabled=false','ai.tldr.enabled=true','ai.logreport.enabled=true','ai.logreport.schedule=1|weekly|1|08:00|1|0|Europe/Berlin',
   'resource.disk.free.min.steadystate=1','resource.disk.free.min.undershot=1','resource.disk.used.max.steadystate=1000000000000',
   'resource.disk.used.max.overshot=1000000000000','operator.sentinel=preserved',
   'ai.production_models='+json.dumps(rows),'ai.inference_system='+json.dumps(dict(service='OPENAI',hoststub=stub)),
   'ai.service_num_ctx='+json.dumps({stub:8192}),'ai.model_capabilities='+json.dumps({f'OPENAI|{stub}|{r["model"]}':dict(thinking='unsupported',tooling='unsupported',vision='unsupported',format='unsupported',format_probe=2) for r in rows})])+'\n')
  state=root/'DATA/SCOUTRO/discovery/state.json';state.parent.mkdir(parents=True);state.write_text('{"state_version":2,"domains":{}}\n')
  digest=hashlib.sha256(state.read_bytes()).hexdigest()
  for fixture in ('DashboardFixture.java','RagSynonymFixture.java'):
   subprocess.run([JAVA,'-cp',CP,str(REPO/'test/scoutro-ui'/fixture),str(root)],cwd=REPO,check=True,timeout=90,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  password=urllib.request.HTTPPasswordMgrWithDefaultRealm();password.add_password(None,base,'admin','yacy')
  client=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPDigestAuthHandler(password))
  def call(path,data=None,kind=None):
   request=urllib.request.Request(base+path,data=data,headers={} if kind is None else {'Content-Type':kind})
   try:
    with client.open(request,timeout=45) as response:return response.status,response.read().decode(response.headers.get_content_charset() or 'utf-8',errors='replace')
   except urllib.error.HTTPError as error:return error.code,error.read().decode(error.headers.get_content_charset() or 'utf-8',errors='replace')
  def form(path,values):
   status,page=call(path);check(status==200,'settings page reachable')
   token=html.unescape(re.search(r'name="transactionToken" value="([^"]+)"',page)[1])
   return call(path,urllib.parse.urlencode(dict(values,transactionToken=token)).encode(),'application/x-www-form-urlencoded')
  def start():
   nonlocal process
   log=(root/'peer.log').open('a');process=subprocess.Popen([JAVA,'-Xmx768m','-Djava.awt.headless=true','-cp',CP,'net.yacy.yacy','-startup',str(root)],cwd=REPO,stdout=log,stderr=log);log.close()
   deadline=time.monotonic()+90
   while time.monotonic()<deadline:
    if process.poll() is not None:raise RuntimeError('Disposable peer stopped during startup')
    try:
     if call('/api/version.xml')[0]==200:return
    except OSError:pass
    time.sleep(.3)
   raise RuntimeError('Disposable peer startup timeout')
  def stop():
   if process and process.poll() is None:
    process.terminate()
    try:process.wait(timeout=25)
    except subprocess.TimeoutExpired:process.kill();process.wait()
  def ui(phase):
   subprocess.run(['node',str(REPO/'test/scoutro-ui/pr75-blockers-ui-test.mjs')],cwd=REPO,env=dict(os.environ,SCOUTRO_URL=base,SCOUTRO_BLOCKER_PHASE=phase),check=True,timeout=100)
  def chat(question,collection=None):
   body=dict(model='chat',search='local',stream=True,messages=[dict(role='user',search='local',content=question)])
   if collection is not None:body['collection']=collection
   before=len(common.CALLS);code,raw=call('/v1/chat/completions',json.dumps(body).encode(),'application/json')
   meta={}
   for line in raw.splitlines():
    if not line.startswith('data:') or '[DONE]' in line:continue
    chunk=json.loads(line[5:]);meta.update({k:v for k,v in chunk.items() if k!='choices'})
   check(code==200,'actual chat endpoint')
   return meta,common.CALLS[before:]
  def verify(active):
   values=common.settings(config);models=json.loads(values['ai.production_models'])
   check(len(models)==3,'three models retained')
   check([m['model'] for m in models if m['knowledge']]==['shared'],'knowledge selection retained')
   check([m['api_key'] for m in models]==[m['api_key'] for m in rows],'all stored keys retained')
   check(all(m['tldr']==(active and m['model']=='shared') and m['logreport']==(active and m['model']=='shared') for m in models),'persisted optional roles')
   check(values['ai.tldr.enabled']==values['ai.logreport.enabled']==str(active).lower(),'persistent automatic flags')
   check(values['operator.sentinel']=='preserved','unrelated configuration retained')
   _,page=call('/LLMSelection_p.html');check(('Automatischer Fallback aktiv' if active else 'Nicht verfügbar') in html.unescape(page),'actual TLDR state shown')
   _,page=call('/LogReports_p.html');check(('Aktiv' if active else 'Ausgeschaltet') in page,'actual report state shown')
  permissions=config.parent.stat().st_mode&0o777
  try:
   start()
   # F1: the same production Searcher, Solr query, resolver, selection and excerpt as real chat.
   cases=[('TV','stackfinder-web','QUOKKA_FUNDSTELLE_A',1),('Suche TV','stackfinder-web','QUOKKA_FUNDSTELLE_A',1),('Rechner','stackfinder-web','SYNONYM_COMPUTER_BELEG',1),
    ('Bitte suche '*25+'Pflegeheime Seniorenheime Beratungen Automatisierungen Integrationen Anwendungen Dienstleistungen Rechner','bauteamcheck-web','SYNONYM_LONG_BELEG',8),
    ('Altenheim','edelsenior-web','SYNONYM_CARE_BELEG',1)]
   for question,collection,marker,concepts in cases:
    meta,calls=chat(question,collection);retrieval=meta['scoutro-retrieval'];sent=json.dumps(calls,ensure_ascii=False)
    check(retrieval['stage']=='all','all original concepts survive real handoff')
    check(retrieval['matches'][0]['concepts']==concepts,'one match per original concept')
    check(retrieval['matches'][0]['expanded']==1,'alternatives counted once: '+json.dumps(retrieval))
    check(marker in sent,'relevant excerpt actually sent to model')
    check([kind for kind,_ in calls]==['chat'],'no extra synonym or TLDR model calls')
    check(meta.get('scoutro-sources'),'source returned to chat')
   for question,collection,marker in [('Beratung','checkthecoach-web','NEGATIVE_MEETING_BELEG'),('KI','checkthecoach-web','NEGATIVE_KITA_BELEG'),('TV','edelsenior-web','QUOKKA_FUNDSTELLE_A')]:
    meta,calls=chat(question,collection)
    check(not meta.get('scoutro-sources'),'negative or foreign source rejected')
    check(marker not in json.dumps(calls),'no invalid source in outgoing context')
   print('PASS: real chat synonym handoff, all four collections, long query, multiple aliases, negative contexts',flush=True)
   ui('http')
   # F2: actual filesystem refusal, not an HTTP mock. Failed saves retain all three active old roles.
   reports=root/'DATA/REPORTS/log';reports.mkdir(parents=True,exist_ok=True)
   old=reports/'report-2026-01-01.md';old.write_text('historical report sentinel')
   journal=reports/'.generation-state.properties';before_journal=journal.read_bytes()
   config.parent.chmod(0o555);check(not os.access(config.parent,os.W_OK),'configuration is genuinely not writable')
   status,body=form('/LLMSelection_p.html',{'saveTldrSettings':'1'})
   check(status==500 and 'Nicht gespeichert' in body and 'TLDR-Einstellungen gespeichert' not in body,'failed TLDR save visible')
   status,body=form('/LogReports_p.html',{'saveReportSettings':'1','scheduleMode':'off','scheduleHours':'1','scheduleTime':'08:00','scheduleWeekday':'1','scheduleMonthday':'0','scheduleZone':'Europe/Berlin'})
   check(status==500 and 'Nicht gespeichert' in body and 'Einstellungen gespeichert' not in body,'failed report save visible')
   ui('failed');verify(True)
   check(journal.read_bytes()==before_journal,'failed saves leave schedule journal unchanged')
   check(sum(k=='report' for k,_ in common.CALLS)==0,'no unplanned report calls')
   stop();config.parent.chmod(permissions);start();verify(True)
   before=sum(k=='tldr' for k,_ in common.CALLS);chat('Weltraumsonderfrage '*45)
   check(sum(k=='tldr' for k,_ in common.CALLS)==before+1,'failed shutdown remains truthfully active after restart, one expected fallback')
   # Positive control: successful saves persist; both independent gates suppress new automatic requests.
   ui('saved')
   before=sum(k=='tldr' for k,_ in common.CALLS);chat('Weltraumsonderfrage '*45)
   check(sum(k=='tldr' for k,_ in common.CALLS)==before,'role opt-out alone prevents automatic fallback')
   check(form('/LLMSelection_p.html',{'saveTldrSettings':'1'})[0]==200,'TLDR save succeeds with writable storage')
   check(form('/LogReports_p.html',{'saveReportSettings':'1','scheduleMode':'off','scheduleHours':'1','scheduleTime':'08:00','scheduleWeekday':'1','scheduleMonthday':'0','scheduleZone':'Europe/Berlin'})[0]==200,'report save succeeds with writable storage')
   verify(False);stop();start();verify(False)
   before=sum(k in ('tldr','report') for k,_ in common.CALLS);chat('Weltraumsonderfrage '*45)
   check(sum(k in ('tldr','report') for k,_ in common.CALLS)==before,'zero automatic optional-role calls after successful shutdown and restart')
   check(old.read_text()=='historical report sentinel','existing reports retained')
   check(hashlib.sha256(state.read_bytes()).hexdigest()==digest,'discovery state unchanged')
   check(json.loads(call('/scoutro/api/v1/crawls')[1])['crawls']==[],'no crawl started')
   check('sk-fixture-secret' not in (root/'peer.log').read_text(errors='replace'),'no stored keys in logs')
   print(f'PASS: {checks} blocker endpoint/persistence/restart checks; all model calls local',flush=True)
  except Exception:
   print((root/'peer.log').read_text(errors='replace')[-5000:],flush=True)
   raise
  finally:
   stop();config.parent.chmod(permissions);model.shutdown();model.server_close()

if __name__=='__main__':main()
