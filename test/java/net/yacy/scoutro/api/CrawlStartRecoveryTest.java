package net.yacy.scoutro.api;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
import net.yacy.scoutro.agents.CrawlRecord;
import net.yacy.scoutro.agents.ScoutroAgents;

/**
 * Crawl starts survive crashes and storage faults between "YaCy started the
 * crawl" and "Scoutro stored its id" without ever starting a second crawl
 * (acceptance finding 3). Restarts are simulated by reopening the agent
 * store from disk; YaCy's state lives in the fake upstream.
 */
public class CrawlStartRecoveryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private File dir;
    private ScoutroAgents agents;
    private FakeUpstream yacy;
    private AgentApi api;
    private String token;
    private String agentId;

    @Before
    public void setUp() throws Exception {
        this.dir = this.tmp.newFolder("SETTINGS");
        this.yacy = new FakeUpstream();
        open();
        final Agent.Builder b = new Agent.Builder();
        b.name = "Crawler";
        b.scope = new Agent.Scope(new LinkedHashSet<>(Arrays.asList("edelsenior-web")), false);
        b.actions = new LinkedHashSet<>(Arrays.asList("crawl.start", "crawl.list", "crawl.status", "crawl.stop"));
        b.limits = new Agent.Limits(Arrays.asList("example.com", "other.org"), 2, 100, 1, 600, 300, false);
        final Agent a = this.agents.store.createAgent(b);
        this.agentId = a.id;
        this.token = this.agents.store.issueToken(a.id, 30 * AgentStore.DAY).plainText();
        // first use writes lastUsedAt; later requests within a minute do not write, so injected
        // storage faults hit the crawl records and not the (fault tolerant) usage timestamp
        Assert.assertEquals(200, call("GET", "crawls", null, null).status);
    }

    /** (Re)start Scoutro: a new store, authorizer and API on the same files. */
    private void open() throws Exception {
        this.agents = ScoutroAgents.createForTest(this.dir, this.now::get);
        this.api = new AgentApi(this.agents, new ScopedActions(new ScoutroActions(this.yacy), this.agents.store));
    }

    private AgentApi.Response start(final String url, final String key) {
        return call("POST", "crawls", Json.obj("url", url, "collection", "edelsenior-web"), key);
    }

    private AgentApi.Response call(final String method, final String path, final JSONObject body, final String key) {
        final List<String> segs = new ArrayList<>(Arrays.asList(path.split("/")));
        return this.api.handle(new AgentApi.Request(method, segs, new HashMap<>(), "Bearer " + this.token, "10.0.0.1",
                key, () -> body == null ? new JSONObject() : body));
    }

    private int starts() {
        return this.yacy.calls("Crawler_p.json").size();
    }

    private static String code(final AgentApi.Response r) {
        return r.body.optJSONObject("error") == null ? "" : r.body.optJSONObject("error").optString("code");
    }

    private CrawlRecord only() {
        final List<CrawlRecord> l = this.agents.store.crawls(this.agentId);
        Assert.assertEquals(1, l.size());
        return l.get(0);
    }

    @Test
    public void attemptIsRecordedWithMarkerBeforeYaCyIsAsked() {
        final AgentApi.Response r = start("https://example.com/", "run-1");
        Assert.assertEquals(201, r.status);
        final CrawlRecord rec = only();
        Assert.assertTrue(rec.isStarted());
        Assert.assertTrue(rec.startMarker.matches("[0-9a-f]{32}"));
        Assert.assertEquals("https://example.com/", rec.url);
        Assert.assertEquals(".*/scoutro-start-" + rec.startMarker + "/.*",
                this.yacy.last("Crawler_p.json").get("mustnotmatch"));
        Assert.assertNull("the marker is internal", r.body.opt("startMarker"));
        // replay with the same key: the same crawl, no second start
        final AgentApi.Response replay = start("https://example.com/", "run-1");
        Assert.assertEquals(200, replay.status);
        Assert.assertTrue(replay.body.optBoolean("idempotentReplay"));
        Assert.assertEquals(1, starts());
    }

    @Test
    public void storageFaultBeforeTheStartChangesNothing() {
        this.agents.store.failSaves(0, 1);
        final AgentApi.Response r = start("https://example.com/", "run-1");
        Assert.assertEquals(503, r.status);
        Assert.assertEquals("agent_store_unavailable", code(r));
        Assert.assertEquals("YaCy must not be asked when the attempt is not recorded", 0, starts());
        Assert.assertTrue(this.agents.store.crawls(this.agentId).isEmpty());
        Assert.assertEquals(201, start("https://example.com/", "run-1").status);
    }

    @Test
    public void crashAfterTheStartIsReconciledByMarkerAfterRestart() throws Exception {
        this.yacy.crawlStartFault = "crash";
        final AgentApi.Response r = start("https://example.com/", "run-1");
        Assert.assertEquals(500, r.status);
        Assert.assertTrue(only().isStarting());
        open(); // restart
        final AgentApi.Response replay = start("https://example.com/", "run-1");
        Assert.assertEquals(replay.body.toString(), 200, replay.status);
        Assert.assertEquals("crawl1", replay.body.optString("id"));
        Assert.assertTrue(replay.body.optBoolean("idempotentReplay"));
        Assert.assertEquals("no second crawl", 1, starts());
        Assert.assertEquals("crawl1", only().crawlId);
        Assert.assertEquals(200, call("GET", "crawls/crawl1", null, null).status);
    }

    @Test
    public void storageFaultAfterTheStartIsReconciled() throws Exception {
        this.agents.store.failSaves(1, 1); // the attempt is recorded, storing the crawl id fails
        final AgentApi.Response r = start("https://example.com/", "run-1");
        Assert.assertEquals(500, r.status);
        Assert.assertEquals("agent_store_unavailable", code(r));
        Assert.assertEquals("crawl1", r.body.optJSONObject("error").optJSONObject("details").optString("id"));
        // the agent can already see and stop it: status reconciles by marker
        Assert.assertEquals(200, call("GET", "crawls/crawl1", null, null).status);
        open();
        Assert.assertEquals(200, start("https://example.com/", "run-1").status);
        Assert.assertEquals(1, starts());
    }

    @Test
    public void lostAnswerAfterTheStartIsAssignedByMarker() {
        this.yacy.crawlStartFault = "lost"; // YaCy created the profile, its answer is lost
        final AgentApi.Response r = start("https://example.com/", "run-1");
        Assert.assertEquals(502, r.status);
        Assert.assertEquals("crawl1", r.body.optJSONObject("error").optJSONObject("details").optString("id"));
        Assert.assertTrue(only().isStarted());
        Assert.assertEquals(200, start("https://example.com/", "run-1").status);
        Assert.assertEquals(1, starts());
    }

    @Test
    public void lostAnswerWithoutProfileIsUnconfirmedAndNeverRepeated() {
        this.yacy.crawlStartFault = "lostNoProfile"; // no profile carries the marker: outcome unknown
        final AgentApi.Response r = start("https://example.com/", "run-1");
        Assert.assertEquals(502, r.status);
        Assert.assertEquals("crawl_start_unconfirmed", code(r));
        Assert.assertTrue(only().isStarting());
        final AgentApi.Response replay = start("https://example.com/", "run-1");
        Assert.assertEquals(409, replay.status);
        Assert.assertEquals("crawl_start_unconfirmed", code(replay));
        Assert.assertEquals(1, starts());
    }

    @Test
    public void repetitionAfterTheFirstCrawlEndedIsNeverAStartWhenTheProfileStillExists() throws Exception {
        this.yacy.crawlStartFault = "crash";
        start("https://example.com/", "run-1");
        this.yacy.crawls.get("crawl1").status = "terminated"; // the first crawl has finished
        open();
        final AgentApi.Response replay = start("https://example.com/", "run-1");
        Assert.assertEquals(200, replay.status);
        Assert.assertEquals("terminated", replay.body.optString("state"));
        Assert.assertEquals(1, starts());
    }

    @Test
    public void repetitionAfterTheProfileIsGoneIsUnconfirmedUntilTheAdministratorResolvesIt() throws Exception {
        this.yacy.crawlStartFault = "crash";
        start("https://example.com/", "run-1");
        this.yacy.crawls.clear(); // the first crawl finished and YaCy removed its profile
        open();
        AgentApi.Response replay = start("https://example.com/", "run-1");
        Assert.assertEquals(409, replay.status);
        Assert.assertEquals("crawl_start_unconfirmed", code(replay));
        replay = start("https://example.com/", "run-1");
        Assert.assertEquals("still refused on every repetition", 409, replay.status);
        Assert.assertEquals("never started blindly a second time", 1, starts());

        // listed as unconfirmed, and counted as running for the parallel limit
        final JSONArray list = call("GET", "crawls", null, null).body.optJSONArray("crawls");
        Assert.assertEquals("unconfirmed", list.optJSONObject(0).optString("state"));
        final AgentApi.Response other = start("https://other.org/", "run-2");
        Assert.assertEquals(429, other.status);

        // administrator: the start did not lead to a usable crawl -> the key is free again
        this.agents.store.abandonCrawlStart(this.agentId, only().startMarker);
        final AgentApi.Response again = start("https://example.com/", "run-1");
        Assert.assertEquals(201, again.status);
        Assert.assertEquals(2, starts());
    }

    @Test
    public void ambiguousFailureWithoutProfileIsUnconfirmed() throws Exception {
        // YaCy may or may not have processed the request; no profile with the marker appears
        this.yacy.crawlStartFault = "reject";
        final AgentApi.Response rejected = start("https://example.com/", "run-1");
        Assert.assertEquals(422, rejected.status);
        Assert.assertEquals(CrawlRecord.REJECTED, only().state);
        // a refused start releases the key: the next attempt starts normally
        Assert.assertEquals(201, start("https://example.com/", "run-1").status);
        Assert.assertEquals(2, starts());
    }

    @Test
    public void failureAnswerWithActiveProfileIsAssignedNotRejected() throws Exception {
        // found on a real YaCy: "Crawling of ... failed" although the profile was activated
        this.yacy.crawlStartFault = "failedButActive";
        final AgentApi.Response r = start("https://example.com/", "run-1");
        Assert.assertEquals(422, r.status);
        Assert.assertEquals("crawl1", r.body.optJSONObject("error").optJSONObject("details").optString("id"));
        Assert.assertTrue(only().isStarted());
        Assert.assertEquals("crawl1", only().crawlId);
        final AgentApi.Response replay = start("https://example.com/", "run-1");
        Assert.assertEquals(200, replay.status);
        Assert.assertEquals("the key must not start a second crawl", 1, starts());
        Assert.assertEquals(200, call("POST", "crawls/crawl1/stop", new JSONObject(), null).status);
    }

    @Test
    public void previouslyRejectedAttemptWithProfileIsReconciled() throws Exception {
        // an attempt stored as rejected by an earlier version, whose profile exists after all
        final String marker = "00112233445566778899aabbccddeeff";
        this.agents.store.recordCrawl(new CrawlRecord("", this.agentId, "edelsenior-web", "example.com", "run-9",
                this.now.get(), CrawlRecord.REJECTED, marker, "https://example.com/", 1, 10, "domain"));
        final FakeUpstream.Crawl c = new FakeUpstream.Crawl("old1", "example.com", "edelsenior-web");
        c.mustNotMatch = ScoutroActions.startMarkerFilter(marker);
        this.yacy.crawls.put("old1", c);
        final AgentApi.Response replay = start("https://example.com/", "run-9");
        Assert.assertEquals(200, replay.status);
        Assert.assertEquals("old1", replay.body.optString("id"));
        Assert.assertEquals(0, starts());
    }

    @Test
    public void onlyUnconfirmedStartsCanBeAbandoned() throws Exception {
        Assert.assertEquals(201, start("https://example.com/", "run-1").status);
        try {
            this.agents.store.abandonCrawlStart(this.agentId, only().startMarker);
            Assert.fail("a confirmed crawl cannot be abandoned");
        } catch (final net.yacy.scoutro.agents.AgentException e) {
            Assert.assertEquals(409, e.status());
        }
    }

    @Test
    public void markerFilterIsRecognized() {
        final String m = "0123456789abcdef0123456789abcdef";
        Assert.assertEquals(m, ScoutroActions.startMarker(ScoutroActions.startMarkerFilter(m)));
        Assert.assertNull(ScoutroActions.startMarker(""));
        Assert.assertNull(ScoutroActions.startMarker(".*foo.*"));
        // the filter excludes no ordinary URL
        Assert.assertFalse("https://example.com/a/b?c=d".matches(ScoutroActions.startMarkerFilter(m)));
    }
}
