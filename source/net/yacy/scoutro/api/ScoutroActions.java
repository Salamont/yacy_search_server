/*
 *  ScoutroActions
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.api;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.document.parser.html.CharacterCoding;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;

/**
 * The Scoutro actions. Every action translates the stable Scoutro request
 * model into calls of existing YaCy functions (HTTP APIs of this peer) and
 * translates the answers into the stable Scoutro response model. No crawler,
 * index or search logic is implemented here.
 */
final class ScoutroActions {

    static final String API_VERSION = "1";

    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-API");

    private static final Pattern CRAWL_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern COLLECTION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?");
    /**
     * A DNS name with at least two labels, lower case, whose last label (TLD)
     * starts with a letter; so no IP literals, ports, wildcards or query syntax.
     */
    private static final Pattern DOMAIN = Pattern.compile(
            "(?=.{3,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]");

    static final int EVIDENCE_DEFAULT_LIMIT = 8;
    static final int EVIDENCE_MAX_LIMIT = 20;
    static final int EVIDENCE_DEFAULT_CHARS = 1500;
    static final int EVIDENCE_MIN_CHARS = 100;
    static final int EVIDENCE_MAX_CHARS = 4000;

    static final int MAX_DEPTH = 10;
    static final int DEFAULT_DEPTH = 2;
    static final int MAX_PAGES = 1_000_000;
    static final int MAX_QUERY_LENGTH = 200;
    static final int MAX_URL_LENGTH = 2048;

    /** Crawl starts are serialized so that the new crawl profile can be identified reliably. */
    private static final Object CRAWL_START_LOCK = new Object();

    private final Upstream yacy;

    ScoutroActions() {
        this(new YaCyLoopback());
    }

    ScoutroActions(final Upstream upstream) {
        this.yacy = upstream;
    }

    // ------------------------------------------------------------------
    // health, system, version
    // ------------------------------------------------------------------

    /** health: public, cheap, confirms that YaCy answers its own public API. */
    JSONObject health() throws ApiException {
        final Document version;
        try {
            version = this.yacy.getPublicXml("api/version.xml", null);
        } catch (final ApiException e) {
            throw new ApiException(503, "unavailable", "YaCy does not answer: " + e.getMessage());
        }
        return Json.obj(
                "status", "ok",
                "service", "scoutro",
                "apiVersion", API_VERSION,
                "version", versionJson(version));
    }

    /** system.status: versions, peer, resources and crawler queue state. */
    JSONObject system() throws ApiException {
        final Switchboard sb = switchboard();
        final Document status = this.yacy.getAdminXml("api/status_p.xml", null);
        final Document version = this.yacy.getPublicXml("api/version.xml", null);
        return Json.obj(
                "version", versionJson(version),
                "peer", Json.obj(
                        "name", sb.peers == null || sb.peers.mySeed() == null ? "" : sb.peers.mySeed().getName(),
                        "network", sb.getConfig(SwitchboardConstants.NETWORK_NAME, "")),
                "resources", Json.obj(
                        "processors", longOrNull(text(status, "processors")),
                        "load", doubleOrNull(text(status, "load")),
                        "memory", Json.obj(
                                "usedBytes", longOrNull(text(status, "memory", "used")),
                                "freeBytes", longOrNull(text(status, "memory", "free")),
                                "totalBytes", longOrNull(text(status, "memory", "total")),
                                "maxBytes", longOrNull(text(status, "memory", "max"))),
                        "disk", Json.obj(
                                "usedBytes", longOrNull(text(status, "disk", "used")),
                                "freeBytes", longOrNull(text(status, "disk", "free")))),
                "crawler", crawlerQueues(status),
                "pagesPerMinute", longOrNull(text(status, "ppm")));
    }

    private static JSONObject versionJson(final Document version) {
        final Properties scoutro = scoutroProperties();
        final String upstream = scoutro.getProperty("scoutro.upstream.version", text(version, "buildVersion"));
        final String release = scoutro.getProperty("scoutro.release", "");
        return Json.obj(
                "scoutro", release.isEmpty() ? null : upstream + "-scoutro." + release,
                "yacy", text(version, "buildVersion"),
                "build", text(version, "file"));
    }

