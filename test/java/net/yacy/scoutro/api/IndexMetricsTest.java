/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.api;
import static org.junit.Assert.*;
import java.io.File;
import java.util.List;
import java.util.Map;
import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
public class IndexMetricsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private EmbeddedInstance instance;
    private IndexMetrics metrics;
    @Before public void setup() throws Exception {
        instance = new EmbeddedInstance(new File("defaults/solr"), tmp.newFolder("index"), "collection1", new String[]{"collection1"});
        final EmbeddedSolrConnector solr = new EmbeddedSolrConnector(instance, "collection1");
        add("a.example", "one", List.of("visible"), 200, false);
        add("a.example", "two", List.of("visible", "secret"), 200, false);
        add("b.example", "private", List.of("secret"), 200, false);
        add("c.example", "error", List.of("visible"), 404, true);
        instance.getDefaultServer().commit();
        metrics = new IndexMetrics(p -> solr.getResponseByParams(p).getResponse());
    }
    private void add(String host, String name, List<String> cs, int status, boolean fail) throws Exception {
        final String url = "https://" + host + "/" + name;
        final SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", ASCII.String(new DigestURL(url).hash())); doc.setField("sku", url);
        doc.setField("host_s", host); doc.setField("collection_sxt", cs); doc.setField("httpstatus_i", status);
        if (fail) doc.setField("failtype_s", "fail");
        instance.getDefaultServer().add(doc);
    }
    @After public void close() { if (instance != null) instance.close(); }
    @Test public void scopeCountsDistinctHostsAndDeduplicatesSharedDocuments() throws Exception {
        JSONObject r = metrics.read(Map.of(), List.of("visible"));
        assertEquals(3, r.getLong("documents")); assertEquals(2, r.getLong("pages")); assertEquals(1, r.getLong("hosts"));
        r = metrics.read(Map.of(), List.of("visible", "secret"));
        assertEquals(4, r.getLong("documents")); assertEquals(3, r.getLong("pages")); assertEquals(2, r.getLong("hosts"));
        assertEquals(0, metrics.read(Map.of(), List.of()).getLong("hosts"));
        assertEquals(0, metrics.read(Map.of(), List.of("absent")).getLong("documents"));
    }
    @Test public void missingMetadataFailsClosedOnlyWithinTheSelectedScope() throws Exception {
        for (String missing : List.of("host_s", "httpstatus_i")) {
            final SolrInputDocument doc = new SolrInputDocument();
            final String url = "https://unknown.example/" + missing;
            doc.setField("id", ASCII.String(new DigestURL(url).hash())); doc.setField("sku", url);
            doc.setField("collection_sxt", List.of(missing));
            if (!missing.equals("host_s")) doc.setField("host_s", "unknown.example");
            if (!missing.equals("httpstatus_i")) doc.setField("httpstatus_i", 200);
            instance.getDefaultServer().add(doc); instance.getDefaultServer().commit();
            try { metrics.read(Map.of(), List.of(missing)); fail("missing metadata became a number"); }
            catch (ApiException e) { assertEquals(503, e.status()); }
        }
        assertEquals(1, metrics.read(Map.of(), List.of("visible")).getLong("hosts"));
    }
    @Test public void invalidSyntaxAndIncompleteAggregatesNeverBecomeZeroOrEstimate() throws Exception {
        for (Map<String,String> input : List.of(Map.of("q", "*:*"), Map.of("fq", "*:*"))) {
            try { metrics.read(input, null); fail(); } catch (ApiException e) { assertEquals(400, e.status()); }
        }
        try { metrics.read(Map.of(), List.of("bad\" OR *:*")); fail(); } catch (ApiException e) { assertEquals(400, e.status()); }
        for (boolean partial : List.of(true, false)) {
            IndexMetrics broken = new IndexMetrics(p -> { NamedList<Object> r = new NamedList<>();
                if (partial) { NamedList<Object> h = new NamedList<>(); h.add("partialResults", true); r.add("responseHeader", h); }
                return r; });
            try { broken.read(Map.of(), null); fail(); } catch (ApiException e) { assertEquals(503, e.status()); }
        }
    }
}
