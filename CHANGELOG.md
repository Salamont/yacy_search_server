# Scoutro changelog

Scoutro is an independent community project based on YaCy (GPL-2.0-or-later).

## Unreleased

- Correction automation of collection assignments (data quality programme WP-H, not released, **off by
  default**): modes off/simulate/propose/apply (`scoutro.collections.autocorrect.mode`). A correction needs two
  independent origins against the current collection, one with a structured or text-fit signal; the website's own
  signals are one origin, OSM or an LLM verdict never suffice, absences and crawl errors are no signals; manual
  decisions always win. Fingerprint, hysteresis, daily limit, breaker. The knowledge graph computes the structured
  and the text-fit signals (the categories the rules read as offered on the domain's pages, through every
  collection's vocabulary, no model); Discovery reports its stored LLM classifications and OSM matches while the
  automation is on; signals count by their source (one source system, copies with their origin). Apply acts only
  after a statistical acceptance on a random sample: every drawn case reviewed, 299 reviews and a one-sided 95 %
  Clopper-Pearson bound of 0.99 by default (the 95 % level is an interim stage only), then an audit of the
  automatic corrections. Review page under Settings > Collections. Routes `/v1/collections/autocorrect*`
  (administrator). See [autocorrect](docs/SCOUTRO_COLLECTION_AUTOCORRECT.md).

- Rule-based review of stored LLM facts (data quality programme WP-G, not released): `GET|POST /v1/kg/tier3-review`
  applies the current rules to tier-3 evidence without any model request (prices quoted as benefits, donations,
  subsidies or unclear amounts; prices without an amount; vocabulary facts the page's vocabularies do not contain).
  Dry run by default, resumable steps; applied findings are automatic assessments (revocable). See
  [tier-3 review](docs/SCOUTRO_KG_TIER3_REVIEW.md).

- Extraction semantics (data quality programme WP-F, not released; knowledge graph schema 8, vocabulary 4,
  extractors jsonld 6 and rule 7): `price` is only an offer price; statutory benefits, donations, subsidies and
  unclear amounts are `monetary_amount` with a `role` (one deterministic classifier for JSON-LD, rules and the LLM
  tier, applied to cached LLM answers too, LLM extractor version unchanged). A price valid from a later date is
  `not_yet_valid`. JSON-LD `seller`/`offeredBy` offer a product, `manufacturer` is `manufacturer_of`, a product's
  `brand` is `has_brand`; a priced product without seller is the declared operator's own offer (confidence 0.6).
  Outside services and prices pages a category needs an offer cue, otherwise it is a `topic`. Prices and amounts are
  archived observations; an identity merge keeps the evidence's source revision. The new versions re-read tiers 1
  and 2 once (no model). The business view shows the amounts that are no price with their role (`amounts`). Two
  identity changes are switched, **off by default**: `scoutro.kg.identity.productUrl` (a service joins the one with
  the same own product page and the same name) and `scoutro.kg.identity.providerServiceKey` (the service of a
  provider named by JSON-LD `provider` or the LLM tier joins that provider's service); switched on, every page is
  read again once with the rules. `POST /v1/kg/identity-preview` shows their effect on one domain in a rolled-back
  transaction (nothing written, no model). See [extraction semantics](docs/SCOUTRO_KG_EXTRACTION_SEMANTICS.md).

- Assessments of graph facts (data quality programme WP-E, not released; knowledge graph schema 7): an
  administrator withdraws or reclassifies the evidence of one source, host or domain for a predicate (optionally
  value, subject, tier) after a preview; the same fact from an independent source stays. Withdrawn evidence is copied
  field by field, dependent derived industries go along, archived observations become `withdrawn`/`corrected`. The
  publisher holds covered claims back across re-extraction, the LLM cache and recrawls; a new source revision marks
  the assessment for review. Revoke restores the evidence exactly. Rebuilds carry assessments, backups contain them,
  a full reset keeps them. An operator statement (`assert`) adds a missing fact for one source with a quote that must
  be in the indexed text: evidence of tier 4 (kind `operator`) that counts as stated, survives re-extraction, purges
  and rebuilds, is never withdrawn by a rule and marks itself for review when the source changes (`kg_evidence` is
  rebuilt once by the schema step to allow tier 4). New routes `/v1/kg/assessments*` (administrator only) and an
  **Assessments** view on the knowledge graph page. Rollback to an earlier build needs the upgrade copy taken before
  schema 7. See [assessments](docs/SCOUTRO_KG_ASSESSMENTS.md).

- Knowledge graph on a collection move (data quality programme WP-D, not released): when only a page's collections
  change and with them the vocabulary context, tiers 1 and 2 read that page again (rules, no model) and the LLM's
  categories, industries and audience segments the new vocabularies do not contain are withdrawn (history kept);
  relations, names and identities stay. A page skipped as `not_selected` gets its first enrichment when it enters an
  LLM collection; a move alone never asks the model. New settings `scoutro.kg.llm.maxRequestsPerDay` (daily request
  cap, off by default) and `scoutro.kg.llm.weight.<collection>` (scan share); status `llm.dailyRequests`,
  `llm.weights`, `sync.processed.rescopedExtractions`. No schema change, no global re-extraction.
  See [collection move](docs/SCOUTRO_KG_COLLECTION_MOVE.md).

- Discovery and collection decisions (data quality programme WP-C, not released): a pair refused by a decision or a
  collection conflict waits (`deferred`) instead of being tried in every batch; such refusals are definite outcomes,
  never unknown starts; OSM candidates keep the matching rule and tags (`osm_match`); `classify` finds the pages of a
  moved domain; the agent bridge reads the runtime profiles. No change to the state file version.

- Durable collection decisions (data quality programme WP-A, not released): a decision fixes the collections of a
  domain, host or path; every index write (all crawl paths, WorkTables replays, the recrawl job, error documents)
  follows it, so a corrected wrong assignment is never restored by a crawl. The fallback collection `sonstige`
  (`scoutro.collections.fallback`) takes already crawled pages only through a decision, never a new crawl. Fail-safe
  files (checksums, backups, journal, marker): with unreadable decisions index writes and crawl starts are refused
  until an administrator recovers them. New routes `/v1/collections/decisions*`; reassign records its moves as
  decisions; crawl starts answer `collection_decided` / `collection_fallback_only`; Discovery no longer refuses a
  decided multiple assignment. No schema change, no re-extraction, no LLM request.
  See [collection decisions](docs/SCOUTRO_COLLECTION_DECISIONS.md) and the
  [programme](docs/SCOUTRO_DATA_QUALITY_PROGRAM.md).

- Knowledge graph quality metrics and a derived facet without `read_timeout` (data quality programme WP-B, not
  released): new administrator route `GET /v1/kg/quality` with counting units (names, explicit/derived/missing
  industries, providers, prices without provider, duplicate candidates, frequent service names, evidence by tier,
  empty excerpts; merged entities excluded). The derived projection reads in chunked read transactions with SQL
  filters and computes an origin's suggestion groups once; facet counts are cached per data version. Same results,
  no schema change. See [quality metrics](docs/SCOUTRO_KG_QUALITY_METRICS.md).

## 0.10.9 / 1.942-scoutro.33 — patch: service offer evidence before publication

Release candidate stacked on application PR #73; not published.
[Release, upgrade, rollback and acceptance](docs/SCOUTRO_RELEASE_0.10.9.md).
The original heading/list review findings are closed. The additional standalone
price/provider finding remains **open and explicitly deferred**; no further
price-evidence or provider-check change is included.

- Service names proposed by the LLM need independent source offer evidence;
  flat navigation and boilerplate no longer inherit arbitrary earlier offer
  headings or type themselves through words in their names. Explicit menu
  offer lists and services supported by content remain valid.
- `offers` additionally checks the named provider's offer. Publication
  preparation applies the same checks to cached subsets and omits dependents
  of rejected services. New diagnostic: `claim_offer_not_stated`.
  See [service offer evidence](docs/SCOUTRO_SERVICE_OFFER_EVIDENCE.md).
  Prompt, model selection, extractor version and existing graph data are unchanged;
  no automatic re-examination or rollout is included.
- Offer lists require the heading's provider binding and inherit its negation;
  mentions of third-party offers and denied lists cannot publish a positive
  operator offer. Prices in a denied list cannot bypass the check. Genuine
  positive lists remain supported. This is not included in published 0.10.8;
  see the [next release/package handoff](docs/SCOUTRO_SERVICE_OFFER_RELEASE_HANDOFF.md).

## 0.10.8 / 1.942-scoutro.32 — patch: the quality check with a fixed baseline and a diagnosis of each reference page

Patch of the installed 0.10.7. See the
[release, upgrade and rollback notes](docs/SCOUTRO_RELEASE_0.10.8.md).
Only the quality check of the LLM selection changes (evaluation 3, diagnosis
1). Knowledge graph schema stays **6**, the extractor identity of tiers 1 and 2
(JSON-LD 5, rules 6), the relation rules 5, the LLM extractor version
(`llm/4`), the default prompt and every setting stay: no re-check, no
re-extraction, no LLM request by the upgrade. Results of evaluation 2 are kept
unchanged and shown as stale. Integrates #71.

### fix: a fixed baseline of the quality check, and a diagnosis of each reference page (evaluation 3)

- **Baseline.** Which expected statements the rules give is decided from tiers 1 and 2 alone, before the answer is
  read. Evaluation 2 looked at the rules only when the model had no hit, so a model that repeated a rule statement
  had it counted as its own (stored runs: the own job advertisement's `employment_type full_time`, expected 1 / given
  2 in one run, 2 / 1 in the other). A repetition is now counted on its own (`repeatsRules`), not rated.
  `QualityBaselineTest` was red before (expected 2, given 1 with the repetition).
- **Diagnosis (version 1).** Each reference page keeps, bounded and without keys, the model's answer, what the
  validator accepted and every item it dropped with its reason, and what the publication would take; cut or
  omitted parts are marked. Only the check's own result holds it; the knowledge graph stores no answer.
- **Where a missing statement went.** The reason of exactly that item (never the answer's other reasons); the
  statement, the verbatim quote, the names in it, the types and the assignment checked apart; *stated otherwise*;
  *not stated* only when every answer was read and none was cut off, otherwise *cause not traceable*.
- **Earlier results** are kept unchanged in `archive/` and listed; a result of evaluation 2 shows its *not stated*
  as *cause not traceable*.
- **The reason of exactly this item.** The validator carries each item's position in the answer into its sample; a
  drop is attributed only by that position, never by kind, predicate and quote (two items can share them, and a
  sample keeps only the start of a quote). Unclear: `reasonAttributed` false.
- **No key in any field.** Every string of a result, field names included, is scrubbed before it is stored, and
  again when the API shows a stored or an earlier result; archived files are only read.
- **Counter-checks** of the stored runs' deviations with mock answers (`QualityDiagnosisTest`): the job portal's
  employer, the agency's position, software used internally as a facility, offers and prices. No validator or model
  rule changed.

## 0.10.7 / 1.942-scoutro.31 — patch: the end of the re-check, the advertisement of a position's page, LLM entity types, the log report's window, a quality check of models

Patch of the installed 0.10.6. See the
[release, upgrade and rollback notes](docs/SCOUTRO_RELEASE_0.10.7.md).
Knowledge graph schema stays **6**, the extractor identity of tiers 1 and 2
(JSON-LD 5, rules 6), the LLM extractor version (`llm/4`) and every setting
stay: no global re-extraction. The upgrade closes the re-check of the rules 4
that 0.10.6 left without its end, without extracting a page for it, and then
extracts again, once, only the pages with a job or an advertisement hint
(relation rules 5, scope `jobs`). The compiled-in default prompt has a new
hash; done documents are not read again. Integrates #67, #68 and #69.

### fix: the end of the relation re-check (C29) and the advertisement of a position's page (C32, relation rules 5)

- **The end of the check (C29).** Production ran 0.10.6 without a restart; the
  full check published all its pages, nothing was open or failed, and its end
  (`sync.relationRecheckInfo.done`) was never written. A job the LLM tier read
  on an old page while the check ran put that page into the check's selection
  (the LLM tier does not touch `processed_at`), so the last page did not end
  it. The selection now counts only evidence published before the check
  started: fixed at the start, it only shrinks. The running process also ends
  a check whose selection empties without a page of it (looked at every five
  minutes while that is so). A check whose pages were all published while its
  end is missing — the graph 0.10.6 leaves — ends at the first start without
  extracting any page again; start, scope and page count stay; the end and its
  event are written once. No rules version change, no new check, no LLM
  request, no crawl; refused writes, failed pages and crashes keep the check
  open as before.
- **Correction (C32).** "Zur Verstärkung unseres Teams suchen wir Dich" is a
  neutral phrase of the tests, never the wording of the reported pages; they
  say "Bei uns wirst Du." and "Bei uns gestaltest Du …", which the rules read
  ("bei uns"). Their employment type stays unknown, as is correct.
- **The advertisement of a position's page (C32, relation rules 5).** The
  measured page of a position has its title line at 18, a long navigation and
  the menu of the positions, the advertisement's headings at 6,308 and 6,426,
  "Bei uns wirst Du." at 7,075 and the application call at 11,328. The rules
  read the title line as the advertisement: its window ended at the menu, so
  the job had no employer. Two causes: only the first 30 occurrences of a
  title were read, and a breadcrumb right before the heading made the heading
  an entry of a list. Now every occurrence is read (at most 400). The
  advertisement is the occurrence followed by sentences of its own text, not
  the longest one, and its evidence starts at its heading. A repetition of
  the title without its specialisation is the same position. The
  advertisement ends at the next title of another position or of a list,
  without the navigation labels before it; the bound of 6,000 characters is
  unchanged. A menu or footer label ("Unser Team", "Karriere bei uns")
  declares no employer, and a company named as the hirer that is not the
  operator (a job portal) makes the operator neither employer nor recruiter.
  The lead of a list is read after its menu. The operator's identity is
  unchanged: the company's name inside the advertisement does not make it.
  The upgrade closes the check of the rules 4 that 0.10.6 left without its
  end, without extracting its pages again
  (`sync.relationRecheckInfoBefore`). It then extracts again, once, the pages
  with a job or an advertisement hint (rules 5, scope `jobs`). No global
  re-extraction, no LLM request, no crawl. Details:
  [docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 31.6 and 32](docs/SCOUTRO_KNOWLEDGE_GRAPH.md).

### fix: the type of an LLM entity, the log report's HTTP 400, the provenance of LLM evidence

- **A service needs an offer, a job an advertisement.** The LLM tier accepted every entity whose quote held its name. So:
  - a page's sidebar tools ("Rechtschreibprüfung", an image list) became services;
  - a team in a contact text ("Our service team …") became the service "Service team";
  - a team member's role became a job.

  The validator now refuses three kinds of entity, each counted under its own reason:
  - a service named as a team, a department or staff;
  - a service without an offer in its quote, its heading, an offer statement or a price;
  - a job without an advertisement.

  Kept: services listed under their heading in a navigation, an offered spell check, a job advertisement and a software
  product sold. The integration with the quality check found that only the infinitive "verkaufen" counted as an offer, so
  "Die … GmbH verkauft MusterText Pro" lost its product; "verkauft", "vertreibt", "vermietet", "sells", "selling" and
  "sold" are offers too.

  The default prompt says so too. A new prompt reads no document again. Evidence accepted before stays; a reviewable cleanup
  plan is documented. Details:
  [docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 33.1](docs/SCOUTRO_KNOWLEDGE_GRAPH.md).
- **The log report's call fits the model's window.** The log lines alone used to be sized to the window minus the output cap
  at four characters per token. With `num_ctx` 131,072 and `max_tokens` 65,536 that made about 263,600 characters, which a
  strict endpoint refuses together with the cap; this is the likely cause of the production HTTP 400s, still a hypothesis.
  - The whole call is now planned: template and instructions included, log lines at 2.5 characters per token, a margin of
    5 %, the configured cap (lowered only where a configured window cannot hold it).
  - Lines that do not fit are omitted visibly: warnings and errors first, then the newest lines, with the count in the prompt,
    the report and the log line.
  - A call that cannot fit is not sent.
  - No model-specific rule, no retry.
- **HTTP errors of a model endpoint name their reason.** The error body is read, at most 16 KiB. The message adds the
  provider's code, the rejected parameter, a router's upstream provider and up to 300 characters of the provider's message. It
  never includes the prompt, the request, a router's raw answer or a key.
  - The log report's lines name the function, service, model, prompt size, estimated tokens, output cap, the window Scoutro
    assumes and whether they exceed it.
  - The knowledge graph's `lastError` names the status and the provider code.
  - No parameter was removed and no fallback was added.
  - A provider's message on the next failure confirms or refutes the context overflow (33.2).
- **Provenance.** The time axes of evidence and observations are documented: which time says what, and that no time names a
  model (33.3).
  - A model switch between reading the selection and admitting a request now defers it.
  - The model and provider an endpoint says it served are counted in `llm.context.measured`.

### feat: quality check of a candidate model for the knowledge graph

- **Quality check of a model for the knowledge graph** (LLM selection, docs/SCOUTRO_LLM_QUALITY_CHECK.md).
  A candidate is checked explicitly against ten versioned, invented reference pages, read exactly as the
  LLM tier reads a page (its client, active prompt, chunks, budget, validator and publication preparation)
  but without publishing: correct, missing and wrong statements, wrong types and relations, unfitting
  evidence, API, schema and validator errors, cut answers, calls with retries, duration, tokens and an
  estimated cost, each deviation with its passage, expectation and actual statement; no overall score.
  Each page lists what a correct reading must state, may state besides (allowed) and must not state. A
  statement the validator accepted that the test set neither expects, allows nor forbids is listed as not
  rated, and the result is then `passed_with_unrated` ("No deviation, but accepted statements not rated"),
  never a plain pass. Scope and an estimate of the cost are shown before the start; every cost is an
  estimate (2 characters per token, `max_tokens`, the price as given), no guaranteed maximum. Calls
  (retries included), tokens and the estimated cost are capped; an unknown cost must be accepted
  explicitly; a run can be cancelled. Results keep their provenance (model, served
  model and provider, parameters, prompt, schema, validator, rules, test set) and become stale on a change,
  without a new run. Choosing or checking a model activates nothing: the knowledge model changes only after
  a confirmation. The check writes only its own result (`DATA/SCOUTRO/llm-quality/`).

## 0.10.6 / 1.942-scoutro.30 — patch: the employer an advertisement declares, the employment type it states, one job per position page

Patch of the installed 0.10.5. See the
[release, upgrade and rollback notes](docs/SCOUTRO_RELEASE_0.10.6.md).
Knowledge graph schema stays **6**, the extractor identity (JSON-LD 5, rules 6)
and every setting stay: no global re-extraction. The upgrade extracts again,
once, the pages with a job of any tier or a job-ad hint (rules version 4,
scope `jobs`) where the check of 0.10.5 is recorded as complete; otherwise the
full check of relations, jobs and hints, once. No LLM request, no crawl.

- **The employer an advertisement declares.** Declarations such as "wir
  suchen", "bei uns", "Werde Teil unseres Teams" or "wir stellen ein" (phrases
  of the tests, not the wording of the reported pages; correction above)
  in the advertisement's own text make the site's operator the
  job's employer, with that sentence as evidence. Menu, footer ("Jobs bei
  uns") and the domain declare nothing; another organisation of the page is
  not who "wir" is. "Für unseren Kunden/Mandanten …": the operator recruits
  (`recruiting_organization`), the employer stays unknown.
- **The employment type the advertisement states.** Not from requirements
  ("Erfahrung als Freelancer", "abgeschlossene Ausbildung"), compounds or
  neighbouring positions; a type stated anywhere in the advertisement ("Wir
  bieten: … in Vollzeit") counts. No default: an unstated type stays unknown.
- **One job per position page.** The JSON-LD posting of a position's page and
  its advertisement are one job when nothing contradicts it (another page,
  employer or place keeps it apart); a posting title that is the company's
  name is no job title. Contradicting statements stay side by side with their
  own sources (`full_time` from JSON-LD and `freelance` from the text); none is
  chosen. A short company name that links the site's own domain (`url` or
  `sameAs`) is the operator, one organisation with its full name.
- **JSON-LD references keep their kinds:** nodes that share an `@id` are no
  longer lost or mixed up.
- **The re-check ends in the running process.** Status
  `sync.relationRecheckInfo` (`rules`, `scope`, `pages`, `since`, `done`,
  `open`, `failed`, `failedPages`) is readable without the event log; `done` is
  set with the last page, without a restart; a refused write or a failed page
  keeps the check open. The LLM count of the check before stays readable
  (`sync.relationRecheckLlmJobsBefore`); the LLM tier's evidence is unchanged.

## 0.10.5 / 1.942-scoutro.29 — patch: derived/matching no longer refused at the WAL limit; a careers page has the jobs it advertises

Patch of the installed 0.10.4. See the
[release, upgrade and rollback notes](docs/SCOUTRO_RELEASE_0.10.5.md).
Knowledge graph schema stays **6**, the extractor identity, the WAL limit and
every setting stay. The upgrade publishes the pending matching-policy notices
in bounded writes and extracts again, once, only the pages with relations,
jobs or job-ad hints of the rule tier and those with LLM-tier jobs (event
`relation_rules_recheck`, rules version 3); no LLM request, no other page.

- **Derived/matching runs again.** A changed matching policy wrote one feed
  notice per stored contribution in a single write whose estimate exceeded the
  WAL limit (87,764 contributions ≈ 85.7 MiB > 64 MiB), so it was refused about
  twice a second and every derived pass ended with `write_refused`. The notices
  are now published 2,000 contributions per write, resumable.
- **A careers page has the positions it advertises as jobs, not its menu.**
  The culture or benefits page of a careers section no longer has the open
  positions of its menu as jobs (nor their employer or application route); a
  position is one job from its advertisement, with its full title including
  the specialisation ("… (m/w/d) Finance"); a changed menu keeps the job.
- **Employment types and memberships.** "Internationale" or "Internet" name no
  internship; "Freiberufliche …" in a title is freelance. A department
  ("Mitglied des Einkaufs") or a person in a role ("eines Beraters als Mitglied
  des …") is no membership of the page's organisation.
- **Status "Extracted from source"** (*Aus Quelle extrahiert*) instead of
  "confirmed" in the network and service lists, with the note that no human
  review is implied; confidence and number of sources stay separate.
- The LLM tier's job evidence of the re-checked pages is counted
  (`sync.relationRecheckLlmJobs`), not changed.

## 0.10.4 / 1.942-scoutro.28 — patch: a list of relations ends with its sentence

Patch of the installed 0.10.2 that contains 0.10.3 (published, not rolled out).
See the [release, upgrade and rollback notes](docs/SCOUTRO_RELEASE_0.10.4.md).
Knowledge graph schema stays **6**, the extractor identity and every setting
stay. The upgrade extracts again only the pages that carry a relation of the
rule tier, once (event `relation_rules_recheck`); no other page.

- **A list of relations ends with its sentence.** After "Unsere Partner: Alpha
  Systeme GmbH und Beta Logistik AG." the next sentence "Muster Software GmbH,
  Musterstraße 1" (the imprint) was read as a third partner, so a company
  could become a partner of itself; the same for customers, references,
  memberships and the other lists. A full stop now ends the list; a list on
  lines goes on, and the full stop of `Inc.`, `Ltd.` and other abbreviated
  legal forms ends no sentence.
- **The relation re-check of 0.10.3 knows the version of the rules**
  (`kg_meta.relation_recheck_rules`): an upgrade from 0.10.2 checks the pages
  with relations of the rule tier once with all fixes; an installation of
  0.10.3 checks them once more.

## 0.10.3 / 1.942-scoutro.27 — patch: the knowledge graph sync no longer stalls; no partners from consent banners; hide a collection or delete its pages

Patch of the installed 0.10.2. See the
[release, upgrade and rollback notes](docs/SCOUTRO_RELEASE_0.10.3.md). Knowledge
graph schema stays **6**, the extractor identity and every setting stay. The
upgrade extracts again only the pages that carry a relation of the rule tier
(event `relation_rules_recheck`, once, at low priority); no other page.

- **The knowledge graph sync no longer stalls (0.10.2 regression).** Two
  gender markers in one sentence of a careers page ("Buchhalter (m/w/d) oder
  Controller (m/w/d)", page text without line breaks) gave the second job
  title a start before the end of the first, so the text of the first
  advertisement had a negative length: `IndexOutOfBoundsException: start > end`
  and `StringIndexOutOfBoundsException: Range [x, y)`. A job title now never
  reaches back over the marker before it, and a joining word (und, oder, bzw.,
  sowie, a slash, comma, & or +) is no part of it.
- **One failing page no longer holds up the others.** A runtime error while
  extracting a page left it at the head of the sync queue without counting an
  attempt, so every step took it again and nothing behind it was published
  (`sync.processed.published` stood still while `extractions` rose). Such a
  failure now counts against the page alone: retried with backoff, after five
  tries dropped with event `doc_failed` (`<id>: extract_failed`, logged once with
  its trace) and read again with its next change, as other failures of a single
  page already were.
- **No partners from consent banners.** The banner text "Wir geben
  Informationen zu Ihrer Verwendung unserer Website an unsere Partner für
  soziale Medien, Werbung und Analysen weiter" made *Werbung* and *Analysen*
  partner organisations of the site, on every page of a site with that banner.
  A relation marker in a consent sentence (cookies, consent, tracking, social
  media, "Werbung und Analysen", "use of our website") gives no relation; a
  list marker followed by a preposition ("an unsere Partner für …", "im Raum
  …") opens no list; common words (Werbung, Analysen, Marketing, Medien, …)
  are no one-word brand in a list. Real partner lists stay. The pages already
  read with relations of the rule tier are extracted again once
  (`relation_rules_recheck`; `sync.relationRecheck` counts the pages left), so
  the wrong partners leave the graph without a global re-extraction.
- **Hide a collection or delete its pages (Dashboard "−").** The dialog lists
  every collection with its page count and says what happens before anything
  changes: an empty collection created in Scoutro is removed as before; a
  collection with pages is **hidden** (`POST /v1/collections/{id}/hide`): it
  leaves the dashboard and every choice, the knowledge graph stops reading it
  (switched off, its data stay) and its pages stay in the index. **Also delete
  its pages from the index**, confirmed by typing the collection id, deletes
  the pages that belong to no other collection (`POST …/{id}/delete-pages`,
  irreversible); a page that also belongs to another collection stays and the
  collection is then hidden. Hidden collections are listed below the cards
  with **Show again** (`POST …/{id}/show`). Not while a Discovery profile, an
  account, agent, guest access or running crawl uses the collection (the push
  API's profile `push_<collection>` crawls nothing and no longer counts as a
  running crawl, so a collection filled through the push API can be hidden or
  removed). Kept in
  `DATA/SCOUTRO/collections-hidden.json`; administrator only, never an agent or
  MCP tool; audited as `api.collections.hide`, `.show` and `.delete-pages`.

## 0.10.2 / 1.942-scoutro.26 — release candidate: service categories, prices one by one, software hints of job advertisements

0.10.1 was published but not rolled out; 0.10.2 contains all of it and is the
upgrade target of an installed 0.10.0. See the
[release, upgrade and rollback notes](docs/SCOUTRO_RELEASE_0.10.2.md).

Upgrade from 0.10.1 (from 0.10.0 the steps of 0.10.1 below come first):
knowledge graph schema stays **6**, the extractor identity stays the same and
the upgrade changes or adds no setting. Collections without job objects read the software hints of their
job advertisements from then on: the first start gives the event
`job_reading_changed` and one targeted reconcile that extracts only their job
pages again (`sync.reconcile.jobRecheck`); no other page, no PDF, no download.

- **Service categories that explain themselves.** The category lists of
  Services, Compare and the new Prices view say why they are empty: the read
  failed (with its code), the collection has no vocabulary profile (with how
  to assign one), or no visible page states a category yet. `kg/facets`
  answers `counts=false` quickly for the selects and names the profile of
  each visible collection (`profiles`); derived counts are read on their own
  (`derived_error`). The page keeps the values for at most a minute, never
  fills a select with the reply of a former collection, and drops a chosen
  category on a collection switch. Nothing depends on collection names.
- **Prices one by one.** "With a current price" is a link to the new Prices
  view and `GET kg/services/prices` (agent grant `kg.read`): every published
  price of a provider's own service, exactly as published, with provider,
  currency and unit, conditions, date and source; filters for status
  (current, conflicting, outdated, uncertain, all), name and category, also
  without a category; 25 per page. The count and the list use one status
  rule (the collection's price age, expiry, conflicts) and the same rights;
  `kg/services` adds `with_any_price`. No average, no conversion, no mixing of
  providers.
- **Software hints of job advertisements.** A fourth job policy, *Read
  software hints from job ads* (`scoutro.kg.jobs.signals.<collection>`, on
  unless switched off), keeps the software and system mentions of an
  advertisement for its declared employer, also without job objects and
  whatever the job display says. Internal use, required or desirable
  competence and client work stay apart; a requirement is never a proof of
  own use, of a purchase or of a need. An intermediary's advertisement, one
  without a declared employer and one with several employers give no hint.
  The company view shows them as **Software and systems** with context and
  source. Matching uses them only under *Use stored job signals for
  matching*, a requirement alone stays weak. The labels and help of the
  three former switches now say what they do (*Extract jobs as objects*,
  *Show job objects*); explicit settings are kept. A changed policy re-reads
  only the job pages of the collection (`recheckJobs`).
- **Tests:** `ServicePricesTest`, `JobAdHintsTest`, `CompanySystemsTest`,
  `JobReadingHashTest`, additions to `SyncServiceTest`, `MatchingTest`,
  `KgCollectionSettingsTest` and `AgentCatalogTest`; browser tests
  `knowledge-services-prices-ui-test.mjs` and
  `knowledge-jobs-systems-ui-test.mjs` (EN/DE, phone and desktop).
  Details: docs/SCOUTRO_KNOWLEDGE_GRAPH.md, section 29.

## 0.10.1 / 1.942-scoutro.25 — release candidate: Discovery recovery, collections, process display, LLM coverage

Upgrade from 0.10.0: knowledge graph schema stays **6** and no page is
extracted again. The first start carries the former collection defaults once
into `DATA/SCOUTRO/knowledge/vocabulary/000-migrated-collections.json` and
records `scoutro.kg.migration.collectionProfiles`, the only key it adds to
`yacy.conf`; the extractor identity stays the same. The two new LLM settings
are optional and keep their defaults; long pages read before are audited and
read on at low priority. Access mode, cookie and sign-in limits are unchanged.
A Discovery batch blocked by an unconfirmed start stays blocked until an
administrator defers it. Rollback to 0.10.0: let an open batch with a deferred
start complete first, pause the job whose website was deferred and keep the
migrated vocabulary file. See the
[release, upgrade and rollback notes](docs/SCOUTRO_RELEASE_0.10.1.md).

- HTML parser: a single stray quote or `<` no longer drops all text after
  it from the indexed text (`text_t`). A quote opens an attribute value only
  after `=` (`alt=Peter's` and `'Peter\'s'` end their tag); a `<` not
  followed by a letter, `/`, `!` or `?` is text (`a < b`, `3<5`); the
  content of `<script>` and `<style>` is read as raw text up to its end tag,
  so `<`, quotes or a `// don't` comment in code no longer swallow the page
  text after it, and a JSON-LD block with a `<` in a string is captured
  intact. Script code is no longer part of the text of an enclosing link or
  heading. `&lt;` and `&gt;` in text are decoded after the tags are
  stripped, so they no longer cut the rest of the text. A page with a few
  stray control characters (at most 8, at most one per 100 characters) is
  parsed with them read as spaces instead of being rejected as binary;
  binary data is still rejected. What is crawled and fetched is unchanged.
  Pages already indexed benefit after their next recrawl; no recrawl is
  triggered.

- Knowledge graph, collections: Scoutro no longer names collections. The
  shipped `categories.json` maps none, and no collection gets a vocabulary or
  facility kinds of the LLM tier by its name: a vocabulary (care, coaching,
  software, construction or an own one) is a profile the operator gives a
  collection (Settings, `scoutro.kg.vocab.<collection>` or a vocabulary file),
  and it brings its facility kinds (`llm_kinds`, replaced by
  `scoutro.kg.llm.kinds.<collection>`). An existing installation keeps the
  collection defaults earlier versions shipped: the first start writes them
  once into `DATA/SCOUTRO/knowledge/vocabulary/000-migrated-collections.json`
  and records `scoutro.kg.migration.collectionProfiles`; the extractor
  identity and the LLM cache keys stay the same and no page is read again
  (docs/SCOUTRO_KNOWLEDGE_GRAPH.md 28). No new setting is needed.

- Knowledge graph, LLM tier (follow-up of the 0.10.0 coverage analysis,
  docs/SCOUTRO_KNOWLEDGE_GRAPH.md 27): a queue item is never dropped while
  its page is still to do (a page left unconfirmed is retried with a backoff
  and then shown as `input_unconfirmed`); every LLM collection has its own
  resumable scan with an equal share of each refill and a revisit behind it,
  so a late collection is not left behind and pages that became to do again
  are found without a restart; a queue item waiting two hours is claimed
  before newer higher-priority ones. Every request is planned against the
  model's context window with an answer reserve
  (`scoutro.kg.llm.answerReserveTokens`, 1,200): a large list of known
  entities is cut to those the chunk names and as many others as fit, a
  chunk is split or the page is shown as `context_budget` instead of
  sending a request that does not fit; `num_ctx` is sent on Ollama's native
  path where `ai.service_num_ctx` is configured; otherwise the window
  Ollama reports for the loaded model (`/api/ps`, read-only, not sent) is
  used before the assumed 4,096. A long page reads its own content before
  navigation that recurs on its host (moved, never removed; never asked once
  the content is read), and is read to the end of its content in passes of
  at most `scoutro.kg.llm.continuationChunks` (5) requests at low priority,
  in the layout of its first reading, with a small progress row per page
  (positions, no text); `extract.maxStatementsPerDoc` holds over all its
  readings, a failed pass is tried again after one and two hours, and the
  rest shows as `text_cut` only after three failed passes, at the statement
  limit, for a legal page or with continuations off. Pages read before this
  version are audited once, 20 per scan pass, and a long one is read on from
  where it stopped, never from its start. A page whose text is missing
  (under 200 characters of its own while its HTML has 20,000 bytes or more,
  or a text asking for JavaScript) is marked `text_missing` and counts as
  not covered. The known entities are offered once per type and name, the
  declared operator first; a relation of an entity to itself is refused and
  counted, a claim about a dropped entity counts as `claim_entity_dropped`,
  and an organisation with a legal form joins the declared operator; the
  prompt and the validator's rules are unchanged. Up to 500 refusal samples
  without person names or contact data are kept in memory only (20 per
  reason, 128 KiB, 7 days; `GET /scoutro/api/v1/kg/llm-refusals`,
  administrator). A page published before schema 4 is confirmed with the
  input hash it was published with. New status blocks `llm.selection`,
  `llm.context`, `llm.continuation`, `llm.documents.coverage`,
  `llm.refusalSamples`, `llm.storage`, `llm.backlog` and counters (queue
  wait, intake, retries). No schema change; the two new settings are
  optional and not written to `yacy.conf`; no page is read again from its
  start.

- Discovery: an administrator can defer an unconfirmed crawl start that
  blocks all Discovery batches (`needs_review`, `submitted_unknown`, no crawl
  profile with its marker) on the Discovery page (**Defer start (outcome
  stays unknown)**) or with `POST /scoutro/api/v1/discovery/attempts/{id}/defer`.
  The crawl profiles are checked again first; the outcome stays unknown and
  is never recorded as not submitted; the website is held and never started
  again by itself (also not by retry, recrawl or the command line
  `--force`); accepted crawls of the batch are kept and other jobs continue.
  The decision (who, when, evidence) stays with the attempt, in the job's
  `held_attempts` and in the candidate state; a crawl with the marker that
  turns up while the batch is still open is recorded as accepted after all.
  The status lists the unconfirmed starts (`active_batch.review`) and counts
  held candidates (`held`). See the
  [recovery contract](docs/SCOUTRO_DISCOVERY_AUTOMATION_V1.md#161-deferring-an-unconfirmed-start-administrator-decision).
  Rollback note: a 0.10.0 image refuses the job store while an open batch
  contains a deferred start (complete the batch first) and does not honour
  the hold of a deferred website (pause the job after a rollback).
- Dashboard: the administrator adds and removes collections with **+** and
  **−** next to *Scoutro Collections*. **+** opens the existing *New
  collection* dialog. **−** removes a collection created in Scoutro from the
  collection list (`DELETE /scoutro/api/v1/collections/{id}`), but only while
  it holds no page (counted anew in the index), no Discovery profile names it
  and no account, agent, guest access or running crawl uses it; otherwise the
  dialog says why it stays. No page, crawl, knowledge-graph setting or other
  data is deleted. Accounts never see the buttons; the API answers them `403`.
  The pages of the collection are counted exactly before the removal (the
  catalog facet lists at most 500 collections); an unreadable account file
  refuses it (`503 collection_uses_unavailable`). `reassign` is now a reserved
  collection ID. The shared *New collection* dialog contains no form any more,
  so the dashboard holds none (the site search of the header aside).
- Knowledge graph status: a process display replaces the long technical status
  list at the top of the overview: web pages and structured data → rule
  processing and AI enrichment in parallel → knowledge store → customer and
  partner suggestions. Each step says what works or waits, how much is open
  (page changes, document tasks, model calls, knowledge objects and matching
  signals kept apart, each with unit and time basis), why it waits and whether
  to act; error, pause, planned waiting, waiting for the viewer and running
  work are distinguished, and a storage-guard reason means the same on every
  step. The AI step counts the pages not yet examined (the backlog) apart from
  its small queue; the average model request time comes only from requests
  that have ended. Pages are shown per collection (a page can belong to
  several), values of a refused or unknown run as *not available*, never 0.
  Details open on tap or Enter/Space; the former lists stay under *Technical
  diagnosis*. It only reads the status and shows no estimated percentages or
  remaining times. The status adds `llm.documents.pending`,
  `llm.processed.requestsFinished` and `llm.processed.averageRequestMillis`.

## 0.10.0 / 1.942-scoutro.24 — release candidate: users, roles and navigation

Upgrade from 0.9.1 keeps the **compatible access mode**: no account exists and
`scoutro.access.protected` stays off, so YaCy's previous rules apply. Browsers
see the Scoutro login page instead of the Digest dialog (the built-in
administrator signs in with the existing password); Digest and Bearer access
for tools and agents are unchanged. Knowledge graph schema stays **6**; no
extractor identity, re-extraction, rebuild or reset. Agent crawls stay on their
start host (see below). See the [release, upgrade and rollback notes](docs/SCOUTRO_RELEASE_0.10.0.md).

- Scoutro accounts for people with the roles Research, Operator and
  Administrator, collection access per account and a separate export right;
  passwords as Argon2id hashes in `DATA/SETTINGS/scoutro-users.json`.
- Own login page (`scoutro-login.html`) with safe return, sign-out, session
  expiry (30 minutes idle, 12 hours at most), server-side revocation,
  password change, administrator reset without e-mail, sign-in limits, CSRF
  protection and an audit log (`scoutro-user-audit.jsonl`). The built-in YaCy
  administrator signs in there with its existing password; HTTP Digest for
  tools and scripts is unchanged.
- Sign-in limits cannot be steered by client headers: connections from a
  trusted reverse proxy (`server.reverseProxy.trusted`, loopback by default,
  i.e. the Olares entrance chain) count in one shared bucket
  `proxy:<address>`; `X-Real-IP` counts only with
  `scoutro.auth.limits.proxyAddress=x-real-ip`, `X-Forwarded-For` never. The
  5-per-name limit and the 4 parallel password checks are unchanged. Behind an
  HTTPS-only entrance that forwards plain HTTP (Olares) set
  `scoutro.auth.cookie.secure=always` before the first browser sign-in.
- Protected access mode (`scoutro.access.protected`, off after an upgrade): an
  allowlist that closes native search, Solr, MCP, suggestions, chat and the old
  YaCy pages to everyone but administrators, the precondition for Research and
  Operator accounts. Optional guest access, off by default. YaCy's
  peer-to-peer routes that read or write the index (`/yacy/search.html`,
  `urls.xml`, `idx.json`, `query.html`, index transfer) need the
  administrator in this mode and the index distribution (DHT) pauses, unless
  peer-to-peer in protected mode (`scoutro.access.p2p`, off by default) is
  switched on.
- One header and navigation for all administration pages: search, account
  menu (My account, sign-out) and the groups Overview, Research, Knowledge,
  Data collection and Settings, shown by the rights of the signed-in identity;
  YaCy's own pages unchanged under "Advanced tools" for administrators, the
  same groups on phones. Re-Start and Shutdown moved from the header to
  Settings → System (with confirmation and an audit entry). The header link
  to the dashboard is now labelled "Overview": a user translation
  (`DATA/LOCALE/<code>.lng`) of the former label "Dashboard" no longer affects
  it; translate `>Overview<` in the `env/templates/header.template` section.
- New pages: My account (profile, password, own sessions), Settings → Users
  (access mode, guest access, accounts, audit log), Settings → Collections
  (catalog, "New collection") and Settings → System; a branded 403 page.
- Pages and data follow the rights (protected access mode): Research and
  Operator accounts use the Scoutro search (`scoutro-search.html`), chat,
  overview, websites, host analysis and the knowledge views within their
  collections; Operators also crawls, Discovery jobs and crawl reports of
  their collections. Every Scoutro API route has a permission and a
  collection scope; foreign collections, `collection:` in queries,
  peer-to-peer search, a wide crawl or a host of other collections are
  refused. Exports need the export right; knowledge-graph operations,
  Discovery's global switches, collections, users and system stay with the
  administrator. The overview is built from the collection catalog instead of
  a fixed list. A collection context in the navigation presets search and
  function pages. The model's index `search` tool is not offered to chats
  without all collections, crawls of such accounts, Discovery crawls and
  agent crawls stay on exactly their start host (YaCy's domain filter alone
  also follows look-alike hosts such as `example.com.au`, outside an agent's
  domain allowlist), and `/heuristic` in a search needs the administrator.
- Design and permission matrix: [SCOUTRO_USERS_ACCESS.md](docs/SCOUTRO_USERS_ACCESS.md);
  baseline with evidence: [SCOUTRO_ACCESS_BASELINE.md](docs/SCOUTRO_ACCESS_BASELINE.md).
- Knowledge graph telemetry: the vocabulary row is labelled without raw
  placeholders, `claim_employer_unresolved` and `value_signal_context_missing`
  are registered LLM invalid-item reasons (shown with 0 before they occur), and
  every reason has a readable, translated label next to its API code.
- Context classification of customer suggestions (shared parser
  `BusinessSignals` 3, no extractor-version change): markers are whole words
  ("SAP In-House Cash" is no own use), an organization's competence statement
  outside a posting is `stated_competence` (no need), and reference, case-study
  and client pages describe client work, never needs. Matching re-reads the
  stored quote as a veto, counts competence only from postings and treats a
  posting for a product the employer offers itself as delivery capacity.
  Existing wrong contributions (such as the two reference-page suggestions of
  STELLWERK) disappear on read right after the update; the regular matching
  cycles delete their cache rows with a delete notice. `MatchingRules.VERSION`
  stays 1, observations and history are unchanged; no rebuild or
  re-extraction. Details: [SCOUTRO_MATCHING.md](docs/SCOUTRO_MATCHING.md),
  "Context correction".

## 0.9.1 / 1.942-scoutro.23 — patch release candidate

- Fix a recursive clause regex that can overflow the stack on long text or
  JSON-LD descriptions and terminate the periodic KG synchronization task.
  The linear scanner preserves existing quotation, date and locator contracts.
- Show the sync task's scheduler state, last execution, error and reconcile
  progress in the administrator API and localized overview. Unexpected task
  failures are logged and remain terminal; fatal JVM errors are not retried.
- Preserve unpersisted dirty events after transaction failures and bind task
  diagnostics/cancellation to their runtime session, including late completion
  after close, restore or a rebuild swap. Existing Solr/storage retries remain.

The code failure is reproduced against the 0.9.0 image with the reported
scan/extraction/publication counters; the actual production trigger remains
unconfirmed. No schema, extractor identity, load threshold or matching change.
See [patch release notes](docs/SCOUTRO_RELEASE_0.9.1.md) and
[sync reproduction and validation](docs/SCOUTRO_KG_SYNC_VALIDATION.md).

## 0.9.0 / 1.942-scoutro.22 — release candidate

- Customer and partner suggestions include organizations in other **authorized**
  collections, with origin labels and working object, graph and evidence links.
  Ordinary facts remain within the selected collection; access checks cover the
  complete evidence chain.
- Durable, dated system/competence and business-need observations survive live
  evidence/source cleanup. Job search, source availability and system assertions
  have separate statuses. No expiry date means unknown status; source loss does
  not prove job completion or software shutdown.
- Six explicit evidence-based rule groups supplement industry matching: IT
  support, CAD/BIM, own construction/site expansion, energy renovation,
  leadership/team development and regional care-transition cooperation. Customer
  and partner roles and independent reasons remain separate. Own SAP/IT-consultant
  recruiting does not support the corresponding job-based customer reasons.
- Optional global LLM enrichment schedule: automatic remains the default;
  scheduled and manual modes, zone/weekdays/time window, shared call-start
  spacing, bounded manual runs and stop/resume. All existing guards remain active.
- Jetty/Solr relocation fixes preserve module/manifest/service metadata and ship
  original upstream attribution/provenance unchanged. Dependency-Guard retained.
- Release packaging includes Scoutro version metadata and these release notes;
  optional build-only proxy CA support does not add trust to the runtime image.

**Upgrade:** KG schema 4 → 5 → 6, full verified DATA/configuration backup and
storage reserve required; protection/backfill precedes new extraction. No
lossless downgrade. Older versions ignore scheduling and can repeat unfinished
chunks. See [release and operator notes](docs/SCOUTRO_RELEASE_0.9.0.md).

The existing 0.8.7 / 1.942-scoutro.21 image remains immutable. Its historical
[rollout notes](docs/SCOUTRO_ROLLOUT_0.8.7.md) are not a 0.9.0 upgrade contract.
