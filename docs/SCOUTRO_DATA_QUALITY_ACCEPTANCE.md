# Data quality programme R1–R4: isolated acceptance before merge and rollout

Status: 2026-10-18. A decision paper, **not** an approval: no merge to `main`, no tag, image or deployment, no access to
the production Olares, no production data, crawl or model call. `apply` and the identity switches stayed off. Basis:
#78–#87, integration branch `scoutro/r4-wp-b-quality-integration` (#87) at `e865f31`, the rollout order in
[SCOUTRO_DATA_QUALITY_ROLLOUT.md](SCOUTRO_DATA_QUALITY_ROLLOUT.md).

## How it was tested, and what that cannot show

No production-like copy of the production graph was available. Everything below ran on **synthetic data** on a
development machine (4 cores, 15 GB RAM), built through the production code path:

- **Data set:** embedded Solr plus the knowledge graph built by the `main` build (schema 6, extractors jsonld 5 / rule 6
  / vocabulary 3) from the synthetic corpus of `KgLoadMeasurement`: 160 000 pages of 5 435 hosts in two collections
  (care vocabulary with the LLM tier, construction vocabulary without), two recrawl rounds of 30 % of the hosts with
  changed content, the LLM tier answered by the measurement's stand-in model (no real model), derived layer.
  Result: 160 000 documents, 36 271 entities, 210 671 statements, 586 698 evidence rows, 157 416 observations,
  `graph.db` 602 MiB.
- **Production for comparison** (from the operator's earlier reports, not measured here): 120 101 documents,
  70 328 entities, 252 337 statements, 607 551 evidence rows, 274 069 observations, `graph.db` 1.44 GiB, of it about
  535 MB `kg_observation`.

The synthetic graph matches production in pages and evidence, not in entities (about half), observations (about 60 %)
and file size (about 40 %): its texts are short and its LLM answers uniform. The following can therefore **not** be
judged from it:

- real page texts, real LLM evidence and real identity conflicts;
- the share of real prices and money amounts that WP-F reclassifies, and the findings of the WP-G review;
- the Olares container: cgroup CPU, the load gate at `maxLoad` 8 on a 24-core host, I/O of the PVC, UID 1000;
- crawling and LLM work running in parallel at production rate;
- physical disk-full;
- runs over several days, and the user interface on real data.

Times on production scale are **estimates** (linear in file size), not measurements.

## Results

### 1. Build and tests from cleaned build directories (local)

- `tools/scoutro/ci/scoutro-checks.sh all` on `e865f31` (`ant clean compile`, every `scoutro-*-test` target, nine
  Python suites): **23/23 passed**; before the last fix also on `00228c2`, 23/23.
- GitHub `Scoutro checks` (java, python) green on #82 `afb609f`, #83 `cae2b46`, #84 `ef46d99`, #85 `5835dcf`, #87
  `e865f31`; unchanged and green on #86 `6df505e`, #78 `9243c41`, #79 `fa74865`, #80 `9e7dc79`, #81 `ed785a9`.

### 2. Integration of the eight work packages (local)

All eight work packages are on #87, including WP-B (#79). New end-to-end test `DrolshagensPilotTest` (in
`scoutro-collection-decisions-test`) crosses A, C, D, E, F and H on one embedded Solr with the graph.

### 3–5. Migration 6 → 7 → 8, preservation, backup, restore, restart and abort (synthetic, production size)

