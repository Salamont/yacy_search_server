# Scoutro 0.10.1 release candidate

Prepared versions: **0.10.1** and **1.942-scoutro.25**, intended aliases of the
same immutable registry image. The numeric YaCy peer version remains **1.942**.
Basis: main `662fa1b56e474678f8be6353f3b5af70f3f2808b` (merge of #58: LLM coverage, HTML parser, collections
named by the operator; on main `6d63742e5f0ea0582242ad5d14f7ffc9e246810e`, the
merge of integration #56: Discovery recovery #54, collection management #55,
knowledge-graph process display). Previous release, installed
privately on Olares: 0.10.0 / 1.942-scoutro.24,
`ghcr.io/salamont/scoutro@sha256:3b7061cfdcb6d9fec14632cde3152769a7db31c20eb4d90f4d3973eb7ebed6df`.

User-facing changes: [CHANGELOG](../CHANGELOG.md). Discovery recovery contract:
[SCOUTRO_DISCOVERY_AUTOMATION_V1.md §16.1](SCOUTRO_DISCOVERY_AUTOMATION_V1.md#161-deferring-an-unconfirmed-start-administrator-decision).
LLM tier and collections: [SCOUTRO_KNOWLEDGE_GRAPH.md §27–28](SCOUTRO_KNOWLEDGE_GRAPH.md).
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
- **AI enrichment covers whole pages.** No queue item is dropped while its
  page is still to do; every LLM collection has its own resumable scan with an
  equal share. Every request is planned against the model's context window
  with an answer reserve. A long page reads its content before recurring
  navigation and is read to the end of its content in bounded passes (at most
  5 requests each, low priority, the layout of its first reading, a small
  progress row of positions only); a failed pass is tried again after one and
  two hours. Pages read before this version are audited, 20 per scan pass, and
  a long one is read on from where it stopped, never from its start. A page
  whose text is missing (little text of its own in a large HTML, or a page
  asking for JavaScript) is marked `text_missing` and counts as not covered.
  Self-references, claims about dropped entities and duplicate known entities
  are handled without loosening the quote check, the person rules or the
  validator; the prompt is unchanged. Up to 500 refusal samples without person
  names or contact data are kept in memory (7 days, 128 KiB).
- **HTML parser.** A stray quote or `<`, script or style code with `<` or
  quotes, `&lt;`/`&gt;` in text and a few stray control characters no longer
  cut or drop the indexed text of a page. Applies to pages parsed after the
  upgrade (a recrawl); the index is not rewritten.
- **Collections named by the operator.** The shipped defaults name no
  collection; a vocabulary and its facility kinds are a profile the operator
  gives a collection. An existing installation keeps its former defaults
  through a one-time migration (see Upgrade).
- **Status API (additive).** `GET /scoutro/api/v1/kg/status` adds
  `llm.documents.pending` (pages of the LLM collections not examined yet, the
  queue included), `llm.processed.requestsFinished` and
  `llm.processed.averageRequestMillis` (measured time per ended model request;
  null before one ended), and the blocks `llm.selection`, `llm.context`,
  `llm.continuation`, `llm.documents.coverage`, `llm.refusalSamples`,
  `llm.storage` and `llm.backlog`. New: `GET /scoutro/api/v1/kg/llm-refusals`
  (administrator). Existing fields keep their meaning.

## Upgrade from 0.10.0

What happens on the first start of the new image with existing DATA:

- **One-time collection migration, no re-extraction.** Knowledge-graph schema
  stays **6**; no extractor identity change, re-extraction, rebuild or reset.
  The first start writes the collection defaults earlier versions shipped into
  `DATA/SCOUTRO/knowledge/vocabulary/000-migrated-collections.json` (without
  an entry an operator file already maps) and records
  `scoutro.kg.migration.collectionProfiles` in `yacy.conf`; the event
  `collection_profiles_migrated` says *extractor identity unchanged*. An event
  `collection_profiles_identity_changed` means the pages would be extracted
  again: stop and report (the runbook checks this on an isolated copy of the
  backup before production).
- **LLM tier rows.** Small `kg_meta` rows: the scan position per LLM
  collection (`llm_scan:*`), the progress of a long page still being read
  (`llm_layout:*`, positions and a hash prefix, no text; removed when the page
  is done) and the audit position (`llm_audit_at`, `llm_audit_end`). After the
  upgrade the audit finds the long pages read before and the tier reads on at
  low priority, at most 5 requests per page and pass; the work shows as
  `llm.continuation.remainingChars` and in the AI step of the overview. The
  refusal samples are kept in memory only.
- **Discovery.** The Discovery job store keeps
  `schema_version` 1. New fields (`deferred` attempts with `decision`,
  `runtime.held_attempts`, `deferred_count` in the history) are written only
  when an administrator defers a start. The worker state gets
  `automation_attempt.state = deferred_unknown` only then. `collections.json`
  changes only when an administrator removes a collection.
- **Settings.** No default changes. The only key the application adds to
  `yacy.conf` is the migration marker above. Two new settings are optional and
  are not written: `scoutro.kg.llm.answerReserveTokens` (1,200 tokens kept for
  the answer) and `scoutro.kg.llm.continuationChunks` (5 requests per pass of
  a long page; 0 turns continuations off). `num_ctx` is sent only where
  `ai.service_num_ctx` is configured; leave it empty for the knowledge host
  (the window Ollama reports for the loaded model is read from `/api/ps`, not
  sent). Do not change the context, parallelism or GPU configuration on account
  of the upgrade. Access mode, login page, cookie (`scoutro.auth.cookie.secure=always`
  as set by the operator for Olares), sign-in limits (`proxy:127.0.0.1` shared
  bucket), heap (`javastart_Xmx`), proxy trust, `adminAccountForLocalhost`,
  DHT and `scoutro.kg.gate.maxLoad` are read from the existing `yacy.conf` and
  stay as they are. Do not reset or re-seed `yacy.conf`.
- **Rights unchanged.** Deferring a start and removing a collection need the
  administrator; accounts (Research, Operator) get `403` and do not see the
  buttons. The process display shows nothing an account could not see before.
  The refusal samples are for the administrator only.
- **A Discovery batch blocked by an unconfirmed start** (as reported for one
  production job) stays blocked
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
- **Knowledge graph:** 0.10.0 ignores the `kg_meta` rows of 0.10.1 and the
  migration marker. **Keep** `vocabulary/000-migrated-collections.json`:
  0.10.0 reads the same collection map from it and keeps its extractor
  identity, and the next 0.10.1 start needs it (its marker is already set).
  Long pages marked `more_text`, `full_text` or `text_missing` stay done under
  0.10.0 and are not read on; `failed` pages (`context_budget`,
  `input_unconfirmed`) can be retried there. The refusal samples are gone with
  the process. New crawls are parsed by the 0.10.0 HTML parser again.
- The 0.10.0 rollback notes towards 0.9.1 still apply to a further step back.

## Acceptance (private Olares instance)

Run after the upgrade of the existing app `yacysearch` (namespace
`yacysearch-salamont`), read-only unless a step says otherwise:

1. Image: running pod uses the pinned 0.10.1 digest; health/version
   `1.942-scoutro.25`, revision = the merge commit of the release PR.
2. Preserved values: `Xmx3072m`, loopback trust, `adminAccountForLocalhost=false`,
   DHT off, `maxLoad=8`, compatible mode, `scoutro.auth.cookie.secure=always`;
   the only new key is the migration marker; schema 6, epoch and document
   count unchanged; no rebuild or re-extraction.
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
7. Collections: event `collection_profiles_migrated` (*extractor identity
   unchanged*), `sync.reconcile.reextract=false`; the four production
   collections keep their vocabularies.
8. LLM coverage (read only): context window detected (32,768) with
   `ai.service_num_ctx` of the knowledge host empty; the audit advances 20
   pages per scan pass to `done`; coverage, remaining characters, storage and
   refusal samples within their limits; samples carry no person name or contact
   data.
9. After 24 hours, bounded and read only: refusal reasons per 100 accepted
   answers against the counters saved before the upgrade, ten pages chosen by
   the owner checked on their source view (right, wrong, missing), long-page
   progress, requests per hour, backlog and storage. Quality is judged by the
   pages, not by successful requests.

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
