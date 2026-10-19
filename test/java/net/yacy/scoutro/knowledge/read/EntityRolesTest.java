// EntityRolesTest.java
// Copyright 2026 Scoutro
//
// This file is part of YaCy / Scoutro.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.

package net.yacy.scoutro.knowledge.read;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.BusinessFacts;
import net.yacy.scoutro.knowledge.extract.Claim;
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.Mention;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * The read side of the production cases of 0.11.5: what pages read before 0.11.5 stored stays physically in the graph (the
 * organisation "Banken" of www.rostocker-massivhaus.de, the brand "SCHIMMEL" as a brand of das-pianohaus.de); the reader labels
 * every organisation with its role, hides a relation to a generic name from the business view and keeps the statements in the
 * object view. Nothing is rewritten or retracted.
 */
public class EntityRolesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final long now = java.time.LocalDate.of(2026, 10, 19).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    private KgStore store;
    private KgConfig cfg;
    private Terms terms;
    private Publisher publisher;
    private KgReader reader;
    private long version = 1000L;

    @Before
    public void open() throws Exception {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        final StorageGuard guard = new StorageGuard(this.cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, this.cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        this.terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            this.terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(this.cfg, this.terms);
        this.reader = new KgReader(this.store, this.cfg, () -> this.now);
    }

    @After
    public void close() {
        this.store.close();
    }

    private Publisher.Doc doc(final String id, final String url, final String collection) {
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
        return d;
    }

    private static ExtractContext ctx() {
        return new ExtractContext(KgVocabularies.get(), Set.of("construction"), false, List.of("bauteamcheck-web"));
    }

    private void publish(final Publisher.Doc d, final Extraction ex) throws Exception {
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = this.now - 86_400_000L;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, d.loadedAt));
    }

    private Extraction extract(final Publisher.Doc d, final String jsonld, final String text) {
        final Extraction ex = new Extraction(300);
        if (jsonld != null) {
            new JsonLdExtractor(200).extract(List.of(jsonld), d.url, d.host, "de", ex, ctx());
        }
        if (text != null) {
            new RuleExtractor(200, 65536).extract(text, d.url, d.host, "de", ex, ctx(), List.of(), List.of());
        } else {
            BusinessFacts.industriesFromServices(ex, ctx(), 2, 200);
        }
        return ex;
    }

    /** The claims of an 0.11.4 page as the old rules stored them: the subject names a generic word as a partner. */
    private static void oldPartner(final Extraction ex, final String subject, final String word, final String predicate) {
        final Mention m = ex.add(new Mention("rule:org:" + word.toLowerCase(java.util.Locale.ROOT), Vocabulary.ORGANIZATION, 2));
        m.name = word;
        ex.add(new Claim(m.ref, Vocabulary.NAME, null, word, 2, Claim.KIND_RULE, false, "text:100", "Unsere Partner: " + word, 0.65));
        ex.add(new Claim(subject, predicate, m.ref, null, 2, Claim.KIND_RULE, false, "text:90", "Unsere Partner: " + word, 0.65));
    }

    private String entity(final String name) throws Exception {
        return this.store.read(c -> KgStore.queryString(c, "SELECT e.public_id FROM kg_entity e JOIN kg_statement s ON s.subj = e.ent_rowid"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'name' AND s.obj_val = '" + name + "' AND e.status = 1 LIMIT 1"));
    }

    private static List<String> others(final JSONArray facts) throws Exception {
        final List<String> out = new ArrayList<>();
        for (int i = 0; facts != null && i < facts.length(); i++) {
            final JSONObject r = facts.getJSONObject(i);
            final JSONObject other = r.getJSONObject("other");
            out.add(r.getString("predicate") + " " + other.optString("name") + " [" + (other.isNull("role") ? "-" : other.getString("role")) + "]");
        }
        return out;
    }

    @Test
    public void aGenericNameStoredBeforeIsLabelledAndHiddenFromTheBusinessViewButKept() throws Exception {
        final Publisher.Doc d = doc("AAAAAArostk1", "https://www.rostocker-massivhaus.de/finanzierung", "bauteamcheck-web");
        final Extraction ex = extract(d, "{\"@type\":\"Organization\",\"name\":\"Rostocker Massivhaus GmbH\",\"url\":\"https://www.rostocker-massivhaus.de/\"}",
                "Finanzierung: Unsere Partner: Muster Baufinanz GmbH und KfW.");
        final String subject = ex.subjects(1).get(0).ref;
        oldPartner(ex, subject, "Banken", Vocabulary.PARTNER_OF);
        oldPartner(ex, subject, "Bildergalerie", Vocabulary.REFERENCE_FOR);
        publish(d, ex);

        final String banken = entity("Banken");
        assertNotNull("the stored fact is not deleted", banken);
        final JSONObject e = this.reader.entity(banken, Viewer.ALL);
        assertEquals("generic", e.getString("role"));
        assertEquals("supported", e.getString("quality"));
        final JSONObject operator = this.reader.entity(entity("Rostocker Massivhaus GmbH"), Viewer.ALL);
        assertEquals("operator", operator.getString("role"));
        assertFalse(this.reader.entity(entity("Muster Baufinanz GmbH"), Viewer.ALL).has("role")
                && !this.reader.entity(entity("Muster Baufinanz GmbH"), Viewer.ALL).isNull("role"));

        final JSONObject view = new BusinessView(this.reader).entity(entity("Rostocker Massivhaus GmbH"), Viewer.ALL, false);
        final JSONObject relations = view.getJSONObject("relations");
        final List<String> shown = others(relations.getJSONArray("facts"));
        assertTrue(shown.toString(), shown.contains("partner_of Muster Baufinanz GmbH [-]"));
        assertTrue(shown.toString(), shown.contains("partner_of KfW [-]"));
        assertFalse(shown.toString(), shown.stream().anyMatch(s -> s.contains("Banken") || s.contains("Bildergalerie")));
        assertEquals("the hidden ones are counted, not lost", 2, relations.getInt("hidden_generic"));

        // the object view of the generic entity itself still shows its statements: nothing is retracted
        final JSONObject generic = new BusinessView(this.reader).entity(banken, Viewer.ALL, false);
        assertEquals("generic", generic.getJSONObject("overview").getString("role"));
        final JSONArray list = this.reader.entities(new KgReader.EntityQuery(), Viewer.ALL).getJSONArray("items");
        boolean found = false;
        for (int i = 0; i < list.length(); i++) {
            if ("Banken".equals(list.getJSONObject(i).optString("name"))) {
                found = true;
                assertEquals("generic", list.getJSONObject(i).getString("role"));
            }
        }
        assertTrue("the entity list still lists it, labelled", found);
    }

    @Test
    public void aBrandAndAMakerAreLabelledAndNeverTheOperator() throws Exception {
        final Publisher.Doc shop = doc("AAAAAApiano1", "https://www.das-pianohaus.de/", "bauteamcheck-web");
        // what 0.11.4 stored from the dealer's brand list: SCHIMMEL as a brand of the dealer
        publish(shop, extract(shop, "{\"@type\":\"Organization\",\"name\":\"Das Pianohaus GmbH\",\"url\":\"https://www.das-pianohaus.de/\","
                + "\"brand\":[{\"@type\":\"Brand\",\"name\":\"Pianohaus Edition\"}]}", null));
        final Publisher.Doc product = doc("AAAAAApiano2", "https://www.das-pianohaus.de/schimmel-k-122", "bauteamcheck-web");
        publish(product, extract(product, "{\"@type\":\"Product\",\"name\":\"Schimmel K 122\",\"brand\":{\"@type\":\"Brand\",\"name\":\"SCHIMMEL\"},"
                + "\"manufacturer\":{\"@type\":\"Organization\",\"name\":\"Wilhelm Schimmel Pianofortefabrik GmbH\"},"
                + "\"offers\":{\"@type\":\"Offer\",\"price\":\"12900\",\"priceCurrency\":\"EUR\"}}", null));

        assertEquals("operator", this.reader.entity(entity("Das Pianohaus GmbH"), Viewer.ALL).getString("role"));
        assertEquals("brand", this.reader.entity(entity("SCHIMMEL"), Viewer.ALL).getString("role"));
        assertEquals("brand", this.reader.entity(entity("Pianohaus Edition"), Viewer.ALL).getString("role"));
        assertEquals("manufacturer", this.reader.entity(entity("Wilhelm Schimmel Pianofortefabrik GmbH"), Viewer.ALL).getString("role"));
        final JSONObject view = new BusinessView(this.reader).entity(entity("Das Pianohaus GmbH"), Viewer.ALL, false);
        final List<String> shown = others(view.getJSONObject("relations").getJSONArray("facts"));
        assertTrue(shown.toString(), shown.contains("brand_of Pianohaus Edition [brand]"));
        assertEquals(0, view.getJSONObject("relations").getInt("hidden_generic"));
    }
}
