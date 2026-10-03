/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Offline fixture generator for a NEW disposable peer only. Never starts a crawl.
 */
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.dashboard.DashboardMetrics;

public class DashboardFixture {
    public static void main(final String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected new disposable peer root");
        final Path root = Path.of(args[0]);
        final Path core = root.resolve("DATA/INDEX/webportal/SEGMENTS/solr_9_0");
        if (!Files.isRegularFile(root.resolve(".scoutro-dashboard-disposable")) || Files.exists(core)) {
            throw new IllegalArgumentException("Refusing to seed an existing or unmarked peer");
        }
        final EmbeddedInstance instance = new EmbeddedInstance(new File("defaults/solr"), core.toFile(),
                "collection1", new String[] {"collection1", "webgraph"});
        try {
            final EmbeddedSolrConnector connector = new EmbeddedSolrConnector(instance, "collection1");
            final DashboardMetrics.Query query = params -> {
                try (SolrQueryRequest request = connector.request(params)) {
                    final SolrQueryResponse result = connector.query(request);
                    if (result.getException() != null) throw new java.io.IOException(result.getException());
                    return result.getValues();
                }
            };
            final DashboardMetrics.Snapshot empty = DashboardMetrics.read(query);
            require(empty.index.pages == 0 && empty.index.hosts == 0, "empty index counts");
            for (int i = 0; i < 7; i++) {
                final String host = i < 4 ? "edel.example" : "bau.example";
                final String collection = i < 3 ? "edelsenior-web" : i < 5 ? "stackfinder-web" : "bauteamcheck-web";
                instance.getDefaultServer().add(document("https://" + host + "/page-" + i, collection, 200, null));
            }
            instance.getDefaultServer().add(document("https://failed.example/missing", "edelsenior-web", 404, "fail"));
            instance.getDefaultServer().add(document("https://excluded.example/blocked", "edelsenior-web", 200, "excl"));
            instance.getDefaultServer().commit();
            final DashboardMetrics.Snapshot data = DashboardMetrics.read(query);
            require(data.index.pages == 7, "successful indexed documents");
            require(data.index.hosts == 2, "distinct indexed hosts");
            require(data.collections.get("edelsenior-web").pages == 3, "EdelSenior pages");
            require(data.collections.get("edelsenior-web").hosts == 1, "EdelSenior hosts");
            require(data.collections.get("checkthecoach-web").pages == 0, "missing collection");
            require(data.collections.get("stackfinder-web").pages == 2, "StackFinder pages");
            require(data.collections.get("stackfinder-web").hosts == 2, "StackFinder hosts");
            require(data.collections.get("bauteamcheck-web").pages == 2, "Bauteamcheck pages");
            require(connector.getSize() == 9, "read-only aggregates retain failure records");
            System.out.println("PASS: 10 real embedded-Solr checks; seeded 7 pages / 2 hosts and 2 excluded error records");
        } finally {
            instance.close();
            net.yacy.cora.protocol.Domains.close();
            net.yacy.cora.util.ConcurrentLog.shutdown();
        }
    }

    private static SolrInputDocument document(final String url, final String collection, final int status, final String fail) throws Exception {
        final DigestURL digest = new DigestURL(url);
        final SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", ASCII.String(digest.hash()));
        doc.setField("sku", url);
        doc.setField("host_s", digest.getHost());
        doc.setField("host_id_s", digest.hosthash());
        doc.setField("url_protocol_s", "https");
        doc.setField("collection_sxt", List.of(collection));
        doc.setField("title", List.of("Dashboard fixture"));
        doc.setField("text_t", "Scoutro dashboard disposable fixture");
        // Also support the real Search API's strict text-content filter offline.
        doc.setField("content_type", List.of("text/html"));
        doc.setField("last_modified", new java.util.Date(0));
        doc.setField("size_i", 42);
        doc.setField("httpstatus_i", status);
        if (fail != null) doc.setField("failtype_s", fail);
        return doc;
    }

    private static void require(final boolean condition, final String what) {
        if (!condition) throw new AssertionError(what);
    }
}
