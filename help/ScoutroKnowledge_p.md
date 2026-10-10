---
page: htroot/ScoutroKnowledge_p.html
help: help/ScoutroKnowledge_p.md
title: Knowledge graph
package: scoutro
access: admin
kind: admin-page
backend_java: source/net/yacy/htroot/ScoutroKnowledge_p.java
---

# Knowledge graph

## Purpose

The knowledge graph collects organisations, facilities, sites, places,
services and jobs from the crawled pages of the followed collections, with
every fact tied to the page it comes from. Since vocabulary 2 (Scoutro 0.8) it
also holds the business view of a company: industry (NACE Rev. 2.1 / WZ 2025),
services and their published prices, organisation contacts, relations between
companies, jobs and audiences, plus suggested matches that are marked as
suggestions. This page shows its status, its objects, their network and a
price comparison, and the evidence behind each fact. Operator guide:
`docs/SCOUTRO_KNOWLEDGE.md`; plan: `docs/SCOUTRO_KNOWLEDGE_GRAPH.md`; API:
`docs/API.md`, section "Knowledge graph".

## Views

| View | URL | Content |
| --- | --- | --- |
| Overview | `ScoutroKnowledge_p.html` | State, storage and JSON-LD budgets with their levels, synchronisation, LLM tier, the collections (see below), controls, backups, identity rebuild, recent events |
| Objects | `?view=objects&q=&type=&quality=&host=&industry=&category=&audience=` | Search by name, filter by type, quality, host, industry (NACE code; a section or division finds everything below it), service category and audience; 25 per page. Every hit shows its type, its provider and domain (a service: who offers it; a job: its employer; others: their own domain), its collection, place, quality, sources and last confirmation, with **Open provider**, **Network**, **Sources** and, for a service, **All providers** |
| Services | `?view=services&q=&category=` · `?view=services&name=SAP` | Services of the same name across providers ("SAP · 133 providers") and, for one name, every provider's service in its own row (see below) |
| Network of a name | `?view=network&group=SAP&category=&s=current,stale&list=1` | All providers of one service name around the name (see below) |
| Object | `?view=object&id=kge_…` | Sections with content only: Overview, Industry, Services, Prices, Contacts, Relations, Jobs, Audiences, Suggested matches, Evidence and sources, all facts and relations, relations pointing to it; evidence per fact; link to the network |
| Network | `?view=network&id=kge_…&depth=2&f=…&list=1` | The neighbourhood of one object as a drawing and as a list (see below) |
| Compare | `?view=compare&category=care/tagespflege` | One service category across providers with the prices as published |
| Source | `?view=source&doc=<Solr id>` | What the graph holds from one page: state, tiers, LLM status, every fact with its evidence |
| Settings | `?view=settings` | The effective settings and their problems, and the knowledge graph settings of each collection: on or off, vocabulary (see below) |

Every view takes `collection=<name>`. **Ordinary facts, names, values, counts,
hosts and sources are computed only from documents of that collection**;
customer/partner suggestions use the targeted exception described below.
Ordinary objects only other collections know are "not found" unless opened in
their own authorized target context. Without a collection the administrator
sees all followed collections. Status, storage and events on the overview
describe the whole graph.

The collection is chosen from a list at the top (a `select`, no free text):
"All collections" first, then the collections of the index alphabetically; a
new collection appears as soon as the index has pages of it. Choosing applies
it at once. A `collection=` of a link that is not in the list falls back to
all collections and the page says so.

## Reading the facts

- **Quality:** `supported` (stated in a current page by structured data or the
  rules), `uncertain` (only hedged, only from unavailable pages, or only read by
  the LLM tier), `conflicting` (a single-valued fact with several supported
  values), `stale` (no current source; hidden unless asked for).
- **Kinds:** `jsonld`, `metadata`, `rule`, `llm`. "Only from the LLM tier"
  marks facts no other tier confirms; their evidence always carries the quote
  from the page.
- **Evidence:** the page (opens externally), its collections (only visible
  ones), state, load date, extractor (for the LLM tier with model and prompt
  version) and the excerpt; links to the source view and the Index Browser.
