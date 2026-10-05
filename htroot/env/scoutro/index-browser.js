/* Scoutro contributors, GPL-2.0-or-later. Read-only browser using authorized API actions. */
(function () {
  'use strict';
  var form = document.getElementById('scoutro-index-form');
  if (!form) return;
  var $ = function (id) { return document.getElementById(id); };
  var query = $('scoutro-index-query'), collection = $('scoutro-index-collection'), sort = $('scoutro-index-sort');
  var error = $('scoutro-index-error'), active = $('scoutro-index-active'), status = $('scoutro-index-status');
  var labels = {};
  document.querySelectorAll('#scoutro-domain-labels [data-label]').forEach(function (n) { labels[n.dataset.label] = n.textContent; });
  function t(key) { return labels[key] || key; }
  function message(id) { return $(id).textContent; }
  var initial = new URLSearchParams(location.search);
  var view = initial.get('view') === 'urls' ? 'urls' : 'domains';
  var offset = Math.max(0, parseInt(initial.get('offset') || '0', 10) || 0);
  if (initial.get('sort') === 'pages') sort.value = 'pages';
  var limit = view === 'urls' ? 50 : 25, generation = 0, exportTotal = null;
  var language = document.documentElement.lang || undefined;

  function setView() {
    $('scoutro-domain-view').hidden = view !== 'domains';
    $('scoutro-url-view').hidden = view !== 'urls';
    $('scoutro-index-sort-field').hidden = view !== 'domains';
    $('scoutro-export-toggle').hidden = view !== 'domains';
    if (view !== 'domains') $('scoutro-export').hidden = true;
    $('scoutro-index-view-domains').setAttribute('aria-current', view === 'domains' ? 'page' : 'false');
    $('scoutro-index-view-urls').setAttribute('aria-current', view === 'urls' ? 'page' : 'false');
    viewLinks();
  }
  function filterParams() {
    var params = new URLSearchParams();
    if (query.value.trim()) params.set('q', query.value.trim());
    if (collection.value.trim()) params.set('collection', collection.value.trim());
    return params;
  }
  function viewLinks() {
    var params = filterParams();
    $('scoutro-index-view-domains').href = 'IndexBrowser_p.html' + (params.toString() ? '?' + params : '');
    params.set('view', 'urls');
    $('scoutro-index-view-urls').href = 'IndexBrowser_p.html?' + params;
  }
  function pageUrl() {
    var params = filterParams();
    if (view === 'urls') params.set('view', 'urls');
    else if (sort.value !== 'host') params.set('sort', sort.value);
    if (offset) params.set('offset', String(offset));
    // Preserve filter state in the page URL, including collection during host searches.
    history.replaceState(null, '', 'IndexBrowser_p.html' + (params.toString() ? '?' + params : ''));
    viewLinks();
  }
  function seoLink(host, name) {
    var params = new URLSearchParams({ host: host });
    if (name) params.set('collection', name);
    return 'ScoutroSEO_p.html?' + params;
  }
  function date(value) {
    if (!value) return null;
    try { return new Date(value).toLocaleString(language, { dateStyle: 'medium', timeStyle: 'short' }); } catch (_) { return value; }
  }
  function node(tag, text, className) {
    var n = document.createElement(tag);
    if (text != null) n.textContent = text;
    if (className) n.className = className;
    return n;
  }
  function fact(list, label, value) {
    if (value == null || value === '') return;
    var row = node('div'), dd = node('dd');
    row.appendChild(node('dt', label));
    if (value instanceof Node) dd.appendChild(value); else dd.textContent = value;
    row.appendChild(dd);
    list.appendChild(row);
  }
  function external(url) {
    if (!/^https?:\/\//i.test(url || '')) return url || null;
    var a = node('a', url); a.href = url; a.target = '_blank'; a.rel = 'noopener noreferrer';
    return a;
  }
  function domainCard(item) {
    var li = node('li', null, 'scoutro-domain');
    var head = node('div', null, 'scoutro-domain-head');
    var host = node('a', item.host, 'scoutro-domain-host');
    host.href = seoLink(item.host, item.collection); host.title = t('seo_title');
    head.appendChild(host);
    head.appendChild(node('span', item.collection || t('no_collection'), 'scoutro-domain-collection' + (item.collection ? '' : ' is-empty')));
    li.appendChild(head);
    var pages = node('p', null, 'scoutro-domain-pages');
    pages.appendChild(node('strong', Number(item.indexed_pages).toLocaleString(language)));
    pages.appendChild(document.createTextNode(' ' + t(item.indexed_pages === 1 ? 'pages_one' : 'pages_many')));
    li.appendChild(pages);
    li.appendChild(node('p', item.title || t('no_title'), 'scoutro-domain-title' + (item.title ? '' : ' is-empty')));
    if (item.description) li.appendChild(node('p', item.description, 'scoutro-domain-description'));
    var facts = node('dl', null, 'scoutro-domain-facts');
    fact(facts, t('website'), item.website ? external(item.website) : null);
    if (item.start_url && item.start_url !== item.website) fact(facts, t('start_url'), external(item.start_url));
    fact(facts, t('last_loaded'), date(item.last_loaded));
    if (item.crawl_status) {
      fact(facts, t('crawl_status'), node('span', t('status_' + item.crawl_status), 'scoutro-badge scoutro-status-' + item.crawl_status));
      fact(facts, t('last_crawled'), date(item.last_crawled));
    }
    if (item.classification) {
      var verdict = node('span', t('verdict_' + item.classification.verdict), 'scoutro-badge scoutro-verdict-' + item.classification.verdict);
      var box = node('span');
      box.appendChild(verdict);
      if (item.classification.confidence != null)
        box.appendChild(document.createTextNode(' ' + t('confidence') + ' ' + Math.round(item.classification.confidence * 100) + ' %'));
      fact(facts, t('classification'), box);
    }
    if (item.discovery) {
      var parts = [item.discovery.profile, item.discovery.source, item.discovery.region].filter(Boolean);
      fact(facts, t('discovery'), parts.length ? parts.join(' · ') : null);
    }
    li.appendChild(facts);
    var actions = node('div', null, 'scoutro-domain-actions');
    var seo = node('a', t('seo'), 'btn btn-primary btn-sm');
    seo.href = seoLink(item.host, item.collection);
    actions.appendChild(seo);
    li.appendChild(actions);
    return li;
  }
  function invalidFilter() {
    var name = collection.value.trim();
    if (name && !/^[A-Za-z0-9_-]{1,64}$/.test(name)) return message('scoutro-index-invalid');
    return null;
  }
  function failure(current, failureMessage) {
    if (current !== generation) return;
    error.textContent = failureMessage;
    error.hidden = false;
    status.textContent = '';
  }
  async function readJson(url) {
    var response = await fetch(url, { credentials: 'same-origin', cache: 'no-store' });
    var body = null;
    try { body = await response.json(); } catch (_) { body = null; }
    if (!response.ok) {
      var code = body && body.error && body.error.code;
      var err = new Error(code === 'invalid_request' && body.error.details && body.error.details.field === 'q'
        ? message('scoutro-index-invalid-host') : message('scoutro-index-unavailable') + ' (HTTP ' + response.status + (code ? ', ' + code : '') + ')');
      throw err;
    }
    return body;
  }
  async function loadDomains() {
    var current = ++generation, list = $('scoutro-domain-list');
    error.hidden = true; list.replaceChildren();
    $('scoutro-domain-empty').hidden = true;
    $('scoutro-domain-prev').disabled = $('scoutro-domain-next').disabled = true;
    $('scoutro-domain-total').textContent = '—'; $('scoutro-domain-range').textContent = '';
    status.textContent = t('loading');
    exportTotal = null; updateExport();
    var invalid = invalidFilter();
    if (invalid) return failure(current, invalid);
    var params = filterParams();
    params.set('sort', sort.value); params.set('offset', String(offset)); params.set('limit', String(limit));
    try {
      var data = await readJson('/scoutro/api/v1/index/domains?' + params);
      if (current !== generation) return;
      status.textContent = '';
      data.items.forEach(function (item) { list.appendChild(domainCard(item)); });
      var hosts = data.total_hosts || 0;
      $('scoutro-domain-total').textContent = String(data.total != null ? data.total : hosts);
      $('scoutro-domain-empty').hidden = hosts !== 0;
      if (hosts) $('scoutro-domain-range').textContent = t('range') + ' ' + (offset + 1) + '–' + Math.min(offset + limit, hosts) + ' ' + t('of') + ' ' + hosts;
      $('scoutro-domain-prev').disabled = offset === 0;
      $('scoutro-domain-next').disabled = offset + limit >= hosts || offset + limit > 10000;
      exportTotal = data.total; updateExport();
    } catch (failed) { failure(current, failed.message); }
  }
  function cell(row, value, index, table) {
    var td = row.insertCell();
    td.dataset.label = table.tHead.rows[0].cells[index].textContent;
    td.textContent = value == null ? '—' : value;
    return td;
  }
  async function loadUrls() {
    var current = ++generation, table = $('scoutro-index-table');
    error.hidden = true;
    table.tBodies[0].replaceChildren();
    $('scoutro-index-prev').disabled = $('scoutro-index-next').disabled = true;
    $('scoutro-index-empty').hidden = true;
    $('scoutro-index-total').textContent = '—';
    var invalid = invalidFilter();
    if (invalid) return failure(current, invalid);
    var params = new URLSearchParams({ q: query.value.trim(), collection: collection.value.trim(), offset: offset, limit: limit });
    try {
      var data = await readJson('/scoutro/api/v1/index/browse?' + params);
      if (current !== generation) return;
      status.textContent = '';
      data.documents.forEach(function (doc) {
        var row = table.tBodies[0].insertRow();
        var urlCell = cell(row, doc.url, 0, table);
        if (/^https?:\/\//i.test(doc.url)) urlCell.replaceChildren(external(doc.url));
        var hostCell = cell(row, doc.host, 1, table);
        if (doc.host) {
          var link = node('a', doc.host); link.href = seoLink(doc.host, collection.value.trim() || doc.collections[0]);
          hostCell.replaceChildren(link);
        }
        cell(row, doc.collections.join(', '), 2, table); cell(row, doc.httpStatus, 3, table);
      });
      $('scoutro-index-total').textContent = String(data.total);
      $('scoutro-index-empty').hidden = data.total !== 0;
      $('scoutro-index-prev').disabled = offset === 0;
      $('scoutro-index-next').disabled = offset + limit >= data.total || offset + limit > 10000;
    } catch (failed) { failure(current, failed.message); }
  }
  function load() {
    var name = collection.value.trim();
    active.hidden = !name;
    active.querySelector('span').textContent = name;
    pageUrl();
    return view === 'urls' ? loadUrls() : loadDomains();
  }
  function selectedFormat() {
    var checked = document.querySelector('input[name="scoutro-export-format"]:checked');
    return checked ? checked.value : 'json';
  }
  function updateExport() {
    var params = filterParams();
    params.set('format', selectedFormat());
    $('scoutro-export-download').href = '/scoutro/api/v1/index/domains/export?' + params;
    var name = collection.value.trim(), host = query.value.trim();
    $('scoutro-export-scope').textContent = (name || t('scope_all')) + ' · ' + (host ? t('scope_host') + ': ' + host : t('scope_none'));
    $('scoutro-export-count').textContent = exportTotal == null ? t('export_count_unknown')
      : exportTotal.toLocaleString(language) + ' ' + t(exportTotal === 1 ? 'export_count_one' : 'export_count_many');
  }
  form.addEventListener('submit', function (event) { event.preventDefault(); offset = 0; load(); });
  sort.addEventListener('change', function () { offset = 0; load(); });
  $('scoutro-index-reset').addEventListener('click', function () { query.value = collection.value = ''; sort.value = 'host'; offset = 0; load(); });
  $('scoutro-index-clear').addEventListener('click', function () { collection.value = ''; offset = 0; load(); });
  $('scoutro-index-prev').addEventListener('click', function () { offset = Math.max(0, offset - limit); load(); });
  $('scoutro-index-next').addEventListener('click', function () { offset += limit; load(); });
  $('scoutro-domain-prev').addEventListener('click', function () { offset = Math.max(0, offset - limit); load(); });
  $('scoutro-domain-next').addEventListener('click', function () { offset += limit; load(); });
  $('scoutro-export-toggle').addEventListener('click', function () {
    var panel = $('scoutro-export'), open = panel.hidden;
    panel.hidden = !open;
    this.setAttribute('aria-expanded', open ? 'true' : 'false');
    updateExport();
  });
  document.querySelectorAll('input[name="scoutro-export-format"]').forEach(function (input) { input.addEventListener('change', updateExport); });
  [query, collection].forEach(function (input) { input.addEventListener('input', function () { exportTotal = null; updateExport(); }); });
  fetch('/scoutro/api/v1/collections', { credentials: 'same-origin' }).then(function (response) {
    if (!response.ok) return null; return response.json();
  }).then(function (data) {
    if (!data) return;
    var choices = $('scoutro-index-choices');
    data.collections.forEach(function (item) { var option = document.createElement('option'); option.value = item.id; choices.appendChild(option); });
  }).catch(function () { /* Suggestions are optional; filter errors remain visible. */ });
  setView();
  load();
})();
