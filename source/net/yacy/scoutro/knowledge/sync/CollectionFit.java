/*
 *  CollectionFit
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

package net.yacy.scoutro.knowledge.sync;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;

/**
 * The collection fit of a website, asked in the LLM tier's request of its home page (docs/SCOUTRO_COLLECTION_AUTOCORRECT.md,
 * "Home page reading"): no request of its own, no change of the system prompt, the schema, the extractor version or the
 * prompt hash. Only the user message of the first request of a home page carries a short question with the profiles of
 * the Discovery collections ({@code profiles.json}); the model adds the field {@code collection_fit} with one verdict per
 * assigned collection ({@code match}, {@code mismatch}, {@code uncertain}), the best fitting collection and a verbatim
 * quote of the main offering. The answer is validated apart from the extraction and never becomes a statement: a missing
 * or invalid field, or one without a quote found in the page text, is {@code uncertain}, the extraction stays.
 */
public final class CollectionFit {

    /** The setting that switches it on (default off). */
    public static final String SETTING = "scoutro.kg.llm.collectionFit";
    /** The field of the answer. */
    public static final String FIELD = "collection_fit";
    /** The question at most, in characters (K3); a longer one is not asked. */
    public static final int MAX_CHARS = 500;
    /** Assigned collections asked at most per home page. */
    public static final int MAX_ASSIGNED = 3;
    /** The topic of one assigned collection, of two or three, an other profile's entry: characters at most. */
    static final int TOPIC_ONE = 100, TOPIC_MORE = 60, OTHER = 40, QUOTE = 200;

    public static final String MATCH = "match", MISMATCH = "mismatch", UNCERTAIN = "uncertain", NONE = "none";
    static final Set<String> VERDICTS = Set.of(MATCH, MISMATCH, UNCERTAIN);
    /**
     * How an answer's field was: valid, absent, not of the expected shape, without a quote found in the text; or not asked
     * because the assigned collections alone do not fit the question's length (K3).
     */
    public static final String VALID = "valid", MISSING = "fit_missing", INVALID = "fit_invalid", UNQUOTED = "fit_unquoted",
            BUDGET = "fit_budget";

    /** The marker of the question (the exception to rule 9 of the system prompt, K2). */
    public static final String MARKER = "Exception to rule 9";
    private static final String INSTRUCTION = MARKER + ", this request only: add \"" + FIELD + "\" (does the site's main offering fit?)\n";
    private static final String FORMAT = "{\"verdicts\":{id:\"match|mismatch|uncertain\"},\"best\":id|\"none\",\"quote\":verbatim page text,"
            + " max 200 chars}; unclear: uncertain\n";

    private CollectionFit() {}

    // -------------------------------------------------------------------------------------------- profiles

    /** One Discovery profile: its collection and topic. */
    public static final class Profile {
        public final String name;
        public final String collection;
        public final String topic;

        Profile(final String name, final String collection, final String topic) {
            this.name = name;
            this.collection = collection;
            this.topic = topic;
        }
    }

    /** The profiles of {@code profiles.json}, by collection, and their criteria version. */
    public static final class Profiles {
        public final String criteria;
        public final Map<String, Profile> byCollection;

        Profiles(final String criteria, final Map<String, Profile> byCollection) {
            this.criteria = criteria;
            this.byCollection = Collections.unmodifiableMap(byCollection);
        }
    }

    private static volatile Object[] cached; // file, modified, size, profiles

    /** The profiles of the file (cached per file version), or null if it cannot be read. */
    public static Profiles read(final File file) {
        try {
            if (file == null || !file.isFile()) return null;
            final long modified = file.lastModified(), size = file.length();
            final Object[] c = cached;
            if (c != null && c[0].equals(file) && (long) c[1] == modified && (long) c[2] == size) return (Profiles) c[3];
            final JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            final JSONObject profiles = root.optJSONObject("profiles");
            if (profiles == null) return null;
            final Map<String, Profile> by = new TreeMap<>();
            for (final String name : new java.util.TreeSet<>(profiles.keySet())) {
                final JSONObject p = profiles.optJSONObject(name);
                final String collection = p == null ? "" : p.optString("collection", "");
                if (collection.isEmpty() || by.containsKey(collection)) continue;
                by.put(collection, new Profile(name, collection, p.optString("topic", "").replaceAll("\\s+", " ").trim()));
            }
            final Profiles out = new Profiles(root.optString("criteria_version", "unversioned"), by);
            cached = new Object[] {file, modified, size, out};
            return out;
        } catch (final IOException | JSONException | RuntimeException e) {
            return null; // no readable profiles: the question is not asked
        }
    }

