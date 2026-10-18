package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.publish.Assessments;
import net.yacy.scoutro.knowledge.publish.Tier3Review;

/**
 * WP-G: stored LLM facts published before the current rules are reviewed by those rules, without a model request: a
 * dry run counts and samples, an applying run withdraws the findings by automatic assessments (revocable), both resume
 * from a cursor and a second run finds nothing new.
 */
public class Tier3ReviewTest extends LlmFixture {

    private static final String A = "A00001a00001";
    private static final String BENEFIT = "Die Pflegekasse zahlt bis zu 1.612 € für die Verhinderungspflege";

    @Override
    protected String[] settings() {
        return new String[] {KgConfig.COLLECTIONS, "c1,c3", KgConfig.LLM_COLLECTIONS, "c1", KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                KgConfig.VOCAB_PREFIX + "c1", "care", "scoutro.kg.llm.kinds.c1", "nursinghome"};
    }

    private JSONObject step(final boolean apply, final int max, final boolean restart) throws Exception {
        return this.store.write(StorageGuard.WriteClass.MAINTENANCE, 16L << 20,
                tx -> Tier3Review.step(tx, this.cfg, apply, max, restart, this.clock.get()));
    }

    private long tier3(final String predicate) {
        return count("SELECT count(*) FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v"
                + " ON v.term_id = s.pred WHERE e.tier = 3 AND v.name = '" + predicate + "'");
    }

    @Test
    public void legacyLlmFactsAreReviewedByTheRulesWithoutAModel() throws Exception {
        this.model.answer = user -> {
            final String a = LlmServiceTest.Fake.grounded(user);
            if (!a.contains("Tagespflege")) return a;
            return a.substring(0, a.length() - 1) + ",\"values\":[{\"subject\":\"e2\",\"predicate\":\"category\","
                    + "\"quote\":\"Das Haus Lindenhof bietet Tagespflege an\"},{\"subject\":\"e1\",\"predicate\":\"price\",\"quote\":\""
                    + BENEFIT + "\"}]}";
        };
        add(A, "https://www.muster-pflege.de/leistungen", "c1", LD, TEXT + " " + BENEFIT + ".", "Leistungen");
        settleSync();
        settleLlm();
        assertEquals(1, this.model.calls.get());
        assertEquals("the current rules read the benefit as an amount", 1, tier3("monetary_amount"));
        // the graph of an earlier version: the amount stored as a price, and the page later moved out of the care vocabulary
        exec("UPDATE kg_statement SET pred = (SELECT term_id FROM kg_vocab WHERE kind = 2 AND name = 'price') WHERE pred ="
                + " (SELECT term_id FROM kg_vocab WHERE kind = 2 AND name = 'monetary_amount')");
        exec("INSERT OR IGNORE INTO kg_collection (name) VALUES ('c3')");
        exec("UPDATE kg_doc_collection SET coll_id = (SELECT coll_id FROM kg_collection WHERE name = 'c3')");
        assertEquals(1, tier3("price"));
        final long categories = tier3("category");
        assertTrue(categories > 0);
        // a dry run changes nothing
        final JSONObject dry = step(false, 100, false);
        assertEquals(1, dry.getJSONObject("findings").getLong("price_role"));
        assertTrue(dry.getJSONObject("findings").getLong("vocabulary") >= categories);
        assertTrue(dry.getBoolean("done"));
        assertEquals(1, tier3("price"));
        assertEquals(0, count("SELECT count(*) FROM kg_assessment"));
        // applying: automatic assessments, the findings withdrawn, nothing asked
        final JSONObject applied = step(true, 100, false);
        assertTrue(applied.toString(), applied.getLong("assessments") >= 2);
        assertEquals(0, tier3("price"));
        assertEquals(0, tier3("category"));
        assertEquals("relations stay", true, tier3("operates") > 0);
        assertEquals(1, this.model.calls.get());
        assertEquals(applied.getLong("assessments"), count("SELECT count(*) FROM kg_assessment WHERE basis = 'automatic'"
                + " AND reason LIKE 'reassessed_rule:%' AND status = 'active'"));
        // resumed from its cursor: the next step finds no page; a restart finds nothing new (the facts are withdrawn)
        assertEquals(0, step(true, 100, false).getJSONObject("step").getLong("documents"));
        assertEquals(0, step(true, 100, true).getJSONObject("step").getLong("assessments"));
        // the cached LLM result applied again keeps them out (WP-E), still without a model request
        exec("UPDATE kg_doc SET llm_status = NULL, llm_reason = NULL WHERE doc_id = '" + A + "'");
        settleLlm();
        assertEquals(1, this.model.calls.get());
        assertEquals(0, tier3("category"));
        // revocable
        final String id = this.store.read(c -> Assessments.list(c, "active", 10)).getJSONArray("items").getJSONObject(0).getString("id");
        assertTrue(this.store.write(StorageGuard.WriteClass.MAINTENANCE, 16L << 20,
                tx -> Assessments.revoke(tx, this.cfg, id, "admin", this.clock.get())).getInt("restored") >= 0);
    }
}
