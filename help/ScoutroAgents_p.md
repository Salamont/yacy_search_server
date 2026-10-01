---
page: htroot/ScoutroAgents_p.html
help: help/ScoutroAgents_p.md
title: Agents & Access
package: configuration-administration
access: admin
kind: admin-page
backend_java: source/net/yacy/htroot/ScoutroAgents_p.java
---

# Agents & Access

## Purpose

This page manages the agents that may use Scoutro without the administrator account. Every agent has its own token, a fixed list of actions, a data scope of collections and limits. Agents call the agent API `/scoutro/api/agent/v1` (see `docs/API.md`, section "Agent access"); the administrator API `/scoutro/api/v1` and all administration pages stay reserved for the administrator.

New agents are created with the wizard ([New Agent](ScoutroAgentWizard_p.md)).

## What You Can Do Here

- See all agents with kind, status, computed connection state, data scope, actions, token expiry and last use.
- Open one agent and change its name, description, collections, actions and limits. Changes apply to the next request of the agent.
- Pause and resume an agent, or revoke it permanently.
- Rotate the token (optionally keeping the old token valid for 15 or 60 minutes) or revoke a single token.
- For a Scoutro research worker: see whether the worker reports, whether it confirmed a Clustro connection, and edit its Clustro settings.
- Read the activity log: every agent request with action, decision, reason code and HTTP status, plus administrative changes.

## Page Architecture

Without the `agent` parameter the page shows the list and the recent activity of all agents. With `agent=<id>` it shows one agent. All changes are POST requests with the field `op` and a transaction token.

| Control | Meaning | Values or examples |
| --- | --- | --- |
| `agent` | Agent id. | `agt_` followed by 12 characters. |
| `op` | Operation. | `update`, `pause`, `resume`, `revoke`, `rotate`, `revokeToken`, `clustro`. |
| `name`, `description` | Identity (with `op=update`). | 1–80 and at most 500 characters. |
| `col_<collection>`, `extraCollections` | Data scope (with `op=update`). | Checkbox per collection; further names separated by commas. |
| `allCollections`, `confirmAllCollections` | Complete local index (with `op=update`). | Both checkboxes are required together. |
| `act_<action>` | Granted actions (with `op=update`). | Checkbox per action, e.g. `act_search`. |
| `domains`, `maxDepth`, `maxPages`, `maxParallelCrawls`, `requestsPerMinute`, `maxTaskSeconds`, `modelAllowed` | Limits (with `op=update`). | Depth 0–3, pages 1–1000, parallel 1–5, requests 1–600 per minute, task 30–3600 seconds. |
| `confirmRevoke` | Confirmation for `op=revoke`. | Checkbox. |
| `expiresInDays`, `graceMinutes` | New token lifetime and grace period of old tokens (with `op=rotate`). | 30/90/180/365 days; 0/15/60 minutes. |
| `token` | Public token id (with `op=revokeToken`). | 16 characters. |
| `clustroBaseUrl`, `clustroWorkspaceId`, `clustroConnectionId`, `clustroAgentKey` | Clustro settings of a research worker (with `op=clustro`). | An empty key keeps the stored key. |

## Correct Use

- Grant the smallest set of actions and collections an agent needs. The presets in the wizard cover research (search, text evidence, lookup, index size) and research with crawls.
- Actions marked "not limited to the data scope" (`search.network`, `index.status.global`, `system.status`, `config.get`, `config.set`) are never part of a preset. Grant them only deliberately.
- A stored grant is a fixed list. Actions added to Scoutro later are never granted automatically.
- The connection state is computed: `connected` means that a usable token fetched `/capabilities` of the current grant within the last 24 hours. After any change of the grant the state is `stale` until the agent fetches its capabilities again. `never_connected`, `paused`, `revoked`, `expired` and `no_token` are shown as such.
- For a research worker, "Clustro reachable" is only shown when the worker itself reported a successful Clustro call within the last five minutes. A stored Clustro URL is not a proof of a connection.

## Access And Safety

Administrator access is required (`_p` page). Every POST needs a transaction token.

- Tokens are shown exactly once, in the answer to the POST that created them. These answers are sent with `Cache-Control: no-store`, `Pragma: no-cache` and `Referrer-Policy: no-referrer`. Scoutro stores only a public token id and `HMAC-SHA256(pepper, secret)`.
- Storage: `DATA/SETTINGS/scoutro-agents.json` (agents, token hashes, crawl ownership, connection state), `DATA/SETTINGS/scoutro-agent-pepper` (keep it with the backup; without it all tokens become invalid) and `DATA/SETTINGS/scoutro-agent-audit.jsonl` (activity, newest 20 000 entries). All files are owner-only (0600). Nothing is stored in `yacy.conf`.
- A research worker's own Scoutro token and its Clustro agent key are kept in `DATA/SETTINGS/agent-runtime/<agent>.secret` (directory 0700, file 0600) because a stored hash cannot authenticate the worker's outgoing calls. Revoking the agent deletes this file.
- Revocation is final: a revoked agent cannot be resumed or edited, and all its tokens stop working immediately.

## Automation And API

Page backend: `source/net/yacy/htroot/ScoutroAgents_p.java` (logic in `source/net/yacy/scoutro/api/AgentAdmin.java`, storage in `source/net/yacy/scoutro/agents/`).

| Endpoint | Method | Access | Backend |
| --- | --- | --- | --- |
| `/ScoutroAgents_p.html` | `GET` | admin | list or one agent (`agent=<id>`) |
| `/ScoutroAgents_p.html` | `POST` | admin + transaction token | operations (`op=...`) |

Agents themselves never use this page. They authenticate on `/scoutro/api/agent/v1/*` with `Authorization: Bearer sca_...` and discover their rights with `GET /scoutro/api/agent/v1/capabilities`. The UI route of this page is `config.agents` (`/scoutro/api/v1/ui/routes/config.agents`).
