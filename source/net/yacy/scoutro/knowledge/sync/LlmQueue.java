/*
 *  LlmQueue
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

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The LLM tier's persistent queue {@code kg_llm_work} and the per-document
 * LLM state in {@code kg_doc} (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3 and 6.5).
 * <p>
 * A document is to do while it is active, has an input hash and
 * {@code llm_status} is null; the publisher resets the status whenever the
 * input changes. The scan walks these documents (a partial index) and either
 * queues them or marks them skipped; the worker marks them done or failed.
 * Every mark carries the input hash it was made for, so a newer input is
 * never marked by an older run.
 */
final class LlmQueue {

    static final int STATUS_DONE = 1;
    static final int STATUS_FAILED = 2;
    static final int STATUS_SKIPPED = 3;

    static final String SKIP_NOT_SELECTED = "not_selected";
    static final String SKIP_NOT_CANDIDATE = "not_candidate";
    static final String SKIP_HOST_CAP = "host_cap";
    static final String SKIP_NO_HOST = "no_host";
    static final String FAILED = "llm_failed";

    private static final Pattern P1 = Pattern.compile(
            "(?i)(?:^|/)(?:impressum|imprint|legal-?notice|anbieterkennzeichnung|about|about-us|ueber-uns|uber-uns|über-uns|wir)(?:[./_\\-]|$)");
    private static final Pattern P2 = Pattern.compile(
            "(?i)(?:^|/)(?:leistungen|services|angebot|angebote|standort|standorte|location|locations|einrichtungen|kontakt|contact|team)"
            + "(?:[./_\\-]|$)");
    private static final Pattern HOME = Pattern.compile("(?i)^/(?:index\\.(?:html?|php)|home|start|de|en)?/?$");

    /** A claimed queue item. */
    static final class Item {
        final String docId;
        final String hostId;
        final int attempts;

        Item(final String docId, final String hostId, final int attempts) {
            this.docId = docId;
            this.hostId = hostId;
            this.attempts = attempts;
        }
    }

    /** A document the scan found to do. */
    static final class Todo {
        final long rowid;
        final String docId;
        final String hostId;
        final String url;
        final byte[] inputHash;
        final List<String> collections;

        Todo(final long rowid, final String docId, final String hostId, final String url, final byte[] inputHash,
                final List<String> collections) {
            this.rowid = rowid;
            this.docId = docId;
            this.hostId = hostId;
            this.url = url;
            this.inputHash = inputHash;
            this.collections = collections;
        }
    }

    private LlmQueue() {}

    /** Queue priority by path (0 first): imprint and about pages, then services, locations and contact, then the home page. */
    static int priority(final String url) {
        String path;
        try {
            path = url == null ? null : new URI(url).getRawPath();
        } catch (final Exception e) {
            path = null;
        }
        if (path == null) {
            return 5;
        }
        if (P1.matcher(path).find()) {
            return 1;
        }
        if (P2.matcher(path).find()) {
            return 2;
        }
        return path.isEmpty() || HOME.matcher(path).matches() ? 3 : 5;
    }

