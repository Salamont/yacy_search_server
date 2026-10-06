/*
 *  DerivedServiceTest
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

package net.yacy.scoutro.knowledge.derive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgChangeLog;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/** Derived relations and suggested matches (package 6): apart from facts, both sides named, visible only with both collections. */
public class DerivedServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private KgStore store;
    private KgConfig cfg;
    private Terms terms;
    private Publisher publisher;
    private StorageGuard guard;
    private final long now = 1_800_000_000_000L;
    private long version = 1000L;

    @Before
    public void open() throws Exception {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled());
        final KgPaths paths = new KgPaths(this.tmp.getRoot());
        this.guard = new StorageGuard(this.cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(paths, this.cfg, this.guard, KgStore.SQLITE, System::currentTimeMillis);
        this.terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            this.terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(this.cfg, this.terms);
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

    private void publish(final Publisher.Doc d, final Extraction ex) throws Exception {
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = this.now;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now));
    }

    private static ExtractContext ctx(final String vocabulary, final String collection) {
        return new ExtractContext(KgVocabularies.get(), Set.of(vocabulary), false, List.of(collection));
    }

    private Extraction page(final Publisher.Doc d, final String jsonld, final String text, final ExtractContext ctx) {
        final Extraction ex = new Extraction(300);
        if (jsonld != null) {
            new JsonLdExtractor(200).extract(List.of(jsonld), d.url, d.host, "de", ex, ctx);
        }
        if (text != null) {
            new RuleExtractor(200, 65536).extract(text, d.url, d.host, "de", ex, ctx, List.of(), List.of());
        } else {
            BusinessFacts.industriesFromServices(ex, ctx, 2, 200);
        }
        return ex;
    }

    /** A software company that names care homes as its audience, two care homes of one operator, a page that links. */
    private void corpus() throws Exception {
        final ExtractContext sw = ctx("software", "stackfinder-web");
        final Publisher.Doc swHome = doc("SWHOMEhost01", "https://www.pflegesoft.de/", "stackfinder-web");
        publish(swHome, page(swHome, "{\"@type\":\"Organization\",\"name\":\"PflegeSoft GmbH\",\"url\":\"https://www.pflegesoft.de/\"}",
                null, sw));
        final Publisher.Doc swFor = doc("SWFORWhost01", "https://www.pflegesoft.de/fuer-wen", "stackfinder-web");
        swFor.linkDomains = new java.util.TreeMap<>(java.util.Map.of("lindenhof.de", 2));
        publish(swFor, page(swFor, "{\"@type\":\"Organization\",\"name\":\"PflegeSoft GmbH\",\"url\":\"https://www.pflegesoft.de/\"}",
                "Für wen? Wir unterstützen Pflegeeinrichtungen bundesweit bei der Dienstplanung.", sw));
        final ExtractContext care = ctx("care", "edelsenior-web");
        final String op = "{\"@type\":\"Organization\",\"name\":\"Lindenhof Pflege gGmbH\",\"url\":\"https://www.lindenhof.de/\"}";
        final Publisher.Doc careHome = doc("CAHOMEhost02", "https://www.lindenhof.de/", "edelsenior-web");
        publish(careHome, page(careHome, op, null, care));
        for (final String house : new String[] {"Birke", "Eiche"}) {
            final Publisher.Doc h = doc(("H" + house.toUpperCase() + "host02aaaaa").substring(0, 12), "https://www.lindenhof.de/haus-" + house,
                    "edelsenior-web");
            publish(h, page(h, "{\"@type\":\"NursingHome\",\"name\":\"Haus " + house + "\",\"address\":{\"streetAddress\":\"" + house
                    + "nweg 1\",\"postalCode\":\"10115\",\"addressLocality\":\"Berlin\"},\"parentOrganization\":" + op + "}", null, care));
        }
    }

    private List<String> derived(final String where) throws Exception {
        return this.store.read(c -> {
            final List<String> out = new ArrayList<>();
            try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery("SELECT d.kind, a.public_id, b.public_id,"
                    + " ka.name, kb.name, d.confidence, d.basis FROM kg_derived d JOIN kg_entity a ON a.ent_rowid = d.a_ent JOIN kg_entity b"
                    + " ON b.ent_rowid = d.b_ent JOIN kg_collection ka ON ka.coll_id = d.coll_a JOIN kg_collection kb ON kb.coll_id = d.coll_b"
                    + (where == null ? "" : " WHERE " + where) + " ORDER BY d.kind, d.der_rowid")) {
                while (rs.next()) {
                    out.add(rs.getInt(1) + "|" + name(c, rs.getString(2)) + "|" + name(c, rs.getString(3)) + "|" + rs.getString(4) + "|"
                            + rs.getString(5) + "|" + rs.getDouble(6) + "|" + rs.getString(7));
                }
            }
            return out;
        });
    }

    private static String name(final java.sql.Connection c, final String entity) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT s.obj_val FROM kg_statement s JOIN kg_entity e ON e.ent_rowid = s.subj"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE e.public_id = ? AND v.name = 'name' LIMIT 1")) {
            ps.setString(1, entity);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : entity;
            }
        }
    }

    @Test
    public void derivationsNameBothSidesAndStayApartFromFacts() throws Exception {
        corpus();
        final long statementsBefore = this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_statement"));
        final DerivedService d = new DerivedService(this.cfg, this.store, () -> this.now);
        final DerivedService.Result r = d.run();
        assertEquals(r.json().toString(), 0, r.deleted);
        final List<String> rows = derived(null);
        // linked_to: weak, from the linking page's collection to the target's
        final List<String> linked = derived("d.kind = 1");
        assertEquals(rows.toString(), 1, linked.size());
        assertTrue(linked.get(0), linked.get(0).startsWith("1|PflegeSoft GmbH|Lindenhof Pflege gGmbH|stackfinder-web|edelsenior-web|0.2|"));
        assertEquals("SWFORWhost01", new JSONObject(linked.get(0).split("\\|", 7)[6]).getJSONArray("docs").getString(0));
        // same_operator: the two houses of the operator, in its collection
        final List<String> same = derived("d.kind = 2");
        assertEquals(1, same.size());
        assertTrue(same.get(0), same.get(0).matches("2\\|Haus (Birke|Eiche)\\|Haus (Birke|Eiche)\\|edelsenior-web\\|edelsenior-web\\|0\\.8\\|.*"));
        // suggested customers: the care homes meet the software company's declared target, Germany-wide
        final List<String> customers = derived("d.kind = 3");
        assertEquals(rows.toString(), 2, customers.size());
        for (final String row : customers) {
            final String[] f = row.split("\\|", 7);
            assertEquals("PflegeSoft GmbH", f[1]);
            assertTrue(f[2], f[2].startsWith("Haus "));
            assertEquals("stackfinder-web", f[3]);
            assertEquals("edelsenior-web", f[4]);
            final JSONObject basis = new JSONObject(f[6]);
            assertEquals("both sides name their facts", 2, basis.getJSONArray("a").length());
            assertEquals(2, basis.getJSONArray("b").length());
            assertTrue(basis.getJSONObject("match").getBoolean("area_confirmed"));
            assertTrue(Double.parseDouble(f[5]) <= 0.75);
        }
        // nothing of this is a statement, so no reader of facts can take it for one
        assertEquals(statementsBefore, (long) this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_statement")));
        assertEquals(0L, (long) this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_vocab WHERE name IN"
                + " ('linked_to', 'same_operator', 'suggested_customer', 'suggested_partner')")));
        // a second pass changes nothing
        final DerivedService.Result again = d.run();
        assertEquals(0, again.inserted + again.updated + again.deleted);
    }

    @Test
    public void theChangeFeedShowsADerivedRowOnlyWithBothCollections() throws Exception {
        corpus();
        new DerivedService(this.cfg, this.store, () -> this.now).run();
        final Viewer both = viewer("stackfinder-web", "edelsenior-web");
        final Viewer software = viewer("stackfinder-web");
        final Viewer careOnly = viewer("edelsenior-web");
        final int all = derivedItems(both);
        assertEquals("linked_to, same_operator and two suggestions", 4, all);
        assertEquals("one side's facts alone never show a combination", 0, derivedItems(software));
        assertEquals("same_operator lies in one collection", 1, derivedItems(careOnly));
        // a removal reaches exactly the viewers that could see the row
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> this.publisher.remove(tx, List.of("SWFORWhost01"), null, this.now));
        final DerivedService.Result r = new DerivedService(this.cfg, this.store, () -> this.now + 1).run();
        assertEquals(r.json().toString(), 3, r.deleted);
        assertEquals(0, derivedItems(software));
        int deletes = 0;
        for (final KgChangeLog.Item it : this.store.read(c -> KgChangeLog.read(c, null, both, 1000)).items) {
            if (it.kind == KgChangeLog.Kind.DERIVED && it.op == KgChangeLog.Op.DELETE) {
                deletes++;
            }
        }
        assertEquals(3, deletes);
    }

    @Test
    public void theManualPauseStopsTheDerivation() throws Exception {
        corpus();
        this.guard.setManualPause(true);
        final DerivedService.Result r = new DerivedService(this.cfg, this.store, () -> this.now).tick(this.now);
        assertEquals("manual", r.refused);
        assertEquals(0L, (long) this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_derived")));
        this.guard.setManualPause(false);
        assertTrue(new DerivedService(this.cfg, this.store, () -> this.now).tick(this.now).inserted > 0);
    }

    @Test
    public void aSuggestionNeverRepeatsAFactAndRespectsTheCaps() throws Exception {
        final KgConfig capped = KgTestSupport.config(KgTestSupport.enabled(KgConfig.MATCHES_MAX_PER_ENTITY, "1"));
        corpus();
        new DerivedService(capped, this.store, () -> this.now).run();
        assertEquals(1, derived("d.kind = 3").size());
        // an observed customer is a fact: it is never also suggested
        final ExtractContext sw = ctx("software", "stackfinder-web");
        final Publisher.Doc refs = doc("SWREFShost01", "https://www.pflegesoft.de/referenzen", "stackfinder-web");
        publish(refs, page(refs, "{\"@type\":\"Organization\",\"name\":\"PflegeSoft GmbH\",\"url\":\"https://www.pflegesoft.de/\"}",
                "Referenzen: Lindenhof Pflege gGmbH.", sw));
        new DerivedService(this.cfg, this.store, () -> this.now).run();
        for (final String row : derived("d.kind = 3")) {
            assertFalse(row, row.contains("|Lindenhof Pflege gGmbH|"));
        }
    }

    @Test
    public void aSuggestionUsesOnlyTheFactsOfItsTwoCollections() throws Exception {
        corpus();
        // Haus Ahorn's address is known only from a third collection
        final String id = "\"@id\":\"https://www.lindenhof.de/#ahorn\",\"name\":\"Haus Ahorn\",\"parentOrganization\":{\"@type\":\"Organization\","
                + "\"name\":\"Lindenhof Pflege gGmbH\",\"url\":\"https://www.lindenhof.de/\"}";
        final Publisher.Doc ahorn = doc("HAHORNhost02", "https://www.lindenhof.de/haus-Ahorn", "edelsenior-web");
        publish(ahorn, page(ahorn, "{\"@type\":\"NursingHome\"," + id + "}", null, ctx("care", "edelsenior-web")));
        final Publisher.Doc atlas = doc("ATLASShost02", "https://www.lindenhof.de/standorte", "pflegeatlas-web");
        publish(atlas, page(atlas, "{\"@type\":\"NursingHome\"," + id + ",\"address\":{\"streetAddress\":\"Ahornweg 1\",\"postalCode\":\"20095\","
                + "\"addressLocality\":\"Hamburg\"}}", null, ctx("care", "pflegeatlas-web")));
        final java.util.Set<String> third = new java.util.HashSet<>(this.store.read(c -> {
            final List<String> ids = new ArrayList<>();
            try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery("SELECT s.public_id FROM kg_statement s"
                    + " JOIN kg_statement_scope ss ON ss.stmt_rowid = s.stmt_rowid JOIN kg_collection k ON k.coll_id = ss.coll_id"
                    + " WHERE k.name = 'pflegeatlas-web' AND NOT EXISTS (SELECT 1 FROM kg_statement_scope o JOIN kg_collection ko ON ko.coll_id = o.coll_id"
                    + " WHERE o.stmt_rowid = s.stmt_rowid AND ko.name <> 'pflegeatlas-web')")) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            return ids;
        }));
        assertEquals("one Haus Ahorn in both collections", 1L, (long) this.store.read(c -> KgStore.queryLong(c,
                "SELECT count(DISTINCT subj) FROM kg_statement WHERE obj_val = 'Haus Ahorn'")));
        new DerivedService(this.cfg, this.store, () -> this.now).run();
        int ahornHere = 0;
        for (final String row : derived("d.kind IN (3, 4)")) {
            final String[] f = row.split("\\|", 7);
            if (f[4].equals("pflegeatlas-web")) {
                continue; // the third collection's own row: its facts are its basis
            }
            final JSONObject basis = new JSONObject(f[6]);
            for (final String side : new String[] {"a", "b"}) {
                for (int i = 0; i < basis.getJSONArray(side).length(); i++) {
                    assertFalse("a fact of another collection: " + row, third.contains(basis.getJSONArray(side).getString(i)));
                }
            }
            assertFalse(row, row.contains("hamburg"));
            if (f[2].equals("Haus Ahorn")) {
                ahornHere++;
                assertFalse("no place of Haus Ahorn is in its collection: " + row, basis.getJSONObject("match").getBoolean("area_confirmed"));
                assertEquals(row, 1, basis.getJSONArray("b").length());
            }
        }
        assertEquals(1, ahornHere);
    }

    @Test
    public void aSeekerInTwoCollectionsGetsItsSuggestionsInEach() throws Exception {
        corpus();
        final ExtractContext sw = ctx("software", "pflegeit-web");
        final Publisher.Doc again = doc("SWAGAIhost01", "https://www.pflegesoft.de/branchen", "pflegeit-web");
        publish(again, page(again, "{\"@type\":\"Organization\",\"name\":\"PflegeSoft GmbH\",\"url\":\"https://www.pflegesoft.de/\"}",
                "Für wen? Wir unterstützen Pflegeeinrichtungen bundesweit bei der Dienstplanung.", sw));
        new DerivedService(this.cfg, this.store, () -> this.now).run();
        final List<String> customers = derived("d.kind = 3");
        int stackfinder = 0;
        int pflegeit = 0;
        for (final String row : customers) {
            final String[] f = row.split("\\|", 7);
            stackfinder += f[3].equals("stackfinder-web") ? 1 : 0;
            pflegeit += f[3].equals("pflegeit-web") ? 1 : 0;
        }
        assertEquals(customers.toString(), 2, stackfinder);
        assertEquals("a viewer of the second collection sees the same suggestions: " + customers, 2, pflegeit);
    }

    /** Two care providers with complementary services for the same audience; Linde's area on {@code areaPage} only. */
    private void partners(final String lindeArea) throws Exception {
        final ExtractContext care = ctx("care", "edelsenior-web");
        final Publisher.Doc sonne = doc("SONNEEhost03", "https://www.sonne-pflege.de/", "edelsenior-web");
        publish(sonne, page(sonne, "{\"@type\":\"Organization\",\"name\":\"Sonne Pflegedienst GmbH\",\"url\":\"https://www.sonne-pflege.de/\"}",
                "Unsere Leistungen: Ambulante Pflege, Verhinderungspflege. Unser Angebot richtet sich an Senioren und pflegebedürftige"
                        + " Menschen in Potsdam und Umgebung.", care));
        final String linde = "{\"@type\":\"Organization\",\"name\":\"Tagespflege Linde GmbH\",\"url\":\"https://www.linde-tagespflege.de/\"}";
        final Publisher.Doc home = doc("LINDEEhost04", "https://www.linde-tagespflege.de/", "edelsenior-web");
        publish(home, page(home, linde, "Unsere Leistungen: Tagespflege. Unser Angebot richtet sich an Senioren und pflegebedürftige Menschen"
                + (lindeArea == null ? " in Potsdam und Umgebung." : "."), care));
        if (lindeArea != null) {
            final Publisher.Doc area = doc("LINDEAhost04", "https://www.linde-tagespflege.de/einzugsgebiet", lindeArea);
            publish(area, page(area, linde, "Unser Angebot richtet sich an Senioren und pflegebedürftige Menschen in Potsdam und Umgebung.",
                    ctx("care", lindeArea)));
        }
    }

    @Test
    public void complementaryProvidersInOnePlaceAreSuggestedPartners() throws Exception {
        partners(null);
        new DerivedService(this.cfg, this.store, () -> this.now).run();
        final List<String> rows = derived("d.kind = 4");
        assertEquals(rows.toString(), 1, rows.size());
        assertTrue(rows.get(0), rows.get(0).startsWith("4|Sonne Pflegedienst GmbH|Tagespflege Linde GmbH|edelsenior-web|edelsenior-web|"));
        assertTrue(rows.get(0), rows.get(0).contains("potsdam"));
    }

    @Test
    public void aPartnerAreaFromAnotherCollectionDoesNotCount() throws Exception {
        partners("pflegeatlas-web");
        new DerivedService(this.cfg, this.store, () -> this.now).run();
        // in edelsenior-web Linde names no place, in pflegeatlas-web no service: no collection holds both
        assertEquals(derived(null).toString(), 0, derived("d.kind = 4").size());
    }

    @Test
    public void aWeakLinkNeedsAPageOfTheTargetSiteInTheTargetCollection() throws Exception {
        corpus();
        // the operator, known by its VAT ID, also appears on a directory site in a third collection
        final String vat = ",\"vatID\":\"DE123456789\"";
        final Publisher.Doc imprint = doc("CAIMPRhost02", "https://www.lindenhof.de/impressum", "edelsenior-web");
        publish(imprint, page(imprint, "{\"@type\":\"Organization\",\"name\":\"Lindenhof Pflege gGmbH\",\"url\":\"https://www.lindenhof.de/\"" + vat
                + "}", null, ctx("care", "edelsenior-web")));
        final Publisher.Doc listing = doc("ATLASLhost05", "https://www.pflegeatlas.de/lindenhof", "pflegeatlas-web");
        publish(listing, page(listing, "{\"@type\":\"Organization\",\"name\":\"Lindenhof Pflege gGmbH\"" + vat + "}", null,
                ctx("care", "pflegeatlas-web")));
        assertEquals("one operator, by its VAT ID", 1L, (long) this.store.read(c -> KgStore.queryLong(c,
                "SELECT count(DISTINCT subj) FROM kg_statement WHERE obj_val = 'DE123456789'")));
        assertEquals("its scope reaches the third collection", 1L, (long) this.store.read(c -> KgStore.queryLong(c,
                "SELECT count(*) FROM kg_entity_scope s JOIN kg_collection k ON k.coll_id = s.coll_id WHERE k.name = 'pflegeatlas-web'"
                        + " AND s.ent_rowid = (SELECT subj FROM kg_statement WHERE obj_val = 'DE123456789' LIMIT 1)")));
        new DerivedService(this.cfg, this.store, () -> this.now).run();
        final List<String> linked = derived("d.kind = 1");
        assertEquals(linked.toString(), 1, linked.size());
        assertTrue(linked.get(0), linked.get(0).contains("|stackfinder-web|edelsenior-web|"));
    }

    @Test
    public void switchingTheLayerOffRemovesItsRowsWithFeedNotices() throws Exception {
        corpus();
        final DerivedService on = new DerivedService(this.cfg, this.store, () -> this.now);
        final int rows = on.run().inserted;
        assertTrue(rows > 0);
        final String stamp = this.store.read(c -> KgStore.getMeta(c, net.yacy.scoutro.knowledge.store.KgSchema.META_DERIVED_AT));
        final KgConfig offCfg = KgTestSupport.config(KgTestSupport.enabled(KgConfig.DERIVED_ENABLED, "false"));
        final DerivedService off = new DerivedService(offCfg, this.store, () -> this.now + 1);
        final DerivedService.Result r = off.tick(this.now + 1);
        assertEquals(rows, r.deleted);
        assertEquals(0L, (long) this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_derived")));
        assertEquals("only one pass", null, off.tick(this.now + 2));
        assertEquals("the last real pass keeps its time", stamp,
                this.store.read(c -> KgStore.getMeta(c, net.yacy.scoutro.knowledge.store.KgSchema.META_DERIVED_AT)));
        assertEquals(rows, derivedDeletes(viewer("stackfinder-web", "edelsenior-web")));
    }

    @Test
    public void anEntityThatGoesTakesItsDerivedRowsWithFeedNotices() throws Exception {
        corpus();
        new DerivedService(this.cfg, this.store, () -> this.now).run();
        final long birke = this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_derived d JOIN kg_statement s ON s.subj IN (d.a_ent, d.b_ent)"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'name' AND s.obj_val = 'Haus Birke'"));
        assertEquals("same_operator and a suggestion", 2L, birke);
        // the page goes, and with it the entity and its derived rows (cascade), before any further pass
        this.store.write(WriteClass.MAINTENANCE, 0, tx -> this.publisher.remove(tx, List.of("HBIRKEhost02"), null, this.now + 1));
        assertEquals(0L, (long) this.store.read(c -> KgStore.queryLong(c, "SELECT count(*) FROM kg_statement WHERE obj_val = 'Haus Birke'")));
        assertEquals(2, derivedDeletes(viewer("stackfinder-web", "edelsenior-web")));
        assertEquals("same_operator lies in one collection", 1, derivedDeletes(viewer("edelsenior-web")));
    }

    private int derivedDeletes(final Viewer v) throws Exception {
        int n = 0;
        for (final KgChangeLog.Item it : this.store.read(c -> KgChangeLog.read(c, null, v, 1000)).items) {
            if (it.kind == KgChangeLog.Kind.DERIVED && it.op == KgChangeLog.Op.DELETE) {
                n++;
            }
        }
        return n;
    }

    private int derivedItems(final Viewer v) throws Exception {
        int n = 0;
        for (final KgChangeLog.Item it : this.store.read(c -> KgChangeLog.read(c, null, v, 1000)).items) {
            if (it.kind == KgChangeLog.Kind.DERIVED && it.op == KgChangeLog.Op.UPSERT) {
                n++;
            }
        }
        return n;
    }

    private Viewer viewer(final String... names) throws Exception {
        return this.store.read(c -> {
            final java.util.Set<Integer> ids = new java.util.TreeSet<>();
            for (final String n : names) {
                ids.add((int) KgStore.queryLong(c, "SELECT coll_id FROM kg_collection WHERE name = '" + n + "'"));
            }
            return Viewer.of(ids);
        });
    }
}
