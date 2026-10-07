/*
 *  BusinessGraph
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.vocab.Categories;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;
import net.yacy.scoutro.knowledge.vocab.Nace;

/**
 * The network around an entity, the comparison of one service across
 * providers, the derived rows and the filter facets (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 23, part B), all computed for one viewer: an edge exists only if its fact
 * (or derived row) and both of its ends are visible; there is no global
 * graph.
 * <p>
 * Edge status: {@code confirmed} (supported, confidence at least 0.6),
 * {@code uncertain}, {@code stale}, {@code weak} ({@code linked_to}),
 * {@code derived} ({@code same_operator}), {@code suggested} (a match).
 * Node types: organisation, facility, site, place, service, job, and the
 * value nodes industry (a NACE code), audience (a segment or customer type)
 * and, on request, price (a published price of a service, attached to that
 * service only). Every entity node carries its context for the viewer
 * (hosts, collections, places, quality, sources, last confirmation); an edge
 * of the centre says whether it points to it ({@code in}) or away
 * ({@code out}).
 * <p>
 * A service (or a job) in the centre shows who offers it (the incoming
 * {@code offers}; the employer of a job) and, at depth 2, the relations of
 * those providers, but not their other services and jobs: those are in the
 * provider's own network (package 6.1).
 */
public final class BusinessGraph {

    public static final int MAX_NODES = 200;
    public static final int MAX_DEPTH = 2;
    /** The edge types a filter may name. */
    public static final Set<String> VALUE_EDGES = Set.of(Vocabulary.INDUSTRY, Vocabulary.TARGET_INDUSTRY, Vocabulary.AUDIENCE_SEGMENT,
            Vocabulary.CUSTOMER_TYPE);

    private final KgReader reader;
    private final BusinessView view;

    public BusinessGraph(final KgReader reader) {
        this.reader = reader;
        this.view = new BusinessView(reader);
    }

    /** What a neighbourhood shows. */
    public static final class Query {
        public int depth = 1;
        public int limit = 50;
        public int offset;
        /** Edge types (predicates, derived kinds, value predicates); null for all. */
        public Set<String> types;
        public boolean weak;
        public boolean derived = true;
        public boolean suggested;
        public boolean values = true;
        public boolean stale;
        /** The published prices of the services in the centre and at depth 1, as value nodes (package 6.1). */
        public boolean prices;
    }

    /** Prices shown per service in the network. */
    static final int MAX_PRICES_PER_SERVICE = 5;

    private static final class Edge {
        final JSONObject json;
        final String to;
        final double strength;

        Edge(final JSONObject json, final String to, final double strength) {
            this.json = json;
            this.to = to;
            this.strength = strength;
        }
    }

