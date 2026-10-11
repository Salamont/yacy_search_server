/*
 * Shared helper of the Scoutro UI tests. Copyright (C) 2026 Scoutro contributors. GPL-2.0-or-later.
 *
 * Browsers that open a page without credentials see the Scoutro login page
 * instead of the HTTP Digest dialog (docs/SCOUTRO_USERS_ACCESS.md), so
 * Playwright's httpCredentials alone no longer authenticate a navigation.
 * A context created with httpCredentials therefore opens the Digest fallback
 * page (scoutro-digest.html) once; Chromium then sends the Digest credentials
 * with every later request of the origin, as before.
 */
export function withDigestSignIn(browser, base = process.env.SCOUTRO_URL || 'http://127.0.0.1:8090') {
  const original = browser.newContext.bind(browser);
  browser.newContext = async (options = {}) => {
    const context = await original(options);
    if (options && options.httpCredentials && base) {
      const page = await context.newPage();
      try {
        await page.goto(String(base).replace(/\/$/, '') + '/scoutro-digest.html', { waitUntil: 'domcontentloaded' });
      } catch (e) {
        // the test's own checks report an unreachable peer
      } finally {
        await page.close();
      }
    }
    return context;
  };
  return browser;
}
