/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.derive;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.json.*;
import net.yacy.scoutro.knowledge.*;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.BusinessSignals;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.read.*;
import net.yacy.scoutro.knowledge.store.*;

/**
 * Local load probe of the suggestion read path (0.11.8): one entity with many matching contributions, read as the object
 * view reads it. {@code shared}: one candidate whose few signals match the offers of many providers (STELLWERK, 10,644
 * contributions in production); {@code distinct}: one provider whose offer matches as many candidates, every row with its
 * own signal and actor (the largest providers, up to 37,560). Not a unit test; run by hand:
 * {@code java ... SuggestionsLoadProbe <new dir> shared|distinct <contributions> [read budget ms, default 5000]}.
 * <p>
 * The data is built with a generous read budget; the store is then opened again with the given budget (production: 5,000 ms)
 * and a real clock for the read leases, so a read that runs longer ends with {@code read_timeout} as in production. Each round
 * reads what the object view and the API read: the suggestion page (25 and 100 groups), the full business view (it embeds
 * the first 100 groups) and the contribution page of the first group.
 */
public final class SuggestionsLoadProbe {
    static final long NOW = 1_800_000_000_000L;
    private static int serial;

    public static void main(final String[] args) throws Exception {
        if (args.length != 3 && args.length != 4) throw new IllegalArgumentException("<new dir> shared|distinct <contributions> [read budget ms]");
        final Path root = Path.of(args[0]);
        if (Files.exists(root)) throw new IllegalArgumentException("refusing existing data");
        Files.createDirectories(root);
        final boolean shared = "shared".equals(args[1]);
        final int n = Integer.parseInt(args[2]);
        final String budget = args.length > 3 ? args[3] : "5000";
        final Map<String, String> settings = new HashMap<>(KgTestSupport.enabled());
        settings.put(KgConfig.JOBS_COLLECTIONS, "a,b,c");
        settings.put(KgConfig.READ_MAX_TRANSACTION_MILLIS, "60000");
        final KgConfig cfg = KgTestSupport.config(settings);
        settings.put(KgConfig.READ_MAX_TRANSACTION_MILLIS, budget);
        final KgConfig readCfg = KgTestSupport.config(settings);
        final KgPaths paths = new KgPaths(root.toFile());
        final String target;
        final JSONObject report;
        try (KgStore store = KgStore.open(paths, cfg, new StorageGuard(cfg, paths, new KgTestSupport.Probe(), () -> NOW), KgStore.SQLITE, () -> NOW)) {
            store.write(WriteClass.SYSTEM, 0, c -> { new Terms().seed(c); return null; });
            final String signal = BusinessSignals.read("system_signal", "Wir nutzen SAP intern.", "text", "organization", "Candidate").get(0);
            final long t0 = System.nanoTime();
            if (shared) {
                // one candidate with 10 signals (10 pages), n/10 providers with one SAP offer each
                target = KgIds.entityId("organization", "test", "", "candidate");
                final List<String[]> rows = new ArrayList<>();
                rows.add(new String[] {target, "b"});
                for (int i = 0; i < n / 10; i++) rows.add(new String[] {KgIds.entityId("organization", "test", "", "provider" + i), "a"});
                entities(store, rows);
                final List<Object[]> obs = new ArrayList<>();
                for (int s = 0; s < 10; s++) obs.add(new Object[] {target, "organization", "system_signal", new JSONObject(signal), "Wir nutzen SAP intern.", "b"});
                for (int i = 0; i < n / 10; i++) obs.add(new Object[] {rows.get(i + 1)[0], "service", "name", KgJson.obj("literal", "SAP Beratung"), "SAP Beratung", "a"});
                observations(store, obs);
            } else {
                // one provider with one SAP offer, n candidates with one signal each
                target = KgIds.entityId("organization", "test", "", "provider");
                final List<String[]> rows = new ArrayList<>();
                rows.add(new String[] {target, "a"});
                for (int i = 0; i < n; i++) rows.add(new String[] {KgIds.entityId("organization", "test", "", "candidate" + i), "b"});
                entities(store, rows);
                final List<Object[]> obs = new ArrayList<>();
                obs.add(new Object[] {target, "service", "name", KgJson.obj("literal", "SAP Beratung"), "SAP Beratung", "a"});
                for (int i = 0; i < n; i++) obs.add(new Object[] {rows.get(i + 1)[0], "organization", "system_signal", new JSONObject(signal), "Wir nutzen SAP intern.", "b"});
                observations(store, obs);
            }
            final double seed = (System.nanoTime() - t0) / 1e9;
            final long t1 = System.nanoTime();
            JSONObject run;
            int runs = 0;
            do {
                run = new MatchingService(store, cfg, 200_000).run(NOW);
                runs++;
            } while (!run.getBoolean("cycle_complete") && runs < 200);
            final double match = (System.nanoTime() - t1) / 1e9;
            final long contributions = store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_match_contribution"));
            report = KgJson.obj("mode", args[1], "contributions", contributions, "seed_s", seed, "match_s", match, "match_runs", runs,
                    "read_budget_ms", readCfg.readMaxTransactionMillis);
        }
        // the reads: the production read budget, a real clock for the leases (the reader keeps the data's clock)
        try (KgStore store = KgStore.open(paths, readCfg, new StorageGuard(readCfg, paths, new KgTestSupport.Probe(), () -> NOW), KgStore.SQLITE,
                System::currentTimeMillis)) {
            final KgReader reader = new KgReader(store, readCfg, () -> NOW);
            final Suggestions suggestions = new Suggestions(reader);
            final KgChangeLog.Viewer origin = reader.viewer(List.of(shared ? "b" : "a")), all = reader.viewer(List.of("a", "b"));
            final JSONArray reads = new JSONArray();
            String proposal = null;
            for (int round = 0; round < 3; round++) {
                for (final String call : List.of("suggestions_25", "suggestions_100", "business", "contributions_25")) {
                    final long t = System.nanoTime();
                    final JSONObject r = KgJson.obj("round", round, "call", call);
                    try {
                        switch (call) {
                            case "suggestions_25":
                            case "suggestions_100": {
                                final JSONObject page = suggestions.page(target, 0, "suggestions_25".equals(call) ? 25 : 100, origin, all);
                                KgJson.put(r, "groups", page.getInt("total"));
                                if (proposal == null && page.getJSONArray("items").length() > 0) {
                                    proposal = page.getJSONArray("items").getJSONObject(0).getString("id");
                                }
                                break;
                            }
                            case "business":
                                new BusinessView(reader).entity(target, origin, all, false);
                                break;
                            default:
                                if (proposal != null) KgJson.put(r, "items", suggestions.contributions(target, proposal, 0, 25, origin, all)
                                        .getJSONArray("items").length());
                        }
                        KgJson.put(r, "seconds", (System.nanoTime() - t) / 1e9);
                    } catch (final KgException e) {
                        KgJson.put(r, "seconds", (System.nanoTime() - t) / 1e9);
                        KgJson.put(r, "error", e.code());
                    }
                    reads.put(r);
                }
            }
            KgJson.put(report, "reads", reads);
            Files.writeString(root.resolve("load-report.json"), report.toString(2));
            System.out.println(report.toString(2));
        } finally {
            net.yacy.cora.protocol.Domains.close();
            net.yacy.cora.util.ConcurrentLog.shutdown();
        }
    }

