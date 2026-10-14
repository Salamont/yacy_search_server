package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.function.Function;

import org.json.JSONArray;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;

/**
 * The type of an entity the LLM tier reads needs the context that states it, not only a quote that contains its name
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 33). Two confirmed mistakes of a production model: the tools of a page's sidebar
 * ("Bilder aus /…/", "Rechtschreibprüfung") and a team named in a contact text ("Our service team is looking forward to
 * providing any support") became services. The model is a fake answering as such a model did; what is checked is the real
 * parser, validator and publication. Counter-checks: services listed under their heading in a navigation, a spell check a
 * company offers, a job advertisement with its title, a team list without a vacancy, a software product sold and the same
 * product used internally. Example data only.
 */
public class LlmEntityContextTest extends LlmFixture {

    static final String HOST = "https://www.muster-pflege.de";

    @Override
    protected String[] settings() {
        return new String[] {KgConfig.COLLECTIONS, "c1", KgConfig.LLM_COLLECTIONS, "c1", KgConfig.LLM_MAX_DOCS_PER_HOST, "25",
                KgConfig.JOBS_EXTRACT_PREFIX + "c1", "true"};
    }

    /** The tier-3 entities of a page: type and name. */
    private List<String> entities(final String id) {
        return strings("SELECT DISTINCT t.name || ':' || s.obj_val FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
                + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred"
                + " JOIN kg_entity n ON n.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = n.type"
                + " WHERE e.tier = 3 AND d.doc_id = '" + id + "' AND v.name = 'name' ORDER BY 1");
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

    private static String entity(final String id, final String type, final String name, final String quote) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"name\":\"" + name + "\",\"quote\":\"" + quote + "\"}";
    }

    private static String answer(final String... entities) {
        return "{\"entities\":[" + String.join(",", entities) + "],\"claims\":[],\"values\":[]}";
    }

    // ------------------------------------------------------------ the confirmed mistakes

    /** The tools of a page's sidebar ("Werkzeuge") are no services the operator offers. */
    @Test
    public void theToolsOfASidebarAreNoServices() throws Exception {
        final String text = "Pflegegrade einfach erklärt. Die Muster Pflege gGmbH erklärt hier die fünf Pflegegrade und was sie für"
                + " Angehörige bedeuten.\nWerkzeuge\nLinks auf diese Seite\nÄnderungen an verlinkten Seiten\nBilder aus /husvedvf/\n"
                + "Rechtschreibprüfung\nSeiteninformationen";
        read("AAAAAActx001", "/wissen/pflegegrade", text, u -> answer(
                entity("e1", "service", "Bilder aus /husvedvf/", "Bilder aus /husvedvf/"),
                entity("e2", "service", "Rechtschreibprüfung", "Rechtschreibprüfung")));
        assertEquals(List.of(), entities("AAAAAActx001"));
        assertEquals(2, samples("entity_service_not_offered"));
    }

    /** A team a contact text names is no service, also where the sentence speaks of support. */
    @Test
    public void aTeamOfAContactTextIsNoService() throws Exception {
        final String text = "Contact. Our service team is looking forward to providing any support you need. Write to us or call us.";
        read("AAAAAActx002", "/kontakt", text, u -> answer(
                entity("e1", "service", "Service team", "Our service team is looking forward to providing any support")));
        assertEquals(List.of(), entities("AAAAAActx002"));
        assertEquals(1, samples("entity_service_team_or_role"));
    }

    // ------------------------------------------------------------ counter-checks

    /** An explicitly scoped offer list in navigation stays, also with a quote of the entry alone. */
    @Test
    public void servicesListedUnderTheirHeadingInANavigationStay() throws Exception {
        final String text = "Startseite\nLeistungen: Tagespflege, Kurzzeitpflege\nKontakt\nDie Muster Pflege gGmbH betreut Senioren in"
                + " Berlin und Brandenburg.";
        read("AAAAAActx003", "/", text, u -> answer(
                entity("e1", "service", "Tagespflege", "Leistungen: Tagespflege"),
                entity("e2", "service", "Kurzzeitpflege", "Kurzzeitpflege")));
        assertEquals(List.of("service:Kurzzeitpflege", "service:Tagespflege"), entities("AAAAAActx003"));
    }

