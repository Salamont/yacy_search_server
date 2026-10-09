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
            assertEquals(5,upgraded.schemaVersion());
            assertEquals(2L,(long)upgraded.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation")));
            upgraded.write(WriteClass.MAINTENANCE,0,c->{Observations.captureExisting(c);return null;});
            assertEquals(2L,(long)upgraded.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation")));
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
}
