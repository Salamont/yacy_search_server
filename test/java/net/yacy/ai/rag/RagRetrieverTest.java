/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Retrieval stages, collection scope and fallback on the offline corpus. */
public class RagRetrieverTest {

    private final RagCorpus corpus;
    private final List<String> queries = new ArrayList<>();

    public RagRetrieverTest() throws Exception {
        this.corpus = new RagCorpus();
    }

    private RagRetriever.Result retrieve(final String question, final String collection) {
        return new RagRetriever(this.corpus.searcher(true, this.queries)).retrieve(RagQuery.prepare(question), collection, false, 8);
    }

    private void onlyIn(final RagRetriever.Result result, final String collection) {
        for (final String id : this.corpus.ids(result.selected)) {
            assertEquals(id, collection, this.corpus.collectionOf(id));
        }
    }

    @Test
    public void shortQuestionFindsTheProvidersWithAllTerms() {
        final RagRetriever.Result result = retrieve("Welche Pflegeheime gibt es in Köln?", "edelsenior-web");
        assertEquals("all", result.stage);
        final List<String> ids = this.corpus.ids(result.selected);
        assertEquals(List.of("sonnenschein", "rhein"), ids.subList(0, 2)); // the term in the title ranks first
        onlyIn(result, "edelsenior-web");
        assertEquals(List.of("pflegeheime pflegeheim köln"), this.queries); // one search
    }

    @Test
    public void tooStrictQuestionFallsBackToTheMajorityOfTerms() {
        final RagRetriever.Result result = retrieve("Pflegeheim Köln Demenz Schwimmbad Haustiere", "edelsenior-web");
        assertEquals("majority", result.stage);
        assertTrue(this.corpus.ids(result.selected).subList(0, 2).containsAll(List.of("rhein", "sonnenschein")));
        final RagRetriever.Result stopwords = retrieve(
                "Gibt es in der Nähe von Köln ein Pflegeheim, das auch für Menschen mit Demenz geeignet ist?", "edelsenior-web");
        assertEquals("majority", stopwords.stage); // "geeignet" is in no document
        assertTrue(this.corpus.ids(stopwords.selected).containsAll(List.of("rhein", "sonnenschein")));
    }

    @Test
    public void longQuestion() {
        final RagRetriever.Result result = retrieve("Meine Mutter ist 84 Jahre alt, lebt allein in Köln und kann sich wegen "
                + "ihrer beginnenden Demenz nicht mehr selbst versorgen. Wir suchen deshalb ein Pflegeheim in Köln oder in der "
                + "Nähe, das einen geschützten Bereich für Menschen mit Demenz hat und in dem Angehörige jederzeit zu Besuch "
                + "kommen können. Was gibt es da und worauf sollten wir achten?", "edelsenior-web");
        assertTrue(this.corpus.ids(result.selected).subList(0, 2).containsAll(List.of("rhein", "sonnenschein")));
    }

