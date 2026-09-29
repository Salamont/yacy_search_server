# Scoutro API (v1)

A stable, machine-readable action layer for agents and tools (e.g. OpenCode
on Olares), scripts and the `scoutroctl` CLI. Agents do not have to use HTML
forms, historic YaCy form parameters or browser automation.

- Description for machines: `GET /scoutro/api/openapi.json` (OpenAPI 3.1) and
  `GET /scoutro/api/actions.json` (action catalog, see `docs/ACTIONS.md`).
- Base path: `/scoutro/api/v1` on the normal Scoutro web port (8090).

## Architecture

```text
 agent / OpenCode / scoutroctl / (later) MCP server
        │  HTTP + JSON, HTTP Digest (Scoutro/YaCy administrator)
        ▼
 /scoutro/api/*  ScoutroApiServlet          (source/net/yacy/scoutro/api)
        │  validation, stable request/response model, error format
        │  loopback HTTP, admin user + password hash (localhost only,
        │  same mechanism as bin/apicall.sh and YaCy's own automation)
        ▼
 existing YaCy endpoints: yacysearch.json, Crawler_p.json,
 CrawlProfileEditor_p.xml, api/status_p.xml, api/version.xml, solr/select
        ▼
 YaCy crawler, index and search (unchanged)
```

- The servlet is registered through YaCy's own extension point,
  `defaults/web.xml`, next to the existing Solr, RAG and MCP servlets. No
  existing YaCy Java file is modified. Scoutro adds new classes in the
  package `net.yacy.scoutro.api` and a marked hunk in `defaults/web.xml`.
- The API translates the stable Scoutro model into the historic YaCy
  parameters in one place (`ScoutroActions`). When YaCy changes, only this
  translation has to follow; agents keep working.
- Reads and actions use YaCy's HTTP endpoints, not YaCy's Java internals, so
  upstream refactorings of internal classes do not break the adapter. The only
  in-process access is the configuration allowlist.
- JSON output only, `Cache-Control: no-store`, request bodies up to 16 KiB.

## Versioning and stability

- `/v1` is stable: fields, codes and semantics are kept. New optional fields
  and new actions may be added. Clients must ignore unknown fields.
- Breaking changes get a new prefix (`/v2`); `/v1` stays available for a
  transition period.
- `apiVersion` in `health` reports the version.

## Authentication

| Endpoints | Access |
|---|---|
| `GET /v1/health`, `GET /v1/ui/routes[/{name}]`, `GET /openapi.json`, `GET /actions.json` | public (no personal or index data) |
| everything else in `/v1`, including search | Scoutro/YaCy administrator account |

- Enforced by the servlet container (security constraints in
  `defaults/web.xml`, role `adminRight`, HTTP **Digest**, the same account as
  the admin pages). The servlet checks the role again for every protected
  route (defence in depth).
- Unauthenticated calls get `401` with a Digest challenge. The body of this
  answer is the server's HTML error page, not JSON; clients should rely on the
  status code.
- Credentials are never part of URLs. `scoutroctl` reads the password only from
  `SCOUTRO_PASSWORD` or `SCOUTRO_PASSWORD_FILE`, never from arguments. The
  API does not log credentials, and the internal loopback credential is built
  per request in memory.
- Cross-site protection for mutating calls (the browser of a logged-in admin
  could otherwise be abused): the body must be `application/json` (`415`
  otherwise), and a present `Origin` header must match the host (`403`
  otherwise). The API never answers CORS preflights. YaCy's transaction
  tokens are therefore not needed on this API.
- Read-only and mutating actions are marked in `actions.json` (`mutating`) and
  in `openapi.json` (`x-scoutro-mutating`). All mutating actions require the
  admin account.

### Proposal: separate agent key (not implemented)

For agents on Olares, a dedicated credential is better than sharing the admin
password:

- A random API key, stored only as a hash in `DATA/SETTINGS/yacy.conf`
  (`scoutro.api.keyHash`), created and rotated on an admin page or by
  `scoutroctl` with the admin account.
- Sent as `Authorization: Bearer <key>`, checked by the servlet with a
  constant-time comparison.
- Optional scopes (`read`, `crawl`) so that an agent can search and crawl but
  not change settings.
- Deployed to OpenCode as an Olares secret.

This is deliberately left for later: v1 reuses the existing admin
authentication and adds no new secret store.

## Endpoints

All examples use `curl --digest -u admin` (curl asks for the password).

### Health (public)

