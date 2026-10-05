/*
 *  KgReader
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

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.document.id.DigestURL;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The read projection {@code scoutro.kg.v1} (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 4.5 and 8.1): entities, statements, evidence, hosts and sources, for one
 * viewer.
 * <p>
 * Visibility is computed from evidence: a piece of evidence is visible if its
 * document is in one of the viewer's collections, a statement if it has
 * visible evidence, an entity if it has a visible statement. Names, aliases,
 * identifiers, quality, {@code last_confirmed}, counts, hosts and the
 * collections of a source are computed <em>only</em> over visible evidence,
 * so a filtered view never reveals anything of another collection; an
 * invisible object is {@code not_found}, exactly like a missing one. The
 * administrator without a filter is {@link Viewer#ALL}. Every call is one or
 * a few bounded read leases; nothing is written.
 */
public final class KgReader {

    public static final String SCHEMA = "scoutro.kg.v1";
    public static final int MAX_LIMIT = 100;
    public static final int MAX_EVIDENCE_LIMIT = 50;
    public static final int MAX_OFFSET = 10_000;
    /** Statements read for one entity summary (names, quality, counts). */
    static final int SUMMARY_STATEMENTS = 2000;

    public static final Pattern ENTITY_ID = Pattern.compile("^kge_[a-z2-7]{20}$");
    public static final Pattern STATEMENT_ID = Pattern.compile("^kgs_[a-z2-7]{20}$");
    public static final Pattern DOC_ID = Pattern.compile("^[A-Za-z0-9_-]{12}$");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    static final String[] QUALITY = {null, "supported", "uncertain", "conflicting", "stale"};
    static final String[] STATE = {null, "active", "unavailable", "gone", "expired"};
    static final String[] KIND = {null, "jsonld", "metadata", "rule", "llm"};

    private final KgStore store;
    private final KgConfig cfg;
    private final LongSupplier clock;
    /** False on the agent routes: the extractor of a piece of evidence without the model name and prompt hash. */
    private final boolean modelDetail;

    public KgReader(final KgStore store, final KgConfig cfg, final LongSupplier clock) {
        this(store, cfg, clock, true);
    }

    private KgReader(final KgStore store, final KgConfig cfg, final LongSupplier clock, final boolean modelDetail) {
        this.store = store;
        this.cfg = cfg;
        this.clock = clock;
        this.modelDetail = modelDetail;
    }

    /**
     * The reader for agents: the same projection, but evidence names only the
     * extractor and its version ({@code llm/1}), not the configured model.
     */
    public KgReader forAgents() {
        return new KgReader(this.store, this.cfg, this.clock, false);
    }

    KgStore store() {
        return this.store;
    }

    long now() {
        return this.clock.getAsLong();
    }

    /** Thrown for an unknown or invisible object (404 {@code not_found}). */
    public static final class NotFound extends Exception {
        private static final long serialVersionUID = 1L;

        public NotFound(final String what) {
            super(what);
        }
    }

    /** Query of the entity list. */
    public static final class EntityQuery {
        public String q;
        public String type;
        public String host;
        public String quality;
        public int offset;
        public int limit = 25;
    }

    // --------------------------------------------------------------- viewer

