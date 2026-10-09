# Durable system and need knowledge — package B

Stacked on draft PR #39, package A (08a42db1d6609f6f53e10a51890f8e1bc28b7847).
At the start of this work #39 was open; main was
7847fe1ed54eda64348eaa64cd15d7eb8c107639.
This prepares grounded inputs. It adds no customer/partner matching rules,
automatic consulting-house exclusion or individual care profiles.

## Store and upgrade

SQLite schema **5**, vocabulary **3**, JSON-LD extractor **4**, rule extractor
**5**, LLM extractor **3**. Migration completes before these workers can replace
evidence. The following additions survive live graph cleanup:

| Storage | Purpose |
| --- | --- |
| kg_evidence.source_revision / source_observed_at | Captured content revision and source time, separate from processing time. |
| kg_observation | Normalized assertion, original quote, locator, source ID/URL/revision, versions, three dates, original/current actor, historical identity grounding, correction/source/assertion states, historical collection provenance. |
| kg_observation_scope | Current access classification. Historical membership never grants access. |
| kg_observation_event | Recording, source/assertion/identity and classification audit events, with stable kgh IDs. |

There are no foreign keys to live documents, statements, jobs or entities.
Only scope/event ownership refers to an archived observation. Relevant job
metadata, contextual systems/needs/own-service roles, service names/categories/
descriptions and offers with necessary source-local identity evidence are
retained. Arbitrary facts, salary and personal contacts are not copied.

The additive migration copies only relevant **remaining** evidence, idempotently.
It invents neither lost quotes nor systems from old job titles. Legacy source
dates are accepted only when an active document's load predates processing;
otherwise unknown. Unrecoverable vocabulary versions are legacy-unknown.

Repeating the same revision/extractor/locator/quote is idempotent. New revisions
and extractor assessments add observations; incomplete extraction does not
withdraw earlier statements. A database trigger prevents rewriting original
quotes, identity grounding, values, revisions and dates; corrections append
revisions and change explicit current states. Originals are never replaced with
newer text.

## Independent states and time

| State | Meaning |
| --- | --- |
| Job search | Explicit end, passed published application deadline, observed search or current status unknown. Age/disappearance do not prove filling. |
| Source | Last known index/live source state; removed means the live source object was removed, with current website reachability unknown. No probe starts. |
| Assertion | Historical assertion retained, corrected/withdrawn, or updated by a later scoped migration/shutdown. |

Archived job search is unknown without an explicit end or published deadline.
An active live job's open status describes observed search, not assurance of
today's vacancy. Technical source expiry does not end it. System/competence
knowledge remains when the job ends or source/live object disappears.

asserted_at preserves a quoted assertion date at its original precision, including
a year only. observed_at is the source load date captured with the evidence, or
null; recorded_at is processing time. Old text/cache reuse never sets observation
time to today. A genuinely newer successful source load of identical content
adds a separate confirmation even when extraction can be skipped; it does not
rewrite the old observation. Failed loads never reconfirm old job quotations.
The internal-use UI states “Zuletzt belegt am …; heutiger Einsatz
nicht erneut bestätigt”. Competence retains its required/desirable context.

Later shutdown/completed source migration is linked only for the same product
and known matching scope or explicit organization-wide scope. Unspecified scope
never retires every installation. A completed target migration may update the
corresponding scoped plan. Competence is never promoted to installed software or
ended by a system change. Quoted event dates retain their precision and are
distinguished from “since” dates; republishing an old shutdown does not retire a
later use observation. Original quotations remain.

## Lifecycle protection

Capture runs on publishing and **before** evidence replacement/deletion,
statement deletion/FK cascade, or source revision change/deletion. Archival
failure aborts the entire destructive SQLite transaction. Aggregates, retention,
reconciliation and FullReset keep history while cleaning the live graph.

Existing disk/budget/WAL admission applies. Refused KG replacements/enrichment
remain deferred in the work queue/reconcile state; lastError, queue and refusal
counters expose the backlog. General Solr search/indexing is not paused. Archive
history has no age retention in this package.

