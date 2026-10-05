package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.yacy.scoutro.knowledge.resolve.Normalizers;

public class ExtractorsTest {

    private static final String PAGE = "https://www.muster-pflege.de/standorte/berlin";

    private static Extraction jsonld(final String... blocks) {
        final Extraction ex = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of(blocks), PAGE, "www.muster-pflege.de", "de", ex);
        return ex;
    }

    private static List<String> values(final Extraction ex, final String ref, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.subject.equals(ref) && c.predicate.equals(predicate)) {
                out.add(c.relation() ? "@" + c.object : c.value);
            }
        }
        return out;
    }

    private static Mention only(final Extraction ex, final String type) {
        Mention found = null;
        for (final Mention m : ex.mentions()) {
            if (m.type.equals(type)) {
                assertNull("more than one " + type + ": " + ex.mentions(), found);
                found = m;
            }
        }
        assertNotNull("no " + type + " in " + ex.mentions(), found);
        return found;
    }

    @Test
    public void organisationOnItsOwnDomainIsTheSiteOperatorWithStrongKeys() {
        final Extraction ex = jsonld("{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"@id\":\"#org\","
                + "\"name\":\"Muster Pflege\",\"legalName\":\"Muster Pflege gGmbH\",\"url\":\"https://www.muster-pflege.de/\","
                + "\"vatID\":\"DE 123 456 789\",\"telephone\":\"030 1234567\",\"email\":\"Info@Muster-Pflege.de\","
                + "\"sameAs\":[\"https://www.wikidata.org/wiki/Q4242\"],"
                + "\"address\":{\"@type\":\"PostalAddress\",\"streetAddress\":\"Musterstr. 12\",\"postalCode\":\"12345\",\"addressLocality\":\"Berlin\"}}");
        final Mention org = only(ex, Vocabulary.ORGANIZATION);
        assertTrue(org.siteOperator);
        assertEquals("Muster Pflege gGmbH", org.legalName);
        assertEquals("DE123456789", org.strongKeys.get(Vocabulary.VAT));
        assertEquals("Q4242", org.strongKeys.get(Vocabulary.WIKIDATA));
        assertEquals("https://www.muster-pflege.de/standorte/berlin#org", org.ldId);
        assertEquals(List.of("+49301234567"), values(ex, org.ref, Vocabulary.PHONE));
        assertEquals(List.of("info@muster-pflege.de"), values(ex, org.ref, Vocabulary.EMAIL));
        assertEquals(List.of("gGmbH"), values(ex, org.ref, Vocabulary.LEGAL_FORM));
        assertEquals(List.of("Musterstr. 12, 12345 Berlin"), values(ex, org.ref, Vocabulary.ADDRESS));
        assertTrue(org.address.complete());
        assertEquals(Normalizers.streetKey("Musterstraße") + " 12|12345|berlin", org.address.key());
    }

    @Test
    public void facilityWithParentOrganisationAndKindAndService() {
        final Extraction ex = jsonld("{\"@context\":\"https://schema.org\",\"@type\":\"ChildCare\",\"name\":\"Kita Sonnenschein\","
                + "\"address\":{\"streetAddress\":\"Lindenallee 3\",\"postalCode\":\"12345\",\"addressLocality\":\"Berlin\"},"
                + "\"geo\":{\"latitude\":52.52,\"longitude\":13.405},"
                + "\"parentOrganization\":{\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\"},"
                + "\"makesOffer\":{\"@type\":\"Offer\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"Krippenbetreuung\"}}}");
        final Mention kita = only(ex, Vocabulary.FACILITY);
        assertEquals("childcare", kita.subkind);
        assertTrue(kita.address.complete());
        final Mention org = only(ex, Vocabulary.ORGANIZATION);
        assertFalse("a parent named in a facility is no site operator by itself", org.siteOperator);
        final Mention service = only(ex, Vocabulary.SERVICE);
        assertEquals(List.of("@" + kita.ref), values(ex, org.ref, Vocabulary.OPERATES));
        assertEquals(List.of("@" + service.ref), values(ex, kita.ref, Vocabulary.OFFERS));
        assertEquals(List.of("52.520000,13.405000"), values(ex, kita.ref, Vocabulary.GEO));
    }

    @Test
    public void graphReferencesAndPublisherOfTheWebsite() {
        final Extraction ex = jsonld("{\"@context\":\"https://schema.org\",\"@graph\":["
                + "{\"@type\":\"WebSite\",\"url\":\"https://www.muster-pflege.de/\",\"publisher\":{\"@id\":\"https://www.muster-pflege.de/#org\"}},"
                + "{\"@type\":\"Organization\",\"@id\":\"https://www.muster-pflege.de/#org\",\"name\":\"Muster Holding GmbH\"},"
                + "{\"@type\":\"Person\",\"name\":\"Max Mustermann\"}]}");
        final Mention org = only(ex, Vocabulary.ORGANIZATION);
        assertTrue(org.siteOperator);
        assertEquals(1, ex.mentions().size());
        for (final Claim c : ex.claims()) {
            assertFalse("no person", c.value != null && c.value.contains("Mustermann"));
        }
    }

    @Test
    public void invalidOversizedAndDeepBlocksAreDropped() {
        final StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            deep.append("{\"a\":");
        }
        deep.append("1");
        for (int i = 0; i < 9; i++) {
            deep.append('}');
        }
        final StringBuilder wide = new StringBuilder("{\"@type\":\"Organization\",\"x\":[");
        for (int i = 0; i < 600; i++) {
            wide.append(i).append(',');
        }
        wide.append("0]}");
        final Extraction ex = jsonld("{not json", deep.toString(), wide.toString());
        assertEquals(3, ex.invalidBlocks());
        assertTrue(ex.mentions().isEmpty());
        assertTrue(JsonLdBlocks.accept("{\"@type\":\"Dentist\",\"name\":\"Praxis\"}"));
        assertFalse("no relevant type", JsonLdBlocks.accept("{\"@type\":\"Recipe\",\"name\":\"Kuchen\"}"));
        assertNull(JsonLdBlocks.parse(deep.toString()));
    }

    @Test
    public void metadataPublisherWithLegalFormAndCoordinate() {
        final Extraction ex = jsonld("{\"@type\":\"Dentist\",\"name\":\"Zahnarztpraxis Mitte\"}");
        new MetadataExtractor(200).extract("Zahnarzt Mitte MVZ GmbH", new double[] {52.5, 13.4}, ex);
        final Mention publisher = ex.mention(MetadataExtractor.PUBLISHER_REF);
        assertTrue(publisher.siteOperator);
        assertEquals("Zahnarzt Mitte MVZ GmbH", publisher.legalName);
        assertEquals(List.of("52.500000,13.400000"), values(ex, only(ex, Vocabulary.FACILITY).ref, Vocabulary.GEO));
        final Extraction none = new Extraction(50);
        new MetadataExtractor(200).extract("Mitte Blog", null, none);
        assertTrue("a publisher without legal form is no operator", none.mentions().isEmpty());
    }

    static final String IMPRINT = "Startseite Leistungen Kontakt\nImpressum\nAngaben gemäß § 5 DDG\nMuster Pflege gGmbH\n"
            + "Musterstraße 12\n12345 Berlin\nTelefon: 030 / 123 45 67\nTelefax: 030 / 123 45 68\nE-Mail: info@muster-pflege.de\n"
            + "Vertreten durch die Geschäftsführerin Erika Musterfrau\nRegistergericht: Amtsgericht Charlottenburg\n"
            + "Registernummer: HRB 12345 B\nUmsatzsteuer-Identifikationsnummer gemäß § 27 a Umsatzsteuergesetz: DE 123 456 789\n";

    @Test
    public void imprintYieldsTheOperatorWithRegisterVatAddressAndContact() {
        final Extraction ex = new Extraction(50);
        assertTrue(RuleExtractor.candidate("https://www.muster-pflege.de/impressum", List.of("Impressum"), ex));
        new RuleExtractor(200, 65536).extract(IMPRINT, "https://www.muster-pflege.de/impressum", "www.muster-pflege.de", "de", ex);
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertNotNull(ex.mentions().toString(), op);
        assertEquals("Muster Pflege gGmbH", op.legalName);
        assertTrue(op.siteOperator);
        assertEquals("hrb 12345 b@charlottenburg", op.strongKeys.get(Vocabulary.REGISTER));
        assertEquals("DE123456789", op.strongKeys.get(Vocabulary.VAT));
        assertEquals(List.of("HRB 12345 B, Amtsgericht Charlottenburg"), values(ex, op.ref, Vocabulary.ID_REGISTER));
        assertEquals(List.of("Musterstraße 12, 12345 Berlin"), values(ex, op.ref, Vocabulary.ADDRESS));
        assertEquals(List.of("+49301234567"), values(ex, op.ref, Vocabulary.PHONE));
        assertEquals(List.of("info@muster-pflege.de"), values(ex, op.ref, Vocabulary.EMAIL));
        for (final Claim c : ex.claims()) {
            assertFalse("persons are not extracted: " + c, c.value.contains("Musterfrau"));
            assertTrue(c.locator.startsWith("text:"));
            assertTrue(c.excerpt.length() <= 200);
        }
    }

    @Test
    public void organisationNamesAfterIntroducingWordsAndOnOneLine() {
        final Extraction ex = new Extraction(50);
        new RuleExtractor(200, 65536).extract("Impressum Diese Website wird betrieben von der Muster Holding GmbH & Co. KG, Am Markt 5, 54321 Musterstadt.",
                "https://example.de/impressum", "example.de", "de", ex);
        assertEquals("Muster Holding GmbH & Co. KG", ex.mention(RuleExtractor.OPERATOR_REF).legalName);
        final Extraction ex2 = new Extraction(50);
        new RuleExtractor(200, 65536).extract("Impressum Angaben gemäß § 5 TMG Sonnenschein e.V. Hauptstr. 1 10115 Berlin",
                "https://example.de/impressum", "example.de", "de", ex2);
        assertEquals("Sonnenschein e.V.", ex2.mention(RuleExtractor.OPERATOR_REF).legalName);
        assertEquals(List.of("Hauptstr. 1, 10115 Berlin"), values(ex2, RuleExtractor.OPERATOR_REF, Vocabulary.ADDRESS));
        final Extraction none = new Extraction(50);
        new RuleExtractor(200, 65536).extract("Impressum Herausgeber: Erika Musterfrau, Hauptstr. 1, 10115 Berlin",
                "https://example.de/impressum", "example.de", "de", none);
        assertTrue("no legal form, no operator", none.mentions().isEmpty());
    }

    @Test
    public void contactValuesAttachOnlyToASingleStructuredSubject() {
        final String text = "Kontakt Telefon: +49 30 9876543 E-Mail: kita@example.de";
        final Extraction one = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of("{\"@type\":\"ChildCare\",\"name\":\"Kita A\"}"), "https://example.de/kontakt", "example.de", "de", one);
        new RuleExtractor(200, 65536).extract(text, "https://example.de/kontakt", "example.de", "de", one);
        assertEquals(List.of("+49309876543"), values(one, only(one, Vocabulary.FACILITY).ref, Vocabulary.PHONE));
        final Extraction two = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of("[{\"@type\":\"ChildCare\",\"name\":\"Kita A\"},{\"@type\":\"ChildCare\",\"name\":\"Kita B\"}]"),
                "https://example.de/kontakt", "example.de", "de", two);
        new RuleExtractor(200, 65536).extract(text, "https://example.de/kontakt", "example.de", "de", two);
        for (final Claim c : two.claims()) {
            assertFalse("not guessed for one of two facilities", Vocabulary.PHONE.equals(c.predicate));
        }
    }

    @Test
    public void candidatePages() {
        assertTrue(RuleExtractor.candidate("https://example.de/", List.of("Start"), null));
        assertTrue(RuleExtractor.candidate("https://example.de/ueber-uns.html", List.of(), null));
        assertTrue(RuleExtractor.candidate("https://example.de/x", List.of("Kontakt und Anfahrt"), null));
        assertFalse(RuleExtractor.candidate("https://example.de/blog/2026/rezept", List.of("Kuchen"), new Extraction(50)));
    }

    @Test
    public void claimLimitPerDocument() {
        final Extraction ex = new Extraction(3);
        final StringBuilder b = new StringBuilder("{\"@type\":\"Organization\",\"name\":\"A GmbH\",\"telephone\":[");
        for (int i = 0; i < 10; i++) {
            b.append("\"030 12345").append(i).append("\",");
        }
        b.append("\"030 999999\"]}");
        new JsonLdExtractor(200).extract(List.of(b.toString()), PAGE, "www.muster-pflege.de", "de", ex);
        assertEquals(3, ex.claims().size());
        assertTrue(ex.droppedClaims() > 0);
    }
}
