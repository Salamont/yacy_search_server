/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.document.id.MultiProtocolURL;
import net.yacy.scoutro.collections.CollectionDecision;

/**
 * Brings the indexed pages of a collection decision's scope to the decision's collections (collection1 and webgraph),
 * in batches that can be resumed: the decision is stored first (write-ahead), so a crash between storing it and
 * finishing here leaves a decision marked pending or partial with a cursor, and running this again continues
 * idempotently (pages already right are skipped, a page changed meanwhile is refused by its Solr version and retried).
 * New writes already follow the decision through the index hook, whatever stage this is at.
 */
final class DecisionApply {

    /** Pages one Solr batch reads. */
    static final int BATCH = 1000;
    private int batch = BATCH;
    static final int MAX_SAMPLE = 20;

    /** The result of a preview or one run. */
    static final class Result {
        final List<String> hosts = new ArrayList<>();
        int pages;
        int changes;
        int updated;
        int skippedEmpty;
        int edges;
        int edgesKept;
        final JSONArray failed = new JSONArray();
        final Map<String, Integer> before = new TreeMap<>();
        final Map<String, Integer> after = new TreeMap<>();
        final JSONArray sample = new JSONArray();
        /** Pages whose collections the graph follows now but would not follow any more. */
        int leavingGraph;
        String cursor;
        boolean done;

        JSONObject toJson() {
            return Json.obj("hosts", new JSONArray(this.hosts), "documents", this.pages, "changes", this.changes, "updated", this.updated,
                    "skippedEmpty", this.skippedEmpty, "webgraphUpdated", this.edges, "webgraphKept", this.edgesKept, "failed", this.failed,
                    "before", new JSONObject(this.before), "after", new JSONObject(this.after), "sample", this.sample,
                    "leavingGraph", this.leavingGraph, "cursor", this.cursor, "done", this.done);
        }
    }

    private final CollectionReassign.Index index;
    private final String fallback;
    /** Collections the knowledge graph follows; null: graph off (nothing to protect). */
    private final Predicate<String> followed;

    DecisionApply(final CollectionReassign.Index index, final String fallback, final Predicate<String> followed) {
        this.index = index;
        this.fallback = fallback;
        this.followed = followed;
    }

    /** With smaller Solr batches (tests). */
    DecisionApply batch(final int size) {
        this.batch = size;
        return this;
    }

    /** The collections a page in scope gets, from the collections it has now; empty: left as it is. */
    List<String> target(final CollectionDecision d, final List<String> before) {
        return d.effective(before, this.fallback);
    }

    /** Looks at every page of the scope (bounded by {@code limit}) without writing: counts, sample, graph check. */
    Result preview(final CollectionDecision d, final int limit) throws ApiException {
        final Result r = new Result();
        r.hosts.addAll(hosts(d));
        String cursor = null;
        int seen = 0;
        while (!r.hosts.isEmpty() && seen < limit) {
            final SolrDocumentList docs = batch(r.hosts, cursor);
            if (docs.isEmpty()) break;
            for (final SolrDocument doc : docs) {
                cursor = String.valueOf(doc.getFirstValue("id"));
                seen++;
                consider(d, doc, r, null, null);
            }
            if (docs.size() < this.batch) break;
        }
        r.done = seen < limit;
        return r;
    }

    /** One run from {@code cursor} (null: from the start) over at most {@code maxPages} pages, writing. */
    Result run(final CollectionDecision d, final String cursor, final int maxPages) throws ApiException {
        final Result r = new Result();
        r.hosts.addAll(hosts(d));
        String at = cursor;
        int seen = 0;
        boolean end = r.hosts.isEmpty();
        try {
            while (!end && seen < maxPages) {
                final SolrDocumentList docs = batch(r.hosts, at);
                if (docs.isEmpty()) {
                    end = true;
                    break;
                }
                final List<CollectionReassign.Change> changed = new ArrayList<>();
                final Set<String> settled = new java.util.HashSet<>();
                for (final SolrDocument doc : docs) {
                    at = String.valueOf(doc.getFirstValue("id"));
                    seen++;
                    consider(d, doc, r, changed, settled);
                }
                for (final CollectionReassign.Change c : changed) {
                    try {
                        this.index.setCollections(CollectionReassign.DEFAULT_CORE, c.id, c.version, c.after);
                        r.updated++;
                    } catch (final IOException e) {
                        settled.remove(c.id);
                        r.failed.put(Json.obj("id", c.id, "url", c.url, "reason", "changed_or_unwritable"));
                    }
                }
                if (this.index.has(CollectionReassign.WEBGRAPH_CORE)) edges(docs, d, settled, r);
                this.index.commit(CollectionReassign.DEFAULT_CORE);
                if (this.index.has(CollectionReassign.WEBGRAPH_CORE)) this.index.commit(CollectionReassign.WEBGRAPH_CORE);
                if (docs.size() < this.batch) end = true;
            }
        } catch (final IOException e) {
            throw new ApiException(503, "index_unavailable", "The index could not be written: " + e.getMessage());
        }
        r.cursor = end ? null : at;
        r.done = end;
        return r;
    }

