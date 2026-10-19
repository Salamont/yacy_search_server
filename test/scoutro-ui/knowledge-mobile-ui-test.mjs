#!/usr/bin/env node
/* GPL-2.0-or-later. The knowledge graph page on a phone (0.11.5) with the real localized HTML/JS and controlled API fixtures:
   the sections of the object view fold (open by default, one by its heading, all by the ToC button on a phone), statements
   are paged by 25 on a phone and by 100 on a desktop, the network diagram measures its node names (two lines on a phone,
   never cut by a character count) and folds its filters on a phone, help popups and tables never scroll the page sideways.
   EN/DE, 360 and 1280 px. Names are examples. */
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

const ORG = 'kge_' + 'a'.repeat(20), PARTNER = 'kge_' + 'b'.repeat(20), BANK = 'kge_' + 'c'.repeat(20);
const LONG = 'Rostocker Massivhaus und Immobilien Verwaltungsgesellschaft mbH & Co. KG';
const ref = (id, name, role) => ({ id, name, type: 'organization', role });
const fact = (id, extra) => Object.assign({ statement: 'kgs_' + id.repeat(20), status: 'current', quality: 'supported', confidence: 0.9, kinds: ['rule'],
  sources: 1, source_docs: [{ doc_id: 'AAAAAAhost01', url: 'https://www.rostocker-massivhaus.example/' }] }, extra);
const OVERVIEW = { id: ORG, type: 'organization', name: LONG, quality: 'supported', role: 'operator', counts: { statements: 3, sources: 1 },
  aliases: [], identifiers: [], hosts: ['www.rostocker-massivhaus.example'], possible_duplicates: [] };
const BUSINESS = { schema: 1, id: ORG, overview: OVERVIEW,
  relations: { facts: [fact('p', { predicate: 'partner_of', direction: 'out', business: true, other: ref(PARTNER, 'Muster Baufinanz Beratungsgesellschaft für Wohnimmobilien GmbH', null) })],
    derived: {}, hidden_generic: 1 },
  sources: [] };
const STATEMENT = i => ({ statement: 'kgs_' + String(i).padStart(20, 'x'), predicate: 'description', value: 'Zitat ' + i + ': ' + 'Massivhäuser aus Rostock, schlüsselfertig geplant und gebaut, '.repeat(3),
  status: 'current', quality: 'supported', confidence: 0.8, kinds: ['rule'], sources: 1, source_docs: [{ doc_id: 'AAAAAAhost01', url: 'https://www.rostocker-massivhaus.example/' }] });
const NETWORK = { center: ORG,
  nodes: [{ id: ORG, label: LONG, type: 'organization', depth: 0, collections: ['alpha'], hosts: ['www.rostocker-massivhaus.example'] },
    { id: PARTNER, label: 'Muster Baufinanz Beratungsgesellschaft für Wohnimmobilien GmbH', type: 'organization', depth: 1, collections: ['alpha'], hosts: ['www.muster-baufinanz.example'] },
    { id: BANK, label: 'KfW', type: 'organization', depth: 1, collections: ['alpha'], hosts: ['www.kfw.example'] }],
  edges: [{ id: 'e1', from: ORG, to: PARTNER, type: 'partner_of', predicate: 'partner_of', label: 'partner_of', status: 'current', fact: true, statement: 'kgs_' + 'p'.repeat(20) },
    { id: 'e2', from: ORG, to: BANK, type: 'partner_of', predicate: 'partner_of', label: 'partner_of', status: 'current', fact: true, statement: 'kgs_' + 'q'.repeat(20) }],
  has_more: false };

