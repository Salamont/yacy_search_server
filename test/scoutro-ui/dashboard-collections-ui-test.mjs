#!/usr/bin/env node
/* Scoutro contributors, GPL-2.0-or-later. The administrator's "+" and "−" of the dashboard's collection list.
   Called only by dashboard-live-smoke.py on a disposable peer: creates and removes one collection of its own. */
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { withDigestSignIn } from './digest-signin.mjs';
const require = createRequire(import.meta.url);
const {chromium} = require('playwright');
const base = process.env.SCOUTRO_URL;
if (!base || !/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw new Error('Disposable loopback peer required');
const shots = process.argv.includes('--screenshots') ? process.argv[process.argv.indexOf('--screenshots') + 1] : process.env.SCOUTRO_SCREENSHOTS;
const browser = await chromium.launch({executablePath:process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium',args:['--no-proxy-server']});
withDigestSignIn(browser, base);
let checks=0; const check=(value,label)=>{assert.ok(value,label);checks++;};
const shot=async(page,name)=>{if(shots){fs.mkdirSync(shots,{recursive:true});await page.screenshot({path:path.join(shots,name),fullPage:true});}};
const credentials={username:process.env.SCOUTRO_ADMIN_USER||'admin',password:process.env.SCOUTRO_ADMIN_PASSWORD||'yacy'};
const card=id=>`[data-collection="${id}"]`;
try {
  // English desktop: create through "+", remove through "−"
  let context = await browser.newContext({viewport:{width:1280,height:900},locale:'en-US',httpCredentials:credentials});
  let page = await context.newPage(); let errors=[]; page.on('pageerror',e=>errors.push(String(e)));
  check((await page.goto(base+'/scoutro-dashboard.html')).status()===200,'en: dashboard');
  await page.locator('#scoutro-dash-collection-tools').waitFor({state:'visible'});
  const add=page.locator('#scoutro-dash-collection-add'), remove=page.locator('#scoutro-dash-collection-remove');
  check(await add.getAttribute('aria-label')==='New collection' && (await add.textContent()).trim()==='+','en: "+" named New collection');
  check(await remove.getAttribute('aria-label')==='Remove collection' && (await remove.textContent()).trim()==='−','en: "−" named Remove collection');
  await shot(page,'dashboard-collections-1280-en-US.png');
  await add.click();
  await page.locator('#scoutro-collection-dialog').waitFor({state:'visible'});
  await page.locator('#scoutro-collection-name').fill('UI Test Portal');
  check(await page.locator('#scoutro-collection-id').inputValue()==='ui-test-portal','en: id suggested from the name');
  await Promise.all([page.waitForNavigation(), page.locator('#scoutro-collection-save').click()]);
  await page.locator(card('ui-test-portal')).waitFor();
  check((await page.locator(card('ui-test-portal')).textContent()).includes('UI Test Portal'),'en: the new collection is a card');
  check((await page.locator('#scoutro-dash-collection-status').textContent()).trim()==='Collection created: UI Test Portal · ui-test-portal','en: creation confirmed after the reload');
  // "−": only empty collections created here can be chosen; the others say why not
  await page.locator('#scoutro-dash-collection-remove').click();
  await page.locator('#scoutro-dash-remove-dialog').waitFor({state:'visible'});
  const select=page.locator('#scoutro-dash-remove-id');
  await page.waitForFunction(()=>!document.getElementById('scoutro-dash-remove-id').disabled);
  check(await select.locator('option[value="ui-test-portal"]').evaluate(o=>!o.disabled),'en: the new, empty collection can be removed');
  const kept=await select.locator('option[value="stackfinder-web"]');
  check(await kept.evaluate(o=>o.disabled) && (await kept.textContent()).includes('2 pages'),'en: a collection with pages is listed as kept, with the reason');
  await shot(page,'dashboard-collections-remove-1280-en-US.png');
  await page.locator('#scoutro-dash-remove-cancel').click();
  check(!(await page.locator('#scoutro-dash-remove-dialog').isVisible()) && await page.locator(card('ui-test-portal')).count()===1,'en: cancel removes nothing');
  await page.locator('#scoutro-dash-collection-remove').click();
  await page.waitForFunction(()=>!document.getElementById('scoutro-dash-remove-id').disabled);
  await select.selectOption('ui-test-portal');
  await Promise.all([page.waitForNavigation(), page.locator('#scoutro-dash-remove-save').click()]);
  await page.locator('#scoutro-dash-collection-status').waitFor({state:'visible'});
  check(await page.locator(card('ui-test-portal')).count()===0,'en: the removed collection has no card');
  check((await page.locator('#scoutro-dash-collection-status').textContent()).trim()==='Collection removed: UI Test Portal · ui-test-portal','en: removal confirmed after the reload');
  check(await page.locator(card('stackfinder-web')).count()===1,'en: the other collections stay');
  check(errors.length===0,`en: no JS errors ${errors}`);
  await context.close();
  // phone: the same controls fit (German labels: accounts-ui-test.mjs, which runs the peer with the browser language)
  context = await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,locale:'en-US',httpCredentials:credentials});
  page = await context.newPage(); errors=[]; page.on('pageerror',e=>errors.push(String(e)));
  check((await page.goto(base+'/scoutro-dashboard.html')).status()===200,'390: dashboard');
  await page.locator('#scoutro-dash-collection-tools').waitFor({state:'visible'});
  const box=await page.locator('#scoutro-dash-collection-remove').boundingBox();
  check(box && box.width>=40 && box.height>=40,'390: "−" is a 40 px touch target');
  await page.locator('#scoutro-dash-collection-remove').tap();
  await page.locator('#scoutro-dash-remove-dialog').waitFor({state:'visible'});
  await page.waitForFunction(()=>document.querySelectorAll('#scoutro-dash-remove-id option').length>1);
  check((await page.locator('#scoutro-dash-remove-title').textContent()).trim()==='Remove collection','390: dialog title');
  check((await page.locator('#scoutro-dash-remove-id optgroup').getAttribute('label'))==='Cannot be removed','390: kept collections grouped');
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),'390: no horizontal overflow');
  await shot(page,'dashboard-collections-remove-390-en-US.png');
  await page.locator('#scoutro-dash-remove-cancel').tap();
  check(errors.length===0,`390: no JS errors ${errors}`);
  await context.close();
  console.log(`PASS: ${checks} dashboard collection +/− UI checks`);
} finally { await browser.close(); }
