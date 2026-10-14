# Scoutro 0.10.7 patch release

Prepared versions: **0.10.7** and **1.942-scoutro.31**, intended aliases of the
same immutable registry image. The numeric YaCy peer version remains **1.942**.
Basis: main `79d015fd736ac9fe2915cedbba060fbe2dbb4029` (merge of #67 on 0.10.6 /
1.942-scoutro.30, `3c28870`), with #68 (`18db883`) and #69 (`369cdc9`) merged
into the release branch and one integration fix of both. Installed privately on
Olares (operator's report, 2026-10-14): 0.10.6 / 1.942-scoutro.30,
`ghcr.io/salamont/scoutro@sha256:ecd7cd9204b014d738a9d500bd382a78d20fee375ba7de425ecb2827e171e952`,
revision `3c28870`, package commit `9c83777`, pod started
2026-10-13T18:18:55Z without a restart since. Its tags stay as they are.

User-facing changes: [CHANGELOG](../CHANGELOG.md). Everything of 0.10.6 and the
releases before applies unchanged unless said here:
[SCOUTRO_RELEASE_0.10.6.md](SCOUTRO_RELEASE_0.10.6.md),
[SCOUTRO_RELEASE_0.10.5.md](SCOUTRO_RELEASE_0.10.5.md),
[SCOUTRO_RELEASE_0.10.4.md](SCOUTRO_RELEASE_0.10.4.md),
[SCOUTRO_RELEASE_0.10.3.md](SCOUTRO_RELEASE_0.10.3.md),
[SCOUTRO_RELEASE_0.10.2.md](SCOUTRO_RELEASE_0.10.2.md),
[SCOUTRO_RELEASE_0.10.1.md](SCOUTRO_RELEASE_0.10.1.md). The access, cookie and
sign-in limit contract of 0.10.0 is unchanged:
[SCOUTRO_RELEASE_0.10.0.md](SCOUTRO_RELEASE_0.10.0.md).

## Why a patch: what production showed

- **C29 failed in the rollout of 0.10.6.** The full re-check of the relation
  rules 4 published all its pages, nothing was open or failed, and
  `sync.relationRecheckInfo.done` stayed null for hours without a restart.
- **C32, measured on 2026-10-14:** the five advertisements of one consultancy
  had no employer. Their phrases ("Bei uns wirst Du.", "Bei uns gestaltest
  Du …") stand inside the advertisement, but the rules read the page's title
  line before a long navigation as the advertisement.
- **The LLM tier gave wrong types:** a page's sidebar tools
  ("Rechtschreibprüfung", an image list) and a team of a contact text
  ("Service team") became services; a team member's role became a job.
- **The hourly log report failed** six times with a bare HTTP 400, without
  the provider's reason.
- **A model for the knowledge graph** could only be judged after it had
  written to the graph. The LLM selection now checks a candidate first,
  without writing to the graph.

## What changes against 0.10.6

- **The end of the re-check (C29, #67).** The check's selection counts only
  evidence published before it started: fixed at its start, it only shrinks.
  A job the LLM tier publishes on an old page during the check no longer holds
  the end open. The running process also ends a check whose selection empties
  without a page of it (looked at every 5 minutes). A check whose pages were
  all published while its end is missing (the graph 0.10.6 leaves) ends at the
  first start without extracting any page again. Details:
  [SCOUTRO_KNOWLEDGE_GRAPH.md, 31.6](SCOUTRO_KNOWLEDGE_GRAPH.md).
- **The advertisement of a position's page, relation rules 5 (C32, #67).**
  Every title occurrence is read (at most 400). The advertisement is the
  occurrence followed by sentences of its own text, its evidence starts at its
  heading, and it ends at the next title. Menu and footer labels declare no
  employer, and a portal's hirer makes the operator neither employer nor
  recruiter. The bound of 6,000 characters stays. Details:
  [SCOUTRO_KNOWLEDGE_GRAPH.md, 32](SCOUTRO_KNOWLEDGE_GRAPH.md).
- **A targeted re-check of the job pages (rules 5).** Only the pages with a
  job of any tier or an advertisement hint are extracted again, once: tiers 1
  and 2, at low priority, restart-safe. No global re-extraction, no LLM request,
  no crawl.
- **The type of an LLM entity (#68).** The validator refuses, each counted
  under its own reason:
  - a service named as a team, a department or staff
    (`entity_service_team_or_role`);
  - a service without an offer in its quote, its heading, an `offers`
    statement or a price (`entity_service_not_offered`);
  - a job without an advertisement (`entity_job_not_advertised`).

  The integration found that only "verkaufen" counted as an offer. "verkauft",
  "vertreibt", "vermietet", "sells", "selling" and "sold" are offers too. The
  compiled-in default prompt says the same: it has a new prompt hash, and the
  LLM extractor version stays `llm/4`. Details:
  [SCOUTRO_KNOWLEDGE_GRAPH.md, 33.1](SCOUTRO_KNOWLEDGE_GRAPH.md).
- **The log report's call fits the model's window (#68).** The whole call is
  planned: template and instructions included, log lines at 2.5 characters per
  token, a margin of 5 %, and the configured output cap. Lines that do not fit
  are omitted visibly: warnings and errors first, then the newest lines. A call
  that cannot fit is not sent.
- **Safe error diagnostics (#68).** HTTP errors of a model endpoint name their
  reason: the status, the provider's code, the rejected parameter, a router's
  upstream provider and up to 300 characters of its message, read from at most
  16 KiB of the body. Never part of it: the request, the prompt, a router's raw
  answer or a key. The knowledge graph's `lastError` names the status and the
  code. Details: [SCOUTRO_KNOWLEDGE_GRAPH.md, 33.2](SCOUTRO_KNOWLEDGE_GRAPH.md).
- **Provenance (#68).** A model switch between reading the selection and
  admitting a request defers the request (`model_changed`).
  `llm.context.measured` counts the models and providers an endpoint says it
  served.
- **Quality check of a candidate model for the knowledge graph (#69).** It is
  a new section of the LLM selection, for administrators only. Details:
  [SCOUTRO_LLM_QUALITY_CHECK.md](SCOUTRO_LLM_QUALITY_CHECK.md). Its
  evaluation 2 counted a rule statement the model repeated as the model's;
  replaced by evaluation 3 with a fixed baseline and a diagnosis in
  [0.10.8](SCOUTRO_RELEASE_0.10.8.md).
  - **How it reads.** It reads ten versioned reference pages (test set
    `kg-reference` version 2, evaluation version 2) with the candidate. The
    steps are the graph's own: client, active prompt, chunks, budget, validator
    and publication preparation. Nothing is published.
  - **Status.** The result shows the status, every deviation and its
    provenance. With accepted statements the test set does not rate, the
    status is `passed_with_unrated`, never `passed`.
  - **Cost.** Every cost it shows is an estimate, never a guaranteed maximum.
  - **Writes.** It writes only its own result, one file per candidate in
    `DATA/SCOUTRO/llm-quality/`, without a key or prompt text. It writes
    nothing to the graph, the extraction cache, the index or any setting.
  - **The active model.** It never changes the active model.
- **Changed in the LLM selection (#69).**
  - The knowledge usage moves only after a confirmation: by the matrix's
    **knowledge** checkbox, by the undeploy of the row that holds it, or by
    *Use for the knowledge graph …*.
  - Switching it off leaves it off. 0.10.6 handed the opt-in knowledge usage
    to the first other row.

Schema 6, the extractor identity of tiers 1 and 2 (JSON-LD 5, rules 6), the LLM
extractor version (`llm/4`) and every setting stay. The upgrade writes no
setting, only `kg_meta` keys of the re-check and the prompt-change record.

## Upgrade from the installed 0.10.6

1. **Nothing is migrated.** The sync queue, the LLM queue, its cache and every
   setting are kept, and `ai.production_models` is unchanged.
2. **The check of the rules 4 is closed (C29).** At the first start the check
   0.10.6 left without its end (rules 4, scope `all`, all pages published,
   `done` null; production ran without a restart) counts as complete, because
   its fixed selection is empty.
   - None of its pages is extracted again for it.
   - Its record stays readable as `sync.relationRecheckInfoBefore`: `rules` 4,
     `scope` `all`, `pages`, `since`, and `done` with `doneAtStart` true.
   - The event `relation_rules_recheck_done` ("rules 4: … its end written when
     the rules 5 started") is written once.
3. **Rules 5, scope `jobs`.** `sync.relationRecheckInfo` shows `rules` 5,
   `scope` `jobs`, `pages` (the job pages) and `since`.
   - `open` falls to 0, then `done` is set in the running process, without a
     restart; `failed` stays 0.
   - Events `relation_rules_recheck` and `relation_rules_recheck_done` (either
     may be displaced from the event log; the status is the record).
   - Expected on re-checked job pages: one job named at the advertisement's
     heading; an employer only where the advertisement declares it; jobs whose
     key changes become new nodes; `identity_conflict` events. Relations and
     the evidence of the LLM tier stay.
4. **LLM tier.** If the compiled-in default prompt is active, its new hash is
   recorded at the start (`llm_prompt_changed`). Done documents are not read
   again, and no cache entry and no evidence is deleted. New and changed pages
   are asked with the new default and checked by the new validator. An active
   custom prompt stays as it is. The upgrade causes no LLM request.
   Wrongly typed evidence accepted before stays. Its removal is the separate,
   reviewable cleanup plan of 33.1 and not part of the upgrade.
5. **Log report.** The next hourly run is planned against the window. Its
   lines carry `estPromptTokens`, `promptTokenBudget`, `maxTokensSent`,
   `windowExceeded` and the omitted lines. A failure line carries
   `httpStatus`, `providerCode`, `providerParam`, `provider`,
   `providerMessage` and `errorBodyBytes`, never the prompt or a key.
6. **Quality check.** It appears in `LLMSelection_p.html`; `GET
   /scoutro/api/v1/kg/llm-quality` lists every production row as
   `unchecked`. Nothing runs on its own, and `DATA/SCOUTRO/llm-quality/` is
   created only when an administrator starts a check. A check sends paid
   requests to the candidate's endpoint, so it needs its own decision with the
   candidate, the price and the cost limit named.

**From 0.10.5 or older** (elsewhere): the steps of 0.10.6 come first. A check
of the rules 3 or 4 that is not complete is followed by the full check once
(scope `all`).

## Rollback

The primary return point is a current, consistent full DATA/configuration
backup with checksums and an isolated restore, plus the 0.10.6 digest above.
No lossless downgrade is claimed.

- **Back to 0.10.6:** same schema and settings.
  - 0.10.6 sees `relation_recheck_rules` = `5`, not its `4`, and checks its
    pages once more with the rules 4 (scope `all`). Its C29 defect can leave
    that check's end unwritten again.
  - It ignores `relation_recheck_before`. Jobs read by the rules 5 are read
    again by the rules 4.
  - Its default prompt and its looser validator apply again to new pages, and
    the start records `llm_prompt_changed` again.
  - Result files in `DATA/SCOUTRO/llm-quality/` are ignored. They hold no key
    and may stay.
  - The matrix hands the knowledge usage to another row again when it is
    switched off.

  Keep such a rollback short.
- **Further back:** the rollback notes of 0.10.6 apply.

## Acceptance (private Olares instance)

Steps 1–19 of [0.10.3](SCOUTRO_RELEASE_0.10.3.md#acceptance-private-olares-instance)
with the additions of [0.10.4](SCOUTRO_RELEASE_0.10.4.md#acceptance-private-olares-instance),
steps 20–21 of [0.10.5](SCOUTRO_RELEASE_0.10.5.md#acceptance-private-olares-instance)
and steps 26–31 of [0.10.6](SCOUTRO_RELEASE_0.10.6.md#acceptance-private-olares-instance)
apply, with the 0.10.7 digest and version `1.942-scoutro.31`. In step 16,
`relation_recheck_rules` = `5`. Steps 24–25 of 0.10.6 are replaced by:

32. **C29, the end is traceable.** Read in the restored copy before the
    upgrade: `relation_recheck_rules` = `4`, `relation_recheck_done` missing,
    `relation_recheck_since`, `relation_recheck_pages`. After the upgrade:
    - `sync.relationRecheckInfoBefore` shows `rules` 4, `scope` `all`, the same
      `pages` and `since`, `done` set and `doneAtStart` true;
    - the event `relation_rules_recheck_done` with "rules 4" is there once;
    - no page was extracted again for the rules 4 (the extraction count of the
      first minutes covers only the rules-5 pages).
33. **Relation rules 5, targeted job re-check.**
    - `sync.relationRecheckInfo` shows `rules` 5 and `scope` `jobs`. `pages` is
      about the count of the selection of 31.4 with `:scope` = `jobs`, read
      before the upgrade.
    - `open` falls to 0, then `done` is set in the running process, without a
      restart; `failed` stays 0.
    - Pages with relations only are not extracted again.
    - No LLM request and no crawl because of the check.
34. **The five advertisements (C32):** each has one job, its `name` evidence
    at the advertisement's heading and none from the navigation before it. Its
    `hiring_organization` is the operator with the phrase as evidence, where
    the page's operator identity is secured (32.3), else `unresolved`.
    `employment_type` appears only where the advertisement states it.
    Counter-checks: intermediaries keep `recruiting_organization` and no
    employer; portals keep neither.
35. **No wrong LLM service or job types.** `GET
    /scoutro/api/v1/kg/llm-refusals` (samples since the start, redacted) and
    the counters of the LLM tier in `kg/status` name the reasons
    `entity_service_team_or_role`, `entity_service_not_offered` and
    `entity_job_not_advertised` where pages read after the upgrade had such
    entities (zero is a valid result). The new
    tier-3 evidence of such pages holds no service named as a team and no
    sidebar tool as a service. The query of 33.1 is read on a consistent copy
    and reported; nothing is deleted. The default prompt's hash changed
    (`llm_prompt_changed`) unless a custom prompt is active, and the LLM queue
    did not re-read done documents.
36. **LOGREPORT budget and safe diagnostics.** The next hourly run's call
    line shows `windowExceeded=false`, `maxTokensSent` and the omitted lines,
    and the run is answered. If a failure occurs, its line names `httpStatus`,
    `providerCode` and `providerMessage` (bounded). No line, report or status
    holds a prompt, a request body or a key.
37. **Quality check without graph writes.** Read only:
    - `GET /scoutro/api/v1/kg/llm-quality` shows `graphRunning`, test set 2 and
      evaluator 2, every row `unchecked`, and no key;
    - `GET …/llm-quality/plan?candidate=<the active knowledge row>` shows the
      requests, `calls.worstCase`, `tokens.worstCase`, and `cost.estimate`
      true with its note, without a chat request at the provider;
    - `DATA/SCOUTRO/llm-quality/` does not exist or is empty.

    Do **not** start a check: it causes cost at the provider and needs its own
    decision. The isolation is shown by `QualityCheckIsolationTest` (every
    graph table and `kg_meta` equal before and after a check beside a running
    graph), not in production.
38. **No automatic change of the active model.** `ai.production_models` (read
    without keys) and the row holding `knowledge` are the same before and
    after the upgrade, and after opening the LLM selection. In the browser, a
    click on another row's **knowledge** checkbox asks for a confirmation;
    *Cancel* leaves the matrix and the setting unchanged. Do not confirm.
39. **State preserved:** configuration, accounts, collections, data counts,
    WAL, LLM, crawl and GPU settings equal before and after (snapshot
    comparison); no data deleted.

C19 (24 hours of the same version) is a separate short follow-up after a
complete sampler window of 0.10.7. Browser and mobile evidence (C27) is
separate from API and synthetic smoke checks and must be reported as such.

## Candidate validation and later publication

As for 0.10.6: the release PR records the exact tested commit, the locally
built linux/amd64 image configuration ID, checks and limitations; a local
configuration ID is not a registry digest; skipped fork CI is not a passed
build. **The authorized merge** of the release PR changes `scoutro.properties`
and the workflow and therefore triggers the main `Publish Scoutro image`
workflow once: it refuses existing tags, then builds and pushes
`1.942-scoutro.31` and `0.10.7` with the actual merge revision. Do not dispatch
it in addition. After publishing, verify one immutable digest for both tags,
linux/amd64 and the merge revision label; the Olares package pins that digest.
