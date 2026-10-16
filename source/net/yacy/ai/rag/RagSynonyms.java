/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.graph.GraphTokenStreamFiniteStrings;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;

/** Search-scoped, text-grounded alternatives; never a new source of business facts. */
public final class RagSynonyms {
    // This bundled thesaurus group mixes a service with a meeting. Operators can extend the list.
    public static final String DEFAULT_BLOCKED = "beratung=besprechung;besprechung=beratung";
    private static final int MAX_ALTERNATIVES = 32;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[-'][\\p{L}\\p{N}]+)*");
    private static final Set<String> TEXT_FIELDS = Set.of("title", "description_txt", "text_t");
    private final Map<String, Map<String, Set<String>>> analyzed = new LinkedHashMap<>();
    private final Set<String> forms = new LinkedHashSet<>();
    private final Set<String> blocked = new LinkedHashSet<>();
    private final boolean indexSynonyms;
    private final Function<String, Set<String>> dictionary;

    /** Uses the live schema and the exact qf of this search, not a bundled replacement dictionary. */
    public RagSynonyms(final IndexSchema schema, final String queryFields, final RagQuery query,
            final Function<String, Set<String>> dictionary, final String blockedPairs) {
        this(schema, queryFields, query, dictionary, blockedPairs, null);
    }

    public RagSynonyms(final IndexSchema schema, final String queryFields, final RagQuery query,
            final Function<String, Set<String>> dictionary, final String blockedPairs, final Set<String> searchedForms) {
        this.dictionary = dictionary;
        for (final String pair : blockedPairs.toLowerCase(Locale.ROOT).split(";")) this.blocked.add(pair.trim());
        for (final RagQuery.Term term : query.terms) {
            if (term.phrase) continue; // a phrase is not a bag of independently expanded words
            for (final String form : term.searchForms(true)) {
                if (searchedForms == null || searchedForms.contains(form)) this.forms.add(form);
            }
        }
        boolean indexed = false;
        for (final String spec : queryFields.trim().split("\\s+")) {
            final String field = spec.split("\\^")[0];
            if ("synonyms_sxt".equals(field)) indexed = true;
            if (schema == null || !TEXT_FIELDS.contains(field)) continue;
            final SchemaField sf = schema.getFieldOrNull(field);
            if (sf == null) continue;
            final Map<String, Set<String>> byForm = new LinkedHashMap<>();
            for (final String form : this.forms) {
                final Set<String> alternatives = new LinkedHashSet<>();
                try (TokenStream tokens = sf.getType().getQueryAnalyzer().tokenStream(field, form)) {
                    final GraphTokenStreamFiniteStrings graph = new GraphTokenStreamFiniteStrings(tokens);
                    final java.util.Iterator<TokenStream> paths = graph.getFiniteStrings();
                    int count = 0;
                    while (paths.hasNext() && count++ < MAX_ALTERNATIVES) {
                        try (TokenStream path = paths.next()) {
                            final CharTermAttribute token = path.addAttribute(CharTermAttribute.class);
                            final List<String> words = new ArrayList<>();
                            path.reset();
                            while (path.incrementToken()) words.add(token.toString());
                            path.end();
                            final String alternative = String.join(" ", words);
                            if (allowed(form, alternative)) alternatives.add(alternative);
                        }
                    }
                } catch (final IOException | RuntimeException e) {
                    // Unknown/remote analyzer: no guessed expansion. Exact matching remains available.
                }
                byForm.put(form, alternatives);
            }
            this.analyzed.put(field, byForm);
        }
        this.indexSynonyms = indexed;
    }

    private boolean allowed(final String form, final String alternative) {
        return alternative.length() >= 2 && alternative.length() <= 120 && !form.equalsIgnoreCase(alternative)
                && !this.blocked.contains(form + "=" + alternative.toLowerCase(Locale.ROOT));
    }

    /** Only alternatives witnessed in the supplied title/description/text may become matches. */
    public Map<String, Set<String>> forDocument(final String title, final String description, final String text,
            final Collection<?> indexedSynonyms) {
        final Map<String, String> fields = Map.of("title", safe(title), "description_txt", safe(description), "text_t", safe(text));
        final Map<String, Set<String>> result = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, Set<String>>> field : this.analyzed.entrySet()) {
            final String content = RagQuery.fold(fields.get(field.getKey()));
            for (final Map.Entry<String, Set<String>> form : field.getValue().entrySet()) {
                for (final String alternative : form.getValue()) {
                    if (RagCandidate.literal(alternative, content)) add(result, form.getKey(), alternative);
                }
            }
        }
        if (!this.indexSynonyms || indexedSynonyms == null || this.dictionary == null) return result;
        final Set<String> indexed = new LinkedHashSet<>();
        for (final Object value : indexedSynonyms) if (value != null) indexed.add(value.toString().toLowerCase(Locale.ROOT));
        // Reverse-check the active dictionary against actual document words. Metadata alone never suffices.
        if (Collections.disjoint(indexed, this.forms)) return result;
        final Matcher words = WORD.matcher(safe(title) + "\n" + safe(description) + "\n" + safe(text));
        int scanned = 0;
        final Set<String> seen = new LinkedHashSet<>();
        while (words.find() && scanned++ < 30000) {
            final String word = words.group().toLowerCase(Locale.ROOT);
            if (!seen.add(word)) continue;
            final Set<String> aliases = this.dictionary.apply(word);
            if (aliases == null) continue;
            for (final String form : this.forms) {
                if (indexed.contains(form) && aliases.contains(form) && allowed(form, word)) add(result, form, word);
            }
        }
        return result;
    }

    private static String safe(final String text) { return text == null ? "" : text; }

    private static void add(final Map<String, Set<String>> result, final String form, final String alternative) {
        final Set<String> values = result.computeIfAbsent(form, ignored -> new LinkedHashSet<>());
        if (values.size() < MAX_ALTERNATIVES) values.add(alternative);
    }
}
