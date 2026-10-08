/*
 *  KgRebuild
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

package net.yacy.scoutro.knowledge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.budget.StorageProbe;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.sync.DirtySet;
import net.yacy.scoutro.knowledge.sync.Gates;
import net.yacy.scoutro.knowledge.sync.SolrSource;
import net.yacy.scoutro.knowledge.sync.SyncService;

/**
 * The administrator action "re-resolve identities" (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 4.3 and 22): a complete rebuild of the graph from Solr into a shadow store
 * {@code rebuild/graph.db}, while the current graph keeps serving reads and
 * following Solr. The shadow runs its own sync (the normal backfill: tiers 1
 * and 2 from Solr, identity resolution with the current rules, so wrong
 * merges are split and new merges applied; every fact keeps its evidence),
 * behind the same gates and with the remaining budget as its own budget.
 * <p>
 * Phases: {@code building} (progress: scanned, enqueued, published, queue),
 * {@code verifying} ({@code quick_check}; the shadow's documents against the
 * current graph's with the reconcile's mass-deletion brake:
 * {@code reconcile.maxDeleteFraction}, at least {@code reconcile.brakeMinDocs},
 * or an empty shadow against a non-empty graph), {@code awaiting_confirmation}
 * when the brake holds, {@code swapping} (entity IDs of the current graph that
 * the shadow does not know become redirects; the cache of the LLM tier and the
 * manual pause are copied; the current database is kept as
 * {@code backup/graph-<UTC>-before-rebuild.db}, the shadow takes its place
 * with its own new epoch, and the graph starts again: a full reconcile catches
 * up with what changed meanwhile, the LLM tier answers from the copied
 * cache), then {@code done}. {@code cancel} at any phase before the swap
 * deletes the shadow and leaves the graph as it was; so does a stop of
 * Scoutro, and a start that finds a shadow left behind records the rebuild
 * as interrupted.
 */
final class KgRebuild {

    static final String THREAD = "ScoutroKG.rebuild";
    static final String DIR = "rebuild";
    /** The shadow needs room for the current graph's logical size times this, plus the WAL and temp limits. */
    static final double SPACE_FACTOR = 1.2;
    private static final long STEP_PAUSE_MILLIS = 50L;
    private static final long IDLE_PAUSE_MILLIS = 500L;
    private static final long CHECK_EVERY_MILLIS = 2000L;
    private static final long MEASURE_EVERY_MILLIS = 30_000L;
    private static final int CACHE_BATCH = 500;
    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-KG");

    enum Phase { BUILDING, VERIFYING, AWAITING_CONFIRMATION, SWAPPING, DONE, CANCELLED, FAILED }

    /** What the runtime does for the rebuild. */
    interface Host {
        /** Replaces the current graph by {@code shadowDb}; the old database goes to {@code keepAs}. */
        void swapIn(File shadowDb, File keepAs, Map<String, String> carryMeta) throws KgException;

        void event(int level, String code, String detail);
    }

    private final Host host;
    private final KgConfig mainCfg;
    private final KgPaths mainPaths;
    private final KgStore main;
    private final SolrSource solr;
    private final Gates gates;
    private final StorageProbe probe;
    private final LongSupplier clock;
    private final KgPaths paths;
    private final KgConfig cfg;
    private final long mainDocs;
    private final long mainEntities;
    private StorageGuard guard;
    private KgStore shadow;
    private SyncService sync;
    private Thread thread;

    private volatile Phase phase = Phase.BUILDING;
    private volatile boolean cancel;
    private volatile boolean confirmed;
    private final long startedAt;
    private volatile long finishedAt;
    private volatile String error;
    private volatile JSONObject verify;
    private volatile JSONObject progress = new JSONObject();
    private volatile String keptAs;
    private volatile int idsRedirected = -1;
    private volatile int cacheCopied = -1;

    private KgRebuild(final Host host, final KgConfig mainCfg, final KgPaths mainPaths, final KgStore main, final SolrSource solr,
            final Gates gates, final StorageProbe probe, final LongSupplier clock, final KgConfig cfg, final long mainDocs,
            final long mainEntities) {
        this.host = host;
        this.mainCfg = mainCfg;
        this.mainPaths = mainPaths;
        this.main = main;
        this.solr = solr;
        this.gates = gates;
        this.probe = probe;
        this.clock = clock;
        this.paths = KgPaths.at(new File(mainPaths.dir, DIR));
        this.cfg = cfg;
        this.mainDocs = mainDocs;
        this.mainEntities = mainEntities;
        this.startedAt = clock.getAsLong();
    }

