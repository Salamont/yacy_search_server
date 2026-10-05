/*
 *  KgRuntime
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.knowledge.budget.JsonLdCapturePolicy;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.budget.StorageProbe;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.search.Switchboard;

/**
 * Lifecycle of the knowledge graph (package 1: store, budget, status).
 * <p>
 * With {@code scoutro.kg.enabled=false} (the default) nothing happens: no
 * directory, no file, no thread, and the SQLite native library is not
 * loaded. When enabled, the store is opened, an unclean previous shutdown is
 * detected through the {@code clean_shutdown} flag, and one daemon thread
 * enforces read deadlines, measures storage and checkpoints the WAL. No
 * failure here may stop Scoutro: every problem ends in the state
 * {@link State#UNAVAILABLE} with a reason, and the rest of Scoutro runs on.
 */
public final class KgRuntime {

    public enum State { DISABLED, RUNNING, UNAVAILABLE, STOPPED }

    public static final String STATUS_SCHEMA = "scoutro.kg.status.v1";
    public static final String MONITOR_THREAD = "ScoutroKG.monitor";

    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-KG");
    private static final long SMALL_WRITE_BYTES = 64L * 1024L;
    private static final long MEASURE_EVERY_MILLIS = 30_000L;
    private static final long QUICK_CHECK_DEADLINE_MILLIS = 120_000L;
    private static final int STATUS_EVENTS = 20;

    private static KgRuntime current;

    /** Everything the runtime needs from its surroundings; replaceable in tests. */
    public static final class Env {
        final File dataRoot;
        final Function<String, String> config;
        final LongSupplier clock;
        final StorageProbe probe;
        final KgStore.ConnectionFactory connections;
        final boolean monitorThread;

        public Env(final File dataRoot, final Function<String, String> config, final LongSupplier clock,
                final StorageProbe probe, final KgStore.ConnectionFactory connections, final boolean monitorThread) {
            this.dataRoot = dataRoot;
            this.config = config;
            this.clock = clock;
            this.probe = probe;
            this.connections = connections;
            this.monitorThread = monitorThread;
        }

        static Env of(final Switchboard sb) {
            return new Env(sb.getDataPath(), key -> sb.getConfig(key, null), System::currentTimeMillis,
                    StorageProbe.SYSTEM, KgStore.SQLITE, true);
        }
    }

    private final Env env;
    private volatile State state = State.STOPPED;
    private volatile String reason;
    private volatile String reasonDetail;
    private KgConfig config;
    private KgPaths paths;
    private StorageGuard guard;
    private JsonLdCapturePolicy jsonld;
    private KgStore store;
    private ScheduledExecutorService monitor;
    private boolean uncleanStartDetected;
    private volatile String quickCheck = "not_run";
    private volatile boolean quickCheckPending;
    /** False while the start could not be written; later packages must not write graph data before it is true. */
    private volatile boolean startRecorded = true;
    private long startedAt;
    private long lastMeasure;

    public KgRuntime(final Env env) {
        this.env = env;
    }

    // ---------------------------------------------------------- static access

    /** Starts the runtime once; called from ScoutroApiServlet.init. Never throws. */
    public static synchronized void start() {
        if (current != null) {
            return;
        }
        try {
            final Switchboard sb = Switchboard.getSwitchboard();
            if (sb == null) {
                return;
            }
            final KgRuntime r = new KgRuntime(Env.of(sb));
            r.open();
            current = r;
        } catch (final Throwable t) {
            LOG.warn("knowledge graph start failed: " + t.getClass().getSimpleName());
        }
    }

    /** Stops the runtime; called from ScoutroApiServlet.destroy. Never throws. */
    public static synchronized void stop() {
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (final Throwable t) {
            LOG.warn("knowledge graph stop failed: " + t.getClass().getSimpleName());
        }
        current = null;
    }

    /** The running instance, or null before start / after stop. */
    public static synchronized KgRuntime current() {
        return current;
    }

    // -------------------------------------------------------------- lifecycle

