package net.yacy.scoutro.knowledge.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgIds;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;

public class KgStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgPaths paths;
    private KgConfig cfg;
    private StorageGuard guard;
    private KgStore store;

    @Before
    public void open() throws Exception {
        this.paths = new KgPaths(this.tmp.getRoot());
        this.cfg = KgTestSupport.config(KgTestSupport.enabled());
        this.guard = new StorageGuard(this.cfg, this.paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(this.paths, this.cfg, this.guard, KgStore.SQLITE, System::currentTimeMillis);
    }

    @After
    public void close() {
        if (this.store != null) {
            this.store.close();
        }
    }

    @Test
    public void createsSchemaV1WithSafePragmas() throws Exception {
        assertTrue(this.store.created());
        assertEquals(KgSchema.CURRENT_VERSION, this.store.schemaVersion());
        assertTrue(KgIds.isEpoch(this.store.epoch()));
        assertTrue(this.paths.db.isFile());
        assertTrue(this.paths.tmp.isDirectory());
        this.store.write(WriteClass.MAINTENANCE, 0, c -> {
            assertEquals("wal", KgStore.queryString(c, "PRAGMA journal_mode"));
            assertEquals(2L, KgStore.queryLong(c, "PRAGMA synchronous")); // FULL
            assertEquals(1L, KgStore.queryLong(c, "PRAGMA foreign_keys"));
            assertEquals(2L, KgStore.queryLong(c, "PRAGMA auto_vacuum")); // INCREMENTAL
            assertEquals(this.paths.tmp.getAbsolutePath(), KgStore.queryString(c, "PRAGMA temp_store_directory"));
            assertEquals(this.store.maxPageCount(), KgStore.queryLong(c, "PRAGMA max_page_count"));
            assertEquals("1", KgStore.getMeta(c, KgSchema.META_CHANGES_MIN_SEQ));
            assertEquals("1", KgStore.getMeta(c, KgSchema.META_CLEAN_SHUTDOWN));
            return null;
        });
        // max_page_count caps the main file at the data share of the budget
        assertEquals(this.cfg.dataBytes() / 4096L, this.store.maxPageCount());
    }

    @Test
    public void reopenKeepsEpochAndData() throws Exception {
        final String epoch = this.store.epoch();
        this.store.write(WriteClass.MAINTENANCE, 0, c -> {
            KgStore.putMeta(c, "probe", "kept");
            return null;
        });
        this.store.close();
        this.store = KgStore.open(this.paths, this.cfg, this.guard, KgStore.SQLITE, System::currentTimeMillis);
        assertFalse(this.store.created());
        assertEquals(epoch, this.store.epoch());
        assertEquals("kept", this.store.read(c -> KgStore.getMeta(c, "probe")));
    }

    @Test
    public void newerSchemaIsRefusedAndLeftUntouched() throws Exception {
        this.store.write(WriteClass.MAINTENANCE, 0, c -> {
            KgStore.putMeta(c, KgSchema.META_SCHEMA_VERSION, "99");
            return null;
        });
        this.store.close();
        this.store = null;
        final long before = this.paths.db.length();
        try {
            KgStore.open(this.paths, this.cfg, this.guard, KgStore.SQLITE, System::currentTimeMillis);
            fail("a newer schema must not be opened");
        } catch (final KgException e) {
            assertEquals(KgException.SCHEMA_UNSUPPORTED, e.code());
        }
        try (Connection c = KgStore.SQLITE.open(this.paths.db)) {
            assertEquals("99", KgStore.getMeta(c, KgSchema.META_SCHEMA_VERSION));
        }
        assertTrue(this.paths.db.length() >= before);
    }

    @Test
    public void foreignDatabaseIsRefused() throws Exception {
        this.store.close();
        this.store = null;
        final KgPaths other = new KgPaths(this.tmp.newFolder("other"));
        assertTrue(other.dir.mkdirs());
        try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + other.db.getAbsolutePath(), new Properties());
                Statement st = c.createStatement()) {
            st.execute("CREATE TABLE something_else (x INTEGER)");
        }
        try {
            KgStore.open(other, this.cfg, this.guard, KgStore.SQLITE, System::currentTimeMillis);
            fail("a foreign database must not be opened");
        } catch (final KgException e) {
            assertEquals(KgException.SCHEMA_UNSUPPORTED, e.code());
        }
    }

    @Test
    public void foreignKeysAndChecksAreEnforced() throws Exception {
        // evidence for a document that does not exist
        expectConstraint(c -> exec(c, "INSERT INTO kg_evidence (stmt_rowid, doc_rowid, tier, ext_id, kind, certainty, observed_at)"
                + " VALUES (1, 1, 1, 1, 1, 1, 0)"));
        // invalid document state
        expectConstraint(c -> exec(c, "INSERT INTO kg_doc (doc_id, state, token, solr_version, state_since)"
                + " VALUES ('AbCdEfGhIjKl', 9, zeroblob(8), 1, 0)"));
        // document id that is not a YaCy URL hash
        expectConstraint(c -> exec(c, "INSERT INTO kg_doc (doc_id, state, token, solr_version, state_since)"
                + " VALUES ('not-a-hash', 1, zeroblob(8), 1, 0)"));
        // statement with both an entity and a value as object
        expectConstraint(c -> {
            seedEntity(c);
            exec(c, "INSERT INTO kg_statement (public_id, subj, pred, obj_ent, obj_val, obj_key, quality, first_seen)"
                    + " VALUES ('kgs_aaaaaaaaaaaaaaaaaaaa', 1, 2, 1, 'x', zeroblob(16), 1, 0)");
        });
        // malformed public id
        expectConstraint(c -> exec(c, "INSERT INTO kg_vocab (term_id, kind, name) VALUES (1, 1, 'organization');"
                + "INSERT INTO kg_entity (public_id, type, status, created_seq) VALUES ('kge_UPPERCASE_NOT_OK__', 1, 1, 1)"));
        // merged entity without a target
        expectConstraint(c -> exec(c, "INSERT INTO kg_vocab (term_id, kind, name) VALUES (1, 1, 'organization');"
                + "INSERT INTO kg_entity (public_id, type, status, created_seq) VALUES ('kge_aaaaaaaaaaaaaaaaaaaa', 1, 2, 1)"));
    }

    @Test
    public void severalTiersSupportTheSameStatementFromOneDocument() throws Exception {
        this.store.write(WriteClass.GROWTH, 0, c -> {
            seedEntity(c);
            exec(c, "INSERT INTO kg_statement (public_id, subj, pred, obj_val, obj_key, quality, first_seen)"
                    + " VALUES ('kgs_aaaaaaaaaaaaaaaaaaaa', 1, 2, '+49 30 1234567', zeroblob(16), 1, 0)");
            exec(c, "INSERT INTO kg_doc (doc_id, state, token, solr_version, state_since) VALUES ('AbCdEfGhIjKl', 1, zeroblob(8), 7, 0)");
            exec(c, "INSERT INTO kg_extractor (ext_id, tier, name, version) VALUES (1, 1, 'jsonld', '1'), (2, 2, 'rules', '1')");
            exec(c, "INSERT INTO kg_evidence (stmt_rowid, doc_rowid, tier, ext_id, kind, certainty, locator, observed_at)"
                    + " VALUES (1, 1, 1, 1, 1, 1, 'jsonld:0/telephone', 0), (1, 1, 2, 2, 3, 1, 'text:120+14', 0)");
            return null;
        });
        assertEquals(2L, (long) this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_evidence")));
        // replacing tier 2 of the document leaves the tier-1 evidence alone
        this.store.write(WriteClass.MAINTENANCE, 0, c -> {
            exec(c, "DELETE FROM kg_evidence WHERE doc_rowid = 1 AND tier = 2");
            return null;
        });
        assertEquals(1L, (long) this.store.read(c -> KgStore.queryLong(c, "SELECT tier FROM kg_evidence")));
        // deleting the document removes its evidence (ON DELETE CASCADE)
        this.store.write(WriteClass.MAINTENANCE, 0, c -> {
            exec(c, "DELETE FROM kg_doc WHERE doc_id = 'AbCdEfGhIjKl'");
            return null;
        });
        assertEquals(0L, (long) this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_evidence")));
    }

    @Test
    public void documentIdsSortLikeSolrStrings() throws Exception {
        // Solr sorts string fields by unsigned UTF-8 bytes; the reconcile merge relies on the same order
        final List<String> ids = Arrays.asList("zzzzzzzzzzzz", "AAAAAAAAAAAA", "000000000000", "------------",
                "____________", "aaaaaaaaaaaa", "Z9-_aZ9-_aZ9", "9ZaaaaaaaaaB");
        this.store.write(WriteClass.GROWTH, 0, c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kg_doc (doc_id, state, token, solr_version, state_since)"
                    + " VALUES (?, 1, zeroblob(8), 1, 0)")) {
                for (final String id : ids) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
            }
            return null;
        });
        final List<String> sqlite = this.store.read(c -> {
            final List<String> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT doc_id FROM kg_doc ORDER BY doc_id")) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
        final List<String> bytes = new ArrayList<>(ids);
        bytes.sort((a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
        assertEquals(bytes, sqlite);
    }

    @Test
    public void readersAreReadOnly() throws Exception {
        try {
            this.store.read(c -> {
                exec(c, "INSERT INTO kg_meta (key, value) VALUES ('x', 'y')");
                return null;
            });
            fail("a reader must not write");
        } catch (final KgException e) {
            assertNotNull(e.code());
        }
        assertNull(this.store.read(c -> KgStore.getMeta(c, "x")));
    }

    @Test
    public void failedWorkRollsBack() throws Exception {
        try {
            this.store.write(WriteClass.MAINTENANCE, 0, c -> {
                KgStore.putMeta(c, "half", "written");
                throw new SQLException("boom");
            });
            fail();
        } catch (final KgException e) {
            assertEquals(KgException.SQL_ERROR, e.code());
        }
        assertNull(this.store.read(c -> KgStore.getMeta(c, "half")));
    }

    @Test
    public void eventRingIsBounded() throws Exception {
        this.store.write(WriteClass.MAINTENANCE, 0, c -> {
            for (int i = 0; i < 1100; i++) {
                KgStore.event(c, 1, "e" + i, null, i);
            }
            return null;
        });
        final long n = this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_event"));
        assertTrue("events: " + n, n <= 1001);
    }

    private interface Sql {
        void run(Connection c) throws SQLException;
    }

    private void expectConstraint(final Sql sql) throws Exception {
        try {
            this.store.write(WriteClass.MAINTENANCE, 0, c -> {
                sql.run(c);
                return null;
            });
            fail("constraint expected");
        } catch (final KgException e) {
            assertEquals(e.getMessage(), KgException.CONSTRAINT, e.code());
        }
    }

    private static void seedEntity(final Connection c) throws SQLException {
        exec(c, "INSERT INTO kg_vocab (term_id, kind, name) VALUES (1, 1, 'organization'), (2, 2, 'phone')");
        exec(c, "INSERT INTO kg_entity (ent_rowid, public_id, type, status, created_seq) VALUES (1, 'kge_aaaaaaaaaaaaaaaaaaaa', 1, 1, 1)");
    }

    private static void exec(final Connection c, final String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (final String s : sql.split(";")) {
                st.execute(s);
            }
        }
    }
}
