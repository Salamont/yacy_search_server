# Discovery Automation

Administrator-only page: `/ScoutroDiscovery_p.html`. The shared Scoutro navigation and dashboard link here. Mobile uses the same navigation. V1 is **disabled after installation**; viewing this page does not enable a scheduler or create candidate state.

## Configuration and jobs

Configure a persistent runtime directory, in order: `SCOUTRO_CONFIG_ROOT`, YaCy setting `scoutro.discovery.configRoot`, `DATA/SCOUTRO/config`. Automation never uses repository examples as a fallback. `profiles.json.profiles` owns profile identity and explicit collection. `profiles.conf` supplies Freeworld search terms; `osm_profiles.json` supplies OSM rules. Classification metadata stays in runtime config but is not executed by automation.

The editor loads profiles, sources and regions from the runtime catalog. OSM regions come from `osm_regions.txt`, Freeworld search texts from `regions.txt`; these are separate lists. V1 uses the existing Germany Geofabrik provider. Source `mode=all` follows the current source catalog. Removed references are blocked, never substituted. A batch uses an immutable fingerprinted snapshot. Update the runtime files as one coordinated change while automation is paused; a double read detects concurrent byte changes but cannot prove a multi-file deployment transaction.

Job settings: name, enabled/paused, profile, sources with mode and regions, candidate scope, discovery refresh, batch size/pages/depth/seed delay, processing fresh/retry/recrawl and schedule. Collection is resolved from the profile. No paths, secrets, `force`, shell commands or Classification settings are accepted. Batch size limits dispatch, never the number of discovered candidates persisted. Retry includes existing problematic/cooldown entries when eligible; it does not override cooldown. **Also retry after an unsuccessful crawl result** (`processing.outcome_retry`, off by default, needs Retry) reads the recorded result of the crawl a job started (crawl report): `not_indexed` (no page indexed) or `not_reloaded` (nothing reloaded by the crawl) make the host a retry with the normal retry backoff, which doubles from two hours up to the recrawl interval; a successful result resets the attempt count. Only crawls started in the last three days and matching the recorded crawl id are considered. Only the existing retry fields of the state change (`status`, `error_class`, `attempts`, `last_error`, `next_attempt`); results are not copied into the state. Recrawl follows the Python profile state, not YaCy's host-wide Autocrawl.

`source_regions` processes pairs with matching recorded provenance. `profile_backlog` processes the whole profile, including old entries whose provenance is unknown. A drain-only profile backlog job may omit sources if `discovery.replenish=false`.

## Operation

Explicit Enable installs/enables exactly one WorkTables heartbeat, every 10 minutes. Jobs have separate intervals of at least 10 minutes, oldest due first, then longest not served. Missed periods produce one due execution, not a catch-up storm. A batch that ends at once with nothing to process (`processed=0`, no fresh candidates in its scope) is recorded normally, and the same heartbeat then starts the next due job, up to five empty batches per heartbeat; a batch with work, an error, a review or a capacity/pause stop ends the heartbeat. WorkTables remains the persistent clock; job definitions/run state are in `DATA/SETTINGS/scoutro-discovery-jobs.json` (schema 1, atomic fsync+rename, numeric revision).

Pause/Disable prevents further seeds; accepted YaCy crawls continue. A job Run once requires a unique `request_id`, global automation enabled and resumed, and the job unpaused; it also works for a disabled job. Run once requests use the same serial admission/recovery path and do not bypass backpressure. Run once may be deferred until the existing batch has completed. Reusing its most recent request ID does not enqueue a second execution. The revision changes with background progress too; reload after HTTP 409.

No heartbeat is silently recreated on restart/read if it was removed or disabled in YaCy Automation. Duplicates block dispatch; explicit Enable repairs owned duplicates. Pause is separate from the existing CLI's pause flag. Resume does not override a CLI pause.

