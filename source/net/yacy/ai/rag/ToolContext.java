/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.scoutro.knowledge.read.KgReader;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * The server-side scope of the tools of one chat request (docs/SCOUTRO_RAG_QUALITY.md, "Tools of the chat").
 * <ul>
 * <li>Scope: the collection the chat session resolved ({@link #collection}), or every collection of the local index for
 * local and administrator access. It is set by the servlet from the session before the model is called; no tool
 * argument can change it. The search is always the local one, never the peer-to-peer network.</li>
 * <li>Sources: every page a tool result names gets the next number of the answer's sources, in the format of the
 * search results, so the answer cites it as [n] and the citation check ({@link RagCitations}) knows it.</li>
 * <li>Budgets: calls, time and result characters of all tool calls of one answer.</li>
 * </ul>
 * One instance per request; tool calls of one answer run one after the other.
 */
public final class ToolContext {

    /** scoped tool calls of one answer, over all tools and rounds */
    public static final int MAX_CALLS = 10;
    /** time all tool calls of one answer may take together */
    public static final long MAX_MILLIS = 20_000L;
    /** longest read lease of one knowledge graph read */
    public static final long GRAPH_READ_MILLIS = 1_500L;
    /** pages of one search call */
    public static final int SEARCH_HITS = 6;
    static final int EXCERPT_CHARS = 420;
    /** below this room no further tool call is served */
    public static final int MIN_RESULT_CHARS = 600;

    /** The local full-text search (the search of the RAG retrieval), replaceable in tests. */
    public interface Searcher {
        List<RagCandidate> search(RagQuery concepts, String query, String collection, int count);
    }

    /** The read layer of the knowledge graph, replaceable in tests. */
    public interface Graph {
        /** The reader; null while the graph is off or not running. */
        KgReader reader() throws Exception;

        /** The viewer of {@code collection}; null: every collection the graph follows. */
        Viewer viewer(KgReader reader, String collection) throws Exception;
    }

    /** The running knowledge graph. */
    public static Graph runtimeGraph() {
        return new Graph() {
            @Override
            public KgReader reader() throws Exception {
                final KgRuntime r = KgRuntime.current();
                return r == null || r.state() != KgRuntime.State.RUNNING || r.config() == null || !r.config().chatEnabled ? null : r.reader();
            }

            @Override
            public Viewer viewer(final KgReader reader, final String collection) throws Exception {
                return KgRuntime.current().chatViewer(reader, collection);
            }
        };
    }

    /** The chat's only collection; null: every collection of the local index (local and administrator access). */
    public final String collection;
    /** Whether the knowledge graph tools may run (local, administrator and account access). */
    public final boolean graph;
    public final String runId;
    private final Searcher searcher;
    private final Graph graphSource;
    private final Supplier<Long> clock;
    private List<RagContext.Source> sources = new ArrayList<>();
    private int added;
    private int calls;
    private long spentMillis;
    private int charsLeft;

    /**
     * @param collection  the chat's collection, or null for the whole local index
     * @param graph       knowledge graph tools allowed
     * @param resultChars characters all tool results of one answer may have together
     */
    public ToolContext(final String collection, final boolean graph, final Searcher searcher, final Graph graphSource,
            final int resultChars, final String runId) {
        this.collection = collection;
        this.graph = graph && graphSource != null;
        this.searcher = searcher;
        this.graphSource = graphSource;
        this.charsLeft = Math.max(MIN_RESULT_CHARS, resultChars);
        this.runId = runId;
        this.clock = System::currentTimeMillis;
    }

    /** The answer's sources so far (search results, graph facts): tool sources are numbered after them, in this list. */
    public synchronized void attach(final List<RagContext.Source> answerSources) {
        final List<RagContext.Source> own = this.sources;
        this.sources = answerSources;
        for (final RagContext.Source s : own) {
            if (!answerSources.contains(s)) answerSources.add(s);
        }
    }

    /** Words for the scope, as tool results and the system prompt name it. */
    public String scopeLabel() {
        return this.collection == null ? "all collections of the local index" : "collection " + this.collection;
    }

    // ---------------------------------------------------------------- budget

    /** A tool body. */
    public interface Call {
        JSONObject run() throws Exception;
    }

    /**
     * Runs one scoped tool call within the budgets of this answer; a refused or failed call answers with an
     * {@code error} the model can read. The result is never longer than the characters left.
     */
    public String run(final String tool, final Call call) {
        synchronized (this) {
            if (this.calls >= MAX_CALLS) return refusal(tool, "tool_budget", "The tool calls of this answer are used up.");
            if (this.spentMillis >= MAX_MILLIS) return refusal(tool, "tool_budget", "The tool time of this answer is used up.");
            if (this.charsLeft < MIN_RESULT_CHARS) return refusal(tool, "tool_budget", "The context room for tool results is used up.");
            this.calls++;
        }
        final long start = this.clock.get();
        String result;
        try {
            final JSONObject json = call.run();
            // page text in a result is untrusted data: it cannot imitate the boundary of a data block
            result = net.yacy.ai.PromptGuard.neutralize(json.toString());
            if (result.length() > room()) {
                // a result that does not fit is not cut in the middle of its JSON: its lists are shortened by the tools
                // (see room()); this is only the last line of defence
                result = refusal(tool, "too_large", "The result does not fit into the context any more.");
            }
        } catch (final Exception e) {
            result = failure(tool, e);
        }
        synchronized (this) {
            this.spentMillis += Math.max(0L, this.clock.get() - start);
            this.charsLeft -= result.length();
        }
        return result;
    }

    /** Characters the current tool result may have. */
    public synchronized int room() {
        return Math.max(0, this.charsLeft);
    }

    /** The read lease of one knowledge graph read: the time left of the answer's tool time, at most {@link #GRAPH_READ_MILLIS}. */
    public synchronized long leaseMillis() {
        return Math.max(50L, Math.min(GRAPH_READ_MILLIS, MAX_MILLIS - this.spentMillis));
    }

    private static String failure(final String tool, final Exception e) {
        if (e instanceof KgReader.NotFound) {
            return refusal(tool, "not_found", "Nothing with this ID in this chat's collections: unknown, or its sources are outside this"
                    + " chat's scope. There is no evidence to cite for it; say that it cannot be verified here.");
        }
        if (e instanceof net.yacy.scoutro.knowledge.KgException) {
            final String code = ((net.yacy.scoutro.knowledge.KgException) e).code();
            if (net.yacy.scoutro.knowledge.KgException.READ_TIMEOUT.equals(code) || net.yacy.scoutro.knowledge.KgException.BUSY.equals(code)) {
                return refusal(tool, net.yacy.scoutro.knowledge.KgException.BUSY.equals(code) ? "busy" : "timeout",
                        "The knowledge graph did not answer in time. Use the search tool or the sources you have, and say that"
                                + " the graph could not be checked.");
            }
        }
        if (e instanceof IllegalArgumentException) return refusal(tool, "invalid_arguments", e.getMessage());
        return refusal(tool, "failed", "The tool could not read its data. Say that this could not be checked.");
    }

    static String refusal(final String tool, final String error, final String note) {
        try {
            return new JSONObject(true).put("tool", tool).put("error", error).put("note", note).toString();
        } catch (final JSONException e) {
            return "{\"error\":\"" + error + "\"}";
        }
    }

    public synchronized int calls() {
        return this.calls;
    }

    public synchronized long spentMillis() {
        return this.spentMillis;
    }

    // --------------------------------------------------------------- sources

    /**
     * The numbered source of a page a tool result names: the existing number of the same page and kind, else the
     * next one. {@code text} is what the result says under this number (for the support check).
     */
    public synchronized RagContext.Source source(final String title, final String url, final String sourceCollection, final String kind,
            final String text) {
        final String key = RagCitations.normalize(url);
        int max = 0;
        for (final RagContext.Source s : this.sources) {
            max = Math.max(max, s.id);
            if (java.util.Objects.equals(s.kind, kind) && RagCitations.normalize(s.url).equals(key)) {
                s.addText(text);
                return s;
            }
        }
        String host = "";
        try {
            final String h = new java.net.URI(url).getHost();
            host = h == null ? "" : h;
        } catch (final Exception e) {
            host = "";
        }
        final RagContext.Source s = new RagContext.Source(max + 1, RagContext.clean(title == null || title.isEmpty() ? url : title,
                RagContext.MAX_TITLE_CHARS), url, host, sourceCollection, kind, text);
        this.sources.add(s);
        this.added++;
        return s;
    }

    /** Sources the tools added to the answer. */
    public synchronized int added() {
        return this.added;
    }

    /** All sources of the answer, the tools' included. */
    public synchronized List<RagContext.Source> sources() {
        return new ArrayList<>(this.sources);
    }

    /** What the tools did for this answer ({@code scoutro-tools} of the stream). */
    public synchronized JSONObject meta() {
        final JSONObject meta = new JSONObject(true);
        try {
            meta.put("calls", this.calls);
            meta.put("millis", this.spentMillis);
            meta.put("sources", this.added);
            meta.put("collection", this.collection == null ? JSONObject.NULL : this.collection);
            meta.put("local", true);
        } catch (final JSONException e) {
            // plain keys and values
        }
        return meta;
    }

    // ----------------------------------------------------------------- reads

    /** The reader of the graph with this answer's read lease, or null while the graph is not available to this chat. */
    public KgReader reader() throws Exception {
        if (!this.graph) return null;
        final KgReader reader = this.graphSource.reader();
        return reader == null ? null : reader.within(leaseMillis());
    }

    /** The viewer of this chat's scope: nothing outside it is read. */
    public Viewer viewer(final KgReader reader) throws Exception {
        return this.graphSource.viewer(reader, this.collection);
    }

    /**
     * The local search within the chat's scope as the retrieval of the answer does it, for a search of the server
     * ({@link ServiceSearch}): pages of other collections dropped, never the network. Not counted as a tool call.
     */
    public RagRetriever.Result retrieve(final RagQuery query, final int max) {
        final RagRetriever retriever = new RagRetriever((concepts, q, c, n, global) -> this.searcher == null ? new ArrayList<>()
                : this.searcher.search(concepts, q, c, n));
        final RagRetriever.Result result = retriever.retrieve(query, this.collection, false, max);
        final List<RagRetriever.Scored> own = new ArrayList<>();
        for (final RagRetriever.Scored s : result.selected) if (s.candidate.inCollection(this.collection)) own.add(s);
        return own.size() == result.selected.size() ? result : new RagRetriever.Result(result.query, result.searchQuery, result.collection,
                false, result.stage, result.candidates, result.foreignDropped, own);
    }

    /**
     * The local search within the chat's scope, as the retrieval of the answer does it: prepared terms, the stages of
     * {@link RagRetriever}, hits of other collections dropped. A {@code collection:} word of the query has no effect.
     */
    public JSONObject search(final String tool, final String text) throws JSONException {
        final RagQuery query = RagQuery.prepare(text);
        final RagRetriever retriever = new RagRetriever((concepts, q, c, n, global) -> this.searcher == null ? new ArrayList<>()
                : this.searcher.search(concepts, q, c, n));
        final RagRetriever.Result result = retriever.retrieve(query, this.collection, false, SEARCH_HITS);
        final JSONObject out = new JSONObject(true);
        out.put("tool", tool);
        out.put("scope", scopeLabel());
        out.put("local", true);
        out.put("query", query.searchString(false));
        out.put("stage", result.stage);
        final JSONArray hits = new JSONArray();
        int used = 420; // head and note
        int notShown = 0;
        final int room = room();
        for (final RagRetriever.Scored scored : result.selected) {
            final RagCandidate candidate = scored.candidate;
            if (!candidate.inCollection(this.collection)) continue; // never a page of another collection
            final String excerpt = RagContext.excerpt(candidate, query, EXCERPT_CHARS);
            final String label = this.collection != null ? this.collection : candidate.collections.isEmpty() ? null : candidate.collections.get(0);
            final String title = RagContext.clean(candidate.title.isEmpty() ? candidate.url : candidate.title, RagContext.MAX_TITLE_CHARS);
            final int length = excerpt.length() + title.length() + candidate.url.length() + (label == null ? 0 : label.length()) + 90;
            if (used + length > room) {
                notShown++; // no room left in the context for this page
                continue;
            }
            used += length;
            final RagContext.Source source = source(title, candidate.url, label, null, excerpt);
            final JSONObject hit = new JSONObject(true);
            hit.put("cite", "[" + source.id + "]");
            hit.put("title", title);
            hit.put("url", candidate.url);
            if (label != null) hit.put("collection", label);
            hit.put("text", excerpt);
            hits.put(hit);
        }
        out.put("sources", hits);
        if (notShown > 0) out.put("not_shown", notShown);
        if (hits.length() == 0 && notShown > 0) {
            out.put("note", "Pages matched, but the context has no room left for them. Answer with the sources you have.");
        } else if (hits.length() == 0) {
            out.put("note", "No page in " + scopeLabel() + " matches these words. Try other or fewer concrete words; if nothing is"
                    + " found, say that the index has no matching content.");
        } else if ("any".equals(result.stage)) {
            out.put("note", "Weak hits: only some of the words matched. Search again with other words if these pages do not answer"
                    + " the question. Cite a page as [n] only for what its text states.");
        } else {
            out.put("note", "Cite a page as [n] only for what its text states.");
        }
        return out;
    }
}
