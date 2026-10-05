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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.solr.client.solrj.SolrClient;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.cora.util.Memory;
import net.yacy.kelondro.util.MemoryControl;
import net.yacy.scoutro.knowledge.budget.JsonLdCapturePolicy;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.budget.StorageProbe;
import net.yacy.scoutro.knowledge.extract.LlmClient;
import net.yacy.scoutro.knowledge.extract.YacyLlmClient;
import net.yacy.scoutro.knowledge.read.KgReader;
import net.yacy.scoutro.knowledge.store.KgBackup;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.Capture;
import net.yacy.scoutro.knowledge.sync.DirtySet;
import net.yacy.scoutro.knowledge.sync.EmbeddedSolrSource;
import net.yacy.scoutro.knowledge.sync.Gates;
import net.yacy.scoutro.knowledge.sync.JsonLdCapture;
import net.yacy.scoutro.knowledge.sync.LlmService;
import net.yacy.scoutro.knowledge.sync.Reconciler;
import net.yacy.scoutro.knowledge.sync.SyncService;
import net.yacy.search.Switchboard;

/**
 * Lifecycle of the knowledge graph (package 1: store, budget, status;
 * package 2a: synchronisation with Solr).
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
 * after an unclean shutdown, measurement, checkpoints and retries. With the
 * embedded Solr core available, the sync thread ({@value #SYNC_THREAD}) follows
 * the index: the capture processor records every change, and every start
 * schedules a full reconcile (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, section 5).
 * With {@code llm.collections} set, the extract threads ({@value #EXTRACT_THREAD})
 * run the optional LLM tier ({@link LlmService}); they hold no lock while a
 * model call runs, and a stop never waits for a call longer than two seconds.
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
    public static final String SYNC_THREAD = SyncService.THREAD;
    /** The LLM tier's threads ({@code llm.parallel}); only while {@code llm.collections} is set. */
    public static final String EXTRACT_THREAD = "ScoutroKG.extract";
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
    private static final long SYNC_DELAY_MILLIS = 200L;
    private static final long SYNC_SLICE_MILLIS = 1000L;
    private static final int SYNC_SLICE_STEPS = 1000;
    private static final long FINAL_DRAIN_MILLIS = 2000L;
    private static final long LLM_FIRST_DELAY_MILLIS = 2000L;
    private static final long LLM_DELAY_MILLIS = 500L;
    private static final long LLM_STOP_WAIT_MILLIS = 2000L;

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
        /** The embedded collection1 client (null when only a remote Solr is connected); the whole supplier null = no sync. */
        final Supplier<SolrClient> solr;
        final Gates.Probe system;
        /** The LLM tier's model (Scoutro's LLM selection, usage knowledge). */
        final LlmClient llm;

        /** Without Solr synchronisation (store, budget and status only). */
        public Env(final File dataRoot, final Function<String, String> config, final LongSupplier clock,
                final StorageProbe probe, final KgStore.ConnectionFactory connections, final boolean monitorThread) {
            this(dataRoot, config, clock, probe, connections, monitorThread, QUICK_CHECK, null, Gates.IDLE, LlmClient.NONE);
        }

        /** With Solr synchronisation through {@code solr}. */
        public Env(final File dataRoot, final Function<String, String> config, final LongSupplier clock,
                final StorageProbe probe, final KgStore.ConnectionFactory connections, final boolean monitorThread,
                final Supplier<SolrClient> solr, final Gates.Probe system) {
            this(dataRoot, config, clock, probe, connections, monitorThread, QUICK_CHECK, solr, system, LlmClient.NONE);
        }

        private Env(final File dataRoot, final Function<String, String> config, final LongSupplier clock,
                final StorageProbe probe, final KgStore.ConnectionFactory connections, final boolean monitorThread,
                final KgStore.SqlWork<String> integrityCheck, final Supplier<SolrClient> solr, final Gates.Probe system,
                final LlmClient llm) {
            this.dataRoot = dataRoot;
            this.config = config;
            this.clock = clock;
            this.probe = probe;
            this.connections = connections;
            this.monitorThread = monitorThread;
            this.integrityCheck = integrityCheck;
            this.solr = solr;
            this.system = system;
            this.llm = llm;
        }

        /** The same environment with another integrity check (tests). */
        Env withIntegrityCheck(final KgStore.SqlWork<String> check) {
            return new Env(this.dataRoot, this.config, this.clock, this.probe, this.connections, this.monitorThread, check,
                    this.solr, this.system, this.llm);
        }

        /** The same environment with another LLM model (tests). */
        Env withLlm(final LlmClient client) {
            return new Env(this.dataRoot, this.config, this.clock, this.probe, this.connections, this.monitorThread,
                    this.integrityCheck, this.solr, this.system, client);
        }

        static Env of(final Switchboard sb) {
            final Supplier<SolrClient> solr = () -> {
                final EmbeddedInstance e = sb.index.fulltext().getEmbeddedInstance();
                return e == null ? null : e.getDefaultServer();
            };
            final Gates.Probe system = new Gates.Probe() {
                @Override
                public int indexingQueue() {
                    return sb.getIndexingProcessorsQueueSize();
                }

                @Override
                public double load() {
                    return Memory.getSystemLoadAverage();
                }

                @Override
                public long freeHeapBytes() {
                    return MemoryControl.available();
                }

                @Override
                public String onlineCaution() {
                    return sb.onlineCaution();
                }
            };
            return new Env(sb.getDataPath(), key -> sb.getConfig(key, null), System::currentTimeMillis,
                    StorageProbe.SYSTEM, KgStore.SQLITE, true, QUICK_CHECK, solr, system, new YacyLlmClient());
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
    private ScheduledExecutorService syncThread;
    private DirtySet dirty;
    private volatile SyncService sync;
    private volatile LlmService llm;
    private ScheduledExecutorService llmThreads;
    private volatile KgBackups backups;
    private volatile Long jsonldEstimate;
    private volatile boolean closing;
    private boolean uncleanStartDetected;
    /** False while the start could not be written; the guard holds graph writes back until it is true. */
    private volatile boolean startRecorded = true;
    /** A manual pause change that is in effect but not yet stored (the guard refused the write); null if stored. */
    private volatile Boolean unsavedManualPause;
    private final Object pauseLock = new Object();
    private long startedAt;
    private long lastMeasure;
    /** The last recorded levels of the graph and JSON-LD budgets (events on every change). */
    private String storageLevel = KgConfig.LEVEL_OK;
    private String jsonldLevel = KgConfig.LEVEL_OK;

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
        this.closing = false;
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
            final String[] backupMeta = this.store.read(c -> new String[] {KgStore.getMeta(c, KgSchema.META_LAST_BACKUP_AT),
                    KgStore.getMeta(c, KgSchema.META_CREATED_AT)});
            this.backups = new KgBackups(() -> this.store, this.paths, this.config, this.guard, this.env.clock,
                    (level, code, detail) -> recordEvent(level, code, detail, false), parseLong(backupMeta[0]), parseLong(backupMeta[1]));
            set(State.RUNNING, null, null);
            if (this.env.solr != null) {
                startSync();
            }
            if (this.env.monitorThread) {
                this.watchdog = daemon(WATCHDOG_THREAD);
                this.watchdog.scheduleWithFixedDelay(this::watchdogTick, WATCHDOG_MILLIS, WATCHDOG_MILLIS, TimeUnit.MILLISECONDS);
                this.maintenance = daemon(MAINTENANCE_THREAD);
                this.maintenance.scheduleWithFixedDelay(this::tick, 0L, MAINTENANCE_MILLIS, TimeUnit.MILLISECONDS);
                if (this.sync != null) {
                    this.syncThread = daemon(SYNC_THREAD);
                    this.syncThread.scheduleWithFixedDelay(this::syncTick, 0L, SYNC_DELAY_MILLIS, TimeUnit.MILLISECONDS);
                }
                if (this.llm != null) {
                    this.llmThreads = daemons(EXTRACT_THREAD, this.config.llmParallel);
                    for (int i = 0; i < this.config.llmParallel; i++) {
                        this.llmThreads.scheduleWithFixedDelay(this::llmTick, LLM_FIRST_DELAY_MILLIS, LLM_DELAY_MILLIS, TimeUnit.MILLISECONDS);
                    }
                }
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

    /**
     * Starts following Solr: the capture processor records from now on, so
     * no change between this point and the reconcile the sync schedules at
     * its start can be missed.
     */
    private void startSync() {
        this.dirty = new DirtySet(this.config.captureMaxPending);
        Capture.activate(this.dirty);
        final EmbeddedSolrSource source = new EmbeddedSolrSource(this.env.solr);
        final Gates gates = new Gates(this.config, this.env.system);
        this.sync = new SyncService(this.config, this.store, this.dirty, source, gates, this.env.clock, this.uncleanStartDetected);
        if (this.config.llmEnabled()) {
            this.llm = new LlmService(this.config, this.store, source, gates, this.env.llm, this.env.clock);
            this.sync.onLlmInput(this.llm::wake);
        }
        updateJsonLdCapture();
    }

    private static ScheduledExecutorService daemons(final String name, final int n) {
        final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        return Executors.newScheduledThreadPool(n, r -> {
            final int i = seq.incrementAndGet();
            final Thread t = new Thread(r, i == 1 ? name : name + "-" + i);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
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
     * previous run means it ended without {@link #close()}: graph writes wait
     * for the integrity check, and the sync enqueues the recent changes
     * ({@code _version_} catch-up) before the full reconcile that every start
     * runs. The integrity flag stays set across a clean stop until
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
        // the sync first: it finishes its step, the capture stops, and what it recorded reaches the persistent queue.
        // No Thread.interrupt here: the sync thread may be inside Solr, whose update log uses interruptible channels.
        // the LLM tier first: a call in flight is abandoned (no interrupt, see above); its result is never written
        if (this.llm != null) {
            this.llm.requestStop();
        }
        if (this.llmThreads != null) {
            this.llmThreads.shutdown();
            try {
                this.llmThreads.awaitTermination(LLM_STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            this.llmThreads = null;
        }
        this.llm = null;
        if (this.sync != null) {
            this.sync.requestStop();
        }
        if (this.syncThread != null) {
            this.syncThread.shutdown();
            awaitQuietly(this.syncThread);
            this.syncThread = null;
        }
        if (this.dirty != null) {
            Capture.deactivate(this.dirty);
        }
        if (this.sync != null) {
            JsonLdCapture.off();
            this.sync.finalDrain(this.env.clock.getAsLong() + FINAL_DRAIN_MILLIS);
            this.sync = null;
        }
        if (this.maintenance != null) {
            this.maintenance.shutdownNow();
            if (s != null) {
                s.interruptAllReaders();
            }
            awaitQuietly(this.maintenance);
            this.maintenance = null;
        }
        final KgBackups b = this.backups;
        if (b != null) {
            if (s != null) {
                s.interruptBackup();
            }
            b.stop();
            this.backups = null;
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

    /** Sync step: runs bounded sync steps for up to a second, then yields. */
    void syncTick() {
        final SyncService s = this.sync;
        if (s == null || this.state != State.RUNNING || this.closing) {
            return;
        }
        final long until = this.env.clock.getAsLong() + SYNC_SLICE_MILLIS;
        int steps = 0;
        while (s.step() && !this.closing && !Thread.currentThread().isInterrupted() && this.env.clock.getAsLong() < until
                && ++steps < SYNC_SLICE_STEPS) {
            // more work is due right away
        }
    }

    /** Extract step: one document at a time, for up to a second, then yields. */
    void llmTick() {
        final LlmService l = this.llm;
        if (l == null || this.state != State.RUNNING || this.closing) {
            return;
        }
        final long until = this.env.clock.getAsLong() + SYNC_SLICE_MILLIS;
        while (l.step() && !this.closing && this.env.clock.getAsLong() < until) {
            // the next document is due right away
        }
    }

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
                updateJsonLdCapture();
                this.storageLevel = noteLevel("storage_level", this.storageLevel, this.guard.level(), this.config.budgetMaxBytes);
                // the backup schedule, after the measurement (a backup needs a growth admission)
                final KgBackups b = this.backups;
                if (b != null) {
                    b.tick(now);
                }
                this.jsonldLevel = noteLevel("jsonld_level", this.jsonldLevel, this.jsonld.level(), this.config.jsonldMaxTotalBytes);
            }
        } catch (final KgException e) {
            // recorded by the guard and visible in the status
        } catch (final RuntimeException e) {
            LOG.warn("knowledge graph maintenance step failed: " + e);
        }
    }

    /**
     * Records a change of a budget level as an event (info when it falls or
     * reaches notice, warning from warning on) and logs warnings.
     */
    private String noteLevel(final String code, final String before, final String now, final long budget) {
        if (now.equals(before)) {
            return before;
        }
        final boolean rising = KgConfig.LEVELS.indexOf(now) > KgConfig.LEVELS.indexOf(before);
        final boolean serious = rising && KgConfig.LEVELS.indexOf(now) >= KgConfig.LEVELS.indexOf(KgConfig.LEVEL_WARNING);
        final String detail = before + " -> " + now + " (budget " + budget + " bytes)";
        if (serious) {
            LOG.warn("knowledge graph " + code.replace('_', ' ') + ": " + detail);
        }
        recordEvent(serious ? 2 : 1, code, detail, false);
        return now;
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
        final SyncService s = this.sync;
        if (s != null) {
            // a reactivation: Solr may have changed in ways the events did not carry (overflow while blocked)
            s.requestReconcile(Reconciler.REASON_RESUME);
        }
        return status();
    }

    /** Schedules a full reconcile now ({@code POST /kg/control {"action":"reconcile"}}). */
    public synchronized JSONObject reconcile() throws KgException {
        requireRunning();
        requireSync().requestReconcile(Reconciler.REASON_ADMIN);
        return status();
    }

    /**
     * Lets a reconcile that the mass-deletion brake stopped delete the
     * documents it confirmed as absent ({@code confirm_reconcile}).
     */
    public synchronized JSONObject confirmReconcile() throws KgException {
        requireRunning();
        requireSync().confirmReconcile();
        return status();
    }

    /**
     * Makes the documents the LLM tier gave up on due again
     * ({@code POST /kg/control {"action":"llm_retry"}}); also closes the
     * circuit breaker.
     */
    public synchronized JSONObject llmRetry() throws KgException {
        requireRunning();
        final LlmService l = this.llm;
        if (l == null) {
            throw new KgException(KgException.LLM_UNAVAILABLE, "the LLM tier is off (no llm.collections, or no Solr synchronisation)");
        }
        final int n = l.retryFailed();
        final JSONObject o = status();
        KgJson.put(o, "reopened", n);
        return o;
    }

    /** The read projection over the running store (package 3); reads stay available while growth is paused. */
    public KgReader reader() throws KgException {
        requireRunning();
        return new KgReader(this.store, this.config, this.env.clock);
    }

    /** The settings of the running graph; null before it was configured. */
    public KgConfig config() {
        return this.config;
    }

    /**
     * Facts for the RAG chat (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 8.4): over the
     * evidence of {@code collection}, or of every followed collection when it
     * is null. A collection the graph does not follow sees nothing.
     */
    public java.util.List<net.yacy.scoutro.knowledge.read.ChatFacts.Entry> chatFacts(final String collection,
            final java.util.List<String> docIds, final java.util.List<String> terms, final int maxFacts, final long deadlineMillis)
            throws KgException {
        final KgReader reader = reader();
        final KgConfig cfg = this.config;
        final net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer viewer = collection != null ? reader.viewer(java.util.List.of(collection))
                : cfg.allCollections ? net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer.ALL : reader.viewer(cfg.collections);
        return new net.yacy.scoutro.knowledge.read.ChatFacts(reader).select(docIds, terms, viewer, maxFacts, deadlineMillis);
    }

    /** How far the graph lags behind Solr ({@code sync.lag}), or null without the sync. */
    public JSONObject lag() {
        final SyncService s = this.sync;
        return s == null ? null : s.status().optJSONObject("lag");
    }

    private SyncService requireSync() throws KgException {
        final SyncService s = this.sync;
        if (s == null) {
            throw new KgException(KgException.SYNC_UNAVAILABLE, "the knowledge graph does not follow Solr in this environment");
        }
        return s;
    }

    /**
     * Re-evaluates the JSON-LD capture with its own budget: the estimate is
     * the sum of {@code kg_doc.jsonld_bytes} plus the bytes captured but not
     * yet synchronised. The parser only ever reads the resulting flag, so a
     * pause never blocks crawling or indexing.
     */
    private void updateJsonLdCapture() {
        final KgStore s = this.store;
        final boolean running = this.state == State.RUNNING && s != null && this.sync != null && !this.closing;
        Long estimate = null;
        if (running) {
            try {
                estimate = s.read(c -> KgStore.queryLong(c, "SELECT coalesce(sum(jsonld_bytes), 0) FROM kg_doc"))
                        + JsonLdCapture.pendingBytes();
            } catch (final KgException e) {
                estimate = this.jsonldEstimate;
            }
        }
        this.jsonldEstimate = estimate;
        final JsonLdCapturePolicy.State st = this.jsonld.evaluate(running, estimate,
                this.guard == null ? 0L : diskUsable(this.guard.status()));
        if (st == JsonLdCapturePolicy.State.ACTIVE) {
            JsonLdCapture.activate(this.config.jsonldMaxBlocksPerDoc, (int) Math.min(Integer.MAX_VALUE, this.config.jsonldMaxBytesPerDoc),
                    this.config.allCollections, this.config.collections);
        } else if (st == JsonLdCapturePolicy.State.PAUSED) {
            JsonLdCapture.pause(this.config.allCollections, this.config.collections);
        } else {
            JsonLdCapture.off();
        }
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
            if (this.sync == null) {
                // without the sync nothing is captured, so the field size in Solr is unknown
                this.jsonld.evaluate(false, null, diskUsable(storage));
            }
        } else {
            this.jsonld.evaluate(false, null, 0L);
        }
        final JSONObject jl = this.jsonld.status();
        KgJson.put(jl, "capture", JsonLdCapture.status());
        KgJson.put(o, "jsonld", jl);
        if (running) {
            KgJson.put(o, "store", KgJson.obj("schemaVersion", s.schemaVersion(), "epoch", s.epoch(),
                    "uncleanStartDetected", this.uncleanStartDetected, "startRecorded", this.startRecorded,
                    "integrity", integrityStatus(), "manualPause", this.guard.manualPause(),
                    "manualPauseSaved", this.unsavedManualPause == null));
            final SyncService sy = this.sync;
            KgJson.put(o, "sync", sy != null ? sy.status()
                    : KgJson.obj("state", "off", "reason", this.env.solr == null ? "not_configured" : "stopped"));
            final LlmService ll = this.llm;
            KgJson.put(o, "llm", ll != null ? ll.status()
                    : KgJson.obj("state", "off", "reason", !this.config.llmEnabled() ? "no_llm_collections"
                            : sy == null ? "no_sync" : "stopped", "enabled", this.config.llmEnabled()));
            final KgBackups b = this.backups;
            if (b != null) {
                KgJson.put(o, "backup", b.status());
            }
            KgJson.put(o, "events", recentEvents(s));
        }
        return o;
    }

    private static long parseLong(final String v) {
        try {
            return v == null ? 0L : Long.parseLong(v);
        } catch (final NumberFormatException e) {
            return 0L;
        }
    }

    // ---------------------------------------------------------------- backups

    /** Starts a backup on the backup thread; the status shows its progress and result. */
    public JSONObject backup() throws KgException {
        final KgBackups b;
        synchronized (this) {
            requireRunning();
            b = this.backups;
        }
        if (b == null || !b.start("manual")) {
            throw new KgException(KgException.OPERATION_RUNNING, "a backup, restore or rebuild is running");
        }
        return status();
    }

    /** The backup files with their metadata. */
    public synchronized JSONObject backups() throws KgException {
        requireRunning();
        return this.backups.list();
    }

    /** The backup file {@code name} for a download; null if it does not exist or is not a backup name. */
    public synchronized File backupFile(final String name) throws KgException {
        requireRunning();
        return KgBackup.find(this.paths.backup, name);
    }

    /**
     * Restores a backup (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 13): checks it
     * (name, SHA-256 of its metadata file, quick_check, schema version, epoch)
     * without touching the current graph; then stops the graph, moves the
     * current database aside as {@code backup/graph-<UTC>-before-restore.db},
     * copies the backup into place with a new dataset epoch (change-feed
     * consumers get {@code epoch_changed} and sync again) and starts the graph
     * again, which reconciles it with Solr. If the restored graph does not
     * start, the previous database is put back and the call fails with
     * {@link KgException#RESTORE_FAILED}.
     */
    public synchronized JSONObject restore(final String name) throws KgException {
        requireRunning();
        final File src = KgBackup.find(this.paths.backup, name);
        if (src == null) {
            throw new KgException(KgException.BACKUP_NOT_FOUND, "no backup " + name + " in " + KgPaths.RELATIVE_DIR + "/backup");
        }
        final JSONObject meta = KgBackup.readMeta(src);
        if (meta != null && meta.optString("sha256", null) != null) {
            final String sum;
            try {
                sum = KgBackup.sha256(src);
            } catch (final IOException e) {
                throw new KgException(KgException.BACKUP_INVALID, "unreadable", "the backup cannot be read", e);
            }
            if (!sum.equals(meta.optString("sha256"))) {
                throw new KgException(KgException.BACKUP_INVALID, "checksum", "the SHA-256 does not match the metadata file", null);
            }
        }
        final JSONObject facts = KgBackup.verify(src, KgSchema.CURRENT_VERSION);
        final JSONObject storage = this.guard.status();
        final long need = src.length();
        if (diskUsable(storage) - need < this.config.criticalFloorBytes() || storage.optLong("usedBytes") + need > this.config.budgetMaxBytes) {
            throw KgException.refused("restore_space", "not enough room in the budget or on the disk for a copy of the backup");
        }
        final KgBackups b = this.backups;
        if (b == null || !b.claim()) {
            throw new KgException(KgException.OPERATION_RUNNING, "a backup, restore or rebuild is running");
        }
        final long now = this.env.clock.getAsLong();
        final File safety = new File(this.paths.backup, KgBackup.name(now, KgBackup.BEFORE_RESTORE));
        final String epoch = KgIds.newEpoch();
        try {
            close();
            moveDatabase(this.paths.db, safety);
            try {
                copyInto(src, this.paths.db);
                KgBackup.prepareRestored(this.paths.db, epoch, "restored from " + src.getName(), now);
            } catch (final IOException | KgException e) {
                rollBack(safety);
                throw new KgException(KgException.RESTORE_FAILED, "copy", "the backup could not be put in place: "
                        + e.getClass().getSimpleName(), e);
            }
            open();
            if (this.state != State.RUNNING) {
                final String why = this.reason;
                close();
                rollBack(safety);
                open();
                throw new KgException(KgException.RESTORE_FAILED, why, "the restored graph did not start (" + why + "); the previous graph is back", null);
            }
        } finally {
            if (b != null) {
                b.release();
            }
        }
        LOG.info("knowledge graph restored from " + src.getName() + "; previous graph kept as " + safety.getName());
        final JSONObject o = status();
        KgJson.put(o, "restored", KgJson.obj("file", src.getName(), "previous", safety.getName(), "epoch", epoch, "counts", facts.opt("counts")));
        return o;
    }

    /** Moves the database (and a WAL or shared-memory file left behind) to {@code target}. */
    private static void moveDatabase(final File db, final File target) throws KgException {
        try {
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) {
                throw new IOException("cannot create " + target.getParentFile());
            }
            Files.move(db.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            for (final String ext : new String[] {"-wal", "-shm"}) {
                final File side = new File(db.getPath() + ext);
                if (side.exists()) {
                    Files.move(side.toPath(), new File(target.getPath() + ext).toPath(), StandardCopyOption.ATOMIC_MOVE);
                }
            }
        } catch (final IOException e) {
            throw new KgException(KgException.RESTORE_FAILED, "move", "the current graph could not be moved aside: " + e.getMessage(), e);
        }
    }

    /** Copies {@code src} to {@code db} through a partial file that is synced before the rename. */
    private static void copyInto(final File src, final File db) throws IOException {
        final File partial = new File(db.getPath() + ".partial");
        Files.copy(src.toPath(), partial.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(partial.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Files.move(partial.toPath(), db.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    /** Puts the database moved aside back in place. */
    private void rollBack(final File safety) {
        try {
            for (final String ext : new String[] {"", "-wal", "-shm"}) {
                final File f = new File(this.paths.db.getPath() + ext);
                if (f.exists()) {
                    Files.delete(f.toPath());
                }
            }
            Files.move(safety.toPath(), this.paths.db.toPath(), StandardCopyOption.ATOMIC_MOVE);
            for (final String ext : new String[] {"-wal", "-shm"}) {
                final File side = new File(safety.getPath() + ext);
                if (side.exists()) {
                    Files.move(side.toPath(), new File(this.paths.db.getPath() + ext).toPath(), StandardCopyOption.ATOMIC_MOVE);
                }
            }
        } catch (final IOException e) {
            LOG.warn("knowledge graph: the previous graph could not be put back from " + safety + ": " + e.getMessage());
        }
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

    /** For tests: the process "dies" without stop(): forget the instance and its hook. */
    static synchronized void forgetForTests() {
        final Thread hook = shutdownHook;
        shutdownHook = null;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (final IllegalStateException | SecurityException e) {
                // shutting down
            }
        }
        current = null;
    }

    /** For tests: whether the shutdown hook is registered. */
    static synchronized boolean shutdownHookRegistered() {
        return shutdownHook != null;
    }

    /** For tests: the sync while running. */
    SyncService sync() {
        return this.sync;
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
