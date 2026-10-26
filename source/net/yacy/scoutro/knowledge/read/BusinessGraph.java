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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;
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
            Vocabulary.CUSTOMER_TYPE, Vocabulary.TARGET_CATEGORY);

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
        Suggestions.Group suggestion;

        Edge(final JSONObject json, final String to, final double strength) {
            this.json = json;
            this.to = to;
            this.strength = strength;
        }
    }

    /** The neighbourhood of an entity; a redirect for a merged one; null if invisible. */
    public JSONObject neighborhood(final String id, final Query q, final Viewer v) throws KgException, KgReader.NotFound {
        return neighborhood(id, q, v, v);
    }

    /** The center's suggestions alone may reach other granted collections. */
    public JSONObject neighborhood(final String id, final Query q, final Viewer selected, final Viewer permitted)
            throws KgException, KgReader.NotFound {
        final Viewer v = KgReader.within(selected, permitted);
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
            addSuggestions(c, ent[0], center, q, v, permitted, first);
            first.sort((a, b) -> Double.compare(b.strength, a.strength));
            final Set<String> neighbourIds = new LinkedHashSet<>();
            for (final Edge e : first) {
                neighbourIds.add(e.to);
            }
            final List<String> neighbours = new ArrayList<>(neighbourIds);
            final int limit = Math.max(1, Math.min(MAX_NODES, q.limit));
            final List<String> page = neighbours.subList(Math.min(q.offset, neighbours.size()), Math.min(neighbours.size(), q.offset + limit));
            final Set<String> pageIds = new HashSet<>(page);
            final Map<String, Long> pageRows = new LinkedHashMap<>();
            for (final Edge e : first) {
                if (pageIds.contains(e.to)) {
                    expandSuggestion(c, ent[0], e, v, permitted);
                    KgJson.put(e.json, "direction", center.equals(e.json.optString("to")) ? "in" : "out");
                    edges.add(e.json);
                    if (!nodes.containsKey(e.to)) {
                        final Long row = e.json.has("_row") ? e.json.optLong("_row") : null;
                        final JSONObject suggestionNode = e.json.optJSONObject("other");
                        nodes.put(e.to, suggestionNode != null ? suggestionNode : row != null ? node(c, row, v, 1) : valueNode(e.to, 1));
                        if (suggestionNode != null) {
                            KgJson.put(suggestionNode, "label", suggestionNode.optString("name", null));
                            KgJson.put(suggestionNode, "depth", 1);
                        }
                        if (row != null) {
                            if (BusinessView.visible(c, row, v)) {
                                pageRows.put(e.to, row);
                                rows.put(e.to, row);
                            }
                        }
                    }
                    if (e.suggestion != null) {
                        final JSONObject other = e.json.optJSONObject("other"), shown = nodes.get(e.to);
                        for (final String key : List.of("collections", "other_collections", "target_collection")) {
                            KgJson.put(shown, key, other.opt(key));
                        }
                    }
                }
            }
            final boolean moreNeighbours = neighbours.size() > q.offset + page.size();
            boolean truncated = moreNeighbours;
            // depth 2: the relations of the organisations and facilities on the page, while room is left
            if (q.depth >= 2) {
                for (final Map.Entry<String, Long> n : pageRows.entrySet()) {
                    final String type = nodes.get(n.getKey()).optString("type");
                    if (!Vocabulary.ORGANIZATION.equals(type) && !Vocabulary.FACILITY.equals(type)) {
                        continue; // places, services, jobs and values are leaves: no hubs
                    }
                    final List<Edge> second = edgesOf(c, n.getValue(), n.getKey(), q, v, now, false);
                    addSuggestions(c, n.getValue(), n.getKey(), q, v, v, second);
                    second.sort((a, b) -> Double.compare(b.strength, a.strength));
                    for (final Edge e : second) {
                        if (nodes.containsKey(e.to)) {
                            if (!containsEdge(edges, e.json)) {
                                expandSuggestion(c, n.getValue(), e, v, v);
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
                        expandSuggestion(c, n.getValue(), e, v, v);
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
                    if ("collections".equals(k) && nodes.get(n.getKey()).has("target_collection")) continue;
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
                    "truncated", truncated, "next_offset", moreNeighbours ? q.offset + page.size() : null);
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
        this.view.ensureCollections(c);
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
                    // 0.13.0: the target category beside the target industry, so the reason of a target industry can be seen
                    final String node = (Vocabulary.INDUSTRY.equals(p) || Vocabulary.TARGET_INDUSTRY.equals(p) ? "nace:" : Vocabulary.CUSTOMER_TYPE
                            .equals(p) ? "customer_type:" : Vocabulary.TARGET_CATEGORY.equals(p) ? "category:" : "segment:") + f.stat.objVal;
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

    private void addSuggestions(final Connection c, final long ent, final String self, final Query q, final Viewer origin,
            final Viewer permitted, final List<Edge> edges) throws SQLException {
        if (!q.suggested) return;
        for (final Suggestions.Group g : Suggestions.groups(c, ent, origin, permitted,this.reader)) {
            final String kind = Vocabulary.DERIVED_KINDS.get(g.kind);
            if (q.types != null && !q.types.contains(kind)) continue;
            final String other = g.otherId;
            final boolean selfFirst = g.kind == 3 ? g.outgoing : self.compareTo(other) < 0;
            final String from = selfFirst ? self : other, to = selfFirst ? other : self;
            final Edge e = new Edge(KgJson.obj("id", "suggestion:" + kind + ":" + from + ":" + to, "from", from, "to", to,
                    "type", kind, "business", false, "status", "suggested", "confidence", BusinessView.round(g.score),
                    "fact", false, "_row", g.other), other, strength("suggested", g.score, 0));
            e.suggestion = g;
            edges.add(e);
        }
    }

    private void expandSuggestion(final Connection c, final long ent, final Edge edge, final Viewer origin, final Viewer permitted)
            throws SQLException {
        if (edge.suggestion == null || edge.json.has("contributions")) return;
        final JSONObject item = new Suggestions(this.reader).item(c, ent, edge.suggestion, origin, permitted);
        for (final String key : List.of("other", "score", "reason", "computed_at", "contributions","contributions_total","next_contribution_offset","contributions_path", "origin_collections", "target_collection")) {
            KgJson.put(edge.json, key, item.opt(key));
        }
        final boolean outgoing = "out".equals(item.optString("direction"));
        KgJson.put(edge.json, "from", outgoing ? KgReader.publicId(c,ent) : edge.suggestion.otherId);
        KgJson.put(edge.json, "to", outgoing ? edge.suggestion.otherId : KgReader.publicId(c,ent));
        int evidence = 0;
        final JSONArray contributions = item.optJSONArray("contributions");
        for (int i = 0; i < contributions.length(); i++) evidence += contributions.optJSONObject(i).optJSONArray("evidence").length();
        KgJson.put(edge.json, "evidence", evidence);
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
        final boolean category = id.startsWith("category:");
        final String code = id.substring(id.indexOf(':') + 1);
        final Categories.Entry e = cats.code(segment ? Vocabulary.AUDIENCE_SEGMENT : category ? Vocabulary.TARGET_CATEGORY : Vocabulary.CUSTOMER_TYPE, code);
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
            this.view.ensureCollections(c); // the price age of the collections, as in the business view
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
        long[] ent = null;
        if (entity != null) {
            ent = this.reader.store().read(c -> KgReader.entityRow(c, entity, v));
            if (ent == null) {
                throw new KgReader.NotFound("entity " + entity);
            }
        }
        final List<JSONObject> rows = collectDerived(kind, entity, ent, v);
        final int end = Math.min(rows.size(), offset + limit);
        return KgJson.obj("schema", BusinessView.SCHEMA, "offset", offset, "limit", limit, "total", rows.size(),
                "items", new JSONArray(rows.subList(Math.min(offset, rows.size()), end)), "note",
                "Derived suggestions, never facts or measured probabilities.");
    }

    /** Rows of kg_derived and kg_match_contribution read per transaction (WP-B: one read lease each, never all at once). */
    static int DERIVED_CHUNK = 500;
    static int CONTRIBUTION_CHUNK = 200;

    private static Integer kindNumber(final String kind) {
        if (kind == null) return null;
        switch (kind) {
            case "linked_to": return 1;
            case "same_operator": return 2;
            case "suggested_customer": return 3;
            case "suggested_partner": return 4;
            default: return -1;
        }
    }

    /**
     * The visible derived groups, optionally of one kind or entity. Reads kg_derived and the matching contributions in
     * keyset chunks, each in its own read transaction within the read lease, with the kind (and for kg_derived the
     * entity rows) filtered in SQL and the suggestion groups of an origin computed once (WP-B; the former single
     * transaction scanned everything, re-queried every row and recomputed an origin's groups for each of its rows, and
     * ended in read_timeout). The projection itself (visibility, grouping, deduplication, order) is unchanged.
     */
    List<JSONObject> collectDerived(final String kind, final String entity, final long[] ent, final Viewer v) throws KgException {
        final Integer kn = kindNumber(kind);
        final DerivedGroups acc = new DerivedGroups(kind, entity);
        if (kn != null && kn < 0) return acc.finish();
        final String[] after = {""};
        final long[] afterRow = {0L};
        boolean more = true;
        while (more) {
            more = this.reader.store().read(c -> {
                final Map<Long, List<Suggestions.Group>> memo = new java.util.HashMap<>();
                final StringBuilder sql = new StringBuilder("SELECT der_rowid, public_id, kind FROM kg_derived WHERE der_rowid > ?");
                if (kn != null) sql.append(" AND kind = ").append(kn.intValue());
                if (ent != null) {
                    final String rows = ent[0] + (ent[1] != 0L ? "," + ent[1] : "");
                    sql.append(" AND (a_ent IN (").append(rows).append(") OR b_ent IN (").append(rows).append("))");
                }
                sql.append(" ORDER BY der_rowid LIMIT ").append(DERIVED_CHUNK);
                int n = 0;
                try (PreparedStatement p = c.prepareStatement(sql.toString())) {
                    p.setLong(1, afterRow[0]);
                    try (ResultSet r = p.executeQuery()) {
                        while (r.next()) {
                            n++;
                            afterRow[0] = r.getLong(1);
                            final String id = r.getString(2);
                            final JSONObject item = r.getInt(3) >= 3 ? Suggestions.legacyRecord(c, id, v, this.reader, false, memo)
                                    : derivedRecord(c, id, v, this.reader);
                            if (item != null) acc.addDerived(item);
                        }
                    }
                }
                return n == DERIVED_CHUNK;
            });
        }
        more = kn == null || kn >= 3;
        while (more) {
            more = this.reader.store().read(c -> {
                int n = 0;
                try (PreparedStatement p = c.prepareStatement("SELECT * FROM kg_match_contribution WHERE public_id > ?"
                        + (kn != null ? " AND kind = " + kn.intValue() : "") + " ORDER BY public_id LIMIT " + CONTRIBUTION_CHUNK)) {
                    p.setString(1, after[0]);
                    try (ResultSet r = p.executeQuery()) {
                        while (r.next()) {
                            n++;
                            after[0] = r.getString("public_id");
                            final JSONObject reason = MatchingProjection.recordRow(c, r, v, this.reader.config());
                            if (reason != null) acc.addContribution(reason);
                        }
                    }
                }
                return n == CONTRIBUTION_CHUNK;
            });
        }
        return acc.finish();
    }

    /** Groups derived rows and matching contributions exactly as the former single-pass projection did. */
    private static final class DerivedGroups {
        private final String kind;
        private final String entity;
        private final Map<String, JSONObject> groups = new LinkedHashMap<>();
        private final Map<String, List<JSONObject>> reasonsByGroup = new LinkedHashMap<>();
        private final Map<String, MatchingProjection.Deduplicator> duplicates = new LinkedHashMap<>();

        DerivedGroups(final String kind, final String entity) {
            this.kind = kind;
            this.entity = entity;
        }

        void addDerived(final JSONObject item) {
            if (this.kind != null && !this.kind.equals(item.optString("kind"))) return;
            final String a = item.optJSONObject("subject").optString("id"), b = item.optJSONObject("other").optString("id");
            if (this.entity != null && !this.entity.equals(a) && !this.entity.equals(b)) return;
            final String key = item.optString("kind") + ":" + ("suggested_partner".equals(item.optString("kind")) && a.compareTo(b) > 0 ? b + ":" + a : a + ":" + b);
            final JSONObject existing = this.groups.get(key);
            if (existing == null) this.groups.put(key, item);
            else {
                final JSONArray all = existing.optJSONArray("contributions"), add = item.optJSONArray("contributions");
                if (all != null && add != null) for (int i = 0; i < add.length(); i++) all.put(add.optJSONObject(i));
                if (item.optDouble("score", item.optDouble("confidence")) > existing.optDouble("score", existing.optDouble("confidence"))) {
                    for (final String field : List.of("score", "confidence", "reason", "computed_at")) KgJson.put(existing, field, item.opt(field));
                }
            }
        }

        void addContribution(final JSONObject reason) {
            if (this.kind != null && !this.kind.equals(reason.optString("kind"))) return;
            final String a = reason.optJSONObject("provider").optString("id"), b = reason.optJSONObject("candidate").optString("id");
            if (this.entity != null && !this.entity.equals(a) && !this.entity.equals(b)) return;
            final String key = reason.optString("kind") + ":" + ("suggested_partner".equals(reason.optString("kind")) && a.compareTo(b) > 0 ? b + ":" + a : a + ":" + b);
            JSONObject item = this.groups.get(key);
            if (item == null) {
                item = KgJson.obj("id", reason.optString("proposal_id"), "kind", reason.optString("kind"), "subject", reason.optJSONObject("provider"),
                        "other", reason.optJSONObject("candidate"), "direction", "out", "score", 0.0, "confidence", 0.0, "fact", false, "label",
                        "suggestion", "contributions", new JSONArray());
                this.groups.put(key, item);
            }
            List<JSONObject> contributions = this.reasonsByGroup.get(key);
            if (contributions == null) {
                contributions = new ArrayList<>();
                final JSONArray initial = item.optJSONArray("contributions");
                for (int i = 0; i < initial.length(); i++) contributions.add(initial.optJSONObject(i));
                this.reasonsByGroup.put(key, contributions);
                this.duplicates.put(key, new MatchingProjection.Deduplicator(contributions));
            }
            this.duplicates.get(key).add(reason);
            KgJson.put(item, "contributions", new JSONArray(contributions));
            if (reason.optDouble("score") > item.optDouble("score", item.optDouble("confidence"))) {
                KgJson.put(item, "score", reason.optDouble("score"));
                KgJson.put(item, "confidence", reason.optDouble("score"));
                KgJson.put(item, "reason", reason.optString("reason"));
                KgJson.put(item, "computed_at", reason.opt("computed_at"));
            }
        }

        List<JSONObject> finish() {
            final List<JSONObject> out = new ArrayList<>(this.groups.values());
            for (final JSONObject item : out) {
                final JSONArray all = item.optJSONArray("contributions");
                if (all == null) continue;
                KgJson.put(item, "contributions_total", all.length());
                KgJson.put(item, "next_contribution_offset", all.length() > 25 ? 25 : null);
                KgJson.put(item, "contributions_path", "entities/" + item.optJSONObject("subject").optString("id") + "/suggestions/" + item.optString("id") + "/contributions");
                if (all.length() > 25) {
                    final JSONArray first = new JSONArray();
                    for (int i = 0; i < 25; i++) first.put(all.opt(i));
                    KgJson.put(item, "contributions", first);
                }
            }
            out.sort(java.util.Comparator.comparingDouble((JSONObject x) -> x.optDouble("score", x.optDouble("confidence"))).reversed()
                    .thenComparing(x -> x.optString("id")));
            return out;
        }
    }

    /** Facet counts of the derived kinds per viewer, kept while the derived data are unchanged (at most 10 minutes). */
    private static final Map<String, Object[]> DERIVED_COUNTS = new java.util.concurrent.ConcurrentHashMap<>();
    static final long DERIVED_COUNTS_TTL = 10L * 60L * 1000L;

    JSONObject derivedCounts(final Viewer v) throws KgException {
        final String version = this.reader.store().read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_derived") + ":"
                + KgStore.queryLong(c, "SELECT coalesce(max(computed_at), 0) FROM kg_derived") + ":"
                + KgStore.queryLong(c, "SELECT count(*) FROM kg_match_contribution") + ":"
                + KgStore.queryLong(c, "SELECT coalesce(max(computed_at), 0) FROM kg_match_contribution"));
        final String key = (v.all() ? "*" : new java.util.TreeSet<>(v.collections()).toString()) + "|" + version;
        final long now = System.currentTimeMillis();
        final Object[] cached = DERIVED_COUNTS.get(key);
        if (cached != null && now - (Long) cached[0] < DERIVED_COUNTS_TTL) return copyCounts((JSONObject) cached[1]);
        final Map<String, Integer> totals = new LinkedHashMap<>();
        for (final JSONObject item : collectDerived(null, null, null, v)) totals.merge(item.optString("kind"), 1, Integer::sum);
        final JSONObject d = new JSONObject();
        for (final Map.Entry<String, Integer> e : totals.entrySet()) KgJson.put(d, e.getKey(), e.getValue());
        if (DERIVED_COUNTS.size() > 64) DERIVED_COUNTS.clear();
        DERIVED_COUNTS.put(key, new Object[] {now, d});
        return copyCounts(d);
    }

    private static JSONObject copyCounts(final JSONObject d) {
        final JSONObject o = new JSONObject();
        for (final String k : d.keySet()) KgJson.put(o, k, d.opt(k));
        return o;
    }

    /** Derived row by ID for the change feed's expanded records; null if invisible. */
    static JSONObject derivedRecord(final Connection c, final String id, final Viewer v,final KgReader reader) throws SQLException {
        final long kind=net.yacy.scoutro.knowledge.store.KgStore.queryLong(c,"SELECT coalesce(max(kind),0) FROM kg_derived WHERE public_id='"+id+"'");
        if(kind>=3)return Suggestions.legacyRecord(c,id,v,reader);
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
        return facets(v, true);
    }

    /**
     * The filter values the viewer's graph holds and the vocabulary profile of each of the viewer's collections (a
     * category exists only where a profile is given, docs/SCOUTRO_KNOWLEDGE_GRAPH.md 28). With {@code counts} also the
     * entity, price and derived counts; the derived counts run in their own read, so a slow projection never costs the
     * filter values (they are null then, with {@code derived_error}). The page's selects ask without counts.
     */
    public JSONObject facets(final Viewer v, final boolean counts) throws KgException {
        final long now = this.reader.now();
        final Categories cats = KgVocabularies.get().categories;
        final Nace nace = KgVocabularies.get().nace;
        final JSONObject out = this.reader.store().read(c -> {
            final JSONObject o = KgJson.obj("schema", BusinessView.SCHEMA);
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
                KgJson.put(o, f[0], items);
            }
            KgJson.put(o, "profiles", profiles(c, v, cats));
            if (counts) {
                final JSONObject n = new JSONObject();
                for (final String[] f : new String[][] {{"jobs", "job"}, {"services", "service"}}) {
                    KgJson.put(n, f[0], net.yacy.scoutro.knowledge.store.KgStore.queryLong(c, "SELECT count(*) FROM kg_entity e JOIN kg_vocab t"
                            + " ON t.term_id = e.type WHERE t.name = '" + f[1] + "' AND " + KgReader.visibleEntity(v, "e")));
                }
                KgJson.put(n, "prices", net.yacy.scoutro.knowledge.store.KgStore.queryLong(c, "SELECT count(*) FROM kg_statement s JOIN kg_vocab v"
                        + " ON v.term_id = s.pred WHERE v.name = 'price' AND " + KgReader.visibleStatement(v, "s")));
                KgJson.put(o, "counts", n);
            }
            return o;
        });
        if (counts) {
            final JSONObject n = out.optJSONObject("counts");
            try {
                KgJson.put(n, "derived", derivedCounts(v));
            } catch (final KgException e) {
                KgJson.put(n, "derived", null);
                KgJson.put(n, "derived_error", e.code());
            }
        }
        return out;
    }

    /**
     * The vocabulary profile of each collection the viewer sees: the vocabulary in force and its source
     * ({@code setting}, {@code vocabulary_files}, {@code none}). Never guessed from a name.
     */
    private JSONArray profiles(final Connection c, final Viewer v, final Categories cats) throws SQLException {
        final net.yacy.scoutro.knowledge.KgConfig cfg = this.reader.config();
        final JSONArray out = new JSONArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM kg_collection" + (v.all() ? "" : " WHERE coll_id IN ("
                + BusinessView.ids(v) + ")") + " ORDER BY name"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final String name = rs.getString(1);
                final String setting = cfg.vocabOverrides.get(name);
                final String vocabulary = cfg.vocabularyOf(name, cats.collections);
                out.put(KgJson.obj("collection", name, "vocabulary", vocabulary, "source", setting != null ? "setting"
                        : cats.collections.containsKey(name) ? "vocabulary_files" : "none"));
            }
        }
        return out;
    }
}
