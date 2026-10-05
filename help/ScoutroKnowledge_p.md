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

The knowledge graph collects organisations, facilities, sites and services from
the crawled pages of the followed collections, with every fact tied to the page
it comes from. This page shows its status, its objects with their facts and
relations, and the evidence behind each fact. Plan:
`docs/SCOUTRO_KNOWLEDGE_GRAPH.md`; API: `docs/API.md`, section "Knowledge graph".

## Views

| View | URL | Content |
| --- | --- | --- |
| Overview | `ScoutroKnowledge_p.html` | State, storage budget, synchronisation, LLM tier, controls, recent events |
| Objects | `?view=objects&q=&type=&quality=&host=` | Search by name, filter by type, quality and host; 25 per page |
| Object | `?view=object&id=kge_…` | Names, identifiers, hosts, possible duplicates, facts and relations, relations pointing to it; evidence per fact |
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

## Controls

**Pause growth** stops new growth only (deletions and state changes continue);
**Resume** ends it and schedules a reconcile; **Reconcile now**; **Confirm
deletions** appears only when the mass-deletion brake stopped a reconcile;
**Retry failed LLM documents**. All are `POST /scoutro/api/v1/kg/control` with
a JSON body from the same origin; nothing here changes the Solr index.

## Access And Safety

Administrator access (Digest) is required, for the page and for every API
route it uses (`/scoutro/api/v1/kg/*`). All page and graph text is rendered as
text; links to crawled pages open in a new tab without a referrer.

## Automation And API

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/scoutro/api/v1/kg/status` | GET | Status |
| `/scoutro/api/v1/kg/entities` | GET | Objects (`q`, `type`, `host`, `quality`, `offset`, `limit`, `collection`) |
| `/scoutro/api/v1/kg/entities/{id}` | GET | Object (or `{"redirect":…}` after a merge) |
| `/scoutro/api/v1/kg/entities/{id}/statements` | GET | Facts and relations (`direction=out|in`, `predicate`, `include=stale`) |
| `/scoutro/api/v1/kg/statements/{id}` / `…/evidence` | GET | One fact and its evidence (≤ 50 per page) |
| `/scoutro/api/v1/kg/hosts/{host}/entities` | GET | Objects of a host |
| `/scoutro/api/v1/kg/sources/{docId}` | GET | What the graph holds from one page |
| `/scoutro/api/v1/kg/control` | POST | `pause`, `resume`, `reconcile`, `confirm_reconcile`, `llm_retry` |
| `/scoutro/api/v1/kg/export` | GET | Export pages (`cursor`, `limit` ≤ 200, `include=evidence`, `collection`) |
| `/scoutro/api/v1/kg/changes` | GET | Changes after a cursor, with delete notices (`expand=true` adds the records) |
| `/scoutro/api/v1/kg/export/download` | GET | The whole export as a file (`format=ndjson|json`, `include=evidence`, `collection`) |

The export is not a snapshot: after it, read the changes from its
`next_changes` cursor. A cursor that is too old or from before a reset answers
410 with `details.full_sync`; start the export again.

**Agents** get the same reads with the grant `kg.read` and export and changes
with the separate grant `kg.export` (Agents & Access), on
`/scoutro/api/agent/v1/kg/…`, always limited to their collections. Status,
controls and the download are never available to agents.

**Chat:** for local and administrator use, the chat adds facts of the graph as
numbered sources marked "Scoutro knowledge graph", each linking the page it
was read from, within the chat's collection. Settings: `scoutro.kg.chat.*`
(shown under Settings).

Settings are `scoutro.kg.*` configuration keys; they take effect at the next
start. Invalid values are listed under Settings and keep the graph off.

## Related Pages

- [SEO / Host Analysis](ScoutroSEO_p.md): tab **Knowledge** per host.
- [Index Browser](IndexBrowser_p.md): **Knowledge** links per domain and page.
- [Dashboard](scoutro-dashboard.md): knowledge graph card.
- [LLM Selection](LLMSelection_p.md): the optional model for the LLM tier.
