/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. The review of the correction automation (WP-H,
 * docs/SCOUTRO_COLLECTION_AUTOCORRECT.md) on Settings > Collections: status, the random sample and the acceptance, the
 * breaker, and the cases with approve, reject and the judgement right or wrong. Administrator only; values as text. */
(function () {
  'use strict';
  var ROOT = '/scoutro/api/v1/collections/autocorrect';
  function $(id) { return document.getElementById('sca-' + id); }
  if (!$('table')) return;
  function label(key) { var el = document.querySelector('[data-sco-label="' + key + '"]'); return el ? el.textContent : key; }
  function fill(text) { var args = Array.prototype.slice.call(arguments, 1); return text.replace(/%(\d)/g, function (m, i) { return args[i - 1] == null ? '' : String(args[i - 1]); }); }
  function el(tag, text, cls) { var n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; }
  function pct(v) { return v == null ? '—' : (v * 100).toLocaleString(document.documentElement.lang || undefined, { maximumFractionDigits: 2 }) + ' %'; }
  function message(text, kind) { var m = $('message'); m.textContent = text || ''; m.className = 'scoutro-status' + (kind ? ' scoutro-status-' + kind : ''); }
  var collections = [];
  var last = null;

  function call(path, body) {
    return fetch(ROOT + path, { method: body ? 'POST' : 'GET', credentials: 'same-origin', cache: 'no-store',
      headers: body ? { 'Content-Type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (j) {
          if (!r.ok) { var e = new Error((j.error && (j.error.message || j.error.code)) || ('HTTP ' + r.status)); e.details = j.error && j.error.details; throw e; }
          return j;
        });
      });
  }

  function fact(dl, key, value) { dl.appendChild(el('dt', label(key))); dl.appendChild(el('dd', value)); }

  function status(report) {
    var dl = $('status'); dl.textContent = '';
    var st = report.stats || {}, sample = st.sample || {}, all = st.all || {}, settings = report.settings || {};
    fact(dl, 'ac_mode', label('ac_mode_' + (settings.mode || 'off')));
    fact(dl, 'ac_breaker', label(report.breaker && report.breaker.open ? 'ac_breaker_open' : 'ac_breaker_closed'));
    fact(dl, 'ac_sample', fill(label('ac_sample_value'), sample.reviewed || 0, sample.right || 0, sample.pending || 0));
    fact(dl, 'ac_bound', pct(sample.lowerBound95));
    fact(dl, 'ac_needed', sample.reviewsForTarget < 0 ? label('ac_out_of_reach') : String(sample.reviewsForTarget == null ? '—' : sample.reviewsForTarget));
    fact(dl, 'ac_all', fill(label('ac_sample_value'), all.reviewed || 0, all.right || 0, all.pending || 0));
    var a = report.acceptance || {};
    $('acceptance').textContent = label('ac_stage_' + (a.accepted ? (a.stage || 'none') : 'none'));
    $('breaker').hidden = !(report.breaker && report.breaker.open);
  }

  function button(text, cls, handler) {
    var b = el('button', text, 'btn btn-sm ' + (cls || 'btn-default')); b.type = 'button';
    b.addEventListener('click', function () { handler().catch(function (e) { message(e.message, 'error'); }); });
    return b;
  }

  function review(domain, verdict, target) {
    var body = { domain: domain, verdict: verdict };
    if (target) body.target = target;
    return call('/review', body).then(function () { message(label('ac_saved'), 'ok'); return load(); });
  }

  function signals(c) {
    var by = {};
    (c.signals || []).forEach(function (s) {
      var key = s.origin && s.origin.indexOf('website:') === 0 ? 'website' : (s.origin || '').split(':')[0];
      (by[key] = by[key] || []).push(s.family + ' ' + s.verdict + ' ' + s.collection);
    });
    var box = el('div');
    Object.keys(by).sort().forEach(function (k) { box.appendChild(el('div', k + ': ' + by[k].join('; '), 'scoutro-hint')); });
    return box;
  }

  function render(report) {
    var body = $('table').tBodies[0]; body.textContent = '';
    var cases = (report.cases || []).filter(function (c) { return !$('drawn').checked || c.sample; });
    if (!cases.length) { var r = body.insertRow(), td = r.insertCell(); td.colSpan = 5; td.textContent = label('ac_none'); return; }
    cases.forEach(function (c) {
      var row = body.insertRow(); row.dataset.domain = c.domain; row.dataset.status = c.status;
      row.insertCell().appendChild(el('strong', c.domain));
      var p = row.insertCell();
      p.appendChild(el('div', (c.from || '—') + ' ' + label('ac_to') + ' ' + (c.target || label('ac_review_case'))));
      p.appendChild(el('div', c.result + ' · ' + c.reason + (c.severity ? ' · ' + c.severity : ''), 'scoutro-hint'));
      row.insertCell().appendChild(signals(c));
      var s = row.insertCell();
      s.appendChild(el('span', label('ac_st_' + c.status)));
      if (c.sample) s.appendChild(el('span', ' · ' + label(c.sample.audit ? 'ac_audit' : 'ac_drawn'), 'scoutro-badge'));
      if (c.held) s.appendChild(el('div', fill(label('ac_held'), c.held), 'scoutro-hint'));
      var act = row.insertCell();
      if (c.status === 'proposed' || c.status === 'simulated') {
        if (c.result === 'review') {
          var sel = el('select', null, 'form-control'); sel.setAttribute('aria-label', label('ac_target'));
          collections.forEach(function (id) { if (id !== c.from) { var o = el('option', id); o.value = id; sel.appendChild(o); } });
          act.appendChild(sel);
          act.appendChild(button(label('ac_approve'), 'btn-primary', function () {
            if (!window.confirm(label('ac_approve_question'))) return Promise.resolve();
            return review(c.domain, 'approve', sel.value);
          }));
        } else {
          act.appendChild(button(label('ac_approve'), 'btn-primary', function () {
            if (!window.confirm(label('ac_approve_question'))) return Promise.resolve();
            return review(c.domain, 'approve');
          }));
        }
        act.appendChild(button(label('ac_reject'), null, function () { return review(c.domain, 'reject'); }));
      }
      if ((c.status === 'proposed' || c.status === 'simulated') && c.result === 'correct' || c.status === 'applied') {
        act.appendChild(button(label('ac_right'), null, function () { return review(c.domain, 'correct'); }));
        act.appendChild(button(label('ac_wrong'), null, function () { return review(c.domain, 'wrong'); }));
      }
    });
  }

  function load() {
    return call('?limit=500').then(function (report) {
      last = report;
      if (!report.available) message(label('ac_unavailable'), 'error');
      status(report);
      render(report);
    }).catch(function () { message(label('ac_error'), 'error'); });
  }

  $('sample').addEventListener('click', function () {
    var size = parseInt($('size').value, 10);
    call('/sample', { size: size }).then(function (r) {
      message(fill(label('ac_drawn_done'), r.draw.drawn, r.draw.population), 'ok');
      $('drawn').checked = true;
      return load();
    }).catch(function (e) { message(e.message, 'error'); });
  });
  $('accept').addEventListener('click', function () {
    call('/accept', {}).then(function () { message(label('ac_accepted'), 'ok'); return load(); })
      .catch(function (e) { message(fill(label('ac_not_accepted'), (e.details && e.details.missing || []).join('; ') || e.message), 'error'); });
  });
  $('breaker').addEventListener('click', function () {
    call('/breaker-reset', {}).then(function () { message(label('ac_breaker_closed_done'), 'ok'); return load(); })
      .catch(function (e) { message(e.message, 'error'); });
  });
  $('drawn').addEventListener('change', function () { if (last) render(last); });
  fetch('/scoutro/api/v1/collections', { credentials: 'same-origin', cache: 'no-store' })
    .then(function (r) { return r.ok ? r.json() : { collections: [] }; })
    .then(function (d) { collections = (d.collections || []).filter(function (c) { return !c.internal; }).map(function (c) { return c.id; }).sort(); })
    .catch(function () { collections = []; })
    .then(load);
})();
