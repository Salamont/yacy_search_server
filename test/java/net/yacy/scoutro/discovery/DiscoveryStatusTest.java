/* Scoutro contributors, GPL-2.0-or-later. Pure projections; no scheduler/crawl. */
package net.yacy.scoutro.discovery;
import static org.junit.Assert.*;
import org.junit.Test;
public class DiscoveryStatusTest {
 private JsonObject root(boolean enabled,boolean paused){return new JsonObject().put("enabled",enabled).put("paused",paused);}
 private JsonObject status(boolean worker){return new JsonObject().put("worker_busy",worker).put("heartbeat",new JsonObject().put("enabled",true)).put("waiting_reason","idle");}
 @Test public void everyGlobalCombinationIsIndependentOfWorker(){
  for(boolean enabled:new boolean[]{true,false}) for(boolean paused:new boolean[]{true,false}) for(boolean worker:new boolean[]{true,false}) {
   JsonObject result=DiscoveryStatus.project(root(enabled,paused),status(worker));
   assertEquals(!enabled?"disabled":paused?"paused":"active",result.getString("automation_status"));
   assertFalse(result.getBoolean("running")); assertTrue(result.isNull("active_batch"));
   String actions=result.getJSONArray("allowed_actions").toString(); assertEquals(!enabled,actions.contains("enable")); assertEquals(enabled&&!paused,actions.contains("pause\"")); assertEquals(enabled&&paused,actions.contains("resume"));
  }
 }
 @Test public void disabledAndPausedDoNotHideAnAlreadySubmittedBatch(){
  for(boolean enabled:new boolean[]{true,false}) for(boolean paused:new boolean[]{true,false}) {
   JsonObject r=root(enabled,paused).put("active_run",new JsonObject().put("id","uuid").put("job_id","job").put("job",new JsonObject().put("name","Bau Test")).put("collection","test-web").put("phase","waiting_for_crawler").put("started_at",1234L).put("state_root","SECRET"));
   JsonObject out=DiscoveryStatus.project(r,status(false)); assertTrue(out.getBoolean("running")); assertEquals("Bau Test",out.getString("job_name")); assertEquals("test-web",out.getString("collection")); assertFalse(out.toString().contains("SECRET"));
  }
 }
 @Test public void recoveryBlocksRemainVisibleWithoutCallingThemRunning(){
  for(String phase:new String[]{"needs_reconcile","needs_review"}){
   JsonObject r=root(true,false).put("active_run",new JsonObject().put("phase",phase).put("error","coordinator_error"));
   JsonObject result=DiscoveryStatus.project(r,status(true)); assertFalse(result.getBoolean("running")); assertNotNull(result.optJSONObject("active_batch")); assertEquals("coordinator_error",result.getString("waiting_reason"));
  }
 }
 @Test public void missingHeartbeatOffersExplicitRepair(){
  JsonObject result=DiscoveryStatus.project(root(true,false),status(false).put("heartbeat",new JsonObject().put("enabled",false)));
  assertTrue(result.getJSONArray("allowed_actions").toString().contains("enable")); assertEquals("active",result.getString("automation_status"));
 }
 @Test public void finishedRunProjectsCompletedWithoutChangingStoredPhase(){
  JsonObject run=new JsonObject().put("phase","waiting_for_crawler").put("finished_at",5678L);
  assertEquals("completed",((JsonObject)DiscoveryStatus.batch(run)).getString("phase")); assertEquals("waiting_for_crawler",run.getString("phase"));
 }
}
