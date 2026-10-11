/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.access;

import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

/** Minimal HttpServletRequest stubs for the access tests (no servlet container). */
final class TestRequests {

    private TestRequests() {
    }

    static final class Builder {
        String method = "GET";
        String uri = "/";
        String query;
        final Map<String, String> headers = new HashMap<>();
        final List<Cookie> cookies = new ArrayList<>();
        Principal principal;
        Set<String> roles = Collections.emptySet();
        boolean secure;

        Builder method(final String m) { this.method = m; return this; }
        Builder uri(final String u) { this.uri = u; return this; }
        Builder query(final String q) { this.query = q; return this; }
        Builder header(final String k, final String v) { this.headers.put(k.toLowerCase(), v); return this; }
        Builder cookie(final String k, final String v) { this.cookies.add(new Cookie(k, v)); return this; }
        Builder principal(final Principal p, final Set<String> r) { this.principal = p; this.roles = r; return this; }
        Builder secure(final boolean s) { this.secure = s; return this; }

        HttpServletRequest build() {
            return (HttpServletRequest) Proxy.newProxyInstance(TestRequests.class.getClassLoader(),
                    new Class<?>[] {HttpServletRequest.class}, (proxy, m, args) -> {
                        switch (m.getName()) {
                            case "getMethod": return this.method;
                            case "getRequestURI": return this.uri;
                            case "getQueryString": return this.query;
                            case "getHeader": return this.headers.get(String.valueOf(args[0]).toLowerCase());
                            case "getCookies": return this.cookies.isEmpty() ? null : this.cookies.toArray(new Cookie[0]);
                            case "getUserPrincipal": return this.principal;
                            case "isUserInRole": return this.principal != null && this.roles.contains(String.valueOf(args[0]));
                            case "isSecure": return this.secure;
                            case "getServletPath": return null;
                            case "getPathInfo": return null;
                            case "toString": return "stub " + this.method + " " + this.uri;
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            default: return null;
                        }
                    });
        }
    }

    static Builder get(final String uri) {
        return new Builder().uri(uri);
    }
}
