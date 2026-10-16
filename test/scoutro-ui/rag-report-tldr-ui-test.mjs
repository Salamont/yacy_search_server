#!/usr/bin/env node
/* Disposable peer only; invoked by rag-report-tldr-live-smoke.py. */
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { withDigestSignIn } from './digest-signin.mjs';
const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
assert(base && new URL(base).hostname === '127.0.0.1');
const browser = await chromium.launch({executablePath: process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-proxy-server']});
withDigestSignIn(browser);
let checks = 0;
try {
  for (const width of process.env.SCOUTRO_ROLES_ONLY ? [] : [390, 1280]) {
    const context = await browser.newContext({locale: 'de-DE', viewport: {width, height: 900}, isMobile: width < 700, hasTouch: width < 700, httpCredentials: {username: 'admin', password: 'yacy'}});
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    for (const name of ['LogReports_p', 'LLMSelection_p']) {
      await page.goto(`${base}/${name}.html`, {waitUntil: 'load'});
      const controls = page.locator(name === 'LogReports_p' ? '.logreport-settings' : '#tldr-controls');
      await controls.scrollIntoViewIfNeeded();
      assert(await controls.isVisible()); checks++;
      assert((await controls.innerText()).includes(name === 'LogReports_p' ? 'Automatische Berichte' : 'Suchanfragen automatisch verkürzen')); checks++;
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2);
      assert(!overflow, `page overflow ${name} ${width}`); checks++;
      assert.equal(errors.length, 0, errors.join('; ')); checks++;
      if (name === 'LogReports_p') {
        assert((await controls.innerText()).includes('YaCy-Standardlogs')); checks++;
        assert(!(await controls.innerText()).includes('65 Tage')); checks++;
        for (const mode of ['hourly', 'daily', 'weekly', 'monthly', 'off']) {
          await page.locator('#scheduleMode').selectOption(mode);
          assert.equal(await page.locator('[data-schedule="hourly"]').isVisible(), mode === 'hourly'); checks++;
          assert.equal(await page.locator('[data-schedule="weekly"]').isVisible(), mode === 'weekly'); checks++;
          assert.equal(await page.locator('[data-schedule="monthly"]').isVisible(), mode === 'monthly'); checks++;
        }
        await page.locator('#scheduleMode').selectOption('weekly');
        await page.locator('#scheduleWeekday').selectOption('3');
        await page.locator('#scheduleTime').fill('08:30');
        await Promise.all([page.waitForNavigation({waitUntil: 'load'}), page.locator('button[name="saveReportSettings"]').click()]);
        assert.equal(await page.locator('#scheduleMode').inputValue(), 'weekly'); checks++;
        assert((await page.locator('#reportNextRun').innerText()).match(/\d{2}\.\d{2}\.\d{4}/)); checks++;
        if (process.env.SCOUTRO_SCREENSHOTS) {
          fs.mkdirSync(process.env.SCOUTRO_SCREENSHOTS, {recursive: true});
          await page.screenshot({path: path.join(process.env.SCOUTRO_SCREENSHOTS, `schedule-weekly-${width}.png`), fullPage: true});
        }
        await page.locator('#scheduleMode').selectOption('monthly');
        await page.locator('#scheduleMonthday').selectOption('31');
        assert.equal(await page.locator('#scheduleMonthday').inputValue(), '31'); checks++;
        await page.locator('#scheduleMonthday').selectOption('0');
        await page.locator('#scheduleMode').selectOption('hourly');
        await page.locator('#scheduleHours').selectOption('2');
        await page.locator('#scheduleMode').selectOption('weekly');
        await page.locator('#scheduleWeekday').selectOption('1');
        await page.locator('#scheduleTime').fill('06:30');
        await page.locator('#scheduleMode').selectOption('off');
        await Promise.all([page.waitForNavigation({waitUntil: 'load'}), page.locator('button[name="saveReportSettings"]').click()]);
        assert.equal(await page.locator('#scheduleMode').inputValue(), 'off'); checks++;
        assert((await page.locator('#reportNextRun').innerText()).includes('Nicht geplant')); checks++;
        await page.locator('input[name="recreateReport"]').check();
        await page.locator('.logreport-message').evaluateAll(nodes => nodes.forEach(node => node.remove()));
        const started = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/LogReports_p.html');
        await page.locator('#runReportButton').click();
        assert.equal((await started).status(), 200); checks++;
        await page.waitForFunction(() => [...document.querySelectorAll('.logreport-message')].some(node => node.textContent.includes('erstellt')));
        checks++;
      } else {
        await page.locator('#tldrQuestion').fill('Welche Anbieter für Wärmedämmung gibt es in Köln?');
        await Promise.all([page.waitForNavigation({waitUntil: 'load'}), page.locator('button[name="testTldr"]').click()]);
        const result = page.locator('#tldr-test-result');
        assert((await result.innerText()).includes('Gültig')); checks++;
        assert((await result.innerText()).includes('promptTokens')); checks++;
        assert((await result.innerText()).includes('Wärmedämmung')); checks++;
      }
      if (process.env.SCOUTRO_SCREENSHOTS) {
        fs.mkdirSync(process.env.SCOUTRO_SCREENSHOTS, {recursive: true});
        await page.screenshot({path: path.join(process.env.SCOUTRO_SCREENSHOTS, `${name}-${width}.png`), fullPage: true});
      }
    }
    // Submit the actual form/button with its CSRF token. No model request.
    await Promise.all([page.waitForNavigation({waitUntil: 'load'}), page.locator('button[name="saveTldrSettings"]').click()]);
    assert((await page.locator('#tldr-controls').innerText()).includes('gespeichert')); checks++;
    await context.close();
  }
  if (process.env.SCOUTRO_ROLES_ONLY) {
    const context = await browser.newContext({locale: 'de-DE', viewport: {width: 1280, height: 900}, httpCredentials: {username: 'admin', password: 'yacy'}});
    const page = await context.newPage();
    await page.goto(`${base}/LLMSelection_p.html`, {waitUntil: 'load'});
    const modelRow = name => page.locator(`#productionModelsTable tbody tr:has(td:nth-child(2):text-is("${name}"))`);
    const role = (name, column) => modelRow(name).locator('td').nth(column).locator('input[type="checkbox"]');
    const allRoles = column => page.locator(`#productionModelsTable tbody tr td:nth-child(${column + 1}) input:checked`);
    async function toggle(name, column, checked) {
      const response = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/LLMSelection_p.html'
        && r.request().postDataJSON()?.production_models?.some(row => row.model === name && row[column === 11 ? 'tldr' : 'logreport'] === checked));
      await role(name, column).setChecked(checked);
      assert.equal((await response).status(), 200); checks++;
    }
    assert.equal(await page.locator('#productionModelsTable tbody tr').count(), 3); checks++;
    assert(await role('fixture-secondary', 13).isChecked()); checks++;
    for (const column of [11, 12]) {
      await toggle('fixture', column, false);
      assert.equal(await allRoles(column).count(), 0, 'explicit opt-out must not move to another model'); checks++;
    }
    await page.reload({waitUntil: 'load'});
    for (const column of [11, 12]) { assert.equal(await allRoles(column).count(), 0); checks++; }
    assert(await role('fixture-secondary', 13).isChecked()); checks++;
    assert((await page.locator('#tldr-controls').innerText()).includes('Kein Modell zugewiesen')); checks++;
    assert(await page.locator('input[name="tldrEnabled"]').isChecked(), 'existing permission alone cannot assign a model'); checks++;
    // The existing manual test stays unchanged: assign a model but leave automation off.
    await toggle('fixture', 11, true);
    await page.locator('input[name="tldrEnabled"]').uncheck();
    await Promise.all([page.waitForNavigation({waitUntil: 'load'}), page.locator('button[name="saveTldrSettings"]').click()]);
    await page.locator('#tldrQuestion').fill('Welche Anbieter für Wärmedämmung gibt es in Köln?');
    await Promise.all([page.waitForNavigation({waitUntil: 'load'}), page.locator('button[name="testTldr"]').click()]);
    assert((await page.locator('#tldr-test-result').innerText()).includes('Gültig')); checks++;
    assert(!(await page.locator('input[name="tldrEnabled"]').isChecked())); checks++;
    // Removing the assigned model must not transfer either optional role; chat keeps its existing behavior.
    await page.locator('input[name="tldrEnabled"]').check();
    await Promise.all([page.waitForNavigation({waitUntil: 'load'}), page.locator('button[name="saveTldrSettings"]').click()]);
    await toggle('fixture', 12, true);
    const removed = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname === '/LLMSelection_p.html'
      && r.request().postDataJSON()?.production_models?.length === 2);
    await modelRow('fixture').locator('button[data-action="undeploy"]').click();
    assert.equal((await removed).status(), 200); checks++;
    await page.reload({waitUntil: 'load'});
    assert.equal(await page.locator('#productionModelsTable tbody tr').count(), 2); checks++;
    for (const column of [11, 12]) { assert.equal(await allRoles(column).count(), 0, 'undeploy must leave optional roles off'); checks++; }
    assert(await role('fixture-secondary', 13).isChecked()); checks++;
    assert(await role('fixture-secondary', 6).isChecked(), 'existing chat reassignment unchanged'); checks++;
    assert(await page.locator('button[name="testTldr"]').isDisabled(), 'no hidden model choice for manual tests'); checks++;
    await context.close();
  }
} finally { await browser.close(); }
console.log(`PASS: ${checks} ${process.env.SCOUTRO_ROLES_ONLY ? "multi-model role opt-out" : "desktop/mobile German UI"} checks`);
