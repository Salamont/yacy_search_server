package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.collections.AutoCorrect;
import net.yacy.scoutro.collections.CollectionDecisions;
import net.yacy.scoutro.collections.DecisionStore;
import net.yacy.scoutro.knowledge.KgRuntime;

/**
 * The plausibility scan of the indexed domains (docs/SCOUTRO_COLLECTION_AUTOCORRECT.md) on a graph stand-in: steps and
 * cursor, the busy graph, two fingerprints (the pages are read again only with a new rules fingerprint; a new Discovery
 * classification, home page reading or OSM signal is assessed without reading them), assessments per domain, collection and
 * source, one submission per domain, the targeted run after a classification, and the fail-safe state.
 */
public class PlausibilityScanTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String BAU = "bauteamcheck-web", STACK = "stackfinder-web", SENIOR = "edelsenior-web", FALLBACK = "sonstige";
    private static final Set<String> KNOWN = Set.of(BAU, STACK, SENIOR, FALLBACK);

    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private final Map<String, String> settings = new HashMap<>();
    private CollectionDecisions decisions;
    private File state;
    private FakeGraph graph;
    private JSONObject discovery = new JSONObject();

    /** The graph: domains with their collections, a content version per domain, foreign offers on the shops. */
    private final class FakeGraph implements PlausibilityScan.Graph {
        final SortedMap<String, KgRuntime.DomainPages> domains = new TreeMap<>();
        final Map<String, String> content = new HashMap<>();
        final Set<String> shops = new java.util.TreeSet<>();
        /** Domains with too little to judge: nothing offered, no word of a vocabulary. */
        final Set<String> quiet = new java.util.TreeSet<>();
        final List<JSONArray> homeSignals = new ArrayList<>();
        final AtomicInteger reads = new AtomicInteger();
        String busy;

        void domain(final String domain, final boolean shop, final String... collections) {
            final KgRuntime.DomainPages p = new KgRuntime.DomainPages();
            p.collections.addAll(List.of(collections));
            p.hosts.add(Integer.toHexString(domain.hashCode() & 0xffffff | 0x100000));
            this.domains.put(domain, p);
            this.content.put(domain, "v1");
            if (shop) this.shops.add(domain);
        }

        @Override public String busy() { return this.busy; }
        @Override public SortedMap<String, KgRuntime.DomainPages> domains() { return this.domains; }
        @Override public String pagesFingerprint(final String domain, final Collection<String> hosts) { return this.content.get(domain); }
        @Override public String vocabularyVersion() { return "vocab-1"; }

        @Override
        public JSONObject textFit(final String domain, final Collection<String> collections, final Collection<String> hosts) {
            this.reads.incrementAndGet();
            final JSONObject by = new JSONObject();
            for (final String c : collections) {
                if (FALLBACK.equals(c)) continue;
                final boolean offered = !this.shops.contains(domain) && !this.quiet.contains(domain);
                Json.put(by, c, Json.obj("vocabulary", c.split("-")[0], "categories", new JSONArray(offered ? List.of(c + "/x") : List.of()), "pages",
                        offered ? 2 : 0, "offered", offered ? 1 : 0, "topics", 0));
            }
            return Json.obj("pages", 3, "collections", by);
        }

        @Override
        public JSONObject offerProfile(final String domain, final Collection<String> collections, final Collection<String> hosts) {
            final JSONArray services = new JSONArray();
            if (this.shops.contains(domain)) {
                for (final String n : List.of("Pralinen", "Trüffel", "Geschenkkorb")) {
                    services.put(Json.obj("name", n, "tiers", new JSONArray(List.of(1)), "categories", new JSONArray(), "pages", 2));
                }
            }
            final JSONObject by = new JSONObject();
            for (final String c : collections) if (!FALLBACK.equals(c)) Json.put(by, c, Json.obj("vocabulary", c.split("-")[0], "services", 0));
            return Json.obj("pages", 3, "services", services, "offerPages", services.length() > 0 ? 2 : 0, "collections", by);
        }

        @Override public List<String[]> structuredIndustries(final String domain) { return List.of(); }
        @Override public Map<String, Set<String>> collectionNace(final Collection<String> collections) { return Map.of(); }

        @Override
        public List<AutoCorrect.Signal> homeSignals(final String domain, final JSONObject assessments, final Collection<String> hosts) {
            final List<AutoCorrect.Signal> out = new ArrayList<>();
            for (final JSONArray a : this.homeSignals) {
                for (int i = 0; i < a.length(); i++) out.add(AutoCorrect.Signal.of(a.optJSONObject(i), domain));
            }
            return out;
        }
    }

    @Before
    public void open() throws Exception {
        this.decisions = new CollectionDecisions(new DecisionStore(this.tmp.newFolder("collections").toPath(),
                this.tmp.newFolder("settings").toPath().resolve("marker")), FALLBACK, false, this.clock::get);
        this.state = new File(this.tmp.getRoot(), "autocorrect.json");
        this.graph = new FakeGraph();
        this.settings.put(AutoCorrectApi.MODE_KEY, "simulate");
        this.discovery.put("domains", new JSONObject());
    }

    private AutoCorrectApi api() {
        return new AutoCorrectApi(new AutoCorrect(this.state, this.clock::get), this.decisions, null, this.settings::get, KNOWN::contains, null)
                .withScan(this.graph, () -> DomainEnrichment.StateFile.parse(this.discovery), () -> List.copyOf(KNOWN), this.clock::get);
    }

    private JSONObject scan(final Object... kv) throws Exception {
        return api().scan(Json.obj(kv), "admin");
    }

    private void classify(final String domain, final String profile, final String collection, final String verdict, final String backend,
            final String error) throws Exception {
        final JSONObject d = this.discovery.getJSONObject("domains").optJSONObject(domain) == null ? new JSONObject()
                : this.discovery.getJSONObject("domains").getJSONObject(domain);
        final JSONObject c = d.optJSONObject("classifications") == null ? new JSONObject() : d.getJSONObject("classifications");
        c.put(profile, Json.obj("profile", profile, "collection", collection, "domain", domain, "verdict", verdict, "confidence", 0.97,
                "reasons", new JSONArray().put(Json.obj("code", "different_topic", "text", "Feinkost, kein Bau")), "classified_at", "2026-10-20T10:00:00Z",
                "classifier", Json.obj("backend", backend, "model", "m", "criteria_version", "2026-10-08.1", "error", error)));
        d.put("classifications", c);
        this.discovery.getJSONObject("domains").put(domain, d);
    }

    private JSONObject caseOf(final String domain) throws Exception {
        final JSONArray cases = api().report(Map.of()).getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) if (domain.equals(cases.getJSONObject(i).getString("domain"))) return cases.getJSONObject(i);
        return null;
    }

    @Test
    public void stepsFollowTheCursorAndAPassEnds() throws Exception {
        for (int i = 0; i < 5; i++) this.graph.domain("bau" + i + "-muster.de", false, BAU);
        this.graph.domain("nur-sonstige.de", false, FALLBACK);
        JSONObject r = scan("maxDomains", 2);
        assertEquals(2, r.getInt("domains"));
        assertEquals("bau1-muster.de", r.getJSONObject("scan").getString("cursor"));
        assertFalse(r.getBoolean("done"));
        // a restart of the process: the cursor is in the state file
        r = scan("maxDomains", 2);
        assertEquals("bau3-muster.de", r.getJSONObject("scan").getString("cursor"));
        r = scan("maxDomains", 2);
        assertEquals("the fallback collection alone is never scanned", 1, r.getInt("domains"));
        assertTrue(r.getBoolean("done"));
        assertTrue(r.getJSONObject("scan").has("completedAt"));
        // the next pass: every domain is unchanged
        r = scan("maxDomains", 200);
        assertEquals(5, r.getInt("domains"));
        assertEquals(5, r.getJSONObject("counters").getInt("unchanged"));
        assertEquals("unchanged domains are never read again", 5, this.graph.reads.get());
        // a collection only
        this.graph.domain("stack-muster.de", false, STACK);
        r = scan("maxDomains", 200, "restart", true, "collection", STACK);
        assertEquals(1, r.getInt("domains"));
        try {
            scan("maxDomains", 201);
            fail("more than 200 domains");
        } catch (final ApiException e) {
            assertEquals("invalid_request", e.code());
        }
    }

    @Test
    public void aBusyGraphPausesTheScanAndKeepsTheCursor() throws Exception {
        for (int i = 0; i < 3; i++) this.graph.domain("bau" + i + "-muster.de", false, BAU);
        scan("maxDomains", 1);
        for (final String reason : List.of("graph_paused", "indexing_queue", "graph_stopped")) {
            this.graph.busy = reason;
            try {
                scan("maxDomains", 1);
                fail("a scan while " + reason);
            } catch (final ApiException e) {
                assertEquals(503, e.status());
                assertEquals("scan_busy", e.code());
                assertEquals(reason, e.toJson().getJSONObject("error").getJSONObject("details").getString("reason"));
            }
        }
        this.graph.busy = null;
        assertEquals("bau1-muster.de", scan("maxDomains", 1).getJSONObject("scan").getString("cursor"));
    }

    @Test
    public void aForeignShopIsSubmittedOnceAndAnUnchangedScanChangesNothing() throws Exception {
        this.graph.domain("feinkost-muster.de", true, BAU);
        this.graph.domain("bau-muster.de", false, BAU);
        JSONObject r = scan("maxDomains", 50);
        assertEquals(1, r.getJSONObject("counters").getInt("suspect"));
        assertEquals(1, r.getJSONObject("actions").getInt("recorded"));
        final JSONObject c = caseOf("feinkost-muster.de");
        assertNotNull(c);
        assertEquals("review", c.getString("result"));
        assertEquals("one_origin_only", c.getString("reason"));
        assertEquals("simulated", c.getString("status"));
        assertEquals(FALLBACK, c.getString("suggested"));
        assertEquals("a matching domain is no case", null, caseOf("bau-muster.de"));
        // the assessments
        JSONObject a = api().assessments(Map.of());
        assertEquals(1, a.getJSONObject("counts").getJSONObject(BAU).getInt("suspect"));
        assertEquals(1, a.getJSONObject("counts").getJSONObject(BAU).getInt("match"));
        a = api().assessments(Map.of("result", "suspect"));
        assertEquals("feinkost-muster.de", a.getJSONArray("items").getJSONObject(0).getString("domain"));
        assertEquals("suspect", a.getJSONArray("items").getJSONObject(0).getJSONObject("rules").getString("result"));
        // a second pass: unchanged, no read, no new case
        final String fingerprint = c.getString("fingerprint");
        r = scan("restart", true);
        assertEquals(2, r.getJSONObject("counters").getInt("unchanged"));
        assertEquals(fingerprint, caseOf("feinkost-muster.de").getString("fingerprint"));
        // a new content is read again
        this.graph.content.put("bau-muster.de", "v2");
        r = scan("restart", true);
        assertEquals(1, r.getJSONObject("counters").getInt("read"));
        // in the mode off the scan assesses but submits nothing
        this.settings.put(AutoCorrectApi.MODE_KEY, "off");
        this.graph.domain("trueffel-muster.de", true, BAU);
        r = scan("domains", new JSONArray(List.of("trueffel-muster.de")));
        assertEquals(1, r.getJSONObject("counters").getInt("suspect"));
        assertFalse(r.getJSONObject("counters").has("submitted"));
        assertEquals(null, caseOf("trueffel-muster.de"));
    }

    @Test
    public void aNewDiscoveryClassificationIsAssessedWithoutReadingThePages() throws Exception {
        // K6: the rules are unchanged, the classification is new
        this.graph.domain("drolshagens-muster.de", false, BAU);
        this.graph.quiet.add("drolshagens-muster.de");
        scan("maxDomains", 50);
        assertEquals("uncertain", api().assessments(Map.of()).getJSONArray("items").getJSONObject(0).getString("result"));
        assertEquals(1, this.graph.reads.get());
        assertEquals(null, caseOf("drolshagens-muster.de"));
        // a heuristic or failed classification changes the input, but is no signal
        classify("drolshagens-muster.de", "bauteamcheck", BAU, "FAIL", "heuristic", "");
        JSONObject r = scan("domains", new JSONArray(List.of("drolshagens-muster.de")));
        assertEquals(1, r.getJSONObject("counters").getInt("reused"));
        assertEquals("the pages are not read again", 1, this.graph.reads.get());
        assertEquals(null, caseOf("drolshagens-muster.de"));
        JSONObject entry = api().assessments(Map.of("source", "discovery")).getJSONArray("items").getJSONObject(0);
        assertFalse(entry.getJSONObject("discovery").getBoolean("signal"));
        assertEquals("heuristic", entry.getJSONObject("discovery").getString("backend"));
        classify("drolshagens-muster.de", "bauteamcheck", BAU, "FAIL", "llm", "timeout");
        scan("domains", new JSONArray(List.of("drolshagens-muster.de")));
        assertEquals(null, caseOf("drolshagens-muster.de"));
        // the model's FAIL: a case, the pages still not read; the targeted run leaves the cursor alone
        final JSONObject before = api().scan(Json.obj("maxDomains", 1), "admin").getJSONObject("scan");
        classify("drolshagens-muster.de", "bauteamcheck", BAU, "FAIL", "llm", "");
        r = scan("domains", new JSONArray(List.of("drolshagens-muster.de")));
        assertEquals(1, this.graph.reads.get());
        assertEquals(before.optString("cursor"), r.getJSONObject("scan").optString("cursor"));
        JSONObject c = caseOf("drolshagens-muster.de");
        assertEquals("review", c.getString("result"));
        assertEquals("one_origin_only", c.getString("reason"));
        boolean llm = false;
        for (int i = 0; i < c.getJSONArray("signals").length(); i++) {
            final JSONObject s = c.getJSONArray("signals").getJSONObject(i);
            if ("llm".equals(s.getString("family")) && "against".equals(s.getString("verdict"))) {
                llm = true;
                assertEquals("discovery_classification", s.getString("kind"));
                assertEquals("2026-10-20T10:00:00Z", s.getString("revision"));
                assertEquals("2026-10-08.1", s.getString("criteria"));
                assertTrue(s.getString("evidence"), s.getString("evidence").startsWith("Discovery bauteamcheck FAIL (0.97): different_topic"));
            }
        }
        assertTrue(c.toString(), llm);
        // the OSM signal of the domain's case (Discovery's submission) is carried over: the contradiction stays visible
        final AutoCorrect engine = new AutoCorrect(this.state, this.clock::get);
        final List<AutoCorrect.Signal> withOsm = new ArrayList<>();
        for (int i = 0; i < c.getJSONArray("signals").length(); i++) withOsm.add(AutoCorrect.Signal.of(c.getJSONArray("signals").getJSONObject(i),
                "drolshagens-muster.de"));
        withOsm.add(AutoCorrect.Signal.of(Json.obj("family", "origin", "verdict", "for", "collection", BAU, "origin", "osm:niedersachsen", "kind",
                "osm_rule", "evidence", "OSM tags[2] of bauteamcheck matched: office=company"), "drolshagens-muster.de"));
        engine.submit("drolshagens-muster.de", BAU, List.of(BAU), withOsm, FALLBACK, false, AutoCorrect.Mode.SIMULATE, 10, null, "discovery");
        assertEquals("contradicting_signals", caseOf("drolshagens-muster.de").getString("reason"));
        final String fingerprint = caseOf("drolshagens-muster.de").getString("fingerprint");
        r = scan("domains", new JSONArray(List.of("drolshagens-muster.de")));
        assertEquals("the OSM signal is a new input", 1, r.getJSONObject("counters").getInt("reused"));
        assertEquals("unchanged", r.getJSONObject("actions").keys().next());
        assertEquals("no flip between the scan and Discovery", fingerprint, caseOf("drolshagens-muster.de").getString("fingerprint"));
        assertEquals("contradicting_signals", caseOf("drolshagens-muster.de").getString("reason"));
        // a new home page reading by the model is a new input as well
        this.graph.homeSignals.add(new JSONArray().put(Json.obj("family", "llm", "verdict", "against", "collection", BAU, "kind", "kg_home_fit",
                "evidence", "Feinkost und Confiserie", "revision", "h1", "criteria", "2026-10-08.1")));
        r = scan("domains", new JSONArray(List.of("drolshagens-muster.de")));
        assertEquals(1, r.getJSONObject("counters").getInt("reused"));
        assertEquals(1, this.graph.reads.get());
    }

    @Test
    public void aDomainInTwoCollectionsIsAssessedPerCollectionAndSubmittedOnce() throws Exception {
        // K1: one domain in two specialist collections, assessed by the rules and Discovery side by side
        this.graph.domain("misch-muster.de", true, STACK, BAU);
        classify("misch-muster.de", "stackfinder", STACK, "PASS", "llm", "");
        final JSONObject r = scan("maxDomains", 50);
        assertEquals(1, r.getJSONObject("counters").getInt("submitted"));
        final JSONObject a = api().assessments(Map.of("limit", "10"));
        assertEquals(2, a.getInt("matching"));
        final JSONObject stack = a.getJSONArray("items").getJSONObject(0).getString("collection").equals(STACK) ? a.getJSONArray("items").getJSONObject(0)
                : a.getJSONArray("items").getJSONObject(1);
        assertTrue(stack.has("rules") && stack.has("discovery"));
        assertEquals("PASS", stack.getJSONObject("discovery").getString("result"));
        // one case, its current collection the alphabetically first with a signal against it
        final JSONObject c = caseOf("misch-muster.de");
        assertEquals(BAU, c.getString("from"));
        assertEquals("multiple_assignment", c.getString("reason"));
        // a new source of one collection leaves the other sources and collections as they are
        final AutoCorrect engine = new AutoCorrect(this.state, this.clock::get);
        engine.assess("misch-muster.de", BAU, AutoCorrect.KG_HOME, Json.obj("result", "mismatch", "at", 1L));
        engine.persist();
        final JSONObject stored = engine.assessments("misch-muster.de");
        assertTrue(stored.getJSONObject(BAU).has("rules") && stored.getJSONObject(BAU).has("kg_home"));
        assertTrue(stored.getJSONObject(STACK).has("rules") && stored.getJSONObject(STACK).has("discovery"));
        // the domain leaves a collection: its assessment is kept as stale
        this.graph.domains.get("misch-muster.de").collections.remove(STACK);
        scan("domains", new JSONArray(List.of("misch-muster.de")));
        final JSONObject after = new AutoCorrect(this.state, this.clock::get).assessments("misch-muster.de");
        assertTrue(after.getJSONObject(STACK).getBoolean("stale"));
        assertTrue(after.getJSONObject(STACK).has("rules"));
        assertFalse(after.getJSONObject(BAU).has("stale"));
    }

    @Test
    public void aTargetedRunTakesDomainsWithoutADiscoveryCrawlAndTheStateIsFailSafe() throws Exception {
        this.graph.domain("manuell-muster.de", true, BAU);
        JSONObject r = scan("domains", new JSONArray(List.of("manuell-muster.de", "fehlt-muster.de")));
        assertEquals(1, r.getJSONObject("counters").getInt("submitted"));
        assertEquals("not_in_graph", r.getJSONObject("notFound").getString("fehlt-muster.de"));
        assertFalse(r.getJSONObject("scan").has("cursor"));
        try {
            scan("domains", new JSONArray(List.of("www.manuell-muster.de")));
            fail("a host is no registrable domain");
        } catch (final ApiException e) {
            assertEquals("invalid_request", e.code());
        }
        // an unreadable state records and submits nothing
        Files.write(this.state.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        try {
            scan("maxDomains", 5);
            fail("a scan on an unreadable state");
        } catch (final ApiException e) {
            assertEquals("autocorrect_unavailable", e.code());
        }
        assertEquals("{broken", new String(Files.readAllBytes(this.state.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void theDiscoveryStateGivesBackendErrorAndCriteria() throws Exception {
        classify("x-muster.de", "bauteamcheck", BAU, "FAIL", "llm", "");
        final Object[] rec = DomainEnrichment.StateFile.parse(this.discovery).get("x-muster.de").classifications.get("bauteamcheck");
        assertEquals("FAIL", rec[0]);
        assertEquals(0.97, (Double) rec[1], 1e-9);
        assertEquals(BAU, rec[3]);
        assertEquals("llm", rec[5]);
        assertEquals("", rec[6]);
        assertEquals("2026-10-08.1", rec[7]);
        assertEquals("different_topic: Feinkost, kein Bau", rec[8]);
        assertEquals("2026-10-20T10:00:00Z", rec[9]);
        assertEquals(1, PlausibilityScan.discoverySignals("x-muster.de", DomainEnrichment.StateFile.parse(this.discovery).get("x-muster.de")).size());
    }
}
