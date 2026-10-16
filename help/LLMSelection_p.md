---
page: htroot/LLMSelection_p.html
help: help/LLMSelection_p.md
title: LLM Selection
package: ranking-ai-analysis
access: admin
kind: admin-page
backend_java: source/net/yacy/htroot/LLMSelection_p.java
---

# LLM Selection

## Purpose

LLM Selection chooses the language model provider or model profile used by YaCy's AI and retrieval features.

Use it before RAG or AI analysis work so later pages know which model endpoint and capability profile to use.

## What You Can Do Here

- Choose the LLM service family YaCy should use for AI-assisted workflows.
- Set the host or endpoint stub so later RAG and AI pages know where to send model requests.
- Verify the selected service before troubleshooting model-assisted answers elsewhere.
- Load available models and use **Deploy** to add one to the Production Models Matrix.

## Page Architecture

The page chooses an LLM service family and base host for model-assisted features. `service` selects the integration style, for example Ollama, LM Studio, OpenAI, or Open Router. `hoststub` points YaCy at the local or remote API base that will receive model requests.

## Correct Use

Select the service that matches the running model endpoint. For local tools such as Ollama or LM Studio, verify the local server URL first. For hosted services, treat API keys and host settings as sensitive operational configuration.

## Access And Safety

Administrator access is required. YaCy protects `_p` pages as administration pages.

Protected related endpoint(s): `/LLMSelection_p.html`.

Model discovery uses the authenticated, same-origin YaCy admin passthrough.
Ollama is read through `GET /api/tags?hoststub=<encoded-base-url>`; other
OpenAI-compatible services use `GET /v1/models?hoststub=<encoded-base-url>`.
YaCy requests the corresponding path on the selected hoststub and returns the
upstream status/body. Calling `/api/tags` without a hoststub lists YaCy's
virtual usage names, not the Ollama models. Discovery does not pull/delete
models or start a crawl. The existing UI saves the inference selection after
a successful load and saves production assignments when Deploy is clicked.

The model status above Services shows loading, an empty model list, or a
persistent error with the discovery path and HTTP status. A 401/403 requires
checking the administrator login and endpoint credentials; 502/504 or a
connection failure requires checking reachability from the Scoutro server.
An HTTP 200 response with invalid JSON or a missing/invalid model array is
reported as an invalid model list. Response bodies and credentials are not
shown in this error. Existing production rows are retained on discovery
failure; retry with **Load Model Name List** after correcting the endpoint.
The production matrix uses the existing Scoutro horizontal scroll container,
cleared below its card heading so the table remains visible even when the
capability-test activity block is hidden.

### Thinking capability

The **thinking** column says whether a model thinks (reasons before it
answers). Requests that must not think (the knowledge graph's extraction, the
format test below) then say so; the chat sends its own no-thinking parameters
for a model marked as thinking, as before.

- An **OLLAMA** model is tested natively (`htroot/env/scoutro/thinking-probe.js`):
  first Ollama's own capability list of `POST /api/show` (static, nothing is
  generated): **yes** if it lists `thinking`, else **no**. Only when
  `/api/show` gives no capability list, a small `POST /api/chat` with
  `think: true` and "Hello" decides: thinking in the answer is **yes**, an
  answer without thinking or a refused `think` (HTTP 400 while the same request
  without it is answered) is **no**. No answer (network, timeout, 5xx, ...)
  is **?** and nothing is stored. Both go through the administrator
  passthrough (`/api/show?hoststub=…`, `/api/chat?hoststub=…`), configured
  endpoints only. The result is stored with `thinking_probe: 2`. A value
  without it was measured by the former streaming test on
  `/v1/chat/completions`, which misses the thinking of some models (e.g. a
  Qwen3 model, then "no"), and is read as **?** until the page tests the
  model again.
- Every other service keeps the streaming test on `/v1/chat/completions`
  ("Hello", thinking tokens in the stream) and its stored values.

Why it matters for Ollama: a model with the capability `thinking` thinks by
default on `/api/chat` unless the request says `think: false`, and can spend
the whole answer budget on thinking (empty answer). Ollama accepts
`think: false` for every model, so the knowledge graph and the format test
send it for an OLLAMA model unless it is known not to think (**no**).

### Format capability (structured output)

The **format** column is a technical test only: does the endpoint and model
accept and follow a JSON schema (`response_format` `json_schema`)? The page
asks once with the schema
`{"type":"object","properties":{"result":{"type":"string","enum":["ok"]}},"required":["result"],"additionalProperties":false}`
and the prompt "Return the required JSON object." (no task, no answer in the
prompt; `htroot/env/scoutro/format-probe.js`):

