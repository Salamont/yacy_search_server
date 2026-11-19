# Scoutro 0.14.3-rc.6: test candidate of the job and provider search by the indexes first

Candidate versions: **0.14.3-rc.6 / 1.942-scoutro.52-rc.6**, built for an acceptance on Olares. **Not a release:** no git
tag; a later release gets its own number. Numeric YaCy peer version **1.942** unchanged.

| What | Value |
|---|---|
| Change on 0.14.3-rc.5 | (1) the job and provider search ask the existing indexes for the matching IDs and their reason first (postings: title, facts, the text of their own page; providers: service and place of the same provider), count unique hits before the selection and load evidence only for the hits shown; the graph's evidence query in a fixed join order (`docs/SCOUTRO_RAG_QUALITY.md`, sections 12 to 14); (2) a plain count question is answered by the server without a model request (`scoutro.chat.count.direct`, default on); lists and recommendations get only the hits shown as sources; (3) a tool call for the same search in the same request reuses its result (`reused`); (4) a provider whose pages naming the place were not all read (120 providers, 3 pages each, or the time budget) is open (`undecided`), not a mere mention: the total stays complete, located and serving are then lower bounds (`placeGaps`, `placeComplete`); (5) the model requests of an answer and their tokens in `scoutro-usage` (reported by the endpoint, else marked as an estimate; `ai.stream.usage`, default off) |
| Application code | **`82997f89183fecd3142a30b46b04e860f1d683f3`** on 0.14.3-rc.5 (`4a0fb52`); `main` holds the state up to `2dc2491d0` (merge `0dbc5af`) |
| Build commit | the commit on `82997f8` of the same branch: version metadata, these notes, the changelog and the publication workflow only; `git diff --name-only 82997f8 <build commit>` names no source, resource, build or test file |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.52-rc.6`, alias `0.14.3-rc.6`, linux/amd64, published by a manual dispatch of `publish-scoutro.yml` with `expected_sha` = the build commit; existing tags are refused, never overwritten |
| Data | knowledge graph schema 9, Discovery job store 1, correction state 1, Solr fields: unchanged. Extractors unchanged, so the update starts no re-extraction and no replay. No setting is written |
| Tools | the releases stay (`ai.tools.<name>.enabled` in `DATA/SETTINGS/yacy.conf` is kept); `provider_search` returns `place_complete` and `place_gaps` beside `lower_bound` |
| Way back | 0.14.3-rc.5, the installed state (`1.942-scoutro.52-rc.5@sha256:8434f515…`), on the same DATA: nothing to restore |

Tests of the application code `82997f8` (synthetic data, simulated model), as they ran:
`tools/scoutro/ci/scoutro-checks.sh all`: **all 25 targets passed, no repetition** ("ALL SCOUTRO CHECKS PASSED"); an
earlier start of the same run was cut off by a restart of the build container after two targets and is not counted.
`ant scoutro-rag-test`: 85 OK (new: 135 providers of whom 133 name the place, 120 read: located 1, serving 1, open 14,
mentioned 117, none 2; the server's count answer and `provider_search` with and without "in" and with reuse; the time
budget in the place step leaves the count complete). Chat endpoint smokes, each in its first run:
`chat-servicesearch-live-smoke.py` 56/56 (section 9: the same 135 providers at the chat endpoint, the count answered by
the server with no model request, `provider_search` with and without "in", the tool's reuse in one request with the same
providers in the same order), `chat-jobsearch-live-smoke.py --ui` 74/74 and 22 UI checks, `chat-tools-live-smoke.py --ui`
54/54 and 28 UI checks, `chat-rag-live-smoke.py` 54/54. `SearchScaleBench check` on the invented stock of production size
(6,851 postings, 246,147 pages): 15/15 after the caches were warm; its first run right after a restart of the machine
reported 23 of 229 postings as a lower bound with the reason "time budget" (the cold Solr text step took 3.2 s), which is
the intended behaviour, not a wrong count.

A real language model was not part of the tests.
