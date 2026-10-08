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
    for (const width of language === 'de' || language === 'en' ? [360, 390, 412, 768, 1280] : [1280]) {
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
        // the format capability: the technical probe of format-probe.js, natively (/api/chat, version 3) for an OLLAMA model and
        // OpenAI-compatible (version 2) for the others; a value of an older probe is unknown
        const formatReading = await page.evaluate(stub => {
          const fixture = getPersistedCapabilitiesForModel('OLLAMA', stub, 'fixture-model:latest');
          persistedModelCapabilities['OLLAMA|' + stub + '|legacy'] = { thinking: 'supported', tooling: 'unsupported', vision: 'unsupported', format: 'unsupported' };
          persistedModelCapabilities['OPENAI|' + stub + '|legacy'] = { thinking: 'supported', format: 'unsupported' };
          const openaiLegacyThinking = getPersistedCapabilitiesForModel('OPENAI', stub, 'legacy').thinking;
          delete persistedModelCapabilities['OPENAI|' + stub + '|legacy'];
          const legacy = getPersistedCapabilitiesForModel('OLLAMA', stub, 'legacy');
          persistedModelCapabilities['OLLAMA|' + stub + '|v2'] = { format: 'ignored', format_probe: 2 };
          persistedModelCapabilities['OPENAI|' + stub + '|v2'] = { format: 'ignored', format_probe: 2 };
          const ollamaV2 = getPersistedCapabilitiesForModel('OLLAMA', stub, 'v2').format;
          const openaiV2 = getPersistedCapabilitiesForModel('OPENAI', stub, 'v2').format;
          setPersistedCapability('OLLAMA', stub, 'legacy', 'format', 'ignored');
          const probed = getPersistedCapabilitiesForModel('OLLAMA', stub, 'legacy');
          const entry = persistedModelCapabilities['OLLAMA|' + stub + '|legacy'];
          setPersistedCapability('OPENAI', stub, 'v2', 'format', 'supported');
          const openaiEntry = persistedModelCapabilities['OPENAI|' + stub + '|v2'];
          for (const k of ['legacy']) delete persistedModelCapabilities['OLLAMA|' + stub + '|' + k];
          delete persistedModelCapabilities['OLLAMA|' + stub + '|v2'];
          delete persistedModelCapabilities['OPENAI|' + stub + '|v2'];
          return { version: typeof ScoutroFormatProbe === 'object' ? ScoutroFormatProbe.VERSION : null, ollamaVersion: ScoutroFormatProbe.VERSION_OLLAMA,
            paths: [ScoutroFormatProbe.path('OLLAMA'), ScoutroFormatProbe.path('OPENAI')], fixture, legacy, ollamaV2, openaiV2, probed, entry, openaiEntry,
            openaiLegacyThinking, thinkingVersion: ScoutroThinkingProbe.VERSION_OLLAMA };
        }, stub);
        check(formatReading.version === 2 && formatReading.ollamaVersion === 4 && formatReading.fixture.format === 'unsupported'
          && JSON.stringify(formatReading.paths) === JSON.stringify(['/api/chat', '/v1/chat/completions']), `${language}/${width}: current format result read: ${JSON.stringify(formatReading)}`);
        check(formatReading.legacy.format === 'unknown' && formatReading.legacy.thinking === 'unknown' && formatReading.legacy.tooling === 'unsupported'
          && formatReading.fixture.thinking === 'unsupported' && formatReading.openaiLegacyThinking === 'supported' && formatReading.thinkingVersion === 2,
          `${language}/${width}: old format and (OLLAMA only) thinking values are unknown, the others unchanged: ${JSON.stringify(formatReading)}`);
        check(formatReading.ollamaV2 === 'unknown' && formatReading.openaiV2 === 'ignored', `${language}/${width}: a /v1 result counts for OpenAI, not for Ollama`);
        check(formatReading.probed.format === 'ignored' && formatReading.entry.format_probe === 4 && formatReading.entry.thinking === 'supported'
          && formatReading.openaiEntry.format_probe === 2, `${language}/${width}: a new result is stored with its service's probe version: ${JSON.stringify(formatReading.entry)}`);
        if (language === 'en' && width === 1280) {
          // the native thinking probe of an OLLAMA model against the fake's Ollama rules (no model name decides), then the native
          // format probe with the found capability: a thinking model answers the schema only with think: false
          const native = await page.evaluate(async stub => {
            const key = model => 'OLLAMA|' + stub + '|' + model;
            const out = {};
            for (const model of ['thinking-fixture:14b', 'plain-fixture:8b', 'listless-fixture:7b']) out[model] = await runNativeThinkingCapabilityTest(stub, model, '');
            // the production case: a value of the former /v1 test (unsupported, no version) -> unknown -> think: false
            persistedModelCapabilities[key('thinking-fixture:14b')] = { thinking: 'unsupported', format: 'ignored', format_probe: 3 };
            out.legacy = getPersistedCapabilitiesForModel('OLLAMA', stub, 'thinking-fixture:14b');
            out.formatAfterUpgrade = await runFormatCapabilityTest('OLLAMA', stub, 'thinking-fixture:14b', '');
            setPersistedCapability('OLLAMA', stub, 'thinking-fixture:14b', 'thinking', out['thinking-fixture:14b']);
            out.stored = { ...persistedModelCapabilities[key('thinking-fixture:14b')] };
            out.formatAfterProbe = await runFormatCapabilityTest('OLLAMA', stub, 'thinking-fixture:14b', '');
            // what the former state did: taken as non-thinking -> no think -> the budget goes to thinking -> ignored
            persistedModelCapabilities[key('thinking-fixture:14b')] = { thinking: 'unsupported', thinking_probe: 2 };
            out.formatTakenAsNonThinking = await runFormatCapabilityTest('OLLAMA', stub, 'thinking-fixture:14b', '');
            persistedModelCapabilities[key('plain-fixture:8b')] = { thinking: 'unsupported', thinking_probe: 2 };
            out.formatPlain = await runFormatCapabilityTest('OLLAMA', stub, 'plain-fixture:8b', '');
            for (const model of ['thinking-fixture:14b', 'plain-fixture:8b']) delete persistedModelCapabilities[key(model)];
            return out;
          }, stub);
          check(native['thinking-fixture:14b'] === 'supported' && native['plain-fixture:8b'] === 'unsupported', `native thinking probe (/api/show): ${JSON.stringify(native)}`);
          check(native['listless-fixture:7b'] === 'unsupported', `no capability list: the /api/chat fallback (think refused, control answered): ${JSON.stringify(native)}`);
          check(native.legacy.thinking === 'unknown' && native.legacy.format === 'unknown', `a /v1 thinking value and a version 3 format value are unknown: ${JSON.stringify(native.legacy)}`);
          check(native.formatAfterUpgrade === 'supported' && native.formatAfterProbe === 'supported', `format probe with think: false: ${JSON.stringify(native)}`);
          check(native.stored.thinking === 'supported' && native.stored.thinking_probe === 2, `native thinking result stored with its version: ${JSON.stringify(native.stored)}`);
          check(native.formatTakenAsNonThinking === 'ignored', `the root cause reproduced: without think: false the thinking model ignores the schema: ${JSON.stringify(native)}`);
          check(native.formatPlain === 'supported', `a model known not to think: the format probe without think: ${JSON.stringify(native)}`);
          check(errors.length === 0, `native probes: JavaScript errors: ${errors.join(', ')}`);
        }
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
        const deployed = saves.at(-1).production_models.find(model => model.model === 'fixture-model:latest');
        check(deployed.knowledge === false, `${width}: the opt-in knowledge usage is not assigned automatically: ${JSON.stringify(deployed)}`);
        check((await page.locator('#productionModelsTable thead').textContent()).includes('knowledge'), `${width}: knowledge column`);
        // Reflow must preserve the exact original controls and serialized grant.
        const beforeReflow = saves.at(-1).production_models;
        const originalControls = await page.locator('#productionModelsTable input').count();
        await page.setViewportSize({width: width < 992 ? 1280 : 390, height: 900});
        await page.evaluate(() => persistProductionModels());
        await page.waitForTimeout(100);
        check(JSON.stringify(saves.at(-1).production_models) === JSON.stringify(beforeReflow), `${width}: resize preserves model selection and save payload`);
        check(await page.locator('#productionModelsTable input').count() === originalControls, `${width}: no duplicate mobile controls`);
        await page.setViewportSize({width, height: 900});
        const overflow = await page.evaluate(() => ({width:document.documentElement.scrollWidth, items:Array.from(document.querySelectorAll('body *')).filter(el => el.getBoundingClientRect().right > innerWidth+1).slice(0,12).map(el => ({tag:el.tagName,id:el.id,cls:el.className,right:el.getBoundingClientRect().right}))}));
        check(overflow.width <= width + 1, `${width}: LLM services/models/matrix do not widen page: ${JSON.stringify(overflow)}`);
        if (width < 992) check(await page.locator('#productionModelsTable tbody tr').first().evaluate(row => getComputedStyle(row).display === 'grid'), `${width}: original matrix row displays as card`);
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
