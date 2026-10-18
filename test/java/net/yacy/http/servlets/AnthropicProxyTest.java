/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.http.servlets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.ai.LLM;

/**
 * The service decisions of the RAG chat and the admin passthrough for ANTHROPIC: no temperature (also not a client's),
 * the other services as before; the service a page names for an endpoint is only a known service name.
 */
public class AnthropicProxyTest {

    @Test
    public void theRagChatSendsNoTemperatureToAnthropic() throws Exception {
        final JSONObject client = new JSONObject().put("temperature", 0.7);
        RAGProxyServlet.applyTemperature(client, LLM.LLMType.ANTHROPIC, 0.2);
        assertFalse("also the client's is removed", client.has("temperature"));
        final JSONObject none = new JSONObject();
        RAGProxyServlet.applyTemperature(none, LLM.LLMType.ANTHROPIC, 0.2);
        assertFalse(none.has("temperature"));
    }

    @Test
    public void theOtherServicesKeepTheClientsOrTheRagTemperature() throws Exception {
        for (final LLM.LLMType type : new LLM.LLMType[] {LLM.LLMType.OPENAI, LLM.LLMType.OPENROUTER, LLM.LLMType.OLLAMA, LLM.LLMType.LMSTUDIO,
                LLM.LLMType.OTHER}) {
            final JSONObject client = new JSONObject().put("temperature", 0.7);
            RAGProxyServlet.applyTemperature(client, type, 0.2);
            assertEquals(type.name(), 0.7, client.getDouble("temperature"), 0.0);
            final JSONObject none = new JSONObject();
            RAGProxyServlet.applyTemperature(none, type, 0.2);
            assertEquals(type.name(), 0.2, none.getDouble("temperature"), 0.0);
        }
    }

    @Test
    public void onlyAKnownServiceNameSelectsServiceHeaders() {
        assertEquals(LLM.LLMType.ANTHROPIC, LLMAdminProxyServlet.serviceType("ANTHROPIC"));
        assertEquals(LLM.LLMType.ANTHROPIC, LLMAdminProxyServlet.serviceType(" ANTHROPIC "));
        assertEquals(LLM.LLMType.OPENAI, LLMAdminProxyServlet.serviceType("OPENAI"));
        assertNull(LLMAdminProxyServlet.serviceType(null));
        assertNull(LLMAdminProxyServlet.serviceType(""));
        assertNull(LLMAdminProxyServlet.serviceType("anthropic-version: 1"));
    }
}
