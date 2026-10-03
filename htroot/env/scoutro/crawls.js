/* Scoutro contributors, GPL-2.0-or-later. No implicit crawl on page load. */
(() => {
  'use strict';
  const $ = id => document.getElementById('scc-' + id);
  const labels = Object.fromEntries([...document.querySelectorAll('[data-scc-label]')].map(n => [n.dataset.sccLabel, n.textContent]));
  const t = key => labels[key] || key, root = '/scoutro/api/v1/';
  const node = (tag, text) => { const element = document.createElement(tag); if (text != null) element.textContent = text; return element; };
  const key = () => [...crypto.getRandomValues(new Uint8Array(16))].map(n => n.toString(16).padStart(2, '0')).join('');
  let submissionKey = key(), busy = false, poll = null, epoch = 0, controller = null;
  const message = text => { $('message').textContent = text; };
  async function api(path, options = {}) {
    const response = await fetch(root + path, { credentials:'same-origin', cache:'no-store', ...options,
      headers:{ Accept:'application/json', ...options.headers } });
    let result; try { result = await response.json(); } catch { throw new Error(t('error') + ' (HTTP ' + response.status + ')'); }
    if (!response.ok) { const error = new Error(t('error') + ' (HTTP ' + response.status + ', ' + (result.error?.code || 'error') + ')'); error.code = result.error?.code; throw error; }
    return result;
  }
  function render(crawls) {
    $('crawls').replaceChildren();
    if (!crawls.length) { $('crawls').append(node('p', t('empty'))); return; }
    for (const crawl of crawls) {
      const card = node('article'); card.className = 'scc-card'; card.dataset.crawlId = crawl.id || '';
      card.append(node('h3', crawl.url || crawl.name || crawl.id), node('p', t(crawl.state)));
      const dl = node('dl');
      for (const [label, value] of [['collection',crawl.collection ?? crawl.collections?.join(', ')], ['scope',crawl.scope ? t(crawl.scope) : null],
        ['pages',crawl.progress?.pagesLoaded ?? crawl.pagesLoaded], ['depth',crawl.depth], ['started_at',crawl.startedAt ? new Date(crawl.startedAt).toLocaleString() : null], ['last_error',crawl.lastError]])
        dl.append(node('dt',t(label)),node('dd',value == null || value === '' ? t('unknown') : String(value)));
      card.append(dl);
      if (crawl.host) { const link = node('a', t('analyze')); const params = new URLSearchParams({host:crawl.host}); if (crawl.collection) params.set('collection',crawl.collection); link.href = 'ScoutroSEO_p.html?' + params; card.append(link); }
      const id = node('small',crawl.id); card.append(node('br'),id); $('crawls').append(card);
    }
  }
  function refresh() {
    if (busy || poll) return poll || Promise.resolve();
    const ticket = epoch; controller = new AbortController();
    poll = api('crawls', {signal:controller.signal}).then(result => { if (ticket === epoch) render(result.crawls); })
      .catch(error => { if (ticket === epoch && error.name !== 'AbortError') message(error.message); })
      .finally(() => { poll = null; });
    return poll;
  }
  $('form').addEventListener('input', () => { if (!busy) submissionKey = key(); });
  $('form').addEventListener('submit', async event => {
    event.preventDefault(); if (busy || !$('form').reportValidity()) return;
    busy = true; epoch++; controller?.abort(); $('start').disabled = true; message(t('loading'));
    const request = {url:$('url').value.trim(),collection:$('collection').value.trim(),scope:$('scope').value, maxPages:Number($('pages').value),depth:Number($('depth').value)};
    try {
      const result = await api('crawls', {method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':submissionKey},body:JSON.stringify(request)});
      message(t(result.idempotentReplay ? 'replayed' : 'started')); render([result]);
      // Keep this key until the user changes the request; double clicks/retries replay.
    } catch(error) { message(error.message); }
    finally { busy = false; $('start').disabled = false; if (poll) await poll; await refresh(); }
  });
  $('refresh').addEventListener('click',refresh);
  const initial = new URLSearchParams(location.search);
  if (initial.has('url')) $('url').value = initial.get('url');
  if (initial.has('collection')) $('collection').value = initial.get('collection');
  api('collections').then(result => { for (const collection of result.collections) { const option = node('option'); option.value = collection.id; $('collections').append(option); } })
    .catch(() => message(t('catalog_error')));
  refresh(); const timer = setInterval(refresh,15000);
  window.addEventListener('pagehide', () => { epoch++; controller?.abort(); clearInterval(timer); });
})();
