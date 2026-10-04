/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.tools;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.google.common.net.InetAddresses;

/** Offline: DNS answers come from a fixed table, never from the network. */
public class OutboundUrlPolicyTest {

    static OutboundUrlPolicy policy(final String... hostAndAddresses) {
        final Map<String, InetAddress[]> dns = new HashMap<>();
        for (int i = 0; i < hostAndAddresses.length; i += 2) {
            final String[] ips = hostAndAddresses[i + 1].split(",");
            final InetAddress[] addresses = new InetAddress[ips.length];
            for (int j = 0; j < ips.length; j++) addresses[j] = InetAddresses.forString(ips[j]);
            dns.put(hostAndAddresses[i], addresses);
        }
        return new OutboundUrlPolicy(host -> {
            final InetAddress[] answer = dns.get(host);
            if (answer == null) throw new UnknownHostException(host);
            return answer.clone();
        });
    }

    private static String blocked(final OutboundUrlPolicy policy, final String url) {
        try {
            policy.check(url);
            fail("not blocked: " + url);
            return null;
        } catch (final OutboundUrlPolicy.Blocked e) {
            return e.reason;
        }
    }

    @Test
    public void localhostAndLoopbackAreBlocked() {
        final OutboundUrlPolicy p = policy();
        assertEquals("internal_host", blocked(p, "http://localhost/"));
        assertEquals("internal_host", blocked(p, "http://localhost./admin"));
        assertEquals("internal_host", blocked(p, "http://api.localhost/"));
        assertEquals("non_public_address", blocked(p, "http://127.0.0.1:8090/ConfigAccounts_p.html"));
        assertEquals("non_public_address", blocked(p, "http://127.8.9.10/"));
        assertEquals("non_public_address", blocked(p, "http://[::1]:8090/"));
        assertEquals("non_public_address", blocked(p, "http://[::ffff:127.0.0.1]/"));
        assertEquals("non_public_address", blocked(p, "http://0.0.0.0/"));
        assertEquals("non_public_address", blocked(p, "http://[::]/"));
    }

    @Test
    public void privateAndLinkLocalAddressesAreBlocked() {
        final OutboundUrlPolicy p = policy();
        for (final String url : new String[] {"http://10.0.0.1/", "http://172.16.5.4/", "http://172.31.255.1/",
                "http://192.168.1.1/", "http://100.64.0.1/", "http://169.254.1.1/", "http://[fd00::1]/",
                "http://[fc00::1]/", "http://[fe80::1]/", "http://[fec0::1]/", "http://224.0.0.1/", "http://[ff02::1]/",
                "http://240.0.0.1/", "http://255.255.255.255/", "http://192.0.2.1/", "http://198.18.0.1/",
                "http://[2001:db8::1]/", "http://[64:ff9b::7f00:1]/", "http://[2002:7f00:1::1]/", "http://[2001::1]/"}) {
            assertEquals(url, "non_public_address", blocked(p, url));
        }
    }

    @Test
    public void cloudMetadataIsBlocked() {
        final OutboundUrlPolicy p = policy("metadata.google.internal", "169.254.169.254");
        assertEquals("non_public_address", blocked(p, "http://169.254.169.254/latest/meta-data/"));
        assertEquals("non_public_address", blocked(p, "http://[fd00:ec2::254]/latest/meta-data/"));
        assertEquals("non_public_address", blocked(p, "http://100.100.100.200/latest/meta-data/")); // Alibaba
        assertEquals("non_public_address", blocked(p, "http://192.0.0.192/opc/v2/instance/"));      // Oracle
        assertEquals("non_public_address", blocked(p, "http://168.63.129.16/machine"));              // Azure WireServer
        assertEquals("internal_host", blocked(p, "http://metadata.google.internal/computeMetadata/v1/"));
        assertEquals("internal_host", blocked(p, "http://metadata/computeMetadata/v1/"));
    }

    @Test
    public void clusterInternalNamesAreBlocked() {
        final OutboundUrlPolicy p = policy("public.example", "93.184.216.34");
        for (final String url : new String[] {"http://ollama:11434/api/tags", "http://kubernetes.default.svc/api",
                "http://kubernetes.default/", "http://ollama.ollama.svc.cluster.local:11434/", "http://scoutro.svc/",
                "http://printer.local/", "http://nas.lan/", "http://router.home.arpa/", "http://host.internal/"}) {
            assertEquals(url, "internal_host", blocked(p, url));
        }
    }

    @Test
    public void dnsAnswersAreValidatedBeforeConnecting() {
        final OutboundUrlPolicy p = policy("rebind.example", "10.1.2.3", "mixed.example", "93.184.216.34,192.168.0.10",
                "v6private.example", "fd12::5");
        assertEquals("non_public_address", blocked(p, "https://rebind.example/"));
        assertEquals("non_public_address", blocked(p, "https://mixed.example/"));      // one private answer is enough
        assertEquals("non_public_address", blocked(p, "https://v6private.example/"));
        assertEquals("dns_failed", blocked(p, "https://unknown.example/"));
    }

    @Test
    public void onlyHttpSchemesWithoutCredentials() {
        final OutboundUrlPolicy p = policy("public.example", "93.184.216.34");
        assertEquals("scheme_not_allowed", blocked(p, "ftp://public.example/file"));
        assertEquals("scheme_not_allowed", blocked(p, "file:///etc/passwd"));
        assertEquals("scheme_not_allowed", blocked(p, "smb://public.example/share"));
        assertEquals("credentials_not_allowed", blocked(p, "http://user:secret@public.example/"));
        assertEquals("invalid_url", blocked(p, ""));
        assertEquals("invalid_url", blocked(p, "not a url"));
    }

    @Test
    public void publicHttpsIsAllowed() throws Exception {
        final OutboundUrlPolicy p = policy("public.example", "93.184.216.34,2606:2800:220:1:248:1893:25c8:1946");
        final OutboundUrlPolicy.Target target = p.check("https://Public.Example/path?q=1#fragment");
        assertEquals("https", target.scheme);
        assertEquals("public.example", target.host);
        assertEquals(443, target.port);
        assertFalse(target.uri.toString().contains("#"));
        assertArrayEquals(new InetAddress[] {InetAddresses.forString("93.184.216.34"),
                InetAddresses.forString("2606:2800:220:1:248:1893:25c8:1946")}, target.addresses());
        assertEquals(8443, p.check("https://public.example:8443/").port);
        assertEquals(80, p.check("http://8.8.8.8/").port);
        assertTrue(OutboundUrlPolicy.isPublicAddress(InetAddresses.forString("1.1.1.1")));
        assertTrue(OutboundUrlPolicy.isPublicAddress(InetAddresses.forString("2001:4860:4860::8888")));
    }
}