- **Possible duplicates** are objects of the same type with the same name.
  They are never merged automatically.
- **Names:** an object is shown with its stated name. Without one the page
  uses a legal name, the declared operator of the site, or for an
  organisation a name from its domain, marked "derived from the domain"
  (`zimmerei-boehmer.de` → "Zimmerei Boehmer"); otherwise "Unnamed
  organisation", "Unnamed service" and so on. These names are only for
  display: they are not stored, not used to merge objects and not given to
  the chat as facts. The ID appears only as *Technical ID* in the object view.

## Business view (vocabulary 2)

- **Industry:** main and further industries with their NACE Rev. 2.1 / WZ 2025
  code down to the four-digit class, the path (section › division › group ›
  class) and the Scoutro categories. If two different codes are equally
  strong, the safe common higher level is shown. Labels are the official
  English NACE titles.
- **Services and prices:** every price exactly as the page published it:
  amount or range, currency, unit, kind (from, up to, range), conditions, VAT,
  care level, own share and the date. Nothing is estimated, converted,
  normalised or averaged. Two different published amounts for the same thing
  stay visible as **conflicting**. A price without a date of its own is
  **outdated** after `scoutro.kg.prices.staleDays` (180; per collection
  `scoutro.kg.prices.staleDays.<collection>`); a validity date of the source
  wins (**expired**).
- **Contacts:** only organisation contacts (central phone, fax, role
  mailboxes, contact form, departments without names, opening and office
  hours, company profiles, website, directions). Person names, personal
  e-mail addresses and extensions are never stored.
- **Relations:** carrier, parent and subsidiary, member, partner,
  cooperation, customer and reference, supplier, service provider, brand,
  certification, funding and sponsoring, with their direction. **Derived,
  no facts:** "same operator" and weak link signals (linked pages).
- **Jobs** (display policy initially inherits `scoutro.kg.jobs.collections`): title, employment type,
  place, salary with unit and date, dates and how to apply. A published deadline is marked separately from a confirmed end. A missing
  page or old undated posting does not prove an end; current search may be unknown.
  Ended/deadline-passed jobs stay visible for
  `scoutro.kg.jobs.endedVisibleDays` (90) days.
- **Audiences** in three layers that never mix: the declared audience
  (customer types, segments, sizes, target industries, sought services,
  service area), published customers and references, and **suggestions**.
- **Suggested matches** combine what one company offers with what another
  seeks (or a shared audience for partners). They are always labelled as a
  suggestion or possible customer, never as a customer relation, and only
  shown when both collections are authorized (the view filter may be narrower).

The selected collection filters the origin organization and its ordinary facts,
sources and graph relations. Existing **customer and partner suggestions** may
reach all collections authorized for the caller. For an administrator these are
all collections; an agent's fixed grants determine its permission scope. The
filter never adds permissions. External targets show **Other collection: NAME**
(**Andere Collection: NAME** in German); all authorized memberships are listed,
without hidden membership counts. Contributions from multiple collections are
grouped by organization and relationship before sorting and paging.

The **Suggested customers and partners** checkbox controls these derivations in
the object list and network (including its list view). Reasons and supporting
facts are expandable. Every evidence and source link uses its contribution's
collection; target object/network links open the explicit authorized target
collection. Browser Back restores the origin view. A missing or inaccessible
supporting statement is omitted and its contribution marked incomplete. Sorting
scores are technical ranking values, not measured probabilities or chances of
winning a customer.

`GET /scoutro/api/v1/kg/entities/{id}/suggestions?collection=ORIGIN&offset=0&limit=25`
returns authorized groups, `total`, `next_offset`, target contexts, memberships
and collection-pair `contributions` with scoped supporting statements. Limits:
1–100 groups per page; the business view embeds the first 100 with a More button.
The agent path `/scoutro/api/agent/v1/kg/entities/{id}/suggestions` requires
`kg.read`; collection must be granted, and candidates may reach the remaining
granted collections. Unknown parameters are rejected. No extraction, matching,
schema change or rebuild is performed by these reads. Counts and ordering use
only authorized rows; without an authorized origin the response is 404.

