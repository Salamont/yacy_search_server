/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Knowledge graph assessments (docs/SCOUTRO_KG_ASSESSMENTS.md):
 * preview, store and apply, list, revoke. Administrator only; every value is rendered as text. */
(() => {
  'use strict';
  const ROOT = '/scoutro/api/v1/kg/assessments';
  const $ = id => document.getElementById('skg-asm-' + id);
  const lang = document.documentElement.lang || undefined;
  const node = (tag, text, className) => { const n = document.createElement(tag); if (text != null) n.textContent = text; if (className) n.className = className; return n; };
  let t = key => key;
  let pending = null; // the request body of the shown preview, with its token
  let bound = false;

  async function call(path, method, body, query, root = ROOT) {
    const q = query ? '?' + new URLSearchParams(query) : '';
    const response = await fetch(root + path + q, { method, credentials: 'same-origin', cache: 'no-store',
      headers: body ? { 'Content-Type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined });
    let json = null; try { json = await response.json(); } catch (_) { /* reported below */ }
    if (!response.ok) {
      const code = json?.error?.code || 'error';
      const known = t('asm_e_' + code);
      const error = new Error((known !== 'asm_e_' + code ? known : json?.error?.message || code) + ' (HTTP ' + response.status + ', ' + code + ')');
      error.code = code; throw error;
    }
    return json;
  }

  function request() {
    const body = { action: $('action').value, scope: { [$('scope-kind').value]: $('scope').value.trim() }, predicate: $('predicate').value.trim(),
      reason: $('reason').value.trim() };
    const optional = { value: $('value').value.trim(), subject: $('subject').value.trim(), targetPredicate: $('target').value.trim(),
      quote: $('quote').value.trim() };
    for (const [k, v] of Object.entries(optional)) if (v) body[k] = v;
    if ($('tier').value) body.tier = parseInt($('tier').value, 10);
    return body;
  }

  function stat(dl, label, value) { dl.append(node('dt', label), node('dd', value == null ? '–' : typeof value === 'number' ? value.toLocaleString(lang) : String(value))); }

  function showPreview(answer, body) {
    const c = answer.changes || {};
    const dl = $('changes'); dl.replaceChildren();
    if (body.action === 'assert') {
      stat(dl, t('asm_source_in_graph'), c.sourceInGraph ? t('asm_yes') : t('asm_no'));
      stat(dl, t('asm_subject_found'), c.subjectFound ? t('asm_yes') : t('asm_no'));
      stat(dl, t('asm_existing'), c.existingStatement || '–');
    } else {
      stat(dl, t('asm_evidence'), c.evidence);
      stat(dl, t('asm_dependent'), c.dependentEvidence);
      stat(dl, t('asm_statements'), c.statements);
      stat(dl, t('asm_leaving'), c.statementsLeaving);
      stat(dl, t('asm_documents'), c.documents);
      stat(dl, t('asm_observations'), c.observations);
      stat(dl, t('asm_contributions'), c.matchingContributions);
    }
    const sample = $('sample'); sample.replaceChildren();
    if ((c.sample || []).length) {
      const table = node('table', null, 'table table-striped scoutro-cards'), tbody = node('tbody');
      for (const s of c.sample) {
        const tr = node('tr');
        for (const v of [s.url, s.predicate, s.value, 'tier ' + s.tier, s.quote]) tr.append(node('td', v == null ? '' : String(v)));
        tbody.append(tr);
      }
      table.append(tbody); sample.append(table);
    }
    $('confirm').disabled = !!c.tooLarge;
    if (c.tooLarge) $('message').textContent = t('asm_too_large');
    pending = Object.assign({}, body, { token: answer.token });
    $('preview').hidden = false;
  }

  function effect(a) {
    const parts = [];
    if (a.withdrawnEvidence) parts.push(a.withdrawnEvidence.toLocaleString(lang) + ' ' + t('asm_withdrawn_rows'));
    if (a.blockedClaims) parts.push(a.blockedClaims.toLocaleString(lang) + ' ' + t('asm_held'));
    return parts.join(', ') || '–';
  }

  async function list() {
    const answer = await call('', 'GET', null, { status: $('status').value });
    const dl = $('counts'); dl.replaceChildren();
    stat(dl, t('asm_count_active'), answer.active); stat(dl, t('asm_count_revoked'), answer.revoked); stat(dl, t('asm_count_review'), answer.reviewNeeded);
    const tbody = $('list').querySelector('tbody'); tbody.replaceChildren();
    if (!(answer.items || []).length) { const tr = node('tr'), td = node('td', t('asm_none')); td.colSpan = 7; tr.append(td); tbody.append(tr); return; }
    for (const a of answer.items) {
      const tr = node('tr'); tr.dataset.id = a.id;
      const scope = Object.entries(a.scope || {})[0] || ['', ''];
      tr.append(node('td', t('asm_' + a.action) + ' · ' + a.id), node('td', scope[0] + ': ' + scope[1]),
        node('td', a.predicate + (a.targetPredicate ? ' → ' + a.targetPredicate : '') + (a.value ? ' = ' + a.value : '') + (a.subject ? ' (' + a.subject + ')' : '')),
        node('td', a.reason + (a.actor ? ' · ' + t('asm_by') + ' ' + a.actor : '')), node('td', effect(a)),
        node('td', t('asm_' + a.status) + (a.reviewNeeded ? ' · ' + t('asm_review') : '')));
      const actions = node('td');
      if (a.status === 'active') {
        const b = node('button', t('asm_revoke'), 'btn btn-default btn-sm'); b.type = 'button';
        b.addEventListener('click', () => revoke(a.id).catch(e => { $('message').textContent = e.message; }));
        actions.append(b);
      }
      tr.append(actions); tbody.append(tr);
    }
  }

  async function revoke(id) {
    if (!window.confirm(t('asm_revoke_question'))) return;
    await call('/' + encodeURIComponent(id) + '/revoke', 'POST', {});
    $('message').textContent = t('asm_revoked_done');
    await list();
  }

  // the identity change preview (WP-F): a dry run for one domain, nothing is written
  const IDP = id => document.getElementById('skg-idp-' + id);
  async function identityPreview() {
    IDP('message').textContent = ''; IDP('result').replaceChildren(); IDP('lists').replaceChildren();
    const body = { domain: IDP('domain').value.trim(), productUrl: IDP('product').checked, providerServiceKey: IDP('provider').checked };
    if (!body.productUrl && !body.providerServiceKey) { IDP('message').textContent = t('idp_choose'); return; }
    const a = await call('', 'POST', body, null, '/scoutro/api/v1/kg/identity-preview');
    const dl = IDP('result'), before = a.baseline || {}, after = a.preview || {};
    stat(dl, t('idp_documents'), a.sample?.documents);
    stat(dl, t('idp_changed'), a.documentsChanged);
    stat(dl, t('idp_entities'), (before.entities ?? 0).toLocaleString(lang) + ' → ' + (after.entities ?? 0).toLocaleString(lang));
    stat(dl, t('idp_services'), (before.services ?? 0).toLocaleString(lang) + ' → ' + (after.services ?? 0).toLocaleString(lang));
    stat(dl, t('idp_merges'), (a.merges || []).length);
    stat(dl, t('idp_conflicts'), (a.conflicts || []).length);
    if (a.llm) stat(dl, t('idp_llm'), a.llm.services + ' (' + a.llm.joinExisting + ' ' + t('idp_llm_existing') + ')');
    for (const [key, items] of [['idp_merges', a.merges], ['idp_conflicts', a.conflicts]]) {
      if (!(items || []).length) continue;
      const ul = node('ul', null, 'skg-evidence-list');
      for (const x of items) ul.append(node('li', String(x)));
      IDP('lists').append(node('h4', t(key)), ul);
    }
    IDP('message').textContent = (a.sample?.truncated ? t('idp_truncated') + ' ' : '') + t('idp_written');
  }

  function bind() {
    if (bound) return; bound = true;
    IDP('form').addEventListener('submit', e => {
      e.preventDefault();
      identityPreview().catch(err => { IDP('message').textContent = err.message; });
    });
    $('form').addEventListener('submit', e => {
      e.preventDefault(); $('message').textContent = ''; $('preview').hidden = true;
      const body = request();
      call('', 'POST', body).then(answer => showPreview(answer, body)).catch(err => { $('message').textContent = err.message; });
    });
    $('confirm').addEventListener('click', () => {
      if (!pending) return;
      const body = pending; pending = null;
      call('', 'POST', body).then(() => { $('preview').hidden = true; $('message').textContent = t('asm_stored'); return list(); })
        .catch(err => { $('message').textContent = err.message; });
    });
    $('status').addEventListener('change', () => list().catch(err => { $('message').textContent = err.message; }));
    const toggle = () => {
      const a = $('action').value;
      $('target').closest('label').hidden = a !== 'reclassify';
      $('quote').closest('label').hidden = a !== 'assert';
      $('tier').closest('label').hidden = a === 'assert';
      if (a === 'assert') $('scope-kind').value = 'source';
      $('scope-kind').disabled = a === 'assert';
    };
    $('action').addEventListener('change', toggle); toggle();
  }

  window.ScoutroAssessments = {
    async show(translate) {
      if (translate) t = translate;
      bind();
      await list();
    }
  };
})();
