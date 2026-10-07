/* Scoutro contributors, GPL-2.0-or-later. The collection choice of the Scoutro pages (package 6.1):
   a real select with the collections of the catalog the signed-in administrator may use, never free text,
   and the "New collection" dialog (env/templates/scoutro/collection-create.template) where creating is allowed. */
(function () {
  'use strict';
  var PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
  var NEW_ID = /^[a-z0-9](?:[a-z0-9]|-(?=[a-z0-9])){1,63}$/;
  var pending = null;
  var names = {};

  // Valid names once each, alphabetically regardless of case.
  function sorted(ids) {
    var seen = {}, out = [];
    (ids || []).forEach(function (id) {
      var name = id == null ? '' : String(id);
      if (PATTERN.test(name) && !seen[name]) { seen[name] = true; out.push(name); }
    });
    return out.sort(function (a, b) {
      var x = a.toLowerCase(), y = b.toLowerCase();
      return x < y ? -1 : x > y ? 1 : a < b ? -1 : a > b ? 1 : 0;
    });
  }

  // The catalog, read once per page view: the selectable collections (never YaCy's internal ones), whether
  // this user may create one and whether it could be read at all. The API answers only an administrator; for
  // anyone else, or when the index cannot be read, the list is empty and nothing can be created.
  function catalog() {
    if (!pending) {
      pending = fetch('/scoutro/api/v1/collections', { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } })
        .then(function (response) { return response.ok ? response.json() : null; })
        .then(function (data) {
          var entries = ((data && data.collections) || []).filter(function (item) { return item && item.selectable !== false && !item.internal; });
          entries.forEach(function (item) { if (item.name && item.name !== item.id) names[item.id] = item.name; });
          return { ids: sorted(entries.map(function (item) { return item.id; })), canCreate: !!(data && data.canCreate), available: !!data };
        })
        .catch(function () { return { ids: [], canCreate: false, available: false }; });
    }
    return pending;
  }
  function load() { return catalog().then(function (c) { return c.ids; }); }
  function reload() { pending = null; return load(); }

  // Fills a select whose first option (empty value) the page provides: then the collections, each with its
  // display name where it has one. Selects the wanted one only if it is listed, else the first option, and
  // ends the loading state.
  function fill(select, ids, wanted) {
    while (select.options.length > 1) select.remove(1);
    ids.forEach(function (id) {
      var option = document.createElement('option');
      option.value = id; option.textContent = names[id] ? names[id] + ' · ' + id : id;
      select.appendChild(option);
    });
    select.value = wanted && ids.indexOf(wanted) >= 0 ? wanted : '';
    select.disabled = false;
    select.removeAttribute('aria-busy');
    return select.value;
  }

  // An id suggestion for a display name, as the server derives it: lower case, umlauts and accents written out,
  // every other character a hyphen ("Mein neues Portal" -> "mein-neues-portal"). The server validates.
  function suggest(name) {
    var s = String(name || '').toLowerCase().replace(/ä/g, 'ae').replace(/ö/g, 'oe').replace(/ü/g, 'ue').replace(/ß/g, 'ss');
    s = s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
    if (s.length > 64) s = s.slice(0, 64).replace(/-+$/, '');
    return s;
  }

  // The "New collection" dialog, set up once per page; any number of buttons open it. A button is shown only
  // when the catalog says this user may create (the server checks again). onCreated(entry, message) of the
  // button that opened it runs after the server confirmed; on any error nothing is added.
  var dialogTarget = null;
  function setUpDialog(dialog) {
    if (dialog.dataset.ready) return;
    dialog.dataset.ready = '1';
    var $ = function (id) { return document.getElementById('scoutro-collection-' + id); };
    var labels = {};
    dialog.querySelectorAll('[data-scoutro-collection-label]').forEach(function (n) { labels[n.dataset.scoutroCollectionLabel] = n.textContent; });
    var edited = false, busy = false;
    function error(text) { $('error').textContent = text || ''; $('error').hidden = !text; }
    function close() { if (dialog.open) dialog.close(); if (dialogTarget) dialogTarget.button.focus(); }
    dialog.openFor = function (target) {
      dialogTarget = target;
      $('form').reset(); edited = false; error('');
      if (dialog.showModal) dialog.showModal(); else dialog.setAttribute('open', '');
      $('name').focus();
    };
    $('name').addEventListener('input', function () { if (!edited) $('id').value = suggest($('name').value); });
    $('id').addEventListener('input', function () { edited = true; });
    $('cancel').addEventListener('click', close);
    $('form').addEventListener('submit', function (event) {
      event.preventDefault();
      if (busy || !dialogTarget) return;
      var name = $('name').value.trim(), id = $('id').value.trim();
      if (!name) { error(labels.name_required); $('name').focus(); return; }
      if (!NEW_ID.test(id)) { error(labels.collection_id_invalid); $('id').focus(); return; }
      busy = true; $('save').disabled = true; error('');
      var body = { id: id, name: name };
      if ($('description').value.trim()) body.description = $('description').value.trim();
      var target = dialogTarget;
      fetch('/scoutro/api/v1/collections', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(body) })
        .then(function (response) {
          return response.json().catch(function () { return null; }).then(function (data) {
            if (!response.ok || !data || !data.collection) {
              var code = data && data.error && data.error.code;
              throw new Error((code && labels[code]) || (response.status === 401 ? labels.unauthorized : labels.error) + ' (HTTP ' + response.status + (code ? ', ' + code : '') + ')');
            }
            return data.collection;
          });
        })
        .then(function (entry) {
          if (entry.name && entry.name !== entry.id) names[entry.id] = entry.name;
          close();
          target.onCreated(entry, labels.created || '');
        })
        .catch(function (e) { error(e.message); })
        .then(function () { busy = false; $('save').disabled = false; });
    });
  }
  function offerCreate(button, onCreated) {
    var dialog = document.getElementById('scoutro-collection-dialog');
    if (!button || !dialog) return;
    setUpDialog(dialog);
    catalog().then(function (c) { button.hidden = !c.canCreate; button.disabled = !c.canCreate; });
    button.addEventListener('click', function () { dialog.openFor({ button: button, onCreated: onCreated }); });
  }

  window.ScoutroCollections = { PATTERN: PATTERN, NEW_ID: NEW_ID, sorted: sorted, catalog: catalog, load: load, reload: reload,
    fill: fill, suggest: suggest, offerCreate: offerCreate };
})();
