/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Temporary tables only. */
package net.yacy.scoutro.report;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.kelondro.blob.Tables;
import net.yacy.scoutro.report.DomainTable.Status;

public class DomainTableTest {
    private static final String MARKER_A = "0123456789abcdef0123456789abcdef";
    private static final String MARKER_B = "fedcba9876543210fedcba9876543210";
    private static final String JOB = "3276af9c-b8b5-4b8c-bc2b-17b8d1905eb7";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File dir;
    private Tables tables;
    private DomainTable table;

    @Before public void open() throws Exception {
        this.dir = this.tmp.newFolder("WORK");
        this.tables = new Tables(this.dir, DomainTable.KEY_LENGTH);
        this.table = new DomainTable(this.tables);
    }

    @After public void close() {
        this.tables.close();
    }

    private void reopen() {
        this.tables.close();
        this.tables = new Tables(this.dir, DomainTable.KEY_LENGTH);
        this.table = new DomainTable(this.tables);
    }

    private static CrawlSnapshot crawl(final String id, final String marker, final long startedAt, final long pages) {
        return CrawlSnapshot.builder(id, startedAt).startMarker(marker).endedAt(startedAt + 1000).job(JOB)
                .discoveryDomain("example.com").counter("pages_ok", pages).label("coverage", "complete").build();
    }

    private DomainTable.Entry entry(final String host, final String collection) throws Exception {
        final DomainTable.Lookup lookup = this.table.read(host, collection);
        assertEquals(DomainTable.ReadStatus.FOUND, lookup.status);
        return lookup.entry;
    }

    private static String ascii(final byte[] value) {
        return new String(value, StandardCharsets.US_ASCII);
    }

    @Test public void keyIsDeterministicTwelveByteBase64() {
        final byte[] key = DomainTable.key("www.example.com", "research");
        assertEquals(DomainTable.KEY_LENGTH, key.length);
        assertTrue(ascii(key).matches("[A-Za-z0-9_-]{12}"));
        assertArrayEquals(key, DomainTable.key("www.example.com", "research"));
        assertArrayEquals(key, DomainTable.key("WWW.Example.COM.", "research"));
        assertArrayEquals(DomainTable.key("xn--bcher-kva.example", "web"), DomainTable.key("BÜCHER.example", "web"));
    }

    @Test public void hostsAndCollectionsAreNeverMerged() {
        final String www = ascii(DomainTable.key("www.example.com", "research"));
        assertFalse(www.equals(ascii(DomainTable.key("example.com", "research"))));
        assertFalse(www.equals(ascii(DomainTable.key("shop.example.com", "research"))));
        assertFalse(www.equals(ascii(DomainTable.key("www.example.com", "Research"))));
        assertFalse(ascii(DomainTable.key("ab.example", "c")).equals(ascii(DomainTable.key("a.example", "bc"))));
    }

    @Test public void rejectsInvalidHostsAndCollections() {
        for (final String host : List.of("", " ", "localhost", "127.0.0.1", "example.com:80", "example.com/path",
                "https://example.com/", "user@example.com", "a..example", "-a.example", "a.exa\nmple")) {
            try { DomainTable.key(host, "research"); fail(host); } catch (final IllegalArgumentException expected) { }
        }
        for (final String collection : List.of("", "bad name", "x:y", "a/b", "c".repeat(65))) {
            try { DomainTable.key("example.com", collection); fail(collection); } catch (final IllegalArgumentException expected) { }
        }
        try { DomainTable.key("example.com", null); fail(); } catch (final IllegalArgumentException expected) { }
    }

    @Test public void wwwAndApexAreSeparateRows() throws Exception {
        assertEquals(Status.CREATED, this.table.complete("www.example.com", "research", crawl("w1", MARKER_A, 100, 15), 1));
        assertEquals(DomainTable.ReadStatus.ABSENT, this.table.read("example.com", "research").status);
        assertEquals(Status.CREATED, this.table.complete("example.com", "research", crawl("a1", MARKER_B, 100, 3), 1));
        assertEquals(15L, (long) entry("www.example.com", "research").current.counters.get("pages_ok"));
        assertEquals(3L, (long) entry("example.com", "research").current.counters.get("pages_ok"));
        assertEquals(DomainTable.ReadStatus.ABSENT, this.table.read("www.example.com", "Research").status);
    }