Network JSON and GraphML exports contain the currently shown, authorized nodes
and derivations, their target/membership contexts and supporting contributions.
Load further neighbour pages to include more stored suggestions. General
`kg/export`, downloads, facets and change feeds retain their strict collection
filter and separate export grant. The existing derivation computation limits
still bound which suggestions exist; displaying all authorized collections does
not run additional matching. Reuse existing graph data; no re-extraction needed.

## Services of the same name

A service belongs to its provider: "SAP" of CTcon GmbH (ctcon.de) and "SAP"
of another company are two objects, each with its own provider, prices and
sources; nothing is merged, and the IDs stay as they are. The object list
therefore shows each service with its provider and domain
(`SAP` · `CTcon GmbH · ctcon.de · stackfinder-web`); a service whose provider
the selected collection does not show says **No provider assigned**.

Searching a name that several providers use adds a line above the list
("SAP · 133 providers"); it opens **Services**, which counts the services of
one name for reading only: providers, services without a provider, the
collections, the providers' places, how many have a current price and how
many a current source. **All providers** lists every service of the name in
its own row with its provider, domain, collection, place, price count,
quality and sources. Prices are never mixed between providers; each row's
prices are its own service's.

## Network of all providers of a name

**Network** next to a name in **Services** (and **Network of all providers**
above the rows of one name, and next to the line above the object list)
draws every provider of that name: `CTcon GmbH → offers → SAP`. The centre
is the name only, no object (a dashed card "Service name"): nothing is opened
or merged there. Every line is one provider's own `offers` fact and names
that provider's own service, with its own prices, sources and collections;
the services, prices and facts of different providers are never mixed.

- **Providers** are cards like in any network, with their domain,
  collections, places, quality and sources; a provider with two services of
  the name has two lines. A service without a visible provider is counted in
  the centre's panel (**Services without a provider**), not drawn.
- **Paging:** the 50 strongest providers first (24 on a phone), **Show more**
  for the next.
- **Details:** the centre shows the counts of the name (services, providers,
  collections, places, current prices and sources) and **All providers as a
  list**; a provider its lines, each with its own service; a line its
  status, evidence and the provider's own service with **Open service** and
  **Network of this service**.
- **Filters:** status only (current, outdated); depth and the layers of an
  object's network do not apply. The list below the drawing names each line's
  own service; **GraphML** and **JSON** carry it too.
- The network of one object (`?view=network&id=…`) is unchanged.

## Network

The network shows the neighbourhood of one object, never the whole graph:
depth 1 (direct neighbours) or 2 (also the neighbours of organisations and
facilities), the 50 strongest neighbours first and **Show more** for the
next.

- **Nodes** are cards by type (organisation, facility or site, service, job,
  place, industry or audience, price): the name and a second line, the domain
  of an organisation (`CTcon GmbH` / `ctcon.de`), "Service" for a service, the
  place of a facility. The centre is framed in dark blue.
- **Lines** point in the direction the source states and carry their
  relation: offers, partner of, customer of, same carrier, belongs to,
  location, audience, weak link signal, suggested customer or partner. Solid
  lines are facts with evidence, dashed lines uncertain facts, dotted lines
  derived rows, which are no facts. On a crowded drawing the labels appear
  for the lines of the node you point at or select.