    private static Properties scoutroProperties() {
        final Properties p = new Properties();
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null) {
            return p;
        }
        final File file = new File(sb.getAppPath(), "scoutro.properties");
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                p.load(in);
            } catch (final IOException e) {
                LOG.warn("cannot read scoutro.properties: " + e.getMessage());
            }
        }
        return p;
    }

    // ------------------------------------------------------------------
    // search
    // ------------------------------------------------------------------

    /** search: yacysearch.json with a small, validated parameter set. */
    JSONObject search(final Map<String, String> query) throws ApiException {
        return search(query, null);
    }

    /**
     * search, optionally restricted to one collection. The collection is sent
     * as the request parameter "collection", which YaCy applies after parsing
     * the query and which overrides any "collection:" written in the query
     * (yacysearch.java). Used for agents; null means no restriction.
     */
    JSONObject search(final Map<String, String> query, final String collection) throws ApiException {
        final String q = trimToNull(query.get("q"));
        if (q == null) {
            throw ApiException.invalid("q", "Parameter 'q' (the search query) is required.");
        }
        if (q.length() > MAX_QUERY_LENGTH) {
            throw ApiException.invalid("q", "Parameter 'q' must not be longer than " + MAX_QUERY_LENGTH + " characters.");
        }
        final int limit = intParam(query, "limit", 10, 1, 100);
        final int offset = intParam(query, "offset", 0, 0, 10_000);
        final String source = enumParam(query, "source", "local", "local", "network");
        final String lang = trimToNull(query.get("lang"));
        if (lang != null && !LANGUAGE.matcher(lang).matches()) {
            throw ApiException.invalid("lang", "Parameter 'lang' must be a two-letter language code such as 'de'.");
        }

        final String body = this.yacy.getAdmin("yacysearch.json", new YaCyLoopback.Params()
                .add("query", q)
                .add("maximumRecords", limit)
                .add("startRecord", offset)
                .add("resource", "network".equals(source) ? "global" : "local")
                .add("contentdom", "text")
                .add("nav", "none")
                .add("lr", lang == null ? null : "lang_" + lang)
                .add("collection", collection));
        final JSONObject json = Json.parseUpstream(body, "yacysearch.json");
        final JSONArray channels = json.optJSONArray("channels");
        final JSONObject channel = channels == null ? null : channels.optJSONObject(0);
        final JSONArray results = Json.arr();
        long total = 0;
        if (channel != null) {
            total = parseLong(channel.optString("totalResults", "0"), 0);
            final JSONArray items = channel.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    final JSONObject item = items.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    results.put(Json.obj(
                            "title", plain(item.optString("title", "")),
                            "url", item.optString("link", ""),
                            "snippet", plain(item.optString("description", "")),
                            "host", item.optString("host", ""),
                            "date", isoDate(item.optString("pubDate", "")),
                            "sizeBytes", longOrNull(item.optString("size", "")),
                            "id", item.optString("guid", "")));
                }
            }
        }
        return Json.obj(
                "query", q,
                "source", source,
                "offset", offset,
                "limit", limit,
                "total", total,
                "results", results);
    }

    // ------------------------------------------------------------------
    // index
    // ------------------------------------------------------------------

    /** index.status: document and link counts plus crawler queues (api/status_p.xml). */
    JSONObject index() throws ApiException {
        final Document status = this.yacy.getAdminXml("api/status_p.xml", null);
        return Json.obj(
                "documents", longOrNull(text(status, "dbsize", "urlpublictext")),
                "webgraphEdges", longOrNull(text(status, "dbsize", "webgraph")),
                "citations", longOrNull(text(status, "dbsize", "citation")),
                "rwiWords", longOrNull(text(status, "dbsize", "rwipublictext")),
                "crawler", crawlerQueues(status),
                "postprocessing", Json.obj(
                        "status", text(status, "postprocessing", "status"),
                        "remaining", longOrNull(text(status, "postprocessing", "collectionRemainingCount"))));
    }

    /** index.lookup: is a URL indexed, or how many documents does a host have (embedded Solr). */
    JSONObject indexLookup(final Map<String, String> query) throws ApiException {
        return indexLookup(query, null);
    }

    /**
     * index.lookup limited to the given collections (null: complete index).
     * Documents outside the collections count as not indexed, and only the
     * given collections are reported for a found document.
     */
    JSONObject indexLookup(final Map<String, String> query, final List<String> collections) throws ApiException {
        final String fq = collectionFilter(collections);
        final String url = trimToNull(query.get("url"));
        final String host = trimToNull(query.get("host"));
        if ((url == null) == (host == null)) {
            throw ApiException.invalid("url", "Give exactly one of the parameters 'url' or 'host'.");
        }
        if (host != null) {
            if (!HOST.matcher(host).matches()) {
                throw ApiException.invalid("host", "Parameter 'host' must be a host name such as 'example.com'.");
            }
            final JSONObject result = solr("host_s:" + phrase(host.toLowerCase(Locale.ROOT)), 0, fq);
            final JSONObject response = result.optJSONObject("response");
            return Json.obj("host", host, "documents", response == null ? 0 : response.optLong("numFound", 0));
        }
        final String normalized = validateHttpUrl(url, "url");
        for (final String candidate : urlVariants(normalized)) {
            final JSONObject result = solr("sku:" + phrase(candidate), 1, fq);
            final JSONObject response = result.optJSONObject("response");
            final JSONArray docs = response == null ? null : response.optJSONArray("docs");
            final JSONObject doc = docs == null ? null : docs.optJSONObject(0);
            if (doc != null) {
                return Json.obj(
                        "url", url,
                        "indexed", true,
                        "document", Json.obj(
                                "url", doc.optString("sku", candidate),
                                "title", firstString(doc.opt("title")),
                                "host", doc.optString("host_s", ""),
                                "lastModified", doc.optString("last_modified", ""),
                                "collections", visibleCollections(doc.optJSONArray("collection_sxt"), collections)));
            }
        }
        return Json.obj("url", url, "indexed", false, "document", null);
    }

    /**
     * Read-only evidence for one domain from the existing full-text index:
     * URL, title and a bounded plain-text excerpt of the indexed page text
     * (Solr text_t). Nothing is fetched from the web and no HTCache is needed,
     * so it works for text-only crawls. The query is built here from validated
     * values only (no Solr syntax from the caller). Page text is returned as
     * untrusted data; callers must never treat it as instructions.
     */
    JSONObject indexEvidence(final Map<String, String> query) throws ApiException {
        return indexEvidence(query, null);
    }

    /**
     * index.evidence; with a non-null collection list the filter is exactly
     * these collections and a 'collection' parameter is ignored (the agent
     * layer has resolved and checked it already).
     */
    JSONObject indexEvidence(final Map<String, String> query, final List<String> scope) throws ApiException {
        final String rawDomain = trimToNull(query.get("domain"));
        if (rawDomain == null) {
            throw ApiException.invalid("domain", "Parameter 'domain' is required, e.g. 'example.com'.");
        }
        final String domain = rawDomain.toLowerCase(Locale.ROOT);
        if (!DOMAIN.matcher(domain).matches()) {
            throw ApiException.invalid("domain", "Parameter 'domain' must be a DNS name such as 'example.com' "
                    + "(no scheme, port, path, IP address or wildcard).");
        }
        final String collection = scope != null ? (scope.size() == 1 ? scope.get(0) : null) : trimToNull(query.get("collection"));
        if (collection != null && !COLLECTION.matcher(collection).matches()) {
            throw ApiException.invalid("collection", "Parameter 'collection' must match [A-Za-z0-9_-]{1,64}.");
        }
        final List<String> filter = scope != null ? scope
                : collection == null ? null : java.util.Collections.singletonList(collection);
        final int limit = intParam(query, "limit", EVIDENCE_DEFAULT_LIMIT, 1, EVIDENCE_MAX_LIMIT);
        final int maxChars = intParam(query, "maxChars", EVIDENCE_DEFAULT_CHARS, EVIDENCE_MIN_CHARS, EVIDENCE_MAX_CHARS);
        final String hostQuery = "host_s:" + phrase(domain)
                + (domain.startsWith("www.") ? "" : " OR host_s:" + phrase("www." + domain));
        final YaCyLoopback.Params params = new YaCyLoopback.Params()
                .add("q", hostQuery)
                .add("defType", "lucene")
                .add("fq", filter == null ? "httpstatus_i:200" : "httpstatus_i:200 AND " + collectionFilter(filter))
                .add("sort", "crawldepth_i asc,sku asc")
                .add("rows", limit)
                .add("wt", "json")
                .add("fl", "sku,title,text_t");
        final JSONObject result = Json.parseUpstream(this.yacy.getAdmin("solr/select", params), "solr/select");
        final JSONObject response = result.optJSONObject("response");
        final JSONArray docs = response == null ? null : response.optJSONArray("docs");
        final JSONArray out = Json.arr();
        if (docs != null) {
            for (int i = 0; i < docs.length(); i++) {
                final JSONObject doc = docs.optJSONObject(i);
                if (doc == null) {
                    continue;
                }
                final String text = excerpt(firstString(doc.opt("text_t")), maxChars);
                out.put(Json.obj(
                        "url", doc.optString("sku", ""),
                        "title", excerpt(firstString(doc.opt("title")), 300),
                        "excerpt", text));
            }
        }
        return Json.obj(
                "domain", domain,
                "collection", collection,
                "collections", filter == null ? null : new JSONArray(filter),
                "total", response == null ? 0 : response.optLong("numFound", 0),
                "limit", limit,
                "maxChars", maxChars,
                "documents", out);
    }

    /** Plain text, whitespace collapsed, control/format characters removed, cut at maxChars. */
    static String excerpt(final String text, final int maxChars) {
        final String clean = CONTROL_CHARS.matcher(text == null ? "" : text).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return clean.length() <= maxChars ? clean : clean.substring(0, maxChars);
    }

    private JSONObject solr(final String q, final int rows, final String fq) throws ApiException {
        final String body = this.yacy.getAdmin("solr/select", new YaCyLoopback.Params()
                .add("q", q)
                .add("fq", fq)
                .add("defType", "lucene")
                .add("rows", rows)
                .add("wt", "json")
                .add("fl", "sku,title,host_s,last_modified,collection_sxt"));
        return Json.parseUpstream(body, "solr/select");
    }

    /** Number of indexed documents (HTTP 200) per collection, for the scoped index status. */
    long countDocuments(final String collection) throws ApiException {
        final String body = this.yacy.getAdmin("solr/select", new YaCyLoopback.Params()
                .add("q", "*:*")
                .add("fq", collection == null ? "httpstatus_i:200"
                        : "httpstatus_i:200 AND " + collectionFilter(java.util.Collections.singletonList(collection)))
                .add("defType", "lucene")
                .add("rows", 0)
                .add("wt", "json"));
        final JSONObject response = Json.parseUpstream(body, "solr/select").optJSONObject("response");
        return response == null ? 0 : response.optLong("numFound", 0);
    }

    /**
     * Number of indexed documents of a host (and www.host) that belong to none
     * of the given collections. Used before an agent crawl: YaCy re-indexes
     * crawled pages with the collection of the new crawl, which would move
     * documents out of collections the agent may not touch.
     */
    long countHostOutside(final String host, final List<String> collections) throws ApiException {
        final String h = host.toLowerCase(Locale.ROOT);
        final String bare = h.startsWith("www.") ? h.substring(4) : h;
        final String body = this.yacy.getAdmin("solr/select", new YaCyLoopback.Params()
                .add("q", "host_s:" + phrase(bare) + " OR host_s:" + phrase("www." + bare))
                .add("fq", "-" + collectionFilter(collections))
                .add("defType", "lucene")
                .add("rows", 0)
                .add("wt", "json"));
        final JSONObject response = Json.parseUpstream(body, "solr/select").optJSONObject("response");
        return response == null ? 0 : response.optLong("numFound", 0);
    }

    /** Solr filter on collection_sxt for validated collection names; null for no restriction. */
    static String collectionFilter(final List<String> collections) {
        if (collections == null) {
            return null;
        }
        if (collections.isEmpty()) {
            return "-*:*"; // an empty scope matches nothing
        }
        final StringBuilder sb = new StringBuilder("(");
        for (final String c : collections) {
            if (!COLLECTION.matcher(c).matches()) {
                throw new IllegalArgumentException("invalid collection name");
            }
            if (sb.length() > 1) {
                sb.append(" OR ");
            }
            sb.append("collection_sxt:").append(phrase(c));
        }
        return sb.append(')').toString();
    }

    private static JSONArray visibleCollections(final JSONArray docCollections, final List<String> scope) {
        final JSONArray out = Json.arr();
        if (docCollections == null) {
            return out;
        }
        for (int i = 0; i < docCollections.length(); i++) {
            final String c = docCollections.optString(i, "");
            if (!c.isEmpty() && (scope == null || scope.contains(c))) {
                out.put(c);
            }
        }
        return out;
    }

    private static String phrase(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Iterable<String> urlVariants(final String url) {
        final Set<String> variants = new java.util.LinkedHashSet<>();
        variants.add(url);
        variants.add(url.endsWith("/") ? url.substring(0, url.length() - 1) : url + "/");
        return variants;
    }

    // ------------------------------------------------------------------
    // configuration (explicit allowlist only)
    // ------------------------------------------------------------------

    /** A setting that agents may read and change: Scoutro name, YaCy key, validation. */
    private static final class Setting {
        final String yacyKey;
        final String type;
        final String description;
        final int min;
        final int max;

        Setting(final String yacyKey, final String type, final String description, final int min, final int max) {
            this.yacyKey = yacyKey;
            this.type = type;
            this.description = description;
            this.min = min;
            this.max = max;
        }
    }

    private static final Map<String, Setting> SETTINGS = new LinkedHashMap<>();
    static {
        SETTINGS.put("search.greeting", new Setting(SwitchboardConstants.GREETING, "string",
                "Greeting shown below the logo on the search start page.", 0, 120));
        SETTINGS.put("search.itemsPerPage", new Setting("search.items", "integer",
                "Default number of results per page on the search page.", 1, 100));
    }

    JSONObject configGet() throws ApiException {
        final Switchboard sb = switchboard();
        final JSONObject settings = new JSONObject();
        for (final Map.Entry<String, Setting> e : SETTINGS.entrySet()) {
            final Setting s = e.getValue();
            final String raw = sb.getConfig(s.yacyKey, "");
            Json.put(settings, e.getKey(), Json.obj(
                    "value", "integer".equals(s.type) ? longOrNull(raw) : raw,
                    "type", s.type,
                    "description", s.description,
                    "writable", true));
        }
        return Json.obj("settings", settings);
    }

    /** config.set: all-or-nothing update of allowlisted settings. */
    JSONObject configSet(final JSONObject body) throws ApiException {
        final Switchboard sb = switchboard();
        if (body.length() == 0) {
            throw ApiException.invalid("body", "Give at least one setting to change.");
        }
        final Map<String, String> updates = new LinkedHashMap<>();
        final Iterator<String> keys = body.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final Setting s = SETTINGS.get(key);
            if (s == null) {
                throw new ApiException(403, "setting_not_allowed",
                        "The setting '" + key + "' is not on the Scoutro allowlist.", Json.obj("field", key));
            }
            final Object value = body.opt(key);
            if ("integer".equals(s.type)) {
                if (!(value instanceof Number) || ((Number) value).doubleValue() != Math.rint(((Number) value).doubleValue())) {
                    throw ApiException.invalid(key, "'" + key + "' must be an integer.");
                }
                final long v = ((Number) value).longValue();
                if (v < s.min || v > s.max) {
                    throw ApiException.invalid(key, "'" + key + "' must be between " + s.min + " and " + s.max + ".");
                }
                updates.put(s.yacyKey, Long.toString(v));
            } else {
                if (!(value instanceof String)) {
                    throw ApiException.invalid(key, "'" + key + "' must be a string.");
                }
                final String v = ((String) value).trim();
                if (v.length() > s.max || v.chars().anyMatch(c -> c < 0x20)) {
                    throw ApiException.invalid(key, "'" + key + "' must be at most " + s.max
                            + " characters without control characters.");
                }
                updates.put(s.yacyKey, v);
            }
        }
        for (final Map.Entry<String, String> u : updates.entrySet()) {
            sb.setConfig(u.getKey(), u.getValue());
        }
        LOG.info("config.set " + body.keySet());
        return configGet();
    }

    // ------------------------------------------------------------------
    // crawls
    // ------------------------------------------------------------------

    /** crawl.list: all user crawls known to YaCy (running and terminated). */
    JSONObject crawlList() throws ApiException {
        final JSONArray crawls = Json.arr();
        for (final JSONObject crawl : loadCrawls().values()) {
            crawls.put(crawl);
        }
        return Json.obj("crawls", crawls);
    }

    /** crawl.status */
    JSONObject crawlGet(final String id) throws ApiException {
        checkCrawlId(id);
        final JSONObject crawl = loadCrawls().get(id);
        if (crawl == null) {
            throw new ApiException(404, "crawl_not_found", "There is no crawl with the id '" + id + "'.",
                    Json.obj("id", id));
        }
        return crawl;
    }

    /** URL filter carrying a start marker; it can only match URLs that contain the marker itself. */
    static final Pattern START_MARKER_FILTER = Pattern.compile("\\.\\*/scoutro-start-([0-9a-f]{32})/\\.\\*");

    static String startMarkerFilter(final String marker) {
        return ".*/scoutro-start-" + marker + "/.*";
    }

    /** crawl.start: translated to the site crawl of Crawler_p. */
    JSONObject crawlStart(final JSONObject body) throws ApiException {
        return crawlStart(body, null);
    }

    /**
     * crawl.start with an optional start marker (32 hex characters). The
     * marker is written into the crawl profile as the URL must-not-match
     * filter {@code .*}{@code /scoutro-start-<marker>/.*}, which excludes no
     * real page, and is reported as {@code startMarker} by the crawl list.
     * It ties a crawl profile to the start attempt that created it, also
     * when the caller crashed before it could store the crawl id.
     */
    JSONObject crawlStart(final JSONObject body, final String marker) throws ApiException {
        if (marker != null && !marker.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid start marker");
        }
        final java.util.List<String> allowed = java.util.List.of("url", "depth", "scope", "maxPages", "collection");
        for (final String key : body.keySet()) {
            if (!allowed.contains(key)) {
                throw ApiException.invalid(key, "Unknown field '" + key + "'. Allowed fields: " + allowed + ".");
            }
        }
        final Object urlValue = body.opt("url");
        if (!(urlValue instanceof String)) {
            throw ApiException.invalid("url", "Field 'url' (an http or https URL) is required.");
        }
        final String url = validateHttpUrl((String) urlValue, "url");
        final int depth = intField(body, "depth", DEFAULT_DEPTH, 0, MAX_DEPTH);
        final String scope = enumField(body, "scope", "domain", "domain", "subpath", "wide");
        final Integer maxPages = present(body, "maxPages") ? intField(body, "maxPages", 0, 1, MAX_PAGES) : null;
        final String collection = present(body, "collection") ? stringField(body, "collection", COLLECTION,
                "letters, digits, '-' and '_' (at most 64)") : "user";

        final YaCyLoopback.Params params = new YaCyLoopback.Params()
                .add("crawlingstart", "1")
                .add("crawlingMode", "url")
                .add("crawlingURL", url)
                .add("crawlingDepth", depth)
                .add("range", scope)
                .add("mustmatch", ".*")
                .add("mustnotmatch", marker == null ? "" : startMarkerFilter(marker))
                .add("crawlingDomMaxCheck", maxPages == null ? "off" : "on")
                .add("crawlingDomMaxPages", maxPages == null ? null : maxPages)
                .add("collection", collection)
                // same defaults as the YaCy site crawl start (CrawlStartSite.html) ...
                .add("crawlingQ", "on")
                .add("followFrames", "on")
                .add("obeyHtmlRobotsNoindex", "on")
                .add("indexText", "on")
                // Scoutro is a search index for agents, not a web archive:
                // index the text only, never store the crawled originals
                // (HTCache) and never index media. cachePolicy=nocache keeps
                // the crawler from consulting a response cache.
                .add("indexMedia", "off")
                .add("storeHTCache", "off")
                .add("cachePolicy", "nocache")
                .add("recrawl", "reload")
                .add("reloadIfOlderNumber", "3")
                .add("reloadIfOlderUnit", "day")
                // ... except that the API never deletes other documents of the site. Note that
                // Crawler_p itself always removes the start URL from the index to reload it.
                .add("deleteold", "off");

        synchronized (CRAWL_START_LOCK) {
            final Set<String> before = new HashSet<>(loadCrawls().keySet());
            final JSONObject answer = Json.parseUpstream(this.yacy.postAdmin("Crawler_p.json", params), "Crawler_p.json");
            final String comment = plain(answer.optString("comment", ""));
            final boolean started = comment.endsWith("started.");
            if (!comment.isEmpty() && !started) {
                throw new ApiException(422, "crawl_rejected", "YaCy did not start the crawl: " + comment,
                        Json.obj("url", url));
            }
            final Map<String, JSONObject> after = loadCrawls();
            JSONObject created = null;
            for (final Map.Entry<String, JSONObject> e : after.entrySet()) {
                if (marker != null ? marker.equals(e.getValue().optString("startMarker"))
                        : !before.contains(e.getKey())) {
                    created = e.getValue();
                    break;
                }
            }
            if (created == null) {
                throw new ApiException(502, "upstream_error",
                        "YaCy accepted the crawl request, but no new crawl profile appeared.", Json.obj("url", url));
            }
            Json.put(created, "startUrl", url);
            Json.put(created, "scope", scope);
            LOG.info("crawl.start id=" + created.optString("id") + " url=" + url + " depth=" + depth + " scope=" + scope);
            return created;
        }
    }

    /** crawl.stop: terminates a running crawl; YaCy removes its profile afterwards. */
    JSONObject crawlStop(final String id) throws ApiException {
        final JSONObject crawl = crawlGet(id);
        if ("terminated".equals(crawl.optString("state"))) {
            throw new ApiException(409, "crawl_not_running", "The crawl '" + id + "' is not running.",
                    Json.obj("id", id, "state", "terminated"));
        }
        this.yacy.postAdmin("Crawler_p.json", new YaCyLoopback.Params().add("terminate", "1").add("handle", id));
        if (loadCrawls().containsKey(id)) {
            throw new ApiException(502, "upstream_error", "YaCy did not stop the crawl '" + id + "'.",
                    Json.obj("id", id));
        }
        LOG.info("crawl.stop id=" + id);
        return Json.obj("id", id, "name", crawl.opt("name"), "state", "stopped");
    }

    /**
     * Read all non-system crawl profiles (CrawlProfileEditor_p.xml: active and
     * terminated) and merge the per-crawl counters of api/status_p.xml.
     */
    Map<String, JSONObject> loadCrawls() throws ApiException {
        final Document profiles = this.yacy.getAdminXml("CrawlProfileEditor_p.xml", null);
        final Document status = this.yacy.getAdminXml("api/status_p.xml", null);
        final boolean paused = "paused".equalsIgnoreCase(text(status, "localcrawlerqueue", "state"));

        final Map<String, String> loaded = new LinkedHashMap<>();
        final NodeList active = status.getElementsByTagName("crawl");
        for (int i = 0; i < active.getLength(); i++) {
            final Element crawl = (Element) active.item(i);
            loaded.put(childText(crawl, "handle"), childText(crawl, "count"));
        }

        final Map<String, JSONObject> crawls = new LinkedHashMap<>();
        final NodeList list = profiles.getElementsByTagName("crawlProfile");
        for (int i = 0; i < list.getLength(); i++) {
            final Element p = (Element) list.item(i);
            final String yacyStatus = childText(p, "status");
            if ("system".equals(yacyStatus)) {
                continue;
            }
            final String id = childText(p, "handle");
            final String state = "active".equals(yacyStatus) ? (paused ? "paused" : "running") : "terminated";
            final long domMaxPages = parseLong(childText(p, "domMaxPages"), -1);
            final JSONArray collections = Json.arr();
            for (final String c : childText(p, "collections").split("[,|]")) {
                if (!c.trim().isEmpty()) {
                    collections.put(c.trim());
                }
            }
            crawls.put(id, Json.obj(
                    "id", id,
                    "name", childText(p, "name"),
                    "state", state,
                    "depth", longOrNull(childText(p, "depth")),
                    "maxPages", domMaxPages > 0 && domMaxPages < Integer.MAX_VALUE ? domMaxPages : null,
                    "pagesLoaded", loaded.containsKey(id) ? longOrNull(loaded.get(id)) : null,
                    "collections", collections,
                    "startMarker", startMarker(childText(p, "crawlerURLMustNotMatch")),
                    "links", Json.obj(
                            "self", "/scoutro/api/v1/crawls/" + id,
                            "stop", "/scoutro/api/v1/crawls/" + id + "/stop")));
        }
        return crawls;
    }

    /** The start marker of a crawl profile's must-not-match filter, or null. */
    static String startMarker(final String mustNotMatch) {
        final java.util.regex.Matcher m = START_MARKER_FILTER.matcher(mustNotMatch == null ? "" : mustNotMatch.trim());
        return m.matches() ? m.group(1) : null;
    }

    private static JSONObject crawlerQueues(final Document status) {
        return Json.obj(
                "state", text(status, "localcrawlerqueue", "state"),
                "localQueue", longOrNull(text(status, "localcrawlerqueue", "size")),
                "limitQueue", longOrNull(text(status, "limitcrawlerqueue", "size")),
                "remoteQueue", longOrNull(text(status, "remotecrawlerqueue", "size")),
                "noloadQueue", longOrNull(text(status, "noloadcrawlerqueue", "size")),
                "loader", longOrNull(text(status, "loaderqueue", "size")));
    }

    static void checkCrawlId(final String id) throws ApiException {
        if (id == null || !CRAWL_ID.matcher(id).matches()) {
            throw ApiException.invalid("id", "A crawl id consists of letters, digits, '-' and '_'.");
        }
    }

    // ------------------------------------------------------------------
    // validation and conversion helpers
    // ------------------------------------------------------------------

    /** Only absolute http(s) URLs with a host; no credentials in the URL. */
    static String validateHttpUrl(final String raw, final String field) throws ApiException {
        final String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > MAX_URL_LENGTH) {
            throw ApiException.invalid(field, "'" + field + "' must be an http or https URL of at most "
                    + MAX_URL_LENGTH + " characters.");
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (final URISyntaxException e) {
            throw ApiException.invalid(field, "'" + field + "' is not a valid URL.");
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw ApiException.invalid(field, "'" + field + "' must use http or https.");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw ApiException.invalid(field, "'" + field + "' must contain a host name.");
        }
        if (uri.getUserInfo() != null) {
            throw ApiException.invalid(field, "'" + field + "' must not contain user credentials.");
        }
        return value;
    }

    private static int intParam(final Map<String, String> query, final String name, final int dflt,
            final int min, final int max) throws ApiException {
        final String raw = trimToNull(query.get(name));
        if (raw == null) {
            return dflt;
        }
        final long v;
        try {
            v = Long.parseLong(raw);
        } catch (final NumberFormatException e) {
            throw ApiException.invalid(name, "Parameter '" + name + "' must be an integer.");
        }
        if (v < min || v > max) {
            throw ApiException.invalid(name, "Parameter '" + name + "' must be between " + min + " and " + max + ".");
        }
        return (int) v;
    }

    private static String enumParam(final Map<String, String> query, final String name, final String dflt,
            final String... values) throws ApiException {
        final String raw = trimToNull(query.get(name));
        if (raw == null) {
            return dflt;
        }
        for (final String v : values) {
            if (v.equals(raw)) {
                return v;
            }
        }
        throw ApiException.invalid(name, "Parameter '" + name + "' must be one of " + String.join(", ", values) + ".");
    }

    private static int intField(final JSONObject body, final String name, final int dflt, final int min,
            final int max) throws ApiException {
        if (!present(body, name)) {
            return dflt;
        }
        final Object value = body.opt(name);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != Math.rint(((Number) value).doubleValue())) {
            throw ApiException.invalid(name, "Field '" + name + "' must be an integer.");
        }
        final long v = ((Number) value).longValue();
        if (v < min || v > max) {
            throw ApiException.invalid(name, "Field '" + name + "' must be between " + min + " and " + max + ".");
        }
        return (int) v;
    }

    private static String enumField(final JSONObject body, final String name, final String dflt,
            final String... values) throws ApiException {
        if (!present(body, name)) {
            return dflt;
        }
        final Object value = body.opt(name);
        for (final String v : values) {
            if (v.equals(value)) {
                return v;
            }
        }
        throw ApiException.invalid(name, "Field '" + name + "' must be one of " + String.join(", ", values) + ".");
    }

    private static String stringField(final JSONObject body, final String name, final Pattern pattern,
            final String rule) throws ApiException {
        final Object value = body.opt(name);
        if (!(value instanceof String) || !pattern.matcher((String) value).matches()) {
            throw ApiException.invalid(name, "Field '" + name + "' must consist of " + rule + ".");
        }
        return (String) value;
    }

    /** A field counts as present when it exists and is not JSON null. */
    private static boolean present(final JSONObject body, final String name) {
        final Object value = body.opt(name);
        return value != null && value != JSONObject.NULL;
    }

    private static Switchboard switchboard() throws ApiException {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null) {
            throw new ApiException(503, "unavailable", "YaCy is not initialized yet.");
        }
        return sb;
    }

    /** Text of the first element at the given path of child element names below the root. */
    private static String text(final Document doc, final String... path) {
        Node node = doc.getDocumentElement();
        for (final String name : path) {
            node = firstChild(node, name);
            if (node == null) {
                return "";
            }
        }
        return node.getTextContent().trim();
    }

    private static Node firstChild(final Node parent, final String name) {
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return n;
            }
        }
        return null;
    }

    private static String childText(final Element parent, final String name) {
        final Node n = firstChild(parent, name);
        return n == null ? "" : n.getTextContent().trim();
    }

    private static String trimToNull(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static long parseLong(final String s, final long dflt) {
        try {
            return Long.parseLong(s.trim().replace(",", "").replace(".", ""));
        } catch (final RuntimeException e) {
            return dflt;
        }
    }

    private static Object longOrNull(final String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(s.trim().replace(",", ""));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static Object doubleOrNull(final String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(s.trim().replace(",", "."));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** HTML to plain text: drop tags, decode entities, collapse whitespace. */
    static String plain(final String html) {
        final String noTags = html.replaceAll("<[^>]*>", "");
        return CharacterCoding.html2unicode(noTags).replaceAll("\\s+", " ").trim();
    }

    private static Object isoDate(final String rfc1123) {
        if (rfc1123 == null || rfc1123.trim().isEmpty()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(rfc1123.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static String firstString(final Object value) {
        if (value instanceof JSONArray) {
            final JSONArray a = (JSONArray) value;
            return a.length() == 0 ? "" : a.optString(0, "");
        }
        return value == null ? "" : String.valueOf(value);
    }
}
