# PR #73: next release and Olares package handoff

PR [Salamont/yacy_search_server#73](https://github.com/Salamont/yacy_search_server/pull/73)
fixes service evidence in validation and publication, including list ownership,
negation and old accepted cache entries. It is **not in published 0.10.8**.
This document prepares the next handoff; it does not authorize or perform a
merge, image publication, package update or rollout.

Separate preparation now exists as application release draft
[#74](https://github.com/Salamont/yacy_search_server/pull/74), branch
`release/scoutro-0.10.9` stacked on #73, and documentation-only package draft
[#11](https://github.com/Salamont/YaCy-Search-Community/pull/11) stacked on #10.
Versions 0.10.9/.33 are prepared but not published. The new image/digest and
the actual next-version package remain pending; #11 does not change the chart
or its 0.10.8 pin. Complete current instructions:
[application handoff](OPENCODE_SCOUTRO_0.10.9_HANDOFF.md) and package
`docs/PREPARE-0.10.9.md` at the exact #11 head supplied in the final handoff.

**Known limitation, expressly deferred:** the standalone price branch can
accept a price-based `offers` passage with co-occurring provider/service names
without establishing that provider's ownership of the priced offer. This
additional price/provider fix is not included or claimed resolved. The
existing heading/list, negation and cache corrections remain unchanged.
Details: [deferred price branch](SCOUTRO_SERVICE_OFFER_EVIDENCE.md#deferred-provider-binding-in-the-standalone-price-branch).

## Verified existing release/package state

Read-only GitHub inspection on 14 October 2026:

| Item | Identity / state |
| --- | --- |
| Application #71 and release #72 | Merged; main `da72b55867f9f11f663cad8aa41b1097596405a3` |
| Published 0.10.8 / `1.942-scoutro.32` | Source `da72b5586`, without #73 |
| 0.10.8 image pin recorded by package #10 | `ghcr.io/salamont/scoutro:1.942-scoutro.32@sha256:ca4e87bbaa93122f5d16e34d7f07e83489b331db00b773e7f5f0537e7108e372` |
| Package [#9](https://github.com/Salamont/YaCy-Search-Community/pull/9), 0.10.7 | Open; head `55f01e296c431b91919e61fd1eb2595b2ac95c43` |
| Package [#10](https://github.com/Salamont/YaCy-Search-Community/pull/10), 0.10.8 | Open, stacked on #9; head `f165c538fd08f1df5851998be77f1f0402608c72` |

These are repository/package records; the image was not pulled or inspected
here. No production state was read in this review. Existing tags, digests and
the package stack remain unchanged. No duplicate package PR was created.

The current rollout assignment is
[package `docs/OPENCODE-OLARES.md` at #10's inspected head](https://github.com/Salamont/YaCy-Search-Community/blob/f165c538fd08f1df5851998be77f1f0402608c72/docs/OPENCODE-OLARES.md).
It targets **0.10.7 → 0.10.8** and cannot deploy or attest this fix. An owner
may continue that existing assignment separately; its acceptance is evidence
for 0.10.8 only.

## Required next handoff, after review and a separate release decision

1. Review and merge application #73 only under a separate authorization.
   Record the final reviewed head and resulting main revision. Preserve #71's
   fixed evaluation baseline, indexed diagnosis and key scrubbing.
2. Prepare a new application release on a revision containing that merge.
   Versions **0.10.9 / 1.942-scoutro.33** are prepared by draft #74 (not by
   #73). Both GHCR aliases were free at 2026-10-14 19:46:25 UTC, but must be
   checked again before publication. Keep the service-evidence fix, the
   explicitly deferred price/provider finding and cache/data limits in the
   release notes.
3. After the separately authorized release merge/publication, capture the
   successful publish run, exact source revision, new immutable manifest
   digest, platform and OCI version/revision labels. Verify both new aliases
   resolve to that digest. Do not repoint 0.10.8 or any existing image tag.
4. Coordinate the next package version in `Salamont/YaCy-Search-Community`
   with the existing #9/#10 stack. Recheck their state/base before making
   changes. Keep #10's 0.10.8 identity and pin; do not turn that version into
   a different image or open another 0.10.7/0.10.8 package PR. Prepare a
   distinct next version only once its image/revision/digest are known, using
   the reviewed successor of #10 (or main after its merge) as the base.
5. Pin both Scoutro containers to the new digest. Carry forward the package
   checks, content fingerprints, Helm/render checks, isolated package/upgrade
   runtime checks, release/acceptance notes and rollback identity from #10.
   Test the published digest itself; distinguish any local-image evidence.
6. Provide a new version-specific rollout assignment derived from
   `docs/OPENCODE-OLARES.md`, with the actual installed source version, exact
   package head, new image identity and verified return image. It must not
   reuse the old 0.10.8 acceptance as proof for #73. Rollout is a separate
   owner decision; none is performed by this application PR.

## Acceptance to carry into the next package

- With synthetic pages and a mock endpoint only, reproduce both review
  passages through shared validation and publication. No false `offers` for
  the referring organisation; no new service/name/offer/value from a denied
  heading or marked item. A genuinely offered foreign service may retain its
  name and price without a false operator relation.
- Retain genuine inline/marked offer lists and content offers, including the
  same names in navigation and a later positive offer after a denied list.
  Check statement subjects/objects and values, not counts alone.
- Use a preexisting accepted cache subset for each failure. Confirm a cache
  hit, zero model calls, guarded publication and unchanged cache bytes; no
  cleanup or re-extraction. Existing service types from lower tiers stay;
  their unsupported tier-3 `offers` still fail.
- Upgrade/restart on an isolated data copy with model/crawl access disabled:
  schema 6, tier identities, relation rules, `llm/4`, prompt/hash, cache keys
  and model settings stay. No version-triggered re-examination, automatic
  quality check, graph cleanup or new model request. Existing graph rows are
  not retroactively fixed. Preserve configuration and stored quality-result
  checksums; older quality results can be stale for the changed validator
  fingerprint without rewriting their files or changing evaluation 3.
- Preserve the runbook's backup/restore gate, exact image/package identity,
  existing-app-only update and return point. Neither the next upgrade nor
  these acceptance notes authorize repairing existing false graph entries,
  starting paid checks or changing the configured knowledge model.

Application regression details and evidence limits:
[service offer evidence](SCOUTRO_SERVICE_OFFER_EVIDENCE.md). Package work and
rollout remain pending after this PR; no tag, digest or package PR is changed
here.