All archived source revisions receive the current classification, even if
extraction is off or the source is no longer followed/live. Reconcile checks
archived source IDs in bounded Solr batches; failed checks do not guess permissions.
As in the live KG, classification follows index synchronization; agent grants
are checked per request. Historical collection names are filtered to current
grants in records. Inaccessible classification events are excluded before
pagination, and inaccessible correction pointers are omitted from details.

Identity merges update current actor assignment with audit. Original actor and
source-local identity grounding stay intact. Unnamed/ambiguous employers remain
unresolved. Equal-title postings use their declared posting ID or separate
source/node context, preventing shared end status. This adds no general identity
algorithm; ambiguous historical splits remain a limitation.

Rebuild carries the authoritative archive **after the old writer stops**, before
swap: all observations, corrections, current scopes and stable observation/audit
IDs, including changes during build. Shadow-only revisions of known sources
inherit final classification. Historical actors affected by identity splits are
reassigned only from matching source-local grounding; ambiguity remains unresolved
instead of following a global redirect blindly. Failed carry rolls back the swap.
Consumers sync again after the new epoch.

Backups include observations/scopes/events, verified counts and foreign keys.
**Restore selects exactly the chosen backup**, with a new epoch; normal reconcile
then applies today's source state/classification. Newer observations/corrections
are not silently mixed in. The previous database remains the existing
before-restore safety copy. An archive merge/import is a separate future action.

## Controlled extraction

Global versioned products.json / needs.json are independent of collection category
vocabularies. Content fingerprints participate in extraction/cache identities;
overrides use the same file names in the existing vocabulary directory.

Products: SAP family, S/4HANA, BW, BW/4HANA, SAP Analytics Cloud, Salesforce,
Microsoft Azure, Revit, Archicad. Generic SAP does not prove S/4HANA; SAPV is not
SAP; bare SAC is ambiguous. Product mention without context yields no assertion.

Contexts: internal use, required/desirable competence, customer projects,
planned/completed migration, shutdown, offered support capability. Small
deterministic sentence rules preserve qualifiers and skip negation or ambiguous
mixed internal/customer context. Own construction/site expansion, energy
renovation, leadership/team development and organizational care transitions have
similarly explicit actor/context rules. Staff capacity and marketing keywords
do not imply procurement.

JSON-LD description/skills/qualifications are processed independently per posting.
Text postings use separate windows: up to 6,000 characters, at most 30; existing
overall bounded source-text limits remain. Overlong/ambiguous sentences are
skipped, not truncated into stronger claims. HTML paragraph boundaries survive.

Employer, portal publisher, recruiter and deployment company are separate roles.
Proxy phrases block automatic employer attribution; domain operator alone is
insufficient. Company role requires own offered service evidence, not name,
collection, title, NACE or product alone. IT/SAP consulting and integration have
role evidence; care consulting/coaching are not IT consulting. Own jobs remain
knowledge; matching exclusions belong to C.

LLM schema, grounding, validator, deterministic normalization and application
share the context rules. Custom prompt text is retained and the prior root answer
shape remains compatible. Signal values need actor/context grounding; multi-job
pages additionally require job-title grounding. Version/fingerprint cache keys
isolate old responses. No real LLM call was run during this work.

## API, policies and consumers

All reads use current server-authorized scope, narrowed by the optional collection.
Missing/inaccessible observations share 404; there are no hidden totals.

| Endpoint under /scoutro/api/v1/kg/ | Contract |
| --- | --- |
| history?entity&source&after&limit&collection | Observation keyset page, limit 1–100, next_after. |
| entities/{id}/history | Also works after deletion of the live entity. |
| observations/{kgo_id} | Original quote/context/revision/identity, dates and separate states. |
| observations/{kgo_id}/history?after&limit | All authorized audit events, stable ID and current-epoch sequence. |
| export?include=history&cursor&limit&collection | scoutro.kg.history.v1: observations followed by **all** audit events, at most 100 per page. No 20-evidence cap. |
| export/download?include=history&format=ndjson | Administrator full stream: header, records, complete/incomplete trailer. JSON also supported. |
| changes?cursor&expand=true | New kind observation; scoped upsert record or deletion notice when access is lost. |

