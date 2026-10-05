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

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

public class KgRuntimeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static boolean monitorThreadAlive() {
        for (final Thread t : Thread.getAllStackTraces().keySet()) {
            if (KgRuntime.MONITOR_THREAD.equals(t.getName()) && t.isAlive()) {
                return true;
            }
        }
        return false;
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
            assertTrue(monitorThreadAlive());
            final KgPaths paths = new KgPaths(this.tmp.getRoot());
            assertTrue(paths.db.isFile());
            final JSONObject s = r.status();
            assertEquals(KgRuntime.STATUS_SCHEMA, s.optString("schema"));
            assertEquals("running", s.optString("state"));
            assertTrue(s.getJSONObject("storage").optBoolean("growthAllowed"));
            assertNotNull(s.getJSONObject("storage").optJSONObject("pages"));
            assertNotNull(s.getJSONObject("storage").optJSONObject("readers"));
            assertEquals(1, s.getJSONObject("store").optInt("schemaVersion"));
            assertFalse(s.getJSONObject("store").optBoolean("uncleanStartDetected"));
            assertEquals("off", s.getJSONObject("jsonld").optString("state"));
        } finally {
            r.close();
        }
        assertEquals(KgRuntime.State.STOPPED, r.state());
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
            assertEquals("ok", next.status().getJSONObject("store").optString("quickCheck"));
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
}
