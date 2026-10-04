/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

import net.yacy.http.servlets.LLMAccess;
import net.yacy.search.SwitchboardConstants;

/** Client address resolution shared by the AI Shield and the LLM admin proxy. */
public class ClientAddressTest {

    private static final String TRUSTED = SwitchboardConstants.SERVER_REVERSE_PROXY_TRUSTED_DEFAULT; // loopback only

    private static ClientAddress resolve(final String peer, final String trusted, final String... headers) {
        final Map<String, List<String>> map = new HashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            map.computeIfAbsent(headers[i].toLowerCase(), k -> new java.util.ArrayList<>()).add(headers[i + 1]);
        }
        return ClientAddress.resolve(peer, name -> map.getOrDefault(name.toLowerCase(), Collections.emptyList()), trusted);
    }

    @Test
    public void trustedProxyWithForwardedForUsesRightmostUntrustedHop() {
        ClientAddress client = resolve("127.0.0.1", TRUSTED, "X-Forwarded-For", "8.8.8.8, 9.9.9.9");
        assertEquals("9.9.9.9", client.effective());
        assertEquals(ClientAddress.Source.X_FORWARDED_FOR, client.source());
        assertFalse(client.isLocal());

        // a client cannot prepend a fake hop: the right-most untrusted entry wins
        client = resolve("127.0.0.1", TRUSTED, "X-Forwarded-For", "127.0.0.1, 1.1.1.1");
        assertEquals("1.1.1.1", client.effective());
        assertFalse(client.isLocal());

        // further trusted proxies in the chain are skipped
        client = resolve("10.0.0.5", "10[.]0[.]0[.]5,10[.]0[.]0[.]6", "X-Forwarded-For", "8.8.8.8, 10.0.0.6");
        assertEquals("8.8.8.8", client.effective());

        // several header lines form one chain
        client = resolve("127.0.0.1", TRUSTED, "X-Forwarded-For", "8.8.8.8", "X-Forwarded-For", "9.9.9.9");
        assertEquals("9.9.9.9", client.effective());
    }

    @Test
    public void trustedProxyWithRealIp() {
        final ClientAddress client = resolve("0:0:0:0:0:0:0:1", TRUSTED, "X-Real-IP", "8.8.4.4", "X-Forwarded-For", "5.5.5.5");
        assertTrue(client.isPeerTrusted());
        assertEquals("8.8.4.4", client.effective());
        assertEquals(ClientAddress.Source.X_REAL_IP, client.source());
        assertFalse(client.isLocal());
    }

    @Test
    public void untrustedPeerCannotSpoofForwardedHeaders() {
        ClientAddress client = resolve("192.0.2.10", TRUSTED, "X-Forwarded-For", "127.0.0.1", "X-Real-IP", "127.0.0.1");
        assertEquals("192.0.2.10", client.effective());
        assertEquals(ClientAddress.Source.UNTRUSTED_PROXY, client.source());
        assertFalse(client.isLocal());

        // headers through an untrusted proxy on loopback: values ignored, but never local
        client = resolve("127.0.0.1", "", "X-Forwarded-For", "8.8.8.8");
        assertEquals("127.0.0.1", client.effective());
        assertEquals(ClientAddress.Source.UNTRUSTED_PROXY, client.source());
        assertFalse(client.isLocal());
    }

    @Test
    public void proxiedLoopbackIsNeverLocal() {
        // a trusted proxy forwarding a forged or genuine loopback address
        assertFalse(resolve("127.0.0.1", TRUSTED, "X-Real-IP", "127.0.0.1").isLocal());
        assertFalse(resolve("127.0.0.1", TRUSTED, "X-Forwarded-For", "127.0.0.1").isLocal());
        assertFalse(resolve("::1", TRUSTED, "X-Forwarded-For", "::1, 127.0.0.1").isLocal());
        assertFalse(resolve("127.0.0.1", TRUSTED, "Forwarded", "for=8.8.8.8").isLocal());
        // broken values: the peer is used, the request stays proxied
        final ClientAddress broken = resolve("127.0.0.1", TRUSTED, "X-Forwarded-For", "8.8.8.8, unknown");
        assertEquals(ClientAddress.Source.UNRESOLVED, broken.source());
        assertEquals("127.0.0.1", broken.effective());
        assertFalse(broken.isLocal());
        assertFalse(resolve("127.0.0.1", TRUSTED, "X-Real-IP", "1.2.3.4, 5.6.7.8").isLocal());
    }

    @Test
    public void directConnections() {
        assertTrue(resolve("127.0.0.1", TRUSTED).isLocal());
        assertTrue(resolve("0:0:0:0:0:0:0:1", TRUSTED).isLocal());
        assertTrue(resolve("[::1]", TRUSTED).isLocal());
        assertFalse(resolve("10.1.2.3", TRUSTED).isLocal()); // own LAN or pod address is not loopback
        assertFalse(resolve("8.8.8.8", TRUSTED).isLocal());
        assertFalse(resolve("not-an-ip", TRUSTED).isLocal());
        assertEquals(ClientAddress.Source.DIRECT, resolve("8.8.8.8", TRUSTED).source());
    }

    @Test
    public void localhostDoesNotBecomeAdministratorByItself() {
        final ClientAddress local = resolve("127.0.0.1", TRUSTED);
        // adminAccountForLocalhost=false: no bypass, also not for a direct loopback request
        assertFalse(LLMAccess.localAdminBypass(false, local, null));
        // adminAccountForLocalhost=true: only direct loopback with a local or no referer
        assertTrue(LLMAccess.localAdminBypass(true, local, null));
        assertTrue(LLMAccess.localAdminBypass(true, local, "http://localhost:8090/LLMSelection_p.html"));
        assertFalse(LLMAccess.localAdminBypass(true, local, "https://evil.example/"));
        // a request through a proxy on loopback is never a local administrator
        assertFalse(LLMAccess.localAdminBypass(true, resolve("127.0.0.1", TRUSTED, "X-Real-IP", "127.0.0.1"), null));
        assertFalse(LLMAccess.localAdminBypass(true, resolve("127.0.0.1", TRUSTED, "X-Forwarded-For", "8.8.8.8"), null));
        assertFalse(LLMAccess.localAdminBypass(true, resolve("10.0.0.7", TRUSTED), null));
    }

    @Test
    public void aiShieldDecision() {
        final ClientAddress local = resolve("127.0.0.1", TRUSTED);
        final ClientAddress remote = resolve("127.0.0.1", TRUSTED, "X-Forwarded-For", "8.8.8.8");
        final boolean[] asked = {false};
        assertEquals(LLMAccess.Shield.LOCAL, LLMAccess.shield(false, local, false, false, () -> { asked[0] = true; return false; }));
        assertFalse("local access needs no administrator check", asked[0]);
        assertEquals(LLMAccess.Shield.GUEST, LLMAccess.shield(true, remote, true, true, () -> false));
        assertEquals(LLMAccess.Shield.ADMIN, LLMAccess.shield(false, remote, false, false, () -> true));
        assertEquals(LLMAccess.Shield.ADMIN_REQUIRED, LLMAccess.shield(false, remote, false, false, () -> false));
        assertEquals(LLMAccess.Shield.BLOCKED_CROSS_SITE, LLMAccess.shield(false, remote, true, false, () -> true));
        assertEquals(LLMAccess.Shield.BLOCKED_TOKEN, LLMAccess.shield(false, remote, false, true, () -> true));
        assertTrue(LLMAccess.Shield.ADMIN.privileged);
        assertFalse(LLMAccess.Shield.GUEST.privileged);
        for (final LLMAccess.Shield blocked : Arrays.asList(LLMAccess.Shield.ADMIN_REQUIRED,
                LLMAccess.Shield.BLOCKED_CROSS_SITE, LLMAccess.Shield.BLOCKED_TOKEN)) {
            assertFalse(blocked.allowed());
        }
    }

    private static HttpServletRequest request(final String... headers) {
        final Map<String, String> map = new HashMap<>();
        for (int i = 0; i < headers.length; i += 2) map.put(headers[i].toLowerCase(), headers[i + 1]);
        return (HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(ClientAddressTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) ->
                        "getHeader".equals(method.getName()) ? map.get(((String) args[0]).toLowerCase()) : null);
    }

    @Test
    public void crossSiteRequestsAreRecognized() {
        final ClientAddress remote = resolve("127.0.0.1", TRUSTED, "X-Forwarded-For", "8.8.8.8");
        final ClientAddress untrusted = resolve("10.0.0.9", TRUSTED, "X-Forwarded-For", "8.8.8.8");
        assertFalse(LLMAccess.crossSite(request("Sec-Fetch-Site", "same-origin", "Origin", "https://evil.example"), remote));
        assertTrue(LLMAccess.crossSite(request("Sec-Fetch-Site", "cross-site"), remote));
        assertTrue(LLMAccess.crossSite(request("Sec-Fetch-Site", "same-site"), remote));
        assertFalse(LLMAccess.crossSite(request(), remote)); // non-browser client
        assertFalse(LLMAccess.crossSite(request("Origin", "https://scoutro.example", "Host", "scoutro.example"), remote));
        assertTrue(LLMAccess.crossSite(request("Origin", "https://evil.example", "Host", "scoutro.example"), remote));
        assertTrue(LLMAccess.crossSite(request("Origin", "null", "Host", "scoutro.example"), remote));
        // a proxy that rewrites Host: X-Forwarded-Host counts only from a trusted proxy
        assertFalse(LLMAccess.crossSite(request("Origin", "https://scoutro.example", "Host", "scoutro:8090",
                "X-Forwarded-Host", "scoutro.example"), remote));
        assertTrue(LLMAccess.crossSite(request("Origin", "https://scoutro.example", "Host", "scoutro:8090",
                "X-Forwarded-Host", "scoutro.example"), untrusted));
    }
}
