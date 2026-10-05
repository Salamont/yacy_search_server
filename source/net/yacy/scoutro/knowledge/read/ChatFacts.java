/*
 *  ChatFacts
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

package net.yacy.scoutro.knowledge.read;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * The facts the Scoutro chat may add to a RAG answer
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 8.4), for one viewer: entities with
 * evidence on the retrieved pages, and entities the question names, with their
 * current supported or uncertain facts. Every fact comes with the visible page
 * it was read from; conflicting and stale facts are left out. One bounded read
 * lease; the caller sets its deadline.
 */
public final class ChatFacts {

    /** At most this many entities per answer. */
    public static final int MAX_ENTITIES = 4;
    /** Retrieved pages used to find entities. */
    static final int MAX_DOCS = 10;
    /** Question words used for the name search. */
    static final int MAX_TERMS = 8;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    /** Name words that never identify an entity on their own. */
    private static final Set<String> GENERIC = Set.of("gmbh", "ggmbh", "mbh", "ag", "kg", "kgaa", "ug", "se", "ev", "eg", "gbr",
            "ohg", "co", "und", "der", "die", "das", "the", "and", "of", "inc", "ltd", "llc");
    /** Order of the facts in an entry. */
    private static final List<String> ORDER = List.of(Vocabulary.LEGAL_FORM, Vocabulary.OPERATES, Vocabulary.PART_OF,
            Vocabulary.LOCATED_AT, Vocabulary.IN_PLACE, Vocabulary.OFFERS, Vocabulary.ADDRESS, Vocabulary.POSTAL_CODE,
            Vocabulary.LOCALITY, Vocabulary.OPENING_HOURS, Vocabulary.PHONE, Vocabulary.EMAIL, Vocabulary.WEBSITE,
            Vocabulary.ID_REGISTER, Vocabulary.ID_VAT, Vocabulary.ID_LEI, Vocabulary.ID_IK, Vocabulary.ID_WIKIDATA, Vocabulary.ALIAS);

    /** One fact as the chat shows it. */
    public static final class Fact {
        public final String statement;
        public final String predicate;
        public final String value;
        /** supported or uncertain */
        public final String quality;
        /** Only the LLM tier read it (then always uncertain). */
        public final boolean llmOnly;
        /** The page states it with a hedge ("plant", "voraussichtlich"). */
        public final boolean hedged;

        public Fact(final String statement, final String predicate, final String value, final String quality, final boolean llmOnly,
                final boolean hedged) {
            this.statement = statement;
            this.predicate = predicate;
            this.value = value;
            this.quality = quality;
            this.llmOnly = llmOnly;
            this.hedged = hedged;
        }
    }

    /** The facts of one entity read from one page. */
    public static final class Entry {
        public final String entity;
        public final String name;
        public final String type;
        public final String url;
        public final String docId;
        /** A visible collection of the page; null if none is named. */
        public final String collection;
        public final List<Fact> facts = new ArrayList<>();

        public Entry(final String entity, final String name, final String type, final String url, final String docId, final String collection) {
            this.entity = entity;
            this.name = name;
            this.type = type;
            this.url = url;
            this.docId = docId;
            this.collection = collection;
        }
    }

    private final KgReader reader;

    public ChatFacts(final KgReader reader) {
        this.reader = reader;
    }

    /**
     * @param docIds   YaCy URL hashes of the retrieved pages, best first
     * @param terms    folded question words (stems), most important first
     * @param maxFacts upper bound of facts over all entries
     * @param deadline read lease deadline in milliseconds
     */
    public List<Entry> select(final List<String> docIds, final List<String> terms, final Viewer v, final int maxFacts, final long deadline)
            throws KgException {
        final long now = this.reader.now();
        return this.reader.store().read(c -> {
            final List<Long> entities = candidates(c, docIds, terms, v);
            final List<Entry> out = new ArrayList<>();
            int facts = 0;
            for (final Long ent : entities) {
                if (facts >= maxFacts) {
                    break;
                }
                for (final Entry e : entries(c, ent, docIds, v, now, maxFacts - facts)) {
                    out.add(e);
                    facts += e.facts.size();
                }
            }
            return out;
        }, deadline);
    }

