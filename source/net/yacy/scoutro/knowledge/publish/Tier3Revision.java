/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.publish;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.vocab.Categories;

/**
 * WP-D: after a collection move the LLM tier is not asked again, but some of its facts were read through the old
 * collection's vocabulary: categories, the industries and industry groups derived from them, and audience segments
 * (LlmExtractor reads them from the quote with the page's vocabularies). This checks each such evidence row of the
 * page against the vocabularies of its new collections and withdraws the rows whose value they do not contain. A
 * category of the care vocabulary on a page that moved to a collection without that vocabulary (for instance the
 * fallback collection without any) is no fact of the page any more; names, offers, relations, prices and every other
 * LLM fact stay. Deleting evidence runs the observation archive's triggers: the history keeps the old assertion.
 */
public final class Tier3Revision {

    /** The LLM tier's predicates whose values come from the page's vocabularies. */
    static final List<String> VOCABULARY_PREDICATES = List.of(Vocabulary.CATEGORY, Vocabulary.INDUSTRY, Vocabulary.INDUSTRY_CATEGORY,
            Vocabulary.AUDIENCE_SEGMENT);

    private Tier3Revision() {
    }

    /** Withdraws the unsupported tier-3 evidence of {@code docRowid}; returns how many rows. */
    static int revise(final Connection tx, final long docRowid, final ExtractContext ctx) throws SQLException {
        final List<long[]> withdraw = new ArrayList<>();
        final StringBuilder in = new StringBuilder();
        for (final String p : VOCABULARY_PREDICATES) in.append(in.length() == 0 ? "" : ",").append('\'').append(p).append('\'');
        try (PreparedStatement ps = tx.prepareStatement("SELECT v.stmt_rowid, v.locator, t.name, s.obj_val FROM kg_evidence v"
                + " JOIN kg_statement s ON s.stmt_rowid = v.stmt_rowid JOIN kg_vocab t ON t.term_id = s.pred"
                + " WHERE v.doc_rowid = ? AND v.tier = 3 AND t.kind = 2 AND t.name IN (" + in + ")")) {
            ps.setLong(1, docRowid);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    if (!supported(ctx, r.getString(3), r.getString(4), r.getString(2))) withdraw.add(new long[] {r.getLong(1)});
                }
            }
        }
        if (withdraw.isEmpty()) return 0;
        try (PreparedStatement del = tx.prepareStatement("DELETE FROM kg_evidence WHERE stmt_rowid = ? AND doc_rowid = ? AND tier = 3")) {
            for (final long[] w : withdraw) {
                del.setLong(1, w[0]);
                del.setLong(2, docRowid);
                del.addBatch();
            }
            del.executeBatch();
        }
        return withdraw.size();
    }

    /** True if one of the context's vocabularies contains the value (for a derived industry: its source category). */
    static boolean supported(final ExtractContext ctx, final String predicate, final String value, final String locator) {
        if (value == null) return true;
        if (locator != null && locator.startsWith("derived:category:")) {
            return category(ctx, locator.substring("derived:category:".length())) != null;
        }
        switch (predicate) {
            case Vocabulary.CATEGORY:
                return category(ctx, value) != null;
            case Vocabulary.INDUSTRY_CATEGORY:
                for (final Categories.Vocab v : ctx.vocabularies) if (v.groups.containsKey(value)) return true;
                return false;
            case Vocabulary.INDUSTRY:
                for (final Categories.Vocab v : ctx.vocabularies) {
                    for (final Categories.Entry e : v.categories.values()) if (value.equals(e.nace)) return true;
                }
                return false;
            case Vocabulary.AUDIENCE_SEGMENT:
                for (final Categories.Vocab v : ctx.vocabularies) if (v.segments.containsKey(value)) return true;
                return false;
            default:
                return true;
        }
    }

    private static Categories.Entry category(final ExtractContext ctx, final String id) {
        for (final Categories.Vocab v : ctx.vocabularies) {
            final Categories.Entry e = v.categories.get(id);
            if (e != null) return e;
        }
        return null;
    }
}
