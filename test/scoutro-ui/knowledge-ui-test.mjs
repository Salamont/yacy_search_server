#!/usr/bin/env node
/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Only run through knowledge-live-smoke.py: disposable peer, collections kga and kgb.
 * The organisation is seen in kga and kgb (alias "Geheime Holding" and a VAT ID only in kgb);
 * "Nur Bee GmbH" only in kgb; the LLM tier read "operates Haus Lindenhof" in kga. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
const entity = process.env.SCOUTRO_KG_ENTITY, host = process.env.SCOUTRO_KG_HOST, onlyB = process.env.SCOUTRO_KG_ONLY_B;
// vocabulary 2: the operator (kga) and the software firm (kgb) that suggests it as a possible customer
const operator = process.env.SCOUTRO_KG_OPERATOR, soft = process.env.SCOUTRO_KG_SOFT;
// package 6.1: CTcon's "SAP" (kgb), one of three providers of the name, and CTcon itself
const sap = process.env.SCOUTRO_KG_SAP, ctcon = process.env.SCOUTRO_KG_CTCON, unnamedOrg = process.env.SCOUTRO_KG_UNNAMED;
assert(base && new URL(base).hostname === '127.0.0.1' && entity && host && onlyB && operator && soft && sap && ctcon, 'Use knowledge-live-smoke.py; no production instance');
const shots = process.env.SCOUTRO_SCREENSHOTS;
if (shots) fs.mkdirSync(shots, { recursive: true });
let checks = 0;
function check(condition, message) { assert(condition, message); checks++; }
const browser = await chromium.launch({
  ...(process.env.SCOUTRO_CHROMIUM_PATH ? { executablePath: process.env.SCOUTRO_CHROMIUM_PATH } : {}),
  args: ['--no-proxy-server'],
});
const noOverflow = page => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1);
// package 6.1: no technical ID in any visible name (links, headings, network cards); the technical ID field is no name
const noIdAsName = page => page.evaluate(() => ![...document.querySelectorAll('#skg-main a, main a, main h2, main h3, #skg-net-svg text, #skg-net-svg title')]
  .filter(n => n.offsetParent !== null || n.closest('svg')).some(n => /kg[es]_[a-z2-7]{20}/.test(n.textContent)));
