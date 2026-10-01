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
import net.yacy.scoutro.agents.CrawlRecord;
import net.yacy.scoutro.agents.ScoutroAgents;

/**
 * Data scopes and crawl limits are enforced on the server side for every
 * agent action (work package 3). The fake upstream records the exact
 * parameters sent to YaCy.
 */
public class ScopedActionsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private ScoutroAgents agents;
    private FakeUpstream yacy;
    private AgentApi api;

    @Before
    public void setUp() throws Exception {
        this.agents = ScoutroAgents.createForTest(this.tmp.newFolder("SETTINGS"), this.now::get);
        this.yacy = new FakeUpstream();
        this.api = new AgentApi(this.agents, new ScopedActions(new ScoutroActions(this.yacy), this.agents.store));
    }

    private String agent(final String name, final List<String> collections, final boolean all, final String... actions)
            throws Exception {
        final Agent.Builder b = new Agent.Builder();
        b.name = name;
        b.scope = new Agent.Scope(new LinkedHashSet<>(collections), all);
        b.actions = new LinkedHashSet<>(Arrays.asList(actions));
        b.limits = new Agent.Limits(Arrays.asList("example.com", "other.org"), 2, 100, 1, 600, 300, false);
        final Agent a = this.agents.store.createAgent(b);
        return this.agents.store.issueToken(a.id, 30 * AgentStore.DAY).plainText();
    }

    private AgentApi.Response call(final String token, final String method, final String path,
            final Map<String, String> query, final JSONObject body, final String idempotencyKey) {
        final List<String> segs = new ArrayList<>();
        for (final String s : path.split("/")) {
            if (!s.isEmpty()) {
                segs.add(s);
            }
        }
        return this.api.handle(new AgentApi.Request(method, segs, query == null ? new HashMap<>() : query,
                "Bearer " + token, "10.0.0.9", idempotencyKey, () -> body == null ? new JSONObject() : body));
    }

    private static Map<String, String> q(final String... kv) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String code(final AgentApi.Response r) {
        return r.body.optJSONObject("error") == null ? "" : r.body.optJSONObject("error").optString("code");
    }

    // ------------------------------------------------------------------
    // search
    // ------------------------------------------------------------------

    @Test
    public void searchAlwaysSendsTheScopeCollectionAndStaysLocal() throws Exception {
        final String t = agent("A", Arrays.asList("edelsenior-web"), false, "search");
        this.yacy.searchResults.put("edelsenior-web", Arrays.asList("https://a.example/1"));
        this.yacy.searchResults.put(null, Arrays.asList("https://everything.example/"));

        final AgentApi.Response r = call(t, "GET", "search", q("q", "pflege"), null, null);
        Assert.assertEquals(r.body.toString(), 200, r.status);
        final FakeUpstream.Call up = this.yacy.last("yacysearch.json");
        Assert.assertEquals("edelsenior-web", up.get("collection"));
        Assert.assertEquals("local", up.get("resource"));
        Assert.assertEquals("https://a.example/1",
                r.body.optJSONArray("results").optJSONObject(0).optString("url"));
    }

    @Test
    public void inlineCollectionSyntaxIsRefused() throws Exception {
        final String t = agent("A", Arrays.asList("edelsenior-web"), false, "search");
        for (final String query : new String[] {"collection:checkthecoach-web pflege", "pflege COLLECTION:x"}) {
            final AgentApi.Response r = call(t, "GET", "search", q("q", query), null, null);
            Assert.assertEquals(400, r.status);
            Assert.assertEquals("query_modifier_not_allowed", code(r));
        }
        Assert.assertTrue("no upstream search may happen", this.yacy.calls("yacysearch.json").isEmpty());
    }

    @Test
    public void foreignCollectionParameterIsDenied() throws Exception {
        final String t = agent("A", Arrays.asList("edelsenior-web"), false, "search", "index.evidence", "index.lookup");
        for (final String path : new String[] {"search", "index/evidence", "index/lookup"}) {
            final AgentApi.Response r = call(t, "GET", path,
                    q("q", "x", "domain", "example.com", "url", "https://example.com/", "collection", "checkthecoach-web"),
                    null, null);
            Assert.assertEquals(path, 403, r.status);
            Assert.assertEquals(path, "collection_not_in_scope", code(r));
        }
        Assert.assertTrue(this.yacy.calls.isEmpty());
    }

    @Test
    public void networkSearchNeedsItsOwnGrant() throws Exception {
        final String t = agent("A", Arrays.asList("edelsenior-web"), false, "search");
        final AgentApi.Response r = call(t, "GET", "search", q("q", "x", "source", "network"), null, null);
        Assert.assertEquals(403, r.status);
        Assert.assertEquals("action_not_granted", code(r));
        Assert.assertTrue(this.yacy.calls.isEmpty());

        final String n = agent("N", Arrays.asList("edelsenior-web"), false, "search", "search.network");
        final AgentApi.Response ok = call(n, "GET", "search", q("q", "x", "source", "network"), null, null);
        Assert.assertEquals(200, ok.status);
        Assert.assertFalse(ok.body.optBoolean("scoped", true));
        Assert.assertEquals("global", this.yacy.last("yacysearch.json").get("resource"));
    }

    @Test
    public void multiCollectionScopeSearchesEachCollectionAndMerges() throws Exception {
        final String t = agent("A", Arrays.asList("edelsenior-web", "prospect-edelsenior"), false, "search");
        this.yacy.searchResults.put("edelsenior-web", Arrays.asList("https://a/1", "https://shared/"));
        this.yacy.searchResults.put("prospect-edelsenior", Arrays.asList("https://b/1", "https://shared/"));
        final AgentApi.Response r = call(t, "GET", "search", q("q", "pflege", "limit", "10"), null, null);
        Assert.assertEquals(200, r.status);
        final List<FakeUpstream.Call> calls = this.yacy.calls("yacysearch.json");
        Assert.assertEquals(2, calls.size());
        Assert.assertEquals("edelsenior-web", calls.get(0).get("collection"));
        Assert.assertEquals("prospect-edelsenior", calls.get(1).get("collection"));
        final JSONArray results = r.body.optJSONArray("results");
        final List<String> urls = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            urls.add(results.optJSONObject(i).optString("url"));
        }
        Assert.assertEquals(Arrays.asList("https://a/1", "https://b/1", "https://shared/"), urls);
        Assert.assertTrue(r.body.optBoolean("totalIsApproximate"));
    }

    @Test
    public void allCollectionsScopeHasNoFilterButStaysLocal() throws Exception {
        final String t = agent("A", new ArrayList<>(), true, "search");
        Assert.assertEquals(200, call(t, "GET", "search", q("q", "x"), null, null).status);
        final FakeUpstream.Call up = this.yacy.last("yacysearch.json");
        Assert.assertNull(up.get("collection"));
        Assert.assertEquals("local", up.get("resource"));
    }

    // ------------------------------------------------------------------
    // evidence, lookup, index status
    // ------------------------------------------------------------------

    @Test
    public void evidenceFiltersOnTheScope() throws Exception {
        final String t = agent("A", Arrays.asList("edelsenior-web", "prospect-edelsenior"), false, "index.evidence");
        Assert.assertEquals(200, call(t, "GET", "index/evidence", q("domain", "example.com"), null, null).status);
        Assert.assertEquals("httpstatus_i:200 AND (collection_sxt:\"edelsenior-web\" OR collection_sxt:\"prospect-edelsenior\")",
                this.yacy.last("solr/select").get("fq"));

        Assert.assertEquals(200, call(t, "GET", "index/evidence",
                q("domain", "example.com", "collection", "prospect-edelsenior"), null, null).status);
        Assert.assertEquals("httpstatus_i:200 AND (collection_sxt:\"prospect-edelsenior\")",
                this.yacy.last("solr/select").get("fq"));
    }

    @Test
    public void lookupIsFilteredAndReportsOnlyScopeCollections() throws Exception {
        final String t = agent("A", Arrays.asList("edelsenior-web"), false, "index.lookup");
        final AgentApi.Response r = call(t, "GET", "index/lookup", q("url", "https://example.com/"), null, null);
        Assert.assertEquals(200, r.status);
        Assert.assertEquals("(collection_sxt:\"edelsenior-web\")", this.yacy.last("solr/select").get("fq"));
        final JSONArray cols = r.body.optJSONObject("document").optJSONArray("collections");
        Assert.assertEquals(1, cols.length());
        Assert.assertEquals("edelsenior-web", cols.optString(0));

        call(t, "GET", "index/lookup", q("host", "example.com"), null, null);
        Assert.assertEquals("(collection_sxt:\"edelsenior-web\")", this.yacy.last("solr/select").get("fq"));
    }

    @Test
    public void indexStatusIsPerCollectionWithoutGlobalValues() throws Exception {
        final String t = agent("A", Arrays.asList("edelsenior-web", "prospect-edelsenior"), false, "index.status");
        final AgentApi.Response r = call(t, "GET", "index", null, null, null);
        Assert.assertEquals(200, r.status);
        Assert.assertEquals(2, r.body.optJSONArray("collections").length());
        Assert.assertNull(r.body.opt("crawler"));
        Assert.assertNull(r.body.opt("rwiWords"));
        Assert.assertTrue(this.yacy.calls("api/status_p.xml").isEmpty());

        final AgentApi.Response global = call(t, "GET", "index", q("global", "true"), null, null);
        Assert.assertEquals(403, global.status);
        Assert.assertEquals("action_not_granted", code(global));
    }

    // ------------------------------------------------------------------
    // crawls
    // ------------------------------------------------------------------

    private static JSONObject crawl(final String url, final String collection) {
        return Json.obj("url", url, "collection", collection);
    }

    @Test
    public void crawlStartIsTextOnlyAndWithinLimits() throws Exception {
        final String t = agent("A", Arrays.asList("edelsenior-web"), false, "crawl.start", "crawl.list", "crawl.status");
        final AgentApi.Response r = call(t, "POST", "crawls", null, crawl("https://www.example.com/", "edelsenior-web"), null);
        Assert.assertEquals(r.body.toString(), 201, r.status);
        Assert.assertTrue(r.headers.get("Location").startsWith("/scoutro/api/agent/v1/crawls/"));
        final FakeUpstream.Call up = this.yacy.last("Crawler_p.json");
        Assert.assertEquals("on", up.get("indexText"));
        Assert.assertEquals("off", up.get("indexMedia"));
        Assert.assertEquals("off", up.get("storeHTCache"));
        Assert.assertEquals("nocache", up.get("cachePolicy"));
        Assert.assertEquals("off", up.get("deleteold"));
        Assert.assertEquals("domain", up.get("range"));
        Assert.assertEquals("2", up.get("crawlingDepth"));
        Assert.assertEquals("on", up.get("crawlingDomMaxCheck"));
        Assert.assertEquals("100", up.get("crawlingDomMaxPages"));
        Assert.assertEquals("edelsenior-web", up.get("collection"));
        Assert.assertEquals("/scoutro/api/agent/v1/crawls/crawl1",
                r.body.optJSONObject("links").optString("self"));
    }

    @Test
    public void crawlLimits() throws Exception {
        final String t = agent("A", Arrays.asList("edelsenior-web"), false, "crawl.start");
        String[][] cases = {
            {"https://evil.org/", "edelsenior-web", null, null, null, "403", "limit_exceeded:domains"},
            {"https://badexample.com/", "edelsenior-web", null, null, null, "403", "limit_exceeded:domains"},
            {"https://example.com/", "checkthecoach-web", null, null, null, "403", "collection_not_in_scope"},
            {"https://example.com/", null, null, null, null, "400", "invalid_request"},
            {"https://example.com/", "edelsenior-web", "3", null, null, "403", "limit_exceeded:maxDepth"},
            {"https://example.com/", "edelsenior-web", null, "101", null, "403", "limit_exceeded:maxPages"},
            {"https://example.com/", "edelsenior-web", null, null, "wide", "400", "invalid_request"},
        };
        for (final String[] c : cases) {
            final JSONObject body = Json.obj("url", c[0]);
            if (c[1] != null) Json.put(body, "collection", c[1]);
            if (c[2] != null) Json.put(body, "depth", Integer.parseInt(c[2]));
            if (c[3] != null) Json.put(body, "maxPages", Integer.parseInt(c[3]));
            if (c[4] != null) Json.put(body, "scope", c[4]);
            final AgentApi.Response r = call(t, "POST", "crawls", null, body, null);
            Assert.assertEquals(Arrays.toString(c), Integer.parseInt(c[5]), r.status);
            Assert.assertEquals(Arrays.toString(c), c[6], code(r));
        }
        Assert.assertTrue("no crawl may have been started", this.yacy.calls("Crawler_p.json").isEmpty());
    }

    @Test
    public void parallelismHostBusyAndIdempotency() throws Exception {
        final String t = agent("A", Arrays.asList("edelsenior-web"), false, "crawl.start", "crawl.stop");
        final AgentApi.Response first = call(t, "POST", "crawls", null, crawl("https://example.com/", "edelsenior-web"), "run-1");
        Assert.assertEquals(201, first.status);

        // same Idempotency-Key: the existing crawl, no second start
        final AgentApi.Response replay = call(t, "POST", "crawls", null, crawl("https://example.com/", "edelsenior-web"), "run-1");
        Assert.assertEquals(200, replay.status);
        Assert.assertTrue(replay.body.optBoolean("idempotentReplay"));
        Assert.assertEquals(first.body.optString("id"), replay.body.optString("id"));
        Assert.assertEquals(1, this.yacy.calls("Crawler_p.json").size());

        // limit of one running crawl
        final AgentApi.Response second = call(t, "POST", "crawls", null, crawl("https://other.org/", "edelsenior-web"), null);
        Assert.assertEquals(429, second.status);
        Assert.assertEquals("limit_exceeded:maxParallelCrawls", code(second));

        // a foreign running crawl on the same host blocks the start
        this.yacy.crawls.put("admincrawl", new FakeUpstream.Crawl("admincrawl", "other.org", "user"));
        Assert.assertEquals(200, call(t, "POST", "crawls/" + first.body.optString("id") + "/stop", null, new JSONObject(), null).status);
        final AgentApi.Response busy = call(t, "POST", "crawls", null, crawl("https://www.other.org/", "edelsenior-web"), null);
        Assert.assertEquals(409, busy.status);
        Assert.assertEquals("host_busy", code(busy));
    }

    @Test
    public void foreignCrawlsAreInvisibleAndCannotBeStopped() throws Exception {
        final String a = agent("A", Arrays.asList("edelsenior-web"), false, "crawl.start", "crawl.list", "crawl.status", "crawl.stop");
        final String b = agent("B", Arrays.asList("edelsenior-web"), false, "crawl.list", "crawl.status", "crawl.stop");
        final String id = call(a, "POST", "crawls", null, crawl("https://example.com/", "edelsenior-web"), null).body.optString("id");
        this.yacy.crawls.put("admincrawl", new FakeUpstream.Crawl("admincrawl", "admin.example", "user"));

        Assert.assertEquals(0, call(b, "GET", "crawls", null, null, null).body.optJSONArray("crawls").length());
        for (final String foreign : new String[] {id, "admincrawl"}) {
            final AgentApi.Response st = call(b, "GET", "crawls/" + foreign, null, null, null);
            Assert.assertEquals(404, st.status);
            Assert.assertEquals("crawl_not_found", code(st));
            final AgentApi.Response stop = call(b, "POST", "crawls/" + foreign + "/stop", null, new JSONObject(), null);
            Assert.assertEquals(404, stop.status);
        }
        Assert.assertTrue(this.yacy.crawls.containsKey(id));
        Assert.assertTrue(this.yacy.crawls.containsKey("admincrawl"));

        final JSONArray own = call(a, "GET", "crawls", null, null, null).body.optJSONArray("crawls");
        Assert.assertEquals(1, own.length());
        Assert.assertEquals(id, own.optJSONObject(0).optString("id"));
        Assert.assertEquals(200, call(a, "POST", "crawls/" + id + "/stop", null, new JSONObject(), null).status);
        Assert.assertFalse(this.yacy.crawls.containsKey(id));
        Assert.assertEquals("removed", call(a, "GET", "crawls/" + id, null, null, null).body.optString("state"));
    }

    @Test
    public void ownershipRecordOfOtherAgentDoesNotLeak() throws Exception {
        final String b = agent("B", Arrays.asList("edelsenior-web"), false, "crawl.status");
        this.agents.store.recordCrawl(new CrawlRecord("x1", "agt_someoneelse00", "edelsenior-web", "example.com", "", 0));
        Assert.assertEquals(404, call(b, "GET", "crawls/x1", null, null, null).status);
    }

    @Test
    public void hostHelpers() {
        Assert.assertEquals("example.com", ScopedActions.crawlHost("c", Json.obj("name", "https://Example.com/path")));
        Assert.assertEquals("example.com", ScopedActions.crawlHost("c", Json.obj("name", "example.com")));
        Assert.assertTrue(ScopedActions.sameHost("www.example.com", "example.com"));
        Assert.assertFalse(ScopedActions.sameHost("example.com", "example.org"));
        Assert.assertFalse(ScopedActions.sameHost("", ""));
    }
}
