#!/usr/bin/env node
/* Real loopback-only peer, controlled crawled sources; no mocked read API. */
import fs from 'node:fs';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { withDigestSignIn } from './digest-signin.mjs';
const { chromium } = createRequire(import.meta.url)('playwright');
const fixture = JSON.parse(fs.readFileSync(process.env.SCOUTRO_CHAIN_FIXTURE, 'utf8'));
assert(new URL(fixture.base).hostname === '127.0.0.1', 'disposable local peer only');
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] });
withDigestSignIn(browser); // Digest credentials after the login page (digest-signin.mjs)
let checks = 0;
function check(value, message) { assert(value, message); checks++; }
try {
  const context = await browser.newContext({ httpCredentials: { username: 'admin', password: 'yacy' } });
  const page = await context.newPage(), errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${fixture.base}/ScoutroKnowledge_p.html?view=object&id=${fixture.ids.stack}&collection=chain-stack`);
  await page.locator('#skg-sec-matches .skg-derived').first().waitFor();
  const customer = page.locator('#skg-sec-matches .skg-derived', { hasText: 'Industrie Werk GmbH' });
  check((await customer.textContent()).includes('Other collection: chain-industry'), 'visible foreign collection');
  check(!(await customer.textContent()).includes('%'), 'no probability');
  await customer.locator('details summary').first().click();
  const history = customer.locator('a[href*="view=history"]').first();
  await history.click();
  await page.locator('#skg-history').waitFor({ state: 'visible' });
  check((await page.locator('#skg-history').textContent()).includes('SAP'), 'real archived quote detail');
  await page.goBack();
  await page.locator('#skg-sec-matches .skg-derived').first().waitFor();
  await page.locator('#skg-sec-matches .skg-derived', { hasText: 'Industrie Werk GmbH' }).locator('a[href*="view=object"]').first().click();
  await page.waitForFunction(() => document.querySelector('#skg-object-name').textContent.includes('Industrie Werk GmbH'));
  check(new URL(page.url()).searchParams.get('collection') === 'chain-industry', 'target context');
  await page.goBack();
  await page.locator('#skg-sec-matches .skg-derived').first().waitFor();
  check(new URL(page.url()).searchParams.get('collection') === 'chain-stack', 'back to provider');
  await page.goto(`${fixture.base}/ScoutroKnowledge_p.html?view=network&id=${fixture.ids.stack}&collection=chain-stack&f=business,structure,offers,places,industry,audiences,jobs,derived,suggested`);
  const edge = page.locator('#skg-net-svg .skg-edge.skg-e-suggested').first();
  await edge.waitFor({ state: 'attached' });
  // Horizontal SVG paths can have a zero-height DOM bounding box despite a
  // visible stroke. Check the actual canvas, geometry and stroke independently.
  check(await page.locator('#skg-net-svg').isVisible(), 'actual graph canvas visible');
  check(await edge.locator('.skg-line').evaluate(el => {
    const b=el.getBoundingClientRect(),s=getComputedStyle(el);
    return (b.width>0||b.height>0)&&s.stroke!=='none'&&parseFloat(s.strokeWidth)>0;
  }), 'actual suggestion edge has visible geometry and stroke');
  check((await page.locator('#skg-net-table').textContent()).includes('Industrie Werk GmbH'), 'actual graph list');
  const exported = JSON.parse(await page.locator('#skg-net-json').evaluate(async a => (await fetch(a.href)).text()));
  check(exported.edges.some(e => e.contributions?.some(c => c.evidence?.some(o => o.predicate === 'system_signal'))), 'actual graph JSON evidence');
  const xml = await page.locator('#skg-net-graphml').evaluate(async a => (await fetch(a.href)).text());
  check(xml.includes('sorting_score') && xml.includes('contributions'), 'actual GraphML reasons');
  await page.locator('#skg-f-suggested').uncheck();
  await page.locator('#skg-net-form button[type=submit]').click();
  await page.waitForFunction(() => !document.querySelector('#skg-net-svg .skg-edge.skg-e-suggested'));
  check(true, 'checkbox controls graph');
  check(errors.length === 0, 'no browser errors: ' + errors.join(', '));
  await context.close();
} finally { await browser.close(); }
console.log(`PASS: ${checks} real A/B/C browser checks`);
