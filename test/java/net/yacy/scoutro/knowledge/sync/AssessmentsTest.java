package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.publish.Assessments;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.store.Observations;

/**
 * WP-E: assessments withdraw or reclassify the support of statements by the evidence of one source, host or domain;
 * they hold across re-extraction, the LLM cache and new revisions, take dependent derived industries along, mark the
 * archive and are undone exactly by a revoke. Isolated test data only (embedded Solr, temporary store).
 */
public class AssessmentsTest extends LlmFixture {

    private static final String A = "A00001a00001";
    private static final String B = "B00002a00001";
    private static final String URL_A = "https://www.muster-pflege.de/leistungen";
    private static final String URL_B = "https://www.muster-pflege.de/kontakt";

    @Override
    protected String[] settings() {
        return new String[] {KgConfig.COLLECTIONS, "c1", KgConfig.LLM_COLLECTIONS, "c1", KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                KgConfig.VOCAB_PREFIX + "c1", "care", "scoutro.kg.llm.kinds.c1", "nursinghome"};
    }

    /** The fixture's answer plus a category of the day care service. */
    private void answerWithCategory() {
        this.model.answer = user -> {
            final String a = LlmServiceTest.Fake.grounded(user);
            if (!a.contains("Tagespflege")) return a;
            return a.substring(0, a.length() - 1) + ",\"values\":[{\"subject\":\"e2\",\"predicate\":\"category\","
                    + "\"quote\":\"Das Haus Lindenhof bietet Tagespflege an\"}]}";
        };
    }

    private Assessments.Draft draft(final String action, final String kind, final String scope, final String predicate) {
        final Assessments.Draft d = new Assessments.Draft();
        d.action = action;
        d.scopeKind = kind;
        d.scope = scope;
        d.predicate = predicate;
        d.reason = "test";
        d.actor = "admin";
        return d;
    }

    private JSONObject preview(final Assessments.Draft d) throws KgException {
        assertEquals(null, d.validate());
        return this.store.read(c -> Assessments.preview(c, Assessments.draft(d)));
    }

    private JSONObject create(final Assessments.Draft d) throws KgException {
        assertEquals(null, d.validate());
        return this.store.write(StorageGuard.WriteClass.MAINTENANCE, 16L << 20, tx -> Assessments.create(tx, this.cfg, d, this.clock.get()));
    }

    private JSONObject revoke(final String id) throws KgException {
        return this.store.write(StorageGuard.WriteClass.MAINTENANCE, 16L << 20, tx -> Assessments.revoke(tx, this.cfg, id, "admin",
                this.clock.get()));
    }

