package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;
import net.yacy.scoutro.knowledge.vocab.Signals;

public class BusinessSignalsTest {
    @Test(timeout=5000) public void longParagraphsAreSkippedWithoutRecursionOrLosingFollowingEvidence() throws Exception {
        Mention actor=new Mention("company",Vocabulary.ORGANIZATION,2);actor.name="Fixture GmbH";
        for(int size:List.of(20000,65536,1048576)) {
            String prefix="a".repeat(size)+". "+"b".repeat(size)+"\n";Extraction e=new Extraction(30);e.add(actor);
            BusinessSignals.extract(prefix+"Wir nutzen SAP intern seit 09.10.2020. Wir nutzen Revit intern.",
                    actor,e,2,Claim.KIND_RULE,"text","text");
            assertEquals(2,e.claims().size());
            Claim first=e.claims().get(0);
            assertEquals("Wir nutzen SAP intern seit 09.10.2020.",first.excerpt);
            assertEquals("2020-10-09",new JSONObject(first.value).getString("asserted_date"));
            assertEquals("text:"+prefix.length(),first.locator);
            assertTrue(e.claims().stream().allMatch(c->c.excerpt.length()<=900));
        }
    }
    @Test public void clauseBoundariesKeepOffsetsDatesAndUnterminatedLineBehavior() throws Exception {
        Mention actor=new Mention("company",Vocabulary.ORGANIZATION,2);Extraction e=new Extraction(30);e.add(actor);
        String prefix="Ignored line without punctuation\n!;  ";
        BusinessSignals.extract(prefix+"Wir nutzen SAP intern seit 09.10.2020.\nWir nutzen Revit intern\nWir nutzen Azure intern.",
                actor,e,2,Claim.KIND_RULE,"text","text");
        assertEquals(2,e.claims().size());
        assertEquals("text:"+(prefix.length()-2),e.claims().get(0).locator);
        assertEquals("Wir nutzen SAP intern seit 09.10.2020.",e.claims().get(0).excerpt);
        assertEquals("2020-10-09",new JSONObject(e.claims().get(0).value).getString("asserted_date"));
        assertFalse(e.claims().stream().anyMatch(c->c.value.contains("revit")));
    }
    @Test public void terminalNewlinesKeepTheExistingEndOfTextContract() throws Exception {
        for(String ending:List.of("","\n","\r\n")) {
            Mention actor=new Mention("company",Vocabulary.ORGANIZATION,2);Extraction e=new Extraction(30);e.add(actor);
            String quote="Wir nutzen SAP intern seit 09.10.2020";
            BusinessSignals.extract(quote+ending,actor,e,2,Claim.KIND_RULE,"text","text");
            assertEquals(1,e.claims().size());assertEquals(quote,e.claims().get(0).excerpt);
            assertEquals("text:0",e.claims().get(0).locator);
            assertEquals("2020-10-09",new JSONObject(e.claims().get(0).value).getString("asserted_date"));
        }
    }
    private List<String> system(String text) {return BusinessSignals.read(Vocabulary.SYSTEM_SIGNAL,text,"text",Vocabulary.JOB,null);}
    private void context(String text,String product,String context) throws Exception {
        List<String> result=system(text);assertEquals(text,1,result.size());JSONObject v=new JSONObject(result.get(0));
        assertEquals(product,v.getString("product"));assertEquals(context,v.getString("context"));
    }
    @Test public void competenceNeverBecomesInternalUse() throws Exception {
        context("Kenntnisse in SAP S/4HANA erforderlich.","sap-s4hana","required_competence");
        context("SAP BW Kenntnisse sind wünschenswert.","sap-bw","desirable_competence");
        context("Wir nutzen SAP S/4HANA intern.","sap-s4hana","internal_use");
        context("Sie arbeiten mit Salesforce in Kundenprojekten.","salesforce","customer_projects");
        context("Wir planen eine Migration zu Microsoft Azure.","microsoft-azure","planned_migration");
        context("Wir haben SAP BW vollständig abgeschaltet.","sap-bw","shutdown");
        context("Unsere Migration zu S/4HANA ist abgeschlossen.","sap-s4hana","completed_migration");
    }
    /**
     * 0.13.0 (test 14 of the plan, BusinessSignals.VERSION 5): a planned migration in the clause of its verb; advice to the reader,
     * a plan of something else beside another party's migration and a product of another clause are none.
     */
    @Test public void aPlannedMigrationInTheClauseOfItsVerb() throws Exception {
        assertEquals("planned_migration",companyContext("Wir planen die Migration auf S/4HANA.","text"));
        assertEquals("planned_migration",companyContext("Wir planen, im kommenden Jahr auf SAP S/4HANA zu migrieren.","text"));
        for(String text:List.of("Wir empfehlen, die Umstellung auf SAP S/4HANA rechtzeitig zu planen.",
                "Wir planen Workshops zur Prozessaufnahme; die Migration auf SAP S/4HANA übernimmt ein Partner.",
                "Wir planen unser Sommerfest – die Migration auf SAP S/4HANA ist Thema eines Vortrags."))
            for(String v:company(text,"text"))assertFalse(text,"planned_migration".equals(new JSONObject(v).getString("context")));
        List<String> one=company("Wir planen die Migration auf SAP S/4HANA – SAP BW bleibt unverändert im Einsatz.","text");
        Set<String> planned=new TreeSet<>();
        for(String v:one)if("planned_migration".equals(new JSONObject(v).getString("context")))planned.add(new JSONObject(v).getString("product"));
        assertEquals(one.toString(),Set.of("sap-s4hana"),planned);
    }
    /** 0.13.0 (item 1): the product tree; a text names every product it states, the general vendor nothing beside one of its products. */
    @Test public void theProductTree() {
        Signals v=KgVocabularies.get().signals;
        assertEquals(Set.of("sap-s4hana","sap-fi"),v.products("SAP S/4HANA FI"));
        assertEquals(Set.of("sap-s4hana"),v.products("SAP S/4HANA"));
        assertEquals(Set.of("sap-fi-aa"),v.products("SAP FI-AA"));
        assertEquals("an alias inside another product's longer one",Set.of("sap-bw4"),v.products("SAP BW/4HANA"));
        assertEquals(Set.of("sap-bw","sap-bw4"),v.products("SAP BW und SAP BW/4HANA"));
        assertEquals("a vendor that is no general product keeps its name",Set.of("salesforce","salesforce-sales-cloud"),v.products("Salesforce Sales Cloud"));
        assertEquals(Set.of("microsoft-dynamics-bc","microsoft-power-bi"),v.products("Dynamics 365 Business Central und Power BI"));
        assertEquals("sap-s4hana",v.parent("sap-fi"));assertEquals("sap",v.parent("sap-s4hana"));
        assertTrue(v.ancestor("sap","sap-fi"));assertFalse(v.ancestor("sap-fi","sap-s4hana"));
        assertEquals(List.of("consulting","implementation","training"),new ArrayList<>(v.serviceTypes("Beratung, Einführung und Schulung zu SAP FI")));
    }
    @Test public void productMentionAndAmbiguousAcronymsAreNotEnough() {
        assertTrue(system("SAP S/4HANA").isEmpty());assertTrue(system("SAPV Kenntnisse erwünscht").isEmpty());
        assertTrue(system("Kenntnisse in SAC erwünscht").isEmpty());assertTrue(system("Wir nutzen SAP nicht.").isEmpty());
        assertTrue(system("Wir verwenden SAP intern und in Kundenprojekten.").isEmpty());
        assertTrue(system("Wir planen keine Migration zu SAP.").isEmpty());
        assertEquals(1,system("Wir planen eine Migration zu SAP in Kundenprojekten.").size());
        assertEquals(Set.of("sap"),KgVocabularies.get().signals.products("SAP"));
        assertEquals(Set.of("sap-sac"),KgVocabularies.get().signals.products("SAP Analytics Cloud"));
    }
    private List<String> company(String text,String section) {return BusinessSignals.read(Vocabulary.SYSTEM_SIGNAL,text,section,Vocabulary.ORGANIZATION,"STELLWERK Consulting AG");}
    private String companyContext(String text,String section) throws Exception {
        List<String> r=company(text,section);assertEquals(text,1,r.size());return new JSONObject(r.get(0)).getString("context");
    }
    /** STELLWERK (Olares 0.9.1): "In-House" of a product name and "use" inside "House" or "cooperate" are no actor and no use. */
    @Test public void markersInsideProductNamesOrOtherWordsAreNoActorOrUse() throws Exception {
        for(String text:List.of("SAP In-House Cash","Projekt-Beispiele: SAP In-House Cash","Einführung von SAP In-House Cash bei einem internationalen Konzern",
                "SAP In-House Banking für einen Energieversorger","We cooperate with SAP partners.","The SAP house style focuses on cloud."))
            assertTrue(text,company(text,"text").isEmpty());
        // explicit own use stays own use, also of the In-House Cash module or "in-house"
        assertEquals("internal_use",companyContext("Wir nutzen SAP In-House Cash intern.","text"));
        assertEquals("internal_use",companyContext("We use SAP in-house.","text"));
        assertEquals("internal_use",companyContext("Wir betreiben SAP in-house.","text"));
    }
    /**
     * 0.12.0 (B2): support sought for an own migration is a need like a planned migration; an offer of such support, a
     * consultant or other recruited title, a client's migration and an unnamed sentence are none.
     */
    @Test public void supportSoughtForAnOwnMigrationIsAPlannedMigration() throws Exception {
        for(String text:List.of("Für unsere S/4HANA-Migration suchen wir externe Unterstützung.","Wir suchen einen Partner für die Migration auf SAP S/4HANA.",
                "Externe Unterstützung für unsere Migration auf SAP S/4HANA gesucht.","We are looking for a partner for our migration to SAP S/4HANA."))
            assertEquals(text,"planned_migration",companyContext(text,"text"));
        for(String text:List.of("Wir unterstützen Sie bei der Migration auf SAP S/4HANA und suchen Partner.","Wir suchen SAP Berater (m/w/d) für Migrationsprojekte.",
                "Für die S/4HANA-Migration unserer Kunden suchen wir Unterstützung.","Externe Unterstützung für die Migration auf SAP S/4HANA gesucht.",
                "Besuchen Sie unseren Partner zur Migration auf SAP S/4HANA.",
                "Wir suchen zur Unterstützung unseres Teams SAP-Berater (m/w/d) für Migrationsprojekte auf SAP S/4HANA."))
            for(String v:company(text,"text"))assertFalse(text,"planned_migration".equals(new JSONObject(v).getString("context")));
    }
    /**
     * 0.12.0 (T6, production sample of 22 October 2026): sentences of providers about their offer, their clients, their
     * partners or others are no own use of the system they name.
     */
    @Test public void aProvidersSentencesAboutItsOfferPartnersAndOthersAreNoOwnUse() throws Exception {
        for(String text:List.of(
                "- Wir verwenden Process-Mining-Tools zur Visualisierung und Analyse der aktuellen Ist-Prozessabläufe aus IT-Systemen wie SAP oder Salesforce auf der Grundlage von Ereignisprotokollen.",
                "Industrieunternehmen betreiben häufig gewachsene IT-Landschaften mit SAP-Systemen, eigenen Rechenzentren und individuellen Anforderungen an Verfügbarkeit.",
                "Bei der Auswahl und Realisierung der bestmöglichen Lösungen für Ihr Unternehmen arbeiten wir direkt mit SAP zusammen.",
                "Making use of a strong framework, having abundant SAP knowledge in house, and delivering a plug and play solution for seamless data streaming integration of SAP.",
                "DELLNER BUBENZER nutzt für seine Prozesse ein PLM-System, welches Daten an das unternehmenseigene SAP ECC ERP sendet.",
                "Seit 2007 realisieren wir professionelle Cloud- und Hostinglösungen – und seit 2010 betreiben wir SAP Business One Umgebungen für Unternehmen jeder Größe.",
                "## Nutzen Sie die Vorteile unseres SAP Supports.",
                "Wir planen, realisieren, warten und betreiben SAP HCM Systeme und sorgen dafür, dass Sie sich auf die inhaltliche HR-Arbeit fokussieren können.",
                "Dann nutzen Sie die Chance und fordern Sie jetzt Ihren Online Termin mit unseren SAP EWM Experten an.",
                "Wir bieten Ihnen den Vergleich zu SAP EWM und TM, erarbeiten mit Ihnen die Roadmap zur Einführung und beraten Sie zum Zusammenspiel mit weiteren Logistiklösungen.",
                "“A challenge as we grow is to use our resources as efficiently as possible and build a sustainable SAP cloud business model,” says Heisig.",
                "Wir zeigen, welche Rolle die SAP BDC spielt und wie Sie diese auch sinnvoll für AI nutzen.",
                "Wir setzen auf eine individuelle Analyse Ihrer Bedürfnisse und integrieren Lösungen, die perfekt mit Ihren bestehenden SAP-Systemen harmonieren.",
                "Wir freuen uns, von nun an seinen reichen Schatz an technischen Erfahrungen mit SAP Technologien und Lösungen nutzen zu können.",
                "Daneben hat das KIT zahlreiche strategische Industriepartner wie ABB, BASF, BOSCH, Daimler, SAP oder Zeiss, die teilweise eigene Programme betreiben.",
                "Wir setzen auf ein für SAP S/4HANA vorkonfiguriertes und modulares Lösungsspektrum.",
                "The KPS Cloud ERP for SAP suits subsidiaries in a two-tier ERP landscape, where the headquarters runs a central SAP ERP platform while subsidiaries operate on a more agile one.",
                "Die OctaVIA AG unterstützt Versorger mit SAP-Systemen, die helfen, Ressourcen optimal zu nutzen."))
            for(String v:company(text,"text"))assertFalse(text+" "+v,"internal_use".equals(new JSONObject(v).getString("context")));
        // own use stays own use, also next to an offer of the same product
        for(String text:List.of("Wir setzen SAP S/4HANA ein.","Wir nutzen SAP S/4HANA intern für unsere Finanzbuchhaltung.","Wir setzen seit 2019 SAP ein, um unsere Logistik zu steuern."))
            assertEquals(text,"internal_use",companyContext(text,"text"));
        assertTrue(BusinessSignals.explicitOwn("Wir nutzen SAP S/4HANA intern für unsere Finanzbuchhaltung."));
        assertFalse(BusinessSignals.explicitOwn("Wir betreiben eine SAP First-Strategie."));
    }
    /**
     * 0.12.0 (T6, round 2, quotes of production): a client's quoted words, a "use case", a list of example technologies, others
     * using the provider's product, a sentence about other companies, the breadth of a competence, "easy to use", "for use" and
     * the reader addressed in English are no own use; a service of a product named as one word ("SAP-Hosting") is an offer;
     * the actor named as the provider of what another uses ("von prismat", "Wir nutzen dab Nexus") is a client's use.
     */
    @Test public void clientsVoicesOthersUseAndServiceWordsAreNoOwnUse() throws Exception {
        for(String text:List.of("„Als junges und modernes Unternehmen setzen wir mit SAP B1 auf eine moderne und zukunftssichere Software.",
                "Gemeinsam sprechen wir 30 Minuten über einen möglichen Use Case und finden heraus, ob dieser ein Fit für das SAP Funding ist.",
                "Wir arbeiten mit führenden RPA-Technologien wie UiPath, Blue Prism und SAP Automation.",
                "SAP LeanIX-Workspaces weltweit nutzen unsere Reports aus dem SAP Extension Hub.",
                "Es genügt ein SAP NetWeaver ab Version 7.4, um das volle Potenzial unseres Tools zu nutzen.",
                "In unserem Webcast zeigen wir, wie Unternehmen SAP-Transformation gezielt nutzen.",
                "Unsere Fokus-Kunden sind Unternehmen, die SAP Lösungen verwenden.",
                "Dabei arbeiten wir mit allen gängigen SAP-Systemen und Tools.",
                "The solution is easy to use and provides SAP-certified access to our data.",
                "We develop SAP applications for use on On-Prem systems.",
                "We implement SAP Signavio as the process layer of your architecture.")) {
            for(String v:company(text,"text"))assertFalse(text+" "+v,"internal_use".equals(new JSONObject(v).getString("context")));
        }
        assertEquals("offered_capability",companyContext("Seit 2007 betreiben wir hochverfügbare Cloudlösungen, seit 2010 SAP-Hosting.","text"));
        for(String[] c:List.of(new String[]{"Die Wurst- und Schinkenmanufaktur Muster nutzt die Application Management Services von prismat als SAP-Support.","prismat"},
                new String[]{"Wir nutzen dab Nexus, um über eine zertifizierte Schnittstelle auf unsere SAP-Daten zuzugreifen.","dab"})) {
            for(String v:BusinessSignals.read(Vocabulary.SYSTEM_SIGNAL,c[0],"text",Vocabulary.ORGANIZATION,c[1]))
                assertFalse(c[0]+" "+v,"internal_use".equals(new JSONObject(v).getString("context")));
            assertTrue(c[0],BusinessSignals.customerVoice(c[0],List.of(c[1])));
        }
        // own use stays own use, also of a provider of the product itself
        for(String text:List.of("Wir nutzen SAP S/4HANA intern.","Wir betreiben eine SAP First-Strategie, aber sind genauso offen für alles andere.",
                "Seit mehr als 25 Jahren nutzen wir Standardfunktionen des zentralen IT-Systems SAP ERP im Verwaltungsbereich."))
            assertEquals(text,"internal_use",companyContext(text,"text"));
        assertTrue("an article reports a project",BusinessSignals.referencePage("https://www.d-labs.com/blog/coaching_von_innovationsteams.html"));
    }
    /** 0.12.0 (T6, round 2): what is said below a heading of customers' voices is said by clients about their own use. */
    @Test public void voicesBelowACustomersHeadingAreTheClientsUse() throws Exception {
        Mention actor=new Mention("company",Vocabulary.ORGANIZATION,2);actor.name="dab";Extraction e=new Extraction(30);e.add(actor);
        BusinessSignals.extract("## Kunden, die unsere Lösungen nutzen.\n\nRudolf Muster.\n\nLeiter Revision, Beispiel AG.\n\n"
                +"Mit der Lösung greifen wir strukturiert auf unsere SAP-Daten zu, die wir intern nutzen.\n\n## Über uns\n\nWir nutzen SAP intern.",
                actor,e,2,Claim.KIND_RULE,"text","text");
        List<String> contexts=new ArrayList<>();for(Claim c:e.claims())contexts.add(new JSONObject(c.value).getString("context"));
        assertEquals(List.of("customer_projects","internal_use"),contexts);
    }
    /** A company's own competence statement is no requirement; a posting's requirement stays one. */
    @Test public void companyCompetenceIsStatedNotRequired() throws Exception {
        assertEquals("stated_competence",companyContext("Kernkompetenz SAP Rechnungswesen","text"));
        assertEquals("stated_competence",companyContext("Unser Team verfügt über langjährige Erfahrung mit SAP.","company"));
        assertEquals("stated_competence",new JSONObject(BusinessSignals.read(Vocabulary.SYSTEM_SIGNAL,"Erfahrung mit SAP wünschenswert.","text",
                Vocabulary.FACILITY,null).get(0)).getString("context"));
        context("Kenntnisse in SAP S/4HANA erforderlich.","sap-s4hana","required_competence");
        context("SAP BW Kenntnisse sind wünschenswert.","sap-bw","desirable_competence");
    }
    /** A reference page reports client work: used, migrated or shut down systems are the client's; offers and competence stay. */
    @Test public void referencePagesReportClientWork() throws Exception {
        for(String url:List.of("https://www.stellwerk.net/referenzen/projekt-beispiele/","https://x.example/referenz/a","https://x.example/kunden/",
                "https://x.example/case-studies/sap","https://x.example/en/references","https://x.example/projektbeispiele.html"))
            assertTrue(url,BusinessSignals.referencePage(url));
        for(String url:List.of("https://x.example/projekte/neubau","https://x.example/fuer-kunden/","https://x.example/ueber-uns/","https://x.example/",
                "https://x.example/kundenservice","not a url with spaces"))
            assertFalse(url,BusinessSignals.referencePage(url));
        assertFalse(BusinessSignals.referencePage(null));
        String refs=BusinessSignals.REFERENCES;
        assertEquals("customer_projects",companyContext("Wir nutzen SAP S/4HANA im Rechnungswesen.",refs));
        assertEquals("customer_projects",companyContext("Unsere Migration von SAP ECC zu SAP S/4HANA ist abgeschlossen.",refs));
        assertEquals("offered_capability",companyContext("Wir bieten SAP Beratung und Implementierung an.",refs));
        assertEquals("stated_competence",companyContext("Kernkompetenz SAP Rechnungswesen",refs));
        assertTrue(company("SAP In-House Cash",refs).isEmpty());
        assertTrue(BusinessSignals.read(Vocabulary.BUSINESS_NEED_SIGNAL,"Wir planen den Neubau unseres Werkes in Berlin.",refs,Vocabulary.ORGANIZATION,null).isEmpty());
        assertFalse(BusinessSignals.read(Vocabulary.BUSINESS_NEED_SIGNAL,"Wir planen den Neubau unseres Werkes in Berlin.","text",Vocabulary.ORGANIZATION,null).isEmpty());
        // the rule tier reads a reference page under that section; the same sentence on another page is own use
        ExtractContext software=new ExtractContext(KgVocabularies.get(),Set.of(),false,List.of("stackfinder-web"));
        String page="Impressum Angaben gemäß § 5 TMG STELLWERK Consulting AG Musterweg 1 50667 Köln Telefon: 0221 1234567. "
                +"Wir nutzen SAP S/4HANA im Rechnungswesen. SAP In-House Cash.";
        for(String[] c:new String[][]{{"https://www.stellwerk.example/referenzen/projekt-beispiele/","references","customer_projects"},
                {"https://www.stellwerk.example/unternehmen/","text","internal_use"}}) {
            Extraction ex=new Extraction(300);
            new RuleExtractor(200,65536).extract(page,c[0],"www.stellwerk.example","de",ex,software,List.of(),List.of());
            List<JSONObject> signals=new ArrayList<>();
            for(Claim claim:ex.claims())if(Vocabulary.SYSTEM_SIGNAL.equals(claim.predicate))signals.add(new JSONObject(claim.value));
            assertEquals(c[0]+": "+signals,1,signals.size());
            assertEquals(c[1],signals.get(0).getString("section"));assertEquals(c[2],signals.get(0).getString("context"));
        }
    }
    /** An unnamed "we" next to another named company leaves the actor unclear; the named actor and a product vendor do not. */
    @Test public void anUnnamedWeNextToAnotherCompanyLeavesTheActorUnclear() throws Exception {
        assertTrue(company("Wir unterstützen die Muster Energie AG, die SAP S/4HANA nutzt.","text").isEmpty());
        assertTrue(company("Für die Muster Pflege gGmbH betreiben wir SAP intern.","text").isEmpty());
        assertEquals("internal_use",companyContext("Die STELLWERK Consulting AG nutzt SAP S/4HANA intern.","text"));
        assertEquals("internal_use",companyContext("Wir nutzen SAP S/4HANA der SAP SE intern.","text"));
        // postings resolve their employer separately: the employer's name in the posting's own sentence is no other actor
        context("Wir, die Muster Energie AG, nutzen SAP S/4HANA intern.","sap-s4hana","internal_use");
    }
    @Test public void assertionDatePreservesPrecisionAndDistinctPassagesKeepTheirQuotes()throws Exception {
        Mention actor=new Mention("j",Vocabulary.JOB,1);Extraction e=new Extraction(30);e.add(actor);
        BusinessSignals.extract("Wir nutzen SAP intern seit 09.10.2020. SAP Kenntnisse erwünscht.",actor,e,1,Claim.KIND_JSONLD,"description","/description");
        assertEquals(2,e.claims().size());
        assertEquals("2020-10-09",new JSONObject(e.claims().get(0).value).getString("asserted_date"));
        assertEquals("desirable_competence",new JSONObject(e.claims().get(1).value).getString("context"));
        assertNotEquals(e.claims().get(0).value,e.claims().get(1).value);
        assertEquals("2020",new JSONObject(system("Wir nutzen SAP intern seit 2020.").get(0)).getString("asserted_date"));
    }
    @Test public void allDomainsHaveControlledOrganizationalNeeds() {
        for(String text:List.of("Wir planen einen Neubau.","Wir planen einen Umbau.","Wir planen eine energetische Sanierung.",
                "Wir planen den Standortausbau.","Unser Unternehmen benötigt Teamentwicklung.","Wir benötigen Führungsentwicklung.",
                "Unser Krankenhaus organisiert unser Entlassmanagement."))
            assertFalse(text,BusinessSignals.read(Vocabulary.BUSINESS_NEED_SIGNAL,text,"text",Vocabulary.ORGANIZATION,null).isEmpty());
        for(String text:List.of("Wir bieten Teamentwicklung an.","Unser Kunde plant einen Neubau.","Frau Beispiel benötigt ambulante Pflege.",
                // 0.12.0 (T6, round 2): the reader's need, as a provider describes it
                "Jedes Team steht vor eigenen Herausforderungen – deshalb setzt unsere Teamentwicklung genau dort an, wo Sie uns brauchen.",
                "Wir richten die Teamentwicklung ganz nach Ihrem Bedarf aus."))
            assertTrue(text,BusinessSignals.read(Vocabulary.BUSINESS_NEED_SIGNAL,text,"text",Vocabulary.ORGANIZATION,null).isEmpty());
    }
    /** 0.12.0 (T6, round 2): the "planned" building needs of the production sample of bauteamcheck were providers' offers. */
    @Test public void aProvidersOfferInTheWordsOfABuildingNeedIsNoNeed() {
        for(String text:List.of("Als Dachdecker- und Zimmereibetrieb aus Olsberg-Elpe planen und realisieren wir ganzheitliche Dachkonzepte – von der energetischen Sanierung bis zum Neubau.",
                "### Wenn Sie einen Neubau, Umbau planen, Gebäudesanierung, einen Beratungstermin mit uns vereinbaren möchten …",
                "Wir planen und bauen deine gesamte Elektrik neu, Altbau wie Neubau.",
                "Neubau, Sanierung oder Erweiterung: Wir planen die Elektrik, verlegen, schließen an, messen und übergeben mit Protokoll.",
                "Neubau, Umbau oder Erweiterung — wir planen die IT mit ein, bevor die Wände stehen.",
                "Wir planen, modernisieren und realisieren Bäder für Neubau, Sanierung und altersgerechtes Wohnen.",
                "Wir organisieren alle Gewerke, die wir für den Umbau benötigen.",
                "Meine Frau und ich planten einen kompletten Umbau unseres Hausflures.",
                "Einen Teil davon durften wir im Zuge der Standorterweiterung planen und umsetzen.",
                "Wir planen (fast) alles: von großen seilverspannten Tragwerken über Tunnelmaßnahmen bis hin zu kleinen Rahmenbauwerken, vom Neubau bis zur historischen Brücke.",
                "Unser Team plant jedes Projekt individuell und setzt den Umbau fachgerecht um."))
            assertTrue(text,BusinessSignals.read(Vocabulary.BUSINESS_NEED_SIGNAL,text,"text",Vocabulary.ORGANIZATION,null).isEmpty());
        // the company's own plan stays, also in the third person of its press release and with a number range
        for(String text:List.of("Die TRILUX Gruppe bekennt sich zum Standort Arnsberg und wir planen mit dem Neubau der Firmenzentrale eine wichtige Investition.",
                "Wir planen den Neubau unseres Werkes in Berlin von 2026 bis zum Herbst 2028.",
                // a hospital's care transition is worded for its patients: read by the matching rule
                "Wir kontaktieren für Sie unsere Pflegefachkräfte der Pflegeüberleitung, falls Sie zu Hause einen ambulanten Pflegedienst benötigen."))
            assertFalse(text,BusinessSignals.read(Vocabulary.BUSINESS_NEED_SIGNAL,text,"text",Vocabulary.ORGANIZATION,null).isEmpty());
        assertFalse(BusinessSignals.read(Vocabulary.BUSINESS_NEED_SIGNAL,"Die Muster AG plant den Neubau ihres Logistikzentrums in Kassel.","text",
                Vocabulary.ORGANIZATION,"Muster AG").isEmpty());
    }
    @Test public void explicitOwnStaffProgramsAndProjectDetailsAreGrounded()throws Exception {
        JSONObject program=new JSONObject(BusinessSignals.read(Vocabulary.BUSINESS_NEED_SIGNAL,
                "Unsere Führungskräfte durchlaufen ein Führungsprogramm.","company",Vocabulary.ORGANIZATION,null).get(0));
        assertEquals("leadership-development",program.getString("need"));assertEquals("explicit_need",program.getString("context"));
        JSONObject project=new JSONObject(BusinessSignals.read(Vocabulary.BUSINESS_NEED_SIGNAL,
                "Als Gebäudebetreiber planen wir die energetische Sanierung unseres Gebäudes in Berlin, Projekt Alpha.","company",Vocabulary.ORGANIZATION,null).get(0));
        assertEquals("own_responsibility",project.getString("responsibility"));assertEquals("Berlin",project.getString("location"));
        assertEquals("alpha",project.getString("project"));assertEquals("planned",project.getString("phase"));
        for(String text:List.of("Wir bieten ein Führungsprogramm für Kunden.","Wir starten kein internes Programm zur Teamentwicklung.","Eine Führungsstelle ist ausgeschrieben."))
            assertTrue(text,BusinessSignals.read(Vocabulary.BUSINESS_NEED_SIGNAL,text,"company",Vocabulary.ORGANIZATION,null).isEmpty());
    }