    /** The neighbourhood of an entity; a redirect for a merged one; null if invisible. */
    public JSONObject neighborhood(final String id, final Query q, final Viewer v) throws KgException, KgReader.NotFound {
        final long now = this.reader.now();
        final Object r = this.reader.store().read(c -> {
            final long[] ent = KgReader.entityRow(c, id, v);
            if (ent == null) {
                return null;
            }
            if (ent[1] != 0L) {
                return KgJson.obj("schema", BusinessView.SCHEMA, "redirect", KgReader.publicId(c, ent[1]));
            }
            final Map<String, JSONObject> nodes = new LinkedHashMap<>();
            final Map<String, Long> rows = new LinkedHashMap<>();
            final List<JSONObject> edges = new ArrayList<>();
            final String center = KgReader.publicId(c, ent[0]);
            nodes.put(center, node(c, ent[0], v, 0));
            rows.put(center, ent[0]);
            final String centerType = nodes.get(center).optString("type");
            // a service or job in the centre: its providers' other services and jobs are no neighbours of it
            final boolean leafCenter = Vocabulary.SERVICE.equals(centerType) || Vocabulary.JOB.equals(centerType);
            // depth 1: the center's edges, strongest first, paged by neighbour
            final List<Edge> first = edgesOf(c, ent[0], center, q, v, now, true);
            first.sort((a, b) -> Double.compare(b.strength, a.strength));
            final List<String> neighbours = new ArrayList<>();
            for (final Edge e : first) {
                if (!neighbours.contains(e.to)) {
                    neighbours.add(e.to);
                }
            }
            final int limit = Math.max(1, Math.min(MAX_NODES, q.limit));
            final List<String> page = neighbours.subList(Math.min(q.offset, neighbours.size()), Math.min(neighbours.size(), q.offset + limit));
            final Map<String, Long> pageRows = new LinkedHashMap<>();
            for (final Edge e : first) {
                if (page.contains(e.to)) {
                    KgJson.put(e.json, "direction", center.equals(e.json.optString("to")) ? "in" : "out");
                    edges.add(e.json);
                    if (!nodes.containsKey(e.to)) {
                        final Long row = e.json.has("_row") ? e.json.optLong("_row") : null;
                        nodes.put(e.to, row != null ? node(c, row, v, 1) : valueNode(e.to, 1));
                        if (row != null) {
                            pageRows.put(e.to, row);
                            rows.put(e.to, row);
                        }
                    }
                }
            }
            boolean truncated = neighbours.size() > q.offset + page.size();
            // depth 2: the relations of the organisations and facilities on the page, while room is left
            if (q.depth >= 2) {
                for (final Map.Entry<String, Long> n : pageRows.entrySet()) {
                    final String type = nodes.get(n.getKey()).optString("type");
                    if (!Vocabulary.ORGANIZATION.equals(type) && !Vocabulary.FACILITY.equals(type)) {
                        continue; // places, services, jobs and values are leaves: no hubs
                    }
                    final List<Edge> second = edgesOf(c, n.getValue(), n.getKey(), q, v, now, false);
                    second.sort((a, b) -> Double.compare(b.strength, a.strength));
                    for (final Edge e : second) {
                        if (nodes.containsKey(e.to)) {
                            if (!containsEdge(edges, e.json)) {
                                if (center.equals(e.json.optString("from")) || center.equals(e.json.optString("to"))) {
                                    KgJson.put(e.json, "direction", center.equals(e.json.optString("to")) ? "in" : "out");
                                }
                                edges.add(e.json); // an edge between two shown nodes is always shown
                            }
                            continue;
                        }
                        final String edgeType = e.json.optString("type");
                        if (leafCenter && (Vocabulary.OFFERS.equals(edgeType) || Vocabulary.HIRING_ORGANIZATION.equals(edgeType))) {
                            continue; // another service or job of the provider
                        }
                        if (nodes.size() >= limit + 1) {
                            truncated = true;
                            break;
                        }
                        final Long row = e.json.has("_row") ? e.json.optLong("_row") : null;
                        nodes.put(e.to, row != null ? node(c, row, v, 2) : valueNode(e.to, 2));
                        if (row != null) {
                            rows.put(e.to, row);
                        }
                        edges.add(e.json);
                    }
                }
            }
            if (q.prices) {
                truncated |= prices(c, nodes, rows, edges, v, now, limit);
            }
            // the context of every entity node, in a few queries for the whole network
            final Map<Long, EntityContexts.Ctx> ctx = EntityContexts.of(c, rows.values(), v, this.reader, now);
            for (final Map.Entry<String, Long> n : rows.entrySet()) {
                final JSONObject cj = EntityContexts.ctxJson(ctx.get(n.getValue()));
                for (final String k : cj.keySet()) {
                    KgJson.put(nodes.get(n.getKey()), k, cj.opt(k));
                }
            }
            final JSONArray edgeArray = new JSONArray();
            for (final JSONObject e : edges) {
                e.remove("_row");
                edgeArray.put(e);
            }
            return KgJson.obj("schema", BusinessView.SCHEMA, "center", center, "depth", Math.min(MAX_DEPTH, Math.max(1, q.depth)), "nodes",
                    new JSONArray(nodes.values()), "edges", edgeArray, "offset", q.offset, "limit", limit, "neighbours", neighbours.size(),
                    "truncated", truncated, "next_offset", truncated ? q.offset + page.size() : null);
        });
        if (r == null) {
            throw new KgReader.NotFound("entity " + id);
        }
        return (JSONObject) r;
    }