- **Around a service** its providers are shown above it (the incoming
  `offers`: `CTcon GmbH → offers → SAP`), with their facilities and, at depth
  2, their relations to other companies, but not their other services (open a
  provider's own network for those). Around a job its employer. Industry,
  audiences, places, prices, jobs, weak signals and suggestions can be added.
  Around an organisation the default shows its relations, structure,
  services, places, industry, audiences, jobs and the same-operator rows.
- **Filters**, grouped: facts (relations between companies, structure and
  operators, services and providers, places, industry, audiences, jobs and
  employers, prices of services), derived (same operator, weak link signals,
  suggested customers and partners), status (current, outdated).
  **Default view** returns to the defaults of the centre's type.
- **Details:** a tap, a click or the keyboard (Tab, Enter) on a node opens
  its panel: name, type, domain, collections, quality, sources, last
  confirmation and its relation to the centre, with **Open object**,
  **Network of this object** and **Open sources**. A line shows its status
  and evidence.
- **Layout:** on a narrow screen (and for many nodes) the drawing is layered:
  what points to the centre above it, what it points to below, wrapped to the
  width, as high as its content; for a few neighbours on a wide screen it is
  radial. The page never scrolls sideways.

The same data is always listed as a table below (**List only** hides the
drawing). **GraphML** (for Gephi or Cytoscape) and **JSON** download what is
shown. No external library is loaded.

## Storage levels

The meters show the graph's storage (budget `scoutro.kg.budget.maxBytes`,
default 10 GiB, for everything in `DATA/SCOUTRO/knowledge`) and the JSON-LD
stored in the Solr index (`scoutro.kg.jsonld.maxTotalBytes`, default 2 GiB).
From 70 % the level is a notice, from 80 % a warning (banner, also in the
dashboard), from 90 % new growth pauses (for JSON-LD: the capture pauses, pages
are still indexed), and the budget is the hard limit. Crawling and indexing
never stop because of these budgets.

## Controls

**Pause growth** stops new growth only (deletions and state changes continue);
**Resume** ends it and schedules a reconcile; **Reconcile now**; **Confirm
deletions** appears only when the mass-deletion brake stopped a reconcile;
**Retry failed LLM documents**; **Create backup**. All are
`POST /scoutro/api/v1/kg/control` with a JSON body from the same origin;
nothing here changes the Solr index.

## Backups

Backups are single SQLite files in `DATA/SCOUTRO/knowledge/backup`, inside the
app's data, made while the graph runs and checked (`quick_check`, SHA-256).
**Download** keeps a copy outside the app. **Restore** asks for a
confirmation, checks the backup first, keeps the current graph as a backup
("before a restore"), starts a new dataset epoch (export consumers sync again)
and reconciles with the index. Scheduled every `scoutro.kg.backup.intervalDays`
(7); the newest `scoutro.kg.backup.keep` (1) stay.

**Delete** (package 6.3, administrator only) removes one backup for good,
after a question that names its file, type, size and creation date; a copy
"before an upgrade" adds that it is the way back to the previous version.
Afterwards the list and the space of the backups (**Backup files**) are
updated. Only a backup file of the backup folder can be deleted, by its name
(`graph-<UTC>[-before-restore|-before-rebuild|-before-upgrade].db`): never a
path, never the live `graph.db` or its `-wal`/`-shm` files, never a link or a
file outside that folder. Its metadata file goes with it. While a backup,
restore or rebuild runs, nothing is deleted (409); download and restore of the
other backups stay as they are.

## LLM tier counters

The **LLM tier** panel of the overview shows the calls, **Accepted entities ·
claims · values**, **Dropped as not quoted** and **Dropped as invalid**. These
numbers count since the last start of Scoutro (since the pod start), only in
memory; every restart resets them, so they are no long-term statistics.

- **Dropped as invalid** counts single entities, claims and values of
  accepted answers that break a rule (an extra field, an unknown type, an id
  that refers to nothing, a relation between the wrong types, …). It is not
  the number of failed calls: one answer can bring valid items and several
  invalid ones. Answers refused as a whole (`invalid_json`, `unknown_field`,
  …) are counted separately in the status (`refusedBy`).
- **Invalid items by reason (since the last start)** (folded) lists the
  reasons that occurred, the most frequent first, with the codes of the API
  (`llm.processed.droppedInvalidByReason`). Each item counts once, under the
  first rule it breaks; the counts add up to **Dropped as invalid**. The
  codes and their rules: `docs/SCOUTRO_KNOWLEDGE_GRAPH.md`, section 6.3.
- These are diagnostic counters only; no model answer, quote or page text is
  stored for them.
