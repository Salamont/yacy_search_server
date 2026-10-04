/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpHost;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.protocol.HttpContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.net.InetAddresses;
import com.sun.net.httpserver.HttpServer;

/**
 * Real Apache HTTP client, validation, pinning and redirect handling. The "public" addresses
 * are fake DNS answers; the test socket factory delivers every validated connection to a local
 * fixture server, so nothing leaves the machine.
 */
public class PinnedHttpGetTest {

    private HttpServer server;
    private final List<String> served = Collections.synchronizedList(new ArrayList<>());
    private final List<InetSocketAddress> connections = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/", exchange -> {
            final String path = exchange.getRequestURI().getPath();
            this.served.add(exchange.getRequestHeaders().getFirst("Host") + path);
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            int status = 200;
            if (path.startsWith("/redirect/")) {
                exchange.getResponseHeaders().add("Location", path.substring("/redirect/".length()).replaceFirst("^(https?):/", "$1://"));
                status = 302;
                body = new byte[0];
            } else if (path.equals("/large")) {
                body = new byte[4096];
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
        this.server.start();
    }

    @After
    public void stop() {
        this.server.stop(0);
    }

    /** Connects every (already validated) target address to the fixture server, reporting the target as peer. */
    private ConnectionSocketFactory toFixture() {
        final int port = this.server.getAddress().getPort();
        return new ConnectionSocketFactory() {
            @Override
            public Socket createSocket(final HttpContext context) {
                return new Socket() {
                    private SocketAddress reported;
                    @Override
                    public void connect(final SocketAddress endpoint, final int timeout) throws IOException {
                        this.reported = endpoint;
                        super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeout);
                    }
                    @Override
                    public SocketAddress getRemoteSocketAddress() {
                        return this.reported;
                    }
                };
            }

            @Override
            public Socket connectSocket(final int timeout, final Socket socket, final HttpHost host,
                    final InetSocketAddress remote, final InetSocketAddress local, final HttpContext context) throws IOException {
                PinnedHttpGetTest.this.connections.add(remote);
                socket.connect(remote, timeout);
                return socket;
            }
        };
    }

    private PinnedHttpGet client(final OutboundUrlPolicy policy) {
        return new PinnedHttpGet(policy, "test", toFixture(), toFixture());
    }

    private static InetAddress ip(final String value) {
        return InetAddresses.forString(value);
    }

    @Test
    public void publicTargetIsFetchedOverTheValidatedAddress() throws Exception {
        final PinnedHttpGet.Result result = client(OutboundUrlPolicyTest.policy("public.example", "93.184.216.34"))
                .get("http://public.example/data.json", null, 5000, 1000);
        assertEquals(200, result.status);
        assertEquals("{\"ok\":true}", new String(result.body, StandardCharsets.UTF_8));
        assertEquals(Collections.singletonList(new InetSocketAddress(ip("93.184.216.34"), 80)), this.connections);
        assertEquals(Collections.singletonList("public.example/data.json"), this.served);
    }

    @Test
    public void redirectFromPublicToPrivateIsBlocked() throws Exception {
        final int port = this.server.getAddress().getPort();
        final OutboundUrlPolicy policy = OutboundUrlPolicyTest.policy("public.example", "93.184.216.34",
                "internal.example", "192.168.1.10");
        for (final String target : new String[] {"http:/127.0.0.1:" + port + "/secret", "http:/internal.example/secret",
                "http:/169.254.169.254/latest/meta-data/", "http:/localhost:" + port + "/secret", "file:/etc/passwd"}) {
            this.served.clear();
            this.connections.clear();
            try {
                client(policy).get("http://public.example/redirect/" + target, null, 5000, 1000);
                fail("redirect not blocked: " + target);
            } catch (final OutboundUrlPolicy.Blocked e) {
                // expected: the redirect target is validated before any connection
            }
            assertEquals(Collections.singletonList("public.example/redirect/" + target), this.served); // nothing else was requested
            assertEquals(1, this.connections.size());
        }
    }

    @Test
    public void redirectToAnotherPublicTargetIsFollowedAndValidated() throws Exception {
        final OutboundUrlPolicy policy = OutboundUrlPolicyTest.policy("public.example", "93.184.216.34",
                "other.example", "93.184.216.35");
        final PinnedHttpGet.Result result = client(policy).get("http://public.example/redirect/http:/other.example/ok", null, 5000, 1000);
        assertEquals(200, result.status);
        assertEquals("http://other.example/ok", result.url);
        assertEquals(new InetSocketAddress(ip("93.184.216.35"), 80), this.connections.get(1));
    }

    @Test
    public void dnsRebindingCannotChangeTheConnectedAddress() throws Exception {
        final AtomicInteger lookups = new AtomicInteger();
        final OutboundUrlPolicy rebinding = new OutboundUrlPolicy(host -> {
            if (!"rebind.example".equals(host)) throw new UnknownHostException(host);
            // first answer public, every later answer private
            return new InetAddress[] {lookups.getAndIncrement() == 0 ? ip("93.184.216.34") : ip("127.0.0.1")};
        });
        final PinnedHttpGet.Result result = client(rebinding).get("http://rebind.example/data.json", null, 5000, 1000);
        assertEquals(200, result.status);
        assertEquals(1, lookups.get()); // the connection used the validated answer, no second lookup
        assertEquals(Collections.singletonList(new InetSocketAddress(ip("93.184.216.34"), 80)), this.connections);
    }

    @Test
    public void redirectLimitAndSizeLimit() throws Exception {
        final OutboundUrlPolicy policy = OutboundUrlPolicyTest.policy("public.example", "93.184.216.34");
        String url = "http://public.example/data.json";
        for (int i = 0; i <= PinnedHttpGet.MAX_REDIRECTS; i++) url = "http://public.example/redirect/" + url.replace("://", ":/");
        try {
            client(policy).get(url, null, 5000, 1000);
            fail("redirect loop not limited");
        } catch (final IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("too many redirects"));
        }
        try {
            client(policy).get("http://public.example/large", null, 5000, 1000);
            fail("size limit not applied");
        } catch (final IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("too large"));
        }
    }
}
