# Scoutro

## What is Scoutro?

Scoutro is an independent community project: a mobile-first search, crawling
and indexing application **based on [YaCy](https://yacy.net/)**. It keeps the
complete technical functionality of YaCy (crawler, index, peer-to-peer
network, search APIs, administration) and adds its own user interface layer
and branding:

- a simple, modern search interface for people and local AI agents,
- an administration that is fully usable on smartphones,
- its own name and visual identity.

Scoutro does **not** hide where it comes from. The visible product name is
Scoutro. YaCy is named as the technical foundation in the footer and on the
About page.

> Scoutro is an independent community project based on YaCy.
> YaCy is developed by the YaCy project.
> Scoutro is not affiliated with or endorsed by the YaCy project.

## YaCy as the technical foundation

| | |
|---|---|
| Upstream project | YaCy, <https://yacy.net/> |
| Upstream repository | <https://github.com/yacy/yacy_search_server> |
| Upstream maintainer | Michael Peter Christen and the YaCy contributors (see `AUTHORS`) |
| Scoutro repository | <https://github.com/Salamont/yacy_search_server> (GitHub fork of the upstream repository) |
| Base version | YaCy 1.942, upstream `master` at commit `b50b76bd552a851f737b98683f20d8b861e137ae` (2026-09-23) |

The base commit is the exact revision of the official image
`docker.io/yacy/yacy_search_server@sha256:55c5e5dd…6475` (OCI label
`org.opencontainers.image.revision`), which the existing Olares installation
runs. Keeping this base means the existing `DATA` directory stays compatible.

Synchronisation with upstream is described in [UPSTREAM.md](UPSTREAM.md).

## License

- YaCy is licensed under the **GNU General Public License, version 2 or (at
  your option) any later version** (`gpl.txt`, `LICENSES/GPL-2.0-or-later.txt`,
  `COPYRIGHT`). Parts in `source/net/yacy/cora` are LGPL-2.1-or-later
  (`lgpl21.txt`). Third-party notices are in `NOTICE` and in the license
  headers of the bundled libraries.
- Scoutro is a modified version of YaCy and is distributed under the **same
  license, GPL-2.0-or-later**. All Scoutro changes in this repository are
  licensed under GPL-2.0-or-later.
- The complete source code of every Scoutro release is this repository at the
  corresponding tag. Container images built from it point to the source via
  the OCI labels `org.opencontainers.image.source` and
  `org.opencontainers.image.revision`.
- Existing license files, copyright notices and headers of YaCy are kept
  unchanged. They must not be removed. This includes the LibreJS license block
  in `htroot/env/templates/metas.template` and the list in `htroot/jslicense.html`.

## Community status

Scoutro is a private community development. It is **not** an official YaCy
product, it is not published in the Olares Market, and it is not affiliated
with or endorsed by the YaCy project. The name "YaCy" is used only to
describe the technical origin.

## Differences from upstream

Scoutro keeps the YaCy core (Java sources, index format, storage, ranking,
crawler, P2P protocol, authentication, API contracts) unchanged. The changes
are limited to the web interface, assets, documentation, tests and build
files, plus an additive API adapter (a new Java package registered through
YaCy's `web.xml` extension point; no existing YaCy class is changed):

| Area | Change | Files |
|---|---|---|
| Mobile navigation | The hamburger menu opens the complete administration menu. Before, the whole left navigation was hidden below 768 px and the toggle was covered by the search field. | `htroot/env/templates/header.template`, `htroot/env/scoutro/` |
| Responsive admin | Wide tables scroll, form controls fit the screen, form labels stack, touch-sized controls. | `htroot/env/scoutro/scoutro.css`, `htroot/env/scoutro/scoutro.js` |
| Branding | Product name, logo and favicon configured in one place, Scoutro start page, attribution footer, About page. | `htroot/env/templates/scoutro/`, `htroot/env/scoutro/`, `htroot/scoutro-about.html` |
| Theme | Modern design derived from the Scoutro artwork for search and administration, dark mode for the public search pages. | `htroot/env/scoutro/theme.css`, `htroot/env/scoutro/brand/brand.css` |
| Tests | Reproducible mobile UI checks (Playwright). | `test/scoutro-ui/` |
| Build | Scoutro container image, Scoutro version file. | `docker/Dockerfile.scoutro`, `scoutro.properties` |
| Agent API | Stable JSON action layer over existing YaCy endpoints (search, crawls, index, system, allowlisted settings, UI routes), OpenAPI and action catalog, `scoutroctl` CLI. | `source/net/yacy/scoutro/`, `defaults/web.xml` (marked blocks), `htroot/env/scoutro/api/`, `tools/scoutro/`, `docs/API.md`, `docs/ACTIONS.md` |
| Research worker | Clustro pull worker: research with sources, portal evaluation (discovery classification), limited crawls, through the agent path with its own token. | `tools/scoutro/agent/`, `test/scoutro-agent/` |
| Agent access | Agent identities with own tokens, fixed action grants, collection scopes and limits; agent path `/scoutro/api/agent/v1` with server-side scope enforcement; "Agents & Access" pages with wizard; activity log. | `source/net/yacy/scoutro/agents/`, `source/net/yacy/scoutro/api/{AgentApi,ScopedActions,AgentAdmin}.java`, `htroot/Scoutro*_p.html`, `source/net/yacy/http/AdminSecurity.java` (marked exemption), `tools/scoutro/server/`, `docs/API.md`, `docs/SERVER_AGENT_ACCESS.md` |
| Knowledge graph (foundation and synchronisation) | Embedded SQLite store with storage budget, WAL/reader limits, pause/resume and status; synchronisation with the embedded Solr core (update processor with a bounded change set, persistent queue, real-time get, reconcile with verified deletions and mass-deletion brake, document states, JSON-LD and rule-based extraction, identity resolution, change feed, retention), a bounded JSON-LD capture in the HTML parser with its own budget, and an optional LLM tier (usage `knowledge` in the LLM selection, opt-in; own threads and queue, timeouts, circuit breaker, per-host cap, cache; only relations quoted verbatim from the page are kept, as uncertain; the knowledge model is never offered as a chat model). Off by default (`scoutro.kg.enabled=false`), then no files, no threads and no capture. New dependency `org.xerial:sqlite-jdbc` (Apache-2.0, see `NOTICE`). Admin routes `/scoutro/api/v1/kg/status`, `/kg/control` and the read routes (`/kg/entities`, `/kg/statements`, `/kg/hosts/{host}/entities`, `/kg/sources/{docId}`), all computed per collection from visible evidence only; admin page `ScoutroKnowledge_p.html` (overview, objects, object, source, settings), a Knowledge tab in the SEO host analysis, Knowledge links in the Index Browser and a dashboard card. Export pages and a change feed with delete notices (`/kg/export`, `/kg/changes`; 410 with a full-sync hint for an expired cursor), an administrator-only download (`/kg/export/download`, NDJSON or JSON), the separate agent grants `kg.read` and `kg.export` on `/scoutro/api/agent/v1/kg/...` (always limited to the agent's collections, never status, control or download), `scoutroctl kg` and the MCP tools, and graph facts in the RAG chat as labelled, numbered sources (local and administrator access, the chat's collection, time and character budget, `scoutro.kg.chat.*`). Storage budgets of 10 GiB for the graph and 2 GiB for JSON-LD in the index with notice, warning, brake and full levels (70/80/90/100 %; the crawl never stops), verified backups and restore inside the app's DATA (`/kg/backups`, download to keep a copy elsewhere), and the identity rebuild in a shadow graph with progress, cancel, the mass-deletion brake and a swap that keeps the previous graph. Only role e-mail addresses and no person names in evidence (O7). Vocabulary 2 (schema 4) makes it a business graph: services with categories and prices exactly as published (date, conditions, staleness, visible conflicts), fifteen directed relations between organisations from explicit statements only, the industry as a NACE Rev. 2.1 / WZ 2025 code at its safe level, organisation contacts without persons, job postings (opt-in per collection, ended ones visible for 90 days), the audience in the separate layers declared, observed and suggested, and a derived layer of weak links, shared operators and suggested matches that are never facts and visible only with both collections; the object view in sections, the network view (SVG, keyboard, list, GraphML), the price comparison, the routes `/kg/entities/{id}/business`, `/neighborhood`, `/kg/compare`, `/kg/derived`, `/kg/facets` for administrators and `kg.read` agents, chat facts that follow the question; the upgrade from 0.7 checks the graph and keeps a verified copy first. Package 6.1: every hit with its provider and domain (one service per provider, never merged), services of the same name across providers as read-only groups (`/kg/services`, `/kg/services/providers`), the network around a service with its providers, card nodes, labelled lines, a layered layout for phones and a detail panel, and per-collection status of vocabulary, jobs, LLM tier and documents (no vocabulary is guessed; `scoutro.kg.vocab.<collection>` also under `*`). Operator guide: `docs/SCOUTRO_KNOWLEDGE.md`. Nothing is written back to Solr. | `source/net/yacy/scoutro/knowledge/`, `source/net/yacy/scoutro/api/KnowledgeApi.java`, `defaults/solr/solrconfig.xml`, `defaults/solr/schema.xml`, `defaults/solr.collection.schema`, `ContentScraper.java`, `CollectionConfiguration.java`, `CollectionSchema.java`, `ivy.xml`, `NOTICE`, `LLM.java` (usage `knowledge`, optional read timeout and response limit), `LLMSelection_p.*`, `RAGProxyServlet.java`, `OllamaTagsServlet.java`, `OpenAIModelsServlet.java`, `ScoutroKnowledge_p.*`, `env/scoutro/knowledge*.{js,css}`, `defaults/scoutro/knowledge/` (vocabularies, NACE), `ScoutroSEO_p.html`, `seo.js`, `IndexBrowser_p.html`, `index-browser.js`, `scoutro-dashboard.html`, `header.template`, `UiRoutes.java`, `YaCyDefaultServlet.java` (read-only page list), `AgentActionRegistry.java`, `AgentApi.java`, `ScopedActions.java`, `ScoutroApiServlet.java`, `net/yacy/ai/rag/GraphFacts.java`, `RagContext.java`, `yacychat.html`, `tools/scoutro/scoutroctl`, `docs/SCOUTRO_KNOWLEDGE_GRAPH.md`, `docs/SCOUTRO_KNOWLEDGE.md`, `docs/API.md`, `docs/ACTIONS.md` |
| Docs | This document, upstream workflow, build, UI structure. | `docs/`, top of `README.md` |

`docs/UI.md` describes the UI structure and each adjustment in detail.

## Attribution

Required and kept visible:

- **In the UI:** "Powered by YaCy" in the footer of every page, with a link to
  the About page. The About page (`scoutro-about.html`) states the origin, the
  license, the upstream and Scoutro source links and the disclaimer above.
  The "Help" menu keeps the links to the YaCy project, the YaCy community
  forum, the YaCy repository and YaCy sponsoring.
- **In the repository:** `COPYRIGHT`, `gpl.txt`, `lgpl21.txt`, `LICENSES/`,
  `NOTICE`, `AUTHORS` and all file headers stay as they are. This document
  lists the upstream origin and the differences.
- **In the container image:** OCI labels name the Scoutro source revision and
  the license `GPL-2.0-or-later`. The image contains the full license files.
