/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.discovery;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Local adapter for YaCy's checked-exception JSON implementation; nested values stay mutable. */
public final class JsonObject extends JSONObject {
    public JsonObject() { super(); }
    public JsonObject(final String text) { this(parse(text)); }
    public JsonObject(final JSONObject source) {
        super();
        for (final String key : source.keySet()) put(key, source.opt(key));
    }
    private static JSONObject parse(final String text) {
        try { return new JSONObject(text); } catch (final JSONException e) { throw new IllegalArgumentException("Invalid JSON object", e); }
    }
    static Object wrapValue(final Object value) {
        if (value instanceof JsonObject || value instanceof JsonArray) return value;
        if (value instanceof JSONObject) return new JsonObject((JSONObject) value);
        if (value instanceof JSONArray) return new JsonArray((JSONArray) value);
        return value == null ? JSONObject.NULL : value;
    }
    @Override public JsonObject put(final String key, final Object value) {
        try { super.put(key, wrapValue(value)); return this; } catch (final JSONException e) { throw new IllegalArgumentException("Invalid JSON value", e); }
    }
    @Override public JsonObject put(final String key, final boolean value) { return put(key, (Object) value); }
    @Override public JsonObject put(final String key, final int value) { return put(key, (Object) value); }
    @Override public JsonObject put(final String key, final long value) { return put(key, (Object) value); }
    @Override public JsonObject put(final String key, final double value) { return put(key, (Object) value); }
    @Override public Object get(final String key) {
        try { return super.get(key); } catch (final JSONException e) { throw new IllegalArgumentException("Missing JSON field " + key, e); }
    }
    @Override public String getString(final String key) {
        final Object v = get(key); if (!(v instanceof String)) throw new IllegalArgumentException("Expected string " + key); return (String) v;
    }
    @Override public boolean getBoolean(final String key) {
        final Object v = get(key); if (!(v instanceof Boolean)) throw new IllegalArgumentException("Expected boolean " + key); return (Boolean) v;
    }
    @Override public int getInt(final String key) { return ((Number) get(key)).intValue(); }
    @Override public long getLong(final String key) { return ((Number) get(key)).longValue(); }
    @Override public JsonObject getJSONObject(final String key) { return (JsonObject) get(key); }
    @Override public JsonArray getJSONArray(final String key) { return (JsonArray) get(key); }
    @Override public JsonObject optJSONObject(final String key) { return opt(key) instanceof JsonObject ? (JsonObject) opt(key) : null; }
    @Override public JsonArray optJSONArray(final String key) { return opt(key) instanceof JsonArray ? (JsonArray) opt(key) : null; }
    public boolean isEmpty() { return length() == 0; }
    @Override public String toString(final int indent) {
        try { return super.toString(indent); } catch (final JSONException e) { throw new IllegalArgumentException("Cannot serialize JSON", e); }
    }
}
