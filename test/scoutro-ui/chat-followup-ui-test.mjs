#!/usr/bin/env node
/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Only run through test/scoutro-access/chat-jobsearch-live-smoke.py --ui: a disposable peer with invented pages and a
 * SIMULATED model that never calls a tool. The search mode the chat page sends for a follow-up such as "Such noch mal"
 * (docs/SCOUTRO_RAG_QUALITY.md, section 12), in one chat each:
 *   A  default "no search", the lens switched on once for the first question, then "Such noch mal" without the lens: the
 *      follow-up is sent without a search mode and the server searches locally with the goal of the chat;
 *   B  default "Scoutro index", the lens switched off for the follow-up: it is sent with search "no", no search;
 *   C  default "no search", "Such noch mal" as the first message: no earlier goal, no search;
 *   D  default "no search", the lens once, then an ordinary message: no search;
 *   E  a plain count question: the server's own answer (no model request) appears in the chat like any answer.
 * Prints one JSON line per request (what was sent, what came back) for the smoke's log check. */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { withDigestSignIn } from './digest-signin.mjs';

const { chromium } = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
assert(base && new URL(base).hostname === '127.0.0.1', 'Use chat-jobsearch-live-smoke.py --ui; no production instance');
const FIRST = 'Welche Stellenanzeigen gibt es zu Java und ML bei Beratungsunternehmen mit Prozessrobotik?';
const RETRY = 'Such noch mal gezielt nach Stellenanzeigen und prüfe die Originalquellen';
let checks = 0;
function check(condition, message) { assert(condition, message); checks++; }
const browser = await chromium.launch({
  ...(process.env.SCOUTRO_CHROMIUM_PATH ? { executablePath: process.env.SCOUTRO_CHROMIUM_PATH } : {}),
  args: ['--no-proxy-server'],
});
withDigestSignIn(browser);

async function chatPage() {
  const context = await browser.newContext({ locale: 'de', viewport: { width: 1280, height: 900 },
    httpCredentials: { username: 'admin', password: 'yacy' } });
  await context.route('**/*', route => route.request().url().startsWith(base) ? route.continue() : route.abort());
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  check((await page.goto(base + '/yacychat.html', { waitUntil: 'networkidle' })).status() === 200, 'chat page');
  await page.locator('#collectionSelect').selectOption('alpha-web');
  return { context, page, errors };
}

// one message through the page; returns the user message as sent and the metadata of the answer
async function send(page, text, label) {
  await page.locator('#userInput').fill(text);
  const request = page.waitForRequest(r => r.url() === base + '/v1/chat/completions');
  const response = page.waitForResponse(r => r.url() === base + '/v1/chat/completions');
  await page.locator('#sendButton').click();
  const body = JSON.parse((await request).postData());
  const meta = {};
  for (const line of (await (await response).text()).split('\n')) {
    if (!line.startsWith('data:') || line.includes('[DONE]')) continue;
    try { for (const [k, v] of Object.entries(JSON.parse(line.slice(5)))) if (k !== 'choices') meta[k] = v; } catch (e) { /* not JSON */ }
  }
  await page.waitForFunction(() => !document.querySelector('#chatMessages [data-pending-reply="true"]'), null, { timeout: 60000 });
  const user = body.messages.filter(m => m.role === 'user').at(-1);
  const sent = { label, text: typeof user.content === 'string' ? user.content : user.content.find(p => p.type === 'text')?.text,
    search: Object.prototype.hasOwnProperty.call(user, 'search') ? user.search : '(absent)', collection: body.collection ?? null,
    retrieval: meta['scoutro-retrieval'] ?? null, jobsearch: meta['scoutro-jobsearch'] ?? null,
    answeredBy: meta['scoutro-answer']?.by ?? 'model', usage: meta['scoutro-usage'] ?? null };
  console.log('REQUEST ' + JSON.stringify(sent));
  return sent;
}

const setDefault = async (page, value) => page.locator(`label[for="${value === 'no' ? 'searchDefaultNone' : 'searchDefaultLocal'}"]`).click();
const lensActive = page => page.locator('#searchButton').evaluate(b => b.classList.contains('button-active'));

