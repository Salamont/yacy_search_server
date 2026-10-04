/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.cora.protocol.http;

import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.*;
import javax.net.ssl.*;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.socket.*;
import org.apache.http.impl.client.*;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.junit.Test;
import com.google.common.net.InetAddresses;
import net.yacy.cora.document.id.MultiProtocolURL;

/** Real Apache route/resolver/connect/TLS/HTTP logic; only DNS answers and sockets are fake. */
public class PinnedRobotsTransportTest {
    private final List<InetSocketAddress> attempts = new ArrayList<>();
    private final List<FakeSocket> sockets = new ArrayList<>();
    private final Set<InetAddress> unreachable = new HashSet<>();
    private final List<String> tlsHosts = new ArrayList<>();
    private final List<FakeTLS> tlsSockets = new ArrayList<>();
    private InetSocketAddress peerOverride;
    private boolean untrusted;
    private String response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok";
    private static InetAddress ip(final String value) { return InetAddresses.forString(value); }
    private MultiProtocolURL target(final String value) throws Exception { return new MultiProtocolURL(value); }

    private ConnectionSocketFactory plain() {
        return new ConnectionSocketFactory() {
            public Socket createSocket(final HttpContext context) {
                final FakeSocket socket = new FakeSocket(); sockets.add(socket); return socket;
            }
            public Socket connectSocket(final int timeout, final Socket socket, final HttpHost host,
                    final InetSocketAddress remote, final InetSocketAddress local, final HttpContext context)
                    throws IOException {
                return PlainConnectionSocketFactory.getSocketFactory().connectSocket(timeout, socket, host, remote, local, context);
            }
        };
    }
    private ConnectionSocketFactory tls(final MultiProtocolURL target) {
        final var strict = PinnedRobotsTransport.strictTls(target, new SSLSocketFactory() {
            public String[] getDefaultCipherSuites() { return new String[]{"TLS_FAKE"}; }
            public String[] getSupportedCipherSuites() { return getDefaultCipherSuites(); }
            public Socket createSocket(Socket socket, String host, int port, boolean close) {
                tlsHosts.add(host);
                final FakeTLS tls = new FakeTLS((FakeSocket) socket); tlsSockets.add(tls); return tls;
            }
            public Socket createSocket(String host, int port) { throw new AssertionError("DNS connection forbidden"); }
            public Socket createSocket(String host, int port, InetAddress local, int lp) { throw new AssertionError(); }
            public Socket createSocket(InetAddress host, int port) { throw new AssertionError(); }
            public Socket createSocket(InetAddress host, int port, InetAddress local, int lp) { throw new AssertionError(); }
        });
        return new ConnectionSocketFactory() {
            public Socket createSocket(HttpContext context) throws IOException { return plain().createSocket(context); }
            public Socket connectSocket(int timeout, Socket socket, HttpHost host, InetSocketAddress remote,
                    InetSocketAddress local, HttpContext context) throws IOException {
                return strict.connectSocket(timeout, socket, host, remote, local, context);
            }
        };
    }
    private CloseableHttpClient client(final MultiProtocolURL target, final InetAddress... addresses) throws Exception {
        final HttpClientBuilder builder = HttpClientBuilder.create();
        PinnedRobotsTransport.configure(builder, target, addresses, plain(), tls(target));
        return builder.build();
    }
    private String read(final CloseableHttpClient client, final String url) throws Exception {
        try (var result = client.execute(new HttpGet(url))) { return EntityUtils.toString(result.getEntity()); }
    }

