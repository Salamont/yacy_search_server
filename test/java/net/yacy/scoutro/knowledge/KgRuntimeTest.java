package net.yacy.scoutro.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

public class KgRuntimeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static boolean threadAlive(final String name) {
        for (final Thread t : Thread.getAllStackTraces().keySet()) {
            if (name.equals(t.getName()) && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static boolean monitorThreadAlive() {
        return threadAlive(KgRuntime.WATCHDOG_THREAD) || threadAlive(KgRuntime.MAINTENANCE_THREAD);
    }

    /** Counts until it is interrupted; never returns a row on its own. */
    private static final String ENDLESS = "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n) SELECT count(*) FROM n";

    private static void waitFor(final String what, final BooleanSupplier condition, final long millis) throws InterruptedException {
        final long until = System.currentTimeMillis() + millis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    private static String refusal(final KgRuntime r, final WriteClass c) {
        try {
            r.store().write(c, 1024, tx -> {
                KgStore.putMeta(tx, "probe_" + c.name().toLowerCase(), "1");
                return null;
            });
            return null;
        } catch (final KgException e) {
            assertEquals(e.getMessage(), KgException.WRITE_REFUSED, e.code());
            return e.reason();
        }
    }

    private static String reasons(final KgRuntime r) throws Exception {
        return r.status().getJSONObject("storage").getJSONArray("reasons").toString();
    }

    private static JSONObject integrity(final KgRuntime r) throws Exception {
        return r.status().getJSONObject("store").getJSONObject("integrity");
    }

    /** Opens and "crashes" a graph so that the next start is unclean. */
    private void crashOnce() {
        final KgRuntime crashed = runtime(KgTestSupport.enabled(), false);
        crashed.open();
        assertEquals(KgRuntime.State.RUNNING, crashed.state());
        crashed.store().close(); // the process dies: no clean-shutdown mark
    }

    private KgRuntime runtime(final Map<String, String> settings, final boolean monitor) {
        return new KgRuntime(new KgRuntime.Env(this.tmp.getRoot(), settings::get, System::currentTimeMillis,
                new KgTestSupport.Probe(), KgStore.SQLITE, monitor));
    }

    @Test
    public void disabledByDefaultCreatesNothing() throws Exception {
        final KgRuntime r = runtime(new HashMap<>(), true);
        r.open();
        assertEquals(KgRuntime.State.DISABLED, r.state());
        assertFalse("no graph directory while disabled", new File(this.tmp.getRoot(), "DATA").exists());
        assertFalse("no background thread while disabled", monitorThreadAlive());
        final JSONObject s = r.status();
        assertEquals("disabled", s.optString("state"));
        assertFalse(s.optBoolean("enabled", true));
        assertFalse(s.has("storage"));
        try {
            r.pause();
            fail();
        } catch (final KgException e) {
            assertEquals(KgException.DISABLED, e.code());
        }
        r.close();
        assertFalse(new File(this.tmp.getRoot(), "DATA").exists());
    }

    @Test
    public void invalidSettingsKeepTheGraphOffWithAReason() throws Exception {
        final KgRuntime r = runtime(KgTestSupport.enabled(KgConfig.BUDGET_PAUSE_PERCENT, "200"), false);
        r.open();
        assertEquals(KgRuntime.State.UNAVAILABLE, r.state());
        assertEquals(KgException.CONFIG_INVALID, r.reason());
        assertFalse(new File(this.tmp.getRoot(), "DATA").exists());
        assertFalse(r.status().getJSONObject("config").optBoolean("valid", true));
    }

    @Test
    public void nativeLibraryFailureDoesNotStopScoutro() throws Exception {
        final KgRuntime r = new KgRuntime(new KgRuntime.Env(this.tmp.getRoot(), KgTestSupport.enabled()::get,
                System::currentTimeMillis, new KgTestSupport.Probe(), db -> {
                    throw new UnsatisfiedLinkError("no sqlitejdbc in java.library.path");
                }, false));
        r.open(); // must not throw
        assertEquals(KgRuntime.State.UNAVAILABLE, r.state());
        assertEquals(KgException.NATIVE_LIBRARY_UNAVAILABLE, r.reason());
        assertEquals("unavailable", r.status().optString("state"));
        r.close();
    }

    @Test
    public void nativeLibraryNotFoundInsideAnSqlExceptionIsRecognised() throws Exception {
        // this is how sqlite-jdbc reports a native library it cannot extract or load
        final KgRuntime r = new KgRuntime(new KgRuntime.Env(this.tmp.getRoot(), KgTestSupport.enabled()::get,
                System::currentTimeMillis, new KgTestSupport.Probe(), db -> {
                    throw new java.sql.SQLException("Error opening connection",
                            new org.sqlite.NativeLibraryNotFoundException("No native library found for os.name=Linux"));
                }, false));
        r.open();
        assertEquals(KgRuntime.State.UNAVAILABLE, r.state());
        assertEquals(KgException.NATIVE_LIBRARY_UNAVAILABLE, r.reason());
        r.close();
    }

    @Test
    public void runsWithMonitorAndStopsCleanly() throws Exception {
        final KgRuntime r = runtime(KgTestSupport.enabled(), true);
        r.open();
        try {
            assertEquals(KgRuntime.State.RUNNING, r.state());
            assertTrue("watchdog runs", threadAlive(KgRuntime.WATCHDOG_THREAD));
            assertTrue("maintenance runs", threadAlive(KgRuntime.MAINTENANCE_THREAD));
            final KgPaths paths = new KgPaths(this.tmp.getRoot());
            assertTrue(paths.db.isFile());
            final JSONObject s = r.status();
            assertEquals(KgRuntime.STATUS_SCHEMA, s.optString("schema"));
            assertEquals("running", s.optString("state"));
            assertTrue(s.getJSONObject("storage").optBoolean("growthAllowed"));
            assertNotNull(s.getJSONObject("storage").optJSONObject("pages"));
            assertNotNull(s.getJSONObject("storage").optJSONObject("readers"));
            assertEquals(KgSchema.CURRENT_VERSION, s.getJSONObject("store").optInt("schemaVersion"));
            assertFalse(s.getJSONObject("store").optBoolean("uncleanStartDetected"));
            assertEquals("not_required", s.getJSONObject("store").getJSONObject("integrity").optString("state"));
            assertEquals("off", s.getJSONObject("jsonld").optString("state"));
        } finally {
            r.close();
        }
        assertEquals(KgRuntime.State.STOPPED, r.state());
        assertFalse("both threads end with close()", monitorThreadAlive());
        final KgRuntime again = runtime(KgTestSupport.enabled(), false);
        again.open();
        assertFalse("a clean stop is not reported as a crash", again.uncleanStartDetected());
        again.close();
    }

    @Test
    public void uncleanShutdownIsDetected() throws Exception {
        final KgRuntime crashed = runtime(KgTestSupport.enabled(), false);
        crashed.open();
        assertEquals(KgRuntime.State.RUNNING, crashed.state());
        // the process dies: the store is closed without the clean-shutdown mark
        crashed.store().close();
        final KgRuntime next = runtime(KgTestSupport.enabled(), false);
        next.open();
        try {
            assertTrue(next.uncleanStartDetected());
            assertEquals("1", next.store().read(c -> KgStore.getMeta(c, KgSchema.META_RECONCILE_REQUIRED)));
            assertTrue(next.status().getJSONObject("store").optBoolean("uncleanStartDetected"));
            next.tick(); // runs the quick_check after an unclean start
            final JSONObject integrity = next.status().getJSONObject("store").getJSONObject("integrity");
            assertEquals("ok", integrity.optString("state"));
            assertEquals("ok", integrity.optString("result"));
            assertEquals(KgRuntime.TRIGGER_UNCLEAN_START, integrity.optString("trigger"));
            assertFalse(integrity.optBoolean("blocksGraphWrites", true));
        } finally {
            next.close();
        }
    }

    @Test
    public void startOnACriticallyFullDiskRunsReadOnlyAndRecordsTheStartLater() throws Exception {
        final KgRuntime first = runtime(KgTestSupport.enabled(), false);
        first.open();
        first.close();
        final KgTestSupport.Probe probe = new KgTestSupport.Probe();
        probe.usable.set(100L * KgTestSupport.MIB); // below YaCy's undershot: every write is refused
        final KgRuntime r = new KgRuntime(new KgRuntime.Env(this.tmp.getRoot(), KgTestSupport.enabled()::get,
                System::currentTimeMillis, probe, KgStore.SQLITE, false));
        r.open();
        try {
            assertEquals("the graph starts, read-only", KgRuntime.State.RUNNING, r.state());
            assertFalse(r.startRecorded());
            final JSONObject s = r.status();
            assertFalse(s.getJSONObject("store").optBoolean("startRecorded", true));
            assertTrue(s.getJSONObject("storage").getJSONArray("reasons").toString().contains("disk_critical"));
            assertEquals("1", r.store().read(c -> KgStore.getMeta(c, KgSchema.META_CLEAN_SHUTDOWN)));
            // space comes back: the monitor records the start
            probe.usable.set(100L * KgTestSupport.GIB);
            r.tick();
            assertTrue(r.startRecorded());
            assertEquals("0", r.store().read(c -> KgStore.getMeta(c, KgSchema.META_CLEAN_SHUTDOWN)));
        } finally {
            r.close();
        }
    }

    @Test
    public void manualPauseIsPersistedAndKeepsMaintenance() throws Exception {
        final KgRuntime r = runtime(KgTestSupport.enabled(), false);
        r.open();
        final JSONObject paused = r.pause();
        assertFalse(paused.getJSONObject("storage").optBoolean("growthAllowed", true));
        assertTrue(paused.getJSONObject("storage").optBoolean("maintenanceAllowed", false));
        assertTrue(paused.getJSONObject("store").optBoolean("manualPause"));
        r.close();
        final KgRuntime again = runtime(KgTestSupport.enabled(), false);
        again.open();
        try {
            assertTrue("pause survives a restart", again.guard().manualPause());
            final JSONObject resumed = again.resume();
            assertTrue(resumed.getJSONObject("storage").optBoolean("growthAllowed"));
            assertNull(again.status().getJSONObject("storage").getJSONArray("reasons").optJSONObject(0));
        } finally {
            again.close();
        }
    }

    @Test
    public void uncleanStartHoldsGraphWritesUntilTheIntegrityCheckPasses() throws Exception {
        crashOnce();
        final KgRuntime r = runtime(KgTestSupport.enabled(), false);
        r.open();
        try {
            assertEquals(KgRuntime.Integrity.PENDING, r.integrity());
            assertEquals(StorageGuard.INTEGRITY_PENDING, refusal(r, WriteClass.GROWTH));
            assertEquals("deletions wait too", StorageGuard.INTEGRITY_PENDING, refusal(r, WriteClass.MAINTENANCE));
            assertNull("the runtime's own records are written", refusal(r, WriteClass.SYSTEM));
            assertTrue(r.startRecorded());
            assertTrue(reasons(r), reasons(r).contains(StorageGuard.INTEGRITY_PENDING));
            assertTrue(integrity(r).optBoolean("blocksGraphWrites"));
            assertEquals("1", r.store().read(c -> KgStore.getMeta(c, KgSchema.META_INTEGRITY_REQUIRED)));
            r.tick();
            assertEquals(KgRuntime.Integrity.OK, r.integrity());
            assertNull(refusal(r, WriteClass.GROWTH));
            assertNull(refusal(r, WriteClass.MAINTENANCE));
            assertFalse(reasons(r), reasons(r).contains("integrity"));
            assertEquals("0", r.store().read(c -> KgStore.getMeta(c, KgSchema.META_INTEGRITY_REQUIRED)));
        } finally {
            r.close();
        }
        final KgRuntime again = runtime(KgTestSupport.enabled(), false);
        again.open();
        try {
            assertEquals(KgRuntime.Integrity.NOT_REQUIRED, again.integrity());
            assertNull(refusal(again, WriteClass.GROWTH));
        } finally {
            again.close();
        }
    }

    @Test
    public void watchdogAbortsAnOverlongIntegrityCheckAndReadsStayBoundedMeanwhile() throws Exception {
        crashOnce();
        final AtomicReference<KgStore.SqlWork<String>> check = new AtomicReference<>(c -> KgStore.queryString(c, ENDLESS));
        final KgRuntime r = new KgRuntime(new KgRuntime.Env(this.tmp.getRoot(), KgTestSupport.enabled(
                KgConfig.INTEGRITY_MAX_MILLIS, "1500", KgConfig.READ_MAX_TRANSACTION_MILLIS, "300")::get,
                System::currentTimeMillis, new KgTestSupport.Probe(), KgStore.SQLITE, true)
                .withIntegrityCheck(c -> check.get().run(c)));
        r.open();
        try {
            waitFor("the check to start", () -> r.integrity() == KgRuntime.Integrity.RUNNING, 5000);
            // the maintenance thread is busy with the check: the watchdog still ends other reads on time
            final long t0 = System.currentTimeMillis();
            try {
                r.store().read(c -> KgStore.queryLong(c, ENDLESS));
                fail("an endless read must be interrupted");
            } catch (final KgException e) {
                assertEquals(KgException.READ_TIMEOUT, e.code());
            }
            final long took = System.currentTimeMillis() - t0;
            assertTrue("read interrupted after " + took + " ms", took >= 300 && took < 300 + 4 * KgRuntime.WATCHDOG_MILLIS);
            assertEquals("the check is still running", KgRuntime.Integrity.RUNNING, r.integrity());
            assertEquals(StorageGuard.INTEGRITY_PENDING, refusal(r, WriteClass.GROWTH));

            waitFor("the check to be aborted", () -> r.integrity() == KgRuntime.Integrity.ABORTED, 10_000);
            final JSONObject integrity = integrity(r);
            final long ran = integrity.getLong("finishedAt") - integrity.getLong("startedAt");
            assertTrue("aborted after " + ran + " ms", ran >= 1500 && ran < 1500 + 4 * KgRuntime.WATCHDOG_MILLIS);
            assertEquals(KgException.READ_TIMEOUT, integrity.optString("error"));
            assertTrue(integrity.optBoolean("blocksGraphWrites"));
            assertTrue(reasons(r), reasons(r).contains(StorageGuard.INTEGRITY_FAILED));
            assertTrue(reasons(r), reasons(r).contains("aborted: read_timeout"));
            assertEquals(StorageGuard.INTEGRITY_FAILED, refusal(r, WriteClass.GROWTH));
            assertEquals(StorageGuard.INTEGRITY_FAILED, refusal(r, WriteClass.MAINTENANCE));
            assertNull(refusal(r, WriteClass.SYSTEM));
            assertEquals("1", r.store().read(c -> KgStore.getMeta(c, KgSchema.META_INTEGRITY_REQUIRED)));

            // resume asks for a new check; this time quick_check runs and passes
            check.set(KgRuntime.QUICK_CHECK);
            final JSONObject resumed = r.resume();
            assertEquals(KgRuntime.TRIGGER_RESUME, resumed.getJSONObject("store").getJSONObject("integrity").optString("trigger"));
            waitFor("the new check to pass", () -> r.integrity() == KgRuntime.Integrity.OK, 10_000);
            assertNull(refusal(r, WriteClass.GROWTH));
            assertEquals("0", r.store().read(c -> KgStore.getMeta(c, KgSchema.META_INTEGRITY_REQUIRED)));
        } finally {
            r.close();
        }
        assertFalse(monitorThreadAlive());
    }

    @Test
    public void abortedCheckStaysRequiredAfterACleanStop() throws Exception {
        crashOnce();
        final KgRuntime r = new KgRuntime(KgTestSupport.env(this.tmp.getRoot(),
                KgTestSupport.enabled(KgConfig.INTEGRITY_MAX_MILLIS, "300"), new KgTestSupport.Probe())
                .withIntegrityCheck(c -> {
                    throw new java.sql.SQLException("interrupted", null, 9); // as an interrupted quick_check reports it
                }));
        r.open();
        r.tick();
        assertEquals(KgRuntime.Integrity.ABORTED, r.integrity());
        r.close(); // clean stop: the flag stays
        final KgRuntime next = runtime(KgTestSupport.enabled(), false);
        next.open();
        try {
            assertFalse(next.uncleanStartDetected());
            assertEquals(KgRuntime.Integrity.PENDING, next.integrity());
            assertEquals(KgRuntime.TRIGGER_INCOMPLETE_CHECK, integrity(next).optString("trigger"));
            assertEquals(StorageGuard.INTEGRITY_PENDING, refusal(next, WriteClass.GROWTH));
            next.tick();
            assertEquals(KgRuntime.Integrity.OK, next.integrity());
        } finally {
            next.close();
        }
    }

    @Test
    public void failedIntegrityCheckStopsEveryWriteWithAVisibleReason() throws Exception {
        crashOnce();
        final KgRuntime r = new KgRuntime(KgTestSupport.env(this.tmp.getRoot(), KgTestSupport.enabled(), new KgTestSupport.Probe())
                .withIntegrityCheck(c -> "row 7 missing from index kg_entity_key_value"));
        r.open();
        try {
            r.tick();
            assertEquals(KgRuntime.Integrity.FAILED, r.integrity());
            final String reasons = reasons(r);
            assertTrue(reasons, reasons.contains(StorageGuard.STORAGE_ERROR));
            assertTrue(reasons, reasons.contains(StorageGuard.INTEGRITY_FAILED));
            assertTrue(reasons, reasons.contains("row 7 missing"));
            assertEquals(StorageGuard.STORAGE_ERROR, refusal(r, WriteClass.SYSTEM));
            // the manual pause still takes effect; it is stored once writes are possible again
            final JSONObject paused = r.pause();
            assertTrue(paused.getJSONObject("store").optBoolean("manualPause"));
            assertFalse(paused.getJSONObject("store").optBoolean("manualPauseSaved", true));
        } finally {
            r.close();
        }
        // the clean-shutdown mark could not be written: the next start checks again
        final KgRuntime next = runtime(KgTestSupport.enabled(), false);
        next.open();
        try {
            assertTrue(next.uncleanStartDetected());
            next.tick();
            assertEquals(KgRuntime.Integrity.OK, next.integrity());
        } finally {
            next.close();
        }
    }

    @Test
    public void leaseThatExpiresBetweenTwoStatementsCannotStartTheNextOne() throws Exception {
        final KgRuntime r = runtime(KgTestSupport.enabled(KgConfig.READ_MAX_TRANSACTION_MILLIS, "300"), true);
        r.open();
        try {
            final AtomicLong interruptedBefore = new AtomicLong(-1);
            final long t0 = System.currentTimeMillis();
            try {
                r.store().read(c -> {
                    KgStore.queryLong(c, "SELECT count(*) FROM kg_meta");
                    // no statement runs while the lease expires; the watchdog's interrupt finds nothing to stop
                    final long until = System.currentTimeMillis() + 5000;
                    while (r.store().readerStatus(System.currentTimeMillis()).optLong("interrupted") == 0
                            && System.currentTimeMillis() < until) {
                        try {
                            Thread.sleep(20);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    interruptedBefore.set(r.store().readerStatus(System.currentTimeMillis()).optLong("interrupted"));
                    return KgStore.queryLong(c, ENDLESS); // must not start
                });
                fail("the statement after the deadline must be refused");
            } catch (final KgException e) {
                assertEquals(KgException.READ_TIMEOUT, e.code());
            }
            assertEquals("the watchdog marked the lease before the second statement", 1L, interruptedBefore.get());
            assertTrue(System.currentTimeMillis() - t0 < 3000);
            // the reader is clean for the next lease
            assertEquals("0", r.store().read(c -> KgStore.getMeta(c, KgSchema.META_CLEAN_SHUTDOWN)));
        } finally {
            r.close();
        }
    }
}
