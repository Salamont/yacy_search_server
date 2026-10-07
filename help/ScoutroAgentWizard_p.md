---
page: htroot/ScoutroAgentWizard_p.html
help: help/ScoutroAgentWizard_p.md
title: New Agent
package: configuration-administration
access: admin
kind: admin-page
backend_java: source/net/yacy/htroot/ScoutroAgentWizard_p.java
---

# New Agent

## Purpose

The wizard creates an agent identity with its own token, actions, data scope and limits. Use it for external agents (for example OpenCode) and for the Scoutro research worker that works for Clustro. Manage existing agents on [Agents & Access](ScoutroAgents_p.md).

## What You Can Do Here

The wizard has six steps:

1. **Identity and kind**: name, description; external access or Scoutro research agent (Clustro worker).
2. **Data scope**: the collections the agent may read and crawl, or explicitly the complete local index (needs a separate confirmation). The list combines the collections of the discovery portal profiles (`tools/scoutro/discovery/profiles.json`, including their `prospect-*` legacy collections) with the collections found in the index.
3. **Actions**: preset "Research" (search, text evidence, lookup, index size), "Research and crawl" (also start, list, read and stop own crawls) or "Custom". Actions that are not limited to the data scope must be checked individually.
4. **Limits**: allowed crawl domains (required for crawl rights; subdomains included), crawl depth, pages per crawl, parallel crawls, requests per minute; for the research worker also the time per task and whether it may use the configured language model.
5. **Access**: summary, token lifetime (30, 90, 180 or 365 days); for a research worker the Clustro base URL, workspace id, connection id and Clustro agent key.
6. **Connection**: the token (shown once), the agent API base URL as seen from the page and a `curl` check of `/capabilities`. Agents on other hosts get the agent listener of the reverse proxy, never the address of the web interface (server operation: `docs/SERVER_AGENT_ACCESS.md`). For a research worker the page shows the start command; `SCOUTRO_AGENT_URL` is required and has no default.

## Page Architecture

All steps are one POST form. The fields of earlier steps travel in the hidden field `draft`; the step number is in `step`. The draft is validated again on every step and completely before anything is stored, so a changed draft cannot bypass the checks.

| Control | Step | Meaning |
| --- | --- | --- |
| `name`, `description`, `kind` | 1 | `kind` is `external` or `research_worker`. |
| `scopeForm`, `col_<collection>`, `allCollections`, `confirmAllCollections` | 2 | One checkbox per collection of the catalog; a collection outside it is refused, so a new one is created first (New collection). `extraCollections` (free text) is no longer accepted. |
| `actionsForm`, `preset`, `act_<action>` | 3 | `preset` is `research`, `research_crawl` or `custom`. |
| `limitsForm`, `domains`, `maxDepth`, `maxPages`, `maxParallelCrawls`, `requestsPerMinute`, `maxTaskSeconds`, `modelAllowed` | 4 | Same ranges as on the management page. |
| `expiresInDays`, `clustroBaseUrl`, `clustroWorkspaceId`, `clustroConnectionId`, `clustroAgentKey` | 5 | The Clustro fields only for a research worker; the key has the form `ak_` + 64 hex characters. |
| `next`, `back` | all | Navigation. |

## Correct Use

- Choose the narrowest scope. Collections are an access boundary for agents: searches are restricted on the server side, `collection:` in a query is refused, and documents outside the scope do not exist for the agent.
- Crawl rights need a domain allowlist. Agents never start wide crawls, may only crawl allowed domains into granted collections, and see and stop only crawls they started.
- For the research worker, create the Clustro connection first: adapter `custom`, without endpoint URL (pull worker), `isPrimary=false`. Clustro shows its `ak_...` key once; paste it in step 5.
- Without a language model the research worker still answers searches with sources; it reports that no model is configured instead of failing. No model is chosen automatically.

## Access And Safety

Administrator access is required (`_p` page); every step is a POST with a transaction token. The result page sends `Cache-Control: no-store` and `Referrer-Policy: no-referrer`; the token is not stored in the session, in logs or in a URL. Scoutro keeps only its keyed hash. The Clustro agent key is written only to the worker's runtime secret (`DATA/SETTINGS/agent-runtime/<agent>.secret`, 0600) and never shown again.

## Automation And API

Page backend: `source/net/yacy/htroot/ScoutroAgentWizard_p.java` (logic in `source/net/yacy/scoutro/api/AgentAdmin.java`).

| Endpoint | Method | Access | Backend |
| --- | --- | --- | --- |
| `/ScoutroAgentWizard_p.html` | `GET` | admin | first step |
| `/ScoutroAgentWizard_p.html` | `POST` | admin + transaction token | steps 1–5 |

The wizard is meant for people. Scripts that need an agent should use the wizard once and then work with the token on `/scoutro/api/agent/v1`. The UI route is `config.agentWizard`.

## Crawl and status grants

Crawl start requires an explicit granted Collection. In step 2 the collections are ticked in a list of existing collections; **New collection** creates one (display name, ID, optional description) and ticks it. No collection name is typed, and the server refuses a collection outside the list. Optional `host.resolve`/`collections.list` reads are scoped. Global `discovery.status` is admin-risk and absent from presets. No Discovery write permission is introduced.

Contract, endpoints, permissions, errors, persistence and CLI: [Scoutro crawl flow](../docs/SCOUTRO_CRAWL_FLOW.md).

Scoutro responsive layout: existing navigation and controls are reused at 360/390/412/768 pixels and desktop. Tables display the original rows as labelled cards on narrow screens; control names, values, events and save paths are unchanged.
