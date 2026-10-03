/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.search.Switchboard;

/** Read-only index browser. Literal input, bounded results, independent collection filter. */
final class IndexBrowse {
    @FunctionalInterface interface Query { NamedList<Object> execute(ModifiableSolrParams p) throws IOException; }
    private final Query query;
    IndexBrowse(final Query query) { this.query = query; }

    static IndexBrowse current() throws ApiException {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null || sb.index == null || sb.index.fulltext().getDefaultConnector() == null)
            throw new ApiException(503, "index_unavailable", "The local index is unavailable.");
        return new IndexBrowse(p -> sb.index.fulltext().getDefaultConnector().getResponseByParams(p).getResponse());
    }

    JSONObject browse(final Map<String, String> input, final List<String> collections) throws ApiException {
        for (final String key : input.keySet()) if (!Set.of("q", "collection", "offset", "limit").contains(key))
            throw ApiException.invalid(key, "Unsupported index browser parameter.");
        final String text = input.getOrDefault("q", "").trim();
        if (text.length() > 250 || text.codePoints().anyMatch(Character::isISOControl))
            throw ApiException.invalid("q", "Host/URL search must contain at most 250 characters without controls.");
        final int offset = number(input, "offset", 0, 0, 10000);
        final int limit = number(input, "limit", 50, 1, 100);
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("defType", "lucene");
        final String literal = ClientUtils.escapeQueryChars(text);
        p.set("q", text.isEmpty() ? "*:*" : "(host_s:*" + literal + "* OR sku:*" + literal + "*)");
        p.set("start", offset); p.set("rows", limit); p.set("timeAllowed", 2000);
        p.set("sort", "sku asc,id asc");
        p.set("fl", "id,sku,host_s,title,collection_sxt,httpstatus_i");
        if (collections != null) {
            final List<String> values = new ArrayList<>();
            for (final String c : collections) {
                if (!c.matches("[A-Za-z0-9_-]{1,64}")) throw ApiException.invalid("collection", "Invalid collection.");
                values.add("\"" + c + "\"");
            }
            p.add("fq", values.isEmpty() ? "(*:* AND -*:*)" : "collection_sxt:(" + String.join(" OR ", values) + ")");
        }
        try {
            final NamedList<Object> result = query.execute(p);
            final Object header = result.get("responseHeader");
            final Object partial = header instanceof NamedList ? ((NamedList<?>) header).get("partialResults")
                    : header instanceof Map ? ((Map<?, ?>) header).get("partialResults") : null;
            if (partial != null && !"false".equals(String.valueOf(partial))) throw new IOException("Partial index response");
            final Object rows = result.get("response");
            if (!(rows instanceof SolrDocumentList)) throw new IOException("Missing index response");
            final SolrDocumentList docs = (SolrDocumentList) rows;
            final JSONArray items = new JSONArray();
            for (final SolrDocument doc : docs) {
                final JSONArray visible = new JSONArray();
                final Collection<Object> memberships = doc.getFieldValues("collection_sxt");
                if (memberships != null) for (final Object c : memberships)
                    if (collections == null || collections.contains(String.valueOf(c))) visible.put(String.valueOf(c));
                final Collection<Object> titles = doc.getFieldValues("title");
                items.put(Json.obj("id", doc.getFieldValue("id"), "url", doc.getFieldValue("sku"),
                        "host", doc.getFieldValue("host_s"), "title", titles == null || titles.isEmpty() ? "" : titles.iterator().next(),
                        "collections", visible, "httpStatus", doc.getFieldValue("httpstatus_i")));
            }
            return Json.obj("q", text, "collection", input.getOrDefault("collection", ""),
                    "offset", offset, "limit", limit, "total", docs.getNumFound(), "documents", items);
        } catch (final IOException | RuntimeException e) {
            throw new ApiException(503, "index_unavailable", "Index browsing is temporarily unavailable; retry later.");
        }
    }

    private static int number(final Map<String, String> input, final String key, final int fallback,
            final int min, final int max) throws ApiException {
        try {
            final int value = Integer.parseInt(input.getOrDefault(key, String.valueOf(fallback)));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (final NumberFormatException e) { throw ApiException.invalid(key, "Value outside the allowed range."); }
    }
}
