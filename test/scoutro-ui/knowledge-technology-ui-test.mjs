#!/usr/bin/env node
/* GPL-2.0-or-later. The company view's "Website technologies" (Scoutro R5, website_technology) of the knowledge graph page with
   the real localized HTML/JS and controlled API fixtures: name with edition, category, host and a stated version, in a
   section of its own apart from "Software and systems" (internal use); phone and desktop, EN/DE. Names are examples. */
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

const ORG = 'kge_' + 'a'.repeat(20);
const fact = (id, extra) => Object.assign({ statement: 'kgs_' + id.repeat(20), status: 'current', quality: 'supported', confidence: 0.9, kinds: ['metadata'],
  sources: 1, source_docs: [{ doc_id: 'AAAAAAhost01', url: 'https://www.musterberatung.de/' }] }, extra);
const BUSINESS = { schema: 1, id: ORG,
  systems: [fact('s', { product: 'Salesforce', context: 'internal_use', source: 'company', kinds: ['rule'] })],
  website_technologies: [
    fact('a', { technology: 'wordpress', name: 'WordPress', variant: null, category: 'cms', host: 'www.musterberatung.de', version: '6.4.2',
      version_source: 'generator', meaning: 'website_integration' }),
    fact('b', { technology: 'elementor-pro', name: 'Elementor', variant: 'Pro', category: 'page_builder', host: 'www.musterberatung.de',
      meaning: 'website_integration' }),
    fact('c', { technology: 'google-tag-manager', name: 'Google Tag Manager', variant: null, category: 'tag_management',
      host: 'www.musterberatung.de', meaning: 'website_integration' })],
  sources: [] };

for (const language of ['en', 'de']) for (const width of [390, 1280]) {
  const context = await browser.newContext({ locale: language, viewport: { width, height: 900 } });
  const page = await context.newPage(), errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.route(base + '/**', async route => {
    const u = new URL(route.request().url());
    if (u.pathname === '/ScoutroKnowledge_p.html') {
      let html = fs.readFileSync(path.join(root, 'htroot/ScoutroKnowledge_p.html'), 'utf8').replace(/#\(\).*?#\(\//gs, '')
        .replace('#[scoutroAdmin]#', '1').replace('#[scoutroExport]#', '1');
      if (language === 'de') {
        html = html.replace('lang="en"', 'lang="de"');
        const section = fs.readFileSync(path.join(root, 'locales/de.lng'), 'utf8').split('#File: ScoutroKnowledge_p.html\n')[1].split('#File: ')[0];
        const entries = section.split('\n').filter(l => l.includes('==') && !l.startsWith('#')).map(l => l.split('=='));
        for (const [key, value] of entries.sort((a, b) => b[0].length - a[0].length)) html = html.split(key).join(value);
      }
      return route.fulfill({ contentType: 'text/html; charset=utf-8', body: html });
    }
    if (u.pathname === '/env/scoutro/collections.js') return route.fulfill({ contentType: 'application/javascript', body: 'window.ScoutroCollections={load:async()=>["alpha-care"],fill:(s,ids,w)=>{for(const id of ids){const o=document.createElement("option");o.value=id;o.textContent=id;s.append(o);}s.value=w||"";s.disabled=false;return s.value;}};' });
    if (u.pathname.startsWith('/env/scoutro/')) return route.fulfill({ contentType: u.pathname.endsWith('.css') ? 'text/css' : 'application/javascript', body: fs.readFileSync(path.join(root, 'htroot', u.pathname.slice(1)), 'utf8') });
    if (u.pathname.endsWith('/kg/entities/' + ORG)) return route.fulfill({ json: { id: ORG, type: 'organization', name: 'Muster Pflege GmbH', quality: 'supported', counts: {} } });
    if (u.pathname.endsWith('/kg/entities/' + ORG + '/business')) return route.fulfill({ json: BUSINESS });
    if (u.pathname.includes('/kg/entities/' + ORG + '/statements')) return route.fulfill({ json: { total: 0, items: [] } });
    if (u.pathname.startsWith('/scoutro/api/v1/kg/')) return route.fulfill({ json: {} });
    return route.fulfill({ status: 404, json: { error: { code: 'not_found' } } });
  });
  const L = key => page.evaluate(k => document.querySelector(`[data-skg-label="${k}"]`).textContent, key);
  const where = ` (${language}, ${width})`;
  await page.goto(base + '/ScoutroKnowledge_p.html?view=object&id=' + ORG, { waitUntil: 'networkidle' });
  await page.waitForSelector('#skg-sec-website_technologies');
  const sec = page.locator('#skg-sec-website_technologies');
  check((await sec.locator('h3').textContent()) === await L('sec_website_technologies'), 'section title' + where);
  check(language === 'de' ? (await L('sec_website_technologies')) === 'Website-Technologien' : (await L('sec_website_technologies')) === 'Website technologies',
    'translated title' + where);
  const items = sec.locator('li[data-technology]');
  check(await items.count() === 3, 'three technologies' + where);
  const wp = await sec.locator('li[data-technology="wordpress"]').innerText();
  check(wp.includes('WordPress') && wp.includes(await L('techcat_cms')) && wp.includes('www.musterberatung.de'), 'name, category, host: ' + wp + where);
  check(wp.includes('6.4.2') && wp.includes(await L('vsrc_generator')), 'the stated version with its source: ' + wp + where);
  const pro = await sec.locator('li[data-technology="elementor-pro"]').innerText();
  check(pro.includes('Elementor Pro') && pro.includes(await L('techcat_page_builder')), 'the edition: ' + pro + where);
  check(!pro.includes(await L('vsrc_generator')), 'no version without a stated one' + where);
  check((await sec.innerText()).includes(await L('website_technologies_note')), 'the note: a website observation' + where);
  // apart from the systems: internal use stays there, the technologies never appear in it
  const systems = await page.locator('#skg-sec-systems').innerText();
  check(systems.includes('Salesforce') && !systems.includes('WordPress') && !systems.includes('Tag Manager'), 'systems apart: ' + systems + where);
  check(!(await sec.innerText()).includes('Salesforce'), 'no system in the technologies' + where);
  const toc = await page.locator('#skg-toc a').allTextContents();
  check(toc.indexOf(await L('sec_website_technologies')) === toc.indexOf(await L('sec_systems')) + 1, 'listed after the systems: ' + toc + where);
  check(await page.evaluate(() => document.scrollingElement.scrollWidth <= window.innerWidth + 1), 'no horizontal scrolling' + where);
  check(errors.length === 0, 'no page errors: ' + errors.join('; ') + where);
  await context.close();
}
await browser.close();
console.log('PASS: ' + checks + ' website technology UI checks (edition, version source, apart from systems, EN/DE, phone and desktop)');
