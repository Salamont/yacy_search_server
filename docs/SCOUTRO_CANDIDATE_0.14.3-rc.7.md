# Scoutro 0.14.3-rc.7: test candidate of the hit attribution of the job and provider search

Candidate versions: **0.14.3-rc.7 / 1.942-scoutro.52-rc.7**, built for an acceptance on Olares. **Not a release:** no git
tag; a later release gets its own number. Numeric YaCy peer version **1.942** unchanged.

| What | Value |
|---|---|
| Change on 0.14.3-rc.6 | A hit belongs to what it is evidenced for (`docs/SCOUTRO_RAG_QUALITY.md`, sections 12 and 13): (1) job search, text: a text hit counts for the posting whose part of the page shows the subject; several postings of one address stay apart, the same posting on two addresses counts once, a hit of a page that belongs to no posting is reported apart (`pageOnly`) and raises no count; (2) job search, employer: "Stellenanzeigen von X" (and the argument `employer` of `job_search`) counts the postings the graph records X as the employer of, a posting that only names X is a mention (`mentions`); "Stellen zu X" stays the search by subject; (3) provider search, place: service and place by the same provider's identity; an address on a provider's site is its location only when it is its own, not a listed company's, a plot's, a project's or a group company's, and what cannot be attributed is open; (4) two companies on one domain: a provider known by pages of its host that name the service is local only when its address or service area is evidenced for the same company (the pages naming the service are asked whom they speak for); (5) the answer's metadata lists the whole result for a comparison with another count of the same stock (`ids`, `idsComplete`, `pageOnlyPages` in `scoutro-jobsearch`; `placeHosts` in `scoutro-servicesearch`); the model is not given these lists |
| Application code | **`bb6a9cc1534b2fa3f3910b2e221f6ae1db614bac`** on 0.14.3-rc.6 (`26b4cab80`): the commits `134209bd5`, `40f8a14fa` and `bb6a9cc15` |
| Build commit | the commit on `bb6a9cc` of the same branch: version metadata, these notes, the changelog and the publication workflow only; `git diff --name-only bb6a9cc <build commit>` names no source, resource or build file and no test but the default version of `test/scoutro-release/check-attribution.py` |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.52-rc.7`, alias `0.14.3-rc.7`, linux/amd64, published by a manual dispatch of `publish-scoutro.yml` with `expected_sha` = the build commit; existing tags are refused, never overwritten |
| Data | knowledge graph schema 9, Discovery job store 1, correction state 1, Solr fields: unchanged. Extractors unchanged, so the update starts no re-extraction and no replay. No setting is written; the searches only read (new read-only graph queries) |
| Tools | the releases stay (`ai.tools.<name>.enabled` in `DATA/SETTINGS/yacy.conf` is kept); `job_search` takes an optional `employer` and returns `mentions_not_counted` and `pages_not_attributed`; `provider_search` returns `place_source` and `place_note` |
| Way back | 0.14.3-rc.6, the installed state (`1.942-scoutro.52-rc.6@sha256:7c870f68…`), on the same DATA: nothing to restore |

Tests of the application code `bb6a9cc` (synthetic data, simulated model), as they ran:
`tools/scoutro/ci/scoutro-checks.sh all`: **all 25 targets passed in its first run, no repetition** ("ALL SCOUTRO CHECKS
PASSED"). `ant scoutro-rag-test`: 89 OK (new against rc.6: a text hit belongs to the posting of its part of the page;
the postings of an employer are those the graph records it for; the place of a provider is its own address or area;
two companies on one domain are not one local provider, with six such combinations and four sites that stay local).
Chat endpoint smokes, each in its first run on this commit: `chat-servicesearch-live-smoke.py` 66/66 (section 10: service
and place by the same provider's identity; section 11: two companies on one domain in the server's count answer, in what
a recommendation is given and in `provider_search` with and without "in"), `chat-jobsearch-live-smoke.py --ui` 84/84 and
22 UI checks (section 9: employer and text attribution, both count questions answered by the server without a model
request), `chat-tools-live-smoke.py --ui` 54/54 and 28 UI checks, `chat-rag-live-smoke.py` 54/54. `SearchScaleBench
check` on the invented stock of production size (7,331 postings, 246,207 pages, of them 60 pages of eight postings
each): 17/17.

Measured on that stock (each run a fresh process, two rounds): the searches of rc.6 take the same time as before within
the noise (postings of one collection 0.44 to 0.54 s warm, of all collections 0.66 to 0.84 s; providers 0.27 to 0.39 s),
with the same counts. A subject that stands in the text of 60 pages of several postings only: rc.6 counted 60 postings
(the first of each page, also on the five pages that name it in their head only) in 0.57 to 0.59 s warm; the candidate
reads the 60 pages (1.2 million characters) and counts the 110 postings whose parts show it and 5 pages apart, in 0.70
to 0.83 s warm and 1.2 to 1.6 s with cold caches, within the unchanged budget of 6 s.

Known limits: a page naming a service without a company's name speaks for its site, also on a site of several
companies; a company is recognised by its legal form; the title of a posting must stand in its page's text for a part
of its own; at most 60 pages of several postings are read per subject (more: a lower bound with its reason).

A real language model was not part of the tests.
