/*
 *  ScoutroSearch
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
 * Scoutro search (scoutro-search.html): the search of accounts and guests within their collections. The
 * container admits the search right or a guest (RoutePolicy); the page searches through
 * /scoutro/api/v1/search, which limits every request to the caller's collections.
 */
public class ScoutroSearch {

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final serverObjects prop = new serverObjects();
        final String query = post == null ? "" : post.get("q", post.get("query", "")).trim();
        prop.putHTML("query", query.length() > 200 ? query.substring(0, 200) : query);
        return prop;
    }
}
