/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Retrieval for the RAG chat on top of the normal YaCy search.
 * <p>
 * One YaCy search with the prepared terms and their base forms (the local search combines the words
 * with OR on exact word forms and ranks) returns up to {@link #CANDIDATES} candidates, restricted to
 * the requested collection. The candidates are then selected by how many of the question's strong
 * terms they contain (inflections and compounds included), in stages, using the strictest stage that
 * has a result:
 * <ol>
 *   <li>{@code all}: every strong term,</li>
 *   <li>{@code majority}: at least half of the strong terms (a too strict question),</li>
 *   <li>{@code any}: at least one strong term.</li>
 * </ol>
 * A question without search terms (only stopwords) or without matching candidates yields no sources,
 * instead of random hits. Candidates outside the requested collection are always dropped.
 */
public final class RagRetriever {

    /** candidates requested from the YaCy search */
    public static final int CANDIDATES = 30;

    /** The YaCy search, replaceable in tests. */
    public interface Searcher {
        List<RagCandidate> search(String query, String collection, int count, boolean global);
    }

    /** A selected candidate with its match counts. */
    public static final class Scored {
        public final RagCandidate candidate;
        public final int strong;
        public final int all;
        public final int title;

        Scored(final RagCandidate candidate, final int strong, final int all, final int title) {
            this.candidate = candidate;
            this.strong = strong;
            this.all = all;
            this.title = title;
        }
    }

    /** Outcome of one retrieval. */
    public static final class Result {
        public final RagQuery query;
        public final String searchQuery;
        public final String collection;
        public final boolean global;
        /** all, majority, any, none, no-terms */
        public final String stage;
        public final int candidates;
        public final int foreignDropped;
        public final List<Scored> selected;

        Result(final RagQuery query, final String searchQuery, final String collection, final boolean global, final String stage,
                final int candidates, final int foreignDropped, final List<Scored> selected) {
            this.query = query;
            this.searchQuery = searchQuery;
            this.collection = collection;
            this.global = global;
            this.stage = stage;
            this.candidates = candidates;
            this.foreignDropped = foreignDropped;
            this.selected = Collections.unmodifiableList(selected);
        }
    }

    private final Searcher searcher;

    public RagRetriever(final Searcher searcher) {
        this.searcher = searcher;
    }

    /**
     * @param collection the only collection to use, or null for the whole index
     * @param global use the P2P search; ignored when a collection is given (remote peers know no collections)
     * @param maxSources upper bound of selected sources
     */
    public Result retrieve(final RagQuery query, final String collection, final boolean global, final int maxSources) {
        final boolean remote = global && collection == null;
        if (query.isEmpty()) return new Result(query, "", collection, remote, "no-terms", 0, 0, Collections.emptyList());
        // remote peers require every word (AND): plain words for the P2P search, base forms only locally
        final String searchQuery = query.searchString(!remote);
        final List<RagCandidate> found = this.searcher.search(searchQuery, collection, CANDIDATES, remote);
        final List<RagCandidate> candidates = new ArrayList<>();
        int foreign = 0;
        if (found != null) {
            for (final RagCandidate candidate : found) {
                if (candidate.inCollection(collection)) candidates.add(candidate); else foreign++;
            }
        }
        final int strongTerms = query.strongTerms();
        final boolean onlyWeak = strongTerms == 0;
        final int strongTotal = onlyWeak ? query.terms.size() : strongTerms;
        final List<Scored> scored = new ArrayList<>();
        for (final RagCandidate candidate : candidates) {
            final Set<RagQuery.Term> matched = candidate.matched(query);
            int strong = 0;
            for (final RagQuery.Term term : matched) if (onlyWeak || !term.weak) strong++;
            if (strong == 0) continue;
            scored.add(new Scored(candidate, strong, matched.size(), candidate.matchedInTitle(query).size()));
        }
        final String[] stages = {"all", "majority", "any"};
        final int[] needs = {strongTotal, Math.max(1, (strongTotal + 1) / 2), 1};
        for (int s = 0; s < stages.length; s++) {
            if (s > 0 && needs[s] == needs[s - 1]) continue;
            final int need = needs[s];
            final List<Scored> accepted = new ArrayList<>();
            for (final Scored candidate : scored) if (candidate.strong >= need) accepted.add(candidate);
            if (accepted.isEmpty()) continue;
            accepted.sort(Comparator.comparingInt((Scored c) -> -c.strong).thenComparingInt(c -> -c.all)
                    .thenComparingInt(c -> -c.title).thenComparingInt(c -> c.candidate.rank));
            return new Result(query, searchQuery, collection, remote, stages[s], candidates.size(), foreign,
                    new ArrayList<>(accepted.subList(0, Math.min(Math.max(1, maxSources), accepted.size()))));
        }
        return new Result(query, searchQuery, collection, remote, "none", candidates.size(), foreign, Collections.emptyList());
    }
}
