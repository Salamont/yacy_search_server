# Scoutro Crawl Report (plan)

Status: confirmed plan. Phases 1 (storage), 2 (capture), 3 (reports and
rollups), 4 (API and UI) and 5 (data quality) and the Discovery option
`processing.outcome_retry` are implemented on branch `ccr-e3e5f88b-1fqp77`;
the optional phase 6 is not started.

Goal: crawl-report views comparable to a classic crawl audit (crawl details,
status, HTTP codes, content types, depth, duplicates, indexability, data age)
for Scoutro Discovery, which is a permanent crawl process made of many small
YaCy crawls (one per host, collection taken from the Discovery profile).

## Principles

- **Built on YaCy.** YaCy's crawler, crawl profiles, queues, robots handling,
  `collection1` Solr index, `ErrorCache`, `WebStructureGraph` and `sb.tables`
  are used directly. There is no abstraction layer for a future crawler.
- **No YaCy core changes and no core switches.** All code lives in
  `net.yacy.scoutro.*`. Optional schema fields are enabled by configuration
  through the existing schema API, not by code changes.
- **No external data sources.** No Google or other third-party API. Metrics
  that need external ranking data (visibility index, keywords, traffic,
  countries, competitors) are out of scope.
- **Current state, not an unbounded log.** One current row per host and
  collection; a recrawl overwrites it. History is kept only as daily rollups.

## Sources of truth

| Store | Content | Lifetime |
|---|---|---|
| Solr `collection1` (YaCy index) | Current page state: status, MIME types, depth, duplicates, canonical. Queried live, never copied. | Recrawl overwrites documents; age is `load_date_dt`. |
| `scoutro_domains` in `sb.tables` (`DATA/WORK`) | Current host/crawl status: crawl identity and times, data YaCy does not keep (ErrorCache exclusions, precheck result, coverage), plus one previous crawl in `prev_*`. | Permanent; overwritten by the next crawl. |
| Rollup NDJSON | Daily aggregates per Discovery job; the only permanent history. | Permanent. |
| Discovery `state.json` | Discovery/scheduler state only: cursors, selection status, backoff. **No** crawl results and no `last_outcome`. | Unchanged. |

`state.json` is rewritten completely on every save. Keeping results out of it
keeps it small even with very many domains.

## `scoutro_domains`

- Table name `scoutro_domains` in YaCy's `sb.tables` (`WorkTables`, 12-byte keys).
- **Key:** `MD5("scoutro-domains/v1\0" + host + "\0" + collection)`, encoded with
  YaCy's enhanced Base64 alphabet and truncated to 12 characters (72 bits).
  `Word.word2hash` is deliberately not used: it lower-cases its input (the
  collections `Research` and `research` would collide) and fills a global cache.
- **Host:** normalized exact host (IDN to ASCII, lower case, trailing dot removed,
  IP literals rejected). The same rules as Scoutro's host input. `www.example.com`
  and `example.com` are different rows and are never merged automatically.
- **Collection:** `[A-Za-z0-9_-]{1,64}`, case-sensitive.
- **Verification:** host and collection are stored in the row and compared on
  every read and write. A mismatch is reported as `key_collision`; that row is
  neither returned for the requested host nor overwritten. A row with unknown
  schema version or invalid fields is reported as `invalid_row` and also left
  untouched (fail closed).
- **Discovery link:** the registrable Discovery domain (the `state.json` key) is
  stored as `discovery_domain` in the crawl section. It is a link only, never a key.
