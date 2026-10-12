#!/usr/bin/env node
/* GPL-2.0-or-later. Job policies and software hints of the knowledge graph page (ScoutroKnowledge_p.html) with the real
   localized HTML/JS and controlled API fixtures: four independent job policies per collection with their explanation, the
   software-hint switch sent on its own and its targeted re-check reported, and the company view's "Software and systems"
   with context and source, also where job objects are not shown. Phone and desktop, EN/DE. Names are examples. */
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
const row = signals => ({ collection: 'alpha-care', name: 'alpha-care', inCatalog: true, indexDocuments: 120, active: true, inactive: false, followedBy: 'list',
  vocabulary: 'care', vocabularySetting: 'care', defaultVocabulary: null, vocabularySource: 'setting', vocabularyKnown: true, graphDocuments: 100,
  state: 'following', llm: false, llmBy: 'none', llmActive: false, jobsExtraction: false, jobsSignals: signals, jobsDisplay: false, jobsMatching: false });
const STATUS = { enabled: true, state: 'running', config: { valid: true, collections: ['alpha-care'] }, storage: {}, sync: { queue: { items: 0 }, reconcile: { pending: false, jobRecheck: ['alpha-care'], last: { state: 'completed' } } },
  llm: { state: 'off', timing: {} }, collections: [{ collection: 'alpha-care', followed: true, vocabulary: 'care', jobs: false, jobSignals: true, llm: false,
    documents: 100, state: 'following' }], rebuild: { phase: 'none' }, events: [] };
const PLAN = { mode: 'automatic', days: [1, 2, 3, 4, 5, 6, 7], from: '00:00', until: '00:00', zone: 'UTC', minStartSeconds: 0 };
const SYSTEMS = [
  { statement: 'kgs_' + 'b'.repeat(20), product: 'sap-s4hana', context: 'internal_use', source: 'job_ad', status: 'current', sources: 1, kinds: ['jsonld'] },
  { statement: 'kgs_' + 'c'.repeat(20), product: 'revit', context: 'desirable_competence', source: 'job_ad', status: 'current', sources: 1, kinds: ['jsonld'] },
  { statement: 'kgs_' + 'd'.repeat(20), product: 'archicad', context: 'customer_projects', source: 'references', status: 'current', sources: 1, kinds: ['rule'] },
  { statement: 'kgs_' + 'e'.repeat(20), product: 'salesforce', context: 'internal_use', source: 'company', status: 'uncertain', sources: 1, kinds: ['rule'] }];

