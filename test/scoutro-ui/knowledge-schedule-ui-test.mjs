#!/usr/bin/env node
/* GPL-2.0-or-later. Real localized HTML/JS, offline status fixtures. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..'), base = 'http://127.0.0.1:8090';
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] });
let checks = 0; const check = (ok, why) => { assert(ok, why); checks++; };
try {
  for (const language of ['en', 'de']) {
    const context = await browser.newContext({ locale: language, viewport: { width: 390, height: 900 } });
    const page = await context.newPage(), errors = [];
    page.on('pageerror', e => errors.push(e.message));
    const plan = { mode: 'scheduled', days: [1, 5], from: '22:00', until: '02:00', zone: 'Europe/Berlin', minStartSeconds: 5 };
    await page.route(base + '/**', async route => {
      const u = new URL(route.request().url());
      if (u.pathname === '/ScoutroKnowledge_p.html') {
        let html = fs.readFileSync(path.join(root, 'htroot/ScoutroKnowledge_p.html'), 'utf8').replace(/#\(\).*?#\(\//gs, '');
        if (language === 'de') {
          html = html.replace('lang="en"', 'lang="de"');
          const section = fs.readFileSync(path.join(root, 'locales/de.lng'), 'utf8').split('#File: ScoutroKnowledge_p.html\n')[1].split('#File: ')[0];
          const entries = section.split('\n').filter(l => l.includes('==') && !l.startsWith('#')).map(l => l.split('=='));
          for (const [key, value] of entries.sort((a, b) => b[0].length - a[0].length)) html = html.split(key).join(value);
        }
        return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
      }
      if (u.pathname === '/env/scoutro/collections.js') return route.fulfill({ contentType: 'application/javascript', body: "window.ScoutroCollections={load:async()=>[],fill:()=>{}};" });
      if (u.pathname.startsWith('/env/scoutro/')) return route.fulfill({ contentType: u.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(path.join(root, 'htroot', u.pathname.slice(1)), 'utf8') });
      if (u.pathname.endsWith('/kg/status')) return route.fulfill({ json: { enabled: true, state: 'running', config: { valid: true }, llm: { enabled: true, model: 'TEST/local', timing: { plan, valid: true, windowOpen: false, nextAllowedStart: null, waitReason: 'manual', runningRequests: 1, manual: { state: 'stopping', documents: 1, maxDocuments: 25, requestStarts: 1, maxRequests: 100 } } } } });
      if (u.pathname.endsWith('/kg/llm-schedule')) return route.fulfill({ json: { plan, valid: true, validationError: null } });
      if (u.pathname.endsWith('/kg/collections')) return route.fulfill({ json: { collections: [], vocabularies: [], llm: { model: 'TEST/local', active: true } } });
      return route.fulfill({ status: 404, body: '' });
    });
    await page.goto(base + '/ScoutroKnowledge_p.html?view=settings', { waitUntil: 'networkidle' });
    await page.waitForFunction(() => document.querySelector('#skg-schedule-zone').value === 'Europe/Berlin');
    check(await page.locator('#skg-schedule-title').textContent() === (language === 'de' ? 'Zeitsteuerung der LLM-Anreicherung' : 'Timing of LLM enrichment'), 'localized title ' + language);
    check((await page.locator('#skg-schedule-mode option[value="automatic"]').textContent()) === (language === 'de' ? 'Wie bisher automatisch' : 'Automatic as before'), 'localized default mode ' + language);
    check(await page.locator('#skg-schedule-days input:checked').count() === 2, 'saved weekdays rendered ' + language);
    check(await page.locator('#skg-schedule-from').inputValue() === '22:00' && await page.locator('#skg-schedule-until').inputValue() === '02:00', 'overnight form rendered ' + language);
    check((await page.locator('#skg-schedule-status').textContent()).includes(language === 'de' ? 'Wissensgraph manuell pausiert' : 'Knowledge graph manually paused'), 'resource reason distinguished from mode ' + language);
    check((await page.locator('#skg-schedule-status').textContent()).includes(language === 'de' ? 'Stoppt nach laufenden Anfragen' : 'Stopping after current requests'), 'localized manual state ' + language);
    check(await page.locator('#skg-schedule-now').isDisabled() && await page.locator('#skg-schedule-stop').isEnabled(), 'inflight stop controls ' + language);
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'no mobile overflow ' + language);
    check(errors.length === 0, 'no localized browser errors ' + language);await context.close();
  }
  console.log(`PASS: ${checks} offline timing UI checks (EN/DE, 390px)`);
} finally { await browser.close(); }
