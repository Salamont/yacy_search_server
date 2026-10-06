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
| Object | `?view=object&id=kge_…` | Sections with content only: Overview, Industry, Services, Prices, Contacts, Relations, Jobs, Audiences, Suggested matches, Evidence and sources, all facts and relations, relations pointing to it; evidence per fact; link to the network |
| Network | `?view=network&id=kge_…&depth=2&f=…&list=1` | The neighbourhood of one object as a drawing and as a list (see below) |
| Compare | `?view=compare&category=care/tagespflege` | One service category across providers with the prices as published |
| Source | `?view=source&doc=<Solr id>` | What the graph holds from one page: state, tiers, LLM status, every fact with its evidence |
| Settings | `?view=settings` | The effective settings and their problems (read-only) |

Every view takes `collection=<name>`. **Every name, value, count, host and
source is then computed only from documents of that collection**; objects only
other collections know are "not found". Without a collection the administrator
sees all followed collections. Status, storage and events on the overview
describe the whole graph.

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
- **Jobs** (only for `scoutro.kg.jobs.collections`): title, employment type,
  place, salary with unit and date, dates and how to apply. A job ends at its
  deadline or when its page is gone; ended jobs stay visible for
  `scoutro.kg.jobs.endedVisibleDays` (90) days.
- **Audiences** in three layers that never mix: the declared audience
  (customer types, segments, sizes, target industries, sought services,
  service area), published customers and references, and **suggestions**.
- **Suggested matches** combine what one company offers with what another
  seeks (or a shared audience for partners). They are always labelled as a
  suggestion or possible customer, never as a customer relation, and only
  shown to a viewer of both collections.

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

## Collections

The overview lists every collection the graph follows, maps or holds
documents of: followed or not, its vocabulary and where it comes from (a
setting or a vocabulary file), jobs, the LLM tier, its documents in the graph
and its state (following, waiting for documents, not followed, vocabulary
missing).

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
- Jobs are read only for the collections named in
  `scoutro.kg.jobs.collections` (shown under Settings); a new collection never
  gets them by itself.

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
| `/scoutro/api/v1/kg/status` | GET | Status, with `collections` (vocabulary, jobs, LLM tier, documents and state per collection) |
| `/scoutro/api/v1/kg/entities` | GET | Objects (`q`, `type`, `host`, `quality`, `industry`, `category`, `audience`, `offset`, `limit`, `collection`); each item with its `context`: hosts, collections, places, quality, sources and, for a service or job, `providers` and `provider_count` |
| `/scoutro/api/v1/kg/entities/{id}/business` | GET | The business view in sections (`include=hidden_jobs`) |
| `/scoutro/api/v1/kg/entities/{id}/neighborhood` | GET | Nodes (with hosts, collections, places, quality, sources) and edges (with `direction` at the centre) (`depth`, `limit` ≤ 200, `offset`, `types`, `weak`, `derived`, `suggested`, `values`, `prices`, `include=stale`) |
| `/scoutro/api/v1/kg/services` | GET | Services of the same name across providers, read-only groups (`q`, `category`, `offset`, `limit` ≤ 50) |
| `/scoutro/api/v1/kg/services/providers` | GET | The services of one name, each with its provider (`name`, `category`, `offset`, `limit`) |
| `/scoutro/api/v1/kg/compare` | GET | One service category across providers (`category`) |
| `/scoutro/api/v1/kg/derived` | GET | Derived rows (`kind`, `entity`) |
| `/scoutro/api/v1/kg/facets` | GET | Industries, categories, audiences and counts of the visible graph |
| `/scoutro/api/v1/kg/entities/{id}` | GET | Object (or `{"redirect":…}` after a merge) |
| `/scoutro/api/v1/kg/entities/{id}/statements` | GET | Facts and relations (`direction=out|in`, `predicate`, `include=stale`) |
| `/scoutro/api/v1/kg/statements/{id}` / `…/evidence` | GET | One fact and its evidence (≤ 50 per page) |
| `/scoutro/api/v1/kg/hosts/{host}/entities` | GET | Objects of a host |
| `/scoutro/api/v1/kg/sources/{docId}` | GET | What the graph holds from one page |
| `/scoutro/api/v1/kg/control` | POST | `pause`, `resume`, `reconcile`, `confirm_reconcile`, `llm_retry`, `backup`, `restore` (with `backup`), `rebuild`, `rebuild_cancel`, `rebuild_confirm`, `derive` |
| `/scoutro/api/v1/kg/backups` | GET | The backup files with their metadata |
| `/scoutro/api/v1/kg/backups/{file}` | GET | Download one backup (SQLite file) |
| `/scoutro/api/v1/kg/export` | GET | Export pages (`cursor`, `limit` ≤ 200, `include=evidence`, `collection`) |
| `/scoutro/api/v1/kg/changes` | GET | Changes after a cursor, with delete notices (`expand=true` adds the records) |
| `/scoutro/api/v1/kg/export/download` | GET | The whole export as a file (`format=ndjson|json`, `include=evidence`, `collection`) |

The export is not a snapshot: after it, read the changes from its
`next_changes` cursor. A cursor that is too old or from before a reset answers
410 with `details.full_sync`; start the export again.

The page and every route need the administrator; backups, restore, rebuild
and the download are never available to agents.

**Agents** get the same reads with the grant `kg.read` and export and changes
with the separate grant `kg.export` (Agents & Access), on
`/scoutro/api/agent/v1/kg/…`, always limited to their collections. Status,
controls and the download are never available to agents.

**Chat:** for local and administrator use, the chat adds facts of the graph as
numbered sources marked "Scoutro knowledge graph", each linking the page it
was read from, within the chat's collection: also industries, services,
prices (always with their date), jobs (with status; salaries with unit and
date) and incoming relations; suggestions are marked as such. Settings: `scoutro.kg.chat.*`
(shown under Settings).

Settings are `scoutro.kg.*` configuration keys; they take effect at the next
start. Invalid values are listed under Settings and keep the graph off.

## Related Pages

- [SEO / Host Analysis](ScoutroSEO_p.md): tab **Knowledge** per host.
- [Index Browser](IndexBrowser_p.md): **Knowledge** links per domain and page.
- [Dashboard](scoutro-dashboard.md): knowledge graph card.
- [LLM Selection](LLMSelection_p.md): the optional model for the LLM tier.
