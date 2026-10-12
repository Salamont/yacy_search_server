package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * A page with a large list of known entities (the worst case of the 0.10.0 coverage analysis, section 11: 40 entities
 * of 300 characters, about 12,800 characters or 3,200 tokens next to a system prompt of 3,283 characters and a chunk
 * of 4,000 in a window of 4,096 tokens). Uses only what the LLM tier offered before the budget, so it also runs
 * against the base as the counter-check.
 */
public class LlmBudgetTest extends LlmFixture {

    private static final String A = "A00001a00001";

    /** JSON-LD with the page's operator first and 39 further organisations with names of 300 characters. */
    static String bigLd() {
        final StringBuilder sb = new StringBuilder("{\"@context\":\"https://schema.org\",\"@graph\":[");
        sb.append("{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\",\"url\":\"https://www.a1-pflege.de/\"}");
        for (int i = 1; i < 40; i++) {
            final String base = "Partnerorganisation " + i + " mit einem sehr langen eingetragenen Namen ";
            sb.append(",{\"@type\":\"Organization\",\"name\":\"").append(base.repeat(300 / base.length() + 1).substring(0, 300).trim())
                    .append("\"}");
        }
        return sb.append("]}").toString();
    }

    @Test
    public void aLargeEntityListIsCutToTheReferencesTheChunkNeeds() throws Exception {
        add(A, "https://www.a1-pflege.de/impressum", "c1", bigLd(), TEXT);
        settleSync();
        assertTrue("tiers 1 and 2 know many entities of the page",
                count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE d.doc_id = '" + A + "'") > 20L);
        settleLlm();
        assertEquals("1:", llmStatus(A));
        assertEquals(1, this.model.users.size());
        final String user = this.model.users.get(0);
        assertTrue(user, user.contains("k1: organization \"Muster Pflege gGmbH\""));
        // the others follow in their order as far as they fit (a plain value needs no name in its quote)
        assertTrue(user, user.contains("Partnerorganisation 1 mit"));
        assertFalse("the last ones are left out: " + user.length() + " characters", user.contains("Partnerorganisation 39 mit"));
        final org.json.JSONObject ctx = this.llm.status().getJSONObject("context");
        assertTrue("the request fits: " + ctx, ctx.getInt("promptTokensEstimatedMax") + ctx.getInt("answerReserveTokens") <= ctx.getInt("windowTokens"));
        assertEquals("the reference k1 of the answer resolved to the operator", List.of("offers Tagespflege q2", "operates Haus Am See q2",
                "operates Haus Lindenhof q2"), strings("SELECT v.name || ' ' || (SELECT obj_val FROM kg_statement n JOIN kg_vocab nv ON"
                        + " nv.term_id = n.pred WHERE n.subj = s.obj_ent AND nv.name = 'name' LIMIT 1) || ' q' || s.quality FROM kg_statement s"
                        + " JOIN kg_vocab v ON v.term_id = s.pred JOIN kg_evidence e ON e.stmt_rowid = s.stmt_rowid AND e.tier = 3"
                        + " WHERE s.obj_ent IS NOT NULL ORDER BY 1"));
        final long trimmed = this.llm.status().getJSONObject("processed").getLong("knownTrimmed");
        assertTrue("trimmed " + trimmed, trimmed > 0L && trimmed < 39L);
    }
}
