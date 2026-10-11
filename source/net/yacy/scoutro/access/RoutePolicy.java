/*
 *  RoutePolicy
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

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Container-level access rule of a request path (docs/SCOUTRO_USERS_ACCESS.md,
 * section 4). Pure function, free of servlet container classes; the Jetty
 * security handler turns a rule into a constraint.
 * <p>
 * In compatible mode only the Scoutro sign-in routes and the Scoutro pages
 * listed in {@link #PAGES} have own rules; every other path keeps YaCy's
 * rules ({@link Kind#LEGACY}). In protected mode the table is an allowlist:
 * a path that is not listed needs the administrator.
 */
public final class RoutePolicy {

    public enum Kind {
        /** YaCy's existing rules decide (compatible mode only, and peer-to-peer paths) */
        LEGACY,
        /** reachable without sign-in; the servlet authenticates itself if needed */
        PUBLIC,
        /** any signed-in Scoutro account or administrator; the servlet decides the details */
        SIGNED_IN,
        /** a signed-in identity with {@link Rule#permission}, or a guest where {@link Rule#guest} */
        PERMISSION,
        /** the YaCy administrator (Digest, administrator session or YaCy's local rules) */
        ADMIN
    }

    public static final class Rule {
        public final Kind kind;
        public final Permission permission;
        public final boolean guest;

        private Rule(final Kind kind, final Permission permission, final boolean guest) {
            this.kind = kind;
            this.permission = permission;
            this.guest = guest;
        }

        @Override
        public String toString() {
            return this.kind + (this.permission == null ? "" : ":" + this.permission.id()) + (this.guest ? "+guest" : "");
        }
    }

    public static final Rule LEGACY = new Rule(Kind.LEGACY, null, false);
    public static final Rule PUBLIC = new Rule(Kind.PUBLIC, null, false);
    public static final Rule SIGNED_IN = new Rule(Kind.SIGNED_IN, null, false);
    public static final Rule ADMIN = new Rule(Kind.ADMIN, null, false);

    public static Rule permission(final Permission p) {
        return new Rule(Kind.PERMISSION, p, false);
    }

    public static Rule permissionOrGuest(final Permission p) {
        return new Rule(Kind.PERMISSION, p, true);
    }

    public static final String LOGIN_PAGE = "/scoutro-login.html";
    public static final String ACCOUNT_PAGE = "/scoutro-account.html";
    /** Sign-in with the browser's Digest dialog: administrator only, never redirected to the login page. */
    public static final String DIGEST_PAGE = "/scoutro-digest.html";
    /** "No permission", also the container's error page for 403; shows no data. */
    public static final String FORBIDDEN_PAGE = "/scoutro-forbidden.html";

    /** Scoutro pages with their own rule in both modes. */
    static final Map<String, Rule> PAGES;
    static {
        final Map<String, Rule> m = new HashMap<>();
        m.put(LOGIN_PAGE, PUBLIC);
        m.put(ACCOUNT_PAGE, SIGNED_IN);
        m.put(DIGEST_PAGE, ADMIN);
        m.put(FORBIDDEN_PAGE, PUBLIC);
        PAGES = Collections.unmodifiableMap(m);
    }

    /** Sign-in routes of the API that answer anonymous callers themselves. */
    static final Set<String> PUBLIC_API = Set.of("/scoutro/api/v1/auth/login", "/scoutro/api/v1/auth/session",
            "/scoutro/api/v1/auth/logout", "/scoutro/api/v1/health", "/scoutro/api/openapi.json", "/scoutro/api/actions.json");

    static final Set<String> STATIC_EXTENSIONS = Set.of("css", "js", "mjs", "map", "png", "gif", "jpg", "jpeg", "svg",
            "ico", "webp", "woff", "woff2", "ttf", "eot");
    static final Set<String> STATIC_FILES = Set.of("/favicon.ico", "/favicon.png", "/favicon.bmp", "/robots.txt",
            "/jslicense.html", "/scoutro-about.html");

    private RoutePolicy() {
    }

    /**
     * @param path the request path inside the context, as the container matched it
     * @param protectedMode the effective access mode ({@link ScoutroAccess#protectedMode()})
     */
    public static Rule classify(final String path, final boolean protectedMode) {
        if (path == null || path.isEmpty() || suspicious(path)) {
            return protectedMode ? ADMIN : LEGACY;
        }
        final Rule page = PAGES.get(path);
        if (page != null) {
            return page;
        }
        if (PUBLIC_API.contains(path) || path.equals("/scoutro/api/v1/ui") || path.startsWith("/scoutro/api/v1/ui/")) {
            return PUBLIC;
        }
        if (path.startsWith("/scoutro/api/agent/")) {
            return PUBLIC; // authenticated by the servlet with agent tokens, never with a session
        }
        if (path.startsWith("/scoutro/api/v1/")) {
            return SIGNED_IN; // ScoutroApiServlet checks every route (deny by default)
        }
        if (!protectedMode) {
            return LEGACY;
        }
        if (STATIC_FILES.contains(path) || staticAsset(path)) {
            return PUBLIC;
        }
        if (path.startsWith("/yacy/")) {
            return LEGACY; // peer-to-peer protocol: YaCy's network rules decide
        }
        return ADMIN;
    }

    /** Paths that a normalizing container would not produce; never trusted by the allowlist. */
    static boolean suspicious(final String path) {
        return path.charAt(0) != '/' || path.contains("..") || path.contains("//") || path.indexOf('\\') >= 0
                || path.indexOf(';') >= 0 || path.indexOf('%') >= 0 || path.indexOf('\0') >= 0;
    }

    static boolean staticAsset(final String path) {
        if (!(path.startsWith("/env/") || path.startsWith("/js/") || path.startsWith("/jquery/"))) {
            return false;
        }
        if (path.startsWith("/env/templates/")) {
            return false; // server-side includes are not static files
        }
        final int dot = path.lastIndexOf('.');
        final int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            return false;
        }
        return STATIC_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
