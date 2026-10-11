/*
 *  Role
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
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The small role model of Scoutro accounts. Export is a separate flag of an
 * account (administrators always have it); see {@link #permissions(boolean)}.
 */
public enum Role {

    /** Anonymous access to explicitly released collections; never stored in an account. */
    GUEST(EnumSet.of(Permission.SEARCH)),
    /** Search, chat, analyses and knowledge in the allowed collections. */
    RESEARCH(EnumSet.of(Permission.SEARCH, Permission.CHAT, Permission.READ)),
    /** Research plus data collection in the allowed collections. */
    OPERATOR(EnumSet.of(Permission.SEARCH, Permission.CHAT, Permission.READ, Permission.COLLECT)),
    /** Everything, including users, agents, models and system functions. */
    ADMINISTRATOR(EnumSet.allOf(Permission.class));

    private final Set<Permission> base;

    Role(final Set<Permission> base) {
        this.base = Collections.unmodifiableSet(base);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Roles an account can have (guest is never an account). */
    public boolean assignable() {
        return this != GUEST;
    }

    /** Permissions of this role; {@code export} adds {@link Permission#EXPORT} to Research and Operator. */
    public Set<Permission> permissions(final boolean export) {
        final EnumSet<Permission> p = this.base.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(this.base);
        if (export && (this == RESEARCH || this == OPERATOR)) {
            p.add(Permission.EXPORT);
        }
        return Collections.unmodifiableSet(p);
    }

    public static Role parse(final String id) {
        if (id == null) {
            return null;
        }
        for (final Role r : values()) {
            if (r.id().equals(id.trim().toLowerCase(Locale.ROOT))) {
                return r;
            }
        }
        return null;
    }
}
