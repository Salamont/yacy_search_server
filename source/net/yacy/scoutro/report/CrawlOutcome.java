/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.report;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;

import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.search.schema.CollectionSchema;

/**
 * The crawl section of one finished crawl: page counts from YaCy's index for the host
 * and collection, split at the crawl start, plus exclusions YaCy does not store.
 * One bounded rows=0 facet request per crawl; partial or incomplete answers fail.
 */
public final class CrawlOutcome {
    /** Documents of host and collection loaded since the crawl start, including error documents. */
    public static final String PAGES_TOTAL = "pages_total";
    public static final String PAGES_OK = "pages_ok";
    public static final String PAGES_REDIRECT = "pages_redirect";
    public static final String PAGES_CLIENT_ERROR = "pages_client_error";
    public static final String PAGES_SERVER_ERROR = "pages_server_error";
    /** YaCy fail type {@code excl}: robots.txt, redirects and other final exclusions stored in the index. */
    public static final String PAGES_EXCLUDED = "pages_excluded";
    /** YaCy fail type {@code fail} without crawler redirects: network errors and unwanted HTTP status codes. */
    public static final String PAGES_FAILED = "pages_failed";
    public static final String PAGES_ROBOTS = "pages_robots";
    /** Documents with a load date before the crawl start: not reloaded by this crawl, not deleted. */
    public static final String PAGES_NOT_RELOADED = "pages_not_reloaded";
    public static final String PAGES_NOT_RELOADED_OK = "pages_not_reloaded_ok";
    public static final String DEPTH = "depth";
    public static final String MAX_PAGES = "max_pages";
    public static final String OUTCOME = "outcome";
    public static final String COVERAGE = "coverage";
    /** Label: "http" or "https", the scheme of the crawl's start URL. */
    public static final String SCHEME = "scheme";
    /**
     * A redirect the crawler followed is stored as fail type {@code fail}, HTTP status -1
     * and this reason prefix (CrawlQueues/HTTPLoader); it is a redirect, not a failure.
     */
    static final String CRAWLER_REDIRECT = "TEMPORARY_NETWORK_FAILURE cannot load: load error - CRAWLER Redirect of URL=";

    @FunctionalInterface
    public interface Query {
        NamedList<Object> execute(ModifiableSolrParams params) throws IOException;
    }

    /** Crawl facts known before the index is read. */
    public static final class Crawl {
        public final String crawlId, startMarker, host, collection;
        public final long startedAt;
        public final Long endedAt;
        public final int depth, maxPages;
        /** "http", "https" or null if unknown. */
        public final String scheme;

        public Crawl(final String crawlId, final String startMarker, final String host, final String collection,
                final long startedAt, final Long endedAt, final int depth, final int maxPages) {
            this(crawlId, startMarker, host, collection, startedAt, endedAt, depth, maxPages, null);
        }

        public Crawl(final String crawlId, final String startMarker, final String host, final String collection,
                final long startedAt, final Long endedAt, final int depth, final int maxPages, final String scheme) {
            this.crawlId = crawlId;
            this.startMarker = startMarker == null || startMarker.isEmpty() ? null : startMarker;
            this.host = HostNames.normalize(host);
            this.collection = DomainTable.collection(collection);
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.depth = depth;
            this.maxPages = maxPages;
            this.scheme = "http".equals(scheme) || "https".equals(scheme) ? scheme : null;
        }

        /** The scheme of a start URL, or null. */
        public static String scheme(final String url) {
            if (url == null) return null;
            final String u = url.trim().toLowerCase(java.util.Locale.ROOT);
            return u.startsWith("https://") ? "https" : u.startsWith("http://") ? "http" : null;
        }

        /** Identity-only snapshot, used to test whether this crawl is already recorded. */
        CrawlSnapshot identity() {
            return CrawlSnapshot.builder(this.crawlId, this.startedAt).startMarker(this.startMarker).build();
        }
    }

    private static final CollectionSchema[] REQUIRED = {
            CollectionSchema.host_s, CollectionSchema.collection_sxt, CollectionSchema.load_date_dt,
            CollectionSchema.httpstatus_i, CollectionSchema.failtype_s};

    private final Query query;
    private final Predicate<CollectionSchema> enabled;

    public CrawlOutcome(final Query query, final Predicate<CollectionSchema> enabled) {
        this.query = query;
        this.enabled = enabled;
    }

