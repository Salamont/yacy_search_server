/* Scoutro contributors, GPL-2.0-or-later. All HTTP/DNS is fake; only temporary DATA. */
package net.yacy.scoutro.api;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class CrawlFlowTest {
 @Rule public TemporaryFolder tmp = new TemporaryFolder();
 private JSONObject request() { return Json.obj("url","https://example.com/path","collection","research","scope","subpath","depth",2,"maxPages",15); }
 private void invalid(ScoutroActions actions, JSONObject body) throws Exception {
  try { actions.crawlStartAdmin(body,"key"); fail("Invalid request dispatched"); } catch (ApiException e) { assertEquals(400,e.status()); }
 }
 @Test public void normalizesHostAndFullUrlWithoutNetwork() throws Exception {
  assertEquals("www.example.com",HostInput.parse("HTTPS://WWW.EXAMPLE.COM.:443/path?q=1#fragment").host);
  assertEquals("https://www.example.com:443/path?q=1",HostInput.parse("HTTPS://WWW.EXAMPLE.COM.:443/path?q=1#fragment").url);
  assertEquals("xn--bcher-kva.example",HostInput.parse("https://BÜCHER.example/path").host);
  assertEquals("https://example.com/",HostInput.parse("Example.COM.").url);
 }
 @Test public void rejectsUnsafeOrAmbiguousHostInputs() throws Exception {
  for (String input : List.of("file:///tmp/x","ftp://example.com/","https://user:pw@example.com/","https://example.com:0/","https://example.com:65536/","localhost","127.0.0.1","https://[::1]/","example.com/path","example.com:80","a.example\" OR *:*","https://a.example%40bad.example/","https://a.exa\nmple/")) {
   try { HostInput.parse(input); fail(input); } catch (ApiException e) { assertEquals(400,e.status()); }
  }
 }
 @Test public void missingEmptyNullAndMalformedCollectionsNeverCallYaCy() throws Exception {
  FakeUpstream yacy=new FakeUpstream(); ScoutroActions actions=new ScoutroActions(yacy);
  invalid(actions,Json.obj("url","https://example.com/"));
  for(Object collection:Arrays.asList("", " ", null, "bad name", "x:y", 12)) invalid(actions,Json.obj("url","https://example.com/","collection",collection));
  assertTrue(yacy.calls.isEmpty());
 }
 @Test public void invalidFieldsNeverCallYaCy() throws Exception {
  FakeUpstream yacy=new FakeUpstream(); ScoutroActions actions=new ScoutroActions(yacy);
  for(JSONObject body:List.of(Json.obj("url","file:///tmp/x","collection","ok"),Json.obj("url","https://u:p@example.com/","collection","ok"),Json.obj("url","https://example.com/","collection","ok","maxPages",0),Json.obj("url","https://example.com/","collection","ok","unknown",true))) invalid(actions,body);
  assertTrue(yacy.calls.isEmpty());
 }
 @Test public void adminReplayPersistsAcrossRestartWithoutDuplicateStart() throws Exception {
  Path path=tmp.newFolder().toPath().resolve("DATA/SCOUTRO/crawls.json");
  FakeUpstream yacy=new FakeUpstream(){ @Override public Path crawlMetadataPath(){return path;} };
  ScoutroActions actions=new ScoutroActions(yacy);
  JSONObject first=actions.crawlStartAdmin(request(),"run-1");
  assertTrue(Files.exists(path)); assertEquals("research",yacy.last("Crawler_p.json").params.get("collection"));
  actions=new ScoutroActions(yacy);
  JSONObject replay=actions.crawlStartAdmin(request(),"run-1");
  assertEquals(first.getString("id"),replay.getString("id")); assertTrue(replay.getBoolean("idempotentReplay")); assertEquals(1,yacy.calls("Crawler_p.json").size());
  JSONObject different=request(); Json.put(different,"maxPages",14);
  try { actions.crawlStartAdmin(different,"run-1"); fail(); } catch(ApiException e){assertEquals("idempotency_conflict",e.code());}
  assertEquals(1,yacy.calls("Crawler_p.json").size());
 }
 @Test public void unknownStartIsConservative() throws Exception {
  FakeUpstream yacy=new FakeUpstream(); ScoutroActions actions=new ScoutroActions(yacy); yacy.crawlStartFault="lostNoProfile";
  try{actions.crawlStartAdmin(request(),"unknown");fail();}catch(ApiException e){assertEquals(502,e.status());}
  try{actions.crawlStartAdmin(request(),"unknown");fail();}catch(ApiException e){assertEquals("crawl_start_unconfirmed",e.code());}
  assertEquals(1,yacy.calls("Crawler_p.json").size());
 }
 @Test public void lostResponseWithProfileReconcilesByMarker() throws Exception {
  FakeUpstream yacy=new FakeUpstream(); ScoutroActions actions=new ScoutroActions(yacy); yacy.crawlStartFault="lost";
  try{actions.crawlStartAdmin(request(),"lost");fail();}catch(ApiException expected){}
  assertTrue(actions.crawlStartAdmin(request(),"lost").getBoolean("idempotentReplay")); assertEquals(1,yacy.calls("Crawler_p.json").size());
 }
 @Test public void secondKeyOnActiveHostIsBlocked() throws Exception {
  FakeUpstream yacy=new FakeUpstream(); ScoutroActions actions=new ScoutroActions(yacy); actions.crawlStartAdmin(request(),"one");
  try{actions.crawlStartAdmin(request(),"two");fail();}catch(ApiException e){assertEquals("host_busy",e.code());}
  assertEquals(1,yacy.calls("Crawler_p.json").size());
 }
 @Test public void statusRetainsMetadataAndNeverInventsProgress() throws Exception {
  FakeUpstream yacy=new FakeUpstream(); ScoutroActions actions=new ScoutroActions(yacy); String id=actions.crawlStartAdmin(request(),"status").getString("id");
  JSONObject status=actions.crawlGet(id);
  assertEquals("https://example.com/path",status.getString("url")); assertEquals("research",status.getString("collection")); assertEquals("subpath",status.getString("scope")); assertFalse(status.isNull("startedAt"));
  assertTrue(status.isNull("endedAt")); assertTrue(status.getJSONObject("progress").isNull("percent"));
  yacy.crawls.get(id).status="terminated"; assertEquals("terminated",actions.crawlGet(id).getString("state"));
  yacy.crawls.clear(); assertEquals("removed",actions.crawlGet(id).getString("state"));
  assertEquals(1,yacy.calls("Crawler_p.json").size());
 }
 @Test public void oldProfilesStayUnknownAndIdReuseNeverGetsOldMetadata() throws Exception {
  FakeUpstream yacy=new FakeUpstream(); ScoutroActions actions=new ScoutroActions(yacy); String id=actions.crawlStartAdmin(request(),"old").getString("id");
  yacy.crawls.put(id,new FakeUpstream.Crawl(id,"foreign.example","foreign"));
  JSONObject status=actions.crawlGet(id); assertTrue(status.isNull("url")); assertTrue(status.isNull("startedAt")); assertEquals("foreign",status.getString("collection"));
 }
 @Test public void readsDoNotCreateDataAndCorruptLedgerFailsClosed() throws Exception {
  Path path=tmp.newFolder().toPath().resolve("DATA/SCOUTRO/crawls.json");
  FakeUpstream yacy=new FakeUpstream(){@Override public Path crawlMetadataPath(){return path;}};
  ScoutroActions actions=new ScoutroActions(yacy); actions.crawlList(); assertFalse(Files.exists(path));
  Files.createDirectories(path.getParent()); Files.writeString(path,"{\"schema_version\":1,\"records\":[{}]}");
  try{actions.crawlStartAdmin(request(),"new");fail();}catch(ApiException e){assertEquals("crawl_store_unavailable",e.code());}
  assertEquals(0,yacy.calls("Crawler_p.json").size()); assertTrue(Files.readString(path).contains("[{}]"));
 }
 @Test public void collectionStorageFailurePreventsDispatch() throws Exception {
  FakeUpstream yacy=new FakeUpstream(){@Override public void requireCollectionStorage() throws ApiException{throw new ApiException(503,"collection_storage_unavailable","Unavailable");}};
  try{new ScoutroActions(yacy).crawlStartAdmin(request(),null);fail();}catch(ApiException e){assertEquals(503,e.status());}
  assertTrue(yacy.calls.isEmpty());
 }
 @Test public void knownAndUnknownResolutionUseScopedIndexOnly() throws Exception {
  FakeUpstream yacy=new FakeUpstream(); ScoutroActions actions=new ScoutroActions(yacy);
  assertTrue(actions.hostResolve(Map.of("input","https://EXAMPLE.com/path"),List.of("visible")).getBoolean("indexed"));
  assertTrue(yacy.last("solr/select").params.get("fq").contains("collection_sxt:\"visible\""));
  yacy.solrNumFound=0; JSONObject unknown=actions.hostResolve(Map.of("input","other.example"),List.of("visible"));
  assertFalse(unknown.getBoolean("indexed")); assertTrue(unknown.getJSONObject("crawl").getBoolean("collectionRequired"));
  assertEquals(0,yacy.calls("Crawler_p.json").size());
 }

 @Test public void partialIndexResponseNeverPretendsHostIsUnknown() throws Exception {
  FakeUpstream yacy=new FakeUpstream(){@Override public String getAdmin(String path,YaCyLoopback.Params params){return "{\"responseHeader\":{\"partialResults\":true},\"response\":{\"numFound\":0}}";}};
  try{new ScoutroActions(yacy).hostResolve(Map.of("input","example.com"),null);fail();}catch(ApiException e){assertEquals("index_unavailable",e.code());}
 }

 @Test public void journalKeepsMoreThanTenThousandReferencesAndReloadsOnce() throws Exception {
  Path path=tmp.newFile("crawls.ndjson").toPath(); StringBuilder journal=new StringBuilder();
  for(int n=0;n<10001;n++){
   String marker=String.format("%032x",n); net.yacy.scoutro.agents.CrawlRecord record=net.yacy.scoutro.agents.CrawlRecord.starting("scoutro","research","example.com","admin:key-"+n,1000L+n,marker,"https://example.com/",2,15,"domain");
   journal.append(Json.obj("schema_version",1,"record",record.toJson())).append('\n');
  }
  Files.writeString(path,journal); CrawlLedger ledger=new CrawlLedger(()->path);
  assertEquals("admin:key-10000",ledger.find("admin:key-10000").clientRef);
  assertEquals("admin:key-0",ledger.find("admin:key-0").clientRef);
  ledger.save(ledger.find("admin:key-10000").withState("started","large-profile"),null);
  assertEquals("large-profile",new CrawlLedger(()->path).find("admin:key-10000").crawlId);
 }
 @Test public void tornAppendIsNeverSilentlyTruncatedOrReplayed() throws Exception {
  Path path=tmp.newFile().toPath(); CrawlLedger ledger=new CrawlLedger(()->path);
  net.yacy.scoutro.agents.CrawlRecord record=net.yacy.scoutro.agents.CrawlRecord.starting("scoutro","research","example.com","admin:key",1000,"00000000000000000000000000000001","https://example.com/",2,15,"domain");
  ledger.save(record,null); Files.writeString(path,"{\"schema_version\":",StandardOpenOption.APPEND);
  byte[] before=Files.readAllBytes(path);
  try{new CrawlLedger(()->path).find("admin:key");fail();}catch(ApiException e){assertEquals("crawl_store_unavailable",e.code());}
  assertArrayEquals(before,Files.readAllBytes(path));
 }
}