    private void consider(final CollectionDecision d, final SolrDocument doc, final Result r, final List<CollectionReassign.Change> changed,
            final Set<String> settled) {
        final String sku = doc.getFirstValue("sku") == null ? null : doc.getFirstValue("sku").toString();
        String host = null;
        String path = "/";
        try {
            final MultiProtocolURL u = new MultiProtocolURL(sku);
            host = u.getHost() == null ? null : u.getHost().toLowerCase(java.util.Locale.ROOT);
            path = u.getPath();
        } catch (final Exception e) {
            return; // not a page URL
        }
        if (!d.covers(host, path)) return;
        r.pages++;
        final List<String> before = collectionsOf(doc);
        final List<String> after = target(d, before);
        if (after.isEmpty()) {
            r.skippedEmpty++;
            return;
        }
        final String id = String.valueOf(doc.getFirstValue("id"));
        if (new TreeSet<>(before).equals(new TreeSet<>(after))) {
            if (settled != null) settled.add(id);
            return;
        }
        r.changes++;
        for (final String x : before) r.before.merge(x, 1, Integer::sum);
        for (final String x : after) r.after.merge(x, 1, Integer::sum);
        if (this.followed != null && before.stream().anyMatch(this.followed) && after.stream().noneMatch(this.followed)) r.leavingGraph++;
        if (r.sample.length() < MAX_SAMPLE) r.sample.put(Json.obj("url", sku, "before", new JSONArray(before), "after", new JSONArray(after)));
        if (changed != null) {
            final Object v = doc.getFirstValue("_version_");
            changed.add(new CollectionReassign.Change(id, sku, v instanceof Number ? ((Number) v).longValue() : 0L, before, after, null));
            settled.add(id);
        }
    }

    /** Webgraph edges of the batch's pages get exactly their page's collections, once the page has them. */
    private void edges(final SolrDocumentList pages, final CollectionDecision d, final Set<String> settled, final Result r) throws IOException {
        final Map<String, List<String>> target = new HashMap<>();
        for (final SolrDocument doc : pages) {
            final String id = String.valueOf(doc.getFirstValue("id"));
            final List<String> after = target(d, collectionsOf(doc));
            if (!after.isEmpty()) target.put(id, after);
        }
        final List<String> ids = new ArrayList<>(target.keySet());
        for (int i = 0; i < ids.size(); i += 100) {
            final ModifiableSolrParams p = new ModifiableSolrParams();
            p.set("q", "source_id_s:" + quoted(ids.subList(i, Math.min(ids.size(), i + 100))));
            p.set("rows", CollectionReassign.MAX_EDGES);
            p.set("sort", "id asc");
            p.set("fl", "id,_version_,collection_sxt,source_id_s");
            for (final SolrDocument e : this.index.select(CollectionReassign.WEBGRAPH_CORE, p)) {
                final String page = String.valueOf(e.getFirstValue("source_id_s"));
                final List<String> after = target.get(page);
                if (after == null || new TreeSet<>(collectionsOf(e)).equals(new TreeSet<>(after))) continue;
                if (!settled.contains(page)) {
                    r.edgesKept++; // its page kept its collections: so does the edge, never a mix of both
                    continue;
                }
                final Object v = e.getFirstValue("_version_");
                try {
                    this.index.setCollections(CollectionReassign.WEBGRAPH_CORE, String.valueOf(e.getFirstValue("id")),
                            v instanceof Number ? ((Number) v).longValue() : 0L, after);
                    r.edges++;
                } catch (final IOException x) {
                    r.failed.put(Json.obj("id", String.valueOf(e.getFirstValue("id")), "core", CollectionReassign.WEBGRAPH_CORE, "page", page,
                            "reason", "changed_or_unwritable"));
                }
            }
        }
    }

    private SolrDocumentList batch(final List<String> hosts, final String cursor) throws ApiException {
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("q", "host_s:" + quoted(hosts));
        if (cursor != null) p.add("fq", "id:{" + ClientUtils.escapeQueryChars(cursor) + " TO *]");
        p.set("rows", this.batch);
        p.set("sort", "id asc");
        p.set("fl", "id,sku,_version_,collection_sxt");
        try {
            return this.index.select(CollectionReassign.DEFAULT_CORE, p);
        } catch (final IOException e) {
            throw new ApiException(503, "index_unavailable", "The index could not be read: " + e.getMessage());
        }
    }

    /** The hosts of the decision's domain (and only its host, if it has one) in the index. */
    private List<String> hosts(final CollectionDecision d) throws ApiException {
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("q", "*:*");
        p.set("rows", 0);
        p.set("facet", true);
        p.set("facet.field", "host_s");
        p.set("facet.contains", d.host == null ? d.domain : d.host);
        p.set("facet.limit", -1);
        p.set("facet.mincount", 1);
        final Set<String> out = new TreeSet<>();
        try {
            for (final String h : this.index.facet(CollectionReassign.DEFAULT_CORE, p, "host_s").keySet()) {
                if (d.host != null ? h.equals(d.host) : h.equals(d.domain) || h.endsWith("." + d.domain)) out.add(h);
            }
        } catch (final IOException e) {
            throw new ApiException(503, "index_unavailable", "The index could not be read: " + e.getMessage());
        }
        return new ArrayList<>(out);
    }

    static List<String> collectionsOf(final SolrDocument d) {
        final List<String> out = new ArrayList<>();
        final Collection<Object> values = d.getFieldValues("collection_sxt");
        if (values != null) for (final Object v : values) if (v != null && !out.contains(v.toString())) out.add(v.toString());
        return out;
    }

    private static String quoted(final Collection<String> values) {
        final List<String> q = new ArrayList<>();
        for (final String v : values) q.add("\"" + ClientUtils.escapeQueryChars(v) + "\"");
        return "(" + String.join(" OR ", q) + ")";
    }

    static String token(final String... parts) {
        try {
            final byte[] h = MessageDigest.getInstance("SHA-256").digest(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", h[i]));
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
