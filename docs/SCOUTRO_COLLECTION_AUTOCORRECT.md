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
| `plausibility` | the knowledge graph: the website offers clearly something else (offers with structured or quoted evidence, none of the current vocabulary, no word of it on the pages) | the website |
| `llm` | Discovery: the stored classification of the site by the model; the knowledge graph's LLM tier: the reading of the home page ([Home page reading](#home-page-reading-in-the-llm-tiers-own-request), off by default) | the website |
| `origin` | Discovery: the OSM object a profile's rule matched; a register entry submitted by an operator | its source system (`osm`, `register`) |

Signals derived from the website itself are **one origin**, whatever their family: the same website or the same
JSON-LD block never counts twice. An absence (`missing_industry`, `no_category`, ...) or a failure (`crawl_error`,
`unreachable`, ...) is **no signal** and is refused.

### Text fit (computed)

`KgRuntime.textFit` reads up to 8 active pages of the domain (offer pages first: `leistungen`, `angebot`, `produkt`,
...) from the indexed text and runs the rules of tier 2 once per page with **every** collection's vocabulary, so all
collections are judged on the same text; no model is called. A category the rules read as offered (on a services or
prices page, or with an explicit offer cue elsewhere; a mere mention is a `topic`) counts for the vocabulary it belongs
to. **Text fit 2** (rules version 3): a role word (`hersteller`, `händler`, `handel`, `dienstleister`, `lieferant`,
`anbieter`, `produzent`, `vertrieb`, ...; `BusinessRules.isRoleCategory`) names what an organisation is, never what it
offers, and is no fit to a vocabulary, not even as a topic: the word "Hersteller" of a nut confectioner no longer speaks
for a construction collection. Per collection the result counts `offered` (categories read as offered) and `topics`
(categories only named) apart. The domain's pages are found through the host index (`kg_doc_host`, one row per host,
`KgRuntime.domainHosts`); only a domain whose pages have no host id is read by its url as before.
`AutoCorrectApi.textFitSignals` (criteria `text_fit/2`):

- a collection whose vocabulary has offers on the pages is spoken **for** (`offers_vocabulary_categories`); another
  collection only with at least 2 categories on at least 2 pages;
- the current collection is spoken **against** (`fits_other_vocabulary`) only when its vocabulary has no offer on at
  least 3 pages with text **and** another collection qualifies: a fit elsewhere, never an absence alone;
- a collection without a vocabulary is never judged; fewer than 3 pages with text give no signal.

The text fit is read only while the mode is not `off` (`evaluate` with `"textFit": false` leaves it out, together with
the plausibility, which reads the same pages).

### Plausibility (computed, no model)

A domain can be in a collection whose vocabulary has no word for what it offers, and no other collection's vocabulary
may have one either (a nut confectioner in a construction collection): the text fit then never speaks against it.
`KgRuntime.offerProfile` reads, from the graph only, the services and products the domain's active pages offer:

- an offer counts with **tier 1** evidence (JSON-LD: an `offers` relation, or the price of a `Product`/`Service`
  through its `Offer`) or **tier 3** evidence (the model's offer or price, with the verbatim quote it was published
  with); a reading of the rules alone (tier 2, for example the labels of a price list) never counts;
- per service: its name (the same name on several pages is one offer), tiers, categories and pages; per collection with
  a vocabulary: how many of the services have a category of it.

`AutoCorrectApi.plausibilitySignals` speaks **against** the current collection (family `plausibility`, kind
`foreign_offers`, criteria `plausibility/1`) only with a positive finding, all of these:

1. at least 3 different offered services or products on at least 2 pages (as above);
2. none of them has a category of the current collection's vocabulary;
3. the text fit reads neither an offered category nor a topic of that vocabulary (role words excepted) on at least one
   page with text;
4. the current collection has a vocabulary.

Otherwise there is no signal and the domain is uncertain. The evidence names at most 5 services with their tiers. The
signal is the website's (origin `website:<domain>`) and neither structured nor a text fit: alone or with an OSM tag it
makes a case for the administrator (`review`), never a correction; with the OSM rule for the collection the
contradiction is visible (`review / contradicting_signals`).

### Assessment of a domain (report)

