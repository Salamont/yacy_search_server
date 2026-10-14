# Scoutro: quality check of a model for the knowledge graph

The LLM selection (`LLMSelection_p.html`) can check a candidate model before it
reads pages for the knowledge graph (usage `knowledge`, see
[SCOUTRO_KNOWLEDGE_GRAPH.md](SCOUTRO_KNOWLEDGE_GRAPH.md), 6.3). The check reads
versioned reference pages with the candidate exactly as the graph's LLM tier
reads a page and compares the result with expectations set by hand. It is a
deliberate, bounded, cancellable run: it shows its scope and its maximum cost
first, publishes nothing, changes no setting and activates nothing.

Code: `net.yacy.scoutro.knowledge.quality` (`QualityCases`,
`QualityEvaluator`, `ModelQualityCheck`, `QualityResults`),
`net.yacy.scoutro.knowledge.sync.LlmReading`, `net.yacy.scoutro.api.LlmQualityApi`,
`htroot/env/scoutro/llm-quality.js`.

## 1. Flow: choosing is not activating

1. **Deploy** a model in the Production Models Matrix (as before). Deploying
   assigns no knowledge usage (it is opt-in).
2. **Choose the candidate** in *Quality check for the knowledge graph*. The
   choice changes nothing.
3. **Show scope and maximum cost**: the reference pages, the planned requests,
   the caps and the worst case of tokens and cost. No model is called.
4. **Check quality**: the run starts only on this explicit action; it can be
   cancelled.
5. **Read the result**: status, scope, the functional and the technical
   result, every deviation, the provenance.
