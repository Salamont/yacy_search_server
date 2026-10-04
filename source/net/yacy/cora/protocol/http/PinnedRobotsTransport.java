/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.cora.protocol.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SNIHostName;
import java.util.Collections;
import com.google.common.net.InetAddresses;

import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;

import net.yacy.cora.document.id.MultiProtocolURL;

/** A fresh, single-request connector. DNS cannot choose a new address after policy validation. */
final class PinnedRobotsTransport {
    private PinnedRobotsTransport() { }

    static String canonicalHost(final String value) {
        return value.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
    }

    static PoolingHttpClientConnectionManager configure(final HttpClientBuilder builder,
            final MultiProtocolURL target, final InetAddress[] validated) throws IOException {
        // Unlike the general crawler's permissive TLS factory, robots keeps trust and hostname checks.
        try {
            return configure(builder, target, validated, PlainConnectionSocketFactory.getSocketFactory(),
                    strictTls(target, SSLContext.getDefault().getSocketFactory()));
        } catch (final java.security.NoSuchAlgorithmException failure) {
            throw new IOException("robots TLS context unavailable", failure);
        }
    }

    static SSLConnectionSocketFactory strictTls(final MultiProtocolURL target, final SSLSocketFactory sockets) {
        return new SSLConnectionSocketFactory(sockets, SSLConnectionSocketFactory.getDefaultHostnameVerifier()) {
            @Override protected void prepareSocket(final SSLSocket socket) {
                final SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                final String host = canonicalHost(target.getHost());
                if (!InetAddresses.isInetAddress(host)) {
                    parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
                }
                socket.setSSLParameters(parameters);
            }
        };
    }

    // Factories are injected only by offline tests; the real Apache connection operator still runs.
    static PoolingHttpClientConnectionManager configure(final HttpClientBuilder builder,
            final MultiProtocolURL target, final InetAddress[] validated,
            final ConnectionSocketFactory plain, final ConnectionSocketFactory tls) throws IOException {
        if (validated == null || validated.length == 0 || Arrays.stream(validated).anyMatch(a -> a == null)) {
            throw new IOException("robots target has no validated addresses");
        }
        final InetAddress[] snapshot = validated.clone();
        final String host = canonicalHost(target.getHost());
        final String scheme = target.getProtocol();
        final int port = target.getPort();
        final PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager(
                RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("http", checked(plain, snapshot, port))
                        .register("https", checked(tls, snapshot, port)).build(),
                name -> {
                    if (!host.equals(canonicalHost(name))) throw new UnknownHostException("robots target changed");
                    return snapshot.clone();
                });
        manager.setMaxTotal(1);
        manager.setDefaultMaxPerRoute(1);
        builder.setConnectionManager(manager).setConnectionManagerShared(false)
                .setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE)
                .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                .setDefaultCredentialsProvider(new BasicCredentialsProvider())
                .setRoutePlanner((destination, request, context) -> {
                    final int destinationPort = destination.getPort() < 0
                            ? ("https".equals(destination.getSchemeName()) ? 443 : 80) : destination.getPort();
                    if (!host.equals(canonicalHost(destination.getHostName()))
                            || port != destinationPort || !scheme.equals(destination.getSchemeName())) {
                        throw new HttpException("robots target changed");
                    }
                    final HttpRoute configured = ProxySettings.RoutePlanner.determineRoute(destination, request, context);
                    if (configured.getProxyHost() != null
                            || HttpClientContext.adapt(context).getRequestConfig().getProxy() != null) {
                        // A remote proxy can resolve the hostname independently. Do not silently bypass it.
                        throw new HttpException("robots pinned transport does not support proxies");
                    }
                    return new HttpRoute(destination, null, "https".equals(scheme));
                });
        return manager;
    }

    private static ConnectionSocketFactory checked(final ConnectionSocketFactory delegate,
            final InetAddress[] snapshot, final int port) {
        return new ConnectionSocketFactory() {
            @Override public Socket createSocket(final HttpContext context) throws IOException {
                return delegate.createSocket(context);
            }
            @Override public Socket connectSocket(final int timeout, final Socket socket, final HttpHost host,
                    final InetSocketAddress remote, final InetSocketAddress local, final HttpContext context)
                    throws IOException {
                if (remote.isUnresolved() || remote.getPort() != port
                        || !Arrays.asList(snapshot).contains(remote.getAddress())) {
                    socket.close();
                    throw new IOException("robots connection outside validated addresses");
                }
                final Socket connected = delegate.connectSocket(timeout, socket, host, remote, local, context);
                if (!remote.equals(connected.getRemoteSocketAddress())) {
                    connected.close();
                    throw new IOException("robots connected peer differs from validated address");
                }
                return connected;
            }
        };
    }
}
