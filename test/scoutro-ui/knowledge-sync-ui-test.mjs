#!/usr/bin/env node
/* GPL-2.0-or-later. Real localized HTML/JS with controlled status fixtures. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const base = 'http://127.0.0.1:8090';
const browser = await chromium.launch({ executablePath: process.env.SCOUTRO_CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] });
let checks = 0;
const check = (ok, why) => { assert(ok, why); checks++; };
try {
  for (const language of ['en', 'de']) {
    const context = await browser.newContext({ locale: language, viewport: { width: 390, height: 900 } });
    const page = await context.newPage(), errors = [];
    page.on('pageerror', e => errors.push(e.message));
    let failed = true;
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
      if (u.pathname === '/env/scoutro/collections.js') return route.fulfill({ contentType: 'application/javascript', body: 'window.ScoutroCollections={load:async()=>[],fill:()=>{}};' });
      if (u.pathname.startsWith('/env/scoutro/')) return route.fulfill({ contentType: u.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(path.join(root, 'htroot', u.pathname.slice(1)), 'utf8') });
      if (u.pathname.endsWith('/kg/status')) return route.fulfill({ json: {
        enabled: true, state: 'running', config: { valid: true }, llm: { state: 'off' },
        sync: { state: failed ? 'failed' : 'running', queue: { items: 9514 }, processed: { published: 486 },
          scheduler: { state: failed ? 'failed' : 'scheduled', lastStartedAt: 1791657600000, lastFinishedAt: 1791657601000,
            completedTicks: 10, failedAt: failed ? 1791657601000 : null, lastError: failed ? 'java.lang.StackOverflowError' : null, automaticRecovery: false },
          reconcile: { pending: true, reason: 'start', current: { scanned: failed ? 10000 : 12000, cursor: '009999host01' } } }
      } });
      if (u.pathname.endsWith('/kg/collections')) return route.fulfill({ json: { collections: [], vocabularies: [], llm: {} } });
      return route.fulfill({ status: 404, body: '' });
    });
    await page.goto(base + '/ScoutroKnowledge_p.html', { waitUntil: 'networkidle' });
    const panel = page.locator('#skg-sync');
    check((await panel.textContent()).includes(language === 'de' ? 'Sync-Aufgabe beendet; Serverprotokoll prüfen' : 'Sync task terminated; inspect server log'), 'localized terminal task ' + language);
    check((await panel.textContent()).includes('java.lang.StackOverflowError'), 'error class is visible ' + language);
    const value = key => page.evaluate(key => {
      const label = document.querySelector(`[data-skg-label="${key}"]`).textContent;
      return [...document.querySelectorAll('#skg-sync dt')].find(n => n.textContent === label)?.nextElementSibling.textContent;
    }, key);
    check(await value('sync_cursor') === '009999host01', 'reconcile cursor ' + language);
    check((await value('sync_scanned')).replace(/\D/g, '') === '10000', 'scanned count ' + language);
    check((await value('sync_last_started')).includes('2026') && (await value('sync_last_finished')).includes('2026'), 'execution timestamps ' + language);
    check((await value('sync_completed_ticks')).includes('10'), 'tick count kept separate from documents ' + language);
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'no mobile overflow ' + language);
    failed = false; await page.reload({ waitUntil: 'networkidle' });
    check((await panel.textContent()).includes(language === 'de' ? 'Eingeplant; wartet auf nächsten Takt' : 'Scheduled; waiting for next tick'), 'idle scheduler distinguished from failure ' + language);
    check(!(await panel.textContent()).includes('StackOverflowError'), 'fresh session has no stale failure ' + language);
    check((await value('sync_scanned')).replace(/\D/g, '') === '12000', 'updated progress ' + language);
    check(errors.length === 0, 'no browser errors ' + language); await context.close();
  }
  console.log(`PASS: ${checks} offline sync-status browser checks (EN/DE, 390px)`);
} finally { await browser.close(); }
