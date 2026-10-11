/*
@licstart  The following is the entire license notice for the
JavaScript code in this page.

Scoutro user management (Settings > Users). Scoutro is an independent community project based on YaCy.
Copyright (C) 2026 Scoutro contributors

This program is free software; you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation; either version 2 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

@licend  The above is the entire license notice
for the JavaScript code in this page.
*/

/*
 * Accounts, access mode, guest access and audit log through
 * /scoutro/api/v1/users, /v1/access and /v1/access/audit (docs/SCOUTRO_USERS_ACCESS.md).
 * The server enforces every rule (last administrator, own account, protected
 * mode); this page only shows the answers.
 */
(function () {
  'use strict';

  var API = '/scoutro/api/v1/';
  var state = { users: [], access: null, collections: [], editing: null, resetFor: null };

  function $(id) { return document.getElementById(id); }

  function label(key, value) {
    var el = document.querySelector('[data-su-label="' + key + '"]');
    var text = el ? el.textContent : key;
    return value === undefined ? text : text.replace('%1', String(value));
  }

  function status(text, kind) {
    var box = $('su-message');
    box.textContent = text || '';
    box.hidden = !text;
    box.className = 'scoutro-status' + (kind ? ' scoutro-status-' + kind : '');
    if (kind === 'error') box.focus();
  }

  function call(path, method, body) {
    var options = { method: method || 'GET', credentials: 'same-origin', cache: 'no-store' };
    if (body !== undefined) {
      options.headers = { 'Content-Type': 'application/json' };
      options.body = JSON.stringify(body);
    }
    return fetch(API + path, options).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, status: r.status, json: j }; });
    }, function () { return { ok: false, status: 0, json: {} }; });
  }

  function errorText(r) {
    var e = r.json && r.json.error;
    if (e && e.code === 'invalid_request' && e.details && e.details.field === 'collections') return label('collections_required');
    if (e && e.code && document.querySelector('[data-su-label="' + e.code + '"]')) return label(e.code);
    return label('failed') + (e && e.message ? ' ' + e.message : '');
  }

  function when(ms) {
    if (!ms) return label('never');
    try {
      return new Date(ms).toLocaleString(document.documentElement.lang || undefined, { dateStyle: 'medium', timeStyle: 'short' });
    } catch (e) {
      return new Date(ms).toISOString();
    }
  }

  function confirmDialog(titleKey, textKey, value) {
    var dialog = $('su-confirm');
    $('su-confirm-title').textContent = label(titleKey);
    $('su-confirm-text').textContent = label(textKey, value);
    return new Promise(function (resolve) {
      dialog.addEventListener('close', function done() {
        dialog.removeEventListener('close', done);
        resolve(dialog.returnValue === 'ok');
      });
      dialog.returnValue = '';
      dialog.showModal();
    });
  }

  // ---------------------------------------------------------------- access mode

  function setSwitch(stateId, buttonId, on) {
    $(stateId).textContent = label(on ? 'on' : 'off');
    $(stateId).classList.toggle('is-locked', !on);
    $(buttonId).textContent = label(on ? 'switch_off' : 'switch_on');
  }

  function renderAccess(a) {
    state.access = a;
    setSwitch('su-protected-state', 'su-protected-toggle', a.protected);
    setSwitch('su-guest-state', 'su-guest-toggle', a.guest);
    $('su-guest-toggle').disabled = !a.protected && !a.guest;
    setSwitch('su-builtin-state', 'su-builtin-toggle', a.builtinAdminLogin);
    $('su-builtin-name').textContent = a.builtinAdminName || 'admin';
    var guestList = $('su-guest-collections');
    guestList.hidden = !a.guest;
    $('su-guest-save').hidden = !a.guest;
    fillCollections(guestList, 'su-guest-c', a.guestCollections || []);
    var warnings = $('su-warnings');
    warnings.textContent = '';
    (a.warnings || []).forEach(function (w) {
      var li = document.createElement('li');
      li.textContent = label('warn_' + w);
      warnings.appendChild(li);
    });
    warnings.hidden = !(a.warnings && a.warnings.length);
    $('su-mode').hidden = false;
  }

  function patchAccess(body, okText) {
    return call('access', 'PATCH', body).then(function (r) {
      if (!r.ok) {
        status(errorText(r), 'error');
        return;
      }
      renderAccess(r.json);
      status(okText || label('saved'), 'ok');
    });
  }

  $('su-protected-toggle').addEventListener('click', function () {
    var on = !state.access.protected;
    confirmDialog(on ? 'confirm_protected_on_title' : 'confirm_protected_off_title', on ? 'confirm_protected_on' : 'confirm_protected_off')
      .then(function (ok) { if (ok) patchAccess({ protected: on }); });
  });
  $('su-guest-toggle').addEventListener('click', function () {
    var on = !state.access.guest;
    var go = on ? confirmDialog('confirm_guest_on_title', 'confirm_guest_on') : Promise.resolve(true);
    go.then(function (ok) { if (ok) patchAccess({ guest: on }); });
  });
  $('su-guest-save').addEventListener('click', function () {
    patchAccess({ guestCollections: checked('su-guest-collections') });
  });
  $('su-builtin-toggle').addEventListener('click', function () {
    var on = !state.access.builtinAdminLogin;
    var go = on ? Promise.resolve(true) : confirmDialog('confirm_builtin_off_title', 'confirm_builtin_off');
    go.then(function (ok) { if (ok) patchAccess({ builtinAdminLogin: on }); });
  });

  // ---------------------------------------------------------------- collections

  function fillCollections(container, prefix, selected) {
    container.textContent = '';
    var ids = state.collections.slice();
    selected.forEach(function (c) { if (ids.indexOf(c) < 0) ids.push(c); });
    if (!ids.length) {
      var p = document.createElement('p');
      p.className = 'scoutro-hint';
      p.textContent = label('no_collections_available');
      container.appendChild(p);
      return;
    }
    ids.forEach(function (id, i) {
      var row = document.createElement('label');
      row.className = 'scoutro-check';
      var box = document.createElement('input');
      box.type = 'checkbox';
      box.value = id;
      box.id = prefix + i;
      box.checked = selected.indexOf(id) >= 0;
      row.appendChild(box);
      row.appendChild(document.createTextNode(' ' + id));
      container.appendChild(row);
    });
  }

  function checked(containerId) {
    return Array.prototype.map.call($(containerId).querySelectorAll('input[type=checkbox]:checked'), function (b) { return b.value; });
  }

  // ---------------------------------------------------------------- accounts

  function button(text, handler, danger) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'btn btn-default btn-sm' + (danger ? ' scoutro-danger' : '');
    b.textContent = text;
    b.addEventListener('click', handler);
    return b;
  }

  function renderUsers(users) {
    state.users = users;
    var body = $('su-table').tBodies[0];
    body.textContent = '';
    $('su-empty').hidden = users.length > 0;
    users.forEach(function (u) {
      var row = body.insertRow();
      var name = row.insertCell();
      var strong = document.createElement('strong');
      strong.textContent = u.displayName || u.username;
      name.appendChild(strong);
      name.appendChild(document.createElement('br'));
      name.appendChild(document.createTextNode(u.username));
      var role = row.insertCell();
      var badge = document.createElement('span');
      badge.className = 'scoutro-badge-role';
      badge.textContent = label('role_' + u.role);
      role.appendChild(badge);
      row.insertCell().textContent = u.allCollections ? label('all_collections') : (u.collections || []).join(', ');
      row.insertCell().textContent = u.export ? label('yes') : label('no');
      var st = row.insertCell();
      var sb = document.createElement('span');
      sb.className = 'scoutro-badge-state' + (u.status === 'locked' ? ' is-locked' : u.mustChangePassword ? ' is-change' : '');
      sb.textContent = u.status === 'locked' ? label('locked') : u.mustChangePassword ? label('must_change') : label('active');
      st.appendChild(sb);
      row.insertCell().textContent = when(u.lastLoginAt);
      row.insertCell().textContent = String(u.sessions || 0);
      var actions = row.insertCell();
      actions.className = 'scoutro-row-actions';
      actions.appendChild(button(label('edit'), function () { openEditor(u); }));
      actions.appendChild(button(label(u.status === 'locked' ? 'unlock' : 'lock'), function () { toggleLock(u); }));
      actions.appendChild(button(label('reset'), function () { openReset(u); }));
      if (u.sessions) actions.appendChild(button(label('end_sessions'), function () { endSessions(u); }));
      actions.appendChild(button(label('remove'), function () { removeUser(u); }, true));
    });
    $('su-list').hidden = false;
  }

  function loadUsers() {
    return call('users').then(function (r) {
      if (!r.ok) {
        status(errorText(r), 'error');
        return;
      }
      renderAccess(r.json.access);
      renderUsers(r.json.users || []);
    });
  }

  function toggleLock(u) {
    var lock = u.status !== 'locked';
    var go = lock ? confirmDialog('confirm_lock_title', 'confirm_lock', u.username) : Promise.resolve(true);
    go.then(function (ok) {
      if (!ok) return;
      call('users/' + encodeURIComponent(u.username), 'PATCH', { status: lock ? 'locked' : 'active' }).then(function (r) {
        status(r.ok ? label(lock ? 'locked_done' : 'unlocked_done') : errorText(r), r.ok ? 'ok' : 'error');
        loadUsers();
      });
    });
  }

  function endSessions(u) {
    call('users/' + encodeURIComponent(u.username) + '/sessions/revoke', 'POST', {}).then(function (r) {
      status(r.ok ? label('sessions_ended', r.json.sessionsEnded || 0) : errorText(r), r.ok ? 'ok' : 'error');
      loadUsers();
    });
  }

  function removeUser(u) {
    confirmDialog('confirm_remove_title', 'confirm_remove', u.username).then(function (ok) {
      if (!ok) return;
      call('users/' + encodeURIComponent(u.username), 'DELETE', {}).then(function (r) {
        status(r.ok ? label('removed') : errorText(r), r.ok ? 'ok' : 'error');
        loadUsers();
      });
    });
  }

  /** A random temporary password from the browser's cryptographic generator. */
  function generatePassword() {
    var alphabet = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    var bytes = new Uint32Array(16);
    window.crypto.getRandomValues(bytes);
    var out = '';
    for (var i = 0; i < bytes.length; i++) {
      out += alphabet.charAt(bytes[i] % alphabet.length);
      if (i % 4 === 3 && i < bytes.length - 1) out += '-';
    }
    return out;
  }

  function editorMessage(text) {
    var box = $('su-editor-message');
    box.textContent = text || '';
    box.hidden = !text;
  }

  function syncScopeFields() {
    var admin = $('su-role').value === 'administrator';
    $('su-scope').disabled = admin;
    $('su-export').disabled = admin;
    if (admin) {
      $('su-scope-all').checked = true;
      $('su-export').checked = true;
    }
    $('su-collections').hidden = $('su-scope-all').checked;
  }

  function openEditor(u) {
    state.editing = u || null;
    editorMessage('');
    $('su-editor-title').textContent = label(u ? 'edit_title' : 'new_title');
    $('su-username').value = u ? u.username : '';
    $('su-username').disabled = !!u;
    $('su-displayname').value = u ? (u.displayName === u.username ? '' : u.displayName) : '';
    $('su-role').value = u ? u.role : 'research';
    $('su-scope-all').checked = !!(u && u.allCollections);
    $('su-scope-some').checked = !(u && u.allCollections);
    fillCollections($('su-collections'), 'su-c', u ? (u.collections || []) : []);
    $('su-export').checked = !!(u && u.export);
    $('su-password-block').hidden = !!u;
    $('su-password').value = u ? '' : generatePassword();
    syncScopeFields();
    $('su-editor').showModal();
    (u ? $('su-displayname') : $('su-username')).focus();
  }

  $('su-role').addEventListener('change', syncScopeFields);
  $('su-scope-all').addEventListener('change', syncScopeFields);
  $('su-scope-some').addEventListener('change', syncScopeFields);
  $('su-generate').addEventListener('click', function () { $('su-password').value = generatePassword(); });
  $('su-editor-cancel').addEventListener('click', function () { $('su-editor').close(); });
  $('su-new').addEventListener('click', function () { openEditor(null); });

  $('su-editor-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var role = $('su-role').value;
    var all = role === 'administrator' || $('su-scope-all').checked;
    var body = {
      displayName: $('su-displayname').value.trim(),
      role: role,
      allCollections: all,
      collections: all ? [] : checked('su-collections'),
      export: role === 'administrator' || $('su-export').checked
    };
    var save = $('su-editor-save');
    save.disabled = true;
    var request;
    if (state.editing) {
      request = call('users/' + encodeURIComponent(state.editing.username), 'PATCH', body);
    } else {
      body.username = $('su-username').value.trim().toLowerCase();
      body.password = $('su-password').value;
      body.mustChangePassword = true;
      request = call('users', 'POST', body);
    }
    request.then(function (r) {
      save.disabled = false;
      if (!r.ok) {
        editorMessage(errorText(r));
        var field = r.json && r.json.error && r.json.error.details && r.json.error.details.field;
        var target = field === 'username' ? $('su-username') : field === 'password' ? $('su-password') : null;
        if (target) target.focus();
        return;
      }
      $('su-editor').close();
      var text = label(state.editing ? 'updated' : 'created');
      if (r.json.sessionsEnded) text += ' ' + label('sessions_ended', r.json.sessionsEnded);
      status(text, 'ok');
      loadUsers();
    });
  });

  function openReset(u) {
    state.resetFor = u;
    $('su-reset-name').textContent = u.username;
    $('su-reset-secret').textContent = generatePassword();
    $('su-reset-message').hidden = true;
    $('su-reset').showModal();
  }
  $('su-reset-cancel').addEventListener('click', function () { $('su-reset').close(); });
  $('su-reset-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var u = state.resetFor;
    call('users/' + encodeURIComponent(u.username) + '/password', 'POST',
      { password: $('su-reset-secret').textContent, mustChangePassword: true }).then(function (r) {
      if (!r.ok) {
        $('su-reset-message').textContent = errorText(r);
        $('su-reset-message').hidden = false;
        return;
      }
      $('su-reset').close();
      status(label('reset_done'), 'ok');
      loadUsers();
    });
  });

  // ---------------------------------------------------------------- audit

  function loadAudit() {
    var who = $('su-audit-who').value.trim();
    return call('access/audit?limit=100' + (who ? '&who=' + encodeURIComponent(who) : '')).then(function (r) {
      var body = $('su-audit-table').tBodies[0];
      body.textContent = '';
      if (!r.ok) return;
      var entries = r.json.entries || [];
      if (!entries.length) {
        var cell = body.insertRow().insertCell();
        cell.colSpan = 6;
        cell.textContent = label('audit_empty');
      }
      entries.forEach(function (e) {
        var row = body.insertRow();
        [when(e.ts), e.actor, e.action, e.target, label('result_' + e.result), e.reason].forEach(function (text) {
          row.insertCell().textContent = text || '';
        });
      });
      $('su-audit').hidden = false;
    });
  }
  $('su-audit-filter').addEventListener('submit', function (event) {
    event.preventDefault();
    loadAudit();
  });

  // ---------------------------------------------------------------- start

  status(label('loading'));
  var catalog = window.ScoutroCollections ? window.ScoutroCollections.catalog() : Promise.resolve({ ids: [] });
  catalog.then(function (c) {
    state.collections = c.ids || [];
    return loadUsers();
  }).then(function () {
    if (!$('su-list').hidden) status('');
    return loadAudit();
  }, function () { status(label('error'), 'error'); });
})();
