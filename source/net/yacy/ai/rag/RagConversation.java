/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Keeps a multi-turn chat within the prompt budget.
 * <p>
 * The chat page attaches the search document of an answer to the user message and sends it again in
 * every later round, so the prompt grew with each turn. Here a turn with a new search drops all
 * earlier search documents (the new sources supersede them); a turn without search keeps only the
 * most recent one, so follow-up questions about "[2]" still work. Files the user attached are
 * kept. Earlier answers lose their citation numbers when their sources are no longer sent, so an old
 * "[1]" is not confused with source [1] of the current search. Then the oldest messages are removed
 * until the history fits its share of the budget.
 */
public final class RagConversation {

    /** file name prefixes of search documents: current and older chat pages */
    static final String[] SEARCH_FILE_PREFIXES = {"scoutro-sources", "search_result_"};
    /** citation markers as {@link RagCitations} reads them, with the space before them */
    private static final Pattern CITATION = Pattern.compile("[ \\t]*\\[\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*\\](?!\\()");

    private RagConversation() {
    }

    /** Result of {@link #pruneSearchAttachments}. */
    public static final class Pruned {
        /** text of the kept search document (turn without new search), or null */
        public final String keptSearchText;
        /** index of the message holding the kept search document, or -1 */
        public final int keptIndex;
        public final int removed;
        /** earlier answers whose citation numbers were removed because their sources are gone */
        public final int staleAnswers;

        Pruned(final String keptSearchText, final int keptIndex, final int removed, final int staleAnswers) {
            this.keptSearchText = keptSearchText;
            this.keptIndex = keptIndex;
            this.removed = removed;
            this.staleAnswers = staleAnswers;
        }

        /** the kept search document is still sent after {@link RagConversation#trimHistory} removed that many messages */
        public boolean keptAfterTrim(final int trimmed) {
            return this.keptSearchText != null && this.keptIndex > trimmed;
        }
    }

    /**
     * @param messages system message first, current user message last
     * @param freshRetrieval this turn searches again
     */
    public static Pruned pruneSearchAttachments(final JSONArray messages, final boolean freshRetrieval) throws JSONException {
        String kept = null;
        int keptIndex = -1;
        int removed = 0;
        for (int i = messages.length() - 1; i >= 0; i--) {
            final JSONObject message = messages.optJSONObject(i);
            if (message == null || !"user".equals(message.optString("role"))) continue;
            final Object content = message.opt("content");
            if (!(content instanceof JSONArray)) continue;
            final JSONArray parts = (JSONArray) content;
            for (int p = parts.length() - 1; p >= 0; p--) {
                final JSONObject part = parts.optJSONObject(p);
                final String text = searchDocument(part);
                if (text == null) continue;
                if (!freshRetrieval && kept == null) {
                    kept = text;
                    keptIndex = i;
                } else {
                    parts.remove(p);
                    removed++;
                }
            }
            normalize(message);
        }
        // answers before the kept search document cite sources the model no longer receives
        int stale = 0;
        for (int i = 1; i < messages.length() - 1; i++) {
            if (keptIndex >= 0 && i > keptIndex) break;
            final JSONObject message = messages.optJSONObject(i);
            if (message != null && "assistant".equals(message.optString("role")) && stripCitations(message)) stale++;
        }
        return new Pruned(kept, keptIndex, removed, stale);
    }

