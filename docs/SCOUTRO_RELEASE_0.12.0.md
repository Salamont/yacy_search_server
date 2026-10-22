# Scoutro 0.12.0: services back, customers and competitors apart (one update from 0.11.7, including 0.11.8)

Prepared versions: **0.12.0 / 1.942-scoutro.43**. Numeric YaCy peer version **1.942** stays unchanged. A release with a
one-time re-read of every tracked page by the rules (no model) and a targeted, bounded re-read of some pages by the LLM tier.
It takes the full release path: a DATA backup with an isolated restore test before the update. Each step after the merge is a
separate decision of the operator.

| What | Value |
|---|---|
| Release | the offer proof rule/8 (consulting sentences, structural evidence), the LLM tier's title proof and candidates, the targeted LLM re-read of services pages without a service, possible competitors on demand, the kind of evidence of every reason, support sought for an own migration as a need |
| Source commit on `main` | **open** (on 0.11.8, merge `6ad15e85f91b87e8d23789ee7f50a5dc954bd591` of #93) |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.43`, alias `0.12.0`, linux/amd64 |
| Registry digest | **open** until publication |
| Knowledge graph schema | **9, unchanged**. New: the partial index `kg_statement_category`, created at the start if missing (`CREATE INDEX IF NOT EXISTS`); an older version ignores it |
| Extractors | `rule/8` (was 7): a new extractor identity, so every tracked page is re-extracted by tiers 1 and 2. `LlmExtractor.VERSION` 4, the prompt hash, `jsonld/6`, the vocabulary hash and `MatchingRules.VERSION` 1 unchanged |
| Discovery job store | schema **1, unchanged** |
| Correction state | schema **1, unchanged** |
| Upgrade from | 0.11.7 / `1.942-scoutro.41` (`sha256:2ef9068cf20ba1b9d72036e619037affd5261cb754cc75dc9618990f3cad0585`), installed in production; 0.11.8 / `1.942-scoutro.42` is published but not installed (owner decision of 22 October 2026: one update to 0.12.0). The update includes everything of [0.11.8](SCOUTRO_RELEASE_0.11.8.md) |

## Why

- **Services were gone.** The stricter offer proof of 0.11.4 to 0.11.7 kept a service only with an offer sentence, a heading
  such as "Leistungen:" or a price. Production on 21 October 2026: 6,669 organisations with services, against 10,624 before;
  62,025 of the 63,572 former services had come from the rules. Own sites that name what they do in their title or brand
  line, a list, the page of one service or a consulting sentence ("Wir unterstützen Sie bei der Einführung von SAP S/4HANA")
  showed topics only. The LLM tier's proof had the same gap.
- **Customers and competitors were mixed.** A consultancy hiring consultants for its own offer looked like a customer of
  other consultancies (0.11.8 corrected the titles); there was no list of competitors, and the reasons did not say whether
  they rested on a need, a use or a job requirement.

## Contents

- **C1, offer proof rule/8** (`ServiceEvidence`, `BusinessRules`, `RuleExtractor`): explicit as before, plus consulting and
  delivery sentences with the provider as subject; structural (confidence 0.4, marked "structural evidence" in the company
  view and `offer_evidence: "structural"` in the business view): the title or brand line of the home page, a services page or
  the page of one service, a bulleted item. A bare menu needs a second sign of the site. Tools and own use, denials, news,
  vacancies, the own past introduction of a system and a client's system on a reference page stay topics.
- **C2, LLM tier** (`LlmExtractor`, `LlmReading`, `ExtractContext`): a service named by the title of its own page is accepted
  with the title as its quote; the site's operator offers it when the answer names no provider. Pages of single services are
  candidates. Prompt, schema and `LlmExtractor.VERSION` unchanged.
- **C3, after the update** (`LlmService`, `LlmQueue`): pages skipped as `not_candidate` are decided once more by the new
  candidate rule; services pages the LLM tier read without an accepted service are asked once more without the cache
  (`llm_reason` `service_recheck`, once per graph). Both go through the ordinary LLM queue, schedule and budget.
- **B1, possible competitors** (`Competitors`, `KnowledgeRead`, `knowledge.js`): read per category the organisation offers
  itself, a page at a time (`GET …/kg/entities/{id}/competitors?category&cursor&limit`, grant `kg.read`, action
  `kg.entity.competitors`), one line per offer of another organisation with its service and up to three visible sources. The
  business view names only the categories (`competitors.categories`, `path`). Derived at reading: no stored derivation, no
  fact, no matching input, **not part of the export, the download or the change feed**.
- **B2, customers per service and reason** (`BusinessSignals`, `MatchingProjection`, `knowledge.js`): every reason names its
  kind of evidence (`evidence_kind`: need, use, job requirement); support sought for an own migration reads as a planned
  migration. Customer and competitor are not exclusive. `MatchingRules.VERSION` stays 1: every correct suggestion of today
  stays visible.
- Details: [knowledge graph, section 36](SCOUTRO_KNOWLEDGE_GRAPH.md#36-offers-back-customers-and-competitors-apart-0120),
  [matching](SCOUTRO_MATCHING.md).

## Quality gates (before the merge, production data read only)

Run on Olares without deployment (package repository, `docs/OPENCODE-GATES-0.12.0.md`). Results: **open**.

| Gate | Target | Result |
|---|---|---|
| T0 reference set | ≥ 50 companies per collection (bauteamcheck, stackfinder, edelsenior, checkthecoach) with real offers and counterexamples; 30 cases checked by the owner | phase 1 (22 October 2026): 200 domains, 586 offers (568 with a category), counterexamples mostly navigation; owner check open |
| T1 recall | ≥ 80 % per collection | rule/7 baseline: 51.9 % (bauteamcheck), 28.9 % (checkthecoach), 57.6 % (edelsenior), 68.2 % (stackfinder); rule/8: open (phase 2) |
| T2 precision | ≥ 85 % per collection; counterexamples stay topics | rule/7 baseline: 98.8–100 %, no counterexample offered; rule/8: open (phase 2) |
| T3 protection cases | the tool "CI/CD", "TYPO3 CMS", other fields, navigation "Kontakt/Karriere": no return | tests pass (`OfferEvidenceRule8Test`); sample open |
| T4 LLM | STELLWERK's SAP DRC, Fast Close, Cash Management accepted (≤ 40 requests) | open |
| T5 effort | the new LLM candidates and the services pages asked again (count), the re-read's duration | open |
| T6 customer/competitor | the plan's table as a test; 50 of today's pairs judged | tests pass; of 50 of today's customer suggestions 7 were customers, 34 providers of the product itself, 5 own capacity, 4 not assignable: round 1 of the rules (reading of providers' sentences, explicit own use of providers); the pairs read with 0.12.0: open (phase 2) |

Model budget of all test runs up to 0.12.0: at most 300 requests, at most 40 per run.

## What the update does

1. **The start:** the partial index is created (a few seconds per million statements, measured: **open**); the LLM tier marks
   the `not_candidate` pages and the services pages without a service to do (events `llm_candidates_changed`,
   `llm_service_recheck`).
2. **Re-extraction by the rules:** every tracked page, at low priority behind new pages, paused like every enrichment
   (`kg_meta.reextract_pending`; a stop repeats it at the next start). Duration on Olares: **open** (T5: time per page of the
   probe × tracked pages). Topics the new proof shows become services again; industries and matching follow.
3. **LLM requests:** the services pages asked again and the new candidates, within the configured schedule and budget.
   Number and cost: **open** (T5).

## Upgrade contract

1. **Before the update:** no backup on the device and no restore test (owner decision of 22 October 2026: the update is
   installed directly). The return path is therefore the rollback below (a re-extraction by 0.11.7), not the exact state
   before the update. The 21-point smoke test and the upgrade test run with the published image beforehand, off the
   device.
2. **One start, no migration, no copy.** Configuration unchanged and not written.
3. The re-extraction and the LLM requests run in the background; the views answer meanwhile.

## Checks after the start

- Health `GET /scoutro/api/v1/health`: `scoutro` `1.942-scoutro.43`.
- `GET /scoutro/api/v1/kg/status`: `store.schemaVersion` 9; the re-extraction pending and progressing; the events
  `llm_candidates_changed` and `llm_service_recheck` once.
- After the re-extraction: LIVAID and STELLWERK show their services; STELLWERK is listed among the possible competitors of
  another SAP consultancy and is no customer suggestion for its own offer; no 503.
- Observation only, no acceptance criterion: organisations with services (before 10,624, on 21 October 2026 6,669).

## Rollback to 0.11.7

No backup is taken before the update (owner decision), so there is no restore to the exact state before it. Start the
0.11.7 image by digest (above) on the same DATA. The schema is unchanged; 0.11.7 ignores the partial index and
the new `kg_meta` entries (tested locally with the classes of 0.11.7 and 0.11.8: they open, write and check a graph with
the index). The views lose the read path and the website topics of 0.11.8. **The extractor identity changes back to
rule/7, so 0.11.7 re-extracts every page again** and the services shown only by the new proof become topics again.
The LLM evidence of pages asked again stays (tier 3 is not corrected by a re-extraction); it is the only lasting difference
to the state before the update.

## Tests of this release

Local: `OfferEvidenceRule8Test`, `ServiceOfferPublicationTest`, `LlmServiceTest` (the new candidate rule, the services pages
asked once more without the cache), `BusinessSignalsTest`, `MatchingTest` (the T6 table, the kind of evidence),
`BusinessViewTest` (competitors, the query plan through the partial index), `KnowledgeApiTest`, `AgentCatalogTest`, the UI
tests `knowledge-competitors-ui-test.mjs` and `knowledge-matching-ui-test.mjs`, and the regression of
`tools/scoutro/ci/scoutro-checks.sh`. Gates on production data: above.

## References

- Previous release notes: [0.11.8](SCOUTRO_RELEASE_0.11.8.md)
- [Knowledge graph, section 36](SCOUTRO_KNOWLEDGE_GRAPH.md)
- [Evidence-based service matching](SCOUTRO_MATCHING.md)
