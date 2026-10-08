# Scoutro Knowledge: operator guide

This guide is for administrators who switch on, run and look after the
Scoutro knowledge graph. The design, the data model and the decisions behind
it are in the [plan](SCOUTRO_KNOWLEDGE_GRAPH.md). The routes are in
[API.md](API.md#knowledge-graph), and the page itself is described in
[help/ScoutroKnowledge_p.md](../help/ScoutroKnowledge_p.md).

## 1. What it is

Scoutro Knowledge is a business graph of **organisations, facilities, sites,
places, services and job postings**, read from the pages Scoutro has
crawled. Each fact says where it comes from. Examples: "Muster Pflege gGmbH
operates Haus Lindenhof", "Haus Lindenhof: phone +49 30 1234567", "Haus
Lindenhof offers short-term care, 89,90 € per day, Stand 08/2026", "CloudWerk
GmbH is a partner of PflegeSoft GmbH".

- **What a firm offers and what it costs.** Services with their category,
  and prices exactly as published (amount, unit, from/up to/range,
  conditions, VAT note, the page's date). Nothing is estimated, converted
  or averaged. Two different prices stay visible as a conflict.
- **How firms relate.** Fifteen relations between organisations: operator,
  parent and subsidiary, partner, customer, supplier, member, certified by,
  and more. Each relation comes only from an explicit statement and is
  directed.
- **Industry, contacts, jobs, audiences.**
  - The industry as a NACE Rev. 2.1 / WZ 2025 code, given only at the level
    the page makes safe.
  - The organisation's contacts (no persons).
  - Job postings where switched on (no persons).
  - The audience in three separate layers: what the firm declares, what its
    pages show, and what Scoutro suggests.
- **Suggested matches are never facts.** A derived layer suggests possible
  customers and partners and links firms that link to each other. Every
  such row names its evidence on both sides, and a viewer sees it only with
  both collections. Each side uses only its facts in its own collection: a
  place or a service known from a third collection never decides a row.
  It is computed, labelled as a suggestion, and never turned into a
  relation.

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
| `scoutro.kg.collections` | e.g. `edelsenior-web,bauteamcheck-web`, or `*` for all | The collections the graph follows; nothing outside them is read. Also under *Knowledge graph → Settings*, per collection ([5.4](#54-collections-o5)) |
| `scoutro.kg.jsonld.enabled` | `true` | Captures `<script type="application/ld+json">` while crawling. Without it, tier 1 only has metadata, and only pages crawled after the switch carry JSON-LD |
| `scoutro.kg.llm.collections` | optional, e.g. `edelsenior-web` | The optional LLM tier ([5.3](#53-the-optional-llm-tier)) |
| `scoutro.kg.jobs.collections` | optional, e.g. `edelsenior-web,bauteamcheck-web` | Job postings ([4.4](#44-business-facts-vocabulary-2)); off by default |

Further settings of the business facts (defaults in brackets):
- `scoutro.kg.vocab.<collection>`: the business vocabulary of a collection.
  The four Scoutro collections have one (`care`, `coaching`, `software`,
  `construction`); empty means none.
- `scoutro.kg.prices.staleDays[.<collection>]` (180): after how many days a
  price without a validity date counts as possibly outdated.
- `scoutro.kg.jobs.endedVisibleDays` (90): how long an ended posting stays
  visible.
- `scoutro.kg.derived.enabled` (`true`) and
  `scoutro.kg.derived.intervalMinutes` (60): the derived layer. Switched
  off, one pass removes its rows, and the change feed reports each removal.
- `scoutro.kg.matches.maxPerEntity` (20) and `scoutro.kg.matches.max`
  (50 000): caps for suggested matches.
- `scoutro.kg.sameOperator.maxGroup` (12): the largest group of facilities
  linked as having the same operator.

Own vocabularies are JSON files in `DATA/SCOUTRO/knowledge/vocabulary/`
([defaults/scoutro/knowledge/README.md](../defaults/scoutro/knowledge/README.md)).
A changed vocabulary re-extracts the pages at low priority.

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
| Relations: operates, part of, located at, in place, offers, and the relations between organisations (partner, customer, member, …) | Page text beyond the excerpt (at most `extract.maxExcerptChars`, default 200 characters, per fact) |
| Services, prices as published with their date and conditions, the industry code, the declared audience and service area | Prices that are not on the page: no estimates, conversions, averages, and no price without an explicit currency |
| Job postings (title, employment type, place, deadline, published salary) in the collections of `jobs.collections` | Recruiters and contact persons of a posting; a posting more than `jobs.endedVisibleDays` after its end |
| Derived rows (weak links, same operator, suggested matches) with the statements of both sides | A suggestion as a fact: it never becomes a relation |
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
- **Two switches, one model (package 6.3).** Selecting a model for the usage
  *knowledge* is not enough: the tier also needs at least one collection
  switched on for it. The key `scoutro.kg.llm.collections` is empty by
  default, so a fresh installation with a knowledge model shows
  `LLM: off · no_llm_collections`. That is the opt-in, not a fault of the LLM
  selection.
  - Switch it per collection under *Knowledge graph → Settings*, column
    *LLM enrichment* (or `PATCH /scoutro/api/v1/kg/collections/{collection}`
    with `{"llm": true}`, `scoutroctl kg collection NAME --llm|--no-llm`).
    It asks first and names the model; only that collection's name is added
    to or removed from the key, and the graph is reopened at once, no
    restart.
  - No collection gets it by itself, also not a new one or one switched on
    for the graph. With `*` in the key every collection has it; that is not
    rewritten here (409 `llm_all_collections`).
  - The overview shows the two layers apart: *Knowledge graph (rules and
    structured data)* active or inactive, *LLM enrichment* active or
    inactive with the reason, and the model in use, also while the tier is
    off. `llm_collections_not_followed` says that the collections switched on
    for it are all off for the graph.
- **Bounded.** It has its own threads (`llm.parallel`, 1 or 2), a timeout
  (`llm.timeoutSeconds`, 120), retries (`llm.maxAttempts`, 2), a circuit
  breaker (`llm.breakerFailures`, 3; backoff up to
  `llm.breakerMaxBackoffMinutes`, 60) and at most `llm.maxDocsPerHost` (25)
  pages per host. Input is capped at `extract.maxInputChars` (12 000).
- **Counters since the start.** The overview's LLM tier panel and
  `llm.processed` in `GET /scoutro/api/v1/kg/status` count in memory since
  the last start (since the pod start); every restart or upgrade resets
  them, nothing is persisted. **Dropped as invalid** (`droppedInvalid`)
  counts single dropped entities, claims and values, not failed calls: one
  answer can bring valid items and several invalid ones. Answers refused as
  a whole are `refusedBy` (`invalid_json`, `unknown_field`, ...). The folded
  **Invalid items by reason** (`droppedInvalidByReason`) splits
  `droppedInvalid` by the first rule each item breaks; the codes add up to
  it and are diagnostics only, no model answer is stored for them (codes:
  `docs/SCOUTRO_KNOWLEDGE_GRAPH.md`, 6.3). `valuesAccepted` counts the
  accepted values (prices, categories, ...).
- **Ollama natively.** An `OLLAMA` model is asked on Ollama's native
  `/api/chat`, with the JSON schema as `format`: Ollama enforces it there,
  while its OpenAI-compatible `/v1/chat/completions` was seen to ignore a
  `response_format`. Every other service keeps `/v1/chat/completions`; there
  is no switching between the two. The validator checks every answer as
  before, existing results and cache entries stay, and only new or changed
  pages are asked on the new path (no re-extraction). The native request says
  `think: false` unless the LLM selection's native thinking test found the
  model does not think: a thinking model (e.g. Qwen3) would otherwise spend
  the answer budget on thinking and answer nothing.
- **Knowledge prompt.** The system prompt of the LLM tier can be read and
  changed without a new image through the administrator API
  `GET`/`POST /scoutro/api/v1/kg/prompt` (validate, activate, reset to the
  compiled-in default; no page and no agent access yet). Scoutro stores the
  active version in the graph with revision, hash, source and time. A new
  prompt is used for new and changed pages and never answered from the
  cache of another one; done documents are not read again by it.
- **Structured output.** With `scoutro.kg.llm.structuredOutput=auto` (the
  default) the model's "format" capability of the LLM selection decides what
  the endpoint is asked for: supported sends the JSON schema, unsupported
  (the endpoint refused it in the current technical probe) sends none
  (prompt and validator only), ignored and unknown send the schema as
  before. A value of the former mood probe counts as unknown until the LLM
  selection page tests the model again. `json_schema`, `json_object` (JSON mode) and `none` override it.
  An endpoint that rejects the format (HTTP 400) is asked again without it;
  the panel shows **Structured output** (for example *Schema sent,
  enforcement not confirmed* or *Fallback after rejection*) and the
  requests by format. The validator checks every answer the same way in
  every mode; existing cache entries stay valid (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3).
- **Kinds per collection (O2).** The facility kinds the model may assign come
  from `scoutro.kg.llm.kinds.<collection>`, a comma-separated list such as
  `nursinghome,assistedliving`. Start lists exist for the four Scoutro
  collections. A new kind needs no schema change.
- **The cache.** Validated answers are stored (`cache.maxPercent` of the
  budget), so a recrawl without changes, a reconcile or an identity rebuild
  calls the model only for changed text.

### 4.4 Business facts (vocabulary 2)

- **Tiers as above.** JSON-LD gives offers, prices, `makesOffer`, catalogs,
  categories, `memberOf`, parent and sub organisations, credentials, contact
  points, `JobPosting` (where jobs are on), audience and service area.
  - The rules read service lists, price lines, relations after explicit
    markers ("Mitglied der …", "Partner der …", "Kunden: …"), the industry
    of an imprint, contacts, job pages and audiences.
  - The LLM tier may point at a relation or a job, but every value (price,
    salary, code) is read from its verbatim quote by Scoutro, never from
    the model.
- **Prices.** Each price belongs to a named service and keeps its date as
  the page wrote it ("Stand 09/2026").
  - A price past its validity date is expired and hidden.
  - A price older than `prices.staleDays` without a validity date is shown
    as possibly outdated; the chat says so.
- **Industry.** A code is given only at the level the page makes safe: for
  example `87` when only "Pflege" is sure, `87.10` for a nursing home.
  Main and secondary industries are kept apart.
- **Jobs** follow the posting: open, ended (visible for `endedVisibleDays`),
  then hidden. A posting whose page disappears ends with it.
- **Audiences in three layers.** *Declared* is what the firm says; *observed*
  is what its pages show, such as a price for private clients; *suggested*
  is a match Scoutro computed. The page, the API and the chat never mix
  them.
- **The object view and the network.** *Knowledge graph → object* shows the
  sections with content: overview, industry, services, prices, contacts,
  relations, jobs, audiences, suggested matches, evidence and sources. *Network*
  draws the neighbours of an object (depth 1 or 2; typed, directed lines whose
  style shows confirmed, uncertain, derived, weak and suggested).
  - It has the same data as a list, and can be downloaded as GraphML
    (Gephi, Cytoscape) or JSON.
  - It is usable with the keyboard and at 360 px.

  *Compare* lists the prices of one service category across providers.
- **Names (package 6.1).** Pages never show an ID as a name. Without a
  stated name an object is shown with a legal name, the declared operator of
  its site or, for an organisation, a name from its domain ("Zimmerei
  Boehmer", marked "derived from the domain"), else as "Unnamed
  organisation" and the like. Such names are presentation only: not stored,
  not used to merge, not a fact for the chat; a stated name replaces them as
  soon as a page states it.
- **Services of the same name (package 6.1).** A service belongs to its
  provider: "SAP" of one company and "SAP" of another are two objects, each
  with its own provider, prices and sources, and they are never merged.
  - The object list shows each hit with its provider and domain
    (`SAP` · `CTcon GmbH · ctcon.de · stackfinder-web`), its collection,
    place, quality, sources and last confirmation. A service whose provider
    the selected collection does not show says *No provider assigned*.
  - *Services* counts the services of one name for reading only ("SAP · 133
    providers"): providers, collections, places, how many with a current
    price and with a current source, and lists every provider's service in
    its own row. Nothing is merged, averaged or mixed; the IDs stay.
  - In the network a service shows who offers it (the incoming `offers`) and,
    at depth 2, its providers' relations, but not their other services.

### 5.4 Collections (O5)

- **Every view is computed from one collection.** Every name, value, count,
  host and source on the page, in the API, in the export, for agents and in
  the chat comes only from documents of the selected collection.
  - An object that only another collection knows answers "not found".
  - An alias or VAT ID seen only in another collection is not shown.
- **The administrator** can also look across all followed collections, with
  no `collection` parameter.
- **Agents never can.** They are always limited to their own collections.
- **New collections (package 6.1).** With `scoutro.kg.collections=*` a new
  collection such as `newportal-web` is followed at once; with a fixed list
  only the named collections are. The overview's *Collections* table lists
  every collection with its vocabulary, jobs, LLM tier, documents in the
  graph and state.
  - A collection without a vocabulary is shown as *No vocabulary assigned*.
    It gets generic facts (names, addresses, contacts, relations, the
    industry of a declared business type), but no service categories; a
    vocabulary is never guessed from a collection's name. The defaults are
    `edelsenior-web` care, `checkthecoach-web` coaching, `stackfinder-web`
    software and `bauteamcheck-web` construction.
  - Assign an existing vocabulary with
    `scoutro.kg.vocab.<collection>=<vocabulary>` (for example
    `scoutro.kg.vocab.newportal-web=software`); this works under `*` as well
    since package 6.1. An empty value switches a default off. A new
    vocabulary or mapping is a file in `DATA/SCOUTRO/knowledge/vocabulary/`
    in the format of `defaults/scoutro/knowledge/categories.json`; no schema
    change is needed. Both take effect at the next start and re-extract the
    pages concerned.
  - Jobs are read only for collections named in
    `scoutro.kg.jobs.collections` (or `*`). A new collection never gets them
    by itself.
- **Switching collections on and off (package 6.2).** *Knowledge graph →
  Settings* lists every collection of the collection catalog (a new one by
  itself; YaCy's internal `robot_*` never) with its pages in the index, *On*
  or *Off*, its vocabulary and its documents in the graph, and *Save* per
  row (API: `GET /scoutro/api/v1/kg/collections`,
  `PATCH /scoutro/api/v1/kg/collections/{collection}`; CLI
  `scoutroctl kg collections`, `scoutroctl kg collection NAME --on|--off
  [--vocabulary V|--no-vocabulary|--default-vocabulary] [--llm|--no-llm]`).
  Administrator only.
  - *On* adds the name to `scoutro.kg.collections` (`*` stays `*`) and
    removes it from `scoutro.kg.collections.inactive`. The pages it already
    has in the index are read by the reconcile every start of the graph runs
    (the backfill); its state is *waiting for documents* until they are in.
  - *Off* removes the name from `scoutro.kg.collections` and adds it to
    `scoutro.kg.collections.inactive`. **Its graph data are kept:** the
    reconcile deletes nothing of it, nothing new is read from it (also not
    JSON-LD), also under `*`, and its data stay readable. A page deleted from
    the index still leaves the graph; a page that also belongs to a followed
    collection is still read through that one and keeps its membership of
    the switched-off one. Before package 6.2, removing a name from the list
    meant that the next reconcile deleted its data (with the mass-deletion
    brake); a name removed by hand from the list (and not added to
    `inactive`) still behaves so.
  - *Vocabulary*: *Default* (of the vocabulary files; the key is removed), a
    vocabulary, or *No vocabulary* (empty value). A changed vocabulary of a
    followed collection changes the extraction identity: the graph reads its
    pages again in the background (low priority), as with a hand-edited key.
    Switching a collection off or on changes no vocabulary and so re-extracts
    nothing.
  - Only that collection's keys change. A change takes effect at once: the
    graph is closed cleanly and opened again with the new settings (a manual
    pause stays; a running reconcile starts anew). While a backup, a restore
    or an identity rebuild runs nothing is written (409
    `operation_running`). While the graph is off, the settings are stored and
    count at its next start.
  - An identity rebuild ([9.3](#93-re-resolve-identities-rebuild)) builds
    the graph from the followed collections only; switch a collection on
    again before a rebuild if its data should stay.

## 6. Permissions and agent access

| Who | What |
|---|---|
| Administrator (Digest login) | The page `ScoutroKnowledge_p.html`; every route under `/scoutro/api/v1/kg/`: status, control, reads, export, change feed, download, backups |
| Agent with grant `kg.read` | Reads under `/scoutro/api/agent/v1/kg/` (entities, statements, evidence, hosts, sources, the business view, the neighbourhood, the comparison, derived rows, facets), always within the agent's collections; derived rows only with both collections |
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
- **Deleting one by hand (package 6.3).** *Delete* in the list,
  `scoutroctl kg backup-delete <file>`, or
  `{"action":"delete_backup","backup":"<file>"}`; administrator only, never
  an agent.
  - The page asks first and names the file, its type (backup, before a
    restore, before a rebuild, before an upgrade), its size and its date. A
    copy before an upgrade is the way back to the previous version; the
    question says so.
  - Only a backup file of the backup folder, by its name: anything that is
    not `graph-<UTC>[-before-restore|-before-rebuild|-before-upgrade].db` is
    400, an unknown name 404. The file must be a regular file directly in
    `DATA/SCOUTRO/knowledge/backup` (no link, no directory); the live
    `graph.db` and its `-wal`/`-shm` are never deleted.
  - The metadata file goes with it; the list and the space of the backups are
    updated. While a backup, restore or rebuild runs, nothing is deleted
    (409). The event log records `backup_deleted`.

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

### 9.4 Upgrading from Scoutro 0.7 (vocabulary 2)

Scoutro 0.8 stores the business facts in schema 4 (0.7: schema 3). The
upgrade happens at the first start of the new version:

1. **Integrity first.** `PRAGMA quick_check` runs on the unchanged file
   (bounded by `integrity.maxMillis`). A damaged or unchecked graph is not
   migrated: the graph stays off (`upgrade_blocked`) and the file stays
   as it is. Restore a backup or reset the graph.
2. **A copy before the migration.** The old graph is copied to
   `backup/graph-<UTC>-before-upgrade.db`, verified and given metadata like
   any backup. The newest such copy survives the backup retention: it is
   the way back to 0.7.
3. **The migration** only adds tables and columns; the existing facts,
   IDs and the change feed with its sequence stay.
4. **The re-extraction.** Every page is read again with the new vocabulary,
   at low priority behind the usual gates. The LLM tier, where configured,
   reads its pages again with the new extractor version. Old LLM evidence
   stays until it is replaced.
5. **Without room for the copy** (budget or disk), the migration still
   runs, but the re-extraction and the LLM re-examination wait
   (`status.upgrade.hold`, `sync.upgradeHold`). Free space and make a backup
   (*Create backup*); a verified backup releases the hold.

`GET /kg/status` reports `upgrade` (from, to, the copy or the hold, what
waits). Make and download a backup before the upgrade in any case.

**A 0.7 graph larger than 64 MiB.** Scoutro 0.7 checks a backup against
the WAL limit (`scoutro.kg.wal.maxBytes`, 64 MiB), although a backup writes
no WAL, and skips it with `wal_limit`. 0.8 no longer does, and its copy
before the upgrade works at any size. For the backup in 0.7, stop Scoutro
and copy `graph.db` (with `graph.db-wal` and `graph.db-shm`, if present).
Alternatively raise `scoutro.kg.wal.maxBytes` above the size of `graph.db`
for that backup and set it back afterwards. With the default budget this
works up to about 900 MiB: the maintenance share (10 % of the budget) must
hold the WAL and temp limits, and the settings page names a value it
cannot hold.

### 9.5 Going back to Scoutro 0.7

- **0.7 does not open a schema-4 graph.** It reports `schema_unsupported`
  and leaves the file untouched; search and crawl run without the graph.
- **To keep the graph in 0.7,** stop Scoutro and restore the copy made
  before the upgrade:
  1. move `graph.db` (and `graph.db-wal`, `graph.db-shm`, if present) out of
     `DATA/SCOUTRO/knowledge/`;
  2. copy `backup/graph-<UTC>-before-upgrade.db` to `graph.db`;
  3. start 0.7.

  The graph reconciles with the index. Facts of pages changed since the
  upgrade come back with their next processing. Business facts of 0.8 are
  not in this copy.
- **Coming back to 0.8** upgrades again (with a new copy).

### 9.6 Going back to a version without the graph

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

Without a model, or with `scoutro.kg.llm.collections` empty (no collection
switched on for the LLM enrichment, the default), the graph runs tiers 1 and
2 only:

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
- **Generic services.** Next to the specific services of a page, the rules
  add the vocabulary's general ones that the page names (a firm "für
  Heizung und Sanitär" offers *Heizung* and *Sanitär*). They are not wrong,
  but coarser than the page's own list.
- **Prices only as written.** A price needs an explicit currency and a named
  service. Price tables are read as YaCy's parser gives them (row after
  row, each price after the service of its row); a price with no service
  before it is not kept. Units, ranges and conditions are kept as the page
  states them, and are never normalised for a comparison.
- **Ambiguous amounts are left out.** A plain space groups thousands only
  where nothing else can be meant ("Kosten: 1 250 €", "ab 1 250 €"); after
  a word or a number it may part two cells of a table row ("Pflegegrad 2
  980 €"), so that line gives no price. In JSON-LD the point is the decimal
  point (schema.org); a string such as `"12.500"`, which German pages also
  use for 12 500, gives no price. The page text still gives it where it
  names it.
- **Suggestions are heuristics.** A suggested customer or partner only says
  that declared targets and offers meet; it is never checked against
  reality.
- **Places by name.** A place is known by its country, its level and its
  name, so that a service area in the text ("in Potsdam und Umgebung") meets
  an address. Two towns of one name in one country (several *Neustadt*) are
  one place in the graph, and a suggestion may take one for the other. The
  suggestion names the place, so its reader can check it.

- **Same names are grouped by spelling.** The service groups compare names
  in lower case (ASCII letters only) without surrounding spaces;
  "SAP-Beratung" and "SAP Beratung" are two groups. The groups count the
  first 5000 services of a name.
- **The network drawing has no zoom or pan.** It is laid out to the width of
  the page instead, and the list below holds the same data.

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
- **After the upgrade to 0.8:** `status.upgrade` shows the copy
  (`backup`) or a hold. With a hold, free space and make a backup. Then the
  re-extraction runs (pending work by type on the overview), and the
  business sections fill.
