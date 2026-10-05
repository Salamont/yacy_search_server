/*
 *  StorageGuard
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

package net.yacy.scoutro.knowledge.budget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.KgPaths;

/**
 * Admission control for every write of the knowledge graph store
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, "Storage and resource contract").
 * <p>
 * The guard enforces an application budget, not a filesystem quota. It
 * measures every file of the graph directory: database, WAL, shared memory,
 * temp files (including the unlinked temp files SQLite keeps open) and local
 * backups. Two write classes exist:
 * <ul>
 * <li>{@link WriteClass#GROWTH}: new data. Stops at the pause threshold of the
 * budget and above the free-space reserve, with hysteresis.</li>
 * <li>{@link WriteClass#MAINTENANCE}: deletions, state changes, bookkeeping.
 * May use the maintenance share of the budget, but is also refused when the
 * WAL limit is reached or the filesystem is below the critical floor. On a
 * really full disk a deletion therefore fails, in a controlled way, and is
 * retried later.</li>
 * </ul>
 * The main database file is additionally capped by SQLite itself
 * ({@code max_page_count}); WAL, temp files and backups are bounded only by
 * this guard.
 */
public final class StorageGuard {

    public enum WriteClass { GROWTH, MAINTENANCE }

    public static final String STORAGE_ERROR = "storage_error";
    public static final String DISK_CRITICAL = "disk_critical";
    public static final String WAL_LIMIT = "wal_limit";
    public static final String WAL_CHECKPOINT_BLOCKED = "wal_checkpoint_blocked";
    public static final String MANUAL = "manual";
    public static final String BUDGET_EXHAUSTED = "budget_exhausted";
    public static final String BUDGET = "budget";
    public static final String DISK_RESERVE = "disk_reserve";
    public static final String TMP_LIMIT = "tmp_limit";

    private final KgConfig cfg;
    private final KgPaths paths;
    private final StorageProbe probe;
    private final LongSupplier clock;

    private long dbBytes;
    private long walBytes;
    private long shmBytes;
    private long tmpVisibleBytes;
    private long tmpOpenBytes = -1L;
    private long backupBytes;
    private long usableBytes;
    private long measuredAt;
    private long fullMeasuredAt;

    private boolean budgetPaused;
    private long budgetPausedSince;
    private boolean diskPaused;
    private long diskPausedSince;
    private boolean manualPause;
    private String storageError;
    private long storageErrorSince;
    private boolean storageFull;
    private long storageFullSince;
    private CheckpointResult lastCheckpoint;
    private long checkpointBlockedSince;
    private final Map<String, Long> refused = new LinkedHashMap<>();

    public StorageGuard(final KgConfig cfg, final KgPaths paths, final StorageProbe probe, final LongSupplier clock) {
        this.cfg = cfg;
        this.paths = paths;
        this.probe = probe;
        this.clock = clock;
    }

    /** Full measurement, including the directory walks; called periodically. */
    public synchronized void refresh() {
        this.tmpVisibleBytes = this.probe.dirBytes(this.paths.tmp);
        this.tmpOpenBytes = this.probe.openUnlinkedBytes(this.paths.tmp);
        this.backupBytes = this.probe.dirBytes(this.paths.backup);
        this.fullMeasuredAt = this.clock.getAsLong();
        measureFast();
    }

    /** Cheap measurement before every admission: three file sizes and the free space. */
    private void measureFast() {
        this.dbBytes = this.probe.fileBytes(this.paths.db);
        this.walBytes = this.probe.fileBytes(this.paths.wal);
        this.shmBytes = this.probe.fileBytes(this.paths.shm);
        this.usableBytes = this.probe.usableBytes(this.paths.dir);
        this.measuredAt = this.clock.getAsLong();
        final long used = usedBytes();
        if (used >= this.cfg.pauseAtBytes()) {
            if (!this.budgetPaused) {
                this.budgetPaused = true;
                this.budgetPausedSince = this.measuredAt;
            }
        } else if (used <= this.cfg.resumeAtBytes()) {
            this.budgetPaused = false;
            if (this.storageFull) {
                this.storageFull = false;
            }
        }
        if (this.usableBytes < this.cfg.growthFloorBytes()) {
            if (!this.diskPaused) {
                this.diskPaused = true;
                this.diskPausedSince = this.measuredAt;
            }
        } else if (this.usableBytes >= this.cfg.growthResumeFloorBytes()) {
            this.diskPaused = false;
        }
    }

