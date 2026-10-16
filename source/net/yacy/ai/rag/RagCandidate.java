/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/** One search result as delivered by the YaCy search, before selection. */
public final class RagCandidate {
    public final String url;
    public final String title;
    public final String description;
    public final String text;
    public final String host;
    public final List<String> collections;
    /** position in the YaCy result order (0 = best) */
    public final int rank;

    private final Map<String, Set<String>> alternatives;
    private final String haystack;
    private final String titleHaystack;

    public RagCandidate(final String url, final String title, final String description, final String text, final String host,
            final List<String> collections, final int rank) {
        this(url, title, description, text, host, collections, rank, Collections.emptyMap());
    }

    public RagCandidate(final String url, final String title, final String description, final String text, final String host,
            final List<String> collections, final int rank, final Map<String, Set<String>> alternatives) {
        final Map<String, Set<String>> copy = new LinkedHashMap<>();
        alternatives.forEach((key, values) -> copy.put(key, Set.copyOf(values)));
        this.alternatives = Collections.unmodifiableMap(copy);
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title.trim();
        this.description = description == null ? "" : description.trim();
        this.text = text == null ? "" : text;
        this.host = host == null ? "" : host;
        this.collections = collections == null ? Collections.emptyList() : Collections.unmodifiableList(collections);
        this.rank = rank;
        this.titleHaystack = RagQuery.fold(this.title);
        this.haystack = this.titleHaystack + "\n" + RagQuery.fold(this.description) + "\n" + RagQuery.fold(this.text);
    }

    public boolean inCollection(final String collection) {
        return collection == null || this.collections.contains(collection);
    }

    /** terms found in title, description or text */
    Set<RagQuery.Term> matched(final RagQuery query) {
        return matchedWithAlternatives(query, this.haystack);
    }

    /** terms found in the title */
    Set<RagQuery.Term> matchedInTitle(final RagQuery query) {
        return matchedWithAlternatives(query, this.titleHaystack);
    }

    Set<RagQuery.Term> exact(final RagQuery query) { return matched(query, this.haystack); }

    Set<RagQuery.Term> matchedWithAlternatives(final RagQuery query, final String folded) {
        final Set<RagQuery.Term> found = matched(query, folded);
        for (final RagQuery.Term term : query.terms) {
            if (found.contains(term) || term.phrase) continue;
            for (final String form : term.searchForms(true)) {
                for (final String alternative : this.alternatives.getOrDefault(form, Collections.emptySet())) {
                    if (literal(alternative, folded)) found.add(term);
                }
            }
        }
        return found;
    }

    static boolean literal(final String text, final String folded) {
        final String normalized = RagQuery.fold(text).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (normalized.isEmpty()) return false;
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(normalized).replace(" ", "\\E[^\\p{L}\\p{N}]+\\Q")
                + "(?![\\p{L}\\p{N}])").matcher(folded).find();
    }

    static Set<RagQuery.Term> matched(final RagQuery query, final String folded) {
        final Set<RagQuery.Term> found = new LinkedHashSet<>();
        for (final RagQuery.Term term : query.terms) {
            if (matches(term, folded)) found.add(term);
        }
        return found;
    }

    /**
     * A stem of {@link RagQuery#MIN_STEM}+ characters matches anywhere in a word (German compounds:
     * "pflege" in "Tagespflege", "bereich" in "Wohnbereich"); shorter words must start a word.
     */
    static boolean matches(final RagQuery.Term term, final String folded) {
        if (term.phrase || term.stem.length() >= RagQuery.MIN_STEM) return folded.contains(term.stem);
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term.stem) + "(?![\\p{L}\\p{N}])").matcher(folded).find();
    }
}
