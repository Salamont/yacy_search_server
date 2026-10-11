#!/usr/bin/env node
/* Scoutro contributors, GPL-2.0-or-later. Called only by discovery-recovery-live-smoke.py on a disposable peer. */
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { withDigestSignIn } from './digest-signin.mjs';
const require = createRequire(import.meta.url);
const {chromium} = require('playwright');
const base = process.env.SCOUTRO_URL;
if (!base || !/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw new Error('Disposable loopback peer required');
const browser = await chromium.launch({executablePath:process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium',args:['--no-proxy-server']});
withDigestSignIn(browser);
let checks=0; const check=(value,label)=>{assert.ok(value,label);checks++;};
const shot=async(page,name)=>{if(process.env.SCOUTRO_SCREENSHOTS){fs.mkdirSync(process.env.SCOUTRO_SCREENSHOTS,{recursive:true});await page.screenshot({path:path.join(process.env.SCOUTRO_SCREENSHOTS,name),fullPage:true});}};
try {
  // a narrow English view first: the panel is readable and dismissing the dialog changes nothing
  const narrow = await browser.newContext({viewport:{width:360,height:900},locale:'en-US',httpCredentials:{username:'admin',password:'yacy'}});
  let page = await narrow.newPage(); let errors=[]; page.on('pageerror',e=>errors.push(String(e)));
  check((await page.goto(base+'/ScoutroDiscovery_p.html')).status()===200,'360: authenticated page');
  await page.locator('#scd-review').waitFor({state:'visible'});
  check(await page.locator('#scd-review-title').textContent()==='Unconfirmed crawl start','360: review panel title');
  const item=page.locator('#scd-review-list li');
  check(await item.count()===1 && (await item.textContent()).includes('codecamp-n.com'),'360: the unconfirmed start is named');
  check(await item.locator('button').textContent()==='Defer start (outcome stays unknown)','360: the action says the outcome stays unknown');
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),'360: no horizontal overflow');
  let prompt='';
  page.once('dialog',dialog=>{prompt=dialog.message();dialog.dismiss();});
  await item.locator('button').click();
  await page.waitForTimeout(500);
  check(prompt.includes('codecamp-n.com') && prompt.includes('not started again automatically'),`360: the confirmation names the website and the consequence: ${prompt}`);
  await page.reload(); await page.locator('#scd-review').waitFor({state:'visible'});
  check(await page.locator('#scd-review-list li').count()===1,'360: dismissing the dialog defers nothing');
  await shot(page,'discovery-recovery-360-en-US.png');
  check(errors.length===0,`360: no JS errors ${errors}`);
  await narrow.close();
  // the administrator defers in German
  const context = await browser.newContext({viewport:{width:1280,height:900},locale:'de-DE',httpCredentials:{username:'admin',password:'yacy'}});
  page = await context.newPage(); errors=[]; page.on('pageerror',e=>errors.push(String(e)));
  check((await page.goto(base+'/ScoutroDiscovery_p.html')).status()===200,'1280: authenticated page');
  await page.locator('#scd-review').waitFor({state:'visible'});
  check(await page.locator('#scd-review-title').textContent()==='Unbestätigter Crawl-Start','de: review panel title');
  const action=page.locator('#scd-review-list li button');
  check(await action.textContent()==='Start zurückstellen (Ausgang bleibt unbekannt)','de: action label');
  await shot(page,'discovery-recovery-1280-de-DE-before.png');
  page.once('dialog',dialog=>{prompt=dialog.message();dialog.accept();});
  await action.click();
  await page.locator('#scd-review').waitFor({state:'hidden'});
  check(prompt.startsWith('Den unbestätigten Crawl-Start für codecamp-n.com zurückstellen?'),`de: confirmation ${prompt}`);
  await page.waitForFunction(()=>[...document.querySelectorAll('.scd-message')].some(e=>e.textContent.includes('Crawl-Start zurückgestellt; Discovery arbeitet weiter.')));
  check(true,'de: deferral confirmed to the administrator');
  const card=page.locator('.scd-job').filter({has:page.locator('h3',{hasText:'Prod StackFinder Sued-West'})});
  await card.locator('dt',{hasText:'Zurückgestellte Starts'}).waitFor();
  check((await card.textContent()).includes('codecamp-n.com'),'de: the job card lists the deferred start');
  await shot(page,'discovery-recovery-1280-de-DE-after.png');
  check(errors.length===0,`1280: no JS errors ${errors}`);
  await context.close();
  console.log(`PASS: ${checks} Discovery deferral UI checks`);
} finally {await browser.close();}
