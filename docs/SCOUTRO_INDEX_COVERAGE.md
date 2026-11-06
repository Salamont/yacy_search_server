# Index coverage of a provider's places (M9): analysis and proposal

Status: analysis on the development branch of the facilities' first stage ([SCOUTRO_KG_FACILITIES.md](SCOUTRO_KG_FACILITIES.md));
**nothing of it is implemented**. Read only: no production crawl, no change of a crawl job, no reindexing.

## 1. Finding

The read-only diagnosis of `edelsenior-web` (November 2026): of twelve official detail pages of care homes, seven are in the
index, five are not. The first stage of the facilities reads a facility on its own page; a page that is not indexed gives
nothing. The knowledge graph reads every active page of a followed collection (tiers 1 and 2 have no filter of a page's type,
depth or language: `BaseTiers.extract`, `EmbeddedSolrSource.scan`); what is missing is missing in the crawl.

## 2. What the crawl does today (code of 0.14.1)

| Mechanism | Behaviour | Where |
|---|---|---|
| Discovery jobs | 15 pages per domain and depth 2 by default (`batch.max_pages`, `batch.depth`; upper bounds 100 and 5, absolute 10 000 and 10); one candidate per registrable domain, its start address the website of the first map object | `scoutro/discovery/JobSchema.java:21`, `DiscoveryService.java:484`, `tools/scoutro/scoutro-discovery:1507` |
| Every Scoutro crawl start (administrator, account, agent, Discovery) | YaCy's mode `url` only, on exactly its start host, without technical resources and documents (`SKIPPED_ADDRESSES`), with the start marker | `ScoutroActions.dispatchCrawl:1198-1225`, `mustNotMatch:1189-1195` |
| Agent crawls (`crawl.start`) | `scope` `domain` or `subpath`; depth and pages within the agent's limits (default 1 and 50, maximum 3 and 1 000); one crawl per host at a time | `docs/API.md` (agent access), `agents/Agent.java:123-156` |
| YaCy's sitemap mode | `Crawler_p` `crawlingMode=sitemap` loads a sitemap with `SitemapImporter`, but sets the profile's must-match to *everything*, its must-not-match to *nothing* and the depth to 0 | `htroot/Crawler_p.java:383-388, 708-722` |
| Several start addresses | `crawlingURL` takes several addresses, separated by line breaks or `|`, in mode `url` | `htroot/Crawler_p.java:268` |
| `robots.txt` | YaCy reads its `Sitemap:` lines (`RobotsTxtEntry.sitemapList`); nothing in Scoutro uses them | `crawler/robots/RobotsTxtEntry.java:52-99` |
| URL variants | YaCy keeps `/haus-am-see` and `/haus-am-see/` as two documents (two URL hashes); the page's canonical address is stored (`canonical_s`, `canonical_equal_sku_b`) but the knowledge graph does not read it | `CollectionConfiguration.java:860, 1022`, `SolrDoc.PROCESS_FIELDS` |
| JavaScript | not rendered (YaCy's crawler) | — |

## 3. Assessment

- **Not needed for the function of stage 1.** Every detail page that is in the index is read; the missing ones can be crawled with
  what exists, in the development environment and, after the owner's decision, in production:
  - a crawl of the provider's list of places, e.g. `https://www.<provider>/einrichtungen/` with `scope=subpath` and depth 1 or 2
    (agent `crawl.start` within its domain allowlist, or the administrator's crawl page);
  - for Discovery, a job with more pages per domain for providers of several places (`batch.max_pages` up to 100).
- **YaCy's sitemap mode cannot be used as it is.** It replaces the profile's filters: a sitemap crawl would load addresses of other
  hosts, the technical resources and documents Scoutro excludes, and pages without the start marker. Scoutro's guarantees for
  every crawl start (start host only, `SKIPPED_ADDRESSES`, the marker, the collection decisions at indexing) would not hold.
- **URL variants are no defect of the facilities any more.** Stage 1 joins the facility read from both documents into one entity
  (its full address keys it). What remains is that each statement counts both documents as sources (remaining defect R5).

## 4. Proposal for a separate change (M9)

| Step | Change | Safety | Tests |
|---|---|---|---|
| 1. Sitemap seeding without YaCy's sitemap mode | A Scoutro action reads `robots.txt` (`Sitemap:` lines) and `/sitemap.xml` of the start host through YaCy's loader (robots respected), keeps the addresses of exactly that host, optionally below a path (`/einrichtungen/`), without `SKIPPED_ADDRESSES`, at most `maxPages`; they become the start addresses of an ordinary crawl in mode `url` with depth 0 (several start addresses, `Crawler_p.java:268`) | every filter of `dispatchCrawl` stays; the agent's domain allowlist, depth and page limits and the one crawl per host apply; a sitemap index is followed one level, bounded in bytes and entries | unit tests of the filter (other host, subdomain, resource, document, path prefix, limit); a local crawl of a static fixture site in the development environment |
| 2. Facility lists first | Discovery and `crawl.start` may name a path to read first (`/einrichtungen/`), so a provider's list of places and its detail pages come before news and careers within `maxPages` | no new host, no wider scope | crawl order tests |
| 3. Canonical documents | The knowledge graph reads `canonical_s`; a document whose canonical address is another indexed document of its host counts as that document's source (one source per canonical page) | read only on the index; the documents themselves stay | `KgExportTest` counts, a fixture with both URL variants |
| 4. Coverage report | Per collection and host: the detail pages of places that indexed pages link to (`outboundlinks`, webgraph) but that are not indexed, as a list for the operator | read only | report tests on the embedded core |

Order: 1 and 4 give most for every portal (complete lists of places, a list of what is missing); 3 removes the double counts; 2 is
an optimisation. Each is a change of its own with its help and API documentation (`AGENTS.md`).
