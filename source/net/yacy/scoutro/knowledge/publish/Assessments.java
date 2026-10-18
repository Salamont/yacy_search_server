/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.publish;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgIds;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Claim;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * Durable assessments of graph facts (data quality programme WP-E, docs/SCOUTRO_KG_ASSESSMENTS.md).
 * <p>
 * An assessment says that the evidence of one source, host or registrable domain for a predicate (optionally one value,
 * subject and tier) is no fact of that source ({@code withdraw}) or supports another predicate ({@code reclassify}).
 * It acts on the support of a statement by evidence, never on the statement as such: the same fact from an
 * independent source stays. Applying it moves the matching evidence rows to {@code kg_evidence_withdrawn} (field by
 * field) and recomputes the statements through {@link Aggregates}; a statement without evidence leaves the active
 * graph, the observation archive marks its observations {@code withdrawn} or {@code corrected}. The publisher checks
 * every new claim against the active assessments of its source before writing it, so a re-extraction, a cached LLM
 * result or a recrawl never publishes it again; a claim found in a new revision of the source is held as well and
 * marks the assessment for review instead of lifting it silently. Revoking restores the copied rows unchanged where
 * the source is the same revision, and lets the rules read a changed source again.
 * <p>
 * Industries derived from a category ({@code derived:category:<id>}) depend on the category and the {@code offers} of
 * the same page: they go with them when nothing else on the page supports them.
 */
public final class Assessments {

    public static final String WITHDRAW = "withdraw";
    public static final String RECLASSIFY = "reclassify";
    public static final String SOURCE = "source";
    public static final String HOST = "host";
    public static final String DOMAIN = "domain";
    /** Evidence rows one assessment may move in one transaction. */
    public static final int MAX_APPLY = 5000;
    static final int MAX_SAMPLE = 20;
    private static final String DERIVED = "derived:category:";
    /** Set by a rebuild carry: the active assessments are applied to the rebuilt graph at the next start. */
    public static final String META_REAPPLY = "assessments_reapply";

    private Assessments() {
    }

    /** One assessment row. */
    public static final class Rule {
        public final String id;
        public final String action;
        public final String scopeKind;
        public final String scope;
        public final String predicate;
        /** Compare form of a literal or the object's public ID; null: every value. */
        public final String value;
        public final String subjectId;
        public final Integer tier;
        public final String targetPredicate;
        public final String reason;
        public final String basis;
        public final String status;
        public final String actor;
        public final long createdAt;
        public final Long revokedAt;
        public final String revokedBy;
        public final long blocked;
        public final boolean reviewNeeded;

        Rule(final ResultSet r) throws SQLException {
            this.id = r.getString("public_id");
            this.action = r.getString("action");
            this.scopeKind = r.getString("scope_kind");
            this.scope = r.getString("scope");
            this.predicate = r.getString("predicate");
            this.value = r.getString("value");
            this.subjectId = r.getString("subject_id");
            final int t = r.getInt("tier");
            this.tier = r.wasNull() ? null : t;
            this.targetPredicate = r.getString("target_predicate");
            this.reason = r.getString("reason");
            this.basis = r.getString("basis");
            this.status = r.getString("status");
            this.actor = r.getString("actor");
            this.createdAt = r.getLong("created_at");
            final long rv = r.getLong("revoked_at");
            this.revokedAt = r.wasNull() ? null : rv;
            this.revokedBy = r.getString("revoked_by");
            this.blocked = r.getLong("blocked");
            this.reviewNeeded = r.getInt("review_needed") == 1;
        }

        private Rule(final String id, final Draft d, final long now) {
            this.id = id;
            this.action = d.action;
            this.scopeKind = d.scopeKind;
            this.scope = d.scope;
            this.predicate = d.predicate;
            this.value = d.value;
            this.subjectId = d.subjectId;
            this.tier = d.tier;
            this.targetPredicate = d.targetPredicate;
            this.reason = d.reason;
            this.basis = d.basis;
            this.status = "active";
            this.actor = d.actor;
            this.createdAt = now;
            this.revokedAt = null;
            this.revokedBy = null;
            this.blocked = 0;
            this.reviewNeeded = false;
        }

        boolean withdraws() {
            return WITHDRAW.equals(this.action);
        }

        /** The object key the rule's value stands for (null: every value). */
        byte[] objectKey() {
            if (this.value == null) return null;
            final Vocabulary.Predicate p = Vocabulary.predicate(this.predicate);
            return KgIds.objectKey(p.relation ? "e:" + this.value : "v:" + p.datatype + ":" + this.value);
        }

        boolean matches(final String predicate, final byte[] objKey, final String subjectId, final int tier) {
            if (!this.predicate.equals(predicate)) return false;
            if (this.tier != null && this.tier != tier) return false;
            if (this.subjectId != null && !this.subjectId.equals(subjectId)) return false;
            return this.value == null || java.util.Arrays.equals(objectKey(), objKey);
        }

        boolean covers(final String docId, final String host) {
            switch (this.scopeKind) {
                case SOURCE:
                    return this.scope.equals(docId);
                case HOST:
                    return host != null && this.scope.equals(host);
                default:
                    return host != null && (host.equals(this.scope) || host.endsWith("." + this.scope));
            }
        }

        public JSONObject toJson() {
            return KgJson.obj("id", this.id, "action", this.action, "scope", KgJson.obj(this.scopeKind, this.scope), "predicate",
                    this.predicate, "value", this.value, "subject", this.subjectId, "tier", this.tier, "targetPredicate",
                    this.targetPredicate, "reason", this.reason, "basis", this.basis, "status", this.status, "actor", this.actor,
                    "createdAt", this.createdAt, "revokedAt", this.revokedAt, "revokedBy", this.revokedBy, "blockedClaims",
                    this.blocked, "reviewNeeded", this.reviewNeeded);
        }
    }

