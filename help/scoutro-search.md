---
page: htroot/scoutro-search.html
help: help/scoutro-search.md
title: Search
package: search
access: search right or guest (protected access mode)
kind: page
backend_java: source/net/yacy/htroot/ScoutroSearch.java
---

# Search

`scoutro-search.html` searches the pages of the signed-in identity's
collections (or of the collections released for guests). It is the search of
the protected access mode: the header search and the start page lead
accounts here, while the native YaCy search stays with the administrator.

## What you can do here

| Element | What it does |
|---|---|
| Search words | Up to 200 characters. Write no `collection:` (choose the collection in the list) and no `/heuristic`. |
| Collection | *All my collections* or one of them. The list comes from `GET /scoutro/api/v1/collections` and shows only the identity's collections. |
| Results | Title (link to the page), address and snippet; 10 per page with *Previous* and *Next*. With several collections the number is approximate and paging ends after 100 results. |

The page shows its states: searching, no results, an error with its reason
(collection not available, `collection:` in the words, session ended).
Links with `?q=…&collection=…` open a search directly.

## Access and safety

The server decides the scope of every request
(docs/SCOUTRO_USERS_ACCESS.md, section 6): without a collection all of the
identity's collections are searched (never the whole index unless it has all
collections); a foreign or unknown collection answers
`403 collection_not_allowed`, `collection:` in the words
`400 query_modifier_not_allowed` (also `/heuristic`, which would search other
services and index their pages with the administrator's rights; for every
caller without the administrator role), and the peer-to-peer search
(`source=network`) `403`. The page stores nothing; the collection context of
the navigation lasts for the browser tab.

## API

| Request | Purpose |
|---|---|
| `GET /scoutro/api/v1/search?q=…&collection=…&limit=…&offset=…` | Local search within the identity's collections (search right or guest). |
| `GET /scoutro/api/v1/collections` | The identity's collections for the list. |
