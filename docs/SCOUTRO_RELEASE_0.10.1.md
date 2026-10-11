# Scoutro 0.10.1 release candidate

Prepared versions: **0.10.1** and **1.942-scoutro.25**, intended aliases of the
same immutable registry image. The numeric YaCy peer version remains **1.942**.
Basis: main `6d63742e5f0ea0582242ad5d14f7ffc9e246810e` (merge of integration
#56: Discovery recovery #54, collection management #55, knowledge-graph process
display; tree of the reviewed head `72b4b9f`). Previous release, installed
privately on Olares: 0.10.0 / 1.942-scoutro.24,
`ghcr.io/salamont/scoutro@sha256:3b7061cfdcb6d9fec14632cde3152769a7db31c20eb4d90f4d3973eb7ebed6df`.

User-facing changes: [CHANGELOG](../CHANGELOG.md). Discovery recovery contract:
[SCOUTRO_DISCOVERY_AUTOMATION_V1.md §16.1](SCOUTRO_DISCOVERY_AUTOMATION_V1.md#161-deferring-an-unconfirmed-start-administrator-decision).
The access, cookie and sign-in limit contract of 0.10.0 is unchanged:
[SCOUTRO_RELEASE_0.10.0.md](SCOUTRO_RELEASE_0.10.0.md).

## What changes

- **Discovery recovery.** An administrator can defer an unconfirmed crawl start
  that holds a Discovery batch in `needs_review` (`submitted_unknown`, no crawl
  profile with its marker): Discovery page → *Unconfirmed crawl start* →
  **Defer start (outcome stays unknown)**, or
  `POST /scoutro/api/v1/discovery/attempts/{id}/defer` with `If-Match` and the
  `run_id`. The crawl profiles are read again first (a marker that turns up
  refuses the deferral and is accepted by the next reconcile). The candidate is
  held in the worker state first (`deferred_unknown`), then the attempt is
  recorded as `deferred` with its decision; the outcome stays unknown and is
  never turned into `not_submitted`. Nothing starts the website again by itself:
  not the backlog, retry, recrawl or `--force`. Accepted crawls of the batch
  stay, the batch completes and the other jobs continue.
- **Collections on the dashboard.** The administrator adds (**+**) and removes
  (**−**) collections next to *Scoutro Collections*. Removal deletes only the
  entry in `DATA/SCOUTRO/collections.json`, and only for a collection created in
  Scoutro that holds no page (counted anew in the index), comes from no
  Discovery profile and is used by no account, agent, guest access or running
  crawl. No page, crawl, knowledge-graph setting or other data is deleted. The
  pages are counted exactly right before the removal, and an unreadable account
  file refuses it (`503 collection_uses_unavailable`). The shared *New
  collection* dialog has no form any more (all pages that offer it).
- **Knowledge-graph process display.** The overview starts with five steps
  (web pages → rule processing | AI enrichment → knowledge store → customer and
  partner suggestions), each with state, open work, waiting reason and required
  action; counters carry unit and time basis; no percentages or remaining times.
  The previous status lists stay under *Technical diagnosis*. The page only
  reads the status. *Recompute suggestions now* moved to *Controls*. The AI step
  counts the pages not yet examined apart from its small queue; a storage-guard
  reason means the same on every step; pages are shown per collection.
- **Status API (additive).** `GET /scoutro/api/v1/kg/status` adds
  `llm.documents.pending` (pages of the LLM collections not examined yet, the
  queue included), `llm.processed.requestsFinished` and
  `llm.processed.averageRequestMillis` (measured time per ended model request;
  null before one ended). Existing fields keep their meaning.

## Upgrade from 0.10.0

What happens on the first start of the new image with existing DATA:

- **No migration.** Knowledge-graph schema stays **6**; no extractor identity
  change, re-extraction, rebuild or reset. The Discovery job store keeps
  `schema_version` 1. New fields (`deferred` attempts with `decision`,
  `runtime.held_attempts`, `deferred_count` in the history) are written only
  when an administrator defers a start. The worker state gets
  `automation_attempt.state = deferred_unknown` only then. `collections.json`
  changes only when an administrator removes a collection.
- **No new setting.** The release adds no configuration key and changes no
  default. Access mode, login page, cookie (`scoutro.auth.cookie.secure=always`
  as set by the operator for Olares), sign-in limits (`proxy:127.0.0.1` shared
  bucket), heap (`javastart_Xmx`), proxy trust, `adminAccountForLocalhost`,
  DHT and `scoutro.kg.gate.maxLoad` are read from the existing `yacy.conf` and
  stay as they are. Do not reset or re-seed `yacy.conf`.
- **Rights unchanged.** Deferring a start and removing a collection need the
  administrator; accounts (Research, Operator) get `403` and do not see the
  buttons. The process display shows nothing an account could not see before.
- **A Discovery batch blocked by an unconfirmed start** (as reported on
  production for *Prod StackFinder Sued-West*, run `83b72b62…`) stays blocked
  after the upgrade until an administrator decides. Follow the read-only check
  and the decision steps of the OpenCode runbook; never edit
  `scoutro-discovery-jobs.json`, the worker `state.json` or crawl profiles by
  hand, and never restart the unconfirmed website.

## Rollback to 0.10.0

The primary return point is a current, consistent full DATA/configuration
backup with checksums and an isolated restore, plus the 0.10.0 digest above.
No lossless downgrade is claimed. Keep newer DATA separately when restoring an
older backup.

Starting the 0.10.0 image on DATA written by 0.10.1 (code facts, not a tested
downgrade):

- **Open batch with a deferred start:** 0.10.0 refuses the whole job store
  (`Invalid discovery jobstore; original left intact`), so Discovery does not
  start; the file is not changed. Let the batch complete under 0.10.1 first
  (no open `active_run`, or none containing a `deferred` attempt). After that,
  0.10.0 reads the store: it ignores `runtime.held_attempts` and the history
  fields.
- **Held candidates:** 0.10.0 does not honour `deferred_unknown`; a deferred
  website can be selected again. Pause the affected job before or right after
  the rollback and keep it paused until the website is handled deliberately.
- **Removed collections** stay removed (0.10.0 reads the shorter list);
  re-create them with the same ID if needed. Their pages were never deleted.
- The process display and the collection buttons disappear; nothing else in
  the knowledge graph changes.
- The 0.10.0 rollback notes towards 0.9.1 still apply to a further step back.

## Acceptance (private Olares instance)

Run after the upgrade of the existing app `yacysearch` (namespace
`yacysearch-salamont`), read-only unless a step says otherwise:

1. Image: running pod uses the pinned 0.10.1 digest; health/version
   `1.942-scoutro.25`, revision = the merge commit of the release PR.
2. Preserved values: `Xmx3072m`, loopback trust, `adminAccountForLocalhost=false`,
   DHT off, `maxLoad=8`, compatible mode, `scoutro.auth.cookie.secure=always`;
   schema 6, epoch and document count unchanged; no rebuild or re-extraction.
3. Knowledge graph → Overview: five steps, *Technical diagnosis* folded and
   complete, refresh sends only GET requests (browser network panel), numbers
   match the technical diagnosis of the same read time.
4. Dashboard as administrator: **+**/**−** visible; the four production
   collections appear under *Not removable* with their page counts. **Do not
   remove a production collection**; a removal test belongs to a throw-away
   collection created for the test (create → remove → gone; no page changed).
5. Discovery: status readable; if a batch is in `needs_review` with an
   unconfirmed start, follow the runbook decision steps; otherwise record that
   no decision is needed.
6. Sign-in/sign-out and cookie attributes as accepted for 0.10.0.

## Candidate validation and later publication

The release PR records the exact tested commit, the locally built linux/amd64
image configuration ID, checks and limitations. A local configuration ID is not
a registry digest. Skipped fork CI is not a passed build.

**A later authorized merge** of the release PR changes `scoutro.properties` and
the workflow and therefore triggers the main `Publish Scoutro image` workflow.
It first refuses existing tags (only a genuine `404 / MANIFEST_UNKNOWN` counts
as free), then builds and pushes `1.942-scoutro.25` and `0.10.1` with the actual
merge revision. Do not dispatch it in addition. Before the merge, re-fetch
main/head and recheck both tag names. After publishing, verify one immutable
digest for both tags, linux/amd64 and the merge revision label; the Olares
package pins that digest, never a local image ID.
