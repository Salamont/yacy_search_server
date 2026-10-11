/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. All HTTP is fake. */
package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.access.Caller;
import net.yacy.scoutro.access.Permission;
import net.yacy.scoutro.access.Role;
import net.yacy.scoutro.access.Scope;

/**
 * Permission and collection scope of the API routes for people (docs/SCOUTRO_USERS_ACCESS.md, sections 3 and 6):
 * the administrator keeps every answer, an account gets only its collections, whatever it sends.
 */
public class PeopleRoutesTest {

    private static final Scope MINE = Scope.of(List.of("edelsenior-web", "checkthecoach-web"));

    private static PeopleRoutes research(final Scope scope) {
        return new PeopleRoutes(Caller.account("anna", Role.RESEARCH, scope, false));
    }

    private static PeopleRoutes operator(final Scope scope) {
        return new PeopleRoutes(Caller.account("otto", Role.OPERATOR, scope, false));
    }

    private static Map<String, String> q(final String... kv) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static void refused(final String code, final int status, final Call call) throws Exception {
        try {
            call.run();
            fail("expected " + code);
        } catch (final ApiException e) {
            assertEquals(code, e.code());
            assertEquals(status, e.status());
        }
    }

    private interface Call {
        void run() throws Exception;
    }

    @Test
    public void viewNeverWidensTheScope() throws Exception {
        final PeopleRoutes anna = research(MINE);
        assertEquals("no collection: all of mine, never the whole index", List.of("checkthecoach-web", "edelsenior-web"), anna.view(q()));
        assertEquals(List.of("edelsenior-web"), anna.view(q("collection", "edelsenior-web")));
        // foreign and unknown names answer the same (no existence oracle); invalid names are a request error
        refused("collection_not_allowed", 403, () -> anna.view(q("collection", "stackfinder-web")));
        refused("collection_not_allowed", 403, () -> anna.view(q("collection", "does-not-exist")));
        refused("invalid_request", 400, () -> anna.view(q("collection", "a b")));
        refused("collection_not_allowed", 403, () -> research(Scope.NONE).view(q()));
        assertEquals(List.of("checkthecoach-web", "edelsenior-web"), anna.permitted());
        // all collections: the whole index without a request, like the administrator
        assertNull(research(Scope.ALL).view(q()));
        assertNull(new PeopleRoutes(Caller.admin("admin")).view(q()));
        assertEquals(List.of("stackfinder-web"), new PeopleRoutes(Caller.admin("admin")).view(q("collection", "stackfinder-web")));
    }

    @Test
    public void permissionsFollowTheRole() throws Exception {
        final PeopleRoutes anna = research(MINE);
        anna.require(Permission.READ);
        anna.require(Permission.CHAT);
        refused("forbidden", 403, () -> anna.require(Permission.COLLECT));
        refused("forbidden", 403, () -> anna.require(Permission.EXPORT));
        refused("forbidden", 403, () -> operator(Scope.ALL).require(Permission.ADMIN));
        new PeopleRoutes(Caller.account("e", Role.RESEARCH, MINE, true)).require(Permission.EXPORT);
        refused("unauthorized", 401, () -> new PeopleRoutes(Caller.anonymous()).require(Permission.SEARCH));
    }

    @Test
    public void searchStaysInsideTheCollections() throws Exception {
        final FakeUpstream yacy = new FakeUpstream();
        yacy.searchResults.put("edelsenior-web", List.of("https://a.example/"));
        yacy.searchResults.put("checkthecoach-web", List.of("https://b.example/"));
        yacy.searchResults.put(null, List.of("https://secret.example/"));
        final ScoutroActions actions = new ScoutroActions(yacy);
        final PeopleRoutes anna = research(MINE);

        final JSONObject both = anna.search(actions, q("q", "test"));
        final List<String> searched = new ArrayList<>();
        for (final FakeUpstream.Call c : yacy.calls("yacysearch.json")) searched.add(c.get("collection"));
        assertEquals(List.of("checkthecoach-web", "edelsenior-web"), searched);
        assertFalse(both.toString().contains("secret.example"));

        yacy.calls.clear();
        anna.search(actions, q("q", "test", "collection", "edelsenior-web"));
        assertEquals("edelsenior-web", yacy.last("yacysearch.json").get("collection"));

        refused("query_modifier_not_allowed", 400, () -> anna.search(actions, q("q", "test collection:stackfinder-web")));
        refused("forbidden", 403, () -> anna.search(actions, q("q", "test", "source", "network")));
        refused("collection_not_allowed", 403, () -> anna.search(actions, q("q", "test", "collection", "stackfinder-web")));

        // the administrator: unchanged, no collection filter
        yacy.calls.clear();
        new PeopleRoutes(Caller.admin("admin")).search(actions, q("q", "test"));
        assertNull(yacy.last("yacysearch.json").get("collection"));
    }

