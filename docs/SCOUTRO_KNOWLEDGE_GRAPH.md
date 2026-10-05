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
- **No automatic split.** If the evidence behind a merge disappears, the merge stays. The admin maintenance action "re-resolve identities" rebuilds resolution from the cached extractions without LLM calls.

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

- **Field type.** Stored only, not indexed: text, multi-valued, declared explicitly in `defaults/solr/schema.xml`. Its name matches the old schema's dynamic `*_txt`, which keeps rollback safe ([13](#13-migration-backup-rollback)).
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
  backup/                                local backups, if enabled
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
| `scoutro.kg.budget.maxBytes` | 1 GiB (**provisional**, O1) | Total budget for the directory above |
| `scoutro.kg.budget.pausePercent` / `resumePercent` | 90 / 80 | Hysteresis for new growth |
| `scoutro.kg.budget.maintenancePercent` | 20 | Share for WAL, temp files, deletions, migration. Must hold `wal.maxBytes + tmp.maxBytes`; otherwise the configuration is invalid. |
| `scoutro.kg.wal.maxBytes` / `wal.checkpointBytes` | 64 MiB / 8 MiB | Hard WAL limit of the guard; size at which a checkpoint is forced |
| `scoutro.kg.tmp.maxBytes` | 64 MiB | Limit for temp files (visible + open unlinked) |
| `scoutro.kg.read.maxTransactionMillis` | 5000 | Deadline of every read transaction |
| `scoutro.kg.integrity.maxMillis` | 120 000 | Deadline of the `quick_check` after an unclean shutdown |
| `scoutro.kg.disk.reserveBytes` / `disk.hysteresisBytes` | 1 GiB / 512 MiB | Free space kept above YaCy's `resource.disk.free.min.steadystate`; resume margin |
| `scoutro.kg.jsonld.enabled`, `jsonld.maxBytesPerDoc`, `jsonld.maxBlocksPerDoc`, `jsonld.maxTotalBytes` | false, 16 KiB, 8, 256 MiB (provisional) | JSON-LD capture and its own Solr budget |
| `scoutro.kg.queue.maxItems` / `capture.maxPending` | 200 000 / 100 000 | Work queue and dirty-set caps; beyond → `reconcile_required` |
| `scoutro.kg.extract.maxStatementsPerDoc` / `maxExcerptChars` / `maxInputChars` | 50 / 200 / 12 000 | Growth per document and LLM input |
| `scoutro.kg.cache.maxPercent` | 20 | Extraction cache share |
| `scoutro.kg.changes.retentionDays` / `maxRows` | 30 / 1 000 000 | Change feed and delete notices |
| `scoutro.kg.source.*` | 14 / 7 / 365 / 90 days | Currency and purge rules ([4.4](#44-quality-and-currency)) |
| `scoutro.kg.backup.keep` / `intervalDays` | 1 / 7 | Local backups (0 = off) |

- **Implemented and validated in package 1** (`KgConfig`): the budget, WAL, temp, read, integrity, disk and JSON-LD settings. **Package 2a** adds `collections` (`*` for all), `capture.maxPending`, `queue.maxItems`, `extract.*` (tiers 1 and 2), `reconcile.*`, `source.*`, `changes.*` and `gate.*`. The LLM and cache settings follow with 2b.
- YaCy's `resource.disk.free.min.steadystate` and `undershot` (MB) are read, never changed.

### 7.3 What bounds which file

| File | Bound | Enforced by |
|---|---|---|
| `graph.db` | `max_page_count` = data share of the budget (budget − maintenance share) in pages. Refused growth ends with `SQLITE_FULL`; SQLite rolls the transaction back itself, and integrity stays `ok` (tested). | SQLite (per writer connection) |
| `graph.db-wal` | `wal.maxBytes` | The guard only (below) |
| `tmp/` | `tmp.maxBytes` (visible + open unlinked files) | The guard: growth stops, running readers are interrupted |
| `backup/` | Counts fully in the budget; a backup starts only if budget and disk reserve allow its size | The guard (package 5) |

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
| O1 | Real server and Olares capacity: free space on the `DATA` filesystem, current index size (documents, hosts, collections), whether `limitedDisk: 20Gi` is enforced | The 1 GiB graph budget and the 256 MiB JSON-LD budget stay provisional until package 5 measurements and these figures exist. No free capacity is claimed. | Owner / operations |
| O2 | ~~Industry vocabulary~~ — decided: extensible, collection-specific vocabulary without a schema rebuild; package 2b ships a small start vocabulary of facility kinds for `edelsenior-web`, `checkthecoach-web`, `stackfinder-web` and `bauteamcheck-web`, replaced per collection by `llm.kinds.<collection>`; kinds are entity attributes, so new ones need no schema change | Tier-3 quality; kinds are merge discriminators | Owner (further terms) |
| O3 | ~~LLM host and model~~ — decided: the existing LLM selection is reused (usage `knowledge`, opt-in column); the LLM is optional and the graph works without it. Throughput on the target hardware is measured in package 5 | `llm.maxDocsPerHost`, heap gate | Operations (model choice) |
| O4 | ~~Fate of branch `ccr-e3e5f88b-1fqp77`~~ — resolved: merged into `main` as PR #13 (`5ee2d29`) | Packages 3/4 integrate into the domain view and reuse its export pattern | — |
| O5 | Existing chat gap: clients choose any collection, guests included | The graph does not widen it (guests get no facts). Fixing content RAG scoping is out of scope. | Owner decision |
| O6 | Backup target outside `DATA` | Local backups count fully in the budget; an external target needs a mounted path, which conflicts with the Olares "no second DATA path" rule | Owner / operations |
| O7 | Legal review of stored excerpts (imprint pages contain names) — decided: minimal data, no extra person profiling, no employee e-mails or personal contact data as an enrichment target; the LLM tier extracts no persons, e-mail addresses or phone numbers | Excerpt length and the export of excerpts | Owner |
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
