/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.htroot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Stored API keys are never rendered, and an empty field keeps them. */
public class LLMSelection_pTest {

    private static JSONArray stored() throws Exception {
        return new JSONArray()
                .put(new JSONObject().put("service", "OPENAI").put("model", "a").put("hoststub", "https://api.example.org/").put("api_key", "sk-row-a"))
                .put(new JSONObject().put("service", "OPENAI").put("model", "b").put("hoststub", "https://api.example.org").put("api_key", "sk-row-b"))
                .put(new JSONObject().put("service", "OLLAMA").put("model", "c").put("hoststub", "http://ollama:11434").put("api_key", ""));
    }

    @Test
    public void emptyFieldKeepsTheStoredKey() throws Exception {
        final JSONObject inference = new JSONObject().put("service", "OPENROUTER").put("hoststub", "https://router.example").put("api_key", "sk-inference");
        // same row: its own key
        assertEquals("sk-row-b", LLMSelection_p.storedKey(stored(), inference, "OPENAI", "https://api.example.org", "b"));
        // a new model on the same endpoint: the endpoint key
        assertEquals("sk-row-a", LLMSelection_p.storedKey(stored(), inference, "OPENAI", "https://api.example.org/", "new"));
        // only the inference system knows the endpoint
        assertEquals("sk-inference", LLMSelection_p.storedKey(stored(), inference, "OPENROUTER", "https://router.example/", "x"));
        assertEquals("", LLMSelection_p.storedKey(stored(), inference, "OLLAMA", "http://ollama:11434", "c"));
        assertEquals("", LLMSelection_p.storedKey(stored(), inference, "OPENAI", "https://other.example", "a"));

        final JSONObject row = new JSONObject().put("service", "OPENAI").put("model", "a").put("hoststub", "https://api.example.org").put("api_key", "");
        LLMSelection_p.keepStoredKey(row, stored(), inference, "");
        assertEquals("sk-row-a", row.getString("api_key"));
        // a newly typed key replaces the stored one
        final JSONObject typed = new JSONObject().put("service", "OPENAI").put("model", "a").put("hoststub", "https://api.example.org").put("api_key", "sk-new");
        LLMSelection_p.keepStoredKey(typed, stored(), inference, "");
        assertEquals("sk-new", typed.getString("api_key"));
        // only an explicit clear removes it
        final JSONObject cleared = new JSONObject().put("service", "OPENAI").put("model", "a").put("hoststub", "https://api.example.org/").put("api_key", "");
        LLMSelection_p.keepStoredKey(cleared, stored(), inference, "https://api.example.org");
        assertEquals("", cleared.getString("api_key"));
    }

    @Test
    public void theFormatProbeVersionIsKeptAndNothingElseChanges() throws Exception {
        final JSONObject in = new JSONObject()
                .put("OLLAMA|http://ollama:11434|llama3.1:8b", new JSONObject().put("thinking", "unsupported").put("tooling", "supported")
                        .put("vision", "unsupported").put("format", "unsupported")) // the mood probe up to 0.8.3: no version
                .put("OPENAI|https://api.example.org|m", new JSONObject().put("thinking", true).put("tooling", false)
                        .put("vision", "unknown").put("format", "ignored").put("format_probe", 2))
                .put("LMSTUDIO|http://lm:1234|x", new JSONObject().put("format", "supported").put("format_probe", "junk"));
        final JSONObject out = LLMSelection_p.normalizeModelCapabilities(in);
        final JSONObject legacy = out.getJSONObject("OLLAMA|http://ollama:11434|llama3.1:8b");
        assertEquals("the stored legacy value stays until a new probe", "unsupported", legacy.getString("format"));
        assertFalse(legacy.has("format_probe"));
        assertEquals("unknown", net.yacy.ai.LLM.formatCapability(legacy));
        assertEquals("unsupported", legacy.getString("thinking"));
        assertEquals("supported", legacy.getString("tooling"));
        assertEquals("unsupported", legacy.getString("vision"));
        final JSONObject current = out.getJSONObject("OPENAI|https://api.example.org|m");
        assertEquals(2, current.getInt("format_probe"));
        assertEquals("ignored", net.yacy.ai.LLM.formatCapability(current));
        assertEquals("supported", current.getString("thinking"));
        assertEquals("unsupported", current.getString("tooling"));
        assertEquals("unknown", current.getString("vision"));
        final JSONObject junk = out.getJSONObject("LMSTUDIO|http://lm:1234|x");
        assertFalse(junk.has("format_probe"));
        assertEquals("unknown", net.yacy.ai.LLM.formatCapability(junk));
        // the page loads the probe and reads stored format values only through it
        final String html = new String(Files.readAllBytes(Paths.get("htroot/LLMSelection_p.html")), StandardCharsets.UTF_8);
        assertTrue(html.contains("<script src=\"env/scoutro/format-probe.js\"></script>"));
        assertTrue(html.contains("format: ScoutroFormatProbe.stored(entry)"));
        assertFalse("no mood probe", html.contains("FORMAT_TEST_CASES") || html.contains("\"literal\""));
    }

    @Test
    public void templateRendersNoKey() throws Exception {
        final String html = new String(Files.readAllBytes(Paths.get("htroot/LLMSelection_p.html")), StandardCharsets.UTF_8);
        final String java = new String(Files.readAllBytes(Paths.get("source/net/yacy/htroot/LLMSelection_p.java")), StandardCharsets.UTF_8);
        assertFalse(html.contains("#[api_key]#"));
        assertFalse(html.contains("#[llm_apikey]#"));
        assertTrue(html.contains("#(api_key_set)#0::1#(/api_key_set)#"));
        assertFalse(java.contains("_api_key\", row.optString(\"api_key\""));
        assertFalse(java.contains("\"llm_apikey\", inference.optString(\"api_key\""));
    }
}
