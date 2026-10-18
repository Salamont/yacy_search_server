/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.publish;

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

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.MoneyRoles;
import net.yacy.scoutro.knowledge.extract.Values;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * Rule-based review of the stored LLM facts (data quality programme WP-G, docs/SCOUTRO_KG_TIER3_REVIEW.md): the
 * checks of the current extraction semantics applied to tier-3 evidence published before them, without any model
 * request. A run reads at most {@code maxDocuments} pages from a cursor (kept in the store, so a later call continues);
 * a dry run only counts and samples; an applying run withdraws each finding through an assessment (WP-E, basis
 * {@code automatic}, reason {@code reassessed_rule:<rule>}), so it is reviewable, protected against re-publication from
 * the LLM cache and undone with a revoke.
 * <ul>
 * <li>{@code price_role}: a price whose quote shows a statutory benefit, a donation, a subsidy or an unclear amount
 * ({@link MoneyRoles});</li>
 * <li>{@code price_ungrounded}: a price whose quote holds no amount with a currency;</li>
 * <li>{@code vocabulary}: a category, industry, industry group or audience segment the page's current vocabularies do
 * not contain ({@link Tier3Revision}).</li>
 * </ul>
 */
public final class Tier3Review {

    /** Version of the rules; a new version starts the review from the beginning. */
    public static final String RULES = "1";
    public static final String META_DRY = "tier3_review_dry";
    public static final String META_APPLY = "tier3_review";
    public static final int MAX_DOCUMENTS = 5000;
    static final List<String> RULE_NAMES = List.of("price_role", "price_ungrounded", "vocabulary");

    private Tier3Review() {
    }

    /** One finding: the evidence of a page for one value of a predicate. */
    static final class Finding {
        final String rule;
        final String docId;
        final String predicate;
        final String value;
        final String detail;
        final String quote;

        Finding(final String rule, final String docId, final String predicate, final String value, final String detail, final String quote) {
            this.rule = rule;
            this.docId = docId;
            this.predicate = predicate;
            this.value = value;
            this.detail = detail;
            this.quote = quote;
        }
    }

    /** The stored progress of the dry or the applying review. */
    public static JSONObject status(final Connection c, final boolean apply) throws SQLException {
        final JSONObject s = state(c, apply);
        KgJson.put(s, "pendingDocuments", KgStore.queryLong(c, "SELECT count(DISTINCT doc_rowid) FROM kg_evidence WHERE tier = 3"
                + " AND doc_rowid > " + s.optLong("cursor")));
        return s;
    }

    private static JSONObject state(final Connection c, final boolean apply) throws SQLException {
        final String stored = KgStore.getMeta(c, apply ? META_APPLY : META_DRY);
        JSONObject s = null;
        if (stored != null) {
            try {
                s = new JSONObject(stored);
            } catch (final org.json.JSONException e) {
                s = null;
            }
        }
        if (s == null || !RULES.equals(s.optString("rules"))) {
            s = KgJson.obj("rules", RULES, "mode", apply ? "apply" : "dry_run", "cursor", 0L, "documents", 0L, "findings", new JSONObject(),
                    "assessments", 0L, "startedAt", null, "updatedAt", null, "done", false);
        }
        return s;
    }

