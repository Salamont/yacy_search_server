/*
 *  CompanySystemsTest
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

package net.yacy.scoutro.knowledge.read;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * Software and systems of a company in its business view (0.10.2): its own statements, the hints of job advertisements read
 * for it as employer and the hints of its job objects, each with context and source; shown whether or not job objects are
 * shown, the job itself never named. Examples only.
 */
public class CompanySystemsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final long now = java.time.LocalDate.of(2027, 1, 15).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    private KgStore store;
    private Publisher publisher;
    private KgReader reader;
    private long version = 1000L;

    private void open(final Map<String, String> settings) throws Exception {
        final KgConfig cfg = KgTestSupport.config(settings);
        final KgPaths paths = new KgPaths(this.tmp.newFolder());
        this.store = KgStore.open(paths, cfg, new StorageGuard(cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis), KgStore.SQLITE,
                System::currentTimeMillis);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(cfg, terms);
        this.reader = new KgReader(this.store, cfg, () -> this.now);
    }

    @After
    public void close() {
        if (this.store != null) {
            this.store.close();
        }
    }

    private void publish(final String id, final String url, final String jsonld, final String text, final ExtractContext ctx) throws Exception {
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = java.net.URI.create(url).getHost();
        d.hostId = id.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of("example-web");
        final Extraction ex = new Extraction(300);
        if (jsonld != null) {
            new JsonLdExtractor(200).extract(List.of(jsonld), d.url, d.host, "de", ex, ctx);
        }
        if (text != null) {
            new RuleExtractor(200, 65536).extract(text, d.url, d.host, "de", ex, ctx, List.of(), List.of());
        }
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = this.now - 86_400_000L;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now));
    }

    private static final String ORG = "{\"@type\":\"Organization\",\"name\":\"Muster Handel GmbH\",\"url\":\"https://www.muster-handel.example/\"}";
    private static final String POSTING = "{\"@type\":\"JobPosting\",\"title\":\"Buchhalter (m/w/d)\",\"description\":\"Wir nutzen SAP S/4HANA intern."
            + " Kenntnisse in Revit sind wünschenswert.\",\"hiringOrganization\":" + ORG + "}";

    private void corpus(final boolean jobs) throws Exception {
        final ExtractContext ctx = new ExtractContext(KgVocabularies.get(), Set.of(), jobs, true, List.of("example-web"));
        publish("HOMEAAhost01", "https://www.muster-handel.example/", ORG, "Muster Handel GmbH. Wir nutzen Salesforce intern.", ctx);
        publish("JOBSAAhost01", "https://www.muster-handel.example/stellen/buchhalter", POSTING, null, ctx);
        publish("REFSAAhost01", "https://www.muster-handel.example/referenzen", ORG,
                "Referenzen. Für unsere Kunden setzen wir ArchiCAD ein.", ctx);
    }

    private JSONObject company() throws Exception {
        final String id = this.store.read(c -> KgStore.queryString(c, "SELECT e.public_id FROM kg_entity e JOIN kg_statement s ON s.subj = e.ent_rowid"
                + " JOIN kg_vocab v ON v.term_id = s.pred JOIN kg_vocab t ON t.term_id = e.type WHERE v.name = 'name'"
                + " AND s.obj_val = 'Muster Handel GmbH' AND t.name = 'organization' AND e.status = 1 LIMIT 1"));
        return new BusinessView(this.reader).entity(id, Viewer.ALL, false);
    }

    private static List<String> systems(final JSONObject view) {
        final List<String> out = new ArrayList<>();
        final JSONArray a = view.optJSONArray("systems");
        for (int i = 0; a != null && i < a.length(); i++) {
            final JSONObject x = a.optJSONObject(i);
            out.add(x.optString("product") + "|" + x.optString("context") + "|" + x.optString("source"));
            assertTrue("each hint opens its evidence", x.optString("statement").startsWith("kgs_"));
        }
        return out;
    }

    @Test
    public void theCompanyShowsTheHintsOfItsAdvertisementsWithoutJobObjects() throws Exception {
        open(KgTestSupport.enabled(KgConfig.COLLECTIONS, "example-web"));
        corpus(false);
        final JSONObject view = company();
        assertEquals(List.of("archicad|customer_projects|references", "revit|desirable_competence|job_ad", "salesforce|internal_use|company",
                "sap-s4hana|internal_use|job_ad"), systems(view));
        assertFalse("no job object, no jobs section", view.has("jobs"));
    }

    @Test
    public void hintsOfJobObjectsStayVisibleWhenJobObjectsAreNotShown() throws Exception {
        // job objects extracted, not shown (no display setting): the job is hidden, its hints are the company's knowledge
        open(KgTestSupport.enabled(KgConfig.COLLECTIONS, "example-web", KgConfig.JOBS_COLLECTIONS, "example-web",
                KgConfig.JOBS_DISPLAY_COLLECTIONS, ""));
        corpus(true);
        final JSONObject view = company();
        assertFalse("job objects are not shown", view.has("jobs"));
        assertEquals(List.of("archicad|customer_projects|references", "revit|desirable_competence|job_ad", "salesforce|internal_use|company",
                "sap-s4hana|internal_use|job_ad"), systems(view));
        assertFalse("the job is not named", view.optJSONArray("systems").toString().contains("Buchhalter"));
    }
}
