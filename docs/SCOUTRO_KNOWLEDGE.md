# Scoutro Knowledge: operator guide

This guide is for administrators who switch on, run and look after the
Scoutro knowledge graph. The design, the data model and the decisions behind
it are in the [plan](SCOUTRO_KNOWLEDGE_GRAPH.md). The routes are in
[API.md](API.md#knowledge-graph), and the page itself is described in
[help/ScoutroKnowledge_p.md](../help/ScoutroKnowledge_p.md).

## 1. What it is

Scoutro Knowledge is a small graph of **organisations, facilities, sites,
places and services**, read from the pages Scoutro has crawled. Each fact
says where it comes from. Examples: "Muster Pflege gGmbH operates Haus
Lindenhof", "Haus Lindenhof: phone +49 30 1234567", "Haus Lindenhof offers
day care".

- **Its own store.** The graph lives next to the search index, not in it. It
  is an embedded SQLite database in `DATA/SCOUTRO/knowledge/`, and it never
  writes to Solr.
- **It follows the index.** New, changed and deleted documents of the chosen
  collections update the graph. A regular reconcile catches anything that
  was missed.
- **Off by default.** While `scoutro.kg.enabled=false`, the graph creates no
  files, starts no threads, loads no native library and captures no JSON-LD.
  The rest of Scoutro is unaffected either way.

## 2. Switching it on

Set these keys, either in *Administration → Advanced Properties*
(`ConfigProperties_p.html`) or in `DATA/SETTINGS/yacy.conf`, then restart
Scoutro:

| Key | Value | Why |
|---|---|---|
| `scoutro.kg.enabled` | `true` | Turns the graph on |
| `scoutro.kg.collections` | e.g. `edelsenior-web,bauteamcheck-web`, or `*` for all | The collections the graph follows; nothing outside them is read |
| `scoutro.kg.jsonld.enabled` | `true` | Captures `<script type="application/ld+json">` while crawling. Without it, tier 1 only has metadata, and only pages crawled after the switch carry JSON-LD |
| `scoutro.kg.llm.collections` | optional, e.g. `edelsenior-web` | The optional LLM tier ([5.3](#53-the-optional-llm-tier)) |

- **Invalid settings keep the graph off.** The page *Knowledge graph →
  Settings* lists every invalid value. Nothing falls back silently.
- **On the first start** the graph reads the followed collections from the
  index (the backfill), behind the same gates as all its work
  ([10](#10-behaviour-under-load-and-errors)).
- **On Olares,** the files stay in the app's own `DATA` volume. No second
  volume or external path is needed or created.

## 3. Which data is stored, and which is not

| Stored | Not stored |
|---|---|
| Names, legal forms, identifiers (register entry, VAT ID, LEI, Wikidata, IK) of organisations and facilities | Persons: no managing directors, owners, contact persons or staff, as entities or as facts |
| Addresses, postal codes, localities, coordinates, opening hours, websites | E-mail addresses that may belong to a person (`max.mustermann@…`, `m.mustermann@…`, `erika@…`) |
| Phone numbers and **role** e-mail addresses (`info@`, `kontakt@`, `verwaltung-berlin@`, …) as the organisation publishes them | Names of persons in evidence excerpts: after a role marker or salutation they become `[…]` |
| Relations: operates, part of, located at, in place, offers | Page text beyond the excerpt (at most `extract.maxExcerptChars`, default 200 characters, per fact) |
| Per fact: the page (Solr ID, URL), its collections, its state, the extractor and an excerpt (the evidence) | Anything about visitors, searches or users of Scoutro |
| The validated answers of the LLM tier (a cache, so a page is not sent twice) | The model's raw output, the model's host or key |

- **Raw JSON-LD lives in the Solr index.** It is stored in the field
  `ld_json_txt` under its own budget ([8](#8-storage-limits-and-warning-levels)).
- **Retention, by state of the page:**
  - a fact whose page is gone or expired is no longer current;
  - it is hidden as `stale`, and deleted after `source.staleRetentionDays`
    (90 days);
  - the change feed keeps 30 days.

## 4. How facts arise

### 4.1 Tiers

| Tier | Source | Kind shown | Quality it can give |
|---|---|---|---|
| 1 | JSON-LD (`schema.org`) and page metadata | `jsonld`, `metadata` | `supported` |
| 2 | Deterministic rules on the text of candidate pages (imprint, contact, about, locations, home, pages with structured data) | `rule` | `supported` |
| 3 (optional) | A language model reads the page text and must quote it verbatim | `llm` | `uncertain` only |

### 4.2 Rule vs. LLM

- **Rules are deterministic.** The same page always gives the same facts.
  - Example: on an imprint, the first organisation name with a legal form
    after the imprint marker is the site's operator.
  - The register entry, VAT ID, address, phone and role e-mail of the
    imprint belong to that operator.
  - On other pages, contact data is attached only if the page's structured
    data describes exactly one organisation or facility. Nothing is guessed.
- **The LLM tier adds relations the rules cannot read.** Examples: "operates
  Haus Lindenhof" in running text, or "offers day care".
  - Every entity and every relation needs a quote of at most 200 characters,
    copied verbatim from the page. Anything not found in the text is dropped.
  - Contact data, persons and identifiers are never taken from the model.
  - A fact that only the LLM tier supports stays `uncertain`. A verbatim
    quote proves that the page says it, not that the model read the
    relation correctly.

### 4.3 Quality

| Quality | Meaning |
|---|---|
| `supported` | At least one current tier-1/2 evidence from a live page |
| `uncertain` | Only hedged, only from temporarily unavailable pages, or only from the LLM tier |
| `conflicting` | A single-valued fact (e.g. VAT ID) with several supported values |
| `stale` | No current evidence; hidden unless asked for, purged later |

## 5. Provenance, identities and collections

### 5.1 Evidence

- **Every fact has evidence.** Each evidence row names the page, the tier,
  the extractor (with model and prompt version for the LLM tier), a locator
  (`jsonld:0/telephone`, `text:1234+20`) and the excerpt.
- **A fact without evidence does not exist.** When the last page behind a
  fact disappears, the fact is deleted and a `delete` change is written.

### 5.2 Identity resolution

- **Strong identifiers merge across sites.** Register entry, VAT ID, LEI,
  Wikidata ID and IK merge entities with an equal value across pages and
  domains.
- **Weaker keys stay within one domain.** A site operator merges by legal
  name within one registrable domain. An organisation named with the same
  legal name on that domain, for example as the parent organisation of a
  facility page, is that operator; two such names that are not the operator
  never merge with each other. A facility merges only with type, kind, name
  and **full address** equal.
- **Never by name, phone, e-mail or postal code alone.** Such pairs are
  shown as *possible duplicates* in the object view. Duplicates are
  preferred to wrong merges.
- **Conflicts block a merge.** Two different VAT IDs, two different JSON-LD
  `@id`s, different kinds or different addresses keep entities apart, and
  the blocked merge is recorded as an `identity_conflict` event.
- **IDs are stable.** An entity ID derives from the key it was created
  with. A merged entity's ID becomes a redirect to the survivor (the API
  answers `{"redirect": …}`).

### 5.3 The optional LLM tier

- **Off unless configured.** It runs only for the collections in
  `scoutro.kg.llm.collections`, and only when a model is selected for the
  usage **knowledge** in *LLM Selection*. It reuses Scoutro's existing LLM
  configuration (O3); no key or host is configured here.
- **Bounded.** It has its own threads (`llm.parallel`, 1 or 2), a timeout
  (`llm.timeoutSeconds`, 120), retries (`llm.maxAttempts`, 2), a circuit
  breaker (`llm.breakerFailures`, 3; backoff up to
  `llm.breakerMaxBackoffMinutes`, 60) and at most `llm.maxDocsPerHost` (25)
  pages per host. Input is capped at `extract.maxInputChars` (12 000).
- **Kinds per collection (O2).** The facility kinds the model may assign come
  from `scoutro.kg.llm.kinds.<collection>`, a comma-separated list such as
  `nursinghome,assistedliving`. Start lists exist for the four Scoutro
  collections. A new kind needs no schema change.
- **The cache.** Validated answers are stored (`cache.maxPercent` of the
  budget), so a recrawl without changes, a reconcile or an identity rebuild
  calls the model only for changed text.

### 5.4 Collections (O5)

- **Every view is computed from one collection.** Every name, value, count,
  host and source on the page, in the API, in the export, for agents and in
  the chat comes only from documents of the selected collection.
  - An object that only another collection knows answers "not found".
  - An alias or VAT ID seen only in another collection is not shown.
- **The administrator** can also look across all followed collections, with
  no `collection` parameter.
- **Agents never can.** They are always limited to their own collections.

## 6. Permissions and agent access

| Who | What |
|---|---|
| Administrator (Digest login) | The page `ScoutroKnowledge_p.html`; every route under `/scoutro/api/v1/kg/`: status, control, reads, export, change feed, download, backups |
| Agent with grant `kg.read` | Reads under `/scoutro/api/agent/v1/kg/` (entities, statements, evidence, hosts, sources), always within the agent's collections |
| Agent with grant `kg.export` (separate) | Export pages and change feed for its collections. Evidence names the extractor but not the model, and the agent gets no backlog |
| Agents, never | Status, control, backups, the whole-graph download |
| Chat, local and administrator | Graph facts as numbered sources marked "Scoutro knowledge graph", within the chat's collection (`scoutro.kg.chat.*`) |
| Chat, AI Shield guests | Only with `scoutro.kg.chat.allowGuests=true` (default `false`) |

- **No anonymous access.** No route is public and none works without a
  login. Agent tokens are created in *Agents & Access*.
- **The tools are thin clients.** `tools/scoutro/scoutroctl kg …` and the
  MCP adapter use the same routes and the same permissions.

## 7. Export and change feed

- **Paging.** `GET /kg/export` pages through entities, then statements. With
  `include=evidence` each statement carries its evidence.
- **Not a snapshot.** After the last page, read `GET /kg/changes` from the
  export's `next_changes` cursor. The feed has upserts, redirects and delete
  notices.
- **Starting over.** A cursor older than the change retention, or from
  before a restore or rebuild (a new *dataset epoch*), answers **410** with
  `details.full_sync`. Start the export again.
- **Download (administrator only).** `GET /kg/export/download?format=ndjson`
  streams the whole graph of one collection or of all of them.

## 8. Storage limits and warning levels

All files under `DATA/SCOUTRO/knowledge/` count against **one budget**,
`scoutro.kg.budget.maxBytes`, default **10 GiB**: the database, its WAL,
temporary files, `backup/` and the shadow of a running rebuild. The JSON-LD
field in the Solr index has its **own budget**,
`scoutro.kg.jsonld.maxTotalBytes`, default **2 GiB**. Both are protection
limits, not targets: nothing is reserved, and the files grow only with the
data.

| Level | From | Graph | JSON-LD |
|---|---|---|---|
| `ok` | below 70 % | – | – |
| `notice` | `budget.noticePercent` (70 %) | Shown in the status and on the page | Same |
| `warning` | `budget.warnPercent` (80 %) | Banner on the page and in the dashboard | Same |
| `brake` | `budget.pausePercent` (90 %) | **New growth pauses.** Reads, deletions, state changes and clean-up continue. It resumes below `budget.resumePercent` (80 %) | **The JSON-LD capture pauses.** Pages are still crawled and indexed, without JSON-LD |
| `full` | the budget | The hard limit; SQLite refuses growth (`SQLITE_FULL`) and rolls back | No more capture |

**The crawl is never stopped by these budgets.**

- **What the graph does at the brake.** Its share of the work stops: new
  graph growth, JSON-LD capture and backups. Search, crawl and indexing go
  on.
- **The disk is protected too.** Growth also pauses when the free space of
  the `DATA` disk would fall below YaCy's `resource.disk.free.min.steadystate`
  plus `scoutro.kg.disk.reserveBytes` (1 GiB). All writes stop below YaCy's
  `undershot` floor.
- **Deviation from the requested levels (documented).** The levels are
  70/80/90/100 as asked. The brake at 90 % pauses growth, it does not
  delete anything. Inside the budget, a maintenance share (by default 10 %,
  at least WAL + temp limits) is kept for deletions and clean-up, so the
  database itself is capped at the brake threshold.
- **Every change of level is an event** (`storage_level`, `jsonld_level`).
  `GET /kg/status` reports `storage.level`, `storage.usedPercent`,
  `jsonld.level` and `jsonld.usedPercent`.

How big the graph gets for a given number of pages, measured, and whether
10 GiB / 2 GiB fit: see the plan, [22.4](SCOUTRO_KNOWLEDGE_GRAPH.md#224-measurements-and-budget-evaluation).

## 9. Maintenance

### 9.1 Backup and restore (O6)

- **Making one.** *Knowledge graph → Create backup*, `scoutroctl kg backup`,
  or `POST /kg/control {"action":"backup"}`.
  - It writes `DATA/SCOUTRO/knowledge/backup/graph-<UTC>.db`, a compact,
    consistent single SQLite file made by `VACUUM INTO` while the graph
    keeps running.
  - The copy is checked with `quick_check`. A metadata file next to it holds
    its SHA-256, size, schema version, dataset epoch and counts.
- **The schedule.** A backup runs every `backup.intervalDays` (7; 0 = only on
  request). The newest `backup.keep` (1) are kept.
  - A backup is skipped, with the reason recorded, while growth is paused:
    manual pause, the budget brake or the disk reserve.
- **Keeping a copy outside Olares.** Download a backup from the backup list
  on the page, or with `scoutroctl kg backup-download <file> > graph.db`
  (administrator only).
  - Nothing outside the app's `DATA` is required or written by Scoutro. An
    external disaster-recovery target is an operations decision.
- **Restoring.** *Restore* in the list, `scoutroctl kg restore <file>`, or
  `{"action":"restore","backup":"<file>"}`.
  - **The backup is checked first,** without touching the graph: the name,
    the SHA-256, `quick_check`, the schema version and the epoch. A bad one
    answers 422 `backup_invalid`, and nothing changes.
  - **Then the swap.** Scoutro stops the graph and keeps the current
    database as `graph-<UTC>-before-restore.db`. It puts the backup in place
    with a **new dataset epoch**, so export consumers sync again.
  - **Then the restart.** The graph starts again and reconciles with the
    index: pages that changed since the backup are processed again.
  - **If the restored graph does not start,** the previous one is put back
    (503 `restore_failed`).
- **A backup from another installation.** Copy the file into
  `DATA/SCOUTRO/knowledge/backup/` under a name `graph-<yyyyMMddTHHmmssZ>.db`
  and restore it. Without its metadata file, the SHA-256 check is skipped;
  the other checks still apply.
- **Safety copies** (`…-before-restore.db`, `…-before-rebuild.db`) stay until
  the next regular backup.

### 9.2 Reconcile

- **When it runs.** Every start, every day at `reconcile.hour` (3), after a
  resume, and on request (*Reconcile now*).
- **What it does.** It compares the graph with the index: missed documents
  are processed, and documents no longer in the index are deleted from the
  graph. Each deletion is verified by a real-time get before it happens.
- **The mass-deletion brake.** A run that would delete more than
  `reconcile.maxDeleteFraction` (20 %) of the tracked documents, and at least
  `reconcile.brakeMinDocs` (50), stops as `suspect` and deletes nothing. So
  does a run that finds the index empty while the graph has documents.
  - The page then shows *Confirm deletions*. Check first why the index lost
    documents, for example an emptied or wrong core.

### 9.3 Re-resolve identities (rebuild)

Use this after a change of the identity rules, after an import that caused
wrong merges, or when the object view shows merges that the current rules
would not make.

1. **Start it** with *Rebuild identities*, `scoutroctl kg rebuild` or
   `{"action":"rebuild"}`. It is refused while a backup, restore, another
   rebuild, a reset or a reconcile waiting for its confirmation runs (409),
   and when the budget has no room for the shadow (503, reason
   `rebuild_space`). The shadow needs about 1.2 × the database plus the WAL
   and temp limits.
2. **The shadow graph** is built from the index in
   `DATA/SCOUTRO/knowledge/rebuild/` with the current rules, behind the same
   gates. The current graph keeps answering and following the index
   meanwhile. Progress (pages read, published, queued, its storage) shows
   on the page, which refreshes itself, and in `status.rebuild`.
3. **The check.** The shadow is checked with `quick_check` and compared with
   the current graph using the reconcile's brake. If it lost too many pages,
   it waits for *Swap in the rebuilt graph* (`rebuild_confirm`).
4. **The swap.**
   - Entity IDs of the current graph that the new one does not know become
     redirects to the entity holding most of their identity keys.
   - The LLM cache and the manual pause are carried over.
   - The current database is kept as `backup/graph-<UTC>-before-rebuild.db`
     (restorable like any backup).
   - The shadow takes its place with a new dataset epoch, and the graph
     starts again.
5. **Cancelling.** *Cancel the rebuild* (`rebuild_cancel`) before the swap
   deletes the shadow, and the graph stays as it was. So does a stop of
   Scoutro. A shadow left by a crash is deleted at the next start
   (`phase: interrupted`).

- **Evidence is never invented.** Every fact of the rebuilt graph has its
  own evidence from the index.
- **LLM-only facts come back from the cache.** Facts that only the LLM tier
  supports are re-read from the carried cache after the swap, without
  model calls. They are missing from the moment of the swap until the LLM
  tier has gone through the pages again.

### 9.4 Going back to a version without the graph

- **The index keeps working.** The old version writes its own Solr
  configuration and reads and searches every document. Its partial updates
  (YaCy's postprocessing) also work on pages that carry `ld_json_txt`
  (tested with the version before the graph). A page it rewrites has the
  field indexed as text, a small growth, until Scoutro crawls it again.
- **The graph's directory is ignored.** `DATA/SCOUTRO/knowledge/` keeps its
  space. If you do not come back, download a backup and delete the
  directory while Scoutro is stopped (`rm -r DATA/SCOUTRO/knowledge`).
- **Coming back.** The graph starts, reconciles with the index and takes in
  the pages indexed meanwhile; their JSON-LD only after they are crawled
  again.

## 10. Behaviour under load and errors

| Situation | What the graph does | What to do |
|---|---|---|
| Heavy crawling, high load, low heap | Its work waits behind gates: indexing queue > `gate.maxIndexingQueue` (20), load > `gate.maxLoad` (2.5), free heap < `gate.minFreeHeapMB` (256). Changes queue up, bounded | Nothing; it catches up |
| Change set or queue full (`capture.maxPending` 100 000, `queue.maxItems` 200 000) | Further changes are dropped from the queue and a reconcile is scheduled at once. It repeats once the lost pages are visible to a search (after about 200 s) and once the queue has room again | Nothing |
| Invalid JSON-LD block | Counted (`invalidBlocks`); the other blocks and the rules still apply | Nothing |
| Very large page | JSON-LD capped at `jsonld.maxBytesPerDoc` (16 KiB) and `jsonld.maxBlocksPerDoc` (8); rule input at `extract.maxRuleInputChars` (64 Ki); at most `extract.maxStatementsPerDoc` (50) facts | Nothing |
| LLM unreachable or slow | After `llm.breakerFailures` failures the breaker opens (status `llm.state: paused`, reason `circuit_breaker`) with growing backoff; tiers 1 and 2 go on unchanged | Fix the model; *Retry failed LLM documents* closes the breaker |
| Budget at 90 % / disk reserve | Growth pauses ([8](#8-storage-limits-and-warning-levels)); reads and deletions go on | Raise the budget, free space, or reduce the followed collections |
| WAL limit, blocked checkpoint (a long reader) | Writes wait until a checkpoint completes; readers past `read.maxTransactionMillis` are interrupted | Nothing |
| Unclean shutdown (kill, power loss) | At the next start, graph writes wait for `PRAGMA quick_check` (≤ `integrity.maxMillis`); the sync catches up from the last version checkpoint, then a full reconcile | Watch `store.integrity` |
| Damaged database | `storage_error`; reads go on while possible, writes stop | Restore a backup, or rebuild |
| Invalid settings | The graph stays off (`config_invalid`), Scoutro runs | Fix the listed settings, restart |
| Remote Solr only | The graph does not follow it (`sync.reason: remote_solr_unsupported`; reconcile and rebuild answer 503 `sync_unavailable`) | Use the embedded core |

Stop and start are part of Scoutro's own. A clean stop marks the graph
clean, and the next start needs no check.

## 11. Operation without an LLM

Without a model, or with `scoutro.kg.llm.collections` empty, the graph runs
tiers 1 and 2 only:

- it has no `uncertain` LLM facts and no relations read from running text;
- it makes no outgoing calls.

This is the default. The page shows the LLM tier as `off`.

## 12. Known limits

- **German imprints first.** Rule extraction knows German and English
  imprint and contact pages. Facts on other pages come from structured data
  or, optionally, the LLM tier.
- **No manual curation.** No manual merge, split or edit of facts. Possible
  duplicates are shown, not merged. The rebuild re-applies the rules.
- **IDs can change in a rebuild, and old IDs redirect.** An entity's ID
  derives from the first key it was seen with, and in a merge the older
  entity survives. A rebuild reads the pages in another order and may pick
  another surviving ID; the old ID then redirects. Statement IDs of such
  entities change too. Export consumers sync again after a rebuild in any
  case (new epoch).
- **Host lookup covers ports 80 and 443 only.** The host routes and the host
  filter find a host by name on those ports; a site on another port is
  found through its pages.
- **Excerpts may be over-redacted.** The person-name redaction is
  rule-based. It may also hide a capitalised word after a role marker, and
  a name without a marker or salutation is not recognised.
- **JSON-LD capture starts with the switch.** Pages crawled before
  `jsonld.enabled=true` have no JSON-LD until they are recrawled.
- **The JSON-LD budget counts uncompressed bytes.** Solr stores the field
  compressed, so the real share of the index is smaller than the budget
  suggests.
- **One graph per installation.** Remote Solr is not followed.
- **Not covered by the shutdown work.** YaCy's own 30-second shutdown and
  other YaCy core issues are outside this feature.

## 13. Checklist for operators

- **After switching it on:** *Knowledge graph → Overview* shows `running`,
  the backfill progressing (`sync.reconcile.current`) and objects appearing.
- **Weekly:** the storage level and the JSON-LD level are `ok` or `notice`,
  and the last backup is recent (`backup.last`). Download one now and then
  to keep a copy outside the app.
- **On a `warning`:** look at the growth (`storage.usedBytes`, objects,
  evidence) against the [measurements](SCOUTRO_KNOWLEDGE_GRAPH.md#224-measurements-and-budget-evaluation),
  then raise `scoutro.kg.budget.maxBytes` or reduce the followed
  collections.
- **On `suspect` reconciles:** find out why the index lost documents before
  confirming.
- **Before an upgrade or going back to an older version:** make a backup
  and download it.
