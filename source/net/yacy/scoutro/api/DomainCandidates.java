/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Read-only: no fetch, no crawl, no LLM, no commit. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.net.IDN;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.search.Switchboard;

/**
 * Indexed hosts consolidated per host and collection ({@link DomainCandidate}), for the Index
 * Browser and the domain export.
 * <p>
 * One JSON facet request returns a page of hosts (sorted by name or page count) with their
 * collections, page counts and latest load dates. A second, grouped request per collection of
 * the page returns the representative page of each host: lowest crawl depth, https before http,
 * shortest URL, preferring HTTP 200. Both requests use the same literal host filter and
 * collection filter, are bounded and fail closed on partial results. The export walks all hosts
 * with a cursor on the host name, a page at a time, and streams each page before reading the
 * next one, so the index is never loaded into memory.
 */
final class DomainCandidates {

    static final int PAGE_DEFAULT = 25, PAGE_MAX = 100, EXPORT_PAGE = 200, GROUP_DOCS = 5, MAX_COLLECTIONS = 100;
    private static final int TIME_ALLOWED_MS = 2000;
    private static final String REPRESENTATIVE_FIELDS = "sku,host_s,title,description_txt,url_protocol_s,httpstatus_i";

    @FunctionalInterface interface Query { NamedList<Object> execute(ModifiableSolrParams p) throws IOException; }

    /** Receives the export: begin, items page by page, end (also after a failure). */
    interface Sink {
        void begin(Filter filter, long generatedAt) throws IOException;
        void item(DomainCandidate candidate) throws IOException;
        void flush() throws IOException;
        void end(long count, boolean complete) throws IOException;
    }

    /** Validated request: literal host part, collections (null: all), sort. */
    static final class Filter {
        final String host;
        final List<String> collections;
        final String sort;
        Filter(final String host, final List<String> collections, final String sort) {
            this.host = host; this.collections = collections; this.sort = sort;
        }
        String collection() { return this.collections == null || this.collections.size() != 1 ? null : this.collections.get(0); }
        JSONObject json() {
            final JSONObject o = new JSONObject(true);
            Json.put(o, "q", this.host); Json.put(o, "collection", collection()); Json.put(o, "sort", this.sort);
            return o;
        }
    }

    /** One page of host buckets turned into candidates. */
    static final class Page {
        final List<DomainCandidate> items;
        final int hosts;
        final String lastHost;
        final Long totalHosts, total;
        Page(final List<DomainCandidate> items, final int hosts, final String lastHost, final Long totalHosts, final Long total) {
            this.items = items; this.hosts = hosts; this.lastHost = lastHost; this.totalHosts = totalHosts; this.total = total;
        }
    }

    private final Query query;
    private final DomainEnrichment enrichment;
    private final LongSupplier clock;

    DomainCandidates(final Query query, final DomainEnrichment enrichment, final LongSupplier clock) {
        this.query = query; this.enrichment = enrichment; this.clock = clock;
    }

