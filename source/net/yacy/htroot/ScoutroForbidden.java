/*
 *  ScoutroForbidden
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
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

/**
 * "No permission" (scoutro-forbidden.html): the error page of the servlet
 * container for status 403, e.g. a signed-in Research account that opens an
 * administrator page. It shows no data and changes nothing.
 */
public class ScoutroForbidden {

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        return new serverObjects();
    }
}
