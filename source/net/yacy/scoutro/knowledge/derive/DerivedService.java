/*
 *  DerivedService
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

package net.yacy.scoutro.knowledge.derive;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgIds;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.Values;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.scoutro.knowledge.store.KgChangeLog;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;
import net.yacy.scoutro.knowledge.vocab.Nace;

/**
 * The derived layer of vocabulary 2 (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 23):
 * what Scoutro concludes from facts, kept apart from them in
 * {@code kg_derived} and never upgraded to a fact.
 * <ul>
 * <li>{@code linked_to}: the site of A links to the site of B (YaCy's
 * outbound links of A's pages, B the declared operator of the target
 * domain); a weak signal (confidence 0.2), never a business relation;</li>
 * <li>{@code same_operator}: two facilities or organisations with the same
 * operator or carrier (groups up to {@code sameOperator.maxGroup});</li>
 * <li>{@code suggested_customer}: B could be a customer of A: A declares a
 * target industry or category that B's industry or services meet, in A's
 * declared service area if A names one;</li>
 * <li>{@code suggested_partner}: A and B address the same audience segment
 * or target industry with different services, in the same area.</li>
 * </ul>
 * Every row names the facts of both sides ({@code basis}: statement IDs) and
 * the two collections they come from; a viewer sees it only with both
 * ({@code kg_change} kind 3 likewise). A pass recomputes everything and
 * applies the difference; it is enrichment, so the manual pause and every
 * growth refusal stop it (the cascades of deleted entities still apply).
 */
public final class DerivedService {

    public static final int KIND_LINKED_TO = 1;
    public static final int KIND_SAME_OPERATOR = 2;
    public static final int KIND_SUGGESTED_CUSTOMER = 3;
    public static final int KIND_SUGGESTED_PARTNER = 4;
    static final double LINKED_TO_CONFIDENCE = 0.2;
    static final int BATCH = 300;
    static final int MAX_LINK_SAMPLES = 5;
    /** Seekers and candidates read per pass at most (bounded memory). */
    static final int MAX_ROWS = 200_000;

    /** One derived row as computed. */
    static final class Row {
        final int kind;
        final long a;
        final long b;
        final int collA;
        final int collB;
        double confidence;
        String reason;
        JSONObject basis;

        Row(final int kind, final long a, final long b, final int collA, final int collB) {
            this.kind = kind;
            this.a = a;
            this.b = b;
            this.collA = collA;
            this.collB = collB;
        }

        String key() {
            return this.kind + ":" + this.a + ":" + this.b + ":" + this.collA + ":" + this.collB;
        }
    }

    /** What a pass did. */
    public static final class Result {
        public int computed;
        public int inserted;
        public int updated;
        public int deleted;
        public final Map<String, Integer> byKind = new LinkedHashMap<>();
        public String refused;
        public long millis;

        public JSONObject json() {
            return KgJson.obj("computed", this.computed, "inserted", this.inserted, "updated", this.updated, "deleted", this.deleted,
                    "byKind", new JSONObject(this.byKind), "refused", this.refused, "durationMs", this.millis);
        }
    }

    private final KgConfig cfg;
    private final KgStore store;
    private final LongSupplier clock;
    private volatile long lastRun;
    private volatile Result last;

    public DerivedService(final KgConfig cfg, final KgStore store, final LongSupplier clock) {
        this.cfg = cfg;
        this.store = store;
        this.clock = clock;
        try {
            final String at = store.read(c -> KgStore.getMeta(c, KgSchema.META_DERIVED_AT));
            this.lastRun = at == null ? 0L : Long.parseLong(at);
        } catch (final KgException | NumberFormatException e) {
            this.lastRun = 0L;
        }
    }

    public Result last() {
        return this.last;
    }

    public long lastRun() {
        return this.lastRun;
    }

    /** True if a pass is due (enabled, interval passed). */
    public boolean due(final long now) {
        return this.cfg.derivedEnabled && now - this.lastRun >= this.cfg.derivedIntervalMillis;
    }

    /** Runs a pass if one is due and growth is allowed; never throws. */
    public Result tick(final long now) {
        if (!due(now)) {
            return null;
        }
        final String refusal = this.store.growthRefusal();
        if (refusal != null) {
            final Result r = new Result();
            r.refused = refusal;
            this.last = r;
            return r;
        }
        try {
            return run();
        } catch (final KgException e) {
            final Result r = new Result();
            r.refused = e.code();
            this.last = r;
            this.lastRun = now; // retried at the next interval
            return r;
        }
    }

