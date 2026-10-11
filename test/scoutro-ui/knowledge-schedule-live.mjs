#!/usr/bin/env node
/* GPL-2.0-or-later. Only through kg-llm-schedule-live.py: NEW disposable local DATA. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import { withDigestSignIn } from './digest-signin.mjs';
const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL, model = process.env.SCOUTRO_SCHEDULE_MODEL, marker = process.env.SCOUTRO_SCHEDULE_MARKER;
assert(base && model && new URL(base).hostname === '127.0.0.1' && new URL(model).hostname === '127.0.0.1' && marker && fs.existsSync(marker), 'Use disposable schedule harness');
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-proxy-server', '--no-sandbox'] });
withDigestSignIn(browser); // Digest credentials after the login page (digest-signin.mjs)
let checks = 0;
const check = (condition, message) => { assert(condition, message); checks++; };
try {
  const context = await browser.newContext({ httpCredentials: { username: 'admin', password: 'yacy' }, viewport: { width: 1280, height: 900 } });
  const page = await context.newPage(), errors = [], requests = [];
  page.on('pageerror', e => errors.push(e.message));page.on('request', r => { if (r.url().includes('/kg/llm-')) requests.push(r.url()); });
  const get = path => page.evaluate(async path => { const r = await fetch(path, { credentials: 'same-origin' }); if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); }, path);
  const status = () => get('/scoutro/api/v1/kg/status');
  const waitStatus = async predicate => { const end = Date.now() + 60000; let s; while (Date.now() < end) { s = await status(); if (predicate(s)) return s; await new Promise(resolve => setTimeout(resolve, 100)); } throw new Error('Runtime condition not reached: ' + JSON.stringify(s.llm)); };
  await page.goto(base + '/ScoutroKnowledge_p.html?view=settings&collection=timing-b', { waitUntil: 'networkidle' });
  await page.waitForFunction(() => document.querySelector('#skg-schedule-mode').value === 'manual');
  check(await page.locator('#skg-schedule-now').isEnabled(), 'manual available with selected knowledge model');
  const before = await status();
  await page.locator('#skg-schedule-zone').fill('not/a/zone');await page.locator('#skg-schedule-form button').click();
  await page.waitForFunction(() => document.querySelector('#skg-message').textContent.includes('HTTP 400'));
  check((await get('/scoutro/api/v1/kg/llm-schedule')).plan.zone === 'UTC', 'invalid form persists nothing');
  await page.locator('#skg-schedule-mode').selectOption('scheduled');await page.locator('#skg-schedule-zone').fill('Europe/Berlin');
  await page.locator('#skg-schedule-from').fill('22:00');await page.locator('#skg-schedule-until').fill('02:00');await page.locator('#skg-schedule-gap').fill('1');
  for (let d = 1; d <= 7; d++) await page.locator('#skg-schedule-days input[value="' + d + '"]').setChecked(d === 1);
  await page.locator('#skg-schedule-form button').click();await page.waitForFunction(() => document.querySelector('#skg-schedule-message').textContent.length > 0);
  const saved = await get('/scoutro/api/v1/kg/llm-schedule');
  check(saved.plan.zone === 'Europe/Berlin' && saved.plan.days.join() === '1' && saved.plan.minStartSeconds === 1 && saved.plan.from === '22:00' && saved.plan.until === '02:00', 'real form saves full explicit plan');
  check((await status()).store.epoch === before.store.epoch, 'hot update retains dataset epoch');
  await page.locator('#skg-schedule-mode').selectOption('manual');await page.locator('#skg-schedule-form button').click();
  await page.waitForFunction(() => document.querySelector('#skg-schedule-mode').value === 'manual' && document.querySelector('#skg-schedule-message').textContent.length > 0);
  await page.locator('#skg-schedule-docs').fill('1');await page.locator('#skg-schedule-requests').fill('100');await page.locator('#skg-schedule-now').click();
  await waitStatus(s => s.llm.timing.runningRequests === 1);
  check((await status()).llm.timing.manual.maxDocuments === 1, 'manual run obeys submitted document bound');
  const inFlight = await status();
  await page.locator('#skg-schedule-gap').fill('2');await page.locator('#skg-schedule-form button').click();
  await page.waitForFunction(() => document.querySelector('#skg-schedule-message').textContent.length > 0);
  const changed = await status();
  check(changed.llm.timing.runningRequests === 1 && changed.llm.timing.manual.id === inFlight.llm.timing.manual.id && changed.llm.processed.calls === inFlight.llm.processed.calls && changed.store.epoch === inFlight.store.epoch, 'timing hot change retains inflight request, run, counters and epoch');
  await page.locator('#skg-schedule-stop').click();
  await waitStatus(s => s.llm.timing.manual.state === 'stopping');
  check((await status()).llm.timing.runningRequests === 1, 'stop keeps admitted request running');
  await context.request.get(model + '/release');
  await waitStatus(s => s.llm.timing.manual.state === 'stopped' && s.llm.timing.runningRequests === 0);
  check((await status()).llm.documents.done === 0, 'stop retains unfinished multi-chunk document');
  await page.waitForFunction(() => !document.querySelector('#skg-schedule-now').disabled);
  await page.locator('#skg-schedule-docs').fill('2');await page.locator('#skg-schedule-now').click();
  await waitStatus(s => s.llm.documents.done === 2);
  check((await status()).llm.processed.callFailures === 0, 'resume publishes without time-related failures');
  const done = (await status()).llm.processed.calls;
  await page.waitForFunction(() => !document.querySelector('#skg-schedule-now').disabled);
  await page.locator('#skg-schedule-now').click();
  await waitStatus(s => s.llm.timing.manual.state === 'completed');
  check((await status()).llm.processed.calls === done, 'manual rerun does not reevaluate done pages');
  check(requests.every(u => !new URL(u).search), 'global collection view never contaminates timing or run requests');
  check(await page.locator('#skg-schedule-status').textContent().then(s => s.includes('Europe/Berlin')), 'status names explicit zone');
  for (const width of [390, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'schedule controls fit viewport ' + width);
  }
  check(errors.length === 0, 'no browser errors: ' + errors.join('; '));await context.close();
  console.log(JSON.stringify({ result: 'passed', checks }));
} finally { await browser.close(); }
