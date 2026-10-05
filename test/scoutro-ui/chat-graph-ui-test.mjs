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
} finally { await browser.close(); }
console.log(`PASS: ${checks} chat UI checks with knowledge graph sources (English and German, 360 and 1280)`);
