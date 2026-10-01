---
page: htroot/scoutro-dashboard.html
help: help/scoutro-dashboard.md
title: Scoutro Dashboard
package: monitoring
access: admin
kind: admin-page
backend_java: source/net/yacy/htroot/ScoutroDashboard.java
---

# Scoutro Dashboard

`scoutro-dashboard.html` is an administrator-only, read-only overview. It uses
YaCy's existing administrator authentication and configured localhost policy,
even when public search is enabled. There are no request parameters, forms or
state-changing actions. Unknown query/form parameters are ignored. Responses
use `Cache-Control: no-store`.
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
| Start Crawl | `CrawlStartExpert.html` |
| Blacklists | `Blacklist_p.html` |
| System Administration | `Settings_p.html` |
| Autocrawler | `Autocrawl_p.html` |
| Automation | `Automation_p.html?sort=-date_recording` |

Index Browser has no stable collection filter, so collection tiles open its
normal host view. No collection detail page is added. No last-index/crawl
timestamp or CPU figure is shown: these are not needed for the overview and
no additional scan/monitor is introduced to derive them.

## Automation and verification

Agents may fetch the HTML using the existing administrator credentials. This
page is not a new API. There is no discovery-state integration, taxonomy change,
crawl start/stop, delete, commit, restart or configuration write on page load.

Run `ant scoutro-dashboard-test` for focused metrics/security/static checks.
Against a disposable local peer, run the existing `test/scoutro-ui/scoutro-ui-test.mjs`
and `test/scoutro-ui/dashboard-ui-test.mjs` with `SCOUTRO_URL`,
`SCOUTRO_ADMIN_USER`, `SCOUTRO_ADMIN_PASSWORD` and optional `--screenshots DIR`.