try {
  const anonymous = await browser.newContext();
  for (const p of ['/ScoutroKnowledge_p.html', '/scoutro/api/v1/kg/entities', '/scoutro/api/v1/kg/entities/' + entity, '/scoutro/api/v1/kg/sources/AAAAAAAAAAAA',
    '/scoutro/api/v1/kg/entities/' + operator + '/business', '/scoutro/api/v1/kg/entities/' + operator + '/neighborhood',
    '/scoutro/api/v1/kg/compare?category=care/tagespflege', '/scoutro/api/v1/kg/derived', '/scoutro/api/v1/kg/facets'])
    check((await anonymous.request.get(base + p)).status() === 401, 'administrator required: ' + p);
  await anonymous.close();

  for (const language of ['en', 'de']) {
    for (const width of language === 'de' ? [360, 390, 412, 768, 1280] : [390, 1280]) {
      const where = ` (${language}/${width})`;
      const context = await browser.newContext({ locale: language, viewport: { width, height: 900 }, httpCredentials: { username: 'admin', password: 'yacy' } });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      page.on('dialog', d => d.accept());
      try {
        // overview
        const response = await page.goto(base + '/ScoutroKnowledge_p.html', { waitUntil: 'networkidle' });
        check(response.status() === 200, 'page' + where);
        await page.waitForFunction(() => document.querySelector('#skg-cards').children.length >= 3);
        check(await page.locator('#skg-overview').isVisible(), 'overview visible' + where);
        check((await page.locator('#skg-cards').textContent()).includes('running'), 'state running' + where);
        // package 6.3: the two layers and the model of the LLM selection; never no_llm_collections with a model and an LLM collection
        const lbl = k => page.evaluate(k => document.querySelector(`[data-skg-label="${k}"]`).textContent, k);
        const cardValue = label => page.evaluate(l => [...document.querySelectorAll('#skg-cards dl')].find(d => d.querySelector('dt').textContent === l)?.querySelector('dd').textContent, label);
        const [lOn, lDet, lLlm, lModel] = [await lbl('kg_on'), await lbl('kg_deterministic'), await lbl('kg_llm'), await lbl('kg_model')];
        check(await cardValue(lDet) === lOn, 'deterministic graph active' + where);
        check((await cardValue(lLlm) || '').startsWith(lOn), 'LLM enrichment active: ' + await cardValue(lLlm) + where);
        check(/^[A-Z_]+\/fixture$/.test(await cardValue(lModel) || ''), 'the model of the LLM selection (service/model): ' + await cardValue(lModel) + where);
        check(!(await page.locator('#skg-overview').textContent()).includes('no_llm_collections'), 'no no_llm_collections with a model and an LLM collection' + where);
        if (language === 'de') check((await page.locator('h1').textContent()).trim() === 'Wissensgraph', 'German heading' + where);
        check(await page.locator('#scoutro-adminnav a[href="ScoutroKnowledge_p.html"]').count() === 1, 'navigation entry' + where);
        await page.waitForFunction(() => document.querySelector('#skg-vocab').children.length > 0);
        const vocab = await page.locator('#skg-vocab').textContent();
        check(vocab.includes('kga: care') && vocab.includes('kgb: software') && /1[.,]?047/.test(vocab), 'vocabularies per collection and NACE codes' + where);
        check(await page.locator('#skg-upgrade-note').isHidden(), 'no upgrade waiting on a new graph' + where);
        // package 6.1: every followed collection with its vocabulary; none is said, not hidden
        const de0 = language === 'de';
        const kgc = page.locator('#skg-collections tbody tr[data-collection="kgc"]');
        check((await kgc.textContent()).includes(de0 ? 'Kein Vokabular zugeordnet' : 'No vocabulary assigned'), 'a collection without a vocabulary says so' + where);
        check(await page.locator('#skg-collections tbody tr[data-collection="kga"]').textContent().then(x => x.includes('care')), 'kga with care' + where);
        check(await kgc.getAttribute('data-state') === 'following', 'kgc followed' + where);
        check(await noOverflow(page), 'no horizontal overflow (overview)' + where);
        if (shots) await page.screenshot({ path: path.join(shots, `kg-overview-${language}-${width}.png`), fullPage: true });

        // the LLM tier's counters since the start: droppedInvalid with its reasons (folded) and valuesAccepted
        const llmPanel = page.locator('#skg-llm-title').locator('..');
        check((await page.locator('#skg-llm').textContent()).includes(await lbl('dropped_invalid')) && (await page.locator('#skg-llm').textContent()).includes(await lbl('llm_accepted')),
          'LLM tier: accepted items and dropped as invalid' + where);
        check((await llmPanel.textContent()).includes(language === 'de' ? 'seit dem letzten Start' : 'since the last start'), 'counters marked as since the last start' + where);
        check(!(await page.locator('#skg-llm-invalid-details').evaluate(d => d.open)), 'reasons folded' + where);
        const liveStatus = await page.evaluate(async () => (await fetch('/scoutro/api/v1/kg/status', { credentials: 'same-origin' })).json());
        const processed = liveStatus.llm.processed;
        const reasons = Object.values(processed.droppedInvalidByReason || {});
        check(typeof processed.valuesAccepted === 'number' && reasons.length >= 46 && reasons.reduce((a, b) => a + b, 0) === processed.droppedInvalid,
          'status: valuesAccepted, every reason code, the reasons add up to droppedInvalid' + where);
        if (width === 360 || width === 1280) {
          // the reasons that occurred, the most frequent first; zeros, an older server and missing counters stay calm
          const zeros = Object.fromEntries(Object.keys(processed.droppedInvalidByReason).map(k => [k, 0]));
          const variants = [
            ['reasons', { ...processed, valuesAccepted: 2, droppedInvalid: 10, droppedInvalidByReason: { ...zeros, claim_unresolved_object: 3, entity_extra_field: 7 } }],
            ['zeros', { ...processed, droppedInvalid: 0, droppedInvalidByReason: zeros }],
            ['older server', { calls: 1, entitiesAccepted: 1, claimsAccepted: 0, droppedUngrounded: 0 }],
            ['nulls', { calls: null, entitiesAccepted: null, valuesAccepted: null, droppedInvalid: null, droppedInvalidByReason: null }],
            ['no counters', null]];
          for (const [name, variant] of variants) {
            // the live status of this page with other counters (the page's own request is answered, nothing else changes)
            await page.route(/\/scoutro\/api\/v1\/kg\/status(\?|$)/, route => route.fulfill({ status: 200, contentType: 'application/json',
              body: JSON.stringify({ ...liveStatus, llm: { ...liveStatus.llm, processed: variant } }) }));
            await page.goto(base + '/ScoutroKnowledge_p.html', { waitUntil: 'networkidle' });
            await page.waitForFunction(() => document.querySelector('#skg-llm').children.length > 0);
            await page.locator('#skg-llm-invalid-details summary').click();
            const shown = await page.locator('#skg-llm-invalid dt').allTextContents();
            if (name === 'reasons') {
              check(JSON.stringify(shown) === JSON.stringify(['entity_extra_field', 'claim_unresolved_object'])
                && JSON.stringify(await page.locator('#skg-llm-invalid dd').allTextContents()) === JSON.stringify(['7', '3']), 'reasons, most frequent first, zeros left out' + where);
              const llmRow = async key => page.evaluate(l => [...document.querySelectorAll('#skg-llm dt')].find(d => d.textContent === l)?.nextElementSibling?.textContent, await lbl(key));
              check(await llmRow('dropped_invalid') === '10' && (await llmRow('llm_accepted') || '').endsWith(' · 2'), 'droppedInvalid and valuesAccepted shown' + where);
            } else check(shown.length === 0 && (await page.locator('#skg-llm-invalid').textContent()) === await lbl('none'), 'no reason: none (' + name + ')' + where);
            check(await noOverflow(page), 'no horizontal overflow (LLM reasons, ' + name + ')' + where);
            if (shots && name === 'reasons') await page.screenshot({ path: path.join(shots, `kg-llm-reasons-${language}-${width}.png`), fullPage: true });
            await page.unroute(/\/scoutro\/api\/v1\/kg\/status(\?|$)/);
          }
          // the structured-output mode: the live one of the fixture (a model never probed: schema sent, not confirmed), then mocked modes
          check(liveStatus.llm.structuredOutput?.mode === 'schema_unverified' && liveStatus.llm.structuredOutput?.capability === 'unknown'
            && liveStatus.llm.structuredOutput?.api === 'ollama_native'
            && typeof liveStatus.llm.structuredOutput?.requests?.json_schema === 'number', 'status: structured output of the fixture model ' + JSON.stringify(liveStatus.llm.structuredOutput) + where);
          const soVariants = [
            ['validator_only', { setting: 'auto', api: 'ollama_native', capability: 'unsupported', mode: 'validator_only', request: 'none', reason: 'capability_unsupported',
              withoutSchema: false, requests: { json_schema: 0, json_object: 0, none: 5 }, rejections: 0, lastRejection: null }],
            ['fallback_after_rejection', { setting: 'auto', capability: 'supported', mode: 'fallback_after_rejection', request: 'none', reason: 'http_400',
              withoutSchema: true, requests: { json_schema: 1, json_object: 0, none: 4 }, rejections: 1, lastRejection: { code: 'http_400', at: 1 } }],
            ['json_mode', { setting: 'json_object', capability: 'unknown', mode: 'json_mode', request: 'json_object', reason: 'setting',
              withoutSchema: false, requests: { json_schema: 0, json_object: 3, none: 0 }, rejections: 0, lastRejection: null }],
            ['ignored', { setting: 'auto', capability: 'ignored', mode: 'schema_unverified', request: 'json_schema', reason: 'capability_ignored',
              withoutSchema: false, requests: { json_schema: 2, json_object: 0, none: 0 }, rejections: 0, lastRejection: null }],
            ['older server', undefined], ['null', null]];
          for (const [name, so] of soVariants) {
            await page.route(/\/scoutro\/api\/v1\/kg\/status(\?|$)/, route => route.fulfill({ status: 200, contentType: 'application/json',
              body: JSON.stringify({ ...liveStatus, llm: { ...liveStatus.llm, structuredOutput: so } }) }));
            await page.goto(base + '/ScoutroKnowledge_p.html', { waitUntil: 'networkidle' });
            await page.waitForFunction(() => document.querySelector('#skg-llm').children.length > 0);
            const llmRow = async key => page.evaluate(l => [...document.querySelectorAll('#skg-llm dt')].find(d => d.textContent === l)?.nextElementSibling?.textContent, await lbl(key));
            if (so) {
              check(await llmRow('structured_output') === await lbl('so_mode_' + so.mode) + ' · ' + await lbl('so_cap_' + so.capability)
                + (so.api ? ' · ' + await lbl('so_api_' + so.api) : ''), 'structured output shown: ' + name + where);
              check((await llmRow('so_requests')).startsWith(String(so.requests.json_schema) + ' ') && (await llmRow('so_requests')).includes(String(so.rejections)), 'requests by format: ' + name + where);
              check(!(await page.locator('#skg-llm').textContent()).includes('{'), 'no raw object shown: ' + name + where);
            } else check(await llmRow('structured_output') === await lbl('missing') && await llmRow('so_requests') === await lbl('missing'), 'no structured output: ' + name + where);
            check(await noOverflow(page), 'no horizontal overflow (structured output, ' + name + ')' + where);
            await page.unroute(/\/scoutro\/api\/v1\/kg\/status(\?|$)/);
          }
          check(errors.length === 0, 'no JavaScript error with any counters: ' + errors.join('; ') + where);
        }

        // objects: all collections, then kga only
        await page.goto(base + '/ScoutroKnowledge_p.html?view=objects', { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-entities tbody tr');
        const all = await page.locator('#skg-entities tbody').textContent();
        check(all.includes('Muster Pflege gGmbH') && all.includes('Nur Bee GmbH'), 'all objects without a filter' + where);
        await page.goto(base + '/ScoutroKnowledge_p.html?view=objects&collection=kga', { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-entities tbody tr');
        const a = await page.locator('#skg-entities tbody').textContent();
        check(a.includes('Muster Pflege gGmbH') && !a.includes('Nur Bee GmbH'), 'collection filter hides the other collection' + where);
        check(await page.locator('#skg-collection').inputValue() === 'kga', 'collection kept in the form' + where);
        // package 6.1: the collection is a real select of the collections of the index, sorted, all collections first
        const scope = page.locator('#skg-collection');
        check(await scope.evaluate(e => e.tagName) === 'SELECT' && await page.locator('#skg-scope input, datalist').count() === 0, 'collection as a real select, no text field' + where);
        const scopeOptions = await scope.locator('option').evaluateAll(list => list.map(o => [o.value, o.textContent.trim()]));
        const indexNames = await page.evaluate(async () => (await (await fetch('/scoutro/api/v1/collections', { credentials: 'same-origin' })).json()).collections.filter(c => c.selectable && !c.internal).map(c => c.id).sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : a.toLowerCase() > b.toLowerCase() ? 1 : a < b ? -1 : a > b ? 1 : 0));
        check(scopeOptions[0][0] === '' && scopeOptions[0][1] === (language === 'de' ? 'Alle Collections' : 'All collections')
          && JSON.stringify(scopeOptions.slice(1).map(o => o[0])) === JSON.stringify(indexNames) && ['kga', 'kgb', 'kgc'].every(c => indexNames.includes(c)),
          'all collections first, then every collection of the index sorted, the new kgc included: ' + JSON.stringify(scopeOptions) + where);
        await scope.selectOption('kgb');
        await page.waitForFunction(() => new URLSearchParams(location.search).get('collection') === 'kgb'
          && (document.querySelector('#skg-entities tbody')?.textContent || '').includes('Nur Bee GmbH'));
        check(await scope.inputValue() === 'kgb' && (await page.locator('#skg-entities tbody').textContent()).includes('Nur Bee GmbH'),
          'choosing a collection applies it at once' + where);
        await page.goto(base + '/ScoutroKnowledge_p.html?view=objects&collection=unknown-web', { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-entities tbody tr');
        check(await scope.inputValue() === '' && await page.locator('#skg-scope-unknown').isVisible() && !new URL(page.url()).searchParams.has('collection')
          && (await page.locator('#skg-entities tbody').textContent()).includes('Nur Bee GmbH'), 'an unknown collection of the link: all collections, said so' + where);
        if (width === 360) {
          // while the list loads, the select waits (disabled, busy) and the page asks nothing with a collection of the link
          await page.route('**/scoutro/api/v1/collections', async route => { await new Promise(r => setTimeout(r, 800)); await route.continue(); });
          const asked = [];
          page.on('request', r => { if (r.url().includes('/scoutro/api/v1/kg/entities')) asked.push(r.url()); });
          await page.goto(base + '/ScoutroKnowledge_p.html?view=objects&collection=kga');
          check(await scope.isDisabled() && await scope.getAttribute('aria-busy') === 'true' && asked.length === 0, 'loading: the select waits' + where);
          await page.waitForFunction(() => !document.getElementById('skg-collection').disabled);
          await page.waitForSelector('#skg-entities tbody tr');
          check(await scope.inputValue() === 'kga' && asked.every(u => u.includes('collection=kga')), 'loaded: the listed collection of the link applied' + where);
          await page.unroute('**/scoutro/api/v1/collections');
        }
        await page.goto(base + '/ScoutroKnowledge_p.html?view=objects&collection=kga', { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-entities tbody tr');
        if (width < 768) check(await page.locator('#skg-entities tbody tr').first().evaluate(r => getComputedStyle(r).display !== 'table-row'), 'rows as cards on narrow screens' + where);
        await page.locator('#skg-q').fill('Nur');
        await page.locator('#skg-search button[type=submit]').click();
        await page.waitForFunction(() => new URLSearchParams(location.search).get('q') === 'Nur' && document.querySelector('#skg-range').textContent === ''
          && !document.querySelector('#skg-message').textContent.includes('…'));
        check(await page.locator('#skg-entities tbody tr').count() === 0, 'search within kga finds no kgb name' + where);
        check(await noOverflow(page), 'no horizontal overflow (objects)' + where);
        check(await noIdAsName(page), 'no technical ID as a name (objects)' + where);

        // object view in kga: own facts, the LLM relation, no kgb data; evidence with source link
        await page.goto(base + `/ScoutroKnowledge_p.html?view=object&id=${entity}&collection=kga`, { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-out .skg-statement');
        const object = await page.locator('#skg-object').textContent();
        check((await page.locator('#skg-object-name').textContent()).includes('Muster Pflege gGmbH'), 'object name' + where);
        check(object.includes('Haus Lindenhof'), 'relation from the LLM tier' + where);
        check(!object.includes('Geheime Holding') && !object.includes('DE123456789'), 'nothing of kgb in the kga view' + where);
        check(await page.locator('#skg-out .skg-llm').count() >= 1, 'LLM-only badge' + where);
        const lindenhof = page.locator('#skg-out .skg-statement', { hasText: 'Haus Lindenhof' }).first();
        await lindenhof.locator('button').click();
        await lindenhof.locator('.skg-excerpt').first().waitFor();
        check((await lindenhof.locator('.skg-excerpt').first().textContent()).includes('betreibt das Haus Lindenhof'), 'verbatim quote as evidence' + where);
        check((await lindenhof.locator('.skg-evidence').textContent()).includes('kga') && !(await lindenhof.locator('.skg-evidence').textContent()).includes('kgb'), 'evidence lists visible collections only' + where);
        check(await noOverflow(page), 'no horizontal overflow (object)' + where);
        if (shots) await page.screenshot({ path: path.join(shots, `kg-object-${language}-${width}.png`), fullPage: true });
        await lindenhof.locator('.skg-evidence a', { hasText: language === 'de' ? 'Was der Graph aus dieser Seite enthält' : 'What the graph holds from this page' }).first().click();
        await page.waitForSelector('#skg-source-items .skg-statement');
        check(await page.locator('#skg-source').isVisible() && (await page.locator('#skg-source-facts').textContent()).includes(host), 'source view' + where);
        check(new URL(page.url()).searchParams.get('collection') === 'kga', 'collection kept on navigation' + where);

        // the same object without a filter shows kgb's alias; the kgb-only object is not found in kga
        await page.goto(base + `/ScoutroKnowledge_p.html?view=object&id=${entity}`, { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-out .skg-statement');
        check((await page.locator('#skg-object-facts').textContent()).includes('Geheime Holding'), 'administrator without filter sees all' + where);
        await page.goto(base + `/ScoutroKnowledge_p.html?view=object&id=${onlyB}&collection=kga`, { waitUntil: 'networkidle' });
        await page.waitForFunction(() => document.querySelector('#skg-message').textContent.includes('404'));
        check(true, 'kgb object not found in kga' + where);

        // vocabulary 2: the object view in sections, only those with content
        const de = language === 'de', L = (en, deText) => de ? deText : en;
        await page.goto(base + `/ScoutroKnowledge_p.html?view=object&id=${operator}`, { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-sec-prices');
        for (const sec of ['industry', 'services', 'prices', 'contacts', 'relations', 'jobs', 'matches', 'sources'])
          check(await page.locator('#skg-sec-' + sec).isVisible(), 'section ' + sec + where);
        check(await page.locator('#skg-sec-audiences').count() === 0, 'no empty audience section for the operator' + where);
        check(await page.locator('#skg-toc a').count() >= 9, 'table of contents of the sections' + where);
        check((await page.locator('#skg-sec-prices h3').textContent()).trim() === L('Prices', 'Preise'), 'section heading translated' + where);
        const prices = await page.locator('#skg-sec-prices').textContent();
        check(prices.includes(L('from 49 EUR per day', 'ab 49 EUR pro Tag')) && prices.includes(L('from 59 EUR per day', 'ab 59 EUR pro Tag')),
          'prices as published, both kept: ' + prices.slice(0, 200) + where);
        check(await page.locator('#skg-sec-prices tr[data-status="conflicting"]').count() === 2, 'the conflicting prices are marked' + where);
        check(prices.includes('2026-09'), 'the stated date of the price list' + where);
        check((await page.locator('#skg-sec-industry').textContent()).includes('NACE Rev. 2.1 / WZ 2025'), 'industry with its classification' + where);
        const jobs = await page.locator('#skg-sec-jobs').textContent();
        check(jobs.includes('Pflegefachkraft (m/w/d)') && jobs.includes(L('3,400 EUR to 3,900 EUR per month', '3.400 EUR bis 3.900 EUR pro Monat')), 'job with its salary and unit' + where);
        const matches = await page.locator('#skg-sec-matches').textContent();
        check(matches.includes('PflegeSoft UI GmbH') && matches.includes(L('suggestion', 'Vorschlag')) && !matches.includes(L('is a customer of', 'ist Kunde von')),
          'a suggestion, never a customer relation' + where);
        await page.locator('#skg-toc a', { hasText: L('Jobs', 'Jobs') }).click();
        check(await page.evaluate(() => document.activeElement?.closest('#skg-sec-jobs') !== null), 'table of contents moves the focus' + where);
        check(await noOverflow(page), 'no horizontal overflow (business view)' + where);
        if (shots) await page.screenshot({ path: path.join(shots, `kg-business-${language}-${width}.png`), fullPage: true });
        await page.goto(base + `/ScoutroKnowledge_p.html?view=object&id=${operator}&collection=kga`, { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-sec-prices');
        check(await page.locator('#skg-sec-matches').count() === 0 && !(await page.locator('#skg-object').textContent()).includes('PflegeSoft'),
          'kga sees no suggestion that needs kgb' + where);

        // the network: SVG and list from the same data, filters, keyboard, export
        await page.locator('#skg-object-network').click();
        await page.waitForSelector('#skg-net-svg[data-state="ready"] .skg-node');
        check(new URL(page.url()).searchParams.get('view') === 'network' && new URL(page.url()).searchParams.get('collection') === 'kga', 'network keeps the collection' + where);
        const edges = await page.locator('#skg-net-svg .skg-edge').count();
        check(edges >= 6 && await page.locator('#skg-net-table tbody tr').count() === edges, 'every line is also a list row' + where);
        check(await page.locator('#skg-net-svg .skg-edge.skg-e-suggested').count() === 0, 'no suggestions unless asked' + where);
        check(await noOverflow(page), 'no horizontal overflow (network)' + where);
        const birke = page.locator('#skg-net-svg .skg-node', { hasText: 'Haus Birke' }).first();
        await birke.focus();
        await page.waitForSelector('#skg-net-detail:not([hidden])');
        check((await page.locator('#skg-net-detail').textContent()).includes('Lindenhof Pflege gGmbH'), 'focus shows the node and its lines' + where);
        check(await page.locator('#skg-net-svg .skg-edge.skg-hl').count() >= 1, 'its lines are highlighted' + where);
        const line = page.locator('#skg-net-svg .skg-edge.skg-e-confirmed').first();
        await line.focus();
        await page.locator('#skg-net-detail button').first().click();
        await page.locator('#skg-net-detail .skg-excerpt, #skg-net-detail .skg-evidence li').first().waitFor();
        check(true, 'a line shows its evidence' + where);
        if (shots) await page.screenshot({ path: path.join(shots, `kg-network-${language}-${width}.png`), fullPage: true });
        const graphml = await page.evaluate(async () => (await fetch(document.querySelector('#skg-net-graphml').href)).text());
        check(graphml.includes('<graphml') && graphml.includes('edgedefault="directed"') && graphml.includes('Haus Birke'), 'GraphML export' + where);
        // depth 2: the homes' own lines, the derived "same operator" between them dotted
        await page.locator('#skg-depth').selectOption('2');
        await page.locator('#skg-net-form button[type=submit]').click();
        await page.waitForFunction(() => new URLSearchParams(location.search).get('depth') === '2' && document.querySelector('#skg-net-svg').dataset.state === 'ready');
        await page.waitForSelector('#skg-net-svg .skg-edge.skg-e-derived');
        check((await page.locator('#skg-net-table').textContent()).includes(L('same operator (derived)', 'gleicher Träger (abgeleitet)')), 'same operator at depth 2, also in the list' + where);
        await page.locator('#skg-f-industry').uncheck();
        await page.locator('#skg-f-audiences').uncheck();
        await page.locator('#skg-net-form button[type=submit]').click();
        await page.waitForFunction(() => new URLSearchParams(location.search).get('f') !== null && document.querySelector('#skg-net-svg').dataset.state === 'ready');
        check(await page.locator('#skg-net-svg .skg-t-value').count() === 0, 'filter: no industry or audience nodes' + where);
        await page.locator('#skg-f-list').check();
        check(await page.locator('#skg-net-wrap').isHidden() && await page.locator('#skg-net-table').isVisible(), 'list only' + where);
        await page.locator('#skg-f-list').uncheck();
        await page.waitForSelector('#skg-net-svg[data-state="ready"] .skg-node');
        await page.locator('#skg-net-svg .skg-node', { hasText: 'Haus Birke' }).first().press('Enter');
        await page.waitForSelector('#skg-net-detail:not([hidden]) .skg-detail-actions a');
        await page.locator('#skg-net-detail .skg-detail-actions a').first().click();
        await page.waitForFunction(() => !document.querySelector('#skg-object').hidden && document.querySelector('#skg-object-name').textContent.includes('Haus Birke')
          && document.querySelector('#skg-out .skg-statement') !== null, null, { timeout: 15000 }).catch(() => {});
        check((await page.locator('#skg-object-name').textContent()).includes('Haus Birke'), 'Enter on a node shows its details, the panel opens its object' + where);
        // the administrator without a filter sees the suggestions on request
        await page.goto(base + `/ScoutroKnowledge_p.html?view=network&id=${operator}&f=business,structure,offers,values,derived,suggested`, { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-net-svg[data-state="ready"] .skg-node');
        check(await page.locator('#skg-net-svg .skg-edge.skg-e-suggested').count() >= 1, 'suggestions shown on request, dotted' + where);

        // package 6.1: services of the same name across providers, with provider and domain; the network around a service
        await page.goto(base + '/ScoutroKnowledge_p.html?view=objects&q=SAP&type=service&collection=kgb', { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-entities tbody tr');
        const hits = page.locator('#skg-entities tbody tr');
        check(await hits.count() === 2, 'two services named SAP in kgb, never merged' + where);
        const hitText = await page.locator('#skg-entities tbody').textContent();
        check(hitText.includes('CTcon UI GmbH') && hitText.includes('ctcon-ui.de') && hitText.includes('Beta IT UI AG') && hitText.includes('beta-it-ui.de'),
          'each SAP with its provider and domain' + where);
        check(!hitText.includes('Gamma') && hitText.includes('kgb') && !hitText.includes('kga'), 'only the providers of kgb' + where);
        const actions = await hits.first().locator('.skg-actions').textContent();
        check(actions.includes(L('Open provider', 'Anbieter öffnen')) && actions.includes(L('Open service', 'Leistung öffnen')) && !/[a-z]_[a-z]/.test(actions),
          'provider, service, network, sources and all providers from the hit, every action labelled: ' + actions + where);
        await page.waitForSelector('#skg-groups:not([hidden])');
        check((await page.locator('#skg-groups').textContent()).includes(L('SAP · 2 providers', 'SAP · 2 Anbieter')), 'the group of the name, of kgb only' + where);
        check(await noOverflow(page), 'no horizontal overflow (service hits)' + where);
        if (shots) await page.screenshot({ path: path.join(shots, `kg-sap-hits-${language}-${width}.png`), fullPage: true });
        await page.locator('#skg-groups a').first().click();
        await page.waitForSelector('#skg-svc-rows tbody tr');
        check(await page.locator('#skg-svc-rows tbody tr').count() === 2 && (await page.locator('#skg-svc-group-title').textContent()).includes('SAP'),
          'all providers of SAP, one row each' + where);
        const svcRows = await page.locator('#skg-svc-rows tbody').textContent();
        check(svcRows.includes('CTcon UI GmbH') && svcRows.includes('Beta IT UI AG') && svcRows.includes(L('1 current of 1', '1 aktuell von 1')), 'each row its own prices' + where);
        check(await noOverflow(page), 'no horizontal overflow (services across providers)' + where);
        check((await page.locator('#skg-svc-network').getAttribute('href')).includes('group=SAP'), 'the network of all providers from the rows' + where);
        // package 6.2: the network of all providers of the name; its centre is the name only, each line names its provider's own service
        await page.locator('#skg-svc-network').click();
        await page.waitForSelector('#skg-net-svg[data-state="ready"] .skg-node');
        const gcentre = page.locator('#skg-net-svg .skg-node.skg-center');
        check(await gcentre.getAttribute('data-type') === 'service_group' && await gcentre.getAttribute('data-node') === 'service_group:sap'
          && (await gcentre.textContent()).includes('SAP'), 'a virtual centre for the name' + where);
        check(await page.locator('#skg-net-svg .skg-node:not(.skg-center)').count() === 2
          && await page.locator(`#skg-net-svg .skg-node[data-node="${ctcon}"]`).count() === 1, 'both providers of kgb, CTcon among them' + where);
        check(await page.locator('#skg-net-svg .skg-edge[data-type="offers"][data-to="service_group:sap"]').count() === 2, 'provider → offers → SAP, one line each' + where);
        check(!(await page.locator('#skg-net-svg').textContent()).includes('Gamma'), 'no provider of another collection' + where);
        check(await page.locator('#skg-depth').isHidden() && await page.locator('#skg-f-offers').isHidden() && await page.locator('#skg-f-current').isVisible(),
          'only the status filter applies' + where);
        check((await page.locator('#skg-network-title').textContent()).includes(L('Network of SAP · 2 providers', 'Netz von SAP · 2 Anbieter')), 'title of the name' + where);
        const gbox = await page.locator('#skg-net-svg').boundingBox();
        check(gbox.width <= width, 'the drawing fits the width: ' + JSON.stringify(gbox) + where);
        if (width < 640) check(await page.locator('#skg-net-svg').getAttribute('data-layout') === 'layered', 'layered on a narrow screen (name)' + where);
        check(await noOverflow(page), 'no horizontal overflow (network of a name)' + where);
        const gtable = await page.locator('#skg-net-table tbody').textContent();
        check(gtable.includes(L("this provider's own service", 'eigene Leistung dieses Anbieters')) && gtable.includes('CTcon UI GmbH') && gtable.includes('Beta IT UI AG'),
          "the list names each line's own service" + where);
        await page.locator(`#skg-net-svg .skg-edge[data-from="${ctcon}"]`).focus();
        await page.waitForSelector('#skg-net-detail:not([hidden])');
        const gline = await page.locator('#skg-net-detail').textContent();
        check(gline.includes(L("this provider's own service", 'eigene Leistung dieses Anbieters')) && gline.includes(L('1 current of 1', '1 aktuell von 1')),
          "a line: its provider's own service with its own prices" + where);
        check((await page.locator('#skg-net-detail .skg-detail-actions a').first().getAttribute('href')).includes('id=' + sap), "a line opens CTcon's own SAP" + where);
        await gcentre.focus();
        await page.waitForSelector('#skg-net-detail:not([hidden])');
        const gdetail = await page.locator('#skg-net-detail').textContent();
        check(gdetail.includes(L('All providers as a list', 'Alle Anbieter als Liste')) && await page.locator('#skg-net-detail .skg-detail-actions a').count() === 1,
          'the centre is no object: its counts and the list only' + where);
        if (shots) await page.screenshot({ path: path.join(shots, `kg-sap-group-network-${language}-${width}.png`), fullPage: true });
        check(await noIdAsName(page), 'no technical ID as a name in the network of a name' + where);
        await page.goto(base + '/ScoutroKnowledge_p.html?view=services&collection=kgb', { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-svc-groups tbody tr');
        check((await page.locator('#skg-svc-groups tbody').textContent()).includes('SAP'), 'the groups of every service name' + where);
        // an organisation without a stated name: its domain, marked, never its ID; in the list, the object view and the network
        await page.goto(base + '/ScoutroKnowledge_p.html?view=objects&type=organization&collection=kgb', { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-entities tbody tr');
        const orgRows = await page.locator('#skg-entities tbody').textContent();
        check(orgRows.includes('Zimmerei Boehmer Ui') && orgRows.includes(L('derived from the domain', 'aus Domain abgeleitet')), 'a nameless organisation by its domain, marked' + where);
        check(await noIdAsName(page), 'no technical ID as a name in the list' + where);
        await page.goto(base + `/ScoutroKnowledge_p.html?view=object&id=${unnamedOrg}&collection=kgb`, { waitUntil: 'networkidle' });
        await page.waitForFunction(() => document.querySelector('#skg-object-name').textContent.length > 0);
        check((await page.locator('#skg-object-name').textContent()).includes('Zimmerei Boehmer Ui'), 'object title from the domain' + where);
        check((await page.locator('#skg-object-facts').textContent()).includes(unnamedOrg), 'the ID only in its technical field' + where);
        check(await noIdAsName(page), 'no technical ID as a name in the object view' + where);
        await page.goto(base + `/ScoutroKnowledge_p.html?view=network&id=${unnamedOrg}&collection=kgb`, { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-net-svg[data-state="ready"] .skg-node');
        check((await page.locator('#skg-net-svg .skg-center').textContent()).includes('Zimmerei Boehmer Ui'), 'network centre from the domain' + where);
        check(await noIdAsName(page), 'no technical ID as a name in the network' + where);
        // the network of CTcon's SAP: the provider above it through the incoming offer, its parent company at depth 2
        await page.goto(base + `/ScoutroKnowledge_p.html?view=network&id=${sap}&collection=kgb`, { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-net-svg[data-state="ready"] .skg-node');
        check(await page.locator('#skg-depth').inputValue() === '2' && await page.locator('#skg-f-offers').isChecked() && !(await page.locator('#skg-f-industry').isChecked()),
          'a service in the centre: providers, depth 2, no industry by default' + where);
        const provider = page.locator(`#skg-net-svg .skg-node[data-node="${ctcon}"]`);
        check(await provider.count() === 1 && (await provider.textContent()).includes('ctcon-ui.de'), 'provider node with its domain' + where);
        const centre = page.locator('#skg-net-svg .skg-node.skg-center');
        check((await centre.textContent()).includes('SAP') && (await centre.textContent()).includes(L('Service', 'Leistung')), 'the service in the centre, marked as a service' + where);
        check(await page.locator(`#skg-net-svg .skg-edge[data-from="${ctcon}"][data-to="${sap}"][data-type="offers"]`).count() === 1, 'CTcon → offers → SAP' + where);
        check((await page.locator('#skg-net-svg .skg-elabel, #skg-net-svg .skg-caption').allTextContents()).includes(L('offers', 'bietet an')), 'the line says what it is' + where);
        check((await page.locator('#skg-net-svg').textContent()).includes('CT Holding UI AG') && !(await page.locator('#skg-net-svg').textContent()).includes('Cloud-Migration'),
          "the provider's parent, not its other services" + where);
        const box = await page.locator('#skg-net-svg').boundingBox();
        check(box.width <= width && box.height < 700, 'the drawing fits its content: ' + JSON.stringify(box) + where);
        if (width < 640) check(await page.locator('#skg-net-svg').getAttribute('data-layout') === 'layered', 'layered on a narrow screen' + where);
        check(await noOverflow(page), 'no horizontal overflow (service network)' + where);
        await provider.click();
        await page.waitForSelector('#skg-net-detail:not([hidden])');
        const detail = await page.locator('#skg-net-detail').textContent();
        check(detail.includes('CTcon UI GmbH') && detail.includes('ctcon-ui.de') && detail.includes('kgb') && detail.includes(L('offers', 'bietet an')),
          'detail: name, domain, collection and the relation to the centre' + where);
        check(await page.locator('#skg-net-detail .skg-detail-actions a').count() === 3, 'detail: open object, its network, its sources' + where);
        if (shots) await page.screenshot({ path: path.join(shots, `kg-sap-network-${language}-${width}.png`), fullPage: true });
        await page.locator('#skg-net-detail .skg-detail-actions a').nth(1).click();
        await page.waitForFunction(id => new URLSearchParams(location.search).get('id') === id && document.querySelector('#skg-net-svg').dataset.state === 'ready', ctcon);
        check(await page.locator('#skg-depth').inputValue() === '1' && (await page.locator('#skg-net-svg').textContent()).includes('Cloud-Migration'),
          "the provider's own network with all its services" + where);
        await page.goto(base + `/ScoutroKnowledge_p.html?view=network&id=${sap}&collection=kgb&list=1`, { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-net-table tbody tr');
        check(await page.locator('#skg-net-wrap').isHidden() && (await page.locator('#skg-net-table').textContent()).includes('CTcon UI GmbH'), 'the list instead of the drawing' + where);

        // comparison: one category across providers, prices exactly as published
        await page.goto(base + '/ScoutroKnowledge_p.html?view=compare', { waitUntil: 'networkidle' });
        await page.waitForFunction(() => document.querySelectorAll('#skg-compare-category option').length > 1);
        await page.locator('#skg-compare-category').selectOption('care/tagespflege');
        await page.locator('#skg-compare-form button[type=submit]').click();
        await page.waitForSelector('#skg-compare-table tbody tr');
        const table = await page.locator('#skg-compare-table').textContent();
        check(await page.locator('#skg-compare-table tbody tr').count() === 2 && table.includes('Lindenhof Pflege gGmbH') && table.includes(L('from 49 EUR', 'ab 49 EUR')),
          'comparison rows' + where);
        check(await noOverflow(page), 'no horizontal overflow (comparison)' + where);
        if (width < 768) check(await page.locator('#skg-compare-table tbody tr').first().evaluate(r => getComputedStyle(r).display !== 'table-row'), 'comparison rows as cards' + where);
        // entity filters from the facets
        await page.goto(base + '/ScoutroKnowledge_p.html?view=objects&category=care/tagespflege', { waitUntil: 'networkidle' });
        await page.waitForSelector('#skg-entities tbody tr');
        const filtered = await page.locator('#skg-entities tbody').textContent();
        check(filtered.includes('Lindenhof Pflege gGmbH') && !filtered.includes('Nur Bee GmbH') && await page.locator('#skg-category').inputValue() === 'care/tagespflege',
          'category filter' + where);

        // settings
        await page.goto(base + '/ScoutroKnowledge_p.html?view=settings', { waitUntil: 'networkidle' });
        await page.waitForFunction(() => document.querySelector('#skg-config').children.length > 0);
        check((await page.locator('#skg-config').textContent()).includes('kga'), 'settings show the LLM collection' + where);
        // package 6.2: the knowledge graph settings of every collection of the catalog
        await page.waitForSelector('#skg-kgc tbody tr[data-collection]');
        const kgcRows = await page.locator('#skg-kgc tbody tr[data-collection]').evaluateAll(rs => rs.map(r => r.dataset.collection));
        check(['kga', 'kgb', 'kgc'].every(c => kgcRows.includes(c)) && !kgcRows.some(c => c.startsWith('robot_')), 'every collection, never robot_*: ' + kgcRows + where);
        const kgaRow = page.locator('#skg-kgc tbody tr[data-collection="kga"]');
        check(await kgaRow.locator('select').first().inputValue() === 'on' && await kgaRow.locator('select').nth(1).inputValue() === 'care'
          && await kgaRow.locator('button').isDisabled(), 'kga on, vocabulary care, nothing to save' + where);
        check(await page.locator('#skg-kgc tbody tr[data-collection="kgc"] select').nth(1).inputValue() === '__default', 'kgc: the default (none)' + where);
        // package 6.3: the LLM enrichment per collection, with the model of the LLM selection
        check((await page.locator('#skg-kgc-model').textContent()).includes('fixture'), 'the model of the LLM selection in the settings' + where);
        check(await page.locator('#skg-kgc-llm-state').textContent() === lLlm + ': ' + lOn, 'LLM enrichment active in the settings' + where);
        check(await kgaRow.locator('select').nth(2).inputValue() === 'on' && await kgaRow.locator('select').nth(2).isEnabled()
          && await page.locator('#skg-kgc tbody tr[data-collection="kgb"] select').nth(2).inputValue() === 'off', 'LLM per collection: kga on, kgb off' + where);
        check(await noOverflow(page), 'no horizontal overflow (settings)' + where);
        if (shots) await page.screenshot({ path: path.join(shots, `kg-settings-${language}-${width}.png`), fullPage: true });
        check(errors.length === 0, 'no JavaScript errors: ' + errors.join(', ') + where);
      } finally { await context.close(); }
    }
  }

  // integrations: SEO tab, Index Browser links, dashboard tile, controls
  const context = await browser.newContext({ locale: 'en', viewport: { width: 1280, height: 900 }, httpCredentials: { username: 'admin', password: 'yacy' } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  // package 6.2: switch kgc off and on again through the settings; its graph data stay, switching on reads its pages again
  {
    const api = async p => page.evaluate(async u => { const r = await fetch(u, { credentials: 'same-origin', cache: 'no-store' }); return { status: r.status, body: await r.json() }; }, p);
    await page.goto(base + '/ScoutroKnowledge_p.html?view=settings', { waitUntil: 'networkidle' });
    const before = (await api('/scoutro/api/v1/kg/entities?collection=kgc&limit=100')).body.total;
    check(before >= 1, 'kgc has objects: ' + before);
    page.once('dialog', d => d.accept());
    const row = () => page.locator('#skg-kgc tbody tr[data-collection="kgc"]');
    await row().locator('select').first().selectOption('off');
    await row().locator('button').click();
    await page.waitForFunction(() => document.querySelector('#skg-kgc-message').textContent.includes('reopened'), null, { timeout: 60000 });
    check(await row().getAttribute('data-state') === 'inactive', 'kgc switched off through the page');
    const kept = await api('/scoutro/api/v1/kg/entities?collection=kgc&limit=100');
    check(kept.status === 200 && kept.body.total === before, 'switched off: its graph data are kept (' + kept.body.total + ')');
    const settingsOff = (await api('/scoutro/api/v1/kg/collections')).body;
    const offRow = settingsOff.collections.find(r => r.collection === 'kgc');
    check(offRow.inactive && !offRow.active && settingsOff.collections.find(r => r.collection === 'kga').active, 'only kgc changed: ' + JSON.stringify(offRow));
    await row().locator('select').first().selectOption('on');
    await row().locator('button').click();
    await page.waitForFunction(() => document.querySelector('#skg-kgc-message').textContent.includes('read now'), null, { timeout: 60000 });
    check(['following', 'waiting'].includes(await row().getAttribute('data-state')), 'kgc switched on again, its pages read through the start reconcile');
    const back = await api('/scoutro/api/v1/kg/entities?collection=kgc&limit=100');
    check(back.status === 200 && back.body.total === before, 'switched on: the same objects (' + back.body.total + ')');
    await page.waitForFunction(async () => (await (await fetch('/scoutro/api/v1/kg/status', { credentials: 'same-origin', cache: 'no-store' })).json()).state === 'running', null, { timeout: 60000 });
    // package 6.3: the LLM enrichment of kgb switched on through the page (after a question naming the model) and off again
    await page.goto(base + '/ScoutroKnowledge_p.html?view=settings', { waitUntil: 'networkidle' });
    await page.waitForSelector('#skg-kgc tbody tr[data-collection="kgb"]');
    const kgbRow = () => page.locator('#skg-kgc tbody tr[data-collection="kgb"]');
    let asked = '';
    page.once('dialog', d => { asked = d.message(); d.accept(); });
    await kgbRow().locator('select').nth(2).selectOption('on');
    await kgbRow().locator('button').click();
    await page.waitForFunction(() => document.querySelector('#skg-kgc-message').textContent.includes('reopened'), null, { timeout: 60000 });
    check(asked.includes('fixture') && asked.includes('kgb'), 'switching the LLM on asks first and names the model: ' + asked);
    const llmOn = (await api('/scoutro/api/v1/kg/collections')).body;
    check(llmOn.collections.find(r => r.collection === 'kgb').llm && llmOn.collections.find(r => r.collection === 'kga').llm
      && llmOn.collections.find(r => r.collection === 'kgb').llmActive && llmOn.llm.active && /\/fixture$/.test(llmOn.llm.model), 'kgb LLM on, kga unchanged: ' + JSON.stringify(llmOn.llm));
    check(await kgbRow().locator('select').nth(2).inputValue() === 'on', 'the row shows the LLM enrichment on');
    await page.waitForFunction(async () => (await (await fetch('/scoutro/api/v1/kg/status', { credentials: 'same-origin', cache: 'no-store' })).json()).state === 'running', null, { timeout: 60000 });
    await kgbRow().locator('select').nth(2).selectOption('off');
    await kgbRow().locator('button').click();
    await page.waitForFunction(() => document.querySelector('#skg-kgc-message').textContent.includes('reopened'), null, { timeout: 60000 });
    const llmOff = (await api('/scoutro/api/v1/kg/collections')).body;
    check(!llmOff.collections.find(r => r.collection === 'kgb').llm && llmOff.collections.find(r => r.collection === 'kga').llm && llmOff.llm.active,
      'kgb LLM off again, kga kept: ' + JSON.stringify(llmOff.llm));
    await page.waitForFunction(async () => (await (await fetch('/scoutro/api/v1/kg/status', { credentials: 'same-origin', cache: 'no-store' })).json()).state === 'running', null, { timeout: 60000 });
  }
  await page.goto(base + `/ScoutroSEO_p.html?host=${host}&collection=kga`, { waitUntil: 'networkidle' });
  await page.waitForSelector('#sseo-analysis:not([hidden])');
  await page.locator('#sseo-tab-knowledge').click();
  await page.waitForSelector('#sseo-kg-body table');
  const seo = await page.locator('#sseo-kg-body').textContent();
  check(seo.includes('Muster Pflege gGmbH') && !seo.includes('Nur Bee'), 'SEO knowledge tab of the host' + ' (kga)');
  check((await page.locator('#sseo-kg-open').getAttribute('href')).includes('collection=kga'), 'SEO link keeps the collection');
  await page.goto(base + '/IndexBrowser_p.html', { waitUntil: 'networkidle' });
  await page.waitForSelector('.scoutro-domain');
  check(await page.locator('.scoutro-domain a[href*="ScoutroKnowledge_p.html?view=objects"]').count() >= 2, 'Index Browser domain cards link to the graph');
  await page.goto(base + '/IndexBrowser_p.html?view=urls&q=' + host, { waitUntil: 'networkidle' });
  await page.waitForSelector('#scoutro-index-table tbody tr');
  check(await page.locator('#scoutro-index-table a[href*="ScoutroKnowledge_p.html?view=source&doc="]').count() >= 1, 'Index Browser URL rows link to the source view');
  await page.goto(base + '/scoutro-dashboard.html', { waitUntil: 'networkidle' });
  await page.waitForFunction(() => document.querySelector('#scoutro-kg-state').textContent !== '—');
  check((await page.locator('#scoutro-kg-state').textContent()).trim() === 'Running', 'dashboard tile');
  await page.goto(base + '/ScoutroKnowledge_p.html', { waitUntil: 'networkidle' });
  await page.locator('[data-skg-action="derive"]').click();
  await page.waitForFunction(() => document.querySelector('#skg-message').textContent === 'Done.');
  check(true, 'derived relations recomputed on request');
  await page.locator('[data-skg-action="pause"]').click();
  await page.waitForFunction(() => document.querySelector('#skg-storage').textContent.includes('manual'));
  check(true, 'pause through the page');
  await page.locator('[data-skg-action="resume"]').click();
  await page.waitForFunction(() => !document.querySelector('#skg-storage').textContent.includes('manual'));
  check(true, 'resume through the page');
  // backups: create through the page, listed with a download of the SQLite file, restore with a confirmation
  page.on('dialog', d => d.accept());
  await page.locator('[data-skg-action="backup"]').click();
  await page.waitForFunction(() => document.querySelector('#skg-backups table tbody tr') !== null, null, { timeout: 60000 });
  const file = (await page.locator('#skg-backups tbody tr td').first().textContent()).trim();
  check(/^graph-\d{8}T\d{6}Z\.db$/.test(file), 'backup listed: ' + file);
  const href = await page.locator('#skg-backups tbody tr a').first().getAttribute('href');
  // through the page's own (Digest-authenticated) session
  const download = await page.evaluate(async h => {
    const r = await fetch(h, { credentials: 'same-origin' });
    const bytes = new Uint8Array(await r.arrayBuffer());
    return { status: r.status, head: String.fromCharCode(...bytes.slice(0, 15)), disposition: r.headers.get('content-disposition') || '',
      type: r.headers.get('content-type') || '', size: bytes.length };
  }, href);
  check(download.status === 200 && download.head === 'SQLite format 3' && download.disposition.includes(file)
    && download.type.startsWith('application/vnd.sqlite3') && download.size > 4096, 'backup download: ' + JSON.stringify(download));
  check((await browser.newContext().then(async c => { const r = await c.request.get(base + '/scoutro/api/v1/kg/backups/' + file); await c.close(); return r.status(); })) === 401,
    'backup download needs the administrator');
  await page.locator(`[data-skg-restore="${file}"]`).click();
  await page.waitForFunction(() => document.querySelector('#skg-message').textContent.length > 0 && !document.querySelector('#skg-message').textContent.includes('…'), null, { timeout: 60000 });
  await page.waitForFunction(() => [...document.querySelectorAll('#skg-backups tbody tr')].some(r => r.textContent.includes('before-restore')), null, { timeout: 60000 });
  check(true, 'restore through the page keeps the previous graph as a backup');
  check((await page.locator('#skg-cards').textContent()).includes('running'), 'running after the restore');
  // identity rebuild: started with a confirmation, the panel refreshes itself until the swap, the previous graph is kept
  check(await page.locator('[data-skg-action="rebuild_cancel"]').isHidden() && await page.locator('[data-skg-action="rebuild_confirm"]').isHidden(),
    'no cancel or confirm without a rebuild');
  await page.locator('[data-skg-action="rebuild"]').click();
  await page.waitForFunction(() => document.querySelector('#skg-rebuild').textContent.includes('finished'), null, { timeout: 240000 });
  check(true, 'rebuild through the page, refreshed until finished');
  check(/graph-\d{8}T\d{6}Z-before-rebuild\.db/.test(await page.locator('#skg-rebuild').textContent()), 'the previous graph is named');
  await page.waitForFunction(() => [...document.querySelectorAll('#skg-backups tbody tr')].some(r => r.textContent.includes('before a rebuild')), null, { timeout: 60000 });
  check(true, 'the previous graph is listed as a backup before a rebuild');
  check(await page.locator('[data-skg-action="rebuild"]').isVisible() && await page.locator('[data-skg-action="rebuild_cancel"]').isHidden(),
    'rebuild available again, nothing to cancel');
  const rebuilt = await page.evaluate(async e => (await fetch('/scoutro/api/v1/kg/entities/' + e, { credentials: 'same-origin' })).status, entity);
  check(rebuilt === 200, 'the entity ID still resolves after the rebuild: ' + rebuilt);
  // package 6.3: delete a backup through the page. The question names file, type, size and date; cancel keeps it;
  // the list and the backup space follow; download and restore of the others stay; nothing but a backup name is accepted
  {
    page.removeAllListeners('dialog');
    const status = () => page.evaluate(async () => (await (await fetch('/scoutro/api/v1/kg/status', { credentials: 'same-origin', cache: 'no-store' })).json()));
    const control = body => page.evaluate(async b => (await fetch('/scoutro/api/v1/kg/control', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) })).status, body);
    await page.goto(base + '/ScoutroKnowledge_p.html', { waitUntil: 'networkidle' });
    await page.waitForSelector('#skg-backups [data-skg-delete]');
    const victim = await page.evaluate(() => [...document.querySelectorAll('#skg-backups [data-skg-delete]')].map(b => b.dataset.skgDelete).find(f => /-before-restore\.db$/.test(f)));
    check(/^graph-\d{8}T\d{6}Z-before-restore\.db$/.test(victim || ''), 'every backup has Delete: ' + victim);
    const before = (await status()).backup;
    const cells = await page.locator('#skg-backups tbody tr', { hasText: victim }).locator('td').allTextContents();
    const kind = await page.evaluate(() => document.querySelector('[data-skg-label="kind_before_restore"]').textContent);
    let question = '';
    page.once('dialog', d => { question = d.message(); d.dismiss(); });
    await page.locator(`[data-skg-delete="${victim}"]`).click();
    check(question.includes(victim) && question.includes(kind) && question.includes(cells[2].trim()) && question.includes(cells[3].trim()),
      'the question names file, type, size and date: ' + question + ' / ' + JSON.stringify(cells));
    await page.waitForTimeout(300);
    check(await page.locator(`[data-skg-delete="${victim}"]`).count() === 1 && (await status()).backup.files === before.files, 'cancel deletes nothing');
    page.once('dialog', d => d.accept());
    await page.locator(`[data-skg-delete="${victim}"]`).click();
    await page.waitForFunction(v => !document.querySelector(`[data-skg-delete="${v}"]`) && document.querySelector('#skg-message').textContent.includes(v), victim, { timeout: 60000 });
    const after = (await status()).backup;
    check(after.files === before.files - 1 && after.bytes < before.bytes, 'count and space of the backups follow: ' + JSON.stringify([before.files, before.bytes, after.files, after.bytes]));
    check((await page.locator('#skg-backup').textContent()).includes(String(after.files) + ' · '), 'the backup files row is updated');
    check((await status()).state === 'running' && await page.evaluate(async e => (await fetch('/scoutro/api/v1/kg/entities/' + e, { credentials: 'same-origin' })).status, entity) === 200,
      'the live graph is untouched');
    check(await page.locator(`[data-skg-restore="${file}"]`).count() === 1 && await page.evaluate(async f => (await fetch('/scoutro/api/v1/kg/backups/' + f, { credentials: 'same-origin' })).status, file) === 200,
      'download and restore of the other backups unchanged');
    check(await control({ action: 'restore', backup: victim }) === 404, 'a deleted backup cannot be restored');
    const refused = [];
    for (const b of ['graph.db', '../graph.db', 'graph.db-wal', 'graph.db-shm', '/etc/passwd', 'graph-20260101T000000Z.db/..', victim])
      refused.push(await control({ action: 'delete_backup', backup: b }));
    check(JSON.stringify(refused) === '[400,400,400,400,400,400,404]', 'only the name of an existing backup: ' + JSON.stringify(refused));
    const anon = await browser.newContext();
    const anonStatus = (await anon.request.post(base + '/scoutro/api/v1/kg/control', { data: { action: 'delete_backup', backup: file } })).status();
    await anon.close();
    check(anonStatus === 401 && (await status()).backup.files === after.files, 'deleting needs the administrator: ' + anonStatus);
  }
  check(errors.length === 0, 'no JavaScript errors in the integrations: ' + errors.join(', '));
  await context.close();
} finally { await browser.close(); }
console.log(`PASS: ${checks} knowledge graph UI checks (English and German, five widths, collection isolation, business view, network, services across providers, service network, network of a name, collections, collection settings on/off, comparison, filters, SEO tab, Index Browser, dashboard, controls, backup, restore, rebuild, LLM layers and LLM per collection, backup delete)`);
