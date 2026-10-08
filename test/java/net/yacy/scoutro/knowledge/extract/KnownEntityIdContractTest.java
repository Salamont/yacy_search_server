package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;


/**
 * The id contract between the prompt, the schema and the unchanged validator (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3):
 * a known entity (k1, k2, ... in the DATA block) is only referred to, never put into "entities" again; a new entity
 * gets a new id e1, e2, ..., once per answer. Answers in the former pattern still lose their duplicates
 * (entity_duplicate_id, the validator is not loosened); answers that follow the contract keep every entity, claim and value.
 */
public class KnownEntityIdContractTest {

    static final String TEXT = "Die Muster Pflege gGmbH betreibt das Haus Lindenhof. Das Haus Lindenhof bietet Kurzzeitpflege an,"
            + " Kurzzeitpflege ab 89,90 € pro Tag. Partner der Muster Pflege gGmbH ist die Beispiel Software GmbH. Der Pflegeverbund Nord ist Träger der Muster Pflege gGmbH.";
    static final String OPERATES = "Die Muster Pflege gGmbH betreibt das Haus Lindenhof";
    static final String OFFERS = "Das Haus Lindenhof bietet Kurzzeitpflege an";
    static final String PARTNER = "Partner der Muster Pflege gGmbH ist die Beispiel Software GmbH";
    static final String CARRIER = "Der Pflegeverbund Nord ist Träger der Muster Pflege gGmbH";

    private static LlmExtractor.Chunk chunk() {
        return LlmExtractor.chunks(TEXT, 12_000).get(0);
    }

    /** k1 the organisation, k2 its facility, k3 the carrier: the known entities of tiers 1 and 2. */
    private static List<LlmExtractor.Known> known() {
        final List<LlmExtractor.Known> out = new ArrayList<>();
        final String[][] k = {{Vocabulary.ORGANIZATION, "Muster Pflege gGmbH"}, {Vocabulary.FACILITY, "Haus Lindenhof"},
            {Vocabulary.ORGANIZATION, "Pflegeverbund Nord"}};
        for (int i = 0; i < k.length; i++) {
            final Mention m = new Mention("jsonld:" + i, k[i][0], 1);
            m.name = k[i][1];
            out.add(new LlmExtractor.Known("k" + (i + 1), m));
        }
        return out;
    }

