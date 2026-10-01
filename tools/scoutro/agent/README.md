# Scoutro research worker for Clustro (`scoutro-agent-bridge`)

A small worker that takes research tasks from Clustro and answers them with
Scoutro's index: search, text evidence from indexed pages, optional summary or
classification by a language model, result with sources back to Clustro.

```text
Clustro run (content) ──list_runs (MCP, pull)──▶ scoutro-agent-bridge
                                                   │  own Scoutro agent token
                                                   ▼
                              /scoutro/api/agent/v1  (authorized per call:
                              actions, collections, limits of the worker)
                                                   │
                                   optional model (no tools, page text as data)
                                                   │
Clustro run  ◀──complete_run / fail_run (MCP)──────┘
```

- **Pull, not push.** The worker only makes outgoing calls: Clustro's `/mcp`
  (and `/webhook` for `run_started`) and Scoutro's agent API. Clustro needs no
  endpoint URL, no dispatch allowlist and no route into Scoutro.
- **Same rights as any agent.** The worker is an agent of kind
  `research_worker` in Administration → Agents & Access. It uses the agent
  path with its own token; Scoutro checks every call against its grant. It
  never uses the administrator account, never calls YaCy directly, and the
  model gets no tools.
- **Python standard library only.** It reuses `scoutro_classify.py`
  (validation, sanitizing, injection guards, classification) and the
  discovery profiles and state.

## Set up

1. In Clustro (operator account): create a connection with adapter `custom`,
   **without** endpoint URL and `isPrimary: false`
   (`POST /workspaces/{ws}/agent-connections`). Note the connection id and the
   `ak_…` key, which Clustro shows once.
2. In Scoutro: Administration → Agents & Access → New agent, kind
   "Scoutro research agent (Clustro worker)". Grant the collections and
   actions it may use (preset "Research"; crawl actions only if wanted), the
   limits, and whether it may use the language model. In step 5 enter the
   Clustro base URL, workspace id, connection id and the `ak_…` key.
   Scoutro writes the runtime secret
   `DATA/SETTINGS/agent-runtime/<agent>.secret` (0600): the worker's own
   Scoutro token and the Clustro settings. A stored hash cannot authenticate
   outgoing calls, so this file is the only plain copy of the token.
3. Start the worker. Only one worker process may serve a Clustro connection
   (Clustro has no claim/lease for pulled runs); the worker enforces this
   itself, see "Single worker per connection":

```sh
SCOUTRO_AGENT_SECRETS_DIR=/opt/yacy_search_server/DATA/SETTINGS/agent-runtime \
SCOUTRO_AGENT_URL=http://127.0.0.1:8090/scoutro/api/agent/v1 \
tools/scoutro/agent/scoutro-agent-bridge --agent agt_xxxxxxxxxxxx
```

| Variable | Meaning | Default |
|---|---|---|
| `SCOUTRO_AGENT_URL` | agent API base, must end with `/scoutro/api/agent/v1` | **required, no default**: on the Scoutro host `http://127.0.0.1:8090/scoutro/api/agent/v1`, on another host the agent listener of the reverse proxy (`docs/SERVER_AGENT_ACCESS.md`). Without it the worker exits with a message naming both |
| `SCOUTRO_AGENT_SECRETS_DIR` | directory of the runtime secrets | `DATA/SETTINGS/agent-runtime` (relative to the working directory) |
| `SCOUTRO_AGENT_STATE_DIR` | journal of runs | `<secrets dir>/<agent>.state` |
| `SCOUTRO_DISCOVERY_DIR` | discovery state for stored classifications (read only) | `<state dir>/discovery` |
| `SCOUTRO_LLM_BASE_URL`, `SCOUTRO_LLM_MODEL`, `SCOUTRO_LLM_API_KEY[_FILE]`, … | OpenAI-compatible model, as for `scoutro-discovery` | not set: no model |
| `SCOUTRO_AGENT_POLL` | poll interval in seconds | 15 |
| `SCOUTRO_AGENT_LOCK_DIR` | directory of the per-connection worker locks; must belong to the worker's user and not be writable by others | `/tmp/scoutro-agent-locks` |

`--once` handles the waiting runs once and exits (for tests and cron);
`--secret-file PATH` names a runtime secret directly. Logs are JSON lines on
stderr; they never contain tokens or keys.

## Tasks

The Clustro run content is either free text — a research question — or a
JSON object with `"scoutro_task": "1"` (`schemas/task.schema.json`):

```json
{"scoutro_task": "1", "type": "research", "query": "Kurzzeitpflege Köln", "collections": ["edelsenior-web"], "limit": 8, "summarize": true}
{"scoutro_task": "1", "type": "portal_evaluation", "profile": "edelsenior", "domains": ["example.de"], "mode": "stored"}
{"scoutro_task": "1", "type": "portal_evaluation", "profile": "edelsenior", "query": "Pflegeheim Köln", "mode": "classify", "verdicts": ["PASS", "UNSURE"]}
{"scoutro_task": "1", "type": "crawl", "url": "https://example.de/", "collection": "edelsenior-web", "depth": 1, "maxPages": 50}
```

Clustro's API is unchanged: the structure lives only in the content, and is
validated strictly (unknown fields or types fail the run with a message).

