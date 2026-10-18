package net.yacy.scoutro.knowledge.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgIds;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;

/**
 * Scoutro R5, schema 8 -> 9: a graph of schema 8 (its capture triggers without website technologies, the matching
 * triggers without a predicate filter) gets the five capture triggers again with website_technology and the two matching
 * observation triggers with a filter that leaves technologies out; the upgrade takes its verified copy first; nothing
 * else changes.
 */
public class TechnologySchemaTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final List<String> CAPTURE = List.of("kg_observation_evidence_delete", "kg_observation_evidence_update",
            "kg_observation_statement_delete", "kg_observation_doc_update", "kg_observation_doc_delete");
    private static final List<String> MATCHING = List.of("kg_match_observation_state", "kg_match_observation_insert");

    private static Map<String, String> triggers(final Connection c) throws Exception {
        final Map<String, String> out = new LinkedHashMap<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT name, sql FROM sqlite_master WHERE type='trigger' ORDER BY name")) {
            while (rs.next()) out.put(rs.getString(1), rs.getString(2));
        }
        return out;
    }

    /** A schema-8 graph as R1-R4 leave it: the capture triggers of that time, without website technologies. */
    private KgPaths schemaEight() throws Exception {
        final KgPaths p = new KgPaths(this.tmp.newFolder("eight"));
        assertTrue(p.dir.mkdirs());
        try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + p.db.getAbsolutePath(), new java.util.Properties());
                Statement s = c.createStatement()) {
            s.execute("PRAGMA auto_vacuum=INCREMENTAL");
            s.execute("PRAGMA journal_mode=WAL");
            for (final String ddl : KgSchema.DDL_V1) s.execute(ddl);
            for (int v = 0; v < 7; v++) {
                for (final String ddl : KgSchema.MIGRATIONS[v]) s.execute(ddl);
            }
            for (final String name : CAPTURE) {
                final String sql = triggers(c).get(name);
                assertNotNull(name, sql);
                s.execute("DROP TRIGGER " + name);
                s.execute(sql.replace(",'website_technology'", "").replace(" OR p.name='website_technology'", ""));
            }
            for (final String ddl : new String[] {"INSERT INTO kg_meta (key, value) VALUES ('" + KgSchema.META_SCHEMA_VERSION + "', '8')",
                    "INSERT INTO kg_meta (key, value) VALUES ('" + KgSchema.META_EPOCH + "', '" + KgIds.newEpoch() + "')",
                    "INSERT INTO kg_meta (key, value) VALUES ('" + KgSchema.META_CLEAN_SHUTDOWN + "', '1')",
                    "INSERT INTO kg_meta (key, value) VALUES ('" + KgSchema.META_CHANGES_MIN_SEQ + "', '1')"}) {
                s.execute(ddl);
            }
            for (final Map.Entry<String, String> t : triggers(c).entrySet()) {
                assertFalse("schema 8 knows no website technology: " + t.getKey(), t.getValue().contains("website_technology"));
            }
            s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        return p;
    }

    @Test
    public void schemaEightGetsTheTechnologyCaptureAndAMatchingThatIgnoresIt() throws Exception {
        final KgPaths p = schemaEight();
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled());
        try (KgStore store = KgStore.open(p, cfg, new StorageGuard(cfg, p, new KgTestSupport.Probe(), System::currentTimeMillis), KgStore.SQLITE,
                System::currentTimeMillis)) {
            assertEquals(9, KgSchema.CURRENT_VERSION);
            assertEquals(9, store.schemaVersion());
            final JSONObject up = store.upgrade();
            assertNotNull(up);
            assertEquals(8, up.getInt("from"));
            assertEquals(9, up.getInt("to"));
            assertTrue("a verified copy before the upgrade", up.has("backup") && !up.isNull("backup"));
            assertEquals(8, KgBackup.verify(new java.io.File(p.backup, up.getString("backup")), 8).getInt("schema_version"));
            final Map<String, String> t = store.read(c -> {
                try {
                    return triggers(c);
                } catch (final Exception e) {
                    throw new java.sql.SQLException(e);
                }
            });
            for (final String name : CAPTURE) {
                assertTrue(name, t.get(name).contains("'website_technology'"));
                assertTrue(name + " archives a technology on change only", t.get(name).contains("OR p.name='website_technology'"));
            }
            for (final String name : MATCHING) {
                assertTrue(name, t.get(name).contains("WHEN NEW.predicate<>'website_technology'"));
            }
            assertEquals("ok", (String) store.read(c -> KgStore.queryString(c, "PRAGMA integrity_check")));
            assertEquals(1L, (long) store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_event WHERE code='schema_migrated'"
                    + " AND detail='schema 8 -> 9'")));
        }
    }

    @Test
    public void aNewGraphStartsWithTheTechnologyRulesInEveryTrigger() throws Exception {
        final KgPaths p = new KgPaths(this.tmp.newFolder("new"));
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled());
        try (KgStore store = KgStore.open(p, cfg, new StorageGuard(cfg, p, new KgTestSupport.Probe(), System::currentTimeMillis), KgStore.SQLITE,
                System::currentTimeMillis)) {
            assertEquals(9, store.schemaVersion());
            final Map<String, String> t = store.read(c -> {
                try {
                    return triggers(c);
                } catch (final Exception e) {
                    throw new java.sql.SQLException(e);
                }
            });
            for (final String name : MATCHING) assertTrue(name, t.get(name).contains("WHEN NEW.predicate<>'website_technology'"));
            for (final String name : CAPTURE) assertTrue(name, t.get(name).contains("'website_technology'"));
        }
    }
}
