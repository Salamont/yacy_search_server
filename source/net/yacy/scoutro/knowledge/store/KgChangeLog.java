/*
 *  KgChangeLog
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

package net.yacy.scoutro.knowledge.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgIds;

/**
 * Coalesced change feed with delete notices that survive coalescing
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, "Export and change feed").
 * <p>
 * One row per object. A new change replaces the row with a higher sequence
 * number. {@code scopes_now} holds the collections the object is visible in
 * after the change; {@code scopes_seen} accumulates every collection the
 * object was visible in since the row was first written, before and after
 * each change. A consumer restricted to collection A therefore still gets a
 * removal after A → B → C, even if it was offline during both changes. Removal
 * notices can be redundant (for an object the consumer never stored); they are
 * never missing. Consumers treat the delete of an unknown id as a no-op.
 * <p>
 * Cursor: {@code <epoch>:<seq>}. It is valid while the dataset epoch is
 * unchanged and {@code seq >= changes_min_seq - 1}; retention raises
 * {@code changes_min_seq} when it removes rows.
 */
public final class KgChangeLog {

    /**
     * What changed. {@code DERIVED} (schema 4) is a derived relation or a
     * suggested match ({@code kg_derived}); its collections never change over
     * its life (they are part of its ID), and a viewer sees it only with
     * <em>all</em> of them, because it combines the facts of two collections.
     */
    public enum Kind {
        ENTITY(1), STATEMENT(2), DERIVED(3), OBSERVATION(4);

        public final int code;

        Kind(final int code) {
            this.code = code;
        }

        static Kind of(final int code) {
            return code == 1 ? ENTITY : code == 3 ? DERIVED : code == 4 ? OBSERVATION : STATEMENT;
        }

        /** The name in the export and the feed. */
        public String label() {
            return this == ENTITY ? "entity" : this == DERIVED ? "derived" : this == OBSERVATION ? "observation" : "statement";
        }
    }

    public enum Op {
        UPSERT(1), DELETE(2), REDIRECT(3);

        public final int code;

        Op(final int code) {
            this.code = code;
        }

        static Op of(final int code) {
            return code == 1 ? UPSERT : code == 2 ? DELETE : REDIRECT;
        }
    }

    /** Collections a reader may see; {@link #ALL} for the administrator without a filter. */
    public static final class Viewer {
        public static final Viewer ALL = new Viewer(null);
        private final Set<Integer> collections;

        private Viewer(final Set<Integer> collections) {
            this.collections = collections;
        }

        public static Viewer of(final Set<Integer> collections) {
            return new Viewer(Collections.unmodifiableSet(new TreeSet<>(collections)));
        }

        /** True for the administrator without a collection filter. */
        public boolean all() {
            return this.collections == null;
        }

        /** The visible collection IDs (empty: nothing is visible); null for {@link #ALL}. */
        public Set<Integer> collections() {
            return this.collections;
        }

        boolean intersects(final Set<Integer> scopes) {
            for (final Integer s : scopes) {
                if (this.collections.contains(s)) {
                    return true;
                }
            }
            return false;
        }

        /** True if every one of {@code scopes} is visible (and there is at least one). */
        public boolean containsAll(final Set<Integer> scopes) {
            return this.collections == null || !scopes.isEmpty() && this.collections.containsAll(scopes);
        }
    }

    /** One change as a given viewer must see it. */
    public static final class Item {
        public final long seq;
        public final Kind kind;
        public final String id;
        public final Op op;
        public final String redirectTo;
        public final long at;

        Item(final long seq, final Kind kind, final String id, final Op op, final String redirectTo, final long at) {
            this.seq = seq;
            this.kind = kind;
            this.id = id;
            this.op = op;
            this.redirectTo = redirectTo;
            this.at = at;
        }
    }

    public static final class Page {
        public final List<Item> items;
        /** Cursor to continue with; advances over rows the viewer may not see. */
        public final String next;
        public final boolean hasMore;

        Page(final List<Item> items, final String next, final boolean hasMore) {
            this.items = items;
            this.next = next;
            this.hasMore = hasMore;
        }
    }

    private static final Pattern CURSOR = Pattern.compile("^([0-9a-f]{16}):([0-9]{1,18})$");
    private static final int MAX_LIMIT = 1000;

    private KgChangeLog() {}

    public static String cursor(final String epoch, final long seq) {
        return epoch + ":" + seq;
    }

