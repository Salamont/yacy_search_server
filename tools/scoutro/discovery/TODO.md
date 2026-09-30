# scoutro-discovery — open points for the next version

Not implemented. Nothing here may be started before the current
discovery/classification state (branch `feat/discovery-classification`) is
merged into `main` **and** tested on Olares.

## Notifications (next version)

Goal: operators (and agents) learn about relevant events and problems of
discovery, crawling, classification and security — without a flood of
messages.

### To be decided before implementation

1. **Which events trigger a notification.** Candidates, to be confirmed:
   - discovery/crawl runs: run finished (summary only), run aborted, `busy`
     (another run active), state unreadable (`state_unreadable`);
   - crawl/index: crawl rejected by YaCy, repeated crawl errors for a domain
     (back-off reached), index not growing, disk/index limits;
   - classification: LLM not configured, unreachable, timeouts, invalid model
     output (fail-safe UNSURE) above a threshold, high share of UNSURE,
     `prompt_injection_suspected`, `conflicting_signals`, outdated
     `criteria_version`;
   - security: SSRF blocks (private/loopback targets), redirect/robots
     anomalies, repeated admin authentication failures on the Scoutro API;
   - maintenance: legacy `prospect-*` collections still in use, pending
     re-classification after a criteria change.
2. **Where the notices appear:** Scoutro UI (e.g. Status page / a notices
   area in the administration), `scoutro-discovery status` / JSON output for
   agents, or both.
3. **Only UI notices, or also Olares/system notifications:** whether Olares
   offers an app notification interface that a community app may use, what it
   requires (permissions in `OlaresManifest.yaml`), and whether this is wanted
   at all. Until clarified: UI/JSON only.
4. **What warnings look like** for crawl, index, LLM and security errors:
   severity levels (info / warning / error / security), a stable
   machine-readable format (code, severity, profile, domain, count,
   first/last seen, recommended action) in addition to the human text; no
   secrets, no raw web content in notices (web content stays untrusted data).
5. **No notification flood:** aggregation per run and per error code,
   de-duplication (same code + profile/domain only once until resolved),
   rate limits / quiet periods, thresholds instead of single events,
   daily summary instead of per-domain messages, explicit acknowledge/resolve.

### Constraints that stay valid

- Notifications never change crawl, indexing or classification behaviour
  (read-only reporting), and never delete anything.
- No new external service and no provider hard-coded; any Olares integration
  goes through the Olares package after review.
- Secrets (admin password, LLM API key) never appear in notices or logs.
