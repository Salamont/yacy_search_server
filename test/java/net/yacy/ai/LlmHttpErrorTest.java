/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

import net.yacy.ai.LLM.LLMType;

/**
 * The streamed call of the log report against a mocked OpenAI-compatible endpoint that refuses requests as strict providers
 * do (0.10.7). Production logged "LOGREPORT phase=write result=failure … Request failed with response code 400" six times
 * without the provider's reason: an exceeded context window, a rejected parameter and a router's upstream error looked the
 * same. The error's message now names what the provider said, bounded and without the prompt, the router's raw upstream
 * answer or a key. Only the public API of {@link LLM}, so the same test runs against the code before.
 */
public class LlmHttpErrorTest {

    private static final String KEY = "sk-test-0123456789abcdef";
    /** The window of the strict mock, in tokens of three characters. */
    private static final int WINDOW = 8192;

    private HttpServer server;
    private volatile String mode = "strict";
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @Before
    public void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/chat/completions", exchange -> {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            this.requests.add(body);
            int chars = 0;
            int maxTokens = 0;
            try {
                final JSONObject request = new JSONObject(body);
                final JSONArray messages = request.getJSONArray("messages");
                for (int i = 0; i < messages.length(); i++) {
                    chars += messages.getJSONObject(i).optString("content").length();
                }
                maxTokens = request.optInt("max_tokens");
            } catch (final org.json.JSONException e) {
                chars = Integer.MAX_VALUE / 4;
            }
            final int requested = chars / 3 + maxTokens;
            final String error;
            switch (this.mode) {
            case "strict":
                error = requested > WINDOW ? "{\"error\":{\"message\":\"This model's maximum context length is " + WINDOW + " tokens. However,"
                        + " you requested " + requested + " tokens. Please reduce the length of the messages or completion.\",\"type\":"
                        + "\"invalid_request_error\",\"param\":\"messages\",\"code\":\"context_length_exceeded\"}}" : null;
                break;
            case "param":
                error = "{\"error\":{\"message\":\"Unrecognized request argument supplied: num_ctx\",\"type\":\"invalid_request_error\","
                        + "\"param\":\"num_ctx\",\"code\":null}}";
                break;
            case "router":
                error = "{\"error\":{\"code\":400,\"message\":\"Provider returned error\",\"metadata\":{\"provider_name\":\"Example Provider\","
                        + "\"raw\":\"{\\\"echo\\\":\\\"" + "LOGLINE-MARKER" + "\\\"}\"}}}";
                break;
            case "key":
                error = "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + ". Authorization: Bearer " + KEY + "\",\"type\":"
                        + "\"invalid_request_error\",\"code\":\"invalid_api_key\"}}";
                break;
            case "huge":
                error = "x".repeat(200_000);
                break;
            default:
                error = null;
            }
            if (error != null) {
                final byte[] b = error.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders("key".equals(this.mode) ? 401 : 400, b.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(b);
                }
                return;
            }
            final byte[] b = ("data: {\"choices\":[{\"delta\":{\"content\":\"Bericht\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        this.server.start();
    }

    @After
    public void stop() {
        this.server.stop(0);
    }

    private LLM llm(final int maxTokens, final int numCtx) {
        return new LLM("http://127.0.0.1:" + this.server.getAddress().getPort(), KEY, maxTokens, numCtx, LLMType.OPENROUTER);
    }

    /** Log lines of an hour, each with a marker that must never reach the error. */
    private static String logLines(final int n) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append("I 2026/10/14 08:").append(i % 60).append(" CRAWLER LOGLINE-MARKER fetched https://www.example.org/page/").append(i)
                    .append(" in 120 ms\n");
        }
        return b.toString();
    }

    private String refused(final LLM llm, final String prompt, final int maxTokens) {
        try {
            llm.chatStream("example/model", "You evaluate logs.", prompt, maxTokens, null);
        } catch (final IOException e) {
            final String m = e.getMessage();
            assertTrue(m, m.startsWith("Request failed with response code "));
            assertFalse("no log line of the prompt: " + m, m.contains("LOGLINE-MARKER"));
            assertFalse("no key: " + m, m.contains(KEY) || m.contains("0123456789abcdef"));
            assertTrue("bounded: " + m.length(), m.length() < 600);
            return m;
        }
        fail("the call was not refused");
        return null;
    }

    /** A prompt that fills a strict provider's window together with its output cap: the reason is named. */
    @Test
    public void anExceededContextWindowIsNamed() {
        final String m = refused(llm(4096, WINDOW), logLines(400), 4096);
        assertTrue(m, m.startsWith("Request failed with response code 400"));
        assertTrue(m, m.contains("code=context_length_exceeded"));
        assertTrue(m, m.contains("param=messages"));
        assertTrue(m, m.contains("maximum context length is 8192 tokens"));
        // the same call within the window is answered
        this.mode = "ok";
        try {
            assertTrue(llm(256, WINDOW).chatStream("example/model", "You evaluate logs.", logLines(10), 256, null).contains("Bericht"));
        } catch (final IOException e) {
            fail(e.getMessage());
        }
    }

    /** A rejected parameter is named, so it is told apart from an exceeded window. */
    @Test
    public void aRejectedParameterIsNamed() {
        this.mode = "param";
        final String m = refused(llm(256, WINDOW), logLines(5), 256);
        assertTrue(m, m.contains("param=num_ctx"));
        assertTrue(m, m.contains("Unrecognized request argument supplied: num_ctx"));
        assertTrue(m, m.contains("code=invalid_request_error"));
    }

    /** A router names its upstream provider; its raw upstream answer (which may echo the request) is never kept. */
    @Test
    public void aRouterNamesItsProviderButNotItsRawAnswer() {
        this.mode = "router";
        final String m = refused(llm(256, WINDOW), logLines(5), 256);
        assertTrue(m, m.contains("provider=Example Provider"));
        assertTrue(m, m.contains("code=400"));
        assertTrue(m, m.contains("Provider returned error"));
    }

    /** A key the provider repeats in its message is masked. */
    @Test
    public void aKeyInTheProvidersMessageIsMasked() {
        this.mode = "key";
        final String m = refused(llm(256, WINDOW), logLines(5), 256);
        assertTrue(m, m.startsWith("Request failed with response code 401"));
        assertTrue(m, m.contains("code=invalid_api_key"));
    }

    /** A huge error body is read only in part and kept in a bounded excerpt. */
    @Test
    public void retryAfterIsSecondsBoundedToAnHour() {
        assertEquals(Long.valueOf(30_000L), LlmHttpError.retryAfter("30"));
        assertEquals(Long.valueOf(30_000L), LlmHttpError.retryAfter(" 30 "));
        assertEquals(Long.valueOf(LlmHttpError.MAX_RETRY_AFTER_MILLIS), LlmHttpError.retryAfter("86400"));
        assertEquals(null, LlmHttpError.retryAfter(null));
        assertEquals(null, LlmHttpError.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(null, LlmHttpError.retryAfter("-1"));
        final LlmHttpError e = LlmHttpError.of(429, "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}", 60, null, 5_000L);
        assertEquals(Long.valueOf(5_000L), e.retryAfterMillis);
        assertEquals(LlmHttpError.KIND_RATE_LIMIT, e.kind());
        assertTrue(e.fields(), e.fields().contains("errorKind=rate_limit"));
    }

    @Test
    public void aHugeErrorBodyIsBounded() {
        this.mode = "huge";
        final String m = refused(llm(256, WINDOW), logLines(5), 256);
        assertTrue(m, m.contains("detail=\"xxx"));
    }
}