- **Crawl section:** `crawl_id`, `start_marker` (32 hex, optional), `started_at`,
  `ended_at`, `job`, `discovery_domain`, bounded counters `n_*` and labels `s_*`
  (see [Capture](#capture-phase-2)).
- **Precheck section:** `pc_at`, `pc_result` (`dns`, `robots`, `blocked`,
  `site_5xx`), `pc_detail` (short reason code), `pc_job`, `pc_discovery_domain`.
  Only the latest Discovery precheck refusal; it never touches the crawl
  sections, and a row may hold only a precheck. An older or identical precheck
  writes nothing.

### Idempotent crawl completion

Crawl identity is the start marker when present, otherwise `crawl_id` plus
`started_at` (YaCy crawl IDs can be reused). Every completion runs check and
write under one lock:

| Incoming completion | Result |
|---|---|
| No row yet | `created` |
| Same identity as current, same content | `unchanged`, nothing written |
| Same identity as current, new content (e.g. settled values) | `updated` in place, no shift |
| Same identity as `prev_*` | `ignored_previous` |
| Started before the current crawl (late report) | `ignored_older` |
| Same start time or same marker but conflicting identity | `conflict`, nothing written |
| Newer crawl | `shifted`: current moves once to `prev_*`, then the new crawl becomes current |

A repeated completion of the same crawl therefore never moves current to
`prev_*` a second time.

### Durability limits

YaCy's tables buffer writes and do not fsync every write. After a hard crash
the last rows can be missing; the next crawl recreates them and Solr is not
affected. Only `prev_*` and ErrorCache exclusions of those rows would be lost.

## Capture (Phase 2)

A daemon thread `ScoutroReport.capture` starts with the Scoutro API servlet
(`load-on-startup` in `defaults/web.xml`) and runs every
`scoutro.report.captureIntervalSeconds`. It reads YaCy in-process and writes
only `scoutro_domains`: it never starts, stops or changes a crawl and writes
nothing to `state.json`, the Discovery job store or `yacy.conf`. It uses its
own executor instead of YaCy's `deployThread`, which would persist thread
settings into `yacy.conf`.

Each step:

1. Scoutro crawls with an active YaCy profile are tracked. A Scoutro crawl is a
   profile whose must-not-match filter carries a start marker recorded as
   started in `DATA/SCOUTRO/crawls.ndjson`: Discovery crawls and crawls started
   through the Scoutro API (administrator and agents). Plain YaCy expert crawls
   are not recorded; their pages are still part of the live Solr state.
2. The ErrorCache is read (see below).
3. A tracked crawl whose profile is no longer active is captured after
   `scoutro.report.settleSeconds`, so that indexing can finish.
4. Once after startup, terminated Scoutro profiles without a row are recovered
   (YaCy keeps terminated profiles); their `ended_at` is unknown.

At most 10 captures run per step. A failing index query is retried up to five
times with growing delay; then the crawl is recorded without page counters
(`outcome` `unknown`). `started_at` is the persisted start intent of the crawl
ledger (as in `crawl.status`); `ended_at` is when the end was observed (within
one interval).

### Counters

One rows=0 JSON facet request on `host_s` and `collection_sxt`, split at the
crawl start by `load_date_dt`, with a 2 s budget; partial answers are rejected.

| Column | Meaning |
|---|---|
| `n_pages_total` | documents of host and collection loaded since the crawl start, error documents included |
| `n_pages_ok` | HTTP 200 without fail type |
| `n_pages_redirect` | HTTP 3xx, or a redirect followed by the crawler (YaCy stores it with fail type `fail`, status -1 and the reason `...CRAWLER Redirect of URL=...`) |
| `n_pages_client_error`, `n_pages_server_error` | HTTP 4xx, 5xx |
| `n_pages_excluded` | fail type `excl` (robots.txt, recorded redirects) |
| `n_pages_failed` | fail type `fail` without crawler redirects |
| `n_pages_robots` | reason `FINAL_ROBOTS_RULE` |
| `n_pages_not_reloaded` | documents with a load date before the crawl start: not reloaded by this crawl, not deleted |
| `n_pages_not_reloaded_ok` | of those, HTTP 200 |
| `n_depth`, `n_max_pages` | crawl parameters |
| `n_excl_noindex`, `n_excl_canonical`, `n_excl_filter`, `n_excl_blacklist`, `n_excl_other` | ErrorCache exclusions that are not stored in the index |
| `s_outcome` | `indexed`, `partial`, `not_reloaded`, `not_indexed` or `unknown` |
| `s_coverage` | `complete` or `partial` (ErrorCache counters) |

`indexed`: pages loaded and no failure; `partial`: pages loaded and at least
one failure; `not_reloaded`: nothing loaded, but earlier HTTP 200 pages exist
(for example within YaCy's reload interval); `not_indexed`: no HTTP 200 page at
all; `unknown`: no page counters (schema field missing or index unavailable).

### ErrorCache

YaCy keeps the latest 1000 ErrorCache entries in memory, including exclusions
it does not store in the index (noindex, canonical, URL and content filters,
blacklist, parser and other processing refusals). The thread reads the newest
entries with a growing window (1, 8, 64, 256, 1000) back to the last entry
already read; an idle step reads one entry. Only `FINAL_PROCESS_CONTEXT` and
`FINAL_LOAD_CONTEXT` entries are counted; everything else is also in the index.
Entries are attributed by exact host, collection and time.

Coverage is `complete` only if reading had started before the crawl began, the
crawl was tracked from its first read on, and the last entry read was never
evicted (more than 1000 entries between two reads, or a cleared cache). Crawls
started before the first read after a restart are therefore `partial`.

### Discovery

The coordinator reports accepted starts (marker, job, Discovery domain) and
terminated attempts. This only adds the job context and triggers a missing
capture; hook failures are logged and never change a Discovery decision.

The new worker RPC action `precheck` reports a precheck refusal (`dns`,
`robots`, `blocked`, `site_5xx`, optional short reason code such as
`robots-disallow-all` or `site-5xx:503`) for the candidate host. It is validated
like `crawl` (candidate domain and URL, allowlisted fields) and stored in the
`pc_*` section. The worker sends it after saving its own state and ignores
errors. Prechecks of the manual CLI are not recorded.

### Configuration

Read from `yacy.conf`; nothing is written.

| Key | Default | Range |
|---|---|---|
| `scoutro.report.capture` | `true` | `false` disables capture and the Discovery hooks |
| `scoutro.report.captureIntervalSeconds` | `20` | 5-300 |
| `scoutro.report.settleSeconds` | `120` | 0-3600 |
| `scoutro.report.cacheSeconds` | `600` | 0-86400, table aggregate cache |
| `scoutro.report.staleDays` | `30` | 1-3650, staleness for crawls without a Discovery job |

## Rollups

- Path: `DATA/SCOUTRO/reports/rollups/<job-id>/<YYYY>.ndjson`, one file per
  Discovery job and year (at most 366 lines, about 370 KB per year).
- One line per job and **day with activity**; written once at day close with
  file and directory fsync. A day that already exists is not written again
  (`already_present`), for example after a restart.
- Line format: `{"v":1,"job":"<uuid>","day":"YYYY-MM-DD", ...aggregates}`,
  at most 16 KiB per line.
- **No separate event log.** Chart markers are an optional bounded `markers`
  array inside the daily rollup (at most 20 entries). There is no unbounded log
  of crawl attempts. Phase 3 writes `job_changed` (the job definition, including
  enable/pause, differs from the previous rollup) and `version_changed`.
- Corrupt or foreign content (invalid JSON, missing final newline, wrong job or
  year, duplicate day, oversized line) fails closed: nothing is appended and the
  file is retained for manual inspection.

## Reports (Phase 3)

`report/ReportService` combines the three sources; Phase 4 exposes it through
the API and UI. Nothing in it starts a crawl or changes YaCy.

| Report | Content |
|---|---|
| Host (host + collection) | the `scoutro_domains` row (current and previous crawl, precheck, `latest_attempt`), data age and staleness, and the live index state of the host with `not_reloaded` since the current crawl start |
| Collection | the table aggregate of the collection and its live index state, or the latest rollup snapshot |
| Job | the table aggregate of a Discovery job, its collections and its rollups for a day range (default 90 days, at most three years) |

### Live index facets

`report/IndexFacets`: one rows=0 JSON facet request on `collection_sxt` (and
`host_s` for a host), 2 s budget, partial answers rejected:

- `documents` (error documents included), `ok` (HTTP 200 without fail type),
  `hosts` (collection only), `http_status`, `fail_type` (`excl`, `fail`);
- for `ok` pages: `content_type`, `depth` (`crawldepth_i`, index order) and
  `duplicates`: groups of at least two pages with the same `exact_signature_l`
  (identical body) or `fuzzy_signature_l` (similar body). Solr's `numBuckets`
  ignores `mincount`, so groups are counted from at most 1000 returned buckets;
  at the limit the numbers are lower bounds and `*_truncated` is true;
- `oldest`, `newest` (`load_date_dt`) and, for a host, `not_reloaded`.

Facets whose schema field is disabled are omitted and listed in `unavailable`.

### Table aggregates

One scan of `scoutro_domains` (under the table lock, so no row is read while it
is replaced) yields per collection and per job: `hosts`, `crawled`,
`precheck_only`, `latest_attempt_precheck` (the precheck is newer than the
current crawl), `coverage_partial`, `stale`, `last_crawl`, `outcomes`,
`prechecks` (of latest-attempt prechecks) and the sums of the `pages_*` and
`excl_*` counters. Rows whose key does not belong to their host and collection,
or that cannot be parsed, are counted and left out. The scan is cached for
`scoutro.report.cacheSeconds`.

A host is **stale** when its current crawl ended (or started, if the end is
unknown) longer ago than the job's `processing.recrawl.days`; crawls without a
job use `scoutro.report.staleDays`.

### Fallback

If the live collection facets fail (timeout, partial answer, index
unavailable), the collection report returns the newest rollup `index` snapshot
of a job of that collection from the last 31 days, with `index_source: rollup`,
`index_as_of` and `index_error`. Without such a snapshot `index` is null.

### Daily rollups

The capture thread calls the daily step on every tick; it runs once per local
day, `scoutro.report.settleSeconds` plus five minutes after midnight, so that
crawls ended just before midnight are captured first. (The Discovery heartbeat
is not used: it is disabled by default.) For each Discovery job and each of the
last seven days with activity and without a line it writes:

`collections`, `crawls`, `outcomes`, `coverage_partial`, `pages` and
`exclusions` (sums over crawl sections that ended that day; current and
previous section of each row), `prechecks` (by result, by precheck time),
`version`, `job_fingerprint` (SHA-256 of the sorted job definition, 16 hex
digits), `markers`, and for yesterday an `index` snapshot (`documents`, `ok`,
`hosts`) per collection, taken when the line is written.

If the Discovery job store cannot be read, reports use the default staleness
and rollups are written without `job_fingerprint`. A failed daily step (for
example an unreadable table) waits ten minutes before it scans again.

Catch-up days are rebuilt from the table, which keeps only the current and the
previous crawl of each host; a host crawled more than twice within the window
counts its older crawls only if they are still there. Crawls outside Discovery
jobs are not in job rollups.

## API and UI (Phase 4)

`api/ReportApi` exposes `ReportService` read-only under
`/scoutro/api/v1/reports/` (administrator, HTTP Digest) and
`/scoutro/api/agent/v1/reports/` (Bearer token with the explicit grant
`report.read`, absent from every preset):

| Route | Content |
|---|---|
| `GET jobs` | Known Discovery jobs plus jobs that only appear in the table, with collections, host counts, stale hosts and last crawl |
| `GET jobs/{id}?from=&to=` | Job report; days as `YYYY-MM-DD`, default the last 90 days, at most three years |
| `GET collections/{collection}` | Collection report (table aggregate and live index state, or rollup fallback) |
| `GET collections/{collection}/hosts?filter=&limit=&offset=` | Host list: `stale` oldest crawl first, all other filters by host name; `limit` 1–100 (default 50), `offset` 0–10000; only offset+limit rows are kept while scanning, and the list is cached like the aggregates |
| `GET hosts/{host}?collection=` | Host report; `collection` is required, a missing row is `status: absent` |

Unknown parameters are rejected with 400 before anything is read; other
methods return 405; an unreadable table or disabled capture returns 503
`report_unavailable`. On the agent path a collection outside the scope is
refused with 403 `collection_not_in_scope`; a job is visible only when all of
its recorded collections are in scope, and a job without a recorded collection
only to agents with the complete index (otherwise 404, like a missing job).

`ScoutroSEO_p.html` has a view switch **Host analysis | Crawl report**
(`?view=report`, UI route `report.crawl`):

- **Crawl report** for a collection or a Discovery job: host counts, outcome
  donut, prechecks, page counters, exclusions, HTTP status, content types,
  depth, duplicates and data age as inline SVG charts and lists; for a job the
  daily history (stacked outcomes per day, markers as dashed lines, data table);
  for a collection the host list with filters, paging, a link to the host
  analysis and **Crawl again**.
- **Crawl status** as fifth tab of the host analysis: the row of the host in
  one collection and its live page state.
- **Crawl again** links to `ScoutroCrawls_p.html?url=<scheme>://host/&collection=c#new-crawl` (scheme of the last crawl, otherwise `https`);
  the page itself sends GET requests only. Stored and indexed values are
  rendered as text; labels are translated through the page's label list.

## Data quality (Phase 5)

The live facets and the host report gain data-quality figures. They are read
from YaCy, never copied, and change nothing:

| Figure | Scope | Source | Needs |
|---|---|---|---|
| Canonical: OK pages with a canonical link, pointing to the page itself or elsewhere, and without | collection, host | `canonical_s`, `canonical_equal_sku_b` | both fields |
| Titles and descriptions: OK pages with and without | collection, host | `title`, `description_txt` | default fields |
| Pages sharing a title or a description: groups of at least two pages and their number | host only | `title_exact_signature_l`, `description_exact_signature_l` | both fields |
| First-level directories: documents and OK pages per directory (`/` for the root and files below it), top 50 | host only | `sku` of at most 5000 documents | default fields |
| Referring hosts: hosts whose crawled pages link to the host, with their link count, top 20 | host only | `WebStructureGraph` (`sb.webStructure`, all protocols and ports) | — |

Equal titles on different hosts are no issue of either site, so title and
description groups are only computed within one host. A canonical pointing
elsewhere stays in the index because Scoutro crawls do not set YaCy's
`noindexWhenCanonicalUnequalURL`; pages that YaCy refused for their canonical
are counted as `excl_canonical` (see [ErrorCache](#errorcache)). Groups are
lower bounds at 1000 returned groups (`same_truncated`). Above 5000 documents
the directory figures cover the documents read (`truncated`).

**Optional fields.** `canonical_s`, `canonical_equal_sku_b`,
`title_exact_signature_l` and `description_exact_signature_l` are disabled in
YaCy's default schema and stay so; nothing in Scoutro changes the schema. An
administrator enables them in YaCy's index schema (`IndexSchema_p.html`,
existing page with transaction token). They are filled for pages crawled
afterwards. While they are disabled the facets that need them are omitted, the
names are listed in `unavailable`, and the UI names the missing fields with a
link to the schema page. Enabling them adds no postprocessing step: YaCy uses
the signatures for its uniqueness flags only when `title_unique_b` or
`description_unique_b` are enabled too. With `canonical_equal_sku_b`, YaCy's
duplicate postprocessing ignores pages whose canonical points elsewhere; with
`canonical_s`, CitationRank (only if enabled) counts links to such a page for
its canonical target. Search and ranking use none of the four fields otherwise.

**Referring hosts and scopes.** YaCy's host link graph knows no collections.
Agents with a narrowed data scope therefore get `referring_hosts: null` and
`referring_hosts_scope: complete_index_required`; the administrator and agents
with the complete index see the list. It describes links observed by this peer,
not a complete backlink index.

**Scheme.** Capture stores the scheme of the crawl's start URL as label
`scheme` (`http` or `https`). The host list returns it, and "Crawl again" uses
it; rows recorded earlier have no scheme and fall back to `https`.

## Data age

- Data age per host: end of the last crawl compared with the job's recrawl
  interval; reported as current or stale.
- New crawls use existing mechanisms only: the Discovery recrawl rule, or an
  explicit "Crawl again" action through the existing `crawl.start`. Nothing is
  started by viewing a page.
- `load_date_dt` before the crawl start means only **"not reloaded by this
  crawl"**, never "deleted". Scoutro crawls run with `deleteold=off` and with a
  page limit, so missing reloads are not evidence of deleted pages.

## Measured storage

`scoutro_domains` was measured with the Phase 1 implementation in a temporary
YaCy table (`Tables`, 12-byte keys): 20,000 and 100,000 hosts, nine counters,
two labels, Discovery job and domain, start marker; heap, index and gap files
counted. Rollup sizes are from synthetic records shaped like the planned data.
Real sizes may differ by about ±30 %.

| Item | Size |
|---|---|
| `scoutro_domains`, host after its first crawl | about 540 B |
| `scoutro_domains`, host after a recrawl (current + complete `prev_*`) | about 1,060 B (1,080 B with index files) |
| Growth over further recrawls of the same hosts (up to 8 crawls measured) | none; YaCy's heap reuses the space of replaced rows |
| 100,000 / 1,000,000 hosts | about 108 MB / 1.1 GB |
| Rollup line | about 1 KB raw, 0.2 KB gzip |
| Rollups for 5 jobs | about 1.85 MB per year |

Report data grows with the number of hosts, not with time or the number of
recrawls. Page data lives only in the YaCy index.

## Phases

1. **Storage (implemented):** `report/DomainTable` (key derivation,
   verification, idempotent current/prev logic), `report/CrawlSnapshot` (one
   bounded crawl section), `report/HostNames` (exact host normalization, also
   used by the existing `HostInput`), `report/RollupStore` (yearly files),
   tests with temporary tables and directories. No Discovery integration, no
   API, no UI, no Solr queries; nothing calls the new classes yet.
2. **Capture (implemented):** see [Capture](#capture-phase-2): monitor thread
   for Discovery and Scoutro API crawls, ErrorCache reader, Discovery hooks and
   the `precheck` RPC. `report/CrawlOutcome`, `report/ExclusionTracker`,
   `report/CaptureService`, `report/PrecheckResult`; YaCy adapter
   `api/CaptureRuntime`. No API, UI or rollup writing yet.
3. **Reports (implemented):** see [Reports](#reports-phase-3):
   `report/IndexFacets`, `report/ReportService` (host, collection and job
   reports, cached table scan, rollup fallback, daily rollups from the capture
   thread), `DomainTable.scan`. No API or UI yet.
4. **API and UI (implemented):** see [API and UI](#api-and-ui-phase-4):
   `api/ReportApi` with GET endpoints under `/scoutro/api/v1/reports/...` and
   the agent path, explicit grant `report.read`, `ReportService.hosts` and
   `jobs`, the crawl report view and the host's crawl status tab in
   `ScoutroSEO_p.html` with inline SVG charts, data age, stale filter and
   "Crawl again"; help, all locales, OpenAPI/actions catalog and UI routes.
5. **Data quality (implemented):** see [Data quality](#data-quality-phase-5):
   canonical, title and description figures (optional schema fields
   `canonical_s`, `canonical_equal_sku_b`, `title_exact_signature_l`,
   `description_exact_signature_l`, enabled by the administrator in YaCy's
   index schema), first-level directories per host, locally observed referring
   hosts through `WebStructureGraph`, and the crawl scheme for "Crawl again".
6. **Optional:** measure the storage cost of YaCy's Webgraph core before any
   isolation-level analysis.

`processing.outcome_retry` (outcome-driven Discovery selection, default off) is
implemented, see [Outcome retry](#outcome-retry). With the option off, crawl
results never write to `state.json`; with it on, only the existing fields of the
retry path are updated.

## Outcome retry

`processing.outcome_retry` is an optional Discovery job flag (default off). It
requires `processing.retry`; jobs stored before it existed have no flag and
keep it off.

- Before its selection, the Python runner collects the job's `crawled` entries
  whose crawl started in the last three days and asks the bridge for their
  recorded results: RPC action `outcomes` with at most 200 host names, answered
  from `scoutro_domains` (current crawl of the host in the run's collection:
  `crawl_id`, `outcome`, `ended_at`). The action is refused for jobs without
  the flag and writes nothing.
- Only a result of the crawl this entry started counts (same `crawl_id`).
  `not_indexed` and `not_reloaded` call the existing `mark_retry`: `status:
  retry`, `error_class` = the outcome, `attempts` + 1, `last_error`,
  `next_attempt` = now + min(2^attempts hours, recrawl interval).
- Starting such a retry keeps the attempt count (`confirm_start` would reset
  it), so a host that keeps failing is retried after 2, 4, 8 … hours up to the
  recrawl interval, and never more often than a normal recrawl after that. A
  successful result (`indexed`, `partial`) resets the attempt count.
- A failed lookup (no table, refused action) is skipped and the selection runs
  as without the flag. The run report counts `outcome_retries`.
- Nothing else is copied into `state.json`; results stay in the crawl report.

## Not changed

YaCy core files, crawler and queues, ranking, P2P, the `state.json` schema and
the Discovery job store schema.

## Tests

`ant scoutro-report-test` runs the report tests, the host normalization
regression tests (`CrawlFlowTest`) and the Discovery tests; all are also part of
`ant scoutro-agents-test`. They use temporary tables, directories and an
embedded index only. Phase 1: deterministic keys, host normalization, `www`
separation, case-sensitive collections, key collisions, invalid rows, all
completion cases, persistence across reopen, no table growth over repeated
recrawls, rollup partitioning, idempotent days, corrupt files and size limits.
Phase 2: the capture query against YaCy's real schema in an embedded Solr core
(`CrawlOutcomeSolrTest`), counters and outcome rule, ErrorCache windows, loss
detection and attribution, capture timing, recovery, retries and Discovery
context, precheck rows, Discovery hooks and the `precheck` RPC, and the Python
worker's precheck report (`test/scoutro-discovery/test_automation.py`).

Phase 3: the report facets against an embedded Solr core (`IndexFacetsSolrTest`),
host, collection and job reports, aggregates, staleness, latest-attempt rule,
cache, fallback, daily rollups with catch-up, grace time and markers, and the
table scan (`ReportServiceTest`).

Phase 4: routes, parameter validation, scopes, the service error and the grant
outside presets (`ReportApiTest`), the agent path (`AgentApiTest`), the catalog
(`AgentCatalogTest`, `test_flow_contract.py`), and
`test/scoutro-ui/report-live-smoke.py` with a disposable fixture: no state
change by report reads over two capture ticks, the UI in English and German at
five widths (`report-ui-test.mjs`) and the admin and agent API
(`test_report_api.py`).

Phase 5: canonical, title and description figures, host-only groups,
disabled fields and the directory scan against an embedded Solr core
(`IndexQualitySolrTest`), referring hosts (merge, self, invalid names, limit,
unavailable graph) and the scheme in the host list (`ReportServiceTest`), the
scheme label (`CrawlOutcomeTest`, `CaptureServiceTest`), and referring hosts
only for the complete index (`ReportApiTest`).

Outcome retry: the optional flag and its dependency on retry, the `outcomes`
action (validation, refusal without the flag, unavailable table) and a real
Python run that turns `not_indexed` into a retry (`DiscoveryAutomationTest`),
reading the current crawl per host (`ReportApiTest`), and the runner's rules:
own crawl id only, three-day window, growing and bounded backoff, reset on
success, batching and best effort (`test/scoutro-discovery/test_automation.py`).

`test/scoutro-api/report-capture-live-smoke.py` runs one real crawl of a local
fixture site on a new disposable peer and checks the captured row (pages,
redirect, 404, noindex from the ErrorCache, outcome and coverage).
