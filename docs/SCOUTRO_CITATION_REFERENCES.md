# Citation and reference postprocessing

YaCy's existing Citation index records locally observed incoming URL references.
It works without Solr Webgraph. WebStructure is a separate, bounded host graph
and is not a substitute for Citation. These data describe the crawl graph known
to the local index, not every backlink on the web.

## Configuration and schema

The repository defaults remain opt-in:

```properties
core.service.citation.tmp = false
core.service.webgraph.tmp = false
```

For Citation-only operation, an administrator can choose:

```properties
core.service.citation.tmp = true
core.service.webgraph.tmp = false
```

`IndexFederated_p.html` provides the existing protected configuration form.
Its `setcitation` POST requires admin access and the transaction token; send
both service choices deliberately. It connects/disconnects Citation live.
Editing `DATA/SETTINGS/yacy.conf` offline instead requires restarting YaCy.
Startup and network initialization also call `Segment.connectCitation()`.

Connecting Citation enables and persists only these six required fields in the
effective `DATA/SETTINGS/solr.collection.schema`:

- `process_sxt`
- `references_i`
- `references_internal_i`
- `references_external_i`
- `references_exthosts_i`
- `host_extent_i`

Missing and explicitly disabled declarations are repaired. Existing enabled
fields and aliases are preserved; an unchanged selection is not rewritten.
In the current default selection the five reference/extent fields are already
enabled, so only `process_sxt` changes. Citation disabled on a fresh/default
installation leaves the selection untouched. An empty all-fields selection
keeps its existing semantics rather than being narrowed to these six fields.

The outgoing-link inputs are already mandatory YaCy fields:
`inboundlinks_protocol_sxt`, `inboundlinks_urlstub_sxt`,
`outboundlinks_protocol_sxt`, `outboundlinks_urlstub_sxt`.
The normal schema validation ensures these inputs and URL/host/status metadata.
No CitationRank, Webgraph, Canonical, hreflang or additional copycount fields are
automatically activated. No ranking settings are changed.

The helper reports a schema write failure and restores the newly changed
in-memory declarations; Citation is not connected by that failed call. Startup
retains its existing exception logging/handling. Operators must address the
reported persistence error before relying on new reference data.

## Field meanings

| Field | Meaning |
|---|---|
| `references_i` | Known incoming references to this URL, internal plus external |
| `references_internal_i` | Incoming references with the same YaCy hosthash |
| `references_external_i` | Incoming references with a different YaCy hosthash |
| `references_exthosts_i` | Distinct external source hosthashes referencing this URL |
| `host_extent_i` | HTTP-200 documents of this YaCy hosthash at postprocessing time |
| `process_sxt` | Pending normal YaCy postprocessing operations |
| `inboundlinkscount_i` | Outgoing internal/same-domain links (`md.llocal()`); **not incoming backlinks** |
| `outboundlinkscount_i` | Outgoing external links; not incoming references |

Hosthash identity includes host, protocol and port. www/non-www and subdomains
have different hashes; HTTP/HTTPS and differing ports may also be external
according to this existing definition. Do not reinterpret those counters as
root-domain totals. A per-URL external-host count cannot be summed to obtain a
host-wide number of unique websites.

## Pipeline and lifecycle

1. The HTTP loader/parser produces document metadata and internal/external
   outgoing URL lists. Existing robots, crawl limits and security remain intact.
2. `CollectionConfiguration.yacy2solr()` creates a `CITATION` processing tag when
   Citation is connected and references are enabled. CitationRank fields are
   not required. Existing `UNIQUE` tags are unchanged.
3. `Segment.storeDocument()` stores the URL document and outgoing references
   in the existing Citation index. The initial reference enrichment can still
   see an incomplete graph; `host_extent_i = -1` is a temporary placeholder.
4. Normal `cleanupJob`/`CollectionConfiguration.postprocessing()` fetches pending
   documents. It partitions by `responsetime_i` and also includes documents
   where that field is absent.
5. `ReferenceReport` reads the Citation reverse index. Normal
   `postprocessing_references()` writes the four counters and a real host
   extent. HTTP-200 host extent is global to that hosthash in the active index,
   not a collection-filtered count.
