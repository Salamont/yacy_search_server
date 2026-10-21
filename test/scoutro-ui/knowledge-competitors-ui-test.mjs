#!/usr/bin/env node
/* GPL-2.0-or-later. The company view's "Possible competitors" (0.12.0, B1) and the mark of a structurally evidenced service
   (rule/8) on the knowledge graph page with the real localized HTML/JS and controlled API fixtures: each competitor with its
   shared categories after the topics, the note that a competitor is no fact; phone and desktop, EN/DE. Names are examples. */
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

const ORG = 'kge_' + 'd'.repeat(20), BETA = 'kge_' + 'e'.repeat(20);
const fact = (id, extra) => Object.assign({ statement: 'kgs_' + id.repeat(20), status: 'uncertain', quality: 'supported', confidence: 0.4, kinds: ['rule'],
  sources: 1, source_docs: [{ doc_id: 'AAAAAAhost01', url: 'https://www.muster-stellwerk.de/' }] }, extra);
const BUSINESS = { schema: 1, id: ORG,
  services: [fact('s', { id: 'kge_' + 'c'.repeat(20), name: 'SAP', offer: 'kgs_' + 'o'.repeat(20), offer_evidence: 'structural',
    categories: [{ value: 'software/sap', label_de: 'SAP', label_en: 'SAP' }], prices: 0 }),
    fact('t', { id: 'kge_' + 'f'.repeat(20), name: 'IT-Beratung', offer: 'kgs_' + 'p'.repeat(20), confidence: 0.65,
    categories: [{ value: 'software/it_beratung', label_de: 'IT-Beratung', label_en: 'IT consulting' }], prices: 0 })],
  competitors: { basis: 'shared_offered_categories', fact: false, items: [
    { other: { id: BETA, name: 'Beta Consult GmbH', type: 'organization' }, label: 'suggestion',
      shared_categories: [{ value: 'software/sap', label_de: 'SAP', label_en: 'SAP' }, { value: 'software/it_beratung', label_de: 'IT-Beratung', label_en: 'IT consulting' }] }] },
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
    if (u.pathname === '/env/scoutro/collections.js') return route.fulfill({ contentType: 'application/javascript', body: 'window.ScoutroCollections={load:async()=>["alpha-it"],fill:(s,ids,w)=>{for(const id of ids){const o=document.createElement("option");o.value=id;o.textContent=id;s.append(o);}s.value=w||"";s.disabled=false;return s.value;}};' });
    if (u.pathname.startsWith('/env/scoutro/')) return route.fulfill({ contentType: u.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(path.join(root, 'htroot', u.pathname.slice(1)), 'utf8') });
    if (u.pathname.endsWith('/kg/entities/' + ORG)) return route.fulfill({ json: { id: ORG, type: 'organization', name: 'Muster Stellwerk GmbH', quality: 'supported', counts: {} } });
    if (u.pathname.endsWith('/kg/entities/' + ORG + '/business')) return route.fulfill({ json: BUSINESS });
    if (u.pathname.includes('/kg/entities/' + ORG + '/statements')) return route.fulfill({ json: { total: 0, items: [] } });
    if (u.pathname.startsWith('/scoutro/api/v1/kg/')) return route.fulfill({ json: {} });
    return route.fulfill({ status: 404, json: { error: { code: 'not_found' } } });
  });
  const L = key => page.evaluate(k => document.querySelector(`[data-skg-label="${k}"]`).textContent, key);
  const where = ` (${language}, ${width})`;
  await page.goto(base + '/ScoutroKnowledge_p.html?view=object&id=' + ORG, { waitUntil: 'networkidle' });
  await page.waitForSelector('#skg-sec-competitors');
  const sec = page.locator('#skg-sec-competitors');
  check((await sec.locator('h3').textContent()) === await L('sec_competitors'), 'section title' + where);
  check(language === 'de' ? (await L('sec_competitors')) === 'Mögliche Wettbewerber' : (await L('sec_competitors')) === 'Possible competitors',
    'translated title' + where);
  const item = await sec.locator('li[data-competitor="' + BETA + '"]').innerText();
  check(item.includes('Beta Consult GmbH') && item.includes('SAP') && item.includes(language === 'de' ? 'IT-Beratung' : 'IT consulting'),
    'competitor with its shared categories: ' + item + where);
  check((await sec.innerText()).includes(await L('competitors_note')), 'the note: a suggestion, no fact' + where);
  const sap = await page.locator('#skg-sec-services li[data-offer-evidence="structural"]').innerText();
  check(sap.includes(await L('offer_structural')), 'the structural mark of a service: ' + sap + where);
  check(await page.locator('#skg-sec-services li[data-offer-evidence]').count() === 1, 'an explicit offer has no mark' + where);
  const toc = await page.locator('#skg-toc a').allTextContents();
  check(toc.indexOf(await L('sec_competitors')) > toc.indexOf(await L('sec_services')), 'listed after the services: ' + toc + where);
  check(await page.evaluate(() => document.scrollingElement.scrollWidth <= window.innerWidth + 1), 'no horizontal scrolling' + where);
  check(errors.length === 0, 'no page errors: ' + errors.join('; ') + where);
  await context.close();
}
await browser.close();
console.log('PASS: ' + checks + ' competitor and structural offer UI checks (shared categories, note, mark, EN/DE, phone and desktop)');