    private long evidence(final String doc, final String predicate, final Integer tier) {
        return count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_statement s"
                + " ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred WHERE d.doc_id = '" + doc + "' AND v.name = '"
                + predicate + "'" + (tier == null ? "" : " AND e.tier = " + tier));
    }

    private long statements(final String predicate) {
        return count("SELECT count(*) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = '" + predicate + "'");
    }

    /** Every field of the evidence rows of a predicate, for an exact comparison. */
    private List<String> rows(final String predicate) {
        return strings("SELECT s.public_id || '|' || d.doc_id || '|' || e.tier || '|' || x.name || '/' || x.version || '/' || x.model"
                + " || '|' || e.kind || '|' || e.certainty || '|' || coalesce(e.confidence, 'n') || '|' || coalesce(e.locator, 'n') || '|'"
                + " || coalesce(e.excerpt, 'n') || '|' || e.observed_at || '|' || coalesce(e.source_revision, 'n') || '|'"
                + " || coalesce(e.source_observed_at, 'n') FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid"
                + " JOIN kg_vocab v ON v.term_id = s.pred JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_extractor x"
                + " ON x.ext_id = e.ext_id WHERE v.name = '" + predicate + "' ORDER BY 1");
    }

    private void readAgain(final String doc) {
        exec("UPDATE kg_doc SET input_hash = NULL WHERE doc_id = '" + doc + "'");
        exec("INSERT OR IGNORE INTO kg_work (doc_id, reason, event_version, priority, not_before, enqueued_at) VALUES ('" + doc
                + "', 4, 0, 5, 0, 0)");
        settleSync();
    }

    @Test
    public void withdrawingOneSourceKeepsTheStatementOfAnIndependentSource() throws Exception {
        add(A, URL_A, "c1", LD, TEXT, "Leistungen");
        add(B, URL_B, "c1", LD, TEXT, "Kontakt");
        settleSync();
        assertEquals(1, statements("phone"));
        final List<String> before = rows("phone");
        assertEquals(2, before.size());
        final Assessments.Draft d = draft(Assessments.WITHDRAW, Assessments.SOURCE, A, "phone");
        final JSONObject p = preview(d);
        assertEquals(1, p.getInt("evidence"));
        assertEquals("the other source keeps it", 0, p.getInt("statementsLeaving"));
        final JSONObject made = create(d);
        final String id = made.getJSONObject("assessment").getString("id");
        assertEquals(1, made.getJSONObject("applied").getInt("evidence"));
        assertEquals(1, statements("phone"));
        assertEquals(0, evidence(A, "phone", null));
        assertEquals(1, evidence(B, "phone", null));
        // idempotent: the rule finds nothing more
        assertEquals(0, preview(d).getInt("evidence"));
        // revoke: the row comes back field by field
        final JSONObject r = revoke(id);
        assertEquals(1, r.getInt("restored"));
        assertEquals(0, r.getInt("notRestored"));
        assertEquals(before, rows("phone"));
        assertEquals("revoked", r.getJSONObject("assessment").getString("status"));
        assertEquals(0, count("SELECT count(*) FROM kg_evidence_withdrawn"));
        assertEquals(null, revoke(id)); // a second revoke changes nothing
    }

    @Test
    public void theOnlyEvidenceLeavesTheGraphAndStaysOutAcrossReextractionAndNewRevisions() throws Exception {
        add(A, URL_A, "c1", LD, TEXT, "Leistungen");
        settleSync();
        final List<String> before = rows("phone");
        assertEquals(1, before.size());
        final Assessments.Draft d = draft(Assessments.WITHDRAW, Assessments.HOST, "www.muster-pflege.de", "phone");
        assertEquals(1, preview(d).getInt("statementsLeaving"));
        final String id = create(d).getJSONObject("assessment").getString("id");
        assertEquals("the statement left the active graph", 0, statements("phone"));
        // the rules read the same page again: the claim is held back
        readAgain(A);
        assertEquals(0, statements("phone"));
        JSONObject listed = this.store.read(c -> Assessments.list(c, "active", 10)).getJSONArray("items").getJSONObject(0);
        assertFalse("the same revision needs no review", listed.getBoolean("reviewNeeded"));
        // a new revision of the source says it again: held, and the assessment is marked for review
        this.clock.addAndGet(60_000L);
        add(A, URL_A, "c1", LD, TEXT + " Neu: Kurzzeitpflege.", "Leistungen");
        settleSync();
        assertEquals(0, statements("phone"));
        listed = this.store.read(c -> Assessments.list(c, "active", 10)).getJSONArray("items").getJSONObject(0);
        assertTrue(listed.getBoolean("reviewNeeded"));
        assertTrue(listed.getLong("blockedClaims") >= 1);
        assertEquals(1, count("SELECT count(*) FROM kg_event WHERE code = 'assessment_review'"));
        // revoking accepts the source as it is now
        final JSONObject r = revoke(id);
        assertEquals(1, r.getInt("restored"));
        assertEquals(1, statements("phone"));
        assertEquals(1, evidence(A, "phone", null));
    }

    @Test
    public void llmFactsStayOutWhenTheCachedResultIsAppliedAgainAndDerivedIndustriesGoWithTheirCategory() throws Exception {
        answerWithCategory();
        add(A, URL_A, "c1", LD, TEXT, "Leistungen");
        settleSync();
        settleLlm();
        assertEquals(1, this.model.calls.get());
        assertTrue("the LLM read the category", evidence(A, "category", 3) > 0);
        final long derived = count("SELECT count(*) FROM kg_evidence WHERE tier = 3 AND locator LIKE 'derived:category:%'");
        assertTrue("and derived an industry from it (" + derived + ")", derived > 0);
        final long operates = evidence(A, "operates", 3);
        final Assessments.Draft d = draft(Assessments.WITHDRAW, Assessments.SOURCE, A, "category");
        d.tier = 3;
        final JSONObject p = preview(d);
        assertEquals(derived, p.getInt("dependentEvidence"));
        final String id = create(d).getJSONObject("assessment").getString("id");
        assertEquals(0, evidence(A, "category", 3));
        assertEquals("the derived industries went with it", 0,
                count("SELECT count(*) FROM kg_evidence WHERE tier = 3 AND locator LIKE 'derived:category:%'"));
        assertEquals("relations stay", operates, evidence(A, "operates", 3));
        assertTrue("the rules' categories stay (tier 2 is not assessed)", evidence(A, "category", 2) >= 0);
        // the LLM tier reads the page again from its cache: no model request, and the category stays out
        exec("UPDATE kg_doc SET llm_status = NULL, llm_reason = NULL WHERE doc_id = '" + A + "'");
        settleLlm();
        assertEquals("a cache hit, no model request", 1, this.model.calls.get());
        assertEquals(0, evidence(A, "category", 3));
        assertEquals(0, count("SELECT count(*) FROM kg_evidence WHERE tier = 3 AND locator LIKE 'derived:category:%'"));
        assertEquals(operates, evidence(A, "operates", 3));
        // revoke brings both back
        final JSONObject r = revoke(id);
        assertTrue(r.toString(), r.getInt("restored") >= 1 + derived);
        assertTrue(evidence(A, "category", 3) > 0);
        assertEquals(derived, count("SELECT count(*) FROM kg_evidence WHERE tier = 3 AND locator LIKE 'derived:category:%'"));
    }

    @Test
    public void reclassifyMovesTheEvidenceToTheTargetPredicateAndMarksTheArchive() throws Exception {
        answerWithCategory();
        add(A, URL_A, "c1", LD, TEXT, "Leistungen");
        settleSync();
        settleLlm();
        final long offers = evidence(A, "offers", 3);
        final long all = evidence(A, "offers", null);
        assertTrue(offers > 0);
        final long recorded = count("SELECT count(*) FROM kg_observation WHERE source_id = '" + A + "' AND predicate = 'offers'"
                + " AND assertion_status = 'recorded'");
        final List<String> before = rows("offers");
        final Assessments.Draft d = draft(Assessments.RECLASSIFY, Assessments.DOMAIN, "muster-pflege.de", "offers");
        d.targetPredicate = "service_provider_for";
        final JSONObject made = create(d);
        final String id = made.getJSONObject("assessment").getString("id");
        assertEquals("every tier in the domain (rules and LLM)", all, made.getJSONObject("applied").getInt("reclassified"));
        assertEquals(0, evidence(A, "offers", null));
        assertEquals(all, evidence(A, "service_provider_for", null));
        assertEquals(offers, evidence(A, "service_provider_for", 3));
        assertTrue("offers are archived observations", recorded > 0);
        assertEquals(recorded, count("SELECT count(*) FROM kg_observation WHERE source_id = '" + A + "' AND predicate = 'offers'"
                + " AND assertion_status = 'corrected' AND correction_of = '" + id + "'"));
        // the cached LLM result is published under the target predicate, never under offers
        exec("UPDATE kg_doc SET llm_status = NULL, llm_reason = NULL WHERE doc_id = '" + A + "'");
        settleLlm();
        assertEquals(0, evidence(A, "offers", 3));
        assertEquals(offers, evidence(A, "service_provider_for", 3));
        final JSONObject r = revoke(id);
        assertTrue(r.toString(), r.getInt("restored") >= all);
        assertEquals(0, evidence(A, "service_provider_for", 3));
        assertEquals(before, rows("offers"));
        assertEquals(0, count("SELECT count(*) FROM kg_observation WHERE correction_of = '" + id + "'"));
    }

    @Test
    public void draftsAreValidated() {
        Assessments.Draft d = draft("delete", Assessments.SOURCE, A, "phone");
        assertEquals("action", d.validate()[0]);
        d = draft(Assessments.WITHDRAW, Assessments.SOURCE, A, "no_such_predicate");
        assertEquals("predicate", d.validate()[0]);
        d = draft(Assessments.RECLASSIFY, Assessments.SOURCE, A, "price");
        d.targetPredicate = "name";
        assertEquals("a price (JSON) cannot become a name (text)", "targetPredicate", d.validate()[0]);
        d = draft(Assessments.WITHDRAW, Assessments.SOURCE, A, "operates");
        d.value = "Haus Lindenhof";
        assertEquals("a relation's value is an entity ID", "value", d.validate()[0]);
        d = draft(Assessments.WITHDRAW, Assessments.SOURCE, A, "phone");
        d.reason = " ";
        assertEquals("reason", d.validate()[0]);
        d = draft(Assessments.WITHDRAW, Assessments.SOURCE, A, "name");
        d.value = "  Muster Pflege gGmbH ";
        assertEquals(null, d.validate());
        assertNotNull(d.value);
    }

    @Test
    public void aRebuildCarriesTheAssessmentsAndTheNextStartAppliesThemAgain() throws Exception {
        add(A, URL_A, "c1", LD, TEXT, "Leistungen");
        settleSync();
        final String id = create(draft(Assessments.WITHDRAW, Assessments.SOURCE, A, "phone")).getJSONObject("assessment").getString("id");
        assertEquals(0, statements("phone"));
        // a shadow graph built from Solr alone (as a rebuild does) has the phone again
        final KgPaths shadowPaths = new KgPaths(this.tmp.newFolder("shadow"));
        final StorageGuard g = new StorageGuard(this.cfg, shadowPaths, this.storageProbe, System::currentTimeMillis);
        KgStore shadow = KgStore.open(shadowPaths, this.cfg, g, KgStore.SQLITE, System::currentTimeMillis);
        final DirtySet shadowDirty = new DirtySet(1000);
        final SyncService shadowSync = new SyncService(this.cfg, shadow, shadowDirty, this.source, new Gates(this.cfg, Gates.IDLE),
                this.clock::get, false);
        shadowSync.reconciler().request(Reconciler.REASON_ADMIN, this.clock.get());
        final KgStore sh = shadow;
        for (int i = 0; i < 4000; i++) {
            final boolean more = shadowSync.step();
            if (!more && !shadowSync.reconciler().pending() && sh.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_work")) == 0L
                    && sh.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_doc")) > 0L) {
                break;
            }
            if (!more) this.clock.addAndGet(3000L);
        }
        assertEquals(1L, (long) shadow.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_statement s JOIN kg_vocab v"
                + " ON v.term_id = s.pred WHERE v.name = 'phone'")));
        shadowSync.requestStop();
        shadow.close();
        this.sync.requestStop();
        this.llm.requestStop();
        this.store.close();
        final File previous = this.paths.db;
        Observations.carryInto(shadowPaths.db, previous);
        shadow = KgStore.open(shadowPaths, this.cfg, g, KgStore.SQLITE, System::currentTimeMillis);
        try {
            assertEquals("1", (String) shadow.read(c -> KgStore.getMeta(c, Assessments.META_REAPPLY)));
            final JSONObject again = shadow.write(StorageGuard.WriteClass.MAINTENANCE, 16L << 20,
                    tx -> Assessments.reapplyAll(tx, this.cfg, this.clock.get()));
            assertEquals(1, again.getInt("assessments"));
            assertEquals(1, again.getInt("evidence"));
            assertEquals(0L, (long) shadow.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_statement s JOIN kg_vocab v"
                    + " ON v.term_id = s.pred WHERE v.name = 'phone'")));
            assertEquals(null, (String) shadow.read(c -> KgStore.getMeta(c, Assessments.META_REAPPLY)));
            assertEquals(id, shadow.read(c -> Assessments.get(c, id)).id);
        } finally {
            shadow.close();
            this.store = null;
        }
    }

    // ------------------------------------------------------------------------------------- operator statements (tier 4)

    private String operator() {
        return strings("SELECT e.public_id FROM kg_entity e JOIN kg_statement s ON s.subj = e.ent_rowid JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE v.name = 'name' AND s.obj_val = 'Muster Pflege gGmbH' AND e.status = 1").get(0);
    }

    private long operatorEvidence(final String id) {
        return count("SELECT count(*) FROM kg_evidence WHERE tier = 4 AND kind = 5 AND locator = 'operator:" + id + "'");
    }

    @Test
    public void anOperatorStatementIsStatedEvidenceAndSurvivesReextractionPurgeAndARevision() throws Exception {
        add(A, URL_A, "c1", LD, TEXT, "Leistungen");
        settleSync();
        final Assessments.Draft d = draft(Assessments.ASSERT, Assessments.SOURCE, A, "operates");
        d.subjectId = operator();
        // a literal the extraction missed: the legal form the page states
        d.predicate = "legal_form";
        d.value = "gGmbH";
        d.quote = "Die Muster Pflege gGmbH betreibt das Haus Lindenhof";
        assertTrue(Assessments.quoteInSource(TEXT, d.quote));
        assertTrue("a quote that is not in the source is refused by the runtime", !Assessments.quoteInSource(TEXT, "Die Muster AG betreibt"));
        final JSONObject p = preview(d);
        assertEquals(1, p.getInt("evidence"));
        assertTrue(p.getBoolean("subjectFound"));
        final String id = create(d).getJSONObject("assessment").getString("id");
        assertEquals(1, operatorEvidence(id));
        assertEquals("stated by the operator: supported", 1, count("SELECT count(*) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE v.name = 'legal_form' AND s.obj_val = 'gGmbH' AND s.quality = 1"));
        // the rules read the page again: tier 4 is no tier the extraction replaces
        readAgain(A);
        assertEquals(1, operatorEvidence(id));
        // a purge of the page's evidence (retention) and the next reading: the statement is stated again
        exec("DELETE FROM kg_evidence WHERE doc_rowid = (SELECT doc_rowid FROM kg_doc WHERE doc_id = '" + A + "')");
        assertEquals(0, operatorEvidence(id));
        readAgain(A);
        assertEquals(1, operatorEvidence(id));
        // withdrawing the predicate never removes an operator statement (it is revoked)
        create(draft(Assessments.WITHDRAW, Assessments.SOURCE, A, "legal_form"));
        assertEquals(1, operatorEvidence(id));
        // a new revision of the source: the statement holds, the assessment is marked for review
        this.clock.addAndGet(60_000L);
        add(A, URL_A, "c1", LD, TEXT + " Neu: Kurzzeitpflege.", "Leistungen");
        settleSync();
        assertEquals(1, operatorEvidence(id));
        assertTrue(this.store.read(c -> Assessments.get(c, id)).reviewNeeded);
        // revoked: gone, and not stated again
        final JSONObject r = revoke(id);
        assertEquals(1, r.getInt("removedOperatorEvidence"));
        assertEquals(0, operatorEvidence(id));
        readAgain(A);
        assertEquals(0, operatorEvidence(id));
        // validation: only a source, with subject, value and quote
        final Assessments.Draft bad = draft(Assessments.ASSERT, Assessments.HOST, "www.muster-pflege.de", "legal_form");
        bad.subjectId = d.subjectId;
        bad.value = "gGmbH";
        bad.quote = "x";
        assertEquals("scope", bad.validate()[0]);
        final Assessments.Draft noQuote = draft(Assessments.ASSERT, Assessments.SOURCE, A, "legal_form");
        noQuote.subjectId = d.subjectId;
        noQuote.value = "gGmbH";
        assertEquals("quote", noQuote.validate()[0]);
    }

    @Test
    public void aRebuildStatesTheOperatorStatementsAgain() throws Exception {
        add(A, URL_A, "c1", LD, TEXT, "Leistungen");
        settleSync();
        final Assessments.Draft d = draft(Assessments.ASSERT, Assessments.SOURCE, A, "legal_form");
        d.subjectId = operator();
        d.value = "gGmbH";
        d.quote = "Die Muster Pflege gGmbH";
        final String id = create(d).getJSONObject("assessment").getString("id");
        // the graph reset by a full reset: documents and evidence gone, the assessment kept; the next publication states it
        exec("DELETE FROM kg_doc");
        assertEquals(0, operatorEvidence(id));
        assertEquals(1, count("SELECT count(*) FROM kg_assessment WHERE status = 'active'"));
        this.clock.addAndGet(60_000L);
        add(A, URL_A, "c1", LD, TEXT, "Leistungen");
        settleSync();
        assertEquals(1, operatorEvidence(id));
        // reapplying (after a rebuild carry) finds it present and changes nothing
        final JSONObject again = this.store.write(StorageGuard.WriteClass.MAINTENANCE, 16L << 20,
                tx -> Assessments.reapplyAll(tx, this.cfg, this.clock.get()));
        assertEquals(1, again.getInt("assessments"));
        assertEquals(1, operatorEvidence(id));
    }
}