    /**
     * Records a change inside the caller's write transaction.
     *
     * @param before collections the object was visible in before the change
     * @param after  collections it is visible in after the change (empty for a delete)
     */
    public static void record(final Connection tx, final Kind kind, final String publicId, final Op op,
            final String redirectTo, final Set<Integer> before, final Set<Integer> after, final long now)
            throws SQLException {
        final Set<Integer> seen = new TreeSet<>(before);
        seen.addAll(after);
        try (PreparedStatement ps = tx.prepareStatement(
                "SELECT scopes_now, scopes_seen FROM kg_change WHERE kind = ? AND public_id = ?")) {
            ps.setInt(1, kind.code);
            ps.setString(2, publicId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    seen.addAll(parse(rs.getString(1)));
                    seen.addAll(parse(rs.getString(2)));
                }
            }
        }
        try (PreparedStatement del = tx.prepareStatement("DELETE FROM kg_change WHERE kind = ? AND public_id = ?")) {
            del.setInt(1, kind.code);
            del.setString(2, publicId);
            del.executeUpdate();
        }
        try (PreparedStatement ins = tx.prepareStatement("INSERT INTO kg_change"
                + " (kind, public_id, op, redirect_to, scopes_now, scopes_seen, at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ins.setInt(1, kind.code);
            ins.setString(2, publicId);
            ins.setInt(3, op.code);
            ins.setString(4, op == Op.REDIRECT ? redirectTo : null);
            ins.setString(5, op == Op.DELETE ? "" : format(after));
            ins.setString(6, format(seen));
            ins.setLong(7, now);
            ins.executeUpdate();
        }
    }