    @Test
    public void zeroOneAndManyHits() {
        assertEquals("none", retrieve("Welche Anbieter gibt es für Weltraumtourismus?", "edelsenior-web").stage);
        assertEquals(0, retrieve("Welche Anbieter gibt es für Weltraumtourismus?", "edelsenior-web").selected.size());
        assertEquals("no-terms", retrieve("Was gibt es?", "edelsenior-web").stage);
        final RagRetriever.Result one = retrieve("Gibt es ein Hospiz in Düsseldorf?", "edelsenior-web");
        assertEquals(List.of("hospiz"), this.corpus.ids(one.selected));
        final RagRetriever.Result many = retrieve("Welche Pflegeheime gibt es?", "edelsenior-web");
        assertEquals(8, many.selected.size()); // capped at maxSources
        assertTrue(many.candidates > 8);
        onlyIn(many, "edelsenior-web");
        // 25 hits of the search: the best 8 by matched terms, then by the search rank
        final List<RagCandidate> hits = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            hits.add(new RagCandidate("https://p" + i + ".example/", "Haus " + i, "", i % 5 == 0 ? "Pflegeheim in Köln" : "Pflegeheim",
                    "p" + i + ".example", List.of("edelsenior-web"), i));
        }
        final RagRetriever.Result ranked = new RagRetriever((q, c, n, g) -> hits).retrieve(RagQuery.prepare("Pflegeheim Köln"), "edelsenior-web", false, 8);
        assertEquals(25, ranked.candidates);
        assertEquals("all", ranked.stage);
        assertEquals(5, ranked.selected.size()); // only the five with both terms
        assertEquals("https://p0.example/", ranked.selected.get(0).candidate.url);
        final RagRetriever.Result capped = new RagRetriever((q, c, n, g) -> hits).retrieve(RagQuery.prepare("Pflegeheim"), "edelsenior-web", false, 8);
        assertEquals(8, capped.selected.size());
    }

    @Test
    public void collectionsAreNeverMixed() {
        // "Pflegeheim Köln" also occurs in bauteamcheck-web (a construction company built one)
        final RagRetriever.Result senior = retrieve("Pflegeheim in Köln", "edelsenior-web");
        onlyIn(senior, "edelsenior-web");
        final RagRetriever.Result whole = retrieve("Pflegeheim in Köln", null);
        assertTrue(this.corpus.ids(whole.selected).contains("bau-koeln")); // without a collection: the whole index, as before
        final RagRetriever.Result stack = retrieve("Welche Agentur in Hamburg arbeitet mit Shopware?", "stackfinder-web");
        assertEquals("shopware-hamburg", this.corpus.ids(stack.selected).get(0));
        onlyIn(stack, "stackfinder-web");
        onlyIn(retrieve("Coaching in Köln", "checkthecoach-web"), "checkthecoach-web");
        // a search that ignores the collection filter still cannot mix: foreign candidates are dropped
        final RagRetriever leaky = new RagRetriever(this.corpus.searcher(false, this.queries));
        final RagRetriever.Result dropped = leaky.retrieve(RagQuery.prepare("Pflegeheim in Köln"), "edelsenior-web", false, 8);
        assertTrue(dropped.foreignDropped > 0);
        onlyIn(dropped, "edelsenior-web");
    }

    @Test
    public void explicitCollectionWinsAndDisablesTheGlobalSearch() {
        final RagRetriever.Result result = new RagRetriever(this.corpus.searcher(true, this.queries))
                .retrieve(RagQuery.prepare("collection:bauteamcheck-web Pflegeheim Köln"), "edelsenior-web", true, 8);
        assertEquals("edelsenior-web", result.collection);
        assertTrue(!result.global);
        onlyIn(result, "edelsenior-web");
    }

    @Test
    public void baseFormsFindDocumentsWithTheOtherWordForm() {
        // "Pflegeheime" alone would only find the documents with the plural
        final RagRetriever.Result result = retrieve("Gibt es Pflegeheime mit Demenzgarten?", "edelsenior-web");
        assertTrue(this.corpus.ids(result.selected).contains("waldblick"));
        assertEquals("pflegeheime pflegeheim demenzgarten demenzgart", this.queries.get(0));
    }

    @Test
    public void theP2PSearchGetsThePlainWords() {
        final List<String> seen = new ArrayList<>();
        final RagRetriever empty = new RagRetriever((query, collection, count, global) -> {
            seen.add(query + (global ? " @global" : ""));
            return new ArrayList<>();
        });
        assertEquals("none", empty.retrieve(RagQuery.prepare("Pflegeheime Köln"), null, true, 8).stage);
        assertEquals("none", empty.retrieve(RagQuery.prepare("Pflegeheime Köln"), "edelsenior-web", true, 8).stage);
        // remote peers require every word; a collection keeps the search local
        assertEquals(List.of("pflegeheime köln @global", "pflegeheime pflegeheim köln"), seen);
    }
}
