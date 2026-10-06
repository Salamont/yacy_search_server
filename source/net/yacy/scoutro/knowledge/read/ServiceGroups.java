/*
 *  ServiceGroups
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

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * Services of the same name across providers, for reading only (package 6.1):
 * "SAP · 133 providers". A service stays one entity per provider (key
 * {@code service_name} within the provider's domain); a group is computed
 * when it is read, over the viewer's visible name statements, and changes no
 * entity, no ID, no price and no evidence. Its counts are counts of separate
 * services: how many providers, in which of the viewer's collections and
 * places, how many with a current published price, how many with a current
 * source. The rows of a group are the services themselves, each with its own
 * provider, prices and sources; nothing is merged, averaged or mixed.
 * <p>
 * The group key is the name in lower case (SQLite {@code lower}, ASCII) with
 * surrounding spaces removed.
 */
public final class ServiceGroups {

    public static final int MAX_LIMIT = 50;
    /** Services read for the counts of one group. */
    static final int MAX_GROUP_SERVICES = 5000;
    static final int MAX_PLACES = 10;
    static final String NOTE = "aggregated by name for reading only: every service stays its own object with its own provider, prices and"
            + " sources; nothing is merged, averaged or mixed";

    private final KgReader reader;

    public ServiceGroups(final KgReader reader) {
        this.reader = reader;
    }

    /** The visible name statements of visible services, optionally matching a text and of a category. */
    private static String names(final Viewer v, final boolean text, final boolean category) {
        return " FROM kg_statement n JOIN kg_vocab nv ON nv.term_id = n.pred JOIN kg_entity e ON e.ent_rowid = n.subj"
                + " WHERE nv.kind = 2 AND nv.name = 'name' AND e.type = (SELECT term_id FROM kg_vocab WHERE kind = 1 AND name = 'service')"
                + " AND " + KgReader.visibleEntity(v, "e") + " AND " + KgReader.visibleStatement(v, "n")
                + (text ? " AND n.stmt_rowid IN (SELECT f.rowid FROM kg_name_fts f WHERE kg_name_fts MATCH ?)" : "")
                + (category ? " AND n.subj IN (SELECT k.subj FROM kg_statement k JOIN kg_vocab kv ON kv.term_id = k.pred WHERE kv.kind = 2"
                        + " AND kv.name = 'category' AND k.obj_val = ? AND " + KgReader.visibleStatement(v, "k") + ")" : "");
    }