```sh
curl http://scoutro:8090/scoutro/api/v1/health
```
```json
{"status":"ok","service":"scoutro","apiVersion":"1",
 "version":{"scoutro":"1.942-scoutro.1","yacy":"1.942","build":"yacy_v1.942_…"}}
```
`503` when YaCy does not answer. Suitable as a container health probe.

### System

`GET /v1/system`: versions, peer name and network, memory, disk, load,
crawler queues, pages per minute.

### Search

`GET /v1/search?q=olares&limit=10&offset=0&source=local&lang=de`

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `q` | string, 1–200 | required | YaCy query syntax, e.g. `site:example.com olares` |
| `limit` | integer 1–100 | 10 | |
| `offset` | integer 0–10000 | 0 | |
| `source` | `local` \| `network` | `local` | `network` also asks other YaCy peers |
| `lang` | two-letter code | – | language filter |

```json
{"query":"olares","source":"local","offset":0,"limit":10,"total":1,
 "results":[{"title":"Olares: persönliche Cloud zu Hause","url":"https://…/olares.html",
   "snippet":"Olares ist ein Betriebssystem für die persönliche Cloud. …","host":"…",
   "date":"2026-09-29T21:23:26Z","sizeBytes":1541,"id":"lEvz8tiNtIAc"}]}
```
Snippets are plain text; HTML tags are removed and entities decoded.

### Crawls

Start:

```sh
curl --digest -u admin -X POST -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com","depth":2,"scope":"domain","maxPages":1000}' \
  http://scoutro:8090/scoutro/api/v1/crawls
```

| Field | Type | Default | Notes |
|---|---|---|---|
| `url` | http/https URL, ≤ 2048 | required | no `file:`, `ftp:`, `smb:`, no credentials in the URL |
| `depth` | integer 0–10 | 2 | link depth |
| `scope` | `domain` \| `subpath` \| `wide` | `domain` | stay on the host / below the start path / follow other hosts |
| `maxPages` | integer 1–1000000 | unlimited | pages per domain |
| `collection` | `[A-Za-z0-9_-]{1,64}` | `user` | YaCy collection name |

Unknown fields are rejected (`400`), so typos in agent calls do not go
unnoticed. Answer `201` with a `Location` header:

```json
{"id":"pLra5iB67fg3","name":"example.com","state":"running","depth":2,"maxPages":1000,
 "pagesLoaded":null,"collections":["user"],"startUrl":"https://example.com","scope":"domain",
 "links":{"self":"/scoutro/api/v1/crawls/pLra5iB67fg3","stop":"/scoutro/api/v1/crawls/pLra5iB67fg3/stop"}}
```

The crawl is started with the defaults of the YaCy site crawl
(`CrawlStartSite.html`), except that the API never sets `deleteold`. As in the
web interface, YaCy removes the **start URL** from the index and loads it
again, and drops queued URLs of the same host from other crawls. If YaCy
refuses the crawl (for example a private address on a peer in the `freeworld`
network), the answer is `422 crawl_rejected` with YaCy's reason.

- `GET /v1/crawls`: all crawls (`running`, `paused`, `terminated`).
- `GET /v1/crawls/{id}`: one crawl, `404 crawl_not_found` if unknown.
- `POST /v1/crawls/{id}/stop` with body `{}`: stops a running or paused crawl
  (`409 crawl_not_running` for a terminated one). YaCy removes the crawl
  profile, so the crawl is no longer listed afterwards (`404`).

`state` = `paused` means that YaCy's local crawler queue is paused as a whole
(YaCy has no per-crawl pause).

### Index

- `GET /v1/index`: `documents` (full-text index), `webgraphEdges`,
  `citations`, `rwiWords`, crawler queues, post-processing.
- `GET /v1/index/lookup?url=https://example.com/page`: `indexed`, plus
  `document` (title, host, lastModified, collections) when indexed.
- `GET /v1/index/lookup?host=example.com`: number of documents of the host.

### Configuration (allowlist)

- `GET /v1/config`: the allowlisted settings with type and description.
- `PATCH /v1/config` with e.g. `{"search.itemsPerPage": 20}`: all or nothing.
  Any key outside the allowlist gets `403 setting_not_allowed`.

| Setting | YaCy key | Type |
|---|---|---|
| `search.greeting` | `promoteSearchPageGreeting` | string ≤ 120 |
| `search.itemsPerPage` | `search.items` | integer 1–100 |

### UI routes (public)