    /** Opens the graph if enabled; ends in RUNNING, DISABLED or UNAVAILABLE. Never throws. */
    public synchronized void open() {
        this.startedAt = this.env.clock.getAsLong();
        try {
            this.config = KgConfig.read(this.env.config);
            this.jsonld = new JsonLdCapturePolicy(this.config);
            if (!this.config.enabled) {
                set(State.DISABLED, null, null);
                return;
            }
            if (!this.config.valid()) {
                set(State.UNAVAILABLE, KgException.CONFIG_INVALID, this.config.problems().toString());
                LOG.warn("knowledge graph not started, invalid settings: " + this.config.problems());
                return;
            }
            this.paths = new KgPaths(this.env.dataRoot);
            this.guard = new StorageGuard(this.config, this.paths, this.env.probe, this.env.clock);
            this.guard.refresh();
            this.store = KgStore.open(this.paths, this.config, this.guard, this.env.connections, this.env.clock);
            markStarted();
            this.guard.refresh();
            this.lastMeasure = this.env.clock.getAsLong();
            if (this.env.monitorThread) {
                this.monitor = Executors.newSingleThreadScheduledExecutor(r -> {
                    final Thread t = new Thread(r, MONITOR_THREAD);
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
                this.monitor.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
            }
            set(State.RUNNING, null, null);
            LOG.info("knowledge graph store open: " + this.paths.db + (this.uncleanStartDetected ? " (unclean previous shutdown)" : ""));
        } catch (final KgException e) {
            closeStoreQuietly();
            set(State.UNAVAILABLE, e.code(), e.getMessage());
            LOG.warn("knowledge graph unavailable: " + e.code() + " " + e.getMessage());
        } catch (final LinkageError e) {
            closeStoreQuietly();
            set(State.UNAVAILABLE, KgException.NATIVE_LIBRARY_UNAVAILABLE, e.getClass().getSimpleName());
            LOG.warn("knowledge graph unavailable: SQLite could not be loaded (" + e.getClass().getSimpleName() + ")");
        } catch (final RuntimeException e) {
            closeStoreQuietly();
            set(State.UNAVAILABLE, KgException.START_FAILED, e.getClass().getSimpleName());
            LOG.warn("knowledge graph start failed: " + e);
        }
    }

    /**
     * Reads the clean-shutdown flag and the manual pause, then marks this run
     * as started. A flag left at 0 by the previous run means it ended without
     * {@link #close()}: the sync (package 2) must then run a full reconcile,
     * and the database gets a quick_check.
     * <p>
     * If the storage guard refuses the bookkeeping write (e.g. the disk is
     * below the critical floor), the runtime still starts, read-only, and the
     * monitor retries the mark until it succeeds ({@link #startRecorded}).
     */
    private void markStarted() throws KgException {
        final String[] flags = this.store.read(c -> new String[] {
                KgStore.getMeta(c, KgSchema.META_CLEAN_SHUTDOWN), KgStore.getMeta(c, KgSchema.META_MANUAL_PAUSE)});
        this.uncleanStartDetected = "0".equals(flags[0]);
        this.guard.setManualPause("1".equals(flags[1]));
        this.quickCheckPending = this.uncleanStartDetected;
        try {
            recordStart();
        } catch (final KgException e) {
            if (!KgException.WRITE_REFUSED.equals(e.code())) {
                throw e;
            }
            this.startRecorded = false;
            LOG.warn("knowledge graph started read-only: the start could not be recorded (" + e.reason() + "); retrying");
        }
    }

    private void recordStart() throws KgException {
        final long now = this.env.clock.getAsLong();
        final boolean unclean = this.uncleanStartDetected;
        this.store.write(WriteClass.MAINTENANCE, SMALL_WRITE_BYTES, tx -> {
            if (unclean) {
                KgStore.putMeta(tx, KgSchema.META_RECONCILE_REQUIRED, "1");
                KgStore.event(tx, 2, "unclean_start", "previous run ended without a clean shutdown", now);
            }
            KgStore.putMeta(tx, KgSchema.META_CLEAN_SHUTDOWN, "0");
            KgStore.putMeta(tx, KgSchema.META_LAST_START, Long.toString(now));
            KgStore.event(tx, 1, "start", null, now);
            return null;
        });
        this.startRecorded = true;
    }

    /** Clean shutdown: marks the flag, checkpoints and closes. Never throws. */
    public synchronized void close() {
        if (this.monitor != null) {
            this.monitor.shutdownNow();
            try {
                this.monitor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.monitor = null;
        }
        if (this.store != null) {
            final long now = this.env.clock.getAsLong();
            try {
                this.store.write(WriteClass.MAINTENANCE, SMALL_WRITE_BYTES, tx -> {
                    KgStore.putMeta(tx, KgSchema.META_CLEAN_SHUTDOWN, "1");
                    KgStore.event(tx, 1, "stop", null, now);
                    return null;
                });
            } catch (final KgException e) {
                LOG.warn("knowledge graph: clean shutdown could not be recorded (" + e.code() + "); the next start runs a reconcile");
            }
            closeStoreQuietly();
        }
        if (this.state == State.RUNNING) {
            set(State.STOPPED, null, null);
        }
    }

    private void closeStoreQuietly() {
        if (this.store != null) {
            try {
                this.store.close();
            } catch (final RuntimeException e) {
                // closing anyway
            }
            this.store = null;
        }
    }

    private void set(final State s, final String r, final String detail) {
        this.state = s;
        this.reason = r;
        this.reasonDetail = detail;
    }

    /** One monitor step: read deadlines every second, measurement and checkpoint every 30 seconds. */
    void tick() {
        final KgStore s = this.store;
        if (s == null || this.state != State.RUNNING) {
            return;
        }
        try {
            final long now = this.env.clock.getAsLong();
            s.interruptExpiredReaders(now);
            if (!this.startRecorded) {
                try {
                    recordStart();
                } catch (final KgException e) {
                    // still refused; the status shows the guard's reason
                }
            }
            if (this.quickCheckPending) {
                this.quickCheckPending = false;
                runQuickCheck();
            }
            if (now - this.lastMeasure >= MEASURE_EVERY_MILLIS) {
                this.lastMeasure = now;
                this.guard.refresh();
                if (this.guard.tmpOverLimit()) {
                    s.interruptAllReaders();
                }
                if (this.guard.walBytes() >= this.config.walCheckpointBytes) {
                    s.checkpoint();
                }
            }
        } catch (final KgException e) {
            // recorded by the guard and visible in the status
        } catch (final RuntimeException e) {
            LOG.warn("knowledge graph monitor step failed: " + e);
        }
    }

    private void runQuickCheck() {
        try {
            this.quickCheck = this.store.quickCheck(QUICK_CHECK_DEADLINE_MILLIS);
            if (!"ok".equals(this.quickCheck)) {
                this.guard.storageError("quick_check");
                LOG.warn("knowledge graph quick_check failed: " + this.quickCheck);
            }
        } catch (final KgException e) {
            this.quickCheck = "error:" + e.code();
        }
    }

    // ---------------------------------------------------------------- control

    public State state() {
        return this.state;
    }

    public String reason() {
        return this.reason;
    }

    /** Stops new growth (extraction and backfill in later packages); deletions continue. Persisted. */
    public synchronized JSONObject pause() throws KgException {
        requireRunning();
        this.guard.setManualPause(true);
        persistManualPause(true);
        return status();
    }

    /**
     * Ends a manual pause. After a storage error it first runs a quick_check
     * and clears the error only if the database is consistent.
     */
    public synchronized JSONObject resume() throws KgException {
        requireRunning();
        if (this.guard.storageErrorCode() != null) {
            runQuickCheck();
            if ("ok".equals(this.quickCheck)) {
                this.guard.clearStorageError();
            }
        }
        this.guard.setManualPause(false);
        persistManualPause(false);
        return status();
    }

    private void persistManualPause(final boolean paused) throws KgException {
        final long now = this.env.clock.getAsLong();
        this.store.write(WriteClass.MAINTENANCE, SMALL_WRITE_BYTES, tx -> {
            KgStore.putMeta(tx, KgSchema.META_MANUAL_PAUSE, paused ? "1" : "0");
            KgStore.event(tx, 1, paused ? "manual_pause" : "manual_resume", null, now);
            return null;
        });
    }

    private void requireRunning() throws KgException {
        if (this.state == State.DISABLED) {
            throw new KgException(KgException.DISABLED, "the knowledge graph is disabled (" + KgConfig.ENABLED + "=false)");
        }
        if (this.state != State.RUNNING || this.store == null) {
            throw new KgException(KgException.UNAVAILABLE, this.reason, "the knowledge graph is not running"
                    + (this.reason == null ? "" : " (" + this.reason + ")"), null);
        }
    }

    // ----------------------------------------------------------------- status

    /** Status contract {@value #STATUS_SCHEMA}; see docs/API.md. */
    public synchronized JSONObject status() {
        final JSONObject o = KgJson.obj("schema", STATUS_SCHEMA,
                "enabled", this.config != null && this.config.enabled,
                "state", this.state.name().toLowerCase(),
                "reason", this.reason, "reasonDetail", this.reasonDetail,
                "startedAt", this.startedAt > 0 ? this.startedAt : null,
                "config", this.config == null ? null : this.config.toJson());
        if (this.config == null || !this.config.enabled) {
            return o;
        }
        final boolean running = this.state == State.RUNNING && this.store != null;
        KgJson.put(o, "paths", KgJson.obj("dir", KgPaths.RELATIVE_DIR));
        if (this.guard != null) {
            final JSONObject storage = this.guard.status();
            if (running) {
                try {
                    KgJson.put(storage, "pages", this.store.pageStats());
                } catch (final KgException e) {
                    KgJson.put(storage, "pages", KgJson.obj("error", e.code()));
                }
                KgJson.put(storage, "readers", this.store.readerStatus(this.env.clock.getAsLong()));
            }
            KgJson.put(o, "storage", storage);
            // nothing is captured yet (package 2), so the field size in Solr is unknown
            this.jsonld.evaluate(running, null, diskUsable(storage));
        } else {
            this.jsonld.evaluate(false, null, 0L);
        }
        KgJson.put(o, "jsonld", this.jsonld.status());
        if (running) {
            KgJson.put(o, "store", KgJson.obj("schemaVersion", this.store.schemaVersion(), "epoch", this.store.epoch(),
                    "uncleanStartDetected", this.uncleanStartDetected, "startRecorded", this.startRecorded,
                    "quickCheck", this.quickCheck, "manualPause", this.guard.manualPause()));
            KgJson.put(o, "events", recentEvents());
        }
        return o;
    }

    private static long diskUsable(final JSONObject storage) {
        final JSONObject disk = storage.optJSONObject("disk");
        return disk == null ? 0L : disk.optLong("usableBytes", 0L);
    }

    private JSONArray recentEvents() {
        try {
            return this.store.read(c -> events(c));
        } catch (final KgException e) {
            return new JSONArray();
        }
    }

    private static JSONArray events(final Connection c) throws SQLException {
        final JSONArray a = new JSONArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT at, level, code, detail FROM kg_event ORDER BY seq DESC LIMIT ?")) {
            ps.setInt(1, STATUS_EVENTS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final int level = rs.getInt(2);
                    a.put(KgJson.obj("at", rs.getLong(1), "level", level == 1 ? "info" : level == 2 ? "warn" : "error",
                            "code", rs.getString(3), "detail", rs.getString(4)));
                }
            }
        }
        return a;
    }

    /** For tests: the store while running. */
    KgStore store() {
        return this.store;
    }

    /** For tests: the guard while enabled. */
    StorageGuard guard() {
        return this.guard;
    }

    /** For tests: whether this run's start is written to the store. */
    boolean startRecorded() {
        return this.startRecorded;
    }

    /** For tests: whether the previous run ended without a clean shutdown. */
    boolean uncleanStartDetected() {
        return this.uncleanStartDetected;
    }
}
