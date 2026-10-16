/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai;

import java.time.*;
import java.time.temporal.ChronoUnit;
import net.yacy.server.serverSwitch;

/** One calendar schedule, one completed interval, one report. */
public final class LogReportSchedule {
    public static final String CONFIG = "ai.logreport.schedule";
    public final String mode;
    public final int hours, weekday, monthday; // monthday=0 means last day
    public final LocalTime time;
    public final ZoneId zone;

    public LogReportSchedule(String mode, int hours, String time, int weekday, int monthday, String zone) {
        if (!java.util.Set.of("off", "hourly", "daily", "weekly", "monthly").contains(mode)
                || !java.util.Set.of(1,2,4,6,12).contains(hours) || weekday < 1 || weekday > 7 || monthday < 0 || monthday > 31
                || !time.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException("Invalid report schedule");
        this.mode = mode; this.hours = hours; this.time = LocalTime.parse(time);
        this.weekday = weekday; this.monthday = monthday; this.zone = ZoneId.of(zone);
    }

    public String encode() { return String.join("|", "1", mode, ""+hours, time.toString(), ""+weekday, ""+monthday, zone.getId()); }

    public static LogReportSchedule load(serverSwitch sb) {
        String value = sb.getConfig(CONFIG, "");
        if (!value.isEmpty()) {
            String[] p = value.split("\\|", -1);
            if (p.length != 7 || !p[0].equals("1")) throw new IllegalArgumentException("Unknown report schedule version");
            return new LogReportSchedule(p[1], Integer.parseInt(p[2]), p[3], Integer.parseInt(p[4]), Integer.parseInt(p[5]), p[6]);
        }
        // Keep all legacy keys as a recoverable record; consolidate the old dual timer into its primary interval.
        long minutes = sb.getConfigLong(LogReportService.CONFIG_PERIOD_MINUTES, 60);
        int nearest = 1;
        for (int h : new int[]{1,2,4,6,12}) if (Math.abs(h*60-minutes) < Math.abs(nearest*60-minutes)) nearest = h;
        return new LogReportSchedule(sb.getConfigBool(LogReportService.CONFIG_ENABLED, true) ? "hourly" : "off",
                nearest, sb.getConfig(LogReportControl.DAILY_TIME, "00:00"), 1, 0, "Europe/Berlin");
    }

    private Instant boundary(LocalDate date) { return date.atTime(time).atZone(zone).toInstant(); }
    private LocalDate monthDate(YearMonth month) { return month.atDay(monthday == 0 ? month.lengthOfMonth() : Math.min(monthday, month.lengthOfMonth())); }

    /** Calendar runs use gap-forward / earlier-overlap resolution; a repeated local run occurs once. */
    public Instant previous(Instant now) {
        if (mode.equals("off")) throw new IllegalStateException("Automation is off");
        if (mode.equals("hourly")) {
            // Elapsed hours, anchored to Berlin midnight in winter; DST never creates a duplicate interval key.
            long anchor = LocalDate.of(1970,1,1).atStartOfDay(zone).toEpochSecond();
            return Instant.ofEpochSecond(anchor + Math.floorDiv(now.getEpochSecond()-anchor, hours*3600L)*hours*3600L);
        }
        LocalDate date = now.atZone(zone).toLocalDate();
        if (mode.equals("daily")) {
            if (boundary(date).isAfter(now)) date = date.minusDays(1);
        } else if (mode.equals("weekly")) {
            date = date.minusDays(Math.floorMod(date.getDayOfWeek().getValue()-weekday, 7));
            if (boundary(date).isAfter(now)) date = date.minusWeeks(1);
        } else {
            YearMonth month = YearMonth.from(date); date = monthDate(month);
            if (boundary(date).isAfter(now)) date = monthDate(month.minusMonths(1));
        }
        return boundary(date);
    }

    public Instant next(Instant now) {
        Instant last = previous(now);
        if (mode.equals("hourly")) return last.plus(hours, ChronoUnit.HOURS);
        LocalDate date = last.atZone(zone).toLocalDate();
        if (mode.equals("daily")) date = date.plusDays(1);
        else if (mode.equals("weekly")) date = date.plusWeeks(1);
        else date = monthDate(YearMonth.from(date).plusMonths(1));
        return boundary(date);
    }
}
