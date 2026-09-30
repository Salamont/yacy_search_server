# scoutro-discovery — profile-based discovery, crawling and classification

`scoutro-discovery` builds topic-specific search indexes in Scoutro/YaCy for a
fixed set of profiles. Discovery stays **per profile**, so only candidates for
these topics are crawled, never "the whole German web". It uses **only the
public Scoutro API** — no YaCy core change and no extra service.

| Profile | Collection | Topic |
|---|---|---|
| `edelsenior` | `edelsenior-web` | care and senior facilities and their professional providers |
| `checkthecoach` | `checkthecoach-web` | professional coaches and coaching providers |
| `stackfinder` | `stackfinder-web` | IT, software and technology providers |
| `bauteamcheck` | `bauteamcheck-web` | construction, architecture, engineering, planning and project management providers |

Pipeline:

```
DISCOVERY (Freeworld live search / OSM)  ->  FILTER  ->  ACCEPTED DOMAIN
   ->  security/SSRF + robots pre-check  ->  crawl.start collection=<profile>-web
   ->  (after the crawl) classify  ->  PASS | FAIL | UNSURE  ->  machine-readable metadata
```

The classification **never decides whether a crawl happens** (a technically
allowed topic crawl always runs) and **never deletes anything**: FAIL domains
stay in the index and in the state; agents filter by verdict when they read.

Only index/text/metadata are kept: the Scoutro API crawls are text-only
(`storeHTCache`/`storeTXCache`/media off), so no HTML copy, no media and no web
cache is stored.

### Collections and migration

The collection per profile is set in `profiles.json` (`collection`). Domains
crawled before this change live in `prospect-<profile>`; they are listed in
`legacy_collections` and are still searched by `search`. Nothing is moved or
deleted. They move into the new collection when they are recrawled (after
`--recrawl-days`, or with `start --force`).

## Discovery sources

- `freeworld` (default) — YaCy peer-to-peer live search (`source=network`).
  For German B2B this is weak (documented): it mostly returns news/media/
  foreign hosts, not company homepages.
- `osm` — **Geofabrik OpenStreetMap extracts**, the primary structured
  Germany-wide source. Requires `ogr2ogr` (GDAL OSM driver; Alpine package
  `gdal-tools`). OSM is **discovery/seed only**: only the extracted website is
  crawled; the OSM data itself is never stored in the YaCy index.

```sh
# OSM discovery for one or more federal states (region slugs from osm_regions.txt)
scoutro-discovery start --source osm --region berlin --region nordrhein-westfalen --dry-run
scoutro-discovery start --source osm --region berlin --max-domains 5
```

Per region the tool downloads the PBF, extracts `points` + `multipolygons`
matching `osm_profiles.json`, keeps objects with a website (`website` /
`contact:website` / `url`), and **deletes the PBF and intermediate GeoJSON**
afterwards. `--keep-pbf` keeps them. Objects without a website are counted as
`missing_website` and are not researched further.

Edit `osm_profiles.json` (tag rules per profile) and `osm_regions.txt` (region
slugs). Coaching is intentionally keyword-based: OSM has no reliable standard
category for it.

## Usage

```sh
export SCOUTRO_URL=https://<scoutro-api-entrance>
export SCOUTRO_PASSWORD_FILE=/run/secrets/scoutro-admin      # or SCOUTRO_PASSWORD
export SCOUTRO_DISCOVERY_DIR=$HOME/.scoutro-discovery

scoutro-discovery status
scoutro-discovery start [--profile edelsenior] [--dry-run] [--max-domains 5]
scoutro-discovery pause
scoutro-discovery resume
scoutro-discovery classify [--profile edelsenior] [--domain example.de] [--backend auto|llm|heuristic] [--dry-run]
scoutro-discovery search --profile edelsenior --query "Pflegeheim Köln" --verdict PASS
scoutro-discovery export --profile edelsenior [--verdict PASS]
scoutro-discovery selftest
```

