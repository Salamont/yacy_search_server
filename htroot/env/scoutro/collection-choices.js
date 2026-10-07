/* Scoutro contributors, GPL-2.0-or-later. Agent grants (package 6.1): collections are ticked in a list, never typed.
   "New collection" creates one (administrator) and adds it to the list of the same form, ticked. */
(function () {
  'use strict';
  if (!window.ScoutroCollections) return;
  document.querySelectorAll('[data-scoutro-collection-new]').forEach(function (button) {
    var box = button.parentNode.querySelector('[data-scoutro-collection-choices]');
    if (!box) return;
    window.ScoutroCollections.offerCreate(button, function (entry) {
      var existing = box.querySelector('input[name="col_' + entry.id + '"]');
      if (existing) { existing.checked = true; existing.focus(); return; }
      var label = document.createElement('label'), input = document.createElement('input'), code = document.createElement('code');
      label.className = 'scoutro-check';
      input.type = 'checkbox'; input.name = 'col_' + entry.id; input.checked = true;
      code.textContent = entry.id;
      label.append(input, ' ', code);
      if (entry.name && entry.name !== entry.id) label.append(' ' + entry.name);
      box.append(label, document.createElement('br'));
      input.focus();
    });
  });
})();
