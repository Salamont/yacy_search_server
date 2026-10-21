# Scoutro 0.11.7: the collection fit question of a home page, corrected (one update from 0.11.6)

Prepared versions: **0.11.7 / 1.942-scoutro.41**. Numeric YaCy peer version **1.942** stays unchanged. A minimal correction on the published 0.11.6; each step after the merge is a separate decision of the operator.

| What | Value |
|---|---|
| Release | the question of the home page reading (`scoutro.kg.llm.collectionFit`) shows the answer's shape as valid JSON; nothing else |
| Source commit on `main` | `f431c981c3c06c7acabd9314487813c7bc39b8cc` (0.11.6, merge commit of #91) |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.41`, alias `0.11.7`, linux/amd64 |
| Registry digest | **open** until publication (the publish workflow refuses existing tags; `1.942-scoutro.41` and `0.11.7` are free) |
| Knowledge graph schema | **9, unchanged**; no migration, no copy, no re-read; `LlmExtractor.VERSION` 4, prompt hash `26b11cb61e4cfb42`, `jsonld/6`, `rule/7` and the vocabulary hash unchanged |
| Discovery job store | schema **1, unchanged** |
| Correction state | schema **1, unchanged**; rules version 3, unchanged |
| Upgrade from | 0.11.6 / `1.942-scoutro.40` (`sha256:c5133490dad10c782e007d25e875c949c341d962e31f1ce17b036bea6df9efe7`) |

## Why

The production test of 0.11.6 switched `scoutro.kg.llm.collectionFit` on and asked 13 home pages: all 13 answers were `fit_invalid`. The question of 0.11.6 showed the answer's shape as invalid JSON (`{"verdicts":{id:"match|mismatch|uncertain"},"best":id|"none",…}`); the model copied the placeholder key `id`, which the check refuses as an id not asked. A probe with the production model, outside the application and without any change to production, asked the corrected question on 10 home pages: **10 of 10 valid**, no refusal of the extraction.

## Contents

- **Question version 2** ([SCOUTRO_COLLECTION_AUTOCORRECT.md](SCOUTRO_COLLECTION_AUTOCORRECT.md#home-page-reading-in-the-llm-tiers-own-request)): the answer's shape as valid JSON with the real assigned ids and the allowed values named once, `Use exactly: "collection_fit":{"verdicts":{"<id>":"<verdict>",...},"best":"<id or none>","quote":"<verbatim page text, max 200 chars>"}, each <verdict> one of match, mismatch, uncertain (unclear: uncertain)`. Still at most 500 characters: the assigned collections' topics are shortened as the length needs (to 15 characters, else ids only), the other profiles are named as far as they fit. The question is identical to the one of the probe.
- **The criteria of a reading** are the profiles' criteria version and the question's (`<criteria_version>/q2`): a reading recorded with the question of 0.11.6 is outdated, gives no signal (it gave none: every verdict was `uncertain`) and is asked again when its home page is read again.
- **Counters** of `llm.processed.collectionFit`: new `questionVersion` and `invalidReasons` (`not_object`, `verdicts_not_object`, `unknown_key`), so an invalid shape is named without storing the answer.
- Unchanged: the check of the field (an id not asked makes it invalid; without a verbatim quote every verdict is `uncertain`; `best` a hint only), the system prompt, the schema, the extraction, the request budget's limit, every request without the question byte for byte, the rules and the modes.

## Upgrade contract

1. **One start, no migration.** 0.11.7 opens the schema-9 graph as it is: no copy, no before-upgrade backup, no re-read, no model call. The prompt hash is unchanged, so no page is read again by the LLM tier.
2. **Configuration unchanged and not written.** `scoutro.kg.llm.collectionFit` keeps its value; with `false` no request changes. The correction mode stays as installed (`off` in production).
3. **Before the update:** the regular DATA backup from before the update is sufficient.

## Checks after the start

- Health `GET /scoutro/api/v1/health`: `scoutro` `1.942-scoutro.41`.
- `GET /scoutro/api/v1/kg/status`: `store.schemaVersion` 9, no `upgrade` of this start, `llm.processed.collectionFit.questionVersion` `2`, `asked` 0 while the setting is off, `store.manualPause` as before.
- `GET /scoutro/api/v1/collections/autocorrect`: the mode as installed.

## Activation (a decision of the operator, after the update)

`scoutro.kg.llm.collectionFit=true` with `scoutro.kg.llm.structuredOutput` `none` or `json_object`, read at the next start of the graph. Only home pages the LLM tier reads (new ones, or changed content) are asked. Watch `llm.processed.collectionFit` over the first 5 to 10 questions: the share of `valid`, `invalidReasons`, refusals with and without the question, tokens.

## Rollback to 0.11.6

Switch `scoutro.kg.llm.collectionFit` off first, then start the 0.11.6 image by digest on the same DATA: all schemas are unchanged. 0.11.6 compares readings without the question's version, so a reading of 0.11.7 is outdated there and gives no signal; with the setting on it would ask its own question again, which the production model does not answer validly. Cached answers have their own cache keys per question. No data is lost in either direction.

## Tests of this release

Local evidence only: `CollectionFitTest` (the question's shape is valid JSON for one to three assigned collections, filled in the way the probe's answers were; the shape of 0.11.6 gives `fit_invalid` with `unknown_key`; the counters and the criteria with the question's version; every other request byte for byte), `PlausibilityScanTest` and the regression of `tools/scoutro/ci/scoutro-checks.sh`. An upgrade test with the real images is open until the image is published.

## References

- Previous release notes: [0.11.6](SCOUTRO_RELEASE_0.11.6.md), [0.11.5](SCOUTRO_RELEASE_0.11.5.md)
