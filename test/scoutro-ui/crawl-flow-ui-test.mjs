/* Scoutro contributors, GPL-2.0-or-later. Disposable peer, all crawl writes intercepted. */
import {createRequire} from 'node:module';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const require=createRequire(import.meta.url),{chromium}=require('playwright');
const base=process.env.SCOUTRO_URL;
if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(base||''))throw Error('Disposable loopback required');
const browser=await chromium.launch({executablePath:process.env.SCOUTRO_CHROMIUM_PATH||'/usr/bin/chromium',args:['--no-proxy-server']});
let count=0;const check=(value,label)=>{assert.ok(value,label);count++;};
const json=(route,data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
try {
 const anon=await browser.newContext();check((await anon.request.get(base+'/ScoutroCrawls_p.html')).status()===401,'Crawls requires admin');await anon.close();
 for(const language of ['en','de']) for(const width of [360,390,412,1280]) {
  const context=await browser.newContext({locale:language==='de'?'de-DE':'en-US',viewport:{width,height:900},isMobile:width<768,hasTouch:width<768,httpCredentials:{username:'admin',password:'yacy'},extraHTTPHeaders:{'Accept-Language':language}});
  await context.addInitScript(()=>{window.scoutroPolls=[];const original=window.setInterval;window.setInterval=(fn,ms,...args)=>{if(ms===15000){window.scoutroPolls.push(fn);return original(fn,600000,...args);}return original(fn,ms,...args);};});
  const page=await context.newPage(),errors=[],posts=[],keys=new Set();let crawls=[],getCalls=0,holdRead=null;
  page.on('pageerror',e=>errors.push(e.message));
  // package 6.1: the catalog (an internal robot_ collection included, never offered) and the creation of a new collection
  const created=[],createPosts=[];
  await page.route('**/scoutro/api/v1/collections',r=>{
   if(r.request().method()==='POST'){const b=r.request().postDataJSON();createPosts.push(b);
    if(['visible','alpha-web'].includes(b.id))return json(r,{error:{code:'collection_exists',message:'exists'}},409);
    created.push({id:b.id,name:b.name,description:b.description||'',documents:0,internal:false,selectable:true,sources:['created']});
    return json(r,{collection:created[created.length-1],created:true},201);}
   return json(r,{collections:[{id:'Alpha-web',name:'Alpha-web',documents:1,selectable:true},{id:'robot_snippet',documents:5,internal:true,selectable:false},{id:'visible',name:'visible',documents:28,selectable:true},...created],allowNew:false,canCreate:true,limit:500});
  });
  await page.route('**/scoutro/api/v1/crawls',async r=>{
   if(r.request().method()==='GET'){getCalls++;if(holdRead){const wait=holdRead;holdRead=null;await wait;}return json(r,{crawls});}
   const body=r.request().postDataJSON(),key=r.request().headers()['idempotency-key'];posts.push({body,key});
   const replay=keys.has(key);keys.add(key);
   crawls=[{id:'fake-profile',url:body.url,host:'absent.example',collection:body.collection,scope:body.scope,depth:body.depth,maxPages:body.maxPages,startedAt:'2026-10-03T00:00:00Z',state:'running',progress:{pagesLoaded:3,total:null,percent:null},lastError:null}];
   return json(r,{...crawls[0],idempotentReplay:replay},replay?200:201);
  });
  await page.goto(base+'/ScoutroCrawls_p.html?url=https%3A%2F%2Fabsent.example%2Fpath',{waitUntil:'networkidle'});
  check(await page.locator('#scc-url').inputValue()==='https://absent.example/path','seed prefilled');
  await page.waitForFunction(()=>!document.getElementById('scc-collection').disabled);
  check(await page.locator('#scc-collection').evaluate(e=>e.tagName)==='SELECT'&&await page.locator('#scc-form datalist, #scc-form input[list], #scc-collections').count()===0,'collection as a real select, no text field, no suggestion list');
  const choices=await page.locator('#scc-collection option').evaluateAll(list=>list.map(o=>[o.value,o.textContent.trim()]));
  check(JSON.stringify(choices.map(o=>o[0]))===JSON.stringify(['','Alpha-web','visible'])&&choices[0][1]===(language==='de'?'Collection wählen':'Choose a collection'),'choose first, then the collections sorted, YaCy internal ones never: '+JSON.stringify(choices));
  check(await page.locator('#scc-collection').inputValue()==='','no default collection');
  check(await page.locator('#scc-collection-new').isVisible(),'New collection offered to the administrator');
  if(width<768)await page.locator('#scoutro-nav-toggle').click();
  check(await page.locator('#scoutro-adminnav a[href="ScoutroCrawls_p.html"]').isVisible(),'desktop/mobile native navigation');
  if(width<768)await page.keyboard.press('Escape');
  check((await page.locator('#scc-new-title').textContent()).includes(language==='de'?'Neuer Crawl':'New crawl'),'localized native UI');
  check(posts.length===0,'GET never starts crawl');await page.locator('#scc-start').click();check(posts.length===0,'empty collection never submits');
  await page.locator('#scc-collection').selectOption('visible');await page.locator('#scc-scope').selectOption('subpath');
  await page.locator('#scc-start').click();await page.waitForFunction(()=>document.querySelector('#scc-crawls').textContent.includes('visible')&&!document.getElementById('scc-status-link').hidden);
  check(posts.length===1 && posts[0].body.collection==='visible' && posts[0].body.scope==='subpath' && posts[0].body.maxPages===15 && posts[0].body.depth===2,'shared JSON contract');
  // package 6.3: the result of the start next to the form, from the backend answer
  const label=k=>page.evaluate(k=>document.querySelector(`[data-scc-label="${k}"]`).textContent,k);
  const fact=k=>page.locator(`#scc-facts dd[data-fact="${k}"]`).textContent();
  check(await page.locator('#scc-result').isVisible()&&await page.locator('#scc-outcome').textContent()===await label('outcome_started'),'the start is confirmed next to the form');
  check(await fact('fact_url')==='https://absent.example/path'&&await fact('collection')==='visible','URL and collection of the start');
  check(await fact('fact_result')===await label('result_started')&&await fact('fact_status')===await label('status_running'),'result of the attempt and the real status');
  check(await fact('fact_id')==='fake-profile'&&(await fact('fact_attempted')).length>4&&(await fact('started_at')).length>4,'profile id and times');
  check(language!=='de'||(await page.locator('#scc-result').textContent()).includes('Gestartet: YaCy hat das Crawl-Profil angelegt.'),'German confirmation');
  check(!/eingereiht|warteschlange|queue/i.test(await page.locator('#scc-result').textContent()),'no queue position claimed');
  check(new URL(page.url()).searchParams.get('crawl')==='fake-profile','the start stays findable after a reload (?crawl=)');
  check(await page.locator('#scc-start').isDisabled()&&await page.locator('#scc-locked').isVisible(),'the started request cannot be sent again');
  await page.evaluate(()=>document.getElementById('scc-form').requestSubmit());await page.waitForTimeout(80);
  check(posts.length===1&&keys.size===1,'a second submit of the started request sends nothing');
  check(await page.locator('#scc-crawl-fake-profile.scc-card-current').count()===1,'the card of this start is marked in the crawl status');
  await page.locator('#scc-status-link').click();
  check(await page.evaluate(()=>document.activeElement?.id)==='scc-crawl-fake-profile','View crawl status leads to its card');
  check((await page.locator('.scc-card').textContent()).includes('3'),'evidenced progress displayed');
  check(!(await page.locator('.scc-card').textContent()).includes('%'),'no invented percentage');
  check((await page.locator('.scc-card a').getAttribute('href')).includes('collection=visible'),'return to scoped SEO');
  const n=getCalls;let release;holdRead=new Promise(resolve=>release=resolve);
  await page.evaluate(()=>{window.scoutroPolls[0]();window.scoutroPolls[0]();window.scoutroPolls[0]();});
  await page.waitForTimeout(60);check(getCalls===n+1,'poll coalesces overlapping calls');release();await page.waitForTimeout(60);
  // New collection: id suggested from the display name, a duplicate refused without any phantom option, then created,
  // listed and selected at once, and the next crawl writes into it
  const options=()=>page.locator('#scc-collection option').evaluateAll(list=>list.map(o=>o.value));
  await page.locator('#scc-collection-new').click();
  check(await page.locator('#scoutro-collection-dialog').evaluate(d=>d.open),'dialog opens');
  await page.locator('#scoutro-collection-name').fill('Mein neues Portal');
  check(await page.locator('#scoutro-collection-id').inputValue()==='mein-neues-portal','id suggested from the display name');
  await page.locator('#scoutro-collection-cancel').click();
  check(!(await page.locator('#scoutro-collection-dialog').evaluate(d=>d.open))&&createPosts.length===0,'cancel creates nothing');
  await page.locator('#scc-collection-new').click();
  check(await page.locator('#scoutro-collection-name').inputValue()==='','dialog starts empty again');
  await page.locator('#scoutro-collection-name').fill('Alpha');await page.locator('#scoutro-collection-id').fill('alpha-web');
  await page.locator('#scoutro-collection-save').click();
  await page.locator('#scoutro-collection-error').waitFor({state:'visible'});
  check((await page.locator('#scoutro-collection-error').textContent()).length>10&&await page.locator('#scoutro-collection-dialog').evaluate(d=>d.open),'duplicate refused, dialog stays open');
  check(JSON.stringify(await options())===JSON.stringify(['','Alpha-web','visible']),'no phantom option after a refused creation');
  await page.locator('#scoutro-collection-id').fill('Bad Id');await page.locator('#scoutro-collection-save').click();
  check(createPosts.length===1,'an invalid id is refused before any request');
  await page.locator('#scoutro-collection-name').fill('Mein neues Portal');await page.locator('#scoutro-collection-id').fill('mein-neues-portal');
  await page.locator('#scoutro-collection-description').fill('Test');
  if(width===360){const box=await page.locator('#scoutro-collection-dialog').boundingBox();check(box&&box.x>=0&&box.x+box.width<=360,'dialog fits 360 px');}
  await page.locator('#scoutro-collection-save').click();
  await page.waitForFunction(()=>document.getElementById('scc-collection').value==='mein-neues-portal');
  check(!(await page.locator('#scoutro-collection-dialog').evaluate(d=>d.open)),'dialog closed after creation');
  check(JSON.stringify(createPosts[1])===JSON.stringify({id:'mein-neues-portal',name:'Mein neues Portal',description:'Test'}),'small JSON create request: '+JSON.stringify(createPosts[1]));
  check(JSON.stringify(await options())===JSON.stringify(['','Alpha-web','mein-neues-portal','visible']),'the list reloaded, sorted, with the new collection');
  check((await page.locator('#scc-collection option[value="mein-neues-portal"]').textContent()).includes('Mein neues Portal'),'listed with its display name');
  check((await page.locator('#scc-message').textContent()).includes('mein-neues-portal'),'creation confirmed');
  check(await page.locator('#scc-start').isEnabled()&&await page.locator('#scc-locked').isHidden(),'a changed request can be started');
  await page.locator('#scc-start').click();await page.waitForFunction(n=>document.querySelector('#scc-crawls').textContent.includes('mein-neues-portal'));
  check(posts.length===2&&posts[1].body.collection==='mein-neues-portal'&&posts[1].key!==posts[0].key,'the crawl starts into the new collection with a new idempotency key');
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'no horizontal page overflow');
  if(process.env.SCOUTRO_SCREENSHOTS){fs.mkdirSync(process.env.SCOUTRO_SCREENSHOTS,{recursive:true});await page.screenshot({path:`${process.env.SCOUTRO_SCREENSHOTS}/crawls-${language}-${width}.png`,fullPage:true});}
  check(errors.length===0,'no JS errors '+errors);await context.close();
 }
 // without the right to create (a user the catalog does not allow it), no button; a link's collection only if listed
 {
  const context=await browser.newContext({viewport:{width:360,height:800},httpCredentials:{username:'admin',password:'yacy'}});
  const page=await context.newPage();
  await page.route('**/scoutro/api/v1/collections',r=>r.request().method()==='POST'?json(r,{error:{code:'unauthorized',message:'x'}},401)
   :json(r,{collections:[{id:'visible',documents:28,selectable:true}],allowNew:false,canCreate:false,limit:500}));
  await page.route('**/scoutro/api/v1/crawls',r=>json(r,{crawls:[]}));
  await page.goto(base+'/ScoutroCrawls_p.html?url=https%3A%2F%2Fa.example%2F&collection=visible',{waitUntil:'networkidle'});
  check(await page.locator('#scc-collection').inputValue()==='visible','a listed collection of the link is chosen');
  check(await page.locator('#scc-collection-new').isHidden(),'no New collection without the right to create');
  await page.goto(base+'/ScoutroCrawls_p.html?url=https%3A%2F%2Fa.example%2F&collection=gone-web',{waitUntil:'networkidle'});
  await page.waitForFunction(()=>!document.getElementById('scc-collection').disabled);
  check(await page.locator('#scc-collection').inputValue()===''&&(await page.locator('#scc-message').textContent()).length>10,'an unknown collection of the link: choose one, said so');
  await context.close();
 }
 // package 6.3: every outcome of a start attempt as the backend reports it; writes mocked
 for(const [language,width] of [['de',360],['en',412]]) {
  const context=await browser.newContext({locale:language==='de'?'de-DE':'en-US',viewport:{width,height:900},isMobile:true,hasTouch:true,httpCredentials:{username:'admin',password:'yacy'},extraHTTPHeaders:{'Accept-Language':language}});
  await context.addInitScript(()=>{window.scoutroPolls=[];window.setInterval=(fn)=>{window.scoutroPolls.push(fn);return 1;};});
  const page=await context.newPage(),errors=[],posts=[];let answer=null,hold=null,list=[];const byId={};
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/scoutro/api/v1/collections',r=>json(r,{collections:[{id:'visible',documents:28,selectable:true}],allowNew:false,canCreate:false,limit:500}));
  await page.route('**/scoutro/api/v1/crawls',async r=>{
   if(r.request().method()==='GET')return json(r,{crawls:list});
   posts.push({body:r.request().postDataJSON(),key:r.request().headers()['idempotency-key']});
   if(hold){const wait=hold;hold=null;await wait;}
   const a=answer(posts.at(-1));if(a==='abort')return r.abort('connectionreset');return json(r,a.body,a.status);
  });
  await page.route('**/scoutro/api/v1/crawls/*',r=>{const id=new URL(r.request().url()).pathname.split('/').pop();
   return byId[id]?json(r,byId[id]):json(r,{error:{code:'crawl_not_found',message:'There is no crawl with the id.'}},404);});
  const crawl=(id,state,extra={})=>({id,url:'https://shop.example/',host:'shop.example',collection:'visible',scope:'domain',depth:2,maxPages:15,startedAt:'2026-10-07T08:00:00Z',state,progress:{pagesLoaded:null,total:null,percent:null},lastError:null,...extra});
  const failure=(status,code,message)=>({status,body:{error:{code,message}}});
  const label=k=>page.evaluate(k=>document.querySelector(`[data-scc-label="${k}"]`).textContent,k);
  const fact=k=>page.locator(`#scc-facts dd[data-fact="${k}"]`).textContent();
  const text=id=>page.locator('#scc-'+id).textContent();
  const start=async expected=>{const n=posts.length;await page.locator('#scc-start').click();
   await page.waitForFunction(e=>document.getElementById('scc-outcome').textContent===e&&document.getElementById('scc-result').getAttribute('aria-busy')==='false',await label(expected));
   await page.waitForTimeout(60);return posts.length-n;};
  const change=async url=>{await page.locator('#scc-url').fill(url);};
  await page.goto(base+'/ScoutroCrawls_p.html?url=https%3A%2F%2Fshop.example%2F&collection=visible',{waitUntil:'networkidle'});
  await page.waitForFunction(()=>document.getElementById('scc-collection').value==='visible');
  check(await page.locator('#scc-result').isHidden(),'no result before a start');
  // double click and a second submit while the start is on its way: one POST
  let release;hold=new Promise(r=>release=r);answer=()=>({status:201,body:crawl('p-one','running')});
  await page.locator('#scc-start').dblclick();
  await page.waitForFunction(()=>document.getElementById('scc-result').getAttribute('aria-busy')==='true');
  check(await text('outcome')===await label('starting')&&await page.locator('#scc-start').isDisabled()&&await page.locator('#scc-form').getAttribute('aria-busy')==='true','the start is shown as on its way, the button disabled');
  await page.evaluate(()=>document.getElementById('scc-form').requestSubmit());
  list=[crawl('p-one','running')];release();
  await page.waitForFunction(e=>document.getElementById('scc-outcome').textContent===e,await label('outcome_started'));await page.waitForTimeout(80);
  check(posts.length===1,'double click and a second submit send one POST: '+posts.length);
  check(await fact('fact_status')===await label('status_running')&&await fact('fact_id')==='p-one','started: running, profile id');
  // waiting exists only as the paused YaCy crawler; never a queue position
  await change('https://paused.example/');answer=()=>({status:201,body:crawl('p-paused','paused',{url:'https://paused.example/'})});list=[crawl('p-paused','paused',{url:'https://paused.example/'})];
  check(await start('outcome_started')===1&&await fact('fact_status')===await label('status_paused'),'started into a paused crawler: waiting');
  check(language!=='de'||(await fact('fact_status')).startsWith('Wartet'),'German: Wartet');
  check(!/eingereiht|warteschlange|queue/i.test(await text('result')),'waiting names no queue position');
  // rejected by YaCy: not started, no profile, the reason of the backend; a retry is a new attempt
  await change('https://refused.example/');list=[];
  answer=()=>failure(422,'crawl_rejected','YaCy did not start the crawl: Crawling of "https://refused.example/" failed. Reason: robots.txt');
  check(await start('outcome_rejected')===1,'rejected');
  check(await fact('fact_result')===await label('result_rejected')&&await fact('fact_status')===await label('status_not_started')&&await fact('fact_id')===await label('no_id'),'rejected: not started, no profile');
  check(await text('error')===await label('err_crawl_rejected')&&(await text('error-detail')).includes('HTTP 422')&&(await text('error-detail')).includes('robots.txt'),'an understandable reason and the backend message');
  check(await page.locator('#scc-result.scc-result-bad').count()===1&&await page.locator('#scc-start').isEnabled()&&await page.locator('#scc-status-link').isHidden(),'rejected: marked, a retry possible, no status link');
  check(!new URL(page.url()).searchParams.has('crawl'),'no crawl remembered for a rejected start');
  if(width===360)check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)&&(await page.locator('#scc-result').boundingBox()).width<=360,'the result with a long reason fits 360 px');
  answer=()=>failure(409,'host_busy','Another crawl is active on this host; its queued URLs must be retained.');
  check(await start('outcome_rejected')===1&&posts.at(-1).key!==posts.at(-2).key,'a retry after a refusal is a new attempt (new key)');
  check(await text('error')===await label('err_host_busy'),'host busy explained');
  answer=()=>failure(400,'collection_unknown','Unknown collection');
  check(await start('outcome_rejected')===1&&await text('error')===await label('err_collection_unknown'),'unknown collection explained');
  // backend error and no answer: failed, the key is kept, the retry is replayed by the backend and never starts twice
  await change('https://broken.example/');
  answer=()=>failure(502,'upstream_error','YaCy accepted the crawl request, but no new crawl profile appeared.');
  check(await start('outcome_failed')===1&&await text('error')===await label('err_upstream_error')&&(await text('error-detail')).includes('HTTP 502'),'backend error: failed, explained');
  check(await fact('fact_status')===await label('status_unknown'),'failed: status unknown, not claimed');
  answer=()=>'abort';
  check(await start('outcome_failed')===1&&await text('error')===await label('err_network')&&await page.locator('#scc-error-detail').isHidden(),'no answer: failed, explained');
  list=[crawl('p-net','running',{url:'https://broken.example/'})];answer=()=>({status:200,body:{...crawl('p-net','running',{url:'https://broken.example/'}),idempotentReplay:true}});
  check(await start('outcome_replayed')===1&&await fact('fact_result')===await label('result_replayed'),'the retry returns the crawl that had started: no second start');
  check(posts.at(-1).key===posts.at(-2).key&&posts.at(-2).key===posts.at(-3).key,'backend error, no answer and the retry share one key');
  check(await page.locator('#scc-start').isDisabled(),'the replayed start is locked as well');
  await change('https://unsure.example/');answer=()=>failure(503,'crawl_start_unconfirmed','YaCy may have started the crawl; retry only with the same Idempotency-Key.');
  check(await start('outcome_unconfirmed')===1&&await fact('fact_status')===await label('status_unknown')&&await page.locator('#scc-result.scc-result-open').count()===1,'unconfirmed: said so, not claimed as started or failed');
  // the status of the start follows the backend: finished, then the profile removed (GET crawls/{id})
  await change('https://live.example/');list=[crawl('p-live','running',{url:'https://live.example/'})];answer=()=>({status:201,body:list[0]});
  check(await start('outcome_started')===1,'started p-live');
  list=[crawl('p-live','terminated',{url:'https://live.example/',progress:{pagesLoaded:12,total:null,percent:null}})];
  await page.evaluate(()=>window.scoutroPolls[0]());await page.waitForFunction(e=>document.querySelector('#scc-facts dd[data-fact="fact_status"]').textContent===e,await label('status_terminated'));
  check(await fact('pages')==='12','finished, with the counted pages');
  list=[];byId['p-live']=crawl('p-live','removed',{url:'https://live.example/'});
  await page.evaluate(()=>window.scoutroPolls[0]());await page.waitForFunction(e=>document.querySelector('#scc-facts dd[data-fact="fact_status"]').textContent===e,await label('status_removed'));
  check(await page.locator('#scc-status-link').isHidden(),'no status link without a card');
  // after a reload the crawl of the start is read again from the backend
  byId['p-live']=crawl('p-live','running',{url:'https://live.example/'});list=[byId['p-live']];
  await page.goto(base+'/ScoutroCrawls_p.html?collection=visible&crawl=p-live',{waitUntil:'networkidle'});
  await page.waitForFunction(()=>!document.getElementById('scc-status-link').hidden);
  check(await text('outcome')===await label('outcome_restored')&&await fact('fact_id')==='p-live'&&await fact('fact_url')==='https://live.example/'&&await fact('fact_status')===await label('status_running'),'reload: the running crawl is found again');
  check(await page.locator('#scc-crawl-p-live.scc-card-current').count()===1&&await page.locator('#scc-start').isEnabled(),'reload: its card marked, the form free');
  await page.goto(base+'/ScoutroCrawls_p.html?crawl=gone-1',{waitUntil:'networkidle'});
  await page.waitForFunction(e=>document.getElementById('scc-outcome').textContent===e,await label('outcome_gone'));
  check(!new URL(page.url()).searchParams.has('crawl'),'an unknown crawl of the link is said so and forgotten');
  await page.goto(base+'/ScoutroCrawls_p.html?crawl=..%2Fsecret',{waitUntil:'networkidle'});
  check(await page.locator('#scc-result').isHidden(),'an invalid crawl id is not requested');
  check(errors.length===0,'no JS errors '+errors);await context.close();
 }
 // State combinations and late read protection are UI-tested against deterministic snapshots.
 const context=await browser.newContext({viewport:{width:390,height:900},httpCredentials:{username:'admin',password:'yacy'}});
 await context.addInitScript(()=>{window.scoutroPolls=[];window.setInterval=(fn)=>{window.scoutroPolls.push(fn);return 1;};});
 const page=await context.newPage();let revision=1,mode='disabled',batch=null,worker=true,late=null;
 const snapshot=()=>({revision,enabled:mode!=='disabled',paused:mode==='paused',automation_status:mode,running:!!batch&&['running','waiting_for_crawler'].includes(batch.phase),active_batch:batch,phase:batch?.phase||'idle',worker_busy:worker,heartbeat:{enabled:true,interval_minutes:1},job_count:0,last_run:null,waiting_reason:'idle',allowed_actions:mode==='disabled'?['enable']:mode==='paused'?['resume','disable']:['pause','disable']});
 await page.route('**/scoutro/api/v1/discovery/**',async route=>{
  const part=new URL(route.request().url()).pathname.split('/').pop();
  if(route.request().method()!=='GET'){mode=part==='enable'?'active':part==='pause'?'paused':part==='resume'?'active':'disabled';revision++;return json(route,{revision});}
  if(part==='catalog')return json(route,{profiles:[],sources:[]});
  const value=part==='jobs'?{revision,jobs:[]}:snapshot();
  if(late&&part==='status'){const l=late;late=null;await l;}
  try{return await json(route,value);}catch{/* abort of an obsolete request is expected */}
 });
 await page.goto(base+'/ScoutroDiscovery_p.html',{waitUntil:'networkidle'});
 check(await page.locator('#scd-global-status').textContent()==='Disabled','worker busy does not imply automation active');
 check(!(await page.locator('#scd-batch-status').textContent()).includes('Batch running'),'worker busy is not batch running');
 check(await page.locator('[data-scd-global=enable]').isVisible()&&!await page.locator('[data-scd-global=pause]').isVisible(),'disabled actions match state');
 await page.locator('[data-scd-global=enable]').click();await page.waitForFunction(()=>document.querySelector('#scd-global-status').textContent==='Active');
 check(!await page.locator('[data-scd-global=enable]').isVisible(),'active hides enable');
 batch={id:'uuid',job_id:'job',job_name:'Bau Test',collection:'test-web',phase:'waiting_for_crawler',started_at:1000};revision++;
 await page.evaluate(()=>window.scoutroPolls[0]());await page.waitForFunction(()=>document.querySelector('#scd-current-batch').textContent.includes('Bau Test'));
 check((await page.locator('#scd-current-batch').textContent()).includes('test-web')&&!(await page.locator('#scd-current-batch').textContent()).includes('uuid'),'batch name/collection instead of UUID');
 check(await page.locator('#scd-batch-status').textContent()==='Batch running','real batch is running');
 await page.locator('[data-scd-global=pause]').click();await page.waitForFunction(()=>document.querySelector('#scd-global-status').textContent==='Paused');
 check(await page.locator('[data-scd-global=resume]').isVisible(),'paused offers resume');
 check(await page.locator('#scd-batch-status').textContent()==='Batch running','paused automation retains active batch');
 batch={...batch,phase:'needs_review'};revision++;await page.evaluate(()=>window.scoutroPolls[0]());await page.waitForFunction(()=>document.querySelector('#scd-batch-status').textContent!=='Batch running');check(true,'blocked batch remains distinct');
 let release;late=new Promise(resolve=>release=resolve);await page.evaluate(()=>{window.scoutroPolls[0]();});await page.waitForTimeout(60);
 const response=page.waitForResponse(r=>r.url().endsWith('/resume'));const sent=page.waitForRequest(r=>r.url().endsWith('/resume'));const click=page.locator('[data-scd-global=resume]').click();await sent;release();await click;await response;
 await page.waitForFunction(()=>document.querySelector('#scd-global-status').textContent==='Active');await page.waitForTimeout(60);
 check(await page.locator('#scd-global-status').textContent()==='Active','late preceding read never restores paused state');
 await context.close();console.log(`PASS: ${count} crawl/Discovery flow UI checks; English/German, 360/390/412/1280; writes mocked`);
}finally{await browser.close();}
