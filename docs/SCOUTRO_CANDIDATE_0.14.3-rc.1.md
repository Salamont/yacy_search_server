# Scoutro 0.14.3-rc.1: test candidate of the chat tools

Candidate versions: **0.14.3-rc.1 / 1.942-scoutro.52-rc.1**, built for an acceptance on a separate test instance. **Not a
release:** no git tag, no merge; a later release gets its own number. Numeric YaCy peer version **1.942** unchanged.

| What | Value |
|---|---|
| Change | the chat's tools: `search` within the chat's scope, `kg_search`, `kg_entity`, `kg_evidence`, tool pages as numbered sources and the support verdict of the citation check (`docs/SCOUTRO_RAG_QUALITY.md`, sections 6 and 11) |
| Application code | **`71fb2b8d07ca73d7afd9d1f36c7c849e4573b144`** on 0.14.2 (`201a6a6`) |
| Build commit | the single commit on `71fb2b8` of the same branch: version metadata, these notes, the changelog and the publication workflow only; `git diff --name-only 71fb2b8 <build commit>` names no source, resource, build or test file |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.52-rc.1`, alias `0.14.3-rc.1`, linux/amd64, published by a manual dispatch of `publish-scoutro.yml` with `expected_sha` = the build commit; existing tags are refused, never overwritten |
| Data | knowledge graph schema 9, Discovery job store 1, correction state 1, Solr fields: unchanged. Extractors `rule/12`, `llm/8`, `jsonld/8`, `metadata/1`: unchanged, so the update starts no re-extraction and no replay. No setting is written |
| Tools | off until released: `ai.tools.search.enabled`, `ai.tools.kg_search.enabled`, `ai.tools.kg_entity.enabled`, `ai.tools.kg_evidence.enabled` (Tools page). Without them the chat behaves as in 0.14.2, except that the citation check also reports `support` |
| Way back | 0.14.2 (`1.942-scoutro.51@sha256:464d19e0…`) on the same DATA: nothing to restore, the candidate writes nothing the old version does not read |

Tests of the application code (synthetic data, simulated model): `tools/scoutro/ci/scoutro-checks.sh all`,
`test/scoutro-access/chat-tools-live-smoke.py --ui`, `test/scoutro-access/chat-rag-live-smoke.py`. A real language model
was not part of them.
