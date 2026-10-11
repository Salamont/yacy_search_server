/*
 *  PageNav
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

import javax.servlet.http.HttpServletRequest;

/**
 * Template fields of the shared header (env/templates/header.template): who is
 * signed in and which navigation groups the identity may use. Computed from
 * the same decision as the servlets ({@link Caller}); the server refuses the
 * pages anyway, the fields only keep the navigation honest.
 */
public final class PageNav {

    /** 0 nobody, 1 Scoutro session, 2 administrator without a session (HTTP Digest or YaCy's local rule) */
    public final int account;
    /** display name (to be HTML-escaped by the caller) */
    public final String user;
    /** 0 guest/none, 1 research, 2 operator, 3 administrator */
    public final int role;
    public final boolean search;
    public final boolean chat;
    public final boolean read;
    public final boolean collect;
    public final boolean admin;
    public final String csrf;

    private PageNav(final int account, final String user, final int role, final boolean search, final boolean chat,
            final boolean read, final boolean collect, final boolean admin, final String csrf) {
        this.account = account;
        this.user = user;
        this.role = role;
        this.search = search;
        this.chat = chat;
        this.read = read;
        this.collect = collect;
        this.admin = admin;
        this.csrf = csrf;
    }

    /**
     * @param authorized YaCy's own administrator decision for this request (Digest, administrator
     *        session or the local rules), as used for the "authorized" template field
     * @param publicSearch YaCy's {@code publicSearchpage}: anonymous search allowed in compatible mode
     */
    public static PageNav of(final HttpServletRequest request, final boolean authorized, final boolean publicSearch) {
        final ScoutroAccess access = ScoutroAccess.current();
        final Caller caller = Caller.of(request, access);
        final ScoutroPrincipal p = caller.principal();
        if (p != null) {
            return new PageNav(1, p.displayName(), roleIndex(p.role()), p.has(Permission.SEARCH), p.has(Permission.CHAT),
                    p.has(Permission.READ), p.has(Permission.COLLECT), p.has(Permission.ADMIN), p.session().csrf);
        }
        if (authorized || caller.admin()) {
            final String name = request.getRemoteUser() == null ? "admin" : request.getRemoteUser();
            return new PageNav(2, name, 3, true, true, true, true, true, "");
        }
        final boolean protectedMode = access != null && access.protectedMode();
        final boolean search = caller.has(Permission.SEARCH) || (!protectedMode && publicSearch);
        return new PageNav(0, "", 0, search, false, false, false, false, "");
    }

    static int roleIndex(final Role role) {
        if (role == null) {
            return 0;
        }
        switch (role) {
            case RESEARCH:
                return 1;
            case OPERATOR:
                return 2;
            case ADMINISTRATOR:
                return 3;
            default:
                return 0;
        }
    }
}
