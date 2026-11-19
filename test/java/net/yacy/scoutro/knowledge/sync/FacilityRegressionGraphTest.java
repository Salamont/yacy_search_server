/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.solr.common.SolrInputDocument;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.read.KgReader;
import net.yacy.scoutro.knowledge.store.KgChangeLog;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Op;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * Regressions of the validation of the facilities' first stage on the production graph (docs/SCOUTRO_KG_FACILITIES.md, K2, K6
 * and the identity questions), end to end: invented pages of a second care provider ("Sonnen Pflege gGmbH", two houses of the same
 * name in two towns) through the real sync, the LLM tier with a controlled model and the identity resolution into a temporary graph.
 * <ul>
 * <li>K2: the imprint names a house before the operator's address; the house gets none (86 such addresses on imprint pages);</li>
 * <li>K6: a town and a firm the model takes for a facility get no address; an unclear name keeps its mark and gets none;</li>
 * <li>no false merge: two houses of one name in two towns stay two, also where an overview lists both;</li>
 * <li>the operator keeps its own contacts (K1) and gets none of a house;</li>
 * <li>a consumer limited to the collection reads no delete for an entity that was merged (M7).</li>
 * </ul>
 * Nothing reaches a model, a network service or a production graph.
 */
public class FacilityRegressionGraphTest extends LlmFixture {

    static final String CARE = "care-web";
    static final String SITE = "https://www.sonnen-pflege.de";

    @Override
    protected String[] settings() {
        return new String[] {KgConfig.COLLECTIONS, CARE, KgConfig.LLM_COLLECTIONS, CARE, KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                "scoutro.kg.vocab." + CARE, "care"};
    }

    @Override
    protected LlmServiceTest.Fake newModel() {
        final LlmServiceTest.Fake f = new LlmServiceTest.Fake();
        f.answer = FacilityRegressionGraphTest::answer;
        return f;
    }

    private static String facility(final String id, final String name, final String quote) {
        return "{\"id\":\"" + id + "\",\"type\":\"facility\",\"name\":\"" + name + "\",\"kind\":\"nursinghome\",\"quote\":\"" + quote + "\"}";
    }

    /** The model's answers per page, each name with a verbatim quote. */
    static String answer(final String user) {
        if (user.contains("Angaben gemäß § 5 TMG")) { // the imprint: a house's name before the operator's address
            return "{\"entities\":[" + facility("e1", "Haus Sonnenhof", "Haus Sonnenhof") + "],\"claims\":[]}";
        }
        if (user.contains("bietet 60 Plätze")) {
            return "{\"entities\":[" + facility("e1", "Haus Sonnenhof", "Das Haus Sonnenhof bietet 60 Plätze") + "],\"claims\":[]}";
        }
        if (user.contains("Unser Haus in Beispielstadt")) {
            return "{\"entities\":[" + facility("e1", "Haus Sonnenhof", "Haus Sonnenhof") + "],\"claims\":[]}";
        }
        if (user.contains("Unsere beiden Häuser tragen denselben Namen")) {
            return "{\"entities\":[" + facility("e1", "Haus Sonnenhof", "Haus Sonnenhof") + "],\"claims\":[]}";
        }
        if (user.contains("Unser Sommerfest in Bachstadt")) { // a town and the operator taken for facilities
            return "{\"entities\":[" + facility("e1", "Bachstadt", "Unser Sommerfest in Bachstadt") + ","
                    + facility("e2", "Sonnen Pflege gGmbH", "Sonnen Pflege gGmbH") + "],\"claims\":[]}";
        }
        if (user.contains("Wohnen im Alter")) { // a name without a sign of a place in its context: unclear
            return "{\"entities\":[" + facility("e1", "Sonnenschein", "Sonnenschein") + "],\"claims\":[]}";
        }
        return "{\"entities\":[],\"claims\":[]}";
    }