    // -------------------------------------------------------------------------------------------- question

    /** The question of one home page: the assigned collections, every collection named, the text (null: not asked, K3). */
    public static final class Question {
        public final List<String> assigned;
        public final Set<String> named;
        public final String text;
        public final String criteria;

        Question(final List<String> assigned, final Set<String> named, final String text, final String criteria) {
            this.assigned = Collections.unmodifiableList(assigned);
            this.named = Collections.unmodifiableSet(named);
            this.text = text;
            this.criteria = criteria;
        }

        /** False if the question is too long to be asked: the home page is read without it, its verdict {@link #BUDGET}. */
        public boolean asked() {
            return this.text != null;
        }
    }

    /**
     * The question for the assigned collections (at most {@link #MAX_ASSIGNED}, each with its profile's topic) and the other
     * profiles (their collection id and label, in the given order, as many as fit): at most {@link #MAX_CHARS} characters;
     * null if none has a profile; not {@link Question#asked asked} if the assigned ones alone do not fit (K3).
     *
     * @param labels a short label per collection (the vocabulary's), else the profile's topic is clipped
     */
    public static Question question(final Profiles profiles, final List<String> assigned, final List<String> others,
            final Map<String, String> labels) {
        if (profiles == null) return null;
        final List<String> mine = new ArrayList<>();
        for (final String c : assigned) {
            if (profiles.byCollection.containsKey(c) && !mine.contains(c) && mine.size() < MAX_ASSIGNED) mine.add(c);
        }
        if (mine.isEmpty()) return null;
        final int topic = mine.size() == 1 ? TOPIC_ONE : TOPIC_MORE;
        final StringBuilder sb = new StringBuilder(INSTRUCTION).append("Assigned: ");
        for (int i = 0; i < mine.size(); i++) {
            sb.append(i == 0 ? "" : "; ").append(mine.get(i)).append(" (").append(clip(profiles.byCollection.get(mine.get(i)).topic, topic)).append(')');
        }
        sb.append('\n');
        final String fixed = sb.toString();
        final java.util.LinkedHashSet<String> named = new java.util.LinkedHashSet<>(mine);
        if (fixed.length() + FORMAT.length() > MAX_CHARS) return new Question(mine, named, null, profiles.criteria);
        final StringBuilder rest = new StringBuilder();
        for (final String c : others) {
            if (named.contains(c) || !profiles.byCollection.containsKey(c)) continue;
            final String label = labels != null && labels.get(c) != null ? labels.get(c) : profiles.byCollection.get(c).topic;
            final String entry = clip(c + ": " + label, OTHER);
            final String candidate = (rest.length() == 0 ? "Others: " : rest + ", ") + entry;
            if (fixed.length() + candidate.length() + 1 + FORMAT.length() > MAX_CHARS) break;
            rest.setLength(0);
            rest.append(candidate);
            named.add(c);
        }
        final String text = fixed + (rest.length() == 0 ? "" : rest + "\n") + FORMAT;
        return new Question(mine, named, text, profiles.criteria);
    }

