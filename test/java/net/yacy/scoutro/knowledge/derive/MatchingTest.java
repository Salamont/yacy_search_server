/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.derive;

import static org.junit.Assert.*;
import java.sql.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import net.yacy.scoutro.knowledge.*;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.*;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.read.*;
import net.yacy.scoutro.knowledge.store.*;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/** Real SQLite archive/cache/access tests, without crawler or LLM. */
public class MatchingTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private KgStore store;private KgConfig cfg;private KgReader reader;private int serial;private String jobTitle="Engineer";
    private final String provider=KgIds.entityId("organization","test","","provider"),candidate=KgIds.entityId("organization","test","","candidate");
    private final long date=1_700_000_000_000L,now=1_800_000_000_000L;
    private long observationDate=1_700_000_000_000L;
    @Before public void open()throws Exception {
        Map<String,String> settings=new HashMap<>(KgTestSupport.enabled());settings.put(KgConfig.JOBS_COLLECTIONS,"a,b,c");
        cfg=KgTestSupport.config(settings);KgPaths paths=new KgPaths(tmp.getRoot());
        store=KgStore.open(paths,cfg,new StorageGuard(cfg,paths,new KgTestSupport.Probe(),()->now),KgStore.SQLITE,()->now);
        store.write(WriteClass.SYSTEM,0,c->{new Terms().seed(c);return null;});reader=new KgReader(store,cfg,()->now);
        live(provider,"a");live(candidate,"b");
    }
    @After public void close(){store.close();}
    private void live(String actor,String scope)throws Exception {
        store.write(WriteClass.SYSTEM,0,c->{try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO kg_entity(public_id,type,status,created_seq)"
                +" SELECT ?,term_id,1,0 FROM kg_vocab WHERE kind=1 AND name='organization'")){p.setString(1,actor);p.executeUpdate();}
            try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO kg_collection(name) VALUES(?)")){p.setString(1,scope);p.executeUpdate();}
            try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO kg_entity_scope(ent_rowid,coll_id,n) SELECT e.ent_rowid,k.coll_id,1 FROM kg_entity e,kg_collection k WHERE e.public_id=? AND k.name=?")) {
                p.setString(1,actor);p.setString(2,scope);p.executeUpdate();
            }return null;
        });
    }
    private String observation(String actor,String type,String predicate,JSONObject value,String quote,String scope)throws Exception {
        String id="kgo_"+String.format("%020x",++serial),source=String.format("src%09d",serial);
        JSONObject identity=KgJson.obj("organization",new JSONArray().put(KgJson.obj("predicate","name","value",actor.equals(provider)?"Provider":"Candidate")),
                "subject",new JSONArray().put(KgJson.obj("predicate","name","value","service".equals(type)?value.optString("literal"):jobTitle)),
                "assignment",new JSONArray().put(KgJson.obj("predicate","offers","quote","We offer this service","locator","/offers")),
                "employer_assignment","source_declared");
        store.write(WriteClass.GROWTH,0,c->{
            try(PreparedStatement p=c.prepareStatement("INSERT INTO kg_observation(public_id,source_id,source_url,content_revision,subject_id,subject_type,"
                    +"organization_id,original_organization_id,identity_context,predicate,value,quote,locator,tier,extractor,vocabulary_version,"
                    +"observed_at,recorded_at,origin_scopes,source_status,certainty) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1,'jsonld/5','test',?,?,'','removed',1)")) {
                int n=1;for(String s:List.of(id,source,"https://example.org/"+source,"revision",actor,type,actor,actor,identity.toString(),predicate,value.toString(),quote,"/description"))p.setString(n++,s);
                p.setLong(n++,observationDate+serial);p.setLong(n,now);p.executeUpdate();
            }
            try(PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO kg_collection(name) VALUES(?)")){p.setString(1,scope);p.executeUpdate();}
            try(PreparedStatement p=c.prepareStatement("INSERT INTO kg_observation_scope SELECT o.observation_rowid,k.coll_id FROM kg_observation o,kg_collection k WHERE o.public_id=? AND k.name=?")) {
                p.setString(1,id);p.setString(2,scope);p.executeUpdate();
            }return null;
        });return id;
    }
    private String offer(String text)throws Exception{return observation(provider,"service","name",KgJson.obj("literal",text),text,"a");}
    private String signal(String text,String type)throws Exception {
        List<String> values=BusinessSignals.read("system_signal",text,"text",type,"Candidate");assertFalse(text,values.isEmpty());
        String first=null;for(String value:values){String id=observation(candidate,type,"system_signal",new JSONObject(value),text,"b");if(first==null)first=id;}return first;
    }
    private String need(String text)throws Exception {
        List<String> values=BusinessSignals.read("business_need_signal",text,"text","organization","Candidate");assertFalse(text,values.isEmpty());
        return observation(candidate,"organization","business_need_signal",new JSONObject(values.get(0)),text,"b");
    }
    private JSONObject run()throws Exception{return new MatchingService(store,cfg).run(now);}
    private JSONObject page(String... scopes)throws Exception{return new Suggestions(reader).page(provider,0,100,reader.viewer(List.of("a")),reader.viewer(List.of(scopes)));}
    private long count()throws Exception{return store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_match_contribution"));}
    private void withdraw(String id)throws Exception {store.write(WriteClass.MAINTENANCE,0,c->{try(PreparedStatement p=c.prepareStatement("UPDATE kg_observation SET assertion_status='withdrawn' WHERE public_id=?")){p.setString(1,id);p.executeUpdate();}return null;});}
    @Test public void itSupportIsExactProductAndNeedsNoTargetIndustry()throws Exception {
        offer("SAP S/4HANA Beratung");signal("Wir nutzen SAP S/4HANA intern.","job");run();
        assertEquals(1,page("a","b").getInt("total"));assertEquals(0,page("a").getInt("total"));
        assertEquals("internal_use",page("a","b").getJSONArray("items").getJSONObject(0).getJSONArray("contributions").getJSONObject(0).getString("context"));
    }
    @Test public void policyDisableAndReenableReachFeedWithoutNewEvidenceOrCalculation()throws Exception {
        offer("SAP Beratung");signal("Wir nutzen SAP intern.","job");run();
        long archive=store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation"));
        JSONObject before=new KgExport(reader).changes(null,100,true,reader.viewer(List.of("a","b")));
        String cursor=before.getString("next");
        Map<String,String> settings=new HashMap<>(KgTestSupport.enabled());settings.put(KgConfig.JOBS_MATCH_COLLECTIONS,"");
        KgConfig disabled=KgTestSupport.config(settings);
        store.write(WriteClass.SYSTEM,0,c->{MatchingService.invalidatePolicy(c,disabled,now+1);return null;});
        KgReader off=new KgReader(store,disabled,()->now+1);
        JSONObject notices=new KgExport(off).changes(cursor,100,false,off.viewer(List.of("a","b")));
        assertTrue(notices.toString().contains("\"kind\":\"match_contribution\""));assertTrue(notices.toString().contains("\"op\":\"delete\""));
        assertEquals(0,new Suggestions(off).page(provider,0,100,off.viewer(List.of("a")),off.viewer(List.of("a","b"))).getInt("total"));
        assertEquals(archive,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation")));assertEquals(1,count());
        store.write(WriteClass.SYSTEM,0,c->{MatchingService.invalidatePolicy(c,cfg,now+2);return null;});
        JSONObject enabled=new KgExport(reader).changes(notices.getString("next"),100,true,reader.viewer(List.of("a","b")));
        assertTrue(enabled.toString().contains("\"op\":\"upsert\""));assertEquals(1,page("a","b").getInt("total"));
        long seq=store.read(c->KgStore.queryLong(c,"SELECT coalesce(max(seq),0) FROM kg_change"));
        store.write(WriteClass.SYSTEM,0,c->{MatchingService.invalidatePolicy(c,cfg,now+3);return null;});
        assertEquals(seq,(long)store.read(c->KgStore.queryLong(c,"SELECT coalesce(max(seq),0) FROM kg_change")));
    }
    @Test public void genericSapDoesNotConfirmSpecificStack()throws Exception {offer("SAP S/4HANA Beratung");signal("SAP Kenntnisse erforderlich.","job");run();assertEquals(0,count());}
    @Test public void customerProjectsCannotBecomeOwnUse()throws Exception {offer("Salesforce Integration");signal("Sie arbeiten mit Salesforce in Kundenprojekten.","job");run();assertEquals(0,count());}
    @Test public void competenceIsWeakAndCompanyRoleUnknownIsVisible()throws Exception {
        offer("Revit Schulung");signal("Revit Kenntnisse wünschenswert.","job");run();
        JSONObject c=page("a","b").getJSONArray("items").getJSONObject(0).getJSONArray("contributions").getJSONObject(0);
        assertEquals("weak",c.getString("evidence_strength"));assertTrue(c.getJSONArray("uncertainties").toString().contains("company_role_unknown"));
    }
    @Test public void cadCustomerMayOfferArchitectureButArchitectureDoesNotOfferCadSupport()throws Exception {
        offer("Architektur");signal("Wir nutzen Revit intern.","organization");run();assertEquals(0,count());
        offer("Revit Schulung");run();assertEquals(1,page("a","b").getInt("total"));
    }
    @Test public void constructionNeedsOwnResponsibilityLocationAndMatchingPhase()throws Exception {
        offer("Architektur und Genehmigungsplanung");need("Wir planen den Neubau unseres Werkes in Berlin.");run();assertEquals(1,page("a","b").getInt("total"));
    }
    @Test public void foreignOrUnderspecifiedConstructionDoesNotMatch()throws Exception {
        offer("Architektur");need("Wir planen einen Neubau in Berlin.");run();assertEquals(0,count());
    }
    @Test public void energyRenovationRequiresRelevantPlanning()throws Exception {
        offer("Energieberatung");need("Als Gebäudebetreiber planen wir die energetische Sanierung unseres Gebäudes in Berlin.");run();assertEquals(1,count());
    }
    @Test public void generalArchitectureDoesNotProveEnergyExpertise()throws Exception {
        offer("Architektur");need("Als Gebäudebetreiber planen wir die energetische Sanierung unseres Gebäudes in Berlin.");run();assertEquals(0,count());
    }
    @Test public void leadershipAndTeamProgramsMatchSeparateServicesAndContributions()throws Exception {
        offer("Führungscoaching");offer("Teamentwicklung");need("Unser Unternehmen benötigt Führungsentwicklung.");need("Unser Unternehmen benötigt Teamentwicklung.");run();
        JSONObject result=page("a","b");assertEquals(1,result.getInt("total"));assertEquals(2,result.getJSONArray("items").getJSONObject(0).getInt("contributions_total"));
    }
    @Test public void generalCoachTitleAndTeamAbilityAreNotNeedSignals()throws Exception {
        assertTrue(BusinessSignals.read("business_need_signal","Wir suchen einen Coach mit Teamfähigkeit.","text","organization","Candidate").isEmpty());
        offer("Life Coaching");need("Unser Unternehmen benötigt Führungsentwicklung.");run();assertEquals(0,count());
    }
    @Test public void hospitalTransitionIsPartnerAndRequiresAdditionalAuthorizedRegion()throws Exception {
        offer("Ambulante Pflege");need("Unser Krankenhaus koordiniert unser Entlassmanagement für den Übergang in ambulante Versorgung in Berlin.");
        observation(provider,"organization","service_area",KgJson.obj("literal","Berlin"),"Unser Versorgungsgebiet ist Berlin.","c");run();
        assertEquals(0,page("a","b").getInt("total"));JSONObject item=page("a","b","c").getJSONArray("items").getJSONObject(0);
        assertEquals("suggested_partner",item.getString("kind"));assertEquals(3,item.getJSONArray("contributions").getJSONObject(0).getJSONArray("evidence").length());
    }
    @Test public void bareDischargeManagementOrWrongRegionDoesNotMatch()throws Exception {
        offer("Ambulante Pflege");need("Unser Krankenhaus organisiert unser Entlassmanagement.");
        observation(provider,"organization","service_area",KgJson.obj("literal","Hamburg"),"Unser Versorgungsgebiet ist Hamburg.","a");run();assertEquals(0,count());
    }
    @Test public void itOwnJobsAreExcludedButIndependentOrganizationalSignalSurvives()throws Exception {
        offer("SAP Beratung");signal("Wir nutzen SAP intern.","job");signal("Wir nutzen SAP intern.","organization");
        observation(candidate,"organization","business_role_evidence",KgJson.obj("role","it_consultancy","context","own_offered_services"),"Wir bieten SAP-Beratung an.","b");
        run();assertEquals(1,count());assertEquals(1,page("a","b").getInt("total"));
    }
    @Test public void unknownEmployerAndOwnConsultantRecruitmentAreDeferred()throws Exception {
        offer("SAP Beratung");String id=signal("SAP Kenntnisse erforderlich.","job");
        store.write(WriteClass.MAINTENANCE,0,c->{try(Statement s=c.createStatement()){s.execute("UPDATE kg_observation SET organization_id=NULL WHERE public_id='"+id+"'");}return null;});
        run();assertEquals(0,count());
    }
    @Test public void duplicatesAreNotIndependentAndWithdrawalPreservesOtherReason()throws Exception {
        offer("Revit Schulung");String first=signal("Wir nutzen Revit intern.","organization");signal("Wir nutzen Revit intern.","organization");
        String competence=signal("Revit Kenntnisse erwünscht.","job");run();
        assertEquals(2,page("a","b").getJSONArray("items").getJSONObject(0).getInt("contributions_total"));
        withdraw(competence);assertEquals(1,page("a","b").getJSONArray("items").getJSONObject(0).getInt("contributions_total"));
        assertTrue(new ObservationHistory(reader).detail(first,reader.viewer(List.of("b"))).has("quote"));
    }
    @Test public void shutdownAndIdentityCorrectionInvalidateAtReadWithoutDerivation()throws Exception {
        offer("SAP Beratung");String id=signal("Wir nutzen SAP unternehmensweit intern.","organization");run();assertEquals(1,page("a","b").getInt("total"));
        signal("Wir haben SAP unternehmensweit abgeschaltet.","organization");assertEquals(0,page("a","b").getInt("total"));
        assertTrue(new ObservationHistory(reader).detail(id,reader.viewer(List.of("b"))).has("quote"));
    }
    @Test public void budgetAbortsPreserveCacheAndResumeAtCandidateCursor()throws Exception {
        offer("Revit Schulung");signal("Wir nutzen Revit intern.","organization");signal("Revit Kenntnisse erwünscht.","job");run();long old=count();
        JSONObject partial=new MatchingService(store,cfg,1).run(now+1);assertEquals("work_budget",partial.getString("deferred"));assertEquals(old,count());
        for(int i=0;i<8;i++)new MatchingService(store,cfg,1).run(now+2+i);
        assertEquals(old,count());assertEquals(1,page("a","b").getInt("total"));
    }
    @Test public void archiveOnlyCandidatesRemainInGraphAndExportWithoutLiveEvidence()throws Exception {
        offer("Revit Schulung");signal("Wir nutzen Revit intern.","organization");run();
        store.write(WriteClass.MAINTENANCE,0,c->{try(Statement s=c.createStatement()){s.execute("DELETE FROM kg_entity WHERE public_id='"+candidate+"'");}return null;});
        assertEquals(1,page("a","b").getInt("total"));
        BusinessGraph.Query query=new BusinessGraph.Query();query.suggested=true;query.values=false;
        assertTrue(new BusinessGraph(reader).neighborhood(provider,query,reader.viewer(List.of("a")),reader.viewer(List.of("a","b"))).toString().contains(candidate));
        String cursor=null;int matching=0;do {
            JSONObject p=new KgExport(reader).page(cursor,1,true,reader.viewer(List.of("a","b")));
            JSONArray items=p.getJSONArray("items");for(int i=0;i<items.length();i++)if("match_contribution".equals(items.getJSONObject(i).optString("record")))matching++;
            cursor=p.isNull("next")?null:p.getString("next");
        }while(cursor!=null);
        assertEquals(1,matching);
    }
    @Test public void identityCorrectionWithdrawsOnlyTheAffectedContributionImmediately()throws Exception {
        offer("SAP Beratung");String job=signal("SAP Kenntnisse erforderlich.","job");signal("Wir nutzen SAP intern.","organization");run();
        store.write(WriteClass.MAINTENANCE,0,c->{try(PreparedStatement p=c.prepareStatement("UPDATE kg_observation SET organization_id=? WHERE public_id=?")){
            p.setString(1,provider);p.setString(2,job);p.executeUpdate();}return null;});
        assertEquals(1,page("a","b").getJSONArray("items").getJSONObject(0).getInt("contributions_total"));
    }
    @Test public void completedMigrationUpdatesOnlyItsKnownSystemScopeAndKeepsCompetence()throws Exception {
        offer("SAP Beratung");signal("Wir nutzen SAP unternehmensweit intern.","organization");signal("SAP Kenntnisse erforderlich.","job");run();
        signal("Unsere Migration von SAP zu Salesforce ist unternehmensweit abgeschlossen.","organization");
        JSONArray reasons=page("a","b").getJSONArray("items").getJSONObject(0).getJSONArray("contributions");
        assertEquals(1,reasons.length());assertEquals("required_competence",reasons.getJSONObject(0).getString("context"));
    }
    @Test public void unspecifiedShutdownDoesNotRetireOtherSystemScopes()throws Exception {
        offer("SAP Beratung");signal("Wir nutzen SAP intern.","organization");run();signal("Wir haben SAP abgeschaltet.","organization");
        assertEquals(1,page("a","b").getInt("total"));
    }
    @Test public void plannedMigrationIsDatedAndClearlyNotAnInstalledSystem()throws Exception {
        offer("Microsoft Azure Integration");signal("Wir planen eine Migration zu Microsoft Azure.","organization");run();
        JSONObject contribution=page("a","b").getJSONArray("items").getJSONObject(0).getJSONArray("contributions").getJSONObject(0);
        assertEquals("planned_not_reconfirmed",contribution.getString("temporal_status"));assertEquals("weak",contribution.getString("evidence_strength"));
    }
    @Test public void ownCapacityAndSystemIntegratorJobsAreExcludedButCareConsultingIsNot()throws Exception {
        offer("Revit Schulung");jobTitle="Revit Trainer";signal("Revit Kenntnisse erforderlich.","job");jobTitle="Engineer";
        run();assertEquals(0,page("a","b").getInt("total"));
        signal("Wir nutzen Revit intern.","job");run();assertEquals(1,page("a","b").getInt("total"));
        observation(candidate,"organization","business_role_evidence",KgJson.obj("role","care_consultancy","context","own_offered_services"),"Wir bieten Pflegeberatung an.","b");
        assertEquals(1,page("a","b").getInt("total"));
        observation(candidate,"organization","business_role_evidence",KgJson.obj("role","system_integrator","context","own_offered_services"),"Wir bieten Systemintegration an.","b");
        assertEquals(0,page("a","b").getInt("total"));
    }
    @Test public void projectCompletionOnlyRetiresTheIdentifiedProject()throws Exception {
        offer("Architektur");need("Wir planen den Neubau unseres Werkes in Berlin, Projekt Alpha.");need("Wir planen den Neubau unseres Werkes in Hamburg, Projekt Beta.");run();
        need("Der Neubau unseres Werkes in Berlin, Projekt Alpha, ist abgeschlossen.");
        JSONObject item=page("a","b").getJSONArray("items").getJSONObject(0);assertEquals(1,item.getInt("contributions_total"));
        assertEquals("beta",item.getJSONArray("contributions").getJSONObject(0).getString("project"));
    }
    @Test public void lateConstructionAndLeadershipHiringDoNotProvePlanningOrCoachingNeed()throws Exception {
        offer("Architektur");need("Wir planen den Neubau unseres Werkes in Berlin; die Bauarbeiten sind im Bau.");
        assertTrue(BusinessSignals.read("business_need_signal","Wir suchen eine Führungskraft mit Teamfähigkeit.","text","organization","Candidate").isEmpty());
        // The complete, source-grounded statement carries the phase; no inference from neighbouring clauses.
        observation(candidate,"organization","business_need_signal",KgJson.obj("need","new-build","context","planned_need","responsibility","own_responsibility","location","Berlin","phase","construction"),"Unser Neubau in Berlin ist im Bau.","b");
        run();assertEquals(0,count());assertEquals(0,page("a","b").getInt("total"));
    }
    @Test public void hiddenStrongerReasonsNeverDisplaceVisibleOnesOrLeakIntoFeed()throws Exception {
        offer("Revit Schulung");signal("Revit Kenntnisse erwünscht.","job");
        observation(candidate,"organization","system_signal",KgJson.obj("product","revit","context","internal_use"),"Wir nutzen Revit intern.","c");run();
        JSONObject restricted=page("a","b");assertEquals(1,restricted.getInt("total"));assertFalse(restricted.toString().contains("\"c\""));
        assertEquals(1,restricted.getJSONArray("items").getJSONObject(0).getInt("contributions_total"));
        assertEquals("weak",restricted.getJSONArray("items").getJSONObject(0).getJSONArray("contributions").getJSONObject(0).getString("evidence_strength"));
        JSONObject feed=new KgExport(reader).changes(null,100,true,reader.viewer(List.of("a","b")));assertFalse(feed.toString().contains("Wir nutzen Revit intern."));
    }
    @Test public void regionScopeRevocationEmitsOnlyPreviouslyFullyAuthorizedRemoval()throws Exception {
        offer("Ambulante Pflege");need("Unser Krankenhaus koordiniert unser Entlassmanagement für den Übergang in ambulante Versorgung in Berlin.");
        String region=observation(provider,"organization","service_area",KgJson.obj("literal","Berlin"),"Unser Versorgungsgebiet ist Berlin.","c");run();
        JSONObject before=new KgExport(reader).changes(null,100,true,reader.viewer(List.of("a","b","c")));
        assertTrue(before.toString().contains("match_contribution"));
        store.write(WriteClass.MAINTENANCE,0,c->{try(PreparedStatement p=c.prepareStatement("DELETE FROM kg_observation_scope WHERE observation_rowid=(SELECT observation_rowid FROM kg_observation WHERE public_id=?)")){p.setString(1,region);p.executeUpdate();}return null;});
        JSONObject permitted=new KgExport(reader).changes(before.getString("next"),100,false,reader.viewer(List.of("a","b","c")));
        assertTrue(permitted.toString(),permitted.toString().contains("\"delete\""));
        JSONObject neverAllowed=new KgExport(reader).changes(null,100,false,reader.viewer(List.of("a","b")));
        assertFalse(neverAllowed.toString().contains("match_contribution"));
    }
    @Test public void manyLongReasonsHaveValidBoundedReferencesAndCompleteDetailPagination()throws Exception {
        offer("Revit Schulung");for(int i=0;i<31;i++)signal("Wir nutzen Revit intern in Bereich "+i+". "+"a".repeat(700),"organization");run();
        JSONObject item=page("a","b").getJSONArray("items").getJSONObject(0);assertEquals(31,item.getInt("contributions_total"));assertEquals(25,item.getJSONArray("contributions").length());
        JSONObject detail=new Suggestions(reader).contributions(provider,item.getString("id"),25,100,reader.viewer(List.of("a")),reader.viewer(List.of("a","b")));
        assertEquals(6,detail.getJSONArray("items").length());assertTrue(detail.isNull("next_offset"));
        assertEquals(0L,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_match_contribution WHERE NOT json_valid(refs) OR length(refs)>4000")));
    }
    @Test public void backupAndRebuildCarryKeepProposalIdsAndEffectiveCorrections()throws Exception {
        offer("Revit Schulung");String id=signal("Wir nutzen Revit intern.","organization");run();
        String proposal=page("a","b").getJSONArray("items").getJSONObject(0).getString("id");
        java.io.File shadow=new java.io.File(tmp.getRoot(),"shadow.db");store.backupTo(shadow,10_000);
        withdraw(id);java.io.File latest=new java.io.File(tmp.getRoot(),"latest.db");store.backupTo(latest,10_000);
        JSONObject backup=KgBackup.verify(latest,KgSchema.CURRENT_VERSION);assertEquals(1,backup.getJSONObject("counts").getInt("match_contributions"));
        Observations.carryInto(shadow,latest);
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+shadow)) {
            assertEquals(proposal,KgStore.queryString(c,"SELECT proposal_id FROM kg_match_contribution"));
            String contribution=KgStore.queryString(c,"SELECT public_id FROM kg_match_contribution");
            assertNull(MatchingProjection.record(c,contribution,Viewer.ALL,cfg));
            assertEquals("withdrawn",KgStore.queryString(c,"SELECT assertion_status FROM kg_observation WHERE public_id='"+id+"'"));
            assertNull(KgStore.queryString(c,"PRAGMA foreign_key_check"));
        }
    }
    @Test public void matchingPolicyAndZeroBudgetSuppressCacheButKeepHistoricalKnowledge()throws Exception {
        offer("Revit Schulung");String job=signal("Revit Kenntnisse erforderlich.","job");run();assertEquals(1,count());
        Map<String,String> settings=new HashMap<>(KgTestSupport.enabled());settings.put(KgConfig.JOBS_MATCH_COLLECTIONS,"");
        KgConfig off=KgTestSupport.config(settings);KgReader offReader=new KgReader(store,off,()->now);
        assertEquals(0,new Suggestions(offReader).page(provider,0,100,reader.viewer(List.of("a")),reader.viewer(List.of("a","b"))).getInt("total"));
        assertTrue(new ObservationHistory(offReader).detail(job,reader.viewer(List.of("b"))).has("quote"));
        settings.put(KgConfig.MATCHES_MAX,"0");off=KgTestSupport.config(settings);
        assertEquals("configuration_disabled",new MatchingService(store,off).run(now).getString("deferred"));assertEquals(1,count());
    }
    private void postingOn(String signal,String employer)throws Exception {
        String id="kgo_"+String.format("%020x",++serial);
        store.write(WriteClass.GROWTH,0,c->{
            try(PreparedStatement p=c.prepareStatement("INSERT INTO kg_observation(public_id,source_id,source_url,content_revision,subject_id,subject_type,"
                    +"organization_id,original_organization_id,identity_context,predicate,value,quote,locator,tier,extractor,vocabulary_version,observed_at,recorded_at,origin_scopes,source_status,certainty)"
                    +" SELECT ?,source_id,source_url,content_revision,subject_id,'job',?,?,identity_context,'hiring_organization',?,'Source-declared employer is hiring','/hiringOrganization',tier,extractor,vocabulary_version,observed_at,recorded_at,origin_scopes,source_status,certainty"
                    +" FROM kg_observation WHERE public_id=?")) {
                p.setString(1,id);p.setString(2,employer);p.setString(3,employer);p.setString(4,employer==null?"":employer);p.setString(5,signal);p.executeUpdate();
            }
            try(PreparedStatement p=c.prepareStatement("INSERT INTO kg_observation_scope SELECT target.observation_rowid,old.coll_id FROM kg_observation_scope old JOIN kg_observation origin USING(observation_rowid),kg_observation target WHERE origin.public_id=? AND target.public_id=?")) {
                p.setString(1,signal);p.setString(2,id);p.executeUpdate();
            }return null;
        });
    }
    @Test public void companyPassageOnAJobPageCannotBypassJobExclusionOrEmployerGrounding()throws Exception {
        offer("SAP Beratung");String passage=signal("Wir nutzen SAP intern.","organization");postingOn(passage,candidate);run();
        JSONObject reason=page("a","b").getJSONArray("items").getJSONObject(0).getJSONArray("contributions").getJSONObject(0);
        assertEquals(3,reason.getJSONArray("evidence").length());assertEquals("qualified",reason.getString("evidence_strength"));
        observation(candidate,"organization","business_role_evidence",KgJson.obj("role","it_consultancy","context","own_offered_services"),"Wir bieten SAP-Beratung an.","b");
        assertEquals(0,page("a","b").getInt("total"));
        signal("Wir nutzen SAP intern.","organization");run();assertEquals(1,page("a","b").getInt("total")); // independent source
    }
    @Test public void mixedPostingEmployersDeferUnassignedCompanyPassages()throws Exception {
        offer("Revit Schulung");String passage=signal("Wir nutzen Revit intern.","organization");postingOn(passage,candidate);postingOn(passage,provider);
        run();assertEquals(0,count());
    }
    @Test public void unknownPostingEmployerAndCoachRecruitmentCannotBecomeOrganizationalNeed()throws Exception {
        offer("Führungscoaching");String passage=need("Unser Unternehmen benötigt Führungsentwicklung.");postingOn(passage,candidate);run();assertEquals(0,count());
        offer("Revit Schulung");String system=signal("Wir nutzen Revit intern.","organization");postingOn(system,null);run();assertEquals(0,count());
    }
    @Test public void repeatedTiersAndServiceFactsAreNotIndependentConfirmations()throws Exception {
        offer("Revit Schulung");observation(provider,"service","description",KgJson.obj("literal","Wir bieten Revit Schulung an."),"Wir bieten Revit Schulung an.","a");
        String first=signal("Wir nutzen Revit intern.","organization");
        String second="kgo_"+String.format("%020x",++serial);
        store.write(WriteClass.GROWTH,0,c->{try(PreparedStatement p=c.prepareStatement("INSERT INTO kg_observation(public_id,source_id,source_url,content_revision,subject_id,subject_type,organization_id,original_organization_id,identity_context,predicate,value,quote,locator,tier,extractor,vocabulary_version,observed_at,recorded_at,origin_scopes,source_status,certainty)"
                +" SELECT ?,source_id,source_url,content_revision,subject_id,subject_type,organization_id,original_organization_id,identity_context,predicate,value,'Unser Unternehmen verwendet Revit intern.','/another-section',2,'rule/6',vocabulary_version,observed_at,recorded_at,origin_scopes,source_status,certainty FROM kg_observation WHERE public_id=?")){
                    p.setString(1,second);p.setString(2,first);p.executeUpdate();}
            try(PreparedStatement p=c.prepareStatement("INSERT INTO kg_observation_scope SELECT target.observation_rowid,old.coll_id FROM kg_observation_scope old JOIN kg_observation origin USING(observation_rowid),kg_observation target WHERE origin.public_id=? AND target.public_id=?")){
                p.setString(1,first);p.setString(2,second);p.executeUpdate();}return null;});
        run();assertEquals(1,page("a","b").getJSONArray("items").getJSONObject(0).getInt("contributions_total"));
        assertEquals(1,new BusinessGraph(reader).derived("suggested_customer",null,0,100,reader.viewer(List.of("a","b"))).getJSONArray("items").getJSONObject(0).getInt("contributions_total"));
    }
    @Test public void fullDetailsRemainReachableAfterLiveProviderCleanup()throws Exception {
        offer("Revit Schulung");signal("Wir nutzen Revit intern.","organization");run();
        String proposal=page("a","b").getJSONArray("items").getJSONObject(0).getString("id");
        store.write(WriteClass.MAINTENANCE,0,c->{try(Statement s=c.createStatement()){s.execute("DELETE FROM kg_entity WHERE public_id='"+provider+"'");}return null;});
        JSONObject details=new Suggestions(reader).contributions(provider,proposal,0,25,reader.viewer(List.of("a")),reader.viewer(List.of("a","b")));
        assertEquals(1,details.getInt("total"));
        try{new Suggestions(reader).contributions(provider,proposal,0,25,reader.viewer(List.of("b")),reader.viewer(List.of("a","b")));fail("origin archive does not belong to B");}
        catch(KgReader.NotFound expected){}
        try{new Suggestions(reader).contributions(provider,proposal,0,25,reader.viewer(List.of("a")),reader.viewer(List.of("a")));fail("full chain permission required");}
        catch(KgReader.NotFound expected){}
    }
    @Test public void individualPatientCaseCannotGenerateAnOrganizationalCarePartner()throws Exception {
        offer("Ambulante Pflege");need("Unser Krankenhaus koordiniert das Entlassmanagement für Frau Müller für den Übergang in ambulante Versorgung in Berlin.");
        observation(provider,"organization","service_area",KgJson.obj("literal","Berlin"),"Unser Versorgungsgebiet ist Berlin.","a");
        run();assertEquals(0,count());
    }
    @Test public void futureTargetPlanSurvivesShutdownButCompletedTargetMigrationClosesIt()throws Exception {
        offer("Microsoft Azure Integration");signal("Wir planen unternehmensweit eine Migration zu Microsoft Azure am 2028-10-09.","organization");run();
        observationDate=1_750_000_000_000L;
        signal("Wir haben Microsoft Azure unternehmensweit am 2024-10-09 abgeschaltet.","organization");assertEquals(1,page("a","b").getInt("total"));
        signal("Unsere Migration zu Microsoft Azure ist unternehmensweit am 2025-10-09 abgeschlossen.","organization");assertEquals(0,page("a","b").getInt("total"));
    }
}
