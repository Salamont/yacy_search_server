/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;

import net.yacy.cora.util.ConcurrentLog;

/**
 * Records every finished Scoutro crawl once in {@link DomainTable}. Each tick tracks
 * the active Scoutro crawls, reads the ErrorCache, notices crawls whose profile is no
 * longer active and captures them after a settle delay that lets indexing finish.
 * Crawls that ended while Scoutro was not running are recovered once from the
 * terminated profiles. Discovery only adds job context; it never waits for a capture.
 */
public final class CaptureService {
    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-REPORT");
    static final int MAX_CAPTURES_PER_TICK = 10;
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_CONTEXTS = 10000;

    /** Scoutro crawls as YaCy currently knows them. */
    public interface Crawls {
        /** Crawls started by Scoutro whose YaCy profile is active. */
        List<CrawlOutcome.Crawl> active() throws IOException;
        /** Crawls started by Scoutro whose YaCy profile is terminated. */
        List<CrawlOutcome.Crawl> terminated() throws IOException;
        /** The Scoutro crawl started with this marker, or null. */
        CrawlOutcome.Crawl byMarker(String marker) throws IOException;
    }

    private static final class Context {
        final String job, domain;
        Context(final String job, final String domain) { this.job = job; this.domain = domain; }
    }

    private static final class Pending {
        final CrawlOutcome.Crawl crawl;
        long due;
        int attempts;
        Pending(final CrawlOutcome.Crawl crawl, final long due) { this.crawl = crawl; this.due = due; }
    }

