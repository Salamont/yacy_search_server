# Scoutro 0.14.0: names, clients, facilities by their kind, the matching's follow-ups and the clean-up

Prepared versions: **0.14.0 / 1.942-scoutro.49**. Numeric YaCy peer version **1.942** stays unchanged. No schema change; one
re-extraction of tiers 1 and 2 (`rule/10`, `jsonld/8`, vocabulary package 8); the LLM tier's stored answers are replayed without
a model request (`llm/6`). The update merges the facilities named by their kind on several pages of one provider at one place:
**a backup of the knowledge graph before the update is mandatory.** Each step after the merge is a separate decision of the
operator.

| What | Value |
|---|---|
| Release | a word of the vocabularies names no organisation (the brands of the products are names); one name is never split; a client field ends with its name; "… bei X" names a client only as a name, on a reference page or below a heading of references; a client named by a description is a value of the provider; only white space joins a place's words; a facility named only by its kind is the provider's at its place, one per provider, kind and address; the provider's work for others is its offer, the vendor no client, an FAQ no use; an audience phrase gives no category, the provider in the third person is no target, words of another sense are no hit; rows on merged entities are removed in every pass |
| Source commit on `main` | **open** (on 0.13.2, `d7fdf60a6842`) |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.49`, alias `0.14.0`, linux/amd64 |
| Registry digest | **open** until publication |
| Knowledge graph schema | **9, unchanged** |
| Extractors | `rule/10` (was 9), `Vocabulary.VERSION` 6 (`facility_kind`, `client_description`), vocabulary package 8 (`products.json` version 3 with the brands and `vendors`, `categories.json` with `exclude`); `jsonld/8` (was 7: a word of the vocabularies names no organisation), in the same re-extraction; `LlmExtractor.VERSION` 6 with `REPLAYS` = {4, 5} (the question's version stays 4); `BusinessSignals.VERSION` 6 and `Audiences.VERSION` 3 (no extractor identity); `MatchingRules.VERSION` stays 1 |
| Upgrade from | 0.13.2 / `1.942-scoutro.48`, installed in production |

## What 0.14.0 changes (`docs/SCOUTRO_KNOWLEDGE_GRAPH.md`, section 44)

1. **Names.** A name that is nothing but a word of the vocabularies (a category's term, a group, a segment, a kind of
   organisation, a customer type, a size, an employment type, a need: "Netzwerk", "Senioren", "Pflegedienst") names no
   organisation, client, partner, supplier or region, in the rules, in JSON-LD (other than the own domain's operator) and in the
   LLM tier. The brands of the product vocabulary (AWS, Google Cloud, Shopify, Shopware, KNX, WordPress, TYPO3, Kubernetes) and
   the products' vendors are names; the comparison keeps the umlauts ("Turen" is no "Türen"). A list is not split inside one
   name ("Garten- und Landschaftsbau", "Ministerium für Muster, Beispiel und Test"); a client field ends before an address, a
   representative, the next field's label or a sentence end; placeholders ("auf Anfrage", "N.N.") are no client; a project's
   name keeps no markdown marks.
2. **"… bei X"** (the owner's decision of 27 October 2026): narrowed, not removed. A heading names a client after "bei" only on
   a reference page or below a heading of references, and only as a name with a legal form or a proper name, cut before the
   words after it; a phrase, a common noun, the reader, a question, a place or an infinitive is none.
3. **Clients named by a description** ("Dienstleister für Musterabrechnungen", "Energieversorgungskonzern in Musterland"): a
   value of the provider (`client_description`: the description, the project with its state and products), never an
   organisation or a candidate of the matching; public bodies and associations stay clients. Shown in the knowledge view under
   *Clients named by a description* (`audiences.client_descriptions`).
4. **Facilities named only by their kind** (the owner's decision of 27 October 2026): kept only with their place in the page
   (an address after the name, or "in X", "an unserem Standort X") and shown as "kind (provider, place)", e.g. "Tagespflege
   (Muster Pflege, Musterstadt)"; one per provider, kind and address (scheme `facility_kind`): three day care facilities of one
   provider at three addresses stay three, the same one on five pages is one. Without a place the kind stays an offer. Only
   white space joins the words of a place's name.
5. **Matching:** the provider's work for others ("Systeme, die wir migrieren") and its choice for the reader are its offers; a
   product named as what an offer replaces is neither used nor offered; the source of a planned migration is no need of it;
   the vendor of a product is no client of it; the lines below an FAQ heading state no use, plan or need.
6. **Audiences:** an audience phrase ("Software für Architekten") gives the provider no category, offer or topic; a provider
   naming itself in the third person ("Der Pflegedienst Muster unterstützt Sie", "Ihr Pflegedienst in Musterstadt") is no
   target (the known limitation of 0.13.2); a category's `exclude` lists words of another sense ("IT-Architekturen").
7. **Clean-up:** rows of the derivation and contributions of the matching that name a merged entity are removed in every pass,
   also in an incomplete one.

## What the update does

1. **One re-extraction** of every tracked page by `rule/10` and `jsonld/8` (as every change of the extractor identity; 0.13.1
   reads every page, also beyond the work queue). A statement whose only evidence was the former reading is deleted with it:
   the organisations named by a vocabulary word, the clients read from a phrase after "bei", the clients named by a
   description (now values of their provider).
2. **The replay of the LLM tier** (section 40.3): the stored answers of `llm/4` and `llm/5` are rebuilt from their evidence,
   validated with the page's text and published as `llm/6`, page by page after its re-extraction. **No model request.** An
   organisation named by a vocabulary word loses its evidence (`entity_organisation_vocabulary_word`); a facility named only by
   its kind keeps it only with its place (`entity_facility_kind_without_place`). **Merges:** the facilities of one provider,
   kind and place named on several pages are joined; the older entity survives, the other redirects to it. A merge is not
   undone by a later version: the backup taken before the update is the way back.
3. The first passes of the derivation and the matching remove the rows naming merged entities (the matching up to 2 000 per
   run, `removed_merged`).
4. New and changed pages are asked as before; the prompt hash includes the version of the products' vocabulary (version 3),
   so their chunks no longer hit the cache of the former prompt (the system prompt, the schema and the question's version 4
   are unchanged). The pages the LLM tier skipped as no candidate are decided once more by `rule/10` (no request).
5. Reading: the third person of B6 and the matching's vetoes take effect at once on stored evidence.

The work of 1 and 2 is CPU only, about one rule extraction per page; the LLM schedule does not hold the replay.

## Gates (production data, read only)

The gates run on production data, read only and without a model, in the private package repository
(`docs/OPENCODE-GATES-0.14.0.md`; M0 before and M1 after the update by `docs/OPENCODE-MEASURE-0.13.0.md`), in all four
collections: G1 every entity of the count of generic names on the pages that named it, read by the staged classes (stays, shown
as a kind, a client description or goes; the owner's criterion: at least nine in ten of those that go are wrong); G2 the
clients after "bei" and in client fields (how many of the real clients stay); G3 the declared audiences with the former and
the new reader, and the categories and topics of a sample of pages; G4 the pairs of the sample of M1 with the former and the new read path,
and their candidates as descriptions or vendors; G5 the facilities named by their kind (distinct providers, kinds and places).
No collection may get worse.

| Gate | Result |
|---|---|
| G1–G5, run 1 (27 October 2026) | judged in full, not by samples: G1 met in every collection (of the names that go far more than nine in ten are wrong; every organisation named by a brand and both firms named like a segment word stay); G2: every real client of a heading "… bei X" stays, many client names cut or run on are read in full now; G3: a few evidence lines are no longer confirmed, all rightly (the provider in the third person, words of another sense), none gained; G4: a few pairs no longer shown, all rightly (an offer's alternative, work for others), some candidates are descriptions or the vendor; G5: the facilities named by their kind kept with their place, merged per provider, kind and place. General reading errors of client names found (places after "bei", abbreviations, no-break spaces, a field's label) and real facilities lost (markdown between the kind and its address, a compound of "Standort"): **corrected** (section 44.2) |
| G1–G5, run 2 (27 October 2026) | on the corrected rules: the facilities lost in run 1 are kept with their place (markdown, a compound of "Standort"), the towns after "bei" and the cut abbreviations are gone, the addresses and no-break spaces of client fields are cut; G3 and G4 as in run 1. Found: the same facility named on one page with its full address and on another with its street only had two keys; a town of two words after a place by a place. **Corrected** (section 44.2) |
| G1–G5, run 3 (27 October 2026) | the facility named with its full address and with its street only is one now, the town of two words is gone; no other verdict changed (the rows of pages new since run 2 are names of products, all kept); G3 and G4 as before. Found: the model's guess of a facility's kind was part of its key, so the same day care on five pages could still be two. **Corrected** (section 44.2) |
| G1–G5, run 4 | **open** |
| M0 / M1 | **open** |

## Known limitations

- A brand written in lower case and ending in "-er" after "bei" reads as an adjective and is not taken as a client.
- A client named only in a heading that asks ("Was macht … bei Muster?") is no client.
- A facility named by its kind shows its provider only where the page names its operator; else "kind (place)" (the facility
  belongs to the provider's domain all the same).
- A facility named by its kind is kept only with its address or "in X" after it; a town on a line of its own ("Tagespflege.
  Musterstadt.") or a place later in the clause ("befindet sich neben unserer Geschäftsstelle in Musterstadt") is none.
- A country or a single word after " / " in a client field reads as a client of its own ("Muster Behörde / Musterland").

## Rollback to 0.13.2

The graph keeps schema 9; 0.13.2 sees a new rule and LLM version in `kg_meta`, extracts every page again with `rule/9` and
examines every document again by `llm/5` (`llm/6` is a version outside its own), with model requests. In this order:

1. Note the value of `scoutro.kg.llm.maxRequestsPerDay` (unset: 0, no cap) and set it to a small value while 0.14.0 runs
   (Advanced Properties, `ConfigProperties_p.html`). It is written to `yacy.conf` at once and holds from the graph's next start.
2. Start the 0.13.2 image by digest on the same DATA; its status shows the small cap in `llm.dailyRequests.cap`.
3. Restore the backup taken before the update in 0.13.2 (`POST /scoutro/api/v1/kg/control`
   `{"action":"restore","backup":"<file>"}`), never in 0.14.0, which would convert it again. The restored graph carries
   `rule/9` and `llm/5`: neither the re-extraction of every page nor the examination of every document remains. Only the
   restore undoes the merges of the update.
4. Set the old value again when the owner decides; until then model requests stay within the small cap per UTC day.

## Tests of this release

`DocumentedRelationsTest`, `GenericNamesTest`, `KindFacilityTest`, `PlaceEvidenceTest`, `LlmExtractorInvalidReasonTest`,
`BusinessSignalsTest`, `MatchingTest`, `DerivedServiceTest`, `AudiencesTest`, `BusinessExtractionTest`, `CollectionMigrationTest`,
and the regression of `tools/scoutro/ci/scoutro-checks.sh`.
