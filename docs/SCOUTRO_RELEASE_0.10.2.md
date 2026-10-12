# Scoutro 0.10.2 release candidate

Prepared versions: **0.10.2** and **1.942-scoutro.26**, intended aliases of the
same immutable registry image. The numeric YaCy peer version remains **1.942**.
Basis: main `19e64696b4286eb97c324b932e31d09c6b9a42cd` (merge of #57, 0.10.1 /
1.942-scoutro.25) and the knowledge graph corrections of this release
(`30fb81099`, `74088decf`). Installed privately on Olares:
0.10.0 / 1.942-scoutro.24,
`ghcr.io/salamont/scoutro@sha256:3b7061cfdcb6d9fec14632cde3152769a7db31c20eb4d90f4d3973eb7ebed6df`.
0.10.1 / 1.942-scoutro.25 was published
(`sha256:9f97f303046b951ff392cf32b742b93373352c3daba6823de261ab4e4c8f2cc3`)
but is **not** rolled out: 0.10.2 contains all of it and replaces it as the
upgrade target. Its tags stay as they are.

User-facing changes: [CHANGELOG](../CHANGELOG.md). Everything of 0.10.1
(Discovery recovery, collections on the dashboard, process display, LLM
coverage, HTML parser, collections named by the operator) is described in
[SCOUTRO_RELEASE_0.10.1.md](SCOUTRO_RELEASE_0.10.1.md) and applies unchanged.
Knowledge graph corrections of this release:
[SCOUTRO_KNOWLEDGE_GRAPH.md §29](SCOUTRO_KNOWLEDGE_GRAPH.md#29-service-categories-prices-one-by-one-and-software-hints-of-job-advertisements-0102).
The access, cookie and sign-in limit contract of 0.10.0 is unchanged:
[SCOUTRO_RELEASE_0.10.0.md](SCOUTRO_RELEASE_0.10.0.md).

## What changes against 0.10.1

- **Service categories.** The category lists of *Services*, *Compare* and
  *Prices* say why they hold only their placeholder: the read failed (with its
  error code; no statement about the data), the collection has no vocabulary
  profile (with how an administrator assigns one), or no visible page states a
  category yet. `GET kg/facets?counts=false` gives the values without counts;
  `profiles` names the vocabulary of each visible collection; derived counts
  are read on their own (`derived_error`). The page keeps the values for at
  most a minute, never fills a select with the reply of a former collection
  and drops a chosen category when the collection changes.
- **Prices one by one.** *With a current price* links to the new *Prices*
  view (`?view=prices`) and `GET kg/services/prices` (agent grant `kg.read`):
  every published price of a provider's own service, exactly as published,
  with provider, amount, currency and unit, conditions, date and source;
  status filter (current, conflicting, outdated, uncertain, all), name and
  category optional; 25 per page. The count and the list share one status
  rule (the collection's price age, expiry, conflicts) and the same rights.
  No average, no conversion, no mixing of providers.
- **Software hints of job advertisements.** A fourth job policy, *Read
  software hints from job ads* (`scoutro.kg.jobs.signals.<collection>`, on
  unless switched off), keeps the software and system mentions of an
  advertisement for its declared employer, also without job objects and
  whatever the job display says. Internal use, required or desirable
  competence and client work stay apart; a requirement is never a proof of own
  use, of a purchase or of a need. An intermediary's advertisement, one without
  a declared employer and one with several employers give no hint. The company
  view lists them under **Software and systems** with context and source.
  Matching uses them only under *Use stored job signals for matching*; a
  requirement alone stays weak. The labels and help of the former switches say
  what they do: *Extract jobs as objects*, *Show job objects*.
- **API (additive).** `kg/facets` `counts`, `profiles`, `derived_error`;
  `kg/services` `with_any_price`; new `kg/services/prices`; the business view
  `systems`; `kg/collections` and its PATCH `jobsSignals`, answer
  `recheckJobs`; `kg/status` `collections[].jobSignals` and
  `sync.reconcile.jobRecheck`. `scoutroctl kg service-prices`,
  `kg facets --no-counts`, `kg collection … --[no-]job-signals` (and the three
  other job policies). Existing fields keep their meaning.

## Upgrade from the installed 0.10.0

The first start of the new image on existing DATA does, in this order:

1. **The one-time collection migration of 0.10.1** (unchanged): writes
   `DATA/SCOUTRO/knowledge/vocabulary/000-migrated-collections.json` and
   records `scoutro.kg.migration.collectionProfiles`; event
   `collection_profiles_migrated` (*extractor identity unchanged*). An event
   `collection_profiles_identity_changed` means every page would be extracted
   again: stop and report.
2. **The job reading.** The graph stores how each collection reads job
   advertisements (`kg_meta.job_modes`). On a graph of 0.10.0 or 0.10.1 there
   is none yet; the reference is what those versions read: job objects with
   their hints where `scoutro.kg.jobs.collections` (or a per-collection setting)
   extracts jobs, nothing elsewhere. A collection **without** job objects reads
   the hints of its advertisements from now on: event `job_reading_changed`
   names those collections, and the next reconcile (`sync.reconcile.jobRecheck`)
   checks their pages against the index and extracts **only their job pages**
   again (a page with a `JobPosting` in its JSON-LD, or a careers page). A
   collection with job objects keeps its reading (no event for it).
3. Everything else of 0.10.1: the LLM tier rows and the audit of long pages,
   Discovery, settings, rights (see the 0.10.1 notes).

Not changed by the upgrade: knowledge-graph schema **6**, the extractor
identity (`kg_meta.extractors`), `sync.reconcile.reextract=false`, no rebuild
or reset, no PDF, download or crawl. The application adds to `yacy.conf` only
the migration marker of step 1; the three former job switches and every
explicit setting stay as they are, a stored `scoutro.kg.jobs.signals.<c>=false`
keeps the hints of that collection off. No setting has to be changed for the
upgrade. Access mode, cookie (`scoutro.auth.cookie.secure=always`), sign-in
limits, heap, proxy trust, DHT, `scoutro.kg.gate.maxLoad` and
`ai.service_num_ctx` (empty for the knowledge host; the 32,768 window Ollama
reports is read, not sent) stay as they are. Do not reset or re-seed
`yacy.conf`, and do not change context, parallelism or GPU configuration on
account of the upgrade.

**Cost of step 2.** The re-check reads every document of the collections
concerned from the index with real-time gets (as a normal reconcile) and runs
the extractors of the rule and JSON-LD tiers on their job pages only. The LLM
tier is not involved. Its progress shows in the technical diagnosis
(*Job pages read again for*), and the event names the collections.

**Upgrade from 0.10.1** (where installed): step 1 has run already; step 2 as
above.

## Rollback

The primary return point is a current, consistent full DATA/configuration
backup with checksums and an isolated restore, plus the 0.10.0 digest above.
No lossless downgrade is claimed.

- **To 0.10.0:** the rollback notes of 0.10.1 apply (Discovery store, held
  candidates, removed collections, migrated vocabulary file: keep it).
  In addition, 0.10.0 ignores `kg_meta.job_modes` and `kg_meta.job_recheck` and
  the key `scoutro.kg.jobs.signals.*`. The software hints read since the
  upgrade stay as statements of the employer (section `job_ad_…`) until their
  page is extracted again; 0.10.0 lists them among the employer's facts and
  extracts no new ones. A job page whose input hash carries the job reading is
  extracted again by 0.10.0 when it next processes the page (the hash
  differs), and then holds no hints any more. In matching, 0.10.0 does not
  confirm the requirements (its parser reads them as the company's own
  competence) and may count an internal use stated in an advertisement like
  one of the company's own pages, regardless of *Use stored job signals for
  matching*; keep such a rollback short or switch the suggestions off
  (`scoutro.kg.matches.max=0`) while it lasts.
- **To 0.10.1:** as to 0.10.0 for the job reading; the rest of 0.10.1 is
  identical.
- A later start of 0.10.2 compares with the stored reading again and checks
  only what changed.

## Acceptance (private Olares instance)

Run after the upgrade of the existing app `yacysearch` (namespace
`yacysearch-salamont`), read-only unless a step says otherwise. Steps 1–9 of
[the 0.10.1 acceptance](SCOUTRO_RELEASE_0.10.1.md#acceptance-private-olares-instance)
apply with the 0.10.2 digest and version `1.942-scoutro.26`, then:

10. **Job reading:** event `job_reading_changed` once, naming the collections
    without job objects; `sync.reconcile.reextract=false`; the extractor
    identity unchanged; `sync.reconcile.jobRecheck` empty after the reconcile
    completed; settings: four job switches per collection, the three former
    ones as before the upgrade, *Read software hints from job ads* on (or off
    where a setting says so).
11. **Software and systems:** a company with job advertisements shows
    *Software and systems* with context and source; a requirement is shown as
    a requirement, not as use; nothing is attributed to an intermediary; the
    job display setting does not hide the section.
12. **Categories (phone):** *Services* and *Compare* of each production
    collection offer their categories; a collection without a vocabulary says
    so; no unexplained empty list. Switching the collection clears the
    category.
13. **Prices (phone):** *With a current price* opens the list; its *Current
    prices* count equals the number; rows name provider, price as published,
    unit, conditions, date and source; paging works; another collection shows
    none of this collection's prices.
14. **After 24 hours (read only):** requests per hour, backlog and storage
    within their limits; ten software hints chosen by the owner checked on
    their source pages (right context, right employer).

## Candidate validation and later publication

The release PR records the exact tested commit, the locally built linux/amd64
image configuration ID, checks and limitations. A local configuration ID is not
a registry digest. Skipped fork CI is not a passed build.

**The authorized merge** of the release PR changes `scoutro.properties` and the
workflow and therefore triggers the main `Publish Scoutro image` workflow. It
first refuses existing tags (only a genuine `404 / MANIFEST_UNKNOWN` counts as
free), then builds and pushes `1.942-scoutro.26` and `0.10.2` with the actual
merge revision. Do not dispatch it in addition. Before the merge, re-fetch
main/head and recheck both tag names. After publishing, verify one immutable
digest for both tags, linux/amd64 and the merge revision label; the Olares
package pins that digest, never a local image ID.
