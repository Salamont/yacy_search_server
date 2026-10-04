/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.report;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One crawl of one host in one collection, as stored in the crawl section of a
 * {@link DomainTable} row. Counters and labels are bounded; their concrete names
 * are defined by the capture phase.
 */
public final class CrawlSnapshot {
    public static final int MAX_COUNTERS = 32;
    public static final int MAX_LABELS = 8;
    private static final String NAME = "[a-z][a-z0-9_]{0,31}";
    private static final String LABEL_VALUE = "[A-Za-z0-9_.:-]{1,64}";

    /** How two crawl reports relate to each other. */
    enum Relation { SAME, CONFLICT, DIFFERENT }

    public final String crawlId;
    /** Scoutro start marker (32 lower-case hex) or null for crawls started without one. */
    public final String startMarker;
    public final long startedAt;
    /** End time in epoch milliseconds, or null while unknown. */
    public final Long endedAt;
    /** Discovery job UUID, or null for crawls outside Discovery. */
    public final String job;
    /** Registrable Discovery domain this host belongs to; a link only, never a key. */
    public final String discoveryDomain;
    public final Map<String, Long> counters;
    public final Map<String, String> labels;

    private CrawlSnapshot(final Builder b) {
        if (b.crawlId == null || !b.crawlId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid crawl id.");
        if (b.startMarker != null && !b.startMarker.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Invalid start marker.");
        if (b.startedAt <= 0) throw new IllegalArgumentException("Invalid start time.");
        if (b.endedAt != null && b.endedAt < b.startedAt) throw new IllegalArgumentException("Invalid end time.");
        if (b.job != null && !UUID.fromString(b.job).toString().equals(b.job))
            throw new IllegalArgumentException("Invalid job id.");
        if (b.counters.size() > MAX_COUNTERS || b.labels.size() > MAX_LABELS)
            throw new IllegalArgumentException("Too many crawl values.");
        for (final Map.Entry<String, Long> e : b.counters.entrySet())
            if (!e.getKey().matches(NAME) || e.getValue() == null || e.getValue() < 0)
                throw new IllegalArgumentException("Invalid counter " + e.getKey());
        for (final Map.Entry<String, String> e : b.labels.entrySet())
            if (!e.getKey().matches(NAME) || e.getValue() == null || !e.getValue().matches(LABEL_VALUE))
                throw new IllegalArgumentException("Invalid label " + e.getKey());
        this.crawlId = b.crawlId;
        this.startMarker = b.startMarker;
        this.startedAt = b.startedAt;
        this.endedAt = b.endedAt;
        this.job = b.job;
        this.discoveryDomain = b.discoveryDomain == null ? null : HostNames.normalize(b.discoveryDomain);
        this.counters = Collections.unmodifiableMap(new TreeMap<>(b.counters));
        this.labels = Collections.unmodifiableMap(new TreeMap<>(b.labels));
    }

    public static Builder builder(final String crawlId, final long startedAt) {
        return new Builder(crawlId, startedAt);
    }

    /**
     * Same crawl: equal start markers, or equal crawl id and start time when at least one
     * side has no marker. Equal markers with a different id or start time, or the same id
     * and start time with two different markers, cannot be ordered and are a conflict.
     */
    Relation relate(final CrawlSnapshot other) {
        final boolean markers = this.startMarker != null && other.startMarker != null;
        final boolean sameMarker = markers && this.startMarker.equals(other.startMarker);
        final boolean sameIdAndTime = this.crawlId.equals(other.crawlId) && this.startedAt == other.startedAt;
        if (sameMarker && sameIdAndTime) return Relation.SAME;
        if (sameMarker || (sameIdAndTime && markers)) return Relation.CONFLICT;
        return sameIdAndTime ? Relation.SAME : Relation.DIFFERENT;
    }

    @Override public boolean equals(final Object o) {
        if (!(o instanceof CrawlSnapshot)) return false;
        final CrawlSnapshot s = (CrawlSnapshot) o;
        return this.crawlId.equals(s.crawlId) && Objects.equals(this.startMarker, s.startMarker)
                && this.startedAt == s.startedAt && Objects.equals(this.endedAt, s.endedAt)
                && Objects.equals(this.job, s.job) && Objects.equals(this.discoveryDomain, s.discoveryDomain)
                && this.counters.equals(s.counters) && this.labels.equals(s.labels);
    }

    @Override public int hashCode() {
        return Objects.hash(this.crawlId, this.startMarker, this.startedAt, this.endedAt, this.job,
                this.discoveryDomain, this.counters, this.labels);
    }

    public static final class Builder {
        private final String crawlId;
        private final long startedAt;
        private String startMarker, job, discoveryDomain;
        private Long endedAt;
        private final Map<String, Long> counters = new TreeMap<>();
        private final Map<String, String> labels = new TreeMap<>();

        private Builder(final String crawlId, final long startedAt) {
            this.crawlId = crawlId;
            this.startedAt = startedAt;
        }

        public Builder startMarker(final String value) { this.startMarker = value; return this; }
        public Builder endedAt(final Long value) { this.endedAt = value; return this; }
        public Builder job(final String value) { this.job = value; return this; }
        public Builder discoveryDomain(final String value) { this.discoveryDomain = value; return this; }
        public Builder counter(final String name, final long value) { this.counters.put(name, value); return this; }
        public Builder label(final String name, final String value) { this.labels.put(name, value); return this; }
        public CrawlSnapshot build() { return new CrawlSnapshot(this); }
    }
}
