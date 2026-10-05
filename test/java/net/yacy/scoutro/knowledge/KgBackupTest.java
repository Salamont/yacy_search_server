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
}
