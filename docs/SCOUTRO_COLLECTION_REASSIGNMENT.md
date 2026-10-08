# Moving a domain between collections (collections.reassign)

Status: 2026-10-08. Background: Discovery crawled `yowea.com`, a coaching consultancy, for the StackFinder profile, so
its pages are in `stackfinder-web` although they belong to `checkthecoach-web`. The generic StackFinder OSM rule
accepted `office=consulting` together with the word "consulting" (see
[tools/scoutro/discovery/README.md](../tools/scoutro/discovery/README.md)).

## What the index and Discovery do on their own (code, not assumptions)

- **A crawl replaces the collections.** YaCy writes a crawled page with the collections of its crawl profile only
  (`CollectionConfiguration.yacy2solr`, `collection_sxt`); the page is added again as a whole. A second crawl of a
  page into another collection therefore *moves* it there; it never adds a collection.
  *This contradicts the operations report of 2026-10-08 ("a new crawl adds collections"); the code shows a
  replacement. The report may have looked at a domain whose pages were in both collections from two separate crawls
  of different URLs.*
- **Discovery refuses such a move.** `DiscoveryGateway.start` answers `409 collection_conflict` when the host has
  pages in another collection than the job's. For yowea.com this is why the CheckTheCoach job found the domain but
  could not crawl it.
- **Discovery re-crawls from its state.** The automation picks `crawled` pairs of its profile from `state.json`
  again after `processing.recrawl.days` (`select_backlog`), whether or not the domain is still a candidate. A fixed
  rule therefore does not stop the StackFinder re-crawls of yowea.com. While the pages are in `stackfinder-web`
  those re-crawls succeed and keep them there.
- **Classification moves nothing.** `classify` and its verdicts (`criteria_version`) only change Discovery's
  `search`/`export` and the domain lists; never the crawl, the index or the knowledge graph.
- **Nothing in YaCy or Scoutro changed `collection_sxt` of indexed pages** before this action: no servlet, API
  action or job. Deleting and crawling again would lose pages that are gone or blocked meanwhile and is not wanted.

## The action

`POST /scoutro/api/v1/collections/reassign` (administrator only, same origin, at most 4 KiB; never an agent route),
`scoutroctl collections reassign DOMAIN --add NAME --remove NAME [--confirm TOKEN]`.

- **Scope.** Exactly one registrable domain and its subdomains (`yowea.com`, `www.yowea.com`, `blog.yowea.com`;
  never `notyowea.com` or `yowea.de`). The pages are found through `host_organization_s` and then filtered exactly by
  host. Only pages in a collection of `remove` move; without `remove` every page of the domain gets `add`. Other
  collections of a page stay. A page that would keep no collection is refused (`422 reassign_would_empty`), as is a
  domain with more than 5 000 pages (`422 reassign_too_large`).
- **Preview first.** Without `confirm` nothing is written. The answer lists the hosts, the number of pages, pages per
  collection before and after, the collections that stay, a sample of up to 20 URLs and a `token`.
- **Apply exactly the preview.** With `confirm` set to the token, the same request is planned again. If any page
  changed since the preview (a new crawl, a new version), the token no longer matches: `409 reassign_preview_stale`,
  nothing is written, and the error details hold the new preview. While a crawl of the domain runs, apply answers
  `409 host_busy`.
- **How it writes.** Every page gets a Solr atomic update of `collection_sxt` only, with the `_version_` read for
  the plan. Text, JSON-LD, title, links and every other field stay. A page whose version changed in the meantime is
  refused by Solr and reported in `failed`. The update goes to the Solr client directly: the connector's `add()`
  would, on an error, delete the page and write the patch alone. The webgraph edges of the domain are updated the same
  way when the webgraph is written. Then one commit.
- **Commit lag.** YaCy indexes through a queue. A page indexed in the last seconds, before Solr committed it, is not
  yet in the preview. Run the preview again after a crawl has finished.

## What the knowledge graph does

The graph's capture records the atomic update like any other change, and its sync reads the page again (real-time get).

- **Collections it follows:** the page only changes collection membership (`LIFECYCLE`, no new extraction). Facts,
  evidence and entity IDs stay; the visibility scopes of statements and entities move with it.
- **A collection it does not follow:** the page leaves the graph.
- **A collection newly followed:** the page enters the graph as a new page.

Not changed by the move:

- the evidence that was extracted under the old collection's vocabulary. A later re-extraction, for example after a
  rule extractor version change, reads it with the new one.
- the LLM state of the page.

## Keeping it corrected

1. **Fix the rule** in the *runtime* configuration (`DATA/SCOUTRO/config/osm_profiles.json`, see below). The
   repository files are examples; automation never falls back to them.
