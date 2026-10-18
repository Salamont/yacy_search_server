package net.yacy.scoutro.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.solr.common.SolrInputDocument;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.Gates;
import net.yacy.scoutro.knowledge.sync.JsonLdCapture;

/**
 * WP-H text fit on the running graph: the rules read the categories a domain's pages offer through every collection's
 * vocabulary at once, from the indexed text, without a model; a care collection's domain whose pages offer construction
 * work fits the construction vocabulary, not the care one.
 */
public class TextFitTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EmbeddedInstance solr;
    private File data;
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1",
                new String[] {"collection1", "webgraph"});
        this.data = this.tmp.newFolder("data");
    }

    @After
    public void close() {
        KgRuntime.stop();
        JsonLdCapture.clear();
        if (this.solr != null) {
            this.solr.close();
        }
    }

    private SolrInputDocument doc(final String id, final String path, final String text) {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", "https://www.dach-muster.de/" + path);
        d.setField("host_s", "www.dach-muster.de");
        d.setField("host_id_s", id.substring(6));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", List.of("pflege-web"));
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        d.setField("title", List.of("Muster Ausbau"));
        d.setField("text_t", text);
        return d;
    }

    @Test
    public void thePagesOfferTheWorkOfAnotherVocabulary() throws Exception {
        final Map<String, String> settings = KgTestSupport.enabled(KgConfig.COLLECTIONS, "pflege-web,bau-web", "scoutro.kg.vocab.pflege-web",
                "care", "scoutro.kg.vocab.bau-web", "construction");
        KgRuntime.start(new KgRuntime.Env(this.data, settings::get, this.clock::get, new KgTestSupport.Probe(), KgStore.SQLITE, false,
                () -> this.solr.getDefaultServer(), Gates.IDLE));
        final KgRuntime r = KgRuntime.current();
        assertNotNull(r);
        this.solr.getDefaultServer().add(doc("AAAAAAdach01", "leistungen", "Unsere Leistungen. Wir bieten Trockenbau, Malerarbeiten und "
                + "Innenausbau aus einer Hand. Fordern Sie ein Angebot an."));
        this.solr.getDefaultServer().add(doc("BBBBBBdach02", "leistungen/trockenbau", "Leistungen: Trockenbau und Innenausbau für Wohnung und Büro."
                + " Wir bieten Trockenbauarbeiten und Malerarbeiten zum Festpreis."));
        this.solr.getDefaultServer().add(doc("CCCCCCdach03", "leistungen/maler", "Unser Angebot: Malerarbeiten, Lackierarbeiten und Trockenbau."));
        this.solr.getDefaultServer().add(doc("DDDDDDdach04", "kontakt", "Kontakt. Muster Ausbau GmbH, Musterstraße 1, 12345 Musterstadt."));
        this.solr.getDefaultServer().commit();
        for (int i = 0; i < 1500 && !synced(r); i++) {
            r.tick();
            r.syncTick();
            this.clock.addAndGet(3000L);
        }
        if (!synced(r)) fail("not synchronised: " + r.status());
        final JSONObject fit = r.textFit("dach-muster.de", List.of("pflege-web", "bau-web"));
        assertEquals(fit.toString(), 4, fit.getInt("pages"));
        final JSONObject bau = fit.getJSONObject("collections").getJSONObject("bau-web");
        assertEquals("construction", bau.getString("vocabulary"));
        assertTrue(fit.toString(), bau.getJSONArray("categories").length() >= 2);
        assertTrue(fit.toString(), bau.getInt("pages") >= 2);
        final JSONObject care = fit.getJSONObject("collections").getJSONObject("pflege-web");
        assertEquals(fit.toString(), 0, care.getJSONArray("categories").length());
        // another domain's pages are never read; an unknown domain has none
        assertEquals(0, r.textFit("andere-muster.de", List.of("pflege-web", "bau-web")).getInt("pages"));
    }

    private static boolean synced(final KgRuntime r) {
        final JSONObject sync = r.status().optJSONObject("sync");
        final JSONObject lag = sync == null ? null : sync.optJSONObject("lag");
        if (sync == null || !sync.optBoolean("initialized") || lag == null || lag.optLong("pending", 1L) != 0L || lag.optBoolean("reconcile_pending", true)) {
            return false;
        }
        try {
            return r.store().read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_doc")) == 4L;
        } catch (final KgException e) {
            return false;
        }
    }
}