    /**
     * Checks the preconditions, opens the shadow store and starts the rebuild thread.
     *
     * @param lookup     the graph's settings
     * @param usedBytes  what the graph uses now (its budget minus this is the shadow's budget)
     * @param logicalBytes the current database's logical size
     */
    static KgRebuild start(final Host host, final KgConfig mainCfg, final Function<String, String> lookup, final KgPaths mainPaths,
            final KgStore main, final SolrSource solr, final Gates gates, final StorageProbe probe, final LongSupplier clock,
            final long usedBytes, final long logicalBytes) throws KgException {
        final long remaining = mainCfg.budgetMaxBytes - usedBytes;
        // at least the smallest budget a graph can have, and room for the copy with its WAL and temporary files
        final long needed = Math.max(KgConfig.MIN_BUDGET_BYTES,
                (long) (logicalBytes * SPACE_FACTOR) + mainCfg.walMaxBytes + mainCfg.tmpMaxBytes);
        if (remaining < needed) {
            throw new KgException(KgException.WRITE_REFUSED, "rebuild_space", "the rebuild needs about " + needed
                    + " bytes of the budget for the shadow graph, " + remaining + " are left", null);
        }
        final KgConfig cfg = KgConfig.read(key -> {
            if (KgConfig.BUDGET_MAX_BYTES.equals(key)) {
                return Long.toString(remaining);
            }
            if (KgConfig.BUDGET_MAINTENANCE_PERCENT.equals(key)) {
                return null; // the smallest share that holds WAL and temp files of the smaller budget
            }
            return lookup.apply(key);
        }, mainCfg.perCollectionKeys);
        if (!cfg.valid()) {
            throw new KgException(KgException.CONFIG_INVALID, "rebuild settings invalid: " + cfg.problems());
        }
        final long[] counts = main.read(c -> new long[] {KgStore.queryLong(c, "SELECT count(*) FROM kg_doc WHERE state = 1"),
                KgStore.queryLong(c, "SELECT count(*) FROM kg_entity WHERE status = 1")});
        final KgRebuild r = new KgRebuild(host, mainCfg, mainPaths, main, solr, gates, probe, clock, cfg, counts[0], counts[1]);
        deleteTree(r.paths.dir);
        r.guard = new StorageGuard(cfg, r.paths, probe, clock);
        r.guard.refresh();
        r.shadow = KgStore.open(r.paths, cfg, r.guard, KgStore.SQLITE, clock);
        r.sync = new SyncService(cfg, r.shadow, new DirtySet(cfg.captureMaxPending), solr, gates, clock, false);
        r.thread = new Thread(r::run, THREAD);
        r.thread.setDaemon(true);
        r.thread.setPriority(Thread.MIN_PRIORITY);
        r.thread.start();
        host.event(1, "rebuild_started", "documents " + counts[0] + ", entities " + counts[1] + ", shadow budget " + remaining);
        return r;
    }

    /** True for a shadow directory left behind by an interrupted rebuild; deletes it. */
    static boolean discardLeftover(final KgPaths mainPaths) {
        final File dir = new File(mainPaths.dir, DIR);
        if (!dir.exists()) {
            return false;
        }
        deleteTree(dir);
        return true;
    }

    // ------------------------------------------------------------ control

