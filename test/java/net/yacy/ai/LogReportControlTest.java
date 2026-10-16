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

    private LogReportSchedule plan(String mode,int hours,String time,int weekday,int monthday) {
        return new LogReportSchedule(mode,hours,time,weekday,monthday,"Europe/Berlin");
    }
    @Test public void everySupportedHourlyIntervalHasOneElapsedWindow() {
        Instant now=Instant.parse("2026-10-16T10:23:00Z");
        for(int hours:new int[]{1,2,4,6,12}) {
            LogReportSchedule p=plan("hourly",hours,"00:00",1,0);
            Instant end=p.next(now),start=p.previous(end.minusNanos(1));
            assertEquals(hours*3600,Duration.between(start,end).getSeconds());
            assertEquals(end,p.previous(end)); assertTrue(end.isAfter(now));
        }
    }
    @Test public void calendarWindowsHandleDstAndMonthsIncludingLeapYears() {
        LogReportSchedule daily=plan("daily",1,"02:30",1,0);
        Instant spring=daily.next(Instant.parse("2026-03-28T23:00:00Z"));
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"),spring);
        assertEquals(Instant.parse("2026-03-30T00:30:00Z"),daily.next(spring));
        Instant autumn=daily.next(Instant.parse("2026-10-24T22:00:00Z"));
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"),autumn);
        assertEquals(Instant.parse("2026-10-26T01:30:00Z"),daily.next(autumn));
        LogReportSchedule weekly=plan("weekly",1,"08:00",1,0);
        assertEquals(167*3600,Duration.between(Instant.parse("2026-03-23T07:00:00Z"),weekly.next(Instant.parse("2026-03-23T07:00:00Z"))).getSeconds());
        for(int day:new int[]{0,31}) {
            LogReportSchedule monthly=plan("monthly",1,"08:00",1,day);
            assertEquals(Instant.parse("2026-02-28T07:00:00Z"),monthly.next(Instant.parse("2026-01-31T07:00:00Z")));
            assertEquals(Instant.parse("2026-03-31T06:00:00Z"),monthly.next(Instant.parse("2026-02-28T07:00:00Z")));
            assertEquals(Instant.parse("2028-02-29T07:00:00Z"),monthly.next(Instant.parse("2028-01-31T07:00:00Z")));
        }
        LogReportSchedule month15=plan("monthly",1,"18:45",7,15);
        assertEquals(Instant.parse("2027-01-15T17:45:00Z"),month15.next(Instant.parse("2026-12-15T17:45:00Z")));
    }
    @Test public void persistedScheduleKeepsFutureRunAndSkipsMissedRunsOnRestart() throws Exception {
        LogReportControl control=new LogReportControl(tmp.getRoot().toPath());
        LogReportSchedule plan=plan("weekly",1,"08:00",1,0);
        Instant now=Instant.parse("2026-10-13T12:00:00Z");
        control.initialize(plan,now,false); String next=control.value("next.scheduled");
        LogReportControl restart=new LogReportControl(tmp.getRoot().toPath());
        restart.initialize(plan,now.plusSeconds(300),false); assertEquals(next,restart.value("next.scheduled"));
        restart.initialize(plan,now.plusSeconds(15*86400),false);
        assertTrue(Instant.parse(restart.value("next.scheduled")).isAfter(now.plusSeconds(15*86400)));
        restart.initialize(plan("off",1,"08:00",1,0),now,true); assertEquals("",restart.value("next.scheduled"));
    }
}
