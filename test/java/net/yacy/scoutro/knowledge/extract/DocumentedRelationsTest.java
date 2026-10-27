/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    /** The project names of the clients' purchased services and needs. */
    private static Set<String> projects(final Extraction ex) throws Exception {
        final Set<String> out = new java.util.TreeSet<>();
        for (final Claim c : ex.claims()) {
            if (Vocabulary.SYSTEM_SIGNAL.equals(c.predicate) || Vocabulary.BUSINESS_NEED_SIGNAL.equals(c.predicate)) {
                final String project = new JSONObject(c.value).optString("project", "");
                if (!project.isEmpty()) {
                    out.add(project);
                }
            }
        }
        return out;
    }

    /**
     * 0.14.0 (the owner's decision of 27 October 2026): after "bei" in a heading of references only a name is a client, a legal
     * form or a proper name, cut before the words after it; a phrase, a common noun, the reader, a place, a question or a part of a
     * quoted title is none. A heading names clients on a reference page or below a heading of references, nowhere else.
     */
    @Test
    public void afterBeiOnlyANameIsAClient() throws Exception {
        final Map<String, Set<String>> c = clients(rules("software", "https://www.muster-technik.de/referenzen/",
                "## Referenzen.\n\n### E-Mobilität bei Beispielwerk: Neue Ladesäulen für Mitarbeiter und Besucher.\n\nText.\n\n"
                        + "### Dachsanierung bei der Muster Collection in Musterstadt.\n\nText.\n\n"
                        + "### Penetrationstest bei der musto Consulting Group Weiterlesen ».\n\nText.\n\n"
                        + "### Beispiel Leaps setzt bei Machine Learning auf Muster.\n\nText.\n\n"
                        + "### Rasche Hilfe bei kaputtem Musterkessel.\n\nText.\n\n"
                        + "### Widerspruchsrecht bei der zuständigen Musterbehörde.\n\nText.\n\n"
                        + "### Musterdorf bei Musterstadt: Villa mit 2 Wohneinheiten.\n\nText.\n\n"
                        + "### Wurzelschutz bei der Erweiterung einer Bushaltestelle in Musterdorf.\n\nText.\n\n"
                        + "### Wo können wir bei Ihnen ansetzen?\n\nText.\n\n"
                        + "### Brücke bei Musterdorf im Zuge der K 23.\n\nText.\n\n"
                        + "### Schallpegelmessung bei „Immer wieder Musterfest“. Messe in Musterstadt.\n\nText.\n\n"
                        + "### Unterstützung bei Projekten im Bereich Digitalisierung.\n\nText.\n\n"
                        + "### Ein Projekt bei Bedarf.\n\nText."));
        assertEquals(c.toString(), Set.of("Beispielwerk", "Muster Collection", "musto Consulting Group", "Beispiel Leaps"), c.keySet());
        assertEquals(List.of("M.U.S", "musto iT"), BusinessRules.clientsAfterBei("S/4HANA Release-Wechsel", "M.U.S. & musto iT."));
        assertEquals(List.of("Kammer der Musterregion Musterstadt"), BusinessRules.clientsAfterBei("Beratung", "der Kammer der Musterregion Musterstadt, Musterland"));
        assertEquals(List.of("Muster Werke"), BusinessRules.clientsAfterBei("Messung", "„Muster Werke“. Messe in Musterstadt"));
        // below a heading of references on another page; a heading of another section names nobody
        assertEquals(Set.of("Beispielwerk"), clients(rules("software", "https://www.muster-technik.de/", "## Unsere Referenzen\n\n"
                + "### E-Mobilität bei Beispielwerk: Neue Ladesäulen.\n\nText.\n\n## Kontakt\n\n### Beratung bei Musterfirma.\n\nText.")).keySet());
        assertTrue(clients(rules("software", "https://www.muster-technik.de/aktuelles/", "## Neuigkeiten\n\n"
                + "### E-Mobilität bei Beispielwerk: Neue Ladesäulen.\n\nText.")).isEmpty());
    }

    /**
     * The gates of 0.14.0: after "bei", a place by a place written after a comma or "aus", a place with its district, a common noun
     * before a genitive and a word before a participle that ends the heading are no client; a name with a legal form stays one.
     */
    @Test
    public void aPlaceByAPlaceADistrictOrACommonNounAfterBeiIsNoClient() throws Exception {
        final Map<String, Set<String>> c = clients(rules("software", "https://www.muster-landschaft.de/referenzen/",
                "## Referenzen.\n\n### Muster Garten GmbH, Musterdorf bei Musterstadt\n\nText.\n\n"
                        + "### Beispiel Brands AG, Beispieldorf bei Probestadt\n\nText.\n\n"
                        + "### Probe Werbung, Probedorf bei Bad Beispiel\n\nText.\n\n"
                        + "### Umgehung an der A7 bei Musterdorf, Landkreis Beispiel (in Bearbeitung).\n\nText.\n\n"
                        + "### Unterstützung aus Musterdorf bei Musterstadt überzeugen.\n\nText.\n\n"
                        + "### 50% der Kosten bei Musterprojekt eingespart.\n\nText.\n\n"
                        + "### Beratung bei der Anbieterauswahl eines neuen Musterdienstes.\n\nText.\n\n"
                        + "### Kommissionierung bei Muster Schweiz per Stimme.\n\nText.\n\n"
                        + "### Erfolg bei Beispiel AG erreicht.\n\nText."));
        assertEquals(c.toString(), Set.of("Muster Schweiz", "Beispiel AG"), c.keySet());
    }

    /** 0.14.0: one name is never split ("Büro für Muster und Beispiel", "Muster heat & power AG"); several names are. */
    @Test
    public void oneNameIsNeverSplitSeveralNamesAre() throws Exception {
        assertEquals(List.of("Büro für Muster und Beispiel Musterland"), BusinessRules.splitNames("Büro für Muster und Beispiel Musterland"));
        assertEquals(List.of("Ministerium für Muster, Beispiel und Probe"), BusinessRules.splitNames("Ministerium für Muster, Beispiel und Probe"));
        assertEquals(List.of("Muster heat & power AG Musterstadt"), BusinessRules.splitNames("Muster heat & power AG Musterstadt"));
        assertEquals(List.of("Hof & Garten Musterstadt Garten- und Hofverein Musterstadt und Umgebung e.V."),
                BusinessRules.splitNames("Hof & Garten Musterstadt Garten- und Hofverein Musterstadt und Umgebung e.V."));
        assertEquals(List.of("Muster Partner GmbH & Co. KG"), BusinessRules.splitNames("Muster Partner GmbH & Co. KG"));
        assertEquals(List.of("Muster Werke mbH Musterstadt (M.W.)", "musto iT gesellschaft für Informationstechnik mbh"),
                BusinessRules.splitNames("Muster Werke mbH Musterstadt (M.W.) und musto iT gesellschaft für Informationstechnik mbh"));
        assertEquals(List.of("Energie Muster AG", "Stadtwerke Beispiel"), BusinessRules.splitNames("Energie Muster AG & Stadtwerke Beispiel"));
        assertEquals(List.of("Muster", "Beispiel"), BusinessRules.splitNames("Muster und Beispiel"));
        // a former and a current name: two names, the label left out
        assertEquals(Set.of("Muster Bank e.G", "Beispiel Bank"), clients(rules("software", "https://www.muster-bau.de/referenzen/",
                "## Umbau Filiale.\n\nAuftraggeber: Muster Bank e.G. / heute: Beispiel Bank\n\nLeistung: Planung und Bauleitung.")).keySet());
    }

    /**
     * 0.14.0: a client field ends with its name: before an address, "vertreten durch", a field after an asterisk and a sentence
     * end after a name; "Ev.-Luth." goes on.
     */
    @Test
    public void aClientFieldEndsWithItsName() throws Exception {
        assertEquals("Beispielamt Musterstadt", BusinessRules.fieldValue("Beispielamt Musterstadt. Planung, Ausschreibung und Bauleitung"));
        assertEquals("Musterschule Musterstadt", BusinessRules.fieldValue("Musterschule Musterstadt, An der Musterwiese 15, 12345 Musterstadt"));
        assertEquals("Agentur für Muster", BusinessRules.fieldValue("Agentur für Muster Beispielweg 80, 12345 Musterstadt"));
        assertEquals("Agentur für Muster", BusinessRules.fieldValue("Agentur für Muster Beispieler Weg 80, 12345 Musterstadt"));
        assertEquals("Land Musterland", BusinessRules.fieldValue("Land Musterland, vertreten durch das Musteramt, Musterstadt"));
        assertEquals("Musteramt Musterstadt", BusinessRules.fieldValue("Musteramt Musterstadt *Unsere Leistungen:. *Ausführungsplanung"));
        assertEquals("Ev.-Luth. Kirchengemeinde Muster", BusinessRules.fieldValue("Ev.-Luth. Kirchengemeinde Muster"));
        assertEquals(Set.of("Beispielamt Musterstadt"), clients(rules("software", "https://www.muster-bau.de/referenzen/",
                "## Neubau Schule.\n\nAuftraggeber: Beispielamt Musterstadt. Planung, Ausschreibung und Bauleitung\n\nLeistung: Fachplanung.")).keySet());
        // the gates of 0.14.0: the abbreviations of a name go on, a short word ends a sentence, a legal form ends the name, the label
        // of another field and no-break spaces are no part of it
        assertEquals("Evang. Kirchengemeinde Musterdorf", BusinessRules.fieldValue("Evang. Kirchengemeinde Musterdorf"));
        assertEquals("Kath. Pfarramt St. Muster", BusinessRules.fieldValue("Kath. Pfarramt St. Muster"));
        assertEquals("Staatl. Bauamt Musterstadt", BusinessRules.fieldValue("Staatl. Bauamt Musterstadt"));
        assertEquals("Kirchengemeinde vor dem Muster Tor", BusinessRules.fieldValue("Kirchengemeinde vor dem Muster Tor. Planung, Ausschreibung"));
        assertEquals("Muster Technik GmbH & Co. KG", BusinessRules.fieldValue("Muster Technik GmbH & Co. KG. Client-Server-Entwicklung"));
        assertEquals("musto consult Objektgesellschaft mbH", BusinessRules.fieldValue("Bauherr: musto consult Objektgesellschaft mbH"));
        assertEquals("Stadt Musterstadt", BusinessRules.fieldValue("Stadt Musterstadt,\u00a0Postfach 4535,\u00a012345 Musterstadt"));
        assertEquals("Dienstleister für Muster\u00adabrechnungen", BusinessRules.fieldValue("Dienstleister für Muster\u00adabrechnungen. Systemgröße: ca. 500 User"));
        assertEquals(Set.of("M. Muster Elektrotechnik GmbH & Co. KG"), clients(rules("software", "https://www.muster-bau.de/referenzen/",
                "## Neubau Halle.\n\nAuftraggeber: M.\u00a0Muster\u00a0Elektrotechnik\u00a0GmbH\u00a0&\u00a0Co.\u00a0KG\n\nLeistung: Planung.")).keySet());
    }

    /** 0.14.0: a placeholder of a client field is no client ("auf Anfrage", "N.N.", "interner Showcase"). */
    @Test
    public void aPlaceholderIsNoClient() throws Exception {
        assertTrue(clients(rules("software", "https://www.muster-bau.de/referenzen/", "## Neubau Halle.\n\nAuftraggeber: Auf Anfrage.\n\n"
                + "Leistung: Planung.\n\n## Umbau.\n\nAuftraggeber: N.N.\n\nLeistung: Bauleitung.\n\n## Portal.\n\nKunde: Interner Showcase\n\n"
                + "Projekt: Muster-Portal.")).isEmpty());
    }

    /** 0.14.0: a project's name keeps no marks of its markdown. */
    @Test
    public void aProjectNameKeepsNoMarkdown() throws Exception {
        final Extraction ex = rules("software", "https://www.muster-stellwerk.de/referenzen/",
                "## ****Release-Wechsel**** **S/4HANA**\n\nKunde: Beispiel AG\n\nRolle: Projektleitung\n\nSAP S/4HANA, erfolgreich abgeschlossen.");
        assertEquals(Set.of("Release-Wechsel S/4HANA"), projects(ex));
        assertEquals("Migration nach C#", BusinessRules.plain("## **Migration nach C#**"));
    }

    /** The client descriptions of the provider (the page's operator). */
    private static Set<String> descriptions(final Extraction ex) throws Exception {
        final Set<String> out = new java.util.TreeSet<>();
        for (final Claim c : ex.claims()) {
            if (Vocabulary.CLIENT_DESCRIPTION.equals(c.predicate)) {
                final JSONObject v = new JSONObject(c.value);
                out.add(v.getString("description") + "|" + v.optString("project") + "|" + v.optString("products") + "|" + v.optString("state")
                        + "|" + ex.mention(c.subject).ref.equals(BusinessRules.SITE_REF));
            }
        }
        return out;
    }

    /**
     * 0.14.0 (item A1, variant a of the owner's decision of 26 October 2026): a client named by a description is a value of the
     * provider at its project, never an organisation; a public body's name stays a client.
     */
    @Test
    public void aClientNamedByADescriptionIsAValueOfTheProvider() throws Exception {
        final Extraction ex = rules("software", "https://www.muster-stellwerk.de/referenzen/",
                "## Release-Wechsel\n\nKunde: Dienstleister für Musterabrechnungen\n\nProjekt: Wechsel auf SAP S/4HANA\n\nErfolgreich abgeschlossen.\n\n"
                        + "## Reporting\n\nKunde: Stadtverwaltung in Musterland\n\nProjekt: Berichte mit Power BI\n\n"
                        + "## Planung\n\nKunde: Senatsverwaltung für Musterentwicklung\n\nProjekt: Planung mit SAP Analytics Cloud");
        assertEquals(Set.of("Senatsverwaltung für Musterentwicklung"), clients(ex).keySet());
        assertEquals(Set.of("Dienstleister für Musterabrechnungen|Wechsel auf SAP S/4HANA|sap-s4hana|completed|true",
                "Stadtverwaltung in Musterland|Berichte mit Power BI|microsoft-power-bi|unknown|true"), descriptions(ex));
        for (final String d : List.of("Energieversorgungskonzern in Musterland", "Europäische Muster-Terminal- und Beispiel-Gruppe",
                "Global agierendes Muster- und Beispiel-Unternehmen", "Internationaler Anbieter von Muster-Management",
                "Muster- und Beispielfabrik", "Wissenschaftlich-technischer Musterverein", "Weltweit führender Hersteller von Musterteilen für Fenster")) {
            assertTrue(d, BusinessRules.described(d));
        }
        for (final String n : List.of("Institut für Musterkunde", "Verein für Musterforschung", "Bayerische Muster- und Beispielverwaltung",
                "Musterverwaltung des Bundes", "Stadtverwaltung Musterstadt", "Konzern", "Musterverein", "Muster Gruppe", "Beispiel Bank eG")) {
            assertFalse(n, BusinessRules.described(n));
        }
    }
}
