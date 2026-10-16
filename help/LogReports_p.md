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

## Flexible situation reports

The existing scheduler checks one plan every 30 seconds. Each due interval produces
**one aggregate report with at most one `logreport` model call**, never a fan-out of
hourly calls. There is no automatic daily compression in parallel.

| Schedule | Report interval | Automatic model calls |
| --- | --- | --- |
| Off | None | 0 |
| Hourly, 1 / 2 / 4 / 6 / 12 h | One elapsed interval ending at the due boundary | At most 1 per interval |
| Daily | Previous local execution time to this execution time | At most 1 per day |
| Weekly | Previous selected weekday/time to this weekday/time | At most 1 per week |
| Monthly | Previous selected calendar day/time to this day/time | At most 1 per month |

Default timezone is `Europe/Berlin`; valid IANA zone IDs and `UTC` are accepted.
Calendar schedules use local wall time. DST gaps move forward; an overlapping local
execution time runs once at its earlier offset. Hourly intervals count elapsed hours,
anchored to the timezone's midnight on 1 January 1970; their local clock phase can
shift at DST. Month days absent in a short month use that month's final day; `0`
explicitly selects the final day. Calendar windows may therefore have different
elapsed lengths. The requested interval is always stored as unambiguous instants.

A future next run survives restart. A run already missed at startup is skipped.
A delayed running scheduler processes only its latest completed interval, never a
queue of missed intervals. Changing or enabling a schedule starts with its next
future boundary. Existing reports and logs are never deleted by scheduling.
An in-flight request can finish after disabling; no further automatic call starts.

### Configuration and migration

`ai.logreport.schedule` is a single versioned atomic configuration value:
`1|mode|hours|HH:mm|weekday|monthday|zone`. The editor validates it before saving. The existing `ai.logreport.enabled=false` remains an emergency stop for automatic reports; saving an active/off schedule synchronizes that flag.
The existing configuration writer persists it in `DATA/SETTINGS/yacy.conf`.
The report directory remains `ai.logreport.dir` (`DATA/REPORTS/log` by default).

An empty schedule migrates `ai.logreport.enabled` and the primary legacy
`ai.logreport.period_minutes` (nearest supported hour interval) once. The old daily
time is retained in the new editor. All legacy keys, existing reports, and the
attempt journal are preserved. Since a single selected frequency now defines one
period, the old optional parallel daily compression is no longer scheduled. The UI
shows a migration notice; choose Daily explicitly if desired. Existing hourly and
daily Markdown files remain readable through the page and feeds. New files use
`report-period-<start epoch seconds>-<end epoch seconds>.md`.

The existing atomic `.generation-state.properties` journal records the next run,
claimed intervals, outcomes, and last completed interval. Claims are written before
a model request. Interrupted/failed attempts and deleted files are not automatically
retried. Explicit manual recreation is required. Attempt entries expire after 90
days; old manual requests require the explicit recreation flag. Existing report
files still prevent duplicates. Existing matching legacy hour/day files are also
recognized. This provides at-most-once automatic attempts, not automatic retries.

### Historical availability and bounded statistics

The former source was the volatile `GuiHandler` buffer (default 100,000 entries).
The default persistent FileHandler rotates 20 files of about 1 MiB each. Neither
provides guaranteed weekly/monthly history. The new report source is a local compact
statistics handler on the existing Java root logger, checkpointed by the existing
scheduler every five minutes and on clean shutdown. No additional service/database
or raw-log archive is introduced. Collection continues while report automation is
off and itself makes no model calls.

`.operational-statistics.gz` in the report directory retains **65 days**, capped at
1,561 hourly buckets. Each bucket has minute counters for entries, warnings, errors,
and monitored milliseconds, plus at most three error, three warning, and one other
redacted/truncated event samples. Samples are representative, not a complete error
listing. Stack traces and full raw logs are not archived. The uncompressed counter
payload is bounded to about 3 MiB, plus bounded samples. Checkpoints are replaced
atomically. Abrupt shutdown loses uncheckpointed statistics, normally up to five minutes; a long-running model call can delay the shared scheduler and extend this window.

No old rotating logs are imported or guessed. Initial installation, offline time,
restart gaps, missing checkpoints, and history outside retention stay unknown. Every
report starts with deterministic requested/evaluated boundaries, available entry and
warning/error counts, observed coverage bounds, monitored seconds, and a conservative
unknown-coverage duration. Zero events are distinguished from absent monitoring.
Heartbeat gaps over 90 seconds count as unknown coverage, including a scheduler delayed by a long model call. Time resolution is one
minute; report start/end require whole minutes. A long interval remains one report.

The LLM receives counters and only samples fitting the existing model context/output
budget, prioritized errors then warnings then other events. Omitted samples are
counted. If no counters or monitored coverage exist, a data-gap report is written
without a paid call. The trusted Scoutro operating context remains in the prompt;
log samples are untrusted evidence and cannot override operating instructions.

## Manual generation and protected writes

Choose start and exclusive end in the displayed schedule timezone, review the shown
period, and click **run report now**. The period must be past, minute-aligned, positive,
and at most 65 days. Invalid intervals return HTTP 400 without starting a job or making a model call. Manual reports work with automation off. An explicit recreation
checkbox permits another model call for an already attempted/completed period.
API charges are possible. During DST overlap local manual times use the earlier
offset; use a nonambiguous boundary when selecting a repeated hour.

All mutations require admin authentication, **POST**, and the existing
`transactionToken` from the page, including requests from localhost.

| Action / parameter | Meaning |
| --- | --- |
| `saveReportSettings=1` | Validate and save a schedule; skip missed history |
| `scheduleMode` | `off`, `hourly`, `daily`, `weekly`, `monthly` |
| `scheduleHours` | `1`, `2`, `4`, `6`, `12` |
| `scheduleTime` | `HH:mm` |
| `scheduleWeekday` | Monday `1` through Sunday `7` |
| `scheduleMonthday` | `1`–`31`, or `0` for last day |
| `scheduleZone` | IANA zone, default `Europe/Berlin` |
| `runReportNow=1`, `reportStart`, `reportEnd` | Start one asynchronous manual interval report; local ISO datetimes |
| `recreateReport=on` | Explicitly allow another attempt |
| `reportHour` | Compatibility-only manual one-hour request when `reportStart` is absent; uses original log source/format and the legacy server timezone |
| `deleteReport` | Explicit individual deletion; strict legacy/period filename, POST only |

Read-only admin feeds `api/logreports.json` and `.rss` retain their interface.
The legacy JSON `daily` flag applies only to legacy daily reports; new arbitrary
period reports have `daily=false`, identifiable by filename/title/content.
`api/logreportstatus.json` is admin-only and returns the existing asynchronous job
state, stable last result, duration, and partial generated text. It challenges
unauthenticated clients with HTTP 401. The completed result remains until a new job.
The page additionally displays effective state/model, next run, last completed
period, and most recent successful/failed attempt. No discovery, crawling, RAG,
TLDR, or knowledge-graph changes are made by these controls.
