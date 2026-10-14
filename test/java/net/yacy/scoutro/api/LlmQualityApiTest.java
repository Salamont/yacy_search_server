package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.KnowledgePrompt;
import net.yacy.scoutro.knowledge.quality.ModelQualityCheck;
import net.yacy.scoutro.knowledge.quality.QualityCases;

/**
 * The API of the model quality check ({@code /scoutro/api/v1/kg/llm-quality}, docs/SCOUTRO_LLM_QUALITY_CHECK.md): the
 * candidates are the stored production rows without their keys, and refusals keep their status. No model is called here.
 */
public class LlmQualityApiTest {

    private static final String ROWS = "[{\"service\":\"OPENROUTER\",\"model\":\"vendor/model-a\",\"hoststub\":\"https://user:pw@router.example/api/\","
            + "\"api_key\":\"sk-row-secret-0123456789\",\"max_tokens\":\"65536\",\"knowledge\":true},"
            + "{\"service\":\"OLLAMA\",\"model\":\"local-model:8b\",\"hoststub\":\"http://127.0.0.1:11434\",\"api_key\":\"\",\"max_tokens\":\"2048\"},"
            + "{\"service\":\"OLLAMA\",\"model\":\"\",\"hoststub\":\"http://127.0.0.1:11434\"}]";
    private static final String CAPS = "{\"OLLAMA|http://127.0.0.1:11434|local-model:8b\":{\"format\":\"supported\",\"format_probe\":4,"
            + "\"thinking\":\"unsupported\",\"thinking_probe\":2}}";

    private static ModelQualityCheck check(final List<ModelQualityCheck.Candidate> candidates, final boolean graph) throws Exception {
        final File dir = Files.createTempDirectory("scoutro-quality-api").toFile();
        return new ModelQualityCheck(new ModelQualityCheck.Environment() {
            @Override
            public List<ModelQualityCheck.Candidate> candidates() {
                return candidates;
            }

            @Override
            public ModelQualityCheck.Inputs inputs() {
                return graph ? new ModelQualityCheck.Inputs(k -> null, KnowledgePrompt.defaults()) : null;
            }

            @Override
            public File dir() {
                return dir;
            }
        });
    }

    private static KnowledgeApi.Body body(final JSONObject o) {
        return () -> o;
    }

    private static final String[] BASE = {"", "v1", "kg", "llm-quality"};

    private static String[] path(final String sub) {
        return new String[] {"", "v1", "kg", "llm-quality", sub};
    }

    @Test
    public void theCandidatesAreTheProductionRowsWithoutTheirKeys() throws Exception {
        final List<ModelQualityCheck.Candidate> c = LlmQualityApi.candidates(ROWS, CAPS, h -> h.contains("11434") ? 8192 : 0);
        assertEquals(2, c.size()); // a row without a model is no candidate
        final JSONObject router = c.get(0).json();
        assertEquals("OPENROUTER|https://user:pw@router.example/api|vendor/model-a", router.optString("key"));
        assertEquals("https://router.example/api", router.optString("endpoint"));
        assertTrue(router.optBoolean("activeForKnowledge"));
        assertTrue(router.optBoolean("apiKeySet"));
        assertEquals(65536, router.optInt("maxTokens"));
        assertFalse(router.optBoolean("numCtxConfigured"));
        final JSONObject local = c.get(1).json();
        assertEquals("supported", local.optString("formatCapability"));
        assertEquals("unsupported", local.optString("thinkingCapability"));
        assertEquals(8192, local.optInt("numCtx"));
        assertFalse(local.optBoolean("apiKeySet"));
        final ModelQualityCheck q = check(c, true);
        final String status = LlmQualityApi.route(q, "GET", BASE, Map.of(), body(new JSONObject())).toString();
        assertFalse(status.contains("sk-row-secret"));
        assertFalse(status.contains("user:pw@router.example/api\",\"endpoint")); // the endpoint is shown without user information
        assertTrue(status.contains("\"status\":\"unchecked\""));
        assertTrue(status.contains("\"version\":\"" + QualityCases.VERSION + "\""));
    }

    @Test
    public void refusalsKeepTheirStatus() throws Exception {
        final List<ModelQualityCheck.Candidate> c = LlmQualityApi.candidates(ROWS, CAPS, h -> 0);
        final ModelQualityCheck q = check(c, true);
        expect(404, "candidate_unknown", () -> LlmQualityApi.route(q, "POST", BASE, Map.of(),
                body(KgJson.obj("action", "start", "candidate", "OLLAMA|http://127.0.0.1:11434|missing", "acceptUnknownCost", true))));
        expect(400, "cost_unknown", () -> LlmQualityApi.route(q, "POST", BASE, Map.of(),
                body(KgJson.obj("action", "start", "candidate", c.get(1).key()))));
        expect(400, "invalid", () -> LlmQualityApi.route(q, "POST", BASE, Map.of(), body(KgJson.obj("action", "delete"))));
        expect(409, "not_running", () -> LlmQualityApi.route(q, "POST", BASE, Map.of(), body(KgJson.obj("action", "cancel"))));
        expect(400, "invalid_inputPrice", () -> LlmQualityApi.route(q, "POST", BASE, Map.of(),
                body(KgJson.obj("action", "start", "candidate", c.get(1).key(), "price", KgJson.obj("inputPerMillion", "-1", "outputPerMillion", "1")))));
        expect(400, "invalid_currency", () -> LlmQualityApi.route(q, "GET", path("plan"),
                Map.of("candidate", c.get(1).key(), "inputPrice", "1", "outputPrice", "2", "currency", "dollars"), body(new JSONObject())));
        expect(400, "invalid", () -> LlmQualityApi.route(q, "GET", path("plan"), Map.of("candidate", c.get(1).key(), "apiKey", "x"),
                body(new JSONObject())));
        expect(400, "invalid", () -> LlmQualityApi.route(q, "GET", BASE, Map.of("x", "1"), body(new JSONObject())));
        expect(405, "method_not_allowed", () -> LlmQualityApi.route(q, "DELETE", BASE, Map.of(), body(new JSONObject())));
        expect(404, "not_found", () -> LlmQualityApi.route(q, "GET", path("other"), Map.of(), body(new JSONObject())));
        expect(503, "unavailable", () -> LlmQualityApi.route(null, "GET", BASE, Map.of(), body(new JSONObject())));
        final ModelQualityCheck off = check(c, false);
        expect(409, "kg_not_running", () -> LlmQualityApi.route(off, "POST", BASE, Map.of(),
                body(KgJson.obj("action", "start", "candidate", c.get(1).key(), "acceptUnknownCost", true))));
        // the test set and an unchecked result read without a model call
        assertEquals(QualityCases.CASES.size(), LlmQualityApi.route(q, "GET", path("testset"), Map.of(), body(new JSONObject()))
                .optJSONArray("cases").length());
        assertEquals("unchecked", LlmQualityApi.route(q, "GET", path("result"), Map.of("candidate", c.get(1).key()), body(new JSONObject()))
                .optString("status"));
    }

    private interface Call {
        void run() throws Exception;
    }

    private static void expect(final int status, final String code, final Call call) throws Exception {
        try {
            call.run();
            fail("expected " + status + " " + code);
        } catch (final ApiException e) {
            assertEquals(e.code(), status, e.status());
            assertTrue(e.code() + " is not " + code, e.code().startsWith(code));
        }
    }
}