for (const language of ['en', 'de']) for (const width of [390, 1280]) {
  const context = await browser.newContext({ locale: language, viewport: { width, height: 900 } });
  const page = await context.newPage(), errors = [], patches = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.accept());
  let signals = true;
  await page.route(base + '/**', async route => {
    const u = new URL(route.request().url()), method = route.request().method();
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
    if (u.pathname.endsWith('/kg/status')) return route.fulfill({ json: STATUS });
    if (u.pathname.endsWith('/kg/llm-schedule')) return route.fulfill({ json: { plan: PLAN, valid: true, validationError: null } });
    if (u.pathname.endsWith('/kg/collections/alpha-care') && method === 'PATCH') {
      const body = JSON.parse(route.request().postData()); patches.push(body);
      if ('jobsSignals' in body) signals = body.jobsSignals;
      return route.fulfill({ json: { collection: row(signals), changed: true, applied: true, backfill: false, kept: false, reextract: false, recheckJobs: true, keys: [] } });
    }
    if (u.pathname.endsWith('/kg/collections')) return route.fulfill({ json: { collections: [row(signals)], vocabularies: ['care', 'software'], llm: { model: null } } });
    if (u.pathname.endsWith('/kg/entities/' + ORG)) return route.fulfill({ json: { id: ORG, type: 'organization', name: 'Muster Handel GmbH', quality: 'supported', counts: {} } });
    if (u.pathname.endsWith('/kg/entities/' + ORG + '/business')) return route.fulfill({ json: { schema: 1, systems: SYSTEMS, sources: [] } });
    if (u.pathname.includes('/kg/entities/' + ORG + '/statements')) return route.fulfill({ json: { total: 0, items: [] } });
    return route.fulfill({ status: 404, json: { error: { code: 'not_found' } } });
  });
  const L = key => page.evaluate(k => document.querySelector(`[data-skg-label="${k}"]`).textContent, key);
  const where = ` (${language}, ${width})`;
  const noOverflow = () => page.evaluate(() => document.scrollingElement.scrollWidth <= window.innerWidth + 1);

  // settings: four independent policies, each explained
  await page.goto(base + '/ScoutroKnowledge_p.html?view=settings', { waitUntil: 'networkidle' });
  await page.waitForSelector('#skg-kgc tbody tr[data-collection="alpha-care"]');
  const boxes = page.locator('#skg-kgc tbody tr[data-collection="alpha-care"] input[type=checkbox]');
  check(await boxes.count() === 4, 'four job policies per collection' + where);
  const fields = await boxes.evaluateAll(b => b.map(x => x.dataset.field + '=' + x.checked));
  check(JSON.stringify(fields) === JSON.stringify(['jobsExtraction=false', 'jobsSignals=true', 'jobsDisplay=false', 'jobsMatching=false']),
    'stored state shown, software hints on by default: ' + fields + where);
  const cellText = await page.locator('#skg-kgc tbody tr[data-collection="alpha-care"]').innerText();
  for (const k of ['jobs_extraction', 'jobs_signals', 'jobs_display', 'jobs_matching']) check(cellText.includes(await L(k)), 'label ' + k + where);
  const help = await page.locator('.skg-job-policies').innerText();
  check(help.includes(await L('jobs_signals')) && help.includes(await L('jobs_display')) && (language === 'de' ? help.includes('nie ein Nachweis eigener Nutzung')
    : help.includes('never a proof of own use')), 'requirement is no proof of use, explained' + where);
  check(language === 'de' ? help.includes('liest nur ihre Stellenseiten neu') : help.includes('reads only its job pages again'), 'targeted re-check explained' + where);
  // switching the hints off sends only that field and reports the targeted check
  await boxes.nth(1).uncheck();
  await page.locator('#skg-kgc tbody tr[data-collection="alpha-care"] button').click();
  await page.waitForFunction(() => document.querySelector('#skg-kgc-message').textContent.length > 20);
  check(patches.length === 1 && JSON.stringify(patches[0]) === '{"jobsSignals":false}', 'only the changed policy is sent: ' + JSON.stringify(patches) + where);
  check((await page.locator('#skg-kgc-message').textContent()).includes(await L('kgc_recheck_jobs')), 'targeted re-check reported' + where);
  check(!(await page.locator('#skg-kgc-message').textContent()).includes(await L('kgc_reextract')), 'no full re-extraction reported' + where);
  check(await noOverflow(), 'settings without horizontal scrolling' + where);

  // the overview's collection table names both
  await page.goto(base + '/ScoutroKnowledge_p.html', { waitUntil: 'networkidle' });
  await page.waitForSelector('#skg-collections tbody tr');
  const overview = await page.locator('#skg-collections tbody tr').first().innerText();
  check(overview.includes((await L('jobs_cell')).replace('%1', await L('no')).replace('%2', await L('yes'))), 'job objects shown and software hints apart' + where);
  const sync = await page.locator('#skg-sync').textContent();
  check(sync.includes(await L('job_recheck')) && sync.includes('alpha-care'), 'the targeted re-check is visible in the diagnosis' + where);

  // company view: software and systems with context and source, no job object needed
  await page.goto(base + '/ScoutroKnowledge_p.html?view=object&id=' + ORG, { waitUntil: 'networkidle' });
  await page.waitForSelector('#skg-sec-systems');
  check((await page.locator('#skg-sec-systems h3').textContent()) === await L('sec_systems'), 'section title' + where);
  const items = page.locator('#skg-sec-systems li.skg-statement');
  check(await items.count() === 4, 'all hints listed' + where);
  const sap = await items.nth(0).innerText();
  check(sap.includes('sap-s4hana') && sap.includes(await L('ctx_internal_use')) && sap.includes(await L('src_job_ad')), 'internal use from an advertisement' + where);
  const revit = await items.nth(1).innerText();
  check(revit.includes(await L('ctx_desirable_competence')) && revit.includes(await L('src_job_ad')), 'a requirement stays a requirement' + where);
  check((await items.nth(2).innerText()).includes(await L('ctx_customer_projects')) && (await items.nth(2).innerText()).includes(await L('src_references')),
    'client work from a reference page' + where);
  check((await page.locator('#skg-sec-systems').innerText()).includes(await L('systems_note')), 'the note says a requirement is no proof' + where);
  check(await items.nth(0).getAttribute('data-source') === 'job_ad' && await items.nth(0).getAttribute('data-context') === 'internal_use', 'data attributes' + where);
  check(!(await page.locator('#skg-business').innerText()).includes(await L('sec_jobs')), 'no jobs section where job objects are not shown' + where);
  check(await noOverflow(), 'company view without horizontal scrolling' + where);
  check(errors.length === 0, 'no page errors: ' + errors.join('; ') + where);
  await context.close();
}
await browser.close();
console.log(`PASS: ${checks} job policy and software hint UI checks (EN/DE, phone and desktop)`);
