#!/usr/bin/env node
/*
 * Scoutro mobile / responsive UI checks.
 *
 * Copyright (C) 2026 Scoutro contributors
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option)
 * any later version. See gpl.txt.
 *
 * Runs against a running Scoutro/YaCy instance with Playwright (library only,
 * no test runner needed). Reproducible checks, not only screenshots:
 *   - the mobile hamburger opens the complete administration navigation
 *   - every navigation entry is visible, reachable, inside the viewport and
 *     large enough to tap (>= 40px) on 360 / 390 / 412 px
 *   - desktop keeps the sidebar and the top bar
 *   - no horizontal page overflow, no controls outside the viewport
 *     (unless inside a horizontal scroll container)
 *   - no JavaScript errors
 *   - navigation works, the search form works
 *   - admin pages still require authentication
 *
 * Usage:
 *   SCOUTRO_URL=http://127.0.0.1:8090 \
 *   SCOUTRO_ADMIN_USER=admin SCOUTRO_ADMIN_PASSWORD=yacy \
 *   node test/scoutro-ui/scoutro-ui-test.mjs [--screenshots DIR]
 *
 * Playwright is resolved from NODE_PATH / the global node_modules; install it
 * with "npm install -g playwright" if missing. Use test credentials of a
 * disposable test instance only.
 */

import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

function loadPlaywright() {
  const require = createRequire(import.meta.url);
  try {
    return require('playwright');
  } catch (e) {
    const globalRoot = execSync('npm root -g').toString().trim();
    return require(path.join(globalRoot, 'playwright'));
  }
}

const { chromium } = loadPlaywright();

const BASE = (process.env.SCOUTRO_URL || 'http://127.0.0.1:8090').replace(/\/$/, '');
const USER = process.env.SCOUTRO_ADMIN_USER || 'admin';
const PASSWORD = process.env.SCOUTRO_ADMIN_PASSWORD || 'yacy';
const shotIdx = process.argv.indexOf('--screenshots');
const SHOTS = shotIdx > 0 ? process.argv[shotIdx + 1] : null;

const VIEWPORTS = [
  { name: 'mobile-360', width: 360, height: 740, mobile: true },
  { name: 'mobile-390', width: 390, height: 844, mobile: true },
  { name: 'mobile-412', width: 412, height: 915, mobile: true },
  { name: 'desktop-1280', width: 1280, height: 800, mobile: false },
];

/* navigation entries that must be reachable on every viewport */
const REQUIRED_NAV = [
  'ConfigBasic.html', 'CrawlStartSite.html', 'Status.html', 'IndexBrowser_p.html',
  'AccessGrid_p.html', 'Crawler_p.html', 'CrawlStartExpert.html', 'IndexControlURLs_p.html',
  'Settings_p.html', 'Blacklist_p.html', 'Performance_p.html', 'ConfigPortal_p.html',
  'ConfigAppearance_p.html', 'RankingSolr_p.html',
];
/* additionally required inside the mobile panel (top-bar functions and shortcuts) */
const REQUIRED_MOBILE_NAV = [
  'index.html', 'Steering.html', 'ViewProfile.html', 'jslicense.html',
  'ConfigAccounts_p.html', 'ConfigNetwork_p.html', 'ConfigSearchPage_p.html',
];

/* pages checked for layout problems (Status / Accounts / Crawl Start / Crawler / Network / Search config / search) */
const PAGES = [
  'Status.html', 'ConfigBasic.html', 'ConfigAccounts_p.html', 'ConfigNetwork_p.html',
  'CrawlStartSite.html', 'CrawlStartExpert.html', 'Crawler_p.html', 'IndexControlURLs_p.html',
  'ConfigSearchPage_p.html', 'ConfigPortal_p.html', 'Performance_p.html', 'Settings_p.html',
  'Network.html', 'index.html', 'yacysearch.html?query=scoutro&resource=local',
];

const failures = [];
const results = [];
function check(ok, vp, what, detail) {
  results.push({ ok, vp, what, detail });
  if (!ok) failures.push(`${vp}: ${what}${detail ? ' - ' + detail : ''}`);
}

/*
 * Measured against the configured viewport width: on mobile, content that is
 * too wide enlarges the layout viewport (innerWidth), which would hide the
 * problem and push the fixed navbar and its toggle out of the visible area.
 */
