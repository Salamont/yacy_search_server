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
}
