package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import net.yacy.ai.LLM;

/**
 * Structured output of the LLM tier negotiated per model (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3), against a local fake
 * OpenAI-compatible endpoint that accepts, ignores or rejects the format. Nothing leaves 127.0.0.1. The validator stays
 * the authority in every mode.
 */
public class StructuredOutputNegotiationTest {

    private HttpServer server;
    private final List<JSONObject> requests = new CopyOnWriteArrayList<>();
    /** ok: answers; reject: 400 for any response_format; always400: 400 for every request. */
    private final AtomicReference<String> endpoint = new AtomicReference<>("ok");
    private final AtomicReference<String> content = new AtomicReference<>("{\"entities\":[],\"claims\":[],\"values\":[]}");

    @Before
    public void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/v1/chat/completions", exchange -> {
            final JSONObject body;
            try {
                body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } catch (final Exception e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            this.requests.add(body);
            final String e = this.endpoint.get();
            final int status = "always400".equals(e) || "reject".equals(e) && body.has("response_format") ? 400 : 200;
            final byte[] out = status != 200 ? new byte[0]
                    : ("{\"choices\":[{\"message\":{\"content\":" + JSONObject.quote(this.content.get()) + "},\"finish_reason\":\"stop\"}]}")
                            .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
            exchange.close();
        });
        this.server.start();
    }

    @After
    public void stop() {
        this.server.stop(0);
    }

    private YacyLlmClient client(final LLM.LLMType type, final String capability) {
        final LLM llm = new LLM("http://127.0.0.1:" + this.server.getAddress().getPort(), "", 512, type);
        final LLM.LLMModel m = new LLM.LLMModel(llm, "llama3.1:8b", false, false);
        m.formatCapability = capability;
        return new YacyLlmClient(() -> m);
    }

    private String ask(final YacyLlmClient c, final String setting) throws IOException {
        return c.complete(LlmExtractor.SYSTEM_PROMPT, "user", LlmExtractor.SCHEMA, 5000L, setting);
    }

    private static long requests(final JSONObject so, final String kind) throws Exception {
        return so.getJSONObject("requests").getLong(kind);
    }

    // A) the provider reports schema support: the schema with the OpenAI contract, no fallback, shown as enforced

    @Test
    public void aSupportedFormatSendsTheCompleteJsonSchema() throws Exception {
        final YacyLlmClient c = client(LLM.LLMType.OPENAI, "supported");
        ask(c, "auto");
        assertEquals(1, this.requests.size());
        final JSONObject rf = this.requests.get(0).getJSONObject("response_format");
        assertEquals("json_schema", rf.getString("type"));
        final JSONObject js = rf.getJSONObject("json_schema");
        assertEquals("scoutro_knowledge_extraction", js.getString("name"));
        assertTrue(js.getBoolean("strict"));
        assertEquals("the extraction schema unchanged", LlmExtractor.SCHEMA.toString(), js.getJSONObject("schema").toString());
        final JSONObject so = c.structuredOutput("auto");
        assertEquals("schema_enforced", so.getString("mode"));
        assertEquals("supported", so.getString("capability"));
        assertEquals("capability_supported", so.getString("reason"));
        assertEquals(1L, requests(so, "json_schema"));
        assertEquals(0L, so.getLong("rejections"));
        assertFalse(so.getBoolean("withoutSchema"));
        assertTrue(so.isNull("lastRejection"));
    }

    // B) the provider reports format unsupported: no response_format at all, prompt and validator only

    @Test
    public void anUnsupportedFormatSendsNoResponseFormat() throws Exception {
        final YacyLlmClient c = client(LLM.LLMType.OPENAI, "unsupported");
        ask(c, "auto");
        ask(c, "auto");
        assertEquals(2, this.requests.size());
        for (final JSONObject r : this.requests) {
            assertFalse("no unsupported parameter is sent", r.has("response_format"));
            assertEquals("the prompt is the same", LlmExtractor.SYSTEM_PROMPT, r.getJSONArray("messages").getJSONObject(0).getString("content"));
        }
        final JSONObject so = c.structuredOutput("auto");
        assertEquals("validator_only", so.getString("mode"));
        assertEquals("none", so.getString("request"));
        assertEquals("capability_unsupported", so.getString("reason"));
        assertEquals(2L, requests(so, "none"));
        assertEquals(0L, requests(so, "json_schema"));
    }

    // C) JSON mode only: json_object, never shown as an enforced schema

    @Test
    public void jsonModeSendsOnlyJsonObject() throws Exception {
        final YacyLlmClient c = client(LLM.LLMType.OPENAI, "unsupported");
        ask(c, "json_object");
        final JSONObject rf = this.requests.get(0).getJSONObject("response_format");
        assertEquals("json_object", rf.getString("type"));
        assertFalse(rf.has("json_schema"));
        final JSONObject so = c.structuredOutput("json_object");
        assertEquals("json_mode", so.getString("mode"));
        assertEquals("json_object", so.getString("request"));
        assertEquals(1L, requests(so, "json_object"));
        assertEquals(0L, requests(so, "json_schema"));
    }

    // D) schema support claimed, the endpoint answers 400: one fallback, counted and shown, remembered, no loop

    @Test
    public void aRejectedSchemaFallsBackOnceVisiblyAndIsRemembered() throws Exception {
        this.endpoint.set("reject");
        final YacyLlmClient c = client(LLM.LLMType.OPENAI, "supported");
        final long before = System.currentTimeMillis();
        ask(c, "auto");
        assertEquals("the call and one retry", 2, this.requests.size());
        assertTrue(this.requests.get(0).has("response_format"));
        assertFalse(this.requests.get(1).has("response_format"));
        JSONObject so = c.structuredOutput("auto");
        assertEquals("fallback_after_rejection", so.getString("mode"));
        assertEquals("http_400", so.getString("reason"));
        assertTrue(so.getBoolean("withoutSchema"));
        assertEquals(1L, so.getLong("rejections"));
        assertEquals("http_400", so.getJSONObject("lastRejection").getString("code"));
        assertTrue(so.getJSONObject("lastRejection").getLong("at") >= before);
        ask(c, "auto");
        assertEquals("remembered: one request without the format", 3, this.requests.size());
        assertFalse(this.requests.get(2).has("response_format"));
        so = c.structuredOutput("auto");
        assertEquals(1L, so.getLong("rejections"));
        assertEquals(1L, requests(so, "json_schema"));
        assertEquals(2L, requests(so, "none"));
    }

    @Test
    public void aRetryThatFailsTooEndsTheCallWithoutALoop() throws Exception {
        this.endpoint.set("always400");
        final YacyLlmClient c = client(LLM.LLMType.OPENAI, "unknown");
        try {
            ask(c, "auto");
            fail("400 without the format too");
        } catch (final IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("400"));
        }
        assertEquals("one call, one retry", 2, this.requests.size());
        final JSONObject so = c.structuredOutput("auto");
        assertFalse("not remembered: the endpoint failed without the format too", so.getBoolean("withoutSchema"));
        assertEquals(1L, so.getLong("rejections"));
        assertEquals("schema_unverified", so.getString("mode"));
    }

    // E) the former behaviour for an unknown capability: the schema, now with its name, shown as unverified

    @Test
    public void anUnknownCapabilityKeepsTheSchemaButDoesNotClaimEnforcement() throws Exception {
        for (final LLM.LLMType type : List.of(LLM.LLMType.OPENAI, LLM.LLMType.OPENROUTER, LLM.LLMType.LMSTUDIO,
                LLM.LLMType.OTHER)) {
            this.requests.clear();
            final YacyLlmClient c = client(type, "unknown");
            ask(c, "auto");
            final JSONObject js = this.requests.get(0).getJSONObject("response_format").getJSONObject("json_schema");
            assertEquals(type.name(), "scoutro_knowledge_extraction", js.getString("name"));
            assertTrue(js.getBoolean("strict"));
            assertTrue(js.has("schema"));
            assertEquals("schema_unverified", c.structuredOutput("auto").getString("mode"));
            assertEquals("capability_unknown", c.structuredOutput("auto").getString("reason"));
        }
    }

    @Test
    public void theSettingOverridesTheCapabilityButNotTheTruth() throws Exception {
        final YacyLlmClient unsupported = client(LLM.LLMType.OPENAI, "unsupported");
        ask(unsupported, "json_schema");
        assertTrue(this.requests.get(0).has("response_format"));
        assertEquals("asked for, but not reported as enforced", "schema_unverified", unsupported.structuredOutput("json_schema").getString("mode"));
        assertEquals("setting", unsupported.structuredOutput("json_schema").getString("reason"));
        final YacyLlmClient supported = client(LLM.LLMType.OPENAI, "supported");
        ask(supported, "none");
        assertFalse(this.requests.get(1).has("response_format"));
        assertEquals("validator_only", supported.structuredOutput("none").getString("mode"));
        assertEquals("schema_enforced", supported.structuredOutput("json_schema").getString("mode"));
        // an unknown setting value is auto (KgConfig refuses it before)
        assertEquals("schema_enforced", supported.structuredOutput("xml").getString("mode"));
        assertEquals("auto", supported.structuredOutput("xml").getString("setting"));
    }

    @Test
    public void thePlanCoversEveryCombination() throws Exception {
        final String[][] cases = {
                // setting, capability, rejected, mode, request, reason
                {"auto", "supported", "false", "schema_enforced", "json_schema", "capability_supported"},
                {"auto", "unsupported", "false", "validator_only", "none", "capability_unsupported"},
                {"auto", "unknown", "false", "schema_unverified", "json_schema", "capability_unknown"},
                {"auto", "ignored", "false", "schema_unverified", "json_schema", "capability_ignored"},
                {"auto", null, "false", "schema_unverified", "json_schema", "capability_unknown"},
                {"auto", "garbage", "false", "schema_unverified", "json_schema", "capability_unknown"},
                {"auto", "supported", "true", "fallback_after_rejection", "none", "http_400"},
                {"json_schema", "supported", "false", "schema_enforced", "json_schema", "setting"},
                {"json_schema", "unsupported", "false", "schema_unverified", "json_schema", "setting"},
                {"json_schema", "unknown", "true", "fallback_after_rejection", "none", "http_400"},
                {"json_object", "supported", "false", "json_mode", "json_object", "setting"},
                {"json_object", "unknown", "true", "fallback_after_rejection", "none", "http_400"},
                {"none", "supported", "false", "validator_only", "none", "setting"},
                {"none", "supported", "true", "validator_only", "none", "setting"}};
        for (final String[] c : cases) {
            final YacyLlmClient.Plan p = YacyLlmClient.plan(c[0], c[1], Boolean.parseBoolean(c[2]), true);
            assertEquals(String.join(",", c[0], String.valueOf(c[1]), c[2]), c[3] + "/" + c[4] + "/" + c[5], p.mode + "/" + p.request + "/" + p.reason);
        }
        final YacyLlmClient.Plan noSchema = YacyLlmClient.plan("auto", "supported", false, false);
        assertEquals("validator_only/none/no_schema", noSchema.mode + "/" + noSchema.request + "/" + noSchema.reason);
    }

    // F) an answer that breaks the schema still meets the unchanged validator

    @Test
    public void withoutASchemaTheValidatorStillDropsWhatBreaksTheRules() throws Exception {
        this.content.set("{\"entities\":[{\"id\":\"e1\",\"type\":\"company\",\"name\":\"Haus Lindenhof\",\"quote\":\"betreibt das Haus Lindenhof\","
                + "\"confidence\":0.9}],\"claims\":[{\"subject\":\"Haus Lindenhof\",\"predicate\":\"operates\",\"object\":\"k1\"}],\"values\":[]}");
        final YacyLlmClient c = client(LLM.LLMType.OPENAI, "unsupported");
        final String answer = ask(c, "auto");
        assertFalse(this.requests.get(0).has("response_format"));
        final LlmExtractor.Result r = LlmExtractor.validate(answer, LlmExtractorTest.chunk(), LlmExtractorTest.known(), Set.of());
        assertNull(r.refused);
        assertEquals(0, r.entities);
        assertEquals(0, r.claims);
        assertEquals(java.util.Map.of("entity_extra_field", 1, "claim_missing_quote", 1), r.droppedInvalidByReason);
        this.content.set("Sure! Here is the JSON you asked for.");
        assertEquals("invalid_json", LlmExtractor.validate(ask(c, "auto"), LlmExtractorTest.chunk(), LlmExtractorTest.known(), Set.of()).refused);
    }

    // G) other usages of LLM.chat keep their request: the schema without a name, nothing without a schema

    @Test
    public void otherUsagesOfChatAreUnchanged() throws Exception {
        final LLM llm = new LLM("http://127.0.0.1:" + this.server.getAddress().getPort(), "", 512, LLM.LLMType.OLLAMA);
        final LLM.Context ctx = new LLM.Context("sys");
        ctx.addPrompt("user");
        llm.chat("m", ctx, LLM.listSchema, 200);
        final JSONObject rf = this.requests.get(0).getJSONObject("response_format");
        assertEquals("json_schema", rf.getString("type"));
        assertEquals("[\"schema\",\"strict\"]", new org.json.JSONArray(new java.util.TreeSet<>(rf.getJSONObject("json_schema").keySet())).toString());
        llm.chat("m", "sys", "user", 200);
        assertFalse(this.requests.get(1).has("response_format"));
        assertEquals("the knowledge client's counters see no other usage", 0L,
                requests(client(LLM.LLMType.OPENAI, "unknown").structuredOutput("auto"), "json_schema"));
    }

    // the format capability counts only as a result of the current technical probe (format_probe 2)

    private static JSONObject entry(final String format, final Integer probe) throws Exception {
        final JSONObject e = new JSONObject().put("thinking", "supported").put("tooling", "unsupported").put("vision", "unknown").put("format", format);
        return probe == null ? e : e.put("format_probe", probe);
    }

    @Test
    public void aLegacyUnsupportedIsUnknownAndAutoKeepsTheSchema() throws Exception {
        // E) the value of the mood probe up to 0.8.3 (no format_probe), as stored in production for OLLAMA/llama3.1:8b
        assertEquals("unknown", LLM.formatCapability(entry("unsupported", null)));
        assertEquals("unknown", LLM.formatCapability(entry("supported", null)));
        assertEquals("an older probe version", "unknown", LLM.formatCapability(entry("unsupported", 1)));
        final YacyLlmClient c = client(LLM.LLMType.OPENAI, LLM.formatCapability(entry("unsupported", null)));
        ask(c, "auto");
        assertEquals("json_schema", this.requests.get(0).getJSONObject("response_format").getString("type"));
        final JSONObject so = c.structuredOutput("auto");
        assertEquals("schema_unverified", so.getString("mode"));
        assertEquals("capability_unknown", so.getString("reason"));
        assertEquals("unknown", so.getString("capability"));
    }

    @Test
    public void aCurrentUnsupportedSendsNoSchema() throws Exception {
        // F) refused by the endpoint in the current probe
        assertEquals("unsupported", LLM.formatCapability(entry("unsupported", LLM.FORMAT_PROBE_VERSION)));
        final YacyLlmClient c = client(LLM.LLMType.OPENAI, LLM.formatCapability(entry("unsupported", 2)));
        ask(c, "auto");
        assertFalse(this.requests.get(0).has("response_format"));
        assertEquals("validator_only", c.structuredOutput("auto").getString("mode"));
    }

    @Test
    public void aCurrentSupportedIsEnforcedAndIgnoredIsNot() throws Exception {
        // G) supported by the current probe
        final YacyLlmClient s = client(LLM.LLMType.OPENAI, LLM.formatCapability(entry("supported", 2)));
        ask(s, "auto");
        assertEquals("json_schema", this.requests.get(0).getJSONObject("response_format").getString("type"));
        assertEquals("schema_enforced", s.structuredOutput("auto").getString("mode"));
        // accepted but not followed in the probe: the schema is still sent, never shown as enforced
        final YacyLlmClient i = client(LLM.LLMType.OPENAI, LLM.formatCapability(entry("ignored", 2)));
        ask(i, "auto");
        assertTrue(this.requests.get(1).has("response_format"));
        assertEquals("schema_unverified", i.structuredOutput("auto").getString("mode"));
        assertEquals("capability_ignored", i.structuredOutput("auto").getString("reason"));
        assertEquals("ignored", i.structuredOutput("auto").getString("capability"));
    }

    @Test
    public void theOverrideSendsTheSchemaWhateverTheCapability() throws Exception {
        // H) json_schema forced: sent for every capability, enforced only if the current probe says supported
        final String[][] cases = {{"unsupported", "2", "schema_unverified"}, {"unsupported", null, "schema_unverified"},
                {"ignored", "2", "schema_unverified"}, {"unknown", "2", "schema_unverified"}, {"supported", "2", "schema_enforced"},
                {"supported", null, "schema_unverified"}};
        for (final String[] k : cases) {
            this.requests.clear();
            final YacyLlmClient c = client(LLM.LLMType.OPENAI, LLM.formatCapability(entry(k[0], k[1] == null ? null : Integer.valueOf(k[1]))));
            ask(c, "json_schema");
            assertTrue(String.join("/", k[0], String.valueOf(k[1])), this.requests.get(0).has("response_format"));
            assertEquals(String.join("/", k[0], String.valueOf(k[1])), k[2], c.structuredOutput("json_schema").getString("mode"));
        }
    }

    @Test
    public void theFormatCapabilityReadsNothingElse() throws Exception {
        // I) a pure reading of format and format_probe: thinking, tooling and vision of the entry stay as they are
        final JSONObject e = entry("supported", 2);
        final String before = e.toString();
        assertEquals("supported", LLM.formatCapability(e));
        assertEquals(before, e.toString());
        assertEquals("unknown", LLM.formatCapability(null));
        assertEquals("unknown", LLM.formatCapability(new JSONObject().put("format_probe", 2)));
        assertEquals("unknown", LLM.formatCapability(entry("garbage", 2)));
    }

    @Test
    public void capabilityValuesAreFourStates() throws Exception {
        assertEquals("ignored", LLM.capabilityStatus("ignored"));
        assertEquals("supported", LLM.capabilityStatus("supported"));
        assertEquals("unsupported", LLM.capabilityStatus(" Unsupported "));
        assertEquals("unknown", LLM.capabilityStatus("unknown"));
        assertEquals("unknown", LLM.capabilityStatus(null));
        assertEquals("unknown", LLM.capabilityStatus("yes"));
        assertEquals("unknown", new LLM.LLMModel(null, "m", false, false).formatCapability);
        final JSONObject none = new YacyLlmClient(() -> null).structuredOutput("auto");
        assertTrue(none.isNull("mode"));
        assertTrue(none.isNull("capability"));
        assertEquals("auto", none.getString("setting"));
        assertNull(LlmClient.NONE.structuredOutput("auto"));
    }
}
