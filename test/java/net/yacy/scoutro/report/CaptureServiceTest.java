/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Temporary tables, fake YaCy state only. */
package net.yacy.scoutro.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.solr.common.util.NamedList;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.kelondro.blob.Tables;

public class CaptureServiceTest {
    private static final String JOB = "3276af9c-b8b5-4b8c-bc2b-17b8d1905eb7";
    private static final String MARKER = "0123456789abcdef0123456789abcdef";
    private static final long SETTLE = 60_000L;

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Tables tables;
    private DomainTable table;
    private final AtomicLong clock = new AtomicLong(1_790_000_000_000L);
    private final List<CrawlOutcome.Crawl> active = new ArrayList<>(), terminated = new ArrayList<>();
    private final ExclusionTrackerTest.FakeCache cache = new ExclusionTrackerTest.FakeCache();
    private final AtomicInteger queries = new AtomicInteger();
    private IOException indexFailure;
    private CaptureService service;

    @Before public void open() throws Exception {
        this.tables = new Tables(this.tmp.newFolder("WORK"), DomainTable.KEY_LENGTH);
        this.table = new DomainTable(this.tables);
        final CaptureService.Crawls crawls = new CaptureService.Crawls() {
            @Override public List<CrawlOutcome.Crawl> active() { return new ArrayList<>(CaptureServiceTest.this.active); }
            @Override public List<CrawlOutcome.Crawl> terminated() { return new ArrayList<>(CaptureServiceTest.this.terminated); }
            @Override public CrawlOutcome.Crawl byMarker(final String marker) {
                for (final CrawlOutcome.Crawl c : CaptureServiceTest.this.terminated) if (marker.equals(c.startMarker)) return c;
                for (final CrawlOutcome.Crawl c : CaptureServiceTest.this.active) if (marker.equals(c.startMarker)) return c;
                return null;
            }
        };
        final CrawlOutcome outcome = new CrawlOutcome(p -> {
            this.queries.incrementAndGet();
            if (this.indexFailure != null) throw this.indexFailure;
            final NamedList<Object> answer = CrawlOutcomeTest.answer(15, 13, 1, 1, 0, 1, 1, 0, 2, 2);
            return answer;
        }, f -> true);
        this.service = new CaptureService(crawls, new ExclusionTracker(this.cache), outcome, this.table, this.clock::get, SETTLE);
    }

    @After public void close() {
        this.tables.close();
    }

    private CrawlOutcome.Crawl crawl(final String id, final String marker, final String host, final long startedAt) {
        return new CrawlOutcome.Crawl(id, marker, host, "research", startedAt, null, 2, 15);
    }

    private void tick(final long advance) throws IOException {
        this.clock.addAndGet(advance);
        this.service.tick();
    }

    private DomainTable.Entry row(final String host) throws IOException {
        final DomainTable.Lookup lookup = this.table.read(host, "research");
        return lookup.status == DomainTable.ReadStatus.FOUND ? lookup.entry : null;
    }

    @Test public void finishedCrawlIsCapturedOnceAfterTheSettleDelay() throws Exception {
        final long start = this.clock.get() + 1;
        final CrawlOutcome.Crawl c = crawl("c1", MARKER, "www.example.com", start);
        tick(0); // baseline
        this.active.add(c);
        this.service.discoveryAccepted(MARKER, JOB, "example.com");
        tick(20_000);
        this.cache.push("www.example.com", "research", "FINAL_PROCESS_CONTEXT denied by document-attached noindexing rule", this.clock.get());
        tick(20_000);
        assertNull(row("www.example.com"));
        this.active.clear();
        this.terminated.add(c);
        tick(20_000);
        final long ended = this.clock.get();
        assertEquals(1, this.service.pendingCount());
        tick(SETTLE - 1);
        assertNull(row("www.example.com"));
        assertEquals(0, this.queries.get());
        tick(1);
        final DomainTable.Entry e = row("www.example.com");
        assertEquals("c1", e.current.crawlId);
        assertEquals(MARKER, e.current.startMarker);
        assertEquals(ended, (long) e.current.endedAt);
        assertEquals(JOB, e.current.job);
        assertEquals("example.com", e.current.discoveryDomain);
        assertEquals(13L, (long) e.current.counters.get(CrawlOutcome.PAGES_OK));
        assertEquals(1L, (long) e.current.counters.get(ExclusionTracker.EXCL_NOINDEX));
        assertEquals("partial", e.current.labels.get(CrawlOutcome.OUTCOME));
        assertEquals("complete", e.current.labels.get(CrawlOutcome.COVERAGE));
        assertEquals(1, this.queries.get());
        this.service.discoveryTerminated(MARKER, JOB, "example.com"); // reconcile reports the same crawl again
        tick(SETTLE * 3);
        assertEquals(1, this.queries.get());
        assertEquals(0, this.service.pendingCount());
    }

