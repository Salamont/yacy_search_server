/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.yacy.search.Switchboard;

/**
 * Turns a natural chat question into search terms for RAG retrieval, without an LLM.
 * <ul>
 *   <li>Stopwords (YaCy's lists, the German and English Solr lists and chat filler words such
 *       as "gibt", "welche", "suche") are removed; quoted phrases are kept.</li>
 *   <li>Terms are ordered by importance (capitalized German nouns, length) and capped at
 *       {@link #MAX_TERMS}; generic words ("Anbieter", "Angebote") are kept but marked weak.</li>
 *   <li>Each term has a stem without common German/English endings; matching compares
 *       umlaut-folded prefixes, so "Pflegeheime", "Pflegeheims" and "Pflegeheim" match.</li>
 *   <li>The local YaCy search combines the words with OR (Solr {@code mm=1}) on exact word forms
 *       and has no stemming; a wildcard ({@code pflegeheim*}) finds nothing. The query therefore
 *       contains each word together with its base form ("pflegeheime pflegeheim"), so documents
 *       with either form become candidates. Remote peers combine words with AND: the P2P query
 *       has the plain words only.</li>
 *   <li>{@code site:} and {@code filetype:} are passed to YaCy; a {@code collection:} written in
 *       the question is returned separately (an explicit collection of the request wins).</li>
 * </ul>
 */
public final class RagQuery {

    public static final int MAX_TERMS = 8;
    static final int MIN_STEM = 5;