6. **Use for the knowledge graph …**: a confirmation names the new and the
   current model and the candidate's check status. Only a confirmed switch
   moves the knowledge usage (the matrix's own checkbox and save). Cancelled,
   the active model stays active.

The matrix's **knowledge** checkbox asks the same confirmation; switching it
off, or undeploying the row that holds it, asks whether the graph shall read
without a language model. A check never deselects the active model, and
deploying or checking another model never touches it.

## 2. Test path: the graph's own steps

A reference page is read with the steps of `LlmReading`, which the queue's
worker (`LlmService.process`) uses for every page as well, so the check has no
extraction of its own:

| Step | Shared code |
| --- | --- |
| tiers 1 and 2 of the page (the known entities `k1`, `k2`, …) | `BaseTiers`, `LlmExtractor.known` |
| would the queue ask the model about the page at all | `RuleExtractor.candidate` |
| chunks of the page's content | `PageText`, `LlmExtractor.chunks` |
| requests planned against the model's window | `LlmReading.budget`, `RequestBudget.plan` |
| system prompt (the **active** knowledge prompt), user message, schema, structured output, timeout | `LlmReading.ask` → `YacyLlmClient` (the real client of the candidate's row) |
| validation of the answer | `LlmExtractor.validate` |
| the extraction the publication takes (values read from quotes, menu jobs dropped, industries) | `LlmExtractor.apply` with `LlmReading.context` |

What the check does **not** do: no queue, no extraction cache (neither read nor
written), no progress, no publication (no `Publisher`, no graph write), no
crawl, no index import. The reference pages are given as text; they belong to
their own collection (`scoutro-quality-check`) that the LLM tier follows and
whose job postings it reads. The graph's other settings for the LLM tier
(input size, statement limit, timeout, structured output, answer reserve) are
the graph's.

## 3. Reference pages (test set `kg-reference`, version 1)

Invented organisations, `.example` domains, no persons or contact data. Each
page has the statements a correct reading makes (**expected**) and those it
must never make (**forbidden**), each with its reason; set by hand from the
page text, never from a model's answer.

| Page | Checks | Expected of the model (examples) | Forbidden (examples) |
| --- | --- | --- | --- |
| `own_job_ad` | own job advertisement | the job's `hiring_organization` is the operator | the application form as a service, a task as a job |
| `intermediary` | agency for an unnamed client | the job with its title | the agency, or anyone, as employer |
| `job_portal` | portal listing another employer | `hiring_organization` the named company | the portal as employer, its search as a service |
| `team_vs_service` | team names next to services | both listed services, `offers` | "Serviceteam", "Kundendienst-Team" |
| `page_tool_vs_product` | page tools next to a product sold | the product, `offers` | "Seite drucken", "Rechtschreibprüfung" (a tool here) … |
| `navigation_next_to_content` | menu around the content | the content's service, `offers` | "Karriere" as job, menu entries as services |
| `price_with_service` | prices with service, unit, condition | 28.50 EUR/hour excl. VAT on one service, 4.20 on the other | either price on the other service |
| `software_competence_vs_internal_use` | offered competence vs internal use | the offered consulting service | the internally used software as anything |
| `contradictory_sources` | two prices of one service | the service | any amount the page does not state |
| `missing_stays_unknown` | what the page does not state | `hiring_organization` | any employment type, any salary, a task as service |

A change of a page, an expectation or the evaluation rules is a new version
(`QualityCases.VERSION`, `QualityEvaluator.VERSION`); earlier results become
stale. Tests make sure every page is a page the queue would ask about (one
request), every page expects something of the model that the rules do not
state already, and every name and passage occurs on the page.

## 4. Evaluation rules

- **Only what the LLM tier adds is rated** (tier 3 of the extraction the
  publication would take). An expected statement that tiers 1 and 2 make
  already is listed as *given by the rules* and not rated; a forbidden one they
  make is not the model's.
- **Normalised comparison:** names by `Normalizers.key` (case, punctuation,
  spacing) against the names a page allows; types and predicates exactly;
  prices by amount, currency, unit and VAT condition. The ids of an answer
  (`e1`, `k2`, random ones) are never compared.
- **An empty reading is no pass** where statements are expected: each one is
  *missing*.
- **A correct statement the validator drops is missing**, and the deviation
  says so: *stated by the model, dropped by the validator* with the reasons
  (or *dropped when the publication was prepared*, or *the whole answer was
  refused*, or *not stated*).
- **Deviation kinds:** missing, wrong (a forbidden statement), wrong type (the
  expected name under another type), wrong relation (the expected entities
  under another or a reversed relation), unfitting evidence (the expected
  statement with a quote that lacks the expected passage).
- Statements neither expected nor forbidden are listed as *not rated*.
- **No overall score.** Status per candidate: `unchecked` (*not checked*),
  `passed` (*Test passed within its scope*: every page read, no deviation),
  `deviations`, `technical_failure` (a page not asked or not answered: API
  error, cap, cancel, window).

## 5. Result view

Technical and functional results are separate tables:

- **functional:** correct, missing, wrong, wrong type, wrong relation,
  unfitting evidence, given by the rules;
- **technical:** API errors (with the provider's reason, bounded, without
  keys), answers refused by the schema check, statements dropped by the
  validator, answers cut at the output limit, calls and retries, duration,
  tokens (prompt, answer, estimated), cost.

Every deviation shows the reference page, its kind, the expected or forbidden
statement with its reason, the actual statement with its quote, and the
passage of the page. The scope ("10 of 10 reference pages, test set version 1,
17 statements expected of the model, 4 given by the rules, 21 forbidden
checked"), the rules and the limits are shown with every result. On narrow
screens the deviations are labelled cards; the key and value tables stay two
columns.

## 6. Provenance and staleness

A result keeps: the row (service, endpoint without user information, model,
`max_tokens`, window and its source, format and thinking capability), the
model and provider the endpoint says it served, the parameters (temperature,
structured output, timeout, answer reserve, input size), the prompt (hash,
source, revision), the schema hash, the validator (`llm/4` and its reasons),
the rules' versions, the test set and the evaluation version.

A change of any of these (a fingerprint of them, compared when the status is
read) marks the result **stale** with the changed fields; it is kept, and
nothing is run again on its own. A row that is gone makes its result stale
(`candidate_removed`). A check of the knowledge usage is **no acceptance for
`log-report`**, chat or another usage; the page says so.

## 7. Limits and cost

- **Before the start:** scope, planned requests and the worst case. Every
  request may be sent twice (once more without the schema after an endpoint
  rejected it with HTTP 400), so the worst case is 2 × requests calls; a
  prompt counts at 2 characters per token (system prompt, user message and
  schema, plus 64 tokens of template), an answer at the row's `max_tokens`.
- **Caps, retries included:** calls (default the worst case, at least the
  planned requests, at most 100), tokens (the worst case) and, with a price,
  cost (default the worst case). Each transport is admitted only if its own
  worst case still fits; it is reserved at that and settled at what the
  endpoint reports (the worst case where it reports nothing, marked as
  estimated). A cap stops the run before a request, never after.
- **Price:** per million tokens, input and output, with its currency and
  source: the provider's model list (OpenRouter-like `pricing`, read through
  the administrator passthrough) or entered. **Without a price** the start is
  refused unless the administrator accepts explicitly that the cost stays
  unknown; then only the caps of calls and tokens apply.
- **Cancel:** no further request is sent; the request in flight is answered
  or times out (`scoutro.kg.llm.timeoutSeconds`).
- **One check at a time.** The candidate's key stays on the server (the
  client of its row); it is never returned or stored with a result.

## 8. Writes

The check writes only its own result: one JSON file per candidate in
`DATA/SCOUTRO/llm-quality/` (replaced atomically by the next check; no key, no
prompt text). No graph table, no extraction cache, no index, no setting
(`ai.production_models` changes only by the confirmed switch of the page).
`QualityCheckIsolationTest` runs a check beside a running graph and compares
every graph table and `kg_meta` before and after.

## 9. API

Administrator only, under `/scoutro/api/v1/kg/llm-quality` (see
`htroot/env/scoutro/api/openapi.json` and `help/LLMSelection_p.md`):

| Route | Purpose |
| --- | --- |
| `GET /v1/kg/llm-quality` | test set, candidates with status and staleness, the run |
| `GET /v1/kg/llm-quality/plan?candidate=…&inputPrice=…&outputPrice=…&currency=…&priceSource=…&maxCalls=…&maxCost=…` | scope and maximum cost; no model call |
| `GET /v1/kg/llm-quality/result?candidate=…` | the stored result |
| `GET /v1/kg/llm-quality/testset` | the reference pages and expectations |
| `POST /v1/kg/llm-quality {"action":"start", …}` / `{"action":"cancel"}` | start or cancel |

`candidate` is `service|hoststub|model` of a production row. Refusals:
`cost_unknown`, `invalid_maxCalls`, `invalid_maxCost`, `invalid_inputPrice`,
`invalid_currency` (400), `candidate_unknown` (404), `running`,
`not_running`, `kg_not_running`, `nothing_to_ask` (409).

## 10. Tests

- `ModelQualityCheckTest` (real client against a local fake endpoint): a
  correct model passes with scope and provenance; the confirmed mistakes
  (team as service, agency as employer, price on the wrong service) are
  deviations with passage, expectation and actual statement; an empty answer
  fails; a statement the validator drops is missing with its reason; ids do
  not matter; unknown cost needs acceptance; the plan sends nothing; the call
  cap counts retries; the cost cap stops before a request; unreported usage
  counts at its worst case; cancel; one run at a time; API errors without the
  key; staleness after a change of the row, the prompt or the settings, with
  no re-run; only the result file is written.
- `QualityCasesTest`: categories, one request per page, expectations of the
  model, names and passages on the page, invented domains, version, the
  normalised comparison of names and prices.
- `QualityCheckIsolationTest`: no graph write beside a running graph.
- `LlmQualityApiTest`: candidates without keys, refusals and their statuses.
- `test/scoutro-ui/llm-quality-live-smoke.py` with
  `llm-quality-ui-test.mjs`: a disposable peer and a fake endpoint; plan, one
  check through the page, result and deviations, the dismissed and the
  confirmed switch (saves intercepted), English and German, 360 to 1280
  pixels, no setting changed, no key anywhere.

## 11. Known limits

- The test set is small (ten German pages) and checks typical mistakes, not a
  model's general quality; a pass says nothing about other pages.
- Prices come from an OpenRouter-like model list only; other providers need a
  price entered. The worst case uses 2 characters per token, not the
  provider's tokenizer.
- A request in flight cannot be interrupted; cancel waits for its answer or
  timeout.
- The served model and provider are what the endpoint reports.
- The reasons of the expectations and the statements are shown in English.
