#!/usr/bin/env node
/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Only run through test/scoutro-api/kg-e2e-live.py (step 7): a disposable peer that crawled the fixture site.
 * The organisation from the crawl in the object view of its collection, its facts with evidence, the source view,
 * the overview with the storage levels and the rebuild panel, in English and German, at 390 and 1280. */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { withDigestSignIn } from './digest-signin.mjs';

const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
const entity = process.env.SCOUTRO_KG_ENTITY, name = process.env.SCOUTRO_KG_NAME, collection = process.env.SCOUTRO_KG_COLLECTION;
assert(base && new URL(base).hostname === '127.0.0.1' && entity && name && collection, 'Use kg-e2e-live.py; no production instance');
let checks = 0;
function check(condition, message) { assert(condition, message); checks++; }
const browser = await chromium.launch({
  ...(process.env.SCOUTRO_CHROMIUM_PATH ? { executablePath: process.env.SCOUTRO_CHROMIUM_PATH } : {}),
  args: ['--no-proxy-server'],
});
withDigestSignIn(browser); // Digest credentials after the login page (digest-signin.mjs)
try {
  for (const [language, width] of [['en', 1280], ['de', 390]]) {
    const where = ` (${language}/${width})`;
    const context = await browser.newContext({ locale: language, viewport: { width, height: 900 }, httpCredentials: { username: 'admin', password: 'yacy' } });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base + `/ScoutroKnowledge_p.html?view=object&id=${entity}&collection=${collection}`, { waitUntil: 'networkidle' });
    await page.waitForSelector('#skg-out .skg-statement');
    check((await page.locator('#skg-object-name').textContent()).includes(name), 'object name' + where);
    const facts = await page.locator('#skg-object').textContent();
    check(facts.includes('+49') && facts.includes('DE811111111'), 'phone and VAT ID from the crawl' + where);
    check(!facts.includes('Musterfrau') && !facts.includes('DE822222222'), 'no person and nothing of the other collection' + where);
    const vat = page.locator('#skg-out .skg-statement', { hasText: 'DE811111111' }).first();
    await vat.locator('button').click();
    await vat.locator('.skg-excerpt').first().waitFor();
    check((await vat.locator('.skg-evidence').textContent()).includes(collection), 'evidence with its collection' + where);
    await vat.locator('.skg-evidence a', { hasText: language === 'de' ? 'Was der Graph aus dieser Seite enthält' : 'What the graph holds from this page' }).first().click();
    await page.waitForSelector('#skg-source-items .skg-statement');
    check(await page.locator('#skg-source').isVisible(), 'source view' + where);
    await page.goto(base + '/ScoutroKnowledge_p.html', { waitUntil: 'networkidle' });
    await page.waitForFunction(() => document.querySelector('#skg-storage').textContent.length > 0);
    check((await page.locator('#skg-cards').textContent()).includes('running'), 'overview running' + where);
    check((await page.locator('#skg-budget .skg-meter').count()) === 1, 'storage meter' + where);
    check(await page.locator('[data-skg-action="rebuild"]').isVisible(), 'rebuild available' + where);
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'no horizontal overflow' + where);
    check(errors.length === 0, 'no JavaScript errors: ' + errors.join(', '));
    await context.close();
  }
} finally { await browser.close(); }
console.log(`PASS: ${checks} end-to-end UI checks (object, evidence, source, overview; English and German)`);
