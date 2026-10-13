# Scoutro 0.10.6 patch release

Prepared versions: **0.10.6** and **1.942-scoutro.30**, intended aliases of the
same immutable registry image. The numeric YaCy peer version remains **1.942**.
Basis: main `ce92b532402efd3f3a0a782087671074a29d18d6` (merge of #65 on 0.10.5 /
1.942-scoutro.29, `b02f28f88`; its tree equals the tested head `17e61ac`).
Installed privately on Olares (operator's report, 2026-10-13): 0.10.5 /
1.942-scoutro.29,
`ghcr.io/salamont/scoutro@sha256:64080288823c9c5e25b1f93f5b4761b59b7ea378e39a2ca6e070316b828d35c7`,
revision `b02f28f`, package commit `dd13150`, running since
2026-10-13T09:38:09Z. Its tags stay as they are.

User-facing changes: [CHANGELOG](../CHANGELOG.md). Everything of 0.10.5, 0.10.4,
0.10.3, 0.10.2 and 0.10.1 applies unchanged unless said here:
[SCOUTRO_RELEASE_0.10.5.md](SCOUTRO_RELEASE_0.10.5.md),
[SCOUTRO_RELEASE_0.10.4.md](SCOUTRO_RELEASE_0.10.4.md),
[SCOUTRO_RELEASE_0.10.3.md](SCOUTRO_RELEASE_0.10.3.md),
[SCOUTRO_RELEASE_0.10.2.md](SCOUTRO_RELEASE_0.10.2.md),
[SCOUTRO_RELEASE_0.10.1.md](SCOUTRO_RELEASE_0.10.1.md). The access, cookie and
sign-in limit contract of 0.10.0 is unchanged:
[SCOUTRO_RELEASE_0.10.0.md](SCOUTRO_RELEASE_0.10.0.md).

## Why a patch: what production showed

The rollout of 0.10.5 and a targeted crawl of the advertisements of one
consultancy (operator's report §§9–12, 2026-10-13) found:

- **No employer and no employment type** on five indexed advertisements whose
  imprint names the operator; where an employer was found, it could come from
  the footer ("Jobs bei uns") or the menu ("Unser Team"). (Correction,
  2026-10-14: the sentence "Zur Verstärkung unseres Teams suchen wir Dich"
  first named here is a neutral phrase of the tests, not the wording of these
  pages; the rollout of 0.10.6 reported "Bei uns wirst Du." and "Bei uns
  gestaltest Du …" — see [the correction below](#correction-2026-10-14-the-end-of-the-check-c29-and-the-wording-of-the-advertisements-c32).)
- **Employment types from the wrong places:** from requirements ("Erfahrung
  als Freelancer", "abgeschlossene Ausbildung"), compounds and neighbouring
  positions; a type stated in the offer further down was missed.
- **A second job from the JSON-LD of the freelance position's page**, named
  after the company. The raw JSON-LD (read by the operator from `ld_json_txt`):
  two separate blocks, no `@id`; a `JobPosting` whose `title` is the company's
  short name, `employmentType` `FULL_TIME`, `datePosted` 2023-08-30, an inline
  `Organization` (short name, `sameAs` the own domain) and an inline `Place`;
  and a `WebSite` with the full company name. Class: an unsuitable source title
  and contradicting employment types (`FULL_TIME` against the freelance
  advertisement) — not an `@id` collision, not a self-reference.
- **The end of the relation re-check was not readable:** 0.10.5 wrote
  `relation_recheck_done` only at a later start, and the event
  `relation_rules_recheck` had been displaced from the bounded event log by
  `identity_conflict` events (the C23 gap of 0.10.5, which stays open for
  0.10.5).

## What changes against 0.10.5

- **Employer** (#65): the operator (imprint, declared JSON-LD operator, a
  JSON-LD organisation of the own domain, or the domain's unnamed operator) is
  a job's employer where the advertisement's own text declares it; the text of
  an advertisement ends at its application call, the positions next to it or
  the footer. A navigation label declares nothing. An intermediary ("Für
  unseren Kunden/Mandanten …") recruits (`recruiting_organization`), the
  employer stays unknown; client projects stay the operator's own positions.
- **Employment type:** from the title or the advertisement's own text except
  its requirements; a form of the term or a compound of a position; no
  default.
- **JSON-LD:** references by kind (nodes sharing an `@id` keep their kinds); a
  posting title that is an organisation's, site's or domain's name gives no
  name; the posting of a position's page joins the advertisement's job unless
  it declares another page, place or employer; both sources keep their
  evidence and tier, no source wins. An organisation that links the page's own
  registrable domain by `url` or `sameAs` is the operator; without a legal form
  it is the domain's operator under its short name and joins the declared
  operator (one organisation with both names).
- **Relation re-check, rules version 4** (`kg_meta.relation_recheck_rules`).
  If the check of the rules 3 is **recorded as complete**
  (`relation_recheck_rules` = `3` and `relation_recheck_done` set), only the
  pages with a job of any tier or a job-ad hint are extracted again
  (`kg_meta.relation_recheck_scope` = `jobs`); otherwise the full check of
  relations, jobs and hints, once. Tiers 1 and 2 only, lowest priority,
  restart-safe; no LLM request, no crawl, no other page; the extractor
  identity stays.
- **The end of the check in the running process:** the publication of the last
  page and `relation_recheck_done` are one write (event
  `relation_rules_recheck_done`); a refused write ends nothing; a page that
  fails for good (`doc_failed`) keeps the check open
  (`kg_meta.relation_recheck_failed`) until it is published.
- **Durable status** `sync.relationRecheckInfo` of `kg/status`: `rules`, `scope`
  (`jobs`/`all`), `pages` (selected at the start), `since`, `done`, live `open`,
  `failed`, `failedPages`. The LLM count of the check before stays readable as
  `sync.relationRecheckLlmJobsBefore` (with its `rules`); the new check counts
  again (`sync.relationRecheckLlmJobs`); nothing of the LLM tier is changed.

Schema 6, the extractor identity (JSON-LD 5, rules 6) and every setting stay;
the upgrade writes no setting, only `kg_meta` keys of the re-check. Details:
[SCOUTRO_KNOWLEDGE_GRAPH.md](SCOUTRO_KNOWLEDGE_GRAPH.md) section 31.

## Upgrade from the installed 0.10.5

1. Nothing is migrated; the sync queue is kept.
2. **Which scope the re-check takes** depends on one `kg_meta` key that 0.10.5
   writes only when it **starts again** after its check ended:
   - `relation_recheck_done` **set** (0.10.5 restarted at least once after its
     check): scope `jobs` — only the pages with a job of any tier or a job-ad
     hint.
   - `relation_recheck_done` **missing** (0.10.5 not restarted since its check;
     production ran from 09:38:09Z with 0 restarts in the operator's report):
     scope `all` — the full check once with the rules 4 (relations, jobs,
     hints; the rules 3 check of 0.10.5 selected 7,830 pages and took about 4
     minutes). Relations come out as with the rules 3 (the rules 4 contain
     them); only the work is larger. Both scopes are correct.

   Read it before the upgrade **in the isolated restored copy only**
   (read-only): `sqlite3 -readonly <restore>/SCOUTRO/knowledge/graph.db
   "SELECT key, value FROM kg_meta WHERE key LIKE 'relation_recheck%'"`; the
   upgrade probe on that copy shows the scope and the duration.
3. **Relation re-check version 4:** `sync.relationRecheckInfo` shows `rules`
   `4`, the scope, `pages` N and `since`; `open` and `sync.relationRecheck` fall
   to 0; then `done` is set **in the running process, without a restart**
   (`failed` 0); event `relation_rules_recheck` once and
   `relation_rules_recheck_done` (either may be displaced from the event log;
   the status is the record). `sync.relationRecheckLlmJobsBefore` holds the
   count of the 0.10.5 check (`rules` 3).
4. Expected on re-checked job pages: new or changed job keys (an employer
   found or dropped, a posting joined to its advertisement) give new job nodes;
   the old nodes leave the current graph; `identity_conflict` events of the
   check are expected. No `job_reading_changed`,
   `sync.reconcile.reextract=false`, no LLM request because of the upgrade;
   LLM state, context, parallelism and GPU unchanged.

**From 0.10.4 or older** (elsewhere): the steps of 0.10.5 come first; the
re-check takes the scope `all` (the rules 3 check never completed there).

## Rollback

The primary return point is a current, consistent full DATA/configuration
backup with checksums and an isolated restore, plus the 0.10.5 digest above.
No lossless downgrade is claimed.

- **To 0.10.5:** same schema and settings. 0.10.5 ignores
  `relation_recheck_scope`, `relation_recheck_pages`,
  `relation_recheck_failed` and `relation_recheck_llm_jobs_before`. It sees
  `relation_recheck_rules` = `4`, not its `3`, and therefore checks its pages
  once more with its own rules (relations, rule-tier jobs, hints, LLM-tier job
  pages): employers from navigation, employment types from requirements and
  the second JSON-LD job come back on those pages, with new
  `identity_conflict` events; an organisation joined under its short name
  stays joined. Returning to 0.10.6 then takes the scope `all` unless 0.10.5
  was restarted after its check. Keep such a rollback short.
- **Further back:** the rollback notes of 0.10.5 apply.

## Acceptance (private Olares instance)

Steps 1–19 of [0.10.3](SCOUTRO_RELEASE_0.10.3.md#acceptance-private-olares-instance)
with the additions of [0.10.4](SCOUTRO_RELEASE_0.10.4.md#acceptance-private-olares-instance)
and steps 20–21 of [0.10.5](SCOUTRO_RELEASE_0.10.5.md#acceptance-private-olares-instance),
the 0.10.6 digest and version `1.942-scoutro.30`; in step 16
`relation_recheck_rules` = `4`; steps 22–23 of 0.10.5 are replaced by:

24. **Re-check, rules 4, scope as recorded:** `sync.relationRecheckInfo.rules`
    = `4`; `scope` = `jobs` if `relation_recheck_done` was set before the
    upgrade (read in the restored copy), else `all` — any other combination is
    a finding; `pages` and `since` stay the same across a restart; `open` → 0,
    `failed` = 0 and `done` set **without a restart** of the process; the
    duration is reported.
25. **No false end:** `done` is set only when `open` = 0 and `failed` = 0;
    a `doc_failed` page of the check is in `failedPages` and keeps `done` null
    until it is published; a refused write during the check
    (`storage.refusedWrites` growing) ends nothing; after a restart during the
    check, `pages` and `since` are unchanged and `open` counts only the rest.
    (Refusals and failures are not provoked in production; the unit tests
    cover them. Production reports what it observes.)
26. **The freelance advertisement (Finance):** exactly one job with its full
    title "Freiberufliche SAP Senior Berater (m/w/d) Finance"; `freelance` from
    the rule (`text:…`) and `full_time` from the JSON-LD
    (`jsonld:0/employmentType`), each with its own evidence; **one** employer
    organisation carrying the short and the full company name; its place and
    `date_posted` 2023-08-30 kept, no `valid_through`, the job not shown as
    ended; **no** job named like the company in the current graph.
27. **FI, CO, Cash Management, TRM, SAC:** one job each with its full title;
    `hiring_organization` only with an evidence excerpt from the
    advertisement's own text (not menu, not footer), else none;
    `employment_type` only where the advertisement states it explicitly
    (excerpt), else none.
28. **DRC** (`noindex,nofollow`): still not in the index and not in the graph.
29. **No LLM by the re-check:** no LLM request caused by the upgrade or the
    re-check; `reextract=false`; no `job_reading_changed`; the 40 LLM menu
    evidence rows (39 jobs) of 0.10.5 are unchanged —
    `relationRecheckLlmJobsBefore` = pages 151 / menuJobs 39 / menuEvidence 40 /
    positionEvidence 8 / otherEvidence 344 (`rules` 3); the new
    `relationRecheckLlmJobs` is read and reported (a different menu count is a
    finding to report, nothing is deleted).
30. **WAL/matching fix still effective:** steps 20–21 of 0.10.5 (difference of
    `storage.refusedWrites.wal_limit` 0 over at least 10 minutes; the next
    scheduled derived pass without `refused`; matching not
    `policy_notice_deferred`).
31. **State preserved:** configuration, accounts, collections, data counts,
    WAL, LLM, crawl and GPU settings equal before and after (snapshot
    comparison); no data deleted.

C19 (24 hours of the same version) is a separate short follow-up after a
complete sampler window of 0.10.6; the window of 0.10.5 is no evidence for
0.10.6. Browser and mobile evidence (C27) is separate from API and synthetic
smoke checks and must be reported as such.

## Candidate validation and later publication

As for 0.10.5: the release PR records the exact tested commit, the locally
built linux/amd64 image configuration ID, checks and limitations; a local
configuration ID is not a registry digest; skipped fork CI is not a passed
build. **The authorized merge** of the release PR changes `scoutro.properties`
and the workflow and therefore triggers the main `Publish Scoutro image`
workflow once: it refuses existing tags, then builds and pushes
`1.942-scoutro.30` and `0.10.6` with the actual merge revision. Do not dispatch
it in addition. After publishing, verify one immutable digest for both tags,
linux/amd64 and the merge revision label; the Olares package pins that digest.

## Correction (2026-10-14): the end of the check (C29) and the wording of the advertisements (C32)

The private rollout of 0.10.6 (operator's acceptance, 2026-10-13) found:

- **C29 failed.** Production ran 0.10.6 healthy and without a restart; the
  full check (scope `all`) published all its pages, nothing open or failed,
  and `sync.relationRecheckInfo.done` stayed null for hours. Cause and fix:
  [SCOUTRO_KNOWLEDGE_GRAPH.md, 31.6](SCOUTRO_KNOWLEDGE_GRAPH.md#316-the-0106-rollout-the-end-of-the-check-c29-and-the-employers-of-the-five-advertisements-c32):
  a job the LLM tier read on an old page during the check put that page into
  the selection, so the last page of the check did not end it. The statements
  above that `done` is set in the running process hold from the version with
  that fix on; a graph left by 0.10.6 ends its check at the first start of
  that version without extracting a page again.
- **C32: the wording of the five advertisements.** They say "Bei uns wirst
  Du." (a heading) and "Bei uns gestaltest Du …", not "Zur Verstärkung
  unseres Teams suchen wir Dich" (a phrase of the tests); they state no
  employment type ("Freiberuflich" only in the menu: correctly none), and
  their jobs have no employer. "bei uns" has been a declaration of the rules
  since 0.10.5: the cause lies in where the phrases stand (outside the
  advertisement's own text) or in the operator's identity, not in the
  wording; which of them is open (31.6).