    private final Crawls crawls;
    private final ExclusionTracker exclusions;
    private final CrawlOutcome outcome;
    private final DomainTable table;
    private final LongSupplier clock;
    private final long settleMillis;
    private final Map<String, CrawlOutcome.Crawl> running = new HashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Context> contexts = new LinkedHashMap<String, Context>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;
        @Override protected boolean removeEldestEntry(final Map.Entry<String, Context> eldest) { return size() > MAX_CONTEXTS; }
    };
    private final ConcurrentLinkedQueue<String[]> discoveryEvents = new ConcurrentLinkedQueue<>();
    private boolean recovered;

    public CaptureService(final Crawls crawls, final ExclusionTracker exclusions, final CrawlOutcome outcome,
            final DomainTable table, final LongSupplier clock, final long settleMillis) {
        this.crawls = crawls;
        this.exclusions = exclusions;
        this.outcome = outcome;
        this.table = table;
        this.clock = clock;
        this.settleMillis = Math.max(0, settleMillis);
    }

    static String key(final CrawlOutcome.Crawl crawl) {
        return crawl.crawlId + "@" + crawl.startedAt + (crawl.startMarker == null ? "" : "#" + crawl.startMarker);
    }

    /** Discovery accepted a crawl start; called on the Discovery coordinator, never blocks. */
    public void discoveryAccepted(final String marker, final String job, final String domain) {
        this.discoveryEvents.add(new String[] {"accepted", marker, job, domain});
    }

    /** Discovery saw the crawl of an attempt terminated; it is captured if it is not yet recorded. */
    public void discoveryTerminated(final String marker, final String job, final String domain) {
        this.discoveryEvents.add(new String[] {"terminated", marker, job, domain});
    }

    /** Records a Discovery precheck result for a host. */
    public DomainTable.Status precheck(final String host, final String collection, final PrecheckResult result) throws IOException {
        return this.table.precheck(host, collection, result, this.clock.getAsLong());
    }

    public synchronized int pendingCount() {
        return this.pending.size();
    }

    /** One monitor step. Failures of single crawls are retried; nothing here starts or stops a crawl. */
    public synchronized void tick() throws IOException {
        final long now = this.clock.getAsLong();
        drainDiscoveryEvents(now);
        final Set<String> active = new HashSet<>();
        for (final CrawlOutcome.Crawl crawl : this.crawls.active()) {
            final String key = key(crawl);
            active.add(key);
            this.running.put(key, crawl);
            this.exclusions.track(key, crawl.host, crawl.collection, crawl.startedAt);
        }
        this.exclusions.poll(now);
        for (final Iterator<Map.Entry<String, CrawlOutcome.Crawl>> i = this.running.entrySet().iterator(); i.hasNext();) {
            final Map.Entry<String, CrawlOutcome.Crawl> e = i.next();
            if (active.contains(e.getKey())) continue;
            i.remove();
            schedule(e.getValue(), now, now + this.settleMillis);
        }
        if (!this.recovered) {
            for (final CrawlOutcome.Crawl crawl : this.crawls.terminated()) {
                if (!active.contains(key(crawl))) schedule(crawl, null, now + this.settleMillis);
            }
            this.recovered = true;
        }
        int captured = 0;
        for (final Iterator<Pending> i = this.pending.values().iterator(); i.hasNext() && captured < MAX_CAPTURES_PER_TICK;) {
            final Pending p = i.next();
            if (p.due > now) continue;
            captured++;
            if (capture(p, now)) i.remove();
        }
    }

    private void drainDiscoveryEvents(final long now) throws IOException {
        String[] event;
        while ((event = this.discoveryEvents.poll()) != null) {
            this.contexts.put(event[1], new Context(event[2], event[3]));
            if ("terminated".equals(event[0])) {
                final CrawlOutcome.Crawl crawl = this.crawls.byMarker(event[1]);
                if (crawl != null && !this.running.containsKey(key(crawl))) schedule(crawl, null, now + this.settleMillis);
            }
        }
    }

    /** endedAt: when the end was observed, or null if unknown. */
    private void schedule(final CrawlOutcome.Crawl crawl, final Long endedAt, final long due) {
        final String key = key(crawl);
        if (this.pending.containsKey(key)) return;
        if (recorded(crawl)) {
            this.exclusions.untrack(key);
            return;
        }
        final CrawlOutcome.Crawl ended = endedAt == null ? crawl : new CrawlOutcome.Crawl(crawl.crawlId, crawl.startMarker,
                crawl.host, crawl.collection, crawl.startedAt, Math.max(endedAt, crawl.startedAt), crawl.depth, crawl.maxPages, crawl.scheme);
        this.pending.put(key, new Pending(ended, due));
    }

    /** True if this crawl, or a newer one, is already in the table, or the row cannot be written. */
    private boolean recorded(final CrawlOutcome.Crawl crawl) {
        final DomainTable.Lookup lookup;
        try {
            lookup = this.table.read(crawl.host, crawl.collection);
        } catch (final IOException e) {
            return false; // retried when captured
        }
        if (lookup.status == DomainTable.ReadStatus.ABSENT) return false;
        if (lookup.status != DomainTable.ReadStatus.FOUND) {
            LOG.warn("scoutro_domains row of " + crawl.host + " in " + crawl.collection + " is " + lookup.status + "; crawl not recorded");
            return true;
        }
        final DomainTable.Entry entry = lookup.entry;
        if (entry.current == null) return false;
        final CrawlSnapshot identity = crawl.identity();
        if (identity.relate(entry.current) != CrawlSnapshot.Relation.DIFFERENT) return true;
        if (entry.previous != null && identity.relate(entry.previous) != CrawlSnapshot.Relation.DIFFERENT) return true;
        return entry.current.startedAt > crawl.startedAt;
    }

    /** @return true when the crawl needs no further attempt. */
    private boolean capture(final Pending p, final long now) {
        final CrawlOutcome.Crawl crawl = p.crawl;
        final String key = key(crawl);
        Map<String, Long> pages;
        try {
            pages = this.outcome.pages(crawl);
        } catch (final IOException | RuntimeException e) {
            if (++p.attempts < MAX_ATTEMPTS) {
                p.due = now + Math.max(this.settleMillis, 1000L) * p.attempts;
                return false;
            }
            LOG.warn("index unavailable for crawl " + crawl.crawlId + " of " + crawl.host + "; recorded without page counts");
            pages = new LinkedHashMap<>();
        }
        final Context context = crawl.startMarker == null ? null : this.contexts.get(crawl.startMarker);
        final DomainTable.Status status;
        try {
            final CrawlSnapshot snapshot = CrawlOutcome.snapshot(crawl, pages, this.exclusions.tally(key),
                    context == null ? null : context.job, context == null ? null : context.domain);
            status = this.table.complete(crawl.host, crawl.collection, snapshot, now);
        } catch (final IOException e) {
            if (++p.attempts < MAX_ATTEMPTS) {
                p.due = now + Math.max(this.settleMillis, 1000L) * p.attempts;
                return false;
            }
            LOG.warn("scoutro_domains unavailable; crawl " + crawl.crawlId + " of " + crawl.host + " not recorded");
            this.exclusions.untrack(key);
            return true;
        } catch (final IllegalArgumentException invalid) {
            LOG.warn("crawl " + crawl.crawlId + " of " + crawl.host + " has invalid report data; not recorded");
            this.exclusions.untrack(key);
            return true;
        }
        if (status == DomainTable.Status.KEY_COLLISION || status == DomainTable.Status.INVALID_ROW
                || status == DomainTable.Status.CONFLICT)
            LOG.warn("crawl " + crawl.crawlId + " of " + crawl.host + " not recorded: " + status);
        this.exclusions.untrack(key);
        return true;
    }

    /** Snapshot of running crawl keys, for tests and diagnostics. */
    synchronized List<String> runningKeys() {
        return new ArrayList<>(this.running.keySet());
    }
}
