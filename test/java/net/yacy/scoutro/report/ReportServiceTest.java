/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Temporary tables and directories, fake index answers. */
package net.yacy.scoutro.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.solr.common.util.NamedList;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.kelondro.blob.Tables;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.search.schema.CollectionSchema;

public class ReportServiceTest {
    private static final String JOB = "3276af9c-b8b5-4b8c-bc2b-17b8d1905eb7";
    private static final String OTHER = "c334ac9e-5f0e-41f4-b738-ac432b115a4d";
    private static final long DAY = 86_400_000L;
    /** 2026-10-04T12:00:00Z */
    private static final long NOON = 1_791_115_200_000L;
    private static final Set<CollectionSchema> FIELDS = Set.of(CollectionSchema.collection_sxt, CollectionSchema.host_s,
            CollectionSchema.httpstatus_i, CollectionSchema.failtype_s, CollectionSchema.load_date_dt);

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Tables tables;
    private DomainTable table;
    private RollupStore rollups;
    private final AtomicLong clock = new AtomicLong(NOON);
    private final AtomicInteger indexCalls = new AtomicInteger();
    private final List<String> queries = new ArrayList<>();
    private boolean indexDown;
    private final List<ReportService.Job> jobs = new ArrayList<>();
    private ReportService service;

    @Before public void open() throws Exception {
        this.tables = new Tables(this.tmp.newFolder("WORK"), DomainTable.KEY_LENGTH);
        this.table = new DomainTable(this.tables);
        this.rollups = new RollupStore(RollupStore.root(this.tmp.newFolder("app").toPath()));
        this.jobs.add(new ReportService.Job(JOB, "Handwerk", "fp-1", 30));
        this.service = service("1.942-scoutro.12");
    }

    @After public void close() {
        this.tables.close();
    }

    private ReportService service(final String version) {
        final IndexFacets facets = new IndexFacets(p -> {
            this.indexCalls.incrementAndGet();
            this.queries.add(p.get("q"));
            if (this.indexDown) throw new IOException("index down");
            return answer(p.get("q").contains("host_s") ? 7 : 120, p.get("q").contains("host_s") ? 5 : 100, 9);
        }, FIELDS::contains);
        return new ReportService(this.table, facets, this.rollups, () -> new ArrayList<>(this.jobs), this.clock::get,
                ZoneOffset.UTC, 600_000L, 14, version, 15 * 60_000L);
    }

    private static Map<String, Object> count(final long n) {
        final Map<String, Object> m = new HashMap<>();
        m.put("count", n);
        return m;
    }

    private static NamedList<Object> answer(final long documents, final long ok, final long hosts) {
        final NamedList<Object> facets = new NamedList<>();
        facets.add("count", documents);
        facets.add("ok", count(ok));
        final Map<String, Object> status = new HashMap<>();
        status.put("buckets", List.of(Map.of("val", 200, "count", ok)));
        facets.add("status", status);
        facets.add("failtype", Map.of("buckets", List.of()));
        facets.add("hosts", hosts);
        facets.add("oldest", new Date(NOON - 40 * DAY));
        facets.add("newest", new Date(NOON));
        facets.add("before", count(2));
        final NamedList<Object> result = new NamedList<>();
        result.add("responseHeader", new NamedList<>());
        result.add("facets", facets);
        return result;
    }

    private void crawl(final String host, final String collection, final String job, final long ended, final String outcome,
            final String coverage, final long ok) throws IOException {
        final CrawlSnapshot s = CrawlSnapshot.builder("c" + Math.abs(host.hashCode()) + "x" + ended, ended - 60_000L).endedAt(ended)
                .job(job).counter(CrawlOutcome.PAGES_OK, ok).counter(ExclusionTracker.EXCL_NOINDEX, 1)
                .label(CrawlOutcome.OUTCOME, outcome).label(CrawlOutcome.COVERAGE, coverage).build();
        this.table.complete(host, collection, s, ended);
    }

    private void precheck(final String host, final String collection, final String job, final long at, final String result) throws IOException {
        this.table.precheck(host, collection, new PrecheckResult(at, result, null, job, "example.com"), at);
    }

