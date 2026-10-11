#!/usr/bin/env node
/* GPL-2.0-or-later. Actual overview UI with an offline /kg/status fixture: the vocabulary row label has no placeholder,
 * the invalid-item reasons show a readable label next to their code, and a code without a label stays visible. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const { chromium } = createRequire(import.meta.url)('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..'), base = 'http://127.0.0.1:8090';
let checks = 0; const check = (v, m) => { assert(v, m); checks++; };

// every code the validator registers has a label, and every label a German translation
const java = fs.readFileSync(path.join(root, 'source/net/yacy/scoutro/knowledge/extract/LlmExtractor.java'), 'utf8');
const list = java.slice(java.indexOf('INVALID_REASONS = List.of('));
const codes = [...list.slice(0, list.indexOf(');')).matchAll(/"((?:entity|claim|value)_[a-z_]+)"/g)].map(m => m[1]);
const page0 = fs.readFileSync(path.join(root, 'htroot/ScoutroKnowledge_p.html'), 'utf8');
const section = fs.readFileSync(path.join(root, 'locales/de.lng'), 'utf8').split('#File: ScoutroKnowledge_p.html\n')[1].split('#File: ')[0];
const english = code => page0.match(new RegExp(`<span data-skg-label="invalid_${code}">([^<]*)</span>`))?.[1];
check(codes.length >= 48 && codes.includes('claim_employer_unresolved') && codes.includes('value_signal_context_missing'), 'both codes registered: ' + codes.length);
for (const code of codes) {
  check(english(code), 'label for ' + code);
  check(section.includes('\n>' + english(code) + '<=='), 'German label for ' + code);
}
check(!/%\d/.test(page0.match(/data-skg-label="vocab_sizes">([^<]*)</)?.[1] ?? '%1'), 'the vocabulary row label has no placeholder');

const by = Object.fromEntries(codes.map(c => [c, 0]));
Object.assign(by, { claim_self_reference: 1315, value_signal_context_missing: 131, claim_employer_unresolved: 4, scoutro_future_code: 2 });
const status = { state: 'running', storage: {}, jsonld: {}, sync: {}, collections: [], events: [], backup: null, rebuild: null, upgrade: null,
  llm: { state: 'running', enabled: true, model: 'OLLAMA/fixture', processed: { calls: 10, entitiesAccepted: 1, claimsAccepted: 1, valuesAccepted: 1,
    droppedUngrounded: 0, droppedInvalid: 1452, droppedInvalidByReason: by } },
  vocabulary: { graphVocabulary: '2', version: 'fixture', categories: 201, naceCodes: 1047, collections: { kga: { vocabulary: 'care' } }, problems: [] },
  derived: { enabled: true, intervalMinutes: 60 } };
const expected = {
  en: { label: 'Categories and NACE codes', value: '201 categories, 1,047 NACE codes', self: 'Statement: refers to itself (subject is object)',
    employer: 'Statement: employer unresolved (posting on behalf of a client)', counts: ['1,315', '131', '4', '2'] },
  de: { label: 'Kategorien und NACE-Codes', value: '201 Kategorien, 1.047 NACE-Codes', self: 'Aussage: Selbstbezug (Subjekt ist Objekt)',
    employer: 'Aussage: Arbeitgeber ungeklärt (Ausschreibung im Kundenauftrag)', counts: ['1.315', '131', '4', '2'] } };
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] });
try {
  for (const [language, width] of [['en', 1280], ['de', 390], ['de', 360]]) {
    const where = ` (${language}/${width})`, want = expected[language];
    const context = await browser.newContext({ viewport: { width, height: 900 }, locale: language }), page = await context.newPage(), errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.route(base + '/**', async route => {
      const u = new URL(route.request().url());
      if (u.pathname === '/ScoutroKnowledge_p.html') {
        let html = page0.replace(/#%[^\n]*?%#/g, '');
        if (language === 'de') {
          html = html.replace('lang="en"', 'lang="de"');
          for (const [key, value] of section.split('\n').filter(l => l.includes('==') && !l.startsWith('#')).map(l => l.split('==')).sort((a, b) => b[0].length - a[0].length))
            html = html.split(key).join(value);
        }
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
      }
      if (u.pathname === '/env/scoutro/collections.js')
        return route.fulfill({ contentType: 'application/javascript', body: "window.ScoutroCollections={load:async()=>['kga'],fill:(el,ids)=>{for(const id of ids){const o=document.createElement('option');o.value=id;o.textContent=id;el.append(o);}el.disabled=false;}};" });
      if (u.pathname.startsWith('/env/scoutro/'))
        return route.fulfill({ contentType: u.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(path.join(root, 'htroot', u.pathname.slice(1)), 'utf8') });
      if (u.pathname === '/scoutro/api/v1/kg/status') return route.fulfill({ contentType: 'application/json', body: JSON.stringify(status) });
      if (u.pathname === '/scoutro/api/v1/kg/entities') return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [], total: 0 }) });
      if (u.pathname.startsWith('/scoutro/api/v1/kg/')) return route.fulfill({ contentType: 'application/json', body: '{}' });
      return route.fulfill({ status: 404, body: '' });
    });
    await page.goto(base + '/ScoutroKnowledge_p.html');
    await page.waitForFunction(() => document.querySelector('#skg-vocab').children.length > 0 && document.querySelector('#skg-llm-invalid').children.length > 0);
    // vocabulary: a label without placeholders, the formatted counts in the value
    const vocab = await page.locator('#skg-vocab dt').allTextContents();
    check(!vocab.some(l => /%\d/.test(l)), 'no raw placeholder in a vocabulary label: ' + JSON.stringify(vocab) + where);
    const row = await page.evaluate(l => [...document.querySelectorAll('#skg-vocab dt')].find(d => d.textContent === l)?.nextElementSibling?.textContent, want.label);
    check(row === want.value, 'vocabulary sizes row: ' + row + where);
    // invalid-item reasons: most frequent first, zeros left out, a readable label next to the code, an unknown code as its code
    await page.locator('#skg-llm-invalid-details summary').click();
    const reasons = await page.locator('#skg-llm-invalid dt').evaluateAll(n => n.map(d => d.dataset.reason));
    check(JSON.stringify(reasons) === JSON.stringify(['claim_self_reference', 'value_signal_context_missing', 'claim_employer_unresolved', 'scoutro_future_code']),
      'reasons, most frequent first: ' + JSON.stringify(reasons) + where);
    const shown = await page.locator('#skg-llm-invalid dt').evaluateAll(n => n.map(d => [d.querySelector('.skg-reason-label')?.textContent ?? null, d.querySelector('.skg-reason-code')?.textContent]));
    check(shown[0][0] === want.self && shown[0][1] === 'claim_self_reference', 'known code: label and code ' + JSON.stringify(shown[0]) + where);
    check(shown[1][0] && shown[1][0] !== 'value_signal_context_missing' && shown[1][1] === 'value_signal_context_missing', 'value_signal_context_missing labelled ' + JSON.stringify(shown[1]) + where);
    check(shown[2][0] === want.employer && shown[2][1] === 'claim_employer_unresolved', 'claim_employer_unresolved labelled ' + JSON.stringify(shown[2]) + where);
    check(shown[3][0] === null && shown[3][1] === 'scoutro_future_code', 'an unknown code stays visible as its code ' + JSON.stringify(shown[3]) + where);
    check(JSON.stringify(await page.locator('#skg-llm-invalid dd').allTextContents()) === JSON.stringify(want.counts), 'formatted counts' + where);
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'no horizontal overflow' + where);
    check(errors.length === 0, 'no page errors: ' + errors.join(' | ') + where);
    await context.close();
  }
} finally { await browser.close(); }
console.log(`PASS: ${checks} offline LLM-reason/vocabulary UI checks (labels, EN/DE, mobile, unknown code)`);
