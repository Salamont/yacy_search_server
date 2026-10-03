/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import static org.junit.Assert.*;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.agents.AgentActionRegistry;
import net.yacy.search.schema.*;

import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real embedded Solr, with final, pending, missing and inconsistent reference values. */
public class SeoAnalysisTest {
    @ClassRule public static TemporaryFolder tmp = new TemporaryFolder();
    private static EmbeddedInstance instance;
    private static EmbeddedSolrConnector solr;
    private static CollectionConfiguration schema;
    private SeoAnalysis seo;
    private AtomicInteger queries;
    private ModifiableSolrParams last;

    @BeforeClass
    public static void index() throws Exception {
        File cfg = tmp.newFile("schema");
        Files.copy(
                new File("defaults/solr.collection.schema").toPath(),
                cfg.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        schema = new CollectionConfiguration(cfg, true);
        schema.ensureCitationFields();
        instance =
                new EmbeddedInstance(
                        new File("defaults/solr"),
                        tmp.newFolder("index"),
                        "collection1",
                        new String[] {"collection1"});
        solr = new EmbeddedSolrConnector(instance, "collection1");
        instance.getDefaultServer()
                .add(doc("a.example", "one", List.of("visible"), 7, 4, 3, 2, 5, null));
        instance.getDefaultServer()
                .add(doc("a.example", "zero", List.of("visible", "secret"), 0, 0, 0, 0, 5, null));
        instance.getDefaultServer()
                .add(
                        doc(
                                "a.example",
                                "missing",
                                List.of("visible", "unfinalized"),
                                0,
                                0,
                                0,
                                0,
                                -1,
                                null));
        instance.getDefaultServer()
                .add(doc("a.example", "pending", List.of("visible"), 99, 90, 9, 9, 5, "CITATION"));
        instance.getDefaultServer()
                .add(doc("a.example", "inconsistent", List.of("secret"), 5, 5, 5, 5, 5, null));
        instance.getDefaultServer()
                .add(doc("b.example", "private", List.of("secret"), 2, 0, 2, 2, 1, null));
        instance.getDefaultServer().commit();
    }

    @AfterClass
    public static void close() {
        if (instance != null) instance.close();
    }

    @Before
    public void setup() {
        queries = new AtomicInteger();
        seo =
                new SeoAnalysis(
                        schema,
                        p -> {
                            queries.incrementAndGet();
                            last = new ModifiableSolrParams(p);
                            return solr.getResponseByParams(p).getResponse();
                        });
    }

    private static SolrInputDocument doc(
            String host,
            String path,
            List<String> collections,
            int total,
            int internal,
            int external,
            int hosts,
            int extent,
            String marker)
            throws Exception {
        String url = "https://" + host + "/" + path;
        DigestURL d = new DigestURL(url);
        SolrInputDocument s = new SolrInputDocument();
        s.setField("id", ASCII.String(d.hash()));
        s.setField("sku", url);
        s.setField("host_s", host);
        s.setField("host_id_s", d.hosthash());
        s.setField("collection_sxt", collections);
        s.setField("references_i", total);
        s.setField("references_internal_i", internal);
        s.setField("references_external_i", external);
        s.setField("references_exthosts_i", hosts);
        s.setField("host_extent_i", extent);
        if (marker != null) s.setField("process_sxt", List.of(marker));
        s.setField("title", List.of("Title " + path));
        s.setField("h1_txt", List.of("Heading"));
        s.setField("wordcount_i", 100);
        s.setField("crawldepth_i", 2);
        s.setField("httpstatus_i", 200);
        s.setField("url_protocol_s", "https");
        s.setField("language_s", "en");
        s.setField("load_date_dt", new Date(1000));
        s.setField("last_modified", new Date(0));
        s.setField("inboundlinkscount_i", 8);
        s.setField("outboundlinkscount_i", 3);
        return s;
    }

    private JSONObject call(String path, Map<String, String> q, List<String> scope)
            throws Exception {
        return seo.route(Arrays.asList(path.split("/")), q, scope);
    }

    private JSONObject summary(List<String> scope) throws Exception {
        return call("hosts/a.example", Map.of(), scope);
    }

    private JSONObject pages(Map<String, String> q) throws Exception {
        return call("hosts/a.example/pages", q, null);
    }

    private static String id(String path) throws Exception {
        return ASCII.String(new DigestURL("https://a.example/" + path).hash());
    }

    private void error(int status, String path, Map<String, String> q, List<String> scope)
            throws Exception {
        try {
            call(path, q, scope);
            fail("Expected refusal");
        } catch (ApiException e) {
            assertEquals(status, e.status());
            assertFalse(e.toJson().toString().contains("private cause"));
        }
    }

    @Test
    public void hostSearchIsBoundedAndScoped() throws Exception {
        JSONObject j = call("hosts", Map.of("q", "a.", "limit", "1"), List.of("visible"));
        assertEquals(1, j.getLong("total"));
        assertEquals(4, j.getJSONArray("items").getJSONObject(0).getLong("pages"));
        assertEquals(1, queries.get());
        assertEquals("0", last.get("rows"));
        assertEquals("2000", last.get("timeAllowed"));
    }

    @Test
    public void unknownHostIs404() throws Exception {
        error(404, "hosts/absent.example", Map.of(), null);
    }

    @Test
    public void hostSummaryHasExactCoverageAndReliableSums() throws Exception {
        JSONObject j = summary(null), c = j.getJSONObject("citation");
        assertEquals(5, j.getLong("indexed_pages"));
        assertEquals(2, c.getLong("processed_pages"));
        assertEquals(1, c.getLong("pending_pages"));
        assertEquals(1, c.getLong("unavailable_pages"));
        assertEquals(1, c.getLong("unknown_pages"));
        assertEquals(.4, c.getDouble("coverage"), .0001);
        assertEquals(7, c.getLong("references_total"));
        assertEquals(4, c.getLong("references_internal"));
        assertEquals(3, c.getLong("references_external"));
        assertFalse(c.has("external_hosts"));
        assertEquals(1, queries.get());
    }

    @Test
    public void contentAndOutgoingAreNotBacklinks() throws Exception {
        JSONObject j = summary(null);
        assertEquals(
                40,
                j.getJSONObject("technology").getJSONObject("outgoing_internal").getLong("value"));
        assertEquals(100, j.getJSONObject("content").getJSONObject("word_count").getInt("value"));
        assertEquals("1970-01-01T00:00:01Z", j.getJSONObject("crawl").getString("load_date"));
    }

    @Test
    public void paginationAndStableSorting() throws Exception {
        JSONObject j = pages(Map.of("limit", "2", "offset", "1", "sort", "url"));
        assertEquals(5, j.getLong("total"));
        assertEquals(2, j.getJSONArray("items").length());
        assertTrue(last.get("sort").endsWith(",id asc"));
        assertFalse(last.get("fl").contains("text_t"));
        assertEquals(1, queries.get());
    }

    @Test
    public void referenceSortExcludesNonFinalizedValues() throws Exception {
        JSONObject j = pages(Map.of("sort", "references_external", "order", "desc"));
        assertEquals(2, j.getLong("total"));
        assertEquals(
                3,
                j.getJSONArray("items")
                        .getJSONObject(0)
                        .getJSONObject("citation")
                        .getInt("references_external"));
    }

    @Test
    public void invalidSortAndRawSolrParametersRefused() throws Exception {
        for (String key : List.of("fq", "fl", "qf", "json.facet"))
            error(400, "hosts/a.example/pages", Map.of(key, "*:*"), null);
        error(400, "hosts/a.example/pages", Map.of("sort", "random()"), null);
        error(
                400,
                "hosts/a.example/pages",
                Map.of("sort", "references_external", "citation", "all"),
                null);
        assertEquals(0, queries.get());
    }

    @Test
    public void paginationBoundsRefused() throws Exception {
        error(400, "hosts/a.example/pages", Map.of("limit", "101"), null);
        error(400, "hosts/a.example/pages", Map.of("offset", "-1"), null);
    }

    @Test
    public void processedZeroIsRealZero() throws Exception {
        JSONObject c = call("pages/" + id("zero"), Map.of(), null).getJSONObject("citation");
        assertEquals("processed", c.getString("status"));
        assertEquals(0, c.getInt("references_total"));
    }

    @Test
    public void pendingAndUnavailableRemainNull() throws Exception {
        for (String path : List.of("pending", "missing")) {
            JSONObject c = call("pages/" + id(path), Map.of(), null).getJSONObject("citation");
            assertEquals(path.equals("pending") ? "pending" : "unavailable", c.getString("status"));
            assertTrue(c.isNull("references_total"));
        }
    }

    @Test
    public void inconsistentReferencesAreUnknown() throws Exception {
        assertEquals(
                "unknown",
                call("pages/" + id("inconsistent"), Map.of(), null)
                        .getJSONObject("citation")
                        .getString("status"));
        assertEquals(1, pages(Map.of("citation", "unknown")).getInt("total"));
    }

    @Test
    public void targetScopeHidesIdsAndGlobalExtent() throws Exception {
        error(404, "pages/" + id("inconsistent"), Map.of(), List.of("visible"));
        JSONObject j = call("pages/" + id("zero"), Map.of(), List.of("visible"));
        assertEquals("[\"visible\"]", j.getJSONArray("collections").toString());
        assertTrue(j.getJSONObject("citation").isNull("host_extent"));
        assertEquals(4, summary(List.of("visible")).getLong("indexed_pages"));
        assertEquals(0, call("hosts", Map.of(), List.of()).getInt("total"));
    }

    @Test
    public void filtersMatchIndividualStatuses() throws Exception {
        for (String s : List.of("processed", "pending", "unavailable", "unknown")) {
            JSONObject j = pages(Map.of("citation", s));
            for (int i = 0; i < j.getJSONArray("items").length(); i++)
                assertEquals(
                        s,
                        j.getJSONArray("items")
                                .getJSONObject(i)
                                .getJSONObject("citation")
                                .getString("status"));
        }
    }

    @Test
    public void hostValidationNeverFetches() throws Exception {
        for (String s :
                List.of(
                        "http://a.example",
                        "localhost",
                        "127.0.0.1",
                        "a.example:80",
                        "a.example/path",
                        "a.example\" OR *:*")) {
            try {
                SeoAnalysis.host(s);
                fail("Invalid host accepted");
            } catch (ApiException e) {
                assertEquals(400, e.status());
            }
        }
        assertEquals("xn--bcher-kva.example", SeoAnalysis.host("BÜCHER.example"));
        assertEquals(0, queries.get());
    }

    @Test
    public void partialAndFailedQueriesAreErrors() throws Exception {
        seo =
                new SeoAnalysis(
                        schema,
                        p -> {
                            NamedList<Object> n = new NamedList<>();
                            NamedList<Object> h = new NamedList<>();
                            h.add("partialResults", true);
                            n.add("responseHeader", h);
                            return n;
                        });
        error(503, "hosts/a.example", Map.of(), null);
        seo =
                new SeoAnalysis(
                        schema,
                        p -> {
                            throw new java.io.IOException("private cause");
                        });
        error(503, "hosts/a.example", Map.of(), null);
    }

    @Test
    public void missingFieldsAreNotZero() throws Exception {
        schema.get("references_external_i").setEnable(false);
        try {
            JSONObject c = summary(null).getJSONObject("citation");
            assertTrue(c.isNull("processed_pages"));
            assertTrue(c.isNull("references_total"));
            assertEquals(
                    "unavailable",
                    call("pages/" + id("one"), Map.of(), null)
                            .getJSONObject("citation")
                            .getString("status"));
        } finally {
            schema.get("references_external_i").setEnable(true);
        }
    }

    @Test
    public void hostWithoutFinalizedTargetsDoesNotInventReferenceZeros() throws Exception {
        JSONObject c = summary(List.of("unfinalized")).getJSONObject("citation");
        assertEquals(0, c.getInt("processed_pages"));
        assertEquals(1, c.getInt("unavailable_pages"));
        assertEquals(0.0, c.getDouble("coverage"), 0.0001);
        for (String key :
                List.of("references_total", "references_internal", "references_external")) {
            assertTrue(key, c.isNull(key));
        }
    }

    @Test
    public void disabledContentFieldIsNotReportedAsZero() throws Exception {
        schema.get("wordcount_i").setEnable(false);
        try {
            JSONObject metric = summary(null).getJSONObject("content").getJSONObject("word_count");
            assertTrue(metric.isNull("value"));
            assertTrue(metric.isNull("measured_pages"));
            assertTrue(
                    call("pages/" + id("zero"), Map.of(), null)
                            .getJSONObject("content")
                            .isNull("word_count"));
            error(400, "hosts/a.example/pages", Map.of("sort", "word_count"), null);
        } finally {
            schema.get("wordcount_i").setEnable(true);
        }
    }

    @Test
    public void newReadGrantDoesNotExpandPresets() {
        assertNotNull(AgentActionRegistry.get("seo.read"));
        assertFalse(AgentActionRegistry.get("seo.read").presetable);
        for (String preset : AgentActionRegistry.presetNames())
            assertFalse(AgentActionRegistry.preset(preset).contains("seo.read"));
    }

    @Test
    public void agentRouteIsReadOnly() {
        assertEquals(
                "seo.read",
                AgentApi.route("GET", List.of("seo", "hosts", "a.example", "pages")).action);
        assertNotNull(AgentApi.route("POST", List.of("seo", "hosts")).error);
    }

    @Test
    public void readsDoNotChangeIndex() throws Exception {
        long before = solr.getSize();
        summary(null);
        pages(Map.of());
        call("pages/" + id("one"), Map.of(), null);
        assertEquals(before, solr.getSize());
    }
}
