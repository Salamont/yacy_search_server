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
            return this.inner.text(id);
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
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
        add("BBBBBBhost01", "https://www.muster.de/b", org("B GmbH", "030 2222222"));
        commit();
        settle();
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
        add("AAAAAAhost01", "https://www.muster.de/", org("A GmbH", "030 1111111"));
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
        assertEquals(0L, count("SELECT count(*) FROM kg_statement"));
        // the fail document is still in Solr; the next reconcile does not track it again
        this.sync.requestReconcile(Reconciler.REASON_ADMIN);
        settle();
        assertFalse(tracked("AAAAAAhost01"));
    }
}
