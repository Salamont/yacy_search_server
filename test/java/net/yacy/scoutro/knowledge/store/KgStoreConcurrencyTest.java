package net.yacy.scoutro.knowledge.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.budget.StorageProbe;

/** Admission, read leases and vacuum under concurrency (review findings 1, 2 and 4 of PR #12). */
public class KgStoreConcurrencyTest {

    private static final long MIB = KgTestSupport.MIB;
    private static final String ENDLESS = "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n) SELECT count(*) FROM n";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;

    @After
    public void closeStore() {
        if (this.store != null) {
            this.store.close();
        }
    }

    /** Real files and plenty of free space; the database file appears larger by {@code extraDb} bytes. */
    private static final class OffsetProbe implements StorageProbe {
        final AtomicLong extraDb = new AtomicLong();

        @Override
        public long fileBytes(final File file) {
            final long real = SYSTEM.fileBytes(file);
            return file.getName().equals("graph.db") && real > 0 ? real + this.extraDb.get() : real;
        }

        @Override
        public long dirBytes(final File dir) {
            return SYSTEM.dirBytes(dir);
        }

        @Override
        public long usableBytes(final File path) {
            return 100L * KgTestSupport.GIB;
        }

        @Override
        public TempFiles openTempFiles(final File dir) {
            return new TempFiles(0L, 0L);
        }
    }

