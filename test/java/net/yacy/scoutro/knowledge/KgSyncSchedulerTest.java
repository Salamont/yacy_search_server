package net.yacy.scoutro.knowledge;

import static org.junit.Assert.*;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.apache.solr.client.solrj.SolrClient;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.Gates;

/** Real periodic executor, controlled sources. No automatic recovery of Errors. */
public class KgSyncSchedulerTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    private KgRuntime runtime;
    private final AtomicLong clock=new AtomicLong(System.currentTimeMillis());
    @After public void close(){if(runtime!=null)runtime.close();}

    private KgRuntime open(Supplier<SolrClient> source)throws Exception {
        Map<String,String> cfg=KgTestSupport.enabled(KgConfig.COLLECTIONS,"c1");
        runtime=new KgRuntime(new KgRuntime.Env(tmp.newFolder(),cfg::get,clock::get,new KgTestSupport.Probe(),KgStore.SQLITE,true,source,Gates.IDLE));
        runtime.open();assertEquals(KgRuntime.State.RUNNING,runtime.state());return runtime;
    }
    private static ScheduledFuture<?> future(KgRuntime r)throws Exception {
        Field field=KgRuntime.class.getDeclaredField("syncFuture");field.setAccessible(true);return (ScheduledFuture<?>)field.get(r);
    }
    private static ScheduledExecutorService executor(KgRuntime r)throws Exception {
        Field field=KgRuntime.class.getDeclaredField("syncThread");field.setAccessible(true);return (ScheduledExecutorService)field.get(r);
    }
    private static void await(BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(10L);
        assertTrue("bounded wait expired",condition.getAsBoolean());
    }
    private JSONObject scheduler(){return runtime.status().optJSONObject("sync").optJSONObject("scheduler");}

    @Test public void escapedErrorsAreReportedAndNeverAutomaticallyRestarted()throws Exception {
        for(Error fault:new Error[]{new AssertionError("fixture"),new StackOverflowError("fixture"),new OutOfMemoryError("simulated, no allocation"),new ThreadDeath(),new LinkageError("fixture")}) {
            AtomicInteger calls=new AtomicInteger();open(()->{calls.incrementAndGet();throw fault;});
            ScheduledFuture<?> task=future(runtime);
            try{task.get(5,TimeUnit.SECONDS);fail("periodic task must fail");}catch(ExecutionException e){assertSame(fault,e.getCause());}
            JSONObject s=scheduler();
            assertEquals("failed",s.getString("state"));assertTrue(s.getBoolean("done"));assertFalse(s.getBoolean("executing"));
            assertEquals(fault.getClass().getName(),s.getString("lastError"));assertTrue(s.getLong("failedAt")>0L);
            assertTrue(s.getLong("lastStartedAt")<=s.getLong("lastFinishedAt"));assertFalse(s.getBoolean("automaticRecovery"));
            assertEquals("failed",runtime.status().getJSONObject("sync").getString("state"));
            assertEquals("task_terminated",runtime.status().getJSONObject("sync").getString("reason"));
            runtime.watchdogTick();runtime.tick();
            executor(runtime).schedule(()->{},450L,TimeUnit.MILLISECONDS).get(2,TimeUnit.SECONDS);
            assertEquals(1,calls.get());assertFalse(executor(runtime).isShutdown());
            runtime.close();assertTrue(task.isDone());
        }
    }

    @Test public void closeDoesNotInterruptInFlightSolrOrResurrectTheTask()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();AtomicBoolean interrupted=new AtomicBoolean();
        open(()->{calls.incrementAndGet();entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted.set(true);Thread.currentThread().interrupt();}return null;});
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(scheduler().getBoolean("executing"));
            ScheduledFuture<?> task=future(runtime);ScheduledExecutorService worker=executor(runtime);
            Thread closer=new Thread(runtime::close,"fixture-close");closer.start();
            await(task::isCancelled);release.countDown();closer.join(5000L);assertFalse(closer.isAlive());
            assertFalse(interrupted.get());assertEquals(1,calls.get());assertTrue(worker.isShutdown());
            runtime.watchdogTick();runtime.tick();runtime.syncTick();assertEquals(1,calls.get());
            assertEquals(KgRuntime.State.STOPPED,runtime.state());
        } finally {release.countDown();}
    }

    @Test public void anExplicitReopenStartsOnlyANewSessionTask()throws Exception {
        AtomicBoolean fault=new AtomicBoolean(true);AtomicInteger calls=new AtomicInteger();
        open(()->{calls.incrementAndGet();if(fault.get())throw new AssertionError("fixture");return null;});
        ScheduledFuture<?> old=future(runtime);try{old.get(5,TimeUnit.SECONDS);fail("must fail");}catch(ExecutionException expected){ }
        ScheduledExecutorService oldExecutor=executor(runtime);runtime.close();fault.set(false);clock.addAndGet(1000L);runtime.open();
        ScheduledFuture<?> fresh=future(runtime);assertNotSame(old,fresh);assertTrue(oldExecutor.isShutdown());
        await(()->scheduler().optLong("completedTicks")>0L);
        assertFalse(fresh.isDone());assertTrue(scheduler().isNull("lastError"));assertTrue(old.isDone());
        runtime.close();assertTrue(fresh.isCancelled());assertEquals(2,calls.get());
    }

    @Test public void restoreCancelsTheOldFutureBeforeOpeningTheReplacement()throws Exception {
        open(()->null);await(()->scheduler().optLong("completedTicks")>0L);
        runtime.backup();
        await(()->{
            JSONObject backup=runtime.status().optJSONObject("backup"),last=backup==null?null:backup.optJSONObject("last");
            return last!=null&&"created".equals(last.optString("result"));
        });
        String name=runtime.status().getJSONObject("backup").getJSONObject("last").getString("file");
        ScheduledFuture<?> old=future(runtime);ScheduledExecutorService oldExecutor=executor(runtime);
        runtime.restore(name);
        ScheduledFuture<?> fresh=future(runtime);assertNotSame(old,fresh);assertTrue(old.isCancelled());assertTrue(oldExecutor.isShutdown());
        await(()->scheduler().optLong("completedTicks")>0L);assertFalse(fresh.isDone());
        runtime.close();runtime.watchdogTick();assertTrue(fresh.isCancelled());
    }
}
