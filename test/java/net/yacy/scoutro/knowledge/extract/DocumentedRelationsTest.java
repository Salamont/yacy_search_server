/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * 0.12.0 (gate T6, round 2, owner decision of 22 October 2026): the clients a provider documents (a project profile, a heading
 * of a reference page, a customer's voice with its company) are its clients, "client customer_of operator", with the products
 * of their project as a purchased service and a program as a purchased need; a customer's voice keeps its own words. Several
 * named clients are each one; a purpose ("… for Rocket Manufacturing"), the reader or an unclear role makes none.
 */
public class DocumentedRelationsTest {

    private static Extraction rules(final String vocabulary, final String url, final String text) {
        return rules(vocabulary, url, text, "Referenzen");
    }

    private static Extraction rules(final String vocabulary, final String url, final String text, final String title) {
        final Extraction ex = new Extraction(300);
        final ExtractContext ctx = new ExtractContext(KgVocabularies.get(), Set.of(vocabulary), false, List.of("test-web"));
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex, ctx, List.of(title), List.of());
        return ex;
    }

    /** Client name to the contexts of its signals (product or need), with "customer_of" if it is a client of the operator. */
    private static Map<String, Set<String>> clients(final Extraction ex) throws Exception {
        final Map<String, Set<String>> out = new TreeMap<>();
        for (final Claim c : ex.claims()) {
            final Mention s = ex.mention(c.subject);
            if (Vocabulary.CUSTOMER_OF.equals(c.predicate)) out.computeIfAbsent(s.name, k -> new java.util.TreeSet<>()).add("customer_of");
            if ((Vocabulary.SYSTEM_SIGNAL.equals(c.predicate) || Vocabulary.BUSINESS_NEED_SIGNAL.equals(c.predicate)) && s.name != null && s.tier == 2
                    && !s.ref.equals(BusinessRules.SITE_REF)) {
                final JSONObject v = new JSONObject(c.value);
                out.computeIfAbsent(s.name, k -> new java.util.TreeSet<>()).add(v.getString("context") + ":" + v.optString("product", v.optString("need")));
            }
        }
        return out;
    }

    @Test
    public void aProjectProfileNamesItsClientsAndWhatTheyBought() throws Exception {
        final Map<String, Set<String>> c = clients(rules("software", "https://www.muster-stellwerk.de/wp-content/uploads/story.pdf",
                "S/4HANA Release-Wechsel\nProjekt-Steckbrief\nKunden: Energieversorgungs- und Verkehrsgesellschaft mbH Aachen (E.V.A.) und\n"
                        + " regio iT gesellschaft für Informationstechnologie mbh\nAuftrag: System-Aktualisierung von Release S/4HANA 2021 auf 2025\n"
                        + "Rolle: Projektplaner & -manager, Coach, Umsetzungspartner\nGo-live erfolgreich abgeschlossen."));
        assertEquals(c.toString(), Set.of("customer_of", "purchased_service:sap-s4hana"), c.get("regio iT gesellschaft für Informationstechnologie mbh"));
        assertEquals(c.toString(), Set.of("customer_of", "purchased_service:sap-s4hana"),
                c.get("Energieversorgungs- und Verkehrsgesellschaft mbH Aachen (E.V.A.)"));
        // a list of clients without a second field of a profile is no profile (the list markers read it)
        assertTrue(clients(rules("software", "https://www.muster-stellwerk.de/", "Kunden: Muster AG, Beispiel GmbH\nKontakt")).isEmpty());
    }

    /** Phase 3c (production pages naming clients, 22 October 2026): the client field inline, on a line of its own and in a list. */
    @Test
    public void theClientFieldOfAProjectInlineOnItsOwnLineAndInAList() throws Exception {
        assertEquals(Set.of("Ev.-Luth. Domgemeinde Freiberg"), clients(rules("software", "https://www.muster-restaurierung.de/project/dom-kreuzgang/",
                "# Dom Freiberg, Kreuzgang.\n\n**Auftraggeber:. **Ev.-Luth. Domgemeinde Freiberg. **Leistung:. **Herstellung von Gittertoren.")).keySet());
        assertEquals(Set.of("Dresdner Verkehrsbetriebe AG", "SBB AG", "ARGE Brienzersee"), clients(rules("software",
                "https://www.muster-gleisbau.de/de/referenzen", "## Betriebshof Trachenberge.\n\nOrt.\n\nDresden.\n\nAuftraggeber.\n\n"
                        + "Dresdner Verkehrsbetriebe AG.\n\nMehr erfahren.\n\n## Wipkingertunnel.\n\nAuftraggeber.\n\nSBB AG, Infrastruktur – Ausbau und"
                        + " Erneuerungsprojekte.\n\nMehr erfahren.\n\n## Brienzersee.\n\nAuftraggeber.\n\nARGE Brienzersee (Bauherr: Die Zentralbahn, Meiringen, CH)."
                        + "\n\nMehr erfahren.")).keySet());
        assertEquals(Set.of("Strabag AG", "Diakonieverein Güstrow"), clients(rules("software", "https://www.muster-reinigung.de/referenzen/",
                "## Baufeinreinigung.\n- Schloßpark-Center Schwerin. Auftraggeber: Strabag AG.\n- Betreutes Wohnen. Auftraggeber: Diakonieverein"
                        + " Güstrow.\n- Einfamilienhaus. Auftraggeber: Privat.")).keySet());
        // a sample invoice on a home page names no client
        assertTrue(clients(rules("software", "https://www.muster-dms.de/", "E-Rechnung.\n\nRN: 2026-890.\n\nBetrag: 1.499 €.\n\nKunde: Meier GmbH."
                + "\n\nDatum: 12.05.2026.")).isEmpty());
        // a label in a sentence of a home page ("… oder öffentliche. Auftraggeber.") is none
        assertTrue(clients(rules("software", "https://www.muster-sanitaer.de/", "Wir. sind. für. Sie. da,. ob. Privatkunden,. Unternehmen. oder."
                + " öffentliche. Auftraggeber.\n\nKostenlose Erstberatung. Leistungen entdecken.")).isEmpty());
    }

    /** Phase 3c: a list of names in a heading, a place after "in … bei", and the title of the page of one reference. */
    @Test
    public void namesOfHeadingsPlacesAndTitlesOfReferences() throws Exception {
        assertEquals(Set.of("BASF Schwarzheide GmbH"), clients(rules("software", "https://www.muster-gleisbau.de/de/referenzen",
                "## Rahmenvertrag für Gleisbauarbeiten, BASF Schwarzheide GmbH.\n\nOrt. Schwarzheide.")).keySet());
        assertTrue(clients(rules("software", "https://www.muster-sanitaer.de/referenzen/", "### Badsanierung in Gschlachtenbretzingen bei"
                + " Michelbach an der Bilz.\n\nReferenz ansehen.")).isEmpty());
        final Map<String, Set<String>> claas = clients(rules("software", "https://www.muster-data.de/success-stories/claas/",
                "Kunden, die uns vertrauen. CLAAS KGaA mbH. Die Finanzplanung mit SAP Analytics Cloud bei CLAAS ist live.",
                "Success Story: SAP SAC Finanzplanung bei CLAAS | Muster Data"));
        assertTrue(claas.toString(), claas.containsKey("CLAAS") && claas.get("CLAAS").contains("customer_of"));
        final Map<String, Set<String>> altana = clients(rules("software", "https://www.muster-data.de/success-stories/altana/",
                "ALTANA berichtet ESG-Kennzahlen mit SAP BW/4HANA.", "Success Story ALTANA: ESG-Reporting mit SAP BW/4HANA | Muster Data"));
        assertTrue(altana.toString(), altana.containsKey("ALTANA") && altana.get("ALTANA").contains("customer_of"));
        // a title naming nobody on the page makes no client
        assertTrue(clients(rules("software", "https://www.muster-data.de/success-stories/x/", "Ein Projekt.", "Referenz Muster AG: Reporting"))
                .isEmpty());
    }

    @Test
    public void headingsOfAReferencePageNameTheClientsOfTheirProjects() throws Exception {
        final Map<String, Set<String>> c = clients(rules("software", "https://www.muster-stellwerk.de/referenzen/success-stories/",
                "Success Stories.\n\n### S/4HANA Release-Wechsel bei E.V.A. & regio iT.\n\nWie ein Release-Wechsel weit unter Budget realisiert werden kann.\n\n"
                        + "### SAP-Einführung für die Muster Logistik GmbH.\n\nDownload.\n\n### Quality Control for Rocket Manufacturing.\n\nRead.\n\n"
                        + "### Mehr Erfolg für Ihr Unternehmen.\n\nKontakt."));
        assertEquals(c.toString(), Set.of("E.V.A", "regio iT", "Muster Logistik GmbH"), c.keySet());
        assertTrue(c.toString(), c.get("regio iT").contains("purchased_service:sap-s4hana"));
        // the same heading outside a reference page names nobody
        assertTrue(clients(rules("software", "https://www.muster-stellwerk.de/", "### S/4HANA Release-Wechsel bei E.V.A. & regio iT.\n\nText.")).isEmpty());
    }

    @Test
    public void aCustomersVoiceBelongsToItsCompany() throws Exception {
        final Map<String, Set<String>> c = clients(rules("software", "https://www.dab-muster.de/produkte/",
                "## Kunden, die unsere Lösungen nutzen.\n\nRudolf Muster.\n\nLeiter Revision, Beispiel AG.\n\n"
                        + "Wir nutzen das Produkt, um über eine zertifizierte Schnittstelle auf unsere SAP-Daten zuzugreifen.\n\n"
                        + "Erika Muster.\n\nVertrieb.\n\nEin schönes Produkt für viele Zwecke und Anwender.\n\n## Über uns\n\nText."));
        assertEquals(c.toString(), Set.of("Beispiel AG"), c.keySet());
        // its words are its own use, and what it bought from the provider
        assertEquals(c.toString(), Set.of("customer_of", "internal_use:sap", "purchased_service:sap"), c.get("Beispiel AG"));
    }

    @Test
    public void quotedWordsSignedByAClientBelongToItsCompanyButTheProvidersOwnSignatureNot() throws Exception {
        final Map<String, Set<String>> c = clients(rules("software", "https://www.whiteduck-muster.de/leistungen/platform/",
                "Das sagen unsere Kunden.\n\n## Referenzen.\n\n+.\n\n\"Mit White Duck Muster haben wir einen Partner gefunden, mit dem wir unsere"
                        + " Plattform in der Azure Cloud aufgebaut haben.\".\n\n**Ronny Muster. **Managing Director. ESYON GmbH.\n\n+.\n\n"
                        + "\"Ein verlässlicher Partner für unsere Lösung.\".\n\n**Martin Muster. **Novicon GmbH.\n\n"
                        + "\"Danke für den schönen Workshop, es hat Spaß gemacht.\"\n\n**Stefan.**\n\n"
                        + "\"Wir freuen uns auf Ihr Projekt in der Azure Cloud.\"\n\n**Nico Muster,. **Geschäftsführer | COO,. White Duck Muster GmbH.\n\n"
                        + "## FAQ.\n\nText."));
        assertEquals(c.toString(), Set.of("ESYON GmbH", "Novicon GmbH"), c.keySet());
        assertEquals(c.toString(), Set.of("customer_of", "purchased_service:microsoft-azure"), c.get("ESYON GmbH"));
        assertEquals(c.toString(), Set.of("customer_of"), c.get("Novicon GmbH"));
        assertEquals("ESYON GmbH", BusinessRules.signed("**Ronny Muster. **Managing Director. ESYON GmbH."));
        assertEquals("Huntsman", BusinessRules.signed("Pierre Muster. Leiter Interne Revision, Huntsman."));
        assertEquals(null, BusinessRules.signed("**Stefan.**"));
        assertEquals(null, BusinessRules.signed("Erika Muster. Vertrieb."));
    }

    @Test
    public void aReferenceOfTeamDevelopmentIsAPurchasedProgram() throws Exception {
        final Map<String, Set<String>> c = clients(rules("coaching", "https://www.muster-coaching.de/referenzen/",
                "Referenzen.\n\n### Teamentwicklung bei der Muster Klinik gGmbH.\n\nZwei Workshops mit allen Teams, erfolgreich abgeschlossen."));
        assertEquals(c.toString(), Set.of("customer_of", "purchased_need:team-development"), c.get("Muster Klinik gGmbH"));
    }
}
