package net.yacy.scoutro.api;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.agents.Agent;
import net.yacy.scoutro.agents.AgentException;
import net.yacy.scoutro.agents.AgentStore;
import net.yacy.scoutro.agents.ScoutroAgents;

/** Wizard and management logic of "Agents & Access" (work package 4). */
public class AgentAdminTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private ScoutroAgents agents;

    @Before
    public void setUp() throws Exception {
        this.agents = ScoutroAgents.createForTest(this.tmp.newFolder("SETTINGS"), this.now::get);
    }

    private static Map<String, String> form(final String... kv) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void scopeFromCheckboxesAndExtraField() throws Exception {
        final Agent.Builder b = AgentAdmin.parseGrant(new Agent.Builder(), form("scopeForm", "1",
                "col_edelsenior-web", "on", "col_checkthecoach-web", "", "extraCollections", "prospect-edelsenior, x_y"));
        Assert.assertEquals(new LinkedHashSet<>(Arrays.asList("edelsenior-web", "prospect-edelsenior", "x_y")),
                b.scope.collections);
        Assert.assertFalse(b.scope.allCollections);
    }

    @Test
    public void completeIndexNeedsExplicitConfirmation() throws Exception {
        try {
            AgentAdmin.parseGrant(new Agent.Builder(), form("scopeForm", "1", "allCollections", "on"));
            Assert.fail("the complete index needs a confirmation");
        } catch (final AgentException e) {
            Assert.assertEquals("allCollections", e.field());
        }
        final Agent.Builder b = AgentAdmin.parseGrant(new Agent.Builder(), form("scopeForm", "1",
                "allCollections", "on", "confirmAllCollections", "on", "col_a", "on"));
        Assert.assertTrue(b.scope.allCollections);
        Assert.assertTrue(b.scope.collections.isEmpty());

        // editing an agent that already has the complete index needs no new confirmation
        final Agent.Builder existing = new Agent.Builder();
        existing.scope = new Agent.Scope(new LinkedHashSet<>(), true);
        Assert.assertTrue(AgentAdmin.parseGrant(existing, form("scopeForm", "1", "allCollections", "on")).scope.allCollections);
        // ... but granting it anew does
        final Agent.Builder narrow = new Agent.Builder();
        narrow.scope = new Agent.Scope(Set.of("a"), false);
        try {
            AgentAdmin.parseGrant(narrow, form("scopeForm", "1", "allCollections", "on"));
            Assert.fail("extending a scope to the complete index needs a confirmation");
        } catch (final AgentException e) {
            Assert.assertEquals("allCollections", e.field());
        }
    }

    @Test
    public void presetsResolveToSnapshotsAndOnlyAddUnscopedExtras() throws Exception {
        final Agent.Builder b = AgentAdmin.parseGrant(new Agent.Builder(), form("actionsForm", "1",
                "preset", "research", "act_crawl.start", "on", "act_system.status", "on"));
        // with a preset, a presetable checkbox (crawl.start) is ignored, an unscoped one is added explicitly
        Assert.assertEquals(new LinkedHashSet<>(Arrays.asList("search", "index.evidence", "index.lookup",
                "index.status", "system.status")), b.actions);
        Assert.assertEquals("custom", b.presetLabel);

        final Agent.Builder plain = AgentAdmin.parseGrant(new Agent.Builder(), form("actionsForm", "1", "preset", "research"));
        Assert.assertEquals("research", plain.presetLabel);

        final Agent.Builder custom = AgentAdmin.parseGrant(new Agent.Builder(), form("actionsForm", "1",
                "preset", "custom", "act_search", "on"));
        Assert.assertEquals(Set.of("search"), custom.actions);
    }

    @Test
    public void stepValidation() throws Exception {
        final Agent.Builder b = new Agent.Builder();
        b.name = "";
        try {
            AgentAdmin.validateDraft(b, 1);
            Assert.fail();
        } catch (final AgentException e) {
            Assert.assertEquals("name", e.field());
        }
        b.name = "Agent";
        AgentAdmin.validateDraft(b, 1);
        try {
            AgentAdmin.validateDraft(b, 2);
            Assert.fail();
        } catch (final AgentException e) {
            Assert.assertEquals("scope", e.field());
        }
        b.scope = new Agent.Scope(Set.of("a"), false);
        b.actions = new LinkedHashSet<>(Arrays.asList("crawl.start"));
        try {
            AgentAdmin.validateDraft(b, 4);
            Assert.fail("crawl rights need domains");
        } catch (final AgentException e) {
            Assert.assertEquals("domains", e.field());
        }
        b.kind = Agent.Kind.RESEARCH_WORKER;
        b.actions = new LinkedHashSet<>(Arrays.asList("config.set"));
        try {
            AgentAdmin.validateDraft(b, 4);
            Assert.fail("workers never get configuration rights");
        } catch (final AgentException e) {
            Assert.assertEquals("actions", e.field());
        }
    }

    @Test
    public void draftRoundTrip() throws Exception {
        final Agent.Builder b = new Agent.Builder();
        b.name = "Ä Agent <x>";
        b.description = "d";
        b.kind = Agent.Kind.RESEARCH_WORKER;
        b.scope = new Agent.Scope(new LinkedHashSet<>(Arrays.asList("a", "b")), false);
        b.actions = new LinkedHashSet<>(Arrays.asList("search"));
        b.limits = new Agent.Limits(Arrays.asList("example.com"), 2, 77, 2, 30, 600, true);
        final Agent d = AgentAdmin.decodeDraft(AgentAdmin.encodeDraft(b)).build();
        Assert.assertEquals("Ä Agent <x>", d.name);
        Assert.assertEquals(Agent.Kind.RESEARCH_WORKER, d.kind);
        Assert.assertEquals(b.scope.collections, d.scope.collections);
        Assert.assertEquals(77, d.limits.maxPages);
        Assert.assertTrue(d.limits.modelAllowed);
        try {
            AgentAdmin.decodeDraft("%%%not-base64");
            Assert.fail();
        } catch (final AgentException e) {
            Assert.assertEquals("draft", e.field());
        }
    }

    @Test
    public void tokenLifetimeAndGraceChoices() throws Exception {
        Assert.assertEquals(90 * AgentStore.DAY, AgentAdmin.tokenTtl(form()));
        Assert.assertEquals(365 * AgentStore.DAY, AgentAdmin.tokenTtl(form("expiresInDays", "365")));
        try {
            AgentAdmin.tokenTtl(form("expiresInDays", "999"));
            Assert.fail();
        } catch (final AgentException e) {
            Assert.assertEquals(400, e.status());
        }
        Assert.assertEquals(15 * 60_000L, AgentAdmin.rotationGrace(form("graceMinutes", "15")));
        try {
            AgentAdmin.rotationGrace(form("graceMinutes", "120"));
            Assert.fail();
        } catch (final AgentException e) {
            Assert.assertEquals("graceMinutes", e.field());
        }
    }

    @Test
    public void clustroSettings() throws Exception {
        final String key = "ak_" + "0123456789abcdef".repeat(4);
        final JSONObject c = AgentAdmin.parseClustro(form("clustroBaseUrl", "https://clustro.example/",
                "clustroWorkspaceId", "ws_1", "clustroConnectionId", "conn-1", "clustroAgentKey", key), null);
        Assert.assertEquals("https://clustro.example", c.optString("clustroBaseUrl"));
        Assert.assertEquals(key, c.optString("clustroAgentKey"));
        // an empty key keeps the stored one
        final JSONObject kept = AgentAdmin.parseClustro(form("clustroBaseUrl", "https://c.example",
                "clustroWorkspaceId", "ws_1", "clustroConnectionId", "conn-1", "clustroAgentKey", ""), c);
        Assert.assertEquals(key, kept.optString("clustroAgentKey"));
        for (final String[] bad : new String[][] {
                {"clustroAgentKey", "ak_short"}, {"clustroBaseUrl", "ftp://x"}, {"clustroBaseUrl", "https://u:p@x.example"},
                {"clustroWorkspaceId", ""}}) {
            final Map<String, String> f = form("clustroBaseUrl", "https://c.example", "clustroWorkspaceId", "ws",
                    "clustroConnectionId", "cn", "clustroAgentKey", key);
            f.put(bad[0], bad[1]);
            try {
                AgentAdmin.parseClustro(f, null);
                Assert.fail(Arrays.toString(bad));
            } catch (final AgentException e) {
                Assert.assertEquals(bad[0], e.field());
            }
        }
    }

    @Test
    public void connectionStateIsComputedHonestly() throws Exception {
        final Agent.Builder b = new Agent.Builder();
        b.name = "A";
        b.scope = new Agent.Scope(Set.of("a"), false);
        b.actions = new LinkedHashSet<>(Arrays.asList("search"));
        Agent a = this.agents.store.createAgent(b);
        Assert.assertEquals("no_token", AgentAdmin.connectionState(a, this.agents.store));

        final String token = this.agents.store.issueToken(a.id, 30 * AgentStore.DAY).plainText();
        Assert.assertEquals("never_connected", AgentAdmin.connectionState(a, this.agents.store));

        final AgentApi api = new AgentApi(this.agents, new ScopedActions(null, null));
        Assert.assertEquals(200, api.handle(new AgentApi.Request("GET", List.of("capabilities"), new HashMap<>(),
                "Bearer " + token, "c", null, JSONObject::new)).status);
        Assert.assertEquals("connected", AgentAdmin.connectionState(a, this.agents.store));

        // a changed grant makes the handshake stale until the agent fetches its capabilities again
        final Agent.Builder change = a.toBuilder();
        change.actions = new LinkedHashSet<>(Arrays.asList("search", "index.evidence"));
        a = this.agents.store.updateAgent(a.id, change);
        Assert.assertEquals("stale", AgentAdmin.connectionState(a, this.agents.store));
        api.handle(new AgentApi.Request("GET", List.of("capabilities"), new HashMap<>(), "Bearer " + token, "c", null,
                JSONObject::new));
        Assert.assertEquals("connected", AgentAdmin.connectionState(a, this.agents.store));

        this.now.addAndGet(AgentAdmin.HANDSHAKE_TTL + 1);
        Assert.assertEquals("stale", AgentAdmin.connectionState(a, this.agents.store));

        a = this.agents.store.pause(a.id);
        Assert.assertEquals("paused", AgentAdmin.connectionState(a, this.agents.store));
        a = this.agents.store.resume(a.id);
        this.now.addAndGet(30 * AgentStore.DAY);
        Assert.assertEquals("expired", AgentAdmin.connectionState(a, this.agents.store));
        a = this.agents.store.revoke(a.id);
        Assert.assertEquals("revoked", AgentAdmin.connectionState(a, this.agents.store));
    }

    @Test
    public void workerRuntimeNeedsFreshReports() throws Exception {
        final Agent.Builder b = new Agent.Builder();
        b.name = "W";
        b.kind = Agent.Kind.RESEARCH_WORKER;
        b.scope = new Agent.Scope(Set.of("a"), false);
        b.actions = new LinkedHashSet<>(Arrays.asList("search"));
        final Agent a = this.agents.store.createAgent(b);
        Assert.assertFalse(AgentAdmin.runtimeState(a, this.agents.store).optBoolean("reporting"));

        this.agents.store.recordHeartbeat(a.id, Json.obj("clustroReachable", true, "clustroCheckedAt", this.now.get() - 10 * 60_000L));
        final JSONObject old = AgentAdmin.runtimeState(a, this.agents.store);
        Assert.assertTrue(old.optBoolean("reporting"));
        Assert.assertFalse("an old Clustro check is not a current connection", old.optBoolean("clustroReachable"));

        this.agents.store.recordHeartbeat(a.id, Json.obj("clustroReachable", true, "clustroCheckedAt", this.now.get()));
        Assert.assertTrue(AgentAdmin.runtimeState(a, this.agents.store).optBoolean("clustroReachable"));
        this.now.addAndGet(AgentAdmin.RUNTIME_TTL + 1);
        final JSONObject stale = AgentAdmin.runtimeState(a, this.agents.store);
        Assert.assertFalse(stale.optBoolean("reporting"));
        Assert.assertFalse(stale.optBoolean("clustroReachable"));
    }

    @Test
    public void profileCollectionsFromDiscoveryProfiles() {
        final List<String> cols = AgentAdmin.profileCollections(new File("tools/scoutro/discovery/profiles.json"));
        Assert.assertTrue(cols.toString(), cols.containsAll(Arrays.asList("edelsenior-web", "prospect-edelsenior",
                "checkthecoach-web", "stackfinder-web", "bauteamcheck-web")));
        Assert.assertTrue(AgentAdmin.profileCollections(new File("does-not-exist.json")).isEmpty());
    }
}
