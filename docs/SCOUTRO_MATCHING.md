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
| `it-support` | Product-specific support, consulting, training or implementation | Exact product; internal use, required/desirable competence or planned migration → possible customer. General SAP does not prove S/4HANA, BW or SAC. |
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
or system integration excludes its job contributions. Its archived knowledge and
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
system/project change and regional match. Additional evidence collections must
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
