/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * The first quality stage of the knowledge graph for facilities (docs/SCOUTRO_KG_FACILITIES.md): tiers 1 and 2 of the invented
 * pages of {@code test/scoutro-kg-facilities/}, read through YaCy's own HTML parser as {@code text_t} has them. A care provider
 * with three facilities on one domain: each facility's detail page names the facility with its own address, phone and role
 * mailbox; the operator's address in the footer, a contact person and a menu link "Impressum" give the facility nothing and the
 * operator nothing of the facility. The API used here is the one of 0.14.1, so the same tests show the state before the change.
 */
public class FacilityPageTest {

    static final String DIR = "test/scoutro-kg-facilities/";
    static final String HOST = "www.muster-altenhilfe.de";
    /** The site-wide organisation node of a CMS plugin (one structured subject on every page: the operator). */
    public static final String OPERATOR_LD = "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"Muster Altenhilfe gGmbH\","
            + "\"url\":\"https://www.muster-altenhilfe.de/\"}";

    public static String html(final String file) throws Exception {
        return new String(Files.readAllBytes(Paths.get(DIR + file)), StandardCharsets.UTF_8);
    }

    /** The page text and title as YaCy's HTML parser gives them for {@code text_t} and {@code title}. */
    public static String[] parsed(final String file, final String url) throws Exception {
        final net.yacy.document.Document d = new net.yacy.document.parser.htmlParser().parse(new net.yacy.cora.document.id.DigestURL(url),
                "text/html", "UTF-8", new net.yacy.document.VocabularyScraper(), 0,
                new java.io.ByteArrayInputStream(html(file).getBytes(StandardCharsets.UTF_8)))[0];
        return new String[] {d.getTextString(), d.dc_title()};
    }

    static ExtractContext ctx(final String vocabulary, final String collection) {
        return new ExtractContext(KgVocabularies.get(), Set.of(vocabulary), false, List.of(collection));
    }

