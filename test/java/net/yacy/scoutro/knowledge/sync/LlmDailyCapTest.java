package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;

/**
 * WP-D: the daily cap on model requests (scoutro.kg.llm.maxRequestsPerDay) is a verifiable consumption limit inside the
 * existing LLM tier: requests above it wait for the next UTC day, the status shows the count, and the scan weights
 * are reported. Off (0) by default.
 */
public class LlmDailyCapTest extends LlmFixture {

    @Override
    protected String[] settings() {
        return new String[] {KgConfig.COLLECTIONS, "c1,c2", KgConfig.LLM_COLLECTIONS, "c1,c2", KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                KgConfig.LLM_MAX_REQUESTS_PER_DAY, "2", KgConfig.LLM_WEIGHT_PREFIX + "c2", "3"};
    }

    @Test
    public void requestsAboveTheDailyCapWaitForTheNextDay() throws Exception {
        // a fixed day boundary: start at 00:10 UTC
        final long day = java.time.LocalDate.of(2027, 3, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        this.clock.set(day + 600_000L);
        addAll(List.of(imprint('A', 1, "c1"), imprint('B', 2, "c1"), imprint('C', 3, "c1"), imprint('D', 4, "c1")));
        settleSync();
        for (int i = 0; i < 200; i++) {
            if (!this.llm.step()) this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
            if (this.clock.get() > day + 3_600_000L) break; // within the same day
        }
        assertEquals("at most the cap of 2 requests on one day", 2, this.model.calls.get());
        final JSONObject status = this.llm.status();
        assertEquals(2L, status.getJSONObject("dailyRequests").getLong("cap"));
        assertEquals(2L, status.getJSONObject("dailyRequests").getLong("today"));
        assertEquals("2027-03-01", status.getJSONObject("dailyRequests").getString("day"));
        assertEquals(3, status.getJSONObject("weights").getInt("c2"));
        assertTrue(status.toString(), status.toString().contains(LlmService.DAILY_CAP));
        // the next day the remaining pages are read
        this.clock.set(day + 86_400_000L + 600_000L);
        settleLlm();
        assertEquals(4, this.model.calls.get());
        assertEquals("2027-03-02", this.llm.status().getJSONObject("dailyRequests").getString("day"));
        assertEquals(2L, this.llm.status().getJSONObject("dailyRequests").getLong("today"));
    }
}
