/*
 *  KgJson
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

package net.yacy.scoutro.knowledge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** JSON building without checked exceptions; null values become JSON null. */
public final class KgJson {

    private KgJson() {}

    public static JSONObject obj(final Object... keysAndValues) {
        final JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            put(o, (String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return o;
    }

    public static JSONObject put(final JSONObject o, final String key, final Object value) {
        try {
            o.put(key, value == null ? JSONObject.NULL : value);
        } catch (final JSONException e) {
            throw new IllegalArgumentException("cannot store " + key + " in JSON", e);
        }
        return o;
    }

    public static JSONArray add(final JSONArray a, final Object value) {
        a.put(value == null ? JSONObject.NULL : value);
        return a;
    }
}
