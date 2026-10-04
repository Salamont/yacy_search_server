# RAG quality catalog

Reproducible before/after measurement of the RAG chat (docs/SCOUTRO_RAG_QUALITY.md).
Synthetic data only: every document is on an `.example` host; nothing is fetched from
the web and no production DATA or URL is used.

| File | Purpose |
| --- | --- |
| `corpus.json` | 28 documents in four collections (edelsenior-web, checkthecoach-web, stackfinder-web, bauteamcheck-web); `heimverzeichnis` is generated (42 000 characters) |
| `catalog.json` | 13 typical Scoutro questions with collection and expected (gold) documents, plus a 4-turn conversation |
| `RagFixture.java` | seeds the corpus into the Solr core of a NEW disposable DATA directory (refuses without the marker file) |
| `rag-quality.py` | starts a disposable peer, sends every question through `/v1/chat/completions` and records what the model receives |
| `rag-chat-ui-test.mjs` | Playwright check of the chat page against that peer (`--ui`) |
| `results/before.json`, `results/after.json` | measurements of the old and the new code |

Run (after `ant compile`):

```
env -u JAVA_TOOL_OPTIONS JAVA=$(which java) python3 test/scoutro-rag/rag-quality.py --label after --out /tmp/after.json
# with the chat page check:
NODE_PATH=/opt/node22/lib/node_modules SCOUTRO_CHROMIUM_PATH=/opt/pw-browsers/chromium \
  python3 test/scoutro-rag/rag-quality.py --label after --out /tmp/after.json --ui
```

Per question: sources in the context (`hits`), relevant of expected (`relevant/gold`),
precision and recall, sources from another collection (`foreign`), context characters,
estimated prompt tokens against `num_ctx` (`fits_num_ctx`), UI sources equal to the
context sources (`ui_equals_context`), sent `temperature`/`max_tokens`/`num_ctx`, the
retrieval stage and the citation check. The conversation reports prompt size per turn.

The model is a local recording endpoint with a fixed answer that cites `[1][2]`, an
invented `[9]` and an invented URL. Environment:

| Variable | Default | Meaning |
| --- | --- | --- |
| `SCOUTRO_RAG_NUM_CTX` | 4096 | `ai.service_num_ctx` of the fixture model |
| `SCOUTRO_RAG_MAX_TOKENS` | 512 | `max_tokens` of the fixture model |
| `SCOUTRO_RAG_LLM`, `SCOUTRO_RAG_MODEL` | – | forward to a real OpenAI-compatible endpoint (read-only) |
| `SCOUTRO_RAG_HOLD` | 0 | keep the peer running for N seconds for manual queries (debugging) |

Offline unit tests on the same corpus: `ant scoutro-rag-test`.