6. Successful normal updates remove `process_sxt` and the harvesting marker.
   Partial and full update paths retain document Collection assignments.

Pending Solr tags and Citation files persist across restart. A resumed normal
cleanup can finalize them without starting another crawl. If Citation and
Webgraph are disconnected, the existing postprocessing gate does not finalize
the tags; reconnect the intended service before expecting completion.

This fix does not redesign existing YaCy error handling or introduce retries,
new workers or forced cleanup. Schema connection failures are reported;
existing index/write errors remain subject to normal YaCy logging and recovery.
An operator should verify that markers drain, counters are populated and no
`ambiguous collection document count` is logged before trusting the run.

With active existing Duplicate/Unique fields, ordinary `UNIQUE` postprocessing
can also update those flags. This change does not activate extra Duplicate,
Canonical or CitationRank functionality or change search ranking profiles.

## Structured query regression

The pending-document query often begins with LocalParams:

```text
{!cache=false}process_sxt:[* TO *]
```

Previously `AbstractSolrConnector.getSolrQuery()` forced `defType=edismax` and
`qf=text_t^1.0` for multi-result fetches. Count used a different parser, so count
and fetch could disagree. The missing-response-time partition was affected too.

The connector and `SolrSelectServlet` now recognize leading LocalParams, remove
only their leading whitespace and leave parser selection to Solr/the caller.
Ordinary multi-result text queries keep the existing eDisMax behavior.
Explicit sort/boost/parser parameters remain supported. Servlet ranking filter
defaults are appended to existing `fq` filters instead of replacing them, so a
Collection scope remains effective. No new public query parameters or Solr
proxy endpoints are introduced.

## Existing documents and later deployment

This is not a backfill or a data migration. Connecting Citation does not
invent reference edges for documents crawled while Citation was disabled.
Existing `references_* = 0` or `host_extent_i = -1` without a processing marker
are not retroactively repaired just by enabling fields. Newly indexed pages
and existing valid pending markers can use the normal pipeline.

Complete references for previously indexed pages require a later controlled
recrawl through the existing crawler. Cached raw content is not generally
available when `storeHTCache=off` / `cachePolicy=nocache`. No migration crawler,
automatic recrawl or production operation is included here.

A later deployment needs a separately authorized source/image release
containing this fix commit. No new release tag, registry image or immutable
digest is produced by this PR. Keep the existing DATA volume and explicitly
choose Citation on / Webgraph off if that is the intended deployment.
The required schema selection is then repaired at Citation connection; no
large schema expansion or index reset is needed. An image upgrade normally
restarts the process; a live form-based service change does not require one.

After a later authorized deployment, validate effective schema and flags,
Citation persistence, a small isolated crawl, references against known source
URLs, the empty response-time bucket, marker drain, host extents, collection
filters and ordinary text/Agent search. Keep any historical backfill/recrawl
as a separate explicit operation.

## Tests

```sh
ant citation-postprocessing-test
ant scoutro-agents-test scoutro-dashboard-test jetty12-server-test locale-refresh-test
ant test
ant all dist
python3 test/scoutro-api/citation-live-smoke.py
python3 test/scoutro-ui/dashboard-live-smoke.py
```

The focused target uses a real HTML parser, a temporary Citation index and
temporary embedded collection1 **without a Webgraph core**. It verifies count,
fetch, concurrent IDs/prefetch, single/multiple collections, missing response
time, exact reference values, partial/full finalization, restart, disabled
Citation, minimal schema repair and persistence failures. It makes no HTTP
requests and starts no crawler, scheduler or automation jobs.

The HTTP smoke creates NEW temporary DATA, simulates a missing marker declaration,
and checks the existing Search API, `site:`, `/date`, HTML search and a scoped
Bearer Agent created through the real wizard. Its fixture network permits offline
`.example` metadata and disables online snippet verification. Production network,
authentication, crawling and scope policies are not changed. It accepts no target
URL or existing DATA path, and removes its disposable peer in a finally block.
