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
    public void aHomePageReadingIsRecordedPerCollectionAndCountsOnlyWhileCurrent() throws Exception {
        // K5: the model's reading of a home page in two collections, recorded per collection as the source kg_home
        final AutoCorrect engine = new AutoCorrect(this.state, this.clock::get);
        final List<String> rescans = new ArrayList<>();
        final CollectionFitRecorder recorder = new CollectionFitRecorder(() -> engine, d -> rescans.add(d), () -> true, this.clock::get);
        final net.yacy.scoutro.knowledge.sync.CollectionFit.Question q = net.yacy.scoutro.knowledge.sync.CollectionFit.question(
                net.yacy.scoutro.knowledge.sync.CollectionFit.read(new File("tools/scoutro/discovery/profiles.json")), List.of(BAU, STACK), List.of(), Map.of());
        final String text = "Feinkost und Confiserie: Pralinen und Trüffel.";
        final net.yacy.scoutro.knowledge.sync.CollectionFit.Verdict v = net.yacy.scoutro.knowledge.sync.CollectionFit.check(new JSONObject(
                "{\"verdicts\":{\"" + BAU + "\":\"mismatch\",\"" + STACK + "\":\"match\"},\"best\":\"" + STACK + "\",\"quote\":\"Feinkost und Confiserie\"}"),
                q, text);
        assertTrue(recorder.needed("misch-muster.de", BAU, "AB12", "c1"));
        recorder.verdict("misch-muster.de", "AAAAAAhome01", "AB12", "TEST/m", "c1", v);
        assertEquals("a mismatch with the automation on: the domain is scanned again", List.of("misch-muster.de"), rescans);
        assertFalse("this content under these criteria is read", recorder.needed("misch-muster.de", BAU, "ab12", "c1"));
        assertTrue("another content", recorder.needed("misch-muster.de", BAU, "CD34", "c1"));
        assertTrue("other criteria", recorder.needed("misch-muster.de", BAU, "AB12", "c2"));
        final JSONObject stored = new AutoCorrect(this.state, this.clock::get).assessments("misch-muster.de");
        assertEquals("mismatch", stored.getJSONObject(BAU).getJSONObject("kg_home").getString("result"));
        assertEquals("match", stored.getJSONObject(STACK).getJSONObject("kg_home").getString("result"));
        assertEquals("Feinkost und Confiserie", stored.getJSONObject(BAU).getJSONObject("kg_home").getString("quote"));
        // the signals only while the page's content and the criteria are current
        List<AutoCorrect.Signal> sig = PlausibilityScan.homeSignals("misch-muster.de", stored, id -> "ab12", "c1");
        assertEquals(sig.toString(), 2, sig.size());
        boolean against = false, match = false;
        for (final AutoCorrect.Signal x : sig) {
            assertEquals("llm", x.family);
            assertEquals("kg_home_fit", x.kind);
            assertEquals("website:misch-muster.de", x.origin);
            against |= BAU.equals(x.collection) && "against".equals(x.verdict);
            match |= STACK.equals(x.collection) && "for".equals(x.verdict);
        }
        assertTrue(against && match);
        assertEquals("the page changed", 0, PlausibilityScan.homeSignals("misch-muster.de", stored, id -> "CD34", "c1").size());
        assertEquals("the page is gone", 0, PlausibilityScan.homeSignals("misch-muster.de", stored, id -> null, "c1").size());
        assertEquals("other criteria", 0, PlausibilityScan.homeSignals("misch-muster.de", stored, id -> "AB12", "c2").size());
        // an unquoted reading (K7): recorded, but no signal at all
        final net.yacy.scoutro.knowledge.sync.CollectionFit.Verdict unquoted = net.yacy.scoutro.knowledge.sync.CollectionFit.check(new JSONObject(
                "{\"verdicts\":{\"" + BAU + "\":\"match\"},\"best\":\"none\",\"quote\":\"Wir bauen\"}"), q, text);
        recorder.verdict("unbelegt-muster.de", "BBBBBBhome02", "EF56", "TEST/m", "c1", unquoted);
        assertEquals("no rescan without a mismatch", 1, rescans.size());
        assertEquals(0, PlausibilityScan.homeSignals("unbelegt-muster.de", new AutoCorrect(this.state, this.clock::get).assessments("unbelegt-muster.de"),
                id -> "EF56", "c1").size());
        // best is never a signal of its own, only a hint in the evidence: not for an uncertain assigned collection
        final net.yacy.scoutro.knowledge.sync.CollectionFit.Verdict bestOnly = net.yacy.scoutro.knowledge.sync.CollectionFit.check(new JSONObject(
                "{\"verdicts\":{\"" + BAU + "\":\"uncertain\"},\"best\":\"" + STACK + "\",\"quote\":\"Feinkost und Confiserie\"}"), q, text);
        recorder.verdict("best-muster.de", "CCCCCChome03", "AA11", "TEST/m", "c1", bestOnly);
        assertEquals("uncertain everywhere, best alone: no signal", 0, PlausibilityScan.homeSignals("best-muster.de",
                new AutoCorrect(this.state, this.clock::get).assessments("best-muster.de"), id -> "AA11", "c1").size());
        // a mismatch names its best collection as a hint in the evidence, never as a signal for it
        final net.yacy.scoutro.knowledge.sync.CollectionFit.Verdict hinted = net.yacy.scoutro.knowledge.sync.CollectionFit.check(new JSONObject(
                "{\"verdicts\":{\"" + BAU + "\":\"mismatch\"},\"best\":\"" + STACK + "\",\"quote\":\"Feinkost und Confiserie\"}"), q, text);
        recorder.verdict("hinweis-muster.de", "DDDDDDhome04", "BB22", "TEST/m", "c1", hinted);
        final List<AutoCorrect.Signal> hintSig = PlausibilityScan.homeSignals("hinweis-muster.de",
                new AutoCorrect(this.state, this.clock::get).assessments("hinweis-muster.de"), id -> "BB22", "c1");
        assertEquals(hintSig.toString(), 1, hintSig.size());
        assertEquals(BAU, hintSig.get(0).collection);
        assertEquals("against", hintSig.get(0).verdict);
        assertTrue(hintSig.get(0).evidence, hintSig.get(0).evidence.contains("a hint only: " + STACK));
        // the scan takes the current reading: one submission, the current collection the one with the mismatch
        this.graph.domain("misch-muster.de", false, STACK, BAU);
        this.graph.quiet.add("misch-muster.de");
        this.graph.homeSignals.add(new JSONArray(List.of(PlausibilityScan.json(sig.get(0)), PlausibilityScan.json(sig.get(1)))));
        final JSONObject r = scan("domains", new JSONArray(List.of("misch-muster.de")));
        assertEquals(r.toString(), 1, r.getJSONObject("counters").getInt("submitted"));
        final JSONObject c = caseOf("misch-muster.de");
        assertEquals(BAU, c.getString("from"));
        assertEquals("review", c.getString("result"));
        assertEquals("multiple_assignment", c.getString("reason"));
    }

    @Test
    public void bestWithAnUncertainVerdictNeverBlocksOrSteersACorrection() throws Exception {
        // the decision "uncertain gives no signal": best on an uncertain collection is a hint only, also beside signals against
        final AutoCorrect engine = new AutoCorrect(this.state, this.clock::get);
        final CollectionFitRecorder recorder = new CollectionFitRecorder(() -> engine, d -> null, () -> true, this.clock::get);
        final net.yacy.scoutro.knowledge.sync.CollectionFit.Profiles profiles = net.yacy.scoutro.knowledge.sync.CollectionFit.read(
                new File("tools/scoutro/discovery/profiles.json"));
        final String text = "Feinkost und Confiserie: Pralinen und Trüffel.";
        final AutoCorrect.Signal textFitAgainst = AutoCorrect.Signal.of(Json.obj("family", "text_fit", "verdict", "against", "collection", BAU,
                "kind", "fits_other_vocabulary", "evidence", "no construction offer on 8 pages"), "gegen-muster.de");
        final AutoCorrect.Signal osmAgainst = AutoCorrect.Signal.of(Json.obj("family", "origin", "verdict", "against", "collection", BAU,
                "origin", "osm:node/42", "kind", "osm_tag", "evidence", "shop=confectionery"), "gegen-muster.de");
        // 1. the current collection uncertain and named best: no signal for it, the correction stays a correction
        final net.yacy.scoutro.knowledge.sync.CollectionFit.Question one = net.yacy.scoutro.knowledge.sync.CollectionFit.question(profiles,
                List.of(BAU), List.of(), Map.of());
        recorder.verdict("gegen-muster.de", "EEEEEEhome05", "CC33", "TEST/m", "c1", net.yacy.scoutro.knowledge.sync.CollectionFit.check(
                new JSONObject("{\"verdicts\":{\"" + BAU + "\":\"uncertain\"},\"best\":\"" + BAU + "\",\"quote\":\"Feinkost und Confiserie\"}"),
                one, text));
        final List<AutoCorrect.Signal> home = PlausibilityScan.homeSignals("gegen-muster.de",
                new AutoCorrect(this.state, this.clock::get).assessments("gegen-muster.de"), id -> "CC33", "c1");
        assertEquals("no signal from an uncertain verdict, whatever best says: " + home, 0, home.size());
        final List<AutoCorrect.Signal> signals = new ArrayList<>(List.of(textFitAgainst, osmAgainst));
        signals.addAll(home);
        AutoCorrect.Outcome o = AutoCorrect.evaluate(BAU, List.of(BAU), signals, FALLBACK, false);
        assertEquals("never contradicting_signals from best: " + o.reason, AutoCorrect.CORRECT, o.result);
        assertEquals("fallback_target", o.reason);
        // 2. another assigned collection uncertain and named best, the current one a mismatch: best steers no target or hint
        final net.yacy.scoutro.knowledge.sync.CollectionFit.Question two = net.yacy.scoutro.knowledge.sync.CollectionFit.question(profiles,
                List.of(BAU, STACK), List.of(), Map.of());
        recorder.verdict("ziel-muster.de", "FFFFFFhome06", "DD44", "TEST/m", "c1", net.yacy.scoutro.knowledge.sync.CollectionFit.check(
                new JSONObject("{\"verdicts\":{\"" + BAU + "\":\"mismatch\",\"" + STACK + "\":\"uncertain\"},\"best\":\"" + STACK
                        + "\",\"quote\":\"Feinkost und Confiserie\"}"), two, text));
        final List<AutoCorrect.Signal> home2 = PlausibilityScan.homeSignals("ziel-muster.de",
                new AutoCorrect(this.state, this.clock::get).assessments("ziel-muster.de"), id -> "DD44", "c1");
        assertEquals(home2.toString(), 1, home2.size());
        assertEquals("against", home2.get(0).verdict);
        assertEquals(BAU, home2.get(0).collection);
        final List<AutoCorrect.Signal> signals2 = new ArrayList<>(List.of(textFitAgainst, osmAgainst));
        signals2.addAll(home2);
        o = AutoCorrect.evaluate(BAU, List.of(BAU), signals2, FALLBACK, false);
        assertEquals(AutoCorrect.CORRECT, o.result);
        assertEquals("no specialist target from best: " + o.target, FALLBACK, o.target);
        o = AutoCorrect.evaluate(BAU, List.of(BAU, STACK), signals2, FALLBACK, false);
        assertEquals("multiple_assignment", o.reason);
        assertEquals("the suggested target is not best's collection", FALLBACK, o.suggested);
        // 3. an explicit, quoted match still speaks for its collection (unchanged)
        recorder.verdict("treffer-muster.de", "GGGGGGhome07", "EE55", "TEST/m", "c1", net.yacy.scoutro.knowledge.sync.CollectionFit.check(
                new JSONObject("{\"verdicts\":{\"" + BAU + "\":\"match\"},\"best\":\"" + BAU + "\",\"quote\":\"Feinkost und Confiserie\"}"),
                one, text));
        final List<AutoCorrect.Signal> home3 = PlausibilityScan.homeSignals("treffer-muster.de",
                new AutoCorrect(this.state, this.clock::get).assessments("treffer-muster.de"), id -> "EE55", "c1");
        assertEquals(home3.toString(), 1, home3.size());
        assertEquals("for", home3.get(0).verdict);
        assertFalse("best equal to the collection adds no hint", home3.get(0).evidence.contains("a hint only"));
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
