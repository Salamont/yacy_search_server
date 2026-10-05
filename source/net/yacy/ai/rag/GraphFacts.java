/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.scoutro.knowledge.read.ChatFacts;

/**
 * Facts of the Scoutro knowledge graph as further numbered sources of a RAG
 * answer (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 8.4).
 * <ul>
 * <li>Only for local and administrator access; AI Shield guests only with
 * {@code scoutro.kg.chat.allowGuests=true}. The scope is the request's
 * collection, or every collection the graph follows; a global (P2P) question
 * without a collection gets no graph facts.</li>
 * <li>Each entry names the page the facts were read from, numbered after the
 * search sources, titled "Scoutro knowledge graph: name", so the answer can
 * cite it like a search result and the reader sees where it came from.
 * Uncertain facts (for example only read by the LLM tier) are marked;
 * conflicting and stale facts are left out.</li>
 * <li>A time budget ({@code chat.timeoutMs}) and a character budget
 * ({@code chat.maxChars}, at most a third of the source budget). Any error or
 * timeout skips the step; the search-based answer is always produced.</li>
 * </ul>
 */
public final class GraphFacts {

    /** Title prefix of every graph entry (also shown in the chat's source list). */
    public static final String TITLE = "Scoutro knowledge graph: ";
    static final int MAX_VALUE_CHARS = 200;
    private static final ConcurrentLog LOG = new ConcurrentLog("RAG-GRAPH");
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), r -> {
        final Thread t = new Thread(r, "ScoutroKG.chat");
        t.setDaemon(true);
        return t;
    });

    private GraphFacts() {
    }

    /** The chat settings of the graph. */
    public static final class Settings {
        final boolean enabled;
        final boolean allowGuests;
        final int maxFacts;
        final int maxChars;
        final long timeoutMillis;

        public Settings(final boolean enabled, final boolean allowGuests, final int maxFacts, final int maxChars, final long timeoutMillis) {
            this.enabled = enabled;
            this.allowGuests = allowGuests;
            this.maxFacts = maxFacts;
            this.maxChars = maxChars;
            this.timeoutMillis = timeoutMillis;
        }
    }

    /** Where the facts come from, replaceable in tests. */
    public interface Source {
        /** The settings; null while the graph is off or not running. */
        Settings settings();

        /** Entries over the evidence of {@code collection} (null: every followed collection). */
        List<ChatFacts.Entry> select(String collection, List<String> docIds, List<String> terms, int maxFacts, long deadlineMillis)
                throws Exception;
    }

    /** The running knowledge graph. */
    public static Source runtime() {
        return new Source() {
            @Override
            public Settings settings() {
                final KgRuntime r = KgRuntime.current();
                final KgConfig c = r == null || r.state() != KgRuntime.State.RUNNING ? null : r.config();
                return c == null ? null : new Settings(c.chatEnabled, c.chatAllowGuests, c.chatMaxFacts, c.chatMaxChars, c.chatTimeoutMillis);
            }

            @Override
            public List<ChatFacts.Entry> select(final String collection, final List<String> docIds, final List<String> terms,
                    final int maxFacts, final long deadlineMillis) throws Exception {
                final KgRuntime r = KgRuntime.current();
                if (r == null) {
                    return Collections.emptyList();
                }
                return r.chatFacts(collection, docIds, terms, maxFacts, deadlineMillis);
            }
        };
    }

    /** The selected entries of one answer, not numbered yet. */
    public static final class Selected {
        final List<ChatFacts.Entry> entries;
        final String reason;
        final boolean timedOut;
        final String collection;
        final int maxChars;
        final long millis;

        Selected(final List<ChatFacts.Entry> entries, final String reason, final boolean timedOut, final String collection,
                final int maxChars, final long millis) {
            this.entries = entries;
            this.reason = reason;
            this.timedOut = timedOut;
            this.collection = collection;
            this.maxChars = maxChars;
            this.millis = millis;
        }

        static Selected skipped(final String reason, final String collection) {
            return new Selected(Collections.emptyList(), reason, false, collection, 0, 0L);
        }

        /** Characters to keep free for the graph entries (an upper bound of {@link #format}). */
        public int reservedChars() {
            final Built b = format(this, 100);
            return b.text.isEmpty() ? 0 : b.text.length() + 2;
        }
    }

    /** The numbered graph entries of one answer. */
    public static final class Built {
        public final List<RagContext.Source> sources;
        public final String text;
        public final JSONObject meta;

        Built(final List<RagContext.Source> sources, final String text, final JSONObject meta) {
            this.sources = Collections.unmodifiableList(sources);
            this.text = text;
            this.meta = meta;
        }
    }

    /**
     * @param privileged local or administrator access
     * @param guest      an AI Shield guest
     * @param scope      the request's collection, or null
     * @param global     a P2P question
     * @param sourceChars the character budget of all sources; the graph takes at most a third
     */
    public static Selected collect(final Source source, final boolean privileged, final boolean guest, final String scope,
            final boolean global, final List<RagRetriever.Scored> selected, final RagQuery query, final int sourceChars) {
        final Settings s;
        try {
            s = source.settings();
        } catch (final RuntimeException e) {
            return Selected.skipped("error", scope);
        }
        if (s == null) {
            return Selected.skipped("not_running", scope);
        }
        if (!s.enabled) {
            return Selected.skipped("disabled", scope);
        }
        if (!privileged && !(guest && s.allowGuests)) {
            return Selected.skipped("access", scope);
        }
        if (scope == null && global) {
            return Selected.skipped("global", null);
        }
        final List<String> docIds = new ArrayList<>();
        for (final RagRetriever.Scored c : selected) {
            try {
                docIds.add(ASCII.String(new DigestURL(c.candidate.url).hash()));
            } catch (final MalformedURLException e) {
                // not a page of the index
            }
        }
        final List<String> terms = new ArrayList<>();
        for (final boolean weak : new boolean[] {false, true}) {
            for (final RagQuery.Term t : query.terms) {
                if (t.weak == weak && !t.phrase) {
                    terms.add(t.stem);
                }
            }
        }
        if (docIds.isEmpty() && terms.isEmpty()) {
            return Selected.skipped("no_terms", scope);
        }
        final int maxChars = Math.min(s.maxChars, Math.max(0, sourceChars / 3));
        if (maxChars < RagContext.MIN_ENTRY_CHARS / 2) {
            return Selected.skipped("no_room", scope);
        }
        final long start = System.currentTimeMillis();
        final Future<List<ChatFacts.Entry>> f;
        try {
            f = POOL.submit(() -> source.select(scope, docIds, terms, s.maxFacts, s.timeoutMillis));
        } catch (final RejectedExecutionException e) {
            return Selected.skipped("busy", scope);
        }
        try {
            final List<ChatFacts.Entry> entries = f.get(s.timeoutMillis, TimeUnit.MILLISECONDS);
            return new Selected(entries, entries.isEmpty() ? "no_facts" : null, false, scope, maxChars, System.currentTimeMillis() - start);
        } catch (final TimeoutException e) {
            f.cancel(true);
            LOG.info("graph facts skipped after " + s.timeoutMillis + " ms");
            return new Selected(Collections.emptyList(), "timeout", true, scope, maxChars, System.currentTimeMillis() - start);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return Selected.skipped("error", scope);
        } catch (final ExecutionException e) {
            LOG.info("graph facts skipped: " + e.getCause().getClass().getSimpleName());
            return new Selected(Collections.emptyList(), "error", false, scope, maxChars, System.currentTimeMillis() - start);
        }
    }

    /** Numbers the entries from {@code firstId}, within the character budget of the selection. */
    public static Built format(final Selected sel, final int firstId) {
        final List<RagContext.Source> sources = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        int facts = 0;
        final java.util.Set<String> entities = new java.util.LinkedHashSet<>();
        for (final ChatFacts.Entry e : sel.entries) {
            final int id = firstId + sources.size();
            final String name = RagContext.clean(e.name == null ? e.entity : e.name, RagContext.MAX_TITLE_CHARS - TITLE.length());
            final StringBuilder header = new StringBuilder();
            header.append('[').append(id).append("] ").append(TITLE).append(name).append('\n');
            header.append("URL: ").append(e.url).append('\n');
            if (e.collection != null) {
                header.append("Collection: ").append(e.collection).append('\n');
            }
            header.append("Text: Facts the Scoutro knowledge graph recorded about ").append(name)
                    .append(e.type == null ? "" : " (" + e.type + ")").append(" from this page, not model knowledge:");
            final int separator = text.length() == 0 ? 0 : 2;
            final StringBuilder body = new StringBuilder();
            int used = 0;
            for (final ChatFacts.Fact f : e.facts) {
                final String line = " " + label(f.predicate) + ": " + RagContext.clean(f.value, MAX_VALUE_CHARS) + marks(f) + ";";
                if (text.length() + separator + header.length() + body.length() + line.length() > sel.maxChars) {
                    break;
                }
                body.append(line);
                used++;
            }
            if (used == 0) {
                continue;
            }
            body.setLength(body.length() - 1);
            body.append('.');
            if (separator > 0) {
                text.append("\n\n");
            }
            text.append(header).append(body);
            sources.add(new RagContext.Source(id, TITLE + name, e.url, host(e.url), e.collection, RagContext.Source.KIND_GRAPH));
            facts += used;
            entities.add(e.entity);
        }
        final JSONObject meta = new JSONObject(true);
        try {
            meta.put("used", !sources.isEmpty());
            meta.put("entities", entities.size());
            meta.put("facts", facts);
            meta.put("sources", sources.size());
            meta.put("timedOut", sel.timedOut);
            meta.put("reason", sel.reason != null ? sel.reason : sources.isEmpty() ? "no_room" : JSONObject.NULL);
            meta.put("collection", sel.collection == null ? JSONObject.NULL : sel.collection);
            meta.put("millis", sel.millis);
        } catch (final JSONException e) {
            // keys and values are plain
        }
        return new Built(sources, text.toString(), meta);
    }

    private static final Map<String, String> LABELS = Map.ofEntries(Map.entry("legal_form", "legal form"), Map.entry("operates", "operates"),
            Map.entry("part_of", "is part of"), Map.entry("located_at", "is located at"), Map.entry("in_place", "is in"),
            Map.entry("offers", "offers"), Map.entry("address", "address"), Map.entry("postal_code", "postal code"),
            Map.entry("locality", "locality"), Map.entry("opening_hours", "opening hours"), Map.entry("phone", "phone"),
            Map.entry("email", "e-mail"), Map.entry("website", "website"), Map.entry("alias", "also called"),
            Map.entry("identifier:register", "register entry"), Map.entry("identifier:vat", "VAT ID"), Map.entry("identifier:lei", "LEI"),
            Map.entry("identifier:ik", "IK number"), Map.entry("identifier:wikidata", "Wikidata"));

    static String label(final String predicate) {
        final String l = LABELS.get(predicate);
        return l != null ? l : predicate.replace('_', ' ');
    }

    /** How sure the graph is: nothing for a supported fact. */
    static String marks(final ChatFacts.Fact f) {
        if (!"uncertain".equals(f.quality)) {
            return "";
        }
        if (f.llmOnly) {
            return " (uncertain: only read from the page text by a language model)";
        }
        return f.hedged ? " (uncertain: the page states it with reservation)" : " (uncertain)";
    }

    private static String host(final String url) {
        try {
            final String h = new java.net.URI(url).getHost();
            return h == null ? "" : h;
        } catch (final Exception e) {
            return "";
        }
    }
}
