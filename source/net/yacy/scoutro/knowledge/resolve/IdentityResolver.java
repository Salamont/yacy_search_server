/*
 *  IdentityResolver
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

package net.yacy.scoutro.knowledge.resolve;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.yacy.scoutro.knowledge.KgIds;
import net.yacy.scoutro.knowledge.extract.Mention;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * Resolves the mentions of one document to entities
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 4.3).
 * <p>
 * Keys, strongest first, all scoped by the entity type:
 * <ol>
 * <li>strong identifiers (register with court, VAT, LEI, Wikidata, IK): global;</li>
 * <li>{@code ld_id}: the JSON-LD {@code @id}, within the declaring host;</li>
 * <li>{@code site_operator}: the declared operator's legal name, within the registrable domain;</li>
 * <li>{@code operator_name}: the same legal name of an organisation that is <em>not</em> declared as
 * operator (a parent organisation on a facility page, a name in running text): it resolves to the
 * declared operator of the domain, in either order, but two such mentions never merge with each other
 * (a portal lists unrelated organisations whose legal names may be equal);</li>
 * <li>{@code facility_address}: name and <em>full</em> address, within the registrable domain;</li>
 * <li>{@code doc_local}: the mention within its document (keeps re-extraction stable).</li>
 * </ol>
 * Name and postal code, phone, e-mail and homepage are never keys. A merge
 * happens only if no discriminator conflicts: different values of the same
 * strong scheme, different {@code @id}s on the same host, different facility
 * kinds, different full addresses. A blocked merge is recorded as an
 * {@code identity_conflict} event; the entities stay separate, duplicates are
 * preferred to wrong merges. The older entity survives a merge; the other
 * becomes a redirect.
 */
public final class IdentityResolver {

    /** A key of one mention: scheme, scope (type-qualified), normalised value. */
    static final class Key {
        final String scheme;
        final String scope;
        final String value;

        Key(final String scheme, final String scope, final String value) {
            this.scheme = scheme;
            this.scope = scope;
            this.value = value;
        }

        @Override
        public String toString() {
            return this.scheme + "/" + this.scope + "/" + this.value;
        }
    }

    /** What one document resolution did; for the status counters. */
    public static final class Counters {
        public int created;
        public int merged;
        public int conflicts;
    }

    private final Terms terms;
    private final Counters counters = new Counters();

    public IdentityResolver(final Terms terms) {
        this.terms = terms;
    }

    public Counters counters() {
        return this.counters;
    }

    /** The keys of a mention in the order of their strength. */
    static List<Key> keys(final Mention m, final String docId, final String host, final String domain) {
        final List<Key> out = new ArrayList<>();
        for (final String scheme : Vocabulary.STRONG_SCHEMES) {
            final String v = m.strongKeys.get(scheme);
            if (v != null) {
                out.add(new Key(scheme, m.type, v));
            }
        }
        if (m.ldId != null && host != null) {
            out.add(new Key(Vocabulary.LD_ID, m.type + "@" + host, m.ldId));
        }
        if (Vocabulary.ORGANIZATION.equals(m.type) && domain != null) {
            final String legal = Normalizers.legalNameKey(m.legalName);
            if (legal != null) {
                out.add(new Key(m.siteOperator ? Vocabulary.SITE_OPERATOR : Vocabulary.OPERATOR_NAME, m.type + "@" + domain, legal));
            }
        }
        if ((Vocabulary.FACILITY.equals(m.type) || Vocabulary.SITE.equals(m.type)) && m.address != null && m.address.complete()
                && m.name != null && domain != null) {
            // the facility kind is part of the key: a day care and a residential home at one address are two facilities
            out.add(new Key(Vocabulary.FACILITY_ADDRESS, m.type + "@" + domain,
                    (m.subkind == null ? "" : m.subkind) + "|" + Normalizers.key(m.name) + "|" + m.address.key()));
        }
        out.add(new Key(Vocabulary.DOC_LOCAL, m.type + "#" + docId, m.ref));
        return out;
    }

