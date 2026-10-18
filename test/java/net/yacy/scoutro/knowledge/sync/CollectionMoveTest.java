package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;

/**
 * WP-D: what the knowledge graph does when a page only changes its collections (a collection decision or reassign).
 * c1 and c2 read the care vocabulary, c3 none (like the fallback collection); the LLM tier follows c1 and c3.
 * A move that changes the vocabulary extracts tiers 1 and 2 again (rules, no model) and withdraws the LLM facts that
 * came from the old vocabulary, keeping names, relations and identities; a move between collections of the same
 * vocabulary only re-scopes; a page skipped as not selected gets its first enrichment when it enters an LLM collection.
 */
public class CollectionMoveTest extends LlmFixture {

    private static final String A = "A00001a00001";
    private static final String B = "B00002b00002";

    @Override
    protected String[] settings() {
        return new String[] {KgConfig.COLLECTIONS, "c1,c2,c3", KgConfig.LLM_COLLECTIONS, "c1,c3", KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                KgConfig.VOCAB_PREFIX + "c1", "care", KgConfig.VOCAB_PREFIX + "c2", "care", "scoutro.kg.llm.kinds.c1", "nursinghome"};
    }

    private long vocabularyEvidence(final String doc, final int tier) {
        return count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_statement s"
                + " ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred WHERE d.doc_id = '" + doc + "' AND e.tier = " + tier
                + " AND v.name IN ('category', 'industry', 'industry_category', 'audience_segment')");
    }

    private long evidence(final String doc, final int tier, final String predicate) {
        return count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_statement s"
                + " ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred WHERE d.doc_id = '" + doc + "' AND e.tier = " + tier
                + " AND v.name = '" + predicate + "'");
    }

    private String collectionsOf(final String doc) {
        return String.join(",", strings("SELECT k.name FROM kg_doc d JOIN kg_doc_collection dc ON dc.doc_rowid = d.doc_rowid"
                + " JOIN kg_collection k ON k.coll_id = dc.coll_id WHERE d.doc_id = '" + doc + "' ORDER BY k.name"));
    }

    private String operator() {
        return String.join(",", strings("SELECT e.public_id FROM kg_entity e JOIN kg_statement s ON s.subj = e.ent_rowid JOIN kg_vocab v"
                + " ON v.term_id = s.pred WHERE v.name = 'name' AND s.obj_val = 'Muster Pflege gGmbH' ORDER BY 1"));
    }

    private long rescoped() throws Exception {
        return this.sync.status().getJSONObject("processed").getLong("rescopedExtractions");
    }

    /** The fixture's answer, plus a category the model reads for the day care service (through the page's vocabulary). */
    private void answerWithCategory() {
        this.model.answer = user -> {
            final String a = LlmServiceTest.Fake.grounded(user);
            if (!a.contains("Tagespflege")) return a;
            return a.substring(0, a.length() - 1) + ",\"values\":[{\"subject\":\"e2\",\"predicate\":\"category\","
                    + "\"quote\":\"Das Haus Lindenhof bietet Tagespflege an\"}]}";
        };
    }

    @Test
    public void aMoveIntoACollectionWithoutVocabularyWithdrawsVocabularyFactsWithoutAModel() throws Exception {
        answerWithCategory();
        add(A, "https://www.muster-pflege.de/leistungen", "c1", LD, TEXT, "Leistungen");
        settleSync();
        settleLlm();
        assertEquals(1, this.model.calls.get());
        final long rules = vocabularyEvidence(A, 2), llm = vocabularyEvidence(A, 3);
        assertTrue("the care vocabulary read categories (rules " + rules + ")", rules > 0);
        assertTrue("the LLM read a category through the care vocabulary (" + llm + ")", llm > 0);
        final long relations = evidence(A, 3, "operates");
        assertTrue(relations > 0);
        final String operatorBefore = operator();
        // the page moves to c3 (no vocabulary), its content unchanged
        add(A, "https://www.muster-pflege.de/leistungen", "c3", LD, TEXT, "Leistungen");
        settleSync();
        settleLlm();
        assertEquals("c3", collectionsOf(A));
        assertEquals("no model request for a move", 1, this.model.calls.get());
        assertEquals(1, rescoped());
        assertEquals("tiers 1 and 2 read again without the care vocabulary", 0, vocabularyEvidence(A, 2));
        assertEquals("the LLM's vocabulary facts are withdrawn", 0, vocabularyEvidence(A, 3));
        assertEquals("its relations stay", relations, evidence(A, 3, "operates"));
        assertTrue("the JSON-LD name stays", evidence(A, 1, "name") > 0);
        assertEquals("the identity stays", operatorBefore, operator());
        assertEquals("the LLM result stays done", "1:", llmStatus(A));
    }

    @Test
    public void aMoveBetweenCollectionsOfTheSameVocabularyOnlyRescopes() throws Exception {
        add(A, "https://www.muster-pflege.de/leistungen", "c1", LD, TEXT, "Leistungen");
        settleSync();
        settleLlm();
        final long rules = vocabularyEvidence(A, 2), llm = vocabularyEvidence(A, 3);
        add(A, "https://www.muster-pflege.de/leistungen", "c2", LD, TEXT, "Leistungen");
        settleSync();
        assertEquals("c2", collectionsOf(A));
        assertEquals(0, rescoped());
        assertEquals(rules, vocabularyEvidence(A, 2));
        assertEquals(llm, vocabularyEvidence(A, 3));
    }

    @Test
    public void aPageNotSelectedGetsItsFirstEnrichmentWhenItEntersAnLlmCollection() throws Exception {
        add(B, "https://www.muster-pflege.de/leistungen", "c2", LD, TEXT, "Leistungen");
        settleSync();
        settleLlm(() -> count("SELECT count(*) FROM kg_doc WHERE llm_status IS NULL") == 0L);
        assertEquals(0, this.model.calls.get());
        assertEquals("3:not_selected", llmStatus(B));
        add(B, "https://www.muster-pflege.de/leistungen", "c1", LD, TEXT, "Leistungen");
        settleSync();
        settleLlm();
        assertEquals("its first reading, not a repeated one", 1, this.model.calls.get());
        assertEquals("1:", llmStatus(B));
        // and back out of the LLM collection: nothing is asked, the result stays
        add(B, "https://www.muster-pflege.de/leistungen", "c2", LD, TEXT, "Leistungen");
        settleSync();
        settleLlm(() -> true);
        assertEquals(1, this.model.calls.get());
        assertTrue(evidence(B, 3, "operates") > 0);
    }
}
