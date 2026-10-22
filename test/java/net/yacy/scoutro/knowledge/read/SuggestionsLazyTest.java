/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.read;

import static org.junit.Assert.*;
import java.sql.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import net.yacy.scoutro.knowledge.*;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.derive.MatchingService;
import net.yacy.scoutro.knowledge.extract.BusinessSignals;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.*;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * 0.11.8: the suggestion read path validates and ranks every matching contribution first and builds only the shown ones in
 * full (light reasons, a memo per read, pooled statements). Every answer must be exactly what the former path (every
 * contribution built in full) gave: pages, contribution pages, the business view and the network, for several viewers.
 */
public class SuggestionsLazyTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static final long NOW = 1_800_000_000_000L;
    private KgStore store;
    private KgReader reader;
    private int serial;
    private final List<String> providers = new ArrayList<>(), candidates = new ArrayList<>();

    @Before public void open() throws Exception {
        final Map<String, String> settings = new HashMap<>(KgTestSupport.enabled());
        settings.put(KgConfig.JOBS_COLLECTIONS, "a,b,c");
        final KgConfig cfg = KgTestSupport.config(settings);
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        this.store = KgStore.open(paths, cfg, new StorageGuard(cfg, paths, new KgTestSupport.Probe(), () -> NOW), KgStore.SQLITE, () -> NOW);
        this.store.write(WriteClass.SYSTEM, 0, c -> { new Terms().seed(c); return null; });
        this.reader = new KgReader(this.store, cfg, () -> NOW);
        final String[] offers = {"SAP Beratung", "SAP S/4HANA Einführung und Support", "Revit Schulung", "SAP Integration"};
        for (int i = 0; i < 6; i++) {
            final String p = KgIds.entityId("organization", "test", "", "provider" + i);
            this.providers.add(p);
            live(p, i % 3 == 2 ? "c" : "a");
            observation(p, "service", "name", KgJson.obj("literal", offers[i % offers.length]), offers[i % offers.length], i % 3 == 2 ? "c" : "a");
            if (i % 2 == 0) observation(p, "service", "name", KgJson.obj("literal", "SAP Beratung"), "SAP Beratung", "a"); // a corroborating twin
        }
        final String[][] signals = {
            {"organization", "Wir nutzen SAP intern."},
            {"organization", "Wir nutzen SAP S/4HANA intern."},
            {"organization", "Wir nutzen Revit intern."},
            {"job", "Wir nutzen SAP S/4HANA intern."},
            {"organization", "Wir planen die Migration auf SAP S/4HANA."},
        };
        final Map<Integer, String> organizationSignal = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            final String cnd = KgIds.entityId("organization", "test", "", "candidate" + i);
            this.candidates.add(cnd);
            live(cnd, i % 4 == 3 ? "c" : "b");
            for (int k = 0; k <= i % 3; k++) {
                final String[] s = signals[(i + k) % signals.length];
                for (final String value : BusinessSignals.read("system_signal", s[1], "text", s[0], "Candidate")) {
                    final String id = observation(cnd, s[0], "system_signal", new JSONObject(value), s[1], i % 4 == 3 ? "c" : "b");
                    if ("organization".equals(s[0])) organizationSignal.putIfAbsent(i, id);
                }
            }
        }
        // the checks the read preloads: a company passage on a job page (its own or another employer's posting) ...
        postingOn(organizationSignal.get(1), this.candidates.get(1));
        postingOn(organizationSignal.get(5), this.candidates.get(5));
        postingOn(organizationSignal.get(7), this.providers.get(0));
        for (final int i : new int[] {2, 6}) system(this.candidates.get(i), "Wir nutzen SAP unternehmensweit intern.");
        JSONObject run;
        int runs = 0;
        do { run = new MatchingService(this.store, cfg).run(NOW); } while (!run.getBoolean("cycle_complete") && ++runs < 50);
        assertTrue("contributions", this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_match_contribution")) > 20);
        // ... and a later shutdown the stored contributions do not know yet
        system(this.candidates.get(2), "Wir haben SAP unternehmensweit abgeschaltet.");
    }

    @After public void close() {
        Suggestions.lazy = true;
        this.store.close();
    }

    @Test public void theLazyPathAnswersExactlyAsTheFullOne() throws Exception {
        final List<Viewer[]> viewers = List.of(
                new Viewer[] {this.reader.viewer(List.of("a", "b", "c")), this.reader.viewer(List.of("a", "b", "c"))},
                new Viewer[] {this.reader.viewer(List.of("a")), this.reader.viewer(List.of("a", "b"))},
                new Viewer[] {this.reader.viewer(List.of("b")), this.reader.viewer(List.of("a", "b"))},
                new Viewer[] {this.reader.viewer(List.of("c")), this.reader.viewer(List.of("a", "b", "c"))},
                new Viewer[] {this.reader.viewer(List.of("a")), this.reader.viewer(List.of("a"))});
        final List<String> entities = new ArrayList<>(this.providers);
        entities.addAll(this.candidates);
        int compared = 0, nonEmpty = 0;
        for (final String id : entities) {
            for (final Viewer[] v : viewers) {
                final Map<String, String> full = answers(id, v[0], v[1], false), light = answers(id, v[0], v[1], true);
                assertEquals(id + " " + full.keySet(), full.keySet(), light.keySet());
                for (final String key : full.keySet()) {
                    assertEquals(id + " " + key, full.get(key), light.get(key));
                    assertFalse(key + " leaks an internal field", light.get(key).contains("\"_mine\""));
                    compared++;
                }
                if (full.get("page").startsWith("{") && new JSONObject(full.get("page")).getInt("total") > 0) nonEmpty++;
            }
        }
        assertTrue("compared " + compared, compared > 100);
        assertTrue("pages with suggestions " + nonEmpty, nonEmpty > 10);
    }

    /** Every answer of the suggestion read path for one entity and viewer, as JSON text. */
    private Map<String, String> answers(final String id, final Viewer origin, final Viewer permitted, final boolean lazy) throws Exception {
        Suggestions.lazy = lazy;
        final Map<String, String> out = new TreeMap<>();
        final Suggestions s = new Suggestions(this.reader);
        try {
            final JSONObject page = s.page(id, 0, 100, origin, permitted);
            out.put("page", page.toString());
            out.put("page-2-2", s.page(id, 2, 2, origin, permitted).toString());
            out.put("page-0-1", s.page(id, 0, 1, origin, permitted).toString());
            final JSONArray items = page.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                final String proposal = items.getJSONObject(i).getString("id");
                out.put("contributions-" + i, s.contributions(id, proposal, 0, 100, origin, permitted).toString());
                out.put("contributions-" + i + "-1-1", s.contributions(id, proposal, 1, 1, origin, permitted).toString());
            }
        } catch (final KgReader.NotFound e) {
            out.put("page", "not found");
        }
        try {
            out.put("business", new BusinessView(this.reader).entity(id, origin, permitted, false).toString());
        } catch (final KgReader.NotFound e) {
            out.put("business", "not found");
        }
        final BusinessGraph.Query q = new BusinessGraph.Query();
        q.suggested = true;
        q.depth = 1;
        try {
            out.put("network", new BusinessGraph(this.reader).neighborhood(id, q, origin, permitted).toString());
        } catch (final KgReader.NotFound e) {
            out.put("network", "not found");
        }
        return out;
    }

    private void system(final String actor, final String text) throws Exception {
        for (final String value : BusinessSignals.read("system_signal", text, "text", "organization", "Candidate")) {
            observation(actor, "organization", "system_signal", new JSONObject(value), text, "b");
        }
    }

    /** As MatchingTest.postingOn: the page of a signal also states a job and its employer. */
    private void postingOn(final String signal, final String employer) throws Exception {
        final String id = "kgo_" + String.format("%020x", ++this.serial);
        this.store.write(WriteClass.GROWTH, 0, c -> {
            try (PreparedStatement p = c.prepareStatement("INSERT INTO kg_observation(public_id,source_id,source_url,content_revision,subject_id,subject_type,"
                    + "organization_id,original_organization_id,identity_context,predicate,value,quote,locator,tier,extractor,vocabulary_version,observed_at,"
                    + "recorded_at,origin_scopes,source_status,certainty) SELECT ?,source_id,source_url,content_revision,subject_id,'job',?,?,identity_context,"
                    + "'hiring_organization',?,'Source-declared employer is hiring','/hiringOrganization',tier,extractor,vocabulary_version,observed_at,"
                    + "recorded_at,origin_scopes,source_status,certainty FROM kg_observation WHERE public_id=?")) {
                p.setString(1, id); p.setString(2, employer); p.setString(3, employer); p.setString(4, employer); p.setString(5, signal); p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO kg_observation_scope SELECT target.observation_rowid,old.coll_id FROM kg_observation_scope old"
                    + " JOIN kg_observation origin USING(observation_rowid),kg_observation target WHERE origin.public_id=? AND target.public_id=?")) {
                p.setString(1, signal); p.setString(2, id); p.executeUpdate();
            }
            return null;
        });
    }

    private void live(final String actor, final String scope) throws Exception {
        this.store.write(WriteClass.SYSTEM, 0, c -> {
            try (PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO kg_entity(public_id,type,status,created_seq)"
                    + " SELECT ?,term_id,1,0 FROM kg_vocab WHERE kind=1 AND name='organization'")) { p.setString(1, actor); p.executeUpdate(); }
            try (PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO kg_collection(name) VALUES(?)")) { p.setString(1, scope); p.executeUpdate(); }
            try (PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO kg_entity_scope(ent_rowid,coll_id,n) SELECT e.ent_rowid,k.coll_id,1"
                    + " FROM kg_entity e,kg_collection k WHERE e.public_id=? AND k.name=?")) { p.setString(1, actor); p.setString(2, scope); p.executeUpdate(); }
            return null;
        });
    }

    /** As MatchingTest: one source page per observation, with the identity the matching rules read. */
    private String observation(final String actor, final String type, final String predicate, final JSONObject value, final String quote,
            final String scope) throws Exception {
        final String id = "kgo_" + String.format("%020x", ++this.serial), source = String.format("src%09d", this.serial);
        final JSONObject identity = KgJson.obj("organization", new JSONArray().put(KgJson.obj("predicate", "name", "value", "Org " + this.serial)),
                "subject", new JSONArray().put(KgJson.obj("predicate", "name", "value", "service".equals(type) ? value.optString("literal") : "Engineer")),
                "assignment", new JSONArray().put(KgJson.obj("predicate", "offers", "quote", "We offer this service", "locator", "/offers")),
                "employer_assignment", "source_declared");
        this.store.write(WriteClass.GROWTH, 0, c -> {
            try (PreparedStatement p = c.prepareStatement("INSERT INTO kg_observation(public_id,source_id,source_url,content_revision,subject_id,subject_type,"
                    + "organization_id,original_organization_id,identity_context,predicate,value,quote,locator,tier,extractor,vocabulary_version,"
                    + "observed_at,recorded_at,origin_scopes,source_status,certainty) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1,'jsonld/5','test',?,?,'','removed',1)")) {
                int n = 1;
                for (final String s : List.of(id, source, "https://example.org/" + source, "revision", actor, type, actor, actor, identity.toString(), predicate,
                        value.toString(), quote, "/description")) p.setString(n++, s);
                p.setLong(n++, 1_700_000_000_000L + this.serial);
                p.setLong(n, NOW);
                p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO kg_collection(name) VALUES(?)")) { p.setString(1, scope); p.executeUpdate(); }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO kg_observation_scope SELECT o.observation_rowid,k.coll_id FROM kg_observation o,"
                    + "kg_collection k WHERE o.public_id=? AND k.name=?")) { p.setString(1, id); p.setString(2, scope); p.executeUpdate(); }
            return null;
        });
        return id;
    }
}
