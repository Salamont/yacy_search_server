# Scoutro 0.11.8: object views without a timeout, the topics of a website, consultant titles as own capacity (one update from 0.11.7)

Prepared versions: **0.11.8 / 1.942-scoutro.42**. Numeric YaCy peer version **1.942** stays unchanged. A small release on the published 0.11.7 without a schema change, without a re-read and without a model call; each step after the merge is a separate decision of the operator.

| What | Value |
|---|---|
| Release | the suggestion read path builds only the shown page; the business view lists the website's topics; the own-capacity title pattern of the matching also reads titles with words between product and role |
| Source commit on `main` | `546c701022e6b562095d61830b04fac8a87fec68` (0.11.7, merge commit of #92) |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.42`, alias `0.11.8`, linux/amd64 |
| Registry digest | **open** until publication (the publish workflow refuses existing tags; `1.942-scoutro.42` and `0.11.8` are free) |
| Knowledge graph schema | **9, unchanged**; no migration, no copy, no re-read; `LlmExtractor.VERSION` 4, prompt hash `26b11cb61e4cfb42`, `jsonld/6`, `rule/7`, the vocabulary hash and `MatchingRules.VERSION` 1 unchanged |
| Discovery job store | schema **1, unchanged** |
| Correction state | schema **1, unchanged**; rules version 3, unchanged |
| Upgrade from | 0.11.7 / `1.942-scoutro.41` (`sha256:2ef9068cf20ba1b9d72036e619037affd5261cb754cc75dc9618990f3cad0585`) |

## Why

- **Object views ran into the read limit.** The suggestion read path built every matching contribution of an entity in full (its evidence with the archived quotes, both sides, the history) before it grouped, ordered and paged them. An entity with many contributions exceeded the read transaction's limit (`scoutro.kg.read.maxTransactionMillis`, 5 s) and the object view answered 503: STELLWERK with 10,644 contributions, the largest providers with up to 37,560.
- **Topics were invisible.** Since 0.11.4 (WP-F) a category a page names without an offer cue (no offer wording, no price, not on a services page) is a `topic` of the organisation, not a service. The business view did not show topics, so a company whose menu named its fields looked empty.
- **Consultancies looked like customers.** The own-capacity title pattern recognised "SAP-Berater" but not "SAP Senior Berater" or "Freiberuflicher SAP Consultant", so a consultancy's own postings counted as customer needs.

## Contents

- **A1 – read path** (`MatchingProjection`, `Suggestions`, `LeasedConnection`, `MatchingRules`): a read first validates every contribution with what grouping, deduplication and ordering need (score, keys, the other side, collections) and builds the full record only for the shown page and the first 25 reasons of a shown group. The observations an entity's contributions refer to, the posting pages of their sources, the shutdowns of their actors' systems and the other sides' live rows are each read in one query; a memo answers repeated rule checks within the read; prepared statements are reused within the read; the group of a reason is found by key. Every check of the former path still runs for every contribution (access chain, policy, actor, rule version, assertions, role, system and project changes, region). **The answer is unchanged**: the same groups, order, `total` and fields, tested against the former path.
- **A2 – topics of the website** (`BusinessView`, `knowledge.js`, help, German translation): the `topic` statements of an organisation are listed in their own section after the services, each with its label, sources and the mark **no proof of an offer**. A topic a shown service already carries is not repeated. New, additive API field `website_topics` of `GET …/kg/entities/{id}/business` (absent when there are none). Topics never feed the matching and never exclude a company from it.
- **A3 – own capacity by title** (`MatchingRules.ownCapacity`): words may stand between product and role ("SAP Senior Berater", "Freiberuflicher SAP Consultant", "Senior SAP FI/CO-Berater", "Revit BIM Trainerin"); every title the former pattern recognised still counts. An in-house or key-user title ("SAP Inhouse Consultant", "SAP In-House Berater", "SAP Key-User") is the employer's own need and stays a customer reason. `MatchingRules.VERSION` stays 1, so no stored contribution is invalidated: the pattern applies when contributions are read. Recorded customer relations (`customer_of`) are unchanged.
- Unchanged: the schema, the extraction, the rules of what a service is, the LLM tier, the matching computation, the correction automation, every setting.

## Load test (local, this release)

`SuggestionsLoadProbe` (test sources): one entity, its contributions computed by the real `MatchingService`, read as the object view reads it, three rounds of 25 and 100 groups, origin filtered to one collection.

| Case | Before (0.11.7 path) | 0.11.8 |
|---|---|---|
| 2,000 contributions sharing 10 signals | 2.7 s | 0.1–0.2 s |
| 40,000 contributions sharing 10 signals (like STELLWERK) | not measured (2.7 s at 2,000) | 1.4–2.0 s |
| 10,000 contributions, a different signal and actor each | about 5 s | 1.0–1.3 s |
| 40,000 contributions, a different signal and actor each | not measured (about 5 s at 10,000) | 3.8–5.4 s |

The last case is the extreme: every reason has its own quote, actor and page. What remains is the validation itself (re-reading each stored quote with the shared parser, reading 40,000 observations). The production objects are measured after the update (see below).

## Upgrade contract

1. **One start, no migration.** 0.11.8 opens the schema-9 graph as it is: no copy, no before-upgrade backup, no re-read, no model call.
2. **Configuration unchanged and not written.**
3. **Before the update:** the regular DATA backup from before the update is sufficient.

## Checks after the start

- Health `GET /scoutro/api/v1/health`: `scoutro` `1.942-scoutro.42`.
- `GET /scoutro/api/v1/kg/status`: `store.schemaVersion` 9, no `upgrade` of this start, `store.manualPause` as before.
- Object views and `GET …/kg/entities/{id}/suggestions?limit=25` of STELLWERK and of the three entities with the most contributions: answer time, no 503.
- `GET …/kg/entities/{id}/business` of STELLWERK and LIVAID: `website_topics` present where the home pages name categories.
- A consultancy with an "SAP Senior Berater" posting: its posting no longer gives a customer reason.

## Rollback to 0.11.7

Start the 0.11.7 image by digest on the same DATA: all schemas and settings are unchanged, nothing was written by 0.11.8 that 0.11.7 does not read. The object views then have the former read path again.

## Tests of this release

Local evidence only: `SuggestionsLazyTest` (every page, contribution page, business view and network of 16 organisations for five viewer pairs, including posting pages and a later shutdown, byte for byte against the former path), `MatchingTest` (+4: consultant titles with words between, in-house and key-user titles and skilled jobs of customers, a website topic is no offer), `BusinessViewTest` (+1: topics of a care operator and a consultancy, with sources, apart from services, not repeated), the UI test `test/scoutro-ui/knowledge-topics-ui-test.mjs` (EN/DE, phone and desktop), `SuggestionsLoadProbe` (above) and the regression of `tools/scoutro/ci/scoutro-checks.sh`. An upgrade test with the real images is open until the image is published.

## References

- Previous release notes: [0.11.7](SCOUTRO_RELEASE_0.11.7.md), [0.11.6](SCOUTRO_RELEASE_0.11.6.md)
- [Evidence-based service matching](SCOUTRO_MATCHING.md)
