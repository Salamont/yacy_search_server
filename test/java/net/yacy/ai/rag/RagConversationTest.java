/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** A multi-turn chat stays within its budget: search documents are not sent again and again. */
public class RagConversationTest {

    private static JSONObject attachment(final String name, final String text) throws Exception {
        return new JSONObject().put("type", "image_url").put("filename", name).put("image_url", new JSONObject()
                .put("url", "data:text/markdown;base64," + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))));
    }

    private static String sources(final int round) {
        final StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            if (i > 1) text.append("\n\n");
            text.append('[').append(i).append("] Quelle ").append(round).append('-').append(i).append("\nURL: https://r").append(round)
                .append(".example/").append(i).append("\nCollection: edelsenior-web\nText: ").append("x".repeat(400));
        }
        return text.toString();
    }

    /** the chat page: every answered user message carries its search document */
    private static JSONArray history(final int rounds, final boolean userFile) throws Exception {
        final JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", "rules"));
        for (int round = 1; round <= rounds; round++) {
            final JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", "Frage " + round));
            content.put(attachment("scoutro-sources-edelsenior-web.md", sources(round)));
            if (userFile && round == 1) content.put(attachment("notizen.md", "meine eigenen Notizen"));
            messages.put(new JSONObject().put("role", "user").put("content", content));
            messages.put(new JSONObject().put("role", "assistant").put("content", "Antwort " + round + " [1]"));
        }
        messages.put(new JSONObject().put("role", "user").put("content", "Neue Frage"));
        return messages;
    }

    @Test
    public void aNewSearchDropsAllEarlierSearchDocuments() throws Exception {
        final JSONArray messages = history(5, true);
        final int before = RagConversation.historyChars(messages);
        final RagConversation.Pruned pruned = RagConversation.pruneSearchAttachments(messages, true);
        assertEquals(5, pruned.removed);
        assertNull(pruned.keptSearchText);
        assertTrue(RagConversation.historyChars(messages) < before / 5);
        assertTrue(messages.toString().contains("notizen.md")); // files of the user stay
        // the earlier answers cite sources that are no longer sent: their numbers go
        assertEquals(5, pruned.staleAnswers);
        for (int i = 0; i < messages.length(); i++) {
            final JSONObject message = messages.getJSONObject(i);
            if ("assistant".equals(message.getString("role"))) assertFalse(message.getString("content"), message.getString("content").contains("["));
        }
    }

    @Test
    public void aTurnWithoutSearchKeepsOnlyTheLatestSearchDocument() throws Exception {
        final JSONArray messages = history(5, false);
        final RagConversation.Pruned pruned = RagConversation.pruneSearchAttachments(messages, false);
        assertEquals(4, pruned.removed);
        assertTrue(pruned.keptSearchText.startsWith("[1] Quelle 5-1"));
        assertEquals(6, RagContext.parse(pruned.keptSearchText).size());
        assertEquals(messages.length() - 3, pruned.keptIndex); // the user message of round 5
        assertTrue(pruned.keptAfterTrim(messages.length() - 4));
        assertFalse(pruned.keptAfterTrim(messages.length() - 3));
        // only the answer to the kept search document keeps its citations
        assertEquals(4, pruned.staleAnswers);
        assertEquals("Antwort 4", messages.getJSONObject(messages.length() - 4).getString("content"));
        assertEquals("Antwort 5 [1]", messages.getJSONObject(messages.length() - 2).getString("content"));
        // a shortened version replaces the kept document
        RagConversation.replaceSearchDocument(messages, RagContext.limitEntries(pruned.keptSearchText, 1000));
        final RagConversation.Pruned again = RagConversation.pruneSearchAttachments(messages, false);
        assertTrue(again.keptSearchText.length() <= 1000);
    }

    @Test
    public void historyGrowthIsBoundedOverManyTurns() throws Exception {
        int previous = 0;
        for (int rounds = 1; rounds <= 12; rounds++) {
            final JSONArray messages = history(rounds, false);
            RagConversation.pruneSearchAttachments(messages, true);
            RagConversation.trimHistory(messages, 2000);
            final int chars = RagConversation.historyChars(messages);
            assertTrue("round " + rounds + ": " + chars, chars <= 2000);
            assertEquals("system", messages.getJSONObject(0).getString("role"));
            assertEquals("Neue Frage", messages.getJSONObject(messages.length() - 1).getString("content"));
            if (messages.length() > 2) assertEquals("user", messages.getJSONObject(1).getString("role"));
            previous = chars;
        }
        assertTrue(previous > 0);
    }

    @Test
    public void searchDocumentsOfOlderChatPagesAreRecognized() throws Exception {
        final JSONObject old = attachment("search_result_pflegeheim.md", "## Titel\nText\nSource: https://a.example/\n");
        assertTrue(RagConversation.searchDocument(old) != null);
        final JSONObject unnamed = new JSONObject().put("type", "image_url").put("image_url", new JSONObject()
                .put("url", "data:text/markdown;base64," + Base64.getEncoder().encodeToString(sources(1).getBytes(StandardCharsets.UTF_8))));
        assertTrue(RagConversation.searchDocument(unnamed) != null);
        assertNull(RagConversation.searchDocument(attachment("notizen.md", "nur Text")));
        assertNull(RagConversation.searchDocument(new JSONObject().put("type", "image_url")
                .put("image_url", new JSONObject().put("url", "data:image/png;base64,AAAA"))));
    }
}
