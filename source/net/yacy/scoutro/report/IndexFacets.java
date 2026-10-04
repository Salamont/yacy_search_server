/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.report;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;

import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.search.schema.CollectionSchema;

/**
 * The current page state of a collection or of one host in it, read live from YaCy's
 * index: one bounded rows=0 facet request; partial or incomplete answers fail. Facets
 * whose schema field is disabled are omitted and listed in {@code unavailable}.
 */
public final class IndexFacets {
    static final int STATUS_LIMIT = 30, TYPE_LIMIT = 20, DEPTH_LIMIT = 50, DUPLICATE_LIMIT = 1000;

    private final CrawlOutcome.Query query;
    private final Predicate<CollectionSchema> enabled;

    public IndexFacets(final CrawlOutcome.Query query, final Predicate<CollectionSchema> enabled) {
        this.query = query;
        this.enabled = enabled;
    }

    /**
     * @param host  null for the whole collection
     * @param since crawl start for {@code not_reloaded} (documents loaded before it), or null
     */
    public JsonObject read(final String collection, final String host, final Long since) throws IOException {
        DomainTable.collection(collection);
        final String h = host == null ? null : HostNames.normalize(host);
        final JsonArray unavailable = new JsonArray();
        for (final CollectionSchema required : new CollectionSchema[] {CollectionSchema.collection_sxt, CollectionSchema.host_s})
            if (!this.enabled.test(required)) throw new IOException("index schema lacks " + required.getSolrFieldName());
        final boolean status = on(CollectionSchema.httpstatus_i, unavailable), fail = on(CollectionSchema.failtype_s, unavailable);
        final boolean loaded = on(CollectionSchema.load_date_dt, unavailable);
        final JsonObject facets = new JsonObject();
        if (status && fail) {
            final JsonObject ok = query(f(CollectionSchema.httpstatus_i) + ":200 AND -" + f(CollectionSchema.failtype_s) + ":[* TO *]");
            final JsonObject inner = new JsonObject();
            if (on(CollectionSchema.content_type, unavailable))
                inner.put("content_type", terms(CollectionSchema.content_type, TYPE_LIMIT));
            if (on(CollectionSchema.crawldepth_i, unavailable))
                inner.put("depth", terms(CollectionSchema.crawldepth_i, DEPTH_LIMIT).put("sort", "index asc"));
            if (on(CollectionSchema.exact_signature_l, unavailable)) inner.put("exact", duplicates(CollectionSchema.exact_signature_l));
            if (on(CollectionSchema.fuzzy_signature_l, unavailable)) inner.put("similar", duplicates(CollectionSchema.fuzzy_signature_l));
            if (!inner.isEmpty()) ok.put("facet", inner);
            facets.put("ok", ok);
        } else {
            unavailable.put("ok");
        }
        if (status) facets.put("status", terms(CollectionSchema.httpstatus_i, STATUS_LIMIT));
        if (fail) facets.put("failtype", terms(CollectionSchema.failtype_s, 5));
        if (h == null) facets.put("hosts", "unique(" + f(CollectionSchema.host_s) + ")");
        if (loaded) {
            facets.put("oldest", "min(" + f(CollectionSchema.load_date_dt) + ")");
            facets.put("newest", "max(" + f(CollectionSchema.load_date_dt) + ")");
            if (since != null)
                facets.put("before", query(f(CollectionSchema.load_date_dt) + ":[* TO " + Instant.ofEpochMilli(since) + "}"));
        }
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("q", f(CollectionSchema.collection_sxt) + ":" + quoted(collection)
                + (h == null ? "" : " AND " + f(CollectionSchema.host_s) + ":" + quoted(h)));
        p.set("defType", "lucene");
        p.set("rows", 0);
        p.set("timeAllowed", 2000);
        p.set("omitHeader", false);
        p.set("json.facet", facets.toString());
        final NamedList<Object> result = this.query.execute(p);
        if (result == null) throw new IOException("No index response");
        final Object partial = value(result.get("responseHeader"), "partialResults");
        if (partial != null && !"false".equals(String.valueOf(partial))) throw new IOException("Partial index response");
        final Object data = result.get("facets");
        final long documents = count(data);
        final boolean empty = documents == 0; // Solr omits sub-facets of an empty result
        final JsonObject out = new JsonObject().put("collection", collection).put("host", h == null ? JsonObject.NULL : h)
                .put("documents", documents);
        if (facets.has("ok")) {
            final Object ok = value(data, "ok");
            final long okCount = empty ? 0 : count(ok);
            out.put("ok", okCount);
            final boolean none = okCount == 0;
            if (facets.getJSONObject("ok").has("facet")) {
                final JsonObject inner = facets.getJSONObject("ok").getJSONObject("facet");
                if (inner.has("content_type")) out.put("content_type", buckets(none ? null : value(ok, "content_type")));
                if (inner.has("depth")) out.put("depth", buckets(none ? null : value(ok, "depth")));
                final JsonObject dup = new JsonObject();
                if (inner.has("exact")) duplicates(dup, "exact", none ? null : value(ok, "exact"));
                if (inner.has("similar")) duplicates(dup, "similar", none ? null : value(ok, "similar"));
                if (!dup.isEmpty()) out.put("duplicates", dup);
            }
        }
        if (facets.has("status")) out.put("http_status", buckets(empty ? null : value(data, "status")));
        if (facets.has("failtype")) {
            final JsonObject types = new JsonObject().put("excl", 0).put("fail", 0);
            for (final Object bucket : list(empty ? null : value(data, "failtype")))
                types.put(String.valueOf(value(bucket, "val")), number(value(bucket, "count")));
            out.put("fail_type", types);
        }
        if (h == null) out.put("hosts", empty ? 0 : number(value(data, "hosts")));
        if (loaded) {
            out.put("oldest", empty ? JsonObject.NULL : iso(value(data, "oldest")));
            out.put("newest", empty ? JsonObject.NULL : iso(value(data, "newest")));
            if (since != null) out.put("not_reloaded", empty ? 0 : count(value(data, "before")));
        }
        out.put("unavailable", unavailable);
        return out;
    }

