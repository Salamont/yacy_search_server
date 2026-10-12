/*
 *  CollectionProfilesUpgradeTest
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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.apache.solr.common.SolrInputDocument;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.Gates;
import net.yacy.scoutro.knowledge.sync.JsonLdCapture;
import net.yacy.scoutro.knowledge.sync.Reconciler;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * (5) The upgrade of a running installation: a graph built under the former collection defaults keeps its stored extractor
 * identity and the input hash of every page across the upgrade and the restarts after it, and nothing is extracted again. The
 * counter-check upgrades the same graph without the migration and sees every page extracted again.
 * <p>
 * The former code is reproduced by the current one with the former {@code categories.json} (its collection map, no facility
 * kinds) and the former built-in list of collections whose keys always count (the marker); that this is the former code's
 * identity is checked against {@link CollectionMigrationTest#FORMER}, which the code at 1d5fa4bf4 computed.
 */
public class CollectionProfilesUpgradeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EmbeddedInstance solr;
    private File data;
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

    private static final String[] DOCS = {"EDEL01host01", "STACK1host02", "BOTH01host03"};

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1",
                new String[] {"collection1", "webgraph"});
        this.data = this.tmp.newFolder("data");
    }

    @After
    public void close() {
        KgRuntime.stop();
        JsonLdCapture.clear();
        if (this.solr != null) {
            this.solr.close();
        }
        KgVocabularies.configure(new File(KgVocabularies.DEFAULTS), null);
    }

    /** The settings of setup {@code fixedUnfollowed} without the LLM tier (which is no part of the extractor identity). */
    private static Map<String, String> settings() {
        final Map<String, String> s = new HashMap<>(CollectionMigrationTest.setups().get("fixedUnfollowed").settings);
        s.remove(KgConfig.LLM_COLLECTIONS);
        return s;
    }

    /** The shipped defaults as the former code had them: categories.json with its collection map and without facility kinds. */
    private File formerDefaults() throws Exception {
        final File dir = this.tmp.newFolder("former-defaults");
        final File now = new File(KgVocabularies.DEFAULTS);
        for (final String f : List.of(KgVocabularies.NACE_FILE, "products.json", "needs.json")) {
            Files.copy(new File(now, f).toPath(), new File(dir, f).toPath());
        }
        final JSONObject c = new JSONObject(new String(Files.readAllBytes(new File(now, KgVocabularies.CATEGORIES_FILE).toPath()),
                StandardCharsets.UTF_8));
        assertFalse("the shipped defaults map no collection", c.has("collections"));
        final JSONObject vs = c.getJSONObject("vocabularies");
        for (final String v : vs.keySet()) {
            assertNotNull(v, vs.getJSONObject(v).remove("llm_kinds"));
        }
        c.put("collections", new JSONObject(CollectionMigration.FORMER_VOCABULARIES));
        Files.write(new File(dir, KgVocabularies.CATEGORIES_FILE).toPath(), c.toString(1).getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private KgRuntime start(final Map<String, String> settings, final boolean writer) {
        KgRuntime.Env env = new KgRuntime.Env(this.data, settings::get, this.clock::get, new KgTestSupport.Probe(), KgStore.SQLITE, false,
                () -> this.solr.getDefaultServer(), Gates.IDLE).withKeys(() -> new ArrayList<>(settings.keySet()));
        if (writer) {
            env = env.withSettings(settings::put);
        }
        KgRuntime.start(env);
        final KgRuntime r = KgRuntime.current();
        assertNotNull(r);
        assertEquals(r.status().toString(), KgRuntime.State.RUNNING, r.state());
        return r;
    }

    private SolrInputDocument doc(final String id, final String path, final List<String> collections) {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", "https://www.example-pflege.de/" + path);
        d.setField("host_s", "www.example-pflege.de");
        d.setField("host_id_s", id.substring(6));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", collections);
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        d.setField("ld_json_txt", List.of("{\"@type\":\"Organization\",\"name\":\"Example Pflege GmbH\",\"telephone\":\"030 1234567\"}"));
        d.setField("text_t", "Unsere Leistungen: Tagespflege und Kurzzeitpflege. Tagespflege ab 49 € pro Tag. Wir beraten auch zu SAP.");
        return d;
    }

    private void drive(final KgRuntime r, final BooleanSupplier done) {
        for (int i = 0; i < 1500; i++) {
            r.tick();
            r.syncTick();
            if (done.getAsBoolean()) {
                return;
            }
            this.clock.addAndGet(3000L);
        }
        fail("not reached: " + r.status());
    }

    private void settle(final KgRuntime r) {
        drive(r, () -> {
            final JSONObject sync = r.status().optJSONObject("sync");
            final JSONObject lag = sync == null ? null : sync.optJSONObject("lag");
            return sync != null && sync.optBoolean("initialized") && lag != null && lag.optLong("pending", 1L) == 0L
                    && !lag.optBoolean("reconcile_pending", true);
        });
    }

    private static String string(final KgRuntime r, final String sql) throws Exception {
        return r.store().read(c -> KgStore.queryString(c, sql));
    }

    private static long count(final KgRuntime r, final String sql) throws Exception {
        return r.store().read(c -> KgStore.queryLong(c, sql));
    }

    /** What identifies the extraction of the graph: the stored extractors, the input hash of each page, the re-extraction scans. */
    private static Map<String, String> identity(final KgRuntime r) throws Exception {
        final Map<String, String> out = new TreeMap<>();
        out.put("extractors", string(r, "SELECT value FROM kg_meta WHERE key = '" + KgSchema.META_EXTRACTORS + "'"));
        for (final String id : DOCS) {
            out.put(id, string(r, "SELECT hex(input_hash) FROM kg_doc WHERE doc_id = '" + id + "'"));
        }
        out.put("reextractScans", Long.toString(count(r, "SELECT count(*) FROM kg_scan WHERE reason = '" + Reconciler.REASON_EXTRACTORS
                + "'")));
        out.put("careCategories", Long.toString(count(r, "SELECT count(*) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE v.name = 'category' AND s.obj_val LIKE 'care/%'")));
        return out;
    }

    /** The graph of the former code: its defaults, its built-in list of collections whose keys always count. */
    private Map<String, String> buildUnderTheFormerCode(final Map<String, String> settings) throws Exception {
        KgVocabularies.configure(formerDefaults(), null);
        settings.put(KgConfig.COLLECTION_PROFILES_MIGRATION, CollectionMigrationTest.FOUR_SORTED);
        final KgRuntime r = start(settings, false);
        this.solr.getDefaultServer().add(doc(DOCS[0], "leistungen", List.of("edelsenior-web")));
        this.solr.getDefaultServer().add(doc(DOCS[1], "services", List.of("stackfinder-web")));
        this.solr.getDefaultServer().add(doc(DOCS[2], "angebote", List.of("edelsenior-web", "stackfinder-web")));
        this.solr.getDefaultServer().commit();
        settle(r);
        final Map<String, String> before = identity(r);
        final String former = CollectionMigrationTest.former("fixedUnfollowed");
        assertEquals("the graph has the identity the former code gave it", former.substring("extractors=".length(), former.indexOf('\n')),
                before.get("extractors"));
        for (final String id : DOCS) {
            assertNotNull(id, before.get(id));
        }
        assertEquals("0", before.get("reextractScans"));
        assertTrue("the care vocabulary applies to the pages of edelsenior-web", Long.parseLong(before.get("careCategories")) > 0L);
        KgRuntime.stop();
        // the former code never wrote the marker, nor a vocabulary file
        settings.remove(KgConfig.COLLECTION_PROFILES_MIGRATION);
        assertFalse(new File(KgRuntime.vocabularyDir(this.data), CollectionMigration.FILE).exists());
        return before;
    }

    @Test
    public void anUpgradeKeepsTheIdentityOfEveryPageAndExtractsNothingAgain() throws Exception {
        final Map<String, String> settings = settings();
        final Map<String, String> before = buildUnderTheFormerCode(settings);
        // the upgrade: the current defaults (no collection map), the running peer's settings writer
        KgVocabularies.configure(new File(KgVocabularies.DEFAULTS), null);
        KgRuntime r = start(settings, true);
        assertEquals(CollectionMigrationTest.FOUR_SORTED, settings.get(KgConfig.COLLECTION_PROFILES_MIGRATION));
        final File file = new File(KgRuntime.vocabularyDir(this.data), CollectionMigration.FILE);
        assertTrue(file.isFile());
        assertFalse("no re-extraction is due", r.status().getJSONObject("sync").getJSONObject("reconcile").optBoolean("reextract"));
        settle(r);
        assertEquals(before, identity(r));
        assertEquals(1L, count(r, "SELECT count(*) FROM kg_event WHERE code = 'collection_profiles_migrated'"));
        assertEquals(0L, count(r, "SELECT count(*) FROM kg_event WHERE code = 'collection_profiles_identity_changed'"));
        assertTrue(string(r, "SELECT detail FROM kg_event WHERE code = 'collection_profiles_migrated'").contains("identity unchanged"));
        assertFalse(r.status().getJSONObject("sync").getJSONObject("reconcile").optBoolean("reextract"));
        // the collections keep their vocabularies, now as the installation's own map
        assertEquals("care", r.config().vocabularyOf("edelsenior-web", KgVocabularies.get().categories.collections));
        assertEquals("vocabulary_files", rowOf(r, "edelsenior-web").getString("vocabularySource"));
        final byte[] written = Files.readAllBytes(file.toPath());
        // restarts: the step does not run again, nothing changes
        KgRuntime.stop();
        r = start(settings, true);
        settle(r);
        assertEquals(before, identity(r));
        assertEquals(1L, count(r, "SELECT count(*) FROM kg_event WHERE code = 'collection_profiles_migrated'"));
        assertTrue(java.util.Arrays.equals(written, Files.readAllBytes(file.toPath())));
        KgRuntime.stop();
        r = start(settings, true);
        settle(r);
        assertEquals(before, identity(r));
    }

    /** The counter-check: the same graph upgraded without the migration extracts every page again. */
    @Test
    public void withoutTheMigrationEveryPageWouldBeExtractedAgain() throws Exception {
        final Map<String, String> settings = settings();
        final Map<String, String> before = buildUnderTheFormerCode(settings);
        KgVocabularies.configure(new File(KgVocabularies.DEFAULTS), null);
        final KgRuntime r = start(settings, false);
        settle(r);
        final Map<String, String> after = identity(r);
        assertNotEquals(before.get("extractors"), after.get("extractors"));
        assertNotEquals(before.get(DOCS[0]), after.get(DOCS[0]));
        assertTrue("every page was extracted again", Long.parseLong(after.get("reextractScans")) > 0L);
        assertEquals("and the former collections lost their vocabulary", "0", after.get("careCategories"));
    }

    private static JSONObject rowOf(final KgRuntime r, final String collection) throws Exception {
        final org.json.JSONArray rows = r.status().getJSONArray("collections");
        for (int i = 0; i < rows.length(); i++) {
            if (collection.equals(rows.getJSONObject(i).getString("collection"))) {
                return rows.getJSONObject(i);
            }
        }
        fail(collection + " not listed");
        return null;
    }
}
