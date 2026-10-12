#!/usr/bin/env node
/* GPL-2.0-or-later. Service categories, the collection switch and the price drilldown of the knowledge graph page
   (ScoutroKnowledge_p.html, views services, compare and prices) with the real localized HTML/JS and controlled API
   fixtures: categories that exist, a collection without a vocabulary profile, categories that fail to load, the
   category of one collection not carried into another, "with a current price" opening exactly its prices, status
   lists, paging, phone layout and that the page only reads. Collection names are examples. */
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

const FACETS = {
  'alpha-care': { categories: [{ code: 'care/tagespflege', label_de: 'Tagespflege', label_en: 'Day care', entities: 3 },
    { code: 'care/kurzzeitpflege', label_de: 'Kurzzeitpflege', label_en: 'Short-term care', entities: 1 }],
  profiles: [{ collection: 'alpha-care', vocabulary: 'care', source: 'setting' }] },
  'beta-shop': { categories: [], profiles: [{ collection: 'beta-shop', vocabulary: null, source: 'none' }] },
  '': { categories: [{ code: 'care/tagespflege', label_de: 'Tagespflege', label_en: 'Day care', entities: 3 }],
    profiles: [{ collection: 'alpha-care', vocabulary: 'care', source: 'setting' }, { collection: 'beta-shop', vocabulary: null, source: 'none' }] },
};
const GROUP = { key: 'tagespflege', name: 'Tagespflege', services: 2, providers: 2, without_provider: 0, collections: [{ name: 'alpha-care', services: 2 }],
  places: [], with_price: 1, with_any_price: 2, with_current_source: 2, truncated: false };
function price(i, status, amount, extra = {}) {
  return { service: { id: 'kge_' + 'a'.repeat(19) + (i % 10), name: i < 2 ? 'Tagespflege' : 'Beratung ' + i },
    providers: [{ id: 'kge_' + 'b'.repeat(19) + (i % 10), name: 'Anbieter ' + i, hosts: ['anbieter' + i + '.example'], collections: ['alpha-care'] }],
    price: { statement: 'kgs_' + 'c'.repeat(18) + String(i).padStart(2, '0'), service: 'kge_x', service_name: 'S' + i, status, sources: 1,
      source_docs: [{ doc_id: 'DOC' + i, url: 'https://anbieter' + i + '.example/preise' }], as_of: '2027-01-13', as_of_basis: 'last_confirmed',
      value: { amount: amount, currency: 'EUR', unit: 'day', kind: 'from', conditions: 'zzgl. Fahrtkosten' }, ...extra } };
}
function prices(q) {
  const status = q.get('status') || 'current', name = q.get('name'), offset = +(q.get('offset') || 0), limit = +(q.get('limit') || 25);
  let rows;
  if (status === 'current') rows = name ? [price(0, 'current', '49.00')] : Array.from({ length: 30 }, (_, i) => price(i, 'current', String(40 + i) + '.00'));
  else if (status === 'conflicting') rows = [price(1, 'conflicting', '52.00', { conflicts_with: ['kgs_other'] }), price(1, 'conflicting', '58.00', { conflicts_with: ['kgs_one'] })];
  else if (status === 'outdated') rows = [price(3, 'stale', '25.00', { stale_since: '2025-07-30', stale_after_days: 180 }), price(4, 'expired', '89.90', { valid_through: '2026-12-31' })];
  else rows = [];
  return { schema: 1, group: name ? GROUP : null, status, offset, limit, total: rows.length, truncated: false,
    counts: name ? { current: 1, conflicting: 2, outdated: 0, uncertain: 0 } : { current: 30, conflicting: 2, outdated: 2, uncertain: 0 },
    services_with_current: name ? 1 : 30, items: rows.slice(offset, offset + limit) };
}

