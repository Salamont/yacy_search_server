/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.htroot;

import net.yacy.cora.protocol.RequestHeader;
import net.yacy.search.Switchboard;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

/** The detail shell is read-only. Mutations use the protected Scoutro JSON API. */
public final class ScoutroDiscovery_p {
    private ScoutroDiscovery_p() { }
    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final serverObjects prop = new serverObjects();
        // Scoutro: YaCy's administrator or a signed-in identity with the collect right (docs/SCOUTRO_USERS_ACCESS.md);
        // the API limits the data to the identity's collections
        if (!((Switchboard) env).verifyAuthentication(header)
                && !net.yacy.scoutro.access.Caller.of(header).has(net.yacy.scoutro.access.Permission.COLLECT)) prop.authenticationRequired();
        return prop;
    }
}
