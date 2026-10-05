/*
 *  WorkQueue
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The persistent work queue {@code kg_work} (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 5.1 and 5.2). One row per document; all methods run inside the caller's
 * transaction.
 * <p>
 * A claim writes a claim token into {@code claimed_at}. A new event for a
 * claimed document re-arms the row ({@code claimed_at = NULL}, highest event
 * version) and bumps {@code kg_doc.generation} in the same transaction, so the
 * publish of the claimed state fails its generation check and the completion
 * (which deletes only a row still carrying its token) leaves the re-armed row
 * for the next round. Nothing is lost between drain, claim and publish.
 */
public final class WorkQueue {

    public static final int REASON_ADD = 1;
    public static final int REASON_DELETE = 2;
    public static final int REASON_RECONCILE = 3;
    public static final int REASON_BACKFILL = 4;
    public static final int REASON_RETRY = 5;

    /** Lower runs first: deletions before additions, events before scans. */
    public static final int PRIO_DELETE = 1;
    public static final int PRIO_CATCHUP = 2;
    public static final int PRIO_ADD = 3;
    public static final int PRIO_VERIFIED = 4;
    public static final int PRIO_RECONCILE = 5;
    public static final int PRIO_BACKFILL = 6;
    public static final int PRIO_REEXTRACT = 8;

    private WorkQueue() {
    }

    /** One claimed row. {@code generation} is the document's generation at claim time, -1 if not tracked. */
    public static final class Item {
        public final String docId;
        public final int reason;
        public final long eventVersion;
        public final int attempts;
        public final long generation;
        public final long claim;

        Item(final String docId, final int reason, final long eventVersion, final int attempts, final long generation, final long claim) {
            this.docId = docId;
            this.reason = reason;
            this.eventVersion = eventVersion;
            this.attempts = attempts;
            this.generation = generation;
            this.claim = claim;
        }

        @Override
        public String toString() {
            return this.docId + " r" + this.reason + "@" + this.eventVersion + " g" + this.generation;
        }
    }

    /** Counts of one enqueue call. */
    public static final class Added {
        public int inserted;
        public int rearmed;
        public int dropped;
    }

    /**
     * Persists drained events: re-arms an existing row (keeping the highest
     * event version), inserts new rows while the queue has room, and bumps the
     * generation of tracked documents. New IDs beyond {@code maxItems} are
     * dropped and counted; the caller then schedules a reconcile.
     */
    public static Added events(final Connection tx, final List<DirtySet.Event> events, final long now, final long maxItems)
            throws SQLException {
        final Added a = new Added();
        long size = size(tx);
        try (PreparedStatement up = tx.prepareStatement("UPDATE kg_work SET"
                + " reason = CASE WHEN ? >= event_version THEN ? ELSE reason END,"
                + " event_version = max(event_version, ?), priority = min(priority, ?), not_before = min(not_before, ?),"
                + " attempts = 0, claimed_at = NULL WHERE doc_id = ?");
                PreparedStatement ins = tx.prepareStatement("INSERT INTO kg_work (doc_id, reason, event_version, priority, not_before,"
                        + " attempts, claimed_at, enqueued_at) VALUES (?, ?, ?, ?, ?, 0, NULL, ?)");
                PreparedStatement gen = tx.prepareStatement("UPDATE kg_doc SET generation = generation + 1 WHERE doc_id = ?")) {
            for (final DirtySet.Event e : events) {
                final int reason = e.delete ? REASON_DELETE : REASON_ADD;
                final int prio = e.delete ? PRIO_DELETE : PRIO_ADD;
                up.setLong(1, e.version);
                up.setInt(2, reason);
                up.setLong(3, e.version);
                up.setInt(4, prio);
                up.setLong(5, now);
                up.setString(6, e.id);
                if (up.executeUpdate() > 0) {
                    a.rearmed++;
                } else if (size < maxItems) {
                    ins.setString(1, e.id);
                    ins.setInt(2, reason);
                    ins.setLong(3, e.version);
                    ins.setInt(4, prio);
                    ins.setLong(5, now);
                    ins.setLong(6, now);
                    ins.executeUpdate();
                    size++;
                    a.inserted++;
                } else {
                    a.dropped++;
                    continue;
                }
                gen.setString(1, e.id);
                gen.executeUpdate();
            }
        }
        return a;
    }