    @Test public void crawlsThatEndedWhileScoutroWasDownAreRecoveredOnce() throws Exception {
        final CrawlOutcome.Crawl old = crawl("c0", null, "old.example", this.clock.get() - 86_400_000L);
        this.terminated.add(old);
        tick(0);
        assertEquals(1, this.service.pendingCount());
        tick(SETTLE);
        final DomainTable.Entry e = row("old.example");
        assertEquals("c0", e.current.crawlId);
        assertNull(e.current.endedAt);
        assertNull(e.current.job);
        assertEquals("partial", e.current.labels.get(CrawlOutcome.COVERAGE));
        tick(SETTLE);
        assertEquals(1, this.queries.get());
    }

    @Test public void recordedCrawlsAreNotQueriedAgain() throws Exception {
        final CrawlOutcome.Crawl c = crawl("c1", MARKER, "example.com", this.clock.get() - 1000);
        this.table.complete("example.com", "research", c.identity(), this.clock.get());
        this.terminated.add(c);
        tick(0);
        tick(SETTLE);
        assertEquals(0, this.queries.get());
        assertEquals(0, this.service.pendingCount());
    }

    @Test public void discoveryReportedTerminationIsCapturedWithContext() throws Exception {
        tick(0);
        final CrawlOutcome.Crawl c = crawl("c7", MARKER, "shop.example.com", this.clock.get() - 5000);
        this.terminated.add(c); // already terminated before the monitor saw it running
        this.service.discoveryTerminated(MARKER, JOB, "example.com");
        tick(1000);
        tick(SETTLE);
        final DomainTable.Entry e = row("shop.example.com");
        assertEquals(JOB, e.current.job);
        assertEquals("example.com", e.current.discoveryDomain);
    }

    @Test public void indexFailuresAreRetriedThenRecordedWithoutPageCounts() throws Exception {
        tick(0);
        this.indexFailure = new IOException("index down");
        this.active.add(crawl("c1", MARKER, "example.com", this.clock.get() + 1));
        tick(1000);
        this.active.clear();
        tick(1000);
        for (int i = 0; i < CaptureService.MAX_ATTEMPTS - 1; i++) {
            tick(SETTLE * (i + 1));
            assertNull(row("example.com"));
        }
        tick(SETTLE * CaptureService.MAX_ATTEMPTS);
        final DomainTable.Entry e = row("example.com");
        assertEquals("unknown", e.current.labels.get(CrawlOutcome.OUTCOME));
        assertNull(e.current.counters.get(CrawlOutcome.PAGES_OK));
        assertEquals(CaptureService.MAX_ATTEMPTS, this.queries.get());
    }

    @Test public void aNewerRecrawlShiftsThePreviousCrawl() throws Exception {
        tick(0);
        final CrawlOutcome.Crawl first = crawl("c1", MARKER, "example.com", this.clock.get() + 1);
        this.active.add(first);
        tick(1000);
        this.active.clear();
        tick(1000);
        tick(SETTLE);
        final CrawlOutcome.Crawl second = crawl("c2", "fedcba9876543210fedcba9876543210", "example.com", this.clock.get() + 1);
        this.active.add(second);
        tick(1000);
        this.active.clear();
        tick(1000);
        tick(SETTLE);
        final DomainTable.Entry e = row("example.com");
        assertEquals("c2", e.current.crawlId);
        assertEquals("c1", e.previous.crawlId);
    }

    @Test public void prechecksAreStoredBesideCrawls() throws Exception {
        final PrecheckResult p = new PrecheckResult(this.clock.get(), "robots", "robots-disallow-all", JOB, "example.com");
        assertEquals(DomainTable.Status.CREATED, this.service.precheck("www.example.com", "research", p));
        final DomainTable.Entry e = row("www.example.com");
        assertNull(e.current);
        assertEquals("robots", e.precheck.result);
        assertEquals("robots-disallow-all", e.precheck.detail);
    }
}