    /** A spell check a company offers is a service: by its offer, or by its price. */
    @Test
    public void aSpellCheckTheCompanyOffersIsAService() throws Exception {
        final String text = "Lektorat und Korrektorat. Wir bieten Lektorat und Rechtschreibprüfung für Abschlussarbeiten an. Die"
                + " Rechtschreibprüfung kostet 2,50 € pro Seite.";
        read("AAAAAActx004", "/lektorat", text, u -> answer(
                entity("e1", "service", "Rechtschreibprüfung", "Wir bieten Lektorat und Rechtschreibprüfung für Abschlussarbeiten an")));
        assertEquals(List.of("service:Rechtschreibprüfung"), entities("AAAAAActx004"));
        read("AAAAAActx005", "/korrektorat", text, u -> "{\"entities\":[" + entity("e1", "service", "Rechtschreibprüfung",
                "Die Rechtschreibprüfung kostet 2,50 € pro Seite") + "],\"claims\":[],\"values\":[{\"subject\":\"e1\","
                + "\"predicate\":\"price\",\"quote\":\"Die Rechtschreibprüfung kostet 2,50 € pro Seite\"}]}");
        assertEquals(List.of("service:Rechtschreibprüfung"), entities("AAAAAActx005"));
    }

    /** A job advertisement keeps its job with the title. */
    @Test
    public void aJobAdvertisementWithItsTitleStays() throws Exception {
        final String text = "Stellenangebote. Wir suchen ab sofort eine Pflegefachkraft (m/w/d) in Vollzeit für das Haus Lindenhof.";
        read("AAAAAActx006", "/karriere/pflegefachkraft", text, u -> answer(
                entity("e1", "job", "Pflegefachkraft (m/w/d)", "Wir suchen ab sofort eine Pflegefachkraft (m/w/d)")));
        assertEquals(List.of("job:Pflegefachkraft (m/w/d)"), entities("AAAAAActx006"));
    }

    /** The roles and the teams of a team list without a vacancy are neither jobs nor services. */
    @Test
    public void aTeamListWithoutAVacancyHasNoJobAndNoService() throws Exception {
        final String text = "Unser Team. Max Muster – Pflegedienstleitung. Erika Beispiel – Verwaltungsleitung. Unser Vertriebsteam"
                + " erreichen Sie werktags von 8 bis 16 Uhr.";
        read("AAAAAActx007", "/team", text, u -> answer(
                entity("e1", "job", "Pflegedienstleitung", "Max Muster – Pflegedienstleitung"),
                entity("e2", "service", "Vertriebsteam", "Unser Vertriebsteam erreichen Sie werktags")));
        assertEquals(List.of(), entities("AAAAAActx007"));
        assertEquals(1, samples("entity_job_not_advertised"));
        assertEquals(1, samples("entity_service_team_or_role"));
    }

    /** A software product the company sells is a service of it; the same product it uses internally is none. */
    @Test
    public void aSoftwareProductSoldIsAServiceAndOneUsedInternallyIsNone() throws Exception {
        read("AAAAAActx008", "/produkte", "Produkte. Unsere Software MusterText Pro prüft die Rechtschreibung in Word-Dokumenten."
                + " MusterText Pro können Sie als Jahreslizenz kaufen.", u -> answer(
                        entity("e1", "service", "MusterText Pro", "MusterText Pro können Sie als Jahreslizenz kaufen")));
        assertEquals(List.of("service:MusterText Pro"), entities("AAAAAActx008"));
        read("AAAAAActx009", "/ueber-uns", "Über uns. Für unsere Pflegedokumentation nutzen wir intern die Software MusterText Pro.",
                u -> answer(entity("e1", "service", "MusterText Pro", "nutzen wir intern die Software MusterText Pro")));
        assertEquals(List.of(), entities("AAAAAActx009"));
    }

