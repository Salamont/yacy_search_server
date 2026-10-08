package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The knowledge prompt as data (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3, "Knowledge prompt"): the compiled-in default as
 * fallback, a custom version with revision, hash, source and time, the history without texts, and the rules of a draft.
 */
public class KnowledgePromptTest {

    static final String CUSTOM = LlmExtractor.SYSTEM_PROMPT.replace("for a knowledge graph", "for a knowledge graph of care providers");

    @Test
    public void theDefaultIsTheCompiledInPrompt() throws Exception {
        final KnowledgePrompt d = KnowledgePrompt.defaults();
        assertEquals(LlmExtractor.SYSTEM_PROMPT, d.text);
        assertEquals(KnowledgePrompt.SOURCE_DEFAULT, d.source);
        assertEquals(0, d.revision);
        assertEquals("the default's hash is the former PROMPT_HASH formula", LlmExtractor.PROMPT_HASH, d.hash);
        assertFalse(d.differsFromDefault());
        assertNull("the default passes its own rules", KnowledgePrompt.invalid(LlmExtractor.SYSTEM_PROMPT));
        final JSONObject j = d.json();
        assertEquals(0, j.getInt("activeVersion"));
        assertEquals(LlmExtractor.PROMPT_HASH, j.getString("activeHash"));
        assertEquals("default", j.getString("source"));
        assertTrue(j.isNull("modifiedAt"));
        assertFalse(j.getBoolean("differsFromDefault"));
        assertFalse("no text in the metadata", j.has("text"));
    }

    @Test
    public void theHashFollowsTheText() throws Exception {
        assertEquals(LlmExtractor.promptHash(CUSTOM), LlmExtractor.promptHash(CUSTOM));
        assertNotEquals(LlmExtractor.PROMPT_HASH, LlmExtractor.promptHash(CUSTOM));
        assertNotEquals(LlmExtractor.promptHash(CUSTOM), LlmExtractor.promptHash(CUSTOM + " "));
        assertEquals(16, LlmExtractor.promptHash(CUSTOM).length());
    }

    @Test
    public void aStoredVersionIsReadBack() throws Exception {
        final KnowledgePrompt custom = KnowledgePrompt.next(CUSTOM, 3, 1_700_000_000_000L);
        assertEquals(KnowledgePrompt.SOURCE_CUSTOM, custom.source);
        final String meta = custom.toMeta();
        assertTrue("a custom version keeps its text", new JSONObject(meta).getString("text").equals(CUSTOM));
        final KnowledgePrompt back = KnowledgePrompt.fromMeta(meta);
        assertEquals(CUSTOM, back.text);
        assertEquals(3, back.revision);
        assertEquals(1_700_000_000_000L, back.modifiedAt);
        assertEquals(custom.hash, back.hash);
        assertTrue(back.differsFromDefault());
        // the default text activated is the default, stored without a text: a new release's default applies to it
        final KnowledgePrompt reset = KnowledgePrompt.next(LlmExtractor.SYSTEM_PROMPT, 4, 1_700_000_000_001L);
        assertEquals(KnowledgePrompt.SOURCE_DEFAULT, reset.source);
        assertFalse(new JSONObject(reset.toMeta()).has("text"));
        assertEquals(LlmExtractor.SYSTEM_PROMPT, KnowledgePrompt.fromMeta(new JSONObject(reset.toMeta()).toString()).text);
        assertEquals(4, KnowledgePrompt.fromMeta(reset.toMeta()).revision);
        final String oldDefault = new JSONObject().put("revision", 5).put("source", "default").put("modifiedAt", 1L)
                .put("text", "an older default text that is not used").toString();
        assertEquals(LlmExtractor.SYSTEM_PROMPT, KnowledgePrompt.fromMeta(oldDefault).text);
    }

    @Test
    public void nothingUsableStoredIsTheDefault() throws Exception {
        for (final String stored : new String[] {null, "", "not json", "[]", "{\"source\":\"custom\"}",
            "{\"source\":\"custom\",\"revision\":2,\"text\":\"too short\"}", "{\"source\":\"custom\",\"text\":42}"}) {
            final KnowledgePrompt p = KnowledgePrompt.fromMeta(stored);
            assertEquals(String.valueOf(stored), LlmExtractor.SYSTEM_PROMPT, p.text);
            assertEquals(String.valueOf(stored), LlmExtractor.PROMPT_HASH, p.hash);
        }
    }

    @Test
    public void aDraftIsChecked() throws Exception {
        assertEquals("prompt_missing", KnowledgePrompt.invalid(null));
        assertEquals("prompt_missing", KnowledgePrompt.invalid("   \n "));
        assertEquals("prompt_too_short", KnowledgePrompt.invalid("Extract entities."));
        assertEquals("prompt_too_long", KnowledgePrompt.invalid("x".repeat(KnowledgePrompt.MAX_CHARS + 1)));
        assertNull(KnowledgePrompt.invalid("x".repeat(KnowledgePrompt.MAX_CHARS)));
        assertNull(KnowledgePrompt.invalid(CUSTOM));
        assertNull("tab, line breaks and umlauts are text", KnowledgePrompt.invalid(CUSTOM + "\r\n\tÄÖÜ – „Anführung“ 😀"));
        for (final String bad : new String[] {"\u0000", "\u0007", "\u001b[31m", "\u007f", "\u0085", " ", "\ud800"}) {
            assertEquals(bad, "prompt_control_characters", KnowledgePrompt.invalid(CUSTOM + bad));
        }
        for (final String secret : new String[] {"sk-abcdefghijklmnop1234", "Authorization: Bearer abcdefghijklmnop.qrstuvw",
            "api_key=abc123def456", "password: hunter22", "-----BEGIN RSA PRIVATE KEY-----", "AKIAABCDEFGHIJKLMNOP",
            "ghp_abcdefghijklmnopqrstuvwxyz0123456789", "xoxb-1234567890-abc"}) {
            assertEquals(secret, "prompt_secret_like", KnowledgePrompt.invalid(CUSTOM + " " + secret));
        }
        assertNull("words alone are no secret", KnowledgePrompt.invalid(CUSTOM + " Never output a password or an API key."));
    }

    @Test
    public void theHistoryHasNoTextAndIsBounded() throws Exception {
        String history = null;
        for (int r = 1; r <= KnowledgePrompt.HISTORY_MAX + 5; r++) {
            history = KnowledgePrompt.next(r % 2 == 0 ? LlmExtractor.SYSTEM_PROMPT : CUSTOM, r, 1000L + r).historyWith(history);
        }
        final JSONArray h = new JSONArray(history);
        assertEquals(KnowledgePrompt.HISTORY_MAX, h.length());
        assertEquals("the newest last", KnowledgePrompt.HISTORY_MAX + 5, h.getJSONObject(h.length() - 1).getInt("revision"));
        for (int i = 0; i < h.length(); i++) {
            final JSONObject e = h.getJSONObject(i);
            assertEquals(java.util.Set.of("revision", "hash", "source", "activatedAt"), e.keySet());
        }
        assertFalse(history.contains("You extract"));
        assertEquals(KnowledgePrompt.HISTORY_MAX + 5, KnowledgePrompt.lastRevision(KnowledgePrompt.defaults(), history));
        assertEquals("an unreadable history counts from the active revision", 7,
                KnowledgePrompt.lastRevision(KnowledgePrompt.next(CUSTOM, 7, 1L), "nonsense"));
    }
}