    private static String entity(final String id, final String type, final String name, final String quote) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"name\":\"" + name + "\",\"quote\":\"" + quote + "\"}";
    }

    private static String claim(final String s, final String p, final String o, final String quote) {
        return "{\"subject\":\"" + s + "\",\"predicate\":\"" + p + "\",\"object\":\"" + o + "\",\"quote\":\"" + quote + "\"}";
    }

    private static String value(final String s, final String p, final String quote) {
        return "{\"subject\":\"" + s + "\",\"predicate\":\"" + p + "\",\"quote\":\"" + quote + "\"}";
    }

    private static LlmExtractor.Result validate(final String[] entities, final String[] claims, final String[] values) {
        final String answer = "{\"entities\":[" + String.join(",", entities) + "],\"claims\":[" + String.join(",", claims)
                + "],\"values\":[" + String.join(",", values) + "]}";
        return LlmExtractor.validate(answer, chunk(), known(), Set.of());
    }

    // the contract as the model reads it: rule 7 of the prompt and the schema's descriptions

    @Test
    public void thePromptAndTheSchemaStateTheContract() throws Exception {
        final String p = LlmExtractor.SYSTEM_PROMPT;
        assertTrue(p.contains("Never put a known entity into \"entities\" again"));
        assertTrue(p.contains("use its k id only as \"subject\" or \"object\" of a claim or as \"subject\" of a value"));
        assertTrue(p.contains("Every new entity in \"entities\" gets the next new id e1, e2, e3, ... (every id once per answer, never a k id)"));
        assertFalse("the former, ambiguous wording is gone", p.contains("instead of repeating them"));
        final JSONObject props = LlmExtractor.SCHEMA.getJSONObject("properties");
        assertTrue(props.getJSONObject("entities").getString("description").contains("never a known entity (k1, k2, ...)"));
        final JSONObject entity = props.getJSONObject("entities").getJSONObject("items").getJSONObject("properties");
        assertTrue(entity.getJSONObject("id").getString("description").contains("never a known id (k1, k2, ...)"));
        final JSONObject claim = props.getJSONObject("claims").getJSONObject("items").getJSONObject("properties");
        final JSONObject value = props.getJSONObject("values").getJSONObject("items").getJSONObject("properties");
        for (final JSONObject ref : List.of(claim.getJSONObject("subject"), claim.getJSONObject("object"), value.getJSONObject("subject"))) {
            assertTrue(ref.getString("description").contains("known entity (k1, k2, ...) or of a new entity in \"entities\" (e1, e2, ...)"));
        }
        // only annotations were added: the constraints are the former ones, and every keyword is JSON Schema
        assertEquals(32, entity.getJSONObject("id").getInt("maxLength"));
        assertFalse("no new id constraint", entity.getJSONObject("id").has("pattern"));
        assertKeywords(LlmExtractor.SCHEMA);
    }

    private static final Set<String> KEYWORDS = Set.of("type", "additionalProperties", "required", "properties", "items", "maxItems",
            "maxLength", "enum", "description");

    private static void assertKeywords(final JSONObject schema) throws Exception {
        for (final Iterator<String> it = schema.keys(); it.hasNext();) {
            final String k = it.next();
            assertTrue("a JSON Schema keyword: " + k, KEYWORDS.contains(k));
            final Object v = schema.get(k);
            if ("properties".equals(k)) {
                final JSONObject p = (JSONObject) v;
                for (final Iterator<String> names = p.keys(); names.hasNext();) assertKeywords(p.getJSONObject(names.next()));
            } else if ("items".equals(k)) {
                assertKeywords((JSONObject) v);
            } else if ("description".equals(k)) {
                assertTrue(v instanceof String);
            }
        }
    }

    // the former pattern: the unchanged validator still drops a duplicate id

    @Test
    public void aKnownEntityDeclaredAgainIsStillDropped() throws Exception {
        final LlmExtractor.Result r = validate(new String[] {entity("k1", "organization", "Muster Pflege gGmbH", OPERATES),
            entity("k2", "facility", "Haus Lindenhof", OPERATES)}, new String[] {claim("k1", "operates", "k2", OPERATES)}, new String[0]);
        assertNull(r.refused);
        assertEquals(Map.of("entity_duplicate_id", 2), r.droppedInvalidByReason);
        assertEquals(0, r.entities);
        assertEquals("the claim on the known ids still resolves", 1, r.claims);
    }

    @Test
    public void aNewEntityUnderAKnownIdIsLost() throws Exception {
        // the new service numbered on in the k scheme: dropped, and its claim then points at the known facility k2
        final LlmExtractor.Result r = validate(new String[] {entity("k2", "service", "Kurzzeitpflege", OFFERS)},
                new String[] {claim("k2", "offers", "k2", OFFERS)}, new String[0]);
        assertEquals(Map.of("entity_duplicate_id", 1, "claim_self_reference", 1), r.droppedInvalidByReason);
        assertEquals(0, r.entities);
        assertEquals(0, r.claims);
    }

    @Test
    public void aNewIdTwiceKeepsOnlyTheFirst() throws Exception {
        final LlmExtractor.Result r = validate(new String[] {entity("e1", "service", "Kurzzeitpflege", OFFERS),
            entity("e1", "organization", "Beispiel Software GmbH", PARTNER)}, new String[0], new String[0]);
        assertEquals(Map.of("entity_duplicate_id", 1), r.droppedInvalidByReason);
        assertEquals(1, r.entities);
    }

    // the contract: known entities referred to, new entities with new ids; nothing is dropped, claims and values stay

    @Test
    public void anAnswerOfTheContractKeepsEverything() throws Exception {
        final LlmExtractor.Result r = validate(new String[] {
            entity("e1", "service", "Kurzzeitpflege", OFFERS),
            entity("e2", "organization", "Beispiel Software GmbH", PARTNER)},
                new String[] {
                    claim("k1", "operates", "k2", OPERATES), // known -> known (several known entities)
                    claim("k2", "offers", "e1", OFFERS), // known -> new
                    claim("e2", "partner_of", "k1", PARTNER), // new -> known
                    claim("k3", "carrier_of", "k1", CARRIER)},
                new String[] {
                    value("e1", "price", "Kurzzeitpflege ab 89,90 € pro Tag"), // on a new entity
                    value("e1", "category", OFFERS),
                    value("k1", "industry", OPERATES)}); // on a known entity
        assertNull(r.refused);
        assertEquals("no invalid item: " + r.droppedInvalidByReason, 0, r.droppedInvalid);
        assertEquals(2, r.entities);
        assertEquals(4, r.claims);
        assertEquals(3, r.values);
        final JSONArray ids = r.accepted.getJSONArray("entities");
        assertEquals("e1", ids.getJSONObject(0).getString("id"));
        assertEquals("e2", ids.getJSONObject(1).getString("id"));
    }

    @Test
    public void selfReferenceAndTypeRulesAreUnchanged() throws Exception {
        final LlmExtractor.Result r = validate(new String[] {entity("e1", "service", "Kurzzeitpflege", OFFERS)},
                new String[] {
                    claim("k1", "operates", "k1", OPERATES), // self-reference on a known id
                    claim("e1", "operates", "k2", OFFERS), // a service operates nothing
                    claim("k1", "operates", "e1", OPERATES)}, // nor is it operated
                new String[] {value("k1", "price", "Kurzzeitpflege ab 89,90 € pro Tag")}); // no price of an organisation
        assertEquals(Map.of("claim_self_reference", 1, "claim_subject_type_mismatch", 1, "claim_object_type_mismatch", 1,
                "value_subject_type_mismatch", 1), r.droppedInvalidByReason);
        assertEquals(1, r.entities);
        assertEquals(0, r.claims);
        assertEquals(0, r.values);
    }

    // a deterministic stand-in for a model that follows the prompt: the known entities of the DATA block are referred to,
    // the new ones numbered e1, e2, ...; over many pages with known entities no duplicate id occurs

    @Test
    public void aModelFollowingTheContractProducesNoDuplicateId() throws Exception {
        int duplicates = 0, claims = 0, values = 0;
        for (int known = 0; known <= 3; known++) {
            final List<LlmExtractor.Known> k = known().subList(0, known);
            final List<String> entities = new ArrayList<>(), cl = new ArrayList<>(), vs = new ArrayList<>();
            // the page's names: a known one is referred to by its k id, a new one gets the next e id
            final String[][] page = {{Vocabulary.ORGANIZATION, "Muster Pflege gGmbH", OPERATES}, {Vocabulary.FACILITY, "Haus Lindenhof", OPERATES},
                {Vocabulary.ORGANIZATION, "Pflegeverbund Nord", CARRIER}, {Vocabulary.SERVICE, "Kurzzeitpflege", OFFERS}};
            final String[] ids = new String[page.length];
            int next = 1;
            for (int i = 0; i < page.length; i++) {
                String id = null;
                for (final LlmExtractor.Known kn : k) if (kn.mention.name.equals(page[i][1])) id = kn.id;
                if (id == null) {
                    id = "e" + next++;
                    entities.add(entity(id, page[i][0], page[i][1], page[i][2]));
                }
                ids[i] = id;
            }
            cl.add(claim(ids[0], "operates", ids[1], OPERATES));
            cl.add(claim(ids[1], "offers", ids[3], OFFERS));
            cl.add(claim(ids[2], "carrier_of", ids[0], CARRIER));
            vs.add(value(ids[3], "price", "Kurzzeitpflege ab 89,90 € pro Tag"));
            final String answer = "{\"entities\":[" + String.join(",", entities) + "],\"claims\":[" + String.join(",", cl)
                    + "],\"values\":[" + String.join(",", vs) + "]}";
            final LlmExtractor.Result r = LlmExtractor.validate(answer, chunk(), k, Set.of());
            duplicates += r.droppedInvalidByReason.getOrDefault("entity_duplicate_id", 0);
            assertEquals("known " + known + ": " + r.droppedInvalidByReason, 0, r.droppedInvalid);
            assertEquals(4 - known, r.entities);
            claims += r.claims;
            values += r.values;
        }
        assertEquals(0, duplicates);
        assertEquals("every claim resolves, with 0 to 3 known entities", 12, claims);
        assertEquals(4, values);
    }
}
