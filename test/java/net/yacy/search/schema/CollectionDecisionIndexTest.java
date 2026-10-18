package net.yacy.search.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.FailType;
import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.cora.federate.solr.instance.InstanceMirror;
import net.yacy.cora.protocol.ResponseHeader;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.document.Condenser;
import net.yacy.document.Document;
import net.yacy.document.VocabularyScraper;
import net.yacy.document.parser.htmlParser;
import net.yacy.scoutro.collections.CollectionDecision;
import net.yacy.scoutro.collections.CollectionDecisions;
import net.yacy.scoutro.collections.DecisionStore;
import net.yacy.search.index.Fulltext;
import net.yacy.search.index.Segment;

/**
 * The indexer itself (real parser, yacy2solr, embedded Solr) follows the durable collection decisions: a recrawl
 * through the old crawl profile never restores a corrected membership, error documents follow them too, the fallback
 * collection takes no new page without a decision, and with unreadable decisions nothing is written.
 */
public class CollectionDecisionIndexTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private CollectionConfiguration schema;
    private Segment segment;
    private EmbeddedSolrConnector solr;
    private Path dir;
    private Path marker;
    private CollectionDecisions decisions;

    @Before
    public void setUp() throws Exception {
        final File schemaFile = this.temporary.newFile("solr.collection.schema");
        Files.copy(new File("defaults/solr.collection.schema").toPath(), schemaFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        this.schema = new CollectionConfiguration(schemaFile, true);
        this.segment = new Segment(new ConcurrentLog("CollectionDecisionIndexTest"), this.temporary.newFolder("segment"),
                this.temporary.newFolder("archive"), this.schema, new WebgraphConfiguration(new File("defaults/solr.webgraph.schema"), true));
        final EmbeddedInstance embedded = new EmbeddedInstance(new File("defaults/solr"), this.temporary.newFolder("solr"),
                CollectionSchema.CORE_NAME, new String[] {CollectionSchema.CORE_NAME});
        final Field mirror = Fulltext.class.getDeclaredField("solrInstances");
        mirror.setAccessible(true);
        ((InstanceMirror) mirror.get(this.segment.fulltext())).connectEmbedded(embedded);
        this.solr = this.segment.fulltext().getDefaultEmbeddedConnector();
        this.dir = this.temporary.newFolder("collections").toPath();
        this.marker = this.temporary.newFolder("settings").toPath().resolve("marker");
        this.decisions = new CollectionDecisions(new DecisionStore(this.dir, this.marker), "sonstige", false, System::currentTimeMillis);
        CollectionDecisions.install(this.decisions);
    }

    @After
    public void close() {
        CollectionDecisions.install(null);
        if (this.segment != null) this.segment.close();
    }

    /** Parses and stores a page with the crawl profile's collections, as a crawl does. */
    private void crawl(final String url, final String... profile) throws Exception {
        final DigestURL u = new DigestURL(url);
        final String html = "<html><head><title>Drolshagens Nuss-Spezialitaeten</title></head><body>Nuss Spezialitaeten Popcorn</body></html>";
        final Document document = new htmlParser().parse(u, "text/html", "UTF-8", new VocabularyScraper(), 0,
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)))[0];
        final Condenser condenser = new Condenser(document, new VocabularyScraper(), true, false, null, false, false, 0);
        final ResponseHeader header = new ResponseHeader(200);
        final Map<String, Pattern> collections = new LinkedHashMap<>();
        for (final String c : profile) collections.put(c, Pattern.compile(".*"));
        final CollectionConfiguration.SolrVector vector = this.schema.yacy2solr(this.segment, collections, header, document, condenser, null,
                "de", false, null, "decision-fixture");
        this.segment.storeDocument(u, null, header, document, vector, "de", condenser, null, "decision-fixture", false);
        this.solr.commit(true);
    }

    private List<String> collections(final String url) throws Exception {
        final SolrDocument d = this.solr.getDocumentById(net.yacy.cora.document.encoding.ASCII.String(new DigestURL(url).hash()));
        assertNotNull(url, d);
        final Collection<Object> v = d.getFieldValues(CollectionSchema.collection_sxt.getSolrFieldName());
        final List<String> out = new ArrayList<>();
        if (v != null) for (final Object o : v) out.add(o.toString());
        return new ArrayList<>(new TreeSet<>(out));
    }

    private void decide(final String domain, final CollectionDecision.Mode mode, final List<String> collections, final List<String> denied)
            throws Exception {
        final CollectionDecision.Builder b = new CollectionDecision.Builder();
        b.id = CollectionDecisions.newId();
        b.domain = domain;
        b.mode = mode;
        b.collections = collections;
        b.denied = denied;
        b.reason = "fachlich_falsch";
        b.decidedAt = 1L;
        this.decisions.put(b.build(), "decide");
    }

    @Test
    public void recrawlThroughTheWrongProfileKeepsTheCorrection() throws Exception {
        crawl("https://drolshagens.de/", "bauteamcheck-web");
        assertEquals(List.of("bauteamcheck-web"), collections("https://drolshagens.de/"));
        decide("drolshagens.de", CollectionDecision.Mode.EXACT, List.of("sonstige"), List.of());
        // the next Discovery recrawl, a WorkTables replay or YaCy's recrawl job: all write through yacy2solr
        crawl("https://drolshagens.de/", "bauteamcheck-web");
        assertEquals(List.of("sonstige"), collections("https://drolshagens.de/"));
        crawl("https://drolshagens.de/products/drolshagens-nuss-spezialitaten", "bauteamcheck-web");
        assertEquals(List.of("sonstige"), collections("https://drolshagens.de/products/drolshagens-nuss-spezialitaten"));
        // another domain is untouched
        crawl("https://bau-firma.de/", "bauteamcheck-web");
        assertEquals(List.of("bauteamcheck-web"), collections("https://bau-firma.de/"));
    }

    @Test
    public void decidedMultipleAssignmentSurvivesASingleCollectionCrawl() throws Exception {
        decide("multi.de", CollectionDecision.Mode.TRANSFORM, List.of("edelsenior-web"), List.of());
        crawl("https://multi.de/", "stackfinder-web");
        assertEquals(List.of("edelsenior-web", "stackfinder-web"), collections("https://multi.de/"));
    }

    @Test
    public void errorDocumentsFollowTheDecision() throws Exception {
        decide("drolshagens.de", CollectionDecision.Mode.EXACT, List.of("sonstige"), List.of());
        final Map<String, Pattern> profile = new LinkedHashMap<>();
        profile.put("bauteamcheck-web", Pattern.compile(".*"));
        final CollectionConfiguration.FailDoc fail = new CollectionConfiguration.FailDoc(new DigestURL("https://drolshagens.de/gone"), profile,
                "TEMPORARY_NETWORK_FAILURE test", FailType.fail, 404, 0);
        final SolrInputDocument doc = fail.toSolr(this.schema);
        assertEquals(List.of("sonstige"), new ArrayList<>(doc.getFieldValues(CollectionSchema.collection_sxt.getSolrFieldName())).stream()
                .map(Object::toString).toList());
    }

    @Test
    public void fallbackTakesNoNewPageWithoutADecision() throws Exception {
        try {
            crawl("https://unbekannt.de/", "sonstige");
            fail("a new candidate entered the fallback collection");
        } catch (final CollectionDecisions.Blocked expected) {
            assertEquals("fallback_without_decision", expected.code);
        }
        assertEquals(0, this.solr.getCountByQuery("host_s:\"unbekannt.de\""));
    }

    @Test
    public void unreadableDecisionsLeaveTheIndexAsItIs() throws Exception {
        crawl("https://drolshagens.de/", "bauteamcheck-web");
        decide("drolshagens.de", CollectionDecision.Mode.EXACT, List.of("sonstige"), List.of());
        crawl("https://drolshagens.de/", "bauteamcheck-web");
        assertEquals(List.of("sonstige"), collections("https://drolshagens.de/"));
        // the decision files are lost (the marker in SETTINGS says they existed); the peer restarts
        try (java.util.stream.Stream<Path> files = Files.list(this.dir)) {
            files.forEach(p -> p.toFile().delete());
        }
        CollectionDecisions.install(new CollectionDecisions(new DecisionStore(this.dir, this.marker), "sonstige", false, System::currentTimeMillis));
        try {
            crawl("https://drolshagens.de/", "bauteamcheck-web");
            fail("written without reliable decisions");
        } catch (final CollectionDecisions.Blocked expected) {
            assertEquals("decisions_unavailable", expected.code);
        }
        // the corrected membership is still in the index, never silently restored to the wrong collection
        assertEquals(List.of("sonstige"), collections("https://drolshagens.de/"));
    }
}
