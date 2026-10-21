# Evidence-based service matching (package C)

Implementation base checked on 2026-10-09: B #40, draft/open,
`8f6f3f11f5a510fd55da90664ebf2c7491f4c326`, including A #39,
`08a42db1d6609f6f53e10a51890f8e1bc28b7847`. Main was still
`7847fe1ed54eda64348eaa64cd15d7eb8c107639`. This branch depends on B;
no other development branches were imported. No production processing was run.

## Explicit rules, version 1

The provider must have a source-grounded **own service**: a service with its
source-local `offers` assignment, or an organizational `offered_capability`
observation naming the product and support/consulting/training/introduction.
A company name, collection, product alone or target industry does not suffice.
The candidate needs a recorded, assigned, contextual observation. Customer and
partner remain different relationship types. Existing industry and shared-audience
matching continue independently; their row IDs remain unchanged.

| Rule | Provider evidence | Candidate evidence and result |
| --- | --- | --- |
| `it-support` | Product-specific support, consulting, training or implementation | Exact product; internal use, a posting's required/desirable competence or planned migration → possible customer. General SAP does not prove S/4HANA, BW or SAC. A company's own competence statement, client work and reference pages are no need (see "Context correction"). |
| `cad-bim` | Explicit Revit/Archicad support, training or introduction | Exact contextual internal use/competence/plan → possible customer, including an architecture firm. Architecture alone is not a software-support offer. |
| `construction` | Architecture/design/approval/general planning, or explicit construction execution | Own new building, rebuilding or site expansion with explicit responsible actor, location and phase. Planning excludes already-under-construction projects; execution at planning stage carries phase/procurement uncertainty. |
| `energy-renovation` | Energy consulting, energetic planning or appropriate building/specialist planning | Own energetic renovation with responsibility, location and planned/approved/construction phase → possible customer. General architecture alone is insufficient. |
| `leadership-development`, `team-development` | Specific leadership/team coaching or organizational development | Explicit own development need/program → possible customer. General coaching, leadership vacancies, team skills and coach recruitment alone are insufficient. |
| `care-transition` | Outpatient or short-term care plus grounded service area | Hospital's own coordinated transition into the corresponding care form, with exact evidenced service-area/location match (B's structured place/region/state values, or legacy explicit literal regions) → possible cooperation partner. Bare discharge management, a foreign process, wrong region or individual patient data do not qualify. |

The regional rule is intentionally conservative: exact normalized region names,
not geocoding, inferred distances or company addresses as service areas. Building
locations are displayed, but do not certify the supplier's travel/service radius.
Care capacity and external purchasing/cooperation always remain unconfirmed.

## Employer, role and time

Job-based system rules require a source-declared employer. Company passages on a posting page cannot bypass this: a unique employer assignment from the same source revision becomes an additional required observation. Mixed or unresolved employers defer such passages. Coaching needs on recruiting pages require independent non-recruiting evidence. An unresolved employer
is deferred. The recorded employing legal entity is classified, not its portal,
collection, company name or a different group company. Proven own IT/SAP consulting
or system integration excludes its job contributions, and so does the employer's own
offer of the same product support (an `offered_capability` or an assigned service that
would make it a provider of that rule and product). Its archived knowledge and
independent organizational/project/legacy reasons remain. Care consulting, coaching
and architecture are not excluded by the word consulting.

Where the business role is unclear, the suggestion carries visible uncertainty
and a lower sorting score; an explicit internal-use job contribution has qualified
matching strength, while competence remains weak. Own consultant/trainer recruitment
for the same support service is excluded; client-project work does not become
internal use. Bare products do not qualify.

An old, undated, ended, deadline-passed or removed posting is **not** a shutdown.
Historical system and competence observations remain usable with their original
source date; missing dates stay unknown. The UI says, for example, "Last evidenced
on …; present use has not been reconfirmed." Read validation retires internal-use
or planned-system contributions only after a later, recorded shutdown/completed
migration with the same known system scope or explicit organization-wide scope.
Competence is not retrospectively converted into installation or erased by a
shutdown. Only the affected product and migration role change. A planned future target is not refuted by shutdown of an existing installation; completed target migration can close its plan contribution. Execution dates do not falsely postpone the date a plan was already evidenced. A completed/cancelled
project retires only a later-dated observation of the same explicitly named
project/need/actor. Unspecified project changes do not retire all projects.

Withdrawn/corrected assertions and changed actors invalidate affected cached
contributions immediately on read. An old job quotation reassigned to another
employer cannot itself ground that employer's system: a new source-grounded
observation is required. B retains the historical assignment and audit history;
C does not invent missing correction evidence or rewrite original identity text.

