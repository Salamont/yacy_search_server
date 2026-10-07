/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.http.servlets;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.cora.document.id.MultiProtocolURL;
import net.yacy.cora.protocol.Domains;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.http.ClientAddress;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;

/**
 * Access rules shared by the public chat endpoint (AI Shield, {@link RAGProxyServlet})
 * and the LLM admin passthrough proxy ({@link LLMAdminProxyServlet}).
 * <p>
 * Both use {@link ClientAddress}: forwarded headers count only from a trusted proxy,
 * and only a direct loopback connection without forwarding headers is "local".
 * Loopback never becomes administrator by itself: the localhost bypass needs
 * {@code adminAccountForLocalhost=true}, a local request and a local (or no) referer;
 * otherwise the administrator has to authenticate with the YaCy account (HTTP Digest).
 */
public final class LLMAccess {

    /** Error codes of the chat and admin proxy endpoints ({"error":{"code","message"}}). */
    public static final String ADMIN_REQUIRED = "admin_required";
    public static final String AI_SHIELD_BLOCKED = "ai_shield_blocked";
    public static final String AI_SHIELD_RATE_LIMITED = "ai_shield_rate_limited";
    /** the chat scope names a collection this client may not choose (package 6.1) */
    public static final String COLLECTION_NOT_ALLOWED = "collection_not_allowed";
    public static final String NO_CHAT_MODEL = "no_chat_model";
    public static final String LLM_UNREACHABLE = "llm_unreachable";
    public static final String LLM_AUTH_FAILED = "llm_auth_failed";
    public static final String LLM_ERROR = "llm_error";

    /** AI Shield outcome for one chat request. */
    public enum Shield {
        /** direct local connection */
        LOCAL(true),
        /** remote guest, allowed by ai.shield.allow-nonlocalhost (guest rate limits apply) */
        GUEST(false),
        /** remote, authenticated YaCy administrator */
        ADMIN(true),
        /** remote, no valid administrator authentication, guests not allowed */
        ADMIN_REQUIRED(false),
        /** remote request from another site (CSRF protection for the administrator path) */
        BLOCKED_CROSS_SITE(false),
        /** remote request with an agent token; agent tokens are not an AI Shield admission */
        BLOCKED_TOKEN(false);

        /** true when the request may proceed with the limits of local access */
        public final boolean privileged;

        Shield(final boolean privileged) {
            this.privileged = privileged;
        }

        public boolean allowed() {
            return this == LOCAL || this == GUEST || this == ADMIN;
        }
    }

    private LLMAccess() {
    }

    public static ClientAddress client(final HttpServletRequest request) {
        return ClientAddress.of(request, trustedProxies());
    }

    /** The client of a page request, resolved like {@link #client(HttpServletRequest)} from its socket peer. */
    public static ClientAddress client(final RequestHeader header) {
        return ClientAddress.resolve(header.getRemoteSocketAddr(), name -> {
            final List<String> values = new ArrayList<>();
            if (header.getHeaders(name) != null) values.addAll(Collections.list(header.getHeaders(name)));
            return values;
        }, trustedProxies());
    }

    private static String trustedProxies() {
        final Switchboard sb = Switchboard.getSwitchboard();
        return sb == null ? SwitchboardConstants.SERVER_REVERSE_PROXY_TRUSTED_DEFAULT
                : sb.getConfig(SwitchboardConstants.SERVER_REVERSE_PROXY_TRUSTED,
                        SwitchboardConstants.SERVER_REVERSE_PROXY_TRUSTED_DEFAULT);
    }

    /**
     * AI Shield decision. {@code admin} is evaluated only when the answer depends on it,
     * because evaluating it may send an authentication challenge.
     */
    public static Shield shield(final boolean allowNonLocal, final ClientAddress client, final boolean crossSite,
            final boolean bearerToken, final BooleanSupplier admin) {
        if (client.isLocal()) return Shield.LOCAL;
        if (allowNonLocal) return Shield.GUEST;
        if (crossSite) return Shield.BLOCKED_CROSS_SITE;
        if (bearerToken) return Shield.BLOCKED_TOKEN;
        return admin.getAsBoolean() ? Shield.ADMIN : Shield.ADMIN_REQUIRED;
    }

