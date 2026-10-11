#!/usr/bin/env node
/* Scoutro contributors, GPL-2.0-or-later. Run only from test/scoutro-access/accounts-ui-live-smoke.py (disposable peer).
 * Multi-user browser check of docs/SCOUTRO_USERS_ACCESS.md: sign-in with return target, the navigation of each role
 * on desktop and phone, the collection context, the Scoutro search, the knowledge page without operations for
 * accounts, the 403 page, guests and sign-out. Passwords come from the environment (generated per run). */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
assert(base && new URL(base).hostname === '127.0.0.1', 'Disposable loopback peer required');
const users = JSON.parse(process.env.SCOUTRO_TEST_USERS || '{}');
const shots = process.env.SCOUTRO_SCREENSHOTS;
if (shots) fs.mkdirSync(shots, { recursive: true });
let checks = 0;
const failures = [];
function check(value, label) {
  checks++;
  if (!value) { failures.push(label); console.log('FAIL: ' + label); }
}

const GROUPS = ['#scoutro-nav-overview', '#scoutro-nav-research', '#scoutro-nav-knowledge', '#scoutro-nav-collect',
  '#scoutro-nav-settings', '#scoutro-nav-advanced'];
const EXPECT = {
  research: { groups: ['#scoutro-nav-overview', '#scoutro-nav-research', '#scoutro-nav-knowledge'], collections: ['stackfinder-web'],
    role: { en: 'Research', de: 'Recherche' }, allowed: ['/scoutro-search.html', '/IndexBrowser_p.html', '/ScoutroSEO_p.html', '/yacychat.html'],
    refused: ['/ScoutroCrawls_p.html', '/ScoutroUsers_p.html', '/Status.html'] },
  operator: { groups: ['#scoutro-nav-overview', '#scoutro-nav-research', '#scoutro-nav-knowledge', '#scoutro-nav-collect'],
    collections: ['bauteamcheck-web', 'edelsenior-web'], role: { en: 'Operator', de: 'Operator' },
    allowed: ['/ScoutroCrawls_p.html', '/ScoutroDiscovery_p.html', '/ScoutroSEO_p.html?view=report'],
    refused: ['/ScoutroUsers_p.html', '/ScoutroSystem_p.html', '/Crawler_p.html'] },
  administrator: { groups: GROUPS, collections: null, role: { en: 'Administrator', de: 'Administrator' },
    allowed: ['/ScoutroUsers_p.html', '/ScoutroSystem_p.html', '/Status.html'], refused: [] },
};
const TEXT = { en: { forbidden: 'No permission', signedOut: 'You are signed out.' },
               de: { forbidden: 'Keine Berechtigung', signedOut: 'Sie sind abgemeldet.' } };

const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH, args: ['--no-proxy-server'] });

async function navOpen(page, mobile) {
  if (!mobile) return;
  const toggle = page.locator('#scoutro-nav-toggle');
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') await toggle.click();
  await page.locator('#scoutro-adminnav').waitFor({ state: 'visible' });
}

async function signIn(page, username, password, target) {
  const response = await page.goto(base + target, { waitUntil: 'domcontentloaded' });
  const url = new URL(page.url());
  check(url.pathname === '/scoutro-login.html' && url.searchParams.get('next') === target, `${username}: ${target} leads to the login page with the return target (${page.url()}, ${response?.status()})`);
  await page.fill('#sa-username', username);
  await page.fill('#sa-password', password);
  await Promise.all([page.waitForURL(u => new URL(u).pathname === target.split('?')[0], { timeout: 20000 }).catch(() => {}), page.click('#sa-submit')]);
  check(new URL(page.url()).pathname === target.split('?')[0], `${username}: back on ${target} after sign-in (${page.url()})`);
}

async function shot(page, name) {
  if (shots) await page.screenshot({ path: path.join(shots, name + '.png'), fullPage: false });
}