async function layoutMetrics(page, width) {
  return page.evaluate((width) => {
    const visible = (el) => {
      const r = el.getBoundingClientRect();
      const s = getComputedStyle(el);
      return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
    };
    const inScroller = (el) => {
      for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
        const s = getComputedStyle(p);
        if ((s.overflowX === 'auto' || s.overflowX === 'scroll') && p.scrollWidth > p.clientWidth) return true;
      }
      return false;
    };
    const outside = [...document.querySelectorAll('input:not([type=hidden]), select, textarea, button, a.btn')]
      .filter(visible)
      /* bootstrap-switch keeps the real checkbox clipped next to the visible switch */
      .filter((el) => !el.closest('.bootstrap-switch-container'))
      .filter((el) => { const r = el.getBoundingClientRect(); return r.right > width + 1 || r.left < -1; })
      .filter((el) => !inScroller(el))
      .map((el) => (el.id ? '#' + el.id : el.name ? el.tagName.toLowerCase() + '[name=' + el.name + ']' : el.tagName.toLowerCase()));
    return {
      overflowX: Math.max(document.documentElement.scrollWidth, innerWidth) - width,
      outside: outside.slice(0, 8),
      outsideCount: outside.length,
    };
  }, width);
}

async function checkNavigation(page, vp) {
  await page.goto(BASE + '/Status.html', { waitUntil: 'domcontentloaded' });
  /* generic selectors, so that the script also reports on an unmodified YaCy */
  const toggle = page.locator('.navbar-fixed-top .navbar-toggle').first();
  const nav = '.sidebar';
  const required = vp.mobile ? REQUIRED_NAV.concat(REQUIRED_MOBILE_NAV) : REQUIRED_NAV;

  if (vp.mobile) {
    const tb = await toggle.boundingBox({ timeout: 5000 }).catch(() => null);
    check(!!tb && tb.x + tb.width <= vp.width + 1, vp.name, 'hamburger toggle inside viewport', tb ? `right=${Math.round(tb.x + tb.width)}` : 'not rendered');
    check(await toggle.isVisible(), vp.name, 'hamburger toggle visible');
    const clicked = await toggle.click({ timeout: 5000 }).then(() => true, () => false);
    check(clicked, vp.name, 'hamburger toggle can be tapped (not covered)');
    const panel = page.locator(nav).first();
    check(await panel.isVisible(), vp.name, 'navigation panel opens');
    check((await toggle.getAttribute('aria-expanded')) === 'true', vp.name, 'toggle reports aria-expanded=true');
  } else {
    check(!(await toggle.isVisible()), vp.name, 'no hamburger on desktop');
    check(await page.locator(nav).first().isVisible(), vp.name, 'sidebar visible on desktop');
    check(await page.locator('#header_shutdown').isVisible(), vp.name, 'top bar visible on desktop');
    check(!(await page.locator('#scoutro-system').isVisible()), vp.name, 'mobile-only groups hidden on desktop');
  }

  const missing = [];
  const tooSmall = [];
  const offscreen = [];
  for (const href of required) {
    const link = page.locator(`${nav} a[href^="${href}"]:visible`).first();
    if ((await link.count()) === 0) { missing.push(href); continue; }
    await link.scrollIntoViewIfNeeded();
    const box = await link.boundingBox();
    if (!box) { missing.push(href); continue; }
    if (box.x < -1 || box.x + box.width > vp.width + 1 || box.y < 0 || box.y + box.height > vp.height + 1) offscreen.push(href);
    if (vp.mobile && box.height < 40) tooSmall.push(`${href}(${Math.round(box.height)}px)`);
    /* the element at the link centre must be the link itself (no overlay) */
    const hit = await page.evaluate(({ x, y, href }) => {
      const el = document.elementFromPoint(x, y);
      return !!(el && el.closest && el.closest(`a[href^="${href}"]`));
    }, { x: box.x + box.width / 2, y: box.y + box.height / 2, href });
    if (!hit) offscreen.push(href + '(covered)');
  }
  check(missing.length === 0, vp.name, `all ${required.length} navigation entries present`, missing.join(', '));
  check(offscreen.length === 0, vp.name, 'navigation entries reachable, inside viewport, not covered', offscreen.join(', '));
  if (vp.mobile) check(tooSmall.length === 0, vp.name, 'touch targets >= 40px', tooSmall.join(', '));

  if (SHOTS && vp.mobile) await page.screenshot({ path: path.join(SHOTS, `${vp.name}-nav-open.png`) });

  if (vp.mobile) {
    await page.keyboard.press('Escape');
    check(!(await page.locator(nav).first().isVisible()), vp.name, 'Escape closes the panel');
    await toggle.click({ timeout: 5000 }).catch(() => {});
  }
  /* navigating from the menu works */
  const navigated = await Promise.all([
    page.waitForURL(/ConfigBasic\.html/, { timeout: 10000 }),
    page.locator(`${nav} a[href^="ConfigBasic.html"]:visible`).first().click({ timeout: 5000 }),
  ]).then(() => true, () => false);
  check(navigated && /ConfigBasic\.html/.test(page.url()), vp.name, 'menu link navigates');
  if (!navigated) await page.goto(BASE + '/ConfigBasic.html', { waitUntil: 'domcontentloaded' });
  /* page submenus (e.g. Accounts, Network Configuration) are reachable */
  const sub = page.locator('.SubMenu a[href^="ConfigNetwork_p.html"]:visible').first();
  check((await sub.count()) === 1, vp.name, 'page submenu entry visible (ConfigNetwork_p.html)');
}

