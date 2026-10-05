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
 * detected through the {@code clean_shutdown} flag, and two daemon threads
 * start. The watchdog ({@value #WATCHDOG_THREAD}) only enforces read
 * deadlines, every {@value #WATCHDOG_MILLIS} ms; it never runs SQL and never
 * waits for the write lock, so nothing the maintenance thread does can keep
 * it from interrupting a read. The maintenance thread
 * ({@value #MAINTENANCE_THREAD}) runs the supervised work: the integrity check
 * after an unclean shutdown, measurement, checkpoints and retries.
 * <p>
 * After an unclean shutdown graph writes (growth and maintenance) stay blocked
 * until {@code PRAGMA quick_check} has passed; a failed or aborted check keeps
 * the block with a visible reason. No failure here may stop Scoutro: every
 * problem ends in the state {@link State#UNAVAILABLE} with a reason, and the
 * rest of Scoutro runs on.
 */
public final class KgRuntime {

    public enum State { DISABLED, RUNNING, UNAVAILABLE, STOPPED }

    /** The integrity check required after an unclean shutdown or requested after a storage error. */
    public enum Integrity { NOT_REQUIRED, PENDING, RUNNING, OK, FAILED, ABORTED }

    public static final String STATUS_SCHEMA = "scoutro.kg.status.v1";
    public static final String WATCHDOG_THREAD = "ScoutroKG.watchdog";
    public static final String MAINTENANCE_THREAD = "ScoutroKG.maintenance";
    public static final String SHUTDOWN_HOOK_THREAD = "ScoutroKG.shutdown";
    /** Watchdog period: a read is interrupted at most this long after its deadline. */
    public static final long WATCHDOG_MILLIS = 250L;

    /** Triggers of the integrity check. */
    public static final String TRIGGER_UNCLEAN_START = "unclean_start";
    public static final String TRIGGER_INCOMPLETE_CHECK = "incomplete_check";
    public static final String TRIGGER_RESUME = "resume";

    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-KG");
    private static final long SMALL_WRITE_BYTES = 64L * 1024L;
    private static final long MAINTENANCE_MILLIS = 1000L;
    private static final long MEASURE_EVERY_MILLIS = 30_000L;
    private static final long STOP_WAIT_MILLIS = 5000L;
    private static final int STATUS_EVENTS = 20;

    /** The integrity check: {@code PRAGMA quick_check}, "ok" if the database is consistent. */
    static final KgStore.SqlWork<String> QUICK_CHECK = c -> KgStore.queryString(c, "PRAGMA quick_check(1)");

    private static KgRuntime current;
    private static Thread shutdownHook;

    /** Everything the runtime needs from its surroundings; replaceable in tests. */
    public static final class Env {
        final File dataRoot;
        final Function<String, String> config;
        final LongSupplier clock;
        final StorageProbe probe;
        final KgStore.ConnectionFactory connections;
        final boolean monitorThread;
        final KgStore.SqlWork<String> integrityCheck;

        public Env(final File dataRoot, final Function<String, String> config, final LongSupplier clock,
                final StorageProbe probe, final KgStore.ConnectionFactory connections, final boolean monitorThread) {
            this(dataRoot, config, clock, probe, connections, monitorThread, QUICK_CHECK);
        }

        private Env(final File dataRoot, final Function<String, String> config, final LongSupplier clock,
                final StorageProbe probe, final KgStore.ConnectionFactory connections, final boolean monitorThread,
                final KgStore.SqlWork<String> integrityCheck) {
            this.dataRoot = dataRoot;
            this.config = config;
            this.clock = clock;
            this.probe = probe;
            this.connections = connections;
            this.monitorThread = monitorThread;
            this.integrityCheck = integrityCheck;
        }

        /** The same environment with another integrity check (tests). */
        Env withIntegrityCheck(final KgStore.SqlWork<String> check) {
            return new Env(this.dataRoot, this.config, this.clock, this.probe, this.connections, this.monitorThread, check);
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
    private volatile KgStore store;
    private ScheduledExecutorService watchdog;
    private ScheduledExecutorService maintenance;
    private volatile boolean closing;
    private boolean uncleanStartDetected;
    /** False while the start could not be written; the guard holds graph writes back until it is true. */
    private volatile boolean startRecorded = true;
    /** A manual pause change that is in effect but not yet stored (the guard refused the write); null if stored. */
    private volatile Boolean unsavedManualPause;
    private final Object pauseLock = new Object();
    private long startedAt;
    private long lastMeasure;

    private final Object integrityLock = new Object();
    private Integrity integrity = Integrity.NOT_REQUIRED;
    private String integrityTrigger;
    private String integrityResult;
    private String integrityError;
    private long integrityStartedAt;
    private long integrityFinishedAt;

    public KgRuntime(final Env env) {
        this.env = env;
    }

    // ---------------------------------------------------------- static access

    /**
     * Starts the runtime once; called from ScoutroApiServlet.init. Never throws.
     * <p>
     * While the graph runs, a JVM shutdown hook closes it as soon as the JVM
     * shuts down (SIGTERM, {@code docker stop}). YaCy stops its HTTP server,
     * and with it the servlet that calls {@link #stop()}, only after its main
     * thread has finished, and its own shutdown hook lets the JVM exit after 30
     * seconds; a slow YaCy shutdown would otherwise leave the clean-shutdown
     * mark unwritten. Nothing is registered while the graph is disabled.
     */
    public static synchronized void start() {
        if (current != null) {
            return;
        }
        try {
            final Switchboard sb = Switchboard.getSwitchboard();
            if (sb != null) {
                start(Env.of(sb));
            }
        } catch (final Throwable t) {
            LOG.warn("knowledge graph start failed: " + t.getClass().getSimpleName());
        }
    }

    /** {@link #start()} with a given environment (tests). */
    static synchronized void start(final Env env) {
        if (current != null) {
            return;
        }
        try {
            final KgRuntime r = new KgRuntime(env);
            r.open();
            current = r;
            if (r.state() == State.RUNNING) {
                final Thread hook = new Thread(KgRuntime::stop, SHUTDOWN_HOOK_THREAD);
                try {
                    Runtime.getRuntime().addShutdownHook(hook);
                    shutdownHook = hook;
                } catch (final IllegalStateException | SecurityException e) {
                    // already shutting down, or not allowed: the servlet's destroy() still stops the graph
                }
            }
        } catch (final Throwable t) {
            LOG.warn("knowledge graph start failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Stops the runtime; called from ScoutroApiServlet.destroy and from the
     * shutdown hook, whichever comes first. Never throws.
     */
    public static synchronized void stop() {
        final Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook != null && hook != Thread.currentThread()) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (final IllegalStateException | SecurityException e) {
                // the JVM is shutting down: the hook waits for this call and then finds nothing to stop
            }
        }
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
            set(State.RUNNING, null, null);
            if (this.env.monitorThread) {
                this.watchdog = daemon(WATCHDOG_THREAD);
                this.watchdog.scheduleWithFixedDelay(this::watchdogTick, WATCHDOG_MILLIS, WATCHDOG_MILLIS, TimeUnit.MILLISECONDS);
                this.maintenance = daemon(MAINTENANCE_THREAD);
                this.maintenance.scheduleWithFixedDelay(this::tick, 0L, MAINTENANCE_MILLIS, TimeUnit.MILLISECONDS);
            }
            LOG.info("knowledge graph store open: " + this.paths.db + (this.uncleanStartDetected
                    ? " (unclean previous shutdown; graph writes wait for the integrity check)" : ""));
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

    private static ScheduledExecutorService daemon(final String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /**
     * Reads the clean-shutdown flag, the manual pause and the integrity flag,
     * then marks this run as started. A clean-shutdown flag left at 0 by the
     * previous run means it ended without {@link #close()}: the sync (package
     * 2) must then run a full reconcile, and graph writes wait for the
     * integrity check. The integrity flag stays set across a clean stop until
     * a check has passed.
     * <p>
     * If the storage guard refuses the bookkeeping write (e.g. the disk is
     * below the critical floor), the runtime still starts, read-only, and the
     * maintenance thread retries the mark until it succeeds; graph writes wait
     * for it ({@link StorageGuard#START_NOT_RECORDED}).
     */
    private void markStarted() throws KgException {
        final String[] flags = this.store.read(c -> new String[] {KgStore.getMeta(c, KgSchema.META_CLEAN_SHUTDOWN),
                KgStore.getMeta(c, KgSchema.META_MANUAL_PAUSE), KgStore.getMeta(c, KgSchema.META_INTEGRITY_REQUIRED)});
        this.uncleanStartDetected = "0".equals(flags[0]);
        this.guard.setManualPause("1".equals(flags[1]));
        if (this.uncleanStartDetected) {
            requireIntegrityCheck(TRIGGER_UNCLEAN_START);
        } else if ("1".equals(flags[2])) {
            requireIntegrityCheck(TRIGGER_INCOMPLETE_CHECK);
        }
        this.guard.setStartNotRecorded(true);
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
        final boolean checkOutstanding = integrityOutstanding();
        this.store.write(WriteClass.SYSTEM, SMALL_WRITE_BYTES, tx -> {
            if (unclean) {
                KgStore.putMeta(tx, KgSchema.META_RECONCILE_REQUIRED, "1");
                KgStore.event(tx, 2, "unclean_start", "previous run ended without a clean shutdown", now);
            }
            if (checkOutstanding) {
                KgStore.putMeta(tx, KgSchema.META_INTEGRITY_REQUIRED, "1");
            }
            KgStore.putMeta(tx, KgSchema.META_CLEAN_SHUTDOWN, "0");
            KgStore.putMeta(tx, KgSchema.META_LAST_START, Long.toString(now));
            KgStore.event(tx, 1, "start", null, now);
            return null;
        });
        this.startRecorded = true;
        this.guard.setStartNotRecorded(false);
    }

    /**
     * Clean shutdown: stops the maintenance thread (a running integrity check
     * is interrupted and stays required), then the watchdog, marks the flag,
     * checkpoints and closes. Never throws.
     */
    public synchronized void close() {
        this.closing = true;
        final KgStore s = this.store;
        if (this.maintenance != null) {
            this.maintenance.shutdownNow();
            if (s != null) {
                s.interruptAllReaders();
            }
            awaitQuietly(this.maintenance);
            this.maintenance = null;
        }
        if (this.watchdog != null) {
            this.watchdog.shutdownNow();
            awaitQuietly(this.watchdog);
            this.watchdog = null;
        }
        if (s != null) {
            final long now = this.env.clock.getAsLong();
            final Boolean unsaved = this.unsavedManualPause;
            try {
                s.write(WriteClass.SYSTEM, SMALL_WRITE_BYTES, tx -> {
                    if (unsaved != null) {
                        KgStore.putMeta(tx, KgSchema.META_MANUAL_PAUSE, unsaved ? "1" : "0");
                    }
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

    private static void awaitQuietly(final ScheduledExecutorService e) {
        try {
            e.awaitTermination(STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeStoreQuietly() {
        final KgStore s = this.store;
        if (s != null) {
            try {
                s.close();
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

    // ------------------------------------------------------------ background

    /** Watchdog step: interrupts reads past their deadline. No SQL, no locks shared with the work it supervises. */
    void watchdogTick() {
        final KgStore s = this.store;
        if (s == null) {
            return;
        }
        try {
            s.interruptExpiredReaders(this.env.clock.getAsLong());
        } catch (final RuntimeException e) {
            LOG.warn("knowledge graph watchdog step failed: " + e);
        }
    }

    /**
     * Maintenance step, every second: records a start the guard refused
     * earlier, stores an unsaved manual pause, runs a pending integrity check,
     * and every 30 seconds measures storage and checkpoints the WAL. Its reads
     * are leases; the watchdog interrupts them at their deadline.
     */
    void tick() {
        final KgStore s = this.store;
        if (s == null || this.state != State.RUNNING || this.closing) {
            return;
        }
        try {
            if (!this.startRecorded) {
                try {
                    recordStart();
                } catch (final KgException e) {
                    // still refused; the status shows the guard's reason
                }
            }
            if (this.unsavedManualPause != null) {
                try {
                    saveManualPause();
                } catch (final KgException e) {
                    // kept in memory, retried
                }
            }
            if (beginIntegrityCheck()) {
                runIntegrityCheck(s);
            }
            final long now = this.env.clock.getAsLong();
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
            LOG.warn("knowledge graph maintenance step failed: " + e);
        }
    }

    // -------------------------------------------------------------- integrity

    private void requireIntegrityCheck(final String trigger) {
        synchronized (this.integrityLock) {
            if (this.integrity == Integrity.PENDING || this.integrity == Integrity.RUNNING) {
                return;
            }
            this.integrity = Integrity.PENDING;
            this.integrityTrigger = trigger;
            this.integrityResult = null;
            this.integrityError = null;
            this.integrityStartedAt = 0L;
            this.integrityFinishedAt = 0L;
        }
        this.guard.setIntegrityBlock(StorageGuard.INTEGRITY_PENDING, trigger);
    }

    /** True if a check is required and has not passed yet. */
    private boolean integrityOutstanding() {
        synchronized (this.integrityLock) {
            return this.integrity != Integrity.NOT_REQUIRED && this.integrity != Integrity.OK;
        }
    }

    private boolean beginIntegrityCheck() {
        synchronized (this.integrityLock) {
            if (this.integrity != Integrity.PENDING) {
                return false;
            }
            this.integrity = Integrity.RUNNING;
            this.integrityStartedAt = this.env.clock.getAsLong();
            return true;
        }
    }

    /**
     * Runs the check as one read lease with the deadline
     * {@code scoutro.kg.integrity.maxMillis}; the watchdog interrupts it when
     * the deadline passes ({@code sqlite3_interrupt} stops quick_check within
     * milliseconds).
     */
    private void runIntegrityCheck(final KgStore s) {
        String result = null;
        KgException failure = null;
        try {
            result = s.read(this.env.integrityCheck, this.config.integrityMaxMillis);
        } catch (final KgException e) {
            failure = e;
        }
        final long now = this.env.clock.getAsLong();
        final long took;
        synchronized (this.integrityLock) {
            took = now - this.integrityStartedAt;
            this.integrityFinishedAt = now;
            if (failure != null) {
                this.integrity = Integrity.ABORTED;
                this.integrityError = failure.code();
            } else if ("ok".equals(result)) {
                this.integrity = Integrity.OK;
                this.integrityResult = "ok";
            } else {
                this.integrity = Integrity.FAILED;
                this.integrityResult = clip(String.valueOf(result));
            }
        }
        if (failure != null) {
            this.guard.setIntegrityBlock(StorageGuard.INTEGRITY_FAILED, "aborted: " + failure.code() + " after " + took + " ms");
            LOG.warn("knowledge graph integrity check aborted after " + took + " ms: " + failure.code());
            if (!this.closing) {
                recordEvent(2, "integrity_aborted", failure.code() + " after " + took + " ms", false);
            }
        } else if ("ok".equals(result)) {
            this.guard.clearStorageError();
            this.guard.setIntegrityBlock(null, null);
            LOG.info("knowledge graph integrity check passed in " + took + " ms");
            recordEvent(1, "integrity_ok", took + " ms", true);
        } else {
            // a damaged database: every write stops, the stored flag keeps the check required
            this.guard.storageError("integrity_check");
            this.guard.setIntegrityBlock(StorageGuard.INTEGRITY_FAILED, "quick_check: " + clip(String.valueOf(result)));
            LOG.warn("knowledge graph integrity check failed: " + result);
        }
    }

    private void recordEvent(final int level, final String code, final String detail, final boolean integrityPassed) {
        final long now = this.env.clock.getAsLong();
        try {
            this.store.write(WriteClass.SYSTEM, SMALL_WRITE_BYTES, tx -> {
                if (integrityPassed) {
                    KgStore.putMeta(tx, KgSchema.META_INTEGRITY_REQUIRED, "0");
                }
                KgStore.event(tx, level, code, detail, now);
                return null;
            });
        } catch (final KgException | RuntimeException e) {
            // the next start checks again if the passed check could not be stored
        }
    }

    private static String clip(final String v) {
        final String line = v.replace('\n', ' ');
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    // ---------------------------------------------------------------- control

    public State state() {
        return this.state;
    }

    public String reason() {
        return this.reason;
    }

    /**
     * Stops new growth (extraction and backfill in later packages); deletions
     * continue. Takes effect at once and is stored; if the guard refuses the
     * write, the maintenance thread stores it later.
     */
    public synchronized JSONObject pause() throws KgException {
        requireRunning();
        this.guard.setManualPause(true);
        saveManualPause();
        return status();
    }

    /**
     * Ends a manual pause. After a storage error or a failed or aborted
     * integrity check it also requests a new check; graph writes resume only
     * when it has passed.
     */
    public synchronized JSONObject resume() throws KgException {
        requireRunning();
        final boolean recheck;
        synchronized (this.integrityLock) {
            recheck = this.integrity == Integrity.FAILED || this.integrity == Integrity.ABORTED;
        }
        if (recheck || this.guard.storageErrorCode() != null) {
            requireIntegrityCheck(TRIGGER_RESUME);
        }
        this.guard.setManualPause(false);
        saveManualPause();
        return status();
    }

    /**
     * Stores the manual pause that is in effect. If the guard refuses the
     * system write (e.g. after a storage error), the pause stays in effect in
     * memory and the maintenance thread stores it later.
     */
    private void saveManualPause() throws KgException {
        synchronized (this.pauseLock) {
            final boolean paused = this.guard.manualPause();
            final long now = this.env.clock.getAsLong();
            try {
                this.store.write(WriteClass.SYSTEM, SMALL_WRITE_BYTES, tx -> {
                    KgStore.putMeta(tx, KgSchema.META_MANUAL_PAUSE, paused ? "1" : "0");
                    KgStore.event(tx, 1, paused ? "manual_pause" : "manual_resume", null, now);
                    return null;
                });
                this.unsavedManualPause = null;
            } catch (final KgException e) {
                this.unsavedManualPause = paused;
                if (!KgException.WRITE_REFUSED.equals(e.code())) {
                    throw e;
                }
            }
        }
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
        final KgStore s = this.store;
        final boolean running = this.state == State.RUNNING && s != null;
        KgJson.put(o, "paths", KgJson.obj("dir", KgPaths.RELATIVE_DIR));
        if (this.guard != null) {
            final JSONObject storage = this.guard.status();
            if (running) {
                try {
                    KgJson.put(storage, "pages", s.pageStats());
                } catch (final KgException e) {
                    KgJson.put(storage, "pages", KgJson.obj("error", e.code()));
                }
                KgJson.put(storage, "readers", s.readerStatus(this.env.clock.getAsLong()));
            }
            KgJson.put(o, "storage", storage);
            // nothing is captured yet (package 2), so the field size in Solr is unknown
            this.jsonld.evaluate(running, null, diskUsable(storage));
        } else {
            this.jsonld.evaluate(false, null, 0L);
        }
        KgJson.put(o, "jsonld", this.jsonld.status());
        if (running) {
            KgJson.put(o, "store", KgJson.obj("schemaVersion", s.schemaVersion(), "epoch", s.epoch(),
                    "uncleanStartDetected", this.uncleanStartDetected, "startRecorded", this.startRecorded,
                    "integrity", integrityStatus(), "manualPause", this.guard.manualPause(),
                    "manualPauseSaved", this.unsavedManualPause == null));
            KgJson.put(o, "events", recentEvents(s));
        }
        return o;
    }

    private JSONObject integrityStatus() {
        synchronized (this.integrityLock) {
            return KgJson.obj("state", this.integrity.name().toLowerCase(),
                    "blocksGraphWrites", this.integrity != Integrity.NOT_REQUIRED && this.integrity != Integrity.OK,
                    "trigger", this.integrityTrigger, "result", this.integrityResult, "error", this.integrityError,
                    "startedAt", this.integrityStartedAt > 0 ? this.integrityStartedAt : null,
                    "finishedAt", this.integrityFinishedAt > 0 ? this.integrityFinishedAt : null,
                    "maxMillis", this.config.integrityMaxMillis);
        }
    }

    private static long diskUsable(final JSONObject storage) {
        final JSONObject disk = storage.optJSONObject("disk");
        return disk == null ? 0L : disk.optLong("usableBytes", 0L);
    }

    private static JSONArray recentEvents(final KgStore s) {
        try {
            return s.read(c -> events(c));
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

    /** For tests: whether the shutdown hook is registered. */
    static synchronized boolean shutdownHookRegistered() {
        return shutdownHook != null;
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

    /** For tests: the state of the integrity check. */
    Integrity integrity() {
        synchronized (this.integrityLock) {
            return this.integrity;
        }
    }
}
