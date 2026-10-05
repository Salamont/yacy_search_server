package net.yacy.scoutro.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.sql.Connection;
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
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.Capture;
import net.yacy.scoutro.knowledge.sync.Gates;
import net.yacy.scoutro.knowledge.sync.JsonLdCapture;

/**
 * The runtime with the synchronisation: start and stop through the same
 * static entry points the servlet and the JVM shutdown hook use, restart,
 * reactivation after a disabled period, an unclean start, the control actions
 * and the JSON-LD budget.
 */
public class KgSyncRuntimeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

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

    private KgRuntime.Env env(final Map<String, String> settings) {
        return new KgRuntime.Env(this.data, settings::get, this.clock::get, new KgTestSupport.Probe(), KgStore.SQLITE, false,
                () -> this.solr.getDefaultServer(), Gates.IDLE);
    }

    private static Map<String, String> settings(final String... extra) {
        final String[] all = new String[extra.length + 2];
        all[0] = KgConfig.COLLECTIONS;
        all[1] = "c1";
        System.arraycopy(extra, 0, all, 2, extra.length);
        return KgTestSupport.enabled(all);
    }

    private KgRuntime start(final Map<String, String> settings) {
        KgRuntime.start(env(settings));
        final KgRuntime r = KgRuntime.current();
        assertNotNull(r);
        return r;
    }

    private SolrClient client() {
        return this.solr.getDefaultServer();
    }

    private SolrInputDocument doc(final String id, final String path, final String ldJson) {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", "https://www.muster.de/" + path);
        d.setField("host_s", "www.muster.de");
        d.setField("host_id_s", id.substring(6));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", List.of("c1"));
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        if (ldJson != null) {
            d.setField("ld_json_txt", List.of(ldJson));
        }
        return d;
    }

    private static String org(final String name) {
        return "{\"@type\":\"Organization\",\"name\":\"" + name + "\",\"telephone\":\"030 1234567\"}";
    }

    /** Runs maintenance and sync steps (no threads in tests) until the condition holds. */
    private void drive(final KgRuntime r, final BooleanSupplier done) {
        for (int i = 0; i < 1500; i++) {
            r.tick();
            r.syncTick();
            if (done.getAsBoolean()) {
                return;
            }
            this.clock.addAndGet(3000L);
        }
        fail("not reached: " + r.status());
    }

    private void settle(final KgRuntime r) {
        drive(r, () -> {
            final JSONObject sync = r.status().optJSONObject("sync");
            final JSONObject lag = sync == null ? null : sync.optJSONObject("lag");
            return sync != null && sync.optBoolean("initialized") && lag != null && lag.optLong("pending", 1L) == 0L
                    && !lag.optBoolean("reconcile_pending", true);
        });
    }

    private static long count(final KgRuntime r, final String sql) {
        try {
            return r.store().read(c -> KgStore.queryLong(c, sql));
        } catch (final KgException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean tracked(final KgRuntime r, final String id) {
        return count(r, "SELECT count(*) FROM kg_doc WHERE doc_id = '" + id + "'") > 0L;
    }

    private long countClosed(final String sql) throws Exception {
        try (Connection c = KgStore.SQLITE.open(new KgPaths(this.data).db)) {
            return KgStore.queryLong(c, sql);
        }
    }

    @Test
    public void runtimeFollowsSolrAndReportsTheSync() throws Exception {
        final KgRuntime r = start(settings(KgConfig.JSONLD_ENABLED, "true"));
        assertEquals(KgRuntime.State.RUNNING, r.state());
        assertNotNull("the capture is active while the graph runs", Capture.active());
        client().add(doc("AAAAAAhost01", "", org("Muster Pflege gGmbH")));
        settle(r);
        assertTrue(tracked(r, "AAAAAAhost01"));
        final JSONObject s = r.status();
        assertTrue(s.getJSONObject("sync").getJSONObject("processed").getLong("published") >= 1L);
        assertEquals("active", s.getJSONObject("jsonld").getString("state"));
        assertTrue(s.getJSONObject("jsonld").getBoolean("captureImplemented"));
        assertTrue(JsonLdCapture.isActive());
        KgRuntime.stop();
        assertNull("the capture stops with the graph", Capture.active());
        assertFalse(JsonLdCapture.isActive());
    }

    @Test
    public void stopDrainsPendingChangesAndTheNextStartProcessesAndReconciles() throws Exception {
        KgRuntime r = start(settings());
        client().add(doc("AAAAAAhost01", "", org("A GmbH")));
        client().commit();
        settle(r);
        // recorded by the capture, not yet drained: the stop (servlet destroy or JVM shutdown hook) persists it
        client().add(doc("CCCCCChost01", "c", org("C GmbH")));
        KgRuntime.stop();
        assertEquals(1L, countClosed("SELECT count(*) FROM kg_work WHERE doc_id = 'CCCCCChost01'"));
        assertEquals("1", stringClosed(KgSchema.META_CLEAN_SHUTDOWN));
        // Solr changes while the graph is stopped
        client().add(doc("WWWWWWhost01", "w", org("W GmbH")));
        client().deleteById("AAAAAAhost01");
        client().commit();
        r = start(settings());
        assertTrue(tracked(r, "WWWWWWhost01") == false);
        settle(r);
        assertTrue(tracked(r, "CCCCCChost01"));
        assertTrue("added while stopped", tracked(r, "WWWWWWhost01"));
        assertFalse("deleted while stopped", tracked(r, "AAAAAAhost01"));
        assertEquals("start", stringOf(r, "SELECT reason FROM kg_scan ORDER BY run_id DESC LIMIT 1"));
    }

    private String stringClosed(final String key) throws Exception {
        try (Connection c = KgStore.SQLITE.open(new KgPaths(this.data).db)) {
            return KgStore.getMeta(c, key);
        }
    }

    private static String stringOf(final KgRuntime r, final String sql) throws Exception {
        return r.store().read(c -> KgStore.queryString(c, sql));
    }

    @Test
    public void reactivationAfterADisabledPeriodReconciles() throws Exception {
        KgRuntime r = start(settings());
        client().add(doc("AAAAAAhost01", "", org("A GmbH")));
        client().commit();
        settle(r);
        KgRuntime.stop();
        // disabled: no capture, no thread, no file access
        r = start(KgTestSupport.enabled(KgConfig.ENABLED, "false"));
        assertEquals(KgRuntime.State.DISABLED, r.state());
        assertNull(Capture.active());
        client().add(doc("BBBBBBhost01", "b", org("B GmbH")));
        client().deleteById("AAAAAAhost01");
        client().commit();
        KgRuntime.stop();
        r = start(settings());
        settle(r);
        assertTrue(tracked(r, "BBBBBBhost01"));
        assertFalse(tracked(r, "AAAAAAhost01"));
    }

    @Test
    public void uncleanStartWaitsForTheIntegrityCheckThenCatchesUp() throws Exception {
        KgRuntime r = start(settings());
        client().add(doc("AAAAAAhost01", "", org("A GmbH")));
        client().commit();
        settle(r);
        // the process dies: no stop, no drain, no clean-shutdown mark
        client().add(doc("YYYYYYhost01", "y", org("Y GmbH")));
        client().commit();
        r.store().close();
        Capture.deactivate(Capture.active());
        KgRuntime.forgetForTests();
        r = start(settings());
        final JSONObject before = r.status();
        assertTrue(before.getJSONObject("store").getBoolean("uncleanStartDetected"));
        settle(r);
        assertTrue(tracked(r, "YYYYYYhost01"));
        assertEquals("unclean_start", stringOf(r, "SELECT reason FROM kg_scan ORDER BY run_id DESC LIMIT 1"));
        assertEquals("ok", r.status().getJSONObject("store").getJSONObject("integrity").getString("state"));
    }

    @Test
    public void controlActionsReconcileAndConfirm() throws Exception {
        final KgRuntime r = start(settings());
        settle(r);
        final JSONObject s = r.reconcile();
        assertTrue(s.getJSONObject("sync").getJSONObject("reconcile").getBoolean("pending"));
        assertEquals("admin", s.getJSONObject("sync").getJSONObject("reconcile").getString("reason"));
        try {
            r.confirmReconcile();
            fail("nothing to confirm");
        } catch (final KgException e) {
            assertEquals(KgException.NOTHING_TO_CONFIRM, e.code());
        }
        settle(r);
        KgRuntime.stop();
        // without Solr (the package-1 environment) there is no sync to control
        final KgRuntime plain = new KgRuntime(KgTestSupport.env(this.tmp.newFolder("plain"), settings(), new KgTestSupport.Probe()));
        plain.open();
        try {
            assertEquals("off", plain.status().getJSONObject("sync").getString("state"));
            plain.reconcile();
            fail("no sync");
        } catch (final KgException e) {
            assertEquals(KgException.SYNC_UNAVAILABLE, e.code());
        } finally {
            plain.close();
        }
    }

    @Test
    public void jsonLdBudgetPausesTheCaptureWithoutBlockingIndexing() throws Exception {
        final KgRuntime r = start(settings(KgConfig.JSONLD_ENABLED, "true", KgConfig.JSONLD_MAX_TOTAL_BYTES, Long.toString(KgTestSupport.MIB)));
        assertTrue(JsonLdCapture.isActive());
        final StringBuilder big = new StringBuilder();
        while (big.length() < 120_000) {
            big.append("Pflege und Betreuung. ");
        }
        for (int i = 0; i < 9; i++) {
            client().add(doc("AAAAA" + i + "host01", "p" + i, "{\"@type\":\"Organization\",\"name\":\"Firma " + i
                    + " GmbH\",\"description\":\"" + big + "\"}"));
        }
        settle(r);
        assertTrue(count(r, "SELECT sum(jsonld_bytes) FROM kg_doc") > KgTestSupport.MIB * 9L / 10L);
        this.clock.addAndGet(31_000L);
        r.tick();
        final JSONObject jl = r.status().getJSONObject("jsonld");
        assertEquals("paused", jl.getString("state"));
        assertEquals("jsonld_budget", jl.getString("reason"));
        assertFalse("the parser sees the pause", JsonLdCapture.isActive());
        // indexing goes on: a document of a followed collection is indexed without the field and marked as skipped
        assertNull(JsonLdCapture.field("SSSSSShost01", List.of("c1"), null, false));
        client().add(doc("SSSSSShost01", "s", null));
        settle(r);
        assertTrue(tracked(r, "SSSSSShost01"));
        assertEquals(1L, count(r, "SELECT jsonld_skipped FROM kg_doc WHERE doc_id = 'SSSSSShost01'"));
    }
}
