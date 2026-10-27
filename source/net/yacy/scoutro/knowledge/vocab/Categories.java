/*
 *  Categories
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The versioned, extensible business vocabularies of the graph
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 23, parts C, F, H and I): per vocabulary
 * (care, coaching, software, construction, ...) the service categories with
 * their groups (the Scoutro industry subcategories), the NACE level each one
 * reliably implies, the audience segments; globally the customer types,
 * company sizes and employment types. Every entry has German and English
 * labels and match terms.
 * <p>
 * Read from {@code defaults/scoutro/knowledge/categories.json} and merged
 * with the files in {@code DATA/SCOUTRO/knowledge/vocabulary/*.json} (an
 * entry with an existing code replaces it, a new one is added): a new
 * category needs no schema change. The values the graph stores are codes:
 * {@code care/tagespflege} (category), {@code construction.tga} (group),
 * {@code b2b}, {@code sme}, {@code full_time}; NACE codes come from
 * {@link Nace}. {@link #version} changes with the content, so a changed
 * vocabulary re-extracts.
 * <p>
 * A vocabulary is a profile: no collection has one by itself. The operator
 * gives a collection its vocabulary with {@code scoutro.kg.vocab.<collection>}
 * or with a {@code collections} map in a file of
 * {@code DATA/SCOUTRO/knowledge/vocabulary} (the shipped defaults name no
 * collection). Each vocabulary also names the facility kinds the LLM tier may
 * assign on the pages of its collections ({@code llm_kinds}); a file may fix
 * the kinds of single collections ({@code collection_llm_kinds}). Kinds are no
 * part of {@link #version}: they never re-extract. A vocabulary may state that
 * the providers of its field run the places their pages name
 * ({@code operates_places}, care: homes, day care, residential groups), which
 * the check of the LLM tier's places reads (0.13.0); it is no part of
 * {@link #version} either, as it changes no rule.
 */
public final class Categories {

    public static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{0,47}$");
    public static final Pattern VOCABULARY = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");
    /** A facility kind the LLM tier may assign (lower-case schema.org type names where one exists). */
    public static final Pattern LLM_KIND = Pattern.compile("^[a-z]{2,64}$");
    /** Facility kinds of one vocabulary or collection at most. */
    public static final int MAX_LLM_KINDS = 40;
    private static final Pattern COLLECTION = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** One entry: a category, group, segment, customer type, company size or employment type. */
    public static final class Entry {
        /** The stored code ({@code care/tagespflege}, {@code care.stationaer}, {@code b2b}). */
        public final String id;
        public final String vocabulary;
        public final String code;
        public final String de;
        public final String en;
        /** Group of a category (stored id {@code vocabulary.group}), else null. */
        public final String group;
        /** The safe NACE level, or null. */
        public final String nace;
        public final List<String> terms;
        /**
         * 0.14.0 (item B7): the words of another sense, folded ({@link TermMatcher#fold}, no hyphens): a word holding one is no
         * hit of the entry ("IT-Architekturen" of architecture, "Verkehrsinfrastruktur" of IT infrastructure); {@code exclude} of
         * the file.
         */
        public final List<String> excludes;

        Entry(final String id, final String vocabulary, final String code, final String de, final String en, final String group,
                final String nace, final List<String> terms) {
            this(id, vocabulary, code, de, en, group, nace, terms, List.of());
        }

        Entry(final String id, final String vocabulary, final String code, final String de, final String en, final String group,
                final String nace, final List<String> terms, final List<String> excludes) {
            this.id = id;
            this.vocabulary = vocabulary;
            this.code = code;
            this.de = de;
            this.en = en;
            this.group = group;
            this.nace = nace;
            this.terms = Collections.unmodifiableList(terms);
            this.excludes = Collections.unmodifiableList(excludes);
        }

        public String label(final String lang) {
            return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("de") ? this.de : this.en;
        }
    }

