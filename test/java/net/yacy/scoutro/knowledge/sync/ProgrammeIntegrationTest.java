package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.publish.Assessments;
import net.yacy.scoutro.knowledge.read.QualityMetrics;

/**
 * The data quality programme R1-R4 in one flow on the synchronised graph with the LLM tier: repeated crawls and
 * collection changes of a page that carries an assessment (WP-E withdraw) and an operator statement (WP-E assert), the
 * vocabulary change of a move (WP-D), the identity preview (WP-F) and the quality metrics (WP-B). The model is asked
 * for new content only: never for a recrawl of the same content, a move, an assessment, a preview or the metrics.
 * c1 and c2 read the care vocabulary, c3 none; the LLM tier follows c1.
 */
public class ProgrammeIntegrationTest extends LlmFixture {

    private static final String A = "A00001a00001";
    private static final String URL = "https://www.muster-pflege.de/leistungen";

    @Override
    protected String[] settings() {
        return new String[] {KgConfig.COLLECTIONS, "c1,c2,c3", KgConfig.LLM_COLLECTIONS, "c1", KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                KgConfig.VOCAB_PREFIX + "c1", "care", KgConfig.VOCAB_PREFIX + "c2", "care", "scoutro.kg.llm.kinds.c1", "nursinghome"};
    }

    private long evidence(final String predicate, final int tier) {
        return count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_statement s"
                + " ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred WHERE d.doc_id = '" + A + "' AND e.tier = " + tier
                + " AND v.name = '" + predicate + "'");
    }

    private long liveStatements(final String predicate) {
        return count("SELECT count(DISTINCT s.stmt_rowid) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred JOIN kg_evidence e"
                + " ON e.stmt_rowid = s.stmt_rowid WHERE v.name = '" + predicate + "'");
    }

    private String operator() {
        return strings("SELECT e.public_id FROM kg_entity e JOIN kg_statement s ON s.subj = e.ent_rowid JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE v.name = 'name' AND s.obj_val = 'Muster Pflege gGmbH' AND e.status = 1").get(0);
    }

    private JSONObject assess(final Assessments.Draft d) throws KgException {
        assertNull(d.validate());
        return this.store.write(StorageGuard.WriteClass.MAINTENANCE, 16L << 20, tx -> Assessments.create(tx, this.cfg, d, this.clock.get()));
    }

    private Assessments.Draft draft(final String action, final String predicate) {
        final Assessments.Draft d = new Assessments.Draft();
        d.action = action;
        d.scopeKind = Assessments.SOURCE;
        d.scope = A;
        d.predicate = predicate;
        d.reason = "integration";
        d.actor = "admin";
        return d;
    }

    /** The page indexed again with the same content into {@code collection} (a recrawl, a collection decision or a move). */
    private void crawl(final String collection) throws Exception {
        this.clock.addAndGet(60_000L);
        add(A, URL, collection, LD, TEXT, "Leistungen");
        settleSync();
        settleLlm(() -> count("SELECT count(*) FROM kg_llm_work") == 0L);
    }

    private void noSyncError() throws Exception {
        final JSONObject s = this.sync.status();
        assertTrue(s.toString(), s.isNull("lastError") || s.optString("lastError", "").isEmpty());
    }

    @Test
    public void repeatedCrawlsAndCollectionChangesKeepTheCorrectionsAndAskTheModelOnlyForNewContent() throws Exception {
        add(A, URL, "c1", LD, TEXT, "Leistungen");
        settleSync();
        settleLlm();
        assertEquals("the first reading", 1, this.model.calls.get());
        assertTrue(evidence("phone", 1) > 0);
        final String org = operator();
        // WP-E: the phone of this source is withdrawn, the legal form stated by the operator with the source's words
        final String withdraw = assess(draft(Assessments.WITHDRAW, "phone")).getJSONObject("assessment").getString("id");
        final Assessments.Draft stated = draft(Assessments.ASSERT, "legal_form");
        stated.subjectId = org;
        stated.value = "gGmbH";
        stated.quote = "Die Muster Pflege gGmbH betreibt das Haus Lindenhof";
        assertTrue(Assessments.quoteInSource(TEXT, stated.quote));
        final String operatorStatement = assess(stated).getJSONObject("assessment").getString("id");
        assertEquals(0, evidence("phone", 1));
        assertEquals(1, evidence("legal_form", 4));
        final long statements = count("SELECT count(*) FROM kg_statement");
        // three recrawls of the same content: nothing new, nothing asked, the corrections hold
        for (int i = 0; i < 3; i++) {
            crawl("c1");
            noSyncError();
        }
        assertEquals("a recrawl of the same content never asks the model", 1, this.model.calls.get());
        assertEquals(0, evidence("phone", 1));
        assertEquals(1, evidence("legal_form", 4));
        assertEquals(statements, count("SELECT count(*) FROM kg_statement"));
        // WP-A/WP-D: a move to c2 (same vocabulary), to c3 (no vocabulary) and back; the content stays
        crawl("c2");
        crawl("c3");
        assertEquals("the move without vocabulary withdrew the vocabulary facts", 0, evidence("category", 2));
        crawl("c1");
        noSyncError();
        assertEquals("no move asks the model", 1, this.model.calls.get());
        assertEquals("the withdrawal holds across every move", 0, evidence("phone", 1));
        assertEquals("the operator statement holds across every move", 1, evidence("legal_form", 4));
        assertEquals("the identity stays", org, operator());
        assertTrue("the rules read the care vocabulary again", evidence("category", 2) > 0);
        // WP-F: the identity preview of the domain writes nothing and asks nothing
        final long before = count("SELECT count(*) FROM kg_evidence") + count("SELECT count(*) FROM kg_entity") * 1000;
        final JSONObject preview = this.sync.identityPreview("muster-pflege.de", true, true, 200);
        assertTrue(preview.toString(), !preview.optBoolean("written"));
        assertEquals(before, count("SELECT count(*) FROM kg_evidence") + count("SELECT count(*) FROM kg_entity") * 1000);
        // WP-B: the metrics count the corrections
        final JSONObject m = new QualityMetrics(this.store, this.clock::get).compute().getJSONObject("metrics");
        assertEquals(2, m.getJSONObject("assessments").getLong("total"));
        assertEquals(1, m.getJSONObject("operator_statements").getLong("evidence"));
        assertTrue(m.getJSONObject("withdrawn_evidence").getLong("total") >= 1);
        assertEquals("no preview, assessment or metric asks the model", 1, this.model.calls.get());
        // new content is the only reason for a new reading; the assessments hold and ask for a review
        this.clock.addAndGet(60_000L);
        add(A, URL, "c1", LD, TEXT + " Neu: Kurzzeitpflege.", "Leistungen");
        settleSync();
        settleLlm();
        assertEquals("new content: one new reading", 2, this.model.calls.get());
        assertEquals(0, evidence("phone", 1));
        assertEquals(1, evidence("legal_form", 4));
        assertTrue(this.store.read(c -> Assessments.get(c, operatorStatement)).reviewNeeded);
        // revoking the withdrawal brings the phone back with the rules (no model)
        this.store.write(StorageGuard.WriteClass.MAINTENANCE, 16L << 20, tx -> Assessments.revoke(tx, this.cfg, withdraw, "admin", this.clock.get()));
        settleSync();
        assertTrue(liveStatements("phone") > 0);
        assertEquals(2, this.model.calls.get());
        noSyncError();
    }
}
