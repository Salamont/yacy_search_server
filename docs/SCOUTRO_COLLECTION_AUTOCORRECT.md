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

| Family | Computed by | Origin |
|---|---|---|
| `structured` | the knowledge graph: an explicit NACE code of a JSON-LD `@type` | the website |
| `text_fit` | the knowledge graph: the categories the rules read as offered on the domain's pages, per collection vocabulary | the website |
| `llm` | Discovery: the stored classification of the site by the model | the website |
| `origin` | Discovery: the OSM object a profile's rule matched; a register entry submitted by an operator | its source system (`osm`, `register`) |

Signals derived from the website itself are **one origin**, whatever their family: the same website or the same
JSON-LD block never counts twice. An absence (`missing_industry`, `no_category`, ...) or a failure (`crawl_error`,
`unreachable`, ...) is **no signal** and is refused.

### Text fit (computed)

`KgRuntime.textFit` reads up to 8 active pages of the domain (offer pages first: `leistungen`, `angebot`, `produkt`,
...) from the indexed text and runs the rules of tier 2 once per page with **every** collection's vocabulary, so all
collections are judged on the same text; no model is called. A category the rules read as offered (on a services or
prices page, or with an explicit offer cue elsewhere; a mere mention is a `topic`) counts for the vocabulary it belongs
to. `AutoCorrectApi.textFitSignals`:

- a collection whose vocabulary has offers on the pages is spoken **for** (`offers_vocabulary_categories`); another
  collection only with at least 2 categories on at least 2 pages;
- the current collection is spoken **against** (`fits_other_vocabulary`) only when its vocabulary has no offer on at
  least 3 pages with text **and** another collection qualifies: a fit elsewhere, never an absence alone;
- a collection without a vocabulary is never judged; fewer than 3 pages with text give no signal.

The text fit is read only while the mode is not `off` (`evaluate` with `"textFit": false` leaves it out).

### Discovery signals (connected)

A Discovery batch (`scoutro_automation.submit_autocorrect`) reports the stored results of its crawled domains while the
automation is on (`init.autocorrect`, from `scoutro.collections.autocorrect.mode`), at most 10 domains per batch, each
again only when its signals changed (`autocorrect_reported` in the profile entry). Nothing is read or asked anew:

- `llm`: each profile's classification by the model: `PASS` for, `FAIL` against its collection (revision: the time of
  the classification, criteria: its criteria version). `UNSURE`, the heuristic classifier and a failed reading are no
  signal.
- `origin` (`osm:<region>`): the OSM object a profile's rule matched speaks for that profile's collection. It speaks
  against the current profile only when it matched another profile by its tags, those tags cover a key of the current
  profile's OSM rule and that rule does not match them; a match of the name text is no verdict on other profiles.

The Java side (`DiscoveryService`, action `autocorrect`) evaluates for the run's own collection only (never one the
process names), with at most 50 signals, and adds the graph's structured and text-fit signals; with the mode `off` it
answers `skipped: off` and the batch sends nothing at all.

### Source dependency

`AutoCorrect.groups` checks where each signal comes from before it counts: every website signal is `website:<domain>`;
an origin signal counts with its source system (`osm:way/1` and `osm:node/7` are both `osm`); an origin signal whose
origin names the domain or whose kind is `imprint`, `website`, `site_text` or `own_page` is the website; a signal whose
evidence another origin already states is a copy and counts with that origin. The outcome lists each such case in
`dependencies`.

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

## Statistics and acceptance (the 99 % target)

The quality target is that at least **99 %** of the automatic corrections are right. It is checked as a statistical
statement: with 95 % confidence the true precision is at least 99 %, i.e. the **one-sided 95 % Clopper-Pearson lower
bound** of the reviewed corrections is at least 0.99. The bound is exact (binomial), not an approximation:

| Errors in the sample | Reviews needed for 0.99 | Reviews needed for 0.95 (interim) |
|---|---|---|
| 0 | 299 | 59 |
| 1 | 473 | 93 |
| 2 | 628 | 124 |

(`AutoCorrect.reviewsNeeded`; the report gives the error-free reviews still needed for the current sample.)

What makes the number meaningful:

1. **A random sample.** `POST .../sample {"size"}` draws open corrections (not drawn, not reviewed, not applied)
   uniformly at random (SecureRandom), records the draw (population, drawn domains) and marks the cases. Only drawn
   cases count for the acceptance; corrections an administrator chose to review are reported (`stats.all`) but never
   accepted on, so the measurement cannot be steered by the choice of cases.
