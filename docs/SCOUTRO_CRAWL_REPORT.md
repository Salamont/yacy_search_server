# Scoutro Crawl Report (plan)

Status: confirmed plan. Implementation starts with Phase 1 (storage) on branch
`ccr-e3e5f88b-1fqp77`; later phases are not started.

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
  `ended_at`, `job`, `discovery_domain`, bounded counters `n_*` and labels `s_*`.
  Phase 2 defines the concrete counter and label names.

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

## Rollups

- Path: `DATA/SCOUTRO/reports/rollups/<job-id>/<YYYY>.ndjson`, one file per
  Discovery job and year (at most 366 lines, about 370 KB per year).
- One line per job and **day with activity**; written once at day close with
  file and directory fsync. A day that already exists is not written again
  (`already_present`), for example after a restart.
- Line format: `{"v":1,"job":"<uuid>","day":"YYYY-MM-DD", ...aggregates}`,
  at most 16 KiB per line.
- **No separate event log.** Chart markers (job edit, enable/pause, version
  change, schema change) are an optional bounded `markers` array inside the
  daily rollup (at most 20 entries). There is no unbounded log of crawl
  attempts.
- Corrupt or foreign content (invalid JSON, missing final newline, wrong job or
  year, duplicate day, oversized line) fails closed: nothing is appended and the
  file is retained for manual inspection.

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

Measured with synthetic records shaped like the planned data (Discovery
defaults: 15 pages, depth 2). Real sizes may differ by about ±30 %.

| Item | Size |
|---|---|
| `scoutro_domains` row (bencoded, earlier estimate with two `prev_*` values) | about 354 B; Phase 1 measures the real row with a complete `prev_*` section |
| Rollup line | about 1 KB raw, 0.2 KB gzip |
| Rollups for 5 jobs | about 1.85 MB per year |

Report data grows with the number of hosts, not with time. Page data lives
only in the YaCy index.

## Phases

1. **Storage:** `report/DomainTable` (key derivation,
   verification, idempotent current/prev logic), `report/RollupStore` (yearly
   files), tests with temporary tables and directories. No Discovery
   integration, no API, no UI, no Solr queries.
2. **Capture:** call from `DiscoveryService` when a crawl has terminated (also for
   Scoutro API crawls via `CrawlLedger`); ErrorCache reader thread (YaCy
   `deployThread`, about 20 s, cursor on fail date, `coverage: partial` when
   entries were lost); precheck results (DNS, robots, blocked, 5xx) from the
   Python worker via RPC into the table.
3. **Report:** live Solr facets per collection, job and host plus the table;
   daily rollup through the existing heartbeat, also used as fallback when a
   facet query exceeds its 2 s budget.
4. **API and UI:** GET endpoints under `/scoutro/api/v1/reports/...`, explicit
   agent grant `report.read`, a "Crawl report" tab in `ScoutroSEO_p.html` with
   inline SVG charts, data age, stale filter and "Crawl again"; help, all
   locales, OpenAPI/actions catalog and UI routes.
5. **Data quality:** optional schema fields (`canonical_s`,
   `canonical_equal_sku_b`, `title_exact_signature_l`,
   `description_exact_signature_l`), directory facets, locally observed
   referring hosts through `WebStructureGraph`.
6. **Optional:** measure the storage cost of YaCy's Webgraph core before any
   isolation-level analysis.

`processing.outcome_retry` (outcome-driven Discovery selection, default off)
is added in a later phase, after capture works. With the option off, crawl
results never write to `state.json`; with it on, only existing selection fields
(`status`, `error_class`, `next_attempt`) are updated.

## Not changed

YaCy core files, crawler and queues, ranking, P2P, the `state.json` schema and
the Discovery job store schema.

## Tests

Phase 1 adds focused tests with temporary tables and directories: deterministic keys, host normalization, `www`
separation, case-sensitive collections, key collisions, invalid rows, all
completion cases, persistence across reopen, rollup partitioning, idempotent
days, corrupt files and size limits.
