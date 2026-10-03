/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.cora.protocol;

import static org.junit.Assert.*;
import org.junit.Test;
import com.google.common.net.InetAddresses;

public class DomainsPublicAddressTest {
    @Test
    public void localAndSpecialUseAddressesAreNotPublicFetchTargets() {
        for (final String ip : new String[] {"127.0.0.1", "10.0.0.1", "172.16.0.1",
                "192.168.0.1", "169.254.1.1", "0.0.0.0", "224.0.0.1", "240.0.0.1",
                "100.64.0.1", "198.18.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1",
                "::1", "::", "fe80::1", "fd00::1", "ff02::1", "2001:db8::1"}) {
            assertFalse(ip, Domains.isPublicAddress("fixture.com", InetAddresses.forString(ip)));
        }
        assertFalse(Domains.isPublicAddress("fixture.com", null));
    }

    @Test
    public void ordinaryPublicAddressesAreAllowed() {
        assertTrue(Domains.isPublicAddress("fixture.com", InetAddresses.forString("8.8.8.8")));
        assertTrue(Domains.isPublicAddress("fixture.com", InetAddresses.forString("2001:4860:4860::8888")));
    }

    @Test
    public void existingLocalityContractIsUnchanged() {
        assertTrue(Domains.isLocal("fixture.com", InetAddresses.forString("10.0.0.1")));
        assertFalse(Domains.isLocal("fixture.com", InetAddresses.forString("8.8.8.8")));
    }
}
