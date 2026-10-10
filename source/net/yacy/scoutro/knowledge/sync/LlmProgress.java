/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.sync;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.store.KgStore;

import org.json.*;

import java.sql.*;
import java.util.*;

/**
 * Validated unfinished chunk checkpoints, independent of the optional/evictable cache. Existing
 * bounded kg_meta values avoid a schema/extractor migration. Only unfinished work is checkpointed;
 * completion removes it in the publication transaction.
 */
final class LlmProgress {
    static final String PREFIX = "llm_progress:";
    private static final int SEGMENT = 3000, MAX_CHARS = 2 * 1024 * 1024;
    final Map<String, ExtractionCache.Entry> entries = new LinkedHashMap<>();
    private long previousBytes;

    static String key(byte[] key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    void put(byte[] key, int status, JSONObject value) {
        entries.put(key(key), new ExtractionCache.Entry(status, value));
    }

    ExtractionCache.Entry get(byte[] key) {
        return entries.get(key(key));
    }

    static LlmProgress load(Connection c, String document, Set<String> required)
            throws SQLException {
        LlmProgress result = new LlmProgress();
        result.previousBytes = storedBytes(c, document);
        String prefix = PREFIX + document + ":";
        try (PreparedStatement p =
                c.prepareStatement(
                        "SELECT key,value FROM kg_meta WHERE key GLOB ? AND key GLOB '*:info'")) {
            p.setString(1, prefix + "*");
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    try {
                        JSONObject info = new JSONObject(r.getString(2));
                        if (!required.contains(info.getString("key"))) continue;
                        String stem = r.getString(1).substring(0, r.getString(1).length() - 4);
                        int n = info.getInt("segments");
                        if (n < 1 || n > (MAX_CHARS + SEGMENT - 1) / SEGMENT)
                            throw new SQLException("invalid unfinished LLM checkpoint size");
                        StringBuilder text = new StringBuilder();
                        boolean complete = true;
                        for (int i = 0; i < n; i++) {
                            String part = KgStore.getMeta(c, stem + i);
                            if (part == null) {
                                complete = false;
                                break;
                            }
                            text.append(part);
                        }
                        if (!complete || text.length() > MAX_CHARS)
                            throw new SQLException("incomplete unfinished LLM checkpoint");
                        result.entries.put(
                                info.getString("key"),
                                new ExtractionCache.Entry(
                                        info.getInt("status"), new JSONObject(text.toString())));
                    } catch (JSONException e) {
                        throw new SQLException("invalid unfinished LLM checkpoint", e);
                    }
                }
            }
        }
        return result;
    }

    long estimate() {
        long size = 1024 + previousBytes;
        for (ExtractionCache.Entry e : entries.values())
            size += 4L * e.value.toString().length() + 1024;
        return size;
    }

    void save(Connection c, String document) throws SQLException {
        clear(c, document);
        int index = 0;
        for (Map.Entry<String, ExtractionCache.Entry> e : entries.entrySet()) {
            String value = e.getValue().value.toString();
            if (value.length() > MAX_CHARS)
                throw new SQLException("unfinished LLM chunk exceeds checkpoint bound");
            String stem = PREFIX + document + ":" + (index++) + ":";
            int n = (value.length() + SEGMENT - 1) / SEGMENT;
            for (int i = 0; i < n; i++)
                KgStore.putMeta(
                        c,
                        stem + i,
                        value.substring(i * SEGMENT, Math.min(value.length(), (i + 1) * SEGMENT)));
            KgStore.putMeta(
                    c,
                    stem + "info",
                    KgJson.obj("key", e.getKey(), "status", e.getValue().status, "segments", n)
                            .toString());
        }
    }

    static void clear(Connection c, String document) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("DELETE FROM kg_meta WHERE key GLOB ?")) {
            p.setString(1, PREFIX + document + ":*");
            p.executeUpdate();
        }
    }

    static void prune(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            // doc IDs are fixed 12-character YaCy IDs. Orphaned bookkeeping never exposes history.
            s.executeUpdate(
                    "DELETE FROM kg_meta WHERE key IN (SELECT key FROM kg_meta WHERE key GLOB"
                        + " 'llm_progress:*' AND NOT EXISTS (SELECT 1 FROM kg_llm_work w WHERE"
                        + " w.doc_id=substr(kg_meta.key,14,12)) AND NOT EXISTS (SELECT 1 FROM"
                        + " kg_doc d WHERE d.doc_id=substr(kg_meta.key,14,12) AND d.llm_status=2)"
                        + " ORDER BY CASE WHEN key GLOB '*:info' THEN 0 ELSE 1 END,key LIMIT 4)");
        }
    }

    static long storedBytes(Connection c, String document) throws SQLException {
        try (PreparedStatement p =
                c.prepareStatement(
                        "SELECT coalesce(sum(length(value)),0)*4 FROM kg_meta WHERE key GLOB ?")) {
            p.setString(1, PREFIX + document + ":*");
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getLong(1) : 0;
            }
        }
    }

    static boolean resetBatch(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            return s.executeUpdate(
                            "DELETE FROM kg_meta WHERE key IN (SELECT key FROM kg_meta WHERE key"
                                + " GLOB 'llm_progress:*' LIMIT 64)")
                    > 0;
        }
    }
}
