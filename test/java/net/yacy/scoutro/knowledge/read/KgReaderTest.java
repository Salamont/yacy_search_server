package net.yacy.scoutro.knowledge.read;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The read projection and its collection isolation (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 4.5): one entity seen in collections A and B, one only in B. A viewer of A
 * never gets a name, value, identifier, count, host, evidence row, source or
 * search hit that only B's documents carry.
 */
public class KgReaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;
    private KgConfig cfg;
    private Publisher publisher;
    private KgReader reader;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private long version = 1000L;

    static final String ORG_A = "{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\",\"url\":\"https://www.muster.de/\","
            + "\"telephone\":\"030 1234567\"}";
    static final String ORG_B = "{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\",\"url\":\"https://www.muster.de/\","
            + "\"telephone\":\"040 9999999\",\"alternateName\":\"Geheime Holding\",\"vatID\":\"DE123456789\"}";
    static final String ONLY_B = "{\"@type\":\"Organization\",\"name\":\"Nur Bee GmbH\",\"url\":\"https://www.nur-bee.de/\","
            + "\"telephone\":\"089 7777777\"}";

    @Before
    public void open() throws Exception {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(this.cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, this.cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(this.cfg, terms);
        this.reader = new KgReader(this.store, this.cfg, this.now::get);
        publish("AAAAAAhost01", "https://www.muster.de/", "ca", ORG_A);
        publish("BBBBBBhost01", "https://www.muster.de/impressum", "cb", ORG_B);
        publish("CCCCCChost02", "https://www.nur-bee.de/", "cb", ONLY_B);
    }

    @After
    public void close() {
        this.store.close();
    }

    private void publish(final String id, final String url, final String collection, final String block) throws Exception {
        publish(id, url, List.of(collection), block);
    }

    private void publish(final String id, final String url, final List<String> collections, final String block) throws Exception {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = net.yacy.cora.document.id.DigestURL.hosthash(d.host, 443);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = collections;
        d.solrVersion = ++this.version;
        d.loadedAt = this.now.get();
        final Extraction ex = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of(block), d.url, d.host, d.language, ex);
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, id));
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now.get()));
    }

    private Viewer viewer(final String... collections) throws Exception {
        return this.reader.viewer(collections.length == 0 ? null : List.of(collections));
    }

    private JSONObject list(final Viewer v, final String q) throws Exception {
        final KgReader.EntityQuery eq = new KgReader.EntityQuery();
        eq.q = q;
        return this.reader.entities(eq, v);
    }

    private static List<String> values(final JSONObject page) throws Exception {
        final List<String> out = new ArrayList<>();
        final JSONArray items = page.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            final JSONObject o = items.getJSONObject(i).getJSONObject("object");
            out.add(items.getJSONObject(i).getString("predicate") + "=" + o.optString("value", o.optString("name")));
        }
        return out;
    }

    private String entityId(final Viewer v, final String q) throws Exception {
        return list(v, q).getJSONArray("items").getJSONObject(0).getString("id");
    }

    private String statementId(final String value) throws Exception {
        return this.store.read(c -> KgStore.queryString(c, "SELECT public_id FROM kg_statement WHERE obj_val = '" + value + "'"));
    }

    @Test
    public void theAdministratorSeesEverything() throws Exception {
        final Viewer all = viewer();
        assertEquals(2L, list(all, null).getLong("total"));
        final String id = entityId(all, "Muster");
        final JSONObject e = this.reader.entity(id, all);
        assertEquals("Muster Pflege gGmbH", e.getString("name"));
        assertEquals("supported", e.getString("quality"));
        assertTrue(e.getJSONArray("aliases").toString().contains("Geheime Holding"));
        assertEquals("vat", e.getJSONArray("identifiers").getJSONObject(0).getString("scheme"));
        assertEquals(2, e.getJSONObject("counts").getInt("sources"));
        final List<String> facts = values(this.reader.entityStatements(id, null, false, false, 0, 100, all));
        assertTrue(facts.toString(), facts.contains("phone=+49301234567") && facts.contains("phone=+49409999999"));
        assertEquals(1L, list(all, "geheime").getLong("total"));
        assertEquals(2L, this.reader.evidence(statementId("Muster Pflege gGmbH"), 0, 50, all).getLong("total"));
    }

    @Test
    public void aViewerOfOneCollectionSeesNothingOfTheOther() throws Exception {
        final Viewer a = viewer("ca");
        final JSONObject page = list(a, null);
        assertEquals("only the shared entity, counted without B", 1L, page.getLong("total"));
        final String id = page.getJSONArray("items").getJSONObject(0).getString("id");
        final JSONObject e = this.reader.entity(id, a);
        assertEquals("Muster Pflege gGmbH", e.getString("name"));
        assertFalse("B's alias", e.toString().contains("Geheime"));
        assertEquals("B's VAT ID", 0, e.getJSONArray("identifiers").length());
        assertEquals("B's source", 1, e.getJSONObject("counts").getInt("sources"));
        assertEquals("[\"www.muster.de\"]", e.getJSONArray("hosts").toString());
        final List<String> facts = values(this.reader.entityStatements(id, null, false, false, 0, 100, a));
        assertTrue(facts.contains("phone=+49301234567"));
        assertFalse("B's phone", facts.toString().contains("+49409999999"));
        assertFalse(facts.toString().contains("DE123456789"));
        assertEquals("search finds no name only B carries", 0L, list(a, "geheime").getLong("total"));
        assertEquals(0L, list(a, "Nur Bee").getLong("total"));
        // evidence and sources of B are invisible
        final JSONObject ev = this.reader.evidence(statementId("Muster Pflege gGmbH"), 0, 50, a);
        assertEquals(1L, ev.getLong("total"));
        assertEquals("[\"ca\"]", ev.getJSONArray("items").getJSONObject(0).getJSONArray("collections").toString());
        notFound(() -> this.reader.statement(statementId("+49409999999"), a));
        notFound(() -> this.reader.evidence(statementId("+49409999999"), 0, 10, a));
        notFound(() -> this.reader.source("BBBBBBhost01", 0, 10, a));
        final String onlyB = entityId(viewer("cb"), "Nur Bee");
        notFound(() -> this.reader.entity(onlyB, a));
        notFound(() -> this.reader.entityStatements(onlyB, null, false, false, 0, 10, a));
        assertEquals(0L, this.reader.hostEntities("www.nur-bee.de", 0, 25, a).getLong("total"));
        assertEquals(1L, this.reader.hostEntities("www.muster.de", 0, 25, a).getLong("total"));
        // the source A holds shows A's evidence only
        final JSONObject src = this.reader.source("AAAAAAhost01", 0, 100, a);
        assertEquals("[\"ca\"]", src.getJSONObject("source").getJSONArray("collections").toString());
        assertFalse(src.toString().contains("+49409999999"));
    }

    @Test
    public void aSharedDocumentListsOnlyTheViewersCollections() throws Exception {
        publish("DDDDDDhost03", "https://www.zwei.de/", List.of("ca", "cgeheim"),
                "{\"@type\":\"Organization\",\"name\":\"Zwei GmbH\",\"url\":\"https://www.zwei.de/\"}");
        final Viewer a = viewer("ca");
        final JSONObject ev = this.reader.evidence(statementId("Zwei GmbH"), 0, 10, a);
        assertEquals("[\"ca\"]", ev.getJSONArray("items").getJSONObject(0).getJSONArray("collections").toString());
        final JSONObject src = this.reader.source("DDDDDDhost03", 0, 10, a);
        assertEquals("[\"ca\"]", src.getJSONObject("source").getJSONArray("collections").toString());
        assertFalse(src.toString().contains("cgeheim"));
        assertTrue(this.reader.source("DDDDDDhost03", 0, 10, viewer()).toString().contains("cgeheim"));
    }

    @Test
    public void anUnknownCollectionSeesNothing() throws Exception {
        final Viewer none = viewer("zz");
        assertEquals(0L, list(none, null).getLong("total"));
        final String id = entityId(viewer(), "Muster");
        notFound(() -> this.reader.entity(id, none));
        notFound(() -> this.reader.source("AAAAAAhost01", 0, 10, none));
    }

    @Test
    public void qualityAndDatesAreComputedPerViewer() throws Exception {
        // A's only source is gone: for A the facts are stale, for the administrator B still supports the name
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> this.publisher.setState(tx,
                List.of(KgStore.queryLong(tx, "SELECT doc_rowid FROM kg_doc WHERE doc_id = 'AAAAAAhost01'")), 3, this.now.get()));
        final Viewer a = viewer("ca");
        final String id = entityId(viewer(), "Muster");
        assertEquals("stale", this.reader.entity(id, a).getString("quality"));
        assertEquals("supported", this.reader.entity(id, viewer()).getString("quality"));
        assertEquals("stale facts are hidden unless asked for", 0L, this.reader.entityStatements(id, null, false, false, 0, 100, a)
                .getLong("total"));
        assertTrue(this.reader.entityStatements(id, null, false, true, 0, 100, a).getLong("total") > 0L);
        final KgReader.EntityQuery stale = new KgReader.EntityQuery();
        stale.quality = "stale";
        assertEquals(1L, this.reader.entities(stale, a).getLong("total"));
        assertEquals(0L, this.reader.entities(stale, viewer()).getLong("total"));
        final KgReader.EntityQuery supported = new KgReader.EntityQuery();
        supported.quality = "supported";
        assertEquals(0L, this.reader.entities(supported, a).getLong("total"));
        assertEquals(2L, this.reader.entities(supported, viewer()).getLong("total"));
    }

    @Test
    public void filtersAndPaging() throws Exception {
        final Viewer all = viewer();
        final KgReader.EntityQuery q = new KgReader.EntityQuery();
        q.type = "facility";
        assertEquals(0L, this.reader.entities(q, all).getLong("total"));
        q.type = "organization";
        q.limit = 1;
        final JSONObject p = this.reader.entities(q, all);
        assertEquals(2L, p.getLong("total"));
        assertEquals(1, p.getJSONArray("items").length());
        assertEquals(KgReader.SCHEMA, p.getString("schema"));
        assertTrue(p.getJSONObject("as_of").getLong("seq") > 0L);
        assertEquals("prefix search", 1L, list(all, "Must").getLong("total"));
        assertEquals("punctuation only", 0L, list(all, "\"*()").getLong("total"));
        final String id = entityId(all, "Muster");
        final JSONObject phones = this.reader.entityStatements(id, "phone", false, false, 0, 100, all);
        assertEquals(2L, phones.getLong("total"));
        final JSONObject in = this.reader.entityStatements(id, null, true, false, 0, 100, all);
        assertEquals("no relation points to it", 0L, in.getLong("total"));
        notFound(() -> this.reader.entity("kge_" + "a".repeat(20), all));
    }

    interface Call {
        Object run() throws Exception;
    }

    private static void notFound(final Call c) throws Exception {
        try {
            c.run();
            fail("visible");
        } catch (final KgReader.NotFound e) {
            // expected
        }
    }
}
