# Scoutro RAG answer quality

Retrieval, context and citations of the RAG chat (`POST /v1/chat/completions`,
`RAGProxyServlet`). This builds on [the LLM/RAG security work](SCOUTRO_LLM_SECURITY.md):
the server base prompt, the untrusted `DATA-<nonce>` blocks, the AI Shield and the
tool policy are unchanged.

Not changed: Classification, LLM providers, Discovery and the crawler, the security
model, `/tools`, MCP and the `search` tool.

## 1. Retrieval flow

The synonym correction uses the original prepared `RagQuery` through the production
search handoff, scoring and excerpts. Expanded strings are not prepared a second
time. `RagSynonyms` reads the active embedded schema/query fields and the existing
YaCy synonym library; only actual document text can substantiate an alternative.
Unknown/remote schemas do not receive guessed alternatives. The ambiguous
Beratung/Besprechung pair is excluded. Each original concept counts once.

Main's ranking comparator, selection stages, collection policy, candidate/source
budgets, knowledge processing and TLDR fallback stay unchanged. Two-letter
acronyms require complete word boundaries; longer terms retain main's prefix rules.
No dictionary/schema, setting, model call or background task is added.

`ant scoutro-rag-test` includes the embedded-Solr synonym regressions. The existing
`test/scoutro-access/chat-rag-live-smoke.py` also checks TV/Television,
Rechner/Computer, long expanded questions, negative cases and the actual outgoing
model context on a disposable peer. Its tiny dictionary fixtures exist only in
that temporary DATA directory; the assigned TLDR model must not receive an extra
call when synonym retrieval succeeds.

| | Before | Now |
| --- | --- | --- |
| Query | the whole question as typed, stopwords included (`welche`, `gibt`, `es`, `in` …); for long questions the tldr model was asked for search words | `RagQuery`: stopwords (YaCy and Solr lists, DE/EN) and chat filler words removed, quoted phrases kept, at most 8 terms (nouns and longer words first), generic words such as "Anbieter" marked weak. No LLM call |
| Word forms | exact form only ("Pflegeheime" does not find "Pflegeheim") | each word plus its base form in the query (`pflegeheime pflegeheim köln`); matching of candidates folds umlauts and accepts compounds |
| Search | `RAGAugmentor.searchResultsDocument` on the whole index | the same YaCy search event (`SearchEventCache`), restricted to the requested collection, 30 candidates |
| Selection | the first hits as returned | candidates ranked by the question's strong terms they contain: stage `all` (every strong term), else `majority`, else `any`; no strong term → no sources instead of random hits |
| Context | whole documents, cut at 30 000 characters | numbered excerpts within a budget from `num_ctx` (section 3) |
| UI sources | a second search after the answer | the same retrieval set: `scoutro-sources` in the stream |
| Follow-up turns | every earlier search document sent again | only the newest one is kept, and only when the turn does not search again |

How the local YaCy search behaves (checked on the fixture peer): it combines the
query words with OR (Solr `mm=1`, ranking by matched words), matches exact word forms
only (no stemming, no decompounding), and a wildcard word (`pflegeheim*`) finds
nothing. Hence the base forms in the query and the coverage stages after the search.
Remote peers (`search: global`) require every word, so a P2P search gets the plain
words only. A collection always keeps the search local: remote peers know no
collections.

Fallback: a question with more than 40 words and no result asks the tldr model once
for search words (as before), then the same selection runs again.

## 2. Collection scope

The request may name one collection:

```json
{"model": "chat", "stream": true, "collection": "edelsenior-web",
 "messages": [{"role": "user", "content": "Welche Pflegeheime gibt es in Köln?", "search": "local"}]}
```

- `collection` must match `[A-Za-z0-9_-]{1,64}`, otherwise `400 invalid_request`.
- It must be one of the client's collections (package 6.1): local and
  administrator access may name every collection, an AI Shield guest only one
  released in `ai.shield.guest-collections` (default: none). Any other name is
  `403 collection_not_allowed`, the same for an existing and an unknown name;
  the name is not echoed. A `collection:` in a guest's question counts only for
  a released collection, otherwise the whole index is searched.
- The YaCy search filters on `collection_sxt`, and every candidate outside the
  collection is dropped again (`foreignDropped` in the log), so sources of other
  collections never reach the context.
- An explicit `collection` wins over a `collection:` written in the question.
- Without `collection`: as before, the whole index (or `collection:` in the
  question). Results of several collections can then be mixed; this is the
  documented fallback for clients that send no collection.
