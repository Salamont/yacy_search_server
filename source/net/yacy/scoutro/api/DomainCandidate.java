/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONObject;

/**
 * One indexed host in one collection: the consolidated candidate of the Index Browser and of the
 * domain export ({@code scoutro.domains.v1}). Every field is either read from Scoutro's own data
 * (index, crawl report table, Discovery jobs and state) or null; nothing is fetched or inferred
 * from web pages. The same object is serialized for the paged API, the JSON export and the CSV
 * export, so a later integration API can reuse it unchanged.
 */
public final class DomainCandidate {

    public static final String SCHEMA = "scoutro.domains.v1";

    /** CSV columns, in this order. */
    public static final List<String> CSV_COLUMNS = List.of("host", "domain", "scheme", "website", "start_url", "collection",
            "indexed_pages", "title", "description", "last_loaded", "last_crawled", "crawl_status", "http_status",
            "classification_verdict", "classification_confidence", "classification_profile", "discovery_profile",
            "discovery_source", "discovery_job", "discovery_region");

    /** Classification of the registrable domain for this collection (Discovery state). */
    public static final class Classification {
        public final String verdict, profile;
        public final Double confidence;
        public final Long classifiedAt;

        public Classification(final String verdict, final Double confidence, final String profile, final Long classifiedAt) {
            this.verdict = verdict; this.confidence = confidence; this.profile = profile; this.classifiedAt = classifiedAt;
        }
    }

    /** Discovery context: profile, configured source of the job (only when it is unambiguous), job and region. */
    public static final class Discovery {
        public final String profile, source, job, region;

        public Discovery(final String profile, final String source, final String job, final String region) {
            this.profile = profile; this.source = source; this.job = job; this.region = region;
        }
    }

    public final String host, domain, scheme, startUrl, collection, title, description, crawlStatus;
    public final long indexedPages;
    public final Long lastLoaded, lastCrawled;
    public final Integer httpStatus;
    public final Classification classification;
    public final Discovery discovery;

    private DomainCandidate(final Builder b) {
        this.host = b.host; this.domain = registrableDomain(b.host); this.scheme = b.scheme; this.startUrl = b.startUrl;
        this.collection = b.collection; this.title = b.title; this.description = b.description; this.crawlStatus = b.crawlStatus;
        this.indexedPages = b.indexedPages; this.lastLoaded = b.lastLoaded; this.lastCrawled = b.lastCrawled;
        this.httpStatus = b.httpStatus; this.classification = b.classification; this.discovery = b.discovery;
    }

    /** Root of the site for the known scheme, derived from host and scheme only. */
    public String website() {
        return this.scheme == null ? null : this.scheme + "://" + this.host + "/";
    }

    public JSONObject toJson() {
        final JSONObject o = new JSONObject(true);
        put(o, "host", this.host); put(o, "domain", this.domain); put(o, "scheme", this.scheme);
        put(o, "website", website()); put(o, "start_url", this.startUrl); put(o, "collection", this.collection);
        put(o, "indexed_pages", this.indexedPages); put(o, "title", this.title); put(o, "description", this.description);
        put(o, "last_loaded", iso(this.lastLoaded)); put(o, "last_crawled", iso(this.lastCrawled));
        put(o, "crawl_status", this.crawlStatus); put(o, "http_status", this.httpStatus);
        if (this.classification == null) put(o, "classification", null);
        else {
            final JSONObject c = new JSONObject(true);
            put(c, "verdict", this.classification.verdict); put(c, "confidence", this.classification.confidence);
            put(c, "profile", this.classification.profile); put(c, "classified_at", iso(this.classification.classifiedAt));
            put(o, "classification", c);
        }
        if (this.discovery == null) put(o, "discovery", null);
        else {
            final JSONObject d = new JSONObject(true);
            put(d, "profile", this.discovery.profile); put(d, "source", this.discovery.source);
            put(d, "job", this.discovery.job); put(d, "region", this.discovery.region);
            put(o, "discovery", d);
        }
        return o;
    }

