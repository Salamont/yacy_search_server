/*
 *  KgCollectionSettingsTest
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The knowledge graph settings of each collection (package 6.2): every
 * collection of the catalog appears (a new one by itself, {@code robot_*}
 * never), a collection is switched on and off with only its own keys changed,
 * its vocabulary is set, cleared or reset to the default, nothing else of the
 * settings changes, and a change reopens the running graph with its data kept.
 */
public class KgCollectionSettingsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<String, String> conf = new TreeMap<>();
    private final Map<String, Long> index = new HashMap<>();
    private final AtomicInteger applied = new AtomicInteger();
    private CollectionCatalog catalog;
    private KgCollectionSettings settings;

    @After
    public void close() {
        KgRuntime.stop();
    }

    private KgCollectionSettings.Settings store() {
        return new KgCollectionSettings.Settings() {
            @Override
            public String get(final String key) {
                return KgCollectionSettingsTest.this.conf.get(key);
            }

            @Override
            public void set(final String key, final String value) {
                KgCollectionSettingsTest.this.conf.put(key, value);
            }

            @Override
            public void remove(final String key) {
                KgCollectionSettingsTest.this.conf.remove(key);
            }

            @Override
            public Iterable<String> keys() {
                final List<String> out = new ArrayList<>();
                for (final String k : KgCollectionSettingsTest.this.conf.keySet()) {
                    if (k.startsWith("scoutro.kg.")) out.add(k);
                }
                return out;
            }
        };
    }

    /** The productive shape: two Scoutro collections followed, one with a vocabulary of its own, jobs for one. */
    private void productive() {
        this.conf.put(KgConfig.ENABLED, "true");
        this.conf.put(KgConfig.COLLECTIONS, "edelsenior-web, stackfinder-web");
        this.conf.put(KgConfig.VOCAB_PREFIX + "stackfinder-web", "software");
        this.conf.put(KgConfig.JOBS_COLLECTIONS, "edelsenior-web");
        this.conf.put("search.items", "10");
        this.index.put("edelsenior-web", 1200L);
        this.index.put("stackfinder-web", 300L);
        this.index.put("bauteamcheck-web", 40L);
        this.index.put("robot_kg_crawl", 7L);
        this.catalog = new CollectionCatalog(null, () -> this.index, () -> List.of("discovery-web"));
        this.settings = new KgCollectionSettings(store(), this.catalog, () -> null, () -> {
            this.applied.incrementAndGet();
            return true;
        });
    }

    private static JSONObject row(final JSONObject list, final String collection) throws Exception {
        final JSONArray rows = list.getJSONArray("collections");
        for (int i = 0; i < rows.length(); i++) {
            if (collection.equals(rows.getJSONObject(i).getString("collection"))) return rows.getJSONObject(i);
        }
        return null;
    }

    private static List<String> names(final JSONObject list) throws Exception {
        final List<String> out = new ArrayList<>();
        final JSONArray rows = list.getJSONArray("collections");
        for (int i = 0; i < rows.length(); i++) out.add(rows.getJSONObject(i).getString("collection"));
        return out;
    }

    private JSONObject patch(final String collection, final String body) throws Exception {
        return this.settings.update(collection, new JSONObject(body));
    }

    private int status(final String collection, final String body) throws Exception {
        try {
            patch(collection, body);
            return 200;
        } catch (final ApiException e) {
            return e.status();
        }
    }

    @Test
    public void everyCollectionOfTheCatalogIsListedWithItsSettingsAndRobotNever() throws Exception {
        productive();
        final JSONObject list = this.settings.list();
        assertEquals(List.of("bauteamcheck-web", "discovery-web", "edelsenior-web", "stackfinder-web"), names(list));
        final JSONObject care = row(list, "edelsenior-web");
        assertTrue(care.getBoolean("active"));
        assertEquals("list", care.getString("followedBy"));
        assertEquals("care", care.getString("vocabulary"));
        assertEquals("vocabulary_files", care.getString("vocabularySource"));
        assertEquals(1200L, care.getLong("indexDocuments"));
        final JSONObject sw = row(list, "stackfinder-web");
        assertEquals("software", sw.getString("vocabularySetting"));
        assertEquals("setting", sw.getString("vocabularySource"));
        final JSONObject bau = row(list, "bauteamcheck-web");
        assertFalse(bau.getBoolean("active"));
        assertEquals("not_followed", bau.getString("state"));
        assertEquals("construction", bau.getString("defaultVocabulary"));
        final JSONObject disc = row(list, "discovery-web");
        assertTrue(disc.isNull("vocabulary"));
        assertEquals("none", disc.getString("vocabularySource"));
        assertTrue(list.getJSONArray("vocabularies").toString().contains("\"care\""));
        assertFalse(list.getBoolean("followAll"));
    }

    @Test
    public void aNewCollectionAppearsInTheKgSettingsByItself() throws Exception {
        productive();
        assertEquals(null, row(this.settings.list(), "neues-portal"));
        this.catalog.create(new JSONObject("{\"id\":\"neues-portal\",\"name\":\"Neues Portal\"}"));
        final JSONObject r = row(this.settings.list(), "neues-portal");
        assertNotNull(r);
        assertEquals("Neues Portal", r.getString("name"));
        assertFalse("never switched on by itself", r.getBoolean("active"));
        assertTrue("no vocabulary guessed from its name", r.isNull("vocabulary"));
        assertEquals(0, this.applied.get());
    }

    @Test
    public void switchingOnAndOffChangesOnlyThatCollectionAndKeepsTheOthers() throws Exception {
        productive();
        final Map<String, String> others = new TreeMap<>(this.conf);
        final JSONObject on = patch("bauteamcheck-web", "{\"active\":true}");
        assertTrue(on.getBoolean("changed"));
        assertTrue(on.getBoolean("applied"));
        assertTrue("its pages already in the index come in through the start reconcile", on.getBoolean("backfill"));
        assertEquals("edelsenior-web,stackfinder-web,bauteamcheck-web", this.conf.get(KgConfig.COLLECTIONS));
        assertTrue(on.getJSONObject("collection").getBoolean("active"));
        assertEquals("construction", on.getJSONObject("collection").getString("vocabulary"));
        assertEquals(1, this.applied.get());
        // the same again changes nothing and reopens nothing
        assertFalse(patch("bauteamcheck-web", "{\"active\":true}").getBoolean("changed"));
        assertEquals(1, this.applied.get());
        final JSONObject off = patch("bauteamcheck-web", "{\"active\":false}");
        assertTrue("its graph data are kept", off.getBoolean("kept"));
        assertFalse(off.getBoolean("backfill"));
        assertEquals("edelsenior-web,stackfinder-web", this.conf.get(KgConfig.COLLECTIONS));
        assertEquals("bauteamcheck-web", this.conf.get(KgConfig.INACTIVE_COLLECTIONS));
        assertEquals("inactive", off.getJSONObject("collection").getString("state"));
        assertTrue(off.getJSONObject("collection").getBoolean("inactive"));
        // switching off an existing productive collection and on again leaves the others exactly as they were
        patch("stackfinder-web", "{\"active\":false}");
        assertEquals("edelsenior-web", this.conf.get(KgConfig.COLLECTIONS));
        assertEquals("bauteamcheck-web,stackfinder-web", this.conf.get(KgConfig.INACTIVE_COLLECTIONS));
        assertEquals("its vocabulary stays", "software", this.conf.get(KgConfig.VOCAB_PREFIX + "stackfinder-web"));
        patch("stackfinder-web", "{\"active\":true}");
        patch("bauteamcheck-web", "{\"active\":true}");
        patch("bauteamcheck-web", "{\"active\":false}");
        patch("bauteamcheck-web", "{\"active\":true}");
        others.put(KgConfig.COLLECTIONS, "edelsenior-web,stackfinder-web,bauteamcheck-web");
        assertEquals("no other key changed, the list of those switched off is gone", others, this.conf);
        final KgConfig cfg = KgConfig.read(this.conf::get, this.conf.keySet());
        assertTrue(cfg.follows("edelsenior-web") && cfg.follows("stackfinder-web") && cfg.follows("bauteamcheck-web"));
        assertTrue(cfg.jobsFor(List.of("edelsenior-web")));
    }

    @Test
    public void underEveryCollectionTheListStaysAStar() throws Exception {
        productive();
        this.conf.put(KgConfig.COLLECTIONS, "*");
        final JSONObject off = patch("discovery-web", "{\"active\":false}");
        assertEquals("*", this.conf.get(KgConfig.COLLECTIONS));
        assertEquals("discovery-web", this.conf.get(KgConfig.INACTIVE_COLLECTIONS));
        assertEquals("inactive", off.getJSONObject("collection").getString("followedBy"));
        assertTrue(row(this.settings.list(), "bauteamcheck-web").getBoolean("active"));
        assertEquals("all", row(this.settings.list(), "bauteamcheck-web").getString("followedBy"));
        patch("discovery-web", "{\"active\":true}");
        assertEquals("*", this.conf.get(KgConfig.COLLECTIONS));
        assertFalse(this.conf.containsKey(KgConfig.INACTIVE_COLLECTIONS));
        assertTrue(row(this.settings.list(), "discovery-web").getBoolean("active"));
    }

    @Test
    public void theVocabularyIsSetClearedAndResetToTheDefault() throws Exception {
        productive();
        final JSONObject set = patch("discovery-web", "{\"vocabulary\":\"coaching\"}");
        assertEquals("coaching", this.conf.get(KgConfig.VOCAB_PREFIX + "discovery-web"));
        assertEquals("coaching", set.getJSONObject("collection").getString("vocabulary"));
        assertFalse("not followed: no extraction changes", set.getBoolean("reextract"));
        // "Kein Vokabular": the key with an empty value
        final JSONObject none = patch("edelsenior-web", "{\"vocabulary\":\"\"}");
        assertEquals("", this.conf.get(KgConfig.VOCAB_PREFIX + "edelsenior-web"));
        assertTrue(none.getJSONObject("collection").isNull("vocabulary"));
        assertEquals("setting", none.getJSONObject("collection").getString("vocabularySource"));
        assertTrue("a followed collection's vocabulary changes the extraction", none.getBoolean("reextract"));
        // null: back to the default of the vocabulary files
        final JSONObject reset = patch("edelsenior-web", "{\"vocabulary\":null}");
        assertFalse(this.conf.containsKey(KgConfig.VOCAB_PREFIX + "edelsenior-web"));
        assertEquals("care", reset.getJSONObject("collection").getString("vocabulary"));
        assertEquals("vocabulary_files", reset.getJSONObject("collection").getString("vocabularySource"));
        // both at once
        final JSONObject both = patch("bauteamcheck-web", "{\"active\":true,\"vocabulary\":\"construction\"}");
        assertTrue(both.getJSONObject("collection").getBoolean("active"));
        assertEquals("construction", this.conf.get(KgConfig.VOCAB_PREFIX + "bauteamcheck-web"));
    }

    @Test
    public void invalidRequestsChangeNothing() throws Exception {
        productive();
        final Map<String, String> before = new TreeMap<>(this.conf);
        assertEquals(400, status("robot_kg_crawl", "{\"active\":true}"));
        assertEquals(400, status("bad name!", "{\"active\":true}"));
        assertEquals(404, status("nowhere-web", "{\"active\":true}"));
        assertEquals(400, status("bauteamcheck-web", "{}"));
        assertEquals(400, status("bauteamcheck-web", "{\"active\":\"yes\"}"));
        assertEquals(400, status("bauteamcheck-web", "{\"followed\":true}"));
        assertEquals(400, status("bauteamcheck-web", "{\"vocabulary\":\"astrology\"}"));
        assertEquals(400, status("bauteamcheck-web", "{\"vocabulary\":7}"));
        assertEquals(before, this.conf);
        assertEquals(0, this.applied.get());
        try {
            patch("robot_kg_crawl", "{\"active\":true}");
            fail();
        } catch (final ApiException e) {
            assertEquals("collection_reserved", e.code());
        }
        try {
            patch("bauteamcheck-web", "{\"vocabulary\":\"astrology\"}");
            fail();
        } catch (final ApiException e) {
            assertEquals("vocabulary_unknown", e.code());
        }
    }

    @Test
    public void aCollectionTheSettingsNameButTheCatalogLacksCanStillBeSwitchedOff() throws Exception {
        productive();
        this.conf.put(KgConfig.COLLECTIONS, "edelsenior-web,stackfinder-web,alt-portal");
        assertNotNull(row(this.settings.list(), "alt-portal"));
        assertFalse(row(this.settings.list(), "alt-portal").getBoolean("inCatalog"));
        patch("alt-portal", "{\"active\":false}");
        assertEquals("alt-portal", this.conf.get(KgConfig.INACTIVE_COLLECTIONS));
        assertNotNull("still listed: switched off", row(this.settings.list(), "alt-portal"));
    }

    @Test
    public void aChangeReopensTheRunningGraphWithItsDataAndTheNewSettings() throws Exception {
        productive();
        this.conf.put(KgConfig.COLLECTIONS, "edelsenior-web");
        final KgRuntime first = KgTestSupport.startCurrent(new KgRuntime.Env(this.tmp.getRoot(), this.conf::get, System::currentTimeMillis,
                new KgTestSupport.Probe(), KgStore.SQLITE, false).withKeys(() -> new ArrayList<>(this.conf.keySet())));
        assertEquals(KgRuntime.State.RUNNING, first.state());
        assertFalse(first.config().follows("bauteamcheck-web"));
        final long created = KgTestSupport.count(first, "SELECT count(*) FROM kg_meta");
        this.settings = new KgCollectionSettings(store(), this.catalog, KgRuntime::current, KgRuntime::reopen);
        final JSONObject on = patch("bauteamcheck-web", "{\"active\":true}");
        assertTrue(on.getBoolean("applied"));
        final KgRuntime second = KgRuntime.current();
        assertNotSame(first, second);
        assertEquals(KgRuntime.State.RUNNING, second.state());
        assertTrue("the new settings are in force at once", second.config().follows("bauteamcheck-web"));
        assertTrue("the same graph, opened again", KgTestSupport.count(second, "SELECT count(*) FROM kg_meta") >= created);
        assertTrue(java.util.Set.of("waiting", "following").contains(on.getJSONObject("collection").getString("state")));
        // the list reads the running graph's view
        assertTrue(row(this.settings.list(), "bauteamcheck-web").getBoolean("active"));
        // without a running graph the settings are stored and count at its next start
        KgRuntime.stop();
        final JSONObject off = patch("bauteamcheck-web", "{\"active\":false}");
        assertFalse(off.getBoolean("applied"));
        assertEquals("bauteamcheck-web", this.conf.get(KgConfig.INACTIVE_COLLECTIONS));
    }

    private static int route(final KnowledgeApi api, final String method, final String path, final Map<String, String> q, final String body)
            throws Exception {
        try {
            api.route(method, ("/v1/kg/" + path).split("/"), q, () -> {
                try {
                    return new JSONObject(body);
                } catch (final org.json.JSONException e) {
                    throw new java.io.IOException(e);
                }
            });
            return 200;
        } catch (final ApiException e) {
            return e.status();
        }
    }

    @Test
    public void theSettingsAreAdministratorRoutesOfTheirOwn() throws Exception {
        productive();
        final KnowledgeApi api = new KnowledgeApi(() -> null, () -> this.settings);
        final Map<String, String> none = new HashMap<>();
        assertEquals(200, route(api, "GET", "collections", none, "{}"));
        assertEquals(400, route(api, "GET", "collections", Map.of("collection", "edelsenior-web"), "{}"));
        assertEquals(405, route(api, "POST", "collections", none, "{}"));
        assertEquals(405, route(api, "PATCH", "collections", none, "{\"active\":true}"));
        assertEquals(405, route(api, "GET", "collections/bauteamcheck-web", none, "{}"));
        assertEquals(405, route(api, "DELETE", "collections/bauteamcheck-web", none, "{}"));
        assertEquals(404, route(api, "PATCH", "collections/bauteamcheck-web/more", none, "{\"active\":true}"));
        assertEquals(200, route(api, "PATCH", "collections/bauteamcheck-web", none, "{\"active\":true}"));
        assertTrue(KgConfig.read(this.conf::get, this.conf.keySet()).follows("bauteamcheck-web"));
        assertEquals(503, route(new KnowledgeApi(() -> null, () -> null), "GET", "collections", none, "{}"));
        // no agent read route: the agent path of the graph does not know it
        assertFalse(KnowledgeRead.known(List.of("collections")));
        assertFalse(KnowledgeRead.handles("collections"));
    }

    // ------------------------------------------------------------- LLM tier (6.3)

    private KgCollectionSettings withModel(final String model) {
        return new KgCollectionSettings(store(), this.catalog, () -> null, () -> {
            this.applied.incrementAndGet();
            return true;
        }, () -> model);
    }

    @Test
    public void theLlmTierIsSwitchedPerCollectionWithTheModelOfTheLlmSelection() throws Exception {
        productive();
        this.settings = withModel("OLLAMA/qwen3:8b");
        final JSONObject list = this.settings.list();
        final JSONObject llm = list.getJSONObject("llm");
        assertEquals("the model of the LLM selection (usage knowledge), no second model setting", "OLLAMA/qwen3:8b", llm.getString("model"));
        assertFalse("no collection for the LLM tier: off", llm.getBoolean("active"));
        for (final String c : names(list)) {
            assertFalse("never switched on by itself: " + c, row(list, c).getBoolean("llm"));
            assertFalse(row(list, c).getBoolean("llmActive"));
        }
        final Map<String, String> others = new TreeMap<>(this.conf);
        final JSONObject on = patch("edelsenior-web", "{\"llm\":true}");
        assertTrue(on.getBoolean("changed") && on.getBoolean("applied"));
        assertEquals("edelsenior-web", this.conf.get(KgConfig.LLM_COLLECTIONS));
        assertTrue(on.getJSONObject("collection").getBoolean("llm") && on.getJSONObject("collection").getBoolean("llmActive"));
        assertEquals("OLLAMA/qwen3:8b", on.getString("llmModel"));
        assertTrue(on.isNull("llmWarning"));
        assertTrue(this.settings.list().getJSONObject("llm").getBoolean("active"));
        assertTrue("the deterministic graph stays as it was", row(this.settings.list(), "edelsenior-web").getBoolean("active"));
        // a collection the graph does not follow: stored, said so, not in effect
        final JSONObject notActive = patch("bauteamcheck-web", "{\"llm\":true}");
        assertEquals("collection_not_active", notActive.getString("llmWarning"));
        assertFalse(notActive.getJSONObject("collection").getBoolean("llmActive"));
        assertEquals("edelsenior-web,bauteamcheck-web", this.conf.get(KgConfig.LLM_COLLECTIONS));
        patch("bauteamcheck-web", "{\"llm\":false}");
        final JSONObject off = patch("edelsenior-web", "{\"llm\":false}");
        assertTrue(off.getBoolean("changed"));
        assertFalse(this.conf.containsKey(KgConfig.LLM_COLLECTIONS));
        assertEquals("only the LLM list changed, and it is gone again", others, this.conf);
        assertFalse(patch("edelsenior-web", "{\"llm\":false}").getBoolean("changed"));
        assertEquals(400, status("edelsenior-web", "{\"llm\":\"yes\"}"));
    }

    @Test
    public void withoutAKnowledgeModelTheLlmSwitchIsStoredAndSaysSo() throws Exception {
        productive();
        this.settings = withModel(null);
        final JSONObject on = patch("stackfinder-web", "{\"llm\":true}");
        assertEquals("stackfinder-web", this.conf.get(KgConfig.LLM_COLLECTIONS));
        assertEquals("no_model", on.getString("llmWarning"));
        assertTrue(on.isNull("llmModel"));
        assertFalse(on.getJSONObject("collection").getBoolean("llmActive"));
        final JSONObject llm = this.settings.list().getJSONObject("llm");
        assertTrue(llm.isNull("model"));
        assertFalse("no model: the LLM tier is not active, the deterministic graph is", llm.getBoolean("active"));
        assertTrue(row(this.settings.list(), "stackfinder-web").getBoolean("active"));
    }

    @Test
    public void allCollectionsForTheLlmTierAreNeverRewrittenIntoAList() throws Exception {
        productive();
        this.settings = withModel("OLLAMA/qwen3:8b");
        this.conf.put(KgConfig.LLM_COLLECTIONS, "*");
        final Map<String, String> before = new TreeMap<>(this.conf);
        assertEquals("all", row(this.settings.list(), "edelsenior-web").getString("llmBy"));
        try {
            patch("edelsenior-web", "{\"llm\":false,\"vocabulary\":\"\"}");
            fail("* cannot leave one out");
        } catch (final ApiException e) {
            assertEquals(409, e.status());
            assertEquals("llm_all_collections", e.code());
        }
        assertEquals("nothing written, also not the vocabulary of the same request", before, this.conf);
        assertFalse(patch("edelsenior-web", "{\"llm\":true}").getBoolean("changed"));
        assertEquals(0, this.applied.get());
    }
}
