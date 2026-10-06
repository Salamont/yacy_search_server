/*
 *  BusinessExtractionTest
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/** Vocabulary 2: services, prices, relations, industry, contacts, jobs and audiences from JSON-LD and text (package 6). */
public class BusinessExtractionTest {

    private static ExtractContext care(final boolean jobs) {
        return new ExtractContext(KgVocabularies.get(), Set.of("care"), jobs, List.of("edelsenior-web"));
    }

    private static ExtractContext vocabulary(final String name, final String collection) {
        return new ExtractContext(KgVocabularies.get(), Set.of(name), false, List.of(collection));
    }

    private static Extraction jsonld(final String block, final String url, final ExtractContext ctx) {
        final Extraction ex = new Extraction(200);
        new JsonLdExtractor(200).extract(List.of(block), url, java.net.URI.create(url).getHost(), "de", ex, ctx);
        BusinessFacts.industriesFromServices(ex, ctx, 2, 200);
        return ex;
    }

    private static Extraction rules(final String text, final String url, final ExtractContext ctx, final List<String> outbound) {
        final Extraction ex = new Extraction(300);
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex, ctx, List.of(), outbound);
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

    private static Mention named(final Extraction ex, final String type, final String name) {
        for (final Mention m : ex.mentions()) {
            if (m.type.equals(type) && name.equals(m.name)) {
                return m;
            }
        }
        return null;
    }