    /**
     * Price nodes of the services in the centre and at depth 1: each price
     * hangs on its own service only, with its status (current, uncertain,
     * stale, expired, conflicting); true if the node limit cut some off.
     */
    private boolean prices(final Connection c, final Map<String, JSONObject> nodes, final Map<String, Long> rows, final List<JSONObject> edges,
            final Viewer v, final long now, final int limit) throws SQLException {
        final List<Map.Entry<String, Long>> services = new ArrayList<>();
        for (final Map.Entry<String, Long> n : rows.entrySet()) {
            final JSONObject node = nodes.get(n.getKey());
            if (Vocabulary.SERVICE.equals(node.optString("type")) && node.optInt("depth") <= 1) {
                services.add(n);
            }
        }
        for (final Map.Entry<String, Long> s : services) {
            final List<BusinessView.Fact> facts = this.view.facts(c, s.getValue(), false, List.of(Vocabulary.PRICE), v, now, 50);
            final JSONArray items = this.view.priceItems(facts, s.getKey(), nodes.get(s.getKey()).optString("label", null), null, now,
                    new java.util.LinkedHashSet<>());
            BusinessView.markConflicts(items);
            for (int i = 0; i < items.length() && i < MAX_PRICES_PER_SERVICE; i++) {
                if (nodes.size() >= limit + 1) {
                    return true;
                }
                final JSONObject p = items.optJSONObject(i);
                final String id = "price:" + p.optString("statement");
                final String status = p.optString("status");
                nodes.put(id, KgJson.obj("id", id, "type", "price", "price", p.opt("value"), "status", status, "as_of", p.opt("as_of"),
                        "service", s.getKey(), "depth", nodes.get(s.getKey()).optInt("depth") + 1, "value", true));
                edges.add(KgJson.obj("id", p.optString("statement"), "from", s.getKey(), "to", id, "type", Vocabulary.PRICE, "business", false,
                        "status", "current".equals(status) ? "confirmed" : "uncertain".equals(status) || "conflicting".equals(status) ? "uncertain"
                                : "stale", "confidence", p.opt("confidence"), "evidence", p.optInt("sources"), "fact", true));
            }
        }
        return false;
    }

    private static boolean containsEdge(final List<JSONObject> edges, final JSONObject e) {
        for (final JSONObject x : edges) {
            if (x.optString("id").equals(e.optString("id"))) {
                return true;
            }
        }
        return false;
    }

