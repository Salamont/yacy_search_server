---
page: htroot/scoutro-dashboard.html
help: help/scoutro-dashboard.md
title: Scoutro Dashboard
package: monitoring
access: admin; read right (protected access mode)
kind: admin-page
backend_java: source/net/yacy/htroot/ScoutroDashboard.java
---

# Scoutro Dashboard

`scoutro-dashboard.html` is an overview. It uses
YaCy's existing administrator authentication and configured localhost policy,
even when public search is enabled. The page itself has no request parameters,
forms or state-changing actions (the site search of the shared header, a GET
to the search page, aside); the only changes are the administrator's "+"
and "−" of the collection list (below), which call the collection API; their
dialogs contain no form either. Unknown
query/form parameters are ignored. Responses use `Cache-Control: no-store`.
The shared template servlet also skips its usual called/submitted-page
registration for this route, so even ignored query parameters cannot write the
navigation-history settings in `yacy.conf`.

The Scoutro entry at the top of the shared administration sidebar opens this
page on desktop and in the mobile hamburger panel. Existing YaCy detail pages
remain the place to browse, configure and control the system.
The dashboard stylesheet hides the shared header/mobile restart and shutdown
controls on this page. The standard navigation on all other pages is unchanged.

## Metric definitions

All index counts come from the **local active Solr `collection1` core**, in the
configured YaCy network (displayed dynamically; normally `webportal` on Scoutro).
No remote mirror index, host discovery state or filesystem outside YaCy is used.

| Metric | Source and definition |
| --- | --- |
| Indexed Pages / Documents | Solr count for `httpstatus_i:200 AND -failtype_s:[* TO *]`: successful indexed URLs, excluding failed/excluded crawl records |
| Websites / Hosts | Exact Solr JSON facet `unique(host_s)` over the same successful documents. A hostname is not a page; subdomains remain separate hosts |
| Collection pages | Query-facet count for `collection_sxt:"<collection-id>"` in the same document scope |
| Collection websites | Exact `unique(host_s)` inside that query facet, when available |
| Collection share | Collection successful documents / all local successful documents × 100. Collections may overlap; shares need not add to 100% |
| Active Crawls | `CrawlSwitchboard.getActive()`, excluding `DEFAULT_PROFILES`, like `Crawler_p.html`. Retained active profiles may be idle |
| Fetching URLs | `crawlQueues.activeWorkerEntries().size()`, as in YaCy status |
| Queued URLs | `crawlQueues.noticeURL.size()`, all pending noticed-URL stacks; excludes the intake stack and worker queue |
| Terminated profiles | Count of retained passive profiles, `crawler.getPassive().size()`. This is not a time-based history |
| Running / Idle | Running when URLs are being fetched or queued work exists and the local crawl job is not paused; profile count alone does not imply Running |
| Index Size | Sum of file lengths in the current local Solr/Lucene index directory; no recursive DATA scan, no caches/logs/webgraph/remote index |
| Memory | YaCy `MemoryControl.used()` / `maxMemory()`, JVM heap, shown in MiB/GiB |
| Uptime | Time since `Switchboard.startupTime` |
| Scoutro Version | YaCy application-root `scoutro.properties` release/upstream fields |
| YaCy Version / Build | `yacyBuildProperties.getVersion()` / `getReleaseStub()` |

One aggregate request uses `rows=0` and docValues facets, with a 1,000 ms Solr
`timeAllowed` budget. No stored-document list is retrieved or enumerated. The
aggregate snapshot is cached in memory for 30 seconds per local connector and
concurrent requests reuse it. This bounds repeated facet work; exact distinct
counts can still require server-side docValues aggregation on a cold cache.
Partial results are rejected rather than displayed as complete counts.
If facets fail, independent count-only queries (also `rows=0`, each budgeted)
recover the overview and each collection separately. A zero count is an empty
collection; `—` means unavailable. Crawler/system sections remain independent.

## Existing detail destinations

| Tile or link | Standard YaCy destination |
| --- | --- |
| Websites, Indexed Pages, Index status, all four collections, Index Browser | `IndexBrowser_p.html?admin=true&hosts=` |
| Active Crawls, Crawler status, Crawler Monitor | `Crawler_p.html` |
| Index Size, Index Administration | `IndexControlURLs_p.html` |
| JVM Memory, JVM Heap, RAM / Disk / Updates | `Performance_p.html` |
| System, versions/build/uptime, System Status | `Status.html?noforward=` |
| Knowledge graph (state, storage used, pending changes) | `ScoutroKnowledge_p.html` |
| Start Crawl | `ScoutroCrawls_p.html#new-crawl` |
| Blacklists | `Blacklist_p.html` |
| System Administration | `Settings_p.html` |
| Autocrawler | `Autocrawl_p.html` |
| Automation | `Automation_p.html?sort=-date_recording` |

Index Browser has no stable collection filter, so collection tiles open its
normal host view. No collection detail page is added. No last-index/crawl
timestamp or CPU figure is shown: these are not needed for the overview and
no additional scan/monitor is introduced to derive them.

