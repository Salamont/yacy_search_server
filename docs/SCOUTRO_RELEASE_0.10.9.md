# Scoutro 0.10.9 release candidate

Prepared versions: **0.10.9 / 1.942-scoutro.33**. Numeric YaCy peer version
**1.942** stays unchanged. **Not published, no registry digest, no rollout.**
Application [PR #73](https://github.com/Salamont/yacy_search_server/pull/73)
was merged under separate explicit authorization on 15 October 2026, with
merge commit `7263ac5ca9154dd9800456644429de508a6fa3dc`. Release
[PR #74](https://github.com/Salamont/yacy_search_server/pull/74), branch
`release/scoutro-0.10.9`, now targets `main` at that commit. Main was merged
into the release branch without changing its file tree; the resulting
candidate was rebuilt and its focused regressions rerun. **#74 is not merged;
its release merge/publication still requires a separate decision.**

## Checked baseline and version availability

14 October 2026: main `da72b55867f9f11f663cad8aa41b1097596405a3` contains
merged #71 (quality evaluation/diagnosis) and #72 (0.10.8/.32). Published
0.10.8 does not contain #73. The reviewed fix head was
`7ba5fcb6c4faa77007861feb5b4a64a3ce713b82`; documentation-only successor
`432a2ad026304a4bc7aa55c26c00eeb02d115c2b` explicitly records the deferred
price finding. Source and application tests are identical to the reviewed
fix. No additional semantic fix or refactoring is in this release delta.

Current main after #73 is `7263ac5ca9154dd9800456644429de508a6fa3dc`.
Its tree equals reviewed #73 head `432a2ad02`; source and Java tests also
equal the reviewed `7ba5fcb6c` tree. The release diff against main contains
only the prepared version/release files, release-test tooling and evidence.

At **2026-10-14 19:46:25 UTC**, both GHCR aliases `0.10.9` and
`1.942-scoutro.33` answered **404 / MANIFEST_UNKNOWN**. Matching Git tags and
GitHub release entries were absent; the repository PR search found no
separate release using these versions. This is a time-specific check, not a
reservation. Repeat immediately before publishing. The main workflow retains
its refusal to overwrite either existing tag.

Repeated at **2026-10-15 06:03:13 UTC**: no matching remote Git tags or
GitHub release entries, both registry aliases still **404 / MANIFEST_UNKNOWN**.
Availability is not a publication authorization or reservation.

Existing package stack, unchanged:

| Package PR | Head | Dependency |
| --- | --- | --- |
| [#9, 0.10.7](https://github.com/Salamont/YaCy-Search-Community/pull/9) | `55f01e296c431b91919e61fd1eb2595b2ac95c43` | Earlier package base |
| [#10, 0.10.8](https://github.com/Salamont/YaCy-Search-Community/pull/10) | `f165c538fd08f1df5851998be77f1f0402608c72` | Stacked on #9 |

## Change against 0.10.8

Integrates #73's existing common service-evidence correction:

- New LLM service names need independent, bounded literal offer evidence.
  Navigation/boilerplate and offer words inside the name do not type it.
- Offering verbs and explicit offer headings/lists check provider binding.
  Referring to another company's offer list does not establish an operator
  offer. Heading/item negation remains effective for shortened quotes through
  their original source context.
- Accepted old cache subsets pass the same publication preparation. Rejected
  new entities have no reference, and their dependent claims/values are
  omitted. Existing lower-tier service types remain; unsupported tier-3
  `offers` for those services still fail. Genuine lists/content offers stay.
- Existing bounded, redacted refusal diagnostics and item indices remain.
  `claim_offer_not_stated` identifies an unsupported provider relation. No
  general production raw-answer archive is added.

**Open and explicitly deferred: standalone price/provider branch.** A literal
passage with a named service price and co-occurring provider name can still
pass that branch without binding the provider to the priced offer. The
additional proposed price/provider fix is not included or claimed resolved.
No further price-evidence or provider-check change is made. See
[the known limitation](SCOUTRO_SERVICE_OFFER_EVIDENCE.md#deferred-provider-binding-in-the-standalone-price-branch).

Schema **6**, JSON-LD **5**, rules **6**, relation rules **5**, LLM extractor
**llm/4**, prompt/schema/hash, cache keys and all settings stay. Evaluation
**3**, diagnosis **1** and #71's fixed baseline/attribution/key scrubbing stay.
The service validator fingerprint differs from 0.10.8; older quality results
can be shown stale for `validator`. Their stored bytes are not rewritten.

## Upgrade contract

From 0.10.8, or from 0.10.7 after applying its already documented release
contracts:

1. No migration, version-triggered re-examination, automatic quality check,
   model call or crawl is introduced by this release. No prompt/extractor or
   model-setting switch. Preserve the sync/LLM queues, cache and configuration.
2. **Existing graph rows are not retroactively cleaned or corrected.** The
   new checks apply when fresh or cached results are normally prepared. This
   release does not schedule their reprocessing or deletion.
3. Stored quality files, including `archive/`, keep their bytes. A stale
   validator marker does not authorize a new quality run. No new archive or
   result is created just by upgrading.
4. Before an eventual upgrade: a consistent full DATA/configuration backup,
   ownership/modes, per-file checksums and isolated restore with the exact
   currently installed image. Include Solr, graph, queues/cache and quality
   results. A KG-only backup is insufficient. Verify storage/inode reserves.
5. Use the existing Olares app only, retaining auth/entrances, UID/GID,
   volumes, resources and effective configuration. No configuration change or
   graph repair is part of this upgrade.

## Return point

Identify the **actual installed source version** before choosing a return
image. Package #10 records 0.10.8's image as
`ghcr.io/salamont/scoutro:1.942-scoutro.32@sha256:ca4e87bbaa93122f5d16e34d7f07e83489b331db00b773e7f5f0537e7108e372`
(revision `da72b5586`); package #9 records 0.10.7/.31 as
`sha256:dd4d6081e9530b2d4f5d01f707f1d6d79a02a22c1805c769d2446391d42f36a9`
(revision `efdab25`). These records do not establish what is installed now.
Existing aliases/digests are never repointed.

The return requires a separate operator decision: stop writers, retain newer
DATA separately, and use the verified pre-upgrade full backup and exact return
image. With schema 6, a same-DATA return may be assessed on an isolated copy,
but no lossless downgrade is claimed. Older validation can again admit the
rejected service/offer cases; returning does not clean rows created earlier.
Do not start quality checks or delete stored results during the return.

## Acceptance for the future package and OpenCode

All graph/LLM failure demonstrations below run **only on synthetic fixtures
with a mock endpoint**. Production acceptance is read-only and must not invoke
a crawl, model or quality run.

| Gate | Required evidence |
| --- | --- |
| New image identity | Published immutable manifest; both new aliases identical; exact authorized release merge revision; OCI version/revision/source/licenses; linux/amd64, runtime user and application paths |
| Package | Unique next package version after the image exists, both Scoutro containers digest-pinned, reviewed package head, member hashes/content fingerprint, Helm/Olares lint and render diff |
| Service regression | Both original review cases rejected; real own lists/content retained; correct subjects/objects; no names/dependents on rejected new entities; shortened quotes retain context |
| Cache | Old accepted subsets used with a cache hit, zero model calls, unchanged result bytes and guarded publication; no cache/graph cleanup |
| Isolated upgrade/restart/return | Exact old/new published images and fresh synthetic DATA; schema/epoch, prompt/hash/extractor identities/settings kept; no scheduled re-extraction or quality run; existing statements and quality-file hashes kept |
| Backup/restore | Consistent full source-version backup with per-file checksums; restore with the verified return image; then network-disabled upgrade dry run on a copy |
| Existing-app rollout | Separate owner authorization and complete version-specific runbook, exact package head/new digest/return point, staged read-only checks and unchanged configuration |

The old package `docs/OPENCODE-OLARES.md` at #10's head applies only to
0.10.7 → 0.10.8. It cannot deploy or attest #73. The next handoff must supply
all instructions as text or exact repository files; no attachment is assumed.
See [the complete preparation handoff](OPENCODE_SCOUTRO_0.10.9_HANDOFF.md) and
[the service/package contract](SCOUTRO_SERVICE_OFFER_RELEASE_HANDOFF.md).

## Validation and publication status

Historical evidence at #73 head `7ba5fcb6c`: **38/38** offer regressions,
**108** focused tests, **1233** separately compiled Scoutro tests and successful
build; original review baseline `7ef1aa4` had **15/38** red. The general
`ant compileTest` still had **15 existing errors**: one outdated `CrawlProfile`
constructor in `HostBalancerTest`, fourteen missing `NetworkGraph` cache
method references in `NetworkGraphTest`. This is not a fully green general
repository test run. Details and earlier checks:
[service evidence validation](SCOUTRO_SERVICE_OFFER_EVIDENCE.md).

Earlier release checks on 14 October, candidate `bb57c86a4`, passed: clean Ant build, 60 Jetty
and 108 focused tests, 198 UI/65 live quality checks with a fake model, 4 Solr
bridge tests and dependency guard, 58 original attribution files verified in
distribution and actual exported local image, and 21 image assertions with
network=none and package UID/GID 1000 on fresh synthetic DATA. General
compileTest again failed with the same 15 existing errors. The full 1233-test
Scoutro run remains historical, not newly run for this release. Details,
commands and identifiers are in the
[handoff](OPENCODE_SCOUTRO_0.10.9_HANDOFF.md) and
[`validation-0.10.9.json`](validation-0.10.9.json).

**New post-merge checks on 15 October**, candidate
`f9e6480963e3546d3b7de40c3a395fb64ea03601`: `ant clean all dist
scoutro-kg-offer-evidence-test` succeeded, including 18 relocation packaging
checks, **60 Jetty tests and 108 focused tests (38/38 offer regressions)**.
Main was included without any file change from `74870648f`; subsequent
updates only record the merge, checks and handoff. The final documentation
distribution is rebuilt separately and identified in #74's description.
General `compileTest` was not rerun: its 15 existing compilation errors above
remain a separately reported limitation. The 1233-test suite, UI/live, bridge
and image smoke checks were not repeated in this step. Package #9/#10/#11
remain unchanged; no new package lint/runtime acceptance is claimed.

Historical checks are not relabelled as rerun. A local container configuration
ID is not a published digest, and a source/JUnit check is not packaged-image
acceptance. No registry image, package fingerprint or production acceptance
for 0.10.9 exists at this preparation stage.

Only a **later authorized release merge into main** may trigger the existing
publication workflow once, with `.33`/`0.10.9` and the actual merge revision.
No extra manual dispatch. Capture the successful run, verify immutable aliases
and pulled OCI labels, then prepare and test the package. Only #73's merge was
authorized and performed in this step; no #74 merge, image publication or
rollout was performed.
