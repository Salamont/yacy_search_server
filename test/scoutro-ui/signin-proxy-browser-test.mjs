/*
 * Chromium sign-in through a TLS edge that forwards plain HTTP (signin-proxy-live-smoke.py).
 * Copyright (C) 2026 Scoutro contributors. GPL-2.0-or-later.
 *
 * Expects SCOUTRO_EDGE_URL (https, self-signed) and SCOUTRO_TEST_USER ({username, password}) of a peer with
 * scoutro.auth.cookie.secure=always. Prints one JSON line per check with cookie attributes only, never a
 * cookie value or password.
 */
import { createRequire } from 'node:module';

const { chromium } = createRequire(import.meta.url)('playwright');
const edge = String(process.env.SCOUTRO_EDGE_URL || '').replace(/\/$/, '');
const user = JSON.parse(process.env.SCOUTRO_TEST_USER || '{}');
let failed = 0;

function report(ok, check, detail = '') {
  if (!ok) failed++;
  console.log(JSON.stringify({ ok: Boolean(ok), check, detail: String(detail).slice(0, 300) }));
}

function attributes(c) {
  return c ? `secure=${c.secure} httpOnly=${c.httpOnly} sameSite=${c.sameSite} path=${c.path} session=${c.expires === -1}` : 'none';
}

const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH, args: ['--no-proxy-server'] });
try {
  // the edge certificate is self-signed for this run; the origin stays https
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  const session = async () => (await context.cookies(edge)).find((c) => c.name === 'scoutro_session');

  await page.goto(edge + '/ConfigAccounts_p.html', { waitUntil: 'domcontentloaded' });
  report(page.url().startsWith(edge + '/scoutro-login.html'), 'administrator page opens the login page on the https origin',
    new URL(page.url()).pathname);
  await page.fill('#sa-username', user.username);
  await page.fill('#sa-password', user.password);
  await Promise.all([
    page.waitForURL((u) => !u.pathname.endsWith('/scoutro-login.html'), { timeout: 60000 }),
    page.click('#sa-submit'),
  ]);
  report(new URL(page.url()).pathname === '/ConfigAccounts_p.html', 'sign-in returns to the requested page', new URL(page.url()).pathname);
  const set = await session();
  report(set && set.secure && set.httpOnly && set.sameSite === 'Strict' && set.path === '/' && set.expires === -1,
    'Secure, HttpOnly, SameSite=Strict session cookie kept', attributes(set));

  const again = await page.goto(edge + '/scoutro-dashboard.html', { waitUntil: 'domcontentloaded' });
  report(again.status() === 200 && new URL(page.url()).pathname === '/scoutro-dashboard.html', 'cookie used on navigation',
    `${again.status()} ${new URL(page.url()).pathname}`);
  await page.reload({ waitUntil: 'domcontentloaded' });
  report(new URL(page.url()).pathname === '/scoutro-dashboard.html', 'cookie used after reload', new URL(page.url()).pathname);
  const state = await page.evaluate(async () => (await fetch('/scoutro/api/v1/auth/session')).json());
  report(state.authenticated === true && state.user && state.user.username === user.username, 'session API sees the account');

  const out = await page.evaluate(async (csrf) => {
    const r = await fetch('/scoutro/api/v1/auth/logout', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Scoutro-CSRF': csrf }, body: '{}' });
    return { status: r.status, ok: (await r.json()).ok };
  }, state.csrf);
  report(out.status === 200 && out.ok === true, 'sign-out', out.status);
  report(!(await session()), 'cookie removed by the Secure clearing cookie', attributes(await session()));
  await page.goto(edge + '/ConfigAccounts_p.html', { waitUntil: 'domcontentloaded' });
  report(new URL(page.url()).pathname === '/scoutro-login.html', 'after sign-out the login page again', new URL(page.url()).pathname);
  await context.close();
} catch (e) {
  report(false, 'browser flow', e && e.message ? e.message.split('\n')[0] : String(e));
} finally {
  await browser.close();
}
process.exit(failed ? 1 : 0);