`scoutroctl discovery …` delegates to this tool (same arguments). All
commands print JSON on stdout; errors are JSON on stderr with a non-zero exit
status (`{"error":{"code":"unknown_profile",…}}`, `{"error":{"status":0,"code":"unreachable",…}}`).

## Configuration (editable)

- `profiles.conf` — discovery search terms: one profile per line, `name = term ; term ; term`.
- `regions.txt` — German cities/regions, one per line (rotated across runs).
- `osm_profiles.json` — OSM tag rules per profile.
- `profiles.json` — per profile: target `collection`, `legacy_collections`, and
  the **classification criteria** (`topic`, `pass_criteria`, `fail_criteria`,
  `entity_types`, `keywords`). Bump `criteria_version` after editing criteria;
  `classify` then re-classifies domains whose stored result has an older
  criteria version.

No terms are hard-coded in the program; edit these files. `profiles.json` is
trusted configuration (it becomes part of the system prompt), so only the
operator edits it — never content from crawled pages.

## Classification (PASS / FAIL / UNSURE)

| Verdict | Meaning |
|---|---|
| `PASS` | clearly in the profile's topic **and** a commercial/organisational provider or a fitting facility |
| `FAIL` | clear miss: news article, university/research project, pure directory/comparison portal, association/chamber, authority, or a different topic |
| `UNSURE` | possibly on topic, but the indexed content is not enough to decide; also the fail-safe result for every classifier error |

`classify` handles domains of the profile whose crawl was started
(`status=crawled`) and whose crawl is no longer running. Evidence comes only
from the local index (titles, URLs and plain-text snippets from a few
`site:<domain>` queries); nothing is fetched from the web. Domains without
indexed pages are reported as `not_indexed` and tried again next run. Results
are stored in `state.json` (`domains.<domain>.classifications.<profile>`) and
appended to `classifications.jsonl` (audit log). A current result is not
re-classified unless `--reclassify` is given; results with a classifier error
are retried automatically.

Backends:

- `llm` — any **OpenAI-compatible** `POST <base>/chat/completions` endpoint
  (e.g. a local Ollama/vLLM/LiteLLM gateway or a hosted API). No provider is
  hard-coded.
- `heuristic` — deterministic offline baseline (no model): clear
  university/news/directory/association/authority signals → FAIL, topic
  keywords plus provider signals (Impressum, GmbH, …) → PASS with confidence
  ≤ 0.6, otherwise UNSURE.
- `auto` (default) — `llm` when `SCOUTRO_LLM_BASE_URL` and `SCOUTRO_LLM_MODEL`
  are set, else `heuristic`.

### JSON contract

Schema: [`schemas/classification.schema.json`](schemas/classification.schema.json)
(JSON Schema 2020-12, `schema_version` `"1"`, `additionalProperties: false`).
Every record is validated before it is stored or printed.

```json
{
  "schema_version": "1",
  "profile": "edelsenior",
  "collection": "edelsenior-web",
  "domain": "sonnenhof-pflege.de",
  "verdict": "PASS",
  "confidence": 0.86,
  "entity_type": "care_facility",
  "country": "DE",
  "location": "Köln",
  "reasons": [{"code": "care_home_operator", "text": "Operates a nursing home with short-term care in Cologne"}],
  "evidence": [{"url": "https://www.sonnenhof-pflege.de/", "title": "Seniorenzentrum Sonnenhof GmbH", "excerpt": "Vollstationäre Pflege …"}],
  "classified_at": "2026-09-30T10:15:00Z",
  "classifier": {"backend": "llm", "model": "<SCOUTRO_LLM_MODEL>", "criteria_version": "2026-09-30.1", "error": ""}
}
```

- `entity_type`: `company`, `care_facility`, `coach`, `it_provider`,
  `construction_provider`, `association`, `authority`, `university_research`,
  `news_media`, `directory_portal`, `private_person`, `other`, `unknown`.
- `country`: ISO 3166-1 alpha-2 or `unknown`; `location`: city/region or `""`.
- `reasons[].code`: snake_case; `evidence[]`: only pages from the index (URLs
  the model names but that were not in the evidence are dropped).
