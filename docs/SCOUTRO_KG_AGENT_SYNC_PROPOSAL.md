# Agent access for portal synchronisation (M5, M6, M7): proposal

Status: **proposal, not implemented.** Follows the facilities' first stage ([SCOUTRO_KG_FACILITIES.md](SCOUTRO_KG_FACILITIES.md)),
which makes the data complete; this proposal makes them reachable and synchronisable for an external agent. Every change is
additive on the agent path `/scoutro/api/agent/v1`, scoped to the agent's collections, outside every preset; no administrator or
YaCy route is opened to agents, no scope is widened. Line numbers refer to 0.14.1 (`c6794d2`).

## M5 – a document by its exact URL

**Problem.** An agent cannot get from a URL to the page's document, text and facts: `index.lookup` answers whether a URL is
indexed, with title and collections, but without the document's ID or text (`api/ScoutroActions.java:733-767`, fields `:854`);
`index.evidence` reads at most 20 documents of a domain, without `offset` or `url` (`:786-839`); `/kg/sources/{docId}` needs the
Solr ID, which only `index.browse` (a substring search) yields.

**Change.**

1. `index.lookup` returns `id` and `load_date` as well (additive fields, same grant).
2. New read action `index.document` (grant of its own, scoped, not in a preset): `GET /index/document?url=&maxChars=&offset=`, the
   page's text from `offset`, at most `maxChars` (100–4 000, as `index.evidence`), title, collections, load date and a content hash
   of the text (SHA-256), for the evidence an import needs (the jobs module's `page_content_hash`).
3. `GET /kg/sources?url=` under `kg.read`: the URL's document (`urlVariants`, the collection filter), then the existing
   `KgReader.source`.

**Where.** `AgentActionRegistry` (entry), `AgentApi.route` (`:294-300`), `ScopedActions.execute` (`filterCollections`),
`ScoutroActions` (`validateHttpUrl`, `urlVariants`, `collectionFilter`, `excerpt`), `KnowledgeRead` (`known`, `route`, `:242-249`),
`tools/scoutro/generate_api_description.py` → `openapi.json`, `actions.json`; `docs/API.md`, `docs/ACTIONS.md`, `help/`.

**Tests.** `AgentCatalogTest`; scope (a URL of another collection is "not indexed", also via `/kg/sources?url=`); limits (`maxChars`,
`offset`, a URL that is no http(s) URL); the hash of a known text. **Effort** 2–3 person days. **Risk** low: the text of one page
of the agent's own collections, bounded per request and by the agent's rate limit.

## M6 – an export by entity type, grouped per entity

**Problem.** `/kg/export` pages through all entities, then all statements, then derived rows, with no filter of a type or a
predicate (`read/KgExport.java:214-325`); a consumer that wants the facilities of a collection reads everything and joins the
statements itself. Broad `GET /kg/entities` reads are the most expensive reads (a `count(*)` and a summary per hit,
`read/KgReader.java:460-561, 701-727`) and end with `503 kg_unavailable` / `read_timeout` after 5 s with two readers
(`KgConfig.java:338`, `store/KgStore.java:84`).

**Change.** A projection `GET /kg/business?type=facility|organization|job&cursor=&limit=` (1–25) under `kg.export`: per entity its
summary, its own contact values (address, postal code, town, geo, phone, role mailbox, website, opening hours), its operators
(incoming `operates`, `carrier_of`) with their IDs, its services, its main source (the page with most of its evidence), quality and
`last_confirmed` per value, the evidence in short form (URL, kind, locator, excerpt, loaded at), `possible_duplicates`, and the
`identity_review` events about it. Keyset over the entity rowid, the type bound into the cursor; one read lease per page; the
change feed gives the IDs to read again. `BusinessView.view` (`read/BusinessView.java:300-420`) already computes most of it per entity.

**Tests.** `KgExportTest`-like: pages cover every entity of the type once, isolation of collections, the cursor with its type, a
page under the read deadline on a measured copy. **Effort** 4–6 person days. **Risk** medium (read load): small pages, a lease per page,
measured on a copy, never on production.

## M7 – the change feed for a scoped agent

**Problem** (from the code, no test covers it). A merge records the redirect of the merged entity with an empty set of scopes after it
(`publish/Aggregates.java:161-169` → `store/KgChangeLog.record`, `scopes_now` empty); `KgChangeLog.visibleOp` (`:281-292`) gives a
viewer that is not `Viewer.ALL` a `delete` instead of the `redirect`. The statements a merge moves to the survivor keep their IDs
and only change their subject (`resolve/IdentityResolver.java`, `UPDATE kg_statement SET subj`); no change is recorded for them, as a
statement change is recorded only when it is new or its scope or quality changes (`Aggregates.java:288, 313`). A scoped consumer sees
the merged facility disappear and does not learn that its statements now belong to the survivor. Stage 1 makes merges of facilities
more frequent, so this matters now.

**Change.** The redirect of an entity is recorded with the survivor's scopes (visible to a viewer that sees the survivor); a
statement whose subject changes in a merge is touched (`Aggregates.touchStatement`), so the feed carries its upsert; the
documentation of the feed (`docs/API.md`, the feed section of `SCOUTRO_KNOWLEDGE_GRAPH.md`) describes the redirect for scoped viewers.

**Tests.** `KgChangeLogTest`: a merge in collection A, a consumer limited to A gets the `redirect` with `redirect_to` and the moved
statements as upserts; a consumer of B only gets nothing; the change feed after an export carries the merge. **Effort** 1–3 person days.
**Risk** low.

**Until then (for the agent, no change).** On every `delete` of an entity read `GET /kg/entities/{id}`: a `{"redirect": …}` names the
survivor, whose statements are read again; a full export once a week.

## Order

M7 first (small, makes every merge of stage 1 visible to scoped consumers), then M5 (evidence by URL, the jobs' content hash), then
M6 (fewer and cheaper reads). None needs a schema change; each needs the release checklist of an agent action (§8.2 of
`SCOUTRO_KNOWLEDGE_GRAPH.md`).