    /** Visible entities, ranked: named in the question first, then found on the retrieved pages. */
    private List<Long> candidates(final Connection c, final List<String> docIds, final List<String> terms, final Viewer v)
            throws SQLException {
        final Map<Long, Integer> score = new LinkedHashMap<>();
        final List<String> stems = new ArrayList<>();
        for (final String t : terms) {
            if (t != null && t.length() >= 3 && stems.size() < MAX_TERMS) {
                stems.add(t.toLowerCase(Locale.ROOT));
            }
        }
        // 1. names that the question names: at least two of their distinctive words (or the only one)
        if (!stems.isEmpty()) {
            final StringBuilder match = new StringBuilder();
            for (final String s : stems) {
                final String clean = s.replaceAll("[^\\p{L}\\p{N}]", "");
                if (!clean.isEmpty()) {
                    match.append(match.length() == 0 ? "" : " OR ").append('"').append(clean).append("\"*");
                }
            }
            if (match.length() > 0) {
                try (PreparedStatement ps = c.prepareStatement("SELECT s.subj, s.obj_val FROM kg_name_fts f JOIN kg_statement s"
                        + " ON s.stmt_rowid = f.rowid JOIN kg_entity e ON e.ent_rowid = s.subj WHERE kg_name_fts MATCH ? AND "
                        + KgReader.visibleStatement(v, "s") + " AND " + KgReader.visibleEntity(v, "e") + " LIMIT 50")) {
                    ps.setString(1, match.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            final int named = named(rs.getString(2), stems);
                            if (named > 0) {
                                score.merge(rs.getLong(1), 10 * named, Math::max);
                            }
                        }
                    }
                }
            }
        }
        // 2. entities with evidence on the retrieved pages, in retrieval order
        int rank = 0;
        for (final String docId : docIds.subList(0, Math.min(MAX_DOCS, docIds.size()))) {
            rank++;
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT s.subj FROM kg_doc d JOIN kg_evidence ev ON ev.doc_rowid = d.doc_rowid"
                    + " JOIN kg_statement s ON s.stmt_rowid = ev.stmt_rowid JOIN kg_entity e ON e.ent_rowid = s.subj WHERE d.doc_id = ? AND "
                    + KgReader.visibleDoc(v, "d") + " AND " + KgReader.visibleEntity(v, "e") + " LIMIT 3")) {
                ps.setString(1, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final int fromDoc = Math.max(1, 10 - rank);
                        score.merge(rs.getLong(1), fromDoc, Integer::sum);
                    }
                }
            }
        }
        final List<Map.Entry<Long, Integer>> ranked = new ArrayList<>(score.entrySet());
        ranked.sort((a, b) -> b.getValue() - a.getValue());
        final List<Long> out = new ArrayList<>();
        for (final Map.Entry<Long, Integer> e : ranked) {
            if (out.size() < MAX_ENTITIES) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * How many distinctive words of a name the question contains; 0 unless at
     * least two (or all, for a one-word name) are named.
     */
    static int named(final String name, final List<String> stems) {
        if (name == null) {
            return 0;
        }
        final Set<String> words = new LinkedHashSet<>();
        final Matcher m = TOKEN.matcher(fold(name));
        while (m.find()) {
            final String w = m.group();
            if (w.length() >= 3 && !GENERIC.contains(w)) {
                words.add(w);
            }
        }
        if (words.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (final String w : words) {
            for (final String s : stems) {
                if (w.startsWith(s) || s.length() >= 4 && s.startsWith(w)) {
                    hits++;
                    break;
                }
            }
        }
        return hits >= Math.min(2, words.size()) ? hits : 0;
    }

    /** Lower case, umlauts folded like the chat's question terms. */
    static String fold(final String text) {
        return text.toLowerCase(Locale.ROOT).replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss");
    }

    /** The current supported and uncertain facts of an entity, grouped by the page each is read from. */
    private List<Entry> entries(final Connection c, final long ent, final List<String> docIds, final Viewer v, final long now,
            final int room) throws SQLException {
        final List<KgReader.Stat> stats = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + KgReader.STMT_COLUMNS + " FROM kg_statement s JOIN kg_vocab v"
                + " ON v.term_id = s.pred WHERE s.subj = ? AND " + KgReader.visibleStatement(v, "s") + " LIMIT 200")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.add(KgReader.stat(rs));
                }
            }
        }
        this.reader.compute(c, stats, v, now);
        final List<KgReader.Stat> chosen = new ArrayList<>();
        for (final KgReader.Stat s : stats) {
            if (s.current && ("supported".equals(s.quality) || "uncertain".equals(s.quality)) && ORDER.contains(s.predicate)) {
                chosen.add(s);
            }
        }
        if (chosen.isEmpty()) {
            return Collections.emptyList();
        }
        chosen.sort((a, b) -> {
            final int p = ORDER.indexOf(a.predicate) - ORDER.indexOf(b.predicate);
            return p != 0 ? p : a.rank() - b.rank();
        });
        final String id = KgReader.publicId(c, ent);
        final String name = KgReader.visibleName(c, ent, v);
        final String type;
        try (PreparedStatement ps = c.prepareStatement("SELECT t.name FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type WHERE e.ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                type = rs.next() ? rs.getString(1) : null;
            }
        }
        final Map<Long, Entry> byDoc = new LinkedHashMap<>();
        final Map<Long, String[]> docs = new HashMap<>();
        int n = 0;
        for (final KgReader.Stat s : chosen) {
            if (n >= room) {
                break;
            }
            final long doc = bestDoc(c, s.rowid, docIds, v, now, docs);
            if (doc == 0L) {
                continue;
            }
            final String[] d = docs.get(doc);
            final Entry e = byDoc.computeIfAbsent(doc, k -> new Entry(id, name, type, d[0], d[1], d[2]));
            final String value = s.objEnt != null ? KgReader.visibleName(c, s.objEnt, v) : s.objVal;
            if (value == null || value.isEmpty()) {
                continue;
            }
            e.facts.add(new Fact(s.publicId, s.predicate, value, s.quality, s.kinds.size() == 1 && s.kinds.contains("llm"), !s.stated));
            n++;
        }
        final List<Entry> out = new ArrayList<>();
        for (final Entry e : byDoc.values()) {
            if (!e.facts.isEmpty()) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * The visible, current page that best supports a statement: a retrieved
     * page first, then an active page, then the latest loaded; 0 if none.
     * Fills {@code docs} with {url, doc_id, collection}.
     */
    private long bestDoc(final Connection c, final long stmt, final List<String> docIds, final Viewer v, final long now,
            final Map<Long, String[]> docs) throws SQLException {
        long best = 0L;
        int bestScore = -1;
        try (PreparedStatement ps = c.prepareStatement("SELECT d.doc_rowid, d.url, d.doc_id, d.state FROM kg_evidence e JOIN kg_doc d"
                + " ON d.doc_rowid = e.doc_rowid WHERE e.stmt_rowid = ? AND " + KgReader.visibleDoc(v, "d") + " AND "
                + this.reader.currentDoc("d", now) + " AND d.url IS NOT NULL ORDER BY d.loaded_at DESC LIMIT 20")) {
            ps.setLong(1, stmt);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final int retrieved = docIds.indexOf(rs.getString(3));
                    final int score = (retrieved >= 0 ? 100 - retrieved : 0) + (rs.getInt(4) == 1 ? 10 : 0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = rs.getLong(1);
                        docs.putIfAbsent(best, new String[] {rs.getString(2), rs.getString(3), null});
                    }
                }
            }
        }
        if (best != 0L && docs.get(best)[2] == null) {
            final JSONArray colls = KgReader.docCollections(c, best, v);
            docs.get(best)[2] = colls.length() == 0 ? null : colls.optString(0, null);
        }
        return best;
    }
}
