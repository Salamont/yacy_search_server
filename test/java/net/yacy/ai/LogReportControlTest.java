/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai;

import static org.junit.Assert.*;
import java.time.*;
import java.nio.file.Files;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class LogReportControlTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @Test public void claimsSurviveRestartDeletionAndRequireExplicitRecreation() throws Exception {
        final LogReportControl first = new LogReportControl(this.tmp.getRoot().toPath());
        assertTrue(first.claim("2026-10-16T10:00", false));
        final LogReportControl restarted = new LogReportControl(this.tmp.getRoot().toPath());
        assertFalse(restarted.claim("2026-10-16T10:00", false));
        assertTrue(restarted.claim("2026-10-16T10:00", true));
        assertTrue(first.claim("2026-10-16", false));
        assertFalse(restarted.claim("2026-10-16", false));
        assertEquals(1, Files.list(this.tmp.getRoot().toPath()).count());
    }
    @Test public void activationRestartAndIntervalChangesNeverQueuePastHours() throws Exception {
        final LogReportControl state = new LogReportControl(this.tmp.getRoot().toPath());
        final Instant now = Instant.parse("2026-10-16T10:23:00Z");
        state.reset(now, ZoneId.of("UTC"), 120, LocalTime.MIDNIGHT);
        assertFalse(state.eligible(LocalDateTime.parse("2026-10-16T09:00")));
        assertTrue(state.eligible(LocalDateTime.parse("2026-10-16T10:00")));
        assertFalse(state.due("hourly", now.plusSeconds(3600)));
        assertTrue(state.due("hourly", now.plusSeconds(7200)));
        state.advance("hourly", now.plusSeconds(14400));
        assertFalse(new LogReportControl(this.tmp.getRoot().toPath()).due("hourly", now.plusSeconds(7200)));
        state.reset(now.plusSeconds(86400), ZoneId.of("UTC"), 60, LocalTime.of(6,0));
        assertFalse(state.eligible(LocalDateTime.parse("2026-10-16T23:00")));
    }
    @Test public void dailyScheduleResolvesDstGapAndOverlapOnce() {
        final ZoneId berlin = ZoneId.of("Europe/Berlin");
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), LogReportControl.nextDaily(Instant.parse("2026-03-28T23:00:00Z"), berlin, LocalTime.of(2,30)));
        final Instant first = LogReportControl.nextDaily(Instant.parse("2026-10-24T22:00:00Z"), berlin, LocalTime.of(2,30));
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), first);
        assertEquals(Instant.parse("2026-10-26T01:30:00Z"), LogReportControl.nextDaily(first.plusSeconds(1), berlin, LocalTime.of(2,30)));
    }
}
