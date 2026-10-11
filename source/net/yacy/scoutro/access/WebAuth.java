/*
 *  WebAuth
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

/**
 * HTTP details of Scoutro sessions: the cookie, the CSRF checks, and the
 * redirect of browser navigations to the login page with a safe return path.
 */
public final class WebAuth {

    public static final String COOKIE = "scoutro_session";
    public static final String CSRF_HEADER = "X-Scoutro-CSRF";
    static final int MAX_NEXT = 1024;

    private WebAuth() {
    }

    // ------------------------------------------------------------------
    // cookie
    // ------------------------------------------------------------------

    /** The session cookie value, or null. With several cookies of that name the first well-formed one counts. */
    public static String cookieValue(final HttpServletRequest request) {
        final Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (final Cookie c : cookies) {
            if (COOKIE.equals(c.getName()) && c.getValue() != null && c.getValue().matches("[A-Za-z0-9_-]{40,64}")) {
                return c.getValue();
            }
        }
        return null;
    }

    /** {@code Set-Cookie} value for a new session (a browser-session cookie; the server enforces expiry). */
    public static String setCookie(final String value, final HttpServletRequest request, final AccessSettings settings) {
        return COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=" + settings.sameSite()
                + (secure(request, settings) ? "; Secure" : "");
    }

    /** {@code Set-Cookie} value that removes the session cookie. */
    public static String clearCookie(final HttpServletRequest request, final AccessSettings settings) {
        return COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=" + settings.sameSite()
                + (secure(request, settings) ? "; Secure" : "");
    }

    static boolean secure(final HttpServletRequest request, final AccessSettings settings) {
        switch (settings.secure()) {
            case "always":
                return true;
            case "never":
                return false;
            default:
                if (request.isSecure()) {
                    return true;
                }
                final String proto = request.getHeader("X-Forwarded-Proto");
                // setting Secure on a spoofed header only hurts the spoofing client
                return proto != null && proto.trim().toLowerCase(Locale.ROOT).startsWith("https");
        }
    }

    // ------------------------------------------------------------------
    // CSRF
    // ------------------------------------------------------------------

    public static boolean unsafeMethod(final String method) {
        return !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
    }

    /** The CSRF header of the request matches the session's token (constant time). */
    public static boolean csrfHeaderMatches(final HttpServletRequest request, final SessionStore.Session session) {
        final String presented = request.getHeader(CSRF_HEADER);
        return presented != null && session != null && MessageDigest.isEqual(
                presented.trim().getBytes(StandardCharsets.US_ASCII), session.csrf.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * A browser request from another site. {@code Sec-Fetch-Site} decides when the
     * browser sends it; otherwise a present {@code Origin} must match the
     * {@code Host}. Requests without either header (non-browser clients) are not cross-site.
     */
    public static boolean crossSite(final HttpServletRequest request) {
        final String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.isEmpty()) {
            final String v = site.trim().toLowerCase(Locale.ROOT);
            return !"same-origin".equals(v) && !"none".equals(v);
        }
        final String origin = request.getHeader("Origin");
        if (origin == null || origin.isEmpty()) {
            return false;
        }
        if ("null".equals(origin)) {
            return true;
        }
        try {
            final String authority = new URI(origin.trim()).getAuthority();
            final String host = request.getHeader("Host");
            return authority == null || host == null || !authority.equalsIgnoreCase(host.trim());
        } catch (final Exception e) {
            return true;
        }
    }

    /**
     * CSRF rule for a request authenticated by a session cookie: Scoutro API
     * calls that change something need the session's token in
     * {@value #CSRF_HEADER}; other changing requests (old YaCy forms, which
     * keep their transaction tokens) must not come from another site.
     */
    public static boolean csrfAllowed(final HttpServletRequest request, final SessionStore.Session session) {
        if (!unsafeMethod(request.getMethod())) {
            return true;
        }
        final String path = path(request);
        if (path.startsWith("/scoutro/")) {
            return csrfHeaderMatches(request, session) && !crossSite(request);
        }
        return !crossSite(request);
    }

    // ------------------------------------------------------------------
    // login redirect
    // ------------------------------------------------------------------

    /**
     * True for a top-level browser navigation that should see the login page
     * instead of a Digest challenge: GET/HEAD, HTML accepted, no
     * {@code Authorization} header, a page path (not an API or protocol path).
     * Tools and scripts never send {@code Accept: text/html} first, so they
     * keep the Digest challenge.
     */
    public static boolean browserNavigation(final HttpServletRequest request) {
        final String method = request.getMethod();
        if (!("GET".equals(method) || "HEAD".equals(method))) {
            return false;
        }
        final String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isEmpty()) {
            return false;
        }
        final String accept = request.getHeader("Accept");
        if (accept == null || !accept.toLowerCase(Locale.ROOT).contains("text/html")) {
            return false;
        }
        final String dest = request.getHeader("Sec-Fetch-Dest");
        if (dest != null && !dest.isEmpty() && !"document".equalsIgnoreCase(dest) && !"iframe".equalsIgnoreCase(dest)) {
            return false;
        }
        final String path = path(request);
        if (path.startsWith("/scoutro/api/") || path.startsWith("/api/") || path.startsWith("/solr/")
                || path.startsWith("/yacy/") || path.startsWith("/v1/") || path.startsWith("/tools")) {
            return false;
        }
        final int dot = path.lastIndexOf('.');
        if (dot > path.lastIndexOf('/')) {
            final String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            return "html".equals(ext) || "htm".equals(ext);
        }
        return true; // a directory such as "/"
    }

