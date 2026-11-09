# Scoutro 0.14.2: facilities, stage 1 (addresses, facility pages and identities)

Prepared versions: **0.14.2 / 1.942-scoutro.51**. Numeric YaCy peer version **1.942** stays unchanged. Knowledge graph schema 9
unchanged; one re-extraction of tiers 1 and 2 (`rule/12`) and one replay of the LLM tier's stored answers (`llm/8`) without a
model request. The update merges entities: **a backup before the update is mandatory** and is the only way back.

| What | Value |
|---|---|
| Release | facilities, stage 1 of pull request #102: addresses, facility pages, the imprint and the operator, the LLM tier's places, identities and the change feed of merges (`docs/SCOUTRO_KG_FACILITIES.md`) |
| Application code | exactly pull request #102 at **`ecdf0ff32e14e64fda890e3d9f7741b176abffe5`** (validated: five full before/after runs on archived production data, the fifth on this commit; GitHub "Scoutro checks" success) |
| Release commit | the single commit on `ecdf0ff` of the release branch (`ccr-6ef669bd-u4ne4p`, draft pull request stacked on #102): version metadata, these notes, the changelog and the publication workflow only, no source, resource or test change (`git diff --stat ecdf0ff <release commit>`) |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.51`, alias `0.14.2`, linux/amd64, published by a manual dispatch of `publish-scoutro.yml` on the release branch **before** the merge (the owner's assignment of 9 November 2026); revision label = the release commit |
| Registry digest | recorded in the private package repository (`docs/RELEASE-0.14.2.md` of `Salamont/YaCy-Search-Community`), which pins it |
| Knowledge graph schema | **9, unchanged**; no automatic copy before the upgrade (that copy is made only for a schema migration) |
| Extractors | `rule/12` (was 10), `llm/8` (was 6; `REPLAYS` = {4, 5, 6, 7}); `jsonld/8`, `metadata/1`, `Vocabulary.VERSION` 6, vocabulary package 8 unchanged; `BusinessSignals.VERSION` 7 and `MatchingRules.VERSION` 1 unchanged |
| Upgrade from | 0.14.1 / `1.942-scoutro.50` (`sha256:76075e6386c0e19ba43774bee133d570b9689d8f5d70d104bacfc38697463e32`), installed in production |

## What the update does

1. At the first start the graph sees a new extractor identity (`rule/12`): a reconcile with reason `extractor_changed` and the
   re-extraction of tiers 1 and 2 of every tracked page at low priority (`reextract_pending`; about 3.7 hours in the validation,
   243 138 pages). It starts **at once**: schema 9 is unchanged, so no copy before the upgrade is made and nothing holds it.
2. The LLM tier's stored answers of `llm/6` are replayed under `llm/8` from their evidence, page by page, without a model
   request (`llm_validation_queued`, `llm.validation.state` `pending`, then `llm_validation_done` and `llm_validation` `8`):
   about 9 hours on one core in the validation. `llm_extractor_changed` must not appear.
3. Entities are merged where the new readings give the same identity (in the validation 8 488 redirects, 2 948 of them
   organisations, none of two different companies) and statements without evidence are deleted. **Merges are not undone by a
   later version**: only the backup restored in 0.14.1 undoes them.
4. Growth in the validation: graph 6.19 GB → 7.85 GB (+1.66 GB). With the backup before the update (≈ 6.2 GB) the knowledge
   graph's directory needs about 14 GB plus WAL and temporary files during the work; the production budget of 25 GiB includes
   the backups.
5. Agents limited to collections (Hermes) receive the merges as `redirect`s (M7); after the one-time work they run a **full
   synchronisation** (the epoch is unchanged; cursors stay valid, but the volume of changes is that of a re-extraction).

## Backups and `scoutro.kg.backup.keep`

With `scoutro.kg.backup.keep=1` (the default) the backup before the update removes every older regular backup and every safety
copy older than it from `DATA/SCOUTRO/knowledge/backup`. To keep them, preserve them **before** the backup outside that folder
(the production runbook of the package repository uses hard links on the same volume, which cost no space until the app removes
its copy, plus a downloaded copy). `keep` itself need not change.

## Rollback to 0.14.1

Only with the backup taken before the update. 0.14.1 on the updated graph would re-extract every page with `rule/10` and examine
every document again **with model requests** (`llm/8` is a version outside its own), and it keeps the merges. So:

1. a small `scoutro.kg.llm.maxRequestsPerDay` (and, if the room of a restore needs it, a larger `scoutro.kg.budget.maxBytes`)
   set before 0.14.1 starts, the old values noted;
2. 0.14.1 with `ghcr.io/salamont/scoutro:1.942-scoutro.50@sha256:76075e6386c0e19ba43774bee133d570b9689d8f5d70d104bacfc38697463e32`;
3. in 0.14.1 (never in 0.14.2) `POST /scoutro/api/v1/kg/control` `{"action":"restore","backup":"<file>"}`: the restored graph
   carries `rule/10` and `llm/6`, so neither a re-extraction nor an examination remains; the pages changed since the backup are
   read again by the reconcile; the cap is set back afterwards.

A restore keeps the updated graph as `graph-<UTC>-before-restore.db` (≈ 7.9 GB) next to the restored backup: check the room first.

## Gates and validation

The gates F1–F6 of `docs/SCOUTRO_KG_FACILITIES.md` section 9 were run on archived production data (a verified Scoutro backup of
5 November 2026 and the matching Solr export) in an isolated environment, five full before/after runs; the fifth on
`ecdf0ff`: care facilities with an address 91 → 1 324; unexplained losses of organisation contacts below one per cent per
predicate; 0 wrong merges of companies; 0 new third-party aliases; Hermes 0 missing or wrong redirects, 0 deletes of visible
entities, 0 leaks. Reports outside the repository (`SCOUTRO_PR102_FINAL_VALIDATION.md`).

## Known limitations

K3, K5 and R1–R9 of `docs/SCOUTRO_KG_FACILITIES.md` sections 10–12 stay, among them: a page of several addresses none of which
the subject's name heads gives it none; operators written in capitals without a recognised legal form stay unnamed; about ten
pre-existing third-party names on imprint pages; `identity_review` only in the event ring (durable mark: proposal, schema 10);
URL variants count two sources; facilities named without an address stay possible duplicates.

## Tests of this release

`tools/scoutro/ci/scoutro-checks.sh all` (clean build, every `scoutro-*-test` target including `scoutro-kg-facilities-test`
with 197 tests, the Python suites), on `ecdf0ff` and on the release commit; the image's smoke and upgrade/rollback tests on
disposable synthetic data are recorded with the package (`docs/RELEASE-0.14.2.md` of the package repository).
