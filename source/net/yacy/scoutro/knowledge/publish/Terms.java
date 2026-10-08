/*
 *  Terms
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.MetadataExtractor;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.extract.Vocabulary;

/**
 * Integer IDs of vocabulary terms, extractors and collections
 * ({@code kg_vocab}, {@code kg_extractor}, {@code kg_collection}). The
 * vocabulary and the extractors are seeded idempotently at start; collections
 * are created on first use. IDs are cached: rows are never deleted while the
 * store is open (a full reset keeps them).
 */
public final class Terms {

    public static final int KIND_TYPE = 1;
    public static final int KIND_PREDICATE = 2;
    public static final int KIND_SCHEME = 3;

    private final Map<String, Integer> vocab = new ConcurrentHashMap<>();
    private final Map<String, Integer> collections = new ConcurrentHashMap<>();
    private final Map<Integer, String> collectionNames = new ConcurrentHashMap<>();
    private final Map<String, Integer> extractors = new ConcurrentHashMap<>();

    /** Seeds vocabulary and extractors (inside a write transaction) and loads all IDs. */
    public void seed(final Connection tx) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO kg_vocab (kind, name, functional) VALUES (?, ?, ?)"
                + " ON CONFLICT (kind, name) DO UPDATE SET functional = excluded.functional")) {
            for (final String t : Vocabulary.TYPES) {
                add(ps, KIND_TYPE, t, false);
            }
            for (final Vocabulary.Predicate p : Vocabulary.PREDICATES.values()) {
                add(ps, KIND_PREDICATE, p.name, p.functional);
            }
            for (final String s : Vocabulary.SCHEMES) {
                add(ps, KIND_SCHEME, s, false);
            }
        }
        try (PreparedStatement ps = tx.prepareStatement("INSERT OR IGNORE INTO kg_extractor (tier, name, version) VALUES (?, ?, ?)")) {
            extractor(ps, 1, JsonLdExtractor.NAME, JsonLdExtractor.VERSION);
            extractor(ps, 1, MetadataExtractor.NAME, MetadataExtractor.VERSION);
            extractor(ps, 2, RuleExtractor.NAME, RuleExtractor.VERSION);
        }
        load(tx);
    }

    /** Loads the IDs without writing (read-only start). */
    public void load(final Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT kind, name, term_id FROM kg_vocab");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                this.vocab.put(rs.getInt(1) + ":" + rs.getString(2), rs.getInt(3));
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT tier, name, version, ext_id FROM kg_extractor WHERE model = '' AND prompt_hash = ''");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                this.extractors.put(rs.getInt(1) + ":" + rs.getString(2) + ":" + rs.getString(3), rs.getInt(4));
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT coll_id, name FROM kg_collection"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                this.collections.put(rs.getString(2), rs.getInt(1));
                this.collectionNames.put(rs.getInt(1), rs.getString(2));
            }
        }
    }

    private static void add(final PreparedStatement ps, final int kind, final String name, final boolean functional) throws SQLException {
        ps.setInt(1, kind);
        ps.setString(2, name);
        ps.setInt(3, functional ? 1 : 0);
        ps.executeUpdate();
    }

    private static void extractor(final PreparedStatement ps, final int tier, final String name, final String version) throws SQLException {
        ps.setInt(1, tier);
        ps.setString(2, name);
        ps.setString(3, version);
        ps.executeUpdate();
    }

    public boolean seeded() {
        return !this.vocab.isEmpty();
    }

    public int type(final String name) {
        return id(KIND_TYPE, name);
    }

    public int predicate(final String name) {
        return id(KIND_PREDICATE, name);
    }

    public int scheme(final String name) {
        return id(KIND_SCHEME, name);
    }

    private int id(final int kind, final String name) {
        final Integer id = this.vocab.get(kind + ":" + name);
        if (id == null) {
            throw new IllegalStateException("unknown vocabulary term " + kind + ":" + name);
        }
        return id;
    }

    public String predicateName(final int termId) {
        for (final Map.Entry<String, Integer> e : this.vocab.entrySet()) {
            if (e.getValue() == termId && e.getKey().startsWith(KIND_PREDICATE + ":")) {
                return e.getKey().substring(2);
            }
        }
        return null;
    }

    public int extractor(final int tier, final String name, final String version) {
        final Integer id = this.extractors.get(tier + ":" + name + ":" + version);
        if (id == null) {
            throw new IllegalStateException("unknown extractor " + tier + ":" + name + ":" + version);
        }
        return id;
    }

    /**
     * ID of the LLM tier's extractor for a model (tier 3, name, version, model,
     * prompt hash), created on first use inside a write transaction. A new
     * model or prompt is a new extractor; its evidence names it.
     */
    public int llmExtractor(final Connection tx, final String model) throws SQLException {
        return llmExtractor(tx, model, LlmExtractor.PROMPT_HASH);
    }

    /** {@link #llmExtractor(Connection, String)} for the prompt with this hash (the active one, KnowledgePrompt). */
    public int llmExtractor(final Connection tx, final String model, final String promptHash) throws SQLException {
        // not cached: the row may come from a transaction that is rolled back
        try (PreparedStatement ps = tx.prepareStatement("INSERT OR IGNORE INTO kg_extractor (tier, name, version, model, prompt_hash)"
                + " VALUES (3, ?, ?, ?, ?)")) {
            ps.setString(1, LlmExtractor.NAME);
            ps.setString(2, LlmExtractor.VERSION);
            ps.setString(3, model);
            ps.setString(4, promptHash);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tx.prepareStatement("SELECT ext_id FROM kg_extractor WHERE tier = 3 AND name = ? AND version = ?"
                + " AND model = ? AND prompt_hash = ?")) {
            ps.setString(1, LlmExtractor.NAME);
            ps.setString(2, LlmExtractor.VERSION);
            ps.setString(3, model);
            ps.setString(4, promptHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("extractor row missing");
                }
                return rs.getInt(1);
            }
        }
    }

    /** ID of a collection, created on first use (inside a write transaction). */
    public int collection(final Connection tx, final String name) throws SQLException {
        final Integer id = this.collections.get(name);
        if (id != null) {
            return id;
        }
        try (PreparedStatement ps = tx.prepareStatement("INSERT OR IGNORE INTO kg_collection (name) VALUES (?)")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tx.prepareStatement("SELECT coll_id FROM kg_collection WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                final int cid = rs.getInt(1);
                // cached only after the transaction is known to commit: callers reload on rollback via forgetCollections
                this.collections.put(name, cid);
                this.collectionNames.put(cid, name);
                return cid;
            }
        }
    }

    /** Drops cached collection IDs, e.g. after a failed transaction that may have created some. */
    public void forgetCollections() {
        this.collections.clear();
        this.collectionNames.clear();
    }

    public String collectionName(final int id) {
        return this.collectionNames.get(id);
    }
}
