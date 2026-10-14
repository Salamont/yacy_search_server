# Scoutro 0.10.8 patch release

Prepared versions: **0.10.8** and **1.942-scoutro.32**, intended aliases of the
same immutable registry image. The numeric YaCy peer version remains **1.942**.
Basis: main `3262d228d9e9ebb6545df0e90809e976e7bd5f14` (merge of #71 on 0.10.7 /
1.942-scoutro.31, `efdab25`; its tree equals the reviewed head `5f3f4dd`).
Installed privately on Olares (operator's rollout report, 2026-10-14): 0.10.7 /
1.942-scoutro.31,
`ghcr.io/salamont/scoutro@sha256:dd4d6081e9530b2d4f5d01f707f1d6d79a02a22c1805c769d2446391d42f36a9`,
revision `efdab25`, package commit `55f01e2`. Its tags stay as they are.

User-facing changes: [CHANGELOG](../CHANGELOG.md). Everything of 0.10.7 and the
releases before applies unchanged unless said here:
[SCOUTRO_RELEASE_0.10.7.md](SCOUTRO_RELEASE_0.10.7.md),
[SCOUTRO_RELEASE_0.10.6.md](SCOUTRO_RELEASE_0.10.6.md),
[SCOUTRO_RELEASE_0.10.5.md](SCOUTRO_RELEASE_0.10.5.md),
[SCOUTRO_RELEASE_0.10.4.md](SCOUTRO_RELEASE_0.10.4.md),
[SCOUTRO_RELEASE_0.10.3.md](SCOUTRO_RELEASE_0.10.3.md),
[SCOUTRO_RELEASE_0.10.2.md](SCOUTRO_RELEASE_0.10.2.md),
[SCOUTRO_RELEASE_0.10.1.md](SCOUTRO_RELEASE_0.10.1.md). The access, cookie and
sign-in limit contract of 0.10.0 is unchanged:
[SCOUTRO_RELEASE_0.10.0.md](SCOUTRO_RELEASE_0.10.0.md).

## Why a patch: what the first quality checks showed

The operator checked several candidate models with the quality check of 0.10.7
(evaluation 2). The stored results showed two gaps:

- **The baseline depended on the answer.** Evaluation 2 counted an expected
  statement as given by the rules only when the model had no hit for it. A
  model that repeated a rule statement had it counted as its own: the own job
  advertisement's `employment_type full_time` (the rules read "in Vollzeit")
  was expected 1 / given 2 in one stored run and 2 / 1 in another. Results of
  different models were therefore not split alike.
- **A missing statement could not be traced.** The result kept counts, not
  the answer and the validator's decision on each item. "Not stated by the
  model" was shown without evidence that the model had not stated it.

## What changes against 0.10.7

Only the quality check of the LLM selection (#71). Details:
[SCOUTRO_LLM_QUALITY_CHECK.md, 4 and 4.3](SCOUTRO_LLM_QUALITY_CHECK.md).

- **Evaluation 3: a fixed baseline.** The expected statements the rules give
  are fixed from tiers 1 and 2 of each reference page before the answer is
  read. A model's repetition of a rule statement is counted apart
  (`repeatsRules`) and not rated. The test set stays `kg-reference` version 2.
- **Diagnosis 1.** Each reference page keeps, in the check's own result only:
  - the model's answer (at most 8,000 characters per request, 320,000 per run);
  - whether the answer was readable and whether the model cut it at its output cap;
  - what the validator accepted, and every item it dropped with its reason and
    its position in the answer;
  - what the publication would take (at most 40 statements).

  Cut or omitted parts are listed (`complete`, `incomplete`). The knowledge
  graph stores no answer; the queue keeps its few redacted refusal samples as
  before.
- **Where a missing statement went.** The reason of exactly that item, matched
  by its position in the answer, never by its quote; when no single sample
  matches, `reasonAttributed` is false. Shown apart: the statement, the
  verbatim quote, the names the validator requires in it, the types and the
  assignment. *Stated otherwise* when the model gave another value. *Not
  stated* only when every answer was readable and none was cut; otherwise
  *cause not traceable*.
- **No key in any field.** Every string of a new result, field names
  included, is scrubbed of the candidate's key, bearer tokens and key-like
  strings before it is stored, and again when the API shows a stored or an
  earlier result.
- **Earlier results stay.** The next check of a candidate moves the result
  before it unchanged to `DATA/SCOUTRO/llm-quality/archive/`; the result lists
  the earlier ones. The page shows an evaluation-2 result as stale (reason
  `evaluator`) and its *not stated* as *cause not traceable*.
- **Validator samples.** Every dropped item's sample carries its position
  (`index`). Two drops that had none (`claim_extra_field`, `value_extra_field`
  and their `…_not_object` forms) now keep one. The validator's decisions, its
  reasons, the prompt, the schema and the model handling are unchanged.

Schema 6, the extractor identity of tiers 1 and 2 (JSON-LD 5, rules 6), the
relation rules 5, the LLM extractor version (`llm/4`), the compiled-in default
prompt and its hash, and every setting stay. The upgrade writes no setting and
no `kg_meta` key.

## Upgrade from the installed 0.10.7

1. **Nothing is migrated.** The sync queue, the LLM queue, its cache and every
   setting are kept, and `ai.production_models` is unchanged.
2. **No re-check, no re-extraction.** `sync.relationRecheckInfo` stays rules 5,
   scope `jobs`, with its `done`. No `llm_prompt_changed` event: the prompt hash
   is the same. The upgrade causes no LLM request and no crawl.
3. **Stored quality results are kept as they are.** Each file in
   `DATA/SCOUTRO/llm-quality/` is only read: its bytes stay. `GET
   /scoutro/api/v1/kg/llm-quality` shows `evaluator` 3 for the test set, and
   each candidate checked before with `evaluator` 2, `stale` true and
   `staleReasons` including `evaluator`, `earlierResults` 0. No `archive/`
   directory appears until an administrator starts a new check.
4. **Nothing runs on its own.** A new check of a candidate sends paid requests
   to its endpoint and needs its own decision with the candidate, the price
   and the cost limit named.

**From 0.10.6 or older** (elsewhere): the steps of 0.10.7 come first.

## Rollback

The primary return point is a current, consistent full DATA/configuration
backup with checksums and an isolated restore, plus the 0.10.7 digest above.
No lossless downgrade is claimed.

- **Back to 0.10.7:** same schema, settings, prompt and rules; nothing is
  re-checked.
  - 0.10.7 reads a result of evaluation 3 as stale (`evaluator`) and ignores
    its diagnosis and its `repeatsRules`.
  - It ignores `archive/`; a check run under 0.10.7 replaces the candidate's
    result without archiving the one before.
  - Validator samples lose their `index` again.
- **Further back:** the rollback notes of 0.10.7 apply.

## Acceptance (private Olares instance)

Steps 1–19 of [0.10.3](SCOUTRO_RELEASE_0.10.3.md#acceptance-private-olares-instance)
with the additions of [0.10.4](SCOUTRO_RELEASE_0.10.4.md#acceptance-private-olares-instance),
steps 20–21 of [0.10.5](SCOUTRO_RELEASE_0.10.5.md#acceptance-private-olares-instance),
steps 26–31 of [0.10.6](SCOUTRO_RELEASE_0.10.6.md#acceptance-private-olares-instance)
and steps 33, 35, 36, 38 and 39 of [0.10.7](SCOUTRO_RELEASE_0.10.7.md#acceptance-private-olares-instance)
apply, with the 0.10.8 digest and version `1.942-scoutro.32`. Step 33 is read
only: the rules-5 check stays done, and nothing is extracted again. Step 37 of
0.10.7 is replaced by:

40. **Quality check: evaluation 3, earlier results kept, nothing run.** Read only:
    - Before the upgrade, list `DATA/SCOUTRO/llm-quality/` with the sha256 of
      every file. After the upgrade, the same files have the same bytes, and
      there is no `archive/`.
    - `GET /scoutro/api/v1/kg/llm-quality` shows `graphRunning`, test set 2 and
      evaluator 3. Each candidate checked before shows `evaluator` 2, `stale`
      true with `evaluator` among `staleReasons`, and `earlierResults` 0. No
      key appears.
    - `GET …/llm-quality/result?candidate=<a candidate checked before>` shows
      the stored result with the note of the earlier evaluation, without a
      diagnosis and without a key.
    - `GET …/llm-quality/plan?candidate=<the active knowledge row>` shows the
      requests and `cost.estimate` true, without a chat request at the
      provider.

    Do **not** start a check: it causes cost at the provider and needs its own
    decision. The baseline, the diagnosis, the attribution by position, the
    scrubbing and the archive are shown by `QualityBaselineTest`,
    `QualityDiagnosisTest` and `ModelQualityCheckTest` with mock models, not
    in production.

C19 (24 hours of the same version) is a separate short follow-up after a
complete sampler window of 0.10.8. Browser and mobile evidence (C27) is
separate from API and synthetic smoke checks and must be reported as such.

## Candidate validation and later publication

As for 0.10.7: the release PR records the exact tested commit, the locally
built linux/amd64 image configuration ID, checks and limitations; a local
configuration ID is not a registry digest; skipped fork CI is not a passed
build. **The authorized merge** of the release PR changes `scoutro.properties`
and the workflow and therefore triggers the main `Publish Scoutro image`
workflow once: it refuses existing tags, then builds and pushes
`1.942-scoutro.32` and `0.10.8` with the actual merge revision. Do not dispatch
it in addition. After publishing, verify one immutable digest for both tags,
linux/amd64 and the merge revision label; the Olares package pins that digest.
