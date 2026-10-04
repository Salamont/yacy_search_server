/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Temporary embedded index only. */
package net.yacy.scoutro.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
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

/** Data-quality facets (canonical, titles, descriptions) and the directory scan against YaCy's real schema. */
public class IndexQualitySolrTest {
    private static final String HOST = "www.q.example";
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
        add(HOST, "", 200, "Home", 1L, "Start", 11L, "https://www.q.example/", true);
        add(HOST, "a/one", 200, "Same", 2L, "Shared", 12L, "https://www.q.example/a/one", true);
        add(HOST, "a/two", 200, "Same", 2L, null, null, "https://www.q.example/a/one", false);  // canonical elsewhere
        add(HOST, "a/b/three", 200, null, null, "Shared", 12L, null, null);
        add(HOST, "c/x.pdf", 200, null, null, null, null, null, null);
        add(HOST, "a/missing", 404, null, null, null, null, null, null);
        add(HOST, "about", 200, "About", 3L, null, null, null, null);
        add("other.q.example", "", 200, "Same", 2L, null, null, null, null);                       // same title, other host
        instance.getDefaultServer().commit();
    }

    @AfterClass public static void close() {
        if (instance != null) instance.close();
    }

    private static void add(final String host, final String path, final int status, final String title, final Long titleSignature,
            final String description, final Long descriptionSignature, final String canonical, final Boolean self) throws Exception {
        final String url = "https://" + host + "/" + path;
        final DigestURL d = new DigestURL(url);
        final SolrInputDocument s = new SolrInputDocument();
        s.setField("id", ASCII.String(d.hash()));
        s.setField("sku", url);
        s.setField("host_s", host);
        s.setField("collection_sxt", List.of("q"));
        s.setField("httpstatus_i", status);
        s.setField("load_date_dt", new Date(1_790_000_000_000L));
        if (status != 200) s.setField("failtype_s", "fail");
        if (title != null) s.setField("title", List.of(title));
        if (titleSignature != null) s.setField("title_exact_signature_l", titleSignature);
        if (description != null) s.setField("description_txt", List.of(description));
        if (descriptionSignature != null) s.setField("description_exact_signature_l", descriptionSignature);
        if (canonical != null) s.setField("canonical_s", canonical);
        if (self != null) s.setField("canonical_equal_sku_b", self);
        instance.getDefaultServer().add(s);
    }

    private static IndexFacets facets(final boolean quality) {
        return new IndexFacets(p -> solr.getResponseByParams(p).getResponse(),
                f -> IndexFacets.QUALITY_FIELDS.contains(f) ? quality : schema.isEmpty() || schema.contains(f));
    }

    @Test public void hostQuality() throws Exception {
        final JsonObject r = facets(true).read("q", HOST, null);
        assertEquals(6, r.getLong("ok"));
        final JsonObject c = r.getJSONObject("canonical");
        assertEquals(List.of(3L, 2L, 1L, 3L), List.of(c.getLong("with"), c.getLong("self"), c.getLong("elsewhere"), c.getLong("without")));
        final JsonObject t = r.getJSONObject("titles");
        assertEquals(List.of(4L, 2L, 1L, 2L), List.of(t.getLong("with"), t.getLong("missing"), t.getLong("same_groups"), t.getLong("same_urls")));
        assertFalse(t.getBoolean("same_truncated"));
        final JsonObject d = r.getJSONObject("descriptions");
        assertEquals(List.of(3L, 3L, 1L, 2L), List.of(d.getLong("with"), d.getLong("missing"), d.getLong("same_groups"), d.getLong("same_urls")));
        assertTrue(r.getJSONArray("unavailable").isEmpty());
    }

    @Test public void collectionHasPresenceButNoCrossHostDuplicates() throws Exception {
        final JsonObject r = facets(true).read("q", null, null);
        assertEquals(7, r.getLong("ok"));
        assertEquals(5, r.getJSONObject("titles").getLong("with"));
        assertFalse(r.getJSONObject("titles").has("same_groups"));
        assertEquals(3, r.getJSONObject("canonical").getLong("with"));
    }

    @Test public void disabledQualityFieldsAreNamed() throws Exception {
        final JsonObject r = facets(false).read("q", HOST, null);
        final String unavailable = r.getJSONArray("unavailable").toString();
        for (final CollectionSchema f : IndexFacets.QUALITY_FIELDS) assertTrue(unavailable, unavailable.contains(f.getSolrFieldName()));
        assertFalse(r.has("canonical"));
        assertEquals(4, r.getJSONObject("titles").getLong("with"));
        assertFalse(r.getJSONObject("titles").has("same_groups"));
        assertFalse(facets(false).read("q", null, null).getJSONArray("unavailable").toString().contains("title_exact_signature_l"));
    }

    @Test public void emptyHost() throws Exception {
        final JsonObject r = facets(true).read("q", "absent.q.example", null);
        assertEquals(0, r.getJSONObject("canonical").getLong("with"));
        assertEquals(0, r.getJSONObject("titles").getLong("missing"));
        assertEquals(0, r.getJSONObject("titles").getLong("same_groups"));
        assertEquals(0, facets(true).directories("q", "absent.q.example").getJSONArray("items").length());
    }

    @Test public void directories() throws Exception {
        final JsonObject r = facets(true).directories("q", "WWW.q.example");
        final JsonArray items = r.getJSONArray("items");
        assertEquals("/a/", items.getJSONObject(0).getString("directory"));
        assertEquals(4, items.getJSONObject(0).getLong("documents"));
        assertEquals(3, items.getJSONObject(0).getLong("ok"));
        assertEquals("/", items.getJSONObject(1).getString("directory"));
        assertEquals(2, items.getJSONObject(1).getLong("documents"));
        assertEquals("/c/", items.getJSONObject(2).getString("directory"));
        assertEquals(List.of(3L, 7L, 7L), List.of(r.getLong("directories"), r.getLong("scanned"), r.getLong("total")));
        assertFalse(r.getBoolean("truncated"));
    }

    @Test public void directoryOfUrl() {
        assertEquals("/", IndexFacets.directory("https://h.example/"));
        assertEquals("/", IndexFacets.directory("https://h.example"));
        assertEquals("/", IndexFacets.directory("https://h.example/x.html"));
        assertEquals("/", IndexFacets.directory("https://h.example/a?x=/y/z"));
        assertEquals("/a/", IndexFacets.directory("https://h.example/a/"));
        assertEquals("/a/", IndexFacets.directory("https://h.example/a/b/c.html#f"));
        assertEquals("/z/", IndexFacets.directory("http://h.example:8080/z/y"));
        assertEquals("/a b/", IndexFacets.directory("https://h.example/a b/c"));
        assertEquals(IndexFacets.DIRECTORY_CHARS + 1, IndexFacets.directory("https://h.example/" + "d".repeat(300) + "/x").length());
    }
}
