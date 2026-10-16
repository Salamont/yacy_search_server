/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
import java.io.File;
import java.nio.file.*;
import java.util.*;
import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import org.apache.solr.common.SolrInputDocument;

/** Append only to the closed, marked, freshly generated disposable dashboard fixture. */
public class RagSynonymFixture {
    public static void main(String[] args) throws Exception {
        final Path root = Path.of(args[0]);
        final Path index = root.resolve("DATA/INDEX/webportal/SEGMENTS/solr_9_0");
        if (!Files.isRegularFile(root.resolve(".scoutro-dashboard-disposable")) || !Files.isDirectory(index))
            throw new IllegalArgumentException("Requires a fresh disposable dashboard fixture");
        final EmbeddedInstance instance = new EmbeddedInstance(new File("defaults/solr"), index.toFile(), "collection1", new String[]{"collection1", "webgraph"});
        try {
            if (instance.getDefaultServer().query(new org.apache.solr.client.solrj.SolrQuery("*:*").setRows(0)).getResults().getNumFound() != 9)
                throw new IllegalArgumentException("Refusing an existing or already extended fixture");
            add(instance, "alpha", "stackfinder-web", "Television ist verfügbar QUOKKA_FUNDSTELLE_A.", List.of());
            add(instance, "computer", "stackfinder-web", "Allgemeine Hinweise. ".repeat(150)
                    + "Unser Computer und Elektronengehirn hat eine besondere Akkulaufzeit SYNONYM_COMPUTER_BELEG.", List.of("rechner"));
            add(instance, "long", "bauteamcheck-web", "Pflegeheim Seniorenheim Beratung Automatisierung Integration Anwendung Dienstleistung. "
                    + "Allgemeine Hinweise. ".repeat(150) + "Der Computer hat eine besondere Akkulaufzeit SYNONYM_LONG_BELEG.", List.of("rechner"));
            add(instance, "meeting", "checkthecoach-web", "Besprechung des Teamessens. NEGATIVE_MEETING_BELEG.", List.of("beratung"));
            add(instance, "care", "edelsenior-web", "Unsere Seniorenresidenz stellt sich vor SYNONYM_CARE_BELEG.", List.of("altenheim"));
            add(instance, "kita", "checkthecoach-web", "Kindertagesstätte und Mittagessen. NEGATIVE_KITA_BELEG.", List.of());
            instance.getDefaultServer().commit();
        } finally {
            instance.close(); net.yacy.cora.protocol.Domains.close(); net.yacy.cora.util.ConcurrentLog.shutdown();
        }
    }
    private static void add(EmbeddedInstance instance, String id, String collection, String text, List<String> synonyms) throws Exception {
        // Synthetic public-TLD URL: no page is fetched. YaCy's existing local-address guard remains enabled.
        final DigestURL url = new DigestURL("https://www.quokka-pflege-rag.de/synonym-" + id);
        final SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", ASCII.String(url.hash())); doc.setField("sku", url.toNormalform(true));
        doc.setField("host_s", url.getHost()); doc.setField("host_id_s", url.hosthash()); doc.setField("url_protocol_s", "https");
        doc.setField("collection_sxt", List.of(collection)); doc.setField("title", List.of("Testbeleg"));
        doc.setField("text_t", text); doc.setField("synonyms_sxt", synonyms); doc.setField("content_type", List.of("text/html"));
        doc.setField("last_modified", new Date(0)); doc.setField("size_i", text.length()); doc.setField("httpstatus_i", 200);
        instance.getDefaultServer().add(doc);
    }
}
