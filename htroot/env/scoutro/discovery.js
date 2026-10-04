/* Scoutro contributors, GPL-2.0-or-later. Admin-only, same-origin JSON actions. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const text = key => document.querySelector(`[data-scd-label="${key}"]`)?.textContent || key;
  const enableLabel = document.querySelector('[data-scd-global=enable]').textContent;
  const apiRoot = 'scoutro/api/v1/discovery';
  let revision = 0, catalog = null, jobs = [], editing = null, active = false, activeJob = null, automation = null, mutating = false;
  let refreshPromise = null, refreshController = null, epoch = 0;
  const message = value => { $('scd-message').textContent = value; };
  const date = value => value ? new Date(value).toLocaleString() : '—';
  // getRandomValues also works on ordinary HTTP YaCy peers; randomUUID requires a secure context.
  const requestId = () => [...crypto.getRandomValues(new Uint8Array(16))].map(byte => byte.toString(16).padStart(2, '0')).join('');
  const element = (tag, content, className) => { const e = document.createElement(tag); if (content) e.textContent = content; if (className) e.className = className; return e; };
  async function api(path, method = 'GET', body, signal) {
    if (method !== 'GET') { if (mutating) throw new Error(text('busy')); mutating = true; epoch++; refreshController?.abort(); }
    if (method !== 'GET' && refreshPromise) await refreshPromise;
    const headers = { Accept: 'application/json' };
    if (method !== 'GET') { headers['Content-Type'] = 'application/json'; headers['If-Match'] = `"${revision}"`; }
    try {
    const response = await fetch(`${apiRoot}/${path}`, { method, headers, signal, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body) });
    let result; try { result = await response.json(); } catch { throw new Error(`${response.status}`); }
    if (!response.ok) throw new Error(result.error?.message || `${response.status}`);
    if (method !== 'GET' && typeof result.revision === 'number') revision = result.revision;
    return result;
    } finally { if (method !== 'GET') mutating = false; }
  }
  function button(label, callback) {
    const b = element('button', text(label), 'btn btn-default'); b.type = 'button';
    b.addEventListener('click', () => Promise.resolve(callback()).catch(e => message(e.message))); return b;
  }
  function refresh() {
    if (refreshPromise) return refreshPromise;
    if (mutating) return Promise.resolve();
    const ticket = epoch; refreshController = new AbortController();
    const signal = refreshController.signal;
    refreshPromise = (async () => {
      try {
        const [status, result] = await Promise.all([api('status','GET',undefined,signal),api('jobs','GET',undefined,signal)]);
        if (ticket !== epoch) return;
        // Job edits/worker progress may move the revision between the two reads.
        // Keep the preceding coherent snapshot and try again on the next poll.
        if (status.revision !== result.revision || status.revision < revision) return;
        revision = status.revision; automation = status.automation_status; active = !!status.active_batch; activeJob = status.active_batch?.job_id;
        $('scd-global-status').textContent = text(automation);
        $('scd-batch-status').textContent = text(status.running ? 'batch_running' : status.phase);
        $('scd-heartbeat').textContent = status.heartbeat.enabled ? `${status.heartbeat.interval_minutes} ${text('minutes')}` : text('disabled');
        $('scd-job-count').textContent = status.job_count;
        const batch = status.active_batch;
        $('scd-current-batch').textContent = batch ? `${batch.job_name || text('missing')} · ${batch.collection || '—'} · ${text(batch.phase)} · ${date(batch.started_at)}` : '—';
        $('scd-next-heartbeat').textContent = date(status.heartbeat.next_execution);
        $('scd-last-run').textContent = date(status.last_run?.finished_at);
        const profiles = status.state?.profiles;
        $('scd-fresh').textContent = status.state?.available && profiles ? Object.values(profiles).reduce((n,p) => n + p.fresh + p.legacy_auth_retry,0) : '—';
        $('scd-waiting').textContent = text(status.config_error || status.waiting_reason || 'idle');
        document.querySelectorAll('[data-scd-global]').forEach(button => {
          const allowed = status.allowed_actions.includes(button.dataset.scdGlobal);
          button.hidden = !allowed; button.disabled = !allowed;
          if (button.dataset.scdGlobal === 'enable') button.textContent = status.enabled ? text('repair_heartbeat') : enableLabel;
        });
        jobs = result.jobs; renderJobs();
      } catch(error) { if (ticket === epoch && error.name !== 'AbortError') message(error.message); }
      finally { refreshPromise = null; }
    })();
    return refreshPromise;
  }
  function renderJobs() {
    $('scd-jobs').replaceChildren(); $('scd-empty').hidden = jobs.length !== 0;
    for (const row of jobs) {
      const job = row.definition, card = element('article', '', 'scd-job'); card.dataset.jobId = job.id;
      card.append(element('h3', job.name), element('p', text(row.status)));
      const dl = element('dl');
      const detail = (label, value) => dl.append(element('dt', text(label)), element('dd', value));
      detail('profile', job.profile);
      detail('regions', Object.entries(job.sources).map(([id, spec]) => `${text(id)}: ${spec.mode === 'all' ? text('all') : spec.regions.map(region => catalog?.sources.find(s => s.id === id)?.regions.find(r => r.id === region)?.label || region).join(', ')}`).join(' · ') || text('profile_backlog'));
      detail('fresh', row.fresh_backlog === null ? '—' : String(row.fresh_backlog));
      detail('last', date(row.runtime.last_run?.finished_at)); detail('next', date(row.runtime.next_due)); card.append(dl);
      if (row.waiting_reason || row.runtime.error) card.append(element('p', text(row.waiting_reason || row.runtime.error), 'scd-message'));
      const actions = element('div', '', 'scd-actions');
      actions.append(button('edit', () => openEditor(job)),
        button(job.enabled ? 'disable' : 'enable', async () => { await api(`jobs/${job.id}`, 'PATCH', { enabled: !job.enabled }); await refresh(); }),
        button(job.paused ? 'resume' : 'pause', async () => { await api(`jobs/${job.id}`, 'PATCH', { paused: !job.paused }); await refresh(); }),
        button('run', async () => { await api(`jobs/${job.id}/run`, 'POST', { request_id: requestId() }); await refresh(); }),
        button('delete', async () => { if (confirm(text('confirm_delete'))) { await api(`jobs/${job.id}`, 'DELETE', {}); await refresh(); } }));
      actions.querySelectorAll('button').forEach((button,index) => { if (index === 3) button.disabled = automation !== 'active' || job.paused || active; if (index === 4) button.disabled = activeJob === job.id; });
      card.append(actions); $('scd-jobs').append(card);
    }
  }
  function sourceBlocks(selected = {}) {
    $('scd-sources').replaceChildren();
    const profile = catalog?.profiles.find(p => p.id === $('scd-profile').value);
    for (const source of catalog?.sources || []) {
      const field = element('fieldset', '', 'scd-source'); field.dataset.sourceId = source.id;
      const legend = element('legend'), enabled = element('input'); enabled.type = 'checkbox'; enabled.dataset.sourceEnabled = source.id;
      enabled.checked = !!selected[source.id]; enabled.disabled = !profile?.sources[source.id];
      const label = element('label', ` ${text(source.id)}`); label.prepend(enabled); legend.append(label); field.append(legend);
      if (enabled.disabled) field.append(element('p', text('unsupported')));
      const all = element('input'); all.type = 'checkbox'; all.dataset.sourceAll = source.id; all.checked = selected[source.id]?.mode === 'all';
      const allLabel = element('label', ` ${text('all')}`); allLabel.prepend(all); field.append(allLabel);
      field.append(element('p', text(source.region_type === 'search_text' ? 'search_regions' : 'regions')));
      const list = element('div', '', 'scd-region-list');
      const choices = [...source.regions];
      for (const id of selected[source.id]?.regions || []) if (!choices.some(r => r.id === id)) choices.push({ id, label: `${text('missing')}: ${id}` });
      for (const region of choices) {
        const check = element('input'); check.type = 'checkbox'; check.value = region.id;
        check.checked = !!selected[source.id]?.regions?.includes(region.id);
        const regionLabel = element('label', ` ${region.label}`); regionLabel.prepend(check); list.append(regionLabel);
      }
      const availability = () => { all.disabled = !enabled.checked || enabled.disabled; list.querySelectorAll('input').forEach(i => { i.disabled = !enabled.checked || enabled.disabled || all.checked; }); };
      enabled.addEventListener('change', availability); all.addEventListener('change', availability); availability();
      field.append(list); $('scd-sources').append(field);
    }
  }
  async function openEditor(job) {
    catalog = await api('catalog');
    if (!catalog) throw new Error(text('runtime_config_invalid'));
    editing = job?.id || null;
    $('scd-profile').replaceChildren(...catalog.profiles.map(p => { const option = element('option', p.id); option.value = p.id; return option; }));
    if (job && !catalog.profiles.some(p => p.id === job.profile)) { const option = element('option', `${text('missing')}: ${job.profile}`); option.value = job.profile; $('scd-profile').append(option); }
    $('scd-name').value = job?.name || ''; $('scd-profile').value = job?.profile || catalog.profiles[0]?.id || '';
    $('scd-scope').value = job?.candidate_scope || 'source_regions';
    for (const [id, value] of Object.entries({ domains: job?.batch.max_domains ?? 50, pages: job?.batch.max_pages ?? 15,
      depth: job?.batch.depth ?? 2, delay: job?.batch.seed_delay_seconds ?? 10, schedule: job?.schedule.every_minutes ?? 60,
      refresh: job?.discovery.replenish_interval_hours ?? 24, 'recrawl-days': job?.processing.recrawl.days ?? 30 })) $('scd-' + id).value = value;
    $('scd-domains').max = catalog.limits.max_domains; $('scd-pages').max = catalog.limits.max_pages; $('scd-depth').max = catalog.limits.depth;
    for (const [id, value] of Object.entries({ enabled: job?.enabled ?? false, paused: job?.paused ?? false,
      replenish: job?.discovery.replenish ?? true, 'process-fresh': job?.processing.fresh ?? true,
      'process-retry': job?.processing.retry ?? false, 'process-outcome-retry': job?.processing.outcome_retry ?? false,
      'process-recrawl': job?.processing.recrawl.enabled ?? false })) $('scd-' + id).checked = value;
    outcomeRetry();
    sourceBlocks(job?.sources || {}); $('scd-editor').hidden = false; $('scd-name').focus();
  }
  // A retry after an unsuccessful crawl result uses the retry path, so it needs retry.
  function outcomeRetry() { const retry = $('scd-process-retry').checked; $('scd-process-outcome-retry').disabled = !retry; if (!retry) $('scd-process-outcome-retry').checked = false; }
  $('scd-process-retry').addEventListener('change', outcomeRetry);
  $('scd-profile').addEventListener('change', () => sourceBlocks());
  $('scd-add').addEventListener('click', async () => { try { await openEditor(); } catch (e) { message(e.message); } });
  $('scd-cancel').addEventListener('click', () => { $('scd-editor').hidden = true; });
  $('scd-job-form').addEventListener('submit', async event => {
    event.preventDefault();
    const sources = {};
    for (const field of $('scd-sources').children) {
      if (!field.querySelector('[data-source-enabled]').checked) continue;
      const all = field.querySelector('[data-source-all]').checked;
      sources[field.dataset.sourceId] = { mode: all ? 'all' : 'selected', regions: all ? [] : [...field.querySelectorAll('.scd-region-list input:checked')].map(i => i.value) };
    }
    const number = id => Number($('scd-' + id).value), checked = id => $('scd-' + id).checked;
    const job = { name: $('scd-name').value, profile: $('scd-profile').value, candidate_scope: $('scd-scope').value, sources,
      enabled: checked('enabled'), paused: checked('paused'),
      discovery: { replenish: checked('replenish'), replenish_interval_hours: number('refresh') },
      batch: { max_domains: number('domains'), max_pages: number('pages'), depth: number('depth'), seed_delay_seconds: number('delay') },
      processing: { fresh: checked('process-fresh'), retry: checked('process-retry'), outcome_retry: checked('process-retry') && checked('process-outcome-retry'),
        recrawl: { enabled: checked('process-recrawl'), days: number('recrawl-days') } },
      schedule: { every_minutes: number('schedule') } };
    try { await api(editing ? `jobs/${editing}` : 'jobs', editing ? 'PATCH' : 'POST', job); $('scd-editor').hidden = true; message(text('saved')); await refresh(); }
    catch (e) { message(e.message); await refresh(); }
  });
  document.querySelectorAll('[data-scd-global]').forEach(b => b.addEventListener('click', async () => {
    try { await api(b.dataset.scdGlobal, 'POST', {}); message(''); await refresh(); } catch (e) { message(e.message); await refresh(); }
  }));
  api('catalog').then(value => { catalog = value; }).catch(e => message(e.message));
  refresh(); const timer = setInterval(refresh,15000);
  window.addEventListener('pagehide', () => { epoch++; refreshController?.abort(); clearInterval(timer); });
})();
