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
    @Test public void actualStartUsesExistingCrawlerApiAndMarker() throws Exception {
        Bridge bridge=new Bridge(); JsonObject result=new DiscoveryGateway(bridge).start(seed(),MARKER);
        assertEquals(MARKER,result.getString("startMarker")); assertEquals(1,bridge.yacy.calls("Crawler_p.json").size());
        var params=bridge.yacy.calls("Crawler_p.json").get(0).params;
        assertEquals("custom-index",params.get("collection"));assertEquals("15",params.get("crawlingDomMaxPages"));
        assertTrue(params.get("mustnotmatch").contains(MARKER));
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
}
