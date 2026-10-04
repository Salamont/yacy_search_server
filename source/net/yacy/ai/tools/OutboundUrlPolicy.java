/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.tools;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

import com.google.common.net.InetAddresses;

import net.yacy.cora.document.id.MultiProtocolURL;

/**
 * Network policy for outbound requests of the LLM tools ({@code http_json}, {@code webfetch}).
 * Only public http(s) destinations pass:
 * <ul>
 *   <li>scheme http or https, no credentials in the URL;</li>
 *   <li>no local or cluster-internal host names (single-label names such as Kubernetes
 *       services, {@code localhost}, {@code .local}, {@code .internal}, {@code .svc},
 *       {@code .cluster.local}, ...);</li>
 *   <li>every DNS answer must be a public unicast address: loopback, RFC 1918, shared
 *       (100.64/10), link-local (incl. cloud metadata 169.254.169.254), multicast,
 *       unspecified, reserved, documentation, ULA (fc00::/7, e.g. fd00:ec2::254) and
 *       transition prefixes are blocked.</li>
 * </ul>
 * The policy is applied before connecting and again to every redirect target. The
 * validated addresses are then the only ones a connection may use ({@link PinnedHttpGet}),
 * so a second DNS answer (DNS rebinding) cannot redirect the connection.
 * It deliberately does not depend on YaCy's network mode: tools are never allowed to
 * reach local resources, also not on an intranet peer.
 */
public final class OutboundUrlPolicy {

    /** DNS lookup, replaceable in tests. */
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** A request target that is not allowed; {@link #reason} is a short code. */
    public static final class Blocked extends IOException {
        private static final long serialVersionUID = 1L;
        public final String reason;

        Blocked(final String reason, final String message) {
            super(message);
            this.reason = reason;
        }
    }

    /** A validated target with the only addresses a connection may use. */
    public static final class Target {
        public final URI uri;
        public final String scheme;
        public final String host;
        public final int port;
        private final InetAddress[] addresses;

        Target(final URI uri, final String scheme, final String host, final int port, final InetAddress[] addresses) {
            this.uri = uri;
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.addresses = addresses.clone();
        }

        public InetAddress[] addresses() {
            return this.addresses.clone();
        }
    }

    static final int MAX_URL_LENGTH = 2048;

    private static final String[] INTERNAL_SUFFIXES = {
        ".localhost", ".local", ".localdomain", ".internal", ".intranet", ".lan", ".home", ".corp",
        ".home.arpa", ".in-addr.arpa", ".ip6.arpa", ".svc", ".cluster.local", ".onion", ".test", ".invalid"
    };

    public static final OutboundUrlPolicy DEFAULT = new OutboundUrlPolicy(InetAddress::getAllByName);

    private final Resolver resolver;

    public OutboundUrlPolicy(final Resolver resolver) {
        this.resolver = resolver;
    }

    /** Validate a URL including its DNS answers. */
    public Target check(final String url) throws Blocked {
        if (url == null || url.trim().isEmpty()) throw new Blocked("invalid_url", "missing URL");
        if (url.length() > MAX_URL_LENGTH) throw new Blocked("invalid_url", "URL too long");
        final URI uri;
        try {
            // YaCy's lenient parser normalizes the text (spaces, IDN); java.net.URI is the strict final form
            uri = new URI(new MultiProtocolURL(url.trim()).toNormalform(true));
        } catch (final MalformedURLException | URISyntaxException | RuntimeException e) {
            throw new Blocked("invalid_url", "invalid URL");
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new Blocked("scheme_not_allowed", "only http and https URLs are allowed");
        }
        if (uri.getRawUserInfo() != null) throw new Blocked("credentials_not_allowed", "credentials in the URL are not allowed");
        final String host = canonicalHost(uri.getHost());
        if (host.isEmpty()) throw new Blocked("invalid_url", "URL without host");
        final int port = uri.getPort() < 0 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        if (port < 1 || port > 65535) throw new Blocked("invalid_url", "invalid port");
        if (isInternalName(host)) throw new Blocked("internal_host", "local or cluster-internal host names are not allowed");

        final InetAddress[] addresses;
        if (InetAddresses.isInetAddress(host)) {
            addresses = new InetAddress[] {InetAddresses.forString(host)};
        } else {
            try {
                addresses = this.resolver.resolve(host);
            } catch (final UnknownHostException e) {
                throw new Blocked("dns_failed", "host name could not be resolved");
            }
        }
        if (addresses == null || addresses.length == 0) throw new Blocked("dns_failed", "host name could not be resolved");
        for (final InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new Blocked("non_public_address", "the host resolves to a local, private or reserved address");
            }
        }
        return new Target(uri, scheme, host, port, addresses);
    }