    /**
     * Resolves {@code m} inside the caller's write transaction and returns the
     * entity row; creates the entity, attaches new keys and merges where the
     * rules allow.
     */
    public long resolve(final Connection tx, final Mention m, final String docId, final String host, final String domain,
            final Aggregates agg) throws SQLException {
        final List<Key> keys = keys(m, docId, host, domain);
        final Map<Key, Long> found = new LinkedHashMap<>();
        for (final Key k : keys) {
            // an operator name looks for the declared operator only, never for another holder of the name
            final Long ent = lookup(tx, Vocabulary.OPERATOR_NAME.equals(k.scheme) ? new Key(Vocabulary.SITE_OPERATOR, k.scope, k.value) : k);
            if (ent != null) {
                found.put(k, ent);
            }
        }
        // the first candidate that does not contradict the mention is the primary
        Long primary = null;
        for (final Long ent : found.values()) {
            if (primary != null && primary.equals(ent)) {
                continue;
            }
            if (!compatible(tx, ent, m, keys)) {
                conflict(tx, agg, ent, null, m, "mention contradicts entity");
                continue;
            }
            if (primary == null) {
                primary = ent;
            } else if (mergeable(tx, primary, ent)) {
                primary = merge(tx, primary, ent, agg);
            } else {
                conflict(tx, agg, primary, ent, m, "discriminator");
            }
        }
        if (primary == null) {
            primary = create(tx, m, creationKey(keys, found), agg);
        }
        // the declared operator takes in the organisations of the domain that were seen under its legal name before
        for (final Key k : keys) {
            if (Vocabulary.SITE_OPERATOR.equals(k.scheme)) {
                final Long named = lookup(tx, new Key(Vocabulary.OPERATOR_NAME, k.scope, k.value));
                if (named != null && !named.equals(primary)) {
                    if (mergeable(tx, primary, named)) {
                        primary = merge(tx, primary, named, agg);
                    } else {
                        conflict(tx, agg, primary, named, m, "discriminator");
                    }
                }
            }
        }
        attach(tx, primary, keys, found);
        if (m.subkind != null) {
            try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_entity SET subkind = ? WHERE ent_rowid = ? AND subkind IS NULL")) {
                ps.setString(1, m.subkind);
                ps.setLong(2, primary);
                ps.executeUpdate();
            }
        }
        return primary;
    }

    /**
     * The key a new entity's public ID is derived from: the strongest key no
     * other entity holds. A key held by an entity the mention contradicts
     * would derive that entity's ID again.
     */
    static Key creationKey(final List<Key> keys, final Map<Key, Long> found) {
        for (final Key k : keys) {
            // an operator name is shared by unrelated organisations of a portal: never an ID
            if (!found.containsKey(k) && !Vocabulary.OPERATOR_NAME.equals(k.scheme)) {
                return k;
            }
        }
        // even the document-local key is held by a contradicted entity (the document changed the mention's kind)
        final Key local = keys.get(keys.size() - 1);
        return new Key(local.scheme, local.scope, local.value + "~" + keys.size() + "~" + found.size());
    }

    private Long lookup(final Connection tx, final Key k) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT k.ent_rowid FROM kg_entity_key k JOIN kg_entity e ON e.ent_rowid = k.ent_rowid"
                + " WHERE k.scheme = ? AND k.scope = ? AND k.value = ? AND e.status = 1")) {
            ps.setInt(1, this.terms.scheme(k.scheme));
            ps.setString(2, k.scope);
            ps.setString(3, k.value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** The mention's own discriminators against an entity: strong values, @id per host, kind, full address. */
    private boolean compatible(final Connection tx, final long ent, final Mention m, final List<Key> keys) throws SQLException {
        final Map<String, List<String>> have = keysOf(tx, ent);
        for (final Key k : keys) {
            if (Vocabulary.DOC_LOCAL.equals(k.scheme) || Vocabulary.SITE_OPERATOR.equals(k.scheme)
                    || Vocabulary.OPERATOR_NAME.equals(k.scheme)) {
                continue;
            }
            final List<String> existing = have.get(k.scheme + "\u0000" + k.scope);
            if (existing != null && !existing.contains(k.value)) {
                return false;
            }
        }
        final String kind = subkind(tx, ent);
        return kind == null || m.subkind == null || kind.equals(m.subkind);
    }

    /** Two entities may merge if none of their discriminators conflict. */
    private boolean mergeable(final Connection tx, final long a, final long b) throws SQLException {
        if (!Objects.equals(type(tx, a), type(tx, b))) {
            return false;
        }
        final String ka = subkind(tx, a);
        final String kb = subkind(tx, b);
        if (ka != null && kb != null && !ka.equals(kb)) {
            return false;
        }
        final Map<String, List<String>> va = keysOf(tx, a);
        final Map<String, List<String>> vb = keysOf(tx, b);
        for (final Map.Entry<String, List<String>> e : va.entrySet()) {
            final String scheme = e.getKey().substring(0, e.getKey().indexOf('\u0000'));
            if (Vocabulary.DOC_LOCAL.equals(scheme) || Vocabulary.SITE_OPERATOR.equals(scheme) || Vocabulary.OPERATOR_NAME.equals(scheme)) {
                continue;
            }
            final List<String> other = vb.get(e.getKey());
            if (other != null && java.util.Collections.disjoint(other, e.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** Keys of an entity, grouped by scheme and scope. */
    private Map<String, List<String>> keysOf(final Connection tx, final long ent) throws SQLException {
        final Map<String, List<String>> out = new HashMap<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT v.name, k.scope, k.value FROM kg_entity_key k"
                + " JOIN kg_vocab v ON v.term_id = k.scheme WHERE k.ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString(1) + "\u0000" + rs.getString(2), x -> new ArrayList<>()).add(rs.getString(3));
                }
            }
        }
        return out;
    }

    private static String subkind(final Connection tx, final long ent) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT subkind FROM kg_entity WHERE ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static Integer type(final Connection tx, final long ent) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT type FROM kg_entity WHERE ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private static String publicId(final Connection tx, final long ent) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT public_id FROM kg_entity WHERE ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private long create(final Connection tx, final Mention m, final Key first, final Aggregates agg) throws SQLException {
        final String publicId = KgIds.entityId(m.type, first.scheme, first.scope, first.value);
        final long seq = KgStore.queryLong(tx, "SELECT coalesce(max(created_seq), 0) + 1 FROM kg_entity");
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO kg_entity (public_id, type, status, merged_into, created_seq, subkind)"
                + " VALUES (?, ?, 1, NULL, ?, ?) ON CONFLICT (public_id) DO NOTHING")) {
            ps.setString(1, publicId);
            ps.setInt(2, this.terms.type(m.type));
            ps.setLong(3, seq);
            ps.setString(4, m.subkind);
            ps.executeUpdate();
        }
        long ent;
        int status;
        try (PreparedStatement ps = tx.prepareStatement("SELECT ent_rowid, status, merged_into FROM kg_entity WHERE public_id = ?")) {
            ps.setString(1, publicId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                ent = rs.getLong(1);
                status = rs.getInt(2);
                if (status == 2) {
                    ent = rs.getLong(3); // the ID was merged before: its survivor stands for it
                }
            }
        }
        if (status == 1) {
            this.counters.created++;
            agg.createdEntity(ent);
        }
        return ent;
    }

    private void attach(final Connection tx, final long ent, final List<Key> keys, final Map<Key, Long> found) throws SQLException {
        final Map<String, List<String>> have = keysOf(tx, ent);
        try (PreparedStatement ps = tx.prepareStatement("INSERT OR IGNORE INTO kg_entity_key (scheme, scope, value, ent_rowid) VALUES (?, ?, ?, ?)")) {
            for (final Key k : keys) {
                final Long owner = found.get(k);
                if (owner != null && owner != ent) {
                    continue; // owned by an entity that could not be merged
                }
                if (Vocabulary.STRONG_SCHEMES.contains(k.scheme) || Vocabulary.LD_ID.equals(k.scheme)
                        || Vocabulary.FACILITY_ADDRESS.equals(k.scheme)) {
                    final List<String> existing = have.get(k.scheme + "\u0000" + k.scope);
                    if (existing != null && !existing.contains(k.value)) {
                        continue; // a second value of a discriminating scheme is never attached
                    }
                }
                ps.setInt(1, this.terms.scheme(k.scheme));
                ps.setString(2, k.scope);
                ps.setString(3, k.value);
                ps.setLong(4, ent);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Merges two entities; the older one (lower {@code created_seq}) survives.
     *
     * @return the survivor
     */
    private long merge(final Connection tx, final long a, final long b, final Aggregates agg) throws SQLException {
        final long seqA = KgStore.queryLong(tx, "SELECT created_seq FROM kg_entity WHERE ent_rowid = " + a);
        final long seqB = KgStore.queryLong(tx, "SELECT created_seq FROM kg_entity WHERE ent_rowid = " + b);
        final long survivor = seqA < seqB || (seqA == seqB && a < b) ? a : b;
        final long loser = survivor == a ? b : a;
        agg.touchEntity(tx, survivor);
        agg.touchEntity(tx, loser);
        final String survivorId = publicId(tx, survivor);
        final String loserId = publicId(tx, loser);
        KgStore.event(tx, 1, "identity_merge", loserId + " -> " + survivorId, agg.now());

        exec(tx, "UPDATE OR IGNORE kg_entity_key SET ent_rowid = ? WHERE ent_rowid = ?", survivor, loser);
        exec(tx, "DELETE FROM kg_entity_key WHERE ent_rowid = ?", loser);
        // statements with the loser as subject, then as object
        for (final long[] st : rows(tx, "SELECT stmt_rowid, pred, obj_key FROM kg_statement WHERE subj = ?", loser)) {
            repoint(tx, agg, st[0], survivor, null, null);
        }
        for (final long[] st : rows(tx, "SELECT stmt_rowid, pred, 0 FROM kg_statement WHERE obj_ent = ?", loser)) {
            repoint(tx, agg, st[0], null, survivor, survivorId);
        }
        exec(tx, "UPDATE kg_entity SET merged_into = ? WHERE merged_into = ?", survivor, loser);
        exec(tx, "UPDATE kg_entity_redirect SET target_rowid = ? WHERE target_rowid = ?", survivor, loser);
        exec(tx, "DELETE FROM kg_entity_scope WHERE ent_rowid = ?", loser);
        exec(tx, "DELETE FROM kg_host_entity WHERE ent_rowid = ?", loser);
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_entity SET subkind = (SELECT subkind FROM kg_entity WHERE ent_rowid = ?)"
                + " WHERE ent_rowid = ? AND subkind IS NULL")) {
            ps.setLong(1, loser);
            ps.setLong(2, survivor);
            ps.executeUpdate();
        }
        exec(tx, "UPDATE kg_entity SET status = 2, merged_into = ? WHERE ent_rowid = ?", survivor, loser);
        try (PreparedStatement ps = tx.prepareStatement("INSERT OR REPLACE INTO kg_entity_redirect (public_id, target_rowid) VALUES (?, ?)")) {
            ps.setString(1, loserId);
            ps.setLong(2, survivor);
            ps.executeUpdate();
        }
        agg.redirectedEntity(tx, loser, survivorId);
        this.counters.merged++;
        return survivor;
    }

    /**
     * Moves a statement to a new subject or object entity. If the target
     * statement already exists, the evidence is united there and the moved
     * statement becomes a redirect.
     */
    private void repoint(final Connection tx, final Aggregates agg, final long stmt, final Long newSubj, final Long newObj,
            final String newObjPublicId) throws SQLException {
        agg.touchStatement(tx, stmt);
        long subj;
        int pred;
        byte[] objKey;
        String publicId;
        try (PreparedStatement ps = tx.prepareStatement("SELECT subj, pred, obj_key, public_id FROM kg_statement WHERE stmt_rowid = ?")) {
            ps.setLong(1, stmt);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                subj = rs.getLong(1);
                pred = rs.getInt(2);
                objKey = rs.getBytes(3);
                publicId = rs.getString(4);
            }
        }
        final long targetSubj = newSubj != null ? newSubj : subj;
        final byte[] targetKey = newObj != null ? KgIds.objectKey("e:" + newObjPublicId) : objKey;
        Long existing = null;
        try (PreparedStatement ps = tx.prepareStatement("SELECT stmt_rowid FROM kg_statement WHERE subj = ? AND pred = ? AND obj_key = ? AND stmt_rowid <> ?")) {
            ps.setLong(1, targetSubj);
            ps.setInt(2, pred);
            ps.setBytes(3, targetKey);
            ps.setLong(4, stmt);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    existing = rs.getLong(1);
                }
            }
        }
        if (existing == null) {
            try (PreparedStatement ps = tx.prepareStatement(newObj != null
                    ? "UPDATE kg_statement SET obj_ent = ?, obj_key = ? WHERE stmt_rowid = ?"
                    : "UPDATE kg_statement SET subj = ? WHERE stmt_rowid = ?")) {
                if (newObj != null) {
                    ps.setLong(1, newObj);
                    ps.setBytes(2, targetKey);
                    ps.setLong(3, stmt);
                } else {
                    ps.setLong(1, targetSubj);
                    ps.setLong(2, stmt);
                }
                ps.executeUpdate();
            }
            return;
        }
        agg.touchStatement(tx, existing);
        final String targetId;
        try (PreparedStatement ps = tx.prepareStatement("SELECT public_id FROM kg_statement WHERE stmt_rowid = ?")) {
            ps.setLong(1, existing);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                targetId = rs.getString(1);
            }
        }
        exec(tx, "INSERT OR IGNORE INTO kg_evidence (stmt_rowid, doc_rowid, tier, ext_id, kind, certainty, confidence, locator, excerpt, observed_at)"
                + " SELECT ?, doc_rowid, tier, ext_id, kind, certainty, confidence, locator, excerpt, observed_at FROM kg_evidence WHERE stmt_rowid = ?",
                existing, stmt);
        exec(tx, "UPDATE kg_statement_redirect SET target_rowid = ? WHERE target_rowid = ?", existing, stmt);
        agg.redirectedStatement(tx, stmt, targetId);
        exec(tx, "DELETE FROM kg_name_fts WHERE rowid = ?", stmt);
        exec(tx, "DELETE FROM kg_statement WHERE stmt_rowid = ?", stmt);
        try (PreparedStatement ps = tx.prepareStatement("INSERT OR REPLACE INTO kg_statement_redirect (public_id, target_rowid) VALUES (?, ?)")) {
            ps.setString(1, publicId);
            ps.setLong(2, existing);
            ps.executeUpdate();
        }
    }

    private void conflict(final Connection tx, final Aggregates agg, final long a, final Long b, final Mention m, final String why)
            throws SQLException {
        this.counters.conflicts++;
        KgStore.event(tx, 2, "identity_conflict", why + ": " + publicId(tx, a) + (b == null ? "" : " / " + publicId(tx, b))
                + " (" + m.type + " " + Normalizers.clip(m.name, 80) + ")", agg.now());
    }

    private static void exec(final Connection tx, final String sql, final Object... args) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static List<long[]> rows(final Connection tx, final String sql, final long arg) throws SQLException {
        final List<long[]> out = new ArrayList<>();
        try (PreparedStatement ps = tx.prepareStatement(sql)) {
            ps.setLong(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new long[] {rs.getLong(1), rs.getLong(2), 0L});
                }
            }
        }
        return out;
    }
}