    @Test
    public void collectionListShowsOnlyOwnCollectionsAndNoCreation() throws Exception {
        final ScoutroActions actions = new ScoutroActions(new FakeUpstream());
        final JSONObject mine = research(MINE).collections(actions);
        final List<String> ids = new ArrayList<>();
        final JSONArray list = mine.getJSONArray("collections");
        for (int i = 0; i < list.length(); i++) ids.add(list.getJSONObject(i).getString("id"));
        assertEquals(List.of("checkthecoach-web", "edelsenior-web"), ids);
        assertFalse(mine.getBoolean("canCreate"));
        assertTrue(new PeopleRoutes(Caller.admin("admin")).collections(actions).getBoolean("canCreate"));
        assertFalse(research(Scope.ALL).collections(actions).getBoolean("canCreate"));
    }

    @Test
    public void indexStatusOfAnAccountCountsOnlyItsCollections() throws Exception {
        final ScoutroActions actions = new ScoutroActions(new FakeUpstream());
        final JSONObject status = research(MINE).index(actions);
        assertFalse(status.getBoolean("allCollections"));
        assertEquals(2, status.getJSONArray("collections").length());
        assertFalse("no crawler queues or global counts", status.has("crawler") || status.has("rwiWords"));
    }

    @Test
    public void operatorsCrawlOnlyIntoTheirCollectionsAndOnTheirHosts() throws Exception {
        final FakeUpstream yacy = new FakeUpstream();
        final ScoutroActions actions = new ScoutroActions(yacy);
        final PeopleRoutes otto = operator(MINE);
        final JSONObject foreign = Json.obj("url", "https://example.com/", "collection", "research", "scope", "domain");
        refused("collection_not_allowed", 403, () -> otto.crawlStart(actions, foreign, null));
        final JSONObject wide = Json.obj("url", "https://example.com/", "collection", "edelsenior-web", "scope", "wide");
        refused("scope_not_allowed", 403, () -> otto.crawlStart(actions, wide, null));
        yacy.outsideNumFound = 2;
        final JSONObject elsewhere = Json.obj("url", "https://example.com/", "collection", "edelsenior-web", "scope", "domain");
        refused("host_indexed_elsewhere", 409, () -> otto.crawlStart(actions, elsewhere, null));
        assertTrue("nothing reached YaCy's crawler", yacy.calls("Crawler_p.json").isEmpty());
    }

    @Test
    public void crawlsOfForeignCollectionsAreInvisible() throws Exception {
        final FakeUpstream yacy = new FakeUpstream();
        final ScoutroActions actions = new ScoutroActions(yacy);
        final String id = actions.crawlStartAdmin(Json.obj("url", "https://example.com/path", "collection", "research",
                "scope", "subpath", "depth", 1, "maxPages", 5), "admin-1").getString("id");
        final PeopleRoutes otto = operator(MINE);
        assertEquals(0, otto.crawlList(actions).getJSONArray("crawls").length());
        refused("crawl_not_found", 404, () -> otto.crawlGet(actions, id));
        refused("crawl_not_found", 404, () -> otto.crawlStop(actions, id));
        assertEquals(1, operator(Scope.of(List.of("research"))).crawlList(actions).getJSONArray("crawls").length());
        assertEquals(1, new PeopleRoutes(Caller.admin("admin")).crawlList(actions).getJSONArray("crawls").length());
    }

    @Test
    public void reportScopeIsTheAccountsScope() {
        assertNull(new PeopleRoutes(Caller.admin("admin")).reportScope());
        final ReportApi.Scope scope = operator(MINE).reportScope();
        assertTrue(scope.allows("edelsenior-web"));
        assertFalse(scope.allows("research"));
    }
}
