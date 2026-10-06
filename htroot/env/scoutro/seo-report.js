/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Read-only crawl report; every stored or indexed value is rendered as text. */
(() => {
  'use strict';
  const initial = new URLSearchParams(location.search);
  if (initial.get('view') !== 'report') return;
  const $ = id => document.getElementById('sseo-' + id);
  const labels = Object.fromEntries([...document.querySelectorAll('[data-sseo-label]')].map(n => [n.dataset.sseoLabel, n.textContent]));
  const t = key => labels[key] || key;
  const value = (prefix, key) => labels[prefix + key] || String(key);
  const lang = document.documentElement.lang;
  const fmt = v => v == null ? t('missing') : typeof v === 'number' ? v.toLocaleString(lang) : String(v);
  const date = v => v == null ? t('missing') : new Date(v).toLocaleString(lang);
  const day = v => new Date(v + 'T00:00:00Z').toLocaleDateString(lang, { timeZone: 'UTC' });
  const node = (tag, text, className) => { const n = document.createElement(tag); if (text != null) n.textContent = text; if (className) n.className = className; return n; };
  const SVG = 'http://www.w3.org/2000/svg';
  const svg = (tag, attributes = {}) => { const n = document.createElementNS(SVG, tag); for (const [k, v] of Object.entries(attributes)) n.setAttribute(k, String(v)); return n; };
  const OUTCOMES = ['indexed', 'partial', 'not_reloaded', 'not_indexed', 'unknown'];
  const COLORS = { indexed: '#2e7d4f', partial: '#c98a12', not_reloaded: '#5b7fa3', not_indexed: '#b3412e', unknown: '#8c99a8' };
  const PAGES = ['pages_total', 'pages_ok', 'pages_redirect', 'pages_client_error', 'pages_server_error', 'pages_excluded', 'pages_failed', 'pages_robots', 'pages_not_reloaded', 'pages_not_reloaded_ok'];
  const EXCLUSIONS = ['excl_noindex', 'excl_canonical', 'excl_filter', 'excl_blacklist', 'excl_other'];
  const PRECHECKS = ['dns', 'robots', 'blocked', 'site_5xx'];
  const QUALITY = ['canonical_s', 'canonical_equal_sku_b', 'title_exact_signature_l', 'description_exact_signature_l'];
  const LIMIT = 25, MAX_OFFSET = 10000;
  let generation = 0, hostGeneration = 0, hostOffset = 0, current = '', jobsLoaded = null;

  async function get(path, query = {}) {
    const response = await fetch('/scoutro/api/v1/reports/' + path + '?' + new URLSearchParams(query), { credentials: 'same-origin', cache: 'no-store' });
    let result; try { result = await response.json(); } catch (_) { throw new Error(t('r_error') + ' (HTTP ' + response.status + ')'); }
    if (!response.ok) {
      const code = result.error?.code || 'error';
      const key = code === 'report_unavailable' ? 'r_unavailable' : response.status === 404 ? 'r_not_found' : response.status === 400 ? 'r_invalid' : 'r_error';
      throw new Error(t(key) + ' (HTTP ' + response.status + ', ' + code + ')');
    }
    return result;
  }
  function message(text = '') { $('r-message').textContent = text; }
  function guarded(task) { const promise = task(), run = generation; promise.catch(e => { if (run === generation) message(e.message); }); }
  function stats(target, rows) {
    const box = $(target); box.replaceChildren();
    for (const [key, v] of rows) box.append(node('dt', t(key)), node('dd', v == null ? t('missing') : String(v)));
  }
  function extra(target, rows) {
    const dl = node('dl', null, 'sseo-stats sseo-extra');
    for (const [key, v] of rows) dl.append(node('dt', t(key)), node('dd', fmt(v)));
    $(target).append(dl);
  }
  function empty(target, key = 'r_none') { $(target).replaceChildren(node('p', t(key), 'sseo-note')); }
  function bars(target, rows) {
    const visible = rows.filter(([, n]) => n != null);
    if (!visible.length || visible.every(([, n]) => n === 0)) { empty(target); return; }
    const max = Math.max(...visible.map(([, n]) => n)), list = node('ul', null, 'sseo-bars');
    for (const [label, n] of visible) {
      const item = node('li'), head = node('div', null, 'sseo-bar-head');
      head.append(node('span', label), node('span', fmt(n), 'sseo-bar-value'));
      const chart = svg('svg', { viewBox: '0 0 100 6', preserveAspectRatio: 'none', 'aria-hidden': 'true', focusable: 'false' });
      chart.append(svg('rect', { width: 100, height: 6, fill: '#e8eef4' }), svg('rect', { width: max ? (n / max * 100).toFixed(2) : 0, height: 6, fill: '#197bb5' }));
      item.append(head, chart); list.append(item);
    }
    $(target).replaceChildren(list);
  }
  function donut(target, counts) {
    const rows = OUTCOMES.map(k => [k, counts[k] || 0]).concat(Object.keys(counts).filter(k => !OUTCOMES.includes(k)).map(k => [k, counts[k]]));
    const total = rows.reduce((sum, [, n]) => sum + n, 0);
    if (!total) { empty(target); return; }
    const summary = rows.filter(([, n]) => n).map(([k, n]) => value('r_', k) + ': ' + fmt(n)).join(', ');
    const chart = svg('svg', { viewBox: '0 0 42 42', role: 'img', 'aria-label': t('r_outcome') + ': ' + summary, class: 'sseo-donut' });
    chart.append(svg('circle', { cx: 21, cy: 21, r: 15.9155, fill: 'none', stroke: '#e8eef4', 'stroke-width': 6 }));
    let start = 0;
    for (const [k, n] of rows) {
      if (!n) continue;
      const share = n / total * 100, arc = svg('circle', { cx: 21, cy: 21, r: 15.9155, fill: 'none', stroke: COLORS[k] || COLORS.unknown, 'stroke-width': 6,
        'stroke-dasharray': share.toFixed(3) + ' ' + (100 - share).toFixed(3), 'stroke-dashoffset': (25 - start).toFixed(3) });
      const title = svg('title'); title.textContent = value('r_', k) + ': ' + fmt(n); arc.append(title); chart.append(arc); start += share;
    }
    const center = svg('text', { x: 21, y: 22.5, 'text-anchor': 'middle', 'font-size': 6, fill: '#22324a' }); center.textContent = fmt(total); chart.append(center);
    const legend = node('ul', null, 'sseo-legend');
    for (const [k, n] of rows) {
      if (!n) continue;
      const item = node('li'), swatch = node('span', null, 'sseo-swatch'); swatch.style.background = COLORS[k] || COLORS.unknown;
      item.append(swatch, node('span', value('r_', k)), node('span', fmt(n) + ' (' + fmt(Math.round(n / total * 1000) / 10) + ' %)', 'sseo-bar-value')); legend.append(item);
    }
    const box = node('div', null, 'sseo-donut-box'); box.append(chart, legend); $(target).replaceChildren(box);
  }
  function cards(rows) {
    $('r-kpis').replaceChildren();
    for (const [key, v] of rows) { const card = node('dl', null, 'sseo-card'); card.append(node('dt', t(key)), node('dd', v == null ? t('missing') : String(v))); $('r-kpis').append(card); }
  }
  function tableTiles(table) {
    donut('r-outcomes', table.outcomes);
    bars('r-prechecks', PRECHECKS.map(k => [value('r_', k), table.prechecks[k] || 0]));
    extra('r-prechecks', [['r_latest_precheck', table.latest_attempt_precheck], ['r_precheck_only', table.precheck_only]]);
    bars('r-pages', PAGES.filter(k => k in table.counters).map(k => [t(k), table.counters[k]]));
    bars('r-exclusions', EXCLUSIONS.filter(k => k in table.counters).map(k => [t(k), table.counters[k]]));
    extra('r-exclusions', [['r_coverage_partial', table.coverage_partial]]);
  }
  function scope(kind) {
    for (const section of document.querySelectorAll('#sseo-r-result [data-sseo-scope]')) section.hidden = section.dataset.sseoScope !== kind;
  }
  function crawlLink(host, collection, scheme) {
    const link = node('a', t('r_crawl_again'), 'btn btn-default btn-sm');
    link.href = 'ScoutroCrawls_p.html?' + new URLSearchParams({ url: (scheme === 'http' ? 'http' : 'https') + '://' + host + '/', collection }) + '#new-crawl'; return link;
  }
  /** Names the optional quality fields that are disabled, with a link to YaCy's index schema page. */
  function fieldHint(target, unavailable) {
    const missing = QUALITY.filter(f => (unavailable || []).includes(f));
    if (!missing.length) return;
    const p = node('p', t('r_fields_disabled') + ' ' + missing.join(', ') + '. ' + t('r_fields_hint') + ' ', 'sseo-note'), link = node('a', t('r_index_schema'));
    link.href = 'IndexSchema_p.html?core=collection1&filter=disabled'; p.append(link); $(target).append(p);
  }

  async function collectionReport(collection) {
    const run = ++generation; current = collection; message(t('loading')); $('r-result').hidden = true;
    const data = await get('collections/' + encodeURIComponent(collection)); if (run !== generation) return;
    const table = data.table, index = data.index;
    $('r-heading').textContent = data.collection; scope('collection');
    cards([['r_hosts', fmt(table.hosts)], ['r_crawled', fmt(table.crawled)], ['r_stale_hosts', fmt(table.stale)], ['r_last_crawl', table.last_crawl && date(table.last_crawl)],
      ['r_documents', index && fmt(index.documents)], ['r_ok', index && fmt(index.ok)]]);
    tableTiles(table);
    if (index) {
      bars('r-status', (index.http_status || []).map(b => [String(b.value), b.count]));
      bars('r-types', (index.content_type || []).map(b => [String(b.value), b.count]));
      bars('r-depth', (index.depth || []).map(b => [String(b.value), b.count]));
      const d = index.duplicates || {}, bound = (n, truncated) => n == null ? null : (truncated ? t('r_at_least') + ' ' : '') + fmt(n);
      stats('r-duplicates', [['r_exact_groups', bound(d.exact_groups, d.exact_truncated)], ['r_exact_urls', bound(d.exact_urls, d.exact_truncated)],
        ['r_similar_groups', bound(d.similar_groups, d.similar_truncated)], ['r_similar_urls', bound(d.similar_urls, d.similar_truncated)]]);
      const c = index.canonical;
      if (!c) empty('r-canonical');
      else bars('r-canonical', c.self == null ? [[t('r_canonical_with'), c.with], [t('r_canonical_without'), c.without]]
        : [[t('r_canonical_self'), c.self], [t('r_canonical_elsewhere'), c.elsewhere], [t('r_canonical_without'), c.without]]);
      fieldHint('r-canonical', index.unavailable);
      const titles = index.titles, descriptions = index.descriptions;
      if (!titles && !descriptions) empty('r-texts');
      else stats('r-texts', [['r_title_with', titles && fmt(titles.with)], ['r_title_missing', titles && fmt(titles.missing)],
        ['r_description_with', descriptions && fmt(descriptions.with)], ['r_description_missing', descriptions && fmt(descriptions.missing)]]);
    } else {
      for (const id of ['r-status', 'r-types', 'r-depth', 'r-duplicates', 'r-canonical', 'r-texts']) empty(id, 'r_index_unavailable');
    }
    stats('r-age', [['r_last_crawl', table.last_crawl && date(table.last_crawl)], ['r_stale_hosts', fmt(table.stale)], ['r_oldest', index?.oldest && date(index.oldest)],
      ['r_newest', index?.newest && date(index.newest)], ['r_index_source', data.index_source && value('r_source_', data.index_source)],
      ['r_index_as_of', data.index_as_of && (data.index_source === 'rollup' ? day(data.index_as_of) : date(data.index_as_of))], ['r_scanned', date(data.table_scanned_at)]]);
    $('r-result').hidden = false; message(data.index_error ? t('r_index_unavailable') : '');
    hostOffset = 0; guardedHosts();
  }
  function guardedHosts() { const run = hostGeneration + 1; hosts().catch(e => { if (run === hostGeneration) message(e.message); }); }
  async function hosts() {
    const run = ++hostGeneration, collection = current, box = $('r-hosts');
    box.replaceChildren(node('p', t('loading'))); $('r-range').textContent = ''; $('r-prev').disabled = true; $('r-next').disabled = true;
    const data = await get('collections/' + encodeURIComponent(collection) + '/hosts', { filter: $('r-filter').value, offset: hostOffset, limit: LIMIT });
    if (run !== hostGeneration) return;
    box.replaceChildren();
    if (!data.items.length) box.append(node('p', t('r_no_hosts'), 'sseo-note'));
    else {
      const table = node('table', null, 'table table-striped'), head = node('tr');
      for (const key of ['r_host', 'r_last_crawl', 'r_age_days', 'r_stale', 'r_outcome', 'r_coverage', 'pages_ok', 'r_latest_attempt', 'r_action']) { const th = node('th', t(key)); th.scope = 'col'; head.append(th); }
      const thead = node('thead'); thead.append(head); table.append(thead);
      const body = node('tbody');
      for (const item of data.items) {
        const row = node('tr'), cell = node('td'), link = node('a', item.host, 'sseo-url');
        link.href = 'ScoutroSEO_p.html?' + new URLSearchParams({ host: item.host, collection }); cell.append(link); row.append(cell);
        const attempt = item.latest_attempt === 'precheck' ? value('r_attempt_', 'precheck') + ': ' + value('r_', item.precheck) : item.latest_attempt && value('r_attempt_', item.latest_attempt);
        for (const [text, className] of [[item.last_crawl && date(item.last_crawl)], [item.age_days == null ? null : fmt(item.age_days)], [t(item.stale ? 'r_yes' : 'r_no'), item.stale ? 'sseo-stale' : null],
          [item.outcome && value('r_', item.outcome)], [item.coverage && (item.coverage === 'complete' ? t('r_complete') : t('r_partly'))],
          [item.pages_ok == null ? null : fmt(item.pages_ok)], [attempt]]) row.append(node('td', text == null ? t('missing') : text, className));
        const action = node('td'); action.append(crawlLink(item.host, collection, item.scheme)); row.append(action); body.append(row);
      }
      table.append(body); box.append(table);
    }
    $('r-range').textContent = t('range') + ': ' + (data.items.length ? fmt(hostOffset + 1) + '–' + fmt(hostOffset + data.items.length) : '0') + ' / ' + fmt(data.total);
    $('r-prev').disabled = hostOffset === 0; $('r-next').disabled = hostOffset + data.items.length >= data.total || hostOffset + LIMIT > MAX_OFFSET;
  }

  function renderHistory(data) {
    const byDay = new Map(data.rollups.map(line => [line.day, line]));
    const days = []; for (let d = new Date(data.from + 'T00:00:00Z'); d <= new Date(data.to + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + 1)) days.push(d.toISOString().slice(0, 10));
    const total = data.rollups.reduce((sum, line) => sum + (line.crawls || 0), 0), max = Math.max(1, ...data.rollups.map(line => line.crawls || 0));
    const box = $('r-history'); box.replaceChildren();
    if (!data.rollups.length) { box.append(node('p', t('r_no_history'), 'sseo-note')); $('r-history-table').replaceChildren(); return; }
    const top = node('div', null, 'sseo-axis'); top.append(node('span', t('r_maximum') + ': ' + fmt(max)), node('span', t('r_total') + ': ' + fmt(total)));
    const chart = svg('svg', { viewBox: '0 0 ' + days.length + ' 100', preserveAspectRatio: 'none', role: 'img', class: 'sseo-history',
      'aria-label': t('r_history') + ', ' + day(data.from) + ' – ' + day(data.to) + ': ' + t('r_total') + ' ' + fmt(total) });
    chart.append(svg('rect', { width: days.length, height: 100, fill: '#f6f9fc' }));
    days.forEach((d, i) => {
      const line = byDay.get(d); if (!line) return;
      let y = 100;
      for (const k of OUTCOMES.concat(Object.keys(line.outcomes || {}).filter(k => !OUTCOMES.includes(k)))) {
        const n = (line.outcomes || {})[k]; if (!n) continue;
        const h = n / max * 100; y -= h;
        const bar = svg('rect', { x: i + 0.1, y: y.toFixed(3), width: 0.8, height: h.toFixed(3), fill: COLORS[k] || COLORS.unknown });
        const title = svg('title'); title.textContent = day(d) + ' · ' + value('r_', k) + ': ' + fmt(n); bar.append(title); chart.append(bar);
      }
      for (const marker of line.markers || []) {
        const m = svg('line', { x1: i + 0.5, x2: i + 0.5, y1: 0, y2: 100, stroke: '#6b4fbb', 'stroke-width': 2, 'stroke-dasharray': '4 3', 'vector-effect': 'non-scaling-stroke' });
        const title = svg('title'); title.textContent = day(d) + ' · ' + value('r_', marker.type); m.append(title); chart.append(m);
      }
    });
    const axis = node('div', null, 'sseo-axis'); axis.append(node('span', day(data.from)), node('span', day(data.to)));
    const legend = node('ul', null, 'sseo-legend sseo-legend-inline');
    for (const k of OUTCOMES) { const item = node('li'), swatch = node('span', null, 'sseo-swatch'); swatch.style.background = COLORS[k]; item.append(swatch, node('span', value('r_', k))); legend.append(item); }
    const markerItem = node('li'), markerSwatch = node('span', null, 'sseo-swatch sseo-swatch-marker'); markerItem.append(markerSwatch, node('span', t('r_markers'))); legend.append(markerItem);
    box.append(top, chart, axis, legend);
    const table = node('table', null, 'table table-striped'), head = node('tr');
    for (const key of ['r_day', 'r_crawls', ...OUTCOMES.map(k => 'r_' + k), 'r_prechecks', 'pages_ok', 'r_markers']) { const th = node('th', t(key)); th.scope = 'col'; head.append(th); }
    const thead = node('thead'); thead.append(head); table.append(thead);
    const body = node('tbody');
    for (const line of data.rollups) {
      const row = node('tr'), prechecks = Object.values(line.prechecks || {}).reduce((a, b) => a + b, 0);
      for (const text of [day(line.day), fmt(line.crawls || 0), ...OUTCOMES.map(k => fmt((line.outcomes || {})[k] || 0)), fmt(prechecks), fmt((line.pages || {}).pages_ok || 0),
        (line.markers || []).map(m => value('r_', m.type)).join(', ')]) row.append(node('td', text));
      body.append(row);
    }
    table.append(body); $('r-history-table').replaceChildren(table);
  }
  async function jobReport(job) {
    const run = ++generation; message(t('loading')); $('r-result').hidden = true;
    const query = {}; if ($('r-from').value) query.from = $('r-from').value; if ($('r-to').value) query.to = $('r-to').value;
    const data = await get('jobs/' + encodeURIComponent(job), query); if (run !== generation) return;
    const table = data.table;
    $('r-heading').textContent = (data.name || data.job) + (data.known ? '' : ' · ' + t('r_job_unknown')); scope('job');
    cards([['r_hosts', fmt(table.hosts)], ['r_crawled', fmt(table.crawled)], ['r_stale_hosts', fmt(table.stale)], ['r_last_crawl', table.last_crawl && date(table.last_crawl)],
      ['r_stale_after_days', fmt(data.stale_after_days)], ['r_latest_precheck', fmt(table.latest_attempt_precheck)]]);
    tableTiles(table);
    stats('r-age', [['r_last_crawl', table.last_crawl && date(table.last_crawl)], ['r_stale_hosts', fmt(table.stale)], ['r_stale_after_days', fmt(data.stale_after_days)]]);
    renderHistory(data);
    const list = $('r-collections'); list.replaceChildren();
    if (!data.collections.length) list.append(node('li', t('r_no_collections')));
    for (const c of data.collections) { const item = node('li'), link = node('a', c); link.href = 'ScoutroSEO_p.html?' + new URLSearchParams({ view: 'report', collection: c }); item.append(link); list.append(item); }
    $('r-result').hidden = false; message();
  }

  function jobs() {
    // one request per page view, also when the collection list and the job scope ask at once
    if (!jobsLoaded) jobsLoaded = loadJobs().catch(e => { jobsLoaded = null; throw e; });
    return jobsLoaded;
  }
  async function loadJobs() {
    const data = await get('jobs'), select = $('r-job'); select.replaceChildren();
    if (!data.jobs.length) { const option = node('option', t('r_no_jobs')); option.value = ''; select.append(option); }
    for (const job of data.jobs) { const option = node('option', (job.name || job.id) + (job.known ? '' : ' · ' + t('r_job_unknown'))); option.value = job.id; select.append(option); }
    return data;
  }
  function kind(selected) {
    $('r-kind').value = selected; const job = selected === 'job';
    $('r-collection-field').hidden = job; for (const id of ['r-job-field', 'r-from-field', 'r-to-field']) $(id).hidden = !job;
    return job ? jobs().catch(e => message(e.message)) : Promise.resolve();
  }
  function show() {
    if ($('r-kind').value === 'job') {
      const job = $('r-job').value; if (!job) { message(t('r_choose_job')); return; }
      const state = new URLSearchParams({ view: 'report', job }); if ($('r-from').value) state.set('from', $('r-from').value); if ($('r-to').value) state.set('to', $('r-to').value);
      history.replaceState(null, '', '?' + state); guarded(() => jobReport(job));
    } else {
      const collection = $('r-collection').value.trim(); if (!collection) { message(t('r_choose_collection')); return; }
      history.replaceState(null, '', '?' + new URLSearchParams({ view: 'report', collection })); $('r-filter').value = 'all'; guarded(() => collectionReport(collection));
    }
  }
  $('r-form').addEventListener('submit', event => { event.preventDefault(); show(); });
  $('r-kind').addEventListener('change', () => kind($('r-kind').value));
  $('r-filter').addEventListener('change', () => { hostOffset = 0; guardedHosts(); });
  $('r-prev').addEventListener('click', () => { hostOffset = Math.max(0, hostOffset - LIMIT); guardedHosts(); });
  $('r-next').addEventListener('click', () => { hostOffset += LIMIT; guardedHosts(); });
  if (initial.has('job')) {
    const job = initial.get('job'); if (initial.has('from')) $('r-from').value = initial.get('from'); if (initial.has('to')) $('r-to').value = initial.get('to');
    kind('job').then(() => {
      $('r-job').value = job;
      if ($('r-job').value !== job) { const option = node('option', job); option.value = job; $('r-job').append(option); $('r-job').value = job; }
      guarded(() => jobReport(job));
    });
  } else kind('collection');
  // the collections of the index and of the Discovery jobs; a collection of the link is reported only if it is one of them
  Promise.all([ScoutroCollections.load(), jobs().then(data => data.jobs.flatMap(job => job.collections || [])).catch(() => [])]).then(([ids, jobCollections]) => {
    const wanted = initial.has('job') ? '' : initial.get('collection') || '';
    if (ScoutroCollections.fill($('r-collection'), ScoutroCollections.sorted(ids.concat(jobCollections)), wanted) && wanted) guarded(() => collectionReport(wanted));
    else if (wanted) message(t('r_choose_collection'));
  });
})();
