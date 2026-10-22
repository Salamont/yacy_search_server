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
    /** The page of the next observations (null: a neutral page of its own); undated: no source date. */
    private String pageUrl=null;private boolean undated=false;
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
                int n=1;for(String s:List.of(id,source,pageUrl!=null?pageUrl:"https://example.org/"+source,"revision",actor,type,actor,actor,identity.toString(),predicate,value.toString(),quote,"/description"))p.setString(n++,s);
                if(undated)p.setNull(n++,Types.INTEGER);else p.setLong(n++,observationDate+serial);
                p.setLong(n,now);p.executeUpdate();
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
    /**
     * Production 0.10.2/0.10.4: a changed matching policy with 87,764 stored contributions made its notices one write with an
     * estimate of 1 KiB per contribution, larger than the WAL limit itself; every maintenance step and every matching run were
     * refused ({@code wal_limit}) however empty the WAL was. The notices go in bounded, resumable batches.
     */
    @Test public void aChangedPolicyWithManyContributionsPublishesItsNoticesInBoundedWrites()throws Exception {
        store.close();
        Map<String,String> settings=new HashMap<>(KgTestSupport.enabled());settings.put(KgConfig.JOBS_COLLECTIONS,"a,b,c");
        settings.put(KgConfig.WAL_MAX_BYTES,String.valueOf(4L*KgTestSupport.MIB));settings.put(KgConfig.WAL_CHECKPOINT_BYTES,String.valueOf(KgTestSupport.MIB));
        cfg=KgTestSupport.config(settings);KgPaths paths=new KgPaths(tmp.getRoot());
        StorageGuard guard=new StorageGuard(cfg,paths,new KgTestSupport.Probe(),()->now);
        store=KgStore.open(paths,cfg,guard,KgStore.SQLITE,()->now);reader=new KgReader(store,cfg,()->now);
        final int n=6000; // 1 KiB each: more than the 4 MiB WAL limit of this test, as 87,764 were more than 64 MiB
        store.write(WriteClass.GROWTH,0,c->{
            try(PreparedStatement p=c.prepareStatement("INSERT INTO kg_match_contribution(public_id,proposal_id,kind,provider_id,candidate_id,rule,"
                    +"rule_version,service_key,signal_key,corroboration_key,refs,computed_at,seen_generation) VALUES(?,?,3,?,?,'it-support','1',?,?,'',"
                    +"'[]',?,'g')")) {
                for(int i=0;i<n;i++) {
                    p.setString(1,KgIds.derivedId("suggested_customer",provider,candidate,"contribution:"+i,"signal"+i));
                    p.setString(2,KgIds.derivedId("suggested_customer",provider,candidate,"matching-v1",""));p.setString(3,provider);
                    p.setString(4,candidate);p.setString(5,"service"+i);p.setString(6,"signal"+i);p.setLong(7,now);p.addBatch();
                }
                p.executeBatch();
            }
            KgStore.putMeta(c,"matching_policy","{\"version\":\"0\"}"); // the policy of an older release
            return null;
        });
        final long before=store.read(c->KgStore.queryLong(c,"SELECT coalesce(max(seq),0) FROM kg_change"));
        final JSONObject result=run();
        assertNull("the matching run is not refused",result.optString("refused",null));
        assertEquals("every contribution got its notice",n,(long)store.read(c->KgStore.queryLong(c,
                "SELECT count(*) FROM kg_change WHERE kind=5 AND seq>"+before)));
        assertEquals("every contribution keeps its access chain",n,(long)store.read(c->KgStore.queryLong(c,"SELECT count(DISTINCT contribution_id) FROM kg_match_access")));
        assertEquals("the policy is recorded once all notices are out",0L,(long)store.read(c->MatchingService.policyEstimate(c,cfg)));
        assertTrue("no write was refused: "+guard.status(),guard.status().optJSONObject("refusedWrites")==null
                ||guard.status().getJSONObject("refusedWrites").length()==0);
        assertTrue("the WAL stayed below its limit",guard.walBytes()<=4L*KgTestSupport.MIB);
    }

    /** The notices of a policy go on from where a stopped publication ended; a policy changed meanwhile starts them again. */
    @Test public void policyNoticesResumeAfterAStopAndRestartForAnotherPolicy()throws Exception {
        final int n=MatchingService.POLICY_BATCH+500;
        store.write(WriteClass.GROWTH,0,c->{
            try(PreparedStatement p=c.prepareStatement("INSERT INTO kg_match_contribution(public_id,proposal_id,kind,provider_id,candidate_id,rule,"
                    +"rule_version,service_key,signal_key,corroboration_key,refs,computed_at,seen_generation) VALUES(?,?,3,?,?,'it-support','1',?,?,'',"
                    +"'[]',?,'g')")) {
                for(int i=0;i<n;i++) {
                    p.setString(1,KgIds.derivedId("suggested_customer",provider,candidate,"contribution:"+i,"signal"+i));
                    p.setString(2,KgIds.derivedId("suggested_customer",provider,candidate,"matching-v1",""));p.setString(3,provider);
                    p.setString(4,candidate);p.setString(5,"service"+i);p.setString(6,"signal"+i);p.setLong(7,now);p.addBatch();
                }
                p.executeBatch();
            }
            KgStore.putMeta(c,"matching_policy","{\"version\":\"0\"}");
            return null;
        });
        final long before=store.read(c->KgStore.queryLong(c,"SELECT coalesce(max(seq),0) FROM kg_change"));
        // one write, then a stop: the position is kept with its policy, the policy is not yet recorded
        assertFalse(MatchingService.invalidatePolicy(store,cfg,now,1));
        assertEquals(MatchingService.POLICY_BATCH,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_change WHERE kind=5 AND seq>"+before)));
        assertTrue(store.read(c->KgStore.getMeta(c,MatchingService.META_POLICY_PENDING)).contains("\"after\""));
        assertTrue(store.read(c->MatchingService.policyEstimate(c,cfg))>0);
        // the next step publishes the rest only
        final long middle=store.read(c->KgStore.queryLong(c,"SELECT coalesce(max(seq),0) FROM kg_change"));
        assertTrue(MatchingService.invalidatePolicy(store,cfg,now,10));
        assertEquals(500L,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_change WHERE kind=5 AND seq>"+middle)));
        assertNull(store.read(c->KgStore.getMeta(c,MatchingService.META_POLICY_PENDING)));
        assertEquals(0L,(long)store.read(c->MatchingService.policyEstimate(c,cfg)));
        // another policy half published, then the recorded one back: nothing to publish, the half publication is dropped
        Map<String,String> settings=new HashMap<>(KgTestSupport.enabled());settings.put(KgConfig.JOBS_MATCH_COLLECTIONS,"");
        KgConfig other=KgTestSupport.config(settings);
        assertFalse(MatchingService.invalidatePolicy(store,other,now+1,1));
        final long back=store.read(c->KgStore.queryLong(c,"SELECT coalesce(max(seq),0) FROM kg_change"));
        assertTrue(MatchingService.invalidatePolicy(store,cfg,now+2,10));
        assertEquals(0L,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_change WHERE kind=5 AND seq>"+back)));
        assertNull(store.read(c->KgStore.getMeta(c,MatchingService.META_POLICY_PENDING)));
        // so the other policy, when it comes again, starts from the first contribution
        assertTrue(MatchingService.invalidatePolicy(store,other,now+3,10));
        assertEquals("all notices for the other policy",(long)n,
                (long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_change WHERE kind=5 AND seq>"+back)));
    }

    /** A hint of a job advertisement read for its employer without a job object (0.10.2, section job_ad_*). */
    private String jobAd(String text)throws Exception {
        List<String> values=BusinessSignals.read("system_signal",text,BusinessSignals.JOB_AD+"description","organization","Candidate");
        assertFalse(text,values.isEmpty());
        return observation(candidate,"organization","system_signal",new JSONObject(values.get(0)),text,"b");
    }
    @Test public void jobAdvertisementHintsAreJobBasedAndFollowTheMatchingPolicy()throws Exception {
        offer("SAP S/4HANA Beratung");jobAd("Wir nutzen SAP S/4HANA intern.");
        Map<String,String> settings=new HashMap<>(KgTestSupport.enabled());settings.put(KgConfig.JOBS_MATCH_COLLECTIONS,"");
        new MatchingService(store,KgTestSupport.config(settings)).run(now);
        assertEquals("matching of stored job signals switched off: no suggestion from an advertisement",0,count());
        run();
        JSONObject c=page("a","b").getJSONArray("items").getJSONObject(0).getJSONArray("contributions").getJSONObject(0);
        assertEquals("internal_use",c.getString("context"));assertEquals("qualified",c.getString("evidence_strength"));
        assertTrue("the company's role stays unknown",c.getJSONArray("uncertainties").toString().contains("company_role_unknown"));
    }
    @Test public void aRequirementOfAnAdvertisementStaysWeakAndClientWorkNeverMatches()throws Exception {
        offer("Revit Schulung");jobAd("Revit Kenntnisse wünschenswert.");run();
        JSONObject c=page("a","b").getJSONArray("items").getJSONObject(0).getJSONArray("contributions").getJSONObject(0);
        assertEquals("desirable_competence",c.getString("context"));assertEquals("weak",c.getString("evidence_strength"));
        offer("Salesforce Integration");jobAd("Sie arbeiten mit Salesforce in Kundenprojekten.");run();
        assertEquals("work in client projects is the client's, no need of the employer",1,page("a","b").getInt("total"));
    }
    @Test public void aCompanysOwnCompetenceStatementIsNoRequirement()throws Exception {
        offer("Revit Schulung");signal("Revit Kenntnisse sind unsere Stärke.","organization");run();assertEquals(0,count());
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
    /** 0.12.0 (T6, round 2): a coach's page about the reader's need is its offer, also as stored by an earlier parser. */
    @Test public void theReadersNeedOnAProvidersPageIsNoNeedOfTheProvider()throws Exception {
        offer("Teamentwicklung");
        String quote="Jedes Team steht vor eigenen Herausforderungen – deshalb setzt die Teamentwicklung von Candidate genau dort an, wo Sie uns brauchen.";
        assertTrue(BusinessSignals.read("business_need_signal",quote,"text","organization","Candidate").isEmpty());
        observation(candidate,"organization","business_need_signal",KgJson.obj("need","team-development","context","explicit_need"),quote,"b");
        run();assertEquals(0,page("a","b").getInt("total"));
        need("Unser Unternehmen benötigt Teamentwicklung.");run();assertEquals(1,page("a","b").getInt("total"));
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
    @Test public void everyReasonNamesItsKindOfEvidence()throws Exception {
        // 0.12.0 (B2): a stated use, a job's requirement, a stated need
        offer("SAP Beratung");signal("Wir nutzen SAP intern.","organization");signal("SAP Kenntnisse erforderlich.","job");run();
        JSONArray reasons=page("a","b").getJSONArray("items").getJSONObject(0).getJSONArray("contributions");
        Set<String> kinds=new TreeSet<>();for(int i=0;i<reasons.length();i++)kinds.add(reasons.getJSONObject(i).getString("evidence_kind"));
        assertEquals(Set.of("use","job_requirement"),kinds);
        assertEquals("need",net.yacy.scoutro.knowledge.read.MatchingProjection.evidenceKind("planned_migration"));
    }
    @Test public void aWebsiteTopicIsNoOfferForTheMatching()throws Exception {
        // 0.11.8: a category the site only names (a topic) never makes a provider; its service does
        observation(provider,"organization","topic",KgJson.obj("literal","software/sap"),"SAP | IT-Beratung | Kontakt","a");
        signal("Wir nutzen SAP intern.","organization");run();assertEquals(0,count());assertEquals(0,page("a","b").getInt("total"));
        offer("SAP Beratung");run();assertEquals(1,page("a","b").getInt("total"));
    }
    @Test public void consultantTitlesWithWordsBetweenAreOwnCapacity()throws Exception {
        // 0.11.8: an SAP consultancy hiring an "SAP Senior Berater" delivers SAP consulting itself; it is no customer.
        offer("SAP Beratung");
        for(String title:List.of("SAP Senior Berater (m/w/d)","Freiberuflicher SAP Consultant","Senior SAP FI/CO-Berater","Revit BIM Trainerin")) {
            jobTitle=title;signal(title.startsWith("Revit")?"Revit Kenntnisse erforderlich.":"SAP Kenntnisse erforderlich.","job");
        }
        jobTitle="Engineer";run();assertEquals(0,page("a","b").getInt("total"));
    }
    @Test public void inhouseKeyUserAndSkilledJobsOfCustomersStayCustomerNeeds()throws Exception {
        // 0.11.8: an industrial firm hiring for its own SAP stays a possible customer.
        offer("SAP Beratung");
        for(String title:List.of("Finanzbuchhalter (SAP-Kenntnisse)","SAP Inhouse Consultant","SAP In-House Berater","SAP Key-User Einkauf")) {
            jobTitle=title;String job=signal("SAP Kenntnisse erforderlich.","job");jobTitle="Engineer";run();
            assertEquals(title,1,page("a","b").getInt("total"));withdraw(job);assertEquals(title,0,page("a","b").getInt("total"));
        }
    }
    /**
     * 0.12.0 gate T6, the customer rows of the plan's table (the competitor row: BusinessViewTest
     * competitorsOfferTheSameCategoryAndTopicsMakeNone). Of an SAP consultancy, an industrial firm hiring a bookkeeper with SAP
     * skills and a firm using SAP S/4HANA are possible customers; a consultancy hiring an SAP Senior Consultant for its own
     * offer is none, yet its explicitly sought support for its own migration makes it one all the same, while it may be a
     * competitor; SAP on a reference page is no need (a topic of the website: aWebsiteTopicIsNoOfferForTheMatching). Every reason
     * names its kind of evidence.
     */
    @Test public void customerRowsOfTheT6Table()throws Exception {
        offer("SAP S/4HANA Beratung");
        jobTitle="Finanzbuchhalter (m/w/d) mit SAP-Kenntnissen";String bookkeeper=signal("Kenntnisse in SAP S/4HANA erforderlich.","job");jobTitle="Engineer";run();
        JSONObject r=reasons("a","b").getJSONObject(0);
        assertEquals("required_competence",r.getString("context"));assertEquals("job_requirement",r.getString("evidence_kind"));withdraw(bookkeeper);
        String use=signal("Wir setzen SAP S/4HANA ein.","organization");run();
        assertEquals("internal_use",reasons("a","b").getJSONObject(0).getString("context"));assertEquals("use",reasons("a","b").getJSONObject(0).getString("evidence_kind"));
        withdraw(use);run();assertEquals(0,page("a","b").getInt("total"));
        // the candidate is a consultancy offering SAP itself, hiring for that offer
        observation(candidate,"organization","business_role_evidence",KgJson.obj("role","it_consultancy","context","own_offered_services"),"Wir bieten SAP-Beratung an.","b");
        signal("Wir bieten SAP S/4HANA Beratung und Implementierung an.","organization");
        jobTitle="SAP Senior Consultant (m/w/d)";signal("Kenntnisse in SAP S/4HANA erforderlich.","job");jobTitle="Engineer";run();
        assertEquals("own capacity is no need",0,page("a","b").getInt("total"));
        pageUrl="https://candidate.example/referenzen/";signal("Wir setzen SAP S/4HANA ein.","organization");pageUrl=null;run();
        assertEquals("a client's system on a reference page",0,page("a","b").getInt("total"));
        signal("Für unsere S/4HANA-Migration suchen wir externe Unterstützung.","organization");run();
        JSONArray all=reasons("a","b");assertEquals(all.toString(),1,all.length());
        assertEquals("planned_migration",all.getJSONObject(0).getString("context"));assertEquals("need",all.getJSONObject(0).getString("evidence_kind"));
    }
    /**
     * 0.12.0 (T6, production sample: 34 of 50 customer suggestions were providers of the product itself): a candidate that
     * offers the same product shows own use only explicitly; its other "use" sentences describe its work for clients.
     */
    @Test public void aProviderOfTheProductItselfIsACustomerOnlyByExplicitOwnUse()throws Exception {
        offer("SAP Beratung");
        signal("Wir bieten SAP Beratung und Implementierung an.","organization");
        String strategy=signal("Wir betreiben eine SAP First-Strategie, aber sind genauso offen für alles andere.","organization");
        run();assertEquals("its strategy is no own use",0,page("a","b").getInt("total"));
        withdraw(strategy);
        signal("Wir nutzen SAP intern für unsere Finanzbuchhaltung.","organization");run();
        assertEquals("internal_use",reasons("a","b").getJSONObject(0).getString("context"));
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
    // ------------------------------------------------ context correction (docs/SCOUTRO_MATCHING.md, "Context correction")

    /** A system signal exactly as the shared parser of 0.9.1 stored it (no re-check by the current parser). */
    private String stored(String quote,String type,String product,String context)throws Exception {
        return observation(candidate,type,"system_signal",KgJson.obj("context",context,"product",product,"scope","unspecified","section","text",
                "system_role","unspecified"),quote,"b");
    }
    /** A cached contribution as MatchingService of 0.9.1 wrote it for one service and one signal. */
    private String cachedContribution(String service,String signal)throws Exception {
        return store.write(WriteClass.GROWTH,0,c->{
            MatchingRules.Observation sv=MatchingRules.Observation.read(c,service),sg=MatchingRules.Observation.read(c,signal);
            MatchingRules.Offer offer=MatchingRules.offers(sv).get(0);
            String key=offer.evidence.id+":"+offer.rule+":"+offer.key+":"+offer.service;
            String proposal=KgIds.derivedId("suggested_customer",offer.evidence.actor,sg.actor,"matching-v1","");
            String id=KgIds.derivedId("suggested_customer",offer.evidence.actor,sg.actor,"contribution:"+key,sg.id);
            JSONArray refs=new JSONArray().put(KgJson.obj("role","service","id",sv.id)).put(KgJson.obj("role","signal","id",sg.id));
            try(PreparedStatement p=c.prepareStatement("INSERT INTO kg_match_contribution(public_id,proposal_id,kind,provider_id,candidate_id,rule,"
                    +"rule_version,service_key,signal_key,corroboration_key,refs,computed_at,seen_generation) VALUES(?,?,3,?,?,?,?,?,?,?,?,?,'0.9.1')")) {
                int n=1;for(String v:List.of(id,proposal,offer.evidence.actor,sg.actor,offer.rule,MatchingRules.VERSION,key,sg.id,MatchingRules.corroboration(offer,sg),refs.toString()))p.setString(n++,v);
                p.setLong(n,now);p.executeUpdate();
            }
            try(PreparedStatement p=c.prepareStatement("INSERT INTO kg_match_ref VALUES(?,?)")) {
                for(String ref:List.of(sv.id,sg.id)){p.setString(1,id);p.setString(2,ref);p.executeUpdate();}
            }
            MatchingAccess.remember(c,id);
            KgChangeLog.record(c,KgChangeLog.Kind.MATCH_CONTRIBUTION,id,KgChangeLog.Op.UPSERT,null,Set.of(),Set.of(),now);
            return id;
        });
    }
    private JSONArray reasons(String... scopes)throws Exception {return page(scopes).getJSONArray("items").getJSONObject(0).getJSONArray("contributions");}

    /**
     * STELLWERK case (Olares 0.9.1): a consultancy's reference page "SAP In-House Cash" (stored internal_use; the product name
     * matched "in-house" and "use") and "Kernkompetenz SAP Rechnungswesen" (stored required_competence) grounded incoming SAP
     * suggestions. Read validation withdraws them at once, the next complete provider partition removes the cache rows with
     * delete notices; an independent own-use reason of the same pair, the observations and their quotes stay.
     */
    @Test public void referencePageAndCoreCompetenceOfAConsultancyNoLongerGroundSuggestions()throws Exception {
        String service=offer("SAP Logistik Beratung");
        pageUrl="https://www.stellwerk.net/unternehmen/";String own=signal("Wir nutzen SAP intern für unsere Buchhaltung.","organization");
        run();String independent=store.read(c->KgStore.queryString(c,"SELECT public_id FROM kg_match_contribution"));
        pageUrl="https://www.stellwerk.net/referenzen/projekt-beispiele/";
        String ihc=stored("SAP In-House Cash","organization","sap","internal_use");
        String core=stored("Kernkompetenz SAP Rechnungswesen","organization","sap","required_competence");
        pageUrl=null;
        String a=cachedContribution(service,ihc),b=cachedContribution(service,core);
        assertEquals("the cache of 0.9.1",3,count());
        // read validation: only the independent reason of the same pair is shown, at once and without a calculation
        JSONObject item=page("a","b").getJSONArray("items").getJSONObject(0);
        assertEquals(1,page("a","b").getInt("total"));assertEquals(1,item.getInt("contributions_total"));
        assertEquals(independent,item.getJSONArray("contributions").getJSONObject(0).getString("id"));
        long seq=store.read(c->KgStore.queryLong(c,"SELECT coalesce(max(seq),0) FROM kg_change"));
        run();
        assertEquals("only the withdrawn cache rows are removed",1,count());
        assertEquals(independent,store.read(c->KgStore.queryString(c,"SELECT public_id FROM kg_match_contribution")));
        // delete notices for exactly the two withdrawn reasons; the independent one is not re-announced
        for(String id:List.of(a,b))assertEquals(id,2L,(long)store.read(c->KgStore.queryLong(c,"SELECT op FROM kg_change WHERE public_id='"+id+"'")));
        assertEquals(0L,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_change WHERE public_id='"+independent+"' AND seq>"+seq)));
        // historical system information and evidence stay unchanged
        for(String id:List.of(ihc,core,own))assertTrue(new ObservationHistory(reader).detail(id,reader.viewer(List.of("b"))).has("quote"));
        assertEquals("internal_use",new ObservationHistory(reader).detail(ihc,reader.viewer(List.of("b"))).getJSONObject("value").getString("context"));
        assertEquals(4L,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation")));
    }
    /** The corrected parser reads none of the two passages as own use or need. */
    @Test public void referencePassagesAreReadAsClientWorkOrStatedCompetence()throws Exception {
        assertTrue(BusinessSignals.read("system_signal","SAP In-House Cash","text","organization","STELLWERK Consulting AG").isEmpty());
        assertEquals("stated_competence",new JSONObject(BusinessSignals.read("system_signal","Kernkompetenz SAP Rechnungswesen","text","organization",
                "STELLWERK Consulting AG").get(0)).getString("context"));
        assertEquals("customer_projects",new JSONObject(BusinessSignals.read("system_signal","Wir nutzen SAP S/4HANA im Rechnungswesen.",
                BusinessSignals.REFERENCES,"organization","STELLWERK Consulting AG").get(0)).getString("context"));
        offer("SAP S/4HANA Beratung");pageUrl="https://www.stellwerk.net/referenzen/projekt-beispiele/";
        stored("Wir nutzen SAP S/4HANA im Rechnungswesen.","organization","sap-s4hana","internal_use"); // even a "we" on a reference page
        run();assertEquals(0,count());assertEquals(0,page("a","b").getInt("total"));
    }
    /** Own offered service or competence of the candidate is no need (eigene Leistung). */
    @Test public void ownOfferedServiceOrCompetenceIsNoNeed()throws Exception {
        offer("SAP Beratung");
        signal("Wir bieten SAP Beratung und Implementierung an.","organization");
        signal("Kernkompetenz SAP Rechnungswesen","organization");
        stored("Unser Team verfügt über langjährige Erfahrung mit SAP.","organization","sap","required_competence");
        run();assertEquals(0,count());assertEquals(0,page("a","b").getInt("total"));
    }
    /** Genuine own use counts, also for a company that offers the product itself; no date is neither current nor ended. */
    @Test public void genuineInternalUseCountsAlsoForAConsultancy()throws Exception {
        offer("SAP S/4HANA Beratung");
        observation(candidate,"organization","business_role_evidence",KgJson.obj("role","it_consultancy","context","own_offered_services"),"Wir bieten SAP-Beratung an.","b");
        signal("Wir bieten SAP S/4HANA Beratung und Implementierung an.","organization");
        undated=true;signal("Wir nutzen SAP S/4HANA intern für unsere Finanzbuchhaltung.","organization");undated=false;
        run();JSONArray reasons=reasons("a","b");assertEquals(1,reasons.length());JSONObject r=reasons.getJSONObject(0);
        assertEquals("internal_use",r.getString("context"));assertEquals("stated",r.getString("evidence_strength"));
        assertEquals("historical_not_reconfirmed",r.getString("temporal_status"));
        assertTrue(r.getJSONArray("uncertainties").toString().contains("observation_date_unknown"));
        assertTrue(r.getJSONArray("uncertainties").toString().contains("external_purchase_or_cooperation_unconfirmed"));
    }
    /** An unclear actor (a product name, or an unnamed "we" next to another company) stays conservative. */
    @Test public void unclearActorAssignmentGroundsNothing()throws Exception {
        assertTrue(BusinessSignals.read("system_signal","Wir unterstützen die Muster Energie AG, die SAP S/4HANA nutzt.","text","organization","Candidate").isEmpty());
        assertEquals("internal_use",new JSONObject(BusinessSignals.read("system_signal","Die Candidate GmbH nutzt SAP S/4HANA intern.","text",
                "organization","Candidate").get(0)).getString("context"));
        offer("SAP S/4HANA Beratung");
        stored("Wir unterstützen die Muster Energie AG, die SAP S/4HANA nutzt.","organization","sap-s4hana","internal_use");
        offer("SAP Beratung");stored("Projektbeispiel: SAP In-House Cash","organization","sap","internal_use");
        run();assertEquals(0,count());assertEquals(0,page("a","b").getInt("total"));
    }
    /** A consultancy's own posting for the product it offers is delivery capacity; its separately evidenced own use stays. */
    @Test public void consultancyPostingForItsOwnOfferIsNoCustomerNeed()throws Exception {
        offer("SAP Beratung");signal("Kenntnisse in SAP Rechnungswesen erforderlich.","job");run();assertEquals(1,count());
        signal("Wir bieten SAP Beratung und Implementierung an.","organization"); // the employer offers SAP itself
        assertEquals("withdrawn on read",0,page("a","b").getInt("total"));
        run();assertEquals(0,count());
        signal("Wir nutzen SAP intern.","organization");run();
        JSONArray reasons=reasons("a","b");assertEquals(1,reasons.length());assertEquals("internal_use",reasons.getJSONObject(0).getString("context"));
    }
    /** The posting of an actual customer (no own offer of the product) stays a weak, visibly uncertain reason. */
    @Test public void postingOfAnActualCustomerStillCounts()throws Exception {
        offer("SAP Beratung");signal("Kenntnisse in SAP Rechnungswesen erforderlich.","job");run();
        JSONObject r=reasons("a","b").getJSONObject(0);
        assertEquals("required_competence",r.getString("context"));assertEquals("weak",r.getString("evidence_strength"));
        assertTrue(r.getJSONArray("uncertainties").toString().contains("company_role_unknown"));
    }
    /** A project on a reference page is a client's, also for the need rules. */
    @Test public void referencePageProjectIsNoOwnNeed()throws Exception {
        offer("Architektur und Genehmigungsplanung");
        JSONObject planned=KgJson.obj("need","new-build","context","planned_need","responsibility","own_responsibility","location","Berlin",
                "phase","planned","scope","unspecified","section","text");
        pageUrl="https://architekt.example/referenzen/";observation(candidate,"organization","business_need_signal",planned,"Wir planen den Neubau unseres Werkes in Berlin.","b");
        run();assertEquals(0,count());
        pageUrl=null;observation(candidate,"organization","business_need_signal",planned,"Wir planen den Neubau unseres Werkes in Berlin.","b");
        run();assertEquals(1,count());
    }
    @Test public void futureTargetPlanSurvivesShutdownButCompletedTargetMigrationClosesIt()throws Exception {
        offer("Microsoft Azure Integration");signal("Wir planen unternehmensweit eine Migration zu Microsoft Azure am 2028-10-09.","organization");run();
        observationDate=1_750_000_000_000L;
        signal("Wir haben Microsoft Azure unternehmensweit am 2024-10-09 abgeschaltet.","organization");assertEquals(1,page("a","b").getInt("total"));
        signal("Unsere Migration zu Microsoft Azure ist unternehmensweit am 2025-10-09 abgeschlossen.","organization");assertEquals(0,page("a","b").getInt("total"));
    }
}
