/*
 *  ScoutroActions
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.net.URISyntaxException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import net.yacy.scoutro.agents.CrawlRecord;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.document.parser.html.CharacterCoding;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;

/**
 * The Scoutro actions. Every action translates the stable Scoutro request
 * model into calls of existing YaCy functions (HTTP APIs of this peer) and
 * translates the answers into the stable Scoutro response model. No crawler,
 * index or search logic is implemented here.
 */
final class ScoutroActions {

    static final String API_VERSION = "1";

    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-API");

    private static final Pattern CRAWL_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static final Pattern COLLECTION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?");
    /**
     * A DNS name with at least two labels, lower case, whose last label (TLD)
     * starts with a letter; so no IP literals, ports, wildcards or query syntax.
     */
    private static final Pattern DOMAIN = Pattern.compile(
            "(?=.{3,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]");

    static final int EVIDENCE_DEFAULT_LIMIT = 8;
    static final int EVIDENCE_MAX_LIMIT = 20;
    static final int EVIDENCE_DEFAULT_CHARS = 1500;
    static final int EVIDENCE_MIN_CHARS = 100;
    static final int EVIDENCE_MAX_CHARS = 4000;

    static final int MAX_DEPTH = 10;
    static final int DEFAULT_DEPTH = 2;
    static final int MAX_PAGES = 1_000_000;
    static final int MAX_QUERY_LENGTH = 200;
    static final int MAX_URL_LENGTH = 2048;

    /** Crawl starts are serialized so that the new crawl profile can be identified reliably. */
    static final Object CRAWL_START_LOCK = new Object();

    private final Upstream yacy;
    private final CrawlLedger crawlLedger;
    private final CollectionCatalog catalog;

    ScoutroActions() {
        this(new YaCyLoopback());
    }

    ScoutroActions(final Upstream upstream) {
        this(upstream, null);
    }

    /** With a catalog of its own (tests); null: the running peer's, or one in memory for a test transport. */
    ScoutroActions(final Upstream upstream, final CollectionCatalog catalog) {
        this.yacy = upstream;
        this.crawlLedger = new CrawlLedger(upstream::crawlMetadataPath);
        // the running peer shares one catalog with its pages and the chat; a test transport keeps its own in memory
        this.catalog = catalog != null ? catalog : upstream instanceof YaCyLoopback ? CollectionCatalog.current()
                : new CollectionCatalog(null, this::indexCollectionCounts, java.util.List::of);
    }

    CollectionCatalog catalog() {
        return this.catalog;
    }

    // ------------------------------------------------------------------
    // health, system, version
    // ------------------------------------------------------------------

    /** health: public, cheap, confirms that YaCy answers its own public API. */
    JSONObject health() throws ApiException {
        final Document version;
        try {
            version = this.yacy.getPublicXml("api/version.xml", null);
        } catch (final ApiException e) {
            throw new ApiException(503, "unavailable", "YaCy does not answer: " + e.getMessage());
        }
        return Json.obj(
                "status", "ok",
                "service", "scoutro",
                "apiVersion", API_VERSION,
                "version", versionJson(version));
    }

    /** system.status: versions, peer, resources and crawler queue state. */
    JSONObject system() throws ApiException {
        final Switchboard sb = switchboard();
        final Document status = this.yacy.getAdminXml("api/status_p.xml", null);
        final Document version = this.yacy.getPublicXml("api/version.xml", null);
        return Json.obj(
                "version", versionJson(version),
                "peer", Json.obj(
                        "name", sb.peers == null || sb.peers.mySeed() == null ? "" : sb.peers.mySeed().getName(),
                        "network", sb.getConfig(SwitchboardConstants.NETWORK_NAME, "")),
                "resources", Json.obj(
                        "processors", longOrNull(text(status, "processors")),
                        "load", doubleOrNull(text(status, "load")),
                        "memory", Json.obj(
                                "usedBytes", longOrNull(text(status, "memory", "used")),
                                "freeBytes", longOrNull(text(status, "memory", "free")),
                                "totalBytes", longOrNull(text(status, "memory", "total")),
                                "maxBytes", longOrNull(text(status, "memory", "max"))),
                        "disk", Json.obj(
                                "usedBytes", longOrNull(text(status, "disk", "used")),
                                "freeBytes", longOrNull(text(status, "disk", "free")))),
                "crawler", crawlerQueues(status),
                "pagesPerMinute", longOrNull(text(status, "ppm")));
    }

    private static JSONObject versionJson(final Document version) {
        final Properties scoutro = scoutroProperties();
        final String upstream = scoutro.getProperty("scoutro.upstream.version", text(version, "buildVersion"));
        final String release = scoutro.getProperty("scoutro.release", "");
        return Json.obj(
                "scoutro", release.isEmpty() ? null : upstream + "-scoutro." + release,
                "yacy", text(version, "buildVersion"),
                "build", text(version, "file"));
    }

