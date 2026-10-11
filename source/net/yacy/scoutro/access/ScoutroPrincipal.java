/*
 *  ScoutroPrincipal
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The servlet-container principal of a request authenticated with a Scoutro
 * session. It is created by the authenticator for every request from the
 * current account, so role, collections and status changes apply at once.
 * Only the role Administrator carries YaCy's {@code adminRight}.
 */
public final class ScoutroPrincipal implements Principal {

    /** YaCy's administrator role ({@code SwitchboardConstants.ADMIN_ACCOUNT_ROLE}). */
    public static final String YACY_ADMIN_ROLE = "adminRight";

    private final SessionStore.Session session;
    private final String name;
    private final String displayName;
    private final Role role;
    private final Set<Permission> permissions;
    private final Scope scope;
    private final boolean mustChangePassword;

    ScoutroPrincipal(final SessionStore.Session session, final String displayName, final Role role,
            final Set<Permission> permissions, final Scope scope, final boolean mustChangePassword) {
        this.session = session;
        this.name = session.username;
        this.displayName = displayName == null || displayName.isEmpty() ? session.username : displayName;
        this.role = role;
        this.mustChangePassword = mustChangePassword;
        this.permissions = mustChangePassword ? Collections.<Permission>emptySet()
                : Collections.unmodifiableSet(permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions));
        this.scope = mustChangePassword ? Scope.NONE : scope;
    }

    @Override
    public String getName() {
        return this.name;
    }

    public String displayName() {
        return this.displayName;
    }

    public Role role() {
        return this.role;
    }

    public boolean builtin() {
        return this.session.kind == SessionStore.Kind.BUILTIN;
    }

    public Set<Permission> permissions() {
        return this.permissions;
    }

    public boolean has(final Permission p) {
        return this.permissions.contains(p);
    }

    public Scope scope() {
        return this.scope;
    }

    public boolean mustChangePassword() {
        return this.mustChangePassword;
    }

    public SessionStore.Session session() {
        return this.session;
    }

    /** Actor name for the audit log. */
    public String actor() {
        return builtin() ? "builtin:" + this.name : this.name;
    }

    /** The container roles: signed in, one per permission, and adminRight only with the permission ADMIN. */
    public String[] containerRoles() {
        final List<String> roles = new ArrayList<>();
        roles.add(Permission.SIGNED_IN_ROLE);
        for (final Permission p : this.permissions) {
            roles.add(p.containerRole());
        }
        if (this.permissions.contains(Permission.ADMIN)) {
            roles.add(YACY_ADMIN_ROLE);
        }
        return roles.toArray(new String[0]);
    }

    @Override
    public String toString() {
        return "ScoutroPrincipal[" + this.name + "," + this.role.id() + "]";
    }
}
