/*
 *  Retention
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.store.KgChangeLog;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * Expiry and purge (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 4.4 and 7.6), hourly, in
 * small maintenance transactions; it continues while new growth is paused.
 * <ol>
 * <li>{@code unavailable} longer than {@code source.unavailableGraceDays} → {@code expired};</li>
 * <li>{@code active} with {@code loaded_at} older than {@code source.maxAgeDays} → {@code expired};</li>
 * <li>{@code gone} longer than {@code source.goneRetentionDays} → removed (Solr holds only a fail document);</li>
 * <li>{@code expired} longer than {@code source.goneRetentionDays} → evidence deleted, the document stays
 * tracked without input hash (a recrawl extracts again);</li>
 * <li>{@code stale} statements older than {@code source.staleRetentionDays} → deleted;</li>
 * <li>change rows outside {@code changes.retentionDays} / {@code changes.maxRows} → deleted.</li>
 * </ol>
 * Nothing in Solr is touched.
 */
final class Retention {

    static final long EVERY_MILLIS = 60L * 60_000L;
    static final int BATCH = 200;
    private static final int CHANGE_BATCH = 5000;
    private static final long ESTIMATE = 2L * 1024L * 1024L;
    private static final int PHASES = 6;

    private final KgConfig cfg;
    private final KgStore store;
    private final Publisher publisher;
    private final LongSupplier clock;

    private long nextRunAt;
    private int phase = -1;
    private volatile long lastRunAt;
    private final long[] counts = new long[PHASES];
    private volatile JSONObject last;

    Retention(final KgConfig cfg, final KgStore store, final Publisher publisher, final LongSupplier clock, final long firstRunAt) {
        this.cfg = cfg;
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.nextRunAt = firstRunAt;
    }

    /** Runs the next pass as soon as possible (tests, budget pause). */
    void runSoon() {
        this.nextRunAt = 0L;
    }

    /** One bounded step; true while a pass is running. */
    boolean step() throws KgException {
        final long now = this.clock.getAsLong();
        if (this.phase < 0) {
            if (now < this.nextRunAt) {
                return false;
            }
            this.phase = 0;
            java.util.Arrays.fill(this.counts, 0L);
        }
        final int n;
        switch (this.phase) {
            case 0:
                n = setState(now, "SELECT doc_rowid FROM kg_doc WHERE state = " + Aggregates.STATE_UNAVAILABLE
                        + " AND state_since < ? LIMIT " + BATCH, now - this.cfg.unavailableGraceMillis, Aggregates.STATE_EXPIRED);
                break;
            case 1:
                n = setState(now, "SELECT doc_rowid FROM kg_doc WHERE state = " + Aggregates.STATE_ACTIVE
                        + " AND loaded_at IS NOT NULL AND loaded_at < ? LIMIT " + BATCH, now - this.cfg.maxAgeMillis, Aggregates.STATE_EXPIRED);
                break;
            case 2:
                n = removeGone(now);
                break;
            case 3:
                n = this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> this.publisher.purgeEvidence(tx,
                        rowids(tx, "SELECT doc_rowid FROM kg_doc WHERE state = " + Aggregates.STATE_EXPIRED
                                + " AND state_since < ? AND (tiers > 0 OR input_hash IS NOT NULL) LIMIT " + BATCH, now - this.cfg.goneRetentionMillis),
                        now));
                break;
            case 4:
                n = this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
                    final List<Long> stmts = rowids(tx, "SELECT stmt_rowid FROM kg_statement WHERE quality = " + Aggregates.Q_STALE
                            + " AND coalesce(last_confirmed, first_seen) < ? LIMIT " + BATCH, now - this.cfg.staleRetentionMillis);
                    this.publisher.deleteStatements(tx, stmts, now);
                    return stmts.size();
                });
                break;
            default:
                n = this.store.write(WriteClass.MAINTENANCE, ESTIMATE,
                        tx -> KgChangeLog.purge(tx, now - this.cfg.changesRetentionMillis, this.cfg.changesMaxRows, CHANGE_BATCH));
                break;
        }
        this.counts[this.phase] += n;
        final int limit = this.phase == PHASES - 1 ? CHANGE_BATCH : BATCH;
        if (n < limit) {
            this.phase++;
            if (this.phase >= PHASES) {
                this.phase = -1;
                this.lastRunAt = now;
                this.nextRunAt = now + EVERY_MILLIS;
                this.last = KgJson.obj("expiredUnavailable", this.counts[0], "expiredByAge", this.counts[1],
                        "removedGone", this.counts[2], "purgedExpired", this.counts[3], "deletedStale", this.counts[4],
                        "purgedChanges", this.counts[5]);
                return false;
            }
        }
        return true;
    }

    private int setState(final long now, final String sql, final long cutoff, final int state) throws KgException {
        return this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            final List<Long> ids = rowids(tx, sql, cutoff);
            this.publisher.setState(tx, ids, state, now);
            return ids.size();
        });
    }

    private int removeGone(final long now) throws KgException {
        return this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            final Map<String, Long> expected = new HashMap<>();
            try (PreparedStatement ps = tx.prepareStatement("SELECT doc_id, generation FROM kg_doc WHERE state = " + Aggregates.STATE_GONE
                    + " AND state_since < ? LIMIT " + BATCH)) {
                ps.setLong(1, now - this.cfg.goneRetentionMillis);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        expected.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            this.publisher.remove(tx, new ArrayList<>(expected.keySet()), expected, now);
            return expected.size();
        });
    }

    private static List<Long> rowids(final Connection c, final String sql, final long cutoff) throws SQLException {
        final List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
        }
        return out;
    }

    JSONObject status() {
        return KgJson.obj("running", this.phase >= 0, "lastRunAt", this.lastRunAt > 0L ? this.lastRunAt : null,
                "nextRunAt", this.phase >= 0 ? null : this.nextRunAt, "last", this.last);
    }
}