    private static List<String> valuesOf(final Extraction ex, final Mention m, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.subject.equals(m.ref) && c.predicate.equals(predicate) && c.value != null) {
                out.add(c.value);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ JSON-LD

    @Test
    public void jsonLdOffersGivePricesCategoriesAndTheIndustryOfTheType() {
        final Extraction ex = jsonld("{\"@type\":\"NursingHome\",\"name\":\"Haus Lindenhof\",\"makesOffer\":[{\"@type\":\"Offer\","
                + "\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Kurzzeitpflege\"},\"priceSpecification\":{\"@type\":\"UnitPriceSpecification\","
                + "\"price\":89.9,\"priceCurrency\":\"EUR\",\"unitCode\":\"DAY\",\"valueAddedTaxIncluded\":true}},{\"@type\":\"Offer\","
                + "\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Tagespflege\"},\"price\":\"49\"}],\"priceRange\":\"€€\"}",
                "https://www.lindenhof.de/", care(false));
        final Mention kurz = named(ex, Vocabulary.SERVICE, "Kurzzeitpflege");
        assertNotNull(kurz);
        assertEquals(List.of("care/kurzzeitpflege"), valuesOf(ex, kurz, Vocabulary.CATEGORY));
        assertEquals(List.of("{\"amount\":\"89.90\",\"currency\":\"EUR\",\"kind\":\"fixed\",\"unit\":\"day\",\"vat\":\"incl\"}"),
                valuesOf(ex, kurz, Vocabulary.PRICE));
        // a price without a currency is no price; "€€" is no number
        assertTrue(valuesOf(ex, named(ex, Vocabulary.SERVICE, "Tagespflege"), Vocabulary.PRICE).isEmpty());
        final Mention home = named(ex, Vocabulary.FACILITY, "Haus Lindenhof");
        assertTrue(valuesOf(ex, home, Vocabulary.PRICE).isEmpty());
        // the type says residential nursing care, and so does the service it offers (tier 2, with the basis)
        assertTrue(valuesOf(ex, home, Vocabulary.INDUSTRY).contains("87.10"));
        for (final Claim c : ex.claims()) {
            if (Vocabulary.INDUSTRY.equals(c.predicate) && c.tier == 2) {
                assertTrue("the basis is named: " + c.excerpt, c.excerpt.startsWith("offers ") && c.excerpt.contains("NACE " + c.value));
                assertEquals(0.6, c.effectiveConfidence(), 1e-9);
            }
        }
        assertEquals("two services of one group: one group", List.of("care.stationaer"), valuesOf(ex, home, Vocabulary.INDUSTRY_CATEGORY));
    }

    @Test
    public void jsonLdRelationsKeepTheirDirection() {
        final Extraction ex = jsonld("{\"@type\":\"Organization\",\"name\":\"Muster Software GmbH\",\"url\":\"https://www.muster.de/\","
                + "\"parentOrganization\":{\"@type\":\"Organization\",\"name\":\"Muster Holding AG\"},"
                + "\"subOrganization\":{\"@type\":\"Organization\",\"name\":\"Muster Cloud GmbH\"},"
                + "\"memberOf\":[{\"@type\":\"Organization\",\"name\":\"Bitkom e.V.\"},\"Cluster IT Musterland e.V.\"],"
                + "\"funder\":{\"@type\":\"Organization\",\"name\":\"KfW\"},\"sponsor\":{\"@type\":\"Person\",\"name\":\"Max Mustermann\"},"
                + "\"brand\":{\"@type\":\"Brand\",\"name\":\"MusterCRM\"},\"sameAs\":[\"https://www.linkedin.com/company/muster-software\","
                + "\"https://www.linkedin.com/in/max-mustermann\"],\"faxNumber\":\"030 1234568\","
                + "\"contactPoint\":{\"@type\":\"ContactPoint\",\"contactType\":\"Vertrieb\",\"telephone\":\"030 99999\","
                + "\"email\":\"vertrieb@muster.de\",\"name\":\"Erika Musterfrau\"}}", "https://www.muster.de/", vocabulary("software",
                        "stackfinder-web"));
        assertEquals(List.of("Muster Software GmbH -> Muster Holding AG"), relations(ex, Vocabulary.SUBSIDIARY_OF));
        assertEquals(List.of("Muster Software GmbH -> Muster Cloud GmbH"), relations(ex, Vocabulary.PARENT_OF));
        assertEquals(List.of("Muster Software GmbH -> Bitkom e.V.", "Muster Software GmbH -> Cluster IT Musterland e.V."),
                relations(ex, Vocabulary.MEMBER_OF));
        assertEquals(List.of("Muster Software GmbH -> KfW"), relations(ex, Vocabulary.FUNDED_BY));
        assertTrue("persons are no organisations", relations(ex, Vocabulary.SPONSORED_BY).isEmpty());
        assertEquals(List.of("MusterCRM -> Muster Software GmbH"), relations(ex, Vocabulary.BRAND_OF));
        assertEquals(List.of("https://linkedin.com/company/muster-software"), values(ex, Vocabulary.SOCIAL_PROFILE));
        assertEquals(List.of("+49301234568"), values(ex, Vocabulary.FAX));
        assertEquals(List.of("{\"email\":\"vertrieb@muster.de\",\"function\":\"Vertrieb\",\"phone\":\"+4930" + "99999\"}"),
                values(ex, Vocabulary.CONTACT_POINT));
        for (final Claim c : ex.claims()) {
            assertFalse(c.toString(), String.valueOf(c.value).contains("Musterfrau") || String.valueOf(c.excerpt).contains("Musterfrau"));
        }
    }

    @Test
    public void jsonLdJobPostingOnlyWhenSwitchedOnAndNeverTheRecruiter() {
        final String block = "{\"@type\":\"JobPosting\",\"title\":\"Pflegefachkraft (m/w/d)\",\"datePosted\":\"2026-09-01\","
                + "\"validThrough\":\"2026-12-31T23:59:00+01:00\",\"employmentType\":[\"FULL_TIME\",\"PART_TIME\"],"
                + "\"hiringOrganization\":{\"@type\":\"Organization\",\"name\":\"Lindenhof Pflege gGmbH\"},"
                + "\"jobLocation\":{\"@type\":\"Place\",\"address\":{\"@type\":\"PostalAddress\",\"addressLocality\":\"Berlin\","
                + "\"postalCode\":\"10115\",\"streetAddress\":\"Lindenallee 3\"}},"
                + "\"baseSalary\":{\"@type\":\"MonetaryAmount\",\"currency\":\"EUR\",\"value\":{\"@type\":\"QuantitativeValue\","
                + "\"minValue\":3400,\"maxValue\":3900,\"unitText\":\"MONTH\"}},"
                + "\"applicationContact\":{\"@type\":\"ContactPoint\",\"name\":\"Erika Musterfrau\",\"email\":\"e.musterfrau@lindenhof.de\"}}";
        final Extraction off = jsonld(block, "https://www.lindenhof.de/karriere/pflegefachkraft", care(false));
        assertNull("jobs are off for the collection", named(off, Vocabulary.JOB, "Pflegefachkraft (m/w/d)"));
        final Extraction ex = jsonld(block, "https://www.lindenhof.de/karriere/pflegefachkraft", care(true));
        final Mention job = named(ex, Vocabulary.JOB, "Pflegefachkraft (m/w/d)");
        assertNotNull(ex.mentions().toString(), job);
        assertEquals("lindenhof pflege ggmbh|pflegefachkraft m w d|berlin", job.jobKey);
        assertEquals(List.of("full_time", "part_time"), valuesOf(ex, job, Vocabulary.EMPLOYMENT_TYPE));
        assertEquals(List.of("2026-12-31"), valuesOf(ex, job, Vocabulary.VALID_THROUGH));
        assertEquals(List.of("2026-09-01"), valuesOf(ex, job, Vocabulary.DATE_POSTED));
        assertEquals(List.of("{\"currency\":\"EUR\",\"kind\":\"range\",\"max\":\"3900.00\",\"min\":\"3400.00\",\"unit\":\"month\"}"),
                valuesOf(ex, job, Vocabulary.SALARY));
        assertEquals(List.of("Pflegefachkraft (m/w/d) -> Lindenhof Pflege gGmbH"), relations(ex, Vocabulary.HIRING_ORGANIZATION));
        assertEquals(1, relations(ex, Vocabulary.JOB_LOCATION).size());
        assertTrue("a personal address is no application route", valuesOf(ex, job, Vocabulary.APPLICATION_ROUTE).isEmpty());
    }

    @Test
    public void jsonLdAudienceAndAreaServed() {
        final Extraction ex = jsonld("{\"@type\":\"Organization\",\"name\":\"Muster Software GmbH\",\"audience\":{\"@type\":"
                + "\"BusinessAudience\",\"audienceType\":\"KMU und Mittelstand\"},\"areaServed\":[\"Deutschland\",\"Bayern\","
                + "{\"@type\":\"GeoCircle\",\"geoMidpoint\":{\"name\":\"München\"},\"geoRadius\":50000}]}",
                "https://www.muster.de/", vocabulary("software", "stackfinder-web"));
        assertEquals(List.of("b2b"), values(ex, Vocabulary.CUSTOMER_TYPE));
        assertEquals(List.of("sme", "midsize"), values(ex, Vocabulary.COMPANY_SIZE));
        assertEquals(List.of("{\"kind\":\"national\",\"name\":\"Deutschland\"}", "{\"kind\":\"state\",\"name\":\"Bayern\"}",
                "{\"around\":\"München\",\"kind\":\"radius\",\"radius_km\":\"50\"}"), values(ex, Vocabulary.SERVICE_AREA));
        assertEquals(List.of("Muster Software GmbH -> Deutschland", "Muster Software GmbH -> Bayern"), relations(ex, Vocabulary.SERVES_PLACE));
        assertEquals("de|state|bayern", named(ex, Vocabulary.PLACE, "Bayern").placeKey);
    }

    // -------------------------------------------------------------- rules

    private static final String PRICES = "Preise und Leistungen. Unsere Tagespflege ab 49 € pro Tag zzgl. Fahrtkosten. "
            + "Kurzzeitpflege: 89,90 €/Tag (Eigenanteil bei Pflegegrad 2). Beratung: 30 – 50 € pro Stunde inkl. MwSt. "
            + "Parkplätze 2 € am Eingang. Stand: 01/2026";

    @Test
    public void pricesInTextBelongToTheServiceNamedBeforeThem() {
        final Extraction ex = rules(PRICES, "https://www.sonnenschein-pflege.de/preise", care(false), List.of());
        final Mention site = ex.mention(BusinessRules.SITE_REF);
        assertNotNull("the unnamed operator of the site offers the services", site);
        assertTrue(site.domainOperator);
        final Mention tages = named(ex, Vocabulary.SERVICE, "Tagespflege");
        final Mention kurz = named(ex, Vocabulary.SERVICE, "Kurzzeitpflege");
        assertEquals(List.of("{\"amount\":\"49.00\",\"as_of\":\"2026-01\",\"conditions\":\"zzgl. Fahrtkosten\",\"currency\":\"EUR\","
                + "\"kind\":\"from\",\"unit\":\"day\"}"), valuesOf(ex, tages, Vocabulary.PRICE));
        assertEquals(List.of("{\"amount\":\"89.90\",\"as_of\":\"2026-01\",\"care_level\":\"2\",\"conditions\":\"Eigenanteil bei Pflegegrad 2\","
                + "\"currency\":\"EUR\",\"kind\":\"fixed\",\"own_share\":true,\"unit\":\"day\"}"), valuesOf(ex, kurz, Vocabulary.PRICE));
        // "Beratung" is no care category: a price list line names it by its label
        final Mention beratung = named(ex, Vocabulary.SERVICE, "Beratung");
        assertNotNull(ex.mentions().toString(), beratung);
        assertEquals(List.of("{\"as_of\":\"2026-01\",\"conditions\":\"inkl. MwSt\",\"currency\":\"EUR\",\"kind\":\"range\",\"max\":\"50.00\","
                + "\"min\":\"30.00\",\"unit\":\"hour\",\"vat\":\"incl\"}"), valuesOf(ex, beratung, Vocabulary.PRICE));
        for (final Claim c : ex.claims()) {
            if (Vocabulary.PRICE.equals(c.predicate)) {
                assertTrue("every number is in its evidence: " + c.excerpt, c.excerpt.contains(c.value.contains("89.90") ? "89,90"
                        : c.value.contains("49.00") ? "49" : "30"));
            }
            assertFalse("the parking fee belongs to nothing named", String.valueOf(c.value).contains("\"2.00\""));
        }
        assertEquals(List.of(Vocabulary.OFFERS), ex.claims().stream().filter(c -> c.object != null && c.subject.equals(site.ref))
                .map(c -> c.predicate).distinct().collect(java.util.stream.Collectors.toList()));
    }

    /** A plain space groups thousands only where nothing else can be meant; a table row's cells never fuse into one amount. */
    @Test
    public void aSpaceBetweenNumbersIsAThousandsSeparatorOnlyWhenNothingElseIsMeant() {
        final java.util.function.Function<String, List<String>> amounts = t -> {
            final List<String> out = new ArrayList<>();
            for (final Values.Price p : Values.prices(t, 0, t.length())) {
                out.add(String.valueOf(p.fields.get("amount")));
            }
            return out;
        };
        // table cells "Pflegegrad 2" and "980 €" in one line
        assertEquals(List.of(), amounts.apply("Eigenanteil Pflegegrad 2 980 € Pflegegrad 3 1 120 €"));
        assertEquals(List.of(), amounts.apply("Zimmer 3 450 € im Monat"));
        // grouping where nothing else can be meant
        assertEquals(List.of("1250.00"), amounts.apply("Kosten: 1 250 € im Monat"));
        assertEquals(List.of("1250.00"), amounts.apply("Eigenanteil ab 1 250 €"));
        assertEquals(List.of("1250.00"), amounts.apply("Eigenanteil € 1 250"));
        assertEquals(List.of("1250.00"), amounts.apply("Eigenanteil 1\u00A0250 €"));
        assertEquals(List.of("1250.00"), amounts.apply("Eigenanteil 1\u202F250 €"));
        assertEquals(List.of("1250.00"), amounts.apply("Eigenanteil 1.250 €"));
        // a line break is no thousands separator
        assertEquals(List.of("250.00"), amounts.apply("Haus 1\n250 € pro Tag"));
    }

    /** schema.org writes "." as the decimal point; "12.500" may be meant either way and gives no amount. */
    @Test
    public void aJsonLdAmountReadsThePointAsTheDecimalPoint() {
        assertEquals("12.50", Values.ldAmount("12.50"));
        assertEquals("12.50", Values.ldAmount(12.5));
        assertEquals("12.50", Values.ldAmount(new java.math.BigDecimal("12.500")));
        assertEquals("3400.00", Values.ldAmount(3400));
        assertEquals("3400.00", Values.ldAmount("3400"));
        assertEquals("1250.00", Values.ldAmount("1,250.00"));
        assertEquals("1250.00", Values.ldAmount("1.250,00"));
        assertNull("12.5 by the rule, 12 500 by habit", Values.ldAmount("12.500"));
        assertNull(Values.ldAmount("3.400"));
        assertNull(Values.ldAmount(12.345));
        assertNull(Values.ldAmount(-5));
        final Extraction ex = jsonld("{\"@type\":\"Organization\",\"name\":\"Sonnenschein Pflege GmbH\",\"makesOffer\":[{\"@type\":\"Offer\","
                + "\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Tagespflege\"},\"price\":\"12.500\",\"priceCurrency\":\"EUR\"},"
                + "{\"@type\":\"Offer\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Kurzzeitpflege\"},\"price\":89.9,\"priceCurrency\":\"EUR\"}]}",
                "https://www.sonnenschein-pflege.de/", care(false));
        assertEquals(List.of("{\"amount\":\"89.90\",\"currency\":\"EUR\",\"kind\":\"fixed\"}"), values(ex, Vocabulary.PRICE));
    }

    /**
     * A price table as YaCy's HTML parser gives it to the index: the cells of all rows in one line, separated by spaces, no
     * colons ({@code <tr><td>Kurzzeitpflege</td><td>89,90 € pro Tag</td></tr>} …, checked with {@code TextParser}).
     */
    @Test
    public void aPriceTableKeepsEveryPriceWithTheServiceOfItsRow() {
        final String table = "Preise – Muster Pflege. # Preise. Leistung Preis Kurzzeitpflege 89,90 € pro Tag Tagespflege ab 49 € pro Tag "
                + "Verhinderungspflege 25 €/Stunde Stand: 08/2026.";
        final Extraction ex = rules(table, "https://www.muster-pflege.de/preise", care(false), List.of());
        assertEquals(List.of("{\"amount\":\"89.90\",\"as_of\":\"2026-08\",\"currency\":\"EUR\",\"kind\":\"fixed\",\"unit\":\"day\"}"),
                valuesOf(ex, named(ex, Vocabulary.SERVICE, "Kurzzeitpflege"), Vocabulary.PRICE));
        assertEquals(List.of("{\"amount\":\"49.00\",\"as_of\":\"2026-08\",\"currency\":\"EUR\",\"kind\":\"from\",\"unit\":\"day\"}"),
                valuesOf(ex, named(ex, Vocabulary.SERVICE, "Tagespflege"), Vocabulary.PRICE));
        assertEquals(List.of("{\"amount\":\"25.00\",\"as_of\":\"2026-08\",\"currency\":\"EUR\",\"kind\":\"fixed\",\"unit\":\"hour\"}"),
                valuesOf(ex, named(ex, Vocabulary.SERVICE, "Verhinderungspflege"), Vocabulary.PRICE));
        assertEquals("three prices, none twice and none for the column heads", 3, values(ex, Vocabulary.PRICE).size());
    }

    /** Benchmark finding: "Wir sind Partner der …" named no partner. */
    @Test
    public void aPartnerNamedInASentenceIsAPartner() {
        final Extraction ex = rules("CloudWerk entwickelt ERP-Software. Wir sind Partner der PflegeSoft GmbH. Zertifiziert nach ISO 27001.",
                "https://www.cloudwerk.de/", vocabulary("software", "stackfinder-web"), List.of());
        final Mention partner = named(ex, Vocabulary.ORGANIZATION, "PflegeSoft GmbH");
        assertNotNull(ex.mentions().toString(), partner);
        assertTrue(ex.claims().stream().anyMatch(c -> Vocabulary.PARTNER_OF.equals(c.predicate) && partner.ref.equals(c.object)));
        // a generic "Partner der Region" names no organisation
        final Extraction region = rules("Wir sind Partner der Region und der Menschen vor Ort.", "https://www.cloudwerk.de/",
                vocabulary("software", "stackfinder-web"), List.of());
        assertTrue(region.claims().stream().noneMatch(c -> Vocabulary.PARTNER_OF.equals(c.predicate)));
    }

    /** Benchmark finding: the heading before a price list line was read as part of its label ("Preise. Wartung Gasheizung"). */
    @Test
    public void aPriceListLabelStartsAfterTheHeading() {
        final Extraction ex = rules("Preise. Wartung Gasheizung: 149 € pauschal. Stand: 09/2026", "https://www.mueller-haustechnik.de/preise",
                care(false), List.of());
        assertNotNull(ex.mentions().toString(), named(ex, Vocabulary.SERVICE, "Wartung Gasheizung"));
        assertNull(named(ex, Vocabulary.SERVICE, "Preise. Wartung Gasheizung"));
        for (final Claim c : ex.claims()) {
            if (Vocabulary.NAME.equals(c.predicate) && "Wartung Gasheizung".equals(c.value)) {
                assertTrue("the name's evidence is the label itself: " + c.excerpt, c.excerpt.contains("Wartung Gasheizung"));
            }
        }
        assertEquals("Wartung Gasheizung", Values.afterLastSentence("Preise. Wartung Gasheizung"));
        assertEquals("St. Martin Paket", Values.afterLastSentence("St. Martin Paket"));
        assertEquals("Grundpaket", Values.afterLastSentence("Grundpaket"));
    }

    @Test
    public void aPriceOnAPageThatIsNoPriceListNeedsANamedService() {
        final Extraction ex = rules("Willkommen. Jetzt nur 2 € für den Newsletter-Versand. Tagespflege gibt es auch.",
                "https://www.sonnenschein-pflege.de/", care(false), List.of());
        assertTrue(values(ex, Vocabulary.PRICE).isEmpty());
        final Mention tages = named(ex, Vocabulary.SERVICE, "Tagespflege");
        assertNotNull(tages);
        for (final Claim c : ex.claims()) {
            if (c.subject.equals(tages.ref)) {
                assertEquals("a home page alone is a weak signal", 0.5, c.effectiveConfidence(), 1e-9);
            }
        }
    }

    @Test
    public void relationsAfterExplicitMarkersNeverPersons() {
        final String text = "Über uns. Träger: Caritasverband für die Diözese Musterstadt e.V. Wir sind Mitglied im Bundesverband privater"
                + " Anbieter sozialer Dienste e.V. Unsere Partner: Muster Software GmbH, Beispiel AG und Max Mustermann. "
                + "Die Sonnenschein Pflege GmbH ist eine Tochtergesellschaft der Muster Holding GmbH. Referenzen: Stadtwerke Musterstadt, Siemens. "
                + "Kunden wie DHL und Bosch vertrauen uns. Wir sind zertifiziert nach DIN EN ISO 9001:2015 und zertifiziert durch TÜV Süd."
                + " Gefördert durch die KfW Bankengruppe.";
        final Extraction ex = rules(text, "https://www.sonnenschein-pflege.de/ueber-uns", care(false), List.of());
        final String s = BusinessRules.SITE_REF;
        assertEquals(s, ex.mention(s).ref);
        assertEquals(List.of("Caritasverband für die Diözese Musterstadt e.V. -> null"), relations(ex, Vocabulary.CARRIER_OF));
        assertEquals(List.of("null -> Bundesverband privater Anbieter sozialer Dienste e.V."), relations(ex, Vocabulary.ASSOCIATION_MEMBER));
        assertEquals(List.of("null -> Muster Software GmbH", "null -> Beispiel AG"), relations(ex, Vocabulary.PARTNER_OF));
        assertEquals(List.of("null -> Muster Holding GmbH"), relations(ex, Vocabulary.SUBSIDIARY_OF));
        assertEquals(List.of("Stadtwerke Musterstadt -> null", "Siemens -> null"), relations(ex, Vocabulary.REFERENCE_FOR));
        assertEquals(List.of("DHL -> null", "Bosch -> null"), relations(ex, Vocabulary.CUSTOMER_OF));
        assertEquals(List.of("null -> TÜV Süd"), relations(ex, Vocabulary.CERTIFIED_BY));
        assertEquals(List.of("null -> KfW Bankengruppe"), relations(ex, Vocabulary.FUNDED_BY));
        assertEquals(List.of("DIN EN ISO 9001:2015"), values(ex, Vocabulary.CERTIFICATION));
        for (final Mention m : ex.mentions()) {
            assertFalse("no person becomes an organisation: " + m, String.valueOf(m.name).contains("Mustermann"));
        }
    }

    @Test
    public void theIndustryOfAnImprintAndTheSafeLevelOfAService() {
        final String imprint = "Impressum Angaben gemäß § 5 TMG Muster Bedachungen GmbH Dachstraße 1 12345 Musterstadt Telefon: 030 1234567 "
                + "Eintragung in die Handwerksrolle der Handwerkskammer Musterstadt, Gewerk: Dachdecker. Registergericht: Amtsgericht "
                + "Musterstadt HRB 1234";
        final Extraction ex = rules(imprint, "https://www.muster-bedachungen.de/impressum", vocabulary("construction", "bauteamcheck-web"),
                List.of());
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertNotNull(op);
        assertEquals(List.of("43.41"), valuesOf(ex, op, Vocabulary.INDUSTRY));
        assertEquals(List.of("construction.dach_fassade"), valuesOf(ex, op, Vocabulary.INDUSTRY_CATEGORY));
        // a service whose category has no reliable class gives the higher level only ("Sanierung" -> F)
        final Extraction s = rules("Leistungen. Sanierung und Heizung aus einer Hand.", "https://www.muster-bau.de/leistungen",
                vocabulary("construction", "bauteamcheck-web"), List.of());
        final Mention site = s.mention(BusinessRules.SITE_REF);
        assertEquals(List.of("F", "43.22"), valuesOf(s, site, Vocabulary.INDUSTRY));
    }

    @Test
    public void organisationContactsWithoutPersons() {
        final String text = "Kontakt. Sonnenschein Pflege GmbH, Hauptstraße 1, 10115 Berlin. Telefon: 030 1234567, Fax: 030 1234568. "
                + "Sprechzeiten: Mo–Fr 8–16 Uhr. Abteilung Vertrieb: Tel. 030 99999, vertrieb@sonnenschein-pflege.de. "
                + "Ihr Ansprechpartner: Herr Max Muster, Fax: 030 1234599, max.muster@sonnenschein-pflege.de. "
                + "Nutzen Sie unser Kontaktformular. Anfahrt: Mit der U6 bis Oranienburger Tor, dann 5 Minuten zu Fuß.";
        final Extraction ex = rules(text, "https://www.sonnenschein-pflege.de/kontakt", care(false),
                List.of("www.linkedin.com/company/sonnenschein-pflege", "www.linkedin.com/in/max-muster", "www.facebook.com/sharer.php?u=x"));
        final Mention site = ex.mention(BusinessRules.SITE_REF);
        assertEquals(List.of("+49301234568"), valuesOf(ex, site, Vocabulary.FAX));
        assertEquals(List.of("Mo–Fr 8–16 Uhr"), valuesOf(ex, site, Vocabulary.OFFICE_HOURS));
        assertEquals(List.of("{\"email\":\"vertrieb@sonnenschein-pflege.de\",\"function\":\"Vertrieb\",\"phone\":\"+493099999\"}"),
                valuesOf(ex, site, Vocabulary.CONTACT_POINT));
        assertEquals(List.of("https://www.sonnenschein-pflege.de/kontakt"), valuesOf(ex, site, Vocabulary.CONTACT_FORM));
        assertEquals(List.of("Mit der U6 bis Oranienburger Tor, dann 5 Minuten zu Fuß"), valuesOf(ex, site, Vocabulary.DIRECTIONS));
        assertEquals(List.of("https://linkedin.com/company/sonnenschein-pflege"), valuesOf(ex, site, Vocabulary.SOCIAL_PROFILE));
        for (final Claim c : ex.claims()) {
            assertFalse("no person, no personal e-mail: " + c, String.valueOf(c.value).contains("max.muster")
                    || String.valueOf(c.value).contains("Max Muster") || String.valueOf(c.value).contains("1234599"));
        }
    }

    @Test
    public void jobsOnACareersPageWithSalaryDeadlineAndRoute() {
        final String text = "Karriere. Wir suchen Pflegefachkraft (m/w/d) in Vollzeit oder Teilzeit. Vergütung: 3.400 – 3.900 € brutto "
                + "monatlich nach AVR. Bewerbungsfrist: 31.12.2026. Bewerbung an bewerbung@sonnenschein-pflege.de. Ihr Ansprechpartner: "
                + "Frau Erika Musterfrau, e.musterfrau@sonnenschein-pflege.de. Auszubildende Pflegefachfrau/-mann (w/m/d) zum 01.08.2027. "
                + "Jetzt online bewerben.";
        final Extraction off = rules(text, "https://www.sonnenschein-pflege.de/karriere", care(false), List.of());
        assertTrue("jobs are off for the collection", off.mentions().stream().noneMatch(m -> Vocabulary.JOB.equals(m.type)));
        final Extraction ex = rules(text, "https://www.sonnenschein-pflege.de/karriere", care(true), List.of());
        final Mention job = named(ex, Vocabulary.JOB, "Pflegefachkraft (m/w/d)");
        assertNotNull(ex.mentions().toString(), job);
        assertEquals(List.of("full_time", "part_time"), valuesOf(ex, job, Vocabulary.EMPLOYMENT_TYPE));
        assertEquals(List.of("{\"currency\":\"EUR\",\"gross\":true,\"kind\":\"range\",\"max\":\"3900.00\",\"min\":\"3400.00\",\"unit\":\"month\"}"),
                valuesOf(ex, job, Vocabulary.SALARY));
        assertEquals(List.of("2026-12-31"), valuesOf(ex, job, Vocabulary.VALID_THROUGH));
        assertEquals(List.of("mailto:bewerbung@sonnenschein-pflege.de"), valuesOf(ex, job, Vocabulary.APPLICATION_ROUTE));
        final Mention azubi = named(ex, Vocabulary.JOB, "Auszubildende Pflegefachfrau/-mann (w/m/d)");
        assertNotNull(azubi);
        assertEquals(List.of("2027-08-01"), valuesOf(ex, azubi, Vocabulary.START_DATE));
        assertEquals(List.of("https://www.sonnenschein-pflege.de/karriere"), valuesOf(ex, azubi, Vocabulary.APPLICATION_ROUTE));
        for (final Claim c : ex.claims()) {
            assertFalse("no recruiter: " + c, String.valueOf(c.value).contains("Musterfrau") || String.valueOf(c.value).contains("e.musterfrau"));
        }
    }

    @Test
    public void declaredAudienceOfASoftwareCompany() {
        final String text = "Für wen? Wir unterstützen Pflegeeinrichtungen und ambulante Pflegedienste im Mittelstand bundesweit, "
                + "wenn Sie Ihre Dienstplanung digitalisieren wollen.";
        final Extraction ex = rules(text, "https://www.muster-software.de/fuer-wen", vocabulary("software", "stackfinder-web"), List.of());
        final Mention site = ex.mention(BusinessRules.SITE_REF);
        assertEquals(List.of("care/stationaere_langzeitpflege", "care/ambulante_pflege"), valuesOf(ex, site, Vocabulary.TARGET_CATEGORY));
        assertEquals(List.of("87.10", "88.10"), valuesOf(ex, site, Vocabulary.TARGET_INDUSTRY));
        assertEquals(List.of("midsize"), valuesOf(ex, site, Vocabulary.COMPANY_SIZE));
        assertEquals(List.of("{\"kind\":\"national\",\"name\":\"Deutschland\"}"), valuesOf(ex, site, Vocabulary.SERVICE_AREA));
        assertEquals(List.of("Ihre Dienstplanung digitalisieren wollen"), valuesOf(ex, site, Vocabulary.NEED));
        assertTrue("its own field names services, not customers", valuesOf(ex, site, Vocabulary.CATEGORY).isEmpty());
    }
}
