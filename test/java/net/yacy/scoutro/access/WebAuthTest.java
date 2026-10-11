/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class WebAuthTest {

    @Test
    public void returnTargetsStayOnThisServer() {
        final String fallback = "/scoutro-dashboard.html";
        assertEquals("/IndexBrowser_p.html?collection=a-web", WebAuth.safeNext("/IndexBrowser_p.html?collection=a-web"));
        assertEquals("/", WebAuth.safeNext("/"));
        for (final String bad : new String[] {null, "", "//evil.example/x", "/\\evil.example", "https://evil.example/",
                "javascript:alert(1)", "evil.example/x", "/\\/evil", "/a\nb", "/scoutro-login.html?next=/x",
                "/" + "a".repeat(2000)}) {
            assertEquals(String.valueOf(bad), fallback, WebAuth.safeNext(bad));
        }
    }

    @Test
    public void onlyBrowserPageNavigationsAreRedirectedToTheLoginPage() {
        assertTrue(WebAuth.browserNavigation(TestRequests.get("/Status.html").header("Accept", "text/html,*/*").build()));
        assertTrue(WebAuth.browserNavigation(TestRequests.get("/").header("Accept", "text/html").build()));
        assertFalse("curl/scripts", WebAuth.browserNavigation(TestRequests.get("/Status.html").header("Accept", "*/*").build()));
        assertFalse("no Accept", WebAuth.browserNavigation(TestRequests.get("/Status.html").build()));
        assertFalse("Digest retry", WebAuth.browserNavigation(TestRequests.get("/Status.html").header("Accept", "text/html")
                .header("Authorization", "Digest username=\"admin\"").build()));
        assertFalse("API", WebAuth.browserNavigation(TestRequests.get("/scoutro/api/v1/system").header("Accept", "text/html").build()));
        assertFalse("JSON", WebAuth.browserNavigation(TestRequests.get("/yacysearch.json").header("Accept", "text/html").build()));
        assertFalse("POST", WebAuth.browserNavigation(TestRequests.get("/Status.html").method("POST").header("Accept", "text/html").build()));
        assertFalse("fetch", WebAuth.browserNavigation(TestRequests.get("/Status.html").header("Accept", "text/html")
                .header("Sec-Fetch-Dest", "empty").build()));
        assertEquals("/scoutro-login.html?next=%2FIndexBrowser_p.html%3Fcollection%3Da-web",
                WebAuth.loginUrl(TestRequests.get("/IndexBrowser_p.html").query("collection=a-web").build(), false));
    }

    @Test
    public void changingSessionRequestsNeedTheTokenOrTheSameSite() {
        final SessionStore store = new SessionStore(() -> 1L, 60_000, 3_600_000);
        final SessionStore.Session s = store.create(SessionStore.Kind.ACCOUNT, "anna", "1", "c", "a").session;
        assertTrue(WebAuth.csrfAllowed(TestRequests.get("/scoutro/api/v1/index/browse").build(), s));
        assertFalse(WebAuth.csrfAllowed(TestRequests.get("/scoutro/api/v1/crawls").method("POST").build(), s));
        assertFalse(WebAuth.csrfAllowed(TestRequests.get("/scoutro/api/v1/crawls").method("POST")
                .header(WebAuth.CSRF_HEADER, "wrong").build(), s));
        assertTrue(WebAuth.csrfAllowed(TestRequests.get("/scoutro/api/v1/crawls").method("POST")
                .header(WebAuth.CSRF_HEADER, s.csrf).header("Sec-Fetch-Site", "same-origin").build(), s));
        assertFalse("token from another site", WebAuth.csrfAllowed(TestRequests.get("/scoutro/api/v1/crawls").method("POST")
                .header(WebAuth.CSRF_HEADER, s.csrf).header("Sec-Fetch-Site", "cross-site").build(), s));
        assertFalse("encoded path", WebAuth.csrfAllowed(TestRequests.get("/%73coutro/api/v1/crawls").method("POST").build(), s));
        // old YaCy forms: same site only
        assertTrue(WebAuth.csrfAllowed(TestRequests.get("/ConfigAccounts_p.html").method("POST")
                .header("Origin", "http://peer:8090").header("Host", "peer:8090").build(), s));
        assertFalse(WebAuth.csrfAllowed(TestRequests.get("/ConfigAccounts_p.html").method("POST")
                .header("Origin", "http://evil.example").header("Host", "peer:8090").build(), s));
        assertFalse(WebAuth.csrfAllowed(TestRequests.get("/Steering.html").method("POST")
                .header("Sec-Fetch-Site", "same-site").build(), s));
    }

    @Test
    public void cookiesAreHttpOnlyStrictAndSecureBehindHttps() {
        final Map<String, String> conf = new HashMap<>();
        final AccessSettings settings = new AccessSettings(conf::get);
        final String plain = WebAuth.setCookie("v", TestRequests.get("/").build(), settings);
        assertEquals("scoutro_session=v; Path=/; HttpOnly; SameSite=Strict", plain);
        assertTrue(WebAuth.setCookie("v", TestRequests.get("/").header("X-Forwarded-Proto", "https").build(), settings)
                .endsWith("; Secure"));
        assertTrue(WebAuth.setCookie("v", TestRequests.get("/").secure(true).build(), settings).endsWith("; Secure"));
        conf.put(AccessSettings.SAME_SITE, "lax");
        conf.put(AccessSettings.SECURE, "always");
        assertEquals("scoutro_session=v; Path=/; HttpOnly; SameSite=Lax; Secure", WebAuth.setCookie("v", TestRequests.get("/").build(), settings));
        assertTrue(WebAuth.clearCookie(TestRequests.get("/").build(), settings).contains("Max-Age=0"));
    }
}
