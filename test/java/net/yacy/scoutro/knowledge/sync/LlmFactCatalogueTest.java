package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;

/**
 * The fact catalogue of the LLM tier (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.7): per kind of page what must be stored, with
 * tier-3 evidence quoting the page verbatim, and what must be refused and counted. The model is a fake answering as a
 * local model typically does, mistakes included; what is checked is the validator and the publication, never the
 * quality of a real model (that is the comparison with the production model, outside the tests). Example data only.
 */
public class LlmFactCatalogueTest extends LlmFixture {

    static final String HOST = "https://www.muster-pflege.de";

    @Override
    protected String[] settings() {
        return new String[] {KgConfig.COLLECTIONS, "c1", KgConfig.LLM_COLLECTIONS, "c1", KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                "scoutro.kg.llm.kinds.c1", "nursinghome", KgConfig.JOBS_EXTRACT_PREFIX + "c1", "true"};
    }

    private static final Pattern KNOWN = Pattern.compile("(k\\d+): \\w+ \"([^\"]*)\"");

    /** The id the request gives the known entity of that name. */
    private static String k(final String user, final String name) {
        final Matcher m = KNOWN.matcher(user);
        while (m.find()) {
            if (m.group(2).equals(name)) {
                return m.group(1);
            }
        }
        return "k0";
    }

    /** The tier-3 facts of a page: subject, predicate and object or value, with the quote of its evidence. */
    private List<String> facts(final String id) {
        return strings("SELECT (SELECT n.obj_val FROM kg_statement n JOIN kg_vocab nv ON nv.term_id = n.pred WHERE n.subj = s.subj"
                + " AND nv.name = 'name' LIMIT 1) || ' ' || v.name || ' ' || coalesce((SELECT n.obj_val FROM kg_statement n JOIN kg_vocab nv"
                + " ON nv.term_id = n.pred WHERE n.subj = s.obj_ent AND nv.name = 'name' LIMIT 1), s.obj_val) || ' | ' || e.excerpt"
                + " FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE e.tier = 3 AND d.doc_id = '" + id + "' AND v.name <> 'name' ORDER BY 1");
    }

    private void read(final String id, final String path, final String text, final Function<String, String> answer) throws Exception {
        this.model.answer = answer;
        add(id, HOST + path, "c1", LD, text, path.substring(1));
        settleSync();
        settleLlm();
        assertEquals("1:", llmStatus(id));
    }

    private int samples(final String reason) throws Exception {
        final JSONArray s = this.llm.refusals(reason, 100).getJSONArray("samples");
        return s.length();
    }

