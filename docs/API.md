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
  `defaults/web.xml`, next to the existing Solr, RAG and MCP servlets.
  Scoutro adds new classes in the packages `net.yacy.scoutro.api` and
  `net.yacy.scoutro.agents`, marked hunks in `defaults/web.xml`, and one
  marked exemption for the agent path in `net.yacy.http.AdminSecurity`.
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

## Agent access (`/scoutro/api/agent/v1`)

Agents get their own identity instead of the administrator password. The
administrator creates them in **Administration → Agents & Access**
(`ScoutroAgents_p.html`, wizard `ScoutroAgentWizard_p.html`, see
`help/ScoutroAgents_p.md`). Every agent has:

- a **token** `sca_<publicId>.<secret>` (shown once; Scoutro stores only the
  public id and `HMAC-SHA256(pepper, secret)`, compared in constant time;
  lifetime 30–365 days; rotation with an optional grace period of up to one
  hour; single tokens can be revoked);
- a fixed **action list** (presets `research` and `research_crawl` are
  resolved into explicit lists when saved, so actions added later never
  extend an existing agent);
- a **data scope**: a list of collections, or explicitly the complete local
  index;
- **limits**: crawl domains (allowlist, subdomains included), crawl depth
  (≤ 3), pages per crawl (≤ 1000), parallel crawls (≤ 5), requests per minute,
  task time and model use (research worker);
- a **status**: `active`, `paused` (all requests refused until resumed) or
  `revoked` (final).

### Authentication

| Path | Credential |
|---|---|
| `/scoutro/api/v1/*` (unchanged) | administrator account, HTTP Digest; agent tokens are **not** accepted (container constraint in `defaults/web.xml`) |
| `/scoutro/api/agent/v1/*` | `Authorization: Bearer sca_...`; Digest/Basic are refused (`bearer_required`), a token in the URL is refused (`token_in_url`) |

The agent path has no container constraint; `ScoutroApiServlet` authenticates
it and never grants the administrator role. `AdminSecurity` keeps the path
free of the admin challenge also when "admin for all pages" is on (never for
paths with `_p.` or `..`). Answers to unauthenticated or invalid requests are
JSON with `WWW-Authenticate: Bearer realm="scoutro-agent"`. After 30 failed
authentications per minute a client gets `429 too_many_failures`.

### Authorization

One decision per request, deny by default, in this order (first failure wins,
stable `error.code`):

1. token valid (`invalid_token`, `token_expired`, `token_revoked` → 401)
2. agent active (`agent_paused`, `agent_revoked` → 403)
3. action known and granted (`unknown_action`, `action_not_granted`,
   `action_not_allowed_for_kind` → 403)
4. named collection inside the scope (`collection_not_in_scope` → 403)
5. requests per minute (`rate_limited` → 429)
6. action limits in the scoped action (`limit_exceeded:<limit>` → 403/429,
   `host_busy`/`host_indexed_elsewhere` → 409)

The grant is read on every request, so changes apply to the next call. Every
decision is written to the activity log (`DATA/SETTINGS/scoutro-agent-audit.jsonl`,
newest 20 000 entries) with agent, public token id, action, decision, reason,
HTTP status and client — never bodies, queries, page text or secrets.

### Actions and data scope

| Grant | Agent path | Scope enforcement |
|---|---|---|
| `search` | `GET /agent/v1/search?q=…[&collection=…]` | Always local. Scoutro sends YaCy's request parameter `collection`, which overrides inline query syntax; `collection:` in `q` is refused (`query_modifier_not_allowed`). With several collections each one is searched and the results are merged (`totalIsApproximate`). |
| `search.network` | `GET /agent/v1/search?source=network` | Separate grant, never in a preset; not limited to collections. |
| `index.evidence` | `GET /agent/v1/index/evidence?domain=…` | Solr filter on the scope (or the named collection). |
| `index.lookup` | `GET /agent/v1/index/lookup?url=…\|host=…` | Solr filter on the scope; documents elsewhere are "not indexed"; only scope collections are reported. |
| `index.status` | `GET /agent/v1/index` | Documents per scope collection; no global queues or counters. |
| `index.status.global` | `GET /agent/v1/index?global=true` | Separate grant, global values. |
| `crawl.start` | `POST /agent/v1/crawls` | `collection` required and in scope; host on the domain allowlist; `scope` `domain`/`subpath` only (no wide crawls); `depth`/`maxPages` within the limits (`maxPages` defaults to the limit); parallel crawl limit; `409 host_busy` while another crawl runs on the host (YaCy's crawl start drops queued URLs of that host from other crawls); `409 host_indexed_elsewhere` when the host already has documents outside the scope (a crawl would re-index them into the agent's collection). Optional `Idempotency-Key` header: the same key returns the existing crawl (200); see "Crawl starts and crashes" below. Crawls stay text-only (`indexText=on`, `indexMedia=off`, no HTCache, `cachePolicy=nocache`, `deleteold=off`). |
| `crawl.list`, `crawl.status`, `crawl.stop` | `GET /agent/v1/crawls`, `GET …/{id}`, `POST …/{id}/stop` | Only crawls this agent started; other ids answer `404 crawl_not_found`. A crawl whose profile YaCy has removed is reported as `removed`. |
| `system.status`, `config.get`, `config.set` | `GET /agent/v1/system`, `GET`/`PATCH /agent/v1/config` | Not scoped; individual grants with a warning, external agents only. |

Always allowed for a valid token: `GET /agent/v1/capabilities` (the actions,
collections and limits of this agent, plus a fingerprint; records the
handshake behind the "connected" status) and `POST /agent/v1/heartbeat`
(runtime state of the research worker; fields `version`, `status`,
`lastError`, `clustroReachable`, `clustroCheckedAt`, `modelConfigured`,
`lastPollAt`, `activeRuns`).

```sh
export SCOUTRO_TOKEN=sca_...            # from the wizard, shown once
curl -H "Authorization: Bearer $SCOUTRO_TOKEN" http://scoutro:8090/scoutro/api/agent/v1/capabilities
curl -H "Authorization: Bearer $SCOUTRO_TOKEN" 'http://scoutro:8090/scoutro/api/agent/v1/search?q=pflegeheim'
tools/scoutro/scoutroctl capabilities   # scoutroctl uses the agent path when SCOUTRO_TOKEN is set
```

### Crawl starts and crashes

A crawl start changes YaCy and the agent store; there is no transaction
spanning both. Scoutro therefore records every start attempt **before** it
asks YaCy (state `starting`, with the Idempotency-Key, the parameters and a
random 32-hex start marker) and stores the crawl id afterwards (`started`).
The marker travels in the crawl profile as the URL must-not-match filter
`.*/scoutro-start-<marker>/.*`, which excludes no real page; it ties a
profile to its attempt.

