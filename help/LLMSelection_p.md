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

## Related Pages

- Related quality work usually continues on ranking settings, content analysis, LLM selection, RAG configuration, or a representative search result page.

On narrow screens the Services, Available Models and Production Models Matrix use labelled cards from the same table cells and controls. Existing model/usage/context-window save handlers are reused; resizing does not create a second model list or save path.
