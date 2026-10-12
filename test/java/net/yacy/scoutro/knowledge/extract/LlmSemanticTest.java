package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;
import org.junit.Test;

/**
 * What the LLM tier is told and what it counts about the page's own entities (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.7):
 * the operator is offered once and first, a relation of an entity to itself is refused and counted (it was published
 * as nothing before, unseen), a claim about a dropped entity counts as such, an organisation with a legal form joins the
 * declared operator, and the diagnostic samples carry no person names or contact data. The validator's rules are not
 * loosened: every case here was dropped before too, or is dropped now. Example data only.
 */
public class LlmSemanticTest {

    static final String TEXT = LlmExtractorTest.TEXT;

    private static Mention mention(final String ref, final String type, final int tier, final String name) {
        final Mention m = new Mention(ref, type, tier);
        m.name = name;
        return m;
    }

    private static LlmExtractor.Result validate(final String answer, final List<LlmExtractor.Known> known) {
        return LlmExtractor.validate(answer, LlmExtractor.chunks(TEXT, 12_000).get(0), known, Set.of());
    }

    @Test
    public void theOperatorIsOfferedOnceAndBeforeALongListOfServices() {
        final Extraction tiers12 = new Extraction(500);
        for (int i = 0; i < 45; i++) {
            tiers12.add(mention("jsonld:s" + i, Vocabulary.SERVICE, 1, "Leistung " + i));
        }
        tiers12.add(mention("jsonld:org", Vocabulary.ORGANIZATION, 1, "Muster Pflege gGmbH"));
        final Mention publisher = mention("metadata:publisher", Vocabulary.ORGANIZATION, 1, "Muster Pflege gGmbH");
        publisher.siteOperator = true;
        tiers12.add(publisher);
        tiers12.add(mention("rule:org:muster pflege ggmbh", Vocabulary.ORGANIZATION, 2, "Muster  Pflege gGmbH"));
        tiers12.add(mention("rule:org:beispiel stiftung", Vocabulary.ORGANIZATION, 2, "Beispiel Stiftung"));
        final List<LlmExtractor.Known> known = LlmExtractor.known(tiers12);
        assertEquals(LlmExtractor.MAX_ITEMS, known.size());
        assertEquals("the declared operator first", "metadata:publisher", known.get(0).mention.ref);
        assertEquals("k1", known.get(0).id);
        assertEquals("one entry per type and name", 1L, known.stream()
                .filter(k -> "muster pflege ggmbh".equals(net.yacy.scoutro.knowledge.resolve.Normalizers.key(k.mention.name))).count());
        assertEquals("then the other organisations", "Beispiel Stiftung", known.get(1).mention.name);
        assertEquals(Vocabulary.SERVICE, known.get(2).mention.type);
    }

    @Test
    public void aNewIdForAKnownEntityRelatedToItselfIsRefusedAndCounted() throws Exception {
        final String answer = "{\"entities\":[{\"id\":\"e1\",\"type\":\"organization\",\"name\":\"Muster Pflege gGmbH\","
                + "\"quote\":\"Die Muster Pflege gGmbH betreibt\"}],\"claims\":[{\"subject\":\"e1\",\"predicate\":\"part_of\","
                + "\"object\":\"k1\",\"quote\":\"Die Muster Pflege gGmbH betreibt das Haus Lindenhof\"}],\"values\":[]}";
        final LlmExtractor.Result r = validate(answer, LlmExtractorTest.known());
        assertEquals(0, r.claims);
        assertEquals(Map.of("claim_self_reference", 1), r.droppedInvalidByReason);
        assertEquals("claim_self_reference", r.samples.get(0).getString("reason"));
        // nothing of it reaches the graph either way
        final Extraction out = new Extraction(50);
        LlmExtractor.apply(r.accepted, LlmExtractorTest.known(), out);
        assertTrue(out.claims().stream().noneMatch(c -> Vocabulary.PART_OF.equals(c.predicate)));
    }