    private long usedBytes() {
        return this.dbBytes + this.walBytes + this.shmBytes + this.tmpVisibleBytes
                + Math.max(0L, this.tmpOpenBytes) + this.backupBytes;
    }

    private long tmpBytes() {
        return this.tmpVisibleBytes + Math.max(0L, this.tmpOpenBytes);
    }

    /**
     * Admits a write that may add up to {@code estimateBytes} to the database
     * and its WAL, or throws {@link KgException#WRITE_REFUSED} with the reason.
     */
    public synchronized void admit(final WriteClass writeClass, final long estimateBytes) throws KgException {
        measureFast();
        final long est = Math.max(0L, estimateBytes);
        final String reason = refusal(writeClass, est);
        if (reason != null) {
            this.refused.merge(reason, 1L, Long::sum);
            throw KgException.refused(reason, "knowledge graph write refused (" + writeClass.name().toLowerCase()
                    + "): " + reason);
        }
    }

    private String refusal(final WriteClass writeClass, final long est) {
        if (this.storageError != null) {
            return STORAGE_ERROR;
        }
        if (this.usableBytes - est < this.cfg.criticalFloorBytes()) {
            return DISK_CRITICAL;
        }
        if (this.walBytes + est > this.cfg.walMaxBytes) {
            return this.checkpointBlockedSince > 0 ? WAL_CHECKPOINT_BLOCKED : WAL_LIMIT;
        }
        if (writeClass == WriteClass.MAINTENANCE) {
            return usedBytes() + est > this.cfg.budgetMaxBytes ? BUDGET_EXHAUSTED : null;
        }
        if (this.manualPause) {
            return MANUAL;
        }
        if (this.storageFull) {
            return BUDGET_EXHAUSTED;
        }
        if (this.budgetPaused || usedBytes() + est > this.cfg.pauseAtBytes()) {
            return BUDGET;
        }
        if (this.diskPaused || this.usableBytes - est < this.cfg.growthFloorBytes()) {
            return DISK_RESERVE;
        }
        if (tmpBytes() > this.cfg.tmpMaxBytes) {
            return TMP_LIMIT;
        }
        return null;
    }

    /** True if new growth would currently be admitted (without an estimate). */
    public synchronized boolean growthAllowed() {
        measureFast();
        return refusal(WriteClass.GROWTH, 0L) == null;
    }

    /** True if maintenance writes would currently be admitted (without an estimate). */
    public synchronized boolean maintenanceAllowed() {
        measureFast();
        return refusal(WriteClass.MAINTENANCE, 0L) == null;
    }

    /** True if temp files exceed their limit; running readers should then be interrupted. */
    public synchronized boolean tmpOverLimit() {
        return tmpBytes() > this.cfg.tmpMaxBytes;
    }

    public synchronized long walBytes() {
        this.walBytes = this.probe.fileBytes(this.paths.wal);
        return this.walBytes;
    }

    public synchronized void recordCheckpoint(final CheckpointResult result) {
        this.lastCheckpoint = result;
        this.walBytes = result.walBytesAfter;
        if (result.complete()) {
            this.checkpointBlockedSince = 0L;
        } else if (this.checkpointBlockedSince == 0L) {
            this.checkpointBlockedSince = result.at;
        }
    }

    /** SQLite refused growth (SQLITE_FULL); growth stays refused until usage is back at the resume threshold. */
    public synchronized void storageFull() {
        if (!this.storageFull) {
            this.storageFull = true;
            this.storageFullSince = this.clock.getAsLong();
        }
    }

    /** I/O error or damaged database: every write stops until {@link #clearStorageError()}. */
    public synchronized void storageError(final String code) {
        if (this.storageError == null) {
            this.storageError = code;
            this.storageErrorSince = this.clock.getAsLong();
        }
    }

    public synchronized void clearStorageError() {
        this.storageError = null;
        this.storageErrorSince = 0L;
    }

    public synchronized String storageErrorCode() {
        return this.storageError;
    }

    public synchronized void setManualPause(final boolean paused) {
        this.manualPause = paused;
    }

    public synchronized boolean manualPause() {
        return this.manualPause;
    }

