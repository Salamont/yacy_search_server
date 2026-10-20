package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.collections.AutoCorrect;
import net.yacy.scoutro.collections.CollectionDecision;
import net.yacy.scoutro.collections.CollectionDecisions;
import net.yacy.scoutro.collections.DecisionStore;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.Gates;
import net.yacy.scoutro.knowledge.sync.JsonLdCapture;

/**
 * The pilot of the data quality programme on isolated test data, end to end on the integration branch (R1 to R4): the
 * nut confectioner drolshagens.de, crawled into bauteamcheck-web (construction vocabulary) through an OSM rule, with
 * the word "Hersteller" that the construction vocabulary reads as {@code construction.handel/hersteller}. The role word is
 * no fit to the construction vocabulary (text fit 2); with one product the website is no finding (uncertain), with the
 * products of the production case it offers clearly something else (plausibility): a case for the administrator, never a
 * move, also against the OSM signal for the collection. The correction automation in simulation writes nothing; the
 * administrator's exact decision moves the domain to the fallback collection, the graph reads its pages again without the
 * construction vocabulary (no model), names, products and identities stay, and discovery may not crawl it into
 * bauteamcheck-web again.
 */
public class DrolshagensPilotTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String BAU = "bauteamcheck-web";
    private static final String FALLBACK = "sonstige";
    private static final Set<String> KNOWN = Set.of(BAU, FALLBACK, "edelsenior-web");
    private static final String ORG = "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"Drolshagens Nuss-Spezialitäten\","
            + "\"url\":\"https://drolshagens.de/\",\"telephone\":\"+49 551 123456\"}";
    private static final String PRODUCT = "{\"@context\":\"https://schema.org\",\"@type\":\"Product\",\"name\":\"Drolshagens Nuss-Spezialitäten\","
            + "\"offers\":{\"@type\":\"Offer\",\"price\":\"9.00\",\"priceCurrency\":\"EUR\"}}";
    /** Two more products of the shop, each with its offer (the production case had ten). */
    private static final String PRODUCTS = "{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"Product\",\"name\":\"Gebrannte Mandeln\","
            + "\"offers\":{\"@type\":\"Offer\",\"price\":\"4.50\",\"priceCurrency\":\"EUR\"}},{\"@type\":\"Product\",\"name\":\"Präsentkorb\","
            + "\"offers\":{\"@type\":\"Offer\",\"price\":\"29.00\",\"priceCurrency\":\"EUR\"}}]}";
    private static final String TEXT = "Drolshagens Nuss-Spezialitäten: Hersteller von gebrannten Mandeln, Nüssen und Popcorn."
            + " Herstellung in eigener Manufaktur seit 1950. Unser Sortiment und unsere Leistungen: Nüsse, Mandeln, Geschenke.";

    private EmbeddedInstance solr;
    private File data;
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private KgRuntime kg;
    private CollectionDecisions decisions;
    private Path decisionDir;

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1",
                new String[] {"collection1", "webgraph"});
        this.data = this.tmp.newFolder("data");
        final Map<String, String> settings = KgTestSupport.enabled(KgConfig.COLLECTIONS, BAU + "," + FALLBACK, KgConfig.JSONLD_ENABLED, "true",
                KgConfig.VOCAB_PREFIX + BAU, "construction");
        this.kg = KgTestSupport.startCurrent(new KgRuntime.Env(this.data, settings::get, this.clock::get, new KgTestSupport.Probe(),
                KgStore.SQLITE, false, () -> this.solr.getDefaultServer(), Gates.IDLE));
        assertNotNull(this.kg);
        this.decisionDir = this.tmp.newFolder("collections").toPath();
        this.decisions = new CollectionDecisions(new DecisionStore(this.decisionDir, this.tmp.newFolder("settings").toPath().resolve("marker")),
                FALLBACK, false, this.clock::get);
        page("AAAAAAdrols1", "https://drolshagens.de/", BAU, ORG, TEXT);
        page("BBBBBBdrols2", "https://drolshagens.de/products/drolshagens-nuss-spezialitaten", BAU, PRODUCT, TEXT);
        page("CCCCCCdrols3", "https://www.drolshagens.de/ueber-uns", BAU, null, TEXT);
        page("DDDDDDbaufi1", "https://bau-firma.de/", BAU, null, "Bau-Firma GmbH: Hersteller von Bauelementen, Baustoffhandel und Dachdecker.");
        client().commit();
        settle();
    }

    @After
    public void close() {
        KgRuntime.stop();
        JsonLdCapture.clear();
        if (this.solr != null) this.solr.close();
    }

    private SolrClient client() {
        return this.solr.getDefaultServer();
    }

    private void page(final String id, final String url, final String collection, final String ldJson, final String text) throws Exception {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", url);
        d.setField("host_s", java.net.URI.create(url).getHost());
        d.setField("host_id_s", id.substring(6));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", List.of(collection));
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        d.setField("title", List.of("Drolshagens " + id));
        d.setField("text_t", text);
        if (ldJson != null) d.setField("ld_json_txt", List.of(ldJson));
        client().add(d);
    }

    private void drive(final BooleanSupplier done) {
        for (int i = 0; i < 3000; i++) {
            KgTestSupport.step(this.kg);
            if (done.getAsBoolean()) return;
            this.clock.addAndGet(3000L);
        }
        fail("not reached: " + this.kg.status());
    }

    private void settle() {
        drive(() -> {
            final JSONObject sync = this.kg.status().optJSONObject("sync");
            final JSONObject lag = sync == null ? null : sync.optJSONObject("lag");
            return sync != null && sync.optBoolean("initialized") && lag != null && lag.optLong("pending", 1L) == 0L
                    && !lag.optBoolean("reconcile_pending", true) && count("SELECT count(*) FROM kg_work") == 0L;
        });
    }

    private long count(final String sql) {
        try {
            return KgTestSupport.count(this.kg, sql);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Live tier 1 and 2 evidence of a page whose statement is a construction category or industry. */
    private long constructionFacts(final String doc) {
        return count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE d.doc_id = '" + doc + "' AND e.tier IN (1, 2)"
                + " AND v.name IN ('category', 'topic', 'industry', 'industry_category', 'audience_segment') AND s.obj_val LIKE 'construction%'");
    }

    /** Live evidence of an offer of the domain's pages (the service "Hersteller" the construction vocabulary read). */
    private long offeredManufacturing() {
        return count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE d.doc_id LIKE '%drols%' AND v.name = 'offers'");
    }

    private List<String> collections(final String id) throws Exception {
        final SolrDocument d = client().getById(id);
        assertNotNull(id, d);
        final List<String> out = new ArrayList<>();
        for (final Object o : d.getFieldValues("collection_sxt")) out.add(o.toString());
        return new ArrayList<>(new TreeSet<>(out));
    }

    private CollectionReassign.Index index() {
        return new CollectionReassign.Index() {
            @Override
            public SolrDocumentList select(final String core, final ModifiableSolrParams params) throws IOException {
                try {
                    return DrolshagensPilotTest.this.solr.getServer(core).query(params).getResults();
                } catch (final Exception e) {
                    throw new IOException(e);
                }
            }

            @Override
            public Map<String, Long> facet(final String core, final ModifiableSolrParams params, final String field) throws IOException {
                try {
                    final Map<String, Long> out = new TreeMap<>();
                    for (final org.apache.solr.client.solrj.response.FacetField.Count c : DrolshagensPilotTest.this.solr.getServer(core)
                            .query(params).getFacetField(field).getValues()) {
                        out.put(c.getName(), c.getCount());
                    }
                    return out;
                } catch (final Exception e) {
                    throw new IOException(e);
                }
            }

            @Override
            public void setCollections(final String core, final String id, final long version, final List<String> collections) throws IOException {
                CollectionReassign.atomicSet(DrolshagensPilotTest.this.solr.getServer(core), id, version, collections);
            }

            @Override
            public void commit(final String core) throws IOException {
                try {
                    DrolshagensPilotTest.this.solr.getServer(core).commit();
                } catch (final Exception e) {
                    throw new IOException(e);
                }
            }

            @Override
            public boolean has(final String core) {
                return "collection1".equals(core);
            }
        };
    }

    private CollectionDecisionsApi decide() {
        return new CollectionDecisionsApi(this.decisions, new DecisionApply(index(), FALLBACK, Set.of(BAU, FALLBACK)::contains), KNOWN::contains);
    }

    /** The automation with the graph's own signals, as ScoutroActions wires it (catalog: the known collections). */
    private AutoCorrectApi autocorrect(final Map<String, String> settings) {
        final List<String> ids = new ArrayList<>(new TreeSet<>(KNOWN));
        final AutoCorrectApi.Graph graph = new AutoCorrectApi.Graph() {
            @Override
            public List<AutoCorrect.Signal> structured(final String domain, final String current) {
                return AutoCorrectApi.structuredSignals(domain, current, DrolshagensPilotTest.this.kg.structuredIndustries(domain),
                        DrolshagensPilotTest.this.kg.collectionNace(ids));
            }

            @Override
            public List<AutoCorrect.Signal> textFit(final String domain, final String current) {
                return AutoCorrectApi.textFitSignals(domain, current, DrolshagensPilotTest.this.kg.textFit(domain, ids));
            }

            @Override
            public List<AutoCorrect.Signal> website(final String domain, final String current) {
                final JSONObject fit = DrolshagensPilotTest.this.kg.textFit(domain, ids);
                final List<AutoCorrect.Signal> out = new ArrayList<>(AutoCorrectApi.textFitSignals(domain, current, fit));
                out.addAll(AutoCorrectApi.plausibilitySignals(domain, current, DrolshagensPilotTest.this.kg.offerProfile(domain, ids), fit));
                return out;
            }
        };
        return new AutoCorrectApi(new AutoCorrect(this.decisionDir.resolve("autocorrect.json").toFile(), this.clock::get), this.decisions,
                decide(), settings::get, KNOWN::contains, graph);
    }

    private static JSONObject body(final Object... kv) throws Exception {
        final JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        return o;
    }

    private static final String OSM_FOR = "{\"family\":\"origin\",\"verdict\":\"for\",\"collection\":\"" + BAU + "\","
            + "\"origin\":\"osm:niedersachsen\",\"kind\":\"osm_rule\",\"evidence\":\"OSM tags[2] of bauteamcheck matched: office=company\"}";
    private static final String OSM_AGAINST = "{\"family\":\"origin\",\"verdict\":\"against\",\"collection\":\"" + BAU + "\","
            + "\"origin\":\"osm:node/42\",\"kind\":\"osm_tag\",\"evidence\":\"shop=confectionery\",\"revision\":\"t1\"}";

    @Test
    public void thePilotMovesTheDomainOnceAndTheGraphForgetsTheConstructionReading() throws Exception {
        // the starting point of the production case: construction facts from the word "Hersteller"
        // (the home page and the products page: a topic construction.handel/hersteller; since 0.11.5 the role word is never an
        // offered service, so the products page carries no category and no derived industry from it)
        assertTrue("a construction topic on the home page", constructionFacts("AAAAAAdrols1") > 0);
        assertTrue("a construction topic on the products page", constructionFacts("BBBBBBdrols2") >= 1);
        assertEquals("the role word \"Hersteller\" is no offered service (0.11.5)", 0L, offeredManufacturing());
        final long bauFacts = constructionFacts("DDDDDDbaufi1");
        assertTrue(bauFacts > 0);
        final long organisations = count("SELECT count(*) FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v"
                + " ON v.term_id = s.pred JOIN kg_entity n ON n.ent_rowid = s.subj WHERE n.status = 1 AND v.name = 'name' AND s.obj_val LIKE 'Drolshagens%'");
        assertTrue(organisations > 0);
        final long modelCalls = count("SELECT count(*) FROM kg_llm_work");

        // the shop's other products (the production case had ten): two more on the about page, each with its offer
        page("CCCCCCdrols3", "https://www.drolshagens.de/ueber-uns", BAU, PRODUCTS, TEXT);
        client().commit();
        settle();

        // 1. the correction automation in simulation: it reads the graph, records a case, writes nothing
        final Map<String, String> settings = new HashMap<>();
        settings.put(AutoCorrectApi.MODE_KEY, "simulate");
        AutoCorrectApi ac = autocorrect(settings);
        assertEquals("simulate", ac.settings().getString("mode"));
        JSONObject r = ac.evaluate(body("domain", "drolshagens.de", "collection", BAU), "admin");
        assertFalse("the role word is no fit to the construction vocabulary (text fit 2)", hasSignal(r, "text_fit", "for", BAU));
        assertTrue("three products of the shop, no construction offer, no construction word: " + r, hasSignal(r, "plausibility", "against", BAU));
        assertEquals("the website alone is a case for the administrator, never a move", "review", r.getJSONObject("outcome").getString("result"));
        assertEquals("one_origin_only", r.getJSONObject("outcome").getString("reason"));
        assertEquals(FALLBACK, r.getJSONObject("outcome").getString("suggested"));
        assertEquals("simulated", r.getJSONObject("case").getString("status"));
        assertEquals(List.of(BAU), collections("AAAAAAdrols1"));
        assertTrue(this.decisions.activeOf("drolshagens.de").isEmpty());
        // the production case: the OSM rule speaks for the collection; the contradiction is visible, never a move
        r = ac.evaluate(body("domain", "drolshagens.de", "collection", BAU, "signals", new JSONArray().put(new JSONObject(OSM_FOR))), "admin");
        assertEquals("review", r.getJSONObject("outcome").getString("result"));
        assertEquals("contradicting_signals", r.getJSONObject("outcome").getString("reason"));
        assertEquals(FALLBACK, r.getJSONObject("outcome").getString("suggested"));
        assertEquals(FALLBACK, r.getJSONObject("case").getString("suggested"));
        assertEquals("simulated", r.getJSONObject("case").getString("status"));
        // with an OSM tag against it: two origins, but neither structured nor a text fit: still only a case
        r = ac.evaluate(body("domain", "drolshagens.de", "collection", BAU, "signals", new JSONArray().put(new JSONObject(OSM_AGAINST))), "admin");
        assertEquals("review", r.getJSONObject("outcome").getString("result"));
        assertEquals("no_structured_or_text_signal", r.getJSONObject("outcome").getString("reason"));
        assertEquals("simulated", r.getJSONObject("case").getString("status"));
        assertEquals(List.of(BAU), collections("AAAAAAdrols1"));
        assertTrue(this.decisions.activeOf("drolshagens.de").isEmpty());
        assertEquals("no model call for any of it", modelCalls, count("SELECT count(*) FROM kg_llm_work"));

        // 2. the administrator's exact decision: preview, then apply
        final JSONObject req = body("domain", "drolshagens.de", "collections", new JSONArray(List.of(FALLBACK)), "reason", "fachlich_falsch",
                "note", "Nuss-Spezialitaeten, kein Bauunternehmen; OSM-Profil bauteamcheck");
        final JSONObject preview = decide().decide(req, "admin");
        assertFalse(preview.getBoolean("applied"));
        assertEquals(3, preview.getJSONObject("preview").getInt("documents"));
        req.put("confirm", preview.getString("token"));
        final JSONObject applied = decide().decide(req, "admin");
        assertTrue(applied.getBoolean("applied"));
        for (final String id : new String[] {"AAAAAAdrols1", "BBBBBBdrols2", "CCCCCCdrols3"}) assertEquals(List.of(FALLBACK), collections(id));
        assertEquals(List.of(BAU), collections("DDDDDDbaufi1"));

        // 3. the graph reads the pages again without the construction vocabulary: rules only, no model
        settle();
        assertEquals("construction facts of the home page withdrawn", 0L, constructionFacts("AAAAAAdrols1"));
        assertEquals("construction facts of the products page withdrawn", 0L, constructionFacts("BBBBBBdrols2"));
        assertEquals("still no offered service \"Hersteller\"", 0L, offeredManufacturing());
        assertEquals("the construction company keeps its facts", bauFacts, constructionFacts("DDDDDDbaufi1"));
        assertEquals("the product's price stays", 1L, count("SELECT count(*) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
                + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred WHERE d.doc_id = 'BBBBBBdrols2'"
                + " AND v.name = 'price'"));
        assertEquals("the names stay", organisations, count("SELECT count(*) FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v"
                + " ON v.term_id = s.pred JOIN kg_entity n ON n.ent_rowid = s.subj WHERE n.status = 1 AND v.name = 'name' AND s.obj_val LIKE 'Drolshagens%'"));
        assertEquals("no model call", modelCalls, count("SELECT count(*) FROM kg_llm_work"));

        // 4. discovery: no recrawl into the wrong collection, the decided one is allowed
        try {
            this.decisions.checkCrawl("drolshagens.de", BAU, true);
            fail("discovery crawled the domain into " + BAU + " again");
        } catch (final CollectionDecisions.Refusal e) {
            assertEquals("collection_decided", e.code);
        }
        this.decisions.checkCrawl("www.drolshagens.de", FALLBACK, true);

        // 5. a recrawl of a page keeps the decision: the indexer writes the decided collection, the graph stays as is
        page("AAAAAAdrols1", "https://drolshagens.de/", FALLBACK, ORG, TEXT);
        client().commit();
        settle();
        assertEquals(0L, constructionFacts("AAAAAAdrols1"));

        // 6. the manual decision wins over every later automatic evaluation
        settings.put(AutoCorrectApi.MODE_KEY, "apply");
        ac = autocorrect(settings);
        r = ac.evaluate(body("domain", "drolshagens.de", "collection", FALLBACK, "signals", new JSONArray().put(new JSONObject(OSM_AGAINST
                .replace(BAU, FALLBACK).replace("t1", "t2")))), "admin");
        assertEquals("manual_decision", r.getJSONObject("outcome").optString("reason"));
        assertEquals(CollectionDecision.Basis.MANUAL, this.decisions.activeOf("drolshagens.de").get(0).basis);
        assertEquals(List.of(FALLBACK), collections("BBBBBBdrols2"));
    }

    @Test
    public void oneProductIsNoFinding() throws Exception {
        // the fixture as it was: one product on the products page, the role word "Hersteller" on every page
        final Map<String, String> settings = new HashMap<>();
        settings.put(AutoCorrectApi.MODE_KEY, "simulate");
        final JSONObject r = autocorrect(settings).evaluate(body("domain", "drolshagens.de", "collection", BAU), "admin");
        assertFalse("the role word is no fit to the construction vocabulary (text fit 2)", hasSignal(r, "text_fit", "for", BAU));
        assertFalse("one product is no positive finding: " + r, hasSignal(r, "plausibility", "against", BAU));
        assertEquals("none", r.getJSONObject("outcome").getString("result"));
        assertEquals("no_signal_against", r.getJSONObject("outcome").getString("reason"));
        final List<String> ids = new ArrayList<>(new TreeSet<>(KNOWN));
        final JSONObject fit = this.kg.textFit("drolshagens.de", ids);
        final JSONObject offers = this.kg.offerProfile("drolshagens.de", ids);
        assertEquals(offers.toString(), 1, offers.getJSONArray("services").length());
        assertEquals(fit.toString(), 0, fit.getJSONObject("collections").getJSONObject(BAU).getInt("offered"));
        assertEquals(fit.toString(), 0, fit.getJSONObject("collections").getJSONObject(BAU).getInt("topics"));
        final List<AutoCorrect.Signal> signals = new ArrayList<>(AutoCorrectApi.textFitSignals("drolshagens.de", BAU, fit));
        signals.addAll(AutoCorrectApi.plausibilitySignals("drolshagens.de", BAU, offers, fit));
        assertEquals(AutoCorrectApi.UNCERTAIN, AutoCorrectApi.assess(BAU, signals, fit));
        // the construction company with its services page: its offers are of the vocabulary, a match (its home page alone only
        // names construction topics, which keeps it from any plausibility signal but is no positive fit)
        page("EEEEEEbaufi2", "https://bau-firma.de/leistungen", BAU, null, "Unsere Leistungen: Dachdeckerarbeiten, Trockenbau und Innenausbau"
                + " aus einer Hand. Fordern Sie ein Angebot an.");
        client().commit();
        settle();
        final JSONObject bauFit = this.kg.textFit("bau-firma.de", ids);
        assertTrue(bauFit.toString(), bauFit.getJSONObject("collections").getJSONObject(BAU).getInt("offered") > 0);
        assertEquals(AutoCorrectApi.MATCH, AutoCorrectApi.assess(BAU, AutoCorrectApi.textFitSignals("bau-firma.de", BAU, bauFit), bauFit));
    }

    private static boolean hasSignal(final JSONObject evaluation, final String family, final String verdict, final String collection) throws Exception {
        final JSONArray signals = evaluation.getJSONObject("outcome").getJSONArray("signals");
        for (int i = 0; i < signals.length(); i++) {
            final JSONObject s = signals.getJSONObject(i);
            if (family.equals(s.getString("family")) && verdict.equals(s.getString("verdict")) && collection.equals(s.getString("collection"))) return true;
        }
        return false;
    }

    @Test
    public void theAutomationNeverAppliesWithoutAnAcceptance() throws Exception {
        final Map<String, String> settings = new HashMap<>();
        settings.put(AutoCorrectApi.MODE_KEY, "apply");
        final AutoCorrectApi ac = autocorrect(settings);
        final JSONObject r = ac.evaluate(body("domain", "drolshagens.de", "collection", BAU, "signals", new JSONArray().put(new JSONObject(OSM_AGAINST))
                .put(new JSONObject("{\"family\":\"structured\",\"verdict\":\"against\",\"collection\":\"" + BAU + "\",\"kind\":\"jsonld_type\","
                        + "\"evidence\":\"JSON-LD @type Store, nut specialities\",\"revision\":\"r1\"}"))), "admin");
        assertNotEquals("applied", r.getString("action"));
        assertEquals(List.of(BAU), collections("AAAAAAdrols1"));
        assertTrue(this.decisions.activeOf("drolshagens.de").isEmpty());
        try {
            ac.accept(new JSONObject(), "admin");
            fail("an acceptance without reviewed cases");
        } catch (final ApiException e) {
            assertEquals("acceptance_not_met", e.code());
        }
    }
}
