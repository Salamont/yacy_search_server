# OpenCode handoff: Scoutro 0.10.9 preparation

**Release review only. NOT a publication or rollout authorization.** Application
#73 was separately authorized and merged; release #74 now targets main.
No 0.10.9 image digest is available, so
the next Olares package is not rollout-ready. Do not infer missing identifiers
or use 0.10.8's digest for the new version.

Read this entire file and `docs/SCOUTRO_RELEASE_0.10.9.md` from the exact
application release-PR head supplied in the final handoff. These repository
files are the full instructions; no attachment or prior chat is needed.

## Verified starting identities

| Repository / item | Identity and dependency |
| --- | --- |
| Application | `Salamont/yacy_search_server` |
| Main | `7263ac5ca9154dd9800456644429de508a6fa3dc`; #71/#72/#73 merged; published 0.10.8 remains at `da72b55867f9f11f663cad8aa41b1097596405a3` |
| Service fix #73 | Merged 15 October 2026 as `7263ac5ca9154dd9800456644429de508a6fa3dc`, parents `da72b5586` and reviewed head `432a2ad026304a4bc7aa55c26c00eeb02d115c2b`; original validated code `7ba5fcb6c4faa77007861feb5b4a64a3ce713b82`; feature branch preserved |
| New application release #74 | https://github.com/Salamont/yacy_search_server/pull/74; `release/scoutro-0.10.9`, PR base `main`; unmerged, awaiting separate final release decision; post-merge tested candidate `f9e6480963e3546d3b7de40c3a395fb64ea03601`; initial local-image candidate `bb57c86a403a796dc334e00671c1ab3c6435acb0`; 0.10.9 / 1.942-scoutro.33 |
| Package repository | `Salamont/YaCy-Search-Community` |
| Package #9, 0.10.7 | Head `55f01e296c431b91919e61fd1eb2595b2ac95c43` |
| Package #10, 0.10.8 | Head `f165c538fd08f1df5851998be77f1f0402608c72`, stacked on #9 |
| Existing 0.10.8 assignment | Package file `docs/OPENCODE-OLARES.md` at exactly `f165c538fd08f1df5851998be77f1f0402608c72`; only 0.10.7 → 0.10.8 |
| Package preparation #11 | https://github.com/Salamont/YaCy-Search-Community/pull/11; draft `prepare/scoutro-0.10.9-handoff`, head `fdb46257fd4eacc0935dbaf369f8cc1e95049667`, base #10; `docs/PREPARE-0.10.9.md` is the next-package assignment; unchanged in this step; its earlier #73/#74 identities are superseded by this file |
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
   #73 validation and 14 October release checks. Record exact tested commit, commands, distribution hash,
   local image configuration ID/labels/platform and limitations. General
   compileTest is not fully green; do not rewrite unrelated tests.
3. **Completed under explicit authorization on 15 October:** #73 merged,
   #74 retargeted to main, main included without a file-tree change, build
   and focused tests rerun. Verify these identities; do not repeat #73's
   merge or merge #74 into the preserved feature branch. **#74 remains
   unmerged and requires a separate final release decision.**
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

Earlier checks executed on 14 October, candidate `bb57c86a403a796dc334e00671c1ab3c6435acb0`:

| Local check | Result |
| --- | --- |
| `ant clean all dist scoutro-kg-offer-evidence-test` | BUILD SUCCESSFUL; 60 Jetty and 108 focused tests, including 38/38 offer regressions |
| `ant compileTest` | FAILED with the same 15 existing errors, only the two files named above |
| `test/solr9-jetty-bridge-spike.sh`, including dependency guard | Passed; 4 bridge tests |
| `test/scoutro-ui/llm-quality-live-smoke.py` | 198 UI / 65 live checks, local peer and fake model only |
| `test/scoutro-release/check-attribution.py` | 58 original files unchanged in both actual distribution and exported local image; version/release docs/help/tools/licences present; build-only Git/session CA absent |
| `test/scoutro-release/run-isolated-image-smoke.py scoutro:0.10.9-rc-bb57c86a4-readable` | 21 existing image assertions passed, network=none, UID/GID 1000, fresh synthetic DATA: capture, clean/unclean restart, integrity, KG backup/restore and rebuild |
| Package `scripts/validate.sh` on doc-only #11 | 39 static checks of unchanged 0.10.8 passed; script then stops at missing `olares-cli`. Olares lint NOT passed |
| Source/Java-test freeze, local documentation links, `git diff --check` | Passed |

