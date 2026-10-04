/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Temporary embedded index only. */
package net.yacy.scoutro.report;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.solr.common.SolrInputDocument;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.search.schema.CollectionConfiguration;

/** The capture query against YaCy's real collection1 schema in an embedded Solr core. */
public class CrawlOutcomeSolrTest {
    private static final long START = 1_790_000_000_000L;
    @ClassRule public static TemporaryFolder tmp = new TemporaryFolder();
    private static EmbeddedInstance instance;
    private static EmbeddedSolrConnector solr;
    private static CollectionConfiguration schema;

    @BeforeClass public static void index() throws Exception {
        final File cfg = tmp.newFile("schema");
        Files.copy(new File("defaults/solr.collection.schema").toPath(), cfg.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        schema = new CollectionConfiguration(cfg, true);
        instance = new EmbeddedInstance(new File("defaults/solr"), tmp.newFolder("index"), "collection1", new String[] {"collection1"});
        solr = new EmbeddedSolrConnector(instance, "collection1");
        final long after = START + 1000, before = START - 86_400_000L;
        add("www.example.com", "a", "research", START, 200, null, null);   // exactly at the start: this crawl
        add("www.example.com", "b", "research", after, 200, null, null);
        add("www.example.com", "c", "research", after, 200, null, null);
        add("www.example.com", "moved", "research", after, 301, "excl", "FINAL_REDIRECT_RULE redirect to https://www.example.com/c");
        add("www.example.com", "missing", "research", after, 404, "fail", "TEMPORARY_NETWORK_FAILURE wrong http status code (http return code = 404)");
        add("www.example.com", "private", "research", after, -1, "excl", "FINAL_ROBOTS_RULE denied by robots.txt");
        add("www.example.com", "hop", "research", after, -1, "fail", CrawlOutcome.CRAWLER_REDIRECT
                + "https://www.example.com/hop to https://www.example.com/b placed on crawler queue for double-check");
        add("www.example.com", "offsite", "research", after, -1, "fail", CrawlOutcome.CRAWLER_REDIRECT
                + "https://www.example.com/offsite aborted. Reason : url does not match must-match filter");
        add("www.example.com", "old1", "research", before, 200, null, null);
        add("www.example.com", "old2", "research", before, 200, null, null);
        add("www.example.com", "oldgone", "research", before, 404, "fail", "TEMPORARY_NETWORK_FAILURE wrong http status code");
        add("www.example.com", "elsewhere", "other", after, 200, null, null);
        add("example.com", "apex", "research", after, 200, null, null);
        instance.getDefaultServer().commit();
    }

    @AfterClass public static void close() {
        if (instance != null) instance.close();
    }

    private static void add(final String host, final String path, final String collection, final long loaded, final int status,
            final String failType, final String failReason) throws Exception {
        final String url = "https://" + host + "/" + path;
        final DigestURL d = new DigestURL(url);
        final SolrInputDocument s = new SolrInputDocument();
        s.setField("id", ASCII.String(d.hash()));
        s.setField("sku", url);
        s.setField("host_s", host);
        s.setField("collection_sxt", List.of(collection));
        s.setField("httpstatus_i", status);
        s.setField("load_date_dt", new Date(loaded));
        if (failType != null) s.setField("failtype_s", failType);
        if (failReason != null) s.setField("failreason_s", failReason);
        instance.getDefaultServer().add(s);
    }

    @Test public void countsSplitAtTheCrawlStartForExactlyThisHostAndCollection() throws Exception {
        final CrawlOutcome outcome = new CrawlOutcome(p -> solr.getResponseByParams(p).getResponse(),
                f -> schema.isEmpty() || schema.contains(f));
        final Map<String, Long> pages = outcome.pages(new CrawlOutcome.Crawl("c1", null, "www.example.com", "research", START, null, 2, 15));
        assertEquals(8L, (long) pages.get(CrawlOutcome.PAGES_TOTAL));
        assertEquals(3L, (long) pages.get(CrawlOutcome.PAGES_OK));
        assertEquals(3L, (long) pages.get(CrawlOutcome.PAGES_REDIRECT)); // one stored 301, two followed by the crawler
        assertEquals(1L, (long) pages.get(CrawlOutcome.PAGES_CLIENT_ERROR));
        assertEquals(0L, (long) pages.get(CrawlOutcome.PAGES_SERVER_ERROR));
        assertEquals(2L, (long) pages.get(CrawlOutcome.PAGES_EXCLUDED));
        assertEquals(1L, (long) pages.get(CrawlOutcome.PAGES_FAILED));
        assertEquals(1L, (long) pages.get(CrawlOutcome.PAGES_ROBOTS));
        assertEquals(3L, (long) pages.get(CrawlOutcome.PAGES_NOT_RELOADED));
        assertEquals(2L, (long) pages.get(CrawlOutcome.PAGES_NOT_RELOADED_OK));
        assertEquals("partial", CrawlOutcome.outcome(pages));
    }

    @Test public void aHostWithoutDocumentsIsNotIndexed() throws Exception {
        final CrawlOutcome outcome = new CrawlOutcome(p -> solr.getResponseByParams(p).getResponse(), f -> true);
        final Map<String, Long> pages = outcome.pages(new CrawlOutcome.Crawl("c2", null, "unknown.example", "research", START, null, 2, 15));
        assertEquals(0L, (long) pages.get(CrawlOutcome.PAGES_TOTAL));
        assertEquals("not_indexed", CrawlOutcome.outcome(pages));
        final Map<String, Long> later = outcome.pages(new CrawlOutcome.Crawl("c3", null, "www.example.com", "research", START + 3_600_000L, null, 2, 15));
        assertEquals(0L, (long) later.get(CrawlOutcome.PAGES_OK));
        assertEquals("not_reloaded", CrawlOutcome.outcome(later));
    }
}
