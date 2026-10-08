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

## Related Pages

- Related quality work usually continues on ranking settings, content analysis, LLM selection, RAG configuration, or a representative search result page.

On narrow screens the Services, Available Models and Production Models Matrix use labelled cards from the same table cells and controls. Existing model/usage/context-window save handlers are reused; resizing does not create a second model list or save path.
