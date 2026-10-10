/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.sync;

import net.yacy.scoutro.knowledge.KgJson;

import org.json.*;

import java.time.*;
import java.time.zone.*;
import java.util.*;

/** Wall-clock admission of request starts; no catch-up credits or extraction identity. */
public final class LlmSchedule {
    public static final String KEY = "scoutro.kg.llm.schedule";
    public static final String AUTOMATIC = "automatic", SCHEDULED = "scheduled", MANUAL = "manual";
    public static final Set<String> FIELDS =
            Set.of("mode", "days", "from", "until", "zone", "minStartSeconds");
    public final String mode;
    public final Set<Integer> days;
    public final LocalTime from, until;
    public final ZoneId zone;
    public final long minStartMillis;
    public final String error;

    private LlmSchedule(
            String mode,
            Set<Integer> days,
            LocalTime from,
            LocalTime until,
            ZoneId zone,
            long gap,
            String error) {
        this.mode = mode;
        this.days = Collections.unmodifiableSet(new TreeSet<>(days));
        this.from = from;
        this.until = until;
        this.zone = zone;
        this.minStartMillis = gap;
        this.error = error;
    }

    public static LlmSchedule defaults() {
        return new LlmSchedule(
                AUTOMATIC,
                Set.of(1, 2, 3, 4, 5, 6, 7),
                LocalTime.MIDNIGHT,
                LocalTime.MIDNIGHT,
                ZoneId.of("UTC"),
                0,
                null);
    }

    /** Invalid hand-edited persisted settings close this tier only, with a visible error. */
    public static LlmSchedule read(String value) {
        if (value == null || value.isBlank()) return defaults();
        try {
            return parse(new JSONObject(value));
        } catch (JSONException | IllegalArgumentException e) {
            LlmSchedule d = defaults();
            return new LlmSchedule(MANUAL, d.days, d.from, d.until, d.zone, 0, "invalid_schedule");
        }
    }

    public static LlmSchedule parse(JSONObject body) {
        try {
            for (Iterator<?> i = body.keys(); i.hasNext(); )
                if (!FIELDS.contains(String.valueOf(i.next())))
                    throw new IllegalArgumentException("Unknown schedule field.");
            for (String field : FIELDS)
                if (!body.has(field) || body.isNull(field))
                    throw new IllegalArgumentException("Missing schedule field: " + field);
            String mode = string(body, "mode");
            if (!Set.of(AUTOMATIC, SCHEDULED, MANUAL).contains(mode))
                throw new IllegalArgumentException(
                        "mode: automatic, scheduled or manual required.");
            Object raw = body.get("days");
            if (!(raw instanceof JSONArray))
                throw new IllegalArgumentException("days: array of ISO weekdays 1..7 required.");
            JSONArray list = (JSONArray) raw;
            Set<Integer> days = new TreeSet<>();
            if (list.length() < 1 || list.length() > 7)
                throw new IllegalArgumentException("days: select at least one weekday.");
            for (int i = 0; i < list.length(); i++) {
                int day = integer(list.get(i), "days", 1, 7);
                if (!days.add(day)) throw new IllegalArgumentException("days: duplicate weekday.");
            }
            LocalTime from = time(string(body, "from")), until = time(string(body, "until"));
            String name = string(body, "zone");
            if (!ZoneId.getAvailableZoneIds().contains(name))
                throw new IllegalArgumentException(
                        "zone: an explicit named time zone is required, for example Europe/Berlin"
                            + " or UTC.");
            int gap = integer(body.get("minStartSeconds"), "minStartSeconds", 0, 86400);
            return new LlmSchedule(mode, days, from, until, ZoneId.of(name), gap * 1000L, null);
        } catch (JSONException | DateTimeException e) {
            throw new IllegalArgumentException("Invalid schedule value.", e);
        }
    }

    private static String string(JSONObject j, String name) throws JSONException {
        Object v = j.get(name);
        if (!(v instanceof String)) throw new IllegalArgumentException(name + ": string required.");
        return (String) v;
    }

    public static int integer(Object v, String name, int min, int max) {
        if (!(v instanceof Integer || v instanceof Long)
                || ((Number) v).longValue() < min
                || ((Number) v).longValue() > max)
            throw new IllegalArgumentException(
                    name + ": integer between " + min + " and " + max + " required.");
        return ((Number) v).intValue();
    }

    private static LocalTime time(String v) {
        if (!v.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
            throw new IllegalArgumentException("Time: HH:mm between 00:00 and 23:59 required.");
        return LocalTime.parse(v);
    }

    public JSONObject json() {
        return KgJson.obj(
                "mode",
                mode,
                "days",
                new JSONArray(days),
                "from",
                String.format(Locale.ROOT, "%02d:%02d", from.getHour(), from.getMinute()),
                "until",
                String.format(Locale.ROOT, "%02d:%02d", until.getHour(), until.getMinute()),
                "zone",
                zone.getId(),
                "minStartSeconds",
                minStartMillis / 1000);
    }

    /**
     * [from, until); a crossing/equal window belongs to its starting weekday. Equal means 24 hours.
     */
    public boolean windowOpen(long millis) {
        if (error != null || MANUAL.equals(mode)) return false;
        if (AUTOMATIC.equals(mode)) return true;
        ZonedDateTime now = Instant.ofEpochMilli(millis).atZone(zone);
        LocalDate d = now.toLocalDate();
        LocalTime t = now.toLocalTime();
        if (from.isBefore(until))
            return days.contains(d.getDayOfWeek().getValue())
                    && !t.isBefore(from)
                    && t.isBefore(until);
        return days.contains(d.getDayOfWeek().getValue()) && !t.isBefore(from)
                || days.contains(d.minusDays(1).getDayOfWeek().getValue()) && t.isBefore(until);
    }

    /**
     * Earliest allowed instant at/after floor. Gaps have no fictitious local times; both overlap
     * occurrences count.
     */
    public Long nextWindow(long floor) {
        if (error != null || MANUAL.equals(mode)) return null;
        if (windowOpen(floor)) return floor;
        LocalDate base = Instant.ofEpochMilli(floor).atZone(zone).toLocalDate();
        TreeSet<Long> candidates = new TreeSet<>();
        ZoneRules rules = zone.getRules();
        for (int i = 0; i <= 8; i++) {
            LocalDate date = base.plusDays(i);
            if (!days.contains(date.getDayOfWeek().getValue())) continue;
            LocalDateTime local = date.atTime(from);
            List<ZoneOffset> offsets = rules.getValidOffsets(local);
            if (offsets.isEmpty())
                candidates.add(rules.getTransition(local).getInstant().toEpochMilli());
            else
                for (ZoneOffset offset : offsets)
                    candidates.add(local.toInstant(offset).toEpochMilli());
        }
        Instant horizon = base.plusDays(9).atStartOfDay(zone).toInstant();
        ZoneOffsetTransition transition =
                rules.nextTransition(Instant.ofEpochMilli(floor).minusNanos(1));
        while (transition != null && transition.getInstant().isBefore(horizon)) {
            candidates.add(transition.getInstant().toEpochMilli());
            transition = rules.nextTransition(transition.getInstant());
        }
        for (long candidate : candidates)
            if (candidate >= floor && windowOpen(candidate)) return candidate;
        return null;
    }
}
