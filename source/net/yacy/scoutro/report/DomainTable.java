/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import net.yacy.cora.order.Base64Order;
import net.yacy.cora.util.SpaceExceededException;
import net.yacy.kelondro.blob.Tables;

/**
 * Current host/crawl status, one row per normalized host and collection, in YaCy's
 * {@code sb.tables}. A newer crawl overwrites the row and moves the previous crawl
 * section once to {@code prev_*}. Rows are verified against host and collection on
 * every access; anything unexpected is left untouched.
 */
public final class DomainTable {
    public static final String TABLE = "scoutro_domains";
    /** Key length of YaCy's WorkTables ({@code sb.tables}). */
    public static final int KEY_LENGTH = 12;
    static final String VERSION = "1";
    static final String PREV = "prev_";
    private static final String KEY_DOMAIN = "scoutro-domains/v1";
    private static final Object LOCK = new Object();

    /** Outcome of {@link #complete}; only CREATED, UPDATED and SHIFTED write. */
    public enum Status { CREATED, UNCHANGED, UPDATED, SHIFTED, IGNORED_PREVIOUS, IGNORED_OLDER, CONFLICT, KEY_COLLISION, INVALID_ROW }

    /** Outcome of {@link #read}. */
    public enum ReadStatus { FOUND, ABSENT, KEY_COLLISION, INVALID_ROW }

    public static final class Entry {
        public final String host, collection;
        public final long updatedAt;
        public final CrawlSnapshot current;
        /** The crawl before {@link #current}, or null. */
        public final CrawlSnapshot previous;

        Entry(final String host, final String collection, final long updatedAt,
                final CrawlSnapshot current, final CrawlSnapshot previous) {
            this.host = host; this.collection = collection; this.updatedAt = updatedAt;
            this.current = current; this.previous = previous;
        }
    }

    public static final class Lookup {
        public final ReadStatus status;
        /** Present only for FOUND. */
        public final Entry entry;
        Lookup(final ReadStatus status, final Entry entry) { this.status = status; this.entry = entry; }
    }

    private static final class InvalidRow extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private final Tables tables;

    /** @param tables YaCy tables with 12-byte keys, normally {@code Switchboard.tables}. */
    public DomainTable(final Tables tables) {
        if (tables == null) throw new IllegalArgumentException("tables");
        this.tables = tables;
    }