    /** The login page URL with the requested page as return target. */
    public static String loginUrl(final HttpServletRequest request, final boolean changePassword) {
        final String next = safeNext(rawTarget(request));
        final StringBuilder sb = new StringBuilder(RoutePolicy.LOGIN_PAGE);
        sb.append(changePassword ? "?change=1&next=" : "?next=");
        sb.append(URLEncoder.encode(next, StandardCharsets.UTF_8));
        return sb.toString();
    }

    /**
     * A return target that stays on this server: a path starting with one
     * slash, without scheme, backslash, control characters or a second slash
     * at the start; otherwise the overview.
     */
    public static String safeNext(final String next) {
        final String fallback = "/scoutro-dashboard.html";
        if (next == null || next.isEmpty() || next.length() > MAX_NEXT) {
            return fallback;
        }
        if (next.charAt(0) != '/' || next.startsWith("//") || next.startsWith("/\\") || next.indexOf('\\') >= 0) {
            return fallback;
        }
        for (int i = 0; i < next.length(); i++) {
            final char c = next.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return fallback;
            }
        }
        if (next.startsWith(RoutePolicy.LOGIN_PAGE)) {
            return fallback;
        }
        try {
            final URI u = new URI(next);
            if (u.getScheme() != null || u.getAuthority() != null) {
                return fallback;
            }
        } catch (final Exception e) {
            return fallback;
        }
        return next;
    }

    /**
     * The decoded request path (context "/"). The security handler runs before
     * the servlet mapping, so servlet path and path info are not set yet.
     */
    static String path(final HttpServletRequest request) {
        final String raw = request.getRequestURI() == null ? "/" : request.getRequestURI();
        try {
            final String decoded = new URI(raw).getPath();
            return decoded == null || decoded.isEmpty() ? "/" : decoded;
        } catch (final Exception e) {
            return "/scoutro/invalid"; // undecodable: treated with the strictest rules
        }
    }

    /** Path plus query exactly as requested, for the return target. */
    static String rawTarget(final HttpServletRequest request) {
        final String raw = request.getRequestURI() == null ? "/" : request.getRequestURI();
        final String query = request.getQueryString();
        return raw + (query == null || query.isEmpty() ? "" : "?" + query);
    }
}
