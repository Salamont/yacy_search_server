/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.search.Switchboard;

/**
 * collections.reassign (administrator): moves the indexed pages of exactly one
 * registrable domain between collections without crawling them again, e.g. a
 * domain that Discovery put into the wrong collection. Only {@code collection_sxt}
 * changes, as a Solr atomic update of each page: text, JSON-LD and every other
 * field stay, and the knowledge graph takes the change over through its usual
 * capture and sync (it re-scopes the page, it does not extract it again).
 * <p>
 * Without {@code confirm} it is a preview: which hosts, how many pages, the
 * collections before and after, a sample and a token. With {@code confirm} set
 * to that token it applies exactly that preview; a page that changed since (a
 * new crawl) is never overwritten: the token no longer matches (409), or Solr
 * refuses the page's old version and it is reported as failed.
 * <p>
 * The pages are those of the domain and its subdomains that are members of a
 * collection to remove (or, without one, every page of the domain); a page
 * keeps its other collections. A change that would leave a page without any
 * collection is refused.
 */
final class CollectionReassign {

    /** The index: one query, one atomic change of the collections of a page, one commit. */
    interface Index {
        SolrDocumentList select(String core, ModifiableSolrParams params) throws IOException;

        /** Sets collection_sxt of page {@code id}, only if its version is still {@code version}. */
        void setCollections(String core, String id, long version, List<String> collections) throws IOException;

        void commit(String core) throws IOException;

        /** True if the core exists and is written. */
        boolean has(String core);
    }

    static final String DEFAULT_CORE = "collection1";
    static final String WEBGRAPH_CORE = "webgraph";
    /** Pages one reassignment may change; more is refused (a domain, not a collection). */
    static final int MAX_DOCUMENTS = 5000;
    /** Webgraph edges of those pages one reassignment may change (a page has many links). */
    static final int MAX_EDGES = 100_000;
    static final int MAX_SAMPLE = 20;
    static final int MAX_COLLECTIONS = 5;
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    private static final Pattern COLLECTION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> FIELDS = Set.of("domain", "add", "remove", "confirm");

    private final Index index;
    private final Predicate<String> known;

    CollectionReassign(final Index index, final Predicate<String> known) {
        this.index = index;
        this.known = known;
    }