    /** One vocabulary. */
    public static final class Vocab {
        public final String name;
        public final String de;
        public final String en;
        /** The facility kinds the LLM tier may assign on the pages of a collection with this vocabulary (sorted; not versioned). */
        public final Set<String> llmKinds;
        public final Map<String, Entry> groups;
        public final Map<String, Entry> categories;
        public final Map<String, Entry> segments;
        /**
         * 0.13.0: the words that name the organisations of the field where no category term does ("Softwareunternehmen",
         * "Bauunternehmen", "Handwerksbetriebe"), each with the NACE level it implies: an audience naming them gives a target
         * industry without a target category ({@link net.yacy.scoutro.knowledge.extract.Audiences}).
         */
        public final Map<String, Entry> organisations;
        /**
         * 0.13.0: the providers of the field run the places their pages name ({@code operates_places}: care): a project page of such
         * a provider is no sign of the object of a project ({@link net.yacy.scoutro.knowledge.extract.PlaceEvidence}).
         */
        public final boolean operatesPlaces;
        final TermMatcher<Entry> categoryMatcher;
        final TermMatcher<Entry> segmentMatcher;
        final TermMatcher<Entry> organisationMatcher;

        Vocab(final String name, final String de, final String en, final Set<String> llmKinds, final Map<String, Entry> groups,
                final Map<String, Entry> categories, final Map<String, Entry> segments) {
            this(name, de, en, llmKinds, groups, categories, segments, Collections.emptyMap());
        }

        Vocab(final String name, final String de, final String en, final Set<String> llmKinds, final Map<String, Entry> groups,
                final Map<String, Entry> categories, final Map<String, Entry> segments, final Map<String, Entry> organisations) {
            this(name, de, en, llmKinds, groups, categories, segments, organisations, false);
        }

        Vocab(final String name, final String de, final String en, final Set<String> llmKinds, final Map<String, Entry> groups,
                final Map<String, Entry> categories, final Map<String, Entry> segments, final Map<String, Entry> organisations,
                final boolean operatesPlaces) {
            this.name = name;
            this.de = de;
            this.en = en;
            this.llmKinds = Collections.unmodifiableSet(new TreeSet<>(llmKinds));
            this.groups = Collections.unmodifiableMap(groups);
            this.categories = Collections.unmodifiableMap(categories);
            this.segments = Collections.unmodifiableMap(segments);
            this.organisations = Collections.unmodifiableMap(organisations);
            this.operatesPlaces = operatesPlaces;
            this.categoryMatcher = TermMatcher.of(categories.values(), e -> e.terms, true);
            this.segmentMatcher = TermMatcher.of(segments.values(), e -> e.terms, false);
            this.organisationMatcher = TermMatcher.of(organisations.values(), e -> e.terms, false);
        }

        /** Categories named in the text, with their positions. */
        public List<TermMatcher.Hit<Entry>> categories(final String text, final int from, final int to) {
            return senses(text, this.categoryMatcher.find(text, from, to));
        }

        public List<TermMatcher.Hit<Entry>> segments(final String text, final int from, final int to) {
            return senses(text, this.segmentMatcher.find(text, from, to));
        }

        /** 0.13.0: the words of the field's organisations named in the text ({@link #organisations}). */
        public List<TermMatcher.Hit<Entry>> organisations(final String text, final int from, final int to) {
            return senses(text, this.organisationMatcher.find(text, from, to));
        }

        /** 0.14.0 (item B7): the hits without those whose word is of another sense ({@link Entry#excludes}). */
        private static List<TermMatcher.Hit<Entry>> senses(final String text, final List<TermMatcher.Hit<Entry>> hits) {
            hits.removeIf(h -> !h.entry.excludes.isEmpty() && otherSense(text, h));
            return hits;
        }

