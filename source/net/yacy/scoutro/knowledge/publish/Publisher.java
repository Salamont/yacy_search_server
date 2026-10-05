/*
 *  Publisher
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
import java.sql.Types;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgIds;
import net.yacy.scoutro.knowledge.extract.Claim;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.Mention;
import net.yacy.scoutro.knowledge.extract.MetadataExtractor;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.resolve.IdentityResolver;
import net.yacy.scoutro.knowledge.resolve.Normalizers;

/**
 * Transactional publish of one source document (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 5.4), inside the caller's {@code BEGIN IMMEDIATE} transaction:
 * <ol>
 * <li>compare-and-set: the document's {@code generation} must still be the
 * one claimed with the work item, and the Solr version read must not be
 * lower than the one already published (the caller additionally checks the
 * in-memory change set for a newer event right before);</li>
 * <li>the document's evidence is replaced for tiers 1 and 2 only; the LLM
 * tier's evidence stays while the input is unchanged and is deleted with the
 * document's LLM mark when it changes ({@link #applyLlm} publishes tier 3);</li>
 * <li>mentions are resolved to entities, statements upserted, evidence
 * inserted;</li>
 * <li>aggregates, scopes and the change feed of every affected statement and
 * entity are recomputed ({@link Aggregates});</li>
 * <li>{@code kg_doc} gets the token, input hash, version and generation + 1.</li>
 * </ol>
 * Lifecycle changes (state, collections, removal) use the same aggregates.
 */
public final class Publisher {

    /** A source document as read from Solr. */
    public static final class Doc {
        public String docId;
        public String url;
        public String host;
        public String hostId;
        public String language;
        public int state;
        public byte[] token;
        public byte[] inputHash;
        public long solrVersion;
        public Long loadedAt;
        public List<String> collections;
        public long jsonldBytes;
        public boolean jsonldSkipped;
    }

    public enum Outcome { PUBLISHED, UNCHANGED, LIFECYCLE, ABORT_GENERATION, ABORT_OLDER }

    /** Result of one publish, with counters for the status. */
    public static final class Result {
        public final Outcome outcome;
        public int statements;
        public int entitiesCreated;
        public int merged;
        public int conflicts;

        Result(final Outcome outcome) {
            this.outcome = outcome;
        }
    }

    private static final int[] REPLACED_TIERS = {1, 2};
    private static final int[] LLM_TIER = {3};

    private final KgConfig cfg;
    private final Terms terms;

    public Publisher(final KgConfig cfg, final Terms terms) {
        this.cfg = cfg;
        this.terms = terms;
    }

    /** Current row of a tracked document; null if not tracked. */
    public static final class Row {
        public final long rowid;
        public final long generation;
        public final long solrVersion;
        public final byte[] token;
        public final byte[] inputHash;
        public final int state;
        public final long stateSince;
        public final Long loadedAt;

        Row(final ResultSet rs) throws SQLException {
            this.rowid = rs.getLong(1);
            this.generation = rs.getLong(2);
            this.solrVersion = rs.getLong(3);
            this.token = rs.getBytes(4);
            this.inputHash = rs.getBytes(5);
            this.state = rs.getInt(6);
            this.stateSince = rs.getLong(7);
            this.loadedAt = rs.getObject(8) == null ? null : rs.getLong(8);
        }
    }

