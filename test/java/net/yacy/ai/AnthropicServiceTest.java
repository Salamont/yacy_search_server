/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import net.yacy.ai.LLM.LLMType;

/**
 * The Anthropic service (ANTHROPIC) on its OpenAI-compatible Chat Completions, against a local fake endpoint that
 * answers as Anthropic does: every request is seen with its headers and body, nothing leaves 127.0.0.1 and the key is a
 * test value. Chat, streaming and the RAG tool chat carry {@code anthropic-version} and the Bearer key and no
 * {@code temperature}; the other services keep their request unchanged.
 */
public class AnthropicServiceTest {

    private static final String KEY = "sk-ant-test-0123456789abcdef";

    /** One request seen by the fake endpoint. */
    private static final class Seen {
        final String path;
        final Map<String, List<String>> headers;
        final JSONObject body;

        Seen(final String path, final Map<String, List<String>> headers, final JSONObject body) {
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        String header(final String name) {
            for (final Map.Entry<String, List<String>> h : this.headers.entrySet()) {
                if (h.getKey().equalsIgnoreCase(name)) return h.getValue().get(0);
            }
            return null;
        }
    }

    private HttpServer server;
    private final List<Seen> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String answer = "{\"result\":\"ok\"}";
    /** The error body of a status other than 200; the default names an invalid key with the key in it. */
    private volatile String errorBody = null;
    private volatile String retryAfter = null;

