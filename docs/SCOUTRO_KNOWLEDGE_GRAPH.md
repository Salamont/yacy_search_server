# Scoutro Knowledge Graph (plan)

Status: **plan (Auftrag 0), nothing implemented.** Base: `main` at `a42cc7d`
(Scoutro `1.942-scoutro.12`, alias `0.6.0`). Inputs: the owner's release plan
(`Scoutro_Knowledge_Graph_Releaseplan.md`, 2026-10-05) and the earlier static
analysis (`YaCy_Knowledge_Graph_Architekturanalyse.md`). Every finding of that
analysis was re-checked against the code; the results are in
[section 1](#1-verified-starting-point). Where this plan deviates from the analysis,
it says so.

Goal: a complete, regular Scoutro release (no pilot) that extracts organisations,
operators, facilities, sites, places and services from enabled collections, links
them with sourced statements, keeps them current through recrawls and deletions,
and exposes them in Index Browser, SEO analysis, chat and an authenticated JSON
API/export. Disk usage is part of the feature: the graph has a budget, a free-space
reserve, automatic pause and resume, and bounded retention.

## Contents

0. [Decisions at a glance](#0-decisions-at-a-glance)
1. [Verified starting point](#1-verified-starting-point)
2. [Principles and non-goals](#2-principles-and-non-goals)
3. [Storage backend decision](#3-storage-backend-decision)
4. [Data model](#4-data-model)
5. [Synchronisation with Solr](#5-synchronisation-with-solr)
6. [Extraction](#6-extraction)
7. [Storage and resource contract](#7-storage-and-resource-contract)
8. [Interfaces: API, export, chat, UI](#8-interfaces-api-export-chat-ui)
9. [Configuration](#9-configuration)
10. [Affected files](#10-affected-files)
11. [Work packages](#11-work-packages)
12. [Release acceptance](#12-release-acceptance)
13. [Migration, backup, rollback](#13-migration-backup-rollback)
14. [Version recommendation](#14-version-recommendation)
15. [Open points and missing access](#15-open-points-and-missing-access)
16. [First implementation PR](#16-first-implementation-pr)

## 0. Decisions at a glance

| Topic | Decision | Section |
|---|---|---|
| Backend | **Embedded SQLite** (`org.xerial:sqlite-jdbc`, one database file under `DATA/SCOUTRO/knowledge/`), WAL mode, `synchronous=FULL`, one writer connection, a small reader pool. No second process and no RDF/graph server. | [3](#3-storage-backend-decision) |
| Data model | Property graph in relational tables: entities, identity keys, **merged statements** with a separate **evidence row per (statement, source document)**. Document-scoped replacement happens on the evidence layer inside one transaction. | [4](#4-data-model) |
| Identity | Deterministic public IDs. Automatic merge only on strong identifiers (register number, VAT ID, LEI, Wikidata QID, IK) or on site-scoped keys inside one registrable domain. Unclear cases stay separate. Merges leave redirects. | [4.3](#43-identity-resolution) |
| Change capture | A Solr `UpdateRequestProcessor` on `collection1` records changed and deleted IDs into a bounded in-memory set. A sync thread moves them into a persistent work queue. | [5.1](#51-change-capture) |
| Consistency | Catch-up by Solr `_version_` after an unclean stop, a periodic full reconcile (merge join of Solr IDs and graph documents), and publish only with a generation check (compare-and-set) plus a dirty-set check. | [5.3](#53-reconcile-and-catch-up), [5.5](#55-stale-results-and-ghost-documents) |
| Solr write-back | **None.** The processing state lives only in the graph store. No new Solr fields for the graph state. | [5.6](#56-no-solr-write-back) |
| Structured data | One small, additive core change: JSON-LD blocks are captured at parse time into a stored-only Solr field `ld_json_txt`. Scoutro crawls keep no HTCache, so this is the only way to keep structured data for asynchronous processing. | [6.2](#62-json-ld-capture-the-only-yacy-core-change) |
| Extraction | Three tiers: JSON-LD/metadata, deterministic rules (imprint/contact data), and LLM (targeted pages only, one request at a time, finite timeouts, circuit breaker). Cache key = extractor version + input hash + domain context. | [6](#6-extraction) |
| Storage | Application budget over **all** files of the graph directory. Hard cap on the main file via `max_page_count`. Free-space reserve coordinated with YaCy's own disk thresholds. Pause at 90 %, resume at 80 %. Deletions keep running. Incremental vacuum returns space without a full copy. | [7](#7-storage-and-resource-contract) |
| Access | Admin: `/scoutro/api/v1/kg/*` (Digest). Agents: `/scoutro/api/agent/v1/kg/*` with new grants `kg.read` and `kg.export`, scoped by collection. Visibility is computed from the evidence: a fact is visible only through a source document in a visible collection. | [8](#8-interfaces-api-export-chat-ui) |
| Chat | A graph-facts step in `RAGProxyServlet` between retrieval and context build. Facts become citable numbered sources. 300 ms limit, silent fallback to plain search. Off for AI Shield guests. | [8.4](#84-chat) |
| Release | `1.942-scoutro.13`, alias `0.7.0`. The Olares chart is a separate follow-up. | [14](#14-version-recommendation) |

## 1. Verified starting point

### 1.1 Repository state

- `origin/main` = `a42cc7d` ("Merge pull request #11 … release/scoutro-0.6.0"). Every PR (#1–#11) is closed.
- Every remote feature branch is contained in `main` except **`ccr-e3e5f88b-1fqp77`** (1 commit, `735deaa`, 2026-10-05, not merged and no PR). It belongs to another session and is left untouched. It adds:
  - an Index Browser domain view (`GET /v1/index/domains`) and a streamed domain export (`/v1/index/domains/export?format=json|csv`, schema `scoutro.domains.v1`);
  - the AI Lab navigation and a reworked chat UI;
  - locale rewrites.

  Its `Sink`/cursor/`complete` pattern is the template for the graph export. Conflicts with this plan are expected in `ScoutroApiServlet.java`, `IndexBrowser_p.html`, `index-browser.js`, `header.template`, all `locales/*`, `openapi.json`/`actions.json` and `generate_api_description.py` (see [open point O4](#15-open-points-and-missing-access)).
- `AGENTS.md`: HTML changes need `locales/` and `help/` updates. API/servlet changes need `help/` or doc updates. Tests are improved incrementally in the reviewed area.
- Existing Scoutro persistence uses kelondro tables (`DATA/WORK`), atomically rewritten JSON (`DATA/SETTINGS`) and append-only NDJSON (`DATA/SCOUTRO/...`). No SQL or graph library is bundled yet (`ivy.xml`). Scoutro config keys have code defaults and are not in `defaults/yacy.init`.
- Container: `eclipse-temurin:24-jdk-noble` (Ubuntu 24.04, glibc), `linux/amd64` only (`.github/workflows/publish-scoutro.yml:90`), compiled with `javacRelease=17`.
- Olares: `DATA` is a hostPath, the container limit is 4 CPU / 6 GiB, the manifest declares `requiredDisk: 2Gi` and `limitedDisk: 20Gi`. Whether `limitedDisk` is enforced is not documented.

### 1.2 Analysis findings re-checked

| Finding (analysis) | Result | Evidence |
|---|---|---|
| Four-stage `WorkflowProcessor` pipeline with bounded blocking queues; back-pressure reaches loaders, proxy and snippet loading in search threads | **Confirmed** | `Switchboard.java:1032-1078`, `WorkflowProcessor.java:67,186`, `CrawlQueues.java:777`, `TextSnippet.java:384` |
| `storeDocumentIndex` single-threaded; Solr add under `synchronized(server)` with delete+commit retry; webgraph retry up to 20 × 1 s | **Confirmed** | `Switchboard.java:1032-1045`, `SolrServerConnector.java:209-250`, `Segment.java:654-663` |
| LLM read timeout 0, no concurrency limit | **Confirmed** | `LLM.java:269-270` |
| `Segment.putDocument` swallows `IOException` | **Confirmed** | `Segment.java:581-587` |
| Deletions by query without ID lists; bypasses of `Fulltext.remove` | **Confirmed** | `Fulltext.java:447-540`, `IndexDeletion_p.java:185/215/242/269`, `ErrorCache.java:67`, `RecrawlBusyThread.java:326`, `CollectionConfiguration.java:1532` |
| `ErrorCache.push` replaces a status-200 document with a fail document of the same ID | **Confirmed** | `ErrorCache.java:116-122`; fail types `fail`/`excl` in `FailType.java:25-26` |
| Partial update = `add` with `set`; `_version_` reset to 0; atomic update reindexes the full document | **Confirmed** | `AbstractSolrConnector.java:610-642`, `SolrServerConnector.java:211`, `schema.xml:58` |
| `toSolrInputDocument` drops fields that are not enabled; `process_sxt` is cleared by postprocessing | **Confirmed** | `SchemaConfiguration.java:81-89`, `CollectionConfiguration.java:1490` |
| Solr conf is rewritten from `defaults/solr` on every start, for both cores | **Confirmed** | `EmbeddedInstance.java:82-83,161-179` (Guava `Files.copy` overwrites) |
| Full index clear | **Detail added:** `deleteByQuery("*:*")` per core plus commit | `Fulltext.java:257-273`, `SolrServerConnector.java:133-144` |
| MVP polling on `load_date_dt` (analysis 2.B) | **Rejected.** `load_date_dt` is set only by `yacy2solr`. Imports, reindex, surrogates and P2P keep old dates. | `CollectionConfiguration.java:1038-1042`; writers: `JsonListImporter.java:127`, `ReindexSolrBusyThread.java:144`, `Switchboard.java:2240`, `Protocol.java:889,1521` |
| New Solr fields `entity_id_sxt`/`graph_sig_l` with versioned write-back (analysis 4.2/4.3) | **Rejected.** No write-back at all ([5.6](#56-no-solr-write-back)). | — |
| Named graph per document in a separate RDF server (analysis 4.6) | **Rejected** in favour of SQLite ([3](#3-storage-backend-decision)) | — |
| Resolve delete-by-query IDs inside the update processor (analysis 4.4) | **Replaced** by an epoch plus a debounced reconcile, so no search runs inside Solr's update lock | — |
| **New:** Scoutro crawls store no originals | `storeHTCache=off`, `cachePolicy=nocache` | `ScoutroActions.java:744-749` |
| **New:** JSON-LD content is not parsed today | Only microdata `itemtype` and a few `itemprop` values are read; `<script>` content goes to evaluation scores only | `ContentScraper.java:615-690,1011-1020,1051` |
| **New:** `getDocumentById` is a normal search | Visible only after a soft commit (`autoSoftCommit` 5 s); no real-time get | `AbstractSolrConnector.java:574-598`, `solrconfig.xml:317-330` |
| **New:** chat collection is chosen by the client | Any admitted client (local, guest, admin) may pick any collection in content RAG | `RAGProxyServlet.java:215-226,307`, `LLMAccess.java:88-95` |

### 1.3 Existing functions the graph builds on

- **Access model.**
  - Admin API under `/scoutro/api/v1/*`: Digest, `requireAdmin`, optional single `collection` restriction (`SeoAnalysis.adminCollections`, `SeoAnalysis.java:125-130`).
  - Agent API under `/scoutro/api/agent/v1/*`: Bearer tokens, a grant registry (`AgentActionRegistry`), scopes enforced as `fq` on `collection_sxt` (`ScoutroActions.collectionFilter`, `ScopedActions.filterCollections`), audit.
  - Mutating admin calls require JSON, same origin and at most 16 KiB (`ScoutroApiServlet.jsonBody`).
  - Errors use `{"error":{"code","message","details"}}`. Pagination is `offset`/`limit`/`total`.
- **Index Browser** (main): URL table via `GET /v1/index/browse`. Domain view and export on the open branch.
- **SEO analysis**: `ScoutroSEO_p.html` with tabs and `/v1/seo/*`, collection `fq` on every query. It auto-starts from `?host=&collection=`.
- **Chat/RAG**: `RAGProxyServlet` → `RagRetriever.retrieve` → `RagContext.build` → `ToolCallProtocol`. It streams `scoutro-sources` and `scoutro-citations` metadata. Web content is wrapped in `PromptGuard` `DATA-<nonce>` blocks.
- **LLM routing**: `ai.production_models` rows with usage flags (`LLM.LLMUsage`, `LLM.java:66-75`), edited in `LLMSelection_p.html`.
- **Runtime start**: `ScoutroApiServlet.init()` starts `CaptureRuntime` (`ScoutroApiServlet.java:260-262`, `load-on-startup` 1 in `defaults/web.xml:154`); `destroy()` stops it (`:266-269`).
- **Disk control in YaCy**: `ResourceObserver` measures `DATA` (`File.getUsableSpace`) and pauses crawls below `resource.disk.free.min.steadystate` (4096 MB in `yacy.init:1266`, 2048 MB undershot).

## 2. Principles and non-goals

- **Solr stays the source of page text.** The graph stores objects, statements, source references and bounded excerpts (≤ 200 characters by default). It is not a second full-text archive. No embeddings, no vector index.
- **Nothing graph-related runs in the crawl or index path.** The only code on that path is the update processor's constant-time ID insert ([5.1](#51-change-capture)). LLM calls and graph transactions run on Scoutro threads.
- **Small YaCy footprint:**
  - one additive parser/schema change (JSON-LD capture);
  - one `solrconfig.xml` chain;
  - one new LLM usage flag and an optional per-call read timeout in `LLM.java`.

  Everything else lives in `net.yacy.scoutro.knowledge` and one class in `net.yacy.ai.rag`.
- **Exactly one backend.** No storage abstraction for several databases.
- **Fail closed:**
  - an unknown schema version, a damaged database or a missing native library disables the graph with a visible reason;
  - search, crawling and chat continue without it.
- **Privacy:** persons are not an entity type in this version. Imprint data such as managing directors is not turned into entities. Excerpts are kept minimal.
- **Non-goals:**
  - portal publishing, or any "already listed in portal" status;
  - manual merge/split UI;
  - microdata/RDFa trees (beyond what YaCy already parses);
  - remote Solr;
  - the Olares rollout.

## 3. Storage backend decision

### 3.1 Required queries

1. Entity by ID, including redirects after a merge.
2. Entities by name prefix or term, by type, by host, by collection. Paginated.
3. Outgoing and incoming statements of an entity, by predicate. Paginated. Depth 1, rarely 2.
4. Evidence of a statement. Paginated, filtered by the viewer's collections.
5. All evidence of one source document, for transactional replacement and deletion.
6. Change feed since a cursor; full export in keyset pages.
7. Counts for the dashboard and per-collection visibility checks.

None of these needs graph algorithms or deep traversal. All of them need secondary indexes, transactions and filtered pagination.

### 3.2 Candidates

| Criterion | **SQLite (embedded)** | H2 (embedded, pure Java) | Jena TDB2 (embedded RDF) | Oxigraph (RDF server) | Third Solr core |
|---|---|---|---|---|---|
| Multi-row ACID transaction per document | yes (WAL) | yes | yes | yes | **no** (no multi-document transactions) |
| Space after deletion | freed pages reused at once; `incremental_vacuum` shrinks the file without a full copy (measured, [7.9](#79-measurements-so-far)) | MVStore compaction, file growth | append-only; space comes back only through compaction into a new copy (≈ 2× space) | RocksDB compaction needs headroom | segment merges; `expungeDeletes` needs headroom |
| Index overhead | only the indexes we define | only defined | every quad in several permutation indexes | several permutation indexes | inverted index per field |
| Hard size cap | `max_page_count` (main file) | none built in | none | none | none |
| Backup/restore | `VACUUM INTO`, single file | `BACKUP TO` | file copy while closed / backup API | dump/load | Solr snapshot |
| Java integration | JDBC, ~12 MB jar with native libraries (glibc and musl, x86_64 and aarch64) | JDBC, pure Java | large dependency tree in the same JVM | separate process and container, HTTP | already present |
| Server/Olares operation | inside the existing container and `DATA` | same | same | **second container**, chart change | inside Solr; shares commit cycle and heap |
| Reliability record | very high, extensively tested atomic commit | occasional corruption reports after crashes (MVStore) | good | good | good, but no transactions |

### 3.3 Decision

**SQLite**, embedded via `org.xerial:sqlite-jdbc` (Apache-2.0; SQLite itself is public domain; compatible with GPL-2.0-or-later). The reasons:

1. Transactional document-scoped replacement is a hard requirement. A Solr core cannot provide it.
2. Deletions must give space back without needing extra space first. SQLite reuses free pages immediately and can shrink incrementally. TDB2 and RocksDB need compaction headroom.
3. Only SQLite offers an engine-enforced cap on the main file (`max_page_count`). That is the closest thing to a real limit inside the application.
4. It runs in the existing container and `DATA` volume. Olares needs no second container, port or path.
5. RDF permutation indexes multiply the storage cost of every statement. The needed queries ([3.1](#31-required-queries)) are relational.

Consequences:

- A native library is loaded from the JVM temp directory. If that fails, the graph is disabled with reason `native_library_unavailable`.
- The release image is amd64/glibc and is covered by the bundled natives.
- All SQLite temp files go to `DATA/SCOUTRO/knowledge/tmp/` (`temp_store=FILE` plus temp directory), so they count against the budget and sit on the same filesystem as `DATA`.

Connection settings:

- `journal_mode=WAL`;
- `synchronous=FULL` (change-feed sequence numbers must never roll back after a power loss);
- `foreign_keys=ON`;
- `auto_vacuum=INCREMENTAL` (set before the first table);
- `journal_size_limit=64 MiB`;
- `wal_autocheckpoint=1000`;
- `busy_timeout=5000`;
- `cache_size` 8 MiB per connection, `mmap_size=0`.

One writer connection, guarded by a lock; 2 to 4 reader connections.

## 4. Data model

### 4.1 Logical model

| Part | Content | Rule |
|---|---|---|
| Entity | Public ID, type, status (`active`/`merged`), redirect target | ID never changes. The display name is derived from visible `name` statements. |
| Identity key | (scheme, scope, value) → entity | Strong keys have a global scope. Site keys are scoped to the registrable domain. |
| Statement | Subject entity, predicate, object (entity or typed literal), aggregates | Unique per (subject, predicate, object key). Merged across sources. |
| Evidence | (statement, source document): kind, extractor, locator, excerpt, certainty | At most one row per statement and document. Replaced per document in one transaction. |
| Source document | Solr `id`, state, content token, input hash, generation, collections, host, URL, load date | One row per tracked document of an enabled collection. |
| Extraction | Cached raw result per cache key; extractor registry | Optional; evicted LRU within budget. |
| Change | Coalesced per object, monotonic sequence, scope before and after | Bounded retention. |

Entity types in v1:

- `organization` (company, operator, provider)
- `facility` (facility, branch)
- `site` (address-bearing location)
- `place` (municipality, district, region)
- `service` (service, offering)

Predicates in v1 (from a versioned vocabulary file):

- `name`, `alias`, `legal_form`
- identifiers: `identifier:register`, `identifier:vat`, `identifier:lei`, `identifier:wikidata`, `identifier:ik`
- relations: `operates` (organization → facility), `part_of` (organization → organization), `located_at` (organization/facility → site), `in_place` (site → place), `offers` (organization/facility → service)
- values: `address`, `postal_code`, `locality`, `geo`, `phone`, `email`, `website`, `opening_hours`

Each predicate is flagged as functional (single-valued) or not. The vocabulary is extensible without a schema change. New industries add types, predicates and service terms.

### 4.2 Physical schema v1 (draft)

Final DDL ships in PR 1, together with the storage benchmark. Until the first release, v1 is a draft: development databases are recreated when it changes.

```sql
CREATE TABLE kg_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
  -- schema_version, dataset_epoch, clean_shutdown, backfill_cursor, backfill_signature,
  -- version_checkpoint, reconcile_required, last_reconcile_at, storage_error
CREATE TABLE kg_collection (coll_id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
CREATE TABLE kg_vocab (term_id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, name TEXT NOT NULL,
  functional INTEGER NOT NULL DEFAULT 0, UNIQUE (kind, name));            -- types, predicates, schemes
CREATE TABLE kg_extractor (ext_id INTEGER PRIMARY KEY, tier INTEGER NOT NULL, name TEXT NOT NULL,
  version TEXT NOT NULL, model TEXT, prompt_hash TEXT, UNIQUE (tier, name, version, model, prompt_hash));

CREATE TABLE kg_doc (                         -- every tracked Solr document of an enabled collection
  doc_rowid  INTEGER PRIMARY KEY,
  doc_id     BLOB NOT NULL UNIQUE,            -- Solr id, 12 Base64 chars decoded to 9 bytes
  state      INTEGER NOT NULL,                -- 1 active, 2 unavailable, 3 gone, 4 expired
  token      BLOB NOT NULL,                   -- 8 bytes, see 5.2
  input_hash BLOB,                            -- 16 bytes, hash of the extraction input used
  generation INTEGER NOT NULL,                -- +1 on every lifecycle change (CAS for publish)
  tiers      INTEGER NOT NULL DEFAULT 0,      -- bitmask of applied tiers
  host_id TEXT, url TEXT,                     -- only filled when the document has evidence
  loaded_at INTEGER, state_since INTEGER, processed_at INTEGER, last_error INTEGER);
CREATE INDEX kg_doc_host ON kg_doc (host_id) WHERE host_id IS NOT NULL;
CREATE TABLE kg_doc_collection (doc_rowid INTEGER NOT NULL, coll_id INTEGER NOT NULL,
  PRIMARY KEY (doc_rowid, coll_id)) WITHOUT ROWID;

CREATE TABLE kg_entity (ent_rowid INTEGER PRIMARY KEY, public_id BLOB NOT NULL UNIQUE,
  type INTEGER NOT NULL, status INTEGER NOT NULL, merged_into INTEGER, created_seq INTEGER NOT NULL);
CREATE TABLE kg_entity_key (scheme INTEGER NOT NULL, scope TEXT NOT NULL, value TEXT NOT NULL,
  ent_rowid INTEGER NOT NULL, PRIMARY KEY (scheme, scope, value)) WITHOUT ROWID;
CREATE INDEX kg_entity_key_ent ON kg_entity_key (ent_rowid);
CREATE TABLE kg_redirect (public_id BLOB PRIMARY KEY, target_rowid INTEGER NOT NULL,
  kind INTEGER NOT NULL) WITHOUT ROWID;                                  -- merged entities/statements

CREATE TABLE kg_statement (stmt_rowid INTEGER PRIMARY KEY, public_id BLOB NOT NULL UNIQUE,
  subj INTEGER NOT NULL, pred INTEGER NOT NULL, obj_ent INTEGER, obj_val TEXT, obj_key BLOB NOT NULL,
  quality INTEGER NOT NULL, current_sources INTEGER NOT NULL, first_seen INTEGER NOT NULL,
  last_confirmed INTEGER, UNIQUE (subj, pred, obj_key));
CREATE INDEX kg_statement_obj ON kg_statement (obj_ent) WHERE obj_ent IS NOT NULL;
CREATE TABLE kg_evidence (stmt_rowid INTEGER NOT NULL, doc_rowid INTEGER NOT NULL,
  kind INTEGER NOT NULL, ext_id INTEGER NOT NULL, certainty INTEGER NOT NULL,
  locator TEXT, excerpt TEXT, observed_at INTEGER NOT NULL,
  PRIMARY KEY (stmt_rowid, doc_rowid)) WITHOUT ROWID;
CREATE INDEX kg_evidence_doc ON kg_evidence (doc_rowid, stmt_rowid);

-- derived visibility and lookup tables, maintained in the publish transaction
CREATE TABLE kg_stmt_scope (stmt_rowid INTEGER NOT NULL, coll_id INTEGER NOT NULL, n INTEGER NOT NULL,
  PRIMARY KEY (stmt_rowid, coll_id)) WITHOUT ROWID;
CREATE TABLE kg_entity_scope (ent_rowid INTEGER NOT NULL, coll_id INTEGER NOT NULL, n INTEGER NOT NULL,
  PRIMARY KEY (ent_rowid, coll_id)) WITHOUT ROWID;
CREATE INDEX kg_entity_scope_coll ON kg_entity_scope (coll_id, ent_rowid);
CREATE TABLE kg_host_entity (host_id TEXT NOT NULL, ent_rowid INTEGER NOT NULL, n INTEGER NOT NULL,
  PRIMARY KEY (host_id, ent_rowid)) WITHOUT ROWID;
CREATE VIRTUAL TABLE kg_name_fts USING fts5 (name, content='', contentless_delete=1,
  tokenize='unicode61 remove_diacritics 2');
  -- rowid = stmt_rowid of name/alias statements

CREATE TABLE kg_change (seq INTEGER PRIMARY KEY AUTOINCREMENT, kind INTEGER NOT NULL,
  public_id BLOB NOT NULL, op INTEGER NOT NULL, redirect_to BLOB, scopes_before TEXT,
  scopes_after TEXT, at INTEGER NOT NULL, UNIQUE (kind, public_id));   -- coalesced: DELETE + INSERT

CREATE TABLE kg_work (doc_id BLOB PRIMARY KEY, reason INTEGER NOT NULL, priority INTEGER NOT NULL,
  not_before INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, claimed_at INTEGER) WITHOUT ROWID;
CREATE INDEX kg_work_next ON kg_work (priority, not_before);
CREATE TABLE kg_extraction (cache_key BLOB PRIMARY KEY, ext_id INTEGER NOT NULL, status INTEGER NOT NULL,
  result BLOB, bytes INTEGER NOT NULL, last_used INTEGER NOT NULL) WITHOUT ROWID;   -- deflated JSON
CREATE INDEX kg_extraction_lru ON kg_extraction (last_used);
CREATE TABLE kg_event (seq INTEGER PRIMARY KEY, at INTEGER NOT NULL, level INTEGER NOT NULL,
  code TEXT NOT NULL, detail TEXT);                                       -- ring, at most 10 000 rows
```

Notes:

- **Sources are paginated and never grow inside one field.** A statement's sources are `kg_evidence` rows, read with keyset pagination.
- **Identical content stays separated by origin.** Two documents with identical text share one cache row (`kg_extraction`). They still produce separate `kg_evidence` rows, and therefore separate document and collection assignments.
- **Integer row IDs stay internal.** Public IDs are separate and stable ([4.3](#43-identity-resolution)).

### 4.3 Identity resolution

**Public IDs.**

- Entity: `kge_` + 20 base32 characters (100 bits) of SHA-256 over `scoutro-kg/entity/v1`, the type and the *primary key at creation*. Statement: `kgs_` + 20 characters over `scoutro-kg/statement/v1`, the subject ID at creation, the predicate and the canonical object.
- Both are stored and never recomputed.
- A rebuild from the same data yields the same IDs in the normal case. Order effects are possible; backups preserve exact IDs.

**Key schemes.**

| Strength | Schemes | Merge rule |
|---|---|---|
| Strong (global) | `register` (court + HRA/HRB/VR/GnR/PR, normalised), `vat` (validated format), `lei`, `wikidata`, `ik` | Equal value → same entity, across domains |
| Site-scoped | `ld_id` (absolute JSON-LD `@id`, scoped to its host), `site_name` (registrable domain + type + normalised name, plus postal code if present) | Equal key inside one registrable domain → same entity |
| Medium | `homepage` (registrable domain of the declared `url` of an organisation) | Merges across domains only if the normalised names are compatible (legal form stripped) and no strong key conflicts |
| Hints only | phone (E.164), e-mail, name + postal code | Never merge. Shown as "possible duplicates" in the detail view. |

**Rules.**

- The primary key at creation is the strongest key known at that time.
- A mention without a postal code attaches to an existing site-scoped entity only if exactly one candidate with that name exists in the domain. Otherwise it stays a separate entity.
- **Merge:** when a new strong key links two entities, the older one survives.
  - The other entity gets `status=merged` and a `kg_redirect` row.
  - Its statements are re-pointed to the survivor. Statements that collide (same predicate and object) are merged; their evidence is unioned and the losing statement ID becomes a redirect.
  - A `redirect` change is written.
- **No automatic split.** If the evidence that justified a merge disappears, the merge stays. The key row remains as an identity fact.
- **Recovery:** an admin maintenance action "re-resolve identities" rebuilds the resolution from the cached extractions without calling an LLM.
- Same-name organisations on different domains without a strong or medium key stay separate. One domain can hold any number of facilities, distinguished by name and postal code.

### 4.4 Quality and currency

**Document states:**

| State | Trigger |
|---|---|
| `active` | Solr document exists, `httpstatus_i=200`, in an enabled collection |
| `unavailable` | Fail document with `failtype_s=fail`, except HTTP 404/410 (temporary failure). The evidence stays current until `unavailableGraceDays` (default 14) has passed. |
| `expired` | Grace exceeded |
| `gone` | `failtype_s=excl`, or HTTP 404/410. The evidence is no longer current at once. |
| Removed | No Solr document, or out of scope. The evidence is deleted at once. |

- **Current evidence:** the document is `active`, or `unavailable` within the grace period, and its `loaded_at` is at most `maxAgeDays` old (default 365).
- **Statement quality:**

| Quality | Condition |
|---|---|
| `supported` | ≥ 1 current evidence with a verified locator |
| `uncertain` | Current evidence exists, but only with `certainty=hedged` or only from `unavailable` documents |
| `conflicting` | Functional predicate with more than one distinct currently supported object for the same subject. All of these statements are flagged. |
| `stale` | No current evidence. Hidden by default (`include=stale`), purged after `staleRetentionDays` (default 90). |

- When the last evidence row is deleted, the statement is deleted and a `delete` change is written.
- A model confidence value is stored with LLM evidence for diagnostics. It never replaces evidence or quality.
- `last_confirmed` = latest `loaded_at` over current evidence. It is shown everywhere, so the age of a statement is visible.

### 4.5 Visibility

- A viewer has a collection set:
  - admin: all, or the single `collection` request parameter;
  - agent: its scope, or all with `allCollections`;
  - chat: see [8.4](#84-chat).
- **Evidence** is visible if its document has a collection in that set.
- A **statement** is visible if it has visible evidence (`kg_stmt_scope`).
- An **entity** is visible if it has a visible statement (`kg_entity_scope`).
- Names, aliases, identifiers, counts, quality and `last_confirmed` are computed **only over visible evidence**. A consolidated view never reveals a statement, count or name from an invisible collection.
- A redirect is followed only if the target is visible. Otherwise the result is `not_found`.
- The change feed emits `delete` for a viewer only if the object was visible to it before (`scopes_before`) and is not visible after.
- Documents without a collection are not tracked (scoped agents cannot see them in Solr either).

## 5. Synchronisation with Solr

### 5.1 Change capture

**Update processor.** `net.yacy.scoutro.knowledge.solr.KgCaptureProcessorFactory` is added to `defaults/solr/solrconfig.xml` as the default update chain:

```xml
<updateRequestProcessorChain name="scoutro-kg" default="true">
  <processor class="solr.LogUpdateProcessorFactory"/>
  <processor class="solr.DistributedUpdateProcessorFactory"/>
  <processor class="net.yacy.scoutro.knowledge.solr.KgCaptureProcessorFactory"/>
  <processor class="solr.RunUpdateProcessorFactory"/>
</updateRequestProcessorChain>
```

- It records after a successful `super.process*()`:
  - add → `dirty(id)`;
  - delete by ID → `deleted(id)`;
  - delete by query → `queryDeleteEpoch++`, plus a `full_reset` marker for `*:*`.
- It is a no-op on the `webgraph` core (both cores share the file) and when the graph is disabled.
- Every `Throwable` is caught: the processor can never fail a Solr update.
- Cost per update is one bounded `ConcurrentHashMap` insert.
- It covers every writer of the embedded core: `Segment.storeDocument`, `ErrorCache.push`, `JsonListImporter`, surrogates, `ReindexSolrBusyThread`, P2P `putMetadata`, `/solr/collection1/update` and all deletes, including the bypasses listed in 1.2.

**Dirty set.**

- Coalesced per ID (last operation wins), capped at `capture.maxPending` (default 100 000 IDs, a few MB).
- On overflow, new IDs are dropped and `reconcile_required` is set.

**Sync thread.**

- Every 2 s it drains the dirty set into `kg_work`, in batches of ≤ 500 IDs per transaction.
- In the same transaction it increments `kg_doc.generation` for these IDs, which invalidates in-flight work ([5.5](#55-stale-results-and-ghost-documents)).
- While paused for budget, `kg_work` is capped at `queue.maxItems`. Beyond that, `reconcile_required` is set instead of growing.

**Remote Solr** has no update processor. The graph requires the embedded Solr and refuses to start otherwise (reason `remote_solr_unsupported`).

### 5.2 Processing a document

1. **Claim.** Take the next `kg_work` row (priority, `not_before`). Remember `generation` and the dirty-set epoch.
2. **Read metadata from Solr.**
   - Fields: `id, sku, host_s, host_id_s, httpstatus_i, failtype_s, exact_signature_l, collection_sxt, language_s, load_date_dt, ld_json_txt` (no `text_t`). Wait at least 6 s after the event so the soft commit has made it visible.
   - Not found → document removed.
   - No enabled collection → removed (out of scope).
   - Fail document → state change ([4.4](#44-quality-and-currency)).
3. **Token.** 8 bytes of SHA-256 over `sku | httpstatus_i | exact_signature_l | sorted(collection_sxt) | language_s`.
   - Token and `ld_json` hash unchanged → only update `loaded_at`. No extraction.
   - Only collections changed → update `kg_doc_collection` and the scope tables. No extraction.
4. **Extract** the needed tiers ([6](#6-extraction)). `text_t` is read only for tier 2/3 candidates. The cache is consulted first.
5. **Publish** in one write transaction ([5.4](#54-transactional-publish)).

### 5.3 Reconcile and catch-up

- **Full reconcile.**
  - Streams Solr `id`, plus the token fields, for `httpstatus_i:*` documents of the enabled collections.
  - Sorted by `id`, paged with `id:{last TO *]` (the pattern used for domain export on the open branch).
  - Merge join against `kg_doc` sorted by `doc_id`:

| Case | Action |
|---|---|
| Missing in graph | Enqueue |
| Missing in Solr | Remove |
| Token differs | Enqueue |

  - Runs daily (`reconcile.hour`, default 03:00) and when `reconcile_required` is set: dirty-set or queue overflow, collection allowlist change, unclean start.
  - Runs 5 minutes after the last delete by query (debounced).
  - Gated like the backfill. Resumable from the last `id`.
  - Cost is O(N) in the number of documents, over light fields only.
- **Catch-up by `_version_`.**
  - Every 60 s, once the dirty set is drained, the sync thread stores `version_checkpoint` = the highest Solr `_version_` observed at least 30 s earlier.
  - After an unclean stop (`clean_shutdown=false`), every ID with `_version_ ≥ version_checkpoint` is enqueued immediately (indexed `_version_`, `schema.xml:58`). A full reconcile is then scheduled, because deletions are not visible by version.
- **Initial backfill.**
  - The same scan as reconcile with an empty graph, cursor in `kg_meta.backfill_cursor`.
  - Interruptible and resumable; one rule set for new and existing documents.
  - JSON-LD exists only for documents indexed after the capture was enabled ([6.2](#62-json-ld-capture-the-only-yacy-core-change)). Older documents get it on recrawl (Scoutro crawls reload after 3 days by default, `ScoutroActions.java:750-752`).
- **Extractor version change.** Documents whose tier result has an older extractor ID are re-queued at low priority. The LLM tier is re-run only by explicit admin action or within the per-host cap.

### 5.4 Transactional publish

One `BEGIN IMMEDIATE` transaction per document, with these steps:

1. Check that `kg_doc.generation` still has the claimed value and that the ID is not in the dirty set. Otherwise abort and requeue.
2. Delete this document's evidence for the replaced tiers, and collect the affected statements.
3. Resolve entities and keys; upsert statements; insert evidence (bounded: ≤ 50 statements per document, excerpt ≤ 200 characters).
4. Recompute the aggregates of the affected statements and entities: `current_sources`, quality, conflicts, `kg_stmt_scope`, `kg_entity_scope`, `kg_host_entity`, FTS rows. Delete statements without evidence.
5. Write coalesced `kg_change` rows with scopes before and after.
6. Update `kg_doc`: token, input hash, tiers, `processed_at`, `generation+1`.

Lifecycle events (remove, state change) use the same aggregate code in batches of ≤ 200 documents.

### 5.5 Stale results and ghost documents

- **Generation CAS.** Every lifecycle event for a document increments its generation in the same transaction that queues it. A worker result for an older generation is discarded at commit.
- **Dirty-set check under the write lock.** Changes not yet drained are still in the dirty set. The update processor writes them synchronously inside Solr's update path, so a Solr change made before the commit cannot slip through.
- **Delete by query.**
  - If `queryDeleteEpoch` changed since the claim, the worker waits for the soft-commit interval plus 1 s and checks again that the document exists before publishing.
  - The debounced reconcile removes the rest.
- **No ghost documents in Solr.** The graph never writes to Solr.
- **No ghost documents in the graph.** Publish requires an existing `kg_doc` row in a non-removed state and the right generation. A crash after a Solr delete is repaired by catch-up and reconcile.

### 5.6 No Solr write-back

The release plan asks to prove any needed write-back. None is needed:

- Every graph read (UI, API, chat) is served from SQLite.
- Graph-driven page lists use Solr `id:(…)` lookups with ≤ 100 IDs plus the viewer's collection `fq`.

Writing back would run into all four problems in 1.2: no optimistic concurrency because `_version_` is reset, a full reindex per atomic update, ghost documents on deleted IDs, and fields dropped on rewrite. The only Solr change is the parse-time `ld_json_txt` field. It is written by `yacy2solr` together with the document, so it can never be stale.

### 5.7 Delete and change paths

| Path (code) | Solr operation | Graph reaction | Lag |
|---|---|---|---|
| `Fulltext.remove(id/ids)` (Crawler_p, `Switchboard.remove`, `CrawlStacker`, `IndexControlURLs_p`, `URIMetadataNode`, `Segment.removeAllUrlReferences`) | delete by ID | Document removed, evidence deleted | seconds |
| `RecrawlBusyThread.java:326`, postprocessing `failids` | delete by ID (bypass) | same (captured in Solr) | seconds |
| `Fulltext.deleteStaleDomain*`, `deleteOldDocuments`, `deleteDomainErrors`, `IndexDeletion_p` queries, `ErrorCache.clear` | delete by query | Epoch++, debounced reconcile | ≤ 5 min + reconcile run |
| `clearLocalSolr` / `connector.clear()` | `*:*` | Full reset: new `dataset_epoch`, database recreated, cursors invalid (`epoch_changed`) | seconds to minutes |
| `ErrorCache.push` over a status-200 document | add (fail doc) | `unavailable` or `gone` | seconds |
| Recrawl/reindex (`yacy2solr`) | add | Token check: nothing, scope update or re-extraction | seconds plus queue |
| Collection change of a document | add | Scope update; out of scope → removed | seconds |
| Allowlist change in the graph settings | — | Reconcile | reconcile run |
| Imports, surrogates, P2P, `/solr/collection1/update` | add/delete | as above | seconds |
| Crash between Solr commit and drain | — | `_version_` catch-up plus reconcile | after restart |

**Read path.** Detail and evidence views, and chat facts, check their (≤ 50) source documents against Solr in one `id:(…)` query and hide missing ones (and enqueue them). Lists and aggregates may lag. The lag is shown as `pending`, `oldest_pending_age_s` and `reconcile_pending` in every list response and on the status page.

## 6. Extraction

### 6.1 Tiers

| Tier | Input | Output | Cost |
|---|---|---|---|
| 1 Structured | `ld_json_txt`, `opengraph_*`, `publisher_t`/`publisher_url_s`, `coordinate_p`, `canonical_s`, `title`, `description_txt` | schema.org `Organization`/`LocalBusiness` and subtypes, `Place`, `PostalAddress`, `GeoCoordinates`, `ContactPoint`, `Service`/`Offer`/`OfferCatalog`, `sameAs`, `vatID`, `leiCode`, `@id` | ms, no text read |
| 2 Rules | `text_t` of candidate pages (imprint, contact, about, site, location, service paths and titles; home page; pages where tier 1 found entities) | Register number plus court, VAT ID, phone, e-mail, postal address, legal form, organisation name near these markers | ms, one text read |
| 3 LLM | `text_t` (≤ 12 000 characters, chunked), title, host | Relations (`operates`, `offers`, `located_at`, `part_of`), facilities and services the earlier tiers missed | seconds to minutes |

- All tiers write the same statement model. Equal statements merge, and their evidence keeps the kind (`jsonld`, `metadata`, `rule`, `llm`).
- Persons are ignored in every tier.
- The LLM tier runs only:
  - for collections listed in `llm.collections`;
  - on candidate pages;
  - for at most `llm.maxDocsPerHost` (default 25) documents per host and extractor version, by priority (imprint, home page, service pages first).

  This keeps the LLM work bounded by the number of hosts, not pages.

### 6.2 JSON-LD capture (the only YaCy core change)

- **Where:** in `ContentScraper` (script branch, `ContentScraper.java:1011-1020`), `<script type="application/ld+json">` content is collected, bounded to 8 blocks and 32 KiB in total.
  - A block is kept only if it parses as JSON (depth ≤ 8, ≤ 500 nodes) and contains a relevant `@type`.
  - It is exposed through `Document` and written by `CollectionConfiguration.yacy2solr` into a new `CollectionSchema` entry `ld_json_txt`.
- **Field type:** text, multi-valued, **stored, not indexed**. Declared explicitly in `defaults/solr/schema.xml`, so it adds no inverted index. The name matches the old schema's dynamic `*_txt`, which keeps rollback safe ([13](#13-migration-backup-rollback)).
- **Default:** disabled in `defaults/solr.collection.schema`. Enabling the graph enables the field through the existing schema configuration (the same mechanism the crawl report uses for optional fields). Installations without the graph pay nothing.
- **Cost:** part of the Solr index, not of the graph budget. It is measured in package 5 (stored-field bytes with and without the field) and shown on the status page.

### 6.3 LLM use

- **Routing:** new usage `LLM.LLMUsage.knowledge` (`LLM.java:66-75`), selectable as a column in `LLMSelection_p.html`. Same rows, keys and authentication as all other usages; no second LLM administration.
- **Time limits:** a new optional per-call read timeout in `LLM.chat` (default unchanged for existing callers). For extraction: connect 10 s, read `llm.timeoutSeconds` (default 120).
  - At most `llm.maxAttempts` (2) per document and extractor version, with backoff.
  - After that, `last_error` is recorded and the document is retried only on a content or extractor change, or on an admin retry.
- **Circuit breaker:** after `llm.breakerFailures` (3) consecutive failures the LLM tier pauses (5 min, doubling up to 60 min) with a visible reason. Tiers 1 and 2 continue. A hanging model blocks only the single LLM worker thread; crawler and indexer share nothing with it.
- **Untrusted input:**
  - page text goes into `PromptGuard` `DATA-<nonce>` blocks;
  - no tools are offered;
  - the response must match a strict JSON schema; size ≤ 64 KiB; ≤ 40 claims per chunk; strings ≤ 300 characters;
  - only vocabulary types and predicates are accepted;
  - every claim needs a quote ≤ 200 characters that occurs **verbatim** (whitespace-normalised) in the input text. Otherwise it is dropped.
  - The counts of invalid answers are visible.
- **Concurrency:** `llm.parallel` (default 1, maximum 2).

### 6.4 Cache

- **Key:** SHA-256 over:
  - the extractor ID (tier, name, version, model, prompt hash);
  - the input hash (the exact text and JSON-LD sent to the tier);
  - the context: registrable domain and language.

  The same text on another domain is extracted again, because "we" and the organisation name depend on the site.
- **Value:** the raw, unresolved extraction result (deflated JSON, ≤ 64 KiB). Resolution into entities runs per document in its own context. Shared extraction therefore never mixes identities or origins.
- **Eviction:** LRU within `cache.maxPercent` of the budget. The cache is optional: losing it only costs recomputation.

### 6.5 Scheduling and gating

**Threads** (daemon, minimum priority, named `ScoutroKG.*`):

| Thread | Work |
|---|---|
| `sync` | drain, lifecycle events |
| `extract` | tiers 1 to 3 |
| `maintenance` | reconcile, backfill, retention, vacuum, backups; one job at a time |

**Gates** for backfill, reconcile and extraction:

- indexing queue below `gate.maxIndexingQueue` (20; `Switchboard.getIndexingProcessorsQueueSize`);
- system load below `gate.maxLoad` (2.5);
- free heap above `gate.minFreeHeapMB` (256);
- not paused ([7.5](#75-pause-and-resume)).

Solr scans additionally yield to `Switchboard.onlineCaution()`. Lifecycle events are never gated, only rate-limited.

**Memory:**

| Item | Bound |
|---|---|
| Dirty set | ≤ 100 000 IDs |
| Batches | ≤ 500 IDs |
| Text | one document at a time (≤ 12 000 characters to the LLM) |
| SQLite cache | 8 MiB per connection |

The graph heap target is ≤ 64 MiB. It is measured in package 5. The YaCy default `Xmx600m` must be reviewed for graph deployments (open point O3).

## 7. Storage and resource contract

### 7.1 What is counted

Everything below `DATA/SCOUTRO/knowledge/` counts against the budget:

```
DATA/SCOUTRO/knowledge/
  graph.db  graph.db-wal  graph.db-shm   database, write-ahead log, shared memory
  tmp/                                   SQLite temp files (temp_store_directory)
  backup/                                local backups, if enabled
```

- **Not counted:** the change feed, events, cache and work queue are tables inside `graph.db`, so they are already counted. YaCy logs (rotated, about 20 MiB in total, `defaults/yacy.logging:51-53`) carry graph log lines. Export and download are streamed and create no files.
- **Not part of the graph budget:** the native library in the JVM temp directory (about 1 MB, removed at shutdown) and the `ld_json_txt` stored field in Solr. Both are reported separately.
- **Measured values:**
  - `used_bytes` = sum of all file sizes, from a directory scan every 30 s and before every write batch;
  - `logical_bytes` = (`page_count` − `freelist_count`) × `page_size`;
  - `free_in_file` = `freelist_count` × `page_size`.

### 7.2 Settings

| Setting | Default | Meaning |
|---|---|---|
| `scoutro.kg.budget.maxBytes` | 1 GiB (**provisional**, see O1) | Total budget for the directory above |
| `scoutro.kg.budget.pausePercent` / `resumePercent` | 90 / 80 | Hysteresis for new extraction |
| `scoutro.kg.budget.maintenancePercent` | 20 | Share reserved for WAL, temp files, deletions, migration |
| `scoutro.kg.disk.reserveBytes` | 1 GiB | Extra free space above YaCy's `resource.disk.free.min.steadystate` that the graph leaves on the `DATA` filesystem |
| `scoutro.kg.disk.hysteresisBytes` | 512 MiB | Resume only above reserve + hysteresis |
| `scoutro.kg.queue.maxItems` | 200 000 | Work queue cap; beyond it → `reconcile_required` |
| `scoutro.kg.capture.maxPending` | 100 000 | Dirty-set cap |
| `scoutro.kg.extract.maxStatementsPerDoc` / `maxExcerptChars` / `maxInputChars` | 50 / 200 / 12 000 | Growth per document and LLM input |
| `scoutro.kg.cache.maxPercent` | 20 | Extraction cache share |
| `scoutro.kg.changes.retentionDays` / `maxRows` | 30 / 1 000 000 | Change-feed and delete-notice retention |
| `scoutro.kg.source.unavailableGraceDays` / `goneRetentionDays` / `maxAgeDays` / `staleRetentionDays` | 14 / 7 / 365 / 90 | Currency and purge rules ([4.4](#44-quality-and-currency)) |
| `scoutro.kg.backup.keep` / `intervalDays` | 1 / 7 | Local backups (0 = off) |

The YaCy keys (`resource.disk.free.min.steadystate`, 4096 MB) are read, not changed.

### 7.3 Hard limits inside the application

- **Main file:** `PRAGMA max_page_count` = (budget × (1 − maintenancePercent) − backup allowance) / `page_size`.
  - SQLite then refuses growth with `SQLITE_FULL`.
  - Measured: SQLite rolls the whole transaction back by itself and integrity stays `ok` ([7.9](#79-measurements-so-far)). Code must tolerate "no transaction active" on its own rollback.
  - `SQLITE_FULL` sets the pause reason `budget_exhausted`.
- **This cap does not cover the WAL, temp files or backups.** They are bounded as follows:

| File | Bound |
|---|---|
| WAL | Size ≈ changed pages of the open transaction (measured: one 20 000-document transaction produced an 84 MB WAL). Transactions are limited to one document or ≤ 200/500 lifecycle rows. Checkpoint after every batch; `journal_size_limit` truncates. No read transaction stays open longer than one export page. |
| Temp files | Queries avoid large sorts (keyset pagination, indexes). Growth is monitored. |
| Backups | Count in the budget. With `keep=1` the usable data capacity is roughly half of (budget − maintenance). The settings page says so. |

- **Application budget vs. real quota.** The budget is enforced by monitoring plus the `max_page_count` cap. It is not a filesystem quota.
  - If the operator adds a real quota or a separate volume (for example a dedicated mount below `DATA/SCOUTRO/knowledge`), ENOSPC appears as `SQLITE_FULL`/`SQLITE_IOERR`.
  - The transaction rolls back and the graph pauses with `storage_error`.
  - Resume requires `PRAGMA quick_check` = ok.
  - The graph cannot protect a `DATA` disk that other components fill. It stops early and keeps deletions going, nothing more.

### 7.4 Before every write batch

`allowed = used + estimate(batch) ≤ pausePercent × budget` **and** `usable(DATA) − estimate ≥ steadystate + reserveBytes`.

The estimate is an upper bound from the per-document limits: 50 statements × (row + index + excerpt) + scope rows + change rows, about 64 KiB per document plus WAL. Lifecycle batches (deletions, state changes) may use the maintenance share instead.

### 7.5 Pause and resume

| Reason | Effect | Resume |
|---|---|---|
| `budget` (≥ pausePercent or estimate too large) | No new extraction or backfill. Capture, deletions, state changes, retention and vacuum continue. | used ≤ resumePercent |
| `disk_reserve` | Same | usable ≥ steadystate + reserve + hysteresis |
| `budget_exhausted` (`SQLITE_FULL`) | Same, plus a `storage_error` event | as `budget`, after `quick_check` |
| `storage_error` (`IOERR`, corruption) | All writes stop; reads keep working while `quick_check` passes | admin action after a successful `quick_check` |
| `llm_breaker` | LLM tier only | backoff elapsed |
| `manual` | Like `budget` | admin resume |
| `disabled`, `schema_unsupported`, `native_library_unavailable`, `remote_solr_unsupported` | Graph off; search unaffected | fix + restart |

- Existing data stays readable at the limit.
- Nothing in Solr and no valid statement is deleted to gain space.
- During a pause the queue does not grow beyond `queue.maxItems`. Changes are coalesced per document, and anything beyond that is recovered by reconcile.

### 7.6 Cleanup

- Daily, and whenever usage reaches the pause threshold, these are purged in small transactions:
  - stale statements older than `staleRetentionDays`;
  - evidence of `gone`/`expired` documents older than their retention;
  - change rows outside the retention;
  - cache rows (LRU);
  - event-ring overflow;
  - backups beyond `keep`.
- Afterwards, `incremental_vacuum` returns free pages to the filesystem in chunks.
  - Measured: about 2 400 pages/s.
  - With sqlite-jdbc every `execute` frees only one page, so the loop runs until `freelist_count` is 0 or a time slice ends.
- A full `VACUUM` is never automatic. It is an admin action, allowed only if `usable(DATA) ≥ 2 × logical_bytes + reserve`. Physical shrinking is reported separately from logical deletion.

### 7.7 Backups

- `VACUUM INTO backup/graph-<UTC>.db` produces a compact, consistent snapshot.
- It starts only if the budget and the disk reserve allow `logical_bytes` more. Otherwise it is skipped with a reason.
- Then `quick_check` on the copy; delete beyond `keep`.
- An external target outside `DATA` is not configured by default (the Olares rule is no second `DATA` path, O6).
- Rationale: tiers 1 and 2 can be rebuilt from Solr, so backups mainly protect LLM results and exact IDs.

### 7.8 Measurement method (package 5)

- **Corpus:** a documented copy of representative collections in the test environment (number of hosts, documents, JSON-LD share, LLM collections). Never the production index; nothing is emptied or reconfigured for testing.
- **Metrics:**
  - bytes per tracked document, per document with evidence, per statement and per evidence row;
  - index share and FTS share;
  - WAL and temp peaks during backfill, cleanup and vacuum;
  - cache share;
  - queue growth during a pause;
  - backfill rate, LLM rate, heap high-water mark;
  - Solr stored-field delta for `ld_json_txt`.
- **Projection:** a formula over the measured per-unit costs with stated assumptions and a reserve factor of 1.5. No promise of the form "N pages need X GB".

### 7.9 Measurements so far

A scratch experiment (not committed) used synthetic data from a fixed-seed generator: 25 pages per host, 1 to 4 entities and 6 to 10 statements per page, 240-character excerpts. It ran with sqlite-jdbc 3.53.4.0, a 4 KiB page size and an earlier draft of the 4.2 schema with text IDs. It shows mechanisms, not capacity:

| Measurement | Result |
|---|---|
| 20 000 documents, 154 766 statements, 158 577 evidence rows, 23 685 entities | 83.4 MB (≈ 4.2 KB per document, ≈ 526 B per evidence row) |
| Share by object | evidence 55 %; statement table + 2 unique indexes 26 %; change log 5 % |
| Delete 10 000 documents incl. aggregates | 0.6 s; 46 % of pages free; file unchanged |
| Re-add 10 000 documents | free pages reused, file +4 % |
| `incremental_vacuum` | 9 759 pages (40 MB) returned in 4 s; file 86.9 → 46.9 MB without a copy |
| One transaction with 20 000 documents | WAL 84 MB ≈ database size → small transactions are mandatory |
| `max_page_count` exceeded | `SQLITE_FULL`, automatic rollback, `integrity_check ok`, row count unchanged |

Consequences already applied in 4.2: binary 9-byte document IDs and binary public IDs, excerpt length as the main lever, and a per-document transaction size.

## 8. Interfaces: API, export, chat, UI

### 8.1 JSON contract `scoutro.kg.v1`

**Entity:**

```json
{"id":"kge_…","type":"organization","name":"…","aliases":["…"],
 "identifiers":[{"scheme":"register","value":"HRB 12345 B, AG Charlottenburg","quality":"supported"}],
 "quality":"supported","first_seen":"2026-…Z","last_confirmed":"2026-…Z",
 "counts":{"statements":12,"sources":4},"possible_duplicates":["kge_…"]}
```

**Statement:**

```json
{"id":"kgs_…","subject":"kge_…","predicate":"operates",
 "object":{"entity":"kge_…"} | {"value":"…","datatype":"string|phone|email|url|address|geo|date"},
 "quality":"supported|uncertain|conflicting|stale","certainty":"stated|hedged",
 "first_seen":"…","last_confirmed":"…","sources":3}
```

**Evidence:**

```json
{"doc_id":"AbCdEfGhIjKl","url":"…","host":"…","collections":["…"],
 "state":"active|unavailable|gone|expired","loaded_at":"…","observed_at":"…",
 "kind":"jsonld|metadata|rule|llm","extractor":"llm/1 (model, prompt 3f2a…)",
 "locator":"jsonld:0/address/postalCode | text:1234+56","excerpt":"…"}
```

- `collections` lists only the viewer's visible collections.
- **List responses** follow the Scoutro convention (no envelope beyond paging):

  ```json
  {"schema":"scoutro.kg.v1","offset":0,"limit":25,"total":123,"items":[…],
   "as_of":{"epoch":"…","seq":12345},"lag":{"pending":7,"oldest_pending_age_s":12,"reconcile_pending":false}}
  ```

- **Errors** use the existing shape. New codes:

| Code | HTTP |
|---|---|
| `kg_disabled` | 503 |
| `kg_unavailable` | 503, with `details.reason` |
| `not_found` | 404 |
| `cursor_expired` | 410, with `details.full_sync` |
| `epoch_changed` | 410 |
| `invalid_request` | 400 |

  Reads stay available while extraction is paused.

### 8.2 Routes

| Route (admin `/scoutro/api/v1`) | Agent route and grant | Purpose |
|---|---|---|
| `GET /kg/status` | — (admin only) | State, pause reasons, budget breakdown, queue, backlog, lag, extractor versions, LLM breaker |
| `POST /kg/control` `{action: pause\|resume\|reconcile\|retry_failed\|reresolve\|vacuum\|backup}` | — | Admin control; `jsonBody` rules (same origin, JSON, ≤ 16 KiB) |
| `GET /kg/entities?q&type&host&collection&quality&offset&limit` | `GET /kg/entities` (`kg.read`) | Search and list; limit 1–100, offset ≤ 10 000 |
| `GET /kg/entities/{id}` | `kg.read` | Detail; redirect answer `{"redirect":"kge_…"}` |
| `GET /kg/entities/{id}/statements?predicate&direction=out\|in&include=stale&offset&limit` | `kg.read` | Relations and values |
| `GET /kg/statements/{id}` / `…/evidence?offset&limit` | `kg.read` | Statement and its paginated sources (limit 1–50) |
| `GET /kg/hosts/{host}/entities` | `kg.read` | For SEO and Index Browser |
| `GET /kg/sources/{docId}` | `kg.read` | What the graph holds from one page |
| `GET /kg/export?format=ndjson\|json&collection&include=evidence` | `kg.export` | Streamed full export / download |
| `GET /kg/changes?cursor=<epoch>:<seq>&limit&expand` | `kg.export` | Incremental changes and delete notices |

- `kg.read` and `kg.export` are scoped grants (`ScopedActions.filterCollections`) and are not part of any preset.
- The MCP adapter offers `kg.read` automatically, because it exposes granted, non-mutating GET actions (`tools/scoutro/scoutro-mcp`).
- Every new action follows the existing checklist:
  - `AgentActionRegistry`, `AgentApi.route`, `ScopedActions`, `ScoutroApiServlet`;
  - `generate_api_description.py` (paths, GRANTS, mcp, cli), then regenerate `openapi.json`/`actions.json`;
  - `AgentCatalogTest`, `scoutroctl`;
  - `docs/API.md`, `docs/ACTIONS.md`, `help/`.

### 8.3 Export and change feed

- **One projection** serves the API, the download and the export (same serializer classes).
- **Export.** NDJSON lines in this order:
  1. header `{"type":"header","schema":"scoutro.kg.v1","epoch","as_of_seq","generated_at","collection"}`;
  2. `entity` lines;
  3. `statement` lines;
  4. optional `evidence` lines;
  5. trailer `{"type":"trailer","counts":{…},"complete":true}`.

  JSON is the same content in one object. Details:
  - Internally keyset pages of ≤ 1 000 rows, each in its own short read transaction, so the WAL can checkpoint.
  - A failure ends the stream with `complete:false` (the pattern of `DomainExport` on the open branch).
  - The download sets `Content-Disposition: attachment; filename="scoutro-kg-<collection|all>-<yyyyMMdd>.ndjson"`.
  - Because pages are separate transactions, the export is not a snapshot. Consumers apply `/kg/changes` from `as_of_seq` afterwards; upserts by ID are idempotent.
- **Changes.**
  - Coalesced per object (`UNIQUE(kind, public_id)`, monotonic `AUTOINCREMENT` sequence).
  - Items: `{seq, kind, id, op: upsert|delete|redirect, redirect_to?, at}`; `expand=true` adds the current object (limit ≤ 100).
  - **Cursor validity:**
    - the epoch must match;
    - `seq` must be ≥ the oldest retained change. Otherwise 410 `cursor_expired` with `details.full_sync="/scoutro/api/v1/kg/export"`.
  - **Delete notices** are kept for `changes.retentionDays`. Visibility follows [4.5](#45-visibility).
- **Portal matching.** Consumers get stable IDs and identifiers. No portal status exists or is invented.

### 8.4 Chat

- **Where:** new class `net.yacy.ai.rag.GraphFacts` (`RagRetriever.Scored` has a package-private constructor). It runs in `RAGProxyServlet` after `retrieve()` and before `RagContext.build` (`RAGProxyServlet.java:309-322`).
- **Candidates:** entities mentioned in the retrieved candidate documents (`kg_doc` by URL hash), plus FTS matches of the `RagQuery` terms on names.
- **Output:** up to `chat.maxFacts` (8) supported, current statements within `chat.maxChars` (1 500, taken from `budget.sourceChars`).
  - Each fact is a numbered source entry in the existing `[n] Title / URL: / Collection: / Text:` format. The URL is its best visible evidence document, checked against Solr.
  - Facts are therefore citable, are validated by `RagCitations`, and survive follow-up parsing.
- **Metadata:** a new stream key `scoutro-graph: {used, entities, facts, timedOut, reason}`.
- **Scope:**
  - the request's resolved collection, or all enabled graph collections when none is given and `global` is false;
  - only for `LOCAL` and `ADMIN` access;
  - AI Shield `GUEST` gets no graph facts by default (`chat.allowGuests=false`).

  This keeps the graph from widening the existing gap that guests may pick any collection in content RAG (open point O5).
- **Failure:** 300 ms total budget (`chat.timeoutMs`). Errors and timeouts skip the step and are logged. The search-based answer is always produced.

The normal YaCy search path (`yacysearch`, `SearchEvent`) is not touched, so a graph outage cannot affect it.

### 8.5 UI

- **New admin page `ScoutroKnowledge_p.html`** (`_p` → Digest admin, `AdminSecurity.java:86`). Views, as in `ScoutroSEO_p.html`:

| View | Content |
|---|---|
| `overview` | Status, pause reasons, budget bar (used/logical/free in file/reserve), queue, lag, LLM breaker, controls |
| `objects` | Search, filter by type/collection/host/quality, mobile cards |
| `object?id=` | Names, identifiers, quality, `last_confirmed`, statements grouped by predicate, relations in and out, paginated evidence with excerpt and link to the page/Index Browser, possible duplicates |
| `source?doc=` | What the graph holds from one page |
| `settings` | Enable switch, collections and LLM collections, budget and reserve, limits, retention, backups, schedule |

  Plus `help/ScoutroKnowledge_p.md`, the `UiRoutes` entries, a German `locales/de.lng` section plus `master.lng.xlf`, the `check-locale-identifiers.py` `PAGES` entry, and a Playwright test at five widths.
- **Navigation:** Scoutro group entry "Knowledge graph" ("Wissensgraph") in `header.template:187-192`.
- **Index Browser:** a "Knowledge" link per URL row (`?view=source`). With the domain view of the open branch, also an object count and link per domain card (O4).
- **SEO analysis:** a new tab "Knowledge" in `ScoutroSEO_p.html`/`seo.js` with the entities of the host, consolidated and with quality and source counts, linking to the object view.
- **Dashboard:** a status tile in `scoutro-dashboard.html` / `DashboardMetrics.java` (state, budget usage, pause reason, backlog).
- **LLM selection:** a new role column "knowledge" in `LLMSelection_p.html`/`.java`, with help and locales.

## 9. Configuration

All keys use the `scoutro.kg.` prefix and have code defaults (the Scoutro convention). They are listed in `help/ScoutroKnowledge_p.md` and `docs/API.md`, and the settings view writes them through the existing config mechanism.

| Key | Default | UI |
|---|---|---|
| `enabled` | `false` | yes |
| `collections` | empty (nothing is ingested) | yes |
| `llm.collections` | empty (no LLM tier) | yes |
| `budget.maxBytes`, `budget.pausePercent`, `budget.resumePercent`, `budget.maintenancePercent` | 1 GiB, 90, 80, 20 | yes |
| `disk.reserveBytes`, `disk.hysteresisBytes` | 1 GiB, 512 MiB | yes |
| `queue.maxItems`, `capture.maxPending` | 200 000, 100 000 | no |
| `extract.maxStatementsPerDoc`, `extract.maxExcerptChars`, `extract.maxInputChars` | 50, 200, 12 000 | advanced |
| `llm.parallel`, `llm.timeoutSeconds`, `llm.maxAttempts`, `llm.breakerFailures`, `llm.breakerMaxBackoffMinutes`, `llm.maxDocsPerHost` | 1, 120, 2, 3, 60, 25 | advanced |
| `gate.maxIndexingQueue`, `gate.maxLoad`, `gate.minFreeHeapMB` | 20, 2.5, 256 | advanced |
| `source.unavailableGraceDays`, `source.goneRetentionDays`, `source.maxAgeDays`, `source.staleRetentionDays` | 14, 7, 365, 90 | advanced |
| `changes.retentionDays`, `changes.maxRows` | 30, 1 000 000 | advanced |
| `cache.maxPercent` | 20 | advanced |
| `reconcile.hour`, `reconcile.debounceSeconds` | 3, 300 | advanced |
| `backup.keep`, `backup.intervalDays` | 1, 7 | yes |
| `chat.enabled`, `chat.allowGuests`, `chat.maxFacts`, `chat.maxChars`, `chat.timeoutMs` | true, false, 8, 1 500, 300 | yes |

## 10. Affected files

**New** (package `net.yacy.scoutro.knowledge`, unless noted):

| Area | Files |
|---|---|
| Store | `store/KgStore.java` (connections, PRAGMAs, write lock), `store/KgSchema.java` + `schema-v1.sql`, `store/KgMigrations.java`, `store/KgIds.java` |
| Budget | `budget/StorageGuard.java`, `budget/PauseState.java` |
| Runtime | `KgConfig.java`, `KgRuntime.java` (start/stop, clean-shutdown flag), `KgStatus.java` |
| Sync | `solr/KgCaptureProcessorFactory.java`, `sync/DirtySet.java`, `sync/SyncService.java`, `sync/Reconciler.java`, `sync/Backfill.java`, `sync/SolrReader.java` |
| Extraction | `extract/JsonLdExtractor.java`, `extract/MetadataExtractor.java`, `extract/RuleExtractor.java`, `extract/LlmExtractor.java`, `extract/ExtractionCache.java`, `extract/Vocabulary.java` + `defaults/scoutro/knowledge-vocabulary.json` |
| Resolution and publish | `resolve/IdentityResolver.java`, `resolve/Normalizers.java`, `publish/Publisher.java`, `publish/Aggregates.java` |
| Read and API | `query/KgQueries.java`, `query/Visibility.java`, `api/KgApi.java`, `api/KgProjection.java`, `api/KgExport.java`, `api/KgChanges.java` |
| Chat | `source/net/yacy/ai/rag/GraphFacts.java` |
| UI | `htroot/ScoutroKnowledge_p.html`, `source/net/yacy/htroot/ScoutroKnowledge_p.java`, `htroot/env/scoutro/knowledge.js`/`.css`, `help/ScoutroKnowledge_p.md` |
| Tests | `test/java/net/yacy/scoutro/knowledge/**`, `test/scoutro-ui/knowledge-ui-test.mjs` |

**Changed:**

| File | Change |
|---|---|
| `ivy.xml`, `NOTICE` | `org.xerial:sqlite-jdbc` (Apache-2.0), license notice |
| `defaults/solr/solrconfig.xml` | Update chain ([5.1](#51-change-capture)) |
| `defaults/solr/schema.xml`, `defaults/solr.collection.schema` | Stored-only `ld_json_txt` (disabled by default) |
| `ContentScraper.java`, `Document.java`, `CollectionSchema.java`, `CollectionConfiguration.java` (`yacy2solr`) | Bounded JSON-LD capture |
| `LLM.java`, `LLMSelection_p.html`/`.java` | Usage `knowledge`, optional per-call read timeout |
| `ScoutroApiServlet.java` | `case "kg"` routes; `KgRuntime.start/stop` in `init`/`destroy` |
| `AgentActionRegistry.java`, `AgentApi.java`, `ScopedActions.java` | Grants `kg.read`, `kg.export` |
| `RAGProxyServlet.java` | Graph-facts step and metadata |
| `ScoutroSEO_p.html`, `seo.js`, `IndexBrowser_p.html`, `index-browser.js`, `scoutro-dashboard.html`, `DashboardMetrics.java`, `header.template` | UI integration |
| `UiRoutes.java`, `locales/de.lng`, `locales/master.lng.xlf` (other `.lng` only where keys change), `test/scoutro-ui/check-locale-identifiers.py` | Routes and translations |
| `tools/scoutro/generate_api_description.py`, `htroot/env/scoutro/api/openapi.json`, `actions.json`, `AgentCatalogTest.java`, `tools/scoutro/scoutroctl` | API catalog |
| `docs/API.md`, `docs/ACTIONS.md`, `docs/SCOUTRO.md` (differences table), `docs/BUILD.md` (DATA note at 107-111 is stale), `build.xml` (test target if needed) | Documentation and build |

## 11. Work packages

Each package is one or more reviewable PRs on its own branch from the then-current `main`. The graph stays behind `scoutro.kg.enabled=false` until package 5, so merged intermediate states change nothing for existing installations. No package is released on its own as "finished".

### Package 1: Foundation

- **Scope:**
  - sqlite-jdbc dependency and notice;
  - `KgStore` with PRAGMAs, schema v1, migrations, schema-version refusal, dataset epoch, IDs;
  - `KgConfig` with validation;
  - `StorageGuard`: directory accounting, `max_page_count`, thresholds, hysteresis, disk reserve coordinated with `ResourceObserver` keys, `SQLITE_FULL` handling, chunked incremental vacuum;
  - `KgRuntime` (start/stop, clean-shutdown flag, unclean detection, event ring);
  - admin routes `GET /kg/status` and `POST /kg/control` (pause/resume only);
  - API docs and catalog regeneration;
  - storage benchmark test (the spike made reproducible).
- **Acceptance:**
  - Data survives a restart.
  - A newer schema is refused and the graph is disabled.
  - The budget pauses at 90 % and resumes at 80 % (simulated sizes).
  - The main file never exceeds the page cap; `SQLITE_FULL` leaves the database consistent.
  - The vacuum loop returns space.
  - The routes need admin Digest and the CSRF rules for POST.
  - With `enabled=false` nothing is created on disk.

### Package 2: Lifecycle and extraction

- **PR 2a** (no LLM):
  - update processor, dirty set, sync, work queue, backfill, reconcile, `_version_` catch-up;
  - document states and expiry; publish with CAS; aggregates and scope tables;
  - JSON-LD capture (core change);
  - tiers 1 and 2; identity resolution and merges; change feed; retention.
- **PR 2b:**
  - `LLMUsage.knowledge`, per-call timeout, `LlmExtractor` with schema validation and verbatim check;
  - cache, circuit breaker, per-host LLM cap, `LLMSelection_p` column.
- **Acceptance:** release checks 1–9 as automated tests with embedded Solr (`EmbeddedSolrConnectorTest` / `IndexBrowseTest` pattern), including:
  - recrawl during extraction;
  - delete during extraction;
  - crash between Solr commit and drain (kill without clean shutdown);
  - delete by query and full clear;
  - a hanging LLM (test server that never answers);
  - invalid and oversized LLM output.

  Also verified in the tests:
  - the update processor class loads in the embedded core;
  - `_version_` is monotonic within a run.

### Package 3: Interface

- **Scope:**
  - admin read routes (`entities`, `statements`, `evidence`, `hosts`, `sources`) with the visibility rules;
  - `ScoutroKnowledge_p.html` with all views, settings and controls;
  - SEO "Knowledge" tab, Index Browser links, dashboard tile, navigation;
  - help, German translations, locale check, Playwright tests at five widths.
- **Acceptance:**
  - Mobile use works.
  - Status and error messages are understandable.
  - No admin page or route is reachable without Digest.
  - A single-collection admin filter hides other collections everywhere, counts included.

### Package 4: JSON and chat

- **Scope:**
  - versioned contract frozen (`scoutro.kg.v1`);
  - agent grants and routes;
  - export (NDJSON/JSON, download);
  - change feed with cursor validity and delete notices;
  - `GraphFacts` in chat with metadata and fallback;
  - catalog, `AgentCatalogTest`, `scoutroctl`, MCP check, `docs/API.md`/`ACTIONS.md`.
- **Acceptance:**
  - Stable IDs across restart and re-extraction.
  - Pagination limits hold; an expired cursor gets 410 with a full-sync hint.
  - A scoped agent never sees foreign collections in details, counts, export, changes or chat.
  - Chat answers cite graph facts as valid sources and fall back to search when the graph is off or slow.

### Package 5: Release completion

- **Scope:**
  - integration, resource and restart tests on the measured corpus ([7.8](#78-measurement-method-package-5));
  - final budget defaults from the measurements (O1);
  - upgrade, backup, restore and rollback tests ([13](#13-migration-backup-rollback));
  - build;
  - release files (`scoutro.properties`, publish workflow alias, `BUILD.md`);
  - `docs/SCOUTRO.md` differences table.
- The Olares chart and its rollout are a separate follow-up.
- **Acceptance:** all twelve release checks ([12](#12-release-acceptance)) pass and are documented with test names and measurements.

## 12. Release acceptance

| # | Release check | Covered by |
|---|---|---|
| 1 | New page yields sourced objects; reprocessing creates no duplicates | 2a: publish replaces evidence per document; deterministic IDs; idempotence test |
| 2 | Identical content shares work without mixing origin/identity | 2b: cache key incl. domain context; separate evidence rows; test with the same text on two domains |
| 3 | Same-name organisations stay separate; several facilities per domain | 2a: key rules in [4.3](#43-identity-resolution); resolver tests |
| 4 | Two sources → one removed: statement remains; last removed: not current | 2a: aggregates and states in [4.4](#44-quality-and-currency) |
| 5 | Recrawl during extraction publishes nothing stale; delete during processing makes no ghost | 2a: CAS, dirty-set check, epoch wait in [5.5](#55-stale-results-and-ghost-documents) |
| 6 | ID, query, collection/domain deletion and full clear within the defined lag; temporary errors follow the status rule | 2a: path table in [5.7](#57-delete-and-change-paths); state tests |
| 7 | Crash between index change, event and graph commit repaired; backfill resumable | 2a: `_version_` catch-up, reconcile, cursor; kill tests |
| 8 | Hanging/faulty LLM does not block the crawler; invalid output bounded; content untrusted | 2b: timeouts, breaker, schema and verbatim check, PromptGuard |
| 9 | Budget, reserve and queue limits hold under load; cleanup and resume work; no unbounded logs/temp files | 1 + 5: StorageGuard tests, load test on the corpus |
| 10 | API/export/chat respect auth, visibility, pagination, limits; chat keeps sources and fallback | 3 + 4: route tests per role, visibility tests, chat tests |
| 11 | Graph outage causes no uncaught error in search; mobile UI and translations work | 4 + 3: graph disabled/broken database tests; search path untouched; Playwright |
| 12 | Upgrade keeps the index; rollback, schema compatibility, restore described and tested | 5: [13](#13-migration-backup-rollback) |

## 13. Migration, backup, rollback

- **Upgrade to the first graph release.**
  - No Solr reindex is needed.
  - On start, the new `solrconfig.xml` (update chain) and `schema.xml` (stored field) are copied into the core conf, as on every start.
  - The graph stays disabled until an admin enables it. Enabling it enables `ld_json_txt` and starts the backfill.
  - The YaCy index and `DATA/SETTINGS` are not modified otherwise.
- **Graph schema migrations** (after v1 is released):
  - forward-only, one transaction each;
  - a pre-migration backup (`VACUUM INTO`) when budget and disk allow, otherwise the migration is deferred and the graph stays off with reason `migration_space`;
  - a newer schema than the code knows is never opened (`schema_unsupported`; data untouched).
- **Rollback to `0.6.0`** (or any version without the graph):
  1. The old image writes its own `solrconfig.xml`/`schema.xml` on start, so the update chain is gone.
  2. Documents that carry `ld_json_txt` remain readable. The old schema maps the name to the dynamic `*_txt` field, so partial updates do not fail with "unknown field". Rewritten documents then index the field as text, a small growth.
  3. The old `CollectionConfiguration` drops the unknown key from `solr.collection.schema` and logs it.
  4. `DATA/SCOUTRO/knowledge/` is ignored. It still occupies space; removal is documented (`rm -r DATA/SCOUTRO/knowledge` while stopped).

  Package 5 tests exactly this sequence:
  1. write with the new version;
  2. start the old image;
  3. run postprocessing with partial updates;
  4. expect no errors and an unchanged document count.
- **Restore from backup.**
  1. Stop the graph (admin pause plus stop, or stop Scoutro).
  2. Replace `graph.db` and delete `-wal`/`-shm`.
  3. Start.

  The start runs a catch-up and full reconcile, because the backup is older than Solr. Exact IDs are preserved.
- **Rebuild without a backup.** Delete the directory and enable the graph again. The backfill recreates tiers 1 and 2 from Solr with mostly the same IDs. Tier 3 needs new LLM work.

## 14. Version recommendation

- The releases so far raise `scoutro.release` (now 12) and use an image alias that so far followed the Olares chart line (`0.5.9`–`0.5.11`, then `0.6.0`).
- The alias is hardcoded in `.github/workflows/publish-scoutro.yml:45,94-97` and in `docs/BUILD.md:36`.
- The graph adds a new persistent store, a new API family, a Solr config change and a new dependency. It needs no index migration.

Recommendation: **`1.942-scoutro.13` with alias `0.7.0`** (a minor step: new feature with new on-disk data, backward-compatible upgrade). If another release lands first, take the next free `N` and the next minor alias. The Olares chart version is decided in the separate rollout task.

## 15. Open points and missing access

| # | Point | Effect | Needed from |
|---|---|---|---|
| O1 | Real server and Olares capacity: free space on the `DATA` filesystem, current index size (documents, hosts, collections), whether `limitedDisk: 20Gi` is enforced | The 1 GiB default stays provisional until package 5 measurements and these figures exist. No free capacity is claimed. | Owner / operations |
| O2 | Industry vocabulary: which service and facility terms, which identifier schemes beyond the listed ones (for example IK numbers) | Tiers 2 and 3 quality; vocabulary file | Owner |
| O3 | LLM host and model for `knowledge` (same machine? GPU?), and the heap (`Xmx`) of the target installation | LLM throughput, `llm.maxDocsPerHost`, heap gate | Owner / operations |
| O4 | Fate of branch `ccr-e3e5f88b-1fqp77` (Index Browser domains, export, AI Lab) | Packages 3/4 integrate into the domain view and reuse its export pattern if it is merged first; otherwise the URL view and own code | Owner |
| O5 | Existing chat gap: clients choose any collection, guests included | The graph does not widen it (guests get no facts). Fixing content RAG scoping is out of scope. | Owner decision |
| O6 | Backup target outside `DATA` | Local backups halve capacity; an external target needs a mounted path, which conflicts with the Olares "no second DATA path" rule | Owner / operations |
| O7 | Legal review of stored excerpts (imprint pages contain names) | Excerpt length and the export of excerpts | Owner |
| O8 | Tag `v1.942-scoutro.12` is not visible in the shallow clone | Release numbering is re-checked at release time | — |

Verified only by reasoning or documentation, and covered by tests in package 2:

- the update processor class loads in the embedded core;
- `_version_` semantics after a restart (clock jumps are covered by the reconcile after an unclean start);
- the ordering of atomic-update merging relative to the processor.

## 16. First implementation PR

**PR 1 (package 1, part 1): "Knowledge graph foundation: store, budget, status".** Branch from the then-current `main`.

- **Scope:**
  - `ivy.xml`: `org.xerial:sqlite-jdbc` (current release, 3.53.4.0 at planning time); `NOTICE` entry.
  - `KgConfig`, `KgStore` (PRAGMAs as in [3.3](#33-decision), single writer lock, reader pool), `schema-v1.sql` ([4.2](#42-physical-schema-v1-draft)), `KgMigrations` (version check, refuse newer), `KgIds`.
  - `StorageGuard` with directory accounting, `max_page_count`, thresholds and hysteresis, disk reserve from the `resource.disk.free.min.steadystate` key, pause reasons, chunked `incremental_vacuum`.
  - `KgRuntime` started from `ScoutroApiServlet.init`, stopped in `destroy`; the clean-shutdown flag; the event ring.
  - Routes `GET /scoutro/api/v1/kg/status` and `POST /scoutro/api/v1/kg/control` (`pause`/`resume`), admin only.
  - Generator entries and regenerated `openapi.json`/`actions.json`; `docs/API.md`.
- **Tests** (run by the existing `net/yacy/scoutro/**/*Test.java` glob):

| Test | Checks |
|---|---|
| `KgStoreTest` | Create, reopen, epoch, newer schema refused |
| `StorageGuardTest` | Simulated sizes, pause/resume hysteresis, `SQLITE_FULL` → `budget_exhausted` and a consistent database, vacuum frees pages |
| `KgRuntimeTest` | Unclean start detected |
| `KgStatusRouteTest` | 401 without admin; CSRF rules on POST |
| `KgStorageBenchmark` | Reproducible spike; manual target, not in CI |

- **Not in PR 1:** no Solr changes, no extraction, no UI page, no agent grants. With `scoutro.kg.enabled=false` (the default) nothing is created on disk, so the PR can be merged without effect on existing installations.
