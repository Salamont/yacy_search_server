/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.resolve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/** 0.13.0 (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 41): the organisation an {@code @id} names, and when two of them are one. */
public class OrganisationIdsTest {

    private static final String SCOPE = "organization@www.muster.de";

    private static Map<String, List<String>> keys(final String... schemeScopeValue) {
        final Map<String, List<String>> out = new HashMap<>();
        for (int i = 0; i < schemeScopeValue.length; i += 3) {
            out.computeIfAbsent(schemeScopeValue[i] + "\u0000" + schemeScopeValue[i + 1], k -> new java.util.ArrayList<>())
                    .add(schemeScopeValue[i + 2]);
        }
        return out;
    }

    @Test
    public void anIdWithoutLanguageAndFragment() {
        assertEquals("https://www.muster.de/", OrganisationIds.canonical("https://www.muster.de/#organization", "muster.de"));
        assertEquals("https://www.muster.de/", OrganisationIds.canonical("https://WWW.Muster.de/en/#organization/", "muster.de"));
        assertEquals("https://www.muster.de/", OrganisationIds.canonical("https://www.muster.de/de-at/#org", "muster.de"));
        assertEquals("https://www.muster.de/firmen/beta", OrganisationIds.canonical("https://www.muster.de/en/firmen/beta/#organization", "muster.de"));
        for (final String node : new String[] {"#Organization", "#/schema/Organization", "#/schema/Organization/base", "#identity", "#organization/", ""}) {
            assertEquals(node, "https://www.muster.de/", OrganisationIds.canonical("https://www.muster.de/" + node, "muster.de"));
        }
        // a fragment of another node names another: a brand, a contact record, a partner
        assertEquals("https://www.muster.de/#brand", OrganisationIds.canonical("https://www.muster.de/en/#brand", "muster.de"));
        assertEquals("https://www.muster.de/#/schema/com_contact/contact/1",
                OrganisationIds.canonical("https://www.muster.de/#/schema/com_contact/contact/1", "muster.de"));
        assertEquals("https://www.muster.de/#beispiel-ag", OrganisationIds.canonical("https://www.muster.de/#beispiel-ag", "muster.de"));
        assertEquals("a segment of two letters that is no language stays", "https://www.muster.de/xq",
                OrganisationIds.canonical("https://www.muster.de/xq/#organization", "muster.de"));
        assertEquals("the scheme stays: http and https are two ids", "http://www.muster.de/",
                OrganisationIds.canonical("http://www.muster.de/#organization", "muster.de"));
        assertNull("another registrable domain", OrganisationIds.canonical("https://www.beispiel.de/#organization", "muster.de"));
        assertNull("no web address", OrganisationIds.canonical("brand:muster", "muster.de"));
        assertNull(OrganisationIds.canonical("urn:uuid:1234", "muster.de"));
    }

    @Test
    public void theRootOfTheOwnDomain() {
        assertTrue(OrganisationIds.root("https://www.muster.de/#organization", "muster.de"));
        assertTrue(OrganisationIds.root("https://muster.de/en/#organization/", "muster.de"));
        assertTrue(OrganisationIds.root("https://muster.de", "muster.de"));
        assertFalse("a company profile below the root", OrganisationIds.root("https://www.muster.de/firmen/alpha/#organization", "muster.de"));
        assertFalse(OrganisationIds.root("https://www.muster.de/?firma=alpha#organization", "muster.de"));
        assertFalse("another domain", OrganisationIds.root("https://www.beispiel.de/#organization", "muster.de"));
        assertFalse("a partner's node at the root", OrganisationIds.root("https://www.muster.de/#beispiel-ag", "muster.de"));
        assertFalse("a brand's node", OrganisationIds.root("https://www.muster.de/#brand", "muster.de"));
    }

    @Test
    public void twoIdsOfOneOrganisationWithOneNameAreOne() {
        final Map<String, List<String>> de = keys("ld_id", SCOPE, "https://www.muster.de/#organization", "domain_operator",
                "organization@muster.de", "operator");
        final Map<String, List<String>> en = keys("ld_id", SCOPE, "https://www.muster.de/en/#organization/");
        assertEquals("ld_id", OrganisationIds.blocking(de, en, false));
        assertNull(OrganisationIds.blocking(de, en, true));
        assertNull(OrganisationIds.apart(de, Set.of("muster software"), Set.of(), en, Set.of("muster software"), Set.of()));
    }

    @Test
    public void inDoubtTwo() {
        final Map<String, List<String>> a = keys("ld_id", SCOPE, "https://www.muster.de/#organization", "site_operator",
                "organization@muster.de", "muster bau gmbh");
        final Set<String> names = Set.of("muster bau");
        assertEquals(OrganisationIds.OTHER_ID, OrganisationIds.apart(a, names, Set.of(),
                keys("ld_id", SCOPE, "https://www.muster.de/firmen/beta/#organization"), names, Set.of()));
        assertEquals("another host is another scope: no variant", OrganisationIds.OTHER_ID, OrganisationIds.apart(a, names, Set.of(),
                keys("ld_id", "organization@shop.muster.de", "https://www.muster.de/en/#organization"), names, Set.of()));
        final Map<String, List<String>> en = keys("ld_id", SCOPE, "https://www.muster.de/en/#organization");
        assertEquals(OrganisationIds.NAMES_DIFFER, OrganisationIds.apart(a, names, Set.of(), en, Set.of("beispiel bau"), Set.of()));
        assertEquals(OrganisationIds.NAMES_DIFFER, OrganisationIds.apart(a, names, Set.of(), en, Set.of(), Set.of()));
        assertEquals(OrganisationIds.LEGAL_NAME_DIFFERS, OrganisationIds.apart(a, names, Set.of(),
                keys("ld_id", SCOPE, "https://www.muster.de/en/#organization", "operator_name", "organization@muster.de", "muster bau ag|DOC"),
                names, Set.of()));
        assertEquals(OrganisationIds.LEGAL_FORM_DIFFERS, OrganisationIds.apart(a, names, Set.of("GmbH"), en, names, Set.of("AG")));
        assertEquals("a strong identifier", "vat", OrganisationIds.apart(keys("ld_id", SCOPE, "https://www.muster.de/#organization", "vat",
                "organization", "DE123456789"), names, Set.of(), keys("ld_id", SCOPE, "https://www.muster.de/en/#organization", "vat",
                        "organization", "DE987654321"), names, Set.of()));
        assertEquals("an entity of two ids meets a third", "ld_id", OrganisationIds.blocking(keys("ld_id", SCOPE,
                "https://www.muster.de/#organization", "ld_id", SCOPE, "https://www.muster.de/en/#organization"), keys("ld_id", SCOPE,
                        "https://www.muster.de/firmen/alpha/#organization"), true));
    }
}
