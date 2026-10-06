/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import net.yacy.http.ClientAddress;

/** The collections a chat client may choose (package 6.1): the list of the page and the check of the endpoint. */
public class ChatCollectionsTest {

    private static final List<String> INDEX = List.of("zeta-web", "Alpha", "beta_web", "kein gültiger Name", "secret");

    @Test
    public void localAndAdministratorAccessGetsEveryCollectionOfTheIndexSortedAlphabetically() {
        assertEquals(List.of("Alpha", "beta_web", "secret", "zeta-web"), ChatCollections.allowed(true, () -> INDEX, ""));
        assertEquals("a new collection of the index appears without any setting", List.of("Alpha", "beta_web", "neu", "secret", "zeta-web"),
                ChatCollections.allowed(true, () -> List.of("zeta-web", "Alpha", "beta_web", "secret", "neu"), ""));
        assertTrue(ChatCollections.permits(true, "secret", ""));
    }

    @Test
    public void aGuestGetsOnlyReleasedCollectionsThatExistAndNoneByDefault() {
        final AtomicInteger reads = new AtomicInteger();
        assertEquals(List.of(), ChatCollections.allowed(false, () -> { reads.incrementAndGet(); return INDEX; }, ""));
        assertEquals("nothing released: the index is not even read", 0, reads.get());
        assertEquals(List.of("beta_web", "zeta-web"), ChatCollections.allowed(false, () -> INDEX, "zeta-web, beta_web gone"));
        assertFalse("never a collection that was not released", ChatCollections.allowed(false, () -> INDEX, "beta_web").contains("secret"));
    }

    @Test
    public void theEndpointAcceptsOnlyTheClientsCollectionsAndAlwaysTheWholeIndex() {
        assertTrue("no collection: the whole index", ChatCollections.permits(false, null, ""));
        assertFalse(ChatCollections.permits(false, "secret", ""));
        assertFalse(ChatCollections.permits(false, "secret", "beta_web"));
        assertTrue(ChatCollections.permits(false, "beta_web", "beta_web,zeta-web"));
        assertFalse("names are compared exactly", ChatCollections.permits(false, "Beta_web", "beta_web"));
    }

    @Test
    public void theReleasedSettingKeepsValidNamesOnce() {
        assertEquals(List.of("a", "b", "c-d"), ChatCollections.released(" b, a  c-d,,a ,näme"));
        assertEquals(List.of(), ChatCollections.released(null));
        assertEquals(List.of(), ChatCollections.released(""));
    }

    @Test
    public void onlyADirectLocalConnectionOrAnAdministratorIsPrivileged() {
        final String trusted = "127[.]0[.]0[.]1,0:0:0:0:0:0:0:1,::1";
        final ClientAddress local = ClientAddress.resolve("127.0.0.1", name -> List.of(), trusted);
        final ClientAddress proxied = ClientAddress.resolve("127.0.0.1",
                name -> ClientAddress.X_FORWARDED_FOR.equals(name) ? List.of("198.51.100.23") : List.of(), trusted);
        final ClientAddress remote = ClientAddress.resolve("198.51.100.23", name -> List.of(), trusted);
        assertTrue(ChatCollections.privileged(local, null));
        assertFalse("a client behind a proxy on loopback is a guest", ChatCollections.privileged(proxied, null));
        assertFalse(ChatCollections.privileged(remote, null));
    }

    @Test
    public void theIndexIsReadAtMostEveryTenSeconds() {
        final AtomicInteger reads = new AtomicInteger();
        final List<String> first = ChatCollections.cachedIndex(() -> { reads.incrementAndGet(); return List.of("a"); });
        final List<String> second = ChatCollections.cachedIndex(() -> { reads.incrementAndGet(); return List.of("b"); });
        assertEquals(first, second);
        assertTrue(reads.get() <= 1);
    }
}