    /** A new assessment as requested, validated by {@link #validate}. */
    public static final class Draft {
        public String action;
        public String scopeKind;
        public String scope;
        public String predicate;
        public String value;
        public String subjectId;
        public Integer tier;
        public String targetPredicate;
        public String reason;
        public String basis = "manual";
        public String actor;

        /** Null if valid, else {field, message}. */
        public String[] validate() {
            if (!WITHDRAW.equals(this.action) && !RECLASSIFY.equals(this.action)) return new String[] {"action", "withdraw or reclassify."};
            if (this.scopeKind == null || this.scope == null) return new String[] {"scope", "One of source, host or domain is required."};
            final Vocabulary.Predicate p = this.predicate == null ? null : Vocabulary.predicate(this.predicate);
            if (p == null) return new String[] {"predicate", "A predicate of the graph vocabulary."};
            if (RECLASSIFY.equals(this.action)) {
                final Vocabulary.Predicate t = this.targetPredicate == null ? null : Vocabulary.predicate(this.targetPredicate);
                if (t == null || t.name.equals(p.name)) return new String[] {"targetPredicate", "Another predicate of the graph vocabulary."};
                if (t.relation != p.relation || !java.util.Objects.equals(t.datatype, p.datatype)) {
                    return new String[] {"targetPredicate", "The target needs the same kind of object (relation or the same datatype)."};
                }
            } else if (this.targetPredicate != null) {
                return new String[] {"targetPredicate", "Only for reclassify."};
            }
            if (this.value != null) {
                if (p.relation) {
                    if (!KgIds.isEntityId(this.value)) return new String[] {"value", "The object's entity ID (kge_...) for a relation."};
                } else {
                    final String clipped = Normalizers.clip(this.value.trim(), 1000);
                    if (clipped == null || clipped.isEmpty()) return new String[] {"value", "A non-empty value."};
                    this.value = Publisher.compareForm(p.datatype, clipped);
                }
            }
            if (this.subjectId != null && !KgIds.isEntityId(this.subjectId)) return new String[] {"subject", "An entity ID (kge_...)."};
            if (this.tier != null && (this.tier < 1 || this.tier > 3)) return new String[] {"tier", "1, 2 or 3."};
            if (this.reason == null || this.reason.trim().isEmpty() || this.reason.length() > 500) {
                return new String[] {"reason", "A reason of 1 to 500 characters (no personal data)."};
            }
            if (!"manual".equals(this.basis) && !"automatic".equals(this.basis)) return new String[] {"basis", "manual or automatic."};
            if (this.actor != null && this.actor.length() > 128) this.actor = this.actor.substring(0, 128);
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------------- reads

    /** The active assessments that cover a source (one indexed query; none on a graph without assessments). */
    public static List<Rule> active(final Connection tx, final String docId, final String host) throws SQLException {
        final String h = host == null ? null : host.toLowerCase(Locale.ROOT);
        final String domain = h == null ? null : Normalizers.registrableDomain(h);
        final List<Rule> out = new ArrayList<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT * FROM kg_assessment WHERE status = 'active' AND ((scope_kind = 'source'"
                + " AND scope = ?) OR (scope_kind = 'host' AND scope = ?) OR (scope_kind = 'domain' AND scope = ?)) ORDER BY assess_rowid")) {
            ps.setString(1, docId);
            ps.setString(2, h);
            ps.setString(3, domain);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) out.add(new Rule(r));
            }
        }
        return out;
    }

