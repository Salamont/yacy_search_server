/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.json.JSONArray;
import org.json.JSONObject;
import net.yacy.scoutro.dashboard.DashboardMetrics;
import net.yacy.search.Switchboard;

/** One bounded local aggregate, with a server-resolved collection scope. No document retrieval. */
final class IndexMetrics {
    @FunctionalInterface interface Query { NamedList<Object> execute(ModifiableSolrParams params) throws IOException; }
    private final Query query;
    IndexMetrics(final Query query) { this.query = query; }
    static IndexMetrics current() throws ApiException {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null || sb.index == null || sb.index.fulltext().getDefaultConnector() == null)
            throw new ApiException(503, "index_unavailable", "System index data is unavailable.");
        return new IndexMetrics(p -> sb.index.fulltext().getDefaultConnector().getResponseByParams(p).getResponse());
    }
    JSONObject read(final Map<String, String> input, final List<String> collections) throws ApiException {
        for (final String key : input.keySet()) if (!key.equals("collection"))
            throw ApiException.invalid(key, "Only the collection parameter is accepted; no Solr query syntax.");
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("q", "*:*"); p.set("rows", 0); p.set("timeAllowed", 2000); p.set("omitHeader", false); p.set("distrib", false);
        if (collections != null) {
            final StringBuilder fq = new StringBuilder("collection_sxt:(");
            for (final String c : collections) {
                if (!c.matches("[A-Za-z0-9_-]{1,64}")) throw ApiException.invalid("collection", "Invalid collection.");
                if (fq.length() > 16) fq.append(" OR ");
                fq.append('"').append(c).append('"');
            }
            p.set("fq", collections.isEmpty() ? "(*:* AND -*:*)" : fq.append(')').toString());
        }
        p.set("json.facet", Json.obj("withoutStatus", Json.obj("type", "query", "q", "-httpstatus_i:[* TO *]"),
                "successful", Json.obj("type", "query", "q", DashboardMetrics.INDEX_QUERY, "facet", Json.obj("hosts", "unique(host_s)",
                        "withoutHost", Json.obj("type", "query", "q", "-host_s:[* TO *]")))).toString());
        try {
            final NamedList<Object> r = this.query.execute(p);
            final Object partial = value(r == null ? null : r.get("responseHeader"), "partialResults");
            if (r == null || (partial != null && !"false".equals(String.valueOf(partial)))) throw new IOException("Partial aggregate");
            final Object facets = r.get("facets"), successful = value(facets, "successful");
            final long documents = count(value(facets, "count"));
            // Solr omits nested aggregates for a proven empty bucket.
            if (documents > 0 && count(value(value(facets, "withoutStatus"), "count")) > 0)
                throw new IOException("Records without HTTP status cannot support complete page counts");
            final long pages = documents == 0 ? 0 : count(value(successful, "count"));
            if (pages > 0 && count(value(value(successful, "withoutHost"), "count")) > 0)
                throw new IOException("Successful records without host cannot support complete host counts");
            final long hosts = pages == 0 ? 0 : count(value(successful, "hosts"));
            return Json.obj("observedAt", Instant.now().toString(), "collections", collections == null ? JSONObject.NULL : new JSONArray(collections),
                    "documents", documents, "pages", pages, "hosts", hosts,
                    "definition", "documents: all indexed records; pages/hosts: HTTP 200 without failtype; hosts are distinct host_s, not registrable domains");
        } catch (final IOException | RuntimeException e) {
            throw new ApiException(503, "index_unavailable", "Complete system index data is unavailable; no estimate is returned.");
        }
    }
    private static long count(final Object value) throws IOException {
        if (!(value instanceof Number) || ((Number) value).longValue() < 0) throw new IOException("Missing count");
        return ((Number) value).longValue();
    }
    private static Object value(final Object object, final String key) {
        if (object instanceof NamedList) return ((NamedList<?>) object).get(key);
        if (object instanceof Map) return ((Map<?, ?>) object).get(key);
        return null;
    }
}
