# OpenCode handoff: Scoutro 0.10.9 preparation

**Preparation and review only. NOT a rollout authorization.** The release is
stacked on unmerged application #73. No 0.10.9 image digest is available, so
the next Olares package is not rollout-ready. Do not infer missing identifiers
or use 0.10.8's digest for the new version.

Read this entire file and `docs/SCOUTRO_RELEASE_0.10.9.md` from the exact
application release-PR head supplied in the final handoff. These repository
files are the full instructions; no attachment or prior chat is needed.

## Verified starting identities

| Repository / item | Identity and dependency |
| --- | --- |
| Application | `Salamont/yacy_search_server` |
| Main | `da72b55867f9f11f663cad8aa41b1097596405a3`; #71 and release #72 merged |
| Service fix #73 | `fix/kg-offer-evidence`, documentation-only head `432a2ad026304a4bc7aa55c26c00eeb02d115c2b`; original validated code `7ba5fcb6c4faa77007861feb5b4a64a3ce713b82` |
| New application release | `release/scoutro-0.10.9`, PR base `fix/kg-offer-evidence`; depends on #73 review/merge; 0.10.9 / 1.942-scoutro.33 |
| Package repository | `Salamont/YaCy-Search-Community` |
| Package #9, 0.10.7 | Head `55f01e296c431b91919e61fd1eb2595b2ac95c43` |
| Package #10, 0.10.8 | Head `f165c538fd08f1df5851998be77f1f0402608c72`, stacked on #9 |
| Existing 0.10.8 assignment | Package file `docs/OPENCODE-OLARES.md` at exactly `f165c538fd08f1df5851998be77f1f0402608c72`; only 0.10.7 → 0.10.8 |
| New published image / merge revision | **Pending**; none invented |
| New pinned package head / fingerprint / rollout approval | **Pending** |

PR URLs: application https://github.com/Salamont/yacy_search_server/pull/73;
packages https://github.com/Salamont/YaCy-Search-Community/pull/9 and
https://github.com/Salamont/YaCy-Search-Community/pull/10. Refresh each PR/head
before future work; preserve all existing work and do not adopt changed
branches without reviewing their diff.

## Scope and known limitation

Keep #73's existing navigation/boilerplate, heading/list provider, negation,
short-quote and cache-publication corrections. **No additional price fix.**
The standalone price branch's provider binding remains open and explicitly
deferred: provider/service co-occurrence with a price does not necessarily
establish an offer by that provider. Do not claim it resolved or implement it
as part of this release/package handoff.

No real model calls, production access/change, model or prompt switch,
re-extraction, existing-data cleanup, quality run, merge, image push, tag
creation/repointing or rollout is authorized by this preparation handoff.
Read no credential file and print no keys, tokens, cookies or authorization
headers. Mock endpoints and fresh disposable test data only.

## Application review and later release

1. Inspect #73's current head and the release branch's base. Confirm the
   release diff contains only version/release documentation/packaging checks,
   no application semantic change. Review the explicitly deferred finding.
2. Use the new local check evidence recorded below separately from historical
   #73 validation. Record exact tested commit, commands, distribution hash,
   local image configuration ID/labels/platform and limitations. General
   compileTest is not fully green; do not rewrite unrelated tests.
3. Under a **separate merge decision**, merge #73 first. Retarget/reconcile
   the release PR to main and rerun checks for any resulting changes. Do not
   merge the release PR into #73's feature branch.
4. Before a separate release merge/publication decision, repeat both GHCR
   manifest availability checks and Git/release/PR searches for `.33` and
   `0.10.9`. Any occupied version stops this version's publication; never
   overwrite an alias. Initial check: 2026-10-14 19:46:25 UTC, both
   **404 / MANIFEST_UNKNOWN**, no matching Git tags/release entries.
5. A later authorized release merge into main triggers the existing
   `Publish Scoutro image` workflow once. Do not dispatch another run. Record
   actual merge revision/run, new immutable manifest digest, config/platform,
   both aliases and pulled OCI version/revision/source/licenses/runtime user.
   A local build ID or old `.32` digest cannot stand in for this evidence.

## Future Olares package, only after publication evidence exists

1. Recheck the #9/#10 stack and actual installed source version. Preserve
   those PRs and their immutable 0.10.7/0.10.8 identities. Do not create a
   second PR for either version or overwrite #10's pin.
2. Prepare the unique next package based on the reviewed #10 successor (or
   main after its merge). Pin both init-config and main Scoutro containers
   to the **actual newly published** digest. Until then keep the package
   preparation explicitly draft/unpinned/not rollout-ready; do not ship a
   placeholder digest or archive that pretends to be the final package.
3. Update chart/manifest/appVersion/release and acceptance notes for 0.10.9,
   retaining auth, entrances, UID/GID, resources, volumes, probes and sidecars.
   Record exact application revision and reviewed package head. Verify lint,
   scripts, renders and member modes/hashes/content fingerprint. Repack and
   independently check out the exact package commit to reproduce it.
4. Run isolated runtime and upgrade/restart/return tests on **the published
   digest**, with synthetic DATA/mock endpoints only. Cover source version,
   fixed service cases, positive controls, old accepted cache hits with zero
   model calls/unchanged result bytes, no new orphan statements, unchanged
   schema/epoch/config/prompt/extractor/quality-file checksums and no automatic
   re-extraction/check. Distinguish local-image-only evidence explicitly.
5. Prepare a new version-specific `docs/OPENCODE-OLARES.md` assignment in
   that future pinned package, based on #10's complete runbook. Supply exact
   package head, digest, source version, return image and acceptance evidence.
   Read and carry forward its preconditions, backup/restore gate, isolated
   network-disabled data-copy dry run and staged measurements. The old
   0.10.8 runbook/acceptance is not evidence for #73.
6. A future rollout requires a **separate explicit owner assignment**. Use
   the existing owner app only; no second installation/Market publication.
   Keep a consistent full DATA/config backup with modes/per-file checksums,
   prove its restore under the exact return image, preserve newer data on
   return, and verify image/package identity before any existing-app update.
   Existing false graph rows are not repaired by this upgrade. No LLM run or
   quality check is part of production acceptance.

## Evidence and outstanding gates

Historical #73 at `7ba5fcb6c`: 38/38 offer regressions; 108 focused; 1233
Scoutro tests; build successful. Baseline `7ef1aa4`: 15/38 red. General
compileTest: **15 existing errors**, one CrawlProfile constructor in
HostBalancerTest and fourteen removed NetworkGraph cache references in
NetworkGraphTest. This is not a fully green repository-wide test run.

New release checks will be recorded here after execution, with their exact
candidate and distinction from the historical runs. Publication, actual OCI
labels/digest, final package/fingerprint, published-image runtime/upgrade,
production backup/restore and owner rollout decision remain pending.
