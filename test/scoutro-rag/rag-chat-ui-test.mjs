/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; disposable RAG fixture peer only.
 * Started by rag-quality.py --ui: collection field, numbered citations linked to the sources of
 * the answer, invented citations marked, earlier search documents not sent again. */
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const require=createRequire(import.meta.url), {chromium}=require('playwright');
const base=process.env.SCOUTRO_URL, shots=process.env.SCOUTRO_SCREENSHOTS;
assert.ok(/^http:\/\/127\.0\.0\.1:\d+$/.test(base));
const browser=await chromium.launch({executablePath:process.env.SCOUTRO_CHROMIUM_PATH,args:['--no-proxy-server']});
let checks=0;
function check(v,m){assert.ok(v,m);checks++;}
const searchDocs=messages=>messages.filter(m=>Array.isArray(m.content)&&m.content.some(p=>p.type==='image_url'&&String(p.filename||'').startsWith('scoutro-sources'))).length;
try {
 for (const width of [390,1280]) {
  const context=await browser.newContext({locale:'en',viewport:{width,height:900},isMobile:width<768,hasTouch:width<768});
  await context.route('**/*',route => route.request().url().startsWith(base) ? route.continue() : route.abort());
  const page=await context.newPage(),errors=[],payloads=[],metas=[];
  page.on('pageerror',e=>errors.push(e.message));
  if(process.env.SCOUTRO_DEBUG){page.on('console',m=>console.error('console',m.type(),m.text()));page.on('request',r=>console.error('request',r.method(),r.url()));}
  page.on('request',r=>{if(r.url()===base+'/v1/chat/completions'&&r.method()==='POST')payloads.push(JSON.parse(r.postData()||'{}'));});
  page.on('response',async r=>{if(r.url()===base+'/v1/chat/completions'){const body=await r.text().catch(()=>'');
   for(const line of body.split('\n'))if(line.startsWith('data:')&&!line.includes('[DONE]')){try{const e=JSON.parse(line.slice(5));if(e['scoutro-sources']||e['scoutro-citations'])metas.push(e);}catch(_){}}}});
  check((await page.goto(base+'/yacychat.html?collection=edelsenior-web',{waitUntil:'networkidle'})).status()===200,'Chat page');
  check(await page.locator('#collectionInput').inputValue()==='edelsenior-web','Collection from the URL parameter');

  // turn 1: the default dialog augmentation searches every turn
  await page.waitForSelector('#searchButton.button-active');
  await page.locator('#userInput').fill('Welche Pflegeheime gibt es in Köln?');
  let done=page.waitForResponse(r=>r.url()===base+'/v1/chat/completions'&&r.status()===200);
  await page.locator('#sendButton').click();await done;
  await page.waitForFunction(()=>document.querySelectorAll('#chatMessages .chat-citation-warning').length===1);
  const turn1=payloads.at(-1);
  check(turn1.collection==='edelsenior-web','Request carries the collection');
  check(turn1.messages.at(-1).search==='local','Turn 1 searches');
  const sourcesEvent=metas.find(e=>Array.isArray(e['scoutro-sources']));
  check(sourcesEvent&&sourcesEvent['scoutro-sources'].length>=2,'Sources of the answer delivered with the stream');
  const sources=sourcesEvent['scoutro-sources'];
  const answer=page.locator('#chatMessages .chat-turn.assistant').last();
  const links=await answer.locator('a.chat-citation').evaluateAll(as=>as.map(a=>({text:a.textContent,href:a.href})));
  check(links.length===2&&links[0].text==='[1]'&&links[1].text==='[2]','Citations [1] and [2] are links: '+JSON.stringify(links));
  check(links[0].href===new URL(sources[0].url).href&&links[1].href===new URL(sources[1].url).href,'Citation links point at the retrieved sources');
  check((await answer.locator('.chat-citation-invalid').allTextContents()).join()==='[9?]','Invented [9] is marked, not linked');
  const list=await answer.locator('.chat-sources li a').evaluateAll(as=>as.map(a=>a.href));
  check(list.length===sources.length&&list.every((href,i)=>href===new URL(sources[i].url).href),'Source list equals the retrieved sources');
  for (const s of sources) check(/\.example\//.test(s.url)&&s.collection==='edelsenior-web','Source from edelsenior-web: '+s.url);
  const warning=await answer.locator('.chat-citation-warning').innerText();
  check(warning.includes('[9]')&&warning.includes('https://erfunden.example/quelle'),'Warning names the invented number and URL');
  check(!(await answer.locator('a[href^="https://erfunden.example"]').count()),'Invented URL is never linked');
  check((await answer.locator('.chat-link-unverified').allTextContents()).join().includes('erfunden.example'),'Invented URL is shown as unverified');

  // turn 2: search switched off for a follow-up keeps only the latest search document
  await page.waitForSelector('#searchButton.button-active');
  await page.locator('#searchButton').click();
  check(!(await page.locator('#searchButton.button-active').count()),'Search switched off for this turn');
  await page.locator('#userInput').fill('Welches davon hat einen Garten?');
  done=page.waitForResponse(r=>r.url()===base+'/v1/chat/completions'&&r.status()===200);
  await page.locator('#sendButton').click();await done;
  await page.waitForFunction(()=>document.querySelectorAll('#chatMessages .chat-citation-warning').length===2);
  const turn2=payloads.at(-1);
  check(!turn2.messages.at(-1).search,'Turn 2 does not search');
  check(searchDocs(turn2.messages)===1,'Turn 2 sends the latest search document once');
  const turn2answer=page.locator('#chatMessages .chat-turn.assistant').last();
  check(await turn2answer.locator('a.chat-citation').count()===2,'Follow-up citations link to the kept sources');

  // turn 3: searching again sends no earlier search document
  await page.waitForSelector('#searchButton.button-active');
  await page.locator('#userInput').fill('Gibt es ein Hospiz in Düsseldorf?');
  done=page.waitForResponse(r=>r.url()===base+'/v1/chat/completions'&&r.status()===200);
  await page.locator('#sendButton').click();await done;
  await page.waitForFunction(()=>document.querySelectorAll('#chatMessages .chat-citation-warning').length===3);
  check(searchDocs(payloads.at(-1).messages)===0,'A new search sends no earlier search document');
  const turn3=page.locator('#chatMessages .chat-turn.assistant').last();
  const sources3=await turn3.locator('.chat-sources li').count();
  check(sources3===1,'One hit: one source, got '+sources3);
  const invalid3=(await turn3.locator('.chat-citation-invalid').allTextContents()).join('');
  check(invalid3==='[2?][9?]','Citations beyond the single source are marked: '+invalid3);

  // an invalid collection name is not applied
  await page.locator('#collectionInput').fill('nicht gültig!');
  await page.locator('#collectionInput').dispatchEvent('change');
  check(await page.evaluate(()=>document.activeElement?.id==='collectionInput'&&!document.getElementById('collectionInput').checkValidity()),'Invalid collection is reported at the field');
  await page.waitForFunction(()=>!document.getElementById('sendButton').disabled);
  await page.locator('#collectionInput').press('Escape');
  await page.locator('#userInput').click();
  await page.locator('#userInput').fill('Danke');
  check(await page.locator('#userInput').inputValue()==='Danke','Question typed');
  done=page.waitForResponse(r=>r.url()===base+'/v1/chat/completions',{timeout:60000});
  await page.locator('#sendButton').click();
  const turn4=await done;
  check(turn4.status()===200,'Turn 4: '+turn4.status()+' '+(await page.locator('#chatMessages').innerText()).slice(-400));
  check(payloads.at(-1).collection==='edelsenior-web','Invalid collection input keeps the last valid collection');
  // clearing the field: the whole index, as before
  await page.locator('#collectionInput').fill('');
  await page.locator('#collectionInput').dispatchEvent('change');
  await page.locator('#userInput').fill('Und sonst?');
  done=page.waitForResponse(r=>r.url()===base+'/v1/chat/completions'&&r.status()===200);
  await page.locator('#sendButton').click();await done;
  check(!('collection' in payloads.at(-1)),'Empty collection: no collection field');

  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth);
  check(overflow<=width+1,'Chat fits the viewport: '+overflow);
  check(errors.length===0,JSON.stringify(errors));
  if(shots){fs.mkdirSync(shots,{recursive:true});await page.screenshot({path:`${shots}/rag-chat-${width}.png`,fullPage:true});}
  await context.close();
 }
 console.log(`PASS: ${checks} RAG chat UI checks (collection, citations, sources, follow-up turns) at 390/1280`);
} finally {await browser.close();}
