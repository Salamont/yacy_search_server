/*
 *  KgUpgradeTest
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.RandomAccessFile;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgIds;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;

/**
 * The upgrade of a graph of Scoutro 0.7 (schema 3) to vocabulary 2 (schema
 * 4, package 6): integrity check and a verified copy before the migration, a
 * held re-extraction without room for the copy, a damaged graph left as it
 * is, and the copy kept through the backup retention.
 */
public class KgUpgradeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;

    @After
    public void close() {
        if (this.store != null) {
            this.store.close();
        }
    }

    /** A schema-3 graph as Scoutro 0.7 leaves it (WAL, checkpointed), with some events to give it pages. */
    private KgPaths v3(final String name, final int events) throws Exception {
        final KgPaths p = new KgPaths(this.tmp.newFolder(name));
        assertTrue(p.dir.mkdirs());
        try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + p.db.getAbsolutePath(), new Properties());
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
                    + KgSchema.META_CHANGES_MIN_SEQ + "', '1'), ('" + KgSchema.META_EXTRACTORS + "', '1:jsonld:2,1:metadata:1,2:rule:2')");
            for (int i = 0; i < events; i++) {
                st.execute("INSERT INTO kg_event (at, level, code, detail) VALUES (" + i + ", 1, 'filler', '" + "x".repeat(400) + "')");
            }
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        return p;
    }

    private static KgConfig cfg() {
        return KgTestSupport.config(KgTestSupport.enabled());
    }

    private static File[] backups(final KgPaths p) {
        final File[] files = p.backup.listFiles();
        return files == null ? new File[0] : files;
    }

    @Test
    public void anUpgradeChecksTheOldGraphAndKeepsAVerifiedCopyFirst() throws Exception {
        final KgPaths p = v3("plain", 20);
        final KgConfig cfg = cfg();
        final StorageGuard guard = new StorageGuard(cfg, p, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(p, cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        assertEquals(4, this.store.schemaVersion());
        final JSONObject up = this.store.upgrade();
        assertNotNull(up);
        assertEquals(3, up.getInt("from"));
        assertEquals(4, up.getInt("to"));
        assertEquals("ok", up.getString("quickCheck"));
        final String name = up.getString("backup");
        assertTrue(name, KgBackup.NAME.matcher(name).matches() && name.endsWith("-before-upgrade.db"));
        assertTrue(KgBackup.beforeUpgrade(name) && KgBackup.safety(name));
        final File copy = new File(p.backup, name);
        // the copy is the old graph: schema 3, verified, with its metadata, and nothing partial is left
        assertEquals(3, KgBackup.verify(copy, 3).getInt("schema_version"));
        final JSONObject meta = KgBackup.readMeta(copy);
        assertEquals("upgrade", meta.getString("trigger"));
        assertEquals(KgBackup.sha256(copy), meta.getString("sha256"));
        assertEquals(2, backups(p).length);
        final String recorded = this.store.read(c -> {
            assertNull("nothing waits", KgStore.getMeta(c, KgSchema.META_UPGRADE_HOLD));
            assertEquals(1L, KgStore.queryLong(c, "SELECT count(*) FROM kg_event WHERE code = 'upgrade_backup'"));
            return KgStore.getMeta(c, KgSchema.META_UPGRADE);
        });
        assertEquals(name, new JSONObject(recorded).getString("backup"));
        // a second start does not copy again
        this.store.close();
        this.store = KgStore.open(p, cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        assertNull(this.store.upgrade());
        assertEquals(2, backups(p).length);
    }

    @Test
    public void withoutRoomForTheCopyTheMigrationRunsAndTheReextractionWaits() throws Exception {
        final KgPaths p = v3("full", 20);
        final KgConfig cfg = cfg();
        final KgTestSupport.Probe probe = new KgTestSupport.Probe();
        probe.usable.set(cfg.criticalFloorBytes() + 1024L); // the disk is nearly full: no copy of the graph fits
        final StorageGuard guard = new StorageGuard(cfg, p, probe, System::currentTimeMillis);
        this.store = KgStore.open(p, cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        assertEquals(4, this.store.schemaVersion());
        final JSONObject up = this.store.upgrade();
        assertTrue(up.isNull("backup"));
        assertEquals(StorageGuard.DISK_CRITICAL, up.getString("hold"));
        assertEquals("no copy and no partial file", 0, backups(p).length);
        this.store.read(c -> {
            assertEquals(StorageGuard.DISK_CRITICAL, KgStore.getMeta(c, KgSchema.META_UPGRADE_HOLD));
            assertEquals("the old extractor identity stays until the hold ends", "1:jsonld:2,1:metadata:1,2:rule:2",
                    KgStore.getMeta(c, KgSchema.META_EXTRACTORS));
            assertEquals(1L, KgStore.queryLong(c, "SELECT count(*) FROM kg_event WHERE code = 'upgrade_held'"));
            return null;
        });
    }

    @Test
    public void aDamagedGraphIsNotMigratedAndStaysAsItIs() throws Exception {
        final KgPaths p = v3("damaged", 400);
        // overwrite the last pages (event rows) with garbage; the schema and the meta table stay readable
        final long pageSize;
        try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + p.db.getAbsolutePath(), new Properties());
                Statement st = c.createStatement()) {
            pageSize = st.executeQuery("PRAGMA page_size").getLong(1);
        }
        try (RandomAccessFile f = new RandomAccessFile(p.db, "rw")) {
            final long pages = f.length() / pageSize;
            for (long page = pages - 3; page < pages; page++) {
                f.seek(page * pageSize);
                final byte[] junk = new byte[(int) pageSize];
                java.util.Arrays.fill(junk, (byte) 0x5a);
                f.write(junk);
            }
        }
        final String before = KgBackup.sha256(p.db);
        final KgConfig cfg = cfg();
        final StorageGuard guard = new StorageGuard(cfg, p, new KgTestSupport.Probe(), System::currentTimeMillis);
        try {
            this.store = KgStore.open(p, cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
            fail("a damaged graph must not be migrated");
        } catch (final KgException e) {
            assertEquals(KgException.UPGRADE_BLOCKED, e.code());
            assertEquals("integrity_failed", e.reason());
        }
        assertEquals("the graph file is unchanged", before, KgBackup.sha256(p.db));
        assertEquals("no copy of a damaged graph", 0, backups(p).length);
    }

    @Test
    public void theNewestCopyBeforeAnUpgradeOutlivesTheRegularRetention() throws Exception {
        final File dir = this.tmp.newFolder("retention");
        final List<String> names = List.of("graph-20261001T000000Z-before-upgrade.db", "graph-20261002T000000Z-before-upgrade.db",
                "graph-20261003T000000Z.db", "graph-20261004T000000Z-before-rebuild.db", "graph-20261005T000000Z.db");
        for (final String n : names) {
            assertTrue(new File(dir, n).createNewFile());
        }
        final List<String> removed = KgBackup.retain(dir, 1);
        assertEquals(List.of("graph-20261005T000000Z.db"), names(dir, false));
        assertTrue("the newest copy before an upgrade stays", new File(dir, "graph-20261002T000000Z-before-upgrade.db").isFile());
        assertFalse(new File(dir, "graph-20261001T000000Z-before-upgrade.db").isFile());
        assertFalse(new File(dir, "graph-20261004T000000Z-before-rebuild.db").isFile());
        assertTrue(removed.containsAll(List.of("graph-20261001T000000Z-before-upgrade.db", "graph-20261003T000000Z.db",
                "graph-20261004T000000Z-before-rebuild.db")));
    }

    private static List<String> names(final File dir, final boolean safety) {
        final List<String> out = new java.util.ArrayList<>();
        for (final File f : KgBackup.list(dir)) {
            if (KgBackup.safety(f.getName()) == safety) {
                out.add(f.getName());
            }
        }
        return out;
    }
}
