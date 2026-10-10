package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.agents.Agent;
import net.yacy.scoutro.agents.AgentActionRegistry;
import net.yacy.scoutro.agents.AgentException;
import net.yacy.scoutro.agents.AgentStore;
import net.yacy.scoutro.agents.ScoutroAgents;
import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The agent routes of the knowledge graph against a real graph
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 8.2 and 8.3): kg.read and kg.export are
 * separate scoped grants, the server sets the viewer from the agent's scope,
 * and an agent of collection A gets nothing that only B's documents carry, in
 * the read routes, the export or the change feed.
 */
public class AgentKnowledgeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private KgRuntime runtime;
    private ScoutroAgents agents;
    private AgentApi api;
    private KgStore store;
    private Publisher publisher;
    private long version = 1000L;
    private int agentNumber;

    private static final String ORG_A = "{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\",\"url\":\"https://www.muster.de/\","
            + "\"telephone\":\"030 1234567\"}";
    private static final String ORG_B = "{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\",\"url\":\"https://www.muster.de/\","
            + "\"telephone\":\"040 9999999\",\"alternateName\":\"Geheime Holding\",\"vatID\":\"DE123456789\"}";
    private static final String ONLY_B = "{\"@type\":\"Organization\",\"name\":\"Nur Bee GmbH\",\"url\":\"https://www.nur-bee.de/\","
            + "\"telephone\":\"089 7777777\"}";
    /** Values only B's documents carry. */
    private static final String[] ONLY_IN_B = {"Geheime Holding", "DE123456789", "+49409999999", "Nur Bee", "nur-bee", "\"kgb\"",
            "BBBBBBhost01", "CCCCCChost02", "impressum", "EEEEEEhost04"};

    @Before
    public void setUp() throws Exception {
        this.runtime = new KgRuntime(KgTestSupport.env(this.tmp.newFolder("DATA"), KgTestSupport.enabled(), new KgTestSupport.Probe()));
        this.runtime.open();
        assertEquals(KgRuntime.State.RUNNING, this.runtime.state());
        final KgStore store = KgTestSupport.store(this.runtime);
        final Terms terms = new Terms();
        store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        final Publisher publisher = new Publisher(KgTestSupport.config(KgTestSupport.enabled()), terms);
        publish(store, publisher, "AAAAAAhost01", "https://www.muster.de/", "kga", ORG_A);
        publish(store, publisher, "BBBBBBhost01", "https://www.muster.de/impressum", "kgb", ORG_B);
        publish(store, publisher, "CCCCCChost02", "https://www.nur-bee.de/", "kgb", ONLY_B);
        this.store = store;
        this.publisher = publisher;
        this.agents = ScoutroAgents.createForTest(this.tmp.newFolder("SETTINGS"), this.now::get);
        this.api = new AgentApi(this.agents, new ScopedActions(null, null, () -> this.runtime));
    }

    @After
    public void tearDown() {
        this.runtime.close();
    }

    private void publish(final KgStore store, final Publisher publisher, final String id, final String url, final String collection,
            final String block) throws Exception {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = net.yacy.cora.document.id.DigestURL.hosthash(d.host, 443);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of(collection);
        d.solrVersion = ++this.version;
        d.loadedAt = this.now.get();
        final Extraction ex = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of(block), d.url, d.host, d.language, ex);
        final Publisher.Row cur = store.read(c -> Publisher.row(c, id));
        store.write(WriteClass.GROWTH, 0, tx -> publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now.get()));
    }

    private String token(final Agent.Kind kind, final boolean all, final String... actions) throws Exception {
        return token(kind, all, List.of("kga"), actions);
    }

    private String token(final Agent.Kind kind, final boolean all, final List<String> collections, final String... actions) throws Exception {
        final Agent.Builder b = new Agent.Builder();
        b.name = "kg " + (++this.agentNumber);
        b.kind = kind;
        b.scope = new Agent.Scope(all ? new LinkedHashSet<>() : new LinkedHashSet<>(collections), all);
        b.actions = new LinkedHashSet<>(Arrays.asList(actions));
        final Agent a = this.agents.store.createAgent(b);
        return this.agents.store.issueToken(a.id, 30 * AgentStore.DAY).plainText();
    }

    private AgentApi.Response get(final String token, final String path, final String... query) {
        final List<String> segs = new ArrayList<>();
        for (final String s : path.split("/")) {
            if (!s.isEmpty()) {
                segs.add(s);
            }
        }
        final Map<String, String> q = new HashMap<>();
        for (int i = 0; i + 1 < query.length; i += 2) {
            q.put(query[i], query[i + 1]);
        }
        return this.api.handle(new AgentApi.Request("GET", segs, q, "Bearer " + token, "10.1.2.3", null, JSONObject::new));
    }

    private static String code(final AgentApi.Response r) {
        return r.body.optJSONObject("error") == null ? null : r.body.optJSONObject("error").optString("code");
    }

    private String id(final String token, final String scope, final String name) throws Exception {
        final AgentApi.Response r = get(token, "kg/entities", "q", name);
        assertEquals(r.body.toString(), 200, r.status);
        return r.body.getJSONArray("items").getJSONObject(0).getString("id");
    }

    private static void assertNothingOfB(final String where, final String text) {
        for (final String secret : ONLY_IN_B) {
            assertFalse(where + " reveals " + secret + ": " + text, text.contains(secret));
        }
    }

    /** Stored derivations are fixtures of the read contract, not new matching rules. */
    private void suggestion(final String suffix, final int kind, final String origin, final String candidate,
            final String ca, final String cb, final double score, final String extraBasis) throws Exception {
        this.store.write(WriteClass.SYSTEM, 0, c -> {
            final JSONObject basis = new JSONObject();
            for (final String side : List.of("a", "b")) {
                try (PreparedStatement ps = c.prepareStatement("SELECT s.public_id FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                        + " JOIN kg_entity e ON e.ent_rowid = s.subj WHERE e.public_id = ? AND v.name = 'name' LIMIT 1")) {
                    ps.setString(1, side.equals("a") ? origin : candidate);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        final JSONArray refs = new JSONArray();
                        if (side.equals("b") && extraBasis != null) refs.put(extraBasis);
                        refs.put(rs.getString(1));
                        Json.put(basis, side, refs);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kg_derived(public_id,kind,a_ent,b_ent,coll_a,coll_b,confidence,reason,basis,computed_at)"
                    + " SELECT ?,?,a.ent_rowid,b.ent_rowid,ca.coll_id,cb.coll_id,?,?,?,? FROM kg_entity a,kg_entity b,kg_collection ca,kg_collection cb"
                    + " WHERE a.public_id=? AND b.public_id=? AND ca.name=? AND cb.name=?")) {
                ps.setString(1, "kgd_" + suffix.repeat(20).substring(0, 20)); ps.setInt(2, kind); ps.setDouble(3, score);
                ps.setString(4, "Fixture: matching declared audience and service."); ps.setString(5, basis.toString()); ps.setLong(6, this.now.get());
                ps.setString(7, origin); ps.setString(8, candidate); ps.setString(9, ca); ps.setString(10, cb);
                assertEquals(1, ps.executeUpdate());
            }
            return null;
        });
    }

    @Test
    public void aFilteredUserAndAgentSeeOnlyAuthorizedCustomerAndPartnerSuggestions() throws Exception {
        final String all = token(Agent.Kind.EXTERNAL, true, "kg.read", "kg.export");
        final String origin = id(all, null, "Muster Pflege"), candidate = id(all, null, "Nur Bee");
        suggestion("a", 3, origin, candidate, "kga", "kgb", 0.6, null);
        suggestion("b", 4, candidate, origin, "kgb", "kga", 0.45, null); // symmetric partner, origin is side B
        final String both = token(Agent.Kind.EXTERNAL, false, List.of("kga", "kgb"), "kg.read");
        final AgentApi.Response page = get(both, "kg/entities/" + origin + "/suggestions", "collection", "kga", "limit", "1");
        assertEquals(page.body.toString(), 200, page.status);
        assertEquals(2, page.body.getInt("total"));
        assertEquals(1, page.body.getInt("next_offset"));
        assertEquals("suggested_customer", page.body.getJSONArray("items").getJSONObject(0).getString("kind"));
        final JSONObject entry = page.body.getJSONArray("items").getJSONObject(0);
        final JSONArray refs = entry.getJSONArray("contributions").getJSONObject(0).getJSONArray("evidence");
        final String candidateStatement = refs.getJSONObject(refs.length() - 1).getString("id");
        final AgentApi.Response proof = get(both, "kg/statements/" + candidateStatement + "/evidence", "collection", entry.getString("target_collection"));
        assertEquals(proof.body.toString(), 200, proof.status);
        assertTrue(proof.body.getJSONArray("items").length() > 0);
        assertEquals("[\"kgb\"]", proof.body.getJSONArray("items").getJSONObject(0).getJSONArray("collections").toString());
        assertEquals("suggested_partner", get(both, "kg/entities/" + origin + "/suggestions", "collection", "kga", "offset", "1")
                .body.getJSONArray("items").getJSONObject(0).getString("kind"));
        final String onlyA = token(Agent.Kind.EXTERNAL, false, "kg.read", "kg.export");
        assertEquals(403, get(onlyA, "kg/statements/" + candidateStatement + "/evidence", "collection", "kgb").status);
        assertEquals(404, get(onlyA, "kg/statements/" + candidateStatement + "/evidence", "collection", "kga").status);
        assertEquals(400, get(both, "kg/entities/" + origin + "/suggestions", "permitted_collections", "kgsecret").status);
        for (final String route : List.of("suggestions", "business", "neighborhood")) {
            final AgentApi.Response narrow = get(onlyA, "kg/entities/" + origin + "/" + route, "collection", "kga",
                    route.equals("neighborhood") ? "suggested" : "collection", route.equals("neighborhood") ? "true" : "kga");
            assertEquals(narrow.body.toString(), 200, narrow.status);
            assertNothingOfB(route, narrow.body.toString());
        }
        final JSONObject business = get(both, "kg/entities/" + origin + "/business", "collection", "kga").body;
        assertEquals(2, business.getJSONObject("suggestions").getInt("total"));
        assertFalse(business.getJSONObject("overview").toString().contains("Geheime Holding"));
        final JSONObject admin = new KnowledgeApi(() -> this.runtime).route("GET", ("/v1/kg/entities/" + origin + "/business").split("/"),
                Map.of("collection", "kga"), JSONObject::new);
        assertEquals(2, admin.getJSONObject("suggestions").getInt("total"));
        assertFalse(admin.getJSONObject("overview").toString().contains("Geheime Holding"));
        final JSONObject graph = get(both, "kg/entities/" + origin + "/neighborhood", "collection", "kga", "suggested", "true").body;
        assertTrue(graph.toString(), graph.toString().contains(candidate));
        assertFalse(graph.toString(), graph.toString().contains("Geheime Holding"));
        assertEquals(403, get(onlyA, "kg/entities/" + candidate, "collection", "kgb").status);
        assertEquals(404, get(onlyA, "kg/entities/" + candidate, "collection", "kga").status);
        assertEquals(403, get(both, "kg/export").status); // export still requires its own grant
        assertNothingOfB("strict export", get(all, "kg/export", "collection", "kga", "include", "evidence").body.toString());
    }

    @Test
    public void collectionPairsAreGroupedBeforePagingAndHiddenBasisDoesNotEscape() throws Exception {
        final String all = token(Agent.Kind.EXTERNAL, true, "kg.read");
        final String origin = id(all, null, "Muster Pflege"), candidate = id(all, null, "Nur Bee");
        publish(this.store, this.publisher, "FFFFFFhost02", "https://www.nur-bee.de/a", "kga", ONLY_B);
        publish(this.store, this.publisher, "GGGGGGhost02", "https://www.nur-bee.de/secret", "kgsecret", ONLY_B);
        suggestion("a", 3, origin, candidate, "kga", "kga", 0.5, null);
        suggestion("b", 3, origin, candidate, "kga", "kgb", 0.6, null);
        suggestion("c", 3, origin, candidate, "kga", "kgsecret", 0.99, null);
        final String both = token(Agent.Kind.EXTERNAL, false, List.of("kga", "kgb"), "kg.read");
        final JSONObject page = get(both, "kg/entities/" + origin + "/suggestions", "collection", "kga", "limit", "1").body;
        assertEquals(1, page.getInt("total"));
        assertTrue(page.isNull("next_offset"));
        final JSONObject item = page.getJSONArray("items").getJSONObject(0);
        assertEquals(2, item.getJSONArray("contributions").length());
        assertEquals(0.6, item.getDouble("score"), 0.001); // hidden strongest row cannot affect sorting
        assertEquals("kgb", item.getString("target_collection"));
        assertEquals("[\"kga\",\"kgb\"]", item.getJSONObject("other").getJSONArray("collections").toString());
        assertFalse(page.toString(), page.toString().contains("kgsecret"));
        final JSONObject network = get(both, "kg/entities/" + origin + "/neighborhood", "collection", "kga", "suggested", "true",
                "types", "suggested_customer", "depth", "2").body;
        assertEquals(network.toString(), 1, network.getJSONArray("edges").length()); // reverse traversal must not duplicate a group
        assertEquals(1, network.getInt("neighbours"));
        final JSONObject empty = get(both, "kg/entities/" + origin + "/suggestions", "collection", "kga", "offset", "1").body;
        assertEquals(1, empty.getInt("total")); assertEquals(0, empty.getJSONArray("items").length());
        final String vat = this.store.read(c -> KgStore.queryString(c, "SELECT s.public_id FROM kg_statement s JOIN kg_vocab v ON v.term_id=s.pred WHERE v.name='identifier:vat'"));
        assertTrue(vat != null);
        this.store.write(WriteClass.SYSTEM, 0, c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kg_derived SET basis=? WHERE public_id=?")) {
                ps.setString(1, Json.obj("a", new JSONArray().put(vat), "b", new JSONArray().put(vat)).toString());
                ps.setString(2, "kgd_" + "a".repeat(20)); ps.executeUpdate();
            }
            return null;
        });
        final JSONObject safe = get(both, "kg/entities/" + origin + "/suggestions", "collection", "kga").body;
        assertFalse(safe.toString(), safe.toString().contains(vat));
        assertFalse(safe.toString(), safe.toString().contains("DE123456789"));
        assertFalse(safe.getJSONArray("items").getJSONObject(0).getJSONArray("contributions").getJSONObject(1).getBoolean("evidence_complete"));
    }

    @Test
    public void customerDirectionAndOriginContributionStayDistinct() throws Exception {
        final String all = token(Agent.Kind.EXTERNAL, true, "kg.read");
        final String origin = id(all, null, "Muster Pflege"), candidate = id(all, null, "Nur Bee");
        suggestion("a", 3, origin, candidate, "kga", "kgb", 0.6, null);
        suggestion("b", 3, candidate, origin, "kgb", "kga", 0.5, null);
        suggestion("c", 4, origin, candidate, "kgb", "kgb", 0.45, null); // origin fact from B cannot enter origin A's view
        final String both = token(Agent.Kind.EXTERNAL, false, List.of("kga", "kgb"), "kg.read");
        final JSONObject page = get(both, "kg/entities/" + origin + "/suggestions", "collection", "kga").body;
        assertEquals(2, page.getInt("total"));
        assertEquals("out", page.getJSONArray("items").getJSONObject(0).getString("direction"));
        assertEquals("in", page.getJSONArray("items").getJSONObject(1).getString("direction"));
        final JSONObject graph = get(both, "kg/entities/" + origin + "/neighborhood", "collection", "kga", "types", "suggested_customer", "suggested", "true").body;
        assertEquals(1, graph.getInt("neighbours"));
        assertEquals(2, graph.getJSONArray("edges").length());
        assertNotEquals(graph.getJSONArray("edges").getJSONObject(0).getString("id"), graph.getJSONArray("edges").getJSONObject(1).getString("id"));
    }

    @Test
    public void pagesReachBeyondTheFormerBusinessAndGraphRowCaps() throws Exception {
        final String all = token(Agent.Kind.EXTERNAL, true, "kg.read");
        final String origin = id(all, null, "Muster Pflege");
        final String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        for (int i = 0; i < 205; i++) {
            final String name = "Candidate " + i, url = "https://candidate-" + i + ".example/";
            publish(this.store, this.publisher, String.format(java.util.Locale.ROOT, "S%05dhost03", i), url, "kgb",
                    Json.obj("@type", "Organization", "name", name, "url", url).toString());
            final String candidate = this.store.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT e.public_id FROM kg_entity e JOIN kg_statement s ON s.subj=e.ent_rowid"
                        + " JOIN kg_vocab v ON v.term_id=s.pred WHERE v.name='name' AND s.obj_val=?")) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getString(1); }
                }
            });
            final String suffix = "" + alphabet.charAt(i / 32) + alphabet.charAt(i % 32);
            suggestion(suffix, 3, origin, candidate, "kga", "kgb", 0.6, null);
        }
        final JSONObject business = get(all, "kg/entities/" + origin + "/business", "collection", "kga").body;
        assertEquals(205, business.getJSONObject("suggestions").getInt("total"));
        assertEquals(100, business.getJSONObject("suggestions").getJSONArray("items").length());
        assertEquals(100, business.getJSONObject("suggestions").getInt("next_offset"));
        final JSONObject last = get(all, "kg/entities/" + origin + "/suggestions", "collection", "kga", "offset", "200", "limit", "100").body;
        assertEquals(205, last.getInt("total")); assertEquals(5, last.getJSONArray("items").length()); assertTrue(last.isNull("next_offset"));
        final JSONObject firstGraph = get(all, "kg/entities/" + origin + "/neighborhood", "collection", "kga", "types", "suggested_customer", "suggested", "true").body;
        assertEquals(205, firstGraph.getInt("neighbours")); assertEquals(50, firstGraph.getInt("next_offset"));
        final JSONObject lastGraph = get(all, "kg/entities/" + origin + "/neighborhood", "collection", "kga", "types", "suggested_customer", "suggested", "true", "offset", "200").body;
        assertEquals(205, lastGraph.getInt("neighbours")); assertEquals(5, lastGraph.getJSONArray("edges").length()); assertTrue(lastGraph.isNull("next_offset"));
    }

    @Test
    public void suggestionEvidenceFollowsVisibleRedirectsWithoutDuplicates() throws Exception {
        final String all = token(Agent.Kind.EXTERNAL, true, "kg.read");
        final String origin = id(all, null, "Muster Pflege"), candidate = id(all, null, "Nur Bee");
        final String old = "kgs_" + "z".repeat(20);
        this.store.write(WriteClass.SYSTEM, 0, c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kg_statement_redirect(public_id,target_rowid)"
                    + " SELECT ?,s.stmt_rowid FROM kg_statement s JOIN kg_vocab v ON v.term_id=s.pred JOIN kg_entity e ON e.ent_rowid=s.subj"
                    + " WHERE e.public_id=? AND v.name='name'")) {
                ps.setString(1, old); ps.setString(2, candidate); assertEquals(1, ps.executeUpdate());
            }
            return null;
        });
        suggestion("a", 3, origin, candidate, "kga", "kgb", 0.6, old);
        final JSONObject item = get(all, "kg/entities/" + origin + "/suggestions", "collection", "kga").body.getJSONArray("items").getJSONObject(0);
        final JSONObject contribution = item.getJSONArray("contributions").getJSONObject(0);
        assertTrue(contribution.getBoolean("evidence_complete"));
        assertEquals(2, contribution.getJSONArray("evidence").length());
        assertFalse(contribution.toString(), contribution.toString().contains(old));
        final String canonical = contribution.getJSONArray("evidence").getJSONObject(1).getString("id");
        assertEquals(200, get(all, "kg/statements/" + canonical + "/evidence", "collection", "kgb").status);
    }

    @Test
    public void readAndExportAreSeparateGrantsForTheirOwnPaths() throws Exception {
        final String search = token(Agent.Kind.EXTERNAL, false, "search");
        final String read = token(Agent.Kind.EXTERNAL, false, "kg.read");
        final String export = token(Agent.Kind.EXTERNAL, false, "kg.export");
        assertEquals("action_not_granted", code(get(search, "kg/entities")));
        assertEquals(200, get(read, "kg/entities").status);
        assertEquals("action_not_granted", code(get(read, "kg/export")));
        assertEquals("action_not_granted", code(get(read, "kg/changes")));
        assertEquals(200, get(export, "kg/export").status);
        assertEquals(200, get(export, "kg/changes").status);
        assertEquals("action_not_granted", code(get(export, "kg/entities")));
        for (final String admin : new String[] {"kg/status", "kg/control", "kg/prompt", "kg/export/download", "kg", "kg/entities/x/y/z", "kg/backups",
                "kg/backups/graph-20300101T000000Z.db"}) {
            assertEquals(admin, 404, get(read, admin).status);
            assertEquals(admin, 404, get(export, admin).status);
        }
        final AgentApi.Response post = this.api.handle(new AgentApi.Request("POST", List.of("kg", "entities"), new HashMap<>(),
                "Bearer " + read, "10.1.2.3", null, JSONObject::new));
        assertEquals(405, post.status);
        // the knowledge prompt is never changed with an agent token, whatever the grant
        for (final String token : new String[] {read, export}) {
            final AgentApi.Response prompt = this.api.handle(new AgentApi.Request("POST", List.of("kg", "prompt"), new HashMap<>(),
                    "Bearer " + token, "10.1.2.3", null, JSONObject::new));
            assertEquals(404, prompt.status);
        }
        // neither grant is part of a preset; the export is not for research workers
        for (final String preset : AgentActionRegistry.presetNames()) {
            assertFalse(AgentActionRegistry.preset(preset).contains("kg.read"));
            assertFalse(AgentActionRegistry.preset(preset).contains("kg.export"));
        }
        assertEquals(200, get(token(Agent.Kind.RESEARCH_WORKER, false, "kg.read"), "kg/entities").status);
        try {
            token(Agent.Kind.RESEARCH_WORKER, false, "kg.export");
            throw new AssertionError("kg.export granted to a research worker");
        } catch (final AgentException e) {
            assertEquals(400, e.status());
        }
    }

    @Test
    public void anAgentOfOneCollectionReadsNothingOfAnother() throws Exception {
        final String a = token(Agent.Kind.EXTERNAL, false, "kg.read", "kg.export");
        assertEquals("collection_not_in_scope", code(get(a, "kg/entities", "collection", "kgb")));
        assertEquals("collection_not_in_scope", code(get(a, "kg/export", "collection", "kgb")));
        assertEquals("collection_not_in_scope", code(get(a, "kg/changes", "collection", "kgb")));
        // the routes of vocabulary 2 refuse the other collection as well
        assertEquals("collection_not_in_scope", code(get(a, "kg/compare", "category", "care/tagespflege", "collection", "kgb")));
        assertEquals("collection_not_in_scope", code(get(a, "kg/derived", "collection", "kgb")));
        assertEquals("collection_not_in_scope", code(get(a, "kg/facets", "collection", "kgb")));
        final AgentApi.Response list = get(a, "kg/entities");
        assertEquals(1, list.body.getLong("total"));
        assertFalse("no graph-wide backlog for agents", list.body.has("lag"));
        final String muster = id(a, "kga", "Muster");
        final StringBuilder seen = new StringBuilder(list.body.toString());
        for (final String path : new String[] {"kg/entities/" + muster, "kg/entities/" + muster + "/statements",
                "kg/entities/" + muster + "/statements?include=stale", "kg/hosts/www.muster.de/entities", "kg/sources/AAAAAAhost01",
                // vocabulary 2 (package 6): the business view, the network, the comparison, the derived rows, the facets
                "kg/entities/" + muster + "/business", "kg/entities/" + muster + "/neighborhood", "kg/derived", "kg/facets"}) {
            final String[] pq = path.split("\\?");
            final AgentApi.Response r = pq.length == 1 ? get(a, pq[0]) : get(a, pq[0], "include", "stale");
            assertEquals(path + " " + r.body, 200, r.status);
            seen.append(r.body);
        }
        final AgentApi.Response net = get(a, "kg/entities/" + muster + "/neighborhood", "depth", "2", "weak", "true", "suggested", "true");
        assertEquals(200, net.status);
        seen.append(net.body);
        final AgentApi.Response compare = get(a, "kg/compare", "category", "care/tagespflege");
        assertEquals(200, compare.status);
        seen.append(compare.body);
        assertEquals("a read route is no export", 403, get(token(Agent.Kind.EXTERNAL, false, "kg.export"), "kg/facets").status);
        final JSONArray statements = get(a, "kg/entities/" + muster + "/statements").body.getJSONArray("items");
        for (int i = 0; i < statements.length(); i++) {
            final AgentApi.Response ev = get(a, "kg/statements/" + statements.getJSONObject(i).getString("id") + "/evidence");
            assertEquals(200, ev.status);
            seen.append(ev.body);
        }
        // B's objects and sources do not exist for A
        final String all = token(Agent.Kind.EXTERNAL, true, "kg.read", "kg.export");
        final String bee = id(all, null, "Nur");
        assertEquals(404, get(a, "kg/entities/" + bee).status);
        assertEquals(404, get(a, "kg/entities/" + bee + "/business").status);
        assertEquals(404, get(a, "kg/entities/" + bee + "/neighborhood").status);
        assertEquals("collection_not_in_scope", code(get(a, "kg/entities/" + muster + "/business", "collection", "kgb")));
        assertEquals("collection_not_in_scope", code(get(a, "kg/entities/" + muster + "/neighborhood", "collection", "kgb")));
        assertEquals(404, get(a, "kg/derived", "entity", bee).status);
        assertEquals(404, get(a, "kg/sources/BBBBBBhost01").status);
        assertEquals(404, get(a, "kg/sources/CCCCCChost02").status);
        assertEquals(0, get(a, "kg/hosts/www.nur-bee.de/entities").body.getLong("total"));
        assertEquals(0, get(a, "kg/entities", "q", "Geheime").body.getLong("total"));
        // export and change feed
        String cursor = null;
        do {
            final AgentApi.Response page = cursor == null ? get(a, "kg/export", "include", "evidence", "limit", "1")
                    : get(a, "kg/export", "include", "evidence", "limit", "1", "cursor", cursor);
            assertEquals(page.body.toString(), 200, page.status);
            seen.append(page.body);
            cursor = page.body.optBoolean("complete") ? null : page.body.getString("next");
        } while (cursor != null);
        final AgentApi.Response changes = get(a, "kg/changes", "expand", "true", "limit", "100");
        assertEquals(changes.body.toString(), 200, changes.status);
        assertTrue(changes.body.getJSONArray("items").length() > 0);
        seen.append(changes.body);
        assertNothingOfB("agent of kga", seen.toString());
        assertTrue(seen.toString().contains("+49301234567"));
        // the administrator-equivalent scope sees B
        assertTrue(get(all, "kg/entities/" + muster).body.toString().contains("Geheime Holding"));
    }

    /** Services of the same name (package 6.1): an agent sees the providers of its own collections only, in the list and the groups. */
    @Test
    public void anAgentSeesOnlyTheProvidersOfItsCollections() throws Exception {
        final String offer = "\"makesOffer\":{\"@type\":\"Offer\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"SAP\"}}";
        publish(this.store, this.publisher, "DDDDDDhost03", "https://www.alpha-it.de/", "kga",
                "{\"@type\":\"Organization\",\"name\":\"Alpha IT GmbH\",\"url\":\"https://www.alpha-it.de/\"," + offer + "}");
        publish(this.store, this.publisher, "EEEEEEhost04", "https://www.nur-bee.de/sap", "kgb",
                "{\"@type\":\"Organization\",\"name\":\"Nur Bee GmbH\",\"url\":\"https://www.nur-bee.de/\"," + offer + "}");
        final String a = token(Agent.Kind.EXTERNAL, false, "kg.read");
        final StringBuilder seen = new StringBuilder();
        final AgentApi.Response hits = get(a, "kg/entities", "q", "SAP", "type", "service");
        assertEquals(hits.body.toString(), 1, hits.body.getLong("total"));
        final JSONObject ctx = hits.body.getJSONArray("items").getJSONObject(0).getJSONObject("context");
        assertEquals("Alpha IT GmbH", ctx.getJSONArray("providers").getJSONObject(0).getString("name"));
        assertEquals("www.alpha-it.de", ctx.getJSONArray("providers").getJSONObject(0).getJSONArray("hosts").getString(0));
        seen.append(hits.body);
        final AgentApi.Response groups = get(a, "kg/services", "q", "SAP");
        assertEquals(groups.body.toString(), 200, groups.status);
        assertEquals(1, groups.body.getJSONArray("items").getJSONObject(0).getInt("providers"));
        seen.append(groups.body);
        final AgentApi.Response rows = get(a, "kg/services/providers", "name", "SAP");
        assertEquals(rows.body.toString(), 200, rows.status);
        assertEquals(1, rows.body.getInt("total"));
        seen.append(rows.body);
        final String sap = hits.body.getJSONArray("items").getJSONObject(0).getString("id");
        final AgentApi.Response net = get(a, "kg/entities/" + sap + "/neighborhood", "depth", "2", "prices", "true");
        assertEquals(net.body.toString(), 200, net.status);
        seen.append(net.body);
        // package 6.2: the network of the name has the agent's provider only, its line naming its own service
        final AgentApi.Response group = get(a, "kg/services/network", "name", "SAP");
        assertEquals(group.body.toString(), 200, group.status);
        assertEquals(1, group.body.getInt("neighbours"));
        assertEquals(1, group.body.getJSONArray("edges").length());
        assertEquals(sap, group.body.getJSONArray("edges").getJSONObject(0).getJSONObject("service").getString("id"));
        seen.append(group.body);
        assertNothingOfB("services of agent kga", seen.toString());
        assertEquals("collection_not_in_scope", code(get(a, "kg/services", "collection", "kgb")));
        assertEquals("collection_not_in_scope", code(get(a, "kg/services/providers", "name", "SAP", "collection", "kgb")));
        assertEquals("collection_not_in_scope", code(get(a, "kg/services/network", "name", "SAP", "collection", "kgb")));
        // the administrator-equivalent scope sees both providers, still as two services
        final String all = token(Agent.Kind.EXTERNAL, true, "kg.read");
        final JSONObject both = get(all, "kg/services", "q", "SAP").body.getJSONArray("items").getJSONObject(0);
        assertEquals(2, both.getInt("services"));
        assertEquals(2, both.getInt("providers"));
        final JSONObject bothNet = get(all, "kg/services/network", "name", "SAP").body;
        assertEquals(2, bothNet.getInt("neighbours"));
        assertNotEquals(bothNet.getJSONArray("edges").getJSONObject(0).getJSONObject("service").getString("id"),
                bothNet.getJSONArray("edges").getJSONObject(1).getJSONObject("service").getString("id"));
        assertEquals("a read route needs kg.read", 403, get(token(Agent.Kind.EXTERNAL, false, "kg.export"), "kg/services/network",
                "name", "SAP").status);
        assertEquals("a read route needs kg.read", 403, get(token(Agent.Kind.EXTERNAL, false, "kg.export"), "kg/services").status);
    }

    @Test
    public void expiredAndForeignCursorsPointToTheAgentExport() throws Exception {
        final String a = token(Agent.Kind.EXTERNAL, false, "kg.export");
        final AgentApi.Response r = get(a, "kg/changes", "cursor", "0123456789abcdef:1");
        assertEquals(410, r.status);
        assertEquals("epoch_changed", code(r));
        assertEquals("/scoutro/api/agent/v1/kg/export", r.body.getJSONObject("error").getJSONObject("details").getString("full_sync"));
        assertEquals(400, get(a, "kg/export", "cursor", "nonsense").status);
        assertEquals(400, get(a, "kg/export", "unknown", "1").status);
    }
}
