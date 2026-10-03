/* Scoutro contributors, GPL-2.0-or-later. Read-only status; worker_busy is not a batch. */
(() => {
  const label = document.getElementById('scoutro-discovery-state'); if (!label) return;
  const text = key => document.querySelector(`[data-scdd-label="${key}"]`)?.textContent || key;
  let busy = false, alive = true, controller = null;
  async function refresh() {
    if (busy) return; busy = true; controller = new AbortController();
    try {
      const response = await fetch('scoutro/api/v1/discovery/status',{credentials:'same-origin',cache:'no-store',signal:controller.signal});
      if (!response.ok) throw new Error(); const status = await response.json();
      if (alive) label.textContent = text(status.automation_status) + (status.running ? ' · ' + text('batch_running') + ' · ' + (status.job_name || '') : '');
    } catch(error) { if (alive && error.name !== 'AbortError') label.textContent = '—'; }
    finally { busy = false; }
  }
  refresh(); const timer = setInterval(refresh,15000);
  window.addEventListener('pagehide',() => { alive=false; controller?.abort(); clearInterval(timer); });
})();