    /**
     * Localhost administrator bypass of the LLM admin proxy, mirroring YaCy's
     * container rule, but only for a direct loopback connection without forwarding headers.
     */
    public static boolean localAdminBypass(final boolean adminAccountForLocalhost, final ClientAddress client,
            final String referer) {
        return adminAccountForLocalhost && client.isLocal() && localReferer(referer);
    }

    static boolean localReferer(final String referer) {
        if (referer == null || referer.isEmpty()) return true;
        try {
            final String host = new MultiProtocolURL(referer).getHost();
            return host == null || host.isEmpty() || Domains.isLocalhost(host);
        } catch (final MalformedURLException e) {
            return false;
        }
    }

    /**
     * True when the request is authenticated (or, with challenge, authenticates) as YaCy administrator.
     * A failed challenge leaves status 401 and the WWW-Authenticate header on the response, but no
     * body, so the caller can still answer with a JSON error.
     */
    public static boolean admin(final HttpServletRequest request, final HttpServletResponse response, final boolean challenge) {
        final String role = SwitchboardConstants.ADMIN_ACCOUNT_ROLE;
        if (request.isUserInRole(role)) return true;
        if (!challenge) return false;
        try {
            return request.authenticate(new DeferredError(response)) && request.isUserInRole(role);
        } catch (final ServletException | IOException e) {
            return false;
        }
    }

    /** Turns sendError of the authenticator into a status, keeping its challenge headers. */
    private static final class DeferredError extends HttpServletResponseWrapper {
        DeferredError(final HttpServletResponse response) {
            super(response);
        }

        @Override
        public void sendError(final int status) {
            setStatus(status);
        }

        @Override
        public void sendError(final int status, final String message) {
            setStatus(status);
        }
    }

    static boolean bearerToken(final HttpServletRequest request) {
        final String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.toLowerCase(Locale.ROOT).startsWith("bearer ");
    }

    /**
     * A browser request from another origin. Sec-Fetch-Site decides when the browser sends it;
     * otherwise a present Origin must match the Host (or, from a trusted proxy, X-Forwarded-Host).
     * Requests without either header (non-browser clients) are not cross-site.
     */
    public static boolean crossSite(final HttpServletRequest request, final ClientAddress client) {
        final String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.isEmpty()) {
            final String value = site.trim().toLowerCase(Locale.ROOT);
            return !"same-origin".equals(value) && !"none".equals(value);
        }
        final String origin = request.getHeader("Origin");
        if (origin == null || origin.isEmpty()) return false;
        if ("null".equals(origin)) return true;
        final String authority;
        try {
            authority = new URI(origin.trim()).getAuthority();
        } catch (final Exception e) {
            return true;
        }
        if (authority == null) return true;
        if (authority.equalsIgnoreCase(trim(request.getHeader("Host")))) return false;
        return !(client.isPeerTrusted() && authority.equalsIgnoreCase(trim(request.getHeader("X-Forwarded-Host"))));
    }

    private static String trim(final String value) {
        return value == null ? "" : value.trim();
    }

    /** Write a JSON error {"error":{"code","message"}} unless the response is already committed. */
    public static void error(final HttpServletResponse response, final int status, final String code,
            final String message) throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json;charset=utf-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getOutputStream().write(errorJson(code, message).getBytes(StandardCharsets.UTF_8));
    }

    static String errorJson(final String code, final String message) {
        try {
            final JSONObject error = new JSONObject(true);
            error.put("code", code);
            error.put("message", message);
            return new JSONObject(true).put("error", error).toString();
        } catch (final JSONException e) {
            return "{\"error\":{\"code\":\"" + code + "\"}}";
        }
    }
}
