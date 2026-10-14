package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.solr.common.SolrInputDocument;
import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgTestSupport;

/**
 * Long pages read to the end of their content (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.5) within the existing limits: the
 * statements per document hold over all readings of a page, and pages read before this version (up to
 * {@code extract.maxInputChars}, marked done without a reason) are read on from where they stopped, a bounded batch per
 * pass, without asking anything of a page again. Collection names are freely chosen.
 */
public class LlmFullReadingTest extends LlmFixture {

    static final String PAGE = LlmLongPageTest.PAGE;
    static final String COLLECTION = "omega-sites";
    static final String URL = "https://www.beispiel-referenzen.de/referenzen/projekt-beispiele/";

    @Override
    protected String[] settings() {
        return settings("5");
    }

    private String[] settings(final String continuationChunks) {
        return new String[] {KgConfig.COLLECTIONS, COLLECTION, KgConfig.LLM_COLLECTIONS, COLLECTION, KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                KgConfig.LLM_CONTINUATION_CHUNKS, continuationChunks};
    }

    private JSONObject processed() throws Exception {
        return this.llm.status().getJSONObject("processed");
    }

    private void restartWith(final String... settings) throws Exception {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled(settings));
        restart();
    }

    private long tier3(final String id) {
        return count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE e.tier = 3 AND d.doc_id = '"
                + id + "'");
    }

    /**
     * One project entity per request: the first project the chunk names, quoted verbatim. An organisation, which the validator
     * does not judge by its context: a customer project no offer states is no service of the page (0.10.7).
     */
    private static String projectAnswer(final String user) {
        final Matcher m = Pattern.compile("Projekt (\\d+): Einführung einer Finanzplattform").matcher(user);
        if (!m.find()) {
            return "{\"entities\":[],\"claims\":[]}";
        }
        return "{\"entities\":[{\"id\":\"e1\",\"type\":\"organization\",\"name\":\"Projekt " + m.group(1) + "\",\"quote\":\"" + m.group()
                + "\"}],\"claims\":[]}";
    }

    @Test
    public void thePagesStatementLimitHoldsOverAllItsReadings() throws Exception {
        restartWith(KgConfig.COLLECTIONS, COLLECTION, KgConfig.LLM_COLLECTIONS, COLLECTION, KgConfig.EXTRACT_MAX_STATEMENTS_PER_DOC, "4");
        this.model.answer = LlmFullReadingTest::projectAnswer;
        final String text = LlmContinuationTest.veryLong();
        add(PAGE, URL, COLLECTION, null, text, "Projekt-Beispiele");
        settleSync();
        settleLlm();
        assertEquals("the rest stays unread, visibly", "1:" + LlmQueue.TEXT_CUT, llmStatus(PAGE));
        assertEquals("the page's statements of the tier stay within the limit", 4L, tier3(PAGE));
        assertEquals(1L, processed().getLong("statementCapDocs"));
        // the first reading and one pass: the pass after it asks nothing once the limit is reached
        assertEquals(net.yacy.scoutro.knowledge.extract.LlmExtractor.chunks(text, this.cfg.extractMaxInputChars).size()
                + this.cfg.llmContinuationChunks, this.model.calls.get());
        assertEquals(0L, count("SELECT count(*) FROM kg_meta WHERE key GLOB '" + LlmQueue.LAYOUT_PREFIX + "*'"));
    }

    @Test
    public void aLongPageReadBeforeThisVersionIsReadOnFromWhereItStopped() throws Exception {
        // the reading of an earlier version: up to extract.maxInputChars, marked done without a reason
        restartWith(settings("0"));
        final String text = LlmLongPageTest.page();
        final String legal = LlmLongPageTest.page();
        addAll(List.of(doc(PAGE, URL, COLLECTION, LD.replace("muster-pflege", "beispiel-referenzen"), text, "Projekt-Beispiele"),
                doc("LLLLLLlgl001", "https://www.beispiel-recht.de/datenschutz/", COLLECTION, LD.replace("muster-pflege", "beispiel-recht"),
                        legal, "Datenschutz"),
                imprint('S', 1, COLLECTION)));
        settleSync();
        settleLlm();
        exec("UPDATE kg_doc SET llm_reason = NULL");
        exec("DELETE FROM kg_meta WHERE key IN ('" + LlmService.META_AUDIT_AT + "', '" + LlmService.META_AUDIT_END + "')");
        assertTrue("the earlier reading did not reach the evidence", this.model.users.stream().noneMatch(u -> u.contains(LlmLongPageTest.QUOTE)));
        final int before = this.model.calls.get();
        // the upgrade: continuations on
        restartWith(settings("5"));
        settleLlm();
        assertEquals("read on to the end of its content", "1:" + LlmQueue.FULL_TEXT, llmStatus(PAGE));
        assertEquals("a legal page is not continued, its rest shows as unread", "1:" + LlmQueue.TEXT_CUT, llmStatus("LLLLLLlgl001"));
        assertEquals("a page read whole stays as it is", "1:", llmStatus(id('S', 1)));
        final List<String> after = this.model.users.subList(before, this.model.users.size());
        assertEquals("only the continuation asks: nothing of the earlier reading again", processed().getLong("continuationCalls"),
                after.size());
        assertTrue("it starts at most the overlap before where the earlier reading stopped",
                after.stream().noneMatch(u -> u.contains(text.substring(12_000 - LlmService.CONTINUATION_OVERLAP - 300,
                        12_000 - LlmService.CONTINUATION_OVERLAP - 200))));
        assertEquals(List.of("text:" + text.indexOf(LlmLongPageTest.QUOTE) + "+" + LlmLongPageTest.QUOTE.length()),
                strings("SELECT e.locator FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
                        + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred"
                        + " WHERE e.tier = 3 AND d.doc_id = '" + PAGE + "' AND v.name = 'operates'"));
        final JSONObject p = processed();
        assertEquals(3L, p.getLong("auditedDocs"));
        assertEquals(1L, p.getLong("backfilledDocs"));
        assertEquals(1L, p.getLong("textCutDocs"));
        this.clock.addAndGet(LlmService.COUNTS_MILLIS + 1L);
        final JSONObject audit = this.llm.status().getJSONObject("continuation").getJSONObject("audit");
        assertTrue(audit.toString(), audit.getBoolean("done"));
        assertEquals(0L, count("SELECT count(*) FROM kg_meta WHERE key GLOB '" + LlmQueue.LAYOUT_PREFIX + "*'"));
    }

    @Test
    public void theAuditChecksABoundedBatchPerPass() throws Exception {
        final List<SolrInputDocument> docs = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            docs.add(imprint('T', i, COLLECTION));
        }
        addAll(docs);
        settleSync();
        settleLlm();
        exec("DELETE FROM kg_meta WHERE key IN ('" + LlmService.META_AUDIT_AT + "', '" + LlmService.META_AUDIT_END + "')");
        restart();
        final int calls = this.model.calls.get();
        this.llm.step();
        assertEquals("one pass checks a batch", (long) LlmService.AUDIT_BATCH, processed().getLong("auditedDocs"));
        settleLlm(() -> {
            try {
                return processed().getLong("auditedDocs") == 30L;
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals("pages read whole are not asked again", calls, this.model.calls.get());
        assertEquals(30L, count("SELECT count(*) FROM kg_doc WHERE llm_status = 1 AND llm_reason IS NULL"));
        // the audit is done: a further pass checks nothing
        this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
        this.llm.step();
        assertEquals(30L, processed().getLong("auditedDocs"));
    }
}
