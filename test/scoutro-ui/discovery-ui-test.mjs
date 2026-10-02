#!/usr/bin/env node
/* Scoutro contributors, GPL-2.0-or-later. Called only by the disposable harness. */
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
const require = createRequire(import.meta.url);
const {chromium} = require('playwright');
const base = process.env.SCOUTRO_URL;
if (!base || !/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw new Error('Disposable loopback peer required');
const browser = await chromium.launch({executablePath:process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium',args:['--no-proxy-server']});
let checks=0; const check=(value,label)=>{assert.ok(value,label);checks++;};
try {
  for (const [width,language] of [[1280,'en-US'],[390,'de-DE'],[360,'de-DE'],[768,'de-DE']]) {
    const context = await browser.newContext({viewport:{width,height:900},locale:language,httpCredentials:{username:'admin',password:'yacy'}});
    const page = await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(String(e)));
    const response=await page.goto(base+'/ScoutroDiscovery_p.html'); check(response.status()===200,`${width}: authenticated page`);
    await page.waitForFunction(()=>document.querySelector('#scd-job-count').textContent==='1');
    const nav=page.locator('#scoutro-adminnav a[href="ScoutroDiscovery_p.html"]');
    if(width<768) await page.locator('[aria-controls="scoutro-adminnav"]').click();
    check(await nav.isVisible(),`${width}: shared navigation visible`);
    check(await nav.textContent().then(t=>t.includes(language==='de-DE'?'Discovery-Automatisierung':'Discovery Automation')),`${width}: localized navigation`);
    if(width<768)await page.keyboard.press('Escape');
    check(await page.locator('#scd-global-status').textContent()===(language==='de-DE'?'Deaktiviert':'Disabled'),`${width}: disabled/localized status`);
    await page.locator('#scd-add').click(); await page.locator('#scd-editor').waitFor({state:'visible'});
    check(await page.locator('#scd-profile option').allTextContents().then(a=>a.join(',')==='new_profile,unsupported'),`${width}: dynamic profiles`);
    const osm=page.locator('[data-source-id="osm"]'),freeworld=page.locator('[data-source-id="freeworld"]');
    check(await osm.locator('.scd-region-list').textContent().then(t=>t.includes('Test-Region')&&!t.includes('Teststadt')),`${width}: OSM separate regions`);
    check(await freeworld.locator('.scd-region-list').textContent().then(t=>t.includes('Teststadt')&&!t.includes('Test-Region')),`${width}: Freeworld separate regions`);
    check(await page.locator('#scd-process-fresh').isChecked()&&!await page.locator('#scd-process-retry').isChecked()&&!await page.locator('#scd-process-recrawl').isChecked(),`${width}: conservative processing defaults`);
    check(await page.evaluate(w=>document.documentElement.scrollWidth<=w+1,width),`${width}: no horizontal overflow`);
    await page.locator('#scd-profile').selectOption('unsupported');
    check(await page.locator('[data-source-enabled="osm"]').isDisabled()&&await page.locator('[data-source-enabled="freeworld"]').isDisabled(),`${width}: capabilities disable unsupported source`);
    await page.locator('#scd-profile').selectOption('new_profile');
    await page.locator('#scd-name').fill('UI disposable');
    await page.locator('[data-source-enabled="osm"]').check();
    await osm.locator('input[value="test-region"]').check();
    await page.locator('[data-source-enabled="freeworld"]').check();
    await freeworld.locator('input[value="Andere Stadt"]').check();
    await page.locator('#scd-job-form button[type="submit"]').click();
    await page.waitForFunction(()=>document.querySelector('#scd-job-count').textContent==='2' && [...document.querySelectorAll('.scd-job h3')].some(e=>e.textContent==='UI disposable'));
    check(await page.locator('.scd-job h3').allTextContents().then(a=>a.includes('UI disposable')),`${width}: UI create`);
    const row=page.locator('.scd-job').filter({has:page.locator('h3',{hasText:'UI disposable'})});
    await row.locator('button').first().click(); await page.locator('#scd-editor').waitFor({state:'visible'});
    check(await osm.locator('input[value="test-region"]').isChecked()&&await freeworld.locator('input[value="Andere Stadt"]').isChecked(),`${width}: edit round trip source regions`);
    await page.locator('#scd-name').fill('UI edited'); await page.locator('#scd-job-form button[type="submit"]').click();
    await page.waitForFunction(()=>[...document.querySelectorAll('.scd-job h3')].some(e=>e.textContent==='UI edited'));
    check(true,`${width}: UI edit`);
    await page.evaluate(()=>Object.defineProperty(crypto,'randomUUID',{configurable:true,value:undefined}));
    const refusedRun = page.waitForResponse(r=>r.url().endsWith('/run')&&r.request().method()==='POST');
    await page.locator('.scd-job').filter({has:page.locator('h3',{hasText:'UI edited'})}).locator('button').nth(3).click();
    check((await refusedRun).status()===409,`${width}: Run once works without secure-context UUID and is blocked while globally disabled`);
    page.once('dialog',dialog=>dialog.accept());
    await page.locator('.scd-job').filter({has:page.locator('h3',{hasText:'UI edited'})}).locator('button').last().click();
    await page.waitForFunction(()=>document.querySelector('#scd-job-count').textContent==='1' && document.querySelectorAll('.scd-job').length===1);
    check(true,`${width}: UI delete`);check(errors.length===0,`${width}: no JS errors ${errors}`);
    if(process.env.SCOUTRO_SCREENSHOTS){await page.reload();await page.waitForFunction(()=>document.querySelector('#scd-job-count').textContent==='1');await page.evaluate(()=>window.scrollTo(0,0));fs.mkdirSync(process.env.SCOUTRO_SCREENSHOTS,{recursive:true});await page.screenshot({path:path.join(process.env.SCOUTRO_SCREENSHOTS,`discovery-${width}-${language}.png`),fullPage:true});}
    await context.close();
  }
  console.log(`PASS: ${checks} Discovery desktop/mobile/German UI checks`);
} finally {await browser.close();}
