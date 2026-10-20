package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.apache.solr.common.SolrInputDocument;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.collections.AutoCorrect;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.Gates;
import net.yacy.scoutro.knowledge.sync.JsonLdCapture;

/**
 * The plausibility of a domain's collection from the graph alone (docs/SCOUTRO_COLLECTION_AUTOCORRECT.md, no model): a
 * signal against the collection only with a positive finding, offers with structured evidence that are none of the
 * collection's vocabulary and no word of it on the pages; every weaker reading is no signal (uncertain). The difficult
 * cases: a construction company that also sells merchandise, a price list read by the rules only, a short website, a
 * collection without a vocabulary.
 */
public class PlausibilityTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String BAU = "bau-web";
    private static final String MISC = "misc-web";
    private static final List<String> IDS = List.of(BAU, MISC);

    private EmbeddedInstance solr;
    private KgRuntime kg;
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1",
                new String[] {"collection1", "webgraph"});
        final Map<String, String> settings = KgTestSupport.enabled(KgConfig.COLLECTIONS, BAU + "," + MISC, KgConfig.JSONLD_ENABLED, "true",
                KgConfig.VOCAB_PREFIX + BAU, "construction");
        this.kg = KgTestSupport.startCurrent(new KgRuntime.Env(this.tmp.newFolder("data"), settings::get, this.clock::get,
                new KgTestSupport.Probe(), KgStore.SQLITE, false, () -> this.solr.getDefaultServer(), Gates.IDLE));
        assertNotNull(this.kg);
    }

    @After
    public void close() {
        KgRuntime.stop();
        JsonLdCapture.clear();
        if (this.solr != null) this.solr.close();
    }

    private static String product(final String name, final String price) {
        return "{\"@type\":\"Product\",\"name\":\"" + name + "\",\"offers\":{\"@type\":\"Offer\",\"price\":\"" + price + "\",\"priceCurrency\":\"EUR\"}}";
    }

    private static String products(final String... jsonld) {
        return "{\"@context\":\"https://schema.org\",\"@graph\":[" + String.join(",", jsonld) + "]}";
    }

    private int pages;

    private void page(final String url, final String collection, final String ldJson, final String text) throws Exception {
        final String id = String.format("%06dpage%02d", ++this.pages, this.pages);
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", url);
        d.setField("host_s", java.net.URI.create(url).getHost());
        d.setField("host_id_s", Integer.toHexString(java.net.URI.create(url).getHost().hashCode() & 0xffffff | 0x100000));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", List.of(collection));
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        d.setField("title", List.of("Seite " + id));
        d.setField("text_t", text);
        if (ldJson != null) d.setField("ld_json_txt", List.of(ldJson));
        this.solr.getDefaultServer().add(d);
    }

    private void settle() throws Exception {
        this.solr.getDefaultServer().commit();
        final BooleanSupplier done = () -> {
            final JSONObject sync = this.kg.status().optJSONObject("sync");
            final JSONObject lag = sync == null ? null : sync.optJSONObject("lag");
            try {
                return sync != null && sync.optBoolean("initialized") && lag != null && lag.optLong("pending", 1L) == 0L
                        && !lag.optBoolean("reconcile_pending", true) && KgTestSupport.count(this.kg, "SELECT count(*) FROM kg_work") == 0L;
            } catch (final Exception e) {
                return false;
            }
        };
        for (int i = 0; i < 3000; i++) {
            KgTestSupport.step(this.kg);
            if (done.getAsBoolean()) return;
            this.clock.addAndGet(3000L);
        }
        fail("not reached: " + this.kg.status());
    }

    /** The website's signals and the assessment in {@code collection}, as the automation reads them. */
    private Object[] read(final String domain, final String collection) {
        final JSONObject fit = this.kg.textFit(domain, IDS);
        final JSONObject offers = this.kg.offerProfile(domain, IDS);
        final List<AutoCorrect.Signal> signals = new ArrayList<>(AutoCorrectApi.textFitSignals(domain, collection, fit));
        signals.addAll(AutoCorrectApi.plausibilitySignals(domain, collection, offers, fit));
        return new Object[] {signals, AutoCorrectApi.assess(collection, signals, fit), fit, offers};
    }

    @SuppressWarnings("unchecked")
    private static boolean plausibilityAgainst(final Object[] read) {
        for (final AutoCorrect.Signal s : (List<AutoCorrect.Signal>) read[0]) {
            if (AutoCorrect.PLAUSIBILITY.equals(s.family) && AutoCorrect.AGAINST.equals(s.verdict)) return true;
        }
        return false;
    }

    @Test
    public void foreignOffersWithoutAWordOfTheVocabularyAreASignal() throws Exception {
        page("https://feinkost-muster.de/", BAU, products(product("Pralinen", "12.90")), "Feinkost Muster: Pralinen, Trüffel und Geschenke.");
        page("https://feinkost-muster.de/shop/trueffel", BAU, products(product("Trüffel", "8.50"), product("Geschenkkorb", "39.00")),
                "Trüffel und Geschenkkörbe aus eigener Herstellung.");
        settle();
        final Object[] r = read("feinkost-muster.de", BAU);
        assertTrue(r[3].toString(), plausibilityAgainst(r));
        assertEquals(AutoCorrectApi.SUSPECT, r[1]);
        @SuppressWarnings("unchecked") final AutoCorrect.Signal s = ((List<AutoCorrect.Signal>) r[0]).get(((List<AutoCorrect.Signal>) r[0]).size() - 1);
        assertEquals("website:feinkost-muster.de", s.origin);
        assertEquals("foreign_offers", s.kind);
        assertEquals("plausibility/1", s.criteria);
        assertTrue(s.evidence, s.evidence.contains("Pralinen") && s.evidence.length() <= 500);
    }

    @Test
    public void aConstructionCompanyThatSellsMerchandiseIsNoFinding() throws Exception {
        // K4: products outside the vocabulary (JSON-LD), but a construction word on the home page: uncertain, never against
        page("https://dach-meister.de/", BAU, null, "Dach-Meister: Ihr Dachdecker in Musterstadt seit 1980.");
        page("https://dach-meister.de/shop/tasse", BAU, products(product("Kaffeetasse", "9.90"), product("Kappe", "14.90")), "Unser Fanshop.");
        page("https://dach-meister.de/shop/shirt", BAU, products(product("T-Shirt", "19.90")), "Unser Fanshop: T-Shirts.");
        settle();
        final Object[] r = read("dach-meister.de", BAU);
        assertEquals(r[3].toString(), 3, ((JSONObject) r[3]).getJSONArray("services").length());
        assertTrue(r[2].toString(), ((JSONObject) r[2]).getJSONObject("collections").getJSONObject(BAU).getInt("topics") > 0);
        assertTrue("a construction topic keeps the plausibility silent: " + r[2], !plausibilityAgainst(r));
        assertEquals(AutoCorrectApi.UNCERTAIN, r[1]);
    }

    @Test
    public void aConstructionCompanyWithProductsAndItsServicesMatches() throws Exception {
        page("https://bau-muster.de/leistungen", BAU, null, "Unsere Leistungen: Dachdeckerarbeiten, Trockenbau und Innenausbau aus einer Hand."
                + " Fordern Sie ein Angebot an.");
        page("https://bau-muster.de/shop/a", BAU, products(product("Kaffeetasse", "9.90"), product("Kappe", "14.90")), "Fanshop.");
        page("https://bau-muster.de/shop/b", BAU, products(product("T-Shirt", "19.90")), "Fanshop.");
        settle();
        final Object[] r = read("bau-muster.de", BAU);
        assertTrue(r[2].toString(), ((JSONObject) r[2]).getJSONObject("collections").getJSONObject(BAU).getInt("offered") > 0);
        assertTrue(!plausibilityAgainst(r));
        assertEquals(AutoCorrectApi.MATCH, r[1]);
    }

    @Test
    public void aPriceListReadByTheRulesAloneIsNoFinding() throws Exception {
        // tier 2 only: the labels of a price list are no structured or quoted offer
        page("https://studio-muster.de/preise", BAU, null, "Preise. Grundpaket: 49 € pro Monat. Premiumpaket: 99 € pro Monat."
                + " Einzelstunde: 35 € pro Stunde.");
        page("https://studio-muster.de/kurse", BAU, null, "Preise. Kursgebühr: 120 € pro Kurs. Probestunde: 15 € einmalig.");
        settle();
        final Object[] r = read("studio-muster.de", BAU);
        assertEquals(r[3].toString(), 0, ((JSONObject) r[3]).getJSONArray("services").length());
        assertTrue(!plausibilityAgainst(r));
        assertEquals(AutoCorrectApi.UNCERTAIN, r[1]);
    }

    @Test
    public void aShortWebsiteAndACollectionWithoutVocabularyAreUncertain() throws Exception {
        page("https://kurz-muster.de/", BAU, products(product("Pralinen", "12.90")), "Willkommen.");
        page("https://misc-muster.de/", MISC, products(product("Pralinen", "12.90"), product("Trüffel", "8.50")), "Pralinen.");
        page("https://misc-muster.de/b", MISC, products(product("Geschenkkorb", "39.00")), "Geschenke.");
        settle();
        final Object[] shortSite = read("kurz-muster.de", BAU);
        assertTrue(!plausibilityAgainst(shortSite));
        assertEquals(AutoCorrectApi.UNCERTAIN, shortSite[1]);
        // the collection without a vocabulary is never judged: no text fit of it, no plausibility signal
        final Object[] misc = read("misc-muster.de", MISC);
        assertTrue(!((JSONObject) misc[2]).getJSONObject("collections").has(MISC));
        assertTrue(!plausibilityAgainst(misc));
        assertEquals(AutoCorrectApi.UNCERTAIN, misc[1]);
    }

    @Test
    public void theScanReadsTheRunningGraphWithoutAModelAndWritesNothingToIt() throws Exception {
        page("https://feinkost-muster.de/", BAU, products(product("Pralinen", "12.90")), "Feinkost Muster: Pralinen, Trüffel und Geschenke.");
        page("https://feinkost-muster.de/shop/trueffel", BAU, products(product("Trüffel", "8.50"), product("Geschenkkorb", "39.00")), "Trüffel.");
        page("https://bau-muster.de/leistungen", BAU, null, "Unsere Leistungen: Dachdeckerarbeiten, Trockenbau und Innenausbau aus einer Hand.");
        page("https://misc-muster.de/", MISC, null, "Etwas anderes.");
        settle();
        final String graphBefore = graphState();
        final java.nio.file.Path dir = this.tmp.newFolder("collections").toPath();
        final net.yacy.scoutro.collections.CollectionDecisions decisions = new net.yacy.scoutro.collections.CollectionDecisions(
                new net.yacy.scoutro.collections.DecisionStore(dir, this.tmp.newFolder("settings").toPath().resolve("marker")), "sonstige", false,
                this.clock::get);
        final Map<String, String> settings = new java.util.HashMap<>(Map.of(AutoCorrectApi.MODE_KEY, "simulate"));
        final AutoCorrectApi api = new AutoCorrectApi(new AutoCorrect(dir.resolve("autocorrect.json").toFile(), this.clock::get), decisions, null,
                settings::get, Set.of(BAU, MISC, "sonstige")::contains, null).withScan(ScoutroActions.scanGraph(this.kg), Map::of, () -> IDS, this.clock::get);
        JSONObject r = api.scan(new JSONObject(), "admin");
        assertEquals(r.toString(), 3, r.getInt("domains"));
        assertEquals(r.toString(), 1, r.getJSONObject("counters").getInt("suspect"));
        assertEquals(1, r.getJSONObject("actions").getInt("recorded"));
        assertTrue(r.getBoolean("done"));
        final JSONObject a = api.assessments(Map.of());
        assertEquals(a.toString(), 1, a.getJSONObject("counts").getJSONObject(BAU).getInt("suspect"));
        assertEquals(a.toString(), 1, a.getJSONObject("counts").getJSONObject(BAU).getInt("match"));
        assertEquals(a.toString(), 1, a.getJSONObject("counts").getJSONObject(MISC).getInt("uncertain"));
        // a second pass: nothing changed, nothing read; never a model, never a write to the graph
        r = api.scan(new JSONObject(), "admin");
        assertEquals(r.toString(), 3, r.getJSONObject("counters").getInt("unchanged"));
        assertEquals("no model and no graph write", graphBefore, graphState());
        // a paused graph: the scan waits
        this.kg.pause();
        try {
            api.scan(new JSONObject(), "admin");
            fail("a scan while the graph is paused");
        } catch (final ApiException e) {
            assertEquals("scan_busy", e.code());
        }
        this.kg.resume();
    }

    /** What a model call or a graph write would change: the LLM queue, statements, evidence, changes, the documents. */
    private String graphState() throws Exception {
        return KgTestSupport.count(this.kg, "SELECT count(*) FROM kg_llm_work") + "/" + KgTestSupport.count(this.kg, "SELECT count(*) FROM kg_statement")
                + "/" + KgTestSupport.count(this.kg, "SELECT count(*) FROM kg_evidence") + "/" + KgTestSupport.count(this.kg, "SELECT count(*) FROM kg_change")
                + "/" + KgTestSupport.count(this.kg, "SELECT coalesce(max(processed_at), 0) FROM kg_doc");
    }

    @Test
    public void theHostIndexFindsTheSamePagesAsTheUrlScan() throws Exception {
        page("https://www.feinkost-muster.de/", BAU, null, "Feinkost.");
        page("https://shop.feinkost-muster.de/a", BAU, null, "Shop.");
        page("https://anderes-muster.de/feinkost-muster.de", BAU, null, "Ein Link auf feinkost-muster.de.");
        settle();
        final Map<String, java.util.Set<String>> hosts = this.kg.domainHosts();
        assertEquals(hosts.toString(), 2, hosts.get("feinkost-muster.de").size());
        // the same pages with the host ids given and looked up; never the other domain's page that names it in its url
        assertEquals(2, this.kg.textFit("feinkost-muster.de", IDS).getInt("pages"));
        assertEquals(2, this.kg.textFit("feinkost-muster.de", IDS, hosts.get("feinkost-muster.de")).getInt("pages"));
        assertEquals(2, this.kg.offerProfile("feinkost-muster.de", IDS).getInt("pages"));
    }
}
