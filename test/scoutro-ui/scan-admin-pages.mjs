#!/usr/bin/env node
/*
 * Scoutro: scan all administration pages for horizontal overflow on a phone.
 *
 * Copyright (C) 2026 Scoutro contributors
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option)
 * any later version. See gpl.txt.
 *
 * Loads every htroot page that includes env/templates/header.template at the
 * given width and reports pages that are wider than the screen, the outermost
 * elements causing it, and JavaScript errors. Informational (always exits 0);
 * the hard checks are in scoutro-ui-test.mjs.
 *
 * Pages with side effects on GET (Steering, IndexDeletion_p, ConfigUpdate_p)
 * are skipped. Run against a disposable test instance only.
 *
 *   SCOUTRO_URL=http://127.0.0.1:8090 node test/scoutro-ui/scan-admin-pages.mjs [width]
 */

import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
let playwright;
try {
  playwright = require('playwright');
} catch (e) {
  playwright = require(path.join(execSync('npm root -g').toString().trim(), 'playwright'));
}

const BASE = (process.env.SCOUTRO_URL || 'http://127.0.0.1:8090').replace(/\/$/, '');
const USER = process.env.SCOUTRO_ADMIN_USER || 'admin';
const PASSWORD = process.env.SCOUTRO_ADMIN_PASSWORD || 'yacy';
const WIDTH = Number(process.argv[2] || 360);
const SKIP = /^(Steering|IndexDeletion_p|ConfigUpdate_p)\b/;

const htroot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../htroot');
const pages = fs.readdirSync(htroot)
  .filter((f) => f.endsWith('.html') && !SKIP.test(f))
  .filter((f) => fs.readFileSync(path.join(htroot, f), 'utf8').includes('env/templates/header.template'))
  .sort();

const browser = await playwright.chromium.launch({ args: ['--no-proxy-server'] });
const ctx = await browser.newContext({
  viewport: { width: WIDTH, height: 800 }, isMobile: true, hasTouch: true,
  httpCredentials: { username: USER, password: PASSWORD },
});
const page = await ctx.newPage();
page.setDefaultTimeout(20000);
page.on('dialog', (d) => d.dismiss());
let errors = [];
page.on('pageerror', (e) => errors.push(e.message));

let ok = 0;
for (const p of pages) {
  errors = [];
  try {
    const r = await page.goto(`${BASE}/${p}`, { waitUntil: 'load' });
    await page.waitForTimeout(200);
    const m = await page.evaluate((W) => {
      const causes = [];
      for (const e of document.body.querySelectorAll('*')) {
        const rect = e.getBoundingClientRect();
        if (rect.right <= W + 1 || rect.width === 0 || e.closest('.navbar, .bootstrap-switch-container')) continue;
        const pr = e.parentElement && e.parentElement.getBoundingClientRect();
        if (pr && pr.right > W + 1) continue;
        let scrolls = false;
        for (let q = e.parentElement; q && q !== document.body; q = q.parentElement) {
          const s = getComputedStyle(q);
          if (s.overflowX === 'auto' || s.overflowX === 'scroll') { scrolls = true; break; }
        }
        if (!scrolls) causes.push(`${e.tagName.toLowerCase()}${e.id ? '#' + e.id : ''}${typeof e.className === 'string' && e.className.trim() ? '.' + e.className.trim().split(/\s+/)[0] : ''}(${Math.round(rect.width)}px)`);
      }
      return { overflow: Math.max(document.documentElement.scrollWidth, innerWidth) - W, causes: causes.slice(0, 4) };
    }, WIDTH);
    const bad = m.overflow > 1;
    if (!bad && errors.length === 0) ok++;
    if (bad || errors.length || r.status() !== 200) {
      console.log(`${bad ? 'OVERFLOW' : 'ok'.padEnd(8)} ${r.status()} ${p.padEnd(34)} ${bad ? m.overflow + 'px ' + m.causes.join(' ') : ''}${errors.length ? ' JS: ' + errors[0].slice(0, 100) : ''}`);
    }
  } catch (e) {
    console.log(`ERROR    ${p} ${e.message.split('\n')[0]}`);
  }
}
console.log(`\n${ok}/${pages.length} admin pages without overflow or JavaScript errors at ${WIDTH}px`);
await browser.close();
