# Knowledge graph on a collection move, LLM priority and daily cap (WP-D)

Status: data quality programme R2, work package D ([programme](SCOUTRO_DATA_QUALITY_PROGRAM.md)); not released.

A collection decision ([WP-A](SCOUTRO_COLLECTION_DECISIONS.md)) or a reassign changes only the `collection_sxt` of
already indexed pages. Before WP-D the knowledge graph treated such a page as a lifecycle change: its memberships were
updated, but its facts stayed as they had been read through the old collection's vocabulary. A page of a care
collection moved to `sonstige` (no vocabulary) kept its care categories and industries.

## What happens on a move

The sync compares, for a page whose content (input hash) is unchanged, the followed collections it was last published
with against its followed collections now. Each list is reduced to its **extraction context**: the vocabularies, the
job collections and the job signals tiers 1 and 2 read (`BaseTiers.contextKey`).

| Case | Graph | Model |
|---|---|---|
| Same context (for example two collections with the same vocabulary) | memberships only (lifecycle), as before | none |
| Context changed | tiers 1 and 2 read the page again with the new collections (rules only), then the LLM facts that came from the old vocabulary are checked | none |
| The page enters an LLM collection and was skipped only as `not_selected` | it becomes a candidate again: its **first** enrichment, scheduled like any other page | one, in the regular queue |
| The page leaves an LLM collection | its LLM result stays (done); nothing is asked | none |

No schema change and no change to `extractionKey` are needed: only the pages that moved are read again; no global
re-extraction and no LLM request is started by a move (`LlmExtractor.VERSION` and the LLM cache key are unchanged).

### Checking the LLM facts (`Tier3Revision`)

The LLM tier reads categories, the industries and industry groups derived from them, and audience segments through
the page's vocabularies. After a context change each such tier-3 evidence row of the page is checked against the
vocabularies of the new collections:

- `category`: the category id must be in one of them (also for an industry derived from a category, locator
  `derived:category:<id>`);
- `industry_category`: the group must exist in one of them;
- `industry`: one of their categories must carry the NACE code;
- `audience_segment`: the segment must exist in one of them.

Rows whose value is not supported are deleted; names, offers, relations, prices, identities and every other LLM fact
stay. Deleting evidence runs the observation archive's triggers, so the history keeps the old assertion. A statement
that loses its last evidence disappears from the derived views like any other withdrawn statement. Relations never
create memberships: the graph keeps the page in the collections Solr gives it.

### Counters

- KG status `sync.processed.rescopedExtractions`: pages read again because a move changed their context.
- The publisher result carries `tier3Revised` (rows withdrawn); it is not a separate status counter.

## LLM priority per collection

`scoutro.kg.llm.weight.<collection>` (1–10, default 1) sets a collection's share of each scan batch of the LLM tier
(before: equal shares). A collection of weight 3 next to one of weight 1 gets three quarters of the frontier batch.
Weights only order the existing queue; they never select a page the tier would not read anyway, and are part of no
identity or cache key. The LLM status shows them as `weights`.

## Daily request cap

`scoutro.kg.llm.maxRequestsPerDay` (0–10 000 000, default 0 = no cap) is a simple verifiable consumption limit for the
existing LLM tier: the requests started on the current UTC day are counted in the store's meta table (`llm_day_requests`,
`yyyy-mm-dd:n`, written in the same transaction as the request's start). At the cap the tier waits with the reason
`daily_request_cap` until the next UTC day; queued pages stay queued. Every HTTP start counts, continuation passes and
format fallbacks included; cache hits count none. The check and the count run in the same synchronized admission step
(`LlmTiming.start`), so the cap is exact with `llm.parallel` 2 too. The LLM status shows `dailyRequests: {cap, today, day}`.

The programme found no cost, token or day budget in the existing system (audit: LLM tier paused, 118 609 pending,
free model). The cap is the plan correction for that; token or price budgets are not part of WP-D.

## Rollout and rollback

- Rollout: no migration. Set the cap (for instance at the expected daily volume) before the first productive move,
  optionally weights. The first productive move (pilot drolshagens.de) follows the programme's rollout rule (together
  with WP-E).
- Rollback: the previous build ignores both settings and the meta key. Facts withdrawn after a move are not restored
  by a rollback; they are in the observation history and come back when the page is read again in a collection of the
  old vocabulary (a move back re-runs tiers 1 and 2; the LLM categories return only with a new LLM reading).

## Tests

- `CollectionMoveTest`: a move into a collection without vocabulary withdraws rule and LLM vocabulary facts, keeps
  relations, the JSON-LD name and the identity, starts no model request; a move between collections of the same
  vocabulary only re-scopes; a `not_selected` page gets its first enrichment in an LLM collection and keeps it after
  leaving.
- `LlmDailyCapTest`: at most the cap per UTC day, the remaining pages the next day; status fields and weights.
