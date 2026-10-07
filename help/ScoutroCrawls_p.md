# Scoutro Crawls

Administrator-only native responsive page. URL and **Collection** are required.
Choose Domain or Subpath, maximum pages per domain and depth. The Collection
is chosen from a list (the collection catalog: collections of the index, the
collections created here and those of the Discovery profiles; internal
`robot_*` collections are not offered); no name is typed. **New collection**
opens a dialog with the display name, the collection ID suggested from it
(for example "Mein neues Portal" becomes `mein-neues-portal`, editable before
saving) and an optional description. The server checks the ID (format,
reserved names, duplicates regardless of case) and refuses with a message
instead of overwriting; after creation the list is reloaded and the new
collection selected. There is no `user` fallback. Page load and polling are read-only; a start
requires explicit form submission. YaCy handles robots, queues and indexing.

The page uses GET and POST `/scoutro/api/v1/collections`, GET `/scoutro/api/v1/crawls`,
GET `/scoutro/api/v1/crawls/{id}` and
POST `/scoutro/api/v1/crawls` with administrator Digest authentication and
same-origin JSON protection. POST fields are `url`, `collection`, `scope`,
`maxPages`, `depth`; native defaults are Domain, 15 pages and depth 2.
Agent equivalents use `/scoutro/api/agent/v1`, explicit grants and scope/limits.
The start mutates YaCy's crawl/index state (seed reload); it never rewrites
runtime taxonomy or creates Discovery jobs/heartbeat. An active same-host
crawl blocks a new start rather than dropping its queue.

Native starts send `Idempotency-Key`: safe repeat returns the existing start;
changed parameters or unconfirmed outcomes return 409 without a second start.
Missing/invalid Collection returns 400 before a YaCy call, a collection
outside the catalog `400 collection_unknown`; foreign agent Collection returns
403. Storage/index availability errors remain visible.

**Start result** (next to the form) shows what the backend answered: URL,
Collection, the result of the start attempt (Started; Already started, no
second start; Rejected; Unconfirmed; Failed), the current status of the crawl
profile (Running; Waiting: the YaCy crawler is paused; Finished; Profile
removed; Not started; Unknown), the crawl/profile ID, the time of the attempt
and the start time recorded by Scoutro, and for a refusal or an error an
understandable reason with the HTTP status, code and message of the backend.
YaCy reports no queue position; the page never claims one. **View crawl
status** leads to the card of this start in **Crawl status** (marked **This
start**); the result follows the card's status with every refresh. While a
start is on its way, and after it has started, **Start crawl** is disabled
until a field changes, so a double click or a second submit never sends the
request twice. After a start the address carries `?crawl=<profile id>`; a
reload reads that crawl again with `GET /scoutro/api/v1/crawls/{id}`.

Cards expose recorded URL, Collection, Scope, start time, YaCy status and known
loaded-page counter. Missing legacy metadata, end time and percentage stay
unknown/null. A terminated profile alone does not prove indexing. The Analyze
host link returns to SEO with Collection and rechecks indexed state there.
No automatic crawl starts on following that link.
**Crawl again** in the crawl report and in the host's **Crawl status** tab
prefills this form the same way (the host with the scheme of its last crawl, otherwise `https://`, and the collection).

Complete endpoints, permissions, status/error contract, durable journal,
automation guidance and CLI: [Scoutro crawl flow](../docs/SCOUTRO_CRAWL_FLOW.md).