- `GET /v1/ui/routes`: stable names, titles, groups and paths of the web
  interface pages (e.g. `config.accounts` → `/ConfigAccounts_p.html`,
  `crawler.monitor` → `/Crawler_p.html`, `config.network` →
  `/ConfigNetwork_p.html`).
- `GET /v1/ui/routes/{name}`: one route, `404 route_not_found` otherwise.

## Errors

```json
{"error":{"code":"invalid_request","message":"Field 'depth' must be between 0 and 10.","details":{"field":"depth"}}}
```

| Status | Codes |
|---|---|
| 400 | `invalid_request` (with `details.field`), `invalid_json` |
| 401 | authentication required (HTML body from the container) |
| 403 | `cross_origin_forbidden`, `setting_not_allowed` |
| 404 | `not_found`, `crawl_not_found`, `route_not_found` |
| 405 | `method_not_allowed` |
| 409 | `crawl_not_running` |
| 413 | `payload_too_large` |
| 415 | `unsupported_media_type` |
| 422 | `crawl_rejected` |
| 500 | `internal_error` |
| 502 | `upstream_error`, `upstream_unreachable`, `upstream_forbidden` |
| 503 | `unavailable` |

## scoutroctl

`tools/scoutro/scoutroctl`, a single Python 3 file (standard library only). It
is a thin client: every command is one API call.

```sh
export SCOUTRO_URL=http://scoutro:8090
export SCOUTRO_PASSWORD_FILE=/run/secrets/scoutro-admin   # or SCOUTRO_PASSWORD

scoutroctl health
scoutroctl search "Olares" --limit 5
scoutroctl crawl start https://example.com --depth 3 --scope domain --max-pages 1000
scoutroctl crawl list
scoutroctl crawl status <id>
scoutroctl crawl stop <id>
scoutroctl index status
scoutroctl index lookup --host example.com
scoutroctl config get
scoutroctl config set search.itemsPerPage 20
scoutroctl ui routes
scoutroctl ui route config.accounts
scoutroctl actions        # print actions.json
```

Output is JSON (`--compact` for one line). Exit codes: `0` success, `1` API
error (the error object is printed), `2` usage error, `3` Scoutro not
reachable.

## For OpenCode and other agents

1. Read `/scoutro/api/actions.json` once. It lists every action with
   parameters, types, limits, whether it is mutating, and the errors.
2. Call the API directly, or run `scoutroctl` in a shell tool.
3. Handle errors by `error.code`; for `invalid_request`, `details.field` names
   the parameter to fix.

## Olares notes (for `scoutro-olares`, not done yet)

The current Olares app (`Salamont/YaCy-Search-Community`) is unchanged. When
Scoutro is packaged as `scoutro-olares`:

- Keep the **private** Olares entrance for the human web UI; do not create a
  public entrance for the API.
- Make the API reachable **inside** Olares for agents: through the app's
  cluster-internal Service (port 8090, path `/scoutro/api`) or through the
  mechanism Olares provides for app-to-app access (to be verified against the
  Olares documentation). Do not expose it publicly.
- Give the agent app (OpenCode) the credential as an Olares secret
  (environment variable or mounted file for `SCOUTRO_PASSWORD_FILE`). Later,
  use the dedicated agent key proposed above.
- Use `/scoutro/api/v1/health` for readiness and liveness probes.
- The Olares entrance proxy may reach YaCy from `127.0.0.1` (sidecar). This
  does not weaken the API: the API constraints always require Digest
  authentication, independent of the client address, and
  `adminAccountForLocalhost` stays `false`.

## Tests

`test/scoutro-api/test_api.py` (Python standard library; validates
`openapi.json` against the OpenAPI 3.1 schema when `openapi-spec-validator` is
installed):

```sh
SCOUTRO_URL=http://127.0.0.1:8090 SCOUTRO_ADMIN_PASSWORD=… \
SCOUTRO_TEST_CRAWL_URL=http://<disposable local test site>/ \
python3 test/scoutro-api/test_api.py -v
```

Covered: health, UI routes, descriptions (OpenAPI structure and schema,
consistency with `actions.json`), authentication for every protected and
mutating call, wrong password, cross-site protection, method not allowed,
system, index, search and its validation, lookup, configuration allowlist,
crawl list, invalid URLs and depths, unknown fields, invalid JSON, unknown and
invalid crawl ids, the full crawl lifecycle against a local test site,
optionally a crawl rejected by YaCy (`SCOUTRO_TEST_REJECTED_URL`), and the
CLI. Crawl tests must only use disposable local test sites.
