#!/usr/bin/env node
/* Scoutro contributors, GPL-2.0-or-later. Run only from disposable seo-live-smoke.py. */
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium} = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
assert(base && new URL(base).hostname === '127.0.0.1', 'Disposable loopback fixture required');
const browser = await chromium.launch({executablePath:process.env.SCOUTRO_CHROMIUM_PATH, args:['--no-proxy-server']});
let checks = 0;
function check(value, label) { assert(value, label); checks++; }
try {
  const anonymous = await browser.newContext();
  check((await anonymous.request.get(base + '/scoutro/api/v1/index/browse')).status() === 401, 'Browser API requires admin');
  await anonymous.close();
  for (const language of ['en','de']) for (const width of [360,390,412,768,1280]) {
    const context = await browser.newContext({viewport:{width,height:900}, httpCredentials:{username:'admin',password:'yacy'}, extraHTTPHeaders:{'Accept-Language':language}});
    const page = await context.newPage(); const errors=[];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base+'/IndexBrowser_p.html?collection=visible&q=a.example');
    await page.waitForFunction(() => document.querySelector('#scoutro-index-total').textContent === '28');
    check(await page.locator('#scoutro-index-collection').inputValue() === 'visible', 'URL collection initialized');
    check(await page.locator('#scoutro-index-active').isVisible(), 'Active collection visible');
    check((await page.locator('#scoutro-index-table tbody').textContent()).includes('a.example'), 'Host filter applied');
    check(!(await page.locator('#scoutro-index-table tbody').textContent()).includes('secret'), 'Other memberships redacted by selected collection');
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth+1), `${language}/${width}: no browser overflow`);
    if (width < 992) check(await page.locator('#scoutro-index-table tbody tr').first().evaluate(row => getComputedStyle(row).display === 'grid'), 'Original table row displayed as card');
    if (width < 768) {
      await page.locator('#scoutro-nav-toggle').click();
      check(await page.locator('#scoutro-adminnav').isVisible(), 'Mobile sidebar usable');
      await page.keyboard.press('Escape');
    }
    await page.locator('#scoutro-index-query').fill('https://a.example/one');
    await page.locator('#scoutro-index-form button[type=submit]').click();
    await page.waitForFunction(() => document.querySelector('#scoutro-index-total').textContent === '1');
    check(new URL(page.url()).searchParams.get('collection') === 'visible', 'URL search remains in collection');
    await page.locator('#scoutro-index-collection').fill('absent');
    await page.locator('#scoutro-index-form button[type=submit]').click();
    await page.locator('#scoutro-index-empty').waitFor({state:'visible'});
    check(await page.locator('#scoutro-index-total').textContent() === '0', 'Unknown valid collection stays empty');
    await page.locator('#scoutro-index-collection').fill('bad OR *:*');
    await page.locator('#scoutro-index-form button[type=submit]').click();
    await page.locator('#scoutro-index-error').waitFor({state:'visible'});
    check(await page.locator('#scoutro-index-table tbody tr').count() === 0, 'Malformed filter never falls back to whole index');
    await page.locator('#scoutro-index-reset').click();
    await page.waitForFunction(() => document.querySelector('#scoutro-index-total').textContent === '30');
    check(await page.locator('#scoutro-index-collection').inputValue() === '', 'Reset clears filter');
    for (const path of ['WatchWebStructure_p.html','Collage.html','ScoutroAgents_p.html','ScoutroAgentWizard_p.html']) {
      await page.goto(base+'/'+path);
      check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth+1), `${language}/${width}: ${path} responsive`);
    }
    check(errors.length === 0, errors.join('; '));
    await context.close();
  }
  console.log(`PASS: ${checks} collection/browser/mobile checks, five widths, English/German`);
} finally { await browser.close(); }