    private KgStore open(final StorageProbe probe, final Map<String, String> settings) throws Exception {
        final KgConfig cfg = KgTestSupport.config(settings);
        assertTrue(cfg.problems().toString(), cfg.valid());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(cfg, paths, probe, System::currentTimeMillis);
        this.store = KgStore.open(paths, cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        return this.store;
    }

    private static void waitFor(final String what, final BooleanSupplier condition) throws InterruptedException {
        final long until = System.currentTimeMillis() + 10_000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void insertBlob(final Connection c, final String table, final int bytes) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + table + " (id INTEGER PRIMARY KEY, v BLOB)");
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table + " (v) VALUES (randomblob(?))")) {
            ps.setInt(1, bytes);
            ps.executeUpdate();
        }
    }

    // --------------------------------------------------------------- finding 1

    @Test
    public void twoWritersNearTheLimitAreNotAdmittedOnTheSameFreeSpace() throws Exception {
        final OffsetProbe probe = new OffsetProbe();
        final KgStore s = open(probe, KgTestSupport.enabled(KgConfig.BUDGET_MAX_BYTES, Long.toString(128 * MIB),
                KgConfig.WAL_MAX_BYTES, Long.toString(16 * MIB), KgConfig.WAL_CHECKPOINT_BYTES, Long.toString(4 * MIB),
                KgConfig.TMP_MAX_BYTES, Long.toString(4 * MIB)));
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled(KgConfig.BUDGET_MAX_BYTES, Long.toString(128 * MIB)));
        // 1.5 MiB below the pause threshold: room for one 1 MiB write, not for two
        final long used = guard(s).status().getLong("usedBytes");
        probe.extraDb.set(cfg.pauseAtBytes() - used - 3 * MIB / 2);
        final long estimate = MIB + 64 * 1024;

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<Object> first = new AtomicReference<>();
        final AtomicReference<Object> second = new AtomicReference<>();
        final Thread a = new Thread(() -> {
            try {
                s.write(WriteClass.GROWTH, estimate, c -> {
                    insertBlob(c, "t_a", (int) MIB);
                    entered.countDown();
                    await(go);
                    return null;
                });
                first.set("ok");
            } catch (final KgException e) {
                first.set(e);
            }
        });
        final Thread b = new Thread(() -> {
            try {
                s.write(WriteClass.GROWTH, estimate, c -> {
                    insertBlob(c, "t_b", (int) MIB);
                    return null;
                });
                second.set("ok");
            } catch (final KgException e) {
                second.set(e);
            }
        });
        a.start();
        await(entered);
        b.start();
        // B asks for admission while A has been admitted and is still writing
        waitFor("the second writer to queue for the write lock", () -> s.queuedWriters() == 1);
        go.countDown();
        a.join(20_000);
        b.join(20_000);
        assertEquals("ok", first.get());
        assertTrue("the second writer must be refused: " + second.get(), second.get() instanceof KgException);
        final KgException refused = (KgException) second.get();
        assertEquals(KgException.WRITE_REFUSED, refused.code());
        assertEquals(StorageGuard.BUDGET, refused.reason());
        assertTrue("usage stays below the pause threshold",
                guard(s).status().getLong("usedBytes") < cfg.pauseAtBytes());
    }

    private static StorageGuard guard(final KgStore s) throws Exception {
        final java.lang.reflect.Field f = KgStore.class.getDeclaredField("guard");
        f.setAccessible(true);
        return (StorageGuard) f.get(s);
    }

    // --------------------------------------------------------------- finding 4

    @Test
    public void lateInterruptCannotReachTheNextLeaseOnTheSameConnection() throws Exception {
        final KgStore s = open(new KgTestSupport.Probe(), KgTestSupport.enabled());
        // R3 occupies the second reader connection, so R2 must reuse R1's connection
        final CountDownLatch r3Inside = new CountDownLatch(1);
        final CountDownLatch r3Release = new CountDownLatch(1);
        final Thread r3 = new Thread(() -> {
            try {
                s.read(c -> {
                    KgStore.queryLong(c, "SELECT 1");
                    r3Inside.countDown();
                    await(r3Release);
                    return null;
                }, 60_000L);
            } catch (final KgException e) {
                throw new IllegalStateException(e);
            }
        });
        r3.start();
        await(r3Inside);

        final CountDownLatch r1Ready = new CountDownLatch(1);
        final CountDownLatch r1Go = new CountDownLatch(1);
        final AtomicReference<Object> r1Result = new AtomicReference<>();
        final Thread r1 = new Thread(() -> {
            try {
                r1Result.set(s.read(c -> {
                    final long n = KgStore.queryLong(c, "SELECT 7");
                    r1Ready.countDown();
                    await(r1Go);
                    return n;
                }, 50L));
            } catch (final KgException e) {
                r1Result.set(e);
            }
        });
        r1.start();
        await(r1Ready);
        Thread.sleep(150); // R1's lease is now past its deadline, R3's is not

        // the watchdog has decided to interrupt R1 and is paused right before sqlite3_interrupt
        final CountDownLatch watchdogInside = new CountDownLatch(1);
        final CountDownLatch watchdogGo = new CountDownLatch(1);
        s.setInterruptProbe(() -> {
            watchdogInside.countDown();
            await(watchdogGo);
        });
        final AtomicReference<Integer> interrupted = new AtomicReference<>();
        final Thread watchdog = new Thread(() -> interrupted.set(s.interruptExpiredReaders(System.currentTimeMillis())));
        watchdog.start();
        await(watchdogInside);

        // R1 finishes its work: it cannot hand its connection back while the interrupt is pending
        r1Go.countDown();
        waitFor("R1 to wait for the connection's monitor", () -> r1.getState() == Thread.State.BLOCKED);
        final AtomicReference<Object> r2Result = new AtomicReference<>();
        final Thread r2 = new Thread(() -> {
            try {
                r2Result.set(s.read(c -> KgStore.queryLong(c,
                        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 2000000) SELECT count(*) FROM n"),
                        60_000L));
            } catch (final KgException e) {
                r2Result.set(e);
            }
        });
        r2.start();
        waitFor("R2 to wait for a free reader", () -> r2.getState() == Thread.State.TIMED_WAITING);
        assertTrue("R1 still holds its lease", r1.isAlive());

        s.setInterruptProbe(null);
        watchdogGo.countDown();
        watchdog.join(10_000);
        r1.join(10_000);
        r2.join(30_000);
        assertEquals(Integer.valueOf(1), interrupted.get());
        assertEquals("R1's work had finished; its result stands", 7L, r1Result.get());
        assertEquals("the next lease on the same connection is not interrupted", 2_000_000L, r2Result.get());
        assertEquals(1L, s.readerStatus(System.currentTimeMillis()).optLong("interrupted"));
        assertEquals(2L, s.readerStatus(System.currentTimeMillis()).optLong("connections"));
        r3Release.countDown();
        r3.join(10_000);
    }

    @Test
    public void closeWaitsForAPendingInterruptAndThenClosesEveryConnection() throws Exception {
        final KgStore s = open(new KgTestSupport.Probe(), KgTestSupport.enabled());
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread reader = new Thread(() -> {
            try {
                result.set(s.read(c -> {
                    KgStore.queryLong(c, "SELECT 1");
                    inside.countDown();
                    await(go);
                    return KgStore.queryLong(c, "SELECT 2"); // after close() interrupted the lease: refused
                }, 50L));
            } catch (final KgException e) {
                result.set(e);
            }
        });
        reader.start();
        await(inside);
        Thread.sleep(100);
        final CountDownLatch watchdogInside = new CountDownLatch(1);
        final CountDownLatch watchdogGo = new CountDownLatch(1);
        s.setInterruptProbe(() -> {
            watchdogInside.countDown();
            await(watchdogGo);
        });
        final Thread watchdog = new Thread(() -> s.interruptExpiredReaders(System.currentTimeMillis()));
        watchdog.start();
        await(watchdogInside);
        final Thread closer = new Thread(s::close);
        closer.start();
        waitFor("close() to wait for the connection's monitor", () -> closer.getState() == Thread.State.BLOCKED);
        s.setInterruptProbe(null);
        watchdogGo.countDown();
        closer.join(10_000);
        assertFalse(closer.isAlive());
        go.countDown();
        reader.join(10_000);
        assertTrue(String.valueOf(result.get()), result.get() instanceof KgException);
        assertEquals(KgException.READ_TIMEOUT, ((KgException) result.get()).code());
        assertEquals("writer and readers are closed", 0, SqliteProcess.openConnections());
        assertEquals(0L, s.readerStatus(System.currentTimeMillis()).optLong("connections"));
        this.store = null;
    }

    @Test
    public void statementAfterTheDeadlineIsRefusedEvenWithoutAnInterrupt() throws Exception {
        final KgStore s = open(new KgTestSupport.Probe(), KgTestSupport.enabled());
        final long t0 = System.currentTimeMillis();
        try {
            s.read(c -> {
                KgStore.queryLong(c, "SELECT 1");
                try {
                    Thread.sleep(250);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return KgStore.queryLong(c, ENDLESS);
            }, 100L);
            fail();
        } catch (final KgException e) {
            assertEquals(KgException.READ_TIMEOUT, e.code());
        }
        assertTrue(System.currentTimeMillis() - t0 < 2000);
        // row steps of a running query are checked too
        try {
            s.read(c -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1 UNION ALL SELECT 2")) {
                    rs.next();
                    Thread.sleep(250);
                    rs.next();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }, 100L);
            fail();
        } catch (final KgException e) {
            assertEquals(KgException.READ_TIMEOUT, e.code());
        }
        assertEquals(2L, s.readerStatus(System.currentTimeMillis()).optLong("interrupted"));
    }

    @Test
    public void leaseOwnsItsTransaction() throws Exception {
        final KgStore s = open(new KgTestSupport.Probe(), KgTestSupport.enabled());
        for (final String forbidden : new String[] {"commit", "rollback", "autocommit", "close", "unwrap"}) {
            try {
                s.read(c -> {
                    switch (forbidden) {
                        case "commit":
                            c.commit();
                            break;
                        case "rollback":
                            c.rollback();
                            break;
                        case "autocommit":
                            c.setAutoCommit(false);
                            break;
                        case "close":
                            c.close();
                            break;
                        default:
                            c.unwrap(org.sqlite.SQLiteConnection.class);
                    }
                    return null;
                });
                fail(forbidden + " must be refused");
            } catch (final KgException e) {
                assertEquals(forbidden, KgException.SQL_ERROR, e.code());
            }
        }
        // a statement the work leaves open does not outlive the lease
        final AtomicReference<Statement> leaked = new AtomicReference<>();
        s.read(c -> {
            final Statement st = c.createStatement();
            st.executeQuery("SELECT 1").next();
            leaked.set(st);
            return null;
        });
        assertTrue(leaked.get().isClosed());
        assertNotNull(s.read(c -> KgStore.getMeta(c, KgSchema.META_EPOCH)));
    }

    // --------------------------------------------------------------- finding 2

    /** Rows of 3 KiB with every second one deleted: free pages spread over the file. */
    private void fragment(final KgStore s, final int rows) throws Exception {
        for (int done = 0; done < rows; done += 500) {
            s.write(WriteClass.MAINTENANCE, 0, c -> {
                for (int i = 0; i < 500; i++) {
                    insertBlob(c, "t_frag", 3 * 1024);
                }
                return null;
            });
            s.checkpoint();
        }
        s.write(WriteClass.MAINTENANCE, 0, c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM t_frag WHERE id % 2 = 0");
            }
            return null;
        });
        s.checkpoint();
    }

    @Test
    public void everyVacuumBatchStaysWithinItsWalEstimate() throws Exception {
        final KgStore s = open(new KgTestSupport.Probe(), KgTestSupport.enabled(
                KgConfig.WAL_MAX_BYTES, Long.toString(256 * MIB), KgConfig.WAL_CHECKPOINT_BYTES, Long.toString(128 * MIB),
                KgConfig.BUDGET_MAX_BYTES, Long.toString(2048 * MIB)));
        fragment(s, 6000);
        final File wal = s.paths().wal;
        final long estimate = s.vacuumEstimateBytes(KgStore.VACUUM_BATCH_PAGES);
        long maxGrowth = 0L;
        int batches = 0;
        while (true) {
            final long before = wal.length();
            final KgStore.VacuumResult r = s.incrementalVacuum(KgStore.VACUUM_BATCH_PAGES, 60_000L);
            if (r.freedPages == 0L) {
                assertEquals(KgStore.VacuumResult.DONE, r.stop);
                break;
            }
            assertEquals(1, r.batches);
            maxGrowth = Math.max(maxGrowth, wal.length() - before);
            batches++;
        }
        assertTrue("batches: " + batches, batches > 10);
        assertTrue("largest batch wrote " + maxGrowth + " WAL bytes, estimate " + estimate, maxGrowth <= estimate);
    }

    @Test
    public void vacuumStopsAtABlockedCheckpointAndKeepsTheWalBounded() throws Exception {
        // fill with a large WAL, then reopen with a tight one
        KgStore s = open(new KgTestSupport.Probe(), KgTestSupport.enabled());
        fragment(s, 6000);
        s.close();
        final long walMax = 4 * MIB;
        s = open(new KgTestSupport.Probe(), KgTestSupport.enabled(
                KgConfig.WAL_MAX_BYTES, Long.toString(walMax), KgConfig.WAL_CHECKPOINT_BYTES, Long.toString(MIB),
                KgConfig.TMP_MAX_BYTES, Long.toString(4 * MIB)));
        final KgPaths paths = s.paths();
        final long dbBefore = paths.db.length();
        final long freeBefore = s.read(c -> KgStore.queryLong(c, "PRAGMA freelist_count"));
        assertTrue("free pages: " + freeBefore, freeBefore > 2000);

        final AtomicLong walPeak = new AtomicLong();
        final Thread watch = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                walPeak.accumulateAndGet(paths.wal.length(), Math::max);
            }
        });
        watch.setDaemon(true);
        watch.start();
        try {
            final KgStore.VacuumResult blocked;
            try (Connection reader = KgStore.SQLITE.open(paths.db)) {
                // a reader outside the store holds an old snapshot: no checkpoint can complete
                reader.setAutoCommit(false);
                try (Statement st = reader.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM kg_meta")) {
                    rs.next();
                }
                blocked = s.incrementalVacuum(1_000_000L, 60_000L);
                reader.commit();
            }
            assertEquals(StorageGuard.WAL_CHECKPOINT_BLOCKED, blocked.stop);
            assertTrue(blocked.freedPages > 0 && blocked.remainingFreePages > 0);
            assertTrue("vacuum stopped early: " + blocked.freedPages + " of " + freeBefore, blocked.freedPages < freeBefore / 2);
            assertEquals("the main file cannot shrink without a checkpoint", dbBefore, paths.db.length());
            assertTrue("WAL " + paths.wal.length(), paths.wal.length() <= walMax);

            // reader gone: the vacuum finishes, checkpointing between batches
            final KgStore.VacuumResult done = s.incrementalVacuum(1_000_000L, 60_000L);
            assertEquals(KgStore.VacuumResult.DONE, done.stop);
            assertEquals(0L, done.remainingFreePages);
            assertTrue(s.checkpoint().complete());
            assertTrue("the main file shrank: " + dbBefore + " -> " + paths.db.length(), paths.db.length() < dbBefore * 3 / 4);
        } finally {
            watch.interrupt();
            watch.join(1000);
        }
        assertTrue("WAL peak " + walPeak.get() + " exceeded " + walMax, walPeak.get() <= walMax);
        assertEquals("ok", s.quickCheck(60_000L));
        assertNull(s.read(c -> KgStore.getMeta(c, "absent")));
    }
}