for (const language of ['en', 'de']) for (const width of [360, 1280]) {
  const context = await browser.newContext({ locale: language, viewport: { width, height: 800 }, hasTouch: width < 640, isMobile: width < 640 });
  const page = await context.newPage(), errors = [], requests = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.route(base + '/**', async route => {
    const u = new URL(route.request().url());
    if (u.pathname === '/ScoutroKnowledge_p.html') {
      // the viewport meta of env/templates/metas.template, which the server includes: a phone renders the page at its own width
      let html = fs.readFileSync(path.join(root, 'htroot/ScoutroKnowledge_p.html'), 'utf8').replace(/#\(\).*?#\(\//gs, '')
        .replace('#%env/templates/metas.template%#', '<meta name="viewport" content="width=device-width, initial-scale=1">')
        .replace('#[scoutroAdmin]#', '1').replace('#[scoutroExport]#', '1');
      if (language === 'de') {
        html = html.replace('lang="en"', 'lang="de"');
        const section = fs.readFileSync(path.join(root, 'locales/de.lng'), 'utf8').split('#File: ScoutroKnowledge_p.html\n')[1].split('#File: ')[0];
        const entries = section.split('\n').filter(l => l.includes('==') && !l.startsWith('#')).map(l => l.split('=='));
        for (const [key, value] of entries.sort((a, b) => b[0].length - a[0].length)) html = html.split(key).join(value);
      }
      return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
    }
    if (u.pathname === '/env/scoutro/collections.js') return route.fulfill({ contentType: 'application/javascript', body: 'window.ScoutroCollections={load:async()=>["alpha"],fill:(s,ids,w)=>{for(const id of ids){const o=document.createElement("option");o.value=id;o.textContent=id;s.append(o);}s.value=w||"";s.disabled=false;return s.value;}};' });
    if (u.pathname.startsWith('/env/')) {
      const file = path.join(root, 'htroot', u.pathname.slice(1));
      if (!fs.existsSync(file)) return route.fulfill({ status: 404, body: '' });
      return route.fulfill({ contentType: u.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(file, 'utf8') });
    }
    if (u.pathname.startsWith('/scoutro/api/v1/kg/')) requests.push(u);
    if (u.pathname.endsWith('/kg/entities/' + ORG)) return route.fulfill({ json: OVERVIEW });
    if (u.pathname.endsWith('/kg/entities/' + ORG + '/business')) return route.fulfill({ json: BUSINESS });
    if (u.pathname.includes('/kg/entities/' + ORG + '/statements')) {
      const limit = Number(u.searchParams.get('limit')), offset = Number(u.searchParams.get('offset') || 0);
      const items = []; for (let i = offset; i < Math.min(60, offset + limit); i++) items.push(STATEMENT(i));
      return route.fulfill({ json: { total: 60, items } });
    }
    if (u.pathname.endsWith('/kg/entities/' + ORG + '/neighborhood')) return route.fulfill({ json: NETWORK });
    if (u.pathname.startsWith('/scoutro/api/v1/kg/')) return route.fulfill({ json: {} });
    return route.fulfill({ status: 404, json: { error: { code: 'not_found' } } });
  });
  const L = key => page.evaluate(k => document.querySelector(`[data-skg-label="${k}"]`).textContent, key);
  const where = ` (${language}, ${width})`;
  const phone = width < 768;

  // the object view: collapsible sections, the ToC button, the page size
  await page.goto(base + '/ScoutroKnowledge_p.html?view=object&id=' + ORG, { waitUntil: 'networkidle' });
  await page.waitForSelector('#skg-sec-relations');
  const sections = page.locator('details.skg-bsec');
  const n = await sections.count();
  check(n >= 1, 'the sections are details: ' + n + where);
  check(await page.evaluate(() => [...document.querySelectorAll('details.skg-bsec')].every(d => d.open)), 'open by default' + where);
  check(await page.evaluate(() => [...document.querySelectorAll('details.skg-bsec')].every(d => d.firstElementChild.tagName === 'SUMMARY' && d.querySelector('summary > h3'))), 'the heading is the summary' + where);
  const rel = page.locator('#skg-sec-relations');
  await rel.locator('> summary').click();
  check(!(await rel.evaluate(d => d.open)), 'a heading folds its section' + where);
  await rel.locator('> summary').click();
  check(await rel.evaluate(d => d.open), 'and opens it again' + where);
  const toggle = page.locator('#skg-toc .skg-toc-toggle');
  if (phone) {
    check(await toggle.count() === 1 && (await toggle.textContent()) === await L('toc_collapse'), 'the ToC offers to fold every section on a phone' + where);
    await toggle.click();
    check(await page.evaluate(() => [...document.querySelectorAll('details.skg-bsec')].every(d => !d.open)), 'all folded' + where);
    check((await toggle.textContent()) === await L('toc_expand'), 'the button now expands' + where);
    await toggle.click();
    check(await page.evaluate(() => [...document.querySelectorAll('details.skg-bsec')].every(d => d.open)), 'all open again' + where);
    // a ToC link opens its section
    await toggle.click();
    await page.locator('#skg-toc a[href="#skg-sec-relations"]').click();
    check(await rel.evaluate(d => d.open), 'a ToC link opens a folded section' + where);
  } else {
    check(await toggle.count() === 0, 'no fold-all button on a desktop' + where);
  }
  const statementRequests = requests.filter(u => u.pathname.includes('/statements'));
  check(statementRequests.length > 0 && statementRequests.every(u => u.searchParams.get('limit') === (phone ? '25' : '100')), 'page size ' + (phone ? 25 : 100) + ': '
    + statementRequests.map(u => u.searchParams.get('limit')).join(',') + where);
  const quotes = await page.locator('.skg-excerpt, blockquote').count();
  check(await page.evaluate(() => document.scrollingElement.scrollWidth <= window.innerWidth + 1), 'no horizontal scrolling in the object view (' + quotes + ' quotes)' + where);
  check((await rel.innerText()).includes((await L('rel_hidden_generic')).replace('%1', '1')), 'the hidden-relation note' + where);

  // the network: measured names, two lines on a phone, folded filters
  await page.goto(base + '/ScoutroKnowledge_p.html?view=network&id=' + ORG, { waitUntil: 'networkidle' });
  await page.waitForSelector('#skg-net-svg .skg-node');
  const nodes = await page.evaluate(() => [...document.querySelectorAll('#skg-net-svg .skg-node')].map(g => {
    const card = g.querySelector('.skg-card'); const w = Number(card.getAttribute('width'));
    const lines = [...g.querySelectorAll('text.skg-n1')].map(t => ({ text: t.textContent, len: t.getComputedTextLength() }));
    const sub = g.querySelector('text.skg-n2');
    return { id: g.dataset.node, width: w, lines, sub: { text: sub.textContent, len: sub.getComputedTextLength() }, title: g.querySelector('title').textContent };
  }));
  check(nodes.length === 3, 'three nodes: ' + nodes.length + where);
  for (const nd of nodes) {
    for (const l of nd.lines) check(l.len <= nd.width - 22 + 0.5, 'a name line fits its card (' + l.len.toFixed(1) + ' of ' + (nd.width - 22) + '): ' + l.text + where);
    check(nd.sub.len <= nd.width - 22 + 0.5, 'the second label fits: ' + nd.sub.text + where);
    check(nd.lines.length === (phone ? 2 : 1), (phone ? 'two' : 'one') + ' name line(s) on this screen: ' + nd.lines.length + where);
    check(nd.lines.every(l => !/^.?…$/.test(l.text)), 'never a name of one character: ' + nd.lines.map(l => l.text).join('|') + where);
  }
  const longNode = nodes.find(nd => nd.id === ORG);
  const shown = longNode.lines.map(l => l.text).join(' ');
  check(shown.replace(/…$/, '').length > (phone ? 24 : 8), 'the long name shows more than a character count would allow: ' + shown + where);
  if (phone) check(longNode.lines[1].text.length > 0 && !longNode.lines[0].text.endsWith('…'), 'the first line breaks at a word, the second carries on: ' + shown + where);
  check(longNode.title.startsWith(LONG), 'the full name in the title' + where);
  const kfw = nodes.find(nd => nd.id === BANK);
  check(kfw.lines[0].text === 'KfW' && (!phone || kfw.lines[1].text === ''), 'a short name is never cut nor padded: ' + JSON.stringify(kfw.lines) + where);
  const filters = page.locator('#skg-net-filters');
  check((await filters.evaluate(d => d.open)) === !phone, 'the filters are ' + (phone ? 'folded' : 'open') + where);
  if (phone) {
    await filters.locator('> summary').click();
    check(await filters.evaluate(d => d.open), 'a tap opens the filters' + where);
    check(await page.locator('#skg-f-business').isVisible(), 'and shows the checkboxes' + where);
  } else {
    check(!(await filters.locator('> summary').isVisible()), 'no summary line on a desktop' + where);
  }
  check(await page.evaluate(() => document.scrollingElement.scrollWidth <= window.innerWidth + 1), 'no horizontal scrolling in the network' + where);
  check(await page.evaluate(() => { const w = document.getElementById('skg-net-wrap'); return w.scrollWidth <= w.clientWidth + 1; }), 'the diagram fits its box' + where);
  check(errors.length === 0, 'no page errors: ' + errors.join('; ') + where);
  await context.close();
}
await browser.close();
console.log(`knowledge-mobile-ui-test: ${checks} checks passed`);
