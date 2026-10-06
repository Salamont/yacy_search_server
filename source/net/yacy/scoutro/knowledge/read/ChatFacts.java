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
    private static final List<String> ORDER;
    static {
        final List<String> o = new ArrayList<>(List.of(Vocabulary.LEGAL_FORM, Vocabulary.INDUSTRY, Vocabulary.INDUSTRY_CATEGORY,
                Vocabulary.OPERATES, Vocabulary.PART_OF, Vocabulary.LOCATED_AT, Vocabulary.IN_PLACE, Vocabulary.OFFERS, Vocabulary.CATEGORY,
                Vocabulary.PRICE, Vocabulary.ADDRESS, Vocabulary.POSTAL_CODE, Vocabulary.LOCALITY, Vocabulary.OPENING_HOURS,
                Vocabulary.OFFICE_HOURS, Vocabulary.PHONE, Vocabulary.FAX, Vocabulary.EMAIL, Vocabulary.CONTACT_POINT, Vocabulary.CONTACT_FORM,
                Vocabulary.WEBSITE, Vocabulary.SOCIAL_PROFILE, Vocabulary.DIRECTIONS));
        // vocabulary 2: relations between organisations as the page states them (linked_to is no statement and never here)
        o.addAll(Vocabulary.BUSINESS_RELATIONS);
        o.addAll(List.of(Vocabulary.CERTIFICATION, Vocabulary.CUSTOMER_TYPE, Vocabulary.AUDIENCE_SEGMENT, Vocabulary.TARGET_INDUSTRY,
                Vocabulary.TARGET_CATEGORY, Vocabulary.COMPANY_SIZE, Vocabulary.SERVICE_AREA, Vocabulary.NEED, Vocabulary.ID_REGISTER,
                Vocabulary.ID_VAT, Vocabulary.ID_LEI, Vocabulary.ID_IK, Vocabulary.ID_WIKIDATA, Vocabulary.ALIAS));
        ORDER = Collections.unmodifiableList(o);
    }
    /** Business facts added per entity (services' prices, jobs, incoming relations, suggestions). */
    static final int MAX_BUSINESS_FACTS = 8;

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
        /**
         * Vocabulary 2: how the chat must word it: null for a stated fact, {@code suggestion} for a match Scoutro
         * computed (never a fact), {@code conflicting} for one of several prices, {@code ended} for a job that ended.
         */
        public final String note;

        public Fact(final String statement, final String predicate, final String value, final String quality, final boolean llmOnly,
                final boolean hedged) {
            this(statement, predicate, value, quality, llmOnly, hedged, null);
        }

        public Fact(final String statement, final String predicate, final String value, final String quality, final boolean llmOnly,
                final boolean hedged, final String note) {
            this.statement = statement;
            this.predicate = predicate;
            this.value = value;
            this.quality = quality;
            this.llmOnly = llmOnly;
            this.hedged = hedged;
            this.note = note;
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
            final String value = s.objEnt != null ? KgReader.visibleName(c, s.objEnt, v) : readable(s.predicate, s.objVal);
            if (value == null || value.isEmpty()) {
                continue;
            }
            e.facts.add(new Fact(s.publicId, s.predicate, value, s.quality, s.kinds.size() == 1 && s.kinds.contains("llm"), !s.stated));
            n++;
        }
        // vocabulary 2: prices of the offered services (with their date), jobs with their status, relations stated by other
        // pages, suggestions as suggestions; each under the page it is read from
        if (n < room) {
            final BusinessView bv = new BusinessView(this.reader);
            int added = 0;
            for (final Object[] b : business(c, bv, ent, v, now)) {
                if (n >= room || added >= MAX_BUSINESS_FACTS) {
                    break;
                }
                final Fact f = (Fact) b[0];
                final Long stmt = (Long) b[1];
                Entry e;
                if (stmt != null) {
                    final long doc = bestDoc(c, stmt, docIds, v, now, docs);
                    if (doc == 0L) {
                        continue;
                    }
                    final String[] d = docs.get(doc);
                    e = byDoc.computeIfAbsent(doc, k -> new Entry(id, name, type, d[0], d[1], d[2]));
                } else {
                    e = byDoc.isEmpty() ? null : byDoc.values().iterator().next(); // a suggestion has no page of its own
                    if (e == null) {
                        continue;
                    }
                }
                e.facts.add(f);
                n++;
                added++;
            }
        }
        final List<Entry> out = new ArrayList<>();
        for (final Entry e : byDoc.values()) {
            if (!e.facts.isEmpty()) {
                out.add(e);
            }
        }
        return out;
    }

    /** A code or JSON value in words: category labels, NACE titles, a price or an area as the page states it. */
    static String readable(final String predicate, final String value) {
        if (value == null) {
            return null;
        }
        final net.yacy.scoutro.knowledge.vocab.KgVocabularies.Snapshot vocab = net.yacy.scoutro.knowledge.vocab.KgVocabularies.get();
        final Vocabulary.Predicate p = Vocabulary.predicate(predicate);
        if (Vocabulary.INDUSTRY.equals(predicate) || Vocabulary.TARGET_INDUSTRY.equals(predicate)) {
            final String label = vocab.nace.label(value);
            return (label == null ? "" : label + " ") + "(NACE " + value + ")";
        }
        if (Vocabulary.AUDIENCE_SEGMENT.equals(predicate)) {
            final net.yacy.scoutro.knowledge.vocab.Categories.Entry e = vocab.categories.segment(value);
            return e == null ? value : e.de + " / " + e.en;
        }
        if (p != null && Vocabulary.T_CODE.equals(p.datatype)) {
            final net.yacy.scoutro.knowledge.vocab.Categories.Entry e = vocab.categories.entry(value);
            return e == null ? value : e.de + " / " + e.en;
        }
        if (p != null && Vocabulary.T_JSON.equals(p.datatype)) {
            final org.json.JSONObject o = net.yacy.scoutro.knowledge.extract.Values.json(value);
            if (o == null) {
                return value;
            }
            if (Vocabulary.PRICE.equals(predicate) || Vocabulary.SALARY.equals(predicate)) {
                return money(o);
            }
            if (Vocabulary.SERVICE_AREA.equals(predicate)) {
                return o.optString("kind") + (o.has("name") ? " " + o.optString("name") : "") + (o.has("radius_km") ? " " + o.optString("radius_km")
                        + " km" + (o.has("around") ? " around " + o.optString("around") : "") : "");
            }
            final StringBuilder sb = new StringBuilder();
            for (final String k : new java.util.TreeSet<>(o.keySet())) {
                sb.append(sb.length() == 0 ? "" : ", ").append(k).append(' ').append(o.optString(k));
            }
            return sb.toString();
        }
        return value;
    }

    /** A price or salary as published: kind, amount or range, currency, unit and conditions; never converted. */
    static String money(final org.json.JSONObject o) {
        final StringBuilder sb = new StringBuilder();
        final String kind = o.optString("kind");
        if (o.has("min")) {
            sb.append(o.optString("min")).append('–').append(o.optString("max"));
        } else {
            sb.append("from".equals(kind) ? "from " : "up_to".equals(kind) ? "up to " : "").append(o.optString("amount"));
        }
        sb.append(' ').append(o.optString("currency"));
        final String unit = o.optString("unit", "");
        if (!unit.isEmpty()) {
            sb.append(' ').append("other".equals(unit) ? o.optString("unit_text") : "once".equals(unit) ? "one-time" : "per " + unit);
        }
        if (o.optBoolean("gross")) {
            sb.append(" gross");
        }
        if (o.has("vat")) {
            sb.append("incl".equals(o.optString("vat")) ? ", VAT included" : ", plus VAT");
        }
        if (o.has("care_level")) {
            sb.append(", care level ").append(o.optString("care_level"));
        }
        if (o.optBoolean("own_share")) {
            sb.append(", own share");
        }
        if (o.has("conditions")) {
            sb.append(", conditions: ").append(o.optString("conditions"));
        }
        if (o.has("valid_through")) {
            sb.append(", valid through ").append(o.optString("valid_through"));
        }
        return sb.toString();
    }

    /** The business facts of an entity: {Fact, statement row for its page (null for a suggestion)}. */
    private List<Object[]> business(final Connection c, final BusinessView bv, final long ent, final Viewer v, final long now) throws SQLException {
        final List<Object[]> out = new ArrayList<>();
        final org.json.JSONObject view = bv.view(c, ent, v, now, false);
        // prices with the service and the date (published, sourced, dated; an expired price is left out)
        final org.json.JSONArray prices = view.optJSONArray("prices");
        for (int i = 0; prices != null && i < prices.length(); i++) {
            final org.json.JSONObject p = prices.optJSONObject(i);
            final String status = p.optString("status");
            if ("expired".equals(status) || "stale".equals(status)) {
                continue;
            }
            final Long row = row(c, p.optString("statement"));
            if (row == null) {
                continue;
            }
            final String service = p.optString("service_name", "");
            final String value = (service.isEmpty() ? "" : service + ": ") + money(p.optJSONObject("value")) + "; as of " + p.optString("as_of")
                    + ("stated".equals(p.optString("as_of_basis")) ? " (stated on the page)" : " (last seen on the page)");
            out.add(new Object[] {new Fact(p.optString("statement"), Vocabulary.PRICE, value, "conflicting".equals(status) ? "supported"
                    : "current".equals(status) ? "supported" : "uncertain", false, false, "conflicting".equals(status) ? "conflicting" : null), row});
        }
        // jobs with their status (an ended job only within its visible days; the view hides the rest)
        final org.json.JSONObject jobs = view.optJSONObject("jobs");
        final org.json.JSONArray items = jobs == null ? null : jobs.optJSONArray("items");
        for (int i = 0; items != null && i < items.length() && i < 3; i++) {
            final org.json.JSONObject j = items.optJSONObject(i);
            final Long row = nameRow(c, j.optString("id"));
            if (row == null) {
                continue;
            }
            final StringBuilder sb = new StringBuilder(j.optString("title"));
            sb.append(" — ").append("open".equals(j.optString("status")) ? "open" : "ended " + j.optString("ended_at", "").replaceFirst("T.*", ""));
            if (j.has("valid_through")) {
                sb.append(", apply until ").append(j.optString("valid_through"));
            }
            final org.json.JSONArray types = j.optJSONArray("employment_type");
            for (int k = 0; types != null && k < types.length(); k++) {
                sb.append(k == 0 ? ", " : "/").append(types.optJSONObject(k).optString("label_en", types.optJSONObject(k).optString("value")));
            }
            final org.json.JSONArray salary = j.optJSONArray("salary");
            if (salary != null && salary.length() > 0) {
                sb.append(", published salary ").append(money(salary.optJSONObject(0).optJSONObject("value")))
                        .append(" (as of ").append(salary.optJSONObject(0).optString("as_of", "").replaceFirst("T.*", "")).append(')');
            }
            out.add(new Object[] {new Fact(j.optString("id"), "job", sb.toString(), "supported", false, false,
                    "open".equals(j.optString("status")) ? null : "ended"), row});
        }
        // relations other pages state about this entity (a customer, a member, a subsidiary), never linked_to
        final org.json.JSONObject rel = view.optJSONObject("relations");
        final org.json.JSONArray facts = rel == null ? null : rel.optJSONArray("facts");
        for (int i = 0; facts != null && i < facts.length(); i++) {
            final org.json.JSONObject r = facts.optJSONObject(i);
            if (!"in".equals(r.optString("direction")) || !r.optBoolean("business") || "stale".equals(r.optString("status"))) {
                continue;
            }
            final Long row = row(c, r.optString("statement"));
            if (row != null) {
                out.add(new Object[] {new Fact(r.optString("statement"), "inverse:" + r.optString("predicate"),
                        r.optJSONObject("other").optString("name"), "current".equals(r.optString("status")) ? "supported" : "uncertain", false,
                        false), row});
            }
        }
        // suggestions: computed by Scoutro from both sides' facts, never a fact
        final org.json.JSONObject matches = view.optJSONObject("suggested_matches");
        for (final String k : new String[] {"suggested_customer", "suggested_partner"}) {
            final org.json.JSONArray list = matches == null ? null : matches.optJSONArray(k);
            for (int i = 0; list != null && i < list.length() && i < 2; i++) {
                final org.json.JSONObject m = list.optJSONObject(i);
                out.add(new Object[] {new Fact(m.optString("id"), k, m.optJSONObject("other").optString("name") + " (confidence "
                        + m.optDouble("confidence") + "; " + m.optString("reason") + ")", "uncertain", false, false, "suggestion"), null});
            }
        }
        return out;
    }

    private static Long row(final Connection c, final String statement) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT stmt_rowid FROM kg_statement WHERE public_id = ?")) {
            ps.setString(1, statement);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** A statement of the job (its name) whose page stands for the job. */
    private static Long nameRow(final Connection c, final String job) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT s.stmt_rowid FROM kg_statement s JOIN kg_entity e ON e.ent_rowid = s.subj"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE e.public_id = ? AND v.name = 'name' ORDER BY s.quality LIMIT 1")) {
            ps.setString(1, job);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
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
