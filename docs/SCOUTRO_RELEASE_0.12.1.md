# Scoutro 0.12.1: the current reading in the matching, one offer per service, the LLM tier's places, the crawl's addresses

Prepared versions: **0.12.1 / 1.942-scoutro.44**. Numeric YaCy peer version **1.942** stays unchanged. No schema change, no
re-extraction, no vocabulary change. Each step after the merge is a separate decision of the operator.

| What | Value |
|---|---|
| Release | the matching reads only the current reading of a page, one offer per service of a provider, offers written with verbs and services named as their product; the LLM tier's places of a host: no technical resources, service and reference pages first; the crawl leaves out technical addresses and documents |
| Source commit on `main` | **open** (on 0.12.0, merge `03130aa0549bfcea6d7620724e28865ebda1f86d` of #94) |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.44`, alias `0.12.1`, linux/amd64 |
| Registry digest | **open** until publication |
| Knowledge graph schema | **9, unchanged** |
| Extractors | **unchanged**: `rule/8`, `jsonld/6`, `LlmExtractor.VERSION` 4, the prompt hash and the vocabularies; no page is extracted again. `MatchingRules.VERSION` stays 1 |
| Discovery job store, correction state | schema **1, unchanged** |
| Upgrade from | 0.12.0 / `1.942-scoutro.43` (`sha256:0ab9c8eac66bc19e4feeb3545c6646252f2cbb681eac0dde1017bd9afa6e8c98`), installed in production |

## Why

The analysis of STELLWERK on 23 October 2026 (production, read only; [section 37](SCOUTRO_KNOWLEDGE_GRAPH.md)): no customer
suggestion with five services and 4,165 SAP signals; earlier readings of pages read again fed the matching (a provider's menu
item as its own system); offers written with verbs were none; the LLM tier's 25 places of the host went to wp-json, a script,
images and careers pages before the project references; 10 of the site's 46 documents were technical addresses.

## Contents

- **M1, the current reading and one offer per service** (`MatchingRules.current`, `Observation.current`,
  `MatchingService.distinct`): an observation of an active page processed after it, without evidence of its tier from its
  extractor, is history, not a matching input; at reading at once, in the cycle with each provider's partition. One offer per
  rule, product and service of a provider.
- **M2, offers** (`MatchingRules.SUPPORT`, `offersOf`): the verbs of support, introduction, integration, migration and
  guidance; a service named or categorized as the product itself. Products stay exact.
- **M3** (planned: the advertisement's section on other pages) is M1: the readings were those of earlier rule versions.
- **M4, the LLM tier's places** (`RuleExtractor.RESOURCE`, `LlmQueue.corePage`, `LlmService.offer`): a technical resource is
  `not_candidate`; a page of the services, of one service or of the references keeps a place up to twice the cap; the pages
  skipped at the cap are decided once more, once per graph (`llm_host_cap_rule` 2, event `llm_host_cap_changed`).
- **M5, the crawl's addresses** (`ScoutroActions.SKIPPED_ADDRESSES`): every crawl start leaves out technical resources and
  documents (PDF, office files, e-books). No crawl is repeated by the release; the crawl page already crawls a domain again
  with its pages and depth on the operator's decision.
- Details: [knowledge graph, section 37](SCOUTRO_KNOWLEDGE_GRAPH.md), [matching](SCOUTRO_MATCHING.md).

## Gates (before the merge, production data read only)

| Gate | Target | Result |
|---|---|---|
| G1 current readings | share of the matching inputs (offers and signals) that are earlier readings; STELLWERK's | **open** |
| G2 STELLWERK | its partition computed with 0.12.1 on the production graph (read only): offers, checks, suggestions with their reasons judged from the provider's view | **open** |
| G3 regression | the pairs of the T6 reference (0.12.0) with 0.12.1: no right pair lost | **open** |
| G4 effort | the pages skipped at the cap that become candidates again (count and estimated requests); checks per hour of the cycle before and after | **open** |
| G5 response times | business and suggestions of the largest providers: no read over 3 s more than with 0.12.0 | **open** |

## What the update does

1. **The start:** the LLM tier decides once more about the pages skipped at the per-host cap (event
   `llm_host_cap_changed`); requests only for service and reference pages within twice the cap, in the configured schedule
   and budget (G4).
2. **The matching:** suggestions resting on an earlier reading disappear at once; the hourly cycle recomputes each provider
   with one offer per service and removes the contributions of the former duplicates when its partition completes.
3. **New crawls** leave out technical addresses and documents.

## Upgrade contract

One start, no migration, no copy, configuration unchanged and not written. A backup before the update is the operator's
decision.

## Checks after the start

- Health `GET /scoutro/api/v1/health`: `scoutro` `1.942-scoutro.44`; `kg/status`: `store.schemaVersion` 9, no re-extraction;
  the event `llm_host_cap_changed` once (when pages were skipped at the cap).
- `kg/status` `derived.last.matching`: `checked` and `completed_partitions` per run (more partitions per run than with 0.12.0).
- STELLWERK: suggestions once its partition is computed (G2); no 503.

## Rollback to 0.12.0

Start the 0.12.0 image by digest (above) on the same DATA. The schema and the extractors are the same: no re-extraction.
0.12.0 ignores `llm_host_cap_rule`; pages the LLM tier read under the new rule keep their reading. The matching of 0.12.0
reads the earlier readings again and recomputes its contributions with its cycle.

## Tests of this release

`MatchingTest` (current readings, one offer, a service named as the product, general SAP proves no S/4HANA, verbs),
`LlmServiceTest` (places of service and reference pages, technical resources), `CrawlStartRecoveryTest` (addresses not
crawled), and the regression of `tools/scoutro/ci/scoutro-checks.sh`.

## References

- Previous release notes: [0.12.0](SCOUTRO_RELEASE_0.12.0.md)
- [Knowledge graph, section 37](SCOUTRO_KNOWLEDGE_GRAPH.md)
- [Evidence-based service matching](SCOUTRO_MATCHING.md)