    @Test public void hostReportJoinsRowStalenessLatestAttemptAndLiveIndex() throws Exception {
        crawl("www.example.com", "research", JOB, NOON - 40 * DAY, "indexed", "complete", 12);
        precheck("www.example.com", "research", JOB, NOON - DAY, "dns");
        final JsonObject r = this.service.host("WWW.example.com", "research");
        assertEquals("found", r.getString("status"));
        final JsonObject current = r.getJSONObject("row").getJSONObject("current");
        assertEquals(40, current.getLong("age_days"));
        assertEquals(30, current.getInt("stale_after_days"));
        assertTrue(current.getBoolean("stale"));
        assertEquals(12, current.getJSONObject("counters").getLong(CrawlOutcome.PAGES_OK));
        assertEquals("precheck", r.getJSONObject("row").getString("latest_attempt"));
        assertEquals("dns", r.getJSONObject("row").getJSONObject("precheck").getString("result"));
        assertEquals("live", r.getString("index_source"));
        assertEquals(2, r.getJSONObject("index").getLong("not_reloaded"));
        assertTrue(this.queries.get(0).contains("host_s:\"www.example.com\""));
    }

    @Test public void unknownHostAndUnavailableIndex() throws Exception {
        this.indexDown = true;
        final JsonObject r = this.service.host("unknown.example", "research");
        assertEquals("absent", r.getString("status"));
        assertTrue(r.isNull("row"));
        assertTrue(r.isNull("index"));
        assertEquals("index_unavailable", r.getString("index_error"));
    }

    @Test public void collectionReportAggregatesTheTable() throws Exception {
        crawl("a.example", "research", JOB, NOON - DAY, "indexed", "complete", 10);
        crawl("b.example", "research", JOB, NOON - 31 * DAY, "partial", "partial", 4);   // stale for the job (30 days)
        precheck("c.example", "research", JOB, NOON - DAY, "robots");                      // precheck only
        crawl("d.example", "research", JOB, NOON - 5 * DAY, "indexed", "complete", 3);
        precheck("d.example", "research", JOB, NOON - DAY, "dns");                         // newer than its crawl
        crawl("e.example", "research", null, NOON - 20 * DAY, "not_indexed", "complete", 0); // no job: 14 days default
        crawl("f.example", "other", OTHER, NOON - DAY, "indexed", "complete", 1);
        precheck("g.example", "research", JOB, NOON - 10 * DAY, "dns");                    // older than its crawl
        crawl("g.example", "research", JOB, NOON - 2 * DAY, "indexed", "complete", 2);
        final JsonObject t = this.service.collection("research").getJSONObject("table");
        assertEquals(6, t.getLong("hosts"));
        assertEquals(5, t.getLong("crawled"));
        assertEquals(1, t.getLong("precheck_only"));
        assertEquals(2, t.getLong("latest_attempt_precheck"));
        assertEquals(1, t.getLong("coverage_partial"));
        assertEquals(2, t.getLong("stale"));
        assertEquals(3, t.getJSONObject("outcomes").getLong("indexed"));
        assertEquals(1, t.getJSONObject("outcomes").getLong("partial"));
        assertEquals(1, t.getJSONObject("outcomes").getLong("not_indexed"));
        assertEquals(1, t.getJSONObject("prechecks").getLong("robots"));
        assertEquals(1, t.getJSONObject("prechecks").getLong("dns"));
        assertEquals(19, t.getJSONObject("counters").getLong(CrawlOutcome.PAGES_OK));
        assertEquals(5, t.getJSONObject("counters").getLong(ExclusionTracker.EXCL_NOINDEX));
        final JsonObject job = this.service.job(JOB, null, null);
        assertTrue(job.getBoolean("known"));
        assertEquals("Handwerk", job.getString("name"));
        assertEquals("research", job.getJSONArray("collections").getString(0));
        assertEquals(5, job.getJSONObject("table").getLong("hosts"));
    }

    @Test public void tableScansAreCachedForTheConfiguredTime() throws Exception {
        crawl("a.example", "research", JOB, NOON - DAY, "indexed", "complete", 1);
        assertEquals(1, this.service.collection("research").getJSONObject("table").getLong("hosts"));
        crawl("b.example", "research", JOB, NOON - DAY, "indexed", "complete", 1);
        assertEquals(1, this.service.collection("research").getJSONObject("table").getLong("hosts"));
        this.clock.addAndGet(600_000L);
        assertEquals(2, this.service.collection("research").getJSONObject("table").getLong("hosts"));
    }