2. **Every drawn case is reviewed.** A drawn case that is not reviewed blocks the acceptance; leaving out the
   difficult ones is not possible. Judging (`correct`/`wrong`) a recorded or proposed case applies nothing, so a
   sample can be reviewed in `simulate` mode.
3. **The thresholds.** `POST .../accept` accepts only with every drawn case reviewed, at least `minReviewed` (default
   **299**) reviews in the sample, a lower bound of at least `minLowerBound` (default **0.99**), and no error of high
   severity among all reviewed corrections; otherwise `409 acceptance_not_met` with what is missing. The acceptance
   records its `stage`: `target_99` (operator release), or `interim_95` when a lower `minLowerBound` was configured:
   the 95 % level is an interim stage for a pilot and **never** the operator release (`operatorRelease: false`).
4. **The rules version.** The acceptance holds for `AutoCorrect.RULES_VERSION` (rules and signal grouping); a changed
   version refuses `apply` (`acceptance_outdated`) until a new sample is accepted.
5. **The audit after the acceptance.** A share `auditRate` (default 0.1) of the automatic corrections is drawn for the
   review (`stats.audit`); with 20 unreviewed audit cases `apply` only proposes (`audit_backlog`); a wrong audited
   correction opens the breaker. The audit is a continuing sample of the same target.

`GET /v1/collections/autocorrect` reports `stats.sample`, `stats.all` and `stats.audit` (reviewed, right, pending,
precision, lowerBound95, errorsBySeverity, reviewsForTarget, reviewsForInterim, stage) and coverage. `{"revoke": true}`
withdraws the acceptance.

Limits of the statement: the sample measures the corrections of the cases evaluated so far; a new kind of website
(another collection, other signals) may behave differently, which the audit is there to see. Severity `high` (a wrong
specialist target) is never accepted at all.

## API (administrator only, no agent route, no MCP tool)

- `GET /scoutro/api/v1/collections/autocorrect?limit=` — settings, breaker, acceptance, statistics, cases.
- `POST .../evaluate` `{"domain", "collection", "memberships", "signals": [...], "graph": true}` — outcome and the
  action of the mode; `graph` adds the knowledge graph's structured signals.
- `POST .../review` `{"domain", "verdict": "approve|reject|correct|wrong", "target", "note"}`.
- `POST .../sample` `{"size"}` — a random sample for the acceptance.
- `POST .../accept`, `POST .../breaker-reset`.

## Review page

*Settings → Collections → Correction proposals* (`ScoutroCollections_p.html`, `collections-autocorrect.js`): mode,
breaker, the sample (reviewed, right, still to review), its lower bound and the reviews still needed for 99 %, the
stage of the acceptance; **Draw a random sample**, **Check the acceptance**, **Close the breaker**; the cases with the
proposal, the signals by source, the status and whether they were drawn; **Approve** (with confirmation; a case for
review asks for the target), **Reject**, **Right** and **Wrong**. German translation and help
(`help/ScoutroCollections_p.md`).

## Rollout

1. `simulate` over the Discovery and graph signals; read the report.
2. Draw a random sample of at least 299 corrections and judge every drawn case (`correct`/`wrong`, nothing changes);
   approvals of proposals are manual decisions and may go on meanwhile.
3. `accept` (99 % target); only then `apply` with a small `maxPerDay`. Review the audit cases; any wrong case opens the
   breaker.

Rollback: set the mode to `off`; revoke automatic decisions with `POST /v1/collections/decisions/revoke`.

## Not part of WP-H (plan deviations)

- A register signal (Handelsregister) is not computed; an operator may submit it as an `origin` signal.
- OSM tags are kept by Discovery only for the keys a rule matched; the OSM signal against the current profile is
  therefore limited to tags that cover a key of its rule.

## Tests

`AutoCorrectTest` (rules, independence, source dependency, absence refused, modes, fingerprint, hysteresis, daily
limit, breaker, the random sample, the 99 % acceptance with 299 error-free and the refusal with one error, the interim
stage, the audit and its backlog, history, unreadable state, Clopper-Pearson and the reviews needed),
`CollectionDecisionsApiTest` (off by default, proposal, approval as a manual decision on the real index, manual
decision wins, the graph's structured signals, the text-fit rule, the 99 % defaults), `TextFitTest` (the running graph
reads the offered categories of a domain through every vocabulary), `DiscoveryAutomationTest` (the `autocorrect`
action only while the automation is on, the run's collection, input limits), `test_automation.py` (Discovery signals
from the stored classifications and OSM matches, nothing sent while off, unchanged signals not sent again) and
`collections-autocorrect-ui-test.mjs` (review page, EN/DE, phone).
