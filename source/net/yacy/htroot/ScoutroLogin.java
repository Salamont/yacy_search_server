/*
 *  ScoutroLogin
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

package net.yacy.htroot;

import net.yacy.cora.protocol.RequestHeader;
import net.yacy.scoutro.access.ScoutroAccess;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

/**
 * The Scoutro login page (scoutro-login.html). Public; the page signs in
 * through POST /scoutro/api/v1/auth/login. It only tells the page whether the
 * account store is available and whether the built-in administrator may sign in.
 */
public class ScoutroLogin {

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final serverObjects prop = new serverObjects();
        final ScoutroAccess access = ScoutroAccess.current();
        prop.put("available", access == null ? 0 : 1);
        prop.put("builtin", access != null && access.builtinAdminLogin() ? 1 : 0);
        return prop;
    }
}
