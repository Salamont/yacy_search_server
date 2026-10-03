/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Disposable fixture only; read-only UI. */
import { createRequire } from 'node:module';
import fs from 'node:fs';
import assert from 'node:assert/strict';
const require = createRequire(import.meta.url), { chromium } = require('playwright');
const base = process.env.SCOUTRO_URL, shots = process.argv.includes('--screenshots') ? process.argv[process.argv.indexOf('--screenshots') + 1] : null;
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH, args: ['--no-proxy-server'] });
let checks = 0;
const check = (v, m) => { assert.ok(v, m); checks++; };
try {
 const anonymous = await browser.newContext();
 for (const path of ['/ScoutroSEO_p.html', '/scoutro/api/v1/seo/hosts']) check((await anonymous.request.get(base + path)).status() === 401, 'Admin required: ' + path);
 await anonymous.close(); if (shots) fs.mkdirSync(shots, { recursive: true });
 for (const language of ['en', 'de']) for (const width of [360, 390, 412, 1280]) {
  const context = await browser.newContext({ viewport: { width, height: 900 }, isMobile: width < 768, hasTouch: width < 768, httpCredentials: { username: 'admin', password: 'yacy' }, extraHTTPHeaders: { 'Accept-Language': language } });
  const page = await context.newPage(), errors = [], writes = [];
  page.on('pageerror', e => errors.push(e.message)); page.on('request', r => { if (!['GET', 'HEAD'].includes(r.method())) writes.push(r.url()); });
  const response = await page.goto(base + '/ScoutroSEO_p.html', { waitUntil: 'networkidle' });
  check(response.status() === 200, 'SEO shell loads');
  check((await page.locator('h1').textContent()).includes(language === 'de' ? 'Host-Analyse' : 'Host Analysis'), 'Heading localized');
  if (width < 768) await page.locator('#scoutro-nav-toggle').click();
  check(await page.locator('#scoutro-adminnav a[href="ScoutroSEO_p.html"]').isVisible(), 'Native navigation desktop/mobile');
  if (width < 768) await page.keyboard.press('Escape');
  await page.locator('#sseo-host').fill('a.'); await page.locator('#sseo-find').click();
  await page.waitForSelector('#sseo-host-results button'); check(await page.locator('#sseo-host-results button').count() === 1, 'Bounded host search');
  await page.locator('#sseo-host-results button').click(); await page.waitForSelector('#sseo-analysis:not([hidden])');
  check(await page.locator('#sseo-heading').textContent() === 'a.example', 'Host selected');
  check((await page.locator('#sseo-kpis').textContent()).includes('29'), 'Real URL count');
  check((await page.locator('#sseo-coverage').textContent()).includes('26'), 'Finalized coverage');
  check(await page.locator('#sseo-panel-overview').isVisible(), 'Overview');
  await page.locator('#sseo-tab-pages').click(); await page.waitForSelector('#sseo-pages-table tbody tr');
  check(await page.locator('#sseo-pages-table tbody tr').count() === 25, 'Server page size');
  check(await page.locator('#sseo-pages-table img').count() === 0, 'Indexed title is text, not markup');
  await page.locator('#sseo-next').click(); await page.waitForFunction(() => document.querySelectorAll('#sseo-pages-table tbody tr').length === 4); check(await page.locator('#sseo-prev').isEnabled(), 'Server next page');
  await page.locator('#sseo-sort').selectOption('references_external'); await page.waitForFunction(() => document.querySelector('#sseo-filter').value === 'processed' && document.querySelector('#sseo-range').textContent.includes('26'));
  check(await page.locator('#sseo-filter').inputValue() === 'processed', 'Reference sort uses finalized URLs');
  await page.locator('#sseo-pages-table button').first().click(); await page.waitForSelector('#sseo-detail:not([hidden])');
  check((await page.locator('#sseo-detail-body').textContent()).includes(language === 'de' ? 'Ausgehende interne Links' : 'Outgoing internal links'), 'URL detail outgoing semantics');
  check((await page.locator('#sseo-detail-body').textContent()).includes('0'), 'Processed zero displayed');
  await page.locator('#sseo-close').click(); check(!await page.locator('#sseo-detail').isVisible(), 'Detail closes');
  await page.locator('#sseo-tab-links').click(); await page.waitForSelector('#sseo-top-external tbody tr');
  check((await page.locator('#sseo-references').textContent()).includes('7'), 'Reliable reference total');
  check(await page.locator('#sseo-top-external tbody tr').count() === 5, 'Bounded top external');
  check(await page.locator('#sseo-top-internal tbody tr').count() === 5, 'Bounded top internal');
  await page.locator('#sseo-tab-technology').click(); check(await page.locator('#sseo-panel-technology').isVisible(), 'Technology');
  check((await page.locator('#sseo-status').textContent()).includes('200'), 'Real HTTP facet');
  check((await page.locator('#sseo-protocol').textContent()).includes('https'), 'Real protocol');
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'No horizontal page overflow');
  await page.evaluate(() => scrollTo(0, 0));
  if (shots) await page.screenshot({ path: `${shots}/seo-${language}-${width}.png`, fullPage: true });
  await page.locator('#sseo-host').fill('absent.example'); await page.locator('#sseo-search button[type="submit"]').click();
  await page.waitForFunction(() => document.querySelector('#sseo-message').textContent.includes('404')); check(!await page.locator('#sseo-analysis').isVisible(), '404 visible, no stale matrix');
  // A failed backend read must be visible, even when a tab previously loaded.
  await page.route('**/scoutro/api/v1/seo/hosts/a.example?*', r => r.fulfill({ status: 503, contentType: 'application/json', body: '{"error":{"code":"index_unavailable"}}' }));
  await page.locator('#sseo-host').fill('a.example'); await page.locator('#sseo-search button[type="submit"]').click();
  await page.waitForFunction(() => document.querySelector('#sseo-message').textContent.includes('503')); check((await page.locator('#sseo-message').textContent()).includes(language === 'de' ? 'Analyse fehlgeschlagen' : 'Analysis failed'), 'Backend errors localized and visible');
  check(errors.length === 0, 'No JS errors: ' + errors.join(';')); check(writes.length === 0, 'No mutation requests');
  await context.close();
 }
 console.log(`PASS: ${checks} SEO UI checks, English/German, four desktop/mobile widths`);
} finally { await browser.close(); }
