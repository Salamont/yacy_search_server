/*
 *  ExtractionCache
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The LLM tier's result cache in {@code kg_extraction}
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.4): per chunk key the <em>validated</em>
 * answer (status 1) or the reason it was refused (status 2), deflated, at most
 * 64 KiB. Unresolved: entities are resolved per document, so a shared entry
 * never mixes identities. Least recently used entries go first once the cache
 * exceeds its share of the budget. Losing it only costs recomputation.
 */
final class ExtractionCache {

    static final int STATUS_OK = 1;
    static final int STATUS_REFUSED = 2;
    static final int MAX_BYTES = 65_536;
    private static final int EVICT_BATCH = 200;
    /** Inflated entries above this size are treated as corrupt. */
    private static final int MAX_INFLATED = 1 << 20;

    /** A cached chunk result. */
    static final class Entry {
        final int status;
        /** {@code entities} and {@code claims} of an accepted answer; {@code refused} of a refused one. */
        final JSONObject value;

        Entry(final int status, final JSONObject value) {
            this.status = status;
            this.value = value;
        }
    }

    private ExtractionCache() {}

    /** The entry for {@code key}, or null (also for an entry that cannot be read). */
    static Entry get(final Connection c, final byte[] key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status, result FROM kg_extraction WHERE cache_key = ?")) {
            ps.setBytes(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                final JSONObject v = decode(rs.getBytes(2));
                return v == null ? null : new Entry(rs.getInt(1), v);
            }
        }
    }

    /** Stores an entry; false if it is too large to cache. */
    static boolean put(final Connection tx, final byte[] key, final int extId, final int status, final JSONObject value,
            final long now) throws SQLException {
        final byte[] blob = encode(value);
        if (blob.length > MAX_BYTES) {
            return false;
        }
        try (PreparedStatement ps = tx.prepareStatement("INSERT OR REPLACE INTO kg_extraction (cache_key, ext_id, status, result, bytes,"
                + " last_used) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setBytes(1, key);
            ps.setInt(2, extId);
            ps.setInt(3, status);
            ps.setBytes(4, blob);
            ps.setLong(5, blob.length + key.length);
            ps.setLong(6, now);
            ps.executeUpdate();
        }
        return true;
    }

    /** Marks entries as used (LRU). */
    static void touch(final Connection tx, final Collection<byte[]> keys, final long now) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_extraction SET last_used = ? WHERE cache_key = ?")) {
            for (final byte[] k : keys) {
                ps.setLong(1, now);
                ps.setBytes(2, k);
                ps.executeUpdate();
            }
        }
    }

    static long bytes(final Connection c) throws SQLException {
        return KgStore.queryLong(c, "SELECT coalesce(sum(bytes), 0) FROM kg_extraction");
    }

    static long entries(final Connection c) throws SQLException {
        return KgStore.queryLong(c, "SELECT count(*) FROM kg_extraction");
    }

    /**
     * Deletes least recently used entries while the cache holds more than
     * {@code maxBytes}, down to 90 % of it; at most {@code maxBatches} batches.
     *
     * @return the cache size afterwards
     */
    static long evict(final Connection tx, final long maxBytes, final int maxBatches) throws SQLException {
        long size = bytes(tx);
        if (size <= maxBytes) {
            return size;
        }
        final long target = maxBytes / 10L * 9L;
        for (int i = 0; i < maxBatches && size > target; i++) {
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_extraction WHERE cache_key IN"
                    + " (SELECT cache_key FROM kg_extraction ORDER BY last_used LIMIT ?)")) {
                ps.setInt(1, EVICT_BATCH);
                if (ps.executeUpdate() == 0) {
                    break;
                }
            }
            size = bytes(tx);
        }
        return size;
    }

    static byte[] encode(final JSONObject value) {
        final byte[] raw = value.toString().getBytes(StandardCharsets.UTF_8);
        final Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            d.setInput(raw);
            d.finish();
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 3));
            final byte[] buf = new byte[4096];
            while (!d.finished()) {
                out.write(buf, 0, d.deflate(buf));
            }
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    static JSONObject decode(final byte[] blob) {
        if (blob == null) {
            return null;
        }
        final Inflater inf = new Inflater();
        try {
            inf.setInput(blob);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(blob.length * 3);
            final byte[] buf = new byte[4096];
            while (!inf.finished()) {
                final int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    return null;
                }
                out.write(buf, 0, n);
                if (out.size() > MAX_INFLATED) {
                    return null;
                }
            }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (final DataFormatException | JSONException e) {
            return null;
        } finally {
            inf.end();
        }
    }
}
