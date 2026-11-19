/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The search goal of a chat turn (docs/SCOUTRO_RAG_QUALITY.md, section 12). A follow-up such as "Such noch mal" or
 * "Such noch mal gezielt nach Stellenanzeigen und prüfe die Originalquellen" names no topic of its own: its automatic
 * search would find nothing or anything. Such a turn searches with the goal of the conversation instead: the latest
 * earlier user message with a topic, joined with the follow-up (which may add a word, e.g. "Such noch mal nach
 * Robotik"). A message without the words of a retry is always its own goal. Only the user's own messages of
 * this request are read; the scope of the search stays the request's.
 */
public final class RagFollowUp {

    /** characters of a goal at most */
    static final int MAX_GOAL_CHARS = 800;

    /** "such noch mal", "nochmal suchen", "versuch es erneut", "search again", "noch mal", "erneut" ... (folded text) */
    private static final Pattern RETRY = Pattern.compile(
            "(?:^|[^\\p{L}])(?:"
            + "(?:such|schau|guck|pruf|check|recherchier)\\p{L}*\\s+(?:\\p{L}+\\s+){0,2}?(?:noch\\s*(?:ein)?mal|nochmal|erneut|wieder|weiter|gezielt|genauer)"
            + "|(?:noch\\s*(?:ein)?mal|nochmal|erneut)\\s+(?:\\p{L}+\\s+){0,2}?(?:such|schau|nachseh|pruf|recherchier|versuch)\\p{L}*"
            + "|versuch\\p{L}*\\s+(?:es\\s+)?(?:noch\\s*(?:ein)?mal|nochmal|erneut)"
            + "|(?:search|look|try)\\s+(?:it\\s+)?again"
            + ")(?:$|[^\\p{L}])"
            + "|^\\s*(?:und\\s+)?(?:noch\\s*(?:ein)?mal|nochmal|erneut|weiter|mehr|again)\\s*[.!?]*\\s*$");

    /** stems of words that ask for a search or a source but name no topic */
    private static final String[] NO_TOPIC = {"such", "nochmal", "erneut", "wieder", "weiter", "gezielt", "genau", "pruf", "check",
            "recherch", "versuch", "quell", "originalquell", "beleg", "link", "nachseh", "schau", "guck", "treffer", "ergebnis",
            "again", "search", "result", "source", "andere", "mehr", "bitte", "einmal"};

    private RagFollowUp() {
    }

    /** The goal of a turn. */
    public static final class Goal {
        /** the text the automatic search and the job search use */
        public final String text;
        /** true when it carries an earlier message's topic */
        public final boolean followUp;
        /** true when that earlier message names a topic of its own (not only another retry) */
        public final boolean topical;

        Goal(final String text, final boolean followUp, final boolean topical) {
            this.text = text;
            this.followUp = followUp;
            this.topical = topical;
        }
    }

    /** Whether a message asks to search again. */
    public static boolean retry(final String text) {
        return text != null && RETRY.matcher(RagQuery.fold(text.trim())).find();
    }

    /** The topic terms of a message: its search terms without the words of intent, retry and sources. */
    static List<RagQuery.Term> topics(final String text) {
        final List<RagQuery.Term> out = new ArrayList<>();
        for (final RagQuery.Term t : RagQuery.prepare(text == null ? "" : text).terms) {
            if (!noTopic(t.stem) && !JobSearch.intentWord(t.stem)) out.add(t);
        }
        return out;
    }

    static boolean noTopic(final String stem) {
        final String s = stem.toLowerCase(Locale.ROOT);
        for (final String p : NO_TOPIC) if (s.startsWith(p)) return true;
        return false;
    }

    /**
     * @param messages  the request's messages (user messages read with their text part only)
     * @param current   index of the current (latest) user message
     * @param question  its text
     */
    public static Goal goal(final JSONArray messages, final int current, final String question) {
        final String own = question == null ? "" : question.trim();
        if (!retry(own) || messages == null || current <= 0) return new Goal(own, false, false);
        final List<String> parts = new ArrayList<>();
        parts.add(own);
        boolean topical = false;
        for (int i = current - 1; i >= 0; i--) {
            final JSONObject message = messages.optJSONObject(i);
            if (message == null || !"user".equals(message.optString("role", ""))) continue;
            final String text = text(message);
            if (text.isEmpty()) continue;
            parts.add(0, text);
            if (!topics(text).isEmpty() && !retry(text)) {
                topical = true;
                break;
            }
        }
        if (parts.size() == 1) return new Goal(own, false, false);
        String goal = String.join(" ", parts);
        if (goal.length() > MAX_GOAL_CHARS) goal = goal.substring(goal.length() - MAX_GOAL_CHARS);
        return new Goal(goal, true, topical);
    }

    /** The text part of a message (attached files are other parts). */
    static String text(final JSONObject message) {
        final Object content = message.opt("content");
        if (content instanceof String) return ((String) content).trim();
        if (content instanceof JSONArray) {
            final JSONArray array = (JSONArray) content;
            for (int i = 0; i < array.length(); i++) {
                final JSONObject part = array.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) return part.optString("text", "").trim();
            }
        }
        return "";
    }
}