Exact local image configuration ID (NOT a registry manifest digest):
`sha256:91a8330d09670df79e098a25c440b96394ce8ddac388556a554f20126c7eef8a`.
Inspected labels: version `1.942-scoutro.33`, revision
`bb57c86a403a796dc334e00671c1ab3c6435acb0`, source
`https://github.com/Salamont/yacy_search_server`, licences GPL-2.0-or-later;
platform linux/amd64, configured user `yacy`. Runtime smoke uses the package's
UID/GID 1000. The complete general test suite is not green; the 1233-test
Scoutro run remains historical and was not repeated here.

Initial tested distribution:
`RELEASE/yacy_v1.942_202610141910_bb57c86a4.tar.gz`, SHA-256
`627e56353c969fd403d18f9fbe9887dfd09a0b582f7716b6b88303ba6c7cf0cf`.
Later commits only add release transport/evidence/handoff material; application
source, Java tests, version properties and publication/Docker settings remain
identical to that candidate. Check the final PR diff before future use and
record any newly built distribution separately.

Local harness issues were resolved without an application fix: use the actual
session CA, public checkout modes (umask 022; private checkout modes prevented
package UID 1000 from reading the image), and container loopback instead of a
host port when networking is disabled. The release adapter retains the
original `kg-image-smoke.py` assertions, refuses a preexisting fixture container
and uses only the original synthetic credentials/fresh DATA. No productive
credential is read or printed. No original assertion was weakened.

Both registry aliases again answered 404 / MANIFEST_UNKNOWN at
**2026-10-14 22:05:12 UTC**. This is still not a reservation.
Machine-readable evidence: `docs/validation-0.10.9.json`.

### New post-merge validation, 15 October 2026

Tested candidate: `f9e6480963e3546d3b7de40c3a395fb64ea03601`. Its first
parent is the originally expected release head `74870648f523dc833e2ae879156af6abd6981b20`,
second parent is main/#73 merge `7263ac5ca9154dd9800456644429de508a6fa3dc`.
The merge changed no files. The source/Java tests equal main and `7ba5fcb6c`;
only release versions, packaging/test tools and documentation differ from main.

Executed from the application repository, using cached dependencies:

```sh
JAVA_HOME=/tmp/scoutro-jdk \
ANT_OPTS='-Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts' \
/tmp/apache-ant-1.10.15/bin/ant -Divy.default.ivy.user.dir=/tmp/scoutro-ivy \
  clean all dist scoutro-kg-offer-evidence-test
```

Result: **BUILD SUCCESSFUL**, 18 relocation packaging checks, **60 Jetty
and 108 focused tests, including 38/38 offer regressions**. Mock model and
fresh isolated Solr/SQLite data only, including both closed review findings,
positive offer lists, dependent statements and legacy cache publication.
No real model call or existing graph re-extraction/cleanup.

Tested distribution: `RELEASE/yacy_v1.942_202610150610_f9e648096.tar.gz`,
SHA-256 `51dac03afefe6b97340a5bba10d3217786d3b88eb7e4e3898fd7915360f41bf4`.
Later changes only refresh documentation/evidence; the final archive is
rebuilt and identified separately in #74's description.

Repeated version check **2026-10-15 06:03:13 UTC**: matching remote Git tags
and GitHub releases absent; both registry manifests 404 / MANIFEST_UNKNOWN.
Repeat immediately before any later authorized publication. Main's only
push workflow run was `Build Docker Image`, run 38690043932, **skipped** by
its upstream-repository guard; no Scoutro publish workflow was triggered.
PR CI also skips this fork; local checks are the executed validation.

The 15 general compileTest errors are the earlier result and were **not
rerun**; no relevant source/test change justified a repeated general run.
The 1233 Scoutro tests, UI/live/bridge/image smoke and package checks above
were also **not rerun** in this step. Additional final distribution attribution
checks are reported separately; none replaces published-image acceptance.
Package #9/#10/#11 heads, bases and contents remain untouched. #11's older
stack description must be reconciled only after actual image publication.

Publication, actual published OCI labels/digest, final package/fingerprint,
published-image runtime/upgrade, production full backup/restore and owner
rollout decision remain pending. No local test satisfies these later gates.