    /** Page counters of one crawl, or an empty map when the index schema lacks a required field. */
    public Map<String, Long> pages(final Crawl crawl) throws IOException {
        final Map<String, Long> out = new LinkedHashMap<>();
        for (final CollectionSchema field : REQUIRED) if (!this.enabled.test(field)) return out;
        final boolean reasons = this.enabled.test(CollectionSchema.failreason_s);
        final String start = Instant.ofEpochMilli(crawl.startedAt).toString();
        final String redirected = "_query_:\"{!prefix f=" + f(CollectionSchema.failreason_s) + "}" + CRAWLER_REDIRECT + "\"";
        final String status3xx = f(CollectionSchema.httpstatus_i) + ":[300 TO 399]", failed = f(CollectionSchema.failtype_s) + ":fail";
        final JsonObject since = new JsonObject()
                .put(PAGES_OK, query(ok()))
                .put(PAGES_REDIRECT, query(reasons ? status3xx + " OR " + redirected : status3xx))
                .put(PAGES_CLIENT_ERROR, query(f(CollectionSchema.httpstatus_i) + ":[400 TO 499]"))
                .put(PAGES_SERVER_ERROR, query(f(CollectionSchema.httpstatus_i) + ":[500 TO 599]"))
                .put(PAGES_EXCLUDED, query(f(CollectionSchema.failtype_s) + ":excl"))
                .put(PAGES_FAILED, query(reasons ? failed + " AND -" + redirected : failed));
        if (reasons) since.put(PAGES_ROBOTS, query(f(CollectionSchema.failreason_s) + ":FINAL_ROBOTS_RULE*"));
        final JsonObject facets = new JsonObject()
                .put("since", query(f(CollectionSchema.load_date_dt) + ":[" + start + " TO *]").put("facet", since))
                .put("before", query(f(CollectionSchema.load_date_dt) + ":[* TO " + start + "}")
                        .put("facet", new JsonObject().put(PAGES_NOT_RELOADED_OK, query(ok()))));
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("q", f(CollectionSchema.host_s) + ":" + quoted(crawl.host) + " AND "
                + f(CollectionSchema.collection_sxt) + ":" + quoted(crawl.collection));
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
        final boolean empty = count(data) == 0; // Solr omits sub-facets of an empty result
        final Object sinceData = value(data, "since"), beforeData = value(data, "before");
        final long total = empty ? 0 : count(sinceData), before = empty ? 0 : count(beforeData);
        out.put(PAGES_TOTAL, total);
        for (final String key : since.keySet()) out.put(key, total == 0 ? 0 : count(value(sinceData, key)));
        out.put(PAGES_NOT_RELOADED, before);
        out.put(PAGES_NOT_RELOADED_OK, before == 0 ? 0 : count(value(beforeData, PAGES_NOT_RELOADED_OK)));
        return out;
    }

    /**
     * indexed: pages loaded without failures; partial: pages loaded with failures;
     * not_reloaded: nothing loaded but earlier pages are indexed; not_indexed: no
     * indexed page at all; unknown: page counts unavailable.
     */
    public static String outcome(final Map<String, Long> pages) {
        if (pages.isEmpty()) return "unknown";
        if (pages.getOrDefault(PAGES_OK, 0L) > 0) return pages.getOrDefault(PAGES_FAILED, 0L) > 0 ? "partial" : "indexed";
        return pages.getOrDefault(PAGES_NOT_RELOADED_OK, 0L) > 0 ? "not_reloaded" : "not_indexed";
    }

    /** The complete crawl section to store for this crawl. */
    public static CrawlSnapshot snapshot(final Crawl crawl, final Map<String, Long> pages,
            final ExclusionTracker.Tally exclusions, final String job, final String discoveryDomain) {
        final CrawlSnapshot.Builder b = CrawlSnapshot.builder(crawl.crawlId, crawl.startedAt).startMarker(crawl.startMarker)
                .endedAt(crawl.endedAt).job(job).discoveryDomain(discoveryDomain);
        for (final Map.Entry<String, Long> e : pages.entrySet()) b.counter(e.getKey(), e.getValue());
        if (crawl.depth >= 0) b.counter(DEPTH, crawl.depth);
        if (crawl.maxPages >= 0) b.counter(MAX_PAGES, crawl.maxPages);
        if (exclusions != null) for (final Map.Entry<String, Long> e : exclusions.counts.entrySet()) b.counter(e.getKey(), e.getValue());
        b.label(OUTCOME, outcome(pages));
        b.label(COVERAGE, exclusions != null && exclusions.complete ? "complete" : "partial");
        if (crawl.scheme != null) b.label(SCHEME, crawl.scheme);
        return b.build();
    }

    private String ok() {
        return f(CollectionSchema.httpstatus_i) + ":200 AND -" + f(CollectionSchema.failtype_s) + ":[* TO *]";
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

    private static Object value(final Object data, final String key) {
        if (data instanceof NamedList<?>) return ((NamedList<?>) data).get(key);
        if (data instanceof Map<?, ?>) return ((Map<?, ?>) data).get(key);
        return null;
    }

    private static long count(final Object bucket) throws IOException {
        final Object n = value(bucket, "count");
        if (!(n instanceof Number)) throw new IOException("Incomplete index response");
        return ((Number) n).longValue();
    }
}
