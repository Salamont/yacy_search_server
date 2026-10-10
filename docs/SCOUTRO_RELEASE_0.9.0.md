# Scoutro 0.9.0 release candidate

Prepared image versions: **0.9.0** and **1.942-scoutro.22**, intended to name the
same immutable published digest. Numeric YaCy peer version stays **1.942**.
Basis: integrated main `7ac44006e29838d5f6349fe3742eadd4e0a44975` (#42, #39,
#40, #41, #43). The actual tested release-head SHA and local artifact/image
identities are recorded in the Draft release PR; this is not a published release.

## What changes

**Suggestions across collections.** The selected collection determines the
provider and its facts. Customer/partner suggestions may include every candidate
collection permitted to the current user/agent. Other collections are visibly
labelled, memberships are deduplicated and links open their authorized context.
Evidence-chain authorization precedes ranking, counts, pagination and export.

**Durable business knowledge.** Relevant system, competence and need observations
retain quote, source/revision, attribution, actor assignment, context and dates.
Source observation and processing times differ: re-extracting an old text does
not renew its date. Internal use, required/desirable skills, customer-project
work, planned migration and completed migration/shutdown are distinct. Product
names alone do not establish installed systems or purchasing demand.

Job search, source availability and system assertions are independent. Age
without a documented end does not end a job. Ending/removing an ad does not erase
its system evidence. Historical use is marked as last documented at a date,
with current use unconfirmed. New documented corrections/migrations affect only
the relevant scope; competence never turns into confirmed internal use.

**Six explicit matching groups**, in addition to independent industry reasons:

| Provider service and candidate evidence | Proposed relation |
|---|---|
| Product-specific IT support ↔ internal system use, competence or planned change | customer |
| Revit/Archicad support, training or introduction ↔ matching CAD/BIM use/competence | customer, including architecture firms |
| Architecture/planning/building ↔ own construction, conversion or site expansion | customer |
| Energy advice/planning ↔ own energy renovation project | customer |
| Coaching/organization services ↔ explicit own leadership/team-development need | customer |
| Regional ambulatory/short-term care ↔ documented hospital care-transition process | possible partner |

No arbitrary keyword/LLM matching, mandatory declared target industry, personal
care profiles, confirmed contract or probability claim. Employer must be assigned
for job-based contributions. Own ads of evidenced IT/SAP consultancies/system
integrators do not carry the corresponding job-based customer reasons; independent
reasons remain valid. Unclear company role is visible uncertainty; unclear employer
postpones job matching. Recruiting to deliver a service is not purchasing demand.
Reasons remain separately supported, dated and retractable.

**Optional LLM timing.** Automatic remains the default. Scheduled/manual modes
use a saved zone, weekdays and daily start window; midnight windows belong to
the starting weekday. A request already running may finish, later chunks wait.
A shared minimum interval includes both workers and real fallback HTTP requests;
cache hits do not start requests. Manual runs are bounded and stoppable and keep
collection exclusions, manual KG pause, resource/integrity protection and breaker.
Crawling, indexing, rules/JSON-LD and existing knowledge remain available.

**Packaging.** Corrected Jetty/Solr relocation retains unchanged original
upstream manifests, Maven provenance and attribution under
`lib/solr9-bridge-upstream/`. The unchanged Dependency-Guard verifies isolation.
Distribution includes Scoutro metadata/changelog/release notes. The image build
can mount a temporary session CA for dependency resolution; it is not shipped in
the runtime image. No upstream version/dependency-guard weakening.

## Upgrade: schema 4 → 5 → 6

1. Identify current image/binary revision, KG schema, config, DATA volume and
   archive size. Pause ongoing work and stop the old peer for a consistent backup.
2. Preserve a **complete** verified DATA/configuration backup and compatible old
   binary/image digest outside the live volume. Include Solr/index, queues, all
   `DATA/SETTINGS`, `DATA/SCOUTRO` (graph, WAL/SHM where applicable, checkpoints,
   backups, agent/discovery settings) and any externally mounted configuration.
   Check hashes and prove restore on an isolated copy; a KG API backup alone is
   insufficient. This preparation does not access the existing installation.
3. Reserve space for the pre-upgrade copy, WAL, durable evidence growth, any
   shadow/rebuild, and configured free-space guards. No universal small MB floor
   guarantees capacity; size the reserve from the actual copied dataset.
4. Start the new binary only against the intended test or authorized upgrade
   volume. Integrity check and protected pre-upgrade copy precede schema work.
   Additive migrations run 4 → 5 → 6. Still-existing relevant evidence is captured
   idempotently before replacement/new extraction; already lost evidence is not
   reconstructed. Schema 4 has no observation-history table to recover.
5. Inspect `store.upgrade`, integrity, schema, backup and storage/upgrade holds,
   reconciliation, archive counts and scopes before resuming growth. When held,
   retain protection, resolve storage/integrity and verify backups; never edit
   `schema_version`, drop archive tables or bypass the hold.
6. Validate ordinary facts, authorized history/export, suggestions and employer
   assignment, source/job/system status separation and LLM timing on the copied
   volume. Then take a verified schema-6 KG backup/export alongside the full backup.
   Production migration/performance remain separate rollout acceptance.

Default LLM timing is automatic if no schedule exists. A release does not set
production schedules or re-enable excluded collections. New versions may trigger
existing extractor-version reconciliation only after protective migration.

## Rollback and operating limits

No lossless in-place downgrade is demonstrated. Stop the new peer, preserve its
entire schema-6 KG/archive and complete authorized export separately, then restore
the **chosen** compatible old full backup with its compatible binary/config.
C → B needs a schema-5 backup; pre-B needs its supported schema, typically 4.
Observations after that chosen backup are absent there; normal restore must not
mix newer observations in. A deliberate archive merge is a separate operation.

Old versions ignore the LLM schedule and may call unfinished chunks again.
Disable LLM/pause KG before reverting if automation is undesired. Hard failure
before an unpersistable result is saved may still lose that result.

Local fixture upgrade, simulated write refusal and bounded SQLite-FULL do not
prove productive upgrades, physical OS disk-full, provider/model quality or
production load. The earlier parallel Rebuild timeout is not declared disproved
by successful serial tests. Fork CI skipping is no successful CI evidence.

## Build evidence and next publication step

Local checks use the real Ant `all`/`dist` targets and the actual two-stage
`docker/Dockerfile.scoutro`, with a normal candidate checkout, temporary DATA,
controlled source/model fixtures and no registry push. Attribution checks compare
original JAR bytes with the tarball and the actual exported image root. The Draft
PR distinguishes distribution checks, actual local image/run checks, schema-4
upgrade smoke, and the **not yet performed** verification of a published image.

**Next step requires separate authorization:** re-fetch main and revalidate the
release PR after any basis change, recheck both Git/registry version names, then
merge exactly the accepted release head. Because the existing main workflow
publishes on version/build-file changes, this merge is itself the publication
trigger. Do not add a manual dispatch or registry push. Preserve the overwrite
refusal and wait for the single build/push run. Confirm both tags share the same
digest, labels identify the actual merge commit, and pull/run that published digest
with isolated data and attribution/upgrade smoke before any installation change.
The final merge revision differs from this candidate, so local labels are not
proof of the later registry image.

No Git release tag, manual release, Olares deployment or Community Market
publication is part of this PR. A later rollout targets the owner's existing
Scoutro installation first, following verified backup/restore acceptance.
