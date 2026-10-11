/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Disposable fixture only (report-live-smoke.py); read-only UI. */
import { createRequire } from 'node:module';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { withDigestSignIn } from './digest-signin.mjs';
const require = createRequire(import.meta.url), { chromium } = require('playwright');
const base = process.env.SCOUTRO_URL, shots = process.argv.includes('--screenshots') ? process.argv[process.argv.indexOf('--screenshots') + 1] : null;
const JOB = '5b0f4c1e-7a2d-4c6b-9f1e-2d3c4b5a6f70';
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH, args: ['--no-proxy-server'] });
withDigestSignIn(browser); // Digest credentials after the login page (digest-signin.mjs)
const text = { en: { title: 'Crawl report', indexed: 'Indexed', partial: 'Partially indexed', unavailable: 'Crawl reports are unavailable', stale: 'Yes', host: 'Host analysis', absent: 'No crawl of this host has been recorded in this collection yet.', need: 'Enter a collection to read the crawl status of this host.',
    elsewhere: 'Canonical points to another URL', untitled: 'Pages without title', sharing: 'Pages sharing a title', directories: 'Directories', disabled: 'Not enabled in the index schema:' },
  de: { title: 'Crawl-Bericht', indexed: 'Indexiert', partial: 'Teilweise indexiert', unavailable: 'Crawl-Berichte sind nicht verfügbar', stale: 'Ja', host: 'Host-Analyse', absent: 'Für diesen Host wurde in dieser Collection noch kein Crawl erfasst.', need: 'Geben Sie eine Collection ein, um den Crawl-Status dieses Hosts zu lesen.',
    elsewhere: 'Canonical verweist auf eine andere URL', untitled: 'Seiten ohne Titel', sharing: 'Seiten mit geteiltem Titel', directories: 'Verzeichnisse', disabled: 'Im Indexschema nicht aktiviert:' } };
