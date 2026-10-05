/*
 *  ScoutroKnowledge_p
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
import net.yacy.search.Switchboard;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

/** Shell of the knowledge graph page; all data comes from the administrator API through the page script. */
public final class ScoutroKnowledge_p {
    private ScoutroKnowledge_p() {}

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final serverObjects prop = new serverObjects();
        if (!((Switchboard) env).verifyAuthentication(header)) prop.authenticationRequired();
        return prop;
    }
}
