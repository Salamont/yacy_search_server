/* Scoutro contributors, GPL-2.0-or-later. No real crawler/data/network. */
package net.yacy.scoutro.api;

import static org.junit.Assert.*;
import org.junit.Test;
import org.w3c.dom.Document;
import net.yacy.scoutro.discovery.JsonObject;

public class DiscoveryGatewayTest {
    private static class Bridge implements Upstream {
        final FakeUpstream yacy = new FakeUpstream();
        boolean foreignTags;
        public String getAdmin(String path, YaCyLoopback.Params parameters) throws ApiException {
            if ("solr/select".equals(path) && "true".equals(parameters.get("facet"))) {
                return "{\"facet_counts\":{\"facet_fields\":{\"collection_sxt\":[\"custom-index\",1" + (foreignTags ? ",\"foreign-index\",1" : "") + "]}}}";
            }
            return yacy.getAdmin(path,parameters);
        }
        public String postAdmin(String p, YaCyLoopback.Params a) throws ApiException { return yacy.postAdmin(p,a); }
        public Document getAdminXml(String p, YaCyLoopback.Params a) throws ApiException { return yacy.getAdminXml(p,a); }
        public Document getPublicXml(String p, YaCyLoopback.Params a) throws ApiException { return yacy.getPublicXml(p,a); }
    }
    private JsonObject seed() { return new JsonObject().put("url","https://example.com/").put("collection","custom-index").put("scope","domain").put("maxPages",15).put("depth",2); }
    private static final String MARKER="0123456789abcdef0123456789abcdef";
    @Test public void ordinaryYaCyCrawlHasJsonNullStartMarker() throws Exception {
        final Bridge bridge = new Bridge();
        final FakeUpstream.Crawl ordinary = new FakeUpstream.Crawl("ordinary", "example.org", "custom-index");
        ordinary.status = "passive";
        bridge.yacy.crawls.put(ordinary.id, ordinary);
        final JsonObject crawl = new DiscoveryGateway(bridge).crawls().getJSONObject(0);
        assertEquals("ordinary", crawl.getString("id")); assertEquals("terminated", crawl.getString("state"));
        assertTrue(crawl.has("startMarker")); assertTrue(crawl.isNull("startMarker"));
        // The bundled org.json coerces JSON null to "null", not the empty fallback.
        assertEquals("null", crawl.optString("startMarker"));
        assertEquals(0, bridge.yacy.calls("Crawler_p.json").size());
    }
    @Test public void actualStartUsesExistingCrawlerApiAndMarker() throws Exception {
        Bridge bridge=new Bridge(); JsonObject result=new DiscoveryGateway(bridge).start(seed(),MARKER);
        assertEquals(MARKER,result.getString("startMarker")); assertEquals(1,bridge.yacy.calls("Crawler_p.json").size());
        var params=bridge.yacy.calls("Crawler_p.json").get(0).params;
        assertEquals("custom-index",params.get("collection"));assertEquals("15",params.get("crawlingDomMaxPages"));
        assertTrue(params.get("mustnotmatch").contains(MARKER));
        // only the candidate host: YaCy's domain filter alone would also follow host.com.au
        assertTrue(params.get("mustnotmatch").contains("(?!https?://"));
        assertEquals(MARKER, ScoutroActions.startMarker(params.get("mustnotmatch")));
    }
    @Test public void foreignCollectionBlocksBeforeAnyCrawlStart() throws Exception {
        Bridge bridge=new Bridge();bridge.yacy.outsideNumFound=1;
        try{new DiscoveryGateway(bridge).start(seed(),MARKER);fail();}catch(ApiException e){assertEquals("collection_conflict",e.code());}
        assertEquals(0,bridge.yacy.calls("Crawler_p.json").size());
    }
    @Test public void multipleCollectionTagsArePreservedByRefusingRecrawl() throws Exception {
        Bridge bridge=new Bridge();bridge.foreignTags=true;
        try{new DiscoveryGateway(bridge).start(seed(),MARKER);fail();}catch(ApiException e){assertEquals("collection_conflict",e.code());}
        assertEquals(0,bridge.yacy.calls("Crawler_p.json").size());
    }
    @Test public void existingHostCrawlCannotHaveItsQueueReplaced() throws Exception {
        Bridge bridge=new Bridge();DiscoveryGateway gateway=new DiscoveryGateway(bridge);gateway.start(seed(),MARKER);
        try{gateway.start(seed(),"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");fail();}catch(ApiException e){assertEquals("host_busy",e.code());}
        assertEquals(1,bridge.yacy.calls("Crawler_p.json").size());
    }
    @Test public void missingCollectionIsRejectedBeforePreflight() throws Exception {
        Bridge bridge=new Bridge(); JsonObject invalid=seed(); invalid.remove("collection");
        try{new DiscoveryGateway(bridge).start(invalid,MARKER);fail();}catch(ApiException e){assertEquals(400,e.status());}
        assertTrue(bridge.yacy.calls.isEmpty());
    }