    /** The viewer for collection names; null names = {@link Viewer#ALL}; unknown names see nothing. */
    public Viewer viewer(final Collection<String> collections) throws KgException {
        if (collections == null) {
            return Viewer.ALL;
        }
        return this.store.read(c -> {
            final Set<Integer> ids = new TreeSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT coll_id FROM kg_collection WHERE name = ?")) {
                for (final String name : collections) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            ids.add(rs.getInt(1));
                        }
                    }
                }
            }
            return Viewer.of(ids);
        });
    }

    /** SQL condition: document {@code alias} is in the viewer's collections ("1" for all). */
    static String visibleDoc(final Viewer v, final String alias) {
        if (v.all()) {
            return "1";
        }
        return "EXISTS (SELECT 1 FROM kg_doc_collection vdc WHERE vdc.doc_rowid = " + alias + ".doc_rowid AND vdc.coll_id IN ("
                + ids(v) + "))";
    }

    private static String ids(final Viewer v) {
        final StringBuilder sb = new StringBuilder();
        for (final Integer i : v.collections()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(i.intValue());
        }
        return sb.length() == 0 ? "-1" : sb.toString();
    }

    /** SQL condition: statement {@code alias} has visible evidence. */
    static String visibleStatement(final Viewer v, final String alias) {
        if (v.all()) {
            return "1";
        }
        return "EXISTS (SELECT 1 FROM kg_statement_scope vss WHERE vss.stmt_rowid = " + alias + ".stmt_rowid AND vss.coll_id IN ("
                + ids(v) + "))";
    }

    /** SQL condition: entity {@code alias} is visible. */
    static String visibleEntity(final Viewer v, final String alias) {
        if (v.all()) {
            return alias + ".status = 1";
        }
        return alias + ".status = 1 AND EXISTS (SELECT 1 FROM kg_entity_scope ves WHERE ves.ent_rowid = " + alias
                + ".ent_rowid AND ves.coll_id IN (" + ids(v) + "))";
    }

    /** SQL condition: document {@code d} is current (4.4): active, or unavailable within the grace period, and not too old. */
    String currentDoc(final String d, final long now) {
        return "((" + d + ".state = 1 OR (" + d + ".state = 2 AND " + d + ".state_since >= " + (now - this.cfg.unavailableGraceMillis)
                + ")) AND (" + d + ".loaded_at IS NULL OR " + d + ".loaded_at >= " + (now - this.cfg.maxAgeMillis) + "))";
    }

    boolean current(final int state, final long stateSince, final Long loadedAt, final long now) {
        final boolean live = state == 1 || (state == 2 && now - stateSince <= this.cfg.unavailableGraceMillis);
        return live && (loadedAt == null || now - loadedAt <= this.cfg.maxAgeMillis);
    }

    // ---------------------------------------------------------- statistics

    /** What one viewer sees of one statement. */
    static final class Stat {
        final long rowid;
        final long subj;
        final String predicate;
        final Long objEnt;
        final String objVal;
        final String publicId;
        final long firstSeenStored;
        boolean visible;
        boolean current;
        boolean supported;
        boolean stated;
        final Set<Long> docs = new HashSet<>();
        final Set<String> kinds = new TreeSet<>();
        Long lastConfirmed;
        Long firstObserved;
        String quality;

        Stat(final long rowid, final long subj, final String predicate, final Long objEnt, final String objVal, final String publicId,
                final long firstSeen) {
            this.rowid = rowid;
            this.subj = subj;
            this.predicate = predicate;
            this.objEnt = objEnt;
            this.objVal = objVal;
            this.publicId = publicId;
            this.firstSeenStored = firstSeen;
        }

        int rank() {
            switch (this.quality) {
                case "supported":
                    return 1;
                case "conflicting":
                    return 2;
                case "uncertain":
                    return 3;
                default:
                    return 4;
            }
        }
    }

    static final String STMT_COLUMNS = "s.stmt_rowid, s.subj, v.name, s.obj_ent, s.obj_val, s.public_id, s.first_seen";

    static Stat stat(final ResultSet rs) throws SQLException {
        return new Stat(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getObject(4) == null ? null : rs.getLong(4), rs.getString(5),
                rs.getString(6), rs.getLong(7));
    }

    /** Fills the visible evidence aggregates of {@code stats}, then the quality with the conflict rule. */
    void compute(final Connection c, final Collection<Stat> stats, final Viewer v, final long now) throws SQLException {
        if (stats.isEmpty()) {
            return;
        }
        final Map<Long, Stat> byRow = new HashMap<>();
        for (final Stat s : stats) {
            byRow.put(s.rowid, s);
        }
        final List<Long> rows = new ArrayList<>(byRow.keySet());
        for (int from = 0; from < rows.size(); from += 500) {
            final List<Long> part = rows.subList(from, Math.min(rows.size(), from + 500));
            final StringBuilder in = new StringBuilder();
            for (final Long r : part) {
                in.append(in.length() == 0 ? "" : ",").append(r.longValue());
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT e.stmt_rowid, e.certainty, e.tier, e.kind, d.doc_rowid, d.state,"
                    + " d.state_since, d.loaded_at, e.observed_at FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
                    + " WHERE e.stmt_rowid IN (" + in + ") AND " + visibleDoc(v, "d"));
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final Stat s = byRow.get(rs.getLong(1));
                    s.visible = true;
                    final int kind = rs.getInt(4);
                    if (kind >= 1 && kind <= 4) {
                        s.kinds.add(KIND[kind]);
                    }
                    final long observed = rs.getLong(9);
                    s.firstObserved = s.firstObserved == null ? observed : Math.min(s.firstObserved, observed);
                    final int state = rs.getInt(6);
                    final Long loaded = rs.getObject(8) == null ? null : rs.getLong(8);
                    if (!current(state, rs.getLong(7), loaded, now)) {
                        continue;
                    }
                    s.current = true;
                    s.docs.add(rs.getLong(5));
                    if (rs.getInt(2) == 1) {
                        s.stated = true;
                        if (state == 1 && rs.getInt(3) != 3) {
                            s.supported = true;
                        }
                    }
                    if (loaded != null && (s.lastConfirmed == null || loaded > s.lastConfirmed)) {
                        s.lastConfirmed = loaded;
                    }
                }
            }
        }
        for (final Stat s : stats) {
            s.quality = !s.current ? "stale" : s.supported ? "supported" : "uncertain";
        }
        // the conflict rule over the viewer's statements: a functional predicate with more than one supported object
        final Map<String, List<Stat>> groups = new LinkedHashMap<>();
        for (final Stat s : stats) {
            final Vocabulary.Predicate p = Vocabulary.predicate(s.predicate);
            if (p != null && p.functional && "supported".equals(s.quality)) {
                groups.computeIfAbsent(s.subj + ":" + s.predicate, k -> new ArrayList<>()).add(s);
            }
        }
        for (final Map.Entry<String, List<Stat>> g : groups.entrySet()) {
            final Stat first = g.getValue().get(0);
            int supported = 0;
            final List<Stat> siblings = siblings(c, first.subj, first.predicate, v);
            compute0(c, siblings, v, now);
            for (final Stat s : siblings) {
                if (s.supported && s.current) {
                    supported++;
                }
            }
            if (supported > 1) {
                for (final Stat s : g.getValue()) {
                    s.quality = "conflicting";
                }
            }
        }
    }

    /** {@link #compute} without the conflict rule (for siblings). */
    private void compute0(final Connection c, final List<Stat> stats, final Viewer v, final long now) throws SQLException {
        final Map<Long, Stat> byRow = new HashMap<>();
        for (final Stat s : stats) {
            byRow.put(s.rowid, s);
        }
        if (byRow.isEmpty()) {
            return;
        }
        final StringBuilder in = new StringBuilder();
        for (final Long r : byRow.keySet()) {
            in.append(in.length() == 0 ? "" : ",").append(r.longValue());
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT e.stmt_rowid, e.certainty, e.tier, d.state, d.state_since, d.loaded_at"
                + " FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE e.stmt_rowid IN (" + in + ") AND "
                + visibleDoc(v, "d")); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final Stat s = byRow.get(rs.getLong(1));
                final int state = rs.getInt(4);
                if (current(state, rs.getLong(5), rs.getObject(6) == null ? null : rs.getLong(6), now)) {
                    s.current = true;
                    if (rs.getInt(2) == 1 && state == 1 && rs.getInt(3) != 3) {
                        s.supported = true;
                    }
                }
            }
        }
    }

    private static List<Stat> siblings(final Connection c, final long subj, final String predicate, final Viewer v) throws SQLException {
        final List<Stat> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + STMT_COLUMNS + " FROM kg_statement s JOIN kg_vocab v"
                + " ON v.term_id = s.pred WHERE s.subj = ? AND v.kind = 2 AND v.name = ? AND " + visibleStatement(v, "s") + " LIMIT 50")) {
            ps.setLong(1, subj);
            ps.setString(2, predicate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(stat(rs));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------- entities

    /** One page of visible entities, newest first, with the summary of each. */
    public JSONObject entities(final EntityQuery q, final Viewer v) throws KgException {
        final long now = this.clock.getAsLong();
        return this.store.read(c -> {
            final StringBuilder where = new StringBuilder(visibleEntity(v, "e"));
            final List<Object> args = new ArrayList<>();
            if (q.type != null) {
                where.append(" AND e.type = (SELECT term_id FROM kg_vocab WHERE kind = 1 AND name = ?)");
                args.add(q.type);
            }
            if (q.host != null) {
                final List<String> hostIds = hostIds(q.host);
                where.append(" AND e.ent_rowid IN (SELECT s.subj FROM kg_doc d JOIN kg_evidence ev ON ev.doc_rowid = d.doc_rowid"
                        + " JOIN kg_statement s ON s.stmt_rowid = ev.stmt_rowid WHERE d.host_id IN (")
                        .append(placeholders(hostIds.size())).append(") AND ").append(visibleDoc(v, "d")).append(")");
                args.addAll(hostIds);
            }
            if (q.q != null) {
                final String match = ftsQuery(q.q);
                if (match == null) {
                    return page(q.offset, q.limit, 0L, new JSONArray(), c, v);
                }
                where.append(" AND e.ent_rowid IN (SELECT s.subj FROM kg_name_fts f JOIN kg_statement s ON s.stmt_rowid = f.rowid"
                        + " WHERE kg_name_fts MATCH ? AND ").append(visibleStatement(v, "s")).append(")");
                args.add(match);
            }
            if (q.quality != null) {
                where.append(" AND ").append(entityQuality(q.quality, v, now));
            }
            final long total;
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM kg_entity e WHERE " + where)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0L;
                }
            }
            final List<long[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT e.ent_rowid FROM kg_entity e WHERE " + where
                    + " ORDER BY e.created_seq DESC, e.ent_rowid DESC LIMIT ? OFFSET ?")) {
                final List<Object> a = new ArrayList<>(args);
                a.add(q.limit);
                a.add(q.offset);
                bind(ps, a);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new long[] {rs.getLong(1)});
                    }
                }
            }
            final JSONArray items = new JSONArray();
            for (final long[] r : rows) {
                items.put(summary(c, r[0], v, now, false));
            }
            return page(q.offset, q.limit, total, items, c, v);
        });
    }

    /** Entity filter by viewer quality: supported (or conflicting), uncertain, or stale. */
    private String entityQuality(final String quality, final Viewer v, final long now) {
        final String evidence = "SELECT 1 FROM kg_statement qs JOIN kg_evidence qe ON qe.stmt_rowid = qs.stmt_rowid"
                + " JOIN kg_doc qd ON qd.doc_rowid = qe.doc_rowid WHERE qs.subj = e.ent_rowid AND " + visibleDoc(v, "qd") + " AND "
                + currentDoc("qd", now);
        final String supported = "EXISTS (" + evidence + " AND qe.certainty = 1 AND qd.state = 1 AND qe.tier <> 3)";
        final String current = "EXISTS (" + evidence + ")";
        switch (quality) {
            case "supported":
                return supported;
            case "uncertain":
                return current + " AND NOT " + supported;
            default:
                return "NOT " + current;
        }
    }

    /** The entity (or a redirect to the visible survivor of a merge). */
    public JSONObject entity(final String id, final Viewer v) throws KgException, NotFound {
        final long now = this.clock.getAsLong();
        final Object r = this.store.read(c -> {
            final long[] ent = entityRow(c, id, v);
            if (ent == null) {
                return null;
            }
            if (ent[1] != 0L) {
                return KgJson.obj("schema", SCHEMA, "redirect", publicId(c, ent[1]));
            }
            final JSONObject o = summary(c, ent[0], v, now, true);
            KgJson.put(o, "schema", SCHEMA);
            asOf(c, o);
            return o;
        });
        if (r == null) {
            throw new NotFound("entity " + id);
        }
        return (JSONObject) r;
    }

    /**
     * {rowid, redirect target rowid or 0}; null if unknown or invisible. A
     * merged entity or a redirect ID answers with the survivor if that is
     * visible to the viewer.
     */
    static long[] entityRow(final Connection c, final String id, final Viewer v) throws SQLException {
        long rowid = 0L;
        int status = 0;
        long merged = 0L;
        try (PreparedStatement ps = c.prepareStatement("SELECT ent_rowid, status, coalesce(merged_into, 0) FROM kg_entity WHERE public_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    rowid = rs.getLong(1);
                    status = rs.getInt(2);
                    merged = rs.getLong(3);
                }
            }
        }
        long target = 0L;
        if (rowid == 0L) {
            try (PreparedStatement ps = c.prepareStatement("SELECT target_rowid FROM kg_entity_redirect WHERE public_id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    target = rs.getLong(1);
                }
            }
        } else if (status == 2) {
            target = merged;
        }
        if (target != 0L) {
            for (int i = 0; i < 16; i++) {
                try (PreparedStatement ps = c.prepareStatement("SELECT status, coalesce(merged_into, 0) FROM kg_entity WHERE ent_rowid = ?")) {
                    ps.setLong(1, target);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getInt(1) == 1) {
                            break;
                        }
                        target = rs.getLong(2);
                    }
                }
            }
            return entityVisible(c, target, v) ? new long[] {target, target} : null;
        }
        return entityVisible(c, rowid, v) ? new long[] {rowid, 0L} : null;
    }

    private static boolean entityVisible(final Connection c, final long rowid, final Viewer v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kg_entity e WHERE e.ent_rowid = ? AND " + visibleEntity(v, "e"))) {
            ps.setLong(1, rowid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Names, identifiers, quality, dates and counts of one entity over the viewer's evidence. */
    JSONObject summary(final Connection c, final long rowid, final Viewer v, final long now, final boolean detail)
            throws SQLException {
        String publicId = null;
        String type = null;
        String kind = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT e.public_id, t.name, e.subkind FROM kg_entity e JOIN kg_vocab t"
                + " ON t.term_id = e.type WHERE e.ent_rowid = ?")) {
            ps.setLong(1, rowid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    publicId = rs.getString(1);
                    type = rs.getString(2);
                    kind = rs.getString(3);
                }
            }
        }
        final List<Stat> stats = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + STMT_COLUMNS + " FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE s.subj = ? AND " + visibleStatement(v, "s") + " LIMIT " + SUMMARY_STATEMENTS)) {
            ps.setLong(1, rowid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.add(stat(rs));
                }
            }
        }
        compute(c, stats, v, now);
        Stat name = null;
        final Set<String> aliases = new LinkedHashSet<>();
        final JSONArray identifiers = new JSONArray();
        final Set<Long> docs = new HashSet<>();
        Long last = null;
        Long first = null;
        String quality = "stale";
        int count = 0;
        for (final Stat s : stats) {
            if (!s.visible) {
                continue;
            }
            count++;
            docs.addAll(s.docs);
            if (s.lastConfirmed != null && (last == null || s.lastConfirmed > last)) {
                last = s.lastConfirmed;
            }
            final long f = v.all() ? s.firstSeenStored : s.firstObserved == null ? s.firstSeenStored : s.firstObserved;
            first = first == null ? f : Math.min(first, f);
            if (rankOf(s.quality) < rankOf(quality)) {
                quality = s.quality;
            }
            if (Vocabulary.NAME.equals(s.predicate)) {
                if (name == null || better(s, name)) {
                    name = s;
                }
            } else if (Vocabulary.ALIAS.equals(s.predicate) && s.objVal != null) {
                aliases.add(s.objVal);
            } else if (s.predicate.startsWith("identifier:") && s.objVal != null) {
                identifiers.put(KgJson.obj("scheme", s.predicate.substring("identifier:".length()), "value", s.objVal, "quality",
                        s.quality));
            }
        }
        for (final Stat s : stats) {
            if (s.visible && Vocabulary.NAME.equals(s.predicate) && s != name && s.objVal != null && !"stale".equals(s.quality)) {
                aliases.add(s.objVal);
            }
        }
        final JSONObject o = KgJson.obj("id", publicId, "type", type, "kind", kind, "name", name == null ? null : name.objVal);
        if (detail) {
            KgJson.put(o, "aliases", new JSONArray(aliases));
            KgJson.put(o, "identifiers", identifiers);
        }
        KgJson.put(o, "quality", quality);
        KgJson.put(o, "first_seen", iso(first));
        KgJson.put(o, "last_confirmed", iso(last));
        KgJson.put(o, "counts", KgJson.obj("statements", count, "sources", docs.size(), "truncated", stats.size() >= SUMMARY_STATEMENTS));
        if (detail) {
            KgJson.put(o, "hosts", hosts(c, rowid, v));
            KgJson.put(o, "possible_duplicates", duplicates(c, rowid, type, name == null ? null : name.objVal, v));
        }
        return o;
    }

    private static int rankOf(final String q) {
        switch (q) {
            case "supported":
                return 1;
            case "conflicting":
                return 2;
            case "uncertain":
                return 3;
            default:
                return 4;
        }
    }

    /** The better display name: quality, then current sources, then the latest confirmation. */
    private static boolean better(final Stat a, final Stat b) {
        if (a.rank() != b.rank()) {
            return a.rank() < b.rank();
        }
        if (a.docs.size() != b.docs.size()) {
            return a.docs.size() > b.docs.size();
        }
        final long la = a.lastConfirmed == null ? 0L : a.lastConfirmed;
        final long lb = b.lastConfirmed == null ? 0L : b.lastConfirmed;
        return la > lb;
    }

    /** Hosts of the viewer's documents with evidence for the entity (at most 20). */
    private static JSONArray hosts(final Connection c, final long rowid, final Viewer v) throws SQLException {
        final Set<String> out = new TreeSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT d.url FROM kg_statement s JOIN kg_evidence e"
                + " ON e.stmt_rowid = s.stmt_rowid JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE s.subj = ? AND "
                + visibleDoc(v, "d") + " LIMIT 200")) {
            ps.setLong(1, rowid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < 20) {
                    final String h = host(rs.getString(1));
                    if (h != null) {
                        out.add(h);
                    }
                }
            }
        }
        return new JSONArray(out);
    }

    /** Visible entities of the same type with the same visible name (at most 5); never merged automatically. */
    private static JSONArray duplicates(final Connection c, final long rowid, final String type, final String name, final Viewer v)
            throws SQLException {
        final JSONArray out = new JSONArray();
        final String match = name == null ? null : ftsPhrase(name);
        if (match == null) {
            return out;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT e.public_id FROM kg_name_fts f JOIN kg_statement s"
                + " ON s.stmt_rowid = f.rowid JOIN kg_entity e ON e.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = e.type"
                + " WHERE kg_name_fts MATCH ? AND e.ent_rowid <> ? AND t.name = ? AND lower(s.obj_val) = lower(?) AND "
                + visibleStatement(v, "s") + " AND " + visibleEntity(v, "e") + " LIMIT 5")) {
            ps.setString(1, match);
            ps.setLong(2, rowid);
            ps.setString(3, type);
            ps.setString(4, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1));
                }
            }
        }
        return out;
    }

    // ----------------------------------------------------------- statements

    /** Statements of an entity: its own facts and relations ({@code out}) or the relations pointing to it ({@code in}). */
    public JSONObject entityStatements(final String id, final String predicate, final boolean incoming, final boolean includeStale,
            final int offset, final int limit, final Viewer v) throws KgException, NotFound {
        final long now = this.clock.getAsLong();
        final Object r = this.store.read(c -> {
            final long[] ent = entityRow(c, id, v);
            if (ent == null) {
                return null;
            }
            if (ent[1] != 0L) {
                return KgJson.obj("schema", SCHEMA, "redirect", publicId(c, ent[1]));
            }
            final StringBuilder where = new StringBuilder(incoming ? "s.obj_ent = ?" : "s.subj = ?");
            where.append(" AND ").append(visibleStatement(v, "s"));
            final List<Object> args = new ArrayList<>();
            args.add(ent[0]);
            if (predicate != null) {
                where.append(" AND v.name = ?");
                args.add(predicate);
            }
            if (!includeStale) {
                where.append(" AND EXISTS (SELECT 1 FROM kg_evidence ce JOIN kg_doc cd ON cd.doc_rowid = ce.doc_rowid"
                        + " WHERE ce.stmt_rowid = s.stmt_rowid AND ").append(visibleDoc(v, "cd")).append(" AND ")
                        .append(currentDoc("cd", now)).append(")");
            }
            final String from = " FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred WHERE " + where;
            final long total;
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*)" + from)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0L;
                }
            }
            final List<Stat> stats = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + STMT_COLUMNS + from + " ORDER BY v.name, s.stmt_rowid LIMIT ? OFFSET ?")) {
                final List<Object> a = new ArrayList<>(args);
                a.add(limit);
                a.add(offset);
                bind(ps, a);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stats.add(stat(rs));
                    }
                }
            }
            compute(c, stats, v, now);
            final JSONArray items = new JSONArray();
            for (final Stat s : stats) {
                items.put(statementJson(c, s, v));
            }
            final JSONObject page = page(offset, limit, total, items, c, v);
            KgJson.put(page, "entity", id);
            KgJson.put(page, "direction", incoming ? "in" : "out");
            return page;
        });
        if (r == null) {
            throw new NotFound("entity " + id);
        }
        return (JSONObject) r;
    }

    /** One statement. */
    public JSONObject statement(final String id, final Viewer v) throws KgException, NotFound {
        final long now = this.clock.getAsLong();
        final Object r = this.store.read(c -> {
            final Stat s = statementRow(c, id, v);
            if (s == null) {
                return null;
            }
            if (s.publicId == null) {
                return KgJson.obj("schema", SCHEMA, "redirect", statementPublicId(c, s.rowid));
            }
            compute(c, List.of(s), v, now);
            final JSONObject o = statementJson(c, s, v);
            KgJson.put(o, "schema", SCHEMA);
            asOf(c, o);
            return o;
        });
        if (r == null) {
            throw new NotFound("statement " + id);
        }
        return (JSONObject) r;
    }

    /** The statement row for an ID or a redirect ID (then {@code publicId} is null); null if unknown or invisible. */
    static Stat statementRow(final Connection c, final String id, final Viewer v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + STMT_COLUMNS + " FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE s.public_id = ? AND " + visibleStatement(v, "s"))) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return stat(rs);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT s.stmt_rowid, s.subj, v.name, s.obj_ent, s.obj_val, NULL, s.first_seen"
                + " FROM kg_statement_redirect r JOIN kg_statement s ON s.stmt_rowid = r.target_rowid JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE r.public_id = ? AND " + visibleStatement(v, "s"))) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? stat(rs) : null;
            }
        }
    }

    JSONObject statementJson(final Connection c, final Stat s, final Viewer v) throws SQLException {
        final Vocabulary.Predicate p = Vocabulary.predicate(s.predicate);
        final JSONObject object;
        if (s.objEnt != null) {
            object = KgJson.obj("entity", publicId(c, s.objEnt), "name", visibleName(c, s.objEnt, v));
        } else {
            object = KgJson.obj("value", s.objVal, "datatype", p == null ? Vocabulary.T_STRING : p.datatype);
        }
        return KgJson.obj("id", s.publicId, "subject", publicId(c, s.subj), "subject_name", visibleName(c, s.subj, v),
                "predicate", s.predicate, "object", object, "quality", s.quality, "certainty", s.stated || !s.current ? "stated" : "hedged",
                "kinds", new JSONArray(s.kinds), "first_seen", iso(v.all() ? s.firstSeenStored : s.firstObserved),
                "last_confirmed", iso(s.lastConfirmed), "sources", s.docs.size());
    }

    /** The best name of an entity among the viewer's name statements (stored order; nothing invisible is read). */
    private static String visibleName(final Connection c, final long ent, final Viewer v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT s.obj_val FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE s.subj = ? AND v.kind = 2 AND v.name = 'name' AND " + visibleStatement(v, "s")
                + " ORDER BY s.quality, s.current_sources DESC, s.last_confirmed DESC LIMIT 1")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // -------------------------------------------------------------- evidence

    /** The visible evidence of a statement, newest observation first. */
    public JSONObject evidence(final String id, final int offset, final int limit, final Viewer v) throws KgException, NotFound {
        final Object r = this.store.read(c -> {
            final Stat s = statementRow(c, id, v);
            if (s == null) {
                return null;
            }
            final String from = " FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_extractor x ON x.ext_id = e.ext_id"
                    + " WHERE e.stmt_rowid = ? AND " + visibleDoc(v, "d");
            final long total;
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*)" + from)) {
                ps.setLong(1, s.rowid);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0L;
                }
            }
            final JSONArray items = new JSONArray();
            try (PreparedStatement ps = c.prepareStatement("SELECT d.doc_rowid, d.doc_id, d.url, d.state, d.loaded_at, e.observed_at, e.kind,"
                    + " e.tier, e.certainty, e.locator, e.excerpt, x.name, x.version, x.model, x.prompt_hash" + from
                    + " ORDER BY e.observed_at DESC, d.doc_id LIMIT ? OFFSET ?")) {
                ps.setLong(1, s.rowid);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.put(evidenceJson(c, rs, v));
                    }
                }
            }
            final JSONObject page = page(offset, limit, total, items, c, v);
            KgJson.put(page, "statement", s.publicId != null ? s.publicId : statementPublicId(c, s.rowid));
            return page;
        });
        if (r == null) {
            throw new NotFound("statement " + id);
        }
        return (JSONObject) r;
    }

    /** One evidence row: columns doc_rowid, doc_id, url, state, loaded_at, observed_at, kind, tier, certainty, locator, excerpt, extractor (4). */
    JSONObject evidenceJson(final Connection c, final ResultSet rs, final Viewer v) throws SQLException {
        final int state = rs.getInt(4);
        final int kind = rs.getInt(7);
        final String model = rs.getString(14);
        final String prompt = rs.getString(15);
        String extractor = rs.getString(12) + "/" + rs.getString(13);
        if (this.modelDetail && model != null && !model.isEmpty()) {
            extractor += " (" + model + (prompt == null || prompt.isEmpty() ? "" : ", prompt " + prompt.substring(0, Math.min(8, prompt.length())))
                    + ")";
        }
        final String url = rs.getString(3);
        return KgJson.obj("doc_id", rs.getString(2), "url", url, "host", host(url), "collections", docCollections(c, rs.getLong(1), v),
                "state", state >= 1 && state <= 4 ? STATE[state] : null, "loaded_at", iso(rs.getObject(5) == null ? null : rs.getLong(5)),
                "observed_at", iso(rs.getLong(6)), "kind", kind >= 1 && kind <= 4 ? KIND[kind] : null, "tier", rs.getInt(8),
                "certainty", rs.getInt(9) == 2 ? "hedged" : "stated", "extractor", extractor, "locator", rs.getString(10),
                "excerpt", rs.getString(11));
    }

    /** Collections of a document the viewer may see. */
    private static JSONArray docCollections(final Connection c, final long docRowid, final Viewer v) throws SQLException {
        final JSONArray out = new JSONArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT k.coll_id, k.name FROM kg_doc_collection dc JOIN kg_collection k"
                + " ON k.coll_id = dc.coll_id WHERE dc.doc_rowid = ? ORDER BY k.name")) {
            ps.setLong(1, docRowid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (v.all() || v.collections().contains(rs.getInt(1))) {
                        out.put(rs.getString(2));
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- hosts

    /** Entities with visible evidence from documents of a host (http and https). */
    public JSONObject hostEntities(final String host, final int offset, final int limit, final Viewer v) throws KgException {
        final EntityQuery q = new EntityQuery();
        q.host = host;
        q.offset = offset;
        q.limit = limit;
        final JSONObject page = entities(q, v);
        KgJson.put(page, "host", host);
        return page;
    }

    /** The host IDs of a host name for http and https (YaCy's host hash). */
    static List<String> hostIds(final String host) {
        final List<String> out = new ArrayList<>();
        for (final int port : new int[] {443, 80}) {
            try {
                final String h = DigestURL.hosthash(host, port);
                if (h != null && h.length() == 6 && !out.contains(h)) {
                    out.add(h);
                }
            } catch (final Exception e) {
                // not a usable host name: no ID
            }
        }
        if (out.isEmpty()) {
            out.add("------");
        }
        return out;
    }

    // -------------------------------------------------------------- sources

    /** What the graph holds from one source document: the document and its visible evidence. */
    public JSONObject source(final String docId, final int offset, final int limit, final Viewer v) throws KgException, NotFound {
        final long now = this.clock.getAsLong();
        final Object r = this.store.read(c -> {
            final JSONObject doc;
            final long rowid;
            try (PreparedStatement ps = c.prepareStatement("SELECT d.doc_rowid, d.url, d.state, d.loaded_at, d.processed_at, d.tiers,"
                    + " d.jsonld_bytes, d.jsonld_skipped, d.llm_status, d.llm_reason, d.state_since FROM kg_doc d WHERE d.doc_id = ? AND "
                    + visibleDoc(v, "d"))) {
                ps.setString(1, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    rowid = rs.getLong(1);
                    final int state = rs.getInt(3);
                    final int tiers = rs.getInt(6);
                    final JSONArray ran = new JSONArray();
                    for (int t = 1; t <= 3; t++) {
                        if ((tiers & (1 << (t - 1))) != 0) {
                            ran.put(t);
                        }
                    }
                    final Integer llm = rs.getObject(9) == null ? null : rs.getInt(9);
                    final Long loaded = rs.getObject(4) == null ? null : rs.getLong(4);
                    doc = KgJson.obj("doc_id", docId, "url", rs.getString(2), "host", host(rs.getString(2)),
                            "state", state >= 1 && state <= 4 ? STATE[state] : null,
                            "current", current(state, rs.getLong(11), loaded, now), "loaded_at", iso(loaded),
                            "processed_at", iso(rs.getObject(5) == null ? null : rs.getLong(5)), "tiers", ran,
                            "jsonld_bytes", rs.getLong(7), "jsonld_skipped", rs.getInt(8) == 1,
                            "llm", llm == null ? null : KgJson.obj("status", llm == 1 ? "done" : llm == 2 ? "failed" : "skipped",
                                    "reason", rs.getString(10)));
                }
            }
            KgJson.put(doc, "collections", docCollections(c, rowid, v));
            final long total;
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM kg_evidence WHERE doc_rowid = ?")) {
                ps.setLong(1, rowid);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0L;
                }
            }
            final List<Stat> stats = new ArrayList<>();
            final Map<Long, JSONObject> ev = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + STMT_COLUMNS + ", e.kind, e.tier, e.certainty, e.locator, e.excerpt"
                    + " FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred"
                    + " WHERE e.doc_rowid = ? ORDER BY s.subj, v.name, e.tier LIMIT ? OFFSET ?")) {
                ps.setLong(1, rowid);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final Stat s = stat(rs);
                        stats.add(s);
                        final int kind = rs.getInt(8);
                        ev.put(s.rowid * 4 + rs.getInt(9), KgJson.obj("kind", kind >= 1 && kind <= 4 ? KIND[kind] : null,
                                "tier", rs.getInt(9), "certainty", rs.getInt(10) == 2 ? "hedged" : "stated", "locator", rs.getString(11),
                                "excerpt", rs.getString(12)));
                    }
                }
            }
            final Map<Long, Stat> unique = new LinkedHashMap<>();
            for (final Stat s : stats) {
                unique.putIfAbsent(s.rowid, s);
            }
            compute(c, unique.values(), v, now);
            final JSONArray items = new JSONArray();
            for (final Map.Entry<Long, JSONObject> e : ev.entrySet()) {
                final Stat s = unique.get(e.getKey() / 4);
                final JSONObject item = statementJson(c, s, v);
                KgJson.put(item, "evidence", e.getValue());
                items.put(item);
            }
            final JSONObject page = page(offset, limit, total, items, c, v);
            KgJson.put(page, "source", doc);
            return page;
        });
        if (r == null) {
            throw new NotFound("source " + docId);
        }
        return (JSONObject) r;
    }

    // -------------------------------------------------------------- helpers

    private JSONObject page(final int offset, final int limit, final long total, final JSONArray items, final Connection c, final Viewer v)
            throws SQLException {
        final JSONObject o = KgJson.obj("schema", SCHEMA, "offset", offset, "limit", limit, "total", total, "items", items);
        asOf(c, o);
        return o;
    }

    private void asOf(final Connection c, final JSONObject o) throws SQLException {
        KgJson.put(o, "as_of", KgJson.obj("epoch", this.store.epoch(), "seq", KgStore.queryLong(c, "SELECT coalesce(max(seq), 0) FROM kg_change")));
    }

    static String publicId(final Connection c, final long ent) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT public_id FROM kg_entity WHERE ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String statementPublicId(final Connection c, final long stmt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT public_id FROM kg_statement WHERE stmt_rowid = ?")) {
            ps.setLong(1, stmt);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** An FTS5 query from free text: every token quoted, the last one as a prefix; null without a token. */
    static String ftsQuery(final String text) {
        final List<String> tokens = new ArrayList<>();
        final java.util.regex.Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find() && tokens.size() < 8) {
            tokens.add(m.group());
        }
        if (tokens.isEmpty()) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            sb.append(i == 0 ? "" : " ").append('"').append(tokens.get(i)).append('"');
            if (i == tokens.size() - 1) {
                sb.append('*');
            }
        }
        return sb.toString();
    }

    /** An FTS5 phrase query for a whole name. */
    static String ftsPhrase(final String name) {
        final List<String> tokens = new ArrayList<>();
        final java.util.regex.Matcher m = TOKEN.matcher(name.toLowerCase(Locale.ROOT));
        while (m.find() && tokens.size() < 16) {
            tokens.add(m.group());
        }
        return tokens.isEmpty() ? null : "\"" + String.join(" ", tokens) + "\"";
    }

    private static String placeholders(final int n) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.toString();
    }

    private static void bind(final PreparedStatement ps, final List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            final Object a = args.get(i);
            if (a instanceof Integer) {
                ps.setInt(i + 1, (Integer) a);
            } else if (a instanceof Long) {
                ps.setLong(i + 1, (Long) a);
            } else {
                ps.setString(i + 1, String.valueOf(a));
            }
        }
    }

    static String host(final String url) {
        if (url == null) {
            return null;
        }
        try {
            return new URI(url).getHost();
        } catch (final Exception e) {
            return null;
        }
    }

    static String iso(final Long millis) {
        return millis == null || millis <= 0L ? null : Instant.ofEpochMilli(millis).toString();
    }
}
