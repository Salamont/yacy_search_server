package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.solr.common.SolrInputDocument;
import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;

/**
 * Regressions of the final review of the LLM tier's coverage work (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27): fixes that
 * were first only argued from the code. Collections are created by the operator with names of their own; these tests
 * use freely chosen names, so nothing here depends on a particular installation.
 */
public class LlmReviewRegressionTest extends LlmFixture {

    static final List<String> COLLECTIONS = List.of("alpha-sites", "beta-sites", "gamma-sites", "delta-sites", "epsilon-sites",
            "zeta-sites");
    static final String FIRST = COLLECTIONS.get(0);

    @Override
    protected LlmServiceTest.Fake newModel() {
        return new LlmContinuationTest.FailingFake();
    }

    @Override
    protected String[] settings() {
        final String all = String.join(",", COLLECTIONS);
        return new String[] {KgConfig.COLLECTIONS, all, KgConfig.LLM_COLLECTIONS, all, KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                "scoutro.kg.llm.kinds." + FIRST, "nursinghome"};
    }

    private LlmContinuationTest.FailingFake failing() {
        return (LlmContinuationTest.FailingFake) this.model;
    }

    private JSONObject processed() throws Exception {
        return this.llm.status().getJSONObject("processed");
    }

    private long evidence(final String id) {
        return count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE e.tier = 3 AND d.doc_id = '"
                + id + "'");
    }

    /** Steps until the page has the given LLM status, at most {@code steps} times; idle steps move the clock on. */
    private void stepUntil(final String id, final String status, final int steps, final long idleMillis) {
        for (int i = 0; i < steps && !status.equals(llmStatus(id)); i++) {
            if (!this.llm.step()) {
                this.clock.addAndGet(idleMillis);
            }
        }
    }

    @Test
    public void anItemHandedBackForANewInputIsNotReleasedAgainUnderASecondWorker() throws Exception {
        final String id = id('A', 1);
        addAll(List.of(imprint('A', 1, FIRST)));
        settleSync();
        final SolrInputDocument changed = imprint('A', 1, FIRST);
        changed.setField("text_t", "Impressum. Die Muster Pflege gGmbH betreibt das Haus Sonnenhof.");
        changed.setField("exact_signature_l", 42L);
        this.model.during = () -> {
            try {
                // the page changes while the model answers ...
                addAll(List.of(changed));
                settleSync();
                // ... and a second worker claims the item the moment this worker hands it back for the new input
                // (deterministic: a trigger claims it in the transaction of the hand-back, once)
                exec("CREATE TABLE test_second_worker (claimed_at INTEGER)");
                exec("CREATE TRIGGER test_second_worker_claims AFTER UPDATE OF claimed_at ON kg_llm_work"
                        + " WHEN NEW.doc_id = '" + id + "' AND OLD.claimed_at IS NOT NULL AND NEW.claimed_at IS NULL"
                        + " AND NOT EXISTS (SELECT 1 FROM test_second_worker)"
                        + " BEGIN INSERT INTO test_second_worker VALUES (4242);"
                        + " UPDATE kg_llm_work SET claimed_at = 4242 WHERE doc_id = NEW.doc_id; END");
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        };
        this.llm.step();
        assertEquals("the item was handed back once and claimed by the second worker", 1L, count("SELECT count(*) FROM test_second_worker"));
        assertEquals("the second worker's claim stands: the first worker does not release the item again", List.of("4242"),
                strings("SELECT claimed_at FROM kg_llm_work WHERE doc_id = '" + id + "'"));
        assertEquals("no answer about the old text is published", 0L, evidence(id));
    }

    @Test
    public void aPassRevisitsAtMostFiftyPagesOverAllCollectionsAndEveryCollectionGetsItsShare() throws Exception {
        final List<SolrInputDocument> docs = new ArrayList<>();
        for (int c = 0; c < COLLECTIONS.size(); c++) {
            for (int i = 1; i <= 12; i++) {
                docs.add(imprint((char) ('B' + c), i, COLLECTIONS.get(c)));
            }
        }
        addAll(docs);
        settleSync();
        settleLlm();
        assertEquals(72L, count("SELECT count(*) FROM kg_doc WHERE llm_status = 1"));
        // every page becomes to do again behind its collection's frontier (as after a new text), without a bulk reopening
        exec("UPDATE kg_doc SET llm_status = NULL, llm_reason = NULL, llm_hash = NULL");
        this.clock.addAndGet(LlmService.REVISIT_PAUSE_MILLIS + 60_000L); // any finished sweep's pause is over
        final long before = processed().getLong("requeuedBehindFrontier");
        this.llm.step(); // one refill pass (and one page read)
        assertEquals("six collections of twelve pages: 50 revisits in one pass, not six shares of nine",
                (long) LlmService.REVISIT_BATCH, processed().getLong("requeuedBehindFrontier") - before);
        for (final String collection : COLLECTIONS) {
            assertTrue("a share for " + collection, count("SELECT count(*) FROM kg_llm_work w JOIN kg_doc d ON d.doc_id = w.doc_id"
                    + " JOIN kg_doc_collection dc ON dc.doc_rowid = d.doc_rowid JOIN kg_collection k ON k.coll_id = dc.coll_id"
                    + " WHERE k.name = '" + collection + "'") > 0L);
        }
    }

    @Test
    public void aReadingIsCountedOnceAlthoughItsFirstRequestFailed() throws Exception {
        final String ld = LD.replace("muster-pflege", "beispiel-navigation");
        final String text = LlmNavigationTest.page();
        final String handel = "Handel\n" + LlmNavigationTest.menu() + LlmNavigationTest.content("Handel", 2000);
        final String wir = "Unternehmen\n" + LlmNavigationTest.menu() + LlmNavigationTest.content("Wir", 2000);
        addAll(List.of(doc(LlmNavigationTest.TARGET, "https://www.beispiel-navigation.de/leistungen/tagespflege/", FIRST, ld, text,
                "Tagespflege"), doc("BBBBBBnav001", "https://www.beispiel-navigation.de/branchen/handel/", FIRST, ld, handel, "Handel"),
                doc("CCCCCCnav001", "https://www.beispiel-navigation.de/unternehmen/", FIRST, ld, wir, "Unternehmen")));
        settleSync();
        // the first request of the long page's first reading fails once: the page is planned and laid out again
        failing().failWhen = u -> u.contains("Pflege Absatz 0:");
        settleLlm(() -> count("SELECT count(*) FROM kg_llm_work") == 0L
                && count("SELECT count(*) FROM kg_doc WHERE state = 1 AND (llm_status IS NULL OR llm_reason = '" + LlmQueue.MORE_TEXT + "')")
                        == 0L);
        assertEquals(1, failing().failed.get());
        assertEquals("1:" + LlmQueue.FULL_TEXT, llmStatus(LlmNavigationTest.TARGET));
        final int moved = PageText.layout(text, PageText.recurring(List.of(PageText.keys(handel), PageText.keys(wir))),
                this.cfg.extractMaxInputChars).moved;
        assertTrue(moved > 0);
        final JSONObject p = processed();
        assertEquals("navigation moved once: the published first reading, not each attempt or the continuation", 1L,
                p.getLong("navigationMoved"));
        assertEquals((long) moved, p.getLong("navigationMovedChars"));
        assertEquals("each page published once", count("SELECT count(*) FROM kg_doc WHERE llm_status = 1"), p.getLong("published"));
        assertEquals("one continuation", 1L, p.getLong("continuationDocs"));
    }

    @Test
    public void trimmedKnownEntitiesAreCountedOncePerPublishedReading() throws Exception {
        final String first = id('K', 1);
        final String second = id('K', 2);
        add(first, "https://www.k1-beispiel.de/impressum", FIRST, LlmBudgetTest.bigLd(), TEXT);
        settleSync();
        settleLlm();
        final long once = processed().getLong("knownTrimmed");
        assertTrue("the large entity list is trimmed: " + once, once > 0L);
        // the same page on another host, whose first request fails once: planned twice, published once
        failing().failWhen = u -> u.contains("k2-beispiel");
        add(second, "https://www.k2-beispiel.de/impressum", FIRST, LlmBudgetTest.bigLd(), TEXT);
        settleSync();
        settleLlm();
        assertEquals(1, failing().failed.get());
        assertEquals("1:", llmStatus(second));
        assertEquals("counted for the published reading only", 2 * once, processed().getLong("knownTrimmed"));
        assertEquals(0L, processed().getLong("chunksSplit"));
    }

    @Test
    public void aContinuationWhoseInputStaysUnconfirmedWaitsBoundedAndKeepsTheFirstReading() throws Exception {
        final String id = LlmLongPageTest.PAGE;
        // the imprint text first, so the first reading publishes evidence; then a long reference text
        final String text = TEXT + "\n" + LlmLongPageTest.page();
        add(id, "https://www.beispiel-referenzen.de/referenzen/projekt-beispiele/", FIRST, LD.replace("muster-pflege", "beispiel-referenzen"),
                text, "Projekt-Beispiele");
        settleSync();
        stepUntil(id, "1:" + LlmQueue.MORE_TEXT, 50, LlmService.SCAN_PAUSE_MILLIS + 1000L);
        assertEquals("1:" + LlmQueue.MORE_TEXT, llmStatus(id));
        final long published = evidence(id);
        assertTrue("the first reading published evidence", published > 0L);
        final int calls = this.model.calls.get();
        // Solr no longer confirms what the graph published (Solr ahead of the graph, the sync not caught up)
        exec("UPDATE kg_doc SET content_hash = randomblob(16) WHERE doc_id = '" + id + "'");
        final long start = this.clock.get();
        stepUntil(id, "1:" + LlmQueue.TEXT_CUT, 600, 60_000L);
        final long waited = this.clock.get() - start;
        assertEquals("the continuation ends visibly, the page stays done", "1:" + LlmQueue.TEXT_CUT, llmStatus(id));
        assertTrue("it waited with the backoff of an unconfirmed input: " + waited / 60_000L + " min", waited >= 60L * 60_000L);
        assertTrue("and not without end: " + waited / 60_000L + " min", waited <= 4L * 3600_000L);
        assertEquals("no model call about an unconfirmed input", calls, this.model.calls.get());
        assertEquals("the first reading's evidence stays", published, evidence(id));
        assertEquals("nothing left queued", 0L, count("SELECT count(*) FROM kg_llm_work"));
        assertEquals(Set.of(), Set.copyOf(strings("SELECT key FROM kg_meta WHERE key GLOB '" + LlmQueue.LAYOUT_PREFIX + "*'")));
    }
}