The HTTP tick only wakes a bounded coordinator and returns. The coordinator performs one provider turn, persists every accepted candidate, and dispatches up to the batch limit with admission before each seed. OSM handles one selected extract per turn, rotates a job cursor and refreshes the discovery interval after the full region cycle. Freeworld handles up to five selected search regions per turn with a job cursor. PBF caches live in the state directory, outside Git; successful downloads are atomic, capped at 5 GiB, time-bounded, refreshed after 24 hours. Extraction uses streamed GeoJSON sequence; no whole extract is loaded as JSON. Source failures are errors, not empty successful discovery results.

The existing Python Multi-Profile State V2 remains the sole candidate-state owner. Missing status means fresh. All profiles and Classification values survive. Configure its root with `SCOUTRO_DISCOVERY_DIR`, `scoutro.discovery.stateRoot`, or `DATA/SCOUTRO/discovery`. CLI `--workdir`/`--config-dir` still work. To adopt existing state, point this root to its persistent directory; back up first. The previously known fresh counts were not fully persisted: rerun discovery once to fill the backlog. Already crawled pairs remain ineligible unless retry/recrawl explicitly applies. There is no reset or invented source-region migration.

## Recovery and boundaries

Before Crawl API dispatch, the Java run ledger fsyncs a unique intent and marker. Confirmed crawl ID follows; Python saves the accepted result before acknowledging it. Lost replies are reconciled using the marker reported by the existing Crawl Profiles API. Unknown or removed profiles block in `needs_review` until an administrator decides (below); there is no blind resubmission or exactly-once claim. Restart during discovery with no starts ends the reserved run without replaying it; the next scheduled run replenishes again. Corrupt stores/state remain untouched and block work. Changing the state root with an open run blocks reconciliation.

For the crawl report, finished Discovery crawls and precheck refusals (DNS, robots.txt, blocked address, robots.txt 5xx) are recorded per host and collection in the `scoutro_domains` table; this never changes a Discovery decision unless a job enables `processing.outcome_retry`, and nothing is added to the Discovery state file. See [Crawl Report](../docs/SCOUTRO_CRAWL_REPORT.md).

### Unconfirmed crawl start (`needs_review`, `submitted_unknown`)

A batch whose crawl start YaCy did not confirm, and whose start marker no crawl profile carries, stays in `needs_review` and blocks every further Discovery batch while it waits for a decision. A missing profile or a missing confirmation does not prove that the start was never accepted (the profile may have been deleted, or the reply lost during a stop), so the start is never recorded as "not submitted" and never repeated.

**Grace period (since 0.11.5).** Discovery counts every reconciliation that finds no profile for the start (`review[].reconciled`). After two such checks, and at the earliest 20 minutes after the start, the coordinator defers the start itself: the same decision an administrator takes below, with the crawl profiles read again right before it, the candidate held first, and `decision.by = system:reconcile`, `decision.automatic = true`. The batch then leaves review in the same wakeup, waits for its accepted crawls and completes, and the other jobs continue. A hold that cannot be confirmed (for example an unreadable candidate state) postpones the automatic deferral to the next heartbeat (`review[].auto_defer_error`); the batch stays in review meanwhile. An unconfirmed start therefore blocks the other jobs for about two heartbeats at most, and never causes a second crawl.

The page shows such a start in **Unconfirmed crawl start** (administrators only): the website, the time it was submitted, how often it was checked, when Discovery would defer it itself, and **Defer start (no new crawl, no retry, outcome stays unknown)**. Before you use it:

1. Look in the **Crawler Monitor** (and the active and finished crawl profiles) for a crawl of that website around the submitted time. If one carries the start marker, Discovery records it as accepted by itself at its next wakeup; no decision is needed.
2. If there is none, defer the start. Discovery then
   - reads the crawl profiles again; if a profile with the marker turned up, nothing is deferred (`409 attempt_found`) and the start is recorded as accepted;
   - holds the candidate in its state (`automation_attempt.state = deferred_unknown`, with the batch, the submitted time, who decided and when): this website is **never started again by itself**, also not with retry, recrawl or the command line `--force`;
   - reads the crawl profiles once more and records the decision with the attempt (`deferred`, outcome `unknown`, who, when, how many profiles were checked) and in the job's `runtime.held_attempts` (newest 100; the candidate's hold stays in the state regardless);
   - keeps every accepted crawl of the batch: the batch waits for them as usual, completes, and the other jobs continue.