    public static Rule get(final Connection c, final String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM kg_assessment WHERE public_id = ?")) {
            ps.setString(1, id);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? new Rule(r) : null;
            }
        }
    }

    /** The assessments ({@code status}: active, revoked or null for both), newest first, with their copies. */
    public static JSONObject list(final Connection c, final String status, final int limit) throws SQLException {
        final JSONArray items = new JSONArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM kg_assessment" + (status == null ? "" : " WHERE status = ?")
                + " ORDER BY assess_rowid DESC LIMIT ?");
                PreparedStatement copies = c.prepareStatement("SELECT count(*), coalesce(sum(dependent), 0) FROM kg_evidence_withdrawn"
                        + " WHERE assessment_id = ?")) {
            int i = 1;
            if (status != null) ps.setString(i++, status);
            ps.setInt(i, limit);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    final Rule rule = new Rule(r);
                    final JSONObject o = rule.toJson();
                    copies.setString(1, rule.id);
                    try (ResultSet k = copies.executeQuery()) {
                        k.next();
                        KgJson.put(o, "withdrawnEvidence", k.getLong(1));
                        KgJson.put(o, "dependentEvidence", k.getLong(2));
                    }
                    items.put(o);
                }
            }
        }
        return KgJson.obj("items", items, "active", KgStore.queryLong(c, "SELECT count(*) FROM kg_assessment WHERE status = 'active'"),
                "revoked", KgStore.queryLong(c, "SELECT count(*) FROM kg_assessment WHERE status = 'revoked'"),
                "reviewNeeded", KgStore.queryLong(c, "SELECT count(*) FROM kg_assessment WHERE status = 'active' AND review_needed = 1"));
    }

    // ----------------------------------------------------------------------------------------------- the publisher

    /** What the publisher does with one claim. */
    static final class Verdict {
        final Rule rule;
        final boolean dependent;

        Verdict(final Rule rule, final boolean dependent) {
            this.rule = rule;
            this.dependent = dependent;
        }
    }

    /**
     * The claims of an extraction the active rules hold back or move, with the derived industries that lose their only
     * support on the page. {@code key} gives a claim's {subject public ID, object key} (null: not published anyway).
     */
    static Map<Claim, Verdict> assess(final Extraction ex, final List<Rule> rules, final java.util.function.Function<Claim, Object[]> key) {
        final Map<Claim, Verdict> out = new IdentityHashMap<>();
        if (rules.isEmpty()) return out;
        for (final Claim c : ex.claims()) {
            final Object[] k = key.apply(c);
            if (k == null) continue;
            for (final Rule r : rules) {
                if (r.matches(c.predicate, (byte[]) k[1], (String) k[0], c.tier)) {
                    out.put(c, new Verdict(r, false));
                    break;
                }
            }
        }
        if (out.isEmpty()) return out;
        for (final Claim c : ex.claims()) {
            if (out.containsKey(c) || c.locator == null || !c.locator.startsWith(DERIVED)) continue;
            final String category = c.locator.substring(DERIVED.length());
            Rule cause = null;
            boolean supported = false;
            for (final Claim o : ex.claims()) {
                if (!Vocabulary.OFFERS.equals(o.predicate) || !c.subject.equals(o.subject) || !sameTierGroup(c.tier, o.tier)) continue;
                for (final Claim k : ex.claims()) {
                    if (!Vocabulary.CATEGORY.equals(k.predicate) || !o.object.equals(k.subject) || !category.equals(k.value)
                            || !sameTierGroup(c.tier, k.tier)) continue;
                    final Verdict vo = out.get(o), vk = out.get(k);
                    if (vo == null && vk == null) {
                        supported = true;
                    } else if (cause == null) {
                        cause = vo != null ? vo.rule : vk.rule;
                    }
                }
            }
            if (!supported && cause != null) out.put(c, new Verdict(cause, true));
        }
        return out;
    }

    /** The claims a derived industry of {@code tier} is read from (BusinessFacts: tier 2 reads tiers 1 and 2, the LLM its own). */
    private static boolean sameTierGroup(final int derived, final int source) {
        return derived == LlmExtractor.TIER ? source == derived : source <= 2;
    }

    /** One evidence row as copied: everything a restore needs, by public IDs. */
    static final class Copy {
        String statementId;
        String subjectId;
        String predicate;
        String objectId;
        String value;
        byte[] objKey;
        int tier;
        JSONObject extractor;
        int kind;
        int certainty;
        Double confidence;
        String locator;
        String excerpt;
        long observedAt;
        String sourceRevision;
        Long sourceObservedAt;
        boolean dependent;
        String replacedBy;
        // live rows (not stored)
        long stmtRowid;
        long docRowid;
        String docId;
        String url;
        long subjRowid;
        Long objRowid;
    }

    /**
     * Stores the copy of evidence the publisher held back; a different source revision than the one copied before
     * marks the assessment for review (the source changed, the assessment still holds).
     */
    static void held(final Connection tx, final Rule r, final String docId, final Copy c, final long now) throws SQLException {
        String before = null;
        boolean existed = false;
        try (PreparedStatement ps = tx.prepareStatement("SELECT source_revision FROM kg_evidence_withdrawn WHERE assessment_id = ?"
                + " AND statement_id = ? AND doc_id = ? AND tier = ?")) {
            ps.setString(1, r.id);
            ps.setString(2, c.statementId);
            ps.setString(3, docId);
            ps.setInt(4, c.tier);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    existed = true;
                    before = rs.getString(1);
                }
            }
        }
        final boolean review = existed && !java.util.Objects.equals(before, c.sourceRevision);
        if (existed && !review) return; // the same revision again: nothing new
        c.docId = docId;
        insertCopy(tx, r.id, c, now);
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_assessment SET blocked = blocked + 1"
                + (review ? ", review_needed = 1" : "") + " WHERE public_id = ?")) {
            ps.setString(1, r.id);
            ps.executeUpdate();
        }
        if (review) {
            KgStore.event(tx, 2, "assessment_review", r.id + " holds " + c.predicate + " of " + docId
                    + " in a new revision of the source; review it (revoke to accept the source)", now);
        }
    }

    private static void insertCopy(final Connection tx, final String assessment, final Copy c, final long now) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("INSERT OR REPLACE INTO kg_evidence_withdrawn (assessment_id, doc_id, statement_id,"
                + " subject_id, predicate, object_id, value, obj_key, tier, extractor, kind, certainty, confidence, locator, excerpt,"
                + " observed_at, source_revision, source_observed_at, withdrawn_at, dependent, replaced_by)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, assessment);
            ps.setString(2, c.docId);
            ps.setString(3, c.statementId);
            ps.setString(4, c.subjectId);
            ps.setString(5, c.predicate);
            ps.setString(6, c.objectId);
            ps.setString(7, c.value);
            ps.setBytes(8, c.objKey);
            ps.setInt(9, c.tier);
            ps.setString(10, c.extractor.toString());
            ps.setInt(11, c.kind);
            ps.setInt(12, c.certainty);
            if (c.confidence == null) ps.setNull(13, Types.REAL);
            else ps.setDouble(13, c.confidence);
            ps.setString(14, c.locator);
            ps.setString(15, c.excerpt);
            ps.setLong(16, c.observedAt);
            ps.setString(17, c.sourceRevision);
            if (c.sourceObservedAt == null) ps.setNull(18, Types.INTEGER);
            else ps.setLong(18, c.sourceObservedAt);
            ps.setLong(19, now);
            ps.setInt(20, c.dependent ? 1 : 0);
            ps.setString(21, c.replacedBy);
            ps.executeUpdate();
        }
    }

    static JSONObject extractor(final Connection tx, final int extId) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT tier, name, version, model, prompt_hash FROM kg_extractor WHERE ext_id = ?")) {
            ps.setInt(1, extId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) throw new SQLException("unknown extractor " + extId);
                return KgJson.obj("tier", r.getInt(1), "name", r.getString(2), "version", r.getString(3), "model", r.getString(4),
                        "prompt_hash", r.getString(5));
            }
        }
    }

    private static int extractorId(final Connection tx, final JSONObject x) throws SQLException {
        try (PreparedStatement ins = tx.prepareStatement("INSERT OR IGNORE INTO kg_extractor (tier, name, version, model, prompt_hash)"
                + " VALUES (?, ?, ?, ?, ?)");
                PreparedStatement sel = tx.prepareStatement("SELECT ext_id FROM kg_extractor WHERE tier = ? AND name = ? AND version = ?"
                        + " AND model = ? AND prompt_hash = ?")) {
            for (final PreparedStatement ps : new PreparedStatement[] {ins, sel}) {
                ps.setInt(1, x.optInt("tier"));
                ps.setString(2, x.optString("name"));
                ps.setString(3, x.optString("version"));
                ps.setString(4, x.optString("model", ""));
                ps.setString(5, x.optString("prompt_hash", ""));
            }
            ins.executeUpdate();
            try (ResultSet r = sel.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------------------------------- preview and apply

    /** The evidence rows the rule matches now, with the derived rows that lose their only support. */
    static List<Copy> matching(final Connection c, final Rule r, final int limit) throws SQLException {
        final StringBuilder sql = new StringBuilder("SELECT e.stmt_rowid, e.doc_rowid, e.tier, d.doc_id, d.url, s.public_id, en.public_id,"
                + " s.obj_val, s.obj_key, oe.public_id, e.ext_id, e.kind, e.certainty, e.confidence, e.locator, e.excerpt, e.observed_at,"
                + " e.source_revision, e.source_observed_at, s.subj, s.obj_ent FROM kg_evidence e"
                + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab p ON p.term_id = s.pred AND p.kind = 2"
                + " JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_entity en ON en.ent_rowid = s.subj"
                + " LEFT JOIN kg_entity oe ON oe.ent_rowid = s.obj_ent WHERE p.name = ?");
        final List<Object> args = new ArrayList<>();
        args.add(r.predicate);
        if (r.value != null) {
            sql.append(" AND s.obj_key = ?");
            args.add(r.objectKey());
        }
        if (r.tier != null) {
            sql.append(" AND e.tier = ?");
            args.add(r.tier);
        }
        if (r.subjectId != null) {
            sql.append(" AND en.public_id = ?");
            args.add(r.subjectId);
        }
        if (SOURCE.equals(r.scopeKind)) {
            sql.append(" AND d.doc_id = ?");
            args.add(r.scope);
        } else {
            sql.append(" AND d.url LIKE ?"); // narrowed by the host check below
            args.add("%" + r.scope.replace("%", "").replace("_", "") + "%");
        }
        sql.append(" ORDER BY e.doc_rowid, e.stmt_rowid, e.tier");
        final List<Copy> out = new ArrayList<>();
        final Map<Integer, JSONObject> extractors = new java.util.HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                final Object a = args.get(i);
                if (a instanceof byte[]) ps.setBytes(i + 1, (byte[]) a);
                else if (a instanceof Integer) ps.setInt(i + 1, (Integer) a);
                else ps.setString(i + 1, (String) a);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() <= limit) {
                    if (!SOURCE.equals(r.scopeKind) && !r.covers(rs.getString(4), host(rs.getString(5)))) continue;
                    out.add(copy(c, rs, r.predicate, extractors));
                }
            }
        }
        if (out.size() <= limit) out.addAll(dependents(c, out, extractors));
        return out;
    }

    private static Copy copy(final Connection c, final ResultSet rs, final String predicate, final Map<Integer, JSONObject> extractors)
            throws SQLException {
        final Copy k = new Copy();
        k.stmtRowid = rs.getLong(1);
        k.docRowid = rs.getLong(2);
        k.tier = rs.getInt(3);
        k.docId = rs.getString(4);
        k.url = rs.getString(5);
        k.statementId = rs.getString(6);
        k.subjectId = rs.getString(7);
        k.value = rs.getString(8);
        k.objKey = rs.getBytes(9);
        k.objectId = rs.getString(10);
        final int ext = rs.getInt(11);
        JSONObject x = extractors.get(ext);
        if (x == null) {
            x = extractor(c, ext);
            extractors.put(ext, x);
        }
        k.extractor = x;
        k.kind = rs.getInt(12);
        k.certainty = rs.getInt(13);
        final double conf = rs.getDouble(14);
        k.confidence = rs.wasNull() ? null : conf;
        k.locator = rs.getString(15);
        k.excerpt = rs.getString(16);
        k.observedAt = rs.getLong(17);
        k.sourceRevision = rs.getString(18);
        final long so = rs.getLong(19);
        k.sourceObservedAt = rs.wasNull() ? null : so;
        k.subjRowid = rs.getLong(20);
        final long oe = rs.getLong(21);
        k.objRowid = rs.wasNull() ? null : oe;
        k.predicate = predicate;
        return k;
    }

    /** Derived industries of the affected pages that only the withdrawn categories or offers supported. */
    private static List<Copy> dependents(final Connection c, final List<Copy> primary, final Map<Integer, JSONObject> extractors)
            throws SQLException {
        final Set<String> moved = new java.util.HashSet<>();
        final Set<Long> docs = new TreeSet<>();
        for (final Copy k : primary) {
            if (Vocabulary.CATEGORY.equals(k.predicate) || Vocabulary.OFFERS.equals(k.predicate)) {
                docs.add(k.docRowid);
            }
            moved.add(k.stmtRowid + ":" + k.docRowid + ":" + k.tier);
        }
        final List<Copy> out = new ArrayList<>();
        if (docs.isEmpty()) return out;
        try (PreparedStatement ps = c.prepareStatement("SELECT e.stmt_rowid, e.doc_rowid, e.tier, d.doc_id, d.url, s.public_id,"
                + " en.public_id, s.obj_val, s.obj_key, NULL, e.ext_id, e.kind, e.certainty, e.confidence, e.locator, e.excerpt,"
                + " e.observed_at, e.source_revision, e.source_observed_at, s.subj, s.obj_ent, p.name FROM kg_evidence e"
                + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab p ON p.term_id = s.pred AND p.kind = 2"
                + " JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_entity en ON en.ent_rowid = s.subj"
                + " WHERE e.doc_rowid = ? AND e.locator LIKE 'derived:category:%'");
                PreparedStatement support = c.prepareStatement("SELECT eo.stmt_rowid, eo.tier, ec.stmt_rowid, ec.tier FROM kg_evidence eo"
                        + " JOIN kg_statement so ON so.stmt_rowid = eo.stmt_rowid JOIN kg_vocab po ON po.term_id = so.pred AND po.name = 'offers'"
                        + " JOIN kg_statement sc ON sc.subj = so.obj_ent JOIN kg_vocab pc ON pc.term_id = sc.pred AND pc.name = 'category'"
                        + " JOIN kg_evidence ec ON ec.stmt_rowid = sc.stmt_rowid AND ec.doc_rowid = eo.doc_rowid"
                        + " WHERE eo.doc_rowid = ? AND so.subj = ? AND sc.obj_val = ?")) {
            for (final long doc : docs) {
                ps.setLong(1, doc);
                final List<Copy> derived = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final Copy k = copy(c, rs, rs.getString(22), extractors);
                        if (!moved.contains(k.stmtRowid + ":" + k.docRowid + ":" + k.tier)) derived.add(k);
                    }
                }
                for (final Copy k : derived) {
                    support.setLong(1, doc);
                    support.setLong(2, k.subjRowid);
                    support.setString(3, k.locator.substring(DERIVED.length()));
                    boolean supported = false;
                    try (ResultSet rs = support.executeQuery()) {
                        while (rs.next() && !supported) {
                            supported = sameTierGroup(k.tier, rs.getInt(2)) && sameTierGroup(k.tier, rs.getInt(4))
                                    && !moved.contains(rs.getLong(1) + ":" + doc + ":" + rs.getInt(2))
                                    && !moved.contains(rs.getLong(3) + ":" + doc + ":" + rs.getInt(4));
                        }
                    }
                    if (!supported) {
                        k.dependent = true;
                        out.add(k);
                    }
                }
            }
        }
        return out;
    }

    private static String host(final String url) {
        if (url == null) return null;
        try {
            final String h = new java.net.URI(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (final java.net.URISyntaxException e) {
            return null;
        }
    }

    /** What applying a rule would change now: counts, a sample and the confirmation token. */
    public static JSONObject preview(final Connection c, final Rule r) throws SQLException {
        final List<Copy> rows = matching(c, r, MAX_APPLY);
        final boolean tooLarge = rows.size() > MAX_APPLY;
        final Set<Long> stmts = new TreeSet<>(), docs = new TreeSet<>(), leaving = new TreeSet<>();
        int dependent = 0;
        final JSONArray sample = new JSONArray();
        final Map<Long, Integer> movedOf = new LinkedHashMap<>();
        for (final Copy k : rows) {
            stmts.add(k.stmtRowid);
            docs.add(k.docRowid);
            if (k.dependent) dependent++;
            movedOf.merge(k.stmtRowid, 1, Integer::sum);
            if (sample.length() < MAX_SAMPLE) {
                sample.put(KgJson.obj("url", k.url, "statement", k.statementId, "subject", k.subjectId, "predicate", k.predicate, "value",
                        k.objectId != null ? k.objectId : k.value, "tier", k.tier, "locator", k.locator, "quote",
                        k.excerpt == null ? null : Normalizers.clip(k.excerpt, 200), "dependent", k.dependent));
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM kg_evidence WHERE stmt_rowid = ?")) {
            for (final Map.Entry<Long, Integer> e : movedOf.entrySet()) {
                ps.setLong(1, e.getKey());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) <= e.getValue()) leaving.add(e.getKey());
                }
            }
        }
        long observations = 0, contributions = 0;
        try (PreparedStatement o = c.prepareStatement("SELECT count(*), (SELECT count(*) FROM kg_match_ref m WHERE m.observation_id IN"
                + " (SELECT public_id FROM kg_observation WHERE source_id = ? AND subject_id = ? AND predicate = ? AND value = ? AND tier = ?"
                + " AND assertion_status = 'recorded')) FROM kg_observation WHERE source_id = ? AND subject_id = ? AND predicate = ?"
                + " AND value = ? AND tier = ? AND assertion_status = 'recorded'")) {
            for (final Copy k : rows) {
                for (final int base : new int[] {0, 5}) {
                    o.setString(base + 1, k.docId);
                    o.setString(base + 2, k.subjectId);
                    o.setString(base + 3, k.predicate);
                    o.setString(base + 4, k.objectId != null ? k.objectId : k.value == null ? "" : k.value);
                    o.setInt(base + 5, k.tier);
                }
                try (ResultSet rs = o.executeQuery()) {
                    rs.next();
                    observations += rs.getLong(1);
                    contributions += rs.getLong(2);
                }
            }
        }
        final JSONObject out = KgJson.obj("evidence", rows.size() - dependent, "dependentEvidence", dependent, "statements", stmts.size(),
                "statementsLeaving", leaving.size(), "documents", docs.size(), "observations", observations, "matchingContributions",
                contributions, "tooLarge", tooLarge, "maxEvidence", MAX_APPLY, "sample", sample);
        KgJson.put(out, "token", token(r, out));
        return out;
    }

    /** A token over the rule and what it would change: a confirmation applies exactly what was previewed. */
    static String token(final Rule r, final JSONObject preview) {
        final String basis = String.join("\n", r.action, r.scopeKind, r.scope, r.predicate, String.valueOf(r.value),
                String.valueOf(r.subjectId), String.valueOf(r.tier), String.valueOf(r.targetPredicate),
                preview.optString("evidence"), preview.optString("dependentEvidence"), preview.optString("statements"),
                preview.optString("documents"));
        try {
            final byte[] h = MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", h[i]));
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The rule a draft becomes (not stored). */
    public static Rule draft(final Draft d) {
        return new Rule("kga_" + "a".repeat(20), d, 0L);
    }

    /** Stores a new assessment and applies it; returns the rule and what moved. */
    public static JSONObject create(final Connection tx, final KgConfig cfg, final Draft d, final long now) throws SQLException {
        final Rule r = new Rule(newId(), d, now);
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO kg_assessment (public_id, action, scope_kind, scope, predicate, value,"
                + " subject_id, tier, target_predicate, reason, basis, status, actor, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " 'active', ?, ?)")) {
            ps.setString(1, r.id);
            ps.setString(2, r.action);
            ps.setString(3, r.scopeKind);
            ps.setString(4, r.scope);
            ps.setString(5, r.predicate);
            ps.setString(6, r.value);
            ps.setString(7, r.subjectId);
            if (r.tier == null) ps.setNull(8, Types.INTEGER);
            else ps.setInt(8, r.tier);
            ps.setString(9, r.targetPredicate);
            ps.setString(10, r.reason);
            ps.setString(11, r.basis);
            ps.setString(12, r.actor);
            ps.setLong(13, now);
            ps.executeUpdate();
        }
        final JSONObject applied = apply(tx, cfg, r, now);
        KgStore.event(tx, 1, "assessment_created", r.id + " " + r.action + " " + r.predicate + " in " + r.scopeKind + " " + r.scope
                + " by " + (r.actor == null ? "unknown" : r.actor) + ": " + applied.optInt("evidence") + " evidence rows, "
                + applied.optInt("dependentEvidence") + " dependent", now);
        return KgJson.obj("assessment", r.toJson(), "applied", applied);
    }

    /** Moves the evidence the rule matches (idempotent: moved rows are gone, a second run finds nothing). */
    static JSONObject apply(final Connection tx, final KgConfig cfg, final Rule r, final long now) throws SQLException {
        final List<Copy> rows = matching(tx, r, MAX_APPLY);
        if (rows.size() > MAX_APPLY) {
            throw new SQLException("assessment_too_large");
        }
        final Terms terms = new Terms();
        terms.seed(tx); // a reclassification may name a predicate no page used yet
        final Aggregates agg = new Aggregates(cfg, terms, now);
        int moved = 0, dependent = 0, reclassified = 0;
        final String status = r.withdraws() ? "withdrawn" : "corrected";
        try (PreparedStatement del = tx.prepareStatement("DELETE FROM kg_evidence WHERE stmt_rowid = ? AND doc_rowid = ? AND tier = ?");
                PreparedStatement obs = tx.prepareStatement("UPDATE kg_observation SET assertion_status = ?, correction_of = ?"
                        + " WHERE source_id = ? AND subject_id = ? AND predicate = ? AND value = ? AND tier = ? AND assertion_status = 'recorded'")) {
            for (final Copy k : rows) {
                agg.touchStatement(tx, k.stmtRowid);
                if (!r.withdraws() && !k.dependent) {
                    final String target = moveTo(tx, terms, agg, r.targetPredicate, k);
                    if (target != null) {
                        k.replacedBy = target;
                        reclassified++;
                    }
                }
                insertCopy(tx, r.id, k, now);
                del.setLong(1, k.stmtRowid);
                del.setLong(2, k.docRowid);
                del.setInt(3, k.tier);
                del.executeUpdate();
                obs.setString(1, k.dependent ? "withdrawn" : status);
                obs.setString(2, r.id);
                obs.setString(3, k.docId);
                obs.setString(4, k.subjectId);
                obs.setString(5, k.predicate);
                obs.setString(6, k.objectId != null ? k.objectId : k.value == null ? "" : k.value);
                obs.setInt(7, k.tier);
                obs.executeUpdate();
                if (k.dependent) dependent++;
                else moved++;
            }
        }
        agg.finish(tx);
        return KgJson.obj("evidence", moved, "dependentEvidence", dependent, "reclassified", reclassified,
                "statementsDeleted", agg.statementsDeleted);
    }

    /** Adds the evidence of {@code k} to (subject, target, object); the target statement's ID if the row is new there. */
    private static String moveTo(final Connection tx, final Terms terms, final Aggregates agg, final String target, final Copy k)
            throws SQLException {
        final Vocabulary.Predicate p = Vocabulary.predicate(target);
        final String canonical = p.relation ? "e:" + k.objectId : "v:" + p.datatype + ":" + Publisher.compareForm(p.datatype, k.value);
        final long stmt = statement(tx, agg, terms, k.subjRowid, k.subjectId, target, k.objRowid, k.value, canonical);
        try (PreparedStatement ps = tx.prepareStatement("INSERT OR IGNORE INTO kg_evidence (stmt_rowid, doc_rowid, tier, ext_id, kind,"
                + " certainty, confidence, locator, excerpt, observed_at, source_revision, source_observed_at) VALUES (?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?)")) {
            bindEvidence(tx, ps, stmt, k.docRowid, k);
            if (ps.executeUpdate() == 0) return null;
        }
        return publicId(tx, stmt);
    }

    private static long statement(final Connection tx, final Aggregates agg, final Terms terms, final long subj, final String subjId,
            final String predicate, final Long objEnt, final String objVal, final String canonical) throws SQLException {
        final byte[] objKey = KgIds.objectKey(canonical);
        final int pred = terms.predicate(predicate);
        try (PreparedStatement find = tx.prepareStatement("SELECT stmt_rowid FROM kg_statement WHERE subj = ? AND pred = ? AND obj_key = ?")) {
            find.setLong(1, subj);
            find.setInt(2, pred);
            find.setBytes(3, objKey);
            try (ResultSet rs = find.executeQuery()) {
                if (rs.next()) {
                    agg.touchStatement(tx, rs.getLong(1));
                    return rs.getLong(1);
                }
            }
        }
        try (PreparedStatement ins = tx.prepareStatement("INSERT INTO kg_statement (public_id, subj, pred, obj_ent, obj_val, obj_key, quality,"
                + " current_sources, first_seen, last_confirmed) VALUES (?, ?, ?, ?, ?, ?, 4, 0, ?, NULL)")) {
            ins.setString(1, KgIds.statementId(subjId, predicate, canonical));
            ins.setLong(2, subj);
            ins.setInt(3, pred);
            if (objEnt == null) ins.setNull(4, Types.INTEGER);
            else ins.setLong(4, objEnt);
            ins.setString(5, objEnt == null ? objVal : null);
            ins.setBytes(6, objKey);
            ins.setLong(7, agg.now());
            ins.executeUpdate();
        }
        final long stmt = KgStore.queryLong(tx, "SELECT last_insert_rowid()");
        agg.createdStatement(stmt);
        return stmt;
    }

    private static void bindEvidence(final Connection tx, final PreparedStatement ps, final long stmt, final long doc, final Copy k)
            throws SQLException {
        ps.setLong(1, stmt);
        ps.setLong(2, doc);
        ps.setInt(3, k.tier);
        ps.setInt(4, extractorId(tx, k.extractor));
        ps.setInt(5, k.kind);
        ps.setInt(6, k.certainty);
        if (k.confidence == null) ps.setNull(7, Types.REAL);
        else ps.setDouble(7, k.confidence);
        ps.setString(8, k.locator);
        ps.setString(9, k.excerpt);
        ps.setLong(10, k.observedAt);
        ps.setString(11, k.sourceRevision);
        if (k.sourceObservedAt == null) ps.setNull(12, Types.INTEGER);
        else ps.setLong(12, k.sourceObservedAt);
    }

    private static String publicId(final Connection tx, final long stmt) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT public_id FROM kg_statement WHERE stmt_rowid = ?")) {
            ps.setLong(1, stmt);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------- revoke

    /**
     * Revokes an assessment: the copied evidence comes back unchanged where its source is still the revision it was
     * copied from (a reclassification's moved row is removed again); a changed source of tiers 1 and 2 is read again by
     * the rules; LLM evidence of a changed source stays out until the LLM tier reads the page anew (no model request).
     */
    public static JSONObject revoke(final Connection tx, final KgConfig cfg, final String id, final String actor, final long now)
            throws SQLException {
        final Rule r = get(tx, id);
        if (r == null || !"active".equals(r.status)) return null;
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_assessment SET status = 'revoked', revoked_at = ?, revoked_by = ?"
                + " WHERE public_id = ?")) {
            ps.setLong(1, now);
            ps.setString(2, actor == null ? null : actor.length() > 128 ? actor.substring(0, 128) : actor);
            ps.setString(3, id);
            ps.executeUpdate();
        }
        final Terms terms = new Terms();
        terms.seed(tx); // a reclassification may name a predicate no page used yet
        final Aggregates agg = new Aggregates(cfg, terms, now);
        int restored = 0, reread = 0, notRestored = 0;
        final Set<String> reextract = new LinkedHashSet<>();
        final JSONArray kept = new JSONArray();
        final List<Copy> copies = new ArrayList<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT doc_id, statement_id, subject_id, predicate, object_id, value, obj_key, tier,"
                + " extractor, kind, certainty, confidence, locator, excerpt, observed_at, source_revision, source_observed_at, dependent,"
                + " replaced_by FROM kg_evidence_withdrawn WHERE assessment_id = ? ORDER BY doc_id, statement_id, tier")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final Copy k = new Copy();
                    k.docId = rs.getString(1);
                    k.statementId = rs.getString(2);
                    k.subjectId = rs.getString(3);
                    k.predicate = rs.getString(4);
                    k.objectId = rs.getString(5);
                    k.value = rs.getString(6);
                    k.objKey = rs.getBytes(7);
                    k.tier = rs.getInt(8);
                    k.extractor = parse(rs.getString(9));
                    k.kind = rs.getInt(10);
                    k.certainty = rs.getInt(11);
                    final double conf = rs.getDouble(12);
                    k.confidence = rs.wasNull() ? null : conf;
                    k.locator = rs.getString(13);
                    k.excerpt = rs.getString(14);
                    k.observedAt = rs.getLong(15);
                    k.sourceRevision = rs.getString(16);
                    final long so = rs.getLong(17);
                    k.sourceObservedAt = rs.wasNull() ? null : so;
                    k.dependent = rs.getInt(18) == 1;
                    k.replacedBy = rs.getString(19);
                    copies.add(k);
                }
            }
        }
        for (final Copy k : copies) {
            final Long doc = currentDoc(tx, k);
            if (doc == null) {
                notRestored++;
                if (kept.length() < MAX_SAMPLE) kept.put(KgJson.obj("source", k.docId, "statement", k.statementId, "reason", "source_changed"));
                if (k.tier <= 2) reextract.add(k.docId);
                continue;
            }
            if (k.replacedBy != null) {
                try (PreparedStatement ps = tx.prepareStatement("SELECT stmt_rowid FROM kg_statement WHERE public_id = ?")) {
                    ps.setString(1, k.replacedBy);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            agg.touchStatement(tx, rs.getLong(1));
                            try (PreparedStatement del = tx.prepareStatement("DELETE FROM kg_evidence WHERE stmt_rowid = ? AND doc_rowid = ?"
                                    + " AND tier = ?")) {
                                del.setLong(1, rs.getLong(1));
                                del.setLong(2, doc);
                                del.setInt(3, k.tier);
                                del.executeUpdate();
                            }
                        }
                    }
                }
            }
            final Long subj = liveEntity(tx, k.subjectId);
            final Long obj = k.objectId == null ? null : liveEntity(tx, k.objectId);
            if (subj == null || k.objectId != null && obj == null) {
                notRestored++;
                if (kept.length() < MAX_SAMPLE) kept.put(KgJson.obj("source", k.docId, "statement", k.statementId, "reason", "entity_gone"));
                if (k.tier <= 2) reextract.add(k.docId);
                continue;
            }
            final Vocabulary.Predicate p = Vocabulary.predicate(k.predicate);
            final String subjId = entityId(tx, subj);
            final String canonical = p.relation ? "e:" + entityId(tx, obj) : "v:" + p.datatype + ":" + Publisher.compareForm(p.datatype, k.value);
            final long stmt = statement(tx, agg, terms, subj, subjId, k.predicate, obj, k.value, canonical);
            try (PreparedStatement ps = tx.prepareStatement("INSERT OR IGNORE INTO kg_evidence (stmt_rowid, doc_rowid, tier, ext_id, kind,"
                    + " certainty, confidence, locator, excerpt, observed_at, source_revision, source_observed_at) VALUES (?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?)")) {
                bindEvidence(tx, ps, stmt, doc, k);
                ps.executeUpdate();
            }
            restored++;
        }
        for (final String d : reextract) {
            // the rules read the changed source again (tiers 1 and 2, no model)
            try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_doc SET input_hash = NULL WHERE doc_id = ? AND state = 1")) {
                ps.setString(1, d);
                if (ps.executeUpdate() > 0) {
                    reread++;
                    try (PreparedStatement w = tx.prepareStatement("INSERT OR IGNORE INTO kg_work (doc_id, reason, event_version, priority,"
                            + " not_before, enqueued_at) VALUES (?, 4, 0, 5, ?, ?)")) {
                        w.setString(1, d);
                        w.setLong(2, now);
                        w.setLong(3, now);
                        w.executeUpdate();
                    }
                }
            }
        }
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_evidence_withdrawn WHERE assessment_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_observation SET assertion_status = 'recorded', correction_of = NULL"
                + " WHERE correction_of = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
        agg.finish(tx);
        KgStore.event(tx, 1, "assessment_revoked", id + " by " + (actor == null ? "unknown" : actor) + ": " + restored + " restored, "
                + notRestored + " not restored, " + reread + " sources read again", now);
        return KgJson.obj("assessment", get(tx, id).toJson(), "restored", restored, "notRestored", notRestored, "notRestoredSample",
                kept, "sourcesReadAgain", reread);
    }

    /** The source's row if it is still the revision the copy was taken from (same content, same load). */
    private static Long currentDoc(final Connection tx, final Copy k) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT doc_rowid, lower(hex(coalesce(content_hash, input_hash))) || ':'"
                + " || coalesce(loaded_at, -1), tiers FROM kg_doc WHERE doc_id = ? AND state = 1")) {
            ps.setString(1, k.docId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                if (!java.util.Objects.equals(rs.getString(2), k.sourceRevision)) return null;
                if ((rs.getInt(3) & (1 << (k.tier - 1))) == 0) return null; // the tier's reading was purged
                return rs.getLong(1);
            }
        }
    }

    private static Long liveEntity(final Connection tx, final String publicId) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT ent_rowid FROM kg_entity WHERE public_id = ? AND status = 1"
                + " UNION ALL SELECT target_rowid FROM kg_entity_redirect WHERE public_id = ? LIMIT 1")) {
            ps.setString(1, publicId);
            ps.setString(2, publicId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long ent = rs.getLong(1);
                for (int i = 0; i < 16; i++) { // follow merges
                    try (PreparedStatement m = tx.prepareStatement("SELECT status, merged_into FROM kg_entity WHERE ent_rowid = ?")) {
                        m.setLong(1, ent);
                        try (ResultSet x = m.executeQuery()) {
                            if (!x.next()) return null;
                            if (x.getInt(1) == 1) return ent;
                            ent = x.getLong(2);
                        }
                    }
                }
                return null;
            }
        }
    }

    private static String entityId(final Connection tx, final long ent) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT public_id FROM kg_entity WHERE ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Applies every active assessment again (after a rebuild carry); clears {@link #META_REAPPLY}. */
    public static JSONObject reapplyAll(final Connection tx, final KgConfig cfg, final long now) throws SQLException {
        final List<Rule> rules = new ArrayList<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT * FROM kg_assessment WHERE status = 'active' ORDER BY assess_rowid");
                ResultSet r = ps.executeQuery()) {
            while (r.next()) rules.add(new Rule(r));
        }
        int evidence = 0;
        for (final Rule r : rules) evidence += apply(tx, cfg, r, now).optInt("evidence");
        KgStore.deleteMeta(tx, META_REAPPLY);
        KgStore.event(tx, 1, "assessments_reapplied", rules.size() + " assessments, " + evidence + " evidence rows", now);
        return KgJson.obj("assessments", rules.size(), "evidence", evidence);
    }

    private static JSONObject parse(final String json) throws SQLException {
        try {
            return new JSONObject(json);
        } catch (final org.json.JSONException e) {
            throw new SQLException("unreadable extractor of a withdrawn copy", e);
        }
    }

    static String newId() {
        final java.security.SecureRandom rnd = new java.security.SecureRandom();
        final String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        final StringBuilder b = new StringBuilder("kga_");
        for (int i = 0; i < 20; i++) b.append(alphabet.charAt(rnd.nextInt(32)));
        return b.toString();
    }
}
