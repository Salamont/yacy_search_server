package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * WP-F identity changes on a synchronised graph: off by default nothing changes; the preview of a domain shows the
 * merge of a product with its product page and the blocked merge of another product that reuses the address, and writes
 * nothing; switched on, every page is extracted again once and the product and its page are one entity, the other
 * product stays apart.
 */
public class IdentityChangesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EmbeddedInstance solr;
    private KgPaths paths;
    private KgConfig cfg;
    private KgStore store;
    private DirtySet dirty;
    private SyncService sync;
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

    private static final String HOME = "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"Muster Shop GmbH\","
            + "\"url\":\"https://www.muster-shop.de/\",\"makesOffer\":{\"@type\":\"Offer\",\"price\":\"199\",\"priceCurrency\":\"EUR\","
            + "\"itemOffered\":{\"@type\":\"Product\",\"name\":\"Gartenbank Eiche\",\"url\":\"https://www.muster-shop.de/produkte/bank/?utm_source=home\"}}}";
    private static final String PRODUCT = "{\"@context\":\"https://schema.org\",\"@type\":\"Product\",\"name\":\"Gartenbank Eiche\","
            + "\"url\":\"https://muster-shop.de/produkte/bank\",\"offers\":{\"@type\":\"Offer\",\"price\":\"199\",\"priceCurrency\":\"EUR\"}}";
    /** Another product whose page reuses the bench's address: the address alone must not merge them. */
    private static final String TABLE = "{\"@context\":\"https://schema.org\",\"@type\":\"Product\",\"name\":\"Gartentisch\","
            + "\"url\":\"https://www.muster-shop.de/produkte/bank\",\"offers\":{\"@type\":\"Offer\",\"price\":\"349\",\"priceCurrency\":\"EUR\"}}";

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1",
                new String[] {"collection1", "webgraph"});
        this.paths = new KgPaths(this.tmp.newFolder("data"));
        start(Map.of());
    }

    private void start(final Map<String, String> extra) throws Exception {
        final java.util.Map<String, String> settings = new java.util.HashMap<>(KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1"));
        settings.putAll(extra);
        this.cfg = KgTestSupport.config(settings);
        assertTrue(this.cfg.problems().toString(), this.cfg.valid());
        final StorageGuard guard = new StorageGuard(this.cfg, this.paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(this.paths, this.cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        this.dirty = new DirtySet(1000);
        Capture.activate(this.dirty);
        this.sync = new SyncService(this.cfg, this.store, this.dirty, new EmbeddedSolrSource(() -> this.solr.getDefaultServer()),
                new Gates(this.cfg, Gates.IDLE), this.clock::get, false);
    }

    private void stop() {
        Capture.deactivate(this.dirty);
        this.sync.finalDrain(this.clock.get() + 2000L);
        this.store.close();
        this.store = null;
    }

    @After
    public void close() {
        Capture.deactivate(this.dirty);
        JsonLdCapture.clear();
        if (this.store != null) {
            this.store.close();
        }
        if (this.solr != null) {
            this.solr.close();
        }
    }

    private SolrClient client() {
        return this.solr.getDefaultServer();
    }

    private void add(final String id, final String url, final String ldJson) throws Exception {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", url);
        d.setField("host_s", java.net.URI.create(url).getHost());
        d.setField("host_id_s", id.substring(6));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", List.of("c1"));
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        d.setField("title", List.of("Seite " + id));
        d.setField("ld_json_txt", List.of(ldJson));
        client().add(d);
    }

    private void corpus() throws Exception {
        Capture.deactivate(this.dirty);
        add("HOME01host01", "https://www.muster-shop.de/", HOME);
        add("PROD01host01", "https://www.muster-shop.de/produkte/bank", PRODUCT);
        add("TABL01host01", "https://www.muster-shop.de/produkte/tisch", TABLE);
        client().commit();
        Capture.activate(this.dirty);
    }

    private void settle() {
        for (int i = 0; i < 2000; i++) {
            final boolean more = this.sync.step();
            if (!more && this.dirty.size() == 0 && count("SELECT count(*) FROM kg_work") == 0L && !this.sync.reconciler().pending()) {
                return;
            }
            if (!more) {
                this.clock.addAndGet(3000L);
            }
        }
        fail("did not settle: " + this.sync.status());
    }

    private long count(final String sql) {
        try {
            return this.store.read(c -> KgStore.queryLong(c, sql));
        } catch (final KgException e) {
            throw new AssertionError(e);
        }
    }

    /** Active service entities with this name. */
    private long services(final String name) {
        return count("SELECT count(DISTINCT e.ent_rowid) FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type JOIN kg_statement s"
                + " ON s.subj = e.ent_rowid JOIN kg_vocab p ON p.term_id = s.pred WHERE t.name = 'service' AND e.status = 1"
                + " AND p.name = 'name' AND s.obj_val = '" + name + "'");
    }

    private long productUrlKeys() {
        return count("SELECT count(*) FROM kg_entity_key k JOIN kg_vocab v ON v.term_id = k.scheme WHERE v.name = 'product_url'");
    }

    /** Every row of the graph's tables, in order: equal before and after means nothing was written. */
    private String dump() throws Exception {
        return this.store.read(c -> {
            final StringBuilder b = new StringBuilder();
            for (final String t : new String[] {"kg_entity", "kg_entity_key", "kg_statement", "kg_evidence", "kg_doc", "kg_event",
                    "kg_observation", "kg_vocab", "kg_collection", "kg_meta", "kg_change"}) {
                try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery("SELECT * FROM " + t)) {
                    final int n = rs.getMetaData().getColumnCount();
                    final List<String> rows = new java.util.ArrayList<>();
                    while (rs.next()) {
                        final StringBuilder r = new StringBuilder();
                        for (int i = 1; i <= n; i++) {
                            final Object o = rs.getObject(i);
                            r.append(o instanceof byte[] ? java.util.HexFormat.of().formatHex((byte[]) o) : String.valueOf(o)).append('|');
                        }
                        rows.add(r.toString());
                    }
                    java.util.Collections.sort(rows);
                    b.append(t).append(':').append(rows).append('\n');
                }
            }
            return b.toString();
        });
    }

    @Test
    public void offByDefaultTheProductAndItsPageStayTwoEntities() throws Exception {
        assertFalse(this.cfg.identityProductUrl);
        assertFalse(this.cfg.identityProviderServiceKey);
        corpus();
        settle();
        assertEquals("the duplicate of the audit (two entities of one product)", 2L, services("Gartenbank Eiche"));
        assertEquals(1L, services("Gartentisch"));
        assertEquals(0L, productUrlKeys());
    }

    @Test
    public void thePreviewShowsTheMergeAndTheBlockedMergeAndWritesNothing() throws Exception {
        corpus();
        settle();
        final String before = dump();
        final JSONObject p = this.sync.identityPreview("muster-shop.de", true, false, 200);
        assertEquals(p.toString(), 3, p.optJSONObject("sample").optInt("documents"));
        assertEquals(p.toString(), 3, p.optJSONObject("baseline").optInt("services"));
        assertEquals(p.toString(), 2, p.optJSONObject("preview").optInt("services"));
        assertEquals(p.toString(), 1, p.optJSONArray("merges").length());
        assertTrue(p.toString(), p.optJSONArray("conflicts").toString().contains("product page of another name"));
        assertTrue(p.toString(), p.optInt("documentsChanged") >= 1);
        assertFalse(p.optBoolean("written"));
        assertTrue("no LLM estimate without providerServiceKey", p.isNull("llm"));
        assertEquals("the preview wrote nothing", before, dump());
        // and the graph goes on as before
        assertEquals(2L, services("Gartenbank Eiche"));
        assertEquals(0L, productUrlKeys());
    }

    @Test
    public void switchedOnEveryPageIsReadAgainOnceAndTheProductJoinsItsPage() throws Exception {
        corpus();
        settle();
        final long extractions = count("SELECT count(*) FROM kg_event");
        stop();
        start(Map.of(KgConfig.IDENTITY_PRODUCT_URL, "true"));
        settle();
        assertEquals("the product and its page are one entity", 1L, services("Gartenbank Eiche"));
        assertEquals("another name stays apart", 1L, services("Gartentisch"));
        assertTrue(productUrlKeys() >= 1L);
        assertTrue(count("SELECT count(*) FROM kg_event WHERE code = 'identity_conflict' AND detail LIKE 'product page of another name%'") >= 1L);
        assertTrue(count("SELECT count(*) FROM kg_event") > extractions);
        final String meta = this.store.read(c -> KgStore.getMeta(c, KgSchema.META_EXTRACTORS));
        assertTrue(meta, meta.contains(Integer.toHexString(this.cfg.extractionKey().hashCode())));
        // switched off again: the merged entity stays (no split), nothing breaks on the next reading
        stop();
        start(Map.of());
        settle();
        assertEquals(1L, services("Gartenbank Eiche"));
        assertEquals(1L, services("Gartentisch"));
    }
}