`AutoCorrectApi.assess` sums up a domain in one collection for the report and the choice of the cases the model may be
asked about, never as a rule of the automation: `suspect` with a signal against the collection (structured, text fit,
plausibility, LLM), `match` with an offered category of its vocabulary or a structured signal for it and nothing
against, else `uncertain` (too little to judge).

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

## Plausibility scan of the indexed domains (no model)

The Discovery batch reports only the domains of its own runs, and only when its own signals change; the graph's signals
of the many domains indexed before would never reach the automation. The scan assesses them in steps on an
administrator's call (no daemon): `POST /v1/collections/autocorrect/scan`
`{"maxDomains": 1..200 (50), "collection": optional, "restart": false, "domains": optional}`.

- **Order and cursor.** The registrable domains of the graph's active pages with at least one specialist collection
  (`KgRuntime.domainCollections`, through the indexes; the fallback collection alone is never scanned), sorted. The unit
  is the domain: all its collections in the same step. The cursor (the last domain done) and the pass are kept in the
  correction state (`scan`); a step after a restart of Scoutro goes on from it, the end of a pass is `done` and the next
  call begins the next pass. Pausing is not calling it.
- **Only while the graph allows it.** A step runs only while the graph runs, is not paused and its gates are open
  (indexing queue, load, heap, YaCy's online caution, the same as for the graph's own scans); otherwise
  `503 scan_busy` with the reason, the cursor stays. No model is called and nothing is written to the graph.
- **Two fingerprints per domain and collection.** The rules' fingerprint (the content hashes of the domain's pages, the
  vocabularies' version, the rules version, the collections, the domain's collection decisions) decides whether the
  pages are read again (text fit, offer profile, structured data); the input's fingerprint (the rules' plus the stored
  Discovery classifications of the domain, also those without a signal, the home page's reading by the model and the OSM
  signals of the domain's case) whether the domain is assessed again at all. A new classification is assessed without
  reading the pages again; an unchanged domain is skipped.
- **Signals.** The rules' (structured, text fit, plausibility), the stored Discovery classifications read from
  `state.json` as `scoutro_automation.autocorrect_signals` maps them (the model's `PASS` or `FAIL` only; a heuristic or
  failed classification and `UNSURE` are shown, but no signal), the home page's reading by the model (when current), and
  the OSM signals of the domain's last case (carried over, so the case never flips between Discovery's submission and the
  scan's).
- **One submission per domain.** While the mode is not `off` and a collection of the domain has a signal against it, the
  domain is submitted once, through the unchanged rules: the current collection is the alphabetically first specialist
  collection with a signal against it, the memberships are all of the domain's, the signals those of all its
  collections. A domain in several specialist collections stays a case (`multiple_assignment`); an unchanged signal set
  answers `unchanged`. A scan has the website's origin and the case's OSM signals only: it never makes a correction that
  would not be made without it. In the mode `off` it records the assessments and submits nothing.
- **Targeted run.** `"domains": [...]` (at most 200 registrable domains) assesses exactly these, also domains no
  Discovery run crawled, and leaves the cursor alone: the run right after a classification of the uncertain domains.

### Assessments

The correction state keeps, per domain, collection and **source**, what each source found (`assessments`); no source
replaces another's:

```json
"assessments": {
  "drolshagens.de": {
    "bauteamcheck-web": {
      "rules":     {"result": "suspect", "rulesFingerprint": "…", "rulesVersion": "3", "signals": [...], "evidence": [...],
                    "offered": 0, "topics": 0, "pages": 8, "offers": 10, "at": 0},
      "discovery": {"result": "FAIL", "confidence": 0.97, "backend": "llm", "error": "", "criteriaVersion": "…",
                    "classifiedAt": "…", "signal": true, "at": 0},
      "kg_home":   {"result": "mismatch", "...": "see the home page's reading"},
      "inputFingerprint": "…"
    }
  }
}
```

The result of a pair is derived, never stored: `suspect` if a source speaks against the collection with a signal, else
`match` if one speaks for it, else `uncertain`. A collection the domain no longer belongs to is marked `stale`, never
deleted. At most 20 000 pairs: beyond, the assessed domains without an open case and with the oldest assessment go
first; the cases are never touched. The state is written once per step; an unreadable state records and submits
nothing. `GET /v1/collections/autocorrect/assessments?result=&collection=&source=&limit=` gives the counts per
collection (derived results, sources, stale) and the newest pairs.

