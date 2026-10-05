package net.yacy.scoutro.knowledge.budget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.JsonLdCapturePolicy.State;

public class JsonLdCapturePolicyTest {

    private static final long MIB = KgTestSupport.MIB;
    private static final long GIB = KgTestSupport.GIB;

    private static JsonLdCapturePolicy policy(final String... settings) {
        return new JsonLdCapturePolicy(KgTestSupport.config(KgTestSupport.enabled(settings)));
    }

    @Test
    public void offUnlessGraphAndCaptureAreEnabledAndRunning() {
        assertEquals(State.OFF, new JsonLdCapturePolicy(KgConfig.read(k -> null)).evaluate(true, 0L, 100 * GIB));
        final JsonLdCapturePolicy p = policy();
        assertEquals(State.OFF, p.evaluate(true, 0L, 100 * GIB));
        final JsonLdCapturePolicy on = policy(KgConfig.JSONLD_ENABLED, "true");
        assertEquals(State.OFF, on.evaluate(false, 0L, 100 * GIB));
        assertEquals(State.ACTIVE, on.evaluate(true, 0L, 100 * GIB));
    }

    @Test
    public void ownBudgetWithHysteresis() {
        final JsonLdCapturePolicy p = policy(KgConfig.JSONLD_ENABLED, "true", KgConfig.JSONLD_MAX_TOTAL_BYTES, Long.toString(100 * MIB));
        assertEquals(State.ACTIVE, p.evaluate(true, 50 * MIB, 100 * GIB));
        assertEquals(State.PAUSED, p.evaluate(true, 91 * MIB, 100 * GIB));
        assertEquals(State.PAUSED, p.evaluate(true, 85 * MIB, 100 * GIB));
        assertEquals(State.ACTIVE, p.evaluate(true, 79 * MIB, 100 * GIB));
        final JSONObject s = p.status();
        assertTrue(s.optBoolean("captureImplemented", false));
        assertEquals(100 * MIB, s.optLong("maxTotalBytes"));
    }

    @Test
    public void diskReservePausesCaptureButNeverTheCrawl() {
        final JsonLdCapturePolicy p = policy(KgConfig.JSONLD_ENABLED, "true", "resource.disk.free.min.steadystate", "4096",
                KgConfig.DISK_RESERVE_BYTES, Long.toString(GIB), KgConfig.DISK_HYSTERESIS_BYTES, Long.toString(512 * MIB));
        assertEquals(State.PAUSED, p.evaluate(true, null, 4 * GIB));
        assertEquals(JsonLdCapturePolicy.DISK_RESERVE, p.status().optString("reason"));
        assertEquals(State.PAUSED, p.evaluate(true, null, 5 * GIB + 100 * MIB));
        assertEquals(State.ACTIVE, p.evaluate(true, null, 6 * GIB));
    }
}
