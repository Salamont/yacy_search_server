/*
 *  KgBackups
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.store.KgBackup;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * Backups of the running graph (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 7.7): on
 * request ({@code backup}) and every {@code backup.intervalDays}, one at a
 * time on the thread {@code ScoutroKG.backup}. Each backup is
 * {@link KgStore#backupTo} into {@code backup/<name>.partial}, then
 * {@link KgBackup#verify}, the metadata file with the SHA-256, the rename
 * to its final name and the retention. A backup that the guard refuses
 * (budget, pause, disk reserve) is skipped with that reason and retried after
 * an hour; nothing else waits for it.
 */
final class KgBackups {

    static final String THREAD = "ScoutroKG.backup";
    static final long RETRY_MILLIS = 3600_000L;
    private static final long SMALL = 64L * 1024L;
    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-KG");

    /** Records an event in the graph (level 1 info, 2 warning). */
    interface Events {
        void record(int level, String code, String detail);
    }

    private final Supplier<KgStore> store;
    private final KgPaths paths;
    private final KgConfig cfg;
    private final StorageGuard guard;
    private final LongSupplier clock;
    private final Events events;
    private final ExecutorService thread;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile String running;
    private volatile JSONObject last;
    private volatile long lastBackupAt;
    private volatile long nextAttemptAt;

    private volatile Runnable afterVerified;

    /** Runs on the backup thread after every verified backup; must not throw. */
    void afterVerified(final Runnable r) {
        this.afterVerified = r;
    }

    KgBackups(final Supplier<KgStore> store, final KgPaths paths, final KgConfig cfg, final StorageGuard guard, final LongSupplier clock,
            final Events events, final long lastBackupAt, final long createdAt) {
        this.store = store;
        this.paths = paths;
        this.cfg = cfg;
        this.guard = guard;
        this.clock = clock;
        this.events = events;
        this.lastBackupAt = lastBackupAt;
        // the first scheduled backup is one interval after the graph was created (or after the last backup)
        this.nextAttemptAt = (lastBackupAt > 0L ? lastBackupAt : createdAt) + cfg.backupIntervalMillis;
        this.thread = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, THREAD);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /** True while a backup runs, or while a restore or rebuild holds the slot. */
    boolean busy() {
        return this.busy.get();
    }

    /** Takes the slot for another operation (restore, rebuild swap); false if a backup runs. */
    boolean claim() {
        return this.busy.compareAndSet(false, true);
    }

    void release() {
        this.busy.set(false);
    }

    /** Starts a backup now; false if one (or a restore) is running. */
    boolean start(final String trigger) {
        if (!this.busy.compareAndSet(false, true)) {
            return false;
        }
        this.running = trigger;
        try {
            this.thread.execute(() -> {
                try {
                    run(trigger);
                } finally {
                    this.running = null;
                    this.busy.set(false);
                }
            });
        } catch (final java.util.concurrent.RejectedExecutionException e) {
            this.running = null;
            this.busy.set(false);
            return false;
        }
        return true;
    }

    /** The schedule, from the maintenance thread: starts a due backup if the guard would admit growth. */
    void tick(final long now) {
        if (this.cfg.backupIntervalMillis <= 0L || now < this.nextAttemptAt || this.busy.get()) {
            return;
        }
        if (!this.guard.growthAllowed()) {
            this.nextAttemptAt = now + RETRY_MILLIS;
            skipped("scheduled", "growth_not_allowed", now);
            return;
        }
        this.nextAttemptAt = now + RETRY_MILLIS; // moved to the full interval when the backup succeeds
        start("scheduled");
    }

