/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.http;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javax.servlet.http.HttpServletRequest;

import com.google.common.net.InetAddresses;

/**
 * Client address of one request, resolved from the socket peer and, only when
 * that peer is a configured trusted reverse proxy ({@code server.reverseProxy.trusted}),
 * from {@code X-Real-IP} or {@code X-Forwarded-For}.
 * <p>
 * Rules (shared by the AI Shield and the LLM admin proxy):
 * <ul>
 *   <li>Forwarding headers from an untrusted peer are ignored; the peer address is the client.</li>
 *   <li>A trusted peer's {@code X-Real-IP} (one valid address) is used first, otherwise the
 *       right-most {@code X-Forwarded-For} entry that is not itself a trusted proxy.</li>
 *   <li>{@link #isLocal()} is true only for a direct loopback connection that carries no
 *       forwarding header at all. A proxied request is never local, whatever address the
 *       headers name, so neither a proxy on loopback nor a forged header turns a remote
 *       client into a local one.</li>
 * </ul>
 * The resolved address is used for rate limits and logs. It never grants rights by itself.
 */
public final class ClientAddress {

    public static final String X_REAL_IP = "X-Real-IP";
    public static final String X_FORWARDED_FOR = "X-Forwarded-For";
    public static final String FORWARDED = "Forwarded";

    /** How the effective address was obtained. */
    public enum Source {
        /** direct connection, no forwarding header */
        DIRECT,
        /** forwarding headers present, but the peer is not a trusted proxy: headers ignored */
        UNTRUSTED_PROXY,
        /** from X-Real-IP of a trusted proxy */
        X_REAL_IP,
        /** from X-Forwarded-For of a trusted proxy */
        X_FORWARDED_FOR,
        /** trusted proxy, but no usable forwarded address: the peer address is used */
        UNRESOLVED
    }

    private final String socketPeer;
    private final String effective;
    private final boolean proxied;
    private final boolean peerTrusted;
    private final Source source;

    private ClientAddress(final String socketPeer, final String effective, final boolean proxied,
            final boolean peerTrusted, final Source source) {
        this.socketPeer = socketPeer;
        this.effective = effective;
        this.proxied = proxied;
        this.peerTrusted = peerTrusted;
        this.source = source;
    }

    /** The TCP peer address of the connection. */
    public String socketPeer() {
        return this.socketPeer;
    }

    /** The client address for rate limits and logs. */
    public String effective() {
        return this.effective;
    }

    /** True when the request carries any forwarding header (trusted or not). */
    public boolean isProxied() {
        return this.proxied;
    }

    public boolean isPeerTrusted() {
        return this.peerTrusted;
    }

    public Source source() {
        return this.source;
    }

    /** Direct loopback connection without any forwarding header. */
    public boolean isLocal() {
        return !this.proxied && isLoopback(this.socketPeer);
    }

    @Override
    public String toString() {
        return "source=" + this.source.name().toLowerCase(Locale.ROOT) + " peerTrusted=" + this.peerTrusted;
    }

    /** Resolve the client of a servlet request. */
    public static ClientAddress of(final HttpServletRequest request, final String trustedProxyPatterns) {
        return resolve(request.getRemoteAddr(), name -> {
            final List<String> values = new ArrayList<>();
            if (request.getHeaders(name) != null) values.addAll(Collections.list(request.getHeaders(name)));
            return values;
        }, trustedProxyPatterns);
    }

    /**
     * Resolve the client from the socket peer and the request headers.
     *
     * @param socketPeer the TCP peer address
     * @param headers all values of a request header by name (never null; empty when absent)
     * @param trustedProxyPatterns comma-separated regular expressions of trusted proxy addresses
     */
    public static ClientAddress resolve(final String socketPeer, final Function<String, List<String>> headers,
            final String trustedProxyPatterns) {
        final String peer = normalize(socketPeer);
        final List<String> realIp = present(headers.apply(X_REAL_IP));
        final List<String> forwardedFor = present(headers.apply(X_FORWARDED_FOR));
        final boolean proxied = !realIp.isEmpty() || !forwardedFor.isEmpty()
                || !present(headers.apply(FORWARDED)).isEmpty();
        final boolean peerTrusted = peer != null && isTrusted(trustedProxyPatterns, peer);
        if (!proxied) return new ClientAddress(peer, peer, false, peerTrusted, Source.DIRECT);
        if (!peerTrusted) return new ClientAddress(peer, peer, true, false, Source.UNTRUSTED_PROXY);

        // X-Real-IP: exactly one header with exactly one valid address.
        if (realIp.size() == 1 && realIp.get(0).indexOf(',') < 0) {
            final String address = normalize(realIp.get(0));
            if (address != null) return new ClientAddress(peer, address, true, true, Source.X_REAL_IP);
        }

        // X-Forwarded-For: walk from the right, skip trusted proxies, the first other address is the client.
        final List<String> chain = new ArrayList<>();
        for (final String value : forwardedFor) {
            for (final String part : value.split(",")) chain.add(part);
        }
        String leftmostTrusted = null;
        boolean broken = false;
        for (int i = chain.size() - 1; i >= 0; i--) {
            final String address = normalize(chain.get(i));
            if (address == null) { // a broken chain is not followed any further
                broken = true;
                break;
            }
            if (!isTrusted(trustedProxyPatterns, address)) {
                return new ClientAddress(peer, address, true, true, Source.X_FORWARDED_FOR);
            }
            leftmostTrusted = address;
        }
        if (!broken && leftmostTrusted != null) {
            // every hop is a trusted proxy: the left-most is the origin, but it stays a proxied (non-local) request
            return new ClientAddress(peer, leftmostTrusted, true, true, Source.X_FORWARDED_FOR);
        }
        return new ClientAddress(peer, peer, true, true, Source.UNRESOLVED);
    }

    private static List<String> present(final List<String> values) {
        final List<String> result = new ArrayList<>();
        if (values == null) return result;
        for (final String value : values) {
            if (value != null && !value.trim().isEmpty()) result.add(value.trim());
        }
        return result;
    }

    /** Canonical address text, or null when the value is not one IPv4/IPv6 address (ports are removed). */
    static String normalize(final String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) value = value.substring(1, value.length() - 1).trim();
        if (value.startsWith("[")) {
            final int end = value.indexOf(']');
            if (end < 0) return null;
            value = value.substring(1, end);
        } else if (value.indexOf(':') > 0 && value.indexOf(':') == value.lastIndexOf(':') && value.indexOf('.') > 0) {
            value = value.substring(0, value.indexOf(':')); // IPv4 with port
        }
        final int zone = value.indexOf('%');
        if (zone >= 0) value = value.substring(0, zone);
        if (!InetAddresses.isInetAddress(value)) return null;
        return InetAddresses.toAddrString(InetAddresses.forString(value));
    }

    static boolean isLoopback(final String address) {
        final String canonical = normalize(address);
        return canonical != null && InetAddresses.forString(canonical).isLoopbackAddress();
    }

    /** Match the configured patterns against the compressed and the full address text. */
    static boolean isTrusted(final String patterns, final String address) {
        final String canonical = normalize(address);
        if (canonical == null) return false;
        if (ProxyAccessPolicy.isClientAllowed(patterns, canonical)) return true;
        final InetAddress parsed = InetAddresses.forString(canonical);
        return ProxyAccessPolicy.isClientAllowed(patterns, parsed.getHostAddress());
    }
}
