package net.yacy.scoutro.knowledge.budget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.store.KgStore;

public class StorageGuardTest {

    private static final long MIB = KgTestSupport.MIB;
    private static final long GIB = KgTestSupport.GIB;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** File sizes and free space fully controlled by the test. */
    private static final class FakeProbe implements StorageProbe {
        final AtomicLong db = new AtomicLong();
        final AtomicLong wal = new AtomicLong();
        final AtomicLong usable = new AtomicLong(100L * GIB);
        final AtomicLong tmpOpen = new AtomicLong();

        @Override
        public long fileBytes(final File file) {
            if (file.getName().endsWith("-wal")) {
                return this.wal.get();
            }
            if (file.getName().endsWith(".db")) {
                return this.db.get();
            }
            return 0L;
        }

        @Override
        public long dirBytes(final File dir) {
            return 0L;
        }

        @Override
        public long usableBytes(final File path) {
            return this.usable.get();
        }

        @Override
        public long openUnlinkedBytes(final File dir) {
            return this.tmpOpen.get();
        }
    }

    private StorageGuard guard(final FakeProbe probe, final String... settings) {
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled(settings));
        assertTrue(cfg.problems().toString(), cfg.valid());
        return new StorageGuard(cfg, new KgPaths(this.tmp.getRoot()), probe, System::currentTimeMillis);
    }

    private static String refusal(final StorageGuard g, final WriteClass c, final long estimate) {
        try {
            g.admit(c, estimate);
            return null;
        } catch (final KgException e) {
            assertEquals(KgException.WRITE_REFUSED, e.code());
            return e.reason();
        }
    }

    @Test
    public void budgetPausesGrowthWithHysteresisButKeepsMaintenance() {
        final FakeProbe p = new FakeProbe();
        final StorageGuard g = guard(p, KgConfig.BUDGET_MAX_BYTES, Long.toString(1000 * MIB));
        p.db.set(500 * MIB);
        assertNull(refusal(g, WriteClass.GROWTH, MIB));
        // a single transaction goes through the WAL first, so it can never be larger than the WAL limit
        assertEquals(StorageGuard.WAL_LIMIT, refusal(g, WriteClass.GROWTH, 450 * MIB));
        // the estimate may cross the pause threshold (900 MiB)
        p.db.set(880 * MIB);
        assertEquals(StorageGuard.BUDGET, refusal(g, WriteClass.GROWTH, 30 * MIB));
        p.db.set(905 * MIB);
        assertEquals(StorageGuard.BUDGET, refusal(g, WriteClass.GROWTH, 0));
        assertNull("deletions continue at the pause threshold", refusal(g, WriteClass.MAINTENANCE, MIB));
        // between resume (800 MiB) and pause the guard stays paused
        p.db.set(850 * MIB);
        assertEquals(StorageGuard.BUDGET, refusal(g, WriteClass.GROWTH, 0));
        p.db.set(790 * MIB);
        assertNull(refusal(g, WriteClass.GROWTH, MIB));
        // maintenance may use the whole budget, not more
        p.db.set(995 * MIB);
        assertEquals(StorageGuard.BUDGET_EXHAUSTED, refusal(g, WriteClass.MAINTENANCE, 10 * MIB));
    }

    @Test
    public void diskReserveAndCriticalFloor() {
        final FakeProbe p = new FakeProbe();
        final StorageGuard g = guard(p, "resource.disk.free.min.steadystate", "4096", "resource.disk.free.min.undershot", "2048",
                KgConfig.DISK_RESERVE_BYTES, Long.toString(GIB), KgConfig.DISK_HYSTERESIS_BYTES, Long.toString(512 * MIB));
        p.usable.set(6L * GIB);
        assertNull(refusal(g, WriteClass.GROWTH, MIB));
        p.usable.set(5L * GIB - MIB); // below 4 GiB YaCy steady state + 1 GiB reserve
        assertEquals(StorageGuard.DISK_RESERVE, refusal(g, WriteClass.GROWTH, 0));
        assertNull(refusal(g, WriteClass.MAINTENANCE, MIB));
        p.usable.set(5L * GIB + 100 * MIB); // above the floor, below floor + hysteresis
        assertEquals(StorageGuard.DISK_RESERVE, refusal(g, WriteClass.GROWTH, 0));
        p.usable.set(5L * GIB + 600 * MIB);
        assertNull(refusal(g, WriteClass.GROWTH, MIB));
        // below YaCy's undershot even deletions are refused: they would need WAL space
        p.usable.set(2L * GIB - MIB);
        assertEquals(StorageGuard.DISK_CRITICAL, refusal(g, WriteClass.MAINTENANCE, 0));
        assertEquals(StorageGuard.DISK_CRITICAL, refusal(g, WriteClass.GROWTH, 0));
    }

    @Test
    public void walLimitAppliesToEveryWriteClass() {
        final FakeProbe p = new FakeProbe();
        final StorageGuard g = guard(p, KgConfig.WAL_MAX_BYTES, Long.toString(16 * MIB),
                KgConfig.WAL_CHECKPOINT_BYTES, Long.toString(2 * MIB));
        p.wal.set(15 * MIB);
        assertEquals(StorageGuard.WAL_LIMIT, refusal(g, WriteClass.MAINTENANCE, 2 * MIB));
        assertEquals(StorageGuard.WAL_LIMIT, refusal(g, WriteClass.GROWTH, 2 * MIB));
        g.recordCheckpoint(new CheckpointResult(true, 100, 0, System.currentTimeMillis(), 15 * MIB));
        assertEquals(StorageGuard.WAL_CHECKPOINT_BLOCKED, refusal(g, WriteClass.MAINTENANCE, 2 * MIB));
        g.recordCheckpoint(new CheckpointResult(false, 0, 0, System.currentTimeMillis(), 0));
        p.wal.set(0);
        assertNull(refusal(g, WriteClass.MAINTENANCE, 2 * MIB));
    }

    @Test
    public void tempFilesManualPauseAndStorageErrors() {
        final FakeProbe p = new FakeProbe();
        final StorageGuard g = guard(p, KgConfig.TMP_MAX_BYTES, Long.toString(8 * MIB));
        p.tmpOpen.set(9 * MIB);
        g.refresh();
        assertTrue(g.tmpOverLimit());
        assertEquals(StorageGuard.TMP_LIMIT, refusal(g, WriteClass.GROWTH, 0));
        p.tmpOpen.set(0);
        g.refresh();
        g.setManualPause(true);
        assertEquals(StorageGuard.MANUAL, refusal(g, WriteClass.GROWTH, 0));
        assertNull("a manual pause does not stop deletions", refusal(g, WriteClass.MAINTENANCE, 0));
        g.setManualPause(false);
        g.storageError("sqlite_10");
        assertEquals(StorageGuard.STORAGE_ERROR, refusal(g, WriteClass.MAINTENANCE, 0));
        g.clearStorageError();
        assertNull(refusal(g, WriteClass.GROWTH, 0));
    }

    @Test
    public void statusNamesEveryActiveReason() {
        final FakeProbe p = new FakeProbe();
        final StorageGuard g = guard(p, KgConfig.BUDGET_MAX_BYTES, Long.toString(1000 * MIB));
        p.db.set(950 * MIB);
        g.setManualPause(true);
        final JSONObject s = g.status();
        assertFalse(s.optBoolean("growthAllowed", true));
        assertTrue(s.optBoolean("maintenanceAllowed", false));
        final String reasons = s.optJSONArray("reasons").toString();
        assertTrue(reasons, reasons.contains("\"manual\""));
        assertTrue(reasons, reasons.contains("\"budget\""));
        assertEquals("application_budget", s.optString("quota"));
    }

    // ------------------------------------------------------------ real SQLite

    private KgStore realStore(final KgTestSupport.Probe probe, final Map<String, String> settings) throws Exception {
        final KgConfig cfg = KgTestSupport.config(settings);
        assertTrue(cfg.problems().toString(), cfg.valid());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard g = new StorageGuard(cfg, paths, probe, System::currentTimeMillis);
        return KgStore.open(paths, cfg, g, KgStore.SQLITE, System::currentTimeMillis);
    }

    private static void insertBlob(final Connection c, final int bytes) throws java.sql.SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS t_fill (v BLOB)");
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO t_fill (v) VALUES (randomblob(?))")) {
            ps.setInt(1, bytes);
            ps.executeUpdate();
        }
    }

    @Test
    public void openReaderBlocksTheCheckpointAndWritesPauseUntilItEnds() throws Exception {
        final KgTestSupport.Probe probe = new KgTestSupport.Probe();
        final KgStore store = realStore(probe, KgTestSupport.enabled(
                KgConfig.WAL_MAX_BYTES, Long.toString(4 * MIB), KgConfig.WAL_CHECKPOINT_BYTES, Long.toString(MIB)));
        final KgPaths paths = store.paths();
        try (Connection reader = KgStore.SQLITE.open(paths.db)) {
            // a reader outside the store's leases holds a snapshot open (e.g. a stuck client)
            reader.setAutoCommit(false);
            try (Statement st = reader.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM kg_meta")) {
                rs.next();
            }
            String refused = null;
            CheckpointResult blocked = null;
            for (int i = 0; i < 40 && refused == null; i++) {
                try {
                    store.write(WriteClass.MAINTENANCE, 400 * 1024, c -> {
                        insertBlob(c, 300 * 1024);
                        return null;
                    });
                } catch (final KgException e) {
                    refused = e.reason();
                }
                if (blocked == null && paths.wal.length() >= MIB) {
                    blocked = store.checkpoint();
                }
            }
            assertTrue("the checkpoint must report the blocking reader", blocked != null && blocked.busy);
            assertFalse(blocked.complete());
            assertEquals(StorageGuard.WAL_CHECKPOINT_BLOCKED, refused);
            assertTrue("WAL grew past journal_size_limit: " + paths.wal.length(), paths.wal.length() > MIB);
            reader.commit();
        }
        // with the reader gone the next write checkpoints, the WAL is reset and writing resumes
        store.write(WriteClass.MAINTENANCE, 400 * 1024, c -> {
            insertBlob(c, 1024);
            return null;
        });
        final CheckpointResult done = store.checkpoint();
        assertTrue(done.complete());
        assertEquals(0L, paths.wal.length());
        store.close();
    }

    @Test
    public void readerPastItsDeadlineIsInterrupted() throws Exception {
        final KgStore store = realStore(new KgTestSupport.Probe(),
                KgTestSupport.enabled(KgConfig.READ_MAX_TRANSACTION_MILLIS, "200"));
        final AtomicReference<KgException> failure = new AtomicReference<>();
        final Thread t = new Thread(() -> {
            try {
                store.read(c -> {
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                            "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n) SELECT count(*) FROM n")) {
                        rs.next();
                    }
                    return null;
                });
            } catch (final KgException e) {
                failure.set(e);
            }
        });
        t.start();
        final long until = System.currentTimeMillis() + 10_000L;
        while (t.isAlive() && System.currentTimeMillis() < until) {
            store.interruptExpiredReaders(System.currentTimeMillis());
            Thread.sleep(50);
        }
        t.join(1000);
        assertFalse("the endless read must be stopped", t.isAlive());
        assertEquals(KgException.READ_TIMEOUT, failure.get().code());
        assertTrue(store.readerStatus(System.currentTimeMillis()).optLong("interrupted") >= 1);
        // the store keeps working
        assertEquals("1", store.read(c -> KgStore.getMeta(c, "clean_shutdown")));
        store.close();
    }

    @Test
    public void pageLimitRefusesGrowthCleanly() throws Exception {
        // 64 MiB budget: the main file may use 80 % (about 51 MiB) before SQLite answers SQLITE_FULL
        final KgStore store = realStore(new KgTestSupport.Probe(), KgTestSupport.enabled(
                KgConfig.BUDGET_MAX_BYTES, Long.toString(64 * MIB),
                KgConfig.WAL_MAX_BYTES, Long.toString(4 * MIB), KgConfig.WAL_CHECKPOINT_BYTES, Long.toString(MIB),
                KgConfig.TMP_MAX_BYTES, Long.toString(4 * MIB)));
        KgException full = null;
        int committed = 0;
        for (int i = 0; i < 200 && full == null; i++) {
            try {
                store.write(WriteClass.MAINTENANCE, 0, c -> {
                    for (int k = 0; k < 4; k++) {
                        insertBlob(c, 128 * 1024);
                    }
                    return null;
                });
                committed++;
            } catch (final KgException e) {
                full = e;
            }
        }
        assertTrue("SQLite must refuse growth at max_page_count", full != null);
        assertEquals(KgException.STORAGE_FULL, full.code());
        final int rows = committed * 4;
        assertEquals("the refused transaction left nothing behind", (long) rows,
                (long) store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM t_fill")));
        assertEquals("ok", store.quickCheck(60_000L));
        assertTrue(store.paths().db.length() <= store.maxPageCount() * 4096L);
        // a deletion still works and space comes back without a full VACUUM
        store.write(WriteClass.MAINTENANCE, 0, c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM t_fill");
            }
            return null;
        });
        final long before = store.paths().db.length();
        final long freed = store.incrementalVacuum(100_000, 60_000);
        store.checkpoint();
        assertTrue("freed pages: " + freed, freed > 1000);
        assertTrue(store.paths().db.length() < before);
        store.close();
    }
}
