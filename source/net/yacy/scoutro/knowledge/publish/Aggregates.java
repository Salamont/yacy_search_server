/*
 *  Aggregates
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

package net.yacy.scoutro.knowledge.publish;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.derive.DerivedService;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgChangeLog;

/**
 * Derived data of statements and entities, recomputed inside the write
 * transaction that changed their evidence (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 4.4, 4.5 and 5.4): quality, current sources, {@code last_confirmed},
 * visibility scopes, host links, the name index, and the change feed.
 * <p>
 * Use: {@link #touchStatement} / {@link #touchEntity} every object
 * <em>before</em> changing it (that records its state before the change),
 * then {@link #finish}. Statements without evidence and entities without
 * statements are deleted; every object whose visibility or quality changed
 * gets one coalesced change row.
 */
public final class Aggregates {

    public static final int Q_SUPPORTED = 1;
    public static final int Q_UNCERTAIN = 2;
    public static final int Q_CONFLICTING = 3;
    public static final int Q_STALE = 4;

    public static final int STATE_ACTIVE = 1;
    public static final int STATE_UNAVAILABLE = 2;
    public static final int STATE_GONE = 3;
    public static final int STATE_EXPIRED = 4;

    private static final class Before {
        final String publicId;
        final Set<Integer> scopes;
        final int quality;
        final boolean existed;

        Before(final String publicId, final Set<Integer> scopes, final int quality, final boolean existed) {
            this.publicId = publicId;
            this.scopes = scopes;
            this.quality = quality;
            this.existed = existed;
        }
    }

    private final KgConfig cfg;
    private final Terms terms;
    private final long now;
    private final Map<Long, Before> statements = new LinkedHashMap<>();
    private final Map<Long, Before> entities = new LinkedHashMap<>();
    /** Objects that left through a redirect (merge); they get a redirect change, not a delete. */
    private final Set<Long> redirectedStatements = new HashSet<>();
    private final Set<Long> redirectedEntities = new HashSet<>();

    // counters for the status
    int statementsCreated;
    int statementsDeleted;
    int entitiesDeleted;

    public Aggregates(final KgConfig cfg, final Terms terms, final long now) {
        this.cfg = cfg;
        this.terms = terms;
        this.now = now;
    }

    public long now() {
        return this.now;
    }

