/*
 *  SignInClient
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.access;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import com.google.common.net.InetAddresses;

import net.yacy.cora.protocol.RequestHeader;
import net.yacy.http.ProxyAccessPolicy;

/**
 * The client of a sign-in as the sign-in limits and the audit count it
 * (docs/SCOUTRO_USERS_ACCESS.md, "Sign-in limits").
 * <p>
 * Only the socket peer is certain. A peer that matches
 * {@code server.reverseProxy.trusted} is a reverse proxy whose clients cannot
 * be told apart reliably: a client can send {@code X-Real-IP} or
 * {@code X-Forwarded-For} itself, and a proxy that passes such a header on
 * would let every attempt choose its own limit bucket. For the Olares entrance
 * chain it is not shown that the client's header is replaced. All requests
 * from one trusted proxy therefore share one bucket,
 * {@code proxy:<address of the proxy>}; the per-name limit and the limit of
 * parallel password checks apply as before.
 * <p>
 * Only the explicit setting {@code scoutro.auth.limits.proxyAddress=x-real-ip},
 * for a proxy shown to replace {@code X-Real-IP} with the address it received
 * the connection from, uses exactly one valid {@code X-Real-IP} of a trusted
 * proxy instead. {@code X-Forwarded-For} is never used. YaCy's effective client
 * address ({@link RequestHeader#client}) is unchanged and not used here.
 */
public final class SignInClient {

    /** Prefix of the shared bucket of all sign-ins that arrive through one trusted reverse proxy. */
    public static final String PROXY_PREFIX = "proxy:";

    private SignInClient() {
    }

    /** The limit and audit client of this request; without settings only the socket peer counts. */
    public static String of(final HttpServletRequest request, final AccessSettings settings) {
        if (settings == null) {
            return canonical(request.getRemoteAddr());
        }
        final List<String> realIp = new ArrayList<>();
        final Enumeration<String> values = request.getHeaders(RequestHeader.X_Real_IP);
        while (values != null && values.hasMoreElements()) {
            realIp.add(values.nextElement());
        }
        return key(request.getRemoteAddr(), realIp, settings.trustedProxies(), settings.proxyRealIp());
    }

    /**
     * @param socketPeer the address of the connection
     * @param realIp all {@code X-Real-IP} header values of the request
     * @param trustedProxies {@code server.reverseProxy.trusted} as configured (matched like YaCy's own resolution)
     * @param useRealIp {@code scoutro.auth.limits.proxyAddress=x-real-ip}
     */
    static String key(final String socketPeer, final List<String> realIp, final String trustedProxies,
            final boolean useRealIp) {
        final String peer = canonical(socketPeer);
        if (!ProxyAccessPolicy.isClientAllowed(trustedProxies, socketPeer)) {
            return peer;
        }
        if (useRealIp && realIp.size() == 1) {
            // a second value means the client's own header came through next to the proxy's
            final String candidate = realIp.get(0) == null ? "" : realIp.get(0).trim();
            if (InetAddresses.isInetAddress(candidate)) {
                return canonical(candidate);
            }
        }
        return PROXY_PREFIX + peer;
    }

    static String canonical(final String address) {
        if (address == null) {
            return "";
        }
        final String a = address.trim();
        return InetAddresses.isInetAddress(a) ? InetAddresses.toAddrString(InetAddresses.forString(a)) : a;
    }
}
