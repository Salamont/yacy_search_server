package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

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
import net.yacy.scoutro.collections.CollectionDecision;
import net.yacy.scoutro.collections.CollectionDecisions;
import net.yacy.scoutro.collections.DecisionStore;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.Capture;
import net.yacy.scoutro.knowledge.sync.DirtySet;
import net.yacy.scoutro.knowledge.sync.EmbeddedSolrSource;
import net.yacy.scoutro.knowledge.sync.Gates;
import net.yacy.scoutro.knowledge.sync.JsonLdCapture;
import net.yacy.scoutro.knowledge.sync.SyncService;

/**
 * The decision routes on a real embedded Solr (collection1 and webgraph) with the knowledge graph's sync: a wrongly
 * crawled domain (drolshagens.de in bauteamcheck-web) is moved to the fallback collection by an exact decision, its
 * pages keep their content and graph data, the move resumes after an interruption, and reassign records its move as
 * a durable decision.
 */
public class CollectionDecisionsApiTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EmbeddedInstance solr;
    private KgStore store;
    private DirtySet dirty;
    private SyncService sync;
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private CollectionDecisions decisions;
    private Path decisionDir;
    private Path marker;
    private final Set<String> followed = new TreeSet<>(Set.of("bauteamcheck-web", "sonstige", "stackfinder-web", "checkthecoach-web"));
    private final Set<String> failing = new TreeSet<>();

    private static final Set<String> KNOWN = Set.of("bauteamcheck-web", "sonstige", "stackfinder-web", "checkthecoach-web", "edelsenior-web");
    private static final String PRODUCT = "{\"@context\":\"https://schema.org\",\"@type\":\"Product\",\"name\":\"Drolshagens Nuss-Spezialitäten\","
            + "\"offers\":{\"@type\":\"Offer\",\"price\":\"9.00\",\"priceCurrency\":\"EUR\"}}";

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1",
                new String[] {"collection1", "webgraph"});
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "bauteamcheck-web,sonstige,stackfinder-web,checkthecoach-web"));
        assertTrue(cfg.problems().toString(), cfg.valid());
        final KgPaths paths = new KgPaths(this.tmp.newFolder("data"));
        this.store = KgStore.open(paths, cfg, new StorageGuard(cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis), KgStore.SQLITE,
                System::currentTimeMillis);
        this.dirty = new DirtySet(1000);
        Capture.activate(this.dirty);
        this.sync = new SyncService(cfg, this.store, this.dirty, new EmbeddedSolrSource(() -> this.solr.getDefaultServer()),
                new Gates(cfg, Gates.IDLE), this.clock::get, false);
        this.decisionDir = this.tmp.newFolder("collections").toPath();
        this.marker = this.tmp.newFolder("settings").toPath().resolve("marker");
        this.decisions = new CollectionDecisions(new DecisionStore(this.decisionDir, this.marker), "sonstige", false, this.clock::get);

        page("AAAAAAdrols1", "https://drolshagens.de/", List.of("bauteamcheck-web"), PRODUCT);
        page("BBBBBBdrols2", "https://drolshagens.de/products/drolshagens-nuss-spezialitaten", List.of("bauteamcheck-web"), PRODUCT);
        page("CCCCCCdrols3", "https://www.drolshagens.de/kontakt", List.of("bauteamcheck-web"), null);
        page("DDDDDDbaufi1", "https://bau-firma.de/", List.of("bauteamcheck-web"), null);
        edge("edge-drols-1", "AAAAAAdrols1", List.of("bauteamcheck-web"));
        edge("edge-drols-2", "BBBBBBdrols2", List.of("bauteamcheck-web"));
        edge("edge-bau-1", "DDDDDDbaufi1", List.of("bauteamcheck-web"));
        client().commit();
        this.solr.getServer("webgraph").commit();
        settle();
    }

    @After
    public void close() {
        Capture.deactivate(this.dirty);
        JsonLdCapture.clear();
        if (this.store != null) this.store.close();
        if (this.solr != null) this.solr.close();
    }

    private CollectionReassign.Index index() {
        return new CollectionReassign.Index() {
            @Override
            public SolrDocumentList select(final String core, final ModifiableSolrParams params) throws IOException {
                try {
                    return CollectionDecisionsApiTest.this.solr.getServer(core).query(params).getResults();
                } catch (final Exception e) {
                    throw new IOException(e);
                }
            }

            @Override
            public Map<String, Long> facet(final String core, final ModifiableSolrParams params, final String field) throws IOException {
                try {
                    final Map<String, Long> out = new TreeMap<>();
                    for (final org.apache.solr.client.solrj.response.FacetField.Count c : CollectionDecisionsApiTest.this.solr.getServer(core)
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
                if (CollectionDecisionsApiTest.this.failing.contains(id)) throw new IOException("version conflict for " + id);
                CollectionReassign.atomicSet(CollectionDecisionsApiTest.this.solr.getServer(core), id, version, collections);
            }

            @Override
            public void commit(final String core) throws IOException {
                try {
                    CollectionDecisionsApiTest.this.solr.getServer(core).commit();
                } catch (final Exception e) {
                    throw new IOException(e);
                }
            }

            @Override
            public boolean has(final String core) {
                return true;
            }
        };
    }

    private CollectionDecisionsApi api(final int maxApply, final int batch) {
        return new CollectionDecisionsApi(this.decisions, new DecisionApply(index(), "sonstige", this.followed::contains).batch(batch),
                KNOWN::contains, maxApply);
    }

    private CollectionDecisionsApi api() {
        return api(CollectionDecisionsApi.MAX_APPLY, DecisionApply.BATCH);
    }

    private SolrClient client() {
        return this.solr.getDefaultServer();
    }

    private void page(final String id, final String url, final List<String> collections, final String ldJson) throws Exception {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", url);
        final String host = java.net.URI.create(url).getHost();
        d.setField("host_s", host);
        d.setField("host_id_s", id.substring(6));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", collections);
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        d.setField("title", List.of("Seite " + id));
        d.setField("text_t", "Inhalt von " + url);
        if (ldJson != null) d.setField("ld_json_txt", List.of(ldJson));
        client().add(d);
    }

    private void edge(final String id, final String page, final List<String> collections) throws Exception {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("source_id_s", page);
        d.setField("collection_sxt", collections);
        this.solr.getServer("webgraph").add(d);
    }

    private void settle() {
        for (int i = 0; i < 2000; i++) {
            final boolean more = this.sync.step();
            final JSONObject lag = this.sync.status().optJSONObject("lag");
            if (!more && this.dirty.size() == 0 && count("SELECT count(*) FROM kg_work") == 0L && !(lag != null && lag.optBoolean("reconcile_pending"))) return;
            if (!more) this.clock.addAndGet(3000L);
        }
        fail("did not settle: " + this.sync.status());
    }

    private long count(final String sql) {
        try {
            return this.store.read(c -> KgStore.queryLong(c, sql));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> collections(final String core, final String id) throws Exception {
        final SolrDocument d = this.solr.getServer(core).getById(id);
        assertNotNull(id, d);
        final List<String> out = new ArrayList<>();
        for (final Object o : d.getFieldValues("collection_sxt")) out.add(o.toString());
        return new ArrayList<>(new TreeSet<>(out));
    }

    private List<String> graphCollections(final String docId) throws Exception {
        return this.store.read(c -> {
            final List<String> out = new ArrayList<>();
            try (java.sql.PreparedStatement p = c.prepareStatement("SELECT c.name FROM kg_doc d JOIN kg_doc_collection dc ON dc.doc_rowid=d.doc_rowid"
                    + " JOIN kg_collection c ON c.coll_id=dc.coll_id WHERE d.doc_id=? ORDER BY c.name")) {
                p.setString(1, docId);
                try (java.sql.ResultSet rs = p.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    private static JSONObject body(final Object... kv) throws Exception {
        final JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        return o;
    }

    private JSONObject decideDrolshagens(final CollectionDecisionsApi api) throws Exception {
        final JSONObject req = body("domain", "drolshagens.de", "collections", new JSONArray(List.of("sonstige")), "reason", "fachlich_falsch",
                "note", "Nuss-Spezialitaeten, kein Bauunternehmen; OSM-Profil bauteamcheck");
        final JSONObject preview = api.decide(req, "admin");
        assertFalse(preview.getBoolean("applied"));
        req.put("confirm", preview.getString("token"));
        return api.decide(req, "admin");
    }

    @Test
    public void previewThenApplyMovesTheDomainAndKeepsItsGraphData() throws Exception {
        final long entitiesBefore = count("SELECT count(*) FROM kg_entity");
        final long evidenceBefore = count("SELECT count(*) FROM kg_evidence");
        assertTrue(evidenceBefore > 0);
        final JSONObject preview = api().decide(body("domain", "drolshagens.de", "collections", new JSONArray(List.of("sonstige")),
                "reason", "fachlich_falsch"), "admin");
        assertEquals(3, preview.getJSONObject("preview").getInt("documents"));
        assertEquals(3, preview.getJSONObject("preview").getInt("changes"));
        assertEquals(List.of("bauteamcheck-web"), collections("collection1", "AAAAAAdrols1")); // nothing written by the preview
        final JSONObject applied = decideDrolshagens(api());
        assertTrue(applied.getBoolean("applied"));
        assertEquals("applied", applied.getJSONObject("decision").getString("apply"));
        assertEquals(3, applied.getJSONObject("result").getInt("updated"));
        for (final String id : new String[] {"AAAAAAdrols1", "BBBBBBdrols2", "CCCCCCdrols3"}) {
            assertEquals(List.of("sonstige"), collections("collection1", id));
        }
        assertEquals(List.of("sonstige"), collections("webgraph", "edge-drols-1"));
        assertEquals(List.of("bauteamcheck-web"), collections("collection1", "DDDDDDbaufi1"));
        assertEquals(List.of("bauteamcheck-web"), collections("webgraph", "edge-bau-1"));
        settle();
        // the graph re-scoped the pages: identities and evidence stay (no re-extraction, no LLM)
        assertEquals(List.of("sonstige"), graphCollections("AAAAAAdrols1"));
        assertEquals(entitiesBefore, count("SELECT count(*) FROM kg_entity"));
        assertEquals(evidenceBefore, count("SELECT count(*) FROM kg_evidence"));
        // durable: a restarted peer reads the decision
        final CollectionDecisions restarted = new CollectionDecisions(new DecisionStore(this.decisionDir, this.marker), "sonstige", false,
                this.clock::get);
        assertNotNull(restarted.find("www.drolshagens.de", "/x"));
    }

    @Test
    public void staleTokenWritesNothing() throws Exception {
        final JSONObject req = body("domain", "drolshagens.de", "collections", new JSONArray(List.of("sonstige")), "reason", "fachlich_falsch");
        final String token = api().decide(req, "admin").getString("token");
        page("EEEEEEdrols4", "https://drolshagens.de/neu", List.of("bauteamcheck-web"), null);
        client().commit();
        req.put("confirm", token);
        try {
            api().decide(req, "admin");
            fail("stale token accepted");
        } catch (final ApiException e) {
            assertEquals("decision_preview_stale", e.code());
        }
        assertTrue(this.decisions.all().isEmpty());
        assertEquals(List.of("bauteamcheck-web"), collections("collection1", "AAAAAAdrols1"));
    }

    @Test
    public void fallbackOnlyForCrawledPages() throws Exception {
        try {
            api().decide(body("domain", "nie-gecrawlt.de", "collections", new JSONArray(List.of("sonstige")), "reason", "fachlich_falsch"), "admin");
            fail("a decision put an uncrawled domain into the fallback collection");
        } catch (final ApiException e) {
            assertEquals("fallback_needs_pages", e.code());
        }
    }

    @Test
    public void aMoveOutOfTheGraphIsRefused() throws Exception {
        this.followed.remove("sonstige");
        try {
            api().decide(body("domain", "drolshagens.de", "collections", new JSONArray(List.of("sonstige")), "reason", "fachlich_falsch"), "admin");
            fail("pages would have left the knowledge graph");
        } catch (final ApiException e) {
            assertEquals("target_not_followed", e.code());
        }
        assertTrue(this.decisions.all().isEmpty());
    }

    @Test
    public void interruptedApplyResumesFromItsCursor() throws Exception {
        final JSONObject first = decideDrolshagens(api(2, 1));
        assertEquals("partial", first.getJSONObject("decision").getString("apply"));
        assertFalse(first.getJSONObject("result").getBoolean("done"));
        final String id = first.getJSONObject("decision").getString("id");
        final JSONObject second = api(2, 1).resume(body("id", id));
        assertEquals("applied", second.getJSONObject("decision").getString("apply"));
        for (final String page : new String[] {"AAAAAAdrols1", "BBBBBBdrols2", "CCCCCCdrols3"}) {
            assertEquals(List.of("sonstige"), collections("collection1", page));
        }
    }

    @Test
    public void crashAfterStoringTheDecisionIsCompletedLater() throws Exception {
        // the peer stopped right after the write-ahead: decision stored as pending, index untouched
        final CollectionDecision.Builder b = new CollectionDecision.Builder();
        b.id = CollectionDecisions.newId();
        b.domain = "drolshagens.de";
        b.collections = List.of("sonstige");
        b.reason = "fachlich_falsch";
        b.decidedAt = this.clock.get();
        b.apply = CollectionDecision.Apply.PENDING;
        final CollectionDecision pending = this.decisions.put(b.build(), "decide");
        assertEquals(1, this.decisions.status().getInt("pendingApply"));
        assertEquals(List.of("bauteamcheck-web"), collections("collection1", "AAAAAAdrols1"));
        final JSONObject done = api().resume(body("id", pending.id));
        assertEquals("applied", done.getJSONObject("decision").getString("apply"));
        assertEquals(0, this.decisions.status().getInt("pendingApply"));
        assertEquals(List.of("sonstige"), collections("collection1", "CCCCCCdrols3"));
        // applying again changes nothing (idempotent)
        assertEquals(0, api().resume(body("id", pending.id)).getJSONObject("result").getInt("updated"));
    }

    @Test
    public void aPageChangedMeanwhileKeepsTheDecisionPartial() throws Exception {
        this.failing.add("BBBBBBdrols2");
        final JSONObject r = decideDrolshagens(api());
        assertEquals("partial", r.getJSONObject("decision").getString("apply"));
        assertEquals(1, r.getJSONObject("result").getJSONArray("failed").length());
        assertEquals(List.of("bauteamcheck-web"), collections("webgraph", "edge-drols-2")); // its edge follows its page
        this.failing.clear();
        final JSONObject again = api().resume(body("id", r.getJSONObject("decision").getString("id")));
        assertEquals("applied", again.getJSONObject("decision").getString("apply"));
        assertEquals(List.of("sonstige"), collections("collection1", "BBBBBBdrols2"));
        assertEquals(List.of("sonstige"), collections("webgraph", "edge-drols-2"));
    }

    @Test
    public void reviseAndRevoke() throws Exception {
        final JSONObject r = decideDrolshagens(api());
        final String id = r.getJSONObject("decision").getString("id");
        // a later finding: the shop fits another existing collection (here: edelsenior-web as an example of a revision)
        final JSONObject req = body("domain", "drolshagens.de", "collections", new JSONArray(List.of("edelsenior-web")), "reason", "passende_fachcollection");
        this.followed.add("edelsenior-web");
        req.put("confirm", api().decide(req, "admin").getString("token"));
        final JSONObject revised = api().decide(req, "admin");
        assertEquals(id, revised.getJSONObject("decision").getString("id"));
        assertEquals(2, revised.getJSONObject("decision").getInt("revision"));
        assertEquals(List.of("edelsenior-web"), collections("collection1", "AAAAAAdrols1"));
        assertTrue(revised.getJSONObject("decision").getJSONObject("evidence").has("supersedes"));
        final JSONObject preview = api().revoke(body("id", id), "admin");
        final JSONObject revoked = api().revoke(body("id", id, "confirm", preview.getString("token")), "admin");
        assertEquals("revoked", revoked.getJSONObject("decision").getString("status"));
        assertEquals(null, this.decisions.find("drolshagens.de", "/"));
        // pages stay where they are
        assertEquals(List.of("edelsenior-web"), collections("collection1", "AAAAAAdrols1"));
    }

    @Test
    public void reassignRecordsItsMoveAsADurableDecision() throws Exception {
        final CollectionReassign reassign = new CollectionReassign(index(), KNOWN::contains).withDecisions(this.decisions, this.followed::contains);
        final JSONObject req = body("domain", "drolshagens.de", "add", new JSONArray(List.of("sonstige")), "remove", new JSONArray(List.of("bauteamcheck-web")));
        req.put("confirm", reassign.run(req, h -> false).getString("token"));
        final JSONObject out = reassign.run(req, h -> false);
        assertTrue(out.getBoolean("applied"));
        final JSONObject decision = out.getJSONObject("decision");
        assertEquals("transform", decision.getString("mode"));
        assertEquals("applied", decision.getString("apply"));
        assertEquals("reassign", decision.getString("reason"));
        // later crawls through the bauteamcheck profile now write sonstige
        assertEquals(List.of("sonstige"), this.decisions.find("drolshagens.de", "/").effective(List.of("bauteamcheck-web"), "sonstige"));
        // a second reassign of a decided domain is refused: change the decision instead
        try {
            new CollectionReassign(index(), KNOWN::contains).withDecisions(this.decisions, this.followed::contains)
                    .run(body("domain", "drolshagens.de", "add", new JSONArray(List.of("edelsenior-web"))), h -> false);
            fail("reassign over an existing decision");
        } catch (final ApiException e) {
            assertEquals("decision_exists", e.code());
        }
    }

    @Test
    public void reassignOutOfTheGraphIsRefused() throws Exception {
        this.followed.remove("sonstige");
        try {
            new CollectionReassign(index(), KNOWN::contains).withDecisions(this.decisions, this.followed::contains)
                    .run(body("domain", "drolshagens.de", "add", new JSONArray(List.of("sonstige")), "remove", new JSONArray(List.of("bauteamcheck-web"))),
                            h -> false);
            fail("reassign would drop the pages from the graph");
        } catch (final ApiException e) {
            assertEquals("target_not_followed", e.code());
        }
    }

    @Test
    public void unavailableStoreRefusesDecisionsAndRecoverNeedsTheToken() throws Exception {
        decideDrolshagens(api());
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(this.decisionDir)) {
            files.forEach(p -> p.toFile().delete());
        }
        this.decisions = new CollectionDecisions(new DecisionStore(this.decisionDir, this.marker), "sonstige", false, this.clock::get);
        try {
            api().decide(body("domain", "drolshagens.de", "collections", new JSONArray(List.of("sonstige")), "reason", "x"), "admin");
            fail("decided on an unreadable store");
        } catch (final ApiException e) {
            assertEquals("decisions_unavailable", e.code());
        }
        final JSONObject preview = api().recover(body(), "admin");
        assertFalse(preview.getBoolean("applied"));
        try {
            api().recover(body("confirm", "0000000000000000"), "admin");
            fail("wrong token accepted");
        } catch (final ApiException e) {
            assertEquals("decision_preview_stale", e.code());
        }
        assertTrue(api().recover(body("confirm", preview.getString("token")), "admin").getBoolean("applied"));
        assertTrue(this.decisions.available());
    }
}
