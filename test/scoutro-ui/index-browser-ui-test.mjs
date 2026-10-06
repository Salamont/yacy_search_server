#!/usr/bin/env node
/* Scoutro contributors, GPL-2.0-or-later. Run only from disposable seo-live-smoke.py.
 * SeoFixture: a.example has 28 pages in "visible" and 2 in "secret" (one page in both), b.example 1 in "secret". */
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
const {chromium} = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL;
assert(base && new URL(base).hostname === '127.0.0.1', 'Disposable loopback fixture required');
const browser = await chromium.launch({executablePath:process.env.SCOUTRO_CHROMIUM_PATH, args:['--no-proxy-server']});
let checks = 0;
function check(value, label) { assert(value, label); checks++; }
const CSV_HEADER = 'host,domain,scheme,website,start_url,collection,indexed_pages,title,description,last_loaded,last_crawled,crawl_status,http_status,classification_verdict,classification_confidence,classification_profile,discovery_profile,discovery_source,discovery_job,discovery_region,entity_name,entity_street,entity_postal_code,entity_city,entity_region,entity_country,entity_phone,entity_email,evidence_entity_url,evidence_contact_url,entity_name_candidate,entity_name_confidence';
const get = (page, path) => page.evaluate(async url => {
  const response = await fetch(url, {credentials: 'same-origin', cache: 'no-store'});
  return {status: response.status, type: response.headers.get('content-type'), disposition: response.headers.get('content-disposition'), body: await response.text()};
}, base + path);
const keyOf = item => item.host + '|' + item.collection;
const UNTRANSLATED = /\b(indexed|pages|loading|without|domains match|Export domains|Download export|Most indexed|Sort by)\b/;
try {
  const anonymous = await browser.newContext();
  for (const path of ['/scoutro/api/v1/index/browse', '/scoutro/api/v1/index/domains', '/scoutro/api/v1/index/domains/export?format=csv', '/IndexBrowser_p.html'])
    check((await anonymous.request.get(base + path)).status() === 401, 'Admin required: ' + path);
  await anonymous.close();

  // API contract: consolidation, filters, export formats
  {
    const context = await browser.newContext({httpCredentials:{username:'admin',password:'yacy'}});
    const page = await context.newPage();
    await page.goto(base + '/IndexBrowser_p.html');
    const all = JSON.parse((await get(page, '/scoutro/api/v1/index/domains')).body);
    check(all.schema === 'scoutro.domains.v1' && all.total === 3 && all.total_hosts === 2, 'Three domain entries on two hosts: ' + JSON.stringify({total: all.total, hosts: all.total_hosts}));
    const pages = Object.fromEntries(all.items.map(item => [keyOf(item), item.indexed_pages]));
    check(pages['a.example|visible'] === 28 && pages['a.example|secret'] === 2 && pages['b.example|secret'] === 1, 'One entry per host and collection: ' + JSON.stringify(pages));
    const visible = all.items.find(item => keyOf(item) === 'a.example|visible');
    check(visible.website === 'https://a.example/' && visible.start_url === 'https://a.example/missing' && visible.title === 'Title missing', 'Representative from the index: ' + JSON.stringify(visible));
    check(visible.classification === null && visible.discovery === null && visible.crawl_status === null, 'Unknown enrichment stays null');
    check(Object.values(visible.entity).every(v => v === null) && Object.values(visible.evidence).every(v => v === null)
      && Object.keys(visible.entity).join() === 'name,name_candidate,name_confidence,street,postal_code,city,region,country,phone,email', 'No entity pages: entity and evidence objects with null values');
    const filtered = JSON.parse((await get(page, '/scoutro/api/v1/index/domains?collection=visible')).body);
    check(filtered.total === 1 && filtered.items[0].host === 'a.example' && filtered.items[0].collection === 'visible', 'Collection filter');
    const byHost = JSON.parse((await get(page, '/scoutro/api/v1/index/domains?q=' + encodeURIComponent('https://b.example/private'))).body);
    check(byHost.total === 1 && byHost.items[0].host === 'b.example', 'URL input searches its host');
    const invalid = await get(page, '/scoutro/api/v1/index/domains?q=' + encodeURIComponent('bad host!'));
    check(invalid.status === 400 && JSON.parse(invalid.body).error.details.field === 'q', 'Invalid host input refused: ' + invalid.body);

    const json = await get(page, '/scoutro/api/v1/index/domains/export?format=json');
    check(json.status === 200 && json.type.startsWith('application/json') && /attachment; filename="scoutro-domains-all-\d{8}\.json"/.test(json.disposition), 'JSON download: ' + json.type + ' ' + json.disposition);
    const exported = JSON.parse(json.body);
    check(exported.schema === 'scoutro.domains.v1' && exported.complete === true && exported.count === 3 && exported.items.length === 3, 'Complete JSON export');
    check(new Set(exported.items.map(keyOf)).size === 3, 'No duplicate entries');
    check(exported.items.map(keyOf).join() === 'a.example|secret,a.example|visible,b.example|secret', 'Export sorted by host, then collection: ' + exported.items.map(keyOf));
    check(exported.items.every(item => 'classification' in item && 'discovery' in item && 'last_crawled' in item), 'Optional fields present as null');
    const secret = JSON.parse((await get(page, '/scoutro/api/v1/index/domains/export?format=json&collection=secret')).body);
    check(secret.count === 2 && secret.items.every(item => item.collection === 'secret') && secret.filter.collection === 'secret', 'Export respects the collection filter');
    const hostOnly = JSON.parse((await get(page, '/scoutro/api/v1/index/domains/export?format=json&q=b.example')).body);
    check(hostOnly.count === 1 && hostOnly.items[0].host === 'b.example', 'Export respects the host filter');
    const csv = await get(page, '/scoutro/api/v1/index/domains/export?format=csv&collection=visible');
    check(csv.status === 200 && csv.type.startsWith('text/csv') && /scoutro-domains-visible-\d{8}\.csv/.test(csv.disposition), 'CSV download: ' + csv.type + ' ' + csv.disposition);
    const lines = csv.body.split('\r\n');
    check(lines[0] === CSV_HEADER && lines.length === 3 && lines[2] === '', 'CSV header, one row, CRLF: ' + JSON.stringify(lines));
    check(lines[1].startsWith('a.example,a.example,https,https://a.example/,https://a.example/missing,visible,28,Title missing,'), 'CSV row: ' + lines[1]);
    check(lines[1].endsWith(',,,,,,,,,,'), 'Unknown entity values are empty CSV cells: ' + lines[1]);
    check((await get(page, '/scoutro/api/v1/index/domains/export?format=xml')).status === 400, 'Unknown export format refused');
    await context.close();
  }

  for (const language of ['en','de']) for (const width of [360,390,412,768,1280]) {
    const where = ` (${language}/${width})`;
    const context = await browser.newContext({viewport:{width,height:900}, httpCredentials:{username:'admin',password:'yacy'}, extraHTTPHeaders:{'Accept-Language':language}});
    const page = await context.newPage(); const errors=[];
    page.on('pageerror', e => errors.push(e.message));

    // default view: one card per domain and collection
    await page.goto(base+'/IndexBrowser_p.html');
    await page.waitForFunction(() => document.querySelector('#scoutro-domain-total').textContent === '3');
    check(await page.locator('#scoutro-domain-list > li').count() === 3, 'Three domain cards' + where);
    const hosts = await page.locator('.scoutro-domain-host').allTextContents();
    check(hosts.filter(host => host === 'a.example').length === 2, 'Same host in two collections stays two entries' + where);
    check(await page.locator('#scoutro-domain-list img').count() === 0, 'Index text rendered as text' + where);
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth+1), 'No horizontal overflow' + where);
    const content = await page.locator('.scoutro-index-intro, #scoutro-index-form, #scoutro-domain-view').evaluateAll(nodes => nodes.map(n => n.innerText).join('\n'));
    if (language === 'de') {
      check(content.includes('Websites im Index') && content.includes('indexierte Seiten') && content.includes('SEO-Analyse'), 'German texts' + where);
      check(!UNTRANSLATED.test(content), 'No English left in the German view: ' + (content.match(UNTRANSLATED) || [])[0] + where);
    } else {
      check(content.includes('Websites in the index') && content.includes('28 indexed pages') && content.includes('SEO analysis'), 'English texts' + where);
    }

    // collection filter and the domain card of a.example/visible; package 6.1: a real select, sorted, no free text
    const choice = page.locator('#scoutro-index-collection');
    check(await choice.evaluate(e => e.tagName) === 'SELECT' && await page.locator('#scoutro-index-form datalist, #scoutro-index-form input[list]').count() === 0, 'Collection as a real select' + where);
    await page.waitForFunction(() => !document.getElementById('scoutro-index-collection').disabled);
    const names = await choice.locator('option').evaluateAll(list => list.map(o => o.value));
    check(JSON.stringify(names) === JSON.stringify(['', 'secret', 'visible']), 'All collections first, then the collections sorted: ' + JSON.stringify(names) + where);
    await choice.selectOption('visible');
    await page.locator('#scoutro-index-form button[type=submit]').click();
    await page.waitForFunction(() => document.querySelector('#scoutro-domain-total').textContent === '1');
    check(new URL(page.url()).searchParams.get('collection') === 'visible', 'Filter kept in the page URL' + where);
    check(await page.locator('#scoutro-index-active').isVisible(), 'Active collection visible' + where);
    const card = page.locator('#scoutro-domain-list > li').first();
    check((await card.locator('.scoutro-domain-pages strong').textContent()) === '28', 'Indexed page count' + where);
    check((await card.locator('.scoutro-domain-title').textContent()) === 'Title missing', 'Title from the index' + where);

    // export panel follows the filter
    await page.locator('#scoutro-export-toggle').click();
    check(await page.locator('#scoutro-export').isVisible(), 'Export panel opens' + where);
    let href = new URL(await page.locator('#scoutro-export-download').getAttribute('href'), base);
    check(href.pathname === '/scoutro/api/v1/index/domains/export' && href.searchParams.get('collection') === 'visible' && href.searchParams.get('format') === 'json', 'JSON export link carries the filter' + where);
    check((await page.locator('#scoutro-export-scope').textContent()).startsWith('visible'), 'Export scope shown' + where);
    check((await page.locator('#scoutro-export-count').textContent()).startsWith('1 '), 'Export count shown' + where);
    await page.locator('input[name="scoutro-export-format"][value="csv"]').check();
    href = new URL(await page.locator('#scoutro-export-download').getAttribute('href'), base);
    check(href.searchParams.get('format') === 'csv', 'CSV selected' + where);
    const download = await get(page, href.pathname + href.search);
    check(download.status === 200 && download.body.split('\r\n').length === 3, 'Download link returns the filtered CSV' + where);

    // host search, invalid input
    await page.locator('#scoutro-index-collection').selectOption('');
    await page.locator('#scoutro-index-query').fill('b.example');
    await page.locator('#scoutro-index-form button[type=submit]').click();
    await page.waitForFunction(() => document.querySelector('#scoutro-domain-total').textContent === '1' && document.querySelector('.scoutro-domain-host')?.textContent === 'b.example');
    check(true, 'Host search' + where);
    await page.locator('#scoutro-index-query').fill('bad host!');
    await page.locator('#scoutro-index-form button[type=submit]').click();
    await page.locator('#scoutro-index-error').waitFor({state:'visible'});
    check(await page.locator('#scoutro-domain-list > li').count() === 0, 'Invalid host shows no domains' + where);

    // SEO analysis keeps host and collection
    await page.locator('#scoutro-index-query').fill('a.example');
    await page.locator('#scoutro-index-collection').selectOption('secret');
    await page.locator('#scoutro-index-form button[type=submit]').click();
    await page.waitForFunction(() => document.querySelector('#scoutro-domain-total').textContent === '1');
    const seo = page.locator('#scoutro-domain-list .scoutro-domain-actions a').first();
    const target = new URL(await seo.getAttribute('href'), base + '/');
    check(target.pathname === '/ScoutroSEO_p.html' && target.searchParams.get('host') === 'a.example' && target.searchParams.get('collection') === 'secret', 'SEO link carries host and collection' + where);
    await seo.click();
    await page.waitForURL(/ScoutroSEO_p\.html/);
    await page.waitForFunction(() => document.getElementById('sseo-host')?.value === 'a.example');
    check(await page.locator('#sseo-collection').inputValue() === 'secret', 'SEO analysis opened with the collection' + where);

    // contact line on the card (response with entity data; the fixture index has no Impressum pages)
    // a synthetic answer: the request of the page carries the browser's login, a mock fetch would not
    await page.route('**/scoutro/api/v1/index/domains?*', route => route.fulfill({contentType: 'application/json', body: JSON.stringify({
      schema: 'scoutro.domains.v1', generated_at: '2026-10-05T00:00:00Z', filter: {q: null, collection: 'visible', sort: 'host'},
      offset: 0, limit: 25, total: 2, total_hosts: 2, items: [{host: 'a.example', domain: 'a.example', scheme: 'https', website: 'https://a.example/',
        start_url: 'https://a.example/', collection: 'visible', indexed_pages: 28, title: 'Title missing', description: null, last_loaded: null,
        last_crawled: null, crawl_status: null, http_status: 200, classification: null, discovery: null,
        entity: {name: 'Musterbau Verwaltungs- und Betriebsgesellschaft mbH', name_candidate: 'Musterbau Verwaltungs- und Betriebsgesellschaft mbH', name_confidence: 'high', street: 'Musterstraße 1', postal_code: '50667', city: 'Köln',
          region: null, country: 'DE', phone: '+49221123456', email: 'info@musterbau-und-sanierung.example'},
        evidence: {entity_url: 'https://a.example/impressum', contact_url: 'https://a.example/impressum', name_url: 'https://a.example/impressum',
          name_method: 'page_text_legal_form', address_url: 'https://a.example/impressum', phone_url: 'https://a.example/impressum', email_url: 'https://a.example/impressum'}},
        {host: 'b.example', domain: 'b.example', scheme: 'https', website: 'https://b.example/', start_url: 'https://b.example/', collection: 'visible',
          indexed_pages: 3, title: 'Seniorenzentrum Sonnenhof | Impressum', description: null, last_loaded: null, last_crawled: null, crawl_status: null,
          http_status: 200, classification: null, discovery: null,
          entity: {name: null, name_candidate: 'Seniorenzentrum Sonnenhof', name_confidence: 'medium', street: null, postal_code: '53225', city: 'Bonn',
            region: null, country: 'DE', phone: null, email: null},
          evidence: {entity_url: 'https://b.example/impressum', contact_url: null, name_url: 'https://b.example/impressum', name_method: 'imprint_title',
            address_url: 'https://b.example/impressum', phone_url: null, email_url: null}}]})}));
    await page.goto(base+'/IndexBrowser_p.html?collection=visible');
    await page.locator('.scoutro-domain-entity').first().waitFor();
    const entity = page.locator('.scoutro-domain-entity').first();
    const entityText = await entity.innerText();
    check(entityText.includes('Musterbau Verwaltungs-') && entityText.includes('50667 Köln'), 'Name, postal code and city on the card' + where);
    check(await entity.locator('a[href="tel:+49221123456"]').count() === 1 && await entity.locator('a[href="mailto:info@musterbau-und-sanierung.example"]').count() === 1, 'Phone and e-mail links' + where);
    check(await entity.locator('a.scoutro-domain-entity-source').getAttribute('href') === 'https://a.example/impressum', 'Source page linked' + where);
    check(!entityText.includes('Musterstraße'), 'Street stays in the export, the card stays short' + where);
    check(await entity.locator('.scoutro-domain-entity-mark').count() === 0, 'A certain name has no marker' + where);
    const candidate = page.locator('.scoutro-domain-entity').nth(1);
    check((await candidate.locator('.is-candidate span').first().textContent()) === 'Seniorenzentrum Sonnenhof', 'Name candidate shown' + where);
    check((await candidate.locator('.scoutro-domain-entity-mark').textContent()) === (language === 'de' ? 'Namenskandidat' : 'Name candidate'), 'Candidate marked as not certain' + where);
    check((await candidate.innerText()).includes('53225 Bonn'), 'Candidate card keeps postal code and city' + where);
    const height = await entity.evaluate(n => n.getBoundingClientRect().height);
    check(height < (width < 768 ? 140 : 100), 'Compact contact block: ' + height + where);
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth+1), 'Card with contact line fits' + where);
    await page.unroute('**/scoutro/api/v1/index/domains?*');

    // single URLs: the former browser view
    await page.goto(base+'/IndexBrowser_p.html?view=urls&collection=visible&q=a.example');
    await page.waitForFunction(() => document.querySelector('#scoutro-index-total').textContent === '28');
    check(await page.locator('#scoutro-index-view-urls').getAttribute('aria-current') === 'page', 'URL view selected' + where);
    check(!(await page.locator('#scoutro-index-table tbody').textContent()).includes('secret'), 'Other memberships redacted by selected collection' + where);
    if (width < 992) check(await page.locator('#scoutro-index-table tbody tr').first().evaluate(row => getComputedStyle(row).display === 'grid'), 'Table row displayed as card' + where);
    // a collection of the link that is not listed: all collections, with a note; the API still refuses a malformed one
    await page.goto(base+'/IndexBrowser_p.html?view=urls&collection=' + encodeURIComponent('bad OR *:*'));
    await page.waitForFunction(() => document.querySelector('#scoutro-index-total').textContent === '30');
    check(await page.locator('#scoutro-index-unknown').isVisible() && await page.locator('#scoutro-index-collection').inputValue() === ''
      && !new URL(page.url()).searchParams.has('collection'), 'Unknown collection of the link: all collections, said so' + where);
    check((await get(page, '/scoutro/api/v1/index/browse?collection=' + encodeURIComponent('bad OR *:*'))).status === 400, 'The API refuses a malformed collection' + where);
    await page.goto(base+'/IndexBrowser_p.html?view=urls&collection=visible&q=a.example');
    await page.waitForFunction(() => document.querySelector('#scoutro-index-total').textContent === '28');
    check(!(await page.locator('#scoutro-index-unknown').isVisible()) && await page.locator('#scoutro-index-collection').inputValue() === 'visible', 'Listed collection of the link chosen' + where);
    await page.locator('#scoutro-index-reset').click();
    await page.waitForFunction(() => document.querySelector('#scoutro-index-total').textContent === '30');
    check(await page.locator('#scoutro-index-collection').inputValue() === '', 'Reset clears filter' + where);
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth+1), 'URL view fits' + where);
    if (width < 768) {
      await page.locator('#scoutro-nav-toggle').click();
      check(await page.locator('#scoutro-adminnav').isVisible(), 'Mobile sidebar usable' + where);
      await page.keyboard.press('Escape');
    }
    for (const path of ['WatchWebStructure_p.html','Collage.html','ScoutroAgents_p.html','ScoutroAgentWizard_p.html']) {
      await page.goto(base+'/'+path);
      check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth+1), `${path} responsive` + where);
    }
    check(errors.length === 0, errors.join('; '));
    await context.close();
  }
  console.log(`PASS: ${checks} domain/export/SEO/URL-view checks, five widths, English/German`);
} finally { await browser.close(); }
