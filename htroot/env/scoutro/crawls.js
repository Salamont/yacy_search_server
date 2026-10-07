/* Scoutro contributors, GPL-2.0-or-later. No implicit crawl on page load. */
(() => {
  'use strict';
  const $ = id => document.getElementById('scc-' + id);
  const labels = Object.fromEntries([...document.querySelectorAll('[data-scc-label]')].map(n => [n.dataset.sccLabel, n.textContent]));
  const t = key => labels[key] || key, root = '/scoutro/api/v1/';
  const node = (tag, text) => { const element = document.createElement(tag); if (text != null) element.textContent = text; return element; };
  const key = () => [...crypto.getRandomValues(new Uint8Array(16))].map(n => n.toString(16).padStart(2, '0')).join('');
  const CRAWL_ID = /^[A-Za-z0-9_-]{1,64}$/;
  let submissionKey = key(), busy = false, changed = false, locked = false, poll = null, epoch = 0, controller = null;
  // the last start attempt of this page (or the crawl of ?crawl= after a reload); the panel shows only backend facts
  let current = null;
  const message = text => { $('message').textContent = text; };
  async function api(path, options = {}) {
    const response = await fetch(root + path, { credentials:'same-origin', cache:'no-store', ...options,
      headers:{ Accept:'application/json', ...options.headers } });
    let result; try { result = await response.json(); } catch { throw new Error(t('error') + ' (HTTP ' + response.status + ')'); }
    if (!response.ok) { const error = new Error(t('error') + ' (HTTP ' + response.status + ', ' + (result.error?.code || 'error') + ')'); error.code = result.error?.code; error.status = response.status; throw error; }
    return result;
  }
  const when = iso => { const date = iso ? new Date(iso) : null; return date && !isNaN(date) ? date.toLocaleString() : null; };
  function render(crawls) {
    $('crawls').replaceChildren();
    if (!crawls.length) { $('crawls').append(node('p', t('empty'))); return; }
    for (const crawl of crawls) {
      const card = node('article'); card.className = 'scc-card'; card.dataset.crawlId = crawl.id || '';
      if (CRAWL_ID.test(crawl.id || '')) { card.id = 'scc-crawl-' + crawl.id; card.tabIndex = -1; }
      card.append(node('h3', crawl.url || crawl.name || crawl.id));
      if (current?.crawl?.id && current.crawl.id === crawl.id) { card.classList.add('scc-card-current'); const badge = node('span', t('this_start')); badge.className = 'scc-badge'; card.append(badge); }
      card.append(node('p', t(crawl.state)));
      const dl = node('dl');
      for (const [label, value] of [['collection',crawl.collection ?? crawl.collections?.join(', ')], ['scope',crawl.scope ? t(crawl.scope) : null],
        ['pages',crawl.progress?.pagesLoaded ?? crawl.pagesLoaded], ['depth',crawl.depth], ['started_at',when(crawl.startedAt)], ['last_error',crawl.lastError]])
        dl.append(node('dt',t(label)),node('dd',value == null || value === '' ? t('unknown') : String(value)));
      card.append(dl);
      if (crawl.host) { const link = node('a', t('analyze')); const params = new URLSearchParams({host:crawl.host}); if (crawl.collection) params.set('collection',crawl.collection); link.href = 'ScoutroSEO_p.html?' + params; card.append(link); }
      const id = node('small',crawl.id); card.append(node('br'),id); $('crawls').append(card);
    }
  }
  // ---- result of the start attempt -------------------------------------------------------------------------------
  // outcome: started (201), replayed (200, same key), restored (?crawl= after a reload), rejected (4xx),
  // unconfirmed (crawl_start_unconfirmed), failed (5xx or no answer), gone (?crawl= no longer known).
  // The status shown is the backend state of the crawl profile; YaCy reports no queue position, so none is claimed.
  function statusOf(entry) {
    const state = entry.crawl?.state;
    if (['running','paused','terminated','removed'].includes(state)) return t('status_' + state);
    if (state) return t(state);
    return t(entry.outcome === 'rejected' ? 'status_not_started' : 'status_unknown');
  }
  function errorText(answer) {
    if (!answer.status) return t('err_network');
    const code = answer.body?.error?.code;
    if (code && labels['err_' + code]) return t('err_' + code);
    if (answer.status === 401 || answer.status === 403) return t('err_unauthorized');
    if (answer.status === 503) return t('err_unavailable');
    if (answer.status === 400) return t('err_invalid_request');
    return t('err_generic');
  }
  function showResult() {
    const entry = current;
    $('result').hidden = !entry;
    if (!entry) return;
    $('result').classList.toggle('scc-result-bad', ['rejected','failed','gone'].includes(entry.outcome));
    $('result').classList.toggle('scc-result-open', ['unconfirmed'].includes(entry.outcome));
    $('result').setAttribute('aria-busy', entry.outcome === 'pending' ? 'true' : 'false');
    $('outcome').textContent = entry.outcome === 'pending' ? t('starting') : t('outcome_' + entry.outcome);
    const crawl = entry.crawl || {};
    const rows = [['fact_url', crawl.url || entry.request?.url], ['collection', crawl.collection || entry.request?.collection]];
    if (entry.outcome !== 'pending') {
      rows.push(['fact_result', t('result_' + entry.outcome)], ['fact_status', statusOf(entry)],
        ['fact_id', crawl.id || (entry.outcome === 'rejected' ? t('no_id') : null)]);
      if (crawl.progress?.pagesLoaded != null) rows.push(['pages', crawl.progress.pagesLoaded]);
    }
    if (entry.attemptedAt) rows.push(['fact_attempted', entry.attemptedAt.toLocaleString()]);
    if (crawl.startedAt) rows.push(['started_at', when(crawl.startedAt)]);
    $('facts').replaceChildren(...rows.flatMap(([label, value]) => {
      const dd = node('dd', value == null || value === '' ? t('unknown') : String(value)); dd.dataset.fact = label;
      return [node('dt', t(label)), dd];
    }));
    $('error').hidden = !entry.error; $('error').textContent = entry.error ? entry.error.text : '';
    $('error-detail').hidden = !entry.error?.detail; $('error-detail').textContent = entry.error?.detail || '';
    const card = crawl.id && CRAWL_ID.test(crawl.id) ? document.getElementById('scc-crawl-' + crawl.id) : null;
    $('status-link').hidden = !card;
    if (card) $('status-link').href = '#' + card.id;
  }
  function remember(id) {
    // the crawl of this start stays findable after a reload through its existing profile id (?crawl=)
    const params = new URLSearchParams(location.search);
    if (id) params.set('crawl', id); else params.delete('crawl');
    const query = params.toString();
    try { history.replaceState(history.state, '', location.pathname + (query ? '?' + query : '') + location.hash); } catch { /* not essential */ }
  }
  async function follow(list) {
    // keeps the status of the panel's crawl current: from the list, else from GET crawls/{id} (removed profiles)
    const entry = current, id = entry?.crawl?.id;
    if (!id || !CRAWL_ID.test(id) || entry.outcome === 'gone') { showResult(); return; }
    const listed = list.find(crawl => crawl.id === id);
    if (listed) { entry.crawl = {...entry.crawl, ...listed}; }
    else if (entry.crawl.state !== 'removed') {
      try { const crawl = await api('crawls/' + encodeURIComponent(id)); if (current === entry) entry.crawl = {...entry.crawl, ...crawl}; }
      catch (error) { if (error.status === 404 && current === entry) entry.crawl = {...entry.crawl, state:'removed'}; }
    }
    if (current === entry) showResult();
  }
  function refresh() {
    if (busy || poll) return poll || Promise.resolve();
    const ticket = epoch; controller = new AbortController();
    poll = api('crawls', {signal:controller.signal}).then(async result => { if (ticket !== epoch) return; render(result.crawls); await follow(result.crawls); })
      .catch(error => { if (ticket === epoch && error.name !== 'AbortError') message(error.message); })
      .finally(() => { poll = null; });
    return poll;
  }
  async function startRequest(request) {
    let response;
    try {
      response = await fetch(root + 'crawls', {method:'POST', credentials:'same-origin', cache:'no-store',
        headers:{Accept:'application/json','Content-Type':'application/json','Idempotency-Key':submissionKey}, body:JSON.stringify(request)});
    } catch { return {status:0, body:null}; }
    let body = null; try { body = await response.json(); } catch { /* an answer without JSON stays a failure with its status */ }
    return {status:response.status, body};
  }
  function lock(value) {
    // after a confirmed start the same request cannot be sent again; any change of the form is a new request
    locked = value; $('start').disabled = value; $('locked').hidden = !value;
  }
  $('form').addEventListener('input', () => { if (busy) { changed = true; return; } submissionKey = key(); if (locked) lock(false); });
  $('form').addEventListener('submit', async event => {
    event.preventDefault(); if (busy || locked || !$('form').reportValidity()) return;
    busy = true; changed = false; epoch++; controller?.abort(); $('start').disabled = true; $('form').setAttribute('aria-busy', 'true');
    const request = {url:$('url').value.trim(),collection:$('collection').value,scope:$('scope').value, maxPages:Number($('pages').value),depth:Number($('depth').value)};
    const entry = current = {request, attemptedAt:new Date(), outcome:'pending', crawl:null, error:null};
    showResult(); message('');
    const answer = await startRequest(request), code = answer.body?.error?.code;
    if (answer.status >= 200 && answer.status < 300 && answer.body?.id) {
      entry.outcome = answer.body.idempotentReplay ? 'replayed' : 'started'; entry.crawl = answer.body;
    } else {
      entry.outcome = code === 'crawl_start_unconfirmed' ? 'unconfirmed' : answer.status >= 400 && answer.status < 500 ? 'rejected' : 'failed';
      const detail = answer.status ? 'HTTP ' + answer.status + (code ? ', ' + code : '') + (answer.body?.error?.message ? ': ' + answer.body.error.message : '') : null;
      entry.error = {text:errorText(answer), detail};
    }
    remember(entry.crawl?.id || null);
    busy = false; $('form').removeAttribute('aria-busy');
    // An open outcome (no answer, 5xx, unconfirmed) keeps its key: a retry is replayed by the backend, never started twice.
    // A definite refusal gets a new key, so that a retry is a new start attempt; an active crawl of the host still answers host_busy.
    if (changed || entry.outcome === 'rejected') submissionKey = key();
    lock(!changed && (entry.outcome === 'started' || entry.outcome === 'replayed'));
    if (!locked) $('start').disabled = false;
    showResult();
    if (poll) await poll; await refresh();
  });
  $('status-link').addEventListener('click', event => {
    const card = document.getElementById($('status-link').hash.slice(1));
    if (!card) return;
    event.preventDefault(); card.scrollIntoView({block:'center'}); card.focus({preventScroll:true});
  });
  $('refresh').addEventListener('click',refresh);
  const initial = new URLSearchParams(location.search);
  if (initial.has('url')) $('url').value = initial.get('url');
  const restoreId = initial.get('crawl');
  if (restoreId && CRAWL_ID.test(restoreId)) {
    // after a reload: the crawl of the last start, read again from the backend
    const entry = current = {request:null, attemptedAt:null, outcome:'restored', crawl:{id:restoreId}, error:null};
    api('crawls/' + encodeURIComponent(restoreId)).then(crawl => { if (current === entry) { entry.crawl = crawl; showResult(); refresh(); } })
      .catch(error => {
        if (current !== entry) return;
        if (error.status === 404) { entry.outcome = 'gone'; entry.crawl = {id:restoreId}; remember(null); }
        else entry.error = {text:t('err_generic'), detail:error.message};
        showResult();
      });
  }
  // package 6.1: the collection is chosen from the catalog, never typed; a new one is created explicitly and then selected
  const wanted = initial.get('collection') || '';
  ScoutroCollections.catalog().then(catalog => {
    if (!catalog.available) message(t('catalog_error'));
    if (ScoutroCollections.fill($('collection'), catalog.ids, wanted) !== wanted && wanted) message(t('unknown_collection'));
  });
  ScoutroCollections.offerCreate($('collection-new'), (entry, created) => ScoutroCollections.reload().then(ids => {
    ScoutroCollections.fill($('collection'), ids, entry.id);
    submissionKey = key(); if (locked) lock(false); // a changed request is a new start
    message(created + ' ' + (entry.name && entry.name !== entry.id ? entry.name + ' · ' : '') + entry.id);
    $('collection').focus();
  }));
  refresh(); const timer = setInterval(refresh,15000);
  window.addEventListener('pagehide', () => { epoch++; controller?.abort(); clearInterval(timer); });
})();
