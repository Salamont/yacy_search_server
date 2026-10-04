/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.report;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Counts exclusions that YaCy keeps only in its in-memory ErrorCache (noindex,
 * canonical, URL/content filters, blacklist, parser and other processing refusals)
 * for the crawls being tracked. The cache holds the latest 1000 entries in insertion
 * order; it is read newest-first with a growing window up to the last entry already
 * seen. If that entry has been evicted, entries may be lost and every tracked crawl is
 * marked incomplete. Entries YaCy also stores in the index are not counted here.
 */
public final class ExclusionTracker {
    public static final String EXCL_NOINDEX = "excl_noindex";
    public static final String EXCL_CANONICAL = "excl_canonical";
    public static final String EXCL_FILTER = "excl_filter";
    public static final String EXCL_BLACKLIST = "excl_blacklist";
    public static final String EXCL_OTHER = "excl_other";
    /** Read windows; the last one is the ErrorCache capacity. */
    static final int[] WINDOWS = {1, 8, 64, 256, 1000};
    static final int MAX_TRACKED = 10000;
    private static final String[] COUNTED = {"FINAL_PROCESS_CONTEXT ", "FINAL_LOAD_CONTEXT "};

    /** One ErrorCache entry. */
    public static final class Event {
        final String id, host, reason;
        final Set<String> collections;
        final long at;

        public Event(final String id, final String host, final Set<String> collections, final String reason, final long at) {
            this.id = id; this.host = host; this.reason = reason; this.at = at;
            this.collections = collections == null ? Set.of() : collections;
        }
    }

    /** The newest {@code max} entries, oldest first. */
    @FunctionalInterface
    public interface Source {
        List<Event> newest(int max);
    }

    /** Exclusion counts of one crawl; complete only if no entry can have been missed. */
    public static final class Tally {
        public final Map<String, Long> counts;
        public final boolean complete;

        Tally(final Map<String, Long> counts, final boolean complete) {
            this.counts = Collections.unmodifiableMap(new TreeMap<>(counts));
            this.complete = complete;
        }
    }

    private static final class Tracked {
        final String host, collection;
        final long startedAt;
        final Map<String, Long> counts = new TreeMap<>();
        boolean complete;

        Tracked(final String host, final String collection, final long startedAt, final boolean complete) {
            this.host = host; this.collection = collection; this.startedAt = startedAt; this.complete = complete;
        }
    }

    private final Source source;
    private final Map<String, Tracked> tracked = new HashMap<>();
    /** Id of the newest entry read so far; "" for an empty cache; null before the baseline. */
    private String lastSeen;
    private long coveredSince = Long.MAX_VALUE, lastPollAt;

    public ExclusionTracker(final Source source) {
        this.source = source;
    }

    /**
     * Starts counting for a crawl (idempotent). Counting is complete only if continuous
     * reading began before the crawl started and no read happened since its start.
     */
    public synchronized void track(final String key, final String host, final String collection, final long startedAt) {
        if (this.tracked.containsKey(key)) return;
        if (this.tracked.size() >= MAX_TRACKED) return;
        this.tracked.put(key, new Tracked(HostNames.normalize(host), collection, startedAt,
                this.coveredSince <= startedAt && this.lastPollAt <= startedAt));
    }

    public synchronized Tally tally(final String key) {
        final Tracked t = this.tracked.get(key);
        return t == null ? null : new Tally(t.counts, t.complete);
    }

    public synchronized Tally untrack(final String key) {
        final Tracked t = this.tracked.remove(key);
        return t == null ? null : new Tally(t.counts, t.complete);
    }

    public synchronized int size() {
        return this.tracked.size();
    }

    /** Reads new ErrorCache entries and attributes them to tracked crawls. */
    public synchronized void poll(final long now) {
        if (this.lastSeen == null) { // baseline: entries from before now are not attributed
            final List<Event> newest = this.source.newest(1);
            this.lastSeen = newest.isEmpty() ? "" : newest.get(newest.size() - 1).id;
            this.coveredSince = now;
            this.lastPollAt = now;
            return;
        }
        List<Event> read = List.of(), fresh = List.of();
        boolean loss = false;
        for (int i = 0; i < WINDOWS.length; i++) {
            read = this.source.newest(WINDOWS[i]);
            final int seen = indexOf(read, this.lastSeen);
            if (seen >= 0) { fresh = read.subList(seen + 1, read.size()); break; }
            if (read.size() < WINDOWS[i] || i == WINDOWS.length - 1) {
                fresh = read; // the whole cache, or its capacity
                loss = !this.lastSeen.isEmpty() || read.size() >= WINDOWS[WINDOWS.length - 1];
                break;
            }
        }
        for (final Event event : fresh) attribute(event);
        if (loss) {
            for (final Tracked t : this.tracked.values()) t.complete = false;
            this.coveredSince = now;
        }
        this.lastSeen = read.isEmpty() ? "" : read.get(read.size() - 1).id;
        this.lastPollAt = now;
    }

    private static int indexOf(final List<Event> events, final String id) {
        if (id.isEmpty()) return -1;
        for (int i = events.size() - 1; i >= 0; i--) if (id.equals(events.get(i).id)) return i;
        return -1;
    }

    private void attribute(final Event event) {
        if (event.reason == null) return;
        String detail = null;
        for (final String prefix : COUNTED) if (event.reason.startsWith(prefix)) detail = event.reason.substring(prefix.length());
        if (detail == null) return; // stored in the index as well; counted from there
        final String host;
        try {
            host = HostNames.normalize(event.host);
        } catch (final IllegalArgumentException notAHost) {
            return;
        }
        Tracked target = null;
        for (final Tracked t : this.tracked.values()) {
            if (t.host.equals(host) && event.collections.contains(t.collection) && t.startedAt <= event.at
                    && (target == null || t.startedAt > target.startedAt)) target = t;
        }
        if (target != null) target.counts.merge(category(detail), 1L, Long::sum);
    }

    static String category(final String reason) {
        final String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("noindexing rule") || r.contains("denied by rule in document") || r.contains("index-control")) return EXCL_NOINDEX;
        if (r.contains("canonical")) return EXCL_CANONICAL;
        if (r.contains("blacklist")) return EXCL_BLACKLIST;
        if (r.contains("regular expression") || r.contains("profile rule") || r.contains("filter")) return EXCL_FILTER;
        return EXCL_OTHER;
    }
}
