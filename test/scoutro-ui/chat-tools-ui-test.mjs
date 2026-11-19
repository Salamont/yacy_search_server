#!/usr/bin/env node
/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Only run through test/scoutro-access/chat-tools-live-smoke.py --ui: a disposable peer with invented pages and a
 * SIMULATED model (a stub that scripts search, kg_search, kg_entity and kg_evidence and then cites their pages, one of
 * them for a statement it does not make). Checks what the chat page shows of it. */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { withDigestSignIn } from './digest-signin.mjs';

const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
assert(base && new URL(base).hostname === '127.0.0.1', 'Use chat-tools-live-smoke.py --ui; no production instance');
let checks = 0;
function check(condition, message) { assert(condition, message); checks++; }
const browser = await chromium.launch({
  ...(process.env.SCOUTRO_CHROMIUM_PATH ? { executablePath: process.env.SCOUTRO_CHROMIUM_PATH } : {}),
  args: ['--no-proxy-server'],
});
withDigestSignIn(browser);
try {
  for (const [language, width] of [['en', 1280], ['de', 390]]) {
    const where = ` (${language}/${width})`;
    const context = await browser.newContext({ locale: language, viewport: { width, height: 900 }, isMobile: width < 768,
      httpCredentials: { username: 'admin', password: 'yacy' } });
    await context.route('**/*', route => route.request().url().startsWith(base) ? route.continue() : route.abort());
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    const metas = [];
    page.on('response', async r => {
      if (r.url() !== base + '/v1/chat/completions') return;
      for (const line of (await r.text().catch(() => '')).split('\n')) {
        if (!line.startsWith('data:') || line.includes('[DONE]')) continue;
        try { const e = JSON.parse(line.slice(5)); if (e['scoutro-citations']) metas.push(e); } catch (e) { /* not JSON */ }
      }
    });
    check((await page.goto(base + '/yacychat.html', { waitUntil: 'networkidle' })).status() === 200, 'chat page' + where);
    await page.locator('#userInput').fill('Welche Stellenanzeigen gibt es zu Java und Machine Learning bei Beratungsunternehmen mit Prozessrobotik?');
    const reply = page.waitForResponse(r => r.url() === base + '/v1/chat/completions' && r.status() === 200);
    await page.locator('#sendButton').click();
    await reply;
    await page.waitForSelector('#chatMessages .chat-citation-warning');
    const answer = page.locator('#chatMessages .chat-turn.assistant').last();
    const meta = metas.at(-1);
    check(meta && Array.isArray(meta['scoutro-sources']) && meta['scoutro-tools']?.calls === 4, 'the final line carries the sources and the tool summary' + where);
    const sources = meta['scoutro-sources'];
    const citations = meta['scoutro-citations'];
    // the source list is the one of the final line: the pages of the tool results with their numbers
    const listed = await answer.locator('.chat-sources li').evaluateAll(items => items.map(li => [li.value, li.querySelector('a').href]));
    check(JSON.stringify(listed) === JSON.stringify(sources.map(s => [s.id, new URL(s.url).href])), 'source list equals the sources after the tools: ' + JSON.stringify(listed) + where);
    const badge = (await answer.locator('.chat-source-kind').first().textContent()).trim();
    check(badge === (language === 'de' ? 'Scoutro-Wissensgraph' : 'Scoutro knowledge graph'), 'graph evidence is marked in the list: ' + badge + where);
    // every cited number is a link to its source; the one behind the invented salary is marked as not supported
    const links = await answer.locator('a.chat-citation').evaluateAll(as => as.map(a => [a.textContent, a.href, a.classList.contains('chat-citation-unsupported')]));
    check(links.length === 3 && links.every(([text, href]) => sources.some(s => `[${s.id}]` === text && new URL(s.url).href === href)), 'citations link their sources: ' + JSON.stringify(links) + where);
    check(citations.unsupported.length === 1 && links.filter(l => l[2]).every(l => l[0] === `[${citations.unsupported[0]}]`) && links.some(l => l[2]),
      'the unsupported citation is marked: ' + JSON.stringify({ links, unsupported: citations.unsupported }) + where);
    check(links.some(l => !l[2]), 'the supported citation is a plain link' + where);
    check(await answer.locator('.chat-citation-invalid').count() === 0, 'no invalid number' + where);
    const warning = await answer.locator('.chat-citation-warning').innerText();
    check(warning.includes(language === 'de' ? 'enthält die davorstehende Aussage nicht' : 'does not contain the statement in front of it')
      && warning.includes(`[${citations.unsupported[0]}]`), 'the note below the answer names it: ' + warning + where);
    const title = await answer.locator('a.chat-citation-unsupported').first().getAttribute('title');
    check(title.startsWith(language === 'de' ? 'Der Text dieser Quelle enthält die Aussage vor dieser Nummer nicht' : 'The text of this source does not contain the statement in front of this number'),
      'and its tooltip says why: ' + title + where);
    check((await answer.innerText()).includes('nichts belegt'), 'what is not stated stays in the answer' + where);
    check(!(await page.locator('#chatMessages').innerText()).includes('BETAGEHEIM'), 'nothing invented for beta-web appears' + where);
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'no horizontal overflow' + where);
    check(errors.length === 0, 'no JavaScript errors: ' + errors.join(', ') + where);
    if (process.env.SCOUTRO_SCREENSHOTS) await page.screenshot({ path: `${process.env.SCOUTRO_SCREENSHOTS}/chat-tools-${language}-${width}.png`, fullPage: true });
    await context.close();
  }
} finally { await browser.close(); }
console.log(`PASS: ${checks} chat UI checks with tool sources and the support note (English 1280, German 390; simulated model)`);
