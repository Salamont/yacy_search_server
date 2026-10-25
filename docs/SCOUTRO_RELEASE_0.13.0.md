# Scoutro 0.13.0: declared audiences, clients, providers, places of the LLM tier and its replay

Prepared versions: **0.13.0 / 1.942-scoutro.46**. Numeric YaCy peer version **1.942** stays unchanged. No schema change; one
re-extraction of tiers 1 and 2 (`rule/9`, `jsonld/7`, vocabulary package 7); the LLM tier's stored answers are replayed without a
model request (`llm/5`). The update merges providers split into two organisations (section 41 of the knowledge graph): **a backup
of the knowledge graph before the update is mandatory.** Each step after the merge is a separate decision of the operator.

| What | Value |
|---|---|
| Release | the declared audience only from a phrase that names organisations, in the clause of its address; one client per provider site; a provider is one organisation by its `@id` on its own domain; planned migration in the clause of its verb; the product tree and the kinds of service; offer items without a category; a facility or site of the LLM tier needs a place in its context; the replay of the stored answers; current lists and the two times of a fact |
| Source commit on `main` | **open** (on 0.12.2, `1100927`) |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.46`, alias `0.13.0`, linux/amd64 |
| Registry digest | **open** until publication |
| Knowledge graph schema | **9, unchanged** |
| Extractors | `rule/9` (was 8), `Vocabulary.VERSION` 5, vocabulary package 7 (`categories.json` with `organisations`, `products.json` version 2 with the tree and `service_types`); `jsonld/7` (was 6: the root `@id` of the own domain without `url`, section 41), in the same re-extraction; `LlmExtractor.VERSION` 5 with `REPLAYS` = {4} (the question's version stays 4); `BusinessSignals.VERSION` 5 (no extractor identity); `MatchingRules.VERSION` stays 1 |
| Upgrade from | 0.12.2 / `1.942-scoutro.45`, installed in production |

## What the update does

1. **One re-extraction** of every tracked page by the rules of `rule/9` (as every change of the extractor identity): the
   declared audiences of the shared reader `Audiences`, the clients of `client_name`, the offer items, the product tree, and by
   `jsonld/7` the site's own organisation of its root `@id`. A statement whose only evidence was the former window of an audience
   is deleted with it. **Merges:** clients named on several pages of one provider (`client_name`), and providers split into two
   organisations (section 41: the structured data's organisation of the root `@id` and the domain's operator; two `@id`s of one
   organisation) are joined when a page of them is read again; the older entity survives, the other redirects to it. A merge is
   not undone by a later version: the backup taken before the update is the way back (see below).
2. **The replay of the LLM tier** (`docs/SCOUTRO_KNOWLEDGE_GRAPH.md`, 40.3): the stored answers of `llm/4` are rebuilt from their
   evidence, validated with the page's text and published as `llm/5` with the model and prompt hash of the answer, page by page
   after its re-extraction. **No model request.** A refused place (a menu item, a page label, an activity, a project's building)
   loses its evidence; a proper name without a place is kept and marked `type_unconfirmed` (the mark takes none of the page's
   limit of statements, so no item the page held before is cut). Status: `llm.validation`; events
   `llm_validation_queued`, `llm_validation_done`.
3. The pages the LLM tier skipped as no candidate are decided once more by `rule/9` (the existing mechanism, no request: the
   candidate rule is unchanged). New and changed pages are asked with the new prompt (the definition of a facility, the products'
   vocabulary): its hash is new, so their chunks no longer hit the cache of the former prompt.
4. Reading: a stored audience fact of the LLM tier takes effect only while the reader confirms its quote; suggestions of the older
   path resting on a stale or unconfirmed basis are not shown; lists and counts hold the current entities.

The work of 1 and 2 is CPU only, about one rule extraction per page; the LLM schedule does not hold the replay.

## Gates (production data, read only)

The gates and the measurement run on production data, read only and without a model, in the private package repository
(`docs/OPENCODE-GATES-0.13.0.md`, parts 1 and 2; `docs/OPENCODE-MEASURE-0.13.0.md`, M0 before and M1 after the update): the
vocabularies of the collections, the declared audiences and the older suggestions on them, the reader on their stored excerpts,
the operators of split providers, the LLM tier's places with the replay on their stored answers, the completeness of tier-3
evidence, every merge the update would make (A6: the update's publication of tiers 1 and 2 on a copy of the graph, the real
resolver deciding; both entities with names, keys, offers and suggestions, the reason, counted per collection; judged by the owner
before the merge), and per collection the suggestions, clients, providers and places before and after.

| Gate | Result |
|---|---|
| Part 1 (A0, A1, A3, A4, A5) | run and judged (25 October 2026) |
| Part 2 (A2, the replay of A4) | A2 on the stored excerpts and on the pages run, judged and passed; the replay (with the page texts) found two faults, corrected (40.2, 40.3): **to run again** |
| Part 3 (A6, the merges) | **open**: the list of every merge, judged by the owner before the merge |
| M0 / M1 | M0 taken (25 October 2026); M1 after the update |

The judgement of A2 changed the reader in two general forms (section 39.2): a goal that names whom the provider serves, and an
organisation's word before the field it is from. The judgement of the replay corrected the check of places (a name as the page
writes it; an address or a place word with a proper name is a place) and the replay itself (services and offers keep the
evidence they were published with). The providers split into two organisations (A3) are item 7 of this release, by the owner's
decision of 25 October 2026 (section 41 of the knowledge graph, with the gate A6 and the measurement of their offers and
suggestions in M1).

## The owner's decisions (25 October 2026)

- Unclear places: kept, marked `type_unconfirmed`, left out of the type filter and its count, listed with
  `review=type_unconfirmed`.
- The prompt's definition of a facility ("a named place of business or care, never a menu item"; new and changed pages only).
- No targeted re-run of the LLM tier.
- Services without a category are shown, with the report `uncategorized_service_names`.
- Documents (PDF, office files) already in the index: not part of 0.13.0. The cleanup of earlier documents and tests comes
  outside this release.
- Providers split into two organisations: part of 0.13.0, with general rules only (the root `@id` of the own domain without
  `url`; two `@id`s that differ only by language segments or the fragment, with the same name or legal name), blocked by a
  differing strong identifier or legal name and across registrable domains, in doubt two; every merge listed by gate A6 and
  judged before the merge; the backup before the update is mandatory.

## Rollback to 0.12.2

The graph keeps schema 9; 0.12.2 sees a new rule and LLM version in `kg_meta`, extracts every page again with `rule/8` (about
6 hours) and examines every document again by `llm/4` (`llm/5` is a version outside its own), with model requests. In this
order:

1. Note the value of `scoutro.kg.llm.maxRequestsPerDay` (unset: 0, no cap) and set it to a small value while 0.13.0 runs
   (Advanced Properties, `ConfigProperties_p.html`). It is written to `yacy.conf` at once and holds from the graph's next start.
2. Start the 0.12.2 image by digest on the same DATA; its status shows the small cap in `llm.dailyRequests.cap`.
3. Restore the backup taken before the update in 0.12.2 (`POST /scoutro/api/v1/kg/control`
   `{"action":"restore","backup":"<file>"}`), never in 0.13.0, which would convert it again. The restored graph carries
   `rule/8` and `llm/4`: neither the re-extraction of every page nor the examination of every document remains. Only the
   restore undoes the merges of the update (0.12.2 keeps merged entities merged).
4. Set the old value again when the owner decides; until then model requests stay within the small cap per UTC day.

Costs: without the restore, the rules' re-extraction of every page (about 6 hours) and model requests only up to the set daily
cap; with it, the copy of the backup and the pages changed since it.

## Tests of this release

`AudiencesTest`, `DeclaredAudienceTest`, `ClientIdentityTest`, `OrganisationIdsTest`, `OperatorIdentityTest`, `BusinessSignalsTest`, `MatchingTest`, `PlaceEvidenceTest`,
`LlmExtractorInvalidReasonTest`, `LlmReplayTest`, `CurrentListsTest`, `ServiceOfferEvidenceTest`, `CollectionMigrationTest`,
`CollectionFitTest`, and the regression of `tools/scoutro/ci/scoutro-checks.sh`.
