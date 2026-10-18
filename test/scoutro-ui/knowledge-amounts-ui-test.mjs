#!/usr/bin/env node
/* GPL-2.0-or-later. The company view's "Other amounts" (WP-F, monetary_amount) of the knowledge graph page with the real
   localized HTML/JS and controlled API fixtures: a statutory benefit, a donation and an amount of unclear role, each with its
   role, apart from the prices; phone and desktop, EN/DE. Names are examples. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const base = 'http://127.0.0.1:8090';
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] });
let checks = 0;
const check = (ok, why) => { assert(ok, why); checks++; };

const ORG = 'kge_' + 'a'.repeat(20), SVC = 'kge_' + 'b'.repeat(20);
const fact = (id, extra) => Object.assign({ statement: 'kgs_' + id.repeat(20), status: 'current', quality: 'supported', confidence: 0.9, kinds: ['rule'],
  sources: 1, source_docs: [{ doc_id: 'AAAAAAhost01', url: 'https://www.muster-pflege.de/preise' }] }, extra);
const BUSINESS = { schema: 1, id: ORG,
  services: [{ id: SVC, name: 'Entlastungsleistungen', offer: 'kgs_' + 'o'.repeat(20), prices: 1, amounts: 1, status: 'current', sources: 1, kinds: ['rule'] }],
  prices: [fact('c', { service: SVC, service_name: 'Entlastungsleistungen', value: { amount: '35.00', currency: 'EUR', kind: 'exact', unit: 'hour' },
    as_of: '2026-10-01', as_of_basis: 'last_confirmed' })],
  amounts: [
    fact('d', { service: SVC, service_name: 'Entlastungsleistungen', role: 'statutory_benefit', value: { amount: '131.00', currency: 'EUR', kind: 'exact', unit: 'month', role: 'statutory_benefit' } }),
    fact('e', { service: null, service_name: null, role: 'donation', value: { amount: '20.00', currency: 'EUR', kind: 'exact', role: 'donation' } }),
    fact('f', { service: null, service_name: null, role: 'unknown', value: { amount: '50.00', currency: 'EUR', kind: 'from', role: 'unknown' } })],
  sources: [] };

for (const language of ['en', 'de']) for (const width of [390, 1280]) {
  const context = await browser.newContext({ locale: language, viewport: { width, height: 900 } });
  const page = await context.newPage(), errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.route(base + '/**', async route => {
    const u = new URL(route.request().url());
    if (u.pathname === '/ScoutroKnowledge_p.html') {
      let html = fs.readFileSync(path.join(root, 'htroot/ScoutroKnowledge_p.html'), 'utf8').replace(/#\(\).*?#\(\//gs, '')
        .replace('#[scoutroAdmin]#', '1').replace('#[scoutroExport]#', '1');
      if (language === 'de') {
        html = html.replace('lang="en"', 'lang="de"');
        const section = fs.readFileSync(path.join(root, 'locales/de.lng'), 'utf8').split('#File: ScoutroKnowledge_p.html\n')[1].split('#File: ')[0];
        const entries = section.split('\n').filter(l => l.includes('==') && !l.startsWith('#')).map(l => l.split('=='));
        for (const [key, value] of entries.sort((a, b) => b[0].length - a[0].length)) html = html.split(key).join(value);
      }
      return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
    }
    if (u.pathname === '/env/scoutro/collections.js') return route.fulfill({ contentType: 'application/javascript', body: 'window.ScoutroCollections={load:async()=>["alpha-care"],fill:(s,ids,w)=>{for(const id of ids){const o=document.createElement("option");o.value=id;o.textContent=id;s.append(o);}s.value=w||"";s.disabled=false;return s.value;}};' });
    if (u.pathname.startsWith('/env/scoutro/')) return route.fulfill({ contentType: u.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(path.join(root, 'htroot', u.pathname.slice(1)), 'utf8') });
    if (u.pathname.endsWith('/kg/entities/' + ORG)) return route.fulfill({ json: { id: ORG, type: 'organization', name: 'Muster Pflege GmbH', quality: 'supported', counts: {} } });
    if (u.pathname.endsWith('/kg/entities/' + ORG + '/business')) return route.fulfill({ json: BUSINESS });
    if (u.pathname.includes('/kg/entities/' + ORG + '/statements')) return route.fulfill({ json: { total: 0, items: [] } });
    if (u.pathname.startsWith('/scoutro/api/v1/kg/')) return route.fulfill({ json: {} });
    return route.fulfill({ status: 404, json: { error: { code: 'not_found' } } });
  });
  const L = key => page.evaluate(k => document.querySelector(`[data-skg-label="${k}"]`).textContent, key);
  const where = ` (${language}, ${width})`;
  await page.goto(base + '/ScoutroKnowledge_p.html?view=object&id=' + ORG, { waitUntil: 'networkidle' });
  await page.waitForSelector('#skg-sec-amounts');
  check((await page.locator('#skg-sec-amounts h3').textContent()) === await L('sec_amounts'), 'section title' + where);
  check(language === 'de' ? (await L('sec_amounts')) === 'Weitere Beträge' : (await L('sec_amounts')) === 'Other amounts', 'translated title' + where);
  const rows = page.locator('#skg-sec-amounts tbody tr');
  check(await rows.count() === 3, 'three amounts' + where);
  check(await rows.nth(0).getAttribute('data-role') === 'statutory_benefit', 'role attribute' + where);
  const first = await rows.nth(0).innerText();
  check(first.includes(await L('amt_role_statutory_benefit')) && first.includes('Entlastungsleistungen') && first.includes('131'), 'the benefit with its service: ' + first + where);
  const second = await rows.nth(1).innerText();
  check(second.includes(await L('amt_role_donation')) && second.includes(await L('amt_general')), 'the donation of the business: ' + second + where);
  check((await rows.nth(2).innerText()).includes(await L('amt_role_unknown')), 'unclear role shown as such' + where);
  check((await page.locator('#skg-sec-amounts').innerText()).includes(await L('amt_note')), 'the note says they are no prices' + where);
  // the prices stay the prices: one row, without the amounts
  const prices = page.locator('#skg-sec-prices tbody tr');
  check(await prices.count() === 1, 'one price' + where);
  check(!(await page.locator('#skg-sec-prices').innerText()).includes('131'), 'the benefit is no price' + where);
  // the table of contents names the section after the prices
  const toc = await page.locator('#skg-toc a').allTextContents();
  check(toc.indexOf(await L('sec_amounts')) === toc.indexOf(await L('sec_prices')) + 1, 'listed after the prices: ' + toc + where);
  check(await page.evaluate(() => document.scrollingElement.scrollWidth <= window.innerWidth + 1), 'no horizontal scrolling' + where);
  check(errors.length === 0, 'no page errors: ' + errors.join('; ') + where);
  await context.close();
}
await browser.close();
console.log('PASS: ' + checks + ' amount UI checks (roles, apart from prices, EN/DE, phone and desktop)');
