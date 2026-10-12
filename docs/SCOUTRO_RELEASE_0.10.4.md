# Scoutro 0.10.4 patch release

Prepared versions: **0.10.4** and **1.942-scoutro.28**, intended aliases of the
same immutable registry image. The numeric YaCy peer version remains **1.942**.
Basis: main `0a0818007e384992c063299c953c05fb3a1a6d9e` (merge of #60, 0.10.3 /
1.942-scoutro.27) and the list rule `e5870e718`. Installed privately on Olares
(operator's report, 2026-10-12): 0.10.2 / 1.942-scoutro.26,
`ghcr.io/salamont/scoutro@sha256:d99a31fbdc55819a19480f6fadabca6be0d3a3704559df7bb6aaf69a8fd9bc72`,
pod started 13:27:12Z. 0.10.3 / 1.942-scoutro.27 was published
(`sha256:fe6371f793fb6067774b83d3ed980b91e967e10e0a9aaa0e6b427352623d9e69`) but
**not rolled out**; 0.10.4 contains it. Their tags stay as they are.

User-facing changes: [CHANGELOG](../CHANGELOG.md). Everything of 0.10.3, 0.10.2
and 0.10.1 applies unchanged unless said here:
[SCOUTRO_RELEASE_0.10.3.md](SCOUTRO_RELEASE_0.10.3.md),
[SCOUTRO_RELEASE_0.10.2.md](SCOUTRO_RELEASE_0.10.2.md),
[SCOUTRO_RELEASE_0.10.1.md](SCOUTRO_RELEASE_0.10.1.md). The access, cookie and
sign-in limit contract of 0.10.0 is unchanged:
[SCOUTRO_RELEASE_0.10.0.md](SCOUTRO_RELEASE_0.10.0.md).

## Why a second patch

The package upgrade check of 0.10.3 ran the published rules on a real 0.10.2
graph and found a second fault of the relation rules beside the consent
banners: after "Unsere Partner: Alpha Systeme GmbH und Beta Logistik AG." the
next sentence "Muster Software GmbH, Musterstraße 1, 10115 Berlin" (the imprint
of the same page) was read as a third partner, so the operator became a partner
of itself. A list went on past a full stop whenever the next sentence started
with a name with a legal form ("Muster GmbH ist seit 1990 …"), for partners,
customers, references, memberships and the other list markers alike. The fault
is older than 0.10.3; it is fixed before the rollout so that production gets
one upgrade and one relation re-check instead of two.

## What changes against 0.10.3

- **A list of relations ends with its sentence.** `BusinessRules.names()`: a
  full stop ends the list; a list on lines (one name per line) goes on as
  before; the full stop of an abbreviated legal form (`Inc.`, `Ltd.`, `Corp.`,
  `S.A.`, `B.V.`, `N.V.`, `e.K.`) ends no sentence, and a joining word after it
  ("Beta Ltd. und Gamma GmbH") is no part of the next name.
- **The relation re-check has a version of the rules.** `kg_meta`
  `relation_recheck_rules` = `2`. A graph without it (0.10.2, or 0.10.3 whose
  check ran with the rules before) is checked once with the rules of 0.10.4:
  the active pages processed before the start that carry a relation of the rule
  tier are extracted again at the lowest priority (event
  `relation_rules_recheck`, `sync.relationRecheck`). No other page, no LLM call,
  the extractor identity stays; restart-safe as in 0.10.3.

Schema 6, the extractor identity and every setting stay; the upgrade writes no
setting (only the three `kg_meta` keys of the relation re-check).

## Upgrade from the installed 0.10.2

As described for 0.10.3 ([upgrade from 0.10.2](SCOUTRO_RELEASE_0.10.3.md#upgrade-from-the-installed-0102)),
with the rules of 0.10.4: the queue of 0.10.2 is kept and processed, nothing is
migrated, the relation re-check runs once (`relation_rules_recheck` once;
`relation_recheck_since`, `relation_recheck_rules` = `2`, later
`relation_recheck_done`).

**From 0.10.3** (an installation that ran it): the relation re-check runs once
more with the rules of 0.10.4 (a second `relation_rules_recheck`); nothing else.

## Rollback

As for 0.10.3 ([rollback](SCOUTRO_RELEASE_0.10.3.md#rollback)). To 0.10.2: pages
extracted again by 0.10.4 keep their corrected relations until 0.10.2 extracts
them again; 0.10.2 ignores the `kg_meta` keys of the re-check and
`collections-hidden.json`, and **stalls again** on the first careers page with
two gender markers in one sentence: keep such a rollback short. To 0.10.3 (not
rolled out anywhere on production): it keeps the `kg_meta` keys and does not
check again.

## Acceptance (private Olares instance)

Steps 1–19 of [0.10.3](SCOUTRO_RELEASE_0.10.3.md#acceptance-private-olares-instance)
with the 0.10.4 digest and version `1.942-scoutro.28`, and in step 16 also:

- `relation_recheck_rules` = `2` (read-only on the dry-run copy) and exactly
  one `relation_rules_recheck` after the upgrade from 0.10.2;
- after `sync.relationRecheck` reached 0: a company whose partner or customer
  list is followed by its imprint or another company's sentence has no
  relation to itself or to that next company; its listed partners stay.

## Candidate validation and later publication

As for 0.10.3: the release PR records the exact tested commit, the locally
built linux/amd64 image configuration ID, checks and limitations; a local
configuration ID is not a registry digest; skipped fork CI is not a passed
build. **The authorized merge** of the release PR changes `scoutro.properties`
and the workflow and therefore triggers the main `Publish Scoutro image`
workflow once: it refuses existing tags, then builds and pushes
`1.942-scoutro.28` and `0.10.4` with the actual merge revision. Do not dispatch
it in addition. After publishing, verify one immutable digest for both tags,
linux/amd64 and the merge revision label; the Olares package pins that digest.
