package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.solr.common.SolrInputDocument;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgTestSupport;

/**
 * The "stranded pending" documents of the 0.10.0 coverage analysis (11.10.2026, section 10): a queue item completed
 * without a status for its document, behind the scan, while the crawl keeps adding pages. Each test reproduces one way
 * into that state with isolated fixtures and a local model, and only uses what the LLM tier offered before the fix,
 * so the same tests run against the base as the counter-check.
 * <p>
 * The crawl is simulated by one new page before every step: the scan always finds new work ahead of its position,
 * as on the production graph (+280 to 340 documents per hour), so nothing behind it is found by a wrap-around.
 */
public class LlmStrandedTest extends LlmFixture {

    private static final String A = "A00001a00001";
    private int crawled;

    /** One crawl step: a new page is indexed and published, then the LLM tier takes one step a minute later. */
    private void crawlStep() throws Exception {
        this.crawled++;
        addAll(List.of(imprint('N', this.crawled, "c1")));
        settleSync();
        this.llm.step();
        this.clock.addAndGet(61_000L);
    }

    private void crawlSteps(final int n) throws Exception {
        for (int i = 0; i < n; i++) {
            crawlStep();
        }
    }

    private String strandedMessage() {
        return "pending behind the scan, neither marked nor queued: " + llmStatus(A) + ", queued " + queued(A) + ", stranded "
                + stranded() + ", status " + this.llm.status();
    }

    @Test
    public void aPageWhoseInputTheWorkerCannotConfirmIsNotDroppedBehindTheScan() throws Exception {
        addAll(List.of(imprint('A', 1, "c1")));
        settleSync();
        // the graph lags behind Solr for this page (the sync has not published the new input yet)
        final String truth = strings("SELECT hex(input_hash) || ':' || coalesce(hex(content_hash), '') FROM kg_doc WHERE doc_id = '" + A
                + "'").get(0);
        exec("UPDATE kg_doc SET input_hash = randomblob(16), content_hash = CASE WHEN content_hash IS NULL THEN NULL ELSE randomblob(16)"
                + " END WHERE doc_id = '" + A + "'");
        crawlSteps(12);
        assertFalse("the worker must not publish an answer about an input it cannot confirm", "1:".equals(llmStatus(A)));
        // the sync catches up
        final String[] h = truth.split(":", -1);
        exec("UPDATE kg_doc SET input_hash = x'" + h[0] + "', content_hash = " + (h[1].isEmpty() ? "NULL" : "x'" + h[1] + "'")
                + " WHERE doc_id = '" + A + "'");
        crawlSteps(20);
        assertEquals(strandedMessage(), "1:", llmStatus(A));
        assertEquals(strandedMessage(), 0L, stranded());
    }

    @Test
    public void aChangeDuringTheCallKeepsThePageToDoWhileTheCrawlContinues() throws Exception {
        addAll(List.of(imprint('A', 1, "c1")));
        settleSync();
        final SolrInputDocument changed = imprint('A', 1, "c1");
        changed.setField("text_t", "Impressum. Die Muster Pflege gGmbH betreibt das Haus Sonnenhof.");
        changed.setField("exact_signature_l", 42L);
        this.model.during = () -> {
            try {
                addAll(List.of(changed));
                settleSync();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        };
        this.llm.step();
        assertEquals("the answer about the old text is not published", 0L,
                count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE e.tier = 3 AND d.doc_id = '" + A
                        + "'"));
        crawlSteps(20);
        assertEquals(strandedMessage(), "1:", llmStatus(A));
        assertTrue("the new text was read", strings("SELECT n.obj_val FROM kg_statement n JOIN kg_evidence e ON e.stmt_rowid = n.stmt_rowid"
                + " JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE e.tier = 3 AND d.doc_id = '" + A + "'").contains("Haus Sonnenhof"));
        assertEquals(strandedMessage(), 0L, stranded());
    }