| Type | What happens | Needs |
|---|---|---|
| `research` | scoped search per granted collection, text evidence from the index per domain, optional summary whose statements must cite source ids | `search` (+ `index.evidence` for excerpts), model optional |
| `portal_evaluation` | providers by query (registrable domains of the hits) or a domain list; `stored`: classifications from the discovery state, `classify`: classification now with `scoutro_classify` (PASS/FAIL/UNSURE, evidence-bound) | profile collections inside the worker's scope; `classify` needs the model |
| `crawl` | text-only crawl within the worker's limits; the run id is the `Idempotency-Key`: Scoutro records the start before YaCy is asked and matches it to its crawl profile afterwards, so a restart of the worker or of Scoutro never starts a second crawl (an unconfirmed start fails the run with `crawl_start_unconfirmed` until the administrator resolves it); the crawl is followed until it ends or the task time is up | `crawl.start` (+ `crawl.status`, `crawl.stop`) |

## Result

`complete_run` gets a JSON text (`schemas/result.schema.json`), at most
30 000 characters (Clustro cuts silently at 32 000; the worker shortens
excerpts first and sets `truncated`):

```json
{"scoutro_result": "1", "type": "research", "status": "ok", "query": "…",
 "summary": "Sonnenhof bietet Kurzzeitpflege. [1]", "summaryStatus": "generated",
 "sources": [{"id": 1, "url": "https://…", "title": "…", "collection": "edelsenior-web", "excerpt": "…"}],
 "warnings": [], "truncated": false}
```

- `status`: `ok`, `partial` (e.g. summary failed, crawl still running),
  `no_evidence`, `model_unavailable`, `invalid_model_output`.
- Without a model nothing fails: research returns sources with
  `summaryStatus` `model_not_allowed` or `model_not_configured`;
  classification returns the stored verdicts with `status: model_unavailable`.
  No model is ever chosen automatically.
- Summary statements citing unknown sources are dropped (with a warning).
  Missing evidence gives `no_evidence` (research) or `UNSURE` (classification).
- Page text is untrusted: it reaches the model only as delimited data in the
  user message, never in the system prompt; injection-like text is flagged and
  can never produce a PASS (see `scoutro_classify.py`).
- Errors (invalid task, collection outside the scope, refused by Scoutro) end
  the run with `fail_run` and a readable message.

## Single worker per connection

Before it opens its journal or calls anything, the worker takes an exclusive
`flock` on `<SCOUTRO_AGENT_LOCK_DIR>/<sha256>.lock`. The hash covers the
normalized Clustro base URL (scheme, host, port, path; default ports and a
trailing slash removed, case folded), the workspace id and the connection id,
so another secret file, another Scoutro agent or a different spelling of the
same URL cannot run a second worker for the same connection. The second
process exits with code 3 and the message "another Scoutro research worker
already serves the Clustro connection …" before it polls, works or crawls.

- The kernel releases the lock when the process ends, also on a crash or
  `SIGKILL`; the lock file stays but carries no meaning (only for the holder's
  pid in messages). No stale-file handling is needed.
- Scope: all worker processes on **one host that use the same lock
  directory**. Processes that do not share `/tmp` (separate containers,
  systemd `PrivateTmp`) must set the same `SCOUTRO_AGENT_LOCK_DIR` on a shared
  local file system. It is not a distributed lock: do not run workers for the
  same connection on several servers.

## HTTP and credentials

Every request of the worker carries a credential (Scoutro token, Clustro agent
key or model API key). The worker therefore **never follows redirects**: a
301/302/303/307/308 answer is reported as an error (`redirect_refused`, or a
model error) and the request is not repeated at the new location, so no
credential can reach another origin and no https→http downgrade can happen.
Configure the canonical URLs (`SCOUTRO_AGENT_URL`, the Clustro base URL,
`SCOUTRO_LLM_BASE_URL`). The same rule applies to the model client of
`scoutro_classify.py`, which `scoutro-discovery` uses as well.

## Trust boundary

The worker is a program of the operator, not an agent. On the Scoutro host
it can technically reach everything on `127.0.0.1:8090` (including the public
`/solr/` and native search); its restriction to the agent's grant comes from
its code, which calls only the agent API. Clustro tasks are untrusted input
and validated against `schemas/task.schema.json`; the model gets no tools.
For network-enforced separation run it on another host with
`SCOUTRO_AGENT_URL` set to the agent listener of the reverse proxy. Details:
`docs/SERVER_AGENT_ACCESS.md`.

## Reliability

- Clustro delivers at least once; the worker keeps a journal
  (`journal.json`, 0600): a run that was answered is never worked on again.
- After a restart the worker resumes its own `active` runs (at most three
  attempts, then `fail_run`); runs of other workers are never touched.
- Before `complete_run` the worker checks the run status: a cancelled run is
  not completed, and its own running crawl is stopped.
- When Clustro or Scoutro is unreachable, runs stay waiting; the worker
  reports the error in its heartbeat (`POST /agent/v1/heartbeat`), shown in
  Agents & Access. "Clustro reachable" there means the worker itself made a
  successful Clustro call within the last five minutes.

## Tests

```sh
python3 test/scoutro-agent/test_bridge.py -v          # offline: fake Clustro, Scoutro and model
python3 test/scoutro-agent/test_single_instance.py -v # real processes: lock, crash, restart
python3 test/scoutro-agent/test_redirects.py -v       # credentials never follow redirects
CLUSTRO_URL=… CLUSTER_ADMIN_KEY=… SCOUTRO_URL=… SCOUTRO_ADMIN_PASSWORD=… \
SCOUTRO_DATA_DIR=…/DATA E2E_COLLECTION=… E2E_QUERY=… \
python3 test/scoutro-agent/e2e_clustro.py             # disposable Clustro API + Scoutro instance
```