    static DomainCandidates current() throws ApiException {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null || sb.index == null || sb.index.fulltext().getDefaultConnector() == null)
            throw new ApiException(503, "index_unavailable", "The local index is unavailable.");
        return new DomainCandidates(p -> sb.index.fulltext().getDefaultConnector().getResponseByParams(p).getResponse(),
                DomainEnrichment.current(), System::currentTimeMillis);
    }

    // ------------------------------------------------------------------ requests

    static Filter filter(final Map<String, String> input, final List<String> collections, final Set<String> allowed)
            throws ApiException {
        for (final String key : input.keySet())
            if (!allowed.contains(key)) throw ApiException.invalid(key, "Unsupported domain browser parameter.");
        final String sort = input.getOrDefault("sort", "host");
        if (!"host".equals(sort) && !"pages".equals(sort)) throw ApiException.invalid("sort", "Sort by host or pages.");
        if (collections != null)
            for (final String c : collections)
                if (c == null || !c.matches("[A-Za-z0-9_-]{1,64}")) throw ApiException.invalid("collection", "Invalid collection.");
        return new Filter(hostPart(input.get("q")), collections, sort);
    }

    /** Literal host or part of a host; a pasted URL is reduced to its host. Never a query language. */
    static String hostPart(final String raw) throws ApiException {
        final String text = raw == null ? "" : raw.trim();
        if (text.length() > 250 || text.codePoints().anyMatch(Character::isISOControl))
            throw ApiException.invalid("q", "Host search must contain at most 250 characters without controls.");
        if (text.isEmpty()) return "";
        String h = text;
        final int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        for (final char stop : new char[] {'/', '?', '#'}) {
            final int i = h.indexOf(stop);
            if (i >= 0) h = h.substring(0, i);
        }
        final int at = h.lastIndexOf('@');
        if (at >= 0) h = h.substring(at + 1);
        h = h.replaceFirst(":\\d{1,5}$", "").toLowerCase(Locale.ROOT);
        try {
            h = IDN.toASCII(h, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (final IllegalArgumentException e) {
            throw ApiException.invalid("q", "Host search accepts host names, parts of host names or URLs.");
        }
        if (!h.matches("[a-z0-9._-]*")) throw ApiException.invalid("q", "Host search accepts host names, parts of host names or URLs.");
        return h;
    }

    /** One page for the Index Browser. */
    JSONObject page(final Map<String, String> input, final List<String> collections) throws ApiException {
        final Filter f = filter(input, collections, Set.of("q", "collection", "sort", "offset", "limit"));
        final int offset = number(input, "offset", 0, 0, 10000), limit = number(input, "limit", PAGE_DEFAULT, 1, PAGE_MAX);
        final DomainEnrichment.Session session = this.enrichment.open();
        final Page page = hostPage(f, offset, limit, null, true, session);
        final JSONObject out = new JSONObject(true);
        Json.put(out, "schema", DomainCandidate.SCHEMA);
        Json.put(out, "generated_at", DomainCandidate.iso(this.clock.getAsLong()));
        Json.put(out, "filter", f.json());
        Json.put(out, "offset", offset); Json.put(out, "limit", limit);
        Json.put(out, "total", page.total); Json.put(out, "total_hosts", page.totalHosts);
        final JSONArray items = new JSONArray();
        for (final DomainCandidate c : page.items) items.put(c.toJson());
        Json.put(out, "items", items);
        return out;
    }

    /** All candidates of the filter, host by host, streamed page by page. */
    void export(final Map<String, String> input, final List<String> collections, final Sink sink) throws ApiException, IOException {
        final Filter f = filter(input, collections, Set.of("q", "collection", "format"));
        final DomainEnrichment.Session session = this.enrichment.open();
        final Filter byHost = new Filter(f.host, f.collections, "host");
        // the first page is read before anything is written, so an unavailable index is a clean error
        Page page = hostPage(byHost, 0, EXPORT_PAGE, null, false, session);
        sink.begin(byHost, this.clock.getAsLong());
        long count = 0;
        try {
            while (true) {
                for (final DomainCandidate c : page.items) { sink.item(c); count++; }
                sink.flush();
                if (page.hosts < EXPORT_PAGE || page.lastHost == null) break;
                page = hostPage(byHost, 0, EXPORT_PAGE, page.lastHost, false, session);
            }
        } catch (final ApiException interrupted) {
            sink.end(count, false);
            return;
        }
        sink.end(count, true);
    }

    // ------------------------------------------------------------------ index

    Page hostPage(final Filter f, final int offset, final int limit, final String after, final boolean totals,
            final DomainEnrichment.Session session) throws ApiException {
        final ModifiableSolrParams p = base(f);
        if (after != null) p.add("fq", "host_s:{" + quoted(after) + " TO *]");
        p.set("rows", 0);
        final JSONObject collectionFacet = Json.obj("type", "terms", "field", "collection_sxt", "limit", MAX_COLLECTIONS,
                "missing", true, "sort", "index asc", "facet", Json.obj("latest", "max(load_date_dt)"));
        final JSONObject facets = Json.obj("hosts", Json.obj("type", "terms", "field", "host_s", "limit", limit,
                "offset", offset, "sort", "pages".equals(f.sort) ? "count desc" : "index asc", "numBuckets", totals,
                "facet", Json.obj("collections", collectionFacet)));
        if (totals && f.collections == null)
            Json.put(facets, "pairs", Json.obj("type", "terms", "field", "collection_sxt", "limit", -1, "missing", true,
                    "facet", Json.obj("hosts", "unique(host_s)")));
        p.set("json.facet", facets.toString());
        final NamedList<Object> result = execute(p);
        final Object data = result.get("facets");
        if (!(value(data, "count") instanceof Number)) throw unavailable();
        final long matching = ((Number) value(data, "count")).longValue();
        final Object hosts = value(data, "hosts");
        final List<DomainCandidate.Builder> builders = new ArrayList<>();
        final Map<String, List<String>> hostsByCollection = new LinkedHashMap<>();
        final Map<String, Map<String, DomainCandidate.Builder>> byCollection = new LinkedHashMap<>();
        int hostCount = 0;
        String lastHost = null;
        for (final Object bucket : buckets(hosts)) {
            final Object host = value(bucket, "val");
            if (!(host instanceof String) || ((String) host).isEmpty()) continue;
            hostCount++;
            lastHost = (String) host;
            final Object nested = value(bucket, "collections");
            for (final Object c : buckets(nested)) {
                final String name = String.valueOf(value(c, "val"));
                if (f.collections != null && !f.collections.contains(name)) continue;
                add(builders, hostsByCollection, byCollection, lastHost, name, c);
            }
            final Object missing = value(nested, "missing");
            if (f.collections == null && count(missing) > 0) add(builders, hostsByCollection, byCollection, lastHost, null, missing);
        }
        for (final Map.Entry<String, List<String>> e : hostsByCollection.entrySet())
            representatives(e.getKey().isEmpty() ? null : e.getKey(), e.getValue(), byCollection.get(e.getKey()));
        final List<DomainCandidate> items = new ArrayList<>(builders.size());
        for (final DomainCandidate.Builder b : builders) {
            session.enrich(b);
            items.add(b.build());
        }
        Long totalHosts = null, total = null;
        if (totals) {
            totalHosts = matching == 0 ? 0L : number(value(hosts, "numBuckets"));
            if (f.collections != null && f.collections.size() == 1) total = totalHosts;
            else if (f.collections == null) total = matching == 0 ? 0L : pairs(value(data, "pairs"));
        }
        return new Page(items, hostCount, lastHost, totalHosts, total);
    }

    private static void add(final List<DomainCandidate.Builder> builders, final Map<String, List<String>> hostsByCollection,
            final Map<String, Map<String, DomainCandidate.Builder>> byCollection, final String host, final String collection,
            final Object bucket) {
        final DomainCandidate.Builder b = DomainCandidate.builder(host, collection)
                .indexedPages(count(bucket)).lastLoaded(time(value(bucket, "latest")));
        builders.add(b);
        final String key = collection == null ? "" : collection;
        hostsByCollection.computeIfAbsent(key, k -> new ArrayList<>()).add(host);
        byCollection.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(host, b);
    }

    /** Representative page per host of one collection (null: documents without collection). */
    private void representatives(final String collection, final List<String> hosts, final Map<String, DomainCandidate.Builder> builders)
            throws ApiException {
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("defType", "lucene");
        final List<String> terms = new ArrayList<>();
        for (final String h : hosts) terms.add(quoted(h));
        p.set("q", "host_s:(" + String.join(" OR ", terms) + ")");
        p.add("fq", collection == null ? "(*:* AND -collection_sxt:[* TO *])" : "collection_sxt:" + quoted(collection));
        p.set("group", "true"); p.set("group.field", "host_s"); p.set("group.main", "true");
        p.set("group.limit", GROUP_DOCS); p.set("group.sort", "crawldepth_i asc,url_protocol_s desc,sku asc");
        p.set("sort", "host_s asc"); p.set("start", 0); p.set("rows", hosts.size() * GROUP_DOCS);
        p.set("fl", REPRESENTATIVE_FIELDS); p.set("timeAllowed", TIME_ALLOWED_MS);
        final Object docs = execute(p).get("response");
        if (!(docs instanceof SolrDocumentList)) throw unavailable();
        final Map<String, SolrDocument> chosen = new LinkedHashMap<>();
        for (final SolrDocument doc : (SolrDocumentList) docs) {
            final Object host = doc.getFieldValue("host_s");
            if (!(host instanceof String) || !builders.containsKey(host)) continue;
            final SolrDocument current = chosen.get(host);
            if (current == null || (!ok(current) && ok(doc))) chosen.put((String) host, doc);
        }
        for (final Map.Entry<String, SolrDocument> e : chosen.entrySet()) {
            final SolrDocument doc = e.getValue();
            final Object protocol = doc.getFieldValue("url_protocol_s"), status = doc.getFieldValue("httpstatus_i");
            builders.get(e.getKey()).startUrl(string(doc.getFieldValue("sku")))
                    .scheme("http".equals(protocol) || "https".equals(protocol) ? (String) protocol : null)
                    .title(first(doc.getFieldValues("title"))).description(first(doc.getFieldValues("description_txt")))
                    .httpStatus(status instanceof Number ? ((Number) status).intValue() : null);
        }
    }

    private static boolean ok(final SolrDocument doc) {
        final Object status = doc.getFieldValue("httpstatus_i");
        return !(status instanceof Number) || ((Number) status).intValue() == 200;
    }

    private static ModifiableSolrParams base(final Filter f) {
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("defType", "lucene");
        p.set("q", f.host.isEmpty() ? "*:*" : "host_s:*" + ClientUtils.escapeQueryChars(f.host) + "*");
        p.set("timeAllowed", TIME_ALLOWED_MS);
        if (f.collections != null) {
            final List<String> values = new ArrayList<>();
            for (final String c : f.collections) values.add(quoted(c));
            p.add("fq", values.isEmpty() ? "(*:* AND -*:*)" : "collection_sxt:(" + String.join(" OR ", values) + ")");
        }
        return p;
    }

    private NamedList<Object> execute(final ModifiableSolrParams p) throws ApiException {
        try {
            final NamedList<Object> result = this.query.execute(p);
            final Object partial = value(result.get("responseHeader"), "partialResults");
            if (partial != null && !"false".equals(String.valueOf(partial))) throw new IOException("Partial index response");
            return result;
        } catch (final IOException | RuntimeException e) {
            ConcurrentLog.warn("SCOUTRO-API", "domain browser index request failed: " + e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(503, "index_unavailable", "Index browsing is temporarily unavailable; retry later.");
    }

    // ------------------------------------------------------------------ parsing helpers

    private static long pairs(final Object data) throws ApiException {
        long total = 0;
        for (final Object bucket : buckets(data)) total += number(value(bucket, "hosts"));
        final Object missing = value(data, "missing");
        if (count(missing) > 0) total += number(value(missing, "hosts"));
        return total;
    }

    private static Collection<?> buckets(final Object data) {
        final Object items = value(data, "buckets");
        return items instanceof Collection<?> ? (Collection<?>) items : List.of();
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

    private static long number(final Object value) throws ApiException {
        if (!(value instanceof Number)) throw unavailable();
        return ((Number) value).longValue();
    }

    /** Dates at or before the epoch are YaCy's "unknown", never a real load. */
    private static Long time(final Object value) {
        final Long t = rawTime(value);
        return t == null || t <= 0 ? null : t;
    }

    private static Long rawTime(final Object value) {
        if (value instanceof Date) return ((Date) value).getTime();
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return java.time.Instant.parse((String) value).toEpochMilli();
            } catch (final java.time.format.DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    private static String first(final Collection<Object> values) {
        if (values == null) return null;
        for (final Object v : values) {
            final String s = v == null ? "" : String.valueOf(v).trim();
            if (!s.isEmpty()) return s;
        }
        return null;
    }

    private static String string(final Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static String quoted(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static int number(final Map<String, String> input, final String key, final int fallback, final int min, final int max)
            throws ApiException {
        try {
            final int value = Integer.parseInt(input.getOrDefault(key, String.valueOf(fallback)));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (final NumberFormatException e) {
            throw ApiException.invalid(key, "Value outside the allowed range.");
        }
    }
}
