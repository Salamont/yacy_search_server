/*
 *  ScoutroSessionAuthenticator
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 */

package net.yacy.http;

import java.io.IOException;

import javax.security.auth.Subject;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.ee8.nested.Authentication;
import org.eclipse.jetty.ee8.security.ServerAuthException;
import org.eclipse.jetty.ee8.security.UserAuthentication;
import org.eclipse.jetty.ee8.security.authentication.DeferredAuthentication;
import org.eclipse.jetty.ee8.security.authentication.DigestAuthenticator;
import org.eclipse.jetty.security.UserIdentity;

import net.yacy.scoutro.access.RoutePolicy;
import net.yacy.scoutro.access.ScoutroAccess;
import net.yacy.scoutro.access.ScoutroPrincipal;
import net.yacy.scoutro.access.WebAuth;

/**
 * YaCy's Digest authenticator with Scoutro sessions in front of it.
 * <ul>
 * <li>A request with an {@code Authorization} header is always handled by
 * Digest, unchanged (tools, scripts, cached browser credentials).</li>
 * <li>Otherwise a valid {@code scoutro_session} cookie authenticates the
 * request. The identity carries the container roles of
 * {@link ScoutroPrincipal#containerRoles()}; only administrators get
 * {@code adminRight}. A changing request must pass the CSRF rule of
 * {@link WebAuth#csrfAllowed}.</li>
 * <li>A browser navigation without credentials to a page that needs a
 * sign-in is redirected to the Scoutro login page; everything else gets the
 * Digest challenge as before.</li>
 * </ul>
 */
public final class ScoutroSessionAuthenticator extends DigestAuthenticator {

    public static final String AUTH_METHOD = "SCOUTRO-SESSION";

    @Override
    public Authentication validateRequest(final ServletRequest req, final ServletResponse res, final boolean mandatory)
            throws ServerAuthException {
        if (!mandatory) {
            return new DeferredAuthentication(this);
        }
        final HttpServletRequest request = (HttpServletRequest) req;
        final HttpServletResponse response = (HttpServletResponse) res;
        final String authorization = request.getHeader("Authorization");
        final boolean deferred = DeferredAuthentication.isDeferred(response);
        if (authorization == null || authorization.isEmpty()) {
            final ScoutroAccess access = ScoutroAccess.current();
            final ScoutroPrincipal principal = access == null ? null : access.resolve(WebAuth.cookieValue(request));
            if (principal != null) {
                if (!WebAuth.csrfAllowed(request, principal.session())) {
                    if (deferred) {
                        return Authentication.UNAUTHENTICATED; // the servlet sees an anonymous request
                    }
                    send(response, 403, "csrf_failed", "The request did not come from a Scoutro page of this site.");
                    return Authentication.SEND_FAILURE;
                }
                if (!deferred && principal.mustChangePassword() && WebAuth.browserNavigation(request)
                        && !RoutePolicy.LOGIN_PAGE.equals(request.getRequestURI())) {
                    redirect(response, WebAuth.loginUrl(request, true));
                    return Authentication.SEND_CONTINUE;
                }
                final UserIdentity identity = UserIdentity.from(new Subject(), principal, principal.containerRoles());
                return new UserAuthentication(AUTH_METHOD, identity);
            }
            if (!deferred && access != null && access.settings.loginPage() && WebAuth.browserNavigation(request)
                    && !RoutePolicy.DIGEST_PAGE.equals(request.getRequestURI())) {
                redirect(response, WebAuth.loginUrl(request, false));
                return Authentication.SEND_CONTINUE;
            }
        }
        return super.validateRequest(req, res, mandatory);
    }

    private static void redirect(final HttpServletResponse response, final String location) throws ServerAuthException {
        try {
            response.setHeader("Cache-Control", "no-store");
            response.sendRedirect(location);
        } catch (final IOException e) {
            throw new ServerAuthException(e);
        }
    }

    private static void send(final HttpServletResponse response, final int status, final String code, final String message)
            throws ServerAuthException {
        try {
            response.setStatus(status);
            response.setContentType("application/json;charset=utf-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}\n");
        } catch (final IOException e) {
            throw new ServerAuthException(e);
        }
    }
}