| Situation | Result |
|---|---|
| The attempt cannot be recorded | `503 agent_store_unavailable`; YaCy is not asked, nothing started |
| Any failure answer: Scoutro looks for the marker first. YaCy's `Crawler_p` reports "Crawling of … failed" also **after** it has activated the profile (e.g. when start URLs cannot be stacked); found on a real instance | a profile with the marker exists: the crawl is assigned (`started`), the error answer names it in `details.id` |
| YaCy refuses the start (400/422) and no profile carries the marker | the attempt becomes `rejected`; the same key may start again |
| YaCy's answer is lost (e.g. 502) and no profile carries the marker | `502 crawl_start_unconfirmed`; the attempt stays `starting` |
| The crawl id cannot be stored after the start | `500 agent_store_unavailable` with the crawl id |
| Crash after the start, before the id is stored | the attempt stays `starting` |
| A `starting` attempt whose marker is found in a crawl profile (on the next crawl request, status, list or replay of the key, also after a restart and after the crawl has terminated) | assigned (`started`); a replay of the key answers `200` with `idempotentReplay` — no second crawl |
| A `starting` attempt without a profile carrying its marker (it never ran, or it ran and YaCy has removed its profile) | `409 crawl_start_unconfirmed` for every replay of the key; listed with state `unconfirmed`; counted as running for the parallel limit; never started again automatically |

Recovery of an unconfirmed start: the administrator checks the Crawler
monitor and the index, then uses "Mark as not started" in Agents & Access
(the attempt becomes `abandoned` and its key may start a new crawl). Limits:
the marker is only as durable as YaCy's crawl profile; a start lost together
with its profile cannot be told apart from one that never ran, which is why
it needs this human decision.

### Storage

`DATA/SETTINGS/scoutro-agents.json` (agents, token hashes, crawl ownership,
connection state), `DATA/SETTINGS/scoutro-agent-pepper` (back it up with the
data; without it every token is invalid), `DATA/SETTINGS/scoutro-agent-audit.jsonl`
and, for research workers only, `DATA/SETTINGS/agent-runtime/<agent>.secret`
(its own Scoutro token and its Clustro settings, because a stored hash cannot
authenticate outgoing calls). All files are 0600 (directory 0700), written
atomically; nothing is kept in `yacy.conf`, which the administrator pages can
display. Existing index data and the discovery state are not touched.

### Research worker for Clustro

An agent of kind `research_worker` is the Scoutro research agent for Clustro:
`tools/scoutro/agent/scoutro-agent-bridge` pulls the runs of its Clustro
connection over Clustro's MCP endpoint, answers them through this agent path
with its own token (so the same grant, scope and limits apply) and reports
results with sources (`complete_run`/`fail_run`). It needs no inbound route.
Task and result formats, set-up and guarantees: `tools/scoutro/agent/README.md`.

### Not part of the agent path

