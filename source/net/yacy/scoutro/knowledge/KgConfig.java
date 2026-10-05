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
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

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
    public static final String COLLECTIONS = "scoutro.kg.collections";
    public static final String CAPTURE_MAX_PENDING = "scoutro.kg.capture.maxPending";
    public static final String QUEUE_MAX_ITEMS = "scoutro.kg.queue.maxItems";
    public static final String EXTRACT_MAX_STATEMENTS_PER_DOC = "scoutro.kg.extract.maxStatementsPerDoc";
    public static final String EXTRACT_MAX_EXCERPT_CHARS = "scoutro.kg.extract.maxExcerptChars";
    public static final String EXTRACT_MAX_RULE_INPUT_CHARS = "scoutro.kg.extract.maxRuleInputChars";
    public static final String RECONCILE_HOUR = "scoutro.kg.reconcile.hour";
    public static final String RECONCILE_DEBOUNCE_SECONDS = "scoutro.kg.reconcile.debounceSeconds";
    public static final String RECONCILE_MAX_DELETE_FRACTION = "scoutro.kg.reconcile.maxDeleteFraction";
    public static final String RECONCILE_BRAKE_MIN_DOCS = "scoutro.kg.reconcile.brakeMinDocs";
    public static final String SOURCE_UNAVAILABLE_GRACE_DAYS = "scoutro.kg.source.unavailableGraceDays";
    public static final String SOURCE_GONE_RETENTION_DAYS = "scoutro.kg.source.goneRetentionDays";
    public static final String SOURCE_MAX_AGE_DAYS = "scoutro.kg.source.maxAgeDays";
    public static final String SOURCE_STALE_RETENTION_DAYS = "scoutro.kg.source.staleRetentionDays";
    public static final String CHANGES_RETENTION_DAYS = "scoutro.kg.changes.retentionDays";
    public static final String CHANGES_MAX_ROWS = "scoutro.kg.changes.maxRows";
    public static final String GATE_MAX_INDEXING_QUEUE = "scoutro.kg.gate.maxIndexingQueue";
    public static final String GATE_MAX_LOAD = "scoutro.kg.gate.maxLoad";
    public static final String GATE_MIN_FREE_HEAP_MB = "scoutro.kg.gate.minFreeHeapMB";

    /** Collection names the graph follows; {@code *} follows every collection. */
    public static final String ALL_COLLECTIONS = "*";
    private static final Pattern COLLECTION_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    static final long DAY = 24L * 3600L * 1000L;

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
    /** Followed collections (sorted); empty means none, see {@link #allCollections}. */
    public final Set<String> collections;
    /** True for {@code scoutro.kg.collections=*}. */
    public final boolean allCollections;
    public final int captureMaxPending;
    public final long queueMaxItems;
    public final int extractMaxStatementsPerDoc;
    public final int extractMaxExcerptChars;
    public final int extractMaxRuleInputChars;
    public final int reconcileHour;
    public final long reconcileDebounceMillis;
    public final double reconcileMaxDeleteFraction;
    public final long reconcileBrakeMinDocs;
    public final long unavailableGraceMillis;
    public final long goneRetentionMillis;
    public final long maxAgeMillis;
    public final long staleRetentionMillis;
    public final long changesRetentionMillis;
    public final long changesMaxRows;
    public final int gateMaxIndexingQueue;
    public final double gateMaxLoad;
    public final long gateMinFreeHeapBytes;
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
        final Set<String> colls = p.collections(COLLECTIONS);
        this.allCollections = colls.contains(ALL_COLLECTIONS);
        colls.remove(ALL_COLLECTIONS);
        this.collections = Collections.unmodifiableSet(colls);
        this.captureMaxPending = (int) p.longValue(CAPTURE_MAX_PENDING, 100_000, 1_000, 1_000_000);
        this.queueMaxItems = p.longValue(QUEUE_MAX_ITEMS, 200_000, 1_000, 10_000_000);
        this.extractMaxStatementsPerDoc = (int) p.longValue(EXTRACT_MAX_STATEMENTS_PER_DOC, 50, 1, 500);
        this.extractMaxExcerptChars = (int) p.longValue(EXTRACT_MAX_EXCERPT_CHARS, 200, 20, 1000);
        this.extractMaxRuleInputChars = (int) p.longValue(EXTRACT_MAX_RULE_INPUT_CHARS, 65_536, 1_024, 1_048_576);
        this.reconcileHour = (int) p.longValue(RECONCILE_HOUR, 3, 0, 23);
        this.reconcileDebounceMillis = 1000L * p.longValue(RECONCILE_DEBOUNCE_SECONDS, 300, 0, 86_400);
        this.reconcileMaxDeleteFraction = p.doubleValue(RECONCILE_MAX_DELETE_FRACTION, 0.2, 0.0, 1.0);
        this.reconcileBrakeMinDocs = p.longValue(RECONCILE_BRAKE_MIN_DOCS, 50, 0, 1_000_000_000L);
        this.unavailableGraceMillis = DAY * p.longValue(SOURCE_UNAVAILABLE_GRACE_DAYS, 14, 1, 365);
        this.goneRetentionMillis = DAY * p.longValue(SOURCE_GONE_RETENTION_DAYS, 7, 0, 365);
        this.maxAgeMillis = DAY * p.longValue(SOURCE_MAX_AGE_DAYS, 365, 1, 3650);
        this.staleRetentionMillis = DAY * p.longValue(SOURCE_STALE_RETENTION_DAYS, 90, 1, 3650);
        this.changesRetentionMillis = DAY * p.longValue(CHANGES_RETENTION_DAYS, 30, 1, 3650);
        this.changesMaxRows = p.longValue(CHANGES_MAX_ROWS, 1_000_000, 1_000, 100_000_000);
        this.gateMaxIndexingQueue = (int) p.longValue(GATE_MAX_INDEXING_QUEUE, 20, 0, 100_000);
        this.gateMaxLoad = p.doubleValue(GATE_MAX_LOAD, 2.5, 0.0, 1024.0);
        this.gateMinFreeHeapBytes = MIB * p.longValue(GATE_MIN_FREE_HEAP_MB, 256, 0, 1_048_576);
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

    /** True if documents of {@code collection} are followed by the graph. */
    public boolean follows(final String collection) {
        return collection != null && (this.allCollections || this.collections.contains(collection));
    }

    /** True if at least one collection is followed. */
    public boolean followsAny() {
        return this.allCollections || !this.collections.isEmpty();
    }

    /** Stable description of the followed collections ({@code *} or the sorted names). */
    public String collectionsKey() {
        return this.allCollections ? ALL_COLLECTIONS : String.join(",", this.collections);
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
        final JSONArray colls = new JSONArray();
        if (this.allCollections) {
            colls.put(ALL_COLLECTIONS);
        }
        for (final String c : this.collections) {
            colls.put(c);
        }
        return KgJson.obj("valid", valid(), "errors", errors, "collections", colls);
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

        double doubleValue(final String key, final double dflt, final double min, final double max) {
            final String v = raw(key);
            if (v == null || v.isEmpty()) {
                return dflt;
            }
            try {
                final double n = Double.parseDouble(v);
                if (Double.isNaN(n) || n < min || n > max) {
                    problem(key, "must be between " + min + " and " + max + ", not " + clip(v));
                    return dflt;
                }
                return n;
            } catch (final NumberFormatException e) {
                problem(key, "must be a number, not '" + clip(v) + "'");
                return dflt;
            }
        }

        /** Comma- or space-separated collection names, or {@code *}. */
        Set<String> collections(final String key) {
            final Set<String> out = new TreeSet<>();
            final String v = raw(key);
            if (v == null || v.isEmpty()) {
                return out;
            }
            for (final String part : v.split("[,\\s]+")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (ALL_COLLECTIONS.equals(part) || COLLECTION_NAME.matcher(part).matches()) {
                    out.add(part);
                } else {
                    problem(key, "invalid collection name '" + clip(part) + "' (letters, digits, '_' and '-', or *)");
                }
            }
            return out;
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