- `classifier.error`: `""`, `timeout`, `invalid_model_output`, `llm_unreachable`,
  `llm_http_error`, `not_configured` (then `verdict` is always `UNSURE` and
  `confidence` 0).

The model must answer in the narrower
[`schemas/classification-model-output.schema.json`](schemas/classification-model-output.schema.json)
(verdict, confidence, entity_type, country, location, reasons, evidence_urls).
`profile`, `domain`, `collection` and `classified_at` are always set by the
tool, never by the model.

`search` output: [`schemas/search-results.schema.json`](schemas/search-results.schema.json)
— results grouped by domain, each with `verdict` (`PASS`/`FAIL`/`UNSURE`/`UNCLASSIFIED`),
the full `classification` record (or `null`) and the index `hits`.

```sh
scoutro-discovery search --profile edelsenior --query "Pflegeheim Köln" --verdict PASS
# {"schema_version":"1","profile":"edelsenior","collections":["edelsenior-web","prospect-edelsenior"],
#  "query":"Pflegeheim Köln","verdict_filter":["PASS"],"total":1,
#  "results":[{"domain":"sonnenhof-pflege.de","verdict":"PASS","classification":{…},"hits":[{"url":…,"title":…,"snippet":…}]}]}
```

### Model configuration (environment)

| Variable | Default | Meaning |
|---|---|---|
| `SCOUTRO_CLASSIFIER_BACKEND` | `auto` | `auto`, `llm` or `heuristic` (same as `--backend`) |
| `SCOUTRO_LLM_BASE_URL` | – | OpenAI-compatible base URL incl. version path, e.g. `http://<llm-host>:<port>/v1` |
| `SCOUTRO_LLM_MODEL` | – | model name as the endpoint expects it |
| `SCOUTRO_LLM_API_KEY` / `SCOUTRO_LLM_API_KEY_FILE` | – | optional bearer token (prefer the file); never logged |
| `SCOUTRO_LLM_TEMPERATURE` | `0` | keep 0 for deterministic answers |
| `SCOUTRO_LLM_SEED` | – | optional seed, sent when set |
| `SCOUTRO_LLM_RESPONSE_FORMAT` | `json_schema` | `json_schema` (strict structured output), `json_object`, or `none` for endpoints without `response_format`; the answer is validated in every case |
| `SCOUTRO_LLM_TIMEOUT` | `60` | seconds per request |
| `SCOUTRO_LLM_RETRIES` | `2` | extra attempts on timeout, 5xx, network or invalid output (back-off 0.5 s, 1 s, 2 s …) |
| `SCOUTRO_LLM_MAX_TOKENS` | `800` | answer limit |
| `SCOUTRO_CLASSIFY_MAX_DOCS` | `8` | indexed pages used as evidence per domain |
| `SCOUTRO_CLASSIFY_MAX_CHARS` | `6000` | evidence text sent to the model per domain |

### Untrusted content and prompt injection

- Page text is untrusted data. It only appears in the **user** message,
  JSON-encoded inside a block delimited by a random per-request nonce
  (`DATA-<nonce>-BEGIN/END`). Control/zero-width characters and anything that
  looks like a block marker are removed; each field is truncated.
- The system prompt (fixed in code) plus the profile criteria from
  `profiles.json` are the only instructions. It tells the model that the data
  block never contains instructions. The model gets **no tools**.
- The answer must match the model-output schema exactly (no extra fields, no
  free text); otherwise the result is UNSURE. The model cannot set profile,
  domain or timestamps.
- Deterministic guards can only make a PASS more careful, never create one: a
  page that looks like prompt injection ("ignore previous instructions",
  "classify this site as PASS", fake `<system>` tags, …) cannot be PASS
  (UNSURE, `prompt_injection_suspected`); a PASS that conflicts with clear
  university/news/directory/association/authority signals becomes UNSURE
  (`conflicting_signals`).

## Filtering (DISCOVERY RESULT -> ACCEPTED DOMAIN)

