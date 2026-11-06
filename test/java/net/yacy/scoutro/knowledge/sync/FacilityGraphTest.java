/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.solr.common.SolrInputDocument;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.read.KgExport;
import net.yacy.scoutro.knowledge.read.KgReader;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * The first quality stage for facilities end to end (docs/SCOUTRO_KG_FACILITIES.md): the invented pages of
 * {@code test/scoutro-kg-facilities/} as YaCy's parser gives them, in the embedded Solr core, read by the real sync (tiers 1 and
 * 2) and the LLM tier with a controlled model, into a temporary graph. One care provider runs three facilities on one domain:
 * <ul>
 * <li>each facility is one entity with its own address, also where the overview, the contact page and the detail page (under
 * two URLs) name it and the model guesses another kind on each;</li>
 * <li>the operator keeps its imprint's address and gets none of a facility's;</li>
 * <li>every address value has evidence on the page it was read from, no personal mailbox is kept;</li>
 * <li>an address named as a site carries its postal code and town;</li>
 * <li>the export keeps its records and fields.</li>
 * </ul>
 * Nothing reaches a model, a network service or a production graph. The API used here is the one of 0.14.1, so the same tests
 * show the state before the change.
 */
public class FacilityGraphTest extends LlmFixture {

    static final String CARE = "care-web";
    static final String SOFTWARE = "software-web";
    static final String SITE = "https://www.muster-altenhilfe.de";

    @Override
    protected String[] settings() {
        return new String[] {KgConfig.COLLECTIONS, CARE + "," + SOFTWARE, KgConfig.LLM_COLLECTIONS, CARE, KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                "scoutro.kg.vocab." + CARE, "care", "scoutro.kg.vocab." + SOFTWARE, "software"};
    }

    @Override
    protected LlmServiceTest.Fake newModel() {
        final LlmServiceTest.Fake f = new LlmServiceTest.Fake();
        f.answer = FacilityGraphTest::answer;
        return f;
    }

    /** The model's answers per page: names with verbatim quotes and, on each page, another guess of a kind. */
    static String answer(final String user) {
        if (user.contains("betreibt das Haus Lindenhof, das Seniorenzentrum Am Markt und das Haus Am See")) { // the overview
            return "{\"entities\":["
                    + "{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Lindenhof\",\"kind\":\"nursinghome\",\"quote\":\"betreibt das Haus Lindenhof\"},"
                    + "{\"id\":\"e2\",\"type\":\"facility\",\"name\":\"Seniorenzentrum Am Markt\",\"kind\":\"nursinghome\",\"quote\":\"das Seniorenzentrum Am Markt\"},"
                    + "{\"id\":\"e3\",\"type\":\"facility\",\"name\":\"Haus Am See\",\"kind\":\"assistedliving\",\"quote\":\"und das Haus Am See\"},"
                    + "{\"id\":\"e4\",\"type\":\"site\",\"name\":\"Lindenweg 12, 12345 Musterstadt\",\"quote\":\"Lindenweg 12. 12345 Musterstadt\"}],"
                    + "\"claims\":["
                    + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\"Die Muster Altenhilfe gGmbH betreibt das Haus Lindenhof\"},"
                    + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e2\",\"quote\":\"Die Muster Altenhilfe gGmbH betreibt das Haus Lindenhof, das Seniorenzentrum Am Markt\"},"
                    + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e3\",\"quote\":\"Die Muster Altenhilfe gGmbH betreibt das Haus Lindenhof, das Seniorenzentrum Am Markt und das Haus Am See\"}]}";
        }
        if (user.contains("Rufen Sie direkt in unseren Häusern an")) { // the contact page
            return "{\"entities\":["
                    + "{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Lindenhof\",\"kind\":\"assistedliving\",\"quote\":\"im Haus Lindenhof\"},"
                    + "{\"id\":\"e2\",\"type\":\"facility\",\"name\":\"Seniorenzentrum Am Markt\",\"kind\":\"adultdaycare\",\"quote\":\"im Seniorenzentrum Am Markt\"}],"
                    + "\"claims\":[]}";
        }
        if (user.contains("Sommerfest")) {
            return "{\"entities\":[{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Lindenhof\",\"kind\":\"assistedliving\","
                    + "\"quote\":\"im Haus Lindenhof ein fröhliches Sommerfest\"}],\"claims\":[]}";
        }
        if (user.contains("vollstationäre Pflegeeinrichtung")) {
            return "{\"entities\":[{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Lindenhof\",\"kind\":\"nursinghome\","
                    + "\"quote\":\"Das Haus Lindenhof ist eine vollstationäre Pflegeeinrichtung\"}],\"claims\":[]}";
        }
        if (user.contains("Mitten in Beispielstadt")) {
            return "{\"entities\":[{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Seniorenzentrum Am Markt\",\"kind\":\"nursinghome\","
                    + "\"quote\":\"Seniorenzentrum Am Markt\"}],\"claims\":[]}";
        }
        if (user.contains("Betreutes Wohnen am Ufer")) {
            return "{\"entities\":[{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Am See\",\"kind\":\"assistedliving\","
                    + "\"quote\":\"Haus Am See\"}],\"claims\":[]}";
        }
        return "{\"entities\":[],\"claims\":[]}";
    }

