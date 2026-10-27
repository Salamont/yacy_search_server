# Scoutro 0.13.2: the speaker's own business, a topic and a referral are no declared audience

Prepared versions: **0.13.2 / 1.942-scoutro.48**. Numeric YaCy peer version **1.942** stays unchanged. No schema change, no new
extractor version: the rules stay `rule/9` and `jsonld/7`, the LLM tier `llm/5`; only the shared reader of audiences changes
(`Audiences.VERSION` 2, no part of an extractor identity). Each step after the merge is a separate decision of the operator.

| What | Value |
|---|---|
| Release | for a target category or industry, the speaker's own business in the first person, its identity, a place after "im", a topic and a referral or comparison are no audience; the services built on a category's term name no organisation |
| Source commit on `main` | `d7fdf60a6842` (merge of #99, on 0.13.1 `2f79fd2bf329`) |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.48`, alias `0.13.2`, linux/amd64 |
| Registry digest | `sha256:9edf0ece1989d63e34a827b574de6d69baee92e42c309d5ef363f2231f632ca6` (both tags) |
| Knowledge graph schema | **9, unchanged** |
| Extractors | unchanged (`rule/9`, `jsonld/7`, `llm/5`); `Audiences.VERSION` 2 |
| Upgrade from | 0.13.1 / `1.942-scoutro.47` |

## The fault of 0.13.0

The reader of 0.13.0 reads every vocabulary, the page's own too. A provider's own word for itself then became its declared
audience: a care service's "Für wen eignet sich unser Pflegedienst?" or "Wir helfen Ihnen als ambulanter Pflegedienst" gave it the
target category of ambulant care, and the older path of suggestions offered other care services to it as customers (an *audience
fit*: its own category met theirs). The measurement after the update found these fits mostly implausible in the care collection,
and single ones in the construction collection.

## What 0.13.2 changes (`docs/SCOUTRO_KNOWLEDGE_GRAPH.md`, section 43)

For a target category and a target industry none of these is an audience any more:

1. **The speaker's own business:** the word after "unser" ("unser Pflegedienst", "in unserer Pflegeeinrichtung"), the clause whose
   object is the speaker ("speziell für uns"), the speaker's identity ("wir sind ein ambulanter Pflegedienst") and the role in the
   nominative after "Ihnen als" ("Ihnen als ambulanter Pflegedienst"). An offer for the speaker's business is the voice of a client
   and stays ("Software für unseren Heizungsbetrieb").
2. **A place:** "Leistungen im Pflegedienst"; after a marker or "für" it stays ("Software für den Einsatz im Pflegeheim").
3. **A topic:** "Fragen rund um den ambulanten Pflegedienst", "das Thema Pflegedienst"; the reader's own stays ("rund um Ihre
   Pflegeeinrichtung").
4. **A referral or a comparison:** "vermitteln Ihnen den passenden Energieberater", "Vermittlung eines ambulanten
   Pflegedienstes", "Leistungsvergleich der Pflegedienste".
5. **The services built on a category's term:** "Pflegedienstleistungen" name no organisation ("Pflegedienstleitungen" and
   "Pflegeheimbetreiber" do).

A topic, a referral and the identity reach at most five words, not across a comma or a conjunction, and never past a preposition
of an addressee. Customer types, audience segments and company sizes keep the reading of 0.13.0.

## What the update does

Nothing at the start: no re-extraction, no model request, no growth of the graph. Stored evidence is read again with the current
reader wherever an audience takes effect, so the change works at once in the business view and in the reasons of the older
suggestions: a target statement whose evidence is only the speaker's own business stays stored as history and has no effect, and
an older suggestion resting on one is no longer shown. The next pass of the derivation computes such a suggestion again where the
confirmed rest of its basis still meets; a complete pass deletes the others (on a graph above the pass's limits the rows stay
stored and hidden).

## Rollback to 0.13.1

The graph keeps schema 9 and the extractor identity of 0.13.1: start the 0.13.1 image by digest on the same DATA. Its reader
confirms the evidence of the own business again: the hidden suggestions are shown again, and those a complete pass of 0.13.2
deleted come back at its next pass. The way back to 0.12.2 stays the one of [0.13.0](SCOUTRO_RELEASE_0.13.0.md) with the backup taken before that update.

## Known limitations

- A target of the LLM tier from a provider that names itself in the third person ("Der Pflegedienst Muster unterstützt …", a
  heading "Ihr häuslicher Pflegedienst in Musterstadt") stays confirmed; corrected in [0.14.0](SCOUTRO_RELEASE_0.14.0.md).
- A client's voice on a provider's page after another marker than an offer ("Speziell für unseren Heizungsbetrieb entwickelt")
  is read as the speaker's own business.

## Tests of this release

`AudiencesTest.theSpeakersOwnBusinessATopicAndAReferralAreNoTarget` (the own business, the place, the identity, the nominative
after "Ihnen als", the topic, the referral, the comparison and the services built on the term give no target; the reader's
business, an addressee after a topic or a referral, the role after the term, the client's voice, the customer types and segments
stay; stored evidence of the own business is not confirmed again); without the change the first group gives targets. The other
tests of `AudiencesTest` and the regression of `tools/scoutro/ci/scoutro-checks.sh` keep their results.
