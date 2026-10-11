/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.http.HttpServletRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.yacy.cora.order.Digest;
import net.yacy.search.SwitchboardConstants;

/**
 * The client the sign-in limits count (docs/SCOUTRO_USERS_ACCESS.md, "Sign-in limits"): a client behind
 * a trusted reverse proxy cannot choose its bucket with X-Real-IP or X-Forwarded-For.
 */
public class SignInClientTest {

    private static final String LOOPBACK = SwitchboardConstants.SERVER_REVERSE_PROXY_TRUSTED_DEFAULT;

    private File dir;
    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    private final Map<String, String> config = new HashMap<>();
    private ScoutroAccess access;

    @Before
    public void setUp() throws Exception {
        this.dir = Files.createTempDirectory("scoutro-signin-client-test").toFile();
        this.config.put(SwitchboardConstants.SERVER_REVERSE_PROXY_TRUSTED, LOOPBACK);
        final String hash = "MD5:" + Digest.encodeMD5Hex("admin:TestRealm:admin-secret-1");
        this.access = ScoutroAccess.createForTest(this.dir, this.clock::get, this.config::get, new ScoutroAccess.BuiltinAdmin() {
            @Override public String name() { return "admin"; }
            @Override public String hash() { return hash; }
            @Override public String realm() { return "TestRealm"; }
        }, new PasswordHasher(256, 1));
    }

    @After
    public void tearDown() {
        ScoutroAccess.resetForTest();
        final File[] files = this.dir.listFiles();
        if (files != null) {
            for (final File f : files) {
                f.delete();
            }
        }
        this.dir.delete();
    }

    private String client(final TestRequests.Builder b) {
        return SignInClient.of(b.build(), this.access.settings);
    }

    private static TestRequests.Builder from(final String peer) {
        return TestRequests.get("/scoutro/api/v1/auth/login").method("POST").remote(peer);
    }

    @Test
    public void aDirectClientCountsItsSocketAddressWhateverHeadersItSends() {
        assertEquals("203.0.113.7", client(from("203.0.113.7")));
        assertEquals("203.0.113.7", client(from("203.0.113.7").header("X-Real-IP", "198.51.100.23")));
        assertEquals("203.0.113.7", client(from("203.0.113.7").header("X-Forwarded-For", "198.51.100.24")));
        this.config.put(AccessSettings.LIMITS_PROXY_ADDRESS, "x-real-ip");
        assertEquals("an untrusted peer stays itself even with the opt-in", "203.0.113.7",
                client(from("203.0.113.7").header("X-Real-IP", "198.51.100.23")));
    }

    @Test
    public void requestsThroughATrustedProxyShareOneBucketByDefault() {
        // the Olares chain connects from loopback; assume the worst case that it passes the client's headers on
        assertEquals("proxy:127.0.0.1", client(from("127.0.0.1")));
        assertEquals("proxy:127.0.0.1", client(from("127.0.0.1").header("X-Real-IP", "198.51.100.23")));
        assertEquals("proxy:127.0.0.1", client(from("127.0.0.1").header("X-Forwarded-For", "198.51.100.24, 10.0.0.5")));
        assertEquals("proxy:127.0.0.1", client(from("127.0.0.1").header("X-Real-IP", "198.51.100.23")
                .header("X-Forwarded-For", "198.51.100.24").header("X-Forwarded-Proto", "http")));
        assertEquals("proxy:::1", client(from("0:0:0:0:0:0:0:1").header("X-Real-IP", "198.51.100.23")));
        assertEquals("proxy:::1", client(from("::1")));
        // unknown values of the setting keep the shared bucket; X-Forwarded-For has no mode at all
        this.config.put(AccessSettings.LIMITS_PROXY_ADDRESS, "x-forwarded-for");
        assertFalse(this.access.settings.proxyRealIp());
        assertEquals("proxy:127.0.0.1", client(from("127.0.0.1").header("X-Forwarded-For", "198.51.100.24")));
    }

