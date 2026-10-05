/*
 *  Reconciler
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgIds;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * Full reconcile and initial backfill (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 5.3).
 * <p>
 * A run merge-joins the committed Solr documents of the followed collections
 * with {@code kg_doc}, both in ascending byte order of the ID, one bounded page
 * per step:
 * <ul>
 * <li>missing in the graph (an active document), a newer {@code _version_} or
 * another token: enqueued;</li>
 * <li>missing in Solr: only a deletion <em>candidate</em> in
 * {@code kg_scan_candidate}.</li>
 * </ul>
 * Deletions happen only after the scan has seen every page (phase scan →
 * verify → delete), only for candidates a real-time get confirms as absent or
 * out of scope twice (when verified and right before the delete), only while
 * the document's generation is unchanged, and only below the mass-deletion
 * brake. A page that fails, is partial or breaks the order aborts the run:
 * nothing is deleted, and the run resumes from its cursor. A restart or a
 * reactivation always starts a new run (Solr may have changed while the graph
 * was down); an interrupted backfill resumes and is followed by a reconcile.
 */
public final class Reconciler {

    public static final int KIND_RECONCILE = 1;
    public static final int KIND_BACKFILL = 2;
    public static final int STATE_RUNNING = 1;
    public static final int STATE_COMPLETED = 2;
    public static final int STATE_ABORTED = 3;
    public static final int STATE_SUSPECT = 4;
    public static final int PHASE_SCAN = 1;
    public static final int PHASE_VERIFY = 2;
    public static final int PHASE_DELETE = 3;
    public static final int PHASE_DONE = 4;
    static final int VERDICT_UNVERIFIED = 0;
    static final int VERDICT_ABSENT = 1;
    static final int VERDICT_PRESENT = 2;

    public static final String REASON_START = "start";
    public static final String REASON_UNCLEAN_START = "unclean_start";
    public static final String REASON_COLLECTIONS = "collections_changed";
    public static final String REASON_EXTRACTORS = "extractor_changed";
    public static final String REASON_QUERY_DELETE = "query_delete";
    public static final String REASON_OVERFLOW = "overflow";
    public static final String REASON_QUEUE_FULL = "queue_full";
    public static final String REASON_DAILY = "daily";
    public static final String REASON_ADMIN = "admin";
    public static final String REASON_RESUME = "resume";
    public static final String REASON_FULL_RESET = "full_reset";
    /** Changes were lost (full queue, overflowing change set) and a scan could not see them yet. */
    public static final String REASON_LOST_CHANGES = "lost_changes";
    /**
     * When a lost change is surely visible to a scan: a real-time get sees a
     * document at once, a search only once a new searcher opens, at the latest
     * with Solr's {@code autoCommit} (180 s, {@code defaults/solr/solrconfig.xml}).
     */
    static final long VISIBLE_AFTER_MILLIS = 200_000L;

    static final int PAGE = 1000;
    static final int DELETE_BATCH = 200;
    static final int KEEP_RUNS = 20;
    private static final long ESTIMATE = 2L * 1024L * 1024L;
    private static final long RETRY_MIN = 60_000L;
    private static final long RETRY_MAX = 30L * 60_000L;
    private static final long QUEUE_FULL_RETRY = 60L * 60_000L;
    private static final List<String> VERIFY_FIELDS = List.of(SolrDoc.ID, SolrDoc.VERSION, SolrDoc.COLLECTIONS);
    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-KG");

    /** A run as stored in {@code kg_scan}. */
    static final class Run {
        long id;
        int kind;
        int state;
        int phase;
        String cursor;
        long startedAt;
        String reason;
        long scanned;
        long enqueued;
        long candidates;
        long confirmed;
        long deleted;
        long solrSeen;
        long tracked;
        long dropped;
        String detail;

        JSONObject json() {
            return KgJson.obj("id", this.id, "kind", this.kind == KIND_BACKFILL ? "backfill" : "reconcile",
                    "state", stateName(this.state), "phase", phaseName(this.phase), "reason", this.reason,
                    "startedAt", this.startedAt, "cursor", this.cursor, "scanned", this.scanned, "enqueued", this.enqueued,
                    "deleteCandidates", this.candidates, "confirmedAbsent", this.confirmed, "deleted", this.deleted,
                    "solrSeen", this.solrSeen, "tracked", this.tracked, "enqueueDropped", this.dropped, "detail", this.detail);
        }
    }

