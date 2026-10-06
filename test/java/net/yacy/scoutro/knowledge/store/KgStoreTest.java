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
        final String tmpDir = this.paths.tmp.getCanonicalPath();
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
            // process-wide, set by SqliteProcess on the first connection
            assertEquals(tmpDir, KgStore.queryString(c, "PRAGMA temp_store_directory"));
            assertEquals("1 = FILE", 1L, KgStore.queryLong(c, "PRAGMA temp_store"));
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
    public void schemaV1IsMigratedInPlaceKeepingItsData() throws Exception {
        this.store.close();
        this.store = null;
        final KgPaths v1 = new KgPaths(this.tmp.newFolder("v1"));
        assertTrue(v1.dir.mkdirs());
        try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + v1.db.getAbsolutePath(), new Properties());
                Statement st = c.createStatement()) {
            st.execute("PRAGMA auto_vacuum=INCREMENTAL");
            st.execute("PRAGMA journal_mode=WAL");
            for (final String ddl : KgSchema.DDL_V1) {
                st.execute(ddl);
            }
            st.execute("INSERT INTO kg_meta (key, value) VALUES ('" + KgSchema.META_SCHEMA_VERSION + "', '1'), ('"
                    + KgSchema.META_EPOCH + "', '" + KgIds.newEpoch() + "'), ('" + KgSchema.META_CLEAN_SHUTDOWN + "', '1'), ('"
                    + KgSchema.META_CHANGES_MIN_SEQ + "', '1')");
            st.execute("INSERT INTO kg_work (doc_id, reason, event_version, priority, not_before) VALUES ('AAAAAAhost01', 1, 7, 3, 0)");
            st.execute("INSERT INTO kg_scan (kind, state, started_at) VALUES (1, 2, 5)");
        }
        this.store = KgStore.open(v1, this.cfg, this.guard, KgStore.SQLITE, System::currentTimeMillis);
        assertFalse(this.store.created());
        assertEquals(KgSchema.CURRENT_VERSION, this.store.schemaVersion());
        this.store.read(c -> {
            assertEquals(Integer.toString(KgSchema.CURRENT_VERSION), KgStore.getMeta(c, KgSchema.META_SCHEMA_VERSION));
            assertEquals("one event per step (v1 -> v2 -> v3 -> v4)", KgSchema.CURRENT_VERSION - 1L,
                    KgStore.queryLong(c, "SELECT count(*) FROM kg_event WHERE code = 'schema_migrated'"));
            assertEquals("v3: the LLM queue", 0L, KgStore.queryLong(c, "SELECT count(*) FROM kg_llm_work"));
            assertEquals("v3: no LLM state yet", 0L, KgStore.queryLong(c, "SELECT count(llm_status) + count(llm_hash) FROM kg_doc"));
            assertEquals("v1 rows keep their values and get the defaults", 7L,
                    KgStore.queryLong(c, "SELECT event_version FROM kg_work WHERE enqueued_at = 0"));
            assertEquals(1L, KgStore.queryLong(c, "SELECT phase FROM kg_scan"));
            assertEquals(0L, KgStore.queryLong(c, "SELECT count(*) FROM kg_scan_candidate"));
            assertEquals(0L, KgStore.queryLong(c, "SELECT count(subkind) FROM kg_entity"));
            assertEquals("ok", KgStore.queryString(c, "PRAGMA foreign_key_check") == null ? "ok" : "violations");
            return null;
        });
        // a second open finds the current version and migrates nothing
        this.store.close();
        this.store = KgStore.open(v1, this.cfg, this.guard, KgStore.SQLITE, System::currentTimeMillis);
        assertEquals(KgSchema.CURRENT_VERSION - 1L,
                (long) this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_event WHERE code = 'schema_migrated'")));
    }

    @Test
    public void schemaV3IsMigratedToV4KeepingTheChangeFeedAndItsSequence() throws Exception {
        this.store.close();
        this.store = null;
        final KgPaths v3 = new KgPaths(this.tmp.newFolder("v3"));
        assertTrue(v3.dir.mkdirs());
        try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + v3.db.getAbsolutePath(), new Properties());
                Statement st = c.createStatement()) {
            st.execute("PRAGMA auto_vacuum=INCREMENTAL");
            st.execute("PRAGMA journal_mode=WAL");
            for (final String ddl : KgSchema.DDL_V1) {
                st.execute(ddl);
            }
            for (int v = 0; v < 2; v++) {
                for (final String ddl : KgSchema.MIGRATIONS[v]) {
                    st.execute(ddl);
                }
            }
            st.execute("INSERT INTO kg_meta (key, value) VALUES ('" + KgSchema.META_SCHEMA_VERSION + "', '3'), ('"
                    + KgSchema.META_EPOCH + "', '" + KgIds.newEpoch() + "'), ('" + KgSchema.META_CLEAN_SHUTDOWN + "', '1'), ('"
                    + KgSchema.META_CHANGES_MIN_SEQ + "', '1')");
            // the feed's sequence is ahead of its rows (the newest row was coalesced away): no cursor may move back
            st.execute("INSERT INTO kg_change (seq, kind, public_id, op, scopes_now, scopes_seen, at) VALUES"
                    + " (5, 1, 'kge_aaaaaaaaaaaaaaaaaaaa', 1, '1', '1', 10), (9, 2, 'kgs_aaaaaaaaaaaaaaaaaaaa', 2, '', '1,2', 11),"
                    + " (12, 1, 'kge_bbbbbbbbbbbbbbbbbbbb', 1, '2', '2', 12)");
            st.execute("DELETE FROM kg_change WHERE seq = 12");
        }
        this.store = KgStore.open(v3, this.cfg, this.guard, KgStore.SQLITE, System::currentTimeMillis);
        assertEquals(4, this.store.schemaVersion());
        this.store.read(c -> {
            assertEquals(1L, KgStore.queryLong(c, "SELECT count(*) FROM kg_event WHERE code = 'schema_migrated'"));
            assertEquals("the rows are copied with their sequence numbers", "5:1:1:1,9:2:2:1,2",
                    KgStore.queryString(c, "SELECT group_concat(seq || ':' || kind || ':' || op || ':' || scopes_seen, ',') FROM"
                            + " (SELECT * FROM kg_change ORDER BY seq)"));
            assertEquals("the sequence is kept", 12L, KgStore.queryLong(c, "SELECT seq FROM sqlite_sequence WHERE name = 'kg_change'"));
            assertEquals(0L, KgStore.queryLong(c, "SELECT count(*) FROM kg_meta WHERE key = 'migration_change_seq'"));
            assertEquals(0L, KgStore.queryLong(c, "SELECT count(*) FROM kg_derived") + KgStore.queryLong(c, "SELECT count(*) FROM kg_doc_link"));
            assertEquals(0L, KgStore.queryLong(c, "SELECT count(content_hash) FROM kg_doc"));
            assertEquals(1L, KgStore.queryLong(c, "SELECT count(*) FROM sqlite_master WHERE name = 'kg_change_at'"));
            assertEquals("ok", KgStore.queryString(c, "PRAGMA integrity_check"));
            assertEquals("ok", KgStore.queryString(c, "PRAGMA foreign_key_check") == null ? "ok" : "violations");
            return null;
        });
        this.store.write(WriteClass.SYSTEM, 0, c -> {
            // the third kind (derived) is accepted, and the next change continues after the kept sequence
            exec(c, "INSERT INTO kg_change (kind, public_id, op, scopes_now, scopes_seen, at) VALUES (3, 'kgd_aaaaaaaaaaaaaaaaaaaa', 1, '1', '1', 13)");
            assertEquals(13L, KgStore.queryLong(c, "SELECT seq FROM kg_change WHERE kind = 3"));
            return null;
        });
        expectConstraint(c -> exec(c, "INSERT INTO kg_change (kind, public_id, op, at) VALUES (4, 'kgd_bbbbbbbbbbbbbbbbbbbb', 1, 0)"));
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
