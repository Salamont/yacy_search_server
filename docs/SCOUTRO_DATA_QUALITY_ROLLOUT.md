# Data quality programme R1–R4: merge and rollout order

Status: 2026-10-18, prepared, **not executed**. No merge to `main`, no tag, no image, no Olares change is part of the
programme ([programme](SCOUTRO_DATA_QUALITY_PROGRAM.md)). This page is the order an operator follows once the pull
requests are reviewed.

## Pull requests and their real dependencies

| PR | Branch | Base | Depends on | Schema | Re-extraction | LLM requests |
|---|---|---|---|---|---|---|
| #86 CI | `scoutro/ci-fork-checks` | `main` | – | – | – | – |
| #78 WP-A | `scoutro/r1-wp-a-collection-decisions` | `main` | #86 (merged in) | – | – | – |
| #79 WP-B | `scoutro/r1-wp-b-quality-derived` | `main` | #86 (merged in) | – | – | – |
| #80 WP-C | `scoutro/r1-wp-c-discovery-decisions` | #78 | A | – | – | – |
| #81 WP-D | `scoutro/r2-wp-d-kg-collection-move` | #80 | A, C | – | pages whose vocabulary context a move changes (rules) | none for a move |
| #82 WP-E | `scoutro/r2-wp-e-kg-assessments` | #81 | D | 6 → 7 (`kg_evidence` rebuilt once, tier 4) | – | – |
| #83 WP-F | `scoutro/r3-wp-f-extraction-semantics` | #82 | E | 7 → 8 (price archive) | tiers 1/2 of every page once (rules) | – |
| #84 WP-G | `scoutro/r3-wp-g-tier3-review` | #83 | E, F | – | – | none (rules on stored LLM facts) |
| #85 WP-H | `scoutro/r4-wp-h-autocorrect` | #84 | A, D, E | – | – | none (stored Discovery results) |
| #87 WP-B integration | `scoutro/r4-wp-b-quality-integration` | #85 | B, A–H | – | – | – |

The stack was always brought up by merge commits (never rebased); every branch contains #86. The dependencies are
real: E's assessments need D's re-scoping (a move must not restore withdrawn facts), F's schema 8 follows E's
schema 7, G writes automatic assessments (E) over F's money roles, H applies collection decisions (A) and reads the
graph's text fit (D's vocabulary context), and #87 counts what A, E and F store.

## Merge order (each with "Create a merge commit", never squash or rebase)

1. #86 (CI): afterwards every PR runs `Scoutro checks` against `main` too.
2. #78 (A), then #79 (B; independent, may also come later).
3. Retarget #80 to `main`, merge; then retarget and merge #81, #82, #83, #84, #85 and #87 in this order. Before each
   merge: `Scoutro checks` green on the PR's current head, no conflict, the PR's description up to date.

A squash would rewrite the commits the next PR is based on and make every following PR conflict.

## Releases and rollout (one release per step, each with its own image)

| Release | Contents | What changes on start | Gate before the next |
|---|---|---|---|
| R1 | #86, #78, #79, #80 | collection decisions active (none stored), quality metrics, Discovery deferrals | decisions file readable, metrics baseline taken (`GET /v1/kg/quality?refresh=true`) |
| R2 | #81, #82 | schema 6 → 7 with upgrade copy; `kg_evidence` copied once (about twice its size on disk for the duration) | integrity check, assessments view, the first pilot (drolshagens.de) with D and E only |
| R3 | #83, #84 | schema 7 → 8 with upgrade copy; tiers 1/2 of every page read again once at low priority, no model; the tier-3 review only as a dry run | queue drained, metrics compared to the baseline, dry-run review read |
| R4 | #85, #87 | correction automation stays `off`; identity switches stay off | – |

For every release:

1. A verified full backup (DATA and the knowledge graph) and an isolated restore test with the previous image.
2. Free disk: R2 needs about the size of `kg_evidence` once more; R3 the WAL of the re-extraction.
3. Upgrade the private package to the release's image digest only; keep `scoutro.kg.gate.maxLoad` and the other
   settings.
4. Check: health, schema version, integrity, queue progress over 15 minutes, LLM requests 0 for R2–R4 apart from
   new content, `GET /v1/kg/quality`.
5. Rollback: an older build does not open schema 7 or 8. Restore the upgrade copy (`graph-<UTC>-before-upgrade.db`)
   or the full backup with the previous image; corrections made since are then gone (assessments can be exported via
   `GET /v1/kg/assessments?status=all` first).

## What stays off until an operator decides

- `scoutro.collections.autocorrect.mode` = `off`. Way to `apply`: `simulate` → a random sample of at least 299
  corrections, every drawn case judged → `accept` (99 % target) → `apply` with a small `maxPerDay`; the audit cases
  are reviewed continuously.
- `scoutro.kg.identity.productUrl` / `.providerServiceKey` = `false`. Before switching: `POST /v1/kg/identity-preview`
  for the domains with most services and a verified backup; switching reads every page again once (rules).
