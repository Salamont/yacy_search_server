#!/usr/bin/env node
/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt.
 * Read-only live dashboard checks. Fixture seeding belongs to the disposable
 * peer harness; this script never starts a crawl or sends a mutation request.
 */
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';

const require = createRequire(import.meta.url);
let playwright;
try { playwright = require('playwright'); }
catch { playwright = require(path.join(execSync('npm root -g').toString().trim(), 'playwright')); }
const base = (process.env.SCOUTRO_URL || 'http://127.0.0.1:8090').replace(/\/$/, '');
const credentials = { username: process.env.SCOUTRO_ADMIN_USER || 'admin', password: process.env.SCOUTRO_ADMIN_PASSWORD || 'yacy' };
const shotIdx = process.argv.indexOf('--screenshots');
const shots = shotIdx >= 0 ? process.argv[shotIdx + 1] : null;
const browser = await playwright.chromium.launch({
  ...(process.env.SCOUTRO_CHROMIUM_PATH ? { executablePath: process.env.SCOUTRO_CHROMIUM_PATH } : {}),
  args: ['--no-proxy-server'],
});
let checks = 0;
function check(value, label) { assert.ok(value, label); checks++; }
try {
  if (shots) fs.mkdirSync(shots, { recursive: true });
  const anonymous = await browser.newContext();
  const refused = await anonymous.request.get(base + '/scoutro-dashboard.html');
  check(refused.status() === 401, 'Dashboard requires admin even with public search');
  await anonymous.close();

  for (const [width, height] of [[360, 740], [390, 844], [412, 915], [1280, 900]]) {
    const mobile = width < 768;
    const context = await browser.newContext({ viewport: { width, height }, isMobile: mobile, hasTouch: mobile, httpCredentials: credentials });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('dialog', d => d.dismiss());
    const response = await page.goto(base + '/scoutro-dashboard.html', { waitUntil: 'networkidle' });
    check(response.status() === 200, `${width}: authenticated dashboard loads`);
    check(response.headers()['cache-control']?.includes('no-store'), `${width}: admin response is not cached by browser`);
    check(await page.locator('h1').textContent() === 'Scoutro Dashboard', `${width}: dashboard responder renders`);
    const rendered = await page.locator('main').innerHTML();
    check(!rendered.includes('#[') && !rendered.includes('UNRESOLVED_PATTERN'), `${width}: no unresolved substitutions`);
    check(/^\d+d \d{2}h \d{2}m$/.test(await page.locator('[data-metric="uptime"]').textContent()), `${width}: uptime is not overwritten by global template defaults`);
    check(await page.locator('[data-collection]').count() === 4, `${width}: all four collections render`);
    check(await page.locator('main form, main button').count() === 0, `${width}: no action forms/buttons`);
    check(!await page.locator('#header_restart').isVisible() && !await page.locator('#header_shutdown').isVisible(), `${width}: no restart/shutdown actions on overview`);
    const data = await page.evaluate(() => ({
      pages: document.querySelector('[data-metric="pages"]').textContent,
      hosts: document.querySelector('[data-metric="hosts"]').textContent,
      collections: [...document.querySelectorAll('[data-collection]')].map(el => ({
        id: el.dataset.collection,
        pages: el.querySelector('[data-metric="collection-pages"]').textContent,
        hosts: el.querySelector('[data-metric="collection-hosts"]')?.textContent,
      })),
    }));
    check(/^\d[\d,]*$/.test(data.pages), `${width}: real document count available`);
    check(/^\d[\d,]*$/.test(data.hosts), `${width}: exact host count available`);
    if (process.env.SCOUTRO_EXPECT_PAGES) {
      check(data.pages.replaceAll(',', '') === process.env.SCOUTRO_EXPECT_PAGES, `${width}: indexed page fixture count`);
      check(data.hosts.replaceAll(',', '') === process.env.SCOUTRO_EXPECT_HOSTS, `${width}: distinct host fixture count`);
      const expected = JSON.parse(process.env.SCOUTRO_EXPECT_COLLECTIONS || '{}');
      for (const item of data.collections) {
        if (item.id in expected) check(item.pages.replaceAll(',', '') === String(expected[item.id]), `${width}: ${item.id} dynamic count`);
        const expectedHosts = { 'edelsenior-web': 1, 'checkthecoach-web': 0, 'stackfinder-web': 2, 'bauteamcheck-web': 1 };
        check(item.hosts?.replaceAll(',', '') === String(expectedHosts[item.id]), `${width}: ${item.id} real distinct host count`);
      }
    }
    check(await page.evaluate(w => document.documentElement.scrollWidth <= w + 1, width), `${width}: no horizontal overflow`);
    const targets = await page.locator('main a[href]').evaluateAll(links => [...new Set(links.map(a => a.getAttribute('href')))]);
    check(targets.length === 10, `${width}: ten standard YaCy destinations`);
    // Use a browser page for YaCy's Digest authentication, not Playwright's
    // API request client (which only sends Basic credentials).
    const detailPage = await context.newPage();
    detailPage.on('dialog', d => d.dismiss());
    for (const target of targets) {
      const detail = await detailPage.goto(base + '/' + target, { waitUntil: 'domcontentloaded' });
      check(detail.status() === 200, `${width}: existing detail ${target}`);
    }
    await detailPage.close();
    check(errors.length === 0, `${width}: no JavaScript errors`);
    if (shots) await page.screenshot({ path: path.join(shots, `dashboard-${width}.png`), fullPage: true });
    if (mobile) await page.locator('#scoutro-nav-toggle').click();
    if (mobile) check(await page.locator('#scoutro-system a[href="Steering.html"]:visible').count() === 0, `${width}: mobile overview has no steering actions`);
    const nav = page.locator('#scoutro-adminnav a[href="scoutro-dashboard.html"]');
    check(await nav.isVisible(), `${width}: dashboard reachable in ${mobile ? 'hamburger' : 'sidebar'}`);
    await nav.scrollIntoViewIfNeeded();
    const box = await nav.boundingBox();
    check(box.x >= 0 && box.x + box.width <= width + 1, `${width}: navigation stays in viewport`);
    if (mobile) {
      check(box.height >= 40, `${width}: mobile dashboard tap target`);
      if (shots) await page.screenshot({ path: path.join(shots, `dashboard-navigation-${width}.png`) });
    }
    await nav.click();
    await page.waitForLoadState('domcontentloaded');
    check(page.url().endsWith('/scoutro-dashboard.html'), `${width}: navigation opens dashboard`);
    await context.close();
  }
} finally { await browser.close(); }
console.log(`PASS: ${checks} dashboard UI checks`);
