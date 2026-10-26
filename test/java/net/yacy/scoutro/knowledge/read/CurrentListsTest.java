package net.yacy.scoutro.knowledge.read;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.Claim;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.Mention;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * 0.13.0, tests 23 (the lists), 24 and 25 of the plan: lists and counts hold the current entities, the places whose type no
 * source confirms are apart (their review report per collection and model), and a fact shows when its source was read and
 * when it was evaluated. Invented organisations and places.
 */
public class CurrentListsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long SOURCE_READ = Instant.parse("2026-09-30T08:00:00Z").toEpochMilli();
    private static final long TODAY = Instant.parse("2026-10-24T09:00:00Z").toEpochMilli();
    private static final String MODEL = "TEST/model";

    private KgStore store;
    private KgConfig cfg;
    private Terms terms;
    private Publisher publisher;
    private KgReader reader;
    private final AtomicLong now = new AtomicLong(SOURCE_READ);
    private long version = 1000L;

    @Before
    public void open() throws Exception {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(this.cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, this.cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        this.terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            this.terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(this.cfg, this.terms);
        this.reader = new KgReader(this.store, this.cfg, this.now::get);
        // read on 30 September; evaluated by the LLM tier today
        publish("AAAAAAhost01", "https://www.muster-pflege.de/", "ca", "Muster Pflege gGmbH");
        publish("BBBBBBhost02", "https://www.beispiel-pflege.de/", "ca", "Beispiel Pflege GmbH");
        this.now.set(TODAY);
        llm("AAAAAAhost01", "https://www.muster-pflege.de/", place("Haus Lindenhof", false), place("Lindengarten", true));
    }

    @After
    public void close() {
        this.store.close();
    }

    private Publisher.Doc doc(final String id, final String url, final String collection) throws Exception {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = net.yacy.cora.document.id.DigestURL.hosthash(d.host, 443);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of(collection);
        d.accessCollections = d.collections;
        d.solrVersion = ++this.version;
        d.loadedAt = SOURCE_READ;
        return d;
    }

    private void publish(final String id, final String url, final String collection, final String name) throws Exception {
        final Publisher.Doc d = doc(id, url, collection);
        final Extraction ex = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of("{\"@type\":\"Organization\",\"name\":\"" + name + "\",\"url\":\"" + url + "\"}"), d.url,
                d.host, d.language, ex);
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, id));
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now.get()));
    }

    /** A place of the LLM tier: its name, and the mark of an unconfirmed type if no place stands in its context. */
    private static Mention[] place(final String name, final boolean unconfirmed) {
        final Mention m = new Mention("llm:facility:" + name.toLowerCase().replace(' ', '-'), Vocabulary.FACILITY, LlmExtractor.TIER);
        m.name = name;
        return new Mention[] {m, unconfirmed ? m : null};
    }

    private void llm(final String id, final String url, final Mention[]... places) throws Exception {
        final Publisher.Doc d = doc(id, url, "ca");
        final Extraction ex = new Extraction(50);
        for (final Mention[] p : places) {
            ex.add(p[0]);
            ex.add(new Claim(p[0].ref, Vocabulary.NAME, null, p[0].name, LlmExtractor.TIER, Claim.KIND_LLM, false, "text:0+" + p[0].name.length(),
                    p[0].name, null));
            if (p[1] != null) {
                ex.add(new Claim(p[0].ref, Vocabulary.TYPE_UNCONFIRMED, null, Vocabulary.FACILITY, LlmExtractor.TIER, Claim.KIND_LLM, true,
                        "text:0+" + p[0].name.length(), p[0].name, null));
            }
        }
        ex.ranTier(LlmExtractor.TIER);
        this.store.write(WriteClass.GROWTH, 0, tx -> {
            KgStore.queryLong(tx, "SELECT 1"); // the document is to do for the tier
            try (java.sql.Statement st = tx.createStatement()) {
                st.executeUpdate("UPDATE kg_doc SET llm_status = NULL WHERE doc_id = '" + id + "'");
            }
            return this.publisher.applyLlm(tx, d, new byte[16], ex, this.terms.llmExtractor(tx, MODEL, LlmExtractor.PROMPT_HASH), this.now.get());
        });
    }

    private Viewer all() throws Exception {
        return this.reader.viewer(null);
    }

    private JSONObject list(final String type, final boolean stale, final boolean unconfirmed, final String review) throws Exception {
        final KgReader.EntityQuery q = new KgReader.EntityQuery();
        q.type = type;
        q.includeStale = stale;
        q.includeUnconfirmed = unconfirmed;
        q.review = review;
        return this.reader.entities(q, all());
    }

    private static String names(final JSONObject page) throws Exception {
        final List<String> out = new java.util.ArrayList<>();
        final JSONArray items = page.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            out.add(items.getJSONObject(i).optString("name"));
        }
        java.util.Collections.sort(out);
        return out.toString();
    }

    /** Test 24: an entity whose only source left the index is in no list and no count by default; include_stale shows it. */
    @Test
    public void anEntityWithoutACurrentSourceIsListedOnlyOnRequest() throws Exception {
        assertEquals(2L, list(Vocabulary.ORGANIZATION, false, false, null).getLong("total"));
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> this.publisher.setState(tx,
                List.of(KgStore.queryLong(tx, "SELECT doc_rowid FROM kg_doc WHERE doc_id = 'BBBBBBhost02'")), 3, this.now.get()));
        assertEquals("[Muster Pflege gGmbH]", names(list(Vocabulary.ORGANIZATION, false, false, null)));
        assertEquals(1L, list(Vocabulary.ORGANIZATION, false, false, null).getLong("total"));
        assertEquals("[Beispiel Pflege GmbH, Muster Pflege gGmbH]", names(list(Vocabulary.ORGANIZATION, true, false, null)));
        assertEquals("the host list", 0L, this.reader.hostEntities("www.beispiel-pflege.de", 0, 25, all()).getLong("total"));
        final KgReader.EntityQuery host = new KgReader.EntityQuery();
        host.host = "www.beispiel-pflege.de";
        host.includeStale = true;
        assertEquals(1L, this.reader.entities(host, all()).getLong("total"));
        final KgReader.EntityQuery stale = new KgReader.EntityQuery();
        stale.quality = "stale";
        assertEquals("a filter by quality stale asks for them", "[Beispiel Pflege GmbH]", names(this.reader.entities(stale, all())));
    }

    /** Test 23: a place whose type no source confirms is not counted as a facility and is listed in the review report. */
    @Test
    public void anUnconfirmedPlaceIsApartAndInTheReview() throws Exception {
        assertEquals("[Haus Lindenhof]", names(list(Vocabulary.FACILITY, false, false, null)));
        assertEquals("not counted as a facility", 1L, list(Vocabulary.FACILITY, false, false, null).getLong("total"));
        assertEquals("[Haus Lindenhof, Lindengarten]", names(list(Vocabulary.FACILITY, false, true, null)));
        final JSONObject review = list(null, false, false, Vocabulary.TYPE_UNCONFIRMED);
        assertEquals("[Lindengarten]", names(review));
        assertEquals(1, review.getJSONArray("review").length());
        final JSONObject row = review.getJSONArray("review").getJSONObject(0);
        assertEquals("ca|" + MODEL + "|1", row.getString("collection") + "|" + row.getString("model") + "|" + row.getLong("entities"));
        final KgReader.EntityQuery q = new KgReader.EntityQuery();
        q.review = Vocabulary.TYPE_UNCONFIRMED;
        assertTrue("agents see no model", this.reader.forAgents().entities(q, all()).getJSONArray("review").getJSONObject(0).isNull("model"));
        // the rules of another page name the same place (its type is theirs): the type is confirmed
        publish("CCCCCChost01", "https://www.muster-pflege.de/haeuser", "ca", "Muster Pflege gGmbH");
        this.store.write(WriteClass.GROWTH, 0, tx -> {
            try (java.sql.Statement st = tx.createStatement()) {
                st.executeUpdate("INSERT INTO kg_evidence (stmt_rowid, doc_rowid, tier, ext_id, kind, certainty, locator, excerpt, observed_at)"
                        + " SELECT s.stmt_rowid, (SELECT doc_rowid FROM kg_doc WHERE doc_id = 'CCCCCChost01'), 2, (SELECT min(ext_id) FROM kg_extractor"
                        + " WHERE tier = 2), 3, 1, 'text:0+12', 'Lindengarten', " + TODAY + " FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                        + " WHERE v.name = 'name' AND s.obj_val = 'Lindengarten'");
            }
            return null;
        });
        assertEquals("[Haus Lindenhof, Lindengarten]", names(list(Vocabulary.FACILITY, false, false, null)));
        assertEquals(0L, list(null, false, false, Vocabulary.TYPE_UNCONFIRMED).getLong("total"));
    }

    /** Test 25: a statement extracted today from a page read on 30 September shows both times, with extractor and model. */
    @Test
    public void aFactShowsWhenItsSourceWasReadAndWhenItWasEvaluated() throws Exception {
        final JSONObject page = list(Vocabulary.FACILITY, false, false, null);
        final JSONObject e = page.getJSONArray("items").getJSONObject(0);
        assertEquals("Haus Lindenhof", e.getString("name"));
        assertTrue(e.toString(), e.getString("source_loaded_at").startsWith("2026-09-30"));
        assertTrue(e.toString(), e.getString("extracted_at").startsWith("2026-10-24"));
        assertEquals("the contract's field is the source's time", e.getString("source_loaded_at"), e.getString("last_confirmed"));
        assertEquals(LlmExtractor.NAME + "/" + LlmExtractor.VERSION, e.getString("extractor"));
        assertEquals(MODEL, e.getString("model"));
        final JSONObject facts = this.reader.entityStatements(e.getString("id"), null, false, false, 0, 10, all());
        final JSONObject name = facts.getJSONArray("items").getJSONObject(0);
        assertTrue(name.toString(), name.getString("source_loaded_at").startsWith("2026-09-30"));
        assertTrue(name.toString(), name.getString("extracted_at").startsWith("2026-10-24"));
        final JSONObject business = new BusinessView(this.reader).entity(e.getString("id"), all(), all(), false);
        assertTrue(business.toString(), business.toString().contains("\"extracted_at\":\"2026-10-24"));
        // the organisation's facts were read by the rules on the day of the crawl
        final JSONObject org = list(Vocabulary.ORGANIZATION, false, false, null).getJSONArray("items").getJSONObject(0);
        assertTrue(org.toString(), org.getString("extracted_at").startsWith("2026-09-30") && org.getString("extractor").startsWith("jsonld/"));
        final KgReader.EntityQuery facilities = new KgReader.EntityQuery();
        facilities.type = Vocabulary.FACILITY;
        assertTrue("agents see no model", this.reader.forAgents().entities(facilities, all()).getJSONArray("items").getJSONObject(0).isNull("model"));
    }
}
