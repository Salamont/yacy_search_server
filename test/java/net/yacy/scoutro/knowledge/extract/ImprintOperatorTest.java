package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The operator of an imprint by its role and section (rule extractor version 4): the operator's insurer, chamber,
 * supervisory authority or web agency never becomes the operator nor lends it contact values, whatever the names; an
 * operator without a legal form is named only on clear evidence, never a person.
 */
public class ImprintOperatorTest {

    /** An architect's imprint: the operator without a legal form, then its chamber and its liability insurer with their own addresses. */
    public static final String LIVAID = "Startseite Leistungen Projekte Kontakt\nImpressum\nAngaben gemäß § 5 DDG\nLIVAID\n"
            + "Lindenallee 12\n10115 Berlin\nTelefon: +49 30 1234567\nE-Mail: info@livaid.com\nInhaber: Max Mustermann\n"
            + "Umsatzsteuer-Identifikationsnummer gemäß § 27 a UStG: DE 123 456 789\n"
            + "Berufsbezeichnung: Architekt (verliehen in der Bundesrepublik Deutschland)\n"
            + "Zuständige Kammer: Architektenkammer Berlin, Alte Jakobstraße 149, 10969 Berlin, Telefon: +49 30 2933070\n"
            + "Berufshaftpflichtversicherung\nName und Sitz des Versicherers:\nMarkel Insurance SE\nSophienstraße 26\n80333 München\n"
            + "Telefon: +49 89 8908310\nE-Mail: info@markel.de\nUSt-IdNr. des Versicherers: DE 298 765 432\n"
            + "Geltungsraum der Versicherung: Deutschland\n";

