package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * The reasons of {@code droppedInvalid} (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3):
 * every dropped item counts once, under the first rule it breaks in the
 * validator's order; the reasons add up to {@code droppedInvalid}; refused
 * answers count no reason. The rules themselves are those of
 * {@link LlmExtractorTest}: these tests only confirm what is dropped.
 */
public class LlmExtractorInvalidReasonTest {

    /** Valid items about {@link LlmExtractorTest#TEXT}; k1 is the known "Muster Pflege gGmbH". */
    static final String E1 = "{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Lindenhof\",\"quote\":\"betreibt das Haus Lindenhof in Berlin\"}";
    static final String E2 = "{\"id\":\"e2\",\"type\":\"service\",\"name\":\"Tagespflege\",\"quote\":\"Das Haus Lindenhof bietet Tagespflege an\"}";
    static final String C1 = "{\"subject\":\"e1\",\"predicate\":\"offers\",\"object\":\"e2\",\"quote\":\"Das Haus Lindenhof bietet Tagespflege an\"}";
    static final String V1 = "{\"subject\":\"e2\",\"predicate\":\"category\",\"quote\":\"bietet Tagespflege an\"}";
    static final String Q = "Das Haus Lindenhof bietet Tagespflege an";
    static final String LONG201 = repeat('x', 201);
    static final String LONG301 = repeat('x', 301);

