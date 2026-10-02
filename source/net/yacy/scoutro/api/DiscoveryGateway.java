/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.kelondro.util.MemoryControl;
import net.yacy.scoutro.discovery.DiscoveryService;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;

/** Uses existing Scoutro translations; no crawler worker or credential passed to Python. */
public final class DiscoveryGateway implements DiscoveryService.Backend {
    private final ScoutroActions actions;
    private final Upstream upstream;
    public DiscoveryGateway() { this(new YaCyLoopback()); }
    DiscoveryGateway(final Upstream upstream) { this.actions = new ScoutroActions(upstream); this.upstream = upstream; }
    @Override public JsonObject search(final JsonObject parameters) throws ApiException {
        final Map<String, String> query = new java.util.HashMap<>();
        query.put("query", parameters.getString("query")); query.put("source", "network"); query.put("limit", "20");
        return new JsonObject(this.actions.search(query));
    }
    @Override public JsonArray crawls() throws ApiException { return new JsonObject(this.actions.crawlList()).getJSONArray("crawls"); }
    @Override public JsonObject start(final JsonObject parameters, final String marker) throws ApiException {
        final String host;
        try { host = new URI(parameters.getString("url")).getHost(); }
        catch (final Exception e) { throw ApiException.invalid("url", "Invalid crawl URL."); }
        if (host == null) throw ApiException.invalid("url", "Expected DNS hostname.");
        synchronized (ScoutroActions.CRAWL_START_LOCK) {
            for (final org.json.JSONObject raw : this.actions.loadCrawls().values()) {
                final JsonObject crawl = new JsonObject(raw);
                if ("terminated".equals(crawl.optString("state"))) continue;
                String runningHost = crawl.optString("name", "");
                try { if (runningHost.contains("://")) runningHost = new URI(runningHost).getHost(); }
                catch (final Exception e) { throw new ApiException(409, "host_busy", "Cannot establish foreign crawl host."); }
                if (host.equalsIgnoreCase(runningHost) || host.equalsIgnoreCase(crawl.optString("host"))) {
                    throw new ApiException(409, "host_busy", "A crawl already uses the candidate host.");
                }
            }
            final String collection = parameters.getString("collection");
            if (this.actions.countHostOutside(host, List.of(collection)) > 0) {
                throw new ApiException(409, "collection_conflict", "Candidate has documents outside the target collection.");
            }
            final String body = this.upstream.getAdmin("solr/select", new YaCyLoopback.Params()
                    .add("q", "host_s:" + ScoutroActions.phrase(host.startsWith("www.") ? host.substring(4) : host)
                            + " OR host_s:" + ScoutroActions.phrase("www." + (host.startsWith("www.") ? host.substring(4) : host)))
                    .add("rows", 0).add("wt", "json").add("facet", "true").add("facet.field", "collection_sxt")
                    .add("facet.limit", 500).add("facet.mincount", 1));
            final JsonArray facets = new JsonObject(body).getJSONObject("facet_counts").getJSONObject("facet_fields").getJSONArray("collection_sxt");
            for (int i = 0; i < facets.length(); i += 2) if (!collection.equals(facets.getString(i))) {
                throw new ApiException(409, "collection_conflict", "Recrawl would replace other collection memberships.");
            }
            return new JsonObject(this.actions.crawlStart(parameters, marker));
        }
    }
    @Override public JsonObject capacity() {
        final Switchboard sb = Switchboard.getSwitchboard();
        final JsonObject result = new JsonObject().put("allowed", false).put("reason", "unavailable");
        if (sb == null || sb.crawlQueues == null || sb.crawlStacker == null || sb.index == null) return result;
        final int crawl = sb.crawlQueues.coreCrawlJobSize() + sb.crawlQueues.limitCrawlJobSize() + sb.crawlQueues.remoteTriggeredCrawlJobSize();
        final int indexing = sb.getIndexingProcessorsQueueSize();
        final int stacker = sb.crawlStacker.size();
        final int loaders = sb.crawlQueues.activeWorkerEntries().size();
        final long threshold = Math.max(0, sb.getConfigLong("scoutro.discovery.queueThreshold", 100));
        final long indexThreshold = Math.max(0, sb.getConfigLong("scoutro.discovery.indexingThreshold", 20));
        result.put("crawl_queue", crawl).put("stacker", stacker).put("indexing_queue", indexing).put("loaders", loaders)
                .put("queue_threshold", threshold).put("indexing_threshold", indexThreshold);
        String reason = "";
        if (sb.crawlJobIsPaused(SwitchboardConstants.CRAWLJOB_LOCAL_CRAWL)) reason = "crawler_paused";
        else if (sb.index.fulltext().getDefaultConnector() == null || sb.index.fulltext().getDefaultConnector().isClosed()) reason = "index_unavailable";
        else if (sb.observer == null || !sb.observer.getMemoryAvailable()
                || MemoryControl.available() < sb.getConfigLong("scoutro.discovery.minimumFreeMemory", 20 * 1024 * 1024L)
                || sb.observer.getUsableSpace() < sb.getConfigLong("scoutro.discovery.minimumFreeDisk", 20 * 1024 * 1024L)) reason = "resources";
        else if (crawl + stacker > threshold || indexing > indexThreshold
                || loaders >= sb.getConfigInt(SwitchboardConstants.CRAWLER_THREADS_ACTIVE_MAX, 10)) reason = "capacity";
        return result.put("allowed", reason.isEmpty()).put("reason", reason);
    }
}
