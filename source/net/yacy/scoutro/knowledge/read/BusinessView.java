/*
 *  BusinessView
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Values;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.vocab.Categories;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;
import net.yacy.scoutro.knowledge.vocab.Nace;

/**
 * The business view of one entity for one viewer (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 23): overview, industry, services, prices, contacts, relations, jobs,
 * audiences, suggested matches and sources, each section only if it has
 * content, everything computed over the viewer's evidence (like
 * {@link KgReader}).
 * <p>
 * Status of an element: {@code current} (supported, confidence at least
 * 0.6), {@code uncertain}, {@code conflicting}, {@code stale}; a price is
 * also {@code expired} after its stated validity, {@code stale} after
 * {@code prices.staleDays} from its stated date or last confirmation, and
 * {@code conflicting} when another current price of the same service, unit,
 * kind and condition states a different amount (both are shown, nothing is
 * averaged). A job is {@code open} or {@code ended} (deadline passed or no
 * current source) and hidden {@code jobs.endedVisibleDays} after it ended.
 * The three audience layers are kept apart: {@code declared} (what the
 * organisation says), {@code observed} (customers and references named as
 * facts) and {@code suggested} (derived, never a fact).
 */
public final class BusinessView {

    public static final String SCHEMA = "scoutro.kg.business.v1";
    static final double CONFIRMED = 0.6;
    static final int MAX_SERVICES = 100;
    static final int MAX_JOBS = 100;
    static final int MAX_RELATIONS = 200;
    static final int MAX_DERIVED = 100;

    static final Set<String> CONTACT_PREDICATES = Set.of(Vocabulary.PHONE, Vocabulary.FAX, Vocabulary.EMAIL, Vocabulary.WEBSITE,
            Vocabulary.CONTACT_FORM, Vocabulary.CONTACT_POINT, Vocabulary.OFFICE_HOURS, Vocabulary.OPENING_HOURS, Vocabulary.SOCIAL_PROFILE,
            Vocabulary.DIRECTIONS, Vocabulary.ADDRESS, Vocabulary.POSTAL_CODE, Vocabulary.LOCALITY, Vocabulary.GEO);
    static final Set<String> DECLARED_AUDIENCE = Set.of(Vocabulary.CUSTOMER_TYPE, Vocabulary.AUDIENCE_SEGMENT, Vocabulary.TARGET_INDUSTRY,
            Vocabulary.TARGET_CATEGORY, Vocabulary.COMPANY_SIZE, Vocabulary.SERVICE_AREA, Vocabulary.NEED);
    /** Relations shown in other sections, not under "relations". */
    static final Set<String> NOT_RELATIONS = Set.of(Vocabulary.OFFERS, Vocabulary.HIRING_ORGANIZATION, Vocabulary.IN_PLACE,
            Vocabulary.SERVES_PLACE, Vocabulary.JOB_LOCATION);

    private final KgReader reader;
    private final KgConfig cfg;

    public BusinessView(final KgReader reader) {
        this.reader = reader;
        this.cfg = reader.config();
    }

    /** One statement as the viewer sees it, with its confidence and the collections of its visible evidence. */
    static final class Fact {
        final KgReader.Stat stat;
        double confidence;
        final Set<Integer> collections = new TreeSet<>();
        final List<String[]> sources = new ArrayList<>(); // {doc_id, url}

        Fact(final KgReader.Stat stat) {
            this.stat = stat;
        }

        String status() {
            switch (this.stat.quality) {
                case "supported":
                    return this.confidence >= CONFIRMED ? "current" : "uncertain";
                case "conflicting":
                    return "conflicting";
                case "uncertain":
                    return "uncertain";
                default:
                    return "stale";
            }
        }
    }

    // ----------------------------------------------------------------- load