- The chat page has a *Collection* list (a `select`, no free text): "All
  collections" first, then the client's collections alphabetically, rendered
  by the server with the same rule as the endpoint. `yacychat.html?collection=edelsenior-web`
  presets it and the choice is remembered in the browser (`localStorage`),
  both only if the name is in the list; otherwise "All collections" applies
  and a remembered name that is no longer listed is forgotten.

## 3. Context budget

`RagContext.plan(num_ctx, max_tokens, fixedChars, hardCap)`, 3 characters per token
(conservative for German):

```
free  = (num_ctx − answerTokens − 192 safety) × 3 − (system prompt + question + prefix)
answerTokens = min(max_tokens, num_ctx / 2)
history ≤ 35 % of free;  sources = min(ai.rag.search-document-maxlength, free − history)
```

- `num_ctx`: per service from LLM Selection (`ai.service_num_ctx`, default 4096).
- `max_tokens`: the chat model's value from LLM Selection; a client value is used
  only when it is smaller.
- Sources: at most `ai.rag.max-sources` (8) entries of at most `ai.rag.document-maxlength`
  (1500) characters. An entry is complete or shortened at a sentence or word boundary;
  from long documents the sentences with the most question terms are taken (gaps
  marked with "…"). The block ends after the last entry that fits, never inside one.
- History: oldest messages are removed until the history fits; a history never starts
  with an answer whose question was removed.

Entry format, inside the `DATA` block:

```
[1] Pflegeheim Haus Sonnenschein Köln
URL: https://pflege-sonnenschein.example/
Collection: edelsenior-web
Text: Das Pflegeheim Haus Sonnenschein in Köln-Lindenthal hat 96 Plätze …
```

## 4. Answer parameters

| Parameter | Source |
| --- | --- |
| `max_tokens` | chat model row in LLM Selection (smaller client value allowed) |
| `num_ctx` | `ai.service_num_ctx` of the service; sent as hint to Ollama/LM Studio only, always used for the budget |
| `temperature` | client value if it is a number, else `ai.rag.temperature` (0.2) |

## 5. Grounding

The server base prompt (`PromptGuard`) has "Rules for answers from search results":
facts only from the numbered sources, `[n]` after each statement, only listed numbers,
say plainly what the sources do not answer, never invent providers, facts, numbers,
URLs or sources, and say that the index has no matching content when there are no
results. `ai.llm-user-prefix` now reads "Search results (numbered sources, cite them as
[n]):"; a stored old default is replaced by the new one.

## 6. Citations

