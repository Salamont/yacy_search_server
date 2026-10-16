/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Real saves on a NEW disposable peer only; llm-role-opt-out-live-smoke.py.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import { withDigestSignIn } from './digest-signin.mjs';
const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
const directory = process.env.SCOUTRO_SETTINGS_DIR;
const mode = process.env.SCOUTRO_ROLE_TEST_MODE;
assert(new URL(base).hostname === '127.0.0.1' && directory.startsWith('/tmp/scoutro-role-opt-out-'));
let checks = 0;
function check(value, message) { assert(value, message); checks++; }
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH,
  args: ['--no-proxy-server'] });
withDigestSignIn(browser, base);
try {
  const context = await browser.newContext({ httpCredentials: { username: 'admin', password: 'yacy' },
    viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  async function idle() { await page.evaluate(async () => { let before; do { before = modelSettingsSave; await before; } while (before !== modelSettingsSave); }); }
  async function load() {
    await page.goto(base + '/LLMSelection_p.html', { waitUntil: 'networkidle' });
    await idle();
    check(await page.locator('#productionModelsTable tbody tr').count() === 3, 'Three real stored model rows');
  }
  const row = () => page.locator('#productionModelsTable tbody tr').first();
  const role = column => row().locator('td').nth(column).locator('input');
  const status = page.locator('#modelSettingsStatus');
  const saves = [];
  page.on('response', response => {
    const request = response.request();
    if (new URL(response.url()).pathname !== '/LLMSelection_p.html' || request.method() !== 'POST' || response.status() === 401) return;
    const body = request.postDataJSON();
    if (body?.production_models) saves.push({ code: response.status(), body });
  });
  await load();
  check(await role(13).isChecked(), 'Knowledge remains assigned');
  if (mode === 'readonly') {
    let first;
    const held = new Promise(resolve => { first = resolve; });
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    let calls = 0;
    await page.route('**/LLMSelection_p.html', async route => {
      if (route.request().method() !== 'POST' || !route.request().postDataJSON()?.production_models) return route.continue();
      calls++;
      if (calls === 1) { first(); await gate; }
      await route.continue();
    });
    await role(11).uncheck();
    await held;
    await role(12).uncheck(); // queue a newer state before the first request fails
    fs.chmodSync(directory, 0o555);
    assert.throws(() => fs.writeFileSync(directory + '/must-not-write', 'x'), 'Directory really is unwritable');
    release();
    await idle();
    check(calls === 1 && saves.length === 1 && saves[0].code === 500, 'Real failed write cancels the queued stale save');
    check(await status.isVisible() && (await status.getAttribute('role')) === 'alert', 'Storage failure visible, no success');
    check((await status.textContent()).includes('HTTP 500'), 'Actual HTTP failure shown');
    check(await row().locator('select[data-role="max-tokens"]').isDisabled(), 'Edits blocked until reload');
    await page.evaluate(() => { persistProductionModels(); saveServiceNumCtx(); });
    await idle();
    check(calls === 1, 'A later token/capability save cannot retry unconfirmed roles');
    check(!(await status.textContent()).includes('sentinel'), 'Error does not leak keys');
    // Keep the directory read-only through peer shutdown. The Python harness restores it.
  } else {
    const stalePage = await context.newPage();
    await stalePage.goto(base + '/LLMSelection_p.html', { waitUntil: 'networkidle' });
    await stalePage.evaluate(async () => { await modelSettingsSave; });
    await role(11).uncheck();
    await role(12).uncheck();
    await idle();
    check(saves.length >= 2 && saves.every(save => save.code === 200), 'Rapid role changes save successfully in order');
    check(saves.at(-1).body.production_models.every(model => !model.tldr && !model.logreport), 'Last save has both roles off on every model');
    await row().locator('select[data-role="max-tokens"]').selectOption('4096');
    await idle();
    check(saves.at(-1).body.production_models[0].knowledge, 'Token change keeps knowledge');
    check(saves.at(-1).body.production_models.every(model => !model.tldr && !model.logreport), 'Token change cannot restore roles');
    const conflict = stalePage.waitForResponse(response => response.status() === 409);
    await stalePage.locator('#productionModelsTable tbody tr').first().locator('select[data-role="max-tokens"]').selectOption('8192');
    await conflict;
    await stalePage.evaluate(async () => { await modelSettingsSave; });
    check((await stalePage.locator('#modelSettingsStatus').textContent()).includes('HTTP 409'), 'Stale second tab cannot overwrite saved role states');
    check(await stalePage.locator('#productionModelsTable select[data-role="max-tokens"]').first().isDisabled(), 'Conflicting tab requires reload');
    await stalePage.close();
    await load();
    check(!await role(11).isChecked() && !await role(12).isChecked(), 'Reload reflects saved deselection');
    check(await row().locator('select[data-role="max-tokens"]').inputValue() === '4096', 'Token edit persisted');
    // New first model: keep chat's existing default, never opt in to the other three roles.
    const defaults = await page.evaluate(() => {
      const row = document.createElement('tr');
      for (let i = 0; i < PRODUCTION_MODEL_TOTAL_COLUMNS; i++) row.insertCell();
      const original = getProductionTableBody;
      try {
        getProductionTableBody = () => null; // detached row: do not alter the real matrix
        ensureProductionRowUsageCells(row, true);
        return [6, 11, 12, 13].map(col => row.cells[col].querySelector('input').checked);
      } finally { getProductionTableBody = original; }
    });
    check(JSON.stringify(defaults) === '[true,false,false,false]', 'New model does not opt into TLDR/logreport; chat default unchanged');
    // Existing chat reassignment is preserved with three models.
    await page.locator('#productionModelsTable tbody tr').nth(1).locator('td').nth(6).locator('input').uncheck();
    await idle();
    check(saves.at(-1).body.production_models[0].chat, 'Chat still transfers to another model');
    for (const failure of ['http', 'network']) {
      let calls = 0;
      await page.route('**/LLMSelection_p.html', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        calls++;
        if (failure === 'http') return route.fulfill({ status: 503, body: 'unavailable' });
        return route.abort('failed');
      });
      await role(11).check();
      await idle();
      check(await status.isVisible() && await status.getAttribute('role') === 'alert', failure + ' error is visible');
      check((await status.textContent()).includes(failure === 'http' ? 'HTTP 503' : 'Network error'), failure + ' reason shown');
      await page.evaluate(() => persistProductionModels());
      await idle();
      check(calls === 1, failure + ' blocks later writes');
      await page.unroute('**/LLMSelection_p.html');
      await load();
      check(!await role(11).isChecked() && !await role(12).isChecked(), failure + ' did not persist apparent role activation');
    }
    await page.setViewportSize({ width: 390, height: 844 });
    check(await status.isVisible(), 'Save state visible at Android width');
    const box = await status.boundingBox();
    check(box.width <= 390 && box.x >= 0, 'Save status fits Android width');
    check(await role(13).isChecked(), 'Knowledge remains assigned after all failure cases');
  }
  check(errors.length === 0, 'No JavaScript errors: ' + errors.join(', '));
  console.log(`PASS: ${checks} role/save browser checks (${mode})`);
} finally { await browser.close(); }
