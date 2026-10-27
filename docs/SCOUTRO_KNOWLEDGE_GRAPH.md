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
| 3 LLM | `text_t` (≤ 12 000 characters per reading, chunked; a long page is read on to the end of its content in passes of up to 5 chunks, [27.5](#275-long-pages-read-to-the-end-of-their-content)), title, host | Relations (`operates`, `offers`, `located_at`, `part_of`), facilities and services the earlier tiers missed | seconds to minutes |

- All tiers write the same statement model. Equal statements merge, and their evidence keeps the kind (`jsonld`, `metadata`, `rule`, `llm`).
- Persons are ignored in every tier.
- The LLM tier runs only:
  - for collections listed in `llm.collections`;
  - on candidate pages;
  - for at most `llm.maxDocsPerHost` (default 25) documents per host (finished plus queued; a changed cap or LLM collection list re-examines skipped documents), by priority (imprint and about pages, then services, locations and contact, then the home page).

  This keeps the LLM work bounded by the number of hosts, not pages.

#### The imprint's operator (rule extractor version 4)

Found on livaid.com (October 2026): the imprint of an architect named the operator without a legal form ("LIVAID") and then, as the law asks, its professional liability insurer with its own address ("Berufshaftpflichtversicherung … Markel Insurance SE, Sophienstraße 26, 80333 München"). Version 3 took the first legal form after the imprint marker as the operator, so the insurer became the declared operator of livaid.com, the insurer's address and contacts became the operator's, and the site's unnamed operator with the services of the home page (`domain_operator`) was merged into it. Version 4:

- **The page text as the rules see it.** YaCy's HTML parser (`Document.getTextString`, the source of `text_t`) ends a block (`p`, `div`, `li`, heading) with a line break and turns a `<br>` into ". ", and it marks bold text and headings in Markdown. The operator block of the real LIVAID imprint (name, tagline, managing director, address, written with `<br>`) becomes one line: `**LIVAID. **Live | Architecture | Innovation | Design. Geschäftsführerin. Tânia Ferreira. Bonnstraße 164, 50354 Hürth. Telefon: …`. Version 4 therefore reads **units**: a line, or a sentence ended by ". " (not after "Str.", "Dr.", "St." and similar), without its Markdown.
- **Sections by role, not names.** In the imprint window (2 500 characters after the marker) a section about another party opens at its marker and ends at the next label of the operator's own data (`RuleExtractor.operatorRanges`):
  - phrases that only speak of another party open it wherever they stand ("Zuständige Kammer", "Mitglied der Architektenkammer …", "Name und Sitz des Versicherers", "Geltungsraum der Versicherung");
  - headings open it only as a label with a colon or as a unit of their own ("Berufshaftpflichtversicherung", "Angaben zur Berufshaftpflichtversicherung", "Versicherer", "Aufsichtsbehörde", "Kammer", "EU-Streitschlichtung", "Verbraucherstreitbeilegung", "Webdesign", "Bildnachweis", …), so "Webdesign Beispiel GmbH", "Schlichtungsstelle Bau e.V." or "Muster Versicherung AG" as the operator, and running text such as "wir bieten Webdesign und Hosting", open nothing;
  - generic words open it only with a colon ("Design:", "Hosting:", "Umsetzung:", "Versicherung:", "Fotos:");
  - the operator's labels end such a section at the start of a unit or with a colon ("Angaben gemäß § 5", "Registergericht", "USt-IdNr.", "Geschäftsführerin", "Kontakt:", …), unless qualified as the other party's ("USt-IdNr. des Versicherers").
  An insurer, a broker, a chamber or an agency operating its own site is found like any other operator ("Markel Insurance SE" on markel.de, "Müller Versicherungsmakler GmbH", "Ingenieurkammer Service GmbH").
- **Name, register, VAT ID, IK, address, phone and e-mail** of the operator come from the operator's sections only, also after an operator with a legal form was found; the fax and the contact points of the business rules skip the other parties' sections too. A street no longer reaches back over a sentence end ("Geschäftsführerin. Tânia Ferreira. Bonnstraße 164" is the address "Bonnstraße 164", not a street holding the person's name), a VAT ID no longer runs into the next line ("DE 123 456 789\nBerufsbezeichnung" was "DE123456789B" and dropped), and a bold legal name keeps its first word ("**Muster Pflege GmbH. **" was "Pflege GmbH").
- **An operator without a legal form** is named only on clear evidence: a unit of its own among the six units above the first postal address of the operator's sections (after an optional "Betreiber:", "Anbieter:", "Herausgeber:"; a tagline, a role and a person may stand between them, as on livaid.com), confirmed by the site's registrable domain (letters and digits equal: "LIVAID" on livaid.com) or by the single structured organisation of the page (tier 1). A unit that may be a person's name (a person marker, a salutation, two or more capitalised words such as "Tânia Ferreira" or "Erika Musterfrau", also on erika-musterfrau.de, or any unit right after a role such as "Geschäftsführerin", "Inhaberin" or "Vertreten durch") is no organisation. Text that runs the name into the street without a line or sentence break, another domain, or no confirmation: nothing is named, the site's operator stays unnamed. Without a legal name such a name cannot key a declared operator (`site_operator` needs the legal form); it goes to the site's unnamed operator (`domain_operator`), so the name, the imprint's contacts and the home page's services meet on one organisation, and a declared operator of the domain found later takes it in as before.
- **What is proven and what is not.** The tests run the real layout of the LIVAID operator block as reported (08.10.2026) as plain lines and through YaCy's own HTML parser (with `<br>`, with the insurer inline, with `<div>`s); the chamber and insurer sections after it are assumed. The production page and its Solr `text_t` were not readable from the development environment; the synthetic texts are no proof for the production page, which is to be checked after a rollout.

**What the new version does to an existing graph.** `RuleExtractor.VERSION` is part of the extractor identity (`SolrDoc.extractors()`); a changed identity makes the next start re-extract every tracked page at low priority behind new pages (`Reconciler.reextractAll`, paused like every enrichment; held while an upgrade waits for its copy). The order is stored with the new identity (`kg_meta.reextract_pending`) and removed only by the completed re-extraction, so a stop before it completed repeats it at the next start (`KgSyncRuntimeTest.aReextractionOrderSurvivesARestartBeforeItCompleted`); since 0.13.1 also a page the full work queue could not take is read (section 42). Each page's tier 1 and 2 evidence (JSON-LD, metadata, rules) is replaced; a statement without evidence is deleted. The LLM tier is not asked again (its mark is the content hash): **its evidence (tier 3) stays as it is and is not corrected by the new version**; it is renewed only when the page's content or the LLM extractor's own version changes. A statement that also has LLM evidence therefore survives the re-extraction even if the rules no longer find it (for livaid.com the analysis found no LLM evidence). That corrects the facts of tiers 1 and 2, not the LLM evidence and not the identities: an earlier merge stays, so does the merged entity's ID and its `site_operator` key with the insurer's legal name (`ImprintOperatorIdentityTest.reExtractionReplacesTheEvidenceButDoesNotSplitTheEarlierMerge`). For livaid.com after re-extraction: the insurer's name and address are gone, "LIVAID" with its own contacts and the services are on the one entity, but a page of the domain that names "Markel Insurance SE" in running text would still join it, and the redirect of the merged ID stays. Only the administrator action "re-resolve identities" (`KgRebuild`, section 22) builds the graph again from Solr with the current rules and splits wrong merges (`KgRebuildTest.aRebuildSplitsAWrongMergeAndKeepsThePreviousGraph`); it needs about 1.2 × the graph's size of free space and runs as its own operation. Neither runs by itself for this change. The old LIVAID/Markel merges therefore need a separately planned rebuild after the rollout.

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
  - The counts of invalid answers are visible (see **Counters** below).
- **Concurrency:** `llm.parallel` (default 1, maximum 2).
- **Selection, request budget and long pages** (since the coverage analysis of 0.10.0, see [27](#27-llm-tier-coverage-selection-request-budget-long-pages-missing-text-and-diagnostics)): every LLM collection has its own resumable scan with an equal share of each refill and a revisit behind it; a queue item is never dropped while its page is still to do; every request is planned against the model's context window; a long page reads its own content before recurring navigation and is read to the end of its content in bounded passes; a page whose text is missing is marked as not covered; dropped items are counted precisely and sampled, bounded, for diagnosis.
- **As implemented (2b, see [19](#19-package-2b-implementation)):**
  - The model only proposes: no persons, no e-mail addresses, no phone numbers (contact data comes from tiers 1 and 2 only, O7); a known entity of tiers 1 and 2 is referred to by `k1`, `k2`, ...; facility kinds only from the collection's vocabulary (O2).
  - **Id contract** (rule 7 of the prompt and the descriptions of the schema): a known entity (`k1`, `k2`, ... in the DATA block) exists already, is never put into `entities` again and is only referred to, as `subject` or `object` of a claim or `subject` of a value; every new entity gets a new id `e1`, `e2`, ..., once per answer and never a `k` id, and claims and values refer to it by that id. The former rule ("refer to them by these ids instead of repeating them") did not say so: models re-declared known entities with their `k` id or numbered new entities on in the `k` scheme, and the validator dropped them as `entity_duplicate_id` (the same 33 times for Llama 3.1 8B and Qwen3 14B on one test set). The validator is unchanged: such an entity is still dropped; a re-declared known entity under its own `k` id loses nothing (claims on `k1` resolve to the known entity), but a new entity under a known id is lost, and claims on it would point at the known entity or reference themselves. The contract change is a new compiled-in default prompt and schema, so `PROMPT_HASH` changed, and with it the extractor identity and the cache key: no answer of the former prompt is reused. Under the knowledge prompt lifecycle (below) this reads no done document again: new and changed pages are asked with the new contract, the existing evidence stays. A graph whose active prompt is a custom version keeps it; its text should take over rule 7.
  - Statements supported only by the LLM tier are `uncertain` ([4.4](#44-quality-and-currency)); a claim the quote states as planned or possible is `hedged`, also when the model says otherwise (a word list).
  - A changed input deletes the document's tier-3 evidence in the tier-1/2 publish and asks again; tiers 1 and 2 never wait for tier 3.
  - The raw HTTP response is read up to 1 MiB; an endpoint that refuses `response_format` (HTTP 400) is asked again without it and remembered (see **Structured output** below).
  - `last_error` is not used: `kg_doc.llm_status` (`done`, `failed`, `skipped`) and `llm_reason` record the outcome per input hash; `POST /kg/control {"action":"llm_retry"}` makes failed documents due again.

**Structured output** (what the endpoint is asked for; `llm.structuredOutput` in `GET /kg/status`):

- **Two transports, never mixed.** An `OLLAMA` model is called on Ollama's native `POST <hoststub>/api/chat` (`stream: false`; `options`: `temperature` 0.1, `num_predict` = the row's max_tokens, the stop tokens; `think: false` unless the model is known not to think, see below; no `num_ctx`, so the server's own context length applies as before). Structured output goes as Ollama's `format`: the extraction schema itself, the string `"json"` for JSON mode, or nothing; the answer is `message.content`. Every other service (OPENAI, OPENROUTER, LMSTUDIO, OTHER) keeps its OpenAI-compatible `POST <hoststub>/v1/chat/completions` with exactly the former request: `response_format` `{"type":"json_schema","json_schema":{"name":"scoutro_knowledge_extraction","strict":true,"schema":SCHEMA}}` (the `name` is required by the OpenAI protocol), `{"type":"json_object"}`, or none. Why: with Ollama (0.30.0, `llama3.1:8b`) the same mini schema was enforced on `/api/chat` with `format` in 10 of 10 tries and ignored on `/v1/chat/completions` with `response_format` in 10 of 10 (no valid JSON). Only the knowledge usage takes the native path; chat, RAG and the other usages keep `/v1/chat/completions`. A failure of `/api/chat` stays a failure of that path (retry, circuit breaker): there is no switch to `/v1`.
- `scoutro.kg.llm.structuredOutput` (`auto` by default; `json_schema`, `json_object`, `none`) and, with `auto`, the model's "format" capability of the LLM selection (`ai.model_capabilities`, or the row's format flag) decide:

  | Setting / capability | Request | `mode` |
  | --- | --- | --- |
  | `auto`, capability `supported` | schema | `schema_enforced` (the provider is reported to enforce it) |
  | `auto`, capability `unknown` (never probed, or a value of the former mood probe) | schema, as before this negotiation | `schema_unverified` |
  | `auto`, capability `ignored` (accepted but not followed in the probe) | schema | `schema_unverified` |
  | `auto`, capability `unsupported` | none | `validator_only` (prompt and validator only) |
  | `json_schema` | schema | `schema_enforced` if the capability is supported, else `schema_unverified` |
  | `json_object` | JSON mode | `json_mode` (valid JSON, no schema) |
  | `none` | none | `validator_only` |
  | any, after the endpoint answered HTTP 400 to a format | none | `fallback_after_rejection` |

- An HTTP 400 to a request with a format is asked once more without it, on the same path (`/api/chat` for Ollama); if that answer comes, the endpoint and model are remembered (until the restart) and every later request goes without a format. The rejection is counted (`rejections`, `lastRejection` `{code: "http_400", at}`) and shown; a retry that fails too ends the call as a transport failure, nothing loops.
- The "format" capability is a technical structured-output test of the LLM selection page (help/LLMSelection_p.md, `htroot/env/scoutro/format-probe.js`; version 2 on `/v1/chat/completions`, version 4 natively on `/api/chat` with `format` for an `OLLAMA` model, the path the extraction uses, with `think: false` unless the model is known not to think; a version 2 or 3 value of an Ollama model counts as unknown, version 3 having been measured without `think: false` for a thinking model the former test missed): a closed one-field schema whose only value the prompt does not name. `supported` = HTTP 200 and exactly that object; `unsupported` = HTTP 400/422 with the schema while the same request without it is answered; `ignored` = accepted (200) but not followed; anything else (network, timeout, 5xx, 401/403/404/405/429) gives no result and stores nothing. It counts only with the service's `format_probe` (2, for OLLAMA 4; `LLM.formatCapability`); the row's format flag is the displayed result, not a probe of its own.
- **Thinking on the native path.** A model with Ollama's capability `thinking` (e.g. Qwen3) thinks by default on `/api/chat` unless the request says `think: false`; with the extraction's budget it can spend every token on thinking and answer nothing (`done_reason: length`, empty `message.content`). Ollama refuses only `think: true` for a model without thinking and accepts `think: false` for every model. So the native request says `think: false` always, except for a model the native thinking probe found not to think (`LLMModel.nativeNoThinking()`); extraction never thinks. The thinking capability of an `OLLAMA` model is measured natively by the LLM selection (`htroot/env/scoutro/thinking-probe.js`): Ollama's own capability list of `POST /api/show` (the list `/api/chat` itself decides by; static, nothing generated), and only without such a list a small `/api/chat` with `think: true`. It is stored with `thinking_probe: 2` and read by `LLM.thinkingCapability`; a value without it comes from the former streaming test on `/v1/chat/completions`, which missed the thinking of e.g. Qwen3 (stored as unsupported), and counts as unknown for the native path, so such a model gets `think: false` right after the update, before any new probe. No model name decides anything. Other services keep their thinking test and values; the chat (`/v1`) keeps reading the stored thinking value as before and, for a model marked as thinking, sends its no-thinking parameters (`reasoning_effort` none), so after a native probe finds a model thinking, the chat no longer lets it think either. `llm.structuredOutput.thinking` and `think` show the native state.
- A value of the former mood probe (up to Scoutro 0.8.3, no `format_probe`: an invalid schema type `literal`, and a wrong mood counted as unsupported) is read as `unknown`, so `auto` sends the schema as before (`schema_unverified`) and never turns it off on that value. The stored value stays until the LLM selection page is opened, which tests the model again; no probe runs on its own. Only a current `unsupported` (the endpoint refused the schema) leads `auto` to `validator_only`. The setting overrides the capability either way; a forced `json_schema` is never shown as enforced unless the current capability is `supported`.
- `llm.structuredOutput`: `setting`, `api` (`ollama_native`, `openai_compatible`), `capability`, `mode`, `request` (`json_schema`, `json_object`, `none`), `reason` (`capability_supported`, `capability_unknown`, `capability_ignored`, `capability_unsupported`, `setting`, `http_400`), `withoutSchema`, `requests` per format (HTTP requests, a fallback retry included), `rejections`, `lastRejection`. In memory since the start, reset by a restart; no answer, host, key or URL.
- The validator decides in every mode: the same rules, the same quotes, the same counters. The mode is transport only: it is no part of the extractor identity (`NAME`/`VERSION`/`PROMPT_HASH`), of the cache key or of the LLM selection, so a changed setting or capability invalidates no cache entry and re-examines no document.

**Counters** (`llm.processed` in `GET /kg/status`; the LLM tier panel of the overview):

- All counters are in memory, per `LlmService` instance, and count since the start of Scoutro (since the pod start). A restart or an upgrade resets them; they are neither persisted nor rebuilt from the graph, so they are no historical statistics. They count the answers of fresh model calls; a cache hit counts nothing.
- A whole answer that is no JSON object of the schema's shape is refused: `answersRefused` with `refusedBy` (`invalid_json`, `not_an_object`, `unknown_field`, `schema`, `too_many_items`, `too_large`, `empty`). A refused answer counts no items.
- Of an accepted answer every item counts once: `entitiesAccepted`, `claimsAccepted`, `valuesAccepted`, `droppedUngrounded` (the quote does not occur verbatim, lacks the names, or a price or salary quote holds no amount with a currency) or `droppedInvalid`.
- `droppedInvalid` counts **single dropped entities, claims and values**, not failed calls and not refused answers: one answer can bring valid items and several invalid ones. `droppedInvalidByReason` splits it by the first rule the item breaks, in the order `LlmExtractor.validate` checks the rules (the counting does not change that order or any rule). Every code is always present (0 if it never occurred); the codes add up to `droppedInvalid`, read together under one lock. They are diagnostic values; no model answer, quote or page text is stored or logged for them.

"Missing" means the field is absent or JSON null (for a name also blank); "malformed" that it is no JSON string or a string over 300 characters (a quote over 300 characters counts as too long).

| Code | Validator condition (in check order per item) |
| --- | --- |
| `entity_not_object` | the array element is no JSON object |
| `entity_extra_field` | a field other than `id`, `type`, `name`, `kind`, `quote` |
| `entity_missing_id`, `entity_malformed_id` | `id` missing or malformed |
| `entity_invalid_id` | `id` does not match `^[A-Za-z0-9_-]{1,32}$` (for example a name with spaces as the id) |
| `entity_duplicate_id` | `id` is a known entity's (`k1`, …) or an entity's accepted earlier in the same answer |
| `entity_missing_type`, `entity_malformed_type` | `type` missing or malformed |
| `entity_unknown_type` | `type` is not `organization`, `facility`, `site`, `service`, `job` (exact, so `company`, `Organization`, `person` too) |
| `entity_missing_name`, `entity_malformed_name` | `name` missing (or blank) or malformed |
| `entity_name_too_short` | the normalised name has fewer than 2 characters |
| `entity_person_or_contact` | the name starts with a salutation or title, or contains `@`, a URL or a phone-like run of 6 digits (O7) |
| `entity_missing_quote`, `entity_malformed_quote` | `quote` missing or no string |
| `entity_quote_too_long` | `quote` over 200 characters |
| `claim_not_object` | the array element is no JSON object |
| `claim_extra_field` | a field other than `subject`, `predicate`, `object`, `hedged`, `quote` (for example `confidence`) |
| `claim_missing_subject`, `claim_malformed_subject` | `subject` missing or malformed |
| `claim_missing_object`, `claim_malformed_object` | `object` missing or malformed |
| `claim_self_reference` | `subject` equals `object`, also after a new id of a known entity (same type and name) is read as that entity, or both ends are entities of one name (27.7) |
| `claim_missing_predicate`, `claim_malformed_predicate` | `predicate` missing or malformed |
| `claim_unknown_predicate` | `predicate` is no relation of the vocabulary (exact, so a synonym like `runs` too) |
| `claim_missing_quote`, `claim_malformed_quote`, `claim_quote_too_long` | as for entities |
| `claim_invalid_hedged` | `hedged` present but no JSON boolean (for example `"false"`) |
| `claim_unresolved_subject`, `claim_unresolved_object` | the id is neither a known entity nor an entity of this answer (also a name instead of an id) |
| `claim_entity_dropped` | an end is an entity of this answer that was dropped (its own reason or `droppedUngrounded` counts it) |
| `claim_subject_type_mismatch`, `claim_object_type_mismatch` | the subject's or object's type is not allowed for the relation |
| `claim_employer_unresolved` | after grounding: a `hiring_organization` quote names a posting on behalf of a client (`für unseren Kunden`, `on behalf of our client`, …); the intermediary is no employer |
| `value_not_object` | the array element is no JSON object |
| `value_extra_field` | a field other than `subject`, `predicate`, `quote` (for example a `value` written by the model) |
| `value_missing_subject`, `value_malformed_subject` | `subject` missing or malformed |
| `value_unresolved_subject` | the subject id is neither a known entity nor an entity accepted in this answer |
| `value_missing_predicate`, `value_malformed_predicate` | `predicate` missing or malformed |
| `value_unknown_predicate` | `predicate` is not one of the value predicates (`price`, `salary`, `category`, …) |
| `value_missing_quote`, `value_malformed_quote`, `value_quote_too_long` | as for entities |
| `value_subject_type_mismatch` | the subject's type cannot carry the value (a salary of a service, a price of an organisation) |
| `value_signal_context_missing` | after grounding: a signal value (`system_signal`, `business_need_signal`, `business_role_evidence`, `job_status`) whose quote the shared signal parser (`BusinessSignals`) reads no context in |

The list is `LlmExtractor.INVALID_REASONS`; a new rule gets a new code and is registered there (the status lists every registered code with 0, and the overview shows each occurring code with its label `invalid_<code>`; a code without a label stays visible as the code). Within one item the order is the one above (structure, then fields, then references and types; the two codes marked *after grounding* are checked once the quote is found verbatim, a quote not found counts as `droppedUngrounded`), for example an entity with an extra field and an unknown type counts as `entity_extra_field`, a claim that refers to itself with an unknown predicate as `claim_self_reference`, a claim with a string `hedged` and an unresolved subject as `claim_invalid_hedged`.

**Knowledge prompt** (the system prompt of the LLM tier; `GET` / `POST /scoutro/api/v1/kg/prompt`, administrator only):

- **Default and active version.** The compiled-in `LlmExtractor.SYSTEM_PROMPT` is the default and the fallback. An administrator can activate another text without a new image; Scoutro stays the source of truth and runs it (`KnowledgePrompt`). The schema stays compiled in: the validator depends on it.
- **Storage.** In the graph's `kg_meta`, not in a file: `llm_prompt_active` (revision, source `default` or `custom`, time, and the text of a custom version only; a `default` version is always the compiled-in text of the running release) and `llm_prompt_history` (the last 50 activations: revision, hash, source, time; no texts). Both are in every backup, come back with a restore and are kept by a rebuild. An unreadable or invalid stored version runs the default.
- **Identity.** The prompt hash is `sha256(VERSION + active text + SCHEMA)` (16 hex digits; the default's is `PROMPT_HASH`). It is part of the extractor identity (`kg_extractor.prompt_hash`, so evidence names the prompt that produced it) and of the cache key: another prompt is never answered from the cache of this one. One document is asked with one version throughout.
- **No automatic re-extraction.** A new prompt, activated or a new compiled-in default, applies to new and changed pages from the next LLM call on (an activation needs no restart). Done documents are not read again, no cache entry and no evidence is deleted; the start records `llm_prompt_changed`, an activation `llm_prompt_activated`. Only a new extractor `VERSION` reads every document again (`llm_extractor_changed`). Reading the done documents with a new prompt will be a separate administrator action.
- **API.** `GET` returns `activeVersion`, `activeHash`, `source`, `modifiedAt`, `differsFromDefault`, the active `text`, the `default` (hash, text), the `limits`, the `history` and `reexamination: "manual"`. `POST {"action":"validate","text":…}` checks a draft and stores nothing (`valid`, `reason`, `hash`, `differsFromActive`, `differsFromDefault`); `{"action":"activate","text":…,"expectedRevision":n}` makes it active (a new revision; the same text again changes nothing, `changed: false`); `{"action":"reset","expectedRevision":n}` makes the compiled-in default active again. 422 `prompt_invalid` (`details.reason`), 409 `prompt_revision_conflict` (`details.activeVersion`) for a stale `expectedRevision`, 409 `kg_disabled`. The body may be 64 KiB. Agents never get the route (also not with `kg.read`); there is no public or guest access and no CLI command. `llm.prompt` in `GET /kg/status` shows the metadata (no text).
- **A draft** has 200 to 8000 characters, only printable text with tab and line breaks, and nothing that looks like a key, token or password (`sk-…`, `Bearer …`, `password=…`, private keys, cloud and chat tokens): `prompt_missing`, `prompt_too_short`, `prompt_too_long`, `prompt_control_characters`, `prompt_secret_like`.

### 6.4 Cache

- **Key:** SHA-256 over:
  - the extractor ID (name, version, model, prompt hash of the active knowledge prompt, see below);
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
| Text | one document at a time (≤ 12 000 characters per reading to the LLM; a continuation up to 5 chunks more) |
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
| `scoutro.kg.extract.maxStatementsPerDoc` / `maxExcerptChars` / `maxInputChars` | 50 / 200 / 12 000 | Growth per document and the LLM tier's first reading |
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
| `llm.kinds.<collection>` | the facility kinds of the collection's vocabulary profile (`llm_kinds` in the vocabulary files, [28](#28-collections-named-by-the-operator-vocabulary-profiles)); empty without a vocabulary | advanced |
| `vocab.<collection>` | the mapping of the vocabulary files; none for a new collection | yes |
| `migration.collectionProfiles` | written once by the upgrade of [28](#28-collections-named-by-the-operator-vocabulary-profiles), never set by hand | no |
| `budget.*`, `disk.*`, `wal.*`, `tmp.maxBytes`, `read.maxTransactionMillis`, `integrity.maxMillis` | [7.2](#72-settings) | budget and disk yes, rest advanced |
| `jsonld.*` | [7.2](#72-settings) | yes |
| `queue.maxItems`, `capture.maxPending` | 200 000, 100 000 | no |
| `extract.maxStatementsPerDoc`, `extract.maxExcerptChars`, `extract.maxRuleInputChars` (tier 2), `extract.maxInputChars` (LLM, 2b) | 50, 200, 65 536, 12 000 | advanced |
| `llm.parallel`, `llm.timeoutSeconds`, `llm.maxAttempts`, `llm.breakerFailures`, `llm.breakerMaxBackoffMinutes`, `llm.maxDocsPerHost` | 1, 120, 2, 3, 60, 25 | advanced |
| `llm.answerReserveTokens` (tokens of the context window kept for the answer, at most the row's output cap), `llm.continuationChunks` (chunks one continuation pass reads of a long page's content; passes repeat until the content is read; 0: none) ([27](#27-llm-tier-coverage-selection-request-budget-long-pages-missing-text-and-diagnostics)) | 1 200 (256–32 768), 5 (0–20) | advanced |
| `llm.structuredOutput` (`auto`, `json_schema`, `json_object`, `none`) | auto | advanced |
| `llm.collectionFit` (a home page's first request also asks whether the website fits its collections; only with `llm.structuredOutput` `none` or `json_object`, [27.10](#2710-the-collection-fit-of-a-home-page)) | false | advanced |
| `llm.maxRequestsPerDay` (model requests per UTC day, 0: no cap), `llm.weight.<collection>` (share of each scan batch, 1–10) ([collection move, priority and cap](SCOUTRO_KG_COLLECTION_MOVE.md)) | 0, 1 | advanced |
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
| O2 | ~~Industry vocabulary~~ — decided: extensible, collection-specific vocabulary without a schema rebuild; package 2b shipped a small start vocabulary of facility kinds for four collections of one installation; since [28](#28-collections-named-by-the-operator-vocabulary-profiles) the kinds come with the vocabulary profile a collection is given (`llm_kinds`), replaced per collection by `llm.kinds.<collection>`, never with a collection's name; kinds are entity attributes, so new ones need no schema change | Tier-3 quality; kinds are merge discriminators | Owner (further terms) |
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
| `ImprintOperatorTest` | Rule version 4: the real LIVAID layout (name, tagline, managing director, address) as lines and as YaCy's parsed text of `<br>`, inline and `<div>` HTML: LIVAID with its own contacts, no insurer contact or legal form, no person; headings and labels against names and running text ("Webdesign Beispiel GmbH"); bold legal names; a person after a role; the synthetic operator without a legal form (LIVAID) with its own contacts only; the insurer, chamber, authority and web agency sections never name the operator nor lend it contacts, also after an operator GmbH; an insurer or broker operating its site stays its operator; unclear evidence (no line breaks, another domain, a person) names nobody; section boundaries |
| `ImprintOperatorIdentityTest` | Extraction with the business rules and `IdentityResolver` on a real store: home page and imprint in both orders end on one organisation named LIVAID with its services and own contacts, also for each form of the real layout; the insurer's own site is an organisation apart; a graph with the version 3 merge keeps the merge after re-extraction while the insurer's facts are gone |
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
2. The scan of the LLM tier walks the documents still to do (`llm_status IS NULL`, active) per collection (27.2) and either queues them (priority: imprint and about pages 1, services, locations, contact and team 2, home page 3, others 5) or marks them `skipped` (`not_selected`, `no_host`, `host_cap`). The queue holds at most 10 000 items; the scan fills it only while at most 100 items are due within a minute.
3. A worker claims one item, reads the document by real-time get and checks that Solr holds the input the graph published, as far as the tier reads it: the content hash (schema 4; the input hash for an older page), so a new vocabulary or extractor version does not count as a change (otherwise the item waits for the sync, with a bounded backoff, see [27.1](#271-reliable-processing)). It runs tiers 1 and 2 again for the page's known entities and skips pages that are no candidates (`not_candidate`). It reads ≤ `extract.maxInputChars` of text in chunks of ≤ 4 000 characters.
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
| Extraction (C, D, F, G, H, I) | `extract/JsonLdExtractor` (version 3), `extract/BusinessRules`, `extract/BusinessFacts`, `extract/Values`, `extract/RuleExtractor` (version 4), `extract/LlmExtractor` (tier 2 version 2), `extract/ExtractContext`, `extract/Claim`, `extract/Mention` |
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
- **Business vocabularies.** `categories.json` holds four seed vocabularies, profiles an operator gives a collection (it maps no collection, [28](#28-collections-named-by-the-operator-vocabulary-profiles)):

  | Vocabulary | Categories | Groups | Facility kinds (LLM tier) |
  |---|---|---|---|
  | care | 22 | 6 | 7 |
  | coaching | 34 | 7 | 4 |
  | software | 35 | 8 | 2 |
  | construction | 110 | 14 | 8 |

  It also holds segments, customer types, company sizes and employment types. Files in `DATA/SCOUTRO/knowledge/vocabulary/*.json` extend it; NACE Rev. 2.1 comes from `nace-2.1.csv`. The vocabulary and the per-collection settings are part of the extractor identity: a change re-extracts at low priority.
- **Schema 4** only adds:
  - `kg_doc.content_hash`;
  - `kg_doc_link` (outbound registrable domains);
  - `kg_derived` (derived rows, visible only with both collections);
  - change kind 3.

  The change feed keeps its sequence. The LLM mark is the content hash, so a new vocabulary keeps the LLM evidence; the new extractor version (vocabulary 2, with its prompt) re-examines a page and keeps the old evidence until it is replaced.
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

- **Off by default.** `jobs.collections` lists the collections with job postings (job objects). The software hints of job advertisements are a policy of their own since 0.10.2 ([29.3](#293-software-hints-of-job-advertisements)).
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
- **Jobs:** job objects only for collections named in `scoutro.kg.jobs.collections` (or `*`); a new collection never gets them by itself. Software hints of job advertisements: see [29.3](#293-software-hints-of-job-advertisements).

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

## Package B: durable observations supersede live-only job knowledge

The current lifecycle, dates, settings, extraction and history/export contract
are specified in [SCOUTRO_DURABLE_OBSERVATIONS.md](SCOUTRO_DURABLE_OBSERVATIONS.md).
Earlier live-only recommendations in sections 23/24 are superseded where they
treat disappearance as job end, rely on current Solr/evidence for historical
knowledge, or couple extraction/display/matching to a single jobs switch.
Existing industry matching remains; additional system/need matching is package C.
Rebuild carries the authoritative archive at final swap; restore selects the
chosen snapshot without silently merging newer history.

## 27. LLM tier coverage: selection, request budget, long pages, missing text and diagnostics

Follow-up of a read-only coverage analysis of 0.10.0 (11.10.2026, sections 9 to 13 of an operator report that is not part of this repository). Figures quoted from it are **example figures of one installation**; sites, collection names and hardware below are examples, not Scoutro defaults: collections are created by the operator, with names of their own. Its telemetry findings (`claim_employer_unresolved`, `value_signal_context_missing`, the `vocab_counts` label) were already shipped in 0.10.0 and are not part of this. No schema change (schema 6), no new table; the scan positions and the progress of long pages (27.5) are rows of `kg_meta`.

### 27.1 Reliable processing

**Found** (section 10, in the analysed installation: 2,679 pages pending behind the scan, neither marked nor queued; reproduced with isolated fixtures in `LlmStrandedTest`): a worker completed a queue item without a status for its page when

- it could not confirm the input (Solr ahead of the graph, or the page missing in Solr) after `llm.maxAttempts` + 2 tries,
- the page changed during the call (the compare-and-set of the publication failed), or
- a new vocabulary or extractor identity, part of the input hash but not of what the tier reads, made every page not yet re-extracted "changed".

The single scan position lived in memory and moved forward only: such a page was found again after the wrap-around (weeks, with about 115,000 pages pending in the analysed installation) or a restart. The same held for a page that became to do again behind the position (a new text, a new extractor).

**Now:**

- The worker compares, marks and publishes on what it reads: `coalesce(content_hash, input_hash)`. A new vocabulary throws no answer away (the publisher already kept a published result across one). A page published before schema 4 (no content hash) is confirmed with the input hash of the extractor versions of schema 3, as the sync compares it.
- An item is completed only when its page got a status or needs nothing any more. An input that cannot be confirmed is retried with a growing backoff (1 to 30 minutes); after `llm.maxAttempts` + 6 tries the page is `failed` with the reason `input_unconfirmed` (visible; `llm_retry` or a new input open it again). A page that changed during the call is due at once for its new input, bounded the same way.
- Every revisit (27.2) finds a page left unqueued behind a frontier, also one left by 0.10.0, without a restart.
- Counters (`processed`): `unconfirmedRetries`, `inputUnconfirmed`, `requeuedBehindFrontier`.

### 27.2 Fair, resumable selection

Every collection the LLM tier follows has its own scan with two positions, persisted in `kg_meta` (`llm_scan:<coll_id>` = `frontier:revisit`):

- The **frontier** walks the collection's pages in row order and keeps its position across restarts. A refill pass (when at most 100 items are due within a minute) gives every collection an equal share of 200 pages, minus the revisits; a share a collection cannot use goes to the others; the collection served first rotates. A collection crawled after the others (its pages at the end of the row order) is queued from the first pass on, not after every older row. Nothing depends on a collection's name: every collection the operator created and switched on for the LLM tier gets its scan and its share (the tests use names such as `alpha-sites` or `collection-b`).
- The **revisit** walks behind the frontier, at most 50 pages per pass over all collections (an equal share each, as far as it lasts), and queues pages that became to do there; a finished sweep of a collection waits 10 minutes before the next one.
- **Order and priorities:** within a collection older pages come first. The queue keeps its priorities (imprint and about 1, services, locations, contact and team 2, home page 3, others 5, continuations 8), but an item waiting longer than two hours is claimed before newer items of a higher priority, so a page is never held back for good.
- **Unchanged:** the per-host cap (finished plus queued per host; a queued continuation belongs to a finished page and does not count again), the schedule and manual runs (`LlmTiming`), the gates, the circuit breaker, claims, chunk progress and the cache.
- A bulk reopening (a changed LLM selection, a new extractor version, `llm_retry`, a full reset) forgets the positions; the frontiers walk again (cheap: only pages to do are queued). Collections the graph follows but the LLM tier does not are still marked `not_selected`, without a model call.
- Status `llm.selection`: `collections`, per collection `collection`, `frontier`, `revisit` (row IDs as of the last pass) and `queued` (pages queued since the start); `scanBatch`, `revisitBatch`, `agingMinutes`. Counter `scanPasses` (refill passes, also those that only marked pages).

### 27.3 Request budget

The analysis (section 11) measured the parts of a request: system prompt 3,283 characters, schema about 2,200, a chunk of up to 4,000, known entities typically about 200 and at worst 12,831 characters (40 of 300) - about 5,700 tokens against a window of 4,096. **What is passed on** (checked in the code): the knowledge tier calls an `OLLAMA` model natively (`/api/chat`) with `num_predict` = the row's `max_tokens` (for example 262,440) and, before this change, without `num_ctx`: the server's own context length applied, and `ai.service_num_ctx` had no effect on this path. Ollama keeps prompt and answer in one window. **Example measurement** (one installation, read-only, Ollama 0.20.5): the server loads `qwen3:14b` with 32,768 tokens and one slot of its own accord: `OLLAMA_CONTEXT_LENGTH` is not set, so the default applies that Ollama 0.20.5 chooses once at server start (`server/routes.go`) from the total memory of its GPUs less `OLLAMA_GPU_OVERHEAD`, not from free memory: 32,768 from 23 GiB, 4,096 below, 262,144 from 47 GiB (for example, a GPU with 24 GiB in total gets 32,768); requests had about 900 to 2,400 prompt tokens, and no truncated prompt or context shift was logged. The analysis's 4,096 was therefore not this path's window; the budget keeps a smaller one from cutting a request silently.

- Every request is planned before it is sent (`RequestBudget`): system prompt; the schema where the endpoint may count it as prompt (OpenAI-compatible; Ollama applies it as a grammar); the user message (site, language, title, facility kinds, known entities, chunk); a frame of 64 tokens for the chat template; and the answer reserve `llm.answerReserveTokens` (1,200 tokens by default: about 15 answer items of a quote of up to 200 characters plus their fields; at most the row's output cap). Characters are converted on the safe side: 4.0 per token for the instructions, 3.5 for page text and data, 3.2 for JSON.
- The window is, in this order: the service's configured `ai.service_num_ctx`; for an unconfigured Ollama service, the window the server reports for the loaded model (`context_length` of `GET /api/ps`, read-only, at most every ten minutes with a timeout of 3 seconds; the last one seen is kept while the server has unloaded the model, since not loaded is no other window); else 4,096 tokens, assumed (the smallest default of Ollama 0.20.5; older releases used 2,048). As long as no window was seen (after a restart the model is often not loaded yet, or the server is not reachable) it is read again after a minute, so the first request, which loads the model, ends the assumption; `/api/show` is asked only once the server answered, with the same 3 seconds for connecting. The model's maximum is read for a configured Ollama service too. The status shows the window as last read and never asks the server. The model's own maximum (`model_info.<architecture>.context_length` of `POST /api/show`; `qwen3:14b` reports 40,960, the `max_position_embeddings` that Qwen's model card explains next to the native context of 32,768 tokens; the example installation loads it with 32,768) is shown, not used: the server decides the window, not the model. **`num_ctx` is now sent on the native path only where it is configured**, so an operator's setting takes effect and the budget plans against what the server uses; a detected window is never sent, so an unconfigured server is asked exactly as before. No production limit is raised. A configured value below the loaded window shrinks it, and Ollama loads the model again whenever a request asks for another `num_ctx`: leave `ai.service_num_ctx` unset for the knowledge host, or set it to the window the server loads; to fix the window for every client of a shared server, set `OLLAMA_CONTEXT_LENGTH` there.
- A chunk that fits with all known entities is asked exactly as before (same cache key). Otherwise it is asked with the known entities its text names (job postings always), and then with the others in their order as far as they fit, numbered again (`k1`, ...): the validator accepts a claim or a signal about a known entity only with a verbatim quote that contains its name, but a plain value (a price, an industry, a company size) needs no name in its quote, so the page's operator, usually first, stays in the request. If these still do not fit, the chunk is split (at most twice; offsets kept, nothing lost). If a part still does not fit, the document is not sent: it is `failed` with the reason `context_budget` (after a larger window, `llm_retry`).
- An OpenAI-compatible service without `ai.service_num_ctx` is planned against the assumed 4,096 tokens (its request carries that `num_ctx`, as before); for a service with a larger window, configure it, or long known-entity lists are trimmed and chunks split needlessly.
- Status `llm.context`: `windowTokens`, `windowSource` (`configured`, `detected`, `assumed_default`), `modelMaxTokens`, `numCtxSent` (true for a configured window and on the OpenAI-compatible path), `maxOutputTokens`, `answerReserveTokens`, `fixedPromptTokens`, `schemaCounted`, `promptTokensEstimatedMax`, and `measured` (what the endpoint reported: answers, prompt and answer tokens, maximum and average, `answersCutAtLimit` for `done_reason`/`finish_reason` `length`; Ollama's prompt count leaves out a prefix served from its prompt cache, so it is a lower bound). Counters `knownTrimmed`, `chunksSplit` (once per published reading), `contextRefused`.

### 27.4 Recurring navigation of long pages

Example (section 12 of the analysis): a site menu of about 4,200 characters stood at the start of every page of one site, about 35 % of the 12,000 characters a long page was read with. For a page longer than `extract.maxInputChars`, blocks of lines that recur on up to three other pages of the same host (the first by document ID) move behind the page's own lines:

- A line recurs if it occurs, compared without case, Markdown marks and repeated spaces, on two of the compared pages (on the only one if the host has one other page). Only runs of at least 300 characters move; a single recurring line in the content (a service, the operator's name) stays where it is.
- **Nothing is removed.** Every line stays verbatim and is read where the input reaches it: services, imprint data and quotes are kept, and the quote check compares against the text the model was given. Quote positions are translated back, so the evidence locator (`text:<position>+<length>`) still points into the stored text. A quote that runs across a block moved from between its parts (verbatim in the text the model read, not in the stored page) is left out like an ungrounded one (`droppedUngrounded`).
- A page that fits the input is read unchanged (same text, same cache key). For a long page with moved navigation the chunks and their cache keys change; this concerns only pages read for the first time or again.
- The recurring lines of a host are cached (128 hosts). Counters `navigationMoved`, `navigationMovedChars` (once per published first reading).
- **Navigation is never asked once the page's own content is read.** The content ends where the moved blocks start; a first reading and every continuation read only up to there. A long page whose own content fits the input once the navigation moved is done with its first reading (`full_text`, 27.5), without a continuation. The moved blocks recur on the host's other pages and are read there where such a page fits the input unchanged. Counter `navigationSkippedChars`.

### 27.5 Long pages read to the end of their content

Example (section 12 of the analysis): a reference page of about 67,000 characters was cut at 12,000 in the middle of its case studies; with the first version of this change it was still cut at 32,000. A long page is now read to the end of its own content in passes of a bounded number of requests:

- The **first reading** reads the first `extract.maxInputChars` characters of the page's content (12,000 by default, as before). If the content goes on, the page is `done` with the reason `more_text` and a **progress row** in `kg_meta` (`llm_layout:<doc_id>`): the position read up to, the end of the page's own content, the blocks the layout moved (27.4) as positions in the stored text, the length of the stored text, the start of the input hash it belongs to, and the passes that failed in a row. A row holds positions only, never text (under 512 bytes in the tests; a few hundred bytes per long page).
- Each **continuation pass** reads at most `llm.continuationChunks` chunks (5 by default, one request each, about 20,000 characters), from the line or sentence the last pass ended in (at most 400 characters back, so a passage cut there is read whole) up to the end of the content, publishes its result and its new position in one transaction, and leaves the page `more_text` for the next pass. When the content is read the page is `done` with `full_text` and the row goes. A page that fits the input keeps no reason, as before.
- **Same layout, same quotes:** a continuation lays the page out with the blocks its first reading moved, never with the host's pages as they are now, so pages added, changed or deleted on the host meanwhile cannot shift its position; a quote is located in the stored text (`text:<position>+<length>`) and is verbatim there. A pass adds its result to what the page holds: nothing is replaced, a statement the page already supports keeps its one tier-3 evidence (the evidence's primary key), and a compare-and-set on the content and on `more_text` makes a second or stale pass publish nothing.
- **First readings and other collections go first:** at most ten pages are continued at a time, at priority 8, so a pass waits behind first readings until it aged two hours; continuations do not count against the per-host cap. Legal pages (privacy, GDPR, terms, cookies, withdrawal, disclaimer; by the URL's path, not its host) are not continued (`text_cut`).
- **Existing limits hold:** `extract.maxStatementsPerDoc` (50) holds over all readings of a page; a pass that would start with the page at the limit ends it as `text_cut` without a request (counter `statementCapDocs`). The request budget (27.3) plans every pass; a part it refuses ends the page as `text_cut`. No limit of the crawl, the host or the storage is raised.
- **Failures, restarts, changes:** answered chunks stay in the page's checkpoint and the cache, so a stop or a restart asks nothing twice and the next pass starts where the last one stopped. A pass whose requests fail `llm.maxAttempts` times keeps the progress and is tried again after an hour, then two hours (counter `continuationRetries`); after three failed passes in a row the rest shows as `text_cut` and the page stays `done` with what was read. A new text of the page is read from its start (the progress row of the old text goes); an input Solr cannot confirm ends the continuation as `text_cut` after the backoff of 27.1. Switching continuations off (`llm.continuationChunks` 0) keeps pages marked `more_text` as they are until they are switched on again.
- **Pages read before this version** (0.10.0 and earlier: read up to `extract.maxInputChars`, `done` without a reason, the rest unread unseen) are checked once by an **audit**: 20 pages per scan pass in row order, up to the newest page done when this version first started (`kg_meta` `llm_audit_at`, `llm_audit_end`). It fetches the stored text and asks nothing: a page read whole stays as it is; a long page gets `more_text` with its progress at the position the earlier reading stopped (only blocks behind it move, so the published evidence keeps its positions), `full_text` if only recurring navigation follows, `text_cut` for a legal page or with continuations off. Nothing is read again from the start; the continuations then read the rest behind the first readings. Counters `auditedDocs`, `backfilledDocs`.
- Status `llm.continuation`: `chunks`, `passChars`, `overlapChars`, `failedPassesMax`, `pendingDocuments` (`more_text`), `remainingChars` (content still to read over all of them), `fullTextDocuments`, `textCutDocuments`, `audit` (`checkedUpTo`, `upTo`, `done`). Status `llm.documents.coverage` splits the done pages: `whole` (fits the input), `fullText`, `partial` (a continuation reads on: a first reading is no full coverage), `textCut`, `textMissing` (27.6). Counters `continuationDocs` (published passes), `continuationCalls` (their requests, failed ones too), `fullTextDocs`, `navigationSkippedChars`, `textCutDocs`, `textCutChars`.

### 27.6 Missing and thin page text

Example (section 12 of the analysis): about 9 % of the pages of one installation had under 200 characters of text, for example a home page stored with 89 characters (its title, a skip link and a phone line) while other pages of the site had up to 67,000. A read-only investigation with 61 synthetic pages run through the crawler's parser path reproduced the cause for such pages: one stray quote or `<` in the delivered HTML made YaCy's HTML tokenizer drop all text after it (a quote outside an attribute value, `a < b` in text, `<`, quotes or a `// don't` comment in an inline script, a `<` in a JSON-LD string, `&lt;` in text), and a single control character rejected the whole page as binary. Without the cached HTML of the example page, its cause stays a strong indication, not a proof.

- **Parser fixed** (`TransformerWriter`, `ContentScraper`): a quote opens an attribute value only after `=`; a `<` not followed by a letter, `/`, `!` or `?` is text; script and style are raw text up to their end tag (JSON-LD is captured intact, script code is no text of an enclosing link or heading); entities are decoded after tags are stripped; a few stray control characters (at most 8, at most one per 100 characters) are read as spaces, binary data is still rejected. 12 of the 61 synthetic pages lose no text any more, none loses text it kept; the 126 existing parser tests pass unchanged. What is crawled, fetched, cached and the robots and blacklist rules are unchanged; no browser rendering, no asset download. Pages already indexed benefit after their next recrawl; none is triggered.
- **Marked, not made up:** a page whose own text (without its title and Markdown marks) has under 200 characters is either **missing its text** — its delivered HTML has 20,000 bytes or more (`size_i`, never part of a hash) or the text asks for JavaScript — and is `done` with `text_missing` (it counts as not covered; whatever text there is is read), or it is **just short** and is read whole as before (counter `thinTextDocs`, no mark). The audit (27.5) marks such pages read before this version. Structured data (JSON-LD, the description) is still read by tiers 1 and 2. A page that only a script renders stays missing: Scoutro does not render pages.
- Counters `textMissingDocs`, `thinTextDocs`; status `llm.documents.coverage.textMissing`.

### 27.7 Semantic enrichment and refusal diagnostics

A read-only analysis of the validator (the only measured shares are those of 0.9.1: unresolved references about 31 %, self-references about 21 %, type mismatches about 20 % of the dropped items) found, besides genuine model errors that must stay refused, losses on the code's side:

- **The operator is offered first and once.** The known entities are listed once per type and name, the declared site operator first, then the other organisations, then facilities, sites, services and jobs. Before, the structured data's services came first and a list cut at 40 entries could lose the operator; an organisation named by the structured data, the publisher and the imprint was offered up to three times, and the model related it to itself.
- **A self-reference is refused and counted.** A relation whose ends are one entity (a new id for a known entity of the same type and name) or two entities of one name is refused as `claim_self_reference`. Before, it passed the validator and was dropped silently while publishing, or stored as a false relation.
- **A claim about a dropped entity counts as such** (`claim_entity_dropped`), apart from a reference to an id that was never declared (`claim_unresolved_*`).
- **An organisation with a legal form joins the declared operator.** The LLM tier's organisations get their legal name, as the rules' do, so the identity resolution (`operator_name`, 4.3) joins one to the declared operator of the domain with the same legal name.
- **Not loosened:** quotes stay verbatim and must name the entities involved, persons and contact data stay refused, ids stay ids (a name written instead of an id stays `claim_unresolved_*`), the type rules of the relations are unchanged, and so are the prompt and the schema (no new extractor identity, no cache loss). The quality of a model's answers is judged by what is accepted and refused, never by a successful HTTP call.
- **Refusal samples:** up to 8 dropped items per answer and every refused answer are kept as samples: the reason, the kind of item, the predicate, the types and states of its references (`known`, `new`, `dropped_entity:<reason>`, `unknown_id`, `name_like`), the page, the chunk's position, the domain, the prompt hash and an excerpt of at most 160 characters without person names, e-mail addresses or phone numbers; never a prompt or a whole answer. They live in memory only (a restart clears them; nothing goes into the graph, an export or a backup): at most 500, 20 per reason, 128 KiB, none older than seven days. `GET /scoutro/api/v1/kg/llm-refusals?reason=&limit=` (administrator; no agent route) lists them, newest first; the status shows `llm.refusalSamples` (size, bytes, per reason, bounds) without excerpts.

### 27.8 Measurements, storage and lifecycle

- **Intake and output** (counters since the start, `llm.processed`): `queued` (pages the scans queued: the LLM-relevant intake), `skippedNotCandidate`, `skippedNotSelected`, `skippedHostCap` (pages unsuitable or out of the selection, no request), `published` (first readings), `continuationDocs` (passes), `calls` and `requestStarts` (requests), `chunksSplit`, `cacheHits`; failures and repetitions: `callFailures`, `timeouts`, `failedDocs`, `unconfirmedRetries`, `continuationRetries`, `abortedChanged`; `averageRequestMillis` over ended requests; the collection fit of home pages (`collectionFit`, 27.10).
- **Waiting:** `processed.queueWait`: how long first readings and continuations waited in the queue until taken (per take, average and maximum).
- **Backlog over time:** `llm.backlog`: one sample per 15 minutes over the last day (in memory) of the pages still to examine, the queue, the pages a continuation reads on and their unread characters, and the change of the pages to examine per hour (negative: the backlog shrinks).
- **What the tier keeps between readings** (`llm.storage`): progress rows (one per page marked `more_text`, positions only) and checkpoints (answered chunks of an unfinished reading), rows and bytes each. A progress row goes when its page is read, cut, changed, reset or removed (pruned, at most 16 a minute, also at start); checkpoints go with their page's reading. The refusal samples are bounded in memory (27.7). Evidence, statements, the change history and backups are never pruned by any of this.
- **Not raised:** the queue (10,000), the scan batch (200), the revisits (50), the continuations queued (10), the per-host cap, the parallelism and the request budget are as before; a larger backend parallelism is not set by Scoutro.

### 27.9 Costs and limits

- **Additional model requests** come only from continuations, splits by the budget, and an answer re-asked after a page changed during its call. A page of 20,000 characters costs 2 to 3 requests more and is read whole; the example reference page of about 67,000 characters costs about 14 more requests in three passes (about 55,000 characters), spread behind the first readings. In the sample of the analysis 5 to 15 % of the pages are longer than 12,000 characters. Navigation behind a page's content costs nothing any more (27.4).
- **Fewer wasted requests:** pages are no longer re-asked after a restart because they were found again, a vocabulary change no longer throws answers away, and an audited page is never read again from its start.
- **Not addressed here:** PDF price lists (blacklisted), the crawl's 15 pages per domain, the per-host cap of 25 for large sites, pages that only a script renders, and model errors the validator must refuse. robots.txt, the blacklist and the validator's rules are unchanged.

### 27.10 The collection fit of a home page

With `scoutro.kg.llm.collectionFit=true` and `llm.structuredOutput` `none` or `json_object`, the first request of a home
page's reading also asks whether the website's main offering fits its specialist collections (the profiles of
Discovery's `profiles.json`): a question of at most 500 characters in the user message, before the DATA block, that shows
the answer's shape as valid JSON with the assigned collections' ids (question version 2 since 0.11.7, part of a
reading's criteria); the model adds the field `collection_fit`. The system prompt, the schema, `LlmExtractor.VERSION` and the prompt hash are
unchanged, so no page is read again for it; every other request and cache key is byte for byte as before, and the
request budget counts the question. The field is split off before the validator, checked apart (a verdict per assigned
collection, a verbatim quote found in the page text, else every verdict `uncertain`), kept with the cached answer and
never applied: no statement, no evidence. A published home page's verdict goes to the correction automation as the
source `kg_home` ([automatic correction, Home page reading](SCOUTRO_COLLECTION_AUTOCORRECT.md#home-page-reading-in-the-llm-tiers-own-request));
a failure there never touches the extraction. Counters: `llm.processed.collectionFit`.

## 28. Collections named by the operator, vocabulary profiles

Up to 0.10.0 the code and the shipped defaults named four collections of one installation: `categories.json` mapped them to the vocabularies care, coaching, software and construction, `KgConfig` always read their per-collection keys, and `KindHints` gave them the facility kinds of the LLM tier by their names. Collections are the operator's, with names of the operator's choice; nothing in the knowledge graph needs particular names any more.

### 28.1 Profiles

- A **vocabulary is a profile** an operator gives a collection: its service categories, groups and audiences, and its facility kinds for the LLM tier (`llm_kinds` of the vocabulary, in `categories.json` and in the operator's vocabulary files; a later file replaces a vocabulary's kinds as a whole). The four shipped profiles carry the kinds the four collections had (care 7, coaching 4, software 2, construction 8).
- **No collection has a profile by itself.** The shipped `categories.json` maps none. A collection gets its profile from `scoutro.kg.vocab.<collection>` (Settings column *Vocabulary*, `PATCH /scoutro/api/v1/kg/collections/{collection}` with `vocabulary`, unchanged) or from a `collections` map in a file of `DATA/SCOUTRO/knowledge/vocabulary/`.
- **Facility kinds** of a collection the LLM tier reads: `scoutro.kg.llm.kinds.<collection>` where it applied before (a collection named in `llm.collections`, or followed by name under `llm.collections=*`); else the kinds a vocabulary file fixes for the collection (`collection_llm_kinds`, see 28.2); else the kinds of its profile (`KgConfig.defaultLlmKinds`); none without a profile. A page in several collections gets the union, as before. Kinds are in the prompt, the validation and the cache key of a request, not in the extractor identity and not in `llmSelectionKey`.
- **Identity:** `llm_kinds` and `collection_llm_kinds` are no part of `Categories.version`; a `collections` map is, as before (a new mapping re-extracts the pages concerned).

### 28.2 The upgrade of an existing installation

`CollectionMigration` runs once, at the start of the runtime and before the settings are read, also while the graph is off:

- **Existing installation:** the graph directory holds `graph.db` or a backup, or a `scoutro.kg.*` setting names one of the four collections (as the end of its key or in its value). Anything else is a new installation: only `scoutro.kg.migration.collectionProfiles=` (empty) is written; no file, no directory.
- It writes the former map into `DATA/SCOUTRO/knowledge/vocabulary/000-migrated-collections.json` (schema `scoutro.kg.categories`), without an entry an operator file already maps (by `Categories.mappedCollections`: a valid name with a valid vocabulary name). The merged map is then the one of the former defaults overlaid by the operator files, so `Categories.version` stays the same, whatever the order of the files.
- Where a collection's profile would give other facility kinds than its name gave (its vocabulary was set by `scoutro.kg.vocab.<collection>`, also to none, or an operator file maps it), the former kinds are fixed for it in the same file (`collection_llm_kinds`). This covers every collection that may have a vocabulary: the four, every mapped one, every one with a `scoutro.kg.vocab.<collection>` key. The kinds are not written as `scoutro.kg.llm.kinds.<collection>`: that key is not read for a collection that is followed only through `*` while `llm.collections=*`, so it could not keep the cache keys of such an installation.
- `scoutro.kg.migration.collectionProfiles` is set to the four collections: `KgConfig` reads their per-collection keys (`vocab`, `prices.staleDays`, `jobs.extract`, `jobs.display`, `jobs.matching`) as it always did, also while they are not followed, so `extractionKey()` stays the same. Collections mapped only by operator files are read, as before, when they are followed, switched off or keyed under `*`.
- **Once:** the marker stops the step. If the file exists without the marker (it could not be recorded), only the marker is recorded; the file, the installation's own from then on, is never written again. Without a settings writer (tests) the step does not run. A file that cannot be written keeps the graph off for this start (`start_failed`), rather than extracting every page again.
- **Check:** at the start in which the step ran, when the graph runs, the stored `kg_meta.extractors` is compared with `SolrDoc.extractors(config)`; event `collection_profiles_migrated` (identity unchanged) or `collection_profiles_identity_changed` (level 2, both values).
- The settings page and the status read the operator's vocabulary directory also while the graph is off.

**Verified** (`CollectionMigrationTest`, `CollectionProfilesUpgradeTest`): for fifteen setups (no override; `vocab.<four>=""` under `*` and a list; `vocab.newportal-web=software`; a fixed list with keys of collections it does not follow; operator files that remap, remap before the migrated file, map to an unknown vocabulary, or map another collection; kinds keys; the graph off) the extractor identity, `extractionKey()`, the vocabulary and the facility kinds of each collection and the LLM cache keys equal those the code of 0.10.0 computed (kept in the test). A graph built under the former defaults keeps `kg_meta.extractors` and every `kg_doc.input_hash` through the upgrade and two restarts, without a re-extraction scan; without the step every page is extracted again.

## 29. Service categories, prices one by one and software hints of job advertisements (0.10.2)

Three findings of the mobile knowledge graph page: the category lists offered only "Every category", so the price comparison could not be used; "With a current price" was a number that led nowhere; and software mentions of job advertisements were read only together with job objects, so a collection without job objects learned nothing from its advertisements.

### 29.1 Service categories

- **Cause.** `kg/facets` lists only stored `category` statements, and a collection gets them only from its vocabulary profile ([28.1](#281-profiles)). A collection without a profile therefore has no categories, whatever its name. On a large graph the facets read could also time out; the page then swallowed the error and showed an empty list. Both looked the same.
- **Read.** `GET kg/facets?counts=false` gives the filter values without counts (quick, for the selects); the page keeps them for at most 60 seconds per collection, and a reply of a former collection never fills the select of a later one. `profiles` lists each visible collection with its vocabulary and where it comes from (`setting`, `vocabulary_files`, `none`). The derived counts are read on their own: when that read fails, `derived` is `null` with `derived_error` and the other values stay.
- **Shown.** Services, Compare and Prices say why a category list is empty: the read failed (with the error code; no statement about the data), the collection has no profile (with how an administrator assigns one), or the profile has no category on a visible page yet. A collection switch drops a chosen category, which belongs to the former collection's vocabulary.
- **Any collection name.** Nothing depends on the four former names; the tests use collections named by the operator.

### 29.2 Prices one by one

- **One rule.** `BusinessView.servicePrices` decides the status of every published price of one service (current, conflicting, outdated or expired, uncertain), with the price age of the collection (`prices.staleDays.<collection>`) and the conflict rule of the business view. `with_price` of `kg/services` (a service with a current price), the new `with_any_price` and the price list use it alike, so the count and the list agree for every collection and every viewer (`ServicePricesTest`).
- **List.** `GET kg/services/prices?name&category&status&offset&limit&collection` (`status` `current` default, `conflicting`, `outdated`, `uncertain`, `all`; `limit` 1–50) gives one item per published price: the provider's own service, its providers (name, domain, collections) and the price exactly as published (amount or range, currency, unit, kind, conditions, VAT, the date and validity, the source page), sorted by service name. `counts` of the prices per status, `services_with_current` (equal to `with_price`), `total` (prices of the status) and `truncated` (more than 2,000 services with a price; narrow by name or category). Name and category are optional, so the list also works without a vocabulary.
- **Nothing mixed.** No average, no conversion, no price of one provider on another's row; historical and conflicting prices keep their status.
- **Rights.** The read uses the viewer of the request: the selected collection and, for an agent, its granted collections (`kg.read`). Nothing is counted that is not shown.
- **Page.** "With a current price" in Services and Compare is a link to `?view=prices` with the same name and category; the Prices view pages by 25 prices with status, name and category filters, phone and desktop.

### 29.3 Software hints of job advertisements

**The three switches before 0.10.2.** *Extract jobs* (`jobs.extract.<collection>`, initially `jobs.collections`) made job objects and, with them, the software mentions of the advertisement (as `system_signal` of the job); *Display jobs* (`jobs.display.<collection>`) only hid job objects in reads and the chat; *Use stored job signals for matching* (`jobs.matching.<collection>`) let the hints of job objects feed customer suggestions. A collection without job extraction got no hint from its advertisements, whatever the display said.

**Now.** A fourth policy, *Read software hints from job ads*, is independent of job objects and of the display:

- **Keys.** `scoutro.kg.jobs.signals.<collection>` (`true`/`false`) or the list `scoutro.kg.jobs.signals.collections` (unset: every followed collection; `*` and keyed collections as for the other per-collection keys). Unset means **on**. The policy is not part of `extractionKey()` and not of the extractor identity.
- **What is read.** On a job page of the collection (a `JobPosting` in JSON-LD, or a careers page by the business rules), the software and system mentions of the description, skills, qualifications and text, for the employer the advertisement declares: exactly one `hiringOrganization` with a name in JSON-LD, or a careers page whose operator says it is hiring (`wir suchen`, `unser Team`, `we are hiring`, …). An intermediary ("for our client", `im Auftrag`), no declared employer or several employers: nothing, so a hint never lands on the wrong company and the existing protections against false customer matches stay.
- **What it means.** The hints are `system_signal` statements of the employer with section `job_ad_…` and the reading of a posting: "we use" is internal use, a required or desirable skill is `required_competence` or `desirable_competence`, work for clients is `customer_projects`. A requirement is never a proof of own use, of a purchase or of a need; no `business_need_signal` and no `business_role_evidence` come from an advertisement. With job objects on, the hints stay on the job as before (unchanged statements and hashes).
- **Website technologies are not system signals** (Scoutro R5, [website technologies](SCOUTRO_WEBSITE_TECHNOLOGY.md)): what a site's markup integrates is `website_technology` (tier 1, extractor `technology/1`), never a `system_signal`, an offer or a matching input; schema 9 archives it as an observation when it changes.
- **Matching.** A `job_ad_…` hint counts as job-based: it feeds suggestions only where *Use stored job signals for matching* is on, and a requirement alone stays weak (`MatchingRules`).
- **Display.** The business view of a company gets **Software and systems** (`systems`, at most 50): product, context, source (`job_ad`, `references`, `company`), date and status, with the evidence of each statement; also the hints of its job objects, without naming the job. Shown whether job objects are shown or not.
- **Settings.** The Settings row of a collection has four switches with their help; `PATCH kg/collections/{collection}` takes `jobsSignals`, writes the key only on a change and answers `recheckJobs: true` where the job reading of the collection changed without changing the extraction identity. `status.collections[].jobSignals` and `GET kg/collections` (`jobsSignals`) show it.

**Targeted re-check instead of a global re-extraction.**

- `SolrDoc.inputHash` gets a component only for job pages and only where the job reading differs from what the extractor identity implies (`jobs:signals` for hints without job objects, `jobs:objects` for job objects without hints). Every other page, and every job page under the former behaviour (objects with hints, or nothing), keeps its hash (`JobReadingHashTest`).
- `SyncService` stores the job reading of each collection (`kg_meta.job_modes`: `full`, `objects`, `signals`, `none`). At the start it compares it with the current settings; a change gives the event `job_reading_changed` (the collections) and `kg_meta.job_recheck`. A graph without the key (0.10.1 and before) is compared with what those versions read (`full` where job objects were on, else `none`); an empty graph only records the current reading.
- The reconciler then runs its normal pass with `jobRecheck` (`sync.reconcile.jobRecheck` of `kg/status`, shown in the diagnosis): it extracts again the documents of those collections whose input hash changed, which are their job pages only; the marker is removed when the pass completes, a re-extraction of everything replaces it. No other page, no PDF, no download, no crawl, no global re-extraction.
- **First start of 0.10.2 on an existing graph.** Collections without job objects read hints from then on (`none` → `signals`): one targeted pass over their pages, extracting their job pages. A collection with job objects keeps its reading (`full`). A stored `jobs.signals.<collection>=false` keeps it off. The three former switches are not changed.

**Limits.** The products a hint can name come from the versioned product vocabulary (nine products); a new product changes the global identity and is not part of this release. The LLM tier adds no hints of advertisements while job objects are off. The pass reads every document of a changed collection from the index to compare its hash, and extracts only the job pages.

### 29.4 Tests

- Java: `ServicePricesTest` (6: the count of a group equals its current list, for every group; no name, category or vocabulary needed; a viewer sees only its collections; stable paging; facets for the selects carry the profiles and no counts), `JobAdHintsTest` (7: employer without job objects, unchanged with job objects, off, intermediary/undeclared/ambiguous, careers page, posting semantics, policy keys and identity), `CompanySystemsTest` (2: systems with and without shown job objects), `JobReadingHashTest` (2), `SyncServiceTest` (+2: a changed job reading extracts only the job pages again; a graph of 0.10.1 reads the hints of its job pages once and records `job_reading_changed`), `MatchingTest` (+3: `job_ad` hints are job-based and follow the matching policy; a requirement stays weak and client work never matches; a company's own competence statement is no requirement), `KgCollectionSettingsTest` (+1: on by default, switched off explicitly, `recheckJobs` without re-extraction).
- Browser (EN/DE, 390 and 1280 pixels): `knowledge-services-prices-ui-test.mjs` (category notes for error, no profile and none found; collection switch; price links, status, paging), `knowledge-jobs-systems-ui-test.mjs` (four switches with their help, only the changed one sent, the targeted re-check reported and shown, the overview's job cell, Software and systems with context and source, no jobs section where job objects are not shown).


## 30. The positions of a careers page, departments and the status "confirmed" (0.10.5)

A production finding of 0.10.4 (owner screenshots of a company graph): the same open position appeared as two job nodes with
cut names, a benefits page of the careers section was the evidence of a job, and the company was "member of" a node named
like its purchasing department, shown as *confirmed*.

### 30.1 Causes (reproduced with neutral texts)

- **Menu entries became jobs.** Every page whose address names a careers section (`/jobs-karriere/…`, `/karriere/…`) is a
  careers page by the business rules, and on a careers page every title with a gender marker ("SAP-Berater (m/w/d)") was a
  job of that page. A site repeats the menu of its open positions on every page of the section, so the benefits page, the
  team page and every other page had all open positions as jobs, with the general text after the menu as their evidence.
- **One position, several nodes.** Page texts carry no line breaks, so the start of a title reached back into the words
  before it ("Jobs & Karriere Stellenangebote SAP-Berater (m/w/d)", "Benefits Team Kultur SAP-Berater (m/w/d)"); the job key
  held the URL and the character offset of the title. The menu entry and the heading of the advertisement were two jobs of
  the page, and a menu that grew by one position gave every position below it a new identity.
- **A department as organisation.** "Als Mitglied des Einkaufs- und Logistikteams …" matched the membership marker
  "Mitglied des …", and the hyphenated word was taken as the name of an organisation (`member_of` → "Einkaufs-").
- **The LLM tier** received the menu jobs of the rules as known entities and could name the same menu entries itself.

### 30.2 What the rules read now

- **The positions a page advertises** (`BusinessRules.postings`). On a *detail page*, whose page title or last path segment
  names a title, only the titles it names are jobs, cut to the named words ("SAP-Berater (m/w/d)"), once each. On any other
  careers page a title is a position if its sentence announces it ("Wir suchen …", "Zur Verstärkung …"), if its terms follow it
  ("in Vollzeit", "ab sofort", "zum 01.08.2027"), or if its own text follows it (tasks, profile, requirements) and it is no
  entry of a list of titles (titles next to each other with a few words between them: a menu or teaser links). The heading of
  an advertisement after the menu belongs to the advertisement, not to the menu. A page with several complete
  advertisements keeps each of them; a title repeated on the page (menu and heading) is one job, read from its advertisement.
- **Identity.** The posting key of a job is the URL of its page and its normalised title, no longer the offset of the title:
  a changed menu keeps the job; a different position on its own page stays a different job.
- **Hints of advertisements** (29.3) are read from the text of the positions only, not from the general text after a menu.
- **The LLM tier** (`ExtractContext.withJobTitles`, `LlmExtractor.apply`) drops a job whose title, or the end of a title, the
  rules see on that page only as a menu entry or teaser link; its claims go with it. A page without such titles, and the
  position of a detail page, keep the model's job as before.
- **Departments.** A membership whose object is a department or team ("Einkauf", "Vertrieb", "Geschäftsführung", "…abteilung",
  "…team", hyphenated or not) is no membership; an association, a chamber or a cooperative stays one.

### 30.3 The existing graph: a targeted re-check

The relation re-check of 0.10.3/0.10.4 gets the rules version **3** (`kg_meta.relation_recheck_rules`). At the first start of
0.10.5 the active pages processed before it are extracted again once, at low priority (event `relation_rules_recheck`): those
that carry, in the rule tier, a relation, a job or a hint read from the text of an advertisement (`section` `job_ad_…`), and
those with a job of the LLM tier. Tiers 1 and 2 only: no other page, no crawl, no LLM request, no global re-extraction. The new
tier-2 evidence of a page replaces its old one:

- The menu jobs of the benefits and team pages and the department memberships lose their evidence.
- The position of a detail page keeps its job with every other statement of it (salary, employment type, application route,
  employer, software hints of the advertisement), and the page keeps its other statements (partners, the operator of its
  JSON-LD) under the same identifiers (`SyncServiceTest.theRecheckKeepsAPositionWithItsEvidenceAndHints`).
- A job keeps its node where its rule reference on the page is unchanged. A position whose earlier title had absorbed menu
  words gets one node with the cut title and the evidence of its page; the nodes with the absorbed names leave the current
  graph unless the LLM tier also names them.
- **Restart-safe:** the check keeps its start (`kg_meta.relation_recheck_since`); after a stop or a crash only the pages not
  yet extracted since then are named again, none twice; an empty selection at a later start ends it for good
  (`relation_recheck_done`; `SyncServiceTest.anInterruptedRecheckGoesOnWhereItStopped`).

**The LLM tier: counted, not changed.** Its evidence stays until the LLM tier reads the page again. For each re-checked page the
re-check classifies the jobs of the LLM tier with the rules of the page text (the same rule that now keeps new menu jobs out of
the LLM tier, `ExtractContext.noPosting`) and adds up `kg_meta.relation_recheck_llm_jobs`, shown as
`sync.relationRecheckLlmJobs` of `kg/status`:

| Field | Meaning |
| --- | --- |
| `pages` | re-checked pages with jobs of the LLM tier |
| `menuJobs`, `menuEvidence` | jobs (and their tier-3 evidence rows) the page has only as a menu entry or teaser link: **wrong** |
| `positionEvidence` | tier-3 evidence of a position the page advertises: kept |
| `otherEvidence` | tier-3 evidence of a job the rules see no title of (no gender marker): kept, not judged |

A new version of the rules starts the count again; a restart keeps it and counts no page twice
(`SyncServiceTest.theRecheckCountsTheJobsOfTheLlmTierWithoutChangingThem`).

**Proposal for the wrong LLM evidence (owner decision after the count):**

1. *Recommended:* remove exactly the tier-3 evidence the re-check counted as `menuEvidence`, deterministically with the same
   rule, as a versioned one-time step of a later release: no model request, no GPU load, every other tier-3 evidence (positions,
   other jobs) stays; a job node without evidence leaves the current graph.
2. *Not recommended:* let the LLM tier read the affected careers pages again. It replaces the evidence, but its requests miss
   the cache where the known entities of a page changed, and it adds GPU load (a separate operational finding).
3. *Leave them:* LLM-only facts are never shown as extracted facts (quality uncertain), but the nodes stay visible.

A job of the LLM tier with a different name than the rule job of the same page is a node of its own; the count shows such
positions under `positionEvidence`, and option 1 leaves them alone.

### 30.4 The status "Extracted from source" (formerly "confirmed")

The business graph and the service lists show a statement as `status: confirmed` when its quality is `supported` and its
confidence reaches `BusinessView.CONFIRMED` (0.6). A rule relation of one current page has 0.65. It means "stated on a current
page of the source and read by a rule or from structured data", not "checked by a person" or "stated by several sources".
Owner decision (13.10.2026): the label is **Extracted from source** (*Aus Quelle extrahiert*); its tooltip and the legend of the
network say that no human review is implied. The confidence and the number of sources stay separate next to it (list,
details, the line of a phone). The API keeps the value `confirmed`.

### 30.5 The owner measurement of 0.10.4 and what followed from it

A read-only measurement of production (13.10.2026, copy of the graph opened read-only) found, for one consultancy, 13 job nodes
named "Freiberufliche SAP Senior Berater (m/w/d)" (one of them "e — …", one in capitals) from three pages: the advertisement
of the position "… (m/w/d) Finance" and two pages of the careers section (culture, benefits). Their 34 statements were all of
the rule tier (tier 2); none had LLM evidence. Besides the menu jobs of 30.1 it showed four more errors of the rules, each
reproduced with a neutral reconstruction of the pages and fixed:

- **The lead word cut the title.** "Stellenangebot" of the lead words (`JOB_LEAD`) matched the start of "Stellenangebote — X",
  the title became "e — X". Lead words are whole words now ("Stellenangebot(e)", "Stellenanzeige(n)", also with ":", "|" or a
  dash after them).
- **The specialisation was cut off.** A title ended at its gender marker, so "… (m/w/d) Finance" and "… (m/w/d) SAC" were the
  same title. Up to three capitalised words or acronyms after the marker that end the sentence or the list entry belong to the
  title (`TITLE_SUFFIX`: "Finance", "SAC", "S/4HANA", "FI/CO"); the terms of the position, a place of work and the words of a
  menu or a sentence do not ("in Vollzeit", "Jetzt bewerben", "Standort Köln", "Skip to content"). A detail page names its
  position with the specialisation; its path may shorten the words ("freiberufl-sap-senior-berater-finance", at least five
  letters of a word, two words or more), its page title names them exactly.
- **"Internationale" was an internship.** The term "intern" matched as the start of a longer word ("Internationale
  Rechnungslegung", "Internet", "interne Schulungen"). Only the words "intern(s)" and "internship(s)" name one now. The title
  itself states its kind of employment ("Freiberufliche …", "Werkstudent …", "Praktikantin …") in a form of the term, never in a
  compound ("Ausbildungsleiter" is no apprenticeship); the terms after the title are read as before.
- **A person's membership was the organisation's.** "… durch den Einsatz eines [company]-Beraters als Mitglied des Einkaufs"
  (of a client, on a reference page) made the consultancy "member of" an organisation "Einkaufs". A membership marker after a
  person in a role in the same sentence (consultant, employee, head of …, managing director, owner, founder, contact, Herr,
  Frau) is that person's membership, not the page's; not after a number of people ("Mit über 200 Mitarbeitern ist die … GmbH
  Mitglied …") and not where "wir" is the member ("Als Hersteller von Leitern sind wir Mitglied …").
- The evidence "bei uns … gut bezahlt" of the benefits page as the employer of a job, and the application form of the culture
  page as its route, go with the menu jobs of these pages (30.2).

Expected result per page, before (0.10.4) and after (0.10.5), neutral reconstruction of the measured pages:

| Page | 0.10.4 | 0.10.5 |
| --- | --- | --- |
| Advertisement "… (m/w/d) Finance" (detail page) | 4 jobs: "e — Freiberufliche SAP Senior Berater (m/w/d)", the two menu entries, the heading, none with "Finance"; `employment_type` internship at "Internationale" | 1 job "Freiberufliche SAP Senior Berater (m/w/d) Finance": name at its heading, `employment_type` freelance at "Freiberufliche" of the title, application route at "Bewerbungsformular" of its text |
| Advertisement "… (m/w/d) SAC" (detail page) | 4 jobs as above, without "SAC" | 1 job "… (m/w/d) SAC", freelance, its software hint (SAP BW, `required_competence`) and its route |
| Benefits, culture (careers section, menu only) | 2 menu jobs each; employer from "bei uns", route from the form of the page | no job, no employer, no route, no employment type |
| Reference page ("eines …-Beraters als Mitglied des Einkaufs") | `member_of` → "Einkaufs" | no membership |

**Scope.** The measurement also counted *suspects* over the whole copy: 230 job names repeated on three or more pages of one
host and 2,130 `member_of` statements (2,105 objects, many of them fragments). They are not cleaned by pattern: the targeted
re-check of 30.3 extracts tiers 1 and 2 of exactly the pages with jobs, job-ad hints or relations of the rule tier again with
the rules above, so a statement goes only where the page no longer gives it, and a correct membership or position stays. The
129 tier-3 evidence rows of jobs in the copy belong to all hosts, not to this company; the count of 30.3 tells how many of
them are menu entries.

### 30.6 Tests

- Java: `JobAdHintsTest` (+8: menu entries, a benefits page and teaser links of a careers section are no jobs; a position
  belongs to its detail page with its application route and its hints; its identity survives a changed menu, another position
  stays another job; several advertisements on one page stay separate; the advertisement of a consultancy is one job with its
  full title, its own evidence and no internship; positions that differ by their specialisation stay separate; benefits and
  culture pages give no job, employer or route; "Internationale", "Internet", "interne" name no internship),
  `BusinessExtractionTest` (+2: a department is no membership, an association and a cooperative stay; a person's membership is
  no membership of the organisation, the organisation's own stays), `LlmExtractorTest` (+1: a model job that is only a menu entry of the
  page is dropped with its claims; without page titles and on the detail page it stays), `SyncServiceTest` (+4: only the
  pages with jobs of the rule tier are extracted again, once; a position keeps all of its evidence and identifiers and the
  benefits page only loses its menu jobs; an interrupted check goes on where it stopped, each page once; the jobs of the LLM
  tier are counted, not changed).
- Browser (`knowledge-ui-test.mjs`, EN/DE, five widths): a fact of the network shows *Extracted from source*, its tooltip and
  the legend say "no human review", confidence and number of sources stay in their own columns, no status "confirmed".

## 31. The employer, the employment type and the JSON-LD posting of an advertisement (relation rules 4)

Owner findings of the 0.10.5 rollout (13.10.2026): after a targeted crawl of six advertisements of a consultancy, five pages
were indexed and gave their position with its full title and its `required_competence` hints, but no employer and no
employment type, although their imprint names the operator. The sixth page declares `noindex,nofollow`; YaCy refused to index
it and it stays outside the graph (the knowledge graph reads only indexed documents). The page of a freelance position had,
besides its job, a second job node from its JSON-LD, named after the organisation, with `FULL_TIME` and `datePosted`
2023-08-30. (The rollout report's note that employer and place "referred to the posting itself" did not hold: the raw JSON-LD,
read later, has no `@id` at all — 31.3.)

### 31.1 Causes (reproduced with neutral texts)

- **The employer declaration was too narrow and read the navigation.** A rule job gets the page's operator as its employer
  only where the advertisement declares it. The list of declarations was "wir suchen", "bei uns", "unser Team", "we are
  hiring", "join our team": the test phrase "Zur Verstärkung unseres Teams suchen wir Dich" matched none of them (a neutral
  phrase of the tests, **not** the wording of the reported pages, which say "Bei uns wirst Du." and "Bei uns gestaltest Du …"
  and are covered by "bei uns": correction in 31.6). Where one matched, the text
  read ran from the title to the next title or 6,000 characters, so into the footer ("Jobs bei uns"), and the text before the
  first title of a page (on a detail page: the navigation with "Unser Team", "Karriere bei uns") counted as well. "Wir" was
  given to the page's subject even when that was another organisation of its structured data (a client, a portal).
- **The employment type was read without sections.** The 400 characters after a title gave a type for every term in them:
  requirements ("Erfahrung als Freelancer", "abgeschlossene Ausbildung"), compounds ("Praktikumsbetreuer"), and the positions
  next to a short advertisement ("Weitere Stellen: Praktikum Marketing, Werkstudent Vertrieb"). A type the offer states further
  down ("Wir bieten: eine unbefristete Stelle in Vollzeit") was missed. A default was never set, and none is now.
- **JSON-LD references ignored the kind of a node** (a general weakness of the parser, found with the shapes tried before the raw
  data were read; **not** the cause of the reported page, whose JSON-LD has no `@id`). A bare `{"@id": …}` reference resolved to whichever node of that id came
  first, of any kind, and a node met with a known id returned the first node's mention. A posting and an organisation that share
  an `@id` (an error of the source) lost one of them; the employer or place of a posting that names the posting's own `@id`
  resolved to the posting itself and was dropped without a trace.
- **A title that names the organisation became a job's name.** `title` (or `name`) of a `JobPosting` that is just the name of
  the organisation, its site or its domain made a job of that name.
- **The JSON-LD posting of a detail page and the job of its advertisement were two nodes.** Their keys never matched (the
  rule job is keyed by the page and its title, the posting by its employer, title and `@id`), so the page had two jobs and a
  contradiction between them (FULL_TIME / freelance) did not show on one position.

### 31.2 What is read now

- **The advertisement's own text** (`BusinessRules.adEnd`): from the sentence that announces the title to the first end of the
  advertisement after its first section heading: the application ("Bewirb Dich", "Jetzt bewerben", the form), the positions
  next to it ("Weitere Stellen", "Ähnliche Jobs") or the footer (imprint, privacy, copyright); else the end of its window as
  before. An application button above the tasks ends nothing; the sentence of the application call still belongs to the
  advertisement for its employer ("Bewirb Dich jetzt bei uns!"), the footer after it does not.
- **The employer** (`BusinessRules.employer`): the page's operator where the advertisement's own text declares it ("wir
  suchen", "suchen wir", "wir stellen ein", "bei uns", the team in any form (examples of the tests): "Zur Verstärkung unseres Teams", "Verstärke unser
  Team", "Werde Teil unseres Teams", "Komm in unser Team", "we are hiring", "we are looking for", "join our team"), or where the
  lead of a list announces its positions with a sentence ("Wir suchen ab sofort: …"); a navigation label in the lead
  ("Unser Team", "Karriere bei uns") declares nothing, and the lead of a detail page is its menu. The operator is the operator
  the imprint names, the declared operator of the structured data, an organisation of the structured data on the page's own
  domain (`url` or `sameAs`; with a legal form the declared operator, without one the domain's operator by its short name), or
  the domain's unnamed operator (joined in the graph only to the domain's one declared operator); never a name
  derived from the domain, never another organisation of the page.
- **Intermediaries and client projects.** Where the advertisement says that the position is a client's ("Für unseren
  Kunden …", "Für unseren Mandanten …", "Im Auftrag unseres Mandanten …", "on behalf of our client"), the operator is the
  `recruiting_organization` with that phrase as its evidence and the employer stays unknown (the job stays `unresolved`). A
  consultant who works in the projects of the operator's clients ("Projekte bei unseren Kunden") holds the operator's own
  position.
- **The employment type** (`BusinessRules.statedTypes`): from the title as before, or from the advertisement's own text,
  wherever it stands in it, except in its requirements (from their heading — "Dein Profil", "Anforderungen", "Was du
  mitbringst" … — to the next offer or tasks heading) and except a requirement in a sentence ("abgeschlossene Ausbildung",
  "Erfahrung als Freelancer"); a term counts as a form of itself or as a compound of a position ("Teilzeitstelle",
  "Ausbildungsplatz", "Werkstudentenjob"), not as another compound. Without such a statement the type stays unknown.
- **JSON-LD references by kind** (`JsonLdExtractor.node`): a reference resolves to the node of its id that has the kind the
  reference expects (an employer: an organisation or facility; a place: a site or facility; else its declared type), a node is
  the same node again only with the same id *and* kind, and a reference whose id names only nodes of another kind (the posting
  itself, a page) resolves to nothing. A posting and an organisation that share an id are two nodes again.
- **A title that names the organisation** (`JsonLdExtractor`): a `JobPosting` whose `title` (or `name`) is the name, legal name
  or alternate name of an organisation or site of the page's JSON-LD, or the name of the site's domain, has no name: no
  position is made of it, and its other statements (employment type, dates, employer, salary) stay with their tier-1
  provenance.
- **The JSON-LD posting of the page of one position is that position** (`BusinessRules.structuredPosting`): where a detail page
  has one advertisement and its JSON-LD one `JobPosting` that names no title of its own or the same title, both get the key of
  the advertisement, so they are one job. Each statement keeps its tier and evidence; neither source decides for the other: a
  page whose JSON-LD says `FULL_TIME` and whose title says "Freiberufliche …" has both employment types on the one position,
  each with its own source. A `JobPosting` of another position stays a job of its own; several postings are not guessed.
  **Only without contradicting identity** (`BusinessRules.contradicts`): a posting that declares another page (its `url`, or
  an `@id` that is the address of another page; host without "www.", path without a final "/" and query compared, scheme and
  fragment aside), another place of work than the advertisement's ("Standort: …"), or another employer — an organisation that
  is not the operator where the advertisement declares the operator, the operator where the advertisement recruits for a
  client, an intermediary's posting where the advertisement is the operator's own — stays a job of its own with all of its
  statements. What either source leaves open contradicts nothing; the client as employer of an intermediary's advertisement
  agrees with it.

### 31.3 The reported JSON-LD posting: source error and contradicting statements

**The raw data (owner, 13.10.2026), confirmed:** two separate JSON-LD blocks and no `@id` reference anywhere.

1. A `JobPosting` with the company's name as its `title`, `employmentType` `FULL_TIME`, `datePosted` 2023-08-30 (with a time
   and the offset `+0000`) and `validThrough` `null`; its `hiringOrganization` an inline `Organization` with the company's
   short name and `sameAs` of the site's own domain; its `jobLocation` an inline `Place` with a `PostalAddress`; no `url` of
   its own. The date gives `date_posted` 2023-08-30; the `null` end gives no `valid_through`.
2. A `WebSite` with the company's full name and the site's own domain.

The HTML title names the freelance Finance position.

**Classification:**

| Finding | Class | 0.10.5 | Rules 4 |
| --- | --- | --- | --- |
| `title` = the company's name | **source error**: a title that names no position | a job named like the company | no name from it; the posting joins the advertisement's job, named by the advertisement |
| `FULL_TIME` (JSON-LD) against "Freiberufliche …" (HTML title and advertisement) | **contradicting statements** of the source | two job nodes, one type each | one job with `full_time` (tier 1, `jsonld:0/employmentType`) and `freelance` (tier 2, `text:…` of the heading), side by side; neither is chosen |
| `datePosted` 2023-08-30 | a statement of the source | kept | kept (tier 1). An old posting date **does not end a position**: the job status is `ended` only by an explicit end (`job_status`), `deadline_passed` only by a passed `valid_through`, else open or not reconfirmed (`BusinessView`); the date is shown, not judged |
| Employer: inline short name with `sameAs` of the own domain; full name in the `WebSite` and the imprint | no contradiction: the same company | — | the short name on its own domain is the domain's operator (`domain_operator`, joined to the declared operator of the imprint); the employer evidence of both sources stays |
| Place: inline `Place` with a postal address | statement of the source | kept on the JSON-LD job | kept on the one job (tier 1) |

**Not the case here:** no `@id` collision and no self-reference — the page has no `@id`. The type-aware resolution of `@id`
references (31.1) stays as a general fix of the parser; it does not change this page.

**Checked against the PR before this finding (`76472af`):** the real structure still gave **two** jobs there. The short name has
no legal form and links its own domain only by `sameAs` (not `url`), so the identity check of the join (31.2) took it for
another employer than the operator the advertisement declares. Now an organisation of the JSON-LD that links the page's own
registrable domain by `url` **or** `sameAs` is the declared operator with a legal form and the domain's operator by its short
name without one; a short name that links another domain, or none, secures no identity and keeps the posting a job of its own
(`JobEvidenceTest.theReportedPageIsOneJobWithBothSourcesOfItsEmploymentType`, `aShortNameIsTheOperatorOnlyByTheSitesOwnDomain`).
On a real store the short name and the full name of the imprint end on **one** organisation, the job's one employer; a short
name of another domain stays an organisation of its own, and so does its posting (`ReportedPostingIdentityTest`).

### 31.4 The existing graph: a targeted re-check (rules 4)

The re-check of 30.3 gets the rules version **4**. A graph whose check with the rules 3 is complete
(`kg_meta.relation_recheck_rules` = 3 and `relation_recheck_done` set; 0.10.5 writes that key only when it starts again after
its check ended, so an installation of 0.10.5 not restarted since has no such key and gets the full check below) is checked once more,
but only on its pages with a job of any tier (now also tier 1, the JSON-LD postings) or a hint read from an advertisement
(tiers 1 and 2): `kg_meta.relation_recheck_scope` = `jobs`. A page with relations only is not extracted again. A graph whose
check of the rules 3 did not complete, or that was checked with older rules, gets the full check once (relations, jobs and
hints). Tiers 1 and 2 only, at low priority, restart-safe as in 30.3: no crawl, no LLM request, no global re-extraction (the
extractor identity stays, so no input hash changes); the evidence of the LLM tier stays and its job evidence is counted again
(`sync.relationRecheckLlmJobs`); the count of the check before stays readable as `sync.relationRecheckLlmJobsBefore` (with its
`rules`). A page that declares `noindex` is not in the index and therefore neither checked nor extracted.

**Readable without its event** (status `sync.relationRecheckInfo`): `rules`, `scope` (`jobs` or `all`), `pages` (the pages the
check selected when it started, `kg_meta.relation_recheck_pages`), `since`, `done`, and live `open` (pages of the check not yet
extracted in this process), `failed` and `failedPages` (`kg_meta.relation_recheck_failed`, at most 200 IDs, the first 20 in the
status).

**The end of the check, in the running process** (review of this PR): a page leaves the check when it is published with the
current rules or is no longer active (deleted, out of scope). The publication of the last page of the check and the end of the
check are **one write**: inside it the selection of 30.3 is run again, and only if no page of it is left is
`kg_meta.relation_recheck_done` written (event `relation_rules_recheck_done`) and the status shows `done` at once — no restart
is needed, a later start finds the end and names nothing again. A refused write (growth paused, storage limits) publishes
nothing and ends nothing: the page and the check stay open and the next admitted write completes both. A page whose extraction
fails for good (`doc_failed`) stays in the check and in its list of failed pages: the check stays open (`done` null) until the
page is published with its next change or at the next start, which names it again; then it leaves the list and the check can
end. (Production showed that the selection could grow while the check ran, so the last page did not end it; since the fix of
31.6 the selection is fixed at the start, and the running process also ends a check whose selection empties without a page of
it.) The event `relation_rules_recheck` is
written once as before, but the bounded event log can lose it: a job whose key changes (an employer found or no longer found,
a posting joined to its advertisement) is resolved to a new node, and the old node keeps the page-local key of the mention, so
the resolver can report `identity_conflict` (`mention contradicts entity`) when the page is extracted again.

Expected changes on a re-checked page:

- a position whose advertisement declares its employer gets `hiring_organization` → the operator (new key, new node); one whose
  employer came only from navigation or footer loses it (`unresolved`); an intermediary's position gets
  `recruiting_organization`;
- employment types from requirements, compounds or neighbouring positions go; a type the offer states is added;
- the JSON-LD job of a detail page that was named after the organisation loses that name and joins the advertisement's job; its
  statements stay with their tier-1 evidence; the old node leaves the current graph;
- relations, the operator, partners and every other statement of the page stay; the 40 menu evidence rows of the LLM tier
  counted by the check of the rules 3 are neither changed nor deleted.

**Verification plan (read-only, production):**

1. Before the upgrade: ~~read the raw `ld_json_txt` of the freelance position's page~~ — done (31.3: source title and
   contradicting employment types). Record `sync.relationRecheckLlmJobs` and the number of job nodes, `hiring_organization`
   and `employment_type` statements of the host.
2. After the upgrade: `sync.relationRecheckInfo` (`rules` 4, `scope` `jobs` — or `all` where `relation_recheck_done` was missing
   before the upgrade, see [the 0.10.6 upgrade notes](SCOUTRO_RELEASE_0.10.6.md#upgrade-from-the-installed-0105) — `pages`), `open` and `sync.relationRecheck` down to 0,
   then `done` set **without a restart** (`failed` 0; otherwise `failedPages` names the pages that keep it open, with their
   `doc_failed` events), `relationRecheckLlmJobsBefore` equal to the recorded count, no LLM request, no crawl.
3. For each of the five indexed advertisements: one job with its full title, `hiring_organization` → the imprint's operator
   with an excerpt from the advertisement (not from menu or footer), `employment_type` only where the advertisement states it.
   For the freelance position: **one** job named by the advertisement, `freelance` (rule) and `full_time` (JSON-LD), each with
   its own evidence; `hiring_organization` from both sources (the short name of the JSON-LD and the imprint's full name, one
   operator); its place (JSON-LD); `date_posted` 2023-08-30 and the job not shown as ended; no job named like the company.
4. The `noindex` page: still not in the index, no job.
5. Count `identity_conflict` events of the check separately; they are expected for changed job keys.

### 31.5 Tests

- Java: `JobEvidenceTest` (new, 15: the advertisement that declares its employer has the operator as it, with five forms of the
  declaration and its evidence in the advertisement; domain and navigation declare no employer, on a detail page and a list;
  an intermediary recruits for its client, a project at a client stays the operator's position; another organisation of the
  page is not who "wir" is; the hints of an advertisement follow the same declaration; a type the advertisement does not
  state stays unknown (requirements, compounds, neighbouring positions, JSON-LD without a type); a stated type is read
  wherever it stands; JSON-LD nodes with one id keep their kinds; a reference to the posting itself is no employer and no
  place; a title that names the organisation is no position title; the JSON-LD posting of the page is its advertisement; a
  posting of another position stays its own job; +1 after review: a posting that contradicts the advertisement — another page
  by `url` or `@id`, another employer, the operator where the advertisement recruits for a client, another place — stays its
  own job, agreeing or missing statements join it; +2 with the raw data: the reported page as it is — two blocks, no `@id`,
  title = the company's name, `FULL_TIME`, inline short-name employer with `sameAs` of the own domain, inline place, the site
  with the full name — is one job named by the advertisement with both employment types and their locators, the employer
  evidence of both sources (both the operator), its place and date, no end; a short name is the operator only by the site's
  own domain), `ReportedPostingIdentityTest` (new, 2: the reported page through identity resolution on a store — one job with
  `freelance` and `full_time`, its date, no status, one place, and one organisation with the short and the full name as its
  employer; a short name of another domain stays apart with its own posting), `SyncServiceTest` (+1: a graph checked with the rules 3 checks only its job
  pages once, keeps the count before, is readable without its event and ends **in the running process**, a restart keeps the
  end; an incomplete check of the rules 3 gets the full check; +3 after review: a page that fails for good keeps the check open
  across a restart until its next publication ends it in that process; a refused write of the last page leaves the page and
  the check open, the next admitted write publishes it and ends the check; a page that is deleted leaves the check and ends it;
  the resumed run after a crash ends the check itself).

### 31.6 The 0.10.6 rollout: the end of the check (C29) and the employers of the five advertisements (C32)

**C29 — the end of the check was never written.** Production (operator's acceptance, 2026-10-13): 0.10.6 healthy without a
restart; the full check (scope `all`: the check of the rules 3 was not recorded as complete) published all its pages
(`published` 10,348 in the process, `failedDocs` 0, queue 0), and for hours `sync.relationRecheckInfo` showed `open` 0, `failed`
0 and `done` null.

*Cause (reproduced, `RelationRecheckEndTest`).* The check is the selection of 30.3 (active pages processed before `since` with a
relation of the rule tier, a job of any tier or an advertisement hint), run again inside the write of its last page (31.4). The
LLM tier publishes on pages without processing them: `Publisher.applyLlm` writes tier-3 evidence and leaves `processed_at`. A
job it reads on an old page while the check runs puts that page into the selection, although the check never named it. When
the last page of the check was published the selection still named that page, so the end was not written; that page is no page
of the check, so its own later extraction called no end either, and a selection emptied that way went unnoticed until a restart
(the replica: `done` null after the check, still null once the extra page is extracted and an hour later; set by a restart).
The production run had the LLM tier beside it (C18). Which pages joined there is not known: the read-only query below names
them on a consistent copy.

*Fix (no rules version change, no new check).*

- The selection counts only evidence published before the check started (`kg_evidence.observed_at` < `since`; `observed_at` is
  the time of the evidence's publication). It is fixed at the start and only shrinks: a page leaves it when it is processed or
  no longer active, none joins. The same selection decides at the start, at the last page and after a restart.
- The running process ends a check whose pages are all gone and none failed once its selection is empty, also without a
  publication of a page of the check (`SyncService.recheckEnd`, looked at every 5 minutes while that is so): one write with the
  end and its event, once; a refused write leaves the check open for the next look; nothing is extracted for it.
- A check whose pages were all published while its end is missing (the graph 0.10.6 leaves) ends at the first start of the
  fixed version without extracting any page again; `since`, `scope` and `pages` stay, the end and its event are written once.
- Unchanged: a refused write publishes and ends nothing; a page that fails for good keeps the check open across restarts; an
  interrupted check resumes with its remaining pages.

*Read-only query* (consistent copy; `:since` = `kg_meta.relation_recheck_since`, `:scope` = `all` or `jobs`): the pages the
selection of 0.10.6 names and the fixed selection does not — the pages that held the end open.

```sql
WITH sel(doc_rowid, observed_at) AS (
  SELECT e.doc_rowid, e.observed_at FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid
    JOIN kg_vocab v ON v.term_id = s.pred JOIN kg_entity n ON n.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = n.type
  WHERE (e.tier = 2 AND :scope = 'all' AND v.name IN ('carrier_of', 'subsidiary_of', 'parent_of', 'brand_of',
         'cooperation_with', 'partner_of', 'customer_of', 'reference_for', 'member_of', 'association_member', 'certified_by',
         'funded_by', 'sponsored_by', 'supplier_of', 'service_provider_for'))
     OR t.name = 'job'
     OR (e.tier IN (1, 2) AND v.name = 'system_signal' AND s.obj_val LIKE '%"section":"job\_ad\_%' ESCAPE '\'))
SELECT d.doc_id, d.url FROM kg_doc d WHERE d.state = 1 AND d.processed_at < :since
  AND EXISTS (SELECT 1 FROM sel WHERE sel.doc_rowid = d.doc_rowid)
  AND NOT EXISTS (SELECT 1 FROM sel WHERE sel.doc_rowid = d.doc_rowid AND sel.observed_at < :since)
LIMIT 50;
```

**C32 — the employers of the five advertisements.** The rollout reported their wording: "Bei uns wirst Du." (a heading) and
"Bei uns gestaltest Du …". The sentence "Zur Verstärkung unseres Teams suchen wir Dich" of 31.1, 31.5 and the 0.10.6 release
notes is a neutral phrase of the tests and was never the wording of these pages (corrected there). The five pages state no
employment type ("Freiberuflich" stands only in the menu), which is correct, and their jobs have no employer
(`employer_assignment` `unresolved`).

*Diagnosis with neutral replicas* (`JobEvidenceTest`, the tests of the reported wording):

- **Language pattern: not the cause.** "bei uns" has been a declaration of the rules since 0.10.5. Both reported phrases make
  the operator the employer, with the phrase as evidence, where they stand in the advertisement's own text and the operator's
  identity is known.
- **Section boundary: possible.** The employer is read from the sentence of the title to the end of the advertisement (31.2).
  A phrase before that sentence (a heading "Bei uns wirst Du." right above the title) or after the application call is not
  read. 0.10.5 read from the title to the next title or 6,000 characters, without an operator test, and found no employer
  either: both phrases lie outside the window of the title occurrence the rules chose.
- **Operator identity: possible.** Where the page names no operator (no imprint, no organisation of its structured data on the
  own domain) and its one organisation is another one's, no employer is set and nothing is guessed.

Which of the two applies is open. Missing detail data (operator report not available here): the positions in the stored text
(`text_t`) of the title occurrences with their gender marker, of the two phrases, of the first section heading and of the first
application call or footer word after the title; the locator of the job's `name` evidence (which title occurrence the rules
chose); and the page's operator evidence (an organisation with `site_operator` or `domain_operator`, its imprint, its JSON-LD
blocks). Reading a heading above the title as part of the advertisement would be a change of the rules with a new version and
its own check; none is made here.

*Update (2026-10-14, measured by OpenCode in the indexed text of one of the five pages; section 32).* Neither assumption above
holds. The phrases stand inside the advertisement: its heading at 6,426, "Bei uns wirst Du." at 7,075, "Bei uns gestaltest
Du …" at 7,141, a further mention of the company at 9,338, the application call at 11,328. The company is also named inside the
advertisement, not only outside it. The rules chose the title line at 18 (`text:18+29`) as the advertisement. Its window ends
at the menu that follows, so it held none of these phrases. The cause is the choice of the title occurrence, fixed by the
rules 5 (32). Whether the extraction has a secured operator identity for these pages is a separate question (32.3).

*Two more findings of the replicas, not seen on the reported pages* (fixed by the rules 5, section 32): where no title occurrence has its own text (no known section heading after it), the rules can choose an occurrence
whose window holds the navigation, whose "Unser Team" then declares the employer; and a category navigation ("Festanstellung",
"Freiberuflich") right after an advertisement without an application call or requirements becomes its employment type. Both
read navigation, which 31.2 excludes.

*Tests.* `RelationRecheckEndTest` (new, 3, with the real LLM tier and the fake model of `LlmServiceTest`): a job the LLM tier
reads on an old page during the full check does not hold its end open — the check ends with its last page in the running
process, a later extraction of that page and a restart change nothing; a graph whose check published all its pages without
an end (the graph 0.10.6 leaves) ends at the next start without extracting any page, with its start, scope, page count, the
count of the LLM tier and its evidence kept, the end and its event once; a check whose selection empties without a page of it
ends in the running process, once, without a restart. Red on 0.10.6 (`3c28870`): `done` null with `open` 0, `failed` 0 (as in
production); one page extracted again at the next start; `done` null without the end of 31.6 in the running process.
`JobEvidenceTest` (+3): the reported wording declares the operator in the advertisement's own text (three forms, the evidence
at the phrase, the imprint's operator); without the operator's identity it declares nothing (`unresolved`); the reported
advertisements state no employment type and their menu ("Festanstellung", "Freiberuflich", another position's title) gives
none.

## 32. The advertisement of a position's page (relation rules 5)

### 32.1 Finding and cause

**Finding** (C32 of the 0.10.6 rollout). OpenCode measured the indexed text (`text_t`) of one of the five pages on 2026-10-14:

| offset | content |
| --- | --- |
| 18 | the position in the page's title line ("Stellenangebote — …"); the job's `name` evidence is `text:18+29` |
| ~49 | the site's navigation |
| 1,798 | the menu of the positions |
| 6,308 | the advertisement's heading (H2) |
| 6,356 | the breadcrumb |
| 6,426 | the advertisement's heading (H1) |
| 7,027 | a sentence naming the company by its short name |
| 7,075 | "Bei uns wirst Du." |
| 7,141 | "Bei uns gestaltest Du …" |
| 9,338 | a further mention of the company in the advertisement's body |
| 11,328 | the application call |
| 11,390 | the footer |

The five jobs (four positions with a specialisation, the freelance one, and one more) had no employer.

**Cause** (reproduced with a neutral replica of this layout, `AdvertisementWindowTest`; red on `03b1a78`).

The rules read the title line at 18 as the advertisement. That occurrence's window runs from 47 to the next title, the first
entry of the menu (1,868 in the replica). Its text ends at 1,817, so it holds none of the phrases from 7,000 on. The bound of
6,000 characters after the title (at most 6,047 here) was not reached. Enlarging it would have changed nothing, because the window
belonged to the wrong occurrence. Two independent reasons led to that choice:

1. **Only 30 title occurrences were read.** `jobTitles` stopped after the 30th occurrence of a gender marker. Five or more
   rounds of a menu of six positions put more than 30 occurrences before the advertisement. Its headings were never read, and
   the title line was the page's only occurrence of the position.
2. **The heading counted as an entry of a list.** With all occurrences read, the breadcrumb right before the H1 (less than 60
   characters away) makes the H1 an entry of a list. Without a known section heading ("Das erwartet Dich" is not one), 0.10.6
   then preferred the occurrence outside a list, which is the title line. The H2, followed by " ** ", lost its specialisation
   and became a second job named by the bare title.

Which of the two applied on the production page depends on the number of gender markers before 6,308, which was not measured.
Both reproduce `text:18+29` without an employer. The replicas also show the two findings of 31.6:

- a menu with "Unser Team" in the title line's window declares the operator the employer (not on the measured page: no
  employer there);
- a category navigation ("Freiberuflich") after an advertisement becomes its employment type.

Before and after, replica of the measured page (with the imprint's operator unless stated):

| case | 0.10.6 (`03b1a78`) | rules 5 |
| --- | --- | --- |
| six menu rounds (43 markers) | 30 read; title 18+29; window 47–1,868; text to 1,817; no employer | 43 read; title 6,426+29; window 6,455–11,415; text to 11,329; employer at 7,075 ("Bei uns") |
| one menu round (13 markers) | title 18+29 and a second job at 6,308+26; no employer | as above |
| no imprint, no JSON-LD | title 18+29; no employer | title 6,426; employer: the domain's unnamed operator, at 7,075 |
| nothing declared | title 18+29; no employer | title 6,426; no employer |
| nothing declared, "Unser Team" in the menu | employer from "Unser Team" at 193 | no employer |

### 32.2 What the rules 5 read

On the page of a position (its page title or path names it):

- **Every occurrence of a title is read.** The bound is 400 occurrences, against pathological pages; a page still has at most 30
  jobs.
- **The advertisement is the occurrence followed by its own text.** Ranking, in order:
  1. sentences of five words or more;
  2. then a known section heading;
  3. then outside a list;
  4. then the longer text.

  A title line, a menu entry, an H2 or a breadcrumb followed only by labels gives way to the next occurrence of the same
  position. The job's evidence starts at the advertisement's heading.
- **A repetition of the title without the specialisation the page names is that position.** Examples: the H2 followed by
  " ** ", or "Bewirb Dich als … (m/w/d) jetzt". It is no second job and does not end the advertisement. Where two
  specialisations share the bare title, nothing is mapped.
- **The advertisement ends at the next title of another position or of a list** (a menu, the neighbouring positions):
  - at most 6,000 characters after its title, as before;
  - before the sentence that announces that title;
  - without the run of navigation labels right before it.

  Employer, intermediary and hints are read up to the application call or the footer, as in 31.2.
- **A label declares nothing.** "Unser Team", "Karriere bei uns" and "Jobs bei uns" in a segment of fewer than four words are
  the labels of a menu or footer. In a sentence ("Bei uns wirst Du.", "Komm in unser Team!") they declare the operator the
  employer, as in 31.2.
- **A company named as the hirer** ("Die Muster Consulting AG sucht …", "Arbeitgeber: Muster Consulting AG"):
  - if its legal name is the operator's, the operator is the employer, with that sentence as evidence;
  - otherwise (a job portal publishes another company's advertisement) the operator is neither employer nor recruiter, even
    where its text says "bei uns". The company named is not created from free text.

On a list of positions (a careers page):

- the lead that announces the positions ("Wir suchen ab sofort:") is read between the last title before the first position
  and that position, which is after the menu (0.10.6 read from the start of the page);
- the text of a position ends before the sentence that announces the next one ("Für unseren Kunden suchen wir: …" belongs to
  the next position).

Unchanged:

- An intermediary's position ("Für unseren Kunden …") gets the operator as `recruiting_organization`, and its employer stays
  unknown.
- The domain, the navigation and the footer give no employer and no employment type.
- A type the advertisement does not state stays unknown.
- The freelance position stays one job with `freelance` (rule, its heading) and `full_time` (JSON-LD), each with its own
  evidence.

### 32.3 The operator's identity: a separate question

The employer an advertisement declares is the page's operator, which comes from one of these sources:

1. the imprint's legal name (`site_operator`);
2. the declared operator of the structured data, or an organisation of it on the page's own domain (the short name with
   `sameAs` of the own domain, 31.3);
3. otherwise the domain's unnamed operator (`domain_operator`), which the graph joins only to the domain's one declared operator.

A foreign organisation as the page's only one gives no operator and no employer. The company's mentions in the advertisement's
text (the short name at 7,027, the name at 9,338) are not one of these sources. Even with its legal form, a name in the body
neither makes the operator nor names it. This is tested in `AdvertisementWindowTest`: with an imprint, with JSON-LD on the own
domain, with no named operator, with a foreign organisation, and with the legal name in the body only.

Which of these the five production pages have was not measured. The read-only query is in 32.5, step 1.

### 32.4 The existing graph: a targeted re-check of the job pages (rules 5)

The rules version becomes **5**. The rules 4 and 5 changed only how jobs are read, so a complete check of the rules 3 or 4 is
followed by a check of the job pages only (scope `jobs`, as in 31.4):

- the pages with a job of any tier or a hint read from an advertisement;
- tiers 1 and 2, at low priority, restart-safe;
- no crawl, no LLM request, no global re-extraction (the extractor identity stays).

**The graph 0.10.6 leaves** (rules 4, scope `all`, all pages published, end missing: C29) starts with the rules 5 in three steps:

1. The check of the rules 4 counts as complete because its selection, fixed by the C29 fix (31.6), is empty. None of its pages
   is extracted again for it.
2. Its record stays readable as `sync.relationRecheckInfoBefore` (`kg_meta.relation_recheck_before`): `rules` 4, `scope`,
   `pages`, `since`, and `done` with `doneAtStart` true. That `done` is the time of the start that recorded it. Its event
   `relation_rules_recheck_done` ("rules 4: … its end written when the rules 5 started") is written once.
3. The check of the rules 5 then selects the job pages (`sync.relationRecheckInfo`: `rules` 5, `scope` `jobs`, `pages`), and its
   end is written in the running process (31.6).

Two separate parts, two commits:

- **The C29 fix** (`03b1a78`, rules 4) ends the check without any extraction and without a new check, both in the running
  process and at the first start.
- **The rules 5** (this change) add the targeted check of the job pages.

The missing end of the rules 4 is neither set by hand nor hidden by a restart. It is recorded as what it was: all pages
extracted, end written at the start of the next rules.

A check of the rules 4 that is *not* complete (pages open or failed) is followed by the full check once (scope `all`), as in 31.4.

Expected changes on a re-checked page:

- **The page of a position read from its title line or its menu:** one job named at the advertisement's heading. It gets the
  employer the advertisement declares (the operator of 32.3, evidence in the advertisement) and the employment type only where
  the advertisement states it.
- **Lost assignments:**
  - an employer that came from a menu label goes;
  - so does the operator as employer of a portal's advertisement that names another company;
  - so does a second job named by the bare title.
- **Other effects:** jobs whose key changes resolve to new nodes, and `identity_conflict` events are expected (31.4). Relations
  and every other statement stay, and the evidence of the LLM tier stays.

### 32.5 Verification plan (read-only, production)

1. **Before the upgrade:**
   - record the number of pages with a job of any tier or a hint (the selection of 31.4 with `:scope` = `jobs`);
   - record, for the five pages, the job's `name` locator, its `hiring_organization` and `employment_type`, and the page's
     operator evidence (an organisation with `site_operator` or `domain_operator`, its imprint, its JSON-LD);
   - optionally, count the gender markers before 6,308 on the measured page.
2. **After the upgrade:**
   - `sync.relationRecheckInfoBefore`: `rules` 4, `scope` `all`, `pages`, `done` set, `doneAtStart` true, and its event once;
   - `sync.relationRecheckInfo`: `rules` 5, `scope` `jobs`, `pages` about the count of step 1;
   - `open` down to 0, then `done` set without a restart, `failed` 0;
   - no LLM request, no crawl.
3. **For each of the five pages:**
   - one job, its `name` evidence at the advertisement's heading (6,426 on the measured page), none from 0 to 6,300;
   - `hiring_organization` → the operator with "Bei uns wirst Du." as evidence, if step 1 found an operator (imprint, own-domain
     JSON-LD, or the domain's unnamed operator joined to its one declared operator); otherwise `unresolved`, which is correct;
   - no `employment_type` except the freelance position's `freelance` (rule) and `full_time` (JSON-LD).
4. **Counter-checks on other hosts of the selection:**
   - intermediaries keep `recruiting_organization` and no employer;
   - portals keep neither;
   - lists keep each position's own type.

### 32.6 Limits

- The bound of 6,000 characters after the heading stays. An advertisement whose text runs longer than that before its
  application call is cut there.
- An advertisement written only in short items, without a sentence of five words, is chosen by its section headings, then by
  its place outside a list, then by its length (as before).
- A run of labels right before the next title is kept where it reaches back to a section heading or to the title. An offer of
  short items ("Wir bieten:. - Vollzeit.") is the advertisement's, so a category navigation right after such an offer can still
  give a type.
- A company named as the hirer is compared by its legal name. On a page whose operator has no legal name (the domain's unnamed
  operator), it is not secured as the operator, and no employer is set.
- At most 400 title occurrences are read per page.

### 32.7 Tests

`AdvertisementWindowTest` (new, 10), all with the measured layout and neutral texts. Red on `03b1a78`: 10/10.

- **Measured page:** one job named at 6,426; employer at 7,075; no type; none of its evidence from the navigation (before
  6,426) or the footer (from 11,390). Run with six menu rounds (more than 30 markers) and with one.
- **Title line:** the title line before a long navigation is not the advertisement.
- **Repetition:** a repetition of the title inside the advertisement is the same position and ends nothing.
- **Menu and footer:** their self-references ("Unser Team", "Karriere bei uns", "Jobs bei uns") declare no employer, on a short
  page and on the measured page.
- **Navigation labels:** labels after the advertisement ("Festanstellung", "Freiberuflich") give no type and no employer; a type
  the offer states is kept.
- **Intermediary:** an intermediary behind the long navigation recruits; no employer.
- **Job portal:** a portal that names another company as the hirer is neither employer nor recruiter, also with "bei uns". The
  operator named as the hirer is the employer.
- **Several positions:** several positions behind the repeated menus keep their own advertisement, type, employer and recruiter.
- **Operator's identity:** cases with an imprint, JSON-LD on the own domain, the unnamed domain operator, a foreign organisation,
  no declaration, the company's legal name in the body only, and the legal name as the hirer with and without the operator's
  identity.
- **Freelance position:** behind the long navigation it is one job with `freelance` (rule, at its heading) and `full_time`
  (JSON-LD), and the employer with the short name (JSON-LD) and the full name (imprint).

`RelationRecheckEndTest`:

- **The C29 tests (3)** stay green.
- **New: the graph 0.10.6 leaves.** It starts with the rules 5 as follows:
  - the check of the rules 4 is closed with `doneAtStart` and its event once;
  - the job pages are checked (scope `jobs`) and the page with relations only is not extracted;
  - the evidence of the LLM tier stays, and a restart extracts nothing.

  Red on `03b1a78`.

`SyncServiceTest` asserts the current rules version instead of a fixed "4".

## 33. The type of an LLM entity, the log report's HTTP 400 and the provenance of LLM evidence (0.10.7)

Section number 33: section 32 is taken by the change of the relation rules 5 (C32), which is reviewed separately.

### 33.1 The type of an entity needs its context

**Finding.** OpenCode compared these cases with their sources on 2026-10-14:

- **The tools of a page's sidebar became services.** The tools ("Werkzeuge") of a wiki-like page — "Bilder aus /…/" and
  "Rechtschreibprüfung" — became `service` entities.
- **A team became a service.** A contact text ("Our service team is looking forward to providing any support …") became the
  service "Service team".

**Cause (reproduced with a modelled answer through the real parser, validator and publication; `LlmEntityContextTest`, red
before this change):**

- The validator accepted an entity when two conditions held: its type was one of the five types, and its quote stood verbatim
  in the text and contained its name. A quote that contains the name proves the name; it says nothing about the type.
- Every accepted entity gets a `name` claim (`LlmExtractor.apply`). So such an entity became a node of the graph even without
  an `offers` relation.
- No context reaches the model or the validator. Nothing marks a chunk as a sidebar, a menu or a contact text. The navigation is
  moved behind the content only on long pages with recurring blocks (27).
- The prompt defined a service as "a service or product offered", without excluding the page's tools or a team.
- The same gap let the role of a team member ("Pflegedienstleitung" in a team list) become a job, and a product used internally
  become a service.

**What the validator checks now** (`LlmExtractor.validate`, after grounding; a known entity of tiers 1 and 2 keeps its type):

- **A service named as a team, a department or staff is none.** Examples: "Service team", "Vertriebsteam",
  "IT-Abteilung"; reason `entity_service_team_or_role`.
- **Any other service needs an offer** (reason `entity_service_not_offered`). The offer can stand in one of four places:
  - in its quote ("wir bieten", "Leistungen", "kaufen", "verkauft", "vertreibt", "vermietet", "services", "offer", "sells",
    "provide", …);
  - in the heading its quote stands under: reading back from the quote over the short segments right before it (labels of at
    most three words, such as the entries of a navigation) to a heading such as "Leistungen" or "Unsere Produkte";
  - in an `offers` statement of the answer about it, whose quote holds its name and an offer;
  - in a price of it, whose quote holds an amount.
- **A job needs an advertisement** (reason `entity_job_not_advertised`). It can be:
  - a vacancy wording ("wir suchen", "Stellenangebot", "hiring", …) or a gender marker ("(m/w/d)") in its name, its quote or
    its heading;
  - a hiring statement of the answer that advertises it.
- **What happens to a refused entity:** it is dropped with its claims (`claim_entity_dropped`), counted per reason, and kept as
  a redacted sample in the refusal diagnostics.

**The prompt.** The compiled-in default defines a service as offered ("in its list of services, an offer or a price; not a
tool of the web page such as print or spell check, not a team or a role"). It defines a job as advertised ("not the role of a
team member").

- A new prompt is a new prompt hash and extractor identity. It reads no document again (6.3, `llm_prompt_changed`); new and
  changed pages are asked with it.
- An activated custom prompt stays as it is. The validator applies to every prompt.
- The default now has 3,499 characters (3,283 before). A custom prompt is stored in `kg_meta`, whose values hold at most
  4,096 characters, although the editor allows 8,000. This limit was there before; a custom prompt longer than about 3,900
  characters cannot be activated.

**Counter-checks (unchanged results):**

- services listed under "Leistungen" in a navigation (also an entry quoted alone);
- a spell check a company offers (by its offer or by its price);
- a job advertisement with its title;
- a software product sold ("als Jahreslizenz kaufen");
- a product a company says it sells ("Die … GmbH verkauft MusterText Pro", "vermietet", "sells"). The integration with the
  quality check (0.10.7) found that only the infinitive "verkaufen" was read as an offer: the reference page
  `page_tool_vs_product` lost its product. The inflected forms "verkauft", "vertreibt", "vermietet" and "sells", "selling",
  "sold" are offers now (`LlmEntityContextTest.aProductTheCompanySaysItSellsIsAService`).

Not changed:

- No domain or company is named in the rules.
- Navigation as a whole is not dropped, and roles are not dropped as such: a role in an advertisement stays a job.
- A product used internally is no service. Its use remains a `system_signal` of the signal rules (10).

**The existing graph: no automatic deletion.** Evidence the earlier validator accepted stays until a page is read again; the
next content change of a page replaces its tier-3 evidence. A targeted, reviewable cleanup plan:

1. **List the candidates.** Read-only, on a consistent copy: entities of type `service` or `job` whose name is stated only by
   tier 3, with the page, the excerpt of the name evidence and the excerpts of `offers` or `hiring_organization` statements about
   them.

   ```sql
   SELECT t.name AS type, n.obj_val AS name, d.url, substr(e.excerpt, 1, 160) AS excerpt, x.model,
          (SELECT group_concat(substr(oe.excerpt, 1, 120), ' | ') FROM kg_statement o JOIN kg_vocab ov ON ov.term_id = o.pred
             JOIN kg_evidence oe ON oe.stmt_rowid = o.stmt_rowid AND oe.tier = 3
           WHERE (o.obj_ent = n.subj AND ov.name = 'offers') OR (o.subj = n.subj AND ov.name = 'hiring_organization')) AS context
   FROM kg_statement n JOIN kg_vocab v ON v.term_id = n.pred AND v.name = 'name'
     JOIN kg_entity en ON en.ent_rowid = n.subj JOIN kg_vocab t ON t.term_id = en.type AND t.name IN ('service', 'job')
     JOIN kg_evidence e ON e.stmt_rowid = n.stmt_rowid AND e.tier = 3 JOIN kg_doc d ON d.doc_rowid = e.doc_rowid
     JOIN kg_extractor x ON x.ext_id = e.ext_id
   WHERE NOT EXISTS (SELECT 1 FROM kg_evidence o WHERE o.stmt_rowid = n.stmt_rowid AND o.tier IN (1, 2))
   ORDER BY d.url LIMIT 500;
   ```

2. **Review.** Apply the rules above to each row, by hand or by a reviewed script that runs `LlmExtractor`'s patterns on the
   excerpts. Keep every row that states an offer or an advertisement.
3. **Remove only the reviewed rows.** Remove the tier-3 evidence of the reviewed entities on their pages: their name and the
   statements about them. Do not remove other evidence of the same pages or any evidence of tiers 1 and 2. Removing is a
   separate, reviewed admin action that recomputes the statements' quality and keeps the observation history; it is not part of
   this change. Editing the store by hand bypasses the aggregates and is not recommended.

No global re-extraction and no LLM request is needed for it.

### 33.2 The log report's HTTP 400

**Finding.** On 2026-10-14 at 08:24Z production logged six `LOGREPORT phase=write result=failure` lines, each with
`java.io.IOException: Request failed with response code 400` and without the provider's reason.

OpenCode then read the call lines of these runs:

- **The six failed calls** each had about 263,600 prompt characters.
- **One successful call** had 39,775.
- **The same model, backend and output settings applied to all:** `max_tokens` 65,536, `numCtx` 131,072, `stream: true`,
  `temperature` 0.1, the stop tokens, no `response_format`, no retry.
- **Missing:** the provider's code and body, the real provider and the real prompt tokens.

**The paths compared:**

| | log report (`LogReportService`) | knowledge graph (`YacyLlmClient`) |
| --- | --- | --- |
| selection | `llmFromUsage(logreport, runId, caller)`: the first row of `ai.production_models` with the usage flag | `llmFromUsageQuiet(knowledge)` |
| endpoint | `LLM.chatStream`: always `POST <hoststub>/v1/chat/completions`, `stream: true` | Ollama: `/api/chat`; others: `/v1/chat/completions` with `stream: false` |
| parameters | `model`, `temperature` 0.1, `max_tokens` = the row's, `num_ctx`, 8 `stop` tokens, `reasoning_effort: none`, `enable_thinking: false` | the same on `/v1/chat/completions`, plus `response_format` |
| prompt size (before) | log lines = max(512, num_ctx/4, num_ctx − max_tokens) tokens at **4** characters per token; template and noise summary not counted | `RequestBudget`: prompt + answer reserve ≤ window, else split or refused |
| timeouts | none | connect 10 s, read `llm.timeout` |
| retries | none (a failed hour is tried at the next run while its lines are in memory) | one retry without the format after an HTTP 400 with a format; queue backoff |
| error body | not read | not read |

**Cause: a context overflow, as a hypothesis until a provider's message confirms it.** The budget explains the measured
sizes exactly:

- With `num_ctx` 131,072 and `max_tokens` 65,536 the log lines got 131,072 − 65,536 = 65,536 tokens at four characters each:
  262,144 characters.
- With the template, the prompt had about 263,600 characters (the replica: 262,882).
- Log lines with timestamps, paths, URLs and numbers have fewer than four characters per token. At three, the prompt alone
  needs about 88,000 tokens, which together with the cap of 65,536 exceeds the window. A strict OpenAI-compatible endpoint
  refuses that with HTTP 400; Ollama truncates.
- The successful call (39,775 characters, about 13,000 tokens) fits.
- The same row's knowledge graph calls fit, because their requests are planned against the window (`RequestBudget`).
- Reproduced with neutral log lines against a mocked strict endpoint (three characters per token, the cap counted): the
  code before builds 262,882 characters and is refused; the code now builds 147,472 and is answered.

**What changed:**

- **The whole call is planned against the window** (`LogReportService.callBudget`). The window is the service's `num_ctx`
  (configured, or assumed when not configured) and the output cap is the row's `max_tokens`; no model or provider is named.
  - A margin of 5 % of the window (at least 256 tokens) stays free.
  - The prompt gets the rest, at least a quarter of the window, and counts its template and instructions as prose at 3.5
    characters per token and its log lines at 2.5. The estimate is conservative.
  - Where the window is configured and the output cap leaves the prompt less than a quarter of it, the cap sent for this call
    is lowered to what is left. Where the window is only assumed, the configured cap is sent as before.
  - Production settings: the cap stays 65,536 and the prompt gets 58,982 tokens. The production-like hour of the replica gets
    147,472 characters, about 58,932 tokens.
- **No silent cut.** If the log lines that are no noise do not fit, they are selected in this order:
  1. warnings and errors (`W`, `E`), the newest first;
  2. the newest other lines, until the next one does not fit.

  The kept lines stay in their order. The prompt says how many lines were omitted and how many of them were warnings or
  errors; so do the report's header ("Sent to the model: …") and the call's log line (`inputlines`, `sentlines`,
  `omittedlines`). The daily report keeps the newest whole hourly reports and says how many it omitted.
- **A call that cannot fit a configured window is not sent.** This applies when the template alone is too large; the log line
  names the reason. Nothing is retried; the next run tries again.
- **The provider's reason is kept.** `LLM` reads at most 16 KiB of an error body (`LlmHttpError`) in every path. The message
  keeps its form, "Request failed with response code N", and adds in parentheses:
  - the provider's error code or type;
  - the rejected parameter;
  - the upstream provider a router names;
  - at most 300 characters of its message.

  Never part of it: the request, the prompt, the whole body, a router's raw upstream answer, an API key or a bearer token.
  The fallback of the structured output still recognises "response code 400".
- **The log report's lines name the call.** The model-call and failure lines carry `function=logreport`, the service type,
  the model, `promptChars`, `estPromptTokens`, `promptTokenBudget`, `maxTokensConfigured`, `maxTokensSent`, `numCtx` with
  `numCtxConfigured`, `windowExceeded`, and the input sent and omitted. A failure also carries `httpStatus`, `providerCode`,
  `providerParam`, `provider`, `providerMessage` and `errorBodyBytes`. The `runId` of every line is the correlation id.
- **The knowledge graph's status names the error.** `lastError` names the HTTP status and the provider code.

No request parameter was removed, no retry and no fallback to another model was added, and there is no rule for one model.

**Limits:**

- The estimate is not the provider's tokenizer: 2.5 characters per log token is chosen to be safe. Real prompt tokens of the
  streamed call are not reported (no `usage` in the stream) and are not measured here.
- A large hour now sends fewer log lines than before, with the warnings and errors first and the omission named. Splitting an
  hour into several calls would keep every line at the cost of more calls; it is not done.

**Read-only check for OpenCode after the next run:**

- the call line's `estPromptTokens`, `promptTokenBudget`, `maxTokensSent`, `windowExceeded=false` and the `omittedlines` of the
  hours before;
- no new failure line; if one comes, its `httpStatus`, `providerCode` and `providerMessage` confirm or refute the context
  overflow.

### 33.3 Provenance and the time axes of LLM evidence

| field | where | what it says | what it does not say |
| --- | --- | --- | --- |
| `kg_evidence.observed_at` | evidence | when Scoutro published the evidence: processing time; reset when the tier is published again or a newer load of identical content confirms it | when the page said it, which model ran |
| `kg_evidence.source_observed_at` | evidence | when YaCy loaded the page (`load_date_dt`) | when the model was asked |
| `kg_evidence.ext_id` → `kg_extractor.model`, `prompt_hash` | evidence | the model selected for the request (`service/model`) and the prompt that produced it | the model a router actually served |
| `kg_observation.observed_at` | observation | the page's load time of the evidence | — |
| `kg_observation.recorded_at` and the event `recorded` | observation history | when the observation was recorded (the evidence's processing time) | **which model**: `extractor` is `name/version` (`llm/4`) |
| `kg_extraction` (cache) | cache | the model and prompt of the cached answer (`ext_id`) | when the model was asked (`last_used` is the last reuse) |

Kept as they are: `observed_at`, the observation's times and its deduplication (unique key without model).

**What these times allow:**

- The model of a tier-3 evidence row is read from its `ext_id`, not from a time.
- An evidence row published after a switch of the model can come from:
  - a continuation of a page read before (`applyLlmMore` keeps the rows it does not replace, with their `ext_id`);
  - a cache hit (the cache key includes the model, so only the same model's answer).
- Its `observed_at` therefore says nothing about Nemo or any other model. **Old evidence is not attributed to the new model.**
  The rows published before the switch keep the `ext_id` of the model that produced them.

**A switch while calls run:**

- The tier takes one model per document; each request is admitted only while the selection equals it (`model_changed` defers
  it).
- A request already sent finishes with its model and is published under that model's name.
- Since 0.10.7 the client also checks, at the admission of each request, that the selection it read is still the selection.
  A switch between the two defers the request, and nothing is sent.

**What the endpoint served (new, diagnostics only):** `llm.context.measured` counts the answers per model, and per provider
behind a router, that the endpoint names in its answer (`servedModels`). It also counts the answers whose served model differs
from the one asked for (`servedDiffering`) and those that name none (`servedUnreported`).

**Proposal, not part of this change:** a nullable `kg_observation.extractor_model`. It would be filled at capture from
`kg_extractor.model` and stay outside the unique key, so the deduplication is unchanged. It would keep the model when the
evidence is gone. This needs a schema version and a migration; until then the model of an observation is read through its
statement's current evidence.

### 33.4 Tests

- `LlmEntityContextTest` (new, 9, the real sync, LLM tier, validator and publisher with a modelled answer):
  - the sidebar's tools are no services;
  - a team of a contact text is no service;
  - counter-checks: services under their heading in a navigation, a spell check offered or priced, a job advertisement, a
    software product sold, a product a company says it sells, rents out or sells in English (added in the 0.10.7
    integration; red before the inflected forms);
  - a team list without a vacancy has no job and no service;
  - a product used internally is no service.

  - the cleanup plan's read-only query (33.1) lists what only the LLM tier states, with its excerpt and model.

  Red before this change: 4 of the first 7 (the four confirmed kinds of mistake). The counter-checks pass before and after.
- `LlmExtractorInvalidReasonTest`: one case per new reason code.
- `LlmHttpErrorTest` (new, 5, a mocked strict endpoint through `LLM.chatStream`): an exceeded window, a rejected parameter, a
  router's error with its provider but without its raw answer, a masked key, and a bounded huge body. Red before this change:
  5 of 5 (no reason).
- `LogReportDiagnosticsTest` (new, 8, neutral log lines and a mocked strict endpoint):
  - a full hour with the production settings fits the window with its cap and is answered, all warnings and errors kept, the
    omission named;
  - an hour that fits is sent whole;
  - an output cap that would fill a configured window is lowered for the call;
  - an assumed window keeps the configured cap;
  - warnings and errors come first and the order stays;
  - the daily prompt keeps the newest whole reports;
  - a prompt that cannot fit is not sent;
  - the failure fields carry the provider's reason, never the prompt or the key.

  Red evidence: the code before, run with the same lines and the same strict endpoint, builds a prompt of 262,882 characters
  and is refused with a bare HTTP 400.
- `YacyLlmClientTest` (+2): the served model and provider are counted; a model switched before the admission defers the
  request.
- `LlmFullReadingTest`: its fixture names a customer project an organisation; as a service no offer states it is refused.


## 34. Service names need an independent offer, and offers need their provider

[Service offer evidence](SCOUTRO_SERVICE_OFFER_EVIDENCE.md) documents the
navigation/boilerplate regression, its synthetic red/green reproduction, the
shared validator/publication fix and its limits. This supersedes section 33's
service-heading scan over unmarked navigation labels. Explicit offer lists in
navigation and services supported by content remain valid.

Inline and marked lists bind `offers` to the heading's direct provider label;
a referring organisation does not inherit a third party's offers. Heading
and item negation prevent positive evidence, including prices in that scope.
Shortened quotes retain the original source/list context. The next
[release/package handoff](SCOUTRO_SERVICE_OFFER_RELEASE_HANDOFF.md) is required:
published 0.10.8 and its existing package PR #10 do not contain #73.

`entity_service_not_offered` also covers a name whose own words were its only
offer cue. `claim_offer_not_stated` covers a grounded, correctly typed `offers`
claim without an offer of that service by that provider. Both use the existing
bounded diagnostics and per-item indices. The same service evidence rule runs
before publication, including cached results; dependents of rejected entities
have no reference and are omitted. No automatic re-examination, prompt/model
switch, existing-data cleanup, merge or rollout is part of this change.

## 35. Names that are no names, brands that are no operators (0.11.5)

### 35.1 Findings (production, 19 October 2026, schema 9, 177,853 pages)

Seven cases of the read-only production report, all confirmed in the code of 0.11.4:

| Host | Stored | Cause |
|---|---|---|
| www.pro-casa-bau.de | organisation "Bildergalerie", `reference_for` | the navigation label after "Referenzen"; the list rule split the menu on line breaks and `qualifies()` accepted any capitalised word |
| www.dielen-kaufen.de | organisation "Showroom", `partner_of` | the menu entry after "Partner" |
| www.rostocker-massivhaus.de | organisation "Banken" (only a `name`), `partner_of` | "Unsere Partner: Banken, Versicherungen, Bausparkassen": a kind of organisation; a relation recheck cannot repair an entity that has only a name |
| architektin-im-wasserturm.de | service "Hersteller", `offers`, derived industry | the vocabulary category `handel/hersteller` on a services page: every hit became an offered service (the same reading as the drolshagens pilot) |
| drolshagens.de | the product brand "Golden-Ingwer" as domain operator, with the brand's social profile | JSON-LD `Product.brand` with the shop's `url`: `ownDomain()` made the brand the operator and `sameAs` became its profile |
| das-pianohaus.de | "SCHIMMEL" `brand_of` the piano shop | `Organization.brand[]` of a dealer lists the makers it carries; the rule read every entry as the organisation's own brand |
| slowik-it.de | "Apple" `partner_of` | correct (a one-word brand in a partner list); the display showed an external brand like a local company |

Several same-named entities ("SCHIMMEL" from every product page) come from brand nodes without an `@id`: each page got a `doc_local` key, no shared identity.

### 35.2 What 0.11.5 changes for pages read from now on

No version bump: `jsonld/6`, `rule/7`, `vocabulary/4` and the vocabulary hash are part of every page's extractor key; a new version would read all 177,853 pages again. `SyncService.RELATION_RULES` stays `5`: no rule-bound recheck starts at the first start. The role category list is code (`BusinessRules.ROLE_CATEGORIES`), not a `categories.json` entry, for the same reason. Pages keep their stored facts until their next regular crawl.

- `BusinessRules.NOT_A_NAME`: the labels of a navigation or footer (galerie, bildergalerie, showroom, ausstellung, standorte, team, karriere, downloads, presse, referenzen, projekte, produkte, angebote, service, sitemap, login, agb, faq, blog, anfahrt, öffnungszeiten, unternehmen, shop, newsletter, menü, gallery, contact, imprint, privacy, careers, references …).
- `BusinessRules.GENERIC_WORD`: kinds of organisations (banken, versicherungen, bausparkassen, behörden, kommunen, unternehmen, firmen, hersteller, händler, lieferanten, dienstleister, handwerker, architekten, bauträger, investoren, verbände, vereine, schulen, kliniken, agenturen, companies, manufacturers, dealers, suppliers, customers, brands, marken …) and `PLURAL_NOUN` (-ungen, -schaften, -heiten, -keiten, -ionen, -innen, -isten, -ereien); both before the institution clause of `qualifies()`, so "Vereine" and "Stiftungen" are no organisations either. `genericName(String)` exposes the guard to the read side.
- `names()`: a list on lines (the marker ends at a line break and every separator is a line break) names organisations only with a legal name or an institution among its items; without one only marked brands stay (`BRAND_MARK`: an abbreviation "DHL", a digit "3M", an inner capital "PayPal", "&"). "Unsere Partner Siemens, DHL und SAP" in a sentence is unchanged (`BusinessExtractionTest`).
- `services()`: a category whose code is a role word (`hersteller`, `händler`, `dienstleister`, `grosshandel`, `einzelhandel`, `lieferant`, `anbieter`, `produzent`, `vertrieb`) is a topic of the page, never an offered service, on every page kind; `industry_category` is no longer derived from it. `KgRuntime.textFit()` counts topics like categories, so the correction automation's text-fit signal of the drolshagens pilot is unchanged.
- `JsonLdExtractor`: while the nodes of `brand` (of a Product or an Organization) and `manufacturer` are read (`Run.brandDepth`), an organisation node is a brand node unless its name is the domain's label: it never becomes `siteOperator` or `domainOperator` by its own `url`, its `sameAs` profiles are not stored (`Run.profilesOff`), and without an `@id` it gets `ldId = "brand:" + key(name)`, one identity per host (`ld_id` scope `organization@host`), so every product page of the host names the same brand. `Organization.brand` → `brand_of` only for an own brand (`ownBrand()`: own domain, or a word of four letters or more shared with or starting a word of the organisation's name, legal name or domain label: "MusterCRM" of "Muster Software GmbH", "Pianohaus Edition" of "Das Pianohaus GmbH"); otherwise the brand supplies the organisation (`supplier_of`, a business relation). `Product.brand` → `has_brand` and `Product.manufacturer` → `manufacturer_of` are unchanged.

### 35.3 What the read side corrects (no data change)

`net.yacy.scoutro.knowledge.read.EntityRoles` derives a role per organisation at read time: `operator` (a `site_operator` or `domain_operator` key), `manufacturer` (subject of `manufacturer_of`, or named under a `manufacturer` locator), `brand` (object of `has_brand`, subject of `brand_of`, named under a `brand` locator, or an `ld_id` `brand:…`), `generic` (only `doc_local` or `operator_name` keys and `genericName(name)`); an operator is never a brand. `KgReader.summary()` adds `role` to the object list and the object view, `BusinessView.entityRef()` to every relation item. `BusinessView.relations()` hides a relation whose other side is `generic` and reports the number as `relations.hidden_generic`; the generic entity's own object view keeps every statement, the object list still lists it with its badge. The page shows role badges, lists brands and manufacturers under their own heading, says how many relations are hidden, labels `supported` as "stated by sources" (German "laut Quellen") with a tooltip that nobody verified it, and reads `brand_of` as "is listed as a brand of" (German "wird als Marke geführt von") so that a dealer's listed maker is not shown as the dealer's own brand.

### 35.4 What stays physically in the graph

The organisations "Bildergalerie", "Showroom" and "Banken", the service "Hersteller" with its `offers` and derived industry, "Golden-Ingwer" as domain operator with the profile, "SCHIMMEL" `brand_of` the dealer and the same-named brand entities per page: all stored by pages read before 0.11.5. They are neither deleted, retracted nor merged by the upgrade (no `reextract_pending`, no recheck, no WP-E assessment written). They change only when their pages are crawled again (the new rules apply, the old facts lose their current source and become `stale`) or by a later targeted action an administrator runs deliberately: a rule-bound recheck of the pages with tier-2 `partner_of`/`reference_for`/`customer_of` relations (`SyncService.RELATION_RULES` 5 → 6, bounded to `RECHECK_RELATIONS`, no model) and, for the known cases, WP-E assessments (`withdraw`) by entity id. Both are prepared here as a description only; neither is part of 0.11.5. Existing duplicates are not merged; the object view lists them as possible duplicates as before.

### 35.5 Tests

`KgProductionCasesTest` (the seven hosts: navigation lists, kinds of partners, the role word, the product brand with the shop's url, the dealer's brand list, the partner brands that stay, and the guard `genericName`), `EntityRolesTest` (an 0.11.4 page's "Banken" and "Bildergalerie" published as the old rules stored them: labelled `generic`, hidden from the business view with `hidden_generic` = 2, kept in the object view and list; a dealer and a product page: `operator`, `brand`, `manufacturer`, brands under their own heading), `BusinessExtractionTest` and `ExtractionSemanticsTest` unchanged, `DrolshagensPilotTest` with "Hersteller" as a topic from the start (no offered service before or after the move).

## 36. Offers back, customers and competitors apart (0.12.0)

### 36.1 Finding

The stricter offer proof of 0.11.4 to 0.11.7 kept a service only with an offer sentence, a heading such as "Leistungen:" or
a price. Production (21 October 2026): 6,669 organisations with services, against 10,624 before; 62,025 of the 63,572
former services had come from the rules (tier 2). Many own sites name what they do only in their title or brand line
("Malermeister Dehmel | Malerarbeiten in Oschersleben"), in a list, on the page of one service
("/sap-beratung/sap-cash-management/") or with a consulting verb ("Wir unterstützen Sie bei der Einführung von SAP
S/4HANA"); those became topics. The LLM tier's proof had the same gap, and its cache kept only what the proof accepted.

### 36.2 What rule/8 accepts (`ServiceEvidence`, `BusinessRules`; `RuleExtractor.VERSION` 8)

Every proof belongs to the provider: its own site or a sentence with the provider as subject.

- **Explicit** (as before, confidence of the page kind): an offer sentence, a heading such as "Leistungen:", a price, and
  now a consulting or delivery verb ("unterstützen", "beraten", "begleiten", "einführen", "implementieren", "umsetzen",
  "stehen wir Ihnen … zur Verfügung", "bekommen … von uns") whose subject is *wir*, *unsere Berater/Experten/Spezialisten/
  Teams/Mitarbeiter* or the provider's name. A delivery for a client ("Wir haben für die Muster AG die SAP-S/4HANA-Einführung
  umgesetzt") proves an own service, never the client's system; "für alle unsere Standorte eingeführt" is own use. Where a
  provider is named (the LLM tier's claims), it must be the subject of the verb: as the client ("Wir haben die Muster AG bei …
  unterstützt") it offers nothing. A reader asked to support the team ("Sie unterstützen unser Team …") is recruiting.
- **Structural** (`BusinessRules.STRUCTURAL_CONFIDENCE` 0.4, marked in the company view and as `offer_evidence:
  "structural"` of the business view): the title or brand line of the home page, a services page or the page of one service
  (`PageKind.SERVICE_DETAIL`: a path segment `*-beratung`, `beratung`, `consulting`, `dienstleistung(en)`, `loesungen`,
  `lösungen`, never under a news, article, guide, event or careers path and never with a careers title), and an item of a
  bulleted list (next to another bulleted line; a date, a vacancy "(m/w/d)", own use, recruiting or a link to another site
  "(öffnet in neuem Tab)" is no item), and a short heading in a row of headings of the same level ("### Abdichtung. ###
  Dämmungen.", at most 60 characters and 7 words, the next heading at most 400 characters away; a question, an event or a
  date, a vacancy, a denial or own use is no such heading). A bare menu or path needs a second sign of the same site (title,
  service page, list or row of headings); a category the title does not name stays a topic.
- **One operator named twice:** when a page's structured data name the operator twice (an Organization and its
  LocalBusiness, "Wibu-Systems" and "WIBU-SYSTEMS AG", one block without a name), the rules read the page for the subject
  with the longest name (`BusinessRules.sameOperator`); two different organisations still leave the page's business facts
  unread.
- **A topic stays a topic:** tools and own use ("wir nutzen / setzen … ein / arbeiten mit", "we use"), denials, news and
  articles, a reader's or a vacancy's request ("Unterstützen Sie uns", "Wir suchen Verstärkung"), the provider's own past
  introduction of a system ("Wir haben SAP S/4HANA eingeführt") and a client's system on a reference page.
- **What names no offer (gate T0, owner review of 22 October 2026):** an item of a list under an application area or a
  market ("- Anwendungen - Fuel Cell - Battery - … - Flooring", `BusinessRules.marketItem`; under "Referenzen", "Projekte" or
  "Leistungen" the items stay offers); a network of partners ("mit Partnern in unserem Netzwerk",
  `BusinessRules.businessNetwork`; a sentence about IT, a LAN, servers or cabling keeps the network); a past participle
  before "into/in" ("embedded into Microsoft Business Central", `BusinessRules.participle`); an English word form of another
  word as a compound ("Laminating" is no "Laminat", `TermMatcher`); and the title of a news, article, event or careers page
  ("… Civil Security Technologies and Services"): only its path makes such a page a services page. The software vocabulary
  no longer has the bare "quality assurance" (an app for quality management is no software testing); it names software
  testing by "software quality assurance", "Softwarequalitätssicherung", "QA testing", "Softwaretest" and, as before,
  "Qualitätssicherung".

The LLM tier checks its services with the same proof (C2): a service named by the title of its own page is accepted with
the title as its quote (`title:0+n`, confidence 0.4), and the site's operator (`BusinessRules.pageSubject`) offers it when
the answer names no provider. The pages of single services are LLM candidates (`RuleExtractor.candidate`). Prompt, schema and
`LlmExtractor.VERSION` stay; cached answers are checked again when they are published.

### 36.3 What an existing graph does after the update

- **Tiers 1 and 2 (no model):** the new extractor identity re-extracts every tracked page once at low priority, as for
  0.11.4 ("What the new version does to an existing graph" under *The imprint's operator*; `Reconciler.REASON_EXTRACTORS`,
  `kg_meta.reextract_pending`); topics the new rule proves become
  services again, industries and the matching get them back. The re-extraction is the bulk of the update's work. The
  vocabulary changes in the same release (above); its version is part of the extractor identity too, so it needs no pass of
  its own. Cached LLM answers stay valid; the fingerprint of the plausibility scan changes, a scan still runs only on demand.
- **Pages skipped as `not_candidate`:** decided once more by the new candidate rule at the first start
  (`kg_meta.llm_candidate_rules`, event `llm_candidates_changed`); a page that is still no candidate is skipped again without
  a request.
- **Services pages the LLM tier read without an accepted service (C3):** made to do once more at the first start
  (`llm_reason` `service_recheck`, event `llm_service_recheck`, once per graph by `kg_meta.llm_service_recheck`): done, not
  continued, active, a services page or the page of one service by its address and no tier-3 offer evidenced by the page.
  The worker asks the model for such a page past its cache (whose entry kept only what the former proof accepted) and
  replaces the entry; the continuation of a long page uses its cache as before. Such a page keeps its place under the per-host
  cap (`scoutro.kg.llm.maxDocsPerHost`): it had it as a done page, and other pages of the host made to do at the same start
  cannot take it. The pages go through the ordinary queue,
  schedule and budget of the LLM tier. Every other page keeps its reading: no mass re-read.
- **Not repaired by this:** pages whose text is missing (`text_missing`, for example near-empty pages of a few
  services pages) stay without services until their content is there; the cause is crawl, rendering or parsing, a separate
  item.

### 36.4 Customers and competitors apart

- **Possible competitors (B1)** are read on demand per category the organisation offers itself
  (`GET …/kg/entities/{id}/competitors?category&cursor&limit`, grant `kg.read`): the visible, current offers of other
  organisations of a service of that category, one line per offer with the other organisation, its own service and at most
  three visible source pages. The business view names only the categories (`competitors.categories`, `path`); it computes no
  competitor. A page reads the category statements through the partial index `kg_statement_category ON kg_statement
  (obj_val, subj) WHERE pred = <category>` with a keyset cursor on the service's row, at most 2,000 services per page; a
  global ranking by shared categories was not boundable (20,000 providers: 1.6 s) and is not offered. Competitors are
  derived at reading: no stored derivation, no fact, no customer relation, no matching input, not part of the export, the
  download or the change feed. A topic makes nobody a competitor. The index is created idempotently at the start
  (`KgSchema.ensureCategoryIndex`); the schema version stays 9 and an older version ignores the index.
- **Customers per service and reason (B2):** a company can be a competitor (SAP consulting), a possible customer
  (explicitly sought support) and a partner at the same time. Only the customer suggestion whose single evidence is the
  company's own capacity for exactly that service (the consultant posting of a consultancy, 0.11.8) is left out. Support
  sought for an own migration ("Für unsere S/4HANA-Migration suchen wir externe Unterstützung") reads as a planned
  migration; an offer of such support, a recruited consultant and a client's migration do not. Every reason names its kind
  of evidence (`evidence_kind`: `need`, `use`, `job_requirement`). `MatchingRules.VERSION` stays: the rules act at reading,
  every correct suggestion of today stays visible.
- **Gate T6, rounds 1 and 2:** in a production sample of 50 customer suggestions, most "use" of candidates offering the
  product came from sentences about their offer, clients or partners, from their clients' voices or about others. The
  signal reader no longer reads own use from them. A provider of the product itself can still be a customer of other
  providers (owner decision of 22 October 2026: a pair is judged from the provider's view); only the delivery role of an
  employer offering the product is its capacity, a title alone excludes nothing (docs/SCOUTRO_MATCHING.md). The judged
  reference with the corrected verdicts: docs/SCOUTRO_GATE_T6_0.12.0.md.
- **Documented business relations (gate T6, round 2, owner decision of 22 October 2026):** the rules read the clients a
  page documents for its operator (`BusinessRules.references`), each as `client customer_of operator` (confidence 0.65) with
  the excerpt as its source:
  - the client field of a project: "Auftraggeber:", "Bauherr:" (not where the page names an "Auftraggeber"), "Kunde:",
    "Client:" at a line's start, after a bold mark or after a sentence ("**Auftraggeber:. **Ev.-Luth. Domgemeinde Freiberg.
    **Leistung:. …", "Schloßpark-Center Schwerin. Auftraggeber: Strabag AG."), or "Auftraggeber." on a line of its own
    above its value on a reference page; the project is the profile's field ("Auftrag:", "Projekt:", "Leistung:" …), the
    heading above or the line's start. "Kunde:" without a second field of a profile counts only on a reference page (a
    sample invoice "Kunde: Meier GmbH" on a home page is none); "Kunden:" (several) only with one. A remark in brackets
    ("ARGE Brienzersee (Bauherr: …)") and a unit after a dash or comma ("SBB AG, Infrastruktur – …") are no part of the
    name;
  - a heading of a reference page naming the client of its project ("### S/4HANA Release-Wechsel bei E.V.A. & regio iT");
    with "für"/"for" only a name with a legal form, since the word also names a purpose ("… for Rocket Manufacturing");
    "in Gschlachtenbretzingen bei Michelbach" names a place; of "für Gleisbauarbeiten, BASF Schwarzheide GmbH" only the
    company;
  - the title of the page of one reference ("Referenz B. Braun: HR-Dashboards mit SAP Analytics Cloud", "Success Story:
    SAP SAC Finanzplanung bei CLAAS", "Servity Success Story mit Wuppertaler Stadtwerke") when the page names the client;
  - a customer's voice below a heading of customers' voices or references ("Kundenstimmen", "Das sagen unsere Kunden",
    "## Referenzen"): the role line with its company ("Leiter Revision, Verbund AG.") and the paragraph after it, or the
    quoted words and the signature below them ("„Mit white duck haben wir …“" / "Ronny Büchner. Managing Director. ESYON
    GmbH."; the company is the signature's last part, with a legal form or after a role); the voice's own words are read for
    that company (its use and its need), never for the page's operator, and the operator's own signature is none.

  Several clients of one profile, heading or voice ("E.V.A. & regio iT") are each a client. A name that is no organisation's (a
  person, a menu word, the reader "Ihr Unternehmen", a phrase such as "mehr Effizienz") is none, and where the role is
  unclear no relation is made; at most 12 clients per page. The project's products become a `system_signal` of the client
  with the context `purchased_service` (section `references`, the project's name and its state `completed`, `running` or
  `unknown`), also those a voice names; a team or leadership development program becomes a `business_need_signal` with
  `purchased_need`; a past building or care project is no need of the client now.
- **No invented relation:** a bare marker of clients or references in a sentence ("Unsere Kunden und Millionen von Anwendern
  vertrauen … Technologien und Methoden", "What Our Customers Say", "für unsere Kunden und Hilfesuchenden") is a phrase, no
  list; on lines it is mostly a menu ("Referenzen / Biografie"), where only a legal name or an institution is a client or a
  reference. With a colon or a word introducing the list ("Unsere Kunden: …", "Kunden wie …", "Unsere Kunden sind …") it is
  one, as before. On the 658 exported pages of the quality gates (22 October 2026) this removes 33 relations, all wrong
  ("Methoden", "Say", "Bäder", "Tiefbau", "PRÄSENTIERT"), and no right one.
- **Measured on production pages (phase 3c, 22 October 2026):** of 400 pages naming clients (100 per collection), the rules
  read 33 clients, each as the page states it (bauteamcheck 25: rail operators, construction companies and public bodies
  as clients of a railway builder, a cleaning company and a restorer; stackfinder 8, 4 with a purchased SAP product);
  checkthecoach and edelsenior name private persons or seminar participants, no client with a clear role.
- **No building need from an offer:** a provider's offer worded with the words of a building need is no need
  (`BusinessSignals.NEED_OFFER`): the reader's project ("Wenn Sie einen Neubau planen", "Wir planen gemeinsam mit Ihnen den
  Umbau"), a delivery ("planen und realisieren wir", "Wir organisieren alle Gewerke"), a range of services ("Ob Neubau,
  Sanierung oder Erweiterung", "vom Neubau bis zur …") or a private client's words ("Meine Frau und ich planten …"). The
  production sample of bauteamcheck (phase 3c: 486 new builds, 198 rebuilds recorded) held only such offers apart from a
  few own plans of companies; a hospital's care transition, worded for its patients, is read as before. The relations act with the re-extraction of rule/8; relations read
  before (also wrong ones from former rules) are not corrected by this. In matching such a purchase is a reason of the kind
  `reference` for every other provider of the service; the provider's own reference names an existing client and makes no
  suggestion (docs/SCOUTRO_MATCHING.md).

### 36.5 Tests

`OfferEvidenceRule8Test` (consulting verbs with their subject, own use, denials and own past introduction, a reader asked to
support the team, the provider as client, title and brand line, bulleted items with a denied one, dates, vacancies and a single
dash, list items linking to other sites, a row of short headings and the headings that are none, one operator named twice in
the structured data, the page of one service, news and careers pages and a news item titled with "Services", a bare menu with and without a brand
line, application areas, partner networks, participles and English word forms), `ServiceOfferPublicationTest.theTitleOfAServicesOwnPageShowsTheModelsServiceStructurally`,
`theTitleProvesNoOfferOfAnotherOrganisation`, `LlmServiceTest.aNewCandidateRuleDecidesOnceMoreAboutSkippedPages`,
`servicesPagesWithoutAnAcceptedServiceAreAskedOnceMoreWithoutTheCache` and `aServicesPageAskedOnceMoreKeepsItsPlaceUnderTheHostCap`, `BusinessSignalsTest.supportSoughtForAnOwnMigrationIsAPlannedMigration`,
`MatchingTest.customerRowsOfTheT6Table` and `everyReasonNamesItsKindOfEvidence`, `DocumentedRelationsTest` (a project
profile, the headings of a reference page with a purpose heading that names no client, a customer's voice, a team development
program, quoted words with their signature and the provider's own signature),
`BusinessExtractionTest.aBareMarkerOfClientsInASentenceNamesNobody`, `MatchingTest.aPurchaseDocumentedByAnotherProvidersReferenceIsAReason`, `theOwnReferenceOfAProviderNamesAClientNoSuggestion`
and `aPurchasedProgramIsANeedButAPastBuildingIsNot`,
`BusinessViewTest.competitorsOfferTheSameCategoryAndTopicsMakeNone` (pointer, page per category, pagination, not found, the
query plan through the partial index), `KnowledgeApiTest.readRoutesValidateTheirParameters`, `AgentCatalogTest`, and the UI
test `test/scoutro-ui/knowledge-competitors-ui-test.mjs`. The quality gates T0 to T6 of the release run on production data
read-only before the merge (docs/SCOUTRO_RELEASE_0.12.0.md).

## 37. The current reading in the matching, one offer per service, the LLM tier's places and the crawl's addresses (0.12.1)

### 37.1 Finding (production, 23 October 2026, read only)

- **STELLWERK had no customer suggestion** after 0.12.0, with five services and 4,165 SAP signals in the graph. The matching
  had no contribution of it: its cycle had not reached it (`cycle_complete` false, `deferred` `work_budget` at 50,000 checks
  an hour), and every page naming its "SAP" service was an offer of its own, checked against the same 3,700 signals again.
- **Earlier readings fed the matching.** Observations are history (SCOUTRO_DURABLE_OBSERVATIONS.md): a page read again by a
  newer extractor keeps its earlier readings. The provider's own menu item "SAP In-House Cash" stood as a system in use and
  "Kernkompetenz SAP Rechnungswesen" as a requirement, three times per page; the rules of today read neither (the same
  quotes: `stated_competence` or nothing). The 306 `internal_use` SAP signals had no contribution at all; the samples were
  such readings, and the matching's context check refused them.
- **An offer needed a noun** ("Beratung", "Einführung"). "Unsere Experten … führen SAP DRC in Ihrem S/4HANA-System ein",
  "Wir machen Sie mit SAP Fiori vertraut" and a service named "SAP" were no offer; STELLWERK passed only by the word
  "Consulting" in its page titles.
- **The LLM tier's places of a host went to whatever came first.** 25 places per host count done and queued pages; the
  site's wp-json, a script and preview images were queued, the careers pages read, the project references (67,000
  characters), a services page and a reference story skipped (`host_cap`).
- **The crawl's 15 pages per domain** included 10 technical addresses of 46 documents (wp-json, oEmbed, a script, images) and
  one PDF the installation's blacklist should have kept out.

### 37.2 The current reading (`MatchingRules.current`)

An observation is a matching input only while it is its page's current reading: unless its page is still active
(`kg_doc.state` 1), was processed after the observation was recorded and carries no evidence of the observation's tier from
the observation's extractor any more. A page gone from the index keeps its observations (an advertisement that is gone is
still the evidence of its job). The condition applies to the provider's offers, the signals, the employer's own offer, the
retirements of a system or a need and the business roles; at reading, `Observation.current` (set by `read` and
`referencedBy`) makes a contribution resting on an earlier reading unusable at once, before the cycle reconciles it. The
observations themselves, their history, export and corrections are unchanged; nothing is deleted.

### 37.3 One offer per service, offers written with verbs

- `MatchingService.distinct`: one offer per rule, product or need and service of a provider, its evidence the latest of its
  observations. The contribution's identity names its evidence, so contributions of the former duplicates are removed when
  the provider's partition completes; `MatchingRules.VERSION` stays 1, no suggestion is recomputed beyond that.
- `MatchingRules.SUPPORT`: the nouns as before plus the verbs a provider writes them with (beraten, unterstützen, schulen,
  einführen and "führen … ein", integrieren, implementieren, betreuen, migrieren, begleiten, "setzen … um", Umsetzung,
  Workshop).
- A service named or categorized as the product itself ("SAP", `software/sap`) is an offer of it without a word of support,
  as the employer's own offer already was since 0.12.0; the product of its name only, never of its quote ("Cloud" quoting
  "SAP Analytics Cloud" offers nothing). Products stay exact: general SAP proves no S/4HANA, BW or SAC
  ([matching](SCOUTRO_MATCHING.md)).

### 37.4 The LLM tier's places of a host

- A technical resource (`RuleExtractor.RESOURCE`: media, scripts, styles, fonts, archives, wp-json, wp-includes, oEmbed,
  feeds, xmlrpc) is no candidate and takes no place: the scan marks it `not_candidate` before the cap is counted.
- A page of the site's services, of one service or of its references (`LlmQueue.corePage`, by its address) keeps a place up
  to twice the cap (`scoutro.kg.llm.maxDocsPerHost`, default 25); careers, news and other pages stay under the cap.
- The pages skipped at the cap are decided once more under this rule, once per graph (`kg_meta` `llm_host_cap_rule` = 2,
  event `llm_host_cap_changed`); a page still over the cap is skipped again without a request.

### 37.5 The crawl's addresses

Every Scoutro crawl start (administrator, account, agent, Discovery) excludes the technical resources and documents (PDF,
office files, e-books) with its URL filter (`ScoutroActions.SKIPPED_ADDRESSES`, an alternative of `mustnotmatch` after the
start marker and the host filter). The owner does not let the crawler read documents (23 October 2026); the installation's
blacklist stays as it is. A crawl already in the index is not crawled again by this release; a page stored nearly empty
before the HTML parser's correction of 12 October (27.6) stays so until the operator crawls its domain again on the crawl
page (URL, collection, pages per domain and depth; pages older than three days are reloaded).

### 37.6 Tests

`MatchingTest.anEarlierReadingOfAPageReadAgainIsNoMatchingInput`, `anEarlierOfferOfAPageReadAgainIsNoOffer`,
`aPageGoneFromTheIndexKeepsItsReadings`, `pagesNamingTheSameServiceAreOneOffer`, `aServiceNamedAsTheProductIsItsOffer`,
`aServiceNamedAsTheGeneralProductProvesNoSpecificStack`, `offersWrittenWithVerbsAreOffers`;
`LlmServiceTest.serviceAndReferencePagesKeepPlacesOverTheCap`, `technicalResourcesTakeNoPlace`;
`CrawlStartRecoveryTest.crawlStaysOnItsStartHostAndKeepsItsMarker` (technical resources and documents are not crawled, pages
with similar words are).

## 38. Own use in the clause of its verb (0.12.2, `BusinessSignals.VERSION` 4)

### 38.1 Finding (production, 24 October 2026, read only)

After 0.12.1 the matching read only current readings; of those in stackfinder-web, 58 were own use and 2 planned migrations.
Judged from the provider's view, about a quarter of them were own use. One sentence of STELLWERK's blog, "Für SAP-Anwender in
Finanzbuchhaltung & Controlling stellt sich die Frage: Was müssen wir jetzt im System umsetzen, um diese Chancen zu nutzen?",
made STELLWERK the customer of 214 SAP providers. The reader took a use verb and "wir" anywhere in the sentence together with
any product of it.

### 38.2 What the reader accepts as own use

- **One clause** (`ownUseProducts`, `CLAUSE_END`): its use verb (`OWN_USE_VERB`), the product and "wir"/"unser" or the company's
  name stand in one clause. Clauses end at a colon, semicolon, question or exclamation mark, a dash and a comma before a
  subordinate or contrasting clause ("…, um diese Chancen zu nutzen", "Teams, die Azure … nutzen", "…, was SAP bereits bietet",
  "…, auf der Unternehmen … betreiben"); a list of names keeps its commas; a relative clause of the company's own ("…, die wir
  intern nutzen") belongs to its antecedent. Only the products of such clauses get the context.
- **Own systems or data** ("auf unsere SAP-Daten zuzugreifen", "unser SAP-System") are own use without a use verb.
- **None:** a question (an FAQ, a rhetorical question to the reader); advice to the reader ("Wir empfehlen die Umstellung … einzuplanen"),
  also as a planned migration; a row of delivery verbs ("Wir entwickeln, modernisieren und betreiben … auf Microsoft Azure",
  "Wir planen, migrieren und betreiben …", an offer); the noun "Nutzen" (German use verbs in lower case only); a product as the
  subject ("Salesforce verwendet nach eigenen Angaben …", "SAP is running"); "lässt sich … nutzen"; others as the subject
  ("Zahlreiche Energieversorger arbeiten …"); a product's technology ("Unsere Lösungen basieren auf … und nutzen …"); examples of
  models and services; a list of three or more platforms worked with; "Azure-eigene" (no own word); "don't/don’t" (negation);
  "Bestandskunden" (customers' projects).
- Unchanged: a client's voice on its provider's page stays the client's (the matching's `customerVoice`), and a provider of the
  product with an own-use statement of its own can be a customer of other providers (regio iT, "Wir betreiben eine SAP
  First-Strategie").

### 38.3 What an existing graph does

Nothing is extracted again: the matching re-reads every stored quote with the current reader (`contextConfirmed`) at its cycle
and at reading, so suggestions resting on a reading the reader no longer makes disappear at once. The stored observations keep
their context as history; pages extracted after the update get the new contexts.

### 38.4 Tests

`BusinessSignalsTest.ownUseOfTheProductionReadings`: every current reading of own use and planned migration of stackfinder-web of
24 October 2026 (15 own use, 26 none, the client's voice of dab), plus the existing reader, matching and documented-relation tests.

**Correction (0.13.0).** In 0.12.2 only own use was bound to the clause of its verb; a planned migration was still read on the
whole quote (the migration verb, the plan and the products anywhere in it). 0.13.0 binds it as well (section 39.6).

## 39. The declared audience in the clause of its address, one client per site, offer items (0.13.0, `rule/9`)

One re-extraction of every tracked page carries the rule changes of 0.13.0 (`RuleExtractor.VERSION` 9, `Vocabulary.VERSION` 5,
vocabulary package 7: the categories' `organisations`, the products' tree). The LLM tier is not asked again (section 40).

### 39.1 Finding

A declared audience (`target_category`, `target_industry`) was read from a window of 320 characters after a marker ("wir
beraten", "wir helfen", "designed for"), across sentence, section and footer ends, with the category terms of every other
vocabulary. Those terms name what a provider offers or uses, not a kind of customer. A building company's contact page,
"Rufen Sie uns an, wir beraten Sie gern: … Powered by WordPress", became a target of CMS providers, and the older derivation
of suggestions (`DerivedService.suggestedCustomers`) offered it to them as a customer. The skip of the page's own
vocabularies depended on the configuration and lost the valid case ("Wir beraten Softwareunternehmen" on a software
provider's page).

### 39.2 The shared reader `Audiences`

`extract/Audiences.java`, used by the rules, by the mapping of the LLM tier's values and at reading:

- **The clause of the address:** after a marker of an address ("wir unterstützen", "wir beraten", "Lösungen für", "designed
  for", a heading "Für wen", "Zielgruppe") the audience is read up to the clause's end (at most 150 characters; a sentence's
  end, a list's end, a footer). "wir beraten Sie gern" addresses the reader: no audience unless the clause names
  organisations ("Sie als Pflegedienst"). A negated clause is none ("nicht nur … sondern auch" is no negation). A goal that
  names whom the provider serves is an address too ("Zufriedene Eigentümer sind unser wichtigstes Anliegen", "happy patients are
  our priority"): its group is read, and the excerpt ends with the goal so that it is confirmed again (gate A2: an owners'
  audience of the finding was written so).
- **An organisation's word as its head:** a phrase is an audience if it ends with a general word for organisations or for
  people of a role (Unternehmen, Betriebe, Einrichtungen, Dienste, Büros, Praxen, Kanzleien, Agenturen, Händler, Hersteller,
  Anbieter, Dienstleister, Träger, Kommunen, Vereine; companies, firms, providers, agencies, practices, manufacturers,
  organisations), a term of `customer_types` or a vocabulary's `segments`, or a category term that itself names organisations
  ("pflegedienst"). This is language knowledge in the code, not industry knowledge.
- **The field from the vocabulary:** the category comes from vocabulary terms inside that phrase, also as the modifier of a
  compound ("WordPress-Agenturen" → `software/cms`), or after the organisation's word and "aus" ("Unternehmen aus dem
  E-Commerce", "Kunden aus dem Bereich Entsorgung", "clients from the energy sector"; at most three words between,
  none a preposition: "Unternehmen aus der Region bei Cloud-Projekten" names no field). Where no category term names a field's organisations, a vocabulary's
  optional `organisations` give a `target_industry` without a category ("Softwareunternehmen" → 62, "Bauunternehmen" → F).
- **Every vocabulary is read**, the page's own too; what decides is the named relation. A service or product term without an
  organisation's head (Netzwerk, Cloud, Sanierung) and a website's technology are no audience.

`customer_type`, `audience_segment` and `company_size` keep their reading inside the reader's clause.

### 39.3 What an existing graph does

- **Rule tier:** the re-extraction replaces every page's rule evidence; a target statement whose only evidence was such a window
  is deleted with its last evidence. A statement with valid evidence elsewhere keeps it.
- **LLM tier:** the derivation (`DerivedService.Graph.read`), the business view and the reasons of the older suggestions use a
  stored audience fact only while one of its current pieces of evidence, read again with `Audiences`
  (`read/AudienceEvidence.confirmed`), still gives that value. Evidence that fails stays stored as history and has no effect.
- **Stored suggestions of the older path:** a contribution whose basis statement is stale or no longer confirmed by the reader
  counts as unavailable ("supporting statements are no longer fully available"), also when the older pass is capped.

### 39.4 The older path: audience fit

`DerivedService.suggestedCustomers` and `suggestedPartners` answer another question than the matching: A declares an audience,
B belongs to it. They stay (they are the only suggestions where no system or need signal exists) and say what they are: the
`rule` stays `legacy_industry_or_partner` (contract), `rule_version` is `legacy-2` for rows of 0.13.0, the `evidence_kind`
`declared_audience`; the UI calls such a contribution *audience fit* ("the declared audience of A meets the industry of B;
no evidence of a need") with the audience's quote, and ranks it below every contribution of the matching of the same pair.
The business view shows the target category beside the target industry (an edge of the network, too).

### 39.5 One client per provider site

- A client named by a provider's project profile, reference heading or customer's voice (`BusinessRules.references`) gets the
  resolver key `client_name`: its name without the legal form (`Normalizers.clientKey`), scoped to the registrable domain of
  the provider's page. The same customer's voice on five product pages of one provider is one client; the key never spans two
  providers. It is no discriminator: a different legal form of the same name ("Muster GmbH", "Muster AG") is another company.
- The name of a client in a profile written on one line ends before the next field (`. <Label>:`, "Kunde: Beispiel AG.
  Systemgröße: ca. 350 User."); `& Co. KG`, `& Co. KGaA`, `GmbH & Co. KGaA`, `B.V. & Co. KG` and their kin are legal forms and
  never split a name.
- An anonymised client is no client: a phrase without a legal form whose last word is a general word for an organisation and
  that names no proper name ("Städtisches Versorgungsunternehmen", "Führender deutscher IT-Dienstleister"). Its project stays a
  reference of the provider without a client.

### 39.6 Planned migration in the clause of its verb (`BusinessSignals.VERSION` 5)

As own use since 0.12.2 (section 38): the migration verb, the plan (or the support sought) and "wir"/"unser" or the company's
name stand in one clause (`plannedMigrationProducts`), and only that clause's products are the migration's. A relative clause
of the company's own ("…, die wir 2027 ablösen wollen") belongs to its antecedent.

### 39.7 Offer items without a category; page labels

- An item of an offer list under an offer heading on a line of its own ("Leistungen", "## Unsere Leistungen", "Our services:";
  one to four words, at most twelve per page) that matches no category is a service with its name, without a category, flagged
  `uncategorized` (`offer_item`), structural and hedged (confidence 0.4, uncertain). A page label ("Kontakt", "Referenzen")
  ends the list; a date, a question and a posting are no item. No fuzzy mapping gives it a category; the matching ignores it,
  the business view lists it as *without a category*, and the quality metric `uncategorized_service_names` lists such names per
  collection as the input of the vocabularies.
- A label of the site's pages ("Kontakt", "Über uns", "Referenzen", `PlaceEvidence.PAGE_LABELS`) ends an offer list and is never
  such an item. The name of a section that never names an offer (Kontakt, Über uns, Impressum, Datenschutz, Referenzen, Jobs,
  Karriere, `PlaceEvidence.SECTION_LABELS`) is no service of the LLM tier either (`entity_page_label`); a label that may name an
  offer ("News", "Downloads", "Service") keeps the rule of 0.12.0: a name the content offers stays a service even if the menu
  shows it too.

### 39.8 Repeated text counts once

A text a site repeats on many pages (a footer, a customer's voice) is one piece of evidence: the business view counts evidence
per registrable domain and normalised excerpt ("1 piece of evidence on 7 pages", `evidence_count` beside `sources`). No
confidence, quality or score grows with repetitions.

### 39.9 Tests

`AudiencesTest` (the reader: clauses, the reader's address, heads, compounds, `organisations`, roles and lists, every
vocabulary), `DeclaredAudienceTest` (the derivation, the reading and the older suggestions with unconfirmed or stale bases,
`legacy-2`, `declared_audience`, the order of contributions), `ClientIdentityTest` (one client per site, two providers and two
legal forms, profiles on one line, anonymised clients, the key), `BusinessSignalsTest.aPlannedMigrationInTheClauseOfItsVerb`,
`BusinessSignalsTest.theProductTree`, `MatchingTest.theProductTreeKeepsProductMatchesAndAddsModuleMatches`,
`MatchingTest.aProductProvesNoModule`, `BusinessViewTest` (evidence counted once per domain and excerpt).

## 40. A place needs a place in its context; the replay of stored answers; current lists and two times (0.13.0, `llm/5`)

### 40.1 Finding

The LLM tier published menu items of a building company's navigation ("Hochbau", "Logistikbau", "Referenzen") as facilities:
the validator checked that the name stood in its quote, and a navigation line contains every name of it. Such entities stayed
in the host's list, the type filter and the counts; an entity whose only page had left the index was listed by its host too.
The UI showed "last confirmed" for the time of the source's crawl, so a reading of today could not be told from a crawl of a
month ago.

### 40.2 `PlaceEvidence`: the check of a facility or a site

`extract/PlaceEvidence.check` decides about a proposed facility or site with the page's text (`PLACE`, `UNCLEAR`, `REFUSED` and a
reason):

- **Refused:** a page label (`entity_page_label`); a generic noun alone ("Gebäude", "Standort"; not with its number: "Haus 3" is
  a name, though no proof of a place by itself, "Halle 7" may be a fair's) or the name of an activity or a
  field of work without a place word, unless the name is an address or heads an address outside a menu: a street with its
  number right after it in its segment, or the name alone in its segment and the next lines a street with its number and a
  postal code ("Hochbau", "Logistikbau", "Gebäudetechnik", but not "Burgstraße 30, 12345 Musterungen", "Muster-Erziehungsberatung
  Beispielweg 15. 12345 Musterstadt." or "Muster Zentralverwaltung\nMusterstraße 106\n12345 Musterstadt"; a word of a longer
  name heads nothing: "Hochbau" of "Muster Hochbau GmbH" above the company's address; `entity_facility_not_a_place`); a sentence
  of the page as a name (four words or more, two words of four letters or more in lower case, ending with ".", "!" or "?",
  without an address in the name or the quote and heading none: not "Geschäftsstelle des Muster e.V."; the same reason); an entry that stands only in a site's menu (a short segment among at least
  two short neighbours, one of the two segments before or after it the label of a section: "Leistungen | Newsletter | Kontakt")
  and is no name (neither a place word nor a proper name), or a name nowhere on the page (the same reason);
  on a project or reference page (by its address) the building of a project, unless a passage names who runs it ("betreibt",
  "unser", a named organisation that plans or opens it), the name is an establishment of the operator ("Niederlassung
  Rostock", "Hauptsitz", "Filiale", "Geschäftsstelle": a project is never one) or begins with the name of the page's domain,
  with a place word or a proper name ("Muster Smart Factory", "Muster Hamburg" on muster.de: the operator's branch a reference
  names; `entity_facility_project_object`; a site with its address stays a site); in a field whose providers run the places they
  name (the vocabulary's `"operates_places": true`, care; every vocabulary of the page's collections must say so) a project page
  is no sign of a project at all: a care provider lists its own homes there ("Projekte": "Pflegeheim Musterstadt „Am See“ … als
  neuer Standort"), and its names are decided as on any page (a proper name without a sign of a place is unclear); the name of
  a service the rules found on the page without a sign of a place (`entity_facility_service_name`).
- **A place:** a quote that gives the place's full address, a street with its number and a postal code, outside a menu line
  ("Suchtberatung. Musterweg 28. 12345 Musterstadt.", "Verwaltung. Musterstraße 9. 12345 Musterstadt."), also of an activity's
  name and on a project page, where the operator's own places are given with their address while a reference names a building
  with its town; a name that is an address ("Musterweg 5, 34117 Kassel"); a place word with a proper name ("Haus Lindenhof",
  "Seniorenzentrum Am Park", "Hauptsitz Kassel", "Niederlassung Kassel", "Seniorentagesstätte Musterstadt"; the first word ending
  with a place word is the place word, a later one a proper name: "Seniorenzentrum Musterheim"; a person's name joined to a place
  word is a proper name: "Paul-Gerhardt-Haus"; the place words include care's places by their kind: "Wohngruppe", "WG",
  "Pflegedienst", "Beratungsstelle", "Spital", "Centrum", "Apotheke", "Treff", "Tagesgruppe"), also in a list of a site's places
  and in a menu;
  an occurrence outside
  a menu, or the model's quote outside a menu line, with an address (a street with its number, a postal code with a place) or a
  verb of a place ("betreibt", "befindet sich", "liegt", "eröffnet"; "unser Werk in …"); an imprint's line with bullets is no menu
  line when it holds a postal code ("Muster GmbH • Musterkamp 33 • 22607 Hamburg"); an entry of a list on a page of the site's
  places by its address (`/standorte/`, `/einrichtungen/`, `/filialen/`, `/locations/`, …).
- **Where the name stands:** in the page's text without the characters of format a word may hold (a soft hyphen, a zero-width
  space: the model's quote was checked without them; gate A4 of 0.13.0, run 9: a care operator's homes whose town the page
  hyphenates softly), its words in order with any separators between (at most eight characters: a comma, a line, a
  period, Markdown's marks of a heading): the model's "Musterweg 5, 34117 Kassel" is found where the page writes the street and
  the place on two lines, "Muster parc Musterstadt" where it writes "Muster parc.\n\n#### Musterstadt."; a name not found so is
  looked for by the model's quote, which is grounded in the page. (Gate A4 of 0.13.0: with literal matching, most addresses of
  the production pages counted as "not on the page" and were refused; in the eighth run, a care operator's homes written as a
  heading over their town.)
- **A list is no menu.** Short entries without a section's label beside them (a site's places, "- Muster Pflege Altstadt",
  "Musterstadt.", an imprint's line) are read as running text: a proper name there without a sign of a place is unclear, not
  refused. (Gate A4 of 0.13.0, third run: lists of a site's branches and imprint lines read as menus held about half of the
  places the check refused in the care collection.)
- **A menu lists places too.** A name that stands only in a site's menu ("Unsere Häuser | Haus Lindenhof | Kontakt",
  "Mustersonne 3": a word written as a name with its number) is a place by its own words (above), else kept unclear and marked:
  never confirmed by the menu, never refused for it. (Gate A4 of 0.13.0,
  sixth run: of the refused facilities of the care collection only about two in three were artefacts; most of the rest were a
  care operator's homes, group homes and services listed in its menus, and places given with their full address. Eighth run:
  still about two in three in the care collection, most of the rest a care operator's own homes on its project pages, names
  written across a heading's marks and offices of counselling with their address; corrected above. Ninth run: about three in
  four; the rest a care provider's homes in the menus of its project pages without a sign of a place, places whose address
  follows on the next lines, names with a soft hyphen, numbered houses of a menu; in the software collection the operator's
  branches its reference pages name; corrected above.)
- **Unclear:** a proper name outside a menu without any of these, and a name only a menu lists. It is kept as published and
  marked (40.5).

The validator of fresh answers refuses what the check refuses (the reasons are codes of the LLM tier's counters and samples; the
mark of 40.4 is the validator's, so the model quality check rates no such statement);
`LlmExtractor.apply` checks every accepted answer again with the page's text and address, the fresh ones, the cached ones and
the replayed ones, so an old cache entry can never bring a refused place back. The compiled-in prompt defines a facility as "a
named place of business or care, never a menu item" (a new prompt hash: new and changed pages are asked with it); the
validator, not the prompt, decides.

### 40.3 The replay of the stored answers (`llm/4` → `llm/5`)

- `LlmExtractor.VERSION` is 5, `LlmExtractor.REPLAYS` = {4}: a version whose change is the validation only. The prompt hash and
  the cache key carry the version of the question (`LlmExtractor.QUESTION_VERSION` 4), which such a version keeps: a cached answer
  is used and validated again by `apply`. (0.13.0 changes the prompt's definition of a facility and the products' vocabulary, which
  are part of the prompt hash: new and changed pages are asked with the new prompt.) `reexamineIfChanged`
  no longer makes the documents to do again for it (a version outside `REPLAYS` still does); it sets `kg_meta`
  `llm_validation` to `pending` and records `llm_validation_queued`.
- `LlmReplay` walks the pages with tier-3 evidence of a replayed version (active, marked) in row order. For each, the answer is
  rebuilt from that evidence: every entity with its type, name, kind and quote, every relation and value with its quote and
  position (`text:<at>+<length>`); a quote clipped or with a person's name redacted for the evidence is read back from the
  page's text at its position where the text agrees with the excerpt. What `apply` derives itself (evidence by the page's title,
  industries of services, the marks of 40.5) is not rebuilt: it is derived again. A relation or value about an entity that is
  neither an entity of the answer nor a known entity of the page any more is dropped and counted.
- The answer is applied with the page's text, address and known entities as a reading of the page applies an answer now, in a
  replay's context (`ExtractContext.replay`): the checks of `llm/5` apply (places, the names of sections), a service and an offer
  keep the evidence they were published with under the rules of their reading, and every item keeps the locator of its evidence
  (a service by its page's title gives the operator's offer again). The replay is a change of the validation of places, not a
  stricter reading of older rules (gate A4: the offer proof of 0.12.0 applied to answers published before it would have dropped
  three in four services of the LLM tier, most of them real). It is then published by `Publisher.applyLlmReplay`: the page's tier-3 evidence is replaced, its mark (status, reason) stays, the cache is
  not written. The evidence names `llm/5` with the model and the prompt hash of the answer: the provenance stays.
- **Order:** a page whose rules have not read it under the current rule version (its input hash) waits for a later pass, so the
  rules' entities of `rule/9` are the known ones. The replay asks no model; neither the circuit breaker nor the schedule holds
  it. It ends when a pass leaves no page (`llm_validation` = 5, `llm_validation_done` with the counts).
- **Effect:** a refused place loses its tier-3 evidence; statements without evidence are deleted, entities without statements
  too. The `llm/4` observations stay as history and are no longer current.
- **Status:** `llm.validation` (state, pages left, replayed, statements, `placesRefused`, `typeUnconfirmed`, `unmapped`,
  `quotesRestored`, `deferred`). Model requests: none.

### 40.4 Places whose type is unconfirmed

An unclear place (40.2) gets a hedged statement `type_unconfirmed` (its type as the value) beside its name: quality uncertain.
The mark qualifies its name and takes none of the page's limit of statements (`scoutro.kg.extract.maxStatementsPerDoc`, also
over the continuations of a long page): on a full page it displaces no item an earlier release published, and a name the
limit cuts carries none (found by the replay of gate A4). A facility or site no source confirms (the mark is current, and no current name of it comes from another tier or from a page
without the mark) is left out of the filter by its type and of its count (`include_unconfirmed=true` shows it). The review list
is `GET kg/entities?review=type_unconfirmed`, with `review`: the numbers per collection and model. Quality metrics:
`facilities_type_unconfirmed`, `sites_type_unconfirmed`. A judged rule correction, never a hand edit, resolves them.

### 40.5 Current lists

`KgReader.entities` (lists, the host list, the type filter, the name search and their counts) holds by default only entities
with a current statement (`currentStatement`), so a list agrees with the status of the entity's own view; the host filter reads
the host's current pages. `include_stale=true` (or the quality filter `stale`) shows the others.

### 40.6 Two times

Every entity, statement and fact carries `source_loaded_at`, the newest crawl of a current source (`last_confirmed` is the same
time, kept for the contract), and `extracted_at`, when its newest evidence was extracted (`kg_evidence.observed_at`, of a current
source first), with `extractor` (`name/version`) and `model` of that evidence (no model for agents). The UI shows "Source read:
30.09.2026 · Evaluated: 24.10.2026 (llm/5, model …)".

### 40.7 Tests

`PlaceEvidenceTest` (the check: menu items, page labels, generic nouns, activities, project pages, service names, addresses,
verbs, unclear names; `refusedPlacesOfTheSixthRun`: a menu's places by their kind or a person's name, a quote with the full
address, a numbered house, the operator's establishment and its named place on a project page; `refusedPlacesOfTheEighthRun`:
a care provider's own homes on its project page and a builder's built ones, `ExtractContext.operatesPlaces`, a name across a
heading's marks, an activity's name with its street and number, a day group; `refusedPlacesOfTheNinthRun`: a soft hyphen, an
address on the next lines and a longer name's word or a menu above an address, the operator's branch and a client on a
reference page, a numbered house of a menu, a care provider's home in the menu of its project page), `CollectionProfilesTest`
(`operates_places` read, reported when invalid, never part of the vocabulary version), `LlmExtractorInvalidReasonTest` (one case per code), `LlmReplayTest` (test 26: a version in `REPLAYS`
asks nothing and keeps the mark, a version outside examines again; test 22: a replay twice and a cached answer with the artifact
bring nothing back; test 23: the menu facility is gone, the place and its service stay, the unclear name is marked; the quotes
read back), `CurrentListsTest` (test 23 lists and review, test 24 current lists and `include_stale`, test 25 the two times).

## 41. A provider is one organisation by its `@id` on its own domain (0.13.0, `jsonld/7`)

### 41.1 Finding

Read-only diagnosis on production data (gate A3): providers existed twice, their offers split between the two, for two general
causes.

1. **The site's own organisation without `url`.** The JSON-LD `Organization` of a site is often known only by an `@id` at the
   root of the site's domain (`https://www.muster-beratung.de/#organization`, the WebSite's `publisher`), with no `url` or
   `sameAs`. `JsonLdExtractor` read the site's own organisation from `url` and `sameAs` only (6.1), so it was not the domain's
   operator: the rules' unnamed operator of the services pages (`domain_operator`) and the structured data's organisation were
   two organisations.
2. **Two `@id`s of one organisation.** A site's language version names its organisation by a second `@id`
   (`https://www.muster-software.de/#organization` and `https://www.muster-software.de/en/#organization/`). Two values of
   `ld_id` on one host are a discriminator (4.3), so the second one never joined the first, nor took its keys.

Merging organisations can hardly be undone in production; both rules are therefore general, narrow, and in doubt keep two
organisations apart. The update's merges are listed per collection before the release by a read-only gate (the private package
repository; nothing is merged there).

### 41.2 Rules (`OrganisationIds`, shared by the resolver and the gate)

- **The root `@id` of the own domain** (`OrganisationIds.root`): an organisation of the structured data without a `url` whose
  `@id` lies on the page's own registrable domain and whose path holds language segments only (`/`, `/en/`, `/de-at/`), is the
  site's own organisation: as an organisation whose `url` is the site, it is the declared operator with a legal name
  (`site_operator`), else the domain's operator (`domain_operator`). Only if it is the one organisation of a root `@id` the
  page's blocks describe: two of them (the site's `#organization` and `#/schema/Organization`) leave both apart; a partner's
  node under a fragment of its own (`#beispiel-ag`) is no root `@id` (below). An `@id`
  below the root (a company's profile on a portal, `/firmen/alpha/#organization`) is not; a node under `brand` or `manufacturer`
  never is (35).
- **One organisation of two `@id`s** (`OrganisationIds.canonical`): two `@id`s of one host that differ only by language segments
  of the path (ISO 639-1, with or without an ISO 3166-1 region: `en`, `de-at`, `en_US`) or by a fragment that names the
  organisation's node (`#organization`, `#organization/`, `#Organization`, `#identity`, `#/schema/Organization/base`, none) name
  one organisation; scheme, host, the rest of the path and the query must be equal. A fragment of another node names another
  (`#brand`, a contact record `#/schema/com_contact/contact/1`, a partner's `#beispiel-ag`, a client's `#kunde-muster`).
  They are no discriminator against each other (`IdentityResolver.compatible`, `.mergeable`) only if `OrganisationIds.apart`
  finds them one:
  - a name in common (`Normalizers.key`; a legal name or an alias in common is not enough: the national companies of a group
    share them, "Muster Balkone Schweiz" with the legal name of "Muster Balkone GmbH"), else `names_differ`;
  - no two legal names that differ (`site_operator`, `operator_name`), else `legal_name_differs`;
  - no two legal forms that differ, else `legal_form_differs`;
  - no other discriminator that differs (a register entry, a VAT number, an LEI, a full address …), else its scheme.
- **An `@id` finds no other by itself.** Two `@id`s of one organisation meet only through another key of the mention (the
  domain's operator, a legal name, a strong identifier); the `@id` itself is looked up as written. Two companies of one name
  on one portal, each by its own `@id`, stay two.
- Unchanged: different registrable domains never meet (every key but the strong identifiers is scoped by the domain or the host);
  strong identifiers stay global and at most one value per entity; a blocked merge is an `identity_conflict` event.

### 41.3 The update

`JsonLdExtractor.VERSION` 7 comes with the re-extraction of `rule/9` (39), which reads every tracked page once anyway: no
second pass. A provider split before (its language version or its structured data's organisation apart from the domain's
operator) is joined when one of its pages is read again: the older entity survives (4.3), the other one redirects to it, its
offers, clients and evidence move with it, and the matching and the derivation recompute in their hourly cycles. No model
request.

### 41.4 Tests

`OrganisationIdsTest` (the organisation of an `@id`: language segments, fragment, scheme, another domain, the root; when two are
one, and every reason to keep them apart), `OperatorIdentityTest` (invented sites: a language version with a second `@id` is one
organisation, never one of another domain; a provider split before is joined at the next reading of its page; the root `@id`
without `url` is the domain's operator, in either order of the pages; a partner's node under its own fragment is no root `@id`,
two organisations of the site's node on one page are neither operator; two companies of one name on a portal stay two and are
not its operator; a contradicting VAT number or legal name blocks the merge; a country page that shares only the legal name, and
a brand's node of the same name, stay apart).

## 42. A re-extraction reads every page, also beyond the work queue (0.13.1)

### 42.1 Finding

A new extractor identity makes the next start re-extract every tracked page (the paragraph on new versions in section 6): the
reconcile run enqueues each unchanged page for it (`PRIO_REEXTRACT`, behind new pages). `WorkQueue.scanned` drops what does not
fit `queue.maxItems`, and on a graph that tracked more pages than that the scan dropped the last pages of the ID order. The run
completed anyway: it removed `reextract_pending`, and the run that followed for the full queue (`queue_full`) enqueues only pages
whose Solr document changed. The dropped pages kept the former rules until their page changes. The replay of the LLM tier
(40.3) waits for a page's re-extraction under the current rules and therefore never ended on such a graph (`llm.validation`
`pending`, `pagesLeft` still, `deferred` growing every pass), and while it is due the normal work of the LLM tier runs only in
the pause between two passes.

### 42.2 Rules

- **The scan waits for room** (`Reconciler.scan`): in a run that reads unchanged pages again (`extractor_changed`, a job recheck),
  a page of that kind that the queue cannot hold ends the scan's page before it; the cursor stays there and the scan goes on once
  the worker made room. If not even the first such page fits, the step waits (`reconcile.scanWaitsForQueueRoom`). New and changed
  pages are enqueued as before (a dropped one comes with the `queue_full` run, which sees it changed).
- **A re-extraction that lost a page stays ordered** (`Reconciler.complete`): a run that still dropped a page to re-extract keeps
  `reextract_pending` and the job recheck; the next run reads again (`reextractDropped` in the run's status).
- **The record of the full reading** (`kg_meta.reextracted_extractors`): a completed backfill, or a re-extraction that dropped no
  page, records the extractor identity under which every tracked page was read. A start under an identity without that record
  and without a re-extraction ordered checks every page once (`SyncService.init`, event `reextract_check`): the input hash
  decides, so a page whose input is unchanged is only read from Solr, a page left out is extracted again.

### 42.3 The update

The first start of 0.13.1 on a graph of 0.13.0 finds no record and checks every page once. No new extractor version, no model
request; the replay ends once the pages it waited for are read.

### 42.4 Tests

`SyncServiceTest.aReextractionLargerThanTheQueueReadsEveryPage` (seven pages to read again and room for two in the queue: every
page is read, none dropped) and `SyncServiceTest.pagesAnEarlierReextractionLeftOutAreReadOnceAtTheNextStart` (a graph without the
record: the two pages left out are extracted again, the others only checked, the next start checks nothing); without the change
the first leaves five pages with the former input, the second both.

## 43. The speaker's own business, a topic and a referral are no declared audience (0.13.2)

### 43.1 Finding

The reader of 0.13.0 reads every vocabulary, the page's own too (39.2). A provider's own word for itself then reached its target
categories and industries: a care service's "Für wen eignet sich unser Pflegedienst?", "Wir helfen Ihnen als ambulanter
Pflegedienst", "Leistungen im Pflegedienst" or "Fragen rund um das Thema Pflegedienst" became the target category of ambulant
care. The older path (39.4) then offered other care services to it as customers: its own category met theirs. The measurement after
the update found such audience fits mostly implausible in the care collection, and single ones in the construction collection (a
heating firm's "unser Heizungsbetrieb", a referral to an energy adviser).

### 43.2 Rules (`Audiences.VERSION` 2)

For a target category and a target industry (not for customer types, segments and sizes, whose reading stays the one of 0.13.0:
"unsere Bauherren" are the speaker's clients), none of these is an audience:

- the word after a possessive of the first person ("unser Pflegedienst", "in unserer Pflegeeinrichtung"; at most two words
  between); an offer for the speaker's business is the voice of a client and stays ("Software für unseren Heizungsbetrieb");
- a place after "im" ("Leistungen im Pflegedienst"); after a marker or a preposition of an addressee it stays ("Software für den
  Einsatz im Pflegeheim");
- the clause whose object is the speaker ("speziell für uns");
- the speaker's identity ("wir sind ein ambulanter Pflegedienst", "wir sind stolz, eines der ersten …");
- the role in the nominative after the reader's dative ("Ihnen als ambulanter Pflegedienst" is the speaker; "Ihnen als ambulantem
  Pflegedienst" and "Sie als Pflegedienst" are the reader);
- a topic ("Fragen rund um den ambulanten Pflegedienst", "das Thema Pflegedienst"); the reader's own stays ("rund um Ihre
  Pflegeeinrichtung");
- a referral or a comparison ("vermitteln Ihnen den passenden Energieberater", "Vermittlung eines ambulanten Pflegedienstes",
  "Leistungsvergleich der Pflegedienste"); a word that only holds one is none ("Wissensvermittlung").

A topic, a referral and the identity reach at most five words, not across a comma or a conjunction, and never past a preposition
of an addressee ("Fragen rund um Software für Pflegedienste", "Vermittlung von Fachkräften für Pflegeeinrichtungen" stay). The
services built on a category's own term name no organisation ("Pflegedienstleistungen"; "Pflegedienstleitungen" and
"Pflegeheimbetreiber" do). Language knowledge in the code, no industry knowledge: the words come from the vocabularies.

### 43.3 What an existing graph does

No re-extraction: the reader's version is no part of an extractor identity (39.3). Stored evidence is read again with the current
reader wherever an audience takes effect (`read/AudienceEvidence.confirmed`): in the derivation, in the business view and in the
reasons of the older suggestions. A target statement whose evidence is only the speaker's own business stays stored as history
and has no effect. A stored suggestion of the older path is shown per stored reason (`Suggestions.legacyItem`: one contribution
per reason); a reason that rests on such a statement is left out at once (`Suggestions.groups` leaves out a contribution whose
basis is not confirmed), so a suggestion with a second, confirmed reason stays shown with that reason, and one without is no
longer shown. A pass of the derivation computes it again where the confirmed rest of its basis still meets; only a complete pass
deletes the others: a pass of a graph above its limits (200 000 statement scopes, entities or page links, or capped rows)
deletes nothing (`DerivedService.apply`), the rows stay stored and hidden. The graph does not grow. (Corrected in 0.14.0 after
the rollout of 0.13.2: the first text counted rows, not reasons; since 0.14.0 a row naming a merged entity goes also in an
incomplete pass, 44.)

### 43.4 Tests

`AudiencesTest.theSpeakersOwnBusinessATopicAndAReferralAreNoTarget` (neutral texts with the structure of the measured cases: the
own business, the place, the identity, the nominative after "Ihnen als", the topic, the referral and the comparison name no
target; the reader's business, an addressee after a topic or a referral, the role after the term, the client's voice and the
customer types and segments stay; stored evidence of the own business is not confirmed again). Without the change the first group
gives targets. The other tests of `AudiencesTest` keep their results.

## 44. Names, clients, facilities by their kind, the matching's follow-ups and the clean-up (0.14.0, `rule/10`, `jsonld/8`, `llm/6`)

### 44.1 Finding

A count on production data, read only, of the organisations, clients, partners, suppliers and places named like a term of
the vocabularies, judged line by line (the private package repository, `docs/SCOUTRO-PLAN-0.14.0.md`):

- **A word of the vocabularies as an organisation:** list items after a relation marker ("Mitglied im Netzwerk", "Partner:
  Maler, Fliesenleger und …"), a compound split at "und" ("Garten- und Landschaftsbau" gave "Landschaftsbau"), the region of
  "X und Umgebung", JSON-LD organisations and the LLM tier's organisations named "Pflegedienst", "Mittelstand" or "Senioren".
  The brands of the products (AWS, Shopware, Google Cloud, Shopify, KNX, WordPress, TYPO3) are no such word, and a firm whose
  name equals a vocabulary word only without its umlauts ("Turen" against "Türen") is a firm.
- **Clients:** a heading of references read "… bei X" as a client also for a phrase ("Hilfe bei kaputtem Kessel"), a
  common noun, the reader ("bei Ihnen") or a place ("Musterdorf bei Musterstadt"); a client field ran on into an address, a
  representative or the next field; placeholders ("auf Anfrage", "N.N.") and markdown marks of a project's name were kept; a
  client named by a description ("Dienstleister für Musterabrechnungen", "Energieversorgungskonzern in Musterland") became an
  organisation and a candidate of the matching.
- **Places of the LLM tier:** a facility named only by its kind ("Tagespflege", "Betreutes Wohnen") was one entity per page,
  without its place and its provider; a name's words were joined across blocks of a page.
- **Matching (after M1 of 0.13.1):** the provider's work for others ("Systeme, die wir migrieren") read as its own use; a
  product named as what an offer replaces ("Alternative zu …") as a use; the vendor of a product as its client; the reader's
  lines below an FAQ heading as the company's use, plan or need.
- **Audiences:** an audience phrase ("Software für Architekten") gave the provider the audience's category; a provider naming
  itself in the third person ("Der Pflegedienst Muster unterstützt Sie") its own category as a target (43, known limitation);
  a word of another sense was a hit ("IT-Architekturen" of architecture).
- **Rows on merged entities:** rows of the derivation and contributions of the matching naming a merged entity stayed stored
  (invisible) where a pass was incomplete.

### 44.2 Rules

**A word of the vocabularies names no organisation** (`KgVocabularies.Snapshot.vocabularyWord`): a name that is nothing but a
term or a label of a service category, a group, an audience segment, a kind of organisation, a customer type, a company size,
an employment type or a need names no organisation, client, partner, supplier or region. It is compared by
`KgVocabularies.wordKey` (lower case, the umlauts written out, every run of other characters one space), so "Türen" and
"Tueren" are one word and "Turen" another. The names of the product vocabulary, its products and their vendors are names, never
words: `products.json` version 3 has the brands and the `vendors` of the tree's nodes, so they are in the product vocabulary
before the rule takes effect. It applies to list items after a relation marker (a list mostly of such words names no
organisation, the other items too), to clients, to "X und Umgebung", to JSON-LD organisations without a legal name other than
the own domain's operator, and to the LLM tier's organisations (`entity_organisation_vocabulary_word`, cached and replayed
answers too).

**One name is never split** (`BusinessRules.joinsName`, `splitNames`): not after the first half of a compound ("Garten- und
Landschaftsbau", "Heizungs-, Lüftungs- und Klimatechnik"), not in the list of a name's phrase ("Ministerium für Muster, Beispiel,
Probe und Test", "Büro für Muster und Beispiel"), not in the trades of an association ("Innung Sanitär, Heizung und Klima");
never at a line break, a full stop or ";". A word of an organisation with its name in quotes is a name ("Netzwerk „Muster
sicher“"). Several clients are split at "und", "&", ";" and " / " only between complete names (a part in lower case or a single
word before "&" continues the name: "Muster heat & power AG", "Hof & Garten").

**A client field ends with its name** (`BusinessRules.fieldValue`): before "vertreten durch", an address (a street with its
number, a postcode with its town), a field label after an asterisk, and a sentence end after a capitalised word of four letters
or more ("Ev. Kirche", "Ev.-Luth. …", "e. V." and "Dr." go on); the label of a former or later name is dropped ("… / heute:
…"). Placeholders ("auf Anfrage", "N.N.", "interner Showcase") are no client; a project's name keeps no markdown marks.

**"… bei X" names a client only as a name** (the owner's decision of 27 October 2026, `BusinessRules.clientsAfterBei`): in a
heading on a reference page or below a heading of references (the nearest heading above with fewer marks: "Referenzen",
"Kundenprojekte", "Success Stories", "Case Studies"). X is a name with a legal form or a proper name, cut before the words after
it ("E-Mobilität bei Beispielwerk: Neue Ladesäulen" names "Beispielwerk"); none for a phrase ("bei Bedarf", "bei defektem …"),
a common noun by its ending ("bei der Erweiterung …"), the reader, a question, a place after the name ("… bei Musterstadt am
Main"), an infinitive ("bei Muster zu sehen") or a part of a quoted title. "Muster AG setzt bei der Planung auf Beispiel" names
the client before "setzt".

**A client named by a description is a value of the provider** (item A1, `client_description`, `T_JSON`): no legal form and an
organisation's word as its head ("Dienstleister", "…konzern", "…verwaltung", "…-Gruppe") after adjectives or the first halves
of compounds, at its end or before a preposition and the rest ("Dienstleister für Musterabrechnungen", "Internationaler
Anbieter von Muster-Management"). The description, the project with its state and the products of its context are stored on
the provider; it is never an organisation, never merged, never a candidate of the matching. Public bodies and associations stay
clients ("Senatsverwaltung für …", "Verein für …", "Bayerische …verwaltung"; a description only with its place or after common
adjectives). The business view lists them under `audiences.client_descriptions`.

**Places of the LLM tier** (`PlaceEvidence`): only white space, or the marks the name itself writes, joins a name's words; a
heading's mark, a sentence end or other text ends it, also through the model's quote (the owner's rule of 26 October 2026, 40.2
known limitation).

**A facility named only by its kind is the provider's at its place** (the owner's decision of 27 October 2026,
`PlaceEvidence.kindOnly`, `placeOf`): a name that is a word of the vocabularies or a place word itself ("Tagespflege",
"Betreutes Wohnen", "Wohngemeinschaft"; a name ending with one, "Sonnenhof", is a name) is a facility only with its place: an
address after an occurrence of the name outside a menu (a street with its number, the postal code and town of the next lines;
or a postal code with its town), read at the model's quote first, else a town after it in its clause ("an unserem Standort
Musterstadt", "in Musterstadt"). The reader ("in Ihrer Nähe"), the provider describing itself ("Wir sind ein ambulanter
Pflegedienst in …") and a phone number are no place; without one the kind stays an offer (`entity_facility_kind_without_place`).
It is shown as "kind (provider, place)", the provider the page's operator ("Tagespflege (Muster Pflege, Musterstadt)"), and
keyed by the scheme `facility_kind`: the kind and the place (the full address, else the street, else the town) within the
registrable domain, a discriminator. Three day care facilities of one provider at three addresses stay three; the same one on
five pages is one.

**Matching** (`BusinessSignals.VERSION` 6, `MatchingRules`; the rules' version stays 1, the vetoes apply when the pairs are
read): the provider's work for others in a relative clause or with the reader as object ("Systeme, die wir migrieren, betreuen
und absichern", "migrieren wir Ihre Daten") and its choice for the reader ("wir nutzen die passenden …") are its offers (A2); a
product named as what an offer replaces ("Alternative zu …", "statt …", "anstelle von …") is neither used nor offered; the
source of a planned migration is no need of it. The vendor of a product (the `vendors` of its node and its ancestors, without
legal form and country) is no client of it (A3). Below a heading of questions and answers ("FAQ", "Häufige Fragen") the lines
state no use, plan or need (A4).

**Audiences** (`Audiences.VERSION` 3, at reading too): an audience phrase gives the provider no category, offer or topic of the
audience (B5); the provider naming itself in the third person, the word as the clause's subject with its own name ("Der
Pflegedienst Muster unterstützt Sie") or presented to the reader at its place ("Ihr häuslicher Pflegedienst in Musterstadt"),
is no target; an addressee stays ("Lösungen für Ihren Pflegedienst") (B6). A category's `exclude` (`categories.json`) lists
words of another sense, folded, that are no hit ("IT-Architekturen", "Verkehrsinfrastruktur", "Partnernetzwerk") (B7).

**Clean-up** (D1): every pass of the derivation deletes its rows naming an entity of status 2 (merged), also a pass that is
incomplete (43.3); every run of the matching first removes up to 2 000 contributions whose provider or candidate is merged
(`removed_merged` in its result).

Language knowledge in the code, industry knowledge only in the vocabularies.

### 44.3 The update

`rule/10`, `jsonld/8`, `Vocabulary.VERSION` 6 (the schemes `facility_kind` and `client_description`) and the vocabulary
package 8 (`products.json` version 3, `categories.json` with `exclude`): **one re-extraction** of every tracked page (42).
`LlmExtractor.VERSION` 6 with `REPLAYS` = {4, 5}: the stored answers are rebuilt from their evidence and validated again (C1,
C6, the places' words), **without a model request** (40.3); the question's version stays 4. An organisation, client or place
whose only evidence was the former reading goes with it; facilities named by their kind on several pages of one provider at one
place are merged into one (the older entity survives), so **a backup before the update is mandatory**. The first passes of
the derivation and the matching remove the rows on merged entities. The prompt hash includes the version of the products'
vocabulary: new and changed pages no longer hit the cache of the former prompt (the system prompt and the schema are
unchanged). Schema 9, unchanged.

### 44.4 Tests

`DocumentedRelationsTest` (after "bei" only a name, one name never split, the field's end, placeholders, markdown, descriptions),
`GenericNamesTest` (vocabulary words, the brands, the umlauts, lists, the senses of B7), `KindFacilityTest` (a kind is a
facility only at its place; one per provider, kind and address: three at three addresses stay three, one on five pages is one),
`PlaceEvidenceTest.onlyWhiteSpaceJoinsTheWordsOfAName`, `LlmExtractorInvalidReasonTest` (the two new codes),
`BusinessSignalsTest` (A2, A4), `MatchingTest` (the vendor, contributions on merged entities), `DerivedServiceTest.
aRowOnAMergedEntityGoesAlsoInAnIncompletePass`, `AudiencesTest.theProviderInTheThirdPersonIsNoTarget`,
`BusinessExtractionTest.aPhraseAboutTheCustomersGivesNoCategory`. Without their change B5 and D1 fail; neutral texts only.