    /** One full pass: compute everything, apply the difference. */
    public Result run() throws KgException {
        final long start = this.clock.getAsLong();
        final Nace nace = KgVocabularies.get().nace;
        final Map<String, Row> want = this.store.read(c -> compute(c, nace));
        final Result r = apply(want, start);
        r.millis = this.clock.getAsLong() - start;
        this.lastRun = start;
        this.last = r;
        return r;
    }

    // ------------------------------------------------------------- compute

    Map<String, Row> compute(final Connection c, final Nace nace) throws SQLException {
        final Map<String, Row> out = new LinkedHashMap<>();
        if (this.cfg.derivedEnabled) {
            linkedTo(c, out);
            sameOperator(c, out);
            final Graph g = Graph.read(c, nace);
            suggestedCustomers(g, nace, out);
            suggestedPartners(g, out);
        }
        return out;
    }

    private static final int MAX_LINKED_TO = 20_000;

    /** {@code linked_to}: outbound links of the pages of A's site to B's site, both declared operators. */
    private void linkedTo(final Connection c, final Map<String, Row> out) throws SQLException {
        final Map<String, Long> operators = new HashMap<>();
        final Map<Long, Set<Integer>> scopes = new HashMap<>();
        final Map<String, int[]> counts = new HashMap<>();
        final Map<String, List<String>> samples = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT l.domain, l.n, d.doc_id, d.url, d.doc_rowid FROM kg_doc_link l"
                + " JOIN kg_doc d ON d.doc_rowid = l.doc_rowid WHERE d.state = 1 LIMIT " + MAX_ROWS);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final String target = rs.getString(1);
                final String source = Normalizers.registrableDomain(host(rs.getString(4)));
                if (source == null || source.equals(target)) {
                    continue;
                }
                final Long a = operator(c, operators, source);
                final Long b = operator(c, operators, target);
                if (a == null || b == null || a.equals(b)) {
                    continue;
                }
                final Set<Integer> docColls = docCollections(c, rs.getLong(5));
                final Set<Integer> sa = scope(c, scopes, a);
                final Set<Integer> sb = scope(c, scopes, b);
                for (final int ca : docColls) {
                    if (!sa.contains(ca)) {
                        continue;
                    }
                    for (final int cb : sb) {
                        final String k = KIND_LINKED_TO + ":" + a + ":" + b + ":" + ca + ":" + cb;
                        counts.computeIfAbsent(k, x -> new int[1])[0] += Math.max(1, rs.getInt(2));
                        final List<String> s = samples.computeIfAbsent(k, x -> new ArrayList<>());
                        if (s.size() < MAX_LINK_SAMPLES && !s.contains(rs.getString(3))) {
                            s.add(rs.getString(3));
                        }
                        if (!out.containsKey(k) && out.size() < MAX_LINKED_TO) {
                            final Row row = new Row(KIND_LINKED_TO, a, b, ca, cb);
                            row.confidence = LINKED_TO_CONFIDENCE;
                            out.put(k, row);
                            row.basis = KgJson.obj("source_domain", source, "target_domain", target);
                        }
                    }
                }
            }
        }
        for (final Map.Entry<String, int[]> e : counts.entrySet()) {
            final Row row = out.get(e.getKey());
            if (row != null) {
                KgJson.put(row.basis, "links", e.getValue()[0]);
                KgJson.put(row.basis, "docs", new JSONArray(samples.get(e.getKey())));
                row.reason = "pages of " + row.basis.optString("source_domain") + " link to " + row.basis.optString("target_domain")
                        + " (" + e.getValue()[0] + " links; webgraph, weak signal)";
            }
        }
    }

    /** The only declared operator of a registrable domain (cached); null if none or several. */
    private Long operator(final Connection c, final Map<String, Long> cache, final String domain) throws SQLException {
        if (cache.containsKey(domain)) {
            return cache.get(domain);
        }
        Long found = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT k.ent_rowid FROM kg_entity_key k JOIN kg_vocab v ON v.term_id = k.scheme"
                + " JOIN kg_entity e ON e.ent_rowid = k.ent_rowid WHERE v.kind = 3 AND v.name = ? AND k.scope = ? AND e.status = 1 LIMIT 2")) {
            ps.setString(1, Vocabulary.SITE_OPERATOR);
            ps.setString(2, Vocabulary.ORGANIZATION + "@" + domain);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    found = rs.getLong(1);
                    if (rs.next()) {
                        found = null;
                    }
                }
            }
        }
        cache.put(domain, found);
        return found;
    }

    private static Set<Integer> docCollections(final Connection c, final long doc) throws SQLException {
        final Set<Integer> out = new TreeSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT coll_id FROM kg_doc_collection WHERE doc_rowid = ?")) {
            ps.setLong(1, doc);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        }
        return out;
    }

    private static Set<Integer> scope(final Connection c, final Map<Long, Set<Integer>> cache, final long ent) throws SQLException {
        Set<Integer> s = cache.get(ent);
        if (s == null) {
            s = new TreeSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT coll_id FROM kg_entity_scope WHERE ent_rowid = ?")) {
                ps.setLong(1, ent);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        s.add(rs.getInt(1));
                    }
                }
            }
            cache.put(ent, s);
        }
        return s;
    }

    private static String host(final String url) {
        try {
            return url == null ? null : new URI(url).getHost();
        } catch (final Exception e) {
            return null;
        }
    }

    /** {@code same_operator}: entities operated or carried by one organisation, per collection where both facts are visible. */
    private void sameOperator(final Connection c, final Map<String, Row> out) throws SQLException {
        // operator:collection -> [entity, statement public id]
        final Map<String, List<Object[]>> groups = new LinkedHashMap<>();
        final Map<String, Long> operatorOf = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT s.subj, s.obj_ent, s.public_id, ss.coll_id FROM kg_statement s"
                + " JOIN kg_vocab v ON v.term_id = s.pred JOIN kg_statement_scope ss ON ss.stmt_rowid = s.stmt_rowid"
                + " JOIN kg_entity o ON o.ent_rowid = s.obj_ent JOIN kg_entity sub ON sub.ent_rowid = s.subj"
                + " WHERE v.kind = 2 AND v.name IN (?, ?) AND s.obj_ent IS NOT NULL AND s.quality <> 4 AND o.status = 1 AND sub.status = 1"
                + " ORDER BY s.subj, ss.coll_id, s.obj_ent LIMIT " + MAX_ROWS)) {
            ps.setString(1, Vocabulary.OPERATES);
            ps.setString(2, Vocabulary.CARRIER_OF);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final String k = rs.getLong(1) + ":" + rs.getInt(4);
                    operatorOf.put(k, rs.getLong(1));
                    groups.computeIfAbsent(k, x -> new ArrayList<>()).add(new Object[] {rs.getLong(2), rs.getString(3)});
                }
            }
        }
        for (final Map.Entry<String, List<Object[]>> g : groups.entrySet()) {
            final List<Object[]> members = g.getValue();
            if (members.size() < 2 || members.size() > this.cfg.sameOperatorMaxGroup) {
                continue;
            }
            final int coll = Integer.parseInt(g.getKey().substring(g.getKey().indexOf(':') + 1));
            final String operator = publicId(c, operatorOf.get(g.getKey()));
            for (int i = 0; i < members.size(); i++) {
                for (int j = 0; j < members.size(); j++) {
                    final long x = (Long) members.get(i)[0];
                    final long y = (Long) members.get(j)[0];
                    if (x >= y) {
                        continue; // symmetric: stored once, lower row first
                    }
                    final Row row = new Row(KIND_SAME_OPERATOR, x, y, coll, coll);
                    row.confidence = 0.8;
                    row.basis = KgJson.obj("operator", operator, "a", new JSONArray(List.of(members.get(i)[1])),
                            "b", new JSONArray(List.of(members.get(j)[1])));
                    row.reason = "both operated or carried by " + operator;
                    out.putIfAbsent(row.key(), row);
                }
            }
        }
    }

    // --------------------------------------------------------- suggestions

    /** B could be a customer of A: A's declared target meets B's industry or services, in A's declared area. */
    private void suggestedCustomers(final Graph g, final Nace nace, final Map<String, Row> out) {
        int total = count(out, KIND_SUGGESTED_CUSTOMER) + count(out, KIND_SUGGESTED_PARTNER);
        for (final Graph.Ent a : g.seekers()) {
            if (total >= this.cfg.matchesMax) {
                break;
            }
            final List<Row> mine = new ArrayList<>();
            for (final Graph.Fact target : a.targets) {
                final List<Graph.Fact> candidates = target.predicate.equals(Vocabulary.TARGET_INDUSTRY) ? g.industryWithin(target.value)
                        : g.offeringCategory(target.value);
                for (final Graph.Fact bf : candidates) {
                    final Graph.Ent b = bf.ent;
                    if (b.rowid == a.rowid || !b.business() || g.related(a.rowid, b.rowid)) {
                        continue;
                    }
                    final Graph.AreaMatch area = g.area(a, b);
                    if (area.declared && !area.confirmed && !area.unknown) {
                        continue; // A names its area and B is shown to be outside it
                    }
                    double conf = target.predicate.equals(Vocabulary.TARGET_INDUSTRY) ? 0.35 + 0.05 * levelOf(nace, target.value) : 0.55;
                    conf += area.confirmed ? 0.15 : area.declared ? -0.1 : 0.0;
                    conf += a.b2b ? 0.05 : 0.0;
                    conf = Math.max(0.2, Math.min(0.75, Math.round(conf * 100.0) / 100.0));
                    final Row row = new Row(KIND_SUGGESTED_CUSTOMER, a.rowid, b.rowid, target.coll, bf.coll);
                    row.confidence = conf;
                    final JSONArray aIds = new JSONArray().put(target.publicId);
                    if (area.aFact != null) {
                        aIds.put(area.aFact);
                    }
                    final JSONArray bIds = new JSONArray().put(bf.publicId);
                    if (area.bFact != null) {
                        bIds.put(area.bFact);
                    }
                    row.basis = KgJson.obj("a", aIds, "b", bIds, "match", KgJson.obj("rule", target.predicate, "target", target.value,
                            "found", bf.value, "area", area.what, "area_confirmed", area.confirmed, "b2b", a.b2b));
                    row.reason = target.predicate + " " + target.value + " meets " + bf.predicate + " " + bf.value
                            + (area.confirmed ? "; area " + area.what : area.declared ? "; area not confirmed" : "; no area declared");
                    mine.add(row);
                }
            }
            total += keepBest(mine, out);
        }
    }

    /** 0 (section) to 3 (class): a finer target is a better reason. */
    private static int levelOf(final Nace nace, final String code) {
        final Nace.Code c = nace.get(code);
        return c == null ? 0 : c.level - 1;
    }

    /** A and B serve the same audience segment or target industry with different services, in the same area. */
    private void suggestedPartners(final Graph g, final Map<String, Row> out) {
        int total = count(out, KIND_SUGGESTED_CUSTOMER) + count(out, KIND_SUGGESTED_PARTNER);
        final Map<String, List<Graph.Fact>> byAudience = g.byAudience();
        final Map<Long, List<Row>> perEntity = new HashMap<>();
        for (final Map.Entry<String, List<Graph.Fact>> e : byAudience.entrySet()) {
            final List<Graph.Fact> facts = e.getValue();
            if (facts.size() < 2 || facts.size() > 200) {
                continue; // an audience everyone names says nothing about a partnership
            }
            for (int i = 0; i < facts.size() && total < this.cfg.matchesMax; i++) {
                for (int j = i + 1; j < facts.size() && total < this.cfg.matchesMax; j++) {
                    final Graph.Fact fa = facts.get(i);
                    final Graph.Fact fb = facts.get(j);
                    Graph.Ent a = fa.ent;
                    Graph.Ent b = fb.ent;
                    if (a.rowid == b.rowid || !a.business() || !b.business() || g.related(a.rowid, b.rowid)) {
                        continue;
                    }
                    if (a.categories.isEmpty() || b.categories.isEmpty() || !Collections.disjoint(a.categories, b.categories)) {
                        continue; // complementary services only; the same services make competitors, not partners
                    }
                    final Graph.AreaMatch area = g.sharedArea(a, b);
                    if (!area.confirmed) {
                        continue;
                    }
                    Graph.Fact x = fa;
                    Graph.Fact y = fb;
                    if (a.rowid > b.rowid) {
                        final Graph.Ent t = a;
                        a = b;
                        b = t;
                        x = fb;
                        y = fa;
                    }
                    final Row row = new Row(KIND_SUGGESTED_PARTNER, a.rowid, b.rowid, x.coll, y.coll);
                    row.confidence = 0.45;
                    row.basis = KgJson.obj("a", new JSONArray().put(x.publicId), "b", new JSONArray().put(y.publicId), "match",
                            KgJson.obj("rule", x.predicate, "audience", e.getKey().substring(e.getKey().indexOf('|') + 1), "area", area.what,
                                    "area_confirmed", true));
                    row.reason = "both address " + x.value + " in " + area.what + " with different services";
                    perEntity.computeIfAbsent(a.rowid, k -> new ArrayList<>()).add(row);
                    total++;
                }
            }
        }
        for (final List<Row> rows : perEntity.values()) {
            keepBest(rows, out);
        }
    }

    /** Keeps the best {@code matches.maxPerEntity} rows of one entity (by confidence); returns how many were added. */
    private int keepBest(final List<Row> rows, final Map<String, Row> out) {
        rows.sort((x, y) -> Double.compare(y.confidence, x.confidence));
        int n = 0;
        final Set<Long> seen = new HashSet<>();
        for (final Row r : rows) {
            if (n >= this.cfg.matchesMaxPerEntity) {
                break;
            }
            if (!seen.add(r.b * 4 + r.kind) && r.kind == KIND_SUGGESTED_CUSTOMER) {
                continue; // one suggestion per pair: the best reason
            }
            if (out.putIfAbsent(r.key(), r) == null) {
                n++;
            }
        }
        return n;
    }

    private static int count(final Map<String, Row> rows, final int kind) {
        int n = 0;
        for (final Row r : rows.values()) {
            if (r.kind == kind) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- apply

    private Result apply(final Map<String, Row> want, final long now) throws KgException {
        final Result r = new Result();
        r.computed = want.size();
        for (final Row row : want.values()) {
            r.byKind.merge(Vocabulary.DERIVED_KINDS.get(row.kind), 1, Integer::sum);
        }
        // existing rows: key -> {der_rowid, public_id, confidence, basis}
        final Map<String, Object[]> have = this.store.read(c -> {
            final Map<String, Object[]> m = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT der_rowid, kind, a_ent, b_ent, coll_a, coll_b, public_id, confidence, basis,"
                    + " reason FROM kg_derived"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(rs.getInt(2) + ":" + rs.getLong(3) + ":" + rs.getLong(4) + ":" + rs.getInt(5) + ":" + rs.getInt(6),
                            new Object[] {rs.getLong(1), rs.getString(7), rs.getDouble(8), rs.getString(9), rs.getString(10)});
                }
            }
            return m;
        });
        final List<Object[]> deletes = new ArrayList<>();
        for (final Map.Entry<String, Object[]> e : have.entrySet()) {
            if (!want.containsKey(e.getKey())) {
                final String[] k = e.getKey().split(":");
                deletes.add(new Object[] {e.getValue()[0], e.getValue()[1], Integer.parseInt(k[3]), Integer.parseInt(k[4])});
            }
        }
        for (int i = 0; i < deletes.size(); i += BATCH) {
            final List<Object[]> part = deletes.subList(i, Math.min(deletes.size(), i + BATCH));
            this.store.write(WriteClass.MAINTENANCE, 0L, tx -> {
                for (final Object[] d : part) {
                    try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_derived WHERE der_rowid = ?")) {
                        ps.setLong(1, (Long) d[0]);
                        if (ps.executeUpdate() > 0) {
                            final Set<Integer> scopes = new TreeSet<>(List.of((Integer) d[2], (Integer) d[3]));
                            KgChangeLog.record(tx, KgChangeLog.Kind.DERIVED, (String) d[1], KgChangeLog.Op.DELETE, null, scopes, Set.of(), now);
                            r.deleted++;
                        }
                    }
                }
                return null;
            });
        }
        final List<Row> upserts = new ArrayList<>();
        for (final Row row : want.values()) {
            final Object[] old = have.get(row.key());
            final String basis = clipBasis(row.basis);
            if (old == null || Math.abs((Double) old[2] - row.confidence) > 1e-9 || !basis.equals(old[3]) || !row.reason.equals(old[4])) {
                upserts.add(row);
            }
        }
        for (int i = 0; i < upserts.size(); i += BATCH) {
            final List<Row> part = upserts.subList(i, Math.min(upserts.size(), i + BATCH));
            this.store.write(WriteClass.GROWTH, 512L * part.size(), tx -> {
                for (final Row row : part) {
                    final String aId = publicId(tx, row.a);
                    final String bId = publicId(tx, row.b);
                    final String ca = collectionName(tx, row.collA);
                    final String cb = collectionName(tx, row.collB);
                    if (aId == null || bId == null || ca == null || cb == null) {
                        continue;
                    }
                    final String id = KgIds.derivedId(Vocabulary.DERIVED_KINDS.get(row.kind), aId, bId, ca, cb);
                    final boolean existed = have.containsKey(row.key());
                    try (PreparedStatement ps = tx.prepareStatement("INSERT INTO kg_derived (public_id, kind, a_ent, b_ent, coll_a, coll_b,"
                            + " confidence, reason, basis, computed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT (kind, a_ent, b_ent, coll_a, coll_b) DO UPDATE SET confidence = excluded.confidence,"
                            + " reason = excluded.reason, basis = excluded.basis, computed_at = excluded.computed_at")) {
                        ps.setString(1, id);
                        ps.setInt(2, row.kind);
                        ps.setLong(3, row.a);
                        ps.setLong(4, row.b);
                        ps.setInt(5, row.collA);
                        ps.setInt(6, row.collB);
                        ps.setDouble(7, row.confidence);
                        ps.setString(8, Normalizers.clip(row.reason, 500));
                        ps.setString(9, clipBasis(row.basis));
                        ps.setLong(10, now);
                        ps.executeUpdate();
                    }
                    final Set<Integer> scopes = new TreeSet<>(List.of(row.collA, row.collB));
                    KgChangeLog.record(tx, KgChangeLog.Kind.DERIVED, id, KgChangeLog.Op.UPSERT, null, scopes, scopes, now);
                    if (existed) {
                        r.updated++;
                    } else {
                        r.inserted++;
                    }
                }
                return null;
            });
        }
        this.store.write(WriteClass.SYSTEM, 0L, tx -> {
            KgStore.putMeta(tx, KgSchema.META_DERIVED_AT, Long.toString(now));
            return null;
        });
        return r;
    }

    private static String clipBasis(final JSONObject basis) {
        final String s = basis == null ? "{}" : basis.toString();
        return s.length() <= 4000 ? s : s.substring(0, 3990) + "\"}";
    }

    static String publicId(final Connection c, final Long ent) throws SQLException {
        if (ent == null) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT public_id FROM kg_entity WHERE ent_rowid = ? AND status = 1")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String collectionName(final Connection c, final int coll) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM kg_collection WHERE coll_id = ?")) {
            ps.setInt(1, coll);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---------------------------------------------------------------- graph

    /** The facts a pass needs, read once (bounded). */
    static final class Graph {

        /** One fact of an entity in one collection. */
        static final class Fact {
            final Ent ent;
            final String predicate;
            final String value;
            final String publicId;
            final int coll;

            Fact(final Ent ent, final String predicate, final String value, final String publicId, final int coll) {
                this.ent = ent;
                this.predicate = predicate;
                this.value = value;
                this.publicId = publicId;
                this.coll = coll;
            }
        }

        static final class Ent {
            final long rowid;
            final String type;
            final List<Fact> targets = new ArrayList<>();
            final List<Fact> industries = new ArrayList<>();
            final List<Fact> areas = new ArrayList<>();
            final List<Fact> audiences = new ArrayList<>();
            /** place_name keys of the entity's own places (in_place) -> statement id. */
            final Map<String, String> places = new LinkedHashMap<>();
            /** place_name keys the entity serves (serves_place) -> statement id. */
            final Map<String, String> serves = new LinkedHashMap<>();
            final Set<String> categories = new TreeSet<>();
            boolean b2b;

            Ent(final long rowid, final String type) {
                this.rowid = rowid;
                this.type = type;
            }

            boolean business() {
                return Vocabulary.ORGANIZATION.equals(this.type) || Vocabulary.FACILITY.equals(this.type);
            }
        }

        static final class AreaMatch {
            boolean declared;
            boolean confirmed;
            boolean unknown;
            String what;
            String aFact;
            String bFact;
        }

        final Map<Long, Ent> ents = new HashMap<>();
        /** category id -> facts "B offers a service of this category" (the service's category statement). */
        final Map<String, List<Fact>> offering = new HashMap<>();
        final Set<String> related = new HashSet<>();
        final Nace nace;

        private Graph(final Nace nace) {
            this.nace = nace;
        }

        static Graph read(final Connection c, final Nace nace) throws SQLException {
            final Graph g = new Graph(nace);
            final Map<Long, String> types = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT e.ent_rowid, t.name FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type"
                    + " WHERE e.status = 1 AND t.name IN ('organization', 'facility') LIMIT " + MAX_ROWS); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    types.put(rs.getLong(1), rs.getString(2));
                }
            }
            final String literal = "SELECT s.subj, v.name, s.obj_val, s.public_id, ss.coll_id FROM kg_statement s JOIN kg_vocab v"
                    + " ON v.term_id = s.pred JOIN kg_statement_scope ss ON ss.stmt_rowid = s.stmt_rowid WHERE v.kind = 2 AND s.quality <> 4"
                    + " AND v.name IN ('target_industry', 'target_category', 'industry', 'service_area', 'audience_segment', 'customer_type')"
                    + " LIMIT " + MAX_ROWS;
            try (PreparedStatement ps = c.prepareStatement(literal); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final String type = types.get(rs.getLong(1));
                    if (type == null || rs.getString(3) == null) {
                        continue;
                    }
                    final Ent e = g.ents.computeIfAbsent(rs.getLong(1), k -> new Ent(k, type));
                    final Fact f = new Fact(e, rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5));
                    switch (f.predicate) {
                        case Vocabulary.TARGET_INDUSTRY:
                        case Vocabulary.TARGET_CATEGORY:
                            e.targets.add(f);
                            break;
                        case Vocabulary.INDUSTRY:
                            e.industries.add(f);
                            break;
                        case Vocabulary.SERVICE_AREA:
                            e.areas.add(f);
                            break;
                        case Vocabulary.AUDIENCE_SEGMENT:
                            e.audiences.add(f);
                            break;
                        default:
                            if ("b2b".equals(f.value)) {
                                e.b2b = true;
                            }
                    }
                }
            }
            // places: in_place (own) and serves_place, with the place's key
            try (PreparedStatement ps = c.prepareStatement("SELECT s.subj, v.name, k.value, s.public_id FROM kg_statement s JOIN kg_vocab v"
                    + " ON v.term_id = s.pred JOIN kg_entity_key k ON k.ent_rowid = s.obj_ent JOIN kg_vocab ks ON ks.term_id = k.scheme"
                    + " WHERE v.kind = 2 AND v.name IN ('in_place', 'serves_place') AND ks.name = 'place_name' AND s.quality <> 4 LIMIT " + MAX_ROWS);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final String type = types.get(rs.getLong(1));
                    if (type == null) {
                        continue;
                    }
                    final Ent e = g.ents.computeIfAbsent(rs.getLong(1), k -> new Ent(k, type));
                    (Vocabulary.IN_PLACE.equals(rs.getString(2)) ? e.places : e.serves).put(rs.getString(3), rs.getString(4));
                }
            }
            // the categories of the services each organisation or facility offers
            try (PreparedStatement ps = c.prepareStatement("SELECT o.subj, c.obj_val, c.public_id, ss.coll_id FROM kg_statement o"
                    + " JOIN kg_vocab ov ON ov.term_id = o.pred JOIN kg_statement c ON c.subj = o.obj_ent JOIN kg_vocab cv ON cv.term_id = c.pred"
                    + " JOIN kg_statement_scope ss ON ss.stmt_rowid = c.stmt_rowid WHERE ov.kind = 2 AND ov.name = 'offers' AND cv.kind = 2"
                    + " AND cv.name = 'category' AND o.quality <> 4 AND c.quality <> 4 LIMIT " + MAX_ROWS); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final String type = types.get(rs.getLong(1));
                    if (type == null || rs.getString(2) == null) {
                        continue;
                    }
                    final Ent e = g.ents.computeIfAbsent(rs.getLong(1), k -> new Ent(k, type));
                    e.categories.add(rs.getString(2));
                    g.offering.computeIfAbsent(rs.getString(2), k -> new ArrayList<>())
                            .add(new Fact(e, Vocabulary.CATEGORY, rs.getString(2), rs.getString(3), rs.getInt(4)));
                }
            }
            // existing relations between two entities: a suggestion never repeats or contradicts a fact
            try (PreparedStatement ps = c.prepareStatement("SELECT s.subj, s.obj_ent FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                    + " WHERE v.kind = 2 AND s.obj_ent IS NOT NULL AND v.name IN ('operates', 'part_of', 'carrier_of', 'parent_of', 'subsidiary_of',"
                    + " 'member_of', 'association_member', 'partner_of', 'cooperation_with', 'customer_of', 'reference_for', 'supplier_of',"
                    + " 'service_provider_for', 'brand_of') LIMIT " + MAX_ROWS); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    g.related.add(Math.min(rs.getLong(1), rs.getLong(2)) + ":" + Math.max(rs.getLong(1), rs.getLong(2)));
                }
            }
            return g;
        }

        List<Ent> seekers() {
            final List<Ent> out = new ArrayList<>();
            for (final Ent e : this.ents.values()) {
                if (!e.targets.isEmpty() && e.business()) {
                    out.add(e);
                }
            }
            out.sort((x, y) -> Long.compare(x.rowid, y.rowid));
            return out;
        }

        /** Industry facts at {@code code} or finer. */
        List<Fact> industryWithin(final String code) {
            final List<Fact> out = new ArrayList<>();
            for (final Ent e : this.ents.values()) {
                for (final Fact f : e.industries) {
                    if (this.nace.within(f.value, code)) {
                        out.add(f);
                    }
                }
            }
            return out;
        }

        List<Fact> offeringCategory(final String category) {
            final List<Fact> l = this.offering.get(category);
            return l == null ? Collections.emptyList() : l;
        }

        boolean related(final long a, final long b) {
            return this.related.contains(Math.min(a, b) + ":" + Math.max(a, b));
        }

        /** Does B lie in A's declared area? national: B's places in that country; a place: B in that place; international: anywhere. */
        AreaMatch area(final Ent a, final Ent b) {
            final AreaMatch m = new AreaMatch();
            m.declared = !a.areas.isEmpty() || !a.serves.isEmpty();
            if (!m.declared) {
                m.unknown = true;
                return m;
            }
            for (final Fact f : a.areas) {
                final JSONObject area = Values.json(f.value);
                final String kind = area == null ? "" : area.optString("kind");
                if ("international".equals(kind)) {
                    m.confirmed = true;
                    m.what = "international";
                    m.aFact = f.publicId;
                    return m;
                }
            }
            for (final Map.Entry<String, String> s : a.serves.entrySet()) {
                final String key = s.getKey();
                final String[] p = key.split("\\|", 3);
                for (final Map.Entry<String, String> own : b.places.entrySet()) {
                    final boolean inCountry = p.length == 3 && "country".equals(p[1]) && own.getKey().startsWith(p[0] + "|");
                    if (own.getKey().equals(key) || inCountry) {
                        m.confirmed = true;
                        m.what = key;
                        m.aFact = s.getValue();
                        m.bFact = own.getValue();
                        return m;
                    }
                }
            }
            // a state or a radius cannot be checked against B's address: unknown, not refuted
            for (final Fact f : a.areas) {
                final JSONObject area = Values.json(f.value);
                final String kind = area == null ? "" : area.optString("kind");
                if ("state".equals(kind) || "radius".equals(kind)) {
                    m.unknown = true;
                }
            }
            if (b.places.isEmpty()) {
                m.unknown = true; // B's place is not known
            }
            return m;
        }

        /** A place both serve or are in. */
        AreaMatch sharedArea(final Ent a, final Ent b) {
            final AreaMatch m = new AreaMatch();
            final Set<String> pa = new LinkedHashSet<>(a.places.keySet());
            pa.addAll(a.serves.keySet());
            final Set<String> pb = new LinkedHashSet<>(b.places.keySet());
            pb.addAll(b.serves.keySet());
            for (final String k : pa) {
                if (!k.contains("|country|") && pb.contains(k)) {
                    m.declared = true;
                    m.confirmed = true;
                    m.what = k;
                    return m;
                }
            }
            return m;
        }

        /** Audience segments and target industries -> the facts naming them. */
        Map<String, List<Fact>> byAudience() {
            final Map<String, List<Fact>> out = new LinkedHashMap<>();
            final List<Ent> sorted = new ArrayList<>(this.ents.values());
            sorted.sort((x, y) -> Long.compare(x.rowid, y.rowid));
            for (final Ent e : sorted) {
                final Set<String> once = new HashSet<>();
                for (final Fact f : e.audiences) {
                    if (once.add("s|" + f.value)) {
                        out.computeIfAbsent("s|" + f.value, k -> new ArrayList<>()).add(f);
                    }
                }
                for (final Fact f : e.targets) {
                    if (Vocabulary.TARGET_INDUSTRY.equals(f.predicate) && once.add("i|" + f.value)) {
                        out.computeIfAbsent("i|" + f.value, k -> new ArrayList<>()).add(f);
                    }
                }
            }
            return out;
        }
    }
}
