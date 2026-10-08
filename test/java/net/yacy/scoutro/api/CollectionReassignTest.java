package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
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
 * collections.reassign on a real embedded Solr (collection1 and webgraph) with the knowledge graph's sync: the pages of
 * exactly one domain move from a wrong collection to the right one, keep their content and other collections, the
 * graph re-scopes them through its capture, and nothing else changes (yowea.com: stackfinder-web -> checkthecoach-web).
 */
public class CollectionReassignTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EmbeddedInstance solr;
    private KgStore store;
    private DirtySet dirty;
    private SyncService sync;
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private CollectionReassign reassign;

    private static final Set<String> KNOWN = Set.of("stackfinder-web", "checkthecoach-web", "partner-web", "bauteamcheck-web");
    private static final String ORG = "{\"@type\":\"Organization\",\"name\":\"Yowea GmbH\",\"url\":\"https://yowea.com/\",\"telephone\":\"030 1234567\"}";

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1",
                new String[] {"collection1", "webgraph"});
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "stackfinder-web,checkthecoach-web"));
        assertTrue(cfg.problems().toString(), cfg.valid());
        final KgPaths paths = new KgPaths(this.tmp.newFolder("data"));
        this.store = KgStore.open(paths, cfg, new StorageGuard(cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis), KgStore.SQLITE,
                System::currentTimeMillis);
        this.dirty = new DirtySet(1000);
        Capture.activate(this.dirty);
        this.sync = new SyncService(cfg, this.store, this.dirty, new EmbeddedSolrSource(() -> this.solr.getDefaultServer()),
                new Gates(cfg, Gates.IDLE), this.clock::get, false);
        this.reassign = new CollectionReassign(new CollectionReassign.Index() {
            @Override
            public SolrDocumentList select(final String core, final ModifiableSolrParams params) throws IOException {
                try {
                    return CollectionReassignTest.this.solr.getServer(core).query(params).getResults();
                } catch (final Exception e) {
                    throw new IOException(e);
                }
            }

            @Override
            public void setCollections(final String core, final String id, final long version, final List<String> collections) throws IOException {
                CollectionReassign.atomicSet(CollectionReassignTest.this.solr.getServer(core), id, version, collections);
            }

            @Override
            public void commit(final String core) throws IOException {
                try {
                    CollectionReassignTest.this.solr.getServer(core).commit();
                } catch (final Exception e) {
                    throw new IOException(e);
                }
            }

            @Override
            public boolean has(final String core) {
                return true;
            }
        }, KNOWN::contains);

        page("AAAAAAyowea1", "https://yowea.com/", List.of("stackfinder-web"), ORG);
        page("BBBBBByowea2", "https://www.yowea.com/coaching", List.of("stackfinder-web"), null);
        page("CCCCCCyowea3", "https://blog.yowea.com/eintrag", List.of("stackfinder-web", "partner-web"), null);
        page("DDDDDDyowea4", "https://yowea.com/bau", List.of("bauteamcheck-web"), null);
        page("EEEEEEnoyowe", "https://notyowea.com/", List.of("stackfinder-web"),
                "{\"@type\":\"Organization\",\"name\":\"Notyowea GmbH\",\"url\":\"https://notyowea.com/\"}");
        page("GGGGGGyowede", "https://www.yowea.de/", List.of("stackfinder-web"), null);
        page("FFFFFFotherx", "https://it-firma.de/", List.of("stackfinder-web"),
                "{\"@type\":\"Organization\",\"name\":\"IT Firma GmbH\",\"url\":\"https://it-firma.de/\"}");
        edge("edge-yowea-1", "yowea.com", List.of("stackfinder-web"));
        edge("edge-other-1", "it-firma.de", List.of("stackfinder-web"));
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

    private SolrClient client() {
        return this.solr.getDefaultServer();
    }

    private void page(final String id, final String url, final List<String> collections, final String ldJson) throws Exception {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", url);
        final String host = java.net.URI.create(url).getHost();
        d.setField("host_s", host);
        // as YaCy writes it: the organisation of the host ("yowea" for blog.yowea.com)
        final String[] labels = host.split("\\.");
        d.setField("host_organization_s", labels[labels.length - 2]);
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

    private void edge(final String id, final String host, final List<String> collections) throws Exception {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("source_host_s", host);
        d.setField("collection_sxt", collections);
        this.solr.getServer("webgraph").add(d);
    }

    private void settle() {
        for (int i = 0; i < 2000; i++) {
            final boolean more = this.sync.step();
            if (!more && this.dirty.size() == 0 && count("SELECT count(*) FROM kg_work") == 0L && !this.syncReconcilePending()) return;
            if (!more) this.clock.addAndGet(3000L);
        }
        fail("did not settle: " + this.sync.status());
    }

    private boolean syncReconcilePending() {
        final JSONObject lag = this.sync.status().optJSONObject("lag");
        return lag != null && lag.optBoolean("reconcile_pending");
    }

    private long count(final String sql) {
        try {
            return this.store.read(c -> KgStore.queryLong(c, sql));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> strings(final String sql) throws Exception {
        return this.store.read(c -> {
            final List<String> out = new ArrayList<>();
            try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        });
    }

    private List<String> scopes(final String docId) throws Exception {
        return strings("SELECT k.name FROM kg_doc d JOIN kg_doc_collection dc ON dc.doc_rowid = d.doc_rowid JOIN kg_collection k"
                + " ON k.coll_id = dc.coll_id WHERE d.doc_id = '" + docId + "' ORDER BY 1");
    }

    /** Collections and version of every page and edge, by id. */
    private Map<String, String> snapshot() throws Exception {
        final Map<String, String> out = new TreeMap<>();
        for (final String core : List.of("collection1", "webgraph")) {
            for (final SolrDocument d : this.solr.getServer(core).query(new SolrQuery("*:*").setRows(100).setFields("id", "collection_sxt", "_version_"))
                    .getResults()) {
                out.put(core + ":" + d.getFirstValue("id"), d.getFieldValues("collection_sxt") + "@" + d.getFirstValue("_version_"));
            }
        }
        return out;
    }

    private SolrDocument get(final String id) throws Exception {
        final SolrDocumentList r = client().query(new SolrQuery("id:" + id).setFields("*")).getResults();
        return r.isEmpty() ? null : r.get(0);
    }

    private static JSONObject request(final String domain, final List<String> add, final List<String> remove, final String confirm) {
        final JSONObject b = Json.obj("domain", domain, "add", new JSONArray(add), "remove", new JSONArray(remove));
        if (confirm != null) Json.put(b, "confirm", confirm);
        return b;
    }

    private static final List<String> COACH = List.of("checkthecoach-web");
    private static final List<String> STACK = List.of("stackfinder-web");

    @Test
    public void thePreviewShowsExactlyTheDomainAndChangesNothing() throws Exception {
        final Map<String, String> before = snapshot();
        final JSONObject preview = this.reassign.run(request("yowea.com", COACH, STACK, null), h -> false);
        assertFalse(preview.getBoolean("applied"));
        assertEquals(List.of("blog.yowea.com", "www.yowea.com", "yowea.com"), list(preview.getJSONArray("hosts")));
        assertEquals("the stackfinder pages of the domain; its bauteamcheck page is not touched", 3, preview.getInt("documents"));
        assertEquals(3, preview.getInt("changes"));
        assertEquals(3, preview.getJSONObject("before").getInt("stackfinder-web"));
        assertFalse(preview.getJSONObject("after").has("stackfinder-web"));
        assertEquals(3, preview.getJSONObject("after").getInt("checkthecoach-web"));
        assertEquals(1, preview.getJSONObject("after").getInt("partner-web"));
        assertEquals(List.of("partner-web"), list(preview.getJSONArray("kept")));
        assertEquals(1, preview.getJSONObject("webgraph").getInt("edges"));
        assertEquals(3, preview.getJSONArray("sample").length());
        assertEquals(16, preview.getString("token").length());
        assertEquals("a preview writes nothing", before, snapshot());
    }

    @Test
    public void applyMovesTheDomainKeepsEverythingElseAndTheGraphFollows() throws Exception {
        assertEquals(List.of("stackfinder-web"), scopes("AAAAAAyowea1"));
        final long statements = count("SELECT count(*) FROM kg_statement");
        final List<String> evidence = strings("SELECT stmt_rowid || '/' || doc_rowid FROM kg_evidence ORDER BY 1");
        final Map<String, String> before = snapshot();
        final String token = this.reassign.run(request("yowea.com", COACH, STACK, null), h -> false).getString("token");
        final JSONObject applied = this.reassign.run(request("yowea.com", COACH, STACK, token), h -> false);
        assertTrue(applied.getBoolean("applied"));
        assertEquals(3, applied.getInt("updated"));
        assertEquals(1, applied.getInt("webgraphUpdated"));
        assertEquals(0, applied.getJSONArray("failed").length());

        // the index: only the three pages and the edge changed, and only their collections
        final Map<String, String> after = snapshot();
        for (final String key : before.keySet()) {
            final boolean moved = List.of("collection1:AAAAAAyowea1", "collection1:BBBBBByowea2", "collection1:CCCCCCyowea3", "webgraph:edge-yowea-1")
                    .contains(key);
            assertEquals(key, !moved, before.get(key).equals(after.get(key)));
        }
        assertEquals(List.of("checkthecoach-web"), get("AAAAAAyowea1").getFieldValues("collection_sxt").stream().map(Object::toString).toList());
        assertEquals(List.of("partner-web", "checkthecoach-web"),
                get("CCCCCCyowea3").getFieldValues("collection_sxt").stream().map(Object::toString).toList());
        assertEquals(List.of("bauteamcheck-web"), get("DDDDDDyowea4").getFieldValues("collection_sxt").stream().map(Object::toString).toList());
        // the content stays: text, JSON-LD, title, URL
        final SolrDocument home = get("AAAAAAyowea1");
        assertEquals("Inhalt von https://yowea.com/", home.getFirstValue("text_t"));
        assertEquals(ORG, home.getFirstValue("ld_json_txt"));
        assertEquals("https://yowea.com/", home.getFirstValue("sku"));
        assertNotNull(home.getFirstValue("title"));

        // the knowledge graph takes it over through its capture: re-scoped, not extracted again
        settle();
        assertEquals(List.of("checkthecoach-web"), scopes("AAAAAAyowea1"));
        assertEquals(List.of("checkthecoach-web"), scopes("CCCCCCyowea3"));
        assertEquals(List.of("stackfinder-web"), scopes("EEEEEEnoyowe"));
        assertEquals(List.of("stackfinder-web"), scopes("FFFFFFotherx"));
        assertEquals("yowea.de is another domain of the same name", List.of("stackfinder-web"), scopes("GGGGGGyowede"));
        assertEquals("no fact lost or added", statements, count("SELECT count(*) FROM kg_statement"));
        assertEquals("the same evidence rows: no new extraction", evidence, strings("SELECT stmt_rowid || '/' || doc_rowid FROM kg_evidence ORDER BY 1"));
        assertEquals(List.of("checkthecoach-web"), strings("SELECT k.name FROM kg_entity_scope s JOIN kg_collection k ON k.coll_id = s.coll_id"
                + " JOIN kg_statement st ON st.subj = s.ent_rowid WHERE st.obj_val = 'Yowea GmbH' GROUP BY k.name"));
        assertEquals(List.of("stackfinder-web"), strings("SELECT k.name FROM kg_entity_scope s JOIN kg_collection k ON k.coll_id = s.coll_id"
                + " JOIN kg_statement st ON st.subj = s.ent_rowid WHERE st.obj_val = 'IT Firma GmbH' GROUP BY k.name"));

        // a second run finds nothing more to do
        final JSONObject again = this.reassign.run(request("yowea.com", COACH, STACK, null), h -> false);
        assertEquals(0, again.getInt("documents"));
    }

    @Test
    public void aChangeSinceThePreviewIsNeverOverwritten() throws Exception {
        final String token = this.reassign.run(request("yowea.com", COACH, STACK, null), h -> false).getString("token");
        // a new crawl writes the page again in the meantime
        page("BBBBBByowea2", "https://www.yowea.com/coaching", List.of("stackfinder-web"), null);
        client().commit();
        final Map<String, String> before = snapshot();
        try {
            this.reassign.run(request("yowea.com", COACH, STACK, token), h -> false);
            fail("stale preview");
        } catch (final ApiException e) {
            assertEquals(409, e.status());
            assertEquals("reassign_preview_stale", e.code());
        }
        assertEquals(before, snapshot());
        // a running crawl of the domain blocks the apply, the preview says so
        final JSONObject preview = this.reassign.run(request("yowea.com", COACH, STACK, null), "www.yowea.com"::equals);
        assertTrue(preview.getBoolean("crawlRunning"));
        try {
            this.reassign.run(request("yowea.com", COACH, STACK, preview.getString("token")), "www.yowea.com"::equals);
            fail("crawl running");
        } catch (final ApiException e) {
            assertEquals("host_busy", e.code());
        }
        assertEquals(before, snapshot());
    }

    @Test
    public void anOutdatedVersionIsRefusedAndThePageStays() throws Exception {
        final SolrDocument d = get("AAAAAAyowea1");
        final long version = ((Number) d.getFirstValue("_version_")).longValue();
        try {
            CollectionReassign.atomicSet(client(), "AAAAAAyowea1", version - 1, COACH);
            fail("an old version must be refused");
        } catch (final IOException expected) {
            // refused by Solr: no delete and re-add of a patch
        }
        client().commit();
        final SolrDocument still = get("AAAAAAyowea1");
        assertEquals(List.of("stackfinder-web"), still.getFieldValues("collection_sxt").stream().map(Object::toString).toList());
        assertEquals("Inhalt von https://yowea.com/", still.getFirstValue("text_t"));
    }

    @Test
    public void requestsAreChecked() throws Exception {
        final Map<String, String> before = snapshot();
        final Object[][] bad = {
            {request("www.yowea.com", COACH, STACK, null), "invalid_request"},
            {request("*.yowea.com", COACH, STACK, null), "invalid_request"},
            {request("https://yowea.com/", COACH, STACK, null), "invalid_request"},
            {request("yowea.com", List.of("unbekannt-web"), STACK, null), "collection_unknown"},
            {request("yowea.com", List.of("robot_crawl"), STACK, null), "collection_unknown"},
            {request("yowea.com", STACK, STACK, null), "invalid_request"},
            {request("yowea.com", List.of(), List.of(), null), "invalid_request"},
            {request("yowea.com", List.of(), STACK, null), "reassign_would_empty"},
            {Json.put(request("yowea.com", COACH, STACK, null), "collection", "x"), "invalid_request"}};
        for (final Object[] b : bad) {
            try {
                this.reassign.run((JSONObject) b[0], h -> false);
                fail("accepted: " + b[0]);
            } catch (final ApiException e) {
                assertEquals(b[0].toString(), b[1], e.code());
            }
        }
        // a domain without pages: an empty preview
        assertEquals(0, this.reassign.run(request("leer.de", COACH, STACK, null), h -> false).getInt("documents"));
        assertEquals(before, snapshot());
    }

    private static List<String> list(final JSONArray a) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }
}