    @Test public void createdRowRoundTripsAndPersists() throws Exception {
        final CrawlSnapshot first = crawl("c1", MARKER_A, 1_700_000_000_000L, 15);
        assertEquals(Status.CREATED, this.table.complete("WWW.Example.com.", "research", first, 42));
        reopen();
        final DomainTable.Entry e = entry("www.example.com", "research");
        assertEquals("www.example.com", e.host);
        assertEquals("research", e.collection);
        assertEquals(42, e.updatedAt);
        assertEquals(first, e.current);
        assertNull(e.previous);
        assertEquals("example.com", e.current.discoveryDomain);
        assertEquals(JOB, e.current.job);
    }

    @Test public void repeatedCompletionNeverShiftsTwice() throws Exception {
        final CrawlSnapshot a = crawl("c1", MARKER_A, 100, 10), b = crawl("c2", MARKER_B, 200, 12);
        assertEquals(Status.CREATED, this.table.complete("example.com", "research", a, 1));
        assertEquals(Status.UNCHANGED, this.table.complete("example.com", "research", a, 2));
        assertEquals(Status.SHIFTED, this.table.complete("example.com", "research", b, 3));
        assertEquals(Status.UNCHANGED, this.table.complete("example.com", "research", b, 4));
        assertEquals(Status.IGNORED_PREVIOUS, this.table.complete("example.com", "research", a, 5));
        reopen();
        assertEquals(Status.UNCHANGED, this.table.complete("example.com", "research", b, 6));
        final DomainTable.Entry e = entry("example.com", "research");
        assertEquals(b, e.current);
        assertEquals(a, e.previous);
        assertEquals(3, e.updatedAt);
    }

    @Test public void sameCrawlWithNewValuesUpdatesInPlace() throws Exception {
        final CrawlSnapshot a = crawl("c1", MARKER_A, 100, 10), b = crawl("c2", MARKER_B, 200, 12);
        this.table.complete("example.com", "research", a, 1);
        this.table.complete("example.com", "research", b, 2);
        final CrawlSnapshot settled = CrawlSnapshot.builder("c2", 200).startMarker(MARKER_B).endedAt(5000L).job(JOB)
                .discoveryDomain("example.com").counter("pages_ok", 14).counter("pages_redirect", 1).build();
        assertEquals(Status.UPDATED, this.table.complete("example.com", "research", settled, 3));
        final DomainTable.Entry e = entry("example.com", "research");
        assertEquals(settled, e.current);
        assertEquals(a, e.previous);
        assertFalse(e.current.labels.containsKey("coverage"));
    }

    @Test public void lateReportOfAnOlderCrawlIsIgnored() throws Exception {
        final CrawlSnapshot newer = crawl("c2", MARKER_B, 200, 12);
        this.table.complete("example.com", "research", newer, 1);
        assertEquals(Status.IGNORED_OLDER, this.table.complete("example.com", "research", crawl("c1", MARKER_A, 100, 10), 2));
        final DomainTable.Entry e = entry("example.com", "research");
        assertEquals(newer, e.current);
        assertNull(e.previous);
    }

    @Test public void conflictingIdentitiesWriteNothing() throws Exception {
        final CrawlSnapshot a = crawl("c1", MARKER_A, 100, 10);
        this.table.complete("example.com", "research", a, 1);
        assertEquals(Status.CONFLICT, this.table.complete("example.com", "research", crawl("other", MARKER_A, 300, 1), 2));
        assertEquals(Status.CONFLICT, this.table.complete("example.com", "research", crawl("c1", MARKER_B, 100, 1), 3));
        assertEquals(Status.CONFLICT, this.table.complete("example.com", "research", crawl("c9", MARKER_B, 100, 1), 4));
        final DomainTable.Entry e = entry("example.com", "research");
        assertEquals(a, e.current);
        assertEquals(1, e.updatedAt);
    }

    @Test public void reusedCrawlIdWithNewStartIsANewCrawl() throws Exception {
        final CrawlSnapshot a = crawl("c1", MARKER_A, 100, 10), again = crawl("c1", MARKER_B, 200, 11);
        this.table.complete("example.com", "research", a, 1);
        assertEquals(Status.SHIFTED, this.table.complete("example.com", "research", again, 2));
        assertEquals(a, entry("example.com", "research").previous);
    }

