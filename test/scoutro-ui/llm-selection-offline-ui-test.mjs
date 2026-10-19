#!/usr/bin/env node
/* GPL-2.0-or-later. The LLM selection page offline (0.11.5) with the real HTML/JS, the server template resolved as for a
   saved Anthropic endpoint and a fake model list: the hints of a local service (install Ollama or LM Studio, no key
   needed, Ollama's num_predict and context length) are hidden for a hosted service and shown for Ollama; no delete
   button for a hosted service; the German page shows no English status, button or table texts; phone and desktop. */
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

// the page as the server would send it for a saved Anthropic endpoint: loops empty, switches at their first alternative
function render(language) {
  let html = fs.readFileSync(path.join(root, 'htroot/LLMSelection_p.html'), 'utf8');
  // the stylesheets and the viewport meta the server's templates include
  html = html.replace('#%env/templates/metas.template%#', '<meta name="viewport" content="width=device-width, initial-scale=1"><link rel="stylesheet" href="env/base.css"><link rel="stylesheet" href="env/scoutro/scoutro.css">');
  html = html.replace(/#\{(\w+)\}#[\s\S]*?#\{\/\1\}#/g, '');
  html = html.replace(/#\((\w+)\)#([^#]*?)::[^#]*?#\(\/\1\)#/g, '$2');
  html = html.replace('data-llm-service="#[llm_service]#"', 'data-llm-service="ANTHROPIC"')
    .replace('data-llm-hoststub="#[llm_hoststub]#"', 'data-llm-hoststub="https://api.anthropic.com"')
    .replace('data-llm-apikey-set="#[llm_apikey_set]#"', 'data-llm-apikey-set="1"')
    .replace('data-settings-saved="#[settings_saved]#"', 'data-settings-saved="1"').replace('data-settings-revision="#[settings_revision]#"', 'data-settings-revision="1"')
    .replace('data-model-capabilities="#[model_capabilities]#"', 'data-model-capabilities="{}"')
    .replace('data-service-num-ctx="#[service_num_ctx_json]#"', 'data-service-num-ctx="{}"')
    .replace(/#\[[^\]]+\]#/g, '');
  if (language === 'de') {
    const section = fs.readFileSync(path.join(root, 'locales/de.lng'), 'utf8').split('#File: LLMSelection_p.html\n')[1].split('#File: ')[0];
    const entries = section.split('\n').filter(l => l.includes('==') && !l.startsWith('#')).map(l => l.split('=='));
    for (const [key, value] of entries) html = html.split(key).join(value);
  }
  return html;
}

for (const language of ['en', 'de']) for (const width of [390, 1280]) {
  const context = await browser.newContext({ locale: language, viewport: { width, height: 900 } });
  const page = await context.newPage(), errors = [], upstream = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('dialog', d => d.dismiss());
  await page.route(base + '/**', async route => {
    const u = new URL(route.request().url());
    if (u.pathname === '/LLMSelection_p.html') {
      // the page saves its inference system after a model list: the server's confirmation, nothing else
      if (route.request().method() === 'POST') return route.fulfill({ contentType: 'text/html; charset=utf-8', body: '<html><body data-settings-saved="1" data-settings-revision="2"></body></html>' });
      return route.fulfill({ contentType: 'text/html; charset=utf-8', body: render(language) });
    }
    if (u.pathname.startsWith('/env/')) {
      const file = path.join(root, 'htroot', u.pathname.slice(1));
      if (!fs.existsSync(file)) return route.fulfill({ status: 404, body: '' });
      return route.fulfill({ contentType: u.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(file, 'utf8') });
    }
    if (u.pathname === '/v1/models') { upstream.push(u); return route.fulfill({ json: { object: 'list', data: [{ id: 'claude-sonnet-5-5', object: 'model' }, { id: 'claude-haiku-5-5', object: 'model' }] } }); }
    if (u.pathname === '/api/tags') { upstream.push(u); return route.fulfill({ json: { models: [{ model: 'llama3.1:8b', name: 'llama3.1:8b' }] } }); }
    return route.fulfill({ status: 404, json: { error: { code: 'not_found' } } });
  });
  const where = ` (${language}, ${width})`;
  await page.goto(base + '/LLMSelection_p.html', { waitUntil: 'networkidle' });
  await page.waitForSelector('#availableModelsContainer table');

  // a hosted service: no local hints, no delete button, the model list through the proxy
  check((await page.locator('#service').inputValue()) === 'ANTHROPIC', 'the saved service is selected' + where);
  const hidden = async sel => page.evaluate(s => [...document.querySelectorAll(s)].map(el => el.hidden), sel);
  const local = await hidden('[data-llm-hint="local"]');
  check(local.length >= 3 && local.every(Boolean), 'the hints of a local service are hidden for Anthropic: ' + local + where);
  check((await hidden('[data-llm-hint="OLLAMA"]')).every(Boolean), 'the Ollama hint is hidden' + where);
  const text = await page.locator('body').innerText();
  check(!text.includes('Install your local LLM service') && !text.includes('Installieren Sie Ihren lokalen LLM-Dienst'), 'no install hint for a hosted service' + where);
  check(!text.includes('not required for Ollama') && !text.includes('nicht erforderlich'), 'no "no key needed" hint' + where);
  const rows = page.locator('#availableModelsContainer tbody tr');
  check(await rows.count() === 2, 'two Anthropic models listed' + where);
  check(await page.locator('#availableModelsContainer button[data-action="deploy-model"]').count() === 2, 'a deploy button per model' + where);
  check(await page.locator('#availableModelsContainer button[data-action="delete-model"]').count() === 0, 'no delete button for a hosted service' + where);
  check(await page.locator('#availableModelsContainer button.btn-danger').count() === 0, 'no disabled delete button either' + where);
  check(await page.locator('#loadModelContainer').isHidden(), 'no recommended downloads for a hosted service' + where);
  check(upstream.some(u => u.pathname === '/v1/models' && u.searchParams.get('hoststub') === 'https://api.anthropic.com'), 'the list came through the proxy' + where);

  // the German page: no English texts of the script
  if (language === 'de') {
    for (const english of ['Available Models', 'Load Model Name List', 'Loading models', 'Deploy', 'Model is already listed', 'Production model tests']) {
      check(!text.includes(english), 'no English "' + english + '"' + where);
    }
    for (const german of ['Verfügbare Modelle', 'Einsetzen', 'Dienst-Auswahl']) {
      check(text.includes(german), 'German "' + german + '"' + where);
    }
    check((await page.locator('input[name="llmselection"]').inputValue()) === 'Modellliste laden', 'the load button in German' + where);
    const heads = await page.locator('#availableModelsContainer thead th').allTextContents();
    check(heads[0] === 'Modell' && heads.includes('Beschreibung') && heads.includes('Aktionen'), 'German table heads: ' + heads + where);
  } else {
    check(text.includes('Available Models') && (await page.locator('#availableModelsContainer button[data-action="deploy-model"]').first().textContent()) === 'Deploy', 'English page unchanged' + where);
  }

  // Ollama: the hints return, the delete button is there
  await page.locator('#service').selectOption('OLLAMA');
  check((await hidden('[data-llm-hint="local"]')).every(h => !h), 'the local hints show for Ollama' + where);
  check((await hidden('[data-llm-hint="OLLAMA"]')).every(h => !h), 'the Ollama hint shows' + where);
  await page.locator('input[name="llmselection"]').click();
  await page.waitForSelector('#availableModelsContainer button[data-action="delete-model"]');
  check(await page.locator('#availableModelsContainer button[data-action="delete-model"]').count() === 1, 'a delete button for an Ollama model' + where);
  await page.locator('#service').selectOption('LMSTUDIO');
  check((await hidden('[data-llm-hint="local"]')).every(h => !h) && (await hidden('[data-llm-hint="OLLAMA"]')).every(Boolean), 'LM Studio: local hints, no Ollama-only hint' + where);
  await page.locator('#service').selectOption('OPENROUTER');
  check((await hidden('[data-llm-hint="local"]')).every(Boolean), 'Open Router: no local hints' + where);
  check(await page.evaluate(() => document.scrollingElement.scrollWidth <= window.innerWidth + 1), 'no horizontal scrolling' + where);
  check(errors.length === 0, 'no page errors: ' + errors.join('; ') + where);
  await context.close();
}
await browser.close();
console.log(`llm-selection-offline-ui-test: ${checks} checks passed`);
