# Scoutro 0.11.4 release candidate (R1–R5 in one update from 0.10.9)

Prepared versions: **0.11.4 / 1.942-scoutro.38**. Numeric YaCy peer version **1.942** stays unchanged. **Not published, no registry digest, no rollout.** Each step is a separate decision of the operator.

| What | Value |
|---|---|
| Release | the data quality programme R1–R4 and website technologies R5, released together |
| Source commit on `main` | `eac0d2e972950e00a89c6e21ea1ae80bb2eabf68` (merge commit of #89) |
| Release commit | this commit: version files and release notes only, on top of the source commit |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.38`, alias `0.11.4`, linux/amd64 |
| Registry digest | **open** until publication |
| Knowledge graph schema | 6 -> 7 -> 8 -> 9 at one start |
| Upgrade from | 0.10.9 / `1.942-scoutro.33` (`sha256:0a3dc2057e04aa164fc038171df2615e0a86cf47c3ce60717e8ab47c81e0c5a9`) |
| Not released | 0.11.0–0.11.3 / `1.942-scoutro.34`–`.37`: neither published nor installed; their contents are part of this release |

## Contents

- R1: durable collection decisions (WP-A, #78), quality metrics of the knowledge graph (WP-B, #79), Discovery follows the decisions (WP-C, #80), CI for this fork (#86).
- R2: the knowledge graph follows collection moves (WP-D, #81); assessments of graph facts (WP-E, #82; schema 7).
- R3: extraction semantics, money by meaning and product roles (WP-F, #83; schema 8, one rule-based re-read); rule-based tier-3 review as a dry run (WP-G, #84).
- R4: collection autocorrect, **off by default** (WP-H, #85); integrated quality metrics (#87).
- R5: website technologies from the page markup (#89; schema 9), switch `scoutro.kg.technology.enabled` (default on). Details: [SCOUTRO_WEBSITE_TECHNOLOGY.md](SCOUTRO_WEBSITE_TECHNOLOGY.md).
- Fixes from the acceptance: the order to read every page again survives a restart (`kg_meta.reextract_pending`), and the partial copy of an interrupted upgrade is removed at the next start.

## Upgrade contract

1. **One start, three migrations, one copy.** At the first start the graph is checked (`quick_check`), copied once to `DATA/SCOUTRO/knowledge/backup/graph-<UTC>-before-upgrade.db` (schema 6, verified) and migrated 6 -> 7 -> 8 -> 9 synchronously. Documents, entities, statements, evidence and entity keys stay; observations grow by the existing prices entering the price archive. Measured on a synthetic graph of production size: the graph start took 19.9 s (check and copy of 552 MiB about 5 s, 6 -> 7 about 8 s, 7 -> 8 about 8 s, 8 -> 9 under 1 s), the file grew from 602 MiB to 707 MiB, observations +21 177, container memory 1.04 GiB. Production estimate (graph 1.44 GiB): under 2 minutes plus the JVM; the peer runs meanwhile, the graph is unavailable.
2. **Then one re-read without a model.** Every page is read again once by the rules (tiers 1/2) at low priority behind the load gate (`scoutro.kg.gate.maxLoad` as installed). The order is kept in `kg_meta.reextract_pending` and survives restarts and a hard stop. Synthetic: 160 000 pages in about 1 h 42 min on 4 cores under read load, including a hard stop (`kill -9`) after 59 min: the queue survived, `quick_check` stayed ok and the next start took the remaining 68 849 pages in 43 min. Afterwards every tier-1/2 evidence comes from the current extractors (`jsonld/6`, `rule/7`); tier-3 (LLM) evidence is unchanged; statements -0.3 %, entities -0.8 % and evidence -0.4 % (rule facts the current rules no longer support), observations +85 % (prices and amounts archived); file 707 -> 843 MiB, WAL at most 9 MB. Production: hours. No LLM request is caused by the upgrade.
3. **Protective configuration in `DATA/SETTINGS/yacy.conf`, set under 0.10.9 before the update** (0.10.9 keeps unknown keys): `scoutro.kg.technology.enabled=false`; `scoutro.collections.autocorrect.mode` off (or missing); `scoutro.kg.identity.productUrl` and `scoutro.kg.identity.providerServiceKey` false (or missing); `scoutro.kg.gate.maxLoad` as installed. No WP-G `apply`, no additional LLM runs.
4. **Before the update:** a consistent full DATA and configuration backup with checksums and an isolated restore with the 0.10.9 image; a knowledge graph backup (`POST /scoutro/api/v1/kg/control {"action":"backup"}`) kept outside the app. Optionally pause the graph under 0.10.9 (`{"action":"pause"}`, persisted): 0.11.4 then migrates but does not start the re-read until `{"action":"resume"}`, which allows a quality baseline (`GET /scoutro/api/v1/kg/quality?refresh=true`) on the migrated graph first.
5. **Disk and knowledge graph budget.** Synthetic: one copy of 552 MiB, graph 602 -> 843 MiB. Production estimate: copy about 1.3 GiB, graph 2.0-2.5 GiB afterwards; at least 4 GiB free on DATA for the update, 7 GiB including the next regular backup. The knowledge graph budget (`scoutro.kg.budget.maxBytes`, default 10 GiB, growth pauses at 90 %) counts the graph, WAL and every backup: production estimate about 5 GiB after the update (graph, one before-upgrade copy, the regular backup from before the update), about 7.5 GiB while the next regular backup is written, then about 6 GiB, so within the default budget. A restore through the app adds a before-restore copy of the graph. Before switching website technologies on (+0.5-1.6 GiB in the graph and as much in the backup), raise the budget to 16 GiB (with that much room on the volume) or remove the before-upgrade copy deliberately once the update is verified.

## Checks after the start

- Health `GET /scoutro/api/v1/health`: `scoutro` `1.942-scoutro.38`.
- `GET /scoutro/api/v1/kg/status`: `store.schemaVersion` 9; `upgrade` `from` 6, `to` 9, `quickCheck` ok, one `backup`; `GET /scoutro/api/v1/kg/backups`: exactly one item of `kind` before_upgrade with `kg_schema_version` 6.
- `grep '^scoutro.kg.technology.enabled' DATA/SETTINGS/yacy.conf` gives `=false`; `GET /scoutro/api/v1/collections/autocorrect`: `settings.mode` off.
- While the re-read runs: `sync.lag.reconcile_pending` true and falling `sync.lag`; `llm.dailyRequests.today` unchanged. Afterwards `reconcile_pending` false; no `website_technology` statement while the switch is off. The index field `website_technology_sxt` is still written for new pages (documented; the graph ignores it while off).
- 15 minutes of queue progress; LLM requests only for new content.

## Rollback to 0.10.9

0.10.9 does not open schema 9 (`unavailable` / `schema_unsupported`; the peer runs on without the graph). Either

- stop, move `graph.db*` aside, restore the before-upgrade copy (schema 6) as `graph.db`, start the 0.10.9 image by digest: the graph is the one of the update moment; pages crawled since are read again from the index. The Solr index written by 0.11.4 (with the optional field) opens under 0.10.9; or
- restore the full DATA backup: bit-identical graph; a pause set before the backup is still set (`{"action":"resume"}`).

Everything learned since the update (re-read, assessments, decisions) is gone; export assessments first (`GET /scoutro/api/v1/kg/assessments?status=all`). No lossless downgrade is promised. Keep newer DATA separately before restoring.

## References

- This release replaces the staged order "one release per step" of [SCOUTRO_DATA_QUALITY_ROLLOUT.md](SCOUTRO_DATA_QUALITY_ROLLOUT.md) by one update; the three migrations in one start, the re-read, its hard stop and the rollback to 0.10.9 were tested with the real images on disposable data (synthetic production size and a real peer).
- [Merge and rollout order](SCOUTRO_DATA_QUALITY_ROLLOUT.md), [acceptance](SCOUTRO_DATA_QUALITY_ACCEPTANCE.md)
- Previous release notes: [0.10.9](SCOUTRO_RELEASE_0.10.9.md)