    private final KgConfig cfg;
    private final KgStore store;
    private final SolrSource solr;
    private final DirtySet dirty;
    private final Publisher publisher;
    private final Gates gates;
    private final LongSupplier clock;

    // the pending request (in memory: every start requests a run anyway); guarded by lock
    private final Object lock = new Object();
    private boolean pending;
    /** A run that starts before this time cannot see changes that were lost (guarded by {@code lock}). */
    private long rescanAfter;
    private String pendingReason;
    private long requestedAt;
    private long dueAt;
    private boolean debounced;
    private boolean reextract;
    private long dailyRequestedFor;

    // run state: changed only by the sync thread, read by the status
    private volatile Run run;
    private volatile long retryAt;
    private int failures;
    private volatile long lastCompletedAt;
    private volatile JSONObject lastRun;
    private volatile boolean confirmRequested;

    // _version_ catch-up after an unclean stop (optimisation only)
    private volatile boolean catchUp;
    private volatile long catchUpVersion;
    private String catchUpCursor;
    private volatile long catchUpEnqueued;

    private volatile String gateClosed;
    /** Page size of scans (smaller in tests). */
    int page = PAGE;
    /** Queue cap ({@code queue.maxItems}; smaller in tests). */
    long queueMax;

    Reconciler(final KgConfig cfg, final KgStore store, final SolrSource solr, final DirtySet dirty, final Publisher publisher,
            final Gates gates, final LongSupplier clock) {
        this.cfg = cfg;
        this.store = store;
        this.solr = solr;
        this.dirty = dirty;
        this.publisher = publisher;
        this.gates = gates;
        this.clock = clock;
        this.queueMax = cfg.queueMaxItems;
    }

    // ------------------------------------------------------------- requests

    /** Requests a run, due at {@code due}. */
    void request(final String reason, final long due) {
        synchronized (this.lock) {
            final long now = this.clock.getAsLong();
            if (!this.pending) {
                this.pending = true;
                this.pendingReason = reason;
                this.dueAt = due;
            } else {
                this.dueAt = this.debounced ? due : Math.min(this.dueAt, due);
            }
            this.debounced = false;
            this.requestedAt = now;
        }
    }

    /**
     * Changes were lost at {@code now} ({@code reason}: the work queue was
     * full or the change set overflowed): a run is requested now, and a run
     * that started before the lost documents are visible to a scan does not
     * count; another one follows ({@link #VISIBLE_AFTER_MILLIS}).
     */
    void lost(final String reason, final long now) {
        synchronized (this.lock) {
            this.rescanAfter = Math.max(this.rescanAfter, now + VISIBLE_AFTER_MILLIS);
        }
        request(reason, now);
    }

    /** True while a run waits because the work queue was full; {@link #queueHasRoom} brings it forward. */
    boolean waitsForQueueRoom() {
        synchronized (this.lock) {
            return this.pending && REASON_QUEUE_FULL.equals(this.pendingReason) && this.run == null
                    && this.dueAt > this.clock.getAsLong();
        }
    }

    /** The work queue has room again: a run that the full queue postponed is due now, not in an hour. */
    void queueHasRoom(final long now) {
        synchronized (this.lock) {
            if (this.pending && REASON_QUEUE_FULL.equals(this.pendingReason) && this.dueAt > now) {
                this.dueAt = now;
            }
        }
    }

    /** Requests a run after a quiet period; every further request of this kind moves it back (delete by query). */
    void requestDebounced(final String reason, final long due) {
        synchronized (this.lock) {
            final long now = this.clock.getAsLong();
            if (!this.pending) {
                this.pending = true;
                this.pendingReason = reason;
                this.dueAt = due;
                this.debounced = true;
            } else if (this.debounced) {
                this.dueAt = Math.max(this.dueAt, due);
            }
            this.requestedAt = now;
        }
    }

    void reextractAll() {
        synchronized (this.lock) {
            this.reextract = true;
        }
    }

