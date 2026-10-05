/* Scoutro contributors, GPL-2.0-or-later. Read-only knowledge graph tile of the dashboard. */
(() => {
  const state = document.getElementById('scoutro-kg-state'); if (!state) return;
  const text = key => document.querySelector(`[data-sckg-label="${key}"]`)?.textContent || key;
  const set = (id, value) => { document.getElementById(id).textContent = value; };
  let busy = false, alive = true, controller = null;
  async function refresh() {
    if (busy) return; busy = true; controller = new AbortController();
    try {
      const response = await fetch('scoutro/api/v1/kg/status', { credentials: 'same-origin', cache: 'no-store', signal: controller.signal });
      if (!response.ok) throw new Error(); const s = await response.json(); if (!alive) return;
      set('scoutro-kg-state', text(s.state));
      const st = s.storage || {};
      const level = st.level && st.level !== 'ok' ? ' · ' + text('level_' + st.level) : '';
      set('scoutro-kg-budget', st.usedBytes != null && st.budgetBytes ? Math.round(st.usedBytes * 100 / st.budgetBytes) + ' %' + level : '—');
      document.getElementById('scoutro-kg-budget').classList.toggle('scoutro-kg-alert', st.level === 'warning' || st.level === 'brake' || st.level === 'full');
      const pending = s.sync?.lag?.pending, llm = s.llm?.queue?.items;
      set('scoutro-kg-backlog', pending == null ? '—' : String(pending) + (llm ? ' · LLM ' + llm : ''));
      const reasons = (st.reasons || []).map(r => r.code).join(', ');
      set('scoutro-kg-reason', s.state === 'running' && st.growthAllowed === false ? text('paused') + (reasons ? ': ' + reasons : '') : (s.reason || ''));
    } catch (error) { if (alive && error.name !== 'AbortError') set('scoutro-kg-state', '—'); }
    finally { busy = false; }
  }
  refresh(); const timer = setInterval(refresh, 15000);
  window.addEventListener('pagehide', () => { alive = false; controller?.abort(); clearInterval(timer); });
})();
