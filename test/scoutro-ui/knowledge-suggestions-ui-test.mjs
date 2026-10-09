#!/usr/bin/env node
/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Offline browser regression: real page/JS/CSS, mocked read API, no peer,
 * crawl, LLM call or production data. Run: node test/scoutro-ui/knowledge-suggestions-ui-test.mjs */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const { chromium } = createRequire(import.meta.url)('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const base = 'http://127.0.0.1:8090';
const origin = 'kge_' + 'a'.repeat(20), target = 'kge_' + 'b'.repeat(20), partner = 'kge_' + 'c'.repeat(20);
const sid = 'kgs_' + 'a'.repeat(20), doc = 'AAAAAAhost01';
const at = '2026-10-09T10:00:00Z';
const ref = { id: target, name: 'Candidate B', type: 'organization', target_collection: 'kgb', collections: ['kgb', 'regional'], other_collections: ['kgb', 'regional'] };
const contribution = { id: 'kgd_' + 'a'.repeat(20), collection_a: 'kga', collection_b: 'kgb', origin_collection: 'kga', target_collection: 'kgb',
  reason: 'Declared audience meets candidate industry', score: 0.6, computed_at: at, evidence_complete: true,
  evidence: [{ id: sid, predicate: 'industry', object: { value: 'Q87' }, collection: 'kgb', sources: 1 }] };
const suggestion = { id: contribution.id, kind: 'suggested_customer', other: ref, direction: 'out', score: 0.6, confidence: 0.6,
  fact: false, label: 'suggestion', reason: contribution.reason, computed_at: at, target_collection: 'kgb', contributions: [contribution] };
const second = { ...suggestion, id: 'kgd_' + 'b'.repeat(20), kind: 'suggested_partner', other: { ...ref, id: partner, name: 'Partner B' } };
const nodeOrigin = { id: origin, label: 'Provider A', type: 'organization', depth: 0, collections: ['kga'], hosts: ['provider.example'] };
const edge = { ...suggestion, id: 'suggestion:suggested_customer:' + origin + ':' + target, from: origin, to: target,
  type: 'suggested_customer', status: 'suggested', evidence: 1 };
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] });
let checks = 0;
const check = (value, text) => { assert(value, text); checks++; };
try {
  for (const [language, width] of [['en', 1280], ['de', 390]]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, locale: language });
    const page = await context.newPage(), errors = [], requests = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.route(base + '/**', async route => {
      const url = new URL(route.request().url());
      if (url.pathname === '/ScoutroKnowledge_p.html') {
        let html = fs.readFileSync(path.join(root, 'htroot/ScoutroKnowledge_p.html'), 'utf8').replace(/#%[^\n]*?%#/g, '');
        if (language === 'de') {
          html = html.replace('lang="en"', 'lang="de"');
          const section = fs.readFileSync(path.join(root, 'locales/de.lng'), 'utf8').split('#File: ScoutroKnowledge_p.html\n')[1].split('#File: ')[0];
          const entries = section.split('\n').filter(l => l.includes('==') && !l.startsWith('#')).map(l => l.split('=='));
          for (const [key, value] of entries.sort((a, b) => b[0].length - a[0].length)) html = html.split(key).join(value);
        }
        return route.fulfill({ contentType: 'text/html', body: html });
      }
      if (url.pathname === '/env/scoutro/collections.js') return route.fulfill({ contentType: 'application/javascript', body:
        `window.ScoutroCollections = {load: async () => ['kga','kgb','regional'], fill: (el, ids) => { for(const id of ids) {const o=document.createElement('option');o.value=id;o.textContent=id;el.append(o);} el.disabled=false;}};` });
      if (url.pathname.startsWith('/env/scoutro/')) {
        return route.fulfill({ contentType: url.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(path.join(root, 'htroot', url.pathname.slice(1)), 'utf8') });
      }
      if (!url.pathname.startsWith('/scoutro/api/v1/kg/')) return route.fulfill({ status: 404, body: '' });
      requests.push(url);
      const resource = url.pathname.slice('/scoutro/api/v1/kg/'.length), selected = url.searchParams.get('collection');
      const current = resource.startsWith('entities/' + origin) ? { id: origin, name: 'Provider A' }
        : resource.startsWith('entities/' + target) ? { id: target, name: 'Candidate B' } : { id: partner, name: 'Partner B' };
      let data;
      if (resource.endsWith('/business')) data = { ...current, overview: { ...current, type: 'organization' }, suggestions:
        { items: current.id === origin ? [suggestion] : [], total: current.id === origin ? 2 : 0, next_offset: current.id === origin ? 1 : null } };
      else if (resource.endsWith('/suggestions')) data = { items: [second], total: 2, next_offset: null };
      else if (resource.endsWith('/statements')) data = { items: [], total: 0 };
      else if (resource.endsWith('/neighborhood')) {
        const on = url.searchParams.get('suggested') === 'true';
        data = { center: origin, nodes: on ? [nodeOrigin, { ...ref, label: ref.name, depth: 1 }] : [nodeOrigin], edges: on ? [edge] : [], neighbours: on ? 1 : 0, truncated: false, next_offset: null };
      } else if (resource.startsWith('entities/')) data = { ...current, type: 'organization', quality: 'supported', aliases: [], identifiers: [], counts: { statements: 0, sources: 0 } };
      else if (resource.endsWith('/evidence')) {
        check(selected === 'kgb', 'evidence request uses contribution context, not origin filter');
        data = { items: [{ doc_id: doc, url: 'https://candidate.example/facts', collections: ['kgb'], excerpt: 'Candidate industry evidence', observed_at: at, loaded_at: at, state: 'active', kind: 'jsonld' }], total: 2 };
      } else if (resource.startsWith('sources/')) {
        check(selected === 'kgb', 'source request uses evidence context');
        data = { source: { doc_id: doc, url: 'https://candidate.example/facts', collections: ['kgb'], state: 'active', tiers: [1] }, items: [], total: 0 };
      } else data = {};
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
    });
    await page.goto(`${base}/ScoutroKnowledge_p.html?view=object&id=${origin}&collection=kga`);
    await page.locator('#skg-sec-matches .skg-derived').waitFor();
    const item = page.locator('#skg-sec-matches .skg-derived').first();
    const tag = language === 'de' ? 'Andere Collection' : 'Other collection';
    check((await item.textContent()).includes(tag + ': kgb') && (await item.textContent()).includes(tag + ': regional'), 'all external authorized memberships tagged');
    check(!(await item.textContent()).includes('%'), 'suggestion score is not shown as a probability');
    check(await item.locator('a').count() === 1, 'one target entry (not duplicated across audience sections)');
    check(new URL(await item.locator('a').getAttribute('href'), base).searchParams.get('collection') === 'kgb', 'target href overrides origin filter');
    const toggle = page.locator('#skg-sec-matches input[type=checkbox]');
    await toggle.uncheck(); check(!(await item.isVisible()), 'object list checkbox hides suggestions'); await toggle.check();
    await item.locator('a').click(); await page.waitForFunction(() => document.querySelector('#skg-object-name').textContent === 'Candidate B');
    check(new URL(page.url()).searchParams.get('collection') === 'kgb', 'target navigation opens B');
    await page.goBack(); await page.locator('#skg-sec-matches .skg-derived').waitFor();
    check(new URL(page.url()).searchParams.get('collection') === 'kga', 'Back restores origin A');
    await page.locator('#skg-sec-matches details summary').first().click();
    await page.locator('#skg-sec-matches .skg-contribution button').first().click();
    await page.locator('#skg-sec-matches .skg-excerpt').waitFor();
    const nextEvidence = page.waitForResponse(response => {
      const url = new URL(response.url());
      return url.pathname.endsWith('/evidence') && url.searchParams.get('offset') === '1';
    });
    await page.locator('#skg-sec-matches .skg-evidence > button').click();
    await nextEvidence;
    check(requests.some(u => u.pathname.endsWith('/evidence') && u.searchParams.get('offset') === '1' && u.searchParams.get('collection') === 'kgb'), 'additional evidence pages keep B context');
    const source = page.locator('#skg-sec-matches .skg-evidence a[href*="view=source"]').first();
    check(new URL(await source.getAttribute('href'), base).searchParams.get('collection') === 'kgb', 'source href preserves evidence context');
    await source.click(); await page.locator('#skg-source').waitFor({ state: 'visible' });
    await page.goBack(); await page.locator('#skg-sec-matches').waitFor({ state: 'visible' });
    await page.locator('#skg-sec-matches > div > button').click();
    await page.waitForFunction(() => document.querySelectorAll('#skg-sec-matches .skg-derived').length === 2);
    check(requests.some(u => u.pathname.endsWith('/suggestions') && u.searchParams.get('offset') === '1'), 'More reads the next group page');

    await page.goto(`${base}/ScoutroKnowledge_p.html?view=network&id=${origin}&collection=kga&f=business,structure,offers,places,industry,audiences,jobs,derived,suggested`);
    await page.locator('#skg-net-svg .skg-edge.skg-e-suggested').first().waitFor();
    check((await page.locator('#skg-net-table').textContent()).includes(tag + ': kgb'), 'network list labels external candidate');
    await page.locator(`.skg-node[data-node="${target}"]`).click();
    const open = page.locator('#skg-net-detail a[href*="view=object"]').first();
    check(new URL(await open.getAttribute('href'), base).searchParams.get('collection') === 'kgb', 'graph detail target context');
    const json = JSON.parse(await page.locator('#skg-net-json').evaluate(async a => (await fetch(a.href)).text()));
    check(json.collection === 'kga' && json.nodes.find(n => n.id === target).target_collection === 'kgb', 'JSON export carries origin and target contexts');
    check(json.edges[0].contributions[0].evidence[0].collection === 'kgb', 'JSON export carries scoped supporting statements');
    const xml = await page.locator('#skg-net-graphml').evaluate(async a => (await fetch(a.href)).text());
    check(xml.includes('key="target_collection">kgb') && xml.includes('key="contributions"') && xml.includes('sorting_score'), 'GraphML export carries contexts and reasons');
    await page.locator('#skg-f-list').check();
    check(await page.locator('#skg-net-wrap').isHidden() && (await page.locator('#skg-net-table tbody tr').count()) === 1, 'list mode keeps suggestions');
    await page.locator('#skg-f-suggested').uncheck(); await page.locator('#skg-net-form button[type=submit]').click();
    await page.waitForFunction(() => document.querySelectorAll('#skg-net-table tbody tr').length === 0);
    check((await page.locator('#skg-net-svg .skg-edge.skg-e-suggested').count()) === 0, 'network checkbox removes suggestions from graph and list');
    check(errors.length === 0, 'no browser errors: ' + errors.join(', '));
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'no horizontal overflow');
    await context.close();
  }
} finally { await browser.close(); }
console.log(`PASS: ${checks} offline suggestion UI checks (EN/DE, object/list/graph, navigation/evidence/export)`);
