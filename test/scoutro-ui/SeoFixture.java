/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Offline; new disposable DATA only. */
import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.search.schema.CollectionConfiguration;

import org.apache.solr.common.SolrInputDocument;

import java.io.File;
import java.nio.file.*;
import java.util.*;

public class SeoFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected disposable root");
        Path root = Path.of(args[0]), core = root.resolve("DATA/INDEX/webportal/SEGMENTS/solr_9_0");
        if (!Files.isRegularFile(root.resolve(".scoutro-seo-disposable")) || Files.exists(core))
            throw new IllegalArgumentException("Refusing existing or unmarked DATA");
        Path config = root.resolve("DATA/SETTINGS/solr.collection.schema");
        Files.copy(Path.of("defaults/solr.collection.schema"), config);
        new CollectionConfiguration(config.toFile(), true).ensureCitationFields();
        EmbeddedInstance instance =
                new EmbeddedInstance(
                        new File("defaults/solr"),
                        core.toFile(),
                        "collection1",
                        new String[] {"collection1", "webgraph"});
        try {
            instance.getDefaultServer()
                    .add(doc("a.example", "one", List.of("visible"), 7, 4, 3, 2, 29, null));
            instance.getDefaultServer()
                    .add(
                            doc(
                                    "a.example",
                                    "zero",
                                    List.of("visible", "secret"),
                                    0,
                                    0,
                                    0,
                                    0,
                                    29,
                                    null));
            instance.getDefaultServer()
                    .add(doc("a.example", "missing", List.of("visible"), 0, 0, 0, 0, -1, null));
            instance.getDefaultServer()
                    .add(
                            doc(
                                    "a.example",
                                    "pending",
                                    List.of("visible"),
                                    99,
                                    90,
                                    9,
                                    9,
                                    29,
                                    "CITATION"));
            instance.getDefaultServer()
                    .add(doc("a.example", "inconsistent", List.of("secret"), 5, 5, 5, 5, 29, null));
            instance.getDefaultServer()
                    .add(doc("b.example", "private", List.of("secret"), 2, 0, 2, 2, 1, null));
            for (int i = 0; i < 24; i++)
                instance.getDefaultServer()
                        .add(doc("a.example", "z" + i, List.of("visible"), 0, 0, 0, 0, 29, null));
            instance.getDefaultServer().commit();
            System.out.println(
                    "PASS: seeded 30 offline URL fixtures, two hosts, two collections, all four"
                        + " reference statuses");
        } finally {
            instance.close();
            net.yacy.cora.protocol.Domains.close();
            net.yacy.cora.util.ConcurrentLog.shutdown();
        }
    }

    private static SolrInputDocument doc(
            String host,
            String path,
            List<String> collections,
            int total,
            int internal,
            int external,
            int hosts,
            int extent,
            String marker)
            throws Exception {
        String url = "https://" + host + "/" + path;
        DigestURL d = new DigestURL(url);
        SolrInputDocument s = new SolrInputDocument();
        s.setField("id", ASCII.String(d.hash()));
        s.setField("sku", url);
        s.setField("host_s", host);
        s.setField("host_id_s", d.hosthash());
        s.setField("collection_sxt", collections);
        s.setField("references_i", total);
        s.setField("references_internal_i", internal);
        s.setField("references_external_i", external);
        s.setField("references_exthosts_i", hosts);
        s.setField("host_extent_i", extent);
        if (marker != null) s.setField("process_sxt", List.of(marker));
        s.setField(
                "title",
                List.of(path.equals("one") ? "<img src=x onerror=alert(1)>" : "Title " + path));
        s.setField("description_txt", List.of("Offline fixture description"));
        s.setField("h2_txt", List.of("Second heading"));
        s.setField("h3_txt", List.of("Third heading"));
        s.setField("text_t", "Scoutro SEO fixture searchable content");
        s.setField("content_type", List.of("text/html"));
        s.setField("size_i", 100);
        s.setField("h1_txt", List.of("Heading"));
        s.setField("wordcount_i", 100);
        s.setField("crawldepth_i", 2);
        s.setField("httpstatus_i", 200);
        s.setField("url_protocol_s", "https");
        s.setField("language_s", "en");
        s.setField("load_date_dt", new Date(1000));
        s.setField("last_modified", new Date(0));
        s.setField("inboundlinkscount_i", 8);
        s.setField("outboundlinkscount_i", 3);
        return s;
    }
}