2. **Take the pair out of Discovery's selection:**
   `scoutro-discovery --workdir <state root> exclude --profile stackfinder --domain yowea.com --reason "…" --dry-run`,
   then without `--dry-run`. The entry keeps its history and gets status `excluded`. It is never selected again, by
   the manual start or the automation, also not with `--force`. `include` undoes it.
3. **The Discovery guard** (`collection_conflict`) refuses any later StackFinder crawl of the domain while its pages
   are in `checkthecoach-web`. A CheckTheCoach crawl of the domain is allowed and writes `checkthecoach-web` again.

A page meant to be in **two** collections at once (a legitimate multiple assignment) can only be made with this
action. Any later crawl of it writes the crawl's single collection, and Discovery refuses to crawl it as
`collection_conflict`.

## Operating procedure for yowea.com (OpenCode, after a rollout of this change)

Run on the Olares peer with administrator credentials from the secret store, never typed into logs. Values in angle
brackets come from the step before.

0. **Record the starting state** (read only):
   - `GET /scoutro/api/v1/discovery/status`
   - the yowea.com entries of `DATA/SCOUTRO/discovery/state.json`: profiles `stackfinder` and `checkthecoach`
   - `GET /scoutro/api/v1/index/browse?q=yowea.com&limit=100`: expected 18 pages, all `stackfinder-web`
   - `GET /scoutro/api/v1/kg/hosts/yowea.com/entities?collection=stackfinder-web` and
     `…&collection=checkthecoach-web`
   - KG status `sync.processed`: `extractions`, `lifecycle`
   - a knowledge graph backup: `POST /scoutro/api/v1/kg/control {"action":"backup"}`
1. **Pause Discovery:** `POST /scoutro/api/v1/discovery/pause`. Check that no crawl of `yowea.com` runs
   (`GET /scoutro/api/v1/crawls`).
2. **Runtime rule:** in `DATA/SCOUTRO/config/osm_profiles.json`, replace the generic StackFinder rule (`office`
   company/consulting) with the rule of the repository's `tools/scoutro/discovery/osm_profiles.json`. In
   `DATA/SCOUTRO/config/profiles.json`, set `criteria_version` to `2026-10-08.1` and take over the StackFinder
   `fail_criteria` and `keywords`.
   - Keep a copy of both files first and diff them.
   - The rule needs the new `scoutro-discovery` (keys `text_fields`, `text_words`). An older one would read them as
     OSM tags, so the rule would match nothing.
3. **Preview:**
   `POST /scoutro/api/v1/collections/reassign {"domain":"yowea.com","add":["checkthecoach-web"],"remove":["stackfinder-web"]}`.
   Expected:
   - `hosts`: only yowea.com hosts
   - `documents` = `changes` = 18
   - `before` {"stackfinder-web": 18}, `after` {"checkthecoach-web": 18}, `kept` []
   - `crawlRunning` false
   - plausible `sample` URLs
   Stop if any value differs and report it.
4. **Apply:** the same body with `"confirm":"<token>"`. Expected: `applied` true, `updated` 18, `failed` [].
   - On `409 reassign_preview_stale`: compare the new preview in the details, then repeat steps 3 and 4 once.
5. **Discovery state:** `scoutro-discovery --workdir DATA/SCOUTRO/discovery exclude --profile stackfinder --domain yowea.com --reason "coaching consultancy, moved to checkthecoach-web 2026-10" --dry-run`.
   Check `before`/`after`, then run it without `--dry-run`. Leave the `checkthecoach` entry unchanged.
6. **Success checks:**
   - index browse: 18 pages, each with `checkthecoach-web` and without `stackfinder-web`
   - the same 18 URLs as in step 0
   - the KG lists the yowea.com entities under `checkthecoach-web`, none under `stackfinder-web`
     (wait until `sync.lag.pending` is 0)
   - KG `extractions` unchanged, `lifecycle` + 18
   - another StackFinder domain and another CheckTheCoach domain from step 0 unchanged
   - `GET /scoutro/api/v1/collections`: `stackfinder-web` count − 18, `checkthecoach-web` + 18
   - the second preview answers `documents: 0`
7. **Resume Discovery:** `POST /scoutro/api/v1/discovery/resume`. Optionally run `scoutro-discovery classify --profile stackfinder`;
   with the new `criteria_version` it re-classifies the old StackFinder results (verdicts only).
8. **Rollback** (only if a check fails):
   - the same request with `add`/`remove` swapped (preview, then apply);
   - `scoutro-discovery include --profile stackfinder --domain yowea.com`;
   - put the copied runtime config files back.
   The graph backup of step 0 is only for a damaged graph, not for this.