    public static String collection(final String collection) {
        if (collection == null || !collection.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid collection.");
        return collection;
    }

    /**
     * Deterministic 12-byte key of a host and a collection: MD5 over a versioned,
     * NUL-separated string in YaCy's enhanced Base64 alphabet. The host is normalized
     * first; the collection is case-sensitive.
     */
    public static byte[] key(final String host, final String collection) {
        final String value = KEY_DOMAIN + '\0' + HostNames.normalize(host) + '\0' + collection(collection);
        try {
            final byte[] md5 = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64Order.enhancedCoder.encodeSubstring(md5, KEY_LENGTH);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    public Lookup read(final String host, final String collection) throws IOException {
        final String h = HostNames.normalize(host), c = collection(collection);
        synchronized (LOCK) {
            final Map<String, byte[]> row = select(key(h, c));
            if (row == null) return new Lookup(ReadStatus.ABSENT, null);
            if (!matches(row, h, c)) return new Lookup(ReadStatus.KEY_COLLISION, null);
            try {
                return new Lookup(ReadStatus.FOUND, parse(row, h, c));
            } catch (final InvalidRow invalid) {
                return new Lookup(ReadStatus.INVALID_ROW, null);
            }
        }
    }

    /**
     * Records a crawl of a host in a collection. Repeating the same crawl never moves
     * the current section to prev_* again; late reports of older crawls are ignored.
     */
    public Status complete(final String host, final String collection, final CrawlSnapshot crawl, final long now)
            throws IOException {
        if (crawl == null) throw new IllegalArgumentException("crawl");
        final String h = HostNames.normalize(host), c = collection(collection);
        final byte[] key = key(h, c);
        synchronized (LOCK) {
            final Map<String, byte[]> row = select(key);
            if (row == null) {
                write(key, h, c, now, crawl, null);
                return Status.CREATED;
            }
            if (!matches(row, h, c)) return Status.KEY_COLLISION;
            final Entry entry;
            try {
                entry = parse(row, h, c);
            } catch (final InvalidRow invalid) {
                return Status.INVALID_ROW;
            }
            switch (crawl.relate(entry.current)) {
                case CONFLICT: return Status.CONFLICT;
                case SAME:
                    if (crawl.equals(entry.current)) return Status.UNCHANGED;
                    write(key, h, c, now, crawl, entry.previous);
                    return Status.UPDATED;
                default:
            }
            if (entry.previous != null && crawl.relate(entry.previous) != CrawlSnapshot.Relation.DIFFERENT)
                return Status.IGNORED_PREVIOUS;
            if (crawl.startedAt < entry.current.startedAt) return Status.IGNORED_OLDER;
            if (crawl.startedAt == entry.current.startedAt) return Status.CONFLICT;
            write(key, h, c, now, crawl, entry.current);
            return Status.SHIFTED;
        }
    }

    private Map<String, byte[]> select(final byte[] key) throws IOException {
        try {
            return this.tables.select(TABLE, key);
        } catch (final SpaceExceededException e) {
            throw new IOException("scoutro_domains unavailable", e);
        }
    }

    private void write(final byte[] key, final String host, final String collection, final long now,
            final CrawlSnapshot current, final CrawlSnapshot previous) throws IOException {
        final Map<String, byte[]> row = new HashMap<>();
        put(row, "v", VERSION);
        put(row, "host", host);
        put(row, "collection", collection);
        put(row, "updated_at", Long.toString(now));
        section(row, "", current);
        if (previous != null) section(row, PREV, previous);
        this.tables.insert(TABLE, key, row); // replaces the complete row
    }

    private static void section(final Map<String, byte[]> row, final String prefix, final CrawlSnapshot s) {
        put(row, prefix + "crawl_id", s.crawlId);
        put(row, prefix + "started_at", Long.toString(s.startedAt));
        if (s.startMarker != null) put(row, prefix + "start_marker", s.startMarker);
        if (s.endedAt != null) put(row, prefix + "ended_at", Long.toString(s.endedAt));
        if (s.job != null) put(row, prefix + "job", s.job);
        if (s.discoveryDomain != null) put(row, prefix + "discovery_domain", s.discoveryDomain);
        for (final Map.Entry<String, Long> e : s.counters.entrySet()) put(row, prefix + "n_" + e.getKey(), Long.toString(e.getValue()));
        for (final Map.Entry<String, String> e : s.labels.entrySet()) put(row, prefix + "s_" + e.getKey(), e.getValue());
    }

    private static void put(final Map<String, byte[]> row, final String column, final String value) {
        row.put(column, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(final Map<String, byte[]> row, final String column) {
        final byte[] value = row.get(column);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    private static boolean matches(final Map<String, byte[]> row, final String host, final String collection) {
        return host.equals(text(row, "host")) && collection.equals(text(row, "collection"));
    }

    private static Entry parse(final Map<String, byte[]> row, final String host, final String collection) throws InvalidRow {
        if (!VERSION.equals(text(row, "v"))) throw new InvalidRow();
        final Map<String, String> current = new TreeMap<>(), previous = new TreeMap<>();
        for (final String column : row.keySet()) {
            if (column.equals("v") || column.equals("host") || column.equals("collection") || column.equals("updated_at")) continue;
            if (column.startsWith(PREV)) previous.put(column.substring(PREV.length()), text(row, column));
            else current.put(column, text(row, column));
        }
        try {
            final long updatedAt = Long.parseLong(text(row, "updated_at"));
            return new Entry(host, collection, updatedAt, snapshot(current), previous.isEmpty() ? null : snapshot(previous));
        } catch (final RuntimeException invalid) {
            throw new InvalidRow();
        }
    }

    private static CrawlSnapshot snapshot(final Map<String, String> columns) throws InvalidRow {
        final String started = columns.get("started_at");
        if (started == null) throw new InvalidRow();
        final CrawlSnapshot.Builder b = CrawlSnapshot.builder(columns.get("crawl_id"), Long.parseLong(started));
        for (final Map.Entry<String, String> e : columns.entrySet()) {
            final String column = e.getKey(), value = e.getValue();
            switch (column) {
                case "crawl_id": case "started_at": break;
                case "start_marker": b.startMarker(value); break;
                case "ended_at": b.endedAt(Long.parseLong(value)); break;
                case "job": b.job(value); break;
                case "discovery_domain": b.discoveryDomain(value); break;
                default:
                    if (column.startsWith("n_")) b.counter(column.substring(2), Long.parseLong(value));
                    else if (column.startsWith("s_")) b.label(column.substring(2), value);
                    else throw new InvalidRow();
            }
        }
        return b.build();
    }
}