    @Before
    public void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/v1/chat/completions", exchange -> {
            final JSONObject body;
            try {
                body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } catch (final org.json.JSONException e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            this.requests.add(new Seen(exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), body));
            final byte[] out;
            if (this.status != 200) {
                if (this.retryAfter != null) exchange.getResponseHeaders().add("Retry-After", this.retryAfter);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                out = (this.errorBody != null ? this.errorBody
                        : "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key " + KEY + "\"}}")
                        .getBytes(StandardCharsets.UTF_8);
            } else if (body.optBoolean("stream", false)) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                out = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hallo \"}}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Welt\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                out = ("{\"id\":\"msg_01\",\"object\":\"chat.completion\",\"model\":" + JSONObject.quote(body.optString("model"))
                        + ",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":" + JSONObject.quote(this.answer)
                        + "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"total_tokens\":17}}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(this.status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        // the model list as Anthropic's OpenAI-compatible endpoint shapes it: data[].id, one page of up to 1000
        this.server.createContext("/v1/models", exchange -> {
            this.requests.add(new Seen(exchange.getRequestURI().getPath() + (exchange.getRequestURI().getQuery() == null ? ""
                    : "?" + exchange.getRequestURI().getQuery()), exchange.getRequestHeaders(), new JSONObject()));
            final byte[] out = ("{\"object\":\"list\",\"data\":[{\"id\":\"claude-sonnet-5-5\",\"object\":\"model\",\"owned_by\":\"anthropic\"},"
                    + "{\"id\":\"claude-haiku-5-5\",\"object\":\"model\",\"owned_by\":\"anthropic\"}],\"has_more\":false}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        this.server.start();
    }

    private String hoststub() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }

    @After
    public void stop() {
        this.server.stop(0);
    }

    private LLM llm(final LLMType type) {
        return new LLM("http://127.0.0.1:" + this.server.getAddress().getPort() + "/", KEY, 256, type);
    }

    private static LLM.Context context() throws Exception {
        final LLM.Context c = new LLM.Context("You are a test.");
        c.addPrompt("Say ok.");
        return c;
    }

    private Seen only() {
        assertEquals(1, this.requests.size());
        return this.requests.get(0);
    }

    @Test
    public void theServiceIsKnownWithItsHostAndHeaders() {
        assertEquals(LLMType.ANTHROPIC, LLMType.valueOf("ANTHROPIC"));
        assertEquals("without /v1: the paths add it", "https://api.anthropic.com", LLMType.ANTHROPIC.hoststub);
        // no switchboard in this test: no configured workspace, the version alone
        assertEquals(Map.of("anthropic-version", "2023-06-01"), LLM.serviceHeaders(LLMType.ANTHROPIC));
        for (final LLMType other : List.of(LLMType.OPENAI, LLMType.OPENROUTER, LLMType.OLLAMA, LLMType.LMSTUDIO, LLMType.OTHER)) {
            assertTrue(other.name(), LLM.serviceHeaders(other).isEmpty());
            assertTrue(other.name(), LLM.sendsTemperature(other));
            assertFalse(other.name(), LLM.ignoresResponseFormat(other.name()));
        }
        assertTrue(LLM.serviceHeaders(null).isEmpty());
        assertFalse(LLM.sendsTemperature(LLMType.ANTHROPIC));
        assertFalse(LLM.sendsTemperature("ANTHROPIC"));
        assertTrue(LLM.ignoresResponseFormat("ANTHROPIC"));
    }

    @Test
    public void onlyAWorkspaceIdBecomesTheWorkspaceHeader() {
        assertEquals("wrkspc_011CZkZaBF1tNoB5wlCeusgy", LLM.anthropicWorkspace(" wrkspc_011CZkZaBF1tNoB5wlCeusgy "));
        assertNull("empty: none, as a key of one workspace needs", LLM.anthropicWorkspace(""));
        assertNull(LLM.anthropicWorkspace(null));
        assertNull("no header injection", LLM.anthropicWorkspace("wrkspc_1\r\nX-Other: 1"));
        assertNull(LLM.anthropicWorkspace("a b"));
    }

    @Test
    public void aChatCarriesTheVersionAndTheBearerKeyAndNoTemperature() throws Exception {
        final String[] usage = new String[1];
        final JSONObject format = new JSONObject().put("type", "json_schema").put("json_schema",
                new JSONObject().put("name", "t").put("schema", new JSONObject().put("type", "object")));
        final String content = llm(LLMType.ANTHROPIC).chatWithResponseFormat("claude-sonnet-5-5", context(), format, 256, 5000, 100_000,
                u -> usage[0] = u.toString());
        assertEquals("{\"result\":\"ok\"}", content);
        final Seen r = only();
        assertEquals("/v1/chat/completions", r.path);
        assertEquals("2023-06-01", r.header("anthropic-version"));
        assertEquals("Bearer " + KEY, r.header("Authorization"));
        assertNull("no x-api-key: the Bearer key as for every service", r.header("x-api-key"));
        assertNull("no workspace without configuration", r.header("anthropic-workspace-id"));
        assertFalse("no temperature", r.body.has("temperature"));
        assertEquals("claude-sonnet-5-5", r.body.getString("model"));
        assertEquals(256, r.body.getInt("max_tokens"));
        assertEquals("the caller's response_format is sent as it is (Anthropic ignores it)", "json_schema",
                r.body.getJSONObject("response_format").getString("type"));
        assertTrue(r.body.getJSONArray("messages").length() >= 2);
        assertTrue(usage[0], usage[0].contains("\"promptTokens\":12"));
    }

    @Test
    public void aStreamedChatCarriesTheHeadersAndAssemblesTheDeltas() throws Exception {
        final StringBuilder deltas = new StringBuilder();
        final String all = llm(LLMType.ANTHROPIC).chatStream("claude-haiku-5-5", "sys", "user", 128, deltas::append);
        assertEquals("Hallo Welt", all);
        assertEquals("Hallo Welt", deltas.toString());
        final Seen r = only();
        assertEquals("2023-06-01", r.header("anthropic-version"));
        assertEquals("Bearer " + KEY, r.header("Authorization"));
        assertTrue(r.body.getBoolean("stream"));
        assertFalse(r.body.has("temperature"));
    }

    @Test
    public void theRagToolChatCarriesTheHeaders() throws Exception {
        final LLM.LLMModel m = new LLM.LLMModel(llm(LLMType.ANTHROPIC), "claude-sonnet-5-5", false, false);
        final JSONObject body = new JSONObject().put("model", "claude-sonnet-5-5").put("stream", true).put("max_tokens", 128)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "Hallo")));
        final Capture out = new Capture();
        assertEquals(200, ToolCallProtocol.proxyToolLifecycle(out, m, body, body.getJSONArray("messages"), null));
        final Seen r = only();
        assertEquals("2023-06-01", r.header("anthropic-version"));
        assertEquals("Bearer " + KEY, r.header("Authorization"));
        assertTrue(out.text(), out.text().contains("Hallo ") && out.text().contains("Welt"));
    }