### Uncertain domains: a classification only for them

The rules decide most domains without a model. For the `uncertain` ones the existing Discovery classifier may be asked
(one model request per domain, no new pipeline, no new prompt):

1. Export the list: `GET .../assessments?result=uncertain&collection=bauteamcheck-web&limit=5000` (the `domain`s).
2. Classify a **sample of 50** first: `scoutro-discovery classify --profile bauteamcheck --domain-file uncertain.txt
   --max 50` (with `SCOUTRO_DISCOVERY_DIR`, `SCOUTRO_CONFIG_ROOT` and the model's `SCOUTRO_LLM_*` of the Discovery
   tools; `--dry-run` first).
3. Right after it: `POST .../scan {"domains": [the same list]}`, so the new classifications reach the automation at once,
   also for domains no Discovery run crawled (`submit_autocorrect` reports only domains with a crawled profile entry).
4. Look at the results, then the rest in bounded runs (`--max`).

Without the targeted run the regular scan takes the new classifications in its next pass (the input's fingerprint), and
the Discovery batch for its own domains.

## Home page reading in the LLM tier's own request

New domains need no request of their own: the knowledge graph's LLM tier reads every home page anyway. With
`scoutro.kg.llm.collectionFit=true` (default `false`) its first request of a home page also asks whether the website's
main offering fits its collections. One request per domain and content, a few tokens more, no other request changed.

- **Only then.** The setting is on and `scoutro.kg.llm.structuredOutput` is `none` or `json_object` (no schema with
  `additionalProperties:false` goes to the provider; with `auto` or `json_schema` it stays off). The Discovery profiles
  (`profiles.json`, the same file as `scoutro.discovery.configRoot` / `SCOUTRO_CONFIG_ROOT` / `DATA/SCOUTRO/config`) are
  readable. The page is a home page (`/` or an index file), its reading's first request (never a continuation, never a
  later part of the plan), in at least one specialist collection with a profile, and at least one of them has no
  reading of this content (content hash) under these criteria (`criteria_version` of the profiles).
- **Unchanged:** the system prompt, the schema, the extractor version and the prompt hash; every other request and its
  cache key byte for byte. The question is in the user message only, before the page's DATA block (operator text, no
  page data). Its cache key has the question added, so an answer with a verdict is never taken for one without.
- **The question** (at most 500 characters, about 150 tokens by the request budget's estimate, which counts it): the
  assigned collections (at most 3, those without a current reading first) with their profile's topic (100 characters,
  60 each for two or three), the other profiles by id and label (40 characters each; those whose vocabulary the rules
  read on the page first, then alphabetical, dropped from the end when the length runs out), and a fixed instruction
  that names itself the exception to rule 9 of the system prompt ("entities, claims and values, nothing else"). When
  the assigned collections alone do not fit, the question is not asked; the reading is `uncertain` (`fit_budget`).
- **The answer:** one more top-level field, `collection_fit`:
  `{"verdicts": {"<assigned id>": "match|mismatch|uncertain", ...}, "best": "<a named id>|none", "quote": "<verbatim page
  text, at most 200 characters>"}`. It is split off before the extraction's check, which is unchanged (without the
  question the field is still `unknown_field`), and checked apart: an id not asked makes the whole field invalid, a
  missing one or a value outside the enumeration is `uncertain`, `best` not named is `none`. **Without a quote found in
  the page text every verdict is `uncertain`, `match` included, and `best` is `none`**: an unquoted match would block a
  correction just as an unquoted mismatch would cause one. A missing (`fit_missing`), invalid (`fit_invalid`) or
  unquoted (`fit_unquoted`) field leaves the extraction accepted. The verdict is kept with the cached answer (a cache
  hit keeps it) and never becomes a statement or evidence.
- **Where it goes.** After the page is published, the verdict is recorded per assigned collection as the source
  `kg_home` in the [assessments](#assessments) (`result`, `best`, `quote`, `model`, `criteriaVersion`, `contentHash`,
  `docId`, `status`, `signal`, `at`). A failure there is counted and never touches the extraction. With a `mismatch` and
  the mode not `off`, the domain is assessed at once by a targeted scan (`{"domains": [domain]}`; while the graph is
  busy, the next pass takes it).