- **Structured output** shows what the endpoint is asked for: *Schema
  enforced by the provider* (the model's format capability is supported),
  *Schema sent, enforcement not confirmed* (capability unknown, or the
  schema forced by `scoutro.kg.llm.structuredOutput`), *JSON only, no
  schema*, *Validator only, no format sent* (capability unsupported or the
  setting `none`) or *Fallback after rejection (HTTP 400)*, with the format
  capability. **Requests by format** counts the requests with schema, JSON
  only and without format and the rejections since the last start. In every
  mode the validator checks each answer the same way. The format capability
  is the technical test of the [LLM selection](LLMSelection_p.md); a value of
  the former mood probe counts as unknown. An Ollama model is asked on
  Ollama's native `/api/chat` with the schema as `format` (status
  `api: ollama_native`); every other service on `/v1/chat/completions`.

## Collections

The overview lists every collection the graph follows, maps or holds
documents of: followed or not, its vocabulary and where it comes from (a
setting or a vocabulary file), jobs, the LLM tier, its documents in the graph
and its state (following, waiting for documents, not followed, vocabulary
missing).

The overview shows the graph in two layers (package 6.3):
**Knowledge graph (rules and structured data)** active or inactive,
**LLM enrichment** active or inactive with its reason, and **Model (usage
knowledge)**: the model chosen for the usage *knowledge* in the
[LLM selection](LLMSelection_p.md). There is no second model setting. The
graph works without the LLM enrichment; it only adds statements (shown as
uncertain, each with a quote of the page).

- With `scoutro.kg.collections=*` a new collection (for example
  `newportal-web`) is followed at once; with a fixed list only the named ones.
- A collection without a vocabulary is shown as **No vocabulary assigned**:
  it gets generic facts (names, addresses, contacts, relations, the industry
  of a declared business type) but no service categories. Its vocabulary is
  never guessed from its name. The defaults are `edelsenior-web` care,
  `checkthecoach-web` coaching, `stackfinder-web` software and
  `bauteamcheck-web` construction.
- Assign an existing vocabulary with `scoutro.kg.vocab.<collection>=<vocabulary>`
  (for example `scoutro.kg.vocab.newportal-web=software`, also under `*`);
  an empty value switches a default off. A new vocabulary, or a mapping, is a
  JSON file in `DATA/SCOUTRO/knowledge/vocabulary/` in the format of
  `defaults/scoutro/knowledge/categories.json`, without a schema change. Both
  take effect at the next start and re-extract the pages concerned.
- Job extraction initially follows collections named in
  `scoutro.kg.jobs.collections` (shown under Settings); a new collection never
  gets them by itself.

### Switching collections on and off (Settings)

**Settings** lists every collection of the collection catalog with its
pages in the index, **Knowledge graph** (On, Off), **Vocabulary**, its
documents in the graph and its state, and **Save** per row. A new collection
(created with **New collection** or with pages in the index) appears by
itself, without a vocabulary, and is followed only under `*`; YaCy's internal
`robot_*` collections never appear.

- **On** follows the collection: its name is added to
  `scoutro.kg.collections` (with `*` the list stays `*`). The pages it already
  has in the index are read through the reconcile every start of the graph
  runs (the existing backfill); its state shows **waiting for documents**
  until they are in.
- **Off** stops reading the collection but **keeps its graph data**: its
  name leaves `scoutro.kg.collections` and is added to
  `scoutro.kg.collections.inactive` (state **switched off, data kept**). The
  reconcile deletes nothing of it and nothing new is read from it, also under
  `*`. A page deleted from the index still leaves the graph, as always; a page
  that also belongs to a followed collection is still read through that one.
- **Vocabulary:** **Default** (of the vocabulary files, e.g. `care` for
  `edelsenior-web`, else none), one of the vocabularies, or **No vocabulary**
  (`scoutro.kg.vocab.<collection>`: unset, a name, empty). A new vocabulary of
  a followed collection makes the graph read its pages again in the
  background.
- Only the keys of that one collection change; the other collections, jobs,
  the LLM tier and all other settings stay as they are. A change takes effect
  at once: the graph is closed cleanly and opened again (a manual pause
  stays). While a backup, restore or rebuild runs, nothing is saved (409).