    /** Records the state of a statement before it changes; a new statement is recorded as not existing. */
    public void touchStatement(final Connection tx, final long stmt) throws SQLException {
        if (this.statements.containsKey(stmt)) {
            return;
        }
        String publicId = null;
        int quality = 0;
        try (PreparedStatement ps = tx.prepareStatement("SELECT public_id, quality FROM kg_statement WHERE stmt_rowid = ?")) {
            ps.setLong(1, stmt);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    publicId = rs.getString(1);
                    quality = rs.getInt(2);
                }
            }
        }
        this.statements.put(stmt, new Before(publicId, publicId == null ? new TreeSet<>()
                : scopes(tx, "SELECT coll_id FROM kg_statement_scope WHERE stmt_rowid = ?", stmt), quality, publicId != null));
    }

    /** A statement created in this transaction. */
    public void createdStatement(final long stmt) {
        if (!this.statements.containsKey(stmt)) {
            this.statements.put(stmt, new Before(null, new TreeSet<>(), 0, false));
            this.statementsCreated++;
        }
    }

    public void touchEntity(final Connection tx, final long ent) throws SQLException {
        if (this.entities.containsKey(ent)) {
            return;
        }
        String publicId = null;
        try (PreparedStatement ps = tx.prepareStatement("SELECT public_id FROM kg_entity WHERE ent_rowid = ? AND status = 1")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    publicId = rs.getString(1);
                }
            }
        }
        this.entities.put(ent, new Before(publicId, publicId == null ? new TreeSet<>()
                : scopes(tx, "SELECT coll_id FROM kg_entity_scope WHERE ent_rowid = ?", ent), 0, publicId != null));
    }

    public void createdEntity(final long ent) {
        if (!this.entities.containsKey(ent)) {
            this.entities.put(ent, new Before(null, new TreeSet<>(), 0, false));
        }
    }

    /** A statement merged into {@code target}: one redirect change, no delete. */
    public void redirectedStatement(final Connection tx, final long stmt, final String targetPublicId) throws SQLException {
        touchStatement(tx, stmt);
        final Before b = this.statements.get(stmt);
        this.redirectedStatements.add(stmt);
        if (b.existed) {
            KgChangeLog.record(tx, KgChangeLog.Kind.STATEMENT, b.publicId, KgChangeLog.Op.REDIRECT, targetPublicId, b.scopes,
                    new TreeSet<>(), this.now);
        }
    }

    /** An entity merged into {@code target}: one redirect change, no delete. */
    public void redirectedEntity(final Connection tx, final long ent, final String targetPublicId) throws SQLException {
        touchEntity(tx, ent);
        final Before b = this.entities.get(ent);
        this.redirectedEntities.add(ent);
        if (b.existed) {
            KgChangeLog.record(tx, KgChangeLog.Kind.ENTITY, b.publicId, KgChangeLog.Op.REDIRECT, targetPublicId, b.scopes,
                    new TreeSet<>(), this.now);
        }
    }

    /** Touches every statement with evidence from {@code docRowid} (before the document changes). */
    public void touchDocument(final Connection tx, final long docRowid, final Integer tier) throws SQLException {
        final List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = tx.prepareStatement(tier == null
                ? "SELECT DISTINCT stmt_rowid FROM kg_evidence WHERE doc_rowid = ?"
                : "SELECT DISTINCT stmt_rowid FROM kg_evidence WHERE doc_rowid = ? AND tier = ?")) {
            ps.setLong(1, docRowid);
            if (tier != null) {
                ps.setInt(2, tier);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        for (final long id : ids) {
            touchStatement(tx, id);
        }
    }

    /**
     * Recomputes every touched statement and entity, deletes empty ones and
     * writes the change feed. Must be the last step of the transaction.
     */
    public void finish(final Connection tx) throws SQLException {
        // 1. statements: delete those without evidence, recompute the others
        final Map<String, Set<Long>> functionalGroups = new HashMap<>();
        final List<Long> alive = new ArrayList<>();
        for (final Long stmt : new ArrayList<>(this.statements.keySet())) {
            if (this.redirectedStatements.contains(stmt)) {
                continue;
            }
            final long[] row = statementRow(tx, stmt); // subj, pred, obj_ent (or 0)
            if (row == null) {
                continue; // already gone (cascade)
            }
            touchEntity(tx, row[0]);
            if (row[2] != 0) {
                touchEntity(tx, row[2]);
            }
            final String pred = this.terms.predicateName((int) row[1]);
            final Vocabulary.Predicate p = pred == null ? null : Vocabulary.predicate(pred);
            if (p != null && p.functional) {
                // also for a deleted statement: its remaining siblings may no longer conflict
                functionalGroups.computeIfAbsent(row[0] + ":" + row[1], k -> new TreeSet<>()).add(stmt);
            }
            if (evidenceCount(tx, stmt) == 0) {
                deleteStatement(tx, stmt);
                continue;
            }
            alive.add(stmt);
        }
        for (final long stmt : alive) {
            recomputeStatement(tx, stmt, baseQuality(tx, stmt));
        }
        // conflicting: a functional predicate with more than one supported object for the same subject
        for (final String group : functionalGroups.keySet()) {
            final String[] sp = group.split(":");
            final long subj = Long.parseLong(sp[0]);
            final int pred = Integer.parseInt(sp[1]);
            final List<Long> siblings = new ArrayList<>();
            try (PreparedStatement ps = tx.prepareStatement("SELECT stmt_rowid FROM kg_statement WHERE subj = ? AND pred = ?")) {
                ps.setLong(1, subj);
                ps.setInt(2, pred);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        siblings.add(rs.getLong(1));
                    }
                }
            }
            final Map<Long, Integer> base = new HashMap<>();
            int supported = 0;
            for (final long s : siblings) {
                final int q = baseQuality(tx, s);
                base.put(s, q);
                if (q == Q_SUPPORTED) {
                    supported++;
                }
            }
            for (final long s : siblings) {
                final int q = supported > 1 && base.get(s) == Q_SUPPORTED ? Q_CONFLICTING : base.get(s);
                if (!this.statements.containsKey(s)) {
                    touchStatement(tx, s); // a sibling whose quality changes gets a change row too
                }
                setQuality(tx, s, q);
            }
        }
        // 2. entities: scopes, host links, delete those without statements
        for (final Long ent : new ArrayList<>(this.entities.keySet())) {
            if (this.redirectedEntities.contains(ent)) {
                continue;
            }
            if (!activeEntity(tx, ent)) {
                continue;
            }
            if (!hasStatements(tx, ent)) {
                deleteEntity(tx, ent);
                continue;
            }
            recomputeEntity(tx, ent);
        }
        // 3. change feed
        for (final Map.Entry<Long, Before> e : this.statements.entrySet()) {
            if (this.redirectedStatements.contains(e.getKey())) {
                continue;
            }
            feed(tx, KgChangeLog.Kind.STATEMENT, e.getKey(), e.getValue(),
                    "SELECT public_id, quality FROM kg_statement WHERE stmt_rowid = ?",
                    "SELECT coll_id FROM kg_statement_scope WHERE stmt_rowid = ?");
        }
        for (final Map.Entry<Long, Before> e : this.entities.entrySet()) {
            if (this.redirectedEntities.contains(e.getKey())) {
                continue;
            }
            feed(tx, KgChangeLog.Kind.ENTITY, e.getKey(), e.getValue(),
                    "SELECT public_id, 0 FROM kg_entity WHERE ent_rowid = ? AND status = 1",
                    "SELECT coll_id FROM kg_entity_scope WHERE ent_rowid = ?");
        }
    }

    private void feed(final Connection tx, final KgChangeLog.Kind kind, final long rowid, final Before before, final String rowSql,
            final String scopeSql) throws SQLException {
        String publicId = null;
        int quality = 0;
        try (PreparedStatement ps = tx.prepareStatement(rowSql)) {
            ps.setLong(1, rowid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    publicId = rs.getString(1);
                    quality = rs.getInt(2);
                }
            }
        }
        if (publicId == null) {
            if (before.existed) {
                KgChangeLog.record(tx, kind, before.publicId, KgChangeLog.Op.DELETE, null, before.scopes, new TreeSet<>(), this.now);
            }
            return;
        }
        final Set<Integer> after = scopes(tx, scopeSql, rowid);
        if (!before.existed || !after.equals(before.scopes) || quality != before.quality) {
            KgChangeLog.record(tx, kind, publicId, KgChangeLog.Op.UPSERT, null, before.scopes, after, this.now);
        }
    }

    // ------------------------------------------------------------ statements

    private static long[] statementRow(final Connection tx, final long stmt) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT subj, pred, coalesce(obj_ent, 0) FROM kg_statement WHERE stmt_rowid = ?")) {
            ps.setLong(1, stmt);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)} : null;
            }
        }
    }

    private static int evidenceCount(final Connection tx, final long stmt) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT count(*) FROM kg_evidence WHERE stmt_rowid = ?")) {
            ps.setLong(1, stmt);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void deleteStatement(final Connection tx, final long stmt) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_name_fts WHERE rowid = ?")) {
            ps.setLong(1, stmt);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_statement WHERE stmt_rowid = ?")) {
            ps.setLong(1, stmt);
            if (ps.executeUpdate() > 0) {
                this.statementsDeleted++;
            }
        }
    }

    /**
     * Quality without the conflict rule (4.4). Only stated evidence of tiers 1
     * and 2 makes a statement supported: what only the LLM tier read stays
     * uncertain, even when its quote was found verbatim (6.3).
     */
    private int baseQuality(final Connection tx, final long stmt) throws SQLException {
        boolean anyCurrent = false;
        boolean supported = false;
        try (PreparedStatement ps = tx.prepareStatement("SELECT e.certainty, d.state, d.state_since, d.loaded_at, e.tier"
                + " FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE e.stmt_rowid = ?")) {
            ps.setLong(1, stmt);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final int state = rs.getInt(2);
                    if (!current(state, rs.getLong(3), rs.getObject(4) == null ? null : rs.getLong(4))) {
                        continue;
                    }
                    anyCurrent = true;
                    if (rs.getInt(1) == 1 && state == STATE_ACTIVE && rs.getInt(5) != 3) {
                        supported = true;
                    }
                }
            }
        }
        if (!anyCurrent) {
            return Q_STALE;
        }
        return supported ? Q_SUPPORTED : Q_UNCERTAIN;
    }

    /** Current evidence: active, or unavailable within the grace period, and loaded at most maxAge ago. */
    boolean current(final int state, final long stateSince, final Long loadedAt) {
        final boolean live = state == STATE_ACTIVE || (state == STATE_UNAVAILABLE && this.now - stateSince <= this.cfg.unavailableGraceMillis);
        final boolean fresh = loadedAt == null || this.now - loadedAt <= this.cfg.maxAgeMillis;
        return live && fresh;
    }

    private void recomputeStatement(final Connection tx, final long stmt, final int quality) throws SQLException {
        long sources = 0;
        Long lastConfirmed = null;
        try (PreparedStatement ps = tx.prepareStatement("SELECT d.doc_rowid, d.state, d.state_since, d.loaded_at"
                + " FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE e.stmt_rowid = ? GROUP BY d.doc_rowid")) {
            ps.setLong(1, stmt);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final Long loaded = rs.getObject(4) == null ? null : rs.getLong(4);
                    if (current(rs.getInt(2), rs.getLong(3), loaded)) {
                        sources++;
                        if (loaded != null && (lastConfirmed == null || loaded > lastConfirmed)) {
                            lastConfirmed = loaded;
                        }
                    }
                }
            }
        }
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_statement SET quality = ?, current_sources = ?,"
                + " last_confirmed = coalesce(?, last_confirmed) WHERE stmt_rowid = ?")) {
            ps.setInt(1, quality);
            ps.setLong(2, sources);
            if (lastConfirmed == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setLong(3, lastConfirmed);
            }
            ps.setLong(4, stmt);
            ps.executeUpdate();
        }
        try (PreparedStatement del = tx.prepareStatement("DELETE FROM kg_statement_scope WHERE stmt_rowid = ?")) {
            del.setLong(1, stmt);
            del.executeUpdate();
        }
        try (PreparedStatement ins = tx.prepareStatement("INSERT INTO kg_statement_scope (stmt_rowid, coll_id, n)"
                + " SELECT ?, dc.coll_id, count(DISTINCT e.doc_rowid) FROM kg_evidence e"
                + " JOIN kg_doc_collection dc ON dc.doc_rowid = e.doc_rowid WHERE e.stmt_rowid = ? GROUP BY dc.coll_id")) {
            ins.setLong(1, stmt);
            ins.setLong(2, stmt);
            ins.executeUpdate();
        }
        // name index: names and aliases are searchable
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_name_fts WHERE rowid = ?")) {
            ps.setLong(1, stmt);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO kg_name_fts (rowid, name) SELECT stmt_rowid, obj_val"
                + " FROM kg_statement WHERE stmt_rowid = ? AND obj_val IS NOT NULL AND pred IN (?, ?)")) {
            ps.setLong(1, stmt);
            ps.setInt(2, this.terms.predicate(Vocabulary.NAME));
            ps.setInt(3, this.terms.predicate(Vocabulary.ALIAS));
            ps.executeUpdate();
        }
    }

    private static void setQuality(final Connection tx, final long stmt, final int quality) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_statement SET quality = ? WHERE stmt_rowid = ?")) {
            ps.setInt(1, quality);
            ps.setLong(2, stmt);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------ entities

    private static boolean activeEntity(final Connection tx, final long ent) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT 1 FROM kg_entity WHERE ent_rowid = ? AND status = 1")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean hasStatements(final Connection tx, final long ent) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(
                "SELECT 1 FROM kg_statement WHERE subj = ? UNION ALL SELECT 1 FROM kg_statement WHERE obj_ent = ? LIMIT 1")) {
            ps.setLong(1, ent);
            ps.setLong(2, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void deleteEntity(final Connection tx, final long ent) throws SQLException {
        // the derived rows naming it or its merge records cascade with them: the change feed learns of their end first
        final List<Long> gone = new ArrayList<>();
        gone.add(ent);
        try (PreparedStatement ps = tx.prepareStatement("SELECT ent_rowid FROM kg_entity WHERE merged_into = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    gone.add(rs.getLong(1));
                }
            }
        }
        for (final long g : gone) {
            DerivedService.forgetEntity(tx, g, this.now);
        }
        // merge records pointing here go with it; their redirect rows cascade
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_entity WHERE merged_into = ?")) {
            ps.setLong(1, ent);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_entity WHERE ent_rowid = ?")) {
            ps.setLong(1, ent);
            if (ps.executeUpdate() > 0) {
                this.entitiesDeleted++;
            }
        }
    }

    private static void recomputeEntity(final Connection tx, final long ent) throws SQLException {
        try (PreparedStatement del = tx.prepareStatement("DELETE FROM kg_entity_scope WHERE ent_rowid = ?")) {
            del.setLong(1, ent);
            del.executeUpdate();
        }
        try (PreparedStatement ins = tx.prepareStatement("INSERT INTO kg_entity_scope (ent_rowid, coll_id, n)"
                + " SELECT ?, ss.coll_id, count(*) FROM kg_statement s JOIN kg_statement_scope ss ON ss.stmt_rowid = s.stmt_rowid"
                + " WHERE s.subj = ? OR s.obj_ent = ? GROUP BY ss.coll_id")) {
            ins.setLong(1, ent);
            ins.setLong(2, ent);
            ins.setLong(3, ent);
            ins.executeUpdate();
        }
        try (PreparedStatement del = tx.prepareStatement("DELETE FROM kg_host_entity WHERE ent_rowid = ?")) {
            del.setLong(1, ent);
            del.executeUpdate();
        }
        try (PreparedStatement ins = tx.prepareStatement("INSERT INTO kg_host_entity (host_id, ent_rowid, n)"
                + " SELECT d.host_id, ?, count(DISTINCT e.doc_rowid) FROM kg_statement s"
                + " JOIN kg_evidence e ON e.stmt_rowid = s.stmt_rowid JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
                + " WHERE s.subj = ? AND d.host_id IS NOT NULL GROUP BY d.host_id")) {
            ins.setLong(1, ent);
            ins.setLong(2, ent);
            ins.executeUpdate();
        }
    }

    private static Set<Integer> scopes(final Connection tx, final String sql, final long id) throws SQLException {
        final Set<Integer> out = new TreeSet<>();
        try (PreparedStatement ps = tx.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        }
        return out;
    }

    public int touchedStatements() {
        return this.statements.size();
    }
}
