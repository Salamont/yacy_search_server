/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.read;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.yacy.scoutro.knowledge.extract.Audiences;

/**
 * 0.13.0: the declared audiences that still take effect (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 39): a statement of a target category,
 * a target industry, an audience segment, a customer type or a company size counts at derivation and reading only if one of its
 * pieces of evidence on a page that is still there (active or unavailable) is confirmed by the current reader
 * ({@link Audiences#confirms}): structured data always, the rule tier's excerpt with its marker, the LLM tier's quote without.
 * Evidence that fails stays stored as history and has no effect. Nothing is written.
 */
public final class AudienceEvidence {

    private AudienceEvidence() {}

    /** Of the statements given by row, the rows whose audience is still confirmed; a statement of another predicate always is. */
    public static Set<Long> confirmed(final Connection c, final Collection<Long> rows) throws SQLException {
        final Set<Long> out = new HashSet<>();
        if (rows.isEmpty()) {
            return out;
        }
        final Map<String, Boolean> memo = new HashMap<>();
        final List<Long> all = new ArrayList<>(rows);
        for (int from = 0; from < all.size(); from += 500) {
            final StringBuilder in = new StringBuilder();
            for (final Long r : all.subList(from, Math.min(all.size(), from + 500))) {
                in.append(in.length() == 0 ? "" : ",").append(r.longValue());
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT s.stmt_rowid, v.name, s.obj_val, e.tier, e.excerpt FROM kg_statement s"
                    + " JOIN kg_vocab v ON v.term_id = s.pred LEFT JOIN kg_evidence e ON e.stmt_rowid = s.stmt_rowid"
                    + " AND EXISTS (SELECT 1 FROM kg_doc d WHERE d.doc_rowid = e.doc_rowid AND d.state IN (1, 2))"
                    + " WHERE s.stmt_rowid IN (" + in + ")"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final long row = rs.getLong(1);
                    if (out.contains(row)) {
                        continue;
                    }
                    final String predicate = rs.getString(2);
                    if (!Audiences.audiencePredicate(predicate)) {
                        out.add(row);
                        continue;
                    }
                    if (rs.getObject(4) == null) {
                        continue; // no evidence on a page that is still there
                    }
                    final int tier = rs.getInt(4);
                    final String value = rs.getString(3), excerpt = rs.getString(5);
                    // a footer or a voice on seven pages is read once (memo by its excerpt)
                    final Boolean ok = memo.computeIfAbsent(tier + "\u0000" + predicate + "\u0000" + value + "\u0000" + excerpt,
                            k -> Audiences.confirms(predicate, value, excerpt, tier));
                    if (ok) {
                        out.add(row);
                    }
                }
            }
        }
        return out;
    }
}
