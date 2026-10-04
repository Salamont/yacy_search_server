/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Offline; new disposable DATA only. */
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import org.apache.solr.common.SolrInputDocument;
import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;

/**
 * Seeds the RAG quality corpus (test/scoutro-rag/corpus.json) into the Solr core of a
 * new, marked disposable peer. Never crawls, never touches existing DATA.
 */
public class RagFixture {

    public static void main(final String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected: disposable root, corpus.json");
        final Path root = Path.of(args[0]);
        final Path core = root.resolve("DATA/INDEX/webportal/SEGMENTS/solr_9_0");
        if (!Files.isRegularFile(root.resolve(".scoutro-rag-disposable")) || Files.exists(core)) {
            throw new IllegalArgumentException("Refusing existing or unmarked DATA");
        }
        final JSONArray documents = new JSONObject(new String(Files.readAllBytes(Path.of(args[1])), StandardCharsets.UTF_8))
                .getJSONArray("documents");
        final EmbeddedInstance instance = new EmbeddedInstance(new File("defaults/solr"), core.toFile(),
                "collection1", new String[] {"collection1", "webgraph"});
        try {
            for (int i = 0; i < documents.length(); i++) {
                final JSONObject d = documents.getJSONObject(i);
                final String text = "heimverzeichnis".equals(d.optString("generate")) ? heimverzeichnis() : d.getString("text");
                instance.getDefaultServer().add(document(d.getString("url"), d.getString("collection"), d.getString("title"),
                        d.optString("description", ""), text));
            }
            instance.getDefaultServer().commit();
            System.out.println("PASS: seeded " + documents.length() + " RAG corpus documents");
        } finally {
            instance.close();
            net.yacy.cora.protocol.Domains.close();
            net.yacy.cora.util.ConcurrentLog.shutdown();
        }
    }

    /** A long directory page (about 45 000 characters); the Aachen entries are near the end. */
    static String heimverzeichnis() {
        final String[] cities = {"Bielefeld", "Dortmund", "Essen", "Münster", "Wuppertal", "Gelsenkirchen", "Duisburg", "Hagen", "Bochum", "Krefeld"};
        final String[] focus = {"Schwerpunkt Kurzzeitpflege", "Schwerpunkt Palliativpflege", "mit Garten", "nahe Innenstadt", "mit Hausarzt im Haus"};
        final StringBuilder text = new StringBuilder("Dieses Heimverzeichnis listet Pflegeheime in Nordrhein-Westfalen nach Städten mit Platzzahl und Schwerpunkt. ");
        int n = 0;
        while (text.length() < 42000) {
            final String city = cities[n % cities.length];
            text.append("Pflegeheim Haus ").append(n + 1).append(" in ").append(city).append(": ").append(40 + n % 90)
                .append(" Plätze, ").append(focus[n % focus.length]).append(", Träger gemeinnützig, Besuchszeiten täglich. ");
            n++;
        }
        text.append("Aachen: Pflegeheim Waldblick in Aachen, 70 Plätze, Demenzgarten, am Aachener Wald. ")
            .append("Aachen: Haus Lousberg in Aachen, 64 Plätze, Schwerpunkt Palliativpflege. ")
            .append("Ende des Verzeichnisses. Angaben ohne Gewähr.");
        return text.toString();
    }

    static SolrInputDocument document(final String url, final String collection, final String title, final String description,
            final String text) throws Exception {
        final DigestURL digest = new DigestURL(url);
        final SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", ASCII.String(digest.hash()));
        doc.setField("sku", url);
        doc.setField("host_s", digest.getHost());
        doc.setField("host_id_s", digest.hosthash());
        doc.setField("url_protocol_s", "https");
        doc.setField("collection_sxt", List.of(collection));
        doc.setField("title", List.of(title));
        if (!description.isEmpty()) doc.setField("description_txt", List.of(description));
        doc.setField("text_t", text);
        doc.setField("content_type", List.of("text/html"));
        doc.setField("language_s", "de");
        doc.setField("last_modified", new Date(0));
        doc.setField("load_date_dt", new Date(0));
        doc.setField("size_i", text.length());
        doc.setField("wordcount_i", text.split("\\s+").length);
        doc.setField("httpstatus_i", 200);
        return doc;
    }
}
