/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

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
 * 0.13.0: the declared audience by the shared reader (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 39): in the clause of the address, a
 * phrase that names organisations or people of a role; every vocabulary, the page's own too. Neutral texts with the structure of
 * the cases of the finding (a footer after "wir beraten Sie gern", own services after a marker, a network as a metaphor).
 */
public class AudiencesTest {

    private static ExtractContext vocabulary(final String name, final String collection) {
        return new ExtractContext(KgVocabularies.get(), Set.of(name), false, List.of(collection));
    }

    private static Extraction rules(final String text, final String url, final ExtractContext ctx) {
        final Extraction ex = new Extraction(300);
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex, ctx, List.of(), List.of());
        return ex;
    }

    private static List<String> site(final Extraction ex, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.subject.equals(BusinessRules.SITE_REF) && c.predicate.equals(predicate) && c.value != null && !out.contains(c.value)) {
                out.add(c.value);
            }
        }
        return out;
    }

    private static List<String> excerpts(final Extraction ex, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.predicate.equals(predicate) && c.value != null) {
                out.add(c.excerpt);
            }
        }
        return out;
    }

    /** Test 1: a footer after "wir beraten Sie gern" names no audience, also not the site's technology. */
    @Test
    public void aContactFooterNamesNoAudience() {
        final String text = "Termin vereinbaren. Rufen Sie uns an, wir beraten Sie gern: Telefon 0123 456789. Kontakt. Impressum. Datenschutz."
                + " Designed by. Muster Themes | Powered by. WordPress";
        final Extraction ex = rules(text, "https://www.muster-bau.de/kontakt/", vocabulary("construction", "bau-web"));
        assertTrue(site(ex, Vocabulary.TARGET_CATEGORY).isEmpty());
        assertTrue(site(ex, Vocabulary.TARGET_INDUSTRY).isEmpty());
    }

    /** Test 2: an audience of owners stays (its wording follows the structure of gate A1's text of the finding). */
    @Test
    public void anAudienceOfOwnersStays() {
        final String text = "Muster Immobilien. Wir beraten Eigentümer und Hausverwaltungen bei der Sanierung und Vermietung ihrer Immobilien.";
        final Extraction ex = rules(text, "https://www.muster-bau.de/muster-immobilien/", vocabulary("construction", "bau-web"));
        assertEquals(List.of("construction/eigentuemer", "construction/hausverwaltungen"), site(ex, Vocabulary.AUDIENCE_SEGMENT));
        assertTrue("the services after the marker are no target", site(ex, Vocabulary.TARGET_CATEGORY).isEmpty());
    }

    /** Test 3: a network as a metaphor of a care service is no target. */
    @Test
    public void aNetworkOfACareServiceIsNoTarget() {
        final String text = "Wir helfen Ihnen, ein starkes Netzwerk aus Angehörigen, Ärzten und Nachbarn aufzubauen.";
        final Extraction ex = rules(text, "https://www.muster-pflege.de/leistungen/", vocabulary("care", "pflege-web"));
        assertTrue(site(ex, Vocabulary.TARGET_CATEGORY).isEmpty());
        assertTrue(site(ex, Vocabulary.TARGET_INDUSTRY).isEmpty());
    }

    /** Test 4: own services after a marker give no target, in the page's vocabulary or another one. */
    @Test
    public void ownServicesAloneGiveNoTarget() {
        for (final String[] c : new String[][] {
            {"construction", "Wir beraten Sie zur Schimmelpilzsanierung und zur Bautrocknung Ihres Kellers."},
            {"coaching", "Wir begleiten Sie mit Unterstützung bei Veränderungsprozessen in Ihrem Team."},
            {"construction", "Wir beraten Sie rund um Baufinanzierung und Modernisierung."},
            {"construction", "Das Gebäude ist designed for diverse functions and open spaces."},
            {"software", "Wir unterstützen Sie bei Cloud-Migrationen und beim Aufbau Ihrer Cloud-Dienste."}}) {
            final Extraction ex = rules(c[1], "https://www.muster.de/", vocabulary(c[0], c[0] + "-web"));
            assertTrue(c[1], site(ex, Vocabulary.TARGET_CATEGORY).isEmpty());
            assertTrue(c[1], site(ex, Vocabulary.TARGET_INDUSTRY).isEmpty());
        }
    }

    /** Test 5: a software provider may address software companies: its own vocabulary is read too. */
    @Test
    public void softwareCompaniesAddressedByASoftwareProvider() {
        final Extraction ex = rules("Wir beraten Softwareunternehmen bei der Einführung von Qualitätsprozessen.", "https://www.muster-software.de/",
                vocabulary("software", "software-web"));
        assertEquals(List.of("62"), site(ex, Vocabulary.TARGET_INDUSTRY));
        assertTrue("organisations of a field give an industry, no category", site(ex, Vocabulary.TARGET_CATEGORY).isEmpty());
    }

    /** Test 6: an offer for agencies of a field: the field's category is the target. */
    @Test
    public void solutionsForAgenciesOfAField() {
        final Extraction ex = rules("Lösungen für WordPress-Agenturen.", "https://www.beispiel-software.de/", vocabulary("software", "software-web"));
        assertEquals(List.of("software/cms"), site(ex, Vocabulary.TARGET_CATEGORY));
        assertEquals(List.of("62.10"), site(ex, Vocabulary.TARGET_INDUSTRY));
    }

    /** Test 8: an audience after the clause's end is none. */
    @Test
    public void anAudienceAfterTheClausesEndIsNone() {
        final Extraction ex = rules("Wir beraten Sie gern. Pflegedienste finden hier alle Formulare.", "https://www.muster-software.de/",
                vocabulary("software", "software-web"));
        assertTrue(site(ex, Vocabulary.TARGET_CATEGORY).isEmpty());
    }

    /** The speaker's own role is no audience; the reader's role is one; a denied one is none; "nicht nur … sondern auch" names both. */
    @Test
    public void rolesDenialsAndLists() {
        final ExtractContext bau = vocabulary("construction", "bau-web");
        assertTrue(Audiences.audiences(Audiences.read("Wir helfen Ihnen als Ihr Generalunternehmer bei jedem Projekt.", bau)).isEmpty());
        assertTrue(Audiences.audiences(Audiences.read("Wir arbeiten für keine Bauträger.", bau)).isEmpty());
        final ExtractContext pflege = vocabulary("software", "software-web");
        assertEquals("[target_category=care/ambulante_pflege, target_industry=88.10]",
                Audiences.audiences(Audiences.read("Wir beraten Sie als ambulanter Pflegedienst zu allen Fragen.", pflege)).toString());
        assertEquals("[target_category=care/therapie_aktivierung, target_industry=86.9]", Audiences.audiences(
                Audiences.read("Wir helfen nicht nur Arztpraxen, sondern auch Physiotherapie-Praxen.", pflege)).toString());
        // a heading and the list below it, also as a stored excerpt writes it on one line
        for (final String t : List.of("Zielgruppe:\n- Architekturbüros\n- Bauträger\nKontakt", "Zielgruppe: - Architekturbüros - Bauträger Kontakt")) {
            assertEquals(t, "[target_category=construction/architektur, target_industry=71.11, target_category=construction/bautraeger,"
                    + " target_industry=68.12]", Audiences.audiences(Audiences.read(t, bau)).toString());
        }
    }

    /** A term of a product or a place whose word ends like an organisation's is none ("Netzwerk", "Rechenzentren", "Fertighäuser"). */
    @Test
    public void wordsThatEndLikeOrganisationsAreNone() {
        final ExtractContext all = Audiences.all();
        for (final String t : List.of("Wir unterstützen Ihr Netzwerk.", "Lösungen für Fertighäuser.", "Wir unterstützen Rechenzentren.",
                "Wir helfen Herstellern.", "Software für Hersteller und Händler.")) {
            final List<Audiences.Audience> found = new ArrayList<>();
            for (final Audiences.Audience a : Audiences.audiences(Audiences.read(t, all))) {
                if (a.predicate.startsWith("target_")) {
                    found.add(a);
                }
            }
            assertTrue(t + " " + found, found.isEmpty());
        }
    }

    /** Test 12 (reader part): the LLM tier's target category only from a phrase naming organisations; stored evidence read again. */
    @Test
    public void theLlmTiersTargetCategoryAndStoredEvidence() {
        final ExtractContext ctx = vocabulary("software", "software-web");
        assertTrue(LlmExtractor.read(Vocabulary.TARGET_CATEGORY, "Wir unterstützen Sie bei Cloud-Migrationen.", ctx).isEmpty());
        assertEquals(List.of("care/ambulante_pflege"), LlmExtractor.read(Vocabulary.TARGET_CATEGORY, "Dienstplanung für ambulante Pflegedienste", ctx));
        assertFalse(Audiences.confirms(Vocabulary.TARGET_CATEGORY, "software/cloud", "Wir unterstützen Sie bei Cloud-Migrationen.", 3));
        assertTrue(Audiences.confirms(Vocabulary.TARGET_CATEGORY, "care/ambulante_pflege", "Dienstplanung für ambulante Pflegedienste", 3));
        // the rule tier's stored excerpt of rule/8 (a window across the footer) is not confirmed; one of rule/9 is
        assertFalse(Audiences.confirms(Vocabulary.TARGET_CATEGORY, "software/cms", "Rufen Sie uns an, wir beraten Sie gern: Kontakt Impressum"
                + " Designed by. Muster Themes | Powered by. WordPress", 2));
        final Extraction ex = rules("Lösungen für WordPress-Agenturen.", "https://www.beispiel-software.de/", ctx);
        for (final String excerpt : excerpts(ex, Vocabulary.TARGET_CATEGORY)) {
            assertTrue(excerpt, Audiences.confirms(Vocabulary.TARGET_CATEGORY, "software/cms", excerpt, 2));
        }
        assertTrue("structured data are not read again", Audiences.confirms(Vocabulary.CUSTOMER_TYPE, "b2b", "audienceType: Business", 1));
    }
}
