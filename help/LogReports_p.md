---
page: htroot/LogReports_p.html
help: help/LogReports_p.md
title: Log Reports
package: monitoring-performance
access: admin
kind: admin-page
backend_java: source/net/yacy/htroot/LogReports_p.java
---

# Log Reports

## Purpose

Log Reports summarize notable events from YaCy logs.

Use it to read operational history without scanning raw log files first.

## What You Can Do Here

- Log Reports summarize notable events from YaCy logs.
- Filter or limit the view to the symptom being investigated.
- Use the observation to decide the next crawler, index, network, or configuration action.

## Page Architecture

Monitoring pages read live peer state from queues, logs, network tables, memory counters, or process trackers. They are safest when used first as observation tools and only then as entry points to tuning pages.

| Control | Meaning | Values or examples |
| --- | --- | --- |

## Correct Use

Use monitoring pages as evidence, not as guesses. Capture the current state, then connect it to the user-visible symptom: slow search, missing documents, stuck crawl, memory pressure, network isolation, or unexpected access.

## Access And Safety

Administrator access is required. YaCy protects `_p` pages as administration pages.

Protected related endpoint(s): `/LogReports_p.html`.

## Automation And API

Page backend: `source/net/yacy/htroot/LogReports_p.java`.

| Endpoint | Method | Access | Backend |
| --- | --- | --- | --- |
| `/LogReports_p.html` | `POST` | admin | `source/net/yacy/htroot/LogReports_p.java` |

### Parameter Guide

The table explains values that an agent or script must set deliberately. Parameters not relevant to a task should be omitted or left at the page default. Low-level generated parameters are omitted when they are only meaningful inside the rendered YaCy form.

| Parameter | Meaning and valid values | Care |
| --- | --- | --- |
| `deleteReport` | Deletion or termination action. Use only with explicit intent. | Can remove data, stop work, expose access, or make a broad operational change. Use only with explicit confirmation and an exact target. |

Example request shape:

```http
POST /LogReports_p.html
Content-Type: application/x-www-form-urlencoded

runReportNow=...&deleteReport=...&report=...
```

## What To Expect

Expect observations: counts, logs, queues, timing, network rows, thread states, or resource values. Monitoring does not fix the issue by itself; it points to the next page or setting to change.

## Related Pages

- `LLMSelection_p.html`

## Automatic reports, schedule and status

**Automatic reports – settings** uses the existing `ai.logreport.enabled`,
`ai.logreport.period_minutes` (default 60), and
`ai.logreport.daily_compression.enabled` (default true). The new
`ai.logreport.daily_time` defaults to `00:00` in the server/log timezone,
which is displayed explicitly. A multi-hour interval schedules separate existing
one-hour buckets, not a new aggregate format. DST gaps move the daily run forward;
repeated local hours have one filename and are processed once. The existing
scheduler checks due times every 30 seconds, without another service.

Disable prevents new automatic model calls; an already running request may finish.
Reports and raw logs are retained. Enabling, changing the schedule, and restarting
open a fresh collection window instead of replaying missed hours. Existing daily
reports prevent rebuilding their hours. A durable attempt journal in the report
directory also prevents silent paid retries after deletion, failures, or interruption.
An uncertain/failed attempt needs an explicit manual recreation. Journal entries are
bounded to 90 days; automatic scheduling never reopens older windows. Manual
requests older than that require the explicit recreation checkbox, even when
historic files have been deleted.

The page displays the assigned model, actual activation state, last successful/failed
creation, result, and next due time separately for hourly and daily reports. Existing
legacy reports do not retroactively acquire creation-status records. Daily
consolidation retains hourly reports. Before consolidation, pending eligible hours
are flushed; this may cause an hourly call at the daily due time as well as at its
regular interval. There is no new aggregation pipeline.

## Manual generation and protected writes

Choose a local report hour and review the displayed one-hour period. The running
hour contains only logs available so far. The explicit button may call the assigned
`logreport` model even with automation off; API charges are possible. Recreating an
already claimed/completed hour requires the separate recreation checkbox. A current
hour written manually is not automatically rebuilt later. Missing logs/model or
model failures are reported through the existing asynchronous job/status endpoint.

All mutations require administrator authentication, **POST**, and the existing
`transactionToken` obtained from this page. Form fields:

- `saveReportSettings`, `automaticEnabled`, `dailyEnabled`, `periodMinutes`
  (integer 1–1440), `dailyTime` (`HH:mm`);
- `runReportNow`, `reportHour` (local ISO datetime), optional `recreateReport`;
- `deleteReport` (strict report filename). Deletion via GET is rejected.

The read-only feeds retain their existing interfaces and access policies.
`api/logreportstatus.json` now challenges unauthenticated clients with HTTP 401
instead of an unchallenged JSON error, so browsers can authenticate polling.
A completed manual-job result remains readable until the next job; page rendering
can no longer consume it before the polling client sees it. Settings use the existing atomic configuration persistence;
the attempt/schedule journal is replaced atomically in the existing report directory.
Trusted boolean/load configuration is included in the budgeted report prompt.
Logs and old reports remain evidence, never operating instructions.