    public static Row row(final Connection c, final String docId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT doc_rowid, generation, solr_version, token, input_hash, state,"
                + " state_since, loaded_at FROM kg_doc WHERE doc_id = ?")) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Row(rs) : null;
            }
        }
    }

    /**
     * Publishes {@code doc}. {@code extraction} is null when only the
     * lifecycle changes (state, collections, load date): the evidence stays.
     *
     * @param claimedGeneration the document's generation when the work item was claimed, -1 if it was not tracked
     */
    public Result apply(final Connection tx, final Doc doc, final long claimedGeneration, final Extraction extraction,
            final long now) throws SQLException {
        final Row cur = row(tx, doc.docId);
        if (cur == null ? claimedGeneration != -1L : cur.generation != claimedGeneration) {
            return new Result(Outcome.ABORT_GENERATION);
        }
        if (cur != null && doc.solrVersion < cur.solrVersion) {
            return new Result(Outcome.ABORT_OLDER);
        }
        final Aggregates agg = new Aggregates(this.cfg, this.terms, now);
        final Set<Integer> colls = new TreeSet<>();
        for (final String c : doc.collections) {
            colls.add(this.terms.collection(tx, c));
        }
        final long rowid;
        final boolean collectionsChanged;
        final boolean stateChanged;
        if (cur == null) {
            rowid = insert(tx, doc, extraction == null ? 0 : extraction.tiers(), now);
            collectionsChanged = true;
            stateChanged = true;
        } else {
            rowid = cur.rowid;
            collectionsChanged = !colls.equals(collections(tx, rowid));
            stateChanged = cur.state != doc.state;
            if (extraction != null || collectionsChanged || stateChanged
                    || !java.util.Objects.equals(cur.loadedAt, doc.loadedAt)) {
                agg.touchDocument(tx, rowid, null);
            }
            update(tx, doc, cur, extraction, stateChanged, now);
        }
        if (collectionsChanged) {
            try (PreparedStatement del = tx.prepareStatement("DELETE FROM kg_doc_collection WHERE doc_rowid = ?")) {
                del.setLong(1, rowid);
                del.executeUpdate();
            }
            try (PreparedStatement ins = tx.prepareStatement("INSERT INTO kg_doc_collection (doc_rowid, coll_id) VALUES (?, ?)")) {
                for (final int c : colls) {
                    ins.setLong(1, rowid);
                    ins.setInt(2, c);
                    ins.executeUpdate();
                }
            }
        }
        final Result result;
        if (extraction != null) {
            result = new Result(Outcome.PUBLISHED);
            replaceEvidence(tx, doc, rowid, extraction, agg, result, now);
        } else {
            result = new Result(collectionsChanged || stateChanged ? Outcome.LIFECYCLE : Outcome.UNCHANGED);
        }
        agg.finish(tx);
        return result;
    }

    private static long insert(final Connection tx, final Doc doc, final int tiers, final long now) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO kg_doc (doc_id, state, token, solr_version, input_hash, generation,"
                + " tiers, host_id, url, jsonld_bytes, jsonld_skipped, loaded_at, state_since, processed_at)"
                + " VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, doc.docId);
            ps.setInt(2, doc.state);
            ps.setBytes(3, doc.token);
            ps.setLong(4, doc.solrVersion);
            ps.setBytes(5, doc.inputHash);
            ps.setInt(6, tiers);
            ps.setString(7, hostId(doc.hostId));
            ps.setString(8, clipUrl(doc.url));
            ps.setLong(9, doc.jsonldBytes);
            ps.setInt(10, doc.jsonldSkipped ? 1 : 0);
            setNullable(ps, 11, doc.loadedAt);
            ps.setLong(12, now);
            ps.setLong(13, now);
            ps.executeUpdate();
        }
        return net.yacy.scoutro.knowledge.store.KgStore.queryLong(tx, "SELECT last_insert_rowid()");
    }

    private static void update(final Connection tx, final Doc doc, final Row cur, final Extraction extraction,
            final boolean stateChanged, final long now) throws SQLException {
        // a new input invalidates the LLM tier's result (6.3): its quotes were checked against the old text
        final boolean llmStale = extraction != null && doc.inputHash != null && !java.util.Arrays.equals(doc.inputHash, llmHash(tx, cur.rowid));
        if (llmStale) {
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_evidence WHERE doc_rowid = ? AND tier = 3")) {
                ps.setLong(1, cur.rowid);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_doc SET llm_status = NULL, llm_hash = NULL, llm_reason = NULL,"
                    + " tiers = tiers & 3 WHERE doc_rowid = ?")) {
                ps.setLong(1, cur.rowid);
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_doc SET state = ?, token = ?, solr_version = ?,"
                + " input_hash = coalesce(?, input_hash), generation = generation + 1,"
                + " tiers = CASE WHEN ? THEN ? | (tiers & 4) ELSE tiers END,"
                + " host_id = ?, url = ?, jsonld_bytes = ?, jsonld_skipped = ?, loaded_at = ?, state_since = ?, processed_at = ?,"
                + " last_error = NULL WHERE doc_rowid = ?")) {
            ps.setInt(1, doc.state);
            ps.setBytes(2, doc.token);
            ps.setLong(3, doc.solrVersion);
            // null keeps the input hash: a document that comes back unchanged after a failure needs no new extraction
            ps.setBytes(4, doc.inputHash);
            ps.setBoolean(5, extraction != null);
            ps.setInt(6, extraction == null ? 0 : extraction.tiers());
            ps.setString(7, hostId(doc.hostId));
            ps.setString(8, clipUrl(doc.url));
            ps.setLong(9, doc.jsonldBytes);
            ps.setInt(10, doc.jsonldSkipped ? 1 : 0);
            setNullable(ps, 11, doc.loadedAt);
            ps.setLong(12, stateChanged ? now : cur.stateSince);
            ps.setLong(13, now);
            ps.setLong(14, cur.rowid);
            ps.executeUpdate();
        }
    }

    private static byte[] llmHash(final Connection tx, final long rowid) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT llm_hash FROM kg_doc WHERE doc_rowid = ?")) {
            ps.setLong(1, rowid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    /**
     * Publishes the LLM tier's result for one document (tier 3 only), inside
     * the caller's transaction. Compare-and-set on the input: the document
     * must still be active with exactly the input hash the tier read, and not
     * yet marked; otherwise nothing changes (the newer input is examined
     * again). The document's tier-3 evidence is replaced, tiers 1 and 2 stay;
     * the document is marked done for this input.
     *
     * @param extId the extractor row of the tier (name, version, model, prompt)
     */
    public Result applyLlm(final Connection tx, final Doc doc, final byte[] inputHash, final Extraction extraction, final int extId,
            final long now) throws SQLException {
        final long rowid;
        try (PreparedStatement ps = tx.prepareStatement("SELECT doc_rowid FROM kg_doc WHERE doc_id = ? AND state = 1 AND input_hash = ?"
                + " AND llm_status IS NULL")) {
            ps.setString(1, doc.docId);
            ps.setBytes(2, inputHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new Result(Outcome.ABORT_GENERATION);
                }
                rowid = rs.getLong(1);
            }
        }
        final Aggregates agg = new Aggregates(this.cfg, this.terms, now);
        agg.touchDocument(tx, rowid, 3);
        final Result result = new Result(Outcome.PUBLISHED);
        replaceEvidence(tx, doc, rowid, extraction, agg, result, now, LLM_TIER, extId);
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_doc SET llm_status = 1, llm_hash = input_hash, llm_reason = NULL,"
                + " tiers = tiers | 4 WHERE doc_rowid = ?")) {
            ps.setLong(1, rowid);
            ps.executeUpdate();
        }
        agg.finish(tx);
        return result;
    }

    private void replaceEvidence(final Connection tx, final Doc doc, final long rowid, final Extraction ex, final Aggregates agg,
            final Result result, final long now) throws SQLException {
        replaceEvidence(tx, doc, rowid, ex, agg, result, now, REPLACED_TIERS, 0);
    }

    private void replaceEvidence(final Connection tx, final Doc doc, final long rowid, final Extraction ex, final Aggregates agg,
            final Result result, final long now, final int[] replacedTiers, final int llmExtId) throws SQLException {
        for (final int tier : replacedTiers) {
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_evidence WHERE doc_rowid = ? AND tier = ?")) {
                ps.setLong(1, rowid);
                ps.setInt(2, tier);
                ps.executeUpdate();
            }
        }
        // only mentions that carry a claim become entities
        final Set<String> used = new HashSet<>();
        for (final Claim c : ex.claims()) {
            if (!replaced(replacedTiers, c.tier)) {
                continue;
            }
            used.add(c.subject);
            if (c.object != null) {
                used.add(c.object);
            }
        }
        final String domain = Normalizers.registrableDomain(doc.host);
        final IdentityResolver resolver = new IdentityResolver(this.terms);
        final Map<String, long[]> ents = new HashMap<>(); // ref -> {rowid}
        final Map<String, String> publicIds = new HashMap<>();
        for (final Mention m : ex.mentions()) {
            if (used.contains(m.ref)) {
                final long ent = resolver.resolve(tx, m, doc.docId, doc.host, domain, agg);
                ents.put(m.ref, new long[] {ent});
            }
        }
        // entities may have been merged into others while later mentions were resolved
        for (final Map.Entry<String, long[]> e : ents.entrySet()) {
            e.getValue()[0] = live(tx, e.getValue()[0]);
            publicIds.put(e.getKey(), publicId(tx, e.getValue()[0]));
        }
        try (PreparedStatement find = tx.prepareStatement("SELECT stmt_rowid FROM kg_statement WHERE subj = ? AND pred = ? AND obj_key = ?");
                PreparedStatement insStmt = tx.prepareStatement("INSERT INTO kg_statement (public_id, subj, pred, obj_ent, obj_val, obj_key,"
                        + " quality, current_sources, first_seen, last_confirmed) VALUES (?, ?, ?, ?, ?, ?, 4, 0, ?, NULL)");
                PreparedStatement insEv = tx.prepareStatement("INSERT OR IGNORE INTO kg_evidence (stmt_rowid, doc_rowid, tier, ext_id,"
                        + " kind, certainty, confidence, locator, excerpt, observed_at) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)")) {
            for (final Claim c : ex.claims()) {
                final Vocabulary.Predicate p = Vocabulary.predicate(c.predicate);
                if (p == null || p.relation != c.relation() || !replaced(replacedTiers, c.tier)) {
                    continue;
                }
                final long subj = ents.get(c.subject)[0];
                final String subjId = publicIds.get(c.subject);
                final Long objEnt;
                final String objVal;
                final String canonical;
                if (c.relation()) {
                    objEnt = ents.get(c.object)[0];
                    if (objEnt == subj) {
                        continue;
                    }
                    objVal = null;
                    canonical = "e:" + publicIds.get(c.object);
                } else {
                    objEnt = null;
                    objVal = Normalizers.clip(c.value, 1000);
                    canonical = "v:" + p.datatype + ":" + compareForm(p.datatype, objVal);
                }
                final byte[] objKey = KgIds.objectKey(canonical);
                final int pred = this.terms.predicate(c.predicate);
                Long stmt = null;
                find.setLong(1, subj);
                find.setInt(2, pred);
                find.setBytes(3, objKey);
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) {
                        stmt = rs.getLong(1);
                    }
                }
                if (stmt == null) {
                    insStmt.setString(1, KgIds.statementId(subjId, c.predicate, canonical));
                    insStmt.setLong(2, subj);
                    insStmt.setInt(3, pred);
                    if (objEnt == null) {
                        insStmt.setNull(4, Types.INTEGER);
                    } else {
                        insStmt.setLong(4, objEnt);
                    }
                    insStmt.setString(5, objVal);
                    insStmt.setBytes(6, objKey);
                    insStmt.setLong(7, now);
                    insStmt.executeUpdate();
                    stmt = net.yacy.scoutro.knowledge.store.KgStore.queryLong(tx, "SELECT last_insert_rowid()");
                    agg.createdStatement(stmt);
                } else {
                    agg.touchStatement(tx, stmt);
                }
                insEv.setLong(1, stmt);
                insEv.setLong(2, rowid);
                insEv.setInt(3, c.tier);
                insEv.setInt(4, c.kind == Claim.KIND_LLM ? llmExtId : extractor(c));
                insEv.setInt(5, c.kind);
                insEv.setInt(6, c.hedged ? 2 : 1);
                insEv.setString(7, c.locator);
                insEv.setString(8, Normalizers.redactPersons(Normalizers.clip(c.excerpt, Math.min(1000, this.cfg.extractMaxExcerptChars))));
                insEv.setLong(9, now);
                if (insEv.executeUpdate() > 0) {
                    result.statements++;
                }
            }
        }
        final IdentityResolver.Counters rc = resolver.counters();
        result.entitiesCreated = rc.created;
        result.merged = rc.merged;
        result.conflicts = rc.conflicts;
    }

    private static boolean replaced(final int[] tiers, final int tier) {
        for (final int t : tiers) {
            if (t == tier) {
                return true;
            }
        }
        return false;
    }

    private int extractor(final Claim c) {
        switch (c.kind) {
            case Claim.KIND_JSONLD:
                return this.terms.extractor(1, JsonLdExtractor.NAME, JsonLdExtractor.VERSION);
            case Claim.KIND_METADATA:
                return this.terms.extractor(1, MetadataExtractor.NAME, MetadataExtractor.VERSION);
            default:
                return this.terms.extractor(2, RuleExtractor.NAME, RuleExtractor.VERSION);
        }
    }

    /** Comparison form of a literal: identifiers and contact values are canonical already; text is compared normalised. */
    static String compareForm(final String datatype, final String value) {
        if (Vocabulary.T_STRING.equals(datatype) || Vocabulary.T_ADDRESS.equals(datatype)) {
            final String k = Normalizers.key(value);
            return k == null ? value : k;
        }
        return value;
    }

    /**
     * Removes tracked documents with all their evidence (Solr deleted them, or
     * they left the followed collections). A document whose generation is not
     * the expected one changed meanwhile and is skipped.
     *
     * @param expected document ID -> expected generation, or null to remove unconditionally
     * @return removed documents
     */
    public int remove(final Connection tx, final Collection<String> docIds, final Map<String, Long> expected, final long now)
            throws SQLException {
        final Aggregates agg = new Aggregates(this.cfg, this.terms, now);
        int removed = 0;
        for (final String id : docIds) {
            final Row cur = row(tx, id);
            if (cur == null) {
                continue;
            }
            final Long want = expected == null ? null : expected.get(id);
            if (want != null && want != cur.generation) {
                continue;
            }
            agg.touchDocument(tx, cur.rowid, null);
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_doc WHERE doc_rowid = ?")) {
                ps.setLong(1, cur.rowid);
                removed += ps.executeUpdate();
            }
        }
        agg.finish(tx);
        return removed;
    }

    /** Sets the state of tracked documents (expiry); evidence stays, quality is recomputed. */
    public int setState(final Connection tx, final Collection<Long> docRowids, final int state, final long now) throws SQLException {
        final Aggregates agg = new Aggregates(this.cfg, this.terms, now);
        int changed = 0;
        for (final long rowid : docRowids) {
            agg.touchDocument(tx, rowid, null);
            try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_doc SET state = ?, state_since = ?, generation = generation + 1"
                    + " WHERE doc_rowid = ? AND state <> ?")) {
                ps.setInt(1, state);
                ps.setLong(2, now);
                ps.setLong(3, rowid);
                ps.setInt(4, state);
                changed += ps.executeUpdate();
            }
        }
        agg.finish(tx);
        return changed;
    }

    /**
     * Deletes the evidence of documents past retention and forgets their input
     * hash, so a recrawl with the same content extracts again; the documents
     * stay tracked (an active Solr document would otherwise be re-added by
     * every reconcile).
     */
    public int purgeEvidence(final Connection tx, final Collection<Long> docRowids, final long now) throws SQLException {
        final Aggregates agg = new Aggregates(this.cfg, this.terms, now);
        int purged = 0;
        for (final long rowid : docRowids) {
            agg.touchDocument(tx, rowid, null);
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_evidence WHERE doc_rowid = ?")) {
                ps.setLong(1, rowid);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_doc SET input_hash = NULL, tiers = 0, generation = generation + 1,"
                    + " llm_status = NULL, llm_hash = NULL, llm_reason = NULL WHERE doc_rowid = ?")) {
                ps.setLong(1, rowid);
                purged += ps.executeUpdate();
            }
        }
        agg.finish(tx);
        return purged;
    }

    /** Deletes statements (and their evidence) by row; for the stale-statement retention. */
    public int deleteStatements(final Connection tx, final Collection<Long> stmts, final long now) throws SQLException {
        final Aggregates agg = new Aggregates(this.cfg, this.terms, now);
        for (final long s : stmts) {
            agg.touchStatement(tx, s);
            // the sources must extract again when they come back with the same content
            try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_doc SET input_hash = NULL WHERE doc_rowid IN"
                    + " (SELECT doc_rowid FROM kg_evidence WHERE stmt_rowid = ?)")) {
                ps.setLong(1, s);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_evidence WHERE stmt_rowid = ?")) {
                ps.setLong(1, s);
                ps.executeUpdate();
            }
        }
        agg.finish(tx);
        return agg.statementsDeleted;
    }

    private static Set<Integer> collections(final Connection tx, final long rowid) throws SQLException {
        final Set<Integer> out = new TreeSet<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT coll_id FROM kg_doc_collection WHERE doc_rowid = ?")) {
            ps.setLong(1, rowid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        }
        return out;
    }

    /** The surviving entity of a possibly merged row. */
    private static long live(final Connection tx, final long ent) throws SQLException {
        long e = ent;
        for (int i = 0; i < 16; i++) {
            try (PreparedStatement ps = tx.prepareStatement("SELECT status, merged_into FROM kg_entity WHERE ent_rowid = ?")) {
                ps.setLong(1, e);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) == 1) {
                        return e;
                    }
                    e = rs.getLong(2);
                }
            }
        }
        return e;
    }

    private static String publicId(final Connection tx, final long ent) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT public_id FROM kg_entity WHERE ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String hostId(final String hostId) {
        return hostId != null && hostId.length() == 6 ? hostId : null;
    }

    private static String clipUrl(final String url) {
        return url == null || url.length() <= 4096 ? url : url.substring(0, 4096);
    }

    private static void setNullable(final PreparedStatement ps, final int i, final Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setLong(i, v);
        }
    }
}
