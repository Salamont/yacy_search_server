/*
 *  ReadProgress
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

package net.yacy.scoutro.knowledge.sync;

import java.sql.Connection;
import java.sql.SQLException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * How far the LLM tier has read a long page whose content goes on (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.5): one small row
 * of {@code kg_meta} ({@code llm_layout:<doc_id>}) per page marked {@link LlmQueue#MORE_TEXT}, never a copy of the text.
 * It holds the position read up to and the end of the page's own content in the layout the first reading used, the
 * blocks that layout moved (so a continuation lays the page out the same way, whatever happened to the other pages of
 * the host since), the text length and the start of the input it belongs to, and the continuation passes that failed in
 * a row. The row goes when the page is read to its end, cut, changed or removed.
 */
final class ReadProgress {

    /** Read up to here in the layout ({@link PageText#text}). */
    final int readUpTo;
    /** End of the page's own content in the layout: moved navigation follows ({@link PageText#contentEnd}). */
    final int contentEnd;
    /** Length of the stored text. */
    final int length;
    /** Hex of the first 8 bytes of the LLM input ({@link LlmQueue#LLM_INPUT}) the progress belongs to. */
    final String input;
    /** The blocks the layout moved, in the stored text ({@link PageText#ranges}). */
    final int[][] moved;
    /** Continuation passes that ended in a failure in a row. */
    final int failedPasses;

    ReadProgress(final int readUpTo, final int contentEnd, final int length, final String input, final int[][] moved, final int failedPasses) {
        this.readUpTo = readUpTo;
        this.contentEnd = contentEnd;
        this.length = length;
        this.input = input;
        this.moved = moved == null ? new int[0][] : moved;
        this.failedPasses = failedPasses;
    }

    static String key(final String docId) {
        return LlmQueue.LAYOUT_PREFIX + docId;
    }

    /** Hex of the first 8 bytes of an input hash. */
    static String input(final byte[] llmInput) {
        if (llmInput == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, llmInput.length); i++) {
            sb.append(String.format("%02x", llmInput[i] & 0xff));
        }
        return sb.toString();
    }

    boolean belongsTo(final byte[] llmInput) {
        return this.input.equals(input(llmInput));
    }

    ReadProgress readTo(final int position) {
        return new ReadProgress(position, this.contentEnd, this.length, this.input, this.moved, 0);
    }

    ReadProgress failedOnce() {
        return new ReadProgress(this.readUpTo, this.contentEnd, this.length, this.input, this.moved, this.failedPasses + 1);
    }

    /** Characters of the page's own content not read yet. */
    int unread() {
        return Math.max(0, this.contentEnd - this.readUpTo);
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof ReadProgress && json().equals(((ReadProgress) o).json());
    }

    @Override
    public int hashCode() {
        return json().hashCode();
    }

    String json() {
        final JSONArray m = new JSONArray();
        for (final int[] r : this.moved) {
            m.put(new JSONArray().put(r[0]).put(r[1]));
        }
        try {
            return new JSONObject().put("v", 2).put("r", this.readUpTo).put("e", this.contentEnd).put("n", this.length).put("h", this.input)
                    .put("m", m).put("f", this.failedPasses).toString();
        } catch (final JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The progress of a page, or null if it has none (or one of an earlier format, which is never trusted). */
    static ReadProgress load(final Connection c, final String docId) throws SQLException {
        final String v = KgStore.getMeta(c, key(docId));
        if (v == null || !v.startsWith("{")) {
            return null;
        }
        try {
            final JSONObject o = new JSONObject(v);
            if (o.optInt("v") != 2) {
                return null;
            }
            final JSONArray m = o.optJSONArray("m");
            final int[][] moved = new int[m == null ? 0 : m.length()][];
            for (int i = 0; i < moved.length; i++) {
                final JSONArray r = m.getJSONArray(i);
                moved[i] = new int[] {r.getInt(0), r.getInt(1)};
            }
            return new ReadProgress(o.getInt("r"), o.getInt("e"), o.getInt("n"), o.optString("h", ""), moved, o.optInt("f"));
        } catch (final JSONException | RuntimeException e) {
            return null;
        }
    }

    void save(final Connection tx, final String docId) throws SQLException {
        KgStore.putMeta(tx, key(docId), json());
    }

    static void delete(final Connection tx, final String docId) throws SQLException {
        KgStore.deleteMeta(tx, key(docId));
    }
}
