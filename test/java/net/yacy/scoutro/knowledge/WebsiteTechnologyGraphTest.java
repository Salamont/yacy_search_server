package net.yacy.scoutro.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.extract.WebsiteTechnology;
import net.yacy.scoutro.knowledge.publish.Assessments;
import net.yacy.scoutro.knowledge.read.ObservationHistory;
import net.yacy.scoutro.knowledge.store.KgChangeLog;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.Gates;
import net.yacy.scoutro.knowledge.sync.JsonLdCapture;

/**
 * Scoutro R5 in the graph: the index field website_technology_sxt of a page becomes website_technology statements about
 * the site's operator with tier-1 evidence of the extractor technology/1; a changed plugin with the same text is read
 * again while the earlier technology stays in the history without being confirmed as current; an identical recrawl adds
 * nothing; a technology is never internal use, an offer or a collection; withdrawals hold across recrawls; the model is
 * never asked for markup; the switch turns it off.
 */
public class WebsiteTechnologyGraphTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EmbeddedInstance solr;
    private File data;
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private KgLoadMeasurement.Model model;

    private static final String WP = "wordpress|1|metagenerator|3|WordPress 6.4.2";
    private static final String PRO = "elementor-pro|1|scriptpath|2|/wp-content/plugins/elementor-pro/assets/js/frontend.min.js?ver=3.18.2";
    private static final String GTM = "google-tag-manager|1|scriptcode|2|'gtm.start':new Date().getTime(),event:'gtm.js'";
    private static final String ROCKET = "wp-rocket|1|comment|1|Performance optimized by WP Rocket. Learn more: https://wp-rocket.me";
    private static final String UNKNOWN = "futureplugin|2|scriptpath|1|/wp-content/plugins/futureplugin/x.js";

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1",
                new String[] {"collection1", "webgraph"});
        this.data = this.tmp.newFolder("data");
        this.model = new KgLoadMeasurement.Model(0);
    }

    @After
    public void close() {
        KgRuntime.stop();
        JsonLdCapture.clear();
        if (this.solr != null) this.solr.close();
    }

    private static Map<String, String> settings(final String... extra) {
        final String[] all = new String[extra.length + 6];
        all[0] = KgConfig.COLLECTIONS;
        all[1] = "c1,c2";
        all[2] = KgConfig.LLM_COLLECTIONS;
        all[3] = "c1";
        all[4] = KgConfig.VOCAB_PREFIX + "c1";
        all[5] = "software";
        System.arraycopy(extra, 0, all, 6, extra.length);
        return KgTestSupport.enabled(all);
    }

    private KgRuntime start(final Map<String, String> settings) {
        KgRuntime.start(new KgRuntime.Env(this.data, settings::get, this.clock::get, new KgTestSupport.Probe(), KgStore.SQLITE, false,
                () -> this.solr.getDefaultServer(), Gates.IDLE).withLlm(this.model));
        final KgRuntime r = KgRuntime.current();
        assertNotNull(r);
        return r;
    }

    private SolrClient client() {
        return this.solr.getDefaultServer();
    }

    /** A page as the index stores it; the text signature stands for the visible text. */
    private void page(final String id, final String url, final String collection, final long textSignature, final String... technologies)
            throws Exception {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", url);
        d.setField("host_s", java.net.URI.create(url).getHost());
        d.setField("host_id_s", id.substring(6));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", List.of(collection));
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        d.setField("exact_signature_l", textSignature);
        d.setField("title", List.of("Musterberatung"));
        d.setField("text_t", "Wir beraten zu SAP, Salesforce und Pardot. Unsere Leistung: Google-Tag-Manager-Einrichtung.");
        if (technologies.length > 0) d.setField("website_technology_sxt", List.of(technologies));
        client().add(d);
        client().commit();
    }

    private void drive(final KgRuntime r, final BooleanSupplier done) {
        for (int i = 0; i < 2000; i++) {
            r.tick();
            r.syncTick();
            if (done.getAsBoolean()) return;
            this.clock.addAndGet(3000L);
        }
        fail("not reached: " + r.status());
    }

    private void settle(final KgRuntime r) {
        drive(r, () -> {
            final JSONObject sync = r.status().optJSONObject("sync");
            final JSONObject lag = sync == null ? null : sync.optJSONObject("lag");
            return sync != null && sync.optBoolean("initialized") && lag != null && lag.optLong("pending", 1L) == 0L
                    && !lag.optBoolean("reconcile_pending", true) && count(r, "SELECT count(*) FROM kg_work") == 0L;
        });
    }

    private static long count(final KgRuntime r, final String sql) {
        try {
            return r.store().read(c -> KgStore.queryLong(c, sql));
        } catch (final KgException e) {
            throw new AssertionError(e);
        }
    }

    /** The live website_technology values, as {technology(+variant)@host}. */
    private static TreeSet<String> technologies(final KgRuntime r) throws Exception {
        return r.store().read(c -> {
            final TreeSet<String> out = new TreeSet<>();
            try (java.sql.ResultSet rs = c.createStatement().executeQuery("SELECT s.obj_val FROM kg_statement s JOIN kg_vocab v ON v.term_id=s.pred"
                    + " WHERE v.name='website_technology' AND EXISTS(SELECT 1 FROM kg_evidence e WHERE e.stmt_rowid=s.stmt_rowid)")) {
                while (rs.next()) {
                    final JSONObject v = new JSONObject(rs.getString(1));
                    out.add(v.getString("technology") + "@" + v.getString("host"));
                }
            } catch (final org.json.JSONException e) {
                throw new java.sql.SQLException(e);
            }
            return out;
        });
    }

    private static String one(final KgRuntime r, final String sql) throws Exception {
        return r.store().read(c -> KgStore.queryString(c, sql));
    }

    @Test
    public void technologiesOfThePageBecomeWebsiteObservationsOfTheSiteOperator() throws Exception {
        final KgRuntime r = start(settings());
        settle(r);
        page("AAAAAAhost01", "https://www.musterberatung.de/", "c1", 11L, WP, PRO, GTM, UNKNOWN);
        settle(r);
        assertEquals("unknown signatures are skipped", new TreeSet<>(List.of("elementor-pro@www.musterberatung.de",
                "google-tag-manager@www.musterberatung.de", "wordpress@www.musterberatung.de")), technologies(r));
        final JSONObject wp = new JSONObject(one(r, "SELECT s.obj_val FROM kg_statement s JOIN kg_vocab v ON v.term_id=s.pred"
                + " WHERE v.name='website_technology' AND s.obj_val LIKE '%\"wordpress\"%'"));
        assertEquals("WordPress", wp.getString("name"));
        assertEquals(WebsiteTechnology.CMS, wp.getString("category"));
        assertEquals("the generator states the version", "6.4.2", wp.getString("version"));
        assertEquals("generator", wp.getString("version_source"));
        final JSONObject pro = new JSONObject(one(r, "SELECT s.obj_val FROM kg_statement s JOIN kg_vocab v ON v.term_id=s.pred"
                + " WHERE v.name='website_technology' AND s.obj_val LIKE '%elementor-pro%'"));
        assertEquals("Pro", pro.getString("variant"));
        assertFalse("an asset's ?ver= is no product version", pro.has("version"));
        // tier-1 evidence of the extractor technology/1, with the element, the rule version and the matched markup
        assertEquals(3L, count(r, "SELECT count(*) FROM kg_evidence e JOIN kg_extractor x ON x.ext_id=e.ext_id JOIN kg_statement s"
                + " ON s.stmt_rowid=e.stmt_rowid JOIN kg_vocab v ON v.term_id=s.pred WHERE v.name='website_technology' AND e.tier=1 AND e.kind=2"
                + " AND x.name='technology' AND x.version='1'"));
        assertEquals("html:metagenerator:tech/1", one(r, "SELECT e.locator FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid=e.stmt_rowid"
                + " WHERE s.obj_val LIKE '%\"wordpress\"%'"));
        assertTrue(one(r, "SELECT e.excerpt FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid=e.stmt_rowid"
                + " WHERE s.obj_val LIKE '%elementor-pro%'").contains("/plugins/elementor-pro/"));
        // about an organization (the domain's operator), and nothing else follows from the markup
        assertEquals("organization", one(r, "SELECT t.name FROM kg_statement s JOIN kg_vocab v ON v.term_id=s.pred JOIN kg_entity n"
                + " ON n.ent_rowid=s.subj JOIN kg_vocab t ON t.term_id=n.type WHERE v.name='website_technology' LIMIT 1"));
        assertEquals("no system signal, offer or service from markup", 0L, count(r, "SELECT count(*) FROM kg_statement s JOIN kg_vocab v"
                + " ON v.term_id=s.pred WHERE v.name IN ('system_signal','offers','category') AND (s.obj_val LIKE '%Pardot%'"
                + " OR s.obj_val LIKE '%WordPress%' OR s.obj_val LIKE '%Tag Manager%' OR s.obj_val LIKE '%Elementor%')"));
        assertEquals("archived as observations", 3L, count(r, "SELECT count(*) FROM kg_observation WHERE predicate='website_technology'"));
        // the business view lists them in a section of their own, never among the systems
        final String org = one(r, "SELECT n.public_id FROM kg_statement s JOIN kg_vocab v ON v.term_id=s.pred JOIN kg_entity n"
                + " ON n.ent_rowid=s.subj WHERE v.name='website_technology' LIMIT 1");
        final net.yacy.scoutro.knowledge.read.KgReader reader = r.reader();
        final JSONObject view = new net.yacy.scoutro.knowledge.read.BusinessView(reader).entity(org, reader.viewer(List.of("c1", "c2")), false);
        final org.json.JSONArray techs = view.getJSONArray("website_technologies");
        assertEquals(3, techs.length());
        for (int i = 0; i < techs.length(); i++) {
            assertEquals("website_integration", techs.getJSONObject(i).getString("meaning"));
        }
        assertFalse("no system from markup", view.has("systems"));
        assertTrue("collections are unchanged", count(r, "SELECT count(*) FROM kg_collection WHERE name NOT IN ('c1','c2')") == 0L);
    }

    @Test
    public void aChangedPluginWithTheSameTextIsReadAgainAndTheEarlierOneStaysInTheHistory() throws Exception {
        final KgRuntime r = start(settings());
        settle(r);
        page("AAAAAAhost01", "https://www.musterberatung.de/", "c1", 11L, WP, PRO);
        settle(r);
        final int calls = this.model.calls.get();
        final String proObservation = one(r, "SELECT public_id FROM kg_observation WHERE predicate='website_technology' AND value LIKE '%elementor-pro%'");
        assertNotNull(proObservation);
        // the same visible text (signature), another plugin
        this.clock.addAndGet(86_400_000L);
        page("AAAAAAhost01", "https://www.musterberatung.de/", "c1", 11L, WP, ROCKET);
        settle(r);
        assertEquals(new TreeSet<>(List.of("wordpress@www.musterberatung.de", "wp-rocket@www.musterberatung.de")), technologies(r));
        assertTrue(one(r, "SELECT e.excerpt FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid=e.stmt_rowid"
                + " WHERE s.obj_val LIKE '%wp-rocket%'").contains("wp-rocket.me"));
        assertEquals("the markup is no reason to ask the model", calls, this.model.calls.get());
        // the earlier plugin: still in the history, no longer confirmed by a live statement
        final JSONObject h = r.store().read(c -> {
            try {
                return ObservationHistory.record(c, proObservation, KgChangeLog.Viewer.ALL);
            } catch (final Exception e) {
                throw new java.sql.SQLException(e);
            }
        });
        assertNotNull(h);
        assertTrue("not confirmed as current: " + h, h.isNull("live_statement") || !h.has("live_statement"));
        assertFalse("never stated as uninstalled", h.toString().contains("uninstall"));
        // an identical recrawl adds nothing; the page's services (rule/8: "Wir beraten zu SAP, …") are archived with every load
        // like any other fact, the technologies only when they change
        final String technologyObservations = "SELECT count(*) FROM kg_observation WHERE predicate='website_technology'";
        final long statements = count(r, "SELECT count(*) FROM kg_statement"), observations = count(r, technologyObservations),
                evidence = count(r, "SELECT count(*) FROM kg_evidence");
        for (int i = 0; i < 2; i++) {
            this.clock.addAndGet(86_400_000L);
            page("AAAAAAhost01", "https://www.musterberatung.de/", "c1", 11L, ROCKET, WP);
            settle(r);
        }
        assertEquals(statements, count(r, "SELECT count(*) FROM kg_statement"));
        assertEquals(observations, count(r, technologyObservations));
        assertEquals(evidence, count(r, "SELECT count(*) FROM kg_evidence"));
        // a collection move changes nothing about the markup
        page("AAAAAAhost01", "https://www.musterberatung.de/", "c2", 11L, ROCKET, WP);
        settle(r);
        assertEquals(new TreeSet<>(List.of("wordpress@www.musterberatung.de", "wp-rocket@www.musterberatung.de")), technologies(r));
        assertEquals(calls, this.model.calls.get());
    }

    @Test
    public void subpagesAndHostsKeepTheirOwnStack() throws Exception {
        final KgRuntime r = start(settings());
        settle(r);
        page("AAAAAAhost01", "https://www.musterberatung.de/", "c2", 11L, WP, GTM);
        page("BBBBBBhost02", "https://shop.musterberatung.de/", "c2", 12L, "pardot|1|scriptcode|1|piAId = '123456'");
        page("CCCCCChost01", "https://www.musterberatung.de/kontakt", "c2", 13L, WP);
        settle(r);
        assertEquals(new TreeSet<>(List.of("google-tag-manager@www.musterberatung.de", "pardot@shop.musterberatung.de",
                "wordpress@www.musterberatung.de")), technologies(r));
        assertEquals("one statement, two pages as evidence", 2L, count(r, "SELECT count(*) FROM kg_evidence e JOIN kg_statement s"
                + " ON s.stmt_rowid=e.stmt_rowid WHERE s.obj_val LIKE '%\"wordpress\"%'"));
        // the contact page loses its marker: the home page still confirms WordPress
        page("CCCCCChost01", "https://www.musterberatung.de/kontakt", "c2", 13L);
        settle(r);
        assertEquals(1L, count(r, "SELECT count(*) FROM kg_evidence e JOIN kg_statement s ON s.stmt_rowid=e.stmt_rowid"
                + " WHERE s.obj_val LIKE '%\"wordpress\"%'"));
        assertTrue(technologies(r).contains("wordpress@www.musterberatung.de"));
    }

    @Test
    public void aWithdrawnTechnologyStaysWithdrawnAcrossRecrawlsUntilRevoked() throws Exception {
        final KgRuntime r = start(settings());
        settle(r);
        page("AAAAAAhost01", "https://www.musterberatung.de/", "c2", 11L, WP, GTM);
        settle(r);
        final Assessments.Draft d = new Assessments.Draft();
        d.action = Assessments.WITHDRAW;
        d.scopeKind = "domain";
        d.scope = "musterberatung.de";
        d.predicate = Vocabulary.WEBSITE_TECHNOLOGY;
        d.reason = "consent tool, not the site's own tag management";
        d.actor = "admin";
        final JSONObject preview = r.assessmentPreview(d);
        final JSONObject created = r.assessmentCreate(d, preview.optString("token"));
        settle(r);
        assertTrue(technologies(r).isEmpty());
        this.clock.addAndGet(86_400_000L);
        page("AAAAAAhost01", "https://www.musterberatung.de/", "c2", 11L, WP, GTM, ROCKET);
        settle(r);
        assertTrue("held back on a recrawl, also a new technology of the predicate", technologies(r).isEmpty());
        r.assessmentRevoke(created.getJSONObject("assessment").getString("id"), "admin");
        settle(r);
        assertEquals(3, technologies(r).size());
    }

    @Test
    public void switchedOffTheGraphReadsNoTechnologyAndThePageKeepsItsHash() throws Exception {
        final KgRuntime r = start(settings(KgConfig.TECHNOLOGY_ENABLED, "false"));
        settle(r);
        page("AAAAAAhost01", "https://www.musterberatung.de/", "c2", 11L, WP, GTM);
        settle(r);
        assertTrue(technologies(r).isEmpty());
        final String hash = one(r, "SELECT hex(input_hash) FROM kg_doc WHERE doc_id='AAAAAAhost01'");
        page("AAAAAAhost01", "https://www.musterberatung.de/", "c2", 11L, ROCKET);
        settle(r);
        assertEquals("off: the markup is not part of the reading", hash, one(r, "SELECT hex(input_hash) FROM kg_doc WHERE doc_id='AAAAAAhost01'"));
        assertNull(one(r, "SELECT value FROM kg_meta WHERE key='reextract_pending'"));
    }
}
