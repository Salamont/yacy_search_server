/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;

/**
 * HTTP GET for the LLM tools, bound to {@link OutboundUrlPolicy}.
 * <ul>
 *   <li>The URL and every redirect target are validated (scheme, host name, all DNS answers).</li>
 *   <li>A connection may only use the addresses validated for that hop (no second DNS lookup,
 *       the connected peer is checked), so DNS rebinding cannot reach another address.</li>
 *   <li>Redirects are followed here, at most {@link #MAX_REDIRECTS}, each as a new validated GET;
 *       a public URL redirecting to a private address is blocked.</li>
 *   <li>No proxy, no cookies, no stored credentials, no retries; TLS keeps the default trust
 *       store and host name verification; the response body is size-limited.</li>
 * </ul>
 */
public final class PinnedHttpGet {

    public static final int MAX_REDIRECTS = 5;

    /** Response of the last hop. */
    public static final class Result {
        public final int status;
        public final String contentType;
        public final byte[] body;
        public final String url;

        Result(final int status, final String contentType, final byte[] body, final String url) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.url = url;
        }
    }

    private final OutboundUrlPolicy policy;
    private final ConnectionSocketFactory plain;
    private final ConnectionSocketFactory tls;
    private final String userAgent;

    public PinnedHttpGet(final OutboundUrlPolicy policy, final String userAgent) {
        this(policy, userAgent, null, null);
    }

    /** Socket factories are injected only by offline tests; validation and pinning still run. */
    PinnedHttpGet(final OutboundUrlPolicy policy, final String userAgent,
            final ConnectionSocketFactory plain, final ConnectionSocketFactory tls) {
        this.policy = policy;
        this.userAgent = userAgent;
        this.plain = plain;
        this.tls = tls;
    }

    public Result get(final String url, final Map<String, String> headers, final int timeoutMs, final int maxBytes)
            throws IOException {
        String current = url;
        for (int hop = 0; ; hop++) {
            final OutboundUrlPolicy.Target target = this.policy.check(current);
            try (CloseableHttpClient client = client(target, timeoutMs)) {
                final HttpGet get = new HttpGet(target.uri);
                if (headers != null) {
                    for (final Map.Entry<String, String> header : headers.entrySet()) get.setHeader(header.getKey(), header.getValue());
                }
                try (CloseableHttpResponse response = client.execute(get)) {
                    final int status = response.getStatusLine().getStatusCode();
                    final Header location = response.getFirstHeader("Location");
                    if (isRedirect(status) && location != null && location.getValue() != null) {
                        if (hop >= MAX_REDIRECTS) throw new IOException("too many redirects");
                        try {
                            current = target.uri.resolve(location.getValue().trim()).toString();
                        } catch (final IllegalArgumentException e) {
                            throw new OutboundUrlPolicy.Blocked("invalid_url", "invalid redirect target");
                        }
                        continue; // the next hop is validated like the first one
                    }
                    final HttpEntity entity = response.getEntity();
                    final Header type = entity == null ? null : entity.getContentType();
                    final byte[] body = entity == null ? new byte[0] : readLimited(entity.getContent(), maxBytes);
                    return new Result(status, type == null ? "" : type.getValue(), body, target.uri.toString());
                }
            }
        }
    }

    static boolean isRedirect(final int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static byte[] readLimited(final InputStream stream, final int maxBytes) throws IOException {
        if (stream == null) return new byte[0];
        try (InputStream in = stream) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (out.size() + read > maxBytes) throw new IOException("response too large");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private CloseableHttpClient client(final OutboundUrlPolicy.Target target, final int timeoutMs) throws IOException {
        final InetAddress[] snapshot = target.addresses();
        final String host = target.host;
        final ConnectionSocketFactory plainFactory = this.plain == null ? PlainConnectionSocketFactory.getSocketFactory() : this.plain;
        ConnectionSocketFactory tlsFactory = this.tls;
        if (tlsFactory == null) {
            try {
                tlsFactory = new SSLConnectionSocketFactory(SSLContext.getDefault(), SSLConnectionSocketFactory.getDefaultHostnameVerifier());
            } catch (final NoSuchAlgorithmException e) {
                throw new IOException("TLS unavailable", e);
            }
        }
        final PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager(
                RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("http", pinned(plainFactory, snapshot, target.port))
                        .register("https", pinned(tlsFactory, snapshot, target.port)).build(),
                name -> {
                    if (!host.equals(OutboundUrlPolicy.canonicalHost(name))) throw new UnknownHostException("target host changed");
                    return snapshot.clone();
                });
        manager.setMaxTotal(1);
        manager.setDefaultMaxPerRoute(1);
        final RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutMs).setSocketTimeout(timeoutMs).setConnectionRequestTimeout(timeoutMs)
                .setRedirectsEnabled(false).setAuthenticationEnabled(false).build();
        final HttpClientBuilder builder = HttpClientBuilder.create()
                .setConnectionManager(manager).setConnectionManagerShared(false)
                .setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE)
                .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableAuthCaching()
                .setDefaultCredentialsProvider(new BasicCredentialsProvider())
                .setDefaultRequestConfig(config)
                .setRoutePlanner((destination, request, context) -> {
                    final int port = destination.getPort() < 0
                            ? ("https".equals(destination.getSchemeName()) ? 443 : 80) : destination.getPort();
                    if (!host.equals(OutboundUrlPolicy.canonicalHost(destination.getHostName()))
                            || port != target.port || !target.scheme.equals(destination.getSchemeName())) {
                        throw new HttpException("target changed");
                    }
                    return new HttpRoute(destination, null, "https".equals(target.scheme)); // direct, never a proxy
                });
        if (this.userAgent != null) builder.setUserAgent(this.userAgent);
        return builder.build();
    }

    /** Allow connections only to the validated addresses and verify the connected peer. */
    private static ConnectionSocketFactory pinned(final ConnectionSocketFactory delegate, final InetAddress[] snapshot,
            final int port) {
        return new ConnectionSocketFactory() {
            @Override
            public Socket createSocket(final HttpContext context) throws IOException {
                return delegate.createSocket(context);
            }

            @Override
            public Socket connectSocket(final int timeout, final Socket socket, final HttpHost host,
                    final InetSocketAddress remote, final InetSocketAddress local, final HttpContext context)
                    throws IOException {
                if (remote.isUnresolved() || remote.getPort() != port || !Arrays.asList(snapshot).contains(remote.getAddress())) {
                    if (socket != null) socket.close();
                    throw new IOException("connection outside the validated addresses");
                }
                final Socket connected = delegate.connectSocket(timeout, socket, host, remote, local, context);
                if (!remote.equals(connected.getRemoteSocketAddress())) {
                    connected.close();
                    throw new IOException("connected peer differs from the validated address");
                }
                return connected;
            }
        };
    }
}