try {
  // A: the reproduced failure: the lens once, then "Such noch mal" without it
  let { context, page, errors } = await chatPage();
  await setDefault(page, 'no');
  check(!(await lensActive(page)), 'A: default "no search": the lens is off');
  await page.locator('#searchButton').click();
  let first = await send(page, FIRST, 'A first');
  check(first.search === 'local' && first.collection === 'alpha-web' && first.retrieval && first.retrieval.followUp === false,
    'A: the first question searches locally in alpha-web: ' + JSON.stringify(first));
  check(!(await lensActive(page)), 'A: after the answer the lens is off again (default "no search")');
  let retry = await send(page, RETRY, 'A retry');
  check(retry.search === '(absent)', 'A: the follow-up is sent without a search mode: ' + JSON.stringify(retry));
  check(retry.retrieval && retry.retrieval.followUp === true && retry.collection === 'alpha-web',
    'A: the server searches locally with followUp true: ' + JSON.stringify(retry.retrieval));
  check(retry.jobsearch && retry.jobsearch.followUp === true && retry.jobsearch.scope === 'collection alpha-web'
    && JSON.stringify(retry.jobsearch.criteria) === JSON.stringify(['Java', 'ML', 'Robotik']),
    'A: the job search takes the goal of the first question: ' + JSON.stringify(retry.jobsearch));
  check(errors.length === 0, 'A: no page error ' + errors.join('; '));
  await context.close();

  // B: the lens switched off for the follow-up: search "no" is sent and respected
  ({ context, page, errors } = await chatPage());
  await setDefault(page, 'local');
  first = await send(page, FIRST, 'B first');
  check(first.search === 'local' && first.retrieval, 'B: the first question searches (default "Scoutro index")');
  check(await lensActive(page), 'B: after the answer the lens is on (default)');
  await page.locator('#searchButton').click();
  check(!(await lensActive(page)), 'B: switched off');
  retry = await send(page, RETRY, 'B retry');
  check(retry.search === 'no' && !retry.retrieval && !retry.jobsearch, 'B: sent as search "no", no search: ' + JSON.stringify(retry));
  check(await lensActive(page), 'B: the next message has the default again (lens on)');
  await context.close();

  // C: "Such noch mal" as the first message of a chat: nothing to take over
  ({ context, page, errors } = await chatPage());
  await setDefault(page, 'no');
  retry = await send(page, RETRY, 'C retry first');
  check(retry.search === '(absent)' && !retry.retrieval && !retry.jobsearch, 'C: no earlier goal, no search: ' + JSON.stringify(retry));
  await context.close();

  // D: an ordinary message after a search, default "no search": no search
  ({ context, page, errors } = await chatPage());
  await setDefault(page, 'no');
  await page.locator('#searchButton').click();
  await send(page, FIRST, 'D first');
  const plain = await send(page, 'Danke. Fasse das bitte kurz zusammen.', 'D plain');
  check(plain.search === '(absent)' && !plain.retrieval && !plain.jobsearch, 'D: an ordinary message stays without search: ' + JSON.stringify(plain));
  await context.close();

  // E: a plain count question: the server's own answer (no model request) is shown as any answer, with its sources
  ({ context, page, errors } = await chatPage());
  await setDefault(page, 'local');
  const count = await send(page, 'Wie viele Stellenanzeigen zu Java gibt es?', 'E count');
  check(count.search === 'local' && count.answeredBy === 'server' && count.usage && count.usage.modelCalls === 0
    && count.jobsearch && count.jobsearch.found >= 1, 'E: the count question is answered by the server: ' + JSON.stringify(count));
  const shown = await page.locator('#chatMessages').innerText();
  check(new RegExp(`gibt es ${count.jobsearch.found} Stellenanzeigen? zu Java`).test(shown.replace(/\*/g, '')),
    'E: the chat page shows the number found: ' + shown.slice(-400));
  check(errors.length === 0, 'E: no page error ' + errors.join('; '));
  await context.close();

  console.log(`PASS: ${checks} chat page checks of the follow-up search mode (simulated model)`);
} finally {
  await browser.close();
}
