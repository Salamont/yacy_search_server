# Scoutro 0.11.5 correction release (one update from 0.11.4)

Prepared versions: **0.11.5 / 1.942-scoutro.39**. Numeric YaCy peer version **1.942** stays unchanged. One correction release on the published 0.11.4; each step after the merge is a separate decision of the operator.

| What | Value |
|---|---|
| Release | corrections of the production findings of 19 October 2026: Discovery dead end, knowledge graph names and brands, Anthropic service, phone UI and German texts |
| Source commit on `main` | `8b8948958ab79b3ad8b2ddffd369f2fbd782db0e` (0.11.4, merge commit of #90) |
| Correction commits | `94d249269` (Anthropic service, #88), `143fc1cd2` (Discovery), `79495d174` (knowledge graph), `c008eaeb2` (Anthropic parameters and errors), `b291d88ec` (phone UI, German texts) |
| Release commit | this commit: version files and release notes only |
| Image | `ghcr.io/salamont/scoutro:1.942-scoutro.39`, alias `0.11.5`, linux/amd64 |
| Registry digest | **open** until publication (the publish workflow refuses existing tags; `1.942-scoutro.39` and `0.11.5` are free) |
| Knowledge graph schema | **9, unchanged**; no migration, no copy, no re-read |
| Discovery job store | schema **1, unchanged**; the file is read as it is |
| Solr | fields and collections unchanged |
| Upgrade from | 0.11.4 / `1.942-scoutro.38` (`sha256:45f790b304088f195da485c952fe180b70836dfb9ec371d2a5f0e81d357bcbae`) |

## Contents

- **Discovery** ([SCOUTRO_DISCOVERY_AUTOMATION_V1.md](SCOUTRO_DISCOVERY_AUTOMATION_V1.md) §16.2, [help](../help/ScoutroDiscovery_p.md)): an unconfirmed crawl start no longer blocks every job forever. After two reconciliations without a crawl profile and 20 minutes the coordinator defers it itself through the same path as an administrator (profiles read again, candidate held first, decision by `system:reconcile`, `automatic=true`), the batch completes and the next job runs. A removed profile of an accepted crawl ends the batch with the result unknown (`outcome=profile_removed`); a deferral waits a bounded 20 s for a running status refresh instead of refusing with `worker_busy`; `resume` and `enable` wake the coordinator at once; the Python hold checks the collection of its own candidate only. Never a resubmission; the four candidates held in production (accsicherheitstechnik.de, sonjasturm.de, tarienna.eu, codecamp-n.com) stay held and are never started again. The UI tells automatic from manual deferrals and labels "Defer start (no new crawl, no retry, outcome stays unknown)". Remaining limit: one global batch; a long accepted crawl still delays the other jobs until it ends.
- **Knowledge graph** ([SCOUTRO_KNOWLEDGE_GRAPH.md](SCOUTRO_KNOWLEDGE_GRAPH.md) §35, [help](../help/ScoutroKnowledge_p.md)): for pages read from now on, navigation labels ("Bildergalerie", "Showroom"), kinds of organisations ("Banken") and plural nouns are no organisations in a list, a list on lines keeps only marked brands without a legal name, the role word "Hersteller" is a topic and never an offered service, a JSON-LD brand or maker is never the operator by its own `url`, gets no social profiles and one identity per host, and a dealer's listed maker supplies the dealer instead of being its brand. The read side labels every organisation with its role (operator, brand, manufacturer, no proper name), lists brands apart from partners, hides relations to generic names (counted) and shows `supported` as "stated by sources". **Stored errors stay physically in the graph** until their pages are crawled again; nothing is deleted, retracted, merged or re-read at the upgrade. `jsonld/6`, `rule/7`, `vocabulary/4`, the vocabulary hash and `RELATION_RULES=5` are unchanged, so no `reextract_pending` is set.
- **Anthropic** ([help](../help/LLMSelection_p.md)): `ANTHROPIC` as its own service on `https://api.anthropic.com` (OpenAI-compatible Chat Completions, `anthropic-version`, optional workspace header, Bearer key), model list through the admin proxy, chat, streaming, RAG and knowledge extraction with the existing validator. Requests carry no `temperature`, `num_ctx`, llama `stop` tokens, `reasoning_effort` or `enable_thinking`. Endpoint errors are shown by kind (auth, quota, rate limit with `Retry-After`, overloaded) without the key; the transport never retries; the LLM breaker pauses at once on a refused key or spent credits. **Nothing is activated**: no key is stored, no model row changes, the paused LLM queue stays paused, no paid call is made by the upgrade.
- **Phone UI and German texts**: measured node names in the network diagram (two lines on a phone), folding sections and filters, pages of 25, readable quotes and stats, fieldsets inside the screen; the LLM selection page shows local-service hints only for Ollama or LM Studio, no delete button for hosted services, and its script texts in German.

## Upgrade contract

1. **One start, no migration.** 0.11.5 opens the schema-9 graph as it is: no `quick_check` copy, no before-upgrade backup, no `reextract_pending`, no re-read, no model call. Existing statements, entities, evidence, keys, observations, assessments and decisions are unchanged. The Discovery job store (`DATA/SETTINGS/scoutro-discovery-jobs.json`, schema 1) is read unchanged; held attempts and the queue of jobs stay; the automation stays as configured (disabled stays disabled).
2. **Configuration unchanged.** Protective settings from 0.11.4 keep their values: `scoutro.kg.technology.enabled=false`, `scoutro.collections.autocorrect.mode=off`, `scoutro.kg.identity.productUrl=false`, `scoutro.kg.identity.providerServiceKey=false`. `ai.production_models`, `ai.service_num_ctx`, `ai.anthropic.workspace_id` (empty) and the knowledge graph's manual pause are read as stored.
3. **Before the update:** the regular DATA backup from before the update is sufficient (no new backup on Olares is needed for this release because no data changes). No knowledge graph backup through the app is required; one may be taken.
4. **Disk:** no growth from the upgrade itself (no copy, no re-read). The image is the same size class as 0.11.4.

## Checks after the start

- Health `GET /scoutro/api/v1/health`: `scoutro` `1.942-scoutro.39`.
- `GET /scoutro/api/v1/kg/status`: `store.schemaVersion` 9, no `upgrade` of this start, `sync.lag.reconcile_pending` false, `llm.dailyRequests.today` unchanged, `store.manualPause` as before; `GET /scoutro/api/v1/kg/backups`: no new `before_upgrade` item.
- `sqlite3 graph.db "SELECT key,value FROM kg_meta WHERE key IN ('schema_version','reextract_pending','extractors')"`: `9`, no `reextract_pending`, extractors unchanged.
- Discovery `ScoutroDiscovery_p.html`: 13 jobs, `active_run` null, the four held candidates listed as held (manual), no new run until the schedule's next tick; the job store file has the same SHA-256 as before the start until a job runs.
- LLM selection: the configured service and models unchanged; `ANTHROPIC` selectable; the LLM queue of the knowledge graph paused as before.
- Object view of an entity named "Banken" or "Bildergalerie": role "no proper name", hidden from the business relations of its page's operator, still listed.

## Rollback to 0.11.4

0.11.4 opens schema 9 and job store schema 1 unchanged: start the 0.11.4 image by digest on the same DATA. Everything 0.11.5 stored meanwhile (new pages' facts, Discovery decisions by `system:reconcile`) is readable by 0.11.4; a job store with automatic deferrals (`decision.automatic`, `attempt.reconciled`, `outcome=profile_removed`) loads under 0.11.4 as well (the fields are optional in schema 1). No data is lost in either direction.

## Tests of this release

Local evidence only (the GitHub Actions quota of the fork may be exhausted; a green check in the pull request is not claimed): `tools/scoutro/ci/scoutro-checks.sh all` (every offline JUnit target and the Python suites), the knowledge graph production cases (`KgProductionCasesTest`, `EntityRolesTest`), the Discovery recovery tests (`DiscoveryRecoveryTest`, `DiscoveryAutomationTest`, `test_automation.py` with the four held hosts), the Anthropic fake-endpoint tests (`AnthropicServiceTest`, `AnthropicProxyTest`, `LlmHttpErrorTest`, `LlmBreakerTest`), fifteen offline knowledge UI tests, `llm-selection-offline-ui-test.mjs`, `format-probe-test.mjs`, the isolated image smoke and the upgrade 0.11.4 -> 0.11.5 -> 0.11.4 on a disposable schema-9 data set with a job store holding the four candidates (`test/scoutro-release/upgrade-0.11.5-test.py`).

## References

- Previous release notes: [0.11.4](SCOUTRO_RELEASE_0.11.4.md), [0.10.9](SCOUTRO_RELEASE_0.10.9.md)
- Analysis of the findings: the correction analysis handed over on 19 October 2026 (`SCOUTRO_0.11.5_KORREKTUR_ANALYSE.md`, outside the repository)
