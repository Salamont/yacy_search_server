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
  await page.locator('#scc-start').click();await page.waitForFunction(()=>document.querySelector('#scc-crawls').textContent.includes('visible'));
  check(posts.length===1 && posts[0].body.collection==='visible' && posts[0].body.scope==='subpath' && posts[0].body.maxPages===15 && posts[0].body.depth===2,'shared JSON contract');
  await page.locator('#scc-start').click();await page.waitForFunction(()=>document.querySelector('#scc-message').textContent.includes('No second start')||document.querySelector('#scc-message').textContent.includes('Kein zweiter Start'));
  check(posts.length===2&&posts[0].key===posts[1].key&&keys.size===1,'double submit retains idempotency key');
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
  await page.locator('#scc-start').click();await page.waitForFunction(n=>document.querySelector('#scc-crawls').textContent.includes('mein-neues-portal'));
  check(posts.length===3&&posts[2].body.collection==='mein-neues-portal'&&posts[2].key!==posts[0].key,'the crawl starts into the new collection with a new idempotency key');
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
