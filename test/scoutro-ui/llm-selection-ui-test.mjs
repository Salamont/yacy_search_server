#!/usr/bin/env node
/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Only run through llm-selection-live-smoke.py: disposable peer + fake Ollama.
 */
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
const stub = process.env.SCOUTRO_LLM_FIXTURE_STUB;
assert(base && stub, 'Use llm-selection-live-smoke.py; no production instance');
assert(new URL(base).hostname === '127.0.0.1' && new URL(stub).hostname === '127.0.0.1');
const shots = process.env.SCOUTRO_SCREENSHOTS;
if (shots) fs.mkdirSync(shots, { recursive: true });
let checks = 0;
function check(condition, message) { assert(condition, message); checks++; }
const browser = await chromium.launch({
  ...(process.env.SCOUTRO_CHROMIUM_PATH ? { executablePath: process.env.SCOUTRO_CHROMIUM_PATH } : {}),
  args: ['--no-proxy-server'],
});
try {
  const languages = ['en', ...fs.readdirSync('locales').filter(name => name.endsWith('.lng')).map(name => name.slice(0, -4))];
  for (const language of languages) {
    for (const width of language === 'de' || language === 'en' ? [390, 1280] : [1280]) {
      const context = await browser.newContext({
        locale: language, viewport: { width, height: 900 },
        httpCredentials: { username: 'admin', password: 'yacy' },
      });
      try {
        const page = await context.newPage();
        const errors = [];
        const saves = [];
        page.on('pageerror', error => errors.push(error.message));
        // Exercise serialization without changing even the disposable peer's settings.
        await page.route('**/LLMSelection_p.html', route => {
          if (route.request().method() !== 'POST') return route.continue();
          saves.push(route.request().postDataJSON());
          return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
        });
        const discovery = page.waitForResponse(response => new URL(response.url()).pathname === '/api/tags');
        const response = await page.goto(`${base}/LLMSelection_p.html`, { waitUntil: 'load' });
        check(response.status() === 200, `${language}/${width}: authenticated page`);
        const tags = await discovery;
        check(tags.status() === 200, `${language}/${width}: discovery status`);
        check(new URL(tags.url()).searchParams.get('hoststub') === stub, `${language}/${width}: configured hoststub`);
        await page.locator('#availableModelsContainer tbody tr').first().waitFor();
        check(await page.locator('#service').inputValue() === 'OLLAMA', `${language}/${width}: service id/value`);
        check((await page.locator('#servicesTable tbody tr').first().locator('td').first().textContent()).trim() === 'OLLAMA', `${language}/${width}: service text, not DOM object`);
        check(!await page.locator('body').textContent().then(text => text.includes('[object HTMLSelectElement]')), `${language}/${width}: no DOM stringification`);
        check((await page.locator('#availableModelsContainer tbody tr').first().textContent()).includes('fixture-model:latest'), `${language}/${width}: discovered model selectable`);
        check(await page.locator('#availableModelsContainer button[data-action="deploy-model"]').first().isEnabled(), `${language}/${width}: Deploy enabled`);
        check(!await page.locator('#modelDiscoveryStatus').isVisible(), `${language}/${width}: success clears status`);
        check(errors.length === 0, `${language}/${width}: JavaScript errors: ${errors.join(', ')}`);
        if (language !== 'de') continue;
        check((await page.locator('h2').textContent()).trim() === 'LLM-Auswahl', `${width}: actual German translation`);
        await page.locator('#availableModelsContainer button[data-action="deploy-model"]').first().click();
        const matrixDisplay = await page.locator('#productionModelsTable').evaluate(table => {
          const ancestors = [];
          for (let node = table; node; node = node.parentElement) {
            ancestors.push({ tag: node.tagName, id: node.id, display: getComputedStyle(node).display, width: node.getBoundingClientRect().width, height: node.getBoundingClientRect().height });
          }
          return ancestors;
        });
        check(await page.locator('#productionModelsTable').isVisible(), `${width}: production matrix visible: ${JSON.stringify(matrixDisplay)}`);
        check((await page.locator('#productionModelsTable').evaluate(table => table.parentElement.getBoundingClientRect().width)) > 200, `${width}: production scroll container is not collapsed by floated legend`);
        check((await page.locator('#productionModelsTable tbody').textContent()).includes('fixture-model:latest'), `${width}: model added to production matrix`);
        check((await page.locator('#productionModelsTable tbody tr').first().locator('td').first().textContent()).trim() === 'OLLAMA', `${width}: production service remains canonical`);
        check(saves.some(save => save.production_models?.some(model => model.service === 'OLLAMA' && model.model === 'fixture-model:latest')), `${width}: canonical production model serialized`);
        const originalMatrix = await page.locator('#productionModelsTable tbody').textContent();
        for (const [variant, http, message] of [
          ['http503', 503, 'HTTP-Status: 503'],
          ['auth', 401, 'Zugangsdaten'],
          ['http502', 502, 'Erreichbarkeit'],
          ['invalid-json', 200, 'ungültige Modellliste'],
          ['invalid-shape', 200, 'ungültige Modellliste'],
          ['invalid-entry', 200, 'ungültige Modellliste'],
          ['empty', 200, 'keine Modelle'],
          ['name-only', 200, null],
        ]) {
          await page.locator('#hoststub').fill(`${stub}/${variant}`);
          const nextResponse = page.waitForResponse(r => new URL(r.url()).pathname === '/api/tags');
          await page.evaluate(() => loadModelList());
          check((await nextResponse).status() === http, `${width}/${variant}: upstream status preserved`);
          if (message) {
            check(await page.locator('#modelDiscoveryStatus').isVisible(), `${width}/${variant}: visible status`);
            check((await page.locator('#modelDiscoveryStatus').textContent()).includes(message), `${width}/${variant}: actionable translated message`);
            if (variant !== 'empty') check((await page.locator('#modelDiscoveryStatus').textContent()).includes('/api/tags'), `${width}/${variant}: discovery path`);
            check(!await page.locator('#availableModelsContainer').isVisible(), `${width}/${variant}: no stale selectable models`);
            check(!(await page.locator('#modelDiscoveryStatus').textContent()).includes('fixture-secret-sentinel'), `${width}/${variant}: no upstream response body exposed`);
            if (shots && variant === 'http503') await page.screenshot({ path: path.join(shots, `llm-selection-error-de-${width}.png`), fullPage: true });
          } else {
            check((await page.locator('#availableModelsContainer tbody').textContent()).includes('name-only:latest'), `${width}: Ollama name-only response`);
          }
          check((await page.locator('#productionModelsTable tbody').textContent()) === originalMatrix, `${width}/${variant}: existing matrix retained`);
          check(await page.locator('#productionModelsTable').isVisible(), `${width}/${variant}: existing matrix stays visible`);
        }
        await page.locator('#hoststub').fill(stub);
        await page.route('**/api/tags?*', route => route.abort('failed'));
        await page.evaluate(() => loadModelList());
        check((await page.locator('#modelDiscoveryStatus').textContent()).includes('Erreichbarkeit'), `${width}: network failure visible`);
        await page.unroute('**/api/tags?*');
        await page.evaluate(() => loadModelList());
        check(!await page.locator('#modelDiscoveryStatus').isVisible(), `${width}: retry clears error`);
        check(await page.locator('#availableModelsContainer').isVisible(), `${width}: retry restores model choices`);
        await page.locator('#service').selectOption('LMSTUDIO');
        await page.locator('#hoststub').fill(stub);
        const openaiDiscovery = page.waitForResponse(r => new URL(r.url()).pathname === '/v1/models');
        await page.evaluate(() => loadModelList());
        check((await openaiDiscovery).status() === 200, `${width}: OpenAI-compatible discovery path`);
        check((await page.locator('#availableModelsContainer tbody').textContent()).includes('fixture-openai'), `${width}: OpenAI-compatible model still selectable`);
        check(await page.locator('#productionModelsTable').isVisible(), `${width}: production matrix remains visible after switching service`);
        if (shots) await page.locator('#productionModelsTable').screenshot({ path: path.join(shots, `llm-matrix-de-${width}.png`) });
        check(errors.length === 0, `${width}: no uncaught JavaScript errors`);
        if (shots) await page.screenshot({ path: path.join(shots, `llm-selection-de-${width}.png`), fullPage: true });
      } finally { await context.close(); }
    }
  }
} finally { await browser.close(); }
console.log(`PASS: ${checks} LLM selection UI checks (English + 14 locales; German desktop/mobile errors and model selection)`);
