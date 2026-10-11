/* Scoutro contributors, GPL-2.0-or-later. Dashboard "+" and "−" of the collection section, administrator only:
   "+" opens the "New collection" dialog (collections.js), "−" removes an empty collection created in Scoutro
   (DELETE /scoutro/api/v1/collections/{id}). The server decides; the page is reloaded so the cards show the list. */
(() => {
  const $ = id => document.getElementById(id);
  const tools = $('scoutro-dash-collection-tools'), dialog = $('scoutro-dash-remove-dialog');
  if (!tools || !dialog || !window.ScoutroCollections) return;
  const text = key => dialog.querySelector(`[data-scoutro-dash-label="${key}"]`)?.textContent || key;
  const status = $('scoutro-dash-collection-status'), select = $('scoutro-dash-remove-id'), error = $('scoutro-dash-remove-error');
  const add = $('scoutro-dash-collection-add'), remove = $('scoutro-dash-collection-remove'), save = $('scoutro-dash-remove-save');
  const KEY = 'scoutro.dashboard.collections';
  let busy = false;

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

  // Removable here: created in Scoutro, no page in the index, no Discovery profile. Whether anything still uses it
  // only the server knows; it answers collection_in_use.
  function reason(entry) {
    const sources = entry.sources || [];
    if (entry.documents > 0) return text('pages').replace('%1', Number(entry.documents).toLocaleString());
    if (sources.includes('profile')) return text('from_profile');
    if (!sources.includes('created')) return text('from_index');
    return '';
  }
  async function fill() {
    select.replaceChildren(new Option(text('loading'), ''));
    select.disabled = true; save.disabled = true;
    const response = await fetch('/scoutro/api/v1/collections', { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(text('error') + ` (HTTP ${response.status})`);
    const entries = ((await response.json()).collections || []).filter(entry => entry && !entry.internal);
    const free = entries.filter(entry => !reason(entry)), kept = entries.filter(entry => reason(entry));
    select.replaceChildren(new Option(text('choose'), ''));
    free.forEach(entry => select.add(new Option(display(entry), entry.id)));
    if (kept.length) {
      const group = document.createElement('optgroup');
      group.label = text('kept');
      kept.forEach(entry => { const option = new Option(`${display(entry)} — ${reason(entry)}`, entry.id); option.disabled = true; group.append(option); });
      select.append(group);
    }
    select.disabled = free.length === 0; save.disabled = free.length === 0;
    $('scoutro-dash-remove-hint').textContent = free.length ? '' : text('none');
  }
  function close() { if (dialog.open) dialog.close(); remove.focus(); }

  remove.addEventListener('click', () => {
    fail(''); $('scoutro-dash-remove-hint').textContent = '';
    if (dialog.showModal) dialog.showModal(); else dialog.setAttribute('open', '');
    fill().then(() => select.focus()).catch(e => fail(e.message));
  });
  $('scoutro-dash-remove-cancel').addEventListener('click', close);
  save.addEventListener('click', async () => {
    const id = select.value;
    if (busy || !id) return;
    busy = true; save.disabled = true; fail('');
    try {
      const response = await fetch('/scoutro/api/v1/collections/' + encodeURIComponent(id), { method: 'DELETE', credentials: 'same-origin',
        cache: 'no-store', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: '{}' });
      const data = await response.json().catch(() => null);
      if (response.ok && data && data.removed) { reloadWith(`${text('removed')} ${display(data.collection)}`); return; }
      const failure = data && data.error, code = failure && failure.code;
      if (code === 'collection_in_use') {
        const uses = (failure.details && failure.details.uses) || {};
        fail(`${text(code)} ${Object.entries(uses).map(([kind, count]) => `${count} ${text('uses_' + kind)}`).join(', ')}`);
      } else if (code === 'collection_not_found') {
        reloadWith(text(code));
      } else {
        fail(code && text(code) !== code ? text(code) : `${text('error')} (HTTP ${response.status}${code ? ', ' + code : ''})`);
      }
    } catch (e) {
      fail(text('error'));
    } finally {
      busy = false; save.disabled = select.disabled;
    }
  });

  window.ScoutroCollections.offerCreate(add, entry => reloadWith(`${text('created')} ${display(entry)}`));
  window.ScoutroCollections.catalog().then(c => { tools.hidden = !c.canCreate; remove.hidden = !c.canCreate; });
})();