    private static void entities(final KgStore store, final List<String[]> rows) throws Exception {
        for (int from = 0; from < rows.size(); from += 5000) {
            final List<String[]> chunk = rows.subList(from, Math.min(rows.size(), from + 5000));
            store.write(WriteClass.SYSTEM, 0, c -> {
                try (PreparedStatement e = c.prepareStatement("INSERT OR IGNORE INTO kg_entity(public_id,type,status,created_seq)"
                        + " SELECT ?,term_id,1,0 FROM kg_vocab WHERE kind=1 AND name='organization'");
                        PreparedStatement k = c.prepareStatement("INSERT OR IGNORE INTO kg_collection(name) VALUES(?)");
                        PreparedStatement s = c.prepareStatement("INSERT OR IGNORE INTO kg_entity_scope(ent_rowid,coll_id,n) SELECT e.ent_rowid,k.coll_id,1"
                                + " FROM kg_entity e,kg_collection k WHERE e.public_id=? AND k.name=?")) {
                    for (final String[] r : chunk) {
                        e.setString(1, r[0]); e.executeUpdate();
                        k.setString(1, r[1]); k.executeUpdate();
                        s.setString(1, r[0]); s.setString(2, r[1]); s.executeUpdate();
                    }
                }
                return null;
            });
        }
    }

    /** As MatchingTest.observation: one source page per observation. Rows: actor, type, predicate, value, quote, scope. */
    private static void observations(final KgStore store, final List<Object[]> rows) throws Exception {
        for (int from = 0; from < rows.size(); from += 5000) {
            final List<Object[]> chunk = rows.subList(from, Math.min(rows.size(), from + 5000));
            store.write(WriteClass.GROWTH, 0, c -> {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO kg_observation(public_id,source_id,source_url,content_revision,subject_id,subject_type,"
                        + "organization_id,original_organization_id,identity_context,predicate,value,quote,locator,tier,extractor,vocabulary_version,"
                        + "observed_at,recorded_at,origin_scopes,source_status,certainty) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1,'jsonld/5','test',?,?,'','removed',1)");
                        PreparedStatement k = c.prepareStatement("INSERT OR IGNORE INTO kg_collection(name) VALUES(?)");
                        PreparedStatement s = c.prepareStatement("INSERT INTO kg_observation_scope SELECT o.observation_rowid,k.coll_id FROM kg_observation o,"
                                + "kg_collection k WHERE o.public_id=? AND k.name=?")) {
                    for (final Object[] r : chunk) {
                        final String actor = (String) r[0], type = (String) r[1], predicate = (String) r[2], quote = (String) r[4], scope = (String) r[5];
                        final JSONObject value = (JSONObject) r[3];
                        final String id = "kgo_" + String.format("%020x", ++serial), source = String.format("src%09d", serial);
                        final JSONObject identity = KgJson.obj("organization", new JSONArray().put(KgJson.obj("predicate", "name", "value", "Org " + actor.substring(4, 10))),
                                "subject", new JSONArray().put(KgJson.obj("predicate", "name", "value", "service".equals(type) ? value.optString("literal") : "Engineer")),
                                "assignment", new JSONArray().put(KgJson.obj("predicate", "offers", "quote", "We offer this service", "locator", "/offers")),
                                "employer_assignment", "source_declared");
                        int i = 1;
                        for (final String v : List.of(id, source, "https://example.org/" + source, "revision", actor, type, actor, actor, identity.toString(), predicate,
                                value.toString(), quote, "/description")) p.setString(i++, v);
                        p.setLong(i++, 1_700_000_000_000L + serial);
                        p.setLong(i, NOW);
                        p.executeUpdate();
                        k.setString(1, scope); k.executeUpdate();
                        s.setString(1, id); s.setString(2, scope); s.executeUpdate();
                    }
                }
                return null;
            });
        }
    }
}
