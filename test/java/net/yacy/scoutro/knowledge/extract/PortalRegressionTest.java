/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.extract;

import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.allValues;
import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.care;
import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.facilities;
import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.facility;
import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.nothingOf;
import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.organisations;
import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.page;
import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.values;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * rule/12, the corrections after the validation of the facilities' first stage with the full production data (docs/SCOUTRO_KG_FACILITIES.md
 * section 12), on invented pages of the four portals' fields (no real organisation, person, address or phone number):
 * <ul>
 * <li>K1: after a link to the imprint the operator keeps the page's only address with its phone, mailbox and fax, also where no name
 * stands before them (1 195 fax, 635 mailbox and 525 phone statements of organisations rested on such footers);</li>
 * <li>F3: a business's service page, project page, article, team page or directions page is no facility's page, whatever its heading
 * (73 per cent of the construction collection's facility pages were none); a branch, an office or a practice named as such, or a
 * page of the site's places, is one; a care provider's homes stay facilities, its category page is none;</li>
 * <li>F4: a privacy notice names its controller only, never a newsletter, hosting or analytics provider; without a controller it
 * names nobody;</li>
 * <li>addresses: a town of two words with a known prefix ("Bad Rappenau", "St. Ingbert") is read whole, the sentence after a town
 * is none of it.</li>
 * </ul>
 */
public class PortalRegressionTest {

    private static Mention operator(final Extraction ex) {
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertNotNull(ex.mentions().toString(), op);
        return op;
    }

    private static void noSiteOperatorNamed(final Extraction ex, final String... names) {
        for (final Mention o : organisations(ex)) {
            for (final String n : names) {
                assertFalse(o.name + " is the site's operator", o.siteOperator && o.name != null && o.name.contains(n));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ K1: footers without a name

    @Test
    public void constructionAFooterWithoutTheNameKeepsTheOperatorsContactsAndFax() throws Exception {
        final Extraction ex = page("construction-footer-no-name.html", "https://www.muster-tiefbau.de/leistungen/", "construction");
        final Mention op = operator(ex);
        assertEquals("Muster Tiefbau GmbH", op.name);
        assertEquals(List.of("Baustraße 12, 45678 Bauhausen"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("45678"), values(ex, op, Vocabulary.POSTAL_CODE));
        assertEquals(List.of("Bauhausen"), values(ex, op, Vocabulary.LOCALITY));
        assertEquals(List.of("+492015555555"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("+492015555556"), values(ex, op, Vocabulary.FAX));
        assertEquals(List.of("info@muster-tiefbau.de"), values(ex, op, Vocabulary.EMAIL));
        assertNotNull("the address keys the operator", op.address);
        assertTrue("a services page names no facility: " + facilities(ex), facilities(ex).isEmpty());
    }

    @Test
    public void coachingAFooterWithoutTheNameKeepsTheOperatorsContactsAndNoPerson() throws Exception {
        final Extraction ex = page("coaching-profile.html", "https://www.beispiel-coaching.de/ueber-mich/", "coaching");
        final Mention op = operator(ex);
        assertEquals("Beispiel Coaching GmbH", op.name);
        assertEquals(List.of("Coachingweg 3, 20095 Hamburg"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+49401234567"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@beispiel-coaching.de"), values(ex, op, Vocabulary.EMAIL));
        assertTrue("a profile page names no facility: " + facilities(ex), facilities(ex).isEmpty());
        for (final Mention m : ex.mentions()) {
            nothingOf(ex, m, "Erika", "Beispiel,");
            assertFalse("no person: " + m.name, m.name != null && m.name.contains("Erika"));
        }
    }

    @Test
    public void careANewsPageNamingAHouseWithItsAddressGivesTheOperatorItsOwnFooterOnly() throws Exception {
        final Extraction ex = care("care-news-dated.html", "/2026/05/12/sommerfest-im-haus-lindenhof/");
        final Mention op = operator(ex);
        assertEquals("Muster Altenhilfe gGmbH", op.name);
        assertEquals(List.of("Trägerstraße 1, 10115 Musterstadt"), values(ex, op, Vocabulary.ADDRESS));
        nothingOf(ex, op, "Lindenweg", "12345");
        assertTrue("a dated article is no facility's page: " + facilities(ex), facilities(ex).isEmpty());
    }

    // ------------------------------------------------------------------------------------------------ F3: what is a facility's page

    @Test
    public void constructionAServicePageIsNoFacility() throws Exception {
        final Extraction ex = page("construction-service-page.html", "https://www.muster-rollladen.de/rollladen-und-raffstore/", "construction");
        assertTrue("'Rollladen und Raffstore' is a service, no facility: " + facilities(ex), facilities(ex).isEmpty());
        final Mention op = operator(ex);
        assertEquals("Muster Rollladenbau GmbH", op.name);
        assertEquals(List.of("Werkstraße 5, 45678 Bauhausen"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+492019999999"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("+492019999998"), values(ex, op, Vocabulary.FAX));
        assertEquals(List.of("info@muster-rollladen.de"), values(ex, op, Vocabulary.EMAIL));
    }

    @Test
    public void constructionAProjectPageIsNoFacility() throws Exception {
        final Extraction ex = page("construction-project-page.html", "https://www.muster-holzbau.de/2026/10/09/buergerhaus-musterstadt/", "construction");
        assertTrue("'Bürgerhaus Musterstadt' is the object of a project: " + facilities(ex), facilities(ex).isEmpty());
        final Mention op = operator(ex);
        assertEquals("Muster Holzbau GmbH", op.name);
        assertEquals(List.of("Schulstraße 37, 65795 Hattersheim am Main"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("Hattersheim am Main"), values(ex, op, Vocabulary.LOCALITY));
        final Extraction ex2 = page("construction-project-page.html", "https://www.muster-holzbau.de/projekte/buergerhaus-musterstadt/", "construction");
        assertTrue("a project's path: " + facilities(ex2), facilities(ex2).isEmpty());
    }

    @Test
    public void constructionABranchPageIsAFacilityWithItsOwnContacts() throws Exception {
        final Extraction ex = page("construction-branch-page.html", "https://www.musterbau-gruppe.de/standorte/niederlassung-nord/", "construction");
        final Mention f = facility(ex, "Niederlassung Nord");
        assertNotNull(facilities(ex) + " " + ex.mentions(), f);
        assertEquals(List.of("Nordstraße 1, 22222 Nordstadt"), values(ex, f, Vocabulary.ADDRESS));
        assertEquals(List.of("+49401234567"), values(ex, f, Vocabulary.PHONE));
        assertEquals(List.of("info-nord@musterbau-gruppe.de"), values(ex, f, Vocabulary.EMAIL));
        nothingOf(ex, f, "Baustraße", "45678", "+492016666666", "info@musterbau-gruppe.de");
        final Mention op = operator(ex);
        assertEquals("Muster Bau GmbH", op.name);
        assertEquals(List.of("Baustraße 9, 45678 Bauhausen"), values(ex, op, Vocabulary.ADDRESS));
        nothingOf(ex, op, "Nordstraße", "22222", "+49401234567", "info-nord@");
    }

    @Test
    public void softwareAnOfficePageOfTheSitesPlacesIsAFacilityNamedByItsTown() throws Exception {
        final Extraction ex = page("software-office-page.html", "https://www.beispiel-it.de/standorte/muenchen/", "software");
        final Mention f = facility(ex, "München");
        assertNotNull(facilities(ex) + " " + ex.mentions(), f);
        assertEquals(List.of("Softwarestraße 8, 80331 München"), values(ex, f, Vocabulary.ADDRESS));
        assertEquals(List.of("+49891234567"), values(ex, f, Vocabulary.PHONE));
        assertEquals(List.of("info.muenchen@beispiel-it.de"), values(ex, f, Vocabulary.EMAIL));
        nothingOf(ex, f, "Musterallee", "34117");
    }

    @Test
    public void softwareATeamPageAndADirectionsPageAreNoFacilities() throws Exception {
        final Extraction team = page("software-team-page.html", "https://www.beispiel-it.de/mitarbeiter/", "software");
        assertTrue("'Mitarbeiter': " + facilities(team), facilities(team).isEmpty());
        for (final Mention m : team.mentions()) {
            nothingOf(team, m, "Erika", "e.beispiel@");
        }
        final Extraction directions = page("software-directions-page.html", "https://www.beispiel-it.de/kontakt/anfahrt/", "software");
        assertTrue("'So finden Sie uns': " + facilities(directions), facilities(directions).isEmpty());
    }

    @Test
    public void coachingAPracticeNamedAsSuchIsAFacility() throws Exception {
        final Extraction ex = page("coaching-location.html", "https://www.beispiel-coaching.de/standorte/praxis-hamburg-altona/", "coaching");
        final Mention f = facility(ex, "Praxis Hamburg-Altona");
        assertNotNull(facilities(ex) + " " + ex.mentions(), f);
        assertEquals(List.of("Altonaer Straße 12, 22767 Hamburg"), values(ex, f, Vocabulary.ADDRESS));
        assertEquals(List.of("+49407654321"), values(ex, f, Vocabulary.PHONE));
        nothingOf(ex, f, "Coachingweg", "20095");
        final Mention op = operator(ex);
        assertEquals(List.of("Coachingweg 3, 20095 Hamburg"), values(ex, op, Vocabulary.ADDRESS));
        nothingOf(ex, op, "Altonaer", "22767");
    }

    @Test
    public void careAHomesPageStaysAFacilityAndACategoryPageIsNone() throws Exception {
        final Extraction home = care("care-facility-lindenhof.html", "/einrichtungen/haus-lindenhof/");
        final Mention f = facility(home, "Haus Lindenhof");
        assertNotNull(facilities(home) + " " + home.mentions(), f);
        assertEquals(List.of("Lindenweg 12, 12345 Musterstadt"), values(home, f, Vocabulary.ADDRESS));
        final Extraction category = care("care-category-page.html", "/angebote/soziotherapeutische-einrichtungen/");
        assertTrue("'Soziotherapeutische Einrichtungen': " + facilities(category), facilities(category).isEmpty());
        final Mention op = operator(category);
        assertEquals(List.of("Trägerstraße 1, 10115 Musterstadt"), values(category, op, Vocabulary.ADDRESS));
    }

    @Test
    public void theTradeOfAFacilitysPageIsItsOperatorsToo() throws Exception {
        final Extraction ex = care("care-facility-lindenhof.html", "/einrichtungen/haus-lindenhof/", FacilityPageTest.OPERATOR_LD);
        final Mention f = facility(ex, "Haus Lindenhof");
        assertNotNull(f);
        final List<String> trade = values(ex, f, Vocabulary.INDUSTRY);
        if (!trade.isEmpty()) {
            final Mention op = organisations(ex).get(0);
            assertEquals(trade, values(ex, op, Vocabulary.INDUSTRY));
        }
    }

    // ------------------------------------------------------------------------------------------------ F4: privacy notices

    @Test
    public void aPrivacyNoticeNamesItsControllerAndNoProcessor() throws Exception {
        final Extraction ex = page("org-privacy-processors.html", "https://www.muster-technik.de/datenschutz/", "software");
        final Mention op = operator(ex);
        assertEquals("Muster Technik GmbH", op.name);
        assertTrue(op.siteOperator);
        assertEquals(List.of("Technikweg 7, 12345 Musterstadt"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+49307777777"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@muster-technik.de"), values(ex, op, Vocabulary.EMAIL));
        nothingOf(ex, op, "Versandstraße", "Serverallee", "Parkway", "Beispiel Versand", "Beispiel Hosting");
        noSiteOperatorNamed(ex, "Beispiel Versand", "Beispiel Hosting", "Beispiel Analytics");
        assertTrue(facilities(ex).isEmpty());
    }

    @Test
    public void aPrivacyNoticeWithoutAControllerNamesNobody() throws Exception {
        final Extraction ex = page("org-privacy-link-only.html", "https://www.muster-schreinerei.de/datenschutz/", "construction");
        assertNull("the link to the imprint names no operator on a privacy notice: " + ex.mentions(), ex.mention(RuleExtractor.OPERATOR_REF));
        noSiteOperatorNamed(ex, "Beispiel Versand", "Händlerbund");
        for (final Mention m : ex.mentions()) {
            nothingOf(ex, m, "Versandstraße", "Händlerweg");
        }
    }

    // ------------------------------------------------------------------------------------------------ addresses: towns

    @Test
    public void aTownOfTwoWordsIsReadWholeAndTheSentenceAfterItIsNone() throws Exception {
        final Extraction ex = page("org-imprint-towns.html", "https://www.muster-kurbad.de/impressum/", "care");
        final Mention op = operator(ex);
        assertEquals("Muster Kurbad GmbH", op.name);
        assertEquals(List.of("Bergstraße 24, 74906 Bad Rappenau"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("Bad Rappenau"), values(ex, op, Vocabulary.LOCALITY));
        assertEquals(allValues(ex, op).toString(), List.of("+4972642055552"), values(ex, op, Vocabulary.PHONE));
    }

    @Test
    public void townsOfTheRulesAndOfThePlaceReader() {
        final String[][] cases = {
                {"Muster GmbH. Bergstraße 24. 74906 Bad Rappenau. Diese Seite wird betrieben von uns.", "Bad Rappenau"},
                {"Muster GmbH. Pfarrgasse 57. 66386 St. Ingbert. Telefon 06894 1234", "St. Ingbert"},
                {"Muster GmbH. Stubenstraße 4. 37308 Heilbad Heiligenstadt", "Heilbad Heiligenstadt"},
                {"Muster GmbH. Musterweg 1. 60311 Frankfurt am Main. Telefon 069 1234", "Frankfurt am Main"},
                {"Muster GmbH. Musterweg 1. 06108 Halle (Saale). Unsere Öffnungszeiten", "Halle (Saale)"},
                {"Muster GmbH. Musterweg 1. 51429 Bergisch Gladbach. Unsere Öffnungszeiten", "Bergisch Gladbach"},
                {"Muster GmbH. Musterweg 1. 32805 Horn-Bad Meinberg. Unsere Öffnungszeiten", "Horn-Bad Meinberg"},
                {"Muster GmbH. Musterweg 1. 12345 Musterstadt. Unsere Öffnungszeiten: Mo bis Fr", "Musterstadt"},
                {"Muster GmbH. Musterweg 1. 12345 Musterstadt Telefon 030 1234", "Musterstadt"},
                {"Muster GmbH. Musterweg 1. 14770 Brandenburg an der Havel. Diese Seite", "Brandenburg an der Havel"}};
        for (final String[] c : cases) {
            final Extraction ex = new Extraction(50);
            new RuleExtractor(200, 65536).extract("Impressum. Angaben gemäß § 5 TMG. " + c[0], "https://www.muster.de/impressum/", "www.muster.de", "de", ex);
            final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
            assertNotNull(c[0] + " " + ex.mentions(), op);
            assertEquals(c[0], List.of(c[1]), values(ex, op, Vocabulary.LOCALITY));
            final PlaceEvidence.Place p = PlaceEvidence.fullPlace("Haus Sonne. " + c[0].substring("Muster GmbH. ".length()), "Haus Sonne");
            assertNotNull(c[0], p);
            assertEquals(c[0], c[1], p.address.locality);
        }
    }

    @Test
    public void aLegalFormIsNoPartOfAStreet() {
        final Extraction ex = new Extraction(50);
        new RuleExtractor(200, 65536).extract("Impressum. Angaben gemäß § 5 TMG. Muster Haus GmbH\nGmbH Musterweg 5\n12345 Musterstadt",
                "https://www.muster.de/impressum/", "www.muster.de", "de", ex);
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertNotNull(ex.mentions().toString(), op);
        assertEquals(List.of("Musterweg 5, 12345 Musterstadt"), values(ex, op, Vocabulary.ADDRESS));
    }

    // ------------------------------------------------------------------------------------------------ operator identity (request 10)

    /** A booking tool's notice ("Anbieter ist die … GmbH, …") on a normal page names no operator; the copyright line's company is it. */
    @Test
    public void softwareAServiceProvidersNoticeOnAPageNamesNoOperator() throws Exception {
        final Extraction ex = page("software-booking-notice.html", "https://www.beispiel-digital.de/leistungen/app-entwicklung/", "software");
        final Mention op = operator(ex);
        assertEquals("Beispiel Digital GmbH", op.name);
        assertEquals(List.of("+493812100000"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("mail@beispiel-digital.de"), values(ex, op, Vocabulary.EMAIL));
        nothingOf(ex, op, "Beispielallee", "Berlin", "Terminwerk");
        noSiteOperatorNamed(ex, "Terminwerk");
    }

    /** A privacy notice without a controller's marker: the site's own company heading the footer's contact block is the operator. */
    @Test
    public void aPrivacyNoticeNamesTheSitesOwnCompanyOfItsContactBlock() throws Exception {
        final Extraction ex = page("org-privacy-brand-contact.html", "https://www.loga-beispiel.de/datenschutz/", "construction");
        final Mention op = operator(ex);
        assertEquals("Loga Beispiel Bau GmbH", op.name);
        assertEquals(List.of("Hansaweg 10-12, 68169 Beispielstadt"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+496214963200"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("+496214963201"), values(ex, op, Vocabulary.FAX));
        assertEquals(List.of("info@loga-beispiel.de"), values(ex, op, Vocabulary.EMAIL));
        nothingOf(ex, op, "Serverstraße", "Beispielhausen", "Dublin", "Hostwerk");
        noSiteOperatorNamed(ex, "Hostwerk", "Analytics");
    }

    /** "Verantwortlicher im Sinne der Datenschutzgesetze … ist:" opens the controller's block of a privacy notice. */
    @Test
    public void aPrivacyNoticesControllerSentenceNamesTheController() throws Exception {
        final Extraction ex = page("org-privacy-controller-sentence.html", "https://www.advis-beispiel.de/datenschutzerklaerung.html", "software");
        final Mention op = operator(ex);
        assertEquals("ADVIS Beratung GmbH", op.name);
        assertEquals(List.of("Oberer Weg 64, 97980 Bad Beispielheim"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("Bad Beispielheim"), values(ex, op, Vocabulary.LOCALITY));
        assertEquals(List.of("+4979319230000"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("+4979319230001"), values(ex, op, Vocabulary.FAX));
        assertEquals(List.of("info@advis-beispiel.de"), values(ex, op, Vocabulary.EMAIL));
        noSiteOperatorNamed(ex, "Analytics");
    }

    /** A combined page ("/impressum-datenschutz/") is an imprint: its identifiers and contacts are the operator's, the processors are none. */
    @Test
    public void aCombinedImprintAndPrivacyPageKeepsTheOperatorsIdentifiers() throws Exception {
        final Extraction ex = page("org-imprint-privacy-combined.html", "https://www.muster-dach.de/impressum-datenschutz/", "construction");
        final Mention op = operator(ex);
        assertEquals("Muster Dach GmbH", op.name);
        assertEquals(List.of("Dachstraße 7, 44787 Bochum"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+492341234567"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@muster-dach.de"), values(ex, op, Vocabulary.EMAIL));
        assertEquals("DE123456789", op.strongKeys.get(Vocabulary.VAT));
        assertNotNull(allValues(ex, op).toString(), op.strongKeys.get(Vocabulary.REGISTER));
        nothingOf(ex, op, "Versandstraße", "Wellington", "Beispiel Versand", "Beispiel Craft");
        noSiteOperatorNamed(ex, "Beispiel Versand", "Beispiel Craft");
    }

    /** An imprint whose operator writes its name in lower case ("uib-beispiel GmbH"): that name, not the hosting provider's after it. */
    @Test
    public void softwareAnImprintWithALowerCaseBrandNamesIt() throws Exception {
        final Extraction ex = page("software-imprint-lowercase-brand.html", "https://uib-beispiel.de/de/imprint/", "software");
        final Mention op = operator(ex);
        assertEquals("uib-beispiel GmbH", op.name);
        assertEquals(List.of("Bonifatiusplatz 1B, 55118 Mainz"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+4961312750000"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@uib-beispiel.de"), values(ex, op, Vocabulary.EMAIL));
        assertEquals("DE123456789", op.strongKeys.get(Vocabulary.VAT));
        nothingOf(ex, op, "Industriestr", "Beispielhausen", "Hetzwerk");
        noSiteOperatorNamed(ex, "Hetzwerk");
    }

    /** A footer in YaCy's text: a label in its own element ("Tel.:. 0441 …"), a dash in the number, the number right after "Str.". */
    @Test
    public void constructionAFootersFormatsOfLabelsNumbersAndStreetsAreRead() throws Exception {
        final Extraction ex = page("construction-footer-formats.html", "https://www.beispiel-zimmerei.de/leistungen/holzbau/", "construction");
        final Mention op = operator(ex);
        assertEquals("Beispiel Zimmerei GmbH", op.name);
        assertEquals(List.of("Ofener Str. 30, 26121 Oldenburg"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+494412197300"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@beispiel-zimmerei.de"), values(ex, op, Vocabulary.EMAIL));
    }

    /** Two offices in one block: the name's own part ends before the second address; the page's only mailbox is the operator's. */
    @Test
    public void constructionTwoOfficesInOneBlockGiveTheOperatorTheFirstAndTheOnlyMailbox() throws Exception {
        final Extraction ex = page("construction-two-offices.html", "https://www.trubel-beispiel.de/", "construction");
        final Mention op = operator(ex);
        assertTrue(op.name, op.name.endsWith("Trubel Beispiel GmbH"));
        assertEquals(List.of("Lindenallee 9, 36179 Bebra"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("Bebra"), values(ex, op, Vocabulary.LOCALITY));
        assertEquals(List.of("+4966229270000"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@trubel-beispiel.de"), values(ex, op, Vocabulary.EMAIL));
        nothingOf(ex, op, "Wollweberstr", "Bad Hersfeld", "5030000");
    }

    /** A partner page: the partners' contact blocks come first, the operator's name is the site's and stands in the footer. */
    @Test
    public void constructionAPartnerPageNamesTheSiteNotAPartner() throws Exception {
        final Extraction ex = page("construction-partner-page.html", "https://saalfeld-beispiel.de/partnerunternehmen/", "construction");
        final Mention op = operator(ex);
        assertEquals("Saalfeld Beispiel e.K.", op.name);
        assertEquals(List.of("Hauptstraße 84, 82140 Olching"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+49814223670"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@saalfeld-beispiel.de"), values(ex, op, Vocabulary.EMAIL));
        nothingOf(ex, op, "Remscheid", "Karlsfeld", "Markt Schwaben", "vailant", "gittfried");
        noSiteOperatorNamed(ex, "Vailant", "Gienger", "Gittfried");
    }

    /** A list of services with their contacts: none of them is the operator; the association the copyright line names is, without the quote. */
    @Test
    public void careAListOfServicesNamesTheAssociationOfTheCopyrightLineOnly() throws Exception {
        final Extraction ex = page("care-directory-list.html", "https://www.kinderhilfe-beispiel.de/medizinische-hilfe/", "care");
        final Mention op = operator(ex);
        assertEquals("Deutsche Kinderhilfe Beispiel e.V.", op.name);
        nothingOf(ex, op, "Unna", "Seminarstraße", "Alexanderstraße", "Viktoriastraße", "assistenz-beispiel", "sozialamt@");
        noSiteOperatorNamed(ex, "Assistenz im Alltag", "Universitätskinderklinik", "Beispiel Karree");
    }

    /** A footer's own small imprint names persons only: the company of the page's contact box, which the copyright line vouches for, is the operator. */
    @Test
    public void constructionAFootersImprintOfPersonsStillNamesTheCompanyOfTheContactBox() throws Exception {
        final Extraction ex = page("construction-footer-mini-imprint.html", "https://glaser-beispiel.de/", "construction");
        final Mention op = operator(ex);
        assertEquals("Hugo Beispiel Glas GmbH & Co. KG", op.name);
        assertEquals(List.of("Beispielstraße 12, 10405 Berlin"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+493044000900"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@glaser-beispiel.de"), values(ex, op, Vocabulary.EMAIL));
    }

    // ------------------------------------------------------------------------------------------------ names and the site's own name

    private static String operatorOf(final String text, final String url) {
        final Extraction ex = new Extraction(50);
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex);
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        return op == null ? null : op.name;
    }

    @Test
    public void namesStartAfterYearsQuotesAndMarksAndKeepTheirJoinedParts() {
        final String imprint = "Impressum. Angaben gemäß § 5 TMG. ";
        assertEquals("Kirchliche Sozialstation Beispielstadt e.V.",
                operatorOf(imprint + "50 Jahre Kirchliche Sozialstation Beispielstadt e.V.\nMusterweg 1\n12345 Musterstadt", "https://www.sozialstation-beispielstadt.de/impressum/"));
        assertEquals("Michael Beispiel Putz-, Stuck-, Dämmtechnik GmbH",
                operatorOf(imprint + "Die Michael Beispiel Putz-, Stuck-, Dämmtechnik GmbH\nMusterweg 1\n12345 Musterstadt", "https://www.mb-putz.de/impressum/"));
        assertEquals("Deutsche Kinderhilfe Beispiel e.V.",
                operatorOf(imprint + "Träger: „Deutsche Kinderhilfe Beispiel e.V.“\nMusterweg 1\n12345 Musterstadt", "https://www.kinderhilfe-beispiel.de/impressum/"));
        assertEquals("Hermann von Beispiel GmbH",
                operatorOf(imprint + "Hermann von Beispiel GmbH\nMusterweg 1\n12345 Musterstadt", "https://vonbeispiel.de/impressum/"));
        assertEquals("Beispiel GmbH",
                operatorOf(imprint + "Betrieben von Beispiel GmbH\nMusterweg 1\n12345 Musterstadt", "https://www.beispiel.de/impressum/"));
        assertEquals("Fleischmann Beispiel GmbH & Co. KG",
                operatorOf(imprint + "|  Fleischmann Beispiel GmbH & Co. KG\nMusterweg 1\n12345 Musterstadt", "https://www.fleischmann-beispiel.de/impressum/"));
    }

    @Test
    public void theSitesOwnName() {
        assertTrue(RuleExtractor.brandName("Bügler Haustechnik GmbH & Co. KG", "www.buegler.com"));
        assertTrue(RuleExtractor.brandName("Hoog & Sohn GmbH", "www.hoog-sohn.de"));
        assertTrue(RuleExtractor.brandName("Saalfeld Beispiel e.K.", "saalfeld-olching.de"));
        assertTrue(RuleExtractor.brandName("J.F. Trubel GmbH", "www.trubel-bebra.de"));
        assertTrue(RuleExtractor.brandName("Maschinen- und Metallbau Claaßen GmbH", "www.claassen-maschinenbau.de"));
        assertTrue(RuleExtractor.brandName("Pro Human Häusliche Pflege GmbH", "www.prohuman24.de"));
        assertFalse("a short start of a longer word", RuleExtractor.brandName("Sozial-Hilfsverein Dühren e.V.", "sozialstation-sinsheim.de"));
        assertFalse(RuleExtractor.brandName("Deutsche Kinderhilfe e.V.", "www.deutsche-datenschutzkanzlei.de"));
        assertFalse(RuleExtractor.brandName("Hetzner Online GmbH", "uib.de"));
        assertTrue(RuleExtractor.brandWord("uib", "uib.de"));
        assertTrue(RuleExtractor.brandWord("bks", "www.bks-finanz.de"));
        assertFalse(RuleExtractor.brandWord("die", "www.muster.de"));
        assertFalse(RuleExtractor.brandWord("von", "www.beispiel.de"));
    }

    @Test
    public void aTownAbbreviatedWithPrIsReadWhole() {
        final Extraction ex = new Extraction(50);
        new RuleExtractor(200, 65536).extract("Impressum. Angaben gemäß § 5 TMG. Muster Maler e.K.\nAlter Markt 1\n32361 Pr. Oldendorf\nTel. 05742 55710",
                "https://www.muster-maler.de/impressum/", "www.muster-maler.de", "de", ex);
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertNotNull(ex.mentions().toString(), op);
        assertEquals(List.of("Alter Markt 1, 32361 Pr. Oldendorf"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("Pr. Oldendorf"), values(ex, op, Vocabulary.LOCALITY));
    }

    // ------------------------------------------------------------------------------------------------ third round (after the second full run)

    /** A privacy notice whose controller has no legal form: the hosting provider after the next heading is not the operator. */
    @Test
    public void aPrivacyNoticesControllerWithoutALegalFormLeavesTheHostingProviderNobody() throws Exception {
        final Extraction ex = page("org-privacy-controller-no-legal-form.html", "https://gb-hessen.de/datenschutz.html", "construction");
        noSiteOperatorNamed(ex, "Hetzwerk");
        for (final Mention m : ex.mentions()) {
            nothingOf(ex, m, "Industriestr", "Beispielhausen", "Hetzwerk");
        }
    }

    /** The controller's block ends with the next heading; the external data protection officer's block names no operator. */
    @Test
    public void softwareAPrivacyNoticeNamesTheControllerNotTheDataProtectionOfficer() throws Exception {
        final Extraction ex = page("software-privacy-dpo-section.html", "https://x-beispiel.com/datenschutzerklaerung", "software");
        final Mention op = operator(ex);
        assertEquals("x-beispiel technologies GmbH", op.name);
        assertEquals(List.of("Rosenkavalierplatz 10, 81925 München"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+49899292740"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("datenschutz@x-beispiel.com"), values(ex, op, Vocabulary.EMAIL));
        nothingOf(ex, op, "Sandstraße", "DataSchutz", "452459");
        noSiteOperatorNamed(ex, "DataSchutz");
    }

    /** A company whose name ends with an exclamation mark keeps it; its imprint's identifiers and contacts are its own. */
    @Test
    public void softwareAnImprintOfANameWithAnExclamationMark() throws Exception {
        final Extraction ex = page("software-imprint-exclamation-name.html", "https://www.wauu.de/impressum", "software");
        final Mention op = operator(ex);
        assertEquals("WAUU! GmbH", op.name);
        assertEquals(List.of("Lotter Straße 47 – 48, 49078 Osnabrück"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+4954133034100"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@wauu.de"), values(ex, op, Vocabulary.EMAIL));
        assertEquals("DE123456789", op.strongKeys.get(Vocabulary.VAT));
        assertNotNull(allValues(ex, op).toString(), op.strongKeys.get(Vocabulary.REGISTER));
    }

    /** "Eingesetzte Dienste und Diensteanbieter" of a privacy notice is no imprint's marker; the site's own company is the operator. */
    @Test
    public void softwareAPrivacyNoticesListOfProvidersNamesNoOperator() throws Exception {
        final Extraction ex = page("software-privacy-providers-list.html", "https://ra-beispiel-koeln.de/datenschutzinformation/", "software");
        final Mention op = operator(ex);
        assertEquals("RA-BEISPIEL Vertriebs GmbH", op.name);
        assertEquals(List.of("Auf der Kaule 23, 51427 Bergisch Gladbach"), values(ex, op, Vocabulary.ADDRESS));
        nothingOf(ex, op, "Bahnhofstraße", "Eppelborn", "HostBeispiel");
        noSiteOperatorNamed(ex, "HostBeispiel");
    }

    /** Of several offices the one headed "Hauptsitz" is the operator's; addresses in list items, numbers with brackets. */
    @Test
    public void constructionTheHeadOfficeOfSeveralOfficesIsTheOperators() throws Exception {
        final Extraction ex = page("construction-head-office.html", "https://safety-beispiel.de/", "construction");
        final Mention op = operator(ex);
        assertEquals("Safety Beispiel GmbH", op.name);
        assertEquals(List.of("Gewerbeweg 5, 03044 Cottbus"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+49355431220"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("+493554312220"), values(ex, op, Vocabulary.FAX));
        assertEquals(List.of("info@safety-beispiel.de"), values(ex, op, Vocabulary.EMAIL));
        nothingOf(ex, op, "Forster Landstraße", "Spremberg", "608180", "608186");
    }

    /** The page's structured operator gets the phone, fax and mailbox of the "Kontakt" block after its address block. */
    @Test
    public void constructionTheStructuredSubjectGetsTheContactBlockAfterItsAddress() throws Exception {
        final String jsonld = "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"@id\":\"https://www.beispiel-gmbh.de/#organization\","
                + "\"name\":\"Anton Beispiel GmbH\",\"url\":\"https://www.beispiel-gmbh.de\"}";
        final Extraction ex = page("construction-subject-contact-block.html", "https://www.beispiel-gmbh.de/elektro", "construction", jsonld);
        Mention subject = null;
        for (final Mention m : organisations(ex)) {
            if ("Anton Beispiel GmbH".equals(m.name) && m.tier == 1) {
                subject = m;
            }
        }
        assertNotNull(ex.mentions().toString(), subject);
        assertEquals(List.of("Ziepelkamp 25, 26901 Lorup"), values(ex, subject, Vocabulary.ADDRESS));
        assertEquals(List.of("+495954444"), values(ex, subject, Vocabulary.PHONE));
        assertEquals(List.of("+495954497"), values(ex, subject, Vocabulary.FAX));
        assertEquals(List.of("info@beispiel-gmbh.de"), values(ex, subject, Vocabulary.EMAIL));
    }

    /** A mobile number is the operator's phone where the page gives no other; a personal mailbox stays none of its. */
    @Test
    public void coachingAMobileNumberIsThePhoneWhereThereIsNoOther() throws Exception {
        final Extraction ex = page("coaching-mobile-number.html", "https://muster-coaching.de/", "coaching");
        final Mention op = operator(ex);
        assertEquals("Muster Coaching GmbH", op.name);
        assertEquals(List.of("Am Müllerwald 27, 55120 Mainz"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+491733819635"), values(ex, op, Vocabulary.PHONE));
        assertTrue(values(ex, op, Vocabulary.EMAIL).toString(), values(ex, op, Vocabulary.EMAIL).isEmpty());
    }

    @Test
    public void namesKeepTheSitesOwnWordsAndStopAtOtherWords() {
        final String imprint = "Impressum. Angaben gemäß § 5 TMG. ";
        assertEquals("x-beispiel technologies GmbH",
                operatorOf(imprint + "x-beispiel technologies GmbH\nMusterweg 1\n12345 Musterstadt", "https://x-beispiel.com/impressum"));
        assertEquals("medinix information systems GmbH",
                operatorOf(imprint + "medinix information systems GmbH\nMusterweg 1\n12345 Musterstadt", "https://medinix.com/impressum/"));
        assertEquals("Gofresh GmbH",
                operatorOf(imprint + "Gofresh and Couchplay are registered trademarks of Gofresh GmbH\nMusterweg 1\n12345 Musterstadt", "https://www.couchplay.tv/privacy.php"));
        assertEquals("Stefan Muster", operatorOfDeclared(imprint + "Stefan Muster\nMusterweg 1\n12345 Musterstadt\nUSt-IdNr.: DE123456789",
                "https://sanitaer-muster.de/impressum/", "Stefan Muster"));
    }

    // ------------------------------------------------------------------------------------ 12.11 (request 11): identifiers behind the block

    /** A privacy notice whose controller follows "gilt für die Datenverarbeitung durch:" keeps its register entry and VAT ID. */
    @Test
    public void constructionAPrivacyArticleNamesItsControllerWithRegisterAndVat() throws Exception {
        final Extraction ex = page("construction-privacy-article.html",
                "https://www.muster-hausservice.de/index.php?option=com_content&view=article&id=3&Itemid=5", "construction");
        final Mention op = operator(ex);
        assertEquals("Muster Hausservice GmbH", op.name);
        assertEquals(List.of("Finkenweg 9, 14656 Brieselang"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+49301234560"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@muster-hausservice.de"), values(ex, op, Vocabulary.EMAIL));
        assertEquals(List.of("DE123456789"), values(ex, op, Vocabulary.ID_VAT));
        assertEquals(List.of("HRB 12345 B, Amtsgericht Charlottenburg"), values(ex, op, Vocabulary.ID_REGISTER));
        noSiteOperatorNamed(ex, "Wolkenwerk");
    }

    /** The misspelling "Impresum" (path and title) is an imprint: the whole window gives the operator its identifiers. */
    @Test
    public void constructionAMisspeltImprintIsAnImprintWithRegisterAndVat() throws Exception {
        final Extraction ex = page("construction-imprint-misspelt.html", "https://www.muster-bau-adelsdorf.de/kontakt/impresum", "construction");
        final Mention op = operator(ex);
        assertEquals("Muster Bau GmbH", op.name);
        assertEquals(List.of("Industriestraße 21, 91325 Adelsdorf"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+4991951234"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("+4991955678"), values(ex, op, Vocabulary.FAX));
        assertEquals(List.of("HRB 3111, Amtsgericht Fürth"), values(ex, op, Vocabulary.ID_REGISTER));
        assertEquals(List.of("DE111222333"), values(ex, op, Vocabulary.ID_VAT));
        noSiteOperatorNamed(ex, "Wolkenwerk");
    }

    /** An imprint inside a privacy notice with sub-headings of its own data ("## Vertreten durch …", "## Kontakt:") is read whole. */
    @Test
    public void constructionAPrivacyPageWithSubheadingsKeepsTheControllersContactsAndIdentifiers() throws Exception {
        final Extraction ex = page("construction-privacy-subheadings.html", "https://www.muster-haustechnik.de/privacy-policy", "construction");
        final Mention op = operator(ex);
        assertEquals("Muster Haustechnik GmbH", op.name);
        assertEquals(List.of("Hauptstraße 11 d, 85716 Unterschleißheim"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals("the landline, not the mobile number", List.of("+49891234567"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@muster-haustechnik.de"), values(ex, op, Vocabulary.EMAIL));
        assertEquals(List.of("HRB 218780, Amtsgericht München"), values(ex, op, Vocabulary.ID_REGISTER));
        assertEquals(List.of("DE234565602"), values(ex, op, Vocabulary.ID_VAT));
        noSiteOperatorNamed(ex, "Wolkenwerk");
    }

    /** A privacy notice without the controller's marker: the operator's footer block, then the lines of its identifiers after it. */
    @Test
    public void softwareAPrivacyPageWithoutTheControllersMarkerGivesTheOperatorTheIdentifiersAfterItsBlock() throws Exception {
        final Extraction ex = page("software-footer-identifiers.html", "https://www.muster-it.de/dsgvo.php", "software");
        final Mention op = operator(ex);
        assertEquals("Muster IT GmbH", op.name);
        assertEquals(List.of("Am Brünnele 2a, 89291 Holzheim"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+49730212340"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("+497302123455"), values(ex, op, Vocabulary.FAX));
        assertEquals(List.of("post@muster-it.de"), values(ex, op, Vocabulary.EMAIL));
        assertEquals(List.of("DE184191578"), values(ex, op, Vocabulary.ID_VAT));
        noSiteOperatorNamed(ex, "Wolkenwerk");
    }

    /** An English legal notice of a group under a numeric address: the operator's block and its identifiers, not the next company's. */
    @Test
    public void constructionAnEnglishLegalNoticeOfTwoCompaniesGivesTheOperatorItsOwnVatOnly() throws Exception {
        final Extraction ex = page("construction-legal-notice-two-companies.html", "https://www.muster-metall-gruppe.com/?page_id=621&lang=en", "construction");
        final Mention op = operator(ex);
        assertEquals("Muster Metallwerke GmbH", op.name);
        assertEquals(List.of("Am Römerkastell 6, 66121 Saarbrücken"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("DE138112082"), values(ex, op, Vocabulary.ID_VAT));
        for (final Mention o : organisations(ex)) {
            assertFalse("the French company's VAT ID is nobody else's: " + values(ex, o, Vocabulary.ID_VAT),
                    values(ex, o, Vocabulary.ID_VAT).contains("FR70353143522"));
        }
    }

    /** The general partner named after the footer's block is another company: its register entry is not the operator's. */
    @Test
    public void softwareAFooterNamingTheGeneralPartnerGivesTheOperatorNoRegisterOfAnotherCompany() throws Exception {
        final Extraction ex = page("software-footer-general-partner.html", "https://www.muster-dienste.de/leistungen/", "software");
        final Mention op = operator(ex);
        assertEquals("Muster Dienste GmbH & Co. KG", op.name);
        assertEquals(List.of("Falkenweg 31, 50935 Köln"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+492211234567"), values(ex, op, Vocabulary.PHONE));
        assertTrue("the general partner's register entry is not the operator's: " + values(ex, op, Vocabulary.ID_REGISTER),
                values(ex, op, Vocabulary.ID_REGISTER).isEmpty());
    }

    /** A controller written before its inline address is named without the comma. */
    @Test
    public void organisationAControllerBeforeItsInlineAddressIsNamedWithoutTheComma() throws Exception {
        final Extraction ex = page("org-privacy-controller-inline-address.html", "https://www.glaserei-muster.de/datenschutzerklaerung/", "construction");
        final Mention op = operator(ex);
        assertEquals("Glaserei Muster", op.name);
        assertEquals(List.of("Im Felde 12, 52249 Eschweiler"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("info@glaserei-muster.de"), values(ex, op, Vocabulary.EMAIL));
        assertEquals(List.of("+492403961396"), values(ex, op, Vocabulary.PHONE));
    }

    /** "mit" joins the parts of a name; "bei" still opens a phrase before one; punctuation after a name is none of it. */
    @Test
    public void namesKeepMitInsideAndLoseThePunctuationAfterThem() {
        final String imprint = "Impressum. Angaben gemäß § 5 TMG. ";
        assertEquals("Unternehmensgruppe Muster Häusliche Pflege mit Stil GmbH", operatorOf(imprint
                + "Unternehmensgruppe Muster Häusliche Pflege mit Stil GmbH\nMusterweg 1\n12345 Musterstadt", "https://seniorenzentrum-beispiel.de/impressum/"));
        assertEquals("Menschen mit Behinderung Beispielstadt e.V.", operatorOf(imprint
                + "Menschen mit Behinderung Beispielstadt e.V.\nMusterweg 1\n12345 Musterstadt", "https://www.mmb-beispielstadt.de/impressum/"));
        assertEquals("Muster GmbH", operatorOf(imprint + "Willkommen bei Muster GmbH\nMusterweg 1\n12345 Musterstadt", "https://www.muster.de/impressum/"));
        assertEquals("Glaserei Muster", RuleExtractor.trimName("Glaserei Muster,"));
        assertEquals("Muster-Bau", RuleExtractor.trimName("**Muster-Bau -"));
        assertEquals("Muster e.V.", RuleExtractor.trimName(" Muster e.V. "));
    }

    /**
     * An imprint's dispute resolution passage names nobody, the operator's plain name is read instead; a body that runs its own site
     * ("Schlichtungsstelle Bau e.V." as the imprint's first organisation) stays its operator (`ImprintOperatorTest`).
     */
    @Test
    public void anImprintsDisputeResolutionBodyIsNobody() {
        final String imprint = "Impressum. Angaben gemäß § 5 TMG. ";
        assertEquals("PLANBOX Beispiel", operatorOf(imprint + "PLANBOX Beispiel\nMusterweg 1\n12345 Musterstadt\nTelefon: 0123 456789\n\n"
                + "Streitbeilegung. Wir nehmen an einem Streitbeilegungsverfahren vor einer Verbraucherschlichtungsstelle teil. Zuständig ist die "
                + "Universalschlichtungsstelle des Zentrums für Schlichtung e.V., Straßburger Straße 8, 77694 Kehl am Rhein.", "https://planbox-beispiel.de/impressum/"));
        assertEquals("Muster Elektro GmbH", operatorOf(imprint + "Muster Elektro GmbH\nMusterweg 1\n12345 Musterstadt\nTelefon: 0123 456789\n\n"
                + "Streitschlichtung. Die Europäische Kommission stellt eine Plattform zur Online-Streitbeilegung (OS) bereit. Wir sind nicht bereit, an "
                + "Streitbeilegungsverfahren vor einer Verbraucherschlichtungsstelle teilzunehmen. Allgemeine Verbraucherschlichtungsstelle des Zentrums für "
                + "Schlichtung e.V., Straßburger Str. 8, 77694 Kehl, mail@verbraucher-beispiel.de", "https://www.muster-elektro.de/impressum/"));
    }

    /** The operator of a text whose structured data declare one organisation of the given name. */
    private static String operatorOfDeclared(final String text, final String url, final String declared) {
        final Extraction ex = new Extraction(50);
        final Mention s = ex.add(new Mention("jsonld:0", Vocabulary.ORGANIZATION, 1));
        s.name = declared;
        new RuleExtractor(200, 65536).extract(text, url, java.net.URI.create(url).getHost(), "de", ex);
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        return op == null ? null : op.name;
    }
}
