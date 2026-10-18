/* Scoutro contributors, GPL-2.0-or-later. Only temporary files; the index is a fake of pages with their collections. */
package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Hiding a collection, showing it again and deleting its pages (0.10.3, Dashboard "−"): hidden it leaves every choice and
 * keeps its pages; deleted are only pages of no other collection, confirmed with the id; a Discovery profile, an account,
 * agent, guest access or running crawl that names it refuses both.
 */
public class CollectionHideTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Pages of the fake index: id → collections. */
    private final Map<String, Set<String>> pages = new LinkedHashMap<>();
    private boolean broken;

    private final CollectionCatalog.Index index = new CollectionCatalog.Index() {
        @Override
        public Map<String, Long> counts() {
            final Map<String, Long> out = new TreeMap<>();
            for (final Set<String> cs : CollectionHideTest.this.pages.values()) {
                for (final String c : cs) out.merge(c, 1L, Long::sum);
            }
            return out;
        }

        @Override
        public Map<String, Long> sharedWith(final String id) {
            final Map<String, Long> out = new TreeMap<>();
            for (final Set<String> cs : CollectionHideTest.this.pages.values()) {
                if (!cs.contains(id)) continue;
                for (final String c : cs) {
                    if (!c.equals(id)) out.merge(c, 1L, Long::sum);
                }
            }
            return out;
        }

        @Override
        public long deleteOnly(final String id, final Collection<String> others) {
            if (CollectionHideTest.this.broken) throw new IllegalStateException("solr");
            final List<String> gone = new ArrayList<>();
            CollectionHideTest.this.pages.forEach((page, cs) -> {
                if (cs.contains(id) && cs.stream().noneMatch(others::contains)) gone.add(page);
            });
            gone.forEach(CollectionHideTest.this.pages::remove);
            return gone.size();
        }
    };

    private Path file() {
        return this.tmp.getRoot().toPath().resolve("collections.json");
    }

    private CollectionCatalog catalog(final String... profiles) {
        return new CollectionCatalog(file(), this.index, () -> List.of(profiles));
    }

    private void page(final String id, final String... collections) {
        this.pages.put(id, Set.of(collections));
    }

    private static CollectionCatalog.Entry entry(final CollectionCatalog c, final String id) throws ApiException {
        return c.entries(true).stream().filter(e -> e.id.equals(id)).findFirst().orElse(null);
    }

    private static ApiException refused(final ThrowingRunnable r, final int status, final String code) {
        try {
            r.run();
            fail("not refused: " + code);
            return null;
        } catch (final ApiException e) {
            assertEquals(code, e.code());
            assertEquals(status, e.status());
            return e;
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    public void aHiddenCollectionLeavesEveryChoiceKeepsItsPagesAndCanBeShownAgain() throws Exception {
        page("p1", "test-web");
        page("p2", "test-web");
        page("p3", "kunden-web");
        final CollectionCatalog c = catalog();
        final CollectionCatalog.Outcome o = c.hide("test-web", id -> Map.of());
        assertTrue(o.hidden);
        assertFalse(o.removed);
        assertEquals(2L, o.kept);
        assertEquals(0L, o.deleted);
        assertEquals(3, this.pages.size());
        final CollectionCatalog.Entry e = entry(c, "test-web");
        assertTrue(e.hidden);
        assertFalse(e.selectable());
        assertEquals(2L, e.documents);
        assertTrue(e.json().getBoolean("hidden"));
        assertFalse(e.json().getBoolean("selectable"));
        assertEquals(List.of("kunden-web"), c.selectable());
        assertFalse("no filter, chat or grant accepts it", c.known("test-web"));
        // kept apart from the created collections, so that an older version never rewrites it
        assertFalse(Files.exists(file()));
        assertTrue(Files.readString(file().resolveSibling("collections-hidden.json")).contains("\"test-web\""));
        assertTrue("survives a restart", entry(catalog(), "test-web").hidden);
        refused(() -> catalog().create(new JSONObject().put("id", "test-web").put("name", "Neu")), 409, "collection_hidden");
        // hiding again changes nothing
        final String stored = Files.readString(file().resolveSibling("collections-hidden.json"));
        catalog().hide("test-web", id -> Map.of());
        assertEquals(stored, Files.readString(file().resolveSibling("collections-hidden.json")));
        // shown again: every choice offers it, its pages were never touched
        final JSONObject record = catalog().show("test-web");
        assertFalse(record.getBoolean("graphSwitchedOff"));
        final CollectionCatalog shown = catalog();
        assertEquals(List.of("kunden-web", "test-web"), shown.selectable());
        assertTrue(shown.known("test-web"));
        assertEquals(3, this.pages.size());
        refused(() -> shown.show("test-web"), 404, "collection_not_hidden");
    }

    @Test
    public void aProfileAUseOrYaCysOwnCollectionRefusesHidingAndDeleting() throws Exception {
        page("p1", "test-web");
        page("p2", "profil-web");
        page("p3", "robot_snippet");
        final CollectionCatalog c = catalog("profil-web");
        refused(() -> c.hide("profil-web", id -> Map.of()), 409, "collection_from_profile");
        refused(() -> c.deletePages("profil-web", "profil-web", id -> Map.of()), 409, "collection_from_profile");
        final ApiException e = refused(() -> c.hide("test-web", id -> Map.of("accounts", 1, "crawls", 1)), 409, "collection_in_use");
        assertEquals(1, e.toJson().getJSONObject("error").getJSONObject("details").getJSONObject("uses").getInt("crawls"));
        refused(() -> c.deletePages("test-web", "test-web", id -> Map.of("agents", 2)), 409, "collection_in_use");
        refused(() -> c.hide("robot_snippet", id -> Map.of()), 404, "collection_not_found");
        refused(() -> c.hide("gibt-es-nicht", id -> Map.of()), 404, "collection_not_found");
        refused(() -> c.hide("../x", id -> Map.of()), 400, "invalid_request");
        assertEquals(3, this.pages.size());
        assertFalse(Files.exists(file().resolveSibling("collections-hidden.json")));
    }

    @Test
    public void deletingRemovesOnlyPagesOfNoOtherCollectionAndHidesTheRest() throws Exception {
        page("p1", "test-web");
        page("p2", "test-web", "kunden-web");
        page("p3", "test-web");
        page("p4", "kunden-web");
        final CollectionCatalog c = catalog();
        refused(() -> c.deletePages("test-web", "Test-Web", id -> Map.of()), 400, "confirm_mismatch");
        refused(() -> c.deletePages("test-web", null, id -> Map.of()), 400, "confirm_mismatch");
        assertEquals("nothing deleted without the exact id", 4, this.pages.size());
        final CollectionCatalog.Outcome o = c.deletePages("test-web", "test-web", id -> Map.of());
        assertEquals(2L, o.deleted);
        assertEquals(1L, o.kept);
        assertEquals(Map.of("kunden-web", 1L), o.sharedWith);
        assertTrue("the shared page keeps it: hidden", o.hidden);
        assertFalse(o.removed);
        assertEquals(Set.of("p2", "p4"), this.pages.keySet());
        assertEquals("the other collection keeps every page", Set.of("test-web", "kunden-web"), this.pages.get("p2"));
        assertTrue(entry(c, "test-web").hidden);
        assertEquals(List.of("kunden-web"), c.selectable());
        final JSONObject json = o.json();
        assertEquals(2L, json.getLong("deletedPages"));
        assertEquals(1L, json.getJSONObject("sharedWith").getLong("kunden-web"));
    }

    @Test
    public void withoutPagesLeftTheCollectionGoesFromBothListsAndCanBeCreatedAgain() throws Exception {
        final CollectionCatalog c = catalog();
        c.create(new JSONObject().put("id", "test-web").put("name", "Test"));
        page("p1", "test-web");
        page("p2", "test-web");
        page("p3", "kunden-web");
        c.hide("test-web", id -> Map.of());
        final CollectionCatalog.Outcome o = catalog().deletePages("test-web", "test-web", id -> Map.of());
        assertTrue(o.removed);
        assertFalse(o.hidden);
        assertEquals(2L, o.deleted);
        assertEquals(Set.of("p3"), this.pages.keySet());
        assertNull(entry(catalog(), "test-web"));
        assertFalse(Files.readString(file()).contains("\"test-web\""));
        assertFalse(Files.readString(file().resolveSibling("collections-hidden.json")).contains("\"test-web\""));
        assertEquals("test-web", catalog().create(new JSONObject().put("id", "test-web").put("name", "Wieder")).id);
    }

    @Test
    public void aHiddenCollectionWithoutPagesStaysListedUntilItIsRemoved() throws Exception {
        page("p1", "alt-web");
        final CollectionCatalog c = catalog();
        c.hide("alt-web", id -> Map.of());
        this.pages.clear(); // its pages were deleted elsewhere (YaCy's index administration)
        final CollectionCatalog.Entry e = entry(catalog(), "alt-web");
        assertTrue(e.hidden);
        assertEquals(0L, e.documents);
        assertEquals("alt-web", catalog().remove("alt-web", id -> Map.of()).id);
        assertNull(entry(catalog(), "alt-web"));
    }

    @Test
    public void aFailingIndexDeletesAndHidesNothing() throws Exception {
        page("p1", "test-web");
        this.broken = true;
        refused(() -> catalog().deletePages("test-web", "test-web", id -> Map.of()), 503, "index_unavailable");
        assertEquals(1, this.pages.size());
        assertFalse(entry(catalog(), "test-web").hidden);
        // without the deletion of the running peer (a fake without it) nothing is deleted either
        final CollectionCatalog plain = new CollectionCatalog(this.tmp.getRoot().toPath().resolve("b.json"), () -> Map.of("x-web", 1L), List::of);
        refused(() -> plain.deletePages("x-web", "x-web", id -> Map.of()), 503, "index_unavailable");
    }

    @Test
    public void theQueryKeepsEveryPageOfAnotherCollection() {
        assertEquals("collection_sxt:\"test-web\" AND -collection_sxt:\"kunden-web\" AND -collection_sxt:\"robot_x\"",
                CollectionCatalog.onlyQuery("test-web", List.of("kunden-web", "robot_x", "test-web", "bad \" id")));
        assertEquals("collection_sxt:\"test-web\"", CollectionCatalog.onlyQuery("test-web", List.of()));
    }

    /**
     * WP-A: collection decisions that put pages into a collection (a reassign records them) keep hiding possible; they
     * refuse removing it and deleting all of its pages, but not deleting its own pages while shared ones keep it listed.
     */
    @Test
    public void decisionsKeepHidingPossibleAndRefuseOnlyWhatWouldLeaveTheirPagesWithoutACollection() throws Exception {
        page("p1", "test-web");
        page("p2", "test-web", "kunden-web");
        page("p3", "alleine-web");
        final ScoutroActions actions = new ScoutroActions(new FakeUpstream(), catalog());
        actions.uses = id -> Map.of();
        actions.decisionUses = id -> "kunden-web".equals(id) ? 0 : 2;
        actions.graphSwitch = new ScoutroActions.GraphSwitch() {
            @Override public boolean off(final String id) { return false; }
            @Override public void on(final String id) { }
        };
        assertTrue("hiding: the pages stay, the decisions go on applying", actions.collectionHide("test-web").getBoolean("hidden"));
        actions.collectionShow("test-web");
        // all pages of alleine-web are its own: deleting them would take it from the lists while decisions name it
        final ApiException all = refused(() -> actions.collectionDeletePages("alleine-web", new JSONObject().put("confirm", "alleine-web")), 409,
                "collection_in_use");
        assertEquals(2, all.toJson().getJSONObject("error").getJSONObject("details").getJSONObject("uses").getInt("decisions"));
        assertTrue("nothing was deleted", this.pages.containsKey("p3"));
        // test-web keeps a shared page: its own page goes, it stays listed (hidden)
        final JSONObject r = actions.collectionDeletePages("test-web", new JSONObject().put("confirm", "test-web"));
        assertTrue(r.getBoolean("hidden"));
        assertFalse(this.pages.containsKey("p1"));
        // an empty collection created here: removing it is refused while decisions name it
        actions.collectionCreate(new JSONObject().put("id", "neu-web").put("name", "Neu"));
        final ApiException removed = refused(() -> actions.collectionRemove("neu-web"), 409, "collection_in_use");
        assertEquals(2, removed.toJson().getJSONObject("error").getJSONObject("details").getJSONObject("uses").getInt("decisions"));
        actions.decisionUses = id -> 0;
        assertTrue(actions.collectionRemove("neu-web").getBoolean("removed"));
    }

    @Test
    public void onlyTheAdministratorSeesAHiddenCollectionInTheList() throws Exception {
        page("p1", "test-web");
        page("p2", "kunden-web");
        final ScoutroActions actions = new ScoutroActions(new FakeUpstream(), catalog());
        actions.uses = id -> Map.of();
        actions.graphSwitch = new ScoutroActions.GraphSwitch() {
            @Override public boolean off(final String id) { return false; }
            @Override public void on(final String id) { }
        };
        actions.collectionHide("test-web");
        final List<String> admin = new ArrayList<>(), others = new ArrayList<>();
        final org.json.JSONArray a = actions.collections(true).getJSONArray("collections"), o = actions.collections(false).getJSONArray("collections");
        for (int i = 0; i < a.length(); i++) admin.add(a.getJSONObject(i).getString("id") + (a.getJSONObject(i).getBoolean("hidden") ? " hidden" : ""));
        for (int i = 0; i < o.length(); i++) others.add(o.getJSONObject(i).getString("id"));
        assertEquals(List.of("kunden-web", "test-web hidden"), admin);
        assertEquals(List.of("kunden-web"), others);
    }

    @Test
    public void hidingSwitchesTheGraphOffAndShowingSwitchesItOnAgainOnlyIfHidingDid() throws Exception {
        page("p1", "test-web");
        page("p2", "frei-web");
        final ScoutroActions actions = new ScoutroActions(new FakeUpstream(), catalog());
        actions.uses = id -> Map.of();
        final List<String> calls = new ArrayList<>();
        actions.graphSwitch = new ScoutroActions.GraphSwitch() {
            @Override public boolean off(final String id) { calls.add("off " + id); return "test-web".equals(id); }
            @Override public void on(final String id) { calls.add("on " + id); }
        };
        JSONObject r = actions.collectionHide("test-web");
        assertTrue(r.getBoolean("hidden"));
        assertTrue(r.getJSONObject("graph").getBoolean("switchedOff"));
        assertTrue(catalog().hiddenRecord("test-web").getBoolean("graphSwitchedOff"));
        r = actions.collectionHide("frei-web"); // the graph did not follow it
        assertFalse(r.getJSONObject("graph").getBoolean("switchedOff"));
        assertTrue(actions.collectionShow("test-web").getJSONObject("graph").getBoolean("switchedOn"));
        assertFalse(actions.collectionShow("frei-web").getJSONObject("graph").getBoolean("switchedOn"));
        assertEquals(List.of("off test-web", "off frei-web", "on test-web"), calls);
        // a refused switch (a backup runs): the collection is hidden all the same and the answer says so
        actions.graphSwitch = new ScoutroActions.GraphSwitch() {
            @Override public boolean off(final String id) throws ApiException { throw new ApiException(409, "kg_busy", "backup"); }
            @Override public void on(final String id) { }
        };
        r = actions.collectionHide("test-web");
        assertTrue(r.getBoolean("hidden"));
        assertEquals("kg_busy", r.getJSONObject("graph").getString("error"));
        // deleting: unknown fields are refused, the id confirms
        refused(() -> actions.collectionDeletePages("frei-web", new JSONObject().put("confirm", "frei-web").put("all", true)), 400, "invalid_request");
        r = actions.collectionDeletePages("frei-web", new JSONObject().put("confirm", "frei-web"));
        assertTrue(r.getBoolean("removed"));
        assertEquals(1L, r.getLong("deletedPages"));
        assertTrue(r.isNull("graph"));
    }
}
