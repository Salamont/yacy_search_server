package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * Isolated fixture of the LLM tier for the selection, budget and text tests: the embedded Solr core with the shipped
 * configuration, the real sync for tiers 1 and 2, and the controllable model of {@link LlmServiceTest}. Every test
 * runs on its own temporary data; nothing reaches a real model or a production graph.
 */
public abstract class LlmFixture {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    protected EmbeddedInstance solr;
    protected KgPaths paths;
    protected KgConfig cfg;
    protected StorageGuard guard;
    protected KgStore store;
    protected DirtySet dirty;
    protected SolrSource source;
    protected SyncService sync;
    protected LlmService llm;
    protected final LlmServiceTest.Fake model = newModel();
    protected final KgTestSupport.Probe storageProbe = new KgTestSupport.Probe();
    protected final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    static final String LD = LlmServiceTest.LD;
    static final String TEXT = LlmServiceTest.TEXT;

    /** The model of the test. */
    protected LlmServiceTest.Fake newModel() {
        return new LlmServiceTest.Fake();
    }

    /** The settings of the test (pairs of key and value), on top of an enabled graph. */
    protected String[] settings() {
        return new String[] {KgConfig.COLLECTIONS, "c1,c2", KgConfig.LLM_COLLECTIONS, "c1,c2", KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                "scoutro.kg.llm.kinds.c1", "nursinghome"};
    }

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1",
                new String[] {"collection1", "webgraph"});
        this.paths = new KgPaths(this.tmp.newFolder("data"));
        this.cfg = KgTestSupport.config(KgTestSupport.enabled(settings()));
        assertTrue(this.cfg.problems().toString(), this.cfg.valid());
        start();
    }

    protected void start() throws Exception {
        this.guard = new StorageGuard(this.cfg, this.paths, this.storageProbe, System::currentTimeMillis);
        this.store = KgStore.open(this.paths, this.cfg, this.guard, KgStore.SQLITE, System::currentTimeMillis);
        this.dirty = new DirtySet(1000);
        Capture.activate(this.dirty);
        this.source = new EmbeddedSolrSource(() -> this.solr.getDefaultServer());
        final Gates gates = new Gates(this.cfg, Gates.IDLE);
        this.sync = new SyncService(this.cfg, this.store, this.dirty, this.source, gates, this.clock::get, false);
        this.llm = new LlmService(this.cfg, this.store, this.source, gates, this.model, this.clock::get);
    }

    protected void restart() throws Exception {
        this.llm.requestStop();
        this.sync.requestStop();
        Capture.deactivate(this.dirty);
        this.sync.finalDrain(this.clock.get() + 2000L);
        this.store.close();
        start();
    }

    @After
    public void close() {
        if (this.dirty != null) {
            Capture.deactivate(this.dirty);
        }
        JsonLdCapture.clear();
        if (this.store != null) {
            this.store.close();
        }
        if (this.solr != null) {
            this.solr.close();
        }
    }

    // ------------------------------------------------------------ helpers

    protected SolrClient client() {
        return this.solr.getDefaultServer();
    }

    protected SolrInputDocument doc(final String id, final String url, final String collection, final String ld, final String text,
            final String title) {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", url);
        d.setField("host_s", java.net.URI.create(url).getHost());
        d.setField("host_id_s", id.substring(6));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", List.of(collection));
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        d.setField("title", List.of(title));
        d.setField("exact_signature_l", (long) text.hashCode());
        d.setField("text_t", text);
        if (ld != null) {
            d.setField("ld_json_txt", List.of(ld));
        }
        return d;
    }

    protected void add(final String id, final String url, final String collection, final String ld, final String text) throws Exception {
        add(id, url, collection, ld, text, "Impressum");
    }

    protected void add(final String id, final String url, final String collection, final String ld, final String text,
            final String title) throws Exception {
        client().add(doc(id, url, collection, ld, text, title));
        client().commit();
    }

    /** Many documents with one commit. */
    protected void addAll(final List<SolrInputDocument> docs) throws Exception {
        client().add(docs);
        client().commit();
    }

    /** An imprint page of its own host: a document ID of 12 characters whose last 6 are the host ID. */
    protected static String id(final char prefix, final int n) {
        return String.format("%c%05d%c%05d", prefix, n, Character.toLowerCase(prefix), n);
    }

    protected SolrInputDocument imprint(final char prefix, final int n, final String collection) {
        final String host = "www." + Character.toLowerCase(prefix) + n + "-pflege.de";
        return doc(id(prefix, n), "https://" + host + "/impressum", collection, LD.replace("muster-pflege", Character.toLowerCase(prefix) + n
                + "-pflege"), TEXT, "Impressum");
    }

    protected void settleSync() {
        for (int i = 0; i < 4000; i++) {
            final boolean more = this.sync.step();
            if (!more && this.dirty.size() == 0 && count("SELECT count(*) FROM kg_work") == 0L && !this.sync.reconciler().pending()) {
                return;
            }
            if (!more) {
                this.clock.addAndGet(3000L);
            }
        }
        fail("sync did not settle: " + this.sync.status());
    }

    protected void settleLlm() {
        settleLlm(() -> count("SELECT count(*) FROM kg_llm_work") == 0L
                && count("SELECT count(*) FROM kg_doc WHERE state = 1 AND llm_status IS NULL") == 0L);
    }

    protected void settleLlm(final BooleanSupplier done) {
        for (int i = 0; i < 2000; i++) {
            final boolean more = this.llm.step();
            if (!more && done.getAsBoolean()) {
                return;
            }
            if (!more) {
                this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
            }
        }
        fail("LLM tier did not settle: " + this.llm.status());
    }

    protected long count(final String sql) {
        try {
            return this.store.read(c -> KgStore.queryLong(c, sql));
        } catch (final KgException e) {
            throw new AssertionError(e);
        }
    }

    protected List<String> strings(final String sql) {
        try {
            return this.store.read(c -> {
                final List<String> out = new ArrayList<>();
                try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
                return out;
            });
        } catch (final KgException e) {
            throw new AssertionError(e);
        }
    }

    protected void exec(final String sql) {
        try {
            this.store.write(StorageGuard.WriteClass.MAINTENANCE, 1024L, tx -> {
                try (java.sql.Statement st = tx.createStatement()) {
                    st.executeUpdate(sql);
                }
                return null;
            });
        } catch (final KgException e) {
            throw new AssertionError(e);
        }
    }

    protected String llmStatus(final String id) {
        final List<String> s = strings("SELECT coalesce(llm_status, 'null') || ':' || coalesce(llm_reason, '') FROM kg_doc WHERE doc_id = '"
                + id + "'");
        return s.isEmpty() ? null : s.get(0);
    }

    protected boolean queued(final String id) {
        return count("SELECT count(*) FROM kg_llm_work WHERE doc_id = '" + id + "'") > 0L;
    }

    /** Pending documents neither marked nor queued: what the scan has to find again (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3). */
    protected long stranded() {
        return count("SELECT count(*) FROM kg_doc d WHERE d.state = 1 AND d.llm_status IS NULL AND d.input_hash IS NOT NULL"
                + " AND NOT EXISTS (SELECT 1 FROM kg_llm_work w WHERE w.doc_id = d.doc_id)");
    }
}
