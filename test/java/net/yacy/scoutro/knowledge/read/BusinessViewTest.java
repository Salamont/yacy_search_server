/*
 *  BusinessViewTest
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

package net.yacy.scoutro.knowledge.read;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.derive.DerivedService;
import net.yacy.scoutro.knowledge.extract.BusinessFacts;
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/** The business view, the network, the comparison and the chat facts of vocabulary 2, per viewer (package 6). */
public class BusinessViewTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long DAY = 86_400_000L;
    /** 2027-01-15. */
    private final long now = java.time.LocalDate.of(2027, 1, 15).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    private KgStore store;
    private KgConfig cfg;
    private Terms terms;
    private Publisher publisher;
    private KgReader reader;
    private long version = 1000L;

    @Before
    public void open() throws Exception {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled(KgConfig.JOBS_COLLECTIONS, "edelsenior-web",
                KgConfig.PRICES_STALE_DAYS + ".edelsenior-web", "180"));
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(this.cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, this.cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        this.terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            this.terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(this.cfg, this.terms);
        this.reader = new KgReader(this.store, this.cfg, () -> this.now);
        corpus();
    }

    @After
    public void close() {
        this.store.close();
    }

    private Publisher.Doc doc(final String id, final String url, final String collection) {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = id.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of(collection);
        return d;
    }

    private void publish(final Publisher.Doc d, final String jsonld, final String text, final ExtractContext ctx, final long loadedAt)
            throws Exception {
        final Extraction ex = new Extraction(300);
        if (jsonld != null) {
            new JsonLdExtractor(200).extract(List.of(jsonld), d.url, d.host, "de", ex, ctx);
        }
        if (text != null) {
            new RuleExtractor(200, 65536).extract(text, d.url, d.host, "de", ex, ctx, List.of(), List.of());
        } else {
            BusinessFacts.industriesFromServices(ex, ctx, 2, 200);
        }
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = loadedAt;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, loadedAt));
    }

    private static ExtractContext ctx(final String vocabulary, final String collection, final boolean jobs) {
        return new ExtractContext(KgVocabularies.get(), Set.of(vocabulary), jobs, List.of(collection));
    }

    private static final String OP = "{\"@type\":\"Organization\",\"name\":\"Lindenhof Pflege gGmbH\",\"url\":\"https://www.lindenhof.de/\"}";

    private void corpus() throws Exception {
        final ExtractContext care = ctx("care", "edelsenior-web", true);
        final long fresh = this.now - 2 * DAY;
        publish(doc("CAHOMEhost02", "https://www.lindenhof.de/", "edelsenior-web"), OP, null, care, fresh);
        publish(doc("HBIRKEhost02", "https://www.lindenhof.de/haus-birke", "edelsenior-web"), "{\"@type\":\"NursingHome\",\"name\":\"Haus Birke\","
                + "\"address\":{\"streetAddress\":\"Birkenweg 1\",\"postalCode\":\"10115\",\"addressLocality\":\"Berlin\"},\"parentOrganization\":" + OP
                + "}", null, care, fresh);
        publish(doc("HEICHEhost02", "https://www.lindenhof.de/haus-eiche", "edelsenior-web"), "{\"@type\":\"NursingHome\",\"name\":\"Haus Eiche\","
                + "\"address\":{\"streetAddress\":\"Eichenweg 1\",\"postalCode\":\"10115\",\"addressLocality\":\"Berlin\"},\"parentOrganization\":" + OP
                + "}", null, care, fresh);
        // two price pages of the operator: 49 € and 59 € for the same day care, and an old and an expired price
        publish(doc("PREIS1host02", "https://www.lindenhof.de/preise", "edelsenior-web"), OP,
                "Preise. Tagespflege ab 49 € pro Tag. Kurzzeitpflege: 89,90 €/Tag gültig bis 31.12.2026. Stand: 01/2027", care, fresh);
        publish(doc("PREIS2host02", "https://www.lindenhof.de/kosten", "edelsenior-web"), OP, "Kosten. Tagespflege ab 59 € pro Tag.", care, fresh);
        publish(doc("PREIS3host02", "https://www.lindenhof.de/preise-alt", "edelsenior-web"), OP,
                "Preise. Verhinderungspflege: 25 € pro Stunde. Stand: 01/2025", care, fresh);
        // jobs: one open, one ended within 90 days, one ended long ago
        publish(doc("JOBSA1host02", "https://www.lindenhof.de/karriere", "edelsenior-web"), OP,
                "Karriere. Wir suchen: Pflegefachkraft (m/w/d) in Vollzeit. Vergütung: 3.400 – 3.900 € brutto monatlich. Bewerbungsfrist: 31.03.2027. "
                        + "Pflegehelfer (m/w/d) in Teilzeit. Bewerbungsfrist: 31.12.2026. Hauswirtschaftskraft (m/w/d) in Minijob."
                        + " Bewerbungsfrist: 30.06.2026.", care, fresh);
        // a software company of another collection that names care homes as its customers' field, and as reference
        final ExtractContext sw = ctx("software", "stackfinder-web", false);
        final Publisher.Doc swFor = doc("SWFORWhost01", "https://www.pflegesoft.de/fuer-wen", "stackfinder-web");
        swFor.linkDomains = new java.util.TreeMap<>(java.util.Map.of("lindenhof.de", 1));
        publish(swFor, "{\"@type\":\"Organization\",\"name\":\"PflegeSoft GmbH\",\"url\":\"https://www.pflegesoft.de/\"}",
                "Für wen? Wir unterstützen Pflegeeinrichtungen bundesweit, nur für Geschäftskunden. Unsere Kunden: Muster Klinikum GmbH.", sw, fresh);
        new DerivedService(this.cfg, this.store, () -> this.now).run();
    }

    private Viewer viewer(final String... names) throws Exception {
        return names.length == 0 ? Viewer.ALL : this.reader.viewer(List.of(names));
    }

    private String entity(final String name) throws Exception {
        return this.store.read(c -> KgStore.queryString(c, "SELECT e.public_id FROM kg_entity e JOIN kg_statement s ON s.subj = e.ent_rowid"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'name' AND s.obj_val = '" + name + "' AND e.status = 1 LIMIT 1"));
    }

    private static List<String> strings(final JSONArray a, final String key) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            out.add(a.optJSONObject(i).optString(key));
        }
        return out;
    }

    private static JSONObject job(final BusinessView bv, final String entity, final Viewer v, final String title) throws Exception {
        final JSONObject jobs = bv.entity(entity, v, false).optJSONObject("jobs");
        for (int i = 0; jobs != null && i < jobs.getJSONArray("items").length(); i++) {
            final JSONObject j = jobs.getJSONArray("items").getJSONObject(i);
            if (j.getString("title").startsWith(title)) {
                return j;
            }
        }
        return null;
    }

    /** Several industries: the best-supported one is the main industry; two equally supported ones give their safe common level. */
    @Test
    public void severalIndustriesGiveAMainOneAndTheOthersAsSecondary() throws Exception {
        final ExtractContext bau = ctx("construction", "bauteamcheck-web", false);
        final String org = "{\"@type\":\"Organization\",\"name\":\"Muster Ausbau GmbH\",\"url\":\"https://www.muster-ausbau.de/\"}";
        publish(doc("AUSBAUhost03", "https://www.muster-ausbau.de/leistungen", "bauteamcheck-web"), org,
                "Leistungen. Elektroinstallation und Malerarbeiten aus einer Hand.", bau, this.now - DAY);
        final BusinessView bv = new BusinessView(this.reader);
        final Viewer v = viewer("bauteamcheck-web");
        JSONObject industry = bv.entity(entity("Muster Ausbau GmbH"), v, false).getJSONObject("industry");
        assertEquals("equally supported: their common level", "43", industry.getJSONObject("main").getString("code"));
        assertEquals("common_level", industry.getJSONObject("main").getString("basis"));
        List<String> secondary = strings(industry.getJSONArray("secondary"), "code");
        java.util.Collections.sort(secondary);
        assertEquals(List.of("43.21", "43.34"), secondary);
        // a second page with the electrical work: it is the best-supported industry now
        publish(doc("AUSBA2host03", "https://www.muster-ausbau.de/leistungen/elektro", "bauteamcheck-web"), org,
                "Leistungen. Elektroinstallation für Neubau und Bestand.", bau, this.now - DAY);
        industry = new BusinessView(this.reader).entity(entity("Muster Ausbau GmbH"), v, false).getJSONObject("industry");
        assertEquals("43.21", industry.getJSONObject("main").getString("code"));
        assertEquals("best_supported", industry.getJSONObject("main").getString("basis"));
        assertEquals(List.of("43.34"), strings(industry.getJSONArray("secondary"), "code"));
    }

    /** Disappearance is source loss, never a vacancy end; age without a deadline stays unknown. */
    @Test
    public void aPostingWhosePageDisappearsHasUnknownSearchStatus() throws Exception {
        final long seen = this.now - 10 * DAY;
        publish(doc("JOBSB1host02", "https://www.lindenhof.de/stellen", "edelsenior-web"), OP,
                "Karriere. Wir suchen: Alltagsbegleiter (m/w/d) in Teilzeit.", ctx("care", "edelsenior-web", true), seen);
        final String op = entity("Lindenhof Pflege gGmbH");
        final Viewer v = viewer("edelsenior-web");
        assertEquals("open", job(new BusinessView(this.reader), op, v, "Alltagsbegleiter").getString("status"));
        final long rowid = this.store.read(c -> KgStore.queryLong(c, "SELECT doc_rowid FROM kg_doc WHERE doc_id = 'JOBSB1host02'"));
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> this.publisher.setState(tx, List.of(rowid), Aggregates.STATE_GONE, this.now - DAY));
        final JSONObject ended = job(new BusinessView(this.reader), op, v, "Alltagsbegleiter");
        assertNotNull("source loss does not hide the posting", ended);
        assertEquals("unknown", ended.getString("status"));
        assertTrue(ended.isNull("ended_at"));
        final KgReader later = new KgReader(this.store, this.cfg, () -> this.now + 100 * DAY);
        assertEquals("age without end proof remains unknown", "unknown", job(new BusinessView(later), op, later.viewer(List.of("edelsenior-web")),
                "Alltagsbegleiter").getString("status"));
    }

    @Test
    public void pricesShowTheirDateStalenessExpiryAndConflictsWithoutAveraging() throws Exception {
        final JSONObject view = new BusinessView(this.reader).entity(entity("Lindenhof Pflege gGmbH"), viewer("edelsenior-web"), false);
        final JSONArray prices = view.getJSONArray("prices");
        final List<String> lines = new ArrayList<>();
        for (int i = 0; i < prices.length(); i++) {
            final JSONObject p = prices.getJSONObject(i);
            final JSONObject v = p.getJSONObject("value");
            lines.add(p.getString("service_name") + " " + (v.has("amount") ? v.getString("amount") : v.getString("min")) + " " + p.getString("status"));
            assertTrue("every price has its date", p.has("as_of"));
            assertTrue("and its source", p.getJSONArray("source_docs").length() > 0);
        }
        java.util.Collections.sort(lines);
        assertEquals(List.of("Kurzzeitpflege 89.90 expired", "Tagespflege 49.00 conflicting", "Tagespflege 59.00 conflicting",
                "Verhinderungspflege 25.00 stale"), lines);
        for (int i = 0; i < prices.length(); i++) {
            final JSONObject p = prices.getJSONObject(i);
            if ("conflicting".equals(p.getString("status"))) {
                assertEquals("each conflicting price names the other", 1, p.getJSONArray("conflicts_with").length());
            }
        }
        // the industry of the operator: the services it offers, at their safe NACE levels
        final JSONObject industry = view.getJSONObject("industry");
        assertNotNull(industry.getJSONObject("main").getString("code"));
        assertEquals("NACE Rev. 2.1 / WZ 2025", industry.getJSONObject("main").getString("classification"));
    }

    @Test
    public void jobsAreOpenEndedOrHiddenAfterTheirVisibleDays() throws Exception {
        final BusinessView bv = new BusinessView(this.reader);
        final JSONObject view = bv.entity(entity("Lindenhof Pflege gGmbH"), viewer("edelsenior-web"), false);
        final JSONObject jobs = view.getJSONObject("jobs");
        final List<String> shown = new ArrayList<>();
        for (int i = 0; i < jobs.getJSONArray("items").length(); i++) {
            final JSONObject j = jobs.getJSONArray("items").getJSONObject(i);
            shown.add(j.getString("title") + " " + j.getString("status"));
            if (j.getString("title").startsWith("Pflegefachkraft")) {
                assertEquals("{\"currency\":\"EUR\",\"gross\":true,\"kind\":\"range\",\"max\":\"3900.00\",\"min\":\"3400.00\",\"unit\":\"month\"}",
                        j.getJSONArray("salary").getJSONObject(0).getJSONObject("value").toString());
            }
        }
        java.util.Collections.sort(shown);
        assertEquals("ended 15 days ago: still visible; ended in June 2026: hidden", List.of("Pflegefachkraft (m/w/d) open",
                "Pflegehelfer (m/w/d) deadline_passed"), shown);
        assertEquals(1, jobs.getInt("hidden_ended"));
        final JSONObject all = bv.entity(entity("Lindenhof Pflege gGmbH"), viewer("edelsenior-web"), true);
        assertEquals(3, all.getJSONObject("jobs").getJSONArray("items").length());
        // jobs switched off for the collection: none, even though they are stored
        final KgConfig off = KgTestSupport.config(KgTestSupport.enabled());
        final JSONObject none = new BusinessView(new KgReader(this.store, off, () -> this.now)).entity(entity("Lindenhof Pflege gGmbH"),
                viewer("edelsenior-web"), true);
        assertFalse(none.has("jobs"));
    }

    /** Equal titles on distinct pages are not proof of one posting; each chat keeps its own source. */
    @Test
    public void separatePostingsReachTheChatOfEveryCollectionThatShowsThem() throws Exception {
        final KgConfig both = KgTestSupport.config(KgTestSupport.enabled(KgConfig.JOBS_COLLECTIONS, "edelsenior-web,pflegejobs-web"));
        final String[][] pages = {{"JOBSC1host02", "https://www.lindenhof.de/karriere/wbl", "edelsenior-web"},
            {"JOBSD1host02", "https://www.lindenhof.de/jobs/wbl", "pflegejobs-web"}};
        for (final String[] p : pages) {
            publish(doc(p[0], p[1], p[2]), OP, "Karriere. Wir suchen: Wohnbereichsleitung (m/w/d) in Vollzeit. Bewerbungsfrist: 31.03.2027.",
                    ctx("care", p[2], true), this.now - DAY);
        }
        assertEquals("distinct postings keep independent status", 2L, (long) this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_statement s"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'name' AND s.obj_val LIKE 'Wohnbereichsleitung%'")));
        final KgReader reader = new KgReader(this.store, both, () -> this.now);
        for (final String[] p : pages) {
            final List<String> jobs = new ArrayList<>();
            for (final ChatFacts.Entry e : new ChatFacts(reader).select(List.of(p[0]), List.of("lindenhof", "stelle", "wohnbereichsleitung"),
                    reader.viewer(List.of(p[2])), 20, System.currentTimeMillis() + 5000)) {
                for (final ChatFacts.Fact f : e.facts) {
                    if ("job".equals(f.predicate) && f.value.startsWith("Wohnbereichsleitung")) {
                        jobs.add(e.docId);
                    }
                }
            }
            assertEquals(p[2] + ": " + jobs, List.of(p[0]), jobs);
        }
    }

    /** Observed audiences are customers and references only: a supplier the firm names is an incoming relation, not a customer. */
    @Test
    public void anIncomingSupplierIsNoObservedCustomer() throws Exception {
        publish(doc("SWLIEFhost01", "https://www.pflegesoft.de/partner", "stackfinder-web"), "{\"@type\":\"Organization\",\"name\":"
                + "\"PflegeSoft GmbH\",\"url\":\"https://www.pflegesoft.de/\"}", "Partner. Unsere Lieferanten: Muster Hosting GmbH.",
                ctx("software", "stackfinder-web", false), this.now - DAY);
        final JSONObject view = new BusinessView(this.reader).entity(entity("PflegeSoft GmbH"), viewer("stackfinder-web", "edelsenior-web"), false);
        final String relations = view.getJSONObject("relations").toString();
        assertTrue("the supplier is an incoming relation: " + relations, relations.contains("supplier_of") && relations.contains("Muster Hosting GmbH"));
        final JSONArray observed = view.getJSONObject("audiences").getJSONArray("observed");
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < observed.length(); i++) {
            names.add(observed.getJSONObject(i).getJSONObject("other").getString("name"));
        }
        assertEquals(List.of("Muster Klinikum GmbH"), names);
    }

    @Test
    public void theThreeAudienceLayersNeverMix() throws Exception {
        final String soft = entity("PflegeSoft GmbH");
        final JSONObject both = new BusinessView(this.reader).entity(soft, viewer("stackfinder-web", "edelsenior-web"), false);
        final JSONObject a = both.getJSONObject("audiences");
        assertEquals(List.of("care/stationaere_langzeitpflege"), strings(a.getJSONObject("declared").getJSONArray("target_category"), "value"));
        final JSONObject b2b = a.getJSONObject("declared").getJSONArray("customer_type").getJSONObject(0);
        assertEquals("b2b", b2b.getString("value"));
        assertEquals("customer type codes carry their labels", "Geschäftskunden (B2B)", b2b.getString("label_de"));
        assertEquals("Businesses (B2B)", b2b.getString("label_en"));
        final List<String> observedNames = new ArrayList<>();
        for (int i = 0; i < a.getJSONArray("observed").length(); i++) {
            observedNames.add(a.getJSONArray("observed").getJSONObject(i).getJSONObject("other").getString("name"));
        }
        assertEquals("the named customer is observed; the declared audience and the suggestions are not", List.of("Muster Klinikum GmbH"),
                observedNames);
        final JSONArray suggested = a.getJSONArray("suggested");
        assertTrue(suggested.length() > 0);
        for (int i = 0; i < suggested.length(); i++) {
            assertEquals("suggestion", suggested.getJSONObject(i).getString("label"));
            assertFalse(suggested.getJSONObject(i).getBoolean("fact"));
            assertFalse("a suggestion is never also an observed customer",
                    observedNames.contains(suggested.getJSONObject(i).getJSONObject("other").getString("name")));
        }
        // the named customer is a fact of the customer, observed for PflegeSoft
        final JSONObject customer = new BusinessView(this.reader).entity(soft, viewer("stackfinder-web"), false);
        final JSONObject ca = customer.getJSONObject("audiences");
        assertEquals("one collection: the suggestions combine two and are not shown", 0, ca.getJSONArray("suggested").length());
        assertEquals(1, ca.getJSONArray("observed").length());
        assertEquals("Muster Klinikum GmbH", ca.getJSONArray("observed").getJSONObject(0).getJSONObject("other").getString("name"));
        assertEquals("customer_of", ca.getJSONArray("observed").getJSONObject(0).getString("predicate"));
    }

    @Test
    public void noViewOrRouteLeaksAnotherCollection() throws Exception {
        final Viewer software = viewer("stackfinder-web");
        try {
            new BusinessView(this.reader).entity(entity("Haus Birke"), software, true);
            fail("a care home is not visible to the software collection");
        } catch (final KgReader.NotFound expected) {
            // like a missing one
        }
        final JSONObject n = new BusinessGraph(this.reader).neighborhood(entity("PflegeSoft GmbH"), q(true, true), software);
        for (int i = 0; i < n.getJSONArray("nodes").length(); i++) {
            assertFalse(n.toString(), n.getJSONArray("nodes").getJSONObject(i).optString("label").contains("Lindenhof"));
            assertFalse(n.getJSONArray("nodes").getJSONObject(i).optString("label").contains("Haus"));
        }
        final JSONObject compare = new BusinessGraph(this.reader).compare("care/tagespflege", 50, software);
        assertEquals(0, compare.getJSONArray("rows").length());
        final JSONObject facets = new BusinessGraph(this.reader).facets(software);
        assertTrue(strings(facets.getJSONArray("categories"), "code").isEmpty());
        assertEquals(0, facets.getJSONObject("counts").getJSONObject("derived").length());
        final JSONObject derived = new BusinessGraph(this.reader).derived(null, null, 0, 100, software);
        assertEquals(0, derived.getJSONArray("items").length());
        final JSONObject careDerived = new BusinessGraph(this.reader).derived(null, null, 0, 100, viewer("edelsenior-web"));
        assertEquals("same_operator lies in one collection", List.of("same_operator"), strings(careDerived.getJSONArray("items"), "kind")
                .stream().distinct().collect(java.util.stream.Collectors.toList()));
        // the chat of the software collection gets no fact of the care homes
        for (final ChatFacts.Entry e : new ChatFacts(this.reader).select(List.of(), List.of("lindenhof", "pflege"), software, 20, 5000)) {
            assertFalse(e.name, e.name.contains("Lindenhof"));
        }
    }

    private static BusinessGraph.Query q(final boolean weak, final boolean suggested) {
        final BusinessGraph.Query q = new BusinessGraph.Query();
        q.weak = weak;
        q.suggested = suggested;
        return q;
    }

    @Test
    public void theOriginFilterKeepsItsFactsButAllowsGrantedCustomerSuggestions() throws Exception {
        final String soft = entity("PflegeSoft GmbH");
        final Viewer origin = viewer("stackfinder-web"), granted = viewer("stackfinder-web", "edelsenior-web");
        final JSONObject business = new BusinessView(this.reader).entity(soft, origin, granted, false);
        final JSONObject page = business.getJSONObject("suggestions");
        assertTrue(page.toString(), page.getInt("total") > 0);
        final JSONObject suggestion = page.getJSONArray("items").getJSONObject(0);
        assertFalse(suggestion.getBoolean("fact"));
        assertEquals("edelsenior-web", suggestion.getString("target_collection"));
        assertTrue(suggestion.getJSONObject("other").getJSONArray("other_collections").toString().contains("edelsenior-web"));
        final JSONArray contributions = suggestion.getJSONArray("contributions");
        assertTrue(contributions.getJSONObject(0).getBoolean("evidence_complete"));
        assertTrue(contributions.getJSONObject(0).getJSONArray("evidence").length() >= 2);
        assertEquals("normal facts and sources keep the origin filter", new BusinessView(this.reader).entity(soft, origin, false)
                .getJSONArray("sources").toString(), business.getJSONArray("sources").toString());
        final BusinessGraph graph = new BusinessGraph(this.reader);
        final JSONObject plain = graph.neighborhood(soft, q(false, false), origin, granted);
        assertFalse(plain.toString(), plain.toString().contains("edelsenior-web"));
        final JSONObject network = graph.neighborhood(soft, q(false, true), origin, granted);
        assertTrue(network.toString(), network.toString().contains("edelsenior-web"));
        for (int i = 0; i < network.getJSONArray("edges").length(); i++) {
            final JSONObject edge = network.getJSONArray("edges").getJSONObject(i);
            if (edge.optBoolean("fact")) assertFalse(edge.toString(), edge.toString().contains("Lindenhof"));
        }
        assertEquals(0, new Suggestions(this.reader).page(soft, 0, 100, origin, origin).getInt("total"));
        final Viewer deniedOrigin = viewer("edelsenior-web");
        for (final String route : List.of("business", "suggestions", "network")) {
            try {
                if ("business".equals(route)) new BusinessView(this.reader).entity(entity("Haus Birke"), deniedOrigin, origin, false);
                else if ("suggestions".equals(route)) new Suggestions(this.reader).page(entity("Haus Birke"), 0, 100, deniedOrigin, origin);
                else graph.neighborhood(entity("Haus Birke"), q(false, true), deniedOrigin, origin);
                fail("a view must not expand its grant: " + route);
            } catch (final KgReader.NotFound expected) { /* invisible like missing */ }
        }
    }

    @Test
    public void theNeighbourhoodShowsTypesStatusesAndPagesItsNeighbours() throws Exception {
        final Viewer both = viewer("stackfinder-web", "edelsenior-web");
        final BusinessGraph g = new BusinessGraph(this.reader);
        final JSONObject plain = g.neighborhood(entity("PflegeSoft GmbH"), q(false, false), both);
        assertFalse("weak links are off unless asked for", strings(plain.getJSONArray("edges"), "status").contains("weak"));
        final JSONObject full = g.neighborhood(entity("PflegeSoft GmbH"), q(true, true), both);
        final List<String> statuses = strings(full.getJSONArray("edges"), "status");
        assertTrue(statuses.toString(), statuses.contains("weak"));
        assertTrue(statuses.contains("suggested"));
        for (int i = 0; i < full.getJSONArray("edges").length(); i++) {
            final JSONObject e = full.getJSONArray("edges").getJSONObject(i);
            if ("linked_to".equals(e.getString("type"))) {
                assertFalse("linked_to is never a business relation", e.getBoolean("business"));
                assertFalse(e.getBoolean("fact"));
            }
        }
        final List<String> types = strings(full.getJSONArray("nodes"), "type");
        assertTrue(types.toString(), types.contains("audience") || types.contains("industry"));
        // one neighbour per page: the rest is behind next_offset ("mehr anzeigen")
        final BusinessGraph.Query one = q(true, true);
        one.limit = 1;
        final JSONObject first = g.neighborhood(entity("PflegeSoft GmbH"), one, both);
        assertEquals(2, first.getJSONArray("nodes").length());
        assertTrue(first.getBoolean("truncated"));
        one.offset = first.getInt("next_offset");
        final JSONObject second = g.neighborhood(entity("PflegeSoft GmbH"), one, both);
        assertFalse(first.getJSONArray("nodes").getJSONObject(1).getString("id").equals(second.getJSONArray("nodes").getJSONObject(1).getString("id")));
        // depth 2 reaches the houses through the operator
        final BusinessGraph.Query deep = q(false, false);
        deep.depth = 2;
        final JSONObject d2 = g.neighborhood(entity("Lindenhof Pflege gGmbH"), deep, viewer("edelsenior-web"));
        assertTrue(strings(d2.getJSONArray("nodes"), "label").contains("Haus Birke"));
    }

    @Test
    public void theComparisonListsEveryPriceWithConditionsDatesAndSources() throws Exception {
        final JSONObject compare = new BusinessGraph(this.reader).compare("care/tagespflege", 50, viewer("edelsenior-web"));
        final JSONArray rows = compare.getJSONArray("rows");
        assertEquals(1, rows.length());
        final JSONArray prices = rows.getJSONObject(0).getJSONArray("prices");
        assertEquals("both prices, never an average", 2, prices.length());
        assertEquals("Lindenhof Pflege gGmbH", rows.getJSONObject(0).getJSONArray("providers").getJSONObject(0).getString("name"));
    }

    /** Facets and the comparison count a fact as current only from a current page the viewer sees. */
    @Test
    public void facetsAndComparisonUseTheViewersOwnCurrentPages() throws Exception {
        final String org = "{\"@type\":\"Organization\",\"name\":\"Muster Tagespflege GmbH\",\"url\":\"https://www.muster-tp.de/\"}";
        final String text = "Unsere Leistungen: Tagespflege. Tagespflege ab 39 € pro Tag.";
        // the same page content: long ago in one collection, today in another
        publish(doc("MUSTEAhost06", "https://www.muster-tp.de/leistungen", "pflegeatlas-web"), org, text, ctx("care", "pflegeatlas-web", false),
                this.now - 400 * DAY);
        publish(doc("MUSTEBhost06", "https://www.muster-tp.de/angebot", "pflegeportal-web"), org, text, ctx("care", "pflegeportal-web", false),
                this.now - DAY);
        final BusinessGraph g = new BusinessGraph(this.reader);
        final Viewer old = viewer("pflegeatlas-web");
        final Viewer fresh = viewer("pflegeportal-web");
        assertEquals(List.of(), strings(g.facets(old).getJSONArray("categories"), "code"));
        assertEquals(List.of("care/tagespflege"), strings(g.facets(fresh).getJSONArray("categories"), "code"));
        assertEquals(0, g.compare("care/tagespflege", 50, old).getJSONArray("rows").length());
        assertEquals(1, g.compare("care/tagespflege", 50, fresh).getJSONArray("rows").length());
    }

    @Test
    public void chatFactsWordSuggestionsAsSuggestionsAndPricesWithTheirDate() throws Exception {
        final Viewer both = viewer("stackfinder-web", "edelsenior-web");
        boolean suggestion = false;
        boolean price = false;
        for (final ChatFacts.Entry e : new ChatFacts(this.reader).select(List.of("SWFORWhost01", "PREIS1host02"), List.of("pflegesoft",
                "lindenhof"), both, 30, 5000)) {
            for (final ChatFacts.Fact f : e.facts) {
                if ("suggestion".equals(f.note)) {
                    suggestion = true;
                    assertTrue(f.predicate.startsWith("suggested_"));
                }
                if ("price".equals(f.predicate)) {
                    price = true;
                    assertTrue(f.value, f.value.contains("as of 20"));
                }
                assertFalse("linked_to never reaches the chat", f.predicate.contains("linked"));
            }
        }
        assertTrue(suggestion);
        assertTrue(price);
    }

    /** Benchmark finding: an outdated price is no longer dropped from the chat, it comes last and is marked; an expired one stays out. */
    @Test
    public void chatFactsGiveOutdatedPricesMarkedAndLeaveExpiredOnesOut() throws Exception {
        final List<ChatFacts.Fact> prices = new ArrayList<>();
        for (final ChatFacts.Entry e : new ChatFacts(this.reader).select(List.of(), List.of("preis", "lindenhof", "pflege"),
                viewer("edelsenior-web"), 30, 5000)) {
            for (final ChatFacts.Fact f : e.facts) {
                if ("price".equals(f.predicate)) {
                    prices.add(f);
                }
            }
        }
        final List<String> notes = new ArrayList<>();
        for (final ChatFacts.Fact f : prices) {
            notes.add(f.value.replaceFirst(":.*", "") + " " + f.note);
            assertFalse("the expired price stays out: " + f.value, f.value.contains("89.90"));
            if (f.value.startsWith("Verhinderungspflege")) {
                // benchmark finding: "Stand: 01/2025" reached the model as 2025-01-31 and came back as an exact day
                assertTrue("the date as precise as the page wrote it: " + f.value, f.value.contains("as of 2025-01 (stated on the page)"));
            }
        }
        assertTrue(notes.toString(), notes.contains("Verhinderungspflege stale"));
        assertTrue(notes.toString(), notes.contains("Tagespflege conflicting"));
        assertEquals("outdated prices after the current ones", "Verhinderungspflege stale", notes.get(notes.size() - 1));
    }
}
