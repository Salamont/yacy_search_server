/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.read;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Values;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/** Read-only projection of existing customer/partner derivations. The origin
 * view selects the origin's contribution, never the candidate permission scope.
 * Other facts, exports and weak/structural derivations keep their strict viewer. */
public final class Suggestions {
    private final KgReader reader;

    public Suggestions(final KgReader reader) {
        this.reader = reader;
    }

    static final class Group {
        final int kind;
        final long other;
        final double score;
        final boolean outgoing;

        Group(final int kind, final long other, final double score, final boolean outgoing) {
            this.kind = kind;
            this.other = other;
            this.score = score;
            this.outgoing = outgoing;
        }
    }

    /** Authorization, endpoint membership and origin filtering precede grouping and pagination. */
    private static String where(final long ent, final Viewer selectedOrigin, final Viewer permitted) {
        final Viewer origin = KgReader.within(selectedOrigin, permitted);
        final String selected = origin.all() ? "1" : "(CASE WHEN d.a_ent = " + ent
                + " THEN d.coll_a ELSE d.coll_b END) IN (" + BusinessView.ids(origin) + ")";
        return "d.kind IN (3,4) AND (d.a_ent = " + ent + " OR d.b_ent = " + ent + ") AND "
                + BusinessView.visibleDerived(permitted, "d") + " AND " + selected
                + " AND a.status = 1 AND b.status = 1"
                + " AND EXISTS (SELECT 1 FROM kg_entity_scope sa WHERE sa.ent_rowid = d.a_ent AND sa.coll_id = d.coll_a)"
                + " AND EXISTS (SELECT 1 FROM kg_entity_scope sb WHERE sb.ent_rowid = d.b_ent AND sb.coll_id = d.coll_b)";
    }

    private static String from(final long ent, final Viewer origin, final Viewer permitted) {
        return " FROM kg_derived d JOIN kg_entity a ON a.ent_rowid = d.a_ent JOIN kg_entity b ON b.ent_rowid = d.b_ent"
                + " JOIN kg_collection ca ON ca.coll_id = d.coll_a JOIN kg_collection cb ON cb.coll_id = d.coll_b WHERE "
                + where(ent, origin, permitted);
    }

