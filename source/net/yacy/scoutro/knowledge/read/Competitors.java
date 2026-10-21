/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.read;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.vocab.Categories;

/**
 * Possible competitors of an organisation (0.12.0, B1), read on demand and never part of the business view's first read:
 * per category the organisation offers itself, the other organisations' offers of a service of that category, one line per
 * offer (a provider with two services of the category has two lines), each with its own service and the source pages the
 * viewer sees. Derived at reading, never a fact, a customer relation or a matching input; a topic of a website is no offer and
 * makes nobody a competitor. Not part of the export or the change feed.
 * <p>
 * The services of a category are read through the partial index of the category statements ({@code (obj_val, subj)},
 * {@link KgSchema#ensureCategoryIndex}) in the order of their row, a page after the cursor, so a page costs the same however many
 * providers a category has; at most {@link #MAX_SCAN} services are looked at for one page.
 */
public final class Competitors {

    static final String INDEX = KgSchema.CATEGORY_INDEX;
    public static final int MAX_LIMIT = 100;
    static final int MAX_SCAN = 2000;

    private final KgReader reader;

    public Competitors(final KgReader reader) {
        this.reader = reader;
    }

    static Long term(final Connection c, final String predicate) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT term_id FROM kg_vocab WHERE kind = 2 AND name = ?")) {
            ps.setString(1, predicate);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? r.getLong(1) : null;
            }
        }
    }

    private static boolean indexed(final Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?")) {
            ps.setString(1, INDEX);
            try (ResultSet r = ps.executeQuery()) {
                return r.next();
            }
        }
    }

    /** The categories the entity offers itself: the categories of the services of its visible, current offers. */
    static Set<String> offered(final Connection c, final long ent, final Viewer v) throws SQLException {
        final Set<String> out = new LinkedHashSet<>();
        final Long offers = term(c, Vocabulary.OFFERS), category = term(c, Vocabulary.CATEGORY);
        if (offers == null || category == null) {
            return out;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT k.obj_val FROM kg_statement o JOIN kg_statement k ON k.subj = o.obj_ent"
                + " AND k.pred = " + category + " WHERE o.subj = ? AND o.pred = " + offers + " AND o.quality <> 4 AND k.quality <> 4 AND "
                + KgReader.visibleStatement(v, "o") + " AND " + KgReader.visibleStatement(v, "k") + " ORDER BY 1 LIMIT 100")) {
            ps.setLong(1, ent);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    out.add(r.getString(1));
                }
            }
        }
        return out;
    }

    /** The categories the entity offers, with their labels, as the business view names them (no other read). */
    static JSONArray categories(final Set<String> codes, final Categories cats) {
        final JSONArray out = new JSONArray();
        for (final String code : codes) {
            final Categories.Entry e = cats.code(Vocabulary.CATEGORY, code);
            out.put(KgJson.obj("value", code, "label_de", e == null ? null : e.de, "label_en", e == null ? null : e.en));
        }
        return out;
    }

    /**
     * One page of the possible competitors in one category the entity offers itself: the offers of other organisations of a
     * service of that category, after {@code after} (the cursor of the previous page, null for the first).
     */
    public JSONObject page(final String id, final String category, final String after, final int limit, final Viewer selected, final Viewer permitted)
            throws KgException, KgReader.NotFound {
        final Viewer v = KgReader.within(selected, permitted);
        final Object r = this.reader.store().read(c -> {
            final long[] ent = KgReader.entityRow(c, id, v);
            if (ent == null || ent[1] != 0L || !offered(c, ent[0], v).contains(category)) {
                return null;
            }
            return page(c, ent[0], category, after == null ? 0L : Long.parseLong(after), Math.max(1, Math.min(MAX_LIMIT, limit)), v);
        });
        if (r == null) {
            throw new KgReader.NotFound("competitors of " + id + " in " + category);
        }
        return (JSONObject) r;
    }

    private static JSONObject page(final Connection c, final long ent, final String category, final long after, final int limit, final Viewer v)
            throws SQLException {
        final long offers = term(c, Vocabulary.OFFERS), cat = term(c, Vocabulary.CATEGORY);
        final String index = indexed(c) ? " INDEXED BY " + INDEX : "";
        final JSONArray items = new JSONArray();
        long cursor = after;
        int scanned = 0;
        boolean more = true;
        while (items.length() < limit && scanned < MAX_SCAN && more) {
            final List<Long> services = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT k.subj FROM kg_statement k" + index + " WHERE k.pred = " + cat
                    + " AND k.obj_val = ? AND k.subj > ? AND k.quality <> 4 AND " + KgReader.visibleStatement(v, "k") + " ORDER BY k.subj LIMIT ?")) {
                ps.setString(1, category);
                ps.setLong(2, cursor);
                ps.setInt(3, 4 * limit);
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        services.add(r.getLong(1));
                    }
                }
            }
            more = services.size() == 4 * limit;
            for (final long service : services) {
                scanned++;
                cursor = service;
                try (PreparedStatement ps = c.prepareStatement("SELECT o.stmt_rowid, o.public_id, o.subj FROM kg_statement o WHERE o.obj_ent = ?"
                        + " AND o.pred = " + offers + " AND o.subj <> ? AND o.quality <> 4 AND " + KgReader.visibleStatement(v, "o")
                        + " ORDER BY o.stmt_rowid")) {
                    ps.setLong(1, service);
                    ps.setLong(2, ent);
                    try (ResultSet r = ps.executeQuery()) {
                        while (r.next()) {
                            final long provider = r.getLong(3);
                            if (!BusinessView.visible(c, provider, v)) {
                                continue;
                            }
                            items.put(KgJson.obj("other", BusinessView.entityRef(c, provider, v), "offer", r.getString(2), "service",
                                    KgJson.obj("id", KgReader.publicId(c, service), "name", KgReader.visibleName(c, service, v)),
                                    "source_docs", sources(c, r.getLong(1), v), "label", "suggestion"));
                        }
                    }
                }
                if (items.length() >= limit) {
                    more = true;
                    break;
                }
            }
        }
        return KgJson.obj("schema", BusinessView.SCHEMA, "category", category, "items", items, "limit", limit, "fact", false,
                "basis", "offers_of_the_same_category", "next", more ? Long.toString(cursor) : null);
    }

    /** At most 3 source pages of an offer the viewer sees. */
    private static JSONArray sources(final Connection c, final long stmt, final Viewer v) throws SQLException {
        final JSONArray out = new JSONArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT d.doc_id, d.url FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
                + " WHERE e.stmt_rowid = ? AND " + KgReader.visibleDoc(v, "d") + " ORDER BY d.doc_id LIMIT 3")) {
            ps.setLong(1, stmt);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    out.put(KgJson.obj("doc_id", r.getString(1), "url", r.getString(2)));
                }
            }
        }
        return out;
    }

}