    private static String clip(final String s, final int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)).trim() + "…";
    }

    // -------------------------------------------------------------------------------------------- answer

    /** The validated reading of one home page: a verdict per assigned collection, the best one, the quote, how it was. */
    public static final class Verdict {
        public final Map<String, String> verdicts;
        public final String best;
        public final String quote;
        public final String status;

        Verdict(final Map<String, String> verdicts, final String best, final String quote, final String status) {
            this.verdicts = Collections.unmodifiableMap(verdicts);
            this.best = best;
            this.quote = quote;
            this.status = status;
        }

        public JSONObject json() {
            return KgJson.obj("verdicts", new JSONObject(this.verdicts), "best", this.best, "quote", this.quote, "status", this.status);
        }

        /** True if at least one collection got match or mismatch. */
        public boolean decided() {
            for (final String v : this.verdicts.values()) if (!UNCERTAIN.equals(v)) return true;
            return false;
        }
    }

    private static Verdict uncertain(final Question q, final String status) {
        final Map<String, String> all = new LinkedHashMap<>();
        for (final String c : q.assigned) all.put(c, UNCERTAIN);
        return new Verdict(all, NONE, null, status);
    }

    /** The verdict of a question that was not asked for its length: every assigned collection uncertain (K3). */
    public static Verdict skipped(final Question q) {
        return uncertain(q, BUDGET);
    }

    /**
     * The verdict of the answer's field for the question, checked against the chunk the model read (3.5, K5, K7): the
     * verdicts an object of assigned ids only (another id makes the whole field invalid), each in the enumeration (else
     * uncertain), a missing one uncertain; best one of the collections named or none; the quote found in the text, else
     * every verdict uncertain and best none (an unquoted match would block a correction as an unquoted mismatch would
     * cause one).
     */
    public static Verdict check(final Object field, final Question q, final String chunkText) {
        if (field == null) return uncertain(q, MISSING);
        if (!(field instanceof JSONObject)) return uncertain(q, INVALID);
        final JSONObject f = (JSONObject) field;
        final Object v = f.opt("verdicts");
        if (!(v instanceof JSONObject)) return uncertain(q, INVALID);
        final JSONObject given = (JSONObject) v;
        for (final String k : given.keySet()) if (!q.assigned.contains(k)) return uncertain(q, INVALID);
        final Map<String, String> verdicts = new LinkedHashMap<>();
        for (final String c : q.assigned) {
            final Object x = given.opt(c);
            verdicts.put(c, x instanceof String && VERDICTS.contains(x) ? (String) x : UNCERTAIN);
        }
        final Object b = f.opt("best");
        String best = b instanceof String && (q.named.contains(b) || NONE.equals(b)) ? (String) b : NONE;
        final Object qt = f.opt("quote");
        final String quote = qt instanceof String ? ((String) qt).trim() : null;
        if (quote == null || quote.isEmpty() || quote.length() > QUOTE || !LlmExtractor.quoteInText(chunkText, quote)) {
            return uncertain(q, UNQUOTED);
        }
        return new Verdict(verdicts, best, quote, VALID);
    }

    /** The verdict stored with a cached answer ({@link Verdict#json}), or the missing field's. */
    public static Verdict cached(final JSONObject stored, final Question q) {
        if (stored == null) return uncertain(q, MISSING);
        final JSONObject v = stored.optJSONObject("verdicts");
        final Map<String, String> verdicts = new LinkedHashMap<>();
        for (final String c : q.assigned) {
            final String x = v == null ? null : v.optString(c, null);
            verdicts.put(c, x != null && VERDICTS.contains(x) ? x : UNCERTAIN);
        }
        return new Verdict(verdicts, stored.optString("best", NONE), stored.isNull("quote") ? null : stored.optString("quote", null),
                stored.optString("status", VALID));
    }

    // -------------------------------------------------------------------------------------------- the hook

    /** Where the verdicts go (the correction automation's state), and whether a home page needs to be asked. */
    public interface Sink {
        /** True if (domain, collection) has no reading of this content under these criteria yet. */
        boolean needed(String domain, String collection, String contentHash, String criteria);

        /** A published home page's verdict; never throws into the LLM tier (the caller catches everything). */
        void verdict(String domain, String docId, String contentHash, String model, String criteria, Verdict verdict);
    }

    private static volatile Supplier<File> profilesFile = () -> null;
    private static volatile Sink sink;

    /** Installed by the Scoutro API at its start: the file of the Discovery profiles and the sink. */
    public static void install(final Supplier<File> profiles, final Sink s) {
        profilesFile = profiles == null ? () -> null : profiles;
        sink = s;
    }

    public static Sink sink() {
        return sink;
    }

    public static Profiles profiles() {
        final Supplier<File> f = profilesFile;
        return read(f == null ? null : f.get());
    }

    /** The collections of a page that have a profile, in their order. */
    static List<String> profiled(final Profiles p, final Collection<String> collections) {
        final List<String> out = new ArrayList<>();
        if (p == null || collections == null) return out;
        for (final String c : new java.util.TreeSet<>(collections)) if (p.byCollection.containsKey(c)) out.add(c);
        return out;
    }
}