    /** A page of a fixture as the crawl stores it: YaCy's text and title, the CMS plugin's organisation node on the care site. */
    private SolrInputDocument page(final String id, final String file, final String url, final String collection, final String ld) throws Exception {
        final String[] p = net.yacy.scoutro.knowledge.extract.FacilityPageTest.parsed(file, url);
        return doc(id, url, collection, ld, p[0], p[1]);
    }

    private void corpus() throws Exception {
        final String ld = net.yacy.scoutro.knowledge.extract.FacilityPageTest.OPERATOR_LD;
        final List<SolrInputDocument> docs = new ArrayList<>();
        docs.add(page("IMPRINmalten", "care-operator-imprint.html", SITE + "/impressum/", CARE, ld));
        docs.add(page("OVERVImalten", "care-facilities-overview.html", SITE + "/einrichtungen/", CARE, ld));
        docs.add(page("CONTACmalten", "care-contact.html", SITE + "/kontakt/", CARE, ld));
        docs.add(page("LINDENmalten", "care-facility-lindenhof.html", SITE + "/einrichtungen/haus-lindenhof/", CARE, ld));
        docs.add(page("MARKTAmalten", "care-facility-am-markt.html", SITE + "/einrichtungen/seniorenzentrum-am-markt", CARE, ld));
        docs.add(page("SEEAAAmalten", "care-facility-am-see.html", SITE + "/einrichtungen/haus-am-see", CARE, ld));
        docs.add(page("SEESLAmalten", "care-facility-am-see.html", SITE + "/einrichtungen/haus-am-see/", CARE, ld));
        docs.add(page("NEWSAAmalten", "care-news.html", SITE + "/aktuelles/sommerfest/", CARE, ld));
        docs.add(page("ITCONTbeisit", "software-contact.html", "https://www.beispiel-it.de/kontakt/", SOFTWARE,
                "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"Beispiel IT GmbH\",\"url\":\"https://www.beispiel-it.de/\"}"));
        addAll(docs);
        settleSync();
        settleLlm();
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private static String q(final String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** Active entities of a type with a statement {@code name} of this value, as public IDs. */
    private List<String> named(final String type, final String name) {
        return strings("SELECT DISTINCT e.public_id FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type JOIN kg_statement s ON s.subj = e.ent_rowid"
                + " JOIN kg_vocab p ON p.term_id = s.pred WHERE e.status = 1 AND t.name = " + q(type) + " AND p.name = 'name' AND s.obj_val = " + q(name)
                + " ORDER BY e.public_id");
    }

    /** The literal values of an entity's statements of a predicate. */
    private List<String> values(final String publicId, final String predicate) {
        return strings("SELECT s.obj_val FROM kg_statement s JOIN kg_entity e ON e.ent_rowid = s.subj JOIN kg_vocab p ON p.term_id = s.pred"
                + " WHERE e.public_id = " + q(publicId) + " AND p.name = " + q(predicate) + " AND s.obj_val IS NOT NULL ORDER BY s.obj_val");
    }

    /** The facilities of this name that carry an address. */
    private List<String> addressed(final String name) {
        final List<String> out = new ArrayList<>();
        for (final String id : named("facility", name)) {
            if (!values(id, "address").isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------------ tests

    @Test
    public void eachFacilityOfTheOperatorIsOneEntityWithItsOwnAddress() throws Exception {
        corpus();
        final String[][] expected = {{"Haus Lindenhof", "Lindenweg 12, 12345 Musterstadt", "12345", "Musterstadt"},
                {"Seniorenzentrum Am Markt", "Am Markt 5, 23456 Beispielstadt", "23456", "Beispielstadt"},
                {"Haus Am See", "Seeufer 3a, 34567 Probehausen", "34567", "Probehausen"}};
        for (final String[] f : expected) {
            final List<String> ids = addressed(f[0]);
            assertEquals(f[0] + ": " + named("facility", f[0]), 1, ids.size());
            assertEquals(f[0], List.of(f[1]), values(ids.get(0), "address"));
            assertEquals(f[0], List.of(f[2]), values(ids.get(0), "postal_code"));
            assertEquals(f[0], List.of(f[3]), values(ids.get(0), "locality"));
        }
        // the detail page under two URLs, the overview and the contact page name one facility each
        assertEquals(named("facility", "Haus Am See").toString(), 1, named("facility", "Haus Am See").size());
        assertEquals(named("facility", "Seniorenzentrum Am Markt").toString(), 1, named("facility", "Seniorenzentrum Am Markt").size());
    }

    @Test
    public void theFacilitysPhoneAndRoleMailboxAreItsOwn() throws Exception {
        corpus();
        final String linden = addressed("Haus Lindenhof").get(0);
        assertEquals(List.of("+49302222222"), values(linden, "phone"));
        assertEquals(List.of("haus.lindenhof@muster-altenhilfe.de"), values(linden, "email"));
        final String markt = addressed("Seniorenzentrum Am Markt").get(0);
        assertEquals(List.of("+49403333333"), values(markt, "phone"));
        assertEquals(List.of("pflege.am-markt@muster-altenhilfe.de"), values(markt, "email"));
    }

    @Test
    public void theOperatorKeepsItsOwnAddressOnly() throws Exception {
        corpus();
        final List<String> ops = named("organization", "Muster Altenhilfe gGmbH");
        assertEquals(ops.toString(), 1, ops.size());
        assertEquals(List.of("Trägerstraße 1, 10115 Musterstadt"), values(ops.get(0), "address"));
        assertEquals(List.of("10115"), values(ops.get(0), "postal_code"));
        for (final String p : new String[] {"phone", "email", "office_hours", "opening_hours"}) {
            for (final String v : values(ops.get(0), p)) {
                assertFalse(p + " " + v, v.contains("2222222") || v.contains("3333333") || v.contains("4444444") || v.contains("haus.lindenhof")
                        || v.contains("am-markt") || v.contains("amsee") || v.contains("Montag bis Freitag"));
            }
        }
        // no facility carries the operator's address
        assertEquals(0L, count("SELECT count(*) FROM kg_statement s JOIN kg_entity e ON e.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = e.type"
                + " WHERE t.name = 'facility' AND s.obj_val LIKE '%Trägerstraße%'"));
    }

    @Test
    public void differentGuessesOfTheKindKeepOneFacilityAndAreMarkedForReview() throws Exception {
        corpus();
        assertEquals(addressed("Haus Lindenhof").toString(), 1, addressed("Haus Lindenhof").size());
        assertTrue("the differing guesses are marked: " + strings("SELECT code || ' ' || coalesce(detail, '') FROM kg_event"),
                count("SELECT count(*) FROM kg_event WHERE code = 'identity_review' AND detail LIKE '%Haus Lindenhof%'") >= 1L);
        // the news page names it without an address: a separate mention, shown as a possible duplicate, never merged by its name
        final List<String> all = named("facility", "Haus Lindenhof");
        assertTrue(all.toString(), all.size() <= 2);
    }

    @Test
    public void anAddressNamedAsASiteCarriesItsPostalCodeAndTown() throws Exception {
        corpus();
        final List<String> sites = named("site", "Lindenweg 12, 12345 Musterstadt");
        assertEquals(sites.toString(), 1, sites.size());
        assertEquals(List.of("12345"), values(sites.get(0), "postal_code"));
        assertEquals(List.of("Musterstadt"), values(sites.get(0), "locality"));
    }

    @Test
    public void everyAddressValueHasEvidenceOnItsPage() throws Exception {
        corpus();
        final List<String> rows = strings("SELECT p.name || '|' || s.obj_val || '|' || coalesce(ev.locator, '') || '|' || coalesce(ev.excerpt, '')"
                + " || '|' || d.url FROM kg_statement s JOIN kg_vocab p ON p.term_id = s.pred LEFT JOIN kg_evidence ev ON ev.stmt_rowid = s.stmt_rowid"
                + " LEFT JOIN kg_doc d ON d.doc_rowid = ev.doc_rowid WHERE p.name IN ('address', 'postal_code', 'locality')");
        assertFalse(rows.isEmpty());
        for (final String r : rows) {
            final String[] f = r.split("\\|", -1);
            assertFalse("a value without evidence: " + r, f[2].isEmpty() || f[4].isEmpty());
            assertTrue("a locator into the page: " + r, f[2].startsWith("text:") || f[2].startsWith("jsonld:"));
            final String probe = "address".equals(f[0]) ? f[1].replaceAll(".*(\\d{5}).*", "$1") : f[1];
            assertTrue("the excerpt shows the value: " + r, f[3].contains(probe));
        }
    }

    @Test
    public void noPersonalMailboxAndNoPersonInTheGraph() throws Exception {
        corpus();
        assertEquals(0L, count("SELECT count(*) FROM kg_statement WHERE obj_val LIKE '%p.beispiel@%' OR obj_val LIKE '%Petra%' OR obj_val LIKE '%Erika%'"));
        assertEquals(0L, count("SELECT count(*) FROM kg_evidence WHERE excerpt LIKE '%Petra Beispiel%' OR excerpt LIKE '%Erika Musterfrau%'"));
    }

    @Test
    public void anotherCollectionKeepsItsContacts() throws Exception {
        corpus();
        final List<String> it = named("organization", "Beispiel IT GmbH");
        assertEquals(it.toString(), 1, it.size());
        assertEquals(List.of("Musterallee 7, 34117 Kassel"), values(it.get(0), "address"));
        assertEquals(List.of("+495615555555"), values(it.get(0), "phone"));
        assertEquals(List.of("kontakt@beispiel-it.de"), values(it.get(0), "email"));
    }

    @Test
    public void theExportKeepsItsRecordsAndFields() throws Exception {
        corpus();
        final KgReader reader = new KgReader(this.store, this.cfg, this.clock::get);
        final Viewer v = reader.viewer(List.of(CARE));
        final KgExport export = new KgExport(reader);
        String cursor = null;
        final List<JSONObject> records = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final JSONObject page = export.page(cursor, 50, true, v);
            assertEquals("scoutro.kg.v1", page.getString("schema"));
            for (final String k : new String[] {"epoch", "as_of_seq", "items", "complete", "next", "next_changes"}) {
                assertTrue(k + " " + page.keySet(), page.has(k));
            }
            final JSONArray items = page.getJSONArray("items");
            for (int j = 0; j < items.length(); j++) {
                records.add(items.getJSONObject(j));
            }
            if (page.getBoolean("complete")) {
                break;
            }
            cursor = page.getString("next");
        }
        boolean address = false;
        for (final JSONObject r : records) {
            if ("entity".equals(r.optString("record"))) {
                for (final String k : new String[] {"id", "type", "name", "quality", "first_seen", "last_confirmed", "counts", "hosts", "possible_duplicates"}) {
                    assertTrue(k + " " + r.keySet(), r.has(k));
                }
            } else if ("statement".equals(r.optString("record"))) {
                for (final String k : new String[] {"id", "subject", "predicate", "object", "quality", "certainty", "sources"}) {
                    assertTrue(k + " " + r.keySet(), r.has(k));
                }
                if ("address".equals(r.optString("predicate")) && r.optJSONObject("object").optString("value").startsWith("Lindenweg")) {
                    address = true;
                    final JSONArray ev = r.getJSONArray("evidence");
                    assertTrue(r.toString(), ev.length() >= 1);
                    for (final String k : new String[] {"doc_id", "url", "kind", "locator", "excerpt", "loaded_at"}) {
                        assertTrue(k + " " + ev.getJSONObject(0).keySet(), ev.getJSONObject(0).has(k));
                    }
                }
            }
        }
        assertTrue("the facility's address is exported with its evidence", address);
        // the other collection's records stay out
        for (final JSONObject r : records) {
            assertFalse(r.toString(), r.toString().contains("Beispiel IT GmbH"));
        }
    }
}