    static String canonicalHost(final String raw) {
        if (raw == null) return "";
        String host = raw.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }

    /** Host names that address local or cluster-internal services. IP literals are checked by address. */
    static boolean isInternalName(final String host) {
        if (InetAddresses.isInetAddress(host)) return false;
        if (host.indexOf('.') < 0) return true; // localhost, Kubernetes service names, intranet hosts
        if (host.startsWith("kubernetes.default") || host.startsWith("metadata.")) return true;
        for (final String suffix : INTERNAL_SUFFIXES) {
            if (host.endsWith(suffix)) return true;
        }
        return false;
    }

    /** Public unicast address that the tools may connect to. */
    public static boolean isPublicAddress(final InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        final byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            final int b0 = b[0] & 0xff, b1 = b[1] & 0xff, b2 = b[2] & 0xff, b3 = b[3] & 0xff;
            if (b0 == 0 || b0 == 10 || b0 == 127 || b0 >= 224) return false;           // this network, private, loopback, multicast/reserved/broadcast
            if (b0 == 100 && (b1 & 0xc0) == 64) return false;                            // 100.64.0.0/10 shared address space (CGNAT, Alibaba metadata)
            if (b0 == 169 && b1 == 254) return false;                                    // link-local, cloud metadata
            if (b0 == 172 && (b1 & 0xf0) == 16) return false;                            // 172.16.0.0/12
            if (b0 == 192 && b1 == 168) return false;                                    // 192.168.0.0/16
            if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) return false;              // IETF protocol assignments (Oracle metadata 192.0.0.192), TEST-NET-1
            if (b0 == 192 && b1 == 88 && b2 == 99) return false;                         // 6to4 relay anycast
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;                       // benchmarking
            if (b0 == 198 && b1 == 51 && b2 == 100) return false;                        // TEST-NET-2
            if (b0 == 203 && b1 == 0 && b2 == 113) return false;                         // TEST-NET-3
            if (b0 == 168 && b1 == 63 && b2 == 129 && b3 == 16) return false;            // Azure host services (WireServer)
            return true;
        }
        if (address instanceof Inet6Address) {
            if (((Inet6Address) address).isIPv4CompatibleAddress()) return false;
            final int b0 = b[0] & 0xff, b1 = b[1] & 0xff, b2 = b[2] & 0xff, b3 = b[3] & 0xff;
            if ((b0 & 0xe0) != 0x20) return false;                                       // only global unicast 2000::/3 (excludes ::, ::1, ::ffff:0:0/96, 64:ff9b::/96, fc00::/7, fe80::/10, ff00::/8)
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) return false;     // Teredo 2001::/32
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x02 && b[4] == 0 && b[5] == 0) return false; // benchmarking 2001:2::/48
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && ((b3 & 0xf0) == 0x10 || (b3 & 0xf0) == 0x20)) return false; // ORCHID
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0d && b3 == 0xb8) return false;     // documentation 2001:db8::/32
            if (b0 == 0x20 && b1 == 0x02) return false;                                  // 6to4 2002::/16 (embeds IPv4)
            if (b0 == 0x3f && b1 == 0xff && (b2 & 0xf0) == 0) return false;             // documentation 3fff::/20
            return true;
        }
        return false;
    }
}
