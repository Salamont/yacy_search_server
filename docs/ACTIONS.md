# Scoutro actions

Scoutro offers a small, stable set of **actions** so that agents (for example
OpenCode on Olares) and scripts can operate Scoutro without browser
automation. Every action is one call of the Scoutro API (`docs/API.md`), one
`scoutroctl` command and, later, one MCP tool.

The machine-readable catalog is served at `/scoutro/api/actions.json` (public);
the full OpenAPI 3.1 description is at `/scoutro/api/openapi.json`. Both are
generated from one set of definitions by
`tools/scoutro/generate_api_description.py`.

## Analysis: YaCy mechanisms behind each action

Checked against the YaCy 1.942 sources (commit `b50b76b`) and a running
instance.

| Scoutro action | Existing YaCy mechanism | YaCy endpoint used internally | YaCy auth | Transaction token | YaCy answer format | Effort | Risk |
|---|---|---|---|---|---|---|---|
| `search` | Search servlet `yacysearch` | `GET yacysearch.json` (query, maximumRecords, startRecord, resource, lr) | public (if `publicSearchpage=true`); called as admin | no | JSON (OpenSearch-like, HTML in snippets) | low | low: long-standing public API |
| `crawl.start` | `Crawler_p` (the servlet behind all crawl start pages; records an Automation entry) | `POST Crawler_p.json` (crawlingstart, crawlingURL, crawlingDepth, range, crawlingDomMaxPages, …) | admin | **no** (Crawler_p does not check tokens) | JSON `{success, comment}`; new crawl found by diffing the profile list | medium | medium: many historic form parameters, translated in one place |
| `crawl.list` | `CrawlProfileEditor_p` (active + terminated profiles) and `api/status_p` (per-crawl counters) | `GET CrawlProfileEditor_p.xml`, `GET api/status_p.xml` | admin | no | XML | low | low |
| `crawl.status` | same as `crawl.list`, filtered by profile handle | same | admin | no | XML | low | low |
| `crawl.stop` | `Crawler_p` `terminate` (same as the stop button) | `POST Crawler_p.json` (terminate, handle) | admin | no | JSON | low | low: YaCy removes the profile, so the crawl disappears afterwards |
| `crawl.pause` / `crawl.resume` | only for the **whole** local crawler queue (`Crawler_p` pause/continue=localcrawler), not per crawl | – | – | – | – | – | not offered in v1 (not per crawl) |
| `index.status` | `api/status_p` (dbsize, queues, postprocessing) | `GET api/status_p.xml` | admin | no | XML | low | low |
| `index.lookup` | embedded Solr (`/solr/select`) | `GET solr/select?q=sku:"…"` / `host_s:"…"` | admin (public if `publicSearchpage`) | no | JSON (Solr) | low | low: stable Solr fields `sku`, `host_s` |
| `index.evidence` | embedded Solr (`/solr/select`) | `GET solr/select?q=host_s:"…" OR host_s:"www.…"&fq=httpstatus_i:200 [AND collection_sxt:"…"]&fl=sku,title,text_t` | admin | no | JSON (Solr) | low | low: stable Solr fields `sku`, `title`, `text_t`, `host_s`, `httpstatus_i`, `collection_sxt` |
| `system.status` | `api/status_p`, `api/version` | `GET api/status_p.xml`, `GET api/version.xml` | admin / public | no | XML | low | low |
| `health` | `api/version` | `GET api/version.xml` | public | no | XML | low | low |
| `config.get` / `config.set` | YaCy configuration (`Switchboard.getConfig` / `setConfig`, as used by `ConfigPortal_p`) | in-process, **allowlist only** | admin | not needed (JSON API has its own CSRF protection) | – | low | low: two harmless settings |
| `ui.routes` / `ui.route` | static page map of the web interface | none | public | no | – | low | low |

How Scoutro reaches these endpoints: over the loopback interface with the
administrator user and the stored password hash as HTTP Basic credentials.
YaCy accepts this only from localhost. It is exactly the mechanism of
`bin/apicall.sh` and of YaCy's own scheduled API calls
(`WorkTables.execAPICalls`). No crawler, index or search logic is
re-implemented.

Other findings:

- YaCy already contains a small MCP server (`MCPSearchServlet` at `/tools`,
  JSON-RPC, public, search only). Scoutro does not change it. A Scoutro MCP
  server will build on the Scoutro API instead (see below).
