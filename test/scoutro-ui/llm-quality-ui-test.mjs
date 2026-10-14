#!/usr/bin/env node
/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Only run through llm-quality-live-smoke.py: disposable peer + fake model endpoint.
 * The quality check of the LLM selection: candidate, price from the provider list, scope and maximum cost, one check,
 * result with deviations, and a switch of the knowledge model only after a confirmation (saves are intercepted).
 */
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { withDigestSignIn } from './digest-signin.mjs';

const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
const stub = process.env.SCOUTRO_QUALITY_STUB;
const key = process.env.SCOUTRO_QUALITY_KEY;
assert(base && stub && key, 'Use llm-quality-live-smoke.py; no production instance');
assert(new URL(base).hostname === '127.0.0.1' && new URL(stub).hostname === '127.0.0.1');
const shots = process.env.SCOUTRO_SCREENSHOTS;
if (shots) fs.mkdirSync(shots, { recursive: true });
let checks = 0;
function check(condition, message) { assert(condition, message); checks++; }
const browser = await chromium.launch({
  ...(process.env.SCOUTRO_CHROMIUM_PATH ? { executablePath: process.env.SCOUTRO_CHROMIUM_PATH } : {}),
  args: ['--no-proxy-server'],
});
withDigestSignIn(browser);

async function open(language, width) {
  const context = await browser.newContext({
    locale: language, viewport: { width, height: 900 }, httpCredentials: { username: 'admin', password: 'yacy' },
  });
  const page = await context.newPage();
  const errors = [];
  const saves = [];
  page.on('pageerror', error => errors.push(error.message));
  // a switch is serialised by the matrix's own save; it never reaches the disposable peer's settings
  await page.route('**/LLMSelection_p.html', route => {
    if (route.request().method() !== 'POST') return route.continue();
    saves.push(route.request().postDataJSON());
    return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
  });
  const response = await page.goto(`${base}/LLMSelection_p.html`, { waitUntil: 'load' });
  check(response.status() === 200, `${language}/${width}: page`);
  await page.waitForFunction(() => document.querySelectorAll('#qualityCandidate option').length === 2);
  return { context, page, errors, saves };
}

async function noOverflow(page, label) {
  const overflow = await page.evaluate(() => document.scrollingElement.scrollWidth - window.innerWidth);
  check(overflow <= 1, `${label}: horizontal page overflow ${overflow}px`);
}

