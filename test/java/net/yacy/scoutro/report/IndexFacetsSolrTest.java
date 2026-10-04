/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Temporary embedded index only. */
package net.yacy.scoutro.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;

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
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.search.schema.CollectionConfiguration;
import net.yacy.search.schema.CollectionSchema;

/** Live report facets against YaCy's real collection1 schema in an embedded Solr core. */
public class IndexFacetsSolrTest {
    private static final long T = 1_790_000_000_000L;
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
        add("www.example.com", "", T, 200, null, "text/html", 0, 11L, 21L);
        add("www.example.com", "a", T + 1000, 200, null, "text/html", 1, 12L, 22L);
        add("www.example.com", "a-copy", T + 2000, 200, null, "text/html", 1, 12L, 22L);   // exact duplicate of a
        add("www.example.com", "a-near", T + 3000, 200, null, "text/html", 2, 13L, 22L);   // similar to a
        add("www.example.com", "doc.pdf", T + 4000, 200, null, "application/pdf", 2, 14L, 24L);
        add("www.example.com", "missing", T + 5000, 404, "fail", "text/html", 2, null, null);
        add("www.example.com", "private", T - 86_400_000L, -1, "excl", "text/html", 1, null, null);
        add("shop.example.com", "", T, 200, null, "text/html", 0, 15L, 25L);
        add("www.example.com", "elsewhere", T, 200, null, "text/html", 0, 16L, 26L);       // other collection below
        instance.getDefaultServer().commit();
    }

    @AfterClass public static void close() {
        if (instance != null) instance.close();
    }

    private static void add(final String host, final String path, final long loaded, final int status, final String failType,
            final String mime, final int depth, final Long exact, final Long fuzzy) throws Exception {
        final String url = "https://" + host + "/" + path;
        final DigestURL d = new DigestURL(url);
        final SolrInputDocument s = new SolrInputDocument();
        s.setField("id", ASCII.String(d.hash()));
        s.setField("sku", url);
        s.setField("host_s", host);
        s.setField("collection_sxt", List.of("elsewhere".equals(path) ? "other" : "research"));
        s.setField("httpstatus_i", status);
        s.setField("load_date_dt", new Date(loaded));
        s.setField("content_type", List.of(mime));
        s.setField("crawldepth_i", depth);
        if (failType != null) s.setField("failtype_s", failType);
        if (exact != null) s.setField("exact_signature_l", exact);
        if (fuzzy != null) s.setField("fuzzy_signature_l", fuzzy);
        instance.getDefaultServer().add(s);
    }

    private static IndexFacets facets() {
        return new IndexFacets(p -> solr.getResponseByParams(p).getResponse(), f -> schema.isEmpty() || schema.contains(f));
    }

    private static long count(final JsonArray buckets, final Object value) {
        for (final Object b : buckets) if (String.valueOf(((JsonObject) b).get("value")).equals(String.valueOf(value))) return ((JsonObject) b).getLong("count");
        return 0;
    }

    @Test public void collectionFacets() throws Exception {
        final JsonObject r = facets().read("research", null, null);
        assertEquals(8, r.getLong("documents"));
        assertEquals(6, r.getLong("ok"));
        assertEquals(2, r.getLong("hosts"));
        assertEquals(6, count(r.getJSONArray("http_status"), 200));
        assertEquals(1, count(r.getJSONArray("http_status"), 404));
        assertEquals(1, r.getJSONObject("fail_type").getLong("excl"));
        assertEquals(1, r.getJSONObject("fail_type").getLong("fail"));
        assertEquals(5, count(r.getJSONArray("content_type"), "text/html"));
        assertEquals(1, count(r.getJSONArray("content_type"), "application/pdf"));
        assertEquals(2, count(r.getJSONArray("depth"), 0));
        assertEquals(2, count(r.getJSONArray("depth"), 1));
        assertEquals(2, count(r.getJSONArray("depth"), 2));
        assertEquals("0", String.valueOf(r.getJSONArray("depth").getJSONObject(0).get("value"))); // index order
        final JsonObject dup = r.getJSONObject("duplicates");
        assertEquals(1, dup.getLong("exact_groups"));
        assertEquals(2, dup.getLong("exact_urls"));
        assertEquals(1, dup.getLong("similar_groups"));
        assertEquals(3, dup.getLong("similar_urls"));
        assertTrue(!dup.getBoolean("exact_truncated"));
        assertEquals("2026-09-20T14:13:20Z", r.getString("oldest"));
        assertEquals("2026-09-21T14:13:25Z", r.getString("newest"));
        assertTrue(r.getJSONArray("unavailable").isEmpty());
    }

    @Test public void hostFacetsWithNotReloaded() throws Exception {
        final JsonObject r = facets().read("research", "WWW.example.com", T + 1000);
        assertEquals("www.example.com", r.getString("host"));
        assertEquals(7, r.getLong("documents"));
        assertEquals(5, r.getLong("ok"));
        assertEquals(2, r.getLong("not_reloaded")); // loaded before the crawl start
        assertTrue(!r.has("hosts"));
    }

    @Test public void emptyScopeIsZeroNotAnError() throws Exception {
        final JsonObject r = facets().read("research", "unknown.example", T);
        assertEquals(0, r.getLong("documents"));
        assertEquals(0, r.getLong("ok"));
        assertEquals(0, r.getLong("not_reloaded"));
        assertEquals(0, r.getJSONObject("duplicates").getLong("exact_groups"));
        assertTrue(r.isNull("oldest"));
    }

    @Test public void disabledFieldsAreListedAndRequiredFieldsFail() throws Exception {
        final JsonObject r = new IndexFacets(p -> solr.getResponseByParams(p).getResponse(),
                f -> f != CollectionSchema.exact_signature_l && f != CollectionSchema.content_type).read("research", null, null);
        assertTrue(r.getJSONArray("unavailable").toString().contains("exact_signature_l"));
        assertTrue(!r.has("content_type"));
        assertTrue(!r.getJSONObject("duplicates").has("exact_groups"));
        try {
            new IndexFacets(p -> solr.getResponseByParams(p).getResponse(), f -> f != CollectionSchema.host_s).read("research", null, null);
            fail();
        } catch (final IOException expected) { }
    }
}
