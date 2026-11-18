---
page: htroot/ToolsConfig_p.html
help: help/ToolsConfig_p.md
title: Tools Config
package: configuration-administration
access: admin
kind: admin-page
backend_java: source/net/yacy/htroot/ToolsConfig_p.java
---

# Tools Config

## Purpose

Tools Config controls helper tools available inside the YaCy interface.

Use it to enable or adjust optional administration conveniences.

## What You Can Do Here

- Tools Config controls helper tools available inside the YaCy interface.
- Read the current value before changing it.
- Verify the effect on the public page, status page, or related administration page.

## Page Architecture

Configuration pages usually contain persistent settings. A visible form writes values into YaCy configuration, while the backend may reload subsystems such as language files, network listeners, cache handling, or search presentation.

| Control | Meaning | Values or examples |
| --- | --- | --- |
| `ai.tools.#[name]#.maxCallsPerTurn` | maxCallsPerTurn. | Integer value. |
| `ai.tools.#[name]#.description` | maxCallsPerTurn. | Text value; use the page label and surrounding context to choose the exact content. |

## Correct Use

Read the current value before changing it. Configuration changes often persist beyond the current request and may affect later crawling, search, network contact, authentication, or resource use. Change one operational idea at a time and verify the result.

## Access And Safety

Administrator access is required. YaCy protects `_p` pages as administration pages.

Protected related endpoint(s): `/ToolsConfig_p.html`.

## Automation And API

Page backend: `source/net/yacy/htroot/ToolsConfig_p.java`.

| Endpoint | Method | Access | Backend |
| --- | --- | --- | --- |
| `/ToolsConfig_p.html` | `POST` | admin | `source/net/yacy/htroot/ToolsConfig_p.java` |

### Parameter Guide

The table explains values that an agent or script must set deliberately. Parameters not relevant to a task should be omitted or left at the page default.

| Parameter | Meaning and valid values | Care |
| --- | --- | --- |
| `ai.tools.#[name]#.maxCallsPerTurn` | maxCallsPerTurn. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `ai.tools.#[name]#.description` | maxCallsPerTurn. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `save` | Saves settings. | Changes stored data, configuration, or a running job. Use the authenticated action flow where required and verify the result. |

Example request shape:

```http
POST /ToolsConfig_p.html
Content-Type: application/x-www-form-urlencoded

ai.tools.#[name]#.maxCallsPerTurn=...&ai.tools.#[name]#.description=...&save=...
```

## What To Expect

A successful change is visible as a saved value, a confirmation, or changed behavior on a related page. Some settings take effect immediately; others require reconnecting, reloading translations, restarting services, or watching the status page.

## Scoutro: tool release

A tool is offered to the chat model and executed only when it is **released for
the chat** (`ai.tools.<name>.enabled=true`) and `ai.tools.<name>.maxCallsPerTurn`
is above 0. Default: no tool is released. A model with `tooling=supported` alone
gets no tools. Client-supplied tool definitions are removed. `http_json` is
read-only (GET); `http_json` and `webfetch` reach only public http(s)
addresses: local, private, cluster-internal and cloud metadata targets, also via
DNS or redirects, are refused. Details: `docs/SCOUTRO_LLM_SECURITY.md`.

| Parameter | Meaning and valid values | Care |
| --- | --- | --- |
| `ai.tools.<name>.enabled` | `true` releases the tool for the chat; absent or any other value withdraws it. | Release only tools the chat really needs. |

Retrieval tools of the Scoutro chat: `search` ("Index search") searches the
local index again; `kg_search`, `kg_entity` and `kg_evidence` ("Knowledge
graph: find entries / facts of an entry / sources of a fact") read entities,
their facts and relations and the source pages of a fact from the knowledge
graph; `job_search` ("Job search") finds job postings of the knowledge graph
for the subjects of a question, partial matches marked; `provider_search`
("Provider search") derives the service and the place of a question and
searches the index again for providers. When a job or provider question finds
only weak hits, Scoutro runs these two itself before it answers. Until this
page is saved, "Job search" is released together with `kg_search` and
"Provider search" together with `search`; afterwards their own checkboxes
apply. In the chat
they work only within the collections of the chat session (an account's
collection, the chosen collection, or the whole local index for local and
administrator access), never in the YaCy network, and their pages become
numbered sources of the answer. They are not offered to an AI Shield guest.
Details: `docs/SCOUTRO_RAG_QUALITY.md`, section 11.

## Related Pages

- Related configuration work is usually reached from `ConfigBasic.html`, `Settings_p.html`, or the adjacent configuration page in the administration menu.
