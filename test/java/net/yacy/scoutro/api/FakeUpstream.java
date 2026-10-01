package net.yacy.scoutro.api;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.json.JSONArray;
import org.w3c.dom.Document;

/**
 * In-memory stand-in for the YaCy HTTP APIs: records every upstream call
 * with its parameters and simulates search results per collection, Solr
 * answers and crawl profiles.
 */
class FakeUpstream implements Upstream {

    static final class Call {
        final String path;
        final YaCyLoopback.Params params;

        Call(final String path, final YaCyLoopback.Params params) {
            this.path = path;
            this.params = params == null ? new YaCyLoopback.Params() : params;
        }

        String get(final String key) {
            return this.params.get(key);
        }
    }

    static final class Crawl {
        final String id;
        final String name;
        String status = "active";
        final String collection;
        String mustNotMatch = "";

        Crawl(final String id, final String name, final String collection) {
            this.id = id;
            this.name = name;
            this.collection = collection;
        }
    }

    final List<Call> calls = new ArrayList<>();
    /** Search result URLs per collection; key null = results without a collection filter. */
    final Map<String, List<String>> searchResults = new LinkedHashMap<>();
    final Map<String, Crawl> crawls = new LinkedHashMap<>();
    long solrNumFound = 3;
    /** Fault injection for crawl starts: "reject" (YaCy refuses), "crash" (error after the profile exists), "lost" (connection lost before YaCy answered, profile exists). */
    String crawlStartFault = null;
    /** numFound for negative collection filters (documents outside a scope). */
    long outsideNumFound = 0;
    private int nextCrawl = 1;

    List<Call> calls(final String path) {
        final List<Call> out = new ArrayList<>();
        for (final Call c : this.calls) {
            if (c.path.equals(path)) {
                out.add(c);
            }
        }
        return out;
    }

    Call last(final String path) {
        final List<Call> l = calls(path);
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    @Override
    public String getAdmin(final String path, final YaCyLoopback.Params params) throws ApiException {
        this.calls.add(new Call(path, params));
        if ("yacysearch.json".equals(path)) {
            final String collection = params.get("collection");
            final List<String> urls = this.searchResults.getOrDefault(collection, new ArrayList<>());
            final JSONArray items = new JSONArray();
            for (final String u : urls) {
                items.put(Json.obj("title", "T " + u, "link", u, "description", "d", "host", "h", "guid", u));
            }
            return Json.obj("channels", new JSONArray().put(Json.obj("totalResults", String.valueOf(urls.size()),
                    "items", items))).toString();
        }
        if ("solr/select".equals(path)) {
            final JSONArray docs = new JSONArray();
            docs.put(Json.obj("sku", "https://example.com/", "title", new JSONArray().put("Example"),
                    "host_s", "example.com", "text_t", "Some text", "collection_sxt",
                    new JSONArray().put("edelsenior-web").put("checkthecoach-web")));
            final String fq = params.get("fq");
            final long found = fq != null && fq.startsWith("-") ? this.outsideNumFound : this.solrNumFound;
            return Json.obj("response", Json.obj("numFound", found, "docs", docs)).toString();
        }
        throw new ApiException(502, "upstream_error", "unexpected GET " + path);
    }

    @Override
    public String postAdmin(final String path, final YaCyLoopback.Params params) throws ApiException {
        this.calls.add(new Call(path, params));
        if ("Crawler_p.json".equals(path)) {
            if ("1".equals(params.get("crawlingstart"))) {
                final String fault = this.crawlStartFault;
                this.crawlStartFault = null;
                final String host = java.net.URI.create(params.get("crawlingURL")).getHost();
                if ("reject".equals(fault)) {
                    return Json.obj("success", false, "comment", "Crawling of " + host + " failed. Reason: test").toString();
                }
                if ("lostNoProfile".equals(fault)) {
                    throw new ApiException(502, "upstream_unreachable", "YaCy did not answer on the loopback interface.");
                }
                final String id = "crawl" + (this.nextCrawl++);
                final Crawl c = new Crawl(id, host, params.get("collection"));
                c.mustNotMatch = params.get("mustnotmatch") == null ? "" : params.get("mustnotmatch");
                this.crawls.put(id, c);
                if ("crash".equals(fault)) {
                    throw new IllegalStateException("simulated crash after YaCy started the crawl");
                }
                if ("failedButActive".equals(fault)) {
                    // Crawler_p reports a failure after it has activated the profile (start URL not stacked)
                    return Json.obj("success", true, "comment", "Crawling of '" + host + "' failed. Reason: double").toString();
                }
                if ("lost".equals(fault)) {
                    throw new ApiException(502, "upstream_unreachable", "YaCy did not answer on the loopback interface.");
                }
                return Json.obj("success", true, "comment", "Crawl of " + host + " started.").toString();
            }
            if ("1".equals(params.get("terminate"))) {
                this.crawls.remove(params.get("handle"));
                return Json.obj("success", true).toString();
            }
        }
        throw new ApiException(502, "upstream_error", "unexpected POST " + path);
    }

    @Override
    public Document getAdminXml(final String path, final YaCyLoopback.Params params) throws ApiException {
        this.calls.add(new Call(path, params));
        final StringBuilder sb = new StringBuilder();
        if ("CrawlProfileEditor_p.xml".equals(path)) {
            sb.append("<crawlProfiles>");
            for (final Crawl c : this.crawls.values()) {
                sb.append("<crawlProfile><handle>").append(c.id).append("</handle><name>").append(c.name)
                        .append("</name><status>").append(c.status).append("</status><depth>1</depth>")
                        .append("<domMaxPages>50</domMaxPages><collections>").append(c.collection)
                        .append("</collections><crawlerURLMustNotMatch>")
                        .append(c.mustNotMatch.replace("&", "&amp;").replace("<", "&lt;"))
                        .append("</crawlerURLMustNotMatch></crawlProfile>");
            }
            sb.append("</crawlProfiles>");
        } else if ("api/status_p.xml".equals(path)) {
            sb.append("<status><localcrawlerqueue><state>running</state><size>0</size></localcrawlerqueue>")
                    .append("<dbsize><urlpublictext>99</urlpublictext></dbsize></status>");
        } else {
            throw new ApiException(502, "upstream_error", "unexpected XML " + path);
        }
        return parse(sb.toString());
    }

    @Override
    public Document getPublicXml(final String path, final YaCyLoopback.Params params) throws ApiException {
        this.calls.add(new Call(path, params));
        return parse("<version><buildVersion>1.942</buildVersion><file>test</file></version>");
    }

    private static Document parse(final String xml) throws ApiException {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            throw new ApiException(502, "upstream_error", "bad test xml");
        }
    }
}