    @Test public void dailyRollupsCatchUpOnlyActiveDaysAndSnapshotYesterday() throws Exception {
        crawl("a.example", "research", JOB, NOON - DAY, "indexed", "complete", 10);        // yesterday
        crawl("b.example", "research", JOB, NOON - 3 * DAY, "partial", "partial", 2);      // three days ago
        precheck("c.example", "research", JOB, NOON - 2 * DAY, "robots");                  // two days ago
        crawl("d.example", "research", JOB, NOON - 9 * DAY, "indexed", "complete", 1);     // beyond the catch-up window
        crawl("e.example", "research", JOB, NOON, "indexed", "complete", 1);               // today: not yet
        this.service.daily();
        final List<JsonObject> lines = this.rollups.read(JOB, 2026);
        assertEquals(List.of("2026-10-01", "2026-10-02", "2026-10-03"),
                List.of(lines.get(0).getString("day"), lines.get(1).getString("day"), lines.get(2).getString("day")));
        assertEquals(1, lines.get(0).getLong("crawls"));
        assertEquals(1, lines.get(0).getLong("coverage_partial"));
        assertEquals(0, lines.get(1).getLong("crawls"));
        assertEquals(1, lines.get(1).getJSONObject("prechecks").getLong("robots"));
        assertEquals(10, lines.get(2).getJSONObject("pages").getLong(CrawlOutcome.PAGES_OK));
        assertEquals(1, lines.get(2).getJSONObject("exclusions").getLong(ExclusionTracker.EXCL_NOINDEX));
        assertEquals("fp-1", lines.get(2).getString("job_fingerprint"));
        assertEquals("1.942-scoutro.12", lines.get(2).getString("version"));
        assertTrue(!lines.get(0).has("index") && !lines.get(1).has("index"));
        assertEquals(120, lines.get(2).getJSONObject("index").getJSONObject("research").getLong("documents"));
        assertEquals(9, lines.get(2).getJSONObject("index").getJSONObject("research").getLong("hosts"));
        assertTrue(!lines.get(2).has("markers"));
        final int calls = this.indexCalls.get();
        this.service.daily(); // same day: nothing
        assertEquals(3, this.rollups.read(JOB, 2026).size());
        assertEquals(calls, this.indexCalls.get());
    }

    @Test public void yesterdayIsRolledUpOnlyAfterTheGraceTime() throws Exception {
        this.clock.set(NOON - 12 * 3_600_000L + 5 * 60_000L); // 00:05 local time
        crawl("a.example", "research", JOB, this.clock.get() - 10 * 60_000L, "indexed", "complete", 1); // 23:55
        this.service.daily();
        assertTrue(this.rollups.read(JOB, 2026).isEmpty());
        crawl("b.example", "research", JOB, this.clock.get() - 6 * 60_000L, "indexed", "complete", 1);  // 23:59, captured late
        this.clock.addAndGet(15 * 60_000L);
        this.service.daily();
        final List<JsonObject> lines = this.rollups.read(JOB, 2026);
        assertEquals(1, lines.size());
        assertEquals("2026-10-03", lines.get(0).getString("day"));
        assertEquals(2, lines.get(0).getLong("crawls"));
    }

    @Test public void unreadableJobsFallBackToDefaultsAndFailedScansWait() throws Exception {
        crawl("a.example", "research", JOB, NOON - 20 * DAY, "indexed", "complete", 1);
        final ReportService noJobs = new ReportService(this.table, new IndexFacets(p -> answer(1, 1, 1), FIELDS::contains), this.rollups,
                () -> { throw new IOException("job store unreadable"); }, this.clock::get, ZoneOffset.UTC, 0, 14, null, 0);
        assertEquals(1, noJobs.collection("research").getJSONObject("table").getLong("stale")); // 14 days default
        assertFalse(noJobs.job(JOB, null, null).getBoolean("known"));
        crawl("b.example", "research", JOB, NOON - DAY, "indexed", "complete", 1);
        noJobs.daily();
        final JsonObject line = this.rollups.read(JOB, 2026).get(0);
        assertEquals("2026-10-03", line.getString("day"));
        assertTrue(!line.has("job_fingerprint") && !line.has("version"));
        final java.io.File work = this.tmp.newFolder("WORK2");
        final java.io.File heap = new java.io.File(work, DomainTable.TABLE + ".bheap"); // a directory where the heap file belongs
        assertTrue(heap.mkdir() && new java.io.File(heap, "keep").createNewFile());
        final Tables broken = new Tables(work, DomainTable.KEY_LENGTH);
        final ReportService failing = new ReportService(new DomainTable(broken), new IndexFacets(p -> answer(1, 1, 1), FIELDS::contains),
                this.rollups, () -> new ArrayList<>(this.jobs), this.clock::get, ZoneOffset.UTC, 0, 14, null, 0);
        try { failing.daily(); fail(); } catch (final IOException expected) { }
        failing.daily(); // within the retry delay: no second scan, so no second failure
        this.clock.addAndGet(ReportService.DAILY_RETRY_MILLIS);
        try { failing.daily(); fail(); } catch (final IOException expected) { }
        broken.close();
    }