try {
  for (const [role, expected] of Object.entries(EXPECT)) {
    const account = users[role];
    assert(account, 'test user for ' + role);
    for (const language of ['en', 'de']) for (const width of [1280, 390]) {
      const mobile = width < 768, where = ` (${role}, ${language}, ${width})`;
      const context = await browser.newContext({ viewport: { width, height: 900 }, isMobile: mobile, hasTouch: mobile, locale: language,
        extraHTTPHeaders: { 'Accept-Language': language } });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      await signIn(page, account.username, account.password, '/scoutro-dashboard.html');
      check((await page.locator('#scoutro-account-menu summary').textContent()).includes(account.displayName), 'account menu shows the name' + where);
      await page.locator('#scoutro-account-menu summary').click();
      check((await page.locator('.scoutro-account-role').first().textContent()).trim() === expected.role[language], 'account menu shows the role' + where);
      await page.keyboard.press('Escape');

      // navigation: the groups of the role, the same on desktop and phone
      await navOpen(page, mobile);
      for (const group of GROUPS) {
        const present = await page.locator(group).count() > 0;
        check(present === expected.groups.includes(group), `navigation group ${group} ${expected.groups.includes(group) ? 'shown' : 'absent'}` + where);
      }
      check(await page.locator('#header_restart, #header_shutdown, a[href="Steering.html"]').count() === 0, 'no Re-Start/Shutdown in the header' + where);
      await shot(page, `${role}-dashboard-${language}-${width}${mobile ? '-nav' : ''}`);
      if (mobile) await page.keyboard.press('Escape');

      // collection context: only the role's collections
      await page.locator('#scoutro-context').waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
      const options = await page.locator('#scoutro-context-select option').evaluateAll(list => list.map(o => o.value).filter(Boolean).sort());
      if (expected.collections) check(JSON.stringify(options) === JSON.stringify(expected.collections), `context lists only own collections ${JSON.stringify(options)}` + where);
      else check(options.length >= 4, 'context lists the catalog for the administrator' + where);
      const cards = await page.locator('[data-collection]').evaluateAll(list => list.map(e => e.dataset.collection).sort());
      if (expected.collections) check(JSON.stringify(cards) === JSON.stringify(expected.collections), `overview cards are the own collections ${JSON.stringify(cards)}` + where);
      check((await page.locator('main').innerText()).includes('JVM') === (role === 'administrator'), 'system figures only for the administrator' + where);

      // pages by permission and the 403 page
      for (const target of expected.allowed) {
        const r = await page.goto(base + target, { waitUntil: 'domcontentloaded' });
        check(r.status() === 200, `page ${target} opens` + where);
      }
      for (const target of expected.refused) {
        const r = await page.goto(base + target, { waitUntil: 'domcontentloaded' });
        check(r.status() === 403 && (await page.locator('h1').first().textContent()).trim() === TEXT[language].forbidden, `page ${target} shows the 403 page` + where);
        if (target === expected.refused[0] && width === 1280) await shot(page, `${role}-forbidden-${language}`);
      }

      if (role === 'administrator' && language === 'en') {
        // every page of the navigation opens, YaCy's own pages under "Advanced tools" included (desktop and phone)
        await page.goto(base + '/scoutro-dashboard.html', { waitUntil: 'domcontentloaded' });
        await navOpen(page, mobile);
        await page.locator('#scoutro-nav-advanced > summary').click();
        check(await page.locator('#scoutro-nav-advanced a[href="ConfigAccounts_p.html"]').isVisible(), 'advanced tools open in the navigation' + where);
        if (mobile) await shot(page, 'administrator-advanced-tools-390');
        const knownErrors = errors.length; // YaCy pages keep their own upstream script errors (docs/UI.md, known issues)
        const targets = await page.locator('#scoutro-adminnav a[href]').evaluateAll(list => [...new Set(list
          .map(a => a.getAttribute('href')).filter(h => h && !/^(https?:|#|javascript:)/.test(h)))]);
        check(targets.length >= 45, `navigation lists the Scoutro and YaCy pages (${targets.length})` + where);
        for (const target of targets) {
          const r = await page.goto(base + '/' + target, { waitUntil: 'domcontentloaded' });
          const body = await page.content();
          check(r.status() === 200 && !body.includes('UNRESOLVED_PATTERN'), `navigation page ${target} opens (${r.status()})` + where);
        }
        errors.splice(knownErrors);
      }

      if (role !== 'administrator') {
        // the header search goes to the Scoutro search within the collections
        await page.goto(base + '/scoutro-dashboard.html', { waitUntil: 'domcontentloaded' });
        await page.fill('#header-search-input', 'page');
        await Promise.all([page.waitForURL(/scoutro-search\.html/), page.locator('#header-search-input').press('Enter')]);
        await page.locator('#ssr-message, #ssr-results').first().waitFor({ state: 'attached' });
        await page.waitForFunction(() => !document.getElementById('ssr-submit').disabled, null, { timeout: 15000 }).catch(() => {});
        const selectable = await page.locator('#ssr-collection option').evaluateAll(list => list.map(o => o.value).filter(Boolean).sort());
        check(JSON.stringify(selectable) === JSON.stringify(expected.collections), 'search offers only own collections' + where);
        check(await page.evaluate(w => document.documentElement.scrollWidth <= w + 1, width), 'search page without horizontal overflow' + where);
        await shot(page, `${role}-search-${language}-${width}`);
        // knowledge without the operations view
        await page.goto(base + '/ScoutroKnowledge_p.html?view=operations', { waitUntil: 'domcontentloaded' });
        check(await page.locator('#skg-view-overview').isHidden() && await page.locator('#skg-view-settings').isHidden(), 'knowledge operations tabs hidden' + where);
        check(await page.locator('#skg-objects').isVisible() && await page.locator('#skg-overview').isHidden(), 'operations view falls back to the objects' + where);
        // index.html leads to the Scoutro search
        await page.goto(base + '/index.html', { waitUntil: 'domcontentloaded' });
        check(new URL(page.url()).pathname === '/scoutro-search.html', 'start page leads to the Scoutro search' + where);
      }

      // sign-out ends the session
      await page.goto(base + '/scoutro-dashboard.html', { waitUntil: 'domcontentloaded' });
      await page.locator('#scoutro-account-menu summary').click();
      await Promise.all([page.waitForURL(/scoutro-login\.html\?signedout=1/), page.locator('#scoutro-signout').click()]);
      check((await page.locator('#sa-message').textContent()).trim() === TEXT[language].signedOut, 'signed-out message' + where);
      await page.goto(base + '/scoutro-dashboard.html', { waitUntil: 'domcontentloaded' });
      check(new URL(page.url()).pathname === '/scoutro-login.html', 'after sign-out the overview needs a sign-in again' + where);
      check(errors.length === 0, 'no JavaScript errors ' + JSON.stringify(errors) + where);
      await context.close();
    }
  }

  // guests: released collections only, the search and the sign-in link
  if (process.env.SCOUTRO_GUEST === '1') {
    for (const width of [1280, 390]) {
      const mobile = width < 768, where = ` (guest, ${width})`;
      const context = await browser.newContext({ viewport: { width, height: 900 }, isMobile: mobile, hasTouch: mobile, locale: 'en' });
      const page = await context.newPage();
      await page.goto(base + '/', { waitUntil: 'domcontentloaded' });
      check(new URL(page.url()).pathname === '/scoutro-search.html', 'guest start page is the Scoutro search' + where);
      await page.waitForFunction(() => document.querySelectorAll('#ssr-collection option').length > 1, null, { timeout: 15000 }).catch(() => {});
      const selectable = await page.locator('#ssr-collection option').evaluateAll(list => list.map(o => o.value).filter(Boolean));
      check(JSON.stringify(selectable) === JSON.stringify(['stackfinder-web']), 'guest search offers the released collection' + where);
      check(await page.locator('#scoutro-signin').count() === 1, 'guest sees the sign-in link' + where);
      await navOpen(page, mobile);
      check(await page.locator('#scoutro-nav-research').count() === 1 && await page.locator('#scoutro-nav-overview').count() === 0, 'guest navigation: search only' + where);
      await shot(page, `guest-search-${width}`);
      const r = await page.goto(base + '/scoutro-dashboard.html', { waitUntil: 'domcontentloaded' });
      check(new URL(page.url()).pathname === '/scoutro-login.html', 'guest is sent to the sign-in for the overview' + where + ' ' + r.status());
      await context.close();
    }
  }
} finally {
  await browser.close();
}
console.log(`${checks - failures.length}/${checks} account UI checks passed`);
process.exit(failures.length ? 1 : 0);
