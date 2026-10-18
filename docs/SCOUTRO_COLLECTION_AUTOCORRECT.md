# Automatic correction of collection assignments (WP-H)

Status: data quality programme R4, work package H ([programme](SCOUTRO_DATA_QUALITY_PROGRAM.md)); not released.
**Off by default.** Builds on collection decisions ([WP-A](SCOUTRO_COLLECTION_DECISIONS.md)).

## Modes

`scoutro.collections.autocorrect.mode`:

| Mode | Effect |
|---|---|
| `off` (default) | an evaluation answers its outcome; nothing is recorded or changed |
| `simulate` | the outcome is recorded for the report (statistics, coverage); nothing changes |
| `propose` | corrections and uncertain cases go to the review queue; an administrator approves (a **manual** collection decision, applied like `POST /v1/collections/decisions`) or rejects |
| `apply` | a correction becomes an **automatic** collection decision only if the acceptance is accepted, the daily limit (`maxPerDay`, default 10) is not reached and the breaker is closed; otherwise it is proposed |

An unknown mode is `off`. An unreadable state file (`DATA/SCOUTRO/collections/autocorrect.json`) records and applies
nothing.

## Signals and independence

A signal names a family, a verdict (`against` or `for` a collection), evidence, and optionally a revision and a
criteria version:

| Family | Example | Origin |
|---|---|---|
| `structured` | an explicit NACE code of a JSON-LD `@type` (added from the knowledge graph), schema.org categories | the website |
| `text_fit` | categories with an offer cue of another collection's vocabulary | the website |
| `llm` | the Discovery classification of the site | the website |
| `origin` | OSM tags, a register entry | its own (`osm:...`, `register:...`) |

Signals derived from the website itself are **one origin**, whatever their family: the same website or the same
JSON-LD block never counts twice. An absence (`missing_industry`, `no_category`, ...) or a failure (`crawl_error`,
`unreachable`, ...) is **no signal** and is refused.

## The rule

1. At least **two independent origins** speak against the current specialist collection, at least one of them with a
   `structured` or `text_fit` signal. An LLM verdict or an OSM tag never suffices, not even together.
2. No origin speaks **for** the current collection (otherwise: review, `contradicting_signals`).
3. The target is a specialist collection only if the same rule holds for it positively and nothing speaks against it;
   otherwise the fallback collection (`sonstige`). Two qualifying targets: review.
4. A **manual decision** in scope always wins (outcome `none`, `manual_decision`); an automatic decision never
   replaces a manual one, also at the decisions API (`409 decision_manual`).
5. A domain in several specialist collections (a multiple assignment) is never corrected automatically (review).

Outcomes: `none`, `review` (with the reason) or `correct` (target, severity `medium` for the fallback, `high` for
another specialist collection).

## Protection

- **Fingerprint** of the signals, revisions and criteria: the same fingerprint never acts twice.
- **Hysteresis**: an automatically decided domain changes again only with a new revision **and** a new signal set.
- **Daily limit** of automatic decisions; **breaker**: a case marked `wrong` opens it, apply only proposes until an
  administrator closes it (`POST .../breaker-reset`).
- An outcome `none` never replaces a case; a reviewed case is kept in the history when the domain is evaluated again.

## Statistics and acceptance

`GET /v1/collections/autocorrect` reports, over the corrections an administrator reviewed (approved/correct as right,
rejected/wrong as wrong): **observed precision**, its **one-sided 95 % Clopper-Pearson lower bound**, **errors by
severity**, and **coverage** (corrections among the evaluated cases).

`POST /v1/collections/autocorrect/accept` accepts the apply mode only with at least `minReviewed` (default 59)
reviewed corrections, a lower bound of at least `minLowerBound` (default 0.95) and no error of high severity;
otherwise `409 acceptance_not_met` with what is missing. With 0 errors, 59 reviews give a lower bound of 0.9505; a
bound of 0.99 needs 299 error-free reviews. `{"revoke": true}` withdraws the acceptance.

## API (administrator only, no agent route, no MCP tool)

- `GET /scoutro/api/v1/collections/autocorrect?limit=` — settings, breaker, acceptance, statistics, cases.
- `POST .../evaluate` `{"domain", "collection", "memberships", "signals": [...], "graph": true}` — outcome and the
  action of the mode; `graph` adds the knowledge graph's structured signals.
- `POST .../review` `{"domain", "verdict": "approve|reject|correct|wrong", "target", "note"}`.
- `POST .../accept`, `POST .../breaker-reset`.

## Rollout

1. `simulate` over the Discovery and graph signals; read the report.
2. `propose`; review at least `minReviewed` corrections (each approval is a manual decision).
3. `accept`; only then `apply` with a small `maxPerDay`. Any wrong case opens the breaker.

Rollback: set the mode to `off`; revoke automatic decisions with `POST /v1/collections/decisions/revoke`.

## Not part of WP-H (plan deviations)

- The `text_fit` signal (categories of every collection's vocabulary with an offer cue, independent of the current
  membership) and the Discovery LLM and OSM signals are not computed by Scoutro itself here: they are submitted to
  `evaluate` (by Discovery or an operator). Only the graph's structured signal (explicit NACE codes of JSON-LD types)
  is added automatically.
- The review queue is an API; a view in the collections UI is open.

## Tests

`AutoCorrectTest` (rules, independence, absence refused, modes, fingerprint, hysteresis, daily limit, breaker,
acceptance, history, unreadable state, Clopper-Pearson) and `CollectionDecisionsApiTest` (off by default, proposal,
approval as a manual decision on the real index, manual decision wins, the graph's structured signals).
