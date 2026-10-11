#!/usr/bin/env node
/* Scoutro contributors, GPL-2.0-or-later. Run only from disposable seo-live-smoke.py (collections visible and secret).
 * Package 6.1, against the real backend: a collection is created on the crawl page, listed and selected at once,
 * still there after a reload, offered by the other choices, ticked in an agent grant and accepted by the server.
 * The crawl start itself is intercepted (no crawl runs); its request must carry the new collection. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import { withDigestSignIn } from './digest-signin.mjs';
const {chromium} = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL, shots = process.env.SCOUTRO_SCREENSHOTS;
assert(base && new URL(base).hostname === '127.0.0.1', 'Disposable loopback fixture required');
const browser = await chromium.launch({executablePath: process.env.SCOUTRO_CHROMIUM_PATH, args: ['--no-proxy-server']});
withDigestSignIn(browser); // Digest credentials after the login page (digest-signin.mjs)
let checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const fits = page => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1);
try {
  // without the administrator: no catalog, no creation
  const anonymous = await browser.newContext();
  check((await anonymous.request.post(base + '/scoutro/api/v1/collections', {data: {id: 'anon-web', name: 'x'}})).status() === 401, 'creating needs the administrator');
  // YaCy's public crawl form before signing in: its own field stays (no empty list), so the login at submit still works
  const visitor = await anonymous.newPage();
  await visitor.goto(base + '/CrawlStartSite.html', {waitUntil: 'networkidle'});
  check(await visitor.locator('input#collection').count() === 1 && await visitor.locator('select#collection').count() === 0, 'without the catalog YaCy\'s field stays');
  await anonymous.close();

  for (const [language, width, id, name] of [['en', 1280, 'live-portal', 'Live Portal'], ['de', 360, 'mein-neues-portal', 'Mein neues Portal']]) {
    const where = ` (${language}/${width})`;
    const context = await browser.newContext({viewport: {width, height: 900}, isMobile: width < 768, hasTouch: width < 768, locale: language,
      httpCredentials: {username: 'admin', password: 'yacy'}, extraHTTPHeaders: {'Accept-Language': language}});
    const page = await context.newPage(), errors = [];
    page.on('pageerror', e => errors.push(e.message));
    let crawl = null;
    await page.route('**/scoutro/api/v1/crawls', route => {
      if (route.request().method() !== 'POST') return route.fulfill({status: 200, contentType: 'application/json', body: '{"crawls":[]}'});
      crawl = route.request().postDataJSON();
      return route.fulfill({status: 201, contentType: 'application/json', body: JSON.stringify({id: 'intercepted', url: crawl.url, collection: crawl.collection, state: 'running'})});
    });
    await page.goto(base + '/ScoutroCrawls_p.html?url=https%3A%2F%2Fa.example%2F', {waitUntil: 'networkidle'});
    await page.waitForFunction(() => !document.getElementById('scc-collection').disabled);
    check(await page.locator('#scc-collection-new').isVisible(), 'New collection for the administrator' + where);
    await page.locator('#scc-collection-new').click();
    await page.locator('#scoutro-collection-name').fill(name);
    check(await page.locator('#scoutro-collection-id').inputValue() === id, 'id suggested from the display name' + where);
    check(await fits(page), 'dialog fits the viewport' + where);
    if (shots) { fs.mkdirSync(shots, {recursive: true}); await page.screenshot({path: `${shots}/collection-create-${language}-${width}.png`}); }
    await page.locator('#scoutro-collection-save').click();
    await page.waitForFunction(v => document.getElementById('scc-collection').value === v, id);
    check(true, 'created, listed and selected at once' + where);
    // a duplicate is refused by the server and adds nothing
    await page.locator('#scc-collection-new').click();
    await page.locator('#scoutro-collection-name').fill(name);
    await page.locator('#scoutro-collection-save').click();
    await page.locator('#scoutro-collection-error').waitFor({state: 'visible'});
    check((await page.locator('#scc-collection option').evaluateAll(list => list.map(o => o.value))).filter(v => v === id).length === 1, 'a duplicate adds no option' + where);
    await page.locator('#scoutro-collection-cancel').click();
    await page.locator('#scc-start').click();
    // package 6.3: the result of the start is shown next to the form, with the profile id of the answer
    await page.waitForFunction(() => document.getElementById('scc-result').getAttribute('aria-busy') === 'false' && document.querySelector('#scc-facts').textContent.includes('intercepted'));
    check(crawl && crawl.collection === id, 'the crawl request uses the new collection' + where);
    // the real server: an unknown collection is refused before any crawl is dispatched
    await page.unroute('**/scoutro/api/v1/crawls');
    const unknown = await page.evaluate(async () => (await fetch('/scoutro/api/v1/crawls', {method: 'POST', credentials: 'same-origin',
      headers: {'Content-Type': 'application/json'}, body: JSON.stringify({url: 'https://a.example/', collection: 'nirgends-web'})})).json());
    check(unknown.error?.code === 'collection_unknown', 'an unknown collection is refused before any crawl' + where);
    // after a reload the collection is still there
    await page.goto(base + '/ScoutroCrawls_p.html?collection=' + id, {waitUntil: 'networkidle'});
    await page.waitForFunction(() => !document.getElementById('scc-collection').disabled);
    check(await page.locator('#scc-collection').inputValue() === id, 'kept after a reload, chosen from the link' + where);
    // every other choice offers it at once
    await page.goto(base + '/IndexBrowser_p.html', {waitUntil: 'networkidle'});
    await page.waitForFunction(v => [...document.querySelectorAll('#scoutro-index-collection option')].some(o => o.value === v), id);
    check(true, 'the Index Browser offers it' + where);
    await page.goto(base + '/yacychat.html', {waitUntil: 'networkidle'});
    check(await page.locator(`#collectionSelect option[value="${id}"]`).count() === 1, 'the chat offers it (local access)' + where);
    // YaCy's own crawl and import forms: the catalog's dropdown with New collection instead of a text field
    for (const form of ['CrawlStartExpert.html', 'CrawlStartSite.html', 'IndexImportWarc_p.html']) {
      await page.goto(base + '/' + form, {waitUntil: 'networkidle'});
      await page.waitForFunction(() => document.getElementById('collection')?.tagName === 'SELECT');
      check(await page.locator(`select#collection option[value="${id}"]`).count() === 1, form + ' offers it' + where);
      check(await page.locator('input[name="collection"]').count() === 0, form + ': no free text' + where);
      const [offered, selectable] = await page.evaluate(async () => [
        [...document.querySelectorAll('select#collection option')].map(o => o.value).filter(Boolean),
        (await (await fetch('/scoutro/api/v1/collections', {credentials: 'same-origin'})).json()).collections.filter(c => c.selectable).map(c => c.id)]);
      check(JSON.stringify(offered) === JSON.stringify(selectable), form + ': exactly the selectable collections of the catalog, in order' + where);
      check(await page.locator('select#collection + button').isVisible(), form + ': New collection for the administrator' + where);
      check(await fits(page), form + ' fits the viewport' + where);
    }
    await page.goto(base + '/IndexDeletion_p.html', {waitUntil: 'networkidle'});
    check(await page.locator('select#collectiondelete option[value="visible"]').count() === 1 && await page.locator('input#collections').count() === 0,
      'the deletion page lists the collections of the index, no text field' + where);
    // the agent wizard: a new collection created there is ticked, and the server accepts the grant
    await page.goto(base + '/ScoutroAgentWizard_p.html', {waitUntil: 'networkidle'});
    await page.locator('#agentName').fill('Collection grant ' + language);
    await Promise.all([page.waitForNavigation(), page.locator('input[name="next"]').click()]);
    check(await page.locator(`input[name="col_${id}"]`).count() === 1, 'the grant list offers the created collection' + where);
    check(await page.locator('input[name="extraCollections"]').count() === 0, 'no free text for further collections' + where);
    const grantId = id + '-agent';
    await page.locator('[data-scoutro-collection-new]').click();
    await page.locator('#scoutro-collection-name').fill(name + ' Agent');
    await page.locator('#scoutro-collection-id').fill(grantId);
    await page.locator('#scoutro-collection-save').click();
    await page.waitForFunction(v => document.querySelector(`input[name="col_${v}"]`)?.checked, grantId);
    check(true, 'created in the grant form and ticked' + where);
    await Promise.all([page.waitForNavigation(), page.locator('input[name="next"]').click()]);
    check(await page.locator('input[name="actionsForm"]').count() === 1, 'the server accepted the grant (step 3)' + where);
    check(await fits(page), 'no horizontal overflow' + where);
    check(errors.length === 0, 'no JavaScript errors: ' + errors.join('; ') + where);
    await context.close();
  }
} finally {
  await browser.close();
}
console.log(`PASS: ${checks} collection creation checks against the real backend (crawl page, reload, Index Browser, chat, YaCy's crawl and import forms, deletion page, agent wizard; English 1280, German 360)`);
