/*
 *  ServiceContextTest
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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

/**
 * Services of the same name across providers (package 6.1): every provider's
 * "SAP" stays its own service with its own provider, domain, prices and
 * sources; the list shows each with its provider, the group counts them for
 * reading only, and the network reaches the providers through the incoming
 * {@code offers} facts. Everything per viewer.
 */
public class ServiceContextTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long DAY = 86_400_000L;
    private final long now = java.time.LocalDate.of(2027, 1, 15).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    private KgStore store;
    private Publisher publisher;
    private KgReader reader;
    private long version = 1000L;

    @Before
    public void open() throws Exception {
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(cfg, terms);
        this.reader = new KgReader(this.store, cfg, () -> this.now);
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

    private void publish(final Publisher.Doc d, final String jsonld, final String text, final ExtractContext ctx) throws Exception {
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
        final long loadedAt = this.now - 2 * DAY;
        d.loadedAt = loadedAt;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, loadedAt));
    }

    private static ExtractContext ctx(final String vocabulary, final String collection) {
        return new ExtractContext(KgVocabularies.get(), vocabulary == null ? Set.of() : Set.of(vocabulary), false, List.of(collection));
    }

    private static String org(final String name, final String site, final String extra) {
        return "{\"@type\":\"Organization\",\"name\":\"" + name + "\",\"url\":\"https://www." + site + "/\"," + extra + "}";
    }

    private static String offer(final String service, final String price) {
        return "{\"@type\":\"Offer\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"" + service + "\"}"
                + (price == null ? "" : ",\"price\":\"" + price + "\",\"priceCurrency\":\"EUR\"") + "}";
    }

    /**
     * Three providers of "SAP": CTcon (with a price, a second service, an
     * address in Hamburg and a parent company) and Beta IT (no price) in
     * stackfinder-web; Gamma Systems in another collection.
     */
    private void corpus() throws Exception {
        final ExtractContext sw = ctx("software", "stackfinder-web");
        publish(doc("CTCON1host01", "https://www.ctcon.de/leistungen", "stackfinder-web"), org("CTcon GmbH", "ctcon.de",
                "\"address\":{\"streetAddress\":\"Hafenstraße 1\",\"postalCode\":\"20457\",\"addressLocality\":\"Hamburg\"},"
                        + "\"parentOrganization\":{\"@type\":\"Organization\",\"name\":\"CT Holding AG\",\"url\":\"https://www.ct-holding.de/\"},"
                        + "\"makesOffer\":[" + offer("SAP", "120") + "," + offer("Cloud-Migration", null) + "]"), null, sw);
        publish(doc("BETAIThost02", "https://www.beta-it.de/angebot", "stackfinder-web"), org("Beta IT AG", "beta-it.de",
                "\"address\":{\"streetAddress\":\"Ring 2\",\"postalCode\":\"80331\",\"addressLocality\":\"München\"},"
                        + "\"makesOffer\":" + offer("SAP", null)), null, sw);
        publish(doc("GAMMAShost03", "https://www.gamma-systems.de/sap", "otherportal-web"), org("Gamma Systems GmbH", "gamma-systems.de",
                "\"makesOffer\":" + offer("SAP", "99")), null, ctx("software", "otherportal-web"));
    }

    private Viewer viewer(final String... names) throws Exception {
        return names.length == 0 ? Viewer.ALL : this.reader.viewer(List.of(names));
    }

    private String entity(final String name) throws Exception {
        return this.store.read(c -> KgStore.queryString(c, "SELECT e.public_id FROM kg_entity e JOIN kg_statement s ON s.subj = e.ent_rowid"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'name' AND s.obj_val = '" + name + "' AND e.status = 1 LIMIT 1"));
    }

    /** The service "SAP" of one provider. */
    private String sapOf(final String provider) throws Exception {
        final JSONArray items = sapHits(Viewer.ALL);
        for (int i = 0; i < items.length(); i++) {
            final JSONObject ctx = items.getJSONObject(i).getJSONObject("context");
            if (ctx.getJSONArray("providers").length() > 0 && provider.equals(ctx.getJSONArray("providers").getJSONObject(0).getString("name"))) {
                return items.getJSONObject(i).getString("id");
            }
        }
        fail("no SAP of " + provider + " in " + items);
        return null;
    }

    private JSONArray sapHits(final Viewer v) throws Exception {
        final KgReader.EntityQuery q = new KgReader.EntityQuery();
        q.q = "SAP";
        q.type = "service";
        return this.reader.entities(q, v).getJSONArray("items");
    }

    private static List<String> strings(final JSONArray a, final String key) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            out.add(a.optJSONObject(i).optString(key));
        }
        return out;
    }

    private static List<Object> objects(final JSONArray a) {
        final List<Object> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            out.add(a.opt(i));
        }
        return out;
    }

    private static List<String> values(final JSONArray a) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            out.add(a.optString(i));
        }
        return out;
    }

    // ---------------------------------------------------------------- search

    @Test
    public void twoProvidersOfSapAreTwoServicesEachWithItsProviderAndDomain() throws Exception {
        final JSONArray hits = sapHits(viewer("stackfinder-web"));
        assertEquals("one SAP per provider, never merged: " + hits, 2, hits.length());
        assertNotEquals(hits.getJSONObject(0).getString("id"), hits.getJSONObject(1).getString("id"));
        final Set<String> seen = new TreeSet<>();
        for (int i = 0; i < hits.length(); i++) {
            final JSONObject hit = hits.getJSONObject(i);
            assertEquals("SAP", hit.getString("name"));
            final JSONObject ctx = hit.getJSONObject("context");
            assertEquals(1, ctx.getInt("provider_count"));
            assertEquals("offers", ctx.getString("relation"));
            final JSONObject provider = ctx.getJSONArray("providers").getJSONObject(0);
            seen.add(provider.getString("name") + " · " + values(provider.getJSONArray("hosts")) + " · " + values(ctx.getJSONArray("collections")));
            assertEquals("organization", provider.getString("type"));
            assertEquals(List.of("stackfinder-web"), values(provider.getJSONArray("collections")));
            assertTrue(provider.getString("id").startsWith("kge_"));
        }
        assertEquals(new TreeSet<>(List.of("Beta IT AG · [www.beta-it.de] · [stackfinder-web]", "CTcon GmbH · [www.ctcon.de] · [stackfinder-web]")),
                seen);
        // the provider's place, its quality and the service's own sources
        for (int i = 0; i < hits.length(); i++) {
            final JSONObject provider = hits.getJSONObject(i).getJSONObject("context").getJSONArray("providers").getJSONObject(0);
            assertEquals("CTcon GmbH".equals(provider.getString("name")) ? List.of("Hamburg") : List.of("München"),
                    values(provider.getJSONArray("places")));
            assertEquals("supported", provider.getString("quality"));
            assertEquals(1, hits.getJSONObject(i).getJSONObject("context").getInt("sources"));
        }
    }

    @Test
    public void theCollectionFilterDecidesWhichProvidersOfSapAreSeen() throws Exception {
        assertEquals(3, sapHits(Viewer.ALL).length());
        final JSONArray other = sapHits(viewer("otherportal-web"));
        assertEquals(1, other.length());
        assertEquals("Gamma Systems GmbH", other.getJSONObject(0).getJSONObject("context").getJSONArray("providers").getJSONObject(0)
                .getString("name"));
        assertEquals(0, sapHits(viewer("bauteamcheck-web")).length());
        // a viewer of both collections sees every collection of each service, and nothing of a third one
        for (final Object o : objects(sapHits(viewer("stackfinder-web", "otherportal-web")))) {
            final JSONObject ctx = ((JSONObject) o).getJSONObject("context");
            assertEquals(1, ctx.getJSONArray("collections").length());
        }
    }

    @Test
    public void aServiceWithoutAVisibleProviderSaysSo() throws Exception {
        // a lone service node of a page without a provider, and a viewer that sees the service but not its provider
        publish(doc("LONESVhost04", "https://www.delta.de/sap", "stackfinder-web"), "{\"@type\":\"Service\",\"name\":\"SAP\"}", null,
                ctx("software", "stackfinder-web"));
        int without = 0;
        for (final Object o : objects(sapHits(viewer("stackfinder-web")))) {
            final JSONObject ctx = ((JSONObject) o).getJSONObject("context");
            assertTrue(ctx.has("providers"));
            if (ctx.getInt("provider_count") == 0) {
                without++;
                assertEquals(0, ctx.getJSONArray("providers").length());
            }
        }
        assertEquals("the lone service is listed without a provider", 1, without);
        final JSONObject group = new ServiceGroups(this.reader).groups("SAP", null, 0, 25, viewer("stackfinder-web")).getJSONArray("items")
                .getJSONObject(0);
        assertEquals(without, group.getInt("without_provider"));
        assertEquals(group.getInt("services") - group.getInt("without_provider"), group.getInt("providers"));
    }

    // ----------------------------------------------------------- aggregation

    @Test
    public void theGroupCountsProvidersPricesAndCollectionsOfTheViewerOnly() throws Exception {
        final ServiceGroups g = new ServiceGroups(this.reader);
        final JSONObject mine = g.groups("SAP", null, 0, 25, viewer("stackfinder-web"));
        assertEquals(1, mine.getLong("total"));
        final JSONObject sap = mine.getJSONArray("items").getJSONObject(0);
        assertEquals("sap", sap.getString("key"));
        assertEquals("SAP", sap.getString("name"));
        assertEquals(2, sap.getInt("services"));
        assertEquals(2, sap.getInt("providers"));
        assertEquals("only CTcon publishes a price", 1, sap.getInt("with_price"));
        assertEquals(2, sap.getInt("with_current_source"));
        assertEquals(List.of("stackfinder-web"), strings(sap.getJSONArray("collections"), "name"));
        assertEquals(new TreeSet<>(List.of("Hamburg", "München")), new TreeSet<>(strings(sap.getJSONArray("places"), "name")));
        // the administrator sees all three providers in two collections
        final JSONObject all = g.groups("SAP", null, 0, 25, Viewer.ALL).getJSONArray("items").getJSONObject(0);
        assertEquals(3, all.getInt("providers"));
        assertEquals(2, all.getInt("with_price"));
        assertEquals(new TreeSet<>(List.of("otherportal-web", "stackfinder-web")), new TreeSet<>(strings(all.getJSONArray("collections"), "name")));
        // without a text the groups of every service name, the largest first
        final JSONObject every = g.groups(null, null, 0, 25, viewer("stackfinder-web"));
        assertEquals(List.of("SAP", "Cloud-Migration"), strings(every.getJSONArray("items"), "name"));
        // a collection that has no SAP sees no group
        assertEquals(0, g.groups("SAP", null, 0, 25, viewer("bauteamcheck-web")).getLong("total"));
    }

    @Test
    public void theRowsOfAGroupKeepEachProvidersOwnPricesAndSources() throws Exception {
        final JSONObject rows = new ServiceGroups(this.reader).providers("sap", null, 0, 25, viewer("stackfinder-web"));
        assertEquals(2, rows.getInt("total"));
        assertEquals(2, rows.getJSONObject("group").getInt("providers"));
        for (final Object o : objects(rows.getJSONArray("items"))) {
            final JSONObject row = (JSONObject) o;
            final String provider = row.getJSONArray("providers").getJSONObject(0).getString("name");
            assertEquals(row.toString(), "CTcon GmbH".equals(provider) ? 1 : 0, row.getJSONObject("prices").getInt("current"));
            assertEquals(1, row.getJSONObject("service").getInt("sources"));
            // the service's own view has only its own prices: never the other provider's
            final JSONObject view = new BusinessView(this.reader).entity(row.getJSONObject("service").getString("id"), viewer("stackfinder-web"),
                    false);
            final JSONArray prices = view.optJSONArray("prices");
            assertEquals("CTcon GmbH".equals(provider) ? 1 : 0, prices == null ? 0 : prices.length());
            if (prices != null) {
                assertEquals("120.00", prices.getJSONObject(0).getJSONObject("value").optString("amount"));
            }
        }
        try {
            new ServiceGroups(this.reader).providers("SAP", null, 0, 25, viewer("bauteamcheck-web"));
            fail("no visible SAP");
        } catch (final KgReader.NotFound expected) {
            // like a missing one
        }
    }

    @Test
    public void theComparisonNamesEachProviderWithItsHosts() throws Exception {
        final Set<String> codes = new TreeSet<>();
        for (final Object o : objects(new BusinessGraph(this.reader).facets(viewer("stackfinder-web")).getJSONArray("categories"))) {
            codes.add(((JSONObject) o).getString("code"));
        }
        for (final String code : codes) {
            for (final Object o : objects(new BusinessGraph(this.reader).compare(code, 50, viewer("stackfinder-web")).getJSONArray("rows"))) {
                for (final Object p : objects(((JSONObject) o).getJSONArray("providers"))) {
                    final JSONObject provider = (JSONObject) p;
                    assertTrue(provider.toString(), provider.getJSONArray("hosts").length() > 0);
                    assertFalse(values(provider.getJSONArray("collections")).contains("otherportal-web"));
                }
            }
        }
    }

    // ---------------------------------------------------------------- network

    private static BusinessGraph.Query q(final int depth) {
        final BusinessGraph.Query q = new BusinessGraph.Query();
        q.depth = depth;
        return q;
    }

    private static JSONObject edge(final JSONObject n, final String from, final String type, final String to) throws Exception {
        for (final Object o : objects(n.getJSONArray("edges"))) {
            final JSONObject e = (JSONObject) o;
            if (e.getString("from").equals(from) && e.getString("type").equals(type) && e.getString("to").equals(to)) {
                return e;
            }
        }
        return null;
    }

    private static JSONObject node(final JSONObject n, final String id) throws Exception {
        for (final Object o : objects(n.getJSONArray("nodes"))) {
            if (((JSONObject) o).getString("id").equals(id)) {
                return (JSONObject) o;
            }
        }
        return null;
    }

    @Test
    public void aServiceAtTheCentreShowsItsProviderThroughTheIncomingOffer() throws Exception {
        final String sap = sapOf("CTcon GmbH");
        final String ctcon = entity("CTcon GmbH");
        final Viewer v = viewer("stackfinder-web");
        final JSONObject n = new BusinessGraph(this.reader).neighborhood(sap, q(1), v);
        assertEquals(sap, n.getString("center"));
        final JSONObject offers = edge(n, ctcon, "offers", sap);
        assertTrue("CTcon GmbH → offers → SAP: " + n, offers != null);
        assertTrue(offers.getBoolean("fact"));
        assertEquals("in", offers.getString("direction"));
        // the provider node carries its domain, collections, quality and sources; the centre its own
        final JSONObject p = node(n, ctcon);
        assertEquals("organization", p.getString("type"));
        assertEquals(List.of("www.ctcon.de"), values(p.getJSONArray("hosts")));
        assertEquals(List.of("stackfinder-web"), values(p.getJSONArray("collections")));
        assertEquals("supported", p.getString("quality"));
        assertTrue(p.getInt("sources") >= 1);
        assertEquals("service", node(n, sap).getString("type"));
        // the other provider of a service of the same name is no neighbour: two services, two networks
        assertEquals(null, node(n, entity("Beta IT AG")));
        // from the provider the same fact points outwards
        final JSONObject fromOrg = new BusinessGraph(this.reader).neighborhood(ctcon, q(1), v);
        assertEquals("out", edge(fromOrg, ctcon, "offers", sap).getString("direction"));
    }

    @Test
    public void depthTwoFromAServiceShowsTheProvidersRelationsNotTheirOtherServices() throws Exception {
        final String sap = sapOf("CTcon GmbH");
        final Viewer v = viewer("stackfinder-web");
        final JSONObject d2 = new BusinessGraph(this.reader).neighborhood(sap, q(2), v);
        final List<String> labels = strings(d2.getJSONArray("nodes"), "label");
        assertTrue("the provider's parent company at depth 2: " + labels, labels.contains("CT Holding AG"));
        assertFalse("the provider's other services stay in the provider's own network: " + labels, labels.contains("Cloud-Migration"));
        assertTrue(edge(d2, entity("CTcon GmbH"), "subsidiary_of", entity("CT Holding AG")) != null);
        // from the provider, depth 1 shows both of its services
        final List<String> own = strings(new BusinessGraph(this.reader).neighborhood(entity("CTcon GmbH"), q(1), v).getJSONArray("nodes"), "label");
        assertTrue(own.toString(), own.contains("Cloud-Migration") && own.contains("SAP"));
    }

    @Test
    public void filtersAndCollectionRightsApplyToAServiceCentre() throws Exception {
        final String sap = sapOf("CTcon GmbH");
        final BusinessGraph g = new BusinessGraph(this.reader);
        final BusinessGraph.Query only = q(2);
        only.types = Set.of("subsidiary_of");
        final JSONObject n = g.neighborhood(sap, only, viewer("stackfinder-web"));
        assertEquals("without offers the service has no visible neighbour", 1, n.getJSONArray("nodes").length());
        final BusinessGraph.Query offers = q(1);
        offers.types = Set.of("offers");
        assertEquals(2, g.neighborhood(sap, offers, viewer("stackfinder-web")).getJSONArray("nodes").length());
        try {
            g.neighborhood(sap, q(1), viewer("otherportal-web"));
            fail("CTcon's SAP is not visible in another collection");
        } catch (final KgReader.NotFound expected) {
            // like a missing one
        }
        // derived, weak and suggested rows are never facts, also around a service
        final BusinessGraph.Query all = q(2);
        all.weak = true;
        all.suggested = true;
        for (final Object o : objects(g.neighborhood(sap, all, viewer("stackfinder-web")).getJSONArray("edges"))) {
            final JSONObject e = (JSONObject) o;
            if (!e.getBoolean("fact")) {
                assertTrue(e.toString(), Set.of("weak", "derived", "suggested").contains(e.getString("status")));
            }
        }
    }

    @Test
    public void pricesAreAnOptionalLayerOfTheirOwnService() throws Exception {
        final String sap = sapOf("CTcon GmbH");
        final BusinessGraph g = new BusinessGraph(this.reader);
        final Viewer v = viewer("stackfinder-web");
        assertFalse(strings(g.neighborhood(sap, q(1), v).getJSONArray("nodes"), "type").contains("price"));
        final BusinessGraph.Query withPrices = q(2);
        withPrices.prices = true;
        final JSONObject n = g.neighborhood(sap, withPrices, v);
        int prices = 0;
        for (final Object o : objects(n.getJSONArray("nodes"))) {
            final JSONObject node = (JSONObject) o;
            if ("price".equals(node.getString("type"))) {
                prices++;
                assertEquals("120.00", node.getJSONObject("price").optString("amount"));
                assertTrue(edge(n, sap, "price", node.getString("id")) != null);
            }
        }
        assertEquals("only the centre's own price, never another provider's", 1, prices);
    }
}