YaCy's own AI functions — the native MCP server `/tools`, `/v1/chat/completions`
(RAG), the AI Lab tools such as `http_json` and `webfetch` — search the whole
index or reach arbitrary hosts and carry no agent identity. They are not
offered to agents. They are not protected by agent tokens either: with the
defaults, the YaCy port answers `/solr/select`, the native search and `/tools`
without any login. Agents must therefore never reach the YaCy port. The
server operating path (YaCy on `127.0.0.1` only, an nginx agent listener that
forwards nothing but `/scoutro/api/agent/v1/`, and a check script run from the
agent's position) and the trust boundary of the research worker are described
in `docs/SERVER_AGENT_ACCESS.md`.

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
  -d '{"url":"https://example.com","depth":2,"scope":"domain","maxPages":1000,"collection":"research"}' \
  http://scoutro:8090/scoutro/api/v1/crawls
```

| Field | Type | Default | Notes |
|---|---|---|---|
| `url` | http/https URL, ≤ 2048 | required | no `file:`, `ftp:`, `smb:`, no credentials in the URL |
| `depth` | integer 0–10 | 2 | link depth |
| `scope` | `domain` \| `subpath` \| `wide` | `domain` | stay on the host / below the start path / follow other hosts |
| `maxPages` | integer 1–1000000 | unlimited | pages per domain |
| `collection` | `[A-Za-z0-9_-]{1,64}` | required | Explicit target; no fallback; must be in the collection catalog (below), otherwise `400 collection_unknown` before anything is dispatched |

Unknown fields are rejected (`400`), so typos in agent calls do not go
unnoticed. Answer `201` with a `Location` header:

```json
{"id":"pLra5iB67fg3","name":"example.com","state":"running","depth":2,"maxPages":1000,
 "pagesLoaded":null,"collections":["research"],"startUrl":"https://example.com","scope":"domain",
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

### Collections

Collections are chosen from a list everywhere in Scoutro and never typed. The
list is the **collection catalog** (`CollectionCatalog`): the collections of the
index (facet `collection_sxt`, read at most every 10 seconds), the collections
created with `POST /v1/collections` (`DATA/SCOUTRO/collections.json`, schema
`scoutro.collections.v1`) and the collections of the Discovery profiles. A
collection beginning with `robot_` is internal: listed with `internal:true`,
never offered as a choice, never a crawl target. The chat, the knowledge graph
filters, crawl starts and agent grants all take their choices from it.

- `GET /v1/collections` (administrator): `collections`, one entry per
  collection, alphabetically regardless of case: `id`, `name` (display
  name), `description`, `documents`, `internal`, `selectable`, `chat`,
  `knowledgeGraph`, `crawlTarget`, `sources` (`index`, `created`, `profile`),
  `createdAt`, and `graph` with `followed`, `vocabulary`, `vocabularySource`,
  `jobs`, `llm` and `state` of the knowledge graph; then `allowNew:false` (no
  free names), `canCreate` and `limit` (500).
- `POST /v1/collections` (administrator, same-origin JSON, at most 4 KiB):

  ```sh
  curl --digest -u admin -X POST -H 'Content-Type: application/json' \
    -d '{"id":"mein-neues-portal","name":"Mein neues Portal","description":"optional"}' \
    http://scoutro:8090/scoutro/api/v1/collections
  ```

  `201 {"collection":{...entry...},"created":true}`. `id` (derived from the
  name when it is left out): 2 to 64 lower-case letters, digits and single hyphens, starting and ending with a letter or digit
  (`400 collection_id_invalid`); not reserved (`robot_*`, `all`, `none`,
  `default`, `user`, `any`, `new`: `400 collection_reserved`); not in the
  catalog in any case (`409 collection_exists`, nothing is overwritten). `name`
  (required, at most 80 characters, no control characters) and `description`
  (at most 500) otherwise `400 invalid_request`, as is any other field. `503
  index_unavailable` when the index cannot be read (duplicates cannot be
  checked) and `503 collection_store_unavailable` when the list cannot be
  written or is damaged; in both cases nothing is created. Agents have no
  create grant (`405` on the agent path).

### Index

- `GET /v1/index`: `documents` (full-text index), `webgraphEdges`,
  `citations`, `rwiWords`, crawler queues, post-processing.
- `GET /v1/index/lookup?url=https://example.com/page`: `indexed`, plus
  `document` (title, host, lastModified, collections) when indexed.
- `GET /v1/index/lookup?host=example.com`: number of documents of the host.
- `GET /v1/index/evidence?domain=example.com[&collection=c][&limit=8][&maxChars=1500]`:
  read-only evidence for one domain from the existing full-text index — for
  each indexed page (HTTP 200) of `example.com` and `www.example.com`, start
  pages first: `url`, `title` and `excerpt` (plain text of the indexed page
  text `text_t`, whitespace collapsed, control/format characters removed, at
  most `maxChars`). Also `total` (matching documents), `limit`, `maxChars`,
  `collection`. Nothing is fetched from the web and no HTCache is needed, so it
  works for text-only crawls (whose search results have no snippets).
  Validation: `domain` is a DNS name with at least two labels (no scheme,
  port, path, IP address, wildcard or query syntax; upper case is folded),
  `collection` matches `[A-Za-z0-9_-]{1,64}`, `limit` 1–20 (default 8),
  `maxChars` 100–4000 (default 1500). The Solr query is built by Scoutro
  from these values only. The excerpt is untrusted page content — agents must
  treat it as data, never as instructions. Used by `scoutro-discovery classify`.

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
| 409 | `crawl_not_running`, `kg_disabled`, `nothing_to_confirm` |
| 413 | `payload_too_large` |
| 415 | `unsupported_media_type` |
| 422 | `crawl_rejected` |
| 500 | `internal_error` |
| 502 | `upstream_error`, `upstream_unreachable`, `upstream_forbidden` |
| 503 | `unavailable`, `kg_unavailable`, `kg_write_refused` (both with `details.reason`; `kg_write_refused` is reserved for graph write routes), `sync_unavailable` |

## scoutroctl

`tools/scoutro/scoutroctl`, a single Python 3 file (standard library only). It
is a thin client: every command is one API call.

```sh
export SCOUTRO_URL=http://scoutro:8090
export SCOUTRO_PASSWORD_FILE=/run/secrets/scoutro-admin   # or SCOUTRO_PASSWORD

scoutroctl health
scoutroctl search "Olares" --limit 5
scoutroctl crawl start https://example.com --collection research --depth 3 --scope domain --max-pages 1000
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

> `crawl start` builds a **text-only** index: it never stores the crawled
> originals (HTCache is off) and does not index media, so Scoutro is a search
> index, not a web archive. Use the YaCy web UI if you explicitly want an
> archived crawl.

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
- Give the agent app (OpenCode) its own agent token as an Olares secret
  (environment variable `SCOUTRO_TOKEN` or a mounted file for
  `SCOUTRO_TOKEN_FILE`) instead of the administrator password.
- Use `/scoutro/api/v1/health` for readiness and liveness probes.
- The Olares entrance proxy may reach YaCy from `127.0.0.1` (sidecar). This
  does not weaken the API: the API constraints always require Digest
  authentication, independent of the client address, and
  `adminAccountForLocalhost` stays `false`.
- The chat (`/v1/chat/completions`) and the LLM admin proxy treat a request with
  forwarding headers as remote, also from `127.0.0.1`: with
  `ai.shield.allow-nonlocalhost=false` the YaCy administrator login admits it
  (see `docs/SCOUTRO_LLM_SECURITY.md`).
- The chat accepts an optional `collection` (`[A-Za-z0-9_-]{1,64}`) that restricts
  RAG to that collection, and streams the sources and the citation check of each
  answer (`scoutro-sources`, `scoutro-citations`; see `docs/SCOUTRO_RAG_QUALITY.md`).
  Local and administrator access may name every collection, an AI Shield guest
  only one released in `ai.shield.guest-collections` (none by default); any other
  name is `403 collection_not_allowed`, without echoing it (package 6.1).

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

Agent access:

- `ant scoutro-agents-test` runs the Java unit tests of the agent store,
  tokens, authorizer, agent path, scoped actions (with a fake YaCy upstream
  that records the exact parameters sent to YaCy), the administration logic,
  the catalog consistency with `AgentActionRegistry`, and `AdminSecurityTest`.
- `ant scoutro-agents-test` also runs the knowledge graph tests
  (`test/java/net/yacy/scoutro/knowledge/**`, `KnowledgeApiTest`): settings,
  IDs, schema constraints, change feed, storage guard (including a checkpoint
  blocked by an open reader and the SQLite page limit), admission under the
  write lock with two concurrent writers, vacuum batches against a blocked
  checkpoint, read leases (late interrupts, expiry between two statements,
  close during an interrupt), where SQLite's temp files land, the integrity
  check aborted by the real watchdog, runtime start/stop, a clean stop on
  SIGTERM in a child JVM without the servlet, and the admin routes through
  the servlet. The package-2a tests run against an embedded Solr core with
  the shipped configuration: the capture processor and real-time get,
  extractors, identity rules and publish, backfill, recrawl and delete during
  processing, crash, restart and reactivation, overflow, delete by query,
  full clear, an aborted reconcile, the mass-deletion brake, storage and gate
  limits, retention, and the JSON-LD capture in YaCy's HTML parser.
- `python3 test/scoutro-api/kg-live-smoke.py` (after `ant compile`) starts a
  disposable peer five times on temporary DATA. It covers disabled, enabled,
  clean restart, hard kill with unclean-start detection and the integrity
  check, and an unloadable SQLite native library while Scoutro keeps running.
  With the graph enabled it pushes pages through YaCy's parser and index path
  (`api/push_p`) and checks the start backfill, the JSON-LD capture and the
  publish, that a page of another collection is not tracked, and the
  reconcile after the clean restart and after the hard kill.
  `JAVA=/path/to/java` selects another JDK.
- `test/scoutro-api/test_agent_api.py` runs end to end against a disposable
  instance: it drives the wizard with the administrator account, takes the
  token from the one-time page and checks `no-store`, credentials, scope
  enforcement, lifecycle (pause, resume, rotation, grant change, revocation)
  and, with `SCOUTRO_TEST_CRAWL_URL` on a DNS name, crawl ownership and
  idempotency.

## Discovery Automation V1

Administrator-only `/scoutro/api/v1/discovery/` endpoints provide dynamic catalog, status, job CRUD/export, run-once and global enable/disable/pause/resume. No agent grants are added. Mutations use JSON and the existing origin guard; edits/delete/global controls require `If-Match` with the numeric store revision. Complete parameters, responses and side effects: [Discovery help](../help/ScoutroDiscovery_p.md), OpenAPI/action catalog. WorkTables calls the separate transaction-protected `ScoutroDiscoveryTick_p.json` responder, not the JSON mutation API. All scheduling/discovery is deterministic; Classification remains separate.

## SEO / Host Analysis (read-only)

Admin Digest: `GET /scoutro/api/v1/seo/hosts`, `/seo/hosts/{host}`,
`/seo/hosts/{host}/pages`, `/seo/pages/{id}`. Equivalent Agent Bearer routes
under `/scoutro/api/agent/v1` require explicit `seo.read`, outside presets.
Collections filter targets before retrieval; foreign assignments and global
host extent are hidden in scoped reads. The UI route is `seo.hostAnalysis`.

Fixed allowlisted queries only: host prefix, optional collection, bounded
limit/offset, page sort/order and reference-readiness filter. No raw Solr
parameters. Partial results/failed queries return 503, unknown targets 404.
Readiness coverage counts finalized reference-field shapes; historical Citation
completeness remains unknown. Non-finalized counters are null, processed zero
is real zero. Incoming counts represent the local observed graph; outgoing
internal `inboundlinkscount_i` is not a backlink count. Per-URL external source
host counts are never summed into a host-wide unique-domain count.

See [page help](../help/ScoutroSEO_p.md) for exact parameters/errors/response
fields and [technical semantics](SCOUTRO_SEO_HOST_ANALYSIS.md) for field origins,
aggregation limits, performance and rollout prerequisites. No crawler,
scheduler, configuration, ranking or DATA migration side effects.

## Crawl report (read-only)

Admin Digest: `GET /scoutro/api/v1/reports/jobs`, `/reports/jobs/{id}`
(`from`, `to` as `YYYY-MM-DD`; default the last 90 days, at most three years),
`/reports/collections/{collection}`, `/reports/collections/{collection}/hosts`
(`filter` = `all`, `stale`, `precheck`, `partial`, `not_indexed`,
`not_reloaded`, `unknown`, `coverage_partial`; `limit` 1–100, default 50;
`offset` 0–10000) and `/reports/hosts/{host}?collection=` (collection
required). Equivalent Agent Bearer routes under `/scoutro/api/agent/v1` require
the explicit grant `report.read`, absent from every preset. A collection
outside the agent's scope is refused with 403 `collection_not_in_scope`; a job
is visible only when all of its collections are in scope (otherwise 404, like a
missing job). The UI route is `report.crawl`.

Sources: the live index for the current page state, `scoutro_domains` for the
current host and crawl status, and the daily rollups for the history (see
[crawl report](SCOUTRO_CRAWL_REPORT.md)). A host without a row returns 200 with
`status: absent`. Unknown parameters return 400 `invalid_request`, other
methods 405, an unavailable table or capture 503 `report_unavailable`. If the
live index fails, a collection report falls back to the newest rollup
snapshot (`index_source: rollup`). No route fetches a URL, starts a crawl or
writes; "Crawl again" in the UI only links to the native crawl form.

Data quality: collection and host reports include `canonical` and
`titles`/`descriptions` (host reports also pages sharing a title or a
description); host reports add first-level `directories` and
`referring_hosts` from YaCy's host link graph. The graph is not
collection-aware, so agents without the complete index get `referring_hosts:
null` with `referring_hosts_scope: complete_index_required`. The optional fields
`canonical_s`, `canonical_equal_sku_b`, `title_exact_signature_l` and
`description_exact_signature_l` are enabled by the administrator in
`IndexSchema_p.html`; while disabled they are listed in `index.unavailable`.

## Knowledge graph

Packages 1 to 5 of the [knowledge graph plan](SCOUTRO_KNOWLEDGE_GRAPH.md):
the embedded store with its storage budget, the synchronisation with the
embedded Solr core (change capture, persistent queue, real-time get, reconcile
and backfill, document states, structured and rule-based extraction, identity
resolution, change feed, retention, bounded JSON-LD capture), the optional
LLM tier, the read routes with the page `ScoutroKnowledge_p.html`, the
export, the change feed, the agent grants `kg.read` and `kg.export`, graph
facts in the RAG chat, budgets with notice, warning, brake and full levels,
backups and restore inside the app's DATA, and the identity rebuild. Nothing
is ever written to Solr.

| Route | Access | Purpose |
|---|---|---|
| `GET /scoutro/api/v1/kg/status` | administrator (Digest) | Status `scoutro.kg.status.v1`; 200 also when disabled or unavailable. `collections`: every collection the graph follows, maps or holds, with `followed`, `vocabulary` (null: none, generic facts only; never guessed from the name), `vocabularySource` (`setting`, `vocabulary_files`, `none`), `vocabularyKnown`, `jobs`, `llm`, `documents` (at most 10 s old) and `state`; `config.jobsCollections` |
| `POST /scoutro/api/v1/kg/control` | administrator (Digest), JSON body, same origin | `{"action":"pause"}`, `"resume"`, `"reconcile"`, `"confirm_reconcile"`, `"llm_retry"`, `"backup"`, `"restore"` (with `"backup": "<file>"`), `"delete_backup"` (with `"backup": "<file>"`, package 6.3), `"rebuild"`, `"rebuild_cancel"`, `"rebuild_confirm"` or `"derive"` (recompute the derived layer now; 409 `derived_unavailable` while it is off) |
| `GET /scoutro/api/v1/kg/prompt` | administrator (Digest) | the knowledge prompt: `activeVersion`, `activeHash`, `source` (`default`, `custom`), `modifiedAt`, `differsFromDefault`, the active `text`, the compiled-in `default`, `limits`, `history` (no texts) |
| `POST /scoutro/api/v1/kg/prompt` | administrator (Digest), JSON body (≤ 64 KiB), same origin | `{"action":"validate","text":…}` (stores nothing), `{"action":"activate","text":…,"expectedRevision":n}`, `{"action":"reset","expectedRevision":n}`; a new prompt changes the prompt hash and the cache key but reads no done document again (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3); 422 `prompt_invalid`, 409 `prompt_revision_conflict` |
| `GET /scoutro/api/v1/kg/backups` | administrator (Digest) | The backup files with their metadata (`scoutro.kg.backup.v1`), newest first |
| `GET /scoutro/api/v1/kg/backups/{file}` | administrator only, never an agent | One backup as a SQLite file (`application/vnd.sqlite3`), to keep it outside the app |
| `GET /scoutro/api/v1/kg/collections` | administrator only, never an agent | Package 6.2: the knowledge graph settings of each collection: every collection of the collection catalog (a new one by itself, never `robot_*`) and every collection the graph's settings or data name, with `active`, `inactive`, `followedBy` (`list`, `all`, `inactive`, `none`), `vocabulary`, `vocabularySetting` (unset `null`, a name, `""` for none), `defaultVocabulary`, `vocabularySource`, `indexDocuments`, `graphDocuments`, `state` (`following`, `waiting`, `inactive`, `not_followed`, `unknown_vocabulary`); `vocabularies` lists the vocabularies a collection can be given. Package 6.3: each row also has `llm` (switched on for the LLM tier), `llmBy` (`list`, `all`, `none`) and `llmActive` (graph on, collection followed and switched on, and a model of the LLM selection); `llm` at the top names `model` (the model of the usage *knowledge*, null without one), `allCollections`, `collections`, `enabled` and `active` |
| `PATCH /scoutro/api/v1/kg/collections/{collection}` | administrator only, never an agent, JSON body, same origin | `{"active": true\|false, "vocabulary": "care"\|""\|null, "llm": true\|false}` (at least one). `llm` (package 6.3) adds the name to or removes it from `scoutro.kg.llm.collections` (an empty list removes the key); it is never set by itself, also not by `active`. With `scoutro.kg.llm.collections=*` switching one collection off answers 409 `llm_all_collections` and writes nothing. The answer adds `llmModel` and `llmWarning` (`no_model`, `collection_not_active`, or null): the setting is stored and waits for a model or for the collection. Only that collection's keys change: `scoutro.kg.collections` (name added or removed, the other names as they are, `*` stays `*`), `scoutro.kg.collections.inactive` (switched off: not followed, also under `*`, its graph data kept) and `scoutro.kg.vocab.<collection>` (`null` removes it: the default). Applied at once by reopening the graph; the start reconcile reads the pages of a collection switched on (`backfill`). Answer: the collection's row, `changed`, `applied`, `backfill`, `kept`, `reextract`, `keys`, `applyError`. 400 `collection_id_invalid`, `collection_reserved` (`robot_*`), `vocabulary_unknown`, `invalid_request`; 404 `collection_unknown`; 409 `operation_running` while a backup, restore or rebuild runs (nothing written) |
| `GET /scoutro/api/v1/kg/entities?q&type&host&quality&industry&category&audience&offset&limit&collection` | administrator (Digest) | Entities, newest first (`scoutro.kg.v1`); `industry` a NACE Rev. 2.1 / WZ 2025 code or prefix (`43`, `43.22`), `category` a service category or group, `audience` a customer type, segment or company size; `type` also `job`. Every item carries `context` for the viewer, batched for the page: `hosts`, `collections`, `places`, `quality`, `sources`, `last_confirmed`, and for a service its `providers` (visible subjects of a visible `offers`, with their hosts, collections and places) and `provider_count`, for a job its employer; `provider_count: 0` when none is visible |
| `GET /scoutro/api/v1/kg/entities/{id}` | administrator | One entity, or `{"redirect": id}` after a merge |
| `GET /scoutro/api/v1/kg/entities/{id}/statements?direction=out\|in&predicate&include=stale&offset&limit&collection` | administrator | Facts and relations |
| `GET /scoutro/api/v1/kg/statements/{id}` and `.../evidence?offset&limit&collection` | administrator | One statement and its evidence (≤ 50 per page) |
| `GET /scoutro/api/v1/kg/hosts/{host}/entities?offset&limit&collection` | administrator | Entities of a host (SEO tab, Index Browser) |
| `GET /scoutro/api/v1/kg/sources/{docId}?offset&limit&collection` | administrator | What the graph holds from one page |
| `GET /scoutro/api/v1/kg/entities/{id}/business?include=hidden_jobs&collection` | administrator | The object view in sections: industry (main, secondary, categories), services, prices with as-of, staleness, validity and conflicts, contacts, relations both ways, jobs (ended ones visible for `jobs.endedVisibleDays`), the audience layers declared/observed/suggested, suggested matches, sources |
| `GET /scoutro/api/v1/kg/entities/{id}/neighborhood?depth&limit&offset&types&weak&derived&suggested&values&prices&include&collection` | administrator | Nodes (entities with hosts, collections, places, quality, sources; industry, audience and with `prices=true` price values) and typed, directed edges with status, confidence, evidence count and, at the centre, `direction` `in`/`out`; depth 1 or 2, paged for "more"; weak (`linked_to`) and suggested edges only on request. A service in the centre shows its providers through the incoming `offers` and at depth 2 their relations, not their other services. There is no route for the whole graph |
| `GET /scoutro/api/v1/kg/services?q&category&offset&limit&collection` | administrator | Services of the same name across providers as read-only groups (`limit` ≤ 50): `services`, `providers`, `without_provider`, the viewer's `collections`, the providers' `places`, `with_price`, `with_current_source`. Nothing is merged; IDs, prices and evidence stay per service |
| `GET /scoutro/api/v1/kg/services/providers?name&category&offset&limit&collection` | administrator | The services of one name (`group` plus one row per service with its own provider, hosts, collections, places, quality, sources and price counts); 404 when no visible service has the name |
| `GET /scoutro/api/v1/kg/services/network?name&category&offset&limit&include=stale&collection` | administrator | Package 6.2: all providers of one name as a network (`aggregated: true`): `center` `service_group:<key>` is a virtual node (`type` `service_group`, `virtual: true`), the name only, never an entity; one node per visible provider with its context; one edge per visible `offers` statement from the provider to the centre, with `service` naming the provider's own service (ID, name, display name, quality, sources, hosts, collections, `prices` `{current, all}`). Nothing is merged or mixed between providers. Paged by provider (`limit` 1–200, default 50, strongest line first, `neighbours`, `truncated`, `next_offset`); `group` as in `services/providers`; outdated offers only with `include=stale`; 404 when no visible service has the name. The network of one object is unchanged |
| `GET /scoutro/api/v1/kg/compare?category&limit&collection` | administrator | One service category across providers: every price as published with conditions, date and sources, never averaged |
| `GET /scoutro/api/v1/kg/derived?kind&entity&offset&limit&collection` | administrator | Derived rows (`linked_to`, `same_operator`, `suggested_customer`, `suggested_partner`), never facts; a row only with both of its collections |
| `GET /scoutro/api/v1/kg/facets?collection` | administrator | The industries, categories and audiences of the visible graph, for the entity filters |
| `GET /scoutro/api/v1/kg/export?cursor&limit&include=evidence&collection` | administrator | Export pages: entities, then statements (`limit` 1–200) |
| `GET /scoutro/api/v1/kg/changes?cursor&limit&expand&collection` | administrator | Change feed with delete notices (`limit` 1–1000, 1–100 with `expand=true`) |
| `GET /scoutro/api/v1/kg/export/download?format=ndjson\|json&include=evidence&collection` | administrator only, never an agent | The whole export as one streamed download |
| `GET /scoutro/api/agent/v1/kg/{entities,statements,hosts,sources}/…`, `…/kg/entities/{id}/business`, `…/neighborhood`, `…/kg/compare`, `…/kg/derived`, `…/kg/facets`, `…/kg/services`, `…/kg/services/providers`, `…/kg/services/network` | agent with `kg.read` | The read routes for the agent's collections; derived rows only with both of their collections. The collection settings (`kg/collections`) are never an agent route |
| `GET /scoutro/api/agent/v1/kg/export`, `…/kg/changes` | agent with `kg.export` | Export pages and change feed for the agent's collections |

**Display names (package 6.1).** Entities, references, nodes, statement
subjects and objects, providers and service rows carry `display_name` and
`display_name_source` next to `name`: `fact` (the stated name), `legal` (a
legal-name alias), `operator` (for the unnamed operator of a site, the one
declared operator of its domain), `domain` (an organisation's name derived
from the host of the viewer's pages, with `display_host`) or `fallback`
(`display_name: null`). Only `fact` is stated by the sources; the others are
presentation, never stored, never used to merge, and `name` stays the stated
name or null. Clients never show the ID (`kge_…`, `kgs_…`) as a name.

- **Switch:** `scoutro.kg.enabled` (default `false`). While false, nothing
  is created on disk, no thread runs and the SQLite native library is not
  loaded; `status` answers `state: disabled`. Settings take effect at the next
  start of Scoutro.
- **States:** `running`, `disabled`, `unavailable` (with `reason`:
  `config_invalid`, `native_library_unavailable`, `schema_unsupported`,
  `storage_error`, `write_refused`, `start_failed`), `stopped`. A failure of the
  graph never stops Scoutro.
- **Status fields:**
  - `storage`: budget, used bytes per file (database, WAL, shared memory,
    visible temp files, SQLite temp files held open after unlinking in the
    graph's `tmp/` (`tmpOpen`) or elsewhere (`tmpOpenElsewhere`), backups),
    where SQLite creates temp files (`tmpDirectory`), pause and resume
    thresholds, disk floors derived from YaCy's `resource.disk.free.min.*`,
    the last verified WAL checkpoint, read leases (`readers`), page
    statistics, `growthAllowed`, `maintenanceAllowed` and the active
    `reasons`;
  - `jsonld`: the JSON-LD capture into the Solr field `ld_json_txt` with its
    own budget (`scoutro.kg.jsonld.maxTotalBytes`): `state` (`off`, `active`,
    `paused`), `reason` (`jsonld_disabled`, `kg_not_running`,
    `jsonld_budget`, `disk_reserve`, ...), `estimatedBytes` (sum of the
    captured bytes the graph tracks plus bytes not yet synchronised) and the
    `capture` counters (`capturedDocs`, `skippedDocs`, `droppedBlocks`,
    `invalidBlocks`, `pendingBytes`). A paused capture never blocks crawling
    or indexing: the document is indexed without the field and counted as
    skipped;
  - `sync`: the synchronisation with Solr (only while the graph runs; `state:
    off` without an embedded Solr core, `unavailable` with
    `remote_solr_unsupported` when only a remote Solr is connected): the
    in-memory change set (`changes`), the persistent queue (`queue`), the
    processing counters (`processed`), the `_version_` checkpoint, the
    reconcile (`reconcile`: `pending`, `reason`, `current` run with phase and
    counts, `awaitingConfirmation`, `catchUp`), `retention` and `lag`
    (`pending`, `oldest_pending_age_s`, `reconcile_pending`);
  - `llm`: the optional LLM tier (package 2b): `state` (`off`,
    `not_configured`, `idle`, `running`, `paused` with `reason:
    circuit_breaker`, `waiting` with a gate, `full_reset` or
    `write_refused`), the selected `model` (`service/model`, never host or
    key), the settings, `queue` (`items`, `claimed`, `oldestEnqueuedAt`,
    `max`), `documents` (`done`, `failed`, `skipped`), `cache` (`entries`,
    `bytes`, `maxBytes`), `breaker` (`open`, `consecutiveFailures`,
    `openUntil`, `backoffMillis`, `timesOpened`, `lastFailure`) and
    `processed` (calls, failures, timeouts, average call time, accepted and
    refused answers by reason, accepted entities, claims and values
    (`valuesAccepted`), dropped ungrounded and invalid items with
    `droppedInvalidByReason`, cache hits and misses, published
    documents and statements, skipped documents by reason). `droppedInvalid`
    counts single dropped entities, claims and values, not calls;
    `droppedInvalidByReason` has every reason code (0 if it never occurred,
    docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3) and adds up to `droppedInvalid`.
    All `processed` counters are in memory since the start and reset by a
    restart; no model answer is stored. `structuredOutput`: what the
    endpoint is asked for (`setting`, `api` `ollama_native` (Ollama's
    `/api/chat` with `format`) or `openai_compatible`, the model's format `capability`
    of the current probe, an older value counts as `unknown`,
    `mode` `schema_enforced`, `schema_unverified`, `json_mode`,
    `validator_only` or `fallback_after_rejection`, `request`, `reason`,
    `withoutSchema`, `requests` per format, `rejections`, `lastRejection`;
    in memory since the start; docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3);
  - `store`: schema version, dataset epoch, `uncleanStartDetected`,
    `startRecorded`, `integrity` (state of `PRAGMA quick_check`),
    `manualPause`, `manualPauseSaved`;
  - the last 20 `events`.

  `startRecorded: false` means the guard refused the start bookkeeping (for
  example `disk_critical`); the graph then runs read-only, graph writes wait
  (`start_not_recorded`) and the start is recorded automatically later.
- **Integrity check:** after an unclean shutdown (and after a check that did
  not finish, also across a clean restart) graph writes wait until `PRAGMA
  quick_check` reports `ok`. The check runs on the maintenance thread as one
  read lease with the deadline `scoutro.kg.integrity.maxMillis` (default
  120000). `store.integrity.state` is `not_required`, `pending`, `running`,
  `ok`, `failed` (a damaged database: also `storage_error`, every write
  stops) or `aborted` (deadline or other error in `error`). A failed or
  aborted check stays visible as `integrity_check_failed` until a new check
  passes.
- **Shutdown:** while the graph runs, a JVM shutdown hook
  (`ScoutroKG.shutdown`) closes it as soon as the JVM shuts down (SIGTERM,
  `docker stop`) and records the clean shutdown. YaCy reaches the servlet's
  `destroy()` only after its main thread has finished and lets the JVM exit
  after 30 seconds, so the graph does not wait for it. SIGKILL is detected as
  an unclean shutdown at the next start.
- **Time limits:** two daemon threads run while the graph runs. The watchdog
  (`ScoutroKG.watchdog`, every 250 ms) only interrupts reads past their
  deadline (`scoutro.kg.read.maxTransactionMillis`, the integrity deadline for
  the check). The maintenance thread (`ScoutroKG.maintenance`) runs the
  integrity check, measurement and checkpoints. A read lease refuses every
  statement and row step after its deadline, so a lease that expires between
  two statements cannot start the next one.
- **Pause reasons:**

  | Reason | Stops |
  |---|---|
  | `storage_error`, `disk_critical`, `wal_checkpoint_blocked`, `wal_limit` | every write, deletions and the runtime's own records included |
  | `integrity_check_pending`, `integrity_check_failed`, `start_not_recorded` | graph writes (growth and deletions); the runtime's own records continue |
  | `manual`, `budget_exhausted`, `budget`, `disk_reserve`, `tmp_limit` | new growth only |

- **Synchronisation (package 2a):**
  - The Solr update processor records every add and delete of
    `collection1` with Solr's version into a bounded change set
    (`scoutro.kg.capture.maxPending`); the sync thread `ScoutroKG.sync`
    drains it into the persistent queue (`scoutro.kg.queue.maxItems`), reads
    each document by real-time get and publishes it with a generation and
    version check. An overflow of either schedules a full reconcile.
  - Only documents of the followed collections (`scoutro.kg.collections`,
    `*` for all) are tracked; states follow `httpstatus_i`/`failtype_s`
    (`active`, `unavailable`, `gone`, `expired`).
  - **Every start and every reactivation** (also after a clean stop and
    after a disabled period) schedules a full reconcile, because Solr may
    have changed meanwhile. After an unclean stop, documents newer than the
    stored `_version_` checkpoint are enqueued first. A delete by query
    schedules a reconcile after `scoutro.kg.reconcile.debounceSeconds`; a
    full clear (`*:*`) resets the graph with a new dataset epoch.
  - A reconcile deletes nothing before its scan has completed; each deletion
    candidate is verified by real-time get, again right before the delete.
    A failing, partial or out-of-order page aborts the run without any
    deletion, and it resumes from its cursor. Above the mass-deletion brake
    (`scoutro.kg.reconcile.maxDeleteFraction` of the tracked documents and at
    least `scoutro.kg.reconcile.brakeMinDocs`, or an empty Solr against a
    non-empty graph) the run stops as `suspect` until `confirm_reconcile`.
  - New growth (extraction) waits behind the gates (`scoutro.kg.gate.*`) and
    the storage guard; removals, state and scope changes continue during a
    pause.
  - On stop (servlet `destroy()` or the JVM shutdown hook) the sync finishes
    its step, the capture stops, and recorded changes are written to the
    persistent queue before the clean-shutdown mark.
- **LLM tier (package 2b, optional):**
  - Runs only for collections in `scoutro.kg.llm.collections` (they must also
    be followed) and only with a model selected for the usage **knowledge**
    in `LLMSelection_p.html` (opt-in column); endpoint, key and `max_tokens`
    are that row's. Without either, the graph is built from tiers 1 and 2
    and `llm.state` is `off` or `not_configured`.
  - `llm.reason` `no_llm_collections` means that no collection is switched on
    for the tier (the key is empty, its default); `llm_collections_not_followed`
    that the collections switched on are all off for the graph. `llm.model`
    names the model of the LLM selection also while the tier is off (package
    6.3). The collections are switched one by one in the collection settings
    (`PATCH …/kg/collections/{collection}` with `llm`), applied at once.
  - Its own threads (`ScoutroKG.extract`, `scoutro.kg.llm.parallel` ≤ 2) and
    its own persistent queue; nothing is locked while a call runs, so a slow
    or hanging model blocks neither crawling, indexing nor the sync.
  - Per document: candidate pages only (imprint, about, services, locations,
    contact, home page, pages with structured data), at most
    `scoutro.kg.llm.maxDocsPerHost` per host, ≤
    `scoutro.kg.extract.maxInputChars` of text in chunks; read timeout
    `scoutro.kg.llm.timeoutSeconds`, ≤ 1 MiB raw response.
  - The answer must be one JSON object of the schema; every entity and
    relation needs a quote found verbatim in the page text that contains the
    names involved, otherwise it is dropped and counted. Persons, e-mail
    addresses and phone numbers are never taken from the model. Statements
    only the LLM tier supports have quality `uncertain`; planned or possible
    facts carry `certainty: hedged`. Evidence names the extractor (`llm`,
    version, model, prompt hash) and the quote.
  - Results are cached per chunk and site (`scoutro.kg.cache.maxPercent` of
    the budget, LRU); a changed page text replaces the document's LLM
    evidence and asks again.
  - Transport failures (timeout, connection, HTTP error) count as attempts
    (`scoutro.kg.llm.maxAttempts`) and for the circuit breaker
    (`scoutro.kg.llm.breakerFailures` in a row: pause of 5 minutes, doubling
    up to `scoutro.kg.llm.breakerMaxBackoffMinutes`); a document that used up
    its attempts is `failed` until its content changes or `llm_retry`.
  - Facility kinds the model may assign come from a small start vocabulary
    per collection, replaced by `scoutro.kg.llm.kinds.<collection>`.
- **Reads (package 3):**
  - Visibility is computed from evidence: a piece of evidence is visible if its
    document is in one of the viewer's collections (`collection`, or all
    followed collections without it), a statement if it has visible
    evidence, an entity if it has a visible statement.
  - Names, aliases, identifiers, quality, `first_seen`, `last_confirmed`,
    counts, hosts and the collections of a source are computed only over
    visible evidence. An object without visible evidence is `404 not_found`,
    exactly like an unknown one; a name search never matches a name that only
    another collection carries. Unknown valid collection names see nothing.
  - Quality per viewer: `supported` (current, stated evidence of tiers 1 or 2
    from an active page), `uncertain` (only hedged, only unavailable pages or
    only the LLM tier), `conflicting` (a single-valued fact with several
    supported values), `stale` (no current source; statements only with
    `include=stale`). `kinds` lists the kinds of the visible evidence;
    `["llm"]` marks a fact only the LLM tier read.
  - Lists: `{"schema":"scoutro.kg.v1","offset","limit","total","items","as_of":{"epoch","seq"},"lag"}`;
    `limit` 1–100 (evidence 1–50), `offset` ≤ 10 000; unknown parameters
    return 400 `invalid_request` with `details.field`.
  - Reads stay available while growth is paused; 409 `kg_disabled` while the
    graph is disabled, 503 `kg_unavailable` when it cannot run.
- **Export and change feed (package 4):**
  - One projection: export records are the JSON of the read routes for the
    same viewer, with the discriminator `record` (`entity`, `statement`; the
    download adds `header` and `trailer`). With `include=evidence` each
    statement carries its newest 20 pieces of visible evidence.
  - Export pages: `{"schema","epoch","as_of_seq","items","complete","next","next_changes"}`.
    First all visible entities, then all visible statements, each in ID
    order; every page is one bounded read. The cursor
    `<epoch>:<as_of_seq>:<e|s><rowid>` carries the change sequence of the
    export's start.
  - The export is not a snapshot. Afterwards a consumer reads
    `GET /kg/changes?cursor=<next_changes>`: upserts by ID are idempotent,
    the delete of an unknown ID is a no-op.
  - Changes: `{"schema","items":[{"seq","kind","id","op","redirect_to","at","record"}],"next","has_more","as_of"}`.
    `op` is `upsert`, `redirect` (merged into `redirect_to`) or `delete`,
    also when the object left the viewer's collections (A → B → C still
    reaches a consumer of A). With `expand=true` each upsert carries its
    current record for the viewer. The cursor advances over changes the
    viewer cannot see.
  - Cursor errors: 400 `invalid_cursor` (malformed or ahead of the feed),
    410 `epoch_changed` after a reset and 410 `cursor_expired` when retention
    removed changes the cursor still needs; both 410 carry `details.full_sync`
    with the export to start again with.
  - Download (administrator only): NDJSON (`application/x-ndjson`) or JSON
    with `Content-Disposition: attachment;
    filename="scoutro-knowledge-<collection|all>-<UTC time>.<format>"`,
    pages of 200 records each in its own read lease. Errors before the first
    page answer as JSON; a failure later ends the stream with a trailer
    `complete:false` and `error`.
- **Agents (package 4):**
  - `kg.read` and `kg.export` are separate scoped grants, absent from every
    preset; `kg.export` is for external agents only. Details:
    [actions](ACTIONS.md#agent-grants-scoutroapiagentv1).
  - The server sets the viewer from the agent: the requested `collection`
    (403 `collection_not_in_scope` outside the scope) or the whole scope;
    there is no implicit cross-collection read.
  - Agent answers: evidence names the extractor (`llm/1`) without the
    configured model; no `lag` (it counts every collection);
    `full_sync` points to `/scoutro/api/agent/v1/kg/export`.
  - `kg/status`, `kg/control`, `kg/prompt` and `kg/export/download` do not
    exist on the agent path (404).
- **Chat (package 4):** see [chat](#graph-facts-in-the-chat) below.
- **Control:**
  - `pause` takes effect at once and survives a restart.
  - `resume` ends a manual pause and schedules a reconcile. After a storage
    error or a failed or aborted integrity check it requests a new `PRAGMA
    quick_check`; graph writes resume only when it passes (watch
    `store.integrity`).
  - `reconcile` schedules a full reconcile now.
  - `confirm_reconcile` lets a reconcile stopped by the mass-deletion brake
    (`sync.reconcile.awaitingConfirmation`) delete; 409 `nothing_to_confirm`
    otherwise.
  - `llm_retry` makes the documents the LLM tier gave up on due again and
    closes the circuit breaker (`reopened` counts them); 409
    `llm_unavailable` while the tier is off.
  - `backup` writes a verified copy (`VACUUM INTO`, `quick_check`, SHA-256
    in a metadata file) into `DATA/SCOUTRO/knowledge/backup` on its own
    thread; `backup.state` and `backup.last` in the status show the result.
    While growth is paused (manual pause, budget brake, disk reserve) the
    backup is skipped with that reason. Scheduled every
    `scoutro.kg.backup.intervalDays` (default 7, 0 = only on request); the
    newest `scoutro.kg.backup.keep` (default 1) are kept; the safety copies of a restore or rebuild stay until the next regular backup.
  - `delete_backup` (package 6.3) deletes one backup by its file name
    (`graph-<UTC>[-before-restore|-before-rebuild|-before-upgrade].db`; 400
    `invalid_request` for any other value, a path included) with its metadata
    file. Only a regular file directly in the backup folder is deleted, never
    a link, the live `graph.db` or its `-wal`/`-shm`; 404 `backup_not_found`
    otherwise, 409 `operation_running` while a backup, restore or rebuild runs
    (nothing deleted), 503 `backup_delete_failed`. The answer is the status
    with `deleted`; the event `backup_deleted` records it.
  - `restore` checks the backup first (name, SHA-256, `quick_check`, schema
    version, epoch) without touching the graph: 404 `backup_not_found`, 422
    `backup_invalid` (`details.reason`). It then stops the graph, keeps the
    current database as `graph-<UTC>-before-restore.db`, puts the backup in
    place with a new dataset epoch (cursors answer 410 `epoch_changed`) and
    starts the graph again, which reconciles with Solr; 503 `restore_failed`
    if it does not start (the previous graph is back).
  - `rebuild` re-resolves every identity: a shadow graph is built from Solr
    in `DATA/SCOUTRO/knowledge/rebuild` with the current rules while the
    graph keeps serving (progress in `rebuild`), checked (`quick_check`, the
    reconcile's mass-deletion brake) and swapped in with a new epoch; the
    previous graph is kept as `graph-<UTC>-before-rebuild.db` and entity IDs
    the new graph does not know become redirects. 409 `operation_running`
    while a backup, restore or rebuild runs (`details.reason`
    `reconcile_busy` while a reconcile waits for its confirmation or a reset
    runs); 503 `kg_write_refused` with `details.reason` `rebuild_space` when
    the budget has no room for the shadow.
  - `rebuild_cancel` deletes the shadow before the swap; the graph stays as
    it is. `rebuild_confirm` lets a rebuild stopped by the brake
    (`rebuild.awaitingConfirmation`) swap. 409 `no_rebuild` otherwise. A
    stop of Scoutro cancels a running rebuild; a shadow left behind by a
    crash is deleted at the next start (`rebuild.phase: interrupted`).
  - 503 `sync_unavailable` for `reconcile`, `confirm_reconcile` and
    `rebuild` where the graph does not follow an embedded Solr core.
  - If the storage guard refuses to store the change (for example during a
    storage error), it is still in effect, `store.manualPauseSaved` is
    `false`, and the maintenance thread stores it later.
  - Unknown fields or actions return 400.
  - 409 `kg_disabled` while the graph is disabled; 503 `kg_unavailable` when
    it cannot run.
- **Storage:** `DATA/SCOUTRO/knowledge/` (`graph.db`, `-wal`, `-shm`,
  `tmp/`, `backup/`, `rebuild/` while a rebuild runs), all counted against
  `scoutro.kg.budget.maxBytes` (default 10 GiB). The budget is an
  application budget (`quota: application_budget`), not a filesystem quota.
  The JSON-LD field lives in the Solr index and has its own budget
  (`scoutro.kg.jsonld.maxTotalBytes`, default 2 GiB). Levels in
  `storage.level` and `jsonld.level`: `notice` from 70 %, `warning` from
  80 % (UI banner, dashboard), `brake` from 90 % (new graph growth pauses;
  the JSON-LD capture pauses while pages are still crawled and indexed),
  `full` at the budget. Neither budget ever stops the crawl.
- **Settings:** see [the plan](SCOUTRO_KNOWLEDGE_GRAPH.md#9-configuration);
  invalid values are listed in `config.errors` and keep the graph off.

### Graph facts in the chat

The RAG chat (`/v1/chat/completions` with a search) adds facts of the
knowledge graph as further numbered sources after the search results:

```
[4] Scoutro knowledge graph: Muster Pflege gGmbH
URL: https://www.muster.de/impressum
Collection: kga
Text: Facts the Scoutro knowledge graph recorded about Muster Pflege gGmbH (organization) from this page, not model knowledge: phone: +49301234567; operates: Haus Lindenhof (uncertain: only read from the page text by a language model).
```

- **Who:** local and administrator access only. AI Shield guests get none
  unless `scoutro.kg.chat.allowGuests=true`; agent tokens are not admitted
  to the chat anyway.
- **Scope:** the request's `collection` (or `collection:` in the question),
  otherwise every collection the graph follows. A global (P2P) question
  without a collection gets no graph facts. Only evidence of that scope
  counts, as in the read routes.
- **What:** entities the question names (at least two distinctive name words,
  or the only one) and entities with evidence on the retrieved pages, at
  most 4; their current `supported` and `uncertain` facts (uncertain ones
  marked, conflicting and stale ones left out), at most
  `scoutro.kg.chat.maxFacts` (8). Each entry names the visible page the facts
  were read from, so the answer can cite it like a search result and
  `scoutro-citations` validates it.
- **Budget:** at most `scoutro.kg.chat.maxChars` (1500) and a third of the
  source budget; the search sources get the rest.
  `scoutro.kg.chat.timeoutMs` (300) bounds the step: a timeout, an error or
  a graph that is off skips it, and the search answer is always produced.
- **Metadata:** `scoutro-sources` marks the entries with `"kind":"graph"`
  (the chat page shows a "Scoutro knowledge graph" badge);
  `scoutro-graph: {used, entities, facts, sources, timedOut, reason,
  collection, millis}` says why facts were or were not added (`reason`:
  `access`, `global`, `disabled`, `not_running`, `no_terms`, `no_room`,
  `no_facts`, `timeout`, `busy`, `error`). Follow-up questions keep the
  entries in the attached search document.
- `scoutro.kg.chat.enabled=false` switches the step off.

## Scoutro native crawl and host flow

Collection is now mandatory for every Scoutro crawl, including administrators. Safe retry uses Idempotency-Key. See [the complete contract](SCOUTRO_CRAWL_FLOW.md) for host resolution, scoped collection suggestions, durable metadata, Discovery states and CLI examples.

`GET /v1/index/browse` and `GET /agent/v1/index/browse` provide literal host/URL browsing with an exact, server-enforced collection filter. The agent route requires explicit `index.browse` and never extends presets. See [Index Browser](SCOUTRO_INDEX_BROWSER.md) for parameters, empty/error behavior and CLI usage.

`GET /v1/index/domains` lists the index consolidated per host and collection; `GET /v1/index/domains/export?format=json|csv` downloads all entries of the same filter as `scoutro.domains.v1` (administrator only, read-only, streamed). Contract and field sources: [Index Browser](SCOUTRO_INDEX_BROWSER.md#export-contract-scoutrodomainsv1).

Structured system facts: `GET /v1/index/metrics` and `GET /v1/system/questions`, with corresponding authorized agent routes. CLI `index metrics`/`ask`, JSON/SSE chat behavior and real read-only stdio MCP adapter are documented in [System questions and MCP](SCOUTRO_SYSTEM_QUESTIONS_MCP.md). The question router delegates to existing grants; it is not a broad system/admin grant.
