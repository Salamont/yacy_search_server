package net.yacy.scoutro.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class KgConfigTest {

    @Test
    public void defaultsAreDisabledAndValid() {
        final KgConfig c = KgConfig.read(key -> null);
        assertFalse(c.enabled);
        assertTrue(c.problems().toString(), c.valid());
        assertEquals(1024L * 1024L * 1024L, c.budgetMaxBytes);
        assertEquals(90, c.pausePercent);
        assertEquals(80, c.resumePercent);
        assertFalse(c.jsonldEnabled);
        // the maintenance share must hold the WAL limit plus the temp limit
        assertTrue(c.maintenanceBytes() >= c.walMaxBytes + c.tmpMaxBytes);
        assertEquals(c.budgetMaxBytes, c.dataBytes() + c.maintenanceBytes());
    }

    @Test
    public void yacyDiskThresholdsAreMegabytes() {
        final Map<String, String> m = new HashMap<>();
        m.put("resource.disk.free.min.steadystate", "4096");
        m.put("resource.disk.free.min.undershot", "2048");
        m.put(KgConfig.DISK_RESERVE_BYTES, Long.toString(KgTestSupport.GIB));
        final KgConfig c = KgTestSupport.config(m);
        assertEquals(4096L * KgTestSupport.MIB, c.yacySteadyStateBytes);
        assertEquals(5120L * KgTestSupport.MIB, c.growthFloorBytes());
        assertEquals(2048L * KgTestSupport.MIB, c.criticalFloorBytes());
        assertEquals(c.growthFloorBytes() + c.diskHysteresisBytes, c.growthResumeFloorBytes());
    }

    @Test
    public void invalidValuesAreReportedNotSilentlyUsed() {
        final KgConfig c = KgTestSupport.config(KgTestSupport.enabled(
                KgConfig.BUDGET_MAX_BYTES, "12",
                KgConfig.BUDGET_PAUSE_PERCENT, "ninety",
                KgConfig.JSONLD_ENABLED, "yes"));
        assertFalse(c.valid());
        final String p = c.problems().toString();
        assertTrue(p, p.contains(KgConfig.BUDGET_MAX_BYTES));
        assertTrue(p, p.contains(KgConfig.BUDGET_PAUSE_PERCENT));
        assertTrue(p, p.contains(KgConfig.JSONLD_ENABLED));
    }

    @Test
    public void resumeMustBeBelowPause() {
        final KgConfig c = KgTestSupport.config(KgTestSupport.enabled(
                KgConfig.BUDGET_PAUSE_PERCENT, "80", KgConfig.BUDGET_RESUME_PERCENT, "80"));
        assertFalse(c.valid());
        assertTrue(c.problems().toString().contains(KgConfig.BUDGET_RESUME_PERCENT));
    }

    @Test
    public void checkpointThresholdMustBeBelowWalLimit() {
        final KgConfig c = KgTestSupport.config(KgTestSupport.enabled(
                KgConfig.WAL_MAX_BYTES, Long.toString(8 * KgTestSupport.MIB),
                KgConfig.WAL_CHECKPOINT_BYTES, Long.toString(8 * KgTestSupport.MIB)));
        assertFalse(c.valid());
        assertTrue(c.problems().toString().contains(KgConfig.WAL_CHECKPOINT_BYTES));
    }

    @Test
    public void maintenanceShareMustHoldWalAndTemp() {
        // 64 MiB budget, 5 % maintenance = 3.2 MiB < 4 MiB WAL + 4 MiB temp
        final KgConfig c = KgTestSupport.config(KgTestSupport.enabled(
                KgConfig.BUDGET_MAX_BYTES, Long.toString(64 * KgTestSupport.MIB),
                KgConfig.BUDGET_MAINTENANCE_PERCENT, "5",
                KgConfig.WAL_MAX_BYTES, Long.toString(4 * KgTestSupport.MIB),
                KgConfig.WAL_CHECKPOINT_BYTES, Long.toString(KgTestSupport.MIB),
                KgConfig.TMP_MAX_BYTES, Long.toString(4 * KgTestSupport.MIB)));
        assertFalse(c.valid());
        assertTrue(c.problems().toString().contains(KgConfig.BUDGET_MAINTENANCE_PERCENT));
    }

    @Test
    public void thresholdsFollowTheBudget() {
        final KgConfig c = KgTestSupport.config(KgTestSupport.enabled(
                KgConfig.BUDGET_MAX_BYTES, Long.toString(1000 * KgTestSupport.MIB)));
        assertTrue(c.valid());
        assertEquals(1000 * KgTestSupport.MIB / 100 * 90, c.pauseAtBytes());
        assertEquals(1000 * KgTestSupport.MIB / 100 * 80, c.resumeAtBytes());
        assertEquals(1000 * KgTestSupport.MIB / 100 * 20, c.maintenanceBytes());
    }
}
