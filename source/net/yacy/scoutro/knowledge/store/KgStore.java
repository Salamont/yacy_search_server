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
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
 * One writer connection, serialised by a lock; every write is admitted by the
 * {@link StorageGuard} first and runs in one {@code BEGIN IMMEDIATE}
 * transaction. Readers come from a small pool and only inside
 * {@link #read(SqlWork)}: each read is a lease with a deadline. A reader that
 * overruns its deadline is interrupted ({@code sqlite3_interrupt}), because an
 * open read transaction keeps the WAL from being checkpointed and lets it grow
 * without bound. Readers never hold a transaction across client I/O: callers
 * materialise a bounded page and return.
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
    private final BlockingQueue<Connection> idleReaders = new LinkedBlockingQueue<>();
    private final AtomicInteger readerCount = new AtomicInteger();
    private final Map<Connection, Lease> leases = new ConcurrentHashMap<>();
    private final AtomicLong interruptedReads = new AtomicLong();
    private volatile boolean closed;
    private long pageSize;
    private long maxPageCount;
    private int schemaVersion;
    private String epoch;
    private boolean created;

    private static final class Lease {
        final long startedAt;
        final long deadlineMillis;
        volatile boolean interrupted;

        Lease(final long startedAt, final long deadlineMillis) {
            this.startedAt = startedAt;
            this.deadlineMillis = deadlineMillis;
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
            guard.admit(WriteClass.MAINTENANCE, SCHEMA_ESTIMATE_BYTES);
        }
        final Connection writer;
        try {
            writer = factory.open(paths.db);
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
            closeQuietly(writer);
            throw e;
        } catch (final SQLException e) {
            closeQuietly(writer);
            throw new KgException(KgException.STORAGE_ERROR, null, "cannot initialise " + paths.db + ": " + e.getMessage(), e);
        } catch (final RuntimeException | LinkageError e) {
            closeQuietly(writer);
            throw new KgException(KgException.NATIVE_LIBRARY_UNAVAILABLE, null,
                    "SQLite failed during initialisation: " + e.getClass().getSimpleName(), e);
        }
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

    private void configureCommon(final Connection c, final int cacheKiB) throws SQLException {
        exec(c, "PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLIS);
        exec(c, "PRAGMA temp_store=FILE");
        exec(c, "PRAGMA temp_store_directory='" + this.paths.tmp.getAbsolutePath().replace("'", "''") + "'");
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
                    putMeta(this.writer, KgSchema.META_SCHEMA_VERSION, Integer.toString(KgSchema.CURRENT_VERSION));
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
                // future: migrations from version to CURRENT_VERSION, each in this transaction
                this.schemaVersion = version;
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
     * {@code estimateBytes} of growth for the given class. A refused or failed
     * write leaves the database unchanged.
     */
    public <T> T write(final WriteClass writeClass, final long estimateBytes, final SqlWork<T> work) throws KgException {
        ensureOpen();
        admit(writeClass, estimateBytes);
        final T result;
        this.writeLock.lock();
        try {
            ensureOpen();
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

    /** Admission with one checkpoint attempt when only the WAL is in the way. */
    private void admit(final WriteClass writeClass, final long estimateBytes) throws KgException {
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
     * Returns free pages to the filesystem in small steps (each call of the
     * pragma through sqlite-jdbc frees one page). Never a full VACUUM.
     *
     * @return freed pages
     */
    public long incrementalVacuum(final long maxPages, final long maxMillis) throws KgException {
        ensureOpen();
        admit(WriteClass.MAINTENANCE, 64L * 1024L);
        final long deadline = this.clock.getAsLong() + maxMillis;
        long freed = 0L;
        this.writeLock.lock();
        try {
            final long before = queryLong(this.writer, "PRAGMA freelist_count");
            long remaining = before;
            try (Statement st = this.writer.createStatement()) {
                while (remaining > 0L && freed < maxPages && this.clock.getAsLong() < deadline) {
                    st.execute("PRAGMA incremental_vacuum");
                    freed++;
                    if (freed % 64L == 0L) {
                        remaining = queryLong(this.writer, "PRAGMA freelist_count");
                    } else {
                        remaining--;
                    }
                }
            }
            freed = before - queryLong(this.writer, "PRAGMA freelist_count");
        } catch (final SQLException e) {
            throw mapWrite(e);
        } finally {
            this.writeLock.unlock();
        }
        maybeCheckpoint();
        return freed;
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

    /** Runs {@code work} in one read transaction; it is interrupted after {@code deadlineMillis}. */
    public <T> T read(final SqlWork<T> work, final long deadlineMillis) throws KgException {
        ensureOpen();
        final Connection c = acquireReader();
        final Lease lease = new Lease(this.clock.getAsLong(), deadlineMillis);
        this.leases.put(c, lease);
        boolean broken = false;
        try {
            exec(c, "BEGIN");
            try {
                final T result = work.run(c);
                exec(c, "COMMIT");
                return result;
            } catch (final SQLException e) {
                rollbackQuietly(c);
                throw mapRead(e, lease);
            } catch (final KgException | RuntimeException | Error e) {
                rollbackQuietly(c);
                throw e;
            }
        } catch (final SQLException e) {
            broken = true;
            throw mapRead(e, lease);
        } finally {
            this.leases.remove(c);
            releaseReader(c, broken);
        }
    }

    private KgException mapRead(final SQLException e, final Lease lease) {
        final int code = baseCode(e);
        if (lease.interrupted || code == SQLITE_INTERRUPT) {
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

    private Connection acquireReader() throws KgException {
        Connection c = this.idleReaders.poll();
        if (c != null) {
            return c;
        }
        if (this.readerCount.incrementAndGet() <= MAX_READERS) {
            try {
                c = this.factory.open(this.paths.db);
                configureCommon(c, 4096);
                exec(c, "PRAGMA query_only=1");
                return c;
            } catch (final SQLException e) {
                this.readerCount.decrementAndGet();
                closeQuietly(c);
                throw new KgException(isNativeFailure(e) ? KgException.NATIVE_LIBRARY_UNAVAILABLE : KgException.STORAGE_ERROR,
                        null, "cannot open a reader: " + e.getMessage(), e);
            } catch (final LinkageError e) {
                this.readerCount.decrementAndGet();
                throw new KgException(KgException.NATIVE_LIBRARY_UNAVAILABLE, null, "SQLite could not be loaded", e);
            }
        }
        this.readerCount.decrementAndGet();
        try {
            c = this.idleReaders.poll(READER_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (c == null) {
            throw new KgException(KgException.BUSY, "all knowledge graph readers are busy");
        }
        return c;
    }

    private void releaseReader(final Connection c, final boolean broken) {
        if (this.closed || broken) {
            closeQuietly(c);
            this.readerCount.decrementAndGet();
            return;
        }
        this.idleReaders.offer(c);
    }

    /**
     * Interrupts every read that has run longer than its deadline; called by
     * the runtime every second and after a blocked checkpoint.
     *
     * @return number of interrupted reads
     */
    public int interruptExpiredReaders(final long now) {
        int n = 0;
        for (final Map.Entry<Connection, Lease> e : this.leases.entrySet()) {
            final Lease l = e.getValue();
            // re-check that the lease is still the current one of this connection, so a read that
            // just finished does not let the interrupt hit the next read on the same connection
            if (!l.interrupted && now - l.startedAt > l.deadlineMillis && this.leases.get(e.getKey()) == l) {
                interrupt(e.getKey(), l);
                n++;
            }
        }
        return n;
    }

    /** Interrupts every running read, e.g. when temp files exceed their limit. */
    public int interruptAllReaders() {
        int n = 0;
        for (final Map.Entry<Connection, Lease> e : this.leases.entrySet()) {
            if (!e.getValue().interrupted) {
                interrupt(e.getKey(), e.getValue());
                n++;
            }
        }
        return n;
    }

    private void interrupt(final Connection c, final Lease l) {
        l.interrupted = true;
        this.interruptedReads.incrementAndGet();
        if (c instanceof org.sqlite.SQLiteConnection) {
            try {
                ((org.sqlite.SQLiteConnection) c).getDatabase().interrupt();
            } catch (final SQLException | RuntimeException e) {
                // the read ends with an error anyway when it next touches the database
            }
        }
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

    /** {@code PRAGMA quick_check}; "ok" if the database is consistent. */
    public String quickCheck(final long deadlineMillis) throws KgException {
        return read(c -> queryString(c, "PRAGMA quick_check(1)"), deadlineMillis);
    }

    public JSONObject readerStatus(final long now) {
        long oldest = 0L;
        for (final Lease l : this.leases.values()) {
            oldest = Math.max(oldest, now - l.startedAt);
        }
        return KgJson.obj("open", this.leases.size(), "oldestAgeMillis", this.leases.isEmpty() ? null : oldest,
                "maxTransactionMillis", this.cfg.readMaxTransactionMillis, "interrupted", this.interruptedReads.get());
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

    /** Final checkpoint and close; idempotent. */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.writeLock.lock();
        try {
            try {
                exec(this.writer, "PRAGMA busy_timeout=" + CHECKPOINT_BUSY_TIMEOUT_MILLIS);
                try (Statement st = this.writer.createStatement(); ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                    rs.next();
                }
            } catch (final SQLException e) {
                // the WAL is replayed on the next open
            }
            this.closed = true;
            closeQuietly(this.writer);
            Connection c;
            while ((c = this.idleReaders.poll()) != null) {
                closeQuietly(c);
                this.readerCount.decrementAndGet();
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

    private static void closeQuietly(final Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (final SQLException | RuntimeException e) {
            // closing anyway
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
