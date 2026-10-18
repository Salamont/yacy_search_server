/*
 *  IdentityPreview
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

import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * What the WP-F identity changes ({@code scoutro.kg.identity.productUrl}, {@code .providerServiceKey}) would do to the
 * entities of one registrable domain, before any of them is switched on (docs/SCOUTRO_KG_EXTRACTION_SEMANTICS.md).
 * <p>
 * Tiers 1 and 2 of a sample of the domain's active pages are extracted from Solr and published twice inside one write
 * transaction: with the identity of the configuration (the baseline) and, after a rollback to a savepoint, with the
 * previewed identity. The difference is the effect of the change alone; the transaction is rolled back, so nothing is
 * written. The LLM tier is never called: its share is estimated from the stored offers of the model.
 */
final class IdentityPreview {

    static final int MAX_DOCS = 200;
    /** Time for the two publications; a sample that takes longer is cut ({@code truncated}). */
    static final long MAX_MILLIS = 20_000L;
    private static final int SAMPLES = 20;

    /** Carries the result out of the write transaction, which rolls back on it. */
    private static final class Rollback extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient JSONObject result;

        Rollback(final JSONObject result) {
            super(null, null, false, false);
            this.result = result;
        }
    }

    /** What one publication left: the entities each page's facts are on, merges and conflicts. */
    private static final class Pass {
        final Map<String, Set<String>> entities = new LinkedHashMap<>();
        final Set<String> services = new TreeSet<>();
        final List<String> merges = new ArrayList<>();
        final List<String> conflicts = new ArrayList<>();
        int published;

        JSONObject json() {
            final Set<String> all = new TreeSet<>();
            for (final Set<String> s : this.entities.values()) {
                all.addAll(s);
            }
            return KgJson.obj("entities", all.size(), "services", this.services.size(), "merges", this.merges.size(), "conflicts",
                    this.conflicts.size(), "published", this.published);
        }
    }

    private IdentityPreview() {}

    static JSONObject run(final KgConfig cfg, final KgStore store, final SolrSource solr, final BaseTiers tiers, final LongSupplier clock,
            final String domain, final boolean productUrl, final boolean providerServiceKey, final int limit) throws KgException {
        final int max = Math.max(1, Math.min(MAX_DOCS, limit));
        final List<String> ids = store.read(c -> documents(c, domain, max + 1));
        final boolean more = ids.size() > max;
        if (more) {
            ids.remove(ids.size() - 1);
        }
        // read and extract outside the write lock: Solr and the rules, never the model
        final Map<String, Publisher.Doc> docs = new LinkedHashMap<>();
        final Map<String, Extraction> extractions = new HashMap<>();
        int skipped = 0;
        try {
            for (int i = 0; i < ids.size(); i += SolrSource.MAX_GET) {
                final Map<String, SolrDoc> got = solr.get(ids.subList(i, Math.min(ids.size(), i + SolrSource.MAX_GET)), SolrDoc.PROCESS_FIELDS);
                for (final String id : ids.subList(i, Math.min(ids.size(), i + SolrSource.MAX_GET))) {
                    final SolrDoc d = got.get(id);
                    if (d == null || d.followed(cfg).isEmpty() || d.state() != Aggregates.STATE_ACTIVE) {
                        skipped++;
                        continue;
                    }
                    final Publisher.Doc doc = SyncService.toDoc(d, d.kept(cfg), Aggregates.STATE_ACTIVE);
                    doc.inputHash = d.inputHash();
                    docs.put(id, doc);
                    extractions.put(id, tiers.extract(d, new BaseTiers.Text(solr, d.id)));
                }
            }
        } catch (final IOException e) {
            throw new KgException(KgException.UNAVAILABLE, "solr_unavailable", "the pages cannot be read from Solr: " + e.getMessage(), e);
        }
        final long now = clock.getAsLong();
        final boolean[] truncated = {more};
        final int notRead = skipped;
        try {
            store.write(WriteClass.MAINTENANCE, 64L * 1024L * Math.max(1, docs.size()), tx -> {
                final long seq = KgStore.queryLong(tx, "SELECT coalesce(max(seq), 0) FROM kg_event");
                exec(tx, "SAVEPOINT identity_preview");
                final Pass before = pass(tx, cfg, docs, extractions, cfg.identityProductUrl, cfg.identityProviderServiceKey, seq, now, clock,
                        truncated);
                exec(tx, "ROLLBACK TO identity_preview");
                final Pass after = pass(tx, cfg, docs, extractions, productUrl, providerServiceKey, seq, now, clock, truncated);
                exec(tx, "ROLLBACK TO identity_preview");
                final JSONObject llm = llmEstimate(tx, domain, docs.keySet(), providerServiceKey && !cfg.identityProviderServiceKey);
                throw new Rollback(result(domain, productUrl, providerServiceKey, docs.size(), notRead, truncated[0], before, after, llm));
            });
        } catch (final Rollback r) {
            return r.result;
        }
        throw new IllegalStateException("the identity preview committed");
    }

    /** Active pages of the registrable domain, oldest first. */
    private static List<String> documents(final Connection c, final String domain, final int max) throws SQLException {
        final List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT doc_id, url FROM kg_doc WHERE state = 1 AND (url LIKE ? OR url LIKE ?)"
                + " ORDER BY doc_rowid")) {
            ps.setString(1, "%://" + domain + "%");
            ps.setString(2, "%." + domain + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < max) {
                    String host;
                    try {
                        host = URI.create(rs.getString(2)).getHost();
                    } catch (final IllegalArgumentException e) {
                        host = null;
                    }
                    if (domain.equals(Normalizers.registrableDomain(host))) {
                        out.add(rs.getString(1));
                    }
                }
            }
        }
        return out;
    }

    private static Pass pass(final Connection tx, final KgConfig cfg, final Map<String, Publisher.Doc> docs,
            final Map<String, Extraction> extractions, final boolean productUrl, final boolean providerServiceKey, final long seq,
            final long now, final LongSupplier clock, final boolean[] truncated) throws SQLException {
        // its own term cache: a collection this pass creates is rolled back with it
        final Terms terms = new Terms();
        terms.load(tx);
        final Publisher publisher = new Publisher(cfg, terms, productUrl, providerServiceKey);
        final Pass out = new Pass();
        final long deadline = clock.getAsLong() + MAX_MILLIS / 2;
        for (final Map.Entry<String, Publisher.Doc> e : docs.entrySet()) {
            if (clock.getAsLong() > deadline) {
                truncated[0] = true;
                break;
            }
            final Publisher.Row row = Publisher.row(tx, e.getKey());
            final Publisher.Result r = publisher.apply(tx, e.getValue(), row == null ? -1L : row.generation, extractions.get(e.getKey()), now);
            if (r.outcome == Publisher.Outcome.PUBLISHED) {
                out.published++;
            }
        }
        final int service = terms.type(Vocabulary.SERVICE);
        try (PreparedStatement ps = tx.prepareStatement("SELECT DISTINCT e.public_id, e.type FROM kg_evidence ev"
                + " JOIN kg_doc d ON d.doc_rowid = ev.doc_rowid JOIN kg_statement s ON s.stmt_rowid = ev.stmt_rowid"
                + " JOIN kg_entity e ON e.ent_rowid IN (s.subj, s.obj_ent) WHERE d.doc_id = ? AND ev.tier IN (1, 2)")) {
            for (final String id : docs.keySet()) {
                ps.setString(1, id);
                final Set<String> ents = new TreeSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ents.add(rs.getString(1));
                        if (rs.getInt(2) == service) {
                            out.services.add(rs.getString(1));
                        }
                    }
                }
                out.entities.put(id, ents);
            }
        }
        try (PreparedStatement ps = tx.prepareStatement("SELECT code, detail FROM kg_event WHERE seq > ? AND code IN"
                + " ('identity_merge', 'identity_conflict') ORDER BY seq")) {
            ps.setLong(1, seq);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ("identity_merge".equals(rs.getString(1)) ? out.merges : out.conflicts).add(rs.getString(2));
                }
            }
        }
        return out;
    }

    /**
     * The services of the model in the sample that {@code providerServiceKey} would give a key: one of an offer of the model,
     * on a service no rule names. {@code joinExisting}: the key is held by another entity already; {@code joinEachOther}: the
     * entities that would join another service of the model with the same key. An estimate: the key of the published
     * provider and name, as the next reading would derive it.
     */
    private static JSONObject llmEstimate(final Connection tx, final String domain, final Set<String> docIds, final boolean applies)
            throws SQLException {
        if (!applies) {
            return null;
        }
        final Map<Long, String> keyOf = new LinkedHashMap<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT DISTINCT s.subj, s.obj_ent FROM kg_evidence ev"
                + " JOIN kg_doc d ON d.doc_rowid = ev.doc_rowid JOIN kg_statement s ON s.stmt_rowid = ev.stmt_rowid"
                + " JOIN kg_vocab p ON p.term_id = s.pred WHERE d.doc_id = ? AND ev.tier = 3 AND p.name = ? AND s.obj_ent IS NOT NULL")) {
            for (final String id : docIds) {
                ps.setString(1, id);
                ps.setString(2, Vocabulary.OFFERS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final long provider = rs.getLong(1);
                        final long service = rs.getLong(2);
                        if (keyOf.containsKey(service) || hasScheme(tx, service, Vocabulary.SERVICE_NAME) || !modelOnly(tx, service)) {
                            continue;
                        }
                        final String p = providerKey(tx, provider);
                        final String name = name(tx, service);
                        if (p != null && name != null) {
                            keyOf.put(service, JsonLdExtractor.serviceKey(null, p, name));
                        }
                    }
                }
            }
        }
        int existing = 0;
        int eachOther = 0;
        final Map<String, Integer> groups = new HashMap<>();
        try (PreparedStatement ps = tx.prepareStatement("SELECT k.ent_rowid FROM kg_entity_key k JOIN kg_vocab v ON v.term_id = k.scheme"
                + " WHERE v.name = ? AND k.scope = ? AND k.value = ?")) {
            for (final Map.Entry<Long, String> e : keyOf.entrySet()) {
                ps.setString(1, Vocabulary.SERVICE_NAME);
                ps.setString(2, Vocabulary.SERVICE + "@" + domain);
                ps.setString(3, Normalizers.clip(e.getValue(), 500));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getLong(1) != e.getKey()) {
                        existing++;
                        continue;
                    }
                }
                if (groups.merge(e.getValue(), 1, Integer::sum) > 1) {
                    eachOther++;
                }
            }
        }
        return KgJson.obj("estimate", true, "services", keyOf.size(), "joinExisting", existing, "joinEachOther", eachOther);
    }

    /** The service is known from the model only: every evidence of it is of tier 3. */
    private static boolean modelOnly(final Connection tx, final long ent) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT 1 FROM kg_statement s JOIN kg_evidence ev ON ev.stmt_rowid = s.stmt_rowid"
                + " WHERE (s.subj = ? OR s.obj_ent = ?) AND ev.tier <> 3 LIMIT 1")) {
            ps.setLong(1, ent);
            ps.setLong(2, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        }
    }

    private static boolean hasScheme(final Connection tx, final long ent, final String scheme) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT 1 FROM kg_entity_key k JOIN kg_vocab v ON v.term_id = k.scheme"
                + " WHERE k.ent_rowid = ? AND v.name = ? LIMIT 1")) {
            ps.setLong(1, ent);
            ps.setString(2, scheme);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** {@link net.yacy.scoutro.knowledge.extract.Mention#providerKey} of a stored provider. */
    private static String providerKey(final Connection tx, final long ent) throws SQLException {
        if (hasScheme(tx, ent, Vocabulary.SITE_OPERATOR) || hasScheme(tx, ent, Vocabulary.DOMAIN_OPERATOR)) {
            return "operator";
        }
        final String name = Normalizers.key(name(tx, ent));
        if (name == null) {
            return null;
        }
        try (PreparedStatement ps = tx.prepareStatement("SELECT v.name, e.subkind FROM kg_entity e JOIN kg_vocab v ON v.term_id = e.type"
                + " WHERE e.ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) + ":" + (rs.getString(2) == null ? "" : rs.getString(2) + ":") + name : null;
            }
        }
    }

    private static String name(final Connection tx, final long ent) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("SELECT s.obj_val FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE s.subj = ? AND v.name = ? AND s.obj_val IS NOT NULL ORDER BY s.current_sources DESC, s.stmt_rowid LIMIT 1")) {
            ps.setLong(1, ent);
            ps.setString(2, Vocabulary.NAME);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static JSONObject result(final String domain, final boolean productUrl, final boolean providerServiceKey, final int docs,
            final int skipped, final boolean truncated, final Pass before, final Pass after, final JSONObject llm) {
        int changed = 0;
        final JSONArray pages = new JSONArray();
        for (final Map.Entry<String, Set<String>> e : after.entities.entrySet()) {
            final Set<String> was = before.entities.getOrDefault(e.getKey(), Set.of());
            if (!was.equals(e.getValue())) {
                changed++;
                if (pages.length() < SAMPLES) {
                    final Set<String> gone = new TreeSet<>(was);
                    gone.removeAll(e.getValue());
                    final Set<String> added = new TreeSet<>(e.getValue());
                    added.removeAll(was);
                    pages.put(KgJson.obj("doc", e.getKey(), "left", new JSONArray(gone), "joined", new JSONArray(added)));
                }
            }
        }
        final List<String> merges = new ArrayList<>(after.merges);
        for (final String m : before.merges) {
            merges.remove(m);
        }
        final List<String> conflicts = new ArrayList<>(after.conflicts);
        for (final String c : before.conflicts) {
            conflicts.remove(c);
        }
        return KgJson.obj("domain", domain, "identity", KgJson.obj("productUrl", productUrl, "providerServiceKey", providerServiceKey),
                "sample", KgJson.obj("documents", docs, "skipped", skipped, "truncated", truncated),
                "baseline", before.json(), "preview", after.json(), "documentsChanged", changed,
                "merges", new JSONArray(merges.subList(0, Math.min(SAMPLES, merges.size()))),
                "conflicts", new JSONArray(conflicts.subList(0, Math.min(SAMPLES, conflicts.size()))),
                "pages", pages, "llm", llm, "written", false);
    }

    private static void exec(final Connection tx, final String sql) throws SQLException {
        try (Statement st = tx.createStatement()) {
            st.execute(sql);
        }
    }
}