    static List<SolrInputDocument> docs(final LlmFixture f) throws Exception {
        final List<SolrInputDocument> docs = new ArrayList<>();
        docs.add(FacilityGraphTest.page(f, "IMPRINsonnen", "sun-imprint.html", SITE + "/impressum/", CARE, null));
        docs.add(FacilityGraphTest.page(f, "SONNENsonnen", "sun-facility-sonnenhof.html", SITE + "/einrichtungen/haus-sonnenhof/", CARE, null));
        docs.add(FacilityGraphTest.page(f, "BEISPIsonnen", "sun-facility-sonnenhof-beispielstadt.html", SITE + "/einrichtungen/haus-sonnenhof-beispielstadt/",
                CARE, null));
        docs.add(FacilityGraphTest.page(f, "OVERVIsonnen", "sun-overview.html", SITE + "/einrichtungen/", CARE, null));
        docs.add(FacilityGraphTest.page(f, "NEWSAAsonnen", "sun-news.html", SITE + "/ueber-uns/sommerfest/", CARE, null));
        docs.add(FacilityGraphTest.page(f, "WOHNENsonnen", "sun-living.html", SITE + "/angebote/wohnen/", CARE, null));
        return docs;
    }

    private void corpus() throws Exception {
        addAll(docs(this));
        settleSync();
        settleLlm();
    }

