/*
 *  ScopedActions
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.api;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.agents.Agent;
import net.yacy.scoutro.agents.AgentActionRegistry;
import net.yacy.scoutro.agents.AgentException;
import net.yacy.scoutro.agents.AgentStore;
import net.yacy.scoutro.agents.CrawlRecord;

/**
 * The only way an agent request reaches {@link ScoutroActions}. The data
 * scope and the limits of the agent are set here on the server side, never
 * taken from query syntax:
 * <ul>
 * <li>search: local only; the collection is sent as YaCy's "collection"
 * parameter (which overrides inline syntax), one upstream call per
 * collection; "collection:" in the query is refused</li>
 * <li>evidence / lookup / index status: Solr filter on the scope; documents
 * outside the scope do not exist for the agent</li>
 * <li>crawls: collection in scope, allowed domain, depth and page limits,
 * parallelism, no wide crawls, no crawl on a host another crawl is busy
 * with; agents see and stop only crawls they started</li>
 * </ul>
 */
class ScopedActions {

    static final int MAX_SEARCH_COLLECTIONS = 10;
    private static final Pattern CLIENT_REF = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");
    private static final Pattern COLLECTION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** Agent crawl starts are serialized so that the parallelism limit holds. */
    private static final Object CRAWL_LOCK = new Object();

    private final ScoutroActions actions;
    private final AgentStore store;

    ScopedActions(final ScoutroActions actions, final AgentStore store) {
        this.actions = actions;
        this.store = store;
    }

    AgentApi.Response execute(final String action, final String id, final AgentApi.Call call)
            throws ApiException, AgentException {
        final Agent agent = call.agent;
        final Map<String, String> q = call.request.query;
        switch (action) {
            case "search":
                return ok(search(agent, q));
            case "search.network":
                return ok(networkSearch(q));
            case "index.evidence":
                return ok(this.actions.indexEvidence(q, filterCollections(agent, q.get("collection"))));
            case "index.lookup":
                return ok(this.actions.indexLookup(q, filterCollections(agent, q.get("collection"))));
            case "index.status":
                return ok(indexStatus(agent));
            case "index.status.global":
                return ok(this.actions.index());
            case "system.status":
                return ok(this.actions.system());
            case "config.get":
                return ok(this.actions.configGet());
            case "config.set":
                return ok(this.actions.configSet(call.request.body.get()));
            case "crawl.start":
                return crawlStart(call);
            case "crawl.list":
                return ok(crawlList(agent));
            case "crawl.status":
                return ok(crawlStatus(agent, id));
            case "crawl.stop":
                call.request.body.get(); // same cross-site protection as all mutating calls
                return ok(crawlStop(agent, id));
            default:
                throw new ApiException(404, "not_found", "Unknown action '" + action + "'.");
        }
    }

    // ------------------------------------------------------------------
    // scope helpers
    // ------------------------------------------------------------------

    /**
     * Collections a read request is limited to: the requested collection
     * (already checked against the scope) or the complete scope; null means
     * no filter (scope "all collections" without a requested collection).
     */
    static List<String> filterCollections(final Agent agent, final String requested) throws ApiException {
        final String c = requested == null ? null : requested.trim();
        if (c != null && !c.isEmpty()) {
            if (!COLLECTION.matcher(c).matches()) {
                throw ApiException.invalid("collection", "Parameter 'collection' must match [A-Za-z0-9_-]{1,64}.");
            }
            requireInScope(agent, c);
            final List<String> one = new ArrayList<>();
            one.add(c);
            return one;
        }
        return agent.scope.allCollections ? null : new ArrayList<>(agent.scope.collections);
    }

    static void requireInScope(final Agent agent, final String collection) throws ApiException {
        if (!agent.scope.allows(collection)) {
            throw new ApiException(403, "collection_not_in_scope",
                    "The collection '" + collection + "' is outside the data scope of this agent.");
        }
    }

    // ------------------------------------------------------------------
    // search
    // ------------------------------------------------------------------