    static String repeat(final char c, final int n) {
        final char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    static String answer(final String entities, final String claims, final String values) {
        return "{\"entities\":[" + entities + "],\"claims\":[" + claims + "],\"values\":[" + values + "]}";
    }

    static LlmExtractor.Result validate(final String answer) {
        return LlmExtractor.validate(answer, LlmExtractorTest.chunk(), LlmExtractorTest.known(), Set.of());
    }

    static int sum(final LlmExtractor.Result r) {
        return r.droppedInvalidByReason.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Exactly one item dropped as invalid, under {@code reason}; the valid items stay. */
    static void assertOnly(final String reason, final LlmExtractor.Result r) {
        assertNull(reason, r.refused);
        assertTrue("a known code: " + reason, LlmExtractor.INVALID_REASONS.contains(reason));
        assertEquals(reason + ": " + r.droppedInvalidByReason, Map.of(reason, 1), r.droppedInvalidByReason);
        assertEquals(reason, 1, r.droppedInvalid);
        assertEquals(reason, r.droppedInvalid, sum(r));
        assertEquals(reason, 0, r.droppedUngrounded);
    }

    /** One case per reason: the bad item next to the valid E1, E2, C1 and V1. */
    static Map<String, String> cases() {
        final Map<String, String> m = new LinkedHashMap<>();
        final String e = E1 + "," + E2 + ",";
        // entities
        m.put("entity_not_object", answer(e + "\"Haus Am See\"", C1, V1));
        m.put("entity_extra_field", answer(e + "{\"id\":\"e3\",\"type\":\"facility\",\"name\":\"Haus Am See\",\"quote\":\"das Haus Am See\","
                + "\"city\":\"Berlin\"}", C1, V1));
        m.put("entity_missing_id", answer(e + "{\"type\":\"facility\",\"name\":\"Haus Am See\",\"quote\":\"das Haus Am See\"}", C1, V1));
        m.put("entity_malformed_id", answer(e + "{\"id\":3,\"type\":\"facility\",\"name\":\"Haus Am See\",\"quote\":\"das Haus Am See\"}", C1, V1));
        m.put("entity_invalid_id", answer(e + "{\"id\":\"Haus Am See\",\"type\":\"facility\",\"name\":\"Haus Am See\",\"quote\":\"das Haus Am See\"}",
                C1, V1));
        m.put("entity_duplicate_id", answer(e + "{\"id\":\"k1\",\"type\":\"facility\",\"name\":\"Haus Am See\",\"quote\":\"das Haus Am See\"}", C1, V1));
        m.put("entity_missing_type", answer(e + "{\"id\":\"e3\",\"name\":\"Haus Am See\",\"quote\":\"das Haus Am See\"}", C1, V1));
        m.put("entity_malformed_type", answer(e + "{\"id\":\"e3\",\"type\":[\"facility\"],\"name\":\"Haus Am See\",\"quote\":\"das Haus Am See\"}",
                C1, V1));
        m.put("entity_unknown_type", answer(e + "{\"id\":\"e3\",\"type\":\"company\",\"name\":\"Haus Am See\",\"quote\":\"das Haus Am See\"}", C1, V1));
        m.put("entity_missing_name", answer(e + "{\"id\":\"e3\",\"type\":\"facility\",\"name\":\"   \",\"quote\":\"das Haus Am See\"}", C1, V1));
        m.put("entity_malformed_name", answer(e + "{\"id\":\"e3\",\"type\":\"facility\",\"name\":{\"de\":\"Haus Am See\"},\"quote\":\"das Haus Am See\"}",
                C1, V1));
        m.put("entity_name_too_short", answer(e + "{\"id\":\"e3\",\"type\":\"facility\",\"name\":\"H\",\"quote\":\"das Haus Am See\"}", C1, V1));
        m.put("entity_person_or_contact", answer(e + "{\"id\":\"e3\",\"type\":\"organization\",\"name\":\"Herr Max Muster\",\"quote\":\"x\"}", C1, V1));
        m.put("entity_missing_quote", answer(e + "{\"id\":\"e3\",\"type\":\"facility\",\"name\":\"Haus Am See\"}", C1, V1));
        m.put("entity_malformed_quote", answer(e + "{\"id\":\"e3\",\"type\":\"facility\",\"name\":\"Haus Am See\",\"quote\":[\"das Haus Am See\"]}",
                C1, V1));
        m.put("entity_quote_too_long", answer(e + "{\"id\":\"e3\",\"type\":\"facility\",\"name\":\"Haus Am See\",\"quote\":\"" + LONG201 + "\"}",
                C1, V1));
        // claims
        final String c = C1 + ",";
        m.put("claim_not_object", answer(E1 + "," + E2, c + "[\"e1\",\"offers\",\"e2\"]", V1));
        m.put("claim_extra_field", answer(E1 + "," + E2, c + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\"" + Q
                + "\",\"confidence\":0.9}", V1));
        m.put("claim_missing_subject", answer(E1 + "," + E2, c + "{\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\"" + Q + "\"}", V1));
        m.put("claim_malformed_subject", answer(E1 + "," + E2, c + "{\"subject\":1,\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\"" + Q
                + "\"}", V1));
        m.put("claim_missing_object", answer(E1 + "," + E2, c + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":null,\"quote\":\"" + Q
                + "\"}", V1));
        m.put("claim_malformed_object", answer(E1 + "," + E2, c + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":[\"e1\"],\"quote\":\""
                + Q + "\"}", V1));
        m.put("claim_self_reference", answer(E1 + "," + E2, c + "{\"subject\":\"e1\",\"predicate\":\"part_of\",\"object\":\"e1\",\"quote\":\"" + Q
                + "\"}", V1));
        m.put("claim_missing_predicate", answer(E1 + "," + E2, c + "{\"subject\":\"k1\",\"object\":\"e1\",\"quote\":\"" + Q + "\"}", V1));
        m.put("claim_malformed_predicate", answer(E1 + "," + E2, c + "{\"subject\":\"k1\",\"predicate\":true,\"object\":\"e1\",\"quote\":\"" + Q
                + "\"}", V1));
        m.put("claim_unknown_predicate", answer(E1 + "," + E2, c + "{\"subject\":\"k1\",\"predicate\":\"runs\",\"object\":\"e1\",\"quote\":\"" + Q
                + "\"}", V1));
        m.put("claim_missing_quote", answer(E1 + "," + E2, c + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\"}", V1));
        m.put("claim_malformed_quote", answer(E1 + "," + E2, c + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":7}", V1));
        m.put("claim_quote_too_long", answer(E1 + "," + E2, c + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\""
                + LONG201 + "\"}", V1));
        m.put("claim_invalid_hedged", answer(E1 + "," + E2, c + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\",\"hedged\":\"false\","
                + "\"quote\":\"" + Q + "\"}", V1));
        m.put("claim_unresolved_subject", answer(E1 + "," + E2, c + "{\"subject\":\"k2\",\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\""
                + Q + "\"}", V1));
        m.put("claim_unresolved_object", answer(E1 + "," + E2, c + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e9\",\"quote\":\""
                + Q + "\"}", V1));
        m.put("claim_subject_type_mismatch", answer(E1 + "," + E2, c + "{\"subject\":\"e2\",\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\""
                + Q + "\"}", V1));
        m.put("claim_object_type_mismatch", answer(E1 + "," + E2, c + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e2\",\"quote\":\""
                + Q + "\"}", V1));
        // values
        final String v = V1 + ",";
        m.put("value_not_object", answer(E1 + "," + E2, C1, v + "42"));
        m.put("value_extra_field", answer(E1 + "," + E2, C1, v + "{\"subject\":\"e2\",\"predicate\":\"category\",\"quote\":\"bietet Tagespflege an\","
                + "\"value\":\"care/tagespflege\"}"));
        m.put("value_missing_subject", answer(E1 + "," + E2, C1, v + "{\"predicate\":\"category\",\"quote\":\"bietet Tagespflege an\"}"));
        m.put("value_malformed_subject", answer(E1 + "," + E2, C1, v + "{\"subject\":2,\"predicate\":\"category\",\"quote\":\"bietet Tagespflege an\"}"));
        m.put("value_unresolved_subject", answer(E1 + "," + E2, C1, v + "{\"subject\":\"Tagespflege\",\"predicate\":\"category\","
                + "\"quote\":\"bietet Tagespflege an\"}"));
        m.put("value_missing_predicate", answer(E1 + "," + E2, C1, v + "{\"subject\":\"e2\",\"quote\":\"bietet Tagespflege an\"}"));
        m.put("value_malformed_predicate", answer(E1 + "," + E2, C1, v + "{\"subject\":\"e2\",\"predicate\":[\"category\"],"
                + "\"quote\":\"bietet Tagespflege an\"}"));
        m.put("value_unknown_predicate", answer(E1 + "," + E2, C1, v + "{\"subject\":\"e2\",\"predicate\":\"kategorie\",\"quote\":\"bietet Tagespflege an\"}"));
        m.put("value_missing_quote", answer(E1 + "," + E2, C1, v + "{\"subject\":\"e2\",\"predicate\":\"category\"}"));
        m.put("value_malformed_quote", answer(E1 + "," + E2, C1, v + "{\"subject\":\"e2\",\"predicate\":\"category\",\"quote\":false}"));
        m.put("value_quote_too_long", answer(E1 + "," + E2, C1, v + "{\"subject\":\"e2\",\"predicate\":\"category\",\"quote\":\"" + LONG201 + "\"}"));
        m.put("value_subject_type_mismatch", answer(E1 + "," + E2, C1, v + "{\"subject\":\"e2\",\"predicate\":\"salary\",\"quote\":\"" + Q + "\"}"));
        return m;
    }

    @Test
    public void theCodesAreStableAndDistinct() {
        assertEquals(new HashSet<>(LlmExtractor.INVALID_REASONS).size(), LlmExtractor.INVALID_REASONS.size());
        for (final String reason : LlmExtractor.INVALID_REASONS) {
            assertTrue(reason, reason.matches("^(entity|claim|value)_[a-z]+(_[a-z]+)*$"));
        }
        assertEquals("one case per code", LlmExtractor.INVALID_REASONS, List.copyOf(cases().keySet()));
    }

    @Test
    public void everyRuleCountsUnderItsOwnReasonAndTheValidItemsStay() throws Exception {
        for (final Map.Entry<String, String> c : cases().entrySet()) {
            final String reason = c.getKey();
            final LlmExtractor.Result r = validate(c.getValue());
            assertOnly(reason, r);
            // the valid items of the same answer are kept
            assertEquals(reason, 2, r.entities);
            assertEquals(reason, 1, r.claims);
            assertEquals(reason, 1, r.values);
            assertEquals(reason, 2, r.accepted.getJSONArray("entities").length());
        }
    }

    @Test
    public void validItemsCountNoReason() {
        final LlmExtractor.Result r = validate(answer(E1 + "," + E2, C1, V1));
        assertNull(r.refused);
        assertEquals(2, r.entities);
        assertEquals(1, r.claims);
        assertEquals("the valid value counts as accepted", 1, r.values);
        assertEquals(0, r.droppedInvalid);
        assertTrue(r.droppedInvalidByReason.isEmpty());
        assertEquals(0, validate(answer(E1, "", "")).droppedInvalid);
        assertEquals(0, validate(answer(E1 + "," + E2, C1, "")).droppedInvalid);
    }

    @Test
    public void theLongStringsAreNamedByWhatTheyBreak() {
        // over the 300 characters of a string: the field is malformed; a quote is too long either way
        assertOnly("entity_malformed_id", validate(answer(E1 + "," + E2 + ",{\"id\":\"" + LONG301 + "\",\"type\":\"facility\","
                + "\"name\":\"Haus Am See\",\"quote\":\"das Haus Am See\"}", C1, V1)));
        assertOnly("entity_malformed_name", validate(answer(E1 + "," + E2 + ",{\"id\":\"e3\",\"type\":\"facility\",\"name\":\"" + LONG301
                + "\",\"quote\":\"das Haus Am See\"}", C1, V1)));
        assertOnly("entity_quote_too_long", validate(answer(E1 + "," + E2 + ",{\"id\":\"e3\",\"type\":\"facility\",\"name\":\"Haus Am See\","
                + "\"quote\":\"" + LONG301 + "\"}", C1, V1)));
        assertOnly("claim_quote_too_long", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\","
                + "\"quote\":\"" + LONG301 + "\"}", V1)));
        assertOnly("entity_missing_id", validate(answer(E1 + "," + E2 + ",{\"id\":null,\"type\":\"facility\",\"name\":\"Haus Am See\","
                + "\"quote\":\"das Haus Am See\"}", C1, V1)));
        // an empty id is a string that does not match the format
        assertOnly("entity_invalid_id", validate(answer(E1 + "," + E2 + ",{\"id\":\"\",\"type\":\"facility\",\"name\":\"Haus Am See\","
                + "\"quote\":\"das Haus Am See\"}", C1, V1)));
    }

