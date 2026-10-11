/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
                "/gsa/search", "/suggest.json", "/tools", "/tools/call", "/v1/models", "/api/tags",
                "/Status.html", "/ViewFile.html", "/yacydoc.html", "/api/version.xml", "/ScoutroAgents_p.html",
                "/ScoutroUsers_p.html", "/LLMSelection_p.html", "/IndexControlURLs_p.html", "/Crawler_p.html",
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
    public void protectedModeClosesPeerToPeerIndexRoutesUntilAllowed() {
        // read or write every collection: search, URL metadata, host index, counts, index transfer, crawl receipts
        final String[] data = {"/yacy/search.html", "/yacy/urls.xml", "/yacy/idx.json", "/yacy/query.html",
            "/yacy/transferRWI.html", "/yacy/transferURL.html", "/yacy/crawlReceipt.html", "/yacy/search.json",
            "/yacy/unknown.html", "/yacy/"};
        for (final String p : data) {
            assertEquals(p, RoutePolicy.Kind.ADMIN, kind(p, true));
            assertEquals(p, RoutePolicy.Kind.ADMIN, RoutePolicy.classify(p, true, false).kind);
            assertEquals(p, RoutePolicy.Kind.LEGACY, RoutePolicy.classify(p, true, true).kind);
            assertEquals(p, RoutePolicy.Kind.LEGACY, kind(p, false));
        }
        // no index data: the handshake keeps YaCy's network rules (also Steering's restart check)
        for (final String p : new String[] {"/yacy/hello.html", "/yacy/seedlist.json", "/yacy/seedlist.xml", "/yacy/seedlist.html",
                "/yacy/profile.html", "/yacy/list.html", "/yacy/message.html"}) {
            assertEquals(p, RoutePolicy.Kind.LEGACY, kind(p, true));
        }
        // path tricks never reach the handshake rule
        for (final String p : new String[] {"/yacy/hello.html/../search.html", "/yacy//search.html", "/yacy/%73earch.html",
                "/yacy/hello.html;/../urls.xml"}) {
            assertEquals(p, RoutePolicy.Kind.ADMIN, kind(p, true));
        }
    }

    @Test
    public void protectedModeOpensFunctionPagesByPermission() {
        final String[][] expected = {
            {"/", "search+guest"}, {"/index.html", "search+guest"}, {"/scoutro-search.html", "search+guest"},
            {"/scoutro-dashboard.html", "read"}, {"/IndexBrowser_p.html", "read"}, {"/ScoutroSEO_p.html", "read"},
            {"/ScoutroKnowledge_p.html", "read"}, {"/yacychat.html", "chat"}, {"/v1/chat/completions", "chat"},
            {"/ScoutroCrawls_p.html", "collect"}, {"/ScoutroDiscovery_p.html", "collect"}};
        for (final String[] e : expected) {
            final RoutePolicy.Rule rule = RoutePolicy.classify(e[0], true);
            assertEquals(e[0], RoutePolicy.Kind.PERMISSION, rule.kind);
            assertEquals(e[0], e[1], rule.permission.id() + (rule.guest ? "+guest" : ""));
            // compatible mode keeps YaCy's rules: restricted accounts cannot exist there
            assertEquals(e[0], RoutePolicy.Kind.LEGACY, kind(e[0], false));
        }
        for (final boolean protectedMode : new boolean[] {false, true}) {
            for (final String p : new String[] {"/scoutro/api/v1/search", "/scoutro/api/v1/collections"}) {
                final RoutePolicy.Rule rule = RoutePolicy.classify(p, protectedMode);
                assertEquals(p, RoutePolicy.Kind.PERMISSION, rule.kind);
                assertEquals(p, Permission.SEARCH, rule.permission);
                assertTrue(p, rule.guest);
            }
        }
        // a sub path is not the page
        assertEquals(RoutePolicy.Kind.ADMIN, kind("/IndexBrowser_p.html/x", true));
        assertEquals(RoutePolicy.Kind.SIGNED_IN, kind("/scoutro/api/v1/collections/reassign", true));
    }

    @Test
    public void unusualPathsNeverMatchTheAllowlist() {
        for (final String p : new String[] {"/env/../Status.html", "//env/x.css", "/env/x.css;jsessionid=1", "/env\\x.css",
                "/scoutro-login.html/../ConfigAccounts_p.html", "/%65nv/x.css", "relative.css", ""}) {
            assertEquals(p, RoutePolicy.Kind.ADMIN, kind(p, true));
        }
    }
}