    /** Lightweight unique neighbours for graph pagination; no per-row JSON/evidence expansion here. */
    static List<Group> groups(final Connection c, final long ent, final Viewer origin, final Viewer permitted) throws SQLException {
        final List<Group> groups = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT d.kind, CASE WHEN d.a_ent = " + ent
                + " THEN d.b_ent ELSE d.a_ent END AS other, max(d.confidence) AS score, CASE WHEN d.kind = 4 OR d.a_ent = "
                + ent + " THEN 1 ELSE 0 END AS outgoing" + from(ent, origin, permitted)
                + " GROUP BY d.kind, other, outgoing ORDER BY score DESC, other, d.kind, outgoing DESC"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) groups.add(new Group(rs.getInt(1), rs.getLong(2), rs.getDouble(3), rs.getInt(4) != 0));
        }
        return groups;
    }

    public JSONObject page(final String id, final int offset, final int limit, final Viewer selected, final Viewer permitted)
            throws KgException, KgReader.NotFound {
        final Viewer origin = KgReader.within(selected, permitted);
        final Object result = this.reader.store().read(c -> {
            final long[] ent = KgReader.entityRow(c, id, origin);
            if (ent == null) return null;
            if (ent[1] != 0L) return KgJson.obj("schema", BusinessView.SCHEMA, "redirect", KgReader.publicId(c, ent[1]));
            return page(c, ent[0], offset, limit, origin, permitted);
        });
        if (result == null) throw new KgReader.NotFound("entity " + id);
        return (JSONObject) result;
    }

    JSONObject page(final Connection c, final long ent, final int offset, final int limit, final Viewer origin, final Viewer permitted)
            throws SQLException {
        final List<Group> groups = groups(c, ent, origin, permitted);
        final JSONArray items = new JSONArray();
        final int end = Math.min(groups.size(), offset + limit);
        for (int i = Math.min(offset, groups.size()); i < end; i++) items.put(item(c, ent, groups.get(i), origin, permitted));
        return KgJson.obj("schema", BusinessView.SCHEMA, "origin", KgReader.publicId(c, ent), "offset", offset, "limit", limit,
                "total", groups.size(), "items", items, "next_offset", end < groups.size() ? end : null,
                "note", "Existing derivations, not facts. Scores are sorting values, not measured probabilities.");
    }

    /** Expand only a shown group. Keep all its authorized collection-pair contributions. */
    JSONObject item(final Connection c, final long ent, final Group group, final Viewer origin, final Viewer permitted) throws SQLException {
        final JSONArray contributions = new JSONArray();
        JSONObject item = null;
        final Set<String> origins = new TreeSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT d.public_id, d.a_ent, d.b_ent, d.coll_a, d.coll_b, d.confidence,"
                + " d.reason, d.basis, d.computed_at, ca.name, cb.name" + from(ent, origin, permitted)
                + " AND d.kind = ? AND (CASE WHEN d.a_ent = ? THEN d.b_ent ELSE d.a_ent END) = ?"
                + (group.kind == 3 ? " AND d.a_ent " + (group.outgoing ? "=" : "<>") + " " + ent : "")
                + " ORDER BY d.confidence DESC, d.public_id")) {
            ps.setInt(1, group.kind);
            ps.setLong(2, ent);
            ps.setLong(3, group.other);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final boolean mine = rs.getLong(2) == ent;
                    final Viewer av = Viewer.of(Set.of(rs.getInt(4))), bv = Viewer.of(Set.of(rs.getInt(5)));
                    final String originName = rs.getString(mine ? 10 : 11), targetName = rs.getString(mine ? 11 : 10);
                    origins.add(originName);
                    final JSONObject basis = Values.json(rs.getString(8));
                    final JSONArray evidence = new JSONArray();
                    final boolean completeA = references(c, basis == null ? null : basis.optJSONArray("a"), av, rs.getString(10), evidence);
                    final boolean completeB = references(c, basis == null ? null : basis.optJSONArray("b"), bv, rs.getString(11), evidence);
                    final String reason = completeA && completeB ? rs.getString(7) : "Supporting statements are no longer fully available.";
                    final JSONObject contribution = KgJson.obj("id", rs.getString(1), "direction", mine ? "out" : "in", "collection_a", rs.getString(10), "collection_b",
                            rs.getString(11), "origin_collection", originName, "target_collection", targetName, "reason", reason,
                            "score", BusinessView.round(rs.getDouble(6)), "computed_at", KgReader.iso(rs.getLong(9)), "evidence", evidence,
                            "evidence_complete", completeA && completeB);
                    contributions.put(contribution);
                    if (item == null) {
                        final JSONObject other = BusinessView.entityRef(c, group.other, mine ? bv : av);
                        final Set<String> memberships = memberships(c, group.other, permitted);
                        KgJson.put(other, "collections", new JSONArray(memberships));
                        KgJson.put(other, "target_collection", targetName);
                        item = KgJson.obj("id", rs.getString(1), "kind", Vocabulary.DERIVED_KINDS.get(group.kind), "direction", mine ? "out" : "in",
                                "other", other, "confidence", BusinessView.round(group.score), "score", BusinessView.round(group.score),
                                "reason", reason, "computed_at", KgReader.iso(rs.getLong(9)), "fact", false, "label", "suggestion",
                                "target_collection", targetName);
                    }
                }
            }
        }
        final Set<String> external = memberships(c, group.other, permitted);
        external.removeAll(memberships(c, ent, origin));
        KgJson.put(item.optJSONObject("other"), "other_collections", new JSONArray(external));
        KgJson.put(item, "origin_collections", new JSONArray(origins));
        KgJson.put(item, "contributions", contributions);
        return item;
    }

    private boolean references(final Connection c, final JSONArray ids, final Viewer v, final String collection, final JSONArray out)
            throws SQLException {
        if (ids == null || ids.length() == 0) return false;
        boolean complete = true;
        final Set<Long> seen = new TreeSet<>();
        for (int i = 0; i < ids.length(); i++) {
            final String id = ids.optString(i);
            final KgReader.Stat s = KgReader.statementRow(c, id, v);
            if (s == null || !BusinessView.visible(c, s.subj, v) || s.objEnt != null && !BusinessView.visible(c, s.objEnt, v)) {
                complete = false;
                continue;
            }
            if (!seen.add(s.rowid)) continue;
            this.reader.compute(c, List.of(s), v, this.reader.now());
            final JSONObject statement = this.reader.statementJson(c, s, v);
            if (s.publicId == null) KgJson.put(statement, "id", KgReader.statementPublicId(c, s.rowid));
            KgJson.put(statement, "collection", collection);
            out.put(statement);
        }
        return complete;
    }

    private static Set<String> memberships(final Connection c, final long ent, final Viewer v) throws SQLException {
        final Set<String> names = new TreeSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT k.name FROM kg_entity_scope es JOIN kg_collection k ON k.coll_id = es.coll_id"
                + " WHERE es.ent_rowid = ?" + (v.all() ? "" : " AND es.coll_id IN (" + BusinessView.ids(v) + ")") + " ORDER BY k.name")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) names.add(rs.getString(1)); }
        }
        return names;
    }
}
