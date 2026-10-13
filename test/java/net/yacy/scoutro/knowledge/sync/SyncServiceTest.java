package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.store.KgChangeLog;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The synchronisation against the embedded Solr core with the shipped
 * configuration (release checks 1, 4 to 7 and 9 of the plan): backfill,
 * events, recrawl and delete during processing, crash and restart, overflow,
 * delete by query, full clear, aborted reconcile, mass-deletion brake and
 * storage limits. The clock is controlled by the test; Solr runs for real.
 */
public class SyncServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EmbeddedInstance solr;
    private KgPaths paths;
    private KgConfig cfg;
    private KgTestSupport.Probe probe;
    private StorageGuard guard;
    private KgStore store;
    private DirtySet dirty;
    private Hooked source;
    private SyncService sync;
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

    /** Solr source with hooks: an action right after a real-time get, failing or partial scans. */
    static final class Hooked implements SolrSource {
        final SolrSource inner;
        volatile Consumer<Collection<String>> afterGet;
        final AtomicInteger scanCalls = new AtomicInteger();
        volatile int failScanAt = -1;
        volatile int partialScanAt = -1;
        /** A page whose extraction fails with a runtime error (a defect of the extraction), and how often it did. */
        volatile String failText;
        final AtomicInteger textFailures = new AtomicInteger();
        /** Called with the number of texts read so far, after each one (an interruption in the middle of a batch). */
        volatile java.util.function.IntConsumer afterText;
        final AtomicInteger texts = new AtomicInteger();

        Hooked(final SolrSource inner) {
            this.inner = inner;
        }

        @Override
        public Map<String, SolrDoc> get(final Collection<String> ids, final List<String> fields) throws IOException {
            final Map<String, SolrDoc> r = this.inner.get(ids, fields);
            final Consumer<Collection<String>> hook = this.afterGet;
            if (hook != null) {
                this.afterGet = null;
                hook.accept(ids);
            }
            return r;
        }

        @Override
        public String text(final String id) throws IOException {
            if (id.equals(this.failText)) {
                this.textFailures.incrementAndGet();
                throw new IllegalStateException("injected extraction defect");
            }
            final String text = this.inner.text(id);
            final java.util.function.IntConsumer hook = this.afterText;
            if (hook != null) {
                hook.accept(this.texts.incrementAndGet());
            }
            return text;
        }

        @Override
        public Page scan(final String after, final int rows, final Collection<String> collections, final long minVersion)
                throws IOException {
            final int n = this.scanCalls.incrementAndGet();
            if (n == this.failScanAt) {
                throw new IOException("injected scan failure");
            }
            final Page p = this.inner.scan(after, rows, collections, minVersion);
            return n == this.partialScanAt ? new Page(p.docs, true) : p;
        }
    }

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1",
                new String[] {"collection1", "webgraph"});
        this.paths = new KgPaths(this.tmp.newFolder("data"));
        this.probe = new KgTestSupport.Probe();
        configure(KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3"));
        start(1000, false);
    }

    private void configure(final Map<String, String> settings) {
        this.cfg = KgTestSupport.config(settings);
        assertTrue(this.cfg.problems().toString(), this.cfg.valid());
    }

    /** Opens the store and a new sync over it, with a new change set (a new runtime). */
    private void start(final int capacity, final boolean unclean) throws Exception {
        this.guard = new StorageGuard(this.cfg, this.paths, this.probe, System::currentTimeMillis);
        this.store = KgStore.open(this.paths, this.cfg, this.guard, KgStore.SQLITE, System::currentTimeMillis);
        this.dirty = new DirtySet(capacity);
        Capture.activate(this.dirty);
        this.source = new Hooked(new EmbeddedSolrSource(() -> this.solr.getDefaultServer()));
        this.sync = new SyncService(this.cfg, this.store, this.dirty, this.source, new Gates(this.cfg, Gates.IDLE), this.clock::get, unclean);
        this.sync.reconciler().page = 3;
    }

    /** Ends the runtime; {@code clean} drains what the capture recorded, a crash loses it. */
    private void stop(final boolean clean) {
        Capture.deactivate(this.dirty);
        if (clean) {
            this.sync.finalDrain(this.clock.get() + 2000L);
        }
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

    // ------------------------------------------------------------ helpers

    private SolrClient client() {
        return this.solr.getDefaultServer();
    }

    private static String org(final String name, final String phone) {
        return "{\"@type\":\"Organization\",\"name\":\"" + name + "\",\"url\":\"https://www.muster.de/\",\"telephone\":\"" + phone + "\"}";
    }

    private SolrInputDocument doc(final String id, final String url, final String collection, final String ldJson) {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", url);
        d.setField("host_s", java.net.URI.create(url).getHost());
        d.setField("host_id_s", id.substring(6));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", List.of(collection));
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        d.setField("title", List.of("Seite " + id));
        if (ldJson != null) {
            d.setField("ld_json_txt", List.of(ldJson));
        }
        return d;
    }

    private SolrInputDocument doc(final String id, final String url, final List<String> collections, final String ldJson) {
        final SolrInputDocument d = doc(id, url, collections.get(0), ldJson);
        d.setField("collection_sxt", collections);
        return d;
    }

    private void add(final String id, final String url, final String ldJson) throws Exception {
        client().add(doc(id, url, "c1", ldJson));
    }

    private void commit() throws Exception {
        client().commit();
    }

    /** Steps until nothing is left: change set, queue and reconcile. */
    private void settle() {
        settle(() -> this.dirty.size() == 0 && queue() == 0L && !this.sync.reconciler().pending());
    }

    private void settle(final BooleanSupplier done) {
        for (int i = 0; i < 2000; i++) {
            final boolean more = this.sync.step();
            if (!more && done.getAsBoolean()) {
                return;
            }
            if (!more) {
                this.clock.addAndGet(3000L);
            }
        }
        fail("did not settle: " + this.sync.status());
    }

    private long queue() {
        return count("SELECT count(*) FROM kg_work");
    }

    private long count(final String sql) {
        try {
            return this.store.read(c -> KgStore.queryLong(c, sql));
        } catch (final KgException e) {
            throw new AssertionError(e);
        }
    }

    private List<String> strings(final String sql) throws Exception {
        return this.store.read(c -> {
            final List<String> out = new ArrayList<>();
            try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    private List<String> phones() throws Exception {
        return strings("SELECT s.obj_val FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'phone' ORDER BY 1");
    }

    private boolean tracked(final String id) {
        return count("SELECT count(*) FROM kg_doc WHERE doc_id = '" + id + "'") > 0L;
    }

    private void withoutCapture(final SolrAction a) throws Exception {
        Capture.deactivate(this.dirty);
        try {
            a.run();
        } finally {
            Capture.activate(this.dirty);
        }
    }

    interface SolrAction {
        void run() throws Exception;
    }

    // -------------------------------------------------------------- tests

    @Test
    public void backfillAndEventsPublishOnlyFollowedCollectionsAndReprocessingIsIdempotent() throws Exception {
        withoutCapture(() -> {
            add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 1234567"));
            client().add(doc("BBBBBBhost02", "https://other.example/", "c2", org("Andere GmbH", "040 1111111")));
            commit();
        });
        settle();
        assertTrue("the start reconcile on an empty graph is the backfill", tracked("AAAAAAhost01"));
        assertFalse("collection c2 is not followed", tracked("BBBBBBhost02"));
        assertEquals(List.of("+49301234567"), phones());
        final List<String> ids = strings("SELECT public_id FROM kg_statement ORDER BY 1");
        assertEquals(1L, count("SELECT count(*) FROM kg_entity"));
        // the same document indexed again (a recrawl without change): nothing new
        add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 1234567"));
        settle();
        assertEquals(ids, strings("SELECT public_id FROM kg_statement ORDER BY 1"));
        assertTrue(this.sync.counters.unchanged.get() + this.sync.counters.lifecycle.get() >= 1L);
        // no graph state is written to Solr: the Solr document carries exactly the fields it was indexed with
        final org.apache.solr.common.SolrDocument d = client().getById("AAAAAAhost01");
        for (final String f : d.getFieldNames()) {
            assertFalse("graph state in Solr: " + f, f.startsWith("kg_"));
        }
    }

    /**
     * 0.10.2 in production: the extraction of a page failed with a runtime error (a range error of the job titles); the step
     * ended, the page stayed claimed at the head of the queue, every step took it again and nothing behind it was published.
     * Such a failure now counts against the page alone: retried with backoff, dropped after {@link SyncService#MAX_FAILURES}
     * with an event, read again with its next change.
     */
    @Test
    public void aPageWhoseExtractionFailsDoesNotHoldUpTheQueue() throws Exception {
        settle();
        this.source.failText = "AAAAAAhost01";
        add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 1111111"));
        add("BBBBBBhost02", "https://www.beispiel.de/", org("Beispiel Technik GmbH", "040 2222222"));
        commit();
        settle();
        assertTrue("the page behind it is published", tracked("BBBBBBhost02"));
        assertEquals(List.of("+49402222222"), phones());
        assertFalse(tracked("AAAAAAhost01"));
        assertEquals("tried again, then given up", SyncService.MAX_FAILURES, this.source.textFailures.get());
        assertEquals(1L, this.sync.counters.failedDocs.get());
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'doc_failed' AND detail = 'AAAAAAhost01: extract_failed'"));
        assertEquals(0L, queue());
        // the defect is gone: the next change of the page is read
        this.source.failText = null;
        add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 1111111"));
        commit();
        settle();
        assertTrue(tracked("AAAAAAhost01"));
        assertEquals(List.of("+49301111111", "+49402222222"), phones());
    }

    @Test
    public void recrawlDuringProcessingPublishesNothingStale() throws Exception {
        add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 1111111"));
        settle();
        assertEquals(List.of("+49301111111"), phones());
        // the page changes, and changes again right after the sync read it
        this.source.afterGet = ids -> {
            try {
                add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 3333333"));
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        };
        add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 2222222"));
        settle();
        assertNull(this.source.afterGet);
        assertTrue("the publish of the older read was refused", this.sync.counters.supersededAborts.get() >= 1L);
        assertEquals("only the newest state is published, the intermediate one never", List.of("+49303333333"), phones());
    }

    @Test
    public void deleteDuringProcessingLeavesNoGhost() throws Exception {
        add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 1111111"));
        settle();
        final Consumer<Collection<String>> deleteIt = ids -> {
            try {
                client().deleteById(new ArrayList<>(ids));
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        };
        // a tracked document changes and is deleted right after the read
        this.source.afterGet = deleteIt;
        add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 2222222"));
        settle();
        assertFalse(tracked("AAAAAAhost01"));
        assertEquals(0L, count("SELECT count(*) FROM kg_statement"));
        assertEquals(0L, count("SELECT count(*) FROM kg_entity"));
        // a new document deleted right after the read is never published
        this.source.afterGet = deleteIt;
        add("CCCCCChost01", "https://www.muster.de/neu", org("Neu GmbH", "030 4444444"));
        settle();
        assertFalse(tracked("CCCCCChost01"));
        assertEquals(0L, count("SELECT count(*) FROM kg_statement"));
        assertTrue(this.sync.counters.supersededAborts.get() >= 2L);
    }

    @Test
    public void deletionAndReactivationFollowTheEvents() throws Exception {
        add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 1111111"));
        settle();
        client().deleteById("AAAAAAhost01");
        settle();
        assertFalse(tracked("AAAAAAhost01"));
        add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 1111111"));
        settle();
        assertTrue("a re-added document is restored", tracked("AAAAAAhost01"));
        assertEquals(List.of("+49301111111"), phones());
    }

    @Test
    public void failDocumentsFollowTheStateRules() throws Exception {
        add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 1111111"));
        settle();
        final SolrInputDocument fail = doc("AAAAAAhost01", "https://www.muster.de/", "c1", null);
        fail.setField("httpstatus_i", 503);
        fail.setField("failtype_s", "fail");
        client().add(fail);
        settle();
        assertEquals("a temporary failure keeps the evidence, uncertain", 2L,
                count("SELECT state FROM kg_doc WHERE doc_id = 'AAAAAAhost01'"));
        assertEquals(1L, count("SELECT count(DISTINCT quality) FROM kg_statement"));
        assertEquals(2L, count("SELECT max(quality) FROM kg_statement"));
        fail.setField("httpstatus_i", 404);
        client().add(fail);
        settle();
        assertEquals("404 is gone", 3L, count("SELECT state FROM kg_doc WHERE doc_id = 'AAAAAAhost01'"));
        assertEquals(4L, count("SELECT max(quality) FROM kg_statement"));
        // the same content again: active, and no new extraction is needed
        final long published = this.sync.counters.published.get();
        add("AAAAAAhost01", "https://www.muster.de/", org("Muster Pflege gGmbH", "030 1111111"));
        settle();
        assertEquals(1L, count("SELECT state FROM kg_doc WHERE doc_id = 'AAAAAAhost01'"));
        assertEquals(1L, count("SELECT max(quality) FROM kg_statement"));
        assertEquals(published, this.sync.counters.published.get());
        // a fail document of a URL the graph never saw active is not tracked
        final SolrInputDocument other = doc("DDDDDDhost01", "https://www.muster.de/x", "c1", null);
        other.setField("httpstatus_i", 500);
        other.setField("failtype_s", "fail");
        client().add(other);
        settle();
        assertFalse(tracked("DDDDDDhost01"));
    }

    @Test
    public void crashBetweenSolrChangeAndDrainIsRepairedAtTheNextStart() throws Exception {
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        add("ZZZZZZhost01", "https://www.muster.de/z", org("Z GmbH", "030 9999999"));
        commit();
        settle();
        // the version checkpoint: a sample at least 30 s before a complete drain
        this.clock.addAndGet(40_000L);
        this.sync.step();
        this.clock.addAndGet(40_000L);
        this.sync.step();
        final String checkpoint = this.store.read(c -> KgStore.getMeta(c, KgSchema.META_VERSION_CHECKPOINT));
        assertNotNull(checkpoint);
        // Solr changes, the capture records it, and the process dies before the drain
        add("YYYYYYhost01", "https://www.muster.de/y", org("Y GmbH", "030 5555555"));
        client().deleteById("ZZZZZZhost01");
        commit();
        assertEquals(2, this.dirty.size());
        stop(false);
        start(1000, true);
        settle();
        assertTrue("the add is repaired", tracked("YYYYYYhost01"));
        assertFalse("the delete is repaired, after a verified lookup", tracked("ZZZZZZhost01"));
        final JSONObject reconcile = this.sync.status().getJSONObject("reconcile");
        assertTrue("recent changes were enqueued first by version", reconcile.getJSONObject("catchUp").getLong("enqueued") >= 1L);
        assertEquals("unclean_start", strings("SELECT reason FROM kg_scan ORDER BY run_id DESC LIMIT 1").get(0));
    }

    @Test
    public void everyStartReconcilesAlsoAfterACleanStop() throws Exception {
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        add("BBBBBBhost01", "https://www.muster.de/b", org("B GmbH", "030 2222222"));
        add("ZZZZZZhost01", "https://www.muster.de/z", org("Z GmbH", "030 9999999"));
        commit();
        settle();
        // recorded but not yet drained: the clean stop persists it
        add("CCCCCChost01", "https://www.muster.de/c", org("C GmbH", "030 3333333"));
        assertEquals(1, this.dirty.size());
        stop(true);
        assertEquals("pending changes reach the persistent queue at the stop", 1L,
                countIn("SELECT count(*) FROM kg_work WHERE doc_id = 'CCCCCChost01'"));
        // while the graph is stopped or disabled, Solr changes without any event
        add("WWWWWWhost01", "https://www.muster.de/w", org("W GmbH", "030 7777777"));
        add("BBBBBBhost01", "https://www.muster.de/b", org("B GmbH", "030 8888888"));
        client().deleteById("ZZZZZZhost01");
        commit();
        start(1000, false);
        settle();
        assertTrue(tracked("CCCCCChost01"));
        assertTrue("added while stopped", tracked("WWWWWWhost01"));
        assertFalse("deleted while stopped", tracked("ZZZZZZhost01"));
        assertTrue("changed while stopped", phones().contains("+49308888888"));
        assertFalse(phones().contains("+49302222222"));
        assertEquals("start", strings("SELECT reason FROM kg_scan ORDER BY run_id DESC LIMIT 1").get(0));
    }

    /** A count with a store opened only for this read. */
    private long countIn(final String sql) throws Exception {
        final KgStore s = KgStore.open(this.paths, this.cfg, new StorageGuard(this.cfg, this.paths, this.probe, System::currentTimeMillis),
                KgStore.SQLITE, System::currentTimeMillis);
        try {
            return s.read(c -> KgStore.queryLong(c, sql));
        } finally {
            s.close();
        }
    }

    @Test
    public void abortedReconcileDeletesNothingAndResumesFromItsCursor() throws Exception {
        for (final String id : new String[] {"AAAAAAhost01", "BBBBBBhost01", "CCCCCChost01", "DDDDDDhost01", "EEEEEEhost01",
                "FFFFFFhost01", "GGGGGGhost01", "HHHHHHhost01"}) {
            add(id, "https://www.muster.de/" + id, org(id + " GmbH", "030 1111111"));
        }
        commit();
        settle();
        assertEquals(8L, count("SELECT count(*) FROM kg_doc"));
        withoutCapture(() -> {
            client().deleteById(List.of("BBBBBBhost01", "GGGGGGhost01"));
            commit();
        });
        // the second page fails: the run ends as aborted and deletes nothing
        this.source.scanCalls.set(0);
        this.source.failScanAt = 2;
        this.sync.requestReconcile(Reconciler.REASON_ADMIN);
        for (int i = 0; i < 50 && (this.sync.reconciler().current() == null
                || this.sync.reconciler().current().state != Reconciler.STATE_ABORTED); i++) {
            this.sync.step();
        }
        Reconciler.Run run = this.sync.reconciler().current();
        assertNotNull(run);
        assertEquals(Reconciler.STATE_ABORTED, run.state);
        final String cursor = run.cursor;
        assertNotNull("the first page was scanned", cursor);
        assertTrue(tracked("BBBBBBhost01"));
        assertTrue(tracked("GGGGGGhost01"));
        assertEquals(3L, count("SELECT state FROM kg_scan WHERE run_id = " + run.id));
        // a partial page aborts the same way
        this.clock.addAndGet(61_000L);
        this.source.partialScanAt = this.source.scanCalls.get() + 1;
        this.sync.step();
        run = this.sync.reconciler().current();
        assertEquals(Reconciler.STATE_ABORTED, run.state);
        assertTrue(run.detail.contains("partial"));
        assertEquals("resumed from the cursor, not from the start", cursor, run.cursor);
        assertTrue(tracked("GGGGGGhost01"));
        // Solr answers again: the run resumes and completes with verified deletions
        this.clock.addAndGet(31L * 60_000L);
        settle();
        assertFalse(tracked("BBBBBBhost01"));
        assertFalse(tracked("GGGGGGhost01"));
        assertEquals(6L, count("SELECT count(*) FROM kg_doc"));
        assertEquals(Reconciler.STATE_COMPLETED, count("SELECT state FROM kg_scan WHERE run_id = " + run.id));
        assertEquals(0L, count("SELECT count(*) FROM kg_scan_candidate"));
    }

    @Test
    public void massDeletionBrakeStopsUntilConfirmed() throws Exception {
        for (int i = 0; i < 10; i++) {
            final String id = "AAAAA" + i + "host01";
            add(id, "https://www.muster.de/" + i, org("Firma " + i + " GmbH", "030 100000" + i));
        }
        commit();
        settle();
        assertEquals(10L, count("SELECT count(*) FROM kg_doc"));
        withoutCapture(() -> {
            for (int i = 0; i < 5; i++) {
                client().deleteById("AAAAA" + i + "host01");
            }
            commit();
        });
        this.sync.requestReconcile(Reconciler.REASON_ADMIN);
        settle(() -> this.sync.reconciler().current() != null && this.sync.reconciler().current().state == Reconciler.STATE_SUSPECT);
        assertEquals("nothing is deleted above the brake", 10L, count("SELECT count(*) FROM kg_doc"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'reconcile_suspect'"));
        assertTrue(this.sync.status().getJSONObject("reconcile").getBoolean("awaitingConfirmation"));
        try {
            new SyncService(this.cfg, this.store, new DirtySet(10), this.source, new Gates(this.cfg, Gates.IDLE), this.clock::get, false)
                    .confirmReconcile();
            fail("nothing to confirm without a stopped run");
        } catch (final KgException e) {
            assertEquals(KgException.NOTHING_TO_CONFIRM, e.code());
        }
        this.sync.confirmReconcile();
        settle();
        assertEquals(5L, count("SELECT count(*) FROM kg_doc"));
    }

    @Test
    public void emptySolrAgainstATrackedGraphNeedsConfirmation() throws Exception {
        configure(KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1")); // default brake minimum: 50 documents
        stop(true);
        start(1000, false);
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        add("BBBBBBhost01", "https://www.muster.de/b", org("B GmbH", "030 2222222"));
        commit();
        settle();
        withoutCapture(() -> {
            client().deleteByQuery("*:*");
            commit();
        });
        this.sync.requestReconcile(Reconciler.REASON_ADMIN);
        settle(() -> this.sync.reconciler().current() != null && this.sync.reconciler().current().state == Reconciler.STATE_SUSPECT);
        assertEquals(2L, count("SELECT count(*) FROM kg_doc"));
        assertTrue(this.sync.reconciler().current().detail.contains("no document"));
    }

    @Test
    public void overflowOfTheChangeSetSchedulesAReconcile() throws Exception {
        stop(true);
        start(2, false);
        settle();
        for (int i = 0; i < 5; i++) {
            add("AAAAA" + i + "host01", "https://www.muster.de/" + i, org("Firma " + i + " GmbH", "030 100000" + i));
        }
        commit();
        assertTrue(this.dirty.overflowed());
        this.sync.step();
        assertEquals(Reconciler.REASON_OVERFLOW, this.sync.status().getJSONObject("reconcile").optString("reason"));
        settle();
        assertEquals(5L, count("SELECT count(*) FROM kg_doc"));
    }

    @Test
    public void fullQueueDropsNewIdsAndTheReconcileCatchesUp() throws Exception {
        settle();
        this.sync.queueMax(2);
        for (int i = 0; i < 5; i++) {
            add("AAAAA" + i + "host01", "https://www.muster.de/" + i, org("Firma " + i + " GmbH", "030 100000" + i));
        }
        commit();
        settle(() -> this.dirty.size() == 0 && queue() == 0L && this.sync.counters.queueDropped.get() > 0L
                && count("SELECT count(*) FROM kg_doc") == 5L);
        assertEquals(5L, count("SELECT count(*) FROM kg_doc"));
        assertTrue(this.sync.counters.queueDropped.get() >= 3L);
    }

    @Test
    public void aReconcileThatTheFullQueuePostponedRunsOnceTheQueueHasRoom() throws Exception {
        settle();
        this.sync.queueMax(2);
        // during a pause the queue fills, the reconcile it triggers finds no room either
        this.guard.setManualPause(true);
        for (int i = 0; i < 5; i++) {
            add("AAAAA" + i + "host01", "https://www.muster.de/" + i, org("Firma " + i + " GmbH", "030 100000" + i));
        }
        commit();
        settle(() -> this.dirty.size() == 0 && !this.sync.reconciler().pending() || this.sync.reconciler().waitsForQueueRoom());
        assertTrue("the reconcile could not enqueue everything", this.sync.reconciler().waitsForQueueRoom());
        this.guard.setManualPause(false);
        final long resumed = this.clock.get();
        settle(() -> this.dirty.size() == 0 && queue() == 0L && count("SELECT count(*) FROM kg_doc") == 5L);
        assertTrue("within minutes after the queue drained, not after the hour of the retry: " + (this.clock.get() - resumed),
                this.clock.get() - resumed < 10L * 60_000L);
    }

    @Test
    public void changesLostBeforeASearchCanSeeThemComeWithALaterReconcile() throws Exception {
        settle();
        this.sync.queueMax(2);
        // not committed yet: the real-time get and the capture see them, a scan does not
        for (int i = 0; i < 5; i++) {
            add("AAAAA" + i + "host01", "https://www.muster.de/" + i, org("Firma " + i + " GmbH", "030 100000" + i));
        }
        settle(() -> {
            final JSONObject rec = this.sync.status().optJSONObject("reconcile");
            final JSONObject last = rec == null ? null : rec.optJSONObject("last");
            return this.dirty.size() == 0 && queue() == 0L && this.sync.reconciler().current() == null && last != null
                    && Reconciler.REASON_QUEUE_FULL.equals(last.optString("reason"));
        });
        assertTrue("the queue dropped changes", this.sync.counters.queueDropped.get() >= 3L);
        assertTrue("the reconcile could not see them", count("SELECT count(*) FROM kg_doc") < 5L);
        commit();
        final long visible = this.clock.get();
        settle(() -> this.dirty.size() == 0 && queue() == 0L && count("SELECT count(*) FROM kg_doc") == 5L);
        assertTrue("a reconcile once they are visible, not the daily one: " + (this.clock.get() - visible),
                this.clock.get() - visible < Reconciler.VISIBLE_AFTER_MILLIS + 10L * 60_000L);
    }

    @Test
    public void deleteByQueryIsDebouncedAndVerified() throws Exception {
        add("AAAAAAhost01", "https://a.example/", org("A GmbH", "030 1111111"));
        add("BBBBBBhost02", "https://b.example/", org("B GmbH", "030 2222222"));
        commit();
        settle();
        client().deleteByQuery("host_s:a.example");
        commit();
        this.sync.step();
        final JSONObject r = this.sync.status().getJSONObject("reconcile");
        assertEquals(Reconciler.REASON_QUERY_DELETE, r.getString("reason"));
        assertTrue(r.getLong("dueAt") >= this.clock.get() + 299_000L);
        this.sync.step();
        assertTrue("not before the debounce", tracked("AAAAAAhost01"));
        this.clock.addAndGet(301_000L);
        settle();
        assertFalse(tracked("AAAAAAhost01"));
        assertTrue(tracked("BBBBBBhost02"));
    }

    @Test
    public void fullClearResetsTheGraphWithANewEpoch() throws Exception {
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111").replace("}",",\"description\":\"Wir nutzen SAP intern.\"}"));
        add("BBBBBBhost01", "https://www.muster.de/b", org("B GmbH", "030 2222222"));
        commit();
        settle();
        assertEquals(1L,count("SELECT count(*) FROM kg_observation"));
        final String epoch = this.store.epoch();
        final String cursor = KgChangeLog.cursor(epoch, count("SELECT max(seq) FROM kg_change"));
        client().deleteByQuery("*:*");
        commit();
        settle();
        assertNotEquals(epoch, this.store.epoch());
        assertEquals(this.store.epoch(), this.store.read(c -> KgStore.getMeta(c, KgSchema.META_EPOCH)));
        for (final String t : new String[] {"kg_doc", "kg_statement", "kg_entity", "kg_evidence", "kg_change", "kg_work"}) {
            assertEquals(t, 0L, count("SELECT count(*) FROM " + t));
        }
        assertEquals(1L,count("SELECT count(*) FROM kg_observation WHERE source_status='removed' AND assertion_status='recorded'"));
        assertEquals(1L,count("SELECT count(*) FROM kg_observation_scope"));
        try {
            this.store.read(c -> KgChangeLog.read(c, cursor, KgChangeLog.Viewer.ALL, 10));
            fail("an old cursor must be refused");
        } catch (final KgException e) {
            assertEquals(KgException.EPOCH_CHANGED, e.code());
        }
        assertEquals("0", this.store.read(c -> KgStore.getMeta(c, KgSchema.META_RESET_IN_PROGRESS)));
        // the index fills again
        add("CCCCCChost01", "https://www.muster.de/c", org("C GmbH", "030 3333333"));
        settle();
        assertTrue(tracked("CCCCCChost01"));
    }

    @Test
    public void interruptedFullResetIsFinishedAtTheNextStart() throws Exception {
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        commit();
        settle();
        client().deleteByQuery("*:*");
        commit();
        this.sync.step(); // new epoch and the mark, then the process dies
        assertEquals("1", this.store.read(c -> KgStore.getMeta(c, KgSchema.META_RESET_IN_PROGRESS)));
        stop(false);
        start(1000, true);
        settle();
        assertEquals(0L, count("SELECT count(*) FROM kg_doc"));
        assertEquals("0", this.store.read(c -> KgStore.getMeta(c, KgSchema.META_RESET_IN_PROGRESS)));
    }

    @Test
    public void refusedGrowthDefersExtractionWhileDeletionsContinue() throws Exception {
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        commit();
        settle();
        this.guard.setManualPause(true);
        add("NNNNNNhost01", "https://www.muster.de/n", org("N GmbH", "030 5555555"));
        client().deleteById("AAAAAAhost01");
        settle(() -> !tracked("AAAAAAhost01"));
        for (int i = 0; i < 20; i++) {
            this.sync.step();
            this.clock.addAndGet(3000L);
        }
        assertFalse("the deletion ran during the pause", tracked("AAAAAAhost01"));
        assertFalse("no new growth during the pause", tracked("NNNNNNhost01"));
        assertEquals(1L, queue());
        assertTrue(this.sync.counters.growthRefused.get() + this.sync.counters.deferred.get() >= 1L);
        // the disk reserve pauses growth the same way
        this.guard.setManualPause(false);
        final long between = (this.cfg.criticalFloorBytes() + this.cfg.growthFloorBytes()) / 2L;
        assertTrue(this.cfg.criticalFloorBytes() < between && between < this.cfg.growthFloorBytes());
        this.probe.usable.set(between);
        this.clock.addAndGet(31_000L);
        for (int i = 0; i < 20; i++) {
            this.sync.step();
            this.clock.addAndGet(3000L);
        }
        assertFalse(tracked("NNNNNNhost01"));
        this.probe.usable.set(100L * KgTestSupport.GIB);
        this.clock.addAndGet(31_000L);
        settle();
        assertTrue(tracked("NNNNNNhost01"));
    }

    @Test
    public void theManualPauseStopsEnrichmentWhileDeletionsContinueAndTheResumeCatchesUp() throws Exception {
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        add("BBBBBBhost01", "https://www.muster.de/b", org("B GmbH", "030 2222222"));
        add("CCCCCChost01", "https://www.muster.de/c", org("C GmbH", "030 3333333"));
        commit();
        settle();
        // C is gone (404) before the pause; its facts are no longer current
        final SolrInputDocument gone = doc("CCCCCChost01", "https://www.muster.de/c", "c1", null);
        gone.setField("httpstatus_i", 404);
        gone.setField("failtype_s", "fail");
        client().add(gone);
        settle();
        assertEquals(3L, count("SELECT state FROM kg_doc WHERE doc_id = 'CCCCCChost01'"));
        final String facts = "SELECT (SELECT count(*) FROM kg_entity) || '/' || (SELECT count(*) FROM kg_statement) || '/'"
                + " || (SELECT count(*) FROM kg_evidence) || '/' || (SELECT count(*) FROM kg_statement WHERE quality = 1)";
        final String before = strings(facts).get(0);

        this.guard.setManualPause(true);
        final long extractions = this.sync.counters.extractions.get();
        add("NNNNNNhost01", "https://www.muster.de/n", org("N GmbH", "030 5555555")); // a new page
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 9999999")); // an update
        add("CCCCCChost01", "https://www.muster.de/c", org("C GmbH", "030 3333333")); // back, same content
        client().deleteById("BBBBBBhost01"); // a deletion
        commit();
        for (int i = 0; i < 40; i++) {
            this.sync.step();
            this.clock.addAndGet(3000L);
        }
        assertFalse("the deletion ran during the pause", tracked("BBBBBBhost01"));
        assertFalse("no new page during the pause", tracked("NNNNNNhost01"));
        assertEquals("no update during the pause", List.of("+49301111111", "+49303333333"), phones());
        assertEquals("a page that is back stays as it was until the resume", 3L,
                count("SELECT state FROM kg_doc WHERE doc_id = 'CCCCCChost01'"));
        assertEquals("no extraction is even started", extractions, this.sync.counters.extractions.get());
        final String during = strings(facts).get(0);
        final long[] b = java.util.Arrays.stream(before.split("/")).mapToLong(Long::parseLong).toArray();
        final long[] d = java.util.Arrays.stream(during.split("/")).mapToLong(Long::parseLong).toArray();
        for (int i = 0; i < b.length; i++) {
            assertTrue("no growth during the pause: " + before + " -> " + during, d[i] <= b[i]);
        }
        final org.json.JSONObject byType = this.sync.status().getJSONObject("lag").getJSONObject("byType");
        assertEquals(byType.toString(), 1L, byType.getLong("new"));
        assertEquals(byType.toString(), 2L, byType.getLong("update"));
        assertEquals(byType.toString(), 0L, byType.getLong("delete"));
        assertEquals(StorageGuard.MANUAL, this.sync.status().optString("growthRefusal"));
        // the pause also survives more time: nothing moves
        this.clock.addAndGet(10L * SyncService.GROWTH_RETRY_MILLIS);
        for (int i = 0; i < 20; i++) {
            this.sync.step();
            this.clock.addAndGet(3000L);
        }
        assertEquals(during, strings(facts).get(0));

        this.guard.setManualPause(false);
        this.clock.addAndGet(SyncService.GROWTH_RETRY_MILLIS + 1000L);
        settle();
        assertTrue("the resume picks up the new page", tracked("NNNNNNhost01"));
        assertEquals(List.of("+49303333333", "+49305555555", "+49309999999"), phones());
        assertEquals(1L, count("SELECT state FROM kg_doc WHERE doc_id = 'CCCCCChost01'"));
        assertTrue(this.sync.counters.extractions.get() > extractions);
    }

    @Test
    public void closedGatesDeferExtractionButNotRemovals() throws Exception {
        final AtomicInteger indexing = new AtomicInteger();
        final Gates.Probe busy = new Gates.Probe() {
            @Override
            public int indexingQueue() {
                return indexing.get();
            }

            @Override
            public double load() {
                return -1d;
            }

            @Override
            public long freeHeapBytes() {
                return Long.MAX_VALUE;
            }

            @Override
            public String onlineCaution() {
                return null;
            }
        };
        stop(true);
        start(1000, false);
        this.sync = new SyncService(this.cfg, this.store, this.dirty, this.source, new Gates(this.cfg, busy), this.clock::get, false);
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        commit();
        settle();
        indexing.set(1000);
        add("NNNNNNhost01", "https://www.muster.de/n", org("N GmbH", "030 5555555"));
        client().deleteById("AAAAAAhost01");
        settle(() -> !tracked("AAAAAAhost01"));
        assertFalse(tracked("NNNNNNhost01"));
        assertEquals(Gates.INDEXING_QUEUE, this.sync.status().optString("gate"));
        indexing.set(0);
        settle();
        assertTrue(tracked("NNNNNNhost01"));
    }

    @Test
    public void collectionChangeOfADocumentMovesItsScopeOrRemovesIt() throws Exception {
        configure(KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1,c3"));
        stop(true);
        start(1000, false);
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        settle();
        assertEquals(List.of("c1"), strings("SELECT c.name FROM kg_entity_scope s JOIN kg_collection c ON c.coll_id = s.coll_id"));
        client().add(doc("AAAAAAhost01", "https://www.muster.de/", "c3", org("A GmbH", "030 1111111")));
        settle();
        assertEquals(List.of("c3"), strings("SELECT c.name FROM kg_entity_scope s JOIN kg_collection c ON c.coll_id = s.coll_id"));
        client().add(doc("AAAAAAhost01", "https://www.muster.de/", "c2", org("A GmbH", "030 1111111")));
        settle();
        assertFalse("out of the followed collections: removed", tracked("AAAAAAhost01"));
    }

    @Test
    public void allowlistChangeTriggersAReconcileThatRemovesUnfollowedDocuments() throws Exception {
        configure(KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1,c2"));
        stop(true);
        start(1000, false);
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        client().add(doc("BBBBBBhost02", "https://other.example/", "c2", org("B GmbH", "040 1111111")));
        commit();
        settle();
        assertTrue(tracked("BBBBBBhost02"));
        stop(true);
        configure(KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1"));
        start(1000, false);
        settle();
        assertFalse(tracked("BBBBBBhost02"));
        assertTrue(tracked("AAAAAAhost01"));
        assertEquals(Reconciler.REASON_COLLECTIONS, strings("SELECT reason FROM kg_scan ORDER BY run_id DESC LIMIT 1").get(0));
    }

    @Test
    public void retentionExpiresAndPurgesOldSources() throws Exception {
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111").replace("}",",\"description\":\"Wir nutzen SAP intern.\"}"));
        settle();
        final SolrInputDocument fail = doc("AAAAAAhost01", "https://www.muster.de/", "c1", null);
        fail.setField("httpstatus_i", 410);
        fail.setField("failtype_s", "fail");
        client().add(fail);
        settle();
        assertEquals(3L, count("SELECT state FROM kg_doc WHERE doc_id = 'AAAAAAhost01'"));
        this.clock.addAndGet(8L * 24L * 3600_000L);
        this.sync.retention().runSoon();
        settle();
        assertFalse("gone longer than the retention: removed", tracked("AAAAAAhost01"));
        assertEquals(1L,count("SELECT count(*) FROM kg_observation WHERE assertion_status='recorded' AND quote LIKE '%SAP intern%'"));
        assertEquals(0L, count("SELECT count(*) FROM kg_statement"));
        // the fail document is still in Solr; the next reconcile does not track it again
        this.sync.requestReconcile(Reconciler.REASON_ADMIN);
        settle();
        assertFalse(tracked("AAAAAAhost01"));
    }

    @Test public void archivedSourceReclassificationIsReconciledEvenAfterItsLiveDocIsGone()throws Exception {
        String content=org("A GmbH","030 1111111").replace("}",",\"description\":\"Wir nutzen SAP intern.\"}");
        add("AAAAAAhost01","https://www.muster.de/",content);commit();settle();
        this.store.write(WriteClass.MAINTENANCE,0,c->{try(java.sql.Statement s=c.createStatement()){s.execute("DELETE FROM kg_doc");}return null;});
        assertEquals(1L,count("SELECT count(*) FROM kg_observation"));
        client().add(doc("AAAAAAhost01","https://www.muster.de/","not-followed",content));commit();
        this.sync.requestReconcile(Reconciler.REASON_ADMIN);
        settle();
        assertEquals(0L,count("SELECT count(*) FROM kg_observation_scope s JOIN kg_collection c USING(coll_id) WHERE c.name='c1'"));
        assertEquals(1L,count("SELECT count(*) FROM kg_observation_scope s JOIN kg_collection c USING(coll_id) WHERE c.name='not-followed'"));
        assertEquals(0L,count("SELECT count(*) FROM kg_doc"));
        assertEquals(1L,count("SELECT count(*) FROM kg_observation WHERE assertion_status='recorded'"));
    }

    // ------------------------------------------- job advertisements (0.10.2)

    private static final String POSTING = "{\"@type\":\"JobPosting\",\"title\":\"Buchhalter (m/w/d)\",\"description\":\"Wir nutzen SAP"
            + " intern. SAP Kenntnisse erforderlich.\",\"hiringOrganization\":{\"@type\":\"Organization\",\"name\":\"Job GmbH\","
            + "\"url\":\"https://www.job.example/\"}}";

    private String hash(final String docId) throws Exception {
        return strings("SELECT hex(input_hash) FROM kg_doc WHERE doc_id = '" + docId + "'").get(0);
    }

    @Test
    public void aChangedJobReadingReadsOnlyTheJobPagesAgain() throws Exception {
        // the hints of job advertisements switched off for c1 (an explicit setting)
        restartWith(KgConfig.COLLECTIONS, "c1", KgConfig.JOBS_SIGNALS_PREFIX + "c1", "false", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        add("JOBSAAhost02", "https://www.job.example/stellen/buchhalter", POSTING);
        add("CCCCCChost03", "https://www.drei.example/", org("C GmbH", "040 1111111"));
        commit();
        settle();
        final String extractors = strings("SELECT value FROM kg_meta WHERE key = 'extractors'").get(0);
        final String a = hash("AAAAAAhost01"), job = hash("JOBSAAhost02"), c = hash("CCCCCChost03");
        assertEquals("no hint and no job object while switched off", 0L, count("SELECT count(*) FROM kg_statement s JOIN kg_vocab v"
                + " ON v.term_id = s.pred WHERE v.name = 'system_signal'"));
        // switched on (the setting removed: on by default): the extractor identity stays, only the job page is read again
        restartWith(KgConfig.COLLECTIONS, "c1", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        settle();
        assertEquals(extractors, strings("SELECT value FROM kg_meta WHERE key = 'extractors'").get(0));
        assertEquals("the company pages keep their input", a, hash("AAAAAAhost01"));
        assertEquals(c, hash("CCCCCChost03"));
        assertFalse("the job page is read again", job.equals(hash("JOBSAAhost02")));
        // the other pages are checked, not extracted: one extraction since the start (a processed page counts its generation up)
        assertEquals("only the job page is extracted again", 1L, this.sync.counters.published.get());
        assertEquals("the hints of the advertisement belong to its declared employer, as advertisement",
                List.of("Job GmbH|internal_use|job_ad_description", "Job GmbH|required_competence|job_ad_description"),
                strings("SELECT n.obj_val || '|' || json_extract(s.obj_val, '$.context') || '|' || json_extract(s.obj_val, '$.section')"
                        + " FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred JOIN kg_statement n ON n.subj = s.subj"
                        + " JOIN kg_vocab nv ON nv.term_id = n.pred WHERE v.name = 'system_signal' AND nv.name = 'name' ORDER BY 1"));
        assertEquals("no job object without job extraction", 0L, count("SELECT count(*) FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type"
                + " WHERE t.name = 'job'"));
        assertEquals("the check is done", 0L, count("SELECT count(*) FROM kg_meta WHERE key = 'job_recheck'"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'job_reading_changed'"));
        assertFalse("no re-extraction of every page", this.sync.reconciler().status().optBoolean("reextract"));
        // a restart with the same reading checks nothing again
        restartWith(KgConfig.COLLECTIONS, "c1", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        settle();
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'job_reading_changed'"));
        assertEquals(Reconciler.REASON_START, strings("SELECT reason FROM kg_scan ORDER BY run_id DESC LIMIT 1").get(0));
    }

    /**
     * 0.10.3: a consent banner made purposes partner organisations. Pages processed before the corrected rules that carry a relation
     * of the rule tier are extracted again once, at low priority, and no other page; a restart does not repeat it.
     */
    @Test
    public void pagesWithRelationsOfTheRuleTierAreExtractedAgainOnce() throws Exception {
        final SolrInputDocument partners = doc("PARTNEhost01", "https://www.partner.example/partner", "c1", null);
        partners.setField("text_t", List.of("Unsere Partner: Muster Software GmbH und Beispiel AG."));
        client().add(partners);
        add("AAAAAAhost02", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        commit();
        settle();
        final String relations = "SELECT count(*) FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v"
                + " ON v.term_id = s.pred WHERE v.name = 'partner_of' AND e.tier = 2";
        assertEquals("two partners of the rule tier", 2L, count(relations));
        assertEquals("a new graph has nothing to check again", 1L, count("SELECT count(*) FROM kg_meta WHERE key = 'relation_recheck_done'"));
        // a graph of 0.10.2: the check never ran
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> {
            KgStore.deleteMeta(tx, "relation_recheck_since");
            KgStore.deleteMeta(tx, "relation_recheck_done");
            return null;
        });
        final String a = hash("AAAAAAhost02");
        this.clock.addAndGet(1000L);
        stop(true);
        start(1000, false);
        settle();
        assertEquals("only the page with relations of the rule tier is extracted again", 1L, this.sync.counters.extractions.get());
        assertEquals(a, hash("AAAAAAhost02"));
        assertEquals(2L, count(relations));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck' AND detail LIKE '1 pages %'"));
        assertEquals(0, this.sync.status().getInt("relationRecheck"));
        // a restart: nothing again, the check is done
        stop(true);
        start(1000, false);
        settle();
        assertEquals(0L, this.sync.counters.extractions.get());
        assertEquals(1L, count("SELECT count(*) FROM kg_meta WHERE key = 'relation_recheck_done'"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck'"));
        assertEquals(1L, count("SELECT count(*) FROM kg_meta WHERE key = 'relation_recheck_rules' AND value = '3'"));
        // a graph of 0.10.3: checked with the rules before (no version row); the current rules check the page once more
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> {
            KgStore.deleteMeta(tx, "relation_recheck_rules");
            return null;
        });
        this.clock.addAndGet(1000L);
        stop(true);
        start(1000, false);
        settle();
        assertEquals("the page with relations of the rule tier once more", 1L, this.sync.counters.extractions.get());
        assertEquals(a, hash("AAAAAAhost02"));
        assertEquals(2L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck'"));
        stop(true);
        start(1000, false);
        settle();
        assertEquals(0L, this.sync.counters.extractions.get());
        assertEquals(2L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck'"));
    }

    /** A page of a careers section (neutral example): the menu of the open positions, then the page's own text. */
    private SolrInputDocument careersPage(final String id, final String url, final String title, final String text) {
        final SolrInputDocument d = doc(id, url, "c1", null);
        d.setField("title", List.of(title));
        d.setField("text_t", List.of("Jobs & Karriere Stellenangebote Freiberuflicher SAP-Berater (m/w/d) SAP Basis Administrator (m/w/d)"
                + " Projektleiter IT (m/w/d) Benefits Team Kultur " + text + " Muster Software GmbH, Musterstraße 1, 10115 Berlin."));
        return d;
    }

    private static final String JOBS_OF = "SELECT count(*) FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid"
            + " JOIN kg_entity n ON n.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = n.type JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
            + " WHERE t.name = 'job' AND d.doc_id = ";

    /**
     * 0.10.5 (production finding of 0.10.4): every page of a careers section had the positions of its menu as jobs. The pages
     * with jobs of the rule tier are extracted again once with the corrected rules: a page that only repeats the menu keeps no
     * job, the page of a position keeps its job with its identity, and no other page is extracted.
     */
    @Test
    public void pagesWithJobsOfTheRuleTierAreExtractedAgainOnce() throws Exception {
        restartWith(KgConfig.COLLECTIONS, "c1", KgConfig.JOBS_COLLECTIONS, "c1", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        client().add(careersPage("JOBDETmswr01", "https://www.muster-software.example/jobs-karriere/stellenangebote/freiberuflicher-sap-berater/",
                "Freiberuflicher SAP-Berater (m/w/d) | Muster Software", "Freiberuflicher SAP-Berater (m/w/d) Ihre Aufgaben: Beratung"
                        + " unserer Kunden zu SAP S/4HANA. Ihr Profil: SAP Kenntnisse erforderlich. Bei uns arbeitest du remote."));
        client().add(careersPage("JOBBENmswr01", "https://www.muster-software.example/jobs-karriere/team-werden/benefits/",
                "Benefits | Muster Software", "Benefits bei uns Wir bieten flexible Arbeitszeiten und ein starkes Team. Jetzt bewerben!"));
        add("AAAAAAhost02", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        commit();
        settle();
        assertTrue("the page of the position has its job", count(JOBS_OF + "'JOBDETmswr01'") > 0L);
        assertEquals("the benefits page has none", 0L, count(JOBS_OF + "'JOBBENmswr01'"));
        final List<String> job = strings("SELECT DISTINCT n.public_id FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid"
                + " JOIN kg_entity n ON n.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = n.type WHERE t.name = 'job'");
        assertEquals("one job: the position of its page", 1, job.size());
        // a graph of 0.10.4: the benefits page had the positions of its menu as jobs; checked with the relation rules 2
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> {
            try (java.sql.Statement st = tx.createStatement()) {
                st.execute("INSERT INTO kg_evidence (stmt_rowid, doc_rowid, tier, ext_id, kind, certainty, confidence, locator, excerpt,"
                        + " observed_at) SELECT e.stmt_rowid, (SELECT doc_rowid FROM kg_doc WHERE doc_id = 'JOBBENmswr01'), e.tier, e.ext_id,"
                        + " e.kind, e.certainty, e.confidence, e.locator, e.excerpt, e.observed_at FROM kg_evidence e JOIN kg_statement s"
                        + " ON s.stmt_rowid = e.stmt_rowid JOIN kg_entity n ON n.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = n.type"
                        + " WHERE t.name = 'job' AND e.tier = 2 AND e.doc_rowid = (SELECT doc_rowid FROM kg_doc WHERE doc_id = 'JOBDETmswr01')");
            }
            KgStore.putMeta(tx, "relation_recheck_rules", "2");
            KgStore.deleteMeta(tx, "relation_recheck_since");
            KgStore.deleteMeta(tx, "relation_recheck_done");
            return null;
        });
        assertTrue(count(JOBS_OF + "'JOBBENmswr01'") > 0L);
        final String a = hash("AAAAAAhost02");
        this.clock.addAndGet(1000L);
        stop(true);
        start(1000, false);
        settle();
        assertEquals("only the two pages with jobs of the rule tier are extracted again", 2L, this.sync.counters.extractions.get());
        assertEquals(a, hash("AAAAAAhost02"));
        assertEquals("the menu entries of the benefits page are no jobs", 0L, count(JOBS_OF + "'JOBBENmswr01'"));
        assertTrue(count(JOBS_OF + "'JOBDETmswr01'") > 0L);
        assertEquals("the position keeps its identity", job, strings("SELECT DISTINCT n.public_id FROM kg_evidence e JOIN kg_statement s"
                + " ON s.stmt_rowid = e.stmt_rowid JOIN kg_entity n ON n.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = n.type"
                + " WHERE t.name = 'job'"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck' AND detail LIKE '2 pages %'"));
        assertEquals(1L, count("SELECT count(*) FROM kg_meta WHERE key = 'relation_recheck_rules' AND value = '3'"));
        // a restart: nothing again
        stop(true);
        start(1000, false);
        settle();
        assertEquals(0L, this.sync.counters.extractions.get());
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck'"));
    }

    /** Everything a page is the evidence of: subject, statement, predicate, object or value, tier and locator. */
    private List<String> evidenceOf(final String docId) throws Exception {
        return strings("SELECT n.public_id || '|' || s.public_id || '|' || v.name || '|' || coalesce(o.public_id, s.obj_val) || '|' || e.tier"
                + " || '|' || coalesce(e.locator, '') FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid"
                + " JOIN kg_vocab v ON v.term_id = s.pred JOIN kg_entity n ON n.ent_rowid = s.subj LEFT JOIN kg_entity o ON o.ent_rowid = s.obj_ent"
                + " JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE d.doc_id = '" + docId + "' ORDER BY 1");
    }

    /** As 0.10.4 left it: the rules of version 2 checked, the menu jobs of the detail page also on the benefits page. */
    private void asCheckedWithRules2(final String detail, final String benefits) throws Exception {
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> {
            try (java.sql.Statement st = tx.createStatement()) {
                st.execute("INSERT INTO kg_evidence (stmt_rowid, doc_rowid, tier, ext_id, kind, certainty, confidence, locator, excerpt,"
                        + " observed_at) SELECT e.stmt_rowid, (SELECT doc_rowid FROM kg_doc WHERE doc_id = '" + benefits + "'), e.tier, e.ext_id,"
                        + " e.kind, e.certainty, e.confidence, e.locator, e.excerpt, e.observed_at FROM kg_evidence e JOIN kg_statement s"
                        + " ON s.stmt_rowid = e.stmt_rowid JOIN kg_entity n ON n.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = n.type"
                        + " WHERE t.name = 'job' AND e.tier = 2 AND e.doc_rowid = (SELECT doc_rowid FROM kg_doc WHERE doc_id = '" + detail + "')");
            }
            KgStore.putMeta(tx, "relation_recheck_rules", "2");
            KgStore.deleteMeta(tx, "relation_recheck_since");
            KgStore.deleteMeta(tx, "relation_recheck_done");
            return null;
        });
    }

    private static final String ADVERTISEMENT = "Freiberuflicher SAP-Berater (m/w/d) in Vollzeit. Ihre Aufgaben: Beratung unserer Kunden zu"
            + " SAP S/4HANA. Ihr Profil: SAP Kenntnisse erforderlich. Vergütung: 4.000 – 5.000 € brutto monatlich. Bei uns arbeitest du"
            + " remote. Bewerbung an jobs@muster-software.example. Unsere Partner: Alpha Systeme GmbH und Beta Logistik AG.";

    /**
     * 0.10.5: the re-check keeps what is valid. The page of a position keeps its job with every other statement of it (salary,
     * employment type, application route, employer, software hints of the advertisement) and the other statements of the page
     * (partners, the operator of JSON-LD), under the same identifiers; the benefits page loses only the menu jobs.
     */
    @Test
    public void theRecheckKeepsAPositionWithItsEvidenceAndHints() throws Exception {
        restartWith(KgConfig.COLLECTIONS, "c1", KgConfig.JOBS_COLLECTIONS, "c1", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        final SolrInputDocument detail = careersPage("JOBDETmswr01",
                "https://www.muster-software.example/jobs-karriere/stellenangebote/freiberuflicher-sap-berater/",
                "Freiberuflicher SAP-Berater (m/w/d) | Muster Software", ADVERTISEMENT);
        detail.setField("ld_json_txt", List.of("{\"@type\":\"Organization\",\"name\":\"Muster Software GmbH\",\"url\":"
                + "\"https://www.muster-software.example/\",\"telephone\":\"030 2222222\"}"));
        client().add(detail);
        client().add(careersPage("JOBBENmswr01", "https://www.muster-software.example/jobs-karriere/team-werden/benefits/",
                "Benefits | Muster Software", "Benefits bei uns Wir bieten flexible Arbeitszeiten und ein starkes Team."
                        + " Unsere Partner: Alpha Systeme GmbH und Beta Logistik AG."));
        commit();
        settle();
        final List<String> position = evidenceOf("JOBDETmswr01");
        final List<String> benefits = evidenceOf("JOBBENmswr01");
        for (final String p : List.of("|name|", "|salary|", "|employment_type|", "|application_route|", "|hiring_organization|",
                "|system_signal|", "|partner_of|", "|phone|")) {
            assertTrue(p + " " + position, position.stream().anyMatch(e -> e.contains(p)));
        }
        assertTrue(benefits.toString(), benefits.stream().anyMatch(e -> e.contains("|partner_of|")));
        asCheckedWithRules2("JOBDETmswr01", "JOBBENmswr01");
        assertTrue(evidenceOf("JOBBENmswr01").size() > benefits.size());
        this.clock.addAndGet(1000L);
        stop(true);
        start(1000, false);
        settle();
        assertEquals(2L, this.sync.counters.extractions.get());
        assertEquals("the position keeps all of its evidence, identifiers included", position, evidenceOf("JOBDETmswr01"));
        assertEquals("the benefits page keeps everything but the menu jobs", benefits, evidenceOf("JOBBENmswr01"));
    }

    /** Copies the evidence of the job of page {@code from} to page {@code to} as evidence of the LLM tier. */
    private void asLlmJobEvidence(final String from, final String to) throws Exception {
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> {
            try (java.sql.Statement st = tx.createStatement()) {
                st.execute("INSERT INTO kg_evidence (stmt_rowid, doc_rowid, tier, ext_id, kind, certainty, confidence, locator, excerpt,"
                        + " observed_at) SELECT e.stmt_rowid, (SELECT doc_rowid FROM kg_doc WHERE doc_id = '" + to + "'), 3, e.ext_id,"
                        + " e.kind, e.certainty, e.confidence, e.locator, e.excerpt, e.observed_at FROM kg_evidence e JOIN kg_statement s"
                        + " ON s.stmt_rowid = e.stmt_rowid JOIN kg_entity n ON n.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = n.type"
                        + " WHERE t.name = 'job' AND e.tier = 2 AND e.doc_rowid = (SELECT doc_rowid FROM kg_doc WHERE doc_id = '" + from + "')");
            }
            return null;
        });
    }

    private static final String TIER3 = "SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE e.tier = 3"
            + " AND d.doc_id = ";

    /**
     * 0.10.5: the re-check counts the jobs of the LLM tier on its pages and changes none of them: a job that is only a menu entry
     * of its page (wrong), the position of a detail page (right). A page with jobs of the LLM tier only is checked as well.
     */
    @Test
    public void theRecheckCountsTheJobsOfTheLlmTierWithoutChangingThem() throws Exception {
        restartWith(KgConfig.COLLECTIONS, "c1", KgConfig.JOBS_COLLECTIONS, "c1", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        client().add(careersPage("JOBDETmswr01", "https://www.muster-software.example/jobs-karriere/stellenangebote/freiberuflicher-sap-berater/",
                "Freiberuflicher SAP-Berater (m/w/d) | Muster Software", ADVERTISEMENT));
        client().add(careersPage("JOBBENmswr01", "https://www.muster-software.example/jobs-karriere/team-werden/benefits/",
                "Benefits | Muster Software", "Benefits bei uns Wir bieten flexible Arbeitszeiten und ein starkes Team."
                        + " Unsere Partner: Alpha Systeme GmbH und Beta Logistik AG."));
        client().add(careersPage("JOBTEAmswr01", "https://www.muster-software.example/jobs-karriere/team-werden/kultur/",
                "Kultur | Muster Software", "Unsere Kultur: offen, fair und neugierig."));
        commit();
        settle();
        assertEquals("nothing of the rules on the culture page", 0L, count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d"
                + " ON d.doc_rowid = e.doc_rowid WHERE e.tier = 2 AND d.doc_id = 'JOBTEAmswr01'"));
        asLlmJobEvidence("JOBDETmswr01", "JOBDETmswr01");
        asLlmJobEvidence("JOBDETmswr01", "JOBBENmswr01");
        asLlmJobEvidence("JOBDETmswr01", "JOBTEAmswr01");
        asCheckedWithRules2("JOBDETmswr01", "JOBBENmswr01");
        final long detail = count(TIER3 + "'JOBDETmswr01'"), benefits = count(TIER3 + "'JOBBENmswr01'"), team = count(TIER3 + "'JOBTEAmswr01'");
        assertTrue(detail > 1L && benefits == detail && team == detail);
        this.clock.addAndGet(1000L);
        stop(true);
        start(1000, false);
        settle();
        assertEquals("the three pages, the culture page for its jobs of the LLM tier", 3L, this.sync.counters.extractions.get());
        final JSONObject audit = this.sync.status().getJSONObject("relationRecheckLlmJobs");
        assertEquals(audit.toString(), 3L, audit.getLong("pages"));
        assertEquals(audit.toString(), 2L, audit.getLong("menuJobs"));
        assertEquals(audit.toString(), benefits + team, audit.getLong("menuEvidence"));
        assertEquals(audit.toString(), detail, audit.getLong("positionEvidence"));
        assertEquals(audit.toString(), 0L, audit.getLong("otherEvidence"));
        assertEquals("counted, not changed", List.of(detail, benefits, team), List.of(count(TIER3 + "'JOBDETmswr01'"),
                count(TIER3 + "'JOBBENmswr01'"), count(TIER3 + "'JOBTEAmswr01'")));
        // a restart keeps the counts and counts nothing twice
        stop(true);
        start(1000, false);
        settle();
        assertEquals(audit.toString(), this.sync.status().getJSONObject("relationRecheckLlmJobs").toString());
    }

    /**
     * 0.10.5: a re-check interrupted by a crash goes on with the pages not yet extracted; none is extracted twice, the check ends
     * once and a later start repeats nothing.
     */
    @Test
    public void anInterruptedRecheckGoesOnWhereItStopped() throws Exception {
        restartWith(KgConfig.COLLECTIONS, "c1", KgConfig.JOBS_COLLECTIONS, "c1", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        final String[] positions = {"Freiberuflicher SAP-Berater", "SAP Basis Administrator", "Projektleiter IT"};
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < positions.length; i++) {
            final String slug = positions[i].toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
            ids.add("JOBDE" + i + "mswr01");
            client().add(careersPage(ids.get(ids.size() - 1), "https://www.muster-software.example/jobs-karriere/stellenangebote/" + slug + "/",
                    positions[i] + " (m/w/d) | Muster Software", ADVERTISEMENT.replace("Freiberuflicher SAP-Berater", positions[i])));
            ids.add("JOBBE" + i + "mswr01");
            client().add(careersPage(ids.get(ids.size() - 1), "https://www.muster-software.example/jobs-karriere/seite-" + i + "/",
                    "Seite " + i + " | Muster Software", "Wir bieten ein starkes Team. Unsere Partner: Alpha Systeme GmbH und Beta Logistik AG."));
        }
        commit();
        settle();
        final java.util.Map<String, List<String>> before = new java.util.TreeMap<>();
        for (final String id : ids) {
            before.put(id, evidenceOf(id));
        }
        asCheckedWithRules2("JOBDE0mswr01", "JOBBE0mswr01");
        this.clock.addAndGet(1000L);
        stop(true);
        start(1000, false);
        // the check starts, two pages are extracted again, then the process dies in the middle of the batch
        final SyncService interrupted = this.sync;
        this.source.afterText = n -> {
            if (n == 2) {
                interrupted.requestStop();
            }
        };
        for (int i = 0; i < 500 && this.source.texts.get() < 2; i++) {
            if (!this.sync.step()) {
                this.clock.addAndGet(3000L);
            }
        }
        final long first = this.sync.counters.extractions.get();
        assertTrue("stopped in the middle: " + first, first >= 1 && first < ids.size());
        stop(false);
        this.clock.addAndGet(1000L);
        start(1000, true);
        settle();
        assertEquals("each page once over both runs", ids.size(), first + this.sync.counters.extractions.get());
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck'"));
        assertEquals(0, this.sync.status().getInt("relationRecheck"));
        for (final String id : ids) {
            assertEquals(id, before.get(id), evidenceOf(id));
        }
        // the next start finds nothing left and ends the check for good
        stop(true);
        start(1000, false);
        settle();
        assertEquals(0L, this.sync.counters.extractions.get());
        assertEquals(1L, count("SELECT count(*) FROM kg_meta WHERE key = 'relation_recheck_done'"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'relation_rules_recheck'"));
    }

    @Test
    public void anUpgradedGraphReadsTheHintsOfItsJobPagesOnce() throws Exception {
        // a graph of 0.10.1: no stored job reading, jobs not extracted; the hints are on by default
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        add("JOBSAAhost02", "https://www.job.example/stellen/buchhalter", POSTING);
        commit();
        settle();
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> {
            KgStore.deleteMeta(tx, "job_modes");
            try (java.sql.Statement st = tx.createStatement()) {
                // as 0.10.1 read the job page: without hints, under an input hash without the job reading
                st.execute("DELETE FROM kg_statement WHERE pred = (SELECT term_id FROM kg_vocab WHERE kind = 2 AND name = 'system_signal')");
                st.execute("UPDATE kg_doc SET input_hash = zeroblob(16) WHERE doc_id = 'JOBSAAhost02'");
            }
            return null;
        });
        final String a = hash("AAAAAAhost01");
        stop(true);
        start(1000, false);
        settle();
        assertEquals(a, hash("AAAAAAhost01"));
        assertEquals(2L, count("SELECT count(*) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'system_signal'"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'job_reading_changed'"));
        assertEquals("only the job page is extracted again", 1L, this.sync.counters.published.get());
    }

    @Test
    public void aCollectionSwitchedOnAgainIsComparedWithTheReadingItsPagesWereReadWith() throws Exception {
        // c2 read without hints of its job advertisements
        restartWith(KgConfig.COLLECTIONS, "c1,c2", KgConfig.JOBS_SIGNALS_PREFIX + "c2", "false", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        client().add(doc("JOBSAAhost02", "https://www.job.example/stellen/buchhalter", "c2", POSTING));
        commit();
        settle();
        final String a = hash("AAAAAAhost01"), job = hash("JOBSAAhost02");
        // switched off, and meanwhile its hint setting removed (on by default): nothing of it is read
        restartWith(KgConfig.COLLECTIONS, "c1", KgConfig.INACTIVE_COLLECTIONS, "c2", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        settle();
        assertEquals(job, hash("JOBSAAhost02"));
        assertEquals(0L, count("SELECT count(*) FROM kg_event WHERE code = 'job_reading_changed'"));
        // switched on again: compared with the reading its pages were read with, so its job page is read again
        restartWith(KgConfig.COLLECTIONS, "c1,c2", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        settle();
        assertFalse(job.equals(hash("JOBSAAhost02")));
        assertEquals(a, hash("AAAAAAhost01"));
        assertEquals("only the job page is extracted again", 1L, this.sync.counters.published.get());
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'job_reading_changed'"));
        assertEquals(2L, count("SELECT count(*) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'system_signal'"));
    }

    // ------------------------------------------- collections switched off (6.2)

    /** Restarts the runtime with these settings (the settings page reopens the graph the same way). */
    private void restartWith(final String... settings) throws Exception {
        stop(true);
        configure(KgTestSupport.enabled(settings));
        start(1000, false);
    }

    private List<String> scopes(final String docId) throws Exception {
        return strings("SELECT k.name FROM kg_doc d JOIN kg_doc_collection dc ON dc.doc_rowid = d.doc_rowid JOIN kg_collection k"
                + " ON k.coll_id = dc.coll_id WHERE d.doc_id = '" + docId + "' ORDER BY 1");
    }

    @Test
    public void aCollectionSwitchedOffKeepsItsGraphDataAndIsNotUpdated() throws Exception {
        restartWith(KgConfig.COLLECTIONS, "c1,c2", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        client().add(doc("BBBBBBhost02", "https://other.example/", "c2", org("B GmbH", "040 1111111")));
        commit();
        settle();
        assertTrue(tracked("BBBBBBhost02"));
        final List<String> before = strings("SELECT public_id FROM kg_statement ORDER BY 1");
        // switched off: no longer in the list, named as switched off
        restartWith(KgConfig.COLLECTIONS, "c1", KgConfig.INACTIVE_COLLECTIONS, "c2", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        settle();
        assertTrue("the start reconcile keeps the pages of a collection switched off", tracked("BBBBBBhost02"));
        assertEquals(before, strings("SELECT public_id FROM kg_statement ORDER BY 1"));
        assertEquals(Reconciler.REASON_COLLECTIONS, strings("SELECT reason FROM kg_scan ORDER BY run_id DESC LIMIT 1").get(0));
        assertEquals(List.of("+49301111111", "+49401111111"), phones());
        // a recrawl of its page is not extracted: the graph keeps what it had
        client().add(doc("BBBBBBhost02", "https://other.example/", "c2", org("B GmbH", "040 2222222")));
        settle();
        assertEquals(List.of("+49301111111", "+49401111111"), phones());
        assertTrue(this.sync.counters.held.get() >= 1L);
        // a new page of it is not taken in, the other collection still is
        client().add(doc("CCCCCChost03", "https://third.example/", "c2", org("C GmbH", "050 1111111")));
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 3333333"));
        settle();
        assertFalse(tracked("CCCCCChost03"));
        assertEquals(List.of("+49303333333", "+49401111111"), phones());
        // the daily reconcile deletes nothing of it either
        this.sync.requestReconcile(Reconciler.REASON_ADMIN);
        settle();
        assertTrue(tracked("BBBBBBhost02"));
        assertEquals(0L, count("SELECT count(*) FROM kg_scan_candidate"));
        // a page deleted from the index still leaves the graph
        client().deleteById("BBBBBBhost02");
        commit();
        settle();
        assertFalse(tracked("BBBBBBhost02"));
    }

    @Test
    public void switchingACollectionOnPicksUpItsPagesThroughTheStartReconcile() throws Exception {
        restartWith(KgConfig.COLLECTIONS, "c1", KgConfig.INACTIVE_COLLECTIONS, "c2");
        withoutCapture(() -> {
            client().add(doc("CCCCCChost03", "https://third.example/", "c2", org("C GmbH", "050 1111111")));
            commit();
        });
        settle();
        assertFalse(tracked("CCCCCChost03"));
        // switched on: in the list again, no longer switched off; the pages it already has in the index come in
        restartWith(KgConfig.COLLECTIONS, "c1,c2");
        settle();
        assertTrue("the backfill of the start reconcile", tracked("CCCCCChost03"));
        assertEquals(List.of("+49501111111"), phones());
        assertEquals(List.of("c2"), scopes("CCCCCChost03"));
    }

    @Test
    public void underEveryCollectionASwitchedOffOneIsKeptButNotFollowed() throws Exception {
        restartWith(KgConfig.COLLECTIONS, "*", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        client().add(doc("BBBBBBhost02", "https://other.example/", "c2", org("B GmbH", "040 1111111")));
        commit();
        settle();
        assertTrue(tracked("BBBBBBhost02"));
        restartWith(KgConfig.COLLECTIONS, "*", KgConfig.INACTIVE_COLLECTIONS, "c2", KgConfig.RECONCILE_BRAKE_MIN_DOCS, "3");
        assertFalse(this.cfg.follows("c2"));
        assertTrue(this.cfg.follows("c1") && this.cfg.follows("any-new-collection"));
        settle();
        assertTrue(tracked("BBBBBBhost02"));
        client().add(doc("CCCCCChost03", "https://third.example/", "c2", org("C GmbH", "050 1111111")));
        client().add(doc("DDDDDDhost04", "https://fourth.example/", "c4", org("D GmbH", "060 1111111")));
        settle();
        assertFalse("a page of the collection switched off", tracked("CCCCCChost03"));
        assertTrue("a page of any other collection", tracked("DDDDDDhost04"));
    }

    @Test
    public void aPageAlsoInAFollowedCollectionKeepsItsMembershipOfOneSwitchedOff() throws Exception {
        restartWith(KgConfig.COLLECTIONS, "c1,c2");
        client().add(doc("AAAAAAhost01", "https://www.muster.de/", List.of("c1", "c2"), org("A GmbH", "030 1111111")));
        settle();
        assertEquals(List.of("c1", "c2"), scopes("AAAAAAhost01"));
        restartWith(KgConfig.COLLECTIONS, "c1", KgConfig.INACTIVE_COLLECTIONS, "c2");
        client().add(doc("AAAAAAhost01", "https://www.muster.de/", List.of("c1", "c2"), org("A GmbH", "030 2222222")));
        settle();
        assertEquals("followed through c1, still seen in c2", List.of("c1", "c2"), scopes("AAAAAAhost01"));
        assertEquals(List.of("+49302222222"), phones());
    }
}