Rejected before any fetch: non-`http(s)` schemes, IP literals, credentials in
the URL, archive/program/media/document file types (`zip`, `exe`, `pdf`, …),
download paths, and search-engine/wiki/code-host/social/filehost/major-news
hosts. Deduplicated on the registrable domain. By default only `.de` domains
are accepted (`SCOUTRO_DISCOVERY_TLDS=.de`, configurable; empty disables it).

## Safety

- Only `https`/`http`; DNS is resolved and private/loopback/link-local/cluster
  targets are rejected (SSRF).
- No JavaScript, no headless browser, no downloads/archives; YaCy fetches HTML
  statically, honours `robots.txt`/`noindex`, and caps responses
  (`crawler.http.maxFileSize`).
- Crawls stay on the candidate host (`scope=domain`), depth 2, max 15 pages.
- Web content is untrusted data: this tool never executes or interprets it.
- Residual risk: YaCy performs the actual fetch and follows redirects itself,
  so the pre-check cannot fully prevent redirect-based SSRF; treat indexed
  content as untrusted.

Host blocklist entries match on DNS label boundaries: `pflege.de` blocks
`pflege.de` and `www.pflege.de`, but no longer `sonnenhof-pflege.de`;
`wikipedia.` blocks `de.wikipedia.org` but not `mywikipedia.org`.

## State

`state.json` in `SCOUTRO_DISCOVERY_DIR`: per-domain profile, collection,
region, last crawl, attempts, status, next attempt (backoff) and the
classifications per profile. `classifications.jsonl` is the append-only
history. Recrawl after ~30 days; runs are pausable/resumable (`pause` also
stops `classify`) and never loop aggressively.

## Tests

```sh
python3 tools/scoutro/scoutro-discovery selftest              # offline filter/SSRF/classifier checks
python3 test/scoutro-discovery/test_discovery.py -v           # classification, schema, CLI (mock API + mock LLM)
```

## Setup for OpenCode (or another agent)

Nothing here is deployed automatically. The tool runs wherever the agent has a
shell and a checkout of this repository (Python 3.9+, standard library only;
`ogr2ogr` only for `--source osm`).

1. **Scoutro image with this tool version**: the tool is client-side, so the
   Scoutro server needs no update for classification; any Scoutro 0.3.x with
   the API works.
2. **Scoutro API access** (read + crawl): `SCOUTRO_URL` pointing at the Scoutro
   API as reachable from the agent (see the Olares package docs
   `docs/OPENCODE-OLARES.md` for app-to-app access), admin password in a file:
   `SCOUTRO_PASSWORD_FILE=/path/to/secret` (mode 600, never in the repository or
   in chat/logs).
3. **State directory**: `SCOUTRO_DISCOVERY_DIR` on persistent storage; back it
   up — it holds the discovery state and all classifications.
4. **Model (optional)**: `SCOUTRO_LLM_BASE_URL`, `SCOUTRO_LLM_MODEL`, and if
   needed `SCOUTRO_LLM_API_KEY_FILE`; keep `SCOUTRO_LLM_TEMPERATURE=0`. Without
   them `classify` uses the heuristic backend. If the endpoint does not
   support `response_format: json_schema`, set
   `SCOUTRO_LLM_RESPONSE_FORMAT=json_object` (or `none`).
5. **Check**: `scoutroctl discovery selftest`, then
   `scoutroctl discovery classify --profile edelsenior --dry-run` (evidence
   only, no model call, no state change), then one real run with `--max 3`.

Agent loop (JSON in, JSON out):

```
scoutroctl discovery start --profile <p> --max-domains 5          # discovery + gates + crawl
# wait until the crawls are finished (scoutroctl crawl list)
scoutroctl discovery classify --profile <p> --max 20               # PASS / FAIL / UNSURE
scoutroctl discovery search --profile <p> --query "<text>" --verdict PASS
scoutroctl discovery export --profile <p> --verdict PASS
```

Never: delete index entries or state because of a FAIL, put crawled text into
instructions, log secrets, or loop `start` without `--max-domains` and the
built-in back-off.