    /** One CSV record (RFC 4180, no line break at the end) in {@link #CSV_COLUMNS} order. */
    public String csvRow() {
        final Classification c = this.classification;
        final Discovery d = this.discovery;
        final Object[] cells = {this.host, this.domain, this.scheme, website(), this.startUrl, this.collection,
                this.indexedPages, this.title, this.description, iso(this.lastLoaded), iso(this.lastCrawled), this.crawlStatus,
                this.httpStatus, c == null ? null : c.verdict, c == null ? null : c.confidence, c == null ? null : c.profile,
                d == null ? null : d.profile, d == null ? null : d.source, d == null ? null : d.job, d == null ? null : d.region};
        final StringBuilder row = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) row.append(',');
            row.append(csvCell(cells[i]));
        }
        return row.toString();
    }

    public static String csvHeader() {
        return String.join(",", CSV_COLUMNS);
    }

    /**
     * Quote a cell when needed. Text from indexed pages that starts like a spreadsheet formula
     * (= + - @, tab, carriage return) is prefixed with an apostrophe, so opening the file in a
     * spreadsheet never evaluates it.
     */
    static String csvCell(final Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (!(value instanceof Number) && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0
                && text.equals(text.trim())) return text;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    static String iso(final Long millis) {
        return millis == null ? null : Instant.ofEpochMilli(millis).toString();
    }

    private static void put(final JSONObject o, final String key, final Object value) {
        Json.put(o, key, value);
    }

    // ------------------------------------------------------------- registrable domain

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    /** The public-suffix subset of scoutro-discovery (registrable_domain), so both sides agree. */
    static final Set<String> MULTI_SUFFIX = Set.of("co.uk", "org.uk", "gov.uk", "ac.uk", "com.au", "co.at", "or.at",
            "com.tr", "co.nz", "com.br", "co.za");

    /** Registrable domain exactly as scoutro-discovery computes it (state.json keys). */
    public static String registrableDomain(final String host) {
        final String h = host == null ? "" : stripDot(host.trim().toLowerCase(Locale.ROOT));
        if (h.isEmpty() || IPV4.matcher(h).matches()) return h;
        final String[] parts = h.split("\\.", -1);
        if (parts.length <= 2) return h;
        final String last2 = parts[parts.length - 2] + "." + parts[parts.length - 1];
        if (MULTI_SUFFIX.contains(last2)) return parts[parts.length - 3] + "." + last2;
        return last2;
    }

    private static String stripDot(final String h) {
        String s = h;
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static Builder builder(final String host, final String collection) {
        return new Builder(host, collection);
    }

    public static final class Builder {
        final String host, collection;
        String scheme, startUrl, title, description, crawlStatus;
        long indexedPages;
        Long lastLoaded, lastCrawled;
        Integer httpStatus;
        Classification classification;
        Discovery discovery;

        Builder(final String host, final String collection) { this.host = host; this.collection = collection; }
        public Builder scheme(final String v) { this.scheme = v; return this; }
        public Builder startUrl(final String v) { this.startUrl = v; return this; }
        public Builder title(final String v) { this.title = v; return this; }
        public Builder description(final String v) { this.description = v; return this; }
        public Builder crawlStatus(final String v) { this.crawlStatus = v; return this; }
        public Builder indexedPages(final long v) { this.indexedPages = v; return this; }
        public Builder lastLoaded(final Long v) { this.lastLoaded = v; return this; }
        public Builder lastCrawled(final Long v) { this.lastCrawled = v; return this; }
        public Builder httpStatus(final Integer v) { this.httpStatus = v; return this; }
        public Builder classification(final Classification v) { this.classification = v; return this; }
        public Builder discovery(final Discovery v) { this.discovery = v; return this; }
        public String host() { return this.host; }
        public String collection() { return this.collection; }
        public String scheme() { return this.scheme; }
        public DomainCandidate build() { return new DomainCandidate(this); }
    }
}
