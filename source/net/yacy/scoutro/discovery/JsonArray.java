/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.discovery;

import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;

public final class JsonArray extends JSONArray implements Iterable<Object> {
    public JsonArray() { super(); }
    public JsonArray(final JSONArray source) { for (int i = 0; i < source.length(); i++) put(source.opt(i)); }
    @Override public JsonArray put(final Object value) { super.put(JsonObject.wrapValue(value)); return this; }
    @Override public JsonArray put(final int value) { return put((Object) value); }
    @Override public JsonArray put(final long value) { return put((Object) value); }
    @Override public JsonArray put(final boolean value) { return put((Object) value); }
    @Override public Object get(final int index) {
        try { return super.get(index); } catch (final JSONException e) { throw new IllegalArgumentException("Missing array entry", e); }
    }
    @Override public JsonObject getJSONObject(final int index) { return (JsonObject) get(index); }
    @Override public JsonArray getJSONArray(final int index) { return (JsonArray) get(index); }
    @Override public String getString(final int index) { return (String) get(index); }
    public boolean isEmpty() { return length() == 0; }
    @Override public Iterator<Object> iterator() {
        return new Iterator<>() {
            private int index;
            @Override public boolean hasNext() { return this.index < length(); }
            @Override public Object next() { if (!hasNext()) throw new java.util.NoSuchElementException(); return get(this.index++); }
        };
    }
}
