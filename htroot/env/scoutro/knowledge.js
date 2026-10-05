/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Knowledge graph admin page; graph and page text is always rendered as text. */
(() => {
  'use strict';
  const $ = id => document.getElementById('skg-' + id);
  const labels = Object.fromEntries([...document.querySelectorAll('[data-skg-label]')].map(n => [n.dataset.skgLabel, n.textContent]));
  const t = key => labels[key] || key;
  const lang = document.documentElement.lang || undefined;
  const fmt = v => v == null || v === '' ? t('missing') : typeof v === 'number' ? v.toLocaleString(lang) : String(v);
  const date = v => v == null ? t('missing') : new Date(v).toLocaleString(lang);
  const bytes = v => v == null ? t('missing') : v >= 1073741824 ? (v / 1073741824).toLocaleString(lang, { maximumFractionDigits: 2 }) + ' GiB'
    : v >= 1048576 ? (v / 1048576).toLocaleString(lang, { maximumFractionDigits: 1 }) + ' MiB' : Math.round(v / 1024).toLocaleString(lang) + ' KiB';
  const node = (tag, text, className) => { const n = document.createElement(tag); if (text != null) n.textContent = text; if (className) n.className = className; return n; };
  const ROOT = '/scoutro/api/v1/kg/';
  const VIEWS = ['overview', 'objects', 'object', 'source', 'settings'];
  const params = new URLSearchParams(location.search);
  let view = VIEWS.includes(params.get('view')) ? params.get('view') : 'overview';
  let collection = params.get('collection') || '';
  let offset = Math.max(0, parseInt(params.get('offset') || '0', 10) || 0);
  let generation = 0;
  const LIMIT = 25;

  function message(text = '') { $('message').textContent = text; }
  function guarded(task) { const promise = task(), run = generation; promise.catch(e => { if (run === generation) message(e.message); }); }
  function validCollection(name) { return name === '' || /^[A-Za-z0-9_-]{1,64}$/.test(name); }

  async function api(path, query = {}) {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) if (v != null && v !== '') p.set(k, v);
    if (collection) p.set('collection', collection);
    const response = await fetch(ROOT + path + (p.toString() ? '?' + p : ''), { credentials: 'same-origin', cache: 'no-store' });
    let body = null; try { body = await response.json(); } catch (_) { /* reported below */ }
    if (!response.ok) {
      const code = body?.error?.code || 'error';
      const text = response.status === 404 ? t('not_found') : code === 'kg_disabled' ? t('disabled') : code === 'kg_unavailable' ? t('unavailable') : t('error');
      throw new Error(text + ' (HTTP ' + response.status + ', ' + code + ')');
    }
    return body;
  }

  function link(text, query) {
    const a = node('a', text);
    const p = new URLSearchParams(query); if (collection) p.set('collection', collection);
    a.href = 'ScoutroKnowledge_p.html?' + p;
    a.addEventListener('click', e => { if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey) return; e.preventDefault(); navigate(query); });
    return a;
  }
  function external(url) {
    if (!/^https?:\/\//i.test(url || '')) return node('span', url || t('missing'));
    const a = node('a', url); a.href = url; a.rel = 'noopener noreferrer nofollow'; a.target = '_blank'; return a;
  }
  function badge(text, kind) { return node('span', text, 'skg-badge skg-' + kind); }
  function qualityBadge(q) { return badge(t(q), 'q-' + q); }
  function stats(id, rows) {
    const dl = $(id); dl.replaceChildren();
    for (const [k, v] of rows) { dl.append(node('dt', t(k))); const dd = node('dd'); if (v instanceof Node) dd.append(v); else dd.textContent = fmt(v); dl.append(dd); }
  }
  function predicate(p) { return labels['p_' + p] || p; }

  function navigate(query) {
    const p = new URLSearchParams(query); if (collection) p.set('collection', collection);
    history.pushState(null, '', 'ScoutroKnowledge_p.html' + (p.toString() ? '?' + p : ''));
    load();
  }

  // A budget meter: blue below notice, then amber (notice), orange (warning), red (brake and full).
  function meterInto(box, used, budget, level) {
    if (used == null || !budget) return;
    const pct = Math.min(100, Math.round(used * 100 / budget));
    const meter = node('div', null, 'skg-meter'); meter.setAttribute('role', 'img'); meter.setAttribute('aria-label', t('used') + ' ' + pct + ' %');
    const cls = { notice: ' skg-notice', warning: ' skg-warn', brake: ' skg-critical', full: ' skg-critical' }[level] || '';
    const fill = node('div', null, 'skg-meter-fill' + cls); fill.style.width = pct + '%';
    meter.append(fill); box.append(meter, node('p', bytes(used) + ' / ' + bytes(budget) + ' (' + pct + ' %)', 'sseo-note'));
  }

  // Backups: the state, the last result, the schedule and the files with download and restore.
  async function backupsInto(b, run) {
    const box = $('backups'); box.replaceChildren();
    if (!b) { stats('backup', []); return; }
    const last = b.last ? t('backup_' + b.last.result) + ' · ' + date(b.last.at) + (b.last.file ? ' · ' + b.last.file : '')
      + (b.last.reason ? ' · ' + b.last.reason : '') : t('none');
    stats('backup', [['backup_state', t('backup_' + b.state)], ['backup_last', last],
      ['backup_next', b.nextScheduledAt ? date(b.nextScheduledAt) : t('none')], ['backup_files', fmt(b.files) + ' · ' + bytes(b.bytes)]]);
    let list = null;
    try { list = await fetch(ROOT + 'backups', { credentials: 'same-origin', cache: 'no-store' }).then(r => r.ok ? r.json() : null); } catch (_) { list = null; }
    if (run !== generation || !list) return;
    if (!list.items.length) { box.append(node('p', t('no_backups'), 'sseo-note')); return; }
    const table = node('table', null, 'table table-striped scoutro-cards'); const head = table.createTHead().insertRow();
    const cols = ['file', 'kind', 'size', 'created', 'actions'];
    for (const c of cols) head.append(node('th', c === 'actions' ? '' : t(c)));
    const body = table.createTBody();
    for (const f of list.items) {
      const row = body.insertRow();
      const actions = node('span');
      const a = node('a', t('download')); a.href = ROOT + 'backups/' + encodeURIComponent(f.file); a.setAttribute('download', f.file);
      const r = node('button', t('restore'), 'btn btn-default btn-xs'); r.type = 'button'; r.dataset.skgRestore = f.file;
      r.addEventListener('click', () => guarded(() => control('restore', { backup: f.file })));
      actions.append(a, ' ', r);
      const cells = [f.file, t('kind_' + f.kind), bytes(f.bytes), f.created_at ? date(Date.parse(f.created_at)) : t('missing'), actions];
      cells.forEach((v, i) => { const td = row.insertCell(); td.dataset.label = i === 4 ? '' : t(cols[i]); if (v instanceof Node) td.append(v); else td.textContent = v; });
    }
    box.append(table);
  }

  // The identity rebuild: phase, progress, the check before the swap and the buttons for its phase.
  const REBUILD_ACTIVE = ['building', 'verifying', 'awaiting_confirmation', 'swapping'];
  let rebuildTimer = 0;
  function rebuildInto(rb) {
    rb = rb || { phase: 'none' };
    const p = rb.progress || {}, v = rb.verify;
    const rows = [['rebuild_phase', t('phase_' + rb.phase) + (rb.startedAt ? ' · ' + date(rb.startedAt) : '')]];
    if (rb.phase === 'building' || rb.phase === 'verifying')
      rows.push(['rebuild_progress', t('rebuild_progress_value').replace('%1', fmt(p.scanned)).replace('%2', fmt(p.published)).replace('%3', fmt(p.queue))]);
    if (rb.budgetBytes) rows.push(['rebuild_space', bytes(p.storage?.usedBytes) + ' / ' + bytes(rb.budgetBytes)]);
    if (v) rows.push(['rebuild_check', t('rebuild_check_value').replace('%1', fmt(v.documentsBefore)).replace('%2', fmt(v.documentsAfter))
      .replace('%3', fmt(v.entitiesBefore)).replace('%4', fmt(v.entitiesAfter)).replace('%5', fmt(v.quickCheck))]);
    if (rb.awaitingConfirmation) rows.push(['rebuild_check', t('rebuild_brake')]);
    if (rb.keptAs) rows.push(['rebuild_kept', rb.keptAs]);
    if (rb.idsRedirected != null) rows.push(['rebuild_ids', rb.idsRedirected]);
    if (rb.error) rows.push(['rebuild_error', rb.error]);
    stats('rebuild', rows);
    const active = REBUILD_ACTIVE.includes(rb.phase);
    document.querySelector('[data-skg-action="rebuild"]').hidden = active;
    document.querySelector('[data-skg-action="rebuild_cancel"]').hidden = !active || rb.phase === 'swapping';
    document.querySelector('[data-skg-action="rebuild_confirm"]').hidden = !rb.awaitingConfirmation;
    return active;
  }

  // ------------------------------------------------------------------ overview
  async function overview() {
    const run = ++generation; message(t('loading'));
    const s = await fetch(ROOT + 'status', { credentials: 'same-origin', cache: 'no-store' }).then(r => r.json());
    if (run !== generation) return;
    let objects = null;
    if (s.state === 'running') { try { objects = (await api('entities', { limit: 1 })).total; } catch (_) { objects = null; } }
    if (run !== generation) return;
    const cards = $('cards'); cards.replaceChildren();
    const card = (k, v) => { const dl = node('dl', null, 'sseo-card'); dl.append(node('dt', t(k)), node('dd', fmt(v))); cards.append(dl); };
    card('state', s.state + (s.reason ? ' · ' + s.reason : ''));
    card('objects_count', objects);
    card('lag', s.sync?.lag?.pending);
    const st = s.storage || {}, jl = s.jsonld || {};
    const used = st.usedBytes, budget = st.budgetBytes;
    const bar = $('budget'); bar.replaceChildren();
    meterInto(bar, used, budget, st.level);
    const jbar = $('jsonld-budget'); jbar.replaceChildren();
    meterInto(jbar, jl.estimatedBytes, jl.maxTotalBytes, jl.level);
    // warning, brake and full are shown as a banner; notice only in the level row
    const banner = $('budget-banner'); banner.replaceChildren();
    for (const [what, level, pct] of [['graph', st.level, st.usedPercent], ['jsonld', jl.level, jl.usedPercent]]) {
      if (level === 'warning' || level === 'brake' || level === 'full')
        banner.append(node('p', t('level_' + level + '_' + what).replace('%1', fmt(pct)), 'skg-banner skg-banner-' + level));
    }
    banner.hidden = !banner.children.length;
    stats('storage', [['used', bytes(used)], ['budget', bytes(budget)], ['level', st.level ? t('level_' + st.level) : null],
      ['growth', st.growthAllowed == null ? null : t(st.growthAllowed ? 'yes' : 'no')],
      ['reasons', (st.reasons || []).map(r => r.code).join(', ') || t('none')],
      ['jsonld', jl.state ? t('jsonld_' + jl.state) + (jl.reason && jl.state !== 'active' ? ' · ' + jl.reason : '') : null],
      ['jsonld_used', jl.estimatedBytes == null ? null : bytes(jl.estimatedBytes) + ' / ' + bytes(jl.maxTotalBytes)
        + (jl.level ? ' · ' + t('level_' + jl.level) : '')]]);
    const sy = s.sync || {};
    stats('sync', [['state', sy.state], ['queue', sy.queue?.items], ['lag', sy.lag?.pending], ['published', sy.processed?.published],
      ['reconcile', sy.reconcile ? (sy.reconcile.pending ? (sy.reconcile.reason || '') : (sy.reconcile.last?.state || t('none'))) : null],
      ['awaiting', sy.reconcile ? t(sy.reconcile.awaitingConfirmation ? 'yes' : 'no') : null]]);
    const l = s.llm || {};
    stats('llm', [['state', l.state + (l.reason ? ' · ' + l.reason : '')], ['model', l.model], ['queue', l.queue?.items],
      ['done', l.documents?.done], ['failed', l.documents?.failed], ['skipped', l.documents?.skipped], ['calls', l.processed?.calls],
      ['dropped', l.processed?.droppedUngrounded], ['breaker', l.breaker ? t(l.breaker.open ? 'open' : 'closed') : null]]);
    document.querySelector('[data-skg-action="confirm_reconcile"]').hidden = !sy.reconcile?.awaitingConfirmation;
    await backupsInto(s.backup, run);
    if (run !== generation) return;
    // while a rebuild runs, the overview refreshes itself
    clearTimeout(rebuildTimer);
    if (rebuildInto(s.rebuild)) rebuildTimer = setTimeout(() => { if (run === generation && view === 'overview') guarded(overview); }, 3000);
    const events = $('events'); events.replaceChildren();
    if (s.events?.length) {
      const table = node('table', null, 'table table-striped scoutro-cards'); const head = table.createTHead().insertRow();
      for (const h of ['at', 'code', 'detail']) head.append(node('th', t(h)));
      const body = table.createTBody();
      for (const e of s.events) { const row = body.insertRow(); for (const [h, v] of [['at', date(e.at)], ['code', e.code], ['detail', e.detail]]) { const td = row.insertCell(); td.textContent = fmt(v); td.dataset.label = t(h); } }
      events.append(table);
    } else events.append(node('p', t('none')));
    message(s.state === 'running' ? '' : s.state === 'disabled' ? t('disabled') : t('unavailable') + (s.reason ? ' (' + s.reason + ')' : ''));
  }

  async function control(action, extra) {
    if (action === 'confirm_reconcile' && !window.confirm(labels.confirm_question || action)) return;
    if (action === 'restore' && !window.confirm(t('restore_question').replace('%1', extra.backup))) return;
    if (action === 'rebuild' && !window.confirm(t('rebuild_question'))) return;
    if (action === 'rebuild_confirm' && !window.confirm(t('rebuild_confirm_question'))) return;
    message(t('loading'));
    const response = await fetch(ROOT + 'control', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(Object.assign({ action }, extra || {})) });
    let body = null; try { body = await response.json(); } catch (_) { /* below */ }
    if (!response.ok) throw new Error(t('error') + ' (HTTP ' + response.status + ', ' + (body?.error?.code || 'error') + ')');
    await overview(); message(t('done_action'));
  }

  // ------------------------------------------------------------------- objects
  async function objects() {
    const run = ++generation; message(t('loading'));
    const q = { q: $('q').value.trim(), type: $('type').value, quality: $('quality').value, host: $('host').value.trim(), offset, limit: LIMIT };
    const data = await api('entities', q); if (run !== generation) return;
    const body = $('entities').tBodies[0]; body.replaceChildren();
    const heads = [...$('entities').tHead.rows[0].cells].map(c => c.textContent);
    for (const e of data.items) {
      const row = body.insertRow();
      const cells = [link(e.name || e.id, { view: 'object', id: e.id }), t(e.type) + (e.kind ? ' · ' + e.kind : ''), qualityBadge(e.quality), fmt(e.counts?.sources), date(e.last_confirmed)];
      cells.forEach((c, i) => { const td = row.insertCell(); td.dataset.label = heads[i]; if (c instanceof Node) td.append(c); else td.textContent = c; });
    }
    $('range').textContent = data.total ? t('range').replace('%1', fmt(offset + 1)).replace('%2', fmt(offset + data.items.length)).replace('%3', fmt(data.total)) : '';
    $('prev').disabled = offset === 0; $('next').disabled = offset + LIMIT >= data.total;
    message(data.total ? '' : t('empty'));
  }
  function objectQuery() {
    const p = { view: 'objects' };
    for (const k of ['q', 'type', 'quality', 'host']) { const v = $(k).value.trim(); if (v) p[k] = v; }
    return p;
  }

  // -------------------------------------------------------------------- object
  async function object(id) {
    const run = ++generation; message(t('loading'));
    const e = await api('entities/' + encodeURIComponent(id)); if (run !== generation) return;
    if (e.redirect) { navigate({ view: 'object', id: e.redirect }); return; }
    $('object-name').textContent = e.name || e.id;
    const ids = node('span'); (e.identifiers || []).forEach((i, n) => { if (n) ids.append(', '); ids.append(i.scheme + ': ' + i.value + ' '); ids.append(qualityBadge(i.quality)); });
    const hosts = node('span'); (e.hosts || []).forEach((h, n) => { if (n) hosts.append(', '); hosts.append(link(h, { view: 'objects', host: h })); });
    const dups = node('span'); (e.possible_duplicates || []).forEach((d, n) => { if (n) dups.append(', '); dups.append(link(d, { view: 'object', id: d })); });
    stats('object-facts', [['type', t(e.type) + (e.kind ? ' · ' + e.kind : '')], ['quality', qualityBadge(e.quality)], ['aliases', (e.aliases || []).join(', ') || t('none')],
      ['identifiers', ids.childNodes.length ? ids : t('none')], ['hosts', hosts.childNodes.length ? hosts : t('none')],
      ['duplicates', dups.childNodes.length ? dups : t('none')], ['first_seen', date(e.first_seen)], ['last_confirmed', date(e.last_confirmed)],
      ['statements', fmt(e.counts?.statements)], ['sources', fmt(e.counts?.sources)]]);
    await statements(e.id, 'out', $('out'), 0, run);
    await statements(e.id, 'in', $('in'), 0, run);
    message(); $('object-name').focus();
  }

  async function statements(id, direction, target, from, run) {
    const data = await api('entities/' + encodeURIComponent(id) + '/statements', { direction, offset: from, limit: 100 });
    if (run !== generation) return;
    if (from === 0) target.replaceChildren();
    if (!data.items.length && from === 0) { target.append(node('p', t('none'))); return; }
    const list = node('ul', null, 'skg-statements');
    for (const s of data.items) list.append(statementItem(s, direction));
    target.append(list);
    if (from + data.items.length < data.total) {
      const more = node('button', t('more'), 'btn btn-default'); more.type = 'button';
      more.addEventListener('click', () => { more.remove(); guarded(() => statements(id, direction, target, from + data.items.length, generation)); });
      target.append(more);
    }
  }

  function statementItem(s, direction) {
    const li = node('li', null, 'skg-statement');
    const head = node('div', null, 'skg-statement-head');
    if (direction === 'in') { head.append(link(s.subject_name || s.subject, { view: 'object', id: s.subject }), ' '); }
    head.append(node('span', predicate(s.predicate), 'skg-predicate'), ' ');
    if (s.object.entity) head.append(link(s.object.name || s.object.entity, { view: 'object', id: s.object.entity }));
    else head.append(node('span', s.object.value, 'skg-value'));
    li.append(head);
    const meta = node('div', null, 'skg-meta');
    meta.append(qualityBadge(s.quality));
    if (s.certainty === 'hedged') meta.append(badge(t('hedged'), 'hedged'));
    if (s.kinds?.length === 1 && s.kinds[0] === 'llm') meta.append(badge(t('llm_only'), 'llm'));
    for (const k of s.kinds || []) meta.append(badge(k, 'kind'));
    meta.append(node('span', t('sources') + ': ' + fmt(s.sources) + ' · ' + t('last_confirmed') + ': ' + date(s.last_confirmed), 'sseo-note'));
    li.append(meta);
    if (s.evidence) { li.append(evidenceList([s.evidence], false)); return li; }
    const toggle = node('button', t('evidence'), 'btn btn-default btn-sm'); toggle.type = 'button'; toggle.setAttribute('aria-expanded', 'false');
    const box = node('div', null, 'skg-evidence'); box.hidden = true;
    toggle.addEventListener('click', () => {
      const open = box.hidden; box.hidden = !open; toggle.setAttribute('aria-expanded', String(open)); toggle.textContent = t(open ? 'hide_evidence' : 'evidence');
      if (open && !box.dataset.loaded) guarded(async () => {
        box.replaceChildren(node('p', t('loading')));
        const data = await api('statements/' + encodeURIComponent(s.id) + '/evidence', { limit: 50 });
        box.replaceChildren(evidenceList(data.items, true)); box.dataset.loaded = '1';
      });
    });
    li.append(toggle, box);
    return li;
  }

  function evidenceList(items, withPage) {
    const ul = node('ul', null, 'skg-evidence-list');
    for (const e of items) {
      const li = node('li');
      if (e.excerpt) li.append(node('blockquote', e.excerpt, 'skg-excerpt'));
      const dl = node('dl', null, 'sseo-stats');
      const add = (k, v) => { dl.append(node('dt', t(k))); const dd = node('dd'); if (v instanceof Node) dd.append(v); else dd.textContent = fmt(v); dl.append(dd); };
      if (withPage) {
        add('page', external(e.url));
        const links = node('span');
        links.append(link(t('source_view'), { view: 'source', doc: e.doc_id }), ' · ');
        const ib = node('a', t('index_browser')); const p = new URLSearchParams({ view: 'urls', q: e.url || '' }); if (collection) p.set('collection', collection);
        ib.href = 'IndexBrowser_p.html?' + p; links.append(ib); add('source_view', links);
        add('collections', (e.collections || []).join(', '));
        add('doc_state', e.state); add('loaded', date(e.loaded_at));
      }
      add('extractor', e.extractor || e.kind); add('observed', date(e.observed_at));
      if (e.certainty === 'hedged') add('quality', t('hedged'));
      li.append(dl); ul.append(li);
    }
    if (!items.length) ul.append(node('li', t('none')));
    return ul;
  }

  // -------------------------------------------------------------------- source
  async function source(doc) {
    const run = ++generation; message(t('loading'));
    const data = await api('sources/' + encodeURIComponent(doc), { limit: 100 }); if (run !== generation) return;
    const s = data.source;
    stats('source-facts', [['page', external(s.url)], ['hosts', s.host ? link(s.host, { view: 'objects', host: s.host }) : null],
      ['collections', (s.collections || []).join(', ')], ['doc_state', s.state], ['loaded', date(s.loaded_at)],
      ['tiers', (s.tiers || []).join(', ') || t('none')], ['llm_status', s.llm ? s.llm.status + (s.llm.reason ? ' · ' + s.llm.reason : '') : t('none')],
      ['statements', fmt(data.total)]]);
    const list = node('ul', null, 'skg-statements');
    for (const item of data.items) {
      const li = statementItem(item, 'out');
      li.querySelector('.skg-statement-head').prepend(link(item.subject_name || item.subject, { view: 'object', id: item.subject }), ' ');
      list.append(li);
    }
    $('source-items').replaceChildren(data.items.length ? list : node('p', t('none')));
    message(); $('source-title').focus();
  }

  // ------------------------------------------------------------------ settings
  async function settings() {
    const run = ++generation; message(t('loading'));
    const s = await fetch(ROOT + 'status', { credentials: 'same-origin', cache: 'no-store' }).then(r => r.json());
    if (run !== generation) return;
    const c = s.config || {};
    const kinds = Object.entries(c.llmKinds || {}).map(([k, v]) => k + ': ' + (v.length ? v.join(', ') : t('none'))).join('\n');
    stats('config', [['enabled', t(s.enabled ? 'yes' : 'no')], ['valid', c.valid == null ? null : t(c.valid ? 'yes' : 'no')],
      ['followed', (c.collections || []).join(', ') || t('none')], ['llm_collections', (c.llmCollections || []).join(', ') || t('none')],
      ['llm_kinds', kinds || t('none')], ['budget', bytes(s.storage?.budgetBytes)], ['model', s.llm?.model],
      ['chat', c.chat == null ? null : !c.chat.enabled ? t('no') : t('chat_detail').replace('%1', fmt(c.chat.maxFacts))
        .replace('%2', fmt(c.chat.maxChars)).replace('%3', fmt(c.chat.timeoutMs)).replace('%4', t(c.chat.allowGuests ? 'yes' : 'no'))]]);
    const errors = $('config-errors'); errors.replaceChildren();
    if (c.errors?.length) {
      const table = node('table', null, 'table table-striped scoutro-cards'); const head = table.createTHead().insertRow();
      head.append(node('th', t('key')), node('th', t('problem')));
      const body = table.createTBody();
      for (const e of c.errors) { const row = body.insertRow(); for (const [h, v] of [['key', e.key], ['problem', e.message]]) { const td = row.insertCell(); td.textContent = v; td.dataset.label = t(h); } }
      errors.append(table);
    }
    message(s.enabled ? '' : t('disabled'));
  }

  // ---------------------------------------------------------------------- views
  function load() {
    const p = new URLSearchParams(location.search);
    view = VIEWS.includes(p.get('view')) ? p.get('view') : 'overview';
    collection = p.get('collection') || '';
    offset = Math.max(0, parseInt(p.get('offset') || '0', 10) || 0);
    $('collection').value = collection;
    for (const v of VIEWS) $(v).hidden = v !== view;
    const nav = view === 'object' || view === 'source' ? 'objects' : view;
    for (const a of document.querySelectorAll('[data-skg-view]')) {
      if (a.dataset.skgView === nav) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
      const q = new URLSearchParams(a.dataset.skgView === 'overview' ? {} : { view: a.dataset.skgView }); if (collection) q.set('collection', collection);
      a.href = 'ScoutroKnowledge_p.html' + (q.toString() ? '?' + q : '');
    }
    if (!validCollection(collection)) { message(t('invalid_collection')); return; }
    if (view === 'overview') guarded(overview);
    else if (view === 'objects') {
      for (const k of ['q', 'type', 'quality', 'host']) $(k).value = p.get(k) || '';
      guarded(objects);
    } else if (view === 'object') guarded(() => object(p.get('id') || ''));
    else if (view === 'source') guarded(() => source(p.get('doc') || ''));
    else guarded(settings);
  }

  $('scope').addEventListener('submit', e => {
    e.preventDefault();
    const name = $('collection').value.trim();
    if (!validCollection(name)) { message(t('invalid_collection')); return; }
    collection = name;
    const p = Object.fromEntries(new URLSearchParams(location.search)); delete p.collection; delete p.offset;
    navigate(p);
  });
  $('search').addEventListener('submit', e => { e.preventDefault(); navigate(objectQuery()); });
  $('prev').addEventListener('click', () => navigate({ ...objectQuery(), offset: Math.max(0, offset - LIMIT) }));
  $('next').addEventListener('click', () => navigate({ ...objectQuery(), offset: offset + LIMIT }));
  for (const b of document.querySelectorAll('[data-skg-action]')) b.addEventListener('click', () => guarded(() => control(b.dataset.skgAction)));
  for (const a of document.querySelectorAll('[data-skg-view]')) a.addEventListener('click', e => {
    if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey) return; e.preventDefault();
    navigate(a.dataset.skgView === 'overview' ? {} : { view: a.dataset.skgView });
  });
  window.addEventListener('popstate', load);
  fetch('/scoutro/api/v1/collections', { credentials: 'same-origin', cache: 'no-store' }).then(r => r.ok ? r.json() : null).then(data => {
    for (const item of data?.collections || []) { const o = document.createElement('option'); o.value = item.id; $('collection-list').append(o); }
  }).catch(() => {});
  load();
})();