- **yes** (`supported`): HTTP 200 and exactly `{"result":"ok"}`;
- **no** (`unsupported`): HTTP 400 or 422 with the schema while the same
  request without it is answered;
- **ignored**: HTTP 200, but the answer is not that object (extra field,
  other value, prose): the parameter is accepted, not followed;
- **?** (`unknown`): no clear answer (network, timeout, 401/403/404/405/429,
  5xx, or a request that fails without the schema too). Nothing is stored and
  the next visit asks again.

An **OLLAMA** model is tested on Ollama's native `/api/chat` with the
schema as `format` (the path the knowledge graph uses for it, through the
administrator passthrough `/api/chat?hoststub=…`, configured endpoints only);
every other service on `/v1/chat/completions` with `response_format`
`json_schema`. The result is stored in `ai.model_capabilities` with
`format_probe: 4` for OLLAMA and `format_probe: 2` for the others. The native
test of an OLLAMA model says `think: false` unless the model is known not to
think (see above). An Ollama value with version 2 was measured on `/v1`, one
with version 3 without `think: false` for a model the former thinking test
took as non-thinking (a thinking model then answers nothing: **ignored**);
both are read as **?** until the next native test. A
format value without it comes from the former mood probe (an invalid schema
type `literal`, and a wrong mood counted as "no") and is read as **?**: the
page tests that model again the next time the LLM selection is opened, and
the knowledge graph treats it as unknown until then. Thinking, tooling and
vision are not affected. No probe runs without the page open.

## Automation And API

Page backend: `source/net/yacy/htroot/LLMSelection_p.java`.

| Endpoint | Method | Access | Backend |
| --- | --- | --- | --- |
| `/LLMSelection_p.html` | `GET` | admin | `source/net/yacy/htroot/LLMSelection_p.java` |

### Parameter Guide

The table explains values that an agent or script must set deliberately. Parameters not relevant to a task should be omitted or left at the page default. Low-level generated parameters are omitted when they are only meaningful inside the rendered YaCy form.

| Parameter | Meaning and valid values | Care |
| --- | --- | --- |
| `service` | Choice value. Options: `OLLAMA` = Ollama, `LMSTUDIO` = LMStudio, `OPENAI` = OpenAI, `OPENROUTER` = Open Router. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `hoststub` | Host or domain scope. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |

Example request shape:

```http
GET /api/tags?hoststub=<URL-encoded-Ollama-base-URL>
```

## What To Expect

After saving, RAG and AI analysis pages should use the selected provider profile. Model failures after this point usually mean the model server, API key, host URL, or model name needs checking.

## Scoutro: stored API keys and errors

The page never shows a stored `api_key`; the field stays empty and the matrix shows
"set". Saving with an empty field keeps the stored key; "remove the stored api_key
on the next save" deletes it. A key typed for an endpoint that is not saved yet is
sent to the admin proxy in `X-LLM-Api-Key`; the YaCy login is never forwarded to
the endpoint. Errors of the YaCy proxy are JSON (`admin_required`, ...); statuses
mirrored from the endpoint carry `X-LLM-Upstream: 1`, so the page tells "YaCy
administrator login required" apart from "the LLM endpoint rejected the
credentials". `hoststub` and `api_key` accept up to 512 characters.

## Scoutro: the knowledge usage

The matrix column **knowledge** selects the model of the Scoutro knowledge
graph's LLM tier (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3). It is opt-in: a newly
deployed model never gets it automatically, and undeploying its model does not
hand it to another row; without a model in this column the graph is built from
structured data and rules only. The tier also needs at least one collection
switched on for it: *Knowledge graph → Settings*, column **LLM enrichment**
(key `scoutro.kg.llm.collections`, empty by default, so the status says
`no_llm_collections` until then; there is no second model setting there, the
graph uses the model of this column). It reads crawled pages of those collections,
uses this row's endpoint, key and `max_tokens`, and keeps only what the page
text states verbatim (see `GET /scoutro/api/v1/kg/status`, field `llm`). The
knowledge model is never offered as a chat model: the RAG proxy and the model
lists (`/api/tags` without hoststub, `/v1/models`) leave it out.

## Scoutro: quality check for the knowledge graph

The section **Quality check for the knowledge graph** checks a candidate model
before it reads pages for the knowledge graph (docs/SCOUTRO_LLM_QUALITY_CHECK.md).
Choosing a candidate activates nothing; the check runs only when **Check quality**
is clicked, publishes nothing and changes no setting.