try {
  // ------------------------------------------------------------ one check, desktop, English
  {
    const { context, page, errors, saves } = await open('en', 1280);
    check((await page.locator('#qualityCheckContainer legend').textContent()).includes('Quality check for the knowledge graph'), 'legend');
    const options = await page.locator('#qualityCandidate option').allTextContents();
    check(options[0].includes('active-fixture') && options[0].includes('active for the knowledge graph'), `active marked: ${options}`);
    check((await page.locator('#qualityCandidateStatus').textContent()).includes('not checked'), 'unchecked shown');
    check(await page.locator('#qualityStartButton').isDisabled(), 'start before the plan');
    await page.selectOption('#qualityCandidate', key);
    // the price of the provider's model list (USD per token) as a price per million tokens
    await page.waitForFunction(() => document.getElementById('qualityPriceIn').value === '1');
    check(await page.inputValue('#qualityPriceOut') === '4', 'output price per million');
    check((await page.locator('#qualityPriceSource').textContent()).includes("provider's model list"), 'price source');
    await page.click('#qualityPlanButton');
    await page.locator('#qualityPlan').waitFor({ state: 'visible' });
    check((await page.locator('[data-q-plan="pages"]').textContent()).startsWith('10'), 'scope: ten pages');
    check((await page.locator('[data-q-plan="calls"]').textContent()).includes('20'), 'calls at most');
    check((await page.locator('[data-q-plan="cost"]').textContent()).includes('USD'), 'maximum cost');
    check(await page.inputValue('#qualityMaxCalls') === '20', 'call cap prefilled');
    check(await page.locator('#qualityStartButton').isEnabled(), 'start after the plan');
    await page.click('#qualityStartButton');
    await page.locator('#qualityResult').waitFor({ state: 'visible', timeout: 120000 });
    await page.waitForFunction(() => document.getElementById('qualityResultStatus').textContent.trim() === 'Deviations found');
    check((await page.locator('#qualityResultScope').textContent()).includes('10 of 10 reference pages'), 'scope of the result');
    check((await page.locator('[data-q-tech="calls"]').textContent()).startsWith('10 / 20'), 'technical calls');
    check((await page.locator('[data-q-fn="correct"]').textContent()) === '0', 'nothing correct in an empty reading');
    const rows = await page.locator('#qualityDeviationsTable tbody tr').count();
    check(rows >= 10, `deviations listed: ${rows}`);
    const first = page.locator('#qualityDeviationsTable tbody tr').first();
    check((await first.locator('td').nth(1).textContent()).includes('missing'), 'deviation kind');
    check((await first.locator('td').nth(2).textContent()).length > 5, 'expectation shown');
    check((await first.locator('td').nth(4).textContent()).length > 5, 'passage of the page shown');
    check((await page.locator('[data-q-prov="served"]').textContent()).includes('FixtureProvider'), 'provenance: served provider');
    check((await page.locator('[data-q-prov="testset"]').textContent()).includes('kg-reference/1'), 'provenance: test set');
    check(!(await page.content()).includes('sk-quality-sentinel'), 'a key in the page');
    if (shots) await page.screenshot({ path: `${shots}/llm-quality-1280-en.png`, fullPage: true });
    // the matrix's knowledge checkbox: dismissed, nothing changes and nothing is saved
    const candidateBox = page.locator('#productionModelsTable tbody tr', { hasText: 'quality-fixture' }).locator('td').nth(13).locator('input');
    const activeBox = page.locator('#productionModelsTable tbody tr', { hasText: 'active-fixture' }).locator('td').nth(13).locator('input');
    // the page saves its inference selection after loading the model list (as before); only a moved knowledge usage counts
    const switched = () => saves.some(save => Array.isArray(save.production_models)
      && save.production_models.some(r => r.model === 'quality-fixture' && r.knowledge === true));
    const savedRows = () => saves.filter(save => Array.isArray(save.production_models)).length;
    const rowsBefore = savedRows();
    let asked = '';
    page.once('dialog', dialog => { asked = dialog.message(); dialog.dismiss(); });
    await candidateBox.click();
    check(asked.includes('quality-fixture') && asked.includes('active-fixture') && asked.includes('Deviations found'), `confirmation: ${asked}`);
    check(!(await candidateBox.isChecked()) && await activeBox.isChecked(), 'dismissed switch changed the matrix');
    check(!switched() && savedRows() === rowsBefore, 'dismissed switch saved');
    // the activation button: dismissed, then confirmed into the intercepted save
    await page.selectOption('#qualityCandidate', key);
    page.once('dialog', dialog => dialog.dismiss());
    await page.click('#qualityActivateButton');
    check(!switched() && savedRows() === rowsBefore && await activeBox.isChecked(), 'dismissed activation saved');
    page.once('dialog', dialog => dialog.accept());
    await page.click('#qualityActivateButton');
    check(switched() && savedRows() === rowsBefore + 1, `confirmed activation: ${savedRows() - rowsBefore} saves of the matrix`);
    const rowsSaved = saves.filter(save => Array.isArray(save.production_models)).pop().production_models;
    check(rowsSaved.find(r => r.model === 'quality-fixture').knowledge === true
      && rowsSaved.find(r => r.model === 'active-fixture').knowledge === false, 'the confirmed switch moves the knowledge usage');
    check(rowsSaved.every(r => !r.api_key), 'a key in the save');
    check(errors.length === 0, `JavaScript errors: ${errors.join(', ')}`);
    await context.close();
  }

  // ------------------------------------------------------------ the stored result on every width, English and German
  for (const language of ['en', 'de']) {
    for (const width of [360, 390, 412, 768, 1280]) {
      const { context, page, errors } = await open(language, width);
      await page.selectOption('#qualityCandidate', key);
      await page.locator('#qualityResult').waitFor({ state: 'visible' });
      const status = (await page.locator('#qualityResultStatus').textContent()).trim();
      check(status === (language === 'de' ? 'Abweichungen' : 'Deviations found'), `${language}/${width}: status ${status}`);
      if (language === 'de') {
        check((await page.locator('#qualityCheckContainer legend').textContent()).includes('Qualitätsprüfung für den Wissensgraphen'), 'de: legend');
        check((await page.locator('#qualityStartButton').textContent()).includes('Qualität prüfen'), 'de: start button');
      }
      await noOverflow(page, `${language}/${width}`);
      if (width < 992) {
        // labelled cards from the same cells: the label of the first column is shown with each value
        const label = await page.locator('#qualityDeviationsTable tbody tr').first().locator('td').nth(1).getAttribute('data-label');
        check(label && label.length > 0, `${language}/${width}: labelled card`);
        // key and value tables stay two columns inside the viewport
        const kv = await page.locator('#qualityTechnicalTable').boundingBox();
        check(kv && kv.x >= 0 && kv.x + kv.width <= width + 1, `${language}/${width}: technical table inside the viewport`);
        check(await page.locator('#qualityTechnicalTable tbody tr').first().evaluate(row => getComputedStyle(row).display) === 'table-row',
          `${language}/${width}: key and value rows are no cards`);
        const display = await page.locator('#qualityDeviationsTable tbody tr').first().evaluate(row => getComputedStyle(row).display);
        check(display === 'grid', `${language}/${width}: deviation as a card (${display})`);
      }
      for (const id of ['qualityPlanButton', 'qualityStartButton', 'qualityActivateButton', 'qualityCandidate']) {
        const box = await page.locator('#' + id).boundingBox();
        check(box && box.x >= 0 && box.x + box.width <= width + 1, `${language}/${width}: ${id} inside the viewport`);
      }
      if (shots && (width === 390 || width === 1280)) await page.screenshot({ path: `${shots}/llm-quality-${width}-${language}.png`, fullPage: true });
      check(errors.length === 0, `${language}/${width}: JavaScript errors: ${errors.join(', ')}`);
      await context.close();
    }
  }
} finally {
  await browser.close();
}
console.log(`PASS: ${checks} quality-check UI checks`);