    /**
     * An offer stated with an inflected verb ("verkauft", "vertreibt", "vermietet", "sells") is an offer: the product a company
     * sells stays a service. The quality check's reference page "page tool vs product" found the gap (0.10.7 integration).
     */
    @Test
    public void aProductTheCompanySaysItSellsIsAService() throws Exception {
        read("AAAAAActx011", "/produkte/mustertext", "MusterText Pro. Die Muster Software GmbH verkauft MusterText Pro, eine Software"
                + " für Kanzleien.", u -> answer(entity("e1", "service", "MusterText Pro", "Die Muster Software GmbH verkauft MusterText Pro")));
        assertEquals(List.of("service:MusterText Pro"), entities("AAAAAActx011"));
        read("AAAAAActx012", "/geraete", "Geräteverleih. Die Muster Technik GmbH vermietet Hubarbeitsbühnen an Handwerksbetriebe.",
                u -> answer(entity("e1", "service", "Hubarbeitsbühnen", "Die Muster Technik GmbH vermietet Hubarbeitsbühnen")));
        assertEquals(List.of("service:Hubarbeitsbühnen"), entities("AAAAAActx012"));
        read("AAAAAActx013", "/en/products", "Products. Muster Software Ltd sells MusterText Pro to law firms.",
                u -> answer(entity("e1", "service", "MusterText Pro", "Muster Software Ltd sells MusterText Pro")));
        assertEquals(List.of("service:MusterText Pro"), entities("AAAAAActx013"));
    }

    /** The read-only query of the cleanup plan (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 33.1), verbatim. */
    static final String CANDIDATES = "SELECT t.name AS type, n.obj_val AS name, d.url, substr(e.excerpt, 1, 160) AS excerpt, x.model,"
            + " (SELECT group_concat(substr(oe.excerpt, 1, 120), ' | ') FROM kg_statement o JOIN kg_vocab ov ON ov.term_id = o.pred"
            + " JOIN kg_evidence oe ON oe.stmt_rowid = o.stmt_rowid AND oe.tier = 3"
            + " WHERE (o.obj_ent = n.subj AND ov.name = 'offers') OR (o.subj = n.subj AND ov.name = 'hiring_organization')) AS context"
            + " FROM kg_statement n JOIN kg_vocab v ON v.term_id = n.pred AND v.name = 'name'"
            + " JOIN kg_entity en ON en.ent_rowid = n.subj JOIN kg_vocab t ON t.term_id = en.type AND t.name IN ('service', 'job')"
            + " JOIN kg_evidence e ON e.stmt_rowid = n.stmt_rowid AND e.tier = 3 JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
            + " JOIN kg_extractor x ON x.ext_id = e.ext_id"
            + " WHERE NOT EXISTS (SELECT 1 FROM kg_evidence o WHERE o.stmt_rowid = n.stmt_rowid AND o.tier IN (1, 2))"
            + " ORDER BY d.url LIMIT 500";

    /** The cleanup plan's query lists the services and jobs only the LLM tier states, with their excerpts and the model. */
    @Test
    public void theCleanupQueryListsWhatOnlyTheLlmTierStates() throws Exception {
        read("AAAAAActx010", "/angebot", "Unser Angebot. Wir bieten Tagespflege und Kurzzeitpflege in Berlin an.", u -> "{\"entities\":["
                + entity("e1", "service", "Tagespflege", "Wir bieten Tagespflege und Kurzzeitpflege in Berlin an") + "],\"claims\":[{"
                + "\"subject\":\"k1\",\"predicate\":\"offers\",\"object\":\"e1\",\"quote\":\"Wir bieten Tagespflege\"}],\"values\":[]}");
        final List<String> rows = strings("SELECT type || '|' || name || '|' || url || '|' || excerpt || '|' || model FROM (" + CANDIDATES + ")");
        assertEquals(rows.toString(), 1, rows.size());
        assertEquals("service|Tagespflege|" + HOST + "/angebot|Wir bieten Tagespflege und Kurzzeitpflege in Berlin an|TEST/fixture",
                rows.get(0));
    }
}
