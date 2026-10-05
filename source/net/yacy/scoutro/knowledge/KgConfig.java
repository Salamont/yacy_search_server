/*
 *  KgConfig
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.search.SwitchboardConstants;

/**
 * Validated settings of the knowledge graph (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * section "Configuration"). Defaults live in code, like all Scoutro keys.
 * An invalid value never falls back silently: the problem is reported and
 * the graph stays off until the setting is corrected.
 */
public final class KgConfig {

    public static final String ENABLED = "scoutro.kg.enabled";
    public static final String BUDGET_MAX_BYTES = "scoutro.kg.budget.maxBytes";
    public static final String BUDGET_PAUSE_PERCENT = "scoutro.kg.budget.pausePercent";
    public static final String BUDGET_RESUME_PERCENT = "scoutro.kg.budget.resumePercent";
    public static final String BUDGET_MAINTENANCE_PERCENT = "scoutro.kg.budget.maintenancePercent";
    public static final String DISK_RESERVE_BYTES = "scoutro.kg.disk.reserveBytes";
    public static final String DISK_HYSTERESIS_BYTES = "scoutro.kg.disk.hysteresisBytes";
    public static final String WAL_MAX_BYTES = "scoutro.kg.wal.maxBytes";
    public static final String WAL_CHECKPOINT_BYTES = "scoutro.kg.wal.checkpointBytes";
    public static final String TMP_MAX_BYTES = "scoutro.kg.tmp.maxBytes";
    public static final String READ_MAX_TRANSACTION_MILLIS = "scoutro.kg.read.maxTransactionMillis";
    public static final String INTEGRITY_MAX_MILLIS = "scoutro.kg.integrity.maxMillis";
    public static final String JSONLD_ENABLED = "scoutro.kg.jsonld.enabled";
    public static final String JSONLD_MAX_BYTES_PER_DOC = "scoutro.kg.jsonld.maxBytesPerDoc";
    public static final String JSONLD_MAX_BLOCKS_PER_DOC = "scoutro.kg.jsonld.maxBlocksPerDoc";
    public static final String JSONLD_MAX_TOTAL_BYTES = "scoutro.kg.jsonld.maxTotalBytes";

    static final long KIB = 1024L;
    static final long MIB = 1024L * KIB;
    static final long GIB = 1024L * MIB;
    static final long TIB = 1024L * GIB;

    /** One problem with one setting. */
    public static final class Problem {
        public final String key;
        public final String message;

        Problem(final String key, final String message) {
            this.key = key;
            this.message = message;
        }

        @Override
        public String toString() {
            return this.key + ": " + this.message;
        }
    }

    public final boolean enabled;
    public final long budgetMaxBytes;
    public final int pausePercent;
    public final int resumePercent;
    public final int maintenancePercent;
    public final long diskReserveBytes;
    public final long diskHysteresisBytes;
    public final long walMaxBytes;
    public final long walCheckpointBytes;
    public final long tmpMaxBytes;
    public final long readMaxTransactionMillis;
    /** Deadline of the integrity check (quick_check) after an unclean shutdown. */
    public final long integrityMaxMillis;
    public final boolean jsonldEnabled;
    public final long jsonldMaxBytesPerDoc;
    public final int jsonldMaxBlocksPerDoc;
    public final long jsonldMaxTotalBytes;
    /** YaCy's own free-space thresholds for the DATA filesystem, in bytes. */
    public final long yacySteadyStateBytes;
    public final long yacyUndershotBytes;
    private final List<Problem> problems;

    private KgConfig(final Parser p) {
        this.enabled = p.bool(ENABLED, false);
        this.budgetMaxBytes = p.longValue(BUDGET_MAX_BYTES, GIB, 64 * MIB, 16 * TIB);
        this.pausePercent = (int) p.longValue(BUDGET_PAUSE_PERCENT, 90, 50, 99);
        this.resumePercent = (int) p.longValue(BUDGET_RESUME_PERCENT, 80, 10, 94);
        this.maintenancePercent = (int) p.longValue(BUDGET_MAINTENANCE_PERCENT, 20, 5, 50);
        this.diskReserveBytes = p.longValue(DISK_RESERVE_BYTES, GIB, 0, 16 * TIB);
        this.diskHysteresisBytes = p.longValue(DISK_HYSTERESIS_BYTES, 512 * MIB, 0, 16 * TIB);
        this.walMaxBytes = p.longValue(WAL_MAX_BYTES, 64 * MIB, 4 * MIB, 4 * GIB);
        this.walCheckpointBytes = p.longValue(WAL_CHECKPOINT_BYTES, 8 * MIB, MIB, 4 * GIB);
        this.tmpMaxBytes = p.longValue(TMP_MAX_BYTES, 64 * MIB, 4 * MIB, 4 * GIB);
        this.readMaxTransactionMillis = p.longValue(READ_MAX_TRANSACTION_MILLIS, 5000, 100, 60000);
        this.integrityMaxMillis = p.longValue(INTEGRITY_MAX_MILLIS, 120_000, 100, 3_600_000);
        this.jsonldEnabled = p.bool(JSONLD_ENABLED, false);
        this.jsonldMaxBytesPerDoc = p.longValue(JSONLD_MAX_BYTES_PER_DOC, 16 * KIB, KIB, 64 * KIB);
        this.jsonldMaxBlocksPerDoc = (int) p.longValue(JSONLD_MAX_BLOCKS_PER_DOC, 8, 1, 32);
        this.jsonldMaxTotalBytes = p.longValue(JSONLD_MAX_TOTAL_BYTES, 256 * MIB, MIB, 16 * TIB);
        // YaCy's keys are megabytes; read with YaCy's own code defaults (SwitchboardConstants)
        this.yacySteadyStateBytes = MIB * p.yacyLong(SwitchboardConstants.RESOURCE_DISK_FREE_MIN_STEADYSTATE,
                SwitchboardConstants.RESOURCE_DISK_FREE_MIN_STEADYSTATE_DEFAULT);
        this.yacyUndershotBytes = MIB * p.yacyLong(SwitchboardConstants.RESOURCE_DISK_FREE_MIN_UNDERSHOT,
                SwitchboardConstants.RESOURCE_DISK_FREE_MIN_UNDERSHOT_DEFAULT);

        if (this.resumePercent >= this.pausePercent) {
            p.problem(BUDGET_RESUME_PERCENT, "must be lower than " + BUDGET_PAUSE_PERCENT + " (" + this.pausePercent + ")");
        }
        if (this.walCheckpointBytes >= this.walMaxBytes) {
            p.problem(WAL_CHECKPOINT_BYTES, "must be lower than " + WAL_MAX_BYTES + " (" + this.walMaxBytes + ")");
        }
        if (maintenanceBytes(this.budgetMaxBytes, this.maintenancePercent) < this.walMaxBytes + this.tmpMaxBytes) {
            p.problem(BUDGET_MAINTENANCE_PERCENT, "the maintenance share of the budget ("
                    + maintenanceBytes(this.budgetMaxBytes, this.maintenancePercent)
                    + " bytes) must hold " + WAL_MAX_BYTES + " + " + TMP_MAX_BYTES + " ("
                    + (this.walMaxBytes + this.tmpMaxBytes) + " bytes)");
        }
        this.problems = Collections.unmodifiableList(p.problems);
    }