        private static boolean otherSense(final String text, final TermMatcher.Hit<Entry> h) {
            int a = h.start;
            while (a > 0 && (Character.isLetterOrDigit(text.charAt(a - 1)) || text.charAt(a - 1) == '-')) {
                a--;
            }
            int b = h.end;
            while (b < text.length() && (Character.isLetterOrDigit(text.charAt(b)) || text.charAt(b) == '-')) {
                b++;
            }
            final String word = TermMatcher.fold(text.substring(a, b).replace("-", ""));
            // with the word before it ("software architecture"), as an exclusion of two words is written together
            int p = a;
            while (p > 0 && p > a - 3 && Character.isWhitespace(text.charAt(p - 1))) {
                p--;
            }
            int q = p;
            while (q > 0 && (Character.isLetterOrDigit(text.charAt(q - 1)) || text.charAt(q - 1) == '-')) {
                q--;
            }
            final String pair = TermMatcher.fold(text.substring(q, p).replace("-", "")) + word;
            for (final String x : h.entry.excludes) {
                if (word.contains(x) || pair.contains(x)) {
                    return true;
                }
            }
            return false;
        }
    }

    public final String version;
    /**
     * Collection name -> vocabulary name, from the {@code collections} maps of the vocabulary files (the default of a collection;
     * {@code scoutro.kg.vocab.<collection>} overrides it). The shipped defaults map no collection.
     */
    public final Map<String, String> collections;
    /** Collection name -> the facility kinds fixed for it by a vocabulary file ({@code collection_llm_kinds}; not versioned). */
    public final Map<String, Set<String>> collectionLlmKinds;
    public final Map<String, Vocab> vocabularies;
    public final Map<String, Entry> customerTypes;
    public final Map<String, Entry> companySizes;
    public final Map<String, Entry> employmentTypes;
    private final Map<String, Entry> byId;
    private final TermMatcher<Entry> customerTypeMatcher;
    private final TermMatcher<Entry> companySizeMatcher;
    private final TermMatcher<Entry> employmentTypeMatcher;

    static final Categories EMPTY = new Categories("0", new TreeMap<>(), new TreeMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
            new LinkedHashMap<>(), new LinkedHashMap<>());

    private Categories(final String version, final Map<String, String> collections, final Map<String, Set<String>> collectionLlmKinds,
            final Map<String, Vocab> vocabularies, final Map<String, Entry> customerTypes, final Map<String, Entry> companySizes,
            final Map<String, Entry> employmentTypes) {
        this.version = version;
        this.collections = Collections.unmodifiableMap(collections);
        this.collectionLlmKinds = Collections.unmodifiableMap(collectionLlmKinds);
        this.vocabularies = Collections.unmodifiableMap(vocabularies);
        this.customerTypes = Collections.unmodifiableMap(customerTypes);
        this.companySizes = Collections.unmodifiableMap(companySizes);
        this.employmentTypes = Collections.unmodifiableMap(employmentTypes);
        final Map<String, Entry> all = new LinkedHashMap<>();
        for (final Vocab v : vocabularies.values()) {
            all.putAll(v.groups);
            all.putAll(v.categories);
            for (final Entry e : v.segments.values()) {
                all.put("segment:" + e.id, e);
            }
        }
        for (final Map<String, Entry> m : List.of(customerTypes, companySizes, employmentTypes)) {
            all.putAll(m);
        }
        this.byId = Collections.unmodifiableMap(all);
        this.customerTypeMatcher = TermMatcher.of(customerTypes.values(), e -> e.terms, false);
        this.companySizeMatcher = TermMatcher.of(companySizes.values(), e -> e.terms, false);
        this.employmentTypeMatcher = TermMatcher.of(employmentTypes.values(), e -> e.terms, false);
    }

    /** A category, group, customer type, company size or employment type by its stored code; null if unknown. */
    public Entry entry(final String id) {
        return id == null ? null : this.byId.get(id);
    }

