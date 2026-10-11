/*
 *  ScoutroSystem_p
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
import net.yacy.kelondro.util.MemoryControl;
import net.yacy.peers.operation.yacyBuildProperties;
import net.yacy.search.Switchboard;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

/**
 * Settings > System (ScoutroSystem_p.html): versions, uptime and memory, and
 * the Re-Start and Shutdown actions that left the header. Read-only; the
 * actions are YaCy's Steering.html with its transaction token and
 * confirmation, logged in the audit log of people.
 */
public class ScoutroSystem_p {

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final Switchboard sb = (Switchboard) env;
        final serverObjects prop = new serverObjects();
        if (!sb.verifyAuthentication(header)) {
            prop.authenticationRequired();
            return prop;
        }
        prop.putHTML("scoutroVersion", ScoutroDashboard.scoutroVersion(sb));
        prop.putHTML("yacyVersion", yacyBuildProperties.getVersion());
        prop.putHTML("uptime", ScoutroDashboard.uptime(System.currentTimeMillis() - sb.startupTime));
        prop.putHTML("heapUsed", ScoutroDashboard.bytes(MemoryControl.used()));
        prop.putHTML("heapMax", ScoutroDashboard.bytes(MemoryControl.maxMemory()));
        prop.putHTML("peerName", sb.peers == null || sb.peers.mySeed() == null ? "" : sb.peers.mySeed().getName());
        return prop;
    }
}
