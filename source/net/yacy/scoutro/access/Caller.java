/*
 *  Caller
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

import java.security.Principal;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

/**
 * Who sends a request, with what permissions and which collections: the one
 * decision every Scoutro servlet asks ({@link #require}, {@link #view}).
 * Built only from the servlet container's authentication (never from request
 * headers or parameters), so it cannot be widened by the client.
 */
public final class Caller {

    public enum Kind {
        /** a signed-in Scoutro account */
        ACCOUNT,
        /** the built-in administrator, signed in on the login page */
        BUILTIN,
        /** the YaCy administrator authenticated by HTTP Digest */
        DIGEST_ADMIN,
        /** anonymous access to released collections (protected mode, guest switched on) */
        GUEST,
        /** nobody */
        ANONYMOUS
    }

    private static final String ADMIN_ROLE = ScoutroPrincipal.YACY_ADMIN_ROLE;

    public final Kind kind;
    public final String name;
    public final Role role;
    private final Set<Permission> permissions;
    private final Scope scope;
    private final ScoutroPrincipal principal;

    private Caller(final Kind kind, final String name, final Role role, final Set<Permission> permissions,
            final Scope scope, final ScoutroPrincipal principal) {
        this.kind = kind;
        this.name = name;
        this.role = role;
        this.permissions = permissions;
        this.scope = scope;
        this.principal = principal;
    }

    public static Caller of(final HttpServletRequest request) {
        return of(request, ScoutroAccess.current());
    }

    public static Caller of(final HttpServletRequest request, final ScoutroAccess access) {
        final Principal p = request == null ? null : request.getUserPrincipal();
        if (p instanceof ScoutroPrincipal) {
            final ScoutroPrincipal sp = (ScoutroPrincipal) p;
            return new Caller(sp.builtin() ? Kind.BUILTIN : Kind.ACCOUNT, sp.getName(), sp.role(), sp.permissions(),
                    sp.scope(), sp);
        }
        // the administrator role comes solely from container authentication (Digest), as before for requireAdmin
        if (request != null && request.isUserInRole(ADMIN_ROLE)) {
            return new Caller(Kind.DIGEST_ADMIN, p == null ? "admin" : p.getName(), Role.ADMINISTRATOR,
                    Collections.unmodifiableSet(EnumSet.allOf(Permission.class)), Scope.ALL, null);
        }
        if (access != null && access.guestEnabled()) {
            return new Caller(Kind.GUEST, "guest", Role.GUEST, Role.GUEST.permissions(false), access.guestScope(), null);
        }
        return anonymous();
    }

    public static Caller anonymous() {
        return new Caller(Kind.ANONYMOUS, "", null, Collections.<Permission>emptySet(), Scope.NONE, null);
    }

    /** For tests and internal callers. */
    public static Caller admin(final String name) {
        return new Caller(Kind.DIGEST_ADMIN, name, Role.ADMINISTRATOR,
                Collections.unmodifiableSet(EnumSet.allOf(Permission.class)), Scope.ALL, null);
    }

    public boolean signedIn() {
        return this.kind == Kind.ACCOUNT || this.kind == Kind.BUILTIN || this.kind == Kind.DIGEST_ADMIN;
    }

    public boolean has(final Permission p) {
        return this.permissions.contains(p);
    }

    public boolean admin() {
        return has(Permission.ADMIN);
    }

    public Set<Permission> permissions() {
        return this.permissions;
    }

    public Scope scope() {
        return this.scope;
    }

    /** The session principal, or null for Digest administrators, guests and anonymous callers. */
    public ScoutroPrincipal principal() {
        return this.principal;
    }

    /** Actor name for the audit log. */
    public String actor() {
        switch (this.kind) {
            case ACCOUNT:
                return this.name;
            case BUILTIN:
                return "builtin:" + this.name;
            case DIGEST_ADMIN:
                return "digest:" + this.name;
            case GUEST:
                return "guest";
            default:
                return "anonymous";
        }
    }

    /**
     * @throws AccessException 401 when nobody is signed in, 403
     *         {@code password_change_required} or {@code forbidden} otherwise
     */
    public void require(final Permission p) throws AccessException {
        if (has(p)) {
            return;
        }
        if (this.principal != null && this.principal.mustChangePassword()) {
            throw new AccessException(403, "password_change_required", "Set a new password first.");
        }
        if (!signedIn() && this.kind != Kind.GUEST) {
            throw new AccessException(401, "unauthorized", "Sign in first.");
        }
        throw new AccessException(403, "forbidden", "Your role does not allow this action.");
    }

    /**
     * The effective collections of a request (see {@link Scope#view}).
     *
     * @return null for the whole index (only with all collections and no request)
     * @throws AccessException 403 {@code collection_not_allowed}, the same for unknown and foreign names
     */
    public List<String> view(final String requested) throws AccessException {
        try {
            return this.scope.view(requested);
        } catch (final Scope.CollectionNotAllowed e) {
            throw new AccessException(403, "collection_not_allowed", "This collection is not available to you.", "collection");
        }
    }

    /** Requires that the identity may use {@code collection}. */
    public void requireCollection(final String collection) throws AccessException {
        if (!this.scope.allows(collection)) {
            throw new AccessException(403, "collection_not_allowed", "This collection is not available to you.", "collection");
        }
    }
}