- The tier-3 review (`POST /v1/kg/tier3-review` with `apply`) is an operator decision after the dry run.

## Residual risks

- Measured on a synthetic graph of production size (160 000 pages, 602 MiB), not on production data: migration,
  copy, abort, rollback, the R3 re-extraction (1 h 54 min, no model call, observations +85 %), load and the correction
  automation in simulation; see the [acceptance](SCOUTRO_DATA_QUALITY_ACCEPTANCE.md) for the numbers, the estimates for
  production and what synthetic data cannot show.
- The text fit reads at most 8 pages of a domain; a site whose offers are not on its offer pages is judged on less.
- OSM tags are kept by Discovery only for the keys a rule matched; the OSM verdict against a profile is limited to
  overlapping keys.
- The 99 % statement holds for the kinds of cases sampled; the audit is there to see new kinds.

## State at the end of the completion round (2026-10-18)

| PR | Head | GitHub `Scoutro checks` |
|---|---|---|
| #86 CI | `6df505e` | success |
| #78 WP-A | `9243c41` | success |
| #79 WP-B | `fa74865` | success |
| #80 WP-C | `9e7dc79` | success |
| #81 WP-D | `ed785a9` | success |
| #82 WP-E | `5e9817b` | success |
| #83 WP-F | `f960273` | success |
| #84 WP-G | `02f1944` | success |
| #85 WP-H | `b63e088` | success |
| #87 integration | `92d381b` (this document's parent) | success |

- Local, from cleaned build directories: `tools/scoutro/ci/scoutro-checks.sh all` on the integration state `af4b9c6`
  (`92d381b` minus the test fix below): all passed (compile, every `scoutro-*-test` target, nine Python suites).
- Live smokes with disposable peers on the integration branch: knowledge, report, SEO and discovery passed; the
  dashboard smoke found a WP-A regression (hiding a collection after a reassign answered `409 collection_in_use`),
  fixed on #78 (`0f9450e`) and merged up the stack; the dashboard smoke on the integration head `92d381b` passed again
  (384/384 checks, 27 of them hide/show/delete-pages).
- Two CI-only findings fixed on #86 and merged up: the CI log now names failing tests (`42bccf2`), and the unchanged
  `KgSyncSchedulerTest` waited for a backup result instead of the free backup slot (`6df505e`, reproduced with a delay).
- No data migration, crawl, model call or change on Olares was made; nothing is merged to `main`.

## Isolated acceptance (2026-10-18)

The decision paper with the release rating per release, measured resources, migration risks and what was tested
locally, production-like (synthetic, production size) and not at all: [SCOUTRO_DATA_QUALITY_ACCEPTANCE.md](SCOUTRO_DATA_QUALITY_ACCEPTANCE.md).
It found two code errors, both fixed and merged up the stack:

- #83 (`d8b62c0`): the order to re-read every page after new extractor versions now survives a restart before that
  re-extraction completed (`kg_meta.reextract_pending`).
- #82 (`afb609f`): the partial copy of an upgrade killed during its copy is removed at the next start.

| PR | Head after the acceptance | GitHub `Scoutro checks` |
|---|---|---|
| #86 CI, #78, #79, #80, #81 | unchanged (see above) | success |
| #82 WP-E | `afb609f` | success |
| #83 WP-F | `cae2b46` | success |
| #84 WP-G | `ef46d99` | success |
| #85 WP-H | `5835dcf` | success |
| #87 integration | `e865f31` (this document's parent) | success; locally `scoutro-checks.sh all` 23/23 |

The new end-to-end test `DrolshagensPilotTest` (in `scoutro-collection-decisions-test`) runs the pilot on isolated
test data across A, C, D, E, F and H.

Before R3, the operator decides on the disk reserve for the observation growth (estimated several hundred MB in
production) and on letting the re-extraction run for hours in the background behind the load gate.

## Continuation

A follow-up session starts with this prompt:

> Scoutro Datenqualität R1–R4 – Fortsetzung. Lies `docs/SCOUTRO_DATA_QUALITY_ROLLOUT.md` und
> `docs/SCOUTRO_DATA_QUALITY_PROGRAM.md` auf `scoutro/r4-wp-b-quality-integration`, prüfe für #78–#87 den Head, die
> GitHub-Läufe `Scoutro checks` und neue Review-Kommentare. Behebe CI-Fehler und Review-Befunde auf dem jeweiligen
> Branch und führe die Änderung per Merge-Commit den Stapel hinauf bis #87 (keine Rebases, keine fremden Branches).
> Danach: lokale Gesamtprüfung `tools/scoutro/ci/scoutro-checks.sh all` auf #87 und die Live-Smokes mit Wegwerf-Peer.
> Grenzen: keine Merges nach `main`, keine Tags/Images/Deployments, keine Olares-Änderungen, keine produktiven Crawls
> oder LLM-Läufe, `apply` und die Identitätsschalter bleiben aus. Abschluss: aktualisierter Stand je PR und in diesem
> Dokument.
