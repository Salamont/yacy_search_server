// KgProductionCasesTest.java
// Copyright 2026 Scoutro
//
// This file is part of YaCy / Scoutro.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.

package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * The seven knowledge graph errors of the production report of 19 October 2026 (Scoutro 0.11.4, schema 9), as regression tests
 * of the extraction rules of 0.11.5 with the real host names. The stored facts of the pages read before 0.11.5 stay as they are
 * (no re-extraction, no mass correction); the read side labels and hides them ({@code EntityRolesTest}).
 * <ol>
 * <li>www.pro-casa-bau.de: the navigation label "Bildergalerie" after "Referenzen" became an organisation.</li>
 * <li>www.dielen-kaufen.de: the menu entry "Showroom" after "Partner" became an organisation.</li>
 * <li>www.rostocker-massivhaus.de: the kind of partner "Banken" became an organisation named "Banken".</li>
 * <li>architektin-im-wasserturm.de: the role word "Hersteller" became an offered service.</li>
 * <li>drolshagens.de: the product brand "Golden-Ingwer" became the shop's operator and took its social profile.</li>
 * <li>das-pianohaus.de: the maker "SCHIMMEL" listed under the dealer's {@code brand} became a brand of the dealer.</li>
 * <li>slowik-it.de: the partner "Apple" of a partner list is correct and stays; it is labelled as a brand when read.</li>
 * </ol>
 */
public class KgProductionCasesTest {

    private static ExtractContext construction() {
        return new ExtractContext(KgVocabularies.get(), Set.of("construction"), false, List.of("bauteamcheck-web"));
    }

    private static ExtractContext software() {
        return new ExtractContext(KgVocabularies.get(), Set.of("software"), false, List.of("stackfinder-web"));
    }

    private static Extraction jsonld(final String block, final String url, final ExtractContext ctx) {
        final Extraction ex = new Extraction(200);
        new JsonLdExtractor(200).extract(List.of(block), url, java.net.URI.create(url).getHost(), "de", ex, ctx);
        BusinessFacts.industriesFromServices(ex, ctx, 2, 200);
        return ex;
    }