    @Test
    public void xRealIpCountsOnlyWithTheExplicitSettingAndExactlyOneValidValue() {
        this.config.put(AccessSettings.LIMITS_PROXY_ADDRESS, "X-Real-IP");
        assertTrue(this.access.settings.proxyRealIp());
        assertEquals("198.51.100.23", client(from("127.0.0.1").header("X-Real-IP", " 198.51.100.23 ")));
        assertEquals("2001:db8::1", client(from("127.0.0.1").header("X-Real-IP", "2001:DB8:0:0:0:0:0:1")));
        assertEquals("proxy:127.0.0.1", client(from("127.0.0.1")));
        assertEquals("proxy:127.0.0.1", client(from("127.0.0.1").header("X-Real-IP", "unknown")));
        assertEquals("proxy:127.0.0.1", client(from("127.0.0.1").header("X-Real-IP", "198.51.100.23, 198.51.100.24")));
        assertEquals("a client header next to the proxy's", "proxy:127.0.0.1",
                client(from("127.0.0.1").header("X-Real-IP", "198.51.100.23").header("X-Real-IP", "203.0.113.9")));
        assertEquals("X-Forwarded-For is never used", "proxy:127.0.0.1",
                client(from("127.0.0.1").header("X-Forwarded-For", "198.51.100.24")));
    }

    @Test
    public void withoutTrustedProxiesOnlyTheSocketPeerCounts() {
        this.config.put(SwitchboardConstants.SERVER_REVERSE_PROXY_TRUSTED, "");
        this.config.put(AccessSettings.LIMITS_PROXY_ADDRESS, "x-real-ip");
        assertEquals("127.0.0.1", client(from("127.0.0.1").header("X-Real-IP", "198.51.100.23")));
        final HttpServletRequest r = from("127.0.0.1").header("X-Real-IP", "198.51.100.23").build();
        assertEquals("no settings: socket peer", "127.0.0.1", SignInClient.of(r, null));
    }

    @Test
    public void rotatingForgedHeadersBehindTheProxyCannotLeaveTheSharedBucket() throws Exception {
        this.config.put(AccessSettings.PROTECTED, "true");
        final AccountStore.Spec s = new AccountStore.Spec();
        s.username = "anna";
        s.role = Role.RESEARCH;
        s.collections = List.of("a-web");
        s.password = "anna-password-1";
        this.access.accounts.create(s, "test", this.access);
        final Set<String> buckets = new HashSet<>();
        for (int i = 0; i < LoginThrottle.CLIENT_FAILURES; i++) {
            // at most 4 failures per probe name, so the account limit never triggers
            final String probe = "probe-" + (i / 4);
            final String bucket = client(from("127.0.0.1").header("X-Real-IP", "198.51.100." + (i + 1))
                    .header("X-Forwarded-For", "203.0.113." + (i + 1)));
            buckets.add(bucket);
            try {
                this.access.login(probe, "wrong-" + i, bucket, "test");
                fail();
            } catch (final AccessException e) {
                assertEquals("invalid_credentials", e.code());
            }
        }
        assertEquals(Set.of("proxy:127.0.0.1"), buckets);
        try {
            this.access.login("anna", "anna-password-1",
                    client(from("127.0.0.1").header("X-Real-IP", "198.51.100.200")), "test");
            fail("a fresh forged X-Real-IP escaped the shared bucket");
        } catch (final AccessException e) {
            assertEquals(429, e.status());
            assertEquals("too_many_attempts", e.code());
        }
        // a client that connects directly keeps its own bucket
        assertNotNull(this.access.login("anna", "anna-password-1", client(from("203.0.113.7")), "test"));
        // the audit names the shared bucket, never the forged values
        for (final org.json.JSONObject e : this.access.audit.recent("probe-0", 10)) {
            assertEquals("proxy:127.0.0.1", e.optString("client"));
        }
    }

    @Test
    public void theAccountLimitStillAppliesAcrossClients() throws Exception {
        for (int i = 0; i < LoginThrottle.ACCOUNT_FAILURES; i++) {
            try {
                this.access.login("probe-account", "wrong-" + i, client(from("203.0.113." + (i + 1))), "test");
                fail();
            } catch (final AccessException e) {
                assertEquals("invalid_credentials", e.code());
            }
        }
        try {
            this.access.login("probe-account", "wrong-x", client(from("203.0.113.99")), "test");
            fail("account limit ignored");
        } catch (final AccessException e) {
            assertEquals(429, e.status());
        }
    }
}
