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
  never `notyowea.com` or `yowea.de`). The hosts come from a facet of `host_s` over the whole index, limited to the
  hosts that contain the domain (`facet.contains`), then filtered exactly. That needs no `host_organization_s` (a page
  indexed without it is found as well) and counts no page, so a large domain of the same name (`yowea.de`) never
  makes the request fail. Only pages in a collection of `remove` move; without `remove` every page of the domain gets
  `add`. Other collections of a page stay. A page that would keep no collection is refused
  (`422 reassign_would_empty`), as is a domain with more than 5 000 pages or 100 000 webgraph edges to change
  (`422 reassign_too_large`); only the domain's own pages count.
- **Preview first.** Without `confirm` nothing is written. The answer lists the hosts, the number of pages, pages per
  collection before and after, the collections that stay, a sample of up to 20 URLs and a `token`.
- **Apply exactly the preview.** With `confirm` set to the token, the same request is planned again. If any page
  changed since the preview (a new crawl, a new version), the token no longer matches: `409 reassign_preview_stale`,
  nothing is written, and the error details hold the new preview. While a crawl of the domain runs, apply answers
  `409 host_busy`.
- **How it writes.** Every page gets a Solr atomic update of `collection_sxt` only, with the `_version_` read for
  the plan. Text, JSON-LD, title, links and every other field stay. A page whose version changed in the meantime is
  refused by Solr and reported in `failed`. The update goes to the Solr client directly: the connector's `add()`
  would, on an error, delete the page and write the patch alone. Then one commit.
- **Webgraph edges follow their page.** When the webgraph is written, the edges of the domain's pages are found by
  their page (`source_id_s`; `source_host_s` is off in the default webgraph schema). Each edge gets exactly the
  collections its page has after the plan, and only if that page was changed or already had them. An edge of a page
  whose update failed keeps its collections, counted as `webgraphKept`, so page and edges never disagree. The next
  run moves the page and its edges together. An edge left behind by an earlier run is corrected by the next run.
- **Commit lag.** YaCy indexes through a queue. A page indexed in the last seconds, before Solr committed it, is not
  yet in the preview. Run the preview again after a crawl has finished.

## What the knowledge graph does

The graph's capture records the atomic update like any other change, and its sync reads the page again (real-time get).

- **Collections it follows, page otherwise unchanged:** the page only changes collection membership (`LIFECYCLE`, a
  maintenance write). Facts, evidence and entity IDs stay; the visibility scopes of statements and entities move
  with it. This also runs while the graph is paused manually (a pause stops growth, not maintenance).
- **A collection it does not follow:** the page leaves the graph.
- **A collection newly followed:** the page enters the graph as a new page.

Not changed by the move:

- the evidence of the rule, metadata and JSON-LD tiers (tiers 1 and 2) extracted under the old collection's
  vocabulary, until the page is extracted again.
- the evidence and state of the LLM tier (tier 3). It is not asked again and its answers are not corrected, neither
  by the move nor by a new extractor version.

### Together with the rule extractor version 4 (PR #36)

PR #36 changes `RuleExtractor.VERSION` to 4, and with it the extractor identity. The first start after a rollout of
both re-extracts every tracked page at low priority (`Reconciler.reextractAll`).

- **What it renews.** Only tiers 1 and 2 (JSON-LD, metadata, rules). The LLM tier is not asked again: its mark is
  the content hash. **Old LLM evidence stays as it is.** Nothing corrects it by itself. It is renewed only when the
  page's content changes or the LLM extractor's own version changes. A page marked `skipped` for the LLM stays skipped
  until the LLM collection selection changes.
- **Pages are extracted again, not only re-scoped.** After that rollout every page's input differs from what the graph
  read. A page the move touches is therefore extracted again (growth), not just re-scoped. Growth waits while the
  graph is paused manually, so yowea.com's collections in the graph change only after the resume.
- **Order matters for the vocabulary of tiers 1 and 2.** Whichever comes first decides it:
  - *Move first, then let the re-extraction reach the pages (recommended):* pause the graph's growth before the
    rollout, move the domain, then resume. The re-extraction reads yowea.com with `checkthecoach-web` and its coaching
    vocabulary.
  - *Re-extraction first:* yowea.com's tiers 1 and 2 are read once more with the StackFinder vocabulary. A later move
    then only re-scopes them, and that evidence keeps the StackFinder vocabulary until the page is extracted again
    (content change or a later extractor version). There is no action to re-extract one domain.
