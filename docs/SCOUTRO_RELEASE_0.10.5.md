# Scoutro 0.10.5 patch release

Prepared versions: **0.10.5** and **1.942-scoutro.29**, intended aliases of the
same immutable registry image. The numeric YaCy peer version remains **1.942**.
Basis: main `acc899ecceb20b83ce3dadd2ab748e994c930be4` (merges of #62 and #63
on 0.10.4 / 1.942-scoutro.28, `0323ddf85`). Installed privately on Olares
(operator's report, 2026-10-12/13): 0.10.4 / 1.942-scoutro.28,
`ghcr.io/salamont/scoutro@sha256:ddd5be37a5bf1c0a2858d0a5be0463b686cf53ae171d375bc36406286e3f74a3`,
pod started 2026-10-12T20:02:14Z. Its tags stay as they are.

User-facing changes: [CHANGELOG](../CHANGELOG.md). Everything of 0.10.4, 0.10.3,
0.10.2 and 0.10.1 applies unchanged unless said here:
[SCOUTRO_RELEASE_0.10.4.md](SCOUTRO_RELEASE_0.10.4.md),
[SCOUTRO_RELEASE_0.10.3.md](SCOUTRO_RELEASE_0.10.3.md),
[SCOUTRO_RELEASE_0.10.2.md](SCOUTRO_RELEASE_0.10.2.md),
[SCOUTRO_RELEASE_0.10.1.md](SCOUTRO_RELEASE_0.10.1.md). The access, cookie and
sign-in limit contract of 0.10.0 is unchanged:
[SCOUTRO_RELEASE_0.10.0.md](SCOUTRO_RELEASE_0.10.0.md).

## Why a patch: what production showed

A read-only measurement of production 0.10.4 (2026-10-13) found two faults:

- **Derived/matching refused at the WAL limit.** `storage.refusedWrites.wal_limit`
  grew by 119.5 per minute (about 2 per second) with an empty WAL; every
  hourly derived pass ended at once with `write_refused`; matching stayed
  `policy_notice_deferred`, customer suggestions paused. A changed matching
  policy publishes one feed notice per stored contribution (87,764) and did so
  in one write whose estimate (about 85.7 MiB) is above `wal.maxBytes` (64 MiB):
  refused however empty the WAL is, retried every second.
- **Wrong jobs and memberships of the rule tier.** On a consultancy's site one
  open position became 13 job nodes ("Freiberufliche SAP Senior Berater
  (m/w/d)", one "e — …", none with its specialisation "Finance") from the
  advertisement and two pages of the careers section that only repeat its menu
  (culture, benefits); 34 statements, all of the rule tier, none of the LLM
  tier; the employment type *internship* came from the word "Internationale";
  and the company was "member of" an organisation "Einkaufs" from a sentence
  about a consultant in the purchasing team of a client.

## What changes against 0.10.4

- **Matching-policy notices in bounded writes** (#62). `MatchingService`
  publishes the notices of 2,000 contributions per write (estimate at most
  about 2 MiB), keeps its position in `kg_meta.matching_policy_pending` (with
  the policy it belongs to) and records `kg_meta.matching_policy` after the
  last one; a stop resumes, another policy starts again. The runtime publishes
  at most 20 writes per start or maintenance step. With 87,764 contributions:
  44 writes in the first steps after the start, then the derived pass runs as
  scheduled. The WAL limit, the checkpoint pace and every setting stay.
- **A careers page has the positions it advertises as jobs** (#63). A detail
  page has the positions it names; another page of a careers section has a
  title as position only if the page announces or describes it, not as an
  entry of a menu or teaser list. A title keeps its specialisation after the
  gender marker ("… (m/w/d) Finance"); lead words are whole words; the posting
  key no longer holds the offset (a changed menu keeps the job). The LLM tier
  drops a job that the rules see on that page only as a menu entry.
- **Employment types:** "intern" names an internship only as a word of its
  own (or "internship"), never as the start of "Internationale", "Internet",
  "interne"; the title itself states its kind of employment ("Freiberufliche
  …" is freelance).
- **Memberships:** a department or team ("Mitglied des Einkaufs") and a person
  in a role ("eines Beraters als Mitglied des …") are no membership of the
  page's organisation; associations, chambers, cooperatives and "wir sind
  Mitglied …" stay.
- **The relation re-check, rules version 3** (`kg_meta.relation_recheck_rules`).
  An installation of 0.10.4 (version 2) is checked once: the active pages
  processed before the start that carry, in the rule tier, a relation, a job or
  a job-ad hint (`section` `job_ad_…`), and those with a job of the LLM tier,
  are extracted again at the lowest priority (event `relation_rules_recheck`,
  `sync.relationRecheck`). Tiers 1 and 2 only: no LLM request, no other page,
  no crawl, the extractor identity stays; restart-safe as before.
- **The LLM tier's job evidence is counted, not changed**:
  `kg_meta.relation_recheck_llm_jobs`, shown as `sync.relationRecheckLlmJobs`
  of `kg/status` (`pages`, `menuJobs`, `menuEvidence` = jobs and evidence rows
  the page has only as a menu entry; `positionEvidence`, `otherEvidence` =
  kept). Removing the counted rows is a later, separate owner decision.
- **Status label:** `confirmed` reads *Extracted from source* / *Aus Quelle
  extrahiert* in the network and service lists; tooltip and legend say that no
  human review is implied; confidence and number of sources stay in their own
  columns; the API value stays `confirmed`.

Schema 6, the extractor identity and every setting stay; the upgrade writes no
setting, only `kg_meta` keys (`matching_policy_pending` while publishing,
`matching_policy`, the keys of the relation re-check and its LLM count).
Details: [SCOUTRO_KNOWLEDGE_GRAPH.md](SCOUTRO_KNOWLEDGE_GRAPH.md) section 30.

## Upgrade from the installed 0.10.4

1. Nothing is migrated; the sync queue is kept.
2. **First maintenance steps:** the matching-policy notices are published in
   bounded writes (expected 44 for 87,764 contributions, 20 per step);
   `storage.refusedWrites.wal_limit` stays where it is (the counter starts at
   0 with the process); `matching_policy_pending` disappears and
   `matching_policy` is recorded. The next scheduled derived pass (hourly)
   ends without `write_refused`; matching partitions advance and
   `derived.matching.deferred` is no longer `policy_notice_deferred`.
3. **Relation re-check version 3:** one event `relation_rules_recheck` naming
   the number of pages ("pages with relations or jobs of the rule tier
   extracted again …"); `sync.relationRecheck` falls to 0;
   `relation_recheck_done` is set at a later start. The 0.10.4 dry run took
   about 30 minutes for 10,223 relation pages; version 3 adds the pages with
   jobs and job-ad hints, so measure the duration in the dry run.
4. No `job_reading_changed`, `sync.reconcile.reextract=false`, no LLM request
   because of the upgrade; LLM state, context, parallelism and GPU unchanged.

**From 0.10.3 or 0.10.2** (elsewhere): the steps of 0.10.3/0.10.4 come first;
0.10.5 adds the two steps above.

## Rollback

The primary return point is a current, consistent full DATA/configuration
backup with checksums and an isolated restore, plus the 0.10.4 digest above.
No lossless downgrade is claimed.

- **To 0.10.4:** same schema and settings. 0.10.4 ignores
  `matching_policy_pending` and `relation_recheck_llm_jobs`. Once 0.10.5 has
  recorded the matching policy, 0.10.4 publishes nothing again (same policy
  fingerprint); a rollback **before** that (a pending publication) brings back
  the refused write and `policy_notice_deferred`. 0.10.4 sees
  `relation_recheck_rules` = `3`, not its `2`, and therefore checks its
  relation pages once more with its own rules: department and person
  memberships and menu jobs come back on those pages; returning to 0.10.5 checks
  them once more with version 3. Keep such a rollback short.
- **Further back:** the rollback notes of 0.10.4 apply.

## Acceptance (private Olares instance)

Steps 1–19 of [0.10.3](SCOUTRO_RELEASE_0.10.3.md#acceptance-private-olares-instance)
with the additions of [0.10.4](SCOUTRO_RELEASE_0.10.4.md#acceptance-private-olares-instance),
the 0.10.5 digest and version `1.942-scoutro.29`; in step 16
`relation_recheck_rules` = `3`; then:

20. **WAL refusals stop (read only):** two readings of `kg/status` at least
    10 minutes apart after the notices were published: the difference of
    `storage.refusedWrites.wal_limit` is 0 (a non-zero difference is a finding:
    report it with the WAL bytes and the derived/matching state at that time);
    `wal_checkpoint_blocked` unchanged; `matching_policy_pending` absent.
21. **Derived/matching runs:** the next scheduled derived pass (not started by
    hand) has no `refused`; `derived.matching.deferred` is not
    `policy_notice_deferred`; the partitions or `cycle_complete` advance
    between two passes.
22. **Relation re-check 3:** exactly one `relation_rules_recheck` after the
    upgrade, `relation_recheck_rules` = `3`, `sync.relationRecheck` → 0;
    `sync.relationRecheckLlmJobs` read and reported (count only).
23. **Jobs and memberships of the measured company (read only):** its
    advertisement "Freiberufliche SAP Senior Berater (m/w/d) Finance" is one job
    with its full title, its detail page as evidence, `employment_type`
    freelance and no internship; no job named "e — …"; no job whose only
    evidence is a culture or benefits page of its careers section; no
    `member_of` "Einkaufs"; its real memberships stay. The label *Aus Quelle
    extrahiert* is shown.

## Candidate validation and later publication

As for 0.10.4: the release PR records the exact tested commit, the locally
built linux/amd64 image configuration ID, checks and limitations; a local
configuration ID is not a registry digest; skipped fork CI is not a passed
build. **The authorized merge** of the release PR changes `scoutro.properties`
and the workflow and therefore triggers the main `Publish Scoutro image`
workflow once: it refuses existing tags, then builds and pushes
`1.942-scoutro.29` and `0.10.5` with the actual merge revision. Do not dispatch
it in addition. After publishing, verify one immutable digest for both tags,
linux/amd64 and the merge revision label; the Olares package pins that digest.
