# scoutro-discovery — Germany-wide prospect discovery

`scoutro-discovery` builds a topic-specific prospect search index in Scoutro/YaCy
for a set of profiles (e.g. `edelsenior`, `checkthecoach`, `stackfinder`,
`bauteamcheck`). It uses **only the public Scoutro API** — no YaCy core change
and no extra service.

Pipeline:

```
DISCOVERY (Freeworld live search)  ->  FILTER  ->  ACCEPTED DOMAIN
   ->  safety/robots pre-check  ->  crawl.start collection=prospect-<profile>
```

Only index/text/metadata are kept: the Scoutro API crawls are text-only
(`storeHTCache`/`storeTXCache`/media off, see the Olares package), so no HTML
copy, no media and no web cache is stored.

## Usage

```sh
export SCOUTRO_URL=https://<scoutro-api-entrance>
export SCOUTRO_PASSWORD_FILE=/run/secrets/scoutro-admin      # or SCOUTRO_PASSWORD
export SCOUTRO_DISCOVERY_DIR=$HOME/.scoutro-discovery

scoutro-discovery status
scoutro-discovery start [--profile edelsenior] [--dry-run] [--max-domains 5]
scoutro-discovery pause
scoutro-discovery resume
scoutro-discovery search --profile edelsenior --query "Pflegeheim Köln"
scoutro-discovery selftest
```

`scoutroctl discovery …` delegates to this tool.

## Configuration (editable)

- `profiles.conf` — one profile per line, `name = term ; term ; term`.
- `regions.txt` — German cities/regions, one per line (rotated across runs).

No terms are hard-coded in the program; edit these files.

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

## State

`state.json` in `SCOUTRO_DISCOVERY_DIR`: per-domain profile, last crawl,
attempts, status and next attempt (backoff). Recrawl after ~30 days; runs are
pausable/resumable and never loop aggressively.