    @Test public void crawlsWithoutMarkerUseIdAndStartTime() throws Exception {
        final CrawlSnapshot plain = CrawlSnapshot.builder("yacy1", 100).counter("pages_ok", 4).build();
        assertEquals(Status.CREATED, this.table.complete("example.com", "web", plain, 1));
        assertEquals(Status.UNCHANGED, this.table.complete("example.com", "web",
                CrawlSnapshot.builder("yacy1", 100).counter("pages_ok", 4).build(), 2));
        final CrawlSnapshot withMarker = CrawlSnapshot.builder("yacy1", 100).startMarker(MARKER_A).counter("pages_ok", 4).build();
        assertEquals(Status.UPDATED, this.table.complete("example.com", "web", withMarker, 3));
        assertEquals(Status.SHIFTED, this.table.complete("example.com", "web",
                CrawlSnapshot.builder("yacy1", 500).counter("pages_ok", 6).build(), 4));
        assertEquals(withMarker, entry("example.com", "web").previous);
    }

    @Test public void shiftReplacesThePreviousSectionCompletely() throws Exception {
        final CrawlSnapshot a = CrawlSnapshot.builder("c1", 100).startMarker(MARKER_A).counter("pages_ok", 1)
                .counter("excl_noindex", 9).label("coverage", "partial").discoveryDomain("example.com").build();
        final CrawlSnapshot b = CrawlSnapshot.builder("c2", 200).counter("pages_ok", 2).build();
        final CrawlSnapshot c = CrawlSnapshot.builder("c3", 300).counter("pages_ok", 3).build();
        this.table.complete("example.com", "research", a, 1);
        this.table.complete("example.com", "research", b, 2);
        this.table.complete("example.com", "research", c, 3);
        reopen();
        final DomainTable.Entry e = entry("example.com", "research");
        assertEquals(c, e.current);
        assertEquals(b, e.previous);
        assertNull(e.previous.startMarker);
        assertNull(e.previous.discoveryDomain);
        assertFalse(e.previous.counters.containsKey("excl_noindex"));
        assertTrue(e.previous.labels.isEmpty());
    }

    @Test public void keyCollisionIsNeitherReturnedNorOverwritten() throws Exception {
        final byte[] key = DomainTable.key("example.com", "research");
        final Map<String, byte[]> foreign = new HashMap<>();
        foreign.put("v", "1".getBytes(StandardCharsets.UTF_8));
        foreign.put("host", "other.example".getBytes(StandardCharsets.UTF_8));
        foreign.put("collection", "research".getBytes(StandardCharsets.UTF_8));
        foreign.put("updated_at", "7".getBytes(StandardCharsets.UTF_8));
        foreign.put("crawl_id", "x1".getBytes(StandardCharsets.UTF_8));
        foreign.put("started_at", "100".getBytes(StandardCharsets.UTF_8));
        this.tables.insert(DomainTable.TABLE, key, foreign);
        final DomainTable.Lookup lookup = this.table.read("example.com", "research");
        assertEquals(DomainTable.ReadStatus.KEY_COLLISION, lookup.status);
        assertNull(lookup.entry);
        assertEquals(Status.KEY_COLLISION, this.table.complete("example.com", "research", crawl("c1", MARKER_A, 900, 1), 8));
        final Tables.Row row = this.tables.select(DomainTable.TABLE, key);
        assertEquals("other.example", row.get("host", ""));
        assertEquals("7", row.get("updated_at", ""));
        assertEquals(6, row.size());
    }

