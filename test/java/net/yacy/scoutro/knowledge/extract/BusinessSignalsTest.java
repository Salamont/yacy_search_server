package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

public class BusinessSignalsTest {
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
        assertEquals(Set.of("sap"),KgVocabularies.get().signals.products("SAP"));
        assertEquals(Set.of("sap-sac"),KgVocabularies.get().signals.products("SAP Analytics Cloud"));
    }
    @Test public void allDomainsHaveControlledOrganizationalNeeds() {
        for(String text:List.of("Wir planen einen Neubau.","Wir planen einen Umbau.","Wir planen eine energetische Sanierung.",
                "Wir planen den Standortausbau.","Unser Unternehmen benötigt Teamentwicklung.","Wir benötigen Führungsentwicklung.",
                "Unser Krankenhaus organisiert unser Entlassmanagement."))
            assertFalse(text,BusinessSignals.read(Vocabulary.BUSINESS_NEED_SIGNAL,text,"text",Vocabulary.ORGANIZATION,null).isEmpty());
        for(String text:List.of("Wir bieten Teamentwicklung an.","Unser Kunde plant einen Neubau.","Frau Beispiel benötigt ambulante Pflege."))
            assertTrue(text,BusinessSignals.read(Vocabulary.BUSINESS_NEED_SIGNAL,text,"text",Vocabulary.ORGANIZATION,null).isEmpty());
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
