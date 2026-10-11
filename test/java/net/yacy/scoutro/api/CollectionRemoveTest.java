/* Scoutro contributors, GPL-2.0-or-later. Only temporary files; the index is a fake. */
package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.access.Scope;
import net.yacy.scoutro.agents.Agent;
import net.yacy.scoutro.agents.AgentStore;

/**
 * Removing a collection (Dashboard "−", {@code DELETE /v1/collections/{id}}): only an empty collection created in
 * Scoutro that nothing names; the index and every other store stay as they are.
 */
public class CollectionRemoveTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<String, Long> index = new LinkedHashMap<>(Map.of("stackfinder-web", 12L, "robot_snippet", 9L));
    private final AtomicInteger reads = new AtomicInteger();

    private CollectionCatalog catalog(final Path file) {
        return new CollectionCatalog(file, () -> {
            this.reads.incrementAndGet();
            return this.index;
        }, () -> List.of("edelsenior-web"));
    }

    private Path file() {
        return this.tmp.getRoot().toPath().resolve("collections.json");
    }

    private static ApiException refused(final CollectionCatalog c, final String id, final CollectionCatalog.Uses uses, final int status,
            final String code) {
        try {
            c.remove(id, uses);
            fail("removed: " + id);
            return null;
        } catch (final ApiException e) {
            assertEquals(id, status, e.status());
            assertEquals(id, code, e.code());
            return e;
        }
    }

    private static List<String> ids(final CollectionCatalog c) throws ApiException {
        return c.entries(true).stream().map(e -> e.id).toList();
    }

    @Test
    public void pagesMissingFromTheFacetAreCountedExactlyBeforeRemoval() throws Exception {
        // more than 500 collections: the facet leaves a small one out, the exact count still finds its pages
        final CollectionCatalog c = new CollectionCatalog(file(), new CollectionCatalog.Index() {
            @Override public Map<String, Long> counts() { return Map.of(); }
            @Override public long count(final String id) { return "klein-web".equals(id) ? 3L : 0L; }
        }, List::of);
        c.create(new JSONObject().put("id", "klein-web").put("name", "Klein"));
        c.create(new JSONObject().put("id", "leer-web").put("name", "Leer"));
        final ApiException e = refused(c, "klein-web", id -> Map.of(), 409, "collection_has_documents");
        assertEquals(3L, e.toJson().getJSONObject("error").getJSONObject("details").getLong("documents"));
        assertTrue(ids(c).contains("klein-web"));
        assertEquals("leer-web", c.remove("leer-web", id -> Map.of()).id);
    }

    @Test
    public void reassignIsAPathOfTheApiNotACollection() throws Exception {
        try {
            catalog(file()).create(new JSONObject().put("id", "reassign").put("name", "Reassign"));
            fail("created");
        } catch (final ApiException e) {
            assertEquals("collection_reserved", e.code());
        }
    }

    @Test
    public void anEmptyUnusedCreatedCollectionGoesFromTheListAndCanBeCreatedAgain() throws Exception {
        final CollectionCatalog c = catalog(file());
        c.create(new JSONObject().put("id", "neu-web").put("name", "Neues Portal"));
        c.create(new JSONObject().put("id", "bleibt-web").put("name", "Bleibt"));
        final CollectionCatalog.Entry removed = c.remove("neu-web", id -> Map.of());
        assertEquals("Neues Portal", removed.name);
        assertFalse(ids(c).contains("neu-web"));
        // the list on disk: only the entry went, the other created collection and the index stay
        final CollectionCatalog reread = catalog(file());
        assertEquals(List.of("bleibt-web", "edelsenior-web", "robot_snippet", "stackfinder-web"), ids(reread));
        assertEquals(Map.of("stackfinder-web", 12L, "robot_snippet", 9L), this.index);
        // the same id again later: a new, empty collection
        assertEquals("neu-web", reread.create(new JSONObject().put("id", "neu-web").put("name", "Wieder da")).id);
        // a second removal of the same id: unknown, nothing else changes
        reread.remove("neu-web", id -> Map.of());
        refused(reread, "neu-web", id -> Map.of(), 404, "collection_not_found");
        assertEquals(List.of("bleibt-web", "edelsenior-web", "robot_snippet", "stackfinder-web"), ids(catalog(file())));
    }

    @Test
    public void pagesThatArrivedSinceTheLastCachedReadKeepTheCollection() throws Exception {
        final CollectionCatalog c = catalog(file());
        c.create(new JSONObject().put("id", "neu-web").put("name", "Neu"));
        assertEquals(0L, c.entries(true).stream().filter(e -> e.id.equals("neu-web")).findFirst().get().documents);
        final int before = this.reads.get();
        this.index.put("neu-web", 5L); // a crawl wrote pages; the catalog's ten-second cache does not know yet
        final ApiException e = refused(c, "neu-web", id -> Map.of(), 409, "collection_has_documents");
        assertEquals(5L, e.toJson().getJSONObject("error").getJSONObject("details").getLong("documents"));
        assertTrue("removal reads the index anew", this.reads.get() > before);
        assertTrue(Files.readString(file()).contains("\"neu-web\""));
    }

    @Test
    public void indexProfileUnknownAndInternalCollectionsStay() throws Exception {
        final CollectionCatalog c = catalog(file());
        refused(c, "stackfinder-web", id -> Map.of(), 409, "collection_has_documents");
        refused(c, "edelsenior-web", id -> Map.of(), 409, "collection_from_profile");
        refused(c, "gibt-es-nicht", id -> Map.of(), 404, "collection_not_found");
        refused(c, "robot_snippet", id -> Map.of(), 404, "collection_not_found");
        refused(c, "../x", id -> Map.of(), 400, "invalid_request");
        refused(c, null, id -> Map.of(), 400, "invalid_request");
        // created and named by a Discovery profile: the profile decides
        c.create(new JSONObject().put("id", "edelsenior-web-neu").put("name", "Neu"));
        final CollectionCatalog both = new CollectionCatalog(file(), () -> this.index, () -> List.of("edelsenior-web-neu"));
        refused(both, "edelsenior-web-neu", id -> Map.of(), 409, "collection_from_profile");
        assertFalse(Files.exists(file().resolveSibling("collections.json.tmp")));
    }

    @Test
    public void aCollectionThatIsStillNamedStaysAndTheUsesAreReported() throws Exception {
        final CollectionCatalog c = catalog(file());
        c.create(new JSONObject().put("id", "neu-web").put("name", "Neu"));
        final String stored = Files.readString(file());
        final Map<String, Integer> uses = new LinkedHashMap<>();
        uses.put("accounts", 1);
        uses.put("agents", 2);
        final ApiException e = refused(c, "neu-web", id -> uses, 409, "collection_in_use");
        final JSONObject details = e.toJson().getJSONObject("error").getJSONObject("details").getJSONObject("uses");
        assertEquals(1, details.getInt("accounts"));
        assertEquals(2, details.getInt("agents"));
        assertEquals(stored, Files.readString(file()));
        // a store that cannot tell refuses as well
        refused(c, "neu-web", id -> {
            throw new ApiException(503, "collection_uses_unavailable", "down");
        }, 503, "collection_uses_unavailable");
        assertEquals(stored, Files.readString(file()));
    }

    @Test
    public void anUnreadableIndexOrADamagedListRemovesNothing() throws Exception {
        final CollectionCatalog ok = catalog(file());
        ok.create(new JSONObject().put("id", "neu-web").put("name", "Neu"));
        final CollectionCatalog down = new CollectionCatalog(file(), () -> {
            throw new ApiException(503, "index_unavailable", "down");
        }, List::of);
        refused(down, "neu-web", id -> Map.of(), 503, "index_unavailable");
        final CollectionCatalog broken = new CollectionCatalog(file(), () -> {
            throw new IllegalStateException("solr");
        }, List::of);
        refused(broken, "neu-web", id -> Map.of(), 503, "index_unavailable");
        assertTrue(Files.readString(file()).contains("\"neu-web\""));
        // the list is damaged after it was read: never overwritten
        Files.writeString(file(), "{ damaged and longer than before ............................................................ }");
        refused(ok, "neu-web", id -> Map.of(), 503, "collection_store_unavailable");
        assertTrue(Files.readString(file()).startsWith("{ damaged"));
    }

    @Test
    public void usesAreExplicitGrantsTheGuestAccessAndCrawlsThatStillRun() throws Exception {
        final AgentStore agents = new AgentStore(this.tmp.newFolder("SETTINGS"), new AtomicLong(1_900_000_000_000L)::get);
        final Agent named = agents.createAgent(agent("Recherche", false, "neu-web"));
        agents.createAgent(agent("Alles", true));
        agents.createAgent(agent("Andere", false, "andere-web"));
        final Agent revoked = agents.createAgent(agent("Alt", false, "neu-web"));
        agents.revoke(revoked.id);
        final List<JSONObject> crawls = List.of(
                new JSONObject().put("state", "running").put("collections", new JSONArray().put("andere-web").put("neu-web")),
                new JSONObject().put("state", "paused").put("collections", new JSONArray().put("neu-web")),
                new JSONObject().put("state", "terminated").put("collections", new JSONArray().put("neu-web")),
                new JSONObject().put("state", "running"));
        final Map<String, Integer> uses = ScoutroActions.collectionUses("neu-web",
                List.of(Scope.of(List.of("neu-web")), Scope.of(List.of("neu-web", "andere-web")), Scope.ALL, Scope.of(List.of("andere-web"))),
                agents.agents(), List.of("neu-web"), crawls);
        assertEquals(Map.of("accounts", 2, "agents", 1, "guest", 1, "crawls", 2), uses);
        assertTrue(named.scope.collections.contains("neu-web"));
        assertEquals(Map.of(), ScoutroActions.collectionUses("frei-web",
                List.of(Scope.ALL, Scope.of(List.of("neu-web"))), agents.agents(), List.of(), crawls));
    }

    private static Agent.Builder agent(final String name, final boolean all, final String... collections) {
        final Agent.Builder b = new Agent.Builder();
        b.name = name;
        b.description = "test agent";
        b.scope = new Agent.Scope(new LinkedHashSet<>(List.of(collections)), all);
        b.actions = new LinkedHashSet<>(List.of("search"));
        b.presetLabel = "research";
        return b;
    }
}
