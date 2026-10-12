package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.solr.common.SolrInputDocument;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Pages whose text is missing or thin (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.6), and the refusal samples of the running
 * tier (27.7). A page whose delivered HTML is large but whose text is near empty, or whose text asks for JavaScript,
 * is marked {@code text_missing}: it counts as not covered, nothing is made up for it. A page that is just short is read
 * whole and counted. Example data only.
 */
public class LlmTextStateTest extends LlmFixture {

    private JSONObject status() throws Exception {
        this.clock.addAndGet(LlmService.COUNTS_MILLIS + 1L);
        return this.llm.status();
    }

    private SolrInputDocument page(final String id, final String url, final String text, final Integer htmlBytes) {
        final SolrInputDocument d = doc(id, url, "c1", null, text, "Startseite");
        if (htmlBytes != null) {
            d.setField("size_i", htmlBytes);
        }
        return d;
    }

    private long evidence(final String id) {
        return count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE e.tier = 3 AND d.doc_id = '"
                + id + "'");
    }

    @Test
    public void aPageWhoseTextIsMissingShowsAsNotCovered() throws Exception {
        final String parserLoss = "AAAAAAtxt001";
        final String script = "BBBBBBtxt002";
        final String shortPage = "CCCCCCtxt003";
        addAll(List.of(
                // a large page of which the text holds only its title and a phone line (the rest lost on the way)
                page(parserLoss, "https://www.beispiel-finanz.de/", "FINANZ-PROZESSE, DIE EINFACH FUNKTIONIEREN.\n- **Hotline", 64_000),
                page(script, "https://www.beispiel-app.de/", "Bitte JavaScript aktivieren, um diese Seite zu nutzen.", 3_000),
                page(shortPage, "https://www.beispiel-kurz.de/", "Willkommen. Diese Seite ist im Aufbau.", 2_000)));
        settleSync();
        settleLlm();
        assertEquals("1:" + LlmQueue.TEXT_MISSING, llmStatus(parserLoss));
        assertEquals("1:" + LlmQueue.TEXT_MISSING, llmStatus(script));
        assertEquals("a short page is read whole", "1:", llmStatus(shortPage));
        assertEquals("nothing is made up for a missing text", 0L, evidence(parserLoss) + evidence(script));
        final JSONObject st = status();
        assertEquals(2L, st.getJSONObject("processed").getLong("textMissingDocs"));
        assertEquals(1L, st.getJSONObject("processed").getLong("thinTextDocs"));
        final JSONObject coverage = st.getJSONObject("documents").getJSONObject("coverage");
        assertEquals(2L, coverage.getLong("textMissing"));
        assertEquals(1L, coverage.getLong("whole"));
    }

    @Test
    public void aPageWithMissingTextReadBeforeThisVersionIsMarkedByTheAudit() throws Exception {
        final String id = "AAAAAAtxt011";
        addAll(List.of(page(id, "https://www.beispiel-finanz.de/", "FINANZ-PROZESSE, DIE EINFACH FUNKTIONIEREN.", 64_000),
                page("BBBBBBtxt012", "https://www.beispiel-kurz.de/", "Willkommen. Diese Seite ist im Aufbau.", 2_000)));
        settleSync();
        settleLlm();
        // as an earlier version left it: done without a reason
        exec("UPDATE kg_doc SET llm_reason = NULL");
        exec("DELETE FROM kg_meta WHERE key IN ('" + LlmService.META_AUDIT_AT + "', '" + LlmService.META_AUDIT_END + "')");
        final int calls = this.model.calls.get();
        restart();
        settleLlm();
        assertEquals("1:" + LlmQueue.TEXT_MISSING, llmStatus(id));
        assertEquals("1:", llmStatus("BBBBBBtxt012"));
        assertEquals("no page is asked again", calls, this.model.calls.get());
        final JSONObject p = status().getJSONObject("processed");
        assertEquals(1L, p.getLong("textMissingDocs"));
        assertEquals(1L, p.getLong("thinTextDocs"));
        assertEquals(2L, p.getLong("auditedDocs"));
    }

    @Test
    public void theRefusalSamplesOfTheRunningTierNameThePageAndCarryNoContactData() throws Exception {
        final String id = "AAAAAAhost01";
        add(id, "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        settleSync();
        settleLlm();
        final JSONObject r = this.llm.refusals(null, 50);
        final JSONArray samples = r.getJSONArray("samples");
        assertTrue(r.toString(), samples.length() >= 2);
        for (int i = 0; i < samples.length(); i++) {
            final JSONObject s = samples.getJSONObject(i);
            assertEquals(id, s.getString("docId"));
            assertEquals("muster-pflege.de", s.getString("domain"));
            assertFalse(s.toString(), s.toString().contains("@"));
        }
        // the hallucinated holding: dropped as ungrounded, and the claim about it as a claim about a dropped entity
        assertEquals(1, this.llm.refusals("claim_entity_dropped", 50).getJSONArray("samples").length());
        assertEquals(1, this.llm.refusals("ungrounded", 50).getJSONArray("samples").length());
        final JSONObject summary = status().getJSONObject("refusalSamples");
        assertEquals((long) samples.length(), summary.getLong("size"));
        assertFalse("the status holds no excerpt", summary.toString().contains("excerpt"));
    }
}
