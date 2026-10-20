#!/usr/bin/env node
/* GPL-2.0-or-later. The correction proposals of Settings > Collections (WP-H) with offline API fixtures: status, the random
 * sample, approve with confirmation, the judgement right/wrong, the acceptance refused with what is missing, the breaker;
 * English and German, desktop and phone, no overflow. Names are examples. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const { chromium } = createRequire(import.meta.url)('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..'), base = 'http://127.0.0.1:8090';
let checks = 0; const check = (v, m) => { assert(v, m); checks++; };

const page0 = fs.readFileSync(path.join(root, 'htroot/ScoutroCollections_p.html'), 'utf8');
const section = fs.readFileSync(path.join(root, 'locales/de.lng'), 'utf8').split('#File: ScoutroCollections_p.html\n')[1].split('#File: ')[0];
const view = page0.slice(page0.indexOf('<section class="scoutro-card" id="sca"'), page0.indexOf('</main>'));
for (const text of [...view.matchAll(/>([^<>\n]*[A-Za-z][^<>\n]*)</g)].map(m => m[1].trim()).filter(Boolean))
  check(section.includes('\n>' + text + '<=='), 'German text for: ' + text);
for (const [, text] of page0.matchAll(/<span data-sco-label="ac_[a-z_0-9]+">([^<]*)<\/span>/g))
  check(section.includes('\n>' + text + '<=='), 'German label for: ' + text);

const signal = (family, verdict, collection, origin) => ({ family, verdict, collection, origin, evidence: family + ' ' + verdict });
const cases = () => [
  { domain: 'dach-muster.de', from: 'edelsenior-web', target: 'bauteamcheck-web', result: 'correct', reason: 'specialist_target', severity: 'high',
    status: 'proposed', sample: { at: 1, by: 'admin' }, signals: [signal('text_fit', 'against', 'edelsenior-web', 'website:dach-muster.de'),
      signal('origin', 'against', 'edelsenior-web', 'osm:niedersachsen')] },
  { domain: 'unklar-muster.de', from: 'edelsenior-web', target: null, result: 'review', reason: 'one_origin_only', status: 'proposed',
    suggested: 'sonstige', signals: [signal('llm', 'against', 'edelsenior-web', 'website:unklar-muster.de'),
      signal('plausibility', 'against', 'edelsenior-web', 'website:unklar-muster.de')] },
  { domain: 'auto-muster.de', from: 'edelsenior-web', target: 'sonstige', result: 'correct', reason: 'fallback_target', severity: 'medium',
    status: 'applied', sample: { at: 2, by: 'audit', audit: true }, signals: [] }];
const report = breaker => ({ available: true, breaker: { open: breaker }, acceptance: { accepted: false },
  settings: { mode: 'propose', minReviewed: 299, minLowerBound: 0.99 }, stats: {
    sample: { reviewed: 12, right: 12, pending: 1, lowerBound95: 0.779, reviewsForTarget: 287, stage: 'none' },
    all: { reviewed: 14, right: 13, pending: 2 } }, cases: cases() });
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] });
try {
  for (const [language, width] of [['en', 1280], ['de', 390], ['de', 360]]) {
    const where = ` (${language}/${width})`, posts = [];
    let breaker = true;
    const context = await browser.newContext({ viewport: { width, height: 900 }, locale: language }), page = await context.newPage(), errors = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('dialog', d => d.accept());
    await page.route(base + '/**', async route => {
      const u = new URL(route.request().url()), method = route.request().method();
      if (u.pathname === '/ScoutroCollections_p.html') {
        let html = page0.replace(/#%[^\n]*?%#/g, '');
        if (language === 'de') {
          html = html.replace('lang="en"', 'lang="de"');
          for (const [key, value] of section.split('\n').filter(l => l.includes('==') && l.startsWith('>')).map(l => l.split('==')).sort((a, b) => b[0].length - a[0].length))
            html = html.split(key).join(value);
        }
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
      }
      if (u.pathname === '/env/scoutro/collections.js') return route.fulfill({ contentType: 'application/javascript', body: 'window.ScoutroCollections=null;' });
      if (u.pathname.startsWith('/env/scoutro/')) return route.fulfill({ contentType: u.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(path.join(root, 'htroot', u.pathname.slice(1)), 'utf8') });
      if (u.pathname === '/scoutro/api/v1/collections') return route.fulfill({ json: { collections: [{ id: 'edelsenior-web', documents: 10, sources: ['index'] },
        { id: 'bauteamcheck-web', documents: 5, sources: ['index'] }, { id: 'sonstige', documents: 1, sources: ['index'] }] } });
      if (u.pathname === '/scoutro/api/v1/collections/autocorrect') return route.fulfill({ json: report(breaker) });
      if (u.pathname.startsWith('/scoutro/api/v1/collections/autocorrect/') && method === 'POST') {
        const body = JSON.parse(route.request().postData() || '{}'), action = u.pathname.split('/').pop();
        posts.push([action, body]);
        if (action === 'accept') return route.fulfill({ status: 409, json: { error: { code: 'acceptance_not_met', message: 'not met',
          details: { missing: ['1 drawn cases not reviewed', 'reviewed in the sample 12 < 299'] } } } });
        if (action === 'sample') return route.fulfill({ json: { draw: { drawn: 2, population: 7 }, stats: {} } });
        if (action === 'breaker-reset') { breaker = false; return route.fulfill({ json: { breaker: { open: false } } }); }
        return route.fulfill({ json: { case: {}, stats: {} } });
      }
      return route.fulfill({ status: 404, body: '' });
    });
    await page.goto(base + '/ScoutroCollections_p.html');
    await page.waitForFunction(() => document.querySelectorAll('#sca-table tbody tr[data-domain]').length === 3);
    const status = await page.locator('#sca-status').textContent();
    check(status.includes('287') && status.includes(language === 'de' ? 'Sicherung' : 'Breaker'), 'status with the reviews still needed: ' + status + where);
    check(!(await page.locator('#sca-breaker').isHidden()), 'the open breaker can be closed' + where);
    const first = await page.locator('#sca-table tbody tr[data-domain="dach-muster.de"]').textContent();
    check(first.includes('bauteamcheck-web') && first.includes('osm') && first.includes('website'), 'signals grouped by source: ' + first + where);
    check(first.includes(language === 'de' ? 'gezogen' : 'drawn'), 'drawn badge' + where);
    // approve after the confirmation
    await page.locator('#sca-table tbody tr[data-domain="dach-muster.de"] button').first().click();
    await page.waitForFunction(() => document.getElementById('sca-message').textContent.length > 0);
    check(JSON.stringify(posts[0]) === JSON.stringify(['review', { domain: 'dach-muster.de', verdict: 'approve' }]), 'approve posts the domain: ' + JSON.stringify(posts[0]) + where);
    // a case for review needs the target: the suggested one is preselected and named as a hint
    const review = page.locator('#sca-table tbody tr[data-domain="unklar-muster.de"]');
    check(await review.locator('select').inputValue() === 'sonstige', 'the suggested target is preselected' + where);
    check((await review.textContent()).includes(language === 'de' ? 'Vorgeschlagenes Ziel: sonstige' : 'Suggested target: sonstige'),
      'the suggestion is named as a hint' + where);
    check((await review.textContent()).includes('plausibility against edelsenior-web'), 'the plausibility signal is listed' + where);
    await page.locator('#sca-table tbody tr[data-domain="unklar-muster.de"] select').selectOption('sonstige');
    await page.locator('#sca-table tbody tr[data-domain="unklar-muster.de"] button').first().click();
    await page.waitForFunction(n => window.__posts !== n, 0).catch(() => {});
    await page.waitForTimeout(100);
    check(posts.some(p => p[0] === 'review' && p[1].domain === 'unklar-muster.de' && p[1].target === 'sonstige'), 'the chosen target is sent' + where);
    // an applied correction is judged right or wrong
    const applied = page.locator('#sca-table tbody tr[data-domain="auto-muster.de"] button');
    check(await applied.count() === 2, 'right and wrong for an applied correction' + where);
    await applied.nth(1).click();
    await page.waitForTimeout(100);
    check(posts.some(p => p[0] === 'review' && p[1].domain === 'auto-muster.de' && p[1].verdict === 'wrong'), 'wrong is sent' + where);
    // the acceptance answers what is missing
    await page.locator('#sca-accept').click();
    await page.waitForFunction(() => document.getElementById('sca-message').textContent.includes('299'));
    check((await page.locator('#sca-message').textContent()).includes('not reviewed'), 'missing reviews reported' + where);
    await page.fill('#sca-size', '2');
    await page.locator('#sca-sample').click();
    await page.waitForFunction(() => document.getElementById('sca-message').textContent.includes('7'));
    check(posts.some(p => p[0] === 'sample' && p[1].size === 2), 'the sample size is sent' + where);
    check(await page.locator('#sca-drawn').isChecked(), 'the list shows the drawn cases' + where);
    check(await page.locator('#sca-table tbody tr[data-domain]').count() === 2, 'only drawn cases' + where);
    await page.locator('#sca-breaker').click();
    await page.waitForFunction(() => document.getElementById('sca-breaker').hidden);
    check(posts.some(p => p[0] === 'breaker-reset'), 'breaker reset' + where);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    check(overflow <= 1, 'no horizontal overflow: ' + overflow + where);
    check(errors.length === 0, 'no script errors: ' + errors.join('; ') + where);
    await context.close();
  }
} finally {
  await browser.close();
}
console.log('collection correction UI: ' + checks + ' checks passed');
