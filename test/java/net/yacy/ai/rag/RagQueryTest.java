/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Query preparation without an LLM. */
public class RagQueryTest {

    private static List<String> terms(final RagQuery query) {
        final List<String> terms = new ArrayList<>();
        for (final RagQuery.Term term : query.terms) terms.add(term.toString());
        return terms;
    }

    @Test
    public void shortNaturalQuestion() {
        final RagQuery query = RagQuery.prepare("Welche Pflegeheime gibt es in Köln?");
        assertEquals(List.of("pflegeheime", "köln"), terms(query));
        // the local search has no stemming and no working wildcards: word and base form
        assertEquals("pflegeheime pflegeheim köln", query.searchString(true));
        assertEquals("pflegeheime köln", query.searchString(false));
    }

    @Test
    public void questionWithManyStopwords() {
        final RagQuery query = RagQuery.prepare("Gibt es in der Nähe von Köln ein Pflegeheim, das auch für Menschen mit Demenz geeignet ist?");
        assertEquals(List.of("köln", "pflegeheim", "menschen", "demenz", "geeignet"), terms(query));
        // only stopwords and filler: no search instead of random hits
        assertTrue(RagQuery.prepare("Was gibt es denn da so?").isEmpty());
        assertTrue(RagQuery.prepare("What is there?").isEmpty());
    }

    @Test
    public void longQuestionKeepsTheMostSpecificTerms() {
        final RagQuery query = RagQuery.prepare("Meine Mutter ist 84 Jahre alt, lebt allein in Köln und kann sich wegen ihrer "
                + "beginnenden Demenz nicht mehr selbst versorgen. Wir suchen deshalb ein Pflegeheim in Köln oder in der Nähe, "
                + "das einen geschützten Bereich für Menschen mit Demenz hat und in dem Angehörige jederzeit zu Besuch kommen "
                + "können. Was gibt es da und worauf sollten wir achten?");
        assertEquals(RagQuery.MAX_TERMS, query.terms.size());
        final List<String> terms = terms(query);
        for (final String noun : new String[] {"köln", "demenz", "pflegeheim", "bereich", "menschen", "angehörige"}) {
            assertTrue(noun + " in " + terms, terms.contains(noun));
        }
        assertFalse(terms.contains("84"));
        assertFalse(terms.contains("suchen"));
    }

    @Test
    public void stemsMatchInflectedFormsAndCompounds() {
        assertEquals("pflegeheim", RagQuery.stem("Pflegeheime"));
        assertEquals("einfamilienhaus", RagQuery.stem("Einfamilienhäuser"));
        assertEquals("koln", RagQuery.stem("Köln"));
        assertEquals("einfamilienhäuser einfamilienhäus einfamilienhaus", RagQuery.prepare("Einfamilienhäuser").searchString(true));
        assertEquals("aachen aache", RagQuery.prepare("Aachen").searchString(true)); // a needless form costs nothing in an OR search
        final RagQuery query = RagQuery.prepare("Pflegeheime Tagespflege Köln");
        final RagCandidate candidate = new RagCandidate("https://x.example/", "Haus", "",
                "Das Pflegeheim mit Wohnbereich und Tagespflege in Köln-Lindenthal (Kölner Süden).", "x.example", List.of("c"), 0);
        assertEquals(3, candidate.matched(query).size());
        // short words must start a word: "köln" does not match "Wolkölnberg"-like inner text
        final RagCandidate inner = new RagCandidate("https://y.example/", "", "", "Ekölnhaus", "y.example", List.of("c"), 0);
        assertEquals(0, inner.matched(RagQuery.prepare("Köln")).size());
    }

    @Test
    public void modifiersPhrasesAcronymsAndWeakWords() {
        RagQuery query = RagQuery.prepare("collection:stackfinder-web site:shop.example \"Shopware 6\" Agentur KI IT");
        assertEquals("stackfinder-web", query.questionCollection);
        assertEquals(List.of("site:shop.example"), query.modifiers);
        assertEquals(List.of("\"shopware 6\"", "agentur", "ki", "it"), terms(query));
        assertTrue(query.searchString(true).endsWith("site:shop.example"));
        query = RagQuery.prepare("Welche Anbieter gibt es für Weltraumtourismus?");
        assertEquals(List.of("anbieter", "weltraumtourismus"), terms(query));
        assertTrue(query.terms.get(0).weak);
        assertEquals(1, query.strongTerms());
        assertNull(RagQuery.prepare("Pflegeheim").questionCollection);
        // other modifiers are not passed to the search
        assertEquals("pflegeheim", RagQuery.prepare("inurl:admin Pflegeheim").searchString(false));
    }
}