- `Crawler_p` removes the start URL from the index when a crawl starts, so
  that it is loaded again. It also drops queued URLs of the same host from
  other crawls. This is upstream behaviour and the same as in the web
  interface. The API never sets `deleteold`, so other documents of the site
  are kept.
- YaCy enforces transaction tokens (CSRF protection) for configuration pages
  such as `ConfigBasic`, `ConfigPortal_p`, `ConfigAccounts_p` and
  `IndexDeletion_p`, but not for `Crawler_p`.

## Action catalog (v1)

| Action | HTTP | Mutating | Auth | `scoutroctl` | Future MCP tool |
|---|---|---|---|---|---|
| `health` | `GET /scoutro/api/v1/health` | no | public | `health` | `scoutro_health` |
| `system.status` | `GET /scoutro/api/v1/system` | no | admin | `system` | `scoutro_system_status` |
| `search` | `GET /scoutro/api/v1/search?q=…` | no | admin | `search QUERY` | `scoutro_search` |
| `index.status` | `GET /scoutro/api/v1/index` | no | admin | `index status` | `scoutro_index_status` |
| `index.lookup` | `GET /scoutro/api/v1/index/lookup?url=…\|host=…` | no | admin | `index lookup --url/--host` | `scoutro_index_lookup` |
| `index.evidence` | `GET /scoutro/api/v1/index/evidence?domain=…` | no | admin | `index evidence DOMAIN` | `scoutro_index_evidence` |
| `crawl.list` | `GET /scoutro/api/v1/crawls` | no | admin | `crawl list` | `scoutro_crawl_list` |
| `crawl.start` | `POST /scoutro/api/v1/crawls` | **yes** | admin | `crawl start URL` | `scoutro_crawl_start` |
| `crawl.status` | `GET /scoutro/api/v1/crawls/{id}` | no | admin | `crawl status ID` | `scoutro_crawl_status` |
| `crawl.stop` | `POST /scoutro/api/v1/crawls/{id}/stop` | **yes** | admin | `crawl stop ID` | `scoutro_crawl_stop` |
| `config.get` | `GET /scoutro/api/v1/config` | no | admin | `config get` | `scoutro_config_get` |
| `config.set` | `PATCH /scoutro/api/v1/config` | **yes** | admin | `config set KEY VALUE` | `scoutro_config_set` |
| `ui.routes` | `GET /scoutro/api/v1/ui/routes` | no | public | `ui routes` | `scoutro_ui_routes` |
| `ui.route` | `GET /scoutro/api/v1/ui/routes/{name}` | no | public | `ui route NAME` | `scoutro_ui_route` |

Each entry in `actions.json` contains `name`, `description`, `mutating`,
`auth`, `http` (method, path, success status), `parameters` (JSON Schema per
parameter with `in`, `required`, `default`, `enum`, limits), `returns` (schema
name, defined under `schemas`), `errors` (possible HTTP status codes), `cli`
and `mcpTool`.

## Not offered in v1 (on purpose)

Deleting the index or DATA, factory reset, account changes, arbitrary
configuration, shutdown/restart, file changes in the container, and per-crawl
pause/resume. `config.set` only accepts the allowlist
(`search.greeting`, `search.itemsPerPage`).

## Example tasks for an agent

| Task | Action(s) |
|---|---|
| "Start a crawl of https://example.com with depth 2." | `crawl.start {"url": "https://example.com", "depth": 2}` |
| "How many pages have been indexed?" | `index.status` → `documents` (per site: `index.lookup?host=example.com`) |
| "Stop the crawl of example.com." | `crawl.list` → pick the running crawl with `name` = `example.com` → `crawl.stop {id}` |
| "Search Scoutro for SAP Analytics." | `search?q=SAP Analytics` |
| "Which crawls are running right now?" | `crawl.list` → `state` = `running` |
| "Which URL is the Accounts page?" | `ui.route config.accounts` → `path` |

## Later: MCP server

The action catalog is designed so that an MCP server can be a thin wrapper:
one tool per action (`mcpTool`), the tool input schema is the `parameters`
object of the action, the tool result is the JSON answer, and errors are the
`error` objects. The MCP server should call the Scoutro API over HTTP with its
own credentials. It must not access YaCy directly, so that the API stays the
only place where YaCy is translated.
