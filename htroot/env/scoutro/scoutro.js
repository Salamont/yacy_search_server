/*
@licstart  The following is the entire license notice for the
JavaScript code in this page.

Scoutro UI helpers. Scoutro is an independent community project based on YaCy.
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
 * Session CSRF protection: changing same-origin calls to the Scoutro API carry
 * the session's token in X-Scoutro-CSRF (docs/SCOUTRO_USERS_ACCESS.md, section 5).
 * The token comes from <meta name="scoutro-csrf"> or, on pages rendered without
 * it, once from /scoutro/api/v1/auth/session. It is never stored in localStorage.
 */
(function () {
  'use strict';
  if (typeof window.fetch !== 'function' || window.fetch.scoutroCsrf) return;
  var original = window.fetch;
  var pending = null;
  function metaToken() {
    var m = document.querySelector('meta[name="scoutro-csrf"]');
    var value = m && m.getAttribute('content') ? m.getAttribute('content') : '';
    return /^[A-Za-z0-9_-]{32}$/.test(value) ? value : ''; // a session token; ignores an unresolved template field
  }
  function sessionToken() {
    if (!pending) {
      pending = original.call(window, '/scoutro/api/v1/auth/session', { credentials: 'same-origin', cache: 'no-store' })
        .then(function (r) { return r.ok ? r.json() : {}; })
        .then(function (j) { return (j && j.csrf) || ''; })
        .catch(function () { return ''; });
    }
    return pending;
  }
  function needsToken(input, init) {
    var method = ((init && init.method) || (input && typeof input === 'object' && input.method) || 'GET').toUpperCase();
    if (method === 'GET' || method === 'HEAD' || method === 'OPTIONS') return false;
    try {
      var url = new URL(typeof input === 'string' ? input : (input && input.url) || String(input), window.location.href);
      return url.origin === window.location.origin && url.pathname.indexOf('/scoutro/') === 0;
    } catch (e) {
      return false;
    }
  }
  function withToken(input, init, token) {
    if (!token) return original.call(window, input, init);
    var options = Object.assign({}, init || {});
    var headers = new Headers(options.headers || (input && typeof input === 'object' && input.headers) || {});
    if (!headers.has('X-Scoutro-CSRF')) headers.set('X-Scoutro-CSRF', token);
    options.headers = headers;
    return original.call(window, input, options);
  }
  var wrapped = function (input, init) {
    if (!needsToken(input, init)) return original.call(window, input, init);
    var token = metaToken();
    if (token) return withToken(input, init, token);
    return sessionToken().then(function (t) { return withToken(input, init, t); });
  };
  wrapped.scoutroCsrf = true;
  window.fetch = wrapped;
  window.ScoutroCsrf = { token: function () { return metaToken() || ''; }, refresh: function () { pending = null; return sessionToken(); } };
})();

/*
 * Mobile navigation: on small screens the navbar toggle opens the complete
 * administration menu (#scoutro-adminnav, the YaCy sidebar) as a scrollable
 * panel. Event delegation on document, so this works without waiting for
 * DOMContentLoaded and on every page that includes env/templates/header.template.
 */