    /** Groups of services by name, the largest first. */
    public JSONObject groups(final String text, final String category, final int offset, final int limit, final Viewer v) throws KgException {
        final long now = this.reader.now();
        final String match = text == null ? null : KgReader.ftsQuery(text);
        return this.reader.store().read(c -> {
            final JSONArray items = new JSONArray();
            if (text != null && match == null) {
                return page(offset, limit, 0L, items);
            }
            final List<Object> args = new ArrayList<>();
            if (match != null) {
                args.add(match);
            }
            if (category != null) {
                args.add(category);
            }
            final String from = names(v, match != null, category != null);
            final long total;
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM (SELECT lower(trim(n.obj_val)) k" + from + " GROUP BY k)")) {
                bind(ps, args, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0L;
                }
            }
            final List<String> keys = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT lower(trim(n.obj_val)) k, count(DISTINCT n.subj)" + from
                    + " GROUP BY k ORDER BY 2 DESC, 1 LIMIT ? OFFSET ?")) {
                final int i = bind(ps, args, 1);
                ps.setInt(i, limit);
                ps.setInt(i + 1, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        keys.add(rs.getString(1));
                    }
                }
            }
            for (final String k : keys) {
                items.put(group(c, k, category, v, now));
            }
            return page(offset, limit, total, items);
        });
    }

    /** One group by name with the services of its page, each with its own provider; null if no visible service has the name. */
    public JSONObject providers(final String name, final String category, final int offset, final int limit, final Viewer v)
            throws KgException, KgReader.NotFound {
        final long now = this.reader.now();
        final Object r = this.reader.store().read(c -> {
            final String key = key(c, name);
            final List<Long> services = services(c, key, category, v);
            if (services.isEmpty()) {
                return null;
            }
            final JSONObject group = summary(c, key, services, v, now);
            final List<Long> page = services.subList(Math.min(offset, services.size()), Math.min(services.size(), offset + limit));
            final Map<Long, JSONObject> context = EntityContexts.json(c, page, v, this.reader, now);
            final Map<Long, long[]> prices = priceCounts(c, page, v, now);
            final JSONArray items = new JSONArray();
            for (final Long s : page) {
                final JSONObject ctx = context.get(s);
                final long[] p = prices.getOrDefault(s, new long[2]);
                items.put(KgJson.obj("service", KgJson.obj("id", KgReader.publicId(c, s), "name", KgReader.visibleName(c, s, v), "quality",
                        ctx.opt("quality"), "sources", ctx.opt("sources"), "last_confirmed", ctx.opt("last_confirmed"), "hosts", ctx.opt("hosts"),
                        "collections", ctx.opt("collections")), "providers", ctx.opt("providers"), "provider_count", ctx.opt("provider_count"),
                        "prices", KgJson.obj("current", p[0], "all", p[1])));
            }
            return KgJson.obj("schema", BusinessView.SCHEMA, "group", group, "offset", offset, "limit", limit, "total", services.size(), "items",
                    items, "note", NOTE);
        });
        if (r == null) {
            throw new KgReader.NotFound("service name " + name);
        }
        return (JSONObject) r;
    }

    private static String key(final Connection c, final String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT lower(trim(?))")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : name;
            }
        }
    }

    /** The visible services with this name key, the oldest first (a stable order). */
    private static List<Long> services(final Connection c, final String key, final String category, final Viewer v) throws SQLException {
        final List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT n.subj" + names(v, false, category != null)
                + " AND lower(trim(n.obj_val)) = ? ORDER BY n.subj LIMIT " + MAX_GROUP_SERVICES)) {
            int i = 1;
            if (category != null) {
                ps.setString(i++, category);
            }
            ps.setString(i, key);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
        }
        return out;
    }

    private JSONObject group(final Connection c, final String key, final String category, final Viewer v, final long now) throws SQLException {
        return summary(c, key, services(c, key, category, v), v, now);
    }

    /** The counts of one group: providers, collections, places, current prices, current sources. */
    private JSONObject summary(final Connection c, final String key, final List<Long> services, final Viewer v, final long now)
            throws SQLException {
        final String in = EntityContexts.in(services);
        // the name as most of the viewer's pages write it
        String name = key;
        try (PreparedStatement ps = c.prepareStatement("SELECT n.obj_val, count(*) FROM kg_statement n JOIN kg_vocab nv ON nv.term_id = n.pred"
                + " WHERE nv.kind = 2 AND nv.name = 'name' AND n.subj IN (" + in + ") AND lower(trim(n.obj_val)) = ? AND "
                + KgReader.visibleStatement(v, "n") + " GROUP BY n.obj_val ORDER BY 2 DESC, 1 LIMIT 1")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    name = rs.getString(1);
                }
            }
        }
        final String offers = "SELECT o.subj FROM kg_statement o JOIN kg_vocab ov ON ov.term_id = o.pred JOIN kg_entity p ON p.ent_rowid = o.subj"
                + " WHERE ov.kind = 2 AND ov.name = 'offers' AND o.obj_ent IN (" + in + ") AND " + KgReader.visibleStatement(v, "o") + " AND "
                + KgReader.visibleEntity(v, "p");
        final long providers = net.yacy.scoutro.knowledge.store.KgStore.queryLong(c, "SELECT count(DISTINCT subj) FROM (" + offers + ")");
        final long withoutProvider = net.yacy.scoutro.knowledge.store.KgStore.queryLong(c, "SELECT count(*) FROM kg_entity x WHERE x.ent_rowid IN ("
                + in + ") AND NOT EXISTS (SELECT 1 FROM kg_statement o JOIN kg_vocab ov ON ov.term_id = o.pred JOIN kg_entity p ON p.ent_rowid"
                + " = o.subj WHERE ov.kind = 2 AND ov.name = 'offers' AND o.obj_ent = x.ent_rowid AND " + KgReader.visibleStatement(v, "o")
                + " AND " + KgReader.visibleEntity(v, "p") + ")");
        final JSONArray collections = new JSONArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT k.name, count(DISTINCT es.ent_rowid) FROM kg_entity_scope es JOIN kg_collection k"
                + " ON k.coll_id = es.coll_id WHERE es.ent_rowid IN (" + in + ")" + (v.all() ? "" : " AND es.coll_id IN (" + BusinessView.ids(v)
                        + ")") + " GROUP BY k.name ORDER BY 2 DESC, 1"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                collections.put(KgJson.obj("name", rs.getString(1), "services", rs.getLong(2)));
            }
        }
        // places of the providers: their stated locality, else the places they are in (each provider counted once per place)
        final JSONArray places = new JSONArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT place, count(DISTINCT subj) FROM (SELECT s.subj, s.obj_val place FROM kg_statement s"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE v.kind = 2 AND v.name = 'locality' AND s.subj IN (" + offers + ") AND "
                + KgReader.visibleStatement(v, "s") + " UNION SELECT s.subj, n.obj_val FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " JOIN kg_entity pl ON pl.ent_rowid = s.obj_ent JOIN kg_statement n ON n.subj = s.obj_ent JOIN kg_vocab nv ON nv.term_id = n.pred"
                + " WHERE v.kind = 2 AND v.name = 'in_place' AND nv.kind = 2 AND nv.name = 'name' AND s.subj IN (" + offers + ") AND "
                + KgReader.visibleStatement(v, "s") + " AND " + KgReader.visibleStatement(v, "n") + " AND " + KgReader.visibleEntity(v, "pl")
                + ") GROUP BY place ORDER BY 2 DESC, 1 LIMIT " + MAX_PLACES); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                places.put(KgJson.obj("name", rs.getString(1), "providers", rs.getLong(2)));
            }
        }
        final long withPrice = net.yacy.scoutro.knowledge.store.KgStore.queryLong(c, "SELECT count(DISTINCT s.subj) FROM kg_statement s"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE v.kind = 2 AND v.name = 'price' AND s.subj IN (" + in + ") AND "
                + KgReader.visibleStatement(v, "s") + " AND " + this.reader.currentStatement(v, "s", now));
        final long current = net.yacy.scoutro.knowledge.store.KgStore.queryLong(c, "SELECT count(DISTINCT s.subj) FROM kg_statement s"
                + " WHERE s.subj IN (" + in + ") AND " + KgReader.visibleStatement(v, "s") + " AND " + this.reader.currentStatement(v, "s", now));
        return KgJson.obj("key", key, "name", name, "services", services.size(), "providers", providers, "without_provider", withoutProvider,
                "collections", collections, "places", places, "with_price", withPrice, "with_current_source", current, "truncated",
                services.size() >= MAX_GROUP_SERVICES);
    }

    /** Visible price statements per service: {current, all}. */
    private Map<Long, long[]> priceCounts(final Connection c, final List<Long> services, final Viewer v, final long now) throws SQLException {
        final Map<Long, long[]> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT s.subj, count(*), sum(CASE WHEN " + this.reader.currentStatement(v, "s", now)
                + " THEN 1 ELSE 0 END) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred WHERE v.kind = 2 AND v.name = 'price' AND s.subj IN ("
                + EntityContexts.in(services) + ") AND " + KgReader.visibleStatement(v, "s") + " GROUP BY s.subj"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getLong(1), new long[] {rs.getLong(3), rs.getLong(2)});
            }
        }
        return out;
    }

    private static int bind(final PreparedStatement ps, final List<Object> args, final int first) throws SQLException {
        int i = first;
        for (final Object a : args) {
            ps.setString(i++, String.valueOf(a));
        }
        return i;
    }

    private static JSONObject page(final int offset, final int limit, final long total, final JSONArray items) {
        return KgJson.obj("schema", BusinessView.SCHEMA, "offset", offset, "limit", limit, "total", total, "items", items, "note", NOTE);
    }
}
