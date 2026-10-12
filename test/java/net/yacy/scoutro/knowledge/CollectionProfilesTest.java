/*
 *  CollectionProfilesTest
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

package net.yacy.scoutro.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.vocab.Categories;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * Collections are named by the operator and get a vocabulary profile only when they are given one: any name works, a name
 * alone gives nothing, the profile brings its categories and its facility kinds of the LLM tier, and a page in several
 * collections gets the union of their vocabularies and kinds.
 */
public class CollectionProfilesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void restore() {
        KgVocabularies.configure(new File(KgVocabularies.DEFAULTS), null);
    }

    private static Categories categories() {
        return KgVocabularies.get().categories;
    }

    private static Set<String> union(final Set<String> a, final Set<String> b) {
        final Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }

    /** (1) An arbitrary name gets the vocabulary it is given and the kinds of that profile; without one it gets nothing. */
    @Test
    public void anyNameGetsTheVocabularyAndTheKindsOfTheProfileItIsGiven() {
        final Map<String, String> s = KgTestSupport.enabled(KgConfig.COLLECTIONS, "alpha_x,example-care", KgConfig.LLM_COLLECTIONS, "*",
                KgConfig.VOCAB_PREFIX + "alpha_x", "construction");
        final KgConfig cfg = KgConfig.read(s::get, s.keySet());
        assertTrue(cfg.problems().toString(), cfg.valid());
        assertEquals("construction", cfg.vocabularyOf("alpha_x", categories().collections));
        assertEquals(categories().llmKinds("construction"), cfg.llmKinds(List.of("alpha_x")));
        assertTrue(cfg.llmKinds(List.of("alpha_x")).containsAll(List.of("roofingcontractor", "plumber", "generalcontractor")));
        assertEquals(categories().llmKinds("construction"), cfg.llmKinds.get("alpha_x"));
        // a name that sounds like a profile gets nothing by it
        assertNull(cfg.vocabularyOf("example-care", categories().collections));
        assertTrue(cfg.llmKinds(List.of("example-care")).isEmpty());
        // the kinds setting still replaces the profile's kinds
        final Map<String, String> own = KgTestSupport.enabled(KgConfig.COLLECTIONS, "alpha_x", KgConfig.LLM_COLLECTIONS, "alpha_x",
                KgConfig.VOCAB_PREFIX + "alpha_x", "construction", "scoutro.kg.llm.kinds.alpha_x", "electrician");
        assertEquals(Set.of("electrician"), KgConfig.read(own::get, own.keySet()).llmKinds(List.of("alpha_x")));
        // a collection the LLM tier does not read gets no kinds
        final Map<String, String> off = KgTestSupport.enabled(KgConfig.COLLECTIONS, "alpha_x", KgConfig.VOCAB_PREFIX + "alpha_x", "construction");
        assertTrue(KgConfig.read(off::get, off.keySet()).llmKinds(List.of("alpha_x")).isEmpty());
    }

    /** (1) The former Scoutro collections are names like any other: on a new installation they get no vocabulary and no kinds. */
    @Test
    public void theFormerScoutroCollectionsGetNothingOnANewInstallation() {
        assertTrue("the shipped defaults map no collection", categories().collections.isEmpty());
        assertTrue(categories().collectionLlmKinds.isEmpty());
        final Map<String, String> s = KgTestSupport.enabled(KgConfig.COLLECTIONS, CollectionMigrationTest.FOUR, KgConfig.LLM_COLLECTIONS, "*");
        final KgConfig cfg = KgConfig.read(s::get, s.keySet());
        for (final String c : CollectionMigrationTest.FOUR.split(",")) {
            assertNull(c, cfg.vocabularyOf(c, categories().collections));
            assertTrue(c, cfg.llmKinds(List.of(c)).isEmpty());
            assertTrue(c, cfg.llmKinds.get(c).isEmpty());
        }
        assertTrue(cfg.migratedCollections.isEmpty());
        assertEquals("{}||{}", cfg.extractionKey());
    }

    /** (1) An operator file maps any name to a profile, and the profile brings its kinds. */
    @Test
    public void anOperatorFileMapsAnyNameToAProfile() throws Exception {
        final File dir = this.tmp.newFolder("vocabulary");
        Files.write(new File(dir, "portals.json").toPath(), ("{\"schema\":\"scoutro.kg.categories\",\"collections\":{\"my-portal-web\":"
                + "\"care\",\"alpha_x\":\"construction\"}}").getBytes(StandardCharsets.UTF_8));
        KgVocabularies.configure(new File(KgVocabularies.DEFAULTS), dir);
        assertTrue(KgVocabularies.get().problems.toString(), KgVocabularies.get().problems.isEmpty());
        final Map<String, String> s = KgTestSupport.enabled(KgConfig.COLLECTIONS, "*", KgConfig.LLM_COLLECTIONS, "*");
        final KgConfig cfg = KgConfig.read(s::get, s.keySet());
        assertEquals("care", cfg.vocabularyOf("my-portal-web", categories().collections));
        assertEquals(categories().llmKinds("care"), cfg.llmKinds(List.of("my-portal-web")));
        assertEquals(categories().llmKinds("construction"), cfg.llmKinds(List.of("alpha_x")));
        // a setting overrides the file, also to none
        final Map<String, String> none = KgTestSupport.enabled(KgConfig.COLLECTIONS, "*", KgConfig.LLM_COLLECTIONS, "*",
                KgConfig.VOCAB_PREFIX + "my-portal-web", "");
        final KgConfig off = KgConfig.read(none::get, none.keySet());
        assertNull(off.vocabularyOf("my-portal-web", categories().collections));
        assertTrue(off.llmKinds(List.of("my-portal-web")).isEmpty());
    }

    /** (2) A page in several collections gets the union of their vocabularies and of their kinds. */
    @Test
    public void aPageInSeveralCollectionsGetsTheUnionOfVocabulariesAndKinds() {
        final Map<String, String> s = KgTestSupport.enabled(KgConfig.COLLECTIONS, "alpha_x,beta_y,gamma_z", KgConfig.LLM_COLLECTIONS, "*",
                KgConfig.VOCAB_PREFIX + "alpha_x", "construction", KgConfig.VOCAB_PREFIX + "beta_y", "care");
        final KgConfig cfg = KgConfig.read(s::get, s.keySet());
        final List<String> page = List.of("alpha_x", "beta_y", "gamma_z");
        assertEquals(Set.of("care", "construction"), cfg.vocabulariesOf(page, categories().collections));
        assertEquals(union(categories().llmKinds("construction"), categories().llmKinds("care")), cfg.llmKinds(page));
        // a collection with its own kinds adds those instead of its profile's
        final Map<String, String> own = KgTestSupport.enabled(KgConfig.COLLECTIONS, "alpha_x,beta_y", KgConfig.LLM_COLLECTIONS, "*",
                KgConfig.VOCAB_PREFIX + "alpha_x", "construction", KgConfig.VOCAB_PREFIX + "beta_y", "care", "scoutro.kg.llm.kinds.beta_y",
                "hospital");
        assertEquals(union(categories().llmKinds("construction"), Set.of("hospital")),
                KgConfig.read(own::get, own.keySet()).llmKinds(List.of("alpha_x", "beta_y")));
        // only the collections the LLM tier reads count
        final Map<String, String> one = KgTestSupport.enabled(KgConfig.COLLECTIONS, "alpha_x,beta_y", KgConfig.LLM_COLLECTIONS, "beta_y",
                KgConfig.VOCAB_PREFIX + "alpha_x", "construction", KgConfig.VOCAB_PREFIX + "beta_y", "care");
        assertEquals(categories().llmKinds("care"), KgConfig.read(one::get, one.keySet()).llmKinds(List.of("alpha_x", "beta_y")));
    }

    /** The profiles carry the kinds the former Scoutro collections had by their names; kinds never change the vocabulary version. */
    @Test
    public void theProfilesCarryTheFormerKindsAndKindsNeverReextract() throws Exception {
        for (final Map.Entry<String, String> e : CollectionMigration.FORMER_VOCABULARIES.entrySet()) {
            assertEquals(e.getKey(), CollectionMigration.FORMER_KINDS.get(e.getKey()), categories().llmKinds(e.getValue()));
        }
        final JSONObject shipped = new JSONObject(new String(Files.readAllBytes(new File(KgVocabularies.DEFAULTS,
                KgVocabularies.CATEGORIES_FILE).toPath()), StandardCharsets.UTF_8));
        final JSONObject without = new JSONObject(shipped.toString());
        for (final String v : without.getJSONObject("vocabularies").keySet()) {
            without.getJSONObject("vocabularies").getJSONObject(v).remove("llm_kinds");
        }
        final JSONObject other = new JSONObject(shipped.toString());
        other.getJSONObject("vocabularies").getJSONObject("care").put("llm_kinds", new JSONArray(List.of("hospital")));
        other.put("collection_llm_kinds", new JSONObject().put("alpha_x", new JSONArray(List.of("store"))));
        final String version = read(shipped).version;
        assertEquals(version, read(without).version);
        assertEquals(version, read(other).version);
        assertEquals(Set.of("hospital"), read(other).llmKinds("care"));
        assertEquals(Set.of("store"), read(other).collectionLlmKinds.get("alpha_x"));
        assertTrue(read(without).llmKinds("care").isEmpty());
        // a collection map is part of the version: a new map re-extracts the pages concerned
        final JSONObject mapped = new JSONObject(shipped.toString());
        mapped.put("collections", new JSONObject().put("alpha_x", "construction"));
        assertFalse(version.equals(read(mapped).version));
        // an invalid kind is left out and reported
        final JSONObject bad = new JSONObject(shipped.toString());
        bad.getJSONObject("vocabularies").getJSONObject("care").put("llm_kinds", new JSONArray(List.of("hospital", "Nursing-Home")));
        final Categories.Problems p = new Categories.Problems();
        final Categories c = Categories.read(List.of(bad), List.of("bad.json"), KgVocabularies.get().nace, p);
        assertEquals(Set.of("hospital"), c.llmKinds("care"));
        assertTrue(p.list.toString(), p.list.toString().contains("Nursing-Home"));
    }

    private static Categories read(final JSONObject file) {
        final Categories.Problems p = new Categories.Problems();
        final Categories c = Categories.read(List.of(file), List.of("categories.json"), KgVocabularies.get().nace, p);
        assertTrue(p.list.toString(), p.list.isEmpty());
        return c;
    }

    /** The keys of the collections a migration carried over are read also while they are not followed, as they always were. */
    @Test
    public void theKeysOfMigratedCollectionsAreReadAlsoWhenNotFollowed() {
        final Map<String, String> s = KgTestSupport.enabled(KgConfig.COLLECTIONS, "alpha_x", KgConfig.VOCAB_PREFIX + "beta_y", "",
                KgConfig.JOBS_EXTRACT_PREFIX + "beta_y", "true");
        assertEquals("{}||{}", KgConfig.read(s::get, s.keySet()).extractionKey());
        s.put(KgConfig.COLLECTION_PROFILES_MIGRATION, "beta_y");
        final KgConfig migrated = KgConfig.read(s::get, s.keySet());
        assertEquals(Set.of("beta_y"), migrated.migratedCollections);
        assertEquals("{beta_y=}||{beta_y=true}", migrated.extractionKey());
        s.put(KgConfig.COLLECTION_PROFILES_MIGRATION, "*");
        assertFalse(KgConfig.read(s::get, s.keySet()).valid());
    }
}
