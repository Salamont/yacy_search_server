/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. No index or network access. */
package net.yacy.scoutro.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.junit.Test;

import net.yacy.search.schema.CollectionSchema;

public class CrawlOutcomeTest {
    private static final long START = 1_790_000_000_000L;
    private static final String JOB = "3276af9c-b8b5-4b8c-bc2b-17b8d1905eb7";
    private static final CrawlOutcome.Crawl CRAWL =
            new CrawlOutcome.Crawl("c1", "0123456789abcdef0123456789abcdef", "WWW.Example.com", "research", START, START + 60_000L, 2, 15);

    private static Map<String, Object> count(final long n) {
        final Map<String, Object> m = new HashMap<>();
        m.put("count", n);
        return m;
    }

    /** Facet answer shaped like Solr's JSON facet response. */
    static NamedList<Object> answer(final long since, final long ok, final long redirect, final long client, final long server,
            final long excluded, final long failed, final long robots, final long before, final long beforeOk) {
        final Map<String, Object> s = count(since);
        if (since > 0) {
            s.put(CrawlOutcome.PAGES_OK, count(ok));
            s.put(CrawlOutcome.PAGES_REDIRECT, count(redirect));
            s.put(CrawlOutcome.PAGES_CLIENT_ERROR, count(client));
            s.put(CrawlOutcome.PAGES_SERVER_ERROR, count(server));
            s.put(CrawlOutcome.PAGES_EXCLUDED, count(excluded));
            s.put(CrawlOutcome.PAGES_FAILED, count(failed));
            s.put(CrawlOutcome.PAGES_ROBOTS, count(robots));
        }
        final Map<String, Object> b = count(before);
        if (before > 0) b.put(CrawlOutcome.PAGES_NOT_RELOADED_OK, count(beforeOk));
        final NamedList<Object> facets = new NamedList<>();
        facets.add("count", since + before);
        facets.add("since", s);
        facets.add("before", b);
        final NamedList<Object> result = new NamedList<>();
        result.add("responseHeader", new NamedList<>());
        result.add("facets", facets);
        return result;
    }

    private static CrawlOutcome outcome(final NamedList<Object> answer, final AtomicReference<ModifiableSolrParams> seen) {
        return new CrawlOutcome(p -> { if (seen != null) seen.set(p); return answer; }, f -> true);
    }

    @Test public void oneBoundedQueryScopedToHostCollectionAndCrawlStart() throws Exception {
        final AtomicReference<ModifiableSolrParams> seen = new AtomicReference<>();
        outcome(answer(15, 12, 2, 1, 0, 2, 1, 0, 4, 3), seen).pages(CRAWL);
        final ModifiableSolrParams p = seen.get();
        assertEquals("host_s:\"www.example.com\" AND collection_sxt:\"research\"", p.get("q"));
        assertEquals("lucene", p.get("defType"));
        assertEquals("0", p.get("rows"));
        assertEquals("2000", p.get("timeAllowed"));
        final String facets = p.get("json.facet");
        assertTrue(facets, facets.contains("load_date_dt:[2026-09-21T14:13:20Z TO *]"));
        assertTrue(facets, facets.contains("load_date_dt:[* TO 2026-09-21T14:13:20Z}"));
        assertTrue(facets, facets.contains("httpstatus_i:200 AND -failtype_s:[* TO *]"));
        assertTrue(facets, facets.contains("failreason_s:FINAL_ROBOTS_RULE*"));
        assertTrue(facets, facets.contains("{!prefix f=failreason_s}" + CrawlOutcome.CRAWLER_REDIRECT));
    }

    @Test public void countersAreReadAndNotReloadedIsSeparate() throws Exception {
        final Map<String, Long> pages = outcome(answer(15, 12, 2, 1, 0, 2, 1, 1, 4, 3), null).pages(CRAWL);
        assertEquals(15L, (long) pages.get(CrawlOutcome.PAGES_TOTAL));
        assertEquals(12L, (long) pages.get(CrawlOutcome.PAGES_OK));
        assertEquals(2L, (long) pages.get(CrawlOutcome.PAGES_REDIRECT));
        assertEquals(1L, (long) pages.get(CrawlOutcome.PAGES_CLIENT_ERROR));
        assertEquals(1L, (long) pages.get(CrawlOutcome.PAGES_FAILED));
        assertEquals(1L, (long) pages.get(CrawlOutcome.PAGES_ROBOTS));
        assertEquals(4L, (long) pages.get(CrawlOutcome.PAGES_NOT_RELOADED));
        assertEquals(3L, (long) pages.get(CrawlOutcome.PAGES_NOT_RELOADED_OK));
    }

