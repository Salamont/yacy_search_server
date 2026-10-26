package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.Claim;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.Mention;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * 0.13.0, tests 22, 23 (the replay part) and 26 of the plan: a version whose change is the validation only replays the stored
 * answers from their evidence without a model request; a replay twice and a cached answer bring no artifact back; a version
 * outside {@link LlmExtractor#REPLAYS} examines the documents again. The page is invented: a menu line naming an activity
 * ("Logistikbau", the structure of the finding), a facility with its place and a proper name without one.
 */
public class LlmReplayTest extends LlmFixture {

    static final String PAGE = "REPLAYhost01";
    static final String URL = "https://www.muster-pflege.de/einrichtungen";
    static final String MENU = "Startseite | Leistungen | Logistikbau | Hochbau | Kontakt";
    static final String PAGE_TEXT = MENU + "\nDie Muster Pflege gGmbH betreibt das Haus Lindenhof in Berlin. Das Haus Lindenhof bietet"
            + " Tagespflege an.\nDer Lindengarten ist bei allen Gästen beliebt.\n";

    /** What the model answers about the page: a place, its service and a name without a place (no menu item). */
    static String answer(final String user) {
        if (!user.contains("Haus Lindenhof")) {
            return "{\"entities\":[],\"claims\":[]}";
        }
        return "{\"entities\":["
                + "{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Lindenhof\",\"kind\":\"nursinghome\",\"quote\":\"betreibt das Haus Lindenhof in Berlin\"},"
                + "{\"id\":\"e2\",\"type\":\"service\",\"name\":\"Tagespflege\",\"quote\":\"Das Haus Lindenhof bietet Tagespflege an\"},"
                + "{\"id\":\"e3\",\"type\":\"facility\",\"name\":\"Lindengarten\",\"quote\":\"Der Lindengarten ist bei allen Gästen beliebt\"}],"
                + "\"claims\":["
                + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\"Die Muster Pflege gGmbH betreibt das Haus Lindenhof\"},"
                + "{\"subject\":\"e1\",\"predicate\":\"offers\",\"object\":\"e2\",\"quote\":\"Das Haus Lindenhof bietet Tagespflege an\"}]}";
    }

    private void readPage() throws Exception {
        this.model.answer = LlmReplayTest::answer;
        add(PAGE, URL, "c1", LD, PAGE_TEXT);
        settleSync();
        settleLlm();
    }

    /** Active entities with this name (any type, any tier). */
    private long named(final String name) {
        return count("SELECT count(DISTINCT e.ent_rowid) FROM kg_entity e JOIN kg_statement s ON s.subj = e.ent_rowid JOIN kg_vocab v"
                + " ON v.term_id = s.pred WHERE e.status = 1 AND v.name = 'name' AND s.obj_val = '" + name + "'");
    }

    /** Statements of this predicate about the entity with this name. */
    private long about(final String name, final String predicate) {
        return count("SELECT count(*) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = '" + predicate + "'"
                + " AND EXISTS (SELECT 1 FROM kg_evidence e WHERE e.stmt_rowid = s.stmt_rowid) AND s.subj IN (SELECT n.subj FROM kg_statement n"
                + " JOIN kg_vocab nv ON nv.term_id = n.pred WHERE nv.name = 'name' AND n.obj_val = '" + name + "')");
    }

    /** The page's tier-3 evidence: predicate, subject, object, locator and excerpt, sorted. */
    private List<String> tier3() {
        return strings("SELECT v.name || '|' || (SELECT n.obj_val FROM kg_statement n JOIN kg_vocab nv ON nv.term_id = n.pred WHERE n.subj = s.subj"
                + " AND nv.name = 'name' ORDER BY n.obj_val LIMIT 1) || '|' || coalesce(s.obj_val, (SELECT n.obj_val FROM kg_statement n JOIN kg_vocab nv"
                + " ON nv.term_id = n.pred WHERE n.subj = s.obj_ent AND nv.name = 'name' ORDER BY n.obj_val LIMIT 1), '') || '|' || coalesce(e.locator, '')"
                + " || '|' || coalesce(e.excerpt, '') FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v"
                + " ON v.term_id = s.pred JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE d.doc_id = '" + PAGE + "' AND e.tier = 3 ORDER BY 1");
    }

    /**
     * The graph as 0.12 left it: the tier's evidence under version 4 (same model and prompt), without the marks of unconfirmed
     * types, and with the facility the former validator accepted from the menu.
     */
    private void asVersion4(final boolean withArtifact) throws Exception {
        exec("INSERT OR IGNORE INTO kg_extractor (tier, name, version, model, prompt_hash) SELECT tier, name, '4', model, prompt_hash"
                + " FROM kg_extractor WHERE tier = 3 AND version = '" + LlmExtractor.VERSION + "'");
        exec("UPDATE kg_evidence SET ext_id = (SELECT x4.ext_id FROM kg_extractor x4 JOIN kg_extractor x5 ON x5.ext_id = kg_evidence.ext_id"
                + " WHERE x4.tier = 3 AND x4.version = '4' AND x4.model = x5.model AND x4.prompt_hash = x5.prompt_hash) WHERE tier = 3");
        exec("DELETE FROM kg_evidence WHERE stmt_rowid IN (SELECT s.stmt_rowid FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE v.name = '" + Vocabulary.TYPE_UNCONFIRMED + "')");
        if (withArtifact) {
            menuFacility();
        }
        final String extractor = strings("SELECT value FROM kg_meta WHERE key = '" + KgSchema.META_LLM_EXTRACTOR + "'").get(0);
        exec("UPDATE kg_meta SET value = '" + extractor.replace("/" + LlmExtractor.VERSION + "/", "/4/") + "' WHERE key = '"
                + KgSchema.META_LLM_EXTRACTOR + "'");
        exec("DELETE FROM kg_meta WHERE key = '" + KgSchema.META_LLM_VALIDATION + "'");
    }

    /** A facility "Logistikbau" quoted from the menu, published as the former version did (an addition to the page's reading). */
    private void menuFacility() throws Exception {
        final String reason = strings("SELECT coalesce(llm_reason, '') FROM kg_doc WHERE doc_id = '" + PAGE + "'").get(0);
        exec("UPDATE kg_doc SET llm_reason = 'more_text' WHERE doc_id = '" + PAGE + "'");
        this.store.write(WriteClass.GROWTH, 1 << 20, tx -> {
            final Terms terms = new Terms();
            terms.seed(tx);
            final Publisher publisher = new Publisher(this.cfg, terms);
            final Publisher.Doc doc = new Publisher.Doc();
            doc.docId = PAGE;
            doc.url = URL;
            doc.host = "www.muster-pflege.de";
            doc.hostId = PAGE.substring(6);
            doc.language = "de";
            doc.accessCollections = List.of("c1");
            final byte[] input;
            try (java.sql.PreparedStatement ps = tx.prepareStatement("SELECT coalesce(content_hash, input_hash), loaded_at FROM kg_doc WHERE doc_id = ?")) {
                ps.setString(1, PAGE);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    input = rs.getBytes(1);
                    doc.loadedAt = rs.getLong(2);
                }
            }
            doc.contentHash = input;
            final int ext = (int) KgStore.queryLong(tx, "SELECT ext_id FROM kg_extractor WHERE tier = 3 AND version = '4' LIMIT 1");
            final Extraction ex = new Extraction(100);
            final Mention m = ex.add(new Mention("llm:facility:logistikbau", Vocabulary.FACILITY, 3));
            m.name = "Logistikbau";
            ex.add(new Claim(m.ref, Vocabulary.NAME, null, "Logistikbau", 3, Claim.KIND_LLM, false, "text:0+" + MENU.length(), MENU, null));
            ex.ranTier(3);
            final Publisher.Result r = publisher.applyLlmMore(tx, doc, input, ex, ext, reason.isEmpty() ? null : reason, this.clock.get());
            assertEquals(Publisher.Outcome.PUBLISHED, r.outcome);
            return null;
        });
        assertEquals(1L, named("Logistikbau"));
    }

    private JSONObject validation() throws Exception {
        this.clock.addAndGet(LlmService.COUNTS_MILLIS + 1L);
        return this.llm.status().getJSONObject("validation");
    }

    /** Steps until the replay is done (the meta records the version). */
    private void settleReplay() {
        settleLlm(() -> LlmExtractor.VERSION.equals(meta(KgSchema.META_LLM_VALIDATION)) && count("SELECT count(*) FROM kg_llm_work") == 0L);
    }

    private String meta(final String key) {
        final List<String> v = strings("SELECT value FROM kg_meta WHERE key = '" + key + "'");
        return v.isEmpty() ? null : v.get(0);
    }

    @Test
    public void aValidationVersionReplaysTheStoredAnswersWithoutARequest() throws Exception {
        readPage();
        assertEquals("one chunk, one request", 1, this.model.calls.get());
        assertEquals("a proper name without a place is kept and marked", 1L, about("Lindengarten", Vocabulary.TYPE_UNCONFIRMED));
        final String mark = llmStatus(PAGE);
        final List<String> fresh = tier3();
        asVersion4(true);
        assertEquals(0L, about("Lindengarten", Vocabulary.TYPE_UNCONFIRMED));
        restart();
        settleReplay();
        // test 26: no model request; the documents were not made to do again
        assertEquals("no model request", 1, this.model.calls.get());
        assertEquals("the page keeps its mark", mark, llmStatus(PAGE));
        // test 23: the menu facility is gone with its only statement, the place and its service stay, the unclear name is marked
        assertEquals("the menu item is no facility", 0L, named("Logistikbau"));
        assertEquals(1L, about("Haus Lindenhof", Vocabulary.OPERATES) + about("Muster Pflege gGmbH", Vocabulary.OPERATES));
        assertEquals(1L, about("Haus Lindenhof", Vocabulary.OFFERS));
        assertEquals(1L, about("Lindengarten", Vocabulary.TYPE_UNCONFIRMED));
        assertEquals("the replay publishes what a reading of the page publishes now", fresh, tier3());
        // the provenance stays: the model and the prompt of the answer, under the new version
        assertEquals(List.of(LlmExtractor.VERSION + "|" + this.model.name), strings("SELECT DISTINCT x.version || '|' || x.model FROM kg_evidence e"
                + " JOIN kg_extractor x ON x.ext_id = e.ext_id WHERE e.tier = 3"));
        final JSONObject v = validation();
        assertEquals("idle", v.getString("state"));
        assertEquals(1L, v.getLong("replayed"));
        assertEquals(1L, v.getLong("placesRefused"));
        assertEquals(1L, v.getLong("typeUnconfirmed"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'llm_validation_queued'"));
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'llm_validation_done'"));
        assertEquals("nothing is cached by a replay", 1L, count("SELECT count(*) FROM kg_extraction"));
    }

    /** Test 22: a second replay changes nothing, and a cached answer holding the artifact brings it back neither. */
    @Test
    public void aReplayTwiceAndACachedAnswerBringNoArtifactBack() throws Exception {
        readPage();
        asVersion4(true);
        restart();
        settleReplay();
        final List<String> once = tier3();
        asVersion4(false);
        restart();
        settleReplay();
        assertEquals("idempotent", once, tier3());
        assertEquals(0L, named("Logistikbau"));
        // an old cache entry: the accepted answer with the menu facility in it
        final List<byte[]> keys = this.store.read(c -> {
            final List<byte[]> out = new ArrayList<>();
            try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery("SELECT cache_key FROM kg_extraction")) {
                while (rs.next()) {
                    out.add(rs.getBytes(1));
                }
            }
            return out;
        });
        assertEquals(1, keys.size());
        this.store.write(WriteClass.MAINTENANCE, 1 << 20, tx -> {
            final ExtractionCache.Entry e = ExtractionCache.get(tx, keys.get(0));
            final JSONArray entities = e.value.optJSONArray("entities");
            entities.put(KgJson.obj("id", "e9", "type", "facility", "name", "Logistikbau", "quote", "Leistungen | Logistikbau | Hochbau", "at", 13));
            final int ext = (int) KgStore.queryLong(tx, "SELECT ext_id FROM kg_extraction LIMIT 1");
            ExtractionCache.put(tx, keys.get(0), ext, e.status, e.value, this.clock.get());
            return null;
        });
        final int calls = this.model.calls.get();
        exec("UPDATE kg_doc SET llm_status = NULL, llm_reason = NULL WHERE doc_id = '" + PAGE + "'");
        exec("DELETE FROM kg_meta WHERE key GLOB 'llm_lane*' OR key GLOB 'llm_scan*'");
        settleLlm();
        assertEquals("answered from the cache", calls, this.model.calls.get());
        assertTrue("the cache holds the artifact", this.store.read(c -> ExtractionCache.get(c, keys.get(0))).value.toString().contains("Logistikbau"));
        assertEquals("a cache hit is validated", 0L, named("Logistikbau"));
        assertEquals(once, tier3());
    }

    /** Test 26: a version outside the replayed ones examines the documents again (with model requests). */
    @Test
    public void aVersionOutsideTheReplaysExaminesAgain() throws Exception {
        readPage();
        final int calls = this.model.calls.get();
        final String extractor = meta(KgSchema.META_LLM_EXTRACTOR);
        exec("UPDATE kg_meta SET value = '" + extractor.replace("/" + LlmExtractor.VERSION + "/", "/3/") + "' WHERE key = '"
                + KgSchema.META_LLM_EXTRACTOR + "'");
        exec("DELETE FROM kg_extraction"); // the answers of another version are not in the cache
        restart();
        settleLlm();
        assertEquals(1L, count("SELECT count(*) FROM kg_event WHERE code = 'llm_extractor_changed'"));
        assertTrue("asked again", this.model.calls.get() > calls);
        assertFalse("no replay", LlmService.VALIDATION_PENDING.equals(meta(KgSchema.META_LLM_VALIDATION)));
        assertEquals(0L, count("SELECT count(*) FROM kg_event WHERE code = 'llm_validation_queued'"));
    }

    /** The quote of the evidence is read back from the page where it was clipped or a name was redacted, never invented. */
    @Test
    public void quotesAreReadBackOnlyWhereTheTextAgrees() {
        final String raw = "Kontakt: Frau Erika Muster leitet das Haus Lindenhof in Berlin seit vielen Jahren.";
        final int at = raw.indexOf("Frau");
        final String quote = "Frau Erika Muster leitet das Haus Lindenhof in Berlin";
        assertEquals(quote, LlmReplay.quote(raw, new int[] {at, quote.length()}, "Frau […] leitet das Haus Lindenhof in Berlin", null));
        assertEquals(quote, LlmReplay.quote(raw, new int[] {at, quote.length()}, "Frau Erika Muster leitet das Haus…", null));
        assertEquals("another text at the position: the excerpt", "Das Haus Am See",
                LlmReplay.quote(raw, new int[] {at, quote.length()}, "Das Haus Am See", null));
        assertEquals(null, LlmReplay.position("title:0+12"));
        assertEquals(null, LlmReplay.position("derived:category:x"));
    }
}
