# Scoutro 0.14.3-rc.2: test candidate of the chat's job and provider search

Candidate versions: **0.14.3-rc.2 / 1.942-scoutro.52-rc.2**, built for an acceptance on Olares. **Not a release:** no git
tag, no merge; a later release gets its own number. Numeric YaCy peer version **1.942** unchanged.

| What | Value |
|---|---|
| Change on 0.14.3-rc.1 | a weakly answered job question gets the job postings of the knowledge graph (`type=job`) before the answer, a provider question a local search for the derived service and place; a follow-up such as "Such noch mal" keeps the goal of the chat; named tools `job_search` and `provider_search` with descriptions on the Tools page (`docs/SCOUTRO_RAG_QUALITY.md`, sections 12 and 13) |
| Application code | **`16f2c8ec500467accc37bda5e84575c072993fea`** on 0.14.3-rc.1 (`e596086`) |
| Build commit | the single commit on `16f2c8ec` of the same branch: version metadata, these notes, the changelog and the publication workflow only; `git diff --name-only 16f2c8ec <build commit>` names no source, resource, build or test file |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.52-rc.2`, alias `0.14.3-rc.2`, linux/amd64, published by a manual dispatch of `publish-scoutro.yml` with `expected_sha` = the build commit; existing tags are refused, never overwritten |
| Data | knowledge graph schema 9, Discovery job store 1, correction state 1, Solr fields: unchanged. Extractors unchanged, so the update starts no re-extraction and no replay. No setting is written |
| Tools | the release of rc.1 stays: `ai.tools.<name>.enabled` in `DATA/SETTINGS/yacy.conf` is kept. `job_search` follows `kg_search`, `provider_search` follows `search` while not set on their own (saving the Tools page gives them their own setting). New optional setting `scoutro.chat.jobs.subjects` (synonym groups of the job search), empty by default |
| Way back | 0.14.3-rc.1 (`1.942-scoutro.52-rc.1@sha256:d3f255ff…`) or 0.14.2 on the same DATA: nothing to restore; the candidate writes nothing the old versions do not read (an unknown `ai.tools.job_search.enabled` or `scoutro.chat.jobs.subjects` is ignored by them) |

Tests of the application code (synthetic data, simulated model): `tools/scoutro/ci/scoutro-checks.sh all`,
`test/scoutro-access/chat-jobsearch-live-smoke.py`, `chat-servicesearch-live-smoke.py`, `chat-rag-live-smoke.py`,
`chat-tools-live-smoke.py --ui`. A real language model was not part of them.
