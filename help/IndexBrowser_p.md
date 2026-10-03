# Index Browser

Read-only URL browsing through `GET /scoutro/api/v1/index/browse`; administrator authentication is required for index data. Enter a literal host or URL substring and optionally an exact Collection. Both filters intersect on the server. No Solr syntax, URL fetch, commit or automatic crawl is accepted by browsing.

`IndexBrowser_p.html?collection=edelsenior-web&q=host.example` initializes both filters; Dashboard collection links use this parameter. Active filters remain visible and can be changed, removed or reset. Unknown valid collection names return an empty result; malformed names show an error without falling back to the whole index. Results include URL, host, visible collection memberships and HTTP status; pagination is bounded to 100 rows and offset 10000. Mobile uses the same rows as labelled cards.

Machine clients use `scoutroctl index browse [QUERY] --collection NAME`, or the Bearer agent path `/scoutro/api/agent/v1/index/browse` with the explicit `index.browse` grant. Collection scope always applies, including membership labels. Existing presets are unchanged. Index maintenance remains on the index administration pages; filtered browsing rejects legacy global mutation parameters.