1. Deploy the model in the matrix (deploying assigns no knowledge usage).
2. Choose it as candidate. For an OpenRouter-like endpoint the price per million
   tokens is taken from the provider's model list (`GET /v1/models?hoststub=…`
   through the administrator passthrough); otherwise enter it, or tick
   *Start without a price* (then only the caps of calls and tokens apply and the
   cost stays unknown).
3. **Show scope and estimated cost**: reference pages, planned requests, the
   worst case of calls (each request once more after a format rejection) and
   the estimated worst case of tokens and cost. Nothing is sent. Every cost is
   an **estimate, no guaranteed maximum**: prompts at 2 characters per token,
   answers at `max_tokens`, times the price as given; the provider's bill can
   differ. The caps of calls (at most 100, retries included) and the cost limit
   (checked against the estimate) can be lowered; a run stops before a request
   that could exceed them.
4. **Check quality**; **Cancel the check** sends no further request (the request
   in flight is answered or times out).
5. The result shows the status (*not checked*, *Test passed within its scope*,
   *No deviation, but accepted statements not rated*, *Deviations found*,
   *Technically failed*), the scope, the functional and the technical result in
   separate tables, every deviation with the reference page, the expectation,
   the actual statement and the passage of the page, the accepted statements
   the test set neither expects, allows nor forbids (they are not rated, so the
   result is no plain pass: read them before switching), and the provenance
   (model, served model and provider, parameters, prompt, schema, validator,
   rules, test set). There is no overall score. What the rules state already
   is fixed before the answer is read and is never expected of the model; a
   model that states it again repeats the rules (counted, not rated). For a
   missing statement the result says where it went: dropped by the validator
   (with the reason of that item), dropped when the publication was prepared,
   the whole answer refused, stated otherwise, *not stated* (only when every
   answer was read and none was cut off) or *cause not traceable*; each item
   found is checked on its own (statement, quote on the page, names in the
   quote, type, assignment). The result keeps a bounded diagnosis of each
   reference page: the model's answer, what the validator kept and dropped,
   and what the publication would take; an incomplete one is marked. A changed
   row, prompt, schema, validator, rule version, test set or evaluation marks
   the result **stale**; nothing runs again on its own. A new check keeps the
   result before it unchanged and lists it (*Earlier results*).
6. **Use for the knowledge graph …** switches the knowledge usage only after a
   confirmation that names the new and the current model and the check status.
   The matrix's **knowledge** checkbox and the undeploy of the knowledge row ask
   the same; cancelled, the active model stays active. Switched off, the knowledge
   usage is off (it is opt-in and never handed to another row).

A check applies to the knowledge usage only, not to log-report, chat or another
usage. It writes only its own result (`DATA/SCOUTRO/llm-quality/`, earlier ones
in `archive/`; no key, no prompt text, the answers to the reference pages
bounded); the row's `api_key` stays on the server.

### API (administrator only)

| Endpoint | Method | Side effects |
| --- | --- | --- |
| `/scoutro/api/v1/kg/llm-quality` | `GET` | none: test set, candidates with status and staleness, the run |
| `/scoutro/api/v1/kg/llm-quality/plan` | `GET` | none, no model call |
| `/scoutro/api/v1/kg/llm-quality/result` | `GET` | none |
| `/scoutro/api/v1/kg/llm-quality/testset` | `GET` | none |
| `/scoutro/api/v1/kg/llm-quality` | `POST` | `start`: requests to the candidate's endpoint (paid where the provider bills), its result file; `cancel` |

| Parameter | Meaning and valid values | Care |
| --- | --- | --- |
| `candidate` | `service\|hoststub\|model` of a production row | Must exist; 404 `candidate_unknown` otherwise. |
| `inputPrice`, `outputPrice` / `price.inputPerMillion`, `price.outputPerMillion` | price per million tokens, 0 to 1000000 | Both or none. |
| `currency` / `price.currency` | three letters, default `USD` | |
| `priceSource` / `price.source` | `provider_list` or `entered` | Shown in the result. |
| `acceptUnknownCost` | `true` to start without a price | Without it a start without price is refused (400 `cost_unknown`). |
| `maxCalls` | cap of calls, retries included, from the planned requests up to 100 | Default: the worst case. |
| `maxCost` | cost limit in the price's currency, checked against the estimate | Default: the estimated worst case. No guaranteed maximum: the provider's bill can differ. |

Automation: read the plan first, start with explicit caps, poll the status
until the run is `finished`, `cancelled` or `stopped`, then read the result.
Never switch the knowledge usage automatically from a result.

## Related Pages

