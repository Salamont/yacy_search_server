/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.Test;

import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * 0.12.0 (rule/8): an offer of the rule tier is shown by an offer sentence, a heading or a price (explicit, as before), by a
 * consulting or delivery sentence whose subject is the provider (explicit), or by the page's structure: its title or brand
 * line, a bulleted item, the title of the service's own page (structural, {@link BusinessRules#STRUCTURAL_CONFIDENCE}). Tools
 * and own use, denials, a bare menu, news pages and the provider's own past introduction of a system stay topics.
 */
public class OfferEvidenceRule8Test {

    private static ExtractContext ctx(final String vocabulary) {
        return new ExtractContext(KgVocabularies.get(), Set.of(vocabulary), false, List.of("test-web"));
    }

    private static Extraction rules(final String vocabulary, final String url, final List<String> titles, final String text) {
        final Extraction ex = new Extraction(300);
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex, ctx(vocabulary), titles, List.of());
        return ex;
    }

    /** The offered categories with the confidence of their offers relation. */
    private static Map<String, Double> offers(final Extraction ex) {
        final Map<String, Double> out = new TreeMap<>();
        for (final Claim c : ex.claims()) {
            if (Vocabulary.OFFERS.equals(c.predicate) && c.object != null) {
                final String ref = ex.mention(c.object).ref;
                out.put(ref.substring(ref.lastIndexOf(':') + 1).replaceFirst("^.*/", ""), c.confidence);
            }
        }
        return out;
    }

    private static List<String> topics(final Extraction ex) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (Vocabulary.TOPIC.equals(c.predicate)) out.add(c.value.replaceFirst("^.*/", ""));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ explicit, new verbs

    @Test
    public void aConsultingSentenceOfTheProviderIsAnOffer() {
        for (final String sentence : List.of("Wir unterstützen Sie bei der Einführung von SAP S/4HANA.",
                "Bei der Migration auf SAP S/4HANA begleiten wir Sie von der Analyse bis zum Go-live.",
                "Unsere Berater setzen Ihre SAP S/4HANA-Migration termingerecht um.",
                "Für SAP S/4HANA stehen wir Ihnen mit erfahrenen Beratern zur Verfügung.",
                "Wir haben für die Muster AG die SAP S/4HANA-Einführung umgesetzt.")) {
            final Map<String, Double> o = offers(rules("software", "https://www.muster-beratung.de/", List.of("Muster Beratung GmbH"),
                    "Willkommen bei der Muster Beratung GmbH. " + sentence));
            assertTrue(sentence + " " + o, o.containsKey("sap"));
            assertEquals(sentence, BusinessRules.HOME_PAGE_CONFIDENCE, o.get("sap"), 1e-9);
        }
    }

    @Test
    public void ownUseToolsDenialsAndOwnPastIntroductionStayTopics() {
        for (final String sentence : List.of("Wir nutzen SAP und unterstützen unsere Kunden bei der Digitalisierung.",
                "Wir setzen SAP S/4HANA intern ein.", "Wir arbeiten mit TYPO3 und beraten Sie zu Ihrer Website.",
                "Wir haben SAP S/4HANA eingeführt.", "Wir unterstützen keine SAP-Systeme.",
                "Unterstützen Sie uns: Wir suchen Verstärkung mit SAP-Kenntnissen.",
                "Wir haben SAP S/4HANA für alle unsere Standorte eingeführt.", "Wir haben SAP S/4HANA für unsere Buchhaltung eingeführt.",
                "Sie unterstützen unser Team bei der Einführung von SAP S/4HANA.")) {
            final Extraction ex = rules("software", "https://www.muster-industrie.de/", List.of("Muster Industrie GmbH"),
                    "Willkommen bei der Muster Industrie GmbH. " + sentence);
            final Map<String, Double> o = offers(ex);
            assertFalse(sentence + " " + o, o.containsKey("sap"));
            assertFalse(sentence + " " + o, o.containsKey("cms"));
        }
    }

    // --------------------------------------------------------------------------------------------------- structural

    @Test
    public void theTitleOrBrandLineOfTheOwnSiteShowsAnOfferStructurally() {
        final Extraction ex = rules("construction", "https://www.maler-dehmel.de/", List.of("Malermeister Dehmel | Malerarbeiten in Oschersleben"),
                "Herzlich willkommen. Malerarbeiten, Tapezieren und mehr. Ihr Team vom Malermeister Dehmel.");
        assertEquals(BusinessRules.STRUCTURAL_CONFIDENCE, offers(ex).get("malerarbeiten"), 1e-9);
        assertTrue(BusinessRules.structural(offers(ex).get("malerarbeiten")));
        assertFalse(topics(ex).contains("malerarbeiten"));
    }

    @Test
    public void aBulletedItemShowsAnOfferStructurallyButADeniedOneDoesNot() {
        final Extraction ex = rules("construction", "https://www.muster-bau.de/", List.of("Muster Bau GmbH"),
                "Willkommen bei Muster Bau.\nUnser Handwerk\n- Fassadensanierung.\n- Zimmerei\n- keine Dachdeckerei\nKontakt");
        final Map<String, Double> o = offers(ex);
        assertEquals(o.toString(), BusinessRules.STRUCTURAL_CONFIDENCE, o.get("fassade"), 1e-9);
        assertEquals(o.toString(), BusinessRules.STRUCTURAL_CONFIDENCE, o.get("zimmerei"), 1e-9);
        assertFalse(o.toString(), o.containsKey("dachdeckerei"));
    }

    @Test
    public void theOwnPageOfAServiceNamesItsOfferInItsTitle() {
        final String url = "https://www.muster-stellwerk.de/sap-beratung/sap-cash-management/";
        assertTrue(BusinessRules.kinds(url, null).contains(BusinessRules.PageKind.SERVICE_DETAIL));
        assertTrue("the LLM tier reads it", RuleExtractor.candidate(url, List.of("SAP Cash Management"), null));
        final Extraction ex = rules("software", url, List.of("SAP-Beratung: Cash Management | Muster Stellwerk"),
                "SAP Cash Management. Liquidität im Blick mit SAP S/4HANA. Mehr zu IT-Beratung finden Sie hier.");
        final Map<String, Double> o = offers(ex);
        assertEquals(o.toString(), BusinessRules.STRUCTURAL_CONFIDENCE, o.get("sap"), 1e-9);
        assertFalse("a category the title does not name stays a topic: " + o, o.containsKey("it_beratung"));
        assertTrue(topics(ex).contains("it_beratung"));
    }

    @Test
    public void datesVacanciesAndASingleDashAreNoListOfOffers() {
        for (final String text : List.of("Unsere Meilensteine\n12. März 2025 Go-live von SAP S/4HANA\n1. April 2025 Schulung der Teams",
                "Offene Stellen\n• SAP Inhouse Consultant (m/w/d)\n• Buchhalter (m/w/d)",
                "Bei uns\n- Wir nutzen SAP S/4HANA\n- Wir arbeiten mit Salesforce",
                "Willkommen.\n- SAP S/4HANA ist bei uns seit Jahren im Einsatz.\nMehr erfahren Sie im Gespräch.")) {
            final Map<String, Double> o = offers(rules("software", "https://www.muster-industrie.de/", List.of("Willkommen"), "Muster Industrie GmbH. " + text));
            assertFalse(text + " " + o, o.containsKey("sap"));
            assertFalse(text + " " + o, o.containsKey("crm"));
        }
    }

    @Test
    public void newsAndCareersPagesAreNoServicePagesWhateverTheirWords() {
        for (final String url : List.of("https://www.muster-stellwerk.de/careers/sap-consulting-m-w-d/",
                "https://www.muster-stellwerk.de/offene-stellen/sap-beratung/", "https://www.muster-stellwerk.de/ausbildung/it-beratung/")) {
            assertFalse(url, BusinessRules.kinds(url, null).contains(BusinessRules.PageKind.SERVICE_DETAIL));
        }
        assertFalse("a vacancy by its title", BusinessRules.kinds("https://www.muster-stellwerk.de/sap-beratung/",
                List.of("Karriere: SAP-Berater (m/w/d)")).contains(BusinessRules.PageKind.SERVICE_DETAIL));
        for (final String url : List.of("https://www.muster-stellwerk.de/blog/sap-beratung-trends/",
                "https://www.muster-stellwerk.de/news/it-beratung-2027/", "https://www.muster-stellwerk.de/karriere/sap-beratung/")) {
            assertFalse(url, BusinessRules.kinds(url, null).contains(BusinessRules.PageKind.SERVICE_DETAIL));
            final Extraction ex = rules("software", url, List.of("SAP-Beratung im Wandel"), "SAP-Beratung im Wandel. Ein Beitrag über SAP S/4HANA.");
            assertTrue(url + " " + offers(ex), offers(ex).isEmpty());
        }
    }

    /** The model's offer of a named provider needs that provider as the subject of the consulting verb, not as its client. */
    @Test
    public void aConsultingSentenceProvesTheOfferOfItsSubjectOnly() {
        final String client = "Wir haben die Muster AG bei der Einführung von SAP S/4HANA unterstützt.";
        assertEquals(null, ServiceEvidence.evidence(client, client, "SAP S/4HANA", "Muster AG"));
        for (final String own : List.of("Die Muster AG unterstützt Sie bei der Einführung von SAP S/4HANA.",
                "Bei der Migration auf SAP S/4HANA begleitet die Muster AG Sie von der Analyse bis zum Go-live.",
                "Die Muster AG hat für die Kunde GmbH die Einführung von SAP S/4HANA umgesetzt.")) {
            assertTrue(own, ServiceEvidence.evidence(own, own, "SAP S/4HANA", "Muster AG") != null);
        }
    }

    @Test
    public void aBareMenuWithoutASecondSignStaysATopic() {
        final Extraction ex = rules("software", "https://www.muster-stellwerk.de/", List.of("Willkommen"),
                "Willkommen bei Muster Stellwerk. SAP | IT-Beratung | Karriere | Kontakt");
        assertTrue(offers(ex).toString(), offers(ex).isEmpty());
        assertTrue(topics(ex).containsAll(List.of("sap", "it_beratung")));
        // the same menu with a brand line naming the field: a structural offer of that field only
        final Map<String, Double> titled = offers(rules("software", "https://www.muster-stellwerk.de/", List.of("Muster Stellwerk | SAP-Beratung"),
                "Willkommen bei Muster Stellwerk. SAP | IT-Beratung | Karriere | Kontakt"));
        assertEquals(titled.toString(), Set.of("sap"), titled.keySet());
    }
}