    @Test public void businessRoleRequiresOwnOfferedItServices() {
        assertFalse(BusinessSignals.read(Vocabulary.BUSINESS_ROLE_EVIDENCE,"Wir bieten SAP-Beratung für Kunden.","company",Vocabulary.ORGANIZATION,null).isEmpty());
        assertFalse(BusinessSignals.read(Vocabulary.BUSINESS_ROLE_EVIDENCE,"Wir bieten Systemintegration an.","company",Vocabulary.ORGANIZATION,null).isEmpty());
        for(String text:List.of("Wir bieten Pflegeberatung und Coaching.","SAP-Beratung GmbH", "IT-Berater gesucht", "SAP Kenntnisse erforderlich"))
            assertTrue(BusinessSignals.read(Vocabulary.BUSINESS_ROLE_EVIDENCE,text,"text",Vocabulary.ORGANIZATION,null).isEmpty());
    }
    @Test public void structuredJobsRemainSeparateAndEmployerMayBeUnresolved() {
        Extraction ex=new Extraction(100);ExtractContext care=new ExtractContext(KgVocabularies.get(),Set.of("care"),true,List.of("edelsenior-web"));
        String json="[{\"@type\":\"JobPosting\",\"title\":\"Engineer A\",\"hiringOrganization\":{\"@type\":\"Organization\",\"name\":\"Industry GmbH\"},"
                +"\"description\":\"Wir nutzen SAP S/4HANA intern.\",\"skills\":\"Salesforce\",\"qualifications\":\"Azure Kenntnisse wünschenswert\"},"
                +"{\"@type\":\"JobPosting\",\"title\":\"Engineer B\",\"hiringOrganization\":{\"@type\":\"Organization\",\"name\":\"Agency GmbH\"},"
                +"\"description\":\"Für unseren Kunden suchen wir. Kenntnisse in Revit erforderlich.\"}]";
        new JsonLdExtractor(500).extract(List.of(json),"https://jobs.example.org/","jobs.example.org","de",ex,care);
        List<Claim> signals=ex.claims().stream().filter(c->c.predicate.equals(Vocabulary.SYSTEM_SIGNAL)).toList();assertEquals(4,signals.size());
        String revit=signals.stream().filter(c->c.value.contains("revit")).findFirst().get().subject;
        assertFalse(ex.claims().stream().anyMatch(c->c.subject.equals(revit)&&c.predicate.equals(Vocabulary.HIRING_ORGANIZATION)));
        assertTrue(ex.claims().stream().anyMatch(c->c.subject.equals(revit)&&c.predicate.equals(Vocabulary.RECRUITING_ORGANIZATION)));
        assertEquals(1,signals.stream().map(c->c.subject).filter(s->!s.equals(revit)).distinct().count());
    }
    @Test public void postingsWithSameTitleAndEmployerHaveSeparateSearchStatusIdentity() {
        Extraction e=new Extraction(100);ExtractContext ctx=new ExtractContext(KgVocabularies.get(),Set.of(),true,List.of("care"));
        String employer="{\"@type\":\"Organization\",\"name\":\"Industry GmbH\"}";
        String json="[{\"@type\":\"JobPosting\",\"title\":\"Engineer\",\"hiringOrganization\":"+employer+",\"description\":\"Wir nutzen SAP intern.\"},"
                +"{\"@type\":\"JobPosting\",\"title\":\"Engineer\",\"hiringOrganization\":"+employer+",\"description\":\"Die Stelle ist besetzt.\"}]";
        new JsonLdExtractor(500).extract(List.of(json),"https://industry.example/jobs","industry.example","de",e,ctx);
        assertEquals(2,e.mentions().stream().filter(m->Vocabulary.JOB.equals(m.type)).map(m->m.jobKey).distinct().count());
    }
    @Test public void llmCannotPromoteBareProductsOrInventNeedsAndApplicationAgreesWithValidation() {
        Mention m=new Mention("company",Vocabulary.ORGANIZATION,1);m.name="Industry GmbH";
        List<LlmExtractor.Known> known=List.of(new LlmExtractor.Known("k1",m));
        String quote="Industry GmbH nutzt SAP intern.";
        String answer="{\"entities\":[],\"claims\":[],\"values\":[{\"subject\":\"k1\",\"predicate\":\"system_signal\",\"quote\":\""+quote+"\"}]}";
        LlmExtractor.Result r=LlmExtractor.validate(answer,new LlmExtractor.Chunk(quote,0),known,Set.of());assertEquals(1,r.values);
        Extraction e=new Extraction(30);LlmExtractor.apply(r.accepted,known,e,ExtractContext.none());assertEquals(1,e.claims().size());
        LlmExtractor.Result bare=LlmExtractor.validate(answer.replace(quote,"SAP"),new LlmExtractor.Chunk("SAP",0),known,Set.of());assertEquals(0,bare.values);
    }

