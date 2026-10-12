/*
 *  ServicePricesTest
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * The drilldown of "with a current price" (0.10.2): every published price one by one, with the status of the group
 * counts, also without a service name or a category and for a collection without a vocabulary; and the facets the
 * page's category selects read (filter values and vocabulary profiles, without the derived projection).
 */
public class ServicePricesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long DAY = 86_400_000L;
    /** 2027-01-15. */
    private final long now = java.time.LocalDate.of(2027, 1, 15).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    private KgStore store;
    private KgConfig cfg;
    private Publisher publisher;
    private KgReader reader;
    private long version = 1000L;

    @Before
    public void open() throws Exception {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled("scoutro.kg.collections", "alpha-care,gamma-shop", "scoutro.kg.vocab.alpha-care", "care"));
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(this.cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, this.cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(this.cfg, terms);
        this.reader = new KgReader(this.store, this.cfg, () -> this.now);
        corpus();
    }

    @After
    public void close() {
        this.store.close();
    }

    private void publish(final String id, final String url, final String collection, final String jsonld, final ExtractContext ctx,
            final long loadedAt) throws Exception {
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
        final Extraction ex = new Extraction(300);
        new JsonLdExtractor(200).extract(List.of(jsonld), d.url, d.host, "de", ex, ctx);
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = loadedAt;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, loadedAt));
    }

    private static String offers(final String org, final String url, final String... offers) throws Exception {
        final JSONArray a = new JSONArray();
        for (int i = 0; i + 2 < offers.length; i += 3) {
            final JSONObject o = new JSONObject().put("@type", "Offer").put("itemOffered", new JSONObject().put("@type", "Service")
                    .put("name", offers[i])).put("price", offers[i + 1]).put("priceCurrency", "EUR");
            if (!offers[i + 2].isEmpty()) {
                o.put("priceValidUntil", offers[i + 2]);
            }
            a.put(o);
        }
        return new JSONObject().put("@context", "https://schema.org").put("@type", "Organization").put("name", org).put("url", url)
                .put("makesOffer", a).toString();
    }

    private void corpus() throws Exception {
        final ExtractContext care = new ExtractContext(KgVocabularies.get(), Set.of("care"), false, List.of("alpha-care"));
        final ExtractContext none = new ExtractContext(KgVocabularies.get(), Set.of(), false, List.of("gamma-shop"));
        final long fresh = this.now - 2 * DAY;
        // Alpha: a current day-care price and an expired short-term care price
        publish("ALPHA1host01", "https://www.alpha-pflege.example/preise", "alpha-care",
                offers("Alpha Pflege GmbH", "https://www.alpha-pflege.example/", "Tagespflege", "49", "", "Kurzzeitpflege", "89.90", "2026-12-31"),
                care, fresh);
        // Beta: two pages that publish different day-care prices: a conflict, no current price
        publish("BETA01host02", "https://www.beta-pflege.example/preise", "alpha-care",
                offers("Beta Pflege GmbH", "https://www.beta-pflege.example/", "Tagespflege", "52", ""), care, fresh);
        publish("BETA02host02", "https://www.beta-pflege.example/kosten", "alpha-care",
                offers("Beta Pflege GmbH", "https://www.beta-pflege.example/", "Tagespflege", "58", ""), care, fresh);
        // Gamma, a collection without a vocabulary: a current price and one on a page older than the source age
        publish("GAMMA1host03", "https://www.gamma-shop.example/beratung", "gamma-shop",
                offers("Gamma Shop GmbH", "https://www.gamma-shop.example/", "Beratung", "80", ""), none, fresh);
        publish("GAMMA2host03", "https://www.gamma-shop.example/wartung", "gamma-shop",
                offers("Gamma Shop GmbH", "https://www.gamma-shop.example/", "Wartung", "30", ""), none, this.now - 400 * DAY);
    }

    private Viewer viewer(final String... names) throws Exception {
        return names.length == 0 ? Viewer.ALL : this.reader.viewer(List.of(names));
    }

    private JSONObject prices(final String name, final String status, final Viewer v) throws Exception {
        return new ServiceGroups(this.reader).prices(name, null, status, 0, 50, v);
    }

    private static List<String> rows(final JSONObject page) {
        final List<String> out = new ArrayList<>();
        final JSONArray items = page.optJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            final JSONObject it = items.optJSONObject(i);
            final JSONObject p = it.optJSONObject("price");
            final JSONArray providers = it.optJSONArray("providers");
            out.add(it.optJSONObject("service").optString("name") + "|" + p.optJSONObject("value").optString("amount") + "|"
                    + p.optString("status") + "|" + (providers.length() == 0 ? "-" : providers.optJSONObject(0).optString("name")));
        }
        return out;
    }

    @Test
    public void theGroupCountsOnlyCurrentPricesAndItsDrilldownListsExactlyThose() throws Exception {
        final ServiceGroups groups = new ServiceGroups(this.reader);
        final JSONObject day = groups.providers("Tagespflege", null, 0, 25, Viewer.ALL).optJSONObject("group");
        assertEquals("two providers publish a day-care price, one of them a conflicting pair", 2, day.optLong("with_any_price"));
        assertEquals("only Alpha's price is current: Beta's two pages contradict each other", 1, day.optLong("with_price"));
        final JSONObject current = prices("Tagespflege", "current", Viewer.ALL);
        assertEquals(List.of("Tagespflege|49.00|current|Alpha Pflege GmbH"), rows(current));
        assertEquals(day.optLong("with_price"), current.optLong("services_with_current"));
        final JSONObject conflicting = prices("Tagespflege", "conflicting", Viewer.ALL);
        assertEquals(List.of("Tagespflege|52.00|conflicting|Beta Pflege GmbH", "Tagespflege|58.00|conflicting|Beta Pflege GmbH"),
                rows(conflicting));
        for (int i = 0; i < 2; i++) {
            assertEquals("each conflicting price names the other", 1,
                    conflicting.optJSONArray("items").optJSONObject(i).optJSONObject("price").optJSONArray("conflicts_with").length());
        }
        assertEquals(1L, conflicting.optJSONObject("counts").optLong("current"));
        assertEquals(2L, conflicting.optJSONObject("counts").optLong("conflicting"));
        assertEquals("the group of a drilldown is the group of the counts", day.toString(), current.optJSONObject("group").toString());
    }

    @Test
    public void everyGroupCountEqualsItsCurrentDrilldown() throws Exception {
        final ServiceGroups groups = new ServiceGroups(this.reader);
        for (final Viewer v : List.of(Viewer.ALL, viewer("alpha-care"), viewer("gamma-shop"))) {
            final JSONArray items = groups.groups(null, null, 0, 50, v).optJSONArray("items");
            assertTrue(items.length() > 0);
            for (int i = 0; i < items.length(); i++) {
                final JSONObject g = items.optJSONObject(i);
                final JSONObject d = groups.prices(g.optString("name"), null, "current", 0, 50, v);
                final Set<String> services = new TreeSet<>();
                for (int j = 0; j < d.optJSONArray("items").length(); j++) {
                    services.add(d.optJSONArray("items").optJSONObject(j).optJSONObject("service").optString("id"));
                }
                assertEquals(g.optString("name"), g.optLong("with_price"), services.size());
                // the per-row counts of the group's services use the same status
                final JSONArray rows = groups.providers(g.optString("name"), null, 0, 50, v).optJSONArray("items");
                long current = 0L;
                for (int j = 0; j < rows.length(); j++) {
                    current += rows.optJSONObject(j).optJSONObject("prices").optLong("current") > 0 ? 1 : 0;
                }
                assertEquals(g.optString("name"), g.optLong("with_price"), current);
            }
        }
    }

    @Test
    public void thePriceListNeedsNoNameNoCategoryAndNoVocabulary() throws Exception {
        assertEquals(List.of("Beratung|80.00|current|Gamma Shop GmbH", "Tagespflege|49.00|current|Alpha Pflege GmbH"),
                rows(prices(null, "current", Viewer.ALL)));
        assertEquals("expired (valid until) and stale (page older than the source age) are outdated",
                List.of("Kurzzeitpflege|89.90|expired|Alpha Pflege GmbH", "Wartung|30.00|stale|Gamma Shop GmbH"),
                rows(prices(null, "outdated", Viewer.ALL)));
        final JSONObject all = prices(null, "all", Viewer.ALL);
        assertEquals(6, all.optInt("total"));
        assertFalse(all.optBoolean("truncated"));
        final JSONObject p = all.optJSONArray("items").optJSONObject(0).optJSONObject("price");
        for (final String field : List.of("statement", "value", "status", "as_of", "as_of_basis", "sources", "source_docs")) {
            assertTrue(field, p.has(field));
        }
        assertEquals("EUR", p.optJSONObject("value").optString("currency"));
        // a collection without a vocabulary has no category, and its prices are found all the same
        assertEquals(List.of("Beratung|80.00|current|Gamma Shop GmbH"), rows(prices(null, "current", viewer("gamma-shop"))));
    }

    @Test
    public void aViewerSeesOnlyThePricesOfItsCollections() throws Exception {
        for (final String row : rows(prices(null, "all", viewer("alpha-care")))) {
            assertFalse(row, row.contains("Gamma"));
        }
        for (final String row : rows(prices(null, "all", viewer("gamma-shop")))) {
            assertTrue(row, row.contains("Gamma"));
        }
        assertEquals(0, prices(null, "all", viewer("unknown-collection")).optInt("total"));
        try {
            prices("Tagespflege", "all", viewer("gamma-shop"));
            throw new AssertionError("a name the viewer does not see is not found");
        } catch (final KgReader.NotFound expected) {
            // as services/providers
        }
    }

    @Test
    public void thePriceListPagesInAStableOrder() throws Exception {
        final ServiceGroups groups = new ServiceGroups(this.reader);
        final List<String> all = rows(groups.prices(null, null, "all", 0, 50, Viewer.ALL));
        final List<String> paged = new ArrayList<>();
        for (int offset = 0; offset < all.size(); offset += 2) {
            final JSONObject page = groups.prices(null, null, "all", offset, 2, Viewer.ALL);
            assertEquals(all.size(), page.optInt("total"));
            paged.addAll(rows(page));
        }
        assertEquals(all, paged);
    }

    @Test
    public void facetsForTheSelectsCarryTheProfilesAndNoCounts() throws Exception {
        final BusinessGraph graph = new BusinessGraph(this.reader);
        final JSONObject lean = graph.facets(Viewer.ALL, false);
        assertNull("the selects need no counts and never wait for the derived projection", lean.optJSONObject("counts"));
        final JSONArray profiles = lean.optJSONArray("profiles");
        final List<String> got = new ArrayList<>();
        for (int i = 0; i < profiles.length(); i++) {
            final JSONObject p = profiles.optJSONObject(i);
            got.add(p.optString("collection") + "=" + (p.isNull("vocabulary") ? "-" : p.optString("vocabulary")) + "/" + p.optString("source"));
        }
        assertEquals(List.of("alpha-care=care/setting", "gamma-shop=-/none"), got);
        assertEquals("a viewer sees the profiles of its own collections only", 1, graph.facets(viewer("gamma-shop"), false)
                .optJSONArray("profiles").length());
        final JSONObject full = graph.facets(Viewer.ALL);
        assertTrue(full.optJSONObject("counts").has("derived"));
        assertEquals(lean.optJSONArray("categories").toString(), full.optJSONArray("categories").toString());
    }
}
