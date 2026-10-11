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
    /**
     * The worker could not confirm the input it was to read (Solr lacked the page or stayed ahead of the graph) within
     * its bounded retries; a new input or the admin retry makes the page to do again.
     */
    static final String INPUT_UNCONFIRMED = "input_unconfirmed";
    /** Done: a continuation reads more of the long page's text later (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.5). */
    static final String MORE_TEXT = "more_text";
    /** Done: text behind what the tier reads stays unread (continuations off, a legal page, or their limit reached). */
    static final String TEXT_CUT = "text_cut";
    /** Queue priority of a continuation: after every first reading, until it waited {@link #AGING_MILLIS}. */
    static final int CONTINUATION_PRIORITY = 8;
    /**
     * What the LLM tier read: the content hash (schema 4), the input hash for a page published before it. A new
     * vocabulary or extractor version changes the input hash, not what the tier reads; the comparisons, the marks and
     * the publication use this identity, so such a change neither throws an answer away nor leaves a page unmarked.
     */
    static final String LLM_INPUT = "coalesce(content_hash, input_hash)";

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
        /** {@link #LLM_INPUT} of the document. */
        final byte[] llmInput;
        final List<String> collections;

        Todo(final long rowid, final String docId, final String hostId, final String url, final byte[] llmInput,
                final List<String> collections) {
            this.rowid = rowid;
            this.docId = docId;
            this.hostId = hostId;
            this.url = url;
            this.llmInput = llmInput;
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

    /** Prefix of the persisted scan position of a collection ({@code llm_scan:<coll_id>} = {@code frontier:revisit}). */
    static final String CURSOR_PREFIX = "llm_scan:";
    /**
     * Per page whose first reading left text for a continuation ({@link #MORE_TEXT}): the other pages of its host the
     * reading's layout compared, so the continuation lays the page out the same way ({@code kg_meta}).
     */
    static final String LAYOUT_PREFIX = "llm_layout:";

    /**
     * The scan of one LLM collection (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.2): its frontier walks the collection's pages in
     * row order and is never reset by a restart; the revisit walks behind the frontier and finds pages that became to do
     * again there (a new text, a new extractor, a retry) or that were left unqueued by an earlier version.
     */
    static final class Lane {
        final int collId;
        final String name;
        /** False for a collection the graph follows but the LLM tier does not: its pages are only marked not selected. */
        final boolean followed;
        /** The last row the frontier examined. */
        long frontier;
        /** The last row the current revisit examined (0: a new sweep starts from the beginning). */
        long revisit;

        Lane(final int collId, final String name, final boolean followed, final long frontier, final long revisit) {
            this.collId = collId;
            this.name = name;
            this.followed = followed;
            this.frontier = frontier;
            this.revisit = revisit;
        }

        String key() {
            return CURSOR_PREFIX + this.collId;
        }

        String value() {
            return this.frontier + ":" + this.revisit;
        }
    }

    /**
     * The collections of the graph with their persisted positions, in collection order, each with whether the LLM tier
     * follows it. A position beyond the last row of the graph (a full reset or a rebuilt graph) starts again from the
     * beginning.
     */
    static List<Lane> lanes(final Connection c, final java.util.function.Predicate<String> follows) throws SQLException {
        final List<Lane> out = new ArrayList<>();
        final long last = KgStore.queryLong(c, "SELECT coalesce(max(doc_rowid), 0) FROM kg_doc");
        try (PreparedStatement ps = c.prepareStatement("SELECT coll_id, name FROM kg_collection ORDER BY coll_id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final String name = rs.getString(2);
                final Lane lane = new Lane(rs.getInt(1), name, follows.test(name), 0L, 0L);
                final String v = KgStore.getMeta(c, lane.key());
                if (v != null) {
                    try {
                        final String[] p = v.split(":");
                        lane.frontier = Long.parseLong(p[0]);
                        lane.revisit = p.length > 1 ? Long.parseLong(p[1]) : 0L;
                    } catch (final NumberFormatException e) {
                        lane.frontier = 0L;
                        lane.revisit = 0L;
                    }
                }
                if (lane.frontier > last || lane.revisit > lane.frontier) {
                    lane.frontier = 0L;
                    lane.revisit = 0L;
                }
                out.add(lane);
            }
        }
        return out;
    }

    /** Forgets every scan position: the frontiers walk their collections again (pages made to do again in bulk). */
    static int resetCursors(final Connection tx) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_meta WHERE key GLOB ?")) {
            ps.setString(1, CURSOR_PREFIX + "*");
            return ps.executeUpdate();
        }
    }

    private static final String LANE_TODO = "SELECT d.doc_rowid, d.doc_id, d.host_id, d.url, coalesce(d.content_hash, d.input_hash),"
            + " (SELECT group_concat(k.name, ',') FROM kg_doc_collection x JOIN kg_collection k ON k.coll_id = x.coll_id"
            + " WHERE x.doc_rowid = d.doc_rowid)"
            + " FROM kg_doc_collection dc INDEXED BY kg_doc_collection_coll JOIN kg_doc d ON d.doc_rowid = dc.doc_rowid"
            + " WHERE dc.coll_id = ? AND dc.doc_rowid > ? AND dc.doc_rowid <= ?"
            + " AND d.llm_status IS NULL AND d.state = 1 AND d.input_hash IS NOT NULL"
            + " AND NOT EXISTS (SELECT 1 FROM kg_llm_work w WHERE w.doc_id = d.doc_id)"
            + " ORDER BY dc.doc_rowid LIMIT ?";

    /**
     * Up to {@code limit} unqueued documents to do of one collection with a row in ({@code after}, {@code upTo}], in row
     * order: the frontier asks with {@code upTo} = {@link Long#MAX_VALUE}, the revisit with its frontier.
     */
    static List<Todo> laneTodo(final Connection c, final int collId, final long after, final long upTo, final int limit)
            throws SQLException {
        final List<Todo> out = new ArrayList<>();
        if (limit <= 0) {
            return out;
        }
        try (PreparedStatement ps = c.prepareStatement(LANE_TODO)) {
            ps.setInt(1, collId);
            ps.setLong(2, after);
            ps.setLong(3, upTo);
            ps.setInt(4, limit);
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

    /** The last row of a collection (0 if it has none). */
    static long lastRow(final Connection c, final int collId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT coalesce(max(doc_rowid), 0) FROM kg_doc_collection WHERE coll_id = ?")) {
            ps.setInt(1, collId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** Continuations in the queue. */
    static long continuations(final Connection c) throws SQLException {
        return KgStore.queryLong(c, "SELECT count(*) FROM kg_llm_work WHERE priority = " + CONTINUATION_PRIORITY);
    }

    /** Up to {@code limit} unqueued pages marked {@link #MORE_TEXT}, oldest row first: {doc_id, host_id}. */
    static List<String[]> moreText(final Connection c, final int limit) throws SQLException {
        final List<String[]> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT d.doc_id, d.host_id FROM kg_doc d INDEXED BY kg_doc_llm_status"
                + " WHERE d.llm_status = 1 AND d.llm_reason = '" + MORE_TEXT + "' AND d.state = 1 AND d.host_id IS NOT NULL"
                + " AND d.input_hash IS NOT NULL"
                + " AND NOT EXISTS (SELECT 1 FROM kg_llm_work w WHERE w.doc_id = d.doc_id) ORDER BY d.doc_rowid LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
        }
        return out;
    }

    /** Done pages per reason {@link #MORE_TEXT} and {@link #TEXT_CUT}. */
    static long[] textCounts(final Connection c) throws SQLException {
        final long[] out = new long[2];
        try (PreparedStatement ps = c.prepareStatement("SELECT llm_reason, count(*) FROM kg_doc INDEXED BY kg_doc_llm_status"
                + " WHERE llm_status = 1 AND llm_reason IN ('" + MORE_TEXT + "', '" + TEXT_CUT + "') GROUP BY llm_reason");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out[MORE_TEXT.equals(rs.getString(1)) ? 0 : 1] = rs.getLong(2);
            }
        }
        return out;
    }

    /** Items that are not claimed and due within {@code horizon}: what the worker has to do soon (the refill measure). */
    static long ready(final Connection c, final long horizon) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM kg_llm_work WHERE claimed_at IS NULL AND not_before <= ?")) {
            ps.setLong(1, horizon);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
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

    /**
     * Documents of the host the tier has finished plus those queued for it (the per-host cap). A queued continuation
     * belongs to a finished page and does not count again.
     */
    static long hostLoad(final Connection c, final String hostId) throws SQLException {
        long n = 0L;
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM kg_doc WHERE host_id = ? AND llm_status = 1")) {
            ps.setString(1, hostId);
            try (ResultSet rs = ps.executeQuery()) {
                n += rs.next() ? rs.getLong(1) : 0L;
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM kg_llm_work w WHERE w.host_id = ?"
                + " AND NOT EXISTS (SELECT 1 FROM kg_doc d WHERE d.doc_id = w.doc_id AND d.llm_status = " + STATUS_DONE + ")")) {
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

    /**
     * A queued item waiting longer than this is claimed before newer items of a higher priority: the page priorities
     * order the queue, but never hold an older page back for good (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.2).
     */
    static final long AGING_MILLIS = 2L * 60L * 60_000L;

    /** Claims the next due item; null if none. */
    static Item claim(final Connection tx, final long now) throws SQLException {
        return claim(tx,now,null);
    }
    static Item claim(final Connection tx,final long now,final java.util.Set<String> only)throws SQLException {
        if(only!=null&&only.isEmpty())return null;
        String scope=only==null?"":" AND doc_id IN ("+String.join(",",java.util.Collections.nCopies(only.size(),"?"))+")";
        final Item item;
        try (PreparedStatement ps = tx.prepareStatement("SELECT doc_id, host_id, attempts FROM kg_llm_work"
                + " WHERE claimed_at IS NULL AND not_before <= ?"+scope
                + " ORDER BY CASE WHEN enqueued_at <= ? THEN 0 ELSE priority END, not_before, doc_id LIMIT 1")) {
            ps.setLong(1, now);
            int i=2;
            if(only!=null){for(String doc:only)ps.setString(i++,doc);}
            ps.setLong(i, now - AGING_MILLIS);
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

    static void complete(final Connection tx, final String docId) throws SQLException {complete(tx,docId,false);}
    static void complete(final Connection tx,final String docId,boolean preserveProgress)throws SQLException {
        if(!preserveProgress)LlmProgress.clear(tx,docId);
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
     * Marks a document for the input ({@link #LLM_INPUT}) it was examined with; nothing happens if the input changed
     * meanwhile.
     *
     * @return true if the mark was set
     */
    static boolean mark(final Connection tx, final String docId, final byte[] llmInput, final int status, final String reason)
            throws SQLException {
        // the mark is the content hash (schema 4): a new vocabulary alone does not make the document to do again
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_doc SET llm_status = ?, llm_hash = " + LLM_INPUT + ","
                + " llm_reason = ? WHERE doc_id = ? AND " + LLM_INPUT + " = ? AND state = 1")) {
            ps.setInt(1, status);
            ps.setString(2, reason);
            ps.setString(3, docId);
            ps.setBytes(4, llmInput);
            return ps.executeUpdate() > 0;
        }
    }

    /** True while the document still needs the LLM tier: active, with an input and not marked. */
    static boolean toDo(final Connection c, final String docId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kg_doc WHERE doc_id = ? AND state = 1 AND input_hash IS NOT NULL"
                + " AND llm_status IS NULL")) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
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

    static long pending(Connection c,java.util.function.Predicate<String> selected)throws SQLException {
        List<String> names=new ArrayList<>();try(java.sql.Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT name FROM kg_collection")) {
            while(r.next())if(selected.test(r.getString(1)))names.add(r.getString(1));
        }
        if(names.isEmpty())return 0;
        try(PreparedStatement p=c.prepareStatement("SELECT count(*) FROM kg_doc d WHERE state=1 AND input_hash IS NOT NULL AND llm_status IS NULL"
                +" AND EXISTS(SELECT 1 FROM kg_doc_collection dc JOIN kg_collection k USING(coll_id) WHERE dc.doc_rowid=d.doc_rowid AND k.name IN("
                +String.join(",",java.util.Collections.nCopies(names.size(),"?"))+"))")) {
            int i=1;for(String name:names)p.setString(i++,name);try(ResultSet r=p.executeQuery()){r.next();return r.getLong(1);}
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

    /** Empties the queue and forgets the scan positions (full reset). */
    /** Removes up to 16 layouts of pages no longer waiting for a continuation (changed, deleted or reset meanwhile). */
    static void pruneLayouts(final Connection tx) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_meta WHERE key IN (SELECT m.key FROM kg_meta m WHERE m.key GLOB '"
                + LAYOUT_PREFIX + "*' AND NOT EXISTS (SELECT 1 FROM kg_doc d WHERE d.doc_id = substr(m.key, " + (LAYOUT_PREFIX.length() + 1)
                + ") AND d.llm_status = " + STATUS_DONE + " AND d.llm_reason = '" + MORE_TEXT + "') LIMIT 16)")) {
            ps.executeUpdate();
        }
    }

    static int clear(final Connection tx) throws SQLException {
        resetCursors(tx);
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_meta WHERE key GLOB ?")) {
            ps.setString(1, LAYOUT_PREFIX + "*");
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_llm_work")) {
            return ps.executeUpdate();
        }
    }
}
