---
page: htroot/yacychat.html
help: help/yacychat.md
title: Chat
package: core-search-public
access: public
kind: ui-page
backend_java: source/net/yacy/htroot/yacychat.java
---

# Chat

## Purpose

Chat provides a conversational interface around YaCy search or local features.

Use it when the user wants an interactive dialogue instead of a classic result page.

## What You Can Do Here

- Chat provides a conversational interface around YaCy search or local features.
- Choose query, target, content type, or integration options according to the user intent.
- Keep ordinary read-only viewing separate from authenticated actions that alter stored data.

## Page Architecture

Public search pages turn request parameters into result lists, previews, snippets, feeds, or integration fragments. They are mostly read-oriented, but some result actions can bookmark, recommend, blacklist, or delete references when authenticated.

| Control | Meaning | Values or examples |
| --- | --- | --- |
| `userInput` | no search, allow attachments. | Checkbox/boolean; present usually means enabled. |
| `sendButton` | no search, allow attachments. | `Send` |

## Correct Use

Start from the user's information need. Use query text, content type, collection, URL filters, and pagination to narrow results. Do not mix ordinary search with authenticated result actions unless the user explicitly asks to modify stored data.

## Access And Safety

The page is normally public or read-only, unless the peer is configured to require authentication for all pages.

## Automation And API

Page backend: `source/net/yacy/htroot/yacychat.java`.

| Endpoint | Method | Access | Backend |
| --- | --- | --- | --- |
| `/yacychat.html` | `GET` | public or page-dependent | `source/net/yacy/htroot/yacychat.java` |

### Parameter Guide

The table explains values that an agent or script must set deliberately. Parameters not relevant to a task should be omitted or left at the page default. Low-level generated parameters are omitted when they are only meaningful inside the rendered YaCy form.

| Parameter | Meaning and valid values | Care |
| --- | --- | --- |
| `userInput` | no search, allow attachments. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `sendButton` | no search, allow attachments. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |

Example request shape:

```http
GET /yacychat.html?userInput=...&searchButton=...&addFileButton=...&fileInput=...&sendButton=...
```

## What To Expect

Expect rendered search or content output: result lists, snippets, previews, redirects, widgets, or fragments. If output is empty, check whether the index contains matching documents before changing query syntax.

## Scoutro: prompt and errors

The server puts its own base system prompt with the security rules first; the
operator prompt (`ai.system-prompt`) and any client system prompt follow with
lower priority. Search results and attached texts are sent as untrusted data
between random `DATA-…-BEGIN`/`DATA-…-END` markers. Errors are shown per code:
no YaCy administrator permission, blocked by the AI Shield, rate limit, no chat
model configured, LLM endpoint not reachable, LLM endpoint rejected the
credentials. Details: `docs/SCOUTRO_LLM_SECURITY.md`.

## Scoutro: collection

The *Collection* list limits the search to one collection: "All collections"
(the whole index) first, then the collections you may use, alphabetically.
Local and administrator access sees every collection of the index, a guest
only those the administrator released on the AI Shield page. There is no free
text: `yacychat.html?collection=NAME` and the remembered choice apply only to a
listed collection, otherwise "All collections" is used. A new collection
appears in the list as soon as the index has pages of it.

## Scoutro: knowledge graph sources

When the knowledge graph runs, an answer can also cite facts Scoutro recorded
about organisations and facilities, for example "[4] Scoutro knowledge graph:
Muster Pflege gGmbH". These sources are marked **Scoutro knowledge graph** in
the source list and link the page the facts were read from; uncertain facts
are marked as such in what the model sees. They are facts from your index,
not knowledge of the language model. They come only from the collection you
chose (or from every collection the graph follows), only for local and
administrator use, and never for a search across the YaCy network. If the
graph is slow or off, the answer uses the search results alone. Settings:
`scoutro.kg.chat.*`, see [Knowledge graph](ScoutroKnowledge_p.md).

## Scoutro: tools of the chat

When the administrator released them on the Tools page (`ai.tools.search`,
`ai.tools.kg_search`, `ai.tools.kg_entity`, `ai.tools.kg_evidence`) and the
chat model supports tool calls, the model can search the index again when the
first results are weak and read the knowledge graph: entities by name, the
facts and relations of one entity, the source pages of one fact. The chat
shows each call under the answer. The tools read only what the chat itself may
read: the chosen collection, an account's collection, or the whole local index
for local and administrator use; never another collection and never the YaCy
network. Pages from tool results appear in the source list with the next
numbers. Limits per answer: 8 rounds, 3 searches, 4 calls of each graph tool,
10 calls and 20 seconds in total.

Job questions: when you ask for job postings and the index search finds only
company pages or nothing, Scoutro searches the job postings of its knowledge
graph itself (with `kg_search` released, in the same collections) and lists
them first as sources "job posting …" with the page they were read from. A
posting that shows only some of your subjects is marked as a partial match.
Whether a posting is still open is not checked. A follow-up such as "Such noch
mal" searches again with the subject of your earlier question.

A cited number whose source text does not contain the statement in front of
it is shown dotted, with the note "The text of the cited source does not
contain the statement in front of it". This check compares words; it is a hint
to read the source, not a proof in either direction. Details:
`docs/SCOUTRO_RAG_QUALITY.md`, sections 6 and 11.

## Related Pages

- Related search work usually continues on `yacysearch.html`, `index.html`, `ViewFile.html`, quick crawl, or the search integration pages.

Scoutro system questions (DE/EN) use authorized structured Actions before RAG or model selection. Host/page counts, crawls, Discovery, collections and host analysis work without Function Calling. System data needs the administrator role or an explicitly granted agent token; missing rights/data produce a clear error without substitute web results. `collection` in the chat JSON scopes index/SEO reads. Details, API parameters, JSON/SSE replies and automation: [System questions and MCP](../docs/SCOUTRO_SYSTEM_QUESTIONS_MCP.md).

System answers retain structured facts in API metadata and show a concise readable summary in the chat. Mobile fieldsets/composer wrap without widening the page; no conversation storage logic changes.

## Scoutro accounts (protected access mode)

`/v1/chat/completions` and this page need the **chat** right. A signed-in
account without the administrator role chats only within its collections,
whatever the AI Shield admits for its address (also on localhost): the page
lists only its collections; without *all collections* there is no "All
collections" entry and a request without a collection uses its only
collection or answers `400 collection_required`; a foreign collection
answers `403 collection_not_allowed`. Accounts never use the peer-to-peer
network search, and system questions answer `403 system_forbidden`. The
model's tools (released on the Tools page) work only within that scope: see
"Scoutro: tools of the chat" below. An AI Shield guest gets neither the
`search` tool nor the knowledge graph tools; the other released tools stay
available.
See [SCOUTRO_USERS_ACCESS.md](../docs/SCOUTRO_USERS_ACCESS.md) for roles, permissions and the protected access mode.