    private static net.yacy.scoutro.collections.CollectionDecisions decisions(final java.nio.file.Path root, final boolean recrawl,
            final String mode, final String collections) throws Exception {
        final net.yacy.scoutro.collections.CollectionDecisions d = new net.yacy.scoutro.collections.CollectionDecisions(
                new net.yacy.scoutro.collections.DecisionStore(root.resolve("c"), root.resolve("marker")), "sonstige", recrawl, () -> 1L);
        if (mode != null) {
            final net.yacy.scoutro.collections.CollectionDecision.Builder b = new net.yacy.scoutro.collections.CollectionDecision.Builder();
            b.id = net.yacy.scoutro.collections.CollectionDecisions.newId();
            b.domain = "example.com";
            b.mode = net.yacy.scoutro.collections.CollectionDecision.Mode.valueOf(mode);
            b.collections = java.util.List.of(collections.split(","));
            b.reason = "fachlich_falsch";
            d.put(b.build(), "decide");
        }
        return d;
    }
    @org.junit.Rule public org.junit.rules.TemporaryFolder tmp = new org.junit.rules.TemporaryFolder();
    @org.junit.After public void forget() { net.yacy.scoutro.collections.CollectionDecisions.install(null); }
    @Test public void decidedMultipleAssignmentIsNoConflict() throws Exception {
        net.yacy.scoutro.collections.CollectionDecisions.install(decisions(tmp.getRoot().toPath(), false, "EXACT", "custom-index,foreign-index"));
        Bridge bridge=new Bridge();bridge.foreignTags=true;bridge.yacy.outsideNumFound=1;
        new DiscoveryGateway(bridge).start(seed(),MARKER);
        assertEquals(1,bridge.yacy.calls("Crawler_p.json").size());
    }
    @Test public void domainCorrectedIntoFallbackIsNotRecrawledByDiscovery() throws Exception {
        net.yacy.scoutro.collections.CollectionDecisions.install(decisions(tmp.getRoot().toPath(), false, "EXACT", "sonstige"));
        Bridge bridge=new Bridge();
        try{new DiscoveryGateway(bridge).start(seed(),MARKER);fail();}catch(ApiException e){assertEquals("collection_decided",e.code());assertEquals(409,e.status());}
        assertEquals(0,bridge.yacy.calls("Crawler_p.json").size());
    }
    @Test public void fallbackRecrawlWhenEnabledStartsTheCrawl() throws Exception {
        net.yacy.scoutro.collections.CollectionDecisions.install(decisions(tmp.getRoot().toPath(), true, "EXACT", "sonstige"));
        Bridge bridge=new Bridge();bridge.yacy.outsideNumFound=1;
        new DiscoveryGateway(bridge).start(seed(),MARKER);
        // the index hook writes sonstige; the crawl itself carries the profile's collection
        assertEquals("custom-index",bridge.yacy.calls("Crawler_p.json").get(0).params.get("collection"));
    }
    @Test public void unreadableDecisionsRefuseEveryCrawl() throws Exception {
        final java.nio.file.Path root = tmp.getRoot().toPath();
        decisions(root, false, "EXACT", "sonstige");
        try (java.util.stream.Stream<java.nio.file.Path> f = java.nio.file.Files.list(root.resolve("c"))) { f.forEach(p -> p.toFile().delete()); }
        net.yacy.scoutro.collections.CollectionDecisions.install(decisions(root, false, null, null));
        Bridge bridge=new Bridge();
        try{new DiscoveryGateway(bridge).start(seed(),MARKER);fail();}catch(ApiException e){assertEquals("decisions_unavailable",e.code());assertEquals(503,e.status());}
        assertEquals(0,bridge.yacy.calls("Crawler_p.json").size());
    }
}
