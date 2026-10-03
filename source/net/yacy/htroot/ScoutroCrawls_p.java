/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.htroot;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.search.Switchboard;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;
/** Read-only admin shell. Crawl writes use the protected Scoutro JSON API only. */
public final class ScoutroCrawls_p {
    private ScoutroCrawls_p() { }
    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final serverObjects properties = new serverObjects();
        if (!((Switchboard) env).verifyAuthentication(header)) properties.authenticationRequired();
        return properties;
    }
}