    @Test public void invalidRowsFailClosed() throws Exception {
        final List<Map<String, String>> rows = List.of(
                Map.of("v", "2", "updated_at", "1", "crawl_id", "c1", "started_at", "100"),
                Map.of("v", "1", "updated_at", "1", "crawl_id", "c1"),
                Map.of("v", "1", "updated_at", "1", "crawl_id", "c1", "started_at", "100", "n_pages_ok", "-1"),
                Map.of("v", "1", "updated_at", "1", "crawl_id", "c1", "started_at", "100", "unknown", "x"),
                Map.of("v", "1", "updated_at", "1", "crawl_id", "c1", "started_at", "100", "prev_crawl_id", "c0"),
                Map.of("v", "1", "updated_at", "x", "crawl_id", "c1", "started_at", "100"));
        int i = 0;
        for (final Map<String, String> columns : rows) {
            final String host = "h" + (i++) + ".example";
            final Map<String, byte[]> row = new HashMap<>();
            row.put("host", host.getBytes(StandardCharsets.UTF_8));
            row.put("collection", "research".getBytes(StandardCharsets.UTF_8));
            for (final Map.Entry<String, String> c : columns.entrySet()) row.put(c.getKey(), c.getValue().getBytes(StandardCharsets.UTF_8));
            final byte[] key = DomainTable.key(host, "research");
            this.tables.insert(DomainTable.TABLE, key, row);
            assertEquals(host, DomainTable.ReadStatus.INVALID_ROW, this.table.read(host, "research").status);
            assertEquals(host, Status.INVALID_ROW, this.table.complete(host, "research", crawl("c2", MARKER_B, 900, 1), 9));
            assertEquals(host, row.size(), this.tables.select(DomainTable.TABLE, key).size());
        }
    }

    private long tableBytes() {
        long bytes = 0;
        for (final File f : this.dir.listFiles()) if (f.getName().startsWith(DomainTable.TABLE)) bytes += f.length();
        return bytes;
    }

    @Test public void recrawlsReuseSpaceSoTheTableGrowsWithHostsOnly() throws Exception {
        final int hosts = 300;
        long afterSecondCrawl = 0;
        for (int pass = 0; pass < 6; pass++) {
            for (int i = 0; i < hosts; i++) {
                final long start = 1_000_000L * (pass + 1) + i;
                final CrawlSnapshot s = CrawlSnapshot.builder("c" + pass + "x" + i, start).startMarker(String.format("%032x", start))
                        .endedAt(start + 500).job(JOB).discoveryDomain("host" + i + ".example").counter("pages_ok", (i * 7 + pass) % 16)
                        .counter("excl_filter", pass % 3).label("coverage", pass % 2 == 0 ? "complete" : "partial").build();
                assertEquals(pass == 0 ? Status.CREATED : Status.SHIFTED, this.table.complete("www.host" + i + ".example", "research", s, start));
            }
            if (pass == 1) {
                reopen();
                afterSecondCrawl = tableBytes();
            }
        }
        reopen();
        final long afterSixthCrawl = tableBytes();
        assertTrue(afterSecondCrawl + " -> " + afterSixthCrawl, afterSixthCrawl <= afterSecondCrawl * 105 / 100);
        assertEquals(5, entry("www.host7.example", "research").current.crawlId.charAt(1) - '0');
    }

    @Test public void snapshotsAreBounded() {
        final List<Runnable> invalid = List.of(
                () -> CrawlSnapshot.builder("bad id", 1).build(),
                () -> CrawlSnapshot.builder(null, 1).build(),
                () -> CrawlSnapshot.builder("c1", 0).build(),
                () -> CrawlSnapshot.builder("c1", 10).endedAt(9L).build(),
                () -> CrawlSnapshot.builder("c1", 1).startMarker("ABC").build(),
                () -> CrawlSnapshot.builder("c1", 1).job("not-a-uuid").build(),
                () -> CrawlSnapshot.builder("c1", 1).job(JOB.toUpperCase()).build(),
                () -> CrawlSnapshot.builder("c1", 1).discoveryDomain("localhost").build(),
                () -> CrawlSnapshot.builder("c1", 1).counter("Pages", 1).build(),
                () -> CrawlSnapshot.builder("c1", 1).counter("pages", -1).build(),
                () -> CrawlSnapshot.builder("c1", 1).label("coverage", "two words").build(),
                () -> {
                    final CrawlSnapshot.Builder b = CrawlSnapshot.builder("c1", 1);
                    for (int i = 0; i <= CrawlSnapshot.MAX_COUNTERS; i++) b.counter("n" + i, i);
                    b.build();
                },
                () -> {
                    final CrawlSnapshot.Builder b = CrawlSnapshot.builder("c1", 1);
                    for (int i = 0; i <= CrawlSnapshot.MAX_LABELS; i++) b.label("l" + i, "x");
                    b.build();
                });
        for (int i = 0; i < invalid.size(); i++) {
            try { invalid.get(i).run(); fail("case " + i); } catch (final IllegalArgumentException expected) { }
        }
        assertNotNull(CrawlSnapshot.builder("c1", 1).startMarker(MARKER_A).build());
    }
}