(function () {
  'use strict';

  var OPEN_CLASS = 'scoutro-nav-open';

  function isOpen() {
    return document.body && document.body.classList.contains(OPEN_CLASS);
  }

  function setOpen(open) {
    var body = document.body;
    if (!body) {
      return;
    }
    if (open) {
      /* the navbar can wrap to more than one line; place the panel below it */
      var navbar = document.querySelector('.scoutro-adminbar');
      if (navbar) {
        body.style.setProperty('--scoutro-nav-top', Math.round(navbar.getBoundingClientRect().bottom) + 'px');
      }
    }
    body.classList.toggle(OPEN_CLASS, open);
    var toggle = document.getElementById('scoutro-nav-toggle');
    if (toggle) {
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      toggle.classList.toggle('collapsed', !open);
    }
  }

  document.addEventListener('click', function (event) {
    var target = event.target;
    if (!target || !target.closest) {
      return;
    }
    if (target.closest('#scoutro-nav-toggle')) {
      event.preventDefault();
      setOpen(!isOpen());
      return;
    }
    /* close the panel when a navigation entry has been chosen */
    var link = target.closest('#scoutro-adminnav a[href]');
    if (link && isOpen() && !link.hasAttribute('onclick')) {
      setOpen(false);
    }
  });

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && isOpen()) {
      setOpen(false);
    }
  });

  /*
   * Wide tables (monitoring lists, crawl profiles, settings) are wrapped into a
   * horizontal scroll container so that they never widen the page. Only
   * outermost tables are wrapped; nested tables scroll with their parent.
   */
  function wrapWideTables(root) {
    var tables = (root || document).querySelectorAll('table');
    for (var i = 0; i < tables.length; i++) {
      var table = tables[i];
      var parent = table.parentElement;
      if (!parent || parent.classList.contains('scoutro-scroll') || parent.closest('table') ||
          table.closest('.scoutro-adminnav')) {
        continue;
      }
      var wrapper = document.createElement('div');
      wrapper.className = 'scoutro-scroll';
      parent.insertBefore(wrapper, table);
      wrapper.appendChild(table);
    }
  }

  /*
   * Mark the navigation entries of the current page (sidebar and page
   * submenus) so that the theme can highlight them. Sidebar entries match by
   * file name (e.g. Status.html?noforward=). In page submenus a link with a
   * query string only matches the same query (AccessTracker_p.html?page=1).
   */
  function markCurrentLinks() {
    var here = window.location.pathname.replace(/^.*\//, '') || 'index.html';
    var query = window.location.search;
    var view = new URLSearchParams(query).get('view') || '';
    var links = document.querySelectorAll('#scoutro-adminnav a[href], ul.SubMenu a[href]');
    for (var i = 0; i < links.length; i++) {
      var href = links[i].getAttribute('href');
      var q = href.indexOf('?');
      var file = (q < 0 ? href : href.substring(0, q)).replace(/^.*\//, '');
      var linkQuery = q < 0 ? '' : href.substring(q);
      var inSidebar = !!links[i].closest('#scoutro-adminnav');
      /* several sidebar entries open views of one page (?view=...): only the matching view is current */
      var linkView = new URLSearchParams(linkQuery).get('view') || '';
      var sidebarMatch = inSidebar && linkView === view;
      if (file === here && (sidebarMatch || (!inSidebar && (linkQuery === '' || linkQuery === query)))) {
        links[i].classList.add('scoutro-current');
        links[i].setAttribute('aria-current', 'page');
        var advanced = links[i].closest('details');
        if (advanced) advanced.open = true;
      }
    }
  }

  /*
   * Account menu of the header: sign-in with the current page as return
   * target, sign-out of the session (POST, CSRF header from the fetch
   * wrapper above), and a menu that closes on Escape or outside clicks.
   */
  function setUpAccount() {
    var signin = document.getElementById('scoutro-signin');
    if (signin) {
      signin.setAttribute('href', 'scoutro-login.html?next=' + encodeURIComponent(window.location.pathname + window.location.search));
    }
    var signout = document.getElementById('scoutro-signout');
    if (signout) {
      signout.addEventListener('click', function () {
        signout.disabled = true;
        fetch('/scoutro/api/v1/auth/logout', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
          headers: { 'Content-Type': 'application/json' }, body: '{}' })
          .catch(function () { return null; })
          .then(function () { window.location.href = 'scoutro-login.html?signedout=1'; });
      });
    }
    var menu = document.getElementById('scoutro-account-menu');
    if (!menu) return;
    document.addEventListener('click', function (event) {
      if (menu.open && !menu.contains(event.target)) menu.open = false;
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && menu.open) {
        menu.open = false;
        var summary = menu.querySelector('summary');
        if (summary) summary.focus();
      }
    });
  }

  /*
   * Search results are loaded asynchronously, so the server cannot know in
   * advance that a search ends without results. Show the empty state
   * (#scoutro-empty-results in yacysearch.html) once the result feed has
   * finished with a total of 0, and hide it again when results arrive.
   */
  function watchEmptyResults() {
    var empty = document.getElementById('scoutro-empty-results');
    var total = document.getElementById('totalcount');
    if (!empty || !total || !window.MutationObserver) {
      return;
    }
    var feeding = document.getElementById('feedingStatus');
    var started = Date.now();
    var update = function () {
      var count = parseInt((total.textContent || '').replace(/[^0-9]/g, ''), 10) || 0;
      var running = !!feeding && feeding.style.visibility === 'visible';
      var hasResults = !!document.querySelector('.searchresults');
      var settled = Date.now() - started > 1200; /* avoid a flash before the first feed update */
      empty.hidden = !(settled && count === 0 && !running && !hasResults);
    };
    new MutationObserver(update).observe(document.body, {
      subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['style']
    });
    window.setTimeout(update, 1300);
    update();
  }

  function onReady() {
    // Scoutro: label the original cells for responsive cards. Controls, cell
    // indices and save handlers remain the single source of truth.
    function labelCards() {
      document.querySelectorAll('.scoutro-cards, #productionModelsTable, #servicesTable, #availableModelsContainer table').forEach(function (table) {
        table.classList.add('scoutro-cards');
        var head = table.tHead && table.tHead.rows[0];
        if (!head) return;
        Array.from(table.tBodies).forEach(function (body) {
          Array.from(body.rows).forEach(function (row) {
            Array.from(row.cells).forEach(function (cell, index) {
              if (head.cells[index]) {
                var header = head.cells[index];
                cell.dataset.label = Array.from(header.childNodes).filter(function (node) {
                  return node.nodeType === 3;
                }).map(function (node) { return node.textContent; }).join(' ').trim() || header.textContent.trim();
                var control = cell.querySelector('input[type=checkbox]');
                if (control) control.setAttribute('aria-label', cell.dataset.label);
              }
            });
          });
        });
      });
    }
    labelCards();
    if (window.MutationObserver) new MutationObserver(labelCards).observe(document.body, {childList: true, subtree: true});
    wrapWideTables();
    markCurrentLinks();
    setUpAccount();
    watchEmptyResults();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', onReady);
  } else {
    onReady();
  }

  /* leaving the small-screen layout must not keep the page locked */
  if (window.matchMedia) {
    var desktop = window.matchMedia('(min-width: 768px)');
    var onChange = function (mq) {
      if (mq.matches) {
        setOpen(false);
      }
    };
    if (desktop.addEventListener) {
      desktop.addEventListener('change', onChange);
    } else if (desktop.addListener) {
      desktop.addListener(onChange);
    }
  }
})();
