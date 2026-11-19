# Scoutro 0.14.3-rc.4: test candidate of the job and provider search over the whole released stock

Candidate versions: **0.14.3-rc.4 / 1.942-scoutro.52-rc.4**, built for an acceptance on Olares. **Not a release:** no git
tag, no merge; a later release gets its own number. Numeric YaCy peer version **1.942** unchanged.

| What | Value |
|---|---|
| Change on 0.14.3-rc.3 | the job search reads every posting of the request's scope to the end of the stock and asks the text of every posting's own page through the direct index; the provider search determines its providers in the graph and in every page of the index that names the service, counts each once and tells a location, a service area and a mere mention of the place apart; questions for all results or their number run the full search; found and shown reported, a lower bound with its reason (`docs/SCOUTRO_RAG_QUALITY.md`, sections 12 and 13) |
| Application code | **`3ffcfce262868609aac84e9e01a4f9a1d187feaa`** on 0.14.3-rc.3 (`336db26`): `c28c1ca` (the whole stock of postings) and `3ffcfce` (direct index, provider search, all/count questions) |
| Build commit | the single commit on `3ffcfce` of the same branch: version metadata, these notes, the changelog and the publication workflow only; `git diff --name-only 3ffcfce <build commit>` names no source, resource, build or test file |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.52-rc.4`, alias `0.14.3-rc.4`, linux/amd64, published by a manual dispatch of `publish-scoutro.yml` with `expected_sha` = the build commit; existing tags are refused, never overwritten |
| Data | knowledge graph schema 9, Discovery job store 1, correction state 1, Solr fields: unchanged. Extractors unchanged, so the update starts no re-extraction and no replay. No setting is written; the new searches only read (the graph's scope tables, Solr) |
| Tools | the releases of rc.3 stay (`ai.tools.<name>.enabled` in `DATA/SETTINGS/yacy.conf` is kept) |
| Way back | 0.14.3-rc.3 (`1.942-scoutro.52-rc.3@sha256:cb609f54…`) on the same DATA: nothing to restore |

Tests of the application code `3ffcfce` (synthetic data, simulated model): `ant scoutro-rag-test` (71), live smokes
`chat-jobsearch-live-smoke.py --ui` (66 and 18 UI), `chat-servicesearch-live-smoke.py` (32), `chat-tools-live-smoke.py --ui`
(54 and 28 UI), `chat-rag-live-smoke.py` (54). `tools/scoutro/ci/scoutro-checks.sh all`: in the first run 24 targets
passed and `scoutro-agents-test` failed in `KgRebuildTest` (a rebuild stopped with `read_timeout` under load; the class
does not touch the changed code); `ant scoutro-agents-test` repeated alone: passed, `KgRebuildTest` 10/10. A complete
green run of all targets in one go was not made. A real language model was not part of the tests.
