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

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { wrapWideTables(); });
  } else {
    wrapWideTables();
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