    @Test public void emptyBucketsAreZeroWithoutSubFacets() throws Exception {
        final Map<String, Long> pages = outcome(answer(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), null).pages(CRAWL);
        assertEquals(0L, (long) pages.get(CrawlOutcome.PAGES_OK));
        assertEquals(0L, (long) pages.get(CrawlOutcome.PAGES_NOT_RELOADED_OK));
        assertEquals("not_indexed", CrawlOutcome.outcome(pages));
        final NamedList<Object> facets = new NamedList<>();
        facets.add("count", 0L); // what Solr sends for a host without documents
        final NamedList<Object> nothing = new NamedList<>();
        nothing.add("facets", facets);
        final Map<String, Long> none = outcome(nothing, null).pages(CRAWL);
        assertEquals(0L, (long) none.get(CrawlOutcome.PAGES_TOTAL));
        assertEquals(0L, (long) none.get(CrawlOutcome.PAGES_FAILED));
        assertEquals("not_indexed", CrawlOutcome.outcome(none));
    }

    @Test public void partialOrIncompleteAnswersFail() {
        final NamedList<Object> partial = answer(1, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        final NamedList<Object> header = new NamedList<>();
        header.add("partialResults", true);
        partial.setVal(0, header);
        final NamedList<Object> incomplete = answer(1, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        @SuppressWarnings("unchecked")
        final Map<String, Object> since = (Map<String, Object>) ((NamedList<Object>) incomplete.get("facets")).get("since");
        since.remove(CrawlOutcome.PAGES_FAILED);
        final NamedList<Object> noFacets = new NamedList<>();
        for (final NamedList<Object> answer : java.util.List.of(partial, incomplete, noFacets)) {
            try { outcome(answer, null).pages(CRAWL); fail(); } catch (final IOException expected) { }
        }
        try { new CrawlOutcome(p -> null, f -> true).pages(CRAWL); fail(); } catch (final IOException expected) { }
    }

    @Test public void missingSchemaFieldsGiveNoCountersInsteadOfWrongOnes() throws Exception {
        final Map<String, Long> none = new CrawlOutcome(p -> { throw new AssertionError("no query"); },
                f -> f != CollectionSchema.load_date_dt).pages(CRAWL);
        assertTrue(none.isEmpty());
        assertEquals("unknown", CrawlOutcome.outcome(none));
        final AtomicReference<ModifiableSolrParams> seen = new AtomicReference<>();
        final Map<String, Long> withoutRobots = new CrawlOutcome(p -> { seen.set(p); return answer(1, 1, 0, 0, 0, 0, 0, 0, 0, 0); },
                f -> f != CollectionSchema.failreason_s).pages(CRAWL);
        assertFalse(withoutRobots.containsKey(CrawlOutcome.PAGES_ROBOTS));
        assertFalse(seen.get().get("json.facet").contains("failreason_s"));
    }

    @Test public void outcomeRule() {
        final Map<String, Long> p = new LinkedHashMap<>();
        p.put(CrawlOutcome.PAGES_OK, 3L);
        assertEquals("indexed", CrawlOutcome.outcome(p));
        p.put(CrawlOutcome.PAGES_FAILED, 1L);
        assertEquals("partial", CrawlOutcome.outcome(p));
        p.put(CrawlOutcome.PAGES_OK, 0L);
        p.put(CrawlOutcome.PAGES_NOT_RELOADED_OK, 5L);
        assertEquals("not_reloaded", CrawlOutcome.outcome(p));
        p.put(CrawlOutcome.PAGES_NOT_RELOADED_OK, 0L);
        assertEquals("not_indexed", CrawlOutcome.outcome(p));
    }

    @Test public void snapshotCarriesParametersExclusionsAndCoverage() throws Exception {
        final Map<String, Long> pages = outcome(answer(15, 12, 2, 1, 0, 2, 0, 0, 4, 3), null).pages(CRAWL);
        final Map<String, Long> excl = new HashMap<>();
        excl.put(ExclusionTracker.EXCL_NOINDEX, 2L);
        final CrawlSnapshot s = CrawlOutcome.snapshot(CRAWL, pages, new ExclusionTracker.Tally(excl, true), JOB, "example.com");
        assertEquals("c1", s.crawlId);
        assertEquals(START + 60_000L, (long) s.endedAt);
        assertEquals(JOB, s.job);
        assertEquals("example.com", s.discoveryDomain);
        assertEquals(2L, (long) s.counters.get(CrawlOutcome.DEPTH));
        assertEquals(15L, (long) s.counters.get(CrawlOutcome.MAX_PAGES));
        assertEquals(2L, (long) s.counters.get(ExclusionTracker.EXCL_NOINDEX));
        assertEquals("indexed", s.labels.get(CrawlOutcome.OUTCOME));
        assertEquals("complete", s.labels.get(CrawlOutcome.COVERAGE));
        final CrawlSnapshot unknown = CrawlOutcome.snapshot(new CrawlOutcome.Crawl("c2", null, "example.com", "research", START, null, 2, -1),
                new LinkedHashMap<>(), null, null, null);
        assertEquals("partial", unknown.labels.get(CrawlOutcome.COVERAGE));
        assertEquals("unknown", unknown.labels.get(CrawlOutcome.OUTCOME));
        assertFalse(unknown.counters.containsKey(CrawlOutcome.MAX_PAGES));
        assertNull(unknown.endedAt);
    }
}
