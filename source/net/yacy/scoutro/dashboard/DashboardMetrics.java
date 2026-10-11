/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.dashboard;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.response.ResultContext;

/** Read-only, bounded Solr aggregates. No stored documents are requested. */
public final class DashboardMetrics {
    // Same successful-URL scope as the standard Index Browser's host overview.
    public static final String INDEX_QUERY = "httpstatus_i:200 AND -failtype_s:[* TO *]";
    public static final int QUERY_BUDGET_MS = 1000;

    @FunctionalInterface
    public interface Query {
        NamedList<Object> execute(ModifiableSolrParams params) throws IOException;
    }

    public static final class Counts {
        /** -1 means unavailable, never an invented zero. */
        public final long pages;
        public final long hosts;

        public Counts(final long pages, final long hosts) {
            this.pages = pages;
            this.hosts = hosts;
        }
    }

    public static final class Snapshot {
        public final Counts index;
        public final Map<String, Counts> collections;

        Snapshot(final Counts index, final Map<String, Counts> collections) {
            this.index = index;
            this.collections = Collections.unmodifiableMap(collections);
        }
    }

    private DashboardMetrics() { }

    /** Collection names that can be written into a query (the catalog's rule). */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /**
     * The counts of the dashboard (docs/SCOUTRO_USERS_ACCESS.md: built from the catalog and the rights).
     *
     * @param collectionIds the collections with a card, from the collection catalog
     * @param scope null for the whole index (administrator), otherwise the totals count only the documents
     *        of these collections
     */
    public static Snapshot read(final Query query, final List<String> collectionIds, final List<String> scope) {
        final Map<String, Counts> collections = new LinkedHashMap<>();
        final List<String> ids = valid(collectionIds);
        final String base = scope == null ? INDEX_QUERY : INDEX_QUERY + " AND " + scopeFilter(valid(scope));
        Counts index = new Counts(-1, -1);
        try {
            // One docValues JSON-facet request for the overview and all collections.
            final ModifiableSolrParams params = parameters(base);
            final StringBuilder facets = new StringBuilder("{\"hosts\":\"unique(host_s)\"");
            for (int i = 0; i < ids.size(); i++) {
                facets.append(",\"c").append(i).append("\":{\"type\":\"query\",\"q\":\"collection_sxt:\\\"")
                        .append(ids.get(i)).append("\\\"\",\"facet\":{\"hosts\":\"unique(host_s)\"}}");
            }
            params.set("json.facet", facets.append('}').toString());
            final NamedList<Object> response = complete(query.execute(params));
            final Object facetData = response.get("facets");
            index = counts(facetData);
            for (int i = 0; i < ids.size(); i++) {
                collections.put(ids.get(i), counts(value(facetData, "c" + i)));
            }
        } catch (final IOException | RuntimeException e) {
            // Unsupported/slow facets must not take down counts or the rest of the page.
        }
        if (index.pages < 0) index = new Counts(count(query, base), -1);
        for (final String id : ids) {
            final Counts c = collections.get(id);
            if (c == null || c.pages < 0) {
                collections.put(id, new Counts(count(query, base + " AND collection_sxt:\"" + id + "\""), -1));
            }
        }
        return new Snapshot(index, collections);
    }

    private static List<String> valid(final List<String> names) {
        final List<String> out = new java.util.ArrayList<>();
        if (names != null) for (final String n : names) if (n != null && NAME.matcher(n).matches() && !out.contains(n)) out.add(n);
        return out;
    }

    /** Documents of any of the collections; no collection matches nothing. */
    static String scopeFilter(final List<String> names) {
        if (names.isEmpty()) return "-*:*";
        final StringBuilder f = new StringBuilder("collection_sxt:(");
        for (int i = 0; i < names.size(); i++) f.append(i == 0 ? "" : " OR ").append('"').append(names.get(i)).append('"');
        return f.append(')').toString();
    }

    private static ModifiableSolrParams parameters(final String q) {
        final ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("q", q);
        params.set("rows", 0);
        params.set("start", 0);
        params.set("timeAllowed", QUERY_BUDGET_MS);
        params.set("omitHeader", false);
        return params;
    }

    private static long count(final Query query, final String q) {
        try {
            final Object result = complete(query.execute(parameters(q))).get("response");
            if (result instanceof SolrDocumentList) return ((SolrDocumentList) result).getNumFound();
            if (result instanceof ResultContext) return ((ResultContext) result).getDocList().matches();
            return -1;
        } catch (final IOException | RuntimeException e) {
            return -1;
        }
    }

    private static NamedList<Object> complete(final NamedList<Object> response) throws IOException {
        if (response == null) throw new IOException("No Solr response");
        final Object header = response.get("responseHeader");
        final Object partial = value(header, "partialResults");
        if (partial != null && !"false".equals(String.valueOf(partial))) {
            throw new IOException("Incomplete Solr aggregate");
        }
        return response;
    }

    private static Counts counts(final Object data) {
        final long pages = number(value(data, "count"));
        // Solr omits nested metrics for empty buckets; an empty index has no hosts.
        return new Counts(pages, pages == 0 ? 0 : number(value(data, "hosts")));
    }

    private static long number(final Object n) {
        return n instanceof Number ? Math.max(-1, ((Number) n).longValue()) : -1;
    }

    private static Object value(final Object data, final String key) {
        if (data instanceof NamedList<?>) return ((NamedList<?>) data).get(key);
        if (data instanceof Map<?, ?>) return ((Map<?, ?>) data).get(key);
        return null;
    }

    public static double share(final long pages, final long total) {
        return pages < 0 || total < 0 ? -1 : total == 0 ? 0 : 100.0 * pages / total;
    }
}