| Step | Measured |
|---|---|
| Upgrade in one start (6 → 8, INT build) | synchronous graph start 18.6 s: quick_check and a verified copy (552 MiB) about 4–5 s, 6 → 7 about 9 s, 7 → 8 about 8 s; WAL up to 250 MiB; RSS 320 MiB |
| Staged as released (R2 build #82, then INT) | 6 → 7: 10.7 s; 7 → 8: 16.8 s, one copy each (two copies of 552 MiB until the next regular backup) |
| Preservation | the upgrade copy equals the pristine file in 35 of 36 tables row by row (SHA-256 over sorted rows); `kg_meta` differs only by the pause key the test set; documents, entities, statements and evidence unchanged after the migration; observations +21 177 (the existing prices enter the price archive) |
| File size | 602 MiB → 707 MiB after the migration (the rebuilt `kg_evidence` leaves free pages) |
| Backup / restore / restart | backup 6.5 s (834 MB), restore 7.4 s, evidence before and after the restore equal (584 218); stop 35 ms, start 46 ms, settled after 14.5 s |
| kill -9 during quick_check, the copy and the migration transaction (at 4.6, 6, 8, 11, 15 and 19 s) | each time the file stayed schema 6, quick_check `ok`, counts unchanged; the next start migrated in about 22 s; the re-extraction order survived the second stop |
| kill -9 during the re-extraction (2k graph) | the pages already queued (1 700) stay in `kg_work`, quick_check `ok` |
| Rollback | the `main` build refuses schema 8 (`schema_unsupported`, the peer runs on without graph); the upgrade copy restored as `graph.db` (2 s) opens with `main` with every count equal to the pristine graph |

### 6. Collection moves, re-evaluation, withdrawal and revocation (synthetic, production size)

The largest host of the construction collection (54 pages) moved to the care collection and back:

- **Moves:** Solr 0.4 s; the graph caught up in 18.5 s and 3.8 s; the category evidence follows the vocabulary.
- **Withdrawal and revocation:** a domain-wide withdrawal of the phone takes 51 ms for the preview and 98 ms to create; the revocation takes 0.33 s; the evidence is 3 → 0 → 3.
- **LLM:** no model call. The page's first enrichment in the LLM collection is covered by `CollectionMoveTest`, not observed at this scale.

### 7. drolshagens.de on isolated test data (local)

`DrolshagensPilotTest`, with the production case reproduced: the construction vocabulary reads "Hersteller" as a topic
on the home page and as category, `construction.handel` and an offered service "Hersteller" on the products page.

- **Correction automation in simulation:** from the website alone it writes nothing and records no case. With the OSM tag against the collection the result is `review` (`contradicting_signals`), never a move.
- **Exact decision:** after the preview and the token, three pages go to `sonstige`; the construction company of the same collection is untouched.
- **Graph re-read without the construction vocabulary:** topic, category, industry and offered service withdrawn; names and the price kept; no model call.
- **Discovery:** refuses `bauteamcheck-web` (`collection_decided`); a recrawl keeps the decision; the manual decision wins over later evaluations.

### 8. One-time rule-based re-read of tiers 1/2 (synthetic, production size)

- **Duration:** all 148 753 queued pages in 1 h 54 min (about 22 pages/s on 4 cores), alongside a recrawl of 10 % of the hosts and continuous queries.
- **Model calls from the re-extraction: 0.** The 4 094 calls of the run came from the recrawled pages; their count stayed flat for the last 85 minutes, while about 120 000 pages were read again.
- **Resources:** RSS up to 1.7 GiB (heap limit 6 GiB); WAL at most 11 MiB; `graph.db` 707 → 796 MiB.
- **Observations +85 % (157 416 → 291 638):** each re-read fact of a new extractor version is a new archived observation. In production `kg_observation` already holds about 535 MB; expect several hundred MB more.
- **Meaning, against the copy before the upgrade (the recrawl included):**
  - active entities −1.0 % (416 gone, 84 new), of them 345 services (categories outside an offer context are now `topic`, 673 new); jobs, facilities and organisations ±30/18/11 from the recrawl;
  - no merge;
  - prices 21 177 → 21 179.
- **WP-G dry run (tier-3 review of stored LLM facts):** 2 551 pages in 0.3 s, no model, nothing written. The synthetic LLM answers give no findings, so the review's effect is **not** judged here.
- **WP-B quality metrics on 160 000 pages:** recomputed in 1.1 s.

### 9. Performance, memory and locks under load (synthetic, production size)

Three reader threads queried continuously during the re-extraction: entities, host entities, sources and status, with
a quality refresh every 30 s. About 50 000 requests each, no error, no lock error (`SQLITE_BUSY`).

| Request | p50 | p95 | p99 | max |
|---|---|---|---|---|
| entities | 91 ms | 189 ms | 232 ms | 628 ms |
| status | 75 ms | 187 ms | 276 ms | 1.2 s |
| host entities | 1 ms | 58 ms | 89 ms | 357 ms |
| source | 1 ms | 59 ms | 93 ms | 618 ms |
| quality refresh | 1.1 s | 1.3 s | 1.5 s | 1.6 s |

### 10. Correction automation in simulation (synthetic, production size)

The correction automation evaluated all 5 435 hosts in `simulate`, with the graph's own signals (structured industries, text fit):

- **Result:** 0 wrong proposals, 0 decisions written, 0 model calls, 0 errors.
- **Review cases:** 30, all hosts with care content in the construction collection.
- **Not detected:** the 2 198 hosts of other trades in the construction collection (dentists, lawyers, bakeries), because an absence alone is no signal and no other vocabulary fits them. The automation is conservative (no wrong move) but finds little without OSM or Discovery signals.
- **Cost:** evaluate p50 136 ms, max 322 ms; the state file (`autocorrect.json`) is rewritten on every evaluation and grew to 2.5 MB at 4 354 cases.
- **`apply`:** stays off; it needs the acceptance of 299 drawn and judged cases (`AutoCorrectTest`, `CollectionDecisionsApiTest`, `DrolshagensPilotTest`).

### 11. Dashboard, API and agent regressions (local)

All in the 23 checks: the dashboard, collection-decisions, knowledge, report, SEO, RAG, LLM security and agent
targets, the agent bridge and MCP adapter suites, and the API flow contract. Live smokes with disposable peers
(knowledge, report, SEO, discovery, dashboard 384/384) last ran on `92d381b`; the fixes since touch only start-up and
upgrade paths.

## Code errors found and fixed

| Error | Branch | Fix | Test |
|---|---|---|---|
| New extractor versions were stored at the start while the order to read every page again lived only in memory: a stop before that reconcile completed (an upgrade whose first start ends early) lost it for good, and the pages kept their old reading. Reproduced on the 2k graph. | #83 WP-F (`d8b62c0`) | the order is stored with the new identity (`kg_meta.reextract_pending`, also when a held upgrade is released) and removed by the completed reconcile | `KgSyncRuntimeTest.aReextractionOrderSurvivesARestartBeforeItCompleted` (red before, green after); 160k: survived two stops |
| A start killed during the copy before a schema upgrade left `backup/graph-*-before-upgrade.db.partial` (up to the graph's size) and its journal until the next regular backup | #82 WP-E (`afb609f`) | the upgrade start removes them (`upgrade.removedPartial`) | `KgUpgradeTest.theNextStartRemovesThePartialCopyOfAnInterruptedUpgrade`; 160k: removed on the restart |

Both fixes were merged up the stack (#82 → #83 → #84 → #85 → #87) by merge commits; the affected suites and then all
23 checks were run again.

## Release rating

| Release | PRs | Tested | Rating | Conditions |
|---|---|---|---|---|
| **R1** | #86, #78, #79, #80 | **locally:** all tests, dashboard smoke, pilot test. **Production-like:** quality metrics on 160k (1.1 s). **Not tested:** real decisions file on Olares | **ready for release** | backup of `DATA/SCOUTRO/collections`; baseline `GET /v1/kg/quality?refresh=true` |
| **R2** | #81, #82 | **locally:** all tests. **Production-like:** 6 → 7 (10.7 s), copy, kill -9, restore, rollback, collection moves, withdrawal and revocation on 160k. **Not tested:** 1.44 GiB file on the Olares PVC | **ready for release** with the fix `afb609f` | free disk of about one graph plus about 0.6 GiB (copy, WAL, rebuilt `kg_evidence`); a start up to about 30 s longer; pilot drolshagens.de with preview only |
| **R3** | #83, #84 | **locally:** all tests. **Production-like:** 7 → 8, re-extraction 160k in 1 h 54 min without a model, load test, WP-G dry run. **Not tested:** real prices and amounts, real LLM facts, the load gate on Olares | **ready for release** with the fix `d8b62c0`, **with conditions** | disk for observations +85 % (production: several hundred MB) and a second upgrade copy; the re-extraction runs for hours in the background behind the load gate; compare the metrics with the R1 baseline; WP-G only as a dry run |
| **R4** | #85, #87 | **locally:** all tests and the pilot. **Production-like:** simulation over 5 435 hosts, 0 wrong. **Not tested:** real OSM, Discovery and LLM signals | **ready for release in `off`/`simulate`** | `apply` is **not** released: it needs 299 judged cases; low detection without external signals |

**Blockers:** none in the code. One **operator decision** comes before R3: the disk reserve for the observation growth, and whether the re-extraction may run in the background for several hours.

## Migration risks and resource needs (estimated for production)

The table scales linearly from 602 MiB to 1.44 GiB, a factor of about 2.4; these are estimates, not measurements.

| What | Synthetic (measured) | Production (estimated) |
|---|---|---|
| Additional graph start R2 / R3 | 10.7 s / 16.8 s | about 25–40 s each (plus the JVM), synchronous at peer start |
| Upgrade copy | 552 MiB each | about 1.4 GiB each; after R2 and R3 two copies until the next regular backup |
| WAL of the migration | up to 250 MiB | up to about 0.6 GiB |
| `graph.db` after the migration | +17 % | about +0.25 GiB |
| Re-extraction | 1 h 54 min, 4 cores | hours, depending on the load gate (`maxLoad` 8) and the CPU share; the queue drains in the background |
| Observations after the re-extraction | +85 % | several hundred MB more in `kg_observation` (largest unknown) |
| Disk peak R3 in total | — | about 2.5–3.5 GiB above today's graph (copy, WAL, growth), well within the KG budget of 10 GiB; check the host's free space |

Further risks:

- **The whole migration is one write transaction at start:** an abort rolls back completely (tested). During it the graph is unavailable; the peer itself runs.
- **Duplicate copies:** every aborted upgrade attempt leaves a complete, verified copy; only the next regular backup keeps just the newest one. In a crash loop each attempt costs one graph size; without room for a copy the guard holds the re-extraction (`upgrade_hold`), it does not fill the disk.
- **Time limits:** the quick check (`scoutro.kg.integrity.maxMillis`, 120 s) and the copy (`scoutro.kg.backup.maxMillis`, 600 s) are generous for 1.44 GiB; at about 4 s per 0.6 GB here, the slower I/O of the PVC is not measured.
- **Rollback:** no older build opens schema 7 or 8. Rollback means restoring the upgrade copy or the full backup with the previous image; corrections made since then are lost.

## Prepared merge and rollout order (not executed)

This is unchanged from [SCOUTRO_DATA_QUALITY_ROLLOUT.md](SCOUTRO_DATA_QUALITY_ROLLOUT.md), with this paper's conditions added:

1. **Merge order:** #86, #78, #79, #80, #81, #82, #83, #84, #85, #87, each with a merge commit.
2. **One release per step**, each with its own image digest, a verified full backup and an isolated restore before it, and a check afterwards (health, schema, integrity, queue progress over 15 minutes, LLM requests).
3. **R1** first: take the quality baseline.
4. **R2**: free disk of one graph size plus 0.6 GiB; pilot drolshagens.de with preview only.
5. **R3**: disk reserve for the observation growth; run the re-extraction in the background behind the load gate; compare the metrics; WP-G only as a dry run.
6. **R4**: keep the mode `off` or `simulate`; `apply` only after a separate decision with 299 judged cases.

Every step is a separate decision by the operator. Passing tests is no approval.