    /** The viewer's statements of an entity (as subject, or as object for {@code incoming}), with confidence and sources. */
    List<Fact> facts(final Connection c, final long ent, final boolean incoming, final Collection<String> predicates, final Viewer v,
            final long now, final int limit) throws SQLException {
        final StringBuilder sql = new StringBuilder("SELECT " + KgReader.STMT_COLUMNS + " FROM kg_statement s JOIN kg_vocab v"
                + " ON v.term_id = s.pred WHERE " + (incoming ? "s.obj_ent" : "s.subj") + " = ? AND ")
                .append(KgReader.visibleStatement(v, "s"));
        if (predicates != null) {
            sql.append(" AND v.name IN (");
            int i = 0;
            for (final String p : predicates) {
                sql.append(i++ == 0 ? "?" : ",?");
            }
            sql.append(')');
        }
        sql.append(" ORDER BY v.name, s.stmt_rowid LIMIT ").append(limit);
        final List<KgReader.Stat> stats = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setLong(1, ent);
            int i = 2;
            if (predicates != null) {
                for (final String p : predicates) {
                    ps.setString(i++, p);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.add(KgReader.stat(rs));
                }
            }
        }
        return facts(c, stats, v, now);
    }

    /**
     * The viewer's statements of one predicate whose object is one of these
     * entities, ordered by subject (package 6.2: the {@code offers} of the
     * services of a group, each its own statement to its own service).
     */
    List<Fact> factsTo(final Connection c, final List<Long> objects, final String predicate, final Viewer v, final long now,
            final int limit) throws SQLException {
        if (objects.isEmpty()) {
            return new ArrayList<>();
        }
        final List<KgReader.Stat> stats = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + KgReader.STMT_COLUMNS + " FROM kg_statement s JOIN kg_vocab v"
                + " ON v.term_id = s.pred WHERE s.obj_ent IN (" + EntityContexts.in(objects) + ") AND v.name = ? AND "
                + KgReader.visibleStatement(v, "s") + " ORDER BY s.subj, s.stmt_rowid LIMIT " + limit)) {
            ps.setString(1, predicate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.add(KgReader.stat(rs));
                }
            }
        }
        return facts(c, stats, v, now);
    }

    private List<Fact> facts(final Connection c, final List<KgReader.Stat> stats, final Viewer v, final long now) throws SQLException {
        this.reader.compute(c, stats, v, now);
        final List<Fact> out = new ArrayList<>();
        final Map<Long, Fact> byRow = new HashMap<>();
        for (final KgReader.Stat s : stats) {
            if (s.visible) {
                final Fact f = new Fact(s);
                out.add(f);
                byRow.put(s.rowid, f);
            }
        }
        evidence(c, byRow, v);
        return out;
    }

    /** Confidence (the best of the visible evidence; the kind's default for evidence without one), collections and sources. */
    private static void evidence(final Connection c, final Map<Long, Fact> byRow, final Viewer v) throws SQLException {
        if (byRow.isEmpty()) {
            return;
        }
        final List<Long> rows = new ArrayList<>(byRow.keySet());
        for (int from = 0; from < rows.size(); from += 500) {
            final StringBuilder in = new StringBuilder();
            for (final Long r : rows.subList(from, Math.min(rows.size(), from + 500))) {
                in.append(in.length() == 0 ? "" : ",").append(r.longValue());
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT e.stmt_rowid, coalesce(e.confidence, CASE e.kind WHEN 1 THEN 0.9 WHEN 2 THEN 0.8"
                    + " WHEN 3 THEN 0.7 ELSE 0.5 END * CASE e.certainty WHEN 2 THEN 0.5 ELSE 1 END), d.doc_id, d.url, dc.coll_id"
                    + " FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_doc_collection dc ON dc.doc_rowid = d.doc_rowid"
                    + " WHERE e.stmt_rowid IN (" + in + ") AND " + KgReader.visibleDoc(v, "d")
                    + (v.all() ? "" : " AND dc.coll_id IN (" + ids(v) + ")"));
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final Fact f = byRow.get(rs.getLong(1));
                    f.confidence = Math.max(f.confidence, rs.getDouble(2));
                    f.collections.add(rs.getInt(5));
                    if (f.sources.size() < 10 && f.sources.stream().noneMatch(x -> x[0].equals(safe(rs, 3)))) {
                        f.sources.add(new String[] {rs.getString(3), rs.getString(4)});
                    }
                }
            }
        }
    }

    private static String safe(final ResultSet rs, final int i) {
        try {
            return rs.getString(i);
        } catch (final SQLException e) {
            return "";
        }
    }

    static String ids(final Viewer v) {
        final StringBuilder sb = new StringBuilder();
        for (final Integer i : v.collections()) {
            sb.append(sb.length() == 0 ? "" : ",").append(i.intValue());
        }
        return sb.length() == 0 ? "-1" : sb.toString();
    }

    // ------------------------------------------------------------- the view

    /** The business view of an entity, or a redirect, or null if it is invisible. */
    public JSONObject entity(final String id, final Viewer v, final boolean hiddenJobs) throws KgException, KgReader.NotFound {
        final long now = this.reader.now();
        final Object r = this.reader.store().read(c -> {
            final long[] ent = KgReader.entityRow(c, id, v);
            if (ent == null) {
                return null;
            }
            if (ent[1] != 0L) {
                return KgJson.obj("schema", SCHEMA, "redirect", KgReader.publicId(c, ent[1]));
            }
            return view(c, ent[0], v, now, hiddenJobs);
        });
        if (r == null) {
            throw new KgReader.NotFound("entity " + id);
        }
        return (JSONObject) r;
    }

    JSONObject view(final Connection c, final long ent, final Viewer v, final long now, final boolean hiddenJobs) throws SQLException {
        loadCollections(c);
        final Categories cats = KgVocabularies.get().categories;
        final Nace nace = KgVocabularies.get().nace;
        final JSONObject overview = this.reader.summary(c, ent, v, now, true);
        final List<Fact> own = facts(c, ent, false, null, v, now, KgReader.SUMMARY_STATEMENTS);
        final List<Fact> in = facts(c, ent, true, null, v, now, KgReader.SUMMARY_STATEMENTS);
        final Map<String, List<Fact>> byPredicate = new LinkedHashMap<>();
        for (final Fact f : own) {
            byPredicate.computeIfAbsent(f.stat.predicate, k -> new ArrayList<>()).add(f);
        }
        final JSONObject out = KgJson.obj("schema", SCHEMA, "id", overview.optString("id"), "overview", overview);
        final Set<String> docs = new LinkedHashSet<>();

        // industry: main and secondary NACE codes, the Scoutro groups
        final JSONObject industry = industry(byPredicate.get(Vocabulary.INDUSTRY), byPredicate.get(Vocabulary.INDUSTRY_CATEGORY), nace, cats,
                docs);
        if (industry != null) {
            KgJson.put(out, "industry", industry);
        }
        // services of the entity (and, for a service, its own categories and prices)
        final JSONArray services = new JSONArray();
        final JSONArray prices = new JSONArray();
        for (final Fact f : byPredicate.getOrDefault(Vocabulary.OFFERS, List.of())) {
            if (services.length() >= MAX_SERVICES || f.stat.objEnt == null || !visible(c, f.stat.objEnt, v)) {
                continue;
            }
            services.put(service(c, f, v, now, cats, prices, docs));
        }
        if ("service".equals(overview.optString("type"))) {
            final JSONArray mine = priceItems(byPredicate.get(Vocabulary.PRICE), overview.optString("id"), overview.optString("name"), null, now,
                    docs);
            for (int i = 0; i < mine.length(); i++) {
                prices.put(mine.opt(i));
            }
            KgJson.put(out, "categories", codes(byPredicate.get(Vocabulary.CATEGORY), cats, nace, docs));
        } else {
            // a general price range of the business
            final JSONArray general = priceItems(byPredicate.get(Vocabulary.PRICE), null, null, null, now, docs);
            for (int i = 0; i < general.length(); i++) {
                prices.put(general.opt(i));
            }
        }
        markConflicts(prices);
        if (services.length() > 0) {
            KgJson.put(out, "services", services);
        }
        if (prices.length() > 0) {
            KgJson.put(out, "prices", prices);
        }
        // contacts (organisation contacts only: persons are never stored, O7)
        final JSONObject contacts = new JSONObject();
        for (final String p : CONTACT_PREDICATES) {
            final List<Fact> l = byPredicate.get(p);
            if (l != null) {
                KgJson.put(contacts, p, literals(l, cats, nace, docs));
            }
        }
        if (contacts.length() > 0) {
            KgJson.put(out, "contacts", contacts);
        }
        // relations between organisations and the other relations, both directions; derived ones apart
        final JSONArray relations = new JSONArray();
        relations(c, own, false, v, relations, docs);
        relations(c, in, true, v, relations, docs);
        final JSONObject derived = derived(c, ent, v, Set.of(DerivedKind.LINKED_TO, DerivedKind.SAME_OPERATOR));
        if (relations.length() > 0 || derived.length() > 0) {
            KgJson.put(out, "relations", KgJson.obj("facts", relations, "derived", derived));
        }
        // jobs of the entity as employer
        final JSONArray jobs = new JSONArray();
        int hidden = 0;
        for (final Fact f : in) {
            if (!Vocabulary.HIRING_ORGANIZATION.equals(f.stat.predicate) || jobs.length() >= MAX_JOBS || !visible(c, f.stat.subj, v)) {
                continue;
            }
            final JSONObject job = job(c, f.stat.subj, v, now, cats, nace, docs);
            if (job == null) {
                continue; // jobs are switched off for the job's collections
            }
            if (job.optBoolean("hidden") && !hiddenJobs) {
                hidden++;
                continue;
            }
            jobs.put(job);
        }
        if ("job".equals(overview.optString("type"))) {
            final JSONObject self = job(c, ent, v, now, cats, nace, docs);
            if (self != null) {
                KgJson.put(out, "job", self);
            }
        }
        if (jobs.length() > 0 || hidden > 0) {
            KgJson.put(out, "jobs", KgJson.obj("items", jobs, "hidden_ended", hidden));
        }
        // audiences: declared, observed, suggested; never mixed
        final JSONObject declared = new JSONObject();
        for (final String p : DECLARED_AUDIENCE) {
            final List<Fact> l = byPredicate.get(p);
            if (l != null) {
                KgJson.put(declared, p, literals(l, cats, nace, docs));
            }
        }
        final JSONArray places = new JSONArray();
        for (final Fact f : byPredicate.getOrDefault(Vocabulary.SERVES_PLACE, List.of())) {
            if (f.stat.objEnt != null && visible(c, f.stat.objEnt, v)) {
                places.put(relationItem(c, f, false, v));
                collect(f, docs);
            }
        }
        if (places.length() > 0) {
            KgJson.put(declared, "serves_place", places);
        }
        final JSONArray observed = new JSONArray();
        for (final Fact f : in) {
            if ((Vocabulary.CUSTOMER_OF.equals(f.stat.predicate) || Vocabulary.REFERENCE_FOR.equals(f.stat.predicate))
                    && visible(c, f.stat.subj, v)) {
                observed.put(relationItem(c, f, true, v));
                collect(f, docs);
            }
        }
        final JSONObject suggested = derived(c, ent, v, Set.of(DerivedKind.SUGGESTED_CUSTOMER));
        if (declared.length() > 0 || observed.length() > 0 || suggested.length() > 0) {
            KgJson.put(out, "audiences", KgJson.obj("declared", declared, "observed", observed, "suggested",
                    suggested.optJSONArray("suggested_customer") == null ? new JSONArray() : suggested.optJSONArray("suggested_customer")));
        }
        final JSONObject matches = derived(c, ent, v, Set.of(DerivedKind.SUGGESTED_CUSTOMER, DerivedKind.SUGGESTED_PARTNER));
        if (matches.length() > 0) {
            KgJson.put(out, "suggested_matches", matches);
        }
        // the remaining statements of the entity (names, identifiers, descriptions, ...) are in the overview
        KgJson.put(out, "sources", sources(c, docs, v));
        KgJson.put(out, "as_of", KgJson.obj("epoch", this.reader.store().epoch(), "seq",
                net.yacy.scoutro.knowledge.store.KgStore.queryLong(c, "SELECT coalesce(max(seq), 0) FROM kg_change")));
        return out;
    }

    // ------------------------------------------------------------- sections

    private JSONObject industry(final List<Fact> codes, final List<Fact> groups, final Nace nace, final Categories cats, final Set<String> docs) {
        final List<Fact> current = new ArrayList<>();
        for (final Fact f : codes == null ? List.<Fact>of() : codes) {
            if (!"stale".equals(f.stat.quality) && f.stat.objVal != null) {
                current.add(f);
            }
        }
        if (current.isEmpty() && (groups == null || groups.isEmpty())) {
            return null;
        }
        // the best-supported code is the main industry; two equally supported classes give their safe common level
        current.sort((a, b) -> Double.compare(score(b), score(a)));
        final JSONObject o = new JSONObject();
        final JSONArray secondary = new JSONArray();
        if (!current.isEmpty()) {
            final Fact best = current.get(0);
            String mainCode = best.stat.objVal;
            String basis = "best_supported";
            if (current.size() > 1 && Math.abs(score(current.get(1)) - score(best)) < 0.05 && !current.get(1).stat.objVal.equals(mainCode)) {
                final String common = nace.common(mainCode, current.get(1).stat.objVal);
                if (common != null) {
                    mainCode = common;
                    basis = "common_level";
                }
            }
            final JSONObject main = naceItem(mainCode, nace);
            KgJson.put(main, "basis", basis);
            if ("best_supported".equals(basis)) {
                fact(main, best);
            } else {
                KgJson.put(main, "confidence", round(Math.min(best.confidence, current.get(1).confidence)));
                KgJson.put(main, "status", best.status());
            }
            KgJson.put(o, "main", main);
            for (final Fact f : current) {
                collect(f, docs);
                if ("best_supported".equals(basis) && f == best) {
                    continue;
                }
                final JSONObject item = naceItem(f.stat.objVal, nace);
                fact(item, f);
                secondary.put(item);
            }
        }
        KgJson.put(o, "secondary", secondary);
        KgJson.put(o, "categories", codes(groups, cats, nace, docs));
        return o;
    }

    private static double score(final Fact f) {
        return f.confidence * (1.0 + 0.1 * Math.min(10, f.stat.docs.size())) * ("supported".equals(f.stat.quality) ? 1.0 : 0.5);
    }

    private static JSONObject naceItem(final String code, final Nace nace) {
        final Nace.Code c = nace.get(code);
        return KgJson.obj("classification", "NACE Rev. 2.1 / WZ 2025", "code", code, "label", c == null ? null : c.label, "level",
                c == null ? null : new String[] {"", "section", "division", "group", "class"}[c.level], "path", new JSONArray(nace.path(code)));
    }

    private JSONObject service(final Connection c, final Fact offer, final Viewer v, final long now, final Categories cats, final JSONArray prices,
            final Set<String> docs) throws SQLException {
        final long s = offer.stat.objEnt;
        final List<Fact> facts = facts(c, s, false, List.of(Vocabulary.NAME, Vocabulary.CATEGORY, Vocabulary.DESCRIPTION, Vocabulary.PRICE),
                v, now, 200);
        String name = null;
        final List<Fact> categories = new ArrayList<>();
        final List<Fact> descriptions = new ArrayList<>();
        final List<Fact> p = new ArrayList<>();
        for (final Fact f : facts) {
            switch (f.stat.predicate) {
                case Vocabulary.NAME:
                    if (name == null || "supported".equals(f.stat.quality)) {
                        name = f.stat.objVal;
                    }
                    break;
                case Vocabulary.CATEGORY:
                    categories.add(f);
                    break;
                case Vocabulary.DESCRIPTION:
                    descriptions.add(f);
                    break;
                default:
                    p.add(f);
            }
        }
        final String id = KgReader.publicId(c, s);
        final JSONObject o = KgJson.obj("id", id, "name", name, "offer", offer.stat.publicId);
        fact(o, offer);
        collect(offer, docs);
        KgJson.put(o, "categories", codes(categories, cats, KgVocabularies.get().nace, docs));
        if (!descriptions.isEmpty()) {
            KgJson.put(o, "description", descriptions.get(0).stat.objVal);
        }
        final JSONArray mine = priceItems(p, id, name, offer.collections, now, docs);
        KgJson.put(o, "prices", mine.length());
        for (int i = 0; i < mine.length(); i++) {
            prices.put(mine.opt(i));
        }
        return o;
    }

    /** Price items with their status (current, uncertain, stale, expired; conflicts are marked over the whole list). */
    JSONArray priceItems(final List<Fact> facts, final String serviceId, final String serviceName, final Set<Integer> collections, final long now,
            final Set<String> docs) {
        final JSONArray out = new JSONArray();
        if (facts == null) {
            return out;
        }
        final LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
        for (final Fact f : facts) {
            final JSONObject value = Values.json(f.stat.objVal);
            if (value == null) {
                continue;
            }
            final JSONObject item = KgJson.obj("statement", f.stat.publicId, "service", serviceId, "service_name", serviceName, "value", value);
            fact(item, f);
            String status = f.status();
            final LocalDate validThrough = Values.lastDay(value.optString("valid_through", null));
            final LocalDate stated = Values.lastDay(value.optString("as_of", null));
            final LocalDate confirmed = f.stat.lastConfirmed == null ? null
                    : Instant.ofEpochMilli(f.stat.lastConfirmed).atZone(ZoneOffset.UTC).toLocalDate();
            final LocalDate asOf = stated != null ? stated : confirmed;
            long staleDays = this.cfg.priceStaleMillis / 86_400_000L;
            for (final Integer coll : f.collections) {
                staleDays = Math.min(staleDays, this.cfg.priceStaleMillis(collectionName(coll)) / 86_400_000L);
            }
            KgJson.put(item, "as_of", asOf == null ? null : asOf.toString());
            KgJson.put(item, "as_of_basis", stated != null ? "stated" : "last_confirmed");
            if (validThrough != null) {
                KgJson.put(item, "valid_through", validThrough.toString());
            }
            if (validThrough != null && validThrough.isBefore(today)) {
                status = "expired"; // the source's own validity wins
            } else if (validThrough == null && asOf != null && asOf.plusDays(staleDays).isBefore(today) && !"stale".equals(status)) {
                status = "stale";
                KgJson.put(item, "stale_since", asOf.plusDays(staleDays).toString());
            }
            KgJson.put(item, "status", status);
            KgJson.put(item, "stale_after_days", staleDays);
            out.put(item);
            collect(f, docs);
        }
        return out;
    }

    private Map<Integer, String> collectionNames = new HashMap<>();

    /** Loads the collection names once per view (on the view's own connection). */
    private void loadCollections(final Connection c) throws SQLException {
        final Map<Integer, String> m = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT coll_id, name FROM kg_collection"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                m.put(rs.getInt(1), rs.getString(2));
            }
        }
        this.collectionNames = m;
    }

    private String collectionName(final Integer coll) {
        return this.collectionNames.get(coll);
    }

    /**
     * Two current prices of one service that differ only in the amount are a
     * conflict: both stay, both are marked, with each other's statement.
     */
    static void markConflicts(final JSONArray prices) {
        final Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        for (int i = 0; i < prices.length(); i++) {
            final JSONObject p = prices.optJSONObject(i);
            final String status = p.optString("status");
            if (!"current".equals(status) && !"uncertain".equals(status) && !"conflicting".equals(status)) {
                continue;
            }
            final JSONObject v = p.optJSONObject("value");
            final String key = p.optString("service") + "|" + v.optString("currency") + "|" + v.optString("unit") + "|" + v.optString("unit_text")
                    + "|" + v.optString("kind") + "|" + v.optString("care_level") + "|" + v.optString("scope") + "|" + v.optString("eligible")
                    + "|" + v.optString("conditions");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        for (final List<JSONObject> g : groups.values()) {
            final Set<String> amounts = new TreeSet<>();
            for (final JSONObject p : g) {
                final JSONObject v = p.optJSONObject("value");
                amounts.add(v.optString("amount") + "|" + v.optString("min") + "|" + v.optString("max"));
            }
            if (amounts.size() > 1) {
                for (final JSONObject p : g) {
                    KgJson.put(p, "status", "conflicting");
                    final JSONArray others = new JSONArray();
                    for (final JSONObject q : g) {
                        if (q != p) {
                            others.put(q.optString("statement"));
                        }
                    }
                    KgJson.put(p, "conflicts_with", others);
                }
            }
        }
    }

    /** One job with its status: open, or ended (deadline passed or no current source), hidden some days after it ended. */
    private JSONObject job(final Connection c, final long job, final Viewer v, final long now, final Categories cats, final Nace nace,
            final Set<String> docs) throws SQLException {
        final List<Fact> facts = facts(c, job, false, null, v, now, 200);
        final Set<Integer> colls = new TreeSet<>();
        for (final Fact f : facts) {
            colls.addAll(f.collections);
        }
        boolean shown = false;
        for (final Integer coll : colls) {
            shown |= this.cfg.jobsShown(collectionName(coll));
        }
        if (!shown) {
            return null;
        }
        final JSONObject o = KgJson.obj("id", KgReader.publicId(c, job));
        boolean current = false;
        Long lastSeen = null;
        String validThrough = null;
        for (final Fact f : facts) {
            current |= !"stale".equals(f.stat.quality);
            // the last load of its pages, also of a page that is gone (lastConfirmed counts current pages only)
            if (f.stat.lastSeen != null && (lastSeen == null || f.stat.lastSeen > lastSeen)) {
                lastSeen = f.stat.lastSeen;
            }
            collect(f, docs);
            switch (f.stat.predicate) {
                case Vocabulary.NAME:
                    if (!o.has("title") || "supported".equals(f.stat.quality)) {
                        KgJson.put(o, "title", f.stat.objVal);
                    }
                    break;
                case Vocabulary.VALID_THROUGH:
                    validThrough = f.stat.objVal;
                    KgJson.put(o, "valid_through", f.stat.objVal);
                    break;
                case Vocabulary.DATE_POSTED:
                case Vocabulary.START_DATE:
                case Vocabulary.OCCUPATIONAL_FIELD:
                    KgJson.put(o, f.stat.predicate, f.stat.objVal);
                    break;
                case Vocabulary.EMPLOYMENT_TYPE:
                case Vocabulary.INDUSTRY:
                case Vocabulary.APPLICATION_ROUTE:
                    if (!o.has(f.stat.predicate)) {
                        KgJson.put(o, f.stat.predicate, new JSONArray());
                    }
                    o.optJSONArray(f.stat.predicate).put(literal(f, cats, nace));
                    break;
                case Vocabulary.SALARY: {
                    final JSONObject value = Values.json(f.stat.objVal);
                    if (value != null) {
                        final JSONObject s = KgJson.obj("statement", f.stat.publicId, "value", value, "as_of", KgReader.iso(f.stat.lastConfirmed));
                        fact(s, f);
                        if (!o.has("salary")) {
                            KgJson.put(o, "salary", new JSONArray());
                        }
                        o.optJSONArray("salary").put(s);
                    }
                    break;
                }
                case Vocabulary.JOB_LOCATION:
                    if (f.stat.objEnt != null && visible(c, f.stat.objEnt, v)) {
                        KgJson.put(o, "location", entityRef(c, f.stat.objEnt, v));
                    }
                    break;
                default:
                    break;
            }
        }
        final LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
        final LocalDate deadline = Values.lastDay(validThrough);
        Long endedAt = null;
        if (deadline != null && deadline.isBefore(today)) {
            endedAt = deadline.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } else if (!current) {
            endedAt = lastSeen == null ? now : lastSeen; // the posting is gone: ended when it was last seen
        }
        KgJson.put(o, "status", endedAt == null ? "open" : "ended");
        KgJson.put(o, "ended_at", KgReader.iso(endedAt));
        KgJson.put(o, "last_confirmed", KgReader.iso(lastSeen));
        KgJson.put(o, "hidden", endedAt != null && now - endedAt > this.cfg.jobsEndedVisibleMillis);
        return o;
    }

    private void relations(final Connection c, final List<Fact> facts, final boolean incoming, final Viewer v, final JSONArray out,
            final Set<String> docs) throws SQLException {
        for (final Fact f : facts) {
            if (out.length() >= MAX_RELATIONS || f.stat.objEnt == null || NOT_RELATIONS.contains(f.stat.predicate)) {
                continue;
            }
            final long other = incoming ? f.stat.subj : f.stat.objEnt;
            if (!visible(c, other, v)) {
                continue; // the other side is not visible to the viewer: no trace of it
            }
            out.put(relationItem(c, f, incoming, v));
            collect(f, docs);
        }
    }

    private JSONObject relationItem(final Connection c, final Fact f, final boolean incoming, final Viewer v) throws SQLException {
        final long other = incoming ? f.stat.subj : f.stat.objEnt;
        final JSONObject o = KgJson.obj("statement", f.stat.publicId, "predicate", f.stat.predicate, "direction", incoming ? "in" : "out",
                "business", Vocabulary.BUSINESS_RELATIONS.contains(f.stat.predicate), "other", entityRef(c, other, v));
        fact(o, f);
        return o;
    }

    private static JSONObject entityRef(final Connection c, final long ent, final Viewer v) throws SQLException {
        String type = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT t.name FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type WHERE e.ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    type = rs.getString(1);
                }
            }
        }
        final String name = KgReader.visibleName(c, ent, v);
        return DisplayNames.put(KgJson.obj("id", KgReader.publicId(c, ent), "name", name, "type", type),
                name != null ? DisplayNames.known(c, ent, name, v) : DisplayNames.of(c, ent, type, null, null, v));
    }

    static boolean visible(final Connection c, final long ent, final Viewer v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kg_entity e WHERE e.ent_rowid = ? AND " + KgReader.visibleEntity(v, "e"))) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Kinds of derived rows by number (schema 4). */
    enum DerivedKind {
        LINKED_TO(1, "linked_to"), SAME_OPERATOR(2, "same_operator"), SUGGESTED_CUSTOMER(3, "suggested_customer"),
        SUGGESTED_PARTNER(4, "suggested_partner");

        final int code;
        final String label;

        DerivedKind(final int code, final String label) {
            this.code = code;
            this.label = label;
        }

        static DerivedKind of(final int code) {
            for (final DerivedKind k : values()) {
                if (k.code == code) {
                    return k;
                }
            }
            return null;
        }
    }

    /** SQL condition: derived row {@code alias} is visible (the viewer has both of its collections). */
    static String visibleDerived(final Viewer v, final String alias) {
        if (v.all()) {
            return "1";
        }
        final String ids = ids(v);
        return alias + ".coll_a IN (" + ids + ") AND " + alias + ".coll_b IN (" + ids + ")";
    }

    /** Derived rows of an entity by kind, both endpoints visible; a suggestion always labelled as one. */
    JSONObject derived(final Connection c, final long ent, final Viewer v, final Set<DerivedKind> kinds) throws SQLException {
        final JSONObject out = new JSONObject();
        final StringBuilder in = new StringBuilder();
        for (final DerivedKind k : kinds) {
            in.append(in.length() == 0 ? "" : ",").append(k.code);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT d.public_id, d.kind, d.a_ent, d.b_ent, d.confidence, d.reason, d.basis,"
                + " d.computed_at FROM kg_derived d JOIN kg_entity a ON a.ent_rowid = d.a_ent JOIN kg_entity b ON b.ent_rowid = d.b_ent"
                + " WHERE (d.a_ent = ? OR d.b_ent = ?) AND d.kind IN (" + in + ") AND a.status = 1 AND b.status = 1 AND "
                + visibleDerived(v, "d") + " ORDER BY d.kind, d.confidence DESC, d.der_rowid LIMIT " + MAX_DERIVED)) {
            ps.setLong(1, ent);
            ps.setLong(2, ent);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final DerivedKind kind = DerivedKind.of(rs.getInt(2));
                    final boolean mine = rs.getLong(3) == ent;
                    final long other = mine ? rs.getLong(4) : rs.getLong(3);
                    if (!visible(c, other, v)) {
                        continue;
                    }
                    String key = kind.label;
                    if (kind == DerivedKind.SUGGESTED_CUSTOMER && !mine) {
                        key = "as_possible_customer"; // this entity could be a customer of the other
                    }
                    if (!out.has(key)) {
                        KgJson.put(out, key, new JSONArray());
                    }
                    out.optJSONArray(key).put(derivedJson(rs, other, mine, c, v));
                }
            }
        }
        return out;
    }

    static JSONObject derivedJson(final ResultSet rs, final long other, final boolean mine, final Connection c, final Viewer v) throws SQLException {
        final DerivedKind kind = DerivedKind.of(rs.getInt(2));
        final JSONObject basis = Values.json(rs.getString(7));
        return KgJson.obj("id", rs.getString(1), "kind", kind.label, "direction", mine ? "out" : "in", "other", entityRef(c, other, v),
                "confidence", round(rs.getDouble(5)), "reason", rs.getString(6), "basis", basis, "computed_at", KgReader.iso(rs.getLong(8)),
                "fact", false, "label", kind == DerivedKind.LINKED_TO ? "weak_signal" : kind == DerivedKind.SAME_OPERATOR ? "derived" : "suggestion");
    }

    private JSONArray codes(final List<Fact> facts, final Categories cats, final Nace nace, final Set<String> docs) {
        return literals(facts == null ? List.of() : facts, cats, nace, docs);
    }

    private JSONArray literals(final List<Fact> facts, final Categories cats, final Nace nace, final Set<String> docs) {
        final JSONArray out = new JSONArray();
        for (final Fact f : facts) {
            out.put(literal(f, cats, nace));
            collect(f, docs);
        }
        return out;
    }

    /** A literal with its label (codes), its parsed value (JSON) and its status. */
    static JSONObject literal(final Fact f, final Categories cats, final Nace nace) {
        final String p = f.stat.predicate;
        final String value = f.stat.objVal;
        final JSONObject o = KgJson.obj("statement", f.stat.publicId, "value", value);
        final Vocabulary.Predicate pred = Vocabulary.predicate(p);
        if (pred != null && Vocabulary.T_JSON.equals(pred.datatype)) {
            KgJson.put(o, "value", Values.json(value));
        } else if (Vocabulary.INDUSTRY.equals(p) || Vocabulary.TARGET_INDUSTRY.equals(p)) {
            KgJson.put(o, "label", nace.label(value));
            KgJson.put(o, "classification", "NACE Rev. 2.1 / WZ 2025");
        } else if (pred != null && Vocabulary.T_CODE.equals(pred.datatype)) {
            final Categories.Entry e = cats.code(p, value);
            if (e != null) {
                KgJson.put(o, "label_de", e.de);
                KgJson.put(o, "label_en", e.en);
                if (e.nace != null) {
                    KgJson.put(o, "nace", e.nace);
                }
            }
        }
        fact(o, f);
        return o;
    }

    static void fact(final JSONObject o, final Fact f) {
        KgJson.put(o, "status", f.status());
        KgJson.put(o, "quality", f.stat.quality);
        KgJson.put(o, "confidence", round(f.confidence));
        KgJson.put(o, "kinds", new JSONArray(f.stat.kinds));
        KgJson.put(o, "last_confirmed", KgReader.iso(f.stat.lastConfirmed));
        KgJson.put(o, "sources", f.stat.docs.size());
        final JSONArray src = new JSONArray();
        for (final String[] s : f.sources) {
            src.put(KgJson.obj("doc_id", s[0], "url", s[1]));
        }
        KgJson.put(o, "source_docs", src);
    }

    private static void collect(final Fact f, final Set<String> docs) {
        for (final String[] s : f.sources) {
            if (docs.size() < 200) {
                docs.add(s[0]);
            }
        }
    }

    private static JSONArray sources(final Connection c, final Set<String> docs, final Viewer v) throws SQLException {
        final JSONArray out = new JSONArray();
        for (final String d : docs) {
            try (PreparedStatement ps = c.prepareStatement("SELECT d.doc_rowid, d.url, d.state, d.loaded_at FROM kg_doc d WHERE d.doc_id = ? AND "
                    + KgReader.visibleDoc(v, "d"))) {
                ps.setString(1, d);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        final int state = rs.getInt(3);
                        out.put(KgJson.obj("doc_id", d, "url", rs.getString(2), "state", state >= 1 && state <= 4 ? KgReader.STATE[state] : null,
                                "loaded_at", KgReader.iso(rs.getObject(4) == null ? null : rs.getLong(4)), "collections",
                                KgReader.docCollections(c, rs.getLong(1), v)));
                    }
                }
            }
        }
        return out;
    }

    static double round(final double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
