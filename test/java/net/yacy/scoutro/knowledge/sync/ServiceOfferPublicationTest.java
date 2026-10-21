package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.extract.KnowledgePrompt;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.RequestBudget;
import net.yacy.scoutro.knowledge.extract.ServiceOfferEvidenceTest;
import net.yacy.scoutro.knowledge.publish.Terms;

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

    /**
     * 0.12.0: the page of one service names it in its title; the model's service of that page is offered structurally, with
     * the confidence of a structural offer, also without an offer sentence. The same text on a news page stays no offer.
     */
    @Test
    public void theTitleOfAServicesOwnPageShowsTheModelsServiceStructurally() throws Exception {
        final String text = "SAP DRC. SAP Document and Reporting Compliance automatisiert die Meldungen an die Finanzbehörden.";
        final JSONObject answer = ServiceOfferEvidenceTest.answer(new JSONArray().put(ServiceOfferEvidenceTest.entity("e1", "SAP DRC", "SAP DRC")),
                new JSONArray().put(ServiceOfferEvidenceTest.claim("k1", "e1", text.substring(0, 8))), new JSONArray());
        this.model.answer = u -> answer.toString();
        add("AAAAAAoff101", "https://www.beispiel-dienste.example/sap-beratung/sap-drc/", "c1", LD, text, "SAP DRC | Beispiel Dienste");
        add("AAAAAAoff102", "https://www.beispiel-dienste.example/news/sap-drc/", "c1", LD, text, "SAP DRC | Beispiel Dienste");
        settleSync();
        settleLlm();
        final List<String> page = statements("AAAAAAoff101");
        assertTrue(page.toString(), page.contains("name:SAP DRC"));
        assertTrue(page.toString(), page.contains("offers:SAP DRC"));
        assertEquals(net.yacy.scoutro.knowledge.extract.BusinessRules.STRUCTURAL_CONFIDENCE, Double.parseDouble(strings("SELECT max(e.confidence) FROM kg_evidence e"
                + " JOIN kg_doc d ON d.doc_rowid=e.doc_rowid WHERE e.tier=3 AND d.doc_id='AAAAAAoff101'").get(0)), 1e-9);
        assertEquals("a news page is no page of a service", List.of(), statements("AAAAAAoff102"));
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

    @Test
    public void anOlderCachedSubsetPassesPreparationWithoutCallingTheModel() throws Exception {
        final String id = "AAAAAAoff003";
        final String text = "Muster Pflege gGmbH\nNavigation\nJobangebote\nKontakt";
        final JSONObject old = ServiceOfferEvidenceTest.answer(
                new JSONArray().put(ServiceOfferEvidenceTest.entity("e1", "Jobangebote", "Jobangebote").put("at", text.indexOf("Jobangebote"))),
                new JSONArray().put(ServiceOfferEvidenceTest.claim("k1", "e1", text).put("at", 0)), new JSONArray());
        readCache(id, text, old);
        assertEquals(List.of(), statements(id));
    }

    private void readCache(final String id, final String text, final JSONObject old) throws Exception {
        for (final String kind : List.of("entities", "claims", "values")) {
            final JSONArray items = old.getJSONArray(kind);
            for (int i = 0; i < items.length(); i++) {
                items.getJSONObject(i).put("at", 0);
            }
        }
        add(id, "https://www.beispiel-dienste.example/", "c1", LD, text, "Leistungen");
        settleSync();
        final SolrDoc d = this.source.get(List.of(id), SolrDoc.PROCESS_FIELDS).get(id);
        final LlmReading.Page page = LlmReading.page(this.cfg, d, text);
        final KnowledgePrompt prompt = KnowledgePrompt.defaults();
        final RequestBudget.Part part = page.reading.plan(LlmReading.budget(this.cfg, prompt, this.model.window()), page.chunks).parts.get(0);
        final byte[] key = LlmExtractor.cacheKey(prompt.hash, this.model.model(), part.chunk, page.reading.title,
                page.reading.domain, page.reading.language, part.known, page.reading.kinds);
        this.store.write(StorageGuard.WriteClass.SYSTEM, 8192L, tx -> {
            final int extId = new Terms().llmExtractor(tx, this.model.model(), prompt.hash);
            assertTrue(ExtractionCache.put(tx, key, extId, ExtractionCache.STATUS_OK, old, this.clock.get()));
            return null;
        });
        final List<String> cachedBytes = strings("SELECT hex(result) FROM kg_extraction");
        this.model.answer = u -> { throw new AssertionError("a cached reading must not call the model"); };
        settleLlm();
        assertEquals("1:", llmStatus(id));
        assertEquals(0, this.model.calls.get());
        assertEquals(1L, this.llm.status().getJSONObject("processed").getLong("cacheHits"));
        assertEquals("the old cache is guarded, not deleted", 1L, count("SELECT count(*) FROM kg_extraction"));
        assertEquals("the accepted subset bytes stay unchanged", cachedBytes, strings("SELECT hex(result) FROM kg_extraction"));
    }

    private static JSONObject listAnswer(final String text) throws Exception {
        return ServiceOfferEvidenceTest.answer(
                new JSONArray().put(ServiceOfferEvidenceTest.entity("e1", "Tagespflege", text)),
                new JSONArray().put(ServiceOfferEvidenceTest.claim("k1", "e1", text)),
                new JSONArray().put(new JSONObject().put("subject", "e1").put("predicate", "price").put("quote", text)));
    }

    private void assertOtherProvider(final String id) {
        final List<String> got = statements(id);
        assertTrue("the other provider's genuine service stays: " + got, got.contains("name:Tagespflege"));
        assertTrue("its price stays: " + got, got.stream().anyMatch(s -> s.startsWith("price:") && s.contains("12")));
        assertTrue("no false offer by the page operator: " + got, got.stream().noneMatch(s -> s.startsWith("offers:")));
    }

    @Test
    public void aThirdPartyListPublishesTheServiceAndPriceWithoutTheOperatorsOffer() throws Exception {
        final String text = "Muster Pflege gGmbH verweist auf Andere Pflege GmbH, deren Leistungen: Tagespflege, 12 EUR pro Tag";
        read("AAAAAAoff004", text, listAnswer(text));
        assertOtherProvider("AAAAAAoff004");
        assertTrue(this.llm.refusals("claim_offer_not_stated", 100).getJSONArray("samples").length() > 0);
    }

    @Test
    public void aNegatedHeadingPublishesNoNameRelationOrPrice() throws Exception {
        final String text = "Muster Pflege gGmbH – Nicht angebotene Leistungen: Tagespflege, 12 EUR pro Tag";
        read("AAAAAAoff005", text, listAnswer(text));
        assertEquals(List.of(), statements("AAAAAAoff005"));
        assertEquals(0L, count("SELECT count(*) FROM kg_entity e JOIN kg_vocab v ON v.term_id=e.type WHERE v.name='service'"));
    }

    @Test
    public void anOldCachedThirdPartyListCannotPublishTheOperatorsOffer() throws Exception {
        final String text = "Muster Pflege gGmbH verweist auf Andere Pflege GmbH, deren Leistungen:\n- Tagespflege, 12 EUR pro Tag";
        readCache("AAAAAAoff006", text, listAnswer(text));
        assertOtherProvider("AAAAAAoff006");
    }

    @Test
    public void anOldCachedNegatedListPublishesNoNameRelationOrPrice() throws Exception {
        final String text = "Muster Pflege gGmbH – Nicht angebotene Leistungen:\n- Tagespflege, 12 EUR pro Tag";
        readCache("AAAAAAoff007", text, listAnswer(text));
        assertEquals(List.of(), statements("AAAAAAoff007"));
        assertEquals(0L, count("SELECT count(*) FROM kg_entity e JOIN kg_vocab v ON v.term_id=e.type WHERE v.name='service'"));
    }

    @Test
    public void aPositiveOwnedListStillPublishesItsOfferAndPrice() throws Exception {
        final String text = "Muster Pflege gGmbH – Unsere Leistungen:\n- Tagespflege, 12 EUR pro Tag";
        read("AAAAAAoff008", text, listAnswer(text));
        final List<String> got = statements("AAAAAAoff008");
        assertTrue(got.toString(), got.contains("name:Tagespflege"));
        assertTrue(got.toString(), got.contains("offers:Tagespflege"));
        assertTrue(got.toString(), got.stream().anyMatch(s -> s.startsWith("price:") && s.contains("12")));
    }

    private static JSONObject shortPriceAnswer(final String text) throws Exception {
        final JSONObject a = listAnswer(text);
        a.getJSONArray("entities").getJSONObject(0).put("quote", "Tagespflege, 12 EUR pro Tag");
        a.getJSONArray("values").getJSONObject(0).put("quote", "Tagespflege, 12 EUR pro Tag");
        return a;
    }

    @Test
    public void aShortFreshPriceQuoteStillReadsTheDeniedSourceHeading() throws Exception {
        final String text = "Muster Pflege gGmbH – Nicht angebotene Leistungen: Tagespflege, 12 EUR pro Tag";
        read("AAAAAAoff009", text, shortPriceAnswer(text));
        assertEquals(List.of(), statements("AAAAAAoff009"));
        assertEquals(0L, count("SELECT count(*) FROM kg_entity e JOIN kg_vocab v ON v.term_id=e.type WHERE v.name='service'"));
    }

    @Test
    public void aShortCachedPriceQuoteStillReadsTheDeniedSourceHeading() throws Exception {
        final String text = "Muster Pflege gGmbH – Nicht angebotene Leistungen:\n- Tagespflege, 12 EUR pro Tag";
        readCache("AAAAAAoff010", text, shortPriceAnswer(text));
        assertEquals(List.of(), statements("AAAAAAoff010"));
        assertEquals(0L, count("SELECT count(*) FROM kg_entity e JOIN kg_vocab v ON v.term_id=e.type WHERE v.name='service'"));
    }
}