    private boolean on(final CollectionSchema field, final JsonArray unavailable) {
        if (this.enabled.test(field)) return true;
        unavailable.put(field.getSolrFieldName());
        return false;
    }

    private static JsonObject terms(final CollectionSchema field, final int limit) {
        return new JsonObject().put("type", "terms").put("field", f(field)).put("limit", limit);
    }

    private static JsonObject duplicates(final CollectionSchema field) {
        return terms(field, DUPLICATE_LIMIT).put("mincount", 2);
    }

    /**
     * Groups of at least two documents with the same signature. Solr's numBuckets ignores
     * mincount, so the groups are counted from the returned buckets; at the limit the
     * numbers are lower bounds and marked truncated.
     */
    private static void duplicates(final JsonObject out, final String name, final Object facet) {
        long urls = 0;
        final Collection<?> buckets = list(facet);
        for (final Object bucket : buckets) urls += number(value(bucket, "count"));
        out.put(name + "_groups", buckets.size()).put(name + "_urls", urls)
                .put(name + "_truncated", buckets.size() >= DUPLICATE_LIMIT);
    }

    private static JsonObject query(final String q) {
        return new JsonObject().put("type", "query").put("q", q);
    }

    private static String f(final CollectionSchema field) {
        return field.getSolrFieldName();
    }

    private static String quoted(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static Object value(final Object data, final String key) {
        if (data instanceof NamedList<?>) return ((NamedList<?>) data).get(key);
        if (data instanceof Map<?, ?>) return ((Map<?, ?>) data).get(key);
        return null;
    }

    private static Collection<?> list(final Object facet) {
        final Object buckets = value(facet, "buckets");
        return buckets instanceof Collection<?> ? (Collection<?>) buckets : java.util.List.of();
    }

    private static JsonArray buckets(final Object facet) {
        final JsonArray out = new JsonArray();
        for (final Object bucket : list(facet)) {
            final Object v = value(bucket, "val");
            out.put(new JsonObject().put("value", v instanceof Number ? v : String.valueOf(v)).put("count", number(value(bucket, "count"))));
        }
        return out;
    }

    private static long count(final Object bucket) throws IOException {
        final Object n = value(bucket, "count");
        if (!(n instanceof Number)) throw new IOException("Incomplete index response");
        return ((Number) n).longValue();
    }

    private static long number(final Object n) {
        return n instanceof Number ? ((Number) n).longValue() : 0;
    }

    private static Object iso(final Object value) {
        if (value instanceof Date) return ((Date) value).toInstant().toString();
        if (value instanceof Number) return Instant.ofEpochMilli(((Number) value).longValue()).toString();
        return value == null ? JsonObject.NULL : String.valueOf(value);
    }
}