    @Test
    public void severalBrokenRulesCountOnceUnderTheFirstInTheValidatorsOrder() {
        // structure before fields: an extra field and an unknown type
        assertOnly("entity_extra_field", validate(answer(E1 + "," + E2 + ",{\"id\":\"e3\",\"type\":\"company\",\"name\":\"Haus Am See\","
                + "\"quote\":\"das Haus Am See\",\"email\":\"a@b.de\"}", C1, V1)));
        // id before type, name and quote
        assertOnly("entity_invalid_id", validate(answer(E1 + "," + E2 + ",{\"id\":\"e 3\",\"type\":\"person\",\"name\":\"Herr Max Muster\"}", C1, V1)));
        // type before name and quote
        assertOnly("entity_unknown_type", validate(answer(E1 + "," + E2 + ",{\"id\":\"e3\",\"type\":\"person\",\"name\":\"Herr Max Muster\"}", C1, V1)));
        // name before quote
        assertOnly("entity_person_or_contact", validate(answer(E1 + "," + E2 + ",{\"id\":\"e3\",\"type\":\"organization\",\"name\":\"Herr Max Muster\","
                + "\"quote\":\"" + LONG201 + "\"}", C1, V1)));
        // a claim: subject = object before the predicate
        assertOnly("claim_self_reference", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"e9\",\"predicate\":\"employs\",\"object\":\"e9\","
                + "\"quote\":\"" + Q + "\"}", V1)));
        // the fields of a claim before its references
        assertOnly("claim_invalid_hedged", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"e9\",\"predicate\":\"operates\",\"object\":\"e1\","
                + "\"hedged\":\"yes\",\"quote\":\"" + Q + "\"}", V1)));
        assertOnly("claim_unknown_predicate", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"e9\",\"predicate\":\"employs\",\"object\":\"e8\","
                + "\"quote\":\"" + LONG201 + "\"}", V1)));
        // the subject before the object, the references before the type rules
        assertOnly("claim_unresolved_subject", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"e9\",\"predicate\":\"operates\",\"object\":\"e8\","
                + "\"quote\":\"" + Q + "\"}", V1)));
        assertOnly("claim_unresolved_object", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"e2\",\"predicate\":\"operates\",\"object\":\"e8\","
                + "\"quote\":\"" + Q + "\"}", V1)));
        // both types wrong (a service operates an organisation): the subject's
        assertOnly("claim_subject_type_mismatch", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"e2\",\"predicate\":\"operates\","
                + "\"object\":\"k1\",\"quote\":\"" + Q + "\"}", V1)));
        // a value: the subject before the predicate, the predicate before the quote, the quote before the type rule
        assertOnly("value_unresolved_subject", validate(answer(E1 + "," + E2, C1, V1 + ",{\"subject\":\"e9\",\"predicate\":\"kategorie\"}")));
        assertOnly("value_unknown_predicate", validate(answer(E1 + "," + E2, C1, V1 + ",{\"subject\":\"e2\",\"predicate\":\"kategorie\","
                + "\"quote\":\"" + LONG201 + "\"}")));
        assertOnly("value_quote_too_long", validate(answer(E1 + "," + E2, C1, V1 + ",{\"subject\":\"e2\",\"predicate\":\"salary\","
                + "\"quote\":\"" + LONG201 + "\"}")));
    }

    @Test
    public void aPartlyValidAnswerKeepsTheValidItemsAndCountsEachInvalidOne() throws Exception {
        final String a = answer(E1 + "," + E2
                + ",{\"id\":\"e3\",\"type\":\"Facility\",\"name\":\"Haus Am See\",\"quote\":\"das Haus Am See\"}"
                + ",{\"id\":\"e4\",\"type\":\"facility\",\"name\":\"Haus Am See\",\"quote\":\"das Haus Am See\",\"note\":\"geplant\"}"
                + ",{\"id\":\"e5\",\"type\":\"organization\",\"name\":\"Erfundene Holding AG\",\"quote\":\"Die Erfundene Holding AG\"}",
                C1 + ",{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\"Die Muster Pflege gGmbH betreibt das Haus Lindenhof\"}"
                        + ",{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e3\",\"quote\":\"" + Q + "\"}"
                        + ",{\"subject\":\"k1\",\"predicate\":\"part_of\",\"object\":\"e5\",\"quote\":\"" + Q + "\"}",
                V1 + ",{\"subject\":\"e2\",\"predicate\":\"salary\",\"quote\":\"" + Q + "\"}");
        final LlmExtractor.Result r = validate(a);
        assertNull(r.refused);
        assertEquals(2, r.entities);
        assertEquals(2, r.claims);
        assertEquals(1, r.values);
        assertEquals("the invented holding", 1, r.droppedUngrounded);
        assertEquals(5, r.droppedInvalid);
        assertEquals(Map.of("entity_unknown_type", 1, "entity_extra_field", 1, "claim_unresolved_object", 2, "value_subject_type_mismatch", 1),
                r.droppedInvalidByReason);
        assertEquals(r.droppedInvalid, sum(r));
        assertEquals(List.of("e1", "e2"), List.of(r.accepted.getJSONArray("entities").getJSONObject(0).getString("id"),
                r.accepted.getJSONArray("entities").getJSONObject(1).getString("id")));
    }

    @Test
    public void typicalDeviationsOfALocalModelStayDropped() {
        // an extra field in one item ("confidence", "city", "source"): the item is dropped, the answer kept
        assertOnly("entity_extra_field", validate(answer(E1 + "," + E2 + ",{\"id\":\"e3\",\"type\":\"facility\",\"name\":\"Haus Am See\","
                + "\"quote\":\"das Haus Am See\",\"confidence\":0.8}", C1, V1)));
        // a reference by an unexpected id: the name instead of the id, a known id that does not exist, a numeric id
        assertOnly("claim_unresolved_subject", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"Muster Pflege gGmbH\",\"predicate\":\"operates\","
                + "\"object\":\"e1\",\"quote\":\"Die Muster Pflege gGmbH betreibt das Haus Lindenhof\"}", V1)));
        assertOnly("claim_unresolved_object", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"k1\",\"predicate\":\"operates\","
                + "\"object\":\"entity_1\",\"quote\":\"Die Muster Pflege gGmbH betreibt das Haus Lindenhof\"}", V1)));
        assertOnly("claim_malformed_object", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"k1\",\"predicate\":\"operates\","
                + "\"object\":1,\"quote\":\"Die Muster Pflege gGmbH betreibt das Haus Lindenhof\"}", V1)));
        // a synonym or another spelling instead of the allowed enum
        for (final String type : List.of("Facility", "company", "Einrichtung", "organisation")) {
            assertOnly("entity_unknown_type", validate(answer(E1 + "," + E2 + ",{\"id\":\"e3\",\"type\":\"" + type + "\",\"name\":\"Haus Am See\","
                    + "\"quote\":\"das Haus Am See\"}", C1, V1)));
        }
        for (final String predicate : List.of("betreibt", "runs", "Operates", "provides")) {
            assertOnly("claim_unknown_predicate", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"k1\",\"predicate\":\"" + predicate + "\","
                    + "\"object\":\"e1\",\"quote\":\"Die Muster Pflege gGmbH betreibt das Haus Lindenhof\"}", V1)));
        }
        assertOnly("value_unknown_predicate", validate(answer(E1 + "," + E2, C1, V1 + ",{\"subject\":\"e2\",\"predicate\":\"cost\","
                + "\"quote\":\"bietet Tagespflege an\"}")));
        // the wrong subject/object types: the facility offers the organisation, the service is located at a service
        assertOnly("claim_object_type_mismatch", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"e1\",\"predicate\":\"offers\",\"object\":\"k1\","
                + "\"quote\":\"" + Q + "\"}", V1)));
        assertOnly("claim_subject_type_mismatch", validate(answer(E1 + "," + E2, C1 + ",{\"subject\":\"e2\",\"predicate\":\"part_of\",\"object\":\"k1\","
                + "\"quote\":\"" + Q + "\"}", V1)));
        assertOnly("value_subject_type_mismatch", validate(answer(E1 + "," + E2, C1, V1 + ",{\"subject\":\"k1\",\"predicate\":\"price\","
                + "\"quote\":\"bietet Tagespflege an\"}")));
    }

    @Test
    public void refusedAnswersCountNoReason() {
        final String bad = "{\"id\":\"e3\",\"type\":\"company\",\"name\":\"X\",\"quote\":\"x\"}";
        final StringBuilder many = new StringBuilder();
        for (int i = 0; i < LlmExtractor.MAX_ITEMS + 1; i++) {
            many.append(i == 0 ? "" : ",").append(bad);
        }
        for (final String[] c : new String[][] {
                {"invalid_json", "{\"entities\":[" + bad},
                {"invalid_json", "Here is the JSON: " + answer(bad, "", "")},
                {"not_an_object", "[" + bad + "]"},
                {"unknown_field", "{\"entities\":[" + bad + "],\"claims\":[],\"values\":[],\"notes\":\"x\"}"},
                {"schema", "{\"entities\":" + bad + ",\"claims\":[]}"},
                {"too_many_items", answer(many.toString(), "", "")},
                {"too_large", "{\"entities\":[" + bad + "]," + repeat(' ', LlmExtractor.MAX_ANSWER_BYTES) + "\"claims\":[]}"}}) {
            final LlmExtractor.Result r = validate(c[1]);
            assertEquals(c[0], r.refused);
            assertNull(r.accepted);
            assertEquals(c[0], 0, r.droppedInvalid);
            assertTrue(c[0], r.droppedInvalidByReason.isEmpty());
        }
        final LlmExtractor.Result empty = validate(null);
        assertEquals("empty", empty.refused);
        assertTrue(empty.droppedInvalidByReason.isEmpty());
    }

    @Test
    public void theReasonsOfTheExistingFixturesAddUp() {
        final LlmExtractor.Result r = LlmExtractor.validate(LlmExtractorTest.answer(), LlmExtractorTest.chunk(), LlmExtractorTest.known(),
                Set.of("nursinghome"));
        assertNotNull(r.accepted);
        assertEquals(Map.of("claim_subject_type_mismatch", 1, "claim_unresolved_object", 1), r.droppedInvalidByReason);
        assertEquals(r.droppedInvalid, sum(r));
    }
}
