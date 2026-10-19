#!/usr/bin/env node
/* GPL-2.0-or-later. The roles of organisations on the knowledge graph page (0.11.5) with the real localized HTML/JS and
   controlled API fixtures: the operator's badge in the object view, brands and manufacturers under their own heading apart
   from partners, the note on relations hidden because the other side has no proper name, the role badge of such an entry in
   the object list, the quality "stated by sources" with its tooltip and the reading of brand_of; phone and desktop, EN/DE.
   Names are examples. */
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

const ORG = 'kge_' + 'a'.repeat(20);
const ref = (id, name, role) => ({ id: 'kge_' + id.repeat(20), name, type: 'organization', role });
const fact = (id, extra) => Object.assign({ statement: 'kgs_' + id.repeat(20), status: 'current', quality: 'supported', confidence: 0.9, kinds: ['jsonld'],
  sources: 1, source_docs: [{ doc_id: 'AAAAAAhost01', url: 'https://www.das-pianohaus.example/' }] }, extra);
const OVERVIEW = { id: ORG, type: 'organization', name: 'Das Pianohaus GmbH', quality: 'supported', role: 'operator', counts: { statements: 9, sources: 2 },
  aliases: [], identifiers: [], hosts: ['www.das-pianohaus.example'], possible_duplicates: [] };
const BUSINESS = { schema: 1, id: ORG, overview: OVERVIEW,
  relations: {
    facts: [
      fact('p', { predicate: 'partner_of', direction: 'out', business: true, other: ref('b', 'Muster Musikschule e.V.', null), kinds: ['rule'] }),
      fact('q', { predicate: 'brand_of', direction: 'in', business: true, other: ref('c', 'Pianohaus Edition', 'brand') }),
      fact('r', { predicate: 'supplier_of', direction: 'in', business: true, other: ref('d', 'Wilhelm Schimmel Pianofortefabrik GmbH', 'manufacturer') })],
    derived: {},
    hidden_generic: 2 },
  sources: [] };
const LIST = { schema: 1, offset: 0, limit: 25, total: 2, items: [
  Object.assign({}, OVERVIEW, { context: { hosts: ['www.das-pianohaus.example'], collections: ['alpha-care'] } }),
  { id: 'kge_' + 'g'.repeat(20), type: 'organization', name: 'Banken', quality: 'supported', role: 'generic', counts: { statements: 1, sources: 1 },
    context: { hosts: ['www.rostocker-massivhaus.example'], collections: ['alpha-care'] } }] };

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
    if (u.pathname.endsWith('/kg/entities/' + ORG)) return route.fulfill({ json: OVERVIEW });
    if (u.pathname.endsWith('/kg/entities/' + ORG + '/business')) return route.fulfill({ json: BUSINESS });
    if (u.pathname.includes('/kg/entities/' + ORG + '/statements')) return route.fulfill({ json: { total: 0, items: [] } });
    if (u.pathname.endsWith('/kg/entities')) return route.fulfill({ json: LIST });
    if (u.pathname.startsWith('/scoutro/api/v1/kg/')) return route.fulfill({ json: {} });
    return route.fulfill({ status: 404, json: { error: { code: 'not_found' } } });
  });
  const L = key => page.evaluate(k => document.querySelector(`[data-skg-label="${k}"]`).textContent, key);
  const where = ` (${language}, ${width})`;

  // the object view of the operator
  await page.goto(base + '/ScoutroKnowledge_p.html?view=object&id=' + ORG, { waitUntil: 'networkidle' });
  await page.waitForSelector('#skg-sec-relations');
  const facts = await page.locator('#skg-object-facts').innerText();
  check(facts.includes(await L('erole')) && facts.includes(await L('erole_operator')), 'the operator role in the object facts: ' + facts + where);
  check(language === 'de' ? (await L('erole_operator')) === 'Betreiber' : (await L('erole_operator')) === 'site operator', 'translated role' + where);
  const quality = page.locator('#skg-object-facts .skg-q-supported').first();
  check((await quality.textContent()) === await L('supported'), 'the quality badge says what sources state' + where);
  check(language === 'de' ? (await L('supported')) === 'laut Quellen' : (await L('supported')) === 'stated by sources', 'no "belegt"/"supported"' + where);
  check((await quality.getAttribute('title')) === await L('q_hint_supported'), 'the tooltip: not verified by a person' + where);
  const sec = page.locator('#skg-sec-relations');
  const text = await sec.innerText();
  check(text.includes('Muster Musikschule e.V.') && text.includes(await L('p_partner_of')), 'the partner stays a relation' + where);
  check(text.includes(await L('rel_brands')), 'the heading of brands and manufacturers' + where);
  const headings = await sec.locator('h4').allTextContents();
  check(headings.includes(await L('rel_brands')), 'brands under their own heading: ' + headings + where);
  const brandList = sec.locator('h4:has-text("' + await L('rel_brands') + '") + ul');
  const brands = await brandList.innerText();
  check(brands.includes('Pianohaus Edition') && brands.includes('Wilhelm Schimmel Pianofortefabrik GmbH'), 'brand and manufacturer under the heading: ' + brands + where);
  check(!brands.includes('Muster Musikschule'), 'the partner is not among the brands' + where);
  check(brands.includes(await L('erole_brand')) && brands.includes(await L('erole_manufacturer')), 'role badges on the brand and the maker: ' + brands + where);
  check(brands.includes(await L('p_brand_of')) && (language === 'de' ? (await L('p_brand_of')) === 'wird als Marke geführt von' : (await L('p_brand_of')) === 'is listed as a brand of'),
    'brand_of reads as a listing, not an ownership' + where);
  const note = await sec.locator('.skg-hidden-note').innerText();
  check(note === (await L('rel_hidden_generic')).replace('%1', '2'), 'the note counts the hidden relations: ' + note + where);
  check(!text.includes('Banken'), 'no generic entry among the relations' + where);
  check(await page.evaluate(() => document.scrollingElement.scrollWidth <= window.innerWidth + 1), 'no horizontal scrolling' + where);

  // the object list: the generic entry is listed with its badge, the operator with its own
  await page.goto(base + '/ScoutroKnowledge_p.html?view=objects&q=', { waitUntil: 'networkidle' });
  await page.waitForSelector('#skg-entities tbody tr');
  const rows = await page.locator('#skg-entities tbody tr').allInnerTexts();
  check(rows.length === 2, 'two objects listed: ' + rows.length + where);
  check(rows[1].includes('Banken') && rows[1].includes(await L('erole_generic')), 'the generic entry is listed, labelled: ' + rows[1] + where);
  check(rows[0].includes(await L('erole_operator')), 'the operator is labelled: ' + rows[0] + where);
  const generic = page.locator('#skg-entities tbody .skg-role-generic').first();
  check((await generic.getAttribute('title')) === await L('erole_hint_generic'), 'the generic badge explains itself' + where);
  check(errors.length === 0, 'no page errors: ' + errors.join('; ') + where);
  await context.close();
}
await browser.close();
console.log(`knowledge-roles-ui-test: ${checks} checks passed`);
