# Scoutro host, crawl and Discovery flow

Scoutro's native admin page is `ScoutroCrawls_p.html` (Crawls → New crawl).
The standard YaCy expert pages remain available for YaCy administration.
Scoutro uses YaCy's existing text crawler, queue, robots and indexing. It does
not introduce crawl workers, ranking, Citation processing, or an LLM dependency.

## One crawl contract

`POST /scoutro/api/v1/crawls` uses administrator Digest authentication.
`POST /scoutro/api/agent/v1/crawls` requires Bearer authentication and `crawl.start`.
Both accept `url`, **required** `collection`, `scope` (`domain`/`subpath`; `wide`
only for admins), `depth` (0–10), `maxPages` (1–1,000,000 per domain).
Admin defaults remain depth 2, domain, unlimited pages when omitted. Agent
limits may lower defaults and maxima. Native UI always submits bounded pages.
An explicit `user` is valid; there is no fallback to it. Missing/empty/null/invalid
collection returns 400 before any YaCy request. Foreign agent collection returns
403 `collection_not_in_scope`. No runtime taxonomy is created or changed.
An unavailable collection schema/store refuses dispatch (503).

Use `Idempotency-Key` (1–100 `[A-Za-z0-9_.:-]`) for starts. Native UI supplies it;
CLI accepts `--idempotency-key`. Same key and parameters: 200 replay instead of
201 new start. Different parameters: 409 `idempotency_conflict`. A start with an
unconfirmed outcome is never dispatched again: 409 `crawl_start_unconfirmed`.
A running/paused crawl on the same host (including www alias) blocks a new
start with 409 `host_busy`, preserving queued URLs. Internal Discovery starts
carry their existing frozen collection and start marker through the same parser.

A start is a mutation: YaCy reloads the seed URL and indexes into the selected
collection. Agents retain domain, depth, pages, parallelism, foreign-index and
ownership checks. Existing agent presets/grants are not automatically expanded.

## Result of a start on the native page (package 6.3)

The page shows the answer of the backend as it is, next to the form
(`#scc-result`); crawl logic, queues and the API are unchanged.

| Answer | Result of the start attempt | Status shown |
| --- | --- | --- |
| 201 | Started (YaCy created the crawl profile) | the profile's state |
| 200 `idempotentReplay` | Already started, no second start | the profile's state |
| 4xx (`collection_unknown`, `host_busy`, `crawl_rejected`, `invalid_request`, `idempotency_conflict`, 401/403) | Rejected | Not started, no profile ID |
| 409/503 `crawl_start_unconfirmed` | Unconfirmed: YaCy may have started it | Unknown |
| 5xx (`upstream_error`, unavailable), no answer | Failed | Unknown |

The profile's state is `running` (**Running**: the YaCy crawl profile is
active), `paused` (**Waiting**: YaCy's local crawler is paused; the only
waiting state there is), `terminated` (**Finished**: YaCy ended the profile)
or `removed` (**Profile removed**). YaCy creates the profile at once and
reports no queue position, so the page never says "queued". The panel also
shows URL, collection, the crawl/profile ID, the time of the attempt and the
start time recorded by the backend, and an understandable error with the
backend's code and message. **View crawl status** leads to the crawl's card in
the list, which marks it as **This start**; the panel follows the card's state
with every refresh, and `GET /v1/crawls/{id}` when the profile left the list.

Double starts: the button is disabled while a start is on its way, and after a
start (or a replay) the same request cannot be sent again until a field
changes. An open outcome (no answer, 5xx, unconfirmed) keeps the
`Idempotency-Key`, so retrying returns a start that arrived instead of
repeating it; a definite refusal gets a new key, and `host_busy` still guards
the host. After a start the page keeps `?crawl=<profile id>` in its address:
a reload reads that crawl again from `GET /v1/crawls/{id}`.

## Host → index → analysis

`GET /scoutro/api/v1/hosts/resolve?input=HOST_OR_HTTP(S)_URL&collection=NAME`
normalizes IDN/case/trailing dot, preserves www and returns a normalized seed URL.
It strips fragments, rejects credentials, IP literals and non-HTTP(S) schemes,
and performs no DNS/target fetch. Missing host returns HTTP 200 `indexed:false`,
`analysisAvailable:false`, not a general error. Solr failure remains an error.
`visibleRecords` describes scoped index records (including error records), not
proof of successfully loaded pages. `crawl.collectionRequired:true` and
`crawl.canRequest` describe permission only; start preflight can still reject.
SEO summary also returns `indexed`/`analysisAvailable` for absent hosts.

