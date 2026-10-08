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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.yacy.ai.LLM;

/**
 * The knowledge usage on Ollama's native {@code /api/chat} with {@code format} (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3),
 * against a local fake that answers both the native and the OpenAI-compatible path, so a wrong path or a hidden
 * switch between them shows. Every other service keeps {@code /v1/chat/completions} with the request it sent before.
 * Nothing leaves 127.0.0.1.
 */
public class OllamaNativeChatTest {

    private HttpServer server;
    /** path and body of every request */
    private final List<Object[]> requests = new CopyOnWriteArrayList<>();
    /** ok, reject (400 for any format), always400, error503, slow, huge, length */
    private final AtomicReference<String> endpoint = new AtomicReference<>("ok");
    private final AtomicReference<String> content = new AtomicReference<>("{\"entities\":[],\"claims\":[],\"values\":[]}");

    @Before
    public void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/api/chat", exchange -> answer(exchange, true));
        this.server.createContext("/v1/chat/completions", exchange -> answer(exchange, false));
        this.server.start();
    }

    private void answer(final HttpExchange exchange, final boolean nativeApi) throws IOException {
        final JSONObject body;
        try {
            body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (final Exception e) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
        }
        this.requests.add(new Object[] {exchange.getRequestURI().getPath(), body});
        final String e = this.endpoint.get();
        if ("slow".equals(e)) {
            try {
                Thread.sleep(3000L);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        final int status = "always400".equals(e) || "reject".equals(e) && (body.has("format") || body.has("response_format")) ? 400
                : "error503".equals(e) ? 503 : 200;
        final String text = "huge".equals(e) ? "x".repeat(YacyLlmClient.MAX_RESPONSE_CHARS + 10) : this.content.get();
        final String json = nativeApi
                ? "{\"model\":\"llama3.1:8b\",\"created_at\":\"2026-10-08T00:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":"
                        + JSONObject.quote(text) + "},\"done\":true,\"done_reason\":\"" + ("length".equals(e) ? "length" : "stop") + "\"}"
                : "{\"choices\":[{\"message\":{\"content\":" + JSONObject.quote(text) + "},\"finish_reason\":\"stop\"}]}";
        final byte[] out = status != 200 ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
        } catch (final IOException ignored) {
            // the client gave up (timeout test)
        }
        exchange.close();
    }

    @After
    public void stop() {
        this.server.stop(0);
    }

    private String stub() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    private LLM.LLMModel model(final LLM.LLMType type, final String capability, final boolean thinking) {
        final LLM.LLMModel m = new LLM.LLMModel(new LLM(stub(), "", 512, type), "llama3.1:8b", false, thinking);
        m.formatCapability = capability;
        return m;
    }

    private YacyLlmClient ollama(final String capability) {
        final LLM.LLMModel m = model(LLM.LLMType.OLLAMA, capability, false);
        return new YacyLlmClient(() -> m);
    }

    private String ask(final YacyLlmClient c, final String setting) throws IOException {
        return c.complete(LlmExtractor.SYSTEM_PROMPT, "user text", LlmExtractor.SCHEMA, 5000L, setting);
    }

    private JSONObject body(final int i) {
        return (JSONObject) this.requests.get(i)[1];
    }

    private String path(final int i) {
        return (String) this.requests.get(i)[0];
    }

    private long onV1() {
        return this.requests.stream().filter(r -> "/v1/chat/completions".equals(r[0])).count();
    }

    // A) no schema: /api/chat, stream false, the answer from message.content, no OpenAI field

    @Test
    public void withoutAFormatOllamaIsAskedNatively() throws Exception {
        this.content.set("plain answer");
        final YacyLlmClient c = ollama("unknown");
        assertEquals("plain answer", ask(c, "none"));
        assertEquals(1, this.requests.size());
        assertEquals("/api/chat", path(0));
        assertEquals(0L, onV1());
        final JSONObject b = body(0);
        assertEquals("llama3.1:8b", b.getString("model"));
        assertFalse(b.getBoolean("stream"));
        assertFalse(b.has("format"));
        for (final String openAiOnly : List.of("response_format", "max_tokens", "temperature", "stop", "num_ctx", "reasoning_effort",
                "enable_thinking")) {
            assertFalse("no OpenAI field at the top: " + openAiOnly, b.has(openAiOnly));
        }
        assertFalse("think only for a thinking model", b.has("think"));
        final JSONObject o = b.getJSONObject("options");
        assertEquals(0.1, o.getDouble("temperature"), 0.0);
        assertEquals("max_tokens of the row", 512, o.getInt("num_predict"));
        assertTrue(o.getJSONArray("stop").length() > 0);
        assertFalse("the server's own context length applies", o.has("num_ctx"));
        final JSONArray messages = b.getJSONArray("messages");
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals(LlmExtractor.SYSTEM_PROMPT, messages.getJSONObject(0).getString("content"));
        assertEquals("user", messages.getJSONObject(1).getString("role"));
        assertEquals("user text", messages.getJSONObject(1).getString("content"));
        final JSONObject so = c.structuredOutput("none");
        assertEquals("ollama_native", so.getString("api"));
        assertEquals("validator_only", so.getString("mode"));
        assertEquals(1L, so.getJSONObject("requests").getLong("none"));
    }

    // B) the schema as Ollama's format, exactly the extraction schema, no response_format

    @Test
    public void theSchemaIsSentAsTheNativeFormat() throws Exception {
        final YacyLlmClient c = ollama("supported");
        ask(c, "auto");
        assertEquals("/api/chat", path(0));
        assertEquals(0L, onV1());
        final JSONObject b = body(0);
        assertEquals("exactly the extraction schema", LlmExtractor.SCHEMA.toString(), b.getJSONObject("format").toString());
        assertTrue("the same object, not a copy with other keys", b.getJSONObject("format").keySet().equals(LlmExtractor.SCHEMA.keySet()));
        assertFalse(b.has("response_format"));
        final JSONObject so = c.structuredOutput("auto");
        assertEquals("ollama_native", so.getString("api"));
        assertEquals("schema_enforced", so.getString("mode"));
        assertEquals("json_schema", so.getString("request"));
        assertEquals(1L, so.getJSONObject("requests").getLong("json_schema"));
        // a capability not confirmed by the native probe: the native schema still goes out, not shown as enforced
        this.requests.clear();
        final YacyLlmClient u = ollama("unknown");
        ask(u, "auto");
        assertTrue(body(0).has("format"));
        assertEquals("schema_unverified", u.structuredOutput("auto").getString("mode"));
    }

    // C) JSON mode: format "json"

    @Test
    public void jsonModeIsTheNativeJsonFormat() throws Exception {
        final YacyLlmClient c = ollama("unknown");
        ask(c, "json_object");
        assertEquals("/api/chat", path(0));
        assertEquals("json", body(0).getString("format"));
        assertFalse(body(0).has("response_format"));
        assertEquals("json_mode", c.structuredOutput("json_object").getString("mode"));
        assertEquals(1L, c.structuredOutput("json_object").getJSONObject("requests").getLong("json_object"));
    }

    // D) a schema answer goes unchanged to the validator; a non-conforming one is still dropped by it

    @Test
    public void theNativeAnswerMeetsTheUnchangedValidator() throws Exception {
        this.content.set("{\"entities\":[{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Lindenhof\",\"quote\":\"betreibt das Haus Lindenhof in Berlin\"}],"
                + "\"claims\":[{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\"Die Muster Pflege gGmbH betreibt das Haus Lindenhof\"}],"
                + "\"values\":[]}");
        final YacyLlmClient c = ollama("supported");
        final String answer = ask(c, "auto");
        LlmExtractor.Result r = LlmExtractor.validate(answer, LlmExtractorTest.chunk(), LlmExtractorTest.known(), Set.of());
        assertNull(r.refused);
        assertEquals(1, r.entities);
        assertEquals(1, r.claims);
        assertEquals(0, r.droppedInvalid);
        this.content.set("{\"entities\":[{\"id\":\"e1\",\"type\":\"company\",\"name\":\"Haus Lindenhof\",\"quote\":\"betreibt das Haus Lindenhof\","
                + "\"confidence\":1}],\"claims\":[],\"values\":[]}");
        r = LlmExtractor.validate(ask(c, "auto"), LlmExtractorTest.chunk(), LlmExtractorTest.known(), Set.of());
        assertEquals(java.util.Map.of("entity_extra_field", 1), r.droppedInvalidByReason);
        this.content.set("Sure, here it is: {}");
        assertEquals("invalid_json", LlmExtractor.validate(ask(c, "auto"), LlmExtractorTest.chunk(), LlmExtractorTest.known(), Set.of()).refused);
    }

    // E) transport failures stay failures of the native path: no switch to /v1, the breaker sees them

    @Test
    public void aServerErrorIsATransportFailureWithoutASwitch() throws Exception {
        this.endpoint.set("error503");
        try {
            ask(ollama("supported"), "auto");
            fail("503");
        } catch (final IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("503"));
        }
        assertEquals("one request, no retry, no other path", 1, this.requests.size());
        assertEquals(0L, onV1());
    }

    @Test
    public void aTimeoutOrAnUnreachableOllamaFails() throws Exception {
        this.endpoint.set("slow");
        final long t0 = System.currentTimeMillis();
        try {
            ollama("supported").complete("sys", "user", LlmExtractor.SCHEMA, 500L, "auto");
            fail("no timeout");
        } catch (final SocketTimeoutException e) {
            assertTrue(System.currentTimeMillis() - t0 < 2500L);
        }
        assertEquals(0L, onV1());
        final int closed;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        final LLM.LLMModel gone = new LLM.LLMModel(new LLM("http://127.0.0.1:" + closed, "", 512, LLM.LLMType.OLLAMA), "m", false, false);
        try {
            new YacyLlmClient(() -> gone).complete("sys", "user", LlmExtractor.SCHEMA, 2000L, "auto");
            fail("unreachable");
        } catch (final IOException e) {
            // a transport failure for the breaker
        }
    }

    @Test
    public void aRejectedFormatFallsBackNativelyAndIsRemembered() throws Exception {
        this.endpoint.set("reject");
        final YacyLlmClient c = ollama("supported");
        ask(c, "auto");
        assertEquals(2, this.requests.size());
        assertEquals("/api/chat", path(0));
        assertEquals("the retry stays on /api/chat", "/api/chat", path(1));
        assertTrue(body(0).has("format"));
        assertFalse(body(1).has("format"));
        assertEquals(0L, onV1());
        final JSONObject so = c.structuredOutput("auto");
        assertEquals("fallback_after_rejection", so.getString("mode"));
        assertEquals(1L, so.getLong("rejections"));
        assertTrue(so.getBoolean("withoutSchema"));
        ask(c, "auto");
        assertEquals("remembered: one native request without format", 3, this.requests.size());
        assertFalse(body(2).has("format"));
        this.endpoint.set("always400");
        this.requests.clear();
        try {
            ask(ollama("unknown"), "auto");
            fail("400 without format too");
        } catch (final IOException e) {
            assertTrue(e.getMessage().contains("400"));
        }
        assertEquals("one call, one native retry, nothing else", 2, this.requests.size());
        assertEquals(0L, onV1());
    }

    @Test
    public void oversizedTruncatedAndThinkingAnswers() throws Exception {
        this.endpoint.set("huge");
        try {
            ask(ollama("supported"), "auto");
            fail("oversized accepted");
        } catch (final IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("exceeds"));
        }
        this.endpoint.set("length");
        this.content.set("<think>x</think>{\"entities\":[");
        assertEquals("truncated answers come back for the validator to refuse", "{\"entities\":[", ask(ollama("supported"), "auto"));
        this.endpoint.set("ok");
        this.requests.clear();
        final LLM.LLMModel thinking = model(LLM.LLMType.OLLAMA, "supported", true);
        new YacyLlmClient(() -> thinking).complete("sys", "user", LlmExtractor.SCHEMA, 5000L, "auto");
        assertFalse("think: false for a thinking model", body(0).getBoolean("think"));
    }

    // F-I) every other service: /v1/chat/completions with exactly the former request

    @Test
    public void everyOtherServiceKeepsItsOpenAiRequest() throws Exception {
        for (final LLM.LLMType type : List.of(LLM.LLMType.OPENAI, LLM.LLMType.OPENROUTER, LLM.LLMType.LMSTUDIO, LLM.LLMType.OTHER)) {
            for (final String setting : List.of("auto", "json_object", "none")) {
                this.requests.clear();
                final LLM.LLMModel m = model(type, "supported", false);
                new YacyLlmClient(() -> m).complete(LlmExtractor.SYSTEM_PROMPT, "user text", LlmExtractor.SCHEMA, 5000L, setting);
                assertEquals(type + "/" + setting, 1, this.requests.size());
                assertEquals(type + "/" + setting, "/v1/chat/completions", path(0));
                final JSONObject viaClient = body(0);
                // the request LLM.chatWithResponseFormat builds for the same response_format, as before this change
                final LLM.Context ctx = new LLM.Context(LlmExtractor.SYSTEM_PROMPT);
                ctx.addPrompt("user text");
                final String request = "auto".equals(setting) ? "json_schema" : "json_object".equals(setting) ? "json_object" : "none";
                m.llm.chatWithResponseFormat(m.model, ctx, YacyLlmClient.responseFormat(request, LlmExtractor.SCHEMA), 512, 5000,
                        YacyLlmClient.MAX_RESPONSE_CHARS);
                assertEquals(type + "/" + setting + ": the same request", body(1).toString(), viaClient.toString());
                assertFalse(viaClient.has("format"));
                assertFalse(viaClient.has("options"));
                assertEquals("openai_compatible", new YacyLlmClient(() -> m).structuredOutput(setting).getString("api"));
            }
        }
        assertEquals(0L, this.requests.stream().filter(r -> "/api/chat".equals(r[0])).count());
    }

    @Test
    public void nativeFormatMapping() throws Exception {
        assertNull(YacyLlmClient.ollamaFormat(null));
        assertEquals("json", YacyLlmClient.ollamaFormat(YacyLlmClient.responseFormat("json_object", LlmExtractor.SCHEMA)));
        assertTrue("the schema object itself", YacyLlmClient.ollamaFormat(YacyLlmClient.responseFormat("json_schema", LlmExtractor.SCHEMA)) == LlmExtractor.SCHEMA);
        assertEquals("ollama_native", YacyLlmClient.api(model(LLM.LLMType.OLLAMA, "unknown", false)));
        for (final LLM.LLMType t : List.of(LLM.LLMType.OPENAI, LLM.LLMType.OPENROUTER, LLM.LLMType.LMSTUDIO, LLM.LLMType.OTHER)) {
            assertEquals("openai_compatible", YacyLlmClient.api(model(t, "unknown", false)));
        }
    }

    // the format capability of an Ollama model counts only from the native probe (version 3)

    @Test
    public void anOllamaCapabilityNeedsTheNativeProbe() throws Exception {
        final JSONObject v2 = new JSONObject().put("format", "ignored").put("format_probe", 2).put("tooling", "supported");
        final JSONObject v3 = new JSONObject().put("format", "supported").put("format_probe", 3);
        assertEquals("measured on /v1: unknown for Ollama", "unknown", LLM.formatCapability(v2, LLM.LLMType.OLLAMA));
        assertEquals("supported", LLM.formatCapability(v3, LLM.LLMType.OLLAMA));
        assertEquals("ignored", LLM.formatCapability(v2, LLM.LLMType.OPENAI));
        assertEquals("unknown", LLM.formatCapability(v3, LLM.LLMType.OPENAI));
        assertEquals("unchanged for every other service", "ignored", LLM.formatCapability(v2, "LMSTUDIO"));
        assertEquals(3, LLM.formatProbeVersion("OLLAMA"));
        assertEquals(2, LLM.formatProbeVersion("OPENROUTER"));
        assertEquals(2, LLM.formatProbeVersion(null));
        assertEquals("the legacy reading without a service is version 2", "ignored", LLM.formatCapability(v2));
    }
}
