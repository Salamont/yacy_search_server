/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

/** Tools need an explicit server-side release; tooling=supported alone releases nothing. */
public class ToolReleaseTest {

    private final Map<String, String> config = new HashMap<>();

    private void use(final String... keyValues) {
        for (int i = 0; i < keyValues.length; i += 2) this.config.put(keyValues[i], keyValues[i + 1]);
        ToolProvider.useSettings((key, dflt) -> this.config.getOrDefault(key, dflt));
    }

    @After
    public void reset() {
        ToolProvider.useSettings(null);
    }

    private static JSONObject clientBody() throws Exception {
        final JSONObject body = new JSONObject(true);
        body.put("model", "chat");
        body.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi")));
        // a client tries to bring its own tools
        body.put("tools", new JSONArray().put(new JSONObject().put("type", "function")
                .put("function", new JSONObject().put("name", "http_json").put("description", "client copy"))));
        body.put("tool_choice", "required");
        body.put("functions", new JSONArray().put(new JSONObject().put("name", "webfetch")));
        return body;
    }

    private static List<String> offered(final JSONObject prepared) throws org.json.JSONException {
        final List<String> names = new ArrayList<>();
        final JSONArray tools = prepared.optJSONArray("tools");
        if (tools == null) return names;
        for (int i = 0; i < tools.length(); i++) names.add(tools.getJSONObject(i).getJSONObject("function").getString("name"));
        return names;
    }

    @Test
    public void toolingSupportedAloneOffersNoTool() throws Exception {
        use();
        final JSONObject prepared = ToolCallProtocol.prepareToolRequestBody(clientBody(), false, true);
        assertFalse(prepared.has("tools"));
        assertFalse(prepared.has("tool_choice"));
        assertFalse(prepared.has("functions"));
        for (final String tool : new String[] {"http_json", "webfetch", "search", "calculator", "datetime"}) {
            assertEquals(tool, 0, ToolProvider.allowedCallsPerTurn(tool));
            assertTrue(tool, ToolProvider.executeTool(tool, "{}").contains("not released"));
        }
    }

    @Test
    public void explicitlyReleasedToolIsOfferedOnlyToToolCapableModels() throws Exception {
        use("ai.tools.calculator.enabled", "true", "ai.tools.webfetch.enabled", "true");
        JSONObject prepared = ToolCallProtocol.prepareToolRequestBody(clientBody(), false, true);
        assertEquals(List.of("calculator", "webfetch"), offered(prepared));
        assertEquals("auto", prepared.getString("tool_choice"));
        assertTrue(ToolProvider.allowedCallsPerTurn("calculator") > 0);
        assertFalse(ToolProvider.executeTool("calculator", "{\"expression\":\"1+2\"}").contains("not released"));
        assertTrue(ToolProvider.executeTool("http_json", "{}").contains("not released"));
        // the client copy of the http_json definition is gone
        assertFalse(prepared.toString().contains("client copy"));

        // the same release does nothing for a model without tool support
        prepared = ToolCallProtocol.prepareToolRequestBody(clientBody(), false, false);
        assertFalse(prepared.has("tools"));
        assertFalse(prepared.has("tool_choice"));
    }

    @Test
    public void maxCallsPerTurnZeroDisablesAReleasedTool() throws Exception {
        use("ai.tools.calculator.enabled", "true", "ai.tools.calculator.maxCallsPerTurn", "0",
                "ai.tools.datetime.enabled", "true", "ai.tools.datetime.maxCallsPerTurn", "2");
        final JSONObject prepared = ToolCallProtocol.prepareToolRequestBody(clientBody(), false, true);
        assertEquals(List.of("datetime"), offered(prepared));
        assertEquals(0, ToolProvider.allowedCallsPerTurn("calculator"));
        assertEquals(2, ToolProvider.allowedCallsPerTurn("datetime"));
        assertTrue(ToolProvider.executeTool("calculator", "{\"expression\":\"1+2\"}").contains("not released"));
        assertFalse(ToolProvider.listTools().stream().filter(t -> t.name.equals("calculator")).findFirst().get().enabled);
        assertTrue(ToolProvider.listTools().stream().filter(t -> t.name.equals("datetime")).findFirst().get().enabled);
    }

    @Test
    public void unknownAndMalformedReleaseValues() {
        use("ai.tools.calculator.enabled", "yes", "ai.tools.nonexistent.enabled", "true");
        assertEquals(0, ToolProvider.allowedCallsPerTurn("calculator"));
        assertEquals(0, ToolProvider.allowedCallsPerTurn("nonexistent"));
        assertTrue(ToolProvider.executeTool("nonexistent", "{}").contains("Unknown tool"));
    }

    @Test
    public void httpJsonIsReadOnlyInTheChat() throws Exception {
        use("ai.tools.http_json.enabled", "true");
        final JSONObject definition = new net.yacy.ai.tools.HttpJsonTool().definition().getJSONObject("function");
        assertFalse(definition.getJSONObject("parameters").getJSONObject("properties").has("body"));
        for (final String method : new String[] {"POST", "PUT", "PATCH", "DELETE", "post"}) {
            final String result = ToolProvider.executeTool("http_json",
                    new JSONObject().put("url", "https://api.example.org/items").put("method", method).put("body", new JSONObject()).toString());
            assertTrue(method + ": " + result, result.contains("is not allowed: http_json is read-only"));
        }
        final String withBody = ToolProvider.executeTool("http_json",
                new JSONObject().put("url", "https://api.example.org/items").put("body", "x").toString());
        assertTrue(withBody, withBody.contains("request body is not allowed"));
        // GET passes the method check and then the network policy: a local target is refused
        final String local = ToolProvider.executeTool("http_json", new JSONObject().put("url", "http://127.0.0.1:8090/api/status").toString());
        assertTrue(local, local.contains("URL not allowed (non_public_address)"));
    }

    @Test
    public void webfetchRefusesLocalTargets() throws Exception {
        use("ai.tools.webfetch.enabled", "true");
        for (final String url : new String[] {"http://127.0.0.1:8090/ConfigAccounts_p.html", "http://localhost/",
                "http://169.254.169.254/latest/meta-data/", "file:///etc/passwd", "http://ollama:11434/api/tags"}) {
            final String result = ToolProvider.executeTool("webfetch", new JSONObject().put("url", url).toString());
            assertTrue(url + ": " + result, result.contains("URL not allowed"));
        }
    }
}
