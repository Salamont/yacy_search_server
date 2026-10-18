package net.yacy.scoutro.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import org.apache.solr.common.SolrDocument;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
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
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.Gates;
import net.yacy.scoutro.knowledge.sync.JsonLdCapture;
import net.yacy.search.index.Fulltext;
import net.yacy.search.index.Segment;
import net.yacy.search.schema.CollectionConfiguration;
import net.yacy.search.schema.CollectionSchema;
import net.yacy.search.schema.WebgraphConfiguration;

/**
 * Scoutro R5 end to end on synthetic pages: HTML through the real parser, the signatures and CollectionConfiguration into
 * the index, from there through the sync into the graph. The same page loaded again with the same visible text (equal
 * text signature) but another plugin's script is read again: the new plugin gets its statement, the earlier one is no
 * longer confirmed; a page that only names products gets none.
 */
public class WebsiteTechnologyPipelineTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private CollectionConfiguration schema;
    private Segment segment;
    private EmbeddedSolrConnector solr;
    private EmbeddedInstance embedded;
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);

    @Before
    public void setUp() throws Exception {
        JsonLdCapture.clear();
        Evaluation.add(new File("defaults/parser.tech.properties"));
        final File schemaFile = this.temporary.newFile("solr.collection.schema");
        Files.copy(new File("defaults/solr.collection.schema").toPath(), schemaFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        this.schema = new CollectionConfiguration(schemaFile, true);
        this.segment = new Segment(new ConcurrentLog("WebsiteTechnologyPipelineTest"), this.temporary.newFolder("segment"),
                this.temporary.newFolder("archive"), this.schema, new WebgraphConfiguration(new File("defaults/solr.webgraph.schema"), true));
        this.embedded = new EmbeddedInstance(new File("defaults/solr"), this.temporary.newFolder("solr"), CollectionSchema.CORE_NAME,
                new String[] {CollectionSchema.CORE_NAME, "webgraph"});
        final Field mirror = Fulltext.class.getDeclaredField("solrInstances");
        mirror.setAccessible(true);
        ((InstanceMirror) mirror.get(this.segment.fulltext())).connectEmbedded(this.embedded);
        this.solr = this.segment.fulltext().getDefaultEmbeddedConnector();
    }

    @After
    public void close() {
        KgRuntime.stop();
        JsonLdCapture.clear();
        if (this.segment != null) this.segment.close();
    }

    private KgRuntime start() {
        final Map<String, String> settings = KgTestSupport.enabled(KgConfig.COLLECTIONS, "web");
        KgRuntime.start(new KgRuntime.Env(this.temporary.getRoot().toPath().resolve("data").toFile(), settings::get, this.clock::get,
                new KgTestSupport.Probe(), KgStore.SQLITE, false, () -> this.embedded.getDefaultServer(), Gates.IDLE));
        final KgRuntime r = KgRuntime.current();
        assertNotNull(r);
        return r;
    }

    private static String fixture(final String name) throws Exception {
        return new String(Files.readAllBytes(new File("test/scoutro-technology/" + name).toPath()), StandardCharsets.UTF_8);
    }

    /** Parses and stores a page as a crawl does; returns its text signature. */
    private long crawl(final String url, final String html) throws Exception {
        final DigestURL u = new DigestURL(url);
        final Document document = new htmlParser().parse(u, "text/html", "UTF-8", new VocabularyScraper(), 0,
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)))[0];
        final Condenser condenser = new Condenser(document, new VocabularyScraper(), true, false, null, false, false, 0);
        final ResponseHeader header = new ResponseHeader(200);
        final Map<String, Pattern> collections = new LinkedHashMap<>();
        collections.put("web", Pattern.compile(".*"));
        final CollectionConfiguration.SolrVector vector = this.schema.yacy2solr(this.segment, collections, header, document, condenser, null,
                "de", false, null, "technology-pipeline");
        this.segment.storeDocument(u, null, header, document, vector, "de", condenser, null, "technology-pipeline", false);
        this.solr.commit(true);
        final SolrDocument d = this.solr.getDocumentById(net.yacy.cora.document.encoding.ASCII.String(u.hash()));
        assertNotNull(d);
        return ((Number) d.getFieldValue(CollectionSchema.exact_signature_l.getSolrFieldName())).longValue();
    }

    private void settle(final KgRuntime r) {
        final BooleanSupplier done = () -> {
            final JSONObject sync = r.status().optJSONObject("sync");
            final JSONObject lag = sync == null ? null : sync.optJSONObject("lag");
            return sync != null && sync.optBoolean("initialized") && lag != null && lag.optLong("pending", 1L) == 0L
                    && !lag.optBoolean("reconcile_pending", true) && count(r, "SELECT count(*) FROM kg_work") == 0L;
        };
        for (int i = 0; i < 2000; i++) {
            r.tick();
            r.syncTick();
            if (done.getAsBoolean()) return;
            this.clock.addAndGet(3000L);
        }
        fail("not reached: " + r.status());
    }

    private static long count(final KgRuntime r, final String sql) {
        try {
            return r.store().read(c -> KgStore.queryLong(c, sql));
        } catch (final KgException e) {
            throw new AssertionError(e);
        }
    }

    private static TreeSet<String> live(final KgRuntime r) throws Exception {
        return r.store().read(c -> {
            final TreeSet<String> out = new TreeSet<>();
            try (java.sql.ResultSet rs = c.createStatement().executeQuery("SELECT s.obj_val FROM kg_statement s JOIN kg_vocab v ON v.term_id=s.pred"
                    + " WHERE v.name='website_technology' AND EXISTS(SELECT 1 FROM kg_evidence e WHERE e.stmt_rowid=s.stmt_rowid)")) {
                while (rs.next()) out.add(new JSONObject(rs.getString(1)).getString("technology"));
            } catch (final org.json.JSONException e) {
                throw new java.sql.SQLException(e);
            }
            return out;
        });
    }

    @Test
    public void fromHtmlToTheGraphAndAChangedPluginWithTheSameText() throws Exception {
        final KgRuntime r = start();
        settle(r);
        final String html = fixture("stack.html");
        final long first = crawl("https://www.musterberatung.de/", html);
        settle(r);
        assertEquals(new TreeSet<>(java.util.List.of("elementor", "elementor-pro", "google-analytics", "google-tag-manager", "pardot",
                "wordpress", "wp-rocket", "yoast-seo", "yoast-seo-premium")), live(r));
        // the next load: the same visible text, Elementor Pro's assets replaced by WP Rocket's minified copies, no Rocket comment
        final String changed = html.replace("/wp-content/plugins/elementor-pro/", "/wp-content/cache/wp-rocket/min/")
                .replace("<!-- This website is like a Rocket, isn't it? Performance optimized by WP Rocket. Learn more: https://wp-rocket.me -->", "");
        this.clock.addAndGet(86_400_000L);
        final long second = crawl("https://www.musterberatung.de/", changed);
        assertEquals("the visible text is the same", first, second);
        settle(r);
        final TreeSet<String> now = live(r);
        assertTrue("Elementor Pro is no longer confirmed: " + now, !now.contains("elementor-pro"));
        assertTrue("WP Rocket now by its cache path: " + now, now.contains("wp-rocket"));
        assertTrue(r.store().read(c -> KgStore.queryString(c, "SELECT e.excerpt FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid=e.stmt_rowid"
                + " WHERE s.obj_val LIKE '%wp-rocket%'")).contains("/cache/wp-rocket/"));
        assertEquals("the earlier plugin stays in the history", 1L, count(r, "SELECT count(*) FROM kg_observation WHERE predicate='website_technology'"
                + " AND value LIKE '%elementor-pro%'"));
    }

    @Test
    public void aPageThatOnlyNamesProductsHasNoWebsiteTechnology() throws Exception {
        final KgRuntime r = start();
        settle(r);
        crawl("https://blog.example.de/cms-vergleich", fixture("mentions.html"));
        settle(r);
        assertTrue(count(r, "SELECT count(*) FROM kg_doc") >= 1L);
        assertTrue(live(r).isEmpty());
    }
}
