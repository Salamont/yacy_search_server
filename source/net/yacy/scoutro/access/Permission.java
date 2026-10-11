/*
 *  Permission
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

import java.util.Locale;

/**
 * What an identity may do. The role decides the permissions, the collection
 * scope decides the data (docs/SCOUTRO_USERS_ACCESS.md, section 3).
 */
public enum Permission {

    /** Scoutro search in the allowed collections. */
    SEARCH,
    /** Chat with sources and graph facts, one allowed collection at a time. */
    CHAT,
    /** Overview, websites, host analysis and the knowledge graph. */
    READ,
    /** Downloads and exports of allowed data. */
    EXPORT,
    /** Crawls, discovery jobs and crawl reports in the allowed collections. */
    COLLECT,
    /** System-wide functions; only the role Administrator has it. */
    ADMIN;

    /** Prefix of the container roles that carry a permission. */
    public static final String ROLE_PREFIX = "scoutro:";

    /** Container role of every signed-in Scoutro account. */
    public static final String SIGNED_IN_ROLE = "scoutroUser";

    /** Stable lower-case id used in the API and in templates. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The servlet container role that carries this permission. */
    public String containerRole() {
        return ROLE_PREFIX + id();
    }

    public static Permission parse(final String id) {
        if (id == null) {
            return null;
        }
        for (final Permission p : values()) {
            if (p.id().equals(id.trim().toLowerCase(Locale.ROOT))) {
                return p;
            }
        }
        return null;
    }
}
