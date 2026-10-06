/* Scoutro contributors, GPL-2.0-or-later. The collection choice of the Scoutro filters (package 6.1):
   a real select with the collections the signed-in administrator may use, never free text. */
(function () {
  'use strict';
  var PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
  var pending = null;

  // Valid names once each, alphabetically regardless of case.
  function sorted(ids) {
    var seen = {}, out = [];
    (ids || []).forEach(function (id) {
      var name = id == null ? '' : String(id);
      if (PATTERN.test(name) && !seen[name]) { seen[name] = true; out.push(name); }
    });
    return out.sort(function (a, b) {
      var x = a.toLowerCase(), y = b.toLowerCase();
      return x < y ? -1 : x > y ? 1 : a < b ? -1 : a > b ? 1 : 0;
    });
  }

  // The collections of the index, read once per page view. The API answers only an administrator;
  // for anyone else, or when the index cannot be read, the list is empty.
  function load() {
    if (!pending) {
      pending = fetch('/scoutro/api/v1/collections', { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } })
        .then(function (response) { return response.ok ? response.json() : null; })
        .then(function (data) { return sorted(((data && data.collections) || []).map(function (item) { return item && item.id; })); })
        .catch(function () { return []; });
    }
    return pending;
  }

  // Fills a select whose first option (empty value) the page provides: then the names. Selects the
  // wanted name only if it is one of them, else the first option, and ends the loading state.
  function fill(select, ids, wanted) {
    while (select.options.length > 1) select.remove(1);
    ids.forEach(function (id) {
      var option = document.createElement('option');
      option.value = id; option.textContent = id;
      select.appendChild(option);
    });
    select.value = wanted && ids.indexOf(wanted) >= 0 ? wanted : '';
    select.disabled = false;
    select.removeAttribute('aria-busy');
    return select.value;
  }

  window.ScoutroCollections = { PATTERN: PATTERN, sorted: sorted, load: load, fill: fill };
})();