    private static boolean stripCitations(final JSONObject message) throws JSONException {
        final Object content = message.opt("content");
        boolean changed = false;
        if (content instanceof String) {
            final String text = CITATION.matcher((String) content).replaceAll("");
            changed = !text.equals(content);
            if (changed) message.put("content", text);
        } else if (content instanceof JSONArray) {
            final JSONArray parts = (JSONArray) content;
            for (int p = 0; p < parts.length(); p++) {
                final JSONObject part = parts.optJSONObject(p);
                if (part == null || !"text".equals(part.optString("type"))) continue;
                final String before = part.optString("text", "");
                final String text = CITATION.matcher(before).replaceAll("");
                if (!text.equals(before)) {
                    part.put("text", text);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** decoded text of a search document attachment, or null for anything else */
    static String searchDocument(final JSONObject part) {
        if (part == null || !"image_url".equals(part.optString("type"))) return null;
        final JSONObject image = part.optJSONObject("image_url");
        final String url = image == null ? "" : image.optString("url", "");
        if (!url.startsWith("data:text/")) return null;
        final int comma = url.indexOf(',');
        if (comma < 0) return null;
        final String text;
        try {
            text = url.substring(0, comma).contains(";base64")
                    ? new String(Base64.getDecoder().decode(url.substring(comma + 1)), StandardCharsets.UTF_8)
                    : url.substring(comma + 1);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        final String name = part.optString("filename", "");
        for (final String prefix : SEARCH_FILE_PREFIXES) if (name.startsWith(prefix)) return text;
        // without a file name: the formats written by the search (new: numbered entries, old: Source lines)
        if (text.startsWith("[1] ") && text.contains("\nURL: ")) return text;
        if (text.startsWith("## ") && text.contains("\nSource: ")) return text;
        return null;
    }

    /** Replace the text of the (single remaining) search document attachment, e.g. by a shortened version. */
    public static void replaceSearchDocument(final JSONArray messages, final String text) throws JSONException {
        for (int i = messages.length() - 1; i >= 0; i--) {
            final JSONObject message = messages.optJSONObject(i);
            if (message == null || !"user".equals(message.optString("role")) || !(message.opt("content") instanceof JSONArray)) continue;
            final JSONArray parts = (JSONArray) message.opt("content");
            for (int p = 0; p < parts.length(); p++) {
                final JSONObject part = parts.optJSONObject(p);
                if (searchDocument(part) == null) continue;
                part.getJSONObject("image_url").put("url", "data:text/markdown;base64,"
                        + Base64.getEncoder().encodeToString((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
                if (!part.optString("filename", "").startsWith(SEARCH_FILE_PREFIXES[0])) part.put("filename", SEARCH_FILE_PREFIXES[0] + ".md");
                return;
            }
        }
    }

    private static void normalize(final JSONObject message) throws JSONException {
        final Object content = message.opt("content");
        if (!(content instanceof JSONArray)) return;
        final JSONArray parts = (JSONArray) content;
        if (parts.length() == 1 && "text".equals(parts.optJSONObject(0) == null ? "" : parts.optJSONObject(0).optString("type"))) {
            message.put("content", parts.optJSONObject(0).optString("text", ""));
        }
    }

    /** characters of a message as the model receives it (text and text attachments) */
    public static int chars(final JSONObject message) {
        if (message == null) return 0;
        final Object content = message.opt("content");
        if (content instanceof String) return ((String) content).length();
        int n = 0;
        if (content instanceof JSONArray) {
            final JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                final JSONObject part = parts.optJSONObject(i);
                if (part == null) continue;
                if ("text".equals(part.optString("type"))) n += part.optString("text", "").length();
                final JSONObject image = part.optJSONObject("image_url");
                if (image != null && image.optString("url", "").startsWith("data:text/")) n += image.optString("url", "").length() * 3 / 4;
            }
        }
        return n;
    }

    /** characters of the history: all messages between the system message and the current user message */
    public static int historyChars(final JSONArray messages) {
        int n = 0;
        for (int i = 1; i < messages.length() - 1; i++) n += chars(messages.optJSONObject(i));
        return n;
    }

    /**
     * Remove the oldest history messages until the history fits allowanceChars. The system message and
     * the current user message are never removed.
     *
     * @return number of removed messages
     */
    public static int trimHistory(final JSONArray messages, final int allowanceChars) {
        int removed = 0;
        while (messages.length() > 2 && historyChars(messages) > allowanceChars) {
            messages.remove(1);
            removed++;
        }
        // a history must not start with an assistant answer whose question was removed
        while (removed > 0 && messages.length() > 2 && "assistant".equals(messages.optJSONObject(1) == null ? "" : messages.optJSONObject(1).optString("role"))) {
            messages.remove(1);
            removed++;
        }
        return removed;
    }
}