    static Properties scoutroProperties() {
        final Properties p = new Properties();
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null) {
            return p;
        }
        final File file = new File(sb.getAppPath(), "scoutro.properties");
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                p.load(in);
            } catch (final IOException e) {
                LOG.warn("cannot read scoutro.properties: " + e.getMessage());
            }
        }
        return p;
    }

    // ------------------------------------------------------------------
    // search
    // ------------------------------------------------------------------

    /** search: yacysearch.json with a small, validated parameter set. */
    JSONObject search(final Map<String, String> query) throws ApiException {
        return search(query, null);
    }

    /**
     * search, optionally restricted to one collection. The collection is sent
     * as the request parameter "collection", which YaCy applies after parsing
     * the query and which overrides any "collection:" written in the query
     * (yacysearch.java). Used for agents; null means no restriction.
     */
    JSONObject search(final Map<String, String> query, final String collection) throws ApiException {
        final String q = trimToNull(query.get("q"));
        if (q == null) {
            throw ApiException.invalid("q", "Parameter 'q' (the search query) is required.");
        }
        if (q.length() > MAX_QUERY_LENGTH) {
            throw ApiException.invalid("q", "Parameter 'q' must not be longer than " + MAX_QUERY_LENGTH + " characters.");
        }
        final int limit = intParam(query, "limit", 10, 1, 100);
        final int offset = intParam(query, "offset", 0, 0, 10_000);
        final String source = enumParam(query, "source", "local", "local", "network");
        final String lang = trimToNull(query.get("lang"));
        if (lang != null && !LANGUAGE.matcher(lang).matches()) {
            throw ApiException.invalid("lang", "Parameter 'lang' must be a two-letter language code such as 'de'.");
        }

        final String body = this.yacy.getAdmin("yacysearch.json", new YaCyLoopback.Params()
                .add("query", q)
                .add("maximumRecords", limit)
                .add("startRecord", offset)
                .add("resource", "network".equals(source) ? "global" : "local")
                .add("contentdom", "text")
                .add("nav", "none")
                .add("lr", lang == null ? null : "lang_" + lang)
                .add("collection", collection));
        final JSONObject json = Json.parseUpstream(body, "yacysearch.json");
        final JSONArray channels = json.optJSONArray("channels");
        final JSONObject channel = channels == null ? null : channels.optJSONObject(0);
        final JSONArray results = Json.arr();
        long total = 0;
        if (channel != null) {
            total = parseLong(channel.optString("totalResults", "0"), 0);
            final JSONArray items = channel.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    final JSONObject item = items.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    results.put(Json.obj(
                            "title", plain(item.optString("title", "")),
                            "url", item.optString("link", ""),
                            "snippet", plain(item.optString("description", "")),
                            "host", item.optString("host", ""),
                            "date", isoDate(item.optString("pubDate", "")),
                            "sizeBytes", longOrNull(item.optString("size", "")),
                            "id", item.optString("guid", "")));
                }
            }
        }
        return Json.obj(
                "query", q,
                "source", source,
                "offset", offset,
                "limit", limit,
                "total", total,
                "results", results);
    }

    // ------------------------------------------------------------------
    // index
    // ------------------------------------------------------------------

    /** index.status: document and link counts plus crawler queues (api/status_p.xml). */
    JSONObject index() throws ApiException {
        final Document status = this.yacy.getAdminXml("api/status_p.xml", null);
        return Json.obj(
                "documents", longOrNull(text(status, "dbsize", "urlpublictext")),
                "webgraphEdges", longOrNull(text(status, "dbsize", "webgraph")),
                "citations", longOrNull(text(status, "dbsize", "citation")),
                "rwiWords", longOrNull(text(status, "dbsize", "rwipublictext")),
                "crawler", crawlerQueues(status),
                "postprocessing", Json.obj(
                        "status", text(status, "postprocessing", "status"),
                        "remaining", longOrNull(text(status, "postprocessing", "collectionRemainingCount"))));
    }

    JSONObject hostResolve(final Map<String, String> query, final List<String> collections) throws ApiException {
        for (final String key : query.keySet()) if (!List.of("input", "collection").contains(key)) throw ApiException.invalid(key, "Unknown host parameter.");
        final HostInput input = HostInput.parse(query.get("input"));
        final JSONObject data = Json.parseUpstream(this.yacy.getAdmin("solr/select", new YaCyLoopback.Params()
                .add("q", "host_s:" + phrase(input.host)).add("fq", collectionFilter(collections))
                .add("defType", "lucene").add("rows", 0).add("wt", "json").add("timeAllowed", 2000)), "solr/select");
        final JSONObject header = data.optJSONObject("responseHeader");
        if (header != null && !header.isNull("partialResults") && !"false".equals(String.valueOf(header.opt("partialResults"))))
            throw new ApiException(503, "index_unavailable", "The index lookup was incomplete; indexing status is unknown.");
        final JSONObject response = data.optJSONObject("response");
        if (response == null || !(response.opt("numFound") instanceof Number)) throw new ApiException(503, "index_unavailable", "The index response is incomplete.");
        final long records = response.optLong("numFound");
        return Json.obj("host", input.host, "url", input.url, "indexed", records > 0, "analysisAvailable", records > 0,
                "visibleRecords", records, "collection", query.get("collection"),
                "crawl", Json.obj("collectionRequired", true, "canRequest", true, "blockedReason", null),
                "links", Json.obj("analysis", "/ScoutroSEO_p.html?host=" + input.host + collectionQuery(query),
                        "newCrawl", "/ScoutroCrawls_p.html?url=" + java.net.URLEncoder.encode(input.url, java.nio.charset.StandardCharsets.UTF_8) + collectionQuery(query)));
    }

    private static String collectionQuery(final Map<String, String> query) {
        final String collection = query.get("collection");
        return collection == null || collection.isBlank() ? "" : "&collection=" + java.net.URLEncoder.encode(collection.trim(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The collections of the index with their document counts (Solr facet). */
    Map<String, Long> indexCollectionCounts() throws ApiException {
        return CollectionCatalog.facetCounts(Json.parseUpstream(this.yacy.getAdmin("solr/select", new YaCyLoopback.Params()
                .add("q", "*:*").add("rows", 0).add("wt", "json").add("facet", "true")
                .add("facet.field", "collection_sxt").add("facet.limit", 500).add("facet.mincount", 1)), "solr/select"));
    }

    /** collections.list for the administrator. */
    JSONObject collections() throws ApiException {
        return collections(true);
    }

    /**
     * collections.list: every collection of the catalog with name, description,
     * documents, sources and what it may be used for; YaCy's internal ones are
     * listed as such and never selectable. The administrator also gets the
     * knowledge graph's view of each collection and may create one
     * ({@code canCreate}); nobody may enter a new name elsewhere ({@code allowNew}).
     */
    JSONObject collections(final boolean administrator) throws ApiException {
        final Map<String, JSONObject> graph = new java.util.HashMap<>();
        final net.yacy.scoutro.knowledge.KgRuntime kg = administrator ? net.yacy.scoutro.knowledge.KgRuntime.current() : null;
        if (kg != null) {
            try {
                graph.putAll(kg.collectionStates());
            } catch (final RuntimeException e) {
                // the catalog does not depend on the graph
            }
        }
        final JSONArray items = new JSONArray();
        for (final CollectionCatalog.Entry e : this.catalog.entries(true)) {
            if (e.hidden && !administrator) continue; // a hidden collection is the administrator's business only (0.10.3)
            final JSONObject o = e.json();
            final JSONObject g = graph.get(e.id);
            if (administrator) Json.put(o, "graph", g == null || e.internal() ? null : Json.obj("followed", g.optBoolean("followed"),
                    "vocabulary", g.opt("vocabulary"), "vocabularySource", g.opt("vocabularySource"), "jobs", g.optBoolean("jobs"),
                    "llm", g.optBoolean("llm"), "state", g.opt("state")));
            items.put(o);
        }
        return Json.obj("collections", items, "allowNew", false, "canCreate", administrator, "limit", 500);
    }

    /** collections.create (administrator): a new, empty collection that every choice offers at once. */
    JSONObject collectionCreate(final JSONObject body) throws ApiException {
        return Json.obj("collection", this.catalog.create(body).json(), "created", true);
    }

    /**
     * collections.remove (administrator): removes a collection created here that is empty and that no Discovery
     * profile, account, agent, guest access or running crawl names ({@link CollectionCatalog#remove}). Nothing in the
     * index or another store is deleted.
     */
    JSONObject collectionRemove(final String id) throws ApiException {
        // a collection that collection decisions put pages into is never removed: their pages would have no collection
        return Json.obj("collection", this.catalog.remove(id, c -> withDecisions(c, this.uses.of(c))).json(), "removed", true);
    }

    /** {@code uses} with the active collection decisions that name {@code id} ({@code decisions}). */
    private Map<String, Integer> withDecisions(final String id, final Map<String, Integer> uses) {
        final int n = this.decisionUses.applyAsInt(id);
        if (n == 0) return uses;
        final Map<String, Integer> out = new java.util.TreeMap<>(uses);
        out.merge("decisions", n, Integer::sum);
        return out;
    }

    /** The active collection decisions that name a collection; the running peer's unless a test sets its own. */
    java.util.function.ToIntFunction<String> decisionUses = ScoutroActions::activeDecisions;

    static int activeDecisions(final String id) {
        final net.yacy.scoutro.collections.CollectionDecisions decisions = net.yacy.scoutro.collections.CollectionDecisions.current();
        int n = 0;
        if (decisions != null) {
            for (final net.yacy.scoutro.collections.CollectionDecision d : decisions.all()) {
                if (d.active() && d.collections.contains(id)) n++;
            }
        }
        return n;
    }

    /**
     * The knowledge graph's switch of a collection (package 6.2, {@code scoutro.kg.collections.inactive}) as hiding uses it
     * (0.10.3): off keeps the graph data and extracts nothing new; true if the graph followed the collection before.
     */
    interface GraphSwitch {
        boolean off(String id) throws ApiException;

        void on(String id) throws ApiException;
    }

    /** The running peer's knowledge graph settings; nothing where the graph does not follow the collection. */
    static final GraphSwitch KG_SWITCH = new GraphSwitch() {
        @Override
        public boolean off(final String id) throws ApiException {
            final KgCollectionSettings s = KgCollectionSettings.current();
            if (s == null || !s.follows(id)) return false;
            s.update(id, Json.obj("active", false));
            return true;
        }

        @Override
        public void on(final String id) throws ApiException {
            final KgCollectionSettings s = KgCollectionSettings.current();
            if (s != null && s.holds(id)) s.update(id, Json.obj("active", true));
        }
    };

    GraphSwitch graphSwitch = KG_SWITCH;
    /** Who names a collection; the running peer's stores unless a test sets its own. */
    CollectionCatalog.Uses uses = this::collectionUses;

    /**
     * collections.hide (administrator, 0.10.3): the collection leaves every choice and the dashboard, the knowledge
     * graph stops reading it (its data stay), its pages stay in the index ({@link CollectionCatalog#hide}). Refused
     * while a Discovery profile, an account, agent, guest access or running crawl names it.
     */
    JSONObject collectionHide(final String id) throws ApiException {
        final CollectionCatalog.Outcome o = this.catalog.hide(id, this.uses);
        return Json.put(o.json(), "graph", switchGraphOff(id));
    }

    /**
     * collections.delete-pages (administrator, 0.10.3): deletes the pages of the collection that belong to no other
     * collection, confirmed with its id; without pages left it goes from the lists, otherwise it is hidden
     * ({@link CollectionCatalog#deletePages}). Irreversible.
     */
    JSONObject collectionDeletePages(final String id, final JSONObject body) throws ApiException {
        for (final String key : body.keySet()) {
            if (!"confirm".equals(key)) throw ApiException.invalid(key, "Unknown field.");
        }
        final Object confirm = body.opt("confirm");
        // decisions keep their pages coming into the collection: it may lose its own pages, but not leave the lists
        final CollectionCatalog.Outcome o = this.catalog.deletePages(id, confirm instanceof String ? (String) confirm : null, this.uses,
                this.decisionUses.applyAsInt(id));
        return Json.put(o.json(), "graph", o.hidden ? switchGraphOff(id) : null);
    }

    /** collections.show (administrator, 0.10.3): a hidden collection is offered again; the graph reads it again if hiding switched it off. */
    JSONObject collectionShow(final String id) throws ApiException {
        final JSONObject record = this.catalog.show(id);
        JSONObject graph = Json.obj("switchedOn", false);
        if (record.optBoolean("graphSwitchedOff")) {
            try {
                this.graphSwitch.on(id);
                graph = Json.obj("switchedOn", true);
            } catch (final ApiException e) {
                graph = Json.obj("switchedOn", false, "error", e.code());
            }
        }
        CollectionCatalog.Entry shown = null;
        for (final CollectionCatalog.Entry e : this.catalog.entries(false)) {
            if (e.id.equals(id)) shown = e;
        }
        return Json.obj("collection", shown == null ? Json.obj("id", id) : shown.json(), "shown", true, "graph", graph);
    }

    /** Switches the graph off for a hidden collection; a refusal (backup running, write refused) is reported, the collection stays hidden. */
    private JSONObject switchGraphOff(final String id) throws ApiException {
        try {
            final boolean off = this.graphSwitch.off(id);
            if (off) this.catalog.hiddenSwitchedGraphOff(id);
            return Json.obj("switchedOff", off);
        } catch (final ApiException e) {
            return Json.obj("switchedOff", false, "error", e.code());
        }
    }

    /** What names a collection explicitly on the running peer; a store that cannot be read refuses the removal. */
    private Map<String, Integer> collectionUses(final String id) throws ApiException {
        final List<net.yacy.scoutro.access.Scope> accounts = new ArrayList<>();
        List<String> guest = List.of();
        final net.yacy.scoutro.access.ScoutroAccess access = net.yacy.scoutro.access.ScoutroAccess.current();
        if (access == null && net.yacy.search.Switchboard.getSwitchboard() != null) {
            // the account file cannot be read (sign-in is off, Digest still works): accounts and guest access stay unknown
            throw new ApiException(503, "collection_uses_unavailable", "It cannot be checked whether accounts or the guest access use this collection; nothing was removed.");
        }
        if (access != null) {
            for (final net.yacy.scoutro.access.Account a : access.accounts.list()) accounts.add(a.scope());
            guest = access.settings.guestCollections();
        }
        final List<net.yacy.scoutro.agents.Agent> agents;
        try {
            agents = net.yacy.scoutro.agents.ScoutroAgents.get().store.agents();
        } catch (final net.yacy.scoutro.agents.AgentException e) {
            throw new ApiException(503, "collection_uses_unavailable", "It cannot be checked whether agents use this collection; nothing was removed.");
        }
        // collection decisions are no use that hiding is refused for (the pages stay, decisions go on applying); removing
        // a collection and deleting all its pages check them ({@link #collectionRemove}, {@link #collectionDeletePages})
        return collectionUses(id, accounts, agents, guest, loadCrawls().values());
    }

    /**
     * Who or what names a collection explicitly, as kind → count: accounts and agents with the collection in their
     * scope (a grant of all collections names none; revoked agents never act again), the guest access, and crawls
     * that are not terminated (the push API's profile of a collection is no crawl). Knowledge-graph settings do not
     * count: they name a collection without granting anything, stay as they are and apply again if the collection is
     * created anew.
     */
    static Map<String, Integer> collectionUses(final String id, final java.util.Collection<net.yacy.scoutro.access.Scope> accounts,
            final java.util.Collection<net.yacy.scoutro.agents.Agent> agents, final java.util.Collection<String> guest,
            final java.util.Collection<JSONObject> crawls) {
        final Map<String, Integer> out = new LinkedHashMap<>();
        for (final net.yacy.scoutro.access.Scope s : accounts) {
            if (!s.isAll() && s.collections().contains(id)) out.merge("accounts", 1, Integer::sum);
        }
        for (final net.yacy.scoutro.agents.Agent a : agents) {
            if (a.status != net.yacy.scoutro.agents.Agent.Status.REVOKED && !a.scope.allCollections && a.scope.collections.contains(id)) {
                out.merge("agents", 1, Integer::sum);
            }
        }
        if (guest.contains(id)) out.put("guest", 1);
        for (final JSONObject crawl : crawls) {
            final JSONArray names = crawl.optJSONArray("collections");
            if ("terminated".equals(crawl.optString("state")) || names == null) continue;
            // the push API's profile of a collection (push_<collection>) crawls nothing and never ends (0.10.3)
            if (crawl.optString("name").startsWith(net.yacy.crawler.data.CrawlProfile.CRAWL_PROFILE_PUSH_STUB)) continue;
            for (int i = 0; i < names.length(); i++) {
                if (id.equals(names.optString(i))) {
                    out.merge("crawls", 1, Integer::sum);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * collections.reassign (administrator): preview, or apply with the preview's token, a move of one domain's
     * pages between collections without crawling them again ({@link CollectionReassign}).
     */
    JSONObject collectionReassign(final JSONObject body) throws ApiException {
        final java.util.Set<String> crawling = new java.util.HashSet<>();
        for (final JSONObject crawl : loadCrawls().values()) {
            if ("terminated".equals(crawl.optString("state"))) continue;
            String host = crawl.optString("host", "");
            String name = crawl.optString("name", "");
            try {
                if (name.contains("://")) name = new java.net.URI(name).getHost();
            } catch (final Exception e) {
                name = "";
            }
            if (!host.isEmpty()) crawling.add(host.toLowerCase(java.util.Locale.ROOT));
            if (name != null && !name.isEmpty()) crawling.add(name.toLowerCase(java.util.Locale.ROOT));
        }
        return CollectionReassign.current(this.catalog).run(body, crawling::contains);
    }

    /**
     * collections.decisions (administrator): list, decide (preview / store and apply), apply, revoke, recover
     * ({@link CollectionDecisionsApi}, docs/SCOUTRO_COLLECTION_DECISIONS.md).
     */
    JSONObject collectionDecisions(final String action, final Map<String, String> query, final JSONObject body, final String actor)
            throws ApiException {
        final net.yacy.scoutro.collections.CollectionDecisions decisions = net.yacy.scoutro.collections.CollectionDecisions.current();
        if (decisions == null) throw new ApiException(503, "decisions_unavailable", "Collection decisions need the running peer.");
        final CollectionDecisionsApi api = new CollectionDecisionsApi(decisions,
                new DecisionApply(CollectionReassign.embeddedIndex(), decisions.fallback(), graphFollows()), id -> {
                    try {
                        return this.catalog.known(id);
                    } catch (final ApiException e) {
                        return false;
                    }
                });
        switch (action) {
            case "list": return api.list(query);
            case "decide": return api.decide(body, actor);
            case "apply": return api.resume(body);
            case "revoke": return api.revoke(body, actor);
            case "recover": return api.recover(body, actor);
            default: throw new ApiException(404, "not_found", "Unknown collection decision route.");
        }
    }

    private static volatile net.yacy.scoutro.collections.AutoCorrect autocorrect;

    private static synchronized net.yacy.scoutro.collections.AutoCorrect autocorrect() throws ApiException {
        if (autocorrect == null) {
            final net.yacy.search.Switchboard sb = net.yacy.search.Switchboard.getSwitchboard();
            if (sb == null) throw new ApiException(503, "autocorrect_unavailable", "The correction automation needs the running peer.");
            autocorrect = new net.yacy.scoutro.collections.AutoCorrect(sb.getDataPath().toPath().resolve("DATA").resolve("SCOUTRO")
                    .resolve("collections").resolve("autocorrect.json").toFile(), System::currentTimeMillis);
        }
        return autocorrect;
    }

    /** The correction automation (WP-H, docs/SCOUTRO_COLLECTION_AUTOCORRECT.md); administrator only, off by default. */
    JSONObject collectionAutocorrect(final String action, final Map<String, String> query, final JSONObject body, final String actor)
            throws ApiException {
        final net.yacy.scoutro.collections.CollectionDecisions decisions = net.yacy.scoutro.collections.CollectionDecisions.current();
        if (decisions == null) throw new ApiException(503, "decisions_unavailable", "Collection decisions need the running peer.");
        final java.util.function.Predicate<String> known = id -> {
            try {
                return this.catalog.known(id);
            } catch (final ApiException e) {
                return false;
            }
        };
        final CollectionDecisionsApi decide = new CollectionDecisionsApi(decisions,
                new DecisionApply(CollectionReassign.embeddedIndex(), decisions.fallback(), graphFollows()), known);
        final net.yacy.search.Switchboard sb = net.yacy.search.Switchboard.getSwitchboard();
        final net.yacy.scoutro.knowledge.KgRuntime kg = net.yacy.scoutro.knowledge.KgRuntime.current();
        final AutoCorrectApi.Graph graph = kg == null ? null : new AutoCorrectApi.Graph() {
            private java.util.List<String> ids(final String current) {
                final java.util.List<String> ids = new java.util.ArrayList<>();
                try {
                    for (final CollectionCatalog.Entry e : ScoutroActions.this.catalog.entries(false)) ids.add(e.id);
                } catch (final ApiException e) {
                    return null; // no catalog, no graph signal
                }
                if (!ids.contains(current)) ids.add(current);
                return ids;
            }

            @Override
            public java.util.List<net.yacy.scoutro.collections.AutoCorrect.Signal> structured(final String domain, final String current) {
                final java.util.List<String> ids = ids(current);
                return ids == null ? java.util.List.of()
                        : AutoCorrectApi.structuredSignals(domain, current, kg.structuredIndustries(domain), kg.collectionNace(ids));
            }

            @Override
            public java.util.List<net.yacy.scoutro.collections.AutoCorrect.Signal> textFit(final String domain, final String current) {
                final java.util.List<String> ids = ids(current);
                return ids == null ? java.util.List.of() : AutoCorrectApi.textFitSignals(domain, current, kg.textFit(domain, ids));
            }
        };
        final AutoCorrectApi api = new AutoCorrectApi(autocorrect(), decisions, decide, key -> sb == null ? null : sb.getConfig(key, null),
                known, graph);
        switch (action) {
            case "report": return api.report(query);
            case "evaluate": return api.evaluate(body, actor);
            case "review": return api.review(body, actor);
            case "accept": return api.accept(body, actor);
            case "breaker-reset": return api.breakerReset(body, actor);
            case "sample": return api.sample(body, actor);
            default: throw new ApiException(404, "not_found", "Unknown correction automation route.");
        }
    }

    /** The collections the running knowledge graph follows; null if it is off (no graph data to protect). */
    static java.util.function.Predicate<String> graphFollows() {
        final net.yacy.scoutro.knowledge.KgRuntime kg = net.yacy.scoutro.knowledge.KgRuntime.current();
        final net.yacy.scoutro.knowledge.KgConfig cfg = kg == null ? null : kg.config();
        return cfg == null || !cfg.enabled ? null : cfg::follows;
    }

    /** index.lookup: is a URL indexed, or how many documents does a host have (embedded Solr). */
    JSONObject indexLookup(final Map<String, String> query) throws ApiException {
        return indexLookup(query, null);
    }

    /**
     * index.lookup limited to the given collections (null: complete index).
     * Documents outside the collections count as not indexed, and only the
     * given collections are reported for a found document.
     */
    JSONObject indexLookup(final Map<String, String> query, final List<String> collections) throws ApiException {
        final String fq = collectionFilter(collections);
        final String url = trimToNull(query.get("url"));
        final String host = trimToNull(query.get("host"));
        if ((url == null) == (host == null)) {
            throw ApiException.invalid("url", "Give exactly one of the parameters 'url' or 'host'.");
        }
        if (host != null) {
            if (!HOST.matcher(host).matches()) {
                throw ApiException.invalid("host", "Parameter 'host' must be a host name such as 'example.com'.");
            }
            final JSONObject result = solr("host_s:" + phrase(host.toLowerCase(Locale.ROOT)), 0, fq);
            final JSONObject response = result.optJSONObject("response");
            return Json.obj("host", host, "documents", response == null ? 0 : response.optLong("numFound", 0));
        }
        final String normalized = validateHttpUrl(url, "url");
        for (final String candidate : urlVariants(normalized)) {
            final JSONObject result = solr("sku:" + phrase(candidate), 1, fq);
            final JSONObject response = result.optJSONObject("response");
            final JSONArray docs = response == null ? null : response.optJSONArray("docs");
            final JSONObject doc = docs == null ? null : docs.optJSONObject(0);
            if (doc != null) {
                return Json.obj(
                        "url", url,
                        "indexed", true,
                        "document", Json.obj(
                                "url", doc.optString("sku", candidate),
                                "title", firstString(doc.opt("title")),
                                "host", doc.optString("host_s", ""),
                                "lastModified", doc.optString("last_modified", ""),
                                "collections", visibleCollections(doc.optJSONArray("collection_sxt"), collections)));
            }
        }
        return Json.obj("url", url, "indexed", false, "document", null);
    }

    /**
     * Read-only evidence for one domain from the existing full-text index:
     * URL, title and a bounded plain-text excerpt of the indexed page text
     * (Solr text_t). Nothing is fetched from the web and no HTCache is needed,
     * so it works for text-only crawls. The query is built here from validated
     * values only (no Solr syntax from the caller). Page text is returned as
     * untrusted data; callers must never treat it as instructions.
     */
    JSONObject indexEvidence(final Map<String, String> query) throws ApiException {
        return indexEvidence(query, null);
    }

    /**
     * index.evidence; with a non-null collection list the filter is exactly
     * these collections and a 'collection' parameter is ignored (the agent
     * layer has resolved and checked it already).
     */
    JSONObject indexEvidence(final Map<String, String> query, final List<String> scope) throws ApiException {
        final String rawDomain = trimToNull(query.get("domain"));
        if (rawDomain == null) {
            throw ApiException.invalid("domain", "Parameter 'domain' is required, e.g. 'example.com'.");
        }
        final String domain = rawDomain.toLowerCase(Locale.ROOT);
        if (!DOMAIN.matcher(domain).matches()) {
            throw ApiException.invalid("domain", "Parameter 'domain' must be a DNS name such as 'example.com' "
                    + "(no scheme, port, path, IP address or wildcard).");
        }
        final String collection = scope != null ? (scope.size() == 1 ? scope.get(0) : null) : trimToNull(query.get("collection"));
        if (collection != null && !COLLECTION.matcher(collection).matches()) {
            throw ApiException.invalid("collection", "Parameter 'collection' must match [A-Za-z0-9_-]{1,64}.");
        }
        final List<String> filter = scope != null ? scope
                : collection == null ? null : java.util.Collections.singletonList(collection);
        final int limit = intParam(query, "limit", EVIDENCE_DEFAULT_LIMIT, 1, EVIDENCE_MAX_LIMIT);
        final int maxChars = intParam(query, "maxChars", EVIDENCE_DEFAULT_CHARS, EVIDENCE_MIN_CHARS, EVIDENCE_MAX_CHARS);
        final String hostQuery = "host_s:" + phrase(domain)
                + (domain.startsWith("www.") ? "" : " OR host_s:" + phrase("www." + domain));
        final YaCyLoopback.Params params = new YaCyLoopback.Params()
                .add("q", hostQuery)
                .add("defType", "lucene")
                .add("fq", filter == null ? "httpstatus_i:200" : "httpstatus_i:200 AND " + collectionFilter(filter))
                .add("sort", "crawldepth_i asc,sku asc")
                .add("rows", limit)
                .add("wt", "json")
                .add("fl", "sku,title,text_t");
        final JSONObject result = Json.parseUpstream(this.yacy.getAdmin("solr/select", params), "solr/select");
        final JSONObject response = result.optJSONObject("response");
        final JSONArray docs = response == null ? null : response.optJSONArray("docs");
        final JSONArray out = Json.arr();
        if (docs != null) {
            for (int i = 0; i < docs.length(); i++) {
                final JSONObject doc = docs.optJSONObject(i);
                if (doc == null) {
                    continue;
                }
                final String text = excerpt(firstString(doc.opt("text_t")), maxChars);
                out.put(Json.obj(
                        "url", doc.optString("sku", ""),
                        "title", excerpt(firstString(doc.opt("title")), 300),
                        "excerpt", text));
            }
        }
        return Json.obj(
                "domain", domain,
                "collection", collection,
                "collections", filter == null ? null : new JSONArray(filter),
                "total", response == null ? 0 : response.optLong("numFound", 0),
                "limit", limit,
                "maxChars", maxChars,
                "documents", out);
    }

    /** Plain text, whitespace collapsed, control/format characters removed, cut at maxChars. */
    static String excerpt(final String text, final int maxChars) {
        final String clean = CONTROL_CHARS.matcher(text == null ? "" : text).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return clean.length() <= maxChars ? clean : clean.substring(0, maxChars);
    }

    private JSONObject solr(final String q, final int rows, final String fq) throws ApiException {
        final String body = this.yacy.getAdmin("solr/select", new YaCyLoopback.Params()
                .add("q", q)
                .add("fq", fq)
                .add("defType", "lucene")
                .add("rows", rows)
                .add("wt", "json")
                .add("fl", "sku,title,host_s,last_modified,collection_sxt"));
        return Json.parseUpstream(body, "solr/select");
    }

    /** Number of indexed documents (HTTP 200) per collection, for the scoped index status. */
    long countDocuments(final String collection) throws ApiException {
        final String body = this.yacy.getAdmin("solr/select", new YaCyLoopback.Params()
                .add("q", "*:*")
                .add("fq", collection == null ? "httpstatus_i:200"
                        : "httpstatus_i:200 AND " + collectionFilter(java.util.Collections.singletonList(collection)))
                .add("defType", "lucene")
                .add("rows", 0)
                .add("wt", "json"));
        final JSONObject response = Json.parseUpstream(body, "solr/select").optJSONObject("response");
        return response == null ? 0 : response.optLong("numFound", 0);
    }

    /**
     * Number of indexed documents of a host (and www.host) that belong to none
     * of the given collections. Used before an agent crawl: YaCy re-indexes
     * crawled pages with the collection of the new crawl, which would move
     * documents out of collections the agent may not touch.
     */
    long countHostOutside(final String host, final List<String> collections) throws ApiException {
        final String h = host.toLowerCase(Locale.ROOT);
        final String bare = h.startsWith("www.") ? h.substring(4) : h;
        final String body = this.yacy.getAdmin("solr/select", new YaCyLoopback.Params()
                .add("q", "host_s:" + phrase(bare) + " OR host_s:" + phrase("www." + bare))
                .add("fq", "-" + collectionFilter(collections))
                .add("defType", "lucene")
                .add("rows", 0)
                .add("wt", "json"));
        final JSONObject response = Json.parseUpstream(body, "solr/select").optJSONObject("response");
        return response == null ? 0 : response.optLong("numFound", 0);
    }

    /** Solr filter on collection_sxt for validated collection names; null for no restriction. */
    static String collectionFilter(final List<String> collections) {
        if (collections == null) {
            return null;
        }
        if (collections.isEmpty()) {
            return "-*:*"; // an empty scope matches nothing
        }
        final StringBuilder sb = new StringBuilder("(");
        for (final String c : collections) {
            if (!COLLECTION.matcher(c).matches()) {
                throw new IllegalArgumentException("invalid collection name");
            }
            if (sb.length() > 1) {
                sb.append(" OR ");
            }
            sb.append("collection_sxt:").append(phrase(c));
        }
        return sb.append(')').toString();
    }

    private static JSONArray visibleCollections(final JSONArray docCollections, final List<String> scope) {
        final JSONArray out = Json.arr();
        if (docCollections == null) {
            return out;
        }
        for (int i = 0; i < docCollections.length(); i++) {
            final String c = docCollections.optString(i, "");
            if (!c.isEmpty() && (scope == null || scope.contains(c))) {
                out.put(c);
            }
        }
        return out;
    }

    static String phrase(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Iterable<String> urlVariants(final String url) {
        final Set<String> variants = new java.util.LinkedHashSet<>();
        variants.add(url);
        variants.add(url.endsWith("/") ? url.substring(0, url.length() - 1) : url + "/");
        return variants;
    }

    // ------------------------------------------------------------------
    // configuration (explicit allowlist only)
    // ------------------------------------------------------------------

    /** A setting that agents may read and change: Scoutro name, YaCy key, validation. */
    private static final class Setting {
        final String yacyKey;
        final String type;
        final String description;
        final int min;
        final int max;

        Setting(final String yacyKey, final String type, final String description, final int min, final int max) {
            this.yacyKey = yacyKey;
            this.type = type;
            this.description = description;
            this.min = min;
            this.max = max;
        }
    }

    private static final Map<String, Setting> SETTINGS = new LinkedHashMap<>();
    static {
        SETTINGS.put("search.greeting", new Setting(SwitchboardConstants.GREETING, "string",
                "Greeting shown below the logo on the search start page.", 0, 120));
        SETTINGS.put("search.itemsPerPage", new Setting("search.items", "integer",
                "Default number of results per page on the search page.", 1, 100));
    }

    JSONObject configGet() throws ApiException {
        final Switchboard sb = switchboard();
        final JSONObject settings = new JSONObject();
        for (final Map.Entry<String, Setting> e : SETTINGS.entrySet()) {
            final Setting s = e.getValue();
            final String raw = sb.getConfig(s.yacyKey, "");
            Json.put(settings, e.getKey(), Json.obj(
                    "value", "integer".equals(s.type) ? longOrNull(raw) : raw,
                    "type", s.type,
                    "description", s.description,
                    "writable", true));
        }
        return Json.obj("settings", settings);
    }

    /** config.set: all-or-nothing update of allowlisted settings. */
    JSONObject configSet(final JSONObject body) throws ApiException {
        final Switchboard sb = switchboard();
        if (body.length() == 0) {
            throw ApiException.invalid("body", "Give at least one setting to change.");
        }
        final Map<String, String> updates = new LinkedHashMap<>();
        final Iterator<String> keys = body.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final Setting s = SETTINGS.get(key);
            if (s == null) {
                throw new ApiException(403, "setting_not_allowed",
                        "The setting '" + key + "' is not on the Scoutro allowlist.", Json.obj("field", key));
            }
            final Object value = body.opt(key);
            if ("integer".equals(s.type)) {
                if (!(value instanceof Number) || ((Number) value).doubleValue() != Math.rint(((Number) value).doubleValue())) {
                    throw ApiException.invalid(key, "'" + key + "' must be an integer.");
                }
                final long v = ((Number) value).longValue();
                if (v < s.min || v > s.max) {
                    throw ApiException.invalid(key, "'" + key + "' must be between " + s.min + " and " + s.max + ".");
                }
                updates.put(s.yacyKey, Long.toString(v));
            } else {
                if (!(value instanceof String)) {
                    throw ApiException.invalid(key, "'" + key + "' must be a string.");
                }
                final String v = ((String) value).trim();
                if (v.length() > s.max || v.chars().anyMatch(c -> c < 0x20)) {
                    throw ApiException.invalid(key, "'" + key + "' must be at most " + s.max
                            + " characters without control characters.");
                }
                updates.put(s.yacyKey, v);
            }
        }
        for (final Map.Entry<String, String> u : updates.entrySet()) {
            sb.setConfig(u.getKey(), u.getValue());
        }
        LOG.info("config.set " + body.keySet());
        return configGet();
    }

    // ------------------------------------------------------------------
    // crawls
    // ------------------------------------------------------------------

    /** crawl.list: all user crawls known to YaCy (running and terminated). */
    JSONObject crawlList() throws ApiException {
        final JSONArray crawls = Json.arr();
        for (final JSONObject crawl : loadCrawls().values()) {
            crawls.put(crawl);
        }
        return Json.obj("crawls", crawls);
    }

    /** crawl.status */
    JSONObject crawlGet(final String id) throws ApiException {
        checkCrawlId(id);
        final JSONObject crawl = loadCrawls().get(id);
        if (crawl == null) {
            final CrawlLedger.Entry recorded = this.crawlLedger.byId(id);
            if (recorded != null) {
                final JSONObject removed = CrawlLedger.describe(null, recorded.record);
                Json.put(removed, "lastError", recorded.error); return removed;
            }
            throw new ApiException(404, "crawl_not_found", "There is no crawl with the id '" + id + "'.",
                    Json.obj("id", id));
        }
        return crawl;
    }

    /** URL filter carrying a start marker; it can only match URLs that contain the marker itself. */
    /** The start marker filter, optionally followed by further alternatives ({@link #mustNotMatch}). */
    static final Pattern START_MARKER_FILTER = Pattern.compile("\\.\\*/scoutro-start-([0-9a-f]{32})/\\.\\*(\\|.*)?");

    static String startMarkerFilter(final String marker) {
        return ".*/scoutro-start-" + marker + "/.*";
    }

    /** crawl.start: translated to the site crawl of Crawler_p. */
    JSONObject crawlStart(final JSONObject body) throws ApiException {
        return startCrawl(CrawlRequest.parse(body), null, null);
    }

    /**
     * crawl.start with an optional start marker (32 hex characters). The
     * marker is written into the crawl profile as the URL must-not-match
     * filter {@code .*}{@code /scoutro-start-<marker>/.*}, which excludes no
     * real page, and is reported as {@code startMarker} by the crawl list.
     * It ties a crawl profile to the start attempt that created it, also
     * when the caller crashed before it could store the crawl id.
     * {@code hostOnly} keeps the crawl on its start host (agents, Discovery).
     */
    JSONObject crawlStart(final JSONObject body, final String marker, final boolean hostOnly) throws ApiException {
        return crawlStart(body, marker, hostOnly, false);
    }

    /** As {@link #crawlStart(JSONObject, String, boolean)}; {@code discovery}: started by Discovery (collection decisions). */
    JSONObject crawlStart(final JSONObject body, final String marker, final boolean hostOnly, final boolean discovery) throws ApiException {
        final CrawlRequest request = hostOnly ? CrawlRequest.parse(body).hostOnly() : CrawlRequest.parse(body);
        return startCrawl(request, marker, marker == null ? null : "marker:" + marker, discovery);
    }

    JSONObject crawlStartAdmin(final JSONObject body, final String key) throws ApiException {
        return crawlStartAdmin(body, key, false);
    }

    /** As {@link #crawlStartAdmin(JSONObject, String)}; {@code hostOnly} keeps the crawl on its start host (PeopleRoutes). */
    JSONObject crawlStartAdmin(final JSONObject body, final String key, final boolean hostOnly) throws ApiException {
        final CrawlRequest request = hostOnly ? CrawlRequest.parse(body).hostOnly() : CrawlRequest.parse(body);
        this.yacy.requireCollectionStorage(); // before anything is read for the crawl
        // a crawl writes into an existing collection only; a new one is created explicitly first (no phantom collections)
        if (!this.catalog.known(request.collection)) {
            throw new ApiException(400, "collection_unknown", "Unknown collection: choose an existing one or create it first"
                    + " (New collection on the crawl page, POST /scoutro/api/v1/collections).", Json.obj("field", "collection"));
        }
        final String valid = CrawlRequest.key(key);
        return startCrawl(request, null, valid == null ? null : "admin:" + valid);
    }

    private JSONObject startCrawl(final CrawlRequest request, final String suppliedMarker, final String reference) throws ApiException {
        return startCrawl(request, suppliedMarker, reference, false);
    }

    /**
     * Every Scoutro crawl start (administrator, account, agent, Discovery) ends here: the durable collection decisions
     * decide whether the crawl may write into its collection ({@link net.yacy.scoutro.collections.CollectionDecisions#checkCrawl}).
     */
    private JSONObject startCrawl(final CrawlRequest request, final String suppliedMarker, final String reference, final boolean discovery)
            throws ApiException {
        if (suppliedMarker != null && !suppliedMarker.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("invalid start marker");
        this.yacy.requireCollectionStorage();
        synchronized (CRAWL_START_LOCK) {
            CrawlRecord record = this.crawlLedger.find(reference);
            if (record != null) {
                if (!request.matches(record)) throw new ApiException(409, "idempotency_conflict", "This Idempotency-Key belongs to a different crawl request.");
                final Map<String, JSONObject> profiles = loadCrawls();
                if (record.isStarting()) for (final JSONObject profile : profiles.values()) {
                    if (record.startMarker.equals(startMarkerValue(profile))) {
                        record = record.withState(CrawlRecord.STARTED, profile.optString("id"));
                        this.crawlLedger.save(record, null); break;
                    }
                }
                if (record.isStarted()) {
                    JSONObject live = profiles.get(record.crawlId);
                    if (live != null && !record.startMarker.equals(startMarkerValue(live))) live = null;
                    final JSONObject replay = CrawlLedger.describe(live, record);
                    Json.put(replay, "idempotentReplay", true); return replay;
                }
                // No blind replay even after a crash, unreadable answer or missing profile.
                throw new ApiException(409, "crawl_start_unconfirmed", "This crawl start has an unconfirmed outcome; it will not be dispatched again.");
            }
            checkCollectionDecision(request.host, request.collection, discovery);
            for (final JSONObject live : loadCrawls().values()) {
                if (!"terminated".equals(live.optString("state")) && ScopedActions.sameHost(request.host, ScopedActions.crawlHost(live.optString("id"), live)))
                    throw new ApiException(409, "host_busy", "Another crawl is active on this host; its queued URLs must be retained.");
            }
            final String marker = suppliedMarker == null ? java.util.UUID.randomUUID().toString().replace("-", "") : suppliedMarker;
            record = CrawlRecord.starting("scoutro", request.collection, request.host, reference, System.currentTimeMillis(),
                    marker, request.url, request.depth, request.maxPages == null ? -1 : request.maxPages, request.scope);
            this.crawlLedger.save(record, null); // durable intent BEFORE the side effect
            final JSONObject created;
            try {
                created = dispatchCrawl(request, marker);
            } catch (final ApiException error) {
                // A rejected response may still have created a profile; retain a conservative intent.
                try {
                    for (final JSONObject profile : loadCrawls().values()) if (marker.equals(startMarkerValue(profile))) {
                        record = record.withState(CrawlRecord.STARTED, profile.optString("id")); break;
                    }
                    this.crawlLedger.save(record, error.code());
                } catch (final ApiException ignored) { /* durable starting intent still prevents a duplicate */ }
                throw error;
            }
            record = record.withState(CrawlRecord.STARTED, created.optString("id"));
            try { this.crawlLedger.save(record, null); }
            catch (final ApiException unavailable) {
                throw new ApiException(503, "crawl_start_unconfirmed", "YaCy may have started the crawl; retry only with the same Idempotency-Key.");
            }
            return CrawlLedger.describe(created, record);
        }
    }

    /** The collection decision for a crawl of {@code host} into {@code collection}; refused as an ApiException. */
    static void checkCollectionDecision(final String host, final String collection, final boolean discovery) throws ApiException {
        final net.yacy.scoutro.collections.CollectionDecisions decisions = net.yacy.scoutro.collections.CollectionDecisions.current();
        if (decisions == null) return;
        try {
            decisions.checkCrawl(host, collection, discovery);
        } catch (final net.yacy.scoutro.collections.CollectionDecisions.Refusal r) {
            throw new ApiException(r.status, r.code, r.getMessage(), Json.obj("host", host, "collection", collection));
        }
    }

    private static String startMarkerValue(final JSONObject profile) {
        final Object value = profile.opt("startMarker");
        return value instanceof String ? (String) value : null;
    }

    static String mustNotMatch(final CrawlRequest request, final String marker) {
        final String start = marker == null ? "" : startMarkerFilter(marker);
        if (!request.hostOnly) return start;
        return start.isEmpty() ? request.outsideHostFilter() : start + "|" + request.outsideHostFilter();
    }

    private JSONObject dispatchCrawl(final CrawlRequest request, final String marker) throws ApiException {
        final String url = request.url, scope = request.scope, collection = request.collection;
        final int depth = request.depth;
        final Integer maxPages = request.maxPages;
        final YaCyLoopback.Params params = new YaCyLoopback.Params()
                .add("crawlingstart", "1")
                .add("crawlingMode", "url")
                .add("crawlingURL", url)
                .add("crawlingDepth", depth)
                .add("range", scope)
                .add("mustmatch", ".*")
                .add("mustnotmatch", mustNotMatch(request, marker))
                .add("crawlingDomMaxCheck", maxPages == null ? "off" : "on")
                .add("crawlingDomMaxPages", maxPages == null ? null : maxPages)
                .add("collection", collection)
                // same defaults as the YaCy site crawl start (CrawlStartSite.html) ...
                .add("crawlingQ", "on")
                .add("followFrames", "on")
                .add("obeyHtmlRobotsNoindex", "on")
                .add("indexText", "on")
                // Scoutro is a search index for agents, not a web archive:
                // index the text only, never store the crawled originals
                // (HTCache) and never index media. cachePolicy=nocache keeps
                // the crawler from consulting a response cache.
                .add("indexMedia", "off")
                .add("storeHTCache", "off")
                .add("cachePolicy", "nocache")
                .add("recrawl", "reload")
                .add("reloadIfOlderNumber", "3")
                .add("reloadIfOlderUnit", "day")
                // ... except that the API never deletes other documents of the site. Note that
                // Crawler_p itself always removes the start URL from the index to reload it.
                .add("deleteold", "off");

        synchronized (CRAWL_START_LOCK) {
            final Set<String> before = new HashSet<>(loadCrawls().keySet());
            final JSONObject answer = Json.parseUpstream(this.yacy.postAdmin("Crawler_p.json", params), "Crawler_p.json");
            final String comment = plain(answer.optString("comment", ""));
            final boolean started = comment.endsWith("started.");
            if (!comment.isEmpty() && !started) {
                throw new ApiException(422, "crawl_rejected", "YaCy did not start the crawl: " + comment,
                        Json.obj("url", url));
            }
            final Map<String, JSONObject> after = loadCrawls();
            JSONObject created = null;
            for (final Map.Entry<String, JSONObject> e : after.entrySet()) {
                if (marker != null ? marker.equals(e.getValue().optString("startMarker"))
                        : !before.contains(e.getKey())) {
                    created = e.getValue();
                    break;
                }
            }
            if (created == null) {
                throw new ApiException(502, "upstream_error",
                        "YaCy accepted the crawl request, but no new crawl profile appeared.", Json.obj("url", url));
            }
            Json.put(created, "startUrl", url);
            Json.put(created, "scope", scope);
            LOG.info("crawl.start id=" + created.optString("id") + " url=" + url + " depth=" + depth + " scope=" + scope);
            return created;
        }
    }

    /** crawl.stop: terminates a running crawl; YaCy removes its profile afterwards. */
    JSONObject crawlStop(final String id) throws ApiException {
        final JSONObject crawl = crawlGet(id);
        if ("terminated".equals(crawl.optString("state"))) {
            throw new ApiException(409, "crawl_not_running", "The crawl '" + id + "' is not running.",
                    Json.obj("id", id, "state", "terminated"));
        }
        this.yacy.postAdmin("Crawler_p.json", new YaCyLoopback.Params().add("terminate", "1").add("handle", id));
        if (loadCrawls().containsKey(id)) {
            throw new ApiException(502, "upstream_error", "YaCy did not stop the crawl '" + id + "'.",
                    Json.obj("id", id));
        }
        LOG.info("crawl.stop id=" + id);
        return Json.obj("id", id, "name", crawl.opt("name"), "state", "stopped");
    }

    /**
     * Read all non-system crawl profiles (CrawlProfileEditor_p.xml: active and
     * terminated) and merge the per-crawl counters of api/status_p.xml.
     */
    Map<String, JSONObject> loadCrawls() throws ApiException {
        final Document profiles = this.yacy.getAdminXml("CrawlProfileEditor_p.xml", null);
        final Document status = this.yacy.getAdminXml("api/status_p.xml", null);
        final boolean paused = "paused".equalsIgnoreCase(text(status, "localcrawlerqueue", "state"));

        final Map<String, String> loaded = new LinkedHashMap<>();
        final NodeList active = status.getElementsByTagName("crawl");
        for (int i = 0; i < active.getLength(); i++) {
            final Element crawl = (Element) active.item(i);
            loaded.put(childText(crawl, "handle"), childText(crawl, "count"));
        }

        final Map<String, JSONObject> crawls = new LinkedHashMap<>();
        final NodeList list = profiles.getElementsByTagName("crawlProfile");
        for (int i = 0; i < list.getLength(); i++) {
            final Element p = (Element) list.item(i);
            final String yacyStatus = childText(p, "status");
            if ("system".equals(yacyStatus)) {
                continue;
            }
            final String id = childText(p, "handle");
            final String state = "active".equals(yacyStatus) ? (paused ? "paused" : "running") : "terminated";
            final long domMaxPages = parseLong(childText(p, "domMaxPages"), -1);
            final JSONArray collections = Json.arr();
            for (final String c : childText(p, "collections").split("[,|]")) {
                if (!c.trim().isEmpty()) {
                    collections.put(c.trim());
                }
            }
            crawls.put(id, Json.obj(
                    "id", id,
                    "name", childText(p, "name"),
                    "state", state,
                    "depth", longOrNull(childText(p, "depth")),
                    "maxPages", domMaxPages > 0 && domMaxPages < Integer.MAX_VALUE ? domMaxPages : null,
                    "pagesLoaded", loaded.containsKey(id) ? longOrNull(loaded.get(id)) : null,
                    "collections", collections,
                    "startMarker", startMarker(childText(p, "crawlerURLMustNotMatch")),
                    "links", Json.obj(
                            "self", "/scoutro/api/v1/crawls/" + id,
                            "stop", "/scoutro/api/v1/crawls/" + id + "/stop")));
        }
        for (final JSONObject live : crawls.values()) {
            Json.put(live, "url", null); Json.put(live, "startUrl", null); Json.put(live, "scope", null);
            Json.put(live, "startedAt", null); Json.put(live, "endedAt", null); Json.put(live, "lastError", null);
            final JSONArray collections = live.optJSONArray("collections");
            Json.put(live, "collection", collections.length() == 1 ? collections.opt(0) : null);
            Json.put(live, "progress", Json.obj("pagesLoaded", live.opt("pagesLoaded"), "total", null, "percent", null));
        }
        for (final JSONObject live : new ArrayList<>(crawls.values())) {
            final CrawlLedger.Entry entry = this.crawlLedger.byMarker(startMarkerValue(live));
            if (entry == null) continue;
            final CrawlRecord record = entry.record;
            if (record.isStarted() && record.crawlId.equals(live.optString("id"))) {
                final JSONObject enriched = CrawlLedger.describe(live, record);
                Json.put(enriched, "lastError", entry.error); crawls.put(record.crawlId, enriched);
            }
        }
        return crawls;
    }

    /** The start marker of a crawl profile's must-not-match filter, or null. */
    static String startMarker(final String mustNotMatch) {
        final java.util.regex.Matcher m = START_MARKER_FILTER.matcher(mustNotMatch == null ? "" : mustNotMatch.trim());
        return m.matches() ? m.group(1) : null;
    }

    private static JSONObject crawlerQueues(final Document status) {
        return Json.obj(
                "state", text(status, "localcrawlerqueue", "state"),
                "localQueue", longOrNull(text(status, "localcrawlerqueue", "size")),
                "limitQueue", longOrNull(text(status, "limitcrawlerqueue", "size")),
                "remoteQueue", longOrNull(text(status, "remotecrawlerqueue", "size")),
                "noloadQueue", longOrNull(text(status, "noloadcrawlerqueue", "size")),
                "loader", longOrNull(text(status, "loaderqueue", "size")));
    }

    static void checkCrawlId(final String id) throws ApiException {
        if (id == null || !CRAWL_ID.matcher(id).matches()) {
            throw ApiException.invalid("id", "A crawl id consists of letters, digits, '-' and '_'.");
        }
    }

    // ------------------------------------------------------------------
    // validation and conversion helpers
    // ------------------------------------------------------------------

    /** Only absolute http(s) URLs with a host; no credentials in the URL. */
    static String validateHttpUrl(final String raw, final String field) throws ApiException {
        final String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > MAX_URL_LENGTH) {
            throw ApiException.invalid(field, "'" + field + "' must be an http or https URL of at most "
                    + MAX_URL_LENGTH + " characters.");
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (final URISyntaxException e) {
            throw ApiException.invalid(field, "'" + field + "' is not a valid URL.");
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            throw ApiException.invalid(field, "'" + field + "' must use http or https.");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw ApiException.invalid(field, "'" + field + "' must contain a host name.");
        }
        if (uri.getUserInfo() != null) {
            throw ApiException.invalid(field, "'" + field + "' must not contain user credentials.");
        }
        return value;
    }

    private static int intParam(final Map<String, String> query, final String name, final int dflt,
            final int min, final int max) throws ApiException {
        final String raw = trimToNull(query.get(name));
        if (raw == null) {
            return dflt;
        }
        final long v;
        try {
            v = Long.parseLong(raw);
        } catch (final NumberFormatException e) {
            throw ApiException.invalid(name, "Parameter '" + name + "' must be an integer.");
        }
        if (v < min || v > max) {
            throw ApiException.invalid(name, "Parameter '" + name + "' must be between " + min + " and " + max + ".");
        }
        return (int) v;
    }

    private static String enumParam(final Map<String, String> query, final String name, final String dflt,
            final String... values) throws ApiException {
        final String raw = trimToNull(query.get(name));
        if (raw == null) {
            return dflt;
        }
        for (final String v : values) {
            if (v.equals(raw)) {
                return v;
            }
        }
        throw ApiException.invalid(name, "Parameter '" + name + "' must be one of " + String.join(", ", values) + ".");
    }

    static int intField(final JSONObject body, final String name, final int dflt, final int min,
            final int max) throws ApiException {
        if (!present(body, name)) {
            return dflt;
        }
        final Object value = body.opt(name);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != Math.rint(((Number) value).doubleValue())) {
            throw ApiException.invalid(name, "Field '" + name + "' must be an integer.");
        }
        final long v = ((Number) value).longValue();
        if (v < min || v > max) {
            throw ApiException.invalid(name, "Field '" + name + "' must be between " + min + " and " + max + ".");
        }
        return (int) v;
    }

    static String enumField(final JSONObject body, final String name, final String dflt,
            final String... values) throws ApiException {
        if (!present(body, name)) {
            return dflt;
        }
        final Object value = body.opt(name);
        for (final String v : values) {
            if (v.equals(value)) {
                return v;
            }
        }
        throw ApiException.invalid(name, "Field '" + name + "' must be one of " + String.join(", ", values) + ".");
    }

    static String stringField(final JSONObject body, final String name, final Pattern pattern,
            final String rule) throws ApiException {
        final Object value = body.opt(name);
        if (!(value instanceof String) || !pattern.matcher((String) value).matches()) {
            throw ApiException.invalid(name, "Field '" + name + "' must consist of " + rule + ".");
        }
        return (String) value;
    }

    /** A field counts as present when it exists and is not JSON null. */
    private static boolean present(final JSONObject body, final String name) {
        final Object value = body.opt(name);
        return value != null && value != JSONObject.NULL;
    }

    private static Switchboard switchboard() throws ApiException {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null) {
            throw new ApiException(503, "unavailable", "YaCy is not initialized yet.");
        }
        return sb;
    }

    /** Text of the first element at the given path of child element names below the root. */
    private static String text(final Document doc, final String... path) {
        Node node = doc.getDocumentElement();
        for (final String name : path) {
            node = firstChild(node, name);
            if (node == null) {
                return "";
            }
        }
        return node.getTextContent().trim();
    }

    private static Node firstChild(final Node parent, final String name) {
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return n;
            }
        }
        return null;
    }

    private static String childText(final Element parent, final String name) {
        final Node n = firstChild(parent, name);
        return n == null ? "" : n.getTextContent().trim();
    }

    private static String trimToNull(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static long parseLong(final String s, final long dflt) {
        try {
            return Long.parseLong(s.trim().replace(",", "").replace(".", ""));
        } catch (final RuntimeException e) {
            return dflt;
        }
    }

    private static Object longOrNull(final String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(s.trim().replace(",", ""));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static Object doubleOrNull(final String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(s.trim().replace(",", "."));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** HTML to plain text: drop tags, decode entities, collapse whitespace. */
    static String plain(final String html) {
        final String noTags = html.replaceAll("<[^>]*>", "");
        return CharacterCoding.html2unicode(noTags).replaceAll("\\s+", " ").trim();
    }

    private static Object isoDate(final String rfc1123) {
        if (rfc1123 == null || rfc1123.trim().isEmpty()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(rfc1123.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static String firstString(final Object value) {
        if (value instanceof JSONArray) {
            final JSONArray a = (JSONArray) value;
            return a.length() == 0 ? "" : a.optString(0, "");
        }
        return value == null ? "" : String.valueOf(value);
    }
}
