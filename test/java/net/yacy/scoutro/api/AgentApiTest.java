package net.yacy.scoutro.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.agents.Agent;
import net.yacy.scoutro.agents.AgentStore;
import net.yacy.scoutro.agents.AgentTokens;
import net.yacy.scoutro.agents.ScoutroAgents;

/** Authentication, authorization, routing and audit of the agent path (work package 2). */
public class AgentApiTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private ScoutroAgents agents;
    private AgentApi api;
    private final List<String> executed = new ArrayList<>();
    private Agent agent;
    private String token;

    @Before
    public void setUp() throws Exception {
        this.agents = ScoutroAgents.createForTest(this.tmp.newFolder("SETTINGS"), this.now::get);
        this.api = new AgentApi(this.agents, new ScopedActions(null, null) {
            @Override
            AgentApi.Response execute(final String action, final String id, final AgentApi.Call call) {
                AgentApiTest.this.executed.add(action + (id == null ? "" : ":" + id));
                return new AgentApi.Response(200, Json.obj("action", action));
            }
        });
        final Agent.Builder b = new Agent.Builder();
        b.name = "Recherche";
        b.scope = new Agent.Scope(new LinkedHashSet<>(Arrays.asList("edelsenior-web")), false);
        b.actions = new LinkedHashSet<>(Arrays.asList("search", "index.evidence"));
        this.agent = this.agents.store.createAgent(b);
        this.token = this.agents.store.issueToken(this.agent.id, 30 * AgentStore.DAY).plainText();
    }

    private AgentApi.Response call(final String method, final String path, final String authorization,
            final Map<String, String> query, final JSONObject body) {
        final List<String> segs = new ArrayList<>();
        for (final String s : path.split("/")) {
            if (!s.isEmpty()) {
                segs.add(s);
            }
        }
        return this.api.handle(new AgentApi.Request(method, segs, query == null ? new HashMap<>() : query,
                authorization, "10.1.2.3", null, () -> body == null ? new JSONObject() : body));
    }

    private AgentApi.Response get(final String path) {
        return call("GET", path, "Bearer " + this.token, null, null);
    }

    private static String code(final AgentApi.Response r) {
        return r.body.optJSONObject("error").optString("code");
    }

    @Test
    public void seoNeedsAnExplicitGrant() throws Exception {
        Assert.assertEquals(401, call("GET", "seo/hosts", null, null, null).status);
        Assert.assertEquals(403, get("seo/hosts").status);
        final Agent.Builder b = new Agent.Builder(); b.name = "SEO reader";
        b.scope = this.agent.scope; b.actions = new LinkedHashSet<>(Arrays.asList("seo.read"));
        this.agent = this.agents.store.createAgent(b);
        this.token = this.agents.store.issueToken(this.agent.id, 30 * AgentStore.DAY).plainText();
        Assert.assertEquals(200, get("seo/hosts").status);
        Assert.assertEquals(405, call("POST", "seo/hosts", "Bearer " + this.token, null, null).status);
    }

    @Test
    public void missingAndForeignCredentialsAreRejected() {
        AgentApi.Response r = call("GET", "capabilities", null, null, null);
        Assert.assertEquals(401, r.status);
        Assert.assertEquals("missing_bearer", code(r));
        Assert.assertTrue(r.headers.get("WWW-Authenticate").startsWith("Bearer"));

        r = call("GET", "capabilities", "Digest username=\"admin\", realm=\"x\"", null, null);
        Assert.assertEquals(401, r.status);
        Assert.assertEquals("bearer_required", code(r));

        r = call("GET", "capabilities", "Basic YWRtaW46eWFjeQ==", null, null);
        Assert.assertEquals("bearer_required", code(r));

        r = call("GET", "capabilities", "Bearer " + AgentTokens.generate().plainText(), null, null);
        Assert.assertEquals(401, r.status);
        Assert.assertEquals("invalid_token", code(r));
        Assert.assertTrue(this.executed.isEmpty());
    }

    @Test
    public void tokenInUrlIsRefused() {
        final Map<String, String> q = new HashMap<>();
        q.put("access_token", this.token);
        final AgentApi.Response r = call("GET", "capabilities", null, q, null);
        Assert.assertEquals(400, r.status);
        Assert.assertEquals("token_in_url", code(r));
    }

    @Test
    public void capabilitiesListOnlyGrantedActionsAndRecordHandshake() {
        final AgentApi.Response r = get("capabilities");
        Assert.assertEquals(200, r.status);
        final JSONArray actions = r.body.optJSONArray("actions");
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < actions.length(); i++) {
            names.add(actions.optJSONObject(i).optString("name"));
        }
        Assert.assertEquals(Arrays.asList("search", "index.evidence"), names);
        Assert.assertEquals("/scoutro/api/agent/v1/search",
                actions.optJSONObject(0).optJSONObject("http").optString("path"));
        Assert.assertFalse(r.body.optJSONObject("scope").optBoolean("networkSearch"));
        final String fp = r.body.optString("fingerprint");
        Assert.assertEquals(fp, this.agents.store.connection(this.agent.id).optString("fingerprint"));
    }

    @Test
    public void grantedActionIsExecutedOthersAreDenied() {
        Assert.assertEquals(200, get("search").status);
        Assert.assertEquals(Arrays.asList("search"), this.executed);

        final AgentApi.Response denied = get("config");
        Assert.assertEquals(403, denied.status);
        Assert.assertEquals("action_not_granted", code(denied));
        Assert.assertEquals(403, call("POST", "crawls", "Bearer " + this.token, null, new JSONObject()).status);
        Assert.assertEquals(Arrays.asList("search"), this.executed);
    }

    @Test
    public void collectionOutsideScopeIsDenied() {
        final Map<String, String> q = new HashMap<>();
        q.put("collection", "checkthecoach-web");
        final AgentApi.Response r = call("GET", "index/evidence", "Bearer " + this.token, q, null);
        Assert.assertEquals(403, r.status);
        Assert.assertEquals("collection_not_in_scope", code(r));
        q.put("collection", "edelsenior-web");
        Assert.assertEquals(200, call("GET", "index/evidence", "Bearer " + this.token, q, null).status);
    }

    @Test
    public void rightsChangesApplyToTheNextCall() throws Exception {
        Assert.assertEquals(200, get("search").status);
        final Agent.Builder b = this.agent.toBuilder();
        b.actions = new LinkedHashSet<>(Arrays.asList("index.evidence"));
        this.agents.store.updateAgent(this.agent.id, b);
        Assert.assertEquals("action_not_granted", code(get("search")));

        this.agents.store.pause(this.agent.id);
        final AgentApi.Response paused = get("index/evidence");
        Assert.assertEquals(403, paused.status);
        Assert.assertEquals("agent_paused", code(paused));

        this.agents.store.resume(this.agent.id);
        Assert.assertEquals(200, get("index/evidence").status);
        this.agents.store.revoke(this.agent.id);
        Assert.assertEquals(401, get("index/evidence").status);
    }

    @Test
    public void expiredTokenIsRejected() {
        this.now.addAndGet(31 * AgentStore.DAY);
        final AgentApi.Response r = get("capabilities");
        Assert.assertEquals(401, r.status);
        Assert.assertEquals("token_expired", code(r));
    }

    @Test
    public void routing() {
        Assert.assertEquals(404, get("nothing").status);
        Assert.assertEquals(404, get("index/whatever").status);
        final AgentApi.Response wrongMethod = call("DELETE", "search", "Bearer " + this.token, null, null);
        Assert.assertEquals(405, wrongMethod.status);
        Assert.assertEquals("crawl.status", AgentApi.route("GET", Arrays.asList("crawls", "c1")).action);
        Assert.assertEquals("crawl.stop", AgentApi.route("POST", Arrays.asList("crawls", "c1", "stop")).action);
        Assert.assertEquals("config.set", AgentApi.route("PATCH", Arrays.asList("config")).action);
        // parameters that broaden a request select a separate action that needs its own grant
        final Map<String, String> network = new HashMap<>();
        network.put("source", "network");
        final AgentApi.Response r = call("GET", "search", "Bearer " + this.token, network, null);
        Assert.assertEquals(403, r.status);
        Assert.assertEquals("action_not_granted", code(r));
        final Map<String, String> global = new HashMap<>();
        global.put("global", "true");
        Assert.assertEquals("index.status.global",
                AgentApi.refine(AgentApi.route("GET", Arrays.asList("index")), global).action);
    }

    @Test
    public void rateLimitPerToken() throws Exception {
        final Agent.Builder b = this.agent.toBuilder();
        b.limits = new Agent.Limits(new ArrayList<>(), 1, 50, 1, 3, 300, false);
        this.agents.store.updateAgent(this.agent.id, b);
        for (int i = 0; i < 3; i++) {
            Assert.assertEquals(200, get("search").status);
        }
        final AgentApi.Response limited = get("search");
        Assert.assertEquals(429, limited.status);
        Assert.assertEquals("rate_limited", code(limited));
        this.now.addAndGet(60_000);
        Assert.assertEquals(200, get("search").status);
    }

    @Test
    public void bruteForceBrake() {
        for (int i = 0; i < 30; i++) {
            Assert.assertEquals(401, call("GET", "search", "Bearer " + AgentTokens.generate().plainText(), null, null).status);
        }
        // even a valid token is refused while the brake is active for this client
        final AgentApi.Response r = get("search");
        Assert.assertEquals(429, r.status);
        Assert.assertEquals("too_many_failures", code(r));
        this.now.addAndGet(60_000);
        Assert.assertEquals(200, get("search").status);
    }

    @Test
    public void heartbeatIsValidatedAndStored() {
        final JSONObject ok = Json.obj("version", "1.0", "clustroReachable", true, "lastPollAt", 123, "activeRuns", 1);
        Assert.assertEquals(200, call("POST", "heartbeat", "Bearer " + this.token, null, ok).status);
        final JSONObject hb = this.agents.store.connection(this.agent.id).optJSONObject("heartbeat");
        Assert.assertTrue(hb.optBoolean("clustroReachable"));

        final AgentApi.Response unknown = call("POST", "heartbeat", "Bearer " + this.token, null, Json.obj("admin", true));
        Assert.assertEquals(400, unknown.status);
        final AgentApi.Response wrongType = call("POST", "heartbeat", "Bearer " + this.token, null,
                Json.obj("clustroReachable", "yes"));
        Assert.assertEquals(400, wrongType.status);
    }

    @Test
    public void unexpectedErrorsAreAnsweredAndAudited() {
        final AgentApi failing = new AgentApi(this.agents, new ScopedActions(null, null) {
            @Override
            AgentApi.Response execute(final String action, final String id, final AgentApi.Call call) {
                throw new IllegalStateException("boom");
            }
        });
        final AgentApi.Response r = failing.handle(new AgentApi.Request("GET", Arrays.asList("search"), new HashMap<>(),
                "Bearer " + this.token, "10.1.2.3", null, JSONObject::new));
        Assert.assertEquals(500, r.status);
        Assert.assertEquals("internal_error", code(r));
        Assert.assertFalse(r.body.toString().contains("boom"));
        Assert.assertEquals("internal_error", this.agents.audit.recent(null, null, 1).get(0).optString("reason"));
    }

    @Test
    public void auditRecordsDecisionsWithoutSecrets() {
        get("search");
        get("config");
        call("GET", "search", "Bearer " + AgentTokens.generate().plainText(), null, null);
        final List<JSONObject> entries = this.agents.audit.recent(null, null, 10);
        Assert.assertEquals(3, entries.size());
        Assert.assertEquals("deny", entries.get(0).optString("decision"));
        Assert.assertEquals("invalid_token", entries.get(0).optString("reason"));
        Assert.assertEquals("action_not_granted", entries.get(1).optString("reason"));
        Assert.assertEquals("allow", entries.get(2).optString("decision"));
        Assert.assertEquals(this.agent.id, entries.get(2).optString("agentId"));
        final String secret = this.token.substring(this.token.indexOf('.') + 1);
        for (final JSONObject e : entries) {
            Assert.assertFalse(e.toString().contains(secret));
        }
    }
}
