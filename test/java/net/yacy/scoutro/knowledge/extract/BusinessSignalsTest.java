package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

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
}
