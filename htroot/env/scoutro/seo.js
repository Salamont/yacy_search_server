/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Read-only; indexed text is always rendered as text. */
(() => {
  'use strict';
  const $ = id => document.getElementById('sseo-' + id);
  const labels = Object.fromEntries([...document.querySelectorAll('[data-sseo-label]')].map(n => [n.dataset.sseoLabel, n.textContent]));
  const t = key => labels[key] || key;
  const fmt = value => value == null ? t('missing') : Array.isArray(value) ? (value.length ? value.join('\n') : t('missing')) : typeof value === 'number' ? value.toLocaleString(document.documentElement.lang, { maximumFractionDigits: 2 }) : String(value);
  const date = value => value == null ? t('missing') : new Date(value).toLocaleString(document.documentElement.lang);
  const display = (key, value) => key === 'load_date' || key === 'last_modified' ? date(value) : fmt(value);
  const node = (tag, text, className) => { const n = document.createElement(tag); if (text != null) n.textContent = text; if (className) n.className = className; return n; };
  let host = '', collection = '', summary = null, offset = 0, generation = 0, detailGeneration = 0, hostOffset = 0, hostsQuery = '';
  const root = '/scoutro/api/v1/seo/';
  async function api(path, query = {}) {
    const parameters = new URLSearchParams(query); if (collection) parameters.set('collection', collection);
    const response = await fetch(root + path + '?' + parameters, { credentials: 'same-origin', cache: 'no-store' });
    let result; try { result = await response.json(); } catch (_) { throw new Error(t('error') + ' (HTTP ' + response.status + ')'); }
    if (!response.ok) throw new Error((response.status === 404 ? t('empty') : t('error')) + ' (HTTP ' + response.status + ', ' + (result.error?.code || 'error') + ')');
    return result;
  }
  function message(text = '') { $('message').textContent = text; }
  function guarded(task) { const promise = task(), run = generation, detailRun = detailGeneration; promise.catch(e => { if (run === generation && detailRun === detailGeneration) message(e.message); }); }
  function stats(id, rows) {
    const target = $(id); target.replaceChildren();
    for (const [key, value] of rows) target.append(node('dt', t(key)), node('dd', display(key, value)));
  }
  function metric(value) { return value?.value == null ? null : fmt(value.value) + ' · ' + t('measured') + ': ' + fmt(value.measured_pages); }
  function distribution(id, values) { stats(id, values == null ? [['missing', null]] : values.length ? values.map(v => [String(v.value), v.pages]) : [['missing', null]]); }
  function coverage(c) { return c.processed_pages == null || c.coverage == null ? null : fmt(c.processed_pages) + ' / ' + fmt(summary.indexed_pages) + ' (' + fmt(c.coverage * 100) + '%)'; }
  function renderSummary() {
    $('heading').textContent = summary.host;
    $('kpis').replaceChildren();
    for (const [key, value] of [['indexed_pages', summary.indexed_pages], ['coverage', coverage(summary.citation)], ['load_date', summary.crawl.load_date]]) {
      const card = node('dl', null, 'sseo-card'); card.append(node('dt', t(key)), node('dd', display(key, value))); $('kpis').append(card);
    }
    stats('coverage', ['processed_pages', 'pending_pages', 'unavailable_pages', 'unknown_pages'].map(k => [k, summary.citation[k]]));
    stats('content', [...['title_pages', 'description_pages', 'h1_pages', 'h2_pages', 'h3_pages'].map(k => [k, summary.content[k]]), ['word_count', metric(summary.content.word_count)]]);
    distribution('languages', summary.content.languages);
    stats('references', [...['references_total', 'references_internal', 'references_external'].map(k => [k, summary.citation[k]]), ['coverage', coverage(summary.citation)]]);
    stats('crawl', [['crawl_depth', metric(summary.crawl.depth)], ['response_time_ms', metric(summary.crawl.response_time_ms)], ['load_date', summary.crawl.load_date], ['last_modified', summary.crawl.last_modified]]);
    distribution('status', summary.technology.http_status); distribution('protocol', summary.technology.protocol);
    stats('outgoing', ['outgoing_internal', 'outgoing_external', 'nofollow'].map(k => [k, metric(summary.technology[k])]));
    for (const option of $('sort').options) {
      option.disabled = option.value === 'url' ? false : option.value.startsWith('references_') || option.value === 'external_hosts' ? summary.citation.processed_pages == null : !summary.fields[option.value];
    }
    if ($('sort').selectedOptions[0].disabled) $('sort').value = 'url';
  }
  function pageTable(target, items, keys) {
    const box = $(target); box.replaceChildren();
    if (!items.length) { box.append(node('p', t('empty'))); return; }
    const table = node('table', null, 'table table-striped' + (keys.length < 5 ? ' sseo-small-table' : ''));
    const head = node('thead'), header = node('tr');
    for (const key of ['url', ...keys]) { const th = node('th', t(key)); th.scope = 'col'; header.append(th); } head.append(header); table.append(head);
    const body = node('tbody');
    for (const item of items) {
      const row = node('tr'), td = node('td'), button = node('button', item.url, 'sseo-url'); button.type = 'button'; button.dataset.sseoPage = item.id;
      button.addEventListener('click', () => guarded(() => detail(item.id))); td.append(button); row.append(td);
      for (const key of keys) {
        const value = key === 'status' ? t(item.citation.status) : item.content[key] ?? item.crawl[key] ?? item.citation[key]; row.append(node('td', fmt(value)));
      } body.append(row);
    } table.append(body); box.append(table);
  }
  async function pages() {
    const run = ++generation; message(t('loading')); $('pages-table').replaceChildren(node('p', t('loading'))); $('range').textContent = ''; $('prev').disabled = true; $('next').disabled = true;
    const data = await api('hosts/' + encodeURIComponent(host) + '/pages', { limit: 25, offset, sort: $('sort').value, order: $('order').value, citation: $('filter').value });
    if (run !== generation) return;
    pageTable('pages-table', data.items, ['title', 'word_count', 'crawl_depth', 'references_internal', 'references_external', 'external_hosts', 'status']);
    $('range').textContent = t('range') + ': ' + (data.items.length ? fmt(offset + 1) + '–' + fmt(offset + data.items.length) : '0') + ' / ' + fmt(data.total);
    $('prev').disabled = offset === 0; $('next').disabled = offset + data.items.length >= data.total; message();
  }
  async function links() {
    const run = ++generation; message(t('loading'));
    for (const id of ['top-external', 'top-internal']) $(id).replaceChildren(node('p', t('loading')));
    if (summary.citation.processed_pages == null) { for (const id of ['top-external', 'top-internal']) $(id).replaceChildren(node('p', t('unavailable'))); message(); return; }
    const values = await Promise.all(['references_external', 'references_internal'].map(sort => api('hosts/' + encodeURIComponent(host) + '/pages', { sort, order: 'desc', citation: 'processed', limit: 5 })));
    if (run !== generation) return;
    pageTable('top-external', values[0].items, ['references_external', 'external_hosts']);
    pageTable('top-internal', values[1].items, ['references_internal']); message();
  }
  function tab(key) {
    generation++; message();
    for (const button of document.querySelectorAll('[data-sseo-tab]')) { const active = button.dataset.sseoTab === key; button.setAttribute('aria-selected', String(active)); button.tabIndex = active ? 0 : -1; $('panel-' + button.dataset.sseoTab).hidden = !active; }
    if (key === 'pages') guarded(pages); if (key === 'links') guarded(links);
  }
  async function analyze(value) {
    const run = ++generation; detailGeneration++; host = value.trim(); collection = $('collection').value.trim(); $('analysis').hidden = true; $('detail').hidden = true; message(t('loading'));
    const data = await api('hosts/' + encodeURIComponent(host)); if (run !== generation) return;
    summary = data; host = data.host; $('host').value = host; offset = 0; $('filter').value = 'all'; $('sort').value = 'url'; $('order').value = 'asc'; renderSummary(); $('analysis').hidden = false; $('host-results').replaceChildren(); $('more-hosts').hidden = true; tab('overview'); message();
  }
  async function find(more = false) {
    const run = ++generation; collection = $('collection').value.trim(); if (!more) { hostOffset = 0; hostsQuery = $('host').value.trim().toLowerCase(); $('host-results').replaceChildren(); }
    message(t('loading')); const data = await api('hosts', { q: hostsQuery, limit: 20, offset: hostOffset }); if (run !== generation) return;
    for (const item of data.items) { const li = node('li'), b = node('button', item.host + ' · ' + fmt(item.pages), 'sseo-url'); b.type = 'button'; b.addEventListener('click', () => guarded(() => analyze(item.host))); li.append(b); $('host-results').append(li); }
    hostOffset += data.items.length; $('more-hosts').hidden = hostOffset >= data.total; message(data.items.length ? '' : t('empty'));
  }
  async function detail(id) {
    const run = ++detailGeneration; $('detail').hidden = true; message(t('loading')); const data = await api('pages/' + encodeURIComponent(id)); if (run !== detailGeneration) return;
    $('detail-url').textContent = data.url; $('detail-body').replaceChildren();
    for (const [name, group] of [['content', data.content], ['crawl', data.crawl], ['outgoing', data.outgoing], ['citation', data.citation]]) {
      const dl = node('dl', null, 'sseo-stats');
      for (const [key, value] of Object.entries(group)) { if (['historical_completeness', 'source_scope', 'host_extent_scope'].includes(key)) continue; dl.append(node('dt', t(key)), node('dd', display(key, key === 'status' ? t(value) : value))); }
      $('detail-body').append(node('h3', t(name)), dl);
    }
    const dl = node('dl', null, 'sseo-stats'); dl.append(node('dt', t('collections')), node('dd', fmt(data.collections))); $('detail-body').append(dl);
    $('detail').hidden = false; $('detail-title').focus(); message();
  }
  $('search').addEventListener('submit', event => { event.preventDefault(); guarded(() => analyze($('host').value)); });
  $('find').addEventListener('click', () => guarded(() => find())); $('more-hosts').addEventListener('click', () => guarded(() => find(true)));
  $('close').addEventListener('click', () => { detailGeneration++; $('detail').hidden = true; $('tab-pages').focus(); });
  for (const button of document.querySelectorAll('[data-sseo-tab]')) {
    button.addEventListener('click', () => tab(button.dataset.sseoTab));
    button.addEventListener('keydown', e => { const buttons = [...document.querySelectorAll('[data-sseo-tab]')]; let i = buttons.indexOf(button); if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return; e.preventDefault(); i = e.key === 'Home' ? 0 : e.key === 'End' ? 3 : (i + (e.key === 'ArrowRight' ? 1 : 3)) % 4; buttons[i].focus(); tab(buttons[i].dataset.sseoTab); });
  }
  for (const id of ['sort', 'order', 'filter']) $(id).addEventListener('change', () => { const sort = $('sort').value; if (sort.startsWith('references_') || sort === 'external_hosts') $('filter').value = 'processed'; offset = 0; guarded(pages); });
  $('prev').addEventListener('click', () => { offset = Math.max(0, offset - 25); guarded(pages); }); $('next').addEventListener('click', () => { offset += 25; guarded(pages); });
})();
