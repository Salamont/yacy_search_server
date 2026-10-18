/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.read;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * Quality metrics of the knowledge graph (WP-B, docs/SCOUTRO_KG_QUALITY_METRICS.md): reproducible counts with a
 * stated counting unit, computed directly from the live graph. Every metric runs in its own read transaction (one
 * slow metric never fails the others; it reports its error instead), merged entities ({@code status = 2}, the former
 * „orphans“ of the audit) never count, and per-collection numbers are memberships: an entity in two collections
 * counts in both, so they do not add up to the total.
 */
public final class QualityMetrics {

    public static final String SCHEMA = "scoutro.kg.quality.v1";
    /** A snapshot is reused while it is younger (or computed again with refresh). */
    public static final long TTL_MILLIS = 10L * 60L * 1000L;
    /** Deadline of one metric's read transaction. */
    public static final long METRIC_DEADLINE_MILLIS = 60_000L;

    private static volatile JSONObject snapshot;
    private static volatile long snapshotAt;

    private final KgStore store;
    private final LongSupplier clock;
    private final long deadline;

    public QualityMetrics(final KgStore store, final LongSupplier clock) {
        this(store, clock, METRIC_DEADLINE_MILLIS);
    }

    QualityMetrics(final KgStore store, final LongSupplier clock, final long deadline) {
        this.store = store;
        this.clock = clock;
        this.deadline = deadline;
    }

    /** The cached snapshot, or a new one when it is older than the TTL or {@code refresh}. */
    public JSONObject current(final boolean refresh) {
        final long now = this.clock.getAsLong();
        final JSONObject s = snapshot;
        if (!refresh && s != null && now - snapshotAt < TTL_MILLIS) return s;
        final JSONObject fresh = compute();
        snapshot = fresh;
        snapshotAt = now;
        return fresh;
    }

    /** Forgets the cached snapshot (tests). */
    static void forget() {
        snapshot = null;
        snapshotAt = 0L;
    }

    /** One metric: per-collection counts and the distinct total. */
    private interface Metric {
        void run(Connection c, JSONObject into) throws SQLException;
    }

    public JSONObject compute() {
        final long started = this.clock.getAsLong();
        final JSONObject metrics = new JSONObject();
        final JSONArray errors = new JSONArray();
        final Map<String, Metric> all = new LinkedHashMap<>();
        all.put("organizations", (c, o) -> entities(c, o, "organization", null));
        all.put("organizations_with_stated_name", (c, o) -> entities(c, o, "organization", hasPred("name")));
        all.put("organizations_without_stated_name", (c, o) -> entities(c, o, "organization", "NOT " + hasPred("name")));
        all.put("organizations_with_explicit_industry", (c, o) -> entities(c, o, "organization", explicitIndustry()));
        all.put("organizations_with_derived_industry_only", (c, o) -> entities(c, o, "organization",
                anyIndustry() + " AND NOT " + explicitIndustry()));
        all.put("organizations_without_industry", (c, o) -> entities(c, o, "organization", "NOT " + anyIndustry()));
        all.put("services", (c, o) -> entities(c, o, "service", null));
        all.put("services_without_provider", (c, o) -> entities(c, o, "service", "NOT " + hasProvider()));
        all.put("services_with_price", (c, o) -> entities(c, o, "service", hasPred("price")));
        all.put("services_with_price_without_provider", (c, o) -> entities(c, o, "service", hasPred("price") + " AND NOT " + hasProvider()));
        all.put("service_duplicate_candidates", this::duplicates);
        all.put("frequent_service_names", this::frequentNames);
        all.put("offers_evidence_by_tier", this::offersByTier);
        all.put("evidence_empty_excerpt", this::emptyExcerpts);
        all.put("price_statements_by_tier", this::pricesByTier);
        for (final Map.Entry<String, Metric> m : all.entrySet()) {
            final JSONObject into = new JSONObject();
            try {
                this.store.read(c -> {
                    m.getValue().run(c, into);
                    return null;
                }, this.deadline);
                KgJson.put(metrics, m.getKey(), into);
            } catch (final KgException e) {
                KgJson.put(metrics, m.getKey(), KgJson.obj("error", e.code()));
                errors.put(m.getKey());
            }
        }
        JSONObject data = null;
        try {
            data = this.store.read(c -> KgJson.obj("documents", KgStore.queryLong(c, "SELECT count(*) FROM kg_doc WHERE state = 1"),
                    "entities", KgStore.queryLong(c, "SELECT count(*) FROM kg_entity WHERE status = 1"),
                    "merged_entities", KgStore.queryLong(c, "SELECT count(*) FROM kg_entity WHERE status = 2"),
                    "statements", KgStore.queryLong(c, "SELECT count(*) FROM kg_statement"),
                    "evidence", KgStore.queryLong(c, "SELECT count(*) FROM kg_evidence"),
                    "llm_pending", KgStore.queryLong(c, "SELECT count(*) FROM kg_doc WHERE state = 1 AND llm_status IS NULL")), this.deadline);
        } catch (final KgException e) {
            errors.put("data_state");
        }
        return KgJson.obj("schema", SCHEMA, "computed_at", KgReader.iso(started), "duration_ms", this.clock.getAsLong() - started,
                "data_state", data, "metrics", metrics, "errors", errors,
                "units", KgJson.obj("entities", "active entities (merged ones excluded); by_collection counts memberships, an entity in"
                        + " two collections counts in both", "evidence", "evidence rows (statement, document, tier)", "statements",
                        "live statements"),
                "note", "Counts of the live graph at computed_at, not of the index. A missing name, industry or provider can mean that the"
                        + " source has none, that it was not extracted, or that it is ambiguous; these causes are not separated here.");
    }

