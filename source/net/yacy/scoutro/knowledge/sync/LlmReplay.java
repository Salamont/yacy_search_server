/*
 *  LlmReplay
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.resolve.Normalizers;

/**
 * 0.13.0: the replay of the LLM tier's stored answers (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 40). A new {@link LlmExtractor#VERSION}
 * whose change is the validation only ({@link LlmExtractor#REPLAYS}) asks the model nothing: the answer a page's tier-3
 * evidence was published from is rebuilt from that evidence (each entity with its type, name and quote, each relation and value
 * with its quote and position), {@link LlmExtractor#apply} validates it with the page's text as Solr holds it, as it validates
 * every answer, and {@link net.yacy.scoutro.knowledge.publish.Publisher#applyLlmReplay} publishes the result under the new
 * version with the model and the prompt hash of the evidence. The cache is not needed: its keys contain the known entities,
 * which a new rule version changes, and its entries may be evicted.
 * <p>
 * What {@link LlmExtractor#apply} derives itself is never rebuilt: the evidence by a page's title ({@code title:}), the
 * industries of the services ({@code derived:}) and the marks of unconfirmed types; apply derives them again. A quote that was
 * clipped or had a person's name redacted for the evidence is read back from the page's text at its position, if the text there
 * agrees with the excerpt; else the excerpt is the quote (apply then refuses what it cannot ground). A relation or value about an
 * entity that is neither an entity of the answer nor a known entity of the page any more is dropped and counted.
 */
public final class LlmReplay {

    /** Pages read per query of the walk. */
    static final int BATCH = 20;

    private static final Pattern TEXT_LOCATOR = Pattern.compile("^text:(\\d{1,9})\\+(\\d{1,4})$");

    private LlmReplay() {
    }

