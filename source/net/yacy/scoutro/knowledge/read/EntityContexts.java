/*
 *  EntityContexts
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * The context of a page of entities for one viewer, in a few batched queries
 * instead of one round per entity (package 6.1): the hosts of the viewer's
 * pages behind an entity, its visible collections, its places, its quality,
 * sources and last confirmation, and for a service its providers (who
 * {@code offers} it), for a job its employer. Everything is computed over the
 * viewer's evidence only, like {@link KgReader}; a provider is listed only if
 * the viewer sees both the provider and the {@code offers} fact.
 * <p>
 * Services stay what the model makes them: one entity per provider (key
 * {@code service_name} within the provider's domain). Two providers of "SAP"
 * are two services; this class only shows each with its own provider.
 */
final class EntityContexts {

    static final int MAX_HOSTS = 5;
    static final int MAX_PLACES = 3;
    static final int MAX_PROVIDERS = 5;
    private static final int CHUNK = 500;

    private EntityContexts() {
    }

    /** What the viewer sees around one entity. */
    static final class Ctx {
        final long rowid;
        final Set<String> hosts = new LinkedHashSet<>();
        final Set<String> collections = new TreeSet<>();
        final Set<String> places = new LinkedHashSet<>();
        String quality = "stale";
        long sources;
        Long lastConfirmed;

        Ctx(final long rowid) {
            this.rowid = rowid;
        }
    }

