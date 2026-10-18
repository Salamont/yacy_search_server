#!/usr/bin/env node
/* GPL-2.0-or-later. The assessments view (WP-E) with offline API fixtures: list, preview, store with the preview's token,
 * revoke after confirmation, the operator statement form, English and German, desktop and mobile without overflow. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const { chromium } = createRequire(import.meta.url)('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..'), base = 'http://127.0.0.1:8090';
let checks = 0; const check = (v, m) => { assert(v, m); checks++; };

const page0 = fs.readFileSync(path.join(root, 'htroot/ScoutroKnowledge_p.html'), 'utf8');
const section = fs.readFileSync(path.join(root, 'locales/de.lng'), 'utf8').split('#File: ScoutroKnowledge_p.html\n')[1].split('#File: ')[0];
// every visible text of the view and every label has a German translation
const view = page0.slice(page0.indexOf('<section id="skg-assessments"'), page0.indexOf('</section>\n\n<div hidden id="skg-labels">'));
for (const text of [...view.matchAll(/>([^<>\n]*[A-Za-z][^<>\n]*)</g)].map(m => m[1].trim()).filter(Boolean))
  check(section.includes('\n>' + text + '<=='), 'German text for: ' + text);
for (const [, text] of page0.matchAll(/<span data-skg-label="asm_[a-z_]+">([^<]*)<\/span>/g))
  check(section.includes('\n>' + text + '<=='), 'German label for: ' + text);

const item = { id: 'kga_aaaaaaaaaaaaaaaaaaaa', action: 'withdraw', scope: { domain: 'muster-pflege.de' }, predicate: 'price', value: null,
  subject: null, tier: 3, targetPredicate: null, reason: 'statutory benefit', basis: 'manual', status: 'active', actor: 'admin',
  createdAt: 1, revokedAt: null, revokedBy: null, blockedClaims: 2, reviewNeeded: true, withdrawnEvidence: 5, dependentEvidence: 1 };
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] });
try {
  for (const [language, width] of [['en', 1280], ['de', 390], ['de', 360]]) {
    const where = ` (${language}/${width})`, posts = [];
    let listed = [item];
    const context = await browser.newContext({ viewport: { width, height: 900 }, locale: language }), page = await context.newPage(), errors = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('dialog', d => d.accept());
    await page.route(base + '/**', async route => {
      const u = new URL(route.request().url()), method = route.request().method();
      if (u.pathname === '/ScoutroKnowledge_p.html') {
        let html = page0.replace(/#%[^\n]*?%#/g, '').replace('#[scoutroAdmin]#', '1').replace('#[scoutroExport]#', '1');
        if (language === 'de') {
          html = html.replace('lang="en"', 'lang="de"');
          for (const [key, value] of section.split('\n').filter(l => l.includes('==') && !l.startsWith('#')).map(l => l.split('==')).sort((a, b) => b[0].length - a[0].length))
            html = html.split(key).join(value);
        }
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
      }
      if (u.pathname === '/env/scoutro/collections.js')
        return route.fulfill({ contentType: 'application/javascript', body: "window.ScoutroCollections={load:async()=>['kga'],fill:(el,ids)=>{for(const id of ids){const o=document.createElement('option');o.value=id;o.textContent=id;el.append(o);}el.disabled=false;}};" });
      if (u.pathname.startsWith('/env/scoutro/'))
        return route.fulfill({ contentType: u.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(path.join(root, 'htroot', u.pathname.slice(1)), 'utf8') });
      if (u.pathname === '/scoutro/api/v1/kg/assessments' && method === 'GET')
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: listed, active: listed.filter(a => a.status === 'active').length, revoked: 0, reviewNeeded: 1 }) });
      if (u.pathname === '/scoutro/api/v1/kg/assessments' && method === 'POST') {
        const body = JSON.parse(route.request().postData()); posts.push(body);
        if (!body.token) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ preview: true, token: 'tok123', assessment: body,
          changes: { evidence: 3, dependentEvidence: 1, statements: 2, statementsLeaving: 1, documents: 1, observations: 2, matchingContributions: 0,
            tooLarge: false, sample: [{ url: 'https://www.muster-pflege.de/preise', predicate: 'price', value: '{"amount":"125"}', tier: 3, quote: 'Entlastungsbetrag 125 €' }] } }) });
        listed = [item, Object.assign({}, item, { id: 'kga_bbbbbbbbbbbbbbbbbbbb', reviewNeeded: false, blockedClaims: 0, withdrawnEvidence: 3 })];
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ assessment: { id: 'kga_bbbbbbbbbbbbbbbbbbbb' }, applied: { evidence: 3 } }) });
      }
      if (u.pathname === '/scoutro/api/v1/kg/identity-preview' && method === 'POST') {
        posts.push({ identity: JSON.parse(route.request().postData()) });
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ domain: 'muster-shop.de', identity: { productUrl: true, providerServiceKey: false },
          sample: { documents: 3, skipped: 0, truncated: false }, baseline: { entities: 5, services: 3, merges: 0, conflicts: 0, published: 3 },
          preview: { entities: 4, services: 2, merges: 1, conflicts: 1, published: 3 }, documentsChanged: 1,
          merges: ['kge_bbbbbbbbbbbbbbbbbbbb -> kge_aaaaaaaaaaaaaaaaaaaa'], conflicts: ['product page of another name: kge_aaaaaaaaaaaaaaaaaaaa (service Gartentisch)'],
          pages: [], llm: null, written: false }) });
      }
      if (u.pathname.endsWith('/revoke') && method === 'POST') {
        posts.push({ revoke: u.pathname });
        listed = listed.map(a => a.id === 'kga_aaaaaaaaaaaaaaaaaaaa' ? Object.assign({}, a, { status: 'revoked' }) : a);
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ restored: 5 }) });
      }
      if (u.pathname.startsWith('/scoutro/api/v1/kg/')) return route.fulfill({ contentType: 'application/json', body: '{}' });
      return route.fulfill({ status: 404, body: '' });
    });
    await page.goto(base + '/ScoutroKnowledge_p.html?view=assessments');
    await page.waitForFunction(() => document.querySelector('#skg-asm-list tbody tr[data-id]'));
    check(await page.locator('#skg-assessments').isVisible(), 'the view is shown' + where);
    check(await page.locator('#skg-view-assessments').getAttribute('aria-current') === 'page', 'the tab is current' + where);
    const row = await page.locator('#skg-asm-list tbody tr[data-id="kga_aaaaaaaaaaaaaaaaaaaa"]').textContent();
    check(row.includes('muster-pflege.de') && row.includes('price'), 'the stored assessment is listed: ' + row + where);
    check(row.includes(language === 'de' ? 'prüfen' : 'review'), 'the review hint is shown' + where);
    // preview, then store with the preview's token
    await page.fill('#skg-asm-scope', 'muster-pflege.de');
    await page.selectOption('#skg-asm-scope-kind', 'domain');
    await page.fill('#skg-asm-predicate', 'price');
    await page.selectOption('#skg-asm-tier', '3');
    await page.fill('#skg-asm-reason', 'statutory benefit');
    check(!(await page.locator('#skg-asm-quote').isVisible()), 'the quote is only for an operator statement' + where);
    await page.click('#skg-asm-preview-button');
    await page.waitForFunction(() => !document.querySelector('#skg-asm-preview').hidden);
    check(posts[0].scope.domain === 'muster-pflege.de' && posts[0].tier === 3 && !posts[0].token, 'the preview asks without a token' + where);
    const changes = await page.locator('#skg-asm-changes').textContent();
    check(changes.includes('3') && changes.includes(language === 'de' ? 'Belegzeilen' : 'Evidence rows'), 'the preview shows its counts: ' + changes + where);
    check((await page.locator('#skg-asm-sample').textContent()).includes('Entlastungsbetrag'), 'the sample is shown' + where);
    await page.click('#skg-asm-confirm');
    await page.waitForFunction(() => document.querySelectorAll('#skg-asm-list tbody tr[data-id]').length === 2);
    check(posts[1].token === 'tok123', 'storing sends the preview token' + where);
    check((await page.locator('#skg-asm-message').textContent()).length > 0, 'a message confirms' + where);
    // revoke after the confirmation dialog
    await page.locator('#skg-asm-list tbody tr[data-id="kga_aaaaaaaaaaaaaaaaaaaa"] button').click();
    await page.waitForFunction(() => !document.querySelector('#skg-asm-list tbody tr[data-id="kga_aaaaaaaaaaaaaaaaaaaa"] button'));
    check(posts.some(p => p.revoke === '/scoutro/api/v1/kg/assessments/kga_aaaaaaaaaaaaaaaaaaaa/revoke'), 'revoke posts the id' + where);
    // the operator statement form: source only, quote shown, tier hidden
    await page.selectOption('#skg-asm-action', 'assert');
    check(await page.locator('#skg-asm-quote').isVisible() && !(await page.locator('#skg-asm-tier').isVisible()), 'the operator form' + where);
    check(await page.locator('#skg-asm-scope-kind').inputValue() === 'source' && await page.locator('#skg-asm-scope-kind').isDisabled(), 'an operator statement is bound to a source' + where);
    // the identity change preview: a dry run for one domain
    await page.uncheck('#skg-idp-product');
    await page.fill('#skg-idp-domain', 'muster-shop.de');
    await page.click('#skg-idp-button');
    check((await page.locator('#skg-idp-message').textContent()).length > 0 && !posts.some(p => p.identity), 'nothing is sent without a chosen change' + where);
    await page.check('#skg-idp-product');
    await page.click('#skg-idp-button');
    await page.waitForFunction(() => document.querySelectorAll('#skg-idp-result dt').length > 0);
    const sent = posts.find(p => p.identity).identity;
    check(sent.domain === 'muster-shop.de' && sent.productUrl === true && sent.providerServiceKey === false, 'the preview asks for the chosen change: ' + JSON.stringify(sent) + where);
    const result = await page.locator('#skg-idp-result').textContent();
    check(result.includes('3 → 2') && result.includes(language === 'de' ? 'Leistungen' : 'Services'), 'services before and after: ' + result + where);
    const lists = await page.locator('#skg-idp-lists').textContent();
    check(lists.includes('kge_bbbbbbbbbbbbbbbbbbbb -> kge_aaaaaaaaaaaaaaaaaaaa') && lists.includes('Gartentisch'), 'merges and blocked merges listed' + where);
    check((await page.locator('#skg-idp-message').textContent()).includes(language === 'de' ? 'nichts geschrieben' : 'Nothing was written'), 'nothing written' + where);
    // no horizontal page overflow on mobile
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    check(overflow <= 1, 'no horizontal overflow: ' + overflow + where);
    check(errors.length === 0, 'no script errors: ' + errors.join('; ') + where);
    await context.close();
  }
} finally {
  await browser.close();
}
console.log('knowledge assessments UI: ' + checks + ' checks passed');
