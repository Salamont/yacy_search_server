# Scoutro 0.14.3-rc.3: test candidate of the follow-up search and the job selection

Candidate versions: **0.14.3-rc.3 / 1.942-scoutro.52-rc.3**, built for an acceptance on Olares. **Not a release:** no git
tag, no merge; a later release gets its own number. Numeric YaCy peer version **1.942** unchanged.

| What | Value |
|---|---|
| Change on 0.14.3-rc.2 | a follow-up such as "Such noch mal" that names no search mode searches the local index with the goal of the chat (a switched-off magnifier sends search "no", which is respected); the job search shares its candidate places among the subjects of a question, merges and deduplicates the hits, sorts them by relevance and shows the most relevant posting of every subject that has one (`docs/SCOUTRO_RAG_QUALITY.md`, section 12) |
| Application code | **`e43e0ba3491b2e17581e57650aac628642151241`** on 0.14.3-rc.2 (`ce714e8`): `e013509` (follow-up search mode) and `e43e0ba` (job selection) |
| Build commit | the single commit on `e43e0ba` of the same branch: version metadata, these notes, the changelog and the publication workflow only; `git diff --name-only e43e0ba <build commit>` names no source, resource, build or test file |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.52-rc.3`, alias `0.14.3-rc.3`, linux/amd64, published by a manual dispatch of `publish-scoutro.yml` with `expected_sha` = the build commit; existing tags are refused, never overwritten |
| Data | knowledge graph schema 9, Discovery job store 1, correction state 1, Solr fields: unchanged. Extractors unchanged, so the update starts no re-extraction and no replay. No setting is written |
| Tools | the releases of rc.2 stay (`ai.tools.<name>.enabled` in `DATA/SETTINGS/yacy.conf` is kept); limits of the job search unchanged: 6 name searches, 12 candidates, 6 postings, 4 s |
| Way back | 0.14.3-rc.2 (`1.942-scoutro.52-rc.2@sha256:89d7f2ed…`) on the same DATA: nothing to restore |

Tests of the application code (synthetic data, simulated model): `tools/scoutro/ci/scoutro-checks.sh all`,
`ant scoutro-rag-test`, `test/scoutro-access/chat-jobsearch-live-smoke.py --ui` (with the follow-up browser test),
`chat-tools-live-smoke.py --ui`. A real language model was not part of them.