    /** The running peer's embedded index; the catalog decides which collections exist. */
    static CollectionReassign current(final CollectionCatalog catalog) throws ApiException {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null || sb.index == null || sb.index.fulltext().getDefaultEmbeddedConnector() == null) {
            throw new ApiException(503, "index_unavailable", "The local embedded index is unavailable.");
        }
        final Index index = new Index() {
            private EmbeddedSolrConnector connector(final String core) {
                return DEFAULT_CORE.equals(core) ? sb.index.fulltext().getDefaultEmbeddedConnector()
                        : sb.index.fulltext().getEmbeddedConnector(core);
            }

            @Override
            public SolrDocumentList select(final String core, final ModifiableSolrParams params) throws IOException {
                return connector(core).getResponseByParams(params).getResults();
            }

            @Override
            public void setCollections(final String core, final String id, final long version, final List<String> collections)
                    throws IOException {
                // the Solr client itself: the connector's add() would delete the page and add the patch alone on an error
                atomicSet(connector(core).getServer(), id, version, collections);
            }

            @Override
            public void commit(final String core) throws IOException {
                connector(core).commit(true);
                sb.index.fulltext().clearCaches();
            }

            @Override
            public boolean has(final String core) {
                return DEFAULT_CORE.equals(core) || WEBGRAPH_CORE.equals(core) && sb.index.fulltext().useWebgraph()
                        && connector(core) != null;
            }
        };
        return new CollectionReassign(index, id -> {
            try {
                return catalog.known(id);
            } catch (final ApiException e) {
                return false;
            }
        });
    }

    /** An atomic "set" of collection_sxt with optimistic concurrency: Solr refuses it if the page has another version. */
    static void atomicSet(final SolrClient client, final String id, final long version, final List<String> collections) throws IOException {
        final SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", id);
        doc.setField("_version_", version);
        doc.setField("collection_sxt", Map.of("set", collections));
        try {
            client.add(doc);
        } catch (final Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** One page of the plan. */
    static final class Change {
        final String id;
        final String url;
        final long version;
        final List<String> before;
        final List<String> after;

        Change(final String id, final String url, final long version, final List<String> before, final List<String> after) {
            this.id = id;
            this.url = url;
            this.version = version;
            this.before = before;
            this.after = after;
        }

        boolean changes() {
            return !this.before.equals(this.after);
        }
    }

    /** What a request asks for and the pages it would change. */
    static final class Plan {
        String domain;
        List<String> add;
        List<String> remove;
        List<String> hosts = new ArrayList<>();
        final List<Change> pages = new ArrayList<>();
        final List<Change> edges = new ArrayList<>();
        boolean webgraph;
        String token;
    }

    /** Preview, or apply with {@code confirm}. {@code busy} is true while a crawl of the domain runs. */
    JSONObject run(final JSONObject body, final Predicate<String> busy) throws ApiException {
        final Plan plan = plan(body);
        final String confirm = body.optString("confirm", "");
        final boolean hostBusy = plan.hosts.stream().anyMatch(busy);
        if (confirm.isEmpty()) {
            return Json.put(describe(plan, hostBusy), "applied", false);
        }
        if (!confirm.equals(plan.token)) {
            throw new ApiException(409, "reassign_preview_stale", "The pages changed since the preview; preview again and confirm the new token.",
                    Json.put(describe(plan, hostBusy), "applied", false));
        }
        if (hostBusy) {
            throw new ApiException(409, "host_busy", "A crawl of the domain is running; it would write the old collection again.");
        }
        int updated = 0;
        int edges = 0;
        final JSONArray failed = new JSONArray();
        try {
            for (final Change c : plan.pages) {
                if (!c.changes()) continue;
                try {
                    this.index.setCollections(DEFAULT_CORE, c.id, c.version, c.after);
                    updated++;
                } catch (final IOException e) {
                    failed.put(Json.obj("id", c.id, "url", c.url, "reason", "changed_or_unwritable"));
                }
            }
            for (final Change c : plan.edges) {
                try {
                    this.index.setCollections(WEBGRAPH_CORE, c.id, c.version, c.after);
                    edges++;
                } catch (final IOException e) {
                    failed.put(Json.obj("id", c.id, "core", WEBGRAPH_CORE, "reason", "changed_or_unwritable"));
                }
            }
            this.index.commit(DEFAULT_CORE);
            if (plan.webgraph && !plan.edges.isEmpty()) this.index.commit(WEBGRAPH_CORE);
        } catch (final IOException e) {
            throw new ApiException(503, "index_unavailable", "The index could not be written: " + e.getMessage());
        }
        ConcurrentLog.info("SCOUTRO-API", "event=collections.reassign domain=" + plan.domain + " add=" + plan.add + " remove=" + plan.remove
                + " updated=" + updated + " webgraphEdges=" + edges + " failed=" + failed.length());
        final JSONObject out = describe(plan, false);
        Json.put(out, "applied", true);
        Json.put(out, "updated", updated);
        Json.put(out, "webgraphUpdated", edges);
        Json.put(out, "failed", failed);
        return out;
    }

    Plan plan(final JSONObject body) throws ApiException {
        for (final String key : body.keySet()) {
            if (!FIELDS.contains(key)) throw ApiException.invalid(key, "Unknown field.");
        }
        final Plan plan = new Plan();
        plan.domain = domain(body.opt("domain"));
        plan.add = collections(body, "add");
        plan.remove = collections(body, "remove");
        if (plan.add.isEmpty() && plan.remove.isEmpty()) throw ApiException.invalid("add", "Name a collection to add or to remove.");
        for (final String c : plan.add) {
            if (plan.remove.contains(c)) throw ApiException.invalid("remove", "A collection cannot be added and removed at once: " + c);
        }
        plan.hosts = hosts(plan.domain);
        if (!plan.hosts.isEmpty()) {
            pages(plan, DEFAULT_CORE, plan.pages, "host_s", MAX_DOCUMENTS);
            plan.webgraph = this.index.has(WEBGRAPH_CORE);
            if (plan.webgraph) pages(plan, WEBGRAPH_CORE, plan.edges, "source_host_s", MAX_EDGES);
        }
        for (final Change c : plan.pages) {
            if (c.after.isEmpty()) {
                throw new ApiException(422, "reassign_would_empty", "A page would be left without any collection: " + c.url,
                        Json.obj("url", c.url));
            }
        }
        plan.token = token(plan);
        return plan;
    }

    private static String domain(final Object raw) throws ApiException {
        if (!(raw instanceof String)) throw ApiException.invalid("domain", "domain is required (a registrable domain such as example.com).");
        final String d = ((String) raw).trim().toLowerCase(Locale.ROOT);
        if (d.length() > 253 || !DOMAIN.matcher(d).matches() || !d.equals(Normalizers.registrableDomain(d))) {
            throw ApiException.invalid("domain", "Use exactly one registrable domain such as example.com (no scheme, path, subdomain or wildcard).");
        }
        return d;
    }

    private List<String> collections(final JSONObject body, final String field) throws ApiException {
        final Object raw = body.opt(field);
        if (raw == null) return List.of();
        if (!(raw instanceof JSONArray) || ((JSONArray) raw).length() > MAX_COLLECTIONS) {
            throw ApiException.invalid(field, field + " must be a list of at most " + MAX_COLLECTIONS + " collections.");
        }
        final Set<String> out = new LinkedHashSet<>();
        final JSONArray list = (JSONArray) raw;
        for (int i = 0; i < list.length(); i++) {
            final Object o = list.opt(i);
            if (!(o instanceof String) || !COLLECTION.matcher((String) o).matches()) {
                throw ApiException.invalid(field, "Invalid collection name.");
            }
            final String c = (String) o;
            if (CollectionCatalog.isInternal(c) || !this.known.test(c)) {
                throw new ApiException(400, "collection_unknown", "No selectable collection " + c + "; choose one of the list.",
                        Json.obj("field", field, "collection", c));
            }
            out.add(c);
        }
        return new ArrayList<>(out);
    }

    /** The hosts of the index that are the domain or one of its subdomains (never "notexample.com"). */
    private List<String> hosts(final String domain) throws ApiException {
        final ModifiableSolrParams p = new ModifiableSolrParams();
        // YaCy keeps the organisation of every page's host in host_organization_s ("yowea" for yowea.com, www.yowea.com,
        // blog.yowea.com, but also yowea.de): the hosts are then filtered to exactly the domain and its subdomains. The
        // exact hosts cover an index without that field. No wildcard: the index's parser may refuse a leading one.
        final String organisation = domain.substring(0, domain.indexOf('.'));
        p.set("q", "host_organization_s:\"" + ClientUtils.escapeQueryChars(organisation) + "\" OR host_s:\"" + ClientUtils.escapeQueryChars(domain)
                + "\" OR host_s:\"www." + ClientUtils.escapeQueryChars(domain) + "\"");
        p.set("rows", MAX_DOCUMENTS + 1);
        p.set("fl", "host_s");
        final Set<String> hosts = new TreeSet<>();
        final SolrDocumentList docs;
        try {
            docs = this.index.select(DEFAULT_CORE, p);
        } catch (final IOException e) {
            throw new ApiException(503, "index_unavailable", "The index could not be read: " + e.getMessage());
        }
        if (docs.size() > MAX_DOCUMENTS) {
            throw new ApiException(422, "reassign_too_large", "More than " + MAX_DOCUMENTS + " pages for this domain.");
        }
        for (final SolrDocument d : docs) {
            final Object h = d.getFirstValue("host_s");
            if (h instanceof String && (h.equals(domain) || ((String) h).endsWith("." + domain))) hosts.add((String) h);
        }
        return new ArrayList<>(hosts);
    }

    private void pages(final Plan plan, final String core, final List<Change> out, final String hostField, final int max) throws ApiException {
        final List<String> quoted = new ArrayList<>();
        for (final String h : plan.hosts) quoted.add("\"" + h + "\"");
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("q", hostField + ":(" + String.join(" OR ", quoted) + ")");
        if (!plan.remove.isEmpty()) {
            final List<String> rq = new ArrayList<>();
            for (final String c : plan.remove) rq.add("\"" + c + "\"");
            p.add("fq", "collection_sxt:(" + String.join(" OR ", rq) + ")");
        }
        p.set("rows", max + 1);
        p.set("sort", "id asc");
        p.set("fl", DEFAULT_CORE.equals(core) ? "id,sku,_version_,collection_sxt" : "id,_version_,collection_sxt");
        final SolrDocumentList docs;
        try {
            docs = this.index.select(core, p);
        } catch (final IOException e) {
            throw new ApiException(503, "index_unavailable", "The index could not be read: " + e.getMessage());
        }
        if (docs.size() > max) {
            throw new ApiException(422, "reassign_too_large", "More than " + max + " entries of " + core + " for this domain.");
        }
        for (final SolrDocument d : docs) {
            final List<String> before = new ArrayList<>();
            final Collection<Object> values = d.getFieldValues("collection_sxt");
            if (values != null) for (final Object v : values) if (v != null) before.add(v.toString());
            final List<String> after = new ArrayList<>();
            for (final String c : before) if (!plan.remove.contains(c) && !after.contains(c)) after.add(c);
            for (final String c : plan.add) if (!after.contains(c)) after.add(c);
            final Object version = d.getFirstValue("_version_");
            out.add(new Change(String.valueOf(d.getFirstValue("id")), d.getFirstValue("sku") == null ? null : d.getFirstValue("sku").toString(),
                    version instanceof Number ? ((Number) version).longValue() : 0L, before, after));
        }
    }

    /** The token of a plan: the request and every page with its version and collections. */
    private static String token(final Plan plan) {
        final StringBuilder b = new StringBuilder(plan.domain).append('|').append(plan.add).append('|').append(plan.remove);
        for (final List<Change> list : List.of(plan.pages, plan.edges)) {
            for (final Change c : list) b.append('\n').append(c.id).append(' ').append(c.version).append(' ').append(c.before);
        }
        try {
            final byte[] h = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", h[i]));
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject describe(final Plan plan, final boolean busy) {
        final Map<String, Integer> before = new TreeMap<>();
        final Map<String, Integer> after = new TreeMap<>();
        final Set<String> kept = new TreeSet<>();
        int changes = 0;
        final JSONArray sample = new JSONArray();
        for (final Change c : plan.pages) {
            for (final String x : c.before) before.merge(x, 1, Integer::sum);
            for (final String x : c.after) after.merge(x, 1, Integer::sum);
            for (final String x : c.before) if (!plan.remove.contains(x)) kept.add(x);
            if (c.changes()) {
                changes++;
                if (sample.length() < MAX_SAMPLE) sample.put(Json.obj("url", c.url, "before", new JSONArray(c.before), "after", new JSONArray(c.after)));
            }
        }
        return Json.obj("domain", plan.domain, "hosts", new JSONArray(plan.hosts), "add", new JSONArray(plan.add),
                "remove", new JSONArray(plan.remove), "documents", plan.pages.size(), "changes", changes,
                "unchanged", plan.pages.size() - changes, "before", new JSONObject(before), "after", new JSONObject(after),
                "kept", new JSONArray(kept), "sample", sample,
                "webgraph", Json.obj("written", plan.webgraph, "edges", plan.edges.size()),
                "crawlRunning", busy, "token", plan.token,
                "knowledgeGraph", "re-scopes the pages through its sync (no new extraction); a collection it does not follow removes them from it");
    }
}
