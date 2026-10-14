package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.extract.ServiceOfferEvidenceTest;

/** Mock model, real shared reading, validator and publication, isolated Solr/SQLite only. */
public class ServiceOfferPublicationTest extends LlmFixture {
    @Override
    protected String[] settings() {
        return new String[] {KgConfig.COLLECTIONS, "c1", KgConfig.LLM_COLLECTIONS, "c1", KgConfig.LLM_MAX_DOCS_PER_HOST, "25"};
    }

    private List<String> statements(final String id) {
        return strings("SELECT v.name || ':' || coalesce(s.obj_val, o.obj_val, '') FROM kg_evidence e"
                + " JOIN kg_doc d ON d.doc_rowid=e.doc_rowid JOIN kg_statement s ON s.stmt_rowid=e.stmt_rowid"
                + " JOIN kg_vocab v ON v.term_id=s.pred LEFT JOIN kg_statement o ON o.subj=s.obj_ent"
                + " AND o.pred=(SELECT term_id FROM kg_vocab WHERE name='name' AND kind=2)"
                + " WHERE e.tier=3 AND d.doc_id='" + id + "' ORDER BY 1");
    }

    private void read(final String id, final String text, final JSONObject answer) throws Exception {
        this.model.answer = u -> answer.toString();
        add(id, "https://www.beispiel-dienste.example/", "c1", LD, text, "Leistungen");
        settleSync();
        settleLlm();
        assertEquals("1:", llmStatus(id));
    }

    @Test
    public void rejectedMenuEntitiesLeaveNoRelationsValuesOrPublishedNames() throws Exception {
        final String privacy = "Wir speichern keine personenbezogenen Daten unserer Webseitenbesucher.";
        final String menu = "Muster Pflege gGmbH\nLeistungen\nTagespflege\nKontakt\nNews\nDownloads\nJobangebote\n" + privacy;
        final JSONArray entities = new JSONArray(), claims = new JSONArray(), values = new JSONArray();
        for (final String name : List.of("News", "Downloads", "Jobangebote", privacy)) {
            final String id = "e" + (entities.length() + 1);
            entities.put(ServiceOfferEvidenceTest.entity(id, name, name));
            claims.put(ServiceOfferEvidenceTest.claim("k1", id, menu));
            values.put(new JSONObject().put("subject", id).put("predicate", "category").put("quote", "Tagespflege"));
        }
        read("AAAAAAoff001", menu, ServiceOfferEvidenceTest.answer(entities, claims, values));
        assertEquals("no tier-3 name, offers relation or value may remain", List.of(), statements("AAAAAAoff001"));
        assertEquals("no orphan service entity was resolved", 0L, count("SELECT count(*) FROM kg_entity e JOIN kg_vocab v ON v.term_id=e.type WHERE v.name='service'"));
        assertTrue(this.llm.refusals("entity_service_not_offered", 100).getJSONArray("samples").length() > 0);
    }

    @Test
    public void aRealOfferAndItsNamedPriceReachPublicationDespiteTheMenu() throws Exception {
        final String quote = "Muster Pflege gGmbH bietet Web&Mail für Kunden an";
        final String price = "Web&Mail: 12 EUR pro Monat";
        read("AAAAAAoff002", "Navigation\nWeb&Mail\nKontakt\n" + quote + ". " + price + ".",
                ServiceOfferEvidenceTest.answer(new JSONArray().put(ServiceOfferEvidenceTest.entity("e1", "Web&Mail", "Web&Mail")),
                        new JSONArray().put(ServiceOfferEvidenceTest.claim("k1", "e1", quote)),
                        new JSONArray().put(new JSONObject().put("subject", "e1").put("predicate", "price").put("quote", price))));
        final List<String> got = statements("AAAAAAoff002");
        assertTrue(got.toString(), got.contains("name:Web&Mail"));
        assertTrue(got.toString(), got.contains("offers:Web&Mail"));
        assertTrue(got.toString(), got.stream().anyMatch(s -> s.startsWith("price:") && s.contains("12")));
    }
}
