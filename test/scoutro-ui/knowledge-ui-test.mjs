#!/usr/bin/env node
/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Only run through knowledge-live-smoke.py: disposable peer, collections kga and kgb.
 * The organisation is seen in kga and kgb (alias "Geheime Holding" and a VAT ID only in kgb);
 * "Nur Bee GmbH" only in kgb; the LLM tier read "operates Haus Lindenhof" in kga. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
const entity = process.env.SCOUTRO_KG_ENTITY, host = process.env.SCOUTRO_KG_HOST, onlyB = process.env.SCOUTRO_KG_ONLY_B;
assert(base && new URL(base).hostname === '127.0.0.1' && entity && host && onlyB, 'Use knowledge-live-smoke.py; no production instance');
const shots = process.env.SCOUTRO_SCREENSHOTS;
if (shots) fs.mkdirSync(shots, { recursive: true });
let checks = 0;
function check(condition, message) { assert(condition, message); checks++; }
const browser = await chromium.launch({
  ...(process.env.SCOUTRO_CHROMIUM_PATH ? { executablePath: process.env.SCOUTRO_CHROMIUM_PATH } : {}),
  args: ['--no-proxy-server'],
});
const noOverflow = page => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1);
try {
  const anonymous = await browser.newContext();
  for (const p of ['/ScoutroKnowledge_p.html', '/scoutro/api/v1/kg/entities', '/scoutro/api/v1/kg/entities/' + entity, '/scoutro/api/v1/kg/sources/AAAAAAAAAAAA'])
    check((await anonymous.request.get(base + p)).status() === 401, 'administrator required: ' + p);
  await anonymous.close();

  for (const language of ['en', 'de']) {
    for (const width of language === 'de' ? [360, 390, 412, 768, 1280] : [390, 1280]) {
      const where = ` (${language}/${width})`;
      const context = await browser.newContext({ locale: language, viewport: { width, height: 900 }, httpCredentials: { username: 'admin', password: 'yacy' } });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      page.on('dialog', d => d.accept());
      try {
        // overview
        const response = await page.goto(base + '/ScoutroKnowledge_p.html', { waitUntil: 'networkidle' });
        check(response.status() === 200, 'page' + where);
        await page.waitForFunction(() => document.querySelector('#skg-cards').children.length >= 3);
        check(await page.locator('#skg-overview').isVisible(), 'overview visible' + where);
        check((await page.locator('#skg-cards').textContent()).includes('running'), 'state running' + where);
        if (language === 'de') check((await page.locator('h1').textContent()).trim() === 'Wissensgraph', 'German heading' + where);
        check(await page.locator('#scoutro-adminnav a[href="ScoutroKnowledge_p.html"]').count() === 1, 'navigation entry' + where);
        check(await noOverflow(page), 'no horizontal overflow (overview)' + where);
        if (shots) await page.screenshot({ path: path.join(shots, `kg-overview-${language}-${width}.png`), fullPage: true });

        // objects: all collections, then kga only
        await page.goto(base + '/ScoutroKnowledge_p.html?view=objects', { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-entities tbody tr');
        const all = await page.locator('#skg-entities tbody').textContent();
        check(all.includes('Muster Pflege gGmbH') && all.includes('Nur Bee GmbH'), 'all objects without a filter' + where);
        await page.goto(base + '/ScoutroKnowledge_p.html?view=objects&collection=kga', { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-entities tbody tr');
        const a = await page.locator('#skg-entities tbody').textContent();
        check(a.includes('Muster Pflege gGmbH') && !a.includes('Nur Bee GmbH'), 'collection filter hides the other collection' + where);
        check(await page.locator('#skg-collection').inputValue() === 'kga', 'collection kept in the form' + where);
        if (width < 768) check(await page.locator('#skg-entities tbody tr').first().evaluate(r => getComputedStyle(r).display !== 'table-row'), 'rows as cards on narrow screens' + where);
        await page.locator('#skg-q').fill('Nur');
        await page.locator('#skg-search button[type=submit]').click();
        await page.waitForFunction(() => new URLSearchParams(location.search).get('q') === 'Nur' && document.querySelector('#skg-range').textContent === ''
          && !document.querySelector('#skg-message').textContent.includes('…'));
        check(await page.locator('#skg-entities tbody tr').count() === 0, 'search within kga finds no kgb name' + where);
        check(await noOverflow(page), 'no horizontal overflow (objects)' + where);

        // object view in kga: own facts, the LLM relation, no kgb data; evidence with source link
        await page.goto(base + `/ScoutroKnowledge_p.html?view=object&id=${entity}&collection=kga`, { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-out .skg-statement');
        const object = await page.locator('#skg-object').textContent();
        check((await page.locator('#skg-object-name').textContent()).includes('Muster Pflege gGmbH'), 'object name' + where);
        check(object.includes('Haus Lindenhof'), 'relation from the LLM tier' + where);
        check(!object.includes('Geheime Holding') && !object.includes('DE123456789'), 'nothing of kgb in the kga view' + where);
        check(await page.locator('#skg-out .skg-llm').count() >= 1, 'LLM-only badge' + where);
        const lindenhof = page.locator('#skg-out .skg-statement', { hasText: 'Haus Lindenhof' }).first();
        await lindenhof.locator('button').click();
        await lindenhof.locator('.skg-excerpt').first().waitFor();
        check((await lindenhof.locator('.skg-excerpt').first().textContent()).includes('betreibt das Haus Lindenhof'), 'verbatim quote as evidence' + where);
        check((await lindenhof.locator('.skg-evidence').textContent()).includes('kga') && !(await lindenhof.locator('.skg-evidence').textContent()).includes('kgb'), 'evidence lists visible collections only' + where);
        check(await noOverflow(page), 'no horizontal overflow (object)' + where);
        if (shots) await page.screenshot({ path: path.join(shots, `kg-object-${language}-${width}.png`), fullPage: true });
        await lindenhof.locator('.skg-evidence a', { hasText: language === 'de' ? 'Was der Graph aus dieser Seite enthält' : 'What the graph holds from this page' }).first().click();
        await page.waitForSelector('#skg-source-items .skg-statement');
        check(await page.locator('#skg-source').isVisible() && (await page.locator('#skg-source-facts').textContent()).includes(host), 'source view' + where);
        check(new URL(page.url()).searchParams.get('collection') === 'kga', 'collection kept on navigation' + where);

        // the same object without a filter shows kgb's alias; the kgb-only object is not found in kga
        await page.goto(base + `/ScoutroKnowledge_p.html?view=object&id=${entity}`, { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-out .skg-statement');
        check((await page.locator('#skg-object-facts').textContent()).includes('Geheime Holding'), 'administrator without filter sees all' + where);
        await page.goto(base + `/ScoutroKnowledge_p.html?view=object&id=${onlyB}&collection=kga`, { waitUntil: 'networkidle' });
        await page.waitForFunction(() => document.querySelector('#skg-message').textContent.includes('404'));
        check(true, 'kgb object not found in kga' + where);

        // settings
        await page.goto(base + '/ScoutroKnowledge_p.html?view=settings', { waitUntil: 'networkidle' });
        await page.waitForFunction(() => document.querySelector('#skg-config').children.length > 0);
        check((await page.locator('#skg-config').textContent()).includes('kga'), 'settings show the LLM collection' + where);
        check(errors.length === 0, 'no JavaScript errors: ' + errors.join(', ') + where);
      } finally { await context.close(); }
    }
  }

  // integrations: SEO tab, Index Browser links, dashboard tile, controls
  const context = await browser.newContext({ locale: 'en', viewport: { width: 1280, height: 900 }, httpCredentials: { username: 'admin', password: 'yacy' } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(base + `/ScoutroSEO_p.html?host=${host}&collection=kga`, { waitUntil: 'networkidle' });
  await page.waitForSelector('#sseo-analysis:not([hidden])');
  await page.locator('#sseo-tab-knowledge').click();
  await page.waitForSelector('#sseo-kg-body table');
  const seo = await page.locator('#sseo-kg-body').textContent();
  check(seo.includes('Muster Pflege gGmbH') && !seo.includes('Nur Bee'), 'SEO knowledge tab of the host' + ' (kga)');
  check((await page.locator('#sseo-kg-open').getAttribute('href')).includes('collection=kga'), 'SEO link keeps the collection');
  await page.goto(base + '/IndexBrowser_p.html', { waitUntil: 'networkidle' });
  await page.waitForSelector('.scoutro-domain');
  check(await page.locator('.scoutro-domain a[href*="ScoutroKnowledge_p.html?view=objects"]').count() >= 2, 'Index Browser domain cards link to the graph');
  await page.goto(base + '/IndexBrowser_p.html?view=urls&q=' + host, { waitUntil: 'networkidle' });
  await page.waitForSelector('#scoutro-index-table tbody tr');
  check(await page.locator('#scoutro-index-table a[href*="ScoutroKnowledge_p.html?view=source&doc="]').count() >= 1, 'Index Browser URL rows link to the source view');
  await page.goto(base + '/scoutro-dashboard.html', { waitUntil: 'networkidle' });
  await page.waitForFunction(() => document.querySelector('#scoutro-kg-state').textContent !== '—');
  check((await page.locator('#scoutro-kg-state').textContent()).trim() === 'Running', 'dashboard tile');
  await page.goto(base + '/ScoutroKnowledge_p.html', { waitUntil: 'networkidle' });
  await page.locator('[data-skg-action="pause"]').click();
  await page.waitForFunction(() => document.querySelector('#skg-storage').textContent.includes('manual'));
  check(true, 'pause through the page');
  await page.locator('[data-skg-action="resume"]').click();
  await page.waitForFunction(() => !document.querySelector('#skg-storage').textContent.includes('manual'));
  check(true, 'resume through the page');
  // backups: create through the page, listed with a download of the SQLite file, restore with a confirmation
  page.on('dialog', d => d.accept());
  await page.locator('[data-skg-action="backup"]').click();
  await page.waitForFunction(() => document.querySelector('#skg-backups table tbody tr') !== null, null, { timeout: 60000 });
  const file = (await page.locator('#skg-backups tbody tr td').first().textContent()).trim();
  check(/^graph-\d{8}T\d{6}Z\.db$/.test(file), 'backup listed: ' + file);
  const href = await page.locator('#skg-backups tbody tr a').first().getAttribute('href');
  // through the page's own (Digest-authenticated) session
  const download = await page.evaluate(async h => {
    const r = await fetch(h, { credentials: 'same-origin' });
    const bytes = new Uint8Array(await r.arrayBuffer());
    return { status: r.status, head: String.fromCharCode(...bytes.slice(0, 15)), disposition: r.headers.get('content-disposition') || '',
      type: r.headers.get('content-type') || '', size: bytes.length };
  }, href);
  check(download.status === 200 && download.head === 'SQLite format 3' && download.disposition.includes(file)
    && download.type.startsWith('application/vnd.sqlite3') && download.size > 4096, 'backup download: ' + JSON.stringify(download));
  check((await browser.newContext().then(async c => { const r = await c.request.get(base + '/scoutro/api/v1/kg/backups/' + file); await c.close(); return r.status(); })) === 401,
    'backup download needs the administrator');
  await page.locator(`[data-skg-restore="${file}"]`).click();
  await page.waitForFunction(() => document.querySelector('#skg-message').textContent.length > 0 && !document.querySelector('#skg-message').textContent.includes('…'), null, { timeout: 60000 });
  await page.waitForFunction(() => [...document.querySelectorAll('#skg-backups tbody tr')].some(r => r.textContent.includes('before-restore')), null, { timeout: 60000 });
  check(true, 'restore through the page keeps the previous graph as a backup');
  check((await page.locator('#skg-cards').textContent()).includes('running'), 'running after the restore');
  check(errors.length === 0, 'no JavaScript errors in the integrations: ' + errors.join(', '));
  await context.close();
} finally { await browser.close(); }
console.log(`PASS: ${checks} knowledge graph UI checks (English and German, five widths, collection isolation, SEO tab, Index Browser, dashboard, controls)`);
