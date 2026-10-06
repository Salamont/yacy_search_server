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
 */
public final class Categories {

    public static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{0,47}$");
    public static final Pattern VOCABULARY = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

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

        Entry(final String id, final String vocabulary, final String code, final String de, final String en, final String group,
                final String nace, final List<String> terms) {
            this.id = id;
            this.vocabulary = vocabulary;
            this.code = code;
            this.de = de;
            this.en = en;
            this.group = group;
            this.nace = nace;
            this.terms = Collections.unmodifiableList(terms);
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
        public final Map<String, Entry> groups;
        public final Map<String, Entry> categories;
        public final Map<String, Entry> segments;
        final TermMatcher<Entry> categoryMatcher;
        final TermMatcher<Entry> segmentMatcher;

        Vocab(final String name, final String de, final String en, final Map<String, Entry> groups, final Map<String, Entry> categories,
                final Map<String, Entry> segments) {
            this.name = name;
            this.de = de;
            this.en = en;
            this.groups = Collections.unmodifiableMap(groups);
            this.categories = Collections.unmodifiableMap(categories);
            this.segments = Collections.unmodifiableMap(segments);
            this.categoryMatcher = TermMatcher.of(categories.values(), e -> e.terms, true);
            this.segmentMatcher = TermMatcher.of(segments.values(), e -> e.terms, false);
        }

        /** Categories named in the text, with their positions. */
        public List<TermMatcher.Hit<Entry>> categories(final String text, final int from, final int to) {
            return this.categoryMatcher.find(text, from, to);
        }

        public List<TermMatcher.Hit<Entry>> segments(final String text, final int from, final int to) {
            return this.segmentMatcher.find(text, from, to);
        }
    }

    public final String version;
    /** Collection name -> vocabulary name (defaults; the configuration may override). */
    public final Map<String, String> collections;
    public final Map<String, Vocab> vocabularies;
    public final Map<String, Entry> customerTypes;
    public final Map<String, Entry> companySizes;
    public final Map<String, Entry> employmentTypes;
    private final Map<String, Entry> byId;
    private final TermMatcher<Entry> customerTypeMatcher;
    private final TermMatcher<Entry> companySizeMatcher;
    private final TermMatcher<Entry> employmentTypeMatcher;

    static final Categories EMPTY = new Categories("0", new TreeMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
            new LinkedHashMap<>(), new LinkedHashMap<>());

    private Categories(final String version, final Map<String, String> collections, final Map<String, Vocab> vocabularies,
            final Map<String, Entry> customerTypes, final Map<String, Entry> companySizes, final Map<String, Entry> employmentTypes) {
        this.version = version;
        this.collections = Collections.unmodifiableMap(collections);
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

    public List<TermMatcher.Hit<Entry>> customerTypes(final String text, final int from, final int to) {
        return this.customerTypeMatcher.find(text, from, to);
    }

    public List<TermMatcher.Hit<Entry>> companySizes(final String text, final int from, final int to) {
        return this.companySizeMatcher.find(text, from, to);
    }

    public List<TermMatcher.Hit<Entry>> employmentTypes(final String text, final int from, final int to) {
        return this.employmentTypeMatcher.find(text, from, to);
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
                    if (c.matches("^[A-Za-z0-9_-]{1,64}$") && v != null && VOCABULARY.matcher(v).matches()) {
                        collections.put(c, v);
                    } else {
                        problems.add(where, "invalid collection mapping '" + clip(c) + "'");
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
                    merge(raw.groups, v.optJSONArray("groups"), where + " " + name + " groups", problems);
                    merge(raw.categories, v.optJSONArray("categories"), where + " " + name + " categories", problems);
                    merge(raw.segments, v.optJSONArray("segments"), where + " " + name + " segments", problems);
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
            out.put(vn, new Vocab(vn, raw.de == null ? vn : raw.de, raw.en == null ? vn : raw.en, groups, categories, segments));
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
        final String version = version(collections, out, ct, cs, et);
        return new Categories(version, collections, out, ct, cs, et);
    }

    private static final class Raw {
        String de;
        String en;
        final Map<String, JSONObject> groups = new LinkedHashMap<>();
        final Map<String, JSONObject> categories = new LinkedHashMap<>();
        final Map<String, JSONObject> segments = new LinkedHashMap<>();
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
        return new Entry(id, vocabulary, code, de, en, group, n, terms);
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
            for (final Map<String, Entry> m : List.of(v.groups, v.categories, v.segments)) {
                for (final Entry e : m.values()) {
                    sb.append(e.id).append('|').append(e.group).append('|').append(e.nace).append('|').append(e.terms).append('\u0002');
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