- An identity rebuild builds the graph from the followed collections only:
  switch a collection on again before a rebuild if its data should stay.

### LLM enrichment per collection (Settings)

The column **LLM enrichment** (On, Off) of each row switches the LLM tier for
that collection; above the table the page shows whether the enrichment is
active and which model of the LLM selection it uses.

- **On** adds the name to `scoutro.kg.llm.collections` after a question that
  names the model; **Off** removes it (an empty list removes the key). Only
  that collection changes; the deterministic graph of the collection stays as
  it is. Applied at once by reopening the graph, like the other settings.
- No collection is switched on by itself, also not a new one or one switched
  on for the knowledge graph. With `scoutro.kg.llm.collections=*` every row
  shows **On (all collections, \*)**; that setting is not rewritten into a
  list here (409 `llm_all_collections`), change it in the Advanced
  Properties.
- The LLM enrichment of a collection works only while the collection is on
  for the knowledge graph and a model has the usage knowledge. Otherwise the
  setting is saved and the row says what it waits for.
- `LLM: off · no_llm_collections` in the status means that no collection is
  switched on for the LLM enrichment; it is not an error of the LLM
  selection. The status names the selected model also then.

## Vocabulary and upgrade

The overview shows the vocabularies in force (categories per collection, NACE
codes), the derived relations (last run, interval
`scoutro.kg.derived.intervalMinutes`, 60) and the upgrade of the last start.
**Recompute suggestions now** runs the derived layer at the next maintenance
step. When a graph of Scoutro 0.7 is upgraded, it is first checked
(`quick_check`) and copied to `graph-<UTC>-before-upgrade.db` in the backup
folder; that copy is the way back to 0.7 and stays through the backup
retention. A graph that fails the check is not touched and the graph stays off
(`upgrade_blocked`). If there is no room for the copy, a banner says so: the
new extraction of every page waits until a backup succeeds.

## Re-resolve identities

