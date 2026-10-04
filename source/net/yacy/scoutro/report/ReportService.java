/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;

/**
 * Crawl reports from the three sources: the live index (current page state),
 * {@code scoutro_domains} (current host and crawl status) and the yearly rollups
 * (history). Table aggregates come from one cached scan. When the live index does not
 * answer within its budget, the latest rollup snapshot of the collection is returned
 * and marked as such. {@link #daily} writes one rollup per Discovery job and day with
 * activity, catching up at most {@link #CATCH_UP_DAYS} days.
 */
public final class ReportService {
    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-REPORT");
    static final int CATCH_UP_DAYS = 7;
    static final int FALLBACK_DAYS = 31;
    static final int MAX_JOB_RANGE_DAYS = 3 * 366;
    static final long DAILY_RETRY_MILLIS = 600_000L;
    public static final int MAX_HOST_LIMIT = 100, MAX_HOST_OFFSET = 10000;
    public static final List<String> HOST_FILTERS = List.of("all", "stale", "precheck", "partial", "not_indexed",
            "not_reloaded", "unknown", "coverage_partial");
    private static final int HOST_LIST_CACHE = 16;
    private static final long DAY = 86_400_000L;

    /** A Discovery job as the report needs it. */
    public static final class Job {
        public final String id, name, fingerprint;
        public final int staleDays;

        public Job(final String id, final String name, final String fingerprint, final int staleDays) {
            this.id = RollupStore.job(id);
            this.name = name;
            this.fingerprint = fingerprint;
            this.staleDays = staleDays;
        }
    }

    @FunctionalInterface
    public interface Jobs {
        List<Job> jobs() throws IOException;
    }

    /** Locally observed hosts linking to a host (YaCy's host link graph), with their link counts. */
    @FunctionalInterface
    public interface ReferringHosts {
        Map<String, Integer> of(String host) throws IOException;
    }

    public static final int REFERRING_LIMIT = 20;

    /** Current status of a group of rows: one collection or one job. */
    static final class Tally {
        long hosts, crawled, precheckOnly, latestPrecheck, coveragePartial, stale, lastCrawl;
        final Map<String, Long> outcomes = new TreeMap<>(), prechecks = new TreeMap<>(), counters = new TreeMap<>();
        final Set<String> collections = new TreeSet<>();

        JsonObject json() {
            return new JsonObject().put("hosts", this.hosts).put("crawled", this.crawled).put("precheck_only", this.precheckOnly)
                    .put("latest_attempt_precheck", this.latestPrecheck).put("coverage_partial", this.coveragePartial)
                    .put("stale", this.stale).put("last_crawl", this.lastCrawl == 0 ? JsonObject.NULL : iso(this.lastCrawl))
                    .put("outcomes", map(this.outcomes)).put("prechecks", map(this.prechecks)).put("counters", map(this.counters));
        }
    }

    /** Crawls and prechecks of one job on one day. */
    static final class Day {
        long crawls, coveragePartial;
        final Map<String, Long> outcomes = new TreeMap<>(), pages = new TreeMap<>(), exclusions = new TreeMap<>(), prechecks = new TreeMap<>();
        final Set<String> collections = new TreeSet<>();

        boolean active() {
            return this.crawls > 0 || !this.prechecks.isEmpty();
        }

        JsonObject json() {
            final JsonArray c = new JsonArray();
            for (final String collection : this.collections) c.put(collection);
            return new JsonObject().put("collections", c).put("crawls", this.crawls).put("outcomes", map(this.outcomes))
                    .put("coverage_partial", this.coveragePartial).put("pages", map(this.pages))
                    .put("exclusions", map(this.exclusions)).put("prechecks", map(this.prechecks));
        }
    }

    /** The first matching hosts of one collection and filter, in report order. */
    private static final class HostList {
        final List<JsonObject> items;
        final long total, at;
        HostList(final List<JsonObject> items, final long total, final long at) { this.items = items; this.total = total; this.at = at; }
    }

    private static final class Aggregates {
        final Map<String, Tally> collections = new HashMap<>(), jobs = new HashMap<>();
        long rows, invalid, at;
    }