## Adding and removing collections

Next to **Scoutro Collections** the administrator sees **+** and **−** (an
account never does; the API refuses it with `403` anyway).

- **+** opens the same **New collection** dialog as *Settings → Collections*
  (`POST /scoutro/api/v1/collections`): display name, ID (suggested from the
  name, cannot be changed later) and an optional description. After the server
  confirmed it the page reloads and shows the new, empty collection as a card.
- **−** opens **Remove collection**. The list offers every collection with its
  page count; one that a Discovery profile names is listed as *Cannot be
  removed* (the profile would crawl into it again). What happens depends on the
  choice, and the dialog says it before anything changes:
  - an **empty collection created in Scoutro** is removed from the list
    (`DELETE /scoutro/api/v1/collections/{id}`): only the entry with name and
    description goes; knowledge-graph settings of that name stay and apply again
    if a collection of the same ID is created later;
  - a **collection with pages** is **hidden** (`POST …/{id}/hide`, 0.10.3): it
    leaves the dashboard and every choice (crawl start, filters, chat, agent
    grants), the knowledge graph stops reading it (its data stay, nothing is
    extracted again) and its pages stay in the index. A search without a
    collection filter still finds them;
  - ticking **Also delete its pages from the index** and typing the collection
    ID deletes its pages (`POST …/{id}/delete-pages`). This cannot be undone.
    Only pages that belong to no other collection are deleted; a page that also
    belongs to another collection stays, and the collection is then hidden. The
    knowledge graph drops the deleted pages by itself.
  The server reads the page count anew and refuses while an account, an agent
  (not revoked), the guest access or a crawl that is not terminated names the
  collection; the dialog then names them (for example "1 accounts, 2 agents").
  Remove the collection there first. Moving pages between collections is
  `POST /v1/collections/reassign`.
- **Hidden collections** are listed below the cards with their page count and
  **Show again** (`POST …/{id}/show`): every choice offers the collection
  again, and the knowledge graph reads it again if hiding had switched it off.
- Every change is in the user audit log (`api.collections POST`,
  `api.collections DELETE`, `api.collections.hide POST`, `api.collections.show POST`,
  `api.collections.delete-pages POST`, each with the collection as target).

## Automation and verification

Agents may fetch the HTML using the existing administrator credentials. This
page is not a new API. There is no discovery-state integration, taxonomy change,
crawl start/stop, delete, commit, restart or configuration write on page load.

Run `ant scoutro-dashboard-test` for focused metrics/security/static checks.
Against a disposable local peer, run the existing `test/scoutro-ui/scoutro-ui-test.mjs`
and `test/scoutro-ui/dashboard-ui-test.mjs` with `SCOUTRO_URL`,
`SCOUTRO_ADMIN_USER`, `SCOUTRO_ADMIN_PASSWORD` and optional `--screenshots DIR`.

## Discovery overview

The compact Discovery Automation card links to `ScoutroDiscovery_p.html`. A read-only request to the administrator Discovery status API displays disabled/paused/enabled and job count. This neither enables the heartbeat nor creates candidate state. If status is unavailable, the existing dashboard metrics remain usable. See [Discovery Automation](ScoutroDiscovery_p.md).

## Knowledge graph

The Knowledge graph card reads `GET /scoutro/api/v1/kg/status` every 15 seconds: state (disabled, running, unavailable, stopped), the share of the storage budget in use, pending changes of the synchronisation (and of the LLM tier, if any) and, while growth is paused, the pause reasons. It only reads; it links to `ScoutroKnowledge_p.html`.

## Native crawls and Discovery status

Start Crawl opens Scoutro Crawls with a mandatory explicit Collection. Discovery shows automation Active/Paused/Disabled separately from Batch running. Read-only worker activity is not a running batch.

Contract, endpoints, permissions, errors, persistence and CLI: [Scoutro crawl flow](../docs/SCOUTRO_CRAWL_FLOW.md).

Scoutro responsive layout: existing navigation and controls are reused at 360/390/412/768 pixels and desktop. Tables display the original rows as labelled cards on narrow screens; control names, values, events and save paths are unchanged.
Dashboard collection links open the Index Browser with the exact `collection` URL parameter.

## Built from the catalog and the rights

The collection cards come from the collection catalog (every selectable
collection, in catalog order), no longer from a fixed list. The
administrator sees all of them with the index, crawler and system figures
and YaCy's tools. An account with the **read** right (protected access
mode) sees only its collections: the totals count only their pages and
hosts, the share refers to its own pages, and instead of system figures it
gets the links to its functions (search, chat, websites, host analysis,
organisations; crawls, Discovery and reports with the **collect** right).
The Discovery card needs the collect right, the knowledge graph card the
administrator. Figures are cached for 30 seconds per set of collections.
See [SCOUTRO_USERS_ACCESS.md](../docs/SCOUTRO_USERS_ACCESS.md) for roles, permissions and the protected access mode.
