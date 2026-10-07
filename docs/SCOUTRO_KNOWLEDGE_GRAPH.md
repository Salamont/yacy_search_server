# Scoutro Knowledge Graph (plan)

Status: **revision 2; package 1 merged (PR #12), package 2a implemented**
(package 1: store, storage guard, runtime, status and control routes, see
[section 16](#16-package-1-implementation) and [16.1](#161-review-corrections-pr-12);
package 2a: synchronisation with Solr, tiers 1 and 2, identity resolution,
change feed, retention and the JSON-LD capture, see
[section 18](#18-package-2a-implementation)). Packages 2b–5 are not started. Base: `main` at `a42cc7d` (Scoutro
`1.942-scoutro.12`, alias `0.6.0`). Inputs: the owner's release plan
(`Scoutro_Knowledge_Graph_Releaseplan.md`, 2026-10-05) and the earlier static
analysis (`YaCy_Knowledge_Graph_Architekturanalyse.md`). Every finding of that
analysis was re-checked against the code ([section 1](#1-verified-starting-point)).

Revision 2 corrects six findings of the review of revision 1 (`9f475a6`):

- storage limits beyond the main file;
- the Solr storage of `ld_json_txt`;
- the real consistency guarantee of the synchronisation;
- the reconcile order;
- the identity rules and schema keys;
- the change feed.

Details are in [section 17](#17-revision-2-corrections).

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
16. [Package 1 implementation](#16-package-1-implementation)
    - 16.1 [Review corrections (PR #12)](#161-review-corrections-pr-12)
17. [Revision 2 corrections](#17-revision-2-corrections)
18. [Package 2a implementation](#18-package-2a-implementation)
19. [Package 2b implementation](#19-package-2b-implementation)
20. [Package 3 implementation](#20-package-3-implementation)
21. [Package 4 implementation](#21-package-4-implementation)
22. [Package 5 implementation](#22-package-5-implementation)
23. [Package 6 implementation](#23-package-6-implementation)

## 0. Decisions at a glance

| Topic | Decision | Section |
|---|---|---|
| Backend | **Embedded SQLite** (`org.xerial:sqlite-jdbc` 3.53.4.0) in `DATA/SCOUTRO/knowledge/`. WAL mode, `synchronous=FULL`, enforced foreign keys. One writer connection; two read-only readers with deadlines. No second process, no RDF/graph server. | [3](#3-storage-backend-decision) |
| Data model | **Merged statements** with an evidence row per (statement, source document, **tier**). Several extraction tiers can support the same statement from the same document without overwriting each other. A document's evidence is replaced per tier in one transaction. | [4](#4-data-model) |
| Identity | Deterministic public IDs. Automatic cross-document merges happen only for: <br>• equal strong identifiers; <br>• the same JSON-LD `@id`; <br>• the declared operator of a site; <br>• facilities with the same full address and name and no conflicting discriminator. <br>Unclear cases stay separate; merges leave redirects. | [4.3](#43-identity-resolution) |
| Change capture | A Solr `UpdateRequestProcessor` on `collection1` records changed and deleted IDs, together with Solr's version, in a bounded in-memory set. A sync thread moves them into a persistent work queue. | [5.1](#51-change-capture) |
| Consistency | **Eventual convergence, not a shared transaction.** <br>• Results are published only for the newest Solr version seen for a document, read with real-time get. <br>• A later event always supersedes an earlier result. <br>• Lost events, deletions and reactivations are repaired by a full reconcile. <br>• `_version_` catch-up is only an optimisation. | [5.5](#55-consistency-guarantee) |
| Reconcile | Merge join in one byte order: Solr's string sort equals SQLite `TEXT COLLATE BINARY`. A deletion happens only after a direct lookup confirms it. An aborted or implausible scan deletes nothing. The reconcile also runs while extraction is paused. | [5.3](#53-reconcile-catch-up-and-backfill) |
| Solr write-back | **None.** The processing state lives only in the graph store. | [5.6](#56-no-solr-write-back) |
| JSON-LD | Stored-only Solr field `ld_json_txt`, written only for documents of enabled collections. Bounded per document and by its own total budget. The capture pauses on budget or low disk; crawling and indexing go on without the field. | [6.2](#62-json-ld-capture-the-only-yacy-core-change) |
| Storage | Application budget over **all** files of the graph directory. <br>• `max_page_count` caps only the main file. <br>• The WAL is bounded by the guard: a limit, verified checkpoints, a write pause and read deadlines with interrupt. <br>• Temp files are measured, including the unlinked ones. <br>• Growth writes and maintenance writes have separate thresholds. <br>• On a really full disk even deletions fail; they fail in a controlled way and are retried. | [7](#7-storage-and-resource-contract) |
| Change feed | Coalesced per object, keeping every collection the object was visible in (`scopes_seen`). Removal notices are never lost, but may be redundant. Cursor `<epoch>:<seq>`. | [8.3](#83-export-and-change-feed) |
| Access | Admin: `/scoutro/api/v1/kg/*` (Digest). Agents: `/scoutro/api/agent/v1/kg/*` with new grants `kg.read` and `kg.export`, scoped by collection. A fact is visible only through a source document in a visible collection. | [8](#8-interfaces-api-export-chat-ui) |
| Chat | A graph-facts step in `RAGProxyServlet`. Facts become citable numbered sources. 300 ms limit, silent fallback to plain search, off for AI Shield guests. | [8.4](#84-chat) |
| Release | `1.942-scoutro.13`, alias `0.7.0`. The Olares chart is a separate follow-up. | [14](#14-version-recommendation) |

## 1. Verified starting point

### 1.1 Repository state

- `origin/main` = `a42cc7d` ("Merge pull request #11 … release/scoutro-0.6.0"). Every PR (#1–#11) is closed.
- Every remote feature branch is contained in `main` except **`ccr-e3e5f88b-1fqp77`** (1 commit, `735deaa`, 2026-10-05, not merged and no PR). It belongs to another session and is left untouched. It adds:
  - an Index Browser domain view (`GET /v1/index/domains`) and a streamed domain export (`/v1/index/domains/export?format=json|csv`, schema `scoutro.domains.v1`);
  - the AI Lab navigation and a reworked chat UI;
  - locale rewrites.

  Its `Sink`/cursor/`complete` pattern is the template for the graph export. Conflicts with this plan are expected in `ScoutroApiServlet.java`, `IndexBrowser_p.html`, `index-browser.js`, `header.template`, all `locales/*`, `openapi.json`/`actions.json` and `generate_api_description.py` (see [open point O4](#15-open-points-and-missing-access)).
- **Update:** that branch was merged into `main` as PR #13 (`5ee2d29`) while package 1 was under review. The package 1 branch merged that `main`: `ScoutroApiServlet.java` combined by hand (domain export and graph start next to each other), `openapi.json`/`actions.json` regenerated with the merged generator (45 actions: the domain actions and `kg.status`/`kg.control`).
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
- SQLite temp files go to `DATA/SCOUTRO/knowledge/tmp/` (`temp_store=FILE` plus `temp_store_directory`). They sit on the same filesystem as `DATA` and are measured even though SQLite unlinks them ([7.1](#71-what-is-counted)).
  - `temp_store_directory` is **process-wide** (the global `sqlite3_temp_directory`, verified: set on one connection, seen by all). SQLite allows changing it only while no connection is in use. `SqliteProcess` therefore sets it on the first connection while no other SQLite connection of the process exists, and never while one is open; readers do not touch it.
  - If the directory is missing or not writable, SQLite silently falls back to `/var/tmp` or `/tmp` (verified). Such files are still counted ([7.1](#71-what-is-counted)).

Connection settings (implemented in `KgStore`):

- `journal_mode=WAL`;
- `synchronous=FULL` (change-feed sequence numbers must never roll back after a power loss);
- `foreign_keys=ON` (verified at open);
- `auto_vacuum=INCREMENTAL` (set before the first table);
- `journal_size_limit` = `wal.checkpointBytes`;
- SQLite's own `wal_autocheckpoint` stays at its default;
- `busy_timeout=5000` (200 ms for the explicit checkpoints);
- `cache_size` 8 MiB for the writer and 4 MiB per reader, `mmap_size=0`;
- `max_page_count` on the writer.

One writer connection, guarded by a lock, and two reader connections with `query_only=1`, used only through read leases with a deadline ([7.3](#73-what-bounds-which-file)). Every connection is opened and closed through `SqliteProcess`, which counts them for the temp-directory rule.

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

### 4.2 Physical schema v1

The authoritative DDL is `KgSchema.DDL_V1`
(`source/net/yacy/scoutro/knowledge/store/KgSchema.java`), tested by
`KgStoreTest`. Until the first release, v1 is a draft: development databases are
recreated when it changes.

| Table | Content | Key rules |
|---|---|---|
| `kg_meta` | `schema_version`, `dataset_epoch`, `clean_shutdown`, `manual_pause`, `reconcile_required`, `changes_min_seq`, … | — |
| `kg_collection`, `kg_vocab`, `kg_extractor` | Collection names; types, predicates, identifier schemes (with a `functional` flag); extractor versions per tier | Names validated by CHECK |
| `kg_doc` | One row per tracked Solr document: `doc_id`, state, token, `solr_version`, input hash, `generation`, tiers, `host_id`, URL, `jsonld_bytes`, `jsonld_skipped`, timestamps | `doc_id` is 12-char **TEXT COLLATE BINARY**, the same order as Solr's string sort ([5.3](#53-reconcile-catch-up-and-backfill)); CHECK on states and lengths |
| `kg_doc_collection` | Collections per document | FK → `kg_doc` ON DELETE CASCADE, FK → `kg_collection` |
| `kg_entity`, `kg_entity_key`, `kg_entity_redirect` | Entities with public ID, type, status; identity keys (scheme, scope, value); redirects of merged IDs | `status=2` ⇔ `merged_into` set; public IDs `kge_` + 20 base32 characters (CHECK) |
| `kg_statement`, `kg_statement_redirect` | Merged statements, unique per (subject, predicate, object key); redirects | Exactly one of `obj_ent` and `obj_val` (CHECK); FKs to entity and vocabulary |
| `kg_evidence` | One row per **(statement, document, tier)** with extractor, kind, certainty, optional confidence, locator, excerpt (≤ 1000 characters by CHECK, 200 by setting) | FKs with ON DELETE CASCADE from statement and document |
| `kg_statement_scope`, `kg_entity_scope`, `kg_host_entity`, `kg_name_fts` | Derived visibility and lookup tables, maintained in the publish transaction; contentless FTS5 with `contentless_delete=1` | FKs with CASCADE |
| `kg_change` | Coalesced change feed: `scopes_now`, `scopes_seen` | `AUTOINCREMENT` sequence; UNIQUE (kind, public_id) |
| `kg_work`, `kg_extraction`, `kg_scan`, `kg_event` | Persistent work queue (with `event_version`); extraction cache; reconcile and backfill runs (cursor, counters, state); bounded event ring (1000) | CHECK on enumerations |

**Notes:**

- **Constraint enforcement.** Foreign keys are enforced on every connection (`PRAGMA foreign_keys=ON`, verified at open). Enumerations, ID formats and lengths are CHECK constraints. `KgStoreTest` proves that violations are rejected.
- **Paginated sources.** A statement's sources are `kg_evidence` rows read with keyset pagination, never a growing list in one field.
- **Shared extraction, separate origin.** Identical content shares one cache row but keeps separate evidence rows, and therefore separate document and collection assignments.
- **Internal IDs.** Integer row IDs stay internal; public IDs are separate and stable ([4.3](#43-identity-resolution)).

### 4.3 Identity resolution

**Public IDs.**

- An entity ID is `kge_` + 20 base32 characters (100 bits) of SHA-256 over:
  - `scoutro-kg/entity/v1`;
  - the type;
  - the **identity key it was created with** (scheme, scope, value).
- A statement ID is `kgs_` + 20 characters over:
  - `scoutro-kg/statement/v1`;
  - the subject ID at creation;
  - the predicate;
  - the canonical object.
- Every part is NFC-normalised and separated by NUL (`KgIds`, tested in `KgIdsTest`).
- IDs are stored once and never recomputed. A rebuild from the same data normally yields the same IDs; backups preserve exact IDs.

**Keys and what they may merge:**

| Key (scheme) | Scope | Merges automatically |
|---|---|---|
| `register` (court + HRA/HRB/VR/GnR/PR, normalised), `vat` (validated format), `lei`, `wikidata`, `ik` | global | Organisations or facilities with the equal value, across documents and domains |
| `ld_id` (absolute JSON-LD `@id`) | host of the declaring page | Mentions with the same `@id` on that host |
| `site_operator` (the organisation declared as operator on an imprint or as `publisher`/`provider` of the site, with its legal name including the legal form) | registrable domain | The operator mentioned on several pages of the same domain, if the normalised legal name is equal |
| `operator_name` (package 5: the same normalised legal name, with legal form, of an organisation **not** declared as operator, e.g. the `parentOrganization` of a facility page) | registrable domain | Only with the declared operator of that domain, in either order; two such mentions never merge with each other (a portal lists unrelated organisations with equal legal names) and the key never derives an ID |
| `facility_address` (type + facility kind + normalised name + **full address**: street, house number, postal code, locality) | registrable domain | Facility or site mentions with all of these equal, **and** no conflicting discriminator (see below) |
| `doc_local` (document ID + extractor-local reference) | one document | Repeated mentions inside one document only |
| phone (E.164), e-mail, name + postal code, `homepage` domain | — | **Never.** Shown as "possible duplicates" in the detail view. |

**Rules:**

- **Postal code alone is not enough.** The same domain, the same name and the same postal code do **not** merge facilities. One provider can run two facilities with the same name in one postal-code area, and a portal domain lists unrelated organisations.
- **Discriminators block a merge** even when a merge key matches:
  - two different values of the same strong scheme (for example two VAT IDs);
  - two different JSON-LD `@id`s;
  - different facility kinds from the vocabulary (for example day care vs. residential care);
  - different full addresses.

  A blocked merge is recorded as an `identity_conflict` event; the entities stay separate.
- **Mentions without a merge key** stay document-local entities (`doc_local`). Duplicates are preferred to wrong merges; the detail view lists likely duplicates for later manual handling (out of scope for this version).
- **Merge:** the older entity survives. The other entity gets `status=2` and a redirect. Its statements are re-pointed; colliding statements are merged by unioning their evidence, and the losing statement ID becomes a redirect. A `redirect` change is written.
- **No automatic split.** If the evidence behind a merge disappears, the merge stays. The admin maintenance action "re-resolve identities" (package 5, [22.5](#225-identity-rebuild)) rebuilds the graph from Solr in a shadow store with the current rules; the LLM tier answers from the copied cache without new model calls for unchanged text.

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
| `supported` | ≥ 1 current, stated evidence of tier 1 or 2 from an `active` document, with a verified locator |
| `uncertain` | Current evidence exists, but only with `certainty=hedged`, only from `unavailable` documents, or only from the LLM tier (tier 3; package 2b: a verbatim quote proves that the page says it, not that the model read the relation right) |
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

The graph and Solr are two stores without a shared transaction. Nothing in
this design makes a Solr update and a SQLite commit atomic. The guarantee is
convergence: every Solr change is eventually reflected, a result derived from
an older Solr state never replaces one from a newer state, and lost signals
are repaired by a full reconcile. [5.5](#55-consistency-guarantee) states it
precisely.

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

- **What it records,** after a successful `super.process*()`, with the version Solr assigned:
  - add → `dirty(id, version)`;
  - delete by ID → `deleted(id, version)`;
  - delete by query → `queryDeleteEpoch++` and a debounced reconcile request; `*:*` → `full_reset`.
- **Safe to run in Solr's update path.**
  - It is a no-op on the `webgraph` core (both cores share the file) and while the graph is disabled.
  - It catches every `Throwable` and never fails a Solr update.
  - Each update costs one bounded `ConcurrentHashMap` insert.
- **Coverage.** It sees every writer of the embedded core, including the bypasses listed in 1.2.

**Dirty set.**

- Coalesced per ID; the highest version wins, and a delete wins over an older add.
- Capped at `capture.maxPending` (100 000 IDs). On overflow it drops new IDs and sets `reconcile_required`.

**Sync thread.**

- Every 2 s it drains the dirty set into `kg_work` (≤ 500 IDs per transaction, keeping the highest `event_version`).
- In the same transaction it increments `kg_doc.generation` for these IDs.
- Draining is a maintenance write. While the storage guard refuses even those ([7.4](#74-write-classes-and-thresholds)), the set keeps coalescing in memory up to its cap, and then falls back to `reconcile_required`.

**Remote Solr** has no update processor. The graph requires the embedded Solr (`remote_solr_unsupported`).

### 5.2 Processing a document

1. **Claim** the next `kg_work` row. Remember its `generation` and `event_version`.
2. **Read the current Solr state with real-time get** (`/get`, served from Solr's update log), not with a search.
   - A search sees a change only after the soft commit. A fixed wait does not prove visibility, so none is used.
   - The answer carries `_version_`:
     - **add event, version ≥ `event_version`:** current. Process it.
     - **add event, version lower or document missing:** not yet visible. Requeue with backoff. After a bounded number of attempts, fall back to the reconcile.
     - **delete event, document absent:** confirmed. Remove the document from the graph.
     - **delete event, document present with a higher version:** it was re-added (reactivated). Process it as an add.
   - Fields read: `id, sku, host_s, host_id_s, httpstatus_i, failtype_s, exact_signature_l, collection_sxt, language_s, load_date_dt, _version_, ld_json_txt`.
   - If `/get` turns out not to work in the embedded core (verified in package 2, O9), the fallback is a version-checked search with the same requeue rule.
3. **Classify:**
   - not found → removed;
   - no enabled collection → out of scope (removed);
   - fail document → state change ([4.4](#44-quality-and-currency)).
4. **Token** (8 bytes of SHA-256 over `sku | httpstatus_i | exact_signature_l | sorted(collection_sxt) | language_s`) and JSON-LD hash:
   - both unchanged → update `loaded_at` and `solr_version` only;
   - only collections changed → update the scopes only.
5. **Extract** the needed tiers ([6](#6-extraction)). `text_t` is read only for tier 2/3 candidates; the cache is consulted first.
6. **Publish** ([5.4](#54-transactional-publish)).

### 5.3 Reconcile, catch-up and backfill

**One sort order.**

- Solr sorts the string field `id` by unsigned UTF-8 bytes (Lucene term order). Range queries `id:{x TO *]` and `cursorMark` use the same order.
- `kg_doc.doc_id` is the same 12-character ASCII text with SQLite's `BINARY` collation (memcmp), so it sorts identically. `KgStoreTest.documentIdsSortLikeSolrStrings` checks this against an unsigned byte comparison.
- Document IDs are never stored decoded: decoded bytes of YaCy's Base64 alphabet would sort differently.

**Full reconcile.**

1. Streams Solr IDs and token fields for the enabled collections, sorted by `id`, in pages of 1000.
2. Merge-joins them with `kg_doc` in the same order:

   | Case | Action |
   |---|---|
   | Missing in the graph | Enqueue (this also covers reactivated documents; fail documents of URLs never seen active are not tracked) |
   | Token differs, or a newer `_version_` | Enqueue |
   | Missing in Solr | Deletion **candidate** only |

3. Deletes nothing directly. A candidate is deleted only after a direct real-time-get lookup in batches of ≤ 100 IDs confirms that the document is absent or out of scope.
4. Has safety stops:
   - A page that fails, is partial (`partialResults`) or breaks the expected ascending order ends the run as `aborted`.
   - Candidates already verified stay correct; the rest are not deleted.
   - The run records its cursor in `kg_scan` and resumes from there.
5. Has a mass-deletion brake:
   - If verified deletions would exceed `reconcile.maxDeleteFraction` (0.2) of the tracked documents (and at least `reconcile.brakeMinDocs`, 50), or Solr reports zero documents while the graph tracks some, the run stops before deleting.
   - It is then marked `suspect` with the counts and needs `POST /kg/control {"action":"confirm_reconcile"}` (package 2).
   - A real `*:*` clear arrives as `full_reset` and is not subject to the brake.
6. Is lifecycle work (reads plus maintenance writes): it runs while extraction is manually or budget-paused. It stops only when maintenance writes are refused, and resumes from its cursor.

**When it runs:**

- **at every start and every reactivation**, also after a clean stop: Solr may have changed while the graph was disabled or stopped (package 2a; previously planned only after an unclean start);
- daily (`reconcile.hour`);
- 5 minutes after the last delete by query;
- after a dirty-set or queue overflow;
- after a change of the collection allowlist;
- on `resume` and on the control action `reconcile`.

**Catch-up by `_version_` (optimisation only).**

- Every 60 s, after a complete drain, the sync thread stores `version_checkpoint`, the highest Solr version seen at least 30 s earlier.
- After an unclean stop, every ID with `_version_ ≥ version_checkpoint` is enqueued at once, so recent adds are processed before the full reconcile reaches them.
- It sees no deletions; with clock jumps it can miss adds. The full reconcile that always follows an unclean start is what makes recovery correct.

**Initial backfill.**

- The same scan with an empty graph, cursor in `kg_scan`; interruptible and resumable.
- JSON-LD exists only for documents indexed after the capture was enabled ([6.2](#62-json-ld-capture-the-only-yacy-core-change)).

**Extractor version change.** Re-queues documents with an older extractor at low priority. The LLM tier is re-run only by admin action or within the per-host cap.

### 5.4 Transactional publish

One `BEGIN IMMEDIATE` transaction per document, with these steps:

1. Check, under the write lock:
   - `kg_doc.generation` still equals the claimed value;
   - the document is not in the dirty set with a version newer than the one read;
   - the version read is not lower than the `kg_doc.solr_version` already published.

   Otherwise abort and requeue.
2. Delete this document's evidence **for the replaced tier(s) only** and collect the affected statements.
3. Resolve entities; upsert statements; insert evidence (≤ 50 statements per document, excerpt ≤ 200 characters).
4. Recompute aggregates, quality, scopes, host links and FTS rows of the affected statements and entities. Delete statements without evidence.
5. Write coalesced `kg_change` rows ([8.3](#83-export-and-change-feed)).
6. Update `kg_doc`: token, input hash, tiers, `solr_version`, `processed_at`, `generation+1`.

Lifecycle events (remove, state change) use the same aggregate code in batches of ≤ 200 documents.

### 5.5 Consistency guarantee

The dirty-set check and the SQLite commit are **not atomic** with respect to Solr. A Solr update can land between the check and the commit. What the design does guarantee:

1. **Monotonic per document.**
   - The graph's data for a document is always derived from one Solr version, stored in `kg_doc.solr_version`.
   - A publish with an older version than the stored one is refused, so an older result never replaces a newer one.
2. **Later events supersede.**
   - The update processor records a change synchronously, after Solr applied it.
   - So a Solr change that lands after the commit check is already, or soon will be, an event with a higher version. Processing that event bumps the generation, re-reads Solr and replaces the published result.
   - A result derived from an older state can therefore be visible for at most the processing latency of the next event.
3. **No resurrection.** A deleted document can be published briefly only if Solr deleted it after the real-time get read. The delete event then removes it.
   - A delete event lost in a crash is repaired by the full reconcile after an unclean start. That reconcile deletes only after a verified lookup.
   - The graph writes nothing to Solr, so it can never create a ghost document there.
4. **Convergence.** Every change while the graph runs is processed through its event. Changes during overflow, pause overflow, downtime or a crash, including deletions and reactivations, are repaired by the next full reconcile. The `_version_` catch-up only shortens the delay.
5. **Read path.**
   - Detail, evidence and chat views verify their (≤ 50) source documents with one real-time-get batch and hide missing ones (and enqueue them).
   - Lists, counts and the export may lag. Every list response carries `lag` (`pending`, `oldest_pending_age_s`, `reconcile_pending`), so clients can see it.

### 5.6 No Solr write-back

The release plan asks to prove any needed write-back. None is needed:

- Every graph read (UI, API, chat) is served from SQLite.
- Graph-driven page lists use Solr `id:(…)` lookups with ≤ 100 IDs plus the viewer's collection `fq`.

Writing back would run into all four problems in 1.2: no optimistic concurrency because `_version_` is reset, a full reindex per atomic update, ghost documents on deleted IDs, and fields dropped on rewrite. The only Solr change is the parse-time `ld_json_txt` field. It is written by `yacy2solr` together with the document, so it can never be stale.

### 5.7 Delete and change paths

| Path (code) | Solr operation | Graph reaction | Lag |
|---|---|---|---|
| `Fulltext.remove(id/ids)` (Crawler_p, `Switchboard.remove`, `CrawlStacker`, `IndexControlURLs_p`, `URIMetadataNode`, `Segment.removeAllUrlReferences`) | delete by ID | Remove after real-time-get confirmation | seconds |
| `RecrawlBusyThread.java:326`, postprocessing `failids` | delete by ID (bypass) | same (captured in Solr) | seconds |
| `Fulltext.deleteStaleDomain*`, `deleteOldDocuments`, `deleteDomainErrors`, `IndexDeletion_p` queries, `ErrorCache.clear` | delete by query | Epoch++, debounced reconcile with verified deletions | ≤ 5 min + reconcile run |
| `clearLocalSolr` / `connector.clear()` | `*:*` | Full reset: new `dataset_epoch`, database recreated, cursors invalid (`epoch_changed`) | seconds to minutes |
| `ErrorCache.push` over a status-200 document | add (fail doc) | `unavailable` or `gone` | seconds |
| Recrawl/reindex (`yacy2solr`) | add | Token check: nothing, scope update or re-extraction | seconds plus queue |
| Delete followed by a re-add (reactivation) | delete, then add with a higher version | Processed as an add; a lost event is repaired by the reconcile ("missing in graph") | seconds / reconcile |
| Collection change of a document | add | Scope update; out of scope → removed | seconds |
| Allowlist change in the graph settings | — | Reconcile | reconcile run |
| Imports, surrogates, P2P, `/solr/collection1/update` | add/delete | as above | seconds |
| Crash between Solr commit and drain | — | `_version_` catch-up (optimisation) plus full reconcile (correctness) | after restart |

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
  - for at most `llm.maxDocsPerHost` (default 25) documents per host (finished plus queued; a changed cap or LLM collection list re-examines skipped documents), by priority (imprint and about pages, then services, locations and contact, then the home page).

  This keeps the LLM work bounded by the number of hosts, not pages.

### 6.2 JSON-LD capture (the only YaCy core change)

**Capture (package 2).**

- **Where:** in `ContentScraper` (script branch, `ContentScraper.java:1011-1020`), `<script type="application/ld+json">` content is collected.
- **Limits:** at most `jsonld.maxBlocksPerDoc` (8) blocks and `jsonld.maxBytesPerDoc` (16 KiB) per document. A block that would exceed them is dropped whole, never cut. A block is kept only if it parses (depth ≤ 8, ≤ 500 nodes) and has a relevant `@type`.
- **Who:** `yacy2solr` writes the field `ld_json_txt` **only for documents of collections enabled for the graph**. All other documents never carry it.

**Storage contract (package 1 provides configuration and status).**

- **Field type.** Stored, not searchable: multi-valued, declared explicitly in `defaults/solr/schema.xml` with the type `text_stored`, whose analyzer emits no token. Its name matches the old schema's dynamic `*_txt`, and `text_stored` has the same Lucene index options as that field's `text_general`, which keeps rollback safe ([13](#13-migration-backup-rollback), [22.10](#2210-rollback-the-fields-index-options)).
- **Outside the graph directory.** The field sits in the Solr index, so it does not count against the graph budget. It has **its own budget**, `jsonld.maxTotalBytes`.
- **Estimate.** The sum of `kg_doc.jsonld_bytes` (uncompressed, an upper bound for Solr's compressed stored fields) plus an in-memory counter of bytes captured but not yet synchronised.
- **When capture pauses** (`JsonLdCapturePolicy`, with hysteresis):

  | Reason | Pauses at | Resumes at |
  |---|---|---|
  | `jsonld_budget` | estimate ≥ `pausePercent` of `jsonld.maxTotalBytes` | `resumePercent` |
  | `disk_reserve` | DATA free space below the graph's growth floor (YaCy steady state + `disk.reserveBytes`) | growth floor + hysteresis |

  It is `off` while the graph or the capture is disabled, or the runtime is not running.
- **The crawl is never blocked.** The parser reads one volatile flag per document. While capture is paused or off, the document is indexed normally without the field.
  - Skipped document IDs go to a bounded set, and the sync marks them `jsonld_skipped`. Their JSON-LD is picked up at the next recrawl.
  - No page is re-fetched for the graph.
- **Display.** The status (`jsonld`) shows state, reason, estimate (null while not measured), limits and later the count of skipped documents. Package 1 reports `captureImplemented: false`.
- **Solr's own growth** stays governed by YaCy's `ResourceObserver`. Space of deleted documents returns only after Solr segment merges; this is a Solr property and is documented, not hidden.

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
- **As implemented (2b, see [19](#19-package-2b-implementation)):**
  - The model only proposes: no persons, no e-mail addresses, no phone numbers (contact data comes from tiers 1 and 2 only, O7); a known entity of tiers 1 and 2 is referred to by `k1`, `k2`, ...; facility kinds only from the collection's vocabulary (O2).
  - Statements supported only by the LLM tier are `uncertain` ([4.4](#44-quality-and-currency)); a claim the quote states as planned or possible is `hedged`, also when the model says otherwise (a word list).
  - A changed input deletes the document's tier-3 evidence in the tier-1/2 publish and asks again; tiers 1 and 2 never wait for tier 3.
  - The raw HTTP response is read up to 1 MiB; an endpoint that refuses `response_format` (HTTP 400) is asked again without it and remembered.
  - `last_error` is not used: `kg_doc.llm_status` (`done`, `failed`, `skipped`) and `llm_reason` record the outcome per input hash; `POST /kg/control {"action":"llm_retry"}` makes failed documents due again.

### 6.4 Cache

- **Key:** SHA-256 over:
  - the extractor ID (name, version, model, prompt hash);
  - the input: the chunk text, the title, the known entities of tiers 1 and 2 and the facility kinds offered (exactly what the prompt contains);
  - the context: registrable domain and language.

  The same text on another domain is extracted again, because "we" and the organisation name depend on the site.
- **Value:** the validated, unresolved extraction result of one chunk (deflated JSON, ≤ 64 KiB), or the reason the answer was refused, so the same text is not asked again. Resolution into entities runs per document in its own context. Shared extraction therefore never mixes identities or origins.
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
  tmp/                                   SQLite temp files (process-wide temp_store_directory)
  backup/                                local backups and the safety copies of a restore or rebuild
  rebuild/                               the shadow graph of a running identity rebuild
```

- **Temp files are invisible to a directory scan.** SQLite unlinks its temp files right after creating them, so they never show up in a directory listing. Measured: a 300 000-row `DISTINCT` held a 27 MB unlinked file in `tmp/`.
  - On Linux (the Scoutro image) the guard adds the sizes of open `… (deleted)` descriptors from `/proc/self/fd`: those below `tmp/` (`tmpOpen`) and SQLite temp files (`etilqs_*`) of the process anywhere else (`tmpOpenElsewhere`), e.g. after SQLite fell back to `/var/tmp`. Both count against `tmp.maxBytes` and the budget.
  - `files.tmpDirectory` shows where SQLite creates them: `graph`, `other` (set while another store held connections; tests only) or `system_default`.
  - Tested in `KgTempFilesTest`: a spilling sort creates its files in `tmp/`, unlinked, counted, and released with the lease; a second store opened while the first is open keeps the first directory and is still counted; a deleted `tmp/` falls back and is still counted.
  - Elsewhere this value is unknown (`null`). Only the free-space checks cover it there.
- **Inside the database.** Change feed, events, cache and work queue are tables inside `graph.db`.
- **Not counted in the graph budget.** Graph log lines go to the rotated YaCy logs (≈ 20 MiB total). The native library sits in the JVM temp directory (≈ 1 MB). `ld_json_txt` lives in Solr with its own budget ([6.2](#62-json-ld-capture-the-only-yacy-core-change)).
- **Measured values** (`GET /kg/status`):

  | Value | Meaning |
  |---|---|
  | `usedBytes` | Sum of all files, including `tmpOpen` and `tmpOpenElsewhere` |
  | `files.*` | Each file separately |
  | `pages.logicalBytes` | Used pages |
  | `pages.freeInFileBytes` | Free pages inside the file |

### 7.2 Settings

| Setting | Default | Meaning |
|---|---|---|
| `scoutro.kg.budget.maxBytes` | 10 GiB (package 5, O1: a protection limit, not a target; evaluated in [22.4](#224-measurements-and-budget-evaluation)) | Total budget for the directory above |
| `scoutro.kg.budget.noticePercent` / `warnPercent` | 70 / 80 | Levels `notice` (status only) and `warning` (UI banner, dashboard) |
| `scoutro.kg.budget.pausePercent` / `resumePercent` | 90 / 80 | Level `brake`: hysteresis for new growth; `full` at the budget |
| `scoutro.kg.budget.maintenancePercent` | 10 (package 5; smallest share that holds WAL and temp limits for small budgets) | Share for WAL, temp files, deletions, migration. Must hold `wal.maxBytes + tmp.maxBytes`; otherwise the configuration is invalid. |
| `scoutro.kg.wal.maxBytes` / `wal.checkpointBytes` | 64 MiB / 8 MiB | Hard WAL limit of the guard; size at which a checkpoint is forced |
| `scoutro.kg.tmp.maxBytes` | 64 MiB | Limit for temp files (visible + open unlinked) |
| `scoutro.kg.read.maxTransactionMillis` | 5000 | Deadline of every read transaction |
| `scoutro.kg.integrity.maxMillis` | 120 000 | Deadline of the `quick_check` after an unclean shutdown |
| `scoutro.kg.disk.reserveBytes` / `disk.hysteresisBytes` | 1 GiB / 512 MiB | Free space kept above YaCy's `resource.disk.free.min.steadystate`; resume margin |
| `scoutro.kg.jsonld.enabled`, `jsonld.maxBytesPerDoc`, `jsonld.maxBlocksPerDoc`, `jsonld.maxTotalBytes` | false, 16 KiB, 8, 2 GiB (package 5, uncompressed bytes) | JSON-LD capture and its own Solr budget, with the same levels |
| `scoutro.kg.queue.maxItems` / `capture.maxPending` | 200 000 / 100 000 | Work queue and dirty-set caps; beyond → `reconcile_required` |
| `scoutro.kg.extract.maxStatementsPerDoc` / `maxExcerptChars` / `maxInputChars` | 50 / 200 / 12 000 | Growth per document and LLM input |
| `scoutro.kg.cache.maxPercent` | 20 | Extraction cache share |
| `scoutro.kg.changes.retentionDays` / `maxRows` | 30 / 1 000 000 | Change feed and delete notices |
| `scoutro.kg.source.*` | 14 / 7 / 365 / 90 days | Currency and purge rules ([4.4](#44-quality-and-currency)) |
| `scoutro.kg.backup.keep` / `intervalDays` / `maxMillis` | 1 / 7 / 600 000 | Local backups (interval 0 = only on request); deadline of one backup |

- **Implemented and validated in package 1** (`KgConfig`): the budget, WAL, temp, read, integrity, disk and JSON-LD settings. **Package 2a** adds `collections` (`*` for all), `capture.maxPending`, `queue.maxItems`, `extract.*` (tiers 1 and 2), `reconcile.*`, `source.*`, `changes.*` and `gate.*`. The LLM and cache settings follow with 2b.
- YaCy's `resource.disk.free.min.steadystate` and `undershot` (MB) are read, never changed.

### 7.3 What bounds which file

| File | Bound | Enforced by |
|---|---|---|
| `graph.db` | `max_page_count` = data share of the budget (budget − maintenance share) in pages. Refused growth ends with `SQLITE_FULL`; SQLite rolls the transaction back itself, and integrity stays `ok` (tested). | SQLite (per writer connection) |
| `graph.db-wal` | `wal.maxBytes` | The guard only (below) |
| `tmp/` | `tmp.maxBytes` (visible + open unlinked files) | The guard: growth stops, running readers are interrupted |
| `backup/`, `rebuild/` | Count fully in the budget; a backup starts only if budget and disk reserve allow its size, a rebuild only if the remaining budget holds 1.2 × the logical size plus the WAL and temp limits | The guard (package 5) |

**Why `journal_size_limit` and small batches are not enough.**

- `journal_size_limit` only truncates the WAL **after a successful reset checkpoint**.
- Small transactions do not help either. A checkpoint cannot copy frames beyond the snapshot of the oldest reader, and cannot reset the WAL while any reader uses it.
- Measured: with one open reader, `wal_checkpoint(TRUNCATE)` reported `busy=1` and 0 checkpointed frames, and the WAL grew to 3 MB despite a 1 MB `journal_size_limit`. One 20 000-document transaction produced an 84 MB WAL.

**How the WAL is bounded:**

1. **Every read is a lease** (`KgStore.read`) with a deadline (`read.maxTransactionMillis`).
   - Readers come from a pool of two read-only connections (`query_only`).
   - A reader past its deadline is interrupted (`sqlite3_interrupt`) by the watchdog thread (every 250 ms) and after every blocked checkpoint. The watchdog runs no SQL and never waits for the write lock, so supervised work (the integrity check, checkpoints) cannot hold it up.
   - **An interrupt between two statements is lost** (verified: SQLite clears it when the next statement starts with none active). The lease therefore hands out a wrapped connection (`LeasedConnection`) that refuses every statement execution and every `ResultSet.next()` after the deadline, and the watchdog repeats the interrupt on every pass while the lease is current.
   - **Interrupt, lease end, reuse and close are serialised per connection.** The watchdog checks the lease and calls `sqlite3_interrupt` under the connection's monitor; a lease ends (all its statements closed, then the lease cleared under the monitor, then COMMIT/ROLLBACK), and a connection is reused or closed, only under the same monitor. A late interrupt can therefore never reach the next lease, and never a closed connection (sqlite-jdbc's `interrupt` is not synchronised with `close`). An open statement would let a pending interrupt break the COMMIT (verified), hence the closing first; a connection whose transaction cannot be ended is closed, not reused.
   - Callers materialise a bounded page and never hold a transaction across client I/O. Export pages are separate transactions ([8.3](#83-export-and-change-feed)). Raw connections are not handed out; the lease refuses transaction control.
2. **Every checkpoint is verified.**
   - After each write, once the WAL reaches `wal.checkpointBytes`, and every 30 s, the store runs `wal_checkpoint(TRUNCATE)` with a 200 ms busy timeout.
   - It reads `busy`, the log frames and the checkpointed frames, and measures the WAL afterwards. Only `busy=0` with an empty WAL counts as complete.
   - Otherwise the guard sets `wal.blockedSince` and the store interrupts expired readers.
3. **Write pause.**
   - Admission runs **under the write lock, right before `BEGIN IMMEDIATE`**, with a fresh measurement. Two writers can therefore never be admitted on the same free space (tested with two synchronised writers near the pause threshold).
   - A write whose estimate would take the WAL past `wal.maxBytes` triggers one checkpoint attempt.
   - If that does not help, the write is refused with `wal_limit`, or `wal_checkpoint_blocked` while a checkpoint is blocked. This applies to every write class, **deletions included**.
   - A single transaction can therefore never exceed the WAL limit either (tested).
4. **Maintenance reserve.** The maintenance share must hold `wal.maxBytes + tmp.maxBytes` (validated). The WAL and temp files can thus reach their limits without taking the total over the budget.

The blocked-checkpoint case is a test: `StorageGuardTest.openReaderBlocksTheCheckpointAndWritesPauseUntilItEnds`. The lease races are tested deterministically in `KgStoreConcurrencyTest`.

**Application budget vs. real quota.**

- The budget is an application budget (`quota: application_budget` in the status), not a filesystem quota.
- If the operator adds a real quota or a separate volume for `DATA/SCOUTRO/knowledge`, ENOSPC arrives as `SQLITE_FULL`/`SQLITE_IOERR`. The transaction rolls back and the guard records `budget_exhausted` or `storage_error`.
- The graph cannot protect a `DATA` disk that others fill; it stops early.

### 7.4 Write classes and thresholds

Every write declares a class and an upper estimate of its growth (database + WAL). The guard measures the database, WAL and shared-memory sizes and the free space fresh for every admission, under the store's write lock; directories every 30 s.

| Check (in order) | Growth (new data, backfill, backups) | Maintenance (deletions, purge, vacuum) | System (start/stop marks, pause flag, events, integrity result) |
|---|---|---|---|
| Storage error since the last good `quick_check` | refused (`storage_error`) | refused | refused |
| Free space − estimate < critical floor (`min(YaCy undershot, growth floor)`) | refused (`disk_critical`) | refused | refused |
| WAL + estimate > `wal.maxBytes` | refused (`wal_limit` / `wal_checkpoint_blocked`) | refused | refused |
| Integrity check pending or failed after an unclean shutdown | refused (`integrity_check_pending` / `integrity_check_failed`) | refused | allowed up to 100 % of the budget |
| Start of this run not recorded | refused (`start_not_recorded`) | refused | – |
| Manual pause | refused (`manual`) | allowed | – |
| `SQLITE_FULL` seen, until usage ≤ resume threshold | refused (`budget_exhausted`) | allowed | – |
| Used + estimate > pause threshold, or budget pause active (hysteresis) | refused (`budget`) | allowed up to 100 % of the budget, then `budget_exhausted` | – |
| Free space − estimate < growth floor (YaCy steady state + reserve), or disk pause active (hysteresis) | refused (`disk_reserve`) | allowed | – |
| Temp files > `tmp.maxBytes` | refused (`tmp_limit`) | allowed | – |

A refused write changes nothing and is retried by its job later.

### 7.5 Pause and resume

| Reason | Effect | Resume |
|---|---|---|
| `budget` | No new growth; deletions, state changes, retention, vacuum continue while maintenance is admitted | used ≤ `resumePercent` |
| `disk_reserve` | Same | free ≥ growth floor + hysteresis |
| `budget_exhausted` | Same (SQLite refused growth) | as `budget` |
| `tmp_limit` | No growth; running readers are interrupted | temp files below the limit |
| `manual` | No growth; lifecycle work continues | `POST /kg/control {"action":"resume"}` |
| `wal_limit`, `wal_checkpoint_blocked` | **All writes** wait | after a complete checkpoint |
| `disk_critical` | **All writes** wait | free ≥ critical floor |
| `storage_error` (`IOERR`, corruption, failed `quick_check`) | All writes stop; reads keep working while possible | `resume` requests a new `quick_check`; the error clears only on `ok` |
| `integrity_check_pending` | Graph writes wait after an unclean shutdown (or a check that never finished, also across a clean restart); the runtime's own records continue | the check passes |
| `integrity_check_failed` | Same, with the result or the abort reason (`aborted: read_timeout after … ms`) as detail; a damaged database adds `storage_error` | `resume` requests a new check |
| `start_not_recorded` | Graph writes wait while the clean-shutdown mark of this run cannot be written | the maintenance thread records the start |
| `llm_breaker` | LLM tier only (package 2) | backoff elapsed |
| `disabled`, `config_invalid`, `schema_unsupported`, `native_library_unavailable`, `remote_solr_unsupported` | Graph off; Scoutro unaffected | fix + restart |

- **Existing data stays readable at the limit.** Nothing in Solr and no valid statement is deleted to gain space.
- **Queues stay bounded during a pause.** Changes coalesce per document, and overflow falls back to the reconcile.
- **No unlimited cleanup is promised.** Below the critical floor or at the WAL limit, deletions and purges fail too. They fail in a controlled way: the transaction rolls back, the refusal is counted in the status (`refusedWrites`), and the job retries later. The graph cannot free space when there is none to write the deletion.

### 7.6 Cleanup

- **What is purged,** daily and whenever usage reaches the pause threshold, in small maintenance transactions:
  - stale statements older than `staleRetentionDays`;
  - evidence of `gone`/`expired` documents past retention;
  - change rows outside retention (which raises `changes_min_seq`);
  - cache rows (LRU);
  - event-ring overflow;
  - backups beyond `keep`.
- **Shrinking the file.** Afterwards, `KgStore.incrementalVacuum` returns free pages in **bounded batches**:
  - one transaction per 128 pages, each admitted on its own as maintenance with a WAL estimate of (2 × pages + 16) frames;
  - between batches the WAL is checkpointed once it reaches `wal.checkpointBytes`;
  - the vacuum stops at `maxPages`, at its deadline, when the guard refuses the next batch (its reason, e.g. `wal_limit`, `budget_exhausted`, `disk_critical`), and **as soon as a checkpoint between batches is blocked**: in WAL mode the main file only shrinks when a checkpoint completes, so further batches would only fill the WAL that deletions may need.
  - Measured with sqlite-jdbc 3.53: one page per transaction writes about 3 WAL frames per freed page (up to 17 for a single page; 19 557 pages produced about 240 MiB of WAL); a 128-page batch writes at most 0.9 frames per page. With sqlite-jdbc each execution of the pragma frees one page, hence the loop inside the batch.
  - Tested: every batch stays within its estimate on a fragmented file; with a reader holding an old snapshot and a 4 MiB WAL limit the vacuum stops with `wal_checkpoint_blocked`, the WAL never exceeds the limit, and after the reader ends it finishes and the file shrinks.
- **Full `VACUUM`** is never automatic. It is an admin action, allowed only if free space ≥ 2 × logical size + reserve.
- **Physical vs. logical.** File shrinking is reported separately from logical deletion (`pages.freeInFileBytes`).

### 7.7 Backups

- `VACUUM INTO backup/graph-<UTC>.db` produces a compact, consistent snapshot.
- It starts only if the budget and the disk reserve allow `logical_bytes` more. Otherwise it is skipped with a reason.
- Then `quick_check` on the copy, a metadata file with its SHA-256; delete beyond `keep`.
- An external target outside `DATA` is not configured by default (the Olares rule is no second `DATA` path, O6); the administrator downloads a backup to keep it elsewhere.
- Rationale: tiers 1 and 2 can be rebuilt from Solr, so backups mainly protect LLM results and exact IDs.
- Implemented in package 5 ([22.3](#223-backup-and-restore)).

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

A scratch experiment (not committed) used synthetic data from a fixed-seed generator: 25 pages per host, 1 to 4 entities and 6 to 10 statements per page, 240-character excerpts. It ran with sqlite-jdbc 3.53.4.0 and a 4 KiB page size. It shows mechanisms, not capacity:

| Measurement | Result |
|---|---|
| 20 000 documents, 154 766 statements, 158 577 evidence rows, 23 685 entities | 83.4 MB (≈ 4.2 KB per document, ≈ 526 B per evidence row) |
| Share by object | evidence 55 %; statement table + 2 unique indexes 26 %; change log 5 % |
| Delete 10 000 documents incl. aggregates | 0.6 s; 46 % of pages free; file unchanged |
| Re-add 10 000 documents | free pages reused, file +4 % |
| `incremental_vacuum` | 9 759 pages (40 MB) returned in 4 s; file 86.9 → 46.9 MB without a copy |
| One transaction with 20 000 documents | WAL 84 MB ≈ database size |
| WAL with one open reader | `wal_checkpoint(TRUNCATE)` → `busy=1`, 0 frames; WAL 3 MB despite a 1 MB `journal_size_limit`; reset after the reader ended |
| `sqlite3_interrupt` on a running read | ends the statement with `SQLITE_INTERRUPT` within ≈ 200 ms |
| `sqlite3_interrupt` between two statements (no statement running, also inside a transaction) | no effect: the next statement (2 000 000 rows) ran to completion |
| `sqlite3_interrupt` while a result set stays open | the next statement **and the ROLLBACK** fail with `SQLITE_INTERRUPT`; after closing the result set the connection works |
| `sqlite3_interrupt` during `PRAGMA quick_check` / `integrity_check` on a 181 MB file | ends within 2 ms (`quick_check` took 171 ms, `integrity_check` 1.3 s) |
| `PRAGMA temp_store_directory` | process-wide: set on one connection, effective for every other; unset → `/var/tmp`; deleted directory → silent fallback to `/var/tmp` |
| `incremental_vacuum` WAL cost | ≈ 3 frames per page in autocommit (up to 17), ≤ 0.9 frames per page in 64- or 256-page transactions |
| Temp files of a large `DISTINCT` | 27 MB unlinked file in the configured `tmp/`, invisible to a directory scan |
| `max_page_count` exceeded | `SQLITE_FULL`, automatic rollback, `integrity_check ok`, row count unchanged |

The automated tests of package 1 reproduce the WAL, interrupt, page-limit and vacuum behaviour.

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
  - Internally keyset pages of ≤ 1 000 rows, each in its own read lease, so the WAL can checkpoint ([7.3](#73-what-bounds-which-file)).
  - A failure ends the stream with `complete:false`.
  - The download sets `Content-Disposition`.
  - The export is not a snapshot. Consumers apply `/kg/changes` from `as_of_seq` afterwards; upserts by ID are idempotent.

**Change feed** (`KgChangeLog`, implemented and tested in package 1):

- **One row per object** (`UNIQUE(kind, public_id)`). A new change replaces the row with a higher `AUTOINCREMENT` sequence.
- **Scope history.**
  - `scopes_now` holds the collections the object is visible in after the change.
  - `scopes_seen` accumulates every collection the object was visible in since the row was first written, before and after each change. Coalescing therefore never loses an earlier collection assignment.
- **What a viewer with collection set V sees per row:**

  | Condition | Item |
  |---|---|
  | The administrator without a filter | the actual operation |
  | The object is visible to V now | the change (`upsert`/`redirect`) |
  | Not visible now, but V intersects `scopes_seen` | `delete` |
  | Otherwise | nothing |

  Example: a consumer limited to A that was offline while an object moved A → B → C gets the removal (`KgChangeLogTest.offlineConsumerStillGetsTheRemovalAfterAtoBtoC`).
- **Removal notices are never missing, but can be redundant,** for example for an object the consumer never stored. Consumers treat the delete of an unknown ID as a no-op. A notice reveals only an opaque ID, and only to a viewer whose collections contained the object at some time.
- **Cursor `<epoch>:<seq>`:**
  - The cursor advances over rows the viewer cannot see.
  - It is valid while the dataset epoch is unchanged and `seq ≥ changes_min_seq − 1`.

  | Answer | When |
  |---|---|
  | 410 `epoch_changed` | the dataset was reset (`*:*`) |
  | 410 `cursor_expired` | older than retention; `details.full_sync` points to the export |
  | 400 `invalid_cursor` | malformed, or ahead of the feed |

  A missing cursor is accepted only while nothing was purged yet.
- **Retention.** Retention removes rows older than `changes.retentionDays` or beyond `changes.maxRows`, oldest first. It then raises `changes_min_seq` in the same transaction.
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

All keys use the `scoutro.kg.` prefix and have code defaults (the Scoutro convention). [7.2](#72-settings) lists the storage keys and marks the ones validated in package 1. Invalid values are reported in `config.errors` and keep the graph off. They never fall back silently.

| Key | Default | UI |
|---|---|---|
| `enabled` | `false` | yes |
| `collections` / `llm.collections` | empty | yes |
| `llm.kinds.<collection>` | start vocabulary for `edelsenior-web`, `checkthecoach-web`, `stackfinder-web`, `bauteamcheck-web`; empty otherwise | advanced |
| `budget.*`, `disk.*`, `wal.*`, `tmp.maxBytes`, `read.maxTransactionMillis`, `integrity.maxMillis` | [7.2](#72-settings) | budget and disk yes, rest advanced |
| `jsonld.*` | [7.2](#72-settings) | yes |
| `queue.maxItems`, `capture.maxPending` | 200 000, 100 000 | no |
| `extract.maxStatementsPerDoc`, `extract.maxExcerptChars`, `extract.maxRuleInputChars` (tier 2), `extract.maxInputChars` (LLM, 2b) | 50, 200, 65 536, 12 000 | advanced |
| `llm.parallel`, `llm.timeoutSeconds`, `llm.maxAttempts`, `llm.breakerFailures`, `llm.breakerMaxBackoffMinutes`, `llm.maxDocsPerHost` | 1, 120, 2, 3, 60, 25 | advanced |
| `gate.maxIndexingQueue`, `gate.maxLoad`, `gate.minFreeHeapMB` | 20, 2.5, 256 | advanced |
| `source.*`, `changes.*`, `cache.maxPercent` | [7.2](#72-settings) | advanced |
| `reconcile.hour`, `reconcile.debounceSeconds`, `reconcile.maxDeleteFraction`, `reconcile.brakeMinDocs` | 3, 300, 0.2, 50 | advanced |
| `backup.keep`, `backup.intervalDays` | 1, 7 | yes |
| `chat.enabled`, `chat.allowGuests`, `chat.maxFacts`, `chat.maxChars`, `chat.timeoutMs` | true, false, 8, 1 500, 300 | yes |

Settings take effect at the next start in package 1. The settings view (package 3) adds a reload that re-validates and re-opens the store.

## 10. Affected files

**New** (package `net.yacy.scoutro.knowledge`, unless noted):

| Area | Files |
|---|---|
| Store (package 1, done) | `store/KgStore.java` (connections, PRAGMAs, write lock with admission, read leases, checkpoints, batched vacuum), `store/LeasedConnection.java` (lease check per statement), `store/SqliteProcess.java` (process-wide temp directory, connection count), `store/KgSchema.java` (DDL v1), `store/KgChangeLog.java`, `KgIds.java`, `KgPaths.java`, `KgException.java`, `KgJson.java` |
| Budget (package 1, done) | `budget/StorageGuard.java`, `budget/StorageProbe.java`, `budget/CheckpointResult.java`, `budget/JsonLdCapturePolicy.java` |
| Runtime (package 1, done) | `KgConfig.java`, `KgRuntime.java` (start/stop, clean-shutdown flag, watchdog and maintenance threads, integrity check, status); API in `source/net/yacy/scoutro/api/KnowledgeApi.java` |
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
| `defaults/solr/schema.xml`, `defaults/solr.collection.schema` | Stored-only `ld_json_txt` (listed as enabled, written only while the capture runs; see [18](#18-package-2a-implementation)) |
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

### Package 1: Foundation (implemented on this branch)

- **Scope:**
  - sqlite-jdbc dependency and `NOTICE`;
  - `KgConfig` (validation);
  - `KgIds`;
  - `KgSchema` v1 with foreign keys and CHECK constraints;
  - `KgStore`: writer lock with admission under the lock, read leases (watchdog interrupt, lease check before every statement, interrupt serialised with lease end, reuse and close), verified checkpoints, `max_page_count`, incremental vacuum in admitted batches, migration and foreign-database refusal, event ring; process-wide temp directory (`SqliteProcess`);
  - `KgChangeLog` (scope history, cursor contract, retention);
  - `StorageGuard` (growth, maintenance and system write classes, budget, WAL, temp files wherever SQLite puts them, disk floors, hysteresis, integrity and start gates, `SQLITE_FULL`/`IOERR` handling);
  - `JsonLdCapturePolicy` (contract only);
  - `KgRuntime` (disabled = no files and no thread, native-library failure isolated, clean-shutdown flag, separate watchdog and maintenance threads, unclean-start detection with a `quick_check` under deadline that gates graph writes, persisted manual pause, read-only start with automatic retry when the start cannot be recorded);
  - admin routes `GET /kg/status` and `POST /kg/control`;
  - OpenAPI and action catalog, `docs/API.md`.
- **Acceptance:** met by the tests listed in [16](#16-package-1-implementation).

### Package 2: Lifecycle and extraction

- **PR 2a** (no LLM; implemented, see [18](#18-package-2a-implementation)):
  - update processor with a version-carrying dirty set, sync, work queue;
  - real-time-get reader;
  - backfill;
  - reconcile in byte order with verified deletions, abort handling and mass-deletion brake;
  - `_version_` catch-up;
  - document states and expiry; publish with CAS and version monotonicity; aggregates and scope tables;
  - JSON-LD capture with `JsonLdCapturePolicy`;
  - tiers 1 and 2; identity resolution with discriminators;
  - change-feed writes; retention.
- **PR 2b** (implemented, see [19](#19-package-2b-implementation)):
  - `LLMUsage.knowledge`, per-call timeout, `LlmExtractor` with schema validation and verbatim check;
  - cache, circuit breaker, per-host LLM cap, `LLMSelection_p` column.
- **Acceptance:** release checks 1–9 as automated tests with embedded Solr (`EmbeddedSolrConnectorTest` / `IndexBrowseTest` pattern), including:
  - recrawl during extraction;
  - delete during extraction;
  - crash between Solr commit and drain (kill without clean shutdown);
  - delete by query and full clear;
  - a hanging LLM (test server that never answers);
  - invalid and oversized LLM output.

  Also verified in the tests (O9):
  - the update processor class loads in the embedded core;
  - real-time get sees uncommitted adds and deletes;
  - `_version_` is monotonic within a run;
  - an aborted reconcile deletes nothing;
  - the mass-deletion brake triggers;
  - a reactivated document is restored;
  - two facilities with the same name and postal code but different addresses stay separate.

### Package 3: Interface

Implemented, see [20](#20-package-3-implementation).

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
| 5 | Recrawl during extraction publishes nothing stale; delete during processing makes no ghost | 2a: generation CAS, version monotonicity, real-time get, superseding events in [5.5](#55-consistency-guarantee) |
| 6 | ID, query, collection/domain deletion and full clear within the defined lag; temporary errors follow the status rule | 2a: path table in [5.7](#57-delete-and-change-paths); state tests |
| 7 | Crash between index change, event and graph commit repaired; backfill resumable | 1: unclean-start detection (`KgRuntimeTest`); 2a: full reconcile with verified deletions, `_version_` catch-up as optimisation, `kg_scan` cursor; kill tests |
| 8 | Hanging/faulty LLM does not block the crawler; invalid output bounded; content untrusted | 2b: timeouts, breaker, schema and verbatim check, PromptGuard |
| 9 | Budget, reserve and queue limits hold under load; cleanup and resume work; no unbounded logs/temp files | 1: `StorageGuardTest` (budget, reserve, WAL limit, checkpoint blocked by a reader, read interrupt, page limit, vacuum); 5: load test on the corpus |
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
  2. Documents that carry `ld_json_txt` remain readable and writable. The old schema maps the name to the dynamic `*_txt` field (indexed text), so partial updates do not fail with "unknown field".
     - Lucene refuses a field whose index options change within an index. The field's own type `text_stored` therefore has the index options of `text_general` and an analyzer that emits no token, so the old version's partial updates (YaCy's postprocessing) succeed ([22.10](#2210-rollback-the-fields-index-options)).
     - A document rewritten by the old version has the field indexed as text, a small growth, until Scoutro writes the page again.
  3. The old `CollectionConfiguration` drops the unknown key from `solr.collection.schema` and logs it.
  4. `DATA/SCOUTRO/knowledge/` is ignored. It still occupies space; removal is documented (`rm -r DATA/SCOUTRO/knowledge` while stopped).

  Package 5 tests exactly this sequence (`test/scoutro-api/kg-rollback-live.py`):
  1. write with the new version;
  2. start the old version: every document is there, readable and searchable, and it indexes new pages without errors;
  3. a partial update with the old classes and the old core configuration on a document that carries the field;
  4. the new version again: the graph is intact, reconciles, takes in the old version's page and rewrites the updated one.
- **Restore from backup** (package 5): `POST /kg/control {"action":"restore","backup":"<file>"}` or *Restore* on the page ([22.3](#223-backup-and-restore)). It checks the backup first, keeps the current graph as `graph-<UTC>-before-restore.db`, gives the restored graph a new dataset epoch and reconciles it with Solr, because the backup is older than Solr. Exact IDs are preserved. By hand, with Scoutro stopped: replace `graph.db`, delete `-wal`/`-shm`, start (no new epoch then).
- **Rebuild without a backup.** The action *re-resolve identities* ([22.5](#225-identity-rebuild)) rebuilds from Solr while the graph keeps serving. Or delete the directory and enable the graph again: the backfill recreates tiers 1 and 2 from Solr with mostly the same IDs; tier 3 needs new LLM work.

## 14. Version recommendation

- The releases so far raise `scoutro.release` (now 12) and use an image alias that so far followed the Olares chart line (`0.5.9`–`0.5.11`, then `0.6.0`).
- The alias is hardcoded in `.github/workflows/publish-scoutro.yml:45,94-97` and in `docs/BUILD.md:36`.
- The graph adds a new persistent store, a new API family, a Solr config change and a new dependency. It needs no index migration.

Recommendation: **`1.942-scoutro.13` with alias `0.7.0`** (a minor step: new feature with new on-disk data, backward-compatible upgrade). If another release lands first, take the next free `N` and the next minor alias. The Olares chart version is decided in the separate rollout task.

## 15. Open points and missing access

| # | Point | Effect | Needed from |
|---|---|---|---|
| O1 | ~~Budgets~~ — decided: configurable, 10 GiB graph and 2 GiB JSON-LD as protection limits (package 5, measured in [22.4](#224-measurements-and-budget-evaluation)). Still open: the real Olares figures (free space on `DATA`, index size, whether `limitedDisk: 20Gi` is enforced) | If `limitedDisk: 20Gi` is enforced, the budgets need lowering ([22.4](#224-measurements-and-budget-evaluation)) | Operations |
| O2 | ~~Industry vocabulary~~ — decided: extensible, collection-specific vocabulary without a schema rebuild; package 2b ships a small start vocabulary of facility kinds for `edelsenior-web`, `checkthecoach-web`, `stackfinder-web` and `bauteamcheck-web`, replaced per collection by `llm.kinds.<collection>`; kinds are entity attributes, so new ones need no schema change | Tier-3 quality; kinds are merge discriminators | Owner (further terms) |
| O3 | ~~LLM host and model~~ — decided: the existing LLM selection is reused (usage `knowledge`, opt-in column); the LLM is optional and the graph works without it. Throughput on the target hardware is measured in package 5 | `llm.maxDocsPerHost`, heap gate | Operations (model choice) |
| O4 | ~~Fate of branch `ccr-e3e5f88b-1fqp77`~~ — resolved: merged into `main` as PR #13 (`5ee2d29`) | Packages 3/4 integrate into the domain view and reuse its export pattern | — |
| O5 | Existing chat gap: clients choose any collection, guests included | The graph does not widen it (guests get no facts). Fixing content RAG scoping is out of scope. | Owner decision |
| O6 | ~~Backup target outside `DATA`~~ — decided: portable backups inside the app's `DATA` (`knowledge/backup`), downloadable by the administrator; an external disaster-recovery target is a later operations decision and no prerequisite (package 5) | Backups count fully in the budget | Operations (external copy) |
| O7 | Legal review of stored excerpts (imprint pages contain names) — decided: minimal data, no extra person profiling, no employee e-mails or personal contact data as an enrichment target; the LLM tier extracts no persons, e-mail addresses or phone numbers; package 5 keeps only role mailboxes and removes person names from excerpts ([22.2](#222-data-minimality-o7)) | Excerpt length and the export of excerpts | Owner |
| O8 | Tag `v1.942-scoutro.12` is not visible in the shallow clone | Release numbering is re-checked at release time | — |
| O9 | ~~Real-time get (`/get`) in YaCy's embedded core, the capture processor class loading, and `_version_` behaviour after a restart are verified only by documentation and reasoning~~ — resolved in package 2a: `KgCaptureProcessorTest` proves with the shipped `defaults/solr` that the processor loads in the default chain after `_version_` is assigned, that real-time get sees uncommitted adds and deletes with the captured versions, and that versions stay monotonic across a core restart; the live smoke confirms the chain in a real peer ([18](#18-package-2a-implementation)) | The version-checked search fallback is not needed; the full reconcile stays the correctness backstop | — |
| O10 | Temp-file measurement via `/proc/self/fd` exists only on Linux | Other platforms report `tmpOpen: null` and rely on the disk floors | — |

## 16. Package 1 implementation

**Shipped in this PR:**

| Area | Files |
|---|---|
| Dependency | `ivy.xml` (`org.xerial:sqlite-jdbc` 3.53.4.0, `compile->master`), `NOTICE` (Apache-2.0, Zentus BSD notice, SQLite public domain) |
| Knowledge package | `source/net/yacy/scoutro/knowledge/`: `KgConfig`, `KgPaths`, `KgIds`, `KgException`, `KgJson`, `KgRuntime`, `budget/StorageGuard`, `budget/StorageProbe`, `budget/CheckpointResult`, `budget/JsonLdCapturePolicy`, `store/KgSchema`, `store/KgStore`, `store/KgChangeLog` |
| API | `source/net/yacy/scoutro/api/KnowledgeApi.java`; `ScoutroApiServlet` (`case "kg"`, start/stop in `init`/`destroy`) |
| Catalog | `tools/scoutro/generate_api_description.py`; regenerated `htroot/env/scoutro/api/openapi.json` and `actions.json` (`kg.status`, `kg.control`; admin only, not grantable) |
| Docs | `docs/API.md`, `docs/SCOUTRO.md`, `docs/BUILD.md`, this plan |

**Behaviour:**

- With `scoutro.kg.enabled=false` (the default) the runtime creates no directory, starts no thread and does not load the native library. `GET /kg/status` answers `state: disabled`.
- Any failure (invalid settings, missing native library, newer or foreign schema, I/O) ends in `state: unavailable` with a reason. Scoutro starts normally.
- If the guard refuses the start bookkeeping (for example on a disk below YaCy's undershot), the graph runs read-only with `startRecorded: false`. The maintenance thread retries every second; the guard refuses graph writes (`start_not_recorded`) until the start is recorded.
- After an unclean shutdown graph writes wait for `PRAGMA quick_check` (`integrity_check_pending`). The check runs on the maintenance thread with the deadline `scoutro.kg.integrity.maxMillis`, enforced by the watchdog. A failed or aborted check leaves `integrity_check_failed`; the flag `integrity_check_required` survives a clean restart until a check passes.
- Two daemon threads run while the graph runs: `ScoutroKG.watchdog` (read deadlines only, every 250 ms) and `ScoutroKG.maintenance` (integrity check, retries, measurement, checkpoints, every second).
- On SIGTERM (`docker stop`) the graph closes in its own JVM shutdown hook (`ScoutroKG.shutdown`, registered only while it runs) and records the clean shutdown. YaCy calls the servlet's `destroy()` only after its main thread has finished, and its own shutdown hook lets the JVM exit after 30 seconds; in repeated SIGTERM probes this limit was hit in 3 of 8 shutdowns with the graph enabled and in 1 of 8 with it disabled (an existing YaCy behaviour). Before this hook the next start then reported a false unclean shutdown; with it, every start after such a shutdown was clean.

**Tests** (all run by `ant scoutro-agents-test`):

| Test | Covers |
|---|---|
| `KgConfigTest` | Defaults, YaCy MB thresholds, invalid values, resume < pause, WAL/checkpoint relation, maintenance share |
| `KgIdsTest` | Determinism, part boundaries, NFC, formats, epochs, YaCy document IDs |
| `KgStoreTest` | PRAGMAs, reopen, newer schema refused and untouched, foreign database refused, foreign-key and CHECK enforcement, two tiers on one statement and document, cascade, Solr byte order, read-only readers, rollback, event ring |
| `KgChangeLogTest` | A → B → C offline consumer, delete to every former collection, cursor advance over invisible rows, retention and expired cursors, `maxRows`, foreign or malformed cursors |
| `StorageGuardTest` | Budget hysteresis, maintenance vs. growth, disk reserve and critical floor, WAL limit for every class, transaction larger than the WAL, temp/manual/storage error, status reasons; with real SQLite: **checkpoint blocked by an open reader and the write pause until it ends**, reader interrupted past its deadline, page limit with clean `SQLITE_FULL`, deletion and incremental vacuum |
| `KgStoreConcurrencyTest` | **Two synchronised writers near the pause threshold** (the second is refused), **late interrupt cannot reach the next lease** on the same connection, close waits for a pending interrupt and closes every connection, statement and row step after the deadline refused without an interrupt, the lease owns its transaction, every vacuum batch within its WAL estimate, **vacuum with a blocking reader and a 4 MiB WAL limit** |
| `KgTempFilesTest` | SQLite temp files of a spilling sort in `tmp/`, unlinked and counted; process-wide directory unchanged while another store's connections are open; fallback after a deleted `tmp/` still counted |
| `JsonLdCapturePolicyTest` | Off states, own budget with hysteresis, disk reserve |
| `KgRuntimeTest` | Disabled creates nothing and starts no thread, invalid settings, native-library failure (linkage error and sqlite-jdbc's `NativeLibraryNotFoundException`), both threads start and stop, unclean start with `reconcile_required` and `quick_check`, read-only start on a critically full disk with later recording, manual pause persisted; **graph writes held back until the check passes**, **an overlong check aborted by the real watchdog while another read is interrupted on time**, aborted check required again after a clean restart, failed check stops every write with a visible reason, **lease expiring between two statements stopped through the real watchdog** |
| `KgShutdownHookTest` | In a child JVM: SIGTERM records a clean stop without the servlet; SIGKILL is still detected as unclean; a disabled graph registers no hook |
| `KnowledgeApiTest` | Routes, validation, 405/404, 409 disabled, 503 unavailable; through `ScoutroApiServlet`: 401 without admin, 415/403 cross-site rules, `no-store` |

**Live smoke test** `test/scoutro-api/kg-live-smoke.py`: 23 checks on a disposable peer with temporary DATA, in five starts (`JAVA=…` selects the JDK):

1. disabled: no directory, 409, 401;
2. enabled: files and status, integrity `not_required`, temp directory `graph`, 415, pause;
3. SIGTERM restart: no crash reported, pause kept, resume;
4. SIGKILL restart: unclean start detected, integrity check `ok` with no integrity reason left, `unclean_start` and `integrity_ok` events;
5. native library not extractable: Scoutro and `health` up, graph `unavailable`/`native_library_unavailable`, control 503.

Offline contract tests `test/scoutro-api/test_flow_contract.py` and `test_mcp_adapter.py` pass, and the generated OpenAPI validates with `openapi-spec-validator`.

### 16.1 Review corrections (PR #12)

The review of `0b303ac` found five gaps in package 1. Each was reproduced or confirmed first, then fixed and covered by a regression test that fails on the old behaviour (checked by re-inserting the old code for findings 1 and 4).

| # | Finding | Confirmed | Change | Tests |
|---|---|---|---|---|
| 1 | `KgStore.write()` admitted before taking the write lock: two writers could be admitted on the same free budget | With the old order, two synchronised writers 1.5 MiB below the pause threshold were both admitted and committed | Admission (fresh measurement, one checkpoint retry) runs under the write lock right before `BEGIN IMMEDIATE`; every admission sees all earlier commits | `KgStoreConcurrencyTest.twoWritersNearTheLimitAreNotAdmittedOnTheSameFreeSpace` |
| 2 | `incrementalVacuum()` admitted 64 KiB once, then ran a long loop | One page per transaction writes ≈ 3 WAL frames per page (up to 17); 19 557 pages ≈ 240 MiB WAL ([7.9](#79-measurements-so-far)) | Batches of 128 pages in one transaction, each admitted (maintenance) with a WAL estimate of (2 × pages + 16) frames; checkpoint between batches; stop at a blocked checkpoint, at a guard refusal, at `maxPages` or the deadline; result with `stop` reason | `everyVacuumBatchStaysWithinItsWalEstimate`, `vacuumStopsAtABlockedCheckpointAndKeepsTheWalBounded` (blocking reader, 4 MiB WAL, WAL peak sampled) |
| 3 | `tick()` ran `quick_check` on the monitor thread, which then enforced no deadline (not even the check's own) | Structural; `sqlite3_interrupt` stops `quick_check` within 2 ms | Watchdog thread (deadlines only, no SQL, no write lock, every 250 ms) separate from the maintenance thread (integrity check, retries, measurement, checkpoints). The check is a read lease with `scoutro.kg.integrity.maxMillis`. Graph writes wait for it (`integrity_check_pending`); a failed or aborted check leaves `integrity_check_failed` (plus `storage_error` when damaged); `integrity_check_required` survives a clean restart. New write class `SYSTEM` for the runtime's own records, `start_not_recorded` gate. | `KgRuntimeTest`: writes held back until the check passes; overlong check aborted by the real watchdog while another read is interrupted on time; aborted check required again after a clean restart; failed check |
| 4 | Lease check and `sqlite3_interrupt` were not atomic against lease end, reuse and close; an interrupt between two statements is lost | Verified: an idle interrupt does not stop the next statement; with a statement left open it breaks the next statement and the ROLLBACK; sqlite-jdbc's `interrupt` is not synchronised with `close` | Per-connection monitor around lease check + interrupt, lease end, reuse and close; statements closed before COMMIT/ROLLBACK; a connection whose transaction cannot be ended is closed. `LeasedConnection` refuses statements and row steps after the deadline and refuses transaction control; the watchdog repeats the interrupt while the lease is current. | `lateInterruptCannotReachTheNextLeaseOnTheSameConnection`, `closeWaitsForAPendingInterruptAndThenClosesEveryConnection`, `statementAfterTheDeadlineIsRefusedEvenWithoutAnInterrupt`, `leaseOwnsItsTransaction`; through the real watchdog: `leaseThatExpiresBetweenTwoStatementsCannotStartTheNextOne` |
| 5 | `configureCommon()` set the global `temp_store_directory` on every new reader, during parallel use | Verified: the setting is process-wide; unset → `/var/tmp`; a missing directory silently falls back | `SqliteProcess` sets it on the first connection while no other SQLite connection of the process exists, never while one is open. The guard counts unlinked temp files in `tmp/` and SQLite temp files (`etilqs_*`) elsewhere; status `files.tmpDirectory`, `tmpOpenElsewhere`. | `KgTempFilesTest` (location, unlinked, counted, released; second store; fallback) |

**Found during verification.** The live smoke failed once at the restart after SIGTERM: the previous run was reported unclean. YaCy's shutdown hook had let the JVM exit after 30 seconds ("Main thread did not completely finish within 30 seconds") before the HTTP server, and with it `KgRuntime.stop()`, was stopped. The graph now closes in its own JVM shutdown hook while it runs (`KgShutdownHookTest`; the SIGTERM test fails without the hook). The slow YaCy shutdown itself also occurs with the graph disabled and is unchanged; it is outside this package.

**JDK 24 and the image layout.** The live smoke passes unchanged on Temurin 24.0.2 (the base of the Scoutro image), and a local image built like the final stage of `docker/Dockerfile.scoutro` (same base, user `yacy` uid 100, `startYACY.sh -f`, `DATA` volume) passes: disabled without directory or `ScoutroKG` thread; enabled with both threads and the temp directory `graph`; `docker stop` is a clean shutdown (the script `exec`s Java); `docker kill` leads to the unclean-start check. The only native-access warning (JEP 472) comes from Lucene's `PosixNativeAccess` and appears on every start, with the graph disabled too; sqlite-jdbc loads from the same unnamed module and adds none. The published image itself could not be pulled here (registry blob download refused by the sandbox proxy).

**Status contract changes** (unreleased, so `scoutro.kg.status.v1` keeps its name): `store.quickCheck` is replaced by `store.integrity`; new `store.manualPauseSaved`, `storage.files.tmpOpenElsewhere`, `storage.files.tmpDirectory`, `storage.readers.connections`/`maxConnections`; new reasons `integrity_check_pending`, `integrity_check_failed`, `start_not_recorded`. `control` no longer answers `kg_write_refused`: a pause change takes effect at once and is stored later if the guard refuses the write.

**Next PR (package 2a):**

- update processor with the version-carrying dirty set;
- sync thread and work queue;
- real-time-get reader with the tests for O9;
- reconcile with verified deletions, abort handling and mass-deletion brake;
- backfill;
- document states;
- publish with CAS and version monotonicity;
- JSON-LD capture with its pause;
- tiers 1 and 2;
- identity resolution;
- retention.

## 17. Revision 2 corrections

| # | Finding on revision 1 | Correction | Where |
|---|---|---|---|
| 1 | `max_page_count` limits only the main file; small batches and `journal_size_limit` do not bound the WAL | WAL bounded by the guard: limit, verified `TRUNCATE` checkpoints, write pause for every class, read leases with deadline and interrupt, maintenance share ≥ WAL + temp limit. Unlinked temp files measured. Deletions may fail at the limit, in a controlled way; no unlimited cleanup is promised. Blocked-checkpoint test added. | [7.1](#71-what-is-counted)–[7.6](#76-cleanup), `StorageGuard`, `KgStore`, `StorageGuardTest` |
| 2 | `ld_json_txt` grows outside the graph directory | Own budget, estimate, pause on budget or disk with hysteresis, only for enabled collections, crawl never blocked, skipped documents marked. Package 1: settings and status contract. | [6.2](#62-json-ld-capture-the-only-yacy-core-change), `JsonLdCapturePolicy` |
| 3 | Dirty-set check and commit are not atomic against Solr; a fixed 6 s wait proves nothing | Stated guarantee: monotonic per document, later events supersede, no resurrection, convergence through the full reconcile. Real-time get with version comparison instead of waiting. `_version_` catch-up only an optimisation; deletions and reactivations through the full reconcile. | [5.2](#52-processing-a-document), [5.4](#54-transactional-publish), [5.5](#55-consistency-guarantee) |
| 4 | Solr string order ≠ order of decoded BLOB IDs; delete reconcile under pause; aborted scans | `doc_id` as TEXT with BINARY collation (tested against unsigned byte order). Verified deletions only, abort without deletions, mass-deletion brake, reconcile runs while extraction is paused. | [5.3](#53-reconcile-catch-up-and-backfill), `KgSchema`, `KgStoreTest` |
| 5 | Domain + name + postal code is not a safe merge; real constraints; evidence keys | Merge keys per type with discriminators that block merges; document-local entities otherwise. Foreign keys and CHECK constraints in v1; evidence key (statement, document, tier). | [4.2](#42-physical-schema-v1), [4.3](#43-identity-resolution), `KgStoreTest` |
| 6 | Coalescing loses earlier collection assignments (A → B → C) | `scopes_seen` per change row; viewer rule; cursor contract with epoch, retention and redundant-but-never-missing removals | [8.3](#83-export-and-change-feed), `KgChangeLog`, `KgChangeLogTest` |

## 18. Package 2a implementation

**Shipped in this PR** (package 2a; disabled by default like everything before):

| Area | Files |
|---|---|
| Change capture | `solr/KgCaptureProcessorFactory`, `sync/DirtySet`, `sync/Capture`; `defaults/solr/solrconfig.xml` (default chain `scoutro-kg`) |
| Synchronisation | `sync/SyncService`, `sync/WorkQueue`, `sync/SolrSource`, `sync/EmbeddedSolrSource`, `sync/SolrDoc`, `sync/Reconciler`, `sync/FullReset`, `sync/Retention`, `sync/Gates` |
| JSON-LD capture | `sync/JsonLdCapture`; `ContentScraper` (script branch), `CollectionConfiguration.yacy2solr`, `CollectionSchema.ld_json_txt`, `defaults/solr/schema.xml`, `defaults/solr.collection.schema`; `budget/JsonLdCapturePolicy` is now applied |
| Extraction (tiers 1 and 2) | `extract/Vocabulary`, `JsonLdBlocks`, `JsonLdExtractor`, `MetadataExtractor`, `RuleExtractor`, `Mention`, `Claim`, `Address`, `Extraction` |
| Resolution and publish | `resolve/Normalizers`, `resolve/IdentityResolver`, `publish/Terms`, `publish/Aggregates`, `publish/Publisher` |
| Schema | `store/KgSchema` v2, migrated in place from v1; `KgChangeLog.purge` with a batch limit |
| Runtime and API | `KgRuntime` (sync thread, start and stop order, JSON-LD evaluation, status `sync`, control `reconcile`/`confirm_reconcile`), `KgConfig` (new keys), `KnowledgeApi`; generator, `openapi.json`, `actions.json` |
| Docs | `docs/API.md`, `docs/SCOUTRO.md`, this plan |

**O9 resolved.** With the shipped `defaults/solr`, the processor loads in the default chain of `collection1` after the processor that assigns `_version_`. Real-time get sees uncommitted adds and deletes with exactly the versions the processor recorded; deletes carry negative versions, a re-add after a delete a higher one; versions stay monotonic across a restart of the core; the `webgraph` core (same configuration) is ignored; a failing sink never fails a Solr update (`KgCaptureProcessorTest`). The version-checked search fallback of 5.2 is not needed and not built.

**Processing.**

- One thread, `ScoutroKG.sync`, runs bounded steps: a pending full reset first, the drain (≤ 500 events per maintenance transaction, refused events go back into the set), one reconcile slice, one batch of ≤ 50 work items (≤ 3 s), one retention slice, the version checkpoint.
- Per item: the claim writes a token into `claimed_at`; one real-time get per batch (≤ 100 IDs); then

  | Solr answer | Action |
  |---|---|
  | absent, delete event (or after two more lookups with backoff for an add event) | remove (maintenance) |
  | no followed collection | remove (out of scope) |
  | `excl`, HTTP 404/410 | `gone`, evidence kept but not current (maintenance) |
  | other fail document or non-200 status | `unavailable` (maintenance) |
  | fail document of a URL never seen active | not tracked |
  | active, token and input hash unchanged | `loaded_at` and version only |
  | active, same input hash (collections or status changed) | state and scopes (maintenance) |
  | active, new input | extraction of tiers 1 and 2, publish as growth |

- The input hash (16 bytes) covers the extractor versions, URL, host, language, `exact_signature_l` (the text), the JSON-LD blocks, titles, publisher and coordinate; the text itself (`text_t`) is read only for tier-2 candidates.
- New growth waits behind the gates (`gate.*`, online caution for scans) and the storage guard. While it is blocked, delete events are processed at once and other items once per 30 s, so removals and state changes continue; an item needing extraction is deferred without counting an attempt.
- The publish transaction checks, under the write lock, that the change set holds no newer event and that no full clear arrived, then the generation and version (`Publisher`). It completes the work row only if the row still carries its claim; a row re-armed by a newer event stays for the next round.

**Start, stop and restart.**

- **Every start and every reactivation schedules a full reconcile**, also after a clean stop and after a disabled period (reason `start`, `unclean_start`, `collections_changed`; `resume` and the control action `reconcile` request one too). The capture is active from the moment the store is open, before the reconcile scans, so no change in between can be missed.
- After an unclean stop the sync first enqueues every document with `_version_ ≥ version_checkpoint` (the highest version seen at least 30 s before a complete drain), then reconciles.
- Stop (servlet `destroy()` or the JVM shutdown hook, both through `KgRuntime.stop()`): the sync thread finishes its step, the capture stops, the change set is drained into `kg_work` (≤ 2 s; a refused drain is covered by the next start's reconcile), then the clean-shutdown mark is written. The next start releases old claims and processes `kg_work` while the reconcile runs.
- A reconcile interrupted by a stop is started again from the beginning at the next start; an interrupted backfill resumes from its cursor and is followed by a reconcile.

**Reconcile and backfill.**

- One run in `kg_scan` with phases `scan` → `verify` → `delete` → `done`; a run on an empty graph is a backfill.
- Scan pages are bounded on both sides: ≤ 1000 Solr documents (`id` ascending, committed, followed collections) and ≤ 1000 graph rows after the cursor; the step covers both up to the smaller last ID. Missing in the graph (active documents only), a newer `_version_` or another token → enqueued; missing in Solr → a candidate in `kg_scan_candidate` with the document's generation.
- A failing, partial or out-of-order page aborts the run (`aborted`, detail, retry with backoff from 1 to 30 minutes, from the cursor). Nothing is deleted before the scan has seen every page.
- Verify: real-time get per ≤ 100 candidates; present in a followed collection → enqueued, otherwise confirmed absent.
- Mass-deletion brake: confirmed > `reconcile.maxDeleteFraction` × tracked **and** ≥ `reconcile.brakeMinDocs`, or Solr returned no document while the graph tracks some → `suspect`, event `reconcile_suspect`, nothing deleted until `confirm_reconcile`.
- Delete: in batches of ≤ 200, each candidate verified again by real-time get right before the delete, removed only with the generation of the scan and without a pending event.
- Delete by query: a reconcile after `reconcile.debounceSeconds` (moved back by every further one). Overflow of the change set or a full queue: a reconcile at once; documents a full queue could not take come with a follow-up run an hour later. Daily at `reconcile.hour`.
- Full clear (`*:*`): a new `dataset_epoch` first (old cursors get `epoch_changed`), then all graph data in batches of 1000 rows; `reset_in_progress` survives a restart; `changes_min_seq` is raised; then a backfill. Not subject to the brake.

**Retention** (hourly, maintenance transactions of ≤ 200 rows):

| Rule | Action |
|---|---|
| `unavailable` longer than `source.unavailableGraceDays` | `expired` |
| `active` with `loaded_at` older than `source.maxAgeDays` | `expired` |
| `gone` longer than `source.goneRetentionDays` | removed (Solr holds only a fail document, which is never tracked again) |
| `expired` longer than `source.goneRetentionDays` | evidence deleted, input hash cleared; the row stays, otherwise every reconcile would add an old but active document again |
| `stale` statements older than `source.staleRetentionDays` | deleted; their sources extract again on a recrawl |
| change rows outside `changes.retentionDays` / `changes.maxRows` | deleted in batches of 5000, `changes_min_seq` raised |

**JSON-LD capture.** The scraper reads one volatile flag per document; while the capture is active it keeps ≤ `jsonld.maxBlocksPerDoc` blocks and ≤ `jsonld.maxBytesPerDoc`, drops a block that would exceed them whole, and keeps only blocks that parse within depth 8 and 500 nodes and carry a relevant `@type`. `yacy2solr` writes `ld_json_txt` only for documents of followed collections. The maintenance thread evaluates the policy every 30 s with the estimate "sum of `kg_doc.jsonld_bytes` + bytes captured but not yet synchronised". While paused, documents of followed collections are indexed without the field and recorded as skipped (`kg_doc.jsonld_skipped`, status `jsonld.capture`). Nothing waits on the graph in the parse or index path.

**Identity, as implemented.** All keys are scoped by the entity type (`type`, `type@host`, `type@domain`, `type#doc`). The facility kind is part of the `facility_address` key, and a new entity takes its public ID from the strongest key no contradicting entity holds: a day care and a residential home with one name at one address stay two entities, as do same-name facilities of one domain without a full address (document-local). When one of two conflicting values of a functional predicate loses its last source, the other becomes `supported` again.

**No write-back.** The graph never writes to Solr. The only Solr change is `ld_json_txt`, written by `yacy2solr` with the document at index time; a test asserts that indexed documents carry no graph field.

**Deviations from the plan above:**

1. A full reconcile runs at every start and reactivation, not only after an unclean start (owner requirement; 5.3 updated).
2. `ld_json_txt` is enabled in `defaults/solr.collection.schema` (10 said disabled). YaCy writes only enabled fields, and `SchemaConfiguration.fill` adds a new key with its default state to existing installations, so a disabled default would keep the capture off everywhere. Nothing is written unless the graph runs with `scoutro.kg.jsonld.enabled=true`. An older version does not know the key and ignores it; the stored values stay readable through the dynamic `*_txt` field.
3. One sync thread for drain, reconcile, tiers 1 and 2 and retention instead of separate `extract` and `maintenance` work (6.5); tiers 1 and 2 take milliseconds per document. The `extract` thread comes with the LLM tier (2b). Vacuum, checkpoints and the integrity check stay on the maintenance thread.
4. The reconcile also compares `_version_`; a token alone misses changes of fields outside the token (JSON-LD, load date).
5. New setting `reconcile.brakeMinDocs` (default 50): with the fraction alone, a graph of three documents would brake on one deletion.
6. With only a remote Solr the sync reports `unavailable` / `remote_solr_unsupported`; store and status stay available (7.5 said graph off).
7. Expired documents keep their row without evidence (see retention).

**Schema v2** (forward migration in the schema transaction; a new database is created as v1 and migrated the same way): `kg_scan.phase`, `reason`, `solr_seen`, `tracked`, `confirmed`; `kg_scan_candidate`; `kg_entity.subkind`; `kg_work.enqueued_at`; indexes on document state and load date, statement subject and quality, claimed work rows and entity order. Tested by `KgStoreTest.schemaV1IsMigratedInPlaceKeepingItsData`.

**Tests** (all run by `ant scoutro-agents-test`; Solr tests use an embedded core with the shipped `defaults/solr`):

| Test | Covers |
|---|---|
| `KgCaptureProcessorTest` | O9: processor in the default chain after `_version_`, real-time get of uncommitted adds and deletes, coalescing, delete by query and `*:*` as signals, `webgraph` ignored, broken sink never fails an update, overflow, versions across a restart |
| `ExtractorsTest` | JSON-LD (`@graph`, `@id` references, types, identifiers, address, relations, site operator), metadata, imprint rules (register, VAT, IK, address, phone, e-mail), bounded and invalid input |
| `PublisherTest` | Idempotent reprocessing, two sources → one → none, same name and postal code with different addresses, same name without address, facility kinds, strong-identifier merge and conflict, stale claims and older versions refused, states and scopes, functional conflict and its recovery, removal with the expected generation |
| `SyncServiceTest` | Backfill of followed collections only and idempotent recrawl, **recrawl during processing publishes nothing stale**, **delete during processing leaves no ghost** (tracked and new document), deletion and reactivation, fail-document states, **crash between Solr change and drain** (catch-up and reconcile), **every start reconciles also after a clean stop**, **aborted and partial reconcile deletes nothing and resumes from its cursor**, **mass-deletion brake until confirmed**, empty Solr, change-set overflow, full queue, debounced delete by query, **full clear with a new epoch** and an interrupted one finished after a restart, **refused growth (manual pause, disk reserve) defers extraction while deletions continue**, closed gates, collection change of a document, allowlist change, retention, no graph field in Solr |
| `KgSyncRuntimeTest` | Through `KgRuntime.start/stop` (the shutdown hook's path): capture on and off with the graph, **stop drains pending changes and the next start processes and reconciles**, **reactivation after a disabled period**, unclean start waiting for the integrity check, control actions, **JSON-LD budget pause without blocking indexing** |
| `JsonLdCaptureTest` | YaCy's HTML parser: nothing collected while off or paused, block and byte limits with whole-block drops, invalid and irrelevant blocks, field only for followed collections, skip records, pending bytes |
| `KgStoreTest`, `KnowledgeApiTest`, `JsonLdCapturePolicyTest` | Migration v1 → v2; new control actions and their errors; capture implemented |

**Live smoke test** `test/scoutro-api/kg-live-smoke.py`, now 31 checks in the same five starts, on JDK 21 and Temurin 24.0.2: additionally the start backfill, a page pushed through YaCy's parser and index path (`api/push_p`) captured with its JSON-LD and published, a page of an unfollowed collection not tracked, the reconcile of the clean restart (during the manual pause, nothing deleted) and of the restart after SIGKILL. The suite (`ant clean scoutro-agents-test`: 45 test classes, 430 tests) also passes; the Solr-backed sync tests pass on JDK 24 too.

**Built image.** An image built from `docker/Dockerfile.scoutro` (both stages; Temurin 24.0.2; user `yacy`; `startYACY.sh -f`; `DATA` volume) passes `test/scoutro-api/kg-image-smoke.py` with 15 checks: disabled without directory or `ScoutroKG` thread; enabled with the watchdog, maintenance and sync threads, the start backfill, a page pushed through the parser captured with its JSON-LD and published, a page of an unfollowed collection not tracked; `docker stop`/`start` is a clean shutdown and the start reconcile keeps the document; after `docker kill` the unclean start is detected, the integrity check passes and the reconcile of the unclean start keeps the document. The sandbox proxy only forwards HTTPS, so the test build added the proxy CA and HTTPS package sources for the build steps and removed the CA from the final image again; the Dockerfile itself is unchanged.

**Not in 2a:** the LLM tier and the extraction cache (2b), read routes, export, UI and chat (3, 4), backups and the admin action "re-resolve identities" (5).

**Next PR (package 2b):** see [19](#19-package-2b-implementation).

## 19. Package 2b implementation

**Shipped in this PR** (stacked on 2a; the LLM tier is optional and off unless `scoutro.kg.llm.collections` is set **and** a model has the usage `knowledge`):

| Area | Files |
|---|---|
| Model access | `LLM.java`: usage `knowledge`; `chat(…, readTimeoutMillis, maxResponseChars)` (the existing callers keep "no timeout, no limit"); the debug print of the schema removed. `extract/LlmClient`, `extract/YacyLlmClient` (the row with the usage `knowledge`; retry without `response_format` after HTTP 400, remembered per endpoint) |
| Extraction | `extract/LlmExtractor` (prompt, strict schema, `PromptGuard` DATA block, chunks, validation, verbatim grounding, hedging, cache key), `extract/LlmBreaker`, `extract/KindHints` (O2 start vocabulary) |
| Worker | `sync/LlmService` (`ScoutroKG.extract`, `llm.parallel` ≤ 2), `sync/LlmQueue`, `sync/ExtractionCache`, `sync/BaseTiers` (tiers 1 and 2 shared by the sync and the LLM tier) |
| Publish | `Publisher.applyLlm` (tier 3 only, compare-and-set on the input hash), invalidation of tier-3 evidence when the input changes, `Aggregates` (LLM-only statements `uncertain`), `Terms.llmExtractor` |
| Schema v3 | `kg_llm_work` (queue with attempts and claims), `kg_doc.llm_status` / `llm_hash` / `llm_reason`, partial indexes for the documents to do, the per-host count and the status counts; migrated in place from v1 and v2 |
| Runtime and API | `KgRuntime` (extract threads only with LLM collections, stop without waiting for a call, status `llm`, `llmRetry`), `KnowledgeApi` (`llm_retry`, `llm_unavailable` 409), `KgConfig` (`llm.*`, `extract.maxInputChars`, `cache.maxPercent`, `llm.kinds.<collection>`, ignored LLM collections) |
| Chat isolation | `RAGProxyServlet` maps the model name `knowledge` to `chat`; `OllamaTagsServlet` and `OpenAIModelsServlet` do not list it |
| UI | `LLMSelection_p.html`/`.java`: column "knowledge", opt-in (never checked for a new row, never handed to another row on undeploy); 14 locales, `master.lng.xlf`, `help/LLMSelection_p.md` |
| Docs and contract | this plan (4.4, 6.1, 6.3, 6.4, 9, 15), `docs/API.md`, `docs/SCOUTRO.md`; generator, `openapi.json`, `actions.json` |

**Flow.**

1. The sync publishes tiers 1 and 2 as before and never waits for tier 3. A new input hash deletes the document's tier-3 evidence in the same transaction and resets `llm_status`; it then wakes the LLM tier.
2. The scan of the LLM tier walks the documents still to do (`llm_status IS NULL`, active; a partial index) and either queues them (priority: imprint and about pages 1, services, locations, contact and team 2, home page 3, others 5) or marks them `skipped` (`not_selected`, `no_host`, `host_cap`). The queue holds at most 10 000 items; the scan fills it only while it holds ≤ 100.
3. A worker claims one item, reads the document by real-time get and checks that Solr's input hash is the one the graph published (otherwise the item waits for the sync). It runs tiers 1 and 2 again for the page's known entities and skips pages that are no candidates (`not_candidate`). It reads ≤ `extract.maxInputChars` of text in chunks of ≤ 4 000 characters.
4. Per chunk: the cache first; otherwise one call with the read timeout `llm.timeoutSeconds`, ≤ 1 MiB raw response, the configured `max_tokens`. Nothing is locked while the call runs.
5. Validation ([6.3](#63-llm-use)): the whole answer is refused unless it is one JSON object of the schema (≤ 64 KiB, ≤ 40 entities and claims, known fields only); single items are dropped when a rule fails (types, relation type rules, known IDs, persons) or their quote is not found verbatim in the chunk with the names involved. A hedge word in the quote makes the claim `hedged` whatever the model says.
6. The validated chunk result (or the refusal reason) goes into the cache; all chunks of the document are applied and published in one growth transaction with a compare-and-set on the input hash; the document is marked `done`.
7. A transport failure counts for the breaker and as an attempt; after `llm.maxAttempts` the document is `failed` (`llm_failed`) until its input changes or `llm_retry`. An answer that is refused is no transport failure: the document is `done` and the refusal is cached.

**Robustness.**

| Case | Behaviour | Test |
|---|---|---|
| Model not selected | `llm.state: not_configured`; tiers 1 and 2 as before; no queue writes | `LlmServiceTest.withoutModelOrOutsideTheLlmCollections…` |
| Hanging model | read timeout, attempt, breaker; the sync publishes other documents meanwhile | `LlmServiceTest.aHangingModelBlocksNeither…`, live smoke 3 |
| Unreachable, HTTP error, oversized response | IOException → attempt and breaker | `YacyLlmClientTest` |
| Invalid JSON, prose, wrong shape, too many items | refused whole, counted by reason, cached | `LlmExtractorTest.malformedAnswersAreRefusedWhole`, `LlmServiceTest.invalidAnswers…` |
| Hallucination, prompt injection in the page | dropped as ungrounded; page text only inside the DATA block | `LlmExtractorTest`, `LlmServiceTest.groundedRelations…`, live smoke 2 |
| Repeated failures | breaker 5 min doubling to `llm.breakerMaxBackoffMinutes`; no unbounded retries | `LlmBreakerTest`, `LlmServiceTest.transportFailures…` |
| Changed page | tier-3 evidence replaced, never mixed with old quotes | `LlmServiceTest.changedInputReplacesTheLlmResult` |
| Stop during a call, restart | stop waits ≤ 2 s; the abandoned call writes nothing; the claim is released at the next start | `KgSyncRuntimeTest.theLlmTierRunsInTheRuntime…`, `LlmServiceTest.aRestartKeeps…`, live smoke 4 and 5 |
| Full clear | the LLM queue is emptied with the graph | `LlmServiceTest.aFullResetEmptiesTheQueue` |
| Storage guard refuses growth | item released, retried after a minute; cache writes are optional | `LlmService` (refused writes) |

**Deviations from the plan:**

1. LLM-only statements are `uncertain`, not `supported` (4.4): a verbatim quote proves the page says it, not that the model read the relation correctly. Consumers can still filter them in.
2. The per-host cap counts finished plus queued documents per host, not per extractor version; a changed cap or LLM collection list re-examines skipped documents.
3. The outcome per document lives in `kg_doc.llm_status` / `llm_reason`, not in `last_error` (which belongs to the sync); the control action is `llm_retry` instead of `retry_failed`.
4. The scan of the LLM tier replaces a queue fed at publish time: one index-driven query finds every document still to do, so nothing is lost when the queue is full or Scoutro stops.
5. Refused answers are cached too, so the same text is not asked again with every recrawl.

**Tests** (new: `LlmExtractorTest` 9, `LlmBreakerTest` 2, `YacyLlmClientTest` 4, `LlmServiceTest` 13 including a recrawl during the call, `KgSyncRuntimeTest` +2, `KnowledgeApiTest` extended, `KgStoreTest` migration to v3). The suite (`ant scoutro-agents-test`) passes with 49 test classes; the LLM and store tests also pass on JDK 24. `LLMSelection_pTest`, `TranslatorTest`, `GenerateSourceMasterXliffTest`, `PromptGuardTest`, the flow contract, the locale identifier check and `llm-selection-live-smoke.py` (619 Playwright checks in 15 languages, now with the opt-in column) pass.

**Mutation checks** (each must make a named test fail; all 11 do): no name check and no verbatim check in the grounding, LLM-only statements `supported`, hedge words ignored, no person filter, stale tier-3 evidence kept on a new input, no collection selection, unbounded attempts, no host cap, no breaker, no compare-and-set on the input.

**Live smoke test** `test/scoutro-api/kg-llm-live-smoke.py` (19 checks, JDK 21 and 24) on a disposable peer with a fake OpenAI-compatible endpoint on 127.0.0.1: the knowledge usage is no chat model (`/api/tags`, `/v1/models`; the RAG proxy answers a request for the model `knowledge` with `no_chat_model` and never calls the extraction model); the extract thread runs; a page pushed through YaCy's parser is read, the quoted relation published and the hallucinated holding dropped; the request carries the strict schema and the DATA block; a hanging model times out while the sync publishes other pages; a stop during a hanging call is a clean shutdown; after the restart `llm_retry` finishes the failed documents.

## 20. Package 3 implementation

**Shipped in this PR** (stacked on 2b):

| Area | Files |
|---|---|
| Read projection | `read/KgReader` (entities, statements, evidence, hosts, sources; visibility, per-viewer quality and counts), `KgChangeLog.Viewer` (public accessors), `KgRuntime.reader()` / `lag()` |
| Read routes | `api/KnowledgeRead` (validation, shared with the agent routes of package 4), `KnowledgeApi` (dispatch, `collection`), `ScoutroApiServlet` (query parameters) |
| Page | `ScoutroKnowledge_p.html` / `.java`, `env/scoutro/knowledge.js`, `knowledge.css`; navigation entry in `header.template`; `UiRoutes` `knowledge.graph`; the page is read-only for YaCy's navigation history (`YaCyDefaultServlet`) |
| Integrations | SEO host analysis tab **Knowledge** (`ScoutroSEO_p.html`, `seo.js`), Index Browser links per domain card and URL row (`IndexBrowser_p.html`, `index-browser.js`), dashboard card (`scoutro-dashboard.html`, `knowledge-dashboard.js`) |
| Language and help | `locales/de.lng` (new section and additions for the four pages and the header), `master.lng.xlf`, `check-locale-identifiers.py`; `help/ScoutroKnowledge_p.md`, `help/ScoutroSEO_p.md`, `help/IndexBrowser_p.md`, `help/scoutro-dashboard.md` |
| Contract | generator (7 paths, 7 schemas), `openapi.json`, `actions.json`, `docs/API.md`, `docs/SCOUTRO.md` |

**Visibility, as implemented ([4.5](#45-visibility)).** A viewer is the administrator without a filter (all followed collections) or a set of collection IDs (the `collection` parameter; in package 4 an agent's scope). Every query restricts evidence to documents of those collections (`kg_doc_collection`), statements to those with visible evidence (`kg_statement_scope`), entities to those with a visible statement (`kg_entity_scope`). Names, aliases, identifiers, quality, `first_seen` (the earliest visible observation for a filtered viewer), `last_confirmed`, counts, hosts, possible duplicates and the collections listed with a source or a piece of evidence are computed from the visible evidence alone. An invisible object is `404 not_found` exactly like an unknown one, a redirect is followed only to a visible survivor, and the name search only matches visible name and alias statements. Quality is recomputed per viewer with the rules of 4.4, including the conflict rule over the viewer's statements; for the administrator it equals the stored quality.

**Page.** Views `overview` (state, storage meter with 80 %/90 % colours, synchronisation, LLM tier, controls including `confirm_reconcile` only while a run waits, recent events), `objects` (name search, type, quality, host; 25 per page; cards on narrow screens), `object` (names, identifiers, hosts, possible duplicates, facts and relations in both directions with quality, hedging and an "only from the LLM tier" badge; evidence on demand with excerpt, page link, source view and Index Browser link), `source` and `settings`. Every view keeps `collection`; status, storage and events describe the whole graph and say so. All text is set with `textContent`; external links only for http(s), with `noopener noreferrer`.

**Deviations from the plan:**

1. The settings view is read-only: it shows the effective `scoutro.kg.*` values and their problems and points to the configuration; there is no write route or live reload. Settings keep taking effect at the next start, as in packages 1 to 2b. A write route with a validated reload is a follow-up.
2. Reads of a disabled graph answer `409 kg_disabled` like the control route, not 503 as 8.1 listed.
3. Entity lists are ordered newest first (`created_seq`); there is no sort parameter.

**Tests.** `KgReaderTest` (5): the administrator sees everything; a viewer of one collection gets no name, alias, identifier, value, count, host, evidence row, source, search hit or host listing of the other collection, and its objects are not found; an unknown collection sees nothing; quality and the quality filter per viewer (a source gone in one collection is stale there and supported elsewhere); filters, paging and prefix search. `KnowledgeApiTest` (+2): parameter validation of every read route (400, 404, 405, limits, IDs, collection names) and Digest for every read route through the servlet. `AdminSecurityTest`, `DashboardMetricsTest` (links). Live: `test/scoutro-ui/knowledge-live-smoke.py` runs a disposable peer with two collections and the fake model, checks the read API's isolation (7 checks) and then `knowledge-ui-test.mjs` (182 Playwright checks: Digest for the page and the routes; English at 390 and 1280, German at 360, 390, 412, 768 and 1280; overview, objects with and without the collection filter, the object view in one collection without the other's alias and VAT ID, the LLM badge, the verbatim quote as evidence, the source view, not found across collections, settings; the SEO tab, the Index Browser links, the dashboard card, pause and resume through the page; no JavaScript error and no horizontal overflow). The existing SEO, Index Browser, report and dashboard UI tests pass with the new tab, links and card.

## 21. Package 4 implementation

**Shipped in this PR** (stacked on package 3):

| Area | Files |
|---|---|
| Export and change feed | `read/KgExport` (export pages with cursor, the stream of the download, changes with records), `KgReader` (agent mode `forAgents()`, helpers shared within `read`) |
| Routes | `api/KnowledgeRead` (`export`, `changes`; path shapes before the state check; base path for `full_sync`), `KnowledgeApi` (`download`, cursor error mapping 400/410), `ScoutroApiServlet` (`/v1/kg/export/download`) |
| Agents | `AgentActionRegistry` (`kg.read`, `kg.export`), `AgentApi.route` (`kg/...`), `ScopedActions` (viewer from the scope, path/grant check, injectable runtime) |
| Chat | `read/ChatFacts` (selection), `KgRuntime.chatFacts`/`config()`, `KgConfig` (`chat.*`), `net.yacy.ai.rag.GraphFacts` (access, scope, budgets, numbered entries, metadata), `RagContext.Source.kind`, `RAGProxyServlet`, `yacychat.html` (badge) |
| Tools | `scoutroctl kg …`, the MCP adapter (catalog-driven, unchanged code), generator (`GRANTS`, `FAMILIES` per read route, mcp/cli names, agent mirrors), `openapi.json`, `actions.json` |
| UI, language, help | settings view shows the chat settings; `de.lng` and `master.lng.xlf`; `help/ScoutroKnowledge_p.md`, `help/ScoutroAgents_p.md`, `help/yacychat.md`; `docs/API.md`, `docs/ACTIONS.md` |

**Export ([8.3](#83-export-and-change-feed)).** `GET /kg/export?cursor&limit&include=evidence&collection` returns pages of records: first every visible entity (the detail JSON of the read routes), then every visible statement, both in row order, each page one bounded read lease. With `include=evidence` a statement carries its newest 20 visible pieces of evidence. The cursor `<epoch>:<as_of_seq>:<e|s><rowid>` carries the change sequence at the start of the export; `next_changes` is `<epoch>:<as_of_seq>`, the change cursor to follow afterwards. A cursor of another epoch is 410 `epoch_changed`; a cursor whose `as_of_seq` retention has already passed is 410 `cursor_expired` (the export could no longer be completed with the feed); both carry `details.full_sync`. The administrator download `GET /kg/export/download?format=ndjson|json` streams the same records in pages of 200 with a header and a trailer (`counts`, `complete`, `error`); errors before the first page answer as JSON, a failure later ends the file with `complete:false`.

**Change feed.** `GET /kg/changes?cursor&limit&expand&collection` is `KgChangeLog.read` for the viewer (package 1: delete notices across coalescing, the cursor advances over invisible rows). `expand=true` (limit ≤ 100) adds the current record of each upsert as the viewer sees it, or null if it vanished since.

**Agents.** `kg.read` covers the seven read routes, `kg.export` the export and the change feed; both are scoped and never part of a preset, `kg.export` is for external agents only. `ScopedActions` builds the viewer from `filterCollections` (the requested collection, checked against the scope, or the whole scope; `allCollections` is the administrator view) and refuses a path that does not belong to the authorised grant. Agent answers name the extractor without the model and prompt hash and carry no `lag`. `kg/status`, `kg/control` and the download are not routed for agents. The generator maps the admin operations to their grants by prefix (`kg.entit`, `kg.statement`, `kg.host.`, `kg.source` → `kg.read`; `kg.export`, `kg.changes` → `kg.export`), never by the bare `kg.` prefix, so that status, control and `kg.download` stay administrator-only.

**Chat ([8.4](#84-chat)).** `GraphFacts.collect` runs after the retrieval and before `RagContext.build`; it reserves its characters first and numbers its entries after the search sources. Access: `LOCAL` and `ADMIN`, `GUEST` only with `chat.allowGuests`. Scope: the request's collection (or `collection:` in the question), else every followed collection; a global question without a collection gets none. `ChatFacts` ranks entities the question names (two distinctive name words, or the only one) above entities with evidence on the retrieved pages, takes at most 4, and their current supported and uncertain facts in a fixed order; each fact is attached to its best visible page (retrieved first, then active, then latest loaded). One entry per entity and page, titled `Scoutro knowledge graph: <name>`, says that the facts were recorded from that page and are not model knowledge, and marks uncertain facts (`only read from the page text by a language model`, `the page states it with reservation`). The time budget runs the selection on its own small pool (`ScoutroKG.chat`, 2 threads, queue 4) and the read lease gets the same deadline; a timeout, a full pool or an error skips the step.

**Deviations from the plan:**

1. Records carry the discriminator `record` (`entity`, `statement`, `header`, `trailer`), because `type` is the entity type. Evidence is nested in its statement (at most 20) instead of separate `evidence` lines.
2. Agents export through JSON pages with a cursor. The streamed download is a separate administrator route (`/kg/export/download`, operation `kg.download`, not grantable) rather than a `format` parameter of the agent route.
3. `kg.export` is not offered to research workers (an internal agent kind with no need for bulk export).
4. The URL of a chat entry is the best visible, current evidence page by its graph state (`kg_doc`, kept in line with Solr by the sync); there is no extra Solr round trip inside the 300 ms budget.
5. Agent answers leave out `lag` (graph-wide) and the model name in evidence (configuration detail).

**Known limits.** Change sequence numbers (`as_of.seq`, cursors) are graph-wide counters: a consumer can see that changes happened elsewhere, never what changed. The export is not a snapshot (by design; idempotent upserts plus the feed). A one-word mention of a multi-word name does not select the entity by name; the retrieved pages usually still bring it. Content RAG still lets AI Shield guests choose any collection (open point O5, unchanged); graph facts stay off for guests by default, so the gap does not widen.

**Tests.**

- `KgExportTest` (8): pages cover every record once (entities before statements, page sizes, the start cursor); an export of one collection holds nothing of the other (names, values, identifiers, pages, collections at evidence); cursor checks (malformed, ahead, other epoch, retention); the change feed after the export carries later changes with the viewer's records, and the whole feed from its start too; a delete notice when an object leaves the viewer's collections; the stream with header, records and trailer; a failure after the header ends it incomplete; a failure before the header is thrown.
- `KnowledgeApiTest` (+2): export and changes parameters and cursor errors with `full_sync` for both bases; the download in both formats, parameter errors before anything is opened, and a disabled graph; Digest for the new routes through the servlet.
- `AgentKnowledgeTest` (3, real graph): separate grants for their own paths, presets, research workers, admin routes 404, 405; an agent of one collection gets nothing of the other through every read route, the export with evidence and the expanded change feed, foreign collections are 403; cursor errors point to the agent export.
- `AgentCatalogTest` (+1, routes): every kg action names its grant and its agent path routes to it; admin routes are neither routed nor described.
- `ChatFactsTest` (4), `GraphFactsTest` (5): the named entity with the viewer's facts and pages only; retrieved pages only when visible; one shared word does not name an entity; the facts bound; access rules, scope, global questions, timeout and errors, numbering, labels, marks, citations, follow-up parsing, the character budget.
- `test_mcp_adapter.py` (+1): the kg tools follow their two grants, path and boolean arguments, no admin tool.
- Mutation checks: 19 of 21 mutations are killed (viewer ignored in the export, its evidence, the feed and its records; cursor checks; agent scope, grant mapping, admin routes, `lag`, `full_sync`; guest access, scope, global questions, time and character budget, uncertainty marks; the name rule). The two survivors replace the viewer in the candidate and statement queries of `ChatFacts`; both are equivalent because the per-viewer computation keeps only facts with visible current evidence.
- Live: `test/scoutro-api/kg-agents-live-smoke.py` (disposable peer, two collections, fake model for extraction and chat; agents created in the wizard) with 33 checks: grants, isolation of reads, export and changes, 403/404/410, evidence without the model, `scoutroctl` and the MCP adapter with the token, the administrator download in both formats and its login, the chat in kga and kgb with labelled, cited, scoped graph sources, and none for a guest. Then `test/scoutro-ui/chat-graph-ui-test.mjs` (32 Playwright checks, English and German, 360 and 1280): the badge, the title, the page link, the citation link, nothing of kgb, no overflow, no JavaScript error.
- Regression: `ant scoutro-agents-test` (53 classes), `scoutro-rag-test` (30), `scoutro-llm-security-test` (32) green; the live smokes of packages 2a (31), 2b (19) and 3 (7 + 182), LLM selection (619 + 40), SEO (282), Index Browser (433), system chat (71), AI Lab (560) and crawl flow (123) pass with the chat change; the package 4 tests also pass on JDK 24.

## 22. Package 5 implementation

**Shipped in this PR** (stacked on package 4):

| Area | Files |
|---|---|
| Budgets and levels | `KgConfig` (10 GiB, 2 GiB, `budget.noticePercent`/`warnPercent`, maintenance share 10 %, `level()`), `budget/StorageGuard` (level, `backup/` and `rebuild/` counted), `budget/JsonLdCapturePolicy` (level), `KgRuntime` (level events); page meters, banner, dashboard card |
| Backup and restore | `store/KgBackup` (verify, metadata with SHA-256, names, retention, prepare a restored or rebuilt database), `KgBackups` (thread `ScoutroKG.backup`, schedule, status), `store/KgStore.backupTo` (`VACUUM INTO` on a read-only connection), `KgRuntime.backup`/`restore`; routes `/kg/backups`, `/kg/backups/{file}` |
| Identity rebuild | `KgRebuild` (shadow store and sync, progress, verify with the brake, ID redirects, LLM cache copy, cancel), `KgRuntime.rebuild`/`rebuildCancel`/`rebuildConfirm`/`swapIn`, `KgPaths` (`rebuild/`) |
| Identity resolution | `resolve/IdentityResolver` and `extract/Vocabulary` (`operator_name`: the parent organisation of a facility page is the declared operator, [4.3](#43-identity-resolution)) |
| Data minimality (O7) | `resolve/Normalizers.roleEmail`, `redactPersons`, `excerpt`; `JsonLdExtractor` and `RuleExtractor` (version 2), `publish/Publisher` |
| API, tools, UI | `KnowledgeApi` (actions `backup`, `restore`, `rebuild`, `rebuild_cancel`, `rebuild_confirm`), `ScoutroApiServlet` (backup download), generator, `openapi.json`, `actions.json`, `scoutroctl kg backup|backups|backup-download|restore|rebuild|rebuild-cancel|rebuild-confirm`; `ScoutroKnowledge_p.html`, `knowledge.js`, `de.lng`, `master.lng.xlf` |
| Measurement and tests | `KgLoadMeasurement` (`ant scoutro-kg-measure`), `test/scoutro-api/kg-e2e-live.py`, `test/scoutro-ui/kg-e2e-ui.mjs`, `test/scoutro-api/kg-rollback-live.py` |
| Documentation | `docs/SCOUTRO_KNOWLEDGE.md` (operator guide), this section, `docs/API.md`, `docs/ACTIONS.md`, `docs/SCOUTRO.md`, `help/ScoutroKnowledge_p.md` |

### 22.1 Budgets and levels

- **Defaults (O1).** `budget.maxBytes` is 10 GiB for everything under `DATA/SCOUTRO/knowledge` (database, WAL, temp files, `backup/`, `rebuild/`). `jsonld.maxTotalBytes` is 2 GiB of uncompressed JSON-LD in the Solr index. Both are protection limits: nothing is reserved.
- **Levels** of both budgets:

  | Level | From | Effect |
  |---|---|---|
  | `notice` | `noticePercent` (70) | status only |
  | `warning` | `warnPercent` (80) | banner on the page and in the dashboard |
  | `brake` | `pausePercent` (90) | new growth pauses, with the existing hysteresis down to `resumePercent` (80); for JSON-LD the capture pauses |
  | `full` | the budget | the hard limit (`max_page_count` / no capture) |

  Every change of level is an event (`storage_level`, `jsonld_level`); warnings are logged.
- **The crawl is never stopped.** At the JSON-LD brake, pages are indexed without the field; at the graph brake, only graph growth and backups pause. This is the deviation that the request allowed: the brake pauses rather than deletes, and nothing of the index is touched.
- **Maintenance share** 10 % instead of 20 %, so the database file is capped at the brake rather than at 80 %. Small budgets get the smallest share that holds the WAL and temp limits.

### 22.2 Data minimality (O7)

The review of O7 found two gaps in the extraction of packages 2a and 2b, closed here:

1. **E-mail addresses.** The JSON-LD and imprint rules kept any e-mail address of an organisation, also a person's (`max.mustermann@`). Now only role mailboxes are kept (`info@`, `kontakt@`, `verwaltung-berlin@`, … — the first token of the local part from a fixed list) and mailboxes named after the organisation's own domain. The imprint rule takes the first role mailbox.
2. **Names in excerpts.** The ±30-character windows of the rules could carry the managing director's name from the next line of an imprint, and an employee's e-mail address from the line before. Names after a role marker (Geschäftsführer, Inhaber, vertreten durch, Ansprechpartner, Verantwortlich …, Leitung, Datenschutzbeauftragte, …) and after a salutation become `[…]`, and so does an e-mail address that is no role mailbox, also when the window cuts it. Organisations after a marker stay. Applied where the rules cut the excerpt and again where every tier's evidence is stored.

Extractor versions 2: existing graphs re-extract tiers 1 and 2 at the next start.

### 22.3 Backup and restore

- **Backup (O6).** `VACUUM INTO` on a dedicated read-only connection writes `backup/graph-<UTC>.db`.
  - Admitted as growth with the logical size as estimate; skipped with the guard's reason while growth is paused or the budget or disk reserve would be crossed.
  - Interrupted at `backup.maxMillis`; verified with `quick_check` and the meta table; then a metadata file with SHA-256, size, schema version, epoch and counts.
  - One at a time on the thread `ScoutroKG.backup`, on request and every `backup.intervalDays` (7). The newest `backup.keep` (1) are kept; safety copies until the next regular backup.
- **Restore.**
  - It checks the file first (checksum of its metadata, `quick_check`, schema version, epoch) without touching the graph.
  - Then it stops the graph, keeps the current database as `graph-<UTC>-before-restore.db`, and copies the backup into place with a new dataset epoch, a clean-shutdown mark and a required reconcile.
  - It starts the graph again; if that fails, the previous graph is put back.
- **Portable.** The file is a plain SQLite database. Restoring one from another installation is copying it into `backup/` under a backup name; without the metadata file only the SHA-256 check is skipped.

### 22.4 Measurements and budget evaluation

**Method** ([7.8](#78-measurement-method-package-5)). `KgLoadMeasurement` runs the graph as in production: its own threads, the real clock, real file sizes, the embedded Solr core of the shipped `defaults/solr`. It uses a synthetic corpus from a fixed seed that imitates German small-business and care websites:
- **Hosts:** hosts of 5 to 54 pages (home, imprint, contact, 1–4 facility pages for care providers, news pages with 2–6 KB of text).
- **JSON-LD:** 45 % of the hosts with an SEO-plugin graph on every page, 25 % with JSON-LD on home and facility pages, 30 % without; 5 % of the hosts add a 12 KB FAQ graph; 2 % of the blocks are truncated.
- **Identities:** every 20th host is a second domain of the previous organisation; every 33rd reuses another organisation's name.
- **LLM collection:** 30 % of the hosts are care providers in the LLM collection.

Per size the run does:
- the indexing with the graph following;
- a full reconcile;
- a recrawl of 10 % with changes;
- the deletion of 5 %;
- a backup, a restart and an identity rebuild;
- the LLM tier with a stand-in model (50 ms per call), and its fallback when the model is unreachable.

The machine had 4 CPUs (the Olares container limit is also 4 CPUs) on JDK 21.

**Corpus and storage** (after the indexing):

| Documents | Hosts | Entities | Statements | Evidence | Graph (logical) | per document | per evidence row | JSON-LD (raw) | per document | Solr segments (these fields) |
|---|---|---|---|---|---|---|---|---|---|---|
| 2 000 | 75 | 227 | 1 255 | 3 950 | 2.1 MiB | 1 098 B | 556 B | 2.1 MiB | 1 108 B | 5.1 MiB |
| 10 000 | 349 | 956 | 5 537 | 20 332 | 9.3 MiB | 972 B | 478 B | 15.2 MiB | 1 597 B | 27.0 MiB |
| 50 000 | 1 725 | 4 532 | 26 185 | 98 763 | 44.4 MiB | 930 B | 471 B | 80.2 MiB | 1 682 B | 136.4 MiB |

**Times:**

| Documents | Heap | Graph after the index (docs/s) | LLM tier (calls) | Reconcile | Recrawl 10 % | Delete 5 % | Backup | Stop / start | Start reconcile | Rebuild | after the swap |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2 000 | 1 GiB | 13 s (133/s) | 32 s (349) | 0.6 s | 2.9 s | 1.1 s | 0.4 s, 1.9 MiB | 0.06 / 0.01 s | 0.4 s | 14 s | 7 s |
| 10 000 | 600 MiB | 57 s (161/s) | 162 s (1 848) | 1.0 s | 6.3 s | 1.5 s | 0.4 s, 8.2 MiB | 0.04 / 0.01 s | 0.8 s | 56 s | 21 s |
| 50 000 | 2 GiB | 235 s (197/s) | 750 s (8 352) | 3.5 s | 29.5 s | 5.8 s | 0.8 s, 38.7 MiB | 0.02 / 0.02 s | 3.2 s | 277 s | 148 s |

**Memory and bounds:**

| Documents | Heap peak (Solr indexing included) | Heap after GC | RSS peak | Work queue peak | Change set peak | WAL peak | Shadow peak | LLM queue peak |
|---|---|---|---|---|---|---|---|---|
| 2 000 | 124 MiB | 33 MiB | 424 MiB | 1 699 | 704 | 4.2 MiB | – | 198 |
| 10 000 | 168 MiB | 33 MiB | 502 MiB | 9 549 | 1 319 | 4.3 MiB | 9.2 MiB | 186 |
| 50 000 | 423 MiB | 34 MiB | 932 MiB | 47 150 | 1 722 | 5.3 MiB | 41.2 MiB | 201 |

**Control run with the final code.** The tables were measured before the fixes found end to end and by the rollback test: the operator's name ([22.6](#226-identity-resolution-the-operators-name)), the excerpt redaction ([22.2](#222-data-minimality-o7)), the sync gaps ([22.9](#229-sync-lost-changes-and-a-full-queue)) and the field type ([22.10](#2210-rollback-the-fields-index-options)). A run with the final code at 10 000 pages (600 MiB):

| | Entities | Statements | Evidence | Graph (logical) | per document | Solr delta of the field | Graph after the index | Heap peak | Rebuild |
|---|---|---|---|---|---|---|---|---|---|
| measured above | 956 | 5 537 | 20 332 | 9.3 MiB | 972 B | 2.37 MiB | 57 s (161/s) | 168 MiB | 56 s |
| final code | 759 | 5 124 | 20 078 | 9.1 MiB | 953 B | 2.38 MiB | 58 s (155/s) | 146 MiB | 59 s |

21 % fewer entities, because a facility page's parent organisation now resolves to its operator. The storage per page and the times stay within a few percent, and the token-free field type costs no measurable index space. The evaluation below holds.

**Findings:**

- **Linear growth.** The graph needs about 0.9–1.1 KB per crawled page, about two evidence rows per page at about 470–560 B each. Entities are about 9 % of the pages.
  - Temp files stayed at 0, and the WAL far below its limit (64 MiB).
  - A backup is 0.87 × the logical size.
  - The shadow of a rebuild is about 1 × the logical size.
  - Right after a rebuild, the directory holds the database, the regular backup and the kept previous graph: about 3 × the logical size until the next regular backup.
- **JSON-LD.** The raw size is 1.1–1.7 KB per page with this corpus. Solr stores the field compressed: the second core without the field was 12.9 MiB smaller at 50 000 pages for 80 MiB raw, a factor of 6.5. Synthetic JSON-LD repeats itself more than real JSON-LD, so the real factor is likely lower.
- **Throughput.** The graph follows at 130–200 pages per second. The initial backfill of one million pages takes about 1.5 h; a full reconcile scans 50 000 pages in 3.5 s.
- **Heap.** The graph itself holds about 1 MiB of heap when idle. The peaks come from Solr's indexing of 500-document batches with the graph processing at the same time; the work queue absorbed the difference (47 150 items at 50 000 pages, cap 200 000). YaCy's default `-Xmx600m` was enough for 10 000 pages.
- **Restart.** Stop and start take milliseconds, and the start reconcile scales with the index (3.2 s for 50 000). The hard kill is measured in the end-to-end test.
- **LLM tier.** With 50 ms per call, the stand-in model took 8 352 calls for 50 000 pages (the per-host cap of 25 holds). With a local model at 5–20 s per page and `llm.parallel=1`, that is 12–46 h for the first pass.
  - The cache answers unchanged pages: the rebuild copied 9 431 cached answers at 50 000 pages. The 963 model calls after it were mostly the LLM work of the recrawl (pages with changed text) that was still queued when the rebuild started.
  - **Fallback.** After three failed calls the breaker opens (5 min, growing to 60). Tiers 1 and 2 published the 40 new pages meanwhile within 1–2.5 s, and `llm_retry` brought the tier back within the scan pause of about 60 s.

**Budget evaluation** (reserve factor 1.5 on the per-unit costs):

- **Graph, 10 GiB.** The brake is at 9 GiB, less 128 MiB for WAL and temp files. A database plus one regular backup takes 1.87 × the logical size, so the database may reach about 4.7 GiB: about **3.5 million pages** of this mix.
  - If a rebuild must stay possible at any time (shadow plus the kept previous graph), about 2.3 GiB: about **1.7 million pages**.
  - Against the Olares orientation values (1.83 TiB storage, about 1.3 TiB free, not current), 10 GiB is below 1 % of the free space. The graph is a few percent of a real index of the same pages: YaCy stores about 10–50 KB per page in `collection1` and `webgraph`, far more than the few fields measured here.
- **JSON-LD, 2 GiB raw.** The brake is at 1.8 GiB. At 1.7 KB per page × 1.5 that is about **0.7 million pages** with SEO-plugin JSON-LD on half of the hosts. Real plugin graphs of 3–6 KB per page bring it down to 0.3–0.6 million.
  - On disk this is only about 0.3 GiB, because Solr stores the field compressed. Reaching the brake costs no crawl, only the JSON-LD of new pages: tier 1 falls back to metadata and the rules.

**Recommendation** (the defaults stay 10 GiB / 2 GiB, as asked):

1. Keep **10 GiB** for the graph. It is safe and rarely reached before the index itself becomes the limit.
2. Keep **2 GiB** for JSON-LD as the start value. For a crawl beyond about 500 000 pages with SEO plugins, raise it to **4–6 GiB** when the level reaches `warning`. That costs about 0.6–1 GiB of disk, because the budget counts uncompressed bytes.
3. **Before rollout, find out whether the Olares manifest's `limitedDisk: 20Gi` is enforced** (O1). If it is, the whole app, index included, must fit 20 GiB: lower the graph budget to about 4 GiB and JSON-LD to about 1 GiB, or raise `limitedDisk`. The disk reserve check protects the `DATA` filesystem in any case.
4. Give YaCy at least 2 GiB heap on Olares (container limit 6 GiB) for large crawls. The graph's gate `gate.minFreeHeapMB` (256) pauses its work before the heap gets tight.

**Not measured here** (no access to the real Olares, O1): the real index size per page, real JSON-LD sizes, a real local model's latency, and the free space on the production `DATA` volume.

### 22.5 Identity rebuild

The administrator action "re-resolve identities" (`rebuild`, `rebuild_cancel`, `rebuild_confirm`; the page; `scoutroctl kg rebuild…`):

1. **Preconditions.** The Solr sync is required, and the rebuild is refused while a backup, a restore, another rebuild, a full reset or a reconcile waiting for its confirmation runs (409 `operation_running`, reason `reconcile_busy`). The remaining budget must hold 1.2 × the logical size plus the WAL and temp limits, and at least the smallest budget (503 `kg_write_refused`, reason `rebuild_space`).
2. **Building.**
   - A shadow store in `rebuild/` gets its own sync: the normal backfill from Solr, tiers 1 and 2, the current identity rules. It sits behind the same gates and has the remaining budget as its own budget; the guard counts it against the graph's budget.
   - The current graph keeps serving reads and following Solr.
   - Progress: pages scanned, published, queued, the shadow's storage.
3. **Verifying.** `quick_check` and the counts, then the reconcile's mass-deletion brake against the current graph: lost ≥ `reconcile.brakeMinDocs` and > `reconcile.maxDeleteFraction` of the documents, or an empty shadow. With the brake, it waits for `rebuild_confirm`.
4. **Swapping.**
   - Entity IDs of the current graph that the shadow does not know (merged IDs, redirects, IDs derived from another first key) become redirects to the shadow entity holding most of their identity keys.
   - The LLM cache (with the extractor rows mapped) and the manual pause are copied.
   - The current database is kept as `backup/graph-<UTC>-before-rebuild.db`. The shadow takes its place with its own new epoch, and the graph starts again; a full reconcile catches up.
   - If the new graph does not start, the previous one is put back.
5. **Ending.** A cancel, a stop of Scoutro or a failure before the swap deletes the shadow and leaves the graph unchanged. A shadow left behind by a crash is deleted at the next start (`phase: interrupted`, event `rebuild_interrupted`).

**Why a rebuild from Solr** instead of re-running the resolver over cached extractions: it uses the current extractors and rules (also new ones), needs no second cache of tiers 1 and 2, and is the same path as the first backfill. LLM answers come from the copied cache, so unchanged pages cost no model call.

### 22.6 Identity resolution: the operator's name

The end-to-end crawl showed a gap of package 2a: the `parentOrganization` of a facility page (a name with legal form, no identifier) became a document-local organisation next to the declared operator of the same domain (three "Lindenhof Pflege gGmbH" instead of one).

- **The new key.** `operator_name` (the normalised legal name within the registrable domain) is given to organisations that are not declared operators.
- **How it resolves.** It looks up only the `site_operator` key, and the operator, once declared, takes in the organisations seen under its name before. So the result does not depend on the crawl order.
- **What it never does.** Two `operator_name` mentions never merge with each other, so a portal that lists two providers with the same legal name keeps them apart, and the key never derives an ID.

Tested in `PublisherTest` (both orders, another domain, the portal case) and end to end.

### 22.7 Deviations

1. **The brake pauses growth; it does not delete.** It sits at 90 % for both budgets, with the hard limit at the budget. The JSON-LD budget counts uncompressed bytes. That is conservative: Solr's share on disk is about 1/6.
2. **The rebuild reads Solr, not cached extractions** ([22.5](#225-identity-rebuild)), and LLM-only facts return from the copied cache after the swap. Between the swap and the LLM tier's pass they are missing (148 s at 50 000 pages with the stand-in model).
3. **Restore and rebuild give the graph a new dataset epoch.** Export consumers get 410 `epoch_changed` and sync again, instead of a feed of the differences.
4. **No version bump, release tag, image or Olares change.** The release files of [11](#package-5-release-completion) (`scoutro.properties`, the publish alias, `BUILD.md`) stay as they are, as instructed. [14](#14-version-recommendation) remains the recommendation.
5. **The rollback test runs the version before the graph from its sources.** It uses commit `5ee2d29`, compiled from a worktree, not the published `0.6.0` image.

### 22.8 Known limits

- **Host lookup.** The host routes find a host by name on the ports 80 and 443 only; a site on another port is found through its pages, not its host name.
- **Name redaction.** The person-name redaction in excerpts is rule-based. A name without a role marker or salutation is not recognised, and a capitalised word after a marker may be hidden too.
- **Synthetic corpus.** The measurements use synthetic pages. Real JSON-LD is less repetitive, and real YaCy documents are larger; see the evaluation for the margins.
- **A remote Solr.** The schema that `api/schema` generates for an external Solr declares `ld_json_txt` from `CollectionSchema`, still `indexed="false"`, so a rollback on such a Solr keeps the gap of [22.10](#2210-rollback-the-fields-index-options). The embedded Solr uses `defaults/solr/schema.xml`.
- **IDs after a rebuild.** An entity's ID derives from the first key it was seen with, and the older entity survives a merge. A rebuild reads in another order and may pick another surviving ID (seen end to end for the operator that took in its facility pages' parent organisation); the old ID redirects. Statement IDs of such entities change.

### 22.9 Sync: lost changes and a full queue

Two gaps of package 2a, found by the end-to-end overflow test and closed here:

1. **Lost changes were not rescanned.** The work queue dropped changes (or the change set overflowed), and the reconcile requested at once could start before Solr's searcher showed the documents. The real-time get and the capture see a document at once, a search only once a new searcher opens: at the latest with the `autoCommit` (180 s). That reconcile completed without them and consumed the request.
   - Now `Reconciler.lost` notes the time.
   - A run that started before the lost documents are surely visible (`VISIBLE_AFTER_MILLIS`, 200 s) is followed by another one (reason `lost_changes`).
2. **The full-queue retry waited a fixed hour.** A reconcile that could not enqueue everything because the queue was full retried after an hour. Now it runs as soon as the queue is below half of `queue.maxItems`; the hour stays the upper bound.

Tests: `SyncServiceTest.changesLostBeforeASearchCanSeeThemComeWithALaterReconcile`, `SyncServiceTest.aReconcileThatTheFullQueuePostponedRunsOnceTheQueueHasRoom`. End to end: 1 100 pages pushed during a pause with caps of 1 000 are all in the graph 197 s after the resume.

**The integrity state (package 1).** The suite showed a race once under load: a passed integrity check read `ok` before its write block was lifted and before `integrity_required = 0` was stored. Now `ok` is published last. With an injected delay of 300 ms before the store, `KgRuntimeTest.watchdogAbortsAnOverlongIntegrityCheckAndReadsStayBoundedMeanwhile` fails every time in the old order and passes in the new one. A crash in between was always safe: the next start checks again.

### 22.10 Rollback: the field's index options

The rollback test found a gap of package 2a: the old version could not write a page that carries `ld_json_txt`.

- **What failed.** The field was declared `indexed="false"`. A version without the graph maps the name to its dynamic `*_txt` field, which is indexed text.
  - Lucene fixes a field's index options for the whole index. The old version's partial update of such a page failed with `cannot change field "ld_json_txt" from index options=NONE to inconsistent index options=DOCS_AND_FREQS_AND_POSITIONS`.
  - YaCy's postprocessing writes pages this way (`CollectionConfiguration.postprocessing`, partially or as a whole document), so it would have failed on these pages at every run.
  - Every dynamic field of the old schema is indexed, so another field name does not help.
- **The fix.** The new type `text_stored` is a `TextField` with the index options of `text_general` (positions and norms). Its analyzer emits no token: a keyword tokenizer, the value replaced by the empty string, empty tokens dropped.
  - The field stays stored, has no postings and cannot be searched.
  - The old version indexes the field as text when it rewrites a page; the next write of the page by Scoutro has no token again.
- **Verified** on one core (new version, old version, new version):
  - a 70 KB value and an empty value are stored;
  - a term query for `organization` finds nothing after the new version's write, one page after the old version's partial update, and nothing after the new version's rewrite.
  - End to end: `kg-rollback-live.py` ([22.11](#2211-tests)).
- **Indexes of pre-release builds.** An index written by a build of packages 2a–4 has the field with the old options, and the new type cannot write into it (the same error the other way round). Such test data must be deleted or reindexed. No release carried the field.

### 22.11 Tests

- **Unit and integration tests:**

  | Test class | What it covers |
  |---|---|
  | `KgConfigTest`, `StorageGuardTest`, `JsonLdCapturePolicyTest` | Levels, thresholds and validation; `backup/` and `rebuild/` counted against the budget |
  | `KgBackupTest` (6) | Verified portable file; restore with a new epoch, keeping the previous graph; damaged, foreign, newer and unknown files change nothing; a pause skips the backup; retention, including safety copies; the schedule |
  | `KgRebuildTest` (6, embedded Solr) | A wrong merge is split and stored IDs redirect; more than one scan page; cancel; brake and confirm; `reconcile_busy`; no room; stop mid-rebuild; leftover shadow |
  | `PublisherTest` (+3) | No person names in stored excerpts; the operator's name in both orders, another domain, the portal case |
  | `ExtractorsTest` (+2) | Role mailboxes only; redaction of names and of e-mail addresses, also cut by the window |
  | `SyncServiceTest` (+2) | Lost changes come with a later reconcile; a reconcile postponed by the full queue runs once the queue has room |
  | `KnowledgeApiTest`, `AgentKnowledgeTest`, `AgentCatalogTest` | The new actions match the OpenAPI enum; the new admin routes are neither routed nor described for agents |
- **Mutation checks:** 27 of 28 mutations are killed: levels and thresholds, the JSON-LD brake, `backup/` and `rebuild/` in the budget, the restore's checksum and epoch, retention and safety copies, the rebuild's brake, ID redirects, cancel, space check, leftover shadow, `reconcile_busy`, stop and the single rebuild, the operator's name (lookup, taking in, no ID), role mailboxes, e-mail addresses and names in excerpts, the excerpt window, the queue-room retry and the rescan of lost changes. The survivor makes the rebuild treat the shadow's backfill as completed as soon as nothing is pending and the queue is empty. With an empty shadow the brake cannot stop that run, so the state is equivalent in every reachable case; the check stays as a guard for a failed run.
- **Interface:** `knowledge-ui-test.mjs` creates, downloads and restores a backup and runs a rebuild through the page (193 Playwright checks in English and German, five widths).
- **End to end:** `kg-e2e-live.py`, 62 checks on three disposable peers, and `kg-e2e-ui.mjs`, 20 Playwright checks in English at 1280 and German at 390.
  - **The 16 steps:** crawl a new domain (70 s for the fixture sites), JSON-LD in the index, facts, entity, evidence, API, UI, agent and chat, change and recrawl (34 s), update, deleted source, reconcile (2 s), restart, re-check, backup (0.5 s), restore in a fresh peer (0.6 s).
  - **Edge cases:** two collections with two organisations of the same name, a large page (300 KB text, a 36 KB FAQ graph, bounded to 16 KiB), invalid JSON-LD, the model unreachable at first, a disk reserve the graph cannot meet and a 1 MiB JSON-LD budget while the crawl goes on, a queue overflow during a pause (all 1 100 pages in the graph 201 s after the resume), and a hard kill in the middle of processing and of a reconcile (recovery 12.5 s).
  - **Rebuild:** 2.6 s; the stored ID of the operator leads to it.
- **Rollback:** `kg-rollback-live.py`, 16 checks: the new version indexes pages with JSON-LD and builds its graph; the version before the graph (commit `5ee2d29`) starts on the same `DATA`, finds every document readable and searchable, and indexes a new page without an error; a partial update with its classes and core configuration keeps the field; the new version again: the graph is intact, reconciles, takes in the old version's page and rewrites the updated one without a token in the field.
- **Suites:** `ant scoutro-agents-test` 55 classes and 510 tests, `scoutro-rag-test` 30, `scoutro-llm-security-test` 32, `scoutro-report-test` 127, `scoutro-dashboard-test` 17, all green on JDK 21. On Temurin/OpenJDK 24.0.2: the 15 classes of the knowledge graph's runtime, store, sync, budgets, backup, rebuild, extraction, API and agents with 142 tests. Live smokes: package 2a (`kg-live-smoke.py`, 31), 2b (`kg-llm-live-smoke.py`, 19), 3 (`knowledge-live-smoke.py`, 7 + 193 Playwright checks) and 4 (`kg-agents-live-smoke.py`, 33 + 32 chat UI checks). Contract: `openapi.json` valid (57 actions, the generator reproduces it unchanged), `test_flow_contract.py` 12, `test_mcp_adapter.py` 9, `check-locale-identifiers.py` without collisions.
- **Image:** an image built like `docker/Dockerfile.scoutro` (Temurin 24.0.2) passes `kg-image-smoke.py`, 21 checks: disabled without directory or thread; enabled, with a page pushed through YaCy's parser captured with its JSON-LD; clean stop and unclean start; a backup in the `DATA` volume kept across a restart, restored with a safety copy; a rebuild that leaves no shadow.
- **Diff checks** over the package's diff: `git diff --check` clean; no `System.out`, `printStackTrace` or `console.log` outside the measurement and test harnesses' report lines; no tokens, keys, passwords or private paths.

### 22.12 Release acceptance

The criteria of [12](#12-release-acceptance) and their evidence:

| # | Check | Evidence |
|---|---|---|
| 1 | Sourced objects; no duplicates on reprocessing | `PublisherTest`; end to end steps 1–5 and 9–10 (recrawl keeps the IDs) |
| 2 | Identical content shares work without mixing identity | 2b `LlmServiceTest` (cache key with the domain) |
| 3 | Same-name organisations stay separate; several facilities per domain | `PublisherTest`; end to end: two organisations named alike in two collections, two facilities of one operator |
| 4 | Two sources, one removed: the statement stays; the last removed: not current | end to end step 11 (the VAT ID loses one of three sources and stays supported; the facility without a source is gone) |
| 5 | No stale publish during a recrawl, no ghost after a delete | 2a `SyncServiceTest` |
| 6 | Deletions within the lag | 2a `SyncServiceTest`; end to end steps 11–12 |
| 7 | Crash repaired, backfill resumable | `KgSyncRuntimeTest`; end to end: hard kill in the middle of processing and of a reconcile, integrity check, every page in the graph afterwards |
| 8 | A hanging or faulty LLM does not block the crawler | 2b `LlmServiceTest`; end to end: the model unreachable at first, tiers 1 and 2 published, the breaker opened, `llm_retry` |
| 9 | Budget, reserve and queue limits hold under load; resume works | `StorageGuardTest`; measurements ([22.4](#224-measurements-and-budget-evaluation)); end to end: the disk reserve and the 1 MiB JSON-LD budget while the crawl goes on, the queue overflow |
| 10 | API, export and chat respect auth, visibility, limits | packages 3 and 4; end to end steps 6 and 8 (agent of one collection, chat in one collection, anonymous 401) |
| 11 | A graph outage causes no error in search; UI and translations | packages 3 and 4; `knowledge-ui-test.mjs`, `kg-e2e-ui.mjs` (English and German) |
| 12 | Upgrade keeps the index; rollback, restore | `kg-rollback-live.py` ([13](#13-migration-backup-rollback), [22.10](#2210-rollback-the-fields-index-options)); end to end steps 15–16 |

### 22.13 Before merge, release and rollout

**Before merging:**
1. Merge the stack in order: #14 (2a) → #15 (2b) → #16 (3) → #17 (4) → package 5. After each merge, retarget the next PR to `main`. Package 5 corrects the field type of 2a ([22.10](#2210-rollback-the-fields-index-options)); do not release a state between them.
2. Run `ant scoutro-agents-test` on `main` after the last merge.

**Before a release** (not done here, as instructed):
1. Raise `scoutro.release` and choose the image alias ([14](#14-version-recommendation): `1.942-scoutro.13`, alias `0.7.0`).
2. Run the publish workflow; published tags are protected.
3. Build and smoke the image: `test/scoutro-api/kg-image-smoke.py`.

**Before the Olares rollout** (a separate task):
1. Find out the free space on the app's `DATA` volume, the current index size, and whether `limitedDisk: 20Gi` is enforced (O1). Choose the budgets with [22.4](#224-measurements-and-budget-evaluation).
2. Set `javastart_Xmx` to at least 2 GiB for large crawls.
3. Switch on: `scoutro.kg.enabled`, `scoutro.kg.collections`, `scoutro.kg.jsonld.enabled`. Optionally set `scoutro.kg.llm.collections` with a model for the usage knowledge. Pages crawled before the switch get JSON-LD only when recrawled.
4. Watch the first backfill, the storage and JSON-LD levels, and the first scheduled backup. Download a backup to keep a copy outside Olares.
5. Make a backup before every later upgrade.

**Not part of this work** (open YaCy topics):
- YaCy's 30-second shutdown.
- `push_p` answers 500 instead of a refusal when YaCy does not take a document: a `ClassCastException` in `Switchboard.parseDocument` when the crawl stacker rejects it, and a `NullPointerException` (`in.queueEntry`) when the parser returns nothing, for example for a host outside the network's domain (seen in the rollback test).
- A crawl start on a page that answers 404 removes the page from the index before it is refused (seen end to end; the graph follows correctly).
- The Olares upgrade, the rollout and a release tag.

## 23. Package 6 implementation

Package 6 makes the graph a business graph ("Business Knowledge Graph"): what a firm offers and what it costs, how firms relate, their industry, contacts, job postings and audiences, with a network view and a measured answer to whether graph facts make the chat better. It starts from `main` after the 0.7.0 release (`57d02c1`) and leaves that release's installation on Olares untouched.

**Shipped in this PR:**

| Area | Files |
|---|---|
| P0: the manual pause | `sync/SyncService`, `sync/LlmService`, `KgRebuild`, `KgRuntime` (pending work by type), page and banner |
| Vocabulary 2, schema 4 (E) | `extract/Vocabulary` (version 2), `store/KgSchema` (schema 4: `kg_doc.content_hash`, `kg_doc_link`, `kg_derived`, change kind 3), `store/KgStore`, `store/KgChangeLog`, `publish/Publisher`, `resolve/IdentityResolver` (schemes `domain_operator`, `job_posting`, `place_name`, `service_name`), `vocab/KgVocabularies`, `vocab/Categories`, `vocab/Nace`, `vocab/TermMatcher`, `defaults/scoutro/knowledge/` (`categories.json`, `nace-2.1.csv`, README) |
| Extraction (C, D, F, G, H, I) | `extract/JsonLdExtractor` (version 3), `extract/BusinessRules`, `extract/BusinessFacts`, `extract/Values`, `extract/RuleExtractor` (version 3), `extract/LlmExtractor` (tier 2 version 2), `extract/ExtractContext`, `extract/Claim`, `extract/Mention` |
| Derived layer and matches (D, I) | `derive/DerivedService`, `KgRuntime` (maintenance step, `derive` action) |
| Read side (B, C, D, F–I) | `read/BusinessView` (object view), `read/BusinessGraph` (neighbourhood, comparison, derived rows, facets), `read/KgReader` (entity filters), `read/KgExport` (derived rows), `read/ChatFacts` (facts that follow the question), `net/yacy/ai/rag/GraphFacts` |
| Upgrade | `store/KgStore` (integrity check and verified copy before the migration, hold), `store/KgBackup` (`before-upgrade`), `KgBackups`, `sync/SyncService` and `sync/LlmService` (the hold) |
| API, agents, tools | `api/KnowledgeApi`, `api/KnowledgeRead`, `agents/AgentActionRegistry`, generator, `openapi.json`, `actions.json` (62 actions), `scoutroctl kg business|neighborhood|compare|derived|facets|control derive` |
| UI | `ScoutroKnowledge_p.html`, `knowledge.js`, `knowledge.css` (object view in sections, network view, comparison, filters), `de.lng`, `master.lng.xlf`, `help/ScoutroKnowledge_p.md` |
| Benchmark (A) | `test/scoutro-kg-benchmark/` (corpus, catalog, harness, answers, scoring, review, results) |
| Tests and measurement | four new test classes and thirteen extended ones ([23.14](#2314-tests-and-checks)); `KgLoadMeasurement` with vocabulary 2 content; `test/scoutro-api/kg-upgrade-live.py` (new); `test/scoutro-ui/knowledge-live-smoke.py`, `knowledge-ui-test.mjs` |
| Documentation | this section, `docs/SCOUTRO_KNOWLEDGE.md`, `docs/API.md`, `docs/ACTIONS.md`, `docs/SCOUTRO.md`, help page |

### 23.1 P0: the manual pause stops all enrichment

Production showed growth and activity while the graph was paused. The guard refused growth writes, but three paths still worked or grew during the pause:

1. the LLM tier claimed queue items and called the model, then had its writes refused, and called again;
2. the sync extracted the first item of every batch, and a page that came back made its facts current again;
3. a rebuild's shadow kept enriching and could swap in.

Now:
- the LLM tier and the sync ask the guard before their work and stay idle with the refusal as reason;
- a returning page waits during the manual pause;
- the shadow follows the pause, and the swap waits for the resume.

Deletions, reconcile, retention, integrity and state checks continue. The status counts pending work by type (new pages, updates, deletions, reconcile checks, captured changes).

### 23.2 Vocabulary 2 and schema 4 (E)

- **The vocabulary.** The job type; predicates for service categories, descriptions and prices; fifteen directed relations between organisations (`parent_of`, `subsidiary_of`, `carrier_of`, `member_of`, `association_member`, `partner_of`, `cooperation_with`, `customer_of`, `reference_for`, `supplier_of`, `service_provider_for`, `brand_of`, `certified_by`, `funded_by`, `sponsored_by`); the industry (NACE Rev. 2.1 / WZ 2025 code at the safe level, and the Scoutro industry group); organisation contacts; job fields; the declared audience. Claims carry a confidence; evidence stores it.
- **Business vocabularies.** `categories.json` holds the seed vocabularies of the four Scoutro collections:

  | Vocabulary | Collection | Categories | Groups |
  |---|---|---|---|
  | care | `edelsenior-web` | 22 | 6 |
  | coaching | `checkthecoach-web` | 34 | 7 |
  | software | `stackfinder-web` | 35 | 8 |
  | construction | `bauteamcheck-web` | 110 | 14 |

  It also holds segments, customer types, company sizes and employment types. Files in `DATA/SCOUTRO/knowledge/vocabulary/*.json` extend it; NACE Rev. 2.1 comes from `nace-2.1.csv`. The vocabulary and the per-collection settings are part of the extractor identity: a change re-extracts at low priority.
- **Schema 4** only adds:
  - `kg_doc.content_hash`;
  - `kg_doc_link` (outbound registrable domains);
  - `kg_derived` (derived rows, visible only with both collections);
  - change kind 3.

  The change feed keeps its sequence. The LLM mark is the content hash, so a new vocabulary keeps the LLM evidence; a new prompt re-examines a page and keeps the old evidence until it is replaced.
- **New identity schemes:**
  - `domain_operator`: the unnamed operator of a services, prices or careers page. It is the declared operator if the domain has exactly one; otherwise a placeholder the operator takes in later.
  - `job_posting`, `service_name`, `place_name`.

### 23.3 Services and prices (C)

- **Sources.** JSON-LD: offers, `priceRange`, `makesOffer`, offer catalogs, categories. Rules: service lists and price lines (unit, from/up to/range, conditions, care level, own share, VAT note, the page's date). The LLM tier only points at a price with a verbatim quote; Scoutro reads the amount from the quote.
- **Exactly as published.** A price needs an explicit currency and a named service. Nothing is estimated, converted, normalised or averaged. Two different prices for the same service, unit and conditions are a visible conflict.
- **Price tables** are read as YaCy's parser gives them to the index: the cells of all rows in one line, each price after the service of its row (tested with the parser's real output).
- **Ambiguous amounts give no price.** In that line two cells can look like one grouped number ("Pflegegrad 2 | 980 €" became 2 980 €, found in the review). A plain space groups thousands only after a prefix, a currency or punctuation; after a word or a number the line gives no price. In JSON-LD the point is the decimal point (schema.org), and a string such as `"12.500"` (12.5 by the rule, 12 500 by German habit) gives none (`85b79f1`).
- **Dates.**
  - A price past its validity is expired and hidden.
  - One older than `prices.staleDays[.<collection>]` (180) without a validity date is shown as possibly outdated, and in the chat after the current ones with a note.
  - The page's date keeps its precision ("Stand 09/2026" stays `2026-09`; [23.12](#2312-benchmark-a)).
- **Comparison.** `GET /kg/compare?category` lists one service category across providers: every price with conditions, date and sources, never averaged.

### 23.4 Relations (D)

- **Only explicit statements.** JSON-LD `memberOf`, `parentOrganization`, `subOrganization`, `brand`, `sponsor`, `funder`, credentials. Rules after explicit markers ("Mitglied der …", "Partner der …", "Kunden: …"): a qualified organisation name, never a person ("Partner der Region" names nobody). "Zertifiziert nach …" gives a certification.
- **Directed.** A relation is stored once, from the page that states it. The object view and the network show it on both entities: outgoing on the one, incoming on the other.
- **Weak signals stay weak.** `linked_to` comes from YaCy's outbound links between two declared site operators. It is a derived row with confidence 0.2, never a business relation, and shown only on request. Its collections are the linking page's and one holding a page of the target site, so its viewer sees both ends.
- **Same operator.** Facilities or organisations of one operator or carrier are linked as `same_operator`, in groups of up to `sameOperator.maxGroup` (12).

### 23.5 Industry (F)

- **The safe level.** The industry is a NACE Rev. 2.1 / WZ 2025 code at the level the evidence makes safe:
  - a service whose category has no reliable class gives only the section ("Sanierung" → F);
  - a trade named in the imprint gives its class ("Gewerk: Dachdecker" → 43.41);
  - the JSON-LD type of a nursing home gives 87.10.
- **Main and secondary.** The best-supported code is the main industry; two equally supported ones give their common level ("43.21" and "43.34" → "43"); the others are secondary.

### 23.6 Contacts (G)

- **What is kept.** The organisation's phone, fax, role mailboxes, contact form, contact points by function (sales, press), office hours, company profiles and directions.
- **No persons.** No personal e-mail address and no person names: the data minimality of package 5 ([22.2](#222-data-minimality-o7)) applies to every new field.

### 23.7 Jobs (H)

- **Off by default.** `jobs.collections` lists the collections with job postings.
- **What a posting holds.** JSON-LD `JobPosting` and careers pages give the employer (never a recruiter), title, employment type, place, deadline, published salary with its unit and gross/net, and the application route.
- **Lifecycle.**
  - A posting is open until its deadline.
  - It is ended when the deadline passes or its page disappears, then counted from the last time the page was seen.
  - It stays visible for `jobs.endedVisibleDays` (90), then is hidden.

  A test for the disappearing page found that such a posting ended "now" on every view and was never hidden: the reader now keeps the last load of a gone page (`5b1c0d8`).

### 23.8 Audiences and suggested matches (I)

- **Three layers, never mixed:**
  - *declared*: what the firm says (customer type, segment, target industry and category, company size, service area, places served);
  - *observed*: customers and references named on pages;
  - *suggested*: matches Scoutro computed.
- **Suggested matches are never facts.**
  - `suggested_customer`: an organisation's declared target meets another's industry or services in its declared area.
  - `suggested_partner`: the same audience with complementary services in the same place.
  - Each row names the statements of both sides and both collections, has a confidence, and a viewer sees it only with both collections.
  - **A row of collections (ca, cb) uses A's facts in ca and B's facts in cb only.** The review found that places, service categories, customer types and existing relations were read from every collection: a place known only from a third collection could confirm the area, and its key and statement ID went into the row's reason and basis. They are now read per collection, and the caps apply per pair of collections, so each collection's viewer gets the same suggestions (`d8f44c9`).
  - A suggestion never repeats a relation its viewer sees and is capped per entity and collection (`matches.maxPerEntity`, 20) and in total (`matches.max`, 50 000).
  - Full passes every `derived.intervalMinutes` (60) or on `derive` recompute it; stale rows are removed with a feed notice. A deleted entity records the deletion of its derived rows in the feed before the cascade removes them; with `derived.enabled=false` one pass removes all rows, with their notices.
- **Places by name.** A place is keyed by country, level and name, so that a service area in the text meets an address. Two towns of one name in one country are one place, and a suggestion may take one for the other; it names the place, so its reader can check it. A postal code in the key would part every text area from the addresses, so this stays a documented limit.
- **Pause and rebuild.** The derived layer is enrichment: the manual pause and every growth refusal stop it. It runs again after a rebuild (`2d06013`): the measurement found that a finished rebuild had kept it waiting until a restart.

### 23.9 Read side, API, agents, export and chat

- **Routes** for administrators and `kg.read` agents ([API.md](API.md#knowledge-graph)):
  - `GET kg/entities/{id}/business`: the object view in sections;
  - `…/neighborhood`: nodes and typed, directed edges with status, confidence and evidence count, depth 1 or 2, paged; there is no route for the whole graph;
  - `GET kg/compare`, `kg/derived`, `kg/facets`;
  - the entity filters `industry`, `category`, `audience`.

  Every route is computed per collection; a `kg.export` token reaches none of them. Facets and the comparison count a fact only with a current page the viewer sees, as the object view does (`354236b`).
- **Export and feed.** Derived rows are exported (phase `d`) and in the expanded change feed (kind 3), each only with both of its collections.
- **Chat facts follow the question** (found by the benchmark's dry run, `10e7c4c`):
  - recognised intents (phone, address, identifiers, hours, contacts, prices, jobs, industry, services, operator, relations, certification, audience) come first, over every candidate entity, then the rest;
  - prices only with their date, outdated ones marked, expired ones out;
  - jobs, incoming relations, and suggestions marked as such;
  - a service is named with its provider.
- **MCP and `scoutroctl`** use the same routes and grants.

### 23.10 Network view and object view (B)

- **The object view** shows the sections that have content: overview, industry, services, prices, contacts, relations, jobs, audiences, suggested matches, evidence and sources, all facts. It has a table of contents, and status, confidence and evidence per value.
- **The network** is an SVG drawn by the page, without an external library.
  - Depth 1 or 2.
  - Typed, directed lines whose style shows confirmed, uncertain, derived, weak and suggested.
  - Filters, "show more", the keyboard (Tab, Enter, Escape), hover and focus showing lines and evidence.
  - The same data as a list, and "list only".
  - GraphML (Gephi, Cytoscape) and JSON downloads.
  - Usable at 360 px.
- **Tests.** Playwright checks the views in English and German at five widths.

### 23.11 Upgrade from 0.7 and the way back

- **Before the migration** (schema 3 → 4):
  - `quick_check` on the unchanged file, bounded by `integrity.maxMillis`. A failed or unfinished check leaves the file untouched and the graph off (`upgrade_blocked`).
  - A verified copy `backup/graph-<UTC>-before-upgrade.db` with metadata. The newest one survives the retention as the way back to 0.7.
- **Without room for the copy,** the additive migration runs, but the re-extraction with the new vocabulary and the LLM re-examination wait (`upgrade_hold`) until a verified backup exists; the backup releases them.
- **Any size.** The 50 000-page measurement found an old defect: a backup was admitted with the graph's size as its estimate, and the guard checked it against `wal.maxBytes` (64 MiB), although the copy writes no WAL. Every graph above 64 MiB was refused with `wal_limit`: no backup, no copy before the upgrade, and a hold that no backup could release. The guard now counts only the WAL part of an estimate against the WAL limit; a copy has none (`1e7e15d`). 0.7.0 still has the defect: [operator guide 9.4](SCOUTRO_KNOWLEDGE.md#94-upgrading-from-scoutro-07-vocabulary-2) says how to back up a larger 0.7 graph.
- **The way back.** 0.7 refuses schema 4 (`schema_unsupported`) and leaves the file untouched. Restoring the copy before the upgrade brings the graph back ([operator guide 9.5](SCOUTRO_KNOWLEDGE.md#95-going-back-to-scoutro-07)).
- **Rebuild with vocabulary 2.** An identity rebuild builds the shadow with the current vocabulary and rules.
  - The rebuild test of the derived layer found an older identity defect: with two or more facility pages processed before the operator's own page, only the first parent organisation joined the declared operator, because the key table holds one entity per key. A rebuild in the same order split them again.
  - Now every such page keeps its own key, and the operator takes in all of them (`bd167f8`). A rebuild repairs graphs split this way.

### 23.12 Benchmark (A)

The method, every figure and the limits are in [test/scoutro-kg-benchmark/README.md](../test/scoutro-kg-benchmark/README.md). In short:

- **Setup.** 19 synthetic pages in four collections, 34 questions, graph facts off and on through the real chat endpoint of a disposable peer. The setting was read and restored byte for byte. One Claude agent per task answered from exactly the recorded messages, with 2 repetitions: 136 answers. The scoring is automatic, with a manual review of every flag, a 20 % sample and all re-answers.
- **Result after the fix.**
  - Fact hits 80.0 % → 100.0 % (+20 pp).
  - No false or invented values; every answerable answer correctly sourced; every unanswerable question a correct "weiß ich nicht".
  - The context grows from 196 to 534 tokens; the context build time from 132 to 142 ms (median).

  **The rule is met.**
- **Run 1 failed it on precision.** A stated month reached the model as a day ("as of 2026-09-30"), and 9 of 68 graph-on answers repeated the day. Fixed in `339452a`; the 16 affected answers were answered again.
- **Where the gain comes from.** Facts that exist only in structured data (contacts in JSON-LD) and same-name firms. Wherever the page text held the fact, the model found it without the graph.
- **Not evaluated.** The real collections on Olares were not reachable from the benchmark's environment, and `checkthecoach-web` is therefore "nicht auswertbar". The README lists what a production run needs.

### 23.13 Measurements with vocabulary 2

**Method** as in [22.4](#224-measurements-and-budget-evaluation): `KgLoadMeasurement`, the same seed and host mix, 4 CPUs, JDK 21, the graph with its own threads and the real clock. The corpus now carries vocabulary 2 content:
- services and price pages on the care and trade hosts, `makesOffer` with prices in plugin-style JSON-LD;
- careers pages (jobs switched on for two collections), memberships, partners and customers, audiences and service areas on the home pages;
- the vocabularies of the collections (`pflege` → care, `web` → construction).

New per run: the derive pass, and read latencies of the object view, the depth-2 neighbourhood and the chat facts (200 samples each). Measured on the final code (`1e7e15d`), with nothing else running.

**Corpus and storage** (after the indexing):

| Documents | Entities | Statements | Evidence | Graph (logical) | per document | JSON-LD (raw) | per document | Solr segments |
|---|---|---|---|---|---|---|---|---|
| 10 000 | 2 172 | 13 570 | 37 182 | 16.1 MiB | 1 692 B | 15.3 MiB | 1 604 B | 25.9 MiB |
| 50 000 | 10 823 | 66 788 | 182 993 | 79.4 MiB | 1 665 B | 80.5 MiB | 1 689 B | 131.1 MiB |

**Business content** (after the indexing; derived rows after the first pass):

| Documents | Entities / page | Statements / page | Relations between firms | Services (offers) | Prices | Industries | Jobs | same_operator | suggested_customer | suggested_partner |
|---|---|---|---|---|---|---|---|---|---|---|
| 10 000 | 0.22 | 1.36 | 140 (0.014 / page) | 1 231 (0.12) | 1 372 (0.14) | 625 | 195 (0.020) | 232 | 0 | 170 |
| 50 000 | 0.22 | 1.34 | 769 (0.015) | 6 159 (0.12) | 6 697 (0.13) | 3 075 | 1 010 (0.020) | 955 | 107 | 415 |

**Times:**

| Documents | Heap | Graph after the index (docs/s) | LLM tier (calls) | Derive pass | Reconcile | Recrawl 10 % | Delete 5 % | Backup | Start reconcile | Rebuild | after the swap |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 10 000 | 600 MiB | 88 s (113/s) | 179 s (1 946) | 1.4 s | 1.2 s | 11.1 s | 2.5 s | 0.2 s, 14.2 MiB | 0.8 s | 90 s | 32 s |
| 50 000 | 3 GiB | 482 s (103/s) | 867 s (8 853) | 1.4 s | 3.6 s | 65.3 s | 12.5 s | 0.6 s, 69.6 MiB | 3.7 s | 502 s | 190 s |

**Memory, bounds and reads:**

| Documents | Heap peak | Heap after GC | RSS peak | Work queue peak | WAL peak | Shadow peak | Object view p50 / p95 | Neighbourhood depth 2 | Chat facts |
|---|---|---|---|---|---|---|---|---|---|
| 10 000 | 182 MiB | 30 MiB | 583 MiB | 9 647 | 4.7 MiB | 18.8 MiB | 3.7 / 8.2 ms | 2.4 / 9.9 ms | 8.6 / 17.6 ms |
| 50 000 | 272 MiB | 31 MiB | 746 MiB | 48 473 | 5.4 MiB | 72.3 MiB | 4.3 / 9.9 ms | 2.7 / 13.3 ms | 16.6 / 42.4 ms |

**Findings:**

- **Storage grows linearly, at 1.8 × vocabulary 1.** About 1.7 KB per page (vocabulary 1: 0.93–0.95 KB), 1.34 statements per page (0.51) and 0.22 entities per page (0.08): services, prices, places, industries and jobs are new entities and statements.
  - A backup is 0.88 × the logical size, the shadow of a rebuild 0.95–1.2 ×.
  - At the end of the 50 000 run the directory held 231 MiB: the database, a regular backup and the graph kept from before the rebuild (2.9 × the logical size).
- **The backup works at any size now.** In the first 50 000-page run it was skipped with `wal_limit` (the graph had grown past 64 MiB): the defect of [23.11](#2311-upgrade-from-07-and-the-way-back), fixed in `1e7e15d`; the control run above made it in 0.6 s.
- **Throughput.** The graph follows at 100–115 pages per second, 30–48 % below vocabulary 1 (155–197): the business rules match the vocabularies on every page and write 2.6 × as many statements. The first pass over one million pages takes about 2.5–3 h (vocabulary 1: about 1.5 h); the rebuild takes 1.6–1.8 × as long as before.
- **The derive pass** takes 1.4 s at both sizes (computing 0.14 / 0.6 s); its heap peak is 95 MiB at 50 000 pages. It reads at most 200 000 rows per kind, so its memory stays bounded.
- **Rebuild counts.** The verification before the swap counts 1 862 / 9 222 entities against 2 061 / 10 216 in the current graph. The shadow has tiers 1 and 2 only; the LLM tier comes back from the copied cache after the swap, and the graph then holds about as many entities as before (10 274 at 50 000). A diagnostic run at 2 000 pages compared both graphs: the same organisations, facilities, jobs and places.
- **Reads.** The object view answers in 4 ms (p95 10 ms), the depth-2 neighbourhood in 3 ms (p95 13 ms), the chat facts in 17 ms (p95 42 ms) at 50 000 pages: small next to the search and the model (benchmark: context build 142 ms median).
- **Heap and container.** The heap peak is 272 MiB of 3 GiB (9 %); the graph holds about 31 MiB after GC. The RSS peak is 746 MiB of the 16 GiB container (5 %).
- **LLM tier.** 8 853 calls for 50 000 pages: the per-host cap holds, and vocabulary 2 does not raise the number of calls.

**Budget evaluation** against the production values (graph 10 GiB, JSON-LD 2 GiB, `-Xmx3072m`, container 16 GiB, Olares `limitedDisk` 50 GiB); reserve factor 1.5 on the per-page costs, nothing changed:

- **Graph, 10 GiB.** The brake is at 9 GiB, less 128 MiB for WAL and temp files. A database and one regular backup take 1.88 × the logical size, so the database may reach about 4.7 GiB: about **2.0 million pages** of this mix (vocabulary 1: 3.5 million).
  - If a rebuild must stay possible at any time (shadow and the kept previous graph): about 2.3 GiB, about **1.0 million pages** (1.7 million).
- **JSON-LD, 2 GiB raw.** Unchanged by vocabulary 2: 1.7 KB per page × 1.5 gives about **0.76 million pages**. Solr stores the field compressed (factor 6.3), about 0.3 GiB on disk at the brake.
- **Heap, 3 GiB.** The peaks come from Solr's indexing with the graph working at the same time. 272 MiB at 50 000 pages leave a wide margin; the graph's gate `gate.minFreeHeapMB` pauses its work before the heap gets tight.
- **Container, 16 GiB.** 0.75 GiB RSS at 50 000 pages; no limit in sight.
- **Disk, `limitedDisk` 50 GiB.** The graph budget is 20 % of it; JSON-LD adds about 0.3 GiB on disk. The index itself was not measured here (the measurement's Solr core holds only the fields the graph reads; a real YaCy index takes 10–50 KB per page). With the graph at its budget, about 39 GiB remain for the index: roughly 0.8–4 million pages. Before the graph budget, the disk is likely the limit for a large crawl.

**Recommendation** (the values stay as they are, as instructed):
1. Keep **10 GiB** for the graph. It holds about 2 million pages of vocabulary 2 content with one backup, 1 million if a rebuild must be possible at any time. Watch the level from `notice` (70 %) on before a rebuild.
2. Keep **2 GiB** for JSON-LD; raise it to 4–6 GiB for crawls beyond about 0.5 million pages with SEO plugins, as in [22.4](#224-measurements-and-budget-evaluation).
3. Keep **`-Xmx3072m`** and the 16 GiB container.
4. **Measure the real index on Olares** before a crawl beyond about 0.5 million pages: with `limitedDisk` 50 GiB the index, not the graph, decides how large the crawl can be. The disk reserve check protects `DATA` in any case.
5. Plan the first pass after the 0.8.0 upgrade with about 100 pages per second for tiers 1 and 2, plus the LLM tier at the model's pace.

**Not measured here** (no access to the Olares instance): the real index size per page, real JSON-LD and price pages, a real local model's latency, and the free space on the production `DATA` volume.

### 23.14 Tests and checks

- **Unit and integration tests** (new or extended for package 6):

  | Test class | What it covers |
  |---|---|
  | `BusinessExtractionTest` (17, new) | JSON-LD offers, price lines and tables as the parser gives them, ambiguous amounts, relations after explicit markers, the industry at the safe level, contacts without persons, jobs only where switched on and never a person as employer, audiences |
  | `BusinessViewTest` (13, new) | The object view in sections, jobs and their lifecycle, the three audience layers (a supplier is no observed customer), the network, the comparison and facets per collection, chat facts per collection |
  | `DerivedServiceTest` (12, new) | Both sides named, visible only with both collections; the facts of the row's own two collections only; one seeker in two collections; no suggestion that repeats a relation; partners; weak links; the pause; switched off; a deleted entity |
  | `KgUpgradeTest` (5, new) | Integrity first, the verified copy, the hold without room, a damaged graph, retention of the copy, a graph larger than the WAL limit |
  | `KgRebuildTest` (+2), `PublisherTest` (+1), `KgBackupTest` (+1), `LlmExtractorTest` (+2), `ChatFactsTest`, `GraphFactsTest`, `KgSyncRuntimeTest`, `SyncServiceTest`, `LlmServiceTest`, `KgStoreTest` (+1 each) | The derived layer after a rebuild, the operator's earlier holders, a backup above the WAL limit, LLM tier 2, chat facts, the pause |
  | `AgentKnowledgeTest`, `KnowledgeApiTest`, `AgentCatalogTest` | Every new route refuses another collection (`collection_not_in_scope`) and a `kg.export` token; actions match the OpenAPI enum |
- **Mutation checks:** 28 mutations of security and integrity rules of package 6, one at a time against the tests named for them (`git checkout` after each). Among them: derived rows only with both collections and only from their own collections' facts, the caps per pair of collections, weak links, feed notices of deleted rows, a grounded quote for every LLM value and price, jobs only where switched on and never a person as employer, the integrity check and the copy before an upgrade, the upgrade hold, expired prices out of the chat, a stated date as precise as the page, ambiguous amounts, and facets and the comparison per viewer.
  - The first run killed 24. Four rules had no test that noticed their removal: the employer never a person or another thing, a suggestion never repeating a relation (the old test checked a pair that would never have been suggested), observed audiences only from customers and references, and no re-extraction scan while an upgrade waits. New tests (`54e9fb4`) kill all four: **28 of 28**.
- **Interface:** `knowledge-live-smoke.py` with `knowledge-ui-test.mjs`: 23 read API checks (collection isolation, vocabulary 2) and 469 Playwright checks in English and German at five widths (object view, network, comparison, filters, SEO tab, Index Browser, dashboard, controls, backup, restore, rebuild).
- **End to end:** `kg-e2e-live.py` on three disposable peers: the 16 steps (crawl, JSON-LD, facts, entities, evidence, API, UI, agent and chat, recrawl, update, deleted source, reconcile, restart, re-check, backup, restore in a fresh peer) and the limits (a disk reserve the graph cannot meet, a tiny JSON-LD budget, a queue overflow during a pause, a hard kill): 61 checks and 20 UI checks in English and German.
  - One run failed at step 2: the operator's phone carried only the imprint's evidence. Its timestamps show that the start page had not reached the graph yet. Step 1 had waited for as many publications as Solr held pages, a count that includes the second collection, while YaCy may still hold a page in its own index queue.
  - Six further runs passed step 2 (two of them all 16 steps), and 40 random processing orders of the site's pages at unit level never lost the JSON-LD evidence.
  - The wait now needs the start page in Solr and every page of the collection processed by the graph (`343f431`); a lost JSON-LD fact still fails the step. The final run of the script: 62 checks and 20 UI checks, passed.
- **Upgrade and the way back:** `kg-upgrade-live.py` (new): 26 checks on disposable `DATA` with a build of 0.7.0 (`57d02c1`): 0.7 builds a schema-3 graph → this version checks it, copies it and migrates it, with prices, the job, the operator of both homes, the industry and the derived layer → 0.7 refuses schema 4 and leaves the file untouched → the copy back in 0.7 → a second upgrade. `kg-rollback-live.py` (the version before the graph): 16 checks.
- **Live smokes:** `kg-live-smoke.py` 31, `kg-llm-live-smoke.py` 19, `kg-agents-live-smoke.py` 33 and 32 chat UI checks. The other Scoutro smokes pass unchanged: SEO and AI Lab (560 and 123), dashboard (237), discovery (80 and 43), LLM selection (619 and 40), locales (75), crawl report (433 and 5), citation (15), RAG chat (60).
- **Rights audit:** every route of `openapi.json` answers an anonymous caller as declared: 129 checked, 0 failures; the defaults stay 10 GiB and 2 GiB.
- **Contract:** the generator reproduces `openapi.json` and `actions.json` byte for byte (62 actions); the OpenAPI document is valid; `test_flow_contract` (12) and `test_mcp_adapter` (9) pass.
- **Suites:** `ant scoutro-agents-test` (568 tests: every Scoutro test class, the knowledge graph included, and `AdminSecurityTest`), `scoutro-rag-test` (31), `scoutro-llm-security-test` (32), `scoutro-report-test` (127) and `scoutro-dashboard-test` (17): **775 tests without a failure on JDK 21 and on JDK 24**, each built with its own JDK.
- **Diff checks:** no whitespace errors (`git diff --check`), no CRLF, no credentials, tokens or private keys, no debug output, no model identifier in the repository.

### 23.15 Before review, merge, 0.8.0 and rollout

**Review and merge:**
1. Review this PR against `main`; it is one package with logically separate commits.
2. After the merge, run `ant scoutro-agents-test` on `main`.

**Release 0.8.0** (not done here, as instructed):
1. Raise `scoutro.release` (`1.942-scoutro.14`, alias `0.8.0`: new on-disk data with an automatic, verified upgrade).
2. Run the publish workflow; build and smoke the image (`test/scoutro-api/kg-image-smoke.py`).

**Rollout on Olares** (a separate task):
1. Make and download a backup of the 0.7.0 graph. Above 64 MiB, 0.7.0 skips it with `wal_limit`: copy `graph.db` with the app stopped, or raise `wal.maxBytes` for the backup ([operator guide 9.4](SCOUTRO_KNOWLEDGE.md#94-upgrading-from-scoutro-07-vocabulary-2)).
2. Check the free space on `DATA`: the upgrade copy needs about the graph's size, otherwise the re-extraction waits for a backup ([23.11](#2311-upgrade-from-07-and-the-way-back)).
3. Choose `jobs.collections`; check the vocabularies of the collections (`scoutro.kg.vocab.<collection>`).
4. After the start, watch `status.upgrade`, the re-extraction (pending work by type) and the first derive pass.
5. Run the A/B benchmark on the real collections, with Claude as tester and judge of the instance's own model ([test/scoutro-kg-benchmark/README.md](../test/scoutro-kg-benchmark/README.md#limits)).

## 24. Package 6.1: service context, network view and new collections

Package 6.1 makes the business graph easier to read without changing its model. It starts from `main` after the 0.8.0 release (`cf4adfc`), adds no schema change and no new on-disk data, and touches no installation.

### 24.1 Services of the same name

Services stay what vocabulary 2 makes them: one entity per provider, keyed `service_name` within the provider's registrable domain ([23.3](#233-services-and-prices-c)). "SAP" of CTcon GmbH and "SAP" of another company are two entities with their own provider, prices and evidence; nothing in this package merges them, changes an ID or moves a price.

- **Context of a hit** (`read/EntityContexts`): every item of `GET entities` (and `hosts/{host}/entities`) carries `context`, computed for the whole page in a few batched queries over the viewer's evidence: `hosts` (the most-used first), `collections`, `places` (stated locality, else the places it is in), `quality`, `sources`, `last_confirmed`; for a service its `providers` (the visible subjects of a visible `offers` fact, each with hosts, collections, places and quality) and `provider_count`, for a job its employer (`hiring_organization`). A service without a visible provider has `provider_count: 0`, which the page says ("No provider assigned").
- **Groups** (`read/ServiceGroups`, `GET services`, `GET services/providers`): the visible name statements of visible services, grouped by the name in lower case without surrounding spaces, for reading only. A group counts separate services: providers, services without a provider, the viewer's collections, the providers' places, services with a current price and with a current source. `services/providers?name=` lists the services of one name, each with its own provider and price counts. Other collections never enter a group; an invisible name is `404`.
- **Comparison:** each provider now also names the hosts and collections of the viewer's pages.

### 24.2 The network around a service

`BusinessGraph.neighborhood` already read the incoming facts; 6.1 makes the service centre useful:

- every entity node carries the same context as a hit (hosts, collections, places, quality, sources, last confirmation), so the drawing can show `CTcon GmbH` / `ctcon.de` and the detail panel needs no further request;
- edges of the centre carry `direction` (`in`: `CTcon GmbH → offers → SAP` with SAP in the centre);
- with a service or job in the centre, depth 2 shows the providers' relations and structure but not their other services and jobs (those would turn a provider with fifty services into a hub; its own network shows them);
- `prices=true` adds the published prices of the services in the centre and at depth 1 as value nodes, each attached to its own service only.

### 24.3 Network view

Still an SVG drawn by `knowledge.js`, no library, no CDN.

- **Nodes** are cards by type (organisation, facility or site, service, job, place, industry or audience, price) with a second line: the domain of an organisation, "Service" for a service, the place of a facility. The centre is framed.
- **Layout:** on a narrow screen, for many neighbours and at depth 2 the drawing is *layered*: what points to the centre above it (providers, customers, members), what it points to below, depth 2 beyond its neighbour, every layer wrapped into rows that fit the width (two columns on a phone). Lines run orthogonally through the gaps between rows and the gutters between columns, so they never cross a card. A few neighbours on a wide screen are drawn *radially*. The drawing is as high as its content; the page never scrolls sideways.
- **Labels:** in the layered drawing the relation to the anchor (centre or neighbour) is the card's caption next to the line that enters it; other lines get a label where it covers neither a card nor another label, else on highlight. Arrows show the direction the source states.
- **Detail panel:** a tap, click or Enter on a node shows name, type, domain, collections, quality, sources, last confirmation and the relation to the centre, with *Open object*, *Network of this object* and *Open sources* (the object view at its evidence section). Value nodes link to the objects of that industry or audience; a price node to its service and its evidence.
- **Filters**, grouped: facts (relations between companies, structure and operators, services and providers, places, industry, audiences, jobs and employers, prices), derived (same operator, weak link signals, suggestions), status (current, outdated). The defaults follow the centre's type: around a service its providers, structure and business relations at depth 2; around a job its employer; around an organisation the defaults of package 6. Links of package 6 (`f=…,values,…`) keep working.
- The list below holds the same edges, each end with its type or domain; GraphML also carries hosts and collections. Zoom and pan were left out: the layout fits the width instead.

### 24.4 New collections

- **Following:** unchanged. `scoutro.kg.collections=*` follows a new collection at once; a fixed list only the named ones.
- **Fix:** under `*`, `scoutro.kg.vocab.<collection>` and `scoutro.kg.prices.staleDays.<collection>` were read only for the four Scoutro collections and the named ones, so an existing vocabulary could not be assigned to a new collection. The runtime now passes the set `scoutro.kg.*` keys to `KgConfig` (the rebuild the same per-collection keys); the keys found are part of the extraction identity as before, so assigning a vocabulary re-extracts that collection's pages. Such a key with an invalid value now counts like one of a named collection: it is listed under Settings and keeps the graph off. A fixed list behaves as before.
- **Vocabulary:** a collection without a mapping has none; it gets generic facts and no service categories. No vocabulary is ever guessed from a collection's name. Override files in `DATA/SCOUTRO/knowledge/vocabulary/` can add a vocabulary and a mapping without a schema change.
- **Status:** `status.collections` lists every collection the graph follows, maps or holds: `followed`, `vocabulary` (null for none), `vocabularySource` (`setting`, `vocabulary_files`, `none`), `vocabularyKnown`, `jobs`, `llm`, `documents` (counted from `kg_doc_collection` at most every 10 seconds) and `state` (`following`, `waiting`, `not_followed`, `unknown_vocabulary`); `config.jobsCollections` shows `scoutro.kg.jobs.collections`. The overview shows them as the *Collections* table with "No vocabulary assigned".
- **Jobs:** only for collections named in `scoutro.kg.jobs.collections` (or `*`); a new collection never gets them by itself.

### 24.5 Display names, never an ID

**Cause.** The read API projected `name` only from a visible `name` statement. An entity without one — the unnamed operator of a services or careers page (the `domain_operator` placeholder of `BusinessRules`) before a page declares the operator, an organisation known only from links, a service whose name another collection holds — had `name: null`, and the object list, object view, network, comparison, matches, relations, provider lists, the SEO tab and the chat fell back to the ID (`kge_…`).

**Fix** (`read/DisplayNames`). Every entity, reference, node, statement subject and object, provider, compared provider and service row now also carries `display_name` and `display_name_source`, computed for the viewer at each read:

1. `fact`: the visible stated name;
2. `legal`: a visible alias that is a legal name (`… GmbH`, `… AG`);
3. `operator`: for the unnamed operator of a site, the visible name of the *one* declared operator of the same domain (`site_operator` key in the placeholder's `domain_operator` scope); with several, none;
4. `domain`: for an organisation, a name from the host of most of the viewer's pages (`www.zimmerei-boehmer.de` → "Zimmerei Boehmer", with `display_host`; the host itself when no readable label is left: an address, punycode, digits); the page adds "derived from the domain";
5. `fallback`: `display_name: null`; the page shows "Unnamed organisation", "Unnamed service", "Unnamed facility", "Unnamed site", "Unnamed place", "Unnamed job posting".

Only `fact` is a name the sources state. The others are presentation: never stored, never an entity key, never used to merge, never given to the chat as a fact (the chat says "an unnamed organisation"). `name` stays the stated name or null. A stated name that turns up later replaces the fallback at the next read, without a migration. The ID stays the link target and appears only in the object view's *Technical ID* field.

### 24.6 Collection choice, never free text

No collection name has to be typed or known anywhere. Every visible collection filter is a `select`: the chat, the knowledge graph page (objects, object, network, services, compare, sources, settings), the SEO host analysis with its crawl status tab, the crawl report and the Index Browser. The crawl start, the agent grants and YaCy's own crawl and import forms choose the collection from the same list too, and **New collection** creates one (§24.6.1).

- **Options:** "All collections" (the empty scope) first; where a view needs exactly one collection (crawl status, crawl report, crawl start) "Choose a collection". Then the collections alphabetically regardless of case, labelled with their display name. Nothing is hard-coded: the admin pages read `GET /scoutro/api/v1/collections` (administrator only, the collection catalog; the report adds the Discovery jobs' collections), so a created collection appears at once and an indexed one as soon as the index has pages of it. While the list loads the select is disabled and `aria-busy`, and the views wait for it.
- **Link and stored values** (`?collection=`, the chat's `localStorage`) count only if the name is in the list; otherwise "All collections" applies, the admin pages say so without repeating the name, and the chat forgets a stored name that is no longer listed.
- **Chat:** `yacychat.java` renders the list with the rule of the endpoint (`ai/rag/ChatCollections`): local and administrator access every selectable collection of the catalog, an AI Shield guest only those released on the AI Shield page (`ai.shield.guest-collections`, none by default; the catalog reads the index at most every 10 s). `/v1/chat/completions` refuses any other name with `403 collection_not_allowed` for existing and unknown names alike and never echoes it; a guest's `collection:` modifier outside the list is ignored. The backend stays authoritative; the select only shows what it accepts.
- **Agents** keep their granted collections (`GET /scoutro/api/agent/v1/collections`, `403 collection_not_in_scope` elsewhere); they never use the chat endpoint.
- **Crawl start** (`ScoutroCrawls_p.html`): the collection a crawl writes to is a required `select` with **New collection** beside it; `POST /scoutro/api/v1/crawls` refuses a collection outside the catalog with `400 collection_unknown` before anything is dispatched, so a crawl never invents a collection.
- **Agent grants** (Agents & Access, agent wizard): the collections are a list of checkboxes of the catalog (plus the collections the agent already holds, marked when the catalog no longer lists them); the free-text field "Further collections" is gone and the server refuses a collection outside the list. **New collection** in the form creates one and ticks it.
- **YaCy's forms** (expert and site crawl start, RSS, WARC and ZIM import): the collection field becomes the same `select` with **New collection** (progressive enhancement: without JavaScript, or while the catalog cannot be read — not yet signed in as administrator, index unavailable — YaCy's original field stays, so the form and its login keep working; YaCy's `Crawler_p` and import servlets themselves still accept the parameter as before). The index deletion page always lists the collections of the index (it deletes what the index holds, including internal ones) instead of a text field.

#### 24.6.1 The collection catalog and New collection

- **One definition** (`scoutro/api/CollectionCatalog`): a collection is known when the index has documents of it (facet `collection_sxt`, cached 10 s), when it was created (`DATA/SCOUTRO/collections.json`, schema `scoutro.collections.v1`, reloaded when the file changes) or when a Discovery profile names it as its collection. `robot_*` is internal: listed with `internal: true`, never selectable. `selectable`, `chat`, `knowledgeGraph` and `crawlTarget` are all "not internal"; the chat narrows guests further (AI Shield), the graph follows by `scoutro.kg.collections` as before. Every list (chat, filters, crawl start, agent grants, AI Shield, YaCy's forms) takes the catalog; there are no ad-hoc filters.
- **List:** `GET /scoutro/api/v1/collections` returns `collections`, one entry each (`id`, display `name`, `description`, `documents`, `internal`, `selectable`, `chat`, `knowledgeGraph`, `crawlTarget`, `sources`, `createdAt`, and `graph`: `followed`, `vocabulary`, `vocabularySource`, `jobs`, `llm`, `state` from the graph's collection status), `allowNew: false`, `canCreate` (administrator) and `limit` (500). Agents (`collections.list`) see only their collections and `canCreate: false`.
- **Create:** **New collection** opens a dialog: display name (required, at most 80 characters), collection ID suggested from it ("Mein neues Portal" → `mein-neues-portal`; umlauts as `ae`/`oe`/`ue`/`ss`, editable) and an optional description (at most 500). `POST /scoutro/api/v1/collections` (administrator, same-origin JSON, at most 4 KiB; without `id` it is derived from the name) checks on the server: `400 collection_id_invalid` (format), `400 collection_reserved` (`robot_*`, `all`, `none`, `default`, `user`, `any`, `new`), `409 collection_exists` (any collection of the catalog, regardless of case; nothing is overwritten), `400 invalid_request` (name, description, unknown field), `503 index_unavailable` (the index cannot be read, so duplicates cannot be checked) and `503 collection_store_unavailable` (a damaged list is never overwritten). The dialog shows the message and adds nothing; after `201` the list is reloaded, the new collection selected (or ticked) and the work goes on.
- **Rights:** only the administrator sees **New collection** (`canCreate`) and only the administrator may call the route; anonymous calls are `401`, cross-origin `403`, agents have no such grant (`405` on the agent path). A created collection has no documents until a crawl writes into it; it gets no vocabulary and no jobs by itself (§24.4).

### 24.7 API, agents and tools

The collection catalog (`GET /v1/collections` with its entries, `POST /v1/collections`, `collection_unknown` of the crawl start) is in `openapi.json` and `actions.json` (65 actions) as `collections.list` and `collections.create`, with `scoutroctl collections` and `scoutroctl collections create NAME [--id ID] [--description TEXT]`.

`GET services`, `GET services/providers`, the `prices` parameter of the neighbourhood, `context` in the entity list, the display-name fields, the node and edge fields of the neighbourhood, `hosts`/`collections` of the compared providers and `status.collections` are in `openapi.json` and `actions.json`, for agents behind `kg.read` (the viewer is the agent's collections; foreign collections are `403 collection_not_in_scope`), as MCP tools `scoutro_kg_services` and `scoutro_kg_services_providers` and as `scoutroctl kg services`, `kg service-providers NAME` and `kg neighborhood --prices`.

## 25. Package 6.2: the network of a service name and collections switched on and off

Package 6.2 starts from `main` after 0.8.1 (`b180200`). It adds no schema change (the graph stays at schema 4), no new on-disk data of the graph and no release; one new setting, `scoutro.kg.collections.inactive`.

### 25.1 The network of a service name

`GET services/network?name&category&offset&limit&include=stale` (`read/ServiceGroups.network`) draws every visible provider of one name, `CTcon GmbH → offers → SAP`, reusing the group of `services/providers` (key, services, summary):

- **Centre:** a virtual node `service_group:<key>` (`type: service_group`, `virtual: true`, with the counts `services`, `providers`, `without_provider`). It is the name only: no entity, no ID, no hosts, sources or prices of its own, nothing to open or merge.
- **Lines:** every visible `offers` statement of a visible provider to a service of the group (`BusinessView.factsTo`, one batched read with the quality and evidence of the viewer) is its own edge from the provider to the centre, `direction: in`, with the status, confidence and evidence count of that statement and `service`: the provider's own service (ID, name, display name, quality, sources, hosts, collections and its own price counts). A provider with two services of the name has two edges; two providers of one service entity are two nodes. Nothing of different providers is merged, averaged or mixed.
- **Providers:** nodes as in the neighbourhood (`BusinessGraph.node`, with their context). Ordered by their strongest line (as the neighbourhood orders neighbours), then by age; paged by provider (`limit` 1–200, default 50, the page asks for 24 on a phone), `neighbours`, `truncated`, `next_offset`. Outdated offers only with `include=stale`. Services without a visible provider are counted in `group.without_provider`, not drawn.
- **Rights:** everything per viewer: the services, statements, providers, names and contexts of the requested collection or of the agent's scope only (`kg.read`, `403 collection_not_in_scope` outside it); a name no visible service has is `404`.
- **Unchanged:** the network of one object (`entities/{id}/neighborhood`), including a service centre with its own providers.
- **Page:** `?view=network&group=<name>` in `ScoutroKnowledge_p.html`, from **Network** next to a group, **Network of all providers** above its rows and the groups hint of the object search. The same SVG renderer (layered on a phone, radial for a few providers), a dashed centre card, status filter only (depth and layers do not apply), a detail panel for the centre (the group's counts, **All providers as a list**), a provider (its lines with their own services) and a line (its own service, **Open service**, **Network of this service**); the list names each line's own service; GraphML and JSON carry `virtual` and `service`.

### 25.2 Collections switched on and off

**Finding.** Until 6.2 the only way to stop following a collection was to remove it from `scoutro.kg.collections`, and the next reconcile then treated its pages as out of scope: real-time get confirmed them, and below the mass-deletion brake they were deleted from the graph (`SyncServiceTest.allowlistChangeTriggersAReconcileThatRemovesUnfollowedDocuments`). So "off" could not keep the data.

**Setting.** `scoutro.kg.collections.inactive` (`KgConfig.INACTIVE_COLLECTIONS`, names only, `*` is invalid) holds the collections switched off. `follows(c)` is false for them, also under `*`; `holds(c)` is true. Their per-collection keys stay read (`scoutro.kg.vocab.<c>`), so switching off or on changes no extraction identity and re-extracts nothing. `collectionsKey()` appends `-<inactive>` only when the list is non-empty, so a running graph without inactive collections sees no change.

**Sync** (`SolrDoc.kept`, `SolrDoc.heldOnly`):

- the reconcile scans the followed collections and the inactive ones (`scanCollections()`; `*` scans everything as before); a page of inactive collections only is seen (so it is no deletion candidate) but not enqueued;
- the verification and the delete step treat such a page as present: no verdict "absent", no deletion;
- an event of such a page (a recrawl) completes without extraction (`counters.held`); its graph data stay as they were;
- a page also in a followed collection is extracted through that one and keeps its membership of the inactive one (`doc.collections = kept`); the extraction context still reads the followed collections only;
- the JSON-LD capture skips inactive collections, also under `*`;
- a page deleted from the index still leaves the graph, as for every collection; retention applies as for any page.

**Switching on** adds the name to the list (unless `*`) and removes it from `inactive`; the collections key changes and the reconcile of the next start (`REASON_COLLECTIONS`) enqueues the pages missing in the graph: the existing backfill.

**Settings route** (`api/KgCollectionSettings`, administrator only, never an agent route): `GET /v1/kg/collections` lists every collection of the catalog (`CollectionCatalog.entries(false)`, never `robot_*`) and every collection the settings or the graph's data name, with `active`, `inactive`, `followedBy`, the vocabulary (`vocabularySetting` read from the key itself: unset, a name, empty), `defaultVocabulary`, `indexDocuments`, `graphDocuments` and `state` (`inactive` added). `PATCH /v1/kg/collections/{collection}` (`active`, `vocabulary`: a name, `""` none, `null` default) writes only that collection's keys, keeping the other names of the lists in their order (`*` stays `*`), under one lock, after checking that no backup, restore or rebuild runs (`409 operation_running`, nothing written). It then applies the settings at once with `KgRuntime.reopen()`: a clean close and a new open in the same environment, the path every start takes (config read, store opened, the start reconcile; a manual pause is stored and stays). While the graph is off the settings only count at its next start (`applied: false`). The answer says `backfill`, `kept`, `reextract` (the extraction identity changed: a followed collection's vocabulary) and the keys written.

**Page:** *Settings* has the table *Collections in the knowledge graph* (On / Off, vocabulary: default, a vocabulary, no vocabulary; documents; state; **Save** per row, with a confirmation for switching off and for a new vocabulary of a followed collection); the overview's collections table shows the state *switched off, data kept*.

**Limits:** the identity rebuild builds the graph from the followed collections, so data of inactive collections do not survive a rebuild; a reopen interrupts a running reconcile, which starts anew; a manual removal from the list without `inactive` still deletes as before.

### 25.3 API, agents and tools

`kg.services.network` (agent: `kg.read`, MCP `scoutro_kg_services_network`, `scoutroctl kg service-network NAME`), `kg.collections` and `kg.collection.update` (administrator only, never grantable; MCP `scoutro_kg_collections`, `scoutro_kg_collection_update`; `scoutroctl kg collections`, `kg collection NAME --on|--off [--vocabulary V|--no-vocabulary|--default-vocabulary]`) are in `openapi.json` and `actions.json` (68 actions).

### 25.4 Tests

- `ServiceContextTest`: every provider around the virtual centre, each line naming its own service with its own prices, sources, hosts and collections; the single-object network unchanged; collection filter (two viewers, an empty one, a category); paging by provider; a service without a provider counted, not drawn.
- `KnowledgeApiTest`, `AgentKnowledgeTest`, `AgentCatalogTest`: route validation, the agent's own providers only, `collection_not_in_scope`, `kg.read` required, the settings routes absent on the agent path.
- `SyncServiceTest`: a collection switched off keeps its graph data through the start reconcile, a recrawl, a new page and the daily reconcile (no candidates), a page deleted from the index leaves; switching on again backfills; under `*` only the inactive one is skipped; a page of a followed and an inactive collection keeps both memberships.
- `KgConfigTest`: semantics of the new key, the scan set, the collections key, the unchanged extraction identity, invalid values.
- `KgCollectionSettingsTest`: every catalog collection listed and `robot_*` never, a new collection appears by itself, on/off changes only that collection's keys and the productive settings stay, `*` stays `*`, vocabulary set/none/default, invalid requests change nothing, a collection only the settings name, the running graph reopened with its data and the new settings, routes and methods.

## 26. Package 6.3: the LLM enrichment per collection, deleting backups and the crawl start result

Package 6.3 continues 6.2 on the same branch. No schema change (schema 4), no new on-disk data, no release, no new setting key.

### 26.1 Why the status said `LLM: off · no_llm_collections`

**Finding: configuration, not a bug.** The model chosen for the usage *knowledge* in the LLM selection was read correctly (`YacyLlmClient` → `LLM.llmFromUsageQuiet(knowledge)`, live from `ai.production_models`). But the tier is a second opt-in: `KgConfig.llmEnabled()` is `followsAny() && (llmAllCollections || !llmCollections.isEmpty())`, and `scoutro.kg.llm.collections` is empty by default. With the key empty `KgRuntime.startSync()` creates no `LlmService`, and the status answered `off · no_llm_collections`. Three things made it look like a fault: the status did not name the selected model while the tier was off, the key could be set only in the Advanced Properties, and it took effect only after a restart.

**Change.**

- `status().llm` names `model` (the model of the LLM selection, or null) also while the tier is off (`KgRuntime.llmModel()`). The reason `no_llm_collections` now means only "no collection switched on"; when collections are switched on but all off for the graph it is `llm_collections_not_followed`.
- The collection settings (`KgCollectionSettings`, `GET/PATCH /v1/kg/collections`) switch the tier per collection: `llm: true|false` adds or removes the one name in `scoutro.kg.llm.collections` (the other names in their order; an empty list removes the key) and applies at once with `KgRuntime.reopen()`, as the other settings do. No collection gets it by itself: neither a new collection nor `active: true`. `*` in the key is shown (`llmBy: all`) and never rewritten into a list (409 `llm_all_collections`). The rows carry `llm`, `llmBy` and `llmActive`; the list carries `llm.model`, `allCollections`, `collections`, `enabled`, `active`; the answer of a change `llmModel` and `llmWarning` (`no_model`, `collection_not_active`): the setting is stored and waits.
- There is no second model setting: the model, endpoint, key and `max_tokens` stay those of the LLM selection row with the usage *knowledge*.
- The page shows three things apart: *Knowledge graph (rules and structured data)* active/inactive, *LLM enrichment* active/inactive with the reason, and *Model (usage knowledge)*. Settings has the column *LLM enrichment* (On/Off, with a question naming the model; *On (all collections, \*)* read-only) and above the table the state and the model with a link to the LLM selection.
- The graph works without the tier as before; existing collections stay off for it.

### 26.2 Deleting a backup

`POST /v1/kg/control {"action":"delete_backup","backup":"<file>"}` (administrator, same origin, never an agent route; `scoutroctl kg backup-delete FILE`).

- **Name only.** `KnowledgeApi` accepts `backup` only for `restore` and `delete_backup` and only matching `KgBackup.NAME` (`graph-<UTC>[-before-restore|-before-rebuild|-before-upgrade].db`): a path, `graph.db`, `-wal`/`-shm` and any other value are 400 before anything is looked up.
- **The file.** `KgRuntime.safeBackup` resolves the name with `KgBackup.find` and accepts it only if its real path lies directly in the real backup folder, it is a regular file and no link (`NOFOLLOW_LINKS`), and it is none of the real paths of the live `graph.db`, `-wal` or `-shm`; otherwise 404 `backup_not_found`.
- **The slot.** It takes the backup slot (`KgBackups.claim()`), so nothing is deleted while a backup, restore or rebuild runs (409 `operation_running`), checks the file again under the slot and deletes it with its metadata file and any `-wal`/`-shm` of that copy (`KgBackup.remove`); 503 `backup_delete_failed` if it stays. The event `backup_deleted` records it, the storage guard re-measures, the answer is the status with `deleted`.
- **Page.** *Delete* next to *Download* and *Restore* in the backup list; the question names file, type, size and date (and for a copy before an upgrade that it is the way back); afterwards the list and *Backup files* (count and space) are reloaded; busy, gone and failed are said in words.

### 26.3 The result of a crawl start

The native crawl page (`ScoutroCrawls_p.html`, `crawls.js`) shows the answer of `POST /v1/crawls` next to the form instead of replacing the crawl list with it. Crawl logic, queues and the API are unchanged.

- **Result of the attempt:** 201 started, 200 `idempotentReplay` already started (no second start), 4xx rejected, `crawl_start_unconfirmed` unconfirmed, 5xx or no answer failed; with an error text per code (`collection_unknown`, `host_busy`, `crawl_rejected` with YaCy's comment, `invalid_request`, `idempotency_conflict`, `upstream_error`, 401/403, 503, no answer) and the backend's status, code and message.
- **Status:** the profile's state as the backend reports it: `running`, `paused` (the only waiting state: YaCy's local crawler is paused), `terminated`, `removed`; *Not started* for a refusal, *Unknown* otherwise. YaCy creates the profile at once and reports no queue position, so the page never says "queued".
- **Facts:** URL, collection, the crawl/profile ID, the time of the attempt and the start time recorded by the backend. The panel follows the card's state with every refresh, and `GET /v1/crawls/{id}` once the profile has left the list.
- **Findable:** *View crawl status* focuses the crawl's card (marked *This start*); after a start the address carries `?crawl=<id>`, so a reload reads it again with `GET /v1/crawls/{id}` (404: said so and forgotten).
- **Double starts:** disabled while a start is on its way, and after a start or a replay until a field changes. An open outcome keeps the `Idempotency-Key` (a retry is replayed, never started twice); a definite refusal gets a new key (`host_busy` still guards the host).

### 26.4 Tests

- `KgCollectionSettingsTest`: the LLM tier per collection with the model of the LLM selection, without a model stored and said so, `*` never rewritten into a list.
- `CollectionsStatusTest`: the model is named while the tier is off; `no_llm_collections` only without an LLM collection; `llm_collections_not_followed`.
- `KgBackupTest`: a backup deleted by its name with its metadata, the list and the space follow, the other backup still restores; only real backup files of the backup folder (bad names, a link to `graph.db`, a linked folder outside, a directory named like a backup); nothing deleted while the slot is taken.
- `KnowledgeApiTest`: `delete_backup` takes a name, never a path (400), missing name, extra field, `backup` on other actions, unknown name 404, GET 405, 503 mapping.
- `knowledge-ui-test.mjs` (live): the three layers and the model at five widths in German and two in English; the LLM column; kgb switched on and off through the page (question with the model, only kgb changed); a backup deleted through the page (question with file, type, size and date; cancel keeps it; list and space follow; the live graph, download and restore of the others unchanged; only names; anonymous 401).
- `crawl-flow-ui-test.mjs` (mocked writes): started, waiting (paused crawler), rejected (422, 409 `host_busy`, 400), backend error (502), no answer, the replayed retry with the same key, unconfirmed, finished and removed, reload with `?crawl=`, an unknown and an invalid id, a double click and a second submit (one POST), 360 px.