    @Test public void snapshotSurvivesDnsRebindingAndCallerMutation() throws Exception {
        final String url = "http://crawl-fixture.test/robots.txt";
        for (String changed : List.of("127.0.0.1", "10.0.0.1", "169.254.169.254", "8.8.4.4")) {
            attempts.clear();
            final InetAddress[] answers = {ip("8.8.8.8")};
            try (var client = client(target(url), answers)) {
                answers[0] = ip(changed);
                assertEquals("ok", read(client, url));
            }
            assertEquals(List.of(new InetSocketAddress(ip("8.8.8.8"), 80)), attempts);
            assertTrue(sockets.get(sockets.size()-1).sent.toString(StandardCharsets.ISO_8859_1)
                    .contains("Host: crawl-fixture.test\r\n"));
        }
    }
    @Test public void ipv6AndFallbackUseOnlySnapshot() throws Exception {
        final String url = "http://crawl-fixture.test:8081/robots.txt";
        unreachable.add(ip("2001:4860:4860::8888"));
        try (var client = client(target(url), ip("2001:4860:4860::8888"), ip("8.8.8.8"))) {
            assertEquals("ok", read(client, url));
        }
        assertEquals(List.of(new InetSocketAddress(ip("2001:4860:4860::8888"),8081),
                            new InetSocketAddress(ip("8.8.8.8"),8081)), attempts);
    }
    @Test public void ipv6LiteralKeepsHostAndPort() throws Exception {
        final String url = "http://[2001:4860:4860::8888]:8081/robots.txt";
        try (var client = client(target(url), ip("2001:4860:4860::8888"))) { assertEquals("ok", read(client,url)); }
        assertEquals(8081, attempts.get(0).getPort());
        assertTrue(sockets.get(0).sent.toString(StandardCharsets.ISO_8859_1)
                .contains("Host: [2001:4860:4860::8888]:8081\r\n"));
    }
    @Test public void freshRequestCannotReuseOldPool() throws Exception {
        final String url = "http://crawl-fixture.test/robots.txt";
        for (String address : List.of("8.8.8.8", "8.8.4.4")) {
            try (var client = client(target(url), ip(address))) { assertEquals("ok", read(client,url)); }
        }
        assertEquals(List.of(new InetSocketAddress(ip("8.8.8.8"),80),new InetSocketAddress(ip("8.8.4.4"),80)),attempts);
        assertTrue(sockets.stream().allMatch(s -> s.closed));
    }
    @Test public void redirectsAreNeverFollowedInsideConnector() throws Exception {
        final String url = "http://crawl-fixture.test/robots.txt";
        response = "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1/internal\r\nContent-Length: 0\r\n\r\n";
        try (var client = client(target(url), ip("8.8.8.8")); var result = client.execute(new HttpGet(url))) {
            assertEquals(302,result.getStatusLine().getStatusCode());
        }
        assertEquals(1,attempts.size());
    }
    @Test public void changedHostSchemeOrPortNeverConnects() throws Exception {
        final String original = "http://crawl-fixture.test/robots.txt";
        for (String changed : List.of("http://other-fixture.test/", "https://crawl-fixture.test/", "http://crawl-fixture.test:8080/")) {
            try (var client = client(target(original), ip("8.8.8.8"))) {
                assertThrows(IOException.class, () -> read(client,changed));
            }
        }
        assertTrue(attempts.isEmpty());
    }
    @Test public void proxiesAreRefusedBeforeSocketCreation() throws Exception {
        final String url = "http://crawl-fixture.test/robots.txt";
        final var originalPlanner = ProxySettings.RoutePlanner;
        try {
            ProxySettings.RoutePlanner = (host, req, context) -> new org.apache.http.conn.routing.HttpRoute(
                    host, null, new HttpHost("proxy-fixture.test",8080),false);
            try (var client = client(target(url),ip("8.8.8.8"))) { assertThrows(IOException.class, () -> read(client,url)); }
        } finally { ProxySettings.RoutePlanner = originalPlanner; }
        try (var client = client(target(url),ip("8.8.8.8"))) {
            final HttpGet request = new HttpGet(url);
            request.setConfig(RequestConfig.custom().setProxy(new HttpHost("proxy-fixture.test",8080)).build());
            assertThrows(IOException.class, () -> client.execute(request));
        }
        assertTrue(sockets.isEmpty());
    }
    @Test public void unexpectedConnectedPeerIsClosedBeforeHttpSend() throws Exception {
        final String url = "http://crawl-fixture.test/robots.txt";
        peerOverride = new InetSocketAddress(ip("127.0.0.1"),80);
        try (var client = client(target(url),ip("8.8.8.8"))) { assertThrows(IOException.class, () -> read(client,url)); }
        assertTrue(sockets.get(0).closed);
        assertEquals(0,sockets.get(0).sent.size());
    }
    @Test public void tlsPreservesSniHostAndEnablesCertificateHostnameChecks() throws Exception {
        final String url = "https://crawl-fixture.test:8443/robots.txt";
        try (var client = client(target(url),ip("8.8.8.8"))) { assertEquals("ok",read(client,url)); }
        assertEquals(List.of("crawl-fixture.test"),tlsHosts);
        assertEquals("HTTPS",tlsSockets.get(0).parameters.getEndpointIdentificationAlgorithm());
        assertEquals(List.of(new SNIHostName("crawl-fixture.test")),tlsSockets.get(0).parameters.getServerNames());
        assertEquals(new InetSocketAddress(ip("8.8.8.8"),8443),attempts.get(0));
    }
    @Test public void wrongCertificateHostnameAndUntrustedHandshakeAreRejected() throws Exception {
        for (String url : List.of("https://wrong-fixture.test/robots.txt", "https://crawl-fixture.test/robots.txt")) {
            untrusted = url.contains("crawl-fixture");
            try (var client = client(target(url),ip("8.8.8.8"))) { assertThrows(IOException.class, () -> read(client,url)); }
        }
        assertTrue(sockets.stream().allMatch(s -> s.sent.size()==0));
    }
    @Test public void emptyValidationRefused() throws Exception {
        assertThrows(IOException.class, () -> client(target("http://crawl-fixture.test/")));
        assertTrue(sockets.isEmpty());
    }
    @Test public void transportDoesNotRetryFailedResponse() throws Exception {
        final String url = "http://crawl-fixture.test/robots.txt";
        response = "";
        try (var client = client(target(url), ip("8.8.8.8"))) {
            assertThrows(IOException.class, () -> read(client,url));
        }
        assertEquals(1,attempts.size());
    }
    @Test public void actualHttpClientKeepsHostAndRejectsReuse() throws Exception {
        final String url = "http://crawl-fixture.test:8081/robots.txt";
        final MultiProtocolURL target = target(url);
        final InetAddress[] addresses = {ip("8.8.8.8")};
        try (var client = new HTTPClient(net.yacy.cora.protocol.ClientIdentification.yacyInternetCrawlerAgent)) {
            client.pinRobotsTarget(target, addresses);
            // Replace socket factories only, after exercising the real public binding entry point.
            final var managerField = HTTPClient.class.getDeclaredField("robotsManager");
            final var builderField = HTTPClient.class.getDeclaredField("clientBuilder");
            managerField.setAccessible(true); builderField.setAccessible(true);
            ((org.apache.http.impl.conn.PoolingHttpClientConnectionManager)managerField.get(client)).close();
            managerField.set(client, PinnedRobotsTransport.configure((HttpClientBuilder)builderField.get(client),
                    target, addresses, plain(), tls(target)));
            assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), client.GETbytes(target,"admin","secret",200,false));
            assertThrows(IOException.class, () -> client.GETbytes(target,"admin","secret",200,false));
        }
        assertEquals(1,attempts.size());
        assertTrue(sockets.get(0).sent.toString(StandardCharsets.ISO_8859_1)
                .contains("Host: crawl-fixture.test:8081\r\n"));
        assertTrue(sockets.get(0).closed);
    }

    private final class FakeSocket extends Socket {
        InetSocketAddress remote;
        boolean closed;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        @Override public void connect(SocketAddress address, int timeout) throws IOException {
            remote = (InetSocketAddress) address; attempts.add(remote);
            if (unreachable.contains(remote.getAddress())) throw new ConnectException("fake unreachable");
        }
        @Override public void bind(SocketAddress address) { }
        @Override public boolean isConnected() { return remote != null; }
        @Override public boolean isClosed() { return closed; }
        @Override public SocketAddress getRemoteSocketAddress() { return peerOverride == null ? remote : peerOverride; }
        @Override public InetAddress getInetAddress() { return remote.getAddress(); }
        @Override public int getPort() { return remote.getPort(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response.getBytes(StandardCharsets.ISO_8859_1)); }
        @Override public OutputStream getOutputStream() { return sent; }
        @Override public void setSoTimeout(int timeout) { }
        @Override public int getSoTimeout() { return 0; }
        @Override public void setTcpNoDelay(boolean on) { }
        @Override public void setKeepAlive(boolean on) { }
        @Override public void setReuseAddress(boolean on) { }
        @Override public void setSoLinger(boolean on,int linger) { }
        @Override public void shutdownOutput() { }
        @Override public void shutdownInput() { }
        @Override public void close() { closed = true; }
    }
    private final class FakeTLS extends SSLSocket {
        final FakeSocket socket;
        SSLParameters parameters = new SSLParameters();
        FakeTLS(FakeSocket socket) { this.socket=socket; }
        @Override public void startHandshake() throws IOException { if (untrusted) throw new SSLHandshakeException("fake untrusted certificate"); }
        @Override public SSLSession getSession() {
            return (SSLSession) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SSLSession.class},(proxy,method,args)-> {
                if (method.getName().equals("getPeerCertificates")) {
                    try (var input = new FileInputStream("test/java/net/yacy/cora/protocol/http/robots-fixture.pem")) {
                        return new Certificate[]{CertificateFactory.getInstance("X.509").generateCertificate(input)};
                    }
                }
                if (method.getName().equals("getCipherSuite")) return "TLS_FAKE";
                if (method.getName().equals("getProtocol")) return "TLSv1.3";
                return null;
            });
        }
        @Override public SSLParameters getSSLParameters() { return parameters; }
        @Override public void setSSLParameters(SSLParameters value) { parameters=value; }
        @Override public SocketAddress getRemoteSocketAddress() { return socket.getRemoteSocketAddress(); }
        @Override public InetAddress getInetAddress() { return socket.getInetAddress(); }
        @Override public int getPort() { return socket.getPort(); }
        @Override public InputStream getInputStream() { return socket.getInputStream(); }
        @Override public OutputStream getOutputStream() { return socket.getOutputStream(); }
        @Override public void setSoTimeout(int t) { }
        @Override public int getSoTimeout() { return 0; }
        @Override public void setTcpNoDelay(boolean on) { }
        @Override public boolean isClosed() { return socket.closed; }
        @Override public void shutdownOutput() { }
        @Override public void shutdownInput() { }
        @Override public void close() { socket.close(); }
        @Override public String[] getSupportedCipherSuites() { return new String[]{"TLS_FAKE"}; }
        @Override public String[] getEnabledCipherSuites() { return getSupportedCipherSuites(); }
        @Override public void setEnabledCipherSuites(String[] names) { }
        @Override public String[] getSupportedProtocols() { return new String[]{"TLSv1.3"}; }
        @Override public String[] getEnabledProtocols() { return getSupportedProtocols(); }
        @Override public void setEnabledProtocols(String[] names) { }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener l) { }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener l) { }
        @Override public void setUseClientMode(boolean mode) { }
        @Override public boolean getUseClientMode() { return true; }
        @Override public void setNeedClientAuth(boolean need) { }
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean want) { }
        @Override public boolean getWantClientAuth() { return false; }
        @Override public void setEnableSessionCreation(boolean flag) { }
        @Override public boolean getEnableSessionCreation() { return true; }
    }
}