    /** Reads all settings; {@code lookup} returns null for keys that are not set. */
    public static KgConfig read(final Function<String, String> lookup) {
        return new KgConfig(new Parser(lookup));
    }

    public boolean valid() {
        return this.problems.isEmpty();
    }

    public List<Problem> problems() {
        return this.problems;
    }

    /** Budget share reserved for WAL, temporary files and maintenance writes. */
    public long maintenanceBytes() {
        return maintenanceBytes(this.budgetMaxBytes, this.maintenancePercent);
    }

    /** Budget share the main database file may use (enforced with max_page_count). */
    public long dataBytes() {
        return this.budgetMaxBytes - maintenanceBytes();
    }

    public long pauseAtBytes() {
        return this.budgetMaxBytes / 100L * this.pausePercent;
    }

    public long resumeAtBytes() {
        return this.budgetMaxBytes / 100L * this.resumePercent;
    }

    /** New growth stops when the DATA filesystem has less free space than this. */
    public long growthFloorBytes() {
        return this.yacySteadyStateBytes + this.diskReserveBytes;
    }

    /** Growth resumes only above this free space. */
    public long growthResumeFloorBytes() {
        return growthFloorBytes() + this.diskHysteresisBytes;
    }

    /** Below this free space every write is refused, deletions included. */
    public long criticalFloorBytes() {
        return Math.min(this.yacyUndershotBytes, growthFloorBytes());
    }

    private static long maintenanceBytes(final long budget, final int percent) {
        return budget / 100L * percent;
    }

    public JSONObject toJson() {
        final JSONArray errors = new JSONArray();
        for (final Problem p : this.problems) {
            errors.put(KgJson.obj("key", p.key, "message", p.message));
        }
        return KgJson.obj("valid", valid(), "errors", errors);
    }

    private static final class Parser {
        private final Function<String, String> lookup;
        private final List<Problem> problems = new ArrayList<>();

        Parser(final Function<String, String> lookup) {
            this.lookup = lookup;
        }

        private String raw(final String key) {
            final String v = this.lookup.apply(key);
            return v == null ? null : v.trim();
        }

        boolean bool(final String key, final boolean dflt) {
            final String v = raw(key);
            if (v == null || v.isEmpty()) {
                return dflt;
            }
            final String l = v.toLowerCase(Locale.ROOT);
            if ("true".equals(l)) {
                return true;
            }
            if ("false".equals(l)) {
                return false;
            }
            problem(key, "must be true or false, not '" + clip(v) + "'");
            return dflt;
        }

        long longValue(final String key, final long dflt, final long min, final long max) {
            final String v = raw(key);
            if (v == null || v.isEmpty()) {
                return dflt;
            }
            try {
                final long n = Long.parseLong(v);
                if (n < min || n > max) {
                    problem(key, "must be between " + min + " and " + max + ", not " + n);
                    return dflt;
                }
                return n;
            } catch (final NumberFormatException e) {
                problem(key, "must be a whole number, not '" + clip(v) + "'");
                return dflt;
            }
        }

        /** YaCy's own keys: reported if unusable, but never part of the graph's validity. */
        long yacyLong(final String key, final long dflt) {
            final String v = raw(key);
            if (v == null || v.isEmpty()) {
                return dflt;
            }
            try {
                final long n = Long.parseLong(v);
                return n < 0 ? dflt : n;
            } catch (final NumberFormatException e) {
                return dflt;
            }
        }

        void problem(final String key, final String message) {
            this.problems.add(new Problem(key, message));
        }

        private static String clip(final String v) {
            return v.length() > 40 ? v.substring(0, 40) + "…" : v;
        }
    }
}
