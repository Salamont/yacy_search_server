package net.yacy.scoutro.knowledge.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.budget.StorageProbe;

/**
 * Where SQLite really creates its temp files and how the guard counts them
 * (review finding 5 of PR #12). Linux only: the files are unlinked right
 * after creation and visible only through /proc/self/fd.
 */
public class KgTempFilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void linuxOnly() {
        Assume.assumeTrue(new File("/proc/self/fd").isDirectory());
        assertEquals("no SQLite connection is left open by an earlier test", 0, SqliteProcess.openConnections());
    }

    private static final class Graph implements AutoCloseable {
        final KgPaths paths;
        final StorageGuard guard;
        final KgStore store;

        Graph(final File root) throws Exception {
            final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled(KgConfig.TMP_MAX_BYTES, Long.toString(256 * KgTestSupport.MIB),
                    KgConfig.BUDGET_MAX_BYTES, Long.toString(4096 * KgTestSupport.MIB)));
            this.paths = new KgPaths(root);
            this.guard = new StorageGuard(cfg, this.paths, StorageProbe.SYSTEM, System::currentTimeMillis);
            this.store = KgStore.open(this.paths, cfg, this.guard, KgStore.SQLITE, System::currentTimeMillis);
            // 12 MiB of rows: sorting them spills to temp files (reader cache 4 MiB)
            for (int k = 0; k < 4; k++) {
                this.store.write(WriteClass.MAINTENANCE, 0, c -> {
                    try (Statement st = c.createStatement()) {
                        st.execute("CREATE TABLE IF NOT EXISTS big (v BLOB)");
                        st.execute("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 6000)"
                                + " INSERT INTO big SELECT randomblob(500) FROM n");
                    }
                    return null;
                });
            }
            this.store.checkpoint();
        }

        /** Runs a sort that spills, and reports the SQLite temp files open meanwhile and the guard's view. */
        Object[] sortAndMeasure() throws Exception {
            return this.store.read(c -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT v FROM big ORDER BY v")) {
                    rs.next();
                    final List<String> files = sqliteTempFiles();
                    this.guard.refresh();
                    return new Object[] {files, this.guard.status().optJSONObject("files")};
                }
            });
        }

        @Override
        public void close() {
            this.store.close();
        }
    }

    static List<String> sqliteTempFiles() {
        final List<String> out = new ArrayList<>();
        final File[] fds = new File("/proc/self/fd").listFiles();
        if (fds != null) {
            for (final File fd : fds) {
                try {
                    final String target = Files.readSymbolicLink(fd.toPath()).toString();
                    if (target.contains(StorageProbe.SQLITE_TEMP_PREFIX)) {
                        out.add(target);
                    }
                } catch (final Exception e) {
                    // closed meanwhile
                }
            }
        }
        return out;
    }

    private static String canonical(final File f) throws Exception {
        return f.getCanonicalPath() + File.separator;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void tempFilesLandInTheGraphTempDirectoryAndAreCounted() throws Exception {
        try (Graph g = new Graph(this.tmp.newFolder("a"))) {
            assertTrue(SqliteProcess.tempDirectoryIs(g.paths.tmp));
            final Object[] m = g.sortAndMeasure();
            final List<String> files = (List<String>) m[0];
            final JSONObject counted = (JSONObject) m[1];
            assertFalse("the sort must spill to a temp file", files.isEmpty());
            for (final String f : files) {
                assertTrue(f, f.startsWith(canonical(g.paths.tmp)));
                assertTrue("unlinked right after creation: " + f, f.endsWith(" (deleted)"));
            }
            assertTrue("counted: " + counted, counted.getLong("tmpOpen") > 1024L * 1024L);
            assertEquals(0L, counted.getLong("tmpOpenElsewhere"));
            assertEquals(StorageGuard.TMP_DIR_GRAPH, counted.getString("tmpDirectory"));
            assertEquals("nothing visible in the directory", 0L, counted.getLong("tmpVisible"));
            g.guard.refresh();
            assertEquals("released with the lease", 0L, g.guard.status().getJSONObject("files").getLong("tmpOpen"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void tempDirectoryIsNeverChangedWhileAConnectionIsOpen() throws Exception {
        final File rootA = this.tmp.newFolder("a");
        final File rootB = this.tmp.newFolder("b");
        try (Graph a = new Graph(rootA)) {
            try (Graph b = new Graph(rootB)) {
                // b opened while a's connections exist: SQLite's process-wide directory stays a's
                assertTrue(SqliteProcess.tempDirectoryIs(a.paths.tmp));
                assertFalse(SqliteProcess.tempDirectoryIs(b.paths.tmp));
                final Object[] m = b.sortAndMeasure();
                final List<String> files = (List<String>) m[0];
                final JSONObject counted = (JSONObject) m[1];
                assertFalse(files.isEmpty());
                for (final String f : files) {
                    assertTrue(f, f.startsWith(canonical(a.paths.tmp)));
                }
                assertEquals(StorageGuard.TMP_DIR_OTHER, counted.getString("tmpDirectory"));
                assertEquals(0L, counted.getLong("tmpOpen"));
                assertTrue("still counted for b: " + counted, counted.getLong("tmpOpenElsewhere") > 1024L * 1024L);
            }
        }
        assertEquals(0, SqliteProcess.openConnections());
        // no connection left: the next store may set its own directory
        try (Graph b = new Graph(rootB)) {
            assertTrue(SqliteProcess.tempDirectoryIs(b.paths.tmp));
            final List<String> files = (List<String>) b.sortAndMeasure()[0];
            assertFalse(files.isEmpty());
            for (final String f : files) {
                assertTrue(f, f.startsWith(canonical(b.paths.tmp)));
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void missingTempDirectoryFallsBackAndIsStillCounted() throws Exception {
        try (Graph g = new Graph(this.tmp.newFolder("a"))) {
            // SQLite silently uses /var/tmp or /tmp when its directory is gone
            Files.delete(g.paths.tmp.toPath());
            final Object[] m = g.sortAndMeasure();
            final List<String> files = (List<String>) m[0];
            final JSONObject counted = (JSONObject) m[1];
            assertFalse(files.isEmpty());
            for (final String f : files) {
                assertFalse(f, f.startsWith(canonical(g.paths.tmp)));
            }
            assertTrue("counted elsewhere: " + counted, counted.getLong("tmpOpenElsewhere") > 1024L * 1024L);
            Path restored = g.paths.tmp.toPath();
            Files.createDirectories(restored);
        }
    }
}
