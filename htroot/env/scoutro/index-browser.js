/* Scoutro contributors, GPL-2.0-or-later. Read-only browser using authorized API actions. */
(function () {
  'use strict';
  var form = document.getElementById('scoutro-index-form');
  if (!form) return;
  var query = document.getElementById('scoutro-index-query');
  var collection = document.getElementById('scoutro-index-collection');
  var table = document.getElementById('scoutro-index-table');
  var error = document.getElementById('scoutro-index-error');
  var active = document.getElementById('scoutro-index-active');
  var previous = document.getElementById('scoutro-index-prev');
  var next = document.getElementById('scoutro-index-next');
  var offset = 0, limit = 50, generation = 0;
  function message(id) { return document.getElementById(id).textContent; }
  function cell(row, value, index) {
    var td = row.insertCell();
    td.dataset.label = table.tHead.rows[0].cells[index].textContent;
    td.textContent = value == null ? '—' : value;
    return td;
  }
  async function load() {
    var current = ++generation;
    var name = collection.value.trim();
    error.hidden = true;
    table.tBodies[0].replaceChildren();
    previous.disabled = next.disabled = true;
    active.hidden = !name;
    active.querySelector('span').textContent = name;
    document.getElementById('scoutro-index-empty').hidden = true;
    document.getElementById('scoutro-index-total').textContent = '—';
    var params = new URLSearchParams({ q: query.value.trim(), collection: name, offset: offset, limit: limit });
    // Preserve filter state in the page URL, including collection during host searches.
    history.replaceState(null, '', 'IndexBrowser_p.html?' + params.toString());
    if (name && !/^[A-Za-z0-9_-]{1,64}$/.test(name)) {
      error.textContent = message('scoutro-index-invalid'); error.hidden = false; return;
    }
    try {
      var response = await fetch('/scoutro/api/v1/index/browse?' + params.toString(), { credentials: 'same-origin' });
      if (!response.ok) throw new Error('HTTP ' + response.status);
      var data = await response.json();
      if (current !== generation) return;
      data.documents.forEach(function (doc) {
        var row = table.tBodies[0].insertRow();
        var urlCell = cell(row, doc.url, 0);
        if (/^https?:\/\//i.test(doc.url)) {
          var link = document.createElement('a'); link.href = doc.url;
          link.textContent = doc.url; link.target = '_blank'; link.rel = 'noopener noreferrer';
          urlCell.replaceChildren(link);
        }
        cell(row, doc.host, 1); cell(row, doc.collections.join(', '), 2); cell(row, doc.httpStatus, 3);
      });
      document.getElementById('scoutro-index-total').textContent = String(data.total);
      document.getElementById('scoutro-index-empty').hidden = data.total !== 0;
      previous.disabled = offset === 0;
      next.disabled = offset + limit >= data.total || offset + limit > 10000;
    } catch (failure) {
      if (current !== generation) return;
      error.textContent = message('scoutro-index-unavailable') + ' (' + failure.message + ')';
      error.hidden = false;
    }
  }
  form.addEventListener('submit', function (event) { event.preventDefault(); offset = 0; load(); });
  document.getElementById('scoutro-index-reset').addEventListener('click', function () { query.value = collection.value = ''; offset = 0; load(); });
  document.getElementById('scoutro-index-clear').addEventListener('click', function () { collection.value = ''; offset = 0; load(); });
  previous.addEventListener('click', function () { offset = Math.max(0, offset - limit); load(); });
  next.addEventListener('click', function () { offset += limit; load(); });
  fetch('/scoutro/api/v1/collections', { credentials: 'same-origin' }).then(function (response) {
    if (!response.ok) return null; return response.json();
  }).then(function (data) {
    if (!data) return;
    var choices = document.getElementById('scoutro-index-choices');
    data.collections.forEach(function (item) { var option = document.createElement('option'); option.value = item.id; choices.appendChild(option); });
  }).catch(function () { /* Suggestions are optional; filter errors remain visible. */ });
  load();
})();
