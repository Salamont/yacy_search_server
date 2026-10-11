# Scoutro access and UI baseline (before user accounts)

This document records the state of `origin/main` before the Scoutro user,
role and navigation work ([SCOUTRO_USERS_ACCESS.md](SCOUTRO_USERS_ACCESS.md)).
Every finding names the file and line it is based on. Earlier reports were
treated as hints only; the code at the commit below is authoritative.

| | |
|---|---|
| Baseline commit | `origin/main` = `8812ad2f901ede7ebe6ea27764d30889c8a7b866` (merge of PR #44, release 0.9.0) |
| Open PRs at that time | #45 `fix/scoutro-kg-sync-scheduler` (draft). Overlapping files: `htroot/ScoutroKnowledge_p.html`, `htroot/env/scoutro/knowledge.js`, `locales/de.lng`, `htroot/env/scoutro/api/{actions,openapi}.json`, `docs/API.md`, `test/scoutro-api/test_api.py`. Its changes are **not** part of this work; textual conflicts are expected and must be resolved when either PR is rebased. |
| Olares package | `Salamont/YaCy-Search-Community`; the files cited below are identical on `main` (0.5.9) and `release/0.9.0` (read through the GitHub API, read-only). Nothing was read from or changed on the production server. |

Legend: **E** existing function, **G** gap, **R** recommendation.

## 1. Human authentication

| # | Finding | Evidence | Kind |
|---|---|---|---|
| A1 | People use exactly one built-in administrator account. Its hash is `adminAccountBase64MD5` (`MD5:` + MD5(user:realm:password) for Digest), its name `adminAccountUserName`. There is no other human account; YaCy's former `UserDB` no longer exists in this code base. | `Jetty12HttpServer.java:563-637` (`AdminLoginService`, one user), `ConfigAccounts_p.java:70-83` | E/G |
| A2 | The container authenticates with HTTP Digest (MD5). Browsers show the native login dialog; there is no login page, no logout, no session expiry and no session revocation. | `defaults/web.xml:180-188`, `Jetty12HttpServer.java:485-496` | G |
| A3 | The container decides per path: `_p.` pages and `/scoutro-dashboard.html` are always protected; `/solr/` and `/gsa/` only when `publicSearchpage=false`; `adminAccountAllPages` protects all but `/yacy/` and `/solr/`. The agent path `/scoutro/api/agent/` is exempt. | `AdminSecurity.java:61-92`, applied in `Jetty12HttpServer.java:518-545` | E |
| A4 | Localhost exception: with `adminAccountForLocalhost=true` (YaCy default, `defaults/yacy.init:479`) a loopback request with a local or missing referer is admin without login. The Scoutro image sets it to `false` (`docker/Dockerfile.scoutro:62`). Independently, loopback requests that send `Basic admin:<stored hash>` are admin (`AdminSecurity.java:116-124`, used by `YaCyLoopback`). | `AdminSecurity.java:101-104,194-208`, `Switchboard.java:3929-3933,3964-3970` | E |
| A5 | `Switchboard.adminAuthenticated` returns admin (4) for a container principal in role `adminRight` only when an `Authorization` header is present or the last admin access is younger than 60 s; a principal from any other container mechanism would not be recognised. | `Switchboard.java:3919-4005` | G (relevant for sessions) |
| A6 | `verifyAuthentication` treats levels 2-4 as admin; Scoutro pages call it in addition to the container check. | `Switchboard.java:4014-4031`; `ScoutroSEO_p.java:16`, `ScoutroKnowledge_p.java:30`, `ScoutroCrawls_p.java:12`, `ScoutroDiscovery_p.java:14` | E |
| A7 | Transaction tokens (`transactionToken`) are an HMAC of user name and path with a per-start secret; local requests are exempt. Scoutro pages do not use them. | `TransactionManager.java:64-94,157-185` | E |
| A8 | The Scoutro API servlet re-checks `isUserInRole("adminRight")` per route (`requireAdmin`); the container additionally requires `adminRight` for `/scoutro/api/v1/*` except `health` and `ui/*`. Mutating calls require `application/json` and a same-origin `Origin`; there is no CSRF token. | `ScoutroApiServlet.java:386-391,396-430`, `defaults/web.xml:190-208` | E |
| A9 | Most read-only Scoutro functions (dashboard, SEO/host analysis, index browser, knowledge graph, crawl reports, collection catalog, search API) are administrator-only. | `ScoutroApiServlet.java:160-297` (every case calls `requireAdmin`), `AdminSecurity.java:86-87` | G |
| A10 | Login throttling exists only for agent tokens (30 failures per client and minute). Digest has none. | `AgentAuthorizer.java:77,122` | G |

## 2. Agents (service identities)

| # | Finding | Evidence | Kind |
|---|---|---|---|
| B1 | Agents have their own tokens (`sca_<id>.<secret>`, stored as HMAC-SHA256 with a server pepper), a fixed list of action grants, a collection scope (`collections` or `allCollections`) and limits. | `AgentStore.java:47-142`, `AgentTokens.java:44,108-123`, `Agent.java:84-119` | E |
| B2 | Authorization is deny-by-default: status, known action, grant, kind, every requested collection in scope, rate. | `AgentAuthorizer.java:88-122` | E |
| B3 | Scope enforcement is server-side: `collection:` in a query is refused, the effective collection set is computed from the scope, multi-collection searches are merged, crawls refuse hosts with documents outside the scope, agents see only their own crawls, the knowledge graph uses the scope as `permitted`. | `ScopedActions.java:164-183,189-262,318,373-378,536-551`; `KnowledgeRead` via `KgReader.within` (`KgReader.java:169-175`) | E |
| B4 | Agent audit: JSON lines in `DATA/SETTINGS/scoutro-agent-audit.jsonl`, ring of 20,000 entries, no request bodies or secrets; it records the query `collection`, not the effective scope or a crawl body collection. | `AgentAudit.java:48-80`, `AgentApi.java:421-427` | E/G |

## 3. Collections

| # | Finding | Evidence | Kind |
|---|---|---|---|
| C1 | The catalog is the union of the Solr facet `collection_sxt` (10 s cache), explicitly created collections (`DATA/SCOUTRO/collections.json`) and Discovery profile collections; `robot_*` names are internal. | `CollectionCatalog.java:48-56,139-151,165-216` | E |
| C2 | `GET /scoutro/api/v1/collections` lists the whole catalog; `POST` creates one (admin). The "New collection" dialog (`collections.js`, `collection-create.template`) and the dropdowns on Crawls, Index Browser, SEO, Knowledge, crawl start and import pages use it. | `ScoutroApiServlet.java:191-205`, `htroot/env/scoutro/collections.js:27-126`, `htroot/env/scoutro/collection-select.js` | E |
| C3 | The dashboard hard-codes the four portal collections and does not use the catalog. | `DashboardMetrics.java:16-21,59-81`, `ScoutroDashboard.java:84-98` | G |
| C4 | Further hard-coded portal collections exist as knowledge-graph defaults (vocabulary hints), not as UI lists. | `KgConfig.java:126-127,332`, `extract/KindHints.java:44-49`, `defaults/scoutro/knowledge/categories.json:6-9` | E (out of scope) |
| C5 | On the administrator API `collection` is a view filter only (`SeoAnalysis.adminCollections`, `null` = whole index); `v1/search` and `v1/index/lookup` ignore it. | `SeoAnalysis.java:125-130`, `ScoutroActions.java:186,365` | G |
| C6 | Reusable scoped back ends exist and take a collection list: `IndexBrowse.browse`, `SeoAnalysis.route`, `IndexMetrics.read`, `DomainCandidates.page/export`, `ReportApi` with `Scope`, `KnowledgeRead.route(view, permitted)`, `ScoutroActions.collectionFilter/indexLookup/indexEvidence/hostResolve/countHostOutside`. | `IndexBrowse.java:34-75`, `ReportApi.java:29-45,126-130`, `KnowledgeRead.java:108-126` | E |

## 4. Public and native routes

| # | Route | Protection at baseline | Collection handling | Evidence |
|---|---|---|---|---|
| D1 | `/yacysearch.*`, `index.html` | public when `publicSearchpage=true` (default `defaults/yacy.init:1169`) | client chooses `collection` or `collection:` in the query; the value is not validated before it becomes a Solr filter | `yacysearch.java:114-115,492-493`, `QueryModifier.java:391-410` |
| D2 | `/suggest.*` | no check at all, also with `publicSearchpage=false` | none (whole-index dictionary) | `suggest.java:57-73` |
| D3 | `/solr/select`, `/solr/*/select` | public when `publicSearchpage=true` | client controls `q`/`fq` | `SolrSelectServlet.java:133-134`, `AdminSecurity.java:88-90` |
| D4 | MCP `/tools*` | no authentication, CORS `*`, also with `publicSearchpage=false` | `collection:` in the query is honoured | `MCPSearchServlet.java:74-83`, `defaults/web.xml:136-143`, `RAGAugmentor.java:372-374` |
| D5 | RAG `/v1/chat/completions` | AI Shield: local connection, admin, or guest if `ai.shield.allow-nonlocalhost=true` | one explicit collection; a guest **without** a collection always gets the whole index (`ChatCollections.permits(..., null, ...)` is true) | `RAGProxyServlet.java:135-155,218-238`, `ChatCollections.java:70-72` |
| D6 | `/v1/models`, `/api/tags` | public model usage list; proxied admin operations need admin | – | `OllamaTagsServlet.java:50-85`, `LLMAdminProxyServlet.java:139-160` |
| D7 | Downloads (`kg/export/download`, `index/domains/export`, `kg/backups/{name}`) | admin | collection is a view filter; backups contain everything | `ScoutroApiServlet.java:222-226,257-271` |
| D8 | Old YaCy pages (`yacydoc.html`, `ViewFile.html`, `CrawlResults.html`, `/api/*`, `ViewImage.*` …) | public unless `adminAccountAllPages=true` | none | `AdminSecurity.java:84-86` |

Consequence: at the baseline, collection rights cannot be given to people.
Whoever reaches the port (or the Olares UI entrance) can read every
collection through D1-D4 and D8 unless the instance is locked down with
`publicSearchpage=false` and `adminAccountAllPages=true`, and even then MCP
and `suggest` stay open (D2, D4).

## 5. Olares (package configuration, not the production server)

| # | Finding | Evidence (`YaCy-Search-Community`) |
|---|---|---|
| O1 | UI entrance `yacysearch`, port 8090, `authLevel: private` (behind the Olares login), no path restrictions. | `yacysearch/OlaresManifest.yaml:22-28`, `templates/service.yaml:13-17` |
| O2 | API entrance `yacysearch-api`, port 8091, `authLevel: internal`, served by an nginx sidecar that forwards **only** `/scoutro/api/` to `127.0.0.1:8090` and answers 404 otherwise; it sets `X-Real-IP`/`X-Forwarded-For` and never injects credentials. YaCy therefore sees API-entrance requests from loopback. | `files/api-proxy.conf:9-34`, `templates/deployment.yaml:147-189` |
| O3 | The package sets only `server.https=false` and, on first install, the admin password through `bin/passwd.sh`. `publicSearchpage`, `adminAccountForLocalhost` and `host` keep the image defaults. | `files/init-config.sh:23-40`, `OlaresManifest.yaml:191-201` |
| O4 | No Olares single sign-on interface is documented or used: no `X-BFL-USER`/`Remote-User` handling, the proxy neither sets nor strips such headers. | search over the package repository; `docs/INSTALL.md:131-132` |

**R:** Olares SSO is not integrated (no documented, verifiable interface).
Scoutro login works the same with or without Olares. Whether the Olares UI
window shares the Scoutro cookie (same site) must be checked on Olares before a
rollout (see the rollout checklist in SCOUTRO_USERS_ACCESS.md).

## 6. Navigation and pages

| # | Finding | Evidence | Kind |
|---|---|---|---|
| N1 | One header template serves all ~100 admin pages. The top bar holds Re-Start and Shutdown next to Forum, Help, Sponsor, Chat and Search. | `htroot/env/templates/header.template:81-176` | G |
| N2 | The sidebar mixes a Scoutro group (Dashboard, Discovery, Crawls, SEO, Knowledge), an AI Lab group and the YaCy groups First Steps, Monitoring (with the Scoutro Index Browser), Production, Administration (with Agents & Access), Search Portal Integration. | `header.template:184-250` | G |
| N3 | Mobile shows extra groups (Configuration, System with Re-Start/Shutdown, Help) that do not exist on desktop, so the information structure differs between desktop and phone. | `header.template:252-278`, `scoutro.css:38-47` | G |
| N4 | Several Scoutro pages are not in the navigation (`LLMSelection_p.html`, `ScoutroAgentWizard_p.html`, `ConfigAccounts_p.html` on desktop) and `UiRoutes` misses the dashboard and the chat. | `submenuAI.template:6`, `UiRoutes.java:46-84` | G |
| N5 | The knowledge page mixes research (objects, services, compare, network, sources, history) with operations: recompute suggestions, pause/resume, reconcile, LLM retry, backups with restore/delete, identity rebuild, LLM timing, collection on/off and LLM enrichment. | `htroot/ScoutroKnowledge_p.html:22-63,177-212`, `knowledge.js:98-171,311-323,1556-1723` | G |
| N6 | Chat has its own collection select (local/admin: all; AI Shield guests: `ai.shield.guest-collections`). | `yacychat.html:915-924`, `yacychat.java:69-85`, `ChatCollections.java` | E |
| N7 | Design tokens and components exist and are reused: `brand/brand.css` tokens, `theme.css` (admin bar, sidebar, pill tabs, cards, forms, empty state), page families `sseo-*`, `skg-*`, `scc-*`, `scoutro-dash-*`. Loading states are text only. | `brand/brand.css:8-41`, `theme.css:58-539,1100-1129` | E |

## 7. Verification of the reported preliminary findings

| Preliminary finding | Result |
|---|---|
| People use one built-in administrator account | **Confirmed** (A1). |
| Read-only Scoutro functions often require admin rights | **Confirmed** — all of them do (A9). |
| Agents already have differentiated action and collection rights | **Confirmed** (B1-B3). |
| Navigation mixes Scoutro functions and YaCy administration | **Confirmed** (N1-N3). |
| The knowledge-graph view mixes research and operations | **Confirmed** (N5). |
| The dashboard partly hard-codes the four portal collections | **Confirmed** (C3); other hard-coded uses are knowledge-graph defaults (C4). |

Additional gaps found: `suggest.json` and MCP are unauthenticated even with
`publicSearchpage=false` (D2, D4); RAG guests without a collection read the
whole index (D5); the `yacysearch` collection value reaches the Solr filter
without validation (D1); the administrator search ignores `?collection` (C5).
