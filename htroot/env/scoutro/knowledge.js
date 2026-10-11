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
  const VIEWS = ['overview', 'objects', 'object', 'network', 'services', 'compare', 'source', 'history', 'settings'];
  const params = new URLSearchParams(location.search);
  let view = VIEWS.includes(params.get('view')) ? params.get('view') : 'overview';
  let collection = params.get('collection') || '';
  let offset = Math.max(0, parseInt(params.get('offset') || '0', 10) || 0);
  let generation = 0;
  const LIMIT = 25;

  function message(text = '') { $('message').textContent = text; }
  function guarded(task) { const promise = task(), run = generation; promise.catch(e => { if (run === generation) message(e.message); }); }
  let allowed = []; // the collections of the select: nothing else is ever used as the scope

  async function api(path, query = {}) {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(query)) if (v != null && v !== '') p.set(k, v);
    if (collection && !Object.hasOwn(query, 'collection')) p.set('collection', collection);
    const response = await fetch(ROOT + path + (p.toString() ? '?' + p : ''), { credentials: 'same-origin', cache: 'no-store' });
    let body = null; try { body = await response.json(); } catch (_) { /* reported below */ }
    if (!response.ok) {
      const code = body?.error?.code || 'error';
      const text = response.status === 404 ? t('not_found') : code === 'kg_disabled' ? t('disabled') : code === 'kg_unavailable' ? t('unavailable') : t('error');
      const error = new Error(text + ' (HTTP ' + response.status + ', ' + code + ')'); error.status = response.status; throw error;
    }
    return body;
  }

  function link(text, query, hash) {
    const a = node('a', text);
    const p = new URLSearchParams(query); if (collection && !Object.hasOwn(query, 'collection')) p.set('collection', collection);
    a.href = 'ScoutroKnowledge_p.html?' + p + (hash ? '#' + hash : '');
    a.addEventListener('click', e => { if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey) return; e.preventDefault(); navigate(query, hash); });
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
  // The name to show (package 6.1): the stated name, else what the read API derived for display (a legal name, the site's
  // operator, the domain), else a typed "unnamed …". A technical ID (kge_…, kgs_…) is never shown as a name.
  const UNNAMED = { organization: 'unnamed_organization', facility: 'unnamed_facility', site: 'unnamed_site', place: 'unnamed_place',
    service: 'unnamed_service', job: 'unnamed_job' };
  function shown(x, type, prefix = '') {
    const name = x?.[prefix + 'display_name'] || x?.[prefix ? prefix + 'name' : 'name'];
    return name && !/^kg[es]_[a-z2-7]{20}$/.test(name) ? name : t(UNNAMED[type || x?.type] || 'unnamed');
  }
  function shownSource(x, prefix = '') { const src = x?.[prefix + 'display_name_source']; return src && src !== 'fact' ? src : null; }
  // a link to an object under its shown name; a name that is not stated by the sources is marked as such
  function nameLink(x, id, type, prefix = '') {
    const a = link(shown(x, type, prefix), { view: 'object', id, ...(x?.target_collection ? { collection: x.target_collection } : {}) });
    const src = shownSource(x, prefix);
    if (src) { a.classList.add('skg-name-' + src); a.title = t('name_src_' + src); }
    return a;
  }
  function nameNote(x, prefix = '') {
    const src = shownSource(x, prefix);
    return src ? node('span', t('name_src_' + src) + (x?.[prefix + 'display_host'] && src === 'domain' ? ' · ' + x[prefix + 'display_host'] : ''), 'sseo-note skg-name-note') : null;
  }

  function navigate(query, hash) {
    const p = new URLSearchParams(query); if (collection && !Object.hasOwn(query, 'collection')) p.set('collection', collection);
    history.pushState(null, '', 'ScoutroKnowledge_p.html' + (p.toString() ? '?' + p : '') + (hash ? '#' + hash : ''));
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
      // package 6.3: delete one backup, after a question that names it with its type, size and date
      const d = node('button', t('delete_backup'), 'btn btn-danger btn-xs'); d.type = 'button'; d.dataset.skgDelete = f.file;
      d.addEventListener('click', () => guarded(() => deleteBackup(f, d)));
      actions.append(a, ' ', r, ' ', d);
      const cells = [f.file, t('kind_' + f.kind), bytes(f.bytes), f.created_at ? date(Date.parse(f.created_at)) : t('missing'), actions];
      cells.forEach((v, i) => { const td = row.insertCell(); td.dataset.label = i === 4 ? '' : t(cols[i]); if (v instanceof Node) td.append(v); else td.textContent = v; });
    }
    box.append(table);
  }

  async function deleteBackup(f, button) {
    const when = f.created_at ? date(Date.parse(f.created_at)) : t('missing');
    let question = t('delete_backup_question').replace('%1', f.file).replace('%2', t('kind_' + f.kind)).replace('%3', bytes(f.bytes)).replace('%4', when);
    if (f.kind === 'before_upgrade') question += '\n\n' + t('delete_backup_upgrade_note');
    if (!window.confirm(question)) return;
    button.disabled = true; message(t('loading'));
    const response = await fetch(ROOT + 'control', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'delete_backup', backup: f.file }) });
    let body = null; try { body = await response.json(); } catch (_) { /* below */ }
    if (!response.ok) {
      button.disabled = false;
      const code = body?.error?.code || 'error';
      const text = code === 'operation_running' ? t('delete_backup_busy') : code === 'backup_not_found' ? t('delete_backup_gone') : t('delete_backup_failed');
      await overview();
      throw new Error(text.replace('%1', f.file) + ' (HTTP ' + response.status + ', ' + code + ')');
    }
    await overview(); message(t('delete_backup_done').replace('%1', f.file));
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
    // package 6.3: the deterministic graph and the LLM enrichment are two layers; the model is the one of the LLM selection
    const layers = llmLayers(s);
    card('kg_deterministic', layers.deterministic);
    card('kg_llm', layers.llm);
    card('kg_model', layers.model);
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
    const scheduler = sy.scheduler, scan = sy.reconcile?.current;
    stats('sync', [['state', sy.state], ['sync_scheduler', scheduler ? t('sync_task_' + scheduler.state) : null],
      ['sync_last_started', scheduler?.lastStartedAt ? date(scheduler.lastStartedAt) : null],
      ['sync_last_finished', scheduler?.lastFinishedAt ? date(scheduler.lastFinishedAt) : null],
      ['sync_completed_ticks', scheduler?.completedTicks],
      ['sync_task_error', scheduler?.lastError || sy.lastError],
      ['sync_scanned', scan?.scanned], ['sync_cursor', scan?.cursor],
      ['queue', sy.queue?.items], ['lag', sy.lag?.pending], ['pending_types', byType],
      ['published', sy.processed?.published],
      ['reconcile', sy.reconcile ? (sy.reconcile.pending ? (sy.reconcile.reason || '') : (sy.reconcile.last?.state || t('none'))) : null],
      ['awaiting', sy.reconcile ? t(sy.reconcile.awaitingConfirmation ? 'yes' : 'no') : null]]);
    const l = s.llm || {}, lp = l.processed || {}, so = l.structuredOutput && typeof l.structuredOutput === 'object' ? l.structuredOutput : null;
    stats('llm', [['kg_llm', layers.llm], ['state', l.state + (l.reason ? ' · ' + l.reason : '')], ['model', l.model || t('kg_no_model')],
      ['structured_output', so?.mode ? t('so_mode_' + so.mode) + (so.capability ? ' · ' + t('so_cap_' + so.capability) : '')
        + (so.api ? ' · ' + t('so_api_' + so.api) : '') : null],
      ['so_requests', so?.requests ? t('so_requests_value').replace('%1', fmt(so.requests.json_schema)).replace('%2', fmt(so.requests.json_object))
        .replace('%3', fmt(so.requests.none)).replace('%4', fmt(so.rejections)) : null],
      ...timingRows(l.timing), ['queue', l.queue?.items],
      ['done', l.documents?.done], ['failed', l.documents?.failed], ['skipped', l.documents?.skipped], ['calls', lp.calls], ['schedule_started', lp.requestStarts],
      ['llm_accepted', lp.entitiesAccepted == null ? null : [lp.entitiesAccepted, lp.claimsAccepted, lp.valuesAccepted].map(fmt).join(' · ')],
      ['dropped', lp.droppedUngrounded], ['dropped_invalid', lp.droppedInvalid], ['breaker', l.breaker ? t(l.breaker.open ? 'open' : 'closed') : null]]);
    invalidReasonsInto(lp.droppedInvalidByReason);
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
      ['matching_run', dv.matching ? t('matching_progress').replace('%1', fmt(dv.matching.checked)).replace('%2', fmt(dv.matching.completed_partitions))
        + ' · ' + t(dv.matching.deferred ? 'matching_deferred_' + dv.matching.deferred : dv.matching.cycle_complete ? 'matching_complete' : 'matching_deferred_work_budget') : t('none')],
      ['upgrade', up ? t('upgrade_value').replace('%1', fmt(up.from)).replace('%2', fmt(up.to)).replace('%3', up.waiting
        ? t('upgrade_waiting').replace('%1', up.hold) : up.backup ? t('upgrade_copy').replace('%1', up.backup) : date(up.at)) : t('none')]]);
    $('upgrade-note').hidden = !up?.waiting;
    collectionsInto(s.collections);
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

  // droppedInvalid by reason (counters since the start): only the reasons that occurred, the most frequent first; the codes as the API names them
  function invalidReasonsInto(by) {
    const box = $('llm-invalid'); box.replaceChildren();
    const rows = Object.entries(by && typeof by === 'object' ? by : {}).filter(([, n]) => typeof n === 'number' && n > 0)
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
    if (!rows.length) { box.append(node('p', t('none'))); return; }
    const dl = node('dl', null, 'sseo-stats');
    for (const [reason, n] of rows) {
      const dt = node('dt'); // a line may break after an underscore; the text stays the code
      reason.split('_').forEach((part, i) => { if (i) dt.append('_', document.createElement('wbr')); dt.append(part); });
      dl.append(dt, node('dd', fmt(n)));
    }
    box.append(dl);
  }

  // The two layers in words: the deterministic graph (rules, structured data) and the LLM enrichment with its model.
  function llmLayers(s) {
    const running = s.state === 'running', l = s.llm || {}, followed = (s.config?.collections || []).length > 0;
    const deterministic = running && followed ? t('kg_on') : t('kg_off') + ' · ' + (running ? t('kg_reason_no_collections') : s.state);
    let llm;
    if (running && l.enabled && l.model && !['off', 'not_configured'].includes(l.state)) llm = t('kg_on') + (l.state && l.state !== 'running' && l.state !== 'idle' ? ' · ' + l.state + (l.reason ? ' (' + l.reason + ')' : '') : '');
    else if (l.reason === 'llm_collections_not_followed') llm = t('kg_off') + ' · ' + t('llm_reason_not_followed');
    else if (!l.enabled || l.reason === 'no_llm_collections') llm = t('kg_off') + ' · ' + t('llm_reason_no_collections');
    else if (!l.model || l.state === 'not_configured') llm = t('kg_off') + ' · ' + t('llm_reason_no_model');
    else llm = t('kg_off') + ' · ' + (l.reason || l.state || '');
    return { deterministic, llm, model: l.model || t('kg_no_model') };
  }

  // Every collection the graph follows, maps or holds: vocabulary (or none, said so), jobs, LLM tier, documents, state.
  function collectionsInto(rows) {
    const body = $('collections').tBodies[0]; body.replaceChildren();
    const heads = [...$('collections').tHead.rows[0].cells].map(c => c.textContent);
    for (const c of rows || []) {
      const vocab = node('span');
      if (c.vocabulary) {
        vocab.append(c.vocabulary);
        vocab.append(' ', node('span', '(' + t(c.vocabularySource === 'setting' ? 'vocab_from_setting' : 'vocab_from_files')
          + (c.vocabularyKnown ? '' : ', ' + t('vocab_unknown')) + ')', 'sseo-note'));
      } else {
        vocab.append(node('span', t('no_vocabulary'), 'skg-novocab'));
        if (c.vocabularySource === 'setting') vocab.append(' ', node('span', '(' + t('vocab_from_setting') + ')', 'sseo-note'));
      }
      const row = rowInto(body, heads, [c.collection, t(c.followed ? 'yes' : 'no'), vocab, t(c.jobs ? 'yes' : 'no'), t(c.llm ? 'yes' : 'no'),
        c.documents == null ? t('missing') : fmt(c.documents), t('vstate_' + c.state)]);
      row.dataset.collection = c.collection; row.dataset.state = c.state;
    }
    if (!rows?.length) { const row = body.insertRow(); const td = row.insertCell(); td.colSpan = heads.length; td.textContent = t('none'); }
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
  // The context of a hit comes with the list (one projection for the page): hosts, collections, places, providers.
  const domain = h => String(h || '').replace(/^www\./i, '');
  function actionLinks(items) {
    const box = node('span', null, 'skg-actions');
    items.filter(Boolean).forEach(([text, query, hash], i) => { if (i) box.append(' · '); box.append(link(text, query, hash)); });
    return box;
  }
  // "CTcon GmbH · ctcon.de" for a service or job; the entity's own domains for the others; a missing provider is said, not hidden
  function providerCell(e, ctx) {
    const box = node('span', null, 'skg-provider');
    if (ctx.providers) {
      const p = ctx.providers[0];
      if (!p) { box.append(node('span', t(e.type === 'job' ? 'no_employer' : 'no_provider'), 'skg-noprov')); return box; }
      box.append(nameLink(p, p.id, p.type));
      if (p.hosts?.length) box.append(' · ', node('span', domain(p.hosts[0]), 'skg-domain'));
      if ((ctx.provider_count || 0) > 1) box.append(' ', node('span', t('more_providers').replace('%1', fmt(ctx.provider_count - 1)), 'sseo-note'));
      return box;
    }
    box.append(node('span', (ctx.hosts || []).map(domain).join(', ') || t('missing'), 'skg-domain'));
    return box;
  }
  function placeOf(ctx) { return (ctx.places || [])[0] || (ctx.providers?.[0]?.places || [])[0] || t('missing'); }
  function hitActions(e, ctx) {
    const p = ctx.providers?.[0];
    return actionLinks([p ? [t(e.type === 'job' ? 'open_employer' : 'open_provider'), { view: 'object', id: p.id }] : null,
      ['service', 'job'].includes(e.type) ? [t(e.type === 'job' ? 'open_object' : 'open_service'), { view: 'object', id: e.id }] : null,
      [t('show_network'), { view: 'network', id: e.id }], [t('show_sources'), { view: 'object', id: e.id }, 'skg-sec-sources'],
      e.type === 'service' && e.name ? [t('all_providers'), { view: 'services', name: e.name }] : null]);
  }
  function rowInto(body, heads, cells) {
    const row = body.insertRow();
    cells.forEach((c, i) => { const td = row.insertCell(); td.dataset.label = heads[i]; if (c instanceof Node) td.append(c); else td.textContent = c; });
    return row;
  }
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
      const ctx = e.context || {};
      const name = node('span', null, 'skg-hit-name'); name.append(nameLink(e, e.id, e.type));
      const note = nameNote(e); if (note) name.append(' ', note);
      rowInto(body, heads, [name, t(e.type) + (e.kind ? ' · ' + e.kind : ''), providerCell(e, ctx), (ctx.collections || []).join(', ') || t('none'),
        placeOf(ctx), qualityBadge(e.quality), fmt(e.counts?.sources), date(e.last_confirmed), hitActions(e, ctx)]).dataset.type = e.type;
    }
    $('range').textContent = data.total ? t('range').replace('%1', fmt(offset + 1)).replace('%2', fmt(offset + data.items.length)).replace('%3', fmt(data.total)) : '';
    $('prev').disabled = offset === 0; $('next').disabled = offset + LIMIT >= data.total;
    message(data.total ? '' : t('empty'));
    await groupsHint(q, run);
  }
  // Searching a service name: the same name across providers, as read-only groups ("SAP · 133 providers")
  async function groupsHint(q, run) {
    const box = $('groups'); box.replaceChildren(); box.hidden = true;
    if (!q.q || (q.type && q.type !== 'service')) return;
    let g = null; try { g = await api('services', { q: q.q, category: q.category, limit: 5 }); } catch (_) { g = null; }
    if (run !== generation || !g) return;
    const items = g.items.filter(x => x.services > 1);
    if (!items.length) return;
    const ul = node('ul', null, 'skg-group-list');
    for (const x of items) {
      const li = node('li');
      li.append(link(t('group_title').replace('%1', x.name).replace('%2', fmt(x.providers)), { view: 'services', name: x.name }));
      li.append(' · ', link(t('group_network'), { view: 'network', group: x.name }));
      li.append(' ', node('span', (x.collections || []).map(c => c.name).join(', '), 'sseo-note'));
      ul.append(li);
    }
    box.append(ul, node('p', t('group_hint_note'), 'sseo-note')); box.hidden = false;
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
    // nothing of the previous object stays visible while this one loads
    $('object-name').textContent = ''; for (const k of ['business', 'out', 'in', 'toc', 'object-facts']) $(k).replaceChildren();
    let e, b;
    try { [e, b] = await Promise.all([api('entities/' + encodeURIComponent(id)), api('entities/' + encodeURIComponent(id) + '/business')]); }
    catch (error) {
      if (error.status === 404) {
        const history = await api('entities/' + encodeURIComponent(id) + '/history', { limit: 1 });
        if (run === generation && history.items?.length) { navigate({ view: 'history', entity: id }); return; }
      }
      throw error;
    }
    if (run !== generation) return;
    if (e.redirect) { navigate({ view: 'object', id: e.redirect }); return; }
    $('object-name').textContent = shown(e);
    const historyLink = $('object-history'); historyLink.replaceChildren(link(t('history_title'), { view: 'history', entity: e.id }));
    const net = $('object-network'); const np = new URLSearchParams({ view: 'network', id: e.id }); if (collection) np.set('collection', collection);
    net.href = 'ScoutroKnowledge_p.html?' + np; net.textContent = t('network_open');
    net.onclick = ev => { if (ev.button !== 0 || ev.ctrlKey || ev.metaKey || ev.shiftKey) return; ev.preventDefault(); navigate({ view: 'network', id: e.id }); };
    const ids = node('span'); (e.identifiers || []).forEach((i, n) => { if (n) ids.append(', '); ids.append(i.scheme + ': ' + i.value + ' '); ids.append(qualityBadge(i.quality)); });
    const hosts = node('span'); (e.hosts || []).forEach((h, n) => { if (n) hosts.append(', '); hosts.append(link(h, { view: 'objects', host: h })); });
    const dups = node('span'); (e.possible_duplicates || []).forEach((d, n) => { if (n) dups.append(', '); dups.append(link(d, { view: 'object', id: d })); });
    { const note = nameNote(e); if (note) $('object-name').append(' ', note); }
    stats('object-facts', [['type', t(e.type) + (e.kind ? ' · ' + e.kind : '')], ['quality', qualityBadge(e.quality)], ['aliases', (e.aliases || []).join(', ') || t('none')],
      ['identifiers', ids.childNodes.length ? ids : t('none')], ['hosts', hosts.childNodes.length ? hosts : t('none')],
      ['duplicates', dups.childNodes.length ? dups : t('none')], ['first_seen', date(e.first_seen)], ['last_confirmed', date(e.last_confirmed)],
      ['statements', fmt(e.counts?.statements)], ['sources', fmt(e.counts?.sources)], ['technical_id', e.id]]);
    businessInto(b);
    await statements(e.id, 'out', $('out'), 0, run);
    await statements(e.id, 'in', $('in'), 0, run);
    message();
    // "Sources" of a hit or a network node jumps to the evidence section
    const anchor = location.hash ? document.getElementById(location.hash.slice(1)) : null;
    if (anchor) { anchor.scrollIntoView({ block: 'start' }); const h = anchor.querySelector('h3'); if (h) { h.tabIndex = -1; h.focus({ preventScroll: true }); } }
    else $('object-name').focus();
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

  // Durable observations have their own links: never rely on a deleted live statement.
  function observationItem(o) {
    const box = node('article', null, 'sseo-panel');
    const value = typeof o.value === 'object' && o.value ? o.value : {};
    const valueLabel = value.product ? t('prod_' + value.product) : value.need ? t('need_' + value.need) : value.role ? t('role_' + value.role) : t('p_' + o.predicate);
    box.append(link(valueLabel + ' · ' + t('ctx_' + (value.context || 'metadata')),
      { view: 'history', observation: o.id, collection: o.collections?.[0] || collection }));
    const identity = o.identity_context?.organization || [];
    const name = identity.find(f => f.predicate === 'name')?.value;
    box.append(node('p', name || t('employer_unresolved'), 'sseo-note'));
    const text = o.observed_at ? t(value.context === 'internal_use' ? 'last_proved' : 'last_observed').replace('%1', date(o.observed_at)) : t('historical_date_unknown');
    box.append(node('p', text), node('blockquote', o.quote || t('quote_unavailable')));
    const dl = node('dl', null, 'sseo-stats');
    for (const [key, val] of [['source_state', t('src_' + o.source?.status)], ['assertion_state', t('assertion_' + o.assertion_status)],
      ['asserted_at', o.asserted_at], ['actor_status', t('actor_' + (o.organization_assignment === 'unresolved' ? 'unresolved' : o.identity_context?.employer_assignment))],
      ['processed_at', date(o.recorded_at)], ['source_revision', o.source?.revision], ['locator', o.locator],
      ['collections', o.collections?.join(', ')], ['extractor', o.extractor], ['vocabulary_version', o.vocabulary_version],
      ['certainty', t(o.certainty === 'qualified' ? 'qualified_signal' : 'stated_signal')]]) {
      dl.append(node('dt', t(key)), node('dd', val == null ? t('missing') : val));
    }
    box.append(dl, external(o.source?.url));
    if (o.job_search) box.append(node('p', t('job_search_state') + ': ' + t('job_' + o.job_search.status)));
    if (o.later_system_change) box.append(node('p', t('later_system_change')), link(t('evidence'), {
      view: 'history', observation: o.later_system_change.observation, collection: o.later_system_change.collection || o.collections?.[0] || collection }));
    if (o.live_statement) box.append(' · ', ...evidenceToggle(o.live_statement, o.collections?.[0] || collection));
    return box;
  }
  async function historyView(p) {
    const run = ++generation; const target = $('history-items'); target.replaceChildren(); message(t('loading'));
    $('history-title').textContent = t('history_title'); $('history-more').hidden = true;
    const download = $('history-download'); const query = new URLSearchParams({ include: 'history', format: 'ndjson' });
    if (collection) query.set('collection', collection); download.href = ROOT + 'export/download?' + query;
    if (p.get('observation')) {
      const id = p.get('observation'); const o = await api('observations/' + encodeURIComponent(id));
      if (run !== generation) return; target.append(observationItem(o));
      const events = node('div'); target.append(events);
      const readEvents = async (after = 0) => {
        const data = await api('observations/' + encodeURIComponent(id) + '/history', { after, limit: LIMIT });
        if (run !== generation) return;
        data.items.forEach(e => events.append(node('p', date(e.at) + ' · ' + (e.kind.startsWith('scope_') ? t('classification_changed') : t('audit_' + e.kind)), 'sseo-note')));
        if (data.has_more) { const more = node('button', t('more'), 'btn btn-default'); events.append(more);
          more.onclick = () => guarded(async () => { more.remove(); await readEvents(data.next_after); }); }
      };
      await readEvents();
    } else {
      const read = async (after = 0) => {
        const data = await api('history', { ...(p.get('entity') ? { entity: p.get('entity') } : {}), after, limit: LIMIT });
        if (run !== generation) return;
        data.items.forEach(o => target.append(observationItem(o)));
        if (!target.childNodes.length) target.append(node('p', t('none')));
        $('history-more').hidden = !data.has_more;
        $('history-more').onclick = () => guarded(() => read(data.next_after));
      };
      await read();
    }
    message();
  }

  function statementItem(s, direction) {
    const li = node('li', null, 'skg-statement');
    const head = node('div', null, 'skg-statement-head');
    if (direction === 'in') { head.append(nameLink(s, s.subject, null, 'subject_'), ' '); }
    head.append(node('span', predicate(s.predicate), 'skg-predicate'), ' ');
    if (s.object.entity) head.append(nameLink(s.object, s.object.entity));
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
  function evidenceToggle(statementId, context = collection) {
    const toggle = node('button', t('evidence'), 'btn btn-default btn-sm'); toggle.type = 'button'; toggle.setAttribute('aria-expanded', 'false');
    const box = node('div', null, 'skg-evidence'); box.hidden = true;
    toggle.addEventListener('click', () => {
      const open = box.hidden; box.hidden = !open; toggle.setAttribute('aria-expanded', String(open)); toggle.textContent = t(open ? 'hide_evidence' : 'evidence');
      if (open && !box.dataset.loaded) guarded(async () => {
        box.replaceChildren(node('p', t('loading')));
        const run = generation;
        const data = await api('statements/' + encodeURIComponent(statementId) + '/evidence', { limit: 50, collection: context });
        if (run !== generation) return;
        box.replaceChildren(evidenceList(data.items, true, context)); box.dataset.loaded = '1';
        let next = data.items.length;
        const more = node('button', t('more'), 'btn btn-default btn-sm'); more.type = 'button'; more.hidden = next >= data.total;
        more.addEventListener('click', () => guarded(async () => {
          more.disabled = true;
          try {
            const page = await api('statements/' + encodeURIComponent(statementId) + '/evidence', { limit: 50, offset: next, collection: context });
            if (run !== generation) return;
            box.insertBefore(evidenceList(page.items, true, context), more); next += page.items.length;
            more.hidden = next >= page.total || !page.items.length;
          } finally { more.disabled = false; }
        }));
        box.append(more);
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
  function entityLink(ref) { return ref ? nameLink(ref, ref.id, ref.type) : node('span', t('missing')); }
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
    if (d.contributions) head.prepend(node('span', t('m_' + (d.kind === 'suggested_customer' && d.direction === 'in' ? 'as_possible_customer' : d.kind)) + ': '));
    li.append(head);
    collectionTags(head, d.other);
    const meta = node('div', null, 'skg-meta');
    meta.append(node('span', [scoreText(d), d.computed_at ? t('computed') + ': ' + date(d.computed_at) : null].filter(Boolean).join(' · '), 'sseo-note'));
    li.append(meta);
    if (d.reason) li.append(node('p', t('reason_label') + ': ' + d.reason, 'skg-reason'));
    if (d.contributions) li.append(contributionList(d));
    li.append(node('p', t('no_fact'), 'sseo-note'));
    return li;
  }

  const scoreText = d => t('sort_score') + ': ' + fmt(d.score ?? d.confidence);
  function collectionTags(box, ref) {
    for (const name of ref?.other_collections || []) box.append(' ', badge(t('other_collection') + ': ' + name, 'collection'));
    if ((ref?.collections || []).length > 1) box.append(node('span', ' · ' + t('collections') + ': ' + ref.collections.join(', '), 'sseo-note'));
  }
  function contributionList(d) {
    const details = node('details'); details.append(node('summary', t('derivation_evidence')));
    function append(c) {
      const part = node('div', null, 'skg-contribution');
      part.append(node('p', [c.collection_a, c.collection_b].filter((x, i, all) => all.indexOf(x) === i).join(' · ') + ' · ' + date(c.computed_at), 'sseo-note'));
      const product = c.product ? t('prod_' + c.product) : c.need ? t('need_' + c.need) : '';
      if (c.rule && !c.rule.startsWith('legacy')) {
        const serviceKey = c.service?.endsWith(' support') ? 'software_support' : ({ 'architecture/planning': 'architecture_planning',
          'construction execution': 'construction_execution', 'energy/facility planning': 'energy_planning',
          'leadership/organization development': 'leadership_development', 'team/organization development': 'team_development',
          outpatient: 'outpatient', short_term: 'short_term' })[c.service];
        part.append(node('p', t('match_rule_' + c.rule) + (product ? ': ' + product : '') + ' · ' + t('matched_service') + ': ' + (c.service_name || (serviceKey ? t('service_' + serviceKey) : c.service)), 'skg-reason'));
        const context = c.context ? t('ctx_' + c.context) : '';
        const when = c.observed_at ? t(c.context === 'internal_use' ? 'last_proved' : 'last_observed').replace('%1', date(c.observed_at)) : t('historical_date_unknown');
        part.append(node('p', [context, when, c.location, c.project, c.phase ? t('project_phase') + ': ' + t('phase_' + c.phase) : null].filter(Boolean).join(' · ')));
        part.append(node('p', [t('evidence_strength') + ': ' + t('strength_' + c.evidence_strength),
          t('matching_fit') + ': ' + t('fit_' + c.fit), t('matching_time') + ': ' + t('time_' + c.temporal_status),
          c.rule + '/' + c.rule_version].join(' · '), 'sseo-note'));
        for (const key of c.uncertainties || []) part.append(node('p', t('uncertainty_' + key), 'sseo-note'));
      } else part.append(node('p', c.evidence_complete ? c.reason : t('basis_unavailable'), 'skg-reason'));
      for (const s of c.evidence || []) {
        if (s.id?.startsWith('kgo_')) { part.append(observationItem(s)); continue; }
        const evidence = node('div'); evidence.append(predicate(s.predicate) + ': ' + (s.object?.value ?? shown(s.object)) + ' · ' + s.collection + ' ');
        evidence.append(...evidenceToggle(s.id, s.collection)); part.append(evidence);
      }
      details.append(part);
    }
    (d.contributions || []).forEach(append);
    if (d.next_contribution_offset != null && d.contributions_path) {
      let offset = d.next_contribution_offset;
      const more = node('button', t('more_reasons'), 'btn btn-default btn-sm'); more.type = 'button';
      more.addEventListener('click', () => guarded(async () => {
        more.disabled = true;
        try { const page = await api(d.contributions_path, { offset, limit: 25 }); page.items.forEach(append);
          offset = page.next_offset; if (offset == null) more.remove();
        } finally { more.disabled = false; }
      })); details.append(more);
    }
    return details;
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
      const service = p.service ? link(p.service_name || t('unnamed_service'), { view: 'object', id: p.service }) : node('span', t('general_price'));
      const cells = [service, money(p.value), conditions(p.value) || t('none'), asOfText(p), status, ev];
      cells.forEach((c, i) => { const td = row.insertCell(); td.dataset.label = t(cols[i]); if (c instanceof Node) td.append(c); else td.textContent = c; });
    }
    return node('div', null, 'sseo-scroll').appendChild(table).parentNode;
  }
  function jobItem(j) {
    const li = node('li', null, 'skg-statement skg-job');
    const head = node('div', null, 'skg-statement-head');
    head.append(link(j.title || t('unnamed_job'), { view: 'object', id: j.id }), ' ', badge(t('job_' + j.status), 'j-' + j.status));
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
    add('source_state', t('src_' + j.source_status));
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
      const head = node('div', null, 'skg-statement-head'); head.append(link(sv.name || t('unnamed_service'), { view: 'object', id: sv.id }));
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
      if (!b.suggestions && a.suggested?.length) parts.push(node('h4', t('suggested_customers')), list(a.suggested, derivedItem), node('p', t('suggestion_note'), 'sseo-note'));
      if (parts.length) box.append(section('audiences', ...parts));
    }
    if (b.suggestions?.total) {
      const on = new URLSearchParams(location.search).get('suggested') !== 'false';
      const toggle = node('input'); toggle.type = 'checkbox'; toggle.checked = on;
      const label = node('label'); label.append(toggle, ' ', t('suggested_toggle'));
      const content = node('div'); content.hidden = !on;
      toggle.addEventListener('change', () => {
        content.hidden = !toggle.checked;
        const p = new URLSearchParams(location.search); if (toggle.checked) p.delete('suggested'); else p.set('suggested', 'false');
        history.replaceState(null, '', 'ScoutroKnowledge_p.html?' + p + location.hash);
      });
      const count = node('p', t('suggestion_count').replace('%1', fmt(b.suggestions.total)), 'sseo-note');
      content.append(count, node('p', t('suggestion_note'), 'sseo-note'), list(b.suggestions.items, derivedItem));
      const more = node('button', t('more'), 'btn btn-default'); more.type = 'button';
      let next = b.suggestions.next_offset;
      const key = d => [d.kind, d.direction, d.other?.id].join('|'), seen = new Set(b.suggestions.items.map(key));
      more.hidden = next == null;
      more.addEventListener('click', () => guarded(async () => {
        more.disabled = true;
        try {
          const run = generation, page = await api('entities/' + encodeURIComponent(b.id) + '/suggestions', { offset: next, limit: 100 });
          if (run !== generation) return;
          content.insertBefore(list(page.items.filter(d => { if (seen.has(key(d))) return false; seen.add(key(d)); return true; }), derivedItem), more); next = page.next_offset;
          count.textContent = t('suggestion_count').replace('%1', fmt(page.total)); more.hidden = next == null;
        } finally { more.disabled = false; }
      }));
      content.append(more); box.append(section('matches', label, content));
    } else if (!b.suggestions && b.suggested_matches && Object.keys(b.suggested_matches).length) {
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

  function evidenceList(items, withPage, context = collection) {
    const ul = node('ul', null, 'skg-evidence-list');
    for (const e of items) {
      const li = node('li');
      if (e.excerpt) li.append(node('blockquote', e.excerpt, 'skg-excerpt'));
      const dl = node('dl', null, 'sseo-stats');
      const add = (k, v) => { dl.append(node('dt', t(k))); const dd = node('dd'); if (v instanceof Node) dd.append(v); else dd.textContent = fmt(v); dl.append(dd); };
      if (withPage) {
        add('page', external(e.url));
        const links = node('span');
        links.append(link(t('source_view'), { view: 'source', doc: e.doc_id, collection: context }), ' · ');
        const ib = node('a', t('index_browser')); const p = new URLSearchParams({ view: 'urls', q: e.url || '' }); if (context) p.set('collection', context);
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
    structure: ['operates', 'part_of', 'located_at'],
    offers: ['offers'],
    places: ['in_place', 'serves_place'],
    industry: ['industry', 'target_industry'],
    audiences: ['audience_segment', 'customer_type'],
    jobs: ['hiring_organization', 'job_location'],
  };
  const FACTS = ['business', 'structure', 'offers', 'places', 'industry', 'audiences', 'jobs', 'prices'];
  const FILTERS = [...FACTS, 'derived', 'weak', 'suggested', 'current', 'stale'];
  // the default view follows the centre: around a service its providers and their structure and relations, around a job its employer
  const DEFAULTS = {
    organization: ['business', 'structure', 'offers', 'places', 'industry', 'audiences', 'jobs', 'derived', 'current'],
    service: ['business', 'structure', 'offers', 'current'],
    job: ['business', 'structure', 'jobs', 'current'],
  };
  const defaultsFor = type => DEFAULTS[type] || DEFAULTS.organization;
  const defaultDepth = type => type === 'service' || type === 'job' ? 2 : 1;
  let graph = null, graphUrls = [], selected = null, drawnWidth = 0;

  // f: the layers (facts and derived rows), s: the status (current, stale); both only in the URL when they differ from the default
  function networkFilters(p, type) {
    const f = p.get('f'), st = p.get('s');
    const on = new Set(f == null ? defaultsFor(type).filter(x => !['current', 'stale'].includes(x)) : []);
    for (const x of (f || '').split(',')) {
      if (x === 'values') { on.add('industry'); on.add('audiences'); } // links of package 6
      else if (FILTERS.includes(x) && x !== 'current') on.add(x); // "stale" in f: a link of package 6 that added outdated facts
    }
    if (st == null) on.add('current');
    else { on.delete('stale'); for (const x of st.split(',')) if (x === 'current' || x === 'stale') on.add(x); }
    return on;
  }
  function networkQuery(on, depth, from) {
    const q = { depth, limit: 50, offset: from, weak: on.has('weak') ? 'true' : null, derived: on.has('derived') ? null : 'false',
      suggested: on.has('suggested') ? 'true' : null, values: on.has('industry') || on.has('audiences') ? null : 'false',
      prices: on.has('prices') ? 'true' : null, include: on.has('stale') ? 'stale' : null };
    const groups = Object.keys(GROUPS);
    if (!groups.every(g => on.has(g))) {
      const types = [];
      for (const g of groups) if (on.has(g)) types.push(...GROUPS[g]);
      if (on.has('derived')) types.push('same_operator');
      if (on.has('weak')) types.push('linked_to');
      if (on.has('suggested')) types.push('suggested_customer', 'suggested_partner');
      // only prices: a type no fact has (weak links are off), so that the prices of the centre stay alone
      q.types = types.join(',') || (on.has('prices') ? 'linked_to' : 'none');
    }
    return q;
  }

  async function network(p) {
    if (p.get('group')) return groupNetwork(p);
    const run = ++generation; message(t('loading'));
    $('net-svg').dataset.state = 'loading';
    singleControls(true);
    const id = p.get('id') || '';
    // the centre's type decides the default view
    const head = await api('entities/' + encodeURIComponent(id)); if (run !== generation) return;
    if (head.redirect) { navigate({ ...Object.fromEntries(p), view: 'network', id: head.redirect }); return; }
    const type = head.type;
    const depth = p.get('depth') === '2' ? 2 : p.get('depth') === '1' ? 1 : defaultDepth(type);
    const on = networkFilters(p, type);
    $('depth').value = String(depth);
    for (const f of FILTERS) $('f-' + f).checked = on.has(f);
    $('f-list').checked = p.get('list') === '1';
    $('net-wrap').hidden = $('f-list').checked;
    $('network-title').textContent = t('network_of').replace('%1', shown(head));
    const q = networkQuery(on, depth, 0);
    if (q.types === 'none') { graph = { center: head.id, centerType: type, nodes: [], edges: [], truncated: false, depth, on }; renderNetwork(); message(t('net_empty')); return; }
    const data = await api('entities/' + encodeURIComponent(id) + '/neighborhood', q); if (run !== generation) return;
    if (data.redirect) { navigate({ ...Object.fromEntries(p), view: 'network', id: data.redirect }); return; }
    graph = { center: data.center, centerType: type, nodes: data.nodes, edges: data.edges, truncated: data.truncated, next: data.next_offset, depth, on };
    selected = null;
    renderNetwork();
    message(visibleEdges(graph).length ? '' : t('net_empty')); $('network-title').focus();
  }

  // The network of a service name (package 6.2): all providers of the name around a centre that is the name only, no object;
  // every line is one provider's own offer of its own service, which the line names. Paged by provider, fewer at a time on a phone.
  const groupLimit = () => (($('net-wrap').clientWidth || $('network').clientWidth || 800) < 640 ? 24 : 50);
  function groupQuery(g, from) {
    return { name: g.name, category: g.category, offset: from || null, limit: g.limit, include: g.on.has('stale') ? 'stale' : null };
  }
  // depth and the layers are those of an object's network; a name's network has its status filter and the list only
  function singleControls(show) {
    for (const el of document.querySelectorAll('[data-skg-single]')) el.hidden = !show;
    for (const el of document.querySelectorAll('[data-skg-group]')) el.hidden = show;
  }
  async function groupNetwork(p) {
    const run = ++generation; message(t('loading'));
    $('net-svg').dataset.state = 'loading';
    singleControls(false);
    const on = networkFilters(p, 'service');
    for (const f of ['current', 'stale']) $('f-' + f).checked = on.has(f);
    $('f-list').checked = p.get('list') === '1';
    $('net-wrap').hidden = $('f-list').checked;
    const group = { name: p.get('group'), category: p.get('category') || '', limit: groupLimit(), on };
    $('network-title').textContent = t('group_network_of').replace('%1', group.name).replace('%2', '…');
    const data = await api('services/network', groupQuery(group, 0)); if (run !== generation) return;
    group.summary = data.group;
    $('network-title').textContent = t('group_network_of').replace('%1', data.group.name).replace('%2', fmt(data.group.providers));
    graph = { center: data.center, centerType: 'service_group', nodes: data.nodes, edges: data.edges, truncated: data.truncated, next: data.next_offset,
      depth: 1, on, group };
    selected = null;
    renderNetwork();
    message(visibleEdges(graph).length ? '' : t('net_empty')); $('network-title').focus();
  }

  async function networkMore() {
    if (!graph?.truncated || graph.next == null) return;
    const run = generation;
    const data = graph.group ? await api('services/network', groupQuery(graph.group, graph.next))
      : await api('entities/' + encodeURIComponent(graph.center) + '/neighborhood', networkQuery(graph.on, graph.depth, graph.next));
    if (run !== generation) return;
    const known = new Set(graph.nodes.map(n => n.id)), edges = new Set(graph.edges.map(e => e.id + '|' + e.from + '|' + e.to));
    for (const n of data.nodes) if (!known.has(n.id)) { graph.nodes.push(n); known.add(n.id); }
    for (const e of data.edges) if (!edges.has(e.id + '|' + e.from + '|' + e.to)) graph.edges.push(e);
    graph.truncated = data.truncated; graph.next = data.next_offset;
    renderNetwork();
  }

  // the status filter keeps current or outdated facts; derived rows, weak signals and suggestions follow their own switches
  function visibleEdges(g) {
    return g.edges.filter(e => !e.fact || (e.status === 'stale' ? g.on.has('stale') : g.on.has('current')));
  }
  const nodeKind = n => n.value ? (n.type === 'price' ? 'price' : 'value') : n.type === 'site' ? 'facility' : n.virtual ? 'group' : n.type;
  const nodeLabel = n => n.type === 'price' ? money(n.price) : n.value ? ((de ? n.label_de : n.label_en) || n.label || n.code) : shown({ ...n, name: n.label }, n.type);
  // the second line of a node: the domain of an organisation, "Service" for a service, the place of a facility
  function subLabel(n) {
    if (n.other_collections?.length) return t('other_collection') + ': ' + n.other_collections.join(', ');
    if (n.virtual) return t('service_group') + ' · ' + t('n_providers').replace('%1', fmt(n.providers));
    if (n.type === 'price') return t('price') + (n.status && n.status !== 'current' ? ' · ' + t('s_' + n.status) : '');
    if (n.type === 'industry') return t('industry') + ' · ' + n.code;
    if (n.type === 'audience') return t('audience');
    if (n.type === 'organization') return n.display_host || (n.hosts?.length ? domain(n.hosts[0]) : t('organization'));
    if (n.type === 'facility' || n.type === 'site') return t(n.type) + (n.places?.length ? ' · ' + n.places[0] : '');
    return t(n.type);
  }
  const clip = (text, max) => text.length > max ? text.slice(0, Math.max(1, max - 1)) + '…' : text;
  function edgeName(e) { return labels['p_' + e.type] || e.type; }
  function edgeShort(e) { return labels['e_' + e.type] || edgeName(e); }
  const groupOrder = n => ({ organization: 0, facility: 1, site: 1, service: 2, job: 3, place: 4 })[n.type] ?? (n.type === 'price' ? 5 : 6);

  // Layered: what points to the centre above it (providers, customers, members), what it points to below; depth 2 beyond its neighbour.
  // Each layer wraps into rows that fit the width, so the drawing is as high as its content and never wider than the page. A layer of
  // several rows sits on an even grid of slots, so that the lines run in the gaps between rows (channels) and columns (gutters).
  function layered(nodes, edges, center, width, cw, ch) {
    const side = new Map([[center, 0]]), anchor = new Map();
    const touching = id => edges.find(x => (x.from === id && x.to === center) || (x.to === id && x.from === center));
    for (const n of nodes) if (n.id !== center && n.depth === 1) { const e = touching(n.id); side.set(n.id, e && e.to === center ? -1 : 1); anchor.set(n.id, center); }
    for (const n of nodes) if (!side.has(n.id)) {
      const e = edges.find(x => (x.to === n.id && side.has(x.from) && side.get(x.from) !== 0) || (x.from === n.id && side.has(x.to) && side.get(x.to) !== 0));
      const parent = e ? (e.to === n.id ? e.from : e.to) : null;
      side.set(n.id, parent ? 2 * Math.sign(side.get(parent)) : 2);
      if (parent) anchor.set(n.id, parent);
    }
    const gap = 14, rowGap = 32, margin = 8;
    let perRow = Math.max(1, Math.floor((width - 2 * margin + gap) / (cw + gap)));
    if (perRow > 1 && perRow % 2) perRow--; // an even grid: the centre line is a gutter
    const gridLeft = (width - (perRow * cw + (perRow - 1) * gap)) / 2;
    const gutters = []; for (let k = 0; k <= perRow; k++) gutters.push(gridLeft + k * (cw + gap) - gap / 2);
    const pos = new Map(), rows = []; let y = margin, centerRow = 0;
    for (const layer of [-2, -1, 0, 1, 2]) {
      const items = nodes.filter(n => side.get(n.id) === layer).sort((a, b) => groupOrder(a) - groupOrder(b) || nodeLabel(a).localeCompare(nodeLabel(b)));
      if (!items.length) continue;
      const chunks = []; for (let r = 0; r * perRow < items.length; r++) chunks.push(items.slice(r * perRow, (r + 1) * perRow));
      if (layer < 0) chunks.reverse(); // the first row is the one next to the centre
      for (const row of chunks) {
        if (rows.length) y += rowGap;
        const h = layer === 0 ? ch + 8 : ch;
        if (layer === 0) centerRow = rows.length;
        row.forEach((n, i) => {
          let x;
          if (layer === 0) x = width / 2;
          else if (chunks.length === 1) { const rw = row.length * cw + (row.length - 1) * gap; x = (width - rw) / 2 + i * (cw + gap) + cw / 2; }
          else x = gridLeft + (i + Math.floor((perRow - row.length) / 2)) * (cw + gap) + cw / 2;
          pos.set(n.id, { x, y: y + h / 2, row: rows.length, layer });
        });
        rows.push({ top: y, bottom: y + h, layer, ids: row.map(n => n.id) });
        y += h;
      }
    }
    return { pos, rows, gutters, anchor, centerRow, margin, box: { x: 0, y: 0, w: width, h: y + margin + 14 } };
  }
  // An orthogonal route from a to b through the channels and gutters of the layered drawing; points from a to b.
  function orthoRoute(L, a, b, half, center) {
    const A = L.pos.get(a), B = L.pos.get(b);
    if (A.row > B.row) return orthoRoute(L, b, a, half, center).reverse();
    const port = (id, p) => id === center ? p.x : p.x - half(id).hw + 18;
    // a channel per gap: near the centre-side end of the gap; captions sit at the other end, next to their cards
    const channel = g => g < L.centerRow ? L.rows[g + 1].top - 9 : L.rows[g].bottom + 9;
    const ra = L.rows[A.row], rb = L.rows[B.row], xa = port(a, A), xb = port(b, B);
    if (A.row === B.row) {
      const below = A.row >= L.centerRow, yA = below ? ra.bottom : ra.top;
      const yc = below ? (A.row + 1 < L.rows.length ? channel(A.row) : ra.bottom + 10) : (A.row > 0 ? channel(A.row - 1) : ra.top - 10);
      return [[xa, yA], [xa, yc], [xb, yc], [xb, yA]];
    }
    const ya = channel(A.row), yb = channel(B.row - 1);
    if (A.row + 1 === B.row) return [[xa, ra.bottom], [xa, ya], [xb, ya], [xb, rb.top]];
    // rows in between: down a gutter that no card of those rows covers, the one nearest to b
    const blocked = x => L.rows.slice(A.row + 1, B.row).some(r => r.ids.some(id => Math.abs(L.pos.get(id).x - x) < half(id).hw + 3));
    const free = L.gutters.filter(x => !blocked(x)).sort((u, v) => Math.abs(u - xb) - Math.abs(v - xb));
    const gx = free.length ? free[0] : L.margin / 2;
    return [[xa, ra.bottom], [xa, ya], [gx, ya], [gx, yb], [xb, yb], [xb, rb.top]];
  }
  // Radial for a few neighbours on a wide screen: an ellipse around the centre, depth 2 outside next to its neighbour.
  function radial(nodes, edges, center, cw, ch, centreWidth) {
    const pos = new Map([[center, { x: 0, y: 0, a: 0 }]]);
    const first = nodes.filter(n => n.id !== center && n.depth === 1).sort((a, b) => groupOrder(a) - groupOrder(b) || nodeLabel(a).localeCompare(nodeLabel(b)));
    const second = nodes.filter(n => n.id !== center && n.depth !== 1);
    const r1 = Math.max(170, (centreWidth + cw) / 2 / 1.35 + 40, first.length * (cw + 24) / (2 * Math.PI) / 1.2), r2 = r1 + ch + 90;
    const at = (r, a) => ({ x: 1.35 * r * Math.cos(a), y: 0.8 * r * Math.sin(a), a });
    first.forEach((n, i) => pos.set(n.id, at(r1, -Math.PI / 2 + 2 * Math.PI * i / Math.max(1, first.length))));
    const byParent = new Map();
    for (const n of second) {
      const e = edges.find(x => (x.to === n.id && pos.has(x.from) && x.from !== center) || (x.from === n.id && pos.has(x.to) && x.to !== center));
      const parent = e ? (e.to === n.id ? e.from : e.to) : center;
      if (!byParent.has(parent)) byParent.set(parent, []); byParent.get(parent).push(n);
    }
    const slot = 2 * Math.PI / Math.max(1, first.length);
    for (const [parent, kids] of byParent) {
      const base = pos.get(parent)?.a ?? 0, spread = Math.min(slot * 0.9, 0.3 * kids.length);
      kids.forEach((n, i) => pos.set(n.id, at(r2, base + (kids.length === 1 ? 0 : -spread / 2 + spread * i / (kids.length - 1)))));
    }
    let minX = 0, maxX = 0, minY = 0, maxY = 0;
    for (const p of pos.values()) { minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x); minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y); }
    const m = 16;
    return { pos, box: { x: minX - cw / 2 - m, y: minY - ch / 2 - m, w: maxX - minX + cw + 2 * m, h: maxY - minY + ch + 2 * m } };
  }
  // where the line from a card's centre towards (tx, ty) leaves the card
  function rim(p, tx, ty, hw, hh) {
    const dx = tx - p.x, dy = ty - p.y;
    if (!dx && !dy) return { x: p.x, y: p.y };
    const k = Math.min(dx ? hw / Math.abs(dx) : Infinity, dy ? hh / Math.abs(dy) : Infinity);
    return { x: p.x + dx * k, y: p.y + dy * k };
  }

  function renderNetwork() {
    const g = graph, box = $('net-svg'), wrap = $('net-wrap'); box.replaceChildren();
    for (const u of graphUrls) URL.revokeObjectURL(u); graphUrls = [];
    const edges = visibleEdges(g);
    const used = new Set([g.center]); for (const e of edges) { used.add(e.from); used.add(e.to); }
    const nodes = g.nodes.filter(n => used.has(n.id));
    const byId = new Map(g.nodes.map(n => [n.id, n]));
    const width = Math.max(280, Math.floor(wrap.clientWidth || $('network').clientWidth || 800));
    drawnWidth = width;
    const narrow = width < 640;
    const ring = nodes.filter(n => n.depth === 1).length, outer = nodes.filter(n => n.depth > 1).length;
    const vertical = narrow || ring > 12 || outer > 0 || nodes.length <= 3;
    // layered: two columns on a phone, as many 190 px cards as fit (an even number) on a wider screen
    const cw = vertical ? Math.min(190, Math.floor((width - 2 * 8 - 14) / 2)) : 150;
    const ch = 44, centreWidth = Math.min(width - 16, Math.round(cw * (vertical ? 1.5 : 1.4)));
    const half = id => id === g.center ? { hw: centreWidth / 2, hh: (ch + 8) / 2 } : { hw: cw / 2, hh: ch / 2 };
    const lay = vertical ? layered(nodes, edges, g.center, width, cw, ch) : radial(nodes, edges, g.center, cw, ch, centreWidth);
    const { pos, box: vb } = lay;
    box.setAttribute('viewBox', `${vb.x.toFixed(1)} ${vb.y.toFixed(1)} ${vb.w.toFixed(1)} ${vb.h.toFixed(1)}`);
    box.style.aspectRatio = vb.w.toFixed(1) + ' / ' + vb.h.toFixed(1);
    box.style.maxWidth = vertical ? '' : Math.ceil(vb.w) + 'px';
    box.dataset.layout = vertical ? 'layered' : 'radial';
    box.setAttribute('aria-label', $('network-title').textContent);
    const defs = svg('defs');
    for (const st of ['confirmed', 'uncertain', 'stale', 'weak', 'derived', 'suggested', 'hl']) {
      const m = svg('marker', { id: 'skg-arrow-' + st, viewBox: '0 0 10 10', refX: '9', refY: '5', markerWidth: '7', markerHeight: '7', orient: 'auto-start-reverse' });
      m.append(svg('path', { d: 'M 0 0 L 10 5 L 0 10 z', class: 'skg-arrow skg-e-' + st })); defs.append(m);
    }
    box.append(defs);
    const edgeLayer = svg('g', { class: 'skg-edges' }), labelLayer = svg('g', { class: 'skg-elabels' }), nodeLayer = svg('g', { class: 'skg-nodes' });
    box.append(edgeLayer, nodeLayer, labelLayer);
    const layout = vertical ? lay : null;
    // layered: the relation to the anchor (the centre, or the neighbour a depth-2 node hangs on) is the card's caption;
    // the other lines get a label on their middle stretch while there are few of them. Radial: labels on the lines.
    const anchorEdge = e => layout && (layout.anchor.get(e.from) === e.to || layout.anchor.get(e.to) === e.from);
    const others = edges.filter(e => !anchorEdge(e));
    const showLabels = layout ? others.length <= 6 : edges.length <= (narrow ? 12 : 24);
    box.classList.toggle('skg-labels-hover', !showLabels);
    const pairs = new Map(), edgeEls = [];
    // labels never cover each other: every caption and label takes a box; a label that finds no free place shows on highlight only
    const textWidth = text => text.length * 6.2 + 6;
    // boxes {x1, y1, x2, y2}: the cards, then every caption and label placed
    const taken = nodes.filter(n => pos.has(n.id)).map(n => { const p = pos.get(n.id), h = half(n.id); return { x1: p.x - h.hw - 2, y1: p.y - h.hh - 2, x2: p.x + h.hw + 2, y2: p.y + h.hh + 2 }; });
    const boxOf = (x, y, w, at) => { const x1 = at === 'start' ? x : at === 'end' ? x - w : x - w / 2; return { x1, y1: y - 10, x2: x1 + w, y2: y + 3 }; };
    const free = b => b.x1 >= vb.x && b.x2 <= vb.x + vb.w && !taken.some(o => b.x1 < o.x2 && b.x2 > o.x1 && b.y1 < o.y2 && b.y2 > o.y1);
    // captions: what the card is to its anchor ("offers", "operates"), beside the line that enters it, on the anchor's side
    if (layout) for (const n of nodes) {
      const anc = layout.anchor.get(n.id), p = pos.get(n.id); if (!anc || !p) continue;
      const names = [...new Set(edges.filter(e => (e.from === n.id && e.to === anc) || (e.to === n.id && e.from === anc)).map(edgeShort))];
      if (!names.length) continue;
      const { hw, hh } = half(n.id), towardsCentre = layout.pos.get(anc).y > p.y;
      const x = p.x - hw + 26, y = towardsCentre ? p.y + hh + 14 : p.y - hh - 6;
      const cap = svg('text', { x: x.toFixed(1), y: y.toFixed(1), class: 'skg-caption', 'data-node': n.id });
      cap.textContent = clip(names.join(' · '), Math.floor((2 * hw - 26) / 6.2));
      labelLayer.append(cap); taken.push(boxOf(x, y, textWidth(cap.textContent), 'start'));
    }
    const labelled = new Set();
    for (const e of edges) {
      const a = pos.get(e.from), b = pos.get(e.to); if (!a || !b) continue;
      const key = [e.from, e.to].sort().join('|'); const k = pairs.get(key) || 0; pairs.set(key, k + 1);
      let d, spots = [];
      if (layout) {
        const pts = orthoRoute(layout, e.from, e.to, half, g.center).map(([x, y]) => [x + (k ? 4 * k : 0), y]);
        d = 'M ' + pts.map(([x, y]) => x.toFixed(1) + ' ' + y.toFixed(1)).join(' L ');
        // places for the label of a non-anchor line: along its horizontal stretches, the longest first, then beside its vertical ones
        const segs = [];
        for (let i = 1; i < pts.length; i++) segs.push([pts[i - 1], pts[i]]);
        segs.sort((u, v) => Math.hypot(v[1][0] - v[0][0], v[1][1] - v[0][1]) - Math.hypot(u[1][0] - u[0][0], u[1][1] - u[0][1]));
        for (const [[x0, y0], [x1, y1]] of segs) for (const f of [0.5, 0.3, 0.7, 0.15, 0.85]) {
          if (y0 === y1) spots.push([x0 + (x1 - x0) * f, y0 - 3], [x0 + (x1 - x0) * f, y0 + 12]);
          else spots.push([x0 + 4, y0 + (y1 - y0) * f + 4, 'start'], [x0 - 4, y0 + (y1 - y0) * f + 4, 'end']);
        }
      } else {
        // parallel lines between the same two cards bend apart; arrows stop at the card's edge
        const bend = k === 0 ? 0 : (k % 2 ? 1 : -1) * Math.ceil(k / 2) * 22;
        const dx = b.x - a.x, dy = b.y - a.y, len = Math.hypot(dx, dy) || 1, nx = -dy / len, ny = dx / len;
        const mx = (a.x + b.x) / 2 + nx * bend, my = (a.y + b.y) / 2 + ny * bend;
        const ha = half(e.from), hb = half(e.to);
        const s0 = rim(a, mx, my, ha.hw, ha.hh), t0 = rim(b, mx, my, hb.hw + 2, hb.hh + 2);
        d = `M ${s0.x.toFixed(1)} ${s0.y.toFixed(1)} Q ${mx.toFixed(1)} ${my.toFixed(1)} ${t0.x.toFixed(1)} ${t0.y.toFixed(1)}`;
        // points on the curve (t = 0.5, then nearer the ends)
        for (const tt of [0.5, 0.35, 0.65]) spots.push([(1 - tt) * (1 - tt) * s0.x + 2 * tt * (1 - tt) * mx + tt * tt * t0.x,
          (1 - tt) * (1 - tt) * s0.y + 2 * tt * (1 - tt) * my + tt * tt * t0.y + 4]);
      }
      const wrapEl = svg('g', { class: 'skg-edge skg-e-' + e.status, tabindex: '0', role: 'button', 'data-edge': e.id, 'data-from': e.from, 'data-to': e.to, 'data-type': e.type });
      const text = nodeLabel(byId.get(e.from) || { id: e.from }) + ' → ' + edgeShort(e) + ' → ' + nodeLabel(byId.get(e.to) || { id: e.to }) + ', ' + t('s_' + e.status);
      wrapEl.setAttribute('aria-label', text);
      wrapEl.append(svg('path', { d, class: 'skg-hit' }), svg('path', { d, class: 'skg-line', 'marker-end': 'url(#skg-arrow-' + e.status + ')',
        'stroke-width': (1 + 1.5 * Math.min(1, e.confidence || 0)).toFixed(1) }));
      const title = svg('title'); title.textContent = text; wrapEl.append(title);
      if (!anchorEdge(e)) {
        const textLabel = clip(edgeShort(e), 22), w = textWidth(textLabel), once = key + '|' + textLabel;
        const spot = labelled.has(once) ? null : spots.find(([x, y, at]) => free(boxOf(x, y, w, at)));
        const [lx, ly, anchorAt] = spot || spots[0] || [0, 0];
        const label = svg('text', { x: lx.toFixed(1), y: ly.toFixed(1), 'text-anchor': anchorAt || 'middle',
          class: 'skg-elabel skg-e-' + e.status + (spot ? '' : ' skg-crowded'), 'data-edge': e.id });
        label.textContent = textLabel; labelLayer.append(label);
        if (spot) { taken.push(boxOf(lx, ly, w, anchorAt)); labelled.add(once); }
      }
      const show = () => { select(null); edgeDetail(e, byId); highlight(e.from, e.to, e.id); };
      wrapEl.addEventListener('click', show); wrapEl.addEventListener('focus', show);
      wrapEl.addEventListener('keydown', ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); show(); } else if (ev.key === 'Escape') hideDetail(); });
      wrapEl.addEventListener('mouseenter', () => highlight(e.from, e.to, e.id)); wrapEl.addEventListener('mouseleave', () => highlight(selected));
      edgeLayer.append(wrapEl); edgeEls.push(wrapEl);
    }
    const ordered = [...nodes].sort((a, b) => (a.id === g.center) - (b.id === g.center) || a.depth - b.depth);
    for (const n of ordered) {
      const p = pos.get(n.id); if (!p) continue;
      const center = n.id === g.center, { hw, hh } = half(n.id);
      const cls = 'skg-node skg-t-' + nodeKind(n) + (center ? ' skg-center' : '') + (n.status ? ' skg-ns-' + n.status : '');
      const el = svg('g', { class: cls, tabindex: '0', role: 'button', transform: `translate(${p.x.toFixed(1)} ${p.y.toFixed(1)})`, 'data-node': n.id, 'data-type': n.type });
      el.setAttribute('aria-label', nodeLabel(n) + ', ' + subLabel(n) + (center ? ', ' + t('net_center') : ''));
      el.append(svg('rect', { class: 'skg-card', x: -hw, y: -hh, width: 2 * hw, height: 2 * hh, rx: n.value ? 14 : 7 }));
      el.append(svg('rect', { class: 'skg-stripe', x: -hw, y: -hh, width: 6, height: 2 * hh, rx: 3 }));
      const chars = Math.max(6, Math.floor((2 * hw - 22) / 7.2));
      const t1 = svg('text', { x: -hw + 13, y: -2, class: 'skg-n1' }); t1.textContent = clip(nodeLabel(n), chars);
      const t2 = svg('text', { x: -hw + 13, y: 14, class: 'skg-n2' }); t2.textContent = clip(subLabel(n), Math.floor(chars * 1.15));
      el.append(t1, t2);
      const title = svg('title'); title.textContent = nodeLabel(n) + ' · ' + subLabel(n); el.append(title);
      const open = () => { select(n.id); nodeDetail(n, byId); };
      el.addEventListener('click', open); el.addEventListener('focus', open);
      el.addEventListener('mouseenter', () => highlight(n.id)); el.addEventListener('mouseleave', () => highlight(selected));
      el.addEventListener('keydown', ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); open(); } else if (ev.key === 'Escape') hideDetail(); });
      nodeLayer.append(el);
    }
    function highlight(id, other, edgeId) {
      for (const w of edgeEls) w.classList.toggle('skg-hl', edgeId ? w.dataset.edge === edgeId && w.dataset.from === id : !!id && (w.dataset.from === id || w.dataset.to === id));
      for (const l of labelLayer.children) l.classList.toggle('skg-hl', l.dataset.edge ? edgeEls.some(w => w.classList.contains('skg-hl') && w.dataset.edge === l.dataset.edge)
        : !!id && (l.dataset.node === id || l.dataset.node === other));
      for (const c of nodeLayer.children) c.classList.toggle('skg-hl', !!id && (c.dataset.node === id || c.dataset.node === other));
    }
    function select(id) { selected = id; highlight(id); }
    // legend, counters, paging, exports and the list
    const legend = $('net-legend'); legend.replaceChildren();
    for (const [kind, key] of [...(g.group ? [['group', 'legend_node_group']] : []), ['organization', 'legend_node_organization'], ['facility', 'legend_node_facility'],
      ['service', 'legend_node_service'], ['job', 'legend_node_job'], ['place', 'legend_node_place'], ['value', 'legend_node_value'], ['price', 'legend_node_price']]) {
      const li = node('li'); const s = svg('svg', { width: '22', height: '14', 'aria-hidden': 'true', class: 'skg-legend-node skg-t-' + kind });
      s.append(svg('rect', { x: '1', y: '1', width: '20', height: '12', rx: kind === 'value' || kind === 'price' ? '6' : '3', class: 'skg-card' }),
        svg('rect', { x: '1', y: '1', width: '4', height: '12', rx: '2', class: 'skg-stripe' }));
      li.append(s, ' ', t(key)); legend.append(li);
    }
    for (const [st, key] of [['confirmed', 'legend_fact'], ['uncertain', 'legend_uncertain'], ['derived', 'legend_derived'], ['weak', 'legend_weak'], ['suggested', 'legend_suggested']]) {
      const li = node('li'); const s = svg('svg', { width: '36', height: '10', 'aria-hidden': 'true' });
      s.append(svg('line', { x1: '0', y1: '5', x2: '36', y2: '5', class: 'skg-line skg-e-' + st })); li.append(s, ' ', t(key)); legend.append(li);
    }
    $('net-count').textContent = t('net_count').replace('%1', fmt(nodes.length)).replace('%2', fmt(edges.length)) + (g.truncated ? ' · ' + t('net_more_hint') : '');
    $('net-more').hidden = !g.truncated || g.next == null;
    const json = new Blob([JSON.stringify({ schema: 'scoutro.kg.business.v1', center: g.center, collection: collection || null,
      ...(g.group ? { aggregated: true, group: g.group.summary || null } : {}), nodes, edges }, null, 2)], { type: 'application/json' });
    const graphml = new Blob([toGraphml({ center: g.center, nodes, edges })], { type: 'application/graphml+xml' });
    for (const [id, blob] of [['net-json', json], ['net-graphml', graphml]]) { const u = URL.createObjectURL(blob); graphUrls.push(u); $(id).href = u; }
    const body = $('net-table').tBodies[0]; body.replaceChildren();
    const heads = [...$('net-table').tHead.rows[0].cells].map(c => c.textContent);
    for (const e of edges) {
      const end = id => {
        const n = byId.get(id) || { id }, span = node('span');
        span.append(n.value || id === g.center ? node('span', nodeLabel(n)) : link(nodeLabel(n), { view: 'object', id, ...(n.target_collection ? { collection: n.target_collection } : {}) }));
        span.append(' ', node('span', '(' + subLabel(n) + ')', 'sseo-note'));
        return span;
      };
      const ev = node('span'); ev.append(fmt(e.evidence) + ' ');
      if (e.fact && e.id.startsWith('kgs_')) ev.append(...evidenceToggle(e.id)); else { ev.append(node('span', t('no_fact'), 'sseo-note')); if (e.contributions) ev.append(contributionList(e)); }
      rowInto(body, heads, [end(e.from), edgeName(e), e.service ? ownService(e.service) : end(e.to), statusBadge(e.status), e.fact ? pct(e.confidence) : scoreText(e), ev]);
    }
    hideDetail();
    box.dataset.state = 'ready';
  }

  function hideDetail() { $('net-detail').hidden = true; $('net-detail').replaceChildren(); }
  function detailStats(rows) {
    const dl = node('dl', null, 'sseo-stats');
    for (const [k, v] of rows) { if (v == null || v === '') continue; dl.append(node('dt', t(k))); const dd = node('dd'); if (v instanceof Node) dd.append(v); else dd.textContent = v; dl.append(dd); }
    return dl;
  }
  function relationLine(e, byId) {
    const from = byId.get(e.from) || { id: e.from }, to = byId.get(e.to) || { id: e.to };
    const li = node('li', null, 'skg-statement');
    li.append(node('div', nodeLabel(from) + ' → ' + edgeShort(e) + ' → ' + nodeLabel(to), 'skg-statement-head'));
    li.append(node('div', t('s_' + e.status) + ' · ' + (e.fact ? t('confidence') + ': ' + pct(e.confidence) : scoreText(e)) + ' · ' + t('sources') + ': ' + fmt(e.evidence)
      + (e.fact ? '' : ' · ' + t('no_fact')), 'sseo-note'));
    if (e.service) li.append(serviceLine(e.service));
    if (e.contributions) li.append(contributionList(e));
    return li;
  }
  // a line of a name's network: the provider's own service, with its own prices and collections (never another provider's)
  function ownService(sv) {
    const span = node('span'); span.append(nameLink(sv, sv.id, 'service'), ' ', node('span', '(' + t('own_service') + ')', 'sseo-note'));
    return span;
  }
  function serviceLine(sv) {
    const d = node('div', null, 'sseo-note');
    d.append(t('own_service') + ': ', nameLink(sv, sv.id, 'service'), ' · ' + t('prices_value').replace('%1', fmt(sv.prices?.current))
      .replace('%2', fmt(sv.prices?.all)) + ' · ' + t('collections') + ': ' + ((sv.collections || []).join(', ') || t('none')));
    return d;
  }
  function nodeDetail(n, byId) {
    const box = $('net-detail'); box.replaceChildren(); box.hidden = false;
    box.append(node('h3', nodeLabel(n)));
    box.append(node('p', subLabel(n) + (n.kind ? ' · ' + n.kind : '') + (n.id === graph.center ? ' · ' + t('net_center') : ''), 'sseo-note'));
    collectionTags(box, n);
    { const note = nameNote(n); if (note) box.append(note); }
    if (n.virtual) {
      // the centre of a name's network: the counts of the group, and the way to its list; it is no object to open
      const g = graph.group?.summary || {};
      box.append(detailStats([['g_services', fmt(g.services)], ['g_providers', fmt(g.providers)], ['g_without_provider', fmt(g.without_provider)],
        ['collections', (g.collections || []).map(c => c.name + ' (' + fmt(c.services) + ')').join(', ') || t('none')],
        ['g_places', (g.places || []).map(x => x.name + ' (' + fmt(x.providers) + ')').join(', ') || t('none')],
        ['g_with_price', fmt(g.with_price)], ['g_with_current', fmt(g.with_current_source)]]));
      box.append(node('p', t('group_centre_note'), 'sseo-note'));
      const actions = node('div', null, 'sseo-controls skg-detail-actions');
      const a = link(t('group_list'), { view: 'services', name: g.name || n.label, ...(graph.group.category ? { category: graph.group.category } : {}) });
      a.className = 'btn btn-default btn-sm'; actions.append(a); box.append(actions);
      if (box.getBoundingClientRect().top > window.innerHeight - 80) box.scrollIntoView({ block: 'nearest' });
      return;
    }
    if (!n.value) box.append(detailStats([['type', t(n.type)], ['domain', (n.hosts || []).map(domain).join(', ') || t('missing')],
      ['collections', (n.collections || []).join(', ') || t('none')], ['places', (n.places || []).join(', ')],
      ['quality', n.quality ? qualityBadge(n.quality) : null], ['sources', fmt(n.sources)], ['last_confirmed', n.last_confirmed ? date(n.last_confirmed) : null]]));
    // the relation to the centre first; a node at depth 2 is reached through its neighbour
    const mine = graph.edges.filter(e => e.from === n.id || e.to === n.id);
    const direct = mine.filter(e => e.from === graph.center || e.to === graph.center);
    if (n.id !== graph.center) {
      box.append(node('h4', t('relation_to_centre')));
      if (direct.length) box.append(list(direct, e => relationLine(e, byId)));
      else {
        const via = mine.map(e => byId.get(e.from === n.id ? e.to : e.from)).find(o => o && graph.edges.some(x => (x.from === o.id && x.to === graph.center) || (x.to === o.id && x.from === graph.center)));
        box.append(node('p', via ? t('via').replace('%1', nodeLabel(via)) : t('none'), 'sseo-note'));
      }
    }
    const rest = mine.filter(e => !direct.includes(e));
    if (rest.length) { box.append(node('h4', t(n.id === graph.center ? 'sec_relations' : 'other_relations'))); box.append(list(rest.slice(0, 12), e => relationLine(e, byId))); }
    const actions = node('div', null, 'sseo-controls skg-detail-actions');
    const button = (text, query, hash) => { const a = link(text, query, hash); a.className = 'btn btn-default btn-sm'; return a; };
    if (!n.value) {
      const context = n.target_collection ? { collection: n.target_collection } : {};
      actions.append(button(t('open_object'), { view: 'object', id: n.id, ...context }));
      if (n.id !== graph.center) actions.append(button(t('open_network'), { view: 'network', id: n.id, ...context }));
      actions.append(button(t('open_sources'), { view: 'object', id: n.id, ...context }, 'skg-sec-sources'));
    } else if (n.type === 'industry') actions.append(button(t('objects_industry'), { view: 'objects', industry: n.code }));
    else if (n.type === 'audience') actions.append(button(t('objects_audience'), { view: 'objects', audience: n.code }));
    else if (n.type === 'price') {
      const svc = byId.get(n.service);
      if (svc) actions.append(button(t('open_service'), { view: 'object', id: svc.id }));
      const e = graph.edges.find(x => x.to === n.id);
      if (e) actions.append(...evidenceToggle(e.id));
    }
    box.append(actions);
    if (box.getBoundingClientRect().top > window.innerHeight - 80) box.scrollIntoView({ block: 'nearest' });
  }
  function edgeDetail(e, byId) {
    const box = $('net-detail'); box.replaceChildren(); box.hidden = false;
    const from = byId.get(e.from) || { id: e.from }, to = byId.get(e.to) || { id: e.to };
    box.append(node('h3', nodeLabel(from) + ' → ' + edgeShort(e) + ' → ' + nodeLabel(to)));
    box.append(node('p', edgeName(e), 'sseo-note'));
    const meta = node('div', null, 'skg-meta'); meta.append(statusBadge(e.status), node('span', (e.fact ? t('confidence') + ': ' + pct(e.confidence) : scoreText(e)) + ' · ' + t('sources') + ': ' + fmt(e.evidence), 'sseo-note'));
    box.append(meta);
    if (e.service) {
      box.append(serviceLine(e.service));
      const actions = node('div', null, 'sseo-controls skg-detail-actions');
      for (const [key, q] of [['open_service', { view: 'object', id: e.service.id }], ['open_service_network', { view: 'network', id: e.service.id }]]) {
        const a = link(t(key), q); a.className = 'btn btn-default btn-sm'; actions.append(a);
      }
      box.append(actions);
    }
    if (e.fact && e.id.startsWith('kgs_')) box.append(...evidenceToggle(e.id));
    else { box.append(node('p', t('no_fact'), 'sseo-note')); if (e.contributions) box.append(contributionList(e)); }
    if (box.getBoundingClientRect().top > window.innerHeight - 80) box.scrollIntoView({ block: 'nearest' });
  }
  function toGraphml(g) {
    const x = v => String(v ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    const out = ['<?xml version="1.0" encoding="UTF-8"?>', '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
      '<key id="label" for="node" attr.name="label" attr.type="string"/>', '<key id="type" for="node" attr.name="type" attr.type="string"/>',
      '<key id="kind" for="node" attr.name="kind" attr.type="string"/>', '<key id="depth" for="node" attr.name="depth" attr.type="int"/>',
      '<key id="hosts" for="node" attr.name="hosts" attr.type="string"/>', '<key id="collections" for="node" attr.name="collections" attr.type="string"/>',
      '<key id="target_collection" for="node" attr.name="target_collection" attr.type="string"/>',
      '<key id="other_collections" for="node" attr.name="other_collections" attr.type="string"/>',
      '<key id="contributions" for="edge" attr.name="contributions" attr.type="string"/>',
      '<key id="score" for="edge" attr.name="sorting_score" attr.type="double"/>',
      '<key id="relation" for="edge" attr.name="relation" attr.type="string"/>', '<key id="status" for="edge" attr.name="status" attr.type="string"/>',
      '<key id="confidence" for="edge" attr.name="confidence" attr.type="double"/>', '<key id="evidence" for="edge" attr.name="evidence" attr.type="int"/>',
      '<key id="fact" for="edge" attr.name="fact" attr.type="boolean"/>', '<key id="virtual" for="node" attr.name="virtual" attr.type="boolean"/>',
      '<key id="service" for="edge" attr.name="service" attr.type="string"/>', '<graph id="' + x(g.center) + '" edgedefault="directed">'];
    for (const n of g.nodes) out.push(`<node id="${x(n.id)}"><data key="label">${x(nodeLabel(n))}</data><data key="type">${x(n.type)}</data>`
      + (n.kind ? `<data key="kind">${x(n.kind)}</data>` : '') + `<data key="depth">${n.depth}</data>`
      + (n.hosts?.length ? `<data key="hosts">${x(n.hosts.join(' '))}</data>` : '') + (n.collections?.length ? `<data key="collections">${x(n.collections.join(' '))}</data>` : '')
      + (n.target_collection ? `<data key="target_collection">${x(n.target_collection)}</data>` : '')
      + (n.other_collections?.length ? `<data key="other_collections">${x(n.other_collections.join(' '))}</data>` : '')
      + (n.virtual ? '<data key="virtual">true</data>' : '') + '</node>');
    g.edges.forEach((e, i) => out.push(`<edge id="e${i}" source="${x(e.from)}" target="${x(e.to)}"><data key="relation">${x(e.type)}</data><data key="status">${x(e.status)}</data>`
      + `<data key="confidence">${e.confidence ?? 0}</data><data key="evidence">${e.evidence ?? 0}</data><data key="fact">${e.fact ? 'true' : 'false'}</data>`
      + (e.contributions ? `<data key="score">${e.score}</data><data key="contributions">${x(JSON.stringify(e.contributions))}</data>` : '')
      + (e.service ? `<data key="service">${x(e.service.id)}</data>` : '') + '</edge>'));
    out.push('</graph>', '</graphml>');
    return out.join('\n') + '\n';
  }
  function networkParams() {
    const p = new URLSearchParams(location.search);
    if (graph?.group) {
      const q = { view: 'network', group: graph.group.name, ...(graph.group.category ? { category: graph.group.category } : {}) };
      const status = ['current', 'stale'].filter(f => $('f-' + f).checked);
      if (status.join(',') !== 'current') q.s = status.join(',') || 'none';
      if ($('f-list').checked) q.list = '1';
      return q;
    }
    const q = { view: 'network', id: p.get('id') || graph?.center || '' };
    const type = graph?.centerType;
    if ($('depth').value !== String(defaultDepth(type))) q.depth = $('depth').value;
    const layers = FILTERS.filter(f => f !== 'current' && f !== 'stale');
    const on = layers.filter(f => $('f-' + f).checked);
    if (on.join(',') !== layers.filter(f => defaultsFor(type).includes(f)).join(',')) q.f = on.join(',');
    const status = ['current', 'stale'].filter(f => $('f-' + f).checked);
    if (status.join(',') !== 'current') q.s = status.join(',') || 'none';
    if ($('f-list').checked) q.list = '1';
    return q;
  }

  // ------------------------------------------------------------------ services
  // Services of the same name across providers: read-only groups, then the services of one name, each with its own provider.
  async function servicesView(p) {
    const run = ++generation; message(t('loading'));
    let f = null; try { f = await loadFacets(); } catch (_) { f = null; }
    if (run !== generation) return;
    const name = p.get('name') || '', category = p.get('category') || '';
    fillSelect($('svc-category'), [[t('categories'), f?.categories || [], false]], category);
    $('svc-q').value = p.get('q') || '';
    $('svc-groups-box').hidden = !!name; $('svc-rows-box').hidden = !name;
    let data;
    if (name) {
      data = await api('services/providers', { name, category, offset, limit: LIMIT }); if (run !== generation) return;
      const g = data.group;
      $('svc-group-title').textContent = t('group_title').replace('%1', g.name).replace('%2', fmt(g.providers));
      stats('svc-group', [['g_services', fmt(g.services)], ['g_providers', fmt(g.providers)], ['g_without_provider', fmt(g.without_provider)],
        ['collections', g.collections.map(c => c.name + ' (' + fmt(c.services) + ')').join(', ') || t('none')],
        ['g_places', g.places.map(x => x.name + ' (' + fmt(x.providers) + ')').join(', ') || t('none')],
        ['g_with_price', fmt(g.with_price)], ['g_with_current', fmt(g.with_current_source)]]);
      const body = $('svc-rows').tBodies[0]; body.replaceChildren();
      const heads = [...$('svc-rows').tHead.rows[0].cells].map(c => c.textContent);
      for (const r of data.items) {
        const sv = r.service, ctx = { providers: r.providers || [], provider_count: r.provider_count, places: [] };
        const e = { id: sv.id, name: sv.name, type: 'service' };
        rowInto(body, heads, [nameLink(sv, sv.id, 'service'), providerCell(e, ctx), (sv.collections || []).join(', ') || t('none'),
          placeOf(ctx), t('prices_value').replace('%1', fmt(r.prices?.current)).replace('%2', fmt(r.prices?.all)), qualityBadge(sv.quality),
          fmt(sv.sources), date(sv.last_confirmed), hitActions(e, ctx)]);
      }
      const back = $('svc-back'); const bp = new URLSearchParams({ view: 'services' }); if (collection) bp.set('collection', collection);
      back.href = 'ScoutroKnowledge_p.html?' + bp;
      const np = { view: 'network', group: g.name, ...(category ? { category } : {}) };
      const net = $('svc-network'); const nq = new URLSearchParams(np); if (collection) nq.set('collection', collection);
      net.href = 'ScoutroKnowledge_p.html?' + nq; net.dataset.query = JSON.stringify(np);
      $('svc-group-title').tabIndex = -1;
    } else {
      data = await api('services', { q: $('svc-q').value.trim(), category, offset, limit: LIMIT }); if (run !== generation) return;
      const body = $('svc-groups').tBodies[0]; body.replaceChildren();
      const heads = [...$('svc-groups').tHead.rows[0].cells].map(c => c.textContent);
      for (const g of data.items) {
        const title = node('span'); title.append(link(g.name, { view: 'services', name: g.name, ...(category ? { category } : {}) }));
        title.append(' · ', link(t('group_network'), { view: 'network', group: g.name, ...(category ? { category } : {}) }));
        if (g.without_provider) title.append(' ', node('span', t('g_without_provider') + ': ' + fmt(g.without_provider), 'sseo-note'));
        rowInto(body, heads, [title, fmt(g.providers), g.collections.map(c => c.name + ' (' + fmt(c.services) + ')').join(', ') || t('none'),
          g.places.slice(0, 5).map(x => x.name).join(', ') || t('missing'), fmt(g.with_price), fmt(g.with_current_source)]);
      }
    }
    const total = data.total;
    $('svc-range').textContent = total ? t('range').replace('%1', fmt(offset + 1)).replace('%2', fmt(offset + data.items.length)).replace('%3', fmt(total)) : '';
    $('svc-prev').disabled = offset === 0; $('svc-next').disabled = offset + LIMIT >= total;
    message(total ? '' : t('services_empty'));
    if (name) $('svc-group-title').focus(); else $('services-title').focus();
  }
  function servicesQuery(from) {
    const p = new URLSearchParams(location.search), q = { view: 'services' };
    for (const k of ['name', 'q', 'category']) if (p.get(k)) q[k] = p.get(k);
    if (from) q.offset = from;
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
      const providers = () => { const box = node('span'); r.providers.forEach((pv, i) => { if (i) box.append(', '); box.append(nameLink(pv, pv.id, 'organization')); if (pv.locality) box.append(' · ' + pv.locality); }); return box; };
      const service = () => nameLink(r.service, r.service.id, 'service');
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
      li.querySelector('.skg-statement-head').prepend(nameLink(item, item.subject, null, 'subject_'), ' ');
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
    const sr = await fetch(ROOT + 'llm-schedule', { credentials: 'same-origin', cache: 'no-store' });
    const saved = await sr.json(); if (run !== generation) return;
    if (!sr.ok) throw new Error(t('error') + ' (HTTP ' + sr.status + ')');
    s.llm ||= {}; s.llm.timing ||= {};
    Object.assign(s.llm.timing, { plan: saved.plan, valid: saved.valid, validationError: saved.validationError });
    renderTiming(s);
    const c = s.config || {};
    const kinds = Object.entries(c.llmKinds || {}).map(([k, v]) => k + ': ' + (v.length ? v.join(', ') : t('none'))).join('\n');
    stats('config', [['enabled', t(s.enabled ? 'yes' : 'no')], ['valid', c.valid == null ? null : t(c.valid ? 'yes' : 'no')],
      ['followed', (c.collections || []).join(', ') || t('none')], ['inactive_collections', (c.inactiveCollections || []).join(', ') || t('none')],
      ['llm_collections', (c.llmCollections || []).join(', ') || t('none')],
      ['jobs_collections', (c.jobsCollections || []).join(', ') || t('none')],
      ['llm_kinds', kinds || t('none')], ['budget', bytes(s.storage?.budgetBytes)], ['model', s.llm?.model],
      ['chat', c.chat == null ? null : !c.chat.enabled ? t('no') : t('chat_detail').replace('%1', fmt(c.chat.maxFacts))
        .replace('%2', fmt(c.chat.maxChars)).replace('%3', fmt(c.chat.timeoutMs)).replace('%4', t(c.chat.allowGuests ? 'yes' : 'no'))]]);
    await kgCollections(run);
    if (run !== generation) return;
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

  function timingRows(time) {
    if (!time) return [];
    const p = time.plan || {}, m = time.manual || {};
    const reason = time.waitReason;
    return [['valid', time.valid == null ? null : t(time.valid ? 'yes' : 'no')], ['problem', time.validationError ? t('schedule_reason_' + time.validationError) : null], ['schedule_mode', t('schedule_' + p.mode)], ['schedule_days', (p.days || []).map(d => t('schedule_' + ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'][d - 1])).join(', ')],
      ['schedule_from', p.from], ['schedule_until', p.until], ['schedule_zone', p.zone], ['schedule_gap', p.minStartSeconds],
      ['schedule_open', t(time.windowOpen ? 'yes' : 'no')], ['schedule_next', time.nextAllowedStart == null ? t('none') : date(time.nextAllowedStart)],
      ['schedule_wait', reason ? t('schedule_reason_' + reason) : t('none')], ['schedule_running', time.runningRequests],
      ['schedule_run', t('schedule_run_' + (m.state || 'not_running')) + ' · ' + fmt(m.documents || 0) + '/' + fmt(m.maxDocuments || 0) + ' · ' + fmt(m.requestStarts || 0) + '/' + fmt(m.maxRequests || 0)]];
  }
  function renderTiming(s) {
    if (!$('schedule-form')) return;
    const time = s.llm?.timing || { plan: s.config?.llmSchedule || { mode: 'automatic', days: [1, 2, 3, 4, 5, 6, 7], from: '00:00', until: '00:00', zone: 'UTC', minStartSeconds: 0 } };
    const p = time.plan;
    for (const [key, value] of [['mode', p.mode], ['from', p.from], ['until', p.until], ['zone', p.zone], ['gap', p.minStartSeconds]]) $('schedule-' + key).value = value;
    const days = $('schedule-days'); while (days.lastChild && days.lastChild.tagName !== 'LEGEND') days.lastChild.remove();
    ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'].forEach((key, i) => {
      const label = node('label'), input = node('input'); input.type = 'checkbox'; input.value = i + 1; input.checked = p.days.includes(i + 1);
      label.append(input, ' ', t('schedule_' + key), ' '); days.append(label);
    });
    stats('schedule-status', timingRows(time));
    const active = ['running', 'stopping'].includes(time.manual?.state) || (time.manual?.runningRequests || 0) > 0;
    $('schedule-now').disabled = active || s.state !== 'running' || !s.llm?.model || !s.llm?.enabled;
    $('schedule-stop').disabled = !active;
  }
  async function timingChange(path, body, method) {
    const r = await fetch(ROOT + path, { method, credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const result = await r.json();
    if (!r.ok) throw new Error(t('error') + ' (HTTP ' + r.status + ', ' + (result.error?.code || 'error') + ', ' + (result.error?.message || '') + ')');
    await settings(); $('schedule-message').textContent = path === 'llm-schedule' ? t('schedule_saved') : '';
  }
  // Poll runtime status without overwriting unsaved form edits.
  setInterval(async () => {
    if (view !== 'settings' || document.hidden || !$('schedule-status')) return;
    const run = generation;
    try {
      const r = await fetch(ROOT + 'status', { credentials: 'same-origin', cache: 'no-store' });
      if (!r.ok) return;
      const s = await r.json(); if (run !== generation || view !== 'settings') return;
      stats('schedule-status', timingRows(s.llm?.timing));
      const m = s.llm?.timing?.manual, active = ['running', 'stopping'].includes(m?.state) || (m?.runningRequests || 0) > 0;
      $('schedule-now').disabled = active || s.state !== 'running' || !s.llm?.model || !s.llm?.enabled;
      $('schedule-stop').disabled = !active;
    } catch (_) { /* next refresh retries; no automatic mutating request */ }
  }, 3000);
  $('schedule-form')?.addEventListener('submit', event => {
    event.preventDefault();
    guarded(() => timingChange('llm-schedule', { mode: $('schedule-mode').value, days: [...$('schedule-days').querySelectorAll('input:checked')].map(i => Number(i.value)),
      from: $('schedule-from').value, until: $('schedule-until').value, zone: $('schedule-zone').value, minStartSeconds: Number($('schedule-gap').value) }, 'PUT'));
  });
  $('schedule-now')?.addEventListener('click', () => {
    if (!$('schedule-docs').reportValidity() || !$('schedule-requests').reportValidity()) return;
    guarded(() => timingChange('llm-run', { action: 'start', maxDocuments: Number($('schedule-docs').value), maxRequests: Number($('schedule-requests').value) }, 'POST'));
  });
  $('schedule-stop')?.addEventListener('click', () => guarded(() => timingChange('llm-run', { action: 'stop' }, 'POST')));

  // The knowledge graph settings of each collection (package 6.2): on or off (off keeps its graph data), and its vocabulary.
  async function kgCollections(run) {
    const r = await fetch(ROOT + 'collections', { credentials: 'same-origin', cache: 'no-store' });
    let data = null; try { data = await r.json(); } catch (_) { /* below */ }
    if (run !== generation) return;
    if (!r.ok || !data) throw new Error(t('error') + ' (HTTP ' + r.status + ', ' + (data?.error?.code || 'error') + ')');
    const body = $('kgc').tBodies[0]; body.replaceChildren();
    const heads = [...$('kgc').tHead.rows[0].cells].map(c => c.textContent);
    // the model of the LLM enrichment is the one of the LLM selection (usage knowledge); there is no second setting
    const lm = data.llm || {};
    $('kgc-model').textContent = lm.model ? t('kgc_model').replace('%1', lm.model) : t('kgc_no_model');
    $('kgc-llm-state').textContent = t('kg_llm') + ': ' + (lm.active ? t('kg_on') : t('kg_off') + ' · '
      + t(!lm.model ? 'llm_reason_no_model' : 'llm_reason_no_collections'));
    for (const c of data.collections) {
      const name = node('span'); name.append(node('strong', c.name));
      if (c.name !== c.collection) name.append(' ', node('code', c.collection));
      if (!c.inCatalog) name.append(' ', node('span', '(' + t('kgc_not_in_catalog') + ')', 'sseo-note'));
      const active = node('select', null, 'form-control'); active.setAttribute('aria-label', t('kgc_label_active').replace('%1', c.name));
      for (const [v, k] of [['on', 'kgc_on'], ['off', 'kgc_off']]) { const o = node('option', t(k)); o.value = v; active.append(o); }
      active.value = c.active ? 'on' : 'off';
      const vocab = node('select', null, 'form-control'); vocab.setAttribute('aria-label', t('kgc_label_vocabulary').replace('%1', c.name));
      const dflt = node('option', c.defaultVocabulary ? t('kgc_default').replace('%1', c.defaultVocabulary) : t('kgc_default_none')); dflt.value = '__default';
      const none = node('option', t('kgc_none')); none.value = '__none';
      vocab.append(dflt);
      for (const v of data.vocabularies) { const o = node('option', v); o.value = v; vocab.append(o); }
      if (c.vocabularySetting && !data.vocabularies.includes(c.vocabularySetting)) { const o = node('option', c.vocabularySetting + ' (' + t('vocab_unknown') + ')'); o.value = c.vocabularySetting; vocab.append(o); }
      vocab.append(none);
      vocab.value = c.vocabularySetting == null ? '__default' : c.vocabularySetting === '' ? '__none' : c.vocabularySetting;
      // the LLM enrichment of this collection: never on by itself; with * for every collection it is shown, not changed here
      const llm = node('select', null, 'form-control'); llm.setAttribute('aria-label', t('kgc_label_llm').replace('%1', c.name));
      for (const [v, k] of [['off', 'kgc_llm_off'], ['on', 'kgc_llm_on']]) { const o = node('option', t(k)); o.value = v; llm.append(o); }
      llm.value = c.llm ? 'on' : 'off';
      if (c.llmBy === 'all') { llm.disabled = true; llm.title = t('kgc_llm_all'); llm.options[1].textContent = t('kgc_llm_all'); }
      const llmCell = node('span'); llmCell.append(llm);
      if (c.llm && !c.llmActive) llmCell.append(' ', node('span', '(' + t(!lm.model ? 'llm_reason_no_model' : 'kgc_llm_waits') + ')', 'sseo-note'));
      const save = node('button', t('kgc_save'), 'btn btn-default btn-sm'); save.type = 'button'; save.disabled = true;
      const jobs = node('span'); const jobControls = [];
      for (const [field, key] of [['jobsExtraction', 'jobs_extraction'], ['jobsDisplay', 'jobs_display'], ['jobsMatching', 'jobs_matching']]) {
        const label = node('label'); const input = node('input'); input.type = 'checkbox'; input.checked = Boolean(c[field]); input.dataset.field = field;
        label.append(input, ' ', t(key)); jobs.append(label, node('br')); jobControls.push(input);
      }
      const initial = [active.value, vocab.value, llm.value, ...jobControls.map(input => input.checked)];
      const changed = () => { save.disabled = active.value === initial[0] && vocab.value === initial[1] && llm.value === initial[2]
        && jobControls.every((input, i) => input.checked === initial[i + 3]); };
      active.addEventListener('change', changed); vocab.addEventListener('change', changed); llm.addEventListener('change', changed);
      jobControls.forEach(input => input.addEventListener('change', changed));
      save.addEventListener('click', () => guarded(() => saveKgCollection(c, active, vocab, initial, llm, lm.model, jobControls)));
      const row = rowInto(body, heads, [name, c.indexDocuments == null ? t('missing') : fmt(c.indexDocuments), active, vocab, llmCell, jobs,
        c.graphDocuments == null ? t('missing') : fmt(c.graphDocuments), t('vstate_' + c.state), save]);
      row.dataset.collection = c.collection; row.dataset.state = c.state;
    }
    if (!data.collections.length) { const row = body.insertRow(); const td = row.insertCell(); td.colSpan = heads.length; td.textContent = t('kgc_empty'); }
  }
  async function saveKgCollection(c, active, vocab, initial, llm, model, jobControls) {
    const change = {};
    jobControls.forEach((input, i) => { if (input.checked !== initial[i + 3]) change[input.dataset.field] = input.checked; });
    if (active.value !== initial[0]) change.active = active.value === 'on';
    if (vocab.value !== initial[1]) change.vocabulary = vocab.value === '__default' ? null : vocab.value === '__none' ? '' : vocab.value;
    if (llm.value !== initial[2]) change.llm = llm.value === 'on';
    if (!Object.keys(change).length) return;
    if (change.active === false && !window.confirm(t('kgc_off_question').replace('%1', c.name))) return;
    if ('vocabulary' in change && (change.active ?? c.active) && !window.confirm(t('kgc_vocab_question').replace('%1', c.name))) return;
    if (change.llm === true && !window.confirm((model ? t('kgc_llm_question').replace('%2', model) : t('kgc_llm_question_no_model')).replace('%1', c.name))) return;
    $('kgc-message').textContent = t('loading');
    const response = await fetch(ROOT + 'collections/' + encodeURIComponent(c.collection), { method: 'PATCH', credentials: 'same-origin', cache: 'no-store',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(change) });
    let body = null; try { body = await response.json(); } catch (_) { /* below */ }
    if (!response.ok) {
      $('kgc-message').textContent = '';
      throw new Error(t('error') + ' (HTTP ' + response.status + ', ' + (body?.error?.code || 'error') + ')');
    }
    const done = !body.changed ? t('kgc_unchanged') : body.applied ? t('kgc_saved') : t('kgc_stored');
    const text = [done.replace('%1', c.name), body.backfill ? t('kgc_backfill') : '', body.reextract ? t('kgc_reextract') : '',
      body.llmWarning === 'no_model' ? t('kgc_llm_warn_no_model') : body.llmWarning === 'collection_not_active' ? t('kgc_llm_warn_not_active') : '']
      .filter(Boolean).join(' ');
    await settings();
    $('kgc-message').textContent = text;
  }

  // ---------------------------------------------------------------------- views
  function load() {
    const p = new URLSearchParams(location.search);
    view = VIEWS.includes(p.get('view')) ? p.get('view') : 'overview';
    collection = p.get('collection') || '';
    // a collection of the link (or of history) counts only if it is one of the listed ones; else all collections
    const unknown = collection !== '' && !allowed.includes(collection);
    if (unknown) {
      collection = ''; p.delete('collection');
      history.replaceState(null, '', 'ScoutroKnowledge_p.html' + (p.toString() ? '?' + p : '') + location.hash);
    }
    $('scope-unknown').hidden = !unknown;
    offset = Math.max(0, parseInt(p.get('offset') || '0', 10) || 0);
    $('collection').value = collection;
    for (const v of VIEWS) $(v).hidden = v !== view;
    const nav = view === 'network' && p.get('group') ? 'services' : view === 'object' || view === 'source' || view === 'network' ? 'objects' : view;
    for (const a of document.querySelectorAll('[data-skg-view]')) {
      if (a.dataset.skgView === nav) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
      const q = new URLSearchParams(a.dataset.skgView === 'overview' ? {} : { view: a.dataset.skgView }); if (collection) q.set('collection', collection);
      a.href = 'ScoutroKnowledge_p.html' + (q.toString() ? '?' + q : '');
    }
    if (view === 'overview') guarded(overview);
    else if (view === 'objects') {
      for (const k of OBJECT_FILTERS) { const el = $(k); el.value = p.get(k) || ''; if (p.get(k) && el.value !== p.get(k)) { const o = node('option', p.get(k)); o.value = p.get(k); el.append(o); el.value = p.get(k); } }
      guarded(objects);
    } else if (view === 'object') guarded(() => object(p.get('id') || ''));
    else if (view === 'network') guarded(() => network(p));
    else if (view === 'services') guarded(() => servicesView(p));
    else if (view === 'compare') guarded(() => compare(p.get('category') || ''));
    else if (view === 'source') guarded(() => source(p.get('doc') || ''));
    else if (view === 'history') guarded(() => historyView(p));
    else guarded(settings);
  }

  $('scope').addEventListener('submit', e => e.preventDefault());
  $('collection').addEventListener('change', () => {
    collection = $('collection').value;
    const p = Object.fromEntries(new URLSearchParams(location.search)); delete p.collection; delete p.offset;
    navigate(p);
  });
  $('search').addEventListener('submit', e => { e.preventDefault(); navigate(objectQuery()); });
  $('net-form').addEventListener('submit', e => { e.preventDefault(); navigate(networkParams()); });
  $('f-list').addEventListener('change', () => {
    $('net-wrap').hidden = $('f-list').checked;
    history.replaceState(null, '', 'ScoutroKnowledge_p.html?' + new URLSearchParams({ ...networkParams(), ...(collection ? { collection } : {}) }));
    if (!$('f-list').checked && graph) renderNetwork(); // drawn for the width it has now
  });
  $('net-more').addEventListener('click', () => guarded(networkMore));
  $('net-reset').addEventListener('click', () => {
    const p = new URLSearchParams(location.search);
    navigate(p.get('group') ? { view: 'network', group: p.get('group'), ...(p.get('category') ? { category: p.get('category') } : {}) }
      : { view: 'network', id: p.get('id') || graph?.center || '' });
  });
  $('svc-back').addEventListener('click', e => { if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey) return; e.preventDefault(); navigate({ view: 'services' }); });
  $('svc-network').addEventListener('click', e => {
    if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || !e.currentTarget.dataset.query) return; e.preventDefault();
    navigate(JSON.parse(e.currentTarget.dataset.query));
  });
  // the layout follows the width: draw again after a resize (rotation of a phone, a narrower window)
  let resizeTimer = 0;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => {
      const w = $('net-wrap').clientWidth;
      if (view === 'network' && graph && !$('net-wrap').hidden && Math.abs(w - drawnWidth) > 40) { drawnWidth = w; renderNetwork(); }
    }, 200);
  });
  $('compare-form').addEventListener('submit', e => { e.preventDefault(); navigate({ view: 'compare', category: $('compare-category').value }); });
  $('svc-form').addEventListener('submit', e => {
    e.preventDefault(); const q = { view: 'services' };
    if ($('svc-q').value.trim()) q.q = $('svc-q').value.trim(); if ($('svc-category').value) q.category = $('svc-category').value;
    navigate(q);
  });
  $('svc-prev').addEventListener('click', () => navigate(servicesQuery(Math.max(0, offset - LIMIT))));
  $('svc-next').addEventListener('click', () => navigate(servicesQuery(offset + LIMIT)));
  $('prev').addEventListener('click', () => navigate({ ...objectQuery(), offset: Math.max(0, offset - LIMIT) }));
  $('next').addEventListener('click', () => navigate({ ...objectQuery(), offset: offset + LIMIT }));
  for (const b of document.querySelectorAll('[data-skg-action]')) b.addEventListener('click', () => guarded(() => control(b.dataset.skgAction)));
  for (const a of document.querySelectorAll('[data-skg-view]')) a.addEventListener('click', e => {
    if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey) return; e.preventDefault();
    navigate(a.dataset.skgView === 'overview' ? {} : { view: a.dataset.skgView });
  });
  window.addEventListener('popstate', load);
  // the views start once the list of collections is there, so a collection of the link can be checked against it
  ScoutroCollections.load().then(ids => { allowed = ids; ScoutroCollections.fill($('collection'), ids, ''); load(); });
})();