    private final DomainTable table;
    private final IndexFacets facets;
    private final RollupStore rollups;
    private final Jobs jobs;
    private final LongSupplier clock;
    private final ZoneId zone;
    private final long cacheMillis;
    private final int defaultStaleDays;
    private final String version;
    private final long graceMillis;
    private final ReferringHosts referring;
    private Aggregates cached;
    private final Map<String, HostList> hostLists = new java.util.LinkedHashMap<String, HostList>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;
        @Override protected boolean removeEldestEntry(final Map.Entry<String, HostList> eldest) { return size() > HOST_LIST_CACHE; }
    };
    private LocalDate lastDaily;
    private long dailyRetryAt;

    /**
     * @param graceMillis time after local midnight before yesterday is rolled up, so that
     *                    crawls which ended just before midnight are captured first
     */
    public ReportService(final DomainTable table, final IndexFacets facets, final RollupStore rollups, final Jobs jobs,
            final LongSupplier clock, final ZoneId zone, final long cacheMillis, final int defaultStaleDays, final String version,
            final long graceMillis) {
        this(table, facets, rollups, jobs, clock, zone, cacheMillis, defaultStaleDays, version, graceMillis, null);
    }

    /** @param referring the host link graph, or null if it is not available */
    public ReportService(final DomainTable table, final IndexFacets facets, final RollupStore rollups, final Jobs jobs,
            final LongSupplier clock, final ZoneId zone, final long cacheMillis, final int defaultStaleDays, final String version,
            final long graceMillis, final ReferringHosts referring) {
        this.referring = referring;
        this.table = table;
        this.facets = facets;
        this.rollups = rollups;
        this.jobs = jobs;
        this.clock = clock;
        this.zone = zone;
        this.cacheMillis = Math.max(0, cacheMillis);
        this.defaultStaleDays = Math.max(1, defaultStaleDays);
        this.version = version;
        this.graceMillis = Math.max(0, graceMillis);
    }

    // ------------------------------------------------------------------ host

    /**
     * Row of one host and collection plus its live page state since the current crawl
     * started, its first-level directories and the locally observed referring hosts.
     */
    public JsonObject host(final String host, final String collection) throws IOException {
        final String h = HostNames.normalize(host), c = DomainTable.collection(collection);
        final DomainTable.Lookup lookup = this.table.read(h, c);
        final JsonObject out = new JsonObject().put("host", h).put("collection", c)
                .put("status", lookup.status.name().toLowerCase(java.util.Locale.ROOT));
        final long now = this.clock.getAsLong();
        Long since = null;
        if (lookup.status == DomainTable.ReadStatus.FOUND) {
            final DomainTable.Entry e = lookup.entry;
            final Map<String, Integer> staleDays = staleDays();
            final JsonObject row = new JsonObject().put("updated_at", iso(e.updatedAt));
            if (e.current != null) {
                final JsonObject current = section(e.current);
                final int days = staleDays.getOrDefault(e.current.job, this.defaultStaleDays);
                current.put("age_days", (now - crawlTime(e.current)) / DAY).put("stale_after_days", days)
                        .put("stale", now - crawlTime(e.current) > days * DAY);
                row.put("current", current);
                since = e.current.startedAt;
            } else {
                row.put("current", JsonObject.NULL);
            }
            row.put("previous", e.previous == null ? JsonObject.NULL : section(e.previous));
            row.put("precheck", e.precheck == null ? JsonObject.NULL : precheck(e.precheck));
            row.put("latest_attempt", latestIsPrecheck(e) ? "precheck" : e.current == null ? JsonObject.NULL : "crawl");
            out.put("row", row);
        } else {
            out.put("row", JsonObject.NULL);
        }
        try {
            out.put("index", this.facets.read(c, h, since)).put("index_source", "live");
        } catch (final IOException e) {
            out.put("index", JsonObject.NULL).put("index_source", JsonObject.NULL).put("index_error", "index_unavailable");
        }
        try {
            out.put("directories", this.facets.directories(c, h));
        } catch (final IOException e) {
            out.put("directories", JsonObject.NULL);
        }
        out.put("referring_hosts", referringHosts(h));
        return out;
    }

    /** Top referring hosts by link count (the host itself excluded), or null if the graph is unavailable. */
    private Object referringHosts(final String host) {
        if (this.referring == null) return JsonObject.NULL;
        final Map<String, Integer> raw;
        try {
            raw = this.referring.of(host);
        } catch (final IOException | RuntimeException e) {
            return JsonObject.NULL;
        }
        final Map<String, Long> hosts = new TreeMap<>();
        for (final Map.Entry<String, Integer> e : raw.entrySet()) {
            final String name;
            try {
                name = HostNames.normalize(e.getKey());
            } catch (final IllegalArgumentException invalid) {
                continue;
            }
            if (!name.equals(host)) hosts.merge(name, e.getValue() == null ? 0L : Math.max(0, e.getValue()), Long::sum);
        }
        final List<Map.Entry<String, Long>> sorted = new ArrayList<>(hosts.entrySet());
        sorted.sort((a, b) -> !a.getValue().equals(b.getValue()) ? Long.compare(b.getValue(), a.getValue()) : a.getKey().compareTo(b.getKey()));
        final JsonArray items = new JsonArray();
        long links = 0;
        for (final Map.Entry<String, Long> e : sorted) links += e.getValue();
        for (final Map.Entry<String, Long> e : sorted.subList(0, Math.min(REFERRING_LIMIT, sorted.size())))
            items.put(new JsonObject().put("host", e.getKey()).put("links", e.getValue()));
        return new JsonObject().put("items", items).put("hosts", sorted.size()).put("links", links)
                .put("truncated", sorted.size() > REFERRING_LIMIT);
    }

    // ------------------------------------------------------------ collection

    /** Table aggregate of a collection plus its live page state, or the latest rollup snapshot. */
    public JsonObject collection(final String collection) throws IOException {
        final String c = DomainTable.collection(collection);
        final Aggregates a = aggregates();
        final Tally t = a.collections.get(c);
        final JsonObject out = new JsonObject().put("collection", c).put("table", (t == null ? new Tally() : t).json())
                .put("table_scanned_at", iso(a.at));
        try {
            out.put("index", this.facets.read(c, null, null)).put("index_source", "live").put("index_as_of", iso(this.clock.getAsLong()));
        } catch (final IOException live) {
            final JsonObject[] fallback = latestSnapshot(c, a);
            if (fallback == null) {
                out.put("index", JsonObject.NULL).put("index_source", JsonObject.NULL).put("index_error", "index_unavailable");
            } else {
                out.put("index", fallback[0]).put("index_source", "rollup").put("index_as_of", fallback[1].getString("day"))
                        .put("index_error", "index_unavailable");
            }
        }
        return out;
    }

    /** {snapshot, rollup} of the newest rollup of any job of this collection that holds an index snapshot. */
    private JsonObject[] latestSnapshot(final String collection, final Aggregates a) {
        final LocalDate today = today();
        JsonObject[] best = null;
        for (final Map.Entry<String, Tally> job : a.jobs.entrySet()) {
            if (!job.getValue().collections.contains(collection)) continue;
            try {
                for (final JsonObject line : this.rollups.read(job.getKey(), today.minusDays(FALLBACK_DAYS), today)) {
                    final JsonObject index = line.optJSONObject("index");
                    if (index == null || index.optJSONObject(collection) == null) continue;
                    if (best == null || line.getString("day").compareTo(best[1].getString("day")) > 0)
                        best = new JsonObject[] {index.getJSONObject(collection), line};
                }
            } catch (final IOException unreadable) {
                // an invalid rollup file is never a fallback
            }
        }
        return best;
    }

    /**
     * Hosts of a collection matching a filter: {@code stale} oldest crawl first, all
     * others by host name. Only offset+limit rows are kept while scanning; the result
     * is cached like the aggregates.
     */
    public synchronized JsonObject hosts(final String collection, final String filter, final int offset, final int limit) throws IOException {
        final String c = DomainTable.collection(collection);
        final String f = filter == null || filter.isEmpty() ? "all" : filter;
        if (!HOST_FILTERS.contains(f)) throw new IllegalArgumentException("Invalid filter.");
        if (offset < 0 || offset > MAX_HOST_OFFSET || limit < 1 || limit > MAX_HOST_LIMIT) throw new IllegalArgumentException("Invalid paging.");
        final long now = this.clock.getAsLong();
        final String key = c + "\0" + f;
        HostList list = this.hostLists.get(key);
        if (list == null || now - list.at >= this.cacheMillis) {
            final Map<String, Integer> staleDays = staleDays();
            final int keep = MAX_HOST_OFFSET + MAX_HOST_LIMIT;
            final java.util.Comparator<JsonObject> order = "stale".equals(f)
                    ? java.util.Comparator.<JsonObject>comparingLong(o -> o.optLong("crawl_time", 0)).thenComparing(o -> o.getString("host"))
                    : java.util.Comparator.comparing(o -> o.getString("host"));
            final java.util.TreeSet<JsonObject> top = new java.util.TreeSet<>(order);
            final long[] total = {0};
            this.table.scan(e -> {
                if (!e.collection.equals(c)) return;
                final JsonObject item = hostItem(e, now, staleDays.getOrDefault(job(e), this.defaultStaleDays));
                if (!matches(f, e, item)) return;
                total[0]++;
                top.add(item);
                if (top.size() > keep) top.pollLast();
            });
            list = new HostList(new ArrayList<>(top), total[0], now);
            this.hostLists.put(key, list);
        }
        final JsonArray items = new JsonArray();
        for (int i = offset; i < Math.min(list.items.size(), offset + limit); i++) {
            final JsonObject item = new JsonObject(list.items.get(i).toString());
            item.remove("crawl_time");
            items.put(item);
        }
        return new JsonObject().put("collection", c).put("filter", f).put("total", list.total).put("offset", offset)
                .put("limit", limit).put("items", items).put("table_scanned_at", iso(list.at));
    }

    private static String job(final DomainTable.Entry e) {
        return e.current != null && e.current.job != null ? e.current.job : e.precheck == null ? null : e.precheck.job;
    }

    private static JsonObject hostItem(final DomainTable.Entry e, final long now, final int staleDays) {
        final JsonObject item = new JsonObject().put("host", e.host).put("job", job(e) == null ? JsonObject.NULL : job(e));
        if (e.current != null) {
            final long at = crawlTime(e.current);
            item.put("crawl_time", at).put("last_crawl", iso(at)).put("age_days", (now - at) / DAY)
                    .put("stale", now - at > staleDays * DAY)
                    .put("outcome", e.current.labels.getOrDefault(CrawlOutcome.OUTCOME, "unknown"))
                    .put("coverage", e.current.labels.getOrDefault(CrawlOutcome.COVERAGE, "partial"))
                    .put("pages_ok", e.current.counters.getOrDefault(CrawlOutcome.PAGES_OK, 0L))
                    .put("scheme", e.current.labels.containsKey(CrawlOutcome.SCHEME) ? e.current.labels.get(CrawlOutcome.SCHEME) : JsonObject.NULL);
        } else {
            item.put("crawl_time", 0).put("last_crawl", JsonObject.NULL).put("age_days", JsonObject.NULL).put("stale", false)
                    .put("outcome", JsonObject.NULL).put("coverage", JsonObject.NULL).put("pages_ok", JsonObject.NULL).put("scheme", JsonObject.NULL);
        }
        final boolean precheck = latestIsPrecheck(e);
        item.put("latest_attempt", precheck ? "precheck" : e.current == null ? JsonObject.NULL : "crawl")
                .put("precheck", precheck ? e.precheck.result : JsonObject.NULL);
        return item;
    }

    private static boolean matches(final String filter, final DomainTable.Entry e, final JsonObject item) {
        switch (filter) {
            case "all": return true;
            case "stale": return item.getBoolean("stale");
            case "precheck": return "precheck".equals(item.opt("latest_attempt"));
            case "coverage_partial": return e.current != null && "partial".equals(item.opt("coverage"));
            default: return e.current != null && filter.equals(item.opt("outcome"));
        }
    }

    /** Known Discovery jobs plus jobs that only appear in the table, with their collections and counts. */
    public JsonObject jobs() throws IOException {
        final Aggregates a = aggregates();
        final Map<String, JsonObject> out = new TreeMap<>();
        for (final Job job : knownJobs()) {
            out.put(job.id, new JsonObject().put("id", job.id).put("known", true)
                    .put("name", job.name == null ? JsonObject.NULL : job.name).put("stale_after_days", job.staleDays));
        }
        for (final String id : a.jobs.keySet()) {
            if (!out.containsKey(id)) out.put(id, new JsonObject().put("id", id).put("known", false).put("name", JsonObject.NULL)
                    .put("stale_after_days", this.defaultStaleDays));
        }
        final JsonArray list = new JsonArray();
        for (final JsonObject job : out.values()) {
            final Tally t = a.jobs.get(job.getString("id"));
            final JsonArray collections = new JsonArray();
            if (t != null) for (final String c : t.collections) collections.put(c);
            job.put("collections", collections).put("hosts", t == null ? 0 : t.hosts).put("crawled", t == null ? 0 : t.crawled)
                    .put("stale", t == null ? 0 : t.stale).put("last_crawl", t == null || t.lastCrawl == 0 ? JsonObject.NULL : iso(t.lastCrawl));
            list.put(job);
        }
        return new JsonObject().put("jobs", list).put("table_scanned_at", iso(a.at));
    }

    // ------------------------------------------------------------------- job

    /** Table aggregate and rollup history of one Discovery job. */
    public JsonObject job(final String jobId, final LocalDate from, final LocalDate to) throws IOException {
        final String id = RollupStore.job(jobId);
        final LocalDate end = to == null ? today() : to, start = from == null ? end.minusDays(89) : from;
        if (end.isBefore(start) || start.plusDays(MAX_JOB_RANGE_DAYS).isBefore(end)) throw new IllegalArgumentException("Invalid day range.");
        Job known = null;
        for (final Job job : knownJobs()) if (job.id.equals(id)) known = job;
        final Tally t = aggregates().jobs.get(id);
        final JsonArray collections = new JsonArray();
        if (t != null) for (final String c : t.collections) collections.put(c);
        final JsonArray history = new JsonArray();
        for (final JsonObject line : this.rollups.read(id, start, end)) history.put(line);
        return new JsonObject().put("job", id).put("known", known != null)
                .put("name", known == null || known.name == null ? JsonObject.NULL : known.name)
                .put("stale_after_days", known == null ? this.defaultStaleDays : known.staleDays)
                .put("collections", collections).put("table", (t == null ? new Tally() : t).json())
                .put("from", start.toString()).put("to", end.toString()).put("rollups", history);
    }

    // ---------------------------------------------------------------- daily

    /**
     * Writes the rollups of the last {@link #CATCH_UP_DAYS} days that have activity and no
     * line yet; runs once per local day, after the grace time. The index snapshot is only
     * taken for yesterday, when the rollup is written, because older index states cannot
     * be reconstructed.
     */
    public synchronized void daily() throws IOException {
        final LocalDate today = today();
        final long now = this.clock.getAsLong();
        if (today.equals(this.lastDaily) || now < this.dailyRetryAt) return;
        if (now < today.atStartOfDay(this.zone).toInstant().toEpochMilli() + this.graceMillis) return;
        try {
            rollUp(today);
        } catch (final IOException | RuntimeException e) {
            this.dailyRetryAt = now + DAILY_RETRY_MILLIS; // never rescan the table on every tick
            throw e;
        }
        this.lastDaily = today;
    }

    private void rollUp(final LocalDate today) throws IOException {
        final LocalDate from = today.minusDays(CATCH_UP_DAYS), yesterday = today.minusDays(1);
        final Map<String, Map<LocalDate, Day>> days = new TreeMap<>();
        this.table.scan(e -> {
            for (final CrawlSnapshot s : new CrawlSnapshot[] {e.current, e.previous}) {
                if (s == null || s.job == null) continue;
                final LocalDate day = day(crawlTime(s));
                if (day.isBefore(from) || day.isAfter(yesterday)) continue;
                final Day d = days.computeIfAbsent(s.job, k -> new TreeMap<>()).computeIfAbsent(day, k -> new Day());
                d.collections.add(e.collection);
                d.crawls++;
                d.outcomes.merge(s.labels.getOrDefault(CrawlOutcome.OUTCOME, "unknown"), 1L, Long::sum);
                if (!"complete".equals(s.labels.get(CrawlOutcome.COVERAGE))) d.coveragePartial++;
                for (final Map.Entry<String, Long> n : s.counters.entrySet()) {
                    if (n.getKey().startsWith("pages_")) d.pages.merge(n.getKey(), n.getValue(), Long::sum);
                    else if (n.getKey().startsWith("excl_")) d.exclusions.merge(n.getKey(), n.getValue(), Long::sum);
                }
            }
            final PrecheckResult p = e.precheck;
            if (p != null && p.job != null) {
                final LocalDate day = day(p.at);
                if (!day.isBefore(from) && !day.isAfter(yesterday)) {
                    final Day d = days.computeIfAbsent(p.job, k -> new TreeMap<>()).computeIfAbsent(day, k -> new Day());
                    d.collections.add(e.collection);
                    d.prechecks.merge(p.result, 1L, Long::sum);
                }
            }
        });
        final Map<String, Job> known = new HashMap<>();
        for (final Job job : knownJobs()) known.put(job.id, job);
        for (final Map.Entry<String, Map<LocalDate, Day>> job : days.entrySet()) {
            for (final Map.Entry<LocalDate, Day> entry : job.getValue().entrySet()) {
                if (!entry.getValue().active()) continue;
                try {
                    write(job.getKey(), entry.getKey(), entry.getValue(), known.get(job.getKey()), entry.getKey().equals(yesterday));
                } catch (final IOException | IllegalArgumentException e) {
                    LOG.warn("rollup of job " + job.getKey() + " for " + entry.getKey() + " not written: " + e.getClass().getSimpleName());
                }
            }
        }
    }

    private void write(final String job, final LocalDate day, final Day d, final Job known, final boolean snapshot) throws IOException {
        JsonObject previous = null;
        for (final JsonObject line : this.rollups.read(job, day.getYear())) {
            final LocalDate at = LocalDate.parse(line.getString("day"));
            if (at.equals(day)) return; // already written
            if (at.isBefore(day)) previous = line;
        }
        if (previous == null) {
            final List<JsonObject> last = this.rollups.read(job, day.getYear() - 1);
            if (!last.isEmpty()) previous = last.get(last.size() - 1);
        }
        final JsonObject line = d.json();
        if (this.version != null) line.put("version", this.version);
        if (known != null && known.fingerprint != null) line.put("job_fingerprint", known.fingerprint);
        final JsonArray markers = new JsonArray();
        if (previous != null) {
            if (this.version != null && previous.has("version") && !this.version.equals(previous.optString("version")))
                markers.put(new JsonObject().put("type", "version_changed").put("from", previous.optString("version")).put("to", this.version));
            if (known != null && known.fingerprint != null && previous.has("job_fingerprint")
                    && !known.fingerprint.equals(previous.optString("job_fingerprint")))
                markers.put(new JsonObject().put("type", "job_changed"));
        }
        if (!markers.isEmpty()) line.put("markers", markers);
        if (snapshot) {
            final JsonObject index = new JsonObject();
            for (final String collection : d.collections) {
                try {
                    final JsonObject live = this.facets.read(collection, null, null);
                    index.put(collection, new JsonObject().put("documents", live.getLong("documents"))
                            .put("ok", live.optLong("ok", 0)).put("hosts", live.optLong("hosts", 0)));
                } catch (final IOException unavailable) {
                    // the snapshot of this collection is skipped; the rollup is still written
                }
            }
            if (!index.isEmpty()) line.put("index", index);
        }
        this.rollups.append(job, day, line);
    }

    // ------------------------------------------------------------- helpers

    private synchronized Aggregates aggregates() throws IOException {
        final long now = this.clock.getAsLong();
        if (this.cached != null && now - this.cached.at < this.cacheMillis) return this.cached;
        final Map<String, Integer> staleDays = staleDays();
        final Aggregates a = new Aggregates();
        a.at = now;
        final DomainTable.ScanResult scan = this.table.scan(e -> {
            final String job = e.current != null && e.current.job != null ? e.current.job : e.precheck == null ? null : e.precheck.job;
            add(a.collections.computeIfAbsent(e.collection, k -> new Tally()), e, now, staleDays.getOrDefault(job, this.defaultStaleDays));
            if (job != null) {
                final Tally t = a.jobs.computeIfAbsent(job, k -> new Tally());
                t.collections.add(e.collection);
                add(t, e, now, staleDays.getOrDefault(job, this.defaultStaleDays));
            }
        });
        a.rows = scan.rows;
        a.invalid = scan.invalid;
        if (scan.invalid > 0) LOG.warn(scan.invalid + " scoutro_domains rows are invalid or collide; they are not reported");
        this.cached = a;
        return a;
    }

    private static void add(final Tally t, final DomainTable.Entry e, final long now, final int staleDays) {
        t.hosts++;
        if (e.current != null) {
            t.crawled++;
            t.outcomes.merge(e.current.labels.getOrDefault(CrawlOutcome.OUTCOME, "unknown"), 1L, Long::sum);
            if (!"complete".equals(e.current.labels.get(CrawlOutcome.COVERAGE))) t.coveragePartial++;
            for (final Map.Entry<String, Long> n : e.current.counters.entrySet())
                if (n.getKey().startsWith("pages_") || n.getKey().startsWith("excl_")) t.counters.merge(n.getKey(), n.getValue(), Long::sum);
            final long at = crawlTime(e.current);
            if (now - at > staleDays * DAY) t.stale++;
            t.lastCrawl = Math.max(t.lastCrawl, at);
        }
        if (latestIsPrecheck(e)) {
            t.latestPrecheck++;
            t.prechecks.merge(e.precheck.result, 1L, Long::sum);
            if (e.current == null) t.precheckOnly++;
        }
    }

    private Map<String, Integer> staleDays() {
        final Map<String, Integer> out = new HashMap<>();
        for (final Job job : knownJobs()) out.put(job.id, job.staleDays);
        return out;
    }

    /** Discovery jobs, or none if the job store cannot be read; reports then use defaults. */
    private List<Job> knownJobs() {
        try {
            return this.jobs.jobs();
        } catch (final IOException | RuntimeException e) {
            LOG.warn("Discovery jobs unavailable for the crawl report; using defaults");
            return List.of();
        }
    }

    /** The precheck is the latest attempt if it is newer than the current crawl. */
    private static boolean latestIsPrecheck(final DomainTable.Entry e) {
        return e.precheck != null && (e.current == null || e.precheck.at > crawlTime(e.current));
    }

    private static long crawlTime(final CrawlSnapshot s) {
        return s.endedAt != null ? s.endedAt : s.startedAt;
    }

    private LocalDate today() {
        return day(this.clock.getAsLong());
    }

    private LocalDate day(final long millis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), this.zone);
    }

    static String iso(final long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    private static JsonObject map(final Map<String, Long> values) {
        final JsonObject out = new JsonObject();
        for (final Map.Entry<String, Long> e : values.entrySet()) out.put(e.getKey(), e.getValue());
        return out;
    }

    static JsonObject section(final CrawlSnapshot s) {
        final JsonObject counters = new JsonObject(), labels = new JsonObject();
        for (final Map.Entry<String, Long> e : s.counters.entrySet()) counters.put(e.getKey(), e.getValue());
        for (final Map.Entry<String, String> e : s.labels.entrySet()) labels.put(e.getKey(), e.getValue());
        return new JsonObject().put("crawl_id", s.crawlId).put("start_marker", s.startMarker == null ? JsonObject.NULL : s.startMarker)
                .put("started_at", iso(s.startedAt)).put("ended_at", s.endedAt == null ? JsonObject.NULL : iso(s.endedAt))
                .put("job", s.job == null ? JsonObject.NULL : s.job)
                .put("discovery_domain", s.discoveryDomain == null ? JsonObject.NULL : s.discoveryDomain)
                .put("counters", counters).put("labels", labels);
    }

    static JsonObject precheck(final PrecheckResult p) {
        return new JsonObject().put("at", iso(p.at)).put("result", p.result).put("detail", p.detail == null ? JsonObject.NULL : p.detail)
                .put("job", p.job == null ? JsonObject.NULL : p.job)
                .put("discovery_domain", p.discoveryDomain == null ? JsonObject.NULL : p.discoveryDomain);
    }

    /** SHA-256 over a canonical (sorted-key) serialization; the first 16 hex digits. */
    public static String fingerprint(final Object json) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical(json).getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String canonical(final Object value) {
        if (value instanceof JSONObject) {
            final JSONObject o = (JSONObject) value;
            final List<String> keys = new ArrayList<>(o.keySet());
            java.util.Collections.sort(keys);
            final StringBuilder b = new StringBuilder("{");
            for (final String k : keys) {
                if (b.length() > 1) b.append(',');
                b.append(JSONObject.quote(k)).append(':').append(canonical(o.opt(k)));
            }
            return b.append('}').toString();
        }
        if (value instanceof JSONArray) {
            final JSONArray a = (JSONArray) value;
            final StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) b.append(',');
                b.append(canonical(a.opt(i)));
            }
            return b.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        return value == null || value == JSONObject.NULL ? "null" : String.valueOf(value);
    }
}