- **Signals** (family `llm`, kind `kg_home_fit`, the website's origin), only while the reading is current: the home
  page's content hash and the profiles' criteria version unchanged and its collection not `stale`. `mismatch` speaks
  against its collection, `match` for it, `uncertain` gives none; `best` is spoken for once, unless a current reading
  speaks against it. They count in every evaluation of the domain (`graph: true`, the scan, Discovery's submissions)
  and only by the unchanged rule: an LLM verdict never suffices, not with OSM either; a `mismatch` alone makes at most a
  case for review.
- **Counters** (`GET /scoutro/api/v1/kg/status`, `llm.processed.collectionFit`, in memory since the start): `asked`
  (fresh requests with the question), `status` (`valid`, `fit_missing`, `fit_invalid`, `fit_unquoted`), `verdicts`
  (`match`, `mismatch`, `uncertain`), `budgetSkipped`, `delivered`, `sinkErrors`, the extraction's refusals with and
  without the question (`refusedWithQuestion`, `answersWithoutQuestion`, `refusedWithoutQuestion`) and the measured
  tokens of both kinds of request (`tokensWithQuestion`, `tokensWithoutQuestion`: answers with the provider's usage,
  average prompt and answer tokens). No page text, no quote. The share of `valid` answers decides whether a system prompt
  of its own for home pages is needed (a later stage: a prompt hash of its own).
- **Not for:** the model quality test (it sets its own collections; the question stays off and its fingerprint is
  unchanged). Pages read before the setting was switched on are asked when their home page is read again (its content
  changes) or through Discovery's classification of the uncertain domains (above).

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
another specialist collection). A case under review carries `suggested` (rules version 3), only a hint for the
administrator and no part of the fingerprint: the specialist collection for which the rule of a target holds, else the
one other specialist collection the signals speak for with nothing against it, else the fallback collection; the review
page preselects it for **Approve**, nothing moves before the approval.

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
- `POST .../scan` `{"maxDomains", "collection", "restart", "domains"}` — one step of the plausibility scan.
- `GET .../assessments?result=&collection=&source=&limit=` — the scan's assessments (sources `rules`, `discovery`,
  `kg_home`).
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
stage, the audit and its backlog, history, unreadable state, Clopper-Pearson and the reviews needed; the plausibility as
a website signal that is never enough, `suggested`, an acceptance of rules version 2 outdated),
`PlausibilityScanTest` (steps and cursor, a busy graph, two fingerprints, a new Discovery classification, home page
reading or OSM signal assessed without reading the pages, assessments per collection and source, stale collections, one
submission per domain, the targeted run, the fail-safe state, the Discovery fields of `state.json`; the home page's
reading recorded per collection, counted only while current, unquoted without a signal, `best`, the targeted scan after
a mismatch), `CollectionFitTest` (the LLM tier with a model stand-in: only a home page's first request asks, the system
prompt, schema and prompt hash of 0.11.5, every other request and cache key byte for byte, the question within 500
characters and 150 tokens, a longer one not asked (`fit_budget`), a missing or unquoted field `uncertain` with the
extraction accepted, the field refused as before on a request without the question, a verdict per collection, a cache hit
keeps the verdict, never a statement, a failing sink, off with a schema sent),
`PlausibilityTest` (the graph's offer profile and the plausibility on a running graph: foreign offers, a construction
company with merchandise, with its services, a price list read by the rules alone, a short website, a collection
without a vocabulary, the host index; the scan on a running graph without a model and without a graph write),
`CollectionDecisionsApiTest` (off by default, proposal, approval as a manual decision on the real index, manual
decision wins, the graph's structured signals, the text-fit rule, the 99 % defaults), `TextFitTest` (the running graph
reads the offered categories of a domain through every vocabulary; now part of `scoutro-collection-decisions-test`),
`DrolshagensPilotTest` (the production case: the role word is no fit, one product is no finding, the shop's products are
a plausibility case, with the OSM rule for the collection `contradicting_signals`, `suggested` the fallback, no model
call), `DiscoveryAutomationTest` (the `autocorrect`
action only while the automation is on, the run's collection, input limits), `test_automation.py` (Discovery signals
from the stored classifications and OSM matches, nothing sent while off, unchanged signals not sent again) and
`collections-autocorrect-ui-test.mjs` (review page, EN/DE, phone; the suggested target preselected and named).
