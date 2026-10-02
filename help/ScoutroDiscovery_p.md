# Discovery Automation

Administrator-only page: `/ScoutroDiscovery_p.html`. The shared Scoutro navigation and dashboard link here. Mobile uses the same navigation. V1 is **disabled after installation**; viewing this page does not enable a scheduler or create candidate state.

## Configuration and jobs

Configure a persistent runtime directory, in order: `SCOUTRO_CONFIG_ROOT`, YaCy setting `scoutro.discovery.configRoot`, `DATA/SCOUTRO/config`. Automation never uses repository examples as a fallback. `profiles.json.profiles` owns profile identity and explicit collection. `profiles.conf` supplies Freeworld search terms; `osm_profiles.json` supplies OSM rules. Classification metadata stays in runtime config but is not executed by automation.

The editor loads profiles, sources and regions from the runtime catalog. OSM regions come from `osm_regions.txt`, Freeworld search texts from `regions.txt`; these are separate lists. V1 uses the existing Germany Geofabrik provider. Source `mode=all` follows the current source catalog. Removed references are blocked, never substituted. A batch uses an immutable fingerprinted snapshot. Update the runtime files as one coordinated change while automation is paused; a double read detects concurrent byte changes but cannot prove a multi-file deployment transaction.

Job settings: name, enabled/paused, profile, sources with mode and regions, candidate scope, discovery refresh, batch size/pages/depth/seed delay, processing fresh/retry/recrawl and schedule. Collection is resolved from the profile. No paths, secrets, `force`, shell commands or Classification settings are accepted. Batch size limits dispatch, never the number of discovered candidates persisted. Retry includes existing problematic/cooldown entries when eligible; it does not override cooldown. Recrawl follows the Python profile state, not YaCy's host-wide Autocrawl.

`source_regions` processes pairs with matching recorded provenance. `profile_backlog` processes the whole profile, including old entries whose provenance is unknown. A drain-only profile backlog job may omit sources if `discovery.replenish=false`.

## Operation

Explicit Enable installs/enables exactly one WorkTables heartbeat, every 10 minutes. Jobs have separate intervals of at least 10 minutes, oldest due first, then longest not served. Missed periods produce one due execution, not a catch-up storm. WorkTables remains the persistent clock; job definitions/run state are in `DATA/SETTINGS/scoutro-discovery-jobs.json` (schema 1, atomic fsync+rename, numeric revision).

Pause/Disable prevents further seeds; accepted YaCy crawls continue. A job Run once requires a unique `request_id`, global automation enabled and resumed, and the job unpaused; it also works for a disabled job. Run once requests use the same serial admission/recovery path and do not bypass backpressure. Run once may be deferred until the existing batch has completed. Reusing its most recent request ID does not enqueue a second execution. The revision changes with background progress too; reload after HTTP 409.

No heartbeat is silently recreated on restart/read if it was removed or disabled in YaCy Automation. Duplicates block dispatch; explicit Enable repairs owned duplicates. Pause is separate from the existing CLI's pause flag. Resume does not override a CLI pause.

The HTTP tick only wakes a bounded coordinator and returns. The coordinator performs one provider turn, persists every accepted candidate, and dispatches up to the batch limit with admission before each seed. OSM handles one selected extract per turn, rotates a job cursor and refreshes the discovery interval after the full region cycle. Freeworld handles up to five selected search regions per turn with a job cursor. PBF caches live in the state directory, outside Git; successful downloads are atomic, capped at 5 GiB, time-bounded, refreshed after 24 hours. Extraction uses streamed GeoJSON sequence; no whole extract is loaded as JSON. Source failures are errors, not empty successful discovery results.

The existing Python Multi-Profile State V2 remains the sole candidate-state owner. Missing status means fresh. All profiles and Classification values survive. Configure its root with `SCOUTRO_DISCOVERY_DIR`, `scoutro.discovery.stateRoot`, or `DATA/SCOUTRO/discovery`. CLI `--workdir`/`--config-dir` still work. To adopt existing state, point this root to its persistent directory; back up first. The previously known fresh counts were not fully persisted: rerun discovery once to fill the backlog. Already crawled pairs remain ineligible unless retry/recrawl explicitly applies. There is no reset or invented source-region migration.

