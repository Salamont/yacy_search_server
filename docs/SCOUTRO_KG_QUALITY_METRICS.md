# Knowledge graph quality metrics and the derived facet (WP-B)

Status: 2026-10-18, base `main` 7fb9fe8a915d3932e929d55f9d9fefa17466b42c. Part of the data quality programme
(`docs/SCOUTRO_DATA_QUALITY_PROGRAM.md`, introduced with WP-A).

## Why

The quality audit of 2026-10-17/18 could only report SQL ratios of an isolated 0.10.6 snapshot (organisations
without industry 69–87 %, services without provider 10–87 %, 2 375 "orphaned" entities, 7 384 empty excerpts); the
live API had no metric, and the derived facet answered `read_timeout`. Corrections of the later work packages need a
reproducible baseline with a stated counting unit.

## `GET /scoutro/api/v1/kg/quality[?refresh=true]`

Administrator only, read only, never an agent route. Answer `scoutro.kg.quality.v1`:

| Metric | Unit / meaning |
|---|---|
| `organizations` | active organisations |
| `organizations_with_stated_name` / `_without_stated_name` | with or without a `name` statement (a display name derived from the domain is no stated name) |
| `organizations_with_explicit_industry` | an `industry`/`industry_category` with at least one evidence that is not the synthetic derivation from a service category (`derived:category:*`) |
| `organizations_with_derived_industry_only` | industry only derived from service categories |
| `organizations_without_industry` | none at all |
| `services`, `services_without_provider` | active services; without any `offers` edge |
| `services_with_price`, `services_with_price_without_provider` | with a `price` statement; and no provider |
| `service_duplicate_candidates` | groups (and their services) with the same provider and the same normalised name: merge candidates, never merged by name alone |
| `frequent_service_names` | top 20 normalised service names (generic names such as „Sanierung“ or „Hersteller“) |
| `offers_evidence_by_tier`, `price_statements_by_tier` | evidence rows by tier (1 JSON-LD/metadata, 2 rules, 3 LLM) |
| `evidence_empty_excerpt` | evidence rows with an empty excerpt, of all rows, by tier |
| `data_state` | documents, entities, merged entities, statements, evidence, documents without LLM status |

### Metrics of the later work packages (integration on top of WP-A–WP-H)

| Metric | Unit / meaning | Work package |
|---|---|---|
| `entities_in_several_collections` | active entities with memberships in two or more collections (multiple assignments; never corrected automatically) | A, H |
| `collection_decisions` (top level) | durable collection decisions by status; active ones by basis (`manual`, `automatic`), mode, application state; `active_to_fallback`: decisions that put a domain into the fallback collection | A |
| `assessments` | assessments by status; active ones by action (`withdraw`, `reclassify`, `assert`) and basis; `review_needed`; `claims_held_back` | E |
| `withdrawn_evidence` | evidence rows active assessments withdrew or moved (`moved`), by tier; `dependent` (derived industries that went along) | E |
| `operator_statements` | tier-4 evidence rows, the statements they support, and those supported by an operator statement only | E |
| `monetary_amounts_by_role` | `monetary_amount` statements by role (`statutory_benefit`, `donation`, `subsidy`, `unknown`) | F |
| `prices_not_yet_valid` | price statements whose `valid_from` is after the day of `computed_at` | F |
| `topic_statements` | categories named without an offer (`topic`) | F |
| `service_product_page_duplicate_candidates` | groups of active services stating the same own page (`website` without scheme, `www.` and trailing slash): candidates of the `product_url` identity, which joins only the same name | F |
| `identity_keys` | keys of active entities by scheme (`product_url` exists only with `scoutro.kg.identity.productUrl`) | F |

`offers_evidence_by_tier` and `price_statements_by_tier` include tier 4 (operator statements). The baseline procedure
below now measures the corrections too: compare `withdrawn_evidence`, `monetary_amounts_by_role` and
`service_duplicate_candidates` / `service_product_page_duplicate_candidates` before and after an assessment, the
WP-F re-extraction or an identity switch.

Entity metrics give `total` (distinct entities with at least one collection) and `by_collection` (memberships: an
entity in two collections counts in both; the numbers do not add up). Merged entities (`status = 2`, merge
redirects) never count. Every metric runs in its own read transaction with a deadline of 60 s; a metric that fails
reports `{"error": code}` and is listed in `errors`, the others are still answered. A snapshot is reused for 10
minutes; `refresh=true` computes it again. The counts describe the graph at `computed_at`, not the index, and they do
not separate the causes of a gap (the source has nothing, not extracted, ambiguous).

Baseline procedure before a correction: read with `refresh=true`, keep the JSON with its `computed_at` and the
deployed version; compare the same metrics after the correction.

## The derived facet and `GET /v1/kg/derived`

Before: one read transaction scanned the whole `kg_derived` table without filter, ran an extra query per row, and
for every suggestion row recomputed all suggestion groups of its origin (quadratic in an origin's suggestions);
it then scanned every matching contribution with its re-validation, all inside the 5 s read lease. `facets?counts`
did the same once more. Result: `read_timeout`.

Now:

- `kg_derived` is read in keyset chunks of 500 rows and `kg_match_contribution` in chunks of 200 (by `public_id`,
  the former order), **each chunk in its own read transaction**;
- the kind is filtered in SQL, and for `?entity=` the derived rows of that entity (and its merge target);
- the groups of an origin are computed once per chunk (memo);
- the derived counts of `facets` are cached per viewer scope and data version (counts and newest `computed_at` of
  both tables) for at most 10 minutes.

The projection itself (visibility, grouping, deduplication, order, the first 25 contributions) is unchanged: a test
compares chunks of one row with the default for every viewer, kind and entity filter of the fixtures. Limitation:
chunks are separate transactions, so a derived run that writes during a long read can mix two states in one
answer; the answer is a projection, not a snapshot. A measurement on a production-sized graph is still open.

## Tests

`BusinessViewTest` (quality metrics: partitions add up, reproducible, per-metric errors; chunked derived projection
equals one pass, facet counts agree), `KnowledgeApiTest` (route: 503 without graph, read only, unknown parameter),
`QualityMetricsIntegrationTest` (the metrics of WP-A/E/F on a graph with a multiple assignment, a withdrawal, an
operator statement, a donation, a future price and two services of one page; every metric has its unit), and the
existing derived/suggestion/matching tests.
