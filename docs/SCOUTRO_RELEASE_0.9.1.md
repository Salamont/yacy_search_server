# Scoutro 0.9.1 patch release candidate

Prepared versions: **0.9.1** and **1.942-scoutro.23**, intended aliases of the
same immutable registry image. Numeric YaCy peer version remains **1.942**.
Basis: `8812ad2f901ede7ebe6ea27764d30889c8a7b866`, the released 0.9.0 main.
Preparation remains in Draft PR #45; no merge, publishing or deployment.

## Fix and evidence

A recursive regex in business-signal extraction can raise `StackOverflowError`
on long text/JSON-LD paragraphs, before checking the allowed quotation length.
The escaping error ends the periodic sync task while its executor stays alive.
Against the unchanged 0.9.0 image a controlled fixture reproduces
`scanned=10000`, `published=486`, `extractions=487`. This proves the code defect
and its failure path; the actual production trigger remains unconfirmed.

The replacement scanner is linear and preserves quote limits, date/version
dots, locators and the existing newline contract. Unpersisted dirty events
are restored after a failed write. The runtime retains the scheduled Future,
reports task failure and logs the escaping exception. Session-bound state
prevents late completion/failure after close from changing the replacement
session's diagnostics. Queue, claims and cursor are not reset by this handler.

Administrator status and the localized overview distinguish an executing,
scheduled, cancelled, stopped or failed task. Execution timestamps/tick counts
are not document progress: compare queue, processed counters and the reconcile
scan/cursor. Existing Solr/storage retry/backoff remains. No automatic recovery
of unexpected task errors, particularly fatal JVM errors, is introduced.
Inspect the full log before a controlled restart. No watchdog revives a closed
task; the existing bounded close wait does not guarantee that an in-flight
operation has already returned.

## Upgrade and rollback

- From 0.9.0, KG schema remains **6**. This patch changes no schema,
  extraction/cache identity, load threshold or matching rule and causes no
  version-driven mass re-extraction. Existing pending work can continue after
  a controlled restart; completed documents are not intentionally re-evaluated.
- Preserve a complete, consistent DATA/configuration backup, external mounts
  and the compatible previous image. Check hashes and an isolated restore;
  keep adequate space for the copied dataset, WAL and durable archive growth.
  A KG-only backup does not replace the full backup.
- From pre-0.9.0 versions, the existing protected **4 → 5 → 6** migration
  contract still applies: protection and retained-evidence migration precede
  new extraction. See [0.9.0 upgrade notes](SCOUTRO_RELEASE_0.9.0.md).
- No lossless downgrade is claimed. Use an explicitly chosen compatible full
  backup and old image, retaining the newer archive separately. Older versions
  can ignore the LLM schedule and repeat unfinished chunks. Returning to 0.9.0
  also restores the known sync/parser defect. No production settings are
  enabled, changed or cleared by release preparation.

## Candidate validation and later publication

The current PR and its patch-release acceptance report record the exact tested
commit, locally built linux/amd64 image configuration ID, checks and limitations.
The candidate image is built with the actual two-stage Dockerfile and runs its
own application JARs: no mounted fix classes. Test fixtures may be mounted
separately read-only. Temporary DATA and controlled local sources/model fixtures
do not access production. A local configuration ID is not a registry digest or
proof about a future published image. Skipped fork CI is not a passed build.

The reproducible initial investigation is preserved in
[SCOUTRO_KG_SYNC_VALIDATION.md](SCOUTRO_KG_SYNC_VALIDATION.md). The final
patch-release report is linked from
[Draft PR #45](https://github.com/Salamont/yacy_search_server/pull/45), so its
tested commit/image evidence can be recorded without changing that candidate.

**A later authorized merge** touching these release files triggers the existing
main `Publish Scoutro image` workflow, which first refuses existing tags and then
builds/pushes both `0.9.1` and `1.942-scoutro.23` with the actual merge revision.
Only a genuine `404 / MANIFEST_UNKNOWN` means an available tag. Network,
authentication and other errors remain failures. This preparation does not
dispatch that workflow or create tags/releases/pushes.

Before that merge, re-fetch main/head and recheck both tag names. After publishing,
verify identical immutable digests, linux/amd64 and the actual merge revision;
pull and isolate-test that published digest. Olares packaging and rollout remain
separate later work based on that verified registry digest, never this local
image ID. No Community Market publication.
