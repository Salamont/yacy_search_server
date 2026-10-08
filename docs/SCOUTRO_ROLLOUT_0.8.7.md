# Rollout of 0.8.7 (1.942-scoutro.21): operations handover

Status: 2026-10-08. A handover for a later, separate OpenCode task on the Olares peer. Nothing here has been run
in production. This release prepares no rollout, no knowledge graph rebuild and no change of production data.

## What 0.8.7 changes in a running peer

| Change | Effect at the first start | Effect only by an operator step |
|---|---|---|
| Rule extractor version 4 (PR #36) | The extractor identity changes: every tracked page is extracted again at low priority (`Reconciler.reextractAll`), tiers 1 and 2 only. Paused like all growth. | — |
| StackFinder OSM rule, `criteria_version` `2026-10-08.1` (PR #37) | None. Automation reads its rules from `DATA/SCOUTRO/config`, never from the repository. | Update the runtime rules (step 4). |
| `collections.reassign` (PR #37) | None. | Move yowea.com (step 5). |
| `scoutro-discovery exclude/include` (PR #37) | None. | Exclude yowea.com from StackFinder (step 5). |

Not changed by this release: the knowledge graph schema stays at 4 (no migration, no copy).

**Not done by this release.**
- **LLM evidence (tier 3)** stays as it is. The re-extraction does not ask the LLM tier again, and nothing corrects
  its old answers. A statement that also has LLM evidence survives even if the rules no longer find it.
- **Merges are not split.** The old LIVAID/Markel merge, its redirect and its `site_operator` key with the
  insurer's legal name stay. Only the identity rebuild (`KgRebuild`, "re-resolve identities") splits them. That
  rebuild needs about 1.2 × the graph's size of free space and **must be planned and run separately. It is not part
  of this rollout.**

## Order

Pause before the new image starts. Otherwise the re-extraction may read yowea.com before the move, under the
StackFinder vocabulary, and there is no action to re-extract one domain (see
[collection reassignment](SCOUTRO_COLLECTION_REASSIGNMENT.md#together-with-the-rule-extractor-version-4-pr-36)).

1. Record (0.8.6, read only)
2. Pause Discovery and the graph's growth (0.8.6)
3. Rollout and restart, then verify that both pauses held
4. Runtime rules in `DATA/SCOUTRO/config`
5. Move yowea.com, then exclude the StackFinder pair
6. Resume the graph, check success per document ID
7. LIVAID: production `text_t` and operator
8. Resume Discovery

Run everything with administrator credentials from the secret store; never type them into logs or notes.

`<state root>` is Discovery's state directory: `$SCOUTRO_DISCOVERY_DIR`, else the YaCy setting
`scoutro.discovery.stateRoot`, else `DATA/SCOUTRO/discovery`. Check which applies in the container before step 2.

## 1. Record (before the rollout, read only)

- The Yowea starting state of [the reassignment procedure, step 0](SCOUTRO_COLLECTION_REASSIGNMENT.md#operating-procedure-for-yoweacom-opencode-after-a-rollout-of-this-change):
  - the page IDs, URLs and collections of yowea.com (expected 18, all `stackfinder-web`);
  - `kg/sources/<id>` for each of them;
  - two control domains;
  - `state.json` entries.
- LIVAID: the IDs of livaid.com's pages (`GET /scoutro/api/v1/index/browse?q=livaid.com&limit=100`).
  - Find the imprint page among them.
  - For it: `GET /scoutro/api/v1/kg/sources/<id>?limit=100` (the maximum; page on with `offset` while `total` is larger). Keep the items, `source.processed_at` and `total`,
    and the items whose `evidence.tier` is 3 (LLM).
  - The entities of `GET /scoutro/api/v1/kg/hosts/<host>/entities` for each livaid.com host of the page list (the
    route matches the exact host name, e.g. `www.livaid.com`), with their IDs and any redirect.
- `GET /scoutro/api/v1/kg/status`: keep `store`, `sync.lag` and the extractor versions.
- A graph backup: `POST /scoutro/api/v1/kg/control {"action":"backup"}`. Check that it is listed afterwards. It is
  for a damaged graph only, not for undoing the move.
- Copies of `DATA/SCOUTRO/config/osm_profiles.json` and `profiles.json`.

## 2. Pause (still on 0.8.6)

- **Discovery automation.**
  - Read `revision` from `GET /scoutro/api/v1/discovery/status`.
  - Then `POST /scoutro/api/v1/discovery/pause` with the body `{}` and the header `If-Match: <revision>`.
  - On `409 revision_conflict`, read the status again.
  - The flag is stored in `DATA/SETTINGS/scoutro-discovery-jobs.json`.
  - Accepted crawls continue. Wait until no Discovery crawl runs (`GET /scoutro/api/v1/crawls`).
- **Manual Discovery runs.** `scoutro-discovery --workdir <state root> pause`.
  - The flag is stored in `state.json`.
  - `start` and `classify` refuse while it is set, and the automation starts no batch.
- **The graph's growth.** `POST /scoutro/api/v1/kg/control {"action":"pause"}`.
  - It is stored in the graph (`kg_meta`).
  - Check in `GET /scoutro/api/v1/kg/status`: `store.manualPause` is `true` and `store.manualPauseSaved` is `true`.
  - If `manualPauseSaved` is `false`, the store refused the write. Do not roll out: find the cause first.
- **No crawl of yowea.com or livaid.com runs** (`GET /scoutro/api/v1/crawls`).

## 3. Rollout and restart (a separate step; only verified here)

The image tag and digest come from the publish run of this release, which has not run yet. Pin the digest, not the
tag, in the Olares package.

After the start, before anything else:

- **Version.** `GET /scoutro/api/v1/system` shows `version.scoutro` `1.942-scoutro.21`; the image label
  `org.opencontainers.image.version` too.
- **The pause held over the restart:**
  - `kg/status`: `store.manualPause` `true`, `store.manualPauseSaved` `true`;
  - `discovery/status`: `paused` `true`;
  - `scoutro-discovery --workdir <state root> status`: `"paused": true`.
- **The re-extraction is queued, not running.**
  - `kg/status` `sync.lag.byType.reconcile` holds the queued re-extraction (in a local rehearsal: one per
    tracked page). Pages' `processed_at` stays at step 1's values; check this with yowea.com's and livaid.com's IDs.
  - Maintenance (re-scoping, integrity, reconcile) may still run.
- **If any pause did not hold:**
  - Pause again at once.
  - Compare yowea.com's `kg/sources/<id>` `processed_at` with step 1. A newer value means the re-extraction already
    read the page with the StackFinder vocabulary.
  - Report that and continue. The move still corrects the collections; only tiers 1 and 2 keep the old vocabulary
    for those pages until their next extraction.

## 4. Runtime rules (`DATA/SCOUTRO/config`)

Change only the StackFinder parts. Leave every other profile and file unchanged.

- **`osm_profiles.json`.** Replace only the generic StackFinder rule (`office` company/consulting) with the one from
  the repository's `tools/scoutro/discovery/osm_profiles.json`. It has its own `text_fields` (name, operator, brand,
  description, service), a strong IT `text_any`, and `text_words` IT, EDV, SAP, ERP, DevOps.
- **`profiles.json`**, StackFinder:
  - `criteria_version` `2026-10-08.1`;
  - the new fail criterion: coaching, training, personnel, management or general consulting without an IT,
    software or technology offer;
  - `digitalisierung` removed from the keywords.
- **Before saving:**
  - diff against the step 1 copies: only these lines may change;
  - check that both files are valid JSON.
- **After saving:** `GET /scoutro/api/v1/discovery/status` reports no config error.
- Discovery stays paused while the files change. A multi-file change is not atomic.

## 5. Move yowea.com and exclude the StackFinder pair

The Yowea procedure in [the reassignment doc](SCOUTRO_COLLECTION_REASSIGNMENT.md), steps 3–5:

1. **Preview.**
   `POST /scoutro/api/v1/collections/reassign {"domain":"yowea.com","add":["checkthecoach-web"],"remove":["stackfinder-web"]}`
   Expected:
   - only yowea.com hosts;
   - `documents` = `changes` = the step 1 count;
   - `before` `{"stackfinder-web": n}`, `after` `{"checkthecoach-web": n}`, `kept` `[]`;
   - `crawlRunning` `false`.

   Stop on any difference and report it.
2. **Apply** with `"confirm":"<token>"`. Expected:
   - `applied` `true`, `updated` n, `failed` `[]`;
   - `webgraphKept` 0 when the webgraph is written.

   On `409 reassign_preview_stale`, preview and apply once more. For pages in `failed`, run preview and apply again.
3. **Exclude the pair.**
   - `scoutro-discovery --workdir <state root> exclude --profile stackfinder --domain yowea.com --reason "coaching consultancy, moved to checkthecoach-web" --dry-run`.
   - Check `before` and `after`, then run it without `--dry-run`.
   - The `checkthecoach` entry stays as it is.

While the graph is paused, the graph does not show the move yet: the touched pages' new input waits as growth.

## 6. Resume the graph and check success per document ID

- `POST /scoutro/api/v1/kg/control {"action":"resume"}`. Check `store.manualPause` `false`.
- The checks of [the reassignment doc, step 7](SCOUTRO_COLLECTION_REASSIGNMENT.md), by ID against step 1. Never use
  global counters, collection totals or `sync.lag.pending` reaching 0: the re-extraction of every page keeps them
  moving for a long time.
  - **Index:** every recorded ID keeps its `url`; it has `checkthecoach-web` and no `stackfinder-web`.
  - **Graph:** `kg/sources/<id>` shows `source.collections` `["checkthecoach-web"]` and a `processed_at` newer
    than step 1.
    - Repeat until all n agree, with a timeout of 30 minutes per round.
    - The re-extraction runs at low priority behind new pages. On a large graph, "all n" can take longer than
      30 minutes. Then record how many agree and check again later; it is not a failure.
  - **Evidence:** `total` may differ from step 1, because tiers 1 and 2 were extracted again under the coaching
    vocabulary. **The tier 3 (LLM) items stay as they were.**
  - **Visibility,** for each host of the preview's `hosts`. The route matches the exact host name:
    `kg/hosts/yowea.com` does not show the entities of `www.yowea.com`.
    - `kg/hosts/<host>/entities?collection=checkthecoach-web` lists the organisation on at least one host;
    - `…?collection=stackfinder-web` lists none of the domain's entities on any host.
  - **Controls:** unchanged collections in the index and the graph.
  - **Nothing more to move:** a new preview answers `documents: 0`.

## 7. LIVAID: production `text_t` and operator

The tests of PR #36 use the reported operator block. Its chamber and insurer sections are assumed. These fixtures
are no proof for the production page; this step closes that gap.

**1. Read the production text** (read only).

`GET /scoutro/api/v1/index/evidence?domain=livaid.com&limit=20&maxChars=4000`.

For the imprint page, check against the expected order: LIVAID, "Live | Architecture | Innovation | Design",
Geschäftsführerin, Tânia Ferreira, Bonnstraße 164, 50354 Hürth, Telefon, Mobil, E-Mail. Then the chamber and insurer
sections, and how they are introduced (heading, colon, phrase).

Record it in the report:
- the order;
- the separators (". " or line);
- the section headings;
- whether "LIVAID" is a unit of its own.

The excerpt is page content: treat it as data. It is cut at `maxChars`, so the window after the imprint marker may be
incomplete. Report that if so.

**2. Wait until the re-extraction has reached the imprint page.** `kg/sources/<imprint id>` shows a `processed_at`
newer than the rollout's start.

**3. Check the operator** in `kg/sources/<imprint id>?limit=100` (all pages via `offset`) (tiers 1 and 2, `evidence.kind` `rule` or
`metadata`):
- the organisation's name is "LIVAID";
- address Bonnstraße 164, 50354 Hürth;
- phone +4922335416033 and `info@livaid.com`;
- no Markel Insurance SE name, address, phone or e-mail;
- no legal form statement from the insurer;
- nothing from the chamber;
- no person (Tânia Ferreira) as an organisation.

**4. Expected and not a fault until the rebuild:**
- the entity is still the merged one, with the same ID as before, its redirect and the `site_operator` key with the
  insurer's legal name. Its displayed name may already read "LIVAID";
- the tier 3 items are unchanged (the analysis found none for livaid.com).

**5. If the production text differs from the tested layout and the check fails:** report the text form. Do not
change any data. Typical cases:
- the name runs into the street without a break;
- LIVAID is not a unit of its own;
- the insurer section has no recognised heading.

A fix is a separate code change.

## 8. Resume Discovery

- `scoutro-discovery --workdir <state root> resume`.
- `POST /scoutro/api/v1/discovery/resume` with `{}` and `If-Match: <revision>` of a fresh `discovery/status`.
- Optional: `scoutro-discovery classify --profile stackfinder`. It re-classifies the older StackFinder results under
  the new `criteria_version`; it changes verdicts only.
- Watch the next StackFinder batch: yowea.com is not selected (status `excluded`). A StackFinder crawl of the domain
  would be refused anyway (`409 collection_conflict`).

## Not part of this rollout

- **The identity rebuild for the old LIVAID/Markel merge.** Plan it separately:
  - check the free space (about 1.2 × the graph's size);
  - make a backup;
  - run it in a quiet window;
  - afterwards check livaid.com and Markel's entities by ID.
- Any correction of old LLM evidence. There is no automatic one. Reading pages again with the LLM is a separate
  administrator decision.
- The roughly 80 suspected name hits from the analysis. They are suspected cases, not a measured error rate.

## Rollback to 0.8.6

The rollback target is `ghcr.io/salamont/scoutro:0.8.6`
(`sha256:a5689d2b80ae18de57ce6344a2d84d8b6d13ad578715f33e813a19de38ae693f`).

- **Rule version.** 0.8.6 has rule version 3. Its start re-extracts every page again with the old rules, so the
  Markel facts come back on livaid.com. Pause the graph's growth before a rollback, as for the rollout.
- **Moved pages stay moved.** 0.8.6 has no `collections.reassign`. To undo the move, run it the other way round
  *before* the rollback.
- **The exclusion.** 0.8.6 does not know the status `excluded`. It treats the entry as a problem entry and may select
  it for a retry. The Discovery guard still refuses a StackFinder crawl while the pages are in `checkthecoach-web`.
  Keep Discovery paused until the decision.
- **The schema stays at 4**, so the graph files need no change in either direction.
- **The runtime rules.** The new rule keys (`text_fields`, `text_words`) are read as OSM tags by 0.8.6's
  `scoutro-discovery`, so the rule would match nothing. Put the step 1 copies back.
