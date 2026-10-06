/*
 *  KgStore
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

package net.yacy.scoutro.knowledge.store;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgIds;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.budget.CheckpointResult;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;

/**
 * The embedded SQLite store of the knowledge graph.
 * <p>
 * One writer connection, serialised by a lock. Every write is admitted by the
 * {@link StorageGuard} while the lock is held, right before
 * {@code BEGIN IMMEDIATE}, so each admission sees the files after all earlier
 * commits. Readers come from a small pool and only inside
 * {@link #read(SqlWork)}: each read is a lease with a deadline, because an
 * open read transaction keeps the WAL from being checkpointed and lets it grow
 * without bound. The runtime's watchdog interrupts leases past their deadline
 * ({@code sqlite3_interrupt}); the leased connection additionally refuses to
 * start statements once the lease is over, since an interrupt between two
 * statements does not stop the next one. Interrupt, lease end, reuse and
 * closing of a reader connection are serialised per connection, so a late
 * interrupt can never reach the next lease. Readers never hold a transaction
 * across client I/O: callers materialise a bounded page and return.
 * <p>
 * Checkpoints are explicit ({@code wal_checkpoint(TRUNCATE)}) and verified;
 * the result goes to the guard, which refuses writes once the WAL reaches its
 * limit and a checkpoint cannot shrink it.
 */
public final class KgStore implements AutoCloseable {

    /** Opens a SQLite connection; replaceable in tests. */
    public interface ConnectionFactory {
        Connection open(File db) throws SQLException;
    }