    @Test
    public void imprintTheOperatorOperatesItsFacilityAndIsNoRelationOfItself() throws Exception {
        final String text = "Impressum. Die Muster Pflege gGmbH betreibt das Haus Lindenhof in Berlin. Das Haus Lindenhof bietet"
                + " ambulante Pflege an.";
        read("AAAAAAcat001", "/impressum", text, u -> "{\"entities\":["
                + "{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Lindenhof\",\"kind\":\"nursinghome\",\"quote\":\"betreibt das Haus Lindenhof\"},"
                + "{\"id\":\"e2\",\"type\":\"service\",\"name\":\"ambulante Pflege\",\"quote\":\"bietet ambulante Pflege an\"},"
                // a local model repeats the operator under a new id and relates it to itself
                + "{\"id\":\"e3\",\"type\":\"organization\",\"name\":\"Muster Pflege gGmbH\",\"quote\":\"Die Muster Pflege gGmbH betreibt\"}],"
                + "\"claims\":[{\"subject\":\"" + k(u, "Muster Pflege gGmbH") + "\",\"predicate\":\"operates\",\"object\":\"e1\","
                + "\"quote\":\"Die Muster Pflege gGmbH betreibt das Haus Lindenhof\"},"
                + "{\"subject\":\"e1\",\"predicate\":\"offers\",\"object\":\"e2\",\"quote\":\"Das Haus Lindenhof bietet ambulante Pflege an\"},"
                + "{\"subject\":\"e3\",\"predicate\":\"part_of\",\"object\":\"" + k(u, "Muster Pflege gGmbH") + "\","
                + "\"quote\":\"Die Muster Pflege gGmbH betreibt\"}],\"values\":[]}");
        assertEquals(List.of("Haus Lindenhof offers ambulante Pflege | Das Haus Lindenhof bietet ambulante Pflege an",
                "Muster Pflege gGmbH operates Haus Lindenhof | Die Muster Pflege gGmbH betreibt das Haus Lindenhof"), facts("AAAAAAcat001"));
        assertEquals("one organisation of that name", List.of("1"), strings("SELECT count(DISTINCT s.subj) FROM kg_statement s"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'name' AND s.obj_val = 'Muster Pflege gGmbH'"));
        assertEquals(1, samples("claim_self_reference"));
    }

    @Test
    public void servicesAPriceBelongsToTheServiceAndAnUnnamedOperatorIsNoClaim() throws Exception {
        final String text = "Unsere Leistungen: Tagespflege und Kurzzeitpflege. Wir bieten Tagespflege ab 49 € pro Tag.";
        read("AAAAAAcat002", "/leistungen", text, u -> "{\"entities\":["
                + "{\"id\":\"e1\",\"type\":\"service\",\"name\":\"Tagespflege\",\"quote\":\"Unsere Leistungen: Tagespflege\"}],"
                // "we offer": the page does not name who offers it, so the quote cannot hold the operator's name
                + "\"claims\":[{\"subject\":\"" + k(u, "Muster Pflege gGmbH") + "\",\"predicate\":\"offers\",\"object\":\"e1\","
                + "\"quote\":\"Wir bieten Tagespflege\"}],"
                + "\"values\":[{\"subject\":\"e1\",\"predicate\":\"price\",\"quote\":\"Tagespflege ab 49 € pro Tag\"},"
                + "{\"subject\":\"" + k(u, "Muster Pflege gGmbH") + "\",\"predicate\":\"price\",\"quote\":\"ab 49 € pro Tag\"}]}");
        final List<String> facts = facts("AAAAAAcat002");
        assertEquals(facts.toString(), 1, facts.size());
        assertTrue(facts.get(0), facts.get(0).startsWith("Tagespflege price ") && facts.get(0).contains("49")
                && facts.get(0).endsWith("| Tagespflege ab 49 € pro Tag"));
        assertEquals("the unnamed operator: not quoted", 1, samples("ungrounded"));
        assertEquals("a price of the organisation", 1, samples("value_subject_type_mismatch"));
    }

    @Test
    public void locationsAFacilityIsLocatedAtItsSite() throws Exception {
        final String text = "Standorte: Das Haus Lindenhof befindet sich in der Musterstraße 1, 10115 Berlin.";
        read("AAAAAAcat003", "/standorte", text, u -> "{\"entities\":["
                + "{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Lindenhof\",\"quote\":\"Das Haus Lindenhof befindet sich\"},"
                + "{\"id\":\"e2\",\"type\":\"site\",\"name\":\"Musterstraße 1, 10115 Berlin\",\"quote\":\"Musterstraße 1, 10115 Berlin\"}],"
                + "\"claims\":[{\"subject\":\"e1\",\"predicate\":\"located_at\",\"object\":\"e2\","
                + "\"quote\":\"Das Haus Lindenhof befindet sich in der Musterstraße 1, 10115 Berlin\"},"
                // a site located at a service: the relation's types are refused
                + "{\"subject\":\"e2\",\"predicate\":\"located_at\",\"object\":\"e1\",\"quote\":\"Das Haus Lindenhof befindet sich in der Musterstraße 1\"}],"
                + "\"values\":[]}");
        assertEquals(List.of("Haus Lindenhof located_at Musterstraße 1, 10115 Berlin"
                + " | Das Haus Lindenhof befindet sich in der Musterstraße 1, 10115 Berlin"), facts("AAAAAAcat003"));
        assertEquals(1, samples("claim_subject_type_mismatch"));
    }

    @Test
    public void referencesACustomerIsStoredAnInventedOneAndAPersonAreNot() throws Exception {
        final String text = "Referenzen: Die Klinikum Nord GmbH ist Kunde der Muster Pflege gGmbH. Ansprechpartner: Herr Max Beispiel.";
        read("AAAAAAcat004", "/referenzen", text, u -> "{\"entities\":["
                + "{\"id\":\"e1\",\"type\":\"organization\",\"name\":\"Klinikum Nord GmbH\",\"quote\":\"Die Klinikum Nord GmbH ist Kunde\"},"
                + "{\"id\":\"e2\",\"type\":\"organization\",\"name\":\"Erfundene Beispiel AG\",\"quote\":\"Die Erfundene Beispiel AG\"},"
                + "{\"id\":\"e3\",\"type\":\"organization\",\"name\":\"Herr Max Beispiel\",\"quote\":\"Ansprechpartner: Herr Max Beispiel\"}],"
                + "\"claims\":[{\"subject\":\"e1\",\"predicate\":\"customer_of\",\"object\":\"" + k(u, "Muster Pflege gGmbH") + "\","
                + "\"quote\":\"Die Klinikum Nord GmbH ist Kunde der Muster Pflege gGmbH\"},"
                + "{\"subject\":\"e2\",\"predicate\":\"reference_for\",\"object\":\"" + k(u, "Muster Pflege gGmbH") + "\","
                + "\"quote\":\"Die Erfundene Beispiel AG\"}],\"values\":[]}");
        assertEquals(List.of("Klinikum Nord GmbH customer_of Muster Pflege gGmbH | Die Klinikum Nord GmbH ist Kunde der Muster Pflege gGmbH"),
                facts("AAAAAAcat004"));
        assertEquals("never a person", 1, samples("entity_person_or_contact"));
        assertEquals("the invented organisation, and the claim about it", 1, samples("claim_entity_dropped"));
        for (int i = 0; i < this.llm.refusals(null, 100).getJSONArray("samples").length(); i++) {
            final String sample = this.llm.refusals(null, 100).getJSONArray("samples").getJSONObject(i).toString();
            assertTrue(sample, !sample.contains("Max Beispiel"));
        }
    }

    @Test
    public void jobsTheEmployerAndASalaryWithItsPeriodAreStoredAClientOfARecruiterIsNot() throws Exception {
        final String text = "Karriere: Wir suchen eine Pflegefachkraft (m/w/d) in Vollzeit. Die Muster Pflege gGmbH zahlt 3.500 € pro Monat."
                + " Im Auftrag unseres Kunden suchen wir eine Pflegehilfskraft (m/w/d) für die Muster Pflege gGmbH.";
        read("AAAAAAcat005", "/karriere", text, u -> "{\"entities\":["
                + "{\"id\":\"e1\",\"type\":\"job\",\"name\":\"Pflegefachkraft (m/w/d)\",\"quote\":\"Wir suchen eine Pflegefachkraft (m/w/d)\"},"
                + "{\"id\":\"e2\",\"type\":\"job\",\"name\":\"Pflegehilfskraft (m/w/d)\",\"quote\":\"suchen wir eine Pflegehilfskraft (m/w/d)\"}],"
                + "\"claims\":[{\"subject\":\"e1\",\"predicate\":\"hiring_organization\",\"object\":\"" + k(u, "Muster Pflege gGmbH") + "\","
                + "\"quote\":\"Pflegefachkraft (m/w/d) in Vollzeit. Die Muster Pflege gGmbH\"},"
                + "{\"subject\":\"e2\",\"predicate\":\"hiring_organization\",\"object\":\"" + k(u, "Muster Pflege gGmbH") + "\","
                + "\"quote\":\"Im Auftrag unseres Kunden suchen wir eine Pflegehilfskraft (m/w/d) für die Muster Pflege gGmbH\"}],"
                + "\"values\":[{\"subject\":\"e1\",\"predicate\":\"salary\",\"quote\":\"zahlt 3.500 € pro Monat\"}]}");
        final List<String> facts = facts("AAAAAAcat005");
        assertTrue(facts.toString(), facts.contains("Pflegefachkraft (m/w/d) hiring_organization Muster Pflege gGmbH"
                + " | Pflegefachkraft (m/w/d) in Vollzeit. Die Muster Pflege gGmbH"));
        assertTrue(facts.toString(), facts.stream().anyMatch(f -> f.startsWith("Pflegefachkraft (m/w/d) salary ") && f.contains("3500")));
        assertTrue("a client of a recruiter is not the employer: " + facts,
                facts.stream().noneMatch(f -> f.startsWith("Pflegehilfskraft (m/w/d) hiring_organization")));
        assertEquals(1, samples("claim_employer_unresolved"));
    }
}