3. If a crawl with that marker shows up while the batch still waits for its crawls, the start is recorded as accepted after all (`decision.superseded`, `held_attempts[].state = accepted_late`) and the candidate gets the usual crawled state. A crawl that shows up after the batch completed starts nothing; the candidate stays held.

The decision is never a "not submitted" and never a retry. A deferred website is crawled again only through a deliberate crawl start by an administrator. The decision needs the store revision (`If-Match`), the id of the open batch and of the attempt; it waits up to 20 seconds for a running wakeup or state refresh and is refused only if that still holds the coordinator (`worker_busy`); it is refused for a changed revision (`revision_conflict`; the page reloads and repeats once), another batch (`stale_run`), a batch not in review (`run_not_in_review`), a start with a known outcome (`attempt_not_unresolved`) or one already deferred (`already_deferred`). The candidate is held before the decision is recorded; a refused or failed decision leaves the batch unchanged and can be repeated. The job's **Deferred starts** name who decided: an administrator, or Discovery after the grace period. Every call is in the user audit log (`api.discovery.attempts.defer POST`). Do not edit or delete `scoutro-discovery-jobs.json` or the candidate state to clear an unknown start.

**Removed crawl profile of an accepted start.** When the profile of an accepted crawl disappears before the batch completed (a crawl stopped by hand, or *Delete terminated profiles* in the crawl profile editor), the crawl is not running any more; Discovery records the attempt with `outcome = profile_removed` (`active_batch.profile_removed_count`), reports it to the crawl report as terminated and lets the batch complete. The result of that crawl is unknown; it is never counted as a successful crawl, and the website is not started again because of it. Until 0.11.4 such a batch stayed in `needs_review` with `crawl_status_unknown` without a way out.

**Resume and enable** wake the coordinator at once; a due job no longer waits for the next heartbeat.

**Remaining limit.** Discovery runs one batch at a time: a batch waits for every accepted crawl of its websites to terminate before the next job starts, and a crawler that is paused in YaCy keeps such a batch waiting. Keep `Batch size` and `Max pages per website` of the jobs small enough for that.

Capacity uses existing queue/stacker/indexing/loader counts, pause status, connector presence and resource observer. Conservative defaults: queue threshold 100, indexing threshold 20, minimum free heap/disk 20 MiB; settings `scoutro.discovery.queueThreshold`, `indexingThreshold`, `minimumFreeMemory`, `minimumFreeDisk`. Limits: `maxDomains=100`, `maxPages=100`, `maxDepth=5` (absolute maxima 500/10000/10); worker timeout `workerTimeoutSeconds=3600`, bounded to 60–7200 seconds. These are YaCy settings, not container-specific logic.

Completion requires accepted Crawl Profiles reported passive/terminated. This is an admission/completion proxy, not proof of every document successfully indexed or all pipeline work drained. Missing crawl status waits. YaCy Autocrawl/Recrawl are not enabled. There are no LLM calls. HTTP/HTTPS validation, public-DNS checks, robots, prospect size/type policy and crawl limits remain. Discovery's robots redirects validate each target and stop after five hops; policy refusals fail closed. YaCy also validates profileless robots targets in public/global mode while preserving explicit local/any installations. This is not a complete DNS-rebinding guarantee; see [robots redirect security](../docs/ROBOTS_REDIRECT_SECURITY.md). To avoid replacing foreign collection tags or dropping an existing same-host crawl queue, the Java bridge refuses those starts. This can leave candidates waiting for a deliberate administrator decision.

## Admin API

Base: `/scoutro/api/v1/discovery/`, HTTP Digest administrator only. Agent tokens/grants do not authorize this API. Mutations require same-origin JSON (`Content-Type: application/json`) and the existing API body limit. Responses never contain credentials or configuration/state paths. GETs are read-only; status asynchronously refreshes a read-only candidate summary at most every 30 seconds.

