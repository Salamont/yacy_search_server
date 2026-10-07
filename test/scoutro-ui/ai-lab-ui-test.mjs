#!/usr/bin/env node
/* Scoutro contributors, GPL-2.0-or-later. Run only from disposable seo-live-smoke.py (ai.shield.show-chat-link unset).
 * AI Lab navigation, chat surface (scope, errors, mobile composer) and the chat tool page (labels, status, saving). */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
const {chromium} = createRequire(import.meta.url)('playwright');
const base = process.env.SCOUTRO_URL, shots = process.env.SCOUTRO_SCREENSHOTS;
assert(base && new URL(base).hostname === '127.0.0.1', 'Disposable loopback fixture required');
const browser = await chromium.launch({executablePath:process.env.SCOUTRO_CHROMIUM_PATH, args:['--no-proxy-server']});
let checks = 0;
function check(value, label) { assert(value, label); checks++; }
const TOOLS = ['datetime','date_math','calculator','number_parser','unit_converter','http_json','table_ops','update_plan','self_reflect','chitchat','prompt_to_mermaid','mermaid_to_ascii','search','wikipedia_link_creator','webfetch'];
const TEXT = {
  en: {lab: 'AI Lab', overview: 'AI Lab overview', tools: 'Chat tools', all: 'all collections', allOption: 'All collections', datetime: 'Date & time', webfetch: 'Fetch web page', http_json: 'Query JSON API',
       limit: 'Maximum calls per answer', unreleased: 'Not released', released: 'Released for the chat', disabled: 'Deactivated', admin: 'No YaCy administrator permission',
       server: 'The server could not create an answer', offline: 'The Scoutro server cannot be reached', failed: 'The answer could not be created:'},
  de: {lab: 'KI-Labor', overview: 'KI-Labor-Übersicht', tools: 'Chat-Werkzeuge', all: 'alle Collections', allOption: 'Alle Collections', datetime: 'Datum & Uhrzeit', webfetch: 'Webseite abrufen', http_json: 'JSON-API abfragen',
       limit: 'Maximale Aufrufe pro Antwort', unreleased: 'Nicht freigegeben', released: 'Für Chat freigegeben', disabled: 'Deaktiviert', admin: 'Keine YaCy-Administratorberechtigung',
       server: 'Der Server konnte keine Antwort erstellen', offline: 'Der Scoutro-Server ist nicht erreichbar', failed: 'Die Antwort konnte nicht erstellt werden:'},
};
const fits = page => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1);
try {
  // visible is not accessible: the AI Shield and the admin pages still decide
  const anonymous = await browser.newContext();
  check((await anonymous.request.get(base + '/ToolsConfig_p.html')).status() === 401, 'Tool page requires admin');
  const remote = await anonymous.request.post(base + '/v1/chat/completions', {headers: {'X-Forwarded-For': '198.51.100.23'}, data: {messages: [{role: 'user', content: 'Hallo'}], stream: true}});
  check(remote.status() === 401 && (await remote.json()).error.code === 'admin_required', 'Remote chat without login refused by the AI Shield: ' + remote.status());
  const publicPage = await anonymous.request.get(base + '/index.html');
  check(!(await publicPage.text()).includes('id="header_chat"'), 'Public search page keeps the front page link setting (off)');
  // a guest (a remote client without administrator login) sees only released collections, none by default
  const guestNames = async () => {
    const guest = await browser.newContext({extraHTTPHeaders: {'X-Forwarded-For': '198.51.100.24'}});
    const guestPage = await guest.newPage();
    await guestPage.goto(base + '/yacychat.html?collection=secret');
    const names = await guestPage.locator('#collectionSelect option').evaluateAll(list => list.map(o => o.value));
    const value = await guestPage.locator('#collectionSelect').inputValue(), html = await guestPage.content();
    await guest.close();
    return {names, value, html};
  };
  let seen = await guestNames();
  check(JSON.stringify(seen.names) === JSON.stringify(['']) && seen.value === '' && !/<option value="(secret|visible)"/.test(seen.html),
    'Guest: only all collections, no collection name in the page: ' + JSON.stringify(seen.names));
  // the AI Shield page releases collections for guests by ticking them, never as free text
  const admin = await browser.newContext({httpCredentials: {username: 'admin', password: 'yacy'}, extraHTTPHeaders: {'Accept-Language': 'en'}});
  const shield = await admin.newPage();
  await shield.goto(base + '/AIShield_p.html');
  const boxes = await shield.locator('#guestCollections input').evaluateAll(list => list.map(i => [i.type, i.name, i.checked]));
  const catalogIds = await shield.evaluate(async () => (await (await fetch('/scoutro/api/v1/collections', { credentials: 'same-origin' })).json()).collections.filter(c => c.selectable && !c.internal).map(c => c.id).sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : a.toLowerCase() > b.toLowerCase() ? 1 : a < b ? -1 : a > b ? 1 : 0));
  check(catalogIds.includes('secret') && catalogIds.includes('visible') && JSON.stringify(boxes) === JSON.stringify(catalogIds.map(id => ['checkbox', 'guest-collection.' + id, false])),
    'AI Shield lists every collection of the catalog as an unticked box: ' + JSON.stringify(boxes));
  await shield.locator('input[name="guest-collection.visible"]').check();
  await Promise.all([shield.waitForNavigation(), shield.locator('#shieldForm button[type="submit"]').click()]);
  check(await shield.locator('input[name="guest-collection.visible"]').isChecked() && !(await shield.locator('input[name="guest-collection.secret"]').isChecked()), 'Released collection stored');
  seen = await guestNames();
  check(JSON.stringify(seen.names) === JSON.stringify(['', 'visible']) && seen.value === '' && !/<option value="secret"/.test(seen.html),
    'Guest: the released collection only, never another one, also not from the link: ' + JSON.stringify(seen.names));
  await shield.locator('input[name="guest-collection.visible"]').uncheck();
  await Promise.all([shield.waitForNavigation(), shield.locator('#shieldForm button[type="submit"]').click()]);
  check(JSON.stringify((await guestNames()).names) === JSON.stringify(['']), 'Release withdrawn');
  await admin.close();
  await anonymous.close();

  for (const language of ['en','de']) for (const width of [390, 1280]) {
    const where = ` (${language}/${width})`, T = TEXT[language], mobile = width < 768;
    const context = await browser.newContext({viewport:{width,height:900}, isMobile: mobile, hasTouch: mobile, locale: language,
      httpCredentials:{username:'admin',password:'yacy'}, extraHTTPHeaders:{'Accept-Language':language}});
    const page = await context.newPage(); const errors = [];
    page.on('pageerror', e => errors.push(e.message));

    // navigation: the chat is in the AI Lab group although the front page link is off
    await page.goto(base + '/IndexBrowser_p.html');
    if (mobile) { await page.locator('#scoutro-nav-toggle').click(); await page.locator('#scoutro-adminnav').waitFor({state:'visible'}); }
    const group = page.locator('#scoutro-ai');
    check((await group.locator('h3').textContent()).trim() === T.lab, 'AI Lab group' + where);
    check(await group.locator('a[href="yacychat.html"]').isVisible(), 'Chat visible in the AI Lab navigation' + where);
    check((await group.locator('a[href="AILab.html"]').textContent()).trim() === T.overview, 'AI Lab overview entry' + where);
    check((await group.locator('a[href="ToolsConfig_p.html"]').textContent()).trim() === T.tools, 'Chat tools entry' + where);
    check(await page.locator('#header_chat').count() === 1, 'Chat button in the admin bar' + where);
    if (mobile) await page.keyboard.press('Escape');

    // chat surface: title, scope, collection list, mobile composer
    await page.goto(base + '/yacychat.html');
    check((await page.locator('.scoutro-chat-header h1').textContent()) === 'Scoutro Chat', 'Chat title' + where);
    const current = () => page.locator('#collectionCurrentValue').textContent();
    const select = page.locator('#collectionSelect');
    check(await current() === T.all, 'Current scope: whole index' + where);
    // package 6.1: a real select of the collections the client may use, no free text, no suggestion list
    check(await select.evaluate(e => e.tagName) === 'SELECT' && await page.locator('#collectionInput, .collection-control input, datalist').count() === 0,
      'Collection as a real select, no text field' + where);
    const options = await select.locator('option').evaluateAll(list => list.map(o => [o.value, o.textContent.trim()]));
    check(options[0][0] === '' && options[0][1] === T.allOption && await select.inputValue() === '', 'First option: all collections, chosen' + where);
    const catalog = await page.evaluate(async () => (await (await fetch('/scoutro/api/v1/collections', { credentials: 'same-origin' })).json()).collections.filter(c => c.selectable && !c.internal).map(c => c.id).sort((a, b) => a.toLowerCase() < b.toLowerCase() ? -1 : a.toLowerCase() > b.toLowerCase() ? 1 : a < b ? -1 : a > b ? 1 : 0));
    check(catalog.includes('secret') && catalog.includes('visible') && JSON.stringify(options.slice(1).map(o => o[0])) === JSON.stringify(catalog),
      'The administrator gets every collection of the catalog, sorted: ' + JSON.stringify(options) + where);
    await select.selectOption('visible');
    check(await current() === 'visible', 'Scope follows the selection' + where);
    check(await page.evaluate(() => localStorage.getItem('scoutro.chat.collection')) === 'visible', 'Choice remembered' + where);
    await page.reload();
    check(await select.inputValue() === 'visible' && await current() === 'visible', 'Remembered choice applied' + where);
    await page.evaluate(() => localStorage.setItem('scoutro.chat.collection', 'gone-web'));
    await page.reload();
    check(await select.inputValue() === '' && await current() === T.all && await page.evaluate(() => localStorage.getItem('scoutro.chat.collection')) === null,
      'A remembered collection that is not listed: all collections, forgotten' + where);
    await page.goto(base + '/yacychat.html?collection=unknown-web');
    check(await select.inputValue() === '' && await current() === T.all, 'Unknown collection of the link: all collections' + where);
    await page.goto(base + '/yacychat.html?collection=secret');
    check(await select.inputValue() === 'secret' && await current() === 'secret', 'Listed collection of the link chosen' + where);
    let sent = null;
    await page.route('**/v1/chat/completions', route => { sent = route.request().postDataJSON(); route.fulfill({status: 503, contentType: 'application/json', body: JSON.stringify({error: {code: 'no_chat_model', message: 'x'}})}); });
    await page.locator('#userInput').fill('Welche Seiten?');
    await page.locator('#sendButton').click();
    await page.waitForFunction(() => document.querySelectorAll('#chatMessages .chat-turn.system').length > 0);
    await page.unroute('**/v1/chat/completions');
    check(sent && sent.collection === 'secret', 'The question is asked in the chosen collection' + where);
    await select.selectOption('');
    check(await current() === T.all && await page.evaluate(() => localStorage.getItem('scoutro.chat.collection')) === null, 'All collections again' + where);
    await page.goto(base + '/yacychat.html');
    if (mobile) check(await page.locator('#chatForm').evaluate(form => getComputedStyle(form).position === 'sticky'), 'Question field stays reachable on mobile' + where);
    check(await fits(page), 'Chat fits the viewport' + where);

    // errors are explained, never shown raw
    const ask = async (text, reply) => {
      await page.route('**/v1/chat/completions', reply);
      const before = await page.locator('#chatMessages .chat-turn.system').count();
      await page.locator('#userInput').fill(text);
      await page.locator('#sendButton').click();
      await page.waitForFunction(n => document.querySelectorAll('#chatMessages .chat-turn.system').length > n, before);
      await page.unroute('**/v1/chat/completions');
      return page.locator('#chatMessages .chat-turn.system').last().innerText();
    };
    let notice = await ask('Frage 1', route => route.fulfill({status: 401, contentType: 'application/json', body: JSON.stringify({error: {code: 'admin_required', message: 'internal admin text'}})}));
    check(notice.includes(T.failed) && notice.includes(T.admin), 'Missing permission explained: ' + notice + where);
    notice = await ask('Frage 2', route => route.fulfill({status: 500, contentType: 'application/json', body: JSON.stringify({error: {message: 'java.lang.NullPointerException at net.yacy.Secret'}})}));
    check(notice.includes(T.server) && notice.includes('HTTP 500') && !notice.includes('NullPointerException'), 'Server error without internals: ' + notice + where);
    notice = await ask('Frage 3', route => route.abort('connectionrefused'));
    check(notice.includes(T.offline), 'Unreachable server explained: ' + notice + where);
    check(await page.locator('#chatMessages .chat-turn.assistant').count() === 0, 'Failed answers leave no waiting placeholder' + where);
    check(await fits(page), 'Chat with notices fits' + where);
    if (shots) { fs.mkdirSync(shots, {recursive: true}); await page.screenshot({path: `${shots}/chat-${language}-${width}.png`, fullPage: true}); }

    // tool page: names, explanations, status
    await page.goto(base + '/ToolsConfig_p.html');
    check(await page.locator('.tool-card[data-tool]').count() === TOOLS.length, 'All tools listed' + where);
    for (const tool of TOOLS) {
      const card = page.locator(`.tool-card[data-tool="${tool}"]`);
      const label = (await card.locator('.tool-label').textContent()).trim();
      check(label && label !== tool, `Readable name for ${tool}: ${label}` + where);
      check((await card.locator('.tool-name').textContent()) === tool, `Technical name kept small for ${tool}` + where);
      for (const part of ['.tool-what', '.tool-example span', '.tool-safety span']) check((await card.locator(part).textContent()).trim().length > 10, `${part} for ${tool}` + where);
      check((await card.locator('.tool-limit > label').textContent()) === T.limit, 'Limit label' + where);
      check((await card.locator('.tool-status').textContent()) === T.unreleased, `Unreleased by default: ${tool}` + where);
    }
    for (const tool of ['datetime', 'webfetch', 'http_json']) check((await page.locator(`.tool-card[data-tool="${tool}"] .tool-label`).textContent()) === T[tool], `Name of ${tool}` + where);
    check(await fits(page), 'Tool page fits' + where);
    if (shots) await page.screenshot({path: `${shots}/tools-${language}-${width}.png`, fullPage: true});
    check(errors.length === 0, errors.join('; ') + where);
    await context.close();
  }

  // saving: same form fields and values as before, 0 stays deactivated
  {
    const context = await browser.newContext({viewport:{width:1280,height:900}, httpCredentials:{username:'admin',password:'yacy'}, extraHTTPHeaders:{'Accept-Language':'en'}});
    const page = await context.newPage(); const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base + '/ToolsConfig_p.html');
    const descriptions = Object.fromEntries(await page.locator('textarea.tool-description').evaluateAll(a => a.map(t => [t.name, t.value])));
    const card = tool => page.locator(`.tool-card[data-tool="${tool}"]`);
    const webfetch = card('webfetch');
    const original = await webfetch.locator('.max-calls-input').inputValue();
    await webfetch.locator('.toggle-button').click();
    check(await webfetch.locator('.max-calls-input').inputValue() === '0' && (await webfetch.locator('.tool-status').textContent()).startsWith(TEXT.en.disabled), 'Deactivate sets 0');
    check((await webfetch.locator('.tool-status').textContent()).includes('not saved yet'), 'Unsaved change marked');
    await webfetch.locator('.toggle-button').click();
    check(await webfetch.locator('.max-calls-input').inputValue() === original, 'Activate restores the previous value');
    await card('datetime').locator('input[type=checkbox]').check();
    await card('datetime').locator('.max-calls-input').fill('2');
    check((await card('datetime').locator('.tool-status').textContent()).startsWith(TEXT.en.released), 'Released and above 0: released');
    await card('calculator').locator('input[type=checkbox]').check();
    await card('calculator').locator('.max-calls-input').fill('0');
    check((await card('calculator').locator('.tool-status').textContent()).startsWith(TEXT.en.disabled), 'Released with 0: deactivated');
    let posted = null;
    page.on('request', r => { if (r.method() === 'POST' && r.url().includes('/ToolsConfig_p.html')) posted = new URLSearchParams(r.postData()); });
    await Promise.all([page.waitForNavigation(), page.locator('button.tool-save').click()]);
    check(posted && posted.get('save') === '1', 'Form posted');
    for (const tool of TOOLS) {
      check(posted.has(`ai.tools.${tool}.maxCallsPerTurn`) && posted.get(`ai.tools.${tool}.description`) === descriptions[`ai.tools.${tool}.description`], `Unchanged field names and description for ${tool}`);
      check(posted.get(`ai.tools.${tool}.enabled`) === (['datetime', 'calculator'].includes(tool) ? 'true' : null), `Release field for ${tool}`);
    }
    check(posted.get('ai.tools.datetime.maxCallsPerTurn') === '2' && posted.get('ai.tools.calculator.maxCallsPerTurn') === '0', 'Limits posted as entered');
    check(await page.locator('.alert-success').isVisible(), 'Saved');
    check(await card('datetime').locator('input[type=checkbox]').isChecked() && await card('datetime').locator('.max-calls-input').inputValue() === '2', 'Release and limit stored');
    check((await card('datetime').locator('.tool-status').textContent()) === TEXT.en.released, 'Stored status: released');
    check(await card('calculator').locator('.max-calls-input').inputValue() === '0' && (await card('calculator').locator('.tool-status').textContent()) === TEXT.en.disabled, '0 stays deactivated after saving');
    // back to the defaults of the fixture
    await card('datetime').locator('input[type=checkbox]').uncheck();
    await card('datetime').locator('.max-calls-input').fill('1');
    await card('calculator').locator('input[type=checkbox]').uncheck();
    await card('calculator').locator('.max-calls-input').fill('10');
    await Promise.all([page.waitForNavigation(), page.locator('button.tool-save').click()]);
    check(!(await page.locator('.tool-card input[type=checkbox]:checked').count()), 'Releases withdrawn again');
    check(errors.length === 0, errors.join('; '));
    await context.close();
  }
  console.log(`PASS: ${checks} AI Lab navigation, chat and tool checks, English/German, 390/1280`);
} finally { await browser.close(); }
