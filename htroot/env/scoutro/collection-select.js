/* Scoutro contributors, GPL-2.0-or-later. YaCy's own crawl and import forms (package 6.1): their collection field
   becomes the catalog's dropdown, so no collection name is typed; "New collection" creates one (administrator)
   and selects it. Without JavaScript, while no administrator is signed in (the public crawl pages: asking the
   API would only raise a login prompt) or when the catalog cannot be read, YaCy's original field stays, so the
   form and its login keep working. The form still sends the parameter "collection". */
(function () {
  'use strict';
  var dialog = document.getElementById('scoutro-collection-dialog');
  if (!window.ScoutroCollections || !dialog || dialog.dataset.scoutroAdmin !== '1') return;
  var labels = {};
  dialog.querySelectorAll('[data-scoutro-collection-label]').forEach(function (n) { labels[n.dataset.scoutroCollectionLabel] = n.textContent; });
  var inputs = [].slice.call(document.querySelectorAll('input[data-scoutro-collection-select]')).filter(function (input) {
    return !input.disabled; // disabled: YaCy's collections are switched off
  });
  if (!inputs.length) return;
  window.ScoutroCollections.catalog().then(function (catalog) {
    if (catalog.available) inputs.forEach(function (input) { replace(input, catalog.ids); });
  });
  function replace(input, ids) {
    var wanted = input.value;
    var select = document.createElement('select');
    select.id = input.id; select.name = input.name; select.required = true;
    if (input.className) select.className = input.className;
    var first = document.createElement('option');
    first.value = ''; first.textContent = labels.choose || 'Choose a collection';
    select.appendChild(first);
    var button = document.createElement('button');
    button.type = 'button'; button.className = 'btn btn-default btn-sm'; button.hidden = true;
    button.textContent = labels['new'] || 'New collection';
    input.replaceWith(select);
    select.after(' ', button);
    window.ScoutroCollections.fill(select, ids, wanted);
    window.ScoutroCollections.offerCreate(button, function (entry) {
      window.ScoutroCollections.reload().then(function (fresh) { window.ScoutroCollections.fill(select, fresh, entry.id); select.focus(); });
    });
  }
})();