    /**
     * Enqueues documents found by a scan. An existing row is left as it is
     * (it is already pending, possibly with a newer event).
     */
    public static Added scanned(final Connection tx, final Collection<String> ids, final int reason, final int priority,
            final long eventVersion, final long now, final long maxItems) throws SQLException {
        final Added a = new Added();
        long size = size(tx);
        try (PreparedStatement has = tx.prepareStatement("SELECT 1 FROM kg_work WHERE doc_id = ?");
                PreparedStatement ins = tx.prepareStatement("INSERT INTO kg_work (doc_id, reason, event_version, priority, not_before,"
                        + " attempts, claimed_at, enqueued_at) VALUES (?, ?, ?, ?, ?, 0, NULL, ?)")) {
            for (final String id : ids) {
                has.setString(1, id);
                try (ResultSet rs = has.executeQuery()) {
                    if (rs.next()) {
                        continue;
                    }
                }
                if (size >= maxItems) {
                    a.dropped++;
                    continue;
                }
                ins.setString(1, id);
                ins.setInt(2, reason);
                ins.setLong(3, eventVersion);
                ins.setInt(4, priority);
                ins.setLong(5, now);
                ins.setLong(6, now);
                ins.executeUpdate();
                size++;
                a.inserted++;
            }
        }
        return a;
    }

    /**
     * Claims up to {@code max} due rows in priority order with token
     * {@code claim}. Claims left over from an earlier batch (the sync thread
     * is the only claimant, so none can be in flight) are released first.
     *
     * @param deletesOnly only delete events (while new growth is refused)
     */
    public static List<Item> claim(final Connection tx, final int max, final long now, final long claim, final boolean deletesOnly)
            throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_work SET claimed_at = NULL WHERE claimed_at IS NOT NULL")) {
            ps.executeUpdate();
        }
        final List<Item> out = new ArrayList<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT w.doc_id, w.reason, w.event_version, w.attempts, d.generation"
                + " FROM kg_work w LEFT JOIN kg_doc d ON d.doc_id = w.doc_id"
                + " WHERE w.not_before <= ?" + (deletesOnly ? " AND w.reason = " + REASON_DELETE : "")
                + " ORDER BY w.priority, w.not_before LIMIT ?")) {
            ps.setLong(1, now);
            ps.setInt(2, max);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final Object g = rs.getObject(5);
                    out.add(new Item(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getInt(4),
                            g == null ? -1L : rs.getLong(5), claim));
                }
            }
        }
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_work SET claimed_at = ? WHERE doc_id = ?")) {
            for (final Item i : out) {
                ps.setLong(1, claim);
                ps.setString(2, i.docId);
                ps.executeUpdate();
            }
        }
        return out;
    }

    /** Removes the row if it still carries the claim (a re-armed row stays). */
    public static boolean complete(final Connection tx, final Item item) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_work WHERE doc_id = ? AND claimed_at = ?")) {
            ps.setString(1, item.docId);
            ps.setLong(2, item.claim);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Gives a claimed row back, due at {@code notBefore}; {@code attempt}
     * counts a failed lookup (not a deferral). A re-armed row is not touched.
     */
    public static void release(final Connection tx, final Item item, final long notBefore, final boolean attempt) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_work SET claimed_at = NULL, not_before = ?,"
                + " attempts = attempts + ? WHERE doc_id = ? AND claimed_at = ?")) {
            ps.setLong(1, notBefore);
            ps.setInt(2, attempt ? 1 : 0);
            ps.setString(3, item.docId);
            ps.setLong(4, item.claim);
            ps.executeUpdate();
        }
    }

    /** Releases every claim (start). */
    public static int resetClaims(final Connection tx) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_work SET claimed_at = NULL WHERE claimed_at IS NOT NULL")) {
            return ps.executeUpdate();
        }
    }

    public static long size(final Connection c) throws SQLException {
        return KgStore.queryLong(c, "SELECT count(*) FROM kg_work");
    }

    /** Earliest due time of a row, or 0 if the queue is empty. */
    public static long nextDue(final Connection c) throws SQLException {
        return KgStore.queryLong(c, "SELECT coalesce(min(not_before), 0) FROM kg_work");
    }

    /** Arrival of the oldest queued change, or 0 if the queue is empty. */
    public static long oldest(final Connection c) throws SQLException {
        return KgStore.queryLong(c, "SELECT coalesce(min(enqueued_at), 0) FROM kg_work");
    }
}
