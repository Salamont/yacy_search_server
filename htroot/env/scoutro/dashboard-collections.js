/* Scoutro contributors, GPL-2.0-or-later. Dashboard "+" and "−" of the collection section, administrator only:
   "+" opens the "New collection" dialog (collections.js). "−" removes an empty collection created in Scoutro
   (DELETE /scoutro/api/v1/collections/{id}), hides one with pages (POST …/{id}/hide) or, ticked and confirmed with
   its id, deletes its pages (POST …/{id}/delete-pages). Hidden collections are listed below the cards with
   "Show again" (POST …/{id}/show). The server decides; the page is reloaded so the cards show the list. */
(() => {
  const $ = id => document.getElementById(id);
  const tools = $('scoutro-dash-collection-tools'), dialog = $('scoutro-dash-remove-dialog');
  if (!tools || !dialog || !window.ScoutroCollections) return;
  const text = key => dialog.querySelector(`[data-scoutro-dash-label="${key}"]`)?.textContent || key;
  const status = $('scoutro-dash-collection-status'), select = $('scoutro-dash-remove-id'), error = $('scoutro-dash-remove-error');
  const add = $('scoutro-dash-collection-add'), remove = $('scoutro-dash-collection-remove'), save = $('scoutro-dash-remove-save');
  const hint = $('scoutro-dash-remove-hint'), pagesBox = $('scoutro-dash-remove-pages-box'), pages = $('scoutro-dash-remove-pages');
  const confirmBox = $('scoutro-dash-remove-confirm-box'), confirm = $('scoutro-dash-remove-confirm');
  const hiddenBox = $('scoutro-dash-hidden'), hiddenList = $('scoutro-dash-hidden-list');
  const API = '/scoutro/api/v1/collections';
  const KEY = 'scoutro.dashboard.collections';
  let busy = false, entries = [];

  // the outcome of the last change, shown once after the reload
  try {
    const done = sessionStorage.getItem(KEY);
    if (done) { sessionStorage.removeItem(KEY); status.textContent = done; status.hidden = false; }
  } catch (e) { /* no storage: no message */ }
  function reloadWith(message) {
    try { sessionStorage.setItem(KEY, message); } catch (e) { /* the reloaded list shows it anyway */ }
    location.reload();
  }
  function fail(message) { error.textContent = message || ''; error.hidden = !message; }
  function display(entry) { return entry.name && entry.name !== entry.id ? `${entry.name} · ${entry.id}` : entry.id; }
  const count = n => Number(n || 0).toLocaleString();
  const created = entry => (entry.sources || []).includes('created');

  async function load() {
    const response = await fetch(API, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(text('error') + ` (HTTP ${response.status})`);
    entries = ((await response.json()).collections || []).filter(entry => entry && !entry.internal);
    return entries;
  }
  async function post(path, method, body) {
    const response = await fetch(path, { method, credentials: 'same-origin', cache: 'no-store',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(body || {}) });
    return { response, data: await response.json().catch(() => null) };
  }
  function failure(response, data) {
    const failed = data && data.error, code = failed && failed.code;
    if (code === 'collection_in_use') {
      const uses = (failed.details && failed.details.uses) || {};
      fail(`${text(code)} ${Object.entries(uses).map(([kind, n]) => `${n} ${text('uses_' + kind)}`).join(', ')}`);
    } else if (code === 'collection_not_found' || code === 'collection_not_hidden') {
      reloadWith(text(code));
    } else {
      fail(code && text(code) !== code ? text(code) : `${text('error')} (HTTP ${response.status}${code ? ', ' + code : ''})`);
    }
  }

  // Hidden collections: listed below the cards with "Show again"
  function listHidden() {
    if (!hiddenBox || !hiddenList) return;
    const hidden = entries.filter(entry => entry.hidden);
    hiddenList.replaceChildren(...hidden.map(entry => {
      const item = document.createElement('li'), button = document.createElement('button');
      item.append(`${display(entry)} — ${entry.documents > 0 ? text('pages').replace('%1', count(entry.documents)) : text('empty')} `);
      button.type = 'button'; button.className = 'btn btn-default btn-sm'; button.textContent = text('show');
      button.addEventListener('click', async () => {
        if (busy) return;
        busy = true; button.disabled = true;
        try {
          const { response, data } = await post(`${API}/${encodeURIComponent(entry.id)}/show`, 'POST');
          if (response.ok && data && data.shown) {
            const graph = data.graph && data.graph.error ? ' ' + text('graph_failed') : '';
            reloadWith(`${text('shown')} ${display(data.collection)}${graph}`);
            return;
          }
          const code = data && data.error && data.error.code;
          status.textContent = code && text(code) !== code ? text(code) : `${text('error')} (HTTP ${response.status})`;
          status.hidden = false;
        } catch (e) {
          status.textContent = text('error'); status.hidden = false;
        } finally {
          busy = false; button.disabled = false;
        }
      });
      item.append(button);
      return item;
    }));
    hiddenBox.hidden = hidden.length === 0;
  }

  // What "−" does with the chosen collection: remove (empty, created here), hide, or delete its pages
  function plan() {
    const entry = entries.find(e => e.id === select.value);
    if (!entry) return null;
    if (!(entry.documents > 0) && created(entry)) return { entry, mode: 'remove' };
    return { entry, mode: pages.checked ? 'delete' : 'hide' };
  }
  function update() {
    const p = plan();
    pagesBox.hidden = !p || p.mode === 'remove';
    confirmBox.hidden = !p || p.mode !== 'delete';
    if (!p) { hint.textContent = ''; save.textContent = text('button_remove'); save.disabled = true; return; }
    hint.textContent = p.mode === 'remove' ? text('plan_remove') : text('plan_hide').replace('%1', count(p.entry.documents));
    $('scoutro-dash-remove-confirm-label').textContent = text('confirm_label').replace('%1', p.entry.id) + ' ';
    save.textContent = text('button_' + p.mode);
    save.disabled = busy || p.mode === 'delete' && confirm.value.trim() !== p.entry.id;
  }
  async function fill() {
    select.replaceChildren(new Option(text('loading'), ''));
    select.disabled = true; save.disabled = true; pages.checked = false; confirm.value = ''; update();
    await load();
    listHidden();
    // a Discovery profile would crawl into it again; whether anything else still uses it only the server knows
    const visible = entries.filter(entry => !entry.hidden);
    const free = visible.filter(entry => !(entry.sources || []).includes('profile'));
    const kept = visible.filter(entry => (entry.sources || []).includes('profile'));
    select.replaceChildren(new Option(text('choose'), ''));
    free.forEach(entry => select.add(new Option(`${display(entry)} — ${entry.documents > 0
      ? text('pages').replace('%1', count(entry.documents)) : text('empty')}`, entry.id)));
    if (kept.length) {
      const group = document.createElement('optgroup');
      group.label = text('kept');
      kept.forEach(entry => { const option = new Option(`${display(entry)} — ${text('from_profile')}`, entry.id); option.disabled = true; group.append(option); });
      select.append(group);
    }
    select.disabled = free.length === 0;
    update();
    if (!free.length) hint.textContent = text('none');
  }
  function close() { if (dialog.open) dialog.close(); remove.focus(); }

  remove.addEventListener('click', () => {
    fail(''); hint.textContent = '';
    if (dialog.showModal) dialog.showModal(); else dialog.setAttribute('open', '');
    fill().then(() => select.focus()).catch(e => fail(e.message));
  });
  select.addEventListener('change', () => { pages.checked = false; confirm.value = ''; fail(''); update(); });
  pages.addEventListener('change', () => { confirm.value = ''; update(); if (pages.checked) confirm.focus(); });
  confirm.addEventListener('input', update);
  $('scoutro-dash-remove-cancel').addEventListener('click', close);
  save.addEventListener('click', async () => {
    const p = plan();
    if (busy || !p) return;
    busy = true; save.disabled = true; fail('');
    const path = `${API}/${encodeURIComponent(p.entry.id)}`;
    try {
      const { response, data } = p.mode === 'remove' ? await post(path, 'DELETE')
        : p.mode === 'hide' ? await post(path + '/hide', 'POST') : await post(path + '/delete-pages', 'POST', { confirm: confirm.value.trim() });
      if (response.ok && data && (data.removed || data.hidden)) {
        const parts = [`${text(data.removed ? 'removed' : 'hidden')} ${display(data.collection)}`];
        if (p.mode === 'delete') parts.push(text('deleted_pages').replace('%1', count(data.deletedPages)));
        if (data.hidden && p.mode === 'delete') parts.push(text('shared_kept').replace('%1', count(data.keptPages)));
        else if (data.hidden) parts.push(text('kept_pages').replace('%1', count(data.keptPages)));
        if (data.graph && data.graph.error) parts.push(text('graph_failed'));
        reloadWith(parts.join(' '));
        return;
      }
      failure(response, data);
    } catch (e) {
      fail(text('error'));
    } finally {
      busy = false; update();
    }
  });

  window.ScoutroCollections.offerCreate(add, entry => reloadWith(`${text('created')} ${display(entry)}`));
  window.ScoutroCollections.catalog().then(c => {
    tools.hidden = !c.canCreate; remove.hidden = !c.canCreate;
    if (c.canCreate) load().then(listHidden).catch(() => { /* the cards stay; the dialog reports a failure */ });
  });
})();
