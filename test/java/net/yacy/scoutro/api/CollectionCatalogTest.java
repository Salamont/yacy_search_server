/* Scoutro contributors, GPL-2.0-or-later. Only temporary files; the index is a fake. */
package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The collection catalog (package 6.1): one definition of the collections, their use, and how a new one is created. */
public class CollectionCatalogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<String, Long> index = new LinkedHashMap<>(Map.of("stackfinder-web", 12L, "Bauteam-Web", 3L, "robot_snippet", 9L));

    private CollectionCatalog catalog(final Path file) {
        return new CollectionCatalog(file, () -> this.index, () -> List.of("edelsenior-web"));
    }

    private static void refused(final CollectionCatalog c, final JSONObject body, final int status, final String code) {
        try {
            c.create(body);
            fail("accepted: " + body);
        } catch (final ApiException e) {
            assertEquals(body.toString(), status, e.status());
            assertEquals(body.toString(), code, e.code());
        }
    }

    @Test
    public void theCatalogIsTheIndexTheCreatedAndTheProfileCollectionsSortedWithYacysOwnMarkedInternal() throws Exception {
        final List<CollectionCatalog.Entry> all = catalog(null).entries(true);
        assertEquals(List.of("Bauteam-Web", "edelsenior-web", "robot_snippet", "stackfinder-web"), all.stream().map(e -> e.id).toList());
        final CollectionCatalog.Entry robot = all.get(2);
        assertTrue(robot.internal());
        final JSONObject json = robot.json();
        for (final String use : List.of("selectable", "chat", "knowledgeGraph", "crawlTarget")) assertFalse(use, json.getBoolean(use));
        assertEquals(List.of("Bauteam-Web", "edelsenior-web", "stackfinder-web"), catalog(null).selectable());
        assertFalse("internal: never a choice", catalog(null).known("robot_snippet"));
        assertTrue("a Discovery profile collection without pages exists", catalog(null).known("edelsenior-web"));
        assertEquals(12L, all.get(3).documents);
        assertEquals("[\"index\"]", all.get(3).json().getJSONArray("sources").toString());
    }

    @Test
    public void aCreatedCollectionIsChoosableAtOnceWithNameAndDescriptionAndSurvivesARestart() throws Exception {
        final Path file = this.tmp.getRoot().toPath().resolve("DATA/SCOUTRO/collections.json");
        final CollectionCatalog c = catalog(file);
        final CollectionCatalog.Entry e = c.create(new JSONObject().put("id", "mein-neues-portal").put("name", "Mein neues Portal")
                .put("description", "Portal für  Tests"));
        assertEquals("mein-neues-portal", e.id);
        assertEquals("Mein neues Portal", e.name);
        assertEquals("Portal für Tests", e.description);
        assertEquals(0L, e.documents);
        assertTrue(e.created && !e.indexed);
        assertTrue("selectable before the index holds a page of it", c.known("mein-neues-portal"));
        assertTrue(c.selectable().contains("mein-neues-portal"));
        // a new process (another catalog on the same file): still there, nothing else
        final CollectionCatalog restarted = catalog(file);
        assertTrue(restarted.known("mein-neues-portal"));
        assertEquals("Mein neues Portal", restarted.entries(true).stream().filter(x -> x.id.equals("mein-neues-portal")).findFirst().get().name);
        final JSONObject stored = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals("scoutro.collections.v1", stored.getString("schema"));
        assertEquals(1, stored.getJSONArray("collections").length());
        // the other catalog sees what this one creates (the file is read again when it changed)
        restarted.create(new JSONObject().put("id", "zweites-portal").put("name", "Zweites Portal"));
        assertTrue(c.known("zweites-portal"));
        // once the index holds pages, the entry is both
        this.index.put("mein-neues-portal", 5L);
        final CollectionCatalog fresh = catalog(file);
        final CollectionCatalog.Entry both = fresh.entries(true).stream().filter(x -> x.id.equals("mein-neues-portal")).findFirst().get();
        assertEquals(5L, both.documents);
        assertEquals("[\"index\",\"created\"]", both.json().getJSONArray("sources").toString());
    }

    @Test
    public void duplicatesInvalidAndReservedIdsAreRefusedAndLeaveNoPhantom() throws Exception {
        final Path file = this.tmp.getRoot().toPath().resolve("collections.json");
        final CollectionCatalog c = catalog(file);
        refused(c, new JSONObject().put("id", "stackfinder-web").put("name", "Doppelt"), 409, "collection_exists");
        refused(c, new JSONObject().put("id", "bauteam-web").put("name", "Nur anders geschrieben"), 409, "collection_exists");
        refused(c, new JSONObject().put("id", "edelsenior-web").put("name", "Profil"), 409, "collection_exists");
        for (final String bad : List.of("bad name", "-start", "end-", "a", "a--b", "Upper", "ümlaut", "x".repeat(65), "with_underscore")) {
            refused(c, new JSONObject().put("id", bad).put("name", "N"), 400, "collection_id_invalid");
        }
        for (final String reserved : List.of("robot_snippetz", "user", "all", "None")) {
            refused(c, new JSONObject().put("id", reserved).put("name", "N"), 400, "collection_reserved");
        }
        refused(c, new JSONObject().put("id", "gut-web"), 400, "invalid_request");
        refused(c, new JSONObject().put("id", "gut-web").put("name", " "), 400, "invalid_request");
        refused(c, new JSONObject().put("id", "gut-web").put("name", "x".repeat(81)), 400, "invalid_request");
        refused(c, new JSONObject().put("id", "gut-web").put("name", "Zei\u0007le"), 400, "invalid_request");
        refused(c, new JSONObject().put("id", "gut-web").put("name", "N").put("owner", "x"), 400, "invalid_request");
        assertFalse("nothing was stored", Files.exists(file));
        assertFalse(c.known("gut-web"));
        // the first create of a name wins; the second is a duplicate
        c.create(new JSONObject().put("id", "gut-web").put("name", "Gut"));
        refused(c, new JSONObject().put("id", "gut-web").put("name", "Gut"), 409, "collection_exists");
        assertEquals(1, new JSONObject(Files.readString(file)).getJSONArray("collections").length());
    }

    @Test
    public void withoutAnIdTheIdComesFromTheName() throws Exception {
        assertEquals("mein-neues-portal", CollectionCatalog.suggest("Mein neues Portal"));
        assertEquals("cafe-groesse-uebersicht", CollectionCatalog.suggest("  Café Größe – Übersicht! "));
        assertEquals("kitas-nrw-2026", CollectionCatalog.suggest("Kitas NRW 2026"));
        assertEquals("", CollectionCatalog.suggest("!!!"));
        assertEquals(64, CollectionCatalog.suggest("a".repeat(30) + " " + "b".repeat(40)).length());
        assertFalse(CollectionCatalog.suggest("a".repeat(63) + " b").endsWith("-"));
        assertEquals("ein-portal", catalog(null).create(new JSONObject().put("name", "Ein Portal")).id);
    }

    @Test
    public void aDamagedListIsNeverOverwrittenAndAnUnreadableIndexKeepsTheCreatedOnes() throws Exception {
        final Path file = this.tmp.getRoot().toPath().resolve("collections.json");
        Files.writeString(file, "{ damaged");
        refused(catalog(file), new JSONObject().put("id", "neu-web").put("name", "Neu"), 503, "collection_store_unavailable");
        assertEquals("{ damaged", Files.readString(file));
        // the index is down: the API says so, the pages keep the created and profile collections
        final Path ok = this.tmp.getRoot().toPath().resolve("ok.json");
        catalog(ok).create(new JSONObject().put("id", "neu-web").put("name", "Neu"));
        final CollectionCatalog down = new CollectionCatalog(ok, () -> {
            throw new ApiException(503, "index_unavailable", "down");
        }, () -> List.of("edelsenior-web"));
        try {
            down.entries(true);
            fail();
        } catch (final ApiException e) {
            assertEquals("index_unavailable", e.code());
        }
        assertEquals(List.of("edelsenior-web", "neu-web"), down.selectable());
        try {
            down.known("stackfinder-web");
            fail("a collection of the index must not count as unknown while the index is down");
        } catch (final ApiException e) {
            assertEquals("index_unavailable", e.code());
        }
        refused(down, new JSONObject().put("id", "noch-eins").put("name", "N"), 503, "index_unavailable");
    }

    @Test
    public void theIndexIsReadAtMostEveryTenSeconds() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final CollectionCatalog c = new CollectionCatalog(null, () -> {
            reads.incrementAndGet();
            return this.index;
        }, List::of);
        c.selectable();
        c.selectable();
        c.entries(true);
        assertEquals(1, reads.get());
    }

    @Test
    public void theApiListsTheCatalogWithUseFlagsGraphStateAndTheRightToCreate() throws Exception {
        final FakeUpstream yacy = new FakeUpstream();
        final ScoutroActions actions = new ScoutroActions(yacy);
        final JSONObject admin = actions.collections(true);
        assertTrue(admin.getBoolean("canCreate"));
        assertFalse("no free names anywhere", admin.getBoolean("allowNew"));
        final JSONArray items = admin.getJSONArray("collections");
        assertEquals("checkthecoach-web", items.getJSONObject(0).getString("id"));
        final JSONObject robot = items.getJSONObject(3);
        assertEquals("robot_internal", robot.getString("id"));
        assertTrue(robot.getBoolean("internal") && !robot.getBoolean("selectable"));
        assertTrue(admin.toString(), items.getJSONObject(0).has("graph"));
        assertFalse(actions.collections(false).getBoolean("canCreate"));
        final JSONObject created = actions.collectionCreate(new JSONObject().put("id", "neues-portal").put("name", "Neues Portal"));
        assertEquals("neues-portal", created.getJSONObject("collection").getString("id"));
        assertEquals("Neues Portal", created.getJSONObject("collection").getString("name"));
        boolean listed = false;
        final JSONArray after = actions.collections(true).getJSONArray("collections");
        for (int i = 0; i < after.length(); i++) {
            listed |= "neues-portal".equals(after.getJSONObject(i).getString("id"));
        }
        assertTrue("listed at once", listed);
    }

    @Test
    public void aCrawlWritesOnlyIntoAnExistingCollectionAndANewOneOnceCreated() throws Exception {
        final FakeUpstream yacy = new FakeUpstream();
        final ScoutroActions actions = new ScoutroActions(yacy);
        final JSONObject body = Json.obj("url", "https://example.com/", "collection", "neuesportal-web");
        try {
            actions.crawlStartAdmin(body, "k1");
            fail("a crawl into an unknown collection");
        } catch (final ApiException e) {
            assertEquals(400, e.status());
            assertEquals("collection_unknown", e.code());
        }
        assertTrue("YaCy was not asked", yacy.calls("Crawler_p.json").isEmpty());
        try {
            actions.crawlStartAdmin(Json.obj("url", "https://example.com/", "collection", "robot_internal"), "k2");
            fail("a crawl into an internal collection");
        } catch (final ApiException e) {
            assertEquals("collection_unknown", e.code());
        }
        actions.collectionCreate(new JSONObject().put("id", "neuesportal-web").put("name", "Neues Portal"));
        actions.crawlStartAdmin(body, "k3");
        assertEquals("neuesportal-web", yacy.last("Crawler_p.json").params.get("collection"));
    }
}
