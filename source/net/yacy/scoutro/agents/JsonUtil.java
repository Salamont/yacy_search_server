/*
 *  JsonUtil
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

package net.yacy.scoutro.agents;

import java.util.LinkedHashSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Helpers around the bundled org.json, whose JSONException is checked. */
final class JsonUtil {

    private JsonUtil() {
    }

    static JSONObject obj(final Object... keysAndValues) {
        final JSONObject o = new JSONObject(true);
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            put(o, (String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return o;
    }

    static JSONObject put(final JSONObject o, final String key, final Object value) {
        try {
            o.put(key, value == null ? JSONObject.NULL : value);
        } catch (final JSONException e) {
            throw new IllegalArgumentException("cannot store " + key + " in JSON", e);
        }
        return o;
    }

    static Set<String> stringSet(final JSONArray a) {
        final Set<String> out = new LinkedHashSet<>();
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                final String s = a.optString(i, null);
                if (s != null && !s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    static JSONObject parse(final String text) throws AgentException {
        try {
            return new JSONObject(text);
        } catch (final JSONException e) {
            throw new AgentException(500, "store_unreadable", "The agent store file is not valid JSON.");
        }
    }
}
