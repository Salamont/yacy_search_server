# Rule-based review of stored LLM facts (WP-G)

Status: data quality programme R3, work package G ([programme](SCOUTRO_DATA_QUALITY_PROGRAM.md)); not released.

The extraction semantics of WP-F apply to new LLM results when they are published, also to cached answers. The LLM
facts published before stay as they are; reading every page again would mean model requests for the whole graph.
The **tier-3 review** applies the current rules to the stored LLM evidence instead, **without any model request**.

## Rules (version 1)

| Rule | Finding |
|---|---|
| `price_role` | a `price` whose quote shows a statutory benefit, a donation, a subsidy or an unclear amount (`MoneyRoles`) |
| `price_ungrounded` | a `price` whose quote holds no amount with a currency |
| `vocabulary` | a `category`, `industry`, `industry_category` or `audience_segment` the page's current vocabularies do not contain (the check of WP-D, for pages moved before it) |

## Running it

- `GET /scoutro/api/v1/kg/tier3-review` — the progress of the dry and of the applying review: cursor, pages read,
  findings per rule, assessments created, pending pages.
- `POST /scoutro/api/v1/kg/tier3-review` with `{"apply": false, "maxDocuments": 1000}` — one bounded step of a
  **dry run**: counts and a sample of at most 20 findings, nothing changes. `"restart": true` starts from the
  beginning. Repeated calls continue from the stored cursor (resumable across restarts) until `done`.
- `{"apply": true}` — the same step withdraws each finding through an **automatic assessment** (WP-E): scope the
  source page, the predicate, the value, tier 3, basis `automatic`, reason `reassessed_rule:<rule>[:<detail>]`,
  actor `tier3_review/1`. One event per step. A finding already covered by an active assessment creates none.

Administrator only; no agent route, no MCP tool. Each step is one maintenance transaction of at most 5000 pages.

## Reversibility

Every applied finding is an ordinary assessment: listed with `GET /v1/kg/assessments`, undone with
`POST /v1/kg/assessments/{id}/revoke` (the evidence comes back exactly), and protecting against re-publication
from the LLM cache. The review never deletes anything else and never asks a model.

## Rollout

Dry run over the whole graph first; check the findings per rule and the sample; then apply in steps. The amounts the
`price_role` rule withdraws as prices remain in the observation archive (`withdrawn`) and in the copies of the
assessments; a new LLM reading of the page publishes them as `monetary_amount`.

## Tests

`Tier3ReviewTest`: legacy LLM facts (an amount stored as a price, categories of a vocabulary the page left) are found
by a dry run that changes nothing, withdrawn by an applying run through automatic assessments, not found again on a
resumed or restarted run, kept out when the cached LLM result is applied again, and revocable; no model request.
