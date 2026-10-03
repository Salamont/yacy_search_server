/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; disposable system data only. */
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const require=createRequire(import.meta.url), {chromium}=require('playwright');
const base=process.env.SCOUTRO_URL, shots=process.env.SCOUTRO_SCREENSHOTS;
assert.ok(/^http:\/\/127\.0\.0\.1:\d+$/.test(base));
const browser=await chromium.launch({executablePath:process.env.SCOUTRO_CHROMIUM_PATH,args:['--no-proxy-server']});
let checks=0;
function check(v,m){assert.ok(v,m);checks++;}
try {
 for (const language of ['en','de']) for (const width of [360,390,412,768,1280]) {
  const context=await browser.newContext({locale:language,viewport:{width,height:900},isMobile:width<768,hasTouch:width<768,httpCredentials:{username:'admin',password:'yacy'}});
  await context.route('**/*',route => route.request().url().startsWith(base) ? route.continue() : route.abort());
  const page=await context.newPage(),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  check((await page.goto(base+'/yacychat.html',{waitUntil:'networkidle'})).status()===200,'Chat shell');
  await page.locator('#userInput').fill('Wie viele Seiten?');
  const reply=page.waitForResponse(r=>r.url()===base+'/v1/chat/completions' && r.status()===200);
  await page.locator('#sendButton').click();
  const response=await reply;
  check((await response.headerValue('content-type')).includes('text/event-stream'),'Real SSE, no model configured');
  await page.waitForFunction(()=>document.getElementById('chatMessages').textContent.includes('30 Dokumente'));
  check((await page.locator('#chatMessages').innerText()).includes('Scoutro-Systemdaten'),'Structured answer visible');
  const overflow=await page.evaluate(()=>({width:document.documentElement.scrollWidth,items:Array.from(document.querySelectorAll('body *')).filter(e=>e.getBoundingClientRect().right>innerWidth+1).slice(0,8).map(e=>({tag:e.tagName,cls:e.className,right:e.getBoundingClientRect().right}))}));
  check(overflow.width<=width+1,'Chat/results fit viewport: '+JSON.stringify(overflow));
  await page.locator('#userInput').fill('/system unknown');
  const failure=page.waitForResponse(r=>r.url()===base+'/v1/chat/completions' && r.status()===400);
  await page.locator('#sendButton').click();await failure;
  await page.waitForFunction(()=>document.getElementById('chatMessages').textContent.includes('no web answer is substituted'));
  check((await page.locator('#chatMessages').innerText()).includes('no web answer is substituted'),'Unsupported system question displays clear API error');
  check(await page.locator('#chatMessages .chat-turn.assistant').count()===1,'Failed system reply leaves no search/wait placeholder');
  check(errors.length===0,JSON.stringify(errors));
  if(shots){fs.mkdirSync(shots,{recursive:true});await page.screenshot({path:`${shots}/system-chat-${language}-${width}.png`,fullPage:true});}
  await context.close();
 }
 const anonymous=await browser.newContext({viewport:{width:390,height:844}});
 const page=await anonymous.newPage();await page.goto(base+'/yacychat.html',{waitUntil:'networkidle'});
 // Exercise the authentication explanation independently of browser HTTP-auth prompts.
 await page.route('**/v1/chat/completions',r=>r.fulfill({status:401,contentType:'text/html',body:'authentication required'}));
 await page.locator('#userInput').fill('How many pages?');await page.locator('#sendButton').click();
 await page.waitForFunction(()=>document.getElementById('chatMessages').textContent.includes('System data requires'));
 check((await page.locator('#chatMessages').innerText()).includes('No web answer is substituted'),'Authentication denial is explicit');
 await anonymous.close();
 console.log(`PASS: ${checks} system chat UI checks, DE/EN, 360/390/412/768/1280, real authorized SSE and visible errors`);
} finally {await browser.close();}
