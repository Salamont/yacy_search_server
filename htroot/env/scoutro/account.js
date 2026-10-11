/*
@licstart  The following is the entire license notice for the
JavaScript code in this page.

Scoutro "My account" page. Scoutro is an independent community project based on YaCy.
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

/* Profile, password change and own sessions through /scoutro/api/v1/auth/* (docs/SCOUTRO_USERS_ACCESS.md). */
(function () {
  'use strict';

  var API = '/scoutro/api/v1/auth/';
  var session = null;

  function $(id) { return document.getElementById(id); }

  function label(key, value) {
    var el = document.querySelector('[data-sac-label="' + key + '"]');
    var text = el ? el.textContent : key;
    return value === undefined ? text : text.replace('%1', String(value));
  }

  function status(text, kind) {
    var box = $('sac-message');
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
    });
  }

  function errorText(r) {
    var code = r.json && r.json.error && r.json.error.code;
    if (code && document.querySelector('[data-sac-label="' + code + '"]')) return label(code);
    return label('failed');
  }

  function when(ms) {
    if (!ms) return '—';
    try {
      return new Date(ms).toLocaleString(document.documentElement.lang || undefined, { dateStyle: 'medium', timeStyle: 'short' });
    } catch (e) {
      return new Date(ms).toISOString();
    }
  }

  function renderProfile(s) {
    var u = s.user;
    $('sac-username').textContent = u.username;
    $('sac-displayname').textContent = u.displayName || u.username;
    $('sac-role').textContent = label('role_' + u.role);
    $('sac-collections').textContent = u.allCollections ? label('all_collections')
      : (u.collections && u.collections.length ? u.collections.join(', ') : label('no_collections'));
    $('sac-export').textContent = u.export ? label('yes') : label('no');
    $('sac-method').textContent = u.digest ? label('method_digest') : u.builtin ? label('method_builtin') : label('method_session');
    $('sac-password-username').value = u.username;
    $('sac-profile').hidden = false;
    var account = s.kind === 'account';
    $('sac-password').hidden = !account;
    $('sac-builtin').hidden = account;
    $('sac-sessions').hidden = !(account || s.kind === 'builtin');
    $('sac-idle').textContent = String(s.idleMinutes || 30);
  }

  function renderSessions() {
    return call('sessions').then(function (r) {
      var body = $('sac-session-table').tBodies[0];
      body.textContent = '';
      if (!r.ok) return;
      (r.json.sessions || []).forEach(function (s) {
        var row = body.insertRow();
        [when(s.createdAt), when(s.lastSeenAt), s.client || '—', s.agent || '—'].forEach(function (text) {
          row.insertCell().textContent = text;
        });
        var action = row.insertCell();
        if (s.current) {
          action.textContent = label('current');
        } else {
          var button = document.createElement('button');
          button.type = 'button';
          button.className = 'btn btn-default btn-sm';
          button.textContent = label('end');
          button.addEventListener('click', function () {
            button.disabled = true;
            call('sessions/revoke', 'POST', { id: s.id }).then(function (x) {
              status(x.ok ? label('ended') : errorText(x), x.ok ? 'ok' : 'error');
              renderSessions();
            });
          });
          action.appendChild(button);
        }
      });
    });
  }

  function load() {
    status(label('loading'));
    call('session').then(function (r) {
      if (!r.ok || !r.json.authenticated || !r.json.user) {
        status(label('error'), 'error');
        return;
      }
      session = r.json;
      renderProfile(session);
      status('');
      if (!$('sac-sessions').hidden) renderSessions();
    }, function () { status(label('error'), 'error'); });
  }

  $('sac-password-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var fresh = $('sac-new').value;
    if (fresh !== $('sac-repeat').value) {
      status(label('mismatch'), 'error');
      $('sac-repeat').focus();
      return;
    }
    if (fresh.length < 10) {
      status(label('password_too_short'), 'error');
      $('sac-new').focus();
      return;
    }
    var button = $('sac-password-submit');
    button.disabled = true;
    button.textContent = label('saving');
    call('password', 'POST', { currentPassword: $('sac-current').value, newPassword: fresh }).then(function (r) {
      button.disabled = false;
      button.textContent = label('save');
      if (!r.ok) {
        status(errorText(r), 'error');
        return;
      }
      ['sac-current', 'sac-new', 'sac-repeat'].forEach(function (id) { $(id).value = ''; });
      // the session was renewed: later calls need the new CSRF token
      var meta = document.querySelector('meta[name="scoutro-csrf"]');
      if (meta && r.json.csrf) meta.setAttribute('content', r.json.csrf);
      status(label('saved'), 'ok');
      renderSessions();
    }, function () {
      button.disabled = false;
      button.textContent = label('save');
      status(label('failed'), 'error');
    });
  });

  $('sac-end-others').addEventListener('click', function () {
    call('sessions/revoke', 'POST', { others: true }).then(function (r) {
      status(r.ok ? label('ended_others', r.json.ended || 0) : errorText(r), r.ok ? 'ok' : 'error');
      renderSessions();
    });
  });

  $('sac-signout').addEventListener('click', function () {
    call('logout', 'POST', {}).then(function () { window.location.href = 'scoutro-login.html?signedout=1'; },
      function () { window.location.href = 'scoutro-login.html?signedout=1'; });
  });

  load();
})();