## Context correction (Olares 0.9.1 finding, STELLWERK)

**Finding, reproduced with the shipped code** (`829ab35`, Scoutro 0.9.1 / 1.942-scoutro.23).
STELLWERK Consulting AG (stellwerk.net, an SAP consultancy) received incoming SAP
suggestions from ecovium.com and mgm-tp.com (`it-support`, stackfinder-web). Their
signals came from its own reference page `/referenzen/projekt-beispiele/`:

| Stored quote | Stored context | Cause in `BusinessSignals` 2 |
| --- | --- | --- |
| `SAP In-House Cash` | `internal_use` | The SAP treasury module name matched the own marker `in.house` **and** the use marker `uses?` inside "Ho**use**"; no actor and no use are stated. |
| `Kernkompetenz SAP Rechnungswesen` | `required_competence` | "Kompetenz" made every competence statement a requirement, also a company's own core competence (its offer); `MatchingRules` accepted competence of any organization as a need. |

The extractor also ignored the page kind: a reference page reports client work. The
same marker flaw read "we cooperate with …" (`operate`) as use. The source page itself
could not be fetched from the development environment; the passages above are the stored
quotes from the read-only production analysis, re-run through the shipped parser.

**Correction (version 3 of the shared parser; no schema, prompt, model or extractor-version change):**

- Markers are words: `\buses?\b`, `\boperate\b`, `\brunning\b`; `in-house` no longer
  matches the module names In-House Cash / In-House Bank(ing). Explicit own use of them
  ("Wir nutzen SAP In-House Cash intern.") stays `internal_use`.
- A competence statement of an organization or facility outside a posting is
  `stated_competence` (own or offered; no need). Postings keep `required_competence` /
  `desirable_competence`. Matching accepts competence only from job subjects.
- Reference, case-study and client pages (`BusinessSignals.referencePage`: paths such as
  `referenzen`, `referenz`, `references`, `case-studies`, `kundenprojekte`,
  `projekt-beispiele`, `kunden`, `customers`, `clients`; deliberately not `projekte`,
  which can be own projects) are read under section `references`: used, migrated or
  shut-down systems become `customer_projects`, needs are not read; offers and stated
  competence stay. Matching ignores signals and needs of such pages for every rule.
- An unnamed "we" next to another named company (a legal-form name that is neither the
  actor nor a product vendor such as SAP SE) leaves the actor unclear: no own use is read.
  Postings are exempt (their employer is resolved separately).
- Postings of an employer that offers the same product support itself are delivery
  capacity, not a customer need. There is no blanket exclusion of consultancies or
  product vendors: a separately evidenced own use or need of the same company still
  matches (tested).