    private static Extraction rules(final String text, final String url, final ExtractContext ctx) {
        final Extraction ex = new Extraction(300);
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex, ctx, List.of(), List.of());
        return ex;
    }

    private static List<String> values(final Extraction ex, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.predicate.equals(predicate) && c.value != null) {
                out.add(c.value);
            }
        }
        return out;
    }

    private static List<String> relations(final Extraction ex, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.predicate.equals(predicate) && c.object != null) {
                out.add(ex.mention(c.subject).name + " -> " + ex.mention(c.object).name);
            }
        }
        return out;
    }

    private static List<String> organisations(final Extraction ex) {
        final List<String> out = new ArrayList<>();
        for (final Mention m : ex.mentions()) {
            if (Vocabulary.ORGANIZATION.equals(m.type) && m.name != null) {
                out.add(m.name);
            }
        }
        return out;
    }

    private static Mention named(final Extraction ex, final String name) {
        for (final Mention m : ex.mentions()) {
            if (name.equals(m.name)) {
                return m;
            }
        }
        return null;
    }

    @Test
    public void proCasaBauANavigationLabelAfterReferenzenIsNoOrganisation() {
        final String nav = "Startseite\nLeistungen\nReferenzen\nBildergalerie\nKontakt\nImpressum\nPro Casa Bau GmbH, Musterweg 1, 18055 Rostock";
        final Extraction ex = rules(nav, "https://www.pro-casa-bau.de/", construction());
        assertEquals(List.of(), relations(ex, Vocabulary.REFERENCE_FOR));
        assertEquals(List.of(), relations(ex, Vocabulary.PARTNER_OF));
        assertFalse(organisations(ex).toString(), organisations(ex).contains("Bildergalerie"));
        // the footer with a legal name on the same lines stays an organisation list where it is anchored by one
        final Extraction anchored = rules("Referenzen:\nMuster Wohnbau GmbH\nStadt Rostock\nBildergalerie", "https://www.pro-casa-bau.de/referenzen",
                construction());
        assertEquals(List.of("Muster Wohnbau GmbH -> null", "Stadt Rostock -> null"), relations(anchored, Vocabulary.REFERENCE_FOR));
    }

    @Test
    public void dielenKaufenAMenuEntryAfterPartnerIsNoOrganisation() {
        for (final String text : new String[] {"Unsere Partner | Showroom | Kontakt | Anfahrt", "Unsere Partner\nShowroom\nKontakt\nAnfahrt",
                "Partner: Showroom, Kontakt"}) {
            final Extraction ex = rules(text, "https://www.dielen-kaufen.de/", construction());
            assertEquals(text, List.of(), relations(ex, Vocabulary.PARTNER_OF));
            assertFalse(text, organisations(ex).contains("Showroom"));
        }
    }

    @Test
    public void rostockerMassivhausAKindOfPartnerIsNoOrganisation() {
        final Extraction ex = rules("Finanzierung: Unsere Partner: Banken, Versicherungen, KfW und Bausparkassen. Wir vermitteln"
                + " Ihnen die passende Finanzierung.", "https://www.rostocker-massivhaus.de/finanzierung", construction());
        assertEquals(List.of("null -> KfW"), relations(ex, Vocabulary.PARTNER_OF));
        final List<String> orgs = organisations(ex);
        for (final String generic : new String[] {"Banken", "Versicherungen", "Bausparkassen"}) {
            assertFalse(orgs.toString(), orgs.contains(generic));
        }
        // the guard the read side uses on what older pages stored
        assertTrue(BusinessRules.genericName("Banken"));
        assertTrue(BusinessRules.genericName("Bildergalerie"));
        assertTrue(BusinessRules.genericName("Showroom"));
        assertTrue(BusinessRules.genericName("Versicherungen"));
        assertFalse(BusinessRules.genericName("Siemens"));
        assertFalse(BusinessRules.genericName("SCHIMMEL"));
        assertFalse(BusinessRules.genericName("Apple"));
        assertFalse(BusinessRules.genericName("Muster Wohnbau GmbH"));
        assertFalse(BusinessRules.genericName("KfW"));
    }

    @Test
    public void realOrganisationsAndOneWordBrandsStay() {
        // the partner lists of 0.10.x stay as they are: a legal name, an institution and a one-word brand in a sentence
        final Extraction list = rules("Unsere Partner Siemens, DHL und SAP. Partner: Muster Software GmbH und IHK Rostock.",
                "https://www.rostocker-massivhaus.de/partner", construction());
        assertEquals(List.of("null -> Siemens", "null -> DHL", "null -> SAP", "null -> Muster Software GmbH", "null -> IHK Rostock"),
                relations(list, Vocabulary.PARTNER_OF));
        // on lines without a legal name only a marked brand stays (an abbreviation, a digit, an inner capital)
        final Extraction lines = rules("Unsere Partner:\nSiemens\nDHL\nPayPal\n3M\nShowroom", "https://www.rostocker-massivhaus.de/partner",
                construction());
        assertEquals(List.of("null -> DHL", "null -> PayPal", "null -> 3M"), relations(lines, Vocabulary.PARTNER_OF));
    }

    @Test
    public void architektinImWasserturmTheRoleWordHerstellerIsATopicNoService() {
        final String url = "https://architektin-im-wasserturm.de/leistungen";
        final Extraction ex = rules("Leistungen: Entwurf, Bauleitung und Ausschreibung. Herstellerangaben und Hersteller prüfen wir für Sie."
                + " Wir arbeiten mit allen Herstellern zusammen.", url, construction());
        final List<String> offers = relations(ex, Vocabulary.OFFERS);
        for (final String o : offers) {
            assertFalse(o, o.endsWith("-> Hersteller"));
        }
        assertNotNull("the page's topic keeps the category", values(ex, Vocabulary.TOPIC).stream().filter(t -> t.endsWith("/hersteller")).findFirst()
                .orElse(null));
        assertFalse("no service of a role word", organisations(ex).contains("Hersteller"));
        for (final Mention m : ex.mentions()) {
            assertFalse(m.toString(), Vocabulary.SERVICE.equals(m.type) && "Hersteller".equals(m.name));
        }
    }

    @Test
    public void drolshagensAProductBrandIsNeitherTheOperatorNorTheOwnerOfTheProfiles() {
        final String url = "https://drolshagens.de/ingwerbonbons";
        final Extraction ex = jsonld("[{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"Drolshagens Nuss-Spezialitäten\","
                + "\"url\":\"https://drolshagens.de/\",\"sameAs\":[\"https://www.facebook.com/drolshagens\"]},"
                + "{\"@context\":\"https://schema.org\",\"@type\":\"Product\",\"name\":\"Ingwerbonbons\","
                + "\"brand\":{\"@type\":\"Brand\",\"name\":\"Golden-Ingwer\",\"url\":\"https://drolshagens.de/golden-ingwer\","
                + "\"sameAs\":[\"https://www.instagram.com/goldeningwer\"]},"
                + "\"offers\":{\"@type\":\"Offer\",\"price\":\"4.90\",\"priceCurrency\":\"EUR\"}}]", url, construction());
        final Mention brand = named(ex, "Golden-Ingwer");
        assertNotNull(brand);
        assertEquals(Vocabulary.ORGANIZATION, brand.type);
        assertFalse("a brand is never the site operator", brand.siteOperator);
        assertFalse("a brand is never the domain operator", brand.domainOperator);
        assertEquals("a brand without @id has its name as its identity on the host", JsonLdExtractor.BRAND_ID + "golden ingwer", brand.ldId);
        assertEquals(List.of("Ingwerbonbons -> Golden-Ingwer"), relations(ex, Vocabulary.HAS_BRAND));
        assertEquals("the brand's profile is nobody's", List.of("https://facebook.com/drolshagens"), values(ex, Vocabulary.SOCIAL_PROFILE));
        final Mention operator = named(ex, "Drolshagens Nuss-Spezialitäten");
        assertNotNull(operator);
        assertTrue(operator.domainOperator);
        assertNotNull("the brand keeps its website", values(ex, Vocabulary.WEBSITE).stream().filter(w -> w.contains("golden-ingwer")).findFirst().orElse(null));
        // a brand that carries the domain's own name is the operator, not a second entity
        final Extraction own = jsonld("{\"@context\":\"https://schema.org\",\"@type\":\"Product\",\"name\":\"Gebrannte Mandeln\","
                + "\"brand\":{\"@type\":\"Brand\",\"name\":\"Drolshagens\",\"url\":\"https://drolshagens.de/\"}}", url, construction());
        final Mention self = named(own, "Drolshagens");
        assertNotNull(self);
        assertTrue(self.domainOperator);
        assertEquals(null, self.ldId);
    }

    @Test
    public void dasPianohausAMakersBrandUnderTheDealersBrandPropertySuppliesTheDealer() {
        final String url = "https://www.das-pianohaus.de/";
        final Extraction ex = jsonld("{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"Das Pianohaus GmbH\","
                + "\"url\":\"https://www.das-pianohaus.de/\",\"brand\":[\"SCHIMMEL\",{\"@type\":\"Brand\",\"name\":\"Yamaha\"},"
                + "{\"@type\":\"Brand\",\"name\":\"Pianohaus Edition\"}]}", url, software());
        assertEquals(List.of("Pianohaus Edition -> Das Pianohaus GmbH"), relations(ex, Vocabulary.BRAND_OF));
        assertEquals(List.of("SCHIMMEL -> Das Pianohaus GmbH", "Yamaha -> Das Pianohaus GmbH"), relations(ex, Vocabulary.SUPPLIER_OF));
        final Mention schimmel = named(ex, "SCHIMMEL");
        assertNotNull(schimmel);
        assertFalse(schimmel.siteOperator || schimmel.domainOperator);
        assertEquals(JsonLdExtractor.BRAND_ID + "schimmel", schimmel.ldId);
        final Mention dealer = named(ex, "Das Pianohaus GmbH");
        assertTrue(dealer.siteOperator);
        // the product side is unchanged: the brand of a product it sells
        final Extraction product = jsonld("{\"@context\":\"https://schema.org\",\"@type\":\"Product\",\"name\":\"Schimmel K 122\","
                + "\"brand\":{\"@type\":\"Brand\",\"name\":\"SCHIMMEL\"},\"manufacturer\":{\"@type\":\"Organization\",\"name\":\"Wilhelm Schimmel Pianofortefabrik GmbH\"},"
                + "\"offers\":{\"@type\":\"Offer\",\"price\":\"12900\",\"priceCurrency\":\"EUR\"}}", url + "schimmel-k-122", software());
        assertEquals(List.of("Schimmel K 122 -> SCHIMMEL"), relations(product, Vocabulary.HAS_BRAND));
        assertEquals(List.of("Wilhelm Schimmel Pianofortefabrik GmbH -> Schimmel K 122"), relations(product, Vocabulary.MANUFACTURER_OF));
        assertEquals(JsonLdExtractor.BRAND_ID + "schimmel", named(product, "SCHIMMEL").ldId);
        assertEquals("a maker with a legal name keeps its legal identity, no brand identity",
                JsonLdExtractor.BRAND_ID + "wilhelm schimmel pianofortefabrik gmbh", named(product, "Wilhelm Schimmel Pianofortefabrik GmbH").ldId);
    }

    @Test
    public void slowikItAPartnerListOfBrandsStays() {
        final Extraction ex = rules("Unsere Partner: Apple, Microsoft und Lenovo. Als zertifizierter Apple Händler beraten wir Sie gern.",
                "https://slowik-it.de/partner", software());
        assertEquals(List.of("null -> Apple", "null -> Microsoft", "null -> Lenovo"), relations(ex, Vocabulary.PARTNER_OF));
        assertFalse(BusinessRules.genericName("Apple"));
    }
}