Agent mirrors require kg.read for details/history, separate kg.export for
export/changes. Workers can read; export remains external-agent only. No preset/
grant is added. Settings, prompts, control and downloads stay administrator-only.

Live export remains scoutro.kg.v1. History export is explicit and not a snapshot.
Check complete, follow next then next_changes. For changed observations read their
paginated events and deduplicate by stable kgh ID. Sequence cursors belong to one
epoch; restart full export after epoch change/feed expiration. Old feed clients
must tolerate the added observation kind.

Per-collection settings PATCH/response adds jobsExtraction, jobsDisplay,
jobsMatching. Keys are scoutro.kg.jobs.extract.<collection>,
scoutro.kg.jobs.display.<collection>, scoutro.kg.jobs.matching.<collection>.
Global lists scoutro.kg.jobs.display.collections / scoutro.kg.jobs.matching.collections
initially inherit scoutro.kg.jobs.collections if missing. The first policy edit
freezes the legacy opt-in. Direct config edits should set all desired policies
explicitly. Only extraction changes require re-extraction; off deletes no
knowledge and changes no permission. Matching policy prepares future C consumers.

## Concrete upgrade and rollback

1. Keep an external peer data/config backup before deploying. Plan archive
   headroom; pause KG growth if re-extraction should wait for an operator window.
2. Start the new binary. Integrity/pre-upgrade backup precede schema 5/backfill,
   then workers start. Inspect schema, upgrade-hold, storage, queue and reconcile.
   Failed migration rolls back; never erase evidence to make room.
3. If the pre-upgrade copy did not fit, free space and create a verified backup
   before releasing the existing re-extraction hold. Custom prompts remain.
4. Verify scoped history and a complete export; take a schema-5 backup with
   archive counts. Reconcile applies today's classification without erasing
   historical assertions.
5. For application/schema rollback: stop the peer, preserve the entire schema-5
   KG directory and history export separately, restore a verified pre-upgrade
   database and compatible older binary/config, then start. The old binary refuses
   schema 5. Restoring schema 4 using the **new** binary upgrades it again.

**No lossless downgrade is demonstrated.** Pre-upgrade backups exclude later
observations. Preserving schema-5 files/export allows later explicit recovery;
automatic merging/import is absent. These are documented procedures, not
operations performed on production.

## Verification and remaining limits

Run the Ant scoutro-kg-observations-test target, offline history/suggestion browser
tests, API generation/reproducibility and locale identifier guard. Tests exercise
real SQLite upgrade, idempotence, incomplete replacement, FK cleanup, retention/
reset, reconciliation outside followed scope, authoritative final rebuild carry,
backup/restore, cache/date separation, ambiguities, employer uncertainty, agents
and complete history export.

Validated for this change: 308 Java tests in the focused target, 26 offline
history browser checks and 44 existing package-A suggestion checks (English and
German), 14 API contract/reproducibility tests, OpenAPI validation, JavaScript/
CLI syntax checks, locale identifier guard (one page, 14 locales, no collisions),
and git diff --check. A fresh core compilation was also exercised.

The existing jetty-solr-dependency-guard.sh remains open: unrelocated Jetty
reference in lib/solr9-bridge-jetty-alpn-java-client-10.0.26.jar. Guard, relocation
tool and dependency versions are unchanged in B; the bridge spike stops there.
Targeted passing tests do **not** imply a green repository-wide check.

No live crawl, real LLM request, production mass evaluation/rebuild, deployment,
large-index/load test or actual disk-full fault injection was performed.
Rules deliberately cover bounded German/English contexts, not arbitrary semantic
matching. Indirect employers, implicit scopes and historical identity splits
cannot all be resolved automatically. Unknown data stay unknown; C must defer
unresolved employers and preserve independent valid proposal reasons.
