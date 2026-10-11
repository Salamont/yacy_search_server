/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.access;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Container rules of the two access modes (docs/SCOUTRO_USERS_ACCESS.md, section 4). */
public class RoutePolicyTest {

    private static RoutePolicy.Kind kind(final String path, final boolean protectedMode) {
        return RoutePolicy.classify(path, protectedMode).kind;
    }

    @Test
    public void compatibleModeKeepsYacyRulesExceptForSignInRoutes() {
        for (final String p : new String[] {"/yacysearch.html", "/solr/select", "/suggest.json", "/tools/call",
                "/Status.html", "/ConfigAccounts_p.html", "/scoutro-dashboard.html", "/index.html", "/api/version.xml"}) {
            assertEquals(p, RoutePolicy.Kind.LEGACY, kind(p, false));
        }
        assertEquals(RoutePolicy.Kind.PUBLIC, kind("/scoutro-login.html", false));
        assertEquals(RoutePolicy.Kind.PUBLIC, kind("/scoutro/api/v1/auth/login", false));
        assertEquals(RoutePolicy.Kind.PUBLIC, kind("/scoutro/api/agent/v1/search", false));
        assertEquals(RoutePolicy.Kind.SIGNED_IN, kind("/scoutro/api/v1/users", false));
        assertEquals(RoutePolicy.Kind.SIGNED_IN, kind("/scoutro-account.html", false));
        assertEquals(RoutePolicy.Kind.ADMIN, kind("/scoutro-digest.html", false));
    }

    @Test
    public void protectedModeIsAnAllowlist() {
        for (final String p : new String[] {"/yacysearch.html", "/yacysearch.json", "/solr/select", "/solr/collection1/select",
                "/gsa/search", "/suggest.json", "/tools", "/tools/call", "/v1/chat/completions", "/v1/models", "/api/tags",
                "/Status.html", "/ViewFile.html", "/yacydoc.html", "/api/version.xml", "/index.html", "/",
                "/CrawlResults.html", "/ViewImage.png", "/env/templates/header.template", "/portalsearch/yacy-portalsearch.js",
                "/scoutro/api/whatever"}) {
            assertEquals(p, RoutePolicy.Kind.ADMIN, kind(p, true));
        }
        for (final String p : new String[] {"/env/scoutro/scoutro.css", "/env/scoutro/brand/mascot-64.png", "/js/lib/x.js",
                "/jquery/js/jquery.min.js", "/favicon.ico", "/robots.txt", "/scoutro-about.html", "/scoutro-login.html",
                "/scoutro/api/v1/health", "/scoutro/api/v1/ui/routes", "/scoutro/api/openapi.json",
                "/scoutro/api/v1/auth/session", "/scoutro/api/agent/v1/capabilities"}) {
            assertEquals(p, RoutePolicy.Kind.PUBLIC, kind(p, true));
        }
        assertEquals(RoutePolicy.Kind.LEGACY, kind("/yacy/hello.html", true));
        assertEquals(RoutePolicy.Kind.SIGNED_IN, kind("/scoutro/api/v1/index/browse", true));
    }

    @Test
    public void unusualPathsNeverMatchTheAllowlist() {
        for (final String p : new String[] {"/env/../Status.html", "//env/x.css", "/env/x.css;jsessionid=1", "/env\\x.css",
                "/scoutro-login.html/../ConfigAccounts_p.html", "/%65nv/x.css", "relative.css", ""}) {
            assertEquals(p, RoutePolicy.Kind.ADMIN, kind(p, true));
        }
    }
}