    private static String q(final String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private List<String> named(final String type, final String name) {
        return strings("SELECT DISTINCT e.public_id FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type JOIN kg_statement s ON s.subj = e.ent_rowid"
                + " JOIN kg_vocab p ON p.term_id = s.pred WHERE e.status = 1 AND t.name = " + q(type) + " AND p.name = 'name' AND s.obj_val = " + q(name)
                + " ORDER BY e.public_id");
    }

    private List<String> values(final String publicId, final String predicate) {
        return strings("SELECT s.obj_val FROM kg_statement s JOIN kg_entity e ON e.ent_rowid = s.subj JOIN kg_vocab p ON p.term_id = s.pred"
                + " WHERE e.public_id = " + q(publicId) + " AND p.name = " + q(predicate) + " AND s.obj_val IS NOT NULL ORDER BY s.obj_val");
    }

    private long statements(final String publicId, final String predicate) {
        return count("SELECT count(*) FROM kg_statement s JOIN kg_entity e ON e.ent_rowid = s.subj JOIN kg_vocab p ON p.term_id = s.pred"
                + " WHERE e.public_id = " + q(publicId) + " AND p.name = " + q(predicate));
    }

    /** The addresses of the facilities of this name, one entry per entity with an address. */
    private List<String> addresses(final String name) {
        final List<String> out = new ArrayList<>();
        for (final String id : named("facility", name)) {
            out.addAll(values(id, "address"));
        }
        java.util.Collections.sort(out);
        return out;
    }

    @Test
    public void theImprintGivesAHouseNoneOfTheOperatorsAddress() throws Exception {
        corpus();
        assertEquals(List.of("Lilienweg 8, 23456 Beispielstadt", "Rosenweg 5, 12345 Musterstadt"), addresses("Haus Sonnenhof"));
        assertEquals("no facility has the operator's address", 0L, count("SELECT count(*) FROM kg_statement s JOIN kg_entity e"
                + " ON e.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = e.type WHERE t.name = 'facility' AND s.obj_val LIKE '%Hauptstraße 10%'"));
    }

    @Test
    public void twoHousesOfOneNameInTwoTownsStayTwo() throws Exception {
        corpus();
        final Map<String, String> byAddress = new HashMap<>();
        for (final String id : named("facility", "Haus Sonnenhof")) {
            final List<String> a = values(id, "address");
            assertTrue(id + " has at most one address: " + a, a.size() <= 1);
            if (!a.isEmpty()) {
                assertEquals("one entity per house", null, byAddress.put(a.get(0), id));
            }
        }
        assertEquals(byAddress.toString(), 2, byAddress.size());
        // each house keeps its own phone; the operator's phone is no house's
        final List<String> phones = new ArrayList<>();
        for (final String id : byAddress.values()) {
            phones.addAll(values(id, "phone"));
        }
        java.util.Collections.sort(phones);
        assertEquals(List.of("+49305555501", "+49405555502"), phones);
        // no redirect joins anything into either house
        for (final String id : byAddress.values()) {
            assertEquals(0L, count("SELECT count(*) FROM kg_entity_redirect r JOIN kg_entity e ON e.ent_rowid = r.target_rowid WHERE e.public_id = " + q(id)));
        }
    }

    @Test
    public void everyFacilityHasAtMostOneAddressAndPostalCode() throws Exception {
        corpus();
        assertEquals(0L, count("SELECT count(*) FROM (SELECT s.subj FROM kg_statement s JOIN kg_vocab p ON p.term_id = s.pred JOIN kg_entity e"
                + " ON e.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = e.type WHERE t.name = 'facility' AND e.status = 1"
                + " AND p.name IN ('address', 'postal_code') GROUP BY s.subj, p.name HAVING count(*) > 1)"));
    }

    @Test
    public void aTownAndAFirmTakenForFacilitiesGetNoAddress() throws Exception {
        corpus();
        for (final String name : new String[] {"Bachstadt", "Sonnen Pflege gGmbH"}) {
            for (final String id : named("facility", name)) {
                assertEquals(name, List.of(), values(id, "address"));
                assertEquals(name, List.of(), values(id, "postal_code"));
            }
        }
        // no facility at all whose address's town is its name
        assertEquals(0L, count("SELECT count(*) FROM kg_statement n JOIN kg_vocab pn ON pn.term_id = n.pred JOIN kg_statement l ON l.subj = n.subj"
                + " JOIN kg_vocab pl ON pl.term_id = l.pred JOIN kg_entity e ON e.ent_rowid = n.subj JOIN kg_vocab t ON t.term_id = e.type"
                + " WHERE t.name = 'facility' AND pn.name = 'name' AND pl.name = 'locality' AND lower(n.obj_val) = lower(l.obj_val)"));
    }

    @Test
    public void anUnclearNameKeepsItsMarkAndGetsNoAddress() throws Exception {
        corpus();
        final List<String> ids = named("facility", "Sonnenschein");
        assertEquals("the model's name is kept, marked: " + ids, 1, ids.size());
        assertEquals(List.of(), values(ids.get(0), "address"));
        assertEquals(1L, statements(ids.get(0), "type_unconfirmed"));
    }

    @Test
    public void theOperatorKeepsItsOwnContactsAndNoneOfAHouse() throws Exception {
        corpus();
        final List<String> ops = named("organization", "Sonnen Pflege gGmbH");
        assertEquals(ops.toString(), 1, ops.size());
        assertEquals(List.of("Hauptstraße 10, 12345 Musterstadt"), values(ops.get(0), "address"));
        assertEquals(List.of("+49305555555"), values(ops.get(0), "phone"));
        assertEquals(List.of("info@sonnen-pflege.de"), values(ops.get(0), "email"));
        for (final String p : new String[] {"address", "phone", "email"}) {
            for (final String v : values(ops.get(0), p)) {
                assertFalse(p + " " + v, v.contains("Rosenweg") || v.contains("Lilienweg") || v.contains("Sonnenweg") || v.contains("Bachweg")
                        || v.contains("5555501") || v.contains("5555502") || v.contains("sonnenhof@") || v.contains("beispielstadt@"));
            }
        }
    }

    /** Hermes, limited to the collection, reads every merged entity as a redirect to an entity it can read, never as a delete. */
    @Test
    public void aConsumerOfTheCollectionReadsNoDeleteForAMergedEntity() throws Exception {
        corpus();
        final KgReader reader = new KgReader(this.store, this.cfg, this.clock::get);
        final Viewer v = reader.viewer(List.of(CARE));
        final Map<String, KgChangeLog.Item> seen = new HashMap<>();
        String cursor = KgChangeLog.cursor(this.store.epoch(), 0L);
        for (int i = 0; i < 1000; i++) {
            final String at = cursor;
            final KgChangeLog.Page p = this.store.read(c -> KgChangeLog.read(c, at, v, 200));
            for (final KgChangeLog.Item it : p.items) {
                if (it.kind == KgChangeLog.Kind.ENTITY) {
                    seen.put(it.id, it);
                }
            }
            cursor = p.next;
            if (!p.hasMore) {
                break;
            }
        }
        final TreeSet<String> active = new TreeSet<>(strings("SELECT public_id FROM kg_entity WHERE status = 1"));
        for (final String merged : strings("SELECT public_id FROM kg_entity_redirect")) {
            final KgChangeLog.Item it = seen.get(merged);
            assertNotNull("the merged entity " + merged + " reaches the consumer", it);
            assertEquals(merged, Op.REDIRECT, it.op);
            assertTrue(it.redirectTo, active.contains(it.redirectTo));
        }
        for (final KgChangeLog.Item it : seen.values()) {
            if (it.op == Op.DELETE) {
                assertFalse("a delete of an entity that only moved: " + it.id, active.contains(it.id));
            }
        }
    }
}
