#!/usr/bin/env node
/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Only run through test/scoutro-api/kg-agents-live-smoke.py: disposable peer with the knowledge graph on the
 * collections kga and kgb and a fake chat model that cites the first knowledge graph entry of its prompt. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
assert(base && new URL(base).hostname === '127.0.0.1', 'Use kg-agents-live-smoke.py; no production instance');
const shots = process.env.SCOUTRO_SCREENSHOTS;
if (shots) fs.mkdirSync(shots, { recursive: true });
let checks = 0;
function check(condition, message) { assert(condition, message); checks++; }
const browser = await chromium.launch({
  ...(process.env.SCOUTRO_CHROMIUM_PATH ? { executablePath: process.env.SCOUTRO_CHROMIUM_PATH } : {}),
  args: ['--no-proxy-server'],
});
try {
  for (const language of ['en', 'de']) {
    for (const width of [360, 1280]) {
      const where = ` (${language}/${width})`;
      const context = await browser.newContext({ locale: language, viewport: { width, height: 900 }, isMobile: width < 768,
        httpCredentials: { username: 'admin', password: 'yacy' } });
      await context.route('**/*', route => route.request().url().startsWith(base) ? route.continue() : route.abort());
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      check((await page.goto(base + '/yacychat.html?collection=kga', { waitUntil: 'networkidle' })).status() === 200, 'chat page' + where);
      // package 6.1: the collection comes from a list of the collections this client may use
      const names = await page.locator('#collectionSelect option').evaluateAll(list => list.map(o => o.value));
      const index = await page.evaluate(async () => (await (await fetch('/scoutro/api/v1/collections', { credentials: 'same-origin' })).json()).collections
        .map(c => c.id).filter(id => !id.startsWith('robot_'))
        .sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : a.toLowerCase() > b.toLowerCase() ? 1 : a < b ? -1 : a > b ? 1 : 0));
      check(await page.locator('#collectionSelect').inputValue() === 'kga' && JSON.stringify(names) === JSON.stringify(['', ...index]) && index.includes('kga')
        && await page.locator('#collectionInput, datalist').count() === 0, 'every collection of the index, sorted, the one of the link chosen: ' + JSON.stringify(names) + where);
      await page.locator('#userInput').fill('Was betreibt die Muster Pflege gGmbH?');
      const reply = page.waitForResponse(r => r.url() === base + '/v1/chat/completions' && r.status() === 200);
      await page.locator('#sendButton').click();
      await reply;
      await page.waitForSelector('#chatMessages .chat-source-kind');
      const badge = (await page.locator('#chatMessages .chat-source-kind').first().textContent()).trim();
      check(badge === (language === 'de' ? 'Scoutro-Wissensgraph' : 'Scoutro knowledge graph'), 'knowledge graph badge: ' + badge + where);
      const item = page.locator('#chatMessages .chat-sources li', { has: page.locator('.chat-source-kind') }).first();
      check((await item.locator('a').textContent()).startsWith('Scoutro knowledge graph: Muster Pflege gGmbH'), 'graph source title' + where);
      check((await item.locator('a').getAttribute('href')).includes('/impressum'), 'graph source links its page' + where);
      const citation = page.locator('#chatMessages a.chat-citation').first();
      check(await citation.count() === 1 && (await citation.getAttribute('href')).includes('/impressum'), 'the answer cites the graph entry' + where);
      check(!(await page.locator('#chatMessages').innerText()).includes('Geheime Holding'), 'nothing of kgb in a kga chat' + where);
      check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'no horizontal overflow' + where);
      check(errors.length === 0, 'no JavaScript errors: ' + errors.join(', ') + where);
      if (shots) await page.screenshot({ path: path.join(shots, `chat-graph-${language}-${width}.png`), fullPage: true });
      await context.close();
    }
  }
  // an AI Shield guest at 360 px: only the released kga; a link to kgb falls back to all collections
  for (const language of ['en', 'de']) {
    const where = ` (guest, ${language}/360)`;
    const guest = await browser.newContext({ locale: language, viewport: { width: 360, height: 800 }, isMobile: true, hasTouch: true,
      extraHTTPHeaders: { 'X-Forwarded-For': language === 'de' ? '198.51.100.27' : '198.51.100.26' } });
    await guest.route('**/*', route => route.request().url().startsWith(base) ? route.continue() : route.abort());
    const page = await guest.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base + '/yacychat.html?collection=kgb', { waitUntil: 'networkidle' });
    const select = page.locator('#collectionSelect');
    const options = await select.locator('option').evaluateAll(list => list.map(o => [o.value, o.textContent.trim()]));
    check(JSON.stringify(options.map(o => o[0])) === JSON.stringify(['', 'kga']) && options[0][1] === (language === 'de' ? 'Alle Collections' : 'All collections'),
      'guest: all collections and the released kga only: ' + JSON.stringify(options) + where);
    check(await select.inputValue() === '' && !(await page.content()).includes('<option value="kgb"'), 'a link to kgb: all collections, no foreign name in the page' + where);
    await select.selectOption('kga');
    check((await page.locator('#collectionCurrentValue').textContent()) === 'kga', 'guest chooses kga' + where);
    const box = await select.boundingBox();
    check(box && box.width <= 360 && await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'select fits at 360 px' + where);
    check(errors.length === 0, 'no JavaScript errors: ' + errors.join(', ') + where);
    if (shots) await page.screenshot({ path: path.join(shots, `chat-guest-${language}-360.png`), fullPage: true });
    await guest.close();
  }
} finally { await browser.close(); }
console.log(`PASS: ${checks} chat UI checks with knowledge graph sources and the collection list (English and German, 360 and 1280, administrator and guest)`);