Stream metadata (in addition to the model's chunks):

| Key | When | Content |
| --- | --- | --- |
| `scoutro-sources` | first data line | `[{id, title, url, host, collection}]`: exactly the context sources |
| `scoutro-retrieval` | first data line, turns with search | `{query, stage, collection, global, candidates, contextChars, contextTokens, numCtx}` |
| `search-filename`, `search-text-base64` | first data line, turns with sources | the context text, which the chat page keeps for follow-up questions |
| `scoutro-citations` | before `[DONE]` | `{valid, invalid, unknownUrls, sources, support, unsupported, weak}` checked against the sources of this answer |
| `scoutro-sources`, `scoutro-tools` | before `[DONE]`, answers with tool calls | all sources including the pages of tool results; `{calls, millis, sources, collection, local}` (section 11) |

Support (`RagCitations`): for every valid `[n]` the statement in front of it (the part of the sentence since the citation
before, else the sentence or line) is compared with the text the model was given under that number: the excerpt of the
page, the graph facts, the passages of tool results. `support` lists per cited source its worst verdict
`{id, verdict, matched, terms, statement}`: `supported` (at least half of the statement's content words occur in that
text, with the word stems of the retrieval), `weak` (fewer), `unsupported` (none: the source does not speak about the
statement), `unchecked` (the text of the source is not known, or the statement has no content word). `unsupported` and
`weak` repeat the numbers. The chat page shows an unsupported number dotted, with a note below the answer.

The chat page links `[n]` to source n and lists the sources below the answer. A number
without a source is shown as `[n?]` and never linked. A URL in the answer that is not
among the sources is shown as unlinked text. Both appear in a warning below the answer.
In follow-up turns the citation numbers of earlier answers are removed before the
history goes to the model when their sources are no longer sent, so an old "[1]" is not
confused with source [1] of the current search.

## 7. Settings

RAG Config page (`RAGConfig_p.html`), defaults in `defaults/yacy.init`:

| Key | Default | Range |
| --- | --- | --- |
| `ai.rag.max-sources` | 8 | 1–20 |
| `ai.rag.document-maxlength` | 1500 | 400–20000 |
| `ai.rag.search-document-maxlength` | 30000 | upper bound for all sources and the `search` tool |
| `ai.rag.temperature` | 0.2 | 0–2 |

Log lines (`RAGProxy`): `event=rag-request phase=messages … numCtx= maxTokens= freeChars=
prunedSearchDocs= staleAnswers= trimmedMessages=`, `event=rag-search phase=candidates …
requested= returned=`, `event=rag-retrieval phase=end … stage= candidates= foreignDropped=
sources= contextChars=`.

## 8. Tests

- `ant scoutro-rag-test`: 25 offline tests (`RagQueryTest`, `RagRetrieverTest`,
  `RagContextTest`, `RagCitationsTest`, `RagConversationTest`) on the corpus of
  `test/scoutro-rag/corpus.json`, with a searcher that behaves like the local YaCy search.
- `test/scoutro-rag/rag-quality.py --label after --out results.json [--ui]`: the
  catalog (`catalog.json`, 13 questions and a 4-turn conversation) against a disposable
  peer seeded with the corpus; the model is a local recording endpoint. `--ui` also runs
  `rag-chat-ui-test.mjs` (Playwright, 60 checks at 390 and 1280 px). See
  `test/scoutro-rag/README.md`.

## 9. Before / after

`test/scoutro-rag/results/before.json` (old code) and `after.json`, `num_ctx` 4096,
`max_tokens` 512. Columns: sources in the context / relevant of expected / sources from
another collection / estimated prompt tokens (✗ = does not fit into 4096 − 512).

| Question | Before | After | Stage |
| --- | --- | --- | --- |
| Q01 Welche Pflegeheime gibt es in Köln? | 1 / 0 of 2 / 1 / 10519 ✗ | 4 / 2 of 2 / 0 / 1481 | all |
| Q02 … Nähe von Köln … Demenz geeignet? (stopwords) | 10 / 2 of 2 / 2 / 1986 | 4 / 2 of 2 / 0 / 1478 | majority |
| Q03 long question (60 words) | 10 / 2 of 2 / 2 / 2071 | 4 / 2 of 2 / 0 / 1562 | majority |
| Q04 Pflegeheim Köln Demenz Schwimmbad Haustiere (too strict) | 10 / 2 of 2 / 3 / 1899 | 4 / 2 of 2 / 0 / 1436 | majority |
| Q05 Agentur Hamburg Shopware (stackfinder-web) | 10 / 1 of 1 / 6 / 1648 | 1 / 1 of 1 / 0 / 800 | all |
| Q06 Business Coach Hamburg (checkthecoach-web) | 10 / 1 of 1 / 6 / 1761 | 1 / 1 of 1 / 0 / 847 | all |
| Q07 Wer baut Einfamilienhäuser in Köln? (bauteamcheck-web) | 10 / 1 of 1 / 8 / 1934 | 2 / 1 of 1 / 0 / 936 | majority |
| Q08 Weltraumtourismus (0 expected) | 10 / – / 4 / 1755 | 0 / – / 0 / 671 | none |
| Q09 Hospiz in Düsseldorf (1 expected) | 10 / 1 of 1 / 3 / 1805 | 1 / 1 of 1 / 0 / 815 | all |
| Q10 Welche Angebote zur Pflege gibt es? (many) | 10 / 8 of 13 / 2 / 1886 | 7 / 7 of 13 / 0 / 1978 | all |
| Q11 Pflegeheim in Köln (edelsenior-web) | 10 / 2 of 2 / 2 / 1895 | 4 / 2 of 2 / 0 / 1475 | all |
| Q11 without `collection` | 10 / 2 of 2 / 2 / 1895 | 7 / 2 of 2 / 3 / 1861 | all |
| Q12 Aachen im Heimverzeichnis (42 000 character page) | 0 / 0 of 2 / 0 / 10526 ✗ | 1 / 1 of 2 / 0 / 1153 | all |
| Q13 Coaching in Köln (checkthecoach-web) | 10 / 1 of 1 / 6 / 1696 | 1 / 1 of 1 / 0 / 784 | all |

Conversation (4 turns, the chat page's client behaviour), estimated prompt tokens per
turn: before 10519 / 12101 / 13605 / 23746 (no turn fits); after 1481 / 1374 / 902 / 1000.

Source quality: before, the UI showed the result of a second search; now the UI sources
are the context sources in every request. Citations: the deterministic test answer cites
`[1][2]`, an invented `[9]` and an invented URL; `[9]` and the URL are reported in
`scoutro-citations`, shown as `[9?]` and as unlinked text.

Answer quality with a real model is not part of these numbers (the catalog runs with a
recording endpoint); `SCOUTRO_RAG_LLM` points the same catalog at a real
OpenAI-compatible endpoint (section 10).

## 10. Known limits

- No decompounding in the index: "Pflege" does not find documents that only contain
  "Tagespflege" or "Pflegeheim" (Q10: 7 of 13). The candidate check accepts compounds,
  the YaCy search does not.
- Base forms come from a few suffix rules, not a stemmer; a useless form ("aache")
  costs nothing in an OR search but cannot find irregular forms.
- The strictest stage wins: with a strong term that only one document contains
  ("Heimverzeichnis"), related documents with the other terms are not added (Q12:
  the Aachen home is covered by the excerpt of the directory, not as its own source).
- Characters per token are an estimate; `num_ctx` in LLM Selection must match the
  window the backend really serves (Ollama `OLLAMA_CONTEXT_LENGTH`).
- The citation check is lexical: it proves that `[n]` and URLs belong to the sources and
  that the words of a statement occur in the text given under its number. It finds a number
  behind a statement its source does not make; it cannot tell a source that says the same
  from one that uses the same words for something else (a negation, another company on the
  same page), and a paraphrase or an answer in another language than the source reads as
  `weak` or `unsupported` although it is right. It is a signal for the reader, not a proof.
- The pages of tool results are not part of the search document the chat page keeps for
  follow-up questions: a later turn can cite them only after calling the tools again.
- Without `collection` the whole index is used, as before.

## 11. Tools of the chat

With a tool-capable chat model the model can search again and read the knowledge graph while it answers
(`ToolCallProtocol`, at most 8 rounds; the last round asks for the answer itself). The tools are off until an
administrator releases them (Tools page, `ai.tools.<name>.enabled=true`):

| Tool | Reads | Returns |
| --- | --- | --- |
| `search(query)` | the local index, with the retrieval of section 1 (`RagQuery`, the stages of `RagRetriever`) | at most 6 pages with excerpt and stage; a weak or empty result says so |
| `kg_search(query, type)` | entities of the graph by words of their name or title (`KgReader.entities`) | at most 8 entities with type, hosts and, for a job or service, its employer or provider |
| `kg_entity(entity, predicate)` | the facts and the relations in both directions of one entity (`KgReader.entityStatements`) | at most 20 facts and 12 incoming relations, each with its newest visible source page |
| `kg_evidence(statement)` | the evidence of one fact (`KgReader.evidence`) | at most 5 source pages with URL, collection, load time, how it was read and the passage |

**Scope.** The servlet builds one `ToolContext` per request from the chat session, before the model is called: the
collection the request resolved to (an account's collection, or the one an administrator chose), or the whole local
index for local and administrator access without a collection. The tools take no collection argument; a
`collection:` word in a query and any other argument are ignored. `search` asks the index for that collection and
drops every hit of another one; the graph tools read with the viewer of that collection (`KgRuntime.chatViewer`), so
an entity, relation or evidence row outside it answers `not_found`, exactly like an unknown ID. The search is always
local, never the peer-to-peer network. The graph tools exist for local, administrator and account access only (as the
graph facts of section 1); an AI Shield guest gets neither them nor `search`. Without a `ToolContext` (any other
caller of `ToolProvider`) `search` is the index tool it was and the graph tools do not exist.

**Sources.** Every page a tool result names gets the next number of the answer's sources (`"cite": "[n]"`), a page
that is already a source keeps its number. A graph fact is given with the page it was read from, marked `"kind":
"graph"` like a graph fact of the retrieval. The final metadata line sends the complete list again
(`scoutro-sources`) before the citation check, which knows the text of each number (section 6). A fact without a
visible source has no number and says `"source": "none visible"`; evidence read from structured data says that it has
no passage. An empty result, `not_found`, `timeout`, `busy` and `unavailable` are worded for the model as "not found
or not verified", and the system prompt tells it to say so instead of stating the fact.

**Limits.** Per answer: 8 rounds; `ai.tools.<name>.maxCallsPerTurn` per tool (search 3, graph tools 4 each); 10 scoped
calls over all tools, and no further call once the tool calls of the answer have taken 20 s; every graph read is a lease
of at most 1.5 s (`KgReader.within`; waiting for one of the store's readers comes on top, see below); all results
together at most a third of the context window (1 500 to 16 000 characters). A call beyond a limit is answered with
`{"error":"tool_budget"}` without running, so the model writes its answer.

**Weak hits.** When the automatic search of the question ends in stage `any` or `none` and a scoped tool is released,
the system prompt tells the model to search again with the concrete words of the question.

Tests: `ChatToolsTest` (in `ant scoutro-rag-test`: a real graph store filled through the extractors, two collections)
and `test/scoutro-access/chat-tools-live-smoke.py` (a disposable peer, the normal chat endpoint, a simulated model
that scripts the tool calls; no language model is called).

Not part of this step: the ranking of the automatic search and its query generation for long questions, the speed of
the automatic graph facts (their 300 ms budget, and the store's two readers a graph tool waits for up to 2 s before it
answers `busy`), a filter of the graph by topic or system, and tools for an AI Shield guest.

## 12. Job search before the answer

A model may answer from weak sources without calling a tool, even when the tools are offered (seen in production: a
question about jobs, `stage=any`, five tools offered, none called). For questions about job postings the server
therefore searches the knowledge graph's postings itself, before the model is called (`JobSearch`):

- **When:** the question (or its goal, below) asks for jobs (Stelle, Stellenanzeige, Job, Karriere, Ausschreibung,
  "(m/w/d)", vacancy, career …) **and** the automatic search answers it weakly: stage `any`, `none` or `no-terms`, no hit,
  or no hit is a job page by its title or address (a company's home page matching the subjects is no posting).
- **Only with the existing release** of `kg_search` and within the scope of the request (`ToolContext`: the session's
  collection, read with its viewer). An AI Shield guest gets none; without the release nothing runs.
- **Few short title variants**, at most 6 name searches of `type=job`: the subjects SAP, KI and Automatisierung first
  together, then each on its own, the question's own compounds ("Prozessautomatisierung": a name search matches only the
  start of a word), then "Intelligenz", "AI", "Automation"; a role of the question ("SAP Berater") before the subject
  alone. Short acronyms are whole words (`KgReader.EntityQuery.wholeWords`), and a hit must show its subject in the title.
  Any other subject is searched by its own words. At most 12 candidates, 6 postings, 4 s.
- **To the answer:** each posting is a numbered source before the search results (`[1] Scoutro knowledge graph: job
  posting <title>`, `URL:` the page it was read from, its collection), with employer, employment type, place, dates, the
  subjects of the question it shows and, as `PARTIAL MATCH`, those it does not. The full matches come first.
- **Application status:** never checked. Each entry says that whether the posting is still open is a separate question,
  with the date the page was last loaded and, if recorded, an end date that has passed; the server rules tell the model
  not to state it and to answer it, if asked, only from these dates.
- **Nothing found:** the server rules say that the postings were searched with these words in this scope and none was
  found, and that no company page may be presented as a posting.
- **Follow-up:** a message that asks to search again ("Such noch mal", "Such noch mal gezielt nach Stellenanzeigen und
  prüfe die Originalquellen", "nochmal suchen", "Versuch es erneut", "search again") takes the goal of the conversation:
  the latest earlier user message with a subject, joined with the follow-up (`RagFollowUp`); the automatic search and the
  job search use it, the scope stays the request's. A message without the words of a retry is always its own goal.
- **Metadata:** `scoutro-jobsearch: {triggered, followUp, scope, criteria, searched, found, notShown, full, partial, timedOut,
  error, millis}` (`notShown`: postings found without room left in the context; the model is then not told "none") in the first data line; `scoutro-retrieval.followUp`. Log: `event=rag-jobsearch`.

Tests: `ChatToolsTest` and `RagFollowUpTest` (`ant scoutro-rag-test`), `test/scoutro-access/chat-jobsearch-live-smoke.py`
(a disposable peer, the normal endpoint, a simulated model that never calls a tool).

Real-model check against an OpenAI-compatible endpoint (read-only, disposable DATA):

```
SCOUTRO_RAG_LLM=http://<ollama-host>:11434 SCOUTRO_RAG_MODEL=llama3.1:8b \
SCOUTRO_RAG_NUM_CTX=8192 python3 test/scoutro-rag/rag-quality.py --label real --out real.json
```