    /**
     * One bounded step of the review from the stored cursor: reads up to {@code maxDocuments} pages with LLM evidence,
     * counts the findings per rule and, when {@code apply}, withdraws each through an automatic assessment. Restart
     * resets the cursor and the totals.
     */
    public static JSONObject step(final Connection tx, final KgConfig cfg, final boolean apply, final int maxDocuments, final boolean restart,
            final long now) throws SQLException {
        JSONObject s = restart ? null : state(tx, apply);
        if (s == null) {
            KgStore.deleteMeta(tx, apply ? META_APPLY : META_DRY);
            s = state(tx, apply);
        }
        if (s.isNull("startedAt")) KgJson.put(s, "startedAt", now);
        final long from = s.optLong("cursor");
        final List<long[]> docs = new ArrayList<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT DISTINCT doc_rowid FROM kg_evidence WHERE tier = 3 AND doc_rowid > ?"
                + " ORDER BY doc_rowid LIMIT ?")) {
            ps.setLong(1, from);
            ps.setInt(2, Math.max(1, Math.min(MAX_DOCUMENTS, maxDocuments)));
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) docs.add(new long[] {r.getLong(1)});
            }
        }
        final JSONObject findings = s.optJSONObject("findings") == null ? new JSONObject() : s.optJSONObject("findings");
        final JSONArray sample = new JSONArray();
        long created = 0;
        long cursor = from;
        for (final long[] d : docs) {
            cursor = d[0];
            for (final Finding f : findings(tx, cfg, d[0])) {
                KgJson.put(findings, f.rule, findings.optLong(f.rule) + 1);
                if (sample.length() < Assessments.MAX_SAMPLE) {
                    sample.put(KgJson.obj("rule", f.rule, "source", f.docId, "predicate", f.predicate, "value", f.value, "detail", f.detail,
                            "quote", f.quote == null ? null : Normalizers.clip(f.quote, 200)));
                }
                if (apply && withdraw(tx, cfg, f, now)) created++;
            }
        }
        KgJson.put(s, "cursor", cursor);
        KgJson.put(s, "documents", s.optLong("documents") + docs.size());
        KgJson.put(s, "findings", findings);
        KgJson.put(s, "assessments", s.optLong("assessments") + created);
        KgJson.put(s, "updatedAt", now);
        KgJson.put(s, "done", docs.size() < Math.max(1, Math.min(MAX_DOCUMENTS, maxDocuments)));
        KgStore.putMeta(tx, apply ? META_APPLY : META_DRY, s.toString());
        if (apply && created > 0) {
            KgStore.event(tx, 1, "tier3_review", created + " automatic assessments for " + docs.size() + " pages (rules " + RULES + ")", now);
        }
        final JSONObject out = new JSONObject();
        for (final String k : s.keySet()) KgJson.put(out, k, s.opt(k));
        KgJson.put(out, "sample", sample);
        KgJson.put(out, "step", KgJson.obj("documents", docs.size(), "assessments", created));
        return out;
    }

    /** The findings of one page's LLM evidence (values deduplicated per predicate). */
    static List<Finding> findings(final Connection c, final KgConfig cfg, final long docRowid) throws SQLException {
        final Map<String, Finding> out = new LinkedHashMap<>();
        String docId = null;
        final List<String> followed = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT d.doc_id, k.name FROM kg_doc d LEFT JOIN kg_doc_collection dc ON dc.doc_rowid ="
                + " d.doc_rowid LEFT JOIN kg_collection k ON k.coll_id = dc.coll_id WHERE d.doc_rowid = ?")) {
            ps.setLong(1, docRowid);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    docId = r.getString(1);
                    if (r.getString(2) != null && cfg.follows(r.getString(2))) followed.add(r.getString(2));
                }
            }
        }
        if (docId == null) return new ArrayList<>();
        final KgVocabularies.Snapshot vocab = KgVocabularies.get();
        final ExtractContext ctx = new ExtractContext(vocab, cfg.vocabulariesOf(followed, vocab.categories.collections), cfg.jobsFor(followed),
                cfg.jobSignalsFor(followed), followed);
        try (PreparedStatement ps = c.prepareStatement("SELECT t.name, s.obj_val, e.excerpt, e.locator FROM kg_evidence e"
                + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab t ON t.term_id = s.pred AND t.kind = 2"
                + " WHERE e.doc_rowid = ? AND e.tier = 3 AND s.obj_val IS NOT NULL ORDER BY e.stmt_rowid")) {
            ps.setLong(1, docRowid);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    final String predicate = r.getString(1), value = r.getString(2), quote = r.getString(3), locator = r.getString(4);
                    Finding f = null;
                    if (Vocabulary.PRICE.equals(predicate)) {
                        final String role = MoneyRoles.role(quote);
                        if (role != null) {
                            f = new Finding("price_role", docId, predicate, value, role, quote);
                        } else if (quote == null || Values.prices(quote, 0, quote.length()).isEmpty()) {
                            f = new Finding("price_ungrounded", docId, predicate, value, null, quote);
                        }
                    } else if (Tier3Revision.VOCABULARY_PREDICATES.contains(predicate) && !Tier3Revision.supported(ctx, predicate, value, locator)) {
                        f = new Finding("vocabulary", docId, predicate, value, String.join(",", followed), quote);
                    }
                    if (f != null) out.putIfAbsent(predicate + "\n" + value, f);
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Withdraws one finding through an automatic assessment (none if an active one covers it already). */
    private static boolean withdraw(final Connection tx, final KgConfig cfg, final Finding f, final long now) throws SQLException {
        final Vocabulary.Predicate p = Vocabulary.predicate(f.predicate);
        final Assessments.Draft d = new Assessments.Draft();
        d.action = Assessments.WITHDRAW;
        d.scopeKind = Assessments.SOURCE;
        d.scope = f.docId;
        d.predicate = f.predicate;
        d.value = f.value;
        d.tier = 3;
        d.reason = "reassessed_rule:" + f.rule + (f.detail == null ? "" : ":" + Normalizers.clip(f.detail, 120));
        d.basis = "automatic";
        d.actor = "tier3_review/" + RULES;
        if (d.validate() != null) return false;
        final String compare = p.relation ? f.value : Publisher.compareForm(p.datatype, f.value);
        try (PreparedStatement ps = tx.prepareStatement("SELECT 1 FROM kg_assessment WHERE status = 'active' AND scope_kind = 'source'"
                + " AND scope = ? AND predicate = ? AND (value IS NULL OR value = ?) AND (tier IS NULL OR tier = 3)")) {
            ps.setString(1, f.docId);
            ps.setString(2, f.predicate);
            ps.setString(3, compare);
            try (ResultSet r = ps.executeQuery()) {
                if (r.next()) return false;
            }
        }
        Assessments.create(tx, cfg, d, now, false);
        return true;
    }
}