    /** Up to {@code limit} documents to do after {@code afterRowid}, in row order. */
    static List<Todo> todo(final Connection c, final long afterRowid, final int limit) throws SQLException {
        final List<Todo> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT d.doc_rowid, d.doc_id, d.host_id, d.url, d.input_hash,"
                + " (SELECT group_concat(k.name, ',') FROM kg_doc_collection dc JOIN kg_collection k ON k.coll_id = dc.coll_id"
                + " WHERE dc.doc_rowid = d.doc_rowid)"
                + " FROM kg_doc d INDEXED BY kg_doc_llm_todo WHERE d.llm_status IS NULL AND d.state = 1 AND d.input_hash IS NOT NULL"
                + " AND d.doc_rowid > ?"
                + " ORDER BY d.doc_rowid LIMIT ?")) {
            ps.setLong(1, afterRowid);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final String colls = rs.getString(6);
                    out.add(new Todo(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBytes(5),
                            colls == null || colls.isEmpty() ? List.of() : Arrays.asList(colls.split(","))));
                }
            }
        }
        return out;
    }

    static long size(final Connection c) throws SQLException {
        return KgStore.queryLong(c, "SELECT count(*) FROM kg_llm_work");
    }

    static long claimed(final Connection c) throws SQLException {
        return KgStore.queryLong(c, "SELECT count(*) FROM kg_llm_work WHERE claimed_at IS NOT NULL");
    }

    static Long oldest(final Connection c) throws SQLException {
        final long v = KgStore.queryLong(c, "SELECT coalesce(min(enqueued_at), 0) FROM kg_llm_work");
        return v == 0L ? null : v;
    }

    /** Documents of the host the tier has finished plus those queued for it (the per-host cap). */
    static long hostLoad(final Connection c, final String hostId) throws SQLException {
        long n = 0L;
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM kg_doc WHERE host_id = ? AND llm_status = 1")) {
            ps.setString(1, hostId);
            try (ResultSet rs = ps.executeQuery()) {
                n += rs.next() ? rs.getLong(1) : 0L;
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM kg_llm_work WHERE host_id = ?")) {
            ps.setString(1, hostId);
            try (ResultSet rs = ps.executeQuery()) {
                n += rs.next() ? rs.getLong(1) : 0L;
            }
        }
        return n;
    }

    /** Queues a document unless it is queued already; true if it was added. */
    static boolean offer(final Connection tx, final String docId, final String hostId, final int priority, final long now)
            throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("INSERT OR IGNORE INTO kg_llm_work (doc_id, host_id, priority, not_before,"
                + " enqueued_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, docId);
            ps.setString(2, hostId);
            ps.setInt(3, priority);
            ps.setLong(4, now);
            ps.setLong(5, now);
            return ps.executeUpdate() > 0;
        }
    }

    /** Claims the next due item; null if none. */
    static Item claim(final Connection tx, final long now) throws SQLException {
        final Item item;
        try (PreparedStatement ps = tx.prepareStatement("SELECT doc_id, host_id, attempts FROM kg_llm_work"
                + " WHERE claimed_at IS NULL AND not_before <= ? ORDER BY priority, not_before LIMIT 1")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                item = new Item(rs.getString(1), rs.getString(2), rs.getInt(3));
            }
        }
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_llm_work SET claimed_at = ? WHERE doc_id = ?")) {
            ps.setLong(1, now);
            ps.setString(2, item.docId);
            ps.executeUpdate();
        }
        return item;
    }

    /** Returns an item to the queue, due at {@code notBefore}; {@code attempt} counts a failed try. */
    static void release(final Connection tx, final String docId, final long notBefore, final boolean attempt) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_llm_work SET claimed_at = NULL, not_before = ?,"
                + " attempts = attempts + ? WHERE doc_id = ?")) {
            ps.setLong(1, notBefore);
            ps.setInt(2, attempt ? 1 : 0);
            ps.setString(3, docId);
            ps.executeUpdate();
        }
    }

    static void complete(final Connection tx, final String docId) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_llm_work WHERE doc_id = ?")) {
            ps.setString(1, docId);
            ps.executeUpdate();
        }
    }

    /** Claims of a previous run (a stop during a call) become due again. */
    static int resetClaims(final Connection tx) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_llm_work SET claimed_at = NULL WHERE claimed_at IS NOT NULL")) {
            return ps.executeUpdate();
        }
    }

    /**
     * Marks a document for the input hash it was examined with; nothing
     * happens if the input changed meanwhile.
     *
     * @return true if the mark was set
     */
    static boolean mark(final Connection tx, final String docId, final byte[] inputHash, final int status, final String reason)
            throws SQLException {
        // the mark is the content hash (schema 4): a new vocabulary alone does not make the document to do again
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_doc SET llm_status = ?, llm_hash = coalesce(content_hash, input_hash),"
                + " llm_reason = ? WHERE doc_id = ? AND input_hash = ? AND state = 1")) {
            ps.setInt(1, status);
            ps.setString(2, reason);
            ps.setString(3, docId);
            ps.setBytes(4, inputHash);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Makes documents with the given status to do again for a new extractor (prompt or version), keeping their
     * evidence until the new result replaces it.
     */
    static int reexamine(final Connection tx, final int status) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_doc SET llm_status = NULL, llm_reason = NULL WHERE llm_status = ?")) {
            ps.setInt(1, status);
            return ps.executeUpdate();
        }
    }

    /** Makes documents with the given status to do again (admin retry, changed selection). */
    static int reopen(final Connection tx, final int status) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_doc SET llm_status = NULL, llm_hash = NULL, llm_reason = NULL"
                + " WHERE llm_status = ?")) {
            ps.setInt(1, status);
            return ps.executeUpdate();
        }
    }

    /** Documents per LLM status: {done, failed, skipped}. */
    static long[] counts(final Connection c) throws SQLException {
        final long[] out = new long[3];
        try (PreparedStatement ps = c.prepareStatement("SELECT llm_status, count(*) FROM kg_doc WHERE llm_status IS NOT NULL"
                + " GROUP BY llm_status"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final int s = rs.getInt(1);
                if (s >= 1 && s <= 3) {
                    out[s - 1] = rs.getLong(2);
                }
            }
        }
        return out;
    }

    /** Empties the queue (full reset). */
    static int clear(final Connection tx) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_llm_work")) {
            return ps.executeUpdate();
        }
    }
}
