/*
 *  CollectionsStatusTest
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * New collections (package 6.1): {@code *} follows a new collection, a fixed
 * list does not; a new collection without a mapping has no vocabulary (never
 * guessed from its name) and gets generic facts only; an existing vocabulary
 * is assigned with {@code scoutro.kg.vocab.<collection>}, also under
 * {@code *}; a new vocabulary file works without a schema change; jobs stay
 * off until a collection is named in {@code scoutro.kg.jobs.collections}. The
 * status shows each collection with these settings and its documents.
 */
public class CollectionsStatusTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgRuntime runtime;
    private long version = 100L;

    @After
    public void close() {
        if (this.runtime != null) {
            this.runtime.close();
        }
        KgVocabularies.overrides(null);
    }

    private KgRuntime start(final Map<String, String> settings) {
        final File data = new File(this.tmp.getRoot(), "DATA");
        this.runtime = new KgRuntime(KgTestSupport.env(this.tmp.getRoot(), settings, new KgTestSupport.Probe())
                .withKeys(() -> new ArrayList<>(settings.keySet())));
        this.runtime.open();
        assertEquals(KgRuntime.State.RUNNING, this.runtime.state());
        assertTrue(data.isDirectory());
        return this.runtime;
    }

    private static JSONObject row(final KgRuntime r, final String collection) throws Exception {
        final JSONArray rows = r.status().getJSONArray("collections");
        for (int i = 0; i < rows.length(); i++) {
            if (collection.equals(rows.getJSONObject(i).getString("collection"))) {
                return rows.getJSONObject(i);
            }
        }
        return null;
    }

    /** Publishes one page of a collection as the sync would extract it (with the context of its followed collections). */
    private void publish(final KgRuntime r, final String id, final String url, final String collection, final String jsonld, final String text)
            throws Exception {
        final KgStore store = r.store();
        final Terms terms = new Terms();
        store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        final KgConfig cfg = r.config();
        final List<String> followed = cfg.follows(collection) ? List.of(collection) : List.of();
        final KgVocabularies.Snapshot vocab = KgVocabularies.get();
        final ExtractContext ctx = new ExtractContext(vocab, cfg.vocabulariesOf(followed, vocab.categories.collections), cfg.jobsFor(followed),
                followed);
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = id.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of(collection);
        d.solrVersion = ++this.version;
        d.loadedAt = System.currentTimeMillis();
        final Extraction ex = new Extraction(300);
        new JsonLdExtractor(200).extract(List.of(jsonld), url, d.host, "de", ex, ctx);
        new RuleExtractor(200, 65536).extract(text, url, d.host, "de", ex, ctx, List.of(), List.of());
        final Publisher publisher = new Publisher(cfg, terms);
        final Publisher.Row cur = store.read(c -> Publisher.row(c, id));
        store.write(WriteClass.GROWTH, 0, tx -> publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, d.loadedAt));
        r.forgetDocumentCounts();
    }

    private static long count(final KgRuntime r, final String predicate, final String collection) throws Exception {
        return r.store().read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " JOIN kg_statement_scope ss ON ss.stmt_rowid = s.stmt_rowid JOIN kg_collection k ON k.coll_id = ss.coll_id"
                + " WHERE v.name = '" + predicate + "' AND k.name = '" + collection + "'"));
    }

    private static final String ORG = "{\"@type\":\"Organization\",\"name\":\"Neuportal Pflege GmbH\",\"url\":\"https://www.neuportal.de/\","
            + "\"telephone\":\"030 1234567\"}";
    private static final String TEXT = "Unsere Leistungen: Tagespflege und Kurzzeitpflege. Tagespflege ab 49 € pro Tag. Karriere: Pflegefachkraft"
            + " (m/w/d) in Vollzeit. Bewerbungsfrist: 31.03.2099.";

    @Test
    public void aNewCollectionUnderStarIsFollowedWithoutAVocabularyAndGetsGenericFactsOnly() throws Exception {
        final KgRuntime r = start(KgTestSupport.enabled(KgConfig.COLLECTIONS, "*"));
        assertTrue(r.config().follows("newportal-web"));
        publish(r, "NEWPO1host01", "https://www.neuportal.de/leistungen", "newportal-web", ORG, TEXT);
        final JSONObject row = row(r, "newportal-web");
        assertTrue("a collection with documents is listed although named nowhere: " + r.status().getJSONArray("collections"), row != null);
        assertTrue(row.getBoolean("followed"));
        assertTrue("no vocabulary is shown as none, not hidden", row.has("vocabulary") && row.isNull("vocabulary"));
        assertEquals("none", row.getString("vocabularySource"));
        assertFalse(row.getBoolean("jobs"));
        assertFalse(row.getBoolean("llm"));
        assertEquals(1, row.getLong("documents"));
        assertEquals("following", row.getString("state"));
        // generic facts yes, but no business category from a vocabulary the collection does not have, and no jobs
        assertTrue(count(r, "name", "newportal-web") > 0);
        assertTrue(count(r, "phone", "newportal-web") > 0);
        assertEquals("never a guessed category", 0, count(r, "category", "newportal-web"));
        assertEquals(0, count(r, "industry_category", "newportal-web"));
        assertEquals(0, count(r, "hiring_organization", "newportal-web"));
        // the four Scoutro collections keep their default vocabularies; a name that resembles one gets nothing
        assertEquals("care", row(r, "edelsenior-web").getString("vocabulary"));
        assertEquals("vocabulary_files", row(r, "edelsenior-web").getString("vocabularySource"));
        assertEquals("waiting", row(r, "edelsenior-web").getString("state"));
        assertNull(r.config().vocabularyOf("edelsenior-web-neu", KgVocabularies.get().categories.collections));
    }

    @Test
    public void aFixedListDoesNotFollowANewCollection() throws Exception {
        final KgRuntime r = start(KgTestSupport.enabled(KgConfig.COLLECTIONS, "edelsenior-web,stackfinder-web",
                KgConfig.VOCAB_PREFIX + "newportal-web", "software"));
        assertFalse(r.config().follows("newportal-web"));
        assertNull("neither followed nor holding documents: not listed", row(r, "newportal-web"));
        assertNull("the key of a collection that is not followed is not read", r.config().vocabOverrides.get("newportal-web"));
        // a page of it (the sync captures none) would take nothing from the collection: no vocabulary, no jobs
        publish(r, "NEWPO2host01", "https://www.neuportal.de/leistungen", "newportal-web", ORG, TEXT);
        final JSONObject row = row(r, "newportal-web");
        assertFalse(row.getBoolean("followed"));
        assertEquals("not_followed", row.getString("state"));
        assertTrue(row.isNull("vocabulary"));
        assertFalse(row.getBoolean("jobs"));
        assertEquals(0, count(r, "category", "newportal-web"));
        assertTrue(row(r, "stackfinder-web").getBoolean("followed"));
        assertEquals("waiting", row(r, "edelsenior-web").getString("state"));
    }

    @Test
    public void anExistingVocabularyIsAssignedBySettingAlsoUnderStar() throws Exception {
        final KgRuntime r = start(KgTestSupport.enabled(KgConfig.COLLECTIONS, "*", KgConfig.VOCAB_PREFIX + "newportal-web", "care"));
        assertEquals("care", r.config().vocabularyOf("newportal-web", KgVocabularies.get().categories.collections));
        assertTrue("the key is part of the extraction identity", r.config().extractionKey().contains("newportal-web=care"));
        publish(r, "NEWPO3host01", "https://www.neuportal.de/leistungen", "newportal-web", ORG, TEXT);
        final JSONObject row = row(r, "newportal-web");
        assertEquals("care", row.getString("vocabulary"));
        assertEquals("setting", row.getString("vocabularySource"));
        assertTrue(row.getBoolean("vocabularyKnown"));
        assertTrue("the care categories now apply", count(r, "category", "newportal-web") > 0);
        // an unknown vocabulary name is reported, the collection gets none
        close();
        final KgRuntime unknown = start(KgTestSupport.enabled(KgConfig.COLLECTIONS, "*", KgConfig.VOCAB_PREFIX + "newportal-web", "portal"));
        final JSONObject bad = row(unknown, "newportal-web");
        assertEquals("unknown_vocabulary", bad.getString("state"));
        assertFalse(bad.getBoolean("vocabularyKnown"));
        // an empty value switches a default vocabulary off
        close();
        final KgRuntime off = start(KgTestSupport.enabled(KgConfig.COLLECTIONS, "*", KgConfig.VOCAB_PREFIX + "edelsenior-web", ""));
        assertTrue(row(off, "edelsenior-web").isNull("vocabulary"));
        assertEquals("setting", row(off, "edelsenior-web").getString("vocabularySource"));
    }

    @Test
    public void aNewVocabularyFileWorksWithoutASchemaChange() throws Exception {
        final File dir = new File(this.tmp.getRoot(), "DATA/SCOUTRO/knowledge/vocabulary");
        assertTrue(dir.mkdirs());
        Files.write(new File(dir, "portal.json").toPath(), ("{\"schema\":\"scoutro.kg.categories\",\"collections\":{\"newportal-web\":\"portal\"},"
                + "\"vocabularies\":{\"portal\":{\"de\":\"Portal\",\"en\":\"Portal\",\"categories\":[{\"code\":\"tagesbetreuung\","
                + "\"de\":\"Tagesbetreuung\",\"en\":\"Day support\",\"nace\":null,\"terms\":[\"tagespflege\"]}]}}}")
                .getBytes(StandardCharsets.UTF_8));
        final KgRuntime r = start(KgTestSupport.enabled(KgConfig.COLLECTIONS, "*"));
        final JSONObject vocabulary = r.status().getJSONObject("vocabulary");
        final List<String> files = new ArrayList<>();
        for (int i = 0; i < vocabulary.getJSONArray("files").length(); i++) {
            files.add(vocabulary.getJSONArray("files").getString(i));
        }
        assertTrue(files.toString(), files.contains("vocabulary/portal.json"));
        publish(r, "NEWPO4host01", "https://www.neuportal.de/leistungen", "newportal-web", ORG, TEXT);
        final JSONObject row = row(r, "newportal-web");
        assertEquals(vocabulary.getJSONArray("problems").toString(), "portal", row.getString("vocabulary"));
        assertEquals("vocabulary_files", row.getString("vocabularySource"));
        assertTrue(row.getBoolean("vocabularyKnown"));
        final long categories = r.store().read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_statement s JOIN kg_vocab v"
                + " ON v.term_id = s.pred WHERE v.name = 'category' AND s.obj_val = 'portal/tagesbetreuung'"));
        assertTrue("the new vocabulary's category is assigned", categories > 0);
    }

    @Test
    public void jobsStayOffWithoutAnExplicitSetting() throws Exception {
        final KgRuntime r = start(KgTestSupport.enabled(KgConfig.COLLECTIONS, "*", KgConfig.VOCAB_PREFIX + "newportal-web", "care"));
        assertFalse(r.config().jobsFor(List.of("newportal-web")));
        assertFalse(row(r, "newportal-web").getBoolean("jobs"));
        assertEquals(0, r.status().getJSONObject("config").getJSONArray("jobsCollections").length());
        close();
        final KgRuntime on = start(KgTestSupport.enabled(KgConfig.COLLECTIONS, "*", KgConfig.JOBS_COLLECTIONS, "newportal-web"));
        assertTrue(on.config().jobsFor(List.of("newportal-web")));
        assertFalse("only the named collection", on.config().jobsFor(List.of("otherportal-web")));
        assertTrue(row(on, "newportal-web").getBoolean("jobs"));
        assertEquals("newportal-web", on.status().getJSONObject("config").getJSONArray("jobsCollections").getString(0));
        publish(on, "NEWPO5host01", "https://www.neuportal.de/karriere", "newportal-web", ORG, TEXT);
        assertTrue(count(on, "hiring_organization", "newportal-web") > 0);
    }

    /** Package 6.3: the LLM status names the model of the LLM selection, also while the tier has no collection. */
    @Test
    public void theLlmStatusNamesTheModelAndNoLlmCollectionsOnlyWithoutOne() throws Exception {
        final net.yacy.scoutro.knowledge.extract.LlmClient model = new net.yacy.scoutro.knowledge.extract.LlmClient() {
            @Override
            public String model() {
                return "OLLAMA/fixture";
            }

            @Override
            public String complete(final String system, final String user, final org.json.JSONObject schema, final long timeoutMillis)
                    throws java.io.IOException {
                throw new java.io.IOException("not called");
            }
        };
        final Map<String, String> plain = KgTestSupport.enabled(KgConfig.COLLECTIONS, "kga,kgb");
        this.runtime = new KgRuntime(KgTestSupport.env(this.tmp.newFolder("a"), plain, new KgTestSupport.Probe()).withLlm(model)
                .withKeys(() -> new ArrayList<>(plain.keySet())));
        this.runtime.open();
        JSONObject llm = this.runtime.status().getJSONObject("llm");
        assertEquals("off", llm.getString("state"));
        assertEquals("no_llm_collections", llm.getString("reason"));
        assertEquals("the model is shown although the tier is off", "OLLAMA/fixture", llm.getString("model"));
        assertEquals("OLLAMA/fixture", this.runtime.llmModel());
        assertEquals("running", this.runtime.status().getString("state"));
        this.runtime.close();
        // one collection for the LLM tier: the reason is no longer no_llm_collections (here: no Solr to follow)
        final Map<String, String> one = KgTestSupport.enabled(KgConfig.COLLECTIONS, "kga,kgb", KgConfig.LLM_COLLECTIONS, "kga");
        this.runtime = new KgRuntime(KgTestSupport.env(this.tmp.newFolder("b"), one, new KgTestSupport.Probe()).withLlm(model)
                .withKeys(() -> new ArrayList<>(one.keySet())));
        this.runtime.open();
        llm = this.runtime.status().getJSONObject("llm");
        assertTrue(llm.getBoolean("enabled"));
        assertFalse(llm.toString(), "no_llm_collections".equals(llm.optString("reason")));
        assertEquals("OLLAMA/fixture", llm.getString("model"));
        assertTrue(row(this.runtime, "kga").getBoolean("llm"));
        assertFalse(row(this.runtime, "kgb").getBoolean("llm"));
        // switched on for the LLM tier, but the collection is off for the graph: said so, not no_llm_collections
        this.runtime.close();
        final Map<String, String> off = KgTestSupport.enabled(KgConfig.COLLECTIONS, "kga", KgConfig.INACTIVE_COLLECTIONS, "kga",
                KgConfig.LLM_COLLECTIONS, "kga");
        this.runtime = new KgRuntime(KgTestSupport.env(this.tmp.newFolder("d"), off, new KgTestSupport.Probe()).withLlm(model)
                .withKeys(() -> new ArrayList<>(off.keySet())));
        this.runtime.open();
        llm = this.runtime.status().getJSONObject("llm");
        assertFalse(llm.getBoolean("enabled"));
        assertEquals("llm_collections_not_followed", llm.getString("reason"));
        assertEquals("OLLAMA/fixture", llm.getString("model"));
        // without a model of the LLM selection, nothing is guessed
        this.runtime.close();
        this.runtime = new KgRuntime(KgTestSupport.env(this.tmp.newFolder("c"), plain, new KgTestSupport.Probe())
                .withKeys(() -> new ArrayList<>(plain.keySet())));
        this.runtime.open();
        assertTrue(this.runtime.status().getJSONObject("llm").isNull("model"));
    }
}
