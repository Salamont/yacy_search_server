package net.yacy.scoutro.knowledge.read;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * WP-F on the stored graph: prices and other amounts are archived observations (schema 8, also for a graph upgraded
 * from schema 7), a price valid from a later date is not_yet_valid and never current, and a donation is no price of the
 * price lists.
 */
public class PriceSemanticsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 2027-01-15. */
    private final long now = java.time.LocalDate.of(2027, 1, 15).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    private KgPaths paths;
    private KgStore store;
    private KgConfig cfg;
    private Publisher publisher;
    private KgReader reader;
    private long version = 1000L;

    @Before
    public void open() throws Exception {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled("scoutro.kg.collections", "alpha-care", "scoutro.kg.vocab.alpha-care", "care"));
        this.paths = new KgPaths(this.tmp.getRoot());
        start();
    }

    private void start() throws Exception {
        final StorageGuard guard = new StorageGuard(this.cfg, this.paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(this.paths, this.cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(this.cfg, terms);
        this.reader = new KgReader(this.store, this.cfg, () -> this.now);
    }

    @After
    public void close() {
        if (this.store != null) this.store.close();
    }

    private void publish(final String id, final String url, final String jsonld) throws Exception {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = id.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of("alpha-care");
        final Extraction ex = new Extraction(300);
        new JsonLdExtractor(200).extract(List.of(jsonld), d.url, d.host, "de", ex,
                new ExtractContext(KgVocabularies.get(), Set.of("care"), false, List.of("alpha-care")));
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = this.now - 86_400_000L;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now));
    }

    private long count(final String sql) throws Exception {
        return this.store.read(c -> KgStore.queryLong(c, sql));
    }

    private void corpus() throws Exception {
        publish("ALPHA1host01", "https://www.alpha-pflege.example/preise", "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\","
                + "\"name\":\"Alpha Pflege GmbH\",\"url\":\"https://www.alpha-pflege.example/\",\"makesOffer\":["
                + "{\"@type\":\"Offer\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Tagespflege\"},\"price\":\"49\",\"priceCurrency\":\"EUR\"},"
                + "{\"@type\":\"Offer\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Kurzzeitpflege\"},\"price\":\"95\",\"priceCurrency\":\"EUR\","
                + "\"validFrom\":\"2027-07-01\"},"
                + "{\"@type\":\"Offer\",\"name\":\"Spende\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Förderkreis\"},\"price\":\"20\","
                + "\"priceCurrency\":\"EUR\"}]}");
    }

    private JSONArray items(final String status) throws Exception {
        return new ServiceGroups(this.reader).prices(null, null, status, 0, 50, Viewer.ALL).optJSONArray("items");
    }

    @Test
    public void aPriceValidFromALaterDateIsNotYetValidAndADonationIsNoPrice() throws Exception {
        corpus();
        final JSONArray current = items("current");
        assertEquals(current.toString(), 1, current.length());
        assertEquals("Tagespflege", current.optJSONObject(0).optJSONObject("service").optString("name"));
        final JSONArray uncertain = items("uncertain");
        boolean announced = false;
        for (int i = 0; i < uncertain.length(); i++) {
            final JSONObject p = uncertain.optJSONObject(i).optJSONObject("price");
            if ("not_yet_valid".equals(p.optString("status"))) {
                announced = true;
                assertEquals("2027-07-01", p.optString("valid_from"));
            }
        }
        assertTrue(uncertain.toString(), announced);
        final JSONArray all = items("all");
        for (int i = 0; i < all.length(); i++) {
            assertTrue("the donation is no price: " + all, !"Förderkreis".equals(all.optJSONObject(i).optJSONObject("service").optString("name")));
        }
        assertEquals(1, count("SELECT count(*) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'monetary_amount'"
                + " AND s.obj_val LIKE '%\"role\":\"donation\"%'"));
    }

    @Test
    public void theBusinessViewShowsADonationAsAnAmountWithItsRoleAndNeverAsAPrice() throws Exception {
        corpus();
        final String org = this.store.read(c -> {
            try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery("SELECT e.public_id FROM kg_entity e"
                    + " JOIN kg_statement s ON s.subj = e.ent_rowid JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'name'"
                    + " AND s.obj_val = 'Alpha Pflege GmbH'")) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
        final JSONObject b = new BusinessView(this.reader).entity(org, Viewer.ALL, false);
        final JSONArray amounts = b.optJSONArray("amounts");
        assertEquals(b.toString(), 1, amounts.length());
        final JSONObject a = amounts.optJSONObject(0);
        assertEquals("donation", a.optString("role"));
        assertEquals("Förderkreis", a.optString("service_name"));
        assertEquals("20.00", a.optJSONObject("value").optString("amount"));
        assertTrue(a.optInt("sources") >= 1);
        final JSONArray prices = b.optJSONArray("prices");
        for (int i = 0; i < prices.length(); i++) {
            assertTrue("no price of the donation: " + prices, !"Förderkreis".equals(prices.optJSONObject(i).optString("service_name")));
        }
        boolean counted = false;
        for (int i = 0; i < b.optJSONArray("services").length(); i++) {
            final JSONObject s = b.optJSONArray("services").optJSONObject(i);
            if ("Förderkreis".equals(s.optString("name"))) {
                counted = true;
                assertEquals(0, s.optInt("prices"));
                assertEquals(1, s.optInt("amounts"));
            }
        }
        assertTrue(b.toString(), counted);
    }

    @Test
    public void pricesAreArchivedAlsoAfterTheUpgradeFromSchemaSeven() throws Exception {
        corpus();
        assertEquals(KgSchema.CURRENT_VERSION, this.store.schemaVersion());
        final long prices = count("SELECT count(*) FROM kg_observation WHERE predicate IN ('price', 'monetary_amount')");
        assertEquals("two prices and one donation", 3, prices);
        // a graph of schema 7: no price observations yet, the old triggers
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            try (java.sql.Statement s = tx.createStatement()) {
                s.executeUpdate("DELETE FROM kg_observation WHERE predicate IN ('price', 'monetary_amount')");
                s.executeUpdate("UPDATE kg_meta SET value = '7' WHERE key = 'schema_version'");
            }
            return null;
        });
        this.store.close();
        this.store = null;
        start();
        assertEquals(KgSchema.CURRENT_VERSION, this.store.schemaVersion());
        assertEquals("the upgrade archives the prices in the graph once", prices,
                count("SELECT count(*) FROM kg_observation WHERE predicate IN ('price', 'monetary_amount')"));
        // and the new triggers archive a changed price before it goes
        publish("ALPHA1host01", "https://www.alpha-pflege.example/preise", "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\","
                + "\"name\":\"Alpha Pflege GmbH\",\"url\":\"https://www.alpha-pflege.example/\",\"makesOffer\":["
                + "{\"@type\":\"Offer\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Tagespflege\"},\"price\":\"52\",\"priceCurrency\":\"EUR\"}]}");
        assertTrue(count("SELECT count(*) FROM kg_observation WHERE predicate = 'price' AND value LIKE '%\"amount\":\"49.00\"%'") >= 1);
        assertTrue(count("SELECT count(*) FROM kg_observation WHERE predicate = 'price' AND value LIKE '%\"amount\":\"52.00\"%'") >= 1);
    }
}
