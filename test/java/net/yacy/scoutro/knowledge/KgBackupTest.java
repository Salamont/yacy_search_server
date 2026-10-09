package net.yacy.scoutro.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgBackup;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * Backups and restore inside the graph's DATA directory
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 7.7 and 13; O6): a verified copy with a
 * checksum and metadata, listed and found only by its name; a restore puts
 * it back with a new epoch and keeps the previous graph aside; damaged,
 * foreign and unknown files change nothing; growth pauses skip a backup;
 * retention and the schedule.
 */
public class KgBackupTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private KgRuntime runtime;
    private File data;
    private long version = 1000L;

    private KgRuntime open(final String... settings) {
        final Map<String, String> s = KgTestSupport.enabled(settings);
        final KgRuntime r = new KgRuntime(new KgRuntime.Env(this.data, s::get, this.now::get, new KgTestSupport.Probe(), KgStore.SQLITE, false));
        r.open();
        assertEquals(KgRuntime.State.RUNNING, r.state());
        return r;
    }

    @Before
    public void setUp() throws Exception {
        this.data = this.tmp.newFolder("root");
        this.runtime = open();
        publish("AAAAAAhost01", "https://www.muster.de/", "{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\","
                + "\"url\":\"https://www.muster.de/\",\"telephone\":\"030 1234567\"}");
    }

    @After
    public void tearDown() {
        this.runtime.close();
    }

    private void publish(final String id, final String url, final String block) throws Exception {
        final KgStore store = KgTestSupport.store(this.runtime);
        final Terms terms = new Terms();
        store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        final Publisher publisher = new Publisher(KgTestSupport.config(KgTestSupport.enabled()), terms);
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = net.yacy.cora.document.id.DigestURL.hosthash(d.host, 443);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of("ca");
        d.solrVersion = ++this.version;
        d.loadedAt = this.now.get();
        final Extraction ex = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of(block), d.url, d.host, d.language, ex);
        final Publisher.Row cur = store.read(c -> Publisher.row(c, id));
        store.write(WriteClass.GROWTH, 0, tx -> publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now.get()));
    }

    private long count(final String sql) throws Exception {
        return KgTestSupport.store(this.runtime).read(c -> KgStore.queryLong(c, sql));
    }

    /** Starts a backup and waits until the backup thread is done; returns its result. */
    private JSONObject backup() throws Exception {
        this.runtime.backup();
        return waitIdle();
    }

    private JSONObject waitIdle() throws Exception {
        final long until = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < until) {
            final JSONObject b = this.runtime.status().getJSONObject("backup");
            if ("idle".equals(b.getString("state")) && b.has("last") && !b.isNull("last")) {
                return b.getJSONObject("last");
            }
            Thread.sleep(20);
        }
        fail("the backup did not end");
        return null;
    }

    private File dir() {
        return new File(this.data, KgPaths.RELATIVE_DIR + "/backup");
    }

    @Test
    public void aBackupIsAVerifiedPortableFileWithChecksumAndMetadata() throws Exception {
        final JSONObject last = backup();
        assertEquals(last.toString(), "created", last.getString("result"));
        final File db = new File(dir(), last.getString("file"));
        assertTrue(db.isFile());
        assertTrue(KgBackup.NAME.matcher(db.getName()).matches());
        final JSONObject meta = KgBackup.readMeta(db);
        assertEquals(KgBackup.SCHEMA, meta.getString("schema"));
        assertEquals(KgBackup.sha256(db), meta.getString("sha256"));
        assertEquals(db.length(), meta.getLong("bytes"));
        assertEquals(KgSchema.CURRENT_VERSION, meta.getInt("kg_schema_version"));
        assertEquals(KgTestSupport.store(this.runtime).epoch(), meta.getString("epoch"));
        assertEquals(count("SELECT count(*) FROM kg_entity WHERE status = 1"), meta.getJSONObject("counts").getLong("entities"));
        // the copy is a complete graph on its own, also at another place
        final File elsewhere = this.tmp.newFile("copied.db");
        Files.copy(db.toPath(), elsewhere.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(meta.getJSONObject("counts").toString(), KgBackup.verify(elsewhere, KgSchema.CURRENT_VERSION).getJSONObject("counts").toString());
        // listed, with no partial file left; the time is in the status and the meta table
        final JSONObject list = this.runtime.backups();
        assertEquals(1, list.getJSONArray("items").length());
        assertEquals("backup", list.getJSONArray("items").getJSONObject(0).getString("kind"));
        assertEquals("DATA/SCOUTRO/knowledge/backup", list.getString("dir"));
        assertFalse(new File(dir(), db.getName() + ".partial").exists());
        assertEquals(Long.toString(this.now.get()), KgTestSupport.store(this.runtime).read(c -> KgStore.getMeta(c, KgSchema.META_LAST_BACKUP_AT)));
        assertNotNull(this.runtime.backupFile(db.getName()));
        assertNull(this.runtime.backupFile("../graph.db"));
        assertNull(this.runtime.backupFile("graph.db"));
    }

    /**
     * A copy goes into another file, not through the WAL: a graph larger than
     * {@code wal.maxBytes} is backed up (it was refused with wal_limit).
     */
    @Test
    public void aGraphLargerThanTheWalLimitIsBackedUp() throws Exception {
        this.runtime.close();
        this.runtime = open(KgConfig.WAL_MAX_BYTES, Long.toString(4L << 20), KgConfig.WAL_CHECKPOINT_BYTES, Long.toString(1L << 20));
        final KgStore store = KgTestSupport.store(this.runtime);
        final String pad = "x".repeat(4000);
        for (int batch = 0; batch < 8; batch++) {
            final int b = batch;
            store.write(WriteClass.SYSTEM, 200L * 4096, tx -> {
                for (int i = 0; i < 200; i++) {
                    KgStore.putMeta(tx, "test.pad." + b + "." + i, pad);
                }
                return null;
            });
        }
        final long logical = store.read(c -> (KgStore.queryLong(c, "PRAGMA page_count") - KgStore.queryLong(c, "PRAGMA freelist_count"))
                * KgStore.queryLong(c, "PRAGMA page_size"));
        assertTrue("the graph is larger than the WAL limit: " + logical, logical > (4L << 20));
        final JSONObject last = backup();
        assertEquals(last.toString(), "created", last.getString("result"));
        assertTrue(last.getLong("bytes") > (4L << 20));
    }

    @Test
    public void aRestorePutsTheBackupBackWithANewEpochAndKeepsThePreviousGraph() throws Exception {
        final String file = backup().getString("file");
        final long entities = count("SELECT count(*) FROM kg_entity WHERE status = 1");
        final String epoch = KgTestSupport.store(this.runtime).epoch();
        this.now.addAndGet(5000L);
        publish("BBBBBBhost02", "https://www.neu.de/", "{\"@type\":\"Organization\",\"name\":\"Neu GmbH\",\"url\":\"https://www.neu.de/\"}");
        assertEquals(entities + 1, count("SELECT count(*) FROM kg_entity WHERE status = 1"));
        final JSONObject r = this.runtime.restore(file);
        assertEquals(KgRuntime.State.RUNNING, this.runtime.state());
        assertEquals(entities, count("SELECT count(*) FROM kg_entity WHERE status = 1"));
        final String restoredEpoch = KgTestSupport.store(this.runtime).epoch();
        assertNotEquals("cursors of the old graph answer epoch_changed", epoch, restoredEpoch);
        assertEquals(restoredEpoch, r.getJSONObject("restored").getString("epoch"));
        // the restored graph reconciles with Solr at its start and needs no integrity check
        assertEquals("1", KgTestSupport.store(this.runtime).read(c -> KgStore.getMeta(c, KgSchema.META_RECONCILE_REQUIRED)));
        assertEquals("not_required", this.runtime.status().getJSONObject("store").getJSONObject("integrity").getString("state"));
        // the previous graph is kept aside, and the backup itself stays
        final String previous = r.getJSONObject("restored").getString("previous");
        assertTrue(previous.endsWith(KgBackup.BEFORE_RESTORE + ".db"));
        assertEquals(entities + 1, KgBackup.verify(new File(dir(), previous), KgSchema.CURRENT_VERSION).getJSONObject("counts").getLong("entities"));
        assertTrue(new File(dir(), file).isFile());
        final JSONArray items = this.runtime.backups().getJSONArray("items");
        assertEquals(2, items.length());
        // the graph keeps working after the restore
        publish("CCCCCChost03", "https://www.drei.de/", "{\"@type\":\"Organization\",\"name\":\"Drei GmbH\",\"url\":\"https://www.drei.de/\"}");
        assertEquals(entities + 1, count("SELECT count(*) FROM kg_entity WHERE status = 1"));
        // a second restore of the same backup works too, and keeps both safety copies until the next backup
        this.now.addAndGet(5000L);
        this.runtime.restore(file);
        assertEquals(entities, count("SELECT count(*) FROM kg_entity WHERE status = 1"));
        this.now.addAndGet(5000L);
        backup();
        int safety = 0;
        for (int i = 0; i < this.runtime.backups().getJSONArray("items").length(); i++) {
            safety += this.runtime.backups().getJSONArray("items").getJSONObject(i).getString("kind").startsWith("before") ? 1 : 0;
        }
        assertEquals("safety copies go once a newer backup exists", 0, safety);
    }

    @Test public void restoreSelectsArchiveSnapshotWithoutMixingNewerObservationsOrCorrections()throws Exception {
        publish("HISTAAhost04","https://archive.example/",
                "{\"@type\":\"Organization\",\"name\":\"Archive GmbH\",\"description\":\"Wir nutzen SAP intern.\"}");
        String file=backup().getString("file");
        assertEquals(1,count("SELECT count(*) FROM kg_observation"));
        now.addAndGet(86_400_000);
        publish("HISTBBhost05","https://new.example/",
                "{\"@type\":\"Organization\",\"name\":\"Later GmbH\",\"description\":\"Wir nutzen Revit intern.\"}");
        KgTestSupport.store(runtime).write(WriteClass.MAINTENANCE,0,c->{try(Statement s=c.createStatement()){
            s.execute("UPDATE kg_observation SET assertion_status='corrected'");}return null;});
        assertEquals(2,count("SELECT count(*) FROM kg_observation"));
        runtime.restore(file);
        assertEquals(1,count("SELECT count(*) FROM kg_observation"));
        assertEquals(0,count("SELECT count(*) FROM kg_observation WHERE assertion_status='corrected'"));
        assertEquals(0,count("SELECT count(*) FROM kg_observation WHERE value LIKE '%revit%'"));
        assertEquals(1,count("SELECT count(*) FROM kg_observation_scope"));
    }

    private void expectInvalid(final String name, final String reason) throws Exception {
        final long entities = count("SELECT count(*) FROM kg_entity WHERE status = 1");
        final String epoch = KgTestSupport.store(this.runtime).epoch();
        try {
            this.runtime.restore(name);
            fail("restored " + name);
        } catch (final KgException e) {
            assertEquals(name, reason, e.reason() != null && !KgException.BACKUP_NOT_FOUND.equals(e.code()) ? e.reason() : e.code());
        }
        assertEquals("nothing changed", KgRuntime.State.RUNNING, this.runtime.state());
        assertEquals(entities, count("SELECT count(*) FROM kg_entity WHERE status = 1"));
        assertEquals(epoch, KgTestSupport.store(this.runtime).epoch());
    }

    @Test
    public void damagedForeignAndUnknownFilesChangeNothing() throws Exception {
        final String file = backup().getString("file");
        final File db = new File(dir(), file);
        expectInvalid("graph-20990101T000000Z.db", KgException.BACKUP_NOT_FOUND);
        expectInvalid("../graph.db", KgException.BACKUP_NOT_FOUND);
        // a flipped byte: the checksum of the metadata file no longer matches
        final File copy = new File(dir(), "graph-20300101T000000Z.db");
        Files.copy(db.toPath(), copy.toPath());
        Files.copy(KgBackup.meta(db).toPath(), KgBackup.meta(copy).toPath());
        try (RandomAccessFile f = new RandomAccessFile(copy, "rw")) {
            f.seek(copy.length() / 2);
            final int b = f.read();
            f.seek(copy.length() / 2);
            f.write(b ^ 0xff);
        }
        expectInvalid(copy.getName(), "checksum");
        // without a metadata file, quick_check and the meta table decide: a damaged page, a foreign database
        Files.delete(KgBackup.meta(copy).toPath());
        try (RandomAccessFile f = new RandomAccessFile(copy, "rw")) {
            f.seek(4096 + 100);
            for (int i = 0; i < 2000; i++) {
                f.write(0x5a);
            }
        }
        try {
            KgBackup.verify(copy, KgSchema.CURRENT_VERSION);
            fail("a damaged page passed the check");
        } catch (final KgException e) {
            assertEquals(KgException.BACKUP_INVALID, e.code());
            assertTrue(e.reason(), List.of("quick_check", "unreadable", "not_a_graph").contains(e.reason()));
        }
        final File foreign = new File(dir(), "graph-20310101T000000Z.db");
        try (Connection c = KgStore.SQLITE.open(foreign); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE other (x INTEGER)");
        }
        expectInvalid(foreign.getName(), "not_a_graph");
        // a newer schema than this version knows
        final File newer = new File(dir(), "graph-20320101T000000Z.db");
        Files.copy(db.toPath(), newer.toPath());
        try (Connection c = KgStore.SQLITE.open(newer); Statement st = c.createStatement()) {
            st.execute("UPDATE kg_meta SET value = '99' WHERE key = 'schema_version'");
        }
        expectInvalid(newer.getName(), "schema_version");
    }

    @Test
    public void aGrowthPauseSkipsTheBackupAndRetentionKeepsTheNewest() throws Exception {
        this.runtime.pause();
        final JSONObject skipped = backup();
        assertEquals("skipped", skipped.getString("result"));
        assertEquals("manual", skipped.getString("reason"));
        assertEquals(0, this.runtime.backups().getJSONArray("items").length());
        this.runtime.resume();
        final String first = backup().getString("file");
        this.now.addAndGet(2000L);
        final JSONObject second = backup();
        assertEquals("created", second.getString("result"));
        assertEquals("[\"" + first + "\"]", second.getJSONArray("removed").toString());
        assertEquals(1, this.runtime.backups().getJSONArray("items").length());
        assertFalse(new File(dir(), first).exists());
        assertFalse(KgBackup.meta(new File(dir(), first)).exists());
    }

    @Test
    public void safetyCopiesDoNotPushOutRegularBackups() throws Exception {
        this.runtime.close();
        this.runtime = open(KgConfig.BACKUP_KEEP, "2");
        final String first = backup().getString("file");
        this.now.addAndGet(2000L);
        this.runtime.restore(first); // the current graph becomes a safety copy, newer than the first backup
        this.now.addAndGet(2000L);
        final JSONObject second = backup();
        assertEquals("created", second.getString("result"));
        assertEquals(1, second.getJSONArray("removed").length());
        assertTrue(second.getJSONArray("removed").getString(0).endsWith(KgBackup.BEFORE_RESTORE + ".db"));
        assertTrue("two regular backups are kept", new File(dir(), first).exists());
        assertEquals(2, this.runtime.backups().getJSONArray("items").length());
    }

    @Test
    public void theScheduleRunsADueBackupFromTheMaintenanceStep() throws Exception {
        this.runtime.close();
        this.runtime = open(KgConfig.BACKUP_INTERVAL_DAYS, "1", KgConfig.BACKUP_KEEP, "2");
        this.runtime.tick();
        Thread.sleep(200);
        assertEquals("not due one day after creation", 0, this.runtime.backups().getJSONArray("items").length());
        this.now.addAndGet(KgConfig.DAY + 60_000L);
        this.runtime.tick(); // measures every 30 s of the clock, then starts the due backup
        assertEquals("scheduled", waitIdle().getString("trigger"));
        assertEquals(1, this.runtime.backups().getJSONArray("items").length());
        final long next = this.runtime.status().getJSONObject("backup").getLong("nextScheduledAt");
        assertTrue(next >= this.now.get() + KgConfig.DAY - 1000L);
    }

    // ------------------------------------------------------------- deletion (6.3)

    private void expectNotFound(final String name) {
        try {
            this.runtime.deleteBackup(name);
            fail("deleted: " + name);
        } catch (final KgException e) {
            assertEquals(name, KgException.BACKUP_NOT_FOUND, e.code());
        }
    }

    /** Package 6.3: a backup is deleted by its name only, with its metadata; the other backups, restore and download stay as they were. */
    @Test
    public void aBackupIsDeletedByItsNameOnlyWithItsMetadata() throws Exception {
        this.runtime.close();
        this.runtime = open(KgConfig.BACKUP_KEEP, "3");
        final String older = backup().getString("file");
        this.now.addAndGet(60_000L);
        final String newer = backup().getString("file");
        assertNotEquals(older, newer);
        final File olderDb = new File(dir(), older);
        assertTrue(olderDb.isFile() && KgBackup.meta(olderDb).isFile());
        final JSONObject before = this.runtime.status().getJSONObject("backup");
        assertEquals(2, before.getInt("files"));
        final JSONObject after = this.runtime.deleteBackup(older);
        assertEquals(older, after.getString("deleted"));
        assertFalse("the backup is gone", olderDb.exists());
        assertFalse("its metadata file too", KgBackup.meta(olderDb).exists());
        assertEquals("the list and the used space follow at once", 1, after.getJSONObject("backup").getInt("files"));
        assertTrue(after.getJSONObject("backup").getLong("bytes") < before.getLong("bytes"));
        final JSONArray items = this.runtime.backups().getJSONArray("items");
        assertEquals(1, items.length());
        assertEquals(newer, items.getJSONObject(0).getString("file"));
        // the graph runs on, its own files untouched; the other backup can still be downloaded and restored
        final KgPaths paths = new KgPaths(this.data);
        assertTrue(paths.db.isFile());
        assertEquals(KgRuntime.State.RUNNING, this.runtime.state());
        assertNotNull(this.runtime.backupFile(newer));
        assertNull(this.runtime.backupFile(older));
        assertTrue(this.runtime.status().getJSONArray("events").toString().contains("backup_deleted"));
        expectNotFound(older);
        this.runtime.restore(newer);
        assertEquals(KgRuntime.State.RUNNING, this.runtime.state());
        assertEquals(1L, count("SELECT count(*) FROM kg_doc"));
    }

    /** Package 6.3: no path, no other file, never the graph's own database, WAL or SHM, nothing outside backup/. */
    @Test
    public void onlyRealBackupFilesOfTheBackupDirectoryCanBeDeleted() throws Exception {
        final String file = backup().getString("file");
        final KgPaths paths = new KgPaths(this.data);
        final long graphBytes = paths.db.length();
        for (final String name : new String[] {"graph.db", "graph.db-wal", "graph.db-shm", "../graph.db", "../../SETTINGS/yacy.conf",
                "/etc/passwd", file + "/../../graph.db", "backup/" + file, "./" + file, file.replace(".db", ".json"), file + ".partial",
                file.replace(".db", ".DB"), "graph-20990101T000000Z.db", "", null}) {
            expectNotFound(name);
        }
        assertTrue("the graph's own database is untouched", paths.db.isFile() && paths.db.length() == graphBytes);
        // a symbolic link named like a backup, pointing to the graph's own database: refused, both stay
        final File link = new File(dir(), "graph-20400101T000000Z.db");
        Files.createSymbolicLink(link.toPath(), paths.db.toPath().toAbsolutePath());
        expectNotFound(link.getName());
        assertTrue(Files.isSymbolicLink(link.toPath()) && paths.db.isFile());
        // a file outside backup/, reached through a linked directory: refused
        final File outside = this.tmp.newFolder("outside");
        final File victim = new File(outside, "graph-20410101T000000Z.db");
        Files.copy(new File(dir(), file).toPath(), victim.toPath());
        final File linkedDir = new File(dir(), "linked");
        Files.createSymbolicLink(linkedDir.toPath(), outside.toPath());
        expectNotFound("linked/" + victim.getName());
        assertTrue(victim.isFile());
        // a directory named like a backup is no backup
        assertTrue(new File(dir(), "graph-20420101T000000Z.db").mkdir());
        expectNotFound("graph-20420101T000000Z.db");
        // the real backup is still there and can be deleted
        assertTrue(new File(dir(), file).isFile());
        this.runtime.deleteBackup(file);
        assertFalse(new File(dir(), file).exists());
        assertTrue(victim.isFile() && paths.db.isFile());
    }

    /** Package 6.3: while a backup, a restore or a rebuild swap holds the slot, nothing is deleted. */
    @Test
    public void nothingIsDeletedWhileTheBackupSlotIsTaken() throws Exception {
        final String file = backup().getString("file");
        final java.lang.reflect.Field f = KgRuntime.class.getDeclaredField("backups");
        f.setAccessible(true);
        final KgBackups slot = (KgBackups) f.get(this.runtime);
        assertTrue(slot.claim());
        try {
            this.runtime.deleteBackup(file);
            fail("deleted while the slot is taken");
        } catch (final KgException e) {
            assertEquals(KgException.OPERATION_RUNNING, e.code());
        } finally {
            slot.release();
        }
        assertTrue(new File(dir(), file).isFile());
        this.runtime.deleteBackup(file);
        assertFalse(new File(dir(), file).exists());
    }
}