| Method | Endpoint | Input / result / side effects |
| --- | --- | --- |
| GET | `catalog` | Profile IDs/collections/capabilities, source IDs/labels/regions, fingerprint, limits. Missing/invalid runtime config: 503. |
| GET | `status` | Store revision, enabled/paused, worker, capacity, heartbeat/next execution, current run summary (in `needs_review` with `review`: the unconfirmed starts with `attempt_id`, `domain`, `state`, `submitted_at`; `deferred_count`), cached state counts (`held` per profile), config error. |
| GET | `jobs` / `jobs/{id}` | Revision and rows: definition, runtime, status, waiting reason, fresh backlog (null until available). |
| GET | `export` | Schema version and definitions only; no internal run/state data. |
| POST | `jobs` | Definition; server assigns UUID and defaults; creates disabled unless explicitly enabled; 201 + Location. Optional If-Match. |
| PATCH | `jobs/{id}` | Partial definition; nested fields merge, sources replaces the whole source map. Required numeric/quoted If-Match. |
| DELETE | `jobs/{id}` | `{}`, required If-Match; rejects an active job; deletes only job definition, not candidate state or crawls. |
| POST | `jobs/{id}/run` | `{"request_id":"unique-id"}`; 202 wakeup response. Requires global enabled/resumed. |
| POST | `enable` / `disable` | `{}`, required If-Match; explicitly controls global flag and owned heartbeat. |
| POST | `pause` / `resume` | `{}`, required If-Match; updates global pause flag only. |
| POST | `attempts/{id}/defer` | `{"run_id":"<open batch id>"}`, required If-Match. Defers the unconfirmed crawl start `id` (from `status.active_batch.review`, which also carries `reconciled`, `auto_defer_after`, `auto_defer_min_age_ms` and a possible `auto_defer_error`) with the outcome kept unknown and holds its candidate; 200 status with `decision` (`automatic: false`; the coordinator's own decisions carry `automatic: true`, `by: system:reconcile`). Errors above (`attempt_found`, `worker_busy`, `revision_conflict`, `stale_run`, `run_not_in_review`, `attempt_unknown`, `attempt_not_unresolved`, `already_deferred`, `attempt_accepted_in_state`, `collection_conflict`, `state_root_changed`; 503 `state_unreadable`, `hold_failed`). |

Common errors: 400 validation, 401/403 authentication/origin, 404 missing job/path, 409 stale revision/config reference/busy/paused, 428 missing revision, 503 unavailable/corrupt persistence/config. See `openapi.json` and `actions.json`. Machine error codes appear as waiting reasons; unknown future codes remain visible.

## Automation versus batch

Active, Paused and Disabled describe automation independently of an existing batch. Batch status shows the job name, collection, phase and start time. Worker activity alone is not a running batch. Buttons follow allowed_actions; heartbeat repair is explicit. Polling rejects overlapping/stale snapshots.

Contract, endpoints, permissions, errors, persistence and CLI: [Scoutro crawl flow](../docs/SCOUTRO_CRAWL_FLOW.md).

## Scoutro accounts (protected access mode)

Operators (the **collect** right) see and change only the jobs whose profile
writes into one of their collections: catalog, job list, create, edit,
delete and *Run now*. A job of another collection answers like a missing one
(`404 job_not_found`); a profile of another collection is refused
(`403 collection_not_allowed`). The status shows a batch of another
collection only as busy, and offers no global action: enabling, disabling,
pausing and resuming the automation and the job export stay with the
administrator (`403 forbidden`), and so does deferring an unconfirmed crawl
start. Every Discovery crawl stays on exactly
its candidate host (with or without `www.`), the host whose collections the
start checks. See [SCOUTRO_USERS_ACCESS.md](../docs/SCOUTRO_USERS_ACCESS.md) for roles, permissions and the protected access mode.
