/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The search goal of a follow-up message (docs/SCOUTRO_RAG_QUALITY.md, section 12). */
public class RagFollowUpTest {

    static final String FIRST = "Welche Stellenanzeigen gibt es zu Java und ML?";

    private static JSONArray chat(final Object... userAndAssistant) throws Exception {
        final JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", "rules"));
        for (int i = 0; i < userAndAssistant.length; i++) {
            messages.put(new JSONObject().put("role", i % 2 == 0 ? "user" : "assistant").put("content", userAndAssistant[i]));
        }
        return messages;
    }

    private static RagFollowUp.Goal goal(final JSONArray messages) {
        final int last = messages.length() - 1;
        return RagFollowUp.goal(messages, last, RagFollowUp.text(messages.optJSONObject(last)));
    }

    @Test
    public void aRetryTakesTheGoalOfTheConversation() throws Exception {
        for (final String retry : new String[] {"Such noch mal", "Such noch mal gezielt nach Stellenanzeigen und prüfe die Originalquellen",
                "Bitte nochmal suchen", "Versuch es erneut.", "noch mal", "Search again", "Schau bitte noch einmal nach"}) {
            assertTrue(retry, RagFollowUp.retry(retry));
            final RagFollowUp.Goal goal = goal(chat(FIRST, "Keine Stellen gefunden [1].", retry));
            assertTrue(retry, goal.followUp);
            assertTrue(retry, goal.text.startsWith(FIRST));
            assertTrue(retry, JobSearch.intent(goal.text));
            assertEquals(retry, java.util.List.of("Java", "ML"), JobSearch.plan(goal.text).criteria());
        }
        // a retry that adds a subject keeps the goal and adds it
        final RagFollowUp.Goal more = goal(chat(FIRST, "…", "Such noch mal nach Robotik"));
        assertTrue(more.followUp);
        assertEquals(java.util.List.of("Java", "ML", "Robotik"), JobSearch.plan(more.text).criteria());
        // two retries in a row still reach the goal; the page's content array is read as text
        final JSONArray twice = chat(FIRST, "…", new JSONArray().put(new JSONObject().put("type", "text").put("text", "Such noch mal")), "…",
                "nochmal suchen bitte");
        assertTrue(goal(twice).text.startsWith(FIRST));
    }

    @Test
    public void aNewQuestionIsItsOwnGoal() throws Exception {
        for (final String own : new String[] {"Welche Pflegeheime gibt es in Nebenstadt?", "Was macht die Tapir Consulting AG?",
                "Noch eine Frage: wer betreibt das Haus Lindenhof?"}) {
            final RagFollowUp.Goal goal = goal(chat(FIRST, "…", own));
            assertFalse(own, goal.followUp);
            assertEquals(own, goal.text);
        }
        // a retry as the first message has nothing to take
        final RagFollowUp.Goal first = goal(chat("Such noch mal"));
        assertFalse(first.followUp);
        assertEquals("Such noch mal", first.text);
    }
}
