/*
 *  TermMatcher
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.knowledge.vocab;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the terms of controlled vocabularies (service categories, audience
 * segments, customer types, employment types) in text, deterministically and
 * with the position of every hit, so that a claim can quote its source.
 * <p>
 * Text and terms are compared token by token in a folded form (lower case,
 * NFKC, {@code ä = ae}, {@code ß = ss = s}); a term of one word also matches
 * the start of a German compound if it has at least six letters
 * ("Kurzzeitpflegeplätze") and its end if it has at least eight
 * ("Komplettsanierung"); the last word of a longer term may start a compound.
 */
public final class TermMatcher<T> {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    static final int PREFIX_MIN = 6;
    static final int SUFFIX_MIN = 8;

    /** A hit: the entry, its span in the text and the matched words. */
    public static final class Hit<T> {
        public final T entry;
        public final int start;
        public final int end;

        Hit(final T entry, final int start, final int end) {
            this.entry = entry;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Term<T> {
        final String[] tokens;
        final T entry;

        Term(final String[] tokens, final T entry) {
            this.tokens = tokens;
            this.entry = entry;
        }
    }

    /** Terms by their first folded token. */
    private final Map<String, List<Term<T>>> byFirst = new HashMap<>();
    /** One-word terms, for compound prefixes and suffixes. */
    private final Map<String, List<Term<T>>> single = new HashMap<>();
    private final boolean suffixes;

    /** A matcher that also finds a one-word term at the end of a compound. */
    public TermMatcher() {
        this(true);
    }

    /**
     * @param suffixes also match a one-word term at the end of a compound; off for audiences and customer types,
     *            where "Hausverwaltungen" must not read as "Verwaltungen"
     */
    public TermMatcher(final boolean suffixes) {
        this.suffixes = suffixes;
    }

    /** Adds a term; empty terms are ignored. */
    public void add(final String term, final T entry) {
        final List<String> t = tokens(term);
        if (t.isEmpty()) {
            return;
        }
        final Term<T> x = new Term<>(t.toArray(new String[0]), entry);
        this.byFirst.computeIfAbsent(x.tokens[0], k -> new ArrayList<>()).add(x);
        if (x.tokens.length == 1) {
            this.single.computeIfAbsent(x.tokens[0], k -> new ArrayList<>()).add(x);
        }
    }

    public boolean isEmpty() {
        return this.byFirst.isEmpty();
    }

    /** The folded comparison form of one word. */
    public static String fold(final String word) {
        String t = Normalizer.normalize(word, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        t = t.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        // the Swiss and the old spelling: "Strasse" = "Straße", "Massnahme" = "Maßnahme"
        return t.replace("ss", "s");
    }

    /** Folded tokens of a text. */
    public static List<String> tokens(final String s) {
        final List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        final Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            out.add(fold(m.group()));
        }
        return out;
    }

    /**
     * All hits in {@code text[from, to)}, in text order; overlapping hits of
     * different entries are all kept, the longest hit per start and entry.
     */
    public List<Hit<T>> find(final String text, final int from, final int to) {
        if (text == null || this.byFirst.isEmpty()) {
            return Collections.emptyList();
        }
        final List<int[]> spans = new ArrayList<>();
        final List<String> words = new ArrayList<>();
        final Matcher m = TOKEN.matcher(text).region(Math.max(0, from), Math.min(text.length(), to));
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            words.add(fold(m.group()));
        }
        final List<Hit<T>> out = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            final String w = words.get(i);
            final List<Term<T>> candidates = this.byFirst.get(w);
            if (candidates != null) {
                for (final Term<T> t : candidates) {
                    final int n = matches(t, words, i);
                    if (n > 0) {
                        out.add(new Hit<>(t.entry, spans.get(i)[0], spans.get(i + n - 1)[1]));
                    }
                }
            }
            // compounds: "Kurzzeitpflegeplatz" starts with "kurzzeitpflege", "Altbausanierung" ends with "sanierung"
            for (int len = Math.min(w.length() - 1, 40); len >= PREFIX_MIN; len--) {
                final List<Term<T>> p = this.single.get(w.substring(0, len));
                if (p != null) {
                    for (final Term<T> t : p) {
                        out.add(new Hit<>(t.entry, spans.get(i)[0], spans.get(i)[1]));
                    }
                }
            }
            for (int start = 1; this.suffixes && w.length() - start >= SUFFIX_MIN; start++) {
                final List<Term<T>> p = this.single.get(w.substring(start));
                if (p != null) {
                    for (final Term<T> t : p) {
                        out.add(new Hit<>(t.entry, spans.get(i)[0], spans.get(i)[1]));
                    }
                }
            }
        }
        return dedupe(out);
    }

    public List<Hit<T>> find(final String text) {
        return text == null ? Collections.emptyList() : find(text, 0, text.length());
    }

    /** The distinct entries found in {@code text}, in order of their first hit. */
    public List<T> entries(final String text) {
        final List<T> out = new ArrayList<>();
        for (final Hit<T> h : find(text)) {
            if (!out.contains(h.entry)) {
                out.add(h.entry);
            }
        }
        return out;
    }

    /** Words matched by term {@code t} at position {@code i}, or 0. */
    private static <T> int matches(final Term<T> t, final List<String> words, final int i) {
        if (i + t.tokens.length > words.size()) {
            return 0;
        }
        for (int k = 0; k < t.tokens.length; k++) {
            final String w = words.get(i + k);
            final String tk = t.tokens[k];
            if (w.equals(tk)) {
                continue;
            }
            // the last word of a longer term may start a compound ("ambulanter pflegedienst" in "ambulanter Pflegedienstleister")
            if (k == t.tokens.length - 1 && t.tokens.length > 1 && tk.length() >= PREFIX_MIN && w.startsWith(tk)) {
                continue;
            }
            return 0;
        }
        return t.tokens.length;
    }

    private static <T> List<Hit<T>> dedupe(final List<Hit<T>> hits) {
        final Map<String, Hit<T>> best = new java.util.LinkedHashMap<>();
        for (final Hit<T> h : hits) {
            final String k = h.start + ":" + System.identityHashCode(h.entry);
            final Hit<T> old = best.get(k);
            if (old == null || h.end > old.end) {
                best.put(k, h);
            }
        }
        final List<Hit<T>> out = new ArrayList<>(best.values());
        out.sort((a, b) -> a.start != b.start ? Integer.compare(a.start, b.start) : Integer.compare(b.end, a.end));
        return out;
    }

    /** A matcher over {@code entries}, each with its terms. */
    public static <T> TermMatcher<T> of(final Collection<T> entries, final java.util.function.Function<T, Collection<String>> terms,
            final boolean suffixes) {
        final TermMatcher<T> m = new TermMatcher<>(suffixes);
        for (final T e : entries) {
            for (final String t : terms.apply(e)) {
                m.add(t, e);
            }
        }
        return m;
    }
}
