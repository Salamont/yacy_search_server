# Scoutro 0.12.0: services back, customers and competitors apart (one update from 0.11.7, including 0.11.8)

Prepared versions: **0.12.0 / 1.942-scoutro.43**. Numeric YaCy peer version **1.942** stays unchanged. A release with a
one-time re-read of every tracked page by the rules (no model) and a targeted, bounded re-read of some pages by the LLM tier.
No backup is taken on the device before the update (owner decision of 22 October 2026, see the upgrade contract). Each step
after the merge is a separate decision of the operator.

| What | Value |
|---|---|
| Release | the offer proof rule/8 (consulting sentences, structural evidence), the LLM tier's title proof and candidates, the targeted LLM re-read of services pages without a service, possible competitors on demand, the kind of evidence of every reason, support sought for an own migration as a need |
| Source commit on `main` | **open** (on 0.11.8, merge `6ad15e85f91b87e8d23789ee7f50a5dc954bd591` of #93) |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.43`, alias `0.12.0`, linux/amd64 |
| Registry digest | **open** until publication |
| Knowledge graph schema | **9, unchanged**. New: the partial index `kg_statement_category`, created at the start if missing (`CREATE INDEX IF NOT EXISTS`); an older version ignores it |
| Extractors | `rule/8` (was 7): a new extractor identity, so every tracked page is re-extracted by tiers 1 and 2. The vocabulary changes too (software: the bare "quality assurance" removed, gate T0); its version is part of the same identity, so the same re-extraction covers it. `LlmExtractor.VERSION` 4, the prompt hash, `jsonld/6` and `MatchingRules.VERSION` 1 unchanged; cached LLM answers stay valid |
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
  kind of evidence (`evidence_kind`: need, use, job requirement, reference); support sought for an own migration reads as a
  planned migration. Customer and competitor are not exclusive. The clients a provider documents (project profile,
  reference heading, customer's voice) are `customer_of` relations with source, project and state; their purchase is a
  reason of the kind `reference` for the other providers of the service. `MatchingRules.VERSION` stays 1: every correct suggestion of today
  stays visible.
- Details: [knowledge graph, section 36](SCOUTRO_KNOWLEDGE_GRAPH.md#36-offers-back-customers-and-competitors-apart-0120),
  [matching](SCOUTRO_MATCHING.md).

## Quality gates (before the merge, production data read only)

Run on Olares without deployment (package repository, `docs/OPENCODE-GATES-0.12.0.md` and the phases after it). Results below; the T0 corrections in [gate T0](SCOUTRO_GATE_T0_0.12.0.md). Open: the owner's review of the replaced T0 case and the confirmation of the final commit on Olares (phase 3d: T1/T2 on the live pages, T6 on the pairs).

| Gate | Target | Result |
|---|---|---|
| T0 reference set | ≥ 50 companies per collection (bauteamcheck, stackfinder, edelsenior, checkthecoach) with real offers and counterexamples; 30 cases checked by the owner | phase 1 (22 October 2026): 200 domains, 586 offers (568 with a category), counterexamples mostly navigation. Owner review of 22 October 2026: 22 corrections (a wrong offer 7, a wrong category 2, an unsuitable quote 5, a missing offer 7, one firm on two hosts), recorded with their reasons in [gate T0](SCOUTRO_GATE_T0_0.12.0.md); the former verdicts are kept, the corrected set has 199 domains; the replacement of the duplicate case is open for the owner |
| T1 recall | ≥ 80 % per collection | rule/7: 51.9 % (bauteamcheck), 28.9 % (checkthecoach), 57.6 % (edelsenior), 68.2 % (stackfinder); rule/8 phase 2: 76.9 / 75.9 / 85.3 / 79.1 %; round 2 (rows of headings, one operator named twice, links in lists), **confirmed on Olares** (phase 3c, the live pages): 85.0 / 83.1 / 89.8 / 84.5 %; on the **corrected reference** (T0): **84.8 / 82.5 / 89.9 / 84.1 %** (Olares, `5c311fa`; unchanged by the corrections of the code, local) |
| T2 precision | ≥ 85 % per collection; counterexamples stay topics | rule/7: 98.8–100 %; rule/8 phase 2: 98.5–100 %, no counterexample offered; round 2 on Olares: of the judged services 99.3 / 99.0 / 100 / 98.6 %; the 13 new ones judged (9 offers; a product feature, an event teaser and two lines of a product's integration list are none): 99.3 / 99.0 / 100 / 97.2 %. On the corrected reference: 98.6 / 98.9 / 100 / 95.0 % (Olares, `5c311fa`: the correction of the reference alone); with the general corrections of the rules (application areas, partner networks, participles, English word forms, news titles, vocabulary): **98.9 / 98.9 / 100 / 96.7 %** (local, eight wrong services fewer, no offer lost). The 13 new services alone: 9 right (bauteamcheck 6/6, edelsenior 2/2, stackfinder 1/5), all kept |
| T3 protection cases | the tool "CI/CD", "TYPO3 CMS", other fields, navigation "Kontakt/Karriere": no return | tests pass (`OfferEvidenceRule8Test`); phase 2: no counterexample offered |
| T4 LLM | STELLWERK's SAP DRC, Fast Close, Cash Management accepted (≤ 40 requests) | phase 2 (37 requests): SAP DRC and Fast Close accepted; Cash Management not: its pages hold 95–104 characters in Solr (the text loss of crawl or parsing, a separate item) |
| T5 effort | the new LLM candidates and the services pages asked again (count), the re-read's duration | **Rules, no model:** 192,862 tracked pages, ≈ 47 min of extraction (14.6 ms per page in phase 2, 13.1 ms in phase 3c), one pass. **Model, separately:** 991 new candidates and 7,404 services pages asked again, × 2.01 requests per page read ≈ **16,900 requests**; at the 37 requests of T4 (2,833 prompt and 1,383 answer tokens each) ≈ 47.8 million prompt and 23.3 million answer tokens with the tier's configured model; ≈ 3 days at ≈ 5,500 requests a day (the rate seen in production, an assumption), in the shared queue with the remaining backlog |
| T6 customer/competitor | the plan's table as a test; pairs of today's suggestions judged from the provider's view | judged from the provider's view (owner rules of 22 October 2026; [the reference](SCOUTRO_GATE_T6_0.12.0.md)): 80 pairs of phase 1 and phase 3b, read on Olares with the read path of round 2 (phase 3c). stackfinder, **measured on Olares: 6 right of 10 shown** (0.11.7: 6 of 56): the 6 pairs whose signal holds stay shown; of 57 whose signal does not hold 4 were still shown, 3 of them one posting's requirement now covered by the posting's delivery role, 1 remains (a provider's method sentence). **6 of 7: a local expectation** until the read-only phase 3d confirms it on Olares with the final commit. checkthecoach (team development): 17 pairs resting on one reader's sentence, none shown (0.11.7: all). Documented purchases add positive fits after the re-extraction (phase 3c: 33 clients on 400 production pages naming clients, each as the page states it). bauteamcheck and edelsenior: no need signal passes their rules (own responsibility, a hospital's own process); the sampled building needs were providers' offers, now no needs. **Known gap of recognition:** two hospitals describe their own discharge management, a real need, which the rule does not recognise without "unser Krankenhaus"/"unsere Klinik" in the sentence and a location; the missing customers are not only a matter of the crawl's scope (no rework in 0.12.0) |

Model budget of all test runs up to 0.12.0: at most 300 requests, at most 40 per run.

## What the update does

1. **The start:** the partial index is created (a few seconds per million statements, measured: **open**); the LLM tier marks
   the `not_candidate` pages and the services pages without a service to do (events `llm_candidates_changed`,
   `llm_service_recheck`).
2. **Re-extraction by the rules:** every tracked page, at low priority behind new pages, paused like every enrichment
   (`kg_meta.reextract_pending`; a stop repeats it at the next start). Duration on Olares: **open** (T5: time per page of the
   probe × tracked pages). Topics the new proof shows become services again; industries and matching follow.
3. **LLM requests:** the services pages asked again and the new candidates, within the configured schedule and budget:
   ≈ 16,900 requests in estimate (T5), apart from the rules' re-read; about three days at the rate seen in production.

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
