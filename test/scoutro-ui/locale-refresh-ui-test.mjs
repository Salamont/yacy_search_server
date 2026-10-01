#!/usr/bin/env node
/* German navigation/pages after locale regeneration. GPL-2.0-or-later. */
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
assert(base, 'Use the disposable locale-refresh-live-smoke.py harness');
const shots = process.env.SCOUTRO_SCREENSHOTS;
if (shots) fs.mkdirSync(shots, { recursive: true });
let checks = 0;
function check(value, message) { assert(value, message); checks++; }
const pages = [
  ['scoutro-dashboard.html', 'h1', 'Scoutro Dashboard'],
  ['ScoutroAgents_p.html', 'h2', 'Agenten & Zugriffe'],
  ['ScoutroAgentWizard_p.html', 'h2', 'Neuer Agent'],
];
const browser = await chromium.launch({
  ...(process.env.SCOUTRO_CHROMIUM_PATH ? { executablePath: process.env.SCOUTRO_CHROMIUM_PATH } : {}),
  args: ['--no-proxy-server'],
});
try {
  for (const width of [360, 1280]) {
    const mobile = width < 768;
    const context = await browser.newContext({
      viewport: { width, height: 900 }, locale: 'de-DE', isMobile: mobile, hasTouch: mobile,
      httpCredentials: { username: 'admin', password: 'yacy' },
    });
    try {
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      for (const [url, heading, label] of pages) {
        errors.length = 0;
        const response = await page.goto(`${base}/${url}`, { waitUntil: 'load' });
        check(response.status() === 200, `${width}: ${url} loads authenticated`);
        check((await page.locator(heading).first().textContent()).trim() === label, `${width}: ${url} German heading`);
        const html = await page.content();
        check(!/#(?:\[[^\r\n]*?\]|\([^\r\n]*?\)|\{[^\r\n]*?\}|%[^\r\n]*?%)#/.test(html), `${width}: ${url} unresolved template marker`);
        if (mobile) {
          const toggle = page.locator('#scoutro-nav-toggle');
          await toggle.click();
          check(await toggle.getAttribute('aria-expanded') === 'true', `${width}: navigation opens`);
        }
        for (const [href, text] of [['scoutro-dashboard.html', 'Dashboard'], ['ScoutroAgents_p.html', 'Agenten & Zugriffe']]) {
          const link = page.locator(`#scoutro-adminnav a[href="${href}"]:visible`).first();
          check(await link.count() === 1, `${width}: ${href} visible in navigation`);
          check((await link.textContent()).trim() === text, `${width}: ${href} German navigation label`);
          await link.scrollIntoViewIfNeeded();
          const box = await link.boundingBox();
          check(box && box.x >= -1 && box.x + box.width <= width + 1 && (!mobile || box.height >= 40), `${width}: ${href} reachable/tappable`);
        }
        check(errors.length === 0, `${width}: ${url} JavaScript errors: ${errors.join(', ')}`);
        if (shots) await page.screenshot({ path: path.join(shots, `${process.env.SCOUTRO_LOCALE_MODE}-${width}-${url}.png`) });
      }
    } finally { await context.close(); }
  }
} finally { await browser.close(); }
console.log(`PASS: ${checks} German desktop/mobile locale UI checks`);