    // --------------------------------------------------------------- metrics

    private static String term(final String name, final int kind) {
        return "(SELECT term_id FROM kg_vocab WHERE kind = " + kind + " AND name = '" + name + "')";
    }

    private static String hasPred(final String pred) {
        return "EXISTS (SELECT 1 FROM kg_statement s WHERE s.subj = e.ent_rowid AND s.pred = " + term(pred, 2) + ")";
    }

    private static String anyIndustry() {
        return "EXISTS (SELECT 1 FROM kg_statement s WHERE s.subj = e.ent_rowid AND s.pred IN (" + term("industry", 2) + ", "
                + term("industry_category", 2) + "))";
    }

    /** An industry with at least one evidence that is not the synthetic derivation from a service category. */
    private static String explicitIndustry() {
        return "EXISTS (SELECT 1 FROM kg_statement s JOIN kg_evidence v ON v.stmt_rowid = s.stmt_rowid WHERE s.subj = e.ent_rowid"
                + " AND s.pred IN (" + term("industry", 2) + ", " + term("industry_category", 2) + ")"
                + " AND (v.locator IS NULL OR v.locator NOT LIKE 'derived:%'))";
    }

    private static String hasProvider() {
        return "EXISTS (SELECT 1 FROM kg_statement s WHERE s.obj_ent = e.ent_rowid AND s.pred = " + term("offers", 2) + ")";
    }

    private static void entities(final Connection c, final JSONObject into, final String type, final String condition) throws SQLException {
        final String where = " e.status = 1 AND e.type = " + term(type, 1) + (condition == null ? "" : " AND " + condition);
        KgJson.put(into, "unit", "entities");
        KgJson.put(into, "total", KgStore.queryLong(c, "SELECT count(*) FROM kg_entity e WHERE" + where
                + " AND EXISTS (SELECT 1 FROM kg_entity_scope es WHERE es.ent_rowid = e.ent_rowid)"));
        final JSONObject by = new JSONObject();
        try (PreparedStatement p = c.prepareStatement("SELECT k.name, count(*) FROM kg_entity_scope es JOIN kg_entity e ON e.ent_rowid = es.ent_rowid"
                + " JOIN kg_collection k ON k.coll_id = es.coll_id WHERE" + where + " GROUP BY k.name ORDER BY k.name");
                ResultSet r = p.executeQuery()) {
            while (r.next()) KgJson.put(by, r.getString(1), r.getLong(2));
        }
        KgJson.put(into, "by_collection", by);
    }

