/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import static org.junit.Assert.*;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;

/** Real temporary Solr: collection intersection, literal search and failure boundaries. */
public class IndexBrowseTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private EmbeddedInstance instance;
    private IndexBrowse browser;
    @Before public void setup() throws Exception {
        instance = new EmbeddedInstance(new File("defaults/solr"), tmp.newFolder("index"), "collection1", new String[]{"collection1"});
        final EmbeddedSolrConnector solr = new EmbeddedSolrConnector(instance, "collection1");
        for (final String[] row : new String[][]{{"a.example", "visible"}, {"a.example", "secret"}, {"b.example", "visible"}}) {
            final String url = "https://" + row[0] + "/" + row[1];
            final SolrInputDocument doc = new SolrInputDocument();
            doc.setField("id", ASCII.String(new DigestURL(url).hash())); doc.setField("sku", url);
            doc.setField("host_s", row[0]); doc.setField("collection_sxt", List.of(row[1])); doc.setField("httpstatus_i", 200);
            instance.getDefaultServer().add(doc);
        }
        final SolrInputDocument shared = new SolrInputDocument();
        shared.setField("id", ASCII.String(new DigestURL("https://shared.example/page").hash()));
        shared.setField("sku", "https://shared.example/page"); shared.setField("host_s", "shared.example");
        shared.setField("collection_sxt", List.of("visible", "secret")); shared.setField("httpstatus_i", 200);
        instance.getDefaultServer().add(shared); instance.getDefaultServer().commit();
        browser = new IndexBrowse(p -> solr.getResponseByParams(p).getResponse());
    }
    @After public void close() { if (instance != null) instance.close(); }
    @Test public void hostAndCollectionIntersectAndMembershipsStayScoped() throws Exception {
        final JSONObject result = browser.browse(Map.of("q", "a.example", "collection", "visible"), List.of("visible"));
        assertEquals(1, result.getLong("total"));
        assertEquals("https://a.example/visible", result.getJSONArray("documents").getJSONObject(0).getString("url"));
        final JSONObject all = browser.browse(Map.of(), List.of("visible"));
        assertEquals(3, all.getLong("total"));
        assertFalse(all.toString().contains("secret"));
        assertEquals(0, browser.browse(Map.of(), List.of()).getLong("total"));
    }
    @Test public void urlsAreLiteralAndSyntaxCannotWidenScope() throws Exception {
        assertEquals(1, browser.browse(Map.of("q", "https://a.example/visible"), List.of("visible")).getLong("total"));
        for (String input : List.of("*:*", "a.example OR *:*", "{!lucene}*:*", "\" OR collection_sxt:secret"))
            assertEquals(input, 0, browser.browse(Map.of("q", input), List.of("visible")).getLong("total"));
        assertEquals(0, browser.browse(Map.of("collection", "absent"), List.of("absent")).getLong("total"));
    }
    @Test public void boundedPaginationAndInvalidParametersFailClosed() throws Exception {
        assertEquals(1, browser.browse(Map.of("limit", "1", "offset", "1"), null).getJSONArray("documents").length());
        for (Map<String,String> input : List.of(Map.of("fq", "*:*"), Map.of("offset", "-1"), Map.of("limit", "101"))) {
            try { browser.browse(input, List.of("visible")); fail("invalid input accepted"); }
            catch (ApiException e) { assertEquals(400, e.status()); }
        }
        try { browser.browse(Map.of(), List.of("bad\" OR *:*")); fail("invalid collection accepted"); }
        catch (ApiException e) { assertEquals(400, e.status()); }
        final IndexBrowse partial = new IndexBrowse(p -> {
            final NamedList<Object> result = new NamedList<>();
            final NamedList<Object> header = new NamedList<>(); header.add("partialResults", true);
            result.add("responseHeader", header); return result;
        });
        try { partial.browse(Map.of(), null); fail("partial response accepted"); }
        catch (ApiException e) { assertEquals(503, e.status()); }
    }
}
