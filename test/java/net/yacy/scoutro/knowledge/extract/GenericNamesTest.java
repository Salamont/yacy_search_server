/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * 0.14.0 (the owner's decision of 27 October 2026, items C1 and C2): a word of the vocabularies alone names no organisation,
 * client, partner, supplier or place; the names of the product vocabulary (a product or its vendor) are names, and the comparison
 * keeps the umlauts. A list is not split inside one name, and a list of kinds names no organisation.
 */
public class GenericNamesTest {

    private static Extraction rules(final String vocabulary, final String url, final String text) {
        final Extraction ex = new Extraction(300);
        final ExtractContext ctx = new ExtractContext(KgVocabularies.get(), Set.of(vocabulary), false, List.of("test-web"));
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex, ctx, List.of(), List.of());
        return ex;
    }

    private static List<String> objects(final Extraction ex, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.predicate.equals(predicate) && c.object != null) {
                out.add(ex.mention(c.object).name);
            }
        }
        return out;
    }

    private static List<String> subjects(final Extraction ex, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.predicate.equals(predicate) && c.object != null) {
                out.add(ex.mention(c.subject).name);
            }
        }
        return out;
    }

    @Test
    public void aWordOfTheVocabulariesIsNoNameButABrandIs() {
        final KgVocabularies.Snapshot v = KgVocabularies.get();
        for (final String word : List.of("Netzwerk", "Senioren", "Managed Services", "Landschaftsbau", "Pflegedienst", "Schüler", "Schueler")) {
            assertTrue(word, v.vocabularyWord(word));
        }
        // the brands of the product vocabulary (0.14.0) and their vendors are names, though categories name them too
        for (final String brand : List.of("AWS", "Shopware", "Google Cloud", "Shopify", "KNX", "WordPress", "TYPO3", "Kubernetes")) {
            assertFalse(brand, v.vocabularyWord(brand));
        }
        // with the umlauts: a firm named "Schuler" is no "Schüler"
        assertFalse(v.vocabularyWord("Schuler"));
        assertFalse(v.vocabularyWord("Muster GmbH"));
        assertEquals("schueler", KgVocabularies.wordKey("Schüler"));
        assertFalse(v.signals.vendors("aws").isEmpty());
        assertTrue(v.signals.products("Wir betreiben Shops mit Shopware und Hosting auf AWS.").containsAll(Set.of("shopware", "aws")));
    }

    @Test
    public void aWordOfTheVocabulariesAfterAMarkerNamesNoOrganisation() {
        final String url = "https://www.muster-bau.de/ueber-uns/";
        assertEquals(List.of(), objects(rules("construction", url, "Wir sind Mitglied im Netzwerk. Mehr erfahren."), Vocabulary.ASSOCIATION_MEMBER));
        assertEquals(List.of(), subjects(rules("construction", url, "Referenzen: Neubau.\n\nKontakt"), Vocabulary.REFERENCE_FOR));
        // a list of trades: kinds, not firms, also the one word no vocabulary knows
        assertEquals(List.of(), objects(rules("construction", url, "Wir bauen in Zusammenarbeit mit Fliesenleger, Maler und Möbelschreiner."),
                Vocabulary.COOPERATION_WITH));
        // brands stay names
        assertEquals(List.of("Muster GmbH", "Shopware", "AWS"), objects(rules("software", "https://www.muster-shop.de/partner/",
                "Unsere Partner: Muster GmbH, Shopware und AWS."), Vocabulary.PARTNER_OF));
        // a word of the vocabularies is no place
        assertEquals(List.of(), objects(rules("construction", url, "Wir montieren Fenster und Türen und Umgebung."), Vocabulary.SERVES_PLACE));
    }

    @Test
    public void aListIsNotSplitInsideOneName() {
        final String url = "https://www.muster-bau.de/ueber-uns/";
        assertEquals(List.of("Fachverband Garten- und Landschaftsbau"), objects(rules("construction", url,
                "Wir sind Mitglied im Fachverband Garten- und Landschaftsbau. Mit mehr als 10 Mitarbeitern."), Vocabulary.ASSOCIATION_MEMBER));
        assertEquals(List.of("Landesministerium für Muster, Beispiel, Probe und Test"), objects(rules("care", "https://www.muster-pflege.de/projekte/",
                "Das Projekt wird gefördert vom Landesministerium für Muster, Beispiel, Probe und Test. Viele Projekte."), Vocabulary.FUNDED_BY));
        assertEquals(List.of("Innung Sanitär, Heizung und Klima"), objects(rules("construction", url,
                "Wir sind Mitglied der Innung Sanitär, Heizung und Klima. Mehr erfahren."), Vocabulary.ASSOCIATION_MEMBER));
        assertEquals(List.of("Netzwerk „Muster sicher“"), objects(rules("care", "https://www.muster-pflege.de/ueber-uns/",
                "Wir sind Mitglied im Netzwerk „Muster sicher“. Mehr erfahren."), Vocabulary.ASSOCIATION_MEMBER));
        // a name with a legal form ends the list of a name's phrase
        final List<String> several = objects(rules("software", "https://www.muster-it.de/ueber-uns/",
                "Wir arbeiten in Kooperation mit Verband für Muster, Beispiel GmbH und Probe AG."), Vocabulary.COOPERATION_WITH);
        assertEquals(several.toString(), List.of("Verband für Muster", "Beispiel GmbH", "Probe AG"), several);
    }

    private static Set<String> categories(final String text) {
        final ExtractContext ctx = new ExtractContext(KgVocabularies.get(), Set.of("software", "construction"), false, List.of("test-web"));
        final Set<String> out = new java.util.TreeSet<>();
        for (final net.yacy.scoutro.knowledge.vocab.TermMatcher.Hit<net.yacy.scoutro.knowledge.vocab.Categories.Entry> h
                : BusinessFacts.categories(ctx, text, 0, text.length())) {
            out.add(h.entry.id);
        }
        return out;
    }

    /** 0.14.0 (item B7): the senses of a word in the vocabulary: a word of another sense ({@code exclude}) is no hit of the entry. */
    @Test
    public void aWordOfAnotherSenseIsNoHit() {
        for (final String t : List.of("Wir planen IT-Architekturen in der Cloud.", "Moderne Softwarearchitektur für Banken.",
                "Software architecture for retailers.")) {
            assertFalse(t, categories(t).contains("construction/architektur"));
        }
        for (final String t : List.of("Wir sanieren Infrastruktureinrichtungen wie Brücken.", "Ausbau der Verkehrsinfrastruktur.",
                "Unser Partnernetzwerk in der Region.", "Wir bauen Ladeinfrastruktur für Betriebe.")) {
            assertFalse(t, categories(t).contains("software/infrastruktur"));
        }
        assertTrue(categories("Architektur und Bauleitung aus einer Hand.").contains("construction/architektur"));
        assertTrue(categories("Wir betreuen Ihre IT-Infrastruktur und Ihr Netzwerk.").contains("software/infrastruktur"));
    }
}
