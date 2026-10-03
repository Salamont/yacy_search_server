/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.api;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.search.Switchboard;
import net.yacy.search.schema.CollectionConfiguration;
import net.yacy.search.schema.CollectionSchema;

import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.IDN;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded, read-only analysis of visible target documents; never resolves or fetches URLs. */
final class SeoAnalysis {
    @FunctionalInterface
    interface Query {
        NamedList<Object> execute(ModifiableSolrParams params) throws IOException;
    }

    private final CollectionConfiguration schema;
    private final Query query;
    private static final Map<String, CollectionSchema> FIELDS = new LinkedHashMap<>();
    private static final Map<String, CollectionSchema> SORTS = new LinkedHashMap<>();
    private static final List<CollectionSchema> REFERENCES =
            Arrays.asList(
                    CollectionSchema.references_i,
                    CollectionSchema.references_internal_i,
                    CollectionSchema.references_external_i,
                    CollectionSchema.references_exthosts_i);

    static {
        FIELDS.put("title", CollectionSchema.title);
        FIELDS.put("description", CollectionSchema.description_txt);
        FIELDS.put("h1", CollectionSchema.h1_txt);
        FIELDS.put("h2", CollectionSchema.h2_txt);
        FIELDS.put("h3", CollectionSchema.h3_txt);
        FIELDS.put("word_count", CollectionSchema.wordcount_i);
        FIELDS.put("language", CollectionSchema.language_s);
        FIELDS.put("crawl_depth", CollectionSchema.crawldepth_i);
        FIELDS.put("http_status", CollectionSchema.httpstatus_i);
        FIELDS.put("response_time_ms", CollectionSchema.responsetime_i);
        FIELDS.put("load_date", CollectionSchema.load_date_dt);
        FIELDS.put("last_modified", CollectionSchema.last_modified);
        FIELDS.put("protocol", CollectionSchema.url_protocol_s);
        FIELDS.put("outgoing_internal", CollectionSchema.inboundlinkscount_i);
        FIELDS.put("outgoing_external", CollectionSchema.outboundlinkscount_i);
        FIELDS.put("nofollow", CollectionSchema.linksnofollowcount_i);
        SORTS.put("url", CollectionSchema.sku);
        SORTS.put("word_count", CollectionSchema.wordcount_i);
        SORTS.put("crawl_depth", CollectionSchema.crawldepth_i);
        SORTS.put("load_date", CollectionSchema.load_date_dt);
        SORTS.put("references_internal", CollectionSchema.references_internal_i);
        SORTS.put("references_external", CollectionSchema.references_external_i);
        SORTS.put("external_hosts", CollectionSchema.references_exthosts_i);
    }

    SeoAnalysis(final CollectionConfiguration schema, final Query query) {
        this.schema = schema;
        this.query = query;
    }