    @Test public void markersRecordJobAndVersionChanges() throws Exception {
        crawl("a.example", "research", JOB, NOON - DAY, "indexed", "complete", 1);
        this.service.daily();
        this.clock.addAndGet(DAY);
        this.jobs.set(0, new ReportService.Job(JOB, "Handwerk", "fp-2", 30));
        this.service = service("1.942-scoutro.13");
        crawl("b.example", "research", JOB, NOON + 2 * 3_600_000L, "indexed", "complete", 1);
        this.service.daily();
        final List<JsonObject> lines = this.rollups.read(JOB, 2026);
        assertEquals(2, lines.size());
        final String markers = lines.get(1).getJSONArray("markers").toString();
        assertTrue(markers, markers.contains("job_changed"));
        assertTrue(markers, markers.contains("version_changed"));
        assertTrue(markers, markers.contains("1.942-scoutro.12"));
    }

    @Test public void collectionFallsBackToTheLatestRollupSnapshot() throws Exception {
        crawl("a.example", "research", JOB, NOON - DAY, "indexed", "complete", 1);
        this.service.daily();
        this.indexDown = true;
        final JsonObject r = this.service.collection("research");
        assertEquals("rollup", r.getString("index_source"));
        assertEquals("2026-10-03", r.getString("index_as_of"));
        assertEquals(120, r.getJSONObject("index").getLong("documents"));
        assertEquals("index_unavailable", r.getString("index_error"));
        final JsonObject none = this.service.collection("other");
        assertTrue(none.isNull("index"));
        assertTrue(none.isNull("index_source"));
    }

    @Test public void jobRangesAndUnknownJobs() throws Exception {
        final JsonObject unknown = this.service.job(OTHER, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        assertFalse(unknown.getBoolean("known"));
        assertEquals(14, unknown.getInt("stale_after_days"));
        assertTrue(unknown.getJSONArray("rollups").isEmpty());
        for (final LocalDate[] range : new LocalDate[][] {{LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)},
                {LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1)}}) {
            try { this.service.job(JOB, range[0], range[1]); fail(); } catch (final IllegalArgumentException expected) { }
        }
        try { this.service.job("not-a-job", null, null); fail(); } catch (final IllegalArgumentException expected) { }
    }

    @Test public void fingerprintIsIndependentOfKeyOrder() {
        final JsonObject a = new JsonObject("{\"b\":1,\"a\":{\"y\":[1,\"x\"],\"x\":null}}");
        final JsonObject b = new JsonObject("{\"a\":{\"x\":null,\"y\":[1,\"x\"]},\"b\":1}");
        assertEquals(ReportService.fingerprint(a), ReportService.fingerprint(b));
        assertEquals(16, ReportService.fingerprint(a).length());
        assertNotEquals(ReportService.fingerprint(a), ReportService.fingerprint(new JsonObject("{\"b\":2}")));
    }

    @Test public void scanSkipsCollidingRowsAndCountsThem() throws Exception {
        crawl("a.example", "research", JOB, NOON - DAY, "indexed", "complete", 1);
        final Map<String, byte[]> foreign = new HashMap<>();
        for (final String[] c : new String[][] {{"v", "1"}, {"host", "other.example"}, {"collection", "research"},
                {"updated_at", "1"}, {"crawl_id", "x"}, {"started_at", "1"}})
            foreign.put(c[0], c[1].getBytes(StandardCharsets.UTF_8));
        this.tables.insert(DomainTable.TABLE, DomainTable.key("b.example", "research"), foreign);
        final List<String> seen = new ArrayList<>();
        final DomainTable.ScanResult r = this.table.scan(e -> seen.add(e.host));
        assertEquals(2, r.rows);
        assertEquals(1, r.invalid);
        assertEquals(List.of("a.example"), seen);
        assertEquals(1, this.service.collection("research").getJSONObject("table").getLong("hosts"));
    }

    @Test public void rollupRootStaysBelowData() {
        final Path root = RollupStore.root(Path.of("/srv/scoutro"));
        assertEquals(Path.of("/srv/scoutro/DATA/SCOUTRO/reports/rollups"), root);
    }
}
