#!/usr/bin/env node
/* Disposable peer only. Regression for rejected asynchronous role saves. GPL-2.0-or-later. */
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
import {withDigestSignIn} from './digest-signin.mjs';
const {chromium}=createRequire(import.meta.url)('playwright');
const base=process.env.SCOUTRO_URL, phase=process.env.SCOUTRO_BLOCKER_PHASE;
assert(base && new URL(base).hostname==='127.0.0.1');
const browser=await chromium.launch({executablePath:process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium',args:['--no-proxy-server']});
withDigestSignIn(browser,base);
let checks=0;
try {
 for(const width of phase==='failed'?[390,1280]:[390]) {
  const context=await browser.newContext({locale:'de-DE',viewport:{width,height:900},isMobile:width<700,hasTouch:width<700,httpCredentials:{username:'admin',password:'yacy'}});
  const page=await context.newPage(); await page.goto(base+'/LLMSelection_p.html');
  const row=page.locator('#productionModelsTable tbody tr').filter({has:page.locator('td:nth-child(2):text-is("shared")')});
  const role=col=>row.locator('td').nth(col).locator('input[type="checkbox"]');
  assert.equal(await page.locator('#productionModelsTable tbody tr').count(),3);checks++;
  assert(await role(13).isChecked());checks++;
  const reject=async column=>{
   const [response]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST' && new URL(r.url()).pathname==='/LLMSelection_p.html'
    && r.request().postDataJSON()?.production_models?.some(m=>m.model==='shared' && m[column===11?'tldr':'logreport']===false)),
    role(column).uncheck()]);
   assert.equal(response.status(),phase==='http'?503:500);checks++;
   await page.locator('#modelSaveError').waitFor({state:'visible'});checks++;
   await page.waitForFunction(col=>document.querySelector('#productionModelsTable tbody tr').cells[col].querySelector('input').checked,column);checks++;
   assert((await page.locator('#modelSaveError').innerText()).includes('bisherigen Einstellungen'));checks++;
  };
  if(phase==='http') {
   await page.route('**/LLMSelection_p.html?*',route=>route.request().method()==='POST'
    ?route.fulfill({status:503,body:'private-upstream-body sk-fake-secret-sentinel'}):route.continue());
   await reject(11);
   assert(!(await page.locator('body').innerText()).includes('private-upstream-body'));checks++;
   await page.unroute('**/LLMSelection_p.html?*');
   await page.route('**/LLMSelection_p.html?*',route=>route.request().method()==='POST'?route.abort():route.continue());
   await role(12).uncheck(); await page.locator('#modelSaveError').waitFor({state:'visible'});
   await page.waitForFunction(()=>document.querySelector('#productionModelsTable tbody tr').cells[12].querySelector('input').checked);checks++;
  } else if(phase==='failed') {
   await reject(11);await reject(12);await page.reload();
   assert(await role(11).isChecked() && await role(12).isChecked());checks++;
  } else {
   for(const column of [11,12]) {
    const [response]=await Promise.all([page.waitForResponse(r=>r.request().method()==='POST' && new URL(r.url()).pathname==='/LLMSelection_p.html'
      && r.request().postDataJSON()?.production_models?.some(m=>m.model==='shared' && m[column===11?'tldr':'logreport']===false)),
      role(column).uncheck()]);
    assert.equal(response.status(),200);checks++;
   }
   await page.evaluate(()=>modelSettingsSave);await page.reload();
   for(const column of [11,12]) {assert.equal(await page.locator(`#productionModelsTable tbody tr td:nth-child(${column+1}) input:checked`).count(),0);checks++;}
   assert(await page.locator('#modelSaveError').isHidden());checks++;
  }
  assert(await role(13).isChecked());checks++;
  assert(!(await page.locator('body').innerText()).includes('sk-fixture-secret'));checks++;
  await context.close();
 }
} finally {await browser.close();}
console.log(`PASS: ${checks} blocker UI ${phase} checks`);
