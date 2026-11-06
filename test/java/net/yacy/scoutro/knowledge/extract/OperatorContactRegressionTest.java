/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.extract;

import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.care;
import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.facilities;
import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.nothingOf;
import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.page;
import static net.yacy.scoutro.knowledge.extract.FacilityPageTest.values;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * K1 of the validation of the facilities' first stage on the production graph (docs/SCOUTRO_KG_FACILITIES.md): 22 to 25 per cent
 * of the organisations' addresses, phones and mailboxes (up to 40 per cent in the care collection) rested only on the rules' reading
 * of a page that links to its imprint, mostly home, about, contact and other pages of a site with one location and its operator's
 * footer. rule/11 had made such a link name the operator without any contact. These invented pages (no real organisation) check
 * that the operator keeps the block its name opens there, and that it still gets none of the places the page describes:
 * <ul>
 * <li>a footer written in blocks, a footer with line breaks and its register and VAT ID, a footer in one line;</li>
 * <li>a contact page with its headquarters and two branches (the operator gets the headquarters of its footer only);</li>
 * <li>a news page about a house the operator runs ("ist Träger des neuen Hauses …").</li>
 * </ul>
 */
public class OperatorContactRegressionTest {

    private static Mention operator(final Extraction ex) {
        final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
        assertNotNull(ex.mentions().toString(), op);
        return op;
    }

    @Test
    public void aFooterInBlocksKeepsTheOperatorsContacts() throws Exception {
        final Extraction ex = page("org-home-footer-blocks.html", "https://www.muster-elektro.de/", "construction");
        final Mention op = operator(ex);
        assertEquals("Muster Elektro GmbH", op.name);
        assertEquals(List.of("Elektroweg 4, 45678 Bauhausen"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("45678"), values(ex, op, Vocabulary.POSTAL_CODE));
        assertEquals(List.of("Bauhausen"), values(ex, op, Vocabulary.LOCALITY));
        assertEquals(List.of("+492017777777"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@muster-elektro.de"), values(ex, op, Vocabulary.EMAIL));
        assertNotNull("the full address keys the operator as before", op.address);
        assertTrue(facilities(ex).isEmpty());
    }

    @Test
    public void aFooterWithBreaksKeepsTheOperatorsContactsRegisterAndVatId() throws Exception {
        final Extraction ex = page("org-about-footer-breaks.html", "https://www.muster-pflegedienst.de/ueber-uns/", "care");
        final Mention op = operator(ex);
        assertEquals("Muster Pflegedienst GmbH", op.name);
        assertEquals(List.of("Pflegeweg 2, 12345 Musterstadt"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+49304444444"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("kontakt@muster-pflegedienst.de"), values(ex, op, Vocabulary.EMAIL));
        assertEquals(List.of("DE987654321"), values(ex, op, Vocabulary.ID_VAT));
        assertEquals(values(ex, op, Vocabulary.ID_REGISTER).toString(), 1, values(ex, op, Vocabulary.ID_REGISTER).size());
        assertTrue(values(ex, op, Vocabulary.ID_REGISTER).get(0), values(ex, op, Vocabulary.ID_REGISTER).get(0).contains("12345"));
        assertEquals("DE987654321", op.strongKeys.get(Vocabulary.VAT));
    }

    @Test
    public void aContactPageWithBranchesGivesTheOperatorItsHeadquartersOnly() throws Exception {
        final Extraction ex = page("org-contact-hq-branches.html", "https://www.musterbau-gruppe.de/kontakt/", "construction");
        final Mention op = operator(ex);
        assertEquals("Muster Bau GmbH", op.name);
        assertEquals(List.of("Baustraße 9, 45678 Bauhausen"), values(ex, op, Vocabulary.ADDRESS));
        assertEquals(List.of("+492016666666"), values(ex, op, Vocabulary.PHONE));
        assertEquals(List.of("info@musterbau-gruppe.de"), values(ex, op, Vocabulary.EMAIL));
        nothingOf(ex, op, "Nordstraße", "Südweg", "22222", "33333", "+49401234567", "+49897654321");
    }

    @Test
    public void aSentenceAboutAHouseTheOperatorRunsGivesItNoneOfTheHouse() throws Exception {
        final Extraction ex = care("care-news-carrier.html", "/aktuelles/haus-am-bach/");
        final Mention op = operator(ex);
        assertEquals("Muster Altenhilfe gGmbH", op.name);
        assertEquals("its footer's address", List.of("Trägerstraße 1, 10115 Musterstadt"), values(ex, op, Vocabulary.ADDRESS));
        nothingOf(ex, op, "Bachweg", "45678", "Bachstadt");
    }

    /** The operator's own block, also where the page's one structured organisation is the operator: one set of contacts, not two. */
    @Test
    public void theStructuredOperatorAndTheLinkedOperatorShareOneSetOfContacts() throws Exception {
        final String ld = "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"Muster Elektro GmbH\","
                + "\"url\":\"https://www.muster-elektro.de/\"}";
        final Extraction ex = page("org-home-footer-blocks.html", "https://www.muster-elektro.de/", "construction", ld);
        int addresses = 0;
        for (final Mention o : FacilityPageTest.organisations(ex)) {
            assertEquals("Muster Elektro GmbH", o.name);
            addresses += values(ex, o, Vocabulary.ADDRESS).size();
        }
        assertEquals(1, addresses);
    }

    /** The facilities' own pages stay as they were: the operator gets none of them (H5), with or without its structured node. */
    @Test
    public void onAFacilitysPageTheOperatorStillGetsNothing() throws Exception {
        for (final String[] page : new String[][] {{"care-facility-lindenhof.html", "/einrichtungen/haus-lindenhof/"},
                {"care-facility-am-markt.html", "/einrichtungen/seniorenzentrum-am-markt"}, {"care-facility-am-see.html", "/einrichtungen/haus-am-see"}}) {
            final Extraction ex = care(page[0], page[1]);
            final Mention op = ex.mention(RuleExtractor.OPERATOR_REF);
            if (op != null) {
                for (final String p : new String[] {Vocabulary.ADDRESS, Vocabulary.PHONE, Vocabulary.EMAIL, Vocabulary.ID_VAT, Vocabulary.ID_REGISTER}) {
                    assertEquals(page[0] + " " + p, List.of(), values(ex, op, p));
                }
            }
        }
    }
}
