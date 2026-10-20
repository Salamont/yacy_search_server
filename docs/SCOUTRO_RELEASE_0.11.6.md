# Scoutro 0.11.6: plausibility of a domain's collection (one update from 0.11.5)

Prepared versions: **0.11.6 / 1.942-scoutro.40**. Numeric YaCy peer version **1.942** stays unchanged. One update on the published 0.11.5; each step after the merge is a separate decision of the operator.

| What | Value |
|---|---|
| Release | the plausibility of a domain's collection: rule signals without a model, a step-wise scan of the domains indexed before, and the collection fit of a home page asked in the knowledge graph's own LLM request (off by default) |
| Source commit on `main` | `e45d21dabe203adae92abe7bdeda28ddaaa5b33f` (0.11.5, merge commit of #88) |
| Feature commits | rules without a model (PR 1), the scan of the indexed domains and the assessments (PR 2), the home page reading in the LLM request (PR 3) |
| Release commit | version files and release notes only |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.40`, alias `0.11.6`, linux/amd64 |
| Registry digest | **open** until publication (the publish workflow refuses existing tags; `1.942-scoutro.40` and `0.11.6` are free) |
| Knowledge graph schema | **9, unchanged**; no migration, no copy, no re-read; `LlmExtractor.VERSION` 4, prompt hash `26b11cb61e4cfb42`, `jsonld/6`, `rule/7` and the vocabulary hash unchanged |
| Discovery job store | schema **1, unchanged** |
| Correction state | `DATA/SCOUTRO/collections/autocorrect.json` schema **1, unchanged**; new sections `assessments` and `scan` are written only by the scan or a home page reading; rules version 2 -> 3 |
| Solr | fields and collections unchanged |
| Upgrade from | 0.11.5 / `1.942-scoutro.39` (`sha256:60b14e03b60d558fbc440a4edd1476a1d745cf5683c607700945f4ab8ec847cc`) |

## Contents

- **Rules without a model** ([SCOUTRO_COLLECTION_AUTOCORRECT.md](SCOUTRO_COLLECTION_AUTOCORRECT.md), [help](../help/ScoutroCollections_p.md)): text fit 2 counts no role word ("Hersteller", "Händler", ...) for a vocabulary; the website signal `plausibility` (`plausibility/1`) speaks against the current collection only with at least three services on at least two pages with structured or quoted evidence, none in the current vocabulary, and no offer or topic of that vocabulary on the pages. It is the website's origin: never a correction by itself, at most a case. A case for review shows a suggested target, preselected and only a hint.
- **Plausibility scan** (administrator, API only): `POST /scoutro/api/v1/collections/autocorrect/scan` assesses the domains indexed before in steps of up to 200, only while the graph runs, is not paused and its gates are open; no model, no write to the graph. Two fingerprints per domain and collection: a domain is read again only when its pages, the vocabularies, the rules or its decisions change, and assessed again when a Discovery classification, a home page reading or an OSM signal changes. One submission per domain through the unchanged rules. `GET .../assessments` lists what each source found (`rules`, `discovery`, `kg_home`). `scoutro-discovery classify --domain-file` classifies exactly the uncertain domains, followed by a targeted scan (`"domains"`).
- **Home page reading** (`scoutro.kg.llm.collectionFit`, **off by default**; only with `scoutro.kg.llm.structuredOutput` `none` or `json_object`): the first request of a home page also asks, in at most 500 characters (about 150 tokens), whether the website fits its collections. No request of its own; the system prompt, the schema, the extractor version and the prompt hash are unchanged, every other request and cache key byte for byte as before. A verdict per assigned collection counts only with a verbatim quote of the page and only while the page and the profiles' criteria are unchanged; it is recorded as the source `kg_home`, never a statement or evidence of the graph. Counters `llm.processed.collectionFit` (asked, valid, missing, invalid, unquoted, verdicts, measured tokens with and without the question, refusals with and without it).

## Upgrade contract

1. **One start, no migration.** 0.11.6 opens the schema-9 graph as it is: no `quick_check` copy, no before-upgrade backup, no `reextract_pending`, no re-read, no model call. The prompt hash is unchanged, so no page is read again by the LLM tier.
2. **Configuration unchanged.** `scoutro.collections.autocorrect.mode` stays as installed (`off` in production): the scan then assesses but submits nothing. `scoutro.kg.llm.collectionFit` is new and `false`: no request changes until an operator switches it on. Protective settings of 0.11.5 keep their values; the knowledge graph's manual pause and the paused LLM queue are read as stored.
3. **Correction state.** The rules version is 3: an acceptance recorded under rules version 2 is outdated (`acceptance_outdated`), so `apply` needs a new random sample and acceptance. In production no acceptance exists and the mode is `off`.
4. **Before the update:** the regular DATA backup from before the update is sufficient (no data changes at the start).
5. **Disk:** no growth from the upgrade itself. The assessments are bounded (at most 20 000 pairs of domain and collection).

## Checks after the start

- Health `GET /scoutro/api/v1/health`: `scoutro` `1.942-scoutro.40`.
- `GET /scoutro/api/v1/kg/status`: `store.schemaVersion` 9, no `upgrade` of this start, `sync.lag.reconcile_pending` false, `llm.processed.collectionFit.asked` 0 while the setting is off, `store.manualPause` as before; `GET /scoutro/api/v1/kg/backups`: no new `before_upgrade` item.
- `GET /scoutro/api/v1/collections/autocorrect`: the mode as installed; `GET .../assessments`: empty until the first scan.

## Using it (each step a decision of the operator)

1. Mode `simulate`, then `POST .../scan {"maxDomains": 50}` repeatedly (or with `"collection"`); read `GET .../assessments?result=suspect` and `?result=uncertain`.
2. Uncertain domains: export, classify a sample of 50 with `scoutro-discovery classify --domain-file ... --max 50` (`--dry-run` first), then `POST .../scan {"domains": [...]}` with the same list.
3. New domains: `scoutro.kg.llm.collectionFit=true` with `scoutro.kg.llm.structuredOutput` `none` or `json_object`, at the next start of the graph; watch `llm.processed.collectionFit` (share of `valid`, tokens with the question, refusals with and without it) before relying on it.
4. `propose`; approvals per case (the suggested target is preselected); `apply` only after the existing acceptance.

## Rollback to 0.11.5

0.11.5 opens schema 9, job store schema 1 and the correction state schema 1 unchanged: start the 0.11.5 image by digest on the same DATA. It keeps the `assessments` and `scan` sections of the correction state as they are (it reads and writes the whole object) and does not use them; an acceptance recorded under rules version 3 is outdated there as well. The setting `scoutro.kg.llm.collectionFit` is ignored by 0.11.5. Cached LLM answers of home pages asked with the question have their own cache keys and are never taken by 0.11.5. No data is lost in either direction.

## Tests of this release

Local evidence only: `tools/scoutro/ci/scoutro-checks.sh all` (every offline JUnit target and the Python suites), `PlausibilityTest`, `PlausibilityScanTest`, `TextFitTest`, `DrolshagensPilotTest`, `AutoCorrectTest` and `CollectionDecisionsApiTest` (`scoutro-collection-decisions-test`), `CollectionFitTest` (`scoutro-kg-llm-schedule-test`; the system prompt, schema and prompt hash of 0.11.5, every other request byte for byte), `test_discovery.py` (`--domain-file`) and `collections-autocorrect-ui-test.mjs`. An upgrade test with the real images is open until the image is published (no schema change; the rollback is covered by the unchanged state schemas).

## References

- Previous release notes: [0.11.5](SCOUTRO_RELEASE_0.11.5.md), [0.11.4](SCOUTRO_RELEASE_0.11.4.md)
- Development order: "Collection-Plausibilitätsprüfung", Fassung 3 (K1–K7), outside the repository