    private static final Pattern TOKEN = Pattern.compile("\"([^\"]{2,80})\"|(\\S+)");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[.'’][\\p{L}\\p{N}]+)*");
    private static final Pattern PASS_MODIFIER = Pattern.compile("(?i)(site|filetype):[\\p{L}\\p{N}._-]{1,253}");
    private static final Pattern COLLECTION_MODIFIER = Pattern.compile("(?i)collection:([A-Za-z0-9_-]{1,64})");
    private static final String[] SUFFIXES = {"ern", "en", "er", "es", "e", "n", "s"};

    /** chat filler and question words that are no search terms (in addition to the stopword lists) */
    static final Set<String> FILLER = Set.of(
            "gibt", "gib", "gibts", "geben", "bitte", "suche", "suchen", "sucht", "finde", "finden", "zeige", "zeig", "zeigen",
            "nenne", "nennen", "liste", "auflisten", "kannst", "könntest", "könnte", "kann", "können", "möchte", "möchten",
            "würde", "würden", "wäre", "gerne", "gern", "welche", "welcher", "welches", "welchen", "welchem", "wer", "wen",
            "wem", "wie", "wo", "was", "wann", "warum", "wieso", "weshalb", "woher", "wohin", "worauf", "womit", "eigentlich",
            "denn", "mal", "etwa", "ungefähr", "irgendwelche", "irgendein", "irgendeine", "jemand", "etwas", "dabei", "dazu",
            "davon", "deshalb", "achten", "sollten", "sollte", "nähe", "bzw", "usw", "jahre", "jahren", "beim", "gibt's",
            "arbeitet", "arbeiten", "steht", "stehen", "liegt", "liegen", "bietet", "bieten", "anbietet", "macht", "machen",
            "geht", "gehen", "kommt", "kommen", "gemacht", "genau",
            "please", "find", "show", "list", "tell", "give", "recommend", "search", "looking", "need", "want", "which",
            "what", "where", "who", "whom", "whose", "how", "when", "why", "any", "there", "near");

    /** generic words: they help ranking but never count as a match on their own */
    static final Set<String> WEAK = Set.of(
            "anbieter", "angebot", "angebote", "angeboten", "firma", "firmen", "unternehmen", "dienstleister", "möglichkeit",
            "möglichkeiten", "information", "informationen", "infos", "empfehlung", "empfehlungen", "tipps", "tipp",
            "beste", "besten", "gute", "guten", "provider", "providers", "company", "companies", "offer", "offers", "options");

    private static volatile Set<String> stopwords = null;

    /** One search term. */
    public static final class Term {
        public final String text;
        /** folded stem for matching against document text */
        public final String stem;
        /** lowercase base form with umlauts, for the YaCy query (the index keeps umlauts) */
        final String searchStem;
        public final boolean phrase;
        public final boolean weak;
        final boolean noun;
        final int position;

        Term(final String text, final boolean phrase, final boolean weak, final boolean noun, final int position) {
            this.text = text;
            this.phrase = phrase;
            this.weak = weak;
            this.noun = noun;
            this.position = position;
            this.stem = phrase ? fold(text) : stem(text);
            this.searchStem = phrase ? text : strip(text.toLowerCase(Locale.ROOT));
        }

        /** the word forms for the YaCy query: the phrase, or the word and (with variants) its base forms */
        List<String> searchForms(final boolean variants) {
            final List<String> forms = new ArrayList<>();
            if (this.phrase) {
                forms.add("\"" + this.text + "\"");
                return forms;
            }
            forms.add(this.text);
            if (!variants) return forms;
            if (!this.searchStem.equals(this.text)) forms.add(this.searchStem);
            // plural with umlaut: "einfamilienhäuser" -> "einfamilienhaus", "höfe" -> "hof"
            final String stripped = this.text.substring(this.searchStem.length());
            if ("er".equals(stripped) || "e".equals(stripped)) {
                final int umlaut = Math.max(this.searchStem.lastIndexOf('ä'), Math.max(this.searchStem.lastIndexOf('ö'), this.searchStem.lastIndexOf('ü')));
                if (umlaut >= 0) {
                    final char plain = this.searchStem.charAt(umlaut) == 'ä' ? 'a' : this.searchStem.charAt(umlaut) == 'ö' ? 'o' : 'u';
                    forms.add(this.searchStem.substring(0, umlaut) + plain + this.searchStem.substring(umlaut + 1));
                }
            }
            return forms;
        }

        @Override
        public String toString() {
            return this.phrase ? "\"" + this.text + "\"" : this.text;
        }
    }

    public final String question;
    public final List<Term> terms;
    /** YaCy modifiers passed through (site:, filetype:) */
    public final List<String> modifiers;
    /** collection named in the question with collection:, or null */
    public final String questionCollection;

    private RagQuery(final String question, final List<Term> terms, final List<String> modifiers, final String questionCollection) {
        this.question = question;
        this.terms = Collections.unmodifiableList(terms);
        this.modifiers = Collections.unmodifiableList(modifiers);
        this.questionCollection = questionCollection;
    }

    public static RagQuery prepare(final String question) {
        return prepare(question, stopwords());
    }

    static RagQuery prepare(final String question, final Set<String> stopwords) {
        final String text = question == null ? "" : question.trim();
        final List<String> modifiers = new ArrayList<>();
        String questionCollection = null;
        final Map<String, Term> terms = new LinkedHashMap<>();
        final Matcher tokens = TOKEN.matcher(text);
        // a question written in capitals has no acronyms
        final boolean capitals = text.equals(text.toUpperCase(Locale.ROOT));
        int position = 0;
        while (tokens.find()) {
            if (tokens.group(1) != null) { // quoted phrase
                final String phrase = tokens.group(1).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                if (!phrase.isEmpty()) terms.putIfAbsent("\"" + phrase, new Term(phrase, true, false, true, position++));
                continue;
            }
            final String raw = tokens.group(2);
            final Matcher collection = COLLECTION_MODIFIER.matcher(raw);
            if (collection.matches()) {
                questionCollection = collection.group(1);
                continue;
            }
            if (PASS_MODIFIER.matcher(raw).matches()) {
                modifiers.add(raw.toLowerCase(Locale.ROOT));
                continue;
            }
            if (raw.indexOf(':') > 0) continue; // other modifiers are not passed through
            final Matcher words = WORD.matcher(raw);
            while (words.find()) {
                final String original = words.group();
                for (final String part : original.split("[.'’]")) {
                    if (part.isEmpty()) continue;
                    final String word = part.toLowerCase(Locale.ROOT);
                    position++;
                    if (!useful(word, capitals ? word : part, stopwords)) continue;
                    final boolean noun = Character.isUpperCase(part.charAt(0)) && position > 1;
                    terms.putIfAbsent(word, new Term(word, false, WEAK.contains(word), noun, position));
                }
            }
        }
        final List<Term> ordered = new ArrayList<>(terms.values());
        if (ordered.size() > MAX_TERMS) {
            // keep the most specific terms: phrases, nouns, strong words, longer words; then restore question order
            final List<Term> ranked = new ArrayList<>(ordered);
            ranked.sort(Comparator.comparing((Term t) -> !t.phrase).thenComparing(t -> t.weak).thenComparing(t -> !t.noun)
                    .thenComparing(t -> -t.text.length()).thenComparingInt(t -> t.position));
            final Set<Term> keep = new HashSet<>(ranked.subList(0, MAX_TERMS));
            ordered.removeIf(t -> !keep.contains(t));
        }
        return new RagQuery(text, ordered, modifiers, questionCollection);
    }

    static boolean useful(final String word, final String original, final Set<String> stopwords) {
        if (word.length() < 2) return false;
        if (word.chars().allMatch(Character::isDigit)) return word.length() >= 3; // postal codes, years
        final boolean acronym = original.length() <= 4 && original.chars().allMatch(Character::isUpperCase);
        if (acronym) return true; // IT, KI, NRW: also when the lowercase form is a stopword ("it")
        if (word.length() < 3) return false;
        return !stopwords.contains(word) && !FILLER.contains(word);
    }

    /**
     * The YaCy query string: the terms (with variants also their base forms, for the local OR search)
     * and the passed-through modifiers.
     */
    public String searchString(final boolean variants) {
        final StringBuilder query = new StringBuilder();
        for (final Term term : this.terms) for (final String form : term.searchForms(variants)) query.append(form).append(' ');
        for (final String modifier : this.modifiers) query.append(modifier).append(' ');
        return query.toString().trim();
    }

    public boolean isEmpty() {
        return this.terms.isEmpty();
    }

    public int strongTerms() {
        int n = 0;
        for (final Term term : this.terms) if (!term.weak) n++;
        return n;
    }

    @Override
    public String toString() {
        return this.terms.toString();
    }

    /** lowercase, umlauts folded to a/o/u, ß to ss */
    static String fold(final String word) {
        return word.toLowerCase(Locale.ROOT).replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss");
    }

    /** a folded stem without one common German/English inflection ending, never shorter than {@link #MIN_STEM} */
    static String stem(final String word) {
        return strip(fold(word));
    }

    private static String strip(final String word) {
        if (word.length() <= MIN_STEM) return word;
        for (final String suffix : SUFFIXES) {
            if (word.endsWith(suffix) && word.length() - suffix.length() >= MIN_STEM) {
                return word.substring(0, word.length() - suffix.length());
            }
        }
        return word;
    }

    /** The stopwords: YaCy's loaded list plus the German and English lists shipped with YaCy. */
    static Set<String> stopwords() {
        Set<String> result = stopwords;
        if (result != null) return result;
        final Set<String> words = new HashSet<>();
        final Switchboard sb = Switchboard.getSwitchboard();
        if (Switchboard.stopwords != null) {
            for (final String w : Switchboard.stopwords) words.add(w.toLowerCase(Locale.ROOT));
        }
        final File base = sb == null || sb.appPath == null ? new File(".") : sb.appPath;
        for (final String name : new String[] {"defaults/solr/lang/stopwords_de.txt", "defaults/solr/lang/stopwords_en.txt",
                "defaults/yacy.stopwords", "defaults/yacy.stopwords.de"}) {
            words.addAll(readList(new File(base, name)));
        }
        result = Collections.unmodifiableSet(words);
        stopwords = result;
        return result;
    }

    static Set<String> readList(final File file) {
        final Set<String> words = new HashSet<>();
        if (!file.isFile()) return words;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.indexOf('�') >= 0) text = new String(bytes, StandardCharsets.ISO_8859_1); // yacy.stopwords.de is Latin-1
            for (String line : text.split("\\r?\\n")) {
                final int bar = line.indexOf('|');
                if (bar >= 0) line = line.substring(0, bar);
                if (line.trim().startsWith("#")) continue;
                final String word = line.trim().toLowerCase(Locale.ROOT);
                if (!word.isEmpty() && word.indexOf(' ') < 0) words.add(word);
            }
        } catch (final IOException e) {
            // a missing list only weakens the filter
        }
        return words;
    }
}
