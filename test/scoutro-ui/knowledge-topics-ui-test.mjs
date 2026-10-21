#!/usr/bin/env node
/* GPL-2.0-or-later. The company view's "Topics of the website" (0.11.8, the topic statements) of the knowledge graph page with
   the real localized HTML/JS and controlled API fixtures: each topic with its label, the "no proof of an offer" mark and its
   evidence, in a section of its own after the services and apart from them; phone and desktop, EN/DE. Names are examples. */
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

const ORG = 'kge_' + 'b'.repeat(20);
const fact = (id, extra) => Object.assign({ statement: 'kgs_' + id.repeat(20), status: 'current', quality: 'supported', confidence: 0.9, kinds: ['rule'],
  sources: 1, source_docs: [{ doc_id: 'AAAAAAhost01', url: 'https://www.muster-stellwerk.de/' }] }, extra);
const BUSINESS = { schema: 1, id: ORG,
  services: [fact('s', { id: 'kge_' + 'c'.repeat(20), name: 'IT-Beratung', offer: 'kgs_' + 'o'.repeat(20), categories: [{ value: 'software/it_beratung',
    label_de: 'IT-Beratung', label_en: 'IT consulting' }], prices: 0 })],
  website_topics: [
    fact('a', { value: 'software/sap', label_de: 'SAP', label_en: 'SAP' }),
    fact('d', { value: 'software/erp', label_de: 'ERP-Systeme', label_en: 'ERP systems' })],
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
  await page.waitForSelector('#skg-sec-website_topics');
  const sec = page.locator('#skg-sec-website_topics');
  check((await sec.locator('h3').textContent()) === await L('sec_website_topics'), 'section title' + where);
  check(language === 'de' ? (await L('sec_website_topics')) === 'Themen der Website' : (await L('sec_website_topics')) === 'Topics of the website',
    'translated title' + where);
  check(language === 'de' ? (await L('topic_no_offer')) === 'kein Angebotsbeleg' : (await L('topic_no_offer')) === 'no proof of an offer',
    'translated mark' + where);
  const items = sec.locator('li[data-topic]');
  check(await items.count() === 2, 'two topics' + where);
  const sap = await sec.locator('li[data-topic="software/sap"]').innerText();
  check(sap.includes('SAP') && sap.includes(await L('topic_no_offer')), 'label and mark: ' + sap + where);
  const erp = await sec.locator('li[data-topic="software/erp"]').innerText();
  check(erp.includes(language === 'de' ? 'ERP-Systeme' : 'ERP systems'), 'the label of the language: ' + erp + where);
  check((await sec.innerText()).includes(await L('website_topics_note')), 'the note: no proof of an offer, no matching input' + where);
  // apart from the services: the service stays there, no topic appears in it
  const services = await page.locator('#skg-sec-services').innerText();
  check(services.includes('IT-Beratung') && !services.includes(await L('topic_no_offer')), 'services apart: ' + services + where);
  const toc = await page.locator('#skg-toc a').allTextContents();
  check(toc.indexOf(await L('sec_website_topics')) === toc.indexOf(await L('sec_services')) + 1, 'listed after the services: ' + toc + where);
  check(await page.evaluate(() => document.scrollingElement.scrollWidth <= window.innerWidth + 1), 'no horizontal scrolling' + where);
  check(errors.length === 0, 'no page errors: ' + errors.join('; ') + where);
  await context.close();
}
await browser.close();
console.log('PASS: ' + checks + ' website topic UI checks (label, no-offer mark, apart from services, EN/DE, phone and desktop)');
