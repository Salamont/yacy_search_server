/*
@licstart  The following is the entire license notice for the
JavaScript code in this page.

Scoutro search page. Scoutro is an independent community project based on YaCy.
Copyright (C) 2026 Scoutro contributors

This program is free software; you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation; either version 2 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

@licend  The above is the entire license notice
for the JavaScript code in this page.
*/

/* Search within the caller's collections through /scoutro/api/v1/search (docs/SCOUTRO_USERS_ACCESS.md, section 6).
   The list of collections only helps choosing; the server decides the scope of every request. */
(function () {
  'use strict';

  var API = '/scoutro/api/v1/';
  var LIMIT = 10;
  var state = { q: '', collection: '', offset: 0, total: 0, approximate: false, count: 0 };

  function $(id) { return document.getElementById(id); }

  function label(key, value) {
    var el = document.querySelector('[data-ssr-label="' + key + '"]');
    var text = el ? el.textContent : key;
    return value === undefined ? text : text.replace('%1', String(value));
  }

  function status(text, kind) {
    var box = $('ssr-message');
    box.textContent = text || '';
    box.hidden = !text;
    box.className = 'scoutro-status' + (kind ? ' scoutro-status-' + kind : '');
    if (kind === 'error') box.focus();
  }

  function get(path) {
    return fetch(API + path, { credentials: 'same-origin', cache: 'no-store', headers: { 'Accept': 'application/json' } })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, status: r.status, json: j }; });
      });
  }

  function errorText(r) {
    var code = r.json && r.json.error && r.json.error.code;
    if (r.status === 401) return label('unauthorized');
    if (code && document.querySelector('[data-ssr-label="' + code + '"]')) return label(code);
    return label('failed');
  }

  function number(n) {
    try { return Number(n).toLocaleString(document.documentElement.lang || undefined); } catch (e) { return String(n); }
  }

  function loadCollections() {
    return get('collections').then(function (r) {
      var select = $('ssr-collection');
      if (!r.ok) {
        status(errorText(r), 'error');
        return false;
      }
      var list = (r.json.collections || []).filter(function (c) { return c.selectable !== false && !c.internal; });
      if (!list.length) {
        status(label('no_collections'), 'warn');
        $('ssr-q').disabled = true;
        $('ssr-submit').disabled = true;
        select.disabled = true;
        return false;
      }
      list.forEach(function (c) {
        var option = document.createElement('option');
        option.value = c.id;
        option.textContent = c.name && c.name !== c.id ? c.name + ' · ' + c.id : c.id;
        select.appendChild(option);
      });
      if (list.length === 1) {
        // one collection: nothing to choose
        select.value = list[0].id;
        select.options[0].hidden = true;
      }
      return true;
    }, function () { status(label('failed'), 'error'); return false; });
  }

  function render(result) {
    var list = $('ssr-list');
    list.textContent = '';
    var items = result.results || [];
    state.total = result.total || 0;
    state.approximate = !!result.totalIsApproximate;
    state.count = items.length;
    items.forEach(function (item) {
      var li = document.createElement('li');
      li.className = 'scoutro-result';
      var a = document.createElement('a');
      a.href = item.url;
      a.rel = 'noopener noreferrer';
      a.textContent = item.title || item.url;
      var h = document.createElement('h3');
      h.appendChild(a);
      li.appendChild(h);
      var meta = document.createElement('p');
      meta.className = 'scoutro-result-url';
      meta.textContent = item.url;
      li.appendChild(meta);
      if (item.snippet) {
        var p = document.createElement('p');
        p.className = 'scoutro-result-snippet';
        p.textContent = item.snippet;
        li.appendChild(p);
      }
      list.appendChild(li);
    });
    $('ssr-results').hidden = !items.length;
    $('ssr-count').textContent = items.length ? (state.approximate ? label('count_approx', number(state.total)) : label('count', number(state.total))) : '';
    $('ssr-prev').disabled = state.offset === 0;
    $('ssr-next').disabled = items.length < LIMIT || state.offset + LIMIT >= (state.approximate ? 100 : state.total);
    status(items.length ? '' : label('empty'), items.length ? '' : 'warn');
  }

  function search(push) {
    if (!state.q) {
      status(label('enter_words'), 'warn');
      $('ssr-results').hidden = true;
      return;
    }
    var params = new URLSearchParams({ q: state.q, limit: String(LIMIT), offset: String(state.offset) });
    if (state.collection) params.set('collection', state.collection);
    if (push) {
      var url = new URLSearchParams({ q: state.q });
      if (state.collection) url.set('collection', state.collection);
      if (state.offset) url.set('offset', String(state.offset));
      history.pushState(null, '', 'scoutro-search.html?' + url.toString());
    }
    status(label('loading'));
    $('ssr-submit').disabled = true;
    get('search?' + params.toString()).then(function (r) {
      $('ssr-submit').disabled = false;
      if (!r.ok) {
        $('ssr-results').hidden = true;
        status(errorText(r), 'error');
        return;
      }
      render(r.json);
    }, function () {
      $('ssr-submit').disabled = false;
      status(label('failed'), 'error');
    });
  }

  function fromUrl() {
    var params = new URLSearchParams(window.location.search);
    state.q = (params.get('q') || params.get('query') || '').trim();
    state.offset = Math.max(0, parseInt(params.get('offset') || '0', 10) || 0);
    var wanted = params.get('collection') || '';
    var select = $('ssr-collection');
    var known = Array.prototype.some.call(select.options, function (o) { return o.value === wanted; });
    if (wanted && known) select.value = wanted;
    state.collection = select.value;
    $('ssr-q').value = state.q;
  }

  $('ssr-form').addEventListener('submit', function (event) {
    event.preventDefault();
    state.q = $('ssr-q').value.trim();
    state.collection = $('ssr-collection').value;
    state.offset = 0;
    search(true);
  });
  $('ssr-prev').addEventListener('click', function () {
    state.offset = Math.max(0, state.offset - LIMIT);
    search(true);
  });
  $('ssr-next').addEventListener('click', function () {
    state.offset += LIMIT;
    search(true);
  });
  window.addEventListener('popstate', function () {
    fromUrl();
    if (state.q) search(false);
  });

  loadCollections().then(function (ready) {
    if (!ready) return;
    fromUrl();
    if (state.q) search(false);
    else $('ssr-q').focus();
  });
})();