    /** The extractor rows of the replayed versions, for SQL ("-1" if there are none). */
    static String extractors(final Connection c) throws SQLException {
        final StringBuilder in = new StringBuilder();
        final StringBuilder versions = new StringBuilder();
        for (final String v : LlmExtractor.REPLAYS) {
            versions.append(versions.length() == 0 ? "" : ",").append('\'').append(v.replace("'", "")).append('\'');
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT ext_id FROM kg_extractor WHERE tier = " + LlmExtractor.TIER + " AND name = ?"
                + " AND version IN (" + (versions.length() == 0 ? "''" : versions) + ") ORDER BY ext_id")) {
            ps.setString(1, LlmExtractor.NAME);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    in.append(in.length() == 0 ? "" : ",").append(rs.getInt(1));
                }
            }
        }
        return in.length() == 0 ? "-1" : in.toString();
    }

    /**
     * The next pages to replay after {@code after} (doc rowid), in row order: active, marked (a page to do is read by the tier,
     * which replaces its evidence anyway) and with tier-3 evidence of a replayed version. Each row is {doc_rowid, doc_id}.
     */
    static List<String[]> todo(final Connection c, final long after, final int limit) throws SQLException {
        final List<String[]> out = new ArrayList<>();
        final String ext = extractors(c);
        if ("-1".equals(ext)) {
            return out;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT d.doc_rowid, d.doc_id FROM kg_doc d WHERE d.doc_rowid > ? AND d.state = 1"
                + " AND d.llm_status IS NOT NULL AND EXISTS (SELECT 1 FROM kg_evidence e WHERE e.doc_rowid = d.doc_rowid AND e.tier = "
                + LlmExtractor.TIER + " AND e.ext_id IN (" + ext + ")) ORDER BY d.doc_rowid LIMIT ?")) {
            ps.setLong(1, after);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[] {Long.toString(rs.getLong(1)), rs.getString(2)});
                }
            }
        }
        return out;
    }

    /** Pages with tier-3 evidence of a replayed version, any state (the status: what is left). */
    static long pending(final Connection c) throws SQLException {
        final String ext = extractors(c);
        if ("-1".equals(ext)) {
            return 0L;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT count(DISTINCT doc_rowid) FROM kg_evidence WHERE ext_id IN (" + ext + ")");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** A page's stored answer, rebuilt: the accepted form {@link LlmExtractor#apply} takes, and whom it came from. */
    static final class Answer {
        final JSONObject accepted = new JSONObject();
        /** Model and prompt hash of the evidence (the most frequent pair, if a page has two). */
        String model = "";
        String promptHash = "";
        /** Facilities and sites of the answer (entities of the answer, not known ones). */
        int places;
        /** Relations and values about an entity that is no longer one of the page. */
        int unmapped;
        /** Quotes read back from the page's text because the excerpt was clipped or redacted. */
        int restored;
    }

    private static final class Row {
        long subj;
        String subjType;
        String subkind;
        String predicate;
        Long objEnt;
        String objVal;
        boolean hedged;
        String locator;
        String excerpt;
    }

    /**
     * The answer the page's tier-3 evidence was published from.
     *
     * @param raw   the page's text as Solr holds it (the positions of the evidence are positions in it)
     * @param known the page's known entities, as the tier asks with them now ({@link LlmReading#known})
     */
    static Answer rebuild(final Connection c, final long docRowid, final String raw, final List<LlmExtractor.Known> known) throws SQLException {
        final Answer a = new Answer();
        final List<Row> rows = new ArrayList<>();
        final Map<String, Integer> origins = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT s.subj, st.name, se.subkind, p.name, s.obj_ent, s.obj_val, e.certainty, e.locator,"
                + " e.excerpt, x.model, x.prompt_hash FROM kg_evidence e JOIN kg_extractor x ON x.ext_id = e.ext_id"
                + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab p ON p.term_id = s.pred"
                + " JOIN kg_entity se ON se.ent_rowid = s.subj JOIN kg_vocab st ON st.term_id = se.type"
                + " WHERE e.doc_rowid = ? AND e.tier = " + LlmExtractor.TIER + " ORDER BY s.stmt_rowid")) {
            ps.setLong(1, docRowid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final Row r = new Row();
                    r.subj = rs.getLong(1);
                    r.subjType = rs.getString(2);
                    r.subkind = rs.getString(3);
                    r.predicate = rs.getString(4);
                    r.objEnt = rs.getObject(5) == null ? null : rs.getLong(5);
                    r.objVal = rs.getString(6);
                    r.hedged = rs.getInt(7) == 2;
                    r.locator = rs.getString(8);
                    r.excerpt = rs.getString(9);
                    rows.add(r);
                    origins.merge(rs.getString(10) + "\u0000" + rs.getString(11), 1, Integer::sum);
                }
            }
        }
        int most = 0;
        for (final Map.Entry<String, Integer> o : origins.entrySet()) {
            if (o.getValue() > most) {
                most = o.getValue();
                final int split = o.getKey().indexOf('\u0000');
                a.model = o.getKey().substring(0, split);
                a.promptHash = o.getKey().substring(split + 1);
            }
        }
        final JSONArray entities = new JSONArray();
        final JSONArray claims = new JSONArray();
        final JSONArray values = new JSONArray();
        // the entities of the answer: every name the tier stated on the page; the first one's id is the entity's
        final Map<Long, String> ids = new HashMap<>();
        for (final Row r : rows) {
            if (!Vocabulary.NAME.equals(r.predicate) || r.objVal == null) {
                continue;
            }
            final String id = "e" + (entities.length() + 1);
            ids.putIfAbsent(r.subj, id);
            final int[] at = position(r.locator);
            entities.put(KgJson.obj("id", id, "type", r.subjType, "name", r.objVal, "kind",
                    Vocabulary.FACILITY.equals(r.subjType) ? r.subkind : null, "quote", quote(raw, at, r.excerpt, a), "at", at == null ? 0 : at[0]));
            if (Vocabulary.FACILITY.equals(r.subjType) || Vocabulary.SITE.equals(r.subjType)) {
                a.places++;
            }
        }
        final Map<Long, String> knownIds = new HashMap<>();
        final Set<String> seenValues = new HashSet<>();
        for (final Row r : rows) {
            if (Vocabulary.NAME.equals(r.predicate) || Vocabulary.TYPE_UNCONFIRMED.equals(r.predicate)) {
                continue;
            }
            final int[] at = position(r.locator);
            if (at == null) {
                continue; // a title's or a derivation's evidence: apply derives it again
            }
            final String subject = id(c, r.subj, r.subjType, ids, knownIds, known);
            if (r.objEnt != null) {
                final String object = id(c, r.objEnt, null, ids, knownIds, known);
                if (subject == null || object == null) {
                    a.unmapped++;
                    continue;
                }
                claims.put(KgJson.obj("subject", subject, "predicate", r.predicate, "object", object, "hedged", r.hedged, "quote",
                        quote(raw, at, r.excerpt, a), "at", at[0]));
            } else {
                // a price the quote shows as another amount is published as monetary_amount; the model pointed at a price
                final String predicate = Vocabulary.MONETARY_AMOUNT.equals(r.predicate) ? Vocabulary.PRICE : r.predicate;
                if (!LlmExtractor.valuePredicate(predicate)) {
                    continue;
                }
                if (subject == null) {
                    a.unmapped++;
                    continue;
                }
                final String quote = quote(raw, at, r.excerpt, a);
                // one value item of the answer may state several values: it is rebuilt once
                if (seenValues.add(subject + "\u0000" + predicate + "\u0000" + at[0] + "\u0000" + quote)) {
                    values.put(KgJson.obj("subject", subject, "predicate", predicate, "quote", quote, "at", at[0]));
                }
            }
        }
        KgJson.put(a.accepted, "entities", entities);
        KgJson.put(a.accepted, "claims", claims);
        KgJson.put(a.accepted, "values", values);
        return a;
    }

    /**
     * The answer's id of an entity: its own (an entity of the answer), else that of the known entity of the page with its type
     * and one of its names; null if it is neither.
     */
    private static String id(final Connection c, final long ent, final String type, final Map<Long, String> ids, final Map<Long, String> knownIds,
            final List<LlmExtractor.Known> known) throws SQLException {
        final String own = ids.get(ent);
        if (own != null) {
            return own;
        }
        if (knownIds.containsKey(ent)) {
            return knownIds.get(ent);
        }
        String t = type;
        final Set<String> names = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT t.name, s.obj_val FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type"
                + " LEFT JOIN kg_statement s ON s.subj = e.ent_rowid AND s.pred = (SELECT term_id FROM kg_vocab WHERE kind = 2 AND name = ?)"
                + " WHERE e.ent_rowid = ?")) {
            ps.setString(1, Vocabulary.NAME);
            ps.setLong(2, ent);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    t = rs.getString(1);
                    final String k = Normalizers.key(rs.getString(2));
                    if (k != null) {
                        names.add(k);
                    }
                }
            }
        }
        String found = null;
        for (final LlmExtractor.Known k : known) {
            if (k.mention.type.equals(t) && k.mention.name != null && names.contains(Normalizers.key(k.mention.name))) {
                found = k.id;
                break;
            }
        }
        knownIds.put(ent, found);
        return found;
    }

    /** {at, length} of a locator {@code text:<at>+<length>}; null for any other (a title's, a derivation's). */
    static int[] position(final String locator) {
        final Matcher m = locator == null ? null : TEXT_LOCATOR.matcher(locator);
        return m == null || !m.matches() ? null : new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    /**
     * The quote of an item: the page's text at its position, if it agrees with the excerpt (the excerpt may be clipped, "…", or
     * have a person's name redacted, "[…]"); else the excerpt.
     */
    static String quote(final String raw, final int[] at, final String excerpt, final Answer a) {
        final String stored = excerpt == null ? "" : excerpt;
        if (raw == null || at == null || at[0] < 0 || at[0] >= raw.length() || at[1] <= 0) {
            return stored;
        }
        final String seen = Normalizers.text(raw.substring(at[0], Math.min(raw.length(), at[0] + 4 * at[1] + 64)));
        if (seen == null) {
            return stored;
        }
        final String candidate = seen.substring(0, Math.min(at[1], seen.length())).trim();
        if (candidate.equals(stored)) {
            return stored;
        }
        if (!agrees(stored, candidate)) {
            return stored;
        }
        if (a != null) {
            a.restored++;
        }
        return candidate;
    }

    /** True if the excerpt is the text with parts redacted ("[…]") or its end clipped ("…"): its parts, in order, from the start. */
    static boolean agrees(final String excerpt, final String text) {
        String e = excerpt.trim();
        if (e.endsWith("\u2026") && !e.endsWith("[\u2026]")) {
            e = e.substring(0, e.length() - 1).trim();
        }
        if (e.isEmpty()) {
            return false;
        }
        int from = 0;
        boolean first = true;
        for (final String part : e.split("\\[\u2026\\]", -1)) {
            final String p = part.trim();
            if (p.isEmpty()) {
                first = false;
                continue;
            }
            final int i = text.indexOf(p, from);
            if (i < 0 || first && i != 0) {
                return false;
            }
            from = i + p.length();
            first = false;
        }
        return true;
    }
}
