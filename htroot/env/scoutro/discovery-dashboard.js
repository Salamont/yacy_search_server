/* Scoutro contributors, GPL-2.0-or-later. Read-only summary. */
(() => {
  const label = document.getElementById('scoutro-discovery-state');
  if (!label) return;
  fetch('scoutro/api/v1/discovery/status', { credentials: 'same-origin' }).then(r => {
    if (!r.ok) throw new Error(); return r.json();
  }).then(status => {
    // Numeric summary avoids introducing untranslated dynamic status strings.
    if (status.enabled) label.textContent = `${status.job_count} · ${status.paused ? '⏸' : '▶'}`;
  }).catch(() => { label.textContent = '—'; });
})();
