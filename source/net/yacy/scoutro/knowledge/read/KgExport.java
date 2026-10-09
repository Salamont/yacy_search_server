/*
 *  KgExport
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

package net.yacy.scoutro.knowledge.read;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.store.KgChangeLog;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * Export and change feed of the read projection {@code scoutro.kg.v1}
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 8.3), for one viewer. The records are the
 * same JSON as the read routes ({@link KgReader}), so names, counts, quality
 * and the collections at evidence are computed over the viewer's evidence
 * only.
 * <p>
 * <b>Export</b>: keyset pages, first the visible entities, then the visible
 * statements (optionally with up to {@link #MAX_EVIDENCE} evidence entries
 * each), then (schema 4) the derived rows the viewer may see (both
 * collections), every page in its own read lease. The cursor
 * {@code <epoch>:<as_of_seq>:<e|s|d><rowid>} carries the change sequence at the
 * start of the export; the export is not a snapshot, so a consumer applies the
 * change feed from {@code next_changes} afterwards (upserts by ID are
 * idempotent, the delete of an unknown ID is a no-op).
 * <p>
 * <b>Changes</b>: {@link KgChangeLog#read} with the viewer, optionally with the
 * current record of each upsert.
 */
public final class KgExport {

    public static final int MAX_LIMIT = 200;
    public static final int MAX_CHANGES_LIMIT = 1000;
    /** Changes with records: at most this many per page. */
    public static final int MAX_EXPANDED_CHANGES = 100;
    /** Evidence entries per statement in an export with evidence. */
    public static final int MAX_EVIDENCE = 20;

    private static final Pattern CURSOR = Pattern.compile("^([0-9a-f]{16}):([0-9]{1,18}):([esd])([0-9]{1,18})$");

    private final KgReader reader;

    public KgExport(final KgReader reader) {
        this.reader = reader;
    }

    /** Receives the export stream of {@link #stream}. */
    public interface Sink {
        /** Called once, after the first page was read (errors before it reach the caller as exceptions). */
        void begin(JSONObject header) throws IOException;

        void record(JSONObject record) throws IOException;

        void end(JSONObject trailer) throws IOException;
    }

    // ---------------------------------------------------------------- export

    /** Where an export continues. */
    static final class Position {
        final String epoch;
        final long asOf;
        /** 'e' entities, 's' statements, 'd' derived rows. */
        final char phase;
        final long after;

        Position(final String epoch, final long asOf, final char phase, final long after) {
            this.epoch = epoch;
            this.asOf = asOf;
            this.phase = phase;
            this.after = after;
        }

        String cursor() {
            return this.epoch + ":" + this.asOf + ":" + this.phase + this.after;
        }
    }

    /**
     * One export page.
     *
     * @param cursor   null to start a new export
     * @param evidence also the visible evidence of each statement (at most {@link #MAX_EVIDENCE})
     */
    public JSONObject page(final String cursor, final int limit, final boolean evidence, final Viewer v) throws KgException {
        final long now = this.reader.now();
        return this.reader.store().read(c -> {
            final Position start = position(c, cursor);
            final List<JSONObject> records = new ArrayList<>();
            final Position next = fill(c, start, Math.max(1, Math.min(MAX_LIMIT, limit)), evidence, v, now, records);
            final JSONObject o = KgJson.obj("schema", KgReader.SCHEMA, "epoch", start.epoch, "as_of_seq", start.asOf,
                    "items", new JSONArray(records), "complete", next == null, "next", next == null ? null : next.cursor(),
                    "next_changes", KgChangeLog.cursor(start.epoch, start.asOf));
            return o;
        });
    }