    private static Extraction rules(final String text, final String url) {
        final Extraction ex = new Extraction(50);
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex);
        return ex;
    }

    private static List<String> values(final Extraction ex, final String ref, final String predicate) {
        final List<String> out = new ArrayList<>();
        for (final Claim c : ex.claims()) {
            if (c.subject.equals(ref) && c.predicate.equals(predicate)) {
                out.add(c.value);
            }
        }
        return out;
    }

    private static void nothingOf(final Extraction ex, final String... foreign) {
        for (final Claim c : ex.claims()) {
            for (final String f : foreign) {
                assertFalse(c + " belongs to " + f, c.value.contains(f));
            }
        }
        for (final Mention m : ex.mentions()) {
            for (final String f : foreign) {
                assertFalse(m + " is " + f, m.name != null && m.name.contains(f));
            }
        }
    }

    @Test
    public void theOperatorWithoutLegalFormIsNamedWithItsOwnContactsOnly() {
        final Extraction ex = rules(LIVAID, "https://www.livaid.com/impressum");
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertNotNull(ex.mentions().toString(), op);
        assertEquals("LIVAID", op.name);
        assertNull("no legal form, no legal name", op.legalName);
        assertFalse("not keyed as a declared operator without a legal name", op.siteOperator);
        assertTrue("the name belongs to the site's operator of the domain", op.domainOperator);
        assertEquals(List.of("LIVAID"), values(ex, op.ref, Vocabulary.NAME));
        assertEquals(List.of("Lindenallee 12, 10115 Berlin"), values(ex, op.ref, Vocabulary.ADDRESS));
        assertEquals(List.of("+49301234567"), values(ex, op.ref, Vocabulary.PHONE));
        assertEquals(List.of("info@livaid.com"), values(ex, op.ref, Vocabulary.EMAIL));
        assertEquals(List.of("DE123456789"), values(ex, op.ref, Vocabulary.ID_VAT));
        assertEquals("DE123456789", op.strongKeys.get(Vocabulary.VAT));
        assertEquals(1, ex.mentions().size());
        nothingOf(ex, "Markel", "Sophien", "80333", "markel.de", "+49898908310", "DE298765432", "Jakob", "10969", "+49302933070",
                "Mustermann");
    }

    @Test
    public void beforeVersion4TheInsurerWasTheOperator() {
        // the reported case: the first legal form after the marker was the insurer's ("Markel Insurance SE"); the same
        // imprint with a legal form for the operator shows that an operator GmbH keeps the insurer's contacts out too
        final Extraction ex = rules(LIVAID.replace("\nLIVAID\n", "\nLIVAID GmbH\n").replace("Telefon: +49 30 1234567\nE-Mail: info@livaid.com\n", ""),
                "https://www.livaid.com/impressum");
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertEquals("LIVAID GmbH", op.legalName);
        assertTrue(op.siteOperator);
        assertEquals(List.of("Lindenallee 12, 10115 Berlin"), values(ex, op.ref, Vocabulary.ADDRESS));
        assertEquals("the operator states no phone: the insurer's and the chamber's are not taken", List.of(), values(ex, op.ref, Vocabulary.PHONE));
        assertEquals(List.of(), values(ex, op.ref, Vocabulary.EMAIL));
        assertEquals(List.of("DE123456789"), values(ex, op.ref, Vocabulary.ID_VAT));
        nothingOf(ex, "Markel", "Sophien", "markel.de", "+49898908310", "DE298765432", "+49302933070");
    }

    @Test
    public void theBusinessRulesTakeNoFaxOrContactPointOfAnotherParty() {
        // the operator states no fax; the insurer's fax and contact point follow in its own section
        final String imprint = LIVAID.replace("Geltungsraum der Versicherung: Deutschland\n",
                "Fax: +49 89 8908311\nAbteilung Schaden: Telefon +49 89 8908312, E-Mail: service@markel.de\n");
        final Extraction ex = new Extraction(200);
        new RuleExtractor(200, 65536).extract(imprint, "https://www.livaid.com/impressum", "www.livaid.com", "de", ex,
                new ExtractContext(net.yacy.scoutro.knowledge.vocab.KgVocabularies.get(), java.util.Set.of("construction"), false,
                        List.of("bauteamcheck-web")), List.of("Impressum"), List.of());
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertEquals("LIVAID", op.name);
        assertEquals(List.of(), values(ex, op.ref, Vocabulary.FAX));
        assertEquals(List.of(), values(ex, op.ref, Vocabulary.CONTACT_POINT));
        nothingOf(ex, "Markel", "markel.de", "+49898908311", "+49898908312");
        // the operator's own fax is kept
        final Extraction own = new Extraction(200);
        new RuleExtractor(200, 65536).extract(imprint.replace("E-Mail: info@livaid.com\n", "E-Mail: info@livaid.com\nFax: +49 30 1234568\n"),
                "https://www.livaid.com/impressum", "www.livaid.com", "de", own,
                new ExtractContext(net.yacy.scoutro.knowledge.vocab.KgVocabularies.get(), java.util.Set.of("construction"), false,
                        List.of("bauteamcheck-web")), List.of("Impressum"), List.of());
        assertEquals(List.of("+49301234568"), values(own, RuleExtractor.OPERATOR_REF, Vocabulary.FAX));
    }

    @Test
    public void anInsurerOperatingTheSiteIsItsOperator() {
        final String imprint = "Impressum\nMarkel Insurance SE\nSophienstraße 26\n80333 München\nTelefon: +49 89 8908310\n"
                + "E-Mail: info@markel.de\nAufsichtsbehörde: Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin), Graurheindorfer Straße 108,"
                + " 53117 Bonn, Telefon: +49 228 41080\nRegistergericht: Amtsgericht München, HRB 233618\nUSt-IdNr.: DE 298 765 432\n";
        final Extraction ex = rules(imprint, "https://www.markel.de/impressum");
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertEquals("Markel Insurance SE", op.legalName);
        assertTrue(op.siteOperator);
        assertEquals(List.of("Sophienstraße 26, 80333 München"), values(ex, op.ref, Vocabulary.ADDRESS));
        assertEquals(List.of("+49898908310"), values(ex, op.ref, Vocabulary.PHONE));
        assertTrue("the register after the authority's section is the operator's again: " + op.strongKeys,
                op.strongKeys.get(Vocabulary.REGISTER).startsWith("hrb 233618@m"));
        assertEquals(List.of("DE298765432"), values(ex, op.ref, Vocabulary.ID_VAT));
        nothingOf(ex, "BaFin", "Graurheindorfer", "53117", "+4922841080");
    }

    @Test
    public void anInsuranceBrokerKeepsItsNameAndItsInsurerAndChamberStayApart() {
        final String imprint = "Impressum\nAngaben gemäß § 5 TMG\nMüller Versicherungsmakler GmbH\nHauptstraße 5\n50667 Köln\n"
                + "Telefon: 0221 123456\nE-Mail: info@mueller-makler.de\nBerufsbezeichnung: Versicherungsmakler mit Erlaubnis nach § 34d Abs. 1 GewO\n"
                + "Zuständige Kammer: IHK Köln, Unter Sachsenhausen 10, 50667 Köln, Telefon: 0221 16400\n"
                + "Berufshaftpflichtversicherung: Allianz Versicherungs-AG, Königinstraße 28, 80802 München, Telefon: 089 38000\n";
        final Extraction ex = rules(imprint, "https://www.mueller-makler.de/impressum");
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertEquals("Müller Versicherungsmakler GmbH", op.legalName);
        assertEquals(List.of("Hauptstraße 5, 50667 Köln"), values(ex, op.ref, Vocabulary.ADDRESS));
        assertEquals(List.of("+49221123456"), values(ex, op.ref, Vocabulary.PHONE));
        assertEquals(List.of("info@mueller-makler.de"), values(ex, op.ref, Vocabulary.EMAIL));
        nothingOf(ex, "Allianz", "Königin", "80802", "+498938000", "Sachsenhausen", "+4922116400");
        // a chamber's own company as the operator: "Kammer" in a name is no section label
        final Extraction chamber = rules("Impressum\nAngaben gemäß § 5 DDG\nIngenieurkammer Service GmbH\nHauptstraße 1\n10115 Berlin\n",
                "https://www.ik-service.de/impressum");
        assertEquals("Ingenieurkammer Service GmbH", chamber.mention(RuleExtractor.OPERATOR_REF).legalName);
    }

    @Test
    public void aThirdPartyBeforeTheOperatorIsNotTheFirstLegalForm() {
        final String imprint = "Impressum\nWebdesign: Pixel Agentur GmbH, Am Hafen 3, 20457 Hamburg\nAngaben gemäß § 5 DDG\nLIVAID GmbH\n"
                + "Lindenallee 12\n10115 Berlin\nE-Mail: info@livaid.com\n";
        final Extraction ex = rules(imprint, "https://www.livaid.com/impressum");
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertEquals("LIVAID GmbH", op.legalName);
        assertEquals(List.of("Lindenallee 12, 10115 Berlin"), values(ex, op.ref, Vocabulary.ADDRESS));
        nothingOf(ex, "Pixel", "Hafen", "20457");
    }

    @Test
    public void unclearEvidenceLeavesTheOperatorUnnamed() {
        // the line breaks are gone: no name stands on its own line above the address
        final Extraction flat = rules(LIVAID.replace('\n', ' '), "https://www.livaid.com/impressum");
        assertNull(flat.mentions().toString(), flat.mention(RuleExtractor.OPERATOR_REF));
        nothingOf(flat, "Markel");
        // another domain does not confirm the name
        assertNull(rules(LIVAID, "https://www.example.de/impressum").mention(RuleExtractor.OPERATOR_REF));
        // a person is never an organisation, also on a domain of the same name
        final String person = "Impressum\nAngaben gemäß § 5 DDG\nErika Musterfrau\nHauptstraße 1\n10115 Berlin\nTelefon: 030 1234567\n";
        assertTrue(rules(person, "https://www.erika-musterfrau.de/impressum").mentions().isEmpty());
        assertTrue(rules(person.replace("Erika Musterfrau", "Inhaberin: Erika Musterfrau"), "https://www.musterfrau.de/impressum").mentions().isEmpty());
        assertTrue(rules(person.replace("Erika Musterfrau", "Frau Musterfrau"), "https://www.musterfrau.de/impressum").mentions().isEmpty());
        // the insurer's block alone names nobody
        final Extraction insurerOnly = rules("Impressum\nBerufshaftpflichtversicherung: Markel Insurance SE, Sophienstraße 26, 80333 München\n",
                "https://www.livaid.com/impressum");
        assertTrue(insurerOnly.mentions().toString(), insurerOnly.mentions().isEmpty());
    }

    @Test
    public void theStructuredOrganisationOfThePageConfirmsAName() {
        final Extraction ex = new Extraction(50);
        new JsonLdExtractor(200).extract(List.of("{\"@type\":\"Organization\",\"name\":\"Studio Linde\"}"), "https://www.example.de/impressum",
                "www.example.de", "de", ex);
        new RuleExtractor(200, 65536).extract(LIVAID.replace("\nLIVAID\n", "\nStudio Linde\n"), "https://www.example.de/impressum",
                "www.example.de", "de", ex);
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertNotNull(ex.mentions().toString(), op);
        assertEquals("Studio Linde", op.name);
        assertTrue(op.domainOperator);
        assertEquals(List.of("+49301234567"), values(ex, op.ref, Vocabulary.PHONE));
        nothingOf(ex, "Markel", "markel.de");
    }

    @Test
    public void sectionsOfOtherParties() {
        final String text = "Impressum\nA\nBerufshaftpflichtversicherung: B\nUSt-IdNr.: C\nZuständige Kammer: D\nKontakt: E\nWebdesign: F";
        final List<int[]> ranges = RuleExtractor.operatorRanges(text, 0, text.length());
        final StringBuilder kept = new StringBuilder();
        for (final int[] r : ranges) {
            kept.append(text, r[0], r[1]).append('|');
        }
        assertEquals("Impressum\nA\n|USt-IdNr.: C\n|Kontakt: E\n|", kept.toString());
        // a label of the other party's data does not end its section
        final String insurer = "Impressum\nA\nBerufshaftpflichtversicherung: B\nUSt-IdNr. des Versicherers: X\nRegistergericht: Amtsgericht Y";
        final StringBuilder ownParts = new StringBuilder();
        for (final int[] r : RuleExtractor.operatorRanges(insurer, 0, insurer.length())) {
            ownParts.append(insurer, r[0], r[1]).append('|');
        }
        assertEquals("Impressum\nA\n|Registergericht: Amtsgericht Y|", ownParts.toString());
        assertTrue(RuleExtractor.inThirdPartySection(text, text.indexOf("B")));
        assertFalse(RuleExtractor.inThirdPartySection(text, text.indexOf("C")));
        // names are no labels
        for (final String own : new String[] {"Muster Versicherung AG", "Handwerkskammer Berlin", "Bundesaufsichtsbehörde", "Design Studio GmbH"}) {
            final String t = "Impressum\n" + own + "\nHauptstraße 1";
            assertEquals(own, 1, RuleExtractor.operatorRanges(t, 0, t.length()).size());
            assertEquals(own, t.length(), RuleExtractor.operatorRanges(t, 0, t.length()).get(0)[1]);
        }
    }
}