**Rebuild identities** builds the whole graph anew from the Solr index with the
current identity rules and vocabulary, in `DATA/SCOUTRO/knowledge/rebuild` and within the
remaining budget, while the current graph keeps serving. The panel shows the
phase and the progress and refreshes itself. Before the swap the new graph is
checked; if it has far fewer pages than the current one it waits for **Swap
in the rebuilt graph**. The current graph is kept as a backup ("before a
rebuild"), stored entity IDs keep leading to their objects. **Cancel the
rebuild**, or a stop of Scoutro, before the swap deletes the new graph and
changes nothing.

## Access And Safety

Administrator access (Digest) is required, for the page and for every API
route it uses (`/scoutro/api/v1/kg/*`). All page and graph text is rendered as
text; links to crawled pages open in a new tab without a referrer.

## Automation And API

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/scoutro/api/v1/kg/status` | GET | Status, with `collections` (vocabulary, jobs, LLM tier, documents and state per collection) and the LLM tier's counters since the start (`llm.processed`, with `valuesAccepted`, `droppedInvalid` and `droppedInvalidByReason`; `llm.structuredOutput`) |
| `/scoutro/api/v1/kg/entities` | GET | Objects (`q`, `type`, `host`, `quality`, `industry`, `category`, `audience`, `offset`, `limit`, `collection`); each item with its `context`: hosts, collections, places, quality, sources and, for a service or job, `providers` and `provider_count` |
| `/scoutro/api/v1/kg/entities/{id}/business` | GET | The business view in sections (`include=hidden_jobs`) |
| `/scoutro/api/v1/kg/entities/{id}/neighborhood` | GET | Nodes (with hosts, collections, places, quality, sources) and edges (with `direction` at the centre) (`depth`, `limit` ≤ 200, `offset`, `types`, `weak`, `derived`, `suggested`, `values`, `prices`, `include=stale`) |
| `/scoutro/api/v1/kg/services` | GET | Services of the same name across providers, read-only groups (`q`, `category`, `offset`, `limit` ≤ 50) |
| `/scoutro/api/v1/kg/services/providers` | GET | The services of one name, each with its provider (`name`, `category`, `offset`, `limit`) |
| `/scoutro/api/v1/kg/services/network` | GET | All providers of one name around a virtual centre (`center` `service_group:<name>`), one line per provider's own `offers` with its own `service` (`name`, `category`, `offset`, `limit` ≤ 200, `include=stale`) |
| `/scoutro/api/v1/kg/collections` | GET | Administrator: the knowledge graph settings of each collection (`active`, `inactive`, `vocabulary`, `vocabularySetting`, `defaultVocabulary`, documents, `state`) |
| `/scoutro/api/v1/kg/collections/{collection}` | PATCH | Administrator: `{"active": true|false, "vocabulary": "care"|""|null}`; changes only that collection's keys and reopens the graph (`applied`, `backfill`, `kept`, `reextract`) |
| `/scoutro/api/v1/kg/compare` | GET | One service category across providers (`category`) |
| `/scoutro/api/v1/kg/derived` | GET | Derived rows (`kind`, `entity`) |
| `/scoutro/api/v1/kg/facets` | GET | Industries, categories, audiences and counts of the visible graph |
| `/scoutro/api/v1/kg/entities/{id}` | GET | Object (or `{"redirect":…}` after a merge) |
| `/scoutro/api/v1/kg/entities/{id}/statements` | GET | Facts and relations (`direction=out|in`, `predicate`, `include=stale`) |
| `/scoutro/api/v1/kg/statements/{id}` / `…/evidence` | GET | One fact and its evidence (≤ 50 per page) |
| `/scoutro/api/v1/kg/hosts/{host}/entities` | GET | Objects of a host |
| `/scoutro/api/v1/kg/sources/{docId}` | GET | What the graph holds from one page |
| `/scoutro/api/v1/kg/control` | POST | `pause`, `resume`, `reconcile`, `confirm_reconcile`, `llm_retry`, `backup`, `restore` (with `backup`), `delete_backup` (with `backup`), `rebuild`, `rebuild_cancel`, `rebuild_confirm`, `derive` |
| `/scoutro/api/v1/kg/backups` | GET | The backup files with their metadata |
| `/scoutro/api/v1/kg/backups/{file}` | GET | Download one backup (SQLite file) |
| `/scoutro/api/v1/kg/export` | GET | Export pages (`cursor`, `limit` ≤ 200, `include=evidence`, `collection`) |
| `/scoutro/api/v1/kg/changes` | GET | Changes after a cursor, with delete notices (`expand=true` adds the records) |
| `/scoutro/api/v1/kg/export/download` | GET | The whole export as a file (`format=ndjson|json`, `include=evidence`, `collection`) |

The export is not a snapshot: after it, read the changes from its
`next_changes` cursor. A cursor that is too old or from before a reset answers
410 with `details.full_sync`; start the export again.

The page and every route need the administrator; backups, restore, rebuild,
the collection settings and the download are never available to agents.

**Agents** get the same reads with the grant `kg.read` and export and changes
with the separate grant `kg.export` (Agents & Access), on
`/scoutro/api/agent/v1/kg/…` (also `…/kg/services/network`), always limited
to their collections. Status, controls, the collection settings and the
download are never available to agents.

**Chat:** for local and administrator use, the chat adds facts of the graph as
numbered sources marked "Scoutro knowledge graph", each linking the page it
was read from, within the chat's collection: also industries, services,
prices (always with their date), jobs (with status; salaries with unit and
date) and incoming relations; suggestions are marked as such. Settings: `scoutro.kg.chat.*`
(shown under Settings).

Settings are `scoutro.kg.*` configuration keys; they take effect at the next
start, except the collection settings saved under Settings, which reopen the
graph at once. Invalid values are listed under Settings and keep the graph off.

## Related Pages

- [SEO / Host Analysis](ScoutroSEO_p.md): tab **Knowledge** per host.
- [Index Browser](IndexBrowser_p.md): **Knowledge** links per domain and page.
- [Dashboard](scoutro-dashboard.md): knowledge graph card.
- [LLM Selection](LLMSelection_p.md): the optional model for the LLM tier.

## Durable system and need history (package B)

**System and need history** opens with ?view=history; filter an actor with
?view=history&entity=kge_… or open an observation with ?view=history&observation=kgo_….
The object view links to this history; a removed live object can still lead to its
authorized historical observations. History and audit events are paginated, with
no hidden totals. Quotes are rendered as text, not executable page markup.

Source state, vacancy status and assertion context are independent. No deadline
or confirmed end means the archived search status is unknown, even for an old
or disappeared posting. A confirmed end does not erase historical system use.
Internal use is qualified with “Zuletzt belegt am …; heutiger Einsatz nicht erneut
bestätigt”. Required/desirable knowledge remains competence, never installed
software. Customer-project work, planned/completed migrations and shutdown have
their own contexts. Later changes affect only a supported system scope.

Observation/source/processing dates remain separate; missing dates are unknown.
Assertion dates retain their original precision. Collection classification is
current, not historical: source reclassification applies to all revisions.
History details and quotes stay reachable without live statements.

**Settings** adds independent extraction, display and stored-signal matching
checkboxes. All initially inherit the previous jobs opt-in; the first edit freezes
display/matching opt-ins. Off deletes no knowledge and changes no permissions.
Only extraction changes need re-extraction. B adds no matching rules.

Read endpoints under /scoutro/api/v1/kg are history (entity/source/after/limit,
limit 1–100), entities/{id}/history, observations/{id}, and
observations/{id}/history (after/limit). Unknown or inaccessible IDs share 404.
Administrator authentication is unchanged. Agent mirrors require kg.read;
history export/changes separately require kg.export. No presets gain grants.

**Download complete authorized history** uses export/download?include=history;
the page export uses export?include=history. Schema scoutro.kg.history.v1 includes
every permitted observation and audit event, not the live export's 20-evidence
sample. Follow all next pages, check complete, then next_changes. Streams have
an explicit complete/incomplete trailer. Re-read changed observation events and
deduplicate stable kgh IDs. This is not a transaction-wide snapshot.

See [upgrade, rollback and lifecycle details](../docs/SCOUTRO_DURABLE_OBSERVATIONS.md).
Rebuild carries archived revisions/corrections/current scopes at final swap;
restore selects the chosen backup and does not merge newer history automatically.
An old schema backup does not preserve observations created after its date.

## Additional service and signal suggestions (package C)

Changing the stored-job matching setting or disabling calculation also updates
contribution change-feed notices when the graph reopens. This works while normal
calculation is paused or deferred. Turning matching off keeps historical knowledge;
turning it on again does not need a new extraction. Scores remain sorting values.
If storage refuses the notice write, reads remain available and use the changed
settings immediately. The overview shows the pending change-feed notices; storage
recovery retries them. A consumer must not infer a completed notification update
from that pending status.

Specific provider services can also match durable system/competence, construction,
energy renovation, leadership/team development and hospital-transition observations.
No declared target industry is required. Care transitions suggest **possible
cooperation partners**, not customers or confirmed cooperation. Six explicit rule
groups are described in `docs/SCOUTRO_MATCHING.md`; there is no arbitrary keyword
or LLM matching.

The suggestion details separate matching strength, service fit and temporal
restriction, show the original archived quotes and source/assignment contexts,
and retain historical dates. An expired/removed vacancy does not refute an earlier
system observation. Competence is not a confirmed installed system. Unresolved
employers are deferred; own IT/SAP consultancy/system-integrator job signals are
excluded while independently grounded reasons remain.

The same checkbox controls list and graph. Other permitted collections remain
tagged and clickable; archive-only targets open history. At most 25 reasons are
previewed; **More reasons** fetches the complete authorized pages. Visible totals,
ranking and pagination use only currently eligible, fully authorized reasons.
Technical scores are sorting values, never success probabilities.

Overview shows the service/signal calculation's examined signals and completed
provider partitions. A deferred work budget resumes later without withdrawing
unprocessed reasons. A provider above the observation limit remains deferred;
other providers continue. Operator limits, upgrade/rollback and API/agent/export
contracts are documented in `docs/SCOUTRO_MATCHING.md`.
