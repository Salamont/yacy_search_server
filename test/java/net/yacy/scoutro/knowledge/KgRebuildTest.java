package net.yacy.scoutro.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.store.KgBackup;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.Gates;
import net.yacy.scoutro.knowledge.sync.JsonLdCapture;

/**
 * The identity rebuild (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 22) against an
 * embedded Solr core: a shadow graph is built from Solr while the graph keeps
 * serving, verified, braked like a reconcile, and swapped in with the
 * previous database kept; a cancel, a stop or a leftover shadow leave the
 * graph unchanged.
 */
public class KgRebuildTest {
    private boolean background;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String EARLIER = KgIds.entityId("Organization", "test", "", "an earlier merge");

    private EmbeddedInstance solr;
    private File data;
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1",
                new String[] {"collection1", "webgraph"});
        this.data = this.tmp.newFolder("data");
    }

    @After
    public void close() {
        KgRuntime.stop();
        JsonLdCapture.clear();
        if (this.solr != null) {
            this.solr.close();
        }
    }

    private KgRuntime start(final String... extra) {
        final String[] all = new String[extra.length + 8];
        all[0] = KgConfig.COLLECTIONS;
        all[1] = "c1";
        all[2] = KgConfig.WAL_MAX_BYTES;
        all[3] = Long.toString(8L * 1024 * 1024);
        all[4] = KgConfig.TMP_MAX_BYTES;
        all[5] = Long.toString(8L * 1024 * 1024);
        all[6] = KgConfig.WAL_CHECKPOINT_BYTES;
        all[7] = Long.toString(2L * 1024 * 1024);
        System.arraycopy(extra, 0, all, 8, extra.length);
        final Map<String, String> settings = KgTestSupport.enabled(all);
        KgRuntime.start(new KgRuntime.Env(this.data, settings::get, this.clock::get, new KgTestSupport.Probe(), KgStore.SQLITE, this.background,
                () -> this.solr.getDefaultServer(), Gates.IDLE));
        final KgRuntime r = KgRuntime.current();
        assertNotNull(r);
        assertEquals(KgRuntime.State.RUNNING, r.state());
        return r;
    }

    private SolrClient client() {
        return this.solr.getDefaultServer();
    }

    private SolrInputDocument doc(final String id, final String host, final String ldJson) {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", "https://" + host + "/" + id);
        d.setField("host_s", host);
        d.setField("host_id_s", id.substring(6));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", List.of("c1"));
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        d.setField("ld_json_txt", List.of(ldJson));
        return d;
    }

    private static String org(final String name, final String vat) {
        return "{\"@type\":\"Organization\",\"name\":\"" + name + "\",\"vatID\":\"" + vat + "\",\"telephone\":\"030 1234567\"}";
    }

    /** Runs the graph's steps with an advancing clock until the condition holds (the rebuild runs on its own thread). */
    private void drive(final KgRuntime r, final BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 3000; i++) {
            if (r.state() == KgRuntime.State.RUNNING && !this.background) {
                r.tick();
                r.syncTick();
            }
            if (done.getAsBoolean()) {
                return;
            }
            this.clock.addAndGet(3000L);
            Thread.sleep(10);
        }
        fail("not reached: " + r.status());
    }

    private void settle(final KgRuntime r) throws InterruptedException {
        drive(r, () -> {
            final JSONObject sync = r.status().optJSONObject("sync");
            final JSONObject lag = sync == null ? null : sync.optJSONObject("lag");
            return sync != null && sync.optBoolean("initialized") && lag != null && lag.optLong("pending", 1L) == 0L
                    && !lag.optBoolean("reconcile_pending", true);
        });
    }

    private static String phase(final KgRuntime r) {
        final JSONObject rb = r.status().optJSONObject("rebuild");
        return rb == null ? "" : rb.optString("phase");
    }

    @Test
    public void rebuildSwapClosesTheOldScheduledSyncAndStartsOneReplacement() throws Exception {
        this.background=true;
        final KgRuntime r=start();
        client().add(doc("AAAAAAhost01","fixture.example",org("Fixture GmbH","DE123456789")));client().commit();settle(r);
        final java.lang.reflect.Field field=KgRuntime.class.getDeclaredField("syncFuture");field.setAccessible(true);
        final java.util.concurrent.ScheduledFuture<?> old=(java.util.concurrent.ScheduledFuture<?>)field.get(r);
        r.rebuild();drive(r,()->"done".equals(phase(r)));settle(r);
        final java.util.concurrent.ScheduledFuture<?> fresh=(java.util.concurrent.ScheduledFuture<?>)field.get(r);
        assertTrue(old.isCancelled());assertNotEquals(old,fresh);assertFalse(fresh.isDone());
        KgRuntime.stop();assertTrue(fresh.isCancelled());r.watchdogTick();assertTrue(fresh.isDone());
    }

    private static long count(final KgRuntime r, final String sql) {
        try {
            return r.store().read(c -> KgStore.queryLong(c, sql));
        } catch (final KgException e) {
            throw new AssertionError(e);
        }
    }

    private static String string(final KgRuntime r, final String sql) {
        try {
            return r.store().read(c -> KgStore.queryString(c, sql));
        } catch (final KgException e) {
            throw new AssertionError(e);
        }
    }

    private void index(final KgRuntime r, final int n) throws Exception {
        for (int i = 0; i < n; i++) {
            client().add(doc(String.format("D%05dhost%02d", i, i % 100), "www.firma" + i + ".de", org("Firma " + i + " GmbH",
                    String.format("DE%09d", 100000000 + i))));
        }
        client().commit();
        settle(r);
    }

    @Test
    public void aRebuildSplitsAWrongMergeAndKeepsThePreviousGraph() throws Exception {
        final KgRuntime r = start();
        index(r, 3);
        final String a = string(r, "SELECT e.public_id FROM kg_entity e JOIN kg_statement s ON s.subj = e.ent_rowid"
                + " WHERE s.obj_val = 'Firma 0 GmbH'");
        final String b = string(r, "SELECT e.public_id FROM kg_entity e JOIN kg_statement s ON s.subj = e.ent_rowid"
                + " WHERE s.obj_val = 'Firma 1 GmbH'");
        final String epoch = r.store().epoch();
        // a wrong merge of an earlier version: B's facts on A, B redirected to A
        r.store().write(WriteClass.MAINTENANCE, 0, tx -> {
            final long ra = KgStore.queryLong(tx, "SELECT ent_rowid FROM kg_entity WHERE public_id = '" + a + "'");
            final long rb = KgStore.queryLong(tx, "SELECT ent_rowid FROM kg_entity WHERE public_id = '" + b + "'");
            try (java.sql.Statement st = tx.createStatement()) {
                // facts both have (the same telephone) stay once, on A
                st.execute("UPDATE OR IGNORE kg_statement SET subj = " + ra + " WHERE subj = " + rb);
                st.execute("DELETE FROM kg_statement WHERE subj = " + rb);
                st.execute("UPDATE kg_entity_key SET ent_rowid = " + ra + " WHERE ent_rowid = " + rb);
                st.execute("DELETE FROM kg_entity_scope WHERE ent_rowid = " + rb);
                st.execute("UPDATE kg_entity SET status = 2, merged_into = " + ra + " WHERE ent_rowid = " + rb);
                // an ID from an even earlier merge that callers may have stored
                st.execute("INSERT INTO kg_entity_redirect (public_id, target_rowid) VALUES ('" + EARLIER + "', " + ra + ")");
            }
            return null;
        });
        assertEquals(2L, count(r, "SELECT count(*) FROM kg_entity WHERE status = 1"));
        r.rebuild();
        assertEquals("building", phase(r));
        assertTrue("the shadow lives in the graph directory", new File(this.data, KgPaths.RELATIVE_DIR + "/rebuild/graph.db").isFile());
        try {
            r.rebuild();
            fail("a second rebuild");
        } catch (final KgException e) {
            assertEquals(KgException.OPERATION_RUNNING, e.code());
        }
        drive(r, () -> "done".equals(phase(r)));
        settle(r);
        // the merge is split: both organisations with their own facts again, with their original IDs
        assertEquals(3L, count(r, "SELECT count(*) FROM kg_entity WHERE status = 1"));
        assertEquals(1L, count(r, "SELECT count(*) FROM kg_entity WHERE status = 1 AND public_id = '" + a + "'"));
        assertEquals(1L, count(r, "SELECT count(*) FROM kg_entity WHERE status = 1 AND public_id = '" + b + "'"));
        assertEquals(0L, count(r, "SELECT count(*) FROM kg_entity WHERE status = 2"));
        assertEquals("a stored ID still leads to its entity", a, string(r, "SELECT e.public_id FROM kg_entity_redirect d"
                + " JOIN kg_entity e ON e.ent_rowid = d.target_rowid WHERE d.public_id = '" + EARLIER + "'"));
        assertEquals("only the unknown ID is redirected", 1L, count(r, "SELECT count(*) FROM kg_entity_redirect"));
        assertNotEquals("export consumers sync again", epoch, r.store().epoch());
        final JSONObject st = r.status().getJSONObject("rebuild");
        assertEquals(3L, st.getJSONObject("verify").getLong("documentsAfter"));
        assertFalse(st.getJSONObject("verify").getBoolean("brake"));
        final File kept = new File(this.data, KgPaths.RELATIVE_DIR + "/backup/" + st.getString("keptAs"));
        assertTrue(kept.getName().endsWith(KgBackup.BEFORE_REBUILD + ".db"));
        assertEquals("the previous graph with its wrong merge", 2L,
                KgBackup.verify(kept, KgSchema.CURRENT_VERSION).getJSONObject("counts").getLong("entities"));
        assertFalse("the shadow is gone", new File(this.data, KgPaths.RELATIVE_DIR + "/rebuild").exists());
        // the graph follows Solr again after the swap
        client().add(doc("Z00000host99", "www.neu.de", org("Neu GmbH", "DE999999999")));
        client().commit();
        settle(r);
        assertEquals(4L, count(r, "SELECT count(*) FROM kg_entity WHERE status = 1"));
    }

    /**
     * The derived layer is rebuilt as well: the shadow starts without it, and the finished rebuild (kept for its status)
     * must not keep it from running (found by the package 6 measurement: no derived rows after a rebuild).
     */
    @Test
    public void theDerivedLayerRunsAgainAfterARebuild() throws Exception {
        final KgRuntime r = start();
        // the operator's home page names it (processed after the homes): both homes' parent organisation is the site's operator
        client().add(doc("HOME00host01", "www.traeger.de", "{\"@type\":\"Organization\",\"name\":\"Träger gGmbH\","
                + "\"url\":\"https://www.traeger.de/\"}"));
        for (final String house : new String[] {"Birke", "Eiche"}) {
            client().add(doc("H" + house.toUpperCase(java.util.Locale.ROOT).substring(0, 4) + "0host01", "www.traeger.de", "{\"@type\":"
                    + "\"NursingHome\",\"name\":\"Haus " + house + "\",\"address\":{\"streetAddress\":\"" + house + "nweg 1\",\"postalCode\":"
                    + "\"10115\",\"addressLocality\":\"Berlin\"},\"parentOrganization\":{\"@type\":\"Organization\",\"name\":\"Träger gGmbH\"}}"));
        }
        client().commit();
        settle(r);
        drive(r, () -> count(r, "SELECT count(*) FROM kg_derived") > 0);
        assertEquals("the two homes of one operator", 1L, count(r, "SELECT count(*) FROM kg_derived WHERE kind = 2"));
        r.rebuild();
        drive(r, () -> "done".equals(phase(r)));
        settle(r);
        drive(r, () -> count(r, "SELECT count(*) FROM kg_derived WHERE kind = 2") == 1L);
    }

    @Test
    public void aRebuildReadsEveryScanPageBeforeTheSwap() throws Exception {
        final KgRuntime r = start();
        final int n = 1100; // more than one page of the backfill (Reconciler.PAGE)
        index(r, n);
        assertEquals(n, count(r, "SELECT count(*) FROM kg_doc WHERE state = 1"));
        final long entities = count(r, "SELECT count(*) FROM kg_entity WHERE status = 1");
        r.rebuild();
        drive(r, () -> "done".equals(phase(r)));
        final JSONObject v = r.status().getJSONObject("rebuild").getJSONObject("verify");
        assertEquals(n, v.getLong("documentsAfter"));
        assertEquals(entities, v.getLong("entitiesAfter"));
        assertEquals(0L, r.status().getJSONObject("rebuild").getLong("idsRedirected"));
    }

    @Test
    public void aPausedGraphPausesTheRebuildAndTheResumeFinishesIt() throws Exception {
        final KgRuntime r = start();
        index(r, 3);
        r.pause();
        r.rebuild();
        for (int i = 0; i < 40; i++) {
            r.tick();
            r.syncTick();
            this.clock.addAndGet(3000L);
            Thread.sleep(10);
        }
        final JSONObject rb = r.status().getJSONObject("rebuild");
        assertEquals(rb.toString(), "building", rb.optString("phase"));
        assertTrue(rb.toString(), rb.optBoolean("paused"));
        final JSONObject progress = rb.optJSONObject("progress");
        assertTrue("the shadow publishes nothing while the graph is paused: " + rb,
                progress == null || progress.optLong("published", 0L) == 0L);
        r.resume();
        drive(r, () -> "done".equals(phase(r)));
        assertEquals(3L, count(r, "SELECT count(*) FROM kg_entity WHERE status = 1"));
        assertEquals("0", string(r, "SELECT value FROM kg_meta WHERE key = '" + KgSchema.META_MANUAL_PAUSE + "'"));
    }

    /** The knowledge prompt belongs to the graph (6.3): a rebuild keeps the active version and its history. */
    @Test
    public void aRebuildKeepsTheKnowledgePrompt() throws Exception {
        final KgRuntime r = start();
        index(r, 2);
        final String custom = net.yacy.scoutro.knowledge.extract.LlmExtractor.SYSTEM_PROMPT.replace("for a knowledge graph",
                "for a knowledge graph of care providers");
        final JSONObject active = r.promptActivate(custom, 0);
        r.rebuild();
        drive(r, () -> "done".equals(phase(r)));
        final JSONObject after = r.prompt();
        assertEquals(1, after.getInt("activeVersion"));
        assertEquals("custom", after.getString("source"));
        assertEquals(active.getString("activeHash"), after.getString("activeHash"));
        assertEquals(custom, after.getString("text"));
        assertEquals(1, after.getJSONArray("history").length());
    }

    @Test
    public void cancelDeletesTheShadowAndTheGraphStaysAsItIs() throws Exception {
        final KgRuntime r = start();
        index(r, 2);
        final String epoch = r.store().epoch();
        r.rebuild();
        r.rebuildCancel();
        assertEquals("cancelled", phase(r));
        assertFalse(new File(this.data, KgPaths.RELATIVE_DIR + "/rebuild").exists());
        assertEquals(epoch, r.store().epoch());
        assertEquals(2L, count(r, "SELECT count(*) FROM kg_entity WHERE status = 1"));
        try {
            r.rebuildCancel();
            fail("nothing to cancel");
        } catch (final KgException e) {
            assertEquals(KgException.NO_REBUILD, e.code());
        }
        try {
            r.rebuildConfirm();
            fail("nothing to confirm");
        } catch (final KgException e) {
            assertEquals(KgException.NO_REBUILD, e.code());
        }
    }

    @Test
    public void theBrakeWaitsForAConfirmationWhenSolrLostDocuments() throws Exception {
        final KgRuntime r = start(KgConfig.RECONCILE_BRAKE_MIN_DOCS, "2", KgConfig.RECONCILE_MAX_DELETE_FRACTION, "0.2");
        index(r, 6);
        // Solr loses four documents; the graph has not seen it yet (its sync is not driven meanwhile)
        for (int i = 0; i < 4; i++) {
            client().deleteById(String.format("D%05dhost%02d", i, i));
        }
        client().commit();
        r.rebuild();
        final long until = System.currentTimeMillis() + 120_000L;
        while (!"awaiting_confirmation".equals(phase(r))) {
            assertTrue(r.status().getJSONObject("rebuild").toString(), System.currentTimeMillis() < until);
            this.clock.addAndGet(3000L);
            Thread.sleep(10);
        }
        final JSONObject v = r.status().getJSONObject("rebuild").getJSONObject("verify");
        assertTrue(v.getBoolean("brake"));
        assertEquals(6L, v.getLong("documentsBefore"));
        assertEquals(2L, v.getLong("documentsAfter"));
        assertEquals("nothing swapped yet", 6L, count(r, "SELECT count(*) FROM kg_doc WHERE state = 1"));
        r.rebuildConfirm();
        drive(r, () -> "done".equals(phase(r)));
        settle(r);
        assertEquals(2L, count(r, "SELECT count(*) FROM kg_doc WHERE state = 1"));
    }

    @Test
    public void noRebuildWhileAReconcileWaitsForItsConfirmation() throws Exception {
        final KgRuntime r = start(KgConfig.RECONCILE_BRAKE_MIN_DOCS, "2", KgConfig.RECONCILE_MAX_DELETE_FRACTION, "0.2");
        index(r, 6);
        // deleted without the capture: only a reconcile finds out, and its brake stops it
        final net.yacy.scoutro.knowledge.sync.DirtySet dirty = net.yacy.scoutro.knowledge.sync.Capture.active();
        net.yacy.scoutro.knowledge.sync.Capture.deactivate(dirty);
        try {
            for (int i = 0; i < 4; i++) {
                client().deleteById(String.format("D%05dhost%02d", i, i));
            }
            client().commit();
        } finally {
            net.yacy.scoutro.knowledge.sync.Capture.activate(dirty);
        }
        r.reconcile();
        drive(r, () -> {
            final JSONObject rec = r.status().optJSONObject("sync").optJSONObject("reconcile");
            return rec != null && rec.optBoolean("awaitingConfirmation");
        });
        try {
            r.rebuild();
            fail("a rebuild while a reconcile waits");
        } catch (final KgException e) {
            assertEquals(KgException.OPERATION_RUNNING, e.code());
            assertEquals("reconcile_busy", e.reason());
        }
        assertFalse(new File(this.data, KgPaths.RELATIVE_DIR + "/rebuild").exists());
        assertEquals("none", phase(r));
    }

    @Test
    public void preconditionsAndALeftoverShadow() throws Exception {
        // without the Solr sync there is nothing to rebuild from
        final KgRuntime plain = new KgRuntime(KgTestSupport.env(this.tmp.newFolder("plain"), KgTestSupport.enabled(), new KgTestSupport.Probe()));
        plain.open();
        try {
            plain.rebuild();
            fail("rebuild without the sync");
        } catch (final KgException e) {
            assertEquals(KgException.SYNC_UNAVAILABLE, e.code());
        } finally {
            plain.close();
        }
        // not enough budget for the shadow: a large backup file fills it
        KgRuntime r = start(KgConfig.BUDGET_MAX_BYTES, Long.toString(160L * 1024 * 1024));
        index(r, 1);
        final File backup = new File(this.data, KgPaths.RELATIVE_DIR + "/backup/graph-20300101T000000Z.db");
        backup.getParentFile().mkdirs();
        try (RandomAccessFile f = new RandomAccessFile(backup, "rw")) {
            f.setLength(100L * 1024 * 1024);
        }
        r.tick();
        this.clock.addAndGet(60_000L);
        r.tick(); // measures the backup directory
        try {
            r.rebuild();
            fail("rebuild without room");
        } catch (final KgException e) {
            assertEquals(KgException.WRITE_REFUSED, e.code());
            assertEquals("rebuild_space", e.reason());
        }
        assertTrue(backup.delete());
        // a stop in the middle deletes the shadow; a shadow left behind by a crash is deleted at the next start
        r.tick();
        this.clock.addAndGet(60_000L);
        r.tick();
        r.rebuild();
        KgRuntime.stop();
        assertFalse(new File(this.data, KgPaths.RELATIVE_DIR + "/rebuild").exists());
        final File leftover = new File(this.data, KgPaths.RELATIVE_DIR + "/rebuild/graph.db");
        leftover.getParentFile().mkdirs();
        assertTrue(leftover.createNewFile());
        r = start();
        assertEquals("interrupted", phase(r));
        assertFalse(leftover.getParentFile().exists());
        assertEquals(1L, count(r, "SELECT count(*) FROM kg_entity WHERE status = 1"));
    }
}