async function checkPages(page, vp, errors) {
  for (const p of PAGES) {
    errors.length = 0;
    const resp = await page.goto(BASE + '/' + p, { waitUntil: 'load' });
    await page.waitForTimeout(300);
    check(resp && resp.status() === 200, vp.name, `${p} loads`, resp ? String(resp.status()) : 'no response');
    const m = await layoutMetrics(page, vp.width);
    check(m.overflowX <= 1, vp.name, `${p} no horizontal page overflow`, m.overflowX > 1 ? `${m.overflowX}px` : '');
    check(m.outsideCount === 0, vp.name, `${p} no controls outside viewport`, m.outside.join(' '));
    check(errors.length === 0, vp.name, `${p} no JavaScript errors`, errors.slice(0, 3).join(' | '));
    if (SHOTS) await page.screenshot({ path: path.join(SHOTS, `${vp.name}-${p.replace(/[^a-z0-9_.-]/gi, '_')}.png`), fullPage: false });
  }
}

async function checkSearchForm(page, vp) {
  await page.goto(BASE + '/index.html', { waitUntil: 'domcontentloaded' });
  const tagline = page.locator('.scoutro-hero .scoutro-tagline');
  check(await tagline.isVisible(), vp.name, 'start page tagline visible');
  const taglineText = ((await tagline.textContent()) || '').trim();
  check(taglineText === 'Search. Crawl. Discover.', vp.name, 'start page tagline text', taglineText);
  const input = page.locator('#search');
  check(await input.isVisible(), vp.name, 'search field visible');
  await input.fill('scoutro');
  await Promise.all([page.waitForURL(/yacysearch\.html/), input.press('Enter')]);
  check(/yacysearch\.html\?.*query=scoutro/.test(page.url()), vp.name, 'search form submits');
}

/* the public search header (simpleSearchHeader.template) keeps its own menu with the way to the administration */
async function checkPublicHeader(page, vp) {
  for (const p of ['index.html', 'yacysearch.html?query=scoutro&resource=local']) {
    await page.goto(BASE + '/' + p, { waitUntil: 'domcontentloaded' });
    const admin = page.locator('#header_administration button');
    if (vp.mobile) {
      const toggle = page.locator('.navbar-fixed-top .navbar-toggle').first();
      const tb = await toggle.boundingBox();
      check(!!tb && (await toggle.isVisible()) && tb.x + tb.width <= vp.width + 1, vp.name, `${p} search header menu toggle visible`);
      if (tb) {
        await toggle.click();
        await admin.waitFor({ state: 'visible', timeout: 3000 }).catch(() => {});
      }
    }
    check(await admin.isVisible(), vp.name, `${p} link to the administration reachable`);
    check((await page.locator('footer.scoutro-attribution', { hasText: 'Powered by' }).count()) === 1, vp.name, `${p} attribution footer present`);
  }
  await page.goto(BASE + '/Status.html', { waitUntil: 'domcontentloaded' });
  check((await page.locator('footer.scoutro-attribution', { hasText: 'Powered by' }).count()) === 1, vp.name, 'Status.html attribution footer present');
}

