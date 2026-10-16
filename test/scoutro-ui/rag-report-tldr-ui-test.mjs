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
  for (const width of [390, 1280]) {
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
} finally { await browser.close(); }
console.log(`PASS: ${checks} desktop/mobile German UI checks`);