    @Test
    public void twoKnownEntitiesOfOneNameAreNoRelation() {
        final Mention facility = mention("jsonld:0", Vocabulary.FACILITY, 1, "Muster Pflege gGmbH");
        final Mention org = mention("metadata:publisher", Vocabulary.ORGANIZATION, 1, "Muster Pflege gGmbH");
        final List<LlmExtractor.Known> known = List.of(new LlmExtractor.Known("k1", org), new LlmExtractor.Known("k2", facility));
        final String answer = "{\"entities\":[],\"claims\":[{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"k2\","
                + "\"quote\":\"Die Muster Pflege gGmbH betreibt\"}],\"values\":[]}";
        final LlmExtractor.Result r = validate(answer, known);
        assertEquals("a business typed facility by its structured data does not operate itself", 0, r.claims);
        assertEquals(Map.of("claim_self_reference", 1), r.droppedInvalidByReason);
    }

    @Test
    public void aClaimAboutADroppedEntityCountsAsSuchNotAsAnUnknownId() throws Exception {
        final String answer = "{\"entities\":[{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Am Meer\",\"quote\":\"das Haus Am Meer\"}],"
                + "\"claims\":[{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\"Die Muster Pflege gGmbH betreibt\"},"
                + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e7\",\"quote\":\"Die Muster Pflege gGmbH betreibt\"}],\"values\":[]}";
        final LlmExtractor.Result r = validate(answer, LlmExtractorTest.known());
        assertEquals(1, r.droppedUngrounded);
        assertEquals(Map.of("claim_entity_dropped", 1, "claim_unresolved_object", 1), r.droppedInvalidByReason);
        final JSONObject dropped = r.samples.stream().filter(s -> "claim_entity_dropped".equals(s.optString("reason"))).findFirst().get();
        assertEquals("dropped_entity:ungrounded", dropped.getString("object"));
        final JSONObject unknown = r.samples.stream().filter(s -> "claim_unresolved_object".equals(s.optString("reason"))).findFirst().get();
        assertEquals("unknown_id", unknown.getString("object"));
        assertEquals("known", unknown.getString("subject"));
    }

    @Test
    public void anOrganisationWithALegalFormJoinsTheDeclaredOperator() {
        final String answer = "{\"entities\":[{\"id\":\"e1\",\"type\":\"organization\",\"name\":\"Erfundene Holding AG\","
                + "\"quote\":\"Erfundene Holding AG\"}],\"claims\":[],\"values\":[]}";
        final String text = "Die Muster Pflege gGmbH gehört zur Erfundene Holding AG.";
        final LlmExtractor.Result r = LlmExtractor.validate(answer, LlmExtractor.chunks(text, 12_000).get(0), LlmExtractorTest.known(),
                Set.of());
        assertEquals(1, r.entities);
        final Extraction out = new Extraction(50);
        LlmExtractor.apply(r.accepted, LlmExtractorTest.known(), out);
        final Mention m = out.mention("llm:organization:erfundene holding ag");
        assertNotNull(m);
        assertEquals("the legal name: the identity resolution joins it to the declared operator of that name", "Erfundene Holding AG",
                m.legalName);
    }

    @Test
    public void samplesAreBoundedAndCarryNoPersonsOrContactData() throws Exception {
        final StringBuilder entities = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            entities.append(i == 0 ? "" : ",").append("{\"id\":\"e").append(i + 1).append("\",\"type\":\"organization\",\"name\":\"Herr Max Muster\","
                    + "\"quote\":\"Kontakt: Herr Max Muster, max.muster@example.org, Tel. 030 1234 5678\"}");
        }
        final LlmExtractor.Result r = validate("{\"entities\":[" + entities + "],\"claims\":[],\"values\":[]}", LlmExtractorTest.known());
        assertEquals(12, r.droppedInvalid);
        assertEquals("bounded per answer", LlmExtractor.SAMPLES, r.samples.size());
        for (final JSONObject s : r.samples) {
            final String excerpt = s.getString("excerpt");
            assertFalse(excerpt, excerpt.contains("max.muster@example.org"));
            assertFalse(excerpt, excerpt.contains("1234 5678"));
            assertFalse(excerpt, excerpt.contains("Max Muster"));
            assertTrue(excerpt.length() <= LlmExtractor.SAMPLE_CHARS);
        }
        assertEquals("never longer than the bound", LlmExtractor.SAMPLE_CHARS,
                LlmExtractor.redact("x".repeat(LlmExtractor.SAMPLE_CHARS * 3)).length());
    }
}