    /** The contexts of {@code rows} (no providers); entities without visible evidence keep the defaults. */
    static Map<Long, Ctx> of(final Connection c, final Collection<Long> rows, final Viewer v, final KgReader reader, final long now)
            throws SQLException {
        final Map<Long, Ctx> out = new LinkedHashMap<>();
        for (final Long r : rows) {
            out.putIfAbsent(r, new Ctx(r));
        }
        final List<Long> all = new ArrayList<>(out.keySet());
        for (int from = 0; from < all.size(); from += CHUNK) {
            final String in = in(all.subList(from, Math.min(all.size(), from + CHUNK)));
            // hosts of the viewer's pages, the most-used first
            try (PreparedStatement ps = c.prepareStatement("SELECT s.subj, min(d.url), count(DISTINCT d.doc_rowid) FROM kg_statement s"
                    + " JOIN kg_evidence e ON e.stmt_rowid = s.stmt_rowid JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE s.subj IN (" + in
                    + ") AND " + KgReader.visibleDoc(v, "d") + " GROUP BY s.subj, d.host_id ORDER BY s.subj, 3 DESC, 2");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final Ctx x = out.get(rs.getLong(1));
                    final String h = KgReader.host(rs.getString(2));
                    if (h != null && x.hosts.size() < MAX_HOSTS) {
                        x.hosts.add(h);
                    }
                }
            }
            // quality, current sources and last confirmation (the rules of the entity filter, KgReader#entityQuality)
            final String current = reader.currentDoc("d", now);
            try (PreparedStatement ps = c.prepareStatement("SELECT s.subj, max(CASE WHEN " + current + " AND e.certainty = 1 AND d.state = 1"
                    + " AND e.tier <> 3 THEN 1 ELSE 0 END), max(CASE WHEN " + current + " THEN 1 ELSE 0 END), count(DISTINCT CASE WHEN " + current
                    + " THEN d.doc_rowid END), max(CASE WHEN " + current + " THEN d.loaded_at END) FROM kg_statement s JOIN kg_evidence e"
                    + " ON e.stmt_rowid = s.stmt_rowid JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE s.subj IN (" + in + ") AND "
                    + KgReader.visibleDoc(v, "d") + " GROUP BY s.subj"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final Ctx x = out.get(rs.getLong(1));
                    x.quality = rs.getInt(2) == 1 ? "supported" : rs.getInt(3) == 1 ? "uncertain" : "stale";
                    x.sources = rs.getLong(4);
                    x.lastConfirmed = rs.getObject(5) == null ? null : rs.getLong(5);
                }
            }
            // the viewer's collections of the entity
            try (PreparedStatement ps = c.prepareStatement("SELECT es.ent_rowid, k.name FROM kg_entity_scope es JOIN kg_collection k"
                    + " ON k.coll_id = es.coll_id WHERE es.ent_rowid IN (" + in + ")" + (v.all() ? "" : " AND es.coll_id IN ("
                            + BusinessView.ids(v) + ")")); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.get(rs.getLong(1)).collections.add(rs.getString(2));
                }
            }
            // places: the stated locality, else the names of the places it is in
            try (PreparedStatement ps = c.prepareStatement("SELECT s.subj, s.obj_val FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                    + " WHERE v.kind = 2 AND v.name = 'locality' AND s.subj IN (" + in + ") AND " + KgReader.visibleStatement(v, "s")
                    + " ORDER BY s.subj, s.quality, s.current_sources DESC"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    addPlace(out.get(rs.getLong(1)), rs.getString(2));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT s.subj, n.obj_val FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                    + " JOIN kg_entity p ON p.ent_rowid = s.obj_ent JOIN kg_statement n ON n.subj = s.obj_ent JOIN kg_vocab nv ON nv.term_id = n.pred"
                    + " WHERE v.kind = 2 AND v.name = 'in_place' AND nv.kind = 2 AND nv.name = 'name' AND s.subj IN (" + in + ") AND "
                    + KgReader.visibleStatement(v, "s") + " AND " + KgReader.visibleStatement(v, "n") + " AND " + KgReader.visibleEntity(v, "p")
                    + " ORDER BY s.subj, n.quality, n.current_sources DESC"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final Ctx x = out.get(rs.getLong(1));
                    if (x.places.isEmpty()) {
                        addPlace(x, rs.getString(2));
                    }
                }
            }
        }
        return out;
    }

    private static void addPlace(final Ctx x, final String place) {
        if (place != null && !place.isEmpty() && x.places.size() < MAX_PLACES) {
            x.places.add(place);
        }
    }

    /**
     * The providers of services (the visible subjects of a visible
     * {@code offers} fact) and the employers of jobs (the visible object of
     * {@code hiring_organization}), best-supported first; keyed by service or
     * job. Every other type gets nothing.
     */
    static Map<Long, List<Long>> providers(final Connection c, final Map<Long, String> typed, final Viewer v) throws SQLException {
        final Map<Long, List<Long>> out = new LinkedHashMap<>();
        final List<Long> services = new ArrayList<>();
        final List<Long> jobs = new ArrayList<>();
        for (final Map.Entry<Long, String> e : typed.entrySet()) {
            if (Vocabulary.SERVICE.equals(e.getValue())) {
                services.add(e.getKey());
                out.put(e.getKey(), new ArrayList<>());
            } else if (Vocabulary.JOB.equals(e.getValue())) {
                jobs.add(e.getKey());
                out.put(e.getKey(), new ArrayList<>());
            }
        }
        for (int from = 0; from < services.size(); from += CHUNK) {
            try (PreparedStatement ps = c.prepareStatement("SELECT s.obj_ent, s.subj FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                    + " JOIN kg_entity p ON p.ent_rowid = s.subj WHERE v.kind = 2 AND v.name = 'offers' AND s.obj_ent IN ("
                    + in(services.subList(from, Math.min(services.size(), from + CHUNK))) + ") AND " + KgReader.visibleStatement(v, "s")
                    + " AND " + KgReader.visibleEntity(v, "p") + " ORDER BY s.obj_ent, s.quality, s.current_sources DESC, s.stmt_rowid");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final List<Long> l = out.get(rs.getLong(1));
                    if (!l.contains(rs.getLong(2))) {
                        l.add(rs.getLong(2));
                    }
                }
            }
        }
        for (int from = 0; from < jobs.size(); from += CHUNK) {
            try (PreparedStatement ps = c.prepareStatement("SELECT s.subj, s.obj_ent FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                    + " JOIN kg_entity p ON p.ent_rowid = s.obj_ent WHERE v.kind = 2 AND v.name = 'hiring_organization' AND s.subj IN ("
                    + in(jobs.subList(from, Math.min(jobs.size(), from + CHUNK))) + ") AND " + KgReader.visibleStatement(v, "s")
                    + " AND " + KgReader.visibleEntity(v, "p") + " ORDER BY s.subj, s.quality, s.current_sources DESC, s.stmt_rowid");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final List<Long> l = out.get(rs.getLong(1));
                    if (!l.contains(rs.getLong(2))) {
                        l.add(rs.getLong(2));
                    }
                }
            }
        }
        return out;
    }

    /** Types of entities by row. */
    static Map<Long, String> types(final Connection c, final Collection<Long> rows) throws SQLException {
        final Map<Long, String> out = new LinkedHashMap<>();
        final List<Long> all = new ArrayList<>(new LinkedHashSet<>(rows));
        for (int from = 0; from < all.size(); from += CHUNK) {
            try (PreparedStatement ps = c.prepareStatement("SELECT e.ent_rowid, t.name FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type"
                    + " WHERE e.ent_rowid IN (" + in(all.subList(from, Math.min(all.size(), from + CHUNK))) + ")");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong(1), rs.getString(2));
                }
            }
        }
        return out;
    }

    /**
     * The full context of a page of entities: hosts, collections, places,
     * quality, and for services and jobs their providers with the same
     * context; as JSON per row ({@code context} of a list item).
     */
    static Map<Long, JSONObject> json(final Connection c, final Collection<Long> rows, final Viewer v, final KgReader reader, final long now)
            throws SQLException {
        final Map<Long, String> types = types(c, rows);
        final Map<Long, List<Long>> providers = providers(c, types, v);
        final Set<Long> providerRows = new LinkedHashSet<>();
        for (final List<Long> l : providers.values()) {
            providerRows.addAll(l.subList(0, Math.min(MAX_PROVIDERS, l.size())));
        }
        final Map<Long, Ctx> own = of(c, rows, v, reader, now);
        final Map<Long, Ctx> theirs = of(c, providerRows, v, reader, now);
        final Map<Long, String> providerTypes = types(c, providerRows);
        final Map<Long, JSONObject> out = new LinkedHashMap<>();
        for (final Long r : rows) {
            final Ctx x = own.get(r);
            final JSONObject o = ctxJson(x);
            final List<Long> l = providers.get(r);
            if (l != null) {
                final JSONArray items = new JSONArray();
                for (final Long p : l.subList(0, Math.min(MAX_PROVIDERS, l.size()))) {
                    final JSONObject item = KgJson.obj("id", KgReader.publicId(c, p), "name", KgReader.visibleName(c, p, v), "type",
                            providerTypes.get(p));
                    final JSONObject pc = ctxJson(theirs.get(p));
                    for (final String k : new String[] {"hosts", "collections", "places", "quality"}) {
                        KgJson.put(item, k, pc.opt(k));
                    }
                    items.put(item);
                }
                KgJson.put(o, "relation", Vocabulary.JOB.equals(types.get(r)) ? Vocabulary.HIRING_ORGANIZATION : Vocabulary.OFFERS);
                KgJson.put(o, "providers", items);
                KgJson.put(o, "provider_count", l.size());
            }
            out.put(r, o);
        }
        return out;
    }

    static JSONObject ctxJson(final Ctx x) {
        return KgJson.obj("hosts", new JSONArray(x.hosts), "collections", new JSONArray(x.collections), "places", new JSONArray(x.places),
                "quality", x.quality, "sources", x.sources, "last_confirmed", KgReader.iso(x.lastConfirmed));
    }

    static String in(final Collection<Long> rows) {
        final StringBuilder sb = new StringBuilder();
        for (final Long r : rows) {
            sb.append(sb.length() == 0 ? "" : ",").append(r.longValue());
        }
        return sb.length() == 0 ? "-1" : sb.toString();
    }
}
