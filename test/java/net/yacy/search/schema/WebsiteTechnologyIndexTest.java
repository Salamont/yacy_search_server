package net.yacy.search.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.apache.solr.common.SolrDocument;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.cora.federate.solr.instance.InstanceMirror;
import net.yacy.cora.protocol.ResponseHeader;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.document.Condenser;
import net.yacy.document.Document;
import net.yacy.document.VocabularyScraper;
import net.yacy.document.parser.html.Evaluation;
import net.yacy.document.parser.htmlParser;
import net.yacy.search.index.Fulltext;
import net.yacy.search.index.Segment;

/**
 * Scoutro R5: the signatures of {@code defaults/parser.tech.properties} on synthetic pages, through the real parser and
 * {@link CollectionConfiguration} into the index field {@code website_technology_sxt}: technical markers count, the
 * running text, links and code shown as text never do; a base product is never its Pro or Premium edition; Google
 * Analytics through gtag is no Google Tag Manager; every entry names its rule version, element and excerpt.
 */
public class WebsiteTechnologyIndexTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private CollectionConfiguration schema;
    private Segment segment;
    private EmbeddedSolrConnector solr;

    @BeforeClass
    public static void signatures() throws Exception {
        Evaluation.add(new File("defaults/parser.tech.properties"));
    }

    @Before
    public void setUp() throws Exception {
        this.schema = schema(new String(Files.readAllBytes(new File("defaults/solr.collection.schema").toPath()), StandardCharsets.UTF_8));
        this.segment = new Segment(new ConcurrentLog("WebsiteTechnologyIndexTest"), this.temporary.newFolder("segment"),
                this.temporary.newFolder("archive"), this.schema, new WebgraphConfiguration(new File("defaults/solr.webgraph.schema"), true));
        final EmbeddedInstance embedded = new EmbeddedInstance(new File("defaults/solr"), this.temporary.newFolder("solr"),
                CollectionSchema.CORE_NAME, new String[] {CollectionSchema.CORE_NAME});
        final Field mirror = Fulltext.class.getDeclaredField("solrInstances");
        mirror.setAccessible(true);
        ((InstanceMirror) mirror.get(this.segment.fulltext())).connectEmbedded(embedded);
        this.solr = this.segment.fulltext().getDefaultEmbeddedConnector();
    }

    @After
    public void close() {
        if (this.segment != null) this.segment.close();
    }

    private CollectionConfiguration schema(final String text) throws Exception {
        final File f = this.temporary.newFile();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return new CollectionConfiguration(f, true);
    }

    private static String fixture(final String name) throws Exception {
        return new String(Files.readAllBytes(new File("test/scoutro-technology/" + name).toPath()), StandardCharsets.UTF_8);
    }

    /** Parses and stores a page as a crawl does; returns the technologies by id: {rule version, element, matches, excerpt}. */
    private Map<String, String[]> crawl(final String url, final String html) throws Exception {
        final DigestURL u = new DigestURL(url);
        final Document document = new htmlParser().parse(u, "text/html", "UTF-8", new VocabularyScraper(), 0,
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)))[0];
        final Condenser condenser = new Condenser(document, new VocabularyScraper(), true, false, null, false, false, 0);
        final ResponseHeader header = new ResponseHeader(200);
        final Map<String, Pattern> collections = new LinkedHashMap<>();
        collections.put("web", Pattern.compile(".*"));
        final CollectionConfiguration.SolrVector vector = this.schema.yacy2solr(this.segment, collections, header, document, condenser, null,
                "de", false, null, "technology-fixture");
        this.segment.storeDocument(u, null, header, document, vector, "de", condenser, null, "technology-fixture", false);
        this.solr.commit(true);
        final SolrDocument d = this.solr.getDocumentById(net.yacy.cora.document.encoding.ASCII.String(u.hash()));
        assertNotNull(url, d);
        final Map<String, String[]> out = new TreeMap<>();
        final Collection<Object> v = d.getFieldValues(CollectionSchema.website_technology_sxt.getSolrFieldName());
        if (v != null) {
            for (final Object o : v) {
                final String[] parts = o.toString().split("\\|", 5);
                assertEquals(o.toString(), 5, parts.length);
                out.put(parts[0], new String[] {parts[1], parts[2], parts[3], parts[4]});
            }
        }
        return out;
    }

    @Test
    public void everyTechnologyOfTheCatalogIsFoundByItsTechnicalMarker() throws Exception {
        final Map<String, String[]> t = crawl("https://www.musterberatung.de/", fixture("stack.html"));
        assertEquals(new java.util.TreeSet<>(java.util.List.of("wordpress", "elementor", "elementor-pro", "yoast-seo", "yoast-seo-premium",
                "wp-rocket", "google-tag-manager", "google-analytics", "pardot")), t.keySet());
        for (final String[] e : t.values()) {
            assertEquals("the rule version goes with every match", "1", e[0]);
            assertFalse(e[3].isEmpty());
            assertTrue(e[3], e[3].length() <= Evaluation.MAX_LOCATOR);
        }
        assertEquals("metagenerator", t.get("wordpress")[1]);
        assertTrue(t.get("wordpress")[3], t.get("wordpress")[3].startsWith("WordPress 6.4.2"));
        assertEquals("metagenerator", t.get("elementor")[1]);
        assertEquals("comment", t.get("yoast-seo-premium")[1]);
        assertTrue(t.get("yoast-seo-premium")[3], t.get("yoast-seo-premium")[3].contains("Yoast SEO Premium plugin v21.7"));
        assertEquals("comment", t.get("wp-rocket")[1]);
        assertEquals("scriptcode", t.get("google-tag-manager")[1]);
        assertTrue(t.get("google-tag-manager")[3], t.get("google-tag-manager")[3].contains("gtm"));
        assertEquals("scriptcode", t.get("pardot")[1]);
        assertTrue(t.get("pardot")[3], t.get("pardot")[3].contains("piAId"));
        assertTrue("Elementor Pro by its own plugin path", t.get("elementor-pro")[3].contains("/plugins/elementor-pro/"));
        assertTrue("several markers counted", Integer.parseInt(t.get("google-tag-manager")[2]) >= 2);
        int bytes = 0, max = 0;
        for (final Map.Entry<String, String[]> e : t.entrySet()) {
            final int b = (e.getKey() + "|" + String.join("|", e.getValue())).getBytes(StandardCharsets.UTF_8).length;
            bytes += b;
            max = Math.max(max, b);
        }
        assertTrue("compact: " + bytes + " bytes for nine technologies", bytes < 2500 && max <= 260 + 40);
    }

    @Test
    public void namingAProductInTheTextLinksOrCodeShownAsTextIsNoTechnology() throws Exception {
        assertTrue(crawl("https://blog.example.de/cms-vergleich", fixture("mentions.html")).isEmpty());
    }

    @Test
    public void theBaseEditionIsNeverItsProOrPremiumEdition() throws Exception {
        final Map<String, String[]> t = crawl("https://www.tischlerei-muster.de/", fixture("basic.html"));
        assertEquals(new java.util.TreeSet<>(java.util.List.of("wordpress", "elementor", "yoast-seo")), t.keySet());
        assertTrue("an asset path with a version is a resource version, not the generator", t.get("elementor")[3].contains("ver=3.12.0"));
        assertEquals("comment", t.get("yoast-seo")[1]);
    }

    @Test
    public void googleAnalyticsThroughGtagIsNoTagManager() throws Exception {
        assertEquals(java.util.Set.of("google-analytics"), crawl("https://www.praxis-beispiel.de/", fixture("gtag.html")).keySet());
    }

    @Test
    public void theFieldIsWrittenOnlyWhereTheSchemaHasIt() throws Exception {
        final String text = new String(Files.readAllBytes(new File("defaults/solr.collection.schema").toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("\nwebsite_technology_sxt"));
        this.schema = schema(text.replace("\nwebsite_technology_sxt", "\n#website_technology_sxt"));
        this.segment.close();
        setUpWith(this.schema);
        assertTrue("switched off in the schema: nothing stored", crawl("https://www.musterberatung.de/", fixture("stack.html")).isEmpty());
    }

    private void setUpWith(final CollectionConfiguration s) throws Exception {
        this.segment = new Segment(new ConcurrentLog("WebsiteTechnologyIndexTest"), this.temporary.newFolder("segment2"),
                this.temporary.newFolder("archive2"), s, new WebgraphConfiguration(new File("defaults/solr.webgraph.schema"), true));
        final EmbeddedInstance embedded = new EmbeddedInstance(new File("defaults/solr"), this.temporary.newFolder("solr2"),
                CollectionSchema.CORE_NAME, new String[] {CollectionSchema.CORE_NAME});
        final Field mirror = Fulltext.class.getDeclaredField("solrInstances");
        mirror.setAccessible(true);
        ((InstanceMirror) mirror.get(this.segment.fulltext())).connectEmbedded(embedded);
        this.solr = this.segment.fulltext().getDefaultEmbeddedConnector();
    }
}
