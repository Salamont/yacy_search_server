/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.Group;
import org.apache.solr.client.solrj.response.GroupCommand;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;

import net.yacy.cora.federate.solr.connector.SolrConnector;

/**
 * The local index read directly for the chat's job and provider search (docs/SCOUTRO_RAG_QUALITY.md, sections 12 and 13):
 * no search event, no ranking cache, no collapse to one page per host. A search first asks which pages or hosts match
 * (addresses and counts only: a filter on the pages' addresses, a facet on their hosts) and loads the text of pages only
 * for the few it shows. The collection is a filter of every query.
 */
public final class SolrIndex implements ToolContext.Index {

    static final String ID = "id", SKU = "sku", TITLE = "title", DESCRIPTION = "description_txt", TEXT = "text_t", HOST = "host_s",
            COLLECTION = "collection_sxt";
    /** addresses of one filter query */
    static final int URL_BATCH = 1000;
    /** hosts of one filter query */
    static final int HOST_BATCH = 200;

    private final Supplier<SolrConnector> connector;

    public SolrIndex(final Supplier<SolrConnector> connector) {
        this.connector = connector;
    }

    /** One word of a query in the three text fields: the start of a word, a word of up to three letters as a whole word. */
    private static String word(final String w) {
        final String raw = w.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) return null;
        final String term = ClientUtils.escapeQueryChars(raw) + (raw.length() <= 3 ? "" : "*");
        return "(" + TITLE + ":" + term + " OR " + DESCRIPTION + ":" + term + " OR " + TEXT + ":" + term + ")";
    }

    /** Every word, or with {@code phrase} the words side by side. */
    static String clause(final List<String> words, final boolean phrase) {
        final List<String> clean = new ArrayList<>();
        for (final String w : words) if (w != null && !w.trim().isEmpty()) clean.add(w.trim());
        if (clean.isEmpty()) return null;
        if (phrase && clean.size() > 1) {
            final String p = '"' + String.join(" ", clean).toLowerCase(Locale.ROOT).replace("\\", " ").replace("\"", " ") + '"';
            return "(" + TITLE + ":" + p + " OR " + DESCRIPTION + ":" + p + " OR " + TEXT + ":" + p + ")";
        }
        final StringBuilder q = new StringBuilder();
        for (final String w : clean) {
            final String one = word(w);
            if (one == null) continue;
            if (q.length() > 0) q.append(" AND ");
            q.append(one);
        }
        return q.length() == 0 ? null : "(" + q + ")";
    }

    static String anyOf(final List<List<String>> alternatives) {
        final StringBuilder q = new StringBuilder();
        for (final List<String> words : alternatives) {
            final String one = clause(words, false);
            if (one == null) continue;
            if (q.length() > 0) q.append(" OR ");
            q.append(one);
        }
        return q.length() == 0 ? null : q.toString();
    }

    private static ModifiableSolrParams params(final String q, final String collection) {
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("q", q);
        p.set("defType", "lucene");
        if (collection != null) p.add("fq", COLLECTION + ":\"" + ClientUtils.escapeQueryChars(collection) + "\"");
        return p;
    }

    /** A filter on one string field by many values: no clause limit, no parsing of the values. */
    private static void terms(final ModifiableSolrParams p, final String field, final Collection<String> values, final String name) {
        p.set(name, String.join(" ", values));
        p.add("fq", "{!terms f=" + field + " separator=' ' v=$" + name + "}");
    }

    private static String first(final Object value) {
        if (value == null) return "";
        if (value instanceof Collection) {
            for (final Object item : (Collection<?>) value) if (item != null) return item.toString();
            return "";
        }
        return value.toString();
    }

    private static RagCandidate candidate(final SolrDocument d, final int rank) {
        final String url = first(d.getFieldValue(SKU));
        if (url.isEmpty()) return null;
        final List<String> collections = new ArrayList<>();
        final Collection<Object> values = d.getFieldValues(COLLECTION);
        if (values != null) for (final Object value : values) if (value != null) collections.add(value.toString());
        return new RagCandidate(url, first(d.getFieldValue(TITLE)), first(d.getFieldValue(DESCRIPTION)), first(d.getFieldValue(TEXT)),
                first(d.getFieldValue(HOST)), collections, rank);
    }

    private static List<String> clean(final Collection<String> values) {
        final List<String> out = new ArrayList<>();
        for (final String v : values) if (v != null && !v.isEmpty() && v.indexOf(' ') < 0 && v.indexOf('\t') < 0 && v.indexOf('\n') < 0) out.add(v);
        return out;
    }

    @Override
    public ToolContext.IndexPage find(final List<String> words, final String collection, final Collection<String> urls, final int offset,
            final int count) throws Exception {
        final List<RagCandidate> out = new ArrayList<>();
        final SolrConnector solr = this.connector.get();
        final String q = words == null ? null : clause(words, false);
        if (solr == null || q == null || count <= 0) return new ToolContext.IndexPage(out, 0L);
        final ModifiableSolrParams p = params(q, collection);
        if (urls != null) {
            final List<String> list = clean(urls);
            if (list.isEmpty()) return new ToolContext.IndexPage(out, 0L);
            terms(p, SKU, list, "scoutro_urls");
        }
        p.set("fl", String.join(",", SKU, TITLE, DESCRIPTION, TEXT, HOST, COLLECTION));
        p.set("sort", ID + " asc");
        p.set("start", offset);
        p.set("rows", count);
        final SolrDocumentList docs = solr.getResponseByParams(p).getResults();
        int rank = offset;
        for (final SolrDocument d : docs) {
            final RagCandidate c = candidate(d, rank);
            if (c != null) {
                out.add(c);
                rank++;
            }
        }
        return new ToolContext.IndexPage(out, docs.getNumFound());
    }

    @Override
    public Set<String> matching(final List<String> words, final boolean phrase, final String collection, final Collection<String> urls)
            throws Exception {
        final Set<String> out = new LinkedHashSet<>();
        final SolrConnector solr = this.connector.get();
        final String q = clause(words, phrase);
        if (solr == null || q == null) return out;
        final List<String> list = clean(urls);
        for (int i = 0; i < list.size(); i += URL_BATCH) {
            final List<String> batch = list.subList(i, Math.min(list.size(), i + URL_BATCH));
            final ModifiableSolrParams p = params(q, collection);
            terms(p, SKU, batch, "scoutro_urls");
            p.set("fl", SKU);
            p.set("sort", ID + " asc");
            p.set("rows", batch.size());
            for (final SolrDocument d : solr.getResponseByParams(p).getResults()) {
                final String url = first(d.getFieldValue(SKU));
                if (!url.isEmpty()) out.add(url);
            }
        }
        return out;
    }

    @Override
    public ToolContext.HostCount hosts(final List<List<String>> alternatives, final String collection) throws Exception {
        final Map<String, Integer> counts = new TreeMap<>();
        final SolrConnector solr = this.connector.get();
        final String q = anyOf(alternatives);
        if (solr == null || q == null) return new ToolContext.HostCount(counts, 0L);
        final ModifiableSolrParams p = params(q, collection);
        p.set("rows", 0);
        p.set("facet", true);
        p.set("facet.field", HOST);
        p.set("facet.limit", -1);
        p.set("facet.mincount", 1);
        p.set("facet.sort", "index");
        final QueryResponse response = solr.getResponseByParams(p);
        final FacetField field = response.getFacetField(HOST);
        if (field != null && field.getValues() != null) {
            for (final FacetField.Count c : field.getValues()) {
                if (c.getName() == null || c.getCount() <= 0) continue;
                counts.merge(c.getName().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""), (int) c.getCount(), Integer::sum);
            }
        }
        return new ToolContext.HostCount(counts, response.getResults() == null ? 0L : response.getResults().getNumFound());
    }

    @Override
    public List<RagCandidate> hostPages(final List<List<String>> alternatives, final String collection, final Collection<String> hosts,
            final int perHost) throws Exception {
        final List<RagCandidate> out = new ArrayList<>();
        final SolrConnector solr = this.connector.get();
        final String q = anyOf(alternatives);
        if (solr == null || q == null || perHost <= 0) return out;
        final List<String> list = clean(hosts);
        for (int i = 0; i < list.size(); i += HOST_BATCH) {
            final List<String> batch = new ArrayList<>();
            for (final String h : list.subList(i, Math.min(list.size(), i + HOST_BATCH))) {
                batch.add(h);
                batch.add("www." + h);
            }
            final ModifiableSolrParams p = params(q, collection);
            terms(p, HOST, batch, "scoutro_hosts");
            p.set("fl", String.join(",", SKU, TITLE, DESCRIPTION, TEXT, HOST, COLLECTION));
            p.set("sort", ID + " asc");
            p.set("rows", batch.size());
            p.set("group", true);
            p.set("group.field", HOST);
            p.set("group.limit", perHost);
            p.set("group.sort", ID + " asc");
            final QueryResponse response = solr.getResponseByParams(p);
            if (response.getGroupResponse() == null) continue;
            for (final GroupCommand command : response.getGroupResponse().getValues()) {
                for (final Group group : command.getValues()) {
                    for (final SolrDocument d : group.getResult()) {
                        final RagCandidate c = candidate(d, out.size());
                        if (c != null) out.add(c);
                    }
                }
            }
        }
        out.sort((a, b) -> a.url.compareTo(b.url));
        return out;
    }
}