let checks = 0;
const check = (v, m) => { assert.ok(v, m); checks++; };
const rows = page => page.locator('#sseo-r-hosts tbody tr');
try {
 const anonymous = await browser.newContext();
 for (const path of ['/ScoutroSEO_p.html?view=report', '/scoutro/api/v1/reports/jobs', '/scoutro/api/v1/reports/collections/visible']) check((await anonymous.request.get(base + path)).status() === 401, 'Admin required: ' + path);
 await anonymous.close(); if (shots) fs.mkdirSync(shots, { recursive: true });
 for (const language of ['en', 'de']) for (const width of [360, 390, 412, 768, 1280]) {
  const l = text[language], where = ` (${language} ${width})`;
  const context = await browser.newContext({ viewport: { width, height: 900 }, isMobile: width < 768, hasTouch: width < 768, httpCredentials: { username: 'admin', password: 'yacy' }, extraHTTPHeaders: { 'Accept-Language': language } });
  const page = await context.newPage(), errors = [], writes = [];
  page.on('pageerror', e => errors.push(e.message)); page.on('dialog', d => { errors.push('dialog ' + d.message()); d.dismiss(); });
  page.on('request', r => { if (!['GET', 'HEAD'].includes(r.method())) writes.push(r.method() + ' ' + r.url()); });
  check((await page.goto(base + '/ScoutroSEO_p.html?view=report', { waitUntil: 'networkidle' })).status() === 200, 'Report shell loads' + where);
  check(await page.locator('#sseo-report-view').isVisible() && !await page.locator('#sseo-host-view').isVisible(), 'Report view selected by URL' + where);
  check((await page.locator('#sseo-report-title').textContent()) === l.title, 'Report heading localized' + where);
  check(await page.locator('#sseo-view-report').getAttribute('aria-current') === 'page' && !await page.locator('#sseo-view-host').getAttribute('aria-current'), 'View switch marks current view' + where);
  check((await page.locator('#sseo-view-host').textContent()) === l.host, 'View switch localized' + where);
  // package 6.1: the collection of the report is chosen from a list (index and Discovery jobs), never typed
  check(await page.locator('#sseo-r-collection').evaluate(e => e.tagName) === 'SELECT' && await page.locator('#sseo-r-form input[list], datalist').count() === 0, 'Report collection as a real select' + where);
  await page.waitForFunction(() => !document.getElementById('sseo-r-collection').disabled);
  const reportNames = await page.locator('#sseo-r-collection option').evaluateAll(list => list.map(o => o.value));
  const catalog = await page.evaluate(async () => (await (await fetch('/scoutro/api/v1/collections', { credentials: 'same-origin' })).json()).collections.filter(c => c.selectable && !c.internal).map(c => c.id).sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : a.toLowerCase() > b.toLowerCase() ? 1 : a < b ? -1 : a > b ? 1 : 0));
  check(catalog.includes('secret') && catalog.includes('visible') && JSON.stringify(reportNames) === JSON.stringify(['', ...catalog]), 'Choose first, then the catalog sorted: ' + JSON.stringify(reportNames) + where);
  await page.locator('#sseo-r-collection').selectOption('visible'); await page.locator('#sseo-r-form button[type="submit"]').click();
  await page.waitForSelector('#sseo-r-hosts tbody tr');
  check(new URL(page.url()).search === '?view=report&collection=visible', 'Scope kept in the URL' + where);
  check((await page.locator('#sseo-r-kpis').textContent()).includes('34'), 'Host count from the table' + where);
  check(await page.locator('#sseo-r-outcomes svg circle').count() === 4, 'Outcome donut with three segments' + where);
  check((await page.locator('#sseo-r-outcomes').textContent()).includes(l.indexed), 'Outcome legend localized' + where);
  check((await page.locator('#sseo-r-types').textContent()).includes('text/html<img src=x onerror=alert(1)>'), 'Indexed value shown as text' + where);
  check(await page.locator('#sseo-report-view img').count() === 0, 'No markup from indexed or stored values' + where);
  check((await page.locator('#sseo-r-duplicates').textContent()).includes('2'), 'Duplicates from the live index' + where);
  check((await page.locator('#sseo-r-canonical').textContent()).includes(l.elsewhere) && await page.locator('#sseo-r-canonical svg').count() === 3, 'Canonical tile' + where);
  check(await page.locator('#sseo-r-canonical a').count() === 0, 'No field hint while the fields are enabled' + where);
  check((await page.locator('#sseo-r-texts').textContent()).includes(l.untitled), 'Titles and descriptions tile' + where);
  check(await rows(page).count() === 25 && (await page.locator('#sseo-r-range').textContent()).includes('34'), 'Host list is paged by the server' + where);
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'No horizontal page overflow' + where);
  if (shots) await page.screenshot({ path: `${shots}/report-collection-${language}-${width}.png`, fullPage: true });
  await page.locator('#sseo-r-next').click(); await page.waitForFunction(() => document.querySelectorAll('#sseo-r-hosts tbody tr').length === 9);
  check(await page.locator('#sseo-r-next').isDisabled() && await page.locator('#sseo-r-prev').isEnabled(), 'Last host page' + where);
  await page.locator('#sseo-r-filter').selectOption('stale'); await page.waitForFunction(() => document.querySelectorAll('#sseo-r-hosts tbody tr').length === 1);
  const stale = rows(page).first();
  check((await stale.textContent()).includes('b.example') && (await stale.locator('.sseo-stale').textContent()) === l.stale, 'Stale filter' + where);
  check(await stale.locator('a.btn').getAttribute('href') === 'ScoutroCrawls_p.html?url=http%3A%2F%2Fb.example%2F&collection=visible#new-crawl', 'Crawl again uses the native crawl form and the recorded scheme' + where);
  check(await stale.locator('a.sseo-url').getAttribute('href') === 'ScoutroSEO_p.html?host=b.example&collection=visible', 'Host links to its analysis' + where);
  await page.locator('#sseo-r-filter').selectOption('precheck'); await page.waitForFunction(() => document.querySelector('#sseo-r-hosts tbody tr td')?.textContent === 'c.example');
  await page.locator('#sseo-r-kind').selectOption('job'); await page.waitForFunction(id => [...document.querySelectorAll('#sseo-r-job option')].some(o => o.value === id), JOB);
  check(await page.locator('#sseo-r-collection-field').isHidden() && await page.locator('#sseo-r-from').isVisible(), 'Job scope controls' + where);
  await page.locator('#sseo-r-job').selectOption(JOB); await page.locator('#sseo-r-form button[type="submit"]').click();
  await page.waitForSelector('#sseo-r-history svg');
  check(new URL(page.url()).searchParams.get('job') === JOB, 'Job scope kept in the URL' + where);
  check((await page.locator('#sseo-r-heading').textContent()).includes('<img src=x onerror=alert(1)>') && await page.locator('#sseo-r-heading img').count() === 0, 'Job name shown as text' + where);
  check(await page.locator('#sseo-r-history svg rect').count() > 5 && await page.locator('#sseo-r-history svg line').count() === 2, 'Daily history with markers' + where);
  check(await page.locator('#sseo-r-hosts').isHidden() && await page.locator('#sseo-r-status').isHidden(), 'Collection-only tiles hidden for a job' + where);
  check(await page.locator('#sseo-r-collections a').getAttribute('href') === 'ScoutroSEO_p.html?view=report&collection=visible', 'Job links its collections' + where);
  await page.locator('#sseo-r-history-table').evaluate(n => n.closest('details').open = true);
  check(await page.locator('#sseo-r-history-table tbody tr').count() >= 8, 'History data table' + where);
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'No horizontal page overflow for a job' + where);
  if (shots) await page.screenshot({ path: `${shots}/report-job-${language}-${width}.png`, fullPage: true });
  // Disabled optional fields are named, with a link to YaCy's index schema page.
  await page.route('**/scoutro/api/v1/reports/collections/visible?*', r => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ collection: 'visible',
    table: { hosts: 1, crawled: 1, precheck_only: 0, latest_attempt_precheck: 0, coverage_partial: 0, stale: 0, last_crawl: null, outcomes: {}, prechecks: {}, counters: {} },
    table_scanned_at: '2026-10-04T00:00:00Z', index_source: 'live', index_as_of: '2026-10-04T00:00:00Z',
    index: { documents: 1, ok: 1, hosts: 1, titles: { with: 1, missing: 0 }, unavailable: ['canonical_s', 'canonical_equal_sku_b'] } }) }));
  await page.locator('#sseo-r-kind').selectOption('collection'); await page.locator('#sseo-r-form button[type="submit"]').click();
  await page.waitForSelector('#sseo-r-canonical a[href="IndexSchema_p.html?core=collection1&filter=disabled"]');
  check((await page.locator('#sseo-r-canonical').textContent()).includes(l.disabled + ' canonical_s, canonical_equal_sku_b'), 'Disabled fields named' + where);
  await page.unroute('**/scoutro/api/v1/reports/collections/visible?*');
  // A failed report read must be visible and localized.
  await page.route('**/scoutro/api/v1/reports/collections/visible?*', r => r.fulfill({ status: 503, contentType: 'application/json', body: '{"error":{"code":"report_unavailable"}}' }));
  await page.locator('#sseo-r-kind').selectOption('collection'); await page.locator('#sseo-r-form button[type="submit"]').click();
  await page.waitForFunction(() => document.querySelector('#sseo-r-message').textContent.includes('503'));
  check((await page.locator('#sseo-r-message').textContent()).includes(l.unavailable), 'Unavailable report localized' + where);
  await page.unroute('**/scoutro/api/v1/reports/collections/visible?*');
  // Host analysis: crawl status tab and keyboard navigation over six tabs (crawl status is the last).
  await page.goto(base + '/ScoutroSEO_p.html?host=a.example&collection=visible', { waitUntil: 'networkidle' });
  await page.waitForSelector('#sseo-analysis:not([hidden])');
  check(await page.locator('#sseo-host-view').isVisible() && await page.locator('#sseo-report-view').isHidden(), 'Host view by default' + where);
  await page.locator('#sseo-tab-overview').focus(); await page.keyboard.press('End');
  check(await page.locator('#sseo-tab-crawl-status').getAttribute('aria-selected') === 'true', 'End selects the last of six tabs' + where);
  await page.waitForSelector('#sseo-cs-body dl');
  check((await page.locator('#sseo-cs-body').textContent()).includes(l.partial), 'Crawl status of host and collection' + where);
  const crawlTab = await page.locator('#sseo-cs-body').textContent();
  check(crawlTab.includes(l.directories) && crawlTab.includes('/docs/'), 'Directories of the host' + where);
  check(crawlTab.includes('blog.example') && crawlTab.includes(l.sharing), 'Referring hosts and shared titles' + where);
  check(await page.locator('#sseo-cs-again').getAttribute('href') === 'ScoutroCrawls_p.html?url=https%3A%2F%2Fa.example%2F&collection=visible#new-crawl', 'Crawl again from the host' + where);
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'No horizontal page overflow in the crawl tab' + where);
  if (shots) await page.screenshot({ path: `${shots}/report-host-${language}-${width}.png`, fullPage: true });
  await page.keyboard.press('ArrowRight');
  check(await page.locator('#sseo-tab-overview').getAttribute('aria-selected') === 'true', 'Arrow keys wrap over six tabs' + where);
  await page.locator('#sseo-tab-crawl-status').click(); await page.locator('#sseo-cs-collection').selectOption('secret'); await page.locator('#sseo-cs-show').click();
  await page.waitForFunction(absent => document.querySelector('#sseo-cs-body').textContent.includes(absent), l.absent);
  check(await page.locator('#sseo-cs-again').getAttribute('href') === 'ScoutroCrawls_p.html?url=https%3A%2F%2Fa.example%2F&collection=secret#new-crawl', 'Absent row is an empty state with a crawl link' + where);
  await page.locator('#sseo-cs-collection').selectOption(''); await page.locator('#sseo-cs-show').click();
  check((await page.locator('#sseo-cs-body').textContent()) === l.need, 'Collection required for the crawl status' + where);
  check(errors.length === 0, 'No JS errors: ' + errors.join(';') + where); check(writes.length === 0, 'No mutation requests: ' + writes.join(';') + where);
  await context.close();
 }
 console.log(`PASS: ${checks} crawl report UI checks, English/German, five desktop/mobile widths`);
} finally { await browser.close(); }
