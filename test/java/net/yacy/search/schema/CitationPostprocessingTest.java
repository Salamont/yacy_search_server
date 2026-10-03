package net.yacy.search.schema;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.connector.AbstractSolrConnector;
import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.cora.federate.solr.instance.InstanceMirror;
import net.yacy.cora.protocol.ResponseHeader;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.document.Condenser;
import net.yacy.document.Document;
import net.yacy.document.VocabularyScraper;
import net.yacy.document.parser.htmlParser;
import net.yacy.search.index.Fulltext;
import net.yacy.search.index.Segment;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real parser, Citation index and embedded Solr; no HTTP requests or production DATA. */
public class CitationPostprocessingTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private CollectionConfiguration schema;
    private File schemaFile;
    private File segmentPath;
    private File archivePath;
    private File solrPath;
    private Segment segment;
    private EmbeddedInstance embedded;
    private EmbeddedSolrConnector solr;

    @Before
    public void setUp() throws Exception {
        schemaFile = temporary.newFile("solr.collection.schema");
        Files.copy(new File("defaults/solr.collection.schema").toPath(), schemaFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        schema = new CollectionConfiguration(schemaFile, true);
        // A references-only fixture must not depend on CitationRank or Duplicate processing.
        for (final CollectionSchema field : new CollectionSchema[] {
                CollectionSchema.exact_signature_unique_b, CollectionSchema.exact_signature_copycount_i,
                CollectionSchema.fuzzy_signature_l, CollectionSchema.fuzzy_signature_copycount_i,
                CollectionSchema.http_unique_b, CollectionSchema.www_unique_b}) {
            schema.get(field.name()).setEnable(false);
        }
        schema.commit();
        segmentPath = temporary.newFolder("segment");
        archivePath = temporary.newFolder("archive");
        solrPath = temporary.newFolder("solr");
        createSegment();
    }

    private void createSegment() throws Exception {
        segment = new Segment(new ConcurrentLog("CitationPostprocessingTest"), segmentPath, archivePath, schema,
                new WebgraphConfiguration(new File("defaults/solr.webgraph.schema"), true));
        embedded = new EmbeddedInstance(new File("defaults/solr"), solrPath,
                CollectionSchema.CORE_NAME, new String[] {CollectionSchema.CORE_NAME});
        // Install a temporary embedded instance without booting Switchboard or its crawler threads.
        final Field mirror = Fulltext.class.getDeclaredField("solrInstances");
        mirror.setAccessible(true);
        ((InstanceMirror) mirror.get(segment.fulltext())).connectEmbedded(embedded);
        solr = segment.fulltext().getDefaultEmbeddedConnector();
        assertFalse(segment.fulltext().useWebgraph());
        assertFalse(embedded.getCoreNames().contains(WebgraphSchema.CORE_NAME));
    }

    @After
    public void close() {
        if (segment != null) segment.close();
    }

    @Test
    public void disabledCitationDoesNotActivateSchemaOrScheduleReferences() throws Exception {
        final byte[] before = Files.readAllBytes(schemaFile.toPath());
        assertFalse(schema.contains(CollectionSchema.process_sxt));
        final SolrInputDocument doc = index("https://host-a.example/page", null, true, "one");
        assertNull(doc.getFieldValue("process_sxt"));
        assertArrayEquals(before, Files.readAllBytes(schemaFile.toPath()));
        assertFalse(segment.connectedCitation());
        assertFalse(segment.fulltext().useWebgraph());
    }

    @Test
    public void citationActivationRepairsMissingMarkerAndKeepsOptionalFieldsDisabled() throws Exception {
        schema.remove(CollectionSchema.process_sxt.name());
        schema.commit();
        final Set<String> before = enabledFields(schema);
        segment.connectCitation(100, 1024 * 1024);
        final Set<String> after = enabledFields(schema);
        after.removeAll(before);
        assertEquals(new HashSet<>(Arrays.asList("process_sxt")), after);
        assertTrue(new CollectionConfiguration(schemaFile, true).contains(CollectionSchema.process_sxt));
        assertFalse(schema.contains(CollectionSchema.cr_host_norm_i));
        assertFalse(schema.contains(CollectionSchema.canonical_s));
        assertFalse(segment.fulltext().useWebgraph());
        final byte[] once = Files.readAllBytes(schemaFile.toPath());
        segment.connectCitation(100, 1024 * 1024);
        assertArrayEquals(once, Files.readAllBytes(schemaFile.toPath()));
    }

    @Test
    public void citationRepairsSixFieldsAlongsideFourAlreadyMandatoryLinkInputs() throws Exception {
        final Set<String> required = new HashSet<>(Arrays.asList("process_sxt", "references_i",
                "references_internal_i", "references_external_i", "references_exthosts_i", "host_extent_i",
                "inboundlinks_protocol_sxt", "inboundlinks_urlstub_sxt",
                "outboundlinks_protocol_sxt", "outboundlinks_urlstub_sxt"));
        for (final String field : required) schema.get(field).setEnable(false);
        schema.commit();
        final Set<String> before = enabledFields(schema);
        segment.connectCitation(100, 1024 * 1024);
        final Set<String> added = enabledFields(schema);
        added.removeAll(before);
        final Set<String> missing = new HashSet<>(required);
        missing.removeAll(before);
        assertEquals(missing, added);
        final CollectionConfiguration persisted = new CollectionConfiguration(schemaFile, true);
        assertTrue(enabledFields(persisted).containsAll(required));
        assertFalse(persisted.contains(CollectionSchema.cr_host_norm_i));
        assertFalse(persisted.contains(CollectionSchema.canonical_s));
    }

    @Test
    public void schemaWriteFailureDoesNotConnectCitationAndCanBeRetried() throws Exception {
        final byte[] before = Files.readAllBytes(schemaFile.toPath());
        Files.delete(schemaFile.toPath());
        Files.createDirectory(schemaFile.toPath());
        try {
            segment.connectCitation(100, 1024 * 1024);
            fail("A failed schema write must be reported");
        } catch (final java.io.IOException expected) {
            assertFalse(segment.connectedCitation());
            assertFalse(schema.contains(CollectionSchema.process_sxt));
        }
        Files.delete(schemaFile.toPath());
        Files.write(schemaFile.toPath(), before);
        segment.connectCitation(100, 1024 * 1024);
        assertTrue(segment.connectedCitation());
        assertTrue(new CollectionConfiguration(schemaFile, true).contains(CollectionSchema.process_sxt));
    }

    @Test
    public void pendingMarkersAndCitationSurviveRestartAndFinish() throws Exception {
        segment.connectCitation(100, 1024 * 1024);
        final String target = "https://host-a.example/restart-target";
        index(target, null, false, "one");
        index("https://host-a.example/restart-source", target, true, "one", "two");
        solr.commit(true);
        assertEquals(2, solr.getCountByQuery("process_sxt:[* TO *]"));
        segment.close();
        schema = new CollectionConfiguration(schemaFile, true);
        createSegment();
        final byte[] persisted = Files.readAllBytes(schemaFile.toPath());
        segment.connectCitation(100, 1024 * 1024);
        assertArrayEquals(persisted, Files.readAllBytes(schemaFile.toPath()));
        assertEquals(2, solr.getCountByQuery("process_sxt:[* TO *]"));
        assertEquals(2, schema.postprocessing(segment, segment.getReferenceReportCache(), null, true));
        solr.commit(true);
        assertEquals(1, number(document(target), "references_internal_i"));
        assertEquals(2, number(document(target), "host_extent_i"));
        assertEquals(0, solr.getCountByQuery("process_sxt:[* TO *]"));
        assertFalse(segment.fulltext().useWebgraph());
    }

    @Test
    public void countFetchIdsAndPrefetchAgreeForCollectionAndEmptyBucket() throws Exception {
        segment.connectCitation(100, 1024 * 1024);
        index("https://host-a.example/one", null, false, "one");
        index("https://host-a.example/shared", null, false, "one", "two");
        index("https://host-a.example/timed", null, true, "one");
        index("https://host-b.example/private", null, false, "two");
        solr.commit(true);
        assertSameDocuments("{!cache=false}process_sxt:[* TO *] AND collection_sxt:one", 3);
        assertSameDocuments(" \t{!cache=false}process_sxt:[* TO *] AND collection_sxt:two", 2);
        assertSameDocuments("{!cache=false}-responsetime_i:[* TO *] AND process_sxt:[* TO *] AND collection_sxt:one", 2);
        assertSameDocuments("{!cache=false}responsetime_i:10 AND process_sxt:[* TO *] AND collection_sxt:one", 1);
    }

    @Test
    public void ordinaryMultiwordSearchAndCollectionFiltersStillWork() throws Exception {
        segment.connectCitation(100, 1024 * 1024);
        index("https://host-a.example/search-one", null, true, "one");
        index("https://host-a.example/search-shared", null, false, "one", "two");
        index("https://host-b.example/search-other", null, false, "two");
        solr.commit(true);
        assertEquals(3, solr.getDocumentListByQuery("foo bar", null, 0, 20, "id").size());
        assertEquals(2, solr.getDocumentListByQuery("foo bar AND collection_sxt:one", null, 0, 20, "id").size());
        assertEquals(2, solr.getDocumentListByQuery("foo bar AND host_s:host-a.example", null, 0, 20, "id").size());
    }

    @Test
    public void multipleExternalSourceUrlsCountOneExternalHostAndAreNotOutgoingLinks() throws Exception {
        segment.connectCitation(100, 1024 * 1024);
        final String target = "https://host-a.example/no-outgoing-links";
        index(target, null, false, "one");
        index("https://host-b.example/source1", target, true, "one");
        index("https://host-b.example/source2", target, false, "two");
        solr.commit(true);
        assertEquals(3, schema.postprocessing(segment, segment.getReferenceReportCache(), null, true));
        solr.commit(true);
        final SolrDocument result = document(target);
        assertEquals(2, number(result, "references_i"));
        assertEquals(0, number(result, "references_internal_i"));
        assertEquals(2, number(result, "references_external_i"));
        assertEquals(1, number(result, "references_exthosts_i"));
        assertEquals(0, number(result, "inboundlinkscount_i"));
        assertEquals(0, number(result, "outboundlinkscount_i"));
    }

    @Test
    public void referencesAndExtentFinalizeWithoutWebgraphOrResponseTime() throws Exception {
        segment.connectCitation(100, 1024 * 1024);
        final String target = "https://host-a.example/page2";
        index(target, null, false, "one", "two");
        index("https://host-a.example/page1", target, true, "one");
        index("https://host-a.example/page3", target, false, "two");
        index("https://host-b.example/page1", target, false, "one");
        index("https://host-c.example/page1", target, true, "two");
        solr.commit(true);
        assertEquals(5, solr.getCountByQuery("process_sxt:[* TO *]"));
        assertEquals(-1, number(document(target), "host_extent_i"));
        assertTrue(segment.citationCount() > 0);

        assertEquals(5, schema.postprocessing(segment, segment.getReferenceReportCache(), null, true));
        solr.commit(true);
        assertEquals(0, solr.getCountByQuery("process_sxt:[* TO *]"));
        assertEquals(0, solr.getCountByQuery("host_extent_i:-1"));
        final SolrDocument result = document(target);
        assertEquals(4, number(result, "references_i"));
        assertEquals(2, number(result, "references_internal_i"));
        assertEquals(2, number(result, "references_external_i"));
        assertEquals(2, number(result, "references_exthosts_i"));
        assertEquals(3, number(result, "host_extent_i"));
        assertEquals(new HashSet<>(Arrays.asList("one", "two")), new HashSet<>(result.getFieldValues("collection_sxt")));
        assertEquals(5, solr.getCountByQuery("*:*"));
        assertFalse(segment.fulltext().useWebgraph());
        assertFalse(schema.contains(CollectionSchema.cr_host_norm_i));
        assertEquals(0, schema.postprocessing(segment, segment.getReferenceReportCache(), null, true));
    }

    @Test
    public void citationHostIdentityIncludesWwwSubdomainProtocolAndPort() throws Exception {
        segment.connectCitation(100, 1024 * 1024);
        final String target = "https://host-a.example/target";
        index(target, null, false, "one");
        index("https://host-a.example/internal", target, false, "one");
        for (final String source : new String[] {"https://www.host-a.example/page", "https://docs.host-a.example/page",
                "http://host-a.example/page", "https://host-a.example:8443/page"}) {
            assertNotEquals(new DigestURL(target).hosthash(), new DigestURL(source).hosthash());
            index(source, target, true, "one");
        }
        solr.commit(true);
        assertEquals(6, schema.postprocessing(segment, segment.getReferenceReportCache(), null, false));
        solr.commit(true);
        final SolrDocument result = document(target);
        assertEquals(5, number(result, "references_i"));
        assertEquals(1, number(result, "references_internal_i"));
        assertEquals(4, number(result, "references_external_i"));
        assertEquals(4, number(result, "references_exthosts_i"));
        assertEquals(2, number(result, "host_extent_i"));
        assertEquals(0, solr.getCountByQuery("process_sxt:[* TO *]"));
    }

    private SolrInputDocument index(final String source, final String target, final boolean responseTime,
            final String... collections) throws Exception {
        final DigestURL url = new DigestURL(source);
        final String html = "<html><head><title>Citation fixture</title></head><body>foo bar fixture "
                + (target == null ? "" : "<a href=\"" + target + "\">target page</a>") + "</body></html>";
        final Document document = new htmlParser().parse(url, "text/html", "UTF-8", new VocabularyScraper(), 0,
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)))[0];
        final Condenser condenser = new Condenser(document, new VocabularyScraper(), true, false, null, false, false, 0);
        final ResponseHeader header = new ResponseHeader(200);
        final Map<String, Pattern> collectionMap = new HashMap<>();
        for (final String collection : collections) collectionMap.put(collection, Pattern.compile(".*"));
        final CollectionConfiguration.SolrVector vector = schema.yacy2solr(segment, collectionMap, header,
                document, condenser, null, "en", false, null, "citation-fixture");
        if (responseTime) vector.setField("responsetime_i", 10);
        else vector.removeField("responsetime_i");
        return segment.storeDocument(url, null, header, document, vector, "en", condenser, null, "citation-fixture", false);
    }

    private SolrDocument document(final String url) throws Exception {
        return solr.getDocumentById(net.yacy.cora.document.encoding.ASCII.String(new DigestURL(url).hash()));
    }

    private static int number(final SolrDocument doc, final String field) {
        assertNotNull(field, doc.getFieldValue(field));
        return ((Number) doc.getFieldValue(field)).intValue();
    }

    private static Set<String> enabledFields(final CollectionConfiguration configuration) {
        final Set<String> fields = new HashSet<>();
        for (final CollectionSchema field : CollectionSchema.values()) {
            if (configuration.contains(field)) fields.add(field.name());
        }
        return fields;
    }

    private void assertSameDocuments(final String query, final int expected) throws Exception {
        assertEquals(expected, solr.getCountByQuery(query));
        final Set<String> ids = new HashSet<>();
        for (final SolrDocument doc : solr.getDocumentListByQuery(query, "id asc", 0, 20, "id")) {
            ids.add((String) doc.getFieldValue("id"));
        }
        assertEquals(expected, ids.size());
        final BlockingQueue<String> pendingIds = solr.concurrentIDsByQueries(Arrays.asList(query), "id asc", 0,
                20, 10000, 10, 1);
        final Set<String> streamed = new HashSet<>();
        while (true) {
            final String id = pendingIds.poll(10, TimeUnit.SECONDS);
            assertNotNull("ID stream did not terminate", id);
            if (id == AbstractSolrConnector.POISON_ID) break;
            streamed.add(id);
        }
        assertEquals(ids, streamed);
        final BlockingQueue<SolrDocument> prefetched = solr.concurrentDocumentsByQueries(Arrays.asList(query),
                "id asc", 0, 20, 10000, 10, 1, true, "id", "collection_sxt");
        final Set<String> fetched = new HashSet<>();
        while (true) {
            final SolrDocument doc = prefetched.poll(10, TimeUnit.SECONDS);
            assertNotNull("Document stream did not terminate", doc);
            if (doc == AbstractSolrConnector.POISON_DOCUMENT) break;
            fetched.add((String) doc.getFieldValue("id"));
        }
        assertEquals(ids, fetched);
    }
}
