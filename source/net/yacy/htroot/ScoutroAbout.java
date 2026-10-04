/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.htroot;

import net.yacy.cora.protocol.RequestHeader;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

/**
 * Public About page. It has no template fields; the class only exists so that
 * YaCyDefaultServlet serves the translated copy of scoutro-about.html.
 */
public final class ScoutroAbout {

    private ScoutroAbout() { }

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        return new serverObjects();
    }
}