- Related quality work usually continues on ranking settings, content analysis, LLM selection, RAG configuration, or a representative search result page.

On narrow screens the Services, Available Models and Production Models Matrix use labelled cards from the same table cells and controls. Existing model/usage/context-window save handlers are reused; resizing does not create a second model list or save path.

## TLDR: search-query fallback, controls and manual test

`tldr-shortener` is a search-query role, not a stored web-page summarizer. Automatic
use requires **all** of: `ai.tldr.enabled=true` (new, compatibility default true), an
assigned `tldr` model, a question longer than 40 words, and no selected sources from
the first RAG retrieval. The switch is independent of the model-role assignment:
assigning a model makes manual tests available, while the switch controls only
automatic use. No other model role or ordinary retrieval is changed.

**TLDR testen** explicitly makes one bounded model request even when automation is
off. It does not search, crawl, index or mutate the graph. Review possible API
charges before clicking. The result displays the original input, actual instruction,
original response content, parsed terms, effective query, validity/error, duration
and token usage if returned. Invalid output is not used as a query. Model-produced
collection/site operators cannot change the existing authorized retrieval scope.
Manual source comparison is not performed (`sourcesBefore/After=-1`). Automatic
calls report selected source counts before/after retry.

The operator history includes only administrator/local administrator-owned calls.
Other accounts' content is not available through this page; their own chat may
receive TLDR metadata in `scoutro-retrieval.tldr`. History is memory-only, at most
32 records for 30 minutes. Full question/instruction/response content is omitted
unless the operator explicitly enables diagnostics for 15 minutes. The new
`ai.tldr.diagnostics_until` is an expiry timestamp, not stored chat content. Content
expires at that deadline and is purged on access; history is cleared on restart
and can be cleared manually. Known credential patterns are redacted in retained
content; configured API keys never enter it.

All settings, model-matrix writes and manual tests require administrator access,
POST and the existing `transactionToken` obtained from this page. The model matrix
JSON body remains unchanged; send the token as a request parameter. Additional
form actions: `saveTldrSettings` with optional `tldrEnabled`/`tldrDiagnostics`,
`testTldr` with `tldrQuestion` (1–16000 characters and within the model context
budget), and `clearTldrHistory`. Automatic output remains capped at 200 tokens,
the manual test shares that path, and calls have a 30-second read timeout and a
16 KiB response cap. A configured model is not a guarantee of endpoint health;
loading this page does not probe it with a paid inference request.

## Optional TLDR and log-report model roles

The existing `tldr-shortener` and `log-report` matrix checkboxes select optional
model roles (`tldr` and `logreport` in `ai.production_models`). Clearing either
checkbox leaves the role unassigned, including when other models exist. Removing
its model does not transfer the role, and deploying a new model never automatically
assigns it. The existing `knowledge` opt-in behavior is unchanged.

No additional switch or configuration key is introduced. Automatic TLDR still
requires both an assigned model and the existing `ai.tldr.enabled`; automatic
log reports still require an assigned model and the existing enabled schedule /
`ai.logreport.enabled`. The report schedule is unchanged. No assigned role means
no automatic model call, even when its enable setting remains configured on.
Assigning a model explicitly does not change these enable settings.

The matrix shows model assignment. The existing TLDR status shows the assigned
model and whether automatic fallback is active, off, or unavailable. The existing
Log Reports page shows report model, actual state and schedule. Reload updates
these server-rendered readouts after a matrix change. These are two existing
requirements (assignment and automatic use), not two competing activation settings.

For the unchanged manual TLDR test, keep a model assigned and switch automatic
fallback off. The explicit test button still works and can incur API costs. With
no assigned model it remains unavailable; no replacement model is chosen for it.
Saving matrix assignments uses the existing authenticated POST with transaction
token. No changes are made to chat/RAG, the knowledge pipeline, reporting sources,
report generation, scheduler, or databases by this correction.

### Existing operation without a report model (read-only inventory)

YaCy already has LLM-free operator views: `ViewLog_p.html` reads the standard
GuiHandler log buffer; `Status.html` shows peer/system/resource state;
`PerformanceMemory_p.html`, `PerformanceSearch_p.html`, and
`PerformanceConcurrency_p.html` show memory, search events and worker state from
existing runtime structures. Normal FileHandler logging/rotation is independent
of model roles. Existing report files remain readable in `LogReports_p.html`.

These views are retained unchanged. No independent automatic narrative weekly or
monthly situation report without an LLM was identified in the reviewed report
code. The existing no-data notice is not such an operational report. No new
non-LLM report or replacement generation mode is introduced.
