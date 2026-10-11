package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import net.yacy.scoutro.knowledge.*;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.store.KgStore;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;

/** Controlled Solr pages, real SQLite transactions. No production sources. */
public class SyncBoundaryTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final class Source implements SolrSource {
        final NavigableMap<String, SolrDoc> docs = new TreeMap<>();
        int scans;
        int failAt = -1;
        boolean failGet;
        String retriedCursor;
        Source(int count) {this(count,10000,10000);}
        Source(int count,int activeFrom,int longDescriptionAt) {
            for (int n=0; n<count; n++) {
                String id=String.format(Locale.ROOT,"%06dhost01",n);
                Map<String,Object> fields=new HashMap<>();
                fields.put(SolrDoc.ID,id); fields.put(SolrDoc.VERSION,1L);
                fields.put(SolrDoc.SKU,"https://fixture.example/"+n);
                fields.put(SolrDoc.COLLECTIONS,List.of("c1"));
                fields.put(SolrDoc.HTTPSTATUS,n<activeFrom?404:200);
                if(n==longDescriptionAt) fields.put(SolrDoc.LD_JSON,List.of("{\"@type\":\"Organization\",\"name\":\"Fixture GmbH\","
                        +"\"description\":\""+"a".repeat(20000)+". Wir nutzen SAP intern.\"}"));
                docs.put(id,SolrDoc.of(fields));
            }
        }
        @Override public Page scan(String after,int rows,Collection<String> collections,long version)throws IOException {
            if(++scans==failAt){retriedCursor=after;throw new IOException("controlled page failure");}
            return new Page((after==null?docs:docs.tailMap(after,false)).values().stream().limit(rows).toList(),false);
        }
        @Override public Map<String,SolrDoc> get(Collection<String> ids,List<String> fields) {
            if(failGet)throw new AssertionError("controlled unexpected task failure");
            Map<String,SolrDoc> out=new HashMap<>();for(String id:ids)if(docs.containsKey(id))out.put(id,docs.get(id));return out;
        }
        @Override public String text(String id){return "";}
    }

    @Test(timeout=30000) public void longDescriptionDoesNotHaltAtTenThousandScannedAndExtraction487()throws Exception {
        AtomicLong clock=new AtomicLong(System.currentTimeMillis());
        KgConfig cfg=KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS,"c1"));
        KgPaths paths=new KgPaths(tmp.newFolder());StorageGuard guard=new StorageGuard(cfg,paths,new KgTestSupport.Probe(),clock::get);
        try(KgStore store=KgStore.open(paths,cfg,guard,KgStore.SQLITE,clock::get)) {
            Source source=new Source(10050,0,486);
            SyncService sync=new SyncService(cfg,store,new DirtySet(1000),source,new Gates(cfg,Gates.IDLE),clock::get,false);
            sync.step();clock.addAndGet(3000L);
            try{for(int i=0;i<10;i++)sync.step();}
            catch(StackOverflowError failure){throw new AssertionError("controlled sync failure: scanned="+sync.reconciler().current().scanned
                    +", published="+sync.counters.published.get()+", extractions="+sync.counters.extractions.get()
                    +", queued="+store.read(WorkQueue::size),failure);}
            assertEquals(10000L,sync.reconciler().current().scanned);
            assertEquals(500L,sync.counters.published.get());assertEquals(500L,sync.counters.extractions.get());
            assertEquals(9500L,(long)store.read(WorkQueue::size));
            sync.step();assertEquals(10050L,sync.reconciler().current().scanned);
            assertEquals(550L,sync.counters.published.get());assertEquals(9500L,(long)store.read(WorkQueue::size));
            assertEquals(1L,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation WHERE predicate='system_signal'")));
        }
    }

    @Test(timeout=30000) public void reconcileResumesBeyondTenThousandAndPublishesAfterLongDescription()throws Exception {
        AtomicLong clock=new AtomicLong(System.currentTimeMillis());
        KgConfig cfg=KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS,"c1"));
        KgPaths paths=new KgPaths(tmp.newFolder());
        StorageGuard guard=new StorageGuard(cfg,paths,new KgTestSupport.Probe(),clock::get);
        try(KgStore store=KgStore.open(paths,cfg,guard,KgStore.SQLITE,clock::get)) {
            Source source=new Source(10050);source.failAt=11;
            SyncService sync=new SyncService(cfg,store,new DirtySet(1000),source,new Gates(cfg,Gates.IDLE),clock::get,false);
            assertTrue(sync.step()); // initialize/create
            for(int i=0;i<10;i++)assertTrue(sync.step());
            assertEquals(10000,sync.reconciler().current().scanned);
            String cursor=sync.reconciler().current().cursor;
            sync.step();
            assertEquals(Reconciler.STATE_ABORTED,sync.reconciler().current().state);
            assertEquals(cursor,source.retriedCursor);
            assertEquals(cursor,store.read(c->KgStore.queryString(c,"SELECT cursor FROM kg_scan ORDER BY run_id DESC LIMIT 1")));
            assertEquals(10000L,(long)store.read(c->KgStore.queryLong(c,"SELECT scanned FROM kg_scan ORDER BY run_id DESC LIMIT 1")));
            clock.addAndGet(60000L);
            for(int i=0;i<100 && sync.reconciler().pending();i++){sync.step();clock.addAndGet(3000L);}
            assertFalse(sync.status().toString(),sync.reconciler().pending());
            assertEquals(10050L,sync.status().getJSONObject("reconcile").getJSONObject("last").getLong("scanned"));
            assertEquals(50L,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_doc")));
            assertEquals(0L,(long)store.read(WorkQueue::size));
            assertEquals(0L,sync.status().getJSONObject("processed").getLong("failedDocs"));
            assertEquals(1L,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation WHERE predicate='system_signal'")));
            assertEquals("Wir nutzen SAP intern.",store.read(c->KgStore.queryString(c,"SELECT quote FROM kg_observation WHERE predicate='system_signal'")));
        }
    }

    @Test public void anUnexpectedErrorKeepsQueueClaimsAndCursorUntilExplicitRestart()throws Exception {
        AtomicLong clock=new AtomicLong(System.currentTimeMillis());
        KgConfig cfg=KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS,"c1"));
        KgPaths paths=new KgPaths(tmp.newFolder());StorageGuard guard=new StorageGuard(cfg,paths,new KgTestSupport.Probe(),clock::get);
        try(KgStore store=KgStore.open(paths,cfg,guard,KgStore.SQLITE,clock::get)) {
            Source source=new Source(10001);source.docs.headMap("010000host01",false).clear();source.failGet=true;
            SyncService failed=new SyncService(cfg,store,new DirtySet(100),source,new Gates(cfg,Gates.IDLE),clock::get,false);
            failed.step();clock.addAndGet(3000L);
            try{failed.step();fail("must propagate Error");}catch(AssertionError e){assertEquals("controlled unexpected task failure",e.getMessage());}
            assertEquals(1L,(long)store.read(WorkQueue::size));
            assertEquals(1L,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_work WHERE claimed_at IS NOT NULL")));
            assertEquals(1L,(long)store.read(c->KgStore.queryLong(c,"SELECT scanned FROM kg_scan ORDER BY run_id DESC LIMIT 1")));
            failed.requestStop();assertFalse(failed.step());
            source.failGet=false;
            SyncService restarted=new SyncService(cfg,store,new DirtySet(100),source,new Gates(cfg,Gates.IDLE),clock::get,false);
            for(int i=0;i<50;i++){restarted.step();clock.addAndGet(3000L);if(!restarted.reconciler().pending())break;}
            assertEquals(0L,(long)store.read(WorkQueue::size));
            assertEquals(1L,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_doc")));
            assertEquals(1L,(long)store.read(c->KgStore.queryLong(c,"SELECT count(*) FROM kg_observation WHERE predicate='system_signal'")));
        }
    }

    @Test public void anErrorDuringDrainRestoresCapturedEventsAfterTransactionRollback()throws Exception {
        AtomicLong clock=new AtomicLong(System.currentTimeMillis());AtomicBoolean inject=new AtomicBoolean(false);
        KgConfig cfg=KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS,"c1"));
        KgPaths paths=new KgPaths(tmp.newFolder());StorageGuard guard=new StorageGuard(cfg,paths,new KgTestSupport.Probe(),clock::get);
        KgStore.ConnectionFactory connections=file->{
            Connection delegate=KgStore.SQLITE.open(file);
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement")&&args[0].toString().contains("kg_work")&&inject.compareAndSet(true,false))
                    throw new AssertionError("controlled drain failure");
                try{return method.invoke(delegate,args);}catch(InvocationTargetException e){throw e.getCause();}
            });
        };
        try(KgStore store=KgStore.open(paths,cfg,guard,connections,clock::get)) {
            DirtySet dirty=new DirtySet(100);SyncService sync=new SyncService(cfg,store,dirty,new Source(0),new Gates(cfg,Gates.IDLE),clock::get,false);
            sync.step();clock.addAndGet(3000L);dirty.added("AAAAAAhost01",1L);inject.set(true);
            try{sync.step();fail("must propagate Error");}catch(AssertionError e){assertEquals("controlled drain failure",e.getMessage());}
            assertEquals(1,dirty.size());assertEquals(0L,(long)store.read(WorkQueue::size));
            assertEquals("ok",store.read(c->KgStore.queryString(c,"PRAGMA quick_check")));
            sync.requestStop();sync.finalDrain(clock.get()+2000L);
            assertEquals(0,dirty.size());assertEquals(1L,(long)store.read(WorkQueue::size));
        }
    }
}