- **The merges of LIVAID/Markel are a separate step.** Neither the move nor the re-extraction splits them; that needs
  the separately planned rebuild of the identities (`KgRebuild`, PR #36).

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

The success checks compare the **same documents by their IDs**, never global counters or collection totals: the graph
may work on other pages in parallel (the re-extraction of PR #36, crawls of other domains, the LLM tier), so those
numbers move anyway.

0. **Record the starting state** (read only):
   - `GET /scoutro/api/v1/discovery/status`
   - the yowea.com entries of `DATA/SCOUTRO/discovery/state.json`: profiles `stackfinder` and `checkthecoach`
   - the domain's pages: `GET /scoutro/api/v1/index/browse?q=yowea.com&limit=100`. Keep the list of
     `id`, `url` and `collections` (expected 18, all `stackfinder-web`; only hosts that are yowea.com or end with
     `.yowea.com`).
   - for each of these IDs: `GET /scoutro/api/v1/kg/sources/<id>?limit=1`. Keep `source.collections`,
     `source.processed_at` and `total`.
   - two control domains, one of `stackfinder-web` and one of `checkthecoach-web`: the same two lists for their pages.
   - a knowledge graph backup: `POST /scoutro/api/v1/kg/control {"action":"backup"}`.
1. **Pause** (for a release with PR #36, before the rollout: [rollout 0.8.7](SCOUTRO_ROLLOUT_0.8.7.md)):
   - Discovery automation: read `revision` from `GET /scoutro/api/v1/discovery/status`, then
     `POST /scoutro/api/v1/discovery/pause` with the body `{}` and the header `If-Match: <revision>` (`428` without
     it; `409 revision_conflict` for an old revision: read the status again). Accepted crawls continue.
   - Manual Discovery runs: `scoutro-discovery --workdir <state root> pause`. `start` and `classify` refuse while it is
     set, and the automation does not start a batch either; `exclude` and `include` still work.
   - If PR #36 is in the same release: before the rollout, also pause the graph's growth
     (`POST /scoutro/api/v1/kg/control {"action":"pause"}`, kept over the restart). The re-extraction then reaches
     yowea.com only after the move.
   - Check that no crawl of `yowea.com` runs (`GET /scoutro/api/v1/crawls`).
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
   - `documents` = `changes` = the number of step 0 (18)
   - `before` {"stackfinder-web": 18}, `after` {"checkthecoach-web": 18}, `kept` []
   - `crawlRunning` false
   - the `sample` URLs among those of step 0
   Stop if any value differs and report it.
4. **Apply:** the same body with `"confirm":"<token>"`. Expected: `applied` true, `updated` 18, `failed` [], and
   `webgraphKept` 0 when the webgraph is written.
   - On `409 reassign_preview_stale`: compare the new preview in the details, then repeat steps 3 and 4 once.
   - Pages in `failed`: run steps 3 and 4 again. They move then, together with their edges.
5. **Discovery state:** `scoutro-discovery --workdir DATA/SCOUTRO/discovery exclude --profile stackfinder --domain yowea.com --reason "coaching consultancy, moved to checkthecoach-web 2026-10" --dry-run`.
   Check `before`/`after`, then run it without `--dry-run`. Leave the `checkthecoach` entry unchanged.
6. **Resume:** `POST /scoutro/api/v1/kg/control {"action":"resume"}` if the graph was paused in step 1.
7. **Success checks** (by ID, against step 0):
   - **index:** every ID of step 0 is still there with the same `url`, with `checkthecoach-web` and without
     `stackfinder-web`. No other ID has appeared for the domain (or it is a newly crawled page).
   - **graph:** for every ID, `GET /scoutro/api/v1/kg/sources/<id>?limit=1` shows `source.collections` =
     ["checkthecoach-web"].
     - Repeat until all 18 agree or a timeout of 30 minutes; with the graph paused before, after the resume.
     - Do not wait for `sync.lag.pending` to reach 0: other work keeps it above 0.
     - `processed_at` is newer than in step 0 in both cases.
     - Without PR #36 in the release: `total` (the page's evidence) stays as in step 0, because the page is only
       re-scoped.
     - With PR #36: `total` may differ, because tiers 1 and 2 were extracted again with the coaching vocabulary; the
       tier 3 (LLM) evidence is kept as it was.
   - **visibility:** `GET /scoutro/api/v1/kg/hosts/yowea.com/entities?collection=checkthecoach-web` lists the
     domain's organisation; `…&collection=stackfinder-web` lists none of the domain's entities.
   - **controls:** the control domains' IDs have the same `collections` in the index and the same
     `source.collections` in the graph as in step 0.
   - **nothing more to move:** a new preview answers `documents: 0`.
8. **Resume Discovery:** `scoutro-discovery --workdir <state root> resume`, then `POST /scoutro/api/v1/discovery/resume`
   with `{}` and `If-Match: <revision>` of a fresh status. Optionally run `scoutro-discovery classify --profile stackfinder`;
   with the new `criteria_version` it re-classifies the old StackFinder results (verdicts only).
9. **Rollback** (only if a check fails):
   - the same request with `add`/`remove` swapped (preview, then apply);
   - `scoutro-discovery include --profile stackfinder --domain yowea.com`;
   - put the copied runtime config files back.
   The graph backup of step 0 is only for a damaged graph, not for this.