    void cancel() {
        this.cancel = true;
        final Thread t = this.thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Lets a rebuild stopped by the brake swap. */
    boolean confirm() {
        if (this.phase != Phase.AWAITING_CONFIRMATION) {
            return false;
        }
        this.confirmed = true;
        return true;
    }

    boolean active() {
        final Phase p = this.phase;
        return p == Phase.BUILDING || p == Phase.VERIFYING || p == Phase.AWAITING_CONFIRMATION || p == Phase.SWAPPING;
    }

    Phase phase() {
        return this.phase;
    }

    /** Waits for the rebuild thread (stop of the graph); never from the rebuild thread itself. */
    void join(final long millis) {
        final Thread t = this.thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(millis);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    boolean isRebuildThread() {
        return Thread.currentThread() == this.thread;
    }

    // --------------------------------------------------------------- run

    private void run() {
        try {
            build();
            if (this.cancel) {
                end(Phase.CANCELLED, null);
                return;
            }
            this.phase = Phase.VERIFYING;
            if (!verify()) {
                this.phase = Phase.AWAITING_CONFIRMATION;
                this.host.event(2, "rebuild_awaiting_confirmation", String.valueOf(this.verify));
                while (!this.cancel && !this.confirmed) {
                    sleep(IDLE_PAUSE_MILLIS);
                }
                if (this.cancel) {
                    end(Phase.CANCELLED, null);
                    return;
                }
            }
            // the swap brings the rebuilt facts in: it waits while the graph is paused
            while (!this.cancel && this.main.manualPause()) {
                this.guard.setManualPause(true);
                sleep(IDLE_PAUSE_MILLIS);
            }
            this.guard.setManualPause(false);
            if (this.cancel) {
                end(Phase.CANCELLED, null);
                return;
            }
            this.phase = Phase.SWAPPING;
            final int carried = carryIds();
            this.idsRedirected = carried;
            final int copied = copyCache();
            this.cacheCopied = copied;
            final Map<String, String> carry = new HashMap<>();
            // the active knowledge prompt and its history belong to the graph: a rebuild keeps them
            for (final String key : new String[] {KgSchema.META_MANUAL_PAUSE, KgSchema.META_LAST_BACKUP_AT,
                net.yacy.scoutro.knowledge.extract.KnowledgePrompt.META_ACTIVE, net.yacy.scoutro.knowledge.extract.KnowledgePrompt.META_HISTORY}) {
                final String v = this.main.read(c -> KgStore.getMeta(c, key));
                if (v != null) {
                    carry.put(key, v);
                }
            }
            this.sync.requestStop();
            this.shadow.close();
            final File keep = new File(this.mainPaths.backup, net.yacy.scoutro.knowledge.store.KgBackup.name(this.clock.getAsLong(),
                    net.yacy.scoutro.knowledge.store.KgBackup.BEFORE_REBUILD));
            this.keptAs = keep.getName();
            this.host.swapIn(this.paths.db, keep, carry);
            deleteTree(this.paths.dir);
            this.phase = Phase.DONE;
            this.finishedAt = this.clock.getAsLong();
            this.host.event(1, "rebuild_done", "previous graph kept as " + keep.getName() + "; " + carried + " entity IDs redirected; "
                    + copied + " cache entries copied; "
                    + String.valueOf(this.verify));
            LOG.info("knowledge graph rebuild done; previous graph kept as " + keep.getName());
        } catch (final KgException e) {
            end(this.cancel ? Phase.CANCELLED : Phase.FAILED, e.code() + (e.reason() == null ? "" : ": " + e.reason()));
        } catch (final RuntimeException e) {
            LOG.warn("knowledge graph rebuild failed: " + e);
            end(Phase.FAILED, e.getClass().getSimpleName());
        }
    }

    /** Runs the shadow sync until its backfill is complete and its queue is empty. */
    private void build() throws KgException {
        long lastCheck = 0L;
        long lastMeasure = 0L;
        while (!this.cancel) {
            // the shadow follows the manual pause of the graph: no enrichment there either while it is paused
            this.guard.setManualPause(this.main.manualPause());
            final boolean more = this.sync.step();
            final long now = this.clock.getAsLong();
            this.shadow.interruptExpiredReaders(now);
            if (now - lastMeasure >= MEASURE_EVERY_MILLIS) {
                lastMeasure = now;
                this.guard.refresh();
                if (this.guard.walBytes() >= this.cfg.walCheckpointBytes) {
                    this.shadow.checkpoint();
                }
            }
            if (now - lastCheck >= CHECK_EVERY_MILLIS) {
                lastCheck = now;
                if (complete()) {
                    return;
                }
            }
            sleep(more ? STEP_PAUSE_MILLIS : IDLE_PAUSE_MILLIS);
        }
    }

    /** The shadow's backfill completed and nothing is queued any more. */
    private boolean complete() {
        final JSONObject s = this.sync.status();
        final JSONObject rec = s.optJSONObject("reconcile");
        final JSONObject queue = s.optJSONObject("queue");
        final JSONObject cur = rec == null ? null : rec.optJSONObject("current");
        this.progress = KgJson.obj("state", s.opt("state"), "scanned", cur == null ? null : cur.opt("scanned"),
                "enqueued", cur == null ? null : cur.opt("enqueued"), "published", s.optJSONObject("processed") == null ? null
                        : s.optJSONObject("processed").opt("published"),
                "queue", queue == null ? null : queue.opt("items"), "gate", s.opt("gate"), "lastError", s.opt("lastError"),
                "storage", KgJson.obj("usedBytes", this.guard.status().opt("usedBytes"), "budgetBytes", this.cfg.budgetMaxBytes,
                        "reasons", this.guard.status().opt("reasons")));
        if (rec == null || rec.optBoolean("pending") || queue == null || queue.optLong("items", 1L) != 0L) {
            return false;
        }
        final JSONObject last = rec.optJSONObject("last");
        return last != null && "completed".equals(last.optString("state"));
    }

    /**
     * {@code quick_check} of the shadow and the brake: true to swap at once,
     * false to wait for a confirmation.
     */
    private boolean verify() throws KgException {
        final String check = this.shadow.quickCheck(this.mainCfg.integrityMaxMillis);
        if (!"ok".equals(check)) {
            throw new KgException(KgException.STORAGE_ERROR, "quick_check", "the shadow graph failed quick_check: " + check, null);
        }
        final long[] counts = this.shadow.read(c -> new long[] {KgStore.queryLong(c, "SELECT count(*) FROM kg_doc WHERE state = 1"),
                KgStore.queryLong(c, "SELECT count(*) FROM kg_entity WHERE status = 1"),
                KgStore.queryLong(c, "SELECT count(*) FROM kg_statement"), KgStore.queryLong(c, "SELECT count(*) FROM kg_evidence")});
        final long lost = this.mainDocs - counts[0];
        final boolean brake = this.mainDocs > 0 && counts[0] == 0
                || lost >= this.mainCfg.reconcileBrakeMinDocs && lost > this.mainCfg.reconcileMaxDeleteFraction * this.mainDocs;
        this.verify = KgJson.obj("quickCheck", check, "documentsBefore", this.mainDocs, "documentsAfter", counts[0],
                "entitiesBefore", this.mainEntities, "entitiesAfter", counts[1], "statementsAfter", counts[2], "evidenceAfter", counts[3],
                "brake", brake);
        return !brake;
    }

    /**
     * Keeps the entity IDs that callers may have stored (API, export, agents):
     * every ID of the current graph (an entity, a merged entity, a redirect)
     * that the shadow does not know becomes a redirect in the shadow to the
     * entity that now holds most of the identity keys of the entity it stood
     * for. An ID whose keys are not in the shadow at all (its documents are
     * gone) is not carried; it answers 404 like any unknown ID.
     */
    private int carryIds() throws KgException {
        int carried = 0;
        long after = 0L;
        while (!this.cancel) {
            final long from = after;
            final List<Object[]> batch = this.main.read(c -> {
                final List<Object[]> out = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT ent_rowid, public_id FROM kg_entity WHERE status = 1 AND ent_rowid > ?"
                        + " ORDER BY ent_rowid LIMIT " + CACHE_BATCH);
                        PreparedStatement merged = c.prepareStatement("SELECT public_id FROM kg_entity WHERE merged_into = ?");
                        PreparedStatement redirects = c.prepareStatement("SELECT public_id FROM kg_entity_redirect WHERE target_rowid = ?");
                        PreparedStatement keys = c.prepareStatement("SELECT v.kind, v.name, k.scope, k.value FROM kg_entity_key k"
                                + " JOIN kg_vocab v ON v.term_id = k.scheme WHERE k.ent_rowid = ?")) {
                    ps.setLong(1, from);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            final long ent = rs.getLong(1);
                            final List<String> ids = new ArrayList<>();
                            ids.add(rs.getString(2));
                            for (final PreparedStatement q : new PreparedStatement[] {merged, redirects}) {
                                q.setLong(1, ent);
                                try (ResultSet r2 = q.executeQuery()) {
                                    while (r2.next()) {
                                        ids.add(r2.getString(1));
                                    }
                                }
                            }
                            final List<Object[]> ks = new ArrayList<>();
                            keys.setLong(1, ent);
                            try (ResultSet r2 = keys.executeQuery()) {
                                while (r2.next()) {
                                    ks.add(new Object[] {r2.getInt(1), r2.getString(2), r2.getString(3), r2.getString(4)});
                                }
                            }
                            out.add(new Object[] {ent, ids, ks});
                        }
                    }
                }
                return out;
            });
            if (batch.isEmpty()) {
                break;
            }
            final int n = this.shadow.write(WriteClass.MAINTENANCE, 64L * 1024L, tx -> {
                int added = 0;
                try (PreparedStatement known = tx.prepareStatement("SELECT (SELECT count(*) FROM kg_entity WHERE public_id = ?)"
                        + " + (SELECT count(*) FROM kg_entity_redirect WHERE public_id = ?)");
                        PreparedStatement key = tx.prepareStatement("SELECT k.ent_rowid FROM kg_entity_key k JOIN kg_vocab v"
                                + " ON v.term_id = k.scheme AND v.kind = ? AND v.name = ? WHERE k.scope = ? AND k.value = ?");
                        PreparedStatement active = tx.prepareStatement("SELECT status, coalesce(merged_into, 0) FROM kg_entity WHERE ent_rowid = ?");
                        PreparedStatement ins = tx.prepareStatement("INSERT OR IGNORE INTO kg_entity_redirect (public_id, target_rowid)"
                                + " VALUES (?, ?)")) {
                    for (final Object[] e : batch) {
                        @SuppressWarnings("unchecked")
                        final List<String> ids = (List<String>) e[1];
                        final List<String> missing = new ArrayList<>();
                        for (final String id : ids) {
                            known.setString(1, id);
                            known.setString(2, id);
                            try (ResultSet rs = known.executeQuery()) {
                                if (rs.next() && rs.getLong(1) == 0L) {
                                    missing.add(id);
                                }
                            }
                        }
                        if (missing.isEmpty()) {
                            continue;
                        }
                        final Map<Long, Integer> votes = new HashMap<>();
                        @SuppressWarnings("unchecked")
                        final List<Object[]> ks = (List<Object[]>) e[2];
                        for (final Object[] k : ks) {
                            key.setInt(1, (Integer) k[0]);
                            key.setString(2, (String) k[1]);
                            key.setString(3, (String) k[2]);
                            key.setString(4, (String) k[3]);
                            try (ResultSet rs = key.executeQuery()) {
                                if (rs.next()) {
                                    votes.merge(activeOf(active, rs.getLong(1)), 1, Integer::sum);
                                }
                            }
                        }
                        long target = 0L;
                        int best = 0;
                        for (final Map.Entry<Long, Integer> v : votes.entrySet()) {
                            if (v.getValue() > best || v.getValue() == best && v.getKey() < target) {
                                best = v.getValue();
                                target = v.getKey();
                            }
                        }
                        if (target == 0L) {
                            continue;
                        }
                        for (final String id : missing) {
                            ins.setString(1, id);
                            ins.setLong(2, target);
                            added += ins.executeUpdate();
                        }
                    }
                }
                return added;
            });
            carried += n;
            after = (Long) batch.get(batch.size() - 1)[0];
        }
        return carried;
    }

    /** The active entity a shadow entity stands for (follows merges). */
    private static long activeOf(final PreparedStatement active, final long ent) throws java.sql.SQLException {
        long target = ent;
        for (int i = 0; i < 16; i++) {
            active.setLong(1, target);
            try (ResultSet rs = active.executeQuery()) {
                if (!rs.next() || rs.getInt(1) == 1) {
                    return target;
                }
                target = rs.getLong(2);
            }
        }
        return target;
    }

    /**
     * Copies the LLM tier's validated answers ({@code kg_extraction}) into
     * the shadow, in key order, with the extractor rows mapped, so the tier
     * answers the rebuilt documents from the cache instead of the model.
     */
    private int copyCache() throws KgException {
        final Map<Long, long[]> ext = new HashMap<>();
        final List<Object[]> extractors = this.main.read(c -> {
            final List<Object[]> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ext_id, tier, name, version, model, prompt_hash FROM kg_extractor");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Object[] {rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)});
                }
            }
            return out;
        });
        this.shadow.write(WriteClass.MAINTENANCE, 64L * 1024L, tx -> {
            for (final Object[] e : extractors) {
                try (PreparedStatement ins = tx.prepareStatement("INSERT OR IGNORE INTO kg_extractor (tier, name, version, model, prompt_hash)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
                    ins.setInt(1, (Integer) e[1]);
                    ins.setString(2, (String) e[2]);
                    ins.setString(3, (String) e[3]);
                    ins.setString(4, (String) e[4]);
                    ins.setString(5, (String) e[5]);
                    ins.executeUpdate();
                }
                try (PreparedStatement q = tx.prepareStatement("SELECT ext_id FROM kg_extractor WHERE tier = ? AND name = ? AND version = ?"
                        + " AND model = ? AND prompt_hash = ?")) {
                    q.setInt(1, (Integer) e[1]);
                    q.setString(2, (String) e[2]);
                    q.setString(3, (String) e[3]);
                    q.setString(4, (String) e[4]);
                    q.setString(5, (String) e[5]);
                    try (ResultSet rs = q.executeQuery()) {
                        if (rs.next()) {
                            ext.put((Long) e[0], new long[] {rs.getLong(1)});
                        }
                    }
                }
            }
            return null;
        });
        int copied = 0;
        byte[] after = new byte[0];
        while (!this.cancel) {
            final byte[] from = after;
            final List<Object[]> rows = this.main.read(c -> {
                final List<Object[]> out = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT cache_key, ext_id, status, result, bytes, last_used FROM kg_extraction"
                        + " WHERE cache_key > ? ORDER BY cache_key LIMIT " + CACHE_BATCH)) {
                    ps.setBytes(1, from);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new Object[] {rs.getBytes(1), rs.getLong(2), rs.getInt(3), rs.getBytes(4), rs.getLong(5), rs.getLong(6)});
                        }
                    }
                }
                return out;
            });
            if (rows.isEmpty()) {
                break;
            }
            long bytes = 0L;
            for (final Object[] r : rows) {
                bytes += (Long) r[4] + 64L;
            }
            try {
                this.shadow.write(WriteClass.GROWTH, bytes, tx -> {
                    try (PreparedStatement ins = tx.prepareStatement("INSERT OR IGNORE INTO kg_extraction"
                            + " (cache_key, ext_id, status, result, bytes, last_used) VALUES (?, ?, ?, ?, ?, ?)")) {
                        for (final Object[] r : rows) {
                            final long[] id = ext.get(r[1]);
                            if (id == null) {
                                continue;
                            }
                            ins.setBytes(1, (byte[]) r[0]);
                            ins.setLong(2, id[0]);
                            ins.setInt(3, (Integer) r[2]);
                            ins.setBytes(4, (byte[]) r[3]);
                            ins.setLong(5, (Long) r[4]);
                            ins.setLong(6, (Long) r[5]);
                            ins.executeUpdate();
                        }
                    }
                    return null;
                });
            } catch (final KgException e) {
                if (KgException.WRITE_REFUSED.equals(e.code())) {
                    break; // the cache is optional: the LLM tier asks the model again for what did not fit
                }
                throw e;
            }
            copied += rows.size();
            after = (byte[]) rows.get(rows.size() - 1)[0];
        }
        return copied;
    }

    private void end(final Phase p, final String why) {
        this.phase = p;
        this.error = why;
        this.finishedAt = this.clock.getAsLong();
        try {
            if (this.sync != null) {
                this.sync.requestStop();
            }
            if (this.shadow != null) {
                this.shadow.close();
            }
        } catch (final RuntimeException e) {
            // closing anyway
        }
        deleteTree(this.paths.dir);
        this.host.event(p == Phase.CANCELLED ? 1 : 2, p == Phase.CANCELLED ? "rebuild_cancelled" : "rebuild_failed",
                why == null ? "the shadow graph was deleted; the graph is unchanged" : why + "; the graph is unchanged");
    }

    private void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            // cancel() interrupts; the loop checks the flag
        }
    }

    static void deleteTree(final File dir) {
        if (!dir.exists()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (final IOException e) {
                    p.toFile().deleteOnExit();
                }
            });
        } catch (final IOException e) {
            dir.deleteOnExit();
        }
    }

    // ------------------------------------------------------------ status

    JSONObject status() {
        return KgJson.obj("phase", this.phase.name().toLowerCase(java.util.Locale.ROOT), "startedAt", this.startedAt,
                "finishedAt", this.finishedAt > 0L ? this.finishedAt : null, "error", this.error,
                "progress", this.progress, "verify", this.verify, "awaitingConfirmation", this.phase == Phase.AWAITING_CONFIRMATION,
                "keptAs", this.keptAs, "idsRedirected", this.idsRedirected < 0 ? null : this.idsRedirected,
                "cacheCopied", this.cacheCopied < 0 ? null : this.cacheCopied, "budgetBytes", this.cfg.budgetMaxBytes,
                "dir", KgPaths.RELATIVE_DIR + "/" + DIR, "paused", this.guard != null && this.guard.manualPause());
    }
}