    /** Work inside one transaction. */
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException, KgException;
    }

    /** Opens the bundled SQLite driver directly; loads the native library on first use. */
    public static final ConnectionFactory SQLITE = db -> new org.sqlite.JDBC().connect("jdbc:sqlite:" + db.getAbsolutePath(), new Properties());

    private static final int MAX_READERS = 2;
    private static final long READER_WAIT_MILLIS = 2000L;
    private static final int BUSY_TIMEOUT_MILLIS = 5000;
    private static final int CHECKPOINT_BUSY_TIMEOUT_MILLIS = 200;
    private static final long SCHEMA_ESTIMATE_BYTES = 512L * 1024L;
    private static final int EVENT_RING = 1000;
    /** Pages freed per incremental-vacuum transaction. */
    static final int VACUUM_BATCH_PAGES = 128;
    /**
     * WAL frames reserved per freed page and per batch. Measured with
     * sqlite-jdbc 3.53: at most 0.9 frames per page in one transaction (one
     * page per transaction needs up to 17 frames), so 2 per page plus 16 is a
     * safe bound; a batch writes each touched page once.
     */
    static final long VACUUM_FRAMES_PER_PAGE = 2L;
    static final long VACUUM_FRAMES_PER_BATCH = 16L;
    private static final long WAL_FRAME_HEADER = 24L;

    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_LOCKED = 6;
    private static final int SQLITE_INTERRUPT = 9;
    private static final int SQLITE_IOERR = 10;
    private static final int SQLITE_CORRUPT = 11;
    private static final int SQLITE_FULL = 13;
    private static final int SQLITE_CANTOPEN = 14;
    private static final int SQLITE_CONSTRAINT = 19;
    private static final int SQLITE_NOTADB = 26;

    private final KgPaths paths;
    private final KgConfig cfg;
    private final StorageGuard guard;
    private final ConnectionFactory factory;
    private final LongSupplier clock;
    private final Connection writer;
    private final ReentrantLock writeLock = new ReentrantLock();
    /** The connection of a running backup, for {@link #interruptBackup()}. */
    private volatile Connection backupConnection;
    private final java.util.concurrent.atomic.AtomicBoolean backupTimedOut = new java.util.concurrent.atomic.AtomicBoolean();
    private final List<ReaderSlot> readers = new CopyOnWriteArrayList<>();
    private final BlockingQueue<ReaderSlot> idleReaders = new LinkedBlockingQueue<>();
    private final AtomicInteger readerCount = new AtomicInteger();
    private final AtomicLong interruptedReads = new AtomicLong();
    /** Test hook: runs inside the per-connection critical section, right before sqlite3_interrupt. */
    private volatile Runnable interruptProbe;
    private volatile boolean closed;
    private long pageSize;
    private long maxPageCount;
    private int schemaVersion;
    private String epoch;
    private boolean created;

    /** One reader connection. {@code lease} and {@code closed} change only under the slot's monitor. */
    private static final class ReaderSlot {
        final Connection conn;
        volatile Lease lease;
        boolean closed;

        ReaderSlot(final Connection conn) {
            this.conn = conn;
        }
    }

    private static final class Lease {
        final long startedAt;
        final long deadlineMillis;
        final AtomicBoolean interrupted = new AtomicBoolean();

        Lease(final long startedAt, final long deadlineMillis) {
            this.startedAt = startedAt;
            this.deadlineMillis = deadlineMillis;
        }

        boolean expired(final long now) {
            return now - this.startedAt > this.deadlineMillis;
        }
    }

    /** Outcome of {@link #incrementalVacuum}. */
    public static final class VacuumResult {
        public static final String DONE = "done";
        public static final String MAX_PAGES = "max_pages";
        public static final String DEADLINE = "deadline";
        public static final String NO_PROGRESS = "no_progress";

        public final long freedPages;
        public final int batches;
        /** {@link #DONE}, {@link #MAX_PAGES}, {@link #DEADLINE}, {@link #NO_PROGRESS} or a guard reason. */
        public final String stop;
        public final long remainingFreePages;

        VacuumResult(final long freedPages, final int batches, final String stop, final long remainingFreePages) {
            this.freedPages = freedPages;
            this.batches = batches;
            this.stop = stop;
            this.remainingFreePages = remainingFreePages;
        }

        public JSONObject toJson() {
            return KgJson.obj("freedPages", this.freedPages, "batches", this.batches, "stop", this.stop,
                    "remainingFreePages", this.remainingFreePages);
        }
    }

    private KgStore(final KgPaths paths, final KgConfig cfg, final StorageGuard guard, final ConnectionFactory factory,
            final LongSupplier clock, final Connection writer) {
        this.paths = paths;
        this.cfg = cfg;
        this.guard = guard;
        this.factory = factory;
        this.clock = clock;
        this.writer = writer;
    }

    /**
     * Opens (and on first use creates) the store. Creates the graph directory;
     * never called while the graph is disabled.
     *
     * @throws KgException {@link KgException#NATIVE_LIBRARY_UNAVAILABLE} if
     *         SQLite cannot be loaded, {@link KgException#SCHEMA_UNSUPPORTED}
     *         for a newer or foreign schema, {@link KgException#STORAGE_ERROR}
     *         for I/O problems
     */
    public static KgStore open(final KgPaths paths, final KgConfig cfg, final StorageGuard guard,
            final ConnectionFactory factory, final LongSupplier clock) throws KgException {
        mkdirs(paths.dir);
        mkdirs(paths.tmp);
        final boolean fresh = !paths.db.isFile() || paths.db.length() == 0L;
        if (fresh) {
            guard.admit(WriteClass.SYSTEM, SCHEMA_ESTIMATE_BYTES);
        }
        final Connection writer;
        try {
            writer = SqliteProcess.open(factory, paths.db, paths.tmp);
        } catch (final LinkageError e) {
            throw new KgException(KgException.NATIVE_LIBRARY_UNAVAILABLE, null,
                    "SQLite could not be loaded: " + e.getClass().getSimpleName(), e);
        } catch (final SQLException e) {
            if (isNativeFailure(e)) {
                throw new KgException(KgException.NATIVE_LIBRARY_UNAVAILABLE, null,
                        "SQLite could not be loaded: " + rootMessage(e), e);
            }
            throw new KgException(KgException.STORAGE_ERROR, null, "cannot open " + paths.db + ": " + e.getMessage(), e);
        }
        final KgStore store = new KgStore(paths, cfg, guard, factory, clock, writer);
        try {
            store.configureWriter(fresh);
            store.initSchema();
        } catch (final KgException e) {
            SqliteProcess.close(writer);
            throw e;
        } catch (final SQLException e) {
            SqliteProcess.close(writer);
            throw new KgException(KgException.STORAGE_ERROR, null, "cannot initialise " + paths.db + ": " + e.getMessage(), e);
        } catch (final RuntimeException | LinkageError e) {
            SqliteProcess.close(writer);
            throw new KgException(KgException.NATIVE_LIBRARY_UNAVAILABLE, null,
                    "SQLite failed during initialisation: " + e.getClass().getSimpleName(), e);
        }
        guard.setTempDirectory(SqliteProcess.tempDirectoryIs(paths.tmp) ? StorageGuard.TMP_DIR_GRAPH
                : SqliteProcess.tempDirectorySet() ? StorageGuard.TMP_DIR_OTHER : StorageGuard.TMP_DIR_UNSET);
        return store;
    }

    private static void mkdirs(final File dir) throws KgException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new KgException(KgException.STORAGE_ERROR, "cannot create " + dir);
        }
    }

    private void configureWriter(final boolean fresh) throws SQLException, KgException {
        if (fresh) {
            // must precede the first table; cannot be changed later without VACUUM
            exec(this.writer, "PRAGMA auto_vacuum=INCREMENTAL");
        }
        final String mode = queryString(this.writer, "PRAGMA journal_mode=WAL");
        if (!"wal".equalsIgnoreCase(mode)) {
            throw new KgException(KgException.STORAGE_ERROR, "SQLite refused WAL mode (" + mode + ")");
        }
        exec(this.writer, "PRAGMA synchronous=FULL");
        exec(this.writer, "PRAGMA foreign_keys=ON");
        if (queryLong(this.writer, "PRAGMA foreign_keys") != 1L) {
            throw new KgException(KgException.STORAGE_ERROR, "SQLite does not enforce foreign keys");
        }
        exec(this.writer, "PRAGMA journal_size_limit=" + this.cfg.walCheckpointBytes);
        configureCommon(this.writer, 8192);
        this.pageSize = queryLong(this.writer, "PRAGMA page_size");
        applyMaxPageCount();
    }

    /**
     * Per-connection settings. The temp directory is process-wide and set by
     * {@link SqliteProcess} on the first connection only.
     */
    private static void configureCommon(final Connection c, final int cacheKiB) throws SQLException {
        exec(c, "PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLIS);
        exec(c, "PRAGMA temp_store=FILE");
        exec(c, "PRAGMA cache_size=-" + cacheKiB);
        exec(c, "PRAGMA mmap_size=0");
    }

    /** Caps the main file at the data share of the budget; SQLite keeps the current size if it is larger. */
    private void applyMaxPageCount() throws SQLException {
        final long wanted = this.guard.maxPageCount(this.pageSize);
        this.maxPageCount = queryLong(this.writer, "PRAGMA max_page_count=" + wanted);
    }

    private void initSchema() throws SQLException, KgException {
        this.writeLock.lock();
        try {
            exec(this.writer, "BEGIN IMMEDIATE");
            try {
                final boolean hasMeta = queryLong(this.writer,
                        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='kg_meta'") > 0L;
                if (!hasMeta) {
                    if (queryLong(this.writer, "SELECT count(*) FROM sqlite_master WHERE type='table'") > 0L) {
                        throw new KgException(KgException.SCHEMA_UNSUPPORTED,
                                paths.db + " contains tables of another application; not opened");
                    }
                    try (Statement st = this.writer.createStatement()) {
                        for (final String ddl : KgSchema.DDL_V1) {
                            st.execute(ddl);
                        }
                    }
                    final long now = this.clock.getAsLong();
                    putMeta(this.writer, KgSchema.META_SCHEMA_VERSION, "1"); // migrated below like any v1 database
                    putMeta(this.writer, KgSchema.META_EPOCH, KgIds.newEpoch());
                    putMeta(this.writer, KgSchema.META_CREATED_AT, Long.toString(now));
                    putMeta(this.writer, KgSchema.META_CLEAN_SHUTDOWN, "1");
                    putMeta(this.writer, KgSchema.META_CHANGES_MIN_SEQ, "1");
                    event(this.writer, 1, "store_created", "schema " + KgSchema.CURRENT_VERSION, now);
                    this.created = true;
                }
                final String v = getMeta(this.writer, KgSchema.META_SCHEMA_VERSION);
                final int version;
                try {
                    version = Integer.parseInt(v == null ? "" : v.trim());
                } catch (final NumberFormatException e) {
                    throw new KgException(KgException.SCHEMA_UNSUPPORTED, "unreadable schema version '" + v + "'");
                }
                if (version > KgSchema.CURRENT_VERSION || version < 1) {
                    throw new KgException(KgException.SCHEMA_UNSUPPORTED, "schema version " + version
                            + " is not supported by this Scoutro version (supports " + KgSchema.CURRENT_VERSION + ")");
                }
                int migrated = version;
                while (migrated < KgSchema.CURRENT_VERSION) {
                    try (Statement st = this.writer.createStatement()) {
                        for (final String ddl : KgSchema.MIGRATIONS[migrated - 1]) {
                            st.execute(ddl);
                        }
                    }
                    migrated++;
                    putMeta(this.writer, KgSchema.META_SCHEMA_VERSION, Integer.toString(migrated));
                    if (!this.created) {
                        event(this.writer, 1, "schema_migrated", "schema " + (migrated - 1) + " -> " + migrated,
                                this.clock.getAsLong());
                    }
                }
                this.schemaVersion = migrated;
                this.epoch = getMeta(this.writer, KgSchema.META_EPOCH);
                if (!KgIds.isEpoch(this.epoch)) {
                    throw new KgException(KgException.SCHEMA_UNSUPPORTED, "invalid dataset epoch");
                }
                exec(this.writer, "COMMIT");
            } catch (final SQLException | KgException | RuntimeException e) {
                rollbackQuietly(this.writer);
                throw e;
            }
        } finally {
            this.writeLock.unlock();
        }
    }

    // ------------------------------------------------------------------ writes

    /**
     * Runs {@code work} in one write transaction after the guard admitted
     * {@code estimateBytes} of growth for the given class. The admission runs
     * under the write lock, right before {@code BEGIN IMMEDIATE}, so it is
     * based on the files after every earlier commit. A refused or failed write
     * leaves the database unchanged.
     */
    public <T> T write(final WriteClass writeClass, final long estimateBytes, final SqlWork<T> work) throws KgException {
        ensureOpen();
        final T result;
        this.writeLock.lock();
        try {
            ensureOpen();
            admitLocked(writeClass, estimateBytes);
            exec(this.writer, "BEGIN IMMEDIATE");
            try {
                result = work.run(this.writer);
                exec(this.writer, "COMMIT");
            } catch (final SQLException e) {
                rollbackQuietly(this.writer);
                throw mapWrite(e);
            } catch (final KgException | RuntimeException | Error e) {
                rollbackQuietly(this.writer);
                throw e;
            }
        } catch (final SQLException e) {
            throw mapWrite(e);
        } finally {
            this.writeLock.unlock();
        }
        maybeCheckpoint();
        return result;
    }

    /** Admission with one checkpoint attempt when only the WAL is in the way; caller holds the write lock. */
    private void admitLocked(final WriteClass writeClass, final long estimateBytes) throws KgException {
        try {
            this.guard.admit(writeClass, estimateBytes);
        } catch (final KgException e) {
            if (!StorageGuard.WAL_LIMIT.equals(e.reason()) && !StorageGuard.WAL_CHECKPOINT_BLOCKED.equals(e.reason())) {
                throw e;
            }
            checkpoint();
            this.guard.admit(writeClass, estimateBytes);
        }
    }

    private void maybeCheckpoint() {
        if (this.guard.walBytes() >= this.cfg.walCheckpointBytes) {
            try {
                checkpoint();
            } catch (final KgException e) {
                // recorded by the guard; the next write tries again
            }
        }
    }

    /**
     * {@code PRAGMA wal_checkpoint(TRUNCATE)} with a short busy timeout. The
     * result is verified and handed to the guard; if a reader blocks it,
     * readers past their deadline are interrupted.
     */
    public CheckpointResult checkpoint() throws KgException {
        ensureOpen();
        final CheckpointResult result;
        this.writeLock.lock();
        try {
            exec(this.writer, "PRAGMA busy_timeout=" + CHECKPOINT_BUSY_TIMEOUT_MILLIS);
            try (Statement st = this.writer.createStatement(); ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                rs.next();
                result = new CheckpointResult(rs.getInt(1) != 0, rs.getInt(2), rs.getInt(3), this.clock.getAsLong(),
                        this.paths.wal.isFile() ? this.paths.wal.length() : 0L);
            } finally {
                exec(this.writer, "PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLIS);
            }
        } catch (final SQLException e) {
            throw mapWrite(e);
        } finally {
            this.writeLock.unlock();
        }
        this.guard.recordCheckpoint(result);
        if (!result.complete()) {
            interruptExpiredReaders(this.clock.getAsLong());
        }
        return result;
    }

    /**
     * Returns free pages to the filesystem, never with a full VACUUM. Works in
     * transactions of {@value #VACUUM_BATCH_PAGES} pages; each batch is
     * admitted on its own (maintenance class) with a WAL estimate, and between
     * batches the WAL is checkpointed. In WAL mode the main file only shrinks
     * when a checkpoint completes, so the vacuum stops as soon as a checkpoint
     * is blocked (a reader holds an old snapshot): further batches would only
     * fill the WAL that deletions may need. It also stops when the guard
     * refuses the next batch, at {@code maxPages} and at the deadline.
     */
    public VacuumResult incrementalVacuum(final long maxPages, final long maxMillis) throws KgException {
        ensureOpen();
        final long deadline = this.clock.getAsLong() + maxMillis;
        long freed = 0L;
        long free = -1L;
        int batches = 0;
        String stop = null;
        while (stop == null) {
            if (freed >= maxPages) {
                stop = VacuumResult.MAX_PAGES;
                break;
            }
            if (this.clock.getAsLong() >= deadline) {
                stop = VacuumResult.DEADLINE;
                break;
            }
            this.writeLock.lock();
            try {
                ensureOpen();
                free = queryLong(this.writer, "PRAGMA freelist_count");
                if (free == 0L) {
                    stop = VacuumResult.DONE;
                    break;
                }
                final int pages = (int) Math.min(VACUUM_BATCH_PAGES, Math.min(maxPages - freed, free));
                try {
                    admitLocked(WriteClass.MAINTENANCE, vacuumEstimateBytes(pages));
                } catch (final KgException e) {
                    if (!KgException.WRITE_REFUSED.equals(e.code())) {
                        throw e;
                    }
                    stop = e.reason();
                    break;
                }
                exec(this.writer, "BEGIN IMMEDIATE");
                try {
                    // sqlite-jdbc steps the pragma once per execution, which frees one page; the statement
                    // must be closed before COMMIT, which SQLite refuses while it is still active
                    try (Statement st = this.writer.createStatement()) {
                        for (int i = 0; i < pages; i++) {
                            st.execute("PRAGMA incremental_vacuum");
                        }
                    }
                    exec(this.writer, "COMMIT");
                } catch (final SQLException | RuntimeException e) {
                    rollbackQuietly(this.writer);
                    throw e;
                }
                final long after = queryLong(this.writer, "PRAGMA freelist_count");
                batches++;
                if (after >= free) {
                    stop = VacuumResult.NO_PROGRESS;
                }
                freed += Math.max(0L, free - after);
                free = after;
            } catch (final SQLException e) {
                throw mapWrite(e);
            } finally {
                this.writeLock.unlock();
            }
            if (stop == null && this.guard.walBytes() >= this.cfg.walCheckpointBytes && !checkpoint().complete()) {
                stop = StorageGuard.WAL_CHECKPOINT_BLOCKED;
            }
        }
        return new VacuumResult(freed, batches, stop, free);
    }

    /** WAL bytes a batch of {@code pages} may write (frame header + page per frame). */
    long vacuumEstimateBytes(final int pages) {
        return (VACUUM_FRAMES_PER_PAGE * pages + VACUUM_FRAMES_PER_BATCH) * (this.pageSize + WAL_FRAME_HEADER);
    }

    private KgException mapWrite(final SQLException e) {
        final int code = baseCode(e);
        switch (code) {
            case SQLITE_FULL:
                this.guard.storageFull();
                return new KgException(KgException.STORAGE_FULL, null,
                        "SQLite refused growth (database page limit or full disk); the transaction was rolled back", e);
            case SQLITE_IOERR:
            case SQLITE_CORRUPT:
            case SQLITE_NOTADB:
            case SQLITE_CANTOPEN:
                this.guard.storageError("sqlite_" + code);
                return new KgException(KgException.STORAGE_ERROR, "sqlite_" + code, "SQLite storage error: " + e.getMessage(), e);
            case SQLITE_CONSTRAINT:
                return new KgException(KgException.CONSTRAINT, null, "constraint violated: " + e.getMessage(), e);
            case SQLITE_BUSY:
            case SQLITE_LOCKED:
                return new KgException(KgException.BUSY, null, "database busy: " + e.getMessage(), e);
            default:
                return new KgException(KgException.SQL_ERROR, null, "SQLite error: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------- reads

    /** Runs {@code work} in one read transaction with the configured deadline. */
    public <T> T read(final SqlWork<T> work) throws KgException {
        return read(work, this.cfg.readMaxTransactionMillis);
    }

    /**
     * Runs {@code work} in one read transaction; it is interrupted after
     * {@code deadlineMillis}. {@code work} gets a leased connection: it must
     * not control the transaction, and every statement it starts after the
     * deadline fails with {@link KgException#READ_TIMEOUT}.
     */
    public <T> T read(final SqlWork<T> work, final long deadlineMillis) throws KgException {
        ensureOpen();
        final ReaderSlot slot = acquireReader();
        final Lease lease = new Lease(this.clock.getAsLong(), deadlineMillis);
        synchronized (slot) {
            slot.lease = lease;
        }
        final LeasedConnection leased = new LeasedConnection(slot.conn, () -> checkLease(lease));
        boolean ok = false;
        try {
            exec(slot.conn, "BEGIN");
            final T result = work.run(leased.proxy);
            ok = true;
            return result;
        } catch (final SQLException e) {
            throw mapRead(e, lease);
        } finally {
            // 1. no statement of the lease stays active, so nothing is left for an interrupt to hit
            leased.closeStatements();
            // 2. from here on no interrupt can be issued for this connection (see interruptReaders)
            synchronized (slot) {
                slot.lease = null;
            }
            // 3. starts with no active statement: SQLite clears a pending interrupt, the end cannot fail by it;
            //    a connection whose transaction could not be ended is closed, never reused
            releaseReader(slot, !endTransaction(slot.conn, ok));
        }
    }

    /** Called by the leased connection before every statement and row step. */
    private void checkLease(final Lease lease) throws SQLException {
        if (lease.interrupted.get() || lease.expired(this.clock.getAsLong())) {
            if (lease.interrupted.compareAndSet(false, true)) {
                this.interruptedReads.incrementAndGet();
            }
            throw new SQLException("read lease ended after " + lease.deadlineMillis + " ms", null, SQLITE_INTERRUPT);
        }
    }

    /** COMMIT (or ROLLBACK); false if the transaction could not be ended and the connection must be closed. */
    private static boolean endTransaction(final Connection c, final boolean commit) {
        try {
            exec(c, commit ? "COMMIT" : "ROLLBACK");
            return true;
        } catch (final SQLException e) {
            if (!commit && String.valueOf(e.getMessage()).contains("no transaction is active")) {
                return true;
            }
            return false;
        }
    }

    private KgException mapRead(final SQLException e, final Lease lease) {
        final int code = baseCode(e);
        if (lease.interrupted.get() || code == SQLITE_INTERRUPT) {
            return new KgException(KgException.READ_TIMEOUT, null,
                    "read transaction exceeded " + lease.deadlineMillis + " ms and was interrupted", e);
        }
        if (code == SQLITE_IOERR || code == SQLITE_CORRUPT || code == SQLITE_NOTADB) {
            this.guard.storageError("sqlite_" + code);
            return new KgException(KgException.STORAGE_ERROR, "sqlite_" + code, "SQLite storage error: " + e.getMessage(), e);
        }
        if (code == SQLITE_BUSY || code == SQLITE_LOCKED) {
            return new KgException(KgException.BUSY, null, "database busy: " + e.getMessage(), e);
        }
        return new KgException(KgException.SQL_ERROR, null, "SQLite error: " + e.getMessage(), e);
    }

    private ReaderSlot acquireReader() throws KgException {
        ReaderSlot slot = this.idleReaders.poll();
        if (slot != null) {
            return slot;
        }
        if (this.readerCount.incrementAndGet() <= MAX_READERS) {
            Connection c = null;
            try {
                c = SqliteProcess.open(this.factory, this.paths.db, this.paths.tmp);
                configureCommon(c, 4096);
                exec(c, "PRAGMA query_only=1");
                slot = new ReaderSlot(c);
                this.readers.add(slot);
                if (this.closed) {
                    closeReader(slot);
                    throw new KgException(KgException.STORE_CLOSED, "the knowledge graph store is closed");
                }
                return slot;
            } catch (final SQLException e) {
                this.readerCount.decrementAndGet();
                SqliteProcess.close(c);
                throw new KgException(isNativeFailure(e) ? KgException.NATIVE_LIBRARY_UNAVAILABLE : KgException.STORAGE_ERROR,
                        null, "cannot open a reader: " + e.getMessage(), e);
            } catch (final LinkageError e) {
                this.readerCount.decrementAndGet();
                SqliteProcess.close(c);
                throw new KgException(KgException.NATIVE_LIBRARY_UNAVAILABLE, null, "SQLite could not be loaded", e);
            }
        }
        this.readerCount.decrementAndGet();
        try {
            slot = this.idleReaders.poll(READER_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (slot == null) {
            throw new KgException(KgException.BUSY, "all knowledge graph readers are busy");
        }
        return slot;
    }

    private void releaseReader(final ReaderSlot slot, final boolean broken) {
        if (this.closed || broken) {
            closeReader(slot);
            return;
        }
        this.idleReaders.offer(slot);
        if (this.closed && this.idleReaders.remove(slot)) {
            closeReader(slot); // close() ran while this reader was being returned
        }
    }

    /** Closes a reader that has no lease; serialised with interrupts by the slot's monitor. */
    private void closeReader(final ReaderSlot slot) {
        synchronized (slot) {
            if (slot.closed) {
                return;
            }
            slot.closed = true;
            SqliteProcess.close(slot.conn);
        }
        this.readers.remove(slot);
        this.readerCount.decrementAndGet();
    }

    /**
     * Interrupts every read that has run longer than its deadline; called by
     * the runtime's watchdog every 250 ms and after a blocked checkpoint. The
     * interrupt is repeated on every call while the lease is still current:
     * an interrupt that arrives between two statements is otherwise lost.
     *
     * @return number of reads newly marked as interrupted
     */
    /** Why new growth is refused now ({@link StorageGuard#growthRefusal}), null if admitted. */
    public String growthRefusal() {
        return this.guard.growthRefusal();
    }

    /** True while the administrator paused the graph ({@link StorageGuard#manualPause}). */
    public boolean manualPause() {
        return this.guard.manualPause();
    }

    public int interruptExpiredReaders(final long now) {
        return interruptReaders(now, false);
    }

    /** Interrupts every running read, e.g. when temp files exceed their limit or the store closes. */
    public int interruptAllReaders() {
        return interruptReaders(this.clock.getAsLong(), true);
    }

    private int interruptReaders(final long now, final boolean all) {
        int n = 0;
        for (final ReaderSlot slot : this.readers) {
            // the lease is checked and the connection interrupted under the slot's monitor; the lease
            // ends, and the connection is reused or closed, only under the same monitor
            synchronized (slot) {
                final Lease l = slot.lease;
                if (slot.closed || l == null || !(all || l.expired(now))) {
                    continue;
                }
                if (l.interrupted.compareAndSet(false, true)) {
                    this.interruptedReads.incrementAndGet();
                    n++;
                }
                final Runnable probe = this.interruptProbe;
                if (probe != null) {
                    probe.run();
                }
                interruptNative(slot.conn);
            }
        }
        return n;
    }

    private static void interruptNative(final Connection c) {
        if (c instanceof org.sqlite.SQLiteConnection) {
            try {
                ((org.sqlite.SQLiteConnection) c).getDatabase().interrupt();
            } catch (final SQLException | RuntimeException e) {
                // the read also ends at its next statement through the lease check
            }
        }
    }

    /** Test hook, see {@link #interruptProbe}. */
    void setInterruptProbe(final Runnable probe) {
        this.interruptProbe = probe;
    }

    /** For tests: threads waiting for the write lock. */
    int queuedWriters() {
        return this.writeLock.getQueueLength();
    }

    // --------------------------------------------------------------- inspection

    /** Page statistics of the main file, read through a lease. */
    public JSONObject pageStats() throws KgException {
        return read(c -> {
            final long count = queryLong(c, "PRAGMA page_count");
            final long free = queryLong(c, "PRAGMA freelist_count");
            return KgJson.obj("pageSize", this.pageSize, "pageCount", count, "freelistCount", free,
                    "maxPageCount", this.maxPageCount, "logicalBytes", (count - free) * this.pageSize,
                    "freeInFileBytes", free * this.pageSize);
        });
    }

    /**
     * A consistent, compact copy of the database into {@code target}
     * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 7.7): {@code VACUUM INTO} on a
     * dedicated read-only connection outside the reader pool, so writers keep
     * going. Admitted as growth with the logical size as estimate under the
     * write lock, so it starts only if the budget, the pause state and the
     * disk reserve allow it; interrupted at {@code deadlineMillis}. A failed
     * or interrupted copy is deleted.
     *
     * @return the size of the copy in bytes
     * @throws KgException {@link KgException#WRITE_REFUSED} with the guard's reason,
     *         {@link KgException#READ_TIMEOUT} at the deadline, {@link KgException#BACKUP_FAILED}
     */
    public long backupTo(final File target, final long deadlineMillis) throws KgException {
        ensureOpen();
        if (target.exists()) {
            throw new KgException(KgException.BACKUP_FAILED, "target_exists", "the backup file exists already", null);
        }
        this.writeLock.lock();
        try {
            final long logical = (queryLong(this.writer, "PRAGMA page_count") - queryLong(this.writer, "PRAGMA freelist_count"))
                    * this.pageSize;
            admitLocked(WriteClass.GROWTH, logical);
        } catch (final SQLException e) {
            throw mapWrite(e);
        } finally {
            this.writeLock.unlock();
        }
        Connection ro = null;
        final java.util.concurrent.atomic.AtomicBoolean timedOut = this.backupTimedOut;
        timedOut.set(false);
        final java.util.Timer timer = new java.util.Timer("ScoutroKG.backup-deadline", true);
        try {
            ro = SqliteProcess.open(KgBackup::readOnly, this.paths.db, this.paths.tmp);
            final Connection c = ro;
            this.backupConnection = ro;
            if (this.closed) {
                interruptNative(ro);
            }
            timer.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    timedOut.set(true);
                    interruptNative(c);
                }
            }, Math.max(1L, deadlineMillis));
            exec(ro, "VACUUM INTO '" + target.getAbsolutePath().replace("'", "''") + "'");
            return target.length();
        } catch (final SQLException e) {
            deleteQuietly(target);
            if (timedOut.get()) {
                throw new KgException(KgException.READ_TIMEOUT, null, "the backup did not finish within " + deadlineMillis + " ms", e);
            }
            throw new KgException(KgException.BACKUP_FAILED, "vacuum_into", "VACUUM INTO failed: " + e.getMessage(), e);
        } catch (final RuntimeException e) {
            deleteQuietly(target);
            throw new KgException(KgException.BACKUP_FAILED, "vacuum_into", "VACUUM INTO failed: " + e.getClass().getSimpleName(), e);
        } finally {
            timer.cancel();
            this.backupConnection = null;
            SqliteProcess.close(ro);
        }
    }

    /** Interrupts a running backup (stop of the graph, or a cancel); it ends with {@link KgException#READ_TIMEOUT}. */
    public void interruptBackup() {
        final Connection c = this.backupConnection;
        if (c != null) {
            this.backupTimedOut.set(true);
            interruptNative(c);
        }
    }

    private static void deleteQuietly(final File f) {
        if (f.exists() && !f.delete()) {
            f.deleteOnExit();
        }
    }

    /** {@code PRAGMA quick_check}; "ok" if the database is consistent. */
    public String quickCheck(final long deadlineMillis) throws KgException {
        return read(c -> queryString(c, "PRAGMA quick_check(1)"), deadlineMillis);
    }

    public JSONObject readerStatus(final long now) {
        long oldest = -1L;
        int open = 0;
        for (final ReaderSlot slot : this.readers) {
            final Lease l = slot.lease;
            if (l != null) {
                open++;
                oldest = Math.max(oldest, now - l.startedAt);
            }
        }
        return KgJson.obj("open", open, "oldestAgeMillis", open == 0 ? null : oldest,
                "connections", this.readers.size(), "maxConnections", MAX_READERS,
                "maxTransactionMillis", this.cfg.readMaxTransactionMillis, "interrupted", this.interruptedReads.get());
    }

    /** After a full reset wrote a new {@code dataset_epoch} (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 5.7). */
    public void epochReset(final String newEpoch) {
        if (!KgIds.isEpoch(newEpoch)) {
            throw new IllegalArgumentException("invalid epoch");
        }
        this.epoch = newEpoch;
    }

    public String epoch() {
        return this.epoch;
    }

    public int schemaVersion() {
        return this.schemaVersion;
    }

    /** True if this call of {@link #open} created the database. */
    public boolean created() {
        return this.created;
    }

    public long maxPageCount() {
        return this.maxPageCount;
    }

    public KgPaths paths() {
        return this.paths;
    }

    private void ensureOpen() throws KgException {
        if (this.closed) {
            throw new KgException(KgException.STORE_CLOSED, "the knowledge graph store is closed");
        }
    }

    /**
     * Final checkpoint and close; idempotent. Running reads are interrupted
     * and close their connection when they end.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.writeLock.lock();
        try {
            if (this.closed) {
                return;
            }
            this.closed = true;
            interruptAllReaders();
            interruptBackup();
            try {
                exec(this.writer, "PRAGMA busy_timeout=" + CHECKPOINT_BUSY_TIMEOUT_MILLIS);
                try (Statement st = this.writer.createStatement(); ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                    rs.next();
                }
            } catch (final SQLException e) {
                // the WAL is replayed on the next open
            }
            SqliteProcess.close(this.writer);
            ReaderSlot slot;
            while ((slot = this.idleReaders.poll()) != null) {
                closeReader(slot);
            }
        } finally {
            this.writeLock.unlock();
        }
    }

    // ------------------------------------------------------------------ helpers

    public static String getMeta(final Connection c, final String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM kg_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public static void putMeta(final Connection c, final String key, final String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kg_meta (key, value) VALUES (?, ?)"
                + " ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /** Appends to the bounded event ring (level 1 info, 2 warn, 3 error). */
    public static void event(final Connection c, final int level, final String code, final String detail, final long now)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kg_event (at, level, code, detail) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, now);
            ps.setInt(2, level);
            ps.setString(3, code);
            ps.setString(4, detail == null ? null : (detail.length() > 500 ? detail.substring(0, 500) : detail));
            ps.executeUpdate();
        }
        exec(c, "DELETE FROM kg_event WHERE seq <= (SELECT max(seq) FROM kg_event) - " + EVENT_RING);
    }

    static void exec(final Connection c, final String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    public static long queryLong(final Connection c, final String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public static String queryString(final Connection c, final String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void rollbackQuietly(final Connection c) {
        try {
            exec(c, "ROLLBACK");
        } catch (final SQLException e) {
            // SQLite may already have rolled back (e.g. after SQLITE_FULL): "no transaction is active"
        }
    }

    /**
     * sqlite-jdbc reports a missing or unloadable native library as
     * {@code SQLException("Error opening connection")} caused by
     * {@code org.sqlite.NativeLibraryNotFoundException}, or as a linkage error.
     */
    static boolean isNativeFailure(final Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof LinkageError || c.getClass().getName().endsWith("NativeLibraryNotFoundException")) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private static String rootMessage(final Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
    }

    private static int baseCode(final SQLException e) {
        if (e instanceof org.sqlite.SQLiteException) {
            return ((org.sqlite.SQLiteException) e).getResultCode().code & 0xff;
        }
        return e.getErrorCode() & 0xff;
    }
}
