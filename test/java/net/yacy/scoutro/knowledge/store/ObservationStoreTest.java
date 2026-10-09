package net.yacy.scoutro.knowledge.store;

import static org.junit.Assert.*;
import java.sql.Statement;
import java.util.List;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import net.yacy.scoutro.knowledge.*;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.*;
import net.yacy.scoutro.knowledge.publish.*;
import net.yacy.scoutro.knowledge.read.*;
import org.json.*;

/** Real SQLite lifecycle tests; the live graph remains disposable. */
public class ObservationStoreTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private KgStore store;
    private KgConfig cfg;
    private KgPaths paths;
    private Terms terms;
    private Publisher publisher;
    private Publisher.Doc doc;
    private final long observed=1_700_000_000_000L;
    private long processed=1_800_000_000_000L;

    @Before public void open() throws Exception {
        cfg=KgTestSupport.config(KgTestSupport.enabled()); paths=new KgPaths(tmp.getRoot());
        store=KgStore.open(paths,cfg,new StorageGuard(cfg,paths,new KgTestSupport.Probe(),()->processed),KgStore.SQLITE,()->processed);
        terms=new Terms(); store.write(WriteClass.SYSTEM,0,c->{terms.seed(c);return null;}); publisher=new Publisher(cfg,terms);
        doc=new Publisher.Doc(); doc.docId="AAAAAAhost01";doc.url="https://example.org/jobs";doc.host="example.org";
        doc.hostId="host01";doc.state=1;doc.token=new byte[8];doc.inputHash=new byte[16];doc.contentHash=new byte[16];
        doc.collections=List.of("a");doc.loadedAt=observed;
    }
    @After public void close() {store.close();}
    private Extraction job() {
        Extraction e=new Extraction(30); e.ranTier(1);
        Mention j=e.add(new Mention("j",Vocabulary.JOB,1));j.name="SAP engineer";j.jobKey="employer|engineer";
        Mention o=e.add(new Mention("o",Vocabulary.ORGANIZATION,1));o.name="Industry GmbH";
        e.add(new Claim("j",Vocabulary.NAME,null,j.name,1,1,false,"/title",j.name));
        e.add(new Claim("o",Vocabulary.NAME,null,o.name,1,1,false,"/employer/name",o.name));
        e.add(new Claim("j",Vocabulary.HIRING_ORGANIZATION,"o",null,1,1,false,"/employer","Industry GmbH is hiring"));
        return e;
    }
    private void publish(Extraction ex) throws Exception {
        Publisher.Row row=store.read(c->Publisher.row(c,doc.docId));doc.solrVersion++;
        store.write(WriteClass.GROWTH,0,c->publisher.apply(c,doc,row==null?-1:row.generation,ex,processed));
    }
    private long count(String sql) throws Exception {return store.read(c->KgStore.queryLong(c,sql));}
    private Extraction system(String text) {
        Extraction e=job();BusinessSignals.extract(text,e.mention("j"),e,1,Claim.KIND_JSONLD,"description","/description");return e;
    }
    private ObservationHistory history(){return new ObservationHistory(new KgReader(store,cfg,()->processed));}
    private KgChangeLog.Viewer viewer(String name)throws Exception{return new KgReader(store,cfg,()->processed).viewer(List.of(name));}
    private String systemId()throws Exception{return store.read(c->{try(Statement s=c.createStatement();java.sql.ResultSet r=s.executeQuery(
            "SELECT public_id FROM kg_observation WHERE predicate='system_signal' ORDER BY observation_rowid LIMIT 1")){assertTrue(r.next());return r.getString(1);}});}

    @Test public void archivalDetailRemainsReachableAndPermissionsUseCurrentClassification()throws Exception {
        publish(system("Wir nutzen SAP intern."));String id=systemId();
        JSONObject original=history().detail(id,viewer("a"));assertEquals("unknown",original.getJSONObject("job_search").getString("status"));
        assertEquals("internal_use",original.getJSONObject("value").getString("context"));
        doc.collections=List.of("b");publish(null);
        try {history().detail(id,viewer("a"));fail("old scope must not grant access");}catch(KgReader.NotFound expected){}
        store.write(WriteClass.MAINTENANCE,0,c->publisher.remove(c,List.of(doc.docId),null,processed));
        JSONObject archived=history().detail(id,viewer("b"));assertTrue(archived.isNull("live_statement"));
        assertEquals("removed",archived.getJSONObject("source").getString("status"));assertEquals("unknown",archived.getJSONObject("job_search").getString("status"));
        assertEquals(0,archived.getJSONArray("origin_collections").length());
        assertEquals(original.getString("observed_at"),archived.getString("observed_at"));assertEquals("Wir nutzen SAP intern.",archived.getString("quote"));
    }
    @Test public void confirmedJobEndDoesNotChangeCompetenceOrInternalSystemStatements()throws Exception {
        publish(system("Kenntnisse in SAP erforderlich. Wir nutzen Salesforce intern."));
        doc.loadedAt=observed+86_400_000;doc.contentHash[0]=3;publish(system("Die Stelle ist besetzt."));
        JSONArray items=history().page(null,null,0,100,viewer("a")).getJSONArray("items");int signals=0;
        for(int i=0;i<items.length();i++){JSONObject o=items.getJSONObject(i);if(!"system_signal".equals(o.getString("predicate")))continue;
            signals++;assertEquals("ended",o.getJSONObject("job_search").getString("status"));assertEquals("recorded",o.getString("assertion_status"));}
        assertEquals(2,signals);
    }
    @Test public void fullHistoryExportIncludesEveryEventAndDoesNotRevealHiddenRecords()throws Exception {
        publish(system("Wir nutzen SAP intern."));String id=systemId();
        for(int i=0;i<35;i++){final int n=i;store.write(WriteClass.MAINTENANCE,0,c->{try(Statement s=c.createStatement()){
            s.execute("UPDATE kg_observation SET source_status='"+(n%2==0?"unavailable":"available")+"'");}return null;});}
        java.util.Set<String> exported=new java.util.HashSet<>();long events=0;String cursor=null;int pages=0;
        do {JSONObject page=history().export(cursor,2,viewer("a"));assertEquals(ObservationHistory.SCHEMA,page.getString("schema"));
            JSONArray items=page.getJSONArray("items");for(int i=0;i<items.length();i++){JSONObject o=items.getJSONObject(i);
                if("observation".equals(o.getString("record")))assertTrue(exported.add(o.getString("id")));else events++;}
            cursor=page.isNull("next")?null:page.getString("next");assertEquals(cursor==null,page.getBoolean("complete"));assertTrue(++pages<100);
        }while(cursor!=null);
        assertTrue(exported.contains(id));assertEquals(count("SELECT count(*) FROM kg_observation"),exported.size());
        assertEquals(count("SELECT count(*) FROM kg_observation_event"),events);assertTrue(events>20);
        assertEquals(0,history().page(null,null,0,100,viewer("unknown")).getJSONArray("items").length());
    }
    @Test public void laterShutdownOnlyUpdatesKnownSystemScopeAndNotCompetence()throws Exception {
        publish(system("Wir nutzen SAP unternehmensweit intern. SAP Kenntnisse erforderlich."));
        String useId=store.read(c->{try(Statement s=c.createStatement();java.sql.ResultSet r=s.executeQuery("SELECT public_id FROM kg_observation"
                +" WHERE json_valid(value) AND json_extract(value,'$.context')='internal_use'")){r.next();return r.getString(1);}});
        doc.loadedAt=observed+86_400_000;doc.contentHash[0]=4;publish(system("Wir haben SAP abgeschaltet."));
        assertFalse(history().detail(useId,viewer("a")).has("later_system_change"));
        doc.loadedAt+=86_400_000;doc.contentHash[0]=5;publish(system("Wir haben SAP unternehmensweit abgeschaltet."));
        assertTrue(history().detail(useId,viewer("a")).has("later_system_change"));
        JSONArray items=history().page(null,null,0,100,viewer("a")).getJSONArray("items");
        for(int i=0;i<items.length();i++){JSONObject o=items.getJSONObject(i);if(o.optJSONObject("value")!=null
                &&"required_competence".equals(o.getJSONObject("value").optString("context")))assertFalse(o.has("later_system_change"));}
    }
    @Test public void identityMergeUpdatesCurrentAssignmentAndPreservesOriginalIdentityEvidence()throws Exception {
        publish(system("Wir nutzen SAP intern."));String id=systemId();JSONObject before=history().detail(id,viewer("a"));
        String originalOrganization=before.getString("organization");
        store.write(WriteClass.MAINTENANCE,0,c->{try(Statement s=c.createStatement()){
            s.execute("INSERT INTO kg_entity(public_id,type,status,created_seq) SELECT 'kge_zzzzzzzzzzzzzzzzzzzz',type,1,created_seq FROM kg_entity WHERE public_id='"+originalOrganization+"'");
            s.execute("UPDATE kg_entity SET status=2,merged_into=(SELECT ent_rowid FROM kg_entity WHERE public_id='kge_zzzzzzzzzzzzzzzzzzzz') WHERE public_id='"+originalOrganization+"'");}return null;});
        JSONObject after=history().detail(id,viewer("a"));assertEquals("kge_zzzzzzzzzzzzzzzzzzzz",after.getString("organization"));
        assertEquals(before.getString("original_organization"),after.getString("original_organization"));assertEquals(before.getJSONObject("identity_context").toString(),after.getJSONObject("identity_context").toString());
        assertTrue(history().events(id,0,100,viewer("a")).toString().contains("state"));
    }

    @Test public void repeatedExtractionDoesNotRedateAndIncompleteRevisionDoesNotRefute() throws Exception {
        publish(job()); assertEquals(2,count("SELECT count(*) FROM kg_observation"));
        processed+=86_400_000;publish(job());
        assertEquals(2,count("SELECT count(*) FROM kg_observation"));
        assertEquals(observed,count("SELECT min(observed_at) FROM kg_observation"));
        assertEquals(processed-86_400_000,count("SELECT min(recorded_at) FROM kg_observation"));
        doc.contentHash[0]=1;Extraction partial=new Extraction(1);partial.ranTier(1);publish(partial);
        assertEquals(2,count("SELECT count(*) FROM kg_observation WHERE assertion_status='recorded'"));
        assertEquals(0,count("SELECT count(*) FROM kg_statement"));
    }
    @Test public void genuinelyNewerLoadOfIdenticalContentCreatesNewConfirmationWithoutExtraction()throws Exception {
        publish(system("Wir nutzen SAP intern."));String old=systemId();
        doc.loadedAt=observed+86_400_000;processed+=86_400_000;publish(null);
        assertEquals(2,count("SELECT count(*) FROM kg_observation WHERE predicate='system_signal'"));
        assertEquals(observed,count("SELECT min(observed_at) FROM kg_observation WHERE predicate='system_signal'"));
        assertEquals(doc.loadedAt.longValue(),count("SELECT max(observed_at) FROM kg_observation WHERE predicate='system_signal'"));
        assertTrue(history().detail(old,viewer("a")).getString("observed_at").startsWith("2023"));
        publish(null);assertEquals(2,count("SELECT count(*) FROM kg_observation WHERE predicate='system_signal'"));
    }
    @Test public void originalObservationCannotBeRewrittenAsCorrection()throws Exception {
        publish(system("Wir nutzen SAP intern."));
        try{store.write(WriteClass.MAINTENANCE,0,c->{try(Statement s=c.createStatement()){s.execute("UPDATE kg_observation SET quote='invented'");}return null;});
            fail("original must be immutable");}catch(KgException expected){assertTrue(expected.getMessage().contains("immutable"));}
        assertEquals(1,count("SELECT count(*) FROM kg_observation WHERE quote='Wir nutzen SAP intern.'"));
    }
    @Test public void inaccessibleScopeEventsAreExcludedBeforePagination()throws Exception {
        publish(system("Wir nutzen SAP intern."));String id=systemId();
        doc.collections=List.of("b");publish(null);
        long after=0;int events=0;
        do {
            JSONObject page=history().events(id,after,1,viewer("b"));
            JSONArray items=page.getJSONArray("items");
            for(int i=0;i<items.length();i++) {
                JSONObject event=items.getJSONObject(i);events++;
                if(event.getString("kind").startsWith("scope_"))assertEquals("b",event.getString("after"));
            }
            if(!page.getBoolean("has_more"))break;after=page.getLong("next_after");
        }while(events<20);
        assertEquals("Only recording and the authorized scope insertion are visible",2,events);
    }
    @Test public void correctionPointersDoNotDiscloseInaccessibleObservations()throws Exception {
        publish(system("Wir nutzen SAP intern."));String visible=systemId();
        doc.docId="BBBBBBhost01";doc.url="https://example.org/other-job";doc.collections=List.of("b");
        publish(system("Wir nutzen Revit intern."));
        String hidden=store.read(c->{try(Statement s=c.createStatement();java.sql.ResultSet r=s.executeQuery(
                "SELECT public_id FROM kg_observation WHERE source_id='BBBBBBhost01' AND predicate='system_signal'")){assertTrue(r.next());return r.getString(1);}});
        store.write(WriteClass.MAINTENANCE,0,c->{try(java.sql.PreparedStatement p=c.prepareStatement("UPDATE kg_observation SET correction_of=? WHERE public_id=?")) {
            p.setString(1,hidden);p.setString(2,visible);p.executeUpdate();}return null;});
        assertTrue(history().detail(visible,viewer("a")).isNull("correction_of"));
        assertEquals(hidden,history().detail(visible,new KgReader(store,cfg,()->processed).viewer(null)).getString("correction_of"));
    }
    @Test public void docAndAllLiveEvidenceCanDisappearWithoutLosingQuoteOrScope() throws Exception {
        publish(job());
        store.write(WriteClass.MAINTENANCE,0,c->publisher.remove(c,List.of(doc.docId),null,processed));
        assertEquals(0,count("SELECT count(*) FROM kg_doc"));assertEquals(0,count("SELECT count(*) FROM kg_entity"));
        assertEquals(2,count("SELECT count(*) FROM kg_observation WHERE source_status='removed' AND length(quote)>0"));
        assertEquals(2,count("SELECT count(*) FROM kg_observation_scope"));
        assertEquals(2,count("SELECT count(*) FROM kg_observation WHERE identity_context LIKE '%Industry GmbH%'"));
    }
    @Test public void reclassificationRevokesHistoricalScopeAndDeletionKeepsLatestScope() throws Exception {
        publish(job());doc.collections=List.of("b");publish(null);
        assertEquals(0,count("SELECT count(*) FROM kg_observation_scope os JOIN kg_collection c USING(coll_id) WHERE c.name='a'"));
        assertEquals(2,count("SELECT count(*) FROM kg_observation_scope os JOIN kg_collection c USING(coll_id) WHERE c.name='b'"));
        store.write(WriteClass.MAINTENANCE,0,c->publisher.remove(c,List.of(doc.docId),null,processed));
        assertEquals(2,count("SELECT count(*) FROM kg_observation_scope os JOIN kg_collection c USING(coll_id) WHERE c.name='b'"));
    }
    @Test public void unknownHistoricalTimeRemainsUnknown() throws Exception {
        doc.loadedAt=null;publish(job());
        assertEquals(2,count("SELECT count(*) FROM kg_observation WHERE observed_at IS NULL"));
    }
    @Test public void newSourceObservationCreatesRevisionWhileFailureDoesNotEndJob() throws Exception {
        publish(job());doc.loadedAt=observed+86_400_000;doc.contentHash[0]=2;publish(job());
        assertEquals(4,count("SELECT count(*) FROM kg_observation"));
        store.write(WriteClass.MAINTENANCE,0,c->publisher.setState(c,List.of(Publisher.row(c,doc.docId).rowid),3,processed));
        assertEquals(4,count("SELECT count(*) FROM kg_observation WHERE source_status='gone' AND assertion_status='recorded'"));
    }
    @Test public void archiveFailureRollsBackDestructiveEvidenceReplacement() throws Exception {
        publish(job());store.write(WriteClass.MAINTENANCE,0,c->{try(Statement s=c.createStatement()){
            s.execute("DELETE FROM kg_observation");s.execute("CREATE TRIGGER test_archive_full BEFORE INSERT ON kg_observation BEGIN SELECT RAISE(ABORT,'archive full'); END");}return null;});
        try {publish(new Extraction(30));fail("replacement must be refused");}catch(KgException expected){assertTrue(expected.getMessage().contains("archive full"));}
        assertEquals(3,count("SELECT count(*) FROM kg_evidence"));assertEquals(3,count("SELECT count(*) FROM kg_statement"));
    }
    @Test public void realSchemaFourUpgradeCapturesOnlyStillExistingEvidence() throws Exception {
        publish(job());store.checkpoint();
        KgPaths oldPaths=new KgPaths(tmp.newFolder("legacy"));oldPaths.dir.mkdirs();oldPaths.tmp.mkdirs();
        try(java.sql.Connection c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+oldPaths.db);Statement s=c.createStatement()) {
            for(String ddl:KgSchema.DDL_V1)s.execute(ddl);
            for(int i=0;i<3;i++)for(String ddl:KgSchema.MIGRATIONS[i])s.execute(ddl);
            try(java.sql.PreparedStatement p=c.prepareStatement("ATTACH DATABASE ? AS fixture")){p.setString(1,paths.db.getAbsolutePath());p.execute();}
            for(String table:List.of("kg_meta","kg_collection","kg_vocab","kg_extractor","kg_doc","kg_entity","kg_statement","kg_doc_collection"))
                s.execute("INSERT INTO "+table+" SELECT * FROM fixture."+table);
            s.execute("INSERT INTO kg_evidence SELECT stmt_rowid,doc_rowid,tier,ext_id,kind,certainty,confidence,locator,excerpt,observed_at FROM fixture.kg_evidence");
            s.execute("UPDATE kg_meta SET value='4' WHERE key='schema_version'");
        }
        try(KgStore upgraded=KgStore.open(oldPaths,cfg,new StorageGuard(cfg,oldPaths,new KgTestSupport.Probe(),()->processed),KgStore.SQLITE,()->processed)) {
            assertEquals(KgSchema.CURRENT_VERSION,upgraded.schemaVersion());
            assertEquals(2L,(long)upgraded.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation")));
            upgraded.write(WriteClass.MAINTENANCE,0,c->{Observations.captureExisting(c);return null;});
            assertEquals(2L,(long)upgraded.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation")));
        }
    }
    @Test public void schemaFiveUpgradePreservesHistoryDatesIdsAndFeedHighWater()throws Exception {
        publish(system("Wir nutzen SAP intern."));store.checkpoint();String original=systemId();
        KgPaths oldPaths=new KgPaths(tmp.newFolder("schema-five"));oldPaths.dir.mkdirs();oldPaths.tmp.mkdirs();
        try(java.sql.Connection c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+oldPaths.db);Statement s=c.createStatement()) {
            for(String ddl:KgSchema.DDL_V1)s.execute(ddl);
            for(int i=0;i<4;i++)for(String ddl:KgSchema.MIGRATIONS[i])s.execute(ddl);
            try(java.sql.PreparedStatement p=c.prepareStatement("ATTACH DATABASE ? AS fixture")){p.setString(1,paths.db.getAbsolutePath());p.execute();}
            for(String table:List.of("kg_meta","kg_collection","kg_vocab","kg_extractor","kg_doc","kg_entity","kg_statement","kg_doc_collection","kg_evidence","kg_observation"))
                s.execute("INSERT INTO "+table+" SELECT * FROM fixture."+table);
            s.execute("DELETE FROM kg_observation_event");s.execute("INSERT INTO kg_observation_event SELECT * FROM fixture.kg_observation_event");
            s.execute("UPDATE kg_meta SET value='5' WHERE key='schema_version'");
            s.execute("UPDATE sqlite_sequence SET seq=500 WHERE name='kg_change'");
        }
        try(KgStore upgraded=KgStore.open(oldPaths,cfg,new StorageGuard(cfg,oldPaths,new KgTestSupport.Probe(),()->processed),KgStore.SQLITE,()->processed)) {
            assertEquals(6,upgraded.schemaVersion());
            assertTrue(upgraded.read(c->KgStore.queryLong(c,"SELECT seq FROM sqlite_sequence WHERE name='kg_change'"))>=500);
            JSONObject preserved=new ObservationHistory(new KgReader(upgraded,cfg,()->processed)).detail(original,new KgReader(upgraded,cfg,()->processed).viewer(List.of("a")));
            assertEquals("Wir nutzen SAP intern.",preserved.getString("quote"));assertTrue(preserved.getString("observed_at").startsWith("2023"));
            long count=upgraded.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation"));
            upgraded.write(WriteClass.MAINTENANCE,0,c->{Observations.captureExisting(c);return null;});
            assertEquals(count,(long)upgraded.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation")));
            assertEquals(0L,(long)upgraded.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_match_contribution")));
        }
    }

    @Test public void upgradeBackfillIsIdempotentAndRawStatementDeleteProtected() throws Exception {
        publish(job());store.write(WriteClass.MAINTENANCE,0,c->{
            try(Statement s=c.createStatement()){s.execute("DELETE FROM kg_observation_event");s.execute("DELETE FROM kg_observation");}
            Observations.captureExisting(c);Observations.captureExisting(c);
            try(Statement s=c.createStatement()){s.execute("DELETE FROM kg_statement");}return null;
        });assertEquals(2,count("SELECT count(*) FROM kg_observation"));
    }
    @Test public void backupAndFinalRebuildCarryIncludeCorrectionsAndEffectiveScopes() throws Exception {
        publish(job());java.io.File backup=new java.io.File(tmp.getRoot(),"before.db");store.backupTo(backup,10_000);
        doc.collections=List.of("b");publish(null);
        store.write(WriteClass.MAINTENANCE,0,c->{try(Statement s=c.createStatement()){
            s.execute("UPDATE kg_observation SET assertion_status='corrected' WHERE predicate='name'");}return null;});
        java.io.File finalCopy=new java.io.File(tmp.getRoot(),"final.db");store.backupTo(finalCopy,10_000);
        Observations.carryInto(backup,finalCopy);
        try(java.sql.Connection c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+backup)) {
            assertEquals(1,KgStore.queryLong(c,"SELECT count(*) FROM kg_observation WHERE assertion_status='corrected'"));
            assertEquals(2,KgStore.queryLong(c,"SELECT count(*) FROM kg_observation_scope s JOIN kg_collection c USING(coll_id) WHERE c.name='b'"));
            assertEquals(0,KgStore.queryLong(c,"SELECT count(*) FROM kg_observation_scope s JOIN kg_collection c USING(coll_id) WHERE c.name='a'"));
        }
    }

    @Test public void finalCarryReclassesShadowOnlyRevisionsAndPreservesAuditIds()throws Exception {
        publish(system("Wir nutzen SAP intern."));
        java.io.File shadow=new java.io.File(tmp.getRoot(),"shadow.db");store.backupTo(shadow,10_000);
        try(java.sql.Connection c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+shadow);Statement s=c.createStatement()) {
            s.execute("INSERT INTO kg_observation(public_id,source_id,source_url,content_revision,subject_id,subject_type,organization_id,"
                +"original_organization_id,identity_context,predicate,value,quote,locator,tier,extractor,vocabulary_version,observed_at,recorded_at,origin_scopes,source_status,certainty)"
                +" SELECT 'kgo_00000000000000000001',source_id,source_url,'shadow-only',subject_id,subject_type,organization_id,"
                +"original_organization_id,identity_context,predicate,value,quote,locator,tier,extractor,vocabulary_version,observed_at,recorded_at,origin_scopes,source_status,certainty"
                +" FROM kg_observation WHERE predicate='system_signal'");
        }
        doc.collections=List.of("b");publish(null);java.io.File latest=new java.io.File(tmp.getRoot(),"latest.db");store.backupTo(latest,10_000);
        java.util.Set<String> ids=store.read(c->{java.util.Set<String> out=new java.util.HashSet<>();try(Statement s=c.createStatement();java.sql.ResultSet r=s.executeQuery("SELECT public_id FROM kg_observation_event")){
            while(r.next())out.add(r.getString(1));}return out;});
        Observations.carryInto(shadow,latest);
        try(java.sql.Connection c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+shadow);Statement s=c.createStatement()) {
            assertEquals(0,KgStore.queryLong(c,"SELECT count(*) FROM kg_observation_scope s JOIN kg_collection c USING(coll_id) WHERE c.name='a'"));
            assertEquals(4,KgStore.queryLong(c,"SELECT count(*) FROM kg_observation_scope s JOIN kg_collection c USING(coll_id) WHERE c.name='b'"));
            try(java.sql.ResultSet r=s.executeQuery("SELECT public_id FROM kg_observation_event")){while(r.next())ids.remove(r.getString(1));}
            assertTrue(ids.isEmpty());assertNull(KgStore.queryString(c,"PRAGMA foreign_key_check"));
        }
    }
    @Test public void rawStatementDeletionCapturesEvidenceBeforeForeignKeyCascade()throws Exception {
        publish(system("Wir nutzen SAP intern."));
        store.write(WriteClass.MAINTENANCE,0,c->{try(Statement s=c.createStatement()) {
            s.execute("DELETE FROM kg_observation");s.execute("DELETE FROM kg_statement");}return null;});
        assertEquals(3,count("SELECT count(*) FROM kg_observation"));
        assertEquals(1,count("SELECT count(*) FROM kg_observation WHERE quote='Wir nutzen SAP intern.'"));
    }
    @Test public void republicationOfAnOlderDatedShutdownDoesNotRetireLaterUse()throws Exception {
        publish(system("Wir nutzen SAP unternehmensweit intern."));String original=systemId();
        doc.loadedAt=observed+86_400_000;doc.contentHash[0]=9;
        publish(system("Wir haben SAP unternehmensweit am 2020-10-09 abgeschaltet."));
        assertFalse(history().detail(original,viewer("a")).has("later_system_change"));
        assertEquals(1,count("SELECT count(*) FROM kg_observation WHERE asserted_at='2020-10-09'"));
    }
    @Test public void offeredServiceKeepsProviderAssignmentAfterLiveCleanup()throws Exception {
        Extraction e=job();Mention service=e.add(new Mention("s",Vocabulary.SERVICE,1));service.name="SAP support";
        e.add(new Claim("s",Vocabulary.NAME,null,service.name,1,1,false,"/service/name",service.name));
        e.add(new Claim("o",Vocabulary.OFFERS,"s",null,1,1,false,"/offers","Industry GmbH offers SAP support"));
        publish(e);store.write(WriteClass.MAINTENANCE,0,c->publisher.remove(c,List.of(doc.docId),null,processed));
        JSONObject page=history().page(null,null,0,100,viewer("a"));JSONArray items=page.getJSONArray("items");boolean found=false;
        for(int i=0;i<items.length();i++){JSONObject o=items.getJSONObject(i);if(!"service".equals(o.getString("subject_type")))continue;
            found=true;assertNotEquals(o.getString("subject"),o.getString("organization"));assertTrue(o.getJSONObject("identity_context").getJSONArray("organization").toString().contains("Industry GmbH"));}
        assertTrue(found);
    }
}
