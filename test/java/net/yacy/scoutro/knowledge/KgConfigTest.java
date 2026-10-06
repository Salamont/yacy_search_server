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
        // package 5 (O1): protection limits, not targets
        assertEquals(10L * KgTestSupport.GIB, c.budgetMaxBytes);
        assertEquals(2L * KgTestSupport.GIB, c.jsonldMaxTotalBytes);
        assertEquals(70, c.noticePercent);
        assertEquals(80, c.warnPercent);
        assertEquals(90, c.pausePercent);
        assertEquals(80, c.resumePercent);
        assertFalse(c.jsonldEnabled);
        // the maintenance share must hold the WAL limit plus the temp limit
        assertTrue(c.maintenanceBytes() >= c.walMaxBytes + c.tmpMaxBytes);
        assertEquals(c.budgetMaxBytes, c.dataBytes() + c.maintenanceBytes());
        // the database file is capped at the brake: growth is never refused by SQLite before the guard pauses it
        assertEquals(10, c.maintenancePercent);
        assertTrue(Math.abs(c.pauseAtBytes() - c.dataBytes()) < 100);
    }

    @Test
    public void levelsFollowTheBudgetPercentages() {
        final KgConfig c = KgConfig.read(key -> null);
        final long b = 1000L * KgTestSupport.MIB;
        final long p = b / 100L;
        assertEquals(KgConfig.LEVEL_OK, c.level(0L, b));
        assertEquals(KgConfig.LEVEL_OK, c.level(70 * p - 1, b));
        assertEquals(KgConfig.LEVEL_NOTICE, c.level(70 * p, b));
        assertEquals(KgConfig.LEVEL_WARNING, c.level(80 * p, b));
        assertEquals(KgConfig.LEVEL_BRAKE, c.level(90 * p, b));
        assertEquals(KgConfig.LEVEL_BRAKE, c.level(b - 1, b));
        assertEquals(KgConfig.LEVEL_FULL, c.level(b, b));
        assertEquals(KgConfig.LEVEL_OK, c.level(5L, 0L));
        // the order must be notice < warning < pause
        final KgConfig bad = KgTestSupport.config(KgTestSupport.enabled(KgConfig.BUDGET_NOTICE_PERCENT, "85"));
        assertFalse(bad.valid());
        assertTrue(bad.problems().toString().contains(KgConfig.BUDGET_NOTICE_PERCENT));
        final KgConfig bad2 = KgTestSupport.config(KgTestSupport.enabled(KgConfig.BUDGET_WARN_PERCENT, "90"));
        assertFalse(bad2.valid());
        assertTrue(bad2.problems().toString().contains(KgConfig.BUDGET_WARN_PERCENT));
        // a small budget gets the smallest maintenance share that holds the WAL and temp limits
        final KgConfig small = KgTestSupport.config(KgTestSupport.enabled(KgConfig.BUDGET_MAX_BYTES, Long.toString(512 * KgTestSupport.MIB)));
        assertTrue(small.problems().toString(), small.valid());
        assertEquals(26, small.maintenancePercent);
        assertTrue(small.maintenanceBytes() >= small.walMaxBytes + small.tmpMaxBytes);
        // an explicit share that is too small is a problem naming the key
        final KgConfig tooSmall = KgTestSupport.config(KgTestSupport.enabled(KgConfig.BUDGET_MAX_BYTES, Long.toString(512 * KgTestSupport.MIB),
                KgConfig.BUDGET_MAINTENANCE_PERCENT, "10"));
        assertFalse(tooSmall.valid());
        assertTrue(tooSmall.problems().toString().contains(KgConfig.BUDGET_MAINTENANCE_PERCENT));
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
        // the default share is raised from 10 % to the smallest one holding WAL + temp (2 x 64 MiB): 13 %
        assertEquals(13, c.maintenancePercent);
        assertEquals(1000 * KgTestSupport.MIB / 100 * 13, c.maintenanceBytes());
    }

    /** Package 6.1: with *, the per-collection keys of a collection named nowhere else count; with a fixed list they do not. */
    @Test
    public void perCollectionKeysOfNewCollectionsCountUnderStarOnly() {
        final Map<String, String> star = KgTestSupport.enabled(KgConfig.COLLECTIONS, "*", KgConfig.VOCAB_PREFIX + "newportal-web", "software",
                KgConfig.PRICES_STALE_DAYS + ".newportal-web", "30", KgConfig.VOCAB_PREFIX + "bad name", "care");
        final KgConfig withKeys = KgConfig.read(star::get, star.keySet());
        assertTrue(withKeys.valid());
        assertEquals("software", withKeys.vocabOverrides.get("newportal-web"));
        assertEquals(30L * 86_400_000L, withKeys.priceStaleMillis("newportal-web"));
        assertTrue(withKeys.extractionKey().contains("newportal-web=software"));
        assertEquals(java.util.List.of(KgConfig.PRICES_STALE_DAYS + ".newportal-web", KgConfig.VOCAB_PREFIX + "newportal-web"),
                withKeys.perCollectionKeys);
        // without the key list (or with a fixed list) only the named and the Scoutro collections are read, as before
        assertFalse(KgConfig.read(star::get).vocabOverrides.containsKey("newportal-web"));
        final Map<String, String> fixed = new HashMap<>(star);
        fixed.put(KgConfig.COLLECTIONS, "edelsenior-web");
        final KgConfig list = KgConfig.read(fixed::get, fixed.keySet());
        assertFalse(list.vocabOverrides.containsKey("newportal-web"));
        assertTrue(list.perCollectionKeys.isEmpty());
        assertFalse(list.follows("newportal-web"));
        // no vocabulary is guessed from a name: a new collection has none unless mapped
        assertEquals(null, withKeys.vocabularyOf("otherportal-web", java.util.Map.of("edelsenior-web", "care")));
        assertFalse("jobs only when named", withKeys.jobsFor(java.util.List.of("newportal-web")));
    }
}