    /** Tiers 1 and 2 of one page, in the order of the sync ({@code BaseTiers}). */
    static Extraction page(final String file, final String url, final String vocabulary, final String... jsonld) throws Exception {
        final String[] p = parsed(file, url);
        final String host = java.net.URI.create(url).getHost();
        final ExtractContext ctx = ctx(vocabulary, vocabulary + "-web");
        final Extraction ex = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of(jsonld), url, host, "de", ex, ctx);
        new RuleExtractor(200, 65536).extract(p[0], url, host, "de", ex, ctx, List.of(p[1]), null);
        return ex;
    }

    static Extraction care(final String file, final String path, final String... jsonld) throws Exception {
        return page(file, "https://" + HOST + path, "care", jsonld);
    }

    /** The values of a mention's claims of a predicate (tiers 1 and 2). */
    static List<String> values(final Extraction ex, final Mention m, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (m != null && c.subject.equals(m.ref) && c.predicate.equals(predicate) && c.value != null) {
                out.add(c.value);
            }
        }
        return out;
    }

    /** Every value of a mention's claims. */
    static List<String> allValues(final Extraction ex, final Mention m) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (m != null && c.subject.equals(m.ref) && c.value != null) {
                out.add(c.predicate + "=" + c.value);
            }
        }
        return out;
    }

    /** The facilities of tiers 1 and 2. */
    static List<Mention> facilities(final Extraction ex) {
        final List<Mention> out = new ArrayList<>();
        for (final Mention m : ex.mentions()) {
            if (Vocabulary.FACILITY.equals(m.type) && m.tier <= 2) {
                out.add(m);
            }
        }
        return out;
    }

    static Mention facility(final Extraction ex, final String name) {
        for (final Mention m : facilities(ex)) {
            if (name.equals(m.name)) {
                return m;
            }
        }
        return null;
    }

    /** The organisations of tiers 1 and 2. */
    static List<Mention> organisations(final Extraction ex) {
        final List<Mention> out = new ArrayList<>();
        for (final Mention m : ex.mentions()) {
            if (Vocabulary.ORGANIZATION.equals(m.type) && m.tier <= 2) {
                out.add(m);
            }
        }
        return out;
    }

    /** No claim of {@code m} carries any of {@code foreign}. */
    static void nothingOf(final Extraction ex, final Mention m, final String... foreign) {
        for (final String v : allValues(ex, m)) {
            for (final String f : foreign) {
                assertFalse(m.name + ": " + v + " is " + f, v.contains(f));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the address parser

    @Test
    public void yacysTextOfAnAddressWithBreaksHasDots() throws Exception {
        final String text = parsed("care-facility-lindenhof.html", "https://" + HOST + "/einrichtungen/haus-lindenhof/")[0];
        assertTrue(text, text.contains("Haus Lindenhof. Lindenweg 12. 12345 Musterstadt. Telefon: 030 2222222."));
        assertTrue("the menu link is part of the text", text.contains("- Impressum"));
    }

    @Test
    public void aDotBetweenHouseNumberAndPostalCodeIsAnAddress() throws Exception {
        final Extraction ex = care("care-operator-imprint.html", "/impressum/");
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertNotNull(ex.mentions().toString(), op);
        assertEquals("Muster Altenhilfe gGmbH", op.name);
        assertEquals(List.of("Trägerstraße 1, 10115 Musterstadt"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("10115"), values(ex, op, Vocabulary.POSTAL_CODE));
        assertEquals(List.of("Musterstadt"), values(ex, op, Vocabulary.LOCALITY));
        assertEquals(List.of("+49301111111"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("verwaltung@muster-altenhilfe.de"), values(ex, op, Vocabulary.EMAIL));
        assertEquals(List.of("DE123456789"), values(ex, op, Vocabulary.ID_VAT));
        assertTrue("the imprint names no facility", facilities(ex).isEmpty());
        nothingOf(ex, op, "Erika", "Musterfrau");
    }

    @Test
    public void aStreetNamedByItsSquareIsAnAddress() throws Exception {
        final Extraction ex = care("care-facility-am-markt.html", "/einrichtungen/seniorenzentrum-am-markt");
        final Mention f = facility(ex, "Seniorenzentrum Am Markt");
        assertNotNull(facilities(ex) + " " + ex.mentions(), f);
        assertEquals(List.of("Am Markt 5, 23456 Beispielstadt"), values(ex, f, Vocabulary.ADDRESS));
        assertEquals(List.of("23456"), values(ex, f, Vocabulary.POSTAL_CODE));
        assertEquals(List.of("Beispielstadt"), values(ex, f, Vocabulary.LOCALITY));
        assertEquals(List.of("+49403333333"), values(ex, f, Vocabulary.PHONE));
        assertEquals(List.of("pflege.am-markt@muster-altenhilfe.de"), values(ex, f, Vocabulary.EMAIL));
    }

    // ---------------------------------------------------------------------------------------- facility detail pages

    @Test
    public void aFacilitysDetailPageNamesTheFacilityWithItsOwnContacts() throws Exception {
        final Extraction ex = care("care-facility-lindenhof.html", "/einrichtungen/haus-lindenhof/");
        final Mention f = facility(ex, "Haus Lindenhof");
        assertNotNull(facilities(ex) + " " + ex.mentions(), f);
        assertEquals(List.of("Lindenweg 12, 12345 Musterstadt"), values(ex, f, Vocabulary.ADDRESS));
        assertEquals(List.of("12345"), values(ex, f, Vocabulary.POSTAL_CODE));
        assertEquals(List.of("Musterstadt"), values(ex, f, Vocabulary.LOCALITY));
        assertEquals(List.of("+49302222222"), values(ex, f, Vocabulary.PHONE));
        assertEquals(List.of("haus.lindenhof@muster-altenhilfe.de"), values(ex, f, Vocabulary.EMAIL));
        assertNotNull("the full address keys the facility across pages", f.address);
        assertTrue(f.address.complete());
    }

    @Test
    public void theOperatorsFooterAddressIsNoFacilityAddress() throws Exception {
        for (final String[] page : new String[][] {{"care-facility-lindenhof.html", "/einrichtungen/haus-lindenhof/", "Haus Lindenhof"},
                {"care-facility-am-markt.html", "/einrichtungen/seniorenzentrum-am-markt", "Seniorenzentrum Am Markt"},
                {"care-facility-am-see.html", "/einrichtungen/haus-am-see", "Haus Am See"}}) {
            for (final boolean withLd : new boolean[] {false, true}) {
                final Extraction ex = withLd ? care(page[0], page[1], OPERATOR_LD) : care(page[0], page[1]);
                final Mention f = facility(ex, page[2]);
                assertNotNull(page[0] + " " + ex.mentions(), f);
                nothingOf(ex, f, "Trägerstraße", "10115", "+49301111111", "verwaltung@");
                for (final Mention m : facilities(ex)) {
                    nothingOf(ex, m, "Trägerstraße", "10115", "+49301111111", "verwaltung@");
                }
            }
        }
    }

    @Test
    public void theOperatorGetsNothingOfAFacilitysPage() throws Exception {
        for (final boolean withLd : new boolean[] {false, true}) {
            final Extraction ex = withLd ? care("care-facility-lindenhof.html", "/einrichtungen/haus-lindenhof/", OPERATOR_LD)
                    : care("care-facility-lindenhof.html", "/einrichtungen/haus-lindenhof/");
            for (final Mention o : organisations(ex)) {
                nothingOf(ex, o, "Lindenweg", "12345", "+49302222222", "haus.lindenhof@", "Montag bis Freitag");
            }
        }
    }

    /**
     * A menu or footer link "Impressum" makes no page an imprint: the operator it names after it (the employer of a careers page)
     * gets none of the page's addresses, phones or mailboxes, nor its identifiers.
     */
    @Test
    public void aMenuLinkImpressumMakesNoPageAnImprint() throws Exception {
        for (final String[] page : new String[][] {{"care-facility-lindenhof.html", "/einrichtungen/haus-lindenhof/"},
                {"care-facilities-overview.html", "/einrichtungen/"}, {"care-contact.html", "/kontakt/"}}) {
            final Extraction ex = care(page[0], page[1]);
            final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
            if (op != null) {
                assertEquals("Muster Altenhilfe gGmbH", op.name);
                for (final String p : new String[] {Vocabulary.ADDRESS, Vocabulary.POSTAL_CODE, Vocabulary.PHONE, Vocabulary.EMAIL,
                        Vocabulary.ID_REGISTER, Vocabulary.ID_VAT, Vocabulary.OFFICE_HOURS, Vocabulary.OPENING_HOURS}) {
                    assertEquals(page[0] + " " + p, List.of(), values(ex, op, p));
                }
            }
        }
    }

    @Test
    public void aProvidersContactPageListingItsHousesGivesTheOperatorNoneOfTheirAddresses() throws Exception {
        final Extraction ex = care("care-contact.html", "/kontakt/", OPERATOR_LD);
        for (final Mention o : organisations(ex)) {
            nothingOf(ex, o, "Lindenweg", "Am Markt", "12345", "23456", "+49302222222", "+49403333333");
        }
        assertTrue("a contact page is no facility's page", facilities(ex).isEmpty());
    }

    @Test
    public void theFacilityKeepsItsOfficeHours() throws Exception {
        final Extraction ex = care("care-facility-lindenhof.html", "/einrichtungen/haus-lindenhof/", OPERATOR_LD);
        final Mention f = facility(ex, "Haus Lindenhof");
        assertNotNull(ex.mentions().toString(), f);
        final List<String> hours = new ArrayList<>(values(ex, f, Vocabulary.OFFICE_HOURS));
        hours.addAll(values(ex, f, Vocabulary.OPENING_HOURS));
        assertEquals(hours.toString(), 1, hours.size());
        assertTrue(hours.get(0), hours.get(0).contains("Montag bis Freitag 8:00 bis 16:00 Uhr"));
    }

    @Test
    public void noPersonalContactsAndNoPersonsOnAFacilitysPage() throws Exception {
        final Extraction ex = care("care-facility-lindenhof.html", "/einrichtungen/haus-lindenhof/", OPERATOR_LD);
        for (final Claim c : ex.claims()) {
            assertFalse(c.toString(), c.value != null && c.value.contains("p.beispiel@"));
            assertFalse(c.toString(), c.value != null && c.value.contains("Petra"));
        }
        for (final Mention m : ex.mentions()) {
            assertFalse(m.toString(), m.name != null && m.name.contains("Petra"));
        }
    }

    @Test
    public void anOverviewOfFacilitiesIsNoFacilityPage() throws Exception {
        final Extraction ex = care("care-facilities-overview.html", "/einrichtungen/", OPERATOR_LD);
        assertTrue(facilities(ex).toString(), facilities(ex).isEmpty());
        for (final Mention o : organisations(ex)) {
            nothingOf(ex, o, "Lindenweg", "Am Markt", "Seeufer", "12345", "23456", "34567");
        }
    }

    @Test
    public void aNewsPageIsNoFacilityPage() throws Exception {
        final Extraction ex = care("care-news.html", "/aktuelles/sommerfest/", OPERATOR_LD);
        assertTrue(facilities(ex).toString(), facilities(ex).isEmpty());
    }

    @Test
    public void bothAddressesOfAPageWithAndWithoutItsSlashNameOneFacility() throws Exception {
        final Extraction a = care("care-facility-am-see.html", "/einrichtungen/haus-am-see");
        final Extraction b = care("care-facility-am-see.html", "/einrichtungen/haus-am-see/");
        final Mention fa = facility(a, "Haus Am See");
        final Mention fb = facility(b, "Haus Am See");
        assertNotNull(a.mentions().toString(), fa);
        assertNotNull(b.mentions().toString(), fb);
        assertEquals(List.of("Seeufer 3a, 34567 Probehausen"), values(a, fa, Vocabulary.ADDRESS));
        assertEquals(values(a, fa, Vocabulary.ADDRESS), values(b, fb, Vocabulary.ADDRESS));
        assertEquals("one identity key for both", fa.address.key(), fb.address.key());
    }

    // ------------------------------------------------------------------------------------------------- other fields

    @Test
    public void anItCompanysContactPageKeepsItsAddress() throws Exception {
        final String ld = "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"Beispiel IT GmbH\",\"url\":\"https://www.beispiel-it.de/\"}";
        final Extraction ex = page("software-contact.html", "https://www.beispiel-it.de/kontakt/", "software", ld);
        // the page's one structured organisation (or, before 0.15.0, the operator a menu link "Impressum" made the rules read)
        final List<String> addresses = new ArrayList<>();
        final List<String> phones = new ArrayList<>();
        final List<String> mails = new ArrayList<>();
        for (final Mention o : organisations(ex)) {
            assertEquals("Beispiel IT GmbH", o.name);
            addresses.addAll(values(ex, o, Vocabulary.ADDRESS));
            phones.addAll(values(ex, o, Vocabulary.PHONE));
            mails.addAll(values(ex, o, Vocabulary.EMAIL));
        }
        assertEquals(List.of("Musterallee 7, 34117 Kassel"), addresses);
        assertEquals(List.of("+495615555555"), phones);
        assertEquals(List.of("kontakt@beispiel-it.de"), mails);
        assertTrue(facilities(ex).isEmpty());
    }

    @Test
    public void aBuildersImprintStaysAsItWas() throws Exception {
        final Extraction ex = page("construction-imprint.html", "https://www.musterbau.de/impressum/", "construction");
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertNotNull(ex.mentions().toString(), op);
        assertEquals("Musterbau GmbH", op.name);
        assertEquals(List.of("Baustraße 9, 45678 Bauhausen"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+492016666666"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@musterbau.de"), values(ex, op, Vocabulary.EMAIL));
        assertTrue(facilities(ex).isEmpty());
    }
}