- A posting whose title names a consulting or training role for the product is
  delivery capacity too (0.11.8: words may stand between product and role, so "SAP
  Senior Berater", "Freiberuflicher SAP Consultant" and "Senior SAP FI/CO-Berater" count
  like "SAP-Berater"). An in-house or key-user title ("SAP Inhouse Consultant", "SAP
  Key-User") is the employer's own need and stays a customer reason. The rule version
  stays 1; stored contributions are checked with the title pattern when they are read.
  A topic of the website (a category the site only names) is no offer and excludes
  nobody.
- Missing dates stay unknown: no expiry and no present use is inferred
  (`historical_not_reconfirmed`, `observation_date_unknown`), as before.

**Withdrawal of existing contributions, targeted.** Observations are append-only: a
later re-extraction adds corrected rows but never changes the stored wrong ones. The
correction therefore works on read: `MatchingRules.assess` re-reads the stored quote
with the current parser (`contextConfirmed`, a veto only, never an upgrade), rejects
reference-page signals, competence outside postings and own-offer postings. Because
`MatchingProjection` re-runs `assess` for every cached contribution, wrong reasons
disappear from lists, graph, details, exports and feeds (as authorized deletions) right
after the deployment, without a calculation. Each contribution is one (service,
signal) pair, so other reasons of the same provider/candidate pair stay visible. The
next **complete and unchanged** provider partition of the regular matching pass does
not re-upsert them and deletes their cache rows with a `match_contribution` delete
notice; unaffected rows keep their IDs and are not re-announced (tested).
`MatchingRules.VERSION` stays `1` on purpose: a new rule version would hide **all**
cached contributions until every provider partition is recomputed.

**Re-evaluation needed and its scope.**

- No global re-extraction, rebuild, reset or manual recompute is needed or triggered.
  `BusinessSignals.VERSION` is no part of an extractor identity; `rule`, `jsonld`, `llm`
  versions are unchanged, so no `extractor_changed` work is queued.
- Immediately on deployment: read validation of all cached contributions (part of every read).
- Within the regular matching cycles (`matches.max` signals per pass, resumable): cache
  cleanup of the withdrawn rows, provider by provider. Until a provider's partition
  completes, its withdrawn rows stay in `kg_match_contribution` but are invisible; a raw
  SQL count of that table can therefore still include them.
- New and changed pages are read with the corrected parser by the ordinary sync; the
  stored historical observations (including STELLWERK's two quotes and their original
  contexts) stay unchanged and visible in the history.
- Live `system_signal` statements of the graph (the object page's context labels) of
  pages not re-extracted keep their old context until the page changes; they do not
  feed matching.
- Read-only size estimate before/after the rollout (upper bounds; the exact veto is the
  Java re-read):

  ```sql
  SELECT count(*) FROM kg_observation WHERE predicate='system_signal' AND assertion_status='recorded'
    AND json_valid(value) AND json_extract(value,'$.context') IN('required_competence','desirable_competence') AND subject_type<>'job';
  SELECT count(*) FROM kg_observation WHERE predicate IN('system_signal','business_need_signal') AND assertion_status='recorded'
    AND (source_url LIKE '%/referenz%' OR source_url LIKE '%/reference%' OR source_url LIKE '%/kunden/%' OR source_url LIKE '%projekt-beispiele%'
      OR source_url LIKE '%case-stud%' OR source_url LIKE '%/customers%' OR source_url LIKE '%/clients%');
  SELECT count(*) FROM kg_observation WHERE predicate='system_signal' AND json_extract(value,'$.context')='internal_use'
    AND (quote LIKE '%In-House Cash%' OR quote LIKE '%In-House Bank%' OR quote LIKE '%cooperat%');
  SELECT count(*) FROM kg_match_contribution;  -- before, and after one complete matching cycle
  ```

**Limits.** The page kind comes from the URL path only; a reference page under another
path stays undetected, an own-use sentence on a reference page is read as client work.
Legal-form detection is pattern based. Product names other than In-House Cash/Bank that
contain marker words are not enumerated. The LLM tier uses the same parser, so
`value_signal_context_missing` can count more such quotes than before.

## Schema 6 and contributions

- `kg_match_contribution`: stable proposal and contribution IDs, current public
  actor IDs, rule/version, service/signal keys, corroboration key, computation
  date, bounded valid JSON references and reconciliation generation.
- `kg_match_ref`: the full observation dependencies (service, signal and optional
  regional/employer evidence). Original quotes, source revisions, assignment quotes and
  dates stay in B's observation store; there are no FKs to live employers/jobs.
- `kg_match_access`: complete historical dependency-scope chains, retained for
  authorized removal notices after cache deletion. An AND over dependencies,
  with alternative currently permitted scopes per observation, controls reads.
  Historical chains authorize deletion notices only, never access to old content.
- `kg_derived_reason`: all independent legacy reasons for the existing derived
  row, retaining its ID. An incomplete legacy pass cannot discard saved reasons.
- Change kind `match_contribution` adds cache upserts/removals; existing kind/IDs
  and feed sequence high water survive the additive migration.

Archive capture is extended only for necessary organizational/facility service-area
facts before any extractor change. Remaining relevant evidence is copied
idempotently; no lost evidence is invented. Controlled need extraction adds
explicit responsibility, project, location, phase and hospital-transition details.
Old underspecified needs stay stored but cannot satisfy these rules. Shared
`BusinessSignals` becomes version 2; JSON-LD/rule/LLM versions become 5/6/4,
respectively. Existing LLM schema, deterministic grounding and custom prompts are
preserved; the shared parser, version and content fingerprint govern application
and caches. There is no LLM matching or live LLM probe.

All independent reasons are gathered per provider/candidate/relationship. Repeated
quotes across revisions, tiers, collection memberships or portals are one
corroboration key, not added confidence. A visible counterpart is one group,
with multiple reasons; customer direction and symmetric partners stay distinct.
If one reason disappears, other eligible reasons remain. Ranking is the maximum
eligible **authorized** sorting value, never a measured probability. Evidence
strength, fit, temporal restriction and uncertainty are separate fields.

## Calculation and access boundaries

Product/need expression indexes join only corresponding offers and observations;
there is no full Cartesian join of all organizations. `matches.max` is also the
new-rule work budget per pass (0 disables these rules without deleting archive knowledge). `maxPerEntity` remains a legacy-result cap, not a silent new-rule preview truncation. Provider partitions resume with service-key and
observation-row cursors; the input audit high water must remain unchanged before
stale contributions can be reconciled. Aborted/capped partitions do not refute
old reasons. At most 500 provider partitions are visited in a pass; a provider with more than
2500 relevant offering/region observations is explicitly deferred while other
providers continue. Status/UI expose `derived.matching` with checked work,
completed partitions, cycle completion and `deferred` reason.

Before grouping/ranking/counting/paging, reads check every current dependency
scope and re-evaluate policy, actor assignment, rule version, assertions, role,
system/project change and regional match. Since 0.11.8 a read validates every
contribution with the facts ordering needs (score, keys, other side) and builds the
evidence only for the shown page: the observations an entity's contributions refer to,
the posting pages of their sources and the shutdowns of their actors' systems are read in
a few queries per read, a memo answers repeated checks, and statements are reused within
the read. The answer is the same as before (groups, order, `total`, every field; tested
against the former path). Local load test with the production read budget (5,000 ms):
40,000 contributions with few shared signals 1.4–2.4 s; with a different signal and actor
each 1.6–3.6 s at 20,000, 2.6–3.9 s at 30,000 and 3.4–5.1 s at 40,000, where 2 of 12 reads
still ended with `read_timeout` (docs/SCOUTRO_RELEASE_0.11.8.md). Additional evidence collections must
be permitted. Inaccessible stronger reasons cannot displace visible weaker ones.
Neither hidden memberships nor hidden reason counts are emitted. Ordinary facts,
sources, weak links and structural relationships keep A's original view boundary.

List/graph show "Other collection: NAME", target context and original archive
quotes. An archive-only counterpart opens B's history fallback if its live object
was cleaned up. Preview contains at most 25 reasons; full paginated details:

`GET /scoutro/api/v1/kg/entities/{id}/suggestions/{proposal}/contributions`

Parameters `collection` (origin filter), `offset` (default 0), `limit` (1–100,
default 25). Returns `items`, authorized deduplicated `total`, and `next_offset`.
Agent equivalent `/scoutro/api/agent/v1/kg/…` needs `kg.read`; scope comes only
from server-checked grants. Missing/inaccessible groups are 404. Clients must
follow `contributions_path`, not assume preview completeness or truncate JSON.

Regular export adds phase `m` after entities/statements/legacy derivations and
emits full eligible `match_contribution` records with archival evidence. Hidden
raw records can yield an empty page with `next`: continue until `complete`.
Change feeds require `kg.export`; contribution eligibility is checked even with
`expand=false`, converting invalid prior reasons to authorized deletions.
Changing stored-job matching policy, disabling the new calculation (`matches.max=0`)
or disabling derivations invalidates cached contribution feed records at KG reopen.
An unchanged policy does not create artificial changes. Re-enabling emits current
upserts without erasing historical observations or requiring new extraction;
read validation remains authoritative even while calculation is paused or deferred.
When the storage guard refuses the notice transaction, the runtime remains readable
and exposes `deferred=policy_notice_deferred`; maintenance retries after recovery.
`include=history` remains B's complete versioned observation/audit export.
Graph JSON and GraphML include previews, total and the full-details link.

## Lifecycle, upgrade and rollback

Archived origin details remain reachable by the same contribution path, authorized from their retained service/signal context; this does not make ordinary deleted objects visible.

Rebuild carries B's current corrected history and scope classification plus
archive-backed contribution IDs; reads still revalidate them. Work cursors reset
against the new archive, and the next bounded pass reconciles the cache.
Backups include contribution/reason counts and verify foreign keys. Normal restore
restores exactly the selected backup, including its cache and history, into a new
epoch. It never merges later observations. Cleanup of live data does not delete
archive evidence. Retention of the normal feed can require a full authorized
export after `cursor_expired`; feed tombstone access chains are not source grants.

Upgrade procedure (operator actions, **not executed here**): retain the verified
before-upgrade backup; stop the old instance; install C with A/B; start against
schema 5 or an older supported store. Schema 6 capture/migration runs before new
extractor scheduling. Confirm backup/integrity/upgrade state before authorizing
large re-extraction. Let ordinary bounded processing build contributions; old
underspecified inputs require fresh grounded extraction, not SQL reinterpretation.
The pre-upgrade-copy/storage guard from B can defer that work while preserving
knowledge and general indexing.

Rollback: stop C, preserve its schema-6 database and verified backup, then run B
with a verified schema-5 backup from before C. B rejects schema 6. There is no
in-place, lossless downgrade: observations acquired after that older backup are
not silently merged or claimed restored. Export C's full authorized history if
needed before choosing the rollback point; any migration/merge back is a separate
explicit procedure. Do not edit schema_version or drop tables to fake a downgrade.

## 0.12.0: kind of evidence, sought support, competitors apart

- Every contribution names its kind of evidence, `evidence_kind`
  (`MatchingProjection.evidenceKind`): `need` (`planned_migration`, `planned_need`,
  `explicit_need`, `organizational_transition`), `use` (`internal_use`) or
  `job_requirement` (`required_competence`, `desirable_competence`); null for other
  contexts. Read at projection, nothing stored.
- `BusinessSignals` reads support sought for an own migration ("Für unsere
  S/4HANA-Migration suchen wir externe Unterstützung", "Wir suchen einen Partner für die
  Migration auf SAP S/4HANA") as `planned_migration` of the company; a sentence offering
  such support, a recruited consultant or a client's migration ("… unserer Kunden") is
  none. It takes effect with the re-extraction of rule/8.
- Customer and competitor are not exclusive. The customer suggestion is left out only
  when its single evidence is the company's own capacity for that service (0.11.8:
  consultant and trainer titles, an employer offering the product itself); use, need and
  the postings of an actual customer stay. `MatchingRules.VERSION` is unchanged.
- Possible competitors are no matching kind and no derivation: they are read per category
  on demand (`…/kg/entities/{id}/competitors`, docs/SCOUTRO_KNOWLEDGE_GRAPH.md 36.4).
- The plan's table is `MatchingTest.customerRowsOfTheT6Table`.

## Validation and limits

Targeted SQLite tests cover the rule groups' positives/negatives, exact products,
competence/client projects, roles/employer uncertainty, scoped transitions,
withdrawals, deduplication, independent reasons, interrupted/resumed budgets,
additional evidence grants, hidden stronger reasons, archive-only targets,
long quotes/valid reference JSON, paginated details and exports. Upgrade 5→6
preserves IDs, dates and feed sequence. Backup/rebuild tests preserve corrected
observations and contribution IDs. Agent/admin tests exercise the actual API
routes and grants; browser fixtures exercise EN/DE, mobile, history links,
list/graph, full reason pagination and JSON/GraphML export.

Final local validation (JDK 17, Ant; offline browser fixtures with Chromium):

| Check | Result |
| --- | --- |
| `ant scoutro-kg-observations-test` | **362 tests passed**, including A/B lifecycle, API/agent, schema upgrade and C rules/access regressions. |
| `ant scoutro-kg-matching-test` | **82 tests passed** after the last projection change; this is a focused subset, not 82 additional distinct tests. |
| `python3 -m unittest discover -s test/scoutro-api -p test_flow_contract.py` | **15 tests passed**. |
| `python3 -m unittest discover -s test/scoutro-api -p test_mcp_adapter.py` | **10 tests passed**. |
| `node test/scoutro-ui/knowledge-suggestions-ui-test.mjs` | **44 checks passed**; the existing next-evidence test now waits for the actual response before asserting its collection context. |
| `node test/scoutro-ui/knowledge-history-ui-test.mjs` | **26 checks passed**. |
| `node test/scoutro-ui/knowledge-matching-ui-test.mjs` | **36 checks passed**. |
| OpenAPI 3.1 validation; API-description generation | Passed; **77 actions** generated. |
| `python3 test/scoutro-ui/check-locale-identifiers.py` | **0 collisions**, 18 pages against 14 locale files. |
| `node --check htroot/env/scoutro/knowledge.js`; `git diff --check` | Passed. |
| `test/jetty-solr-dependency-guard.sh` | **Failed**, pre-existing A/B relocation issue: `unrelocated Jetty reference in lib/solr9-bridge-jetty-alpn-java-client-10.0.26.jar`. |
| `test/solr9-jetty-bridge-spike.sh` | **Blocked at that same guard**; the integrated bridge spike did not reach its test execution. |

No live crawl, LLM calls, production re-extraction/rebuild, actual-disk-full or
large-index/load tests were run. Pattern coverage is deliberately small and
conservative; region equivalence, unnamed project transitions and ambiguous
employer corrections require fresh evidence or future explicit rules.
The pre-existing Jetty/Solr relocation guard remains a separate failing check;
this is not a completely green repository-wide build.