    /**
     * 0.12.2: the current readings of own use and planned migration in stackfinder-web on 24 October 2026 (production, all of
     * them), judged from the provider's view: own use only where the company says it uses the product itself; a provider's
     * offer, a question, others' use, a recommendation to the reader, a use verb with another object, a list of platforms worked
     * with, a slogan or a legal text are none. {quote, product, the company's name (or empty)}.
     */
    private static final String[][] OWN_USE={
            {"Wenn wir Softwarelösung in der Azure Cloud entwickeln verwenden wir übrigens.","microsoft-azure",""}, // toowoxx.de,
            {"Wir nutzen die Salesforce Marketing Cloud für unseren E-Mail Service, für die Bereitstellung von Cloud Pages und zum Anbieten von Formularen, mit welchen Sie mit uns in Kontakt treten können.","salesforce",""}, // rio.cloud,
            {"Wir nutzen Salesforce Sales Cloud zur Verwaltung von Kundendaten.","salesforce",""}, // cloudogu.com,
            {"As our CRM system, we use the service of Salesforce.","salesforce",""}, // www.tradebyte.com,
            {"Seit mehr als 25 Jahren nutzen wir Standardfunktionen des zentralen IT-Systems SAP ERP im Verwaltungsbereich.","sap",""}, // init-software.de,
            {"Wir betreiben eine SAP First-Strategie, aber sind genauso offen für alles andere.","sap",""}, // www.regioit.de,
            {"Zur Bearbeitung und Dokumentation von Anfragen sowie zur Verwaltung von Interessenten, Kunden, Projekten und Geschäftskontakten verwenden wir Salesforce.","salesforce",""}, // www.pluspol-interactive.de,
            {"Zur Verarbeitung der Daten nutzen wir das SAP B1 Integration Framework [B1iF].","sap",""}, // neumeier-edv.de,
            {"Aareon nutzt für Interessenten- und Kundendaten Cloud Dienste von HubSpot sowie Salesforce und intern Microsoft®-Office-Anwendungen (z.","salesforce","Aareon"}, // www.aareon.de,
            {"Wir nutzen zur Kundenbetreuung und zur Verbesserung des Nutzungserlebnisses das CRM Modul / Marketing Cloud der Salesforce.","salesforce",""}, // flowfact.de,
            {"Wir nutzen dazu das Analyse-Tool Pardot der salesforce.","salesforce",""}, // www.service1.com,
            {"Bei der Nutzung einer Mietlizenz verwenden wir für die Identitätsprüfung Azure Active Directory B2C (Azure AD B2C), einen Dienst von Microsoft, der uns den Identitätsnachweis der Nutzer unserer Anwendungen ermöglicht.","microsoft-azure",""}, // valentin-software.com,
            {"In unserer Software GeoT*SOL und PV*SOL verwenden wir eine Microsoft Azure-Datenbank um anonyme Nutzungsdaten zu erfassen.","microsoft-azure",""}, // valentin-software.com,
            {"We use Salesforce Sales Cloud to manage customer data.","salesforce",""}, // www.vernaio.com,
            {"We use the CRM system Salesforce (Salesforce.","salesforce",""}  // www.iris-sensing.com
    };
    private static final String[][] NO_OWN_USE={
            {"Wir entwickeln, modernisieren und betreiben Cloud- und KI-Lösungen auf Microsoft Azure – von der ersten Idee bis zum produktiven Betrieb.","microsoft-azure",""}, // whiteduck.de,
            {"Wir empfehlen die Umstellung im SAP HCM rechtzeitig einzuplanen, um genügend Zeit für Prüfungen und Tests zu haben!","sap",""}, // www.novamushr01.com,
            {"Statt neue Komplexität hinzuzufügen, entfernen wir überflüssige Anpassungen und nutzen, was SAP bereits bietet.","sap",""}, // www.placeworkers.com,
            {"Je nach Datenschutz- und Performance-Anforderungen nutzen wir Cloud-Modelle wie Azure OpenAI oder Anthropic Claude.","microsoft-azure",""}, // octavia.de,
            {"Wir sind systemunabhängig und arbeiten mit Shopify, Shopware, WooCommerce, Magento und Salesforce Commerce Cloud – inklusive der jeweils plattformspezifischen SEO-Optimierung.","salesforce",""}, // www.seo-kueche.de,
            {"Unsere Lösungen basieren auf SharePoint Online und nutzen zudem weitere Microsoft Anwendungen und Services, wie zum Beispiel Entra ID (ehemals Azure Active Directory).","microsoft-azure",""}, // www.d-velop.de,
            {"Azure ist die Plattform darunter, auf der Unternehmen eigene Server, Datenbanken und Anwendungen betreiben - mit mehreren hundert einzelnen Diensten.","microsoft-azure",""}, // datadiorama.com,
            {"Azure ist die Cloud-Plattform darunter, auf der Unternehmen eigene Server, Datenbanken und Anwendungen betreiben.","microsoft-azure",""}, // datadiorama.com,
            {"je stärker eine Umgebung Azure-eigene Spezialdienste nutzt, desto aufwendiger wird der Umzug.","microsoft-azure",""}, // datadiorama.com,
            {"3.6 Die bestimmungsgemäße Nutzung der Software besteht darin, die SAP-Softwareanwendungen des eigenen Unternehmens zu testen und zu analysieren, um unsichere Konfigurationen und/oder Berechtigungen (oder in anderen Sicherheits- und Compliance-relevanten Bereichen) in diesen Softwareanwendungen zu finden, sowie darin, die Befunde und alle anderen Resultate des Testens und der Analyse zur Verbesserung solcher Konfigurationen und Berechtigungen zu verwenden.","sap",""}, // www.scdsoft.de,
            {"10KBLAZE is the name that Onapsis uses to refer to a set of publicly-released SAP exploits.","sap","Onapsis"}, // onapsis.com,
            {"We provide tasks for managing clusters, running jobs, and monitoring activity, ensuring predictable Azure spend and enterprise-grade SLA protection for critical data pipelines.","microsoft-azure",""}, // www.betasystems.com,
            {"We develop, modernize and operate cloud and AI solutions on Microsoft Azure – from the initial idea to productive operation.","microsoft-azure",""}, // whiteduck.de,
            {"Für SAP-HCM-Bestandskunden entwickeln wir belastbare Migrationsszenarien, die technische End-of-Life-Risiken absichern und gleichzeitig die Chance nutzen, HR-Prozesse zu harmonisieren und für die Cloud-Welt neu zu denken.","sap",""}, // www.spotconsulting.de,
            {"Zahlreiche Energieversorger arbeiten erfolgreich mit unserer Lösung enventa Receivables Energy in SAP®.","sap",""}, // www.enventa-group.com,
            {"Salesforce verwendet nach eigenen Angaben unter anderem das EU-US Data Privacy Framework sowie Standardvertragsklauseln und weitere vertragliche Schutzmechanismen.","salesforce",""}, // www.pluspol-interactive.de,
            {"### SAP is running, we want more.","sap",""}, // hpc.de,
            {"Wir planen, migrieren und betreiben Cloud-Infrastrukturen auf AWS, Microsoft Azure, Google Cloud und STACKIT, DevOps-Plattformen und Kubernetes – für Mittelstand und Konzerne in Bayern, Deutschland und der DACH-Region.","microsoft-azure",""}, // www.ventx.de,
            {"Unser Angebot richtet sich an Teams, die Azure-Anwendungen produktiv betreiben:.","microsoft-azure",""}, // whiteduck.de,
            {"### Do we need to run SAP to use the Digital Euro Hub?","sap",""}, // www.sapfioneer.com,
            {"We don’t use Microsoft Azure as a standard solution, but as a strategic tool to tackle complex challenges securely, scalably, and economically.","microsoft-azure",""}, // devdeer.com,
            {"Im Blick bleibt dabei stets der wirtschaftliche Nutzen und wir beraten in Fragen Kosten und Nutzen im Rahmen einer SAP-Einführung.","sap",""}, // www.si-pro.com,
            {"In den kommenden Stufen lässt sich der Code flexibel in Cloud-Umgebungen (SAP Analytics Cloud oder Microsoft Azure), auf intern gehosteten Servern oder über die PAL on premise im SAP-HANA-Kern nutzen;","microsoft-azure",""}, // marmeladenbaum.de,
            {"In den kommenden Stufen lässt sich der Code flexibel in Cloud-Umgebungen (SAP Analytics Cloud oder Microsoft Azure), auf intern gehosteten Servern oder über die PAL on premise im SAP-HANA-Kern nutzen;","sap-sac",""}, // marmeladenbaum.de,
            {"Unser Angebot richtet sich an Teams, die Azure als strategische Plattform nutzen:.","microsoft-azure",""}, // whiteduck.de,
            {"Für SAP-Anwender in Finanzbuchhaltung & Controlling stellt sich die Frage: Was müssen wir jetzt im System umsetzen, um diese Chancen zu nutzen?","sap","STELLWERK Consulting AG"}  // stellwerk.net
    };
    /** The contexts of own use or a plan the reader gives the product, unnamed and with the company's name (as the matching reads). */
    private static Set<String> ownContexts(String quote,String product,String name) throws Exception {
        Set<String> out=new TreeSet<>();
        for(String actor:Arrays.asList(null,name.isEmpty()?null:name))
            for(String v:BusinessSignals.read(Vocabulary.SYSTEM_SIGNAL,quote,"text",Vocabulary.ORGANIZATION,actor)) {
                JSONObject o=new JSONObject(v);
                if(product.equals(o.getString("product"))&&Set.of("internal_use","planned_migration").contains(o.getString("context")))out.add(o.getString("context"));
            }
        return out;
    }
    @Test public void ownUseOfTheProductionReadings() throws Exception {
        List<String> missed=new ArrayList<>(),wrong=new ArrayList<>();
        for(String[] c:OWN_USE)if(!ownContexts(c[0],c[1],c[2]).contains("internal_use"))missed.add(c[0]);
        for(String[] c:NO_OWN_USE)if(!ownContexts(c[0],c[1],c[2]).isEmpty())wrong.add(c[0]);
        assertTrue("own use missed: "+missed,missed.isEmpty());
        assertTrue("own use or plan read where there is none: "+wrong,wrong.isEmpty());
        // a client's voice on its provider's page (dab): the client's own SAP, never the provider's (the matching's customerVoice)
        String voice="Wir nutzen dab Nexus, um über eine zertifizierte Schnittstelle strukturiert auf unsere SAP-Daten zuzugreifen.";
        assertTrue(BusinessSignals.customerVoice(voice,List.of("dab")));
        assertTrue(ownContexts(voice,"sap","").contains("internal_use"));
    }
    /**
     * 0.14.0 (items A2 and A4): a provider's own offer is no use ("die wir migrieren", "wir nutzen die passenden …"), a product
     * named as what an offer replaces is neither used nor offered ("Alternative zu …"), and a reader's line under an FAQ is no use.
     */
    @Test public void anOwnOfferAnAlternativeAndTheReadersLineAreNoUse() throws Exception {
        for(String quote:List.of("Wir betreiben Microsoft 365 und Azure, die wir migrieren, betreuen und absichern.",
                "Wir setzen die passenden DATEV-Module für Ihre Buchhaltung ein.", "Wir nutzen eine Alternative zu Microsoft 365.",
                "Unsere Plattform ist die Alternative zu Salesforce, die wir intern nutzen.")) {
            assertTrue(quote,ownContexts(quote,"microsoft-365",quote.contains("DATEV")?"":"").isEmpty()&&ownContexts(quote,"datev","").isEmpty()
                    &&ownContexts(quote,"salesforce","").isEmpty());
        }
        Set<String> offered=new TreeSet<>();
        for(String v:BusinessSignals.read(Vocabulary.SYSTEM_SIGNAL,"Wir betreiben Microsoft 365, die wir migrieren, betreuen und absichern.","text",
                Vocabulary.ORGANIZATION,null))offered.add(new JSONObject(v).getString("context"));
        assertEquals(Set.of("offered_capability"),offered);
        assertTrue(BusinessSignals.read(Vocabulary.SYSTEM_SIGNAL,"Wir bieten eine Alternative zu Salesforce an.","text",Vocabulary.ORGANIZATION,null).isEmpty());
        // an own plan stays one
        assertEquals(Set.of("planned_migration"),ownContexts("Wir planen die Migration auf Microsoft Azure, die wir 2027 umsetzen werden.","microsoft-azure",""));
        // the reader's line below an FAQ heading
        Mention actor=new Mention("company",Vocabulary.ORGANIZATION,2);actor.name="Muster IT GmbH";
        Extraction e=new Extraction(30);e.add(actor);
        BusinessSignals.extract("## Häufige Fragen\n\nWir arbeiten schon mit Office 365.\n\nDann migrieren wir Ihre Postfächer.\n\n## Über uns\n\n"
                + "Wir nutzen SAP S/4HANA intern.",actor,e,2,Claim.KIND_RULE,"text","text");
        Set<String> found=new TreeSet<>();
        for(Claim c:e.claims())found.add(new JSONObject(c.value).getString("product")+":"+new JSONObject(c.value).getString("context"));
        assertEquals("the reader's Office 365 under the FAQ is no use; the company's own SAP below another heading is",
                Set.of("sap-s4hana:internal_use"),found);
    }
}
