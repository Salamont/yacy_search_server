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
and the existing derived/suggestion/matching tests.
