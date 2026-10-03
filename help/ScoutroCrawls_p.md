# Scoutro Crawls

Administrator-only native responsive page. URL and **Collection** are required.
Choose Domain or Subpath, maximum pages per domain and depth. Collection
suggestions come from the index; administrators can type a new valid name.
There is no `user` fallback. Page load and polling are read-only; a start
requires explicit form submission. YaCy handles robots, queues and indexing.

The page uses `/scoutro/api/v1/collections`, GET `/scoutro/api/v1/crawls` and
POST `/scoutro/api/v1/crawls` with administrator Digest authentication and
same-origin JSON protection. POST fields are `url`, `collection`, `scope`,
`maxPages`, `depth`; native defaults are Domain, 15 pages and depth 2.
Agent equivalents use `/scoutro/api/agent/v1`, explicit grants and scope/limits.
The start mutates YaCy's crawl/index state (seed reload); it never rewrites
runtime taxonomy or creates Discovery jobs/heartbeat. An active same-host
crawl blocks a new start rather than dropping its queue.

Native starts send `Idempotency-Key`: safe repeat returns the existing start;
changed parameters or unconfirmed outcomes return 409 without a second start.
Missing/invalid Collection returns 400 before a YaCy call; foreign agent
Collection returns 403. Storage/index availability errors remain visible.

Cards expose recorded URL, Collection, Scope, start time, YaCy status and known
loaded-page counter. Missing legacy metadata, end time and percentage stay
unknown/null. A terminated profile alone does not prove indexing. The Analyze
host link returns to SEO with Collection and rechecks indexed state there.
No automatic crawl starts on following that link.

Complete endpoints, permissions, status/error contract, durable journal,
automation guidance and CLI: [Scoutro crawl flow](../docs/SCOUTRO_CRAWL_FLOW.md).