## Recovery and boundaries

Before Crawl API dispatch, the Java run ledger fsyncs a unique intent and marker. Confirmed crawl ID follows; Python saves the accepted result before acknowledging it. Lost replies are reconciled using the marker reported by the existing Crawl Profiles API. Unknown or removed profiles block in `needs_review`; there is no blind resubmission or exactly-once claim. Restart during discovery with no starts ends the reserved run without replaying it; the next scheduled run replenishes again. Corrupt stores/state remain untouched and block work. Changing the state root with an open run blocks reconciliation.

There is no automatic destructive resolution of `needs_review` in V1. Restore visibility of the original marked Crawl Profile and trigger a tick, or leave automation paused and investigate from a backed-up ledger. Do not clear an unknown intent to force a retry.

Capacity uses existing queue/stacker/indexing/loader counts, pause status, connector presence and resource observer. Conservative defaults: queue threshold 100, indexing threshold 20, minimum free heap/disk 20 MiB; settings `scoutro.discovery.queueThreshold`, `indexingThreshold`, `minimumFreeMemory`, `minimumFreeDisk`. Limits: `maxDomains=100`, `maxPages=100`, `maxDepth=5` (absolute maxima 500/10000/10); worker timeout `workerTimeoutSeconds=3600`, bounded to 60–7200 seconds. These are YaCy settings, not container-specific logic.

Completion requires accepted Crawl Profiles reported passive/terminated. This is an admission/completion proxy, not proof of every document successfully indexed or all pipeline work drained. Missing crawl status waits. YaCy Autocrawl/Recrawl are not enabled. There are no LLM calls. HTTP/HTTPS validation, existing public-DNS checks, robots, prospect size/type policy and crawl limits remain; redirect/DNS-rebinding hardening is a separate follow-up, not a complete SSRF guarantee. To avoid replacing foreign collection tags or dropping an existing same-host crawl queue, the Java bridge refuses those starts. This can leave candidates waiting for a deliberate administrator decision.

## Admin API

Base: `/scoutro/api/v1/discovery/`, HTTP Digest administrator only. Agent tokens/grants do not authorize this API. Mutations require same-origin JSON (`Content-Type: application/json`) and the existing API body limit. Responses never contain credentials or configuration/state paths. GETs are read-only; status asynchronously refreshes a read-only candidate summary at most every 30 seconds.

| Method | Endpoint | Input / result / side effects |
| --- | --- | --- |
| GET | `catalog` | Profile IDs/collections/capabilities, source IDs/labels/regions, fingerprint, limits. Missing/invalid runtime config: 503. |
| GET | `status` | Store revision, enabled/paused, worker, capacity, heartbeat/next execution, current run summary, cached state counts, config error. |
| GET | `jobs` / `jobs/{id}` | Revision and rows: definition, runtime, status, waiting reason, fresh backlog (null until available). |
| GET | `export` | Schema version and definitions only; no internal run/state data. |
| POST | `jobs` | Definition; server assigns UUID and defaults; creates disabled unless explicitly enabled; 201 + Location. Optional If-Match. |
| PATCH | `jobs/{id}` | Partial definition; nested fields merge, sources replaces the whole source map. Required numeric/quoted If-Match. |
| DELETE | `jobs/{id}` | `{}`, required If-Match; rejects an active job; deletes only job definition, not candidate state or crawls. |
| POST | `jobs/{id}/run` | `{"request_id":"unique-id"}`; 202 wakeup response. Requires global enabled/resumed. |
| POST | `enable` / `disable` | `{}`, required If-Match; explicitly controls global flag and owned heartbeat. |
| POST | `pause` / `resume` | `{}`, required If-Match; updates global pause flag only. |

Common errors: 400 validation, 401/403 authentication/origin, 404 missing job/path, 409 stale revision/config reference/busy/paused, 428 missing revision, 503 unavailable/corrupt persistence/config. See `openapi.json` and `actions.json`. Machine error codes appear as waiting reasons; unknown future codes remain visible.