The SEO UI offers an explicit Start crawl link prefilled with seed and selected
collection. It never submits automatically. After crawling/indexing the status
card links back to analysis, which resolves the host again. Termination alone
is not proof of indexing. Exact hosts and www variants remain distinct index
lookups. The collection of a crawl is chosen from a list, never typed:
`GET /v1/collections` (administrator) lists up to 500 collections of the
collection catalog (`CollectionCatalog`: the index facet `collection_sxt`, the
collections created with `POST /v1/collections` in
`DATA/SCOUTRO/collections.json` and the collections of the Discovery profiles;
`robot_*` is internal and never listed as a choice). **New collection** next to
the list opens a dialog (display name, ID suggested from it, optional
description); `POST /v1/collections` validates it on the server (`400
collection_id_invalid`, `400 collection_reserved`, `409 collection_exists`,
`400 invalid_request`, `503 index_unavailable` when duplicates cannot be
checked, `503 collection_store_unavailable`), the list is reloaded and the new
collection selected. `POST /v1/crawls` refuses a collection outside the catalog
with `400 collection_unknown` before anything is dispatched; agents and
Discovery keep their scope from their grants and profiles.
`/agent/v1/collections` reports granted names, including empty collections,
without foreign counts.

Agent equivalents use `/agent/v1/hosts/resolve` and `/agent/v1/collections` with
explicit new grants `host.resolve`/`collections.list`. Reads filter collections
and never reveal foreign index records; an unseen host is `indexed:false`.
Neither read grant permits starting a crawl. `seo.read` remains separate.

The crawl report (`ScoutroSEO_p.html?view=report`) and the host's **Crawl
status** tab offer **Crawl again** links of the same kind: `ScoutroCrawls_p.html`
with `url=` the host (scheme of its last crawl, otherwise `https://`) and the collection prefilled. They never submit; the
grant `report.read` permits reading only.

## Machine-readable crawl status and persistence

GET `/v1/crawls` and `/v1/crawls/{id}` (agent: own scoped crawls only) return
`url`, `collection`, `host`, `scope`, `depth`, `maxPages`, `startedAt`, `endedAt`,
`state`, `lastError`, `progress`. States remain YaCy `running`/`paused`/
`terminated`, plus `removed` for a recorded profile which no longer exists;
agent recovery can also report `unconfirmed`. Missing evidence is JSON null.
`startedAt` is the persisted start-intent time; end time, total and percentage
remain null when YaCy supplies no reliable value. `pagesLoaded` comes from
YaCy's existing profile counter. Live lists enumerate retained YaCy profiles;
recorded removed admin profiles remain addressable by ID. IDs can be reused:
metadata is attached only when both ID and start marker match.

The additive ledger `<DATA>/SCOUTRO/crawls.ndjson` is created only on a valid
explicit start, before dispatch, with complete append records and file/directory fsync. Records are indexed in memory by marker/reference/ID; unchanged files are not reparsed on every poll. GET/startup does not
create or migrate data. Existing agent ownership and Discovery state engines
remain authoritative for their respective recovery; no reset/migration is
performed. Corrupt/unsupported metadata fails closed and is retained. Journal entries are bounded at 16 KiB; there is no total 10,000-crawl cap that
would stop a large Discovery backlog. Restart reconstructs the metadata indexes
once, without any crawl dispatch. A torn/corrupt journal fails closed and needs
explicit administrative recovery; no automatic truncation or key eviction.
Long-term compaction/retention remains a future explicit maintenance operation.
Clients must not
replace an unconfirmed key to retry the same intent.

## Discovery status

GET `/v1/discovery/status` retains existing fields and adds:

- `automation_status`: `active` / `paused` / `disabled` (disabled takes precedence).
- `running`: true only for a real active batch in `reserved`, `running`, or
  `waiting_for_crawler`. `worker_busy` alone can be read-only status work.
- `active_batch`: nullable public projection; `job_name`, `collection`, `phase`,
  `started_at`, `attempt_count`. No runtime/state-root paths or secrets.
- `phase`: `idle`, active phases above, `needs_reconcile`, `needs_review`.
  Finished history/last run projects `completed`.
- `waiting_reason`: existing reliable scheduler/recovery reason or global pause.
- `allowed_actions`: `enable`, `disable`, `pause`, `resume` as applicable;
  an enabled automation with missing heartbeat offers explicit heartbeat repair.

An existing batch is independent of global automation; disabling/pausing does
not cancel submitted YaCy crawls. Blocked recovery retains `active_batch` while
`running:false`. No jobs, heartbeat or automation are enabled by installation.
Agent GET requires explicit global `discovery.status` (external agents only,
admin risk, absent from presets); no agent Discovery write action is added.
Global Discovery status contains job metadata and should be granted consciously.

Discovery UI renders job name/collection/phase/time instead of a primary UUID.
Global buttons derive from allowed actions. Polls use one in-flight refresh,
abort/generation guards and matched revisions for status/jobs; older responses
cannot replace a newer state. Crawl cards use the same guarded polling.

## Clients

OpenAPI and actions.json are generated together by
`tools/scoutro/generate_api_description.py`; the Agent Action Registry matches.
MCP tool names in actions.json are mappings, not a new MCP server.

```
scoutroctl host resolve 'https://www.example.com/path' --collection research
scoutroctl collections
scoutroctl collections create 'Mein neues Portal' --description 'optional'
scoutroctl crawl start https://www.example.com/ --collection research --max-pages 15 --depth 2 --idempotency-key run-1
scoutroctl crawl status CRAWL_ID
scoutroctl automation status
```

Legacy `scoutroctl discovery ...` still delegates to the existing discovery CLI.
Existing requests omitting a collection must be updated explicitly; no existing
crawl, agent, collection, runtime config, job or LLM setting is rewritten.
