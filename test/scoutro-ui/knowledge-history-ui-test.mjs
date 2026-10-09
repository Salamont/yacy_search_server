#!/usr/bin/env node
/* GPL-2.0-or-later. Real UI with offline API fixtures; no peer, crawl or LLM. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const { chromium } = createRequire(import.meta.url)('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const base = 'http://127.0.0.1:8090', id = 'kgo_' + 'a'.repeat(20), actor = 'kge_' + 'a'.repeat(20);
const observation = { schema: 'scoutro.kg.history.v1', id, subject: actor, subject_type: 'job', organization: actor,
  predicate: 'system_signal', value: { product: 'sap-s4hana', context: 'internal_use' }, quote: 'Wir nutzen SAP S/4HANA intern. <img src=x onerror=alert(1)>',
  identity_context: { organization: [{ predicate: 'name', value: 'Industry GmbH' }], employer_assignment: 'source_declared' },
  observed_at: '2020-10-09T10:00:00Z', recorded_at: '2026-10-09T10:00:00Z', asserted_at: '2020',
  source: { status: 'removed', id: 'AAAAAAhost01', url: 'https://industry.example/jobs', revision: 'old-content:2020' },
  locator: '/description', extractor: 'jsonld/4', vocabulary_version: '3/products-1',
  assertion_status: 'recorded', certainty: 'stated', live_statement: null, collections: ['kga'],
  job_search: { status: 'unknown', position_filled: 'unknown' } };
const competence = { ...observation, id: 'kgo_' + 'b'.repeat(20), value: { product: 'revit', context: 'desirable_competence' },
  observed_at: null, quote: 'Revit Kenntnisse wünschenswert.' };
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] });
let checks = 0; const check = (value, message) => { assert(value, message); checks++; };
try {
  for (const [language, width] of [['en', 1280], ['de', 390]]) {
    const context = await browser.newContext({ locale: language, viewport: { width, height: 900 } });
    const page = await context.newPage(), requests = [], errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.route(base + '/**', async route => {
      const url = new URL(route.request().url());
      if (url.pathname === '/ScoutroKnowledge_p.html') {
        let html = fs.readFileSync(path.join(root, 'htroot/ScoutroKnowledge_p.html'), 'utf8').replace(/#%[^\n]*?%#/g, '');
        if (language === 'de') {
          html = html.replace('lang="en"', 'lang="de"');
          const section = fs.readFileSync(path.join(root, 'locales/de.lng'), 'utf8').split('#File: ScoutroKnowledge_p.html\n')[1].split('#File: ')[0];
          const entries = section.split('\n').filter(l => l.includes('==') && !l.startsWith('#')).map(l => l.split('=='));
          for (const [key, value] of entries.sort((a,b) => b[0].length - a[0].length)) html = html.split(key).join(value);
        }
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
      }
      if (url.pathname === '/env/scoutro/collections.js') return route.fulfill({ contentType: 'application/javascript', body:
        "window.ScoutroCollections={load:async()=>['kga'],fill:(el,ids)=>{for(const id of ids){const o=document.createElement('option');o.value=id;o.textContent=id;el.append(o);}el.disabled=false;}};" });
      if (url.pathname.startsWith('/env/scoutro/')) return route.fulfill({ contentType: url.pathname.endsWith('.css') ? 'text/css' : 'application/javascript',
        body: fs.readFileSync(path.join(root, 'htroot', url.pathname.slice(1)), 'utf8') });
      if (!url.pathname.startsWith('/scoutro/api/v1/kg/')) return route.fulfill({ status:404, body:'' });
      requests.push(url); const resource = url.pathname.slice('/scoutro/api/v1/kg/'.length);
      let data;
      if (resource === 'history' || resource.endsWith('/history') && resource.startsWith('entities/'))
        data = { schema: observation.schema, items: url.searchParams.get('after') === '1' ? [competence] : [observation], has_more: url.searchParams.get('after') !== '1', next_after: 1 };
      else if (resource === 'observations/' + id) data = observation;
      else if (resource === 'observations/' + id + '/history') data = { items: [{ id: 'kgh_'+'a'.repeat(20), observation: id, kind: url.searchParams.get('after') === '2' ? 'state' : 'scope_delete', at: observation.recorded_at }],
        has_more: url.searchParams.get('after') !== '2', next_after: 2 };
      else if (resource.startsWith('entities/')) return route.fulfill({ status:404, contentType:'application/json', body: JSON.stringify({error:{code:'not_found'}}) });
      else data = {};
      return route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
    });
    await page.goto(base + '/ScoutroKnowledge_p.html?view=history&collection=kga');
    await page.locator('#skg-history-items article').waitFor();
    const item = page.locator('#skg-history-items article').first(), text = await item.textContent();
    check(text.includes('2020') && text.includes('2026'), 'source and processing dates remain distinguishable');
    check(text.includes(language === 'de' ? 'heutiger Einsatz nicht erneut bestätigt' : 'present use has not been reconfirmed'), 'historical internal use is qualified: ' + text);
    check(text.includes(language === 'de' ? 'aktueller Suchstatus unbekannt' : 'current search status unknown'), 'source removal does not end the vacancy');
    check(await item.locator('img').count() === 0, 'quotation is text, not executable markup');
    check(await item.locator('button').count() === 0, 'archived quote does not need a live statement');
    const download = new URL(await page.locator('#skg-history-download').getAttribute('href'), base);
    check(download.searchParams.get('include') === 'history' && download.searchParams.get('collection') === 'kga', 'complete history download keeps view context');
    await page.locator('#skg-history-more').click(); await page.waitForFunction(() => document.querySelectorAll('#skg-history-items article').length === 2);
    check(requests.some(u => u.pathname.endsWith('/history') && u.searchParams.get('after') === '1'), 'list is keyset paginated');
    const second = await page.locator('#skg-history-items article').nth(1).textContent();
    check(second.includes(language === 'de' ? 'erwünschte Kenntnisse' : 'desirable competence'), 'competence retains its context');
    check(second.includes(language === 'de' ? 'Beobachtungsdatum unbekannt' : 'observation date unknown'), 'missing date is not replaced by processing date');
    await item.locator('a').first().click(); await page.locator('#skg-history-items > div > button').waitFor();
    await page.locator('#skg-history-items > div > button').click();
    check(requests.some(u => u.pathname.includes('/observations/') && u.searchParams.get('after') === '2'), 'audit events are paginated');
    await page.goto(base + '/ScoutroKnowledge_p.html?view=object&id=' + actor + '&collection=kga');
    await page.waitForFunction(() => new URL(location.href).searchParams.get('view') === 'history');
    await page.locator('#skg-history-items article').waitFor();
    check(new URL(page.url()).searchParams.get('entity') === actor, 'deleted object navigation reaches its archive');
    check(errors.length === 0, 'no browser errors: ' + errors.join(', '));
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'no overflow on narrow display');
    await context.close();
  }
} finally { await browser.close(); }
console.log('PASS: ' + checks + ' offline history UI checks (EN/DE, dates/status, navigation, pagination, export)');