/* results page: search pill stays on one row; empty state appears for a search without results */
async function checkResultsPage(page, vp) {
  await page.goto(BASE + '/yacysearch.html?query=zzqxscoutronoresult&resource=local', { waitUntil: 'load' });
  const pill = await page.locator('form[name="searchform"] .input-group').boundingBox();
  const button = await page.locator('form[name="searchform"] #Enter').boundingBox();
  check(!!pill && !!button && button.height <= 48 && button.y >= pill.y - 1 && button.y + button.height <= pill.y + pill.height + 1,
    vp.name, 'results search field and button on one row', button ? `button ${Math.round(button.width)}x${Math.round(button.height)}` : 'missing');
  /* the search button is a round icon button (no wrapping text); the label only serves screen readers */
  const enter = page.locator('form[name="searchform"] #Enter');
  check((await enter.locator('.glyphicon-search').count()) === 1, vp.name, 'results search button shows the search icon');
  check(!!button && Math.abs(button.width - button.height) <= 2 && button.width <= 48, vp.name, 'results search button is a round icon',
    button ? `${Math.round(button.width)}x${Math.round(button.height)}` : 'missing');
  const label = await enter.locator('.scoutro-button-label').boundingBox();
  check(!label || (label.width <= 1 && label.height <= 1), vp.name, 'results search button label is visually hidden',
    label ? `${label.width}x${label.height}` : 'no box');
  check(((await enter.getAttribute('aria-label')) || (await enter.textContent()) || '').trim().length > 0, vp.name,
    'results search button has an accessible name');
  const bg = await page.locator('form[name="searchform"] .input-group').evaluate(e => getComputedStyle(e).backgroundImage);
  check(/mascot-64\.png/.test(bg), vp.name, 'results search pill shows the mascot asset', bg.slice(0, 60));
  const empty = page.locator('#scoutro-empty-results');
  const shown = await empty.waitFor({ state: 'visible', timeout: 8000 }).then(() => true, () => false);
  check(shown, vp.name, 'empty state shown for a search without results');
  if (shown) {
    const img = await page.locator('#scoutro-empty-results img').evaluate((i) => i.complete && i.naturalWidth > 0);
    check(img, vp.name, 'empty state illustration loaded');
  }
}

async function checkAuth(browser) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  for (const p of ['ConfigAccounts_p.html', 'ConfigNetwork_p.html', 'Settings_p.html', 'ConfigPortal_p.html']) {
    const r = await page.goto(BASE + '/' + p);
    check(r.status() === 401, 'anonymous', `${p} requires authentication`, String(r.status()));
  }
  const r = await page.goto(BASE + '/index.html');
  check(r.status() === 200, 'anonymous', 'public search page reachable', String(r.status()));
  await ctx.close();
}

const browser = await chromium.launch({ args: ['--no-proxy-server'] });
try {
  if (SHOTS) fs.mkdirSync(SHOTS, { recursive: true });
  await checkAuth(browser);
  for (const vp of VIEWPORTS) {
    const ctx = await browser.newContext({
      viewport: { width: vp.width, height: vp.height },
      isMobile: vp.mobile,
      hasTouch: vp.mobile,
      httpCredentials: { username: USER, password: PASSWORD },
    });
    const page = await ctx.newPage();
    page.setDefaultTimeout(20000);
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('dialog', (d) => d.dismiss()); /* never confirm restart/shutdown */
    for (const step of [checkNavigation, checkPublicHeader, checkPages, checkSearchForm, checkResultsPage]) {
      try {
        await step(page, vp, errors);
      } catch (e) {
        check(false, vp.name, `${step.name} aborted`, e.message.split('\n')[0]);
      }
    }
    await ctx.close();
  }
} finally {
  await browser.close();
}

const passed = results.filter((r) => r.ok).length;
for (const r of results.filter((r) => !r.ok)) console.log(`FAIL ${r.vp}: ${r.what}${r.detail ? ' - ' + r.detail : ''}`);
console.log(`\n${passed}/${results.length} checks passed`);
process.exit(failures.length ? 1 : 0);
