package net.yacy.scoutro.knowledge.read;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

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
import net.yacy.scoutro.knowledge.publish.Assessments;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * The quality metrics of WP-B with the corrections and semantics of the later work packages: multiple assignments
 * (WP-A/H), assessments, withdrawn evidence and operator statements (WP-E), amounts by role, prices not yet valid,
 * topics, product page duplicates and identity keys (WP-F). Every metric states its unit; nothing fails.
 */
public class QualityMetricsIntegrationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 2027-01-15. */
    private final long now = java.time.LocalDate.of(2027, 1, 15).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    private KgStore store;
    private KgConfig cfg;
    private Publisher publisher;
    private long version = 1000L;

    @Before
    public void open() throws Exception {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled("scoutro.kg.collections", "alpha-care,beta-care", "scoutro.kg.vocab.alpha-care",
                "care", "scoutro.kg.vocab.beta-care", "care"));
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(this.cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, this.cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(this.cfg, terms);
    }

    @After
    public void close() {
        if (this.store != null) this.store.close();
    }

    private void publish(final String id, final String url, final List<String> collections, final String jsonld) throws Exception {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = id.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = collections;
        final Extraction ex = new Extraction(300);
        new JsonLdExtractor(200).extract(List.of(jsonld), d.url, d.host, "de", ex,
                new ExtractContext(KgVocabularies.get(), Set.of("care"), false, collections));
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = this.now - 86_400_000L;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now));
    }

    private String entity(final String name) throws Exception {
        return this.store.read(c -> {
            try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery("SELECT e.public_id FROM kg_entity e"
                    + " JOIN kg_statement s ON s.subj = e.ent_rowid JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'name' AND e.status = 1"
                    + " AND s.obj_val = '" + name + "'")) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private void assess(final String action, final String predicate, final String value, final String subject, final String quote)
            throws Exception {
        final Assessments.Draft d = new Assessments.Draft();
        d.action = action;
        d.scopeKind = Assessments.SOURCE;
        d.scope = "ALPHA1host01";
        d.predicate = predicate;
        d.value = value;
        d.subjectId = subject;
        d.quote = quote;
        d.reason = "metrics test";
        d.actor = "admin";
        assertNull(d.validate());
        this.store.write(WriteClass.MAINTENANCE, 1L << 20, tx -> Assessments.create(tx, this.cfg, d, this.now));
    }

    @Test
    public void theMetricsOfTheLaterWorkPackagesAreCountedWithTheirUnits() throws Exception {
        publish("ALPHA1host01", "https://www.alpha-pflege.example/preise", List.of("alpha-care", "beta-care"), "{\"@context\":\"https://schema.org\","
                + "\"@type\":\"Organization\",\"name\":\"Alpha Pflege GmbH\",\"url\":\"https://www.alpha-pflege.example/\",\"telephone\":\"030 1234567\","
                + "\"makesOffer\":[{\"@type\":\"Offer\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Tagespflege\","
                + "\"url\":\"https://www.alpha-pflege.example/tagespflege\"},\"price\":\"49\",\"priceCurrency\":\"EUR\"},"
                + "{\"@type\":\"Offer\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Kurzzeitpflege\"},\"price\":\"95\",\"priceCurrency\":\"EUR\","
                + "\"validFrom\":\"2027-07-01\"},"
                + "{\"@type\":\"Offer\",\"name\":\"Spende\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Förderkreis\"},\"price\":\"20\","
                + "\"priceCurrency\":\"EUR\"}]}");
        // the day care's own page describes it again, without its provider: a second service with the same page
        publish("ALPHA2host01", "https://www.alpha-pflege.example/tagespflege", List.of("alpha-care"), "{\"@context\":\"https://schema.org\","
                + "\"@type\":\"Service\",\"name\":\"Tagespflege Alpha\",\"url\":\"https://alpha-pflege.example/tagespflege/\"}");
        assess(Assessments.WITHDRAW, "phone", null, null, null);
        assess(Assessments.ASSERT, "legal_form", "GmbH", entity("Alpha Pflege GmbH"), "Alpha Pflege GmbH");
        final JSONObject q = new QualityMetrics(this.store, () -> this.now).compute();
        assertEquals(q.toString(), 0, q.getJSONArray("errors").length());
        final JSONObject m = q.getJSONObject("metrics");
        for (final String k : m.keySet()) assertTrue("unit of " + k, m.getJSONObject(k).has("unit"));
        assertTrue(m.toString(), m.getJSONObject("entities_in_several_collections").getLong("total") >= 1);
        final JSONObject a = m.getJSONObject("assessments");
        assertEquals(2, a.getLong("total"));
        assertEquals(1, a.getJSONObject("active_by_action").getLong("withdraw"));
        assertEquals(1, a.getJSONObject("active_by_action").getLong("assert"));
        assertEquals(1, m.getJSONObject("withdrawn_evidence").getLong("total"));
        assertEquals(1, m.getJSONObject("withdrawn_evidence").getJSONObject("by_tier").getLong("tier1"));
        assertEquals(1, m.getJSONObject("operator_statements").getLong("evidence"));
        assertEquals(1, m.getJSONObject("operator_statements").getLong("statements"));
        assertEquals("the legal form is also read from the JSON-LD name: not the operator's alone", 0,
                m.getJSONObject("operator_statements").getLong("only_operator"));
        assertEquals(1, m.getJSONObject("monetary_amounts_by_role").getJSONObject("by_role").getLong("donation"));
        assertEquals(1, m.getJSONObject("prices_not_yet_valid").getLong("total"));
        assertEquals(1, m.getJSONObject("service_product_page_duplicate_candidates").getLong("groups"));
        assertEquals(2, m.getJSONObject("service_product_page_duplicate_candidates").getLong("services"));
        assertTrue(m.getJSONObject("identity_keys").getJSONObject("by_scheme").has("service_name"));
        assertTrue("product_url is off by default", !m.getJSONObject("identity_keys").getJSONObject("by_scheme").has("product_url"));
        assertTrue(m.getJSONObject("price_statements_by_tier").getString("unit").contains("4 operator"));
    }
}
