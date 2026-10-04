/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. In-memory ErrorCache stand-in only. */
package net.yacy.scoutro.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class ExclusionTrackerTest {
    /** Behaves like YaCy's ErrorCache: insertion order, oldest entries evicted beyond 1000. */
    static final class FakeCache implements ExclusionTracker.Source {
        final List<ExclusionTracker.Event> entries = new ArrayList<>();
        final List<Integer> windows = new ArrayList<>();
        int next;

        void push(final String host, final String collection, final String reason, final long at) {
            this.entries.add(new ExclusionTracker.Event("h" + (this.next++), host, Set.of(collection), reason, at));
            while (this.entries.size() > 1000) this.entries.remove(0);
        }

        @Override public List<ExclusionTracker.Event> newest(final int max) {
            this.windows.add(max);
            return new ArrayList<>(this.entries.subList(Math.max(0, this.entries.size() - max), this.entries.size()));
        }
    }

    private static final String NOINDEX = "FINAL_PROCESS_CONTEXT denied by document-attached noindexing rule";

    @Test public void entriesBeforeTheBaselineAreNeverAttributed() {
        final FakeCache cache = new FakeCache();
        final ExclusionTracker t = new ExclusionTracker(cache);
        cache.push("example.com", "research", NOINDEX, 50);
        t.track("k", "example.com", "research", 10);
        t.poll(100);
        t.poll(120);
        assertTrue(t.tally("k").counts.isEmpty());
        assertFalse(t.tally("k").complete); // started before reading began
    }

    @Test public void categoriesHostCollectionAndStartAreRespected() {
        final FakeCache cache = new FakeCache();
        final ExclusionTracker t = new ExclusionTracker(cache);
        t.poll(100);
        t.track("k", "www.example.com", "research", 200);
        cache.push("www.example.com", "research", NOINDEX, 210);
        cache.push("www.example.com", "research", "FINAL_PROCESS_CONTEXT denied by rule in document, process case=LOCAL_CRAWLING", 211);
        cache.push("www.example.com", "research", "FINAL_PROCESS_CONTEXT Not Condensed Resource 'u': denied, canonical != source; canonical = a", 212);
        cache.push("www.example.com", "research", "FINAL_PROCESS_CONTEXT Not Condensed Resource 'u': indexing prevented by regular expression on url", 213);
        cache.push("www.example.com", "research", "FINAL_LOAD_CONTEXT url in blacklist", 214);
        cache.push("www.example.com", "research", "FINAL_PROCESS_CONTEXT parser error: no parser", 215);
        cache.push("www.example.com", "research", "FINAL_ROBOTS_RULE denied by robots.txt", 216);       // also in the index
        cache.push("www.example.com", "research", "TEMPORARY_NETWORK_FAILURE timeout (http return code = 0)", 217);
        cache.push("example.com", "research", NOINDEX, 218);                                             // other host
        cache.push("www.example.com", "other", NOINDEX, 219);                                            // other collection
        cache.push("www.example.com", "research", NOINDEX, 150);                                         // before the start
        t.poll(300);
        final ExclusionTracker.Tally tally = t.untrack("k");
        assertEquals(2L, (long) tally.counts.get(ExclusionTracker.EXCL_NOINDEX));
        assertEquals(1L, (long) tally.counts.get(ExclusionTracker.EXCL_CANONICAL));
        assertEquals(1L, (long) tally.counts.get(ExclusionTracker.EXCL_FILTER));
        assertEquals(1L, (long) tally.counts.get(ExclusionTracker.EXCL_BLACKLIST));
        assertEquals(1L, (long) tally.counts.get(ExclusionTracker.EXCL_OTHER));
        assertEquals(5, tally.counts.size());
        assertTrue(tally.complete);
        assertNull(t.tally("k"));
    }

    @Test public void readsGrowFromOneEntryAndStopAtTheLastSeen() {
        final FakeCache cache = new FakeCache();
        final ExclusionTracker t = new ExclusionTracker(cache);
        for (int i = 0; i < 500; i++) cache.push("old.example", "research", NOINDEX, 1);
        t.poll(100);
        t.track("k", "example.com", "research", 200);
        cache.windows.clear();
        t.poll(210); // nothing new
        assertEquals(List.of(1), cache.windows);
        for (int i = 0; i < 5; i++) cache.push("example.com", "research", NOINDEX, 220);
        cache.windows.clear();
        t.poll(230);
        assertEquals(List.of(1, 8), cache.windows);
        assertEquals(5L, (long) t.tally("k").counts.get(ExclusionTracker.EXCL_NOINDEX));
        t.poll(240);
        assertEquals(5L, (long) t.tally("k").counts.get(ExclusionTracker.EXCL_NOINDEX)); // never counted twice
        assertTrue(t.tally("k").complete);
    }

    @Test public void evictedEntriesMakeTrackedCrawlsIncomplete() {
        final FakeCache cache = new FakeCache();
        final ExclusionTracker t = new ExclusionTracker(cache);
        cache.push("example.com", "research", NOINDEX, 1);
        t.poll(100);
        t.track("k", "example.com", "research", 200);
        for (int i = 0; i < 1500; i++) cache.push("example.com", "research", NOINDEX, 210);
        t.poll(300);
        assertFalse(t.tally("k").complete);
        assertEquals(1000L, (long) t.tally("k").counts.get(ExclusionTracker.EXCL_NOINDEX));
        t.track("later", "later.example", "research", 400);
        cache.push("later.example", "research", NOINDEX, 410);
        t.poll(420);
        assertTrue(t.tally("later").complete);
        assertEquals(1L, (long) t.tally("later").counts.get(ExclusionTracker.EXCL_NOINDEX));
    }

    @Test public void emptyCacheAtBaselineAndClearedCache() {
        final FakeCache cache = new FakeCache();
        final ExclusionTracker t = new ExclusionTracker(cache);
        t.poll(100);
        t.track("k", "example.com", "research", 200);
        cache.push("example.com", "research", NOINDEX, 210);
        t.poll(220);
        assertTrue(t.tally("k").complete);
        assertEquals(1L, (long) t.tally("k").counts.get(ExclusionTracker.EXCL_NOINDEX));
        cache.entries.clear(); // administrator cleared the ErrorCache
        cache.push("example.com", "research", NOINDEX, 230);
        t.poll(240);
        assertFalse(t.tally("k").complete);
        assertEquals(2L, (long) t.tally("k").counts.get(ExclusionTracker.EXCL_NOINDEX));
    }

    @Test public void crawlFirstSeenAfterAReadSinceItsStartIsIncomplete() {
        final FakeCache cache = new FakeCache();
        final ExclusionTracker t = new ExclusionTracker(cache);
        t.poll(100);
        t.poll(300);
        t.track("k", "example.com", "research", 200); // started at 200, but a read at 300 did not know it
        assertFalse(t.tally("k").complete);
        t.track("k2", "example2.com", "research", 300);
        assertTrue(t.tally("k2").complete);
    }

    @Test public void reasonsOfYaCyAreClassified() {
        assertEquals(ExclusionTracker.EXCL_NOINDEX, ExclusionTracker.category("X-YACY-Index-Control header prohibits indexing"));
        assertEquals(ExclusionTracker.EXCL_FILTER, ExclusionTracker.category("denied by profile rule, process case=LOCAL_CRAWLING, profile name = x"));
        assertEquals(ExclusionTracker.EXCL_FILTER, ExclusionTracker.category("Not Condensed Resource 'u': indexing prevented by regular expression on content"));
        assertEquals(ExclusionTracker.EXCL_OTHER, ExclusionTracker.category("missing in cache"));
        assertEquals(ExclusionTracker.EXCL_OTHER, ExclusionTracker.category("redirection not wanted"));
    }
}
