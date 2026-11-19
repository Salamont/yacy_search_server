# Scoutro 0.14.3-rc.5: test candidate with three corrections of the job and provider search

Candidate versions: **0.14.3-rc.5 / 1.942-scoutro.52-rc.5**, built for an acceptance on Olares. **Not a release:** no git
tag, no merge; a later release gets its own number. Numeric YaCy peer version **1.942** unchanged.

| What | Value |
|---|---|
| Change on 0.14.3-rc.4 | (1) a count question has no subject of its own: "Wie viele Stellenanzeigen zu X gibt es?" and "Zeige alle Stellenanzeigen zu X" search X only and count the same unique postings; (2) `provider_search` and `job_search` fit their result into the tool room of the answer (texts shortened first, then entries left out; totals, place categories and completeness kept; only the entries given become sources); (3) `provider_search` takes the place without "in" ("Tragwerksplanung Musterstadt" = "Tragwerksplanung in Musterstadt", also places of several words) and an optional argument `place` (`docs/SCOUTRO_RAG_QUALITY.md`, sections 12 and 13) |
| Application code | **`5f438c985f5bd9a60c57e2bf9f8da40df8d8a20c`** on 0.14.3-rc.4 (`b98aa22`, search code `3ffcfce`) |
| Build commit | the single commit on `5f438c9` of the same branch: version metadata, these notes, the changelog and the publication workflow only; `git diff --name-only 5f438c9 <build commit>` names no source, resource, build or test file |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.52-rc.5`, alias `0.14.3-rc.5`, linux/amd64, published by a manual dispatch of `publish-scoutro.yml` with `expected_sha` = the build commit; existing tags are refused, never overwritten |
| Data | knowledge graph schema 9, Discovery job store 1, correction state 1, Solr fields: unchanged. Extractors unchanged, so the update starts no re-extraction and no replay. No setting is written |
| Tools | the releases stay (`ai.tools.<name>.enabled` in `DATA/SETTINGS/yacy.conf` is kept); `provider_search` keeps `query` as its only required argument |
| Way back | 0.14.3-rc.3, the installed state (`1.942-scoutro.52-rc.3@sha256:cb609f54…`), on the same DATA: nothing to restore |

Tests of the application code `5f438c9` (synthetic data, simulated model): `ant scoutro-rag-test` (76, 5 new);
`chat-jobsearch-live-smoke.py --ui` (72 and 18 UI; section 8: the count question, "Zeige alle" and `job_search` count the
same 14 postings among postings with "viele", "vielen" and "vielseitige"); `chat-servicesearch-live-smoke.py` (45;
section 7: the tool with and without "in" and with `place`, a place of several words, no place, long pages within the
room of a 4,096-token model, only the providers given as sources), `chat-tools-live-smoke.py --ui` (54 and 28 UI),
`chat-rag-live-smoke.py` (54). The provider smoke passed in its third run: the first had a wrong check of the source
numbers (a provider whose page is already a source keeps its number) and two failures of the place of several words, the
second ran beside the job smoke and asked before the knowledge graph had read the offices' addresses (the smoke now waits
for them). `tools/scoutro/ci/scoutro-checks.sh all`: **all 25 targets passed in the first run, no repetition**
("ALL SCOUTRO CHECKS PASSED"). A real language model was not part of the tests.