    /**
     * Reads the changes after {@code cursor} as {@code viewer} must see them;
     * {@code null} as cursor starts at the beginning of the retained feed only
     * if nothing was purged yet (otherwise the consumer needs a full export).
     */
    public static Page read(final Connection c, final String cursor, final Viewer viewer, final int limit)
            throws SQLException, KgException {
        final String epoch = KgStore.getMeta(c, KgSchema.META_EPOCH);
        final long minSeq = Long.parseLong(KgStore.getMeta(c, KgSchema.META_CHANGES_MIN_SEQ));
        final long after;
        if (cursor == null) {
            after = 0L;
            if (minSeq > 1L) {
                throw new KgException(KgException.CURSOR_EXPIRED, "changes before " + minSeq
                        + " were removed by retention; start with a full export");
            }
        } else {
            final Matcher m = CURSOR.matcher(cursor);
            if (!m.matches()) {
                throw new KgException(KgException.INVALID_CURSOR, "cursor must look like <epoch>:<seq>");
            }
            if (!m.group(1).equals(epoch)) {
                throw new KgException(KgException.EPOCH_CHANGED, "the dataset was reset; start with a full export");
            }
            after = Long.parseLong(m.group(2));
            if (after < minSeq - 1L) {
                throw new KgException(KgException.CURSOR_EXPIRED, "the cursor is older than the retained changes; start with a full export");
            }
            if (after > maxSeq(c)) {
                throw new KgException(KgException.INVALID_CURSOR, "the cursor is ahead of the change feed");
            }
        }
        final int want = Math.max(1, Math.min(MAX_LIMIT, limit));
        final List<Item> items = new ArrayList<>();
        long last = after;
        boolean more = false;
        try (PreparedStatement ps = c.prepareStatement("SELECT seq, kind, public_id, op, redirect_to, scopes_now, scopes_seen, at"
                + " FROM kg_change WHERE seq > ? ORDER BY seq LIMIT ?")) {
            ps.setLong(1, after);
            ps.setInt(2, want * 4 + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (items.size() == want) {
                        more = true;
                        break;
                    }
                    final long seq = rs.getLong(1);
                    last = seq;
                    final Kind kind = Kind.of(rs.getInt(2));
                    final Op op = kind == Kind.DERIVED ? visibleDerived(viewer, Op.of(rs.getInt(4)), parse(rs.getString(7)))
                            : visibleOp(viewer, Op.of(rs.getInt(4)), parse(rs.getString(6)), parse(rs.getString(7)));
                    if (op != null) {
                        items.add(new Item(seq, kind, rs.getString(3), op,
                                op == Op.REDIRECT ? rs.getString(5) : null, rs.getLong(8)));
                    }
                }
            }
        }
        if (!more) {
            more = maxSeq(c) > last;
        }
        return new Page(items, cursor(epoch, last), more);
    }

    /**
     * What a viewer must see of a row: the change itself if the object is
     * visible to it now, a delete if it was visible at any point covered by the
     * row, otherwise nothing.
     */
    static Op visibleOp(final Viewer viewer, final Op op, final Set<Integer> now, final Set<Integer> seen) {
        if (viewer.all()) {
            return op;
        }
        if (op != Op.DELETE && viewer.intersects(now)) {
            return op;
        }
        if (viewer.intersects(seen)) {
            return Op.DELETE;
        }
        return null;
    }

    /** A derived row: seen with all of its collections or not at all (its collections are fixed). */
    static Op visibleDerived(final Viewer viewer, final Op op, final Set<Integer> seen) {
        return viewer.all() || viewer.containsAll(seen) ? op : null;
    }

    /**
     * Retention: removes rows older than {@code cutoff} and, beyond
     * {@code maxRows}, the oldest rows; raises {@code changes_min_seq} so that
     * older cursors are refused with {@link KgException#CURSOR_EXPIRED}.
     *
     * @return removed rows
     */
    public static int purge(final Connection tx, final long cutoff, final long maxRows) throws SQLException {
        return purge(tx, cutoff, maxRows, Long.MAX_VALUE);
    }

    /** {@link #purge(Connection, long, long)} that removes at most {@code maxDelete} rows (bounded transactions). */
    public static int purge(final Connection tx, final long cutoff, final long maxRows, final long maxDelete) throws SQLException {
        long upTo = 0L;
        try (PreparedStatement ps = tx.prepareStatement("SELECT max(seq) FROM kg_change WHERE at < ?")) {
            ps.setLong(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    upTo = rs.getLong(1);
                }
            }
        }
        final long rows = KgStore.queryLong(tx, "SELECT count(*) FROM kg_change");
        if (rows > maxRows) {
            try (PreparedStatement ps = tx.prepareStatement("SELECT seq FROM kg_change ORDER BY seq LIMIT 1 OFFSET ?")) {
                ps.setLong(1, rows - maxRows - 1);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        upTo = Math.max(upTo, rs.getLong(1));
                    }
                }
            }
        }
        if (upTo <= 0L) {
            return 0;
        }
        if (maxDelete < Long.MAX_VALUE) {
            try (PreparedStatement ps = tx.prepareStatement("SELECT seq FROM kg_change WHERE seq <= ? ORDER BY seq LIMIT 1 OFFSET ?")) {
                ps.setLong(1, upTo);
                ps.setLong(2, Math.max(0L, maxDelete - 1L));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        upTo = Math.min(upTo, rs.getLong(1));
                    }
                }
            }
        }
        final int removed;
        try (PreparedStatement del = tx.prepareStatement("DELETE FROM kg_change WHERE seq <= ?")) {
            del.setLong(1, upTo);
            removed = del.executeUpdate();
        }
        final long current = Long.parseLong(KgStore.getMeta(tx, KgSchema.META_CHANGES_MIN_SEQ));
        if (upTo + 1L > current) {
            KgStore.putMeta(tx, KgSchema.META_CHANGES_MIN_SEQ, Long.toString(upTo + 1L));
        }
        return removed;
    }

    private static long maxSeq(final Connection c) throws SQLException {
        // includes purged and coalesced rows: AUTOINCREMENT keeps the highest sequence ever used
        final long live = KgStore.queryLong(c, "SELECT coalesce(max(seq), 0) FROM kg_change");
        final long used = KgStore.queryLong(c, "SELECT coalesce((SELECT seq FROM sqlite_sequence WHERE name = 'kg_change'), 0)");
        return Math.max(live, used);
    }

    static Set<Integer> parse(final String scopes) {
        final Set<Integer> s = new TreeSet<>();
        if (scopes == null || scopes.isEmpty()) {
            return s;
        }
        for (final String p : scopes.split(",")) {
            if (!p.isEmpty()) {
                s.add(Integer.valueOf(p));
            }
        }
        return s;
    }

    static String format(final Set<Integer> scopes) {
        final StringBuilder sb = new StringBuilder();
        for (final Integer s : new TreeSet<>(scopes)) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /** True if {@code id} is a public entity or statement id. */
    public static boolean isPublicId(final String id) {
        return KgIds.isEntityId(id) || KgIds.isStatementId(id);
    }
}
