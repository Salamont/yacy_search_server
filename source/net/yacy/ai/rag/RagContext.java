/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The search context of one RAG answer: a prompt budget derived from the model's context window
 * and numbered source entries that fit into it.
 * <p>
 * Budget: num_ctx tokens minus the answer (max_tokens), a safety reserve, the system prompt and
 * the question; the rest is shared by the chat history (at most {@link #HISTORY_SHARE}) and the
 * sources. Tokens are estimated with {@link #CHARS_PER_TOKEN} characters per token (conservative
 * for German text).
 * <p>
 * Sources: each entry is complete or cleanly shortened at a sentence or word boundary, never cut
 * in the middle of the block. From long documents the passages with the most question terms are
 * taken, not just the beginning. Entries are numbered [1], [2], ... in the order of relevance:
 * <pre>
 * [1] Title
 * URL: https://...
 * Collection: name
 * Text: excerpt
 * </pre>
 */
public final class RagContext {

    public static final int CHARS_PER_TOKEN = 3;
    /** tokens kept free for chat formatting and estimate errors */
    static final int SAFETY_TOKENS = 192;
    /** share of the free budget the chat history may use */
    static final double HISTORY_SHARE = 0.35;
    /** smallest useful entry (header and some text) */
    static final int MIN_ENTRY_CHARS = 360;
    static final int MIN_TEXT_CHARS = 160;
    static final int MAX_TITLE_CHARS = 160;
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern ENTRY = Pattern.compile("(?m)^\\[(\\d{1,3})\\] (.*)\\nURL: (\\S+)(?:\\nCollection: (\\S+))?");

    private RagContext() {
    }

    /** The prompt budget in characters. */
    public static final class Budget {
        public final int numCtx;
        public final int maxTokens;
        /** characters available for history and sources together */
        public final int freeChars;
        public final int historyAllowance;
        private final int hardCap;

        Budget(final int numCtx, final int maxTokens, final int freeChars, final int historyAllowance, final int hardCap) {
            this.numCtx = numCtx;
            this.maxTokens = maxTokens;
            this.freeChars = freeChars;
            this.historyAllowance = historyAllowance;
            this.hardCap = hardCap;
        }

        /** characters for the sources once the history uses historyChars */
        public int sourceChars(final int historyChars) {
            final int left = this.freeChars - Math.min(historyChars, this.historyAllowance);
            return Math.max(MIN_ENTRY_CHARS, Math.min(this.hardCap, left));
        }
    }

    /**
     * @param numCtx context window of the service (ai.service_num_ctx)
     * @param maxTokens answer length (max_tokens of the model)
     * @param fixedChars system prompt, question and other fixed prompt text
     * @param hardCap upper bound for the sources (ai.rag.search-document-maxlength)
     */
    public static Budget plan(final int numCtx, final int maxTokens, final int fixedChars, final int hardCap) {
        final int answerTokens = Math.max(0, Math.min(maxTokens, numCtx / 2));
        final int freeChars = Math.max(0, (numCtx - answerTokens - SAFETY_TOKENS) * CHARS_PER_TOKEN - fixedChars);
        return new Budget(numCtx, maxTokens, freeChars, (int) (freeChars * HISTORY_SHARE), Math.max(MIN_ENTRY_CHARS, hardCap));
    }

    public static int tokens(final int chars) {
        return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /** A numbered source as shown to the model and to the user. */
    public static final class Source {
        /** {@link #kind} of an entry of the Scoutro knowledge graph ({@link GraphFacts}). */
        public static final String KIND_GRAPH = "graph";
        public final int id;
        public final String title;
        public final String url;
        public final String host;
        public final String collection;
        /** null for a search result, {@link #KIND_GRAPH} for knowledge graph facts */
        public final String kind;

        Source(final int id, final String title, final String url, final String host, final String collection) {
            this(id, title, url, host, collection, null);
        }

        Source(final int id, final String title, final String url, final String host, final String collection, final String kind) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.host = host;
            this.collection = collection;
            this.kind = kind;
        }

        JSONObject toJson() throws JSONException {
            final JSONObject json = new JSONObject(true);
            json.put("id", this.id);
            json.put("title", this.title);
            json.put("url", this.url);
            json.put("host", this.host);
            if (this.collection != null) json.put("collection", this.collection);
            if (this.kind != null) json.put("kind", this.kind);
            return json;
        }
    }

    /** The sources of one answer and their text for the data block. */
    public static final class Built {
        public final List<Source> sources;
        public final String text;

        Built(final List<Source> sources, final String text) {
            this.sources = Collections.unmodifiableList(sources);
            this.text = text;
        }

        public JSONArray sourcesJson() throws JSONException {
            return RagContext.sourcesJson(this.sources);
        }
    }

    public static JSONArray sourcesJson(final List<Source> sources) throws JSONException {
        final JSONArray array = new JSONArray();
        for (final Source source : sources) array.put(source.toJson());
        return array;
    }

    /**
     * @param selected candidates in relevance order
     * @param budgetChars characters for all entries together
     * @param perDocChars upper bound per entry (ai.rag.document-maxlength)
     * @param maxSources upper bound of entries (ai.rag.max-sources)
     * @param collection collection label for the entries (null: the candidate's first collection)
     */
    public static Built build(final List<RagRetriever.Scored> selected, final RagQuery query, final int budgetChars,
            final int perDocChars, final int maxSources, final String collection) {
        int count = Math.min(Math.max(0, maxSources), selected.size());
        while (count > 1 && budgetChars / count < MIN_ENTRY_CHARS) count--;
        final List<Source> sources = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        if (count == 0) return new Built(sources, "");
        final int perEntry = Math.max(MIN_ENTRY_CHARS, Math.min(perDocChars, budgetChars / count));
        for (int i = 0; i < count; i++) {
            final RagCandidate candidate = selected.get(i).candidate;
            final int id = sources.size() + 1;
            final String label = collection != null ? collection : candidate.collections.isEmpty() ? null : candidate.collections.get(0);
            final String header = header(id, candidate, label);
            final int separator = text.length() == 0 ? 0 : 2;
            final int room = Math.min(perEntry, budgetChars - text.length() - separator) - header.length() - "Text: ".length();
            if (room < MIN_TEXT_CHARS) break; // no partial entry at the end of the block
            final String excerpt = excerpt(candidate, query, room);
            if (separator > 0) text.append("\n\n");
            text.append(header).append("Text: ").append(excerpt);
            sources.add(new Source(id, clean(candidate.title, MAX_TITLE_CHARS), candidate.url, candidate.host, label));
        }
        return new Built(sources, text.toString());
    }

    private static String header(final int id, final RagCandidate candidate, final String collection) {
        final StringBuilder header = new StringBuilder();
        header.append('[').append(id).append("] ").append(clean(candidate.title.isEmpty() ? candidate.url : candidate.title, MAX_TITLE_CHARS)).append('\n');
        header.append("URL: ").append(candidate.url).append('\n');
        if (collection != null) header.append("Collection: ").append(collection).append('\n');
        return header.toString();
    }

    static String clean(final String text, final int max) {
        final String single = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return single.length() <= max ? single : cut(single, max);
    }

    /**
     * The most relevant text of a document within maxChars: the description, then the sentences with
     * the most question terms, in document order; gaps are marked with an ellipsis.
     */
    static String excerpt(final RagCandidate candidate, final RagQuery query, final int maxChars) {
        final String body = candidate.text.replaceAll("\\s+", " ").trim();
        final String description = candidate.description.replaceAll("\\s+", " ").trim();
        final List<String> sentences = new ArrayList<>();
        if (!description.isEmpty() && !body.contains(description)) sentences.add(description);
        for (final String sentence : SENTENCE_END.split(body)) {
            String rest = sentence.trim();
            while (rest.length() > 400) { // very long runs without punctuation become word-bounded pieces
                final String piece = cut(rest, 400);
                sentences.add(piece.substring(0, piece.length() - 1));
                rest = rest.substring(piece.length() - 1).trim();
            }
            if (!rest.isEmpty()) sentences.add(rest);
        }
        if (sentences.isEmpty()) return "";
        final int[] score = new int[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            score[i] = RagCandidate.matched(query, RagQuery.fold(sentences.get(i))).size();
        }
        final boolean[] chosen = new boolean[sentences.size()];
        int used = 0;
        // first the matching sentences (most terms first, earlier first), then fill from the beginning
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) order.add(i);
        order.sort((a, b) -> score[b] != score[a] ? score[b] - score[a] : a - b);
        for (final int i : order) {
            final int length = sentences.get(i).length() + 3;
            if (used + length > maxChars) continue;
            chosen[i] = true;
            used += length;
        }
        final StringBuilder out = new StringBuilder();
        boolean gap = false;
        for (int i = 0; i < sentences.size(); i++) {
            if (!chosen[i]) {
                gap = out.length() > 0 || gap;
                continue;
            }
            if (out.length() > 0) out.append(gap ? " … " : " ");
            else if (i > 0) out.append("… ");
            out.append(sentences.get(i));
            gap = false;
        }
        if (out.length() == 0) return cut(sentences.get(order.get(0)), maxChars); // one sentence longer than the room
        if (gap) out.append(" …");
        return out.toString();
    }

    /** shorten at a word boundary and mark with an ellipsis */
    static String cut(final String text, final int max) {
        if (text.length() <= max) return text;
        int end = text.lastIndexOf(' ', Math.max(1, max - 1));
        if (end < max / 2) end = max - 1;
        return text.substring(0, end).trim() + "…";
    }

    /** The numbered sources of an earlier answer, read back from its attached search document. */
    public static List<Source> parse(final String attachment) {
        final List<Source> sources = new ArrayList<>();
        if (attachment == null) return sources;
        final Matcher entry = ENTRY.matcher(attachment);
        while (entry.find()) {
            final String url = entry.group(3);
            String host = "";
            try {
                host = new java.net.URI(url).getHost();
            } catch (final Exception e) {
                host = "";
            }
            final String title = entry.group(2).trim();
            sources.add(new Source(Integer.parseInt(entry.group(1)), title, url, host == null ? "" : host, entry.group(4),
                    title.startsWith(GraphFacts.TITLE) ? Source.KIND_GRAPH : null));
        }
        return sources;
    }

    /** Keep whole entries of an earlier search document within maxChars. */
    public static String limitEntries(final String attachment, final int maxChars) {
        if (attachment == null || attachment.length() <= maxChars) return attachment;
        final String[] entries = attachment.split("\n\n(?=\\[\\d{1,3}\\] )");
        final StringBuilder kept = new StringBuilder();
        for (final String entry : entries) {
            if (kept.length() + entry.length() + 2 > maxChars) break;
            if (kept.length() > 0) kept.append("\n\n");
            kept.append(entry);
        }
        return kept.toString();
    }
}
