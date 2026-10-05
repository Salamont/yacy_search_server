/*
 *  FullReset
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
import java.util.List;
import java.util.function.LongSupplier;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgIds;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The graph's answer to a full clear of the index ({@code *:*}, see
 * docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 5.7): a new {@code dataset_epoch} first, so
 * every change-feed cursor is answered with {@code epoch_changed} at once,
 * then all graph data is deleted in bounded maintenance transactions.
 * Vocabulary, extractors, collections, events and the scan history stay.
 * The mark {@code reset_in_progress} survives a restart, so an interrupted
 * reset is finished first. A full clear is not subject to the mass-deletion
 * brake: it is an explicit operation on the index, not an inferred absence.
 */
final class FullReset {

    /** Rows per delete transaction. */
    static final int BATCH = 1000;
    private static final long ESTIMATE = 4L * 1024L * 1024L;

    private final KgStore store;
    private final LongSupplier clock;
    private boolean begun;

    FullReset(final KgStore store, final LongSupplier clock, final boolean resumed) {
        this.store = store;
        this.clock = clock;
        this.begun = resumed;
    }

    /** Restarts the reset (a second clear arrived while one was running). */
    void restart() {
        this.begun = false;
    }

    /**
     * One bounded step.
     *
     * @return true when the reset is complete
     */
    boolean step() throws KgException {
        final long now = this.clock.getAsLong();
        if (!this.begun) {
            final String epoch = KgIds.newEpoch();
            this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
                KgStore.putMeta(tx, KgSchema.META_RESET_IN_PROGRESS, "1");
                KgStore.putMeta(tx, KgSchema.META_EPOCH, epoch);
                exec(tx, "DELETE FROM kg_work");
                exec(tx, "DELETE FROM kg_llm_work");
                exec(tx, "DELETE FROM kg_scan_candidate");
                try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_scan SET state = 3, finished_at = ?, detail = 'full_reset'"
                        + " WHERE state IN (1, 3, 4)")) {
                    ps.setLong(1, now);
                    ps.executeUpdate();
                }
                KgStore.event(tx, 2, "full_reset", "the index was cleared; new dataset epoch " + epoch, now);
                return null;
            });
            this.store.epochReset(epoch);
            this.begun = true;
            return false;
        }
        if (deleteBatch("SELECT seq FROM kg_change LIMIT " + BATCH, "DELETE FROM kg_change WHERE seq = ?")) {
            return false;
        }
        if (statements()) {
            return false;
        }
        if (deleteBatch("SELECT ent_rowid FROM kg_entity WHERE status = 2 LIMIT " + BATCH, "DELETE FROM kg_entity WHERE ent_rowid = ?")) {
            return false;
        }
        if (deleteBatch("SELECT ent_rowid FROM kg_entity LIMIT " + BATCH, "DELETE FROM kg_entity WHERE ent_rowid = ?")) {
            return false;
        }
        if (deleteBatch("SELECT doc_rowid FROM kg_doc LIMIT " + BATCH, "DELETE FROM kg_doc WHERE doc_rowid = ?")) {
            return false;
        }
        if (deleteBlobBatch("SELECT cache_key FROM kg_extraction LIMIT " + BATCH, "DELETE FROM kg_extraction WHERE cache_key = ?")) {
            return false;
        }
        this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            // every change of the old epoch counts as purged: a consumer starts again with a full export
            final long used = KgStore.queryLong(tx, "SELECT coalesce((SELECT seq FROM sqlite_sequence WHERE name = 'kg_change'), 0)");
            KgStore.putMeta(tx, KgSchema.META_CHANGES_MIN_SEQ, Long.toString(used + 1L));
            KgStore.putMeta(tx, KgSchema.META_RESET_IN_PROGRESS, "0");
            KgStore.event(tx, 1, "full_reset_done", null, now);
            return null;
        });
        return true;
    }

    /** Statements with their name index rows (contentless FTS rows are deleted by rowid); evidence and scopes cascade. */
    private boolean statements() throws KgException {
        return this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            final List<Long> ids = longs(tx, "SELECT stmt_rowid FROM kg_statement LIMIT " + BATCH);
            try (PreparedStatement fts = tx.prepareStatement("DELETE FROM kg_name_fts WHERE rowid = ?");
                    PreparedStatement del = tx.prepareStatement("DELETE FROM kg_statement WHERE stmt_rowid = ?")) {
                for (final long id : ids) {
                    fts.setLong(1, id);
                    fts.executeUpdate();
                    del.setLong(1, id);
                    del.executeUpdate();
                }
            }
            return !ids.isEmpty();
        });
    }

    private boolean deleteBatch(final String select, final String delete) throws KgException {
        return this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            final List<Long> ids = longs(tx, select);
            try (PreparedStatement del = tx.prepareStatement(delete)) {
                for (final long id : ids) {
                    del.setLong(1, id);
                    del.executeUpdate();
                }
            }
            return !ids.isEmpty();
        });
    }

    private boolean deleteBlobBatch(final String select, final String delete) throws KgException {
        return this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            final List<byte[]> ids = new ArrayList<>();
            try (PreparedStatement ps = tx.prepareStatement(select); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getBytes(1));
                }
            }
            try (PreparedStatement del = tx.prepareStatement(delete)) {
                for (final byte[] id : ids) {
                    del.setBytes(1, id);
                    del.executeUpdate();
                }
            }
            return !ids.isEmpty();
        });
    }

    private static List<Long> longs(final Connection c, final String sql) throws SQLException {
        final List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }

    private static void exec(final Connection c, final String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
