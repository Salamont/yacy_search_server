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
  const de = String(lang || '').toLowerCase().startsWith('de');
  const pct = v => v == null ? t('missing') : Math.round(v * 100) + ' %';
  const ROOT = '/scoutro/api/v1/kg/';
  const VIEWS = ['overview', 'objects', 'object', 'network', 'compare', 'source', 'settings'];
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
    const pausedNote = $('paused-note'); if (pausedNote) pausedNote.hidden = !(s.storage?.reasons || []).some(r => r.code === 'manual');
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
    const bt = sy.lag?.byType;
    const byType = bt ? [['pending_new', bt.new], ['pending_update', bt.update], ['pending_delete', bt.delete],
      ['pending_reconcile', bt.reconcile], ['pending_captured', bt.captured], ['pending_llm', s.llm?.queue?.items]]
      .filter(([, n]) => n != null).map(([k, n]) => t(k) + ' ' + fmt(n)).join(' · ') : null;
    stats('sync', [['state', sy.state], ['queue', sy.queue?.items], ['lag', sy.lag?.pending], ['pending_types', byType],
      ['published', sy.processed?.published],
      ['reconcile', sy.reconcile ? (sy.reconcile.pending ? (sy.reconcile.reason || '') : (sy.reconcile.last?.state || t('none'))) : null],
      ['awaiting', sy.reconcile ? t(sy.reconcile.awaitingConfirmation ? 'yes' : 'no') : null]]);
    const l = s.llm || {};
    stats('llm', [['state', l.state + (l.reason ? ' · ' + l.reason : '')], ['model', l.model], ['queue', l.queue?.items],
      ['done', l.documents?.done], ['failed', l.documents?.failed], ['skipped', l.documents?.skipped], ['calls', l.processed?.calls],
      ['dropped', l.processed?.droppedUngrounded], ['breaker', l.breaker ? t(l.breaker.open ? 'open' : 'closed') : null]]);
    document.querySelector('[data-skg-action="confirm_reconcile"]').hidden = !sy.reconcile?.awaitingConfirmation;
    // vocabulary 2: the vocabularies in force, the derived layer and the upgrade of the last start
    const vo = s.vocabulary || {}, dv = s.derived || {}, up = s.upgrade;
    const derivedRows = dv.last ? Object.values(dv.last.byKind || {}).reduce((a, b) => a + b, 0) : null;
    stats('vocab', [['graph_vocabulary', vo.graphVocabulary], ['vocab_version', vo.version],
      ['vocab_counts', vo.categories == null ? null : t('vocab_counts').replace('%1', fmt(vo.categories)).replace('%2', fmt(vo.naceCodes))],
      ['vocab_collections', Object.entries(vo.collections || {}).map(([c, v]) => c + ': ' + (v.vocabulary || t('none')) + (v.jobs ? ' · ' + t('jobs_on') : '')).join('\n') || t('none')],
      ['vocab_problems', (vo.problems || []).join('\n') || t('none')],
      ['derived_last', dv.enabled === false ? t('derived_off') : t('derived_value').replace('%1', fmt(derivedRows))
        .replace('%2', dv.lastRun ? date(dv.lastRun) : t('none')).replace('%3', fmt(dv.intervalMinutes))],
      ['upgrade', up ? t('upgrade_value').replace('%1', fmt(up.from)).replace('%2', fmt(up.to)).replace('%3', up.waiting
        ? t('upgrade_waiting').replace('%1', up.hold) : up.backup ? t('upgrade_copy').replace('%1', up.backup) : date(up.at)) : t('none')]]);
    $('upgrade-note').hidden = !up?.waiting;
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
    await facetsInto(run);
    if (run !== generation) return;
    const q = { q: $('q').value.trim(), type: $('type').value, quality: $('quality').value, host: $('host').value.trim(),
      industry: $('industry').value, category: $('category').value, audience: $('audience').value, offset, limit: LIMIT };
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
    for (const k of OBJECT_FILTERS) { const v = $(k).value.trim(); if (v) p[k] = v; }
    return p;
  }

  // The filter values the visible graph holds (industries, categories, audiences), loaded once per collection.
  const OBJECT_FILTERS = ['q', 'type', 'quality', 'host', 'industry', 'category', 'audience'];
  let facets = null, facetsFor = null;
  async function loadFacets() {
    if (facets && facetsFor === collection) return facets;
    facets = await api('facets'); facetsFor = collection; return facets;
  }
  const facetLabel = f => (de ? f.label_de : f.label_en) || f.label || f.code;
  function fillSelect(select, groups, keep) {
    const first = select.options[0]; select.replaceChildren(first);
    for (const [title, items, prefix] of groups) {
      if (!items?.length) continue;
      const g = document.createElement('optgroup'); g.label = title;
      for (const f of items) { const o = node('option', facetLabel(f) + (prefix ? ' (' + f.code + ')' : '') + ' · ' + fmt(f.entities)); o.value = f.code; g.append(o); }
      select.append(g);
    }
    select.value = keep || '';
    if (keep && select.value !== keep) { const o = node('option', keep); o.value = keep; select.append(o); select.value = keep; }
  }
  async function facetsInto(run) {
    let f = null;
    try { f = await loadFacets(); } catch (_) { f = null; }
    if (run !== generation || !f) return;
    fillSelect($('industry'), [[t('industry'), f.industries, true]], $('industry').value || new URLSearchParams(location.search).get('industry'));
    fillSelect($('category'), [[t('categories'), f.categories, false]], $('category').value || new URLSearchParams(location.search).get('category'));
    fillSelect($('audience'), [[t('p_customer_type'), f.customer_types, false], [t('p_audience_segment'), f.segments, false]],
      $('audience').value || new URLSearchParams(location.search).get('audience'));
  }

  // -------------------------------------------------------------------- object
  async function object(id) {
    const run = ++generation; message(t('loading'));
    const [e, b] = await Promise.all([api('entities/' + encodeURIComponent(id)), api('entities/' + encodeURIComponent(id) + '/business')]);
    if (run !== generation) return;
    if (e.redirect) { navigate({ view: 'object', id: e.redirect }); return; }
    $('object-name').textContent = e.name || e.id;
    const net = $('object-network'); const np = new URLSearchParams({ view: 'network', id: e.id }); if (collection) np.set('collection', collection);
    net.href = 'ScoutroKnowledge_p.html?' + np; net.textContent = t('network_open');
    net.onclick = ev => { if (ev.button !== 0 || ev.ctrlKey || ev.metaKey || ev.shiftKey) return; ev.preventDefault(); navigate({ view: 'network', id: e.id }); };
    const ids = node('span'); (e.identifiers || []).forEach((i, n) => { if (n) ids.append(', '); ids.append(i.scheme + ': ' + i.value + ' '); ids.append(qualityBadge(i.quality)); });
    const hosts = node('span'); (e.hosts || []).forEach((h, n) => { if (n) hosts.append(', '); hosts.append(link(h, { view: 'objects', host: h })); });
    const dups = node('span'); (e.possible_duplicates || []).forEach((d, n) => { if (n) dups.append(', '); dups.append(link(d, { view: 'object', id: d })); });
    stats('object-facts', [['type', t(e.type) + (e.kind ? ' · ' + e.kind : '')], ['quality', qualityBadge(e.quality)], ['aliases', (e.aliases || []).join(', ') || t('none')],
      ['identifiers', ids.childNodes.length ? ids : t('none')], ['hosts', hosts.childNodes.length ? hosts : t('none')],
      ['duplicates', dups.childNodes.length ? dups : t('none')], ['first_seen', date(e.first_seen)], ['last_confirmed', date(e.last_confirmed)],
      ['statements', fmt(e.counts?.statements)], ['sources', fmt(e.counts?.sources)]]);
    businessInto(b);
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
    li.append(...evidenceToggle(s.id));
    return li;
  }

  // A button that loads and shows the evidence of one statement below itself.
  function evidenceToggle(statementId) {
    const toggle = node('button', t('evidence'), 'btn btn-default btn-sm'); toggle.type = 'button'; toggle.setAttribute('aria-expanded', 'false');
    const box = node('div', null, 'skg-evidence'); box.hidden = true;
    toggle.addEventListener('click', () => {
      const open = box.hidden; box.hidden = !open; toggle.setAttribute('aria-expanded', String(open)); toggle.textContent = t(open ? 'hide_evidence' : 'evidence');
      if (open && !box.dataset.loaded) guarded(async () => {
        box.replaceChildren(node('p', t('loading')));
        const data = await api('statements/' + encodeURIComponent(statementId) + '/evidence', { limit: 50 });
        box.replaceChildren(evidenceList(data.items, true)); box.dataset.loaded = '1';
      });
    });
    return [toggle, box];
  }

  // ------------------------------------------------------------ business view
  // Values exactly as the source published them: amounts, units and conditions are never converted or averaged.
  function money(v) {
    if (!v) return t('missing');
    const num = n => n == null || n === '' ? '' : Number(n).toLocaleString(lang, { minimumFractionDigits: Number.isInteger(Number(n)) ? 0 : 2, maximumFractionDigits: 2 })
      + (v.currency ? ' ' + v.currency : '');
    let text;
    if (v.kind === 'range' || (v.amount == null && v.min != null && v.max != null)) text = t('k_range').replace('%1', num(v.min)).replace('%2', num(v.max));
    else if (v.kind === 'from') text = t('k_from').replace('%1', num(v.amount ?? v.min));
    else if (v.kind === 'up_to') text = t('k_up_to').replace('%1', num(v.amount ?? v.max));
    else text = num(v.amount);
    const unit = v.unit && v.unit !== 'other' ? (labels['u_' + v.unit] || v.unit) : (v.unit_text || '');
    return [text, unit].filter(Boolean).join(' ');
  }
  function conditions(v) {
    const out = [];
    if (v.vat) out.push(labels['vat_' + v.vat] || v.vat);
    if (v.care_level) out.push(t('care_level') + ' ' + v.care_level);
    if (v.own_share) out.push(t('own_share'));
    if (v.gross === true) out.push(t('gross'));
    if (v.conditions) out.push(v.conditions);
    if (v.eligible) out.push(v.eligible);
    return out.join(' · ');
  }
  const codeLabel = item => (de ? item.label_de : item.label_en) || item.label || '';
  function valueText(pred, item) {
    const v = item.value;
    if (v && typeof v === 'object') {
      if (pred === 'price' || pred === 'salary') return [money(v), conditions(v)].filter(Boolean).join(' · ');
      if (pred === 'contact_point') return [v.function || v.name, v.phone, v.email, v.hours].filter(Boolean).join(' · ');
      if (pred === 'service_area') return v.kind === 'radius' ? t('area_radius').replace('%1', fmt(v.radius_km)) + (v.around ? ' · ' + v.around : '')
        : v.kind === 'national' ? t('area_national') + (v.name ? ' · ' + v.name : '') : v.kind === 'international' ? t('area_international') + (v.name ? ' · ' + v.name : '') : (v.name || '');
      return Object.entries(v).map(([k, x]) => k + ': ' + x).join(' · ');
    }
    const label = codeLabel(item);
    return label && label !== v ? label + ' (' + v + ')' : String(v ?? '');
  }
  function statusBadge(status) { return badge(t('s_' + status), 's-' + status); }
  function factMeta(item) {
    const meta = node('div', null, 'skg-meta');
    if (item.status) meta.append(statusBadge(item.status));
    if (item.kinds?.length === 1 && item.kinds[0] === 'llm') meta.append(badge(t('llm_only'), 'llm'));
    for (const k of item.kinds || []) meta.append(badge(k, 'kind'));
    meta.append(node('span', [item.confidence != null ? t('confidence') + ': ' + pct(item.confidence) : null,
      item.sources != null ? t('sources') + ': ' + fmt(item.sources) : null,
      item.last_confirmed ? t('last_confirmed') + ': ' + date(item.last_confirmed) : null].filter(Boolean).join(' · '), 'sseo-note'));
    return meta;
  }
  function factItem(head, item) {
    const li = node('li', null, 'skg-statement');
    li.append(head instanceof Node ? head : node('div', head, 'skg-statement-head'), factMeta(item));
    if (item.statement) li.append(...evidenceToggle(item.statement));
    return li;
  }
  function literalItem(pred, item) {
    const head = node('div', null, 'skg-statement-head');
    head.append(node('span', predicate(pred), 'skg-predicate'), ' ', node('span', valueText(pred, item), 'skg-value'));
    if (pred === 'website' || pred === 'social_profile' || pred === 'contact_form' || pred === 'directions') {
      if (/^https?:\/\//i.test(item.value || '')) { head.lastChild.replaceWith(external(item.value)); }
    }
    return factItem(head, item);
  }
  function list(items, render) { const ul = node('ul', null, 'skg-statements'); for (const i of items) ul.append(render(i)); return ul; }
  function section(key, ...children) {
    const sec = node('section', null, 'sseo-panel skg-bsec'); sec.id = 'skg-sec-' + key;
    const h = node('h3', t('sec_' + key)); h.id = 'skg-sec-' + key + '-title'; sec.setAttribute('aria-labelledby', h.id);
    sec.append(h, ...children.filter(Boolean)); return sec;
  }
  function entityLink(ref) { return ref ? link(ref.name || ref.id, { view: 'object', id: ref.id }) : node('span', t('missing')); }
  function relationItem(r) {
    const head = node('div', null, 'skg-statement-head');
    if (r.direction === 'in') head.append(entityLink(r.other), ' ', node('span', predicate(r.predicate), 'skg-predicate'), ' ', node('span', $('object-name').textContent));
    else head.append(node('span', predicate(r.predicate), 'skg-predicate'), ' ', entityLink(r.other));
    return factItem(head, r);
  }
  function derivedItem(d) {
    const li = node('li', null, 'skg-statement skg-derived');
    const head = node('div', null, 'skg-statement-head');
    head.append(badge(t('s_' + (d.kind === 'linked_to' ? 'weak' : d.kind === 'same_operator' ? 'derived' : 'suggested')), 'd-' + d.kind), ' ', entityLink(d.other));
    li.append(head);
    const meta = node('div', null, 'skg-meta');
    meta.append(node('span', [t('confidence') + ': ' + pct(d.confidence), d.computed_at ? t('computed') + ': ' + date(d.computed_at) : null].filter(Boolean).join(' · '), 'sseo-note'));
    li.append(meta);
    if (d.reason) li.append(node('p', t('reason_label') + ': ' + d.reason, 'skg-reason'));
    li.append(node('p', t('no_fact'), 'sseo-note'));
    return li;
  }
  // the date a price holds for: as the source wrote it (2026-09), else the day it was last seen
  function asOfText(p) {
    const stated = p.as_of_basis === 'stated';
    return ((stated ? p.value?.as_of : null) || p.as_of || t('missing')) + ' · ' + t(stated ? 'as_of_stated' : 'as_of_seen')
      + (p.valid_through ? ' · ' + t('valid_through') + ' ' + p.valid_through : '');
  }
  function priceTable(prices) {
    const table = node('table', null, 'table table-striped scoutro-cards skg-prices');
    const cols = ['sec_services', 'price', 'conditions', 'as_of', 'status', 'sources'];
    const head = table.createTHead().insertRow(); for (const c of cols) head.append(node('th', t(c)));
    const body = table.createTBody();
    for (const p of prices) {
      const row = body.insertRow(); row.dataset.status = p.status;
      const status = node('span'); status.append(statusBadge(p.status));
      if (p.stale_since) status.append(node('span', t('stale_since').replace('%1', p.stale_since).replace('%2', fmt(p.stale_after_days)), 'sseo-note'));
      if (p.conflicts_with?.length) status.append(node('span', t('conflicts_with'), 'sseo-note'));
      const ev = node('span'); ev.append(fmt(p.sources) + ' ', ...evidenceToggle(p.statement));
      const service = p.service ? link(p.service_name || p.service, { view: 'object', id: p.service }) : node('span', t('general_price'));
      const cells = [service, money(p.value), conditions(p.value) || t('none'), asOfText(p), status, ev];
      cells.forEach((c, i) => { const td = row.insertCell(); td.dataset.label = t(cols[i]); if (c instanceof Node) td.append(c); else td.textContent = c; });
    }
    return node('div', null, 'sseo-scroll').appendChild(table).parentNode;
  }
  function jobItem(j) {
    const li = node('li', null, 'skg-statement skg-job');
    const head = node('div', null, 'skg-statement-head');
    head.append(link(j.title || j.id, { view: 'object', id: j.id }), ' ', badge(t('job_' + j.status), 'j-' + j.status));
    li.append(head);
    const dl = node('dl', null, 'sseo-stats');
    const add = (k, v) => { if (v == null || v === '' || (Array.isArray(v) && !v.length)) return; dl.append(node('dt', t(k))); const dd = node('dd'); if (v instanceof Node) dd.append(v); else dd.textContent = Array.isArray(v) ? v.join(', ') : v; dl.append(dd); };
    add('p_employment_type', (j.employment_type || []).map(e => codeLabel(e) || e.value));
    add('location', j.location ? entityLink(j.location) : null);
    add('p_occupational_field', j.occupational_field);
    add('industry', (j.industry || []).map(e => valueText('industry', e)));
    if (j.salary?.length) { const sal = node('span'); j.salary.forEach((x, n) => { if (n) sal.append(' · '); sal.append(money(x.value) + (x.as_of ? ' (' + t('as_of') + ' ' + date(x.as_of) + ')' : '') + ' '); sal.append(...evidenceToggle(x.statement)); }); add('salary', sal); }
    add('p_date_posted', j.date_posted); add('p_start_date', j.start_date); add('p_valid_through', j.valid_through);
    add('p_application_route', (j.application_route || []).map(a => valueText('application_route', a)));
    add('ended_at', j.ended_at ? date(j.ended_at) : null); add('last_confirmed', j.last_confirmed ? date(j.last_confirmed) : null);
    li.append(dl);
    return li;
  }
  const SECTIONS = ['overview', 'industry', 'services', 'prices', 'contacts', 'relations', 'jobs', 'audiences', 'matches', 'sources', 'facts'];
  function businessInto(b) {
    const box = $('business'); box.replaceChildren();
    if (b.industry && (b.industry.main || b.industry.secondary?.length || b.industry.categories?.length)) {
      const m = b.industry.main, parts = [];
      if (m) {
        const dl = node('dl', null, 'sseo-stats');
        dl.append(node('dt', t('main_industry')), node('dd', (m.label || m.code) + ' (' + m.code + ')'));
        dl.append(node('dt', t('classification')), node('dd', m.classification + ' · ' + (m.path || []).join(' › ')));
        parts.push(dl, factMeta(m));
        if (m.basis === 'common_level') parts.push(node('p', t('basis_common_level'), 'sseo-note'));
      }
      if (b.industry.secondary?.length) parts.push(node('h4', t('secondary_industries')), list(b.industry.secondary, i => factItem((i.label || i.code) + ' (' + i.code + ')', i)));
      if (b.industry.categories?.length) parts.push(node('h4', t('industry_groups')), list(b.industry.categories, i => literalItem('industry_category', i)));
      box.append(section('industry', ...parts));
    }
    if (b.categories?.length) box.append(section('services', list(b.categories, i => literalItem('category', i))));
    if (b.services?.length) box.append(section('services', list(b.services, sv => {
      const head = node('div', null, 'skg-statement-head'); head.append(link(sv.name || sv.id, { view: 'object', id: sv.id }));
      const li = factItem(head, sv);
      if (sv.categories?.length) li.insertBefore(node('p', sv.categories.map(c => codeLabel(c) || c.value).join(' · '), 'skg-value'), li.children[1]);
      if (sv.description) li.insertBefore(node('p', sv.description, 'skg-value'), li.children[1]);
      return li;
    })));
    if (b.prices?.length) box.append(section('prices', priceTable(b.prices)));
    if (b.contacts && Object.keys(b.contacts).length) {
      const items = []; for (const [p, l] of Object.entries(b.contacts)) for (const i of l) items.push([p, i]);
      box.append(section('contacts', list(items, ([p, i]) => literalItem(p, i))));
    }
    if (b.relations && (b.relations.facts?.length || Object.keys(b.relations.derived || {}).length)) {
      const parts = [];
      if (b.relations.facts?.length) parts.push(list(b.relations.facts, relationItem));
      for (const [k, l] of Object.entries(b.relations.derived || {})) if (l.length) parts.push(node('h4', t('m_' + k)), list(l, derivedItem));
      box.append(section('relations', ...parts));
    }
    const jobs = b.jobs?.items || (b.job ? [b.job] : []);
    if (jobs.length || b.jobs?.hidden_ended) box.append(section('jobs', jobs.length ? list(jobs, jobItem) : null,
      b.jobs?.hidden_ended ? node('p', t('hidden_ended').replace('%1', fmt(b.jobs.hidden_ended)), 'sseo-note') : null));
    if (b.audiences) {
      const a = b.audiences, parts = [];
      const declared = []; for (const [p, l] of Object.entries(a.declared || {})) for (const i of l) declared.push([p, i]);
      if (declared.length) parts.push(node('h4', t('declared')), list(declared, ([p, i]) => p === 'serves_place' ? relationItem(i) : literalItem(p, i)));
      if (a.observed?.length) parts.push(node('h4', t('observed_customers')), list(a.observed, relationItem));
      if (a.suggested?.length) parts.push(node('h4', t('suggested_customers')), list(a.suggested, derivedItem), node('p', t('suggestion_note'), 'sseo-note'));
      if (parts.length) box.append(section('audiences', ...parts));
    }
    if (b.suggested_matches && Object.keys(b.suggested_matches).length) {
      const parts = [node('p', t('suggestion_note'), 'sseo-note')];
      for (const [k, l] of Object.entries(b.suggested_matches)) if (l.length) parts.push(node('h4', t('m_' + k)), list(l, derivedItem));
      box.append(section('matches', ...parts));
    }
    if (b.sources?.length) {
      const table = node('table', null, 'table table-striped scoutro-cards');
      const cols = ['page', 'collections', 'doc_state', 'loaded'];
      const head = table.createTHead().insertRow(); for (const c of cols) head.append(node('th', t(c)));
      const body = table.createTBody();
      for (const src of b.sources) {
        const row = body.insertRow(); const pageCell = node('span'); pageCell.append(external(src.url), ' · ', link(t('source_view'), { view: 'source', doc: src.doc_id }));
        [pageCell, (src.collections || []).join(', '), src.state, date(src.loaded_at)].forEach((c, i) => { const td = row.insertCell(); td.dataset.label = t(cols[i]); if (c instanceof Node) td.append(c); else td.textContent = fmt(c); });
      }
      box.append(section('sources', node('div', null, 'sseo-scroll').appendChild(table).parentNode));
    }
    // the table of contents lists the sections this object has
    const toc = $('toc'); toc.replaceChildren();
    const seen = new Set();
    for (const key of SECTIONS) {
      const target = document.getElementById('skg-sec-' + key); if (!target || seen.has(key)) continue; seen.add(key);
      const a = node('a', t('sec_' + key)); a.href = '#skg-sec-' + key;
      a.addEventListener('click', ev => { ev.preventDefault(); target.scrollIntoView({ block: 'start' }); const h = target.querySelector('h3'); if (h) { h.tabIndex = -1; h.focus({ preventScroll: true }); } });
      toc.append(a);
    }
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

  // ------------------------------------------------------------------- network
  // The neighbourhood of one object as an SVG drawn here (no external library), with the same data as a list.
  const SVG = 'http://www.w3.org/2000/svg';
  const svg = (tag, attrs) => { const n = document.createElementNS(SVG, tag); for (const [k, v] of Object.entries(attrs || {})) n.setAttribute(k, v); return n; };
  const GROUPS = {
    business: ['parent_of', 'subsidiary_of', 'carrier_of', 'member_of', 'association_member', 'partner_of', 'cooperation_with', 'customer_of',
      'reference_for', 'supplier_of', 'service_provider_for', 'brand_of', 'certified_by', 'funded_by', 'sponsored_by'],
    structure: ['operates', 'part_of', 'located_at', 'in_place', 'serves_place', 'job_location'],
    offers: ['offers', 'hiring_organization'],
    values: ['industry', 'target_industry', 'audience_segment', 'customer_type'],
  };
  const FILTERS = ['business', 'structure', 'offers', 'values', 'derived', 'weak', 'suggested', 'stale'];
  const FILTER_DEFAULTS = ['business', 'structure', 'offers', 'values', 'derived'];
  let graph = null, graphUrls = [];

  function networkFilters(p) {
    const f = p.get('f'); const on = new Set(f == null ? FILTER_DEFAULTS : f.split(',').filter(x => FILTERS.includes(x)));
    return on;
  }
  function networkQuery(on, depth, from) {
    const q = { depth, limit: 50, offset: from, weak: on.has('weak') ? 'true' : null, derived: on.has('derived') ? null : 'false',
      suggested: on.has('suggested') ? 'true' : null, values: on.has('values') ? null : 'false', include: on.has('stale') ? 'stale' : null };
    if (!['business', 'structure', 'offers'].every(g => on.has(g))) {
      const types = [];
      for (const g of ['business', 'structure', 'offers', 'values']) if (on.has(g)) types.push(...GROUPS[g]);
      if (on.has('derived')) types.push('same_operator');
      if (on.has('weak')) types.push('linked_to');
      if (on.has('suggested')) types.push('suggested_customer', 'suggested_partner');
      q.types = types.join(',') || 'none';
    }
    return q;
  }

  async function network(p) {
    const run = ++generation; message(t('loading'));
    $('net-svg').dataset.state = 'loading';
    const id = p.get('id') || '';
    const depth = p.get('depth') === '2' ? 2 : 1;
    const on = networkFilters(p);
    $('depth').value = String(depth);
    for (const f of FILTERS) $('f-' + f).checked = on.has(f);
    $('f-list').checked = p.get('list') === '1';
    $('net-wrap').hidden = $('f-list').checked;
    const q = networkQuery(on, depth, 0);
    if (q.types === 'none') { graph = { center: id, nodes: [], edges: [], truncated: false }; renderNetwork(); message(t('net_empty')); return; }
    const data = await api('entities/' + encodeURIComponent(id) + '/neighborhood', q); if (run !== generation) return;
    if (data.redirect) { navigate({ ...Object.fromEntries(p), view: 'network', id: data.redirect }); return; }
    graph = { center: data.center, nodes: data.nodes, edges: data.edges, truncated: data.truncated, next: data.next_offset, depth, on };
    const center = data.nodes.find(n => n.id === data.center);
    $('network-title').textContent = t('network_of').replace('%1', center?.label || data.center);
    renderNetwork();
    message(data.edges.length ? '' : t('net_empty')); $('network-title').focus();
  }

  async function networkMore() {
    if (!graph?.truncated || graph.next == null) return;
    const run = generation;
    const data = await api('entities/' + encodeURIComponent(graph.center) + '/neighborhood', networkQuery(graph.on, graph.depth, graph.next));
    if (run !== generation) return;
    const known = new Set(graph.nodes.map(n => n.id)), edges = new Set(graph.edges.map(e => e.id + '|' + e.from + '|' + e.to));
    for (const n of data.nodes) if (!known.has(n.id)) { graph.nodes.push(n); known.add(n.id); }
    for (const e of data.edges) if (!edges.has(e.id + '|' + e.from + '|' + e.to)) graph.edges.push(e);
    graph.truncated = data.truncated; graph.next = data.next_offset;
    renderNetwork();
  }

  const nodeLabel = n => n.value ? ((de ? n.label_de : n.label_en) || n.label || n.code) : (n.label || n.id);
  const shortLabel = text => text.length > 24 ? text.slice(0, 23) + '…' : text;
  function nodeGroup(n) { return n.value ? 3 : n.type === 'organization' ? 0 : n.type === 'facility' || n.type === 'site' || n.type === 'place' ? 1 : 2; }
  function edgeName(e) { return labels['p_' + e.type] || e.type; }

  function layout(nodes, edges, center) {
    const pos = new Map(); pos.set(center, { x: 0, y: 0, a: 0 });
    const first = nodes.filter(n => n.depth === 1).sort((a, b) => nodeGroup(a) - nodeGroup(b) || nodeLabel(a).localeCompare(nodeLabel(b)));
    const second = nodes.filter(n => n.depth === 2);
    const r1 = Math.max(150, Math.min(260, 26 * first.length / Math.PI)), r2 = r1 + 140;
    first.forEach((n, i) => { const a = -Math.PI / 2 + 2 * Math.PI * i / Math.max(1, first.length); pos.set(n.id, { x: r1 * Math.cos(a), y: r1 * Math.sin(a), a }); });
    // depth 2 sits on an outer ring next to the neighbour it hangs on
    const byParent = new Map();
    for (const n of second) {
      const e = edges.find(x => (x.to === n.id && pos.has(x.from) && x.from !== center) || (x.from === n.id && pos.has(x.to) && x.to !== center));
      const parent = e ? (e.to === n.id ? e.from : e.to) : center;
      if (!byParent.has(parent)) byParent.set(parent, []); byParent.get(parent).push(n);
    }
    const slot = 2 * Math.PI / Math.max(1, first.length);
    for (const [parent, kids] of byParent) {
      const base = pos.get(parent)?.a ?? 0, spread = Math.min(slot * 0.9, 0.22 * kids.length);
      kids.forEach((n, i) => { const a = base + (kids.length === 1 ? 0 : -spread / 2 + spread * i / (kids.length - 1)); pos.set(n.id, { x: r2 * Math.cos(a), y: r2 * Math.sin(a), a }); });
    }
    return { pos, radius: second.length ? r2 : r1 };
  }

  function renderNetwork() {
    const g = graph, box = $('net-svg'); box.replaceChildren();
    for (const u of graphUrls) URL.revokeObjectURL(u); graphUrls = [];
    const byId = new Map(g.nodes.map(n => [n.id, n]));
    const { pos, radius } = layout(g.nodes, g.edges, g.center);
    const half = radius + 110;
    box.setAttribute('viewBox', `${-half} ${-half} ${2 * half} ${2 * half}`);
    box.setAttribute('aria-label', $('network-title').textContent);
    const defs = svg('defs');
    for (const st of ['confirmed', 'uncertain', 'stale', 'weak', 'derived', 'suggested']) {
      const m = svg('marker', { id: 'skg-arrow-' + st, viewBox: '0 0 10 10', refX: '10', refY: '5', markerWidth: '7', markerHeight: '7', orient: 'auto-start-reverse' });
      m.append(svg('path', { d: 'M 0 0 L 10 5 L 0 10 z', class: 'skg-arrow skg-e-' + st })); defs.append(m);
    }
    box.append(defs);
    const edgeLayer = svg('g', { class: 'skg-edges' }), nodeLayer = svg('g', { class: 'skg-nodes' });
    box.append(edgeLayer, nodeLayer);
    const pairs = new Map();
    const edgeEls = [];
    for (const e of g.edges) {
      const a = pos.get(e.from), b = pos.get(e.to); if (!a || !b) continue;
      const key = [e.from, e.to].sort().join('|'); const k = pairs.get(key) || 0; pairs.set(key, k + 1);
      // parallel edges between the same two nodes bend apart; arrows stop at the node's rim
      const dx = b.x - a.x, dy = b.y - a.y, len = Math.hypot(dx, dy) || 1, nx = -dy / len, ny = dx / len;
      const bend = k === 0 ? 0 : (k % 2 ? 1 : -1) * Math.ceil(k / 2) * 26;
      const rimA = e.from === g.center ? 22 : 15, rimB = e.to === g.center ? 22 : 15;
      const sx = a.x + dx / len * rimA, sy = a.y + dy / len * rimA, tx = b.x - dx / len * (rimB + 2), ty = b.y - dy / len * (rimB + 2);
      const cx = (sx + tx) / 2 + nx * bend, cy = (sy + ty) / 2 + ny * bend;
      const d = `M ${sx.toFixed(1)} ${sy.toFixed(1)} Q ${cx.toFixed(1)} ${cy.toFixed(1)} ${tx.toFixed(1)} ${ty.toFixed(1)}`;
      const wrap = svg('g', { class: 'skg-edge skg-e-' + e.status, tabindex: '0', role: 'button', 'data-edge': e.id, 'data-from': e.from, 'data-to': e.to });
      wrap.setAttribute('aria-label', (nodeLabel(byId.get(e.from) || { id: e.from })) + ' ' + edgeName(e) + ' ' + (nodeLabel(byId.get(e.to) || { id: e.to })) + ', ' + t('s_' + e.status));
      wrap.append(svg('path', { d, class: 'skg-hit' }), svg('path', { d, class: 'skg-line', 'marker-end': 'url(#skg-arrow-' + e.status + ')',
        'stroke-width': (1 + 2 * Math.min(1, e.confidence || 0)).toFixed(1) }));
      const title = svg('title'); title.textContent = wrap.getAttribute('aria-label'); wrap.append(title);
      const show = () => edgeDetail(e, byId); wrap.addEventListener('mouseenter', show); wrap.addEventListener('focus', show); wrap.addEventListener('click', show);
      wrap.addEventListener('keydown', ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); show(); } else if (ev.key === 'Escape') hideDetail(); });
      edgeLayer.append(wrap); edgeEls.push(wrap);
    }
    const many = g.nodes.length > 30;
    const ordered = [...g.nodes].sort((a, b) => a.depth - b.depth);
    for (const n of ordered) {
      const p = pos.get(n.id); if (!p) continue;
      const cls = 'skg-node skg-t-' + (n.value ? 'value' : n.type) + (n.id === g.center ? ' skg-center' : '') + (n.depth === 2 && many ? ' skg-quiet' : '');
      const el = svg('g', { class: cls, tabindex: '0', role: n.value ? 'img' : 'link', transform: `translate(${p.x.toFixed(1)} ${p.y.toFixed(1)})`, 'data-node': n.id });
      el.setAttribute('aria-label', nodeLabel(n) + ' (' + (n.value ? t(n.type) : t(n.type) + (n.kind ? ' · ' + n.kind : '')) + (n.id === g.center ? ', ' + t('net_center') : '') + ')');
      const r = n.id === g.center ? 20 : 13;
      el.append(n.value ? svg('rect', { x: -r, y: -r * 0.75, width: 2 * r, height: 1.5 * r, rx: 4 }) : svg('circle', { r }));
      const text = svg('text', { y: r + 14, 'text-anchor': 'middle' }); text.textContent = shortLabel(nodeLabel(n)); el.append(text);
      const title = svg('title'); title.textContent = nodeLabel(n); el.append(title);
      const show = () => { nodeDetail(n, byId); for (const w of edgeEls) w.classList.toggle('skg-hl', w.dataset.from === n.id || w.dataset.to === n.id); };
      el.addEventListener('mouseenter', show); el.addEventListener('focus', show);
      const open = () => { if (!n.value && n.id !== g.center) navigate({ view: 'object', id: n.id }); else show(); };
      el.addEventListener('click', open);
      el.addEventListener('keydown', ev => { if (ev.key === 'Enter') { ev.preventDefault(); open(); } else if (ev.key === 'Escape') hideDetail(); });
      nodeLayer.append(el);
    }
    // legend, counters, paging, exports and the list
    const legend = $('net-legend'); legend.replaceChildren();
    for (const [st, key] of [['confirmed', 'legend_fact'], ['uncertain', 'legend_uncertain'], ['derived', 'legend_derived'], ['weak', 'legend_weak'], ['suggested', 'legend_suggested']]) {
      const li = node('li'); const s = svg('svg', { width: '36', height: '10', 'aria-hidden': 'true' });
      s.append(svg('line', { x1: '0', y1: '5', x2: '36', y2: '5', class: 'skg-line skg-e-' + st })); li.append(s, ' ', t(key)); legend.append(li);
    }
    { const li = node('li'); const s = svg('svg', { width: '14', height: '12', 'aria-hidden': 'true' }); s.append(svg('rect', { x: '1', y: '1', width: '12', height: '10', rx: '2', class: 'skg-legend-value' })); li.append(s, ' ', t('legend_value')); legend.append(li); }
    $('net-count').textContent = t('net_count').replace('%1', fmt(g.nodes.length)).replace('%2', fmt(g.edges.length)) + (g.truncated ? ' · ' + t('net_more_hint') : '');
    $('net-more').hidden = !g.truncated;
    const json = new Blob([JSON.stringify({ schema: 'scoutro.kg.business.v1', center: g.center, collection: collection || null, nodes: g.nodes, edges: g.edges }, null, 2)], { type: 'application/json' });
    const graphml = new Blob([toGraphml(g)], { type: 'application/graphml+xml' });
    for (const [id, blob] of [['net-json', json], ['net-graphml', graphml]]) { const u = URL.createObjectURL(blob); graphUrls.push(u); $(id).href = u; }
    const body = $('net-table').tBodies[0]; body.replaceChildren();
    const heads = [...$('net-table').tHead.rows[0].cells].map(c => c.textContent);
    for (const e of g.edges) {
      const row = body.insertRow();
      const end = id => { const n = byId.get(id) || { id }; return n.value || id === g.center ? node('span', nodeLabel(n)) : link(nodeLabel(n), { view: 'object', id }); };
      const ev = node('span'); ev.append(fmt(e.evidence) + ' ');
      if (e.fact && e.id.startsWith('kgs_')) ev.append(...evidenceToggle(e.id)); else ev.append(node('span', t('no_fact'), 'sseo-note'));
      [end(e.from), edgeName(e), end(e.to), statusBadge(e.status), pct(e.confidence), ev].forEach((c, i) => { const td = row.insertCell(); td.dataset.label = heads[i]; if (c instanceof Node) td.append(c); else td.textContent = c; });
    }
    hideDetail();
    box.dataset.state = 'ready';
    // on a narrow screen the drawing is wider than the page: start with its centre in view
    const wrap = $('net-wrap'); requestAnimationFrame(() => { wrap.scrollLeft = Math.max(0, (wrap.scrollWidth - wrap.clientWidth) / 2); });
  }

  function hideDetail() { $('net-detail').hidden = true; $('net-detail').replaceChildren(); }
  function nodeDetail(n, byId) {
    const box = $('net-detail'); box.replaceChildren(); box.hidden = false;
    box.append(node('h3', nodeLabel(n)));
    box.append(node('p', n.value ? t(n.type) + ' · ' + n.code : t(n.type) + (n.kind ? ' · ' + n.kind : ''), 'sseo-note'));
    const mine = graph.edges.filter(e => e.from === n.id || e.to === n.id);
    box.append(list(mine.slice(0, 12), e => {
      const other = byId.get(e.from === n.id ? e.to : e.from) || { id: e.from === n.id ? e.to : e.from };
      const li = node('li', null, 'skg-statement');
      li.append(node('div', (e.from === n.id ? edgeName(e) + ' → ' : '← ' + edgeName(e) + ' · ') + nodeLabel(other), 'skg-statement-head'));
      li.append(node('div', t('s_' + e.status) + ' · ' + t('confidence') + ': ' + pct(e.confidence) + ' · ' + t('sources') + ': ' + fmt(e.evidence), 'sseo-note'));
      return li;
    }));
    if (!n.value && n.id !== graph.center) { const a = link(t('open_object'), { view: 'object', id: n.id }); a.className = 'btn btn-default btn-sm'; box.append(a); }
  }
  function edgeDetail(e, byId) {
    const box = $('net-detail'); box.replaceChildren(); box.hidden = false;
    const from = byId.get(e.from) || { id: e.from }, to = byId.get(e.to) || { id: e.to };
    box.append(node('h3', nodeLabel(from) + ' · ' + edgeName(e) + ' · ' + nodeLabel(to)));
    const meta = node('div', null, 'skg-meta'); meta.append(statusBadge(e.status), node('span', t('confidence') + ': ' + pct(e.confidence) + ' · ' + t('sources') + ': ' + fmt(e.evidence), 'sseo-note'));
    box.append(meta);
    if (e.fact && e.id.startsWith('kgs_')) box.append(...evidenceToggle(e.id));
    else box.append(node('p', t('no_fact'), 'sseo-note'));
  }
  function toGraphml(g) {
    const x = v => String(v ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    const out = ['<?xml version="1.0" encoding="UTF-8"?>', '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
      '<key id="label" for="node" attr.name="label" attr.type="string"/>', '<key id="type" for="node" attr.name="type" attr.type="string"/>',
      '<key id="kind" for="node" attr.name="kind" attr.type="string"/>', '<key id="depth" for="node" attr.name="depth" attr.type="int"/>',
      '<key id="relation" for="edge" attr.name="relation" attr.type="string"/>', '<key id="status" for="edge" attr.name="status" attr.type="string"/>',
      '<key id="confidence" for="edge" attr.name="confidence" attr.type="double"/>', '<key id="evidence" for="edge" attr.name="evidence" attr.type="int"/>',
      '<key id="fact" for="edge" attr.name="fact" attr.type="boolean"/>', '<graph id="' + x(g.center) + '" edgedefault="directed">'];
    for (const n of g.nodes) out.push(`<node id="${x(n.id)}"><data key="label">${x(nodeLabel(n))}</data><data key="type">${x(n.value ? n.type : n.type)}</data>`
      + (n.kind ? `<data key="kind">${x(n.kind)}</data>` : '') + `<data key="depth">${n.depth}</data></node>`);
    g.edges.forEach((e, i) => out.push(`<edge id="e${i}" source="${x(e.from)}" target="${x(e.to)}"><data key="relation">${x(e.type)}</data><data key="status">${x(e.status)}</data>`
      + `<data key="confidence">${e.confidence ?? 0}</data><data key="evidence">${e.evidence ?? 0}</data><data key="fact">${e.fact ? 'true' : 'false'}</data></edge>`));
    out.push('</graph>', '</graphml>');
    return out.join('\n') + '\n';
  }
  function networkParams() {
    const p = new URLSearchParams(location.search);
    const q = { view: 'network', id: p.get('id') || graph?.center || '' };
    if ($('depth').value === '2') q.depth = '2';
    const on = FILTERS.filter(f => $('f-' + f).checked);
    if (on.join(',') !== FILTER_DEFAULTS.join(',')) q.f = on.join(',');
    if ($('f-list').checked) q.list = '1';
    return q;
  }

  // ------------------------------------------------------------------- compare
  async function compare(category) {
    const run = ++generation; message(t('loading'));
    let f = null; try { f = await loadFacets(); } catch (_) { f = null; }
    if (run !== generation) return;
    fillSelect($('compare-category'), [[t('categories'), f?.categories || [], false]], category);
    const body = $('compare-table').tBodies[0]; body.replaceChildren();
    if (!category) { message(t('choose_category')); return; }
    const data = await api('compare', { category }); if (run !== generation) return;
    const heads = [...$('compare-table').tHead.rows[0].cells].map(c => c.textContent);
    for (const r of data.rows) {
      const providers = () => { const box = node('span'); r.providers.forEach((pv, i) => { if (i) box.append(', '); box.append(link(pv.name || pv.id, { view: 'object', id: pv.id })); if (pv.locality) box.append(' · ' + pv.locality); }); return box; };
      const service = () => link(r.service.name || r.service.id, { view: 'object', id: r.service.id });
      const prices = r.prices.length ? r.prices : [null];
      for (const p of prices) {
        const row = body.insertRow(); if (p) row.dataset.status = p.status;
        const status = node('span'); if (p) { status.append(statusBadge(p.status)); if (p.conflicts_with?.length) status.append(node('span', t('conflicts_with'), 'sseo-note')); }
        const ev = node('span'); if (p) ev.append(fmt(p.sources) + ' ', ...evidenceToggle(p.statement));
        [providers(), service(), p ? money(p.value) : t('compare_no_price'), p ? (conditions(p.value) || t('none')) : '', p ? asOfText(p) : t('missing'), status, ev]
          .forEach((c, i) => { const td = row.insertCell(); td.dataset.label = heads[i]; if (c instanceof Node) td.append(c); else td.textContent = c; });
      }
    }
    message(data.rows.length ? '' : t('compare_empty'));
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
    const nav = view === 'object' || view === 'source' || view === 'network' ? 'objects' : view;
    for (const a of document.querySelectorAll('[data-skg-view]')) {
      if (a.dataset.skgView === nav) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
      const q = new URLSearchParams(a.dataset.skgView === 'overview' ? {} : { view: a.dataset.skgView }); if (collection) q.set('collection', collection);
      a.href = 'ScoutroKnowledge_p.html' + (q.toString() ? '?' + q : '');
    }
    if (!validCollection(collection)) { message(t('invalid_collection')); return; }
    if (view === 'overview') guarded(overview);
    else if (view === 'objects') {
      for (const k of OBJECT_FILTERS) { const el = $(k); el.value = p.get(k) || ''; if (p.get(k) && el.value !== p.get(k)) { const o = node('option', p.get(k)); o.value = p.get(k); el.append(o); el.value = p.get(k); } }
      guarded(objects);
    } else if (view === 'object') guarded(() => object(p.get('id') || ''));
    else if (view === 'network') guarded(() => network(p));
    else if (view === 'compare') guarded(() => compare(p.get('category') || ''));
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
  $('net-form').addEventListener('submit', e => { e.preventDefault(); navigate(networkParams()); });
  $('f-list').addEventListener('change', () => { $('net-wrap').hidden = $('f-list').checked; history.replaceState(null, '', 'ScoutroKnowledge_p.html?' + new URLSearchParams({ ...networkParams(), ...(collection ? { collection } : {}) })); });
  $('net-more').addEventListener('click', () => guarded(networkMore));
  $('compare-form').addEventListener('submit', e => { e.preventDefault(); navigate({ view: 'compare', category: $('compare-category').value }); });
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
