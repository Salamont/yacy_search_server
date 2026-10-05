package net.yacy.scoutro.knowledge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class KgIdsTest {

    @Test
    public void entityIdsAreDeterministicAndWellFormed() {
        final String a = KgIds.entityId("organization", "register", "", "HRB 12345 B|AG Charlottenburg");
        final String b = KgIds.entityId("organization", "register", "", "HRB 12345 B|AG Charlottenburg");
        assertEquals(a, b);
        assertTrue(a, KgIds.isEntityId(a));
        assertEquals(KgIds.ID_LENGTH, a.length());
        assertFalse(KgIds.isStatementId(a));
    }

    @Test
    public void partBoundariesCannotCollide() {
        // "ab" + "c" must differ from "a" + "bc"
        assertNotEquals(KgIds.entityId("organization", "ab", "c", "x"), KgIds.entityId("organization", "a", "bc", "x"));
        assertNotEquals(KgIds.entityId("facility", "site_name", "example.de", "x"),
                KgIds.entityId("organization", "site_name", "example.de", "x"));
    }

    @Test
    public void unicodeIsNormalised() {
        // composed and decomposed umlaut give the same id
        assertEquals(KgIds.entityId("organization", "site_name", "example.de", "Müller GmbH"),
                KgIds.entityId("organization", "site_name", "example.de", "Müller GmbH"));
    }

    @Test
    public void statementIdsAndObjectKeys() {
        final String s = KgIds.statementId("kge_aaaaaaaaaaaaaaaaaaaa", "operates", "e:kge_bbbbbbbbbbbbbbbbbbbb");
        assertTrue(s, KgIds.isStatementId(s));
        assertEquals(16, KgIds.objectKey("v:+49301234567").length);
        assertNotEquals(KgIds.statementId("kge_aaaaaaaaaaaaaaaaaaaa", "operates", "x"),
                KgIds.statementId("kge_aaaaaaaaaaaaaaaaaaaa", "offers", "x"));
    }

    @Test
    public void epochsAreRandomHex() {
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            final String e = KgIds.newEpoch();
            assertTrue(e, KgIds.isEpoch(e));
            seen.add(e);
        }
        assertEquals(100, seen.size());
    }

    @Test
    public void docIdsAreYacyUrlHashes() {
        assertTrue(KgIds.isDocId("AbCd-_12xYz0"));
        assertFalse(KgIds.isDocId("AbCd-_12xYz"));
        assertFalse(KgIds.isDocId("AbCd-_12xYz0!"));
        assertFalse(KgIds.isDocId("AbCd+/12xYz0"));
    }
}