    @Test
    public void aNewExtractorIdentityAloneDoesNotHoldBackTheLlmTier() throws Exception {
        addAll(List.of(imprint('A', 1, "c1")));
        settleSync();
        // a changed configuration (jobs in another collection) changes every input hash until the sync re-extracted the page;
        // the page text, title, site and links (what the LLM tier reads) are the same
        final KgConfig other = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1,c2", KgConfig.LLM_COLLECTIONS, "c1,c2",
                KgConfig.JOBS_COLLECTIONS, "c2"));
        SolrDoc.configure(other);
        try {
            crawlSteps(8);
            assertEquals(strandedMessage(), "1:", llmStatus(A));
            assertEquals("no answer was thrown away", 0L, this.llm.status().getJSONObject("processed").getLong("abortedChanged"));
        } finally {
            SolrDoc.configure(this.cfg);
        }
    }

    @Test
    public void aReopenedPageBehindTheScanIsReadAgainWithoutARestart() throws Exception {
        addAll(List.of(imprint('A', 1, "c1")));
        settleSync();
        crawlSteps(2);
        assertEquals("1:", llmStatus(A));
        // a recrawl with a new text: the publisher makes the page to do again
        final SolrInputDocument changed = imprint('A', 1, "c1");
        changed.setField("text_t", "Impressum. Die Muster Pflege gGmbH betreibt das Haus Sonnenhof.");
        changed.setField("exact_signature_l", 43L);
        addAll(List.of(changed));
        settleSync();
        assertEquals("null:", llmStatus(A));
        crawlSteps(20);
        assertEquals(strandedMessage(), "1:", llmStatus(A));
        assertEquals(strandedMessage(), 0L, stranded());
    }

    @Test
    public void everyCollectionGetsWorkFromTheFirstScan() throws Exception {
        final List<SolrInputDocument> early = new ArrayList<>();
        for (int i = 1; i <= LlmService.SCAN_BATCH + 20; i++) {
            early.add(imprint('B', i, "c1"));
        }
        addAll(early);
        settleSync();
        addAll(List.of(imprint('Z', 1, "c2"), imprint('Z', 2, "c2"), imprint('Z', 3, "c2")));
        settleSync();
        assertTrue("the late collection has higher row IDs", count("SELECT min(doc_rowid) FROM kg_doc WHERE doc_id GLOB 'Z*'")
                > count("SELECT max(doc_rowid) FROM kg_doc WHERE doc_id GLOB 'B*'"));
        this.llm.step();
        assertEquals("the late collection is not left behind the early one: " + this.llm.status(), 3L,
                count("SELECT count(*) FROM kg_llm_work WHERE doc_id GLOB 'Z*'"));
    }

    @Test
    public void anOlderQueuedPageIsNotStarvedByNewerPriorityPages() throws Exception {
        // a reference page (priority 5) queued first, then a steady stream of imprint pages (priority 1)
        add("P00001p00001", "https://www.p1-pflege.de/referenzen/projekt-beispiele/", "c1", LD.replace("muster-pflege", "p1-pflege"), TEXT,
                "Projekt-Beispiele");
        settleSync();
        this.llm.step(); // the scan queues it; nothing else is queued, so it is read now
        assertEquals("1:", llmStatus("P00001p00001"));
        add("P00002p00002", "https://www.p2-pflege.de/referenzen/", "c1", LD.replace("muster-pflege", "p2-pflege"), TEXT,
                "Referenzen");
        settleSync();
        final List<SolrInputDocument> first = new ArrayList<>();
        for (int i = 100; i < 110; i++) {
            first.add(imprint('N', i, "c1"));
        }
        addAll(first);
        settleSync();
        this.llm.step(); // queues the reference page and ten imprints, reads one imprint
        assertTrue(queued("P00002p00002"));
        for (int i = 0; i < 40 && queued("P00002p00002"); i++) {
            addAll(List.of(imprint('N', 200 + i, "c1"), imprint('N', 300 + i, "c1")));
            settleSync();
            this.llm.step();
            this.clock.addAndGet(10 * 60_000L);
        }
        assertEquals("a queued page is read within hours although newer imprint pages keep arriving: " + this.llm.status(), "1:",
                llmStatus("P00002p00002"));
    }
}