for (const language of ['en', 'de']) for (const width of [390, 1280]) {
  const context = await browser.newContext({ locale: language, viewport: { width, height: 900 } });
  const page = await context.newPage(), errors = [], writes = [], calls = [];
  page.on('pageerror', e => errors.push(e.message));
  let failFacets = false;
  await page.route(base + '/**', async route => {
    const u = new URL(route.request().url());
    if (route.request().method() !== 'GET') writes.push(route.request().method() + ' ' + u.pathname);
    if (u.pathname.startsWith('/scoutro/api/')) calls.push(u.pathname.replace('/scoutro/api/v1/kg/', '') + u.search);
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
    if (u.pathname === '/env/scoutro/collections.js') return route.fulfill({ contentType: 'application/javascript', body: 'window.ScoutroCollections={load:async()=>["alpha-care","beta-shop"],fill:(s,ids,w)=>{for(const id of ids){const o=document.createElement("option");o.value=id;o.textContent=id;s.append(o);}s.value=w||"";s.disabled=false;return s.value;}};' });
    if (u.pathname.startsWith('/env/scoutro/')) return route.fulfill({ contentType: u.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(path.join(root, 'htroot', u.pathname.slice(1)), 'utf8') });
    const coll = u.searchParams.get('collection') || '';
    if (u.pathname.endsWith('/kg/facets')) return failFacets ? route.fulfill({ status: 503, json: { error: { code: 'kg_unavailable' } } })
      : route.fulfill({ json: { schema: 1, industries: [], customer_types: [], segments: [], ...FACETS[coll] } });
    if (u.pathname.endsWith('/kg/services')) return route.fulfill({ json: { schema: 1, offset: 0, limit: 25, total: 1, items: [GROUP] } });
    if (u.pathname.endsWith('/kg/services/providers')) return route.fulfill({ json: { schema: 1, group: GROUP, offset: 0, limit: 25, total: 0, items: [] } });
    if (u.pathname.endsWith('/kg/services/prices')) return route.fulfill({ json: prices(u.searchParams) });
    if (u.pathname.endsWith('/kg/compare')) return route.fulfill({ json: { schema: 1, rows: [], truncated: false } });
    if (u.pathname.includes('/kg/statements/')) return route.fulfill({ json: { offset: 0, limit: 10, total: 1, items: [{ doc_id: 'DOC0', url: 'https://anbieter0.example/preise',
      host: 'anbieter0.example', collections: ['alpha-care'], state: 'active', excerpt: 'Tagespflege ab 49 € pro Tag', locator: 'text:12' }] } });
    return route.fulfill({ status: 404, body: '' });
  });
  const L = key => page.evaluate(k => document.querySelector(`[data-skg-label="${k}"]`).textContent, key);
  const where = ` (${language}, ${width})`;
  const settle = () => page.waitForFunction(() => !document.querySelector('#skg-message')?.textContent.match(/^(Loading|Lädt|Wird geladen)/));
  const go = async query => { calls.length = 0; await page.goto(base + '/ScoutroKnowledge_p.html' + query, { waitUntil: 'networkidle' }); await settle(); };
  const options = sel => page.locator(sel + ' option').evaluateAll(os => os.map(o => o.value));
  const noOverflow = () => page.evaluate(() => document.scrollingElement.scrollWidth <= window.innerWidth + 1);

  // 1. categories of a collection with a vocabulary
  await go('?view=services&collection=alpha-care');
  check(JSON.stringify(await options('#skg-svc-category')) === JSON.stringify(['', 'care/tagespflege', 'care/kurzzeitpflege']), 'categories offered' + where);
  check(await page.locator('#skg-svc-category-note').isHidden(), 'no note when categories exist' + where);
  check(calls.some(c => c.startsWith('facets?') && c.includes('counts=false') && c.includes('collection=alpha-care')), 'facets read for the collection, without counts' + where);
  const label = await page.locator('#skg-svc-category option[value="care/tagespflege"]').textContent();
  check(label.includes(language === 'de' ? 'Tagespflege' : 'Day care'), 'localized category label' + where);

  // 2. switching to a collection without a profile drops the category and says why the list is empty
  await go('?view=services&collection=alpha-care&category=care/tagespflege');
  calls.length = 0;
  await page.selectOption('#skg-collection', 'beta-shop'); await page.waitForLoadState('networkidle'); await settle();
  check(!new URL(page.url()).searchParams.has('category') && new URL(page.url()).searchParams.get('collection') === 'beta-shop', 'the category does not carry over' + where);
  check(calls.some(c => c.startsWith('facets?') && c.includes('collection=beta-shop')), 'categories reloaded for the new collection' + where);
  check(JSON.stringify(await options('#skg-svc-category')) === JSON.stringify(['']), 'only the placeholder for beta-shop' + where);
  const note = await page.locator('#skg-svc-category-note').textContent();
  check(await page.locator('#skg-svc-category-note').isVisible() && note.includes('beta-shop')
    && note.startsWith((await L('categories_no_profile')).split('%1')[0]) && note.includes(await L('categories_no_profile_admin')), 'no vocabulary profile explained' + where);

  // 3. a failure to load is told apart from an empty list
  failFacets = true;
  await go('?view=compare&collection=alpha-care');
  const failed = await page.locator('#skg-compare-category-note').textContent();
  check(await page.locator('#skg-compare-category-note').isVisible() && failed.startsWith((await L('categories_error')).split('%1')[0]) && failed.includes('503'),
    'load failure shown with its cause' + where);
  check(!failed.includes(await L('categories_none_found')) && !failed.includes((await L('categories_no_profile')).split('%1')[0]), 'not mistaken for empty data' + where);
  check(await page.locator('#skg-message').textContent() !== await L('choose_category'), 'no request to choose from an empty list' + where);
  check(await page.locator('#skg-compare-prices').isVisible() && new URL(await page.locator('#skg-compare-prices').getAttribute('href'), base).searchParams.get('view') === 'prices',
    'prices reachable without a category' + where);
  failFacets = false;
  await go('?view=compare&collection=beta-shop');
  check((await page.locator('#skg-compare-category-note').textContent()).includes('beta-shop'), 'compare explains the missing profile too' + where);

  // 4. "with a current price" opens exactly its prices
  await go('?view=services&collection=alpha-care');
  const cell = page.locator('#skg-svc-groups tbody tr').first().locator('td').nth(4);
  check(await cell.locator('a').count() === 1 && (await cell.innerText()).trim() === '1', 'the count is a link' + where);
  calls.length = 0;
  await cell.locator('a').click(); await page.waitForLoadState('networkidle'); await settle();
  const req = calls.find(c => c.startsWith('services/prices?'));
  check(req && req.includes('name=Tagespflege') && req.includes('status=current') && req.includes('collection=alpha-care'), 'drilldown asks for the current prices of the name' + where);
  check(await page.locator('#skg-prices').isVisible() && (await page.locator('#skg-prices-title').textContent()).includes('Tagespflege'), 'prices view of the name' + where);
  const rows = page.locator('#skg-prices-table tbody tr');
  check(await rows.count() === 1, 'one row per current price' + where);
  const text = await rows.first().innerText();
  check(text.includes('Anbieter 0') && text.includes('Tagespflege') && text.includes('49') && text.includes('EUR') && text.includes('zzgl. Fahrtkosten')
    && text.includes('2027-01-13'), 'provider, service, price, currency, conditions and date' + where);
  check(await rows.first().getAttribute('data-status') === 'current', 'row status' + where);
  check(await page.locator('#skg-prices-scope').isVisible(), 'the name is the scope, with the way back' + where);
  // sources of a price
  await rows.first().locator('button').first().click();
  await page.waitForFunction(() => document.querySelector('#skg-prices-table tbody').innerText.includes('anbieter0.example'));
  check(true, 'the evidence of a price opens in its row' + where);

  // 5. the status counts are links to their lists
  calls.length = 0;
  await page.locator('#skg-prices-counts a').first().click(); await page.waitForLoadState('networkidle'); await settle();
  check(calls.some(c => c.startsWith('services/prices?') && c.includes('status=conflicting')), 'conflicting prices listed on their own' + where);
  check(await page.locator('#skg-prices-table tbody tr').count() === 2 && (await page.locator('#skg-prices-table').innerText()).includes(await L('conflicts_with')),
    'both conflicting prices, each marked' + where);
  check(await page.locator('#skg-prices-status').inputValue() === 'conflicting', 'the select shows the list' + where);

  // 6. all services with a current price, without a name or a category, paged
  await go('?view=services&collection=alpha-care');
  calls.length = 0;
  await page.locator('#skg-svc-prices').click(); await page.waitForLoadState('networkidle'); await settle();
  const all = calls.find(c => c.startsWith('services/prices?'));
  check(all && !all.includes('name=') && !all.includes('category='), 'no name and no category needed' + where);
  check(await page.locator('#skg-prices-table tbody tr').count() === 25 && !(await page.locator('#skg-prices-next').isDisabled()), 'first page of 30' + where);
  if (language === 'de') check((await page.locator('#skg-prices-title').textContent()) === 'Leistungen mit Preis' && (await page.locator('#skg-view-prices').textContent()) === 'Preise', 'German view title and tab');
  calls.length = 0;
  await page.locator('#skg-prices-next').click(); await page.waitForLoadState('networkidle'); await settle();
  check(calls.some(c => c.startsWith('services/prices?') && c.includes('offset=25')) && await page.locator('#skg-prices-table tbody tr').count() === 5, 'second page' + where);
  // outdated prices keep their reason
  await go('?view=prices&collection=alpha-care&status=outdated');
  const outdated = await page.locator('#skg-prices-table').innerText();
  check(outdated.includes('2025-07-30') && outdated.includes('180') && outdated.includes('2026-12-31'), 'stale since and valid through shown' + where);
  // an empty list says so
  await go('?view=prices&collection=alpha-care&status=uncertain');
  check(await page.locator('#skg-message').textContent() === await L('prices_empty'), 'empty list explained' + where);

  check(await noOverflow(), 'no horizontal scrolling' + where);
  check(writes.length === 0, 'only GET requests: ' + writes.join(', ') + where);
  check(errors.length === 0, 'no page errors: ' + errors.join('; ') + where);
  await context.close();
}
await browser.close();
console.log(`PASS: ${checks} service category and price drilldown UI checks (EN/DE, phone and desktop, empty, failed, collection switch, paging)`);