    @Test
    public void noLocalServerHintsReachAnthropic() throws Exception {
        // 0.11.5: num_ctx, the llama stop tokens and the thinking switches are Ollama's and the self-hosted servers'; Anthropic
        // knows none of them and an unknown field is a refused request that still costs
        llm(LLMType.ANTHROPIC).chatWithResponseFormat("claude-sonnet-5-5", context(), null, 64, 5000, 100_000);
        Seen r = only();
        for (final String key : new String[] {"num_ctx", "stop", "reasoning_effort", "enable_thinking", "temperature"}) {
            assertFalse(key + " is not sent: " + r.body, r.body.has(key));
        }
        this.requests.clear();
        llm(LLMType.ANTHROPIC).chatStream("claude-haiku-5-5", "sys", "user", 64, null);
        r = only();
        for (final String key : new String[] {"num_ctx", "stop", "reasoning_effort", "enable_thinking", "temperature"}) {
            assertFalse(key + " is not streamed: " + r.body, r.body.has(key));
        }
        // the RAG chat of a thinking model: the client's switches are removed, none are added
        this.requests.clear();
        final LLM.LLMModel m = new LLM.LLMModel(llm(LLMType.ANTHROPIC), "claude-sonnet-5-5", false, true);
        final JSONObject body = new JSONObject().put("model", "claude-sonnet-5-5").put("stream", true).put("max_tokens", 128)
                .put("num_ctx", 8192).put("enable_thinking", true)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "Hallo")));
        assertEquals(200, ToolCallProtocol.proxyToolLifecycle(new Capture(), m, body, body.getJSONArray("messages"), null));
        r = only();
        for (final String key : new String[] {"num_ctx", "reasoning_effort", "enable_thinking"}) {
            assertFalse(key + " is not relayed: " + r.body, r.body.has(key));
        }
        // the other services keep their hints
        this.requests.clear();
        llm(LLMType.OPENROUTER).chatWithResponseFormat("m", context(), null, 64, 5000, 100_000);
        r = only();
        assertTrue(r.body.has("num_ctx") && r.body.has("stop") && "none".equals(r.body.getString("reasoning_effort"))
                && !r.body.getBoolean("enable_thinking"));
    }

    @Test
    public void theModelListIsAskedForOnePageOfUpToAThousand() throws Exception {
        assertEquals("?limit=1000", net.yacy.http.servlets.LLMAdminProxyServlet.upstreamQuery(LLMType.ANTHROPIC, "/v1/models"));
        assertEquals("", net.yacy.http.servlets.LLMAdminProxyServlet.upstreamQuery(LLMType.ANTHROPIC, "/v1/chat/completions"));
        assertEquals("", net.yacy.http.servlets.LLMAdminProxyServlet.upstreamQuery(LLMType.OPENAI, "/v1/models"));
        // the list the fake endpoint serves has the shape the page reads (data[].id)
        final java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        final java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(hoststub() + "/v1/models"
                + net.yacy.http.servlets.LLMAdminProxyServlet.upstreamQuery(LLMType.ANTHROPIC, "/v1/models")))
                .header("Authorization", "Bearer " + KEY).header("anthropic-version", LLM.ANTHROPIC_VERSION).GET().build();
        final java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        final JSONArray data = new JSONObject(response.body()).getJSONArray("data");
        assertEquals("claude-sonnet-5-5", data.getJSONObject(0).getString("id"));
        assertEquals("/v1/models?limit=1000", only().path);
    }

    @Test
    public void aRateLimitNamesItsKindAndItsWaitWithoutTheKey() throws Exception {
        this.status = 429;
        this.retryAfter = "30";
        this.errorBody = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"This request would exceed your organization's rate limit of 50 requests per minute (key " + KEY + ")\"}}";
        try {
            llm(LLMType.ANTHROPIC).chatWithResponseFormat("claude-sonnet-5-5", context(), null, 64, 5000, 100_000);
            fail("a 429 is an error");
        } catch (final LlmHttpError e) {
            assertEquals(429, e.status);
            assertEquals(LlmHttpError.KIND_RATE_LIMIT, e.kind());
            assertTrue(e.retryable());
            assertEquals(Long.valueOf(30_000L), e.retryAfterMillis);
            assertEquals("rate_limit_error", e.code);
            assertFalse("the key never reaches the error: " + e.getMessage(), e.getMessage().contains(KEY));
            assertFalse(e.json().toString(), e.json().toString().contains(KEY));
            assertEquals("rate_limit", e.json().getJSONObject("error").getString("kind"));
            assertEquals(30_000L, e.json().getJSONObject("error").getLong("retry_after_ms"));
        }
        assertEquals("one request, no retry by the transport", 1, this.requests.size());
    }

    @Test
    public void anOverloadAForbiddenKeyAndSpentCreditsAreToldApart() throws Exception {
        final Object[][] cases = {
            {529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}", LlmHttpError.KIND_OVERLOADED, true},
            {403, "{\"type\":\"error\",\"error\":{\"type\":\"permission_error\",\"message\":\"Your API key does not have permission to use the specified resource.\"}}", LlmHttpError.KIND_AUTH, false},
            {401, null, LlmHttpError.KIND_AUTH, false},
            {400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}", LlmHttpError.KIND_REQUEST, false},
            {402, "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"You exceeded your current quota\"}}", LlmHttpError.KIND_QUOTA, false},
            {429, "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"You exceeded your current quota, please check your plan and billing details.\"}}", LlmHttpError.KIND_QUOTA, false}};
        for (final Object[] c : cases) {
            this.requests.clear();
            this.status = (Integer) c[0];
            this.errorBody = (String) c[1];
            try {
                llm(LLMType.ANTHROPIC).chatStream("claude-sonnet-5-5", "sys", "user", 64, null);
                fail(c[0] + " is an error");
            } catch (final LlmHttpError e) {
                assertEquals(String.valueOf(c[0]), c[2], e.kind());
                assertEquals(String.valueOf(c[0]), c[3], e.retryable());
                assertFalse(e.getMessage(), e.getMessage().contains(KEY));
                assertTrue(e.getMessage(), e.getMessage().contains(String.valueOf(c[0])));
            }
            assertEquals(1, this.requests.size());
        }
    }

    @Test
    public void anAnthropicErrorNamesItsReasonWithoutTheKey() throws Exception {
        this.status = 401;
        try {
            llm(LLMType.ANTHROPIC).chatWithResponseFormat("claude-sonnet-5-5", context(), null, 64, 5000, 100_000);
            fail("a 401 is an error");
        } catch (final IOException e) {
            final String m = String.valueOf(e.getMessage());
            assertTrue(m, m.contains("401"));
            assertFalse("the key never reaches the error: " + m, m.contains(KEY));
        }
        assertEquals("2023-06-01", only().header("anthropic-version"));
    }

    @Test
    public void theOtherServicesKeepTheirRequest() throws Exception {
        for (final LLMType type : List.of(LLMType.OPENAI, LLMType.OPENROUTER, LLMType.LMSTUDIO, LLMType.OLLAMA, LLMType.OTHER)) {
            this.requests.clear();
            llm(type).chatWithResponseFormat("m", context(), null, 64, 5000, 100_000);
            Seen r = only();
            assertNull(type.name(), r.header("anthropic-version"));
            assertEquals(type.name(), "Bearer " + KEY, r.header("Authorization"));
            assertEquals(type.name(), 0.1, r.body.getDouble("temperature"), 0.0);
            this.requests.clear();
            llm(type).chatStream("m", "sys", "user", 64, null);
            r = only();
            assertNull(type.name(), r.header("anthropic-version"));
            assertEquals(type.name(), 0.1, r.body.getDouble("temperature"), 0.0);
            this.requests.clear();
            final JSONObject body = new JSONObject().put("model", "m").put("stream", true)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "Hallo")));
            ToolCallProtocol.proxyToolLifecycle(new Capture(), new LLM.LLMModel(llm(type), "m", false, false), body, body.getJSONArray("messages"), null);
            assertNull(type.name(), only().header("anthropic-version"));
        }
    }

    @Test
    public void anAnswerThatIsNoJsonIsReturnedAsTextForTheCallersValidator() throws Exception {
        this.answer = "Sure! Here is the JSON you asked for.";
        assertEquals("the transport neither repairs nor accepts it: the caller's validator decides", this.answer,
                llm(LLMType.ANTHROPIC).chatWithResponseFormat("claude-sonnet-5-5", context(), null, 64, 5000, 100_000));
    }

    /** The downstream SSE stream of the RAG chat, in memory. */
    private static final class Capture extends ServletOutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void write(final int b) {
            this.bytes.write(b);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(final WriteListener listener) {
        }

        String text() {
            return new String(this.bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