    /** An audience segment by its stored code ({@code care/senioren}). */
    /**
     * The entry behind a code value of a predicate: a customer type, company
     * size or employment type, an audience segment, or else a service category
     * or group; null if the vocabulary does not know it.
     */
    public Entry code(final String predicate, final String value) {
        if (value == null) {
            return null;
        }
        switch (predicate == null ? "" : predicate) {
            case "customer_type":
                return this.customerTypes.get(value);
            case "company_size":
                return this.companySizes.get(value);
            case "employment_type":
                return this.employmentTypes.get(value);
            case "audience_segment":
                return segment(value);
            default:
                return entry(value);
        }
    }

    public Entry segment(final String id) {
        return id == null ? null : this.byId.get("segment:" + id);
    }

    public Vocab vocabulary(final String name) {
        return name == null ? null : this.vocabularies.get(name);
    }

    /** The facility kinds of a vocabulary (its profile); empty for none or an unknown one. */
    public Set<String> llmKinds(final String vocabulary) {
        final Vocab v = vocabulary(vocabulary);
        return v == null ? Collections.emptySet() : v.llmKinds;
    }

    public List<TermMatcher.Hit<Entry>> customerTypes(final String text, final int from, final int to) {
        return this.customerTypeMatcher.find(text, from, to);
    }

    public List<TermMatcher.Hit<Entry>> companySizes(final String text, final int from, final int to) {
        return this.companySizeMatcher.find(text, from, to);
    }

    private static final Set<String> INTERNSHIP_WORDS = Set.of("intern", "interns", "internship", "internships");

    public List<TermMatcher.Hit<Entry>> employmentTypes(final String text, final int from, final int to) {
        final List<TermMatcher.Hit<Entry>> out = new ArrayList<>();
        for (final TermMatcher.Hit<Entry> h : this.employmentTypeMatcher.find(text, from, to)) {
            // the term "intern" starts "Internationale", "Internet", "interne": only the word itself or "internship" names one (0.10.5)
            final String word = TermMatcher.fold(text.substring(h.start, h.end));
            if (word.startsWith("intern") && !INTERNSHIP_WORDS.contains(word)) {
                continue;
            }
            out.add(h);
        }
        return out;
    }

    public int categoryCount() {
        int n = 0;
        for (final Vocab v : this.vocabularies.values()) {
            n += v.categories.size();
        }
        return n;
    }

    // ------------------------------------------------------------- reading

    /** Collects the problems of the files read; the valid parts are used. */
    public static final class Problems {
        public final List<String> list = new ArrayList<>();

        void add(final String where, final String what) {
            if (this.list.size() < 50) {
                this.list.add(where + ": " + what);
            }
        }
    }