    /**
     * The whole export as one stream: header, entities, statements, trailer.
     * Pages of {@code pageSize} records, each in its own read lease, so the
     * WAL can checkpoint in between. A failure after the header ends the
     * stream with {@code complete:false}; a failure before it is thrown.
     */
    public void stream(final Viewer v, final boolean evidence, final String collection, final int pageSize, final Sink sink)
            throws KgException, IOException {
        String cursor = null;
        long entities = 0;
        long statements = 0;
        long evidenceCount = 0;
        long derivedCount = 0;
        boolean begun = false;
        try {
            while (true) {
                final JSONObject page = page(cursor, pageSize, evidence, v);
                if (!begun) {
                    sink.begin(KgJson.obj("record", "header", "schema", KgReader.SCHEMA, "epoch", page.optString("epoch"),
                            "as_of_seq", page.optLong("as_of_seq"), "next_changes", page.optString("next_changes"),
                            "generated_at", KgReader.iso(this.reader.now()), "collection", collection, "evidence", evidence));
                    begun = true;
                }
                final JSONArray items = page.optJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    final JSONObject r = items.optJSONObject(i);
                    if ("entity".equals(r.optString("record"))) {
                        entities++;
                    } else if ("derived".equals(r.optString("record"))) {
                        derivedCount++;
                    } else {
                        statements++;
                        final JSONArray ev = r.optJSONArray("evidence");
                        evidenceCount += ev == null ? 0 : ev.length();
                    }
                    sink.record(r);
                }
                if (page.optBoolean("complete")) {
                    break;
                }
                cursor = page.optString("next");
            }
        } catch (final KgException e) {
            if (!begun) {
                throw e;
            }
            sink.end(trailer(entities, statements, evidenceCount, derivedCount, false, e.code()));
            return;
        }
        sink.end(trailer(entities, statements, evidenceCount, derivedCount, true, null));
    }

    private static JSONObject trailer(final long entities, final long statements, final long evidence, final long derived,
            final boolean complete, final String error) {
        return KgJson.obj("record", "trailer", "counts", KgJson.obj("entities", entities, "statements", statements, "evidence", evidence,
                "derived", derived), "complete", complete, "error", error);
    }

    /** The position of a cursor, or of a new export; refuses a cursor of another epoch or older than the retained changes. */
    private static Position position(final Connection c, final String cursor) throws SQLException, KgException {
        final String epoch = KgStore.getMeta(c, KgSchema.META_EPOCH);
        final long maxSeq = maxSeq(c);
        if (cursor == null) {
            return new Position(epoch, maxSeq, 'e', 0L);
        }
        final Matcher m = CURSOR.matcher(cursor);
        if (!m.matches()) {
            throw new KgException(KgException.INVALID_CURSOR, "export cursor must look like <epoch>:<seq>:<e|s|d><rowid>");
        }
        if (!m.group(1).equals(epoch)) {
            throw new KgException(KgException.EPOCH_CHANGED, "the dataset was reset; start the export again");
        }
        final long asOf = Long.parseLong(m.group(2));
        final long minSeq = Long.parseLong(KgStore.getMeta(c, KgSchema.META_CHANGES_MIN_SEQ));
        if (asOf > maxSeq) {
            throw new KgException(KgException.INVALID_CURSOR, "the export cursor is ahead of the change feed");
        }
        if (asOf < minSeq - 1L) {
            // the changes since the start of this export were partly removed: its result could not be completed
            throw new KgException(KgException.CURSOR_EXPIRED, "changes since the start of this export were removed by retention; start again");
        }
        return new Position(epoch, asOf, m.group(3).charAt(0), Long.parseLong(m.group(4)));
    }

    /** Fills up to {@code limit} records from {@code pos}; returns the next position or null at the end. */
    private Position fill(final Connection c, final Position pos, final int limit, final boolean evidence, final Viewer v, final long now,
            final List<JSONObject> out) throws SQLException {
        Position p = pos;
        if (p.phase == 'e') {
            final List<Long> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT e.ent_rowid FROM kg_entity e WHERE e.ent_rowid > ? AND "
                    + KgReader.visibleEntity(v, "e") + " ORDER BY e.ent_rowid LIMIT ?")) {
                ps.setLong(1, p.after);
                ps.setInt(2, limit + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(rs.getLong(1));
                    }
                }
            }
            for (int i = 0; i < Math.min(limit, rows.size()); i++) {
                final JSONObject e = this.reader.summary(c, rows.get(i), v, now, true);
                final JSONObject r = KgJson.obj("record", "entity");
                for (final String k : e.keySet()) {
                    KgJson.put(r, k, e.opt(k));
                }
                out.add(r);
            }
            if (rows.size() > limit) {
                return new Position(p.epoch, p.asOf, 'e', rows.get(limit - 1));
            }
            p = new Position(p.epoch, p.asOf, 's', 0L);
            if (out.size() >= limit) {
                return p;
            }
        }
        if (p.phase == 'd') {
            return derived(c, p, limit - out.size(), v, out);
        }
        final int room = limit - out.size();
        final List<KgReader.Stat> stats = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + KgReader.STMT_COLUMNS + " FROM kg_statement s JOIN kg_vocab v"
                + " ON v.term_id = s.pred WHERE s.stmt_rowid > ? AND " + KgReader.visibleStatement(v, "s") + " ORDER BY s.stmt_rowid LIMIT ?")) {
            ps.setLong(1, p.after);
            ps.setInt(2, room + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stats.add(KgReader.stat(rs));
                }
            }
        }
        final boolean more = stats.size() > room;
        final List<KgReader.Stat> page = more ? stats.subList(0, room) : stats;
        this.reader.compute(c, page, v, now);
        for (final KgReader.Stat s : page) {
            final JSONObject st = this.reader.statementJson(c, s, v);
            final JSONObject r = KgJson.obj("record", "statement");
            for (final String k : st.keySet()) {
                KgJson.put(r, k, st.opt(k));
            }
            if (evidence) {
                KgJson.put(r, "evidence", evidence(c, s.rowid, v));
            }
            out.add(r);
        }
        if (more) {
            return new Position(p.epoch, p.asOf, 's', page.get(page.size() - 1).rowid);
        }
        final Position d = new Position(p.epoch, p.asOf, 'd', 0L);
        return out.size() >= limit ? d : derived(c, d, limit - out.size(), v, out);
    }

    /** The visible derived rows from {@code p} (record {@code derived}); null at the end. */
    private static Position derived(final Connection c, final Position p, final int room, final Viewer v, final List<JSONObject> out)
            throws SQLException {
        final List<long[]> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT d.der_rowid FROM kg_derived d WHERE d.der_rowid > ? AND "
                + BusinessView.visibleDerived(v, "d") + " ORDER BY d.der_rowid LIMIT ?")) {
            ps.setLong(1, p.after);
            ps.setInt(2, room + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new long[] {rs.getLong(1)});
                }
            }
        }
        for (int i = 0; i < Math.min(room, rows.size()); i++) {
            final String id = KgStore.queryString(c, "SELECT public_id FROM kg_derived WHERE der_rowid = " + rows.get(i)[0]);
            final JSONObject d = BusinessGraph.derivedRecord(c, id, v);
            if (d != null) {
                final JSONObject r = KgJson.obj("record", "derived");
                for (final String k : d.keySet()) {
                    KgJson.put(r, k, d.opt(k));
                }
                out.add(r);
            }
        }
        return rows.size() > room ? new Position(p.epoch, p.asOf, 'd', rows.get(room - 1)[0]) : null;
    }

    /** The newest {@link #MAX_EVIDENCE} visible evidence entries of a statement. */
    private JSONArray evidence(final Connection c, final long stmt, final Viewer v) throws SQLException {
        final JSONArray items = new JSONArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT d.doc_rowid, d.doc_id, d.url, d.state, d.loaded_at, e.observed_at, e.kind,"
                + " e.tier, e.certainty, e.locator, e.excerpt, x.name, x.version, x.model, x.prompt_hash FROM kg_evidence e"
                + " JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_extractor x ON x.ext_id = e.ext_id WHERE e.stmt_rowid = ? AND "
                + KgReader.visibleDoc(v, "d") + " ORDER BY e.observed_at DESC, d.doc_id LIMIT " + MAX_EVIDENCE)) {
            ps.setLong(1, stmt);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.put(this.reader.evidenceJson(c, rs, v));
                }
            }
        }
        return items;
    }

    // --------------------------------------------------------------- changes

    /**
     * The changes after {@code cursor} as the viewer must see them; with
     * {@code expand}, every upsert carries its current record (null if the
     * object vanished since).
     */
    public JSONObject changes(final String cursor, final int limit, final boolean expand, final Viewer v) throws KgException {
        final long now = this.reader.now();
        return this.reader.store().read(c -> {
            final KgChangeLog.Page page = KgChangeLog.read(c, cursor, v, limit);
            final JSONArray items = new JSONArray();
            for (final KgChangeLog.Item it : page.items) {
                final JSONObject o = KgJson.obj("seq", it.seq, "kind", it.kind.label(),
                        "id", it.id, "op", it.op.name().toLowerCase(java.util.Locale.ROOT), "redirect_to", it.redirectTo,
                        "at", KgReader.iso(it.at));
                if (expand && it.op == KgChangeLog.Op.UPSERT) {
                    KgJson.put(o, "record", record(c, it, v, now));
                }
                items.put(o);
            }
            return KgJson.obj("schema", KgReader.SCHEMA, "items", items, "next", page.next, "has_more", page.hasMore,
                    "as_of", KgJson.obj("epoch", KgStore.getMeta(c, KgSchema.META_EPOCH), "seq", maxSeq(c)));
        });
    }

    /** The current record of a changed object for the viewer; null if it is not visible (any more). */
    private JSONObject record(final Connection c, final KgChangeLog.Item it, final Viewer v, final long now) throws SQLException {
        if(it.kind==KgChangeLog.Kind.OBSERVATION)return ObservationHistory.record(c,it.id,v);
        if (it.kind == KgChangeLog.Kind.DERIVED) {
            return BusinessGraph.derivedRecord(c, it.id, v);
        }
        if (it.kind == KgChangeLog.Kind.ENTITY) {
            final long[] ent = KgReader.entityRow(c, it.id, v);
            if (ent == null || ent[1] != 0L) {
                return null;
            }
            return this.reader.summary(c, ent[0], v, now, true);
        }
        final KgReader.Stat s = KgReader.statementRow(c, it.id, v);
        if (s == null || s.publicId == null) {
            return null;
        }
        this.reader.compute(c, List.of(s), v, now);
        return this.reader.statementJson(c, s, v);
    }

    private static long maxSeq(final Connection c) throws SQLException {
        final long live = KgStore.queryLong(c, "SELECT coalesce(max(seq), 0) FROM kg_change");
        final long used = KgStore.queryLong(c, "SELECT coalesce((SELECT seq FROM sqlite_sequence WHERE name = 'kg_change'), 0)");
        return Math.max(live, used);
    }
}
