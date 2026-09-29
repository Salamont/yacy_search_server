/*
 *  Json
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

package net.yacy.scoutro.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Small helpers around the bundled org.json, whose JSONException is a
 * checked exception.
 */
final class Json {

    private Json() {
    }

    /** Build an object from alternating keys and values; null values are stored as JSON null. */
    static JSONObject obj(final Object... keysAndValues) {
        final JSONObject o = new JSONObject();
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

    static JSONArray arr() {
        return new JSONArray();
    }

    static JSONObject parseObject(final String text) throws ApiException {
        try {
            return new JSONObject(text);
        } catch (final JSONException e) {
            throw new ApiException(400, "invalid_json", "The request body is not a valid JSON object.");
        }
    }

    /** Parse an upstream (YaCy) JSON response; failures are reported as upstream errors. */
    static JSONObject parseUpstream(final String text, final String source) throws ApiException {
        try {
            return new JSONObject(text);
        } catch (final JSONException e) {
            throw new ApiException(502, "upstream_error", "YaCy returned an unreadable response from " + source + ".");
        }
    }
}