    /** max_page_count for the main file: the data share of the budget in pages. */
    public long maxPageCount(final long pageSize) {
        return Math.max(1L, this.cfg.dataBytes() / Math.max(512L, pageSize));
    }

    /** Active pause reasons, most severe first. */
    public synchronized List<JSONObject> reasons() {
        final List<JSONObject> r = new ArrayList<>();
        if (this.storageError != null) {
            r.add(reason(STORAGE_ERROR, this.storageErrorSince, this.storageError));
        }
        if (this.usableBytes < this.cfg.criticalFloorBytes()) {
            r.add(reason(DISK_CRITICAL, this.measuredAt, null));
        }
        if (this.checkpointBlockedSince > 0) {
            r.add(reason(WAL_CHECKPOINT_BLOCKED, this.checkpointBlockedSince, null));
        }
        if (this.walBytes > this.cfg.walMaxBytes) {
            r.add(reason(WAL_LIMIT, this.measuredAt, null));
        }
        if (this.manualPause) {
            r.add(reason(MANUAL, 0L, null));
        }
        if (this.storageFull) {
            r.add(reason(BUDGET_EXHAUSTED, this.storageFullSince, null));
        }
        if (this.budgetPaused) {
            r.add(reason(BUDGET, this.budgetPausedSince, null));
        }
        if (this.diskPaused) {
            r.add(reason(DISK_RESERVE, this.diskPausedSince, null));
        }
        if (tmpBytes() > this.cfg.tmpMaxBytes) {
            r.add(reason(TMP_LIMIT, this.fullMeasuredAt, null));
        }
        return r;
    }

    private static JSONObject reason(final String code, final long since, final String detail) {
        return KgJson.obj("code", code, "since", since > 0 ? since : null, "detail", detail);
    }

    /** Storage part of the status contract. */
    public synchronized JSONObject status() {
        measureFast();
        final JSONObject files = KgJson.obj("db", this.dbBytes, "wal", this.walBytes, "shm", this.shmBytes,
                "tmpVisible", this.tmpVisibleBytes, "tmpOpen", this.tmpOpenBytes >= 0 ? this.tmpOpenBytes : null,
                "backup", this.backupBytes);
        final JSONObject wal = KgJson.obj("bytes", this.walBytes, "maxBytes", this.cfg.walMaxBytes,
                "checkpointAtBytes", this.cfg.walCheckpointBytes,
                "lastCheckpoint", this.lastCheckpoint == null ? null : this.lastCheckpoint.toJson(),
                "blockedSince", this.checkpointBlockedSince > 0 ? this.checkpointBlockedSince : null);
        final JSONObject disk = KgJson.obj("usableBytes", this.usableBytes,
                "yacySteadyStateBytes", this.cfg.yacySteadyStateBytes, "yacyUndershotBytes", this.cfg.yacyUndershotBytes,
                "reserveBytes", this.cfg.diskReserveBytes, "growthFloorBytes", this.cfg.growthFloorBytes(),
                "growthResumeFloorBytes", this.cfg.growthResumeFloorBytes(), "criticalFloorBytes", this.cfg.criticalFloorBytes());
        final JSONObject refusals = new JSONObject();
        for (final Map.Entry<String, Long> e : this.refused.entrySet()) {
            KgJson.put(refusals, e.getKey(), e.getValue());
        }
        final JSONArray reasons = new JSONArray();
        for (final JSONObject r : reasons()) {
            reasons.put(r);
        }
        return KgJson.obj("budgetBytes", this.cfg.budgetMaxBytes, "usedBytes", usedBytes(),
                "pauseAtBytes", this.cfg.pauseAtBytes(), "resumeAtBytes", this.cfg.resumeAtBytes(),
                "maintenanceReserveBytes", this.cfg.maintenanceBytes(), "dataShareBytes", this.cfg.dataBytes(),
                "tmpMaxBytes", this.cfg.tmpMaxBytes, "files", files, "wal", wal, "disk", disk,
                "growthAllowed", refusal(WriteClass.GROWTH, 0L) == null,
                "maintenanceAllowed", refusal(WriteClass.MAINTENANCE, 0L) == null,
                "reasons", reasons, "refusedWrites", refusals, "measuredAt", this.measuredAt,
                "fullMeasuredAt", this.fullMeasuredAt > 0 ? this.fullMeasuredAt : null,
                "quota", "application_budget");
    }
}