    /** Services of one provider sharing a normalised name: merge candidates (a name alone never merges). */
    private void duplicates(final Connection c, final JSONObject into) throws SQLException {
        long groups = 0, services = 0;
        try (PreparedStatement p = c.prepareStatement("SELECT count(*) FROM (SELECT o.subj, lower(trim(n.obj_val)) AS k FROM kg_entity e"
                + " JOIN kg_statement n ON n.subj = e.ent_rowid AND n.pred = " + term("name", 2)
                + " JOIN kg_statement o ON o.obj_ent = e.ent_rowid AND o.pred = " + term("offers", 2)
                + " WHERE e.status = 1 AND e.type = " + term("service", 1) + " GROUP BY o.subj, k HAVING count(DISTINCT e.ent_rowid) > 1)");
                ResultSet r = p.executeQuery()) {
            if (r.next()) groups = r.getLong(1);
        }
        try (PreparedStatement p = c.prepareStatement("SELECT coalesce(sum(n), 0) FROM (SELECT count(DISTINCT e.ent_rowid) AS n FROM kg_entity e"
                + " JOIN kg_statement n ON n.subj = e.ent_rowid AND n.pred = " + term("name", 2)
                + " JOIN kg_statement o ON o.obj_ent = e.ent_rowid AND o.pred = " + term("offers", 2)
                + " WHERE e.status = 1 AND e.type = " + term("service", 1) + " GROUP BY o.subj, lower(trim(n.obj_val)) HAVING n > 1)");
                ResultSet r = p.executeQuery()) {
            if (r.next()) services = r.getLong(1);
        }
        KgJson.put(into, "unit", "groups of services with the same provider and normalised name");
        KgJson.put(into, "groups", groups);
        KgJson.put(into, "services", services);
    }

    private void frequentNames(final Connection c, final JSONObject into) throws SQLException {
        final JSONArray top = new JSONArray();
        try (PreparedStatement p = c.prepareStatement("SELECT lower(trim(n.obj_val)) AS k, count(*) AS c FROM kg_entity e JOIN kg_statement n"
                + " ON n.subj = e.ent_rowid AND n.pred = " + term("name", 2) + " WHERE e.status = 1 AND e.type = " + term("service", 1)
                + " GROUP BY k ORDER BY c DESC, k LIMIT 20"); ResultSet r = p.executeQuery()) {
            while (r.next()) top.put(KgJson.obj("name", r.getString(1), "services", r.getLong(2)));
        }
        KgJson.put(into, "unit", "services per normalised name (top 20)");
        KgJson.put(into, "top", top);
    }

    private void offersByTier(final Connection c, final JSONObject into) throws SQLException {
        tiers(c, into, "offers");
    }

    private void pricesByTier(final Connection c, final JSONObject into) throws SQLException {
        tiers(c, into, "price");
    }

    private static void tiers(final Connection c, final JSONObject into, final String pred) throws SQLException {
        final JSONObject by = new JSONObject();
        try (PreparedStatement p = c.prepareStatement("SELECT v.tier, count(*) FROM kg_evidence v JOIN kg_statement s ON s.stmt_rowid = v.stmt_rowid"
                + " WHERE s.pred = " + term(pred, 2) + " GROUP BY v.tier ORDER BY v.tier"); ResultSet r = p.executeQuery()) {
            while (r.next()) KgJson.put(by, "tier" + r.getInt(1), r.getLong(2));
        }
        KgJson.put(into, "unit", "evidence rows of " + pred + " statements by tier (1 JSON-LD/metadata, 2 rules, 3 LLM)");
        KgJson.put(into, "by_tier", by);
    }

    private void emptyExcerpts(final Connection c, final JSONObject into) throws SQLException {
        KgJson.put(into, "unit", "evidence rows with an empty excerpt");
        KgJson.put(into, "total", KgStore.queryLong(c, "SELECT count(*) FROM kg_evidence WHERE excerpt IS NULL OR excerpt = ''"));
        KgJson.put(into, "of", KgStore.queryLong(c, "SELECT count(*) FROM kg_evidence"));
        final JSONObject by = new JSONObject();
        try (PreparedStatement p = c.prepareStatement("SELECT v.tier, count(*) FROM kg_evidence v WHERE v.excerpt IS NULL OR v.excerpt = ''"
                + " GROUP BY v.tier ORDER BY v.tier"); ResultSet r = p.executeQuery()) {
            while (r.next()) KgJson.put(by, "tier" + r.getInt(1), r.getLong(2));
        }
        KgJson.put(into, "by_tier", by);
    }
}
