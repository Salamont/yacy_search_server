# Index Browser

Read-only URL browsing through `GET /scoutro/api/v1/index/browse`; administrator authentication is required for index data. Enter a literal host or URL substring and optionally choose a Collection from the list (all collections of the index, alphabetically; no free text). Both filters intersect on the server. No Solr syntax, URL fetch, commit or automatic crawl is accepted by browsing.

`IndexBrowser_p.html?collection=my-portal-web&q=host.example` initializes both filters; Dashboard collection links use this parameter. Active filters remain visible and can be changed, removed or reset. A collection of the link that is not in the list falls back to all collections and the page says so; the API still refuses a malformed name (400) and never widens a filter by itself. Results include URL, host, visible collection memberships and HTTP status; pagination is bounded to 100 rows and offset 10000. Mobile uses the same rows as labelled cards.

Each domain card links to **Knowledge** (`ScoutroKnowledge_p.html?view=objects&host=…&collection=…`, the objects the knowledge graph found on the domain) and each URL row to the page's source view (`?view=source&doc=<id>`, what the graph holds from that page). Both views compute everything only from documents of the chosen collection; while the graph is disabled they say so.

Machine clients use `scoutroctl index browse [QUERY] --collection NAME`, or the Bearer agent path `/scoutro/api/agent/v1/index/browse` with the explicit `index.browse` grant. Collection scope always applies, including membership labels. Existing presets are unchanged. Index maintenance remains on the index administration pages; filtered browsing rejects legacy global mutation parameters.

## Scoutro accounts (protected access mode)

In the protected access mode accounts with the **read** right (Research,
Operator) open this page as *Research → Websites*. Every list, count and
domain comes from `/scoutro/api/v1/index/*` and is limited to the account's
collections; a `collection` parameter outside them answers
`403 collection_not_allowed`. The domain export (`/index/domains/export`)
also needs the **export** right; without it the *Export* button is hidden.
The legacy maintenance actions of this page (`load`, `delete`,
`deleteLoadErrors`, `reload404`, `addtoblacklist`) stay with the
administrator. See [SCOUTRO_USERS_ACCESS.md](../docs/SCOUTRO_USERS_ACCESS.md) for roles, permissions and the protected access mode.
