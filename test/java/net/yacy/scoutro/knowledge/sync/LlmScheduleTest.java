package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.time.Instant;

public class LlmScheduleTest {
    static long at(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    static LlmSchedule plan(
            String mode, String days, String from, String until, String zone, int gap)
            throws Exception {
        return LlmSchedule.parse(
                new JSONObject(
                        "{\"mode\":\""
                                + mode
                                + "\",\"days\":"
                                + days
                                + ",\"from\":\""
                                + from
                                + "\",\"until\":\""
                                + until
                                + "\",\"zone\":\""
                                + zone
                                + "\",\"minStartSeconds\":"
                                + gap
                                + "}"));
    }

    @Test
    public void absentSettingsAreAlwaysAutomaticAndHaveNoSpacing() {
        LlmSchedule p = LlmSchedule.read(null);
        assertEquals("automatic", p.mode);
        assertEquals(0, p.minStartMillis);
        assertTrue(p.windowOpen(0));
        assertEquals(Long.valueOf(1234), p.nextWindow(1234));
    }

    @Test
    public void startIncludedEndExcludedAndNextWeekday() throws Exception {
        LlmSchedule p = plan("scheduled", "[1,3]", "08:00", "17:00", "UTC", 0);
        assertFalse(p.windowOpen(at("2026-10-12T07:59:59Z")));
        assertTrue(p.windowOpen(at("2026-10-12T08:00:00Z")));
        assertFalse(p.windowOpen(at("2026-10-12T17:00:00Z")));
        assertEquals(
                Long.valueOf(at("2026-10-14T08:00:00Z")), p.nextWindow(at("2026-10-12T17:00:00Z")));
    }

    @Test
    public void overnightBelongsToStartingDay() throws Exception {
        LlmSchedule p = plan("scheduled", "[1]", "22:00", "02:00", "UTC", 0);
        assertTrue(p.windowOpen(at("2026-10-12T23:00:00Z")));
        assertTrue(p.windowOpen(at("2026-10-13T01:59:59Z")));
        assertFalse(p.windowOpen(at("2026-10-13T02:00:00Z")));
        assertFalse(p.windowOpen(at("2026-10-12T01:00:00Z")));
        assertEquals(
                Long.valueOf(at("2026-10-19T22:00:00Z")), p.nextWindow(at("2026-10-13T02:00:00Z")));
    }

    @Test
    public void equalTimesMean24HoursStartingOnSelectedDay() throws Exception {
        LlmSchedule p = plan("scheduled", "[1]", "08:00", "08:00", "UTC", 0);
        assertTrue(p.windowOpen(at("2026-10-13T07:59:59Z")));
        assertFalse(p.windowOpen(at("2026-10-13T08:00:00Z")));
    }

    @Test
    public void namedZoneIsIndependentOfServerTimezone() throws Exception {
        LlmSchedule p = plan("scheduled", "[1]", "08:00", "09:00", "Europe/Berlin", 0);
        assertTrue(p.windowOpen(at("2026-10-12T06:00:00Z")));
        assertFalse(p.windowOpen(at("2026-10-12T07:00:00Z")));
    }

    @Test
    public void missingSpringHourHasNoFictitiousStarts() throws Exception {
        LlmSchedule p = plan("scheduled", "[7]", "02:10", "02:50", "Europe/Berlin", 0);
        assertFalse(p.windowOpen(at("2026-03-29T01:00:00Z")));
        assertEquals(
                Long.valueOf(at("2026-04-05T00:10:00Z")), p.nextWindow(at("2026-03-29T00:00:00Z")));
        LlmSchedule q = plan("scheduled", "[7]", "02:10", "03:20", "Europe/Berlin", 0);
        assertEquals(
                Long.valueOf(at("2026-03-29T01:00:00Z")), q.nextWindow(at("2026-03-29T00:00:00Z")));
    }

    @Test
    public void bothFallOccurrencesAreAllowedAndNextCanBeSecondOccurrence() throws Exception {
        LlmSchedule p = plan("scheduled", "[7]", "02:10", "02:50", "Europe/Berlin", 0);
        assertTrue(p.windowOpen(at("2026-10-25T00:20:00Z")));
        assertTrue(p.windowOpen(at("2026-10-25T01:20:00Z")));
        assertEquals(
                Long.valueOf(at("2026-10-25T01:10:00Z")), p.nextWindow(at("2026-10-25T00:55:00Z")));
    }

    @Test
    public void transitionCanReopenOvernightWindow() throws Exception {
        LlmSchedule p = plan("scheduled", "[6]", "22:00", "02:30", "Europe/Berlin", 0);
        assertEquals(
                Long.valueOf(at("2026-10-25T01:00:00Z")), p.nextWindow(at("2026-10-25T00:45:00Z")));
    }

    @Test
    public void manualHasNoAutomaticStartAndInvalidPersistedPlanClosesOnlyLlm() {
        LlmSchedule p = LlmSchedule.read("broken");
        assertNotNull(p.error);
        assertFalse(p.windowOpen(0));
        assertNull(p.nextWindow(0));
    }

    @Test
    public void rejectsUnknownMissingWrongTypeAndInvalidValues() throws Exception {
        JSONObject good = plan("automatic", "[1]", "08:00", "09:00", "UTC", 0).json();
        for (String mutation :
                new String[] {
                    "unknown", "mode", "days", "from", "until", "zone", "minStartSeconds", "missing"
                }) {
            JSONObject j = new JSONObject(good.toString());
            switch (mutation) {
                case "days":
                    j.put("days", new org.json.JSONArray("[1,1]"));
                    break;
                case "zone":
                    j.put("zone", "+01:00");
                    break;
                case "from":
                    j.put("from", "24:00");
                    break;
                case "until":
                    j.put("until", "09:00:00");
                    break;
                case "minStartSeconds":
                    j.put(mutation, 0.5);
                    break;
                case "missing":
                    j.remove("zone");
                    break;
                default:
                    j.put(mutation, "invalid");
            }
            try {
                LlmSchedule.parse(j);
                fail(mutation);
            } catch (IllegalArgumentException expected) {
            }
        }
    }
}