    private JSONObject search(final Agent agent, final Map<String, String> q) throws ApiException {
        final String source = q.get("source");
        if (source != null && !"local".equals(source)) {
            throw ApiException.invalid("source", "Parameter 'source' must be 'local' here; network search is the "
                    + "separate action search.network (source=network).");
        }
        final String query = q.get("q");
        if (query != null && query.toLowerCase(Locale.ROOT).contains("collection:")) {
            throw new ApiException(400, "query_modifier_not_allowed", "The query must not contain 'collection:'; "
                    + "use the parameter 'collection' with one of your granted collections.");
        }
        final List<String> collections = filterCollections(agent, q.get("collection"));
        final Map<String, String> local = new HashMap<>(q);
        local.remove("collection");
        local.put("source", "local");
        if (collections == null) {
            return this.actions.search(local, null); // complete local index was granted explicitly
        }
        if (collections.size() > MAX_SEARCH_COLLECTIONS) {
            throw ApiException.invalid("collection", "This agent has more than " + MAX_SEARCH_COLLECTIONS
                    + " collections; name one with the parameter 'collection'.");
        }
        if (collections.size() == 1) {
            final JSONObject r = this.actions.search(local, collections.get(0));
            Json.put(r, "collections", new JSONArray(collections));
            return r;
        }
        // YaCy's RWI filter compares a single collection name, so search each collection
        // separately and merge: fetch offset+limit from each, interleave, drop duplicate URLs.
        final int limit = parseInt(q.get("limit"), 10);
        final int offset = parseInt(q.get("offset"), 0);
        final Map<String, String> each = new HashMap<>(local);
        each.put("offset", "0");
        each.put("limit", Integer.toString(Math.min(100, offset + limit)));
        final List<JSONArray> lists = new ArrayList<>();
        long total = 0;
        JSONObject first = null;
        for (final String c : collections) {
            final JSONObject r = this.actions.search(each, c);
            if (first == null) {
                first = r;
            }
            total += r.optLong("total", 0);
            lists.add(r.optJSONArray("results") == null ? new JSONArray() : r.optJSONArray("results"));
        }
        final List<JSONObject> merged = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < 100; i++) {
            boolean any = false;
            for (final JSONArray l : lists) {
                final JSONObject item = l.optJSONObject(i);
                if (item != null) {
                    any = true;
                    if (seen.add(item.optString("url"))) {
                        merged.add(item);
                    }
                }
            }
            if (!any) {
                break;
            }
        }
        final JSONArray page = new JSONArray();
        for (int i = offset; i < Math.min(merged.size(), offset + limit); i++) {
            page.put(merged.get(i));
        }
        return Json.obj("query", first == null ? query : first.opt("query"), "source", "local", "offset", offset,
                "limit", limit, "total", total, "totalIsApproximate", true,
                "collections", new JSONArray(collections), "results", page);
    }

    private JSONObject networkSearch(final Map<String, String> q) throws ApiException {
        final Map<String, String> net = new HashMap<>(q);
        net.remove("collection");
        net.put("source", "network");
        final JSONObject r = this.actions.search(net, null);
        Json.put(r, "scoped", false);
        return r;
    }

    private static int parseInt(final String s, final int dflt) {
        try {
            return s == null ? dflt : Integer.parseInt(s.trim());
        } catch (final NumberFormatException e) {
            return dflt; // ScoutroActions.search validates and reports the parameter
        }
    }

    // ------------------------------------------------------------------
    // index status
    // ------------------------------------------------------------------

    private JSONObject indexStatus(final Agent agent) throws ApiException {
        if (agent.scope.allCollections) {
            return Json.obj("documents", this.actions.countDocuments(null), "allCollections", true);
        }
        final JSONArray list = new JSONArray();
        for (final String c : agent.scope.collections) {
            list.put(Json.obj("name", c, "documents", this.actions.countDocuments(c)));
        }
        return Json.obj("collections", list, "allCollections", false);
    }

    // ------------------------------------------------------------------
    // crawls
    // ------------------------------------------------------------------

    private AgentApi.Response crawlStart(final AgentApi.Call call) throws ApiException, AgentException {
        final Agent agent = call.agent;
        final Agent.Limits limits = agent.limits;
        final JSONObject body = call.request.body.get();
        final String clientRef = call.request.idempotencyKey == null ? null : call.request.idempotencyKey.trim();
        if (clientRef != null && !CLIENT_REF.matcher(clientRef).matches()) {
            throw ApiException.invalid("Idempotency-Key", "The Idempotency-Key header must match [A-Za-z0-9_.:-]{1,100}.");
        }
        for (final String key : body.keySet()) {
            if (!List.of("url", "depth", "maxPages", "collection", "scope").contains(key)) {
                throw ApiException.invalid(key, "Unknown field '" + key + "'. Allowed fields: url, depth, maxPages, "
                        + "collection, scope.");
            }
        }
        final Object collectionValue = body.opt("collection");
        if (!(collectionValue instanceof String) || !COLLECTION.matcher((String) collectionValue).matches()) {
            throw ApiException.invalid("collection", "Field 'collection' (one of your granted collections) is required.");
        }
        final String collection = (String) collectionValue;
        requireInScope(agent, collection);
        final Object urlValue = body.opt("url");
        if (!(urlValue instanceof String)) {
            throw ApiException.invalid("url", "Field 'url' (an http or https URL) is required.");
        }
        final String url = ScoutroActions.validateHttpUrl((String) urlValue, "url");
        final String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        if (!limits.allowsHost(host)) {
            throw new ApiException(403, "limit_exceeded:domains", "The host '" + host
                    + "' is not on the domain allowlist of this agent.");
        }
        final Object scopeValue = body.opt("scope");
        final String scope = scopeValue == null || scopeValue == JSONObject.NULL ? "domain" : String.valueOf(scopeValue);
        if (!"domain".equals(scope) && !"subpath".equals(scope)) {
            throw ApiException.invalid("scope", "Field 'scope' must be 'domain' or 'subpath'; agents cannot start wide crawls.");
        }
        final int depth = intField(body, "depth", Math.min(ScoutroActions.DEFAULT_DEPTH, limits.maxDepth));
        if (depth < 0 || depth > limits.maxDepth) {
            throw new ApiException(403, "limit_exceeded:maxDepth", "Field 'depth' must be between 0 and "
                    + limits.maxDepth + " for this agent.");
        }
        final int maxPages = intField(body, "maxPages", limits.maxPages);
        if (maxPages < 1 || maxPages > limits.maxPages) {
            throw new ApiException(403, "limit_exceeded:maxPages", "Field 'maxPages' must be between 1 and "
                    + limits.maxPages + " for this agent.");
        }

        synchronized (CRAWL_LOCK) {
            final CrawlRecord existing = this.store.crawlByClientRef(agent.id, clientRef);
            if (existing != null) {
                final JSONObject crawl = describe(existing, this.actions.loadCrawls());
                Json.put(crawl, "idempotentReplay", true);
                return new AgentApi.Response(200, crawl);
            }
            final Map<String, JSONObject> all = this.actions.loadCrawls();
            int running = 0;
            for (final CrawlRecord own : this.store.crawls(agent.id)) {
                final JSONObject c = all.get(own.crawlId);
                if (c != null && !"terminated".equals(c.optString("state"))) {
                    running++;
                }
            }
            if (running >= limits.maxParallelCrawls) {
                throw new ApiException(429, "limit_exceeded:maxParallelCrawls", "This agent already runs " + running
                        + " crawl(s); at most " + limits.maxParallelCrawls + " may run at the same time.");
            }
            // YaCy's crawl start drops queued URLs of the same host from other crawls,
            // so an agent must not start a crawl on a host a foreign crawl is busy with.
            final Set<String> ownIds = new LinkedHashSet<>();
            for (final CrawlRecord own : this.store.crawls(agent.id)) {
                ownIds.add(own.crawlId);
            }
            for (final Map.Entry<String, JSONObject> e : all.entrySet()) {
                if (ownIds.contains(e.getKey()) || "terminated".equals(e.getValue().optString("state"))) {
                    continue;
                }
                if (sameHost(host, crawlHost(e.getKey(), e.getValue()))) {
                    throw new ApiException(409, "host_busy", "Another crawl is running on '" + host
                            + "'; start this crawl when it has finished.");
                }
            }
            final JSONObject start = Json.obj("url", url, "depth", depth, "scope", scope, "maxPages", maxPages,
                    "collection", collection);
            final JSONObject created = this.actions.crawlStart(start);
            final String crawlId = created.optString("id");
            try {
                this.store.recordCrawl(new CrawlRecord(crawlId, agent.id, collection, host, clientRef, this.store.now()));
            } catch (final IOException e) {
                throw new ApiException(500, "agent_store_unavailable",
                        "The crawl was started (id " + crawlId + ") but its ownership could not be stored.");
            }
            agentLinks(created);
            final AgentApi.Response resp = new AgentApi.Response(201, created);
            resp.headers.put("Location", AgentActionRegistry.BASE_PATH + "/crawls/" + crawlId);
            return resp;
        }
    }

    private JSONObject crawlList(final Agent agent) throws ApiException {
        final Map<String, JSONObject> all = this.actions.loadCrawls();
        final JSONArray out = new JSONArray();
        final List<CrawlRecord> own = this.store.crawls(agent.id);
        for (int i = own.size() - 1; i >= 0 && out.length() < 100; i--) {
            out.put(describe(own.get(i), all));
        }
        return Json.obj("crawls", out);
    }

    private JSONObject crawlStatus(final Agent agent, final String id) throws ApiException {
        ScoutroActions.checkCrawlId(id);
        return describe(requireOwn(agent, id), this.actions.loadCrawls());
    }

    private JSONObject crawlStop(final Agent agent, final String id) throws ApiException {
        ScoutroActions.checkCrawlId(id);
        requireOwn(agent, id);
        return this.actions.crawlStop(id);
    }

    /** Foreign or unknown crawl ids are reported as not found, so ids cannot be probed. */
    private CrawlRecord requireOwn(final Agent agent, final String id) throws ApiException {
        final CrawlRecord own = this.store.crawl(agent.id, id);
        if (own == null) {
            throw new ApiException(404, "crawl_not_found", "There is no crawl with the id '" + id + "'.",
                    Json.obj("id", id));
        }
        return own;
    }

    /** YaCy's view of an own crawl, or its ownership record when YaCy has removed the profile. */
    private static JSONObject describe(final CrawlRecord own, final Map<String, JSONObject> all) {
        final JSONObject live = all.get(own.crawlId);
        final JSONObject out;
        if (live != null) {
            out = Json.obj();
            for (final String key : live.keySet()) {
                Json.put(out, key, live.opt(key));
            }
        } else {
            out = Json.obj("id", own.crawlId, "state", "removed", "collections", new JSONArray().put(own.collection));
        }
        Json.put(out, "host", own.host);
        Json.put(out, "collection", own.collection);
        Json.put(out, "startedAt", AgentApi.iso(own.createdAt));
        if (!own.clientRef.isEmpty()) {
            Json.put(out, "clientRef", own.clientRef);
        }
        agentLinks(out);
        return out;
    }

    private static void agentLinks(final JSONObject crawl) {
        final String id = crawl.optString("id");
        Json.put(crawl, "links", Json.obj("self", AgentActionRegistry.BASE_PATH + "/crawls/" + id,
                "stop", AgentActionRegistry.BASE_PATH + "/crawls/" + id + "/stop"));
    }

    /** Best-effort host of a crawl profile: its name is the start host or URL for site crawls. */
    static String crawlHost(final String id, final JSONObject crawl) {
        final String name = crawl.optString("name", "").trim().toLowerCase(Locale.ROOT);
        if (name.contains("://")) {
            try {
                final String h = URI.create(name).getHost();
                return h == null ? name : h;
            } catch (final IllegalArgumentException e) {
                return name;
            }
        }
        final int slash = name.indexOf('/');
        return slash > 0 ? name.substring(0, slash) : name;
    }

    static boolean sameHost(final String a, final String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        final String x = a.startsWith("www.") ? a.substring(4) : a;
        final String y = b.startsWith("www.") ? b.substring(4) : b;
        return x.equals(y);
    }

    private static int intField(final JSONObject body, final String name, final int dflt) throws ApiException {
        final Object v = body.opt(name);
        if (v == null || v == JSONObject.NULL) {
            return dflt;
        }
        if (!(v instanceof Number) || ((Number) v).doubleValue() != Math.rint(((Number) v).doubleValue())) {
            throw ApiException.invalid(name, "Field '" + name + "' must be an integer.");
        }
        return ((Number) v).intValue();
    }

    private static AgentApi.Response ok(final JSONObject body) {
        return new AgentApi.Response(200, body);
    }
}
