# Anthropic: isolated live test and first benchmark (prepared, not run)

Status: 2026-10-18, PR #88 (`ANTHROPIC` service). This page prepares the first real Anthropic calls. It is **not**
an approval: the operator decides whether and when to run it. Nothing here touches Olares, production DATA or the
production key store.

## What is already proven without a key

- Every request path carries `anthropic-version: 2023-06-01` and the Bearer key, no `temperature`; the model list asks
  for one page (`limit=1000`); a compliant format probe is shown as `ignored` (tests in PR #88).
- `test/scoutro-ui/anthropic-live-check.py` itself: its dry run (default) starts a disposable peer and a local stand-in
  for Anthropic and passes 43 checks for three models; its cost cap refuses a request whose worst case would exceed it
  (tested with a 0.006 USD cap: the third request was refused, nothing forwarded, the run failed).

## What only a live call can show

1. `GET /v1/models` with the Bearer key (the native list documents `x-api-key`; the compatibility layer documents
   Bearer).
2. That the compatibility layer ignores Scoutro's extra fields (`num_ctx`, `enable_thinking`, `reasoning_effort`,
   `stop`) instead of refusing them.
3. Whether the default thinking of the Claude 5 models leaves an answer within `max_tokens` (thinking counts as output).
4. Real token counts and latency.

## Preconditions

- A dedicated Anthropic workspace for Scoutro tests with a **monthly spend limit** set in the Console (for example
  5 USD), and an API key of that workspace only. A key of one workspace needs no `anthropic-workspace-id`; for a key
  that can act on several, export `ANTHROPIC_WORKSPACE_ID`.
- A development machine with the PR #88 head, `ant compile`, JDK 17+, Python 3. Not Olares, no production DATA.
- The key only in the environment of the one command (`ANTHROPIC_API_KEY`), never in a file, ticket or chat. After the
  test, revoke or rotate the key if it was shared with anyone.
- Prices checked on https://www.anthropic.com/pricing. The script carries the list prices of 2026-10-06 (USD per million
  tokens): Haiku 5.5 0.10 / 0.50, Sonnet 5.5 2 / 10, Opus 5.5 4 / 20. A change is passed as `--price MODEL=IN,OUT`.

## Stage 1: live check (cost cap 0.25 USD)

```sh
ant compile
python3 test/scoutro-ui/anthropic-live-check.py                      # dry run first, must PASS
ANTHROPIC_API_KEY=... python3 test/scoutro-ui/anthropic-live-check.py --live \
    --models claude-haiku-5-5 --max-usd 0.25 --out anthropic-live-haiku.json
```

Per model: the model list once, then a chat probe (`max_tokens` 1024), the format probe (`response_format`, 256) and a
streamed RAG chat (1024), all through Scoutro's own code on a disposable peer. The tap in front of Anthropic counts every
request (cap 12), checks the headers, prices each answer from its reported usage and refuses (HTTP 429, not forwarded)
every request whose worst case would exceed the cost cap.

| Models | Requests | Worst case | Expected |
|---|---|---|---|
| Haiku 5.5 | 4 | < 0.002 USD | < 0.001 USD |
| + Sonnet 5.5 | 7 | < 0.03 USD | < 0.01 USD |
| + Opus 5.5 | 10 | < 0.07 USD | < 0.03 USD |

Start with Haiku alone; add `--models claude-haiku-5-5,claude-sonnet-5-5,claude-opus-5-5` only after it passed.

**Pass:** `result: pass`; every upstream request with `anthropic-version`, Bearer, no `x-api-key`, no temperature;
model list 200 with the model; chat and RAG chat answered; format probe 200 or 400 (Scoutro shows `ignored` either way
for 200). **Stop and report** on: a 400 naming one of Scoutro's fields (then the field must be left out for Anthropic,
a code change), a 401/403 on the model list (Bearer not accepted there), empty answers at 1024 tokens (thinking eats
the budget: raise `max_tokens` of Anthropic rows), any refusal by the local caps.

## Stage 2: first benchmark (quality check, cost cap per model)

The existing quality check (`/scoutro/api/v1/kg/llm-quality`, also on the LLM selection page) runs the knowledge test set
`kg-reference` v2 (10 cases, one request each, every request at most twice) against a production model row, with a
plan before anything is sent and caps of calls, tokens and cost. On a disposable or development peer (never Olares):

1. Add one `ANTHROPIC` row per model with `knowledge` off (the quality check does not need the usage switched on) and
   `max_tokens` 2048 or more.
2. Ask for the plan with the price; it shows the worst case before any call. Measured on the PR #88 head with
   `max_tokens` 2048:

| Model | Planned / worst-case calls | Prompt tokens estimated / worst | Worst-case cost | Expected |
|---|---|---|---|---|
| Haiku 5.5 | 10 / 20 | 17 979 / 64 256 | about 0.03 USD | < 0.02 USD |
| Sonnet 5.5 | 10 / 20 | 17 979 / 64 256 | 0.54 USD | about 0.15–0.30 USD |
| Opus 5.5 | 10 / 20 | 17 979 / 64 256 | 1.08 USD | about 0.30–0.60 USD |

3. Start with `maxCost` equal to the worst case (the check stops at the cap), Haiku first. Whole stage: at most
   about 1.7 USD; with the workspace limit of 5 USD nothing above that can be charged.

The structured output of Anthropic is `schema_unverified` (its format is never `supported`); the validator decides
every answer as for any other model, so the benchmark compares models on the same rules.

## What to report back

- The report JSON of stage 1 (it never contains the key) and, for stage 2, the plan and result JSON of each run.
- The decision this enables: whether an Anthropic row may be configured on Olares (a separate rollout of PR #88, with
  its own backup and approval). The first productive use would be one usage at a time, with a cost cap.
