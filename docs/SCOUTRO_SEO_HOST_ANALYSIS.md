# Scoutro SEO / Host Analysis

Implementation is additive to unmerged Citation PR #3 (`ded4328`). Navigation
reuses `header.template`, including the existing mobile toggle. Admin UI:
`ScoutroSEO_p.html`; named UI route: `seo.hostAnalysis`. English source and German
translations use the existing generated-page mechanism; other locales retain
English fallback. Translation keys are delimited so they cannot rename IDs.

## Field provenance

All metrics read the **active local collection index**, respecting its effective
schema and field aliases. No network fetch, citation-source expansion, external
state, taxonomies, configuration writes or ranking modifications occur.

| Display | Actual field(s) / operation |
| --- | --- |
| Host / URL / ID / target scope | `host_s`, `sku`, `id`, `collection_sxt` |
| Indexed URLs | Solr document count after host and collection filters; includes error records |
| Title / description / H1–H3 | `title`, `description_txt`, `h1_txt`, `h2_txt`, `h3_txt`; presence counts and stored arrays |
| Words / language | `wordcount_i` mean with measured-document count; `language_s` bounded facet |
| Depth / HTTP status / response time | `crawldepth_i` mean, `httpstatus_i` facet, `responsetime_i` mean; ms |
| Recorded load / modified | `load_date_dt`, `last_modified`; maximum stored date, not proof of completed crawl |
| Protocol | `url_protocol_s` bounded facet |
| Outgoing internal / external / nofollow | `inboundlinkscount_i`, `outboundlinkscount_i`, `linksnofollowcount_i`; measured sums |
| Incoming locally observed references | `references_i`, `references_internal_i`, `references_external_i` |
| External source hosts, per URL only | `references_exthosts_i` |
| Reference readiness | `process_sxt`, `host_extent_i`, the four reference fields |

## Reference readiness and its limits

`processed` means required fields enabled, no nonempty processing marker,
`host_extent_i >= 0`, all four counters >= 0, total = internal + external, and
external hosts <= external references. This is the existing postprocessing's
**finalized field shape**, not evidence that historical Citation was enabled.
A genuine stored zero is available only in this state. Any process marker is
`pending`; its raw counters are withheld. No finalized extent (missing/-1) and
only initial/missing zeros is `unavailable`; partial/inconsistent values are
`unknown`. Schema-disabled required fields give `unavailable` unless a known
process marker is pending. API status predicates and per-document evaluation
are tested against the same real index fixture.

No durable historical Citation provenance/success flag exists. Therefore
`historical_completeness` is always `unknown`; coverage has
`basis=finalized_reference_fields`, not "complete backlinks collected". Failed
or incomplete historical graph construction cannot be reconstructed here.
`pending_pages` is null when its marker field is disabled; `processed_pages`
and `coverage` are null when readiness cannot be measured. This feature does
not alter the crawler or add a provenance migration.

Host total/internal/external sums use only finalized **target URLs**, with
coverage/status counts alongside. No finalized targets => null sums. There is
no host-wide unique external-domain statistic: per-URL host counts overlap and
must not be summed. `host_extent_i` is a global stored successful-host extent;
it may be stale, differs from all-record `indexed_pages`, and is hidden for
narrowed target scopes. The URL DTO explicitly labels its scope.

## Auth, scope and API

The four routes and all parameters are documented in `help/ScoutroSEO_p.md`,
`docs/API.md`, `openapi.json` and `actions.json`. Admin requires existing Digest
rights. Agent equivalent routes require explicit `seo.read`; existing presets
and stored agents gain no permission. Rate limits, audit and self-authentication
reuse existing infrastructure. GET only; no transaction/mutation action.

Collection OR filters are applied to **all** host counts, lists, aggregates and
URL-detail queries before retrieval. Invisible/missing targets share 404.
Multi-collection documents appear once and report only visible assignments.
`host_extent` is omitted as null for scoped reads. Incoming values describe the
stored local observed graph (`source_scope=local_observed_graph`), not a graph
filtered by source collection: no source URL or hidden assignment is exposed.
There is no client Solr syntax or arbitrary field access. Host validation is
IDN/hostname normalization without DNS resolution. Returned index content is
untrusted data and is rendered through textContent, never innerHTML.

## Bounded query plans

- Hosts: one rows=0 terms facet on `host_s`, collection filter, prefix query,
  index order, at most 50 buckets per page; total uses `numBuckets`.
- Summary: one rows=0 JSON facet request scoped to the exact indexed host;
  filtered counts, sums, means and maxima; distributions capped at 30 values.
- Pages: one allowlisted field query, at most 100 stored rows, stable server
  sort + ID tie-breaker, bounded offset. Reference sorts filter final targets.
- Links tab: two top-five page queries. No per-row queries or full result list.
- Detail: one row queried by hash within authorized collections.

All queries use a 2000ms Solr `timeAllowed` budget. Partial results are rejected
with 503 rather than misleading totals. Facets still visit matching documents
in Solr (work scales with host/scope size); there is no Java full-index scan.
Deep offset pagination can become expensive and may time out; bounds are
10000 hosts/100000 pages. A later cursor implementation can extend deep
browsing without relaxing the fixed-query boundary. No new UI dependency.

The sole core edit extends the existing read-only dashboard exception in
`YaCyDefaultServlet` to `ScoutroSEO_p.html`, suppressing normal template-visit
registration in `server.servlets.called`/`server.servlets.submitted`. Thus even
the cold admin page request does not write navigation history/configuration.

## Verification and future rollout

`ant scoutro-agents-test` includes real embedded-Solr SEO tests. The disposable
`test/scoutro-ui/seo-live-smoke.py` seeds 30 records (two hosts/collections,
finalized values including zeros, pending/unavailable/unknown), checks read
non-mutation, English/German UI at 360/390/412/1280 px, all tabs/detail,
pagination, error visibility, markup injection and authenticated agent scopes.
Run only against its freshly marked temporary DATA. See the UI test README.

Future rollout must first review/merge PR #3 and this stacked feature, validate
the combined source, prepare a separately authorized version/image release and
then an Olares chart pointing at its immutable digest. Translation refresh
must ship the new page/header; existing index metadata remains intact. Citation
must be enabled for newly collected graph data; any recrawl of older URLs needs
separate authorization and does not run on page load. This change performs no
merge, release, registry push, Olares access or deployment.
