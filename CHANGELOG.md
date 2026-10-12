# Scoutro changelog

Scoutro is an independent community project based on YaCy (GPL-2.0-or-later).

## Unreleased

- HTML parser: a single stray quote or `<` no longer drops all text after
  it from the indexed text (`text_t`). A quote opens an attribute value only
  after `=` (`alt=Peter's` and `'Peter\'s'` end their tag); a `<` not
  followed by a letter, `/`, `!` or `?` is text (`a < b`, `3<5`); the
  content of `<script>` and `<style>` is read as raw text up to its end tag,
  so `<`, quotes or a `// don't` comment in code no longer swallow the page
  text after it, and a JSON-LD block with a `<` in a string is captured
  intact. Script code is no longer part of the text of an enclosing link or
  heading. `&lt;` and `&gt;` in text are decoded after the tags are
  stripped, so they no longer cut the rest of the text. What is crawled and
  fetched is unchanged.
  Pages already indexed benefit after their next recrawl; no recrawl is
  triggered.

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
  navigation that recurs on its host (moved, never removed), and a long
  content page is continued by at most `scoutro.kg.llm.continuationChunks`
  (5) further requests at low priority, in the layout of its first reading;
  text still unread shows as `text_cut`. A page published before schema 4
  is confirmed with the input hash it was published with. New
  status blocks `llm.selection`, `llm.context`, `llm.continuation` and
  counters. No schema change; pages read before are not read again.

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