    /** One backup, on the backup thread. Never throws. */
    private void run(final String trigger) {
        final long start = this.clock.getAsLong();
        final KgStore s = this.store.get();
        if (s == null) {
            return;
        }
        final File dir = this.paths.backup;
        final String name = KgBackup.name(start, "");
        final File partial = new File(dir, name + ".partial");
        final File target = new File(dir, name);
        try {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new KgException(KgException.BACKUP_FAILED, "mkdir", "cannot create " + KgPaths.RELATIVE_DIR + "/backup", null);
            }
            if (target.exists()) {
                throw new KgException(KgException.BACKUP_FAILED, "exists", "a backup of this second exists already", null);
            }
            s.backupTo(partial, this.cfg.backupMaxMillis);
            final JSONObject facts = KgBackup.verify(partial, KgSchema.CURRENT_VERSION);
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            final long took = this.clock.getAsLong() - start;
            final JSONObject meta = KgBackup.writeMeta(target, facts, trigger, start, took);
            final List<String> removed = KgBackup.retain(dir, this.cfg.backupKeep);
            this.lastBackupAt = start;
            this.nextAttemptAt = start + Math.max(this.cfg.backupIntervalMillis, RETRY_MILLIS);
            try {
                s.write(WriteClass.SYSTEM, SMALL, tx -> {
                    KgStore.putMeta(tx, KgSchema.META_LAST_BACKUP_AT, Long.toString(start));
                    return null;
                });
            } catch (final KgException e) {
                // the file is there; the schedule counts from memory until the next start
            }
            this.last = KgJson.obj("result", "created", "trigger", trigger, "file", name, "bytes", target.length(),
                    "sha256", meta.optString("sha256"), "at", start, "durationMs", took, "counts", facts.opt("counts"),
                    "removed", new JSONArray(removed));
            this.events.record(1, "backup_created", name + " (" + target.length() + " bytes, " + took + " ms, " + trigger + ")");
            LOG.info("knowledge graph backup " + name + " written in " + took + " ms");
            final Runnable after = this.afterVerified;
            if (after != null) {
                after.run(); // e.g. ends the wait of an upgrade without its copy
            }
        } catch (final KgException e) {
            deleteQuietly(partial);
            final boolean refused = KgException.WRITE_REFUSED.equals(e.code());
            if (refused) {
                skipped(trigger, e.reason(), start);
            } else {
                this.last = KgJson.obj("result", "failed", "trigger", trigger, "file", name, "at", start, "reason", e.code(),
                        "detail", e.reason());
                this.events.record(2, "backup_failed", e.code() + (e.reason() == null ? "" : ": " + e.reason()));
                LOG.warn("knowledge graph backup failed: " + e.code() + " " + e.getMessage());
            }
            this.nextAttemptAt = start + RETRY_MILLIS;
        } catch (final IOException | RuntimeException e) {
            deleteQuietly(partial);
            deleteQuietly(target);
            this.last = KgJson.obj("result", "failed", "trigger", trigger, "file", name, "at", start, "reason", KgException.BACKUP_FAILED,
                    "detail", e.getClass().getSimpleName());
            this.events.record(2, "backup_failed", e.getClass().getSimpleName());
            this.nextAttemptAt = start + RETRY_MILLIS;
        } finally {
            this.guard.refresh();
        }
    }

    private void skipped(final String trigger, final String reason, final long at) {
        this.last = KgJson.obj("result", "skipped", "trigger", trigger, "at", at, "reason", reason);
        this.events.record(1, "backup_skipped", trigger + ": " + reason);
    }

    private static void deleteQuietly(final File f) {
        if (f.exists() && !f.delete()) {
            f.deleteOnExit();
        }
    }

    /** Waits for a running backup to end (interrupted by the store's close); called at stop. */
    void stop() {
        this.thread.shutdown();
        try {
            this.thread.awaitTermination(5000L, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Status: state, the last result, the schedule and the files. */
    JSONObject status() {
        final List<File> files = KgBackup.list(this.paths.backup);
        long bytes = 0L;
        for (final File f : files) {
            bytes += f.length();
        }
        return KgJson.obj("state", this.running != null ? "running" : "idle", "running", this.running, "last", this.last,
                "lastBackupAt", this.lastBackupAt > 0L ? this.lastBackupAt : null,
                "nextScheduledAt", this.cfg.backupIntervalMillis > 0L ? this.nextAttemptAt : null,
                "keep", this.cfg.backupKeep, "intervalDays", this.cfg.backupIntervalMillis / KgConfig.DAY,
                "files", files.size(), "bytes", bytes, "dir", KgPaths.RELATIVE_DIR + "/backup");
    }

    /** The backup files with their metadata, newest first. */
    JSONObject list() {
        final JSONArray items = new JSONArray();
        for (final File f : KgBackup.list(this.paths.backup)) {
            final JSONObject meta = KgBackup.readMeta(f);
            items.put(KgJson.obj("file", f.getName(), "kind", KgBackup.safety(f.getName())
                    ? (f.getName().contains(KgBackup.BEFORE_REBUILD) ? "before_rebuild" : KgBackup.beforeUpgrade(f.getName()) ? "before_upgrade"
                            : "before_restore") : "backup",
                    "bytes", f.length(), "created_at", meta == null ? null : meta.opt("created_at"),
                    "sha256", meta == null ? null : meta.opt("sha256"), "metadata", meta != null,
                    "kg_schema_version", meta == null ? null : meta.opt("kg_schema_version"),
                    "epoch", meta == null ? null : meta.opt("epoch"), "counts", meta == null ? null : meta.opt("counts"),
                    "trigger", meta == null ? null : meta.opt("trigger")));
        }
        final JSONObject o = status();
        KgJson.put(o, "schema", KgBackup.SCHEMA);
        KgJson.put(o, "items", items);
        return o;
    }
}
