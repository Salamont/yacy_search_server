/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.cora.language.synonyms.SynonymLibrary;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Real repository schema, live analyzer and actual eDisMax results; the corpus searcher cannot model this. */
public class RagSynonymsSolrTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private EmbeddedInstance instance;
    private String qf;
    @Before public void setup() throws Exception {
        this.instance = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1", new String[]{"collection1"});
        final Properties defaults = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(new File("defaults/yacy.init").toPath())) { defaults.load(reader); }
        this.qf = defaults.getProperty("search.ranking.solr.collection.boostfields.tmpa.0").replace(',', ' ');
        final File dictionary = this.tmp.newFolder("dictionary");
        // Small faithful fixtures of the already shipped groups; no live/imported dictionary.
        Files.writeString(new File(dictionary, "test.txt").toPath(), "Computer,Rechner,Elektronengehirn\nAltenheim,Seniorenresidenz\nBeratung,Consulting,Besprechung\nWärmedämmung,Wärmeisolierung\n", StandardCharsets.UTF_8);
        SynonymLibrary.init(dictionary);
    }
    @After public void close() {
        if (this.instance != null) this.instance.close();
        SynonymLibrary.init(new File(this.tmp.getRoot(), "missing"));
    }
    private void add(final String id, final String collection, final String text, final List<String> synonyms) throws Exception {
        final SolrInputDocument doc = new SolrInputDocument();
        doc.setField("id", id); doc.setField("sku", "https://" + id + ".example/");
        doc.setField("title", List.of("Testquelle")); doc.setField("text_t", text);
        doc.setField("collection_sxt", List.of(collection)); doc.setField("synonyms_sxt", synonyms);
        this.instance.getDefaultServer().add(doc); this.instance.getDefaultServer().commit();
    }
    private List<RagCandidate> search(final RagQuery query, final String fields, final String collection, final boolean includeText) throws Exception {
        final SolrQuery solr = new SolrQuery(query.searchString(true));
        solr.set("defType", "edismax"); solr.set("qf", fields); solr.set("mm", "1"); solr.setRows(30);
        if (collection != null) solr.addFilterQuery("collection_sxt:\"" + collection + "\"");
        final RagSynonyms resolver = new RagSynonyms(this.instance.getDefaultCore().getLatestSchema(), fields, query,
                SynonymLibrary::getSynonyms, RagSynonyms.DEFAULT_BLOCKED);
        final List<RagCandidate> results = new ArrayList<>();
        for (final SolrDocument doc : this.instance.getDefaultServer().query(solr).getResults()) {
            final String text = includeText ? doc.getFieldValue("text_t").toString() : "";
            final String title = "Testquelle";
            results.add(new RagCandidate(doc.getFieldValue("sku").toString(), title, "", text, "test.example",
                    List.of(doc.getFieldValues("collection_sxt").iterator().next().toString()), results.size(),
                    resolver.forDocument(title, "", text, doc.getFieldValues("synonyms_sxt"))));
        }
        return results;
    }
    private RagRetriever.Result retrieve(final RagQuery q, final List<RagCandidate> results, final String collection) {
        return new RagRetriever((query, c, n, g) -> results).retrieve(q, collection, false, 8);
    }
    @Test public void realIndexSynonymsSurviveAcrossAllCollections() throws Exception {
        final String[][] cases = {{"rechner", "Computer", "stackfinder-web"}, {"altenheim", "Seniorenresidenz", "edelsenior-web"},
                {"beratung", "Consulting", "checkthecoach-web"}, {"wärmedämmung", "Wärmeisolierung", "bauteamcheck-web"}};
        int id = 0;
        for (final String[] c : cases) {
            add("case" + id++, c[2], "Wir bieten " + c[1] + " in Köln.", List.of(c[0]));
            final RagQuery query = RagQuery.prepare(c[0]);
            final List<RagCandidate> hits = search(query, this.qf, c[2], true);
            assertEquals(1, hits.size());
            assertEquals(0, search(query, this.qf.replaceAll("synonyms_sxt\\^\\S+", ""), c[2], true).size());
            final RagRetriever.Result result = retrieve(query, hits, c[2]);
            assertEquals(1, result.selected.size());
            assertEquals(0, RagCandidate.matched(query, RagQuery.fold(result.selected.get(0).candidate.text)).size());
            assertEquals(1, result.selected.get(0).strong);
        }
    }
    @Test public void liveQueryAnalyzerWorksWithoutIndexMetadataAndDoesNotInventText() throws Exception {
        add("tv", "stackfinder-web", "Television is available as a device.", List.of());
        final RagQuery q = RagQuery.prepare("TV");
        final List<RagCandidate> hits = search(q, "text_t", "stackfinder-web", true);
        assertEquals("Solr", 1, hits.size());
        assertEquals("RAG query=" + q, 1, retrieve(q, hits, "stackfinder-web").selected.size());
        assertEquals(0, retrieve(q, search(q, "text_t", "stackfinder-web", false), "stackfinder-web").selected.size());
        assertEquals(0, retrieve(q, hits, "edelsenior-web").selected.size());
        final RagSynonyms filtered = new RagSynonyms(this.instance.getDefaultCore().getLatestSchema(), "text_t", q,
                SynonymLibrary::getSynonyms, RagSynonyms.DEFAULT_BLOCKED, Set.of("other"));
        assertTrue("Concepts filtered out of the actual search gain no alternatives",
                filtered.forDocument("Testquelle", "", "Television is available as a device.", List.of()).isEmpty());
    }
    @Test public void exactAndExpandedConceptsCountOnceAndShareExcerptMatching() throws Exception {
        final String longText = "Köln hat viele schöne Plätze. " + "Allgemeine Informationen zum Unternehmen. ".repeat(80)
                + "Unser Computer und Elektronengehirn hat eine besondere Akkulaufzeit. " + "Weitere Informationen folgen. ".repeat(80);
        add("expanded", "stackfinder-web", longText, List.of("rechner"));
        add("exact", "stackfinder-web", "Rechner Köln", List.of());
        final RagQuery q = RagQuery.prepare("Rechner Köln");
        final List<RagCandidate> hits = search(q, this.qf, "stackfinder-web", true);
        final RagRetriever.Result result = retrieve(q, hits, "stackfinder-web");
        assertEquals("all", result.stage); assertEquals(2, result.selected.size());
        final RagCandidate expanded = result.selected.stream().map(hit -> hit.candidate)
                .filter(candidate -> candidate.url.contains("expanded")).findFirst().orElseThrow();
        assertEquals(2, expanded.matched(q).size());
        assertTrue(RagContext.excerpt(expanded, q, 300).contains("Akkulaufzeit"));
    }
    @Test public void multiWordAnalyzerAlternativesAndLongQuestionsStillCountOneConcept() throws Exception {
        add("graph", "stackfinder-web", "cccbar cccbaz", List.of());
        final RagQuery q = RagQuery.prepare("Bitte suche cccfoo");
        final List<RagCandidate> hits = search(q, "text_t", "stackfinder-web", true);
        assertEquals(1, hits.size());
        final RagRetriever.Result result = retrieve(q, hits, "stackfinder-web");
        assertEquals(1, result.selected.size()); assertEquals(1, result.selected.get(0).strong);
        assertEquals(1, result.selected.get(0).candidate.matched(q).size());
        final RagQuery longQuestion = RagQuery.prepare("Bitte suche ".repeat(30) + "cccfoo");
        assertEquals(1, retrieve(longQuestion, search(longQuestion, "text_t", "stackfinder-web", true), "stackfinder-web").selected.size());
        final RagCandidate multiple = new RagCandidate("https://test.example/", "Test", "", "Computer und Elektronengehirn", "test.example", List.of(), 0,
                Map.of("rechner", Set.of("Computer", "Elektronengehirn")));
        assertEquals(1, multiple.matched(RagQuery.prepare("Rechner")).size());
    }

    @Test public void ambiguousAliasesAndShortPrefixesRemainRejected() throws Exception {
        add("meeting", "checkthecoach-web", "Besprechung des Teamessens.", List.of("beratung"));
        final RagQuery q = RagQuery.prepare("Beratung");
        final List<RagCandidate> hits = search(q, this.qf, "checkthecoach-web", true);
        assertEquals(1, hits.size()); assertEquals(0, retrieve(q, hits, "checkthecoach-web").selected.size());
        final RagCandidate kita = new RagCandidate("https://kita.example/", "Kita", "", "Kindertagesstätte Berlin", "kita.example", List.of(), 0);
        assertFalse(kita.matched(RagQuery.prepare("KI Berlin")).stream().anyMatch(t -> t.text.equals("ki")));
    }
    @Test public void negationsAndThirdPartyAttributionRemainInTheSourceRatherThanBecomingFacts() {
        final RagQuery q = RagQuery.prepare("KI Beratung");
        for (final String text : List.of("Wir bieten keine KI Beratung an.", "Die KI Beratung bietet ausschließlich die Partnerfirma an.")) {
            final RagCandidate candidate = new RagCandidate("https://test.example/", "Test", "", text, "test.example", List.of(), 0);
            final RagRetriever.Result result = retrieve(q, List.of(candidate), null);
            assertEquals(1, result.selected.size());
            assertEquals(text, RagContext.excerpt(candidate, q, 400));
        }
    }
}
