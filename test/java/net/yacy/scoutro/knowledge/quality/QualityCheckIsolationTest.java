package net.yacy.scoutro.knowledge.quality;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import net.yacy.scoutro.knowledge.sync.LlmFixture;

/**
 * A quality check beside a running graph writes nothing of it (docs/SCOUTRO_LLM_QUALITY_CHECK.md, 6): no entity,
 * statement, evidence, document, extraction cache entry, queue entry, observation or meta value changes, and the graph's
 * own model is not asked. Example data only.
 */
public class QualityCheckIsolationTest extends LlmFixture {

    private static final String LD = "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\","
            + "\"url\":\"https://www.muster-pflege.de\"}";
    private static final List<String> TABLES = List.of("kg_doc", "kg_doc_collection", "kg_entity", "kg_entity_key", "kg_statement",
            "kg_evidence", "kg_extraction", "kg_extractor", "kg_llm_work", "kg_observation", "kg_observation_event", "kg_event", "kg_change",
            "kg_meta", "kg_work");

    private Map<String, Object> graph() {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final String t : TABLES) {
            out.put(t, count("SELECT count(*) FROM " + t));
        }
        out.put("meta", strings("SELECT key || '=' || value FROM kg_meta ORDER BY key"));
        return out;
    }

    @Test
    public void aCheckWritesNothingOfTheGraph() throws Exception {
        add("AAAAAAiso001", "https://www.muster-pflege.de/leistungen", "c1", LD,
                "Leistungen der Muster Pflege gGmbH. Die Muster Pflege gGmbH bietet Tagespflege in Musterstadt an.");
        settleSync();
        settleLlm();
        final Map<String, Object> before = graph();
        assertTrue(((Number) before.get("kg_doc")).longValue() > 0);
        try (QualityFixture f = new QualityFixture()) {
            final ModelQualityCheck q = f.check();
            q.start(f.key(), new ModelQualityCheck.Price(BigDecimal.ONE, BigDecimal.ONE, "USD", "entered"), false, null, null);
            q.await(60_000L);
            assertEquals(ModelQualityCheck.PASSED, q.result(f.key()).optString("status"));
            assertEquals(QualityCases.CASES.size(), f.requests.size());
        }
        settleLlm();
        assertEquals(before, graph());
    }
}
