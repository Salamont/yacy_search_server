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
  "(m/w/d)", vacancy, career …) **and** it asks for a number or a list of them ("Wie viele …", "Zeige alle …", "Welche
  Stellenanzeigen gibt es zu …"; `QuestionKind`), or the automatic search answers it weakly: stage `any`, `none` or
  `no-terms`, no hit, or no hit is a job page by its title or address (a company's home page matching the subjects is no
  posting).
- **Only with the release of the tool `job_search`** ("Job search" on the Tools page), which follows `kg_search` while it
  is not set on its own (saving the Tools page gives it its own setting), and within the scope of the request
  (`ToolContext`: the session's collection, read with its viewer). An AI Shield guest gets none; without the release
  nothing runs. The model can also call `job_search(query)` itself (2 calls per answer).
- **Subjects and title variants:** the subjects of the question (in its order: its configured subject groups and up to
  3 of its own words, not counting words about jobs or the answer); at most 6 title variants for the name index: the
  subjects first together, then each on its own, the question's own compounds ("Prozessrobotik": a name search matches
  only the start of a word), then the synonyms of a configured subject group; a role of the question ("Java Entwickler")
  before the subject alone. `scoutro.chat.jobs.subjects` may group synonyms (`Label: synonym, synonym; Label: …`, empty
  by default; e.g. `ML: Machine Learning, maschinelles Lernen`). Short names (up to 3 letters) are whole words.
- **All or how many:** a question for all postings or their number ("alle", "sämtliche", "wie viele", "Anzahl", "how
  many" …; `JobSearch.wantsAll`) runs the full search also after strong first hits, so they never stand in for it.
  The words of such a question are never a subject (`JobSearch.countWord`): "Wie viele Stellenanzeigen zu X gibt es?" and
  "Zeige alle Stellenanzeigen zu X" have the subject X only and count the same unique postings (before 0.14.3-rc.5 "viele"
  was a subject of its own, and postings with "vielen" or "vielseitige" in their text were counted).
- **The matching postings first, by the indexes; the evidence only for those shown** (`JobSearch.run`, the same for the
  automatic search and `job_search`). Which postings match and why is determined for the whole released stock before
  anything is selected:
  1. **scan**: every posting of the request's scope that the viewer sees with a current page, in chunks of 2,000
     (`KgReader.entityScan`): its title and its statements (employer, place, field, signals …), a related entity by its
     name (`entityNames`). Three queries per chunk that follow the indexes of the entity type, of the statements by
     subject and of the evidence, in a fixed join order; no evidence text is read and no statement is checked one by one.
     A posting shows a subject by its title or by a fact;
  2. **facts**: only the statements that alone show a subject are checked for the viewer (`currentStatements`: visible,
     with a current source);
  3. **text**: per subject, the pages of the postings that do not show it yet are asked in the direct index
     (`SolrIndex.matching`): a filter on up to 1,000 addresses per query and the collection, answered with addresses
     only. A page counts only as the page of a posting of the scope; the ranked search, which gives one page per host, is
     not used. Without a direct index the ranked search is the fallback, reported as a lower bound. **A text hit belongs
     to a posting, not to a page** (since the correction on 0.14.3-rc.6):
     - a page of one posting (one record, or records whose titles are one title: "Planer (m/w/d)" and "Planer Hochbau
       (m/w/d)" read from its heading and its structured data) is that posting;
     - a page of several postings (an overview) is read (at most 60 such pages per search) and each posting is asked
       for its part of the page: the text after an occurrence of its title up to the next title of a posting of the
       page, at most 1,500 characters, gender markers ("(m/w/d)") ignored (`JobSearch.attribute`). Only a posting whose
       part shows the subject counts. A title of words about jobs only ("Karriere", "Stellenangebote") has no part of
       its own;
     - what such a page says outside every posting's part is a hit of the page and of no posting: it raises neither
       `found` nor `records` and is reported apart as `pageOnly` (pages). Before, the first posting of a page took the
       hit of the whole page, and further postings of the page none;
  4. **selection**: duplicates count once: one title of one employer at one place over its pages (the same posting on
     two addresses), and the records of one page whose titles are one title. Different postings of one address stay
     apart. `found` is the number of postings, `records` the matching records before that (seven records of one employer
     of which two are one posting on two pages: `found` 6, `records` 7);
  5. **evidence**: only the postings shown are read in full (`KgReader.jobDigests` for at most 6), and the page of a
     posting that shows a subject by its text only gives an excerpt of 200 characters.

  A posting matches a subject by its title, a fact, or only the text of its page (in this order of strength). All of
  this within 6 s; every step checks the time. The metadata and the log give the milliseconds of each step (`phases`).
- **Lower bound:** when the time ran out in a step (the stock, the check of the facts or the posting pages not all read),
  a read of the graph failed, or there is no direct index, the count is a lower bound (`lowerBound`) and `gaps` says
  why; the model is told "at least" with the reason and, with nothing found, not to say that the index holds none. A
  chunk of the scan that does not fit one read of the graph (1.5 s) is read again in smaller chunks.
- **The employer of the question** ("Stellenanzeigen von X", "Stellen bei der Firma X", "jobs at X"; the tool's argument
  `employer`): a name after "von", "bei", "at", "by" or "from" that starts with a capital or a digit and is no word
  about jobs, the count or a role ("bei Beratungsunternehmen" names none; `JobSearch.employerSpan`). It is no subject:
  only a posting the graph records X as the employer of counts (a current `hiring_organization` statement the viewer
  sees whose name holds X as whole words: "Biber" is the employer "Biber Messtechnik GmbH", not "Biber18"). A posting
  that names X in its title, another fact or its page's text is a mention, counted apart (`mentions`) and never part of
  `found`. Other subjects of the question narrow the employer's postings ("Stellenanzeigen zu Treasury von X"). "Stellen
  zu X" stays the search by subject: every posting that shows X by its employer, its title or its text.
- **One stock, one answer:** a search over all collections reads the same postings as the searches of the single
  collections together, so it holds every posting any of them finds (`ChatToolsTest`, `SearchScaleBench check`). The
  order of the scan is the order of the graph's rows; the selection breaks ties by it, so the same question gives the
  same postings.
- **Selection:** sorted by the subjects a posting shows and how; of the 6 shown, the subjects in turn take their most
  relevant posting (an or-question keeps every subject that has postings), then the most relevant rest. Dates neither
  exclude nor rank.
- **To the answer, by the kind of the question** (`QuestionKind`):
  - a **plain count** ("Wie viele Stellenanzeigen zu X gibt es?": one short question for the number and nothing else) is
    answered by the server itself, without a model request: the number found (or "at least" with the reason), the
    records when a posting stands on two pages, what was seen and not counted (postings that only name the employer
    of the question, overview pages whose hit belongs to no posting), per subject when there are several, and the
    postings shown with their numbers. `scoutro-answer: {by: "server", modelCalls: 0}`; `scoutro.chat.count.direct=false` sends it to the model;
  - a **count that asks for more**, a **list** or a **recommendation** goes to the model with the postings shown as its
    only sources: the pages of the automatic search and their graph facts stay out of the context
    (`scoutro-retrieval.focused`), and the server rules name the kind (the number is the search's, a list is the entries
    given, a recommendation weighs their evidence);
  - any other job question (something of one posting) keeps the automatic search's pages; the postings come first.

  Each posting shown is a numbered source (`[1] Scoutro knowledge graph: job posting <title>`, `URL:` the page it was
  read from, its collection), with employer, employment type, place, the dates it states, the subjects of the question
  it shows (those shown only in its page's text named, with the excerpt) and, as `PARTIAL MATCH`, those it does not. The
  server rules give the number found and shown.
- **One search per request:** the result is kept for the request (`ToolContext.remember`). A call of `job_search` for
  the same subjects in the same scope returns it (`reused: true`, its entries shortened: they are sources of the answer
  already) instead of searching again; another subject or another request searches.
- **Currency:** the stock of the knowledge graph counts as current: a missing posting or end date excludes, ranks and
  remarks nothing, and no page is checked live.
- **Nothing found:** the server rules say that the postings were searched with these words in this scope and none was
  found (or none within the budget), and that no company page may be presented as a posting.
- **Limits of the available queries** (where a complete set is not guaranteed): there is no index on the values of
  facts, so the facts of every posting of the scope are read and matched in memory; the time grows with the number of
  postings (6,851 postings: 0.23 s with warm caches, 1.0 s cold, section 14; about 40,000 postings cold fill the budget), and beyond the 6 s the count is a lower bound. The description of a posting is not a fact of its
  own, it counts through the indexed text of its page. The direct index matches every word as a word or the start of one
  and a word of up to three letters as a whole word (Solr's tokens: "Robotik" finds "Robotiktechnik", not
  "Prozessrobotik", which only the graph's own matching of titles and facts finds); several words of one synonym must
  stand side by side. It needs the posting's page under the same address in the graph and the index; a posting whose
  page is not in the index counts by its title and facts only.
- **Follow-up:** a message that asks to search again ("Such noch mal", "Such noch mal gezielt nach Stellenanzeigen und
  prüfe die Originalquellen", "nochmal suchen", "Versuch es erneut", "search again") takes the goal of the conversation:
  the latest earlier user message with a subject, joined with the follow-up (`RagFollowUp`); the automatic search and the
  job search use it, the scope stays the request's. A message without the words of a retry is always its own goal.
  Search mode: a retry that names no search mode (`search` absent, e.g. the lens off after the first question because the
  default is "no search") searches the local index when its goal carries an earlier message with a subject; an explicit
  `search: "no"` (the lens switched off for this message) is respected, and a retry as the first message has nothing to
  search for. Log: `event=rag-request … ragMode=local searchMode=followUp` (else `sent` or `absent`) and
  `event=rag-retrieval … followUp=true`.
- **Metadata:** `scoutro-jobsearch: {triggered, followUp, scope, criteria, searched, found, lowerBound, shown, notShown,
  perSubject, records, scanned, gaps, full, partial, timedOut, error, millis, phases}` (`found`: matching postings
  determined in the scope, each once; `records`: matching records; `shown`: given as sources; `notShown`: selected without
  room left in the context; `scanned`: postings read; `phases`: milliseconds of scan, names, facts, text, selection,
  evidence) in the first data line; `scoutro-retrieval.{followUp, kind, focused}`. Log: `event=rag-jobsearch … found=
  lowerBound= scanned= records= shown= all= kind= gaps= ms= phases=scan:…,names:…,facts:…,text:…,selection:…,evidence:…`.
- **Model requests and tokens:** the last data line carries `scoutro-usage: {modelCalls, requestChars, answerChars,
  tokens, inputTokens, outputTokens}`; `tokens` is `reported` when the endpoint sent the usage of every request,
  `estimated` (characters / 3) when it did not, `none` for the server's own answer. The log line of the model lifecycle
  carries the same. `ai.stream.usage=true` asks an OpenAI-compatible endpoint to report the usage of a streamed answer
  (`stream_options.include_usage`; off by default, as not every endpoint accepts the field).

Tests: `ChatToolsTest`, `SolrIndexTest` (the direct index on an embedded Solr with the shipped schema) and
`RagFollowUpTest` (`ant scoutro-rag-test`), `test/scoutro-access/chat-jobsearch-live-smoke.py` (a disposable peer, the
normal endpoint, a simulated model that never calls a tool). Measurement, not part of the suites:
`SearchScaleFixture` builds an invented stock of the size of a production peer, `SearchScaleBench` measures the steps on
it and checks the counts; `test/scoutro-access/chat-search-measure.py` records the model requests and their sizes at the
chat endpoint (section 14).

## 13. Provider search before the answer

A question for the provider of a service names the problem, rarely the service ("Wer hilft in Musterstadt bei Schimmel
im Keller?"); its automatic search may find a page that only shares words (a house gallery in Musterstadt). The server therefore
searches again itself, before the model is called (`ServiceSearch`, tool `provider_search`, "Provider search"):

- **When:** the question asks for a provider of a service: a problem word of a small table ("tragend", "Statik",
  "Wanddurchbruch" → Tragwerksplanung, Statiker, Statik; mould, damp, energy certificate, asbestos, building surveyor),
  or a provider question in words ("Wer baut …", "Anbieter", "Fachbetrieb", "Handwerker" …) with its own nouns as the
  service; **and** it asks for a number, a list or a recommendation of providers ("Wie viele Anbieter …", "Welche
  Statiker gibt es …", "Wer prüft …"; `QuestionKind`), or the automatic search matched weakly or no hit names the
  service. A job question takes the job search of section 12 (`type=job` stays the filter for postings) and never this
  one.
- **The place:** the town after "in", "bei", "aus", "um", "nahe" (also "Bad …", "… am Main", "Neustadt bei Nebenstadt";
  a problem word after "bei" is no part of it: "in Musterstadt bei Schimmel"), or a postal code. The tool also takes the place
  without a preposition at the end of its request, after the service: "Tragwerksplanung Musterstadt" and "Tragwerksplanung
  in Musterstadt" are one search, as are "Statik Neustadt an der Musterau" and "Statik Bad Musterbrunn"; a part of a
  building or a kind of work there is no place ("Schimmelsanierung Keller"). The optional argument `place` names it when
  the words leave it unclear; it takes precedence.
- **The providers first, by the indexes; texts only for those shown** (`ServiceSearch.run`, the same for the automatic
  search and `provider_search`), within the request's scope and 6 s:
  1. **graph**: the rows of the services, organisations and facilities named like a service of the plan (the name index,
     `KgReader.namedEntities`), the providers that offer those services (`subjectsOf` over `offers`), and of all of them
     the name and a current page (`entityRefs`);
  2. **index**: the hosts of the scope with pages that name a service of the plan, counted by host in one query (a facet
     on the host, `SolrIndex.hosts`); no page is loaded. A host of a provider of the graph counts for that provider, any
     other host is a provider of its own;
  3. **place** (with a place in the question), by the identity of the provider (below): the addresses and service
     areas of the graph that name the place (`factsNaming`), for the provider itself or a facility it operates; and
     the hosts of the scope with a page naming the place (one more count by host): of those that are providers, the
     pages naming the place are read (at most 3 per host and 120 providers; providers of the graph and those with the
     most pages naming the service first) and asked whose the address or area on them is (`ProviderPlace`). A provider
     whose pages naming the place were all read without an address or area of its own only **mentions** it; one whose
     pages were not all read (beyond the 120, more than 3 pages, or the time budget), or whose site shows an address in
     the place that cannot be shown to be its own, is **open** (`undecided`);
  4. **selection** and **evidence**: of the at most 6 providers shown one page naming the service is read for the
     excerpt (240 characters), and the organisation of a host without one in step 1 for its name.

  A provider counts once: an entity of the graph over all its pages, any other provider once per host; a facility an
  organisation of the rows operates, or a part of it, counts for that organisation (`operates`, `part_of`); other
  entities and hosts stay separate. The count does not depend on how far a paging came: it is the same for the same
  stock, whatever was asked before. Without a direct index the ranked search (8 pages per search, one per host) is the
  fallback, reported as a lower bound.
- **Service and place of the same provider** (the identity of a provider; corrected on 0.14.3-rc.6, where any page of
  a provider's host with a postal code and the place made it "located", and any organisation of the graph on a host
  gave the host its address):
  - **Who a provider is:** an entity of the graph of step 1; for a host of step 2, the organisation the host belongs
    to: the declared operator of its registrable domain (`KgReader.siteOperators`: the one organisation holding the
    `site_operator` or `domain_operator` key). A host without one declared operator (a directory, a group's site, a
    site whose operator the graph has not read) has no identity in the graph; a page must then show whose an address
    is.
  - **The place by the graph:** an address, locality, postal code or service area that names the place is the
    provider's when its subject is the provider, or a facility or site the provider operates, that is part of it or
    located at it (`operates`, `part_of`, `located_at`, two steps: the site of a facility of the organisation). An
    address of another entity on the provider's site is not its own: another organisation's (a company a directory
    lists) is named, not counted; that of a facility the graph does not link, or of an organisation of a similar name (a
    company of a group), leaves the relation open.
  - **The place by a page of its site** (`ProviderPlace.read`), only when the page shows whose the address is: the name
    of a company at the address (the words before it up to a legal form such as "GmbH") is the provider's name: its
    own; shares nothing with it: another company's; a longer name around it ("Kranich Dach Süd GmbH" for "Kranich Dach
    GmbH"): a company of a group, open. Without a company's name: the provider's own name or a word of its host at
    the address, or the site's contact page or imprint with one address: its own. The address of a project, a
    reference or a plot (the words of its own sentence, or the page's title and address), and an entry of a list of
    four or more addresses, are not the provider's. Anything else is open. A service area on its page counts unless
    the page is such a list.
  - **Two sources:** the page that names the service and the page (or the graph statement's page) that evidences the
    place are given apart (`placeSource`, in the tool `place_source`; "Source of the place: … (another page than the
    one naming the service)"), and what a site says of the place without evidencing a location as `placeNote`.
  - **Two companies on one domain** (`ServiceSearch.sameCompany`, on 0.14.3-rc.7): a host can carry several
    companies. A provider known only by pages of its host that name the service, whose address or service area is
    evidenced for a named company (the graph's operator of the host, the company at the address, the company of the
    page stating the area), is local only when a page naming the service is that company's or names no company (it
    then speaks for its site). The company a page speaks for (`ProviderPlace.speaker`) is the company of its title,
    else the company a sentence names as the one doing the service; a company the service was done for ("für die …
    GmbH") is not it. Of the provider's pages naming the service at most three are read for this. When they name
    another company, the provider is the company of the service and the place is the other one's: named, not counted
    (`placeNote`: "its pages name the service for …; the address in … is that of …, another company on the same
    site"); the provider is then not shown as the site's operator. Companies of similar names, or pages naming the
    service that were not all read: open. Of two companies of which the one with the address offers the service too,
    that one is the provider, shown by its own page.
  A page of another host never gives a provider its place. Whether a page that names a service offers it is still the
  page's text (the excerpt shows it). What remains by the host: a page naming the service without a company's name
  counts for the site, also on a site of several companies; a company is recognised by its legal form only.
- **Its relation to the place** (with a place in the question): **located** there by an address (the graph's address,
  locality or postal code, or a postal code and the place on a page of its host), **serves** it by a service area (the
  graph's service area, an area phrase on its page such as "Einsatzgebiet: Musterstadt und Umgebung", or the service
  stated for the place: "Tragwerksplanung in Musterstadt"), or only **mentions** it ("eine Messe, die in Musterstadt
  stattfand", or the address of a project, a plot or another company; all its pages naming the place read), which
  evidences neither; **open** when its pages naming the place were not all read or an address there cannot be shown to
  be its own; else none (no page of its host names the place). Located before serving before open before
  mentioning before the rest, then the providers of graph and page, then by the pages naming the service, then by name.
- **Two kinds of incompleteness, kept apart:** `gaps` (and `lowerBound`) concern the count of providers: a step of graph
  or index that could not run or be read to its end. `placeGaps` (and `placeComplete: false`) concern only the relation
  to the place: the total stands, the numbers located and serving are then at least those evidenced, and `undecided`
  counts the providers whose relation is open. The server's count answer says both apart ("Diese Gesamtzahl ist
  vollständig; … mindestens **1** … Bei **14** Anbietern ist der Ortsbezug offen … Grund: …"); `provider_search` returns
  `lower_bound`, `place_complete` and `place_gaps`, and its note tells the model not to count an open provider as
  located, serving or mentioning.
- **To the answer:** the providers found are counted (`found`, per relation to the place) before the answer's limit of 6;
  each shown provider is a numbered source ("Provider …") with a page naming the service, its evidence (graph, page or
  both), its relation to the place with the evidence, and an excerpt of its page. As in section 12 the kind of the
  question decides: a plain count ("Wie viele Anbieter für X gibt es in Y?") is answered by the server itself (found,
  and located / serving / open / mentioning / unrelated apart), a list or a recommendation goes to the model with the providers
  shown as its only sources; a recommendation is told to prefer an evidenced location or service area and to say so
  when none has one. A call of `provider_search` for the same services and place in the same request returns the result
  already computed (`reused: true`).
- **Follow-up:** "Such noch mal" takes the goal of the conversation (section 12).
- **Metadata:** `scoutro-servicesearch: {triggered, followUp, scope, services, place, searched, found, lowerBound, gaps,
  shown, perPlace, placeComplete, placeGaps, placeNamedBy, placeHosts, timedOut, millis, servicePages, phases}`
  (`perPlace`: location, service_area, undecided, mentioned, none; `placeHosts`: the hosts of every provider with a
  relation to the place, per relation and at most 200 each, not only of the providers shown; `scoutro-jobsearch` of
  section 12 adds `pageOnly` with `pageOnlyPages` (their addresses), `ids` (the graph IDs of every posting found, at
  most 500; `idsComplete`) and, with an employer in the question, `employer` and `mentions`. The lists are for a
  comparison of a whole result with another count of the same stock; the model is not given them) (`servicePages`: pages of the scope naming a
  service; `phases`: milliseconds of graph, index, place, selection, evidence). Log: `event=rag-servicesearch … found=
  lowerBound= shown= all= kind= servicePages= gaps= placeOpen= placeGaps= ms= phases=graph:…,index:…,place:…,selection:…,evidence:…`.
  The model can call `provider_search(query, place?)` itself (2 calls per answer); it also takes the model's own words.
- **The tool room:** all tool results of an answer share a third of the model's context in characters (at most 16,000;
  4,096 for a context of 4,096 tokens). `provider_search` and `job_search` fit their result into what is left: first the
  texts of the entries are shortened (2,000, 900, 500, 260, 140 characters), then entries are left out from the end;
  `found`, `lower_bound`, `per_place`/`per_subject` stay, `shown` is the number given, `shortened` and `not_shown` say what
  was cut, and only the entries given become numbered sources. When nothing but the totals fits, the list of searches
  becomes its number. Before 0.14.3-rc.5 a result with a place did not fit a 4,096-token context (`too_large`).
- **Limits of the available queries:** a provider of the graph without a page in the scope, or without a name, is not
  counted; two providers on one host without entities of their own count as one; an organisation of the graph gives a
  host of the index its place only when the page of its name is on that host; the name index and the addresses naming a
  place are read up to 5,000 rows each (more is a lower bound); the pages naming the place are read for 120 providers
  and 3 pages each (the rest is open: located and serving are then lower bounds, the total is not affected); the direct index matches the start of words,
  not a service within a compound.

Tests: `ServiceSearchTest` and `ChatToolsTest` (`ant scoutro-rag-test`: 11 providers in the graph and the index, one over
three pages, one by the graph only, one by its page only, one only of the second service, each relation to the place,
another collection, the time limit, the tool), `test/scoutro-access/chat-servicesearch-live-smoke.py` (a house gallery,
an office located in Musterstadt by its address in the graph, eleven offices of which one on two pages, one of another
collection; the number question after strong first hits, the tool, the index's search events cold and warm; a model
that never calls a tool; all invented).


## 14. Measurement of the job and provider search (0.14.3-rc.5 and after)

**What the rc.5 acceptance showed** (a production peer: 6,844 postings, 300,677 entities, 812,178 statements, 2,180,413
evidence rows, 275,134 pages; real model): every search of the server ended at its budget of 6 s. The job search had read
1,250 postings (five pages of 250) and no longer loaded the name hits it had found: 19 of 40 postings by name in one
collection, 5 over all collections. The provider search had read 100 to 500 of 3,072 pages naming the service, a different
number in each run (270 to 328 providers), and no provider with a relation to the place.

**Where the time went** (measured on an invented stock of the same size, `SearchScaleFixture`: 6,851 postings, 250,799
entities, 537,491 statements, 2,001,365 evidence rows, 246,147 pages; rc.5 with timers added, each run a fresh process, cold
= the operating system's page cache dropped before):

| rc.5, step | jobs, all collections (cold / warm) | providers, one collection (cold / warm) |
| --- | --- | --- |
| name index (`entities` per variant, with totals) | 2.5 s / 1.3 s | 5.9 s / 4.2 s (9 searches and the offers) |
| digests: the evidence query | **47.1 s / 40.1 s** (28 pages of 250) | **15.5 s / 8.9 s** (6 batches of 100) |
| digests: entities, facts, collections | 0.4 s / 0.3 s | 0.06 s / 0.03 s |
| page text | 0.8 s / 0.6 s (34 queries, 57 pages loaded) | 2.4 s / 2.2 s (63 queries, 6,012 pages with 6.8 million characters loaded) |
| total to the end | 50.8 s / 42.2 s | 23.9 s / 15.4 s |
| as shipped (reads of at most 1.5 s, budget 6 s) | error `read_timeout`, nothing found | error `read_timeout` in the graph, nothing found |

The evidence query of a page of digests is the bottleneck: left to the planner it starts from every current page of the
graph and reads all their evidence (2 million rows) to find the evidence of 250 entities, about 1.5 s a page here and
1.2 s on the production peer (1,250 postings in 6 s). The same query with a fixed join order (the entities' statements,
their evidence, its page) takes 0.2 ms. On this stock the unchanged rc.5 does not get through its first page within a
read of 1.5 s; the totals to the end were measured with that limit lifted.

**After** (the same stock, the budget of 6 s unchanged):

| step | jobs, one collection (1,811 of 6,851 postings) | jobs, all collections | providers, one collection | providers, all collections |
| --- | --- | --- | --- | --- |
| scan / graph | 893 / 236 ms | 1,011 / 230 ms | 159 / 133 ms | 121 / 130 ms |
| names | 32 / 18 ms | 45 / 31 ms | | |
| facts check | 5 / 4 ms | 6 / 9 ms | | |
| page text / index | 190 / 131 ms (2 questions, no page loaded) | 287 / 231 ms (7 questions, no page loaded) | 103 / 99 ms (one count by host) | 81 / 96 ms |
| place | | | 233 / 93 ms | 203 / 107 ms |
| selection | 7 / 3 ms | 7 / 7 ms | 1 / 1 ms | 1 / 2 ms |
| evidence of the 6 shown | 17 / 12 ms | 12 / 11 ms | 17 / 18 ms (10 pages loaded in all) | 19 / 20 ms |
| **total** (cold / warm) | **1,174 / 435 ms** | **1,397 / 540 ms** | **523 / 352 ms** | **432 / 362 ms** |
| found / shown | 60 / 6, complete | 229 / 6, complete | 506 / 6, complete | 507 / 6, complete |

Completeness on this stock (`SearchScaleBench check`): the 229 postings of all collections are exactly the 60, 120 and 49
of the three collections; "Wie viele …" and "Zeige alle …" find the same postings; the employer with seven records has six
postings; 506 providers of which 2 are located in the town (one by the address on another page of its host than the one
naming the service), 2 serve it, 2 only mention it and 500 show no relation; all collections hold every provider of the
one; `provider_search` with "Tragwerksplanung Musterstadt" and "Tragwerksplanung in Musterstadt" gives the same result
(4,186 characters).

**Model requests at the chat endpoint** (`chat-search-measure.py`: a disposable peer, 80 invented pages, a simulated model
with a context of 131,072 tokens; request characters as the stub received them; tokens are an estimate, characters / 3):

| question | rc.5: model requests, characters (est. input tokens) | after |
| --- | --- | --- |
| "Wie viele Stellenanzeigen zu Kotlin gibt es?" | 1; 24,337 (8,112); 19 sources | **0**: answered by the server; 6 sources |
| "Zeige alle Stellenanzeigen zu Kotlin." | 1; 24,330 (8,110); 19 sources | 1; 11,313 (3,771); 6 sources |
| a list, the model calls `job_search` twice | 3; 81,678 (27,226); two searches | 3; 41,010 (13,670); both calls reuse the result |
| "Wie viele Anbieter für Tragwerksplanung gibt es in Musterstadt?" | 1; 12,528 (4,176) | **0**: answered by the server |
| "Wer prüft in Musterstadt, ob eine Wand tragend ist?" | 1; 20,559 (6,853); 10 sources, the provider search did not run (strong first hits) | 1; 11,747 (3,915); 6 providers |
| a list, the model calls `provider_search` | 2; 25,131 (8,377); the automatic search did not run | 2; 26,228 (8,742); the automatic search ran, the tool reuses it |

Of the about 11,300 characters of a request after the change, about 8,900 are the system prompt, the server rules and the
definitions of the tools; the sources are 2,400 to 2,700. Real token counts need a real endpoint: the answer's last data
line and the log now carry them when the endpoint reports them (`scoutro-usage`).

**What stays a limit:** an exact count needs every posting's facts read, as no index on fact values exists; that is the
scan above, and it grows with the number of postings. The place of a provider on its pages is read for at most 120
providers. Both are reported as lower bounds with their reason when they are reached.


Real-model check against an OpenAI-compatible endpoint (read-only, disposable DATA):

```
SCOUTRO_RAG_LLM=http://<ollama-host>:11434 SCOUTRO_RAG_MODEL=llama3.1:8b \
SCOUTRO_RAG_NUM_CTX=8192 python3 test/scoutro-rag/rag-quality.py --label real --out real.json
```
