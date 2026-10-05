package net.yacy.scoutro.knowledge.read;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * Export pages, the streamed export and the change feed with records
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 8.3), with the fixture of
 * {@link KgReaderTest}: one organisation in collections ca and cb, one only in
 * cb. A viewer of ca never gets anything only cb's documents carry, neither in
 * an export page, nor in the stream, nor in a change record.
 */
public class KgExportTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;
    private KgConfig cfg;
    private Publisher publisher;
    private KgReader reader;
    private KgExport export;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private long version = 1000L;

    /** Values only cb's documents carry. */
    private static final String[] ONLY_IN_CB = {"Geheime Holding", "DE123456789", "+49409999999", "Nur Bee", "nur-bee", "\"cb\"",
            "BBBBBBhost01", "CCCCCChost02", "impressum"};

    @Before
    public void open() throws Exception {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(this.cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, this.cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(this.cfg, terms);
        this.reader = new KgReader(this.store, this.cfg, this.now::get);
        this.export = new KgExport(this.reader);
        publish("AAAAAAhost01", "https://www.muster.de/", List.of("ca"), KgReaderTest.ORG_A);
        publish("BBBBBBhost01", "https://www.muster.de/impressum", List.of("cb"), KgReaderTest.ORG_B);
        publish("CCCCCChost02", "https://www.nur-bee.de/", List.of("cb"), KgReaderTest.ONLY_B);
    }

    @After
    public void close() {
        this.store.close();
    }

    private void publish(final String id, final String url, final List<String> collections, final String block) throws Exception {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = net.yacy.cora.document.id.DigestURL.hosthash(d.host, 443);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.inputHash[0] = (byte) this.version;
        d.collections = collections;
        d.solrVersion = ++this.version;
        d.loadedAt = this.now.get();
        final Extraction ex = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of(block), d.url, d.host, d.language, ex);
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, id));
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now.get()));
    }

    private Viewer viewer(final String... collections) throws Exception {
        return this.reader.viewer(collections.length == 0 ? null : List.of(collections));
    }

    /** All records of a complete export in pages of {@code limit}. */
    private List<JSONObject> all(final Viewer v, final int limit, final boolean evidence) throws Exception {
        final List<JSONObject> out = new ArrayList<>();
        String cursor = null;
        for (int pages = 0; pages < 100; pages++) {
            final JSONObject page = this.export.page(cursor, limit, evidence, v);
            assertEquals("scoutro.kg.v1", page.getString("schema"));
            final JSONArray items = page.getJSONArray("items");
            assertTrue("page size", items.length() <= limit);
            for (int i = 0; i < items.length(); i++) {
                out.add(items.getJSONObject(i));
            }
            if (page.getBoolean("complete")) {
                assertTrue(page.isNull("next"));
                return out;
            }
            cursor = page.getString("next");
        }
        fail("the export did not end");
        return out;
    }

    private long count(final String sql) throws Exception {
        return this.store.read(c -> KgStore.queryLong(c, sql));
    }

    @Test
    public void exportPagesCoverEveryRecordOnceInOrder() throws Exception {
        final List<JSONObject> records = all(viewer(), 2, false);
        final Set<String> ids = new HashSet<>();
        boolean statements = false;
        int entities = 0;
        int stmts = 0;
        for (final JSONObject r : records) {
            assertTrue("no record twice: " + r.getString("id"), ids.add(r.getString("id")));
            if ("entity".equals(r.getString("record"))) {
                assertFalse("entities before statements", statements);
                entities++;
            } else {
                assertEquals("statement", r.getString("record"));
                statements = true;
                stmts++;
            }
        }
        assertEquals(count("SELECT count(*) FROM kg_entity WHERE status = 1"), entities);
        assertEquals(count("SELECT count(*) FROM kg_statement"), stmts);
        // the same records in one page
        assertEquals(records.size(), all(viewer(), KgExport.MAX_LIMIT, false).size());
        // the first page names the change cursor at its start
        final JSONObject first = this.export.page(null, 1, false, viewer());
        assertEquals(first.getString("epoch") + ":" + first.getLong("as_of_seq"), first.getString("next_changes"));
        assertEquals(count("SELECT coalesce(max(seq), 0) FROM kg_change"), first.getLong("as_of_seq"));
    }

    @Test
    public void aCollectionExportHoldsNothingOfAnotherCollection() throws Exception {
        final List<JSONObject> records = all(viewer("ca"), 3, true);
        final String text = new JSONArray(records).toString();
        for (final String secret : ONLY_IN_CB) {
            assertFalse("ca export reveals " + secret + ": " + text, text.contains(secret));
        }
        assertTrue(text.contains("Muster Pflege gGmbH"));
        assertTrue(text.contains("+49301234567"));
        boolean evidence = false;
        for (final JSONObject r : records) {
            if ("statement".equals(r.getString("record"))) {
                final JSONArray ev = r.getJSONArray("evidence");
                assertTrue(ev.length() >= 1);
                for (int i = 0; i < ev.length(); i++) {
                    assertEquals("[\"ca\"]", ev.getJSONObject(i).getJSONArray("collections").toString());
                    evidence = true;
                }
            }
        }
        assertTrue(evidence);
        // cb sees its own values, an unknown collection sees nothing
        final String b = new JSONArray(all(viewer("cb"), 50, true)).toString();
        assertTrue(b.contains("Geheime Holding") && b.contains("Nur Bee GmbH") && !b.contains("+49301234567"));
        assertEquals(0, all(viewer("unknown"), 50, true).size());
    }

    private void expectCode(final String code, final String cursor) {
        try {
            this.export.page(cursor, 10, false, Viewer.ALL);
            fail("cursor " + cursor + " accepted");
        } catch (final KgException e) {
            assertEquals(cursor, code, e.code());
        }
    }

    @Test
    public void exportCursorsAreChecked() throws Exception {
        final JSONObject first = this.export.page(null, 1, false, Viewer.ALL);
        final String next = first.getString("next");
        assertNotNull(this.export.page(next, 1, false, Viewer.ALL));
        final String epoch = first.getString("epoch");
        final long seq = first.getLong("as_of_seq");
        expectCode(KgException.INVALID_CURSOR, "nonsense");
        expectCode(KgException.INVALID_CURSOR, epoch + ":" + seq + ":x1");
        expectCode(KgException.INVALID_CURSOR, epoch + ":" + (seq + 100) + ":e0");
        expectCode(KgException.EPOCH_CHANGED, "0123456789abcdef:" + seq + ":e0");
        // retention removed changes after the start of the export: it cannot be completed
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> {
            KgStore.putMeta(tx, KgSchema.META_CHANGES_MIN_SEQ, Long.toString(seq + 2));
            return null;
        });
        expectCode(KgException.CURSOR_EXPIRED, next);
    }

    @Test
    public void theChangeFeedFromTheExportCarriesLaterChangesWithTheViewersRecords() throws Exception {
        final String changes = this.export.page(null, 1, false, viewer("ca")).getString("next_changes");
        // a new page in ca, and the shared organisation gets a new value in cb
        publish("DDDDDDhost03", "https://www.neu.de/", List.of("ca"),
                "{\"@type\":\"Organization\",\"name\":\"Neu Pflege GmbH\",\"url\":\"https://www.neu.de/\"}");
        publish("BBBBBBhost01", "https://www.muster.de/impressum", List.of("cb"), KgReaderTest.ORG_B.replace("040 9999999", "040 1111111"));
        final JSONObject a = this.export.changes(changes, 100, true, viewer("ca"));
        final String text = a.toString();
        assertTrue(text, text.contains("Neu Pflege GmbH"));
        for (final String secret : new String[] {"+49401111111", "+49409999999", "Geheime Holding", "\"cb\""}) {
            assertFalse("ca changes reveal " + secret + ": " + text, text.contains(secret));
        }
        final JSONArray items = a.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            final JSONObject it = items.getJSONObject(i);
            if ("upsert".equals(it.getString("op"))) {
                assertFalse("an upsert carries its record", it.isNull("record"));
            }
        }
        // cb gets its new phone number, but not the page that only ca holds
        final String b = this.export.changes(changes, 100, true, viewer("cb")).toString();
        assertTrue(b, b.contains("+49401111111") && !b.contains("Neu Pflege GmbH"));
        // without expand: no records
        assertFalse(this.export.changes(changes, 100, false, viewer("ca")).toString().contains("\"record\""));
        // the whole feed from its start: the shared organisation's record is computed over ca's evidence only
        final String whole = this.export.changes(null, 100, true, viewer("ca")).toString();
        assertTrue(whole, whole.contains("Muster Pflege gGmbH") && whole.contains("\"record\""));
        for (final String secret : ONLY_IN_CB) {
            assertFalse("ca's whole feed reveals " + secret + ": " + whole, whole.contains(secret));
        }
    }

    @Test
    public void aViewerGetsADeleteWhenAnObjectLeavesItsCollections() throws Exception {
        final String changes = this.export.page(null, 1, false, viewer("ca")).getString("next_changes");
        // the page of ca moves to cb: the facts only it carried leave ca
        publish("AAAAAAhost01", "https://www.muster.de/", List.of("cb"), KgReaderTest.ORG_A);
        final JSONArray items = this.export.changes(changes, 100, true, viewer("ca")).getJSONArray("items");
        boolean deleted = false;
        for (int i = 0; i < items.length(); i++) {
            final JSONObject it = items.getJSONObject(i);
            assertEquals("ca may only learn that its objects are gone: " + it, "delete", it.getString("op"));
            assertTrue(it.isNull("record") || !it.has("record"));
            deleted = true;
        }
        assertTrue(deleted);
        assertEquals(0, all(viewer("ca"), 50, false).size());
    }

    private static final class Collect implements KgExport.Sink {
        JSONObject header;
        final List<JSONObject> records = new ArrayList<>();
        JSONObject trailer;
        Runnable onRecord = () -> {};

        @Override public void begin(final JSONObject h) { this.header = h; }
        @Override public void record(final JSONObject r) { this.records.add(r); this.onRecord.run(); }
        @Override public void end(final JSONObject t) { this.trailer = t; }
    }

    @Test
    public void theStreamWritesHeaderRecordsAndATrailer() throws Exception {
        final Collect sink = new Collect();
        this.export.stream(viewer("ca"), true, "ca", 1, sink);
        assertEquals("header", sink.header.getString("record"));
        assertEquals("ca", sink.header.getString("collection"));
        assertEquals(sink.header.getString("epoch") + ":" + sink.header.getLong("as_of_seq"), sink.header.getString("next_changes"));
        assertEquals("trailer", sink.trailer.getString("record"));
        assertTrue(sink.trailer.getBoolean("complete"));
        final JSONObject counts = sink.trailer.getJSONObject("counts");
        assertEquals(sink.records.size(), counts.getLong("entities") + counts.getLong("statements"));
        assertEquals(all(viewer("ca"), 50, true).toString(), sink.records.toString());
        for (final String secret : ONLY_IN_CB) {
            assertFalse(sink.records.toString().contains(secret));
        }
    }

    @Test
    public void aFailureAfterTheHeaderEndsTheStreamIncomplete() throws Exception {
        final Collect sink = new Collect();
        sink.onRecord = this.store::close; // the next page cannot be read
        this.export.stream(Viewer.ALL, false, null, 1, sink);
        assertEquals(1, sink.records.size());
        assertFalse(sink.trailer.getBoolean("complete"));
        assertEquals(KgException.STORE_CLOSED, sink.trailer.getString("error"));
    }

    @Test
    public void aFailureBeforeTheHeaderIsThrown() throws Exception {
        this.store.close();
        final Collect sink = new Collect();
        try {
            this.export.stream(Viewer.ALL, false, null, 10, sink);
            fail("no export from a closed store");
        } catch (final KgException e) {
            assertEquals(KgException.STORE_CLOSED, e.code());
        }
        assertEquals(null, sink.header);
    }
}
