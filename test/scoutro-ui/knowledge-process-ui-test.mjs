#!/usr/bin/env node
/* GPL-2.0-or-later. The process display of the knowledge graph status (ScoutroKnowledge_p.html, overview) with the
   real localized HTML/JS and controlled status fixtures: running work, empty queues, waiting reasons, errors and
   missing data; units and time bases, keyboard, phone layout and that the display only ever reads. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const base = 'http://127.0.0.1:8090';
const shots = process.argv.includes('--screenshots') ? process.argv[process.argv.indexOf('--screenshots') + 1] : process.env.SCOUTRO_SCREENSHOTS;
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] });
let checks = 0;
const check = (ok, why) => { assert(ok, why); checks++; };
const NOW = Date.now();

const collections = [{ collection: 'kga', followed: true, documents: 41857 }, { collection: 'kgb', followed: true, documents: 2767 },
  { collection: 'kgc', followed: false, documents: 0 }];
const busy = () => ({ enabled: true, state: 'running', config: { valid: true, collections: ['kga', 'kgb'] },
  storage: { usedBytes: 1.71 * 1073741824, budgetBytes: 10737418240, usedPercent: 17, level: 'ok', growthAllowed: true, reasons: [] },
  jsonld: { state: 'active', capture: { capturedDocs: 4321 } },
  sync: { state: 'running', queue: { items: 1100 }, processed: { published: 119567 },
    lag: { pending: 1200, oldest_pending_age_s: 42, byType: { new: 700, update: 300, delete: 20, reconcile: 80, captured: 100 } },
    scheduler: { state: 'running', lastFinishedAt: NOW - 30000 }, reconcile: { pending: false, awaitingConfirmation: false, last: { state: 'completed', startedAt: NOW - 86400000 } } },
  llm: { state: 'running', enabled: true, model: 'ollama/qwen3:14b', queue: { items: 181, claimed: 1 },
    documents: { done: 3093, failed: 1, skipped: 8988 }, breaker: { open: false },
    processed: { calls: 188, averageCallMillis: 41234 }, timing: { waitReason: 'in_flight', runningRequests: 1, windowOpen: true } },
  derived: { enabled: true, intervalMinutes: 60, lastRun: NOW - 1800000, last: { byKind: { suggested_customer: 12430, suggested_partner: 71, same_operator: 18, linked_to: 381 }, durationMs: 9100 },
    matching: { checked: 50000, completed_partitions: 89, cycle_complete: false, deferred: 'work_budget' } },
  collections, backup: { state: 'idle', files: 0, bytes: 0 }, rebuild: { phase: 'none' }, events: [] });
const SCENARIOS = {
  busy,
  empty: () => { const s = busy(); s.sync.queue.items = 0; s.sync.lag = { pending: 0, oldest_pending_age_s: 0, byType: { new: 0, update: 0, delete: 0, reconcile: 0, captured: 0 } };
    s.llm.queue = { items: 0, claimed: 0 }; s.llm.timing = { waitReason: 'backlog_empty', runningRequests: 0 }; s.llm.processed = { calls: 0, averageCallMillis: null }; return s; },
  waiting: () => { const s = busy(); s.sync.gate = 'load'; s.llm.timing = { waitReason: 'time_window', runningRequests: 0, nextAllowedStart: NOW + 3600000 };
    s.llm.queue = { items: 181, claimed: 0 }; return s; },
  paused: () => { const s = busy(); s.storage.reasons = [{ code: 'manual' }]; s.storage.growthAllowed = false; s.llm.timing = { waitReason: 'manual_pause', runningRequests: 0 }; return s; },
  errors: () => { const s = busy(); s.sync.scheduler = { state: 'failed', lastError: 'java.lang.StackOverflowError', failedAt: NOW - 60000 };
    s.llm.breaker = { open: true, openUntil: NOW + 600000 }; s.llm.timing = { waitReason: 'circuit_breaker', runningRequests: 0 };
    s.store = { integrity: { state: 'failed' } }; return s; },
  missing: () => ({ enabled: true, state: 'running', config: { valid: true }, sync: { state: 'running' }, llm: { state: 'running', enabled: true, model: 'm' },
    derived: { enabled: true, intervalMinutes: 60 }, collections: [{ collection: 'kga', followed: true, documents: null }], rebuild: { phase: 'none' } }),
  down: () => ({ enabled: true, state: 'unavailable', reason: 'kg_unavailable', config: { valid: true }, llm: { state: 'off' }, rebuild: { phase: 'none' } }),
};

for (const language of ['en', 'de']) for (const width of [390, 1280]) {
  const context = await browser.newContext({ locale: language, viewport: { width, height: 900 } });
  const page = await context.newPage(), errors = [], writes = [];
  page.on('pageerror', e => errors.push(e.message));
  let scenario = 'busy', objects = 92596;
  await page.route(base + '/**', async route => {
    const u = new URL(route.request().url());
    if (route.request().method() !== 'GET') writes.push(route.request().method() + ' ' + u.pathname);
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
    if (u.pathname === '/env/scoutro/collections.js') return route.fulfill({ contentType: 'application/javascript', body: 'window.ScoutroCollections={load:async()=>["kga","kgb"],fill:(s,ids,w)=>{for(const id of ids){const o=document.createElement("option");o.value=id;o.textContent=id;s.append(o);}s.value=w||"";s.disabled=false;return s.value;}};' });
    if (u.pathname.startsWith('/env/scoutro/')) return route.fulfill({ contentType: u.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(path.join(root, 'htroot', u.pathname.slice(1)), 'utf8') });
    if (u.pathname.endsWith('/kg/status')) return route.fulfill({ json: SCENARIOS[scenario]() });
    if (u.pathname.endsWith('/kg/entities')) return objects == null ? route.fulfill({ status: 503, json: { error: { code: 'kg_unavailable' } } })
      : route.fulfill({ json: { total: u.searchParams.get('collection') === 'kga' ? 61234 : objects, items: [] } });
    if (u.pathname.endsWith('/kg/backups')) return route.fulfill({ json: { items: [] } });
    return route.fulfill({ status: 404, body: '' });
  });
  const L = key => page.evaluate(k => document.querySelector(`[data-skg-label="${k}"]`).textContent, key);
  const station = key => page.locator(`.skg-station[data-station="${key}"]`);
  const chip = async key => (await station(key).locator('.skg-station-state').textContent()).trim();
  const lines = async key => (await station(key).locator('.skg-station-lines').innerText()).replace(/\s+/g, ' ');
  const detail = async key => (await station(key).locator('.skg-station-detail').innerText()).replace(/\s+/g, ' ');
  const where = ` (${language}, ${width})`;
  const n = v => v.toLocaleString(language);
  const load = async (name, query = '') => { scenario = name; await page.goto(base + '/ScoutroKnowledge_p.html' + query, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => document.querySelector('#skg-process-meta').textContent.length > 0); };

  // running work
  await load('busy');
  check(await page.locator('#skg-overview').isVisible() && await page.locator('#skg-process').isVisible(), 'process display on the status' + where);
  check(!(await page.locator('#skg-diagnosis').evaluate(d => d.open)) && !(await page.locator('#skg-sync').isVisible()), 'technical diagnosis closed at first' + where);
  const meta = await page.locator('#skg-process-meta').innerText();
  check(meta.includes((await L('pr_updated')).split('%1')[0].trim()) && meta.includes(await L('pr_scope_all')), 'read time and scope shown' + where);
  check(await chip('rules') === await L('pr_k_running') && await chip('ai') === await L('pr_k_running'), 'rules and AI work' + where);
  check((await lines('rules')).includes((await L('pr_rules_open')).replace('%1', n(1200))), 'page comparison counted in page changes' + where);
  check((await lines('ai')).includes((await L('pr_ai_open')).replace('%1', n(181)).replace('%2', n(1))), 'document tasks with the running one inside, not added' + where);
  check(!(await lines('ai')).includes(n(182)) && !(await lines('ai')).includes(n(8988)), 'no sum with running or skipped tasks' + where);
  check((await lines('ai')).includes(await L('pr_ai_failed_one')) && await chip('ai') !== await L('pr_k_error'), 'one failed task is a hint in the singular, not a red state' + where);
  check((await lines('sources')).includes((await L('pr_src_open')).replace('%1', n(100))), 'detected changes at the sources' + where);
  check(await chip('suggestions') === await L('pr_k_scheduled'), 'suggestions wait as planned' + where);
  check((await lines('suggestions')).includes(await L('pr_sg_open')), 'no invented overall progress of the matching' + where);
  // details by mouse: units and time bases
  await station('ai').locator('.skg-station-head').click();
  check(await station('ai').locator('.skg-station-detail').isVisible() && await station('ai').locator('.skg-station-head').getAttribute('aria-expanded') === 'true', 'tap opens the detail' + where);
  const ai = await detail('ai');
  check(ai.includes((await L('pr_unit_tasks')).replace('%1', n(181))) && ai.includes(await L('pr_basis_now')), 'tasks with unit and basis' + where);
  check(ai.includes((await L('pr_unit_docs')).replace('%1', n(8988))) && ai.includes(await L('pr_d_skipped')), 'skipped shown apart as no backlog' + where);
  check(ai.includes((await L('pr_d_avg_value')).replace('%1', (41.2).toLocaleString(language)).replace('%2', n(188))), 'measured average call duration' + where);
  check(ai.includes(await L('pr_basis_total')) && ai.includes(await L('pr_basis_start')), 'all-time and since-start bases kept apart' + where);
  await station('suggestions').locator('.skg-station-head').click();
  const sg = await detail('suggestions');
  check(sg.includes((await L('pr_unit_signals')).replace('%1', n(50000))) && sg.includes((await L('pr_unit_partitions')).replace('%1', n(89)))
    && sg.includes(await L('pr_basis_run')) && sg.includes(await L('pr_sg_note')), 'matching run values marked as last run, no progress' + where);
  await station('rules').locator('.skg-station-head').click();
  check((await detail('rules')).includes(await L('pr_rules_note')), '0/n changes belong to the page comparison, not the AI queue' + where);
  check(!/\d\s*%\s*(done|complete|fertig|erledigt)|\bETA\b|Restzeit|remaining time|verbleibend/i.test(await page.locator('#skg-process').innerText()), 'no invented percentage or remaining time' + where);
  // keyboard: focus a step, Enter and Space toggle, the diagnosis link opens the old status list
  await station('knowledge').locator('.skg-station-head').focus();
  await page.keyboard.press('Enter');
  check(await station('knowledge').locator('.skg-station-detail').isVisible(), 'Enter opens the detail' + where);
  check((await detail('knowledge')).includes((await L('pr_unit_objects')).replace('%1', n(92596))), 'knowledge objects with unit' + where);
  await page.keyboard.press('Space');
  check(!(await station('knowledge').locator('.skg-station-detail').isVisible()), 'Space closes it again' + where);
  await station('ai').locator('.skg-diagnosis-link').click();
  check(await page.locator('#skg-diagnosis').evaluate(d => d.open) && await page.locator('#skg-llm').isVisible()
    && (await page.locator('#skg-llm').innerText()).length > 20 && await page.locator('#skg-sync').isVisible() && await page.locator('#skg-cards').isVisible(),
    'every former status list stays reachable in the technical diagnosis' + where);
  check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'no horizontal overflow' + where);
  const branches = await page.locator('.skg-flow-branches > li').evaluateAll(list => list.map(e => Math.round(e.getBoundingClientRect().top)));
  check(width >= 640 ? branches[0] === branches[1] : branches[0] < branches[1], 'parallel steps side by side on desktop, stacked on the phone' + where);
  if (shots) { fs.mkdirSync(shots, { recursive: true }); await page.locator('#skg-process').screenshot({ path: path.join(shots, `knowledge-process-busy-${language}-${width}.png`) }); }
  // refresh only reads
  await page.locator('#skg-process-refresh').click(); await page.waitForTimeout(300);
  check(await station('ai').locator('.skg-station-detail').isVisible(), 'an open detail stays open after a refresh' + where);

  // empty queues
  await load('empty');
  check(await chip('rules') === await L('pr_k_idle') && (await lines('rules')).includes(await L('pr_rules_open_none')), 'empty page comparison is up to date' + where);
  check(await chip('ai') === await L('pr_k_idle') && (await lines('ai')).includes(await L('pr_ai_open_none')), 'empty AI queue is up to date' + where);
  await station('ai').locator('.skg-station-head').click();
  check((await detail('ai')).includes(await L('pr_d_avg_none')), 'no average without a measured call' + where);

  // waiting reasons: planned versus resources
  await load('waiting');
  check(await chip('ai') === await L('pr_k_scheduled') && (await lines('ai')).includes(await L('schedule_reason_time_window')), 'time window is a planned wait' + where);
  check(await chip('rules') === await L('pr_k_waiting') && (await lines('rules')).includes(await L('schedule_reason_load')), 'load gate is a resource wait' + where);
  check((await lines('rules')).includes(await L('pr_q_why')), 'the reason is asked and answered' + where);

  // pause
  await load('paused');
  check(await chip('rules') === await L('pr_k_paused') && await chip('ai') === await L('pr_k_paused') && await chip('knowledge') === await L('pr_k_paused'), 'manual pause is a pause' + where);
  check((await lines('knowledge')).includes(await L('pr_kn_manual_action')), 'the pause says how to resume' + where);

  // errors
  await load('errors');
  check(await chip('rules') === await L('pr_k_error') && (await lines('rules')).includes(await L('pr_rules_failed')), 'a stopped sync task is an error with an action' + where);
  check(await chip('ai') === await L('pr_k_error') && (await lines('ai')).includes(await L('schedule_reason_circuit_breaker')), 'an open breaker is an error' + where);
  check(await chip('knowledge') === await L('pr_k_error'), 'a failed integrity check is an error' + where);
  check(await chip('sources') === await L('pr_k_waiting') && (await lines('sources')).includes(await L('pr_src_blocked')), 'detected changes wait while the sync task is stopped' + where);
  await station('ai').locator('.skg-station-head').click();
  check((await detail('ai')).includes(await L('pr_unit_calls_one')) || (await detail('ai')).includes((await L('pr_unit_calls')).replace('%1', n(0))), 'singular units in the details' + where);
  if (shots) await page.locator('#skg-process').screenshot({ path: path.join(shots, `knowledge-process-errors-${language}-${width}.png`) });

  // missing data
  objects = null; await load('missing');
  check((await lines('rules')).includes(await L('pr_open_unknown')) && await chip('rules') === await L('pr_k_unknown'), 'missing sync data said so' + where);
  check((await lines('ai')).includes(await L('pr_open_unknown')), 'missing AI queue said so' + where);
  await station('knowledge').locator('.skg-station-head').click();
  check((await detail('knowledge')).includes(await L('pr_open_unknown')), 'missing object count said so' + where);
  await station('suggestions').locator('.skg-station-head').click();
  check((await lines('suggestions')).includes((await L('pr_sg_never')).replace('%1', n(60))), 'no run yet said so' + where);
  await load('down');
  for (const key of ['sources', 'rules', 'ai', 'knowledge', 'suggestions']) check(await chip(key) === await L('pr_k_unknown'), 'graph down: no data at ' + key + where);
  check((await lines('rules')).includes((await L('pr_graph_down')).replace('%1', 'kg_unavailable')), 'graph down named' + where);
  objects = 92596;

  // scope of a collection
  await load('busy', '?collection=kga');
  check((await page.locator('#skg-process-meta').innerText()).includes((await L('pr_scope_one')).replace('%1', 'kga')), 'collection scope shown' + where);
  await station('knowledge').locator('.skg-station-head').click();
  check((await detail('knowledge')).includes((await L('pr_unit_objects')).replace('%1', n(61234))), 'objects follow the collection' + where);
  await station('sources').locator('.skg-station-head').click();
  check((await detail('sources')).includes((await L('pr_unit_pages')).replace('%1', n(41857))), 'pages follow the collection' + where);

  check(writes.length === 0, 'the display and its details only read: ' + writes.join(', ') + where);
  check(errors.length === 0, 'no script errors: ' + errors.join('; ') + where);
  await context.close();
}
await browser.close();
console.log(`PASS: ${checks} knowledge process display checks`);
