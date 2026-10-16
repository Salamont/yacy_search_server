/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Properties;

/** Small durable scheduler/attempt journal in the existing report directory. No report deletion. */
public final class LogReportControl {
    public static final String DAILY_TIME = "ai.logreport.daily_time";
    public static final Object LOCK = new Object();
    private final Path file;

    public LogReportControl(final Path directory) { this.file = directory.resolve(".generation-state.properties"); }

    private Properties read() throws IOException {
        final Properties p = new Properties();
        if (Files.exists(this.file)) try (Reader r = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) { p.load(r); }
        return p;
    }

    private void write(final Properties p) throws IOException {
        Files.createDirectories(this.file.getParent());
        final Path temp = Files.createTempFile(this.file.getParent(), ".generation-", ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { p.store(w, "Scoutro log report schedule and claimed periods"); }
            try { Files.move(temp, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (final AtomicMoveNotSupportedException e) { Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    /** A restart or settings change opens a fresh window; old intervals are never queued. */
    public void reset(final Instant now, final ZoneId zone, final long delayMinutes, final LocalTime dailyTime) throws IOException {
        synchronized (LOCK) {
            final Properties p = read();
            p.setProperty("since", now.atZone(zone).truncatedTo(ChronoUnit.HOURS).toLocalDateTime().toString());
            p.setProperty("next.hourly", now.plus(Math.max(0, delayMinutes), ChronoUnit.MINUTES).toString());
            p.setProperty("next.daily", nextDaily(now, zone, dailyTime).toString());
            write(p);
        }
    }

    public static Instant nextDaily(final Instant now, final ZoneId zone, final LocalTime time) {
        final ZonedDateTime local = now.atZone(zone);
        ZonedDateTime next = local.toLocalDate().atTime(time).atZone(zone);
        if (!next.toInstant().isAfter(now)) next = local.toLocalDate().plusDays(1).atTime(time).atZone(zone);
        return next.toInstant(); // java.time resolves a DST gap forward and an overlap once, at its earlier offset
    }

    public String value(final String key) throws IOException { synchronized (LOCK) { return read().getProperty(key, ""); } }

    public boolean due(final String kind, final Instant now) throws IOException {
        final String next = value("next." + kind);
        return !next.isEmpty() && !Instant.parse(next).isAfter(now);
    }

    public void advance(final String kind, final Instant next) throws IOException {
        synchronized (LOCK) { final Properties p = read(); p.setProperty("next." + kind, next.toString()); write(p); }
    }

    public boolean eligible(final LocalDateTime bucket) throws IOException {
        final String since = value("since");
        return !since.isEmpty() && !bucket.isBefore(LocalDateTime.parse(since));
    }

    /** Claim BEFORE the paid request: an interrupted or ambiguous attempt requires explicit manual retry. */
    public boolean claim(final String period, final boolean force) throws IOException {
        synchronized (LOCK) {
            final Properties p = read();
            if (!force && p.containsKey("period." + period)) return false;
            p.setProperty("period." + period, "attempted");
            // A bounded journal; scheduler windows and existing report files still prevent historical replay.
            final String cutoff = LocalDate.now().minusDays(90).toString();
            p.keySet().removeIf(k -> k.toString().startsWith("period.") && k.toString().substring(7).compareTo(cutoff) < 0);
            write(p);
            return true;
        }
    }

    public void outcome(final String kind, final boolean success, final String message) throws IOException {
        synchronized (LOCK) {
            final Properties p = read();
            p.setProperty("last." + kind + (success ? ".success" : ".failure"), Instant.now().toString());
            p.setProperty("last." + kind + ".message", message.length() > 400 ? message.substring(0, 400) : message);
            write(p);
        }
    }
}
