package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
            "BBBBBBhost01", "CCCCCChost02", "impressum"};

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
        final Agent.Builder b = new Agent.Builder();
        b.name = "kg " + (++this.agentNumber);
        b.kind = kind;
        b.scope = new Agent.Scope(all ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.asList("kga")), all);
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
        for (final String admin : new String[] {"kg/status", "kg/control", "kg/export/download", "kg", "kg/entities/x/y/z", "kg/backups",
                "kg/backups/graph-20300101T000000Z.db"}) {
            assertEquals(admin, 404, get(read, admin).status);
            assertEquals(admin, 404, get(export, admin).status);
        }
        final AgentApi.Response post = this.api.handle(new AgentApi.Request("POST", List.of("kg", "entities"), new HashMap<>(),
                "Bearer " + read, "10.1.2.3", null, JSONObject::new));
        assertEquals(405, post.status);
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
