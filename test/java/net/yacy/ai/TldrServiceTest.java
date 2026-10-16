/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;

public class TldrServiceTest {
    private HttpServer server;
    private LLM.LLMModel model;
    private final AtomicInteger calls = new AtomicInteger();
    private String answer = "[\"Wärmedämmung\",\"Köln\"]";
    private JSONObject request;
    @Before public void setup() throws Exception {
        TldrService.clearHistory();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/chat/completions", exchange -> {
            this.calls.incrementAndGet();
            try {
                this.request = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                final JSONObject response = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                        .put("message", new JSONObject().put("content", this.answer)).put("finish_reason", "stop")))
                        .put("usage", new JSONObject().put("prompt_tokens", 12).put("completion_tokens", 7));
                final byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (final Exception e) { throw new java.io.IOException(e); }
            finally { exchange.close(); }
        });
        this.server.start();
        this.model = new LLM.LLMModel(new LLM("http://127.0.0.1:" + this.server.getAddress().getPort(), "secret-not-returned", 2048, 8192, LLM.LLMType.OPENAI), "test", false, false);
    }
    @After public void close() { this.server.stop(0); TldrService.clearHistory(); }
    @Test public void automaticGateAndManualCallAreIndependent() throws Exception {
        assertFalse(TldrService.shouldRun(false, true, 80, 0));
        assertFalse(TldrService.shouldRun(true, true, 40, 0));
        assertFalse(TldrService.shouldRun(true, false, 80, 0));
        assertFalse(TldrService.shouldRun(true, true, 80, 1));
        assertTrue(TldrService.shouldRun(true, true, 41, 0));
        assertEquals(0, this.calls.get());
        final TldrService.Attempt result = TldrService.execute(this.model, "Eine Frage zu Wärmedämmung", "Return search terms", true);
        assertTrue(result.error, result.valid()); assertEquals(1, this.calls.get());
        assertEquals(this.answer, result.details.getString("raw"));
        assertEquals("Return search terms", result.details.getString("instruction"));
        assertEquals("wärmedämmung köln", result.words);
        assertEquals("Return search terms", this.request.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertEquals(12, result.details.getJSONObject("usage").getInt("promptTokens"));
        assertFalse(result.details.toString().contains("secret-not-returned"));
    }
    @Test public void invalidRawOutputIsVisibleAndNeverUsedAsSearchOrInstructions() throws Exception {
        for (final String raw : new String[]{"not json", "[]", "[1]", "<think>hidden</think>[\"rechner\"]"}) {
            this.answer = raw;
            final TldrService.Attempt result = TldrService.execute(this.model, "Computer Köln", "Return search terms", true);
            assertFalse(result.valid()); assertEquals(raw, result.details.getString("raw")); assertEquals("", result.words);
        }
        assertEquals("Other callers retain think-block filtering", "[\"rechner\"]",
                this.model.llm.chat(this.model.model, new LLM.Context("Existing role"), null, 200));
        this.answer = "[\"collection:secret site:other.example\"]";
        final TldrService.Attempt result = TldrService.execute(this.model, "Computer Köln", "Return search terms", true);
        assertFalse(result.words.contains(":")); // cannot change retrieval scope
    }
    @Test public void historyIsBoundedExpiresContentAndExcludesOtherUsers() throws Exception {
        final TldrService.Attempt result = TldrService.execute(this.model, "Private administrator question", "Return search terms", true);
        TldrService.remember(result, 0, 1, false, System.currentTimeMillis() + 5000);
        assertEquals(0, TldrService.history().length());
        for (int i = 0; i < 40; i++) TldrService.remember(result, 0, 1, true, 0);
        assertEquals(TldrService.MAX_HISTORY, TldrService.history().length());
        assertFalse(TldrService.history().getJSONObject(0).has("input"));
        assertFalse(TldrService.history().getJSONObject(0).has("raw"));
        TldrService.remember(result, 0, 1, true, System.currentTimeMillis() + 5000);
        assertTrue(TldrService.history().getJSONObject(0).has("raw"));
        assertFalse(TldrService.history(System.currentTimeMillis() + 6000).getJSONObject(0).has("raw"));
        assertEquals(0, TldrService.history(System.currentTimeMillis() + TldrService.HISTORY_MS + 1).length());
        TldrService.clearHistory(); assertEquals(0, TldrService.history().length());
    }
    @Test public void oversizedInputAndMissingModelDoNotCallAnEndpoint() {
        assertFalse(TldrService.execute(null, "Frage", "Terms", true).valid());
        assertFalse(TldrService.execute(this.model, "x".repeat(16001), "Terms", true).valid());
        assertEquals(0, this.calls.get());
    }
}