    /**
     * Merges the default file and the override files (in this order) and
     * validates every entry against the schema and, if given, every NACE
     * code against the classification. An invalid entry is left out and
     * reported; nothing throws for content.
     */
    public static Categories read(final List<JSONObject> files, final List<String> names, final Nace nace, final Problems problems) {
        final Map<String, String> collections = new TreeMap<>();
        final Map<String, Set<String>> collectionKinds = new TreeMap<>();
        final Map<String, Raw> vocabs = new LinkedHashMap<>();
        final Map<String, JSONObject> customerTypes = new LinkedHashMap<>();
        final Map<String, JSONObject> companySizes = new LinkedHashMap<>();
        final Map<String, JSONObject> employmentTypes = new LinkedHashMap<>();
        for (int i = 0; i < files.size(); i++) {
            final JSONObject f = files.get(i);
            final String where = names.get(i);
            if (f == null) {
                continue;
            }
            if (!"scoutro.kg.categories".equals(f.optString("schema"))) {
                problems.add(where, "schema must be scoutro.kg.categories");
                continue;
            }
            final JSONObject cols = f.optJSONObject("collections");
            if (cols != null) {
                for (final Iterator<String> it = cols.keys(); it.hasNext();) {
                    final String c = it.next();
                    final String v = cols.optString(c, null);
                    if (COLLECTION.matcher(c).matches() && v != null && VOCABULARY.matcher(v).matches()) {
                        collections.put(c, v);
                    } else {
                        problems.add(where, "invalid collection mapping '" + clip(c) + "'");
                    }
                }
            }
            final JSONObject ck = f.optJSONObject("collection_llm_kinds");
            if (ck != null) {
                for (final Iterator<String> it = ck.keys(); it.hasNext();) {
                    final String c = it.next();
                    final Set<String> k = COLLECTION.matcher(c).matches() ? kinds(ck.optJSONArray(c), where + " collection_llm_kinds " + clip(c),
                            problems) : null;
                    if (k != null) {
                        collectionKinds.put(c, k);
                    } else {
                        problems.add(where, "invalid collection_llm_kinds of '" + clip(c) + "'");
                    }
                }
            }
            merge(customerTypes, f.optJSONArray("customer_types"), where + " customer_types", problems);
            merge(companySizes, f.optJSONArray("company_sizes"), where + " company_sizes", problems);
            merge(employmentTypes, f.optJSONArray("employment_types"), where + " employment_types", problems);
            final JSONObject vs = f.optJSONObject("vocabularies");
            if (vs != null) {
                for (final Iterator<String> it = vs.keys(); it.hasNext();) {
                    final String name = it.next();
                    final JSONObject v = vs.optJSONObject(name);
                    if (!VOCABULARY.matcher(name).matches() || v == null) {
                        problems.add(where, "invalid vocabulary '" + clip(name) + "'");
                        continue;
                    }
                    final Raw raw = vocabs.computeIfAbsent(name, k -> new Raw());
                    if (v.optString("de", null) != null) {
                        raw.de = v.optString("de");
                    }
                    if (v.optString("en", null) != null) {
                        raw.en = v.optString("en");
                    }
                    if (v.has("llm_kinds")) {
                        // a later file replaces the kinds of the vocabulary as a whole
                        final Set<String> k = kinds(v.optJSONArray("llm_kinds"), where + " " + name + " llm_kinds", problems);
                        if (k != null) {
                            raw.llmKinds = k;
                        } else {
                            problems.add(where, "invalid llm_kinds of '" + clip(name) + "' (a list of facility kinds)");
                        }
                    }
                    if (v.has("operates_places")) {
                        // a later file replaces it, as the kinds
                        final Object o = v.opt("operates_places");
                        if (o instanceof Boolean) {
                            raw.operatesPlaces = (Boolean) o;
                        } else {
                            problems.add(where, "invalid operates_places of '" + clip(name) + "' (true or false)");
                        }
                    }
                    merge(raw.groups, v.optJSONArray("groups"), where + " " + name + " groups", problems);
                    merge(raw.categories, v.optJSONArray("categories"), where + " " + name + " categories", problems);
                    merge(raw.segments, v.optJSONArray("segments"), where + " " + name + " segments", problems);
                    merge(raw.organisations, v.optJSONArray("organisations"), where + " " + name + " organisations", problems);
                }
            }
        }
        final Map<String, Vocab> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Raw> e : vocabs.entrySet()) {
            final String vn = e.getKey();
            final Raw raw = e.getValue();
            final Map<String, Entry> groups = new LinkedHashMap<>();
            for (final JSONObject g : raw.groups.values()) {
                final Entry x = entry(vn + "." + g.optString("code"), vn, g, null, nace, "group", problems);
                if (x != null) {
                    groups.put(x.id, x);
                }
            }
            final Map<String, Entry> categories = new LinkedHashMap<>();
            for (final JSONObject c : raw.categories.values()) {
                final String group = c.optString("group", null);
                if (group != null && !groups.containsKey(vn + "." + group)) {
                    problems.add(vn + "/" + c.optString("code"), "unknown group '" + clip(group) + "'");
                    continue;
                }
                final Entry x = entry(vn + "/" + c.optString("code"), vn, c, group == null ? null : vn + "." + group, nace, "category",
                        problems);
                if (x != null) {
                    categories.put(x.id, x);
                }
            }
            final Map<String, Entry> segments = new LinkedHashMap<>();
            for (final JSONObject s : raw.segments.values()) {
                final Entry x = entry(vn + "/" + s.optString("code"), vn, s, null, null, "segment", problems);
                if (x != null) {
                    segments.put(x.id, x);
                }
            }
            final Map<String, Entry> organisations = new LinkedHashMap<>();
            for (final JSONObject o : raw.organisations.values()) {
                final Entry x = entry(vn + "/" + o.optString("code"), vn, o, null, nace, "organisation", problems);
                if (x != null && x.nace == null) {
                    problems.add(x.id, "organisation needs a NACE code");
                } else if (x != null) {
                    organisations.put(x.id, x);
                }
            }
            out.put(vn, new Vocab(vn, raw.de == null ? vn : raw.de, raw.en == null ? vn : raw.en,
                    raw.llmKinds == null ? Collections.emptySet() : raw.llmKinds, groups, categories, segments, organisations,
                    raw.operatesPlaces));
        }
        for (final Iterator<Map.Entry<String, String>> it = collections.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<String, String> c = it.next();
            if (!out.containsKey(c.getValue())) {
                problems.add("collections", c.getKey() + " maps to the unknown vocabulary '" + clip(c.getValue()) + "'");
                it.remove();
            }
        }
        final Map<String, Entry> ct = flat(customerTypes, "customer type", problems);
        final Map<String, Entry> cs = flat(companySizes, "company size", problems);
        final Map<String, Entry> et = flat(employmentTypes, "employment type", problems);
        // the facility kinds (of vocabularies and of collections) and operates_places are left out: they never re-extract
        final String version = version(collections, out, ct, cs, et);
        return new Categories(version, collections, collectionKinds, out, ct, cs, et);
    }

    private static final class Raw {
        String de;
        String en;
        /** null: not given (no kinds). */
        Set<String> llmKinds;
        boolean operatesPlaces;
        final Map<String, JSONObject> groups = new LinkedHashMap<>();
        final Map<String, JSONObject> categories = new LinkedHashMap<>();
        final Map<String, JSONObject> segments = new LinkedHashMap<>();
        final Map<String, JSONObject> organisations = new LinkedHashMap<>();
    }

    private static void merge(final Map<String, JSONObject> into, final JSONArray items, final String where, final Problems problems) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            final JSONObject o = items.optJSONObject(i);
            final String code = o == null ? null : o.optString("code", null);
            if (code == null || !CODE.matcher(code).matches()) {
                problems.add(where, "entry " + i + " has no valid code");
                continue;
            }
            into.put(code, o);
        }
    }

    /**
     * A list of facility kinds (sorted; an empty list means none); an invalid kind is left out and reported, more than
     * {@link #MAX_LLM_KINDS} are cut. Null if {@code items} is no list.
     */
    private static Set<String> kinds(final JSONArray items, final String where, final Problems problems) {
        if (items == null) {
            return null;
        }
        final Set<String> out = new TreeSet<>();
        for (int i = 0; i < items.length(); i++) {
            final String k = items.optString(i, "");
            if (!LLM_KIND.matcher(k).matches()) {
                problems.add(where, "invalid facility kind '" + clip(k) + "' (2 to 64 lower-case letters)");
            } else if (out.size() >= MAX_LLM_KINDS) {
                problems.add(where, "at most " + MAX_LLM_KINDS + " kinds");
                break;
            } else {
                out.add(k);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static Map<String, Entry> flat(final Map<String, JSONObject> raw, final String what, final Problems problems) {
        final Map<String, Entry> out = new LinkedHashMap<>();
        for (final JSONObject o : raw.values()) {
            final Entry e = entry(o.optString("code"), null, o, null, null, what, problems);
            if (e != null) {
                out.put(e.id, e);
            }
        }
        return out;
    }

    private static Entry entry(final String id, final String vocabulary, final JSONObject o, final String group, final Nace nace,
            final String what, final Problems problems) {
        final String code = o.optString("code");
        final String de = label(o.optString("de", null));
        final String en = label(o.optString("en", null));
        if (de == null || en == null) {
            problems.add(id, what + " needs a German and an English label (1 to 120 characters)");
            return null;
        }
        String n = o.isNull("nace") ? null : o.optString("nace", null);
        if (n != null && nace != null && nace.size() > 0 && !nace.valid(n)) {
            problems.add(id, "unknown NACE code '" + clip(n) + "'");
            n = null;
        }
        final List<String> terms = new ArrayList<>();
        final JSONArray t = o.optJSONArray("terms");
        for (int i = 0; t != null && i < t.length() && terms.size() < 40; i++) {
            final String term = t.optString(i, "").trim();
            if (!term.isEmpty() && term.length() <= 80 && !TermMatcher.tokens(term).isEmpty()) {
                terms.add(term);
            }
        }
        if (terms.isEmpty()) {
            terms.add(de);
            if (!en.equals(de)) {
                terms.add(en);
            }
        }
        final List<String> excludes = new ArrayList<>();
        final JSONArray x = o.optJSONArray("exclude");
        for (int i = 0; x != null && i < x.length() && excludes.size() < 40; i++) {
            final String word = TermMatcher.fold(x.optString(i, "").trim().replace("-", "").replace(" ", ""));
            if (word.length() >= 4 && word.length() <= 80) {
                excludes.add(word);
            }
        }
        return new Entry(id, vocabulary, code, de, en, group, n, terms, excludes);
    }

    private static String label(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim();
        return t.isEmpty() || t.length() > 120 ? null : t;
    }

    private static String version(final Map<String, String> collections, final Map<String, Vocab> vocabs, final Map<String, Entry> ct,
            final Map<String, Entry> cs, final Map<String, Entry> et) {
        final StringBuilder sb = new StringBuilder();
        sb.append(collections).append('\u0000');
        for (final Vocab v : vocabs.values()) {
            sb.append(v.name).append('\u0001');
            for (final Map<String, Entry> m : List.of(v.groups, v.categories, v.segments, v.organisations)) {
                for (final Entry e : m.values()) {
                    sb.append(e.id).append('|').append(e.group).append('|').append(e.nace).append('|').append(e.terms)
                            .append(e.excludes.isEmpty() ? "" : "|" + e.excludes).append('\u0002');
                }
            }
        }
        for (final Map<String, Entry> m : List.of(ct, cs, et)) {
            for (final Entry e : m.values()) {
                sb.append(e.id).append('|').append(e.terms).append('\u0002');
            }
        }
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", d[i] & 0xff));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The collections one vocabulary file maps, as {@link #read} takes them from it: a valid name with a valid vocabulary name
     * (also one the files do not define, which {@link #read} then reports and drops after the merge).
     */
    public static Set<String> mappedCollections(final JSONObject file) {
        final Set<String> out = new TreeSet<>();
        final JSONObject cols = file == null || !"scoutro.kg.categories".equals(file.optString("schema")) ? null
                : file.optJSONObject("collections");
        if (cols != null) {
            for (final Iterator<String> it = cols.keys(); it.hasNext();) {
                final String c = it.next();
                final String v = cols.optString(c, null);
                if (COLLECTION.matcher(c).matches() && v != null && VOCABULARY.matcher(v).matches()) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** The vocabularies of a document's collections, given the collection -> vocabulary mapping in force. */
    public static Set<String> vocabulariesOf(final Collection<String> docCollections, final Map<String, String> mapping) {
        final Set<String> out = new LinkedHashSet<>();
        if (docCollections != null) {
            for (final String c : docCollections) {
                final String v = mapping.get(c);
                if (v != null) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static String clip(final String s) {
        return s == null ? "" : s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