    /** True while a run is requested, running, waiting or catching up. */
    boolean pending() {
        synchronized (this.lock) {
            return this.pending || this.run != null || this.catchUp;
        }
    }

    /** Enqueues every document with {@code _version_ >= version} before the full run (after an unclean stop). */
    void catchUp(final long version) {
        this.catchUpCursor = null;
        this.catchUpEnqueued = 0L;
        this.catchUpVersion = version;
        this.catchUp = version > 0L;
    }

    /** Forgets the current run (a full reset aborted it). */
    void forgetRun() {
        this.run = null;
        this.catchUp = false;
        this.confirmRequested = false;
    }

    /**
     * At start, inside the caller's transaction: runs of an earlier session
     * end (a reconcile is always started anew), a backfill resumes.
     */
    void onStart(final Connection tx, final long now) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_scan_candidate WHERE run_id IN"
                + " (SELECT run_id FROM kg_scan WHERE kind = 1 AND state IN (1, 3, 4))")) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_scan SET state = 3, finished_at = ?, detail = 'restart'"
                + " WHERE kind = 1 AND state IN (1, 3, 4)")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        }
        final String last = KgStore.getMeta(tx, KgSchema.META_RECONCILE_LAST_COMPLETED);
        this.lastCompletedAt = last == null ? 0L : Long.parseLong(last);
        final Run backfill = load(tx, "SELECT " + RUN_COLUMNS + " FROM kg_scan WHERE kind = 2 AND state IN (1, 3, 4)"
                + " ORDER BY run_id DESC LIMIT 1");
        if (backfill != null) {
            setState(tx, backfill, STATE_RUNNING, null, now);
            this.retryAt = 0L;
            this.run = backfill;
        }
    }

    /**
     * Confirms a run the mass-deletion brake stopped; the sync thread
     * continues it with the delete phase at its next step.
     */
    void confirm() throws KgException {
        final Run r = this.run;
        if (r == null || r.state != STATE_SUSPECT) {
            throw new KgException(KgException.NOTHING_TO_CONFIRM, "no reconcile is waiting for confirmation");
        }
        this.confirmRequested = true;
    }

    // ----------------------------------------------------------------- step

    /**
     * One bounded unit of work (one page, one verify or delete batch).
     *
     * @return true if more work is due right away
     */
    boolean step() throws KgException {
        final long now = this.clock.getAsLong();
        if (this.run == null) {
            if (this.catchUp) {
                if (gate()) {
                    return false;
                }
                catchUpStep(now);
                return true;
            }
            daily(now);
            final boolean due;
            synchronized (this.lock) {
                due = this.pending && now >= this.dueAt;
            }
            if (!due || gate()) {
                return false;
            }
            create(now);
            return true;
        }
        final Run r = this.run;
        if (r.state == STATE_SUSPECT) {
            if (!this.confirmRequested) {
                return false;
            }
            this.confirmRequested = false;
            this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
                setState(tx, r, STATE_RUNNING, null, now);
                setPhase(tx, r, PHASE_DELETE);
                KgStore.event(tx, 2, "reconcile_confirmed", "deletion of " + r.confirmed + " documents confirmed", now);
                return null;
            });
            return true;
        }
        if (r.state == STATE_ABORTED) {
            if (now < this.retryAt) {
                return false;
            }
            this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
                setState(tx, r, STATE_RUNNING, r.detail, now);
                return null;
            });
        }
        if (gate()) {
            return false;
        }
        switch (r.phase) {
            case PHASE_SCAN:
                scan(r, now);
                return true;
            case PHASE_VERIFY:
                verify(r, now);
                return true;
            case PHASE_DELETE:
                delete(r, now);
                return true;
            default:
                complete(r, now);
                return true;
        }
    }

    private boolean gate() {
        this.gateClosed = this.gates.closed(true);
        return this.gateClosed != null;
    }

    private void daily(final long now) {
        synchronized (this.lock) {
            if (this.pending) {
                return;
            }
        }
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, this.cfg.reconcileHour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        final long today = c.getTimeInMillis();
        if (now >= today && this.lastCompletedAt < today && this.dailyRequestedFor < today) {
            this.dailyRequestedFor = today;
            request(REASON_DAILY, now);
        }
    }

    private void create(final long now) throws KgException {
        final String reason;
        final boolean all;
        synchronized (this.lock) {
            reason = this.pendingReason;
            all = this.reextract;
        }
        final Run r = this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            final long tracked = KgStore.queryLong(tx, "SELECT count(*) FROM kg_doc");
            final Run n = new Run();
            n.kind = tracked == 0L ? KIND_BACKFILL : KIND_RECONCILE;
            n.state = STATE_RUNNING;
            n.phase = PHASE_SCAN;
            n.startedAt = now;
            n.reason = all ? REASON_EXTRACTORS : reason;
            try (PreparedStatement ps = tx.prepareStatement("INSERT INTO kg_scan (kind, state, started_at, phase, reason) VALUES (?, ?, ?, ?, ?)")) {
                ps.setInt(1, n.kind);
                ps.setInt(2, n.state);
                ps.setLong(3, n.startedAt);
                ps.setInt(4, n.phase);
                ps.setString(5, clip(n.reason, 64));
                ps.executeUpdate();
            }
            n.id = KgStore.queryLong(tx, "SELECT last_insert_rowid()");
            KgStore.putMeta(tx, KgSchema.META_COLLECTIONS, this.cfg.collectionsKey());
            KgStore.event(tx, 1, n.kind == KIND_BACKFILL ? "backfill_started" : "reconcile_started", n.reason, now);
            return n;
        });
        this.run = r;
        this.failures = 0;
    }

    // ----------------------------------------------------------------- scan

    /** Scan state of one tracked document. */
    private static final class Tracked {
        final long generation;
        final long version;
        final byte[] token;

        Tracked(final long generation, final long version, final byte[] token) {
            this.generation = generation;
            this.version = version;
            this.token = token;
        }
    }

    private void scan(final Run r, final long now) throws KgException {
        final SolrSource.Page page;
        try {
            page = this.solr.scan(r.cursor, this.page, this.cfg.allCollections ? null : this.cfg.collections, 0L);
        } catch (final IOException e) {
            abort(r, "solr_error: " + e.getMessage(), now);
            return;
        }
        if (page.partial) {
            abort(r, "partial_results", now);
            return;
        }
        String prev = r.cursor;
        for (final SolrDoc d : page.docs) {
            if (d.id == null || (prev != null && d.id.compareTo(prev) <= 0)) {
                abort(r, "order: " + d.id + " after " + prev, now);
                return;
            }
            prev = d.id;
        }
        final String from = r.cursor;
        final int limit = this.page;
        final LinkedHashMap<String, Tracked> graph = this.store.read(c -> tracked(c, from, limit));
        final boolean solrFull = page.docs.size() >= limit;
        final boolean graphFull = graph.size() >= limit;
        String upper = null;
        if (solrFull) {
            upper = page.docs.get(page.docs.size() - 1).id;
        }
        if (graphFull) {
            String lastGraph = null;
            for (final String id : graph.keySet()) {
                lastGraph = id;
            }
            upper = upper == null || lastGraph.compareTo(upper) < 0 ? lastGraph : upper;
        }
        if (upper != null && !KgIds.isDocId(upper)) {
            abort(r, "foreign_id: " + upper, now);
            return;
        }
        final List<String> enqueue = new ArrayList<>();
        final List<String> reextract = new ArrayList<>();
        final Map<String, Long> candidates = new LinkedHashMap<>();
        long seen = 0L;
        final java.util.Set<String> inSolr = new java.util.HashSet<>();
        for (final SolrDoc d : page.docs) {
            if (upper != null && d.id.compareTo(upper) > 0) {
                break;
            }
            seen++;
            inSolr.add(d.id);
            if (!KgIds.isDocId(d.id)) {
                continue;
            }
            final Tracked t = graph.get(d.id);
            if (t == null) {
                // never-active documents (fail documents of new URLs) are not tracked
                if (d.state() == net.yacy.scoutro.knowledge.publish.Aggregates.STATE_ACTIVE) {
                    enqueue.add(d.id);
                }
            } else if (d.version > t.version || !Arrays.equals(d.token(), t.token)) {
                enqueue.add(d.id);
            } else if (REASON_EXTRACTORS.equals(r.reason)) {
                reextract.add(d.id);
            }
        }
        for (final Map.Entry<String, Tracked> e : graph.entrySet()) {
            if (upper != null && e.getKey().compareTo(upper) > 0) {
                break;
            }
            if (!inSolr.contains(e.getKey())) {
                candidates.put(e.getKey(), e.getValue().generation);
            }
        }
        final boolean end = upper == null;
        final long scanned = seen;
        final String next = upper;
        final int reason = r.kind == KIND_BACKFILL ? WorkQueue.REASON_BACKFILL : WorkQueue.REASON_RECONCILE;
        final int prio = r.kind == KIND_BACKFILL ? WorkQueue.PRIO_BACKFILL : WorkQueue.PRIO_RECONCILE;
        this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            final WorkQueue.Added a = WorkQueue.scanned(tx, enqueue, reason, prio, 0L, now, this.queueMax);
            final WorkQueue.Added b = WorkQueue.scanned(tx, reextract, WorkQueue.REASON_RECONCILE, WorkQueue.PRIO_REEXTRACT, 0L, now,
                    this.queueMax);
            try (PreparedStatement ps = tx.prepareStatement("INSERT OR REPLACE INTO kg_scan_candidate (run_id, doc_id, verdict, generation)"
                    + " VALUES (?, ?, 0, ?)")) {
                for (final Map.Entry<String, Long> e : candidates.entrySet()) {
                    ps.setLong(1, r.id);
                    ps.setString(2, e.getKey());
                    ps.setLong(3, e.getValue());
                    ps.executeUpdate();
                }
            }
            r.scanned += scanned;
            r.solrSeen += scanned;
            r.enqueued += a.inserted + b.inserted;
            r.dropped += a.dropped + b.dropped;
            r.candidates += candidates.size();
            if (end) {
                r.tracked = KgStore.queryLong(tx, "SELECT count(*) FROM kg_doc");
                r.phase = PHASE_VERIFY;
            } else {
                r.cursor = next;
            }
            saveCounts(tx, r);
            return null;
        });
    }

    private static LinkedHashMap<String, Tracked> tracked(final Connection c, final String after, final int limit) throws SQLException {
        final LinkedHashMap<String, Tracked> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT doc_id, generation, solr_version, token FROM kg_doc"
                + (after == null ? "" : " WHERE doc_id > ?") + " ORDER BY doc_id LIMIT " + limit)) {
            if (after != null) {
                ps.setString(1, after);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), new Tracked(rs.getLong(2), rs.getLong(3), rs.getBytes(4)));
                }
            }
        }
        return out;
    }

    // --------------------------------------------------------------- verify

    private void verify(final Run r, final long now) throws KgException {
        final List<String> ids = this.store.read(c -> candidates(c, r.id, VERDICT_UNVERIFIED, SolrSource.MAX_GET)).idList();
        if (ids.isEmpty()) {
            brake(r, now);
            return;
        }
        final Map<String, SolrDoc> found;
        try {
            found = this.solr.get(ids, VERIFY_FIELDS);
        } catch (final IOException e) {
            abort(r, "solr_error: " + e.getMessage(), now);
            return;
        }
        final List<String> present = new ArrayList<>();
        final List<String> absent = new ArrayList<>();
        for (final String id : ids) {
            final SolrDoc d = found.get(id);
            if (d != null && !d.followed(this.cfg).isEmpty()) {
                present.add(id);
            } else {
                absent.add(id);
            }
        }
        this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            verdict(tx, r.id, present, VERDICT_PRESENT);
            verdict(tx, r.id, absent, VERDICT_ABSENT);
            // a candidate the scan missed (not yet committed) is processed like any other change
            final WorkQueue.Added a = WorkQueue.scanned(tx, present, WorkQueue.REASON_RECONCILE, WorkQueue.PRIO_VERIFIED, 0L, now,
                    this.queueMax);
            r.enqueued += a.inserted;
            r.dropped += a.dropped;
            r.confirmed += absent.size();
            saveCounts(tx, r);
            return null;
        });
    }

    /** The mass-deletion brake: too many confirmed absences, or an empty Solr against a non-empty graph. */
    private void brake(final Run r, final long now) throws KgException {
        final boolean emptySolr = r.solrSeen == 0L && r.tracked > 0L;
        final boolean tooMany = r.confirmed >= this.cfg.reconcileBrakeMinDocs
                && r.confirmed > this.cfg.reconcileMaxDeleteFraction * r.tracked;
        if (r.confirmed > 0L && (emptySolr || tooMany)) {
            final String detail = "brake: " + r.confirmed + " of " + r.tracked + " tracked documents absent"
                    + (emptySolr ? " and Solr returned no document" : "") + "; POST /kg/control {\"action\":\"confirm_reconcile\"}";
            this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
                setState(tx, r, STATE_SUSPECT, detail, now);
                KgStore.event(tx, 3, "reconcile_suspect", detail, now);
                return null;
            });
            LOG.warn("knowledge graph reconcile stopped before deleting: " + detail);
            return;
        }
        this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            setPhase(tx, r, r.confirmed > 0L ? PHASE_DELETE : PHASE_DONE);
            return null;
        });
    }

    // --------------------------------------------------------------- delete

    private void delete(final Run r, final long now) throws KgException {
        final Candidates batch = this.store.read(c -> candidates(c, r.id, VERDICT_ABSENT, DELETE_BATCH));
        if (batch.ids.isEmpty()) {
            setPhaseNow(r, PHASE_DONE);
            return;
        }
        // verified again right before the delete: a re-added document is never removed
        final List<String> ids = batch.idList();
        final Map<String, SolrDoc> found = new HashMap<>();
        try {
            for (int i = 0; i < ids.size(); i += SolrSource.MAX_GET) {
                found.putAll(this.solr.get(ids.subList(i, Math.min(ids.size(), i + SolrSource.MAX_GET)), VERIFY_FIELDS));
            }
        } catch (final IOException e) {
            abort(r, "solr_error: " + e.getMessage(), now);
            return;
        }
        final List<String> remove = new ArrayList<>();
        final List<String> back = new ArrayList<>();
        final Map<String, Long> expected = new HashMap<>();
        for (final String id : ids) {
            final SolrDoc d = found.get(id);
            if (d != null && !d.followed(this.cfg).isEmpty()) {
                back.add(id);
            } else if (this.dirty.hasNewer(id, 0L)) {
                back.add(id); // a change is pending in memory: its event decides
            } else {
                remove.add(id);
                expected.put(id, batch.ids.get(id));
            }
        }
        this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            final int n = remove.isEmpty() ? 0 : this.publisher.remove(tx, remove, expected, now);
            WorkQueue.scanned(tx, back, WorkQueue.REASON_RECONCILE, WorkQueue.PRIO_VERIFIED, 0L, now, this.queueMax);
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_scan_candidate WHERE run_id = ? AND doc_id = ?")) {
                for (final String id : ids) {
                    ps.setLong(1, r.id);
                    ps.setString(2, id);
                    ps.executeUpdate();
                }
            }
            r.deleted += n;
            saveCounts(tx, r);
            return null;
        });
    }

    private void complete(final Run r, final long now) throws KgException {
        this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_scan_candidate WHERE run_id = ?")) {
                ps.setLong(1, r.id);
                ps.executeUpdate();
            }
            r.phase = PHASE_DONE;
            saveCounts(tx, r);
            setState(tx, r, STATE_COMPLETED, null, now);
            try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_scan SET finished_at = ? WHERE run_id = ?")) {
                ps.setLong(1, now);
                ps.setLong(2, r.id);
                ps.executeUpdate();
            }
            KgStore.putMeta(tx, KgSchema.META_RECONCILE_LAST_COMPLETED, Long.toString(now));
            KgStore.putMeta(tx, KgSchema.META_RECONCILE_REQUIRED, "0");
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM kg_scan WHERE run_id NOT IN"
                    + " (SELECT run_id FROM kg_scan ORDER BY run_id DESC LIMIT " + KEEP_RUNS + ")")) {
                ps.executeUpdate();
            }
            KgStore.event(tx, 1, r.kind == KIND_BACKFILL ? "backfill_completed" : "reconcile_completed",
                    "scanned " + r.scanned + ", enqueued " + r.enqueued + ", deleted " + r.deleted, now);
            return null;
        });
        this.lastCompletedAt = now;
        this.lastRun = r.json();
        this.run = null;
        this.failures = 0;
        synchronized (this.lock) {
            // a request that arrived after this run started (or before a resumed backfill) needs another run
            if (this.pending && this.requestedAt <= r.startedAt) {
                this.pending = false;
            }
            if (REASON_EXTRACTORS.equals(r.reason)) {
                this.reextract = false;
            }
        }
        if (r.dropped > 0L) {
            // the queue was full: the documents that did not fit come with the next run, as soon as the
            // queue has room again (SyncService.step), at the latest after QUEUE_FULL_RETRY
            request(REASON_QUEUE_FULL, now + QUEUE_FULL_RETRY);
        }
        final long after;
        synchronized (this.lock) {
            after = this.rescanAfter;
        }
        if (r.startedAt < after) {
            // lost changes may not have been visible to this scan yet
            request(REASON_LOST_CHANGES, after);
        }
        JsonLdCapture.reconciled();
    }

    private void abort(final Run r, final String detail, final long now) throws KgException {
        this.failures++;
        this.retryAt = now + Math.min(RETRY_MAX, RETRY_MIN << Math.min(10, this.failures - 1));
        final String d = clip(detail, 400);
        LOG.warn("knowledge graph " + (r.kind == KIND_BACKFILL ? "backfill" : "reconcile") + " aborted, nothing deleted: " + d);
        r.state = STATE_ABORTED;
        r.detail = d;
        this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            setState(tx, r, STATE_ABORTED, d, now);
            KgStore.event(tx, 2, "reconcile_aborted", d, now);
            return null;
        });
    }

    // ------------------------------------------------------------- catch-up

    private void catchUpStep(final long now) throws KgException {
        final SolrSource.Page page;
        try {
            page = this.solr.scan(this.catchUpCursor, this.page, this.cfg.allCollections ? null : this.cfg.collections, this.catchUpVersion);
        } catch (final IOException e) {
            LOG.info("knowledge graph version catch-up skipped (" + e.getMessage() + "); the full reconcile follows");
            this.catchUp = false;
            return;
        }
        final List<String> ids = new ArrayList<>();
        for (final SolrDoc d : page.docs) {
            if (KgIds.isDocId(d.id)) {
                ids.add(d.id);
            }
        }
        final WorkQueue.Added a = this.store.write(WriteClass.MAINTENANCE, ESTIMATE,
                tx -> WorkQueue.scanned(tx, ids, WorkQueue.REASON_RECONCILE, WorkQueue.PRIO_CATCHUP, 0L, now, this.queueMax));
        this.catchUpEnqueued += a.inserted;
        if (page.partial || page.docs.size() < this.page || a.dropped > 0) {
            this.catchUp = false;
        } else {
            this.catchUpCursor = page.docs.get(page.docs.size() - 1).id;
        }
    }

    // -------------------------------------------------------------- storage

    private static final String RUN_COLUMNS = "run_id, kind, state, phase, cursor, started_at, reason, scanned, enqueued,"
            + " delete_candidates, confirmed, deleted, solr_seen, tracked, detail";

    private static Run load(final Connection c, final String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            final Run r = new Run();
            r.id = rs.getLong(1);
            r.kind = rs.getInt(2);
            r.state = rs.getInt(3);
            r.phase = rs.getInt(4);
            r.cursor = rs.getString(5);
            r.startedAt = rs.getLong(6);
            r.reason = rs.getString(7);
            r.scanned = rs.getLong(8);
            r.enqueued = rs.getLong(9);
            r.candidates = rs.getLong(10);
            r.confirmed = rs.getLong(11);
            r.deleted = rs.getLong(12);
            r.solrSeen = rs.getLong(13);
            r.tracked = rs.getLong(14);
            r.detail = rs.getString(15);
            return r;
        }
    }

    private static void saveCounts(final Connection tx, final Run r) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_scan SET cursor = ?, scanned = ?, enqueued = ?, delete_candidates = ?,"
                + " confirmed = ?, deleted = ?, solr_seen = ?, tracked = ?, phase = ? WHERE run_id = ?")) {
            ps.setString(1, r.cursor);
            ps.setLong(2, r.scanned);
            ps.setLong(3, r.enqueued);
            ps.setLong(4, r.candidates);
            ps.setLong(5, r.confirmed);
            ps.setLong(6, r.deleted);
            ps.setLong(7, r.solrSeen);
            ps.setLong(8, r.tracked);
            ps.setInt(9, r.phase);
            ps.setLong(10, r.id);
            ps.executeUpdate();
        }
    }

    private static void setState(final Connection tx, final Run r, final int state, final String detail, final long now) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_scan SET state = ?, detail = ?,"
                + " finished_at = CASE WHEN ? IN (2, 3) THEN ? ELSE NULL END WHERE run_id = ?")) {
            ps.setInt(1, state);
            ps.setString(2, detail);
            ps.setInt(3, state);
            ps.setLong(4, now);
            ps.setLong(5, r.id);
            ps.executeUpdate();
        }
        r.state = state;
        r.detail = detail;
    }

    private static void setPhase(final Connection tx, final Run r, final int phase) throws SQLException {
        r.phase = phase;
        saveCounts(tx, r);
    }

    private void setPhaseNow(final Run r, final int phase) throws KgException {
        this.store.write(WriteClass.MAINTENANCE, ESTIMATE, tx -> {
            setPhase(tx, r, phase);
            return null;
        });
    }

    /** Candidates of a run with one verdict: ID → generation at scan time. */
    private static final class Candidates {
        final LinkedHashMap<String, Long> ids = new LinkedHashMap<>();

        List<String> idList() {
            return new ArrayList<>(this.ids.keySet());
        }
    }

    private static Candidates candidates(final Connection c, final long run, final int verdict, final int limit) throws SQLException {
        final Candidates out = new Candidates();
        try (PreparedStatement ps = c.prepareStatement("SELECT doc_id, generation FROM kg_scan_candidate WHERE run_id = ? AND verdict = ?"
                + " ORDER BY doc_id LIMIT ?")) {
            ps.setLong(1, run);
            ps.setInt(2, verdict);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.ids.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return out;
    }

    private static void verdict(final Connection tx, final long run, final Collection<String> ids, final int verdict) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("UPDATE kg_scan_candidate SET verdict = ? WHERE run_id = ? AND doc_id = ?")) {
            for (final String id : ids) {
                ps.setInt(1, verdict);
                ps.setLong(2, run);
                ps.setString(3, id);
                ps.executeUpdate();
            }
        }
    }

    private static String clip(final String s, final int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // --------------------------------------------------------------- status

    static String stateName(final int state) {
        switch (state) {
            case STATE_RUNNING:
                return "running";
            case STATE_COMPLETED:
                return "completed";
            case STATE_ABORTED:
                return "aborted";
            default:
                return "suspect";
        }
    }

    static String phaseName(final int phase) {
        switch (phase) {
            case PHASE_SCAN:
                return "scan";
            case PHASE_VERIFY:
                return "verify";
            case PHASE_DELETE:
                return "delete";
            default:
                return "done";
        }
    }

    JSONObject status() {
        final Run r = this.run;
        final boolean p;
        final String reason;
        final long due;
        final boolean all;
        synchronized (this.lock) {
            p = this.pending;
            reason = this.pendingReason;
            due = this.dueAt;
            all = this.reextract;
        }
        return KgJson.obj("pending", p, "reason", p ? reason : null, "dueAt", p ? due : null, "reextract", all,
                "current", r == null ? null : r.json(), "retryAt", r != null && r.state == STATE_ABORTED ? this.retryAt : null,
                "awaitingConfirmation", r != null && r.state == STATE_SUSPECT,
                "last", this.lastRun, "lastCompletedAt", this.lastCompletedAt > 0L ? this.lastCompletedAt : null,
                "catchUp", KgJson.obj("active", this.catchUp, "fromVersion", this.catchUpVersion > 0L ? this.catchUpVersion : null,
                        "enqueued", this.catchUpEnqueued),
                "gate", this.gateClosed);
    }

    /** For tests: the current run, or null. */
    Run current() {
        return this.run;
    }
}