    static SeoAnalysis current() throws ApiException {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null || sb.index == null || sb.index.fulltext().getDefaultConnector() == null)
            throw new ApiException(503, "index_unavailable", "The local index is unavailable.");
        return new SeoAnalysis(
                sb.index.fulltext().getDefaultConfiguration(),
                p ->
                        sb.index
                                .fulltext()
                                .getDefaultConnector()
                                .getResponseByParams(p)
                                .getResponse());
    }

    /** Segments after seo. The caller has already authenticated/authorized GET and collections. */
    JSONObject route(
            final List<String> path,
            final Map<String, String> input,
            final List<String> collections)
            throws ApiException {
        if (path.size() == 1 && "hosts".equals(path.get(0))) {
            parameters(input, "q", "limit", "offset", "collection");
            return hosts(input, collections);
        }
        if (path.size() == 2 && "hosts".equals(path.get(0))) {
            parameters(input, "collection");
            return summary(host(path.get(1)), collections);
        }
        if (path.size() == 3 && "hosts".equals(path.get(0)) && "pages".equals(path.get(2))) {
            parameters(input, "sort", "order", "offset", "limit", "citation", "collection");
            return pages(host(path.get(1)), input, collections);
        }
        if (path.size() == 2 && "pages".equals(path.get(0))) {
            parameters(input, "collection");
            if (!path.get(1).matches("[A-Za-z0-9_-]{12}"))
                throw ApiException.invalid("id", "Invalid URL identifier.");
            final ModifiableSolrParams p =
                    params(f(CollectionSchema.id) + ":" + quoted(path.get(1)), collections);
            p.set("rows", 1);
            p.set("fl", fields());
            final SolrDocumentList docs = documents(execute(p));
            if (docs.isEmpty()) throw missing();
            return page(docs.get(0), collections);
        }
        throw new ApiException(404, "not_found", "Unknown host analysis path.");
    }

    static List<String> adminCollections(final Map<String, String> q) throws ApiException {
        final String collection = q.get("collection");
        if (collection == null || collection.isEmpty()) return null;
        checkCollection(collection);
        return Arrays.asList(collection);
    }

    private static void checkCollection(final String c) throws ApiException {
        if (c == null || !c.matches("[A-Za-z0-9_-]{1,64}"))
            throw ApiException.invalid("collection", "Invalid collection.");
    }

    private static void parameters(final Map<String, String> input, final String... allowed)
            throws ApiException {
        final Set<String> names = Set.of(allowed);
        for (final String key : input.keySet())
            if (!names.contains(key))
                throw ApiException.invalid(key, "Unsupported analysis parameter.");
    }

    static String host(final String input) throws ApiException {
        try {
            final String normalized =
                    IDN.toASCII(input.trim(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (normalized.length() > 253
                    || !normalized.matches(
                            "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
                    || normalized.matches("[0-9.]+")) throw new IllegalArgumentException();
            return normalized;
        } catch (final IllegalArgumentException | NullPointerException e) {
            throw ApiException.invalid("host", "Enter a hostname without protocol, path or port.");
        }
    }

    private String f(final CollectionSchema field) {
        return field.getSolrFieldName();
    }

    private boolean enabled(final CollectionSchema field) {
        return schema != null && (schema.isEmpty() || schema.contains(field));
    }

    private boolean citationFields() {
        return enabled(CollectionSchema.process_sxt)
                && enabled(CollectionSchema.host_extent_i)
                && REFERENCES.stream().allMatch(this::enabled);
    }

    private static String quoted(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String pendingQuery() {
        return f(CollectionSchema.process_sxt) + ":[* TO *]";
    }

    private String processedQuery() {
        if (!citationFields()) return "(*:* AND -*:*)";
        final StringBuilder q =
                new StringBuilder(
                        "-"
                                + pendingQuery()
                                + " AND "
                                + f(CollectionSchema.host_extent_i)
                                + ":[0 TO *]");
        for (final CollectionSchema ref : REFERENCES)
            q.append(" AND ").append(f(ref)).append(":[0 TO *]");
        q.append(" AND _query_:")
                .append(
                        quoted(
                                "{!frange l=0 u=0}sub("
                                        + f(REFERENCES.get(0))
                                        + ",sum("
                                        + f(REFERENCES.get(1))
                                        + ","
                                        + f(REFERENCES.get(2))
                                        + "))"));
        q.append(" AND _query_:")
                .append(
                        quoted(
                                "{!frange l=0}sub("
                                        + f(REFERENCES.get(2))
                                        + ","
                                        + f(REFERENCES.get(3))
                                        + ")"));
        return q.toString();
    }

    private String unavailableQuery() {
        if (!citationFields())
            return enabled(CollectionSchema.process_sxt) ? "-" + pendingQuery() : "*:*";
        final String extent = f(CollectionSchema.host_extent_i);
        final StringBuilder q =
                new StringBuilder(
                        "-"
                                + pendingQuery()
                                + " AND ((*:* AND -"
                                + extent
                                + ":[* TO *]) OR "
                                + extent
                                + ":\"-1\")");
        for (final CollectionSchema ref : REFERENCES)
            q.append(" AND ((*:* AND -")
                    .append(f(ref))
                    .append(":[* TO *]) OR ")
                    .append(f(ref))
                    .append(":0)");
        return q.toString();
    }

    private ModifiableSolrParams params(final String q, final List<String> collections)
            throws ApiException {
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("q", q);
        p.set("defType", "lucene");
        p.set("rows", 0);
        p.set("timeAllowed", 2000);
        p.set("omitHeader", false);
        if (collections != null) {
            final List<String> values = new ArrayList<>();
            for (final String c : collections) {
                checkCollection(c);
                values.add(quoted(c));
            }
            p.add(
                    "fq",
                    values.isEmpty()
                            ? "(*:* AND -*:*)"
                            : f(CollectionSchema.collection_sxt)
                                    + ":("
                                    + String.join(" OR ", values)
                                    + ")");
        }
        return p;
    }

    private NamedList<Object> execute(final ModifiableSolrParams p) throws ApiException {
        try {
            final NamedList<Object> result = query.execute(p);
            final Object partial = value(result.get("responseHeader"), "partialResults");
            if (partial != null && !"false".equals(String.valueOf(partial)))
                throw new IOException("Partial host analysis response");
            return result;
        } catch (final IOException | RuntimeException e) {
            ConcurrentLog.warn("SCOUTRO-SEO", "Read-only index analysis failed", e);
            throw new ApiException(
                    503,
                    "index_unavailable",
                    "Index analysis is temporarily unavailable; retry later.");
        }
    }

    private JSONObject hosts(final Map<String, String> q, final List<String> collections)
            throws ApiException {
        final String prefix = q.getOrDefault("q", "").toLowerCase(Locale.ROOT);
        if (prefix.length() > 253 || !prefix.matches("[a-z0-9.-]*"))
            throw ApiException.invalid("q", "Invalid hostname prefix.");
        final int limit = number(q, "limit", 20, 1, 50), offset = number(q, "offset", 0, 0, 10000);
        final ModifiableSolrParams p =
                params(
                        prefix.isEmpty()
                                ? "*:*"
                                : "{!prefix f=" + f(CollectionSchema.host_s) + "}" + prefix,
                        collections);
        p.set(
                "json.facet",
                Json.obj(
                                "hosts",
                                Json.obj(
                                        "type",
                                        "terms",
                                        "field",
                                        f(CollectionSchema.host_s),
                                        "limit",
                                        limit,
                                        "offset",
                                        offset,
                                        "numBuckets",
                                        true,
                                        "sort",
                                        "index asc"))
                        .toString());
        final Object facets = execute(p).get("facets");
        if (!(value(facets, "count") instanceof Number))
            throw new ApiException(503, "index_unavailable", "Incomplete index response.");
        final Object bucket = value(facets, "hosts");
        final Object total = count(facets) == 0 ? 0 : value(bucket, "numBuckets");
        if (!(total instanceof Number))
            throw new ApiException(503, "index_unavailable", "Incomplete index response.");
        return Json.obj(
                "items",
                buckets(bucket, "host", "pages"),
                "total",
                total,
                "offset",
                offset,
                "limit",
                limit);
    }

    private JSONObject summary(final String host, final List<String> collections)
            throws ApiException {
        final ModifiableSolrParams p =
                params(f(CollectionSchema.host_s) + ":" + quoted(host), collections);
        final JSONObject facets =
                Json.obj(
                        "processed",
                        Json.obj(
                                "type",
                                "query",
                                "q",
                                processedQuery(),
                                "facet",
                                citationFields()
                                        ? Json.obj(
                                                "total",
                                                "sum(" + f(REFERENCES.get(0)) + ")",
                                                "internal",
                                                "sum(" + f(REFERENCES.get(1)) + ")",
                                                "external",
                                                "sum(" + f(REFERENCES.get(2)) + ")")
                                        : Json.obj()),
                        "unavailable",
                        Json.obj("type", "query", "q", unavailableQuery()));
        if (enabled(CollectionSchema.process_sxt))
            Json.put(facets, "pending", Json.obj("type", "query", "q", pendingQuery()));
        for (final String key : Arrays.asList("title", "description", "h1", "h2", "h3"))
            if (enabled(FIELDS.get(key)))
                Json.put(
                        facets,
                        key,
                        Json.obj("type", "query", "q", f(FIELDS.get(key)) + ":[* TO *]"));
        for (final String key :
                Arrays.asList(
                        "word_count",
                        "crawl_depth",
                        "response_time_ms",
                        "outgoing_internal",
                        "outgoing_external",
                        "nofollow"))
            if (enabled(FIELDS.get(key)))
                Json.put(
                        facets,
                        key,
                        Json.obj(
                                "type",
                                "query",
                                "q",
                                f(FIELDS.get(key)) + ":[0 TO *]",
                                "facet",
                                Json.obj(
                                        "average",
                                        "avg(" + f(FIELDS.get(key)) + ")",
                                        "sum",
                                        "sum(" + f(FIELDS.get(key)) + ")")));
        for (final String key : Arrays.asList("language", "http_status", "protocol"))
            if (enabled(FIELDS.get(key)))
                Json.put(
                        facets,
                        key,
                        Json.obj("type", "terms", "field", f(FIELDS.get(key)), "limit", 30));
        for (final String key : Arrays.asList("load_date", "last_modified"))
            if (enabled(FIELDS.get(key)))
                Json.put(
                        facets,
                        key,
                        Json.obj(
                                "type",
                                "query",
                                "q",
                                f(FIELDS.get(key)) + ":[* TO *]",
                                "facet",
                                Json.obj("latest", "max(" + f(FIELDS.get(key)) + ")")));
        p.set("json.facet", facets.toString());
        final Object data = execute(p).get("facets");
        if (!(value(data, "count") instanceof Number))
            throw new ApiException(503, "index_unavailable", "Incomplete index response.");
        final long total = count(data);
        if (total == 0) throw missing();
        final long processed = count(value(data, "processed")),
                pending = enabled(CollectionSchema.process_sxt) ? count(value(data, "pending")) : 0;
        final long unavailable = count(value(data, "unavailable"));
        final JSONObject citation =
                Json.obj(
                        "processed_pages",
                        citationFields() ? processed : null,
                        "pending_pages",
                        enabled(CollectionSchema.process_sxt) ? pending : null,
                        "unavailable_pages",
                        unavailable,
                        "unknown_pages",
                        total - processed - pending - unavailable,
                        "coverage",
                        citationFields() ? (double) processed / total : null,
                        "references_total",
                        processed > 0 ? value(value(data, "processed"), "total") : null,
                        "references_internal",
                        processed > 0 ? value(value(data, "processed"), "internal") : null,
                        "references_external",
                        processed > 0 ? value(value(data, "processed"), "external") : null,
                        "basis",
                        "finalized_reference_fields",
                        "historical_completeness",
                        "unknown",
                        "source_scope",
                        "local_observed_graph");
        final JSONObject content = Json.obj(),
                crawl = Json.obj(),
                technology = Json.obj(),
                availability = Json.obj();
        for (final Map.Entry<String, CollectionSchema> entry : FIELDS.entrySet())
            Json.put(availability, entry.getKey(), enabled(entry.getValue()));
        for (final String key : Arrays.asList("title", "description", "h1", "h2", "h3"))
            Json.put(
                    content,
                    key + "_pages",
                    enabled(FIELDS.get(key)) ? count(value(data, key)) : null);
        Json.put(content, "word_count", metric(data, "word_count", "average"));
        Json.put(
                content,
                "languages",
                enabled(CollectionSchema.language_s)
                        ? buckets(value(data, "language"), "value", "pages")
                        : null);
        Json.put(crawl, "depth", metric(data, "crawl_depth", "average"));
        Json.put(crawl, "response_time_ms", metric(data, "response_time_ms", "average"));
        for (final String key : Arrays.asList("load_date", "last_modified"))
            Json.put(crawl, key, iso(value(value(data, key), "latest")));
        for (final String key : Arrays.asList("http_status", "protocol"))
            Json.put(
                    technology,
                    key,
                    enabled(FIELDS.get(key)) ? buckets(value(data, key), "value", "pages") : null);
        for (final String key : Arrays.asList("outgoing_internal", "outgoing_external", "nofollow"))
            Json.put(technology, key, metric(data, key, "sum"));
        return Json.obj(
                "host",
                host,
                "indexed_pages",
                total,
                "citation",
                citation,
                "content",
                content,
                "crawl",
                crawl,
                "technology",
                technology,
                "fields",
                availability);
    }

    private JSONObject metric(final Object data, final String key, final String name) {
        final Object bucket = value(data, key);
        final long measured = count(bucket);
        return Json.obj(
                "value",
                enabled(FIELDS.get(key)) && measured > 0 ? value(bucket, name) : null,
                "measured_pages",
                enabled(FIELDS.get(key)) ? measured : null);
    }

    private JSONObject pages(
            final String host, final Map<String, String> input, final List<String> collections)
            throws ApiException {
        final String sort = input.getOrDefault("sort", "url"),
                order = input.getOrDefault("order", "asc");
        if (!SORTS.containsKey(sort) || !enabled(SORTS.get(sort)))
            throw ApiException.invalid("sort", "Unsupported or unrecorded sort field.");
        if (!order.equals("asc") && !order.equals("desc"))
            throw ApiException.invalid("order", "Order must be asc or desc.");
        final boolean referenceSort =
                sort.startsWith("references_") || sort.equals("external_hosts");
        final String filter = input.getOrDefault("citation", referenceSort ? "processed" : "all");
        if (!Set.of("all", "processed", "pending", "unavailable", "unknown").contains(filter))
            throw ApiException.invalid("citation", "Unknown reference status.");
        if (referenceSort && (!filter.equals("processed") || !citationFields()))
            throw ApiException.invalid(
                    "citation", "Reference sorting requires finalized reference values.");
        final int offset = number(input, "offset", 0, 0, 100000),
                limit = number(input, "limit", 25, 1, 100);
        final ModifiableSolrParams p =
                params(f(CollectionSchema.host_s) + ":" + quoted(host), collections);
        if (filter.equals("processed")) p.add("fq", processedQuery());
        if (filter.equals("pending"))
            p.add("fq", enabled(CollectionSchema.process_sxt) ? pendingQuery() : "(*:* AND -*:*)");
        if (filter.equals("unavailable")) p.add("fq", unavailableQuery());
        if (filter.equals("unknown")) {
            p.add("fq", "-(" + processedQuery() + ")");
            p.add("fq", "-(" + unavailableQuery() + ")");
            if (enabled(CollectionSchema.process_sxt)) p.add("fq", "-" + pendingQuery());
        }
        p.set("start", offset);
        p.set("rows", limit);
        p.set("sort", f(SORTS.get(sort)) + " " + order + "," + f(CollectionSchema.id) + " asc");
        p.set("fl", fields());
        final SolrDocumentList docs = documents(execute(p));
        final JSONArray items = Json.arr();
        for (final SolrDocument doc : docs) items.put(page(doc, collections));
        return Json.obj(
                "host",
                host,
                "items",
                items,
                "total",
                docs.getNumFound(),
                "offset",
                offset,
                "limit",
                limit,
                "sort",
                sort,
                "order",
                order,
                "citation_filter",
                filter);
    }

    private String fields() {
        final List<String> fields = new ArrayList<>();
        for (final CollectionSchema field : CollectionSchema.values()) {
            if ((FIELDS.containsValue(field)
                            || REFERENCES.contains(field)
                            || Arrays.asList(
                                            CollectionSchema.id,
                                            CollectionSchema.sku,
                                            CollectionSchema.host_s,
                                            CollectionSchema.host_extent_i,
                                            CollectionSchema.process_sxt,
                                            CollectionSchema.collection_sxt)
                                    .contains(field))
                    && enabled(field)) fields.add(f(field));
        }
        return String.join(",", fields);
    }

    private Object datum(final SolrDocument doc, final CollectionSchema field) {
        return enabled(field) ? doc.getFieldValue(f(field)) : null;
    }

    private JSONObject page(final SolrDocument doc, final List<String> collections) {
        final String status = status(doc);
        final JSONObject citation =
                Json.obj(
                        "status",
                        status,
                        "historical_completeness",
                        "unknown",
                        "source_scope",
                        "local_observed_graph");
        final String[] keys = {
            "references_total", "references_internal", "references_external", "external_hosts"
        };
        for (int i = 0; i < REFERENCES.size(); i++)
            Json.put(
                    citation,
                    keys[i],
                    status.equals("processed") ? datum(doc, REFERENCES.get(i)) : null);
        Json.put(
                citation,
                "host_extent",
                status.equals("processed") && collections == null
                        ? datum(doc, CollectionSchema.host_extent_i)
                        : null);
        Json.put(
                citation,
                "host_extent_scope",
                collections == null ? "whole_local_index" : "not_exposed");
        final JSONObject content = Json.obj(), crawl = Json.obj(), outgoing = Json.obj();
        for (final String key :
                Arrays.asList("title", "description", "h1", "h2", "h3", "word_count", "language"))
            Json.put(content, key, datum(doc, FIELDS.get(key)));
        for (final String key :
                Arrays.asList(
                        "http_status",
                        "crawl_depth",
                        "response_time_ms",
                        "load_date",
                        "last_modified"))
            Json.put(
                    crawl,
                    key,
                    key.equals("load_date") || key.equals("last_modified")
                            ? iso(datum(doc, FIELDS.get(key)))
                            : datum(doc, FIELDS.get(key)));
        for (final String key : Arrays.asList("outgoing_internal", "outgoing_external", "nofollow"))
            Json.put(outgoing, key, datum(doc, FIELDS.get(key)));
        final JSONArray visibleCollections = Json.arr();
        final Collection<Object> stored = doc.getFieldValues(f(CollectionSchema.collection_sxt));
        if (stored != null)
            for (final Object c : stored)
                if (collections == null || collections.contains(String.valueOf(c)))
                    visibleCollections.put(c);
        return Json.obj(
                "id",
                datum(doc, CollectionSchema.id),
                "url",
                datum(doc, CollectionSchema.sku),
                "host",
                datum(doc, CollectionSchema.host_s),
                "content",
                content,
                "crawl",
                crawl,
                "outgoing",
                outgoing,
                "citation",
                citation,
                "collections",
                visibleCollections);
    }

    private String status(final SolrDocument doc) {
        final Object marker = datum(doc, CollectionSchema.process_sxt);
        if (marker instanceof Collection<?> && !((Collection<?>) marker).isEmpty()
                || marker instanceof String && !((String) marker).isEmpty()) return "pending";
        if (!citationFields()) return "unavailable";
        final Object extent = datum(doc, CollectionSchema.host_extent_i);
        final long[] refs = new long[4];
        boolean complete = extent instanceof Number && ((Number) extent).longValue() >= 0,
                empty =
                        extent == null
                                || extent instanceof Number && ((Number) extent).longValue() == -1;
        for (int i = 0; i < 4; i++) {
            final Object value = datum(doc, REFERENCES.get(i));
            refs[i] = value instanceof Number ? ((Number) value).longValue() : -1;
            complete &= refs[i] >= 0;
            empty &= value == null || refs[i] == 0;
        }
        if (complete && refs[0] == refs[1] + refs[2] && refs[3] <= refs[2]) return "processed";
        return empty ? "unavailable" : "unknown";
    }

    private static SolrDocumentList documents(final NamedList<Object> data) throws ApiException {
        final Object docs = data.get("response");
        if (!(docs instanceof SolrDocumentList))
            throw new ApiException(503, "index_unavailable", "Incomplete index response.");
        return (SolrDocumentList) docs;
    }

    private static Object value(final Object data, final String key) {
        if (data instanceof NamedList<?>) return ((NamedList<?>) data).get(key);
        if (data instanceof Map<?, ?>) return ((Map<?, ?>) data).get(key);
        return null;
    }

    private static long count(final Object bucket) {
        final Object n = value(bucket, "count");
        return n instanceof Number ? ((Number) n).longValue() : 0;
    }

    private static JSONArray buckets(final Object data, final String label, final String size) {
        final JSONArray list = Json.arr();
        final Object items = value(data, "buckets");
        if (items instanceof Collection<?>)
            for (final Object bucket : (Collection<?>) items)
                list.put(Json.obj(label, value(bucket, "val"), size, value(bucket, "count")));
        return list;
    }

    private static Object iso(final Object value) {
        if (value instanceof Date) return ((Date) value).toInstant().toString();
        if (value instanceof Number)
            return Instant.ofEpochMilli(((Number) value).longValue()).toString();
        return value;
    }

    private static int number(
            final Map<String, String> q,
            final String key,
            final int fallback,
            final int min,
            final int max)
            throws ApiException {
        try {
            final int n = Integer.parseInt(q.getOrDefault(key, Integer.toString(fallback)));
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (final NumberFormatException e) {
            throw ApiException.invalid(key, "Analysis pagination is outside the allowed range.");
        }
    }

    private static ApiException missing() {
        return new ApiException(404, "not_found", "No visible indexed host or page was found.");
    }
}
