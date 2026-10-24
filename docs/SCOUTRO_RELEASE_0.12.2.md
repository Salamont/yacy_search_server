# Scoutro 0.12.2: own use in the clause of its verb

Prepared versions: **0.12.2 / 1.942-scoutro.45**. Numeric YaCy peer version **1.942** stays unchanged. No schema change, no
re-extraction, no vocabulary change. Each step after the merge is a separate decision of the operator.

| What | Value |
|---|---|
| Release | the shared reader of system signals reads own use and planned migration only in one clause with the product and the company; questions, advice to the reader, rows of delivery verbs, a product or others as the subject and lists of platforms are none |
| Source commit on `main` | **open** (on 0.12.1, merge `16b40fbf8a9492d54b64277581c70f05d70cea45` of #95) |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.45`, alias `0.12.2`, linux/amd64 |
| Registry digest | **open** until publication |
| Knowledge graph schema | **9, unchanged** |
| Extractors | **unchanged** (`rule/8`, `jsonld/6`, `LlmExtractor.VERSION` 4, vocabularies); `BusinessSignals.VERSION` 4 is no part of an extractor identity; `MatchingRules.VERSION` stays 1 |
| Upgrade from | 0.12.1 / `1.942-scoutro.44` (`sha256:2172a316e40c01db3fc55079549483ccf7ac1ac06c070eb7ddc8732870915a6d`), installed in production |

## Why

On 24 October 2026 (production, read only) about a quarter of the current own-use readings of stackfinder-web were own use;
STELLWERK was suggested to 214 SAP providers as their customer because of one rhetorical question in its blog
([section 38](SCOUTRO_KNOWLEDGE_GRAPH.md)).

## What the update does

The matching re-reads every stored quote with the new reader at its hourly cycle and at reading: suggestions resting on a
reading it no longer makes disappear at once. No page is extracted again; new pages get the new contexts.

## Gates (production data, read only, before the update)

| Gate | Target | Result |
|---|---|---|
| G1 | every current reading of own use and planned migration in stackfinder-web of 24 October 2026, judged from the provider's view | **local:** 15 of 15 own use kept, 26 of 26 none dropped (`BusinessSignalsTest.ownUseOfTheProductionReadings`) |
| G2 | the reasons of own use of the 51 providers of gate G2 of 0.12.1 | **local, on their quotes:** 10 kept (9 own use, the client's voice of dab is refused by the matching), 9 dropped, all wrong; the advice to the reader no planned migration |
| G3 | STELLWERK's 214 suggestions as a customer | **local:** the one sentence behind them is no own use |
| G4 | the four collections: suggestions per collection before and after (MatchProbe) | **open** (Olares, read only) |

## Rollback to 0.12.1

Start the 0.12.1 image by digest (above) on the same DATA; nothing to undo.

## Tests of this release

`BusinessSignalsTest` (the production readings, the existing reader tests), `MatchingTest`, `DocumentedRelationsTest`, and the
regression of `tools/scoutro/ci/scoutro-checks.sh`.
