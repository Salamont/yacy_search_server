/*
@licstart  The following is the entire license notice for the
JavaScript code in this page.

Scoutro login page. Scoutro is an independent community project based on YaCy.
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
 * Sign-in flow (docs/SCOUTRO_USERS_ACCESS.md, section 5):
 * 1. already signed in -> continue to the return target;
 * 2. POST /scoutro/api/v1/auth/login; a password change may be required first;
 * 3. confirm with GET /scoutro/api/v1/auth/session that the browser kept the
 *    cookie, then continue. The CSRF token stays in memory only.
 */
(function () {
  'use strict';

  var API = '/scoutro/api/v1/auth/';
  var params = new URLSearchParams(window.location.search);
  var next = safeNext(params.get('next'));
  var csrf = '';
  var typedPassword = '';

  function $(id) { return document.getElementById(id); }

  function label(key, value) {
    var el = document.querySelector('[data-sa-label="' + key + '"]');
    var text = el ? el.textContent : key;
    return value === undefined ? text : text.replace('%1', String(value));
  }

  /** Same rule as WebAuth.safeNext on the server: a path on this server, never another site. */
  function safeNext(value) {
    var fallback = '/scoutro-dashboard.html';
    if (!value || value.length > 1024 || value.charAt(0) !== '/' || value.indexOf('//') === 0
        || value.indexOf('\\') >= 0 || /[\u0000-\u001f\u007f]/.test(value) || value.indexOf('/scoutro-login.html') === 0) {
      return fallback;
    }
    try {
      var u = new URL(value, window.location.origin);
      return u.origin === window.location.origin ? value : fallback;
    } catch (e) {
      return fallback;
    }
  }

  function show(id) {
    ['sa-login', 'sa-change', 'sa-done'].forEach(function (s) { $(s).hidden = s !== id; });
  }

  function message(text, kind) {
    var box = $('sa-message');
    if (!text) {
      box.hidden = true;
      box.textContent = '';
      return;
    }
    box.textContent = text;
    box.className = 'scoutro-auth-message' + (kind ? ' scoutro-auth-message-' + kind : '');
    box.hidden = false;
    if (kind !== 'info') box.focus();
  }

  function busy(button, on, key) {
    button.disabled = on;
    button.setAttribute('aria-busy', on ? 'true' : 'false');
    button.textContent = label(key);
  }

  function errorText(json, status) {
    var code = json && json.error && json.error.code;
    if (code === 'too_many_attempts') {
      var seconds = Number(json.error.details && json.error.details.retryAfter) || 60;
      return label('too_many_attempts', Math.max(1, Math.ceil(seconds / 60)));
    }
    if (code && document.querySelector('[data-sa-label="' + code + '"]')) return label(code);
    if (status === 503) return label('accounts_unavailable');
    return label('generic');
  }

  function request(path, options) {
    var headers = { 'Content-Type': 'application/json' };
    if (csrf) headers['X-Scoutro-CSRF'] = csrf;
    return fetch(API + path, Object.assign({ credentials: 'same-origin', cache: 'no-store', headers: headers }, options))
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (j) { return { status: r.status, ok: r.ok, json: j }; });
      });
  }

  function continueToTarget() {
    $('sa-continue').href = next;
    show('sa-done');
    window.location.replace(next);
  }

  /** The cookie is only useful when the browser sends it back; check before leaving the page. */
  function confirmSession(warning) {
    return request('session', { method: 'GET', headers: {} }).then(function (r) {
      if (r.ok && r.json.authenticated && r.json.user) {
        if (r.json.user.mustChangePassword) {
          startChange(true);
          return;
        }
        if (warning) {
          message(label(warning), 'warn');
          $('sa-continue').href = next;
          show('sa-done');
          return;
        }
        continueToTarget();
      } else {
        message(label('cookie'), 'error');
        show('sa-login');
      }
    }, function () { message(label('network'), 'error'); });
  }

  function startChange(knownPassword) {
    show('sa-change');
    $('sa-current-field').hidden = !!(knownPassword && typedPassword);
    var focus = $('sa-current-field').hidden ? $('sa-new') : $('sa-current');
    focus.focus();
  }

  $('sa-login-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var username = $('sa-username').value.trim();
    var password = $('sa-password').value;
    if (!username || !password) {
      message(label('required'), 'error');
      (username ? $('sa-password') : $('sa-username')).focus();
      return;
    }
    message('');
    var button = $('sa-submit');
    busy(button, true, 'signing_in');
    request('login', { method: 'POST', body: JSON.stringify({ username: username, password: password, next: next }) })
      .then(function (r) {
        busy(button, false, 'sign_in');
        if (!r.ok) {
          message(errorText(r.json, r.status), 'error');
          $('sa-password').value = '';
          $('sa-password').focus();
          return;
        }
        csrf = r.json.csrf || '';
        typedPassword = password;
        $('sa-change-username').value = username;
        $('sa-password').value = '';
        if (r.json.user && r.json.user.mustChangePassword) {
          startChange(true);
          return;
        }
        confirmSession(r.json.warning);
      }, function () {
        busy(button, false, 'sign_in');
        message(label('network'), 'error');
      });
  });

  $('sa-change-form').addEventListener('submit', function (event) {
    event.preventDefault();
    var current = $('sa-current-field').hidden ? typedPassword : $('sa-current').value;
    var fresh = $('sa-new').value;
    if (fresh !== $('sa-repeat').value) {
      message(label('mismatch'), 'error');
      $('sa-repeat').focus();
      return;
    }
    if (fresh.length < 10) {
      message(label('password_too_short'), 'error');
      $('sa-new').focus();
      return;
    }
    message('');
    var button = $('sa-change-submit');
    busy(button, true, 'saving');
    request('password', { method: 'POST', body: JSON.stringify({ currentPassword: current, newPassword: fresh }) })
      .then(function (r) {
        busy(button, false, 'save');
        if (!r.ok) {
          message(errorText(r.json, r.status), 'error');
          if (r.json && r.json.error && r.json.error.code === 'wrong_password') {
            $('sa-current-field').hidden = false;
            $('sa-current').focus();
          }
          return;
        }
        csrf = r.json.csrf || csrf;
        typedPassword = '';
        ['sa-current', 'sa-new', 'sa-repeat'].forEach(function (id) { $(id).value = ''; });
        confirmSession();
      }, function () {
        busy(button, false, 'save');
        message(label('network'), 'error');
      });
  });

  // keep the return target for the Digest fallback
  $('sa-digest').href = 'scoutro-digest.html?next=' + encodeURIComponent(next);

  if (params.get('signedout') === '1') message(label('signed_out'), 'info');
  if (params.get('expired') === '1') message(label('expired'), 'info');

  // already signed in? (also the way back after a password change was required)
  request('session', { method: 'GET', headers: {} }).then(function (r) {
    if (r.ok && r.json.authenticated && r.json.user) {
      csrf = r.json.csrf || '';
      if (r.json.user.mustChangePassword) {
        $('sa-change-username').value = r.json.user.username || '';
        startChange(false);
        return;
      }
      if (params.get('change') !== '1' && params.get('signedout') !== '1') {
        continueToTarget();
        return;
      }
    }
    if (r.ok && r.json.available === false) message(label('accounts_unavailable'), 'warn');
    if ($('sa-login').hidden === false) $('sa-username').focus();
  }, function () { $('sa-username').focus(); });
})();