    /** The edges of one entity: relations both ways, values, derived rows; only with visible ends. */
    private List<Edge> edgesOf(final Connection c, final long ent, final String self, final Query q, final Viewer v, final long now,
            final boolean withValues) throws SQLException {
        final List<Edge> out = new ArrayList<>();
        for (final boolean incoming : new boolean[] {false, true}) {
            for (final BusinessView.Fact f : this.view.facts(c, ent, incoming, null, v, now, 2000)) {
                final String p = f.stat.predicate;
                if (q.types != null && !q.types.contains(p)) {
                    continue;
                }
                if (!q.stale && "stale".equals(f.stat.quality)) {
                    continue;
                }
                if (f.stat.objEnt != null) {
                    final long other = incoming ? f.stat.subj : f.stat.objEnt;
                    if (other == ent || !BusinessView.visible(c, other, v)) {
                        continue;
                    }
                    final String otherId = KgReader.publicId(c, other);
                    final String status = "stale".equals(f.stat.quality) ? "stale" : "current".equals(f.status()) ? "confirmed" : "uncertain";
                    final JSONObject e = KgJson.obj("id", f.stat.publicId, "from", incoming ? otherId : self, "to", incoming ? self : otherId,
                            "type", p, "business", Vocabulary.BUSINESS_RELATIONS.contains(p), "status", status, "confidence",
                            BusinessView.round(f.confidence), "evidence", f.stat.docs.size(), "fact", true, "_row", other);
                    out.add(new Edge(e, otherId, strength(status, f.confidence, f.stat.docs.size())));
                } else if (withValues && q.values && !incoming && VALUE_EDGES.contains(p) && f.stat.objVal != null) {
                    final String node = (Vocabulary.INDUSTRY.equals(p) || Vocabulary.TARGET_INDUSTRY.equals(p) ? "nace:" : Vocabulary.CUSTOMER_TYPE
                            .equals(p) ? "customer_type:" : "segment:") + f.stat.objVal;
                    final String status = "stale".equals(f.stat.quality) ? "stale" : "current".equals(f.status()) ? "confirmed" : "uncertain";
                    final JSONObject e = KgJson.obj("id", f.stat.publicId, "from", self, "to", node, "type", p, "business", false, "status", status,
                            "confidence", BusinessView.round(f.confidence), "evidence", f.stat.docs.size(), "fact", true);
                    out.add(new Edge(e, node, strength(status, f.confidence, f.stat.docs.size()) - 0.5));
                }
            }
        }
        final List<Integer> kinds = new ArrayList<>();
        if (q.weak && (q.types == null || q.types.contains(Vocabulary.LINKED_TO))) {
            kinds.add(1);
        }
        if (q.derived && (q.types == null || q.types.contains(Vocabulary.SAME_OPERATOR))) {
            kinds.add(2);
        }
        if (q.suggested && (q.types == null || q.types.contains(Vocabulary.SUGGESTED_CUSTOMER))) {
            kinds.add(3);
        }
        if (q.suggested && (q.types == null || q.types.contains(Vocabulary.SUGGESTED_PARTNER))) {
            kinds.add(4);
        }
        if (!kinds.isEmpty()) {
            final StringBuilder in = new StringBuilder();
            for (final Integer k : kinds) {
                in.append(in.length() == 0 ? "" : ",").append(k.intValue());
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT d.public_id, d.kind, d.a_ent, d.b_ent, d.confidence FROM kg_derived d"
                    + " JOIN kg_entity a ON a.ent_rowid = d.a_ent JOIN kg_entity b ON b.ent_rowid = d.b_ent WHERE (d.a_ent = ? OR d.b_ent = ?)"
                    + " AND d.kind IN (" + in + ") AND a.status = 1 AND b.status = 1 AND " + BusinessView.visibleDerived(v, "d")
                    + " ORDER BY d.confidence DESC LIMIT 200")) {
                ps.setLong(1, ent);
                ps.setLong(2, ent);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final long other = rs.getLong(3) == ent ? rs.getLong(4) : rs.getLong(3);
                        if (!BusinessView.visible(c, other, v)) {
                            continue;
                        }
                        final String otherId = KgReader.publicId(c, other);
                        final String from = KgReader.publicId(c, rs.getLong(3));
                        final String to = KgReader.publicId(c, rs.getLong(4));
                        final int kind = rs.getInt(2);
                        final String status = kind == 1 ? "weak" : kind == 2 ? "derived" : "suggested";
                        final JSONObject e = KgJson.obj("id", rs.getString(1), "from", from, "to", to, "type", Vocabulary.DERIVED_KINDS.get(kind),
                                "business", false, "status", status, "confidence", BusinessView.round(rs.getDouble(5)), "evidence", 0, "fact", false,
                                "_row", other);
                        out.add(new Edge(e, otherId, strength(status, rs.getDouble(5), 0)));
                    }
                }
            }
        }
        return out;
    }

    /** The subject of a derived row: ID, stated name and the name to show. */
    private static JSONObject subjectRef(final Connection c, final long ent, final Viewer v) throws SQLException {
        final String name = KgReader.visibleName(c, ent, v);
        return DisplayNames.put(KgJson.obj("id", KgReader.publicId(c, ent), "name", name), DisplayNames.known(c, ent, name, v));
    }

    static double strength(final String status, final double confidence, final int evidence) {
        final double base;
        switch (status) {
            case "confirmed":
                base = 4.0;
                break;
            case "uncertain":
                base = 3.0;
                break;
            case "derived":
                base = 2.5;
                break;
            case "suggested":
                base = 2.0;
                break;
            case "weak":
                base = 1.0;
                break;
            default:
                base = 0.5;
        }
        return base + confidence + Math.min(10, evidence) * 0.01;
    }

    static JSONObject node(final Connection c, final long ent, final Viewer v, final int depth) throws SQLException {
        String type = null;
        String kind = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT t.name, e.subkind FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type"
                + " WHERE e.ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    type = rs.getString(1);
                    kind = rs.getString(2);
                }
            }
        }
        final String name = KgReader.visibleName(c, ent, v);
        // label: the stated name; display_name: what to show, never the ID (package 6.1)
        return DisplayNames.put(KgJson.obj("id", KgReader.publicId(c, ent), "type", type, "kind", kind, "label", name, "depth", depth,
                "value", false), name != null ? DisplayNames.known(c, ent, name, v) : DisplayNames.of(c, ent, type, null, null, v));
    }

    private static JSONObject valueNode(final String id, final int depth) {
        final Categories cats = KgVocabularies.get().categories;
        final Nace nace = KgVocabularies.get().nace;
        if (id.startsWith("nace:")) {
            final String code = id.substring(5);
            return KgJson.obj("id", id, "type", "industry", "code", code, "label", nace.label(code), "depth", depth, "value", true);
        }
        final boolean segment = id.startsWith("segment:");
        final String code = id.substring(id.indexOf(':') + 1);
        final Categories.Entry e = cats.code(segment ? Vocabulary.AUDIENCE_SEGMENT : Vocabulary.CUSTOMER_TYPE, code);
        return KgJson.obj("id", id, "type", "audience", "code", code, "label", e == null ? code : e.en, "label_de", e == null ? code : e.de,
                "label_en", e == null ? code : e.en, "depth", depth, "value", true);
    }

    // ------------------------------------------------------------- compare

    /** The same service category across providers: one row per service with its prices, conditions, dates and sources. */
    public JSONObject compare(final String category, final int limit, final Viewer v) throws KgException {
        final long now = this.reader.now();
        final Categories.Entry entry = KgVocabularies.get().categories.entry(category);
        return this.reader.store().read(c -> {
            final JSONArray rows = new JSONArray();
            final List<Long> services = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT s.subj FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                    + " JOIN kg_entity e ON e.ent_rowid = s.subj WHERE v.kind = 2 AND v.name = 'category' AND s.obj_val = ? AND s.quality <> 4"
                    + " AND e.status = 1 AND " + KgReader.visibleStatement(v, "s") + " AND " + this.reader.currentStatement(v, "s", now)
                    + " LIMIT " + (limit + 1))) {
                ps.setString(1, category);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        services.add(rs.getLong(1));
                    }
                }
            }
            final boolean truncated = services.size() > limit;
            final java.util.Set<Long> providerRows = new java.util.LinkedHashSet<>();
            for (final long s : services.subList(0, Math.min(limit, services.size()))) {
                final List<BusinessView.Fact> facts = this.view.facts(c, s, false, List.of(Vocabulary.NAME, Vocabulary.PRICE), v, now, 200);
                String name = null;
                final List<BusinessView.Fact> prices = new ArrayList<>();
                for (final BusinessView.Fact f : facts) {
                    if (Vocabulary.NAME.equals(f.stat.predicate)) {
                        name = name == null || "supported".equals(f.stat.quality) ? f.stat.objVal : name;
                    } else {
                        prices.add(f);
                    }
                }
                final String sid = KgReader.publicId(c, s);
                final JSONArray items = this.view.priceItems(prices, sid, name, null, now, new java.util.LinkedHashSet<>());
                BusinessView.markConflicts(items);
                final JSONArray providers = new JSONArray();
                for (final BusinessView.Fact f : this.view.facts(c, s, true, List.of(Vocabulary.OFFERS), v, now, 20)) {
                    if (BusinessView.visible(c, f.stat.subj, v)) {
                        final String pname = KgReader.visibleName(c, f.stat.subj, v);
                        final JSONObject p = DisplayNames.put(KgJson.obj("id", KgReader.publicId(c, f.stat.subj), "name", pname,
                                "locality", locality(c, f.stat.subj, v), "_row", f.stat.subj), DisplayNames.known(c, f.stat.subj, pname, v));
                        providers.put(p);
                        providerRows.add(f.stat.subj);
                    }
                }
                rows.put(KgJson.obj("service", DisplayNames.put(KgJson.obj("id", sid, "name", name), DisplayNames.known(c, s, name, v)),
                        "providers", providers, "prices", items));
            }
            // each provider with the hosts and collections of the viewer's pages (package 6.1), batched
            final Map<Long, EntityContexts.Ctx> ctx = EntityContexts.of(c, providerRows, v, this.reader, now);
            for (int i = 0; i < rows.length(); i++) {
                final JSONArray providers = rows.optJSONObject(i).optJSONArray("providers");
                for (int j = 0; j < providers.length(); j++) {
                    final JSONObject p = providers.optJSONObject(j);
                    final EntityContexts.Ctx x = ctx.get(p.optLong("_row"));
                    p.remove("_row");
                    KgJson.put(p, "hosts", new JSONArray(x.hosts));
                    KgJson.put(p, "collections", new JSONArray(x.collections));
                }
            }
            return KgJson.obj("schema", BusinessView.SCHEMA, "category", KgJson.obj("code", category, "label_de", entry == null ? null : entry.de,
                    "label_en", entry == null ? null : entry.en, "nace", entry == null ? null : entry.nace), "rows", rows, "truncated", truncated,
                    "note", "prices as published, with their unit, conditions and date; never converted, normalised or averaged");
        });
    }

    private static String locality(final Connection c, final long ent, final Viewer v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT s.obj_val FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE s.subj = ? AND v.name = 'locality' AND " + KgReader.visibleStatement(v, "s") + " LIMIT 1")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ------------------------------------------------------------- derived

    /** Derived rows (matches, linked_to, same_operator) the viewer may see, optionally of one entity. */
    public JSONObject derived(final String kind, final String entity, final int offset, final int limit, final Viewer v)
            throws KgException, KgReader.NotFound {
        final Object r = this.reader.store().read(c -> {
            Long ent = null;
            if (entity != null) {
                final long[] e = KgReader.entityRow(c, entity, v);
                if (e == null) {
                    return null;
                }
                ent = e[1] != 0L ? e[1] : e[0];
            }
            final int k = kind == null ? 0 : Vocabulary.DERIVED_KINDS.indexOf(kind);
            final String where = "a.status = 1 AND b.status = 1 AND " + BusinessView.visibleDerived(v, "d") + (k > 0 ? " AND d.kind = " + k : "")
                    + (ent != null ? " AND (d.a_ent = " + ent + " OR d.b_ent = " + ent + ")" : "");
            final String from = " FROM kg_derived d JOIN kg_entity a ON a.ent_rowid = d.a_ent JOIN kg_entity b ON b.ent_rowid = d.b_ent WHERE " + where;
            final long total = net.yacy.scoutro.knowledge.store.KgStore.queryLong(c, "SELECT count(*)" + from);
            final JSONArray items = new JSONArray();
            try (PreparedStatement ps = c.prepareStatement("SELECT d.public_id, d.kind, d.a_ent, d.b_ent, d.confidence, d.reason, d.basis,"
                    + " d.computed_at" + from + " ORDER BY d.kind, d.confidence DESC, d.der_rowid LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (!BusinessView.visible(c, rs.getLong(3), v) || !BusinessView.visible(c, rs.getLong(4), v)) {
                            continue;
                        }
                        final JSONObject o = BusinessView.derivedJson(rs, rs.getLong(4), true, c, v);
                        KgJson.put(o, "subject", subjectRef(c, rs.getLong(3), v));
                        items.put(o);
                    }
                }
            }
            return KgJson.obj("schema", BusinessView.SCHEMA, "offset", offset, "limit", limit, "total", total, "items", items,
                    "note", "derived rows are no facts: suggestions and weak signals, each with the facts of both sides");
        });
        if (r == null) {
            throw new KgReader.NotFound("entity " + entity);
        }
        return (JSONObject) r;
    }

    /** Derived row by ID for the change feed's expanded records; null if invisible. */
    static JSONObject derivedRecord(final Connection c, final String id, final Viewer v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT d.public_id, d.kind, d.a_ent, d.b_ent, d.confidence, d.reason, d.basis,"
                + " d.computed_at FROM kg_derived d JOIN kg_entity a ON a.ent_rowid = d.a_ent JOIN kg_entity b ON b.ent_rowid = d.b_ent"
                + " WHERE d.public_id = ? AND a.status = 1 AND b.status = 1 AND " + BusinessView.visibleDerived(v, "d"))) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !BusinessView.visible(c, rs.getLong(3), v) || !BusinessView.visible(c, rs.getLong(4), v)) {
                    return null;
                }
                final JSONObject o = BusinessView.derivedJson(rs, rs.getLong(4), true, c, v);
                KgJson.put(o, "subject", subjectRef(c, rs.getLong(3), v));
                return o;
            }
        }
    }

    // -------------------------------------------------------------- facets

    /** The filter values the viewer's graph holds: industries, service categories, audiences, places; with entity counts. */
    public JSONObject facets(final Viewer v) throws KgException {
        final long now = this.reader.now();
        final Categories cats = KgVocabularies.get().categories;
        final Nace nace = KgVocabularies.get().nace;
        return this.reader.store().read(c -> {
            final JSONObject out = KgJson.obj("schema", BusinessView.SCHEMA);
            for (final String[] f : new String[][] {{"industries", Vocabulary.INDUSTRY}, {"categories", Vocabulary.CATEGORY},
                {"customer_types", Vocabulary.CUSTOMER_TYPE}, {"segments", Vocabulary.AUDIENCE_SEGMENT},
                {"target_industries", Vocabulary.TARGET_INDUSTRY}, {"employment_types", Vocabulary.EMPLOYMENT_TYPE}}) {
                final JSONArray items = new JSONArray();
                try (PreparedStatement ps = c.prepareStatement("SELECT s.obj_val, count(DISTINCT s.subj) FROM kg_statement s JOIN kg_vocab v"
                        + " ON v.term_id = s.pred WHERE v.kind = 2 AND v.name = ? AND s.quality <> 4 AND " + KgReader.visibleStatement(v, "s")
                        + " AND " + this.reader.currentStatement(v, "s", now) + " GROUP BY s.obj_val ORDER BY 2 DESC, 1 LIMIT 200")) {
                    ps.setString(1, f[1]);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            final String code = rs.getString(1);
                            final JSONObject item = KgJson.obj("code", code, "entities", rs.getLong(2));
                            if (Vocabulary.INDUSTRY.equals(f[1]) || Vocabulary.TARGET_INDUSTRY.equals(f[1])) {
                                KgJson.put(item, "label", nace.label(code));
                            } else {
                                final Categories.Entry e = cats.code(f[1], code);
                                KgJson.put(item, "label_de", e == null ? null : e.de);
                                KgJson.put(item, "label_en", e == null ? null : e.en);
                            }
                            items.put(item);
                        }
                    }
                }
                KgJson.put(out, f[0], items);
            }
            final JSONObject counts = new JSONObject();
            for (final String[] f : new String[][] {{"jobs", "job"}, {"services", "service"}}) {
                KgJson.put(counts, f[0], net.yacy.scoutro.knowledge.store.KgStore.queryLong(c, "SELECT count(*) FROM kg_entity e JOIN kg_vocab t"
                        + " ON t.term_id = e.type WHERE t.name = '" + f[1] + "' AND " + KgReader.visibleEntity(v, "e")));
            }
            KgJson.put(counts, "prices", net.yacy.scoutro.knowledge.store.KgStore.queryLong(c, "SELECT count(*) FROM kg_statement s JOIN kg_vocab v"
                    + " ON v.term_id = s.pred WHERE v.name = 'price' AND " + KgReader.visibleStatement(v, "s")));
            final JSONObject derived = new JSONObject();
            try (PreparedStatement ps = c.prepareStatement("SELECT d.kind, count(*) FROM kg_derived d WHERE " + BusinessView.visibleDerived(v, "d")
                    + " GROUP BY d.kind"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    KgJson.put(derived, Vocabulary.DERIVED_KINDS.get(rs.getInt(1)), rs.getLong(2));
                }
            }
            KgJson.put(counts, "derived", derived);
            KgJson.put(out, "counts", counts);
            return out;
        });
    }
}
