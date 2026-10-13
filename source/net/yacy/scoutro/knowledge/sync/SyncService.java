/*
 *  SyncService
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.BusinessRules;
import net.yacy.scoutro.knowledge.extract.BusinessSignals;
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The synchronisation of the graph with Solr (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * section 5). One thread ({@code ScoutroKG.sync}) runs {@link #step()} in a
 * loop; every step is bounded:
 * <ol>
 * <li>a full reset after a {@code *:*} clear, before anything else;</li>
 * <li>the drain of the in-memory change set into {@code kg_work} (maintenance
 * writes; refused events go back into the set);</li>
 * <li>one slice of the reconcile, backfill or {@code _version_} catch-up;</li>
 * <li>one batch of work items: claim, real-time get, classify, extract tiers 1
 * and 2, publish with the generation and version check;</li>
 * <li>one slice of retention.</li>
 * </ol>
 * New growth (extraction of new or changed documents) is a growth write and
 * waits behind the gates and the storage guard; removals, state and scope
 * changes are maintenance writes and continue during a pause. Nothing is
 * written to Solr.
 */
public final class SyncService {

    public static final String THREAD = "ScoutroKG.sync";

    static final long DRAIN_MILLIS = 2000L;
    static final int DRAIN_BATCH = 500;
    static final int DRAIN_BATCHES_PER_STEP = 20;
    static final int CLAIM_BATCH = 50;
    static final long BATCH_BUDGET_MILLIS = 3000L;
    static final long IDLE_CLAIM_MILLIS = 2000L;
    static final long GROWTH_RETRY_MILLIS = 30_000L;
    static final long PENDING_EVERY_MILLIS = 5_000L;
    static final long GATE_RETRY_MILLIS = 15_000L;
    static final long BLOCKED_BATCH_MILLIS = 30_000L;
    static final long REFUSED_RETRY_MILLIS = 5000L;
    static final long SOLR_RETRY_MILLIS = 10_000L;
    static final int MAX_LOOKUPS = 3;
    static final int MAX_FAILURES = 5;
    static final long CHECKPOINT_EVERY_MILLIS = 60_000L;
    static final long CHECKPOINT_LAG_MILLIS = 30_000L;
    static final long SAMPLE_EVERY_MILLIS = 5000L;
    static final long FIRST_RETENTION_DELAY_MILLIS = 5L * 60_000L;
    private static final long SMALL = 64L * 1024L;
    private static final long PUBLISH_ESTIMATE = 512L * 1024L;
    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-KG");

    /** Processing counters (status). */
    static final class Counters {
        final AtomicLong published = new AtomicLong();
        final AtomicLong unchanged = new AtomicLong();
        final AtomicLong lifecycle = new AtomicLong();
        final AtomicLong removed = new AtomicLong();
        final AtomicLong untracked = new AtomicLong();
        final AtomicLong supersededAborts = new AtomicLong();
        final AtomicLong generationAborts = new AtomicLong();
        final AtomicLong olderAborts = new AtomicLong();
        final AtomicLong notVisible = new AtomicLong();
        final AtomicLong deferred = new AtomicLong();
        final AtomicLong growthRefused = new AtomicLong();
        final AtomicLong maintenanceRefused = new AtomicLong();
        final AtomicLong drainRefused = new AtomicLong();
        final AtomicLong queueDropped = new AtomicLong();
        final AtomicLong solrErrors = new AtomicLong();
        final AtomicLong failedDocs = new AtomicLong();
        final AtomicLong drained = new AtomicLong();
        final AtomicLong fullResets = new AtomicLong();
        /** Tier 1 and 2 extractions started (none while growth is refused). */
        final AtomicLong extractions = new AtomicLong();
        /** Pages of collections switched off only (package 6.2): kept as they are. */
        final AtomicLong held = new AtomicLong();

        JSONObject json() {
            return KgJson.obj("published", this.published.get(), "unchanged", this.unchanged.get(), "lifecycle", this.lifecycle.get(),
                    "removed", this.removed.get(), "untracked", this.untracked.get(),
                    "abortedSuperseded", this.supersededAborts.get(), "abortedGeneration", this.generationAborts.get(),
                    "abortedOlder", this.olderAborts.get(), "notYetVisible", this.notVisible.get(), "deferred", this.deferred.get(),
                    "growthRefused", this.growthRefused.get(), "maintenanceRefused", this.maintenanceRefused.get(),
                    "drainRefused", this.drainRefused.get(), "queueDropped", this.queueDropped.get(),
                    "solrErrors", this.solrErrors.get(), "failedDocs", this.failedDocs.get(), "drained", this.drained.get(),
                    "fullResets", this.fullResets.get(), "extractions", this.extractions.get(), "held", this.held.get());
        }
    }

    private final KgConfig cfg;
    private final KgStore store;
    private final DirtySet dirty;
    private final SolrSource solr;
    private final Gates gates;
    private final LongSupplier clock;
    private final boolean uncleanStart;
    private final Terms terms = new Terms();
    private final Publisher publisher;
    private final Reconciler reconciler;
    private final Retention retention;
    private final BaseTiers tiers;
    final Counters counters = new Counters();

    private volatile boolean initialized;
    /** Why the re-extraction of an upgrade waits for a verified backup (package 6); null if it does not. */
    private volatile String upgradeHold;
    private volatile String state = "starting";
    private volatile String reason;
    private volatile String lastError;
    private FullReset reset;
    private volatile boolean resetting;
    private long handledResets;
    private long handledQueryDeletes;
    private long lastDrain;
    private long nextClaimAt;
    private long growthBlockedUntil;
    private long lastBlockedBatch;
    private long claimSeq;
    private long lastSample;
    private final ArrayDeque<long[]> samples = new ArrayDeque<>();
    private long lastCheckpoint;
    private volatile long versionCheckpoint;
    private volatile String gateClosed;
    /** Why growth was refused at the last batch ({@link StorageGuard#growthRefusal}), null if admitted. */
    private volatile String growthRefusal;
    /** Pending work by type, recounted at most every {@link #PENDING_EVERY_MILLIS}. */
    private volatile JSONObject pendingByType;
    private volatile long pendingAt;
    private long queueMax;
    private volatile boolean stopping;
    /** Called after a document of an LLM collection was published with new input (wakes the LLM tier). */
    private volatile Runnable llmWake;

    public SyncService(final KgConfig cfg, final KgStore store, final DirtySet dirty, final SolrSource solr, final Gates gates,
            final LongSupplier clock, final boolean uncleanStart) {
        this.cfg = cfg;
        this.store = store;
        this.dirty = dirty;
        this.solr = solr;
        this.gates = gates;
        this.clock = clock;
        this.uncleanStart = uncleanStart;
        this.publisher = new Publisher(cfg, this.terms);
        this.reconciler = new Reconciler(cfg, store, solr, dirty, this.publisher, gates, clock);
        this.retention = new Retention(cfg, store, this.publisher, clock, clock.getAsLong() + FIRST_RETENTION_DELAY_MILLIS);
        SolrDoc.configure(cfg);
        this.tiers = new BaseTiers(cfg);
        this.queueMax = cfg.queueMaxItems;
        // signals that arrived before this service existed are covered by the start reconcile
        this.handledResets = dirty.fullResets();
        this.handledQueryDeletes = dirty.queryDeletes();
    }

    // ----------------------------------------------------------------- start

    /** kg_meta: how each followed collection reads job advertisements ({@link KgConfig#jobMode}), as JSON, since 0.10.2. */
    static final String META_JOB_MODES = "job_modes";
    /**
     * kg_meta (0.10.3): when the relation rules that ignore consent banners started; an active page processed before it with a
     * relation of the rule tier is extracted again once ({@link #relationRecheck}). {@code META_RELATION_RECHECK_DONE} ends it.
     */
    static final String META_RELATION_RECHECK = "relation_recheck_since";
    static final String META_RELATION_RECHECK_DONE = "relation_recheck_done";
    /**
     * kg_meta (0.10.4): the version of the relation rules the check above belongs to. 1 (0.10.3, no row): consent banners name
     * no partner; 2 (0.10.4): a list ends with its sentence; 3 (0.10.5): a department is no membership ("Mitglied des
     * Einkaufs"), and a careers page has the positions it advertises as jobs, not the entries of its menu. A graph checked with
     * older rules is checked once more.
     */
    static final String META_RELATION_RULES = "relation_recheck_rules";
    static final String RELATION_RULES = "3";
    /** The relations the rule tier reads after a marker (BusinessRules); the fixes of 0.10.3 and 0.10.4 may change them. */
    static final List<String> RECHECK_RELATIONS = List.of(Vocabulary.CARRIER_OF, Vocabulary.SUBSIDIARY_OF, Vocabulary.PARENT_OF,
            Vocabulary.BRAND_OF, Vocabulary.COOPERATION_WITH, Vocabulary.PARTNER_OF, Vocabulary.CUSTOMER_OF, Vocabulary.REFERENCE_FOR,
            Vocabulary.MEMBER_OF, Vocabulary.ASSOCIATION_MEMBER, Vocabulary.CERTIFIED_BY, Vocabulary.FUNDED_BY, Vocabulary.SPONSORED_BY,
            Vocabulary.SUPPLIER_OF, Vocabulary.SERVICE_PROVIDER_FOR);
    /** Pages extracted again although their input is unchanged ({@link #relationRecheck}); removed once extracted. */
    private final java.util.Set<String> recheckRelations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * kg_meta (0.10.5): what the re-check found in the job evidence of the LLM tier on its pages, as JSON ({@link #llmJobAudit}):
     * {@code pages} with such evidence, {@code menuJobs} and {@code menuEvidence} (a job the rules see on the page only as a
     * menu entry or teaser link: wrong), {@code positionEvidence} (a position the page advertises) and {@code otherEvidence}
     * (no title of the rules). Counted only; nothing of the LLM tier is changed.
     */
    static final String META_RECHECK_LLM_JOBS = "relation_recheck_llm_jobs";
    /** The audit of each page re-checked in the current batch, added to {@link #META_RECHECK_LLM_JOBS} with its publication. */
    private final Map<String, long[]> llmJobAudit = new java.util.concurrent.ConcurrentHashMap<>();
    /** {@link #META_RECHECK_LLM_JOBS} as last written (status). */
    private volatile JSONObject llmJobs = new JSONObject();

    /**
     * 0.10.3: a consent banner ("an unsere Partner für soziale Medien, Werbung und Analysen weiter") made purposes partner
     * organisations; 0.10.4: a list went on into the next sentence ("… Beta AG. Muster GmbH, Musterstraße 1" made the operator of
     * the imprint a partner); 0.10.5: a department became an organisation the operator is a member of, and every page of a
     * careers section had the positions of its menu as jobs (or read the hints of their advertisements from its general
     * text). The active pages processed before the corrected rules with a relation, a job or a job advertisement hint of the
     * rule tier are extracted again once per version of the rules ({@link #RELATION_RULES}), at low priority, and only they;
     * their input is unchanged, so they are named here. Tiers 1 and 2 only: the evidence of the LLM tier stays; a page with a
     * job of the LLM tier is also checked, and that evidence is counted ({@link #META_RECHECK_LLM_JOBS}). Restart-safe: a
     * page extracted since then is not named again, and an empty selection ends the check for good.
     */
    private List<String> relationRecheck(final java.sql.Connection tx, final long now) throws java.sql.SQLException {
        if (!RELATION_RULES.equals(KgStore.getMeta(tx, META_RELATION_RULES))) {
            KgStore.deleteMeta(tx, META_RELATION_RECHECK); // checked with older rules (0.10.3) or never: once with these
            KgStore.deleteMeta(tx, META_RELATION_RECHECK_DONE);
            KgStore.deleteMeta(tx, META_RECHECK_LLM_JOBS); // counted by the check of these rules
            KgStore.putMeta(tx, META_RELATION_RULES, RELATION_RULES);
        }
        if (KgStore.getMeta(tx, META_RELATION_RECHECK_DONE) != null) {
            return List.of();
        }
        final String stored = KgStore.getMeta(tx, META_RELATION_RECHECK);
        final long since = stored == null ? now : Long.parseLong(stored);
        if (stored == null) {
            KgStore.putMeta(tx, META_RELATION_RECHECK, Long.toString(since));
        }
        final List<String> ids = new ArrayList<>();
        final StringBuilder in = new StringBuilder();
        for (int i = 0; i < RECHECK_RELATIONS.size(); i++) {
            in.append(i == 0 ? "?" : ", ?");
        }
        try (java.sql.PreparedStatement ps = tx.prepareStatement("SELECT d.doc_id FROM kg_doc d WHERE d.state = " + Aggregates.STATE_ACTIVE
                + " AND (d.processed_at IS NULL OR d.processed_at < ?) AND EXISTS (SELECT 1 FROM kg_evidence e"
                + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred"
                + " JOIN kg_entity n ON n.ent_rowid = s.subj JOIN kg_vocab t ON t.term_id = n.type"
                + " WHERE e.doc_rowid = d.doc_rowid AND (e.tier = 2 AND (v.name IN (" + in + ") OR t.name = ?"
                + " OR v.name = ? AND s.obj_val LIKE ? ESCAPE '\\') OR e.tier = 3 AND t.name = ?)) ORDER BY d.doc_id")) {
            ps.setLong(1, since);
            for (int i = 0; i < RECHECK_RELATIONS.size(); i++) {
                ps.setString(i + 2, RECHECK_RELATIONS.get(i));
            }
            // a job of the rule tier, or a hint read from the text of an advertisement (BusinessSignals.JOB_AD)
            ps.setString(RECHECK_RELATIONS.size() + 2, Vocabulary.JOB);
            ps.setString(RECHECK_RELATIONS.size() + 3, Vocabulary.SYSTEM_SIGNAL);
            ps.setString(RECHECK_RELATIONS.size() + 4, "%\"section\":\"" + BusinessSignals.JOB_AD.replace("_", "\\_") + "%");
            // a job of the LLM tier: its page is extracted again (tiers 1 and 2) and its evidence counted (llmJobAudit)
            ps.setString(RECHECK_RELATIONS.size() + 5, Vocabulary.JOB);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        }
        if (ids.isEmpty()) {
            KgStore.putMeta(tx, META_RELATION_RECHECK_DONE, Long.toString(now));
            return ids;
        }
        if (stored == null) {
            KgStore.event(tx, 1, "relation_rules_recheck", ids.size() + " pages with relations or jobs of the rule tier extracted again"
                    + " (consent banners are no partners, a list ends with its sentence, a department is no membership, a menu entry"
                    + " is no job); no other page", now);
        }
        WorkQueue.scanned(tx, ids, WorkQueue.REASON_RECONCILE, WorkQueue.PRIO_REEXTRACT, 0L, now, this.queueMax);
        return ids;
    }

    /**
     * Compares the job reading of the followed collections with the stored one (0.10.2) and returns the collections whose
     * pages must be checked again, also those of a check that did not complete; empty if none. Without a stored reading
     * (an upgrade) the former behaviour is the reference: job objects with their hints where jobs are extracted, nothing
     * elsewhere. Only a different reading checks pages again, and only job pages are extracted again. The reading a
     * collection was read with stays stored while it is not followed, so switching it on again compares with that one.
     */
    private String jobReading(final java.sql.Connection tx, final long now) throws java.sql.SQLException {
        // the collections of the graph and those the settings name (a reading stored before their first page counts for them)
        final java.util.Map<String, String> current = new java.util.TreeMap<>();
        final java.util.Set<String> resting = new java.util.TreeSet<>(); // in the graph, not followed now
        for (final String c : this.cfg.collections) {
            if (this.cfg.follows(c)) {
                current.put(c, this.cfg.jobMode(c));
            }
        }
        try (java.sql.PreparedStatement ps = tx.prepareStatement("SELECT name FROM kg_collection ORDER BY name");
                java.sql.ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final String c = rs.getString(1);
                if (this.cfg.follows(c)) {
                    current.put(c, this.cfg.jobMode(c));
                } else {
                    resting.add(c);
                }
            }
        }
        final String stored0 = KgStore.getMeta(tx, META_JOB_MODES);
        // a graph without pages has read nothing yet: its reading starts now (only an upgraded graph compares with the former one)
        final String stored = stored0 == null && KgStore.queryLong(tx, "SELECT count(*) FROM (SELECT 1 FROM kg_doc LIMIT 1)") == 0L
                ? "{}" : stored0;
        org.json.JSONObject before = null;
        if (stored != null) {
            try {
                before = new org.json.JSONObject(stored);
            } catch (final org.json.JSONException e) {
                before = null; // unreadable: every collection counts as changed below
            }
        }
        final java.util.Set<String> recheck = new java.util.TreeSet<>();
        final String pending = KgStore.getMeta(tx, Reconciler.META_JOB_RECHECK);
        if (pending != null && !pending.isEmpty()) {
            recheck.addAll(java.util.Arrays.asList(pending.split(",")));
        }
        for (final java.util.Map.Entry<String, String> e : current.entrySet()) {
            final String c = e.getKey();
            // a collection first seen since the reading was stored was read under this version: nothing to check again
            final String was = stored == null ? (this.cfg.jobsExtracted(c) ? "full" : "none")
                    : before == null ? null : before.optString(c, e.getValue());
            if (!e.getValue().equals(was)) {
                recheck.add(c);
            }
        }
        recheck.retainAll(current.keySet());
        final java.util.Map<String, String> keep = new java.util.TreeMap<>(current);
        for (final String c : resting) {
            final String was = stored == null ? (this.cfg.jobsExtracted(c) ? "full" : "none") : before == null ? null : before.optString(c, null);
            if (was != null) {
                keep.put(c, was);
            }
        }
        KgStore.putMeta(tx, META_JOB_MODES, new org.json.JSONObject(keep).toString());
        if (recheck.isEmpty()) {
            KgStore.deleteMeta(tx, Reconciler.META_JOB_RECHECK);
            return "";
        }
        final String list = String.join(",", recheck);
        if (!list.equals(pending)) {
            KgStore.event(tx, 1, "job_reading_changed", "pages checked again, only job pages extracted again: " + list, now);
        }
        KgStore.putMeta(tx, Reconciler.META_JOB_RECHECK, list);
        return list;
    }

    /**
     * Start-up work, retried until the guard admits it (e.g. after the
     * integrity check): seeds the vocabulary, releases claims of the last run,
     * and schedules the full reconcile that every start and every reactivation
     * runs, because Solr may have changed while the graph was disabled or
     * stopped, also after a clean shutdown.
     */
    private void init() throws KgException {
        final long now = this.clock.getAsLong();
        final String[] meta = this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
            this.terms.seed(tx);
            KgStore.putMeta(tx,"observation_vocabulary",net.yacy.scoutro.knowledge.extract.Vocabulary.VERSION+"-"
                    + net.yacy.scoutro.knowledge.vocab.KgVocabularies.get().version());
            WorkQueue.resetClaims(tx);
            final String collections = KgStore.getMeta(tx, KgSchema.META_COLLECTIONS);
            final String extractors = KgStore.getMeta(tx, KgSchema.META_EXTRACTORS);
            final String hold = KgStore.getMeta(tx, KgSchema.META_UPGRADE_HOLD);
            if (hold == null) {
                KgStore.putMeta(tx, KgSchema.META_EXTRACTORS, SolrDoc.extractors());
            } // an upgrade without its copy: the old identity stays, so the re-extraction runs once the hold is released
            this.reconciler.onStart(tx, now);
            final String jobs = jobReading(tx, now);
            this.recheckRelations.clear();
            this.recheckRelations.addAll(relationRecheck(tx, now));
            this.llmJobs = llmJobAudit(tx);
            return new String[] {collections, extractors, KgStore.getMeta(tx, KgSchema.META_RESET_IN_PROGRESS),
                    KgStore.getMeta(tx, KgSchema.META_VERSION_CHECKPOINT), hold, jobs};
        });
        final boolean collectionsChanged = meta[0] != null && !meta[0].equals(this.cfg.collectionsKey());
        final boolean extractorsChanged = meta[1] != null && !meta[1].equals(SolrDoc.extractors());
        this.upgradeHold = meta[4];
        this.reconciler.request(collectionsChanged ? Reconciler.REASON_COLLECTIONS
                : this.uncleanStart ? Reconciler.REASON_UNCLEAN_START : Reconciler.REASON_START, now);
        if (extractorsChanged && this.upgradeHold == null) {
            this.reconciler.reextractAll();
        }
        if (meta[5] != null && !meta[5].isEmpty()) {
            this.reconciler.recheckJobs(java.util.Arrays.asList(meta[5].split(",")));
        }
        if ("1".equals(meta[2])) {
            this.reset = new FullReset(this.store, this.clock, true);
            this.resetting = true;
        }
        final long checkpoint = meta[3] == null ? 0L : Long.parseLong(meta[3]);
        this.versionCheckpoint = checkpoint;
        if (this.uncleanStart && checkpoint > 0L) {
            this.reconciler.catchUp(checkpoint);
        }
        this.initialized = true;
        this.state = "running";
        this.reason = null;
    }

    // ------------------------------------------------------------------ step

    /**
     * One bounded step of the sync thread. Expected storage/Solr failures and
     * RuntimeExceptions are handled here; Errors propagate to the runtime's
     * terminal task boundary. They are not safe to retry automatically.
     *
     * @return true if more work is due at once
     */
    public boolean step() {
        if (this.stopping) {
            return false;
        }
        try {
            if (!this.initialized) {
                init();
            }
            final long resets = this.dirty.fullResets();
            if (resets != this.handledResets) {
                this.handledResets = resets;
                this.counters.fullResets.incrementAndGet();
                if (this.reset == null) {
                    this.reset = new FullReset(this.store, this.clock, false);
                } else {
                    this.reset.restart();
                }
                this.resetting = true;
            }
            if (this.resetting) {
                this.state = "resetting";
                if (this.reset.step()) {
                    this.reset = null;
                    this.resetting = false;
                    this.state = "running";
                    this.reconciler.forgetRun();
                    this.reconciler.request(Reconciler.REASON_FULL_RESET, this.clock.getAsLong());
                }
                return true;
            }
            final long now = this.clock.getAsLong();
            if (now - this.lastDrain >= DRAIN_MILLIS || this.dirty.size() >= DRAIN_BATCH) {
                drain(now);
            }
            if (this.dirty.takeOverflow()) {
                this.reconciler.lost(Reconciler.REASON_OVERFLOW, now);
            }
            final long qd = this.dirty.queryDeletes();
            if (qd != this.handledQueryDeletes) {
                this.handledQueryDeletes = qd;
                this.reconciler.requestDebounced(Reconciler.REASON_QUERY_DELETE, now + this.cfg.reconcileDebounceMillis);
            }
            boolean more = this.reconciler.step();
            more |= processBatch();
            more |= this.retention.step();
            if (!more && this.reconciler.waitsForQueueRoom()
                    && this.store.read(WorkQueue::size) < Math.max(1L, this.queueMax / 2L)) {
                // the queue drained: the reconcile it postponed picks up what did not fit
                this.reconciler.queueHasRoom(this.clock.getAsLong());
            }
            checkpoint(this.clock.getAsLong());
            this.lastError = null;
            return more;
        } catch (final KgException e) {
            // refused or failed write: recorded by the guard; the step is retried
            this.lastError = e.code() + (e.reason() == null ? "" : ": " + e.reason());
            if (!this.initialized) {
                this.state = "waiting";
                this.reason = this.lastError;
            }
            this.terms.forgetCollections();
            return false;
        } catch (final RuntimeException e) {
            this.lastError = e.getClass().getSimpleName();
            LOG.warn("knowledge graph sync step failed: " + e);
            this.terms.forgetCollections();
            return false;
        }
    }

    // ----------------------------------------------------------------- drain

    /** Persists the change set into {@code kg_work}; a refused batch goes back into the set. */
    private void drain(final long now) {
        this.lastDrain = now;
        for (int i = 0; i < DRAIN_BATCHES_PER_STEP; i++) {
            final List<DirtySet.Event> events = this.dirty.drain(DRAIN_BATCH);
            if (events.isEmpty()) {
                break;
            }
            final WorkQueue.Added a;
            boolean persisted = false;
            try {
                a = this.store.write(WriteClass.MAINTENANCE, SMALL + events.size() * 256L,
                        tx -> WorkQueue.events(tx, events, now, this.queueMax));
                persisted = true;
            } catch (final KgException e) {
                this.counters.drainRefused.incrementAndGet();
                return;
            } finally {
                // The store rolls back RuntimeException/Error as well. Keep
                // the captured batch even when a terminal task error escapes.
                if (!persisted) {
                    this.dirty.restore(events);
                }
            }
            this.counters.drained.addAndGet(events.size());
            this.nextClaimAt = 0L;
            if (a.dropped > 0) {
                this.counters.queueDropped.addAndGet(a.dropped);
                this.reconciler.lost(Reconciler.REASON_QUEUE_FULL, now);
            }
        }
    }

    /** Ends the current batch early (stop); the step returns soon after. */
    public void onLlmInput(final Runnable wake) {
        this.llmWake = wake;
    }

    public void requestStop() {
        this.stopping = true;
    }

    /** Final drain at close (the sync thread has stopped): pending events reach the persistent queue. */
    public void finalDrain(final long deadlineMillis) {
        if (!this.initialized) {
            return;
        }
        while (this.clock.getAsLong() < deadlineMillis && this.dirty.size() > 0) {
            final long before = this.counters.drainRefused.get();
            drain(this.clock.getAsLong());
            if (this.counters.drainRefused.get() != before) {
                return; // refused: the next start reconciles anyway
            }
        }
    }

    // --------------------------------------------------------------- process

    private enum Action { NONE, REMOVE, LIFECYCLE, EXTRACT }

    private boolean processBatch() throws KgException {
        final long now = this.clock.getAsLong();
        if (now < this.nextClaimAt) {
            return false;
        }
        this.gateClosed = this.gates.closed(false);
        // asked before any work starts: a pause (or the budget, disk or integrity) stops extraction, not only its writes
        this.growthRefusal = this.store.growthRefusal();
        final boolean extractOk = now >= this.growthBlockedUntil && this.gateClosed == null && this.growthRefusal == null;
        // while growth is blocked, delete events run at once and the rest once per interval (lifecycle is only rate-limited)
        final boolean deletesOnly = !extractOk && now - this.lastBlockedBatch < BLOCKED_BATCH_MILLIS;
        if (!extractOk && !deletesOnly) {
            this.lastBlockedBatch = now;
        }
        final long claim = Math.max(now, this.claimSeq + 1L);
        this.claimSeq = claim;
        final List<WorkQueue.Item> items;
        try {
            items = this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> WorkQueue.claim(tx, CLAIM_BATCH, now, claim, deletesOnly));
        } catch (final KgException e) {
            this.counters.maintenanceRefused.incrementAndGet();
            this.nextClaimAt = now + REFUSED_RETRY_MILLIS;
            return false;
        }
        if (items.isEmpty()) {
            this.nextClaimAt = now + IDLE_CLAIM_MILLIS;
            return false;
        }
        final List<String> ids = new ArrayList<>(items.size());
        for (final WorkQueue.Item i : items) {
            ids.add(i.docId);
        }
        Map<String, SolrDoc> docs;
        try {
            docs = this.solr.get(ids, SolrDoc.PROCESS_FIELDS);
        } catch (final IOException e) {
            solrFailed(e);
            releaseAll(items, now + SOLR_RETRY_MILLIS);
            this.nextClaimAt = now + SOLR_RETRY_MILLIS;
            return false;
        }
        this.state = "running";
        this.reason = null;
        final long deadline = now + BATCH_BUDGET_MILLIS;
        final Iterator<WorkQueue.Item> it = items.iterator();
        while (it.hasNext()) {
            final WorkQueue.Item item = it.next();
            if (this.clock.getAsLong() > deadline || this.stopping) {
                break; // the rest is released by the next claim
            }
            if (!process(item, docs.get(item.docId), extractOk)) {
                break;
            }
        }
        return true;
    }

    private void solrFailed(final IOException e) {
        this.counters.solrErrors.incrementAndGet();
        if (e instanceof EmbeddedSolrSource.Unsupported) {
            this.state = "unavailable";
            this.reason = "remote_solr_unsupported";
        } else {
            this.lastError = "solr: " + e.getMessage();
        }
    }

    /** @return false to end the batch (a maintenance write was refused or Solr failed) */
    private boolean process(final WorkQueue.Item item, final SolrDoc d, final boolean extractOk) throws KgException {
        final long now = this.clock.getAsLong();
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, item.docId));
        if (d == null) {
            if (item.reason == WorkQueue.REASON_ADD && item.attempts < MAX_LOOKUPS - 1) {
                // an add event whose document real-time get does not return: a delete event follows, or it is not visible yet
                this.counters.notVisible.incrementAndGet();
                return release(item, now + backoff(item.attempts), true);
            }
            return remove(item, cur);
        }
        if (item.reason == WorkQueue.REASON_ADD && d.version < item.eventVersion && item.attempts < MAX_LOOKUPS - 1) {
            this.counters.notVisible.incrementAndGet();
            return release(item, now + backoff(item.attempts), true);
        }
        final List<String> followed = d.followed(this.cfg);
        if (followed.isEmpty()) {
            // Classification remains security-relevant even when extraction is disabled.
            this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
                net.yacy.scoutro.knowledge.store.Observations.classify(tx,d.id,d.collections);
                return null;
            });
            if (d.heldOnly(this.cfg)) {
                // a collection switched off (package 6.2): its graph data stay as they are, nothing is extracted
                this.counters.held.incrementAndGet();
                return complete(item);
            }
            return remove(item, cur); // out of scope
        }
        final int state = d.state();
        if (cur == null && state != Aggregates.STATE_ACTIVE) {
            // a fail document of a URL the graph never saw active: nothing to track
            this.counters.untracked.incrementAndGet();
            return complete(item);
        }
        // the page keeps its membership in a collection switched off; the extraction reads the followed ones only
        final Publisher.Doc doc = toDoc(d, d.kept(this.cfg), state);
        final byte[] input = d.inputHash();
        final Action action;
        if (state != Aggregates.STATE_ACTIVE) {
            doc.inputHash = null; // keeps the stored hash: an unchanged return needs no new extraction
            action = Action.LIFECYCLE;
        } else if (cur != null && Arrays.equals(input, cur.inputHash) && !this.recheckRelations.contains(item.docId)) {
            doc.inputHash = input;
            action = Action.LIFECYCLE;
        } else {
            doc.inputHash = input;
            action = Action.EXTRACT;
        }
        if (action == Action.LIFECYCLE && cur != null && cur.state != Aggregates.STATE_ACTIVE && state == Aggregates.STATE_ACTIVE
                && StorageGuard.MANUAL.equals(this.growthRefusal)) {
            // a page that is back makes its facts current again: enrichment, held back until the resume
            this.counters.deferred.incrementAndGet();
            return release(item, now + GROWTH_RETRY_MILLIS, false);
        }
        Extraction ex = null;
        if (action == Action.EXTRACT) {
            if (!extractOk) {
                this.counters.deferred.incrementAndGet();
                return release(item, now + (this.gateClosed != null ? GATE_RETRY_MILLIS : GROWTH_RETRY_MILLIS), false);
            }
            try {
                final BaseTiers.Text text = new BaseTiers.Text(this.solr, d.id);
                ex = extract(d, text);
                if (this.recheckRelations.contains(item.docId)) {
                    final long[] audit = llmJobAudit(d, text);
                    if (audit != null) {
                        this.llmJobAudit.put(item.docId, audit);
                    }
                }
            } catch (final IOException e) {
                solrFailed(e);
                release(item, now + SOLR_RETRY_MILLIS, false);
                return false;
            } catch (final RuntimeException e) {
                // a defect of the extraction of this page: it counts against the page, the pages behind it go on
                return failed(item, "extract_failed", e);
            }
        }
        return publish(item, doc, ex, action == Action.EXTRACT ? WriteClass.GROWTH : WriteClass.MAINTENANCE);
    }

    private static long backoff(final int attempts) {
        return 2000L << Math.min(6, attempts);
    }

    private Publisher.Doc toDoc(final SolrDoc d, final List<String> followed, final int state) {
        final Publisher.Doc doc = new Publisher.Doc();
        doc.docId = d.id;
        doc.url = d.url;
        doc.host = d.host != null ? d.host : host(d.url);
        doc.hostId = d.hostId;
        doc.language = d.language;
        doc.state = state;
        doc.token = d.token();
        doc.solrVersion = d.version;
        doc.loadedAt = d.loadDate;
        doc.collections = followed;
        doc.accessCollections = d.collections;
        doc.jsonldBytes = d.jsonLdBytes();
        doc.jsonldSkipped = d.ldJson.isEmpty() && JsonLdCapture.wasSkipped(d.id);
        doc.contentHash = d.contentHash();
        doc.legacyInputHash = d.inputHash(SolrDoc.EXTRACTORS_V1);
        doc.linkDomains = d.linkDomains();
        return doc;
    }

    private static String host(final String url) {
        try {
            return url == null ? null : new URI(url).getHost();
        } catch (final Exception e) {
            return null;
        }
    }

    /** Tiers 1 and 2; the text is read only for tier-2 candidates. */
    private Extraction extract(final SolrDoc d) throws IOException {
        return extract(d, new BaseTiers.Text(this.solr, d.id));
    }

    private Extraction extract(final SolrDoc d, final BaseTiers.Text text) throws IOException {
        this.counters.extractions.incrementAndGet();
        return this.tiers.extract(d, text);
    }

    /**
     * The job evidence of the LLM tier on a re-checked page (0.10.5), counted, not changed: {menu evidence, position evidence,
     * other evidence, menu jobs}, or null if the page has none. A job of the model is a menu entry where the rules see its
     * title on the page only as an entry of a list of titles ({@link ExtractContext#noPosting}), as the LLM tier now drops it.
     */
    private long[] llmJobAudit(final SolrDoc d, final BaseTiers.Text text) throws IOException {
        final List<Object[]> jobs;
        final ExtractContext titles;
        try {
            jobs = this.store.read(c -> tier3Jobs(c, d.id));
            if (jobs.isEmpty()) {
                return null;
            }
            titles = ExtractContext.none().withJobTitles(BusinessRules.jobTitleKeys(text.get(), d.url, d.titles));
        } catch (final KgException | RuntimeException e) {
            return null; // a diagnosis only: the page and the re-check go on without it
        }
        final long[] out = new long[4];
        for (final Object[] job : jobs) {
            final String name = (String) job[0];
            final long evidence = (Long) job[1];
            if (titles.noPosting(name)) {
                out[0] += evidence;
                out[3]++;
            } else if (titles.posting(name)) {
                out[1] += evidence;
            } else {
                out[2] += evidence;
            }
        }
        return out;
    }

    /** The jobs with evidence of the LLM tier on a page: {their name on it, their tier-3 evidence rows on it}. */
    private static List<Object[]> tier3Jobs(final java.sql.Connection c, final String docId) throws java.sql.SQLException {
        final List<Object[]> out = new ArrayList<>();
        try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT min(nm.obj_val), (SELECT count(*) FROM kg_evidence x"
                + " JOIN kg_statement xs ON xs.stmt_rowid = x.stmt_rowid WHERE x.doc_rowid = d.doc_rowid AND x.tier = 3 AND xs.subj = n.ent_rowid)"
                + " FROM kg_doc d JOIN kg_evidence e ON e.doc_rowid = d.doc_rowid AND e.tier = 3 JOIN kg_statement nm ON nm.stmt_rowid = e.stmt_rowid"
                + " JOIN kg_vocab nv ON nv.term_id = nm.pred JOIN kg_entity n ON n.ent_rowid = nm.subj JOIN kg_vocab t ON t.term_id = n.type"
                + " WHERE d.doc_id = ? AND nv.name = ? AND t.name = ? AND nm.obj_val IS NOT NULL GROUP BY n.ent_rowid")) {
            ps.setString(1, docId);
            ps.setString(2, Vocabulary.NAME);
            ps.setString(3, Vocabulary.JOB);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Object[] {rs.getString(1), rs.getLong(2)});
                }
            }
        }
        return out;
    }

    /** {@link #META_RECHECK_LLM_JOBS} as stored; empty if none or unreadable. */
    private static JSONObject llmJobAudit(final java.sql.Connection c) throws java.sql.SQLException {
        try {
            final String stored = KgStore.getMeta(c, META_RECHECK_LLM_JOBS);
            return stored == null ? new JSONObject() : new JSONObject(stored);
        } catch (final org.json.JSONException e) {
            return new JSONObject();
        }
    }

    /** Adds the audit of a published page to {@link #META_RECHECK_LLM_JOBS}; returns the new totals. */
    private static JSONObject addLlmJobAudit(final java.sql.Connection tx, final long[] audit) throws java.sql.SQLException {
        final JSONObject total = llmJobAudit(tx);
        final String[] keys = {"menuEvidence", "positionEvidence", "otherEvidence", "menuJobs"};
        for (int i = 0; i < keys.length; i++) {
            KgJson.put(total, keys[i], total.optLong(keys[i]) + audit[i]);
        }
        KgJson.put(total, "pages", total.optLong("pages") + 1);
        KgStore.putMeta(tx, META_RECHECK_LLM_JOBS, total.toString());
        return total;
    }

    /**
     * The publish transaction: right before it, under the write lock, the
     * change set must not hold a newer event and no full clear may have
     * arrived; inside it, the publisher checks the generation and the version.
     */
    private boolean publish(final WorkQueue.Item item, final Publisher.Doc doc, final Extraction ex, final WriteClass cls)
            throws KgException {
        final long now = this.clock.getAsLong();
        final long resetMark = this.handledResets;
        final Object[] outcome = new Object[2];
        try {
            this.store.write(cls, cls == WriteClass.GROWTH ? PUBLISH_ESTIMATE : SMALL, tx -> {
                if (this.dirty.hasNewer(item.docId, doc.solrVersion) || this.dirty.fullResets() != resetMark) {
                    WorkQueue.release(tx, item, now, false);
                    outcome[0] = "superseded";
                    return null;
                }
                final Publisher.Result r = this.publisher.apply(tx, doc, item.generation, ex, now);
                if (r.outcome == Publisher.Outcome.ABORT_GENERATION) {
                    WorkQueue.release(tx, item, now, false);
                } else {
                    WorkQueue.complete(tx, item);
                }
                final long[] audit = r.outcome == Publisher.Outcome.PUBLISHED ? this.llmJobAudit.get(item.docId) : null;
                outcome[1] = audit == null ? null : addLlmJobAudit(tx, audit);
                outcome[0] = r.outcome;
                return null;
            });
            if (outcome[1] != null) {
                this.llmJobAudit.remove(item.docId);
                this.llmJobs = (JSONObject) outcome[1];
            }
        } catch (final KgException e) {
            this.terms.forgetCollections();
            return refused(item, e, cls);
        }
        if ("superseded".equals(outcome[0])) {
            this.counters.supersededAborts.incrementAndGet();
            return true;
        }
        switch ((Publisher.Outcome) outcome[0]) {
            case PUBLISHED:
                this.recheckRelations.remove(item.docId);
                this.counters.published.incrementAndGet();
                JsonLdCapture.synced(item.docId);
                final Runnable wake = this.llmWake;
                if (wake != null && doc.state == Aggregates.STATE_ACTIVE && llmCollection(doc.collections)) {
                    wake.run();
                }
                break;
            case UNCHANGED:
                this.recheckRelations.remove(item.docId);
                this.counters.unchanged.incrementAndGet();
                JsonLdCapture.synced(item.docId);
                break;
            case LIFECYCLE:
                this.counters.lifecycle.incrementAndGet();
                JsonLdCapture.synced(item.docId);
                break;
            case ABORT_GENERATION:
                this.counters.generationAborts.incrementAndGet();
                break;
            default:
                this.counters.olderAborts.incrementAndGet();
                break;
        }
        return true;
    }

    private boolean llmCollection(final List<String> collections) {
        for (final String c : collections) {
            if (this.cfg.llmFollows(c)) {
                return true;
            }
        }
        return false;
    }

    private boolean remove(final WorkQueue.Item item, final Publisher.Row cur) throws KgException {
        if (cur == null) {
            this.counters.untracked.incrementAndGet();
            JsonLdCapture.synced(item.docId);
            return complete(item);
        }
        final boolean[] removed = new boolean[1];
        try {
            this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
                // a pending event (a re-add after the read) decides instead
                if (this.dirty.hasNewer(item.docId, 0L)) {
                    WorkQueue.release(tx, item, this.clock.getAsLong(), false);
                    return null;
                }
                final int n = this.publisher.remove(tx, Collections.singletonList(item.docId),
                        Collections.singletonMap(item.docId, item.generation), this.clock.getAsLong());
                if (n == 0 && Publisher.row(tx, item.docId) != null) {
                    WorkQueue.release(tx, item, this.clock.getAsLong(), false); // generation moved on
                } else {
                    WorkQueue.complete(tx, item);
                    removed[0] = true;
                }
                return null;
            });
        } catch (final KgException e) {
            return refused(item, e, WriteClass.MAINTENANCE);
        }
        if (removed[0]) {
            this.counters.removed.incrementAndGet();
            JsonLdCapture.synced(item.docId);
        } else {
            this.counters.generationAborts.incrementAndGet();
        }
        return true;
    }

    private boolean complete(final WorkQueue.Item item) throws KgException {
        try {
            this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> WorkQueue.complete(tx, item));
            return true;
        } catch (final KgException e) {
            return refused(item, e, WriteClass.MAINTENANCE);
        }
    }

    private boolean release(final WorkQueue.Item item, final long notBefore, final boolean attempt) {
        try {
            this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
                WorkQueue.release(tx, item, notBefore, attempt);
                return null;
            });
            return true;
        } catch (final KgException e) {
            this.counters.maintenanceRefused.incrementAndGet();
            this.nextClaimAt = this.clock.getAsLong() + REFUSED_RETRY_MILLIS;
            return false;
        }
    }

    private void releaseAll(final List<WorkQueue.Item> items, final long notBefore) {
        try {
            this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
                for (final WorkQueue.Item i : items) {
                    WorkQueue.release(tx, i, notBefore, false);
                }
                return null;
            });
        } catch (final KgException e) {
            this.counters.maintenanceRefused.incrementAndGet();
        }
    }

    /**
     * A refused growth write blocks new extraction for a while and keeps the
     * item; a refused maintenance write ends the batch. Any other failure
     * counts against the item; after {@link #MAX_FAILURES} it is dropped
     * (visible in the status, retried by the next reconcile).
     */
    private boolean refused(final WorkQueue.Item item, final KgException e, final WriteClass cls) {
        final long now = this.clock.getAsLong();
        if (KgException.WRITE_REFUSED.equals(e.code()) || KgException.STORAGE_FULL.equals(e.code())) {
            if (cls == WriteClass.GROWTH) {
                this.counters.growthRefused.incrementAndGet();
                this.growthBlockedUntil = now + GROWTH_RETRY_MILLIS;
                this.counters.deferred.incrementAndGet();
                return release(item, now + GROWTH_RETRY_MILLIS, false);
            }
            this.counters.maintenanceRefused.incrementAndGet();
            this.nextClaimAt = now + REFUSED_RETRY_MILLIS;
            return false;
        }
        return failed(item, e.code(), e);
    }

    /**
     * A failure of this item alone: released with backoff and counted; after {@link #MAX_FAILURES} it is dropped with event
     * {@code doc_failed} (visible in the status, retried by the next reconcile or change). The last attempt logs the trace.
     */
    private boolean failed(final WorkQueue.Item item, final String code, final Exception e) {
        final long now = this.clock.getAsLong();
        final String message = e instanceof KgException ? e.getMessage() : e.toString();
        this.lastError = code + ": " + message;
        final boolean last = item.attempts + 1 >= MAX_FAILURES;
        if (last && !(e instanceof KgException)) {
            LOG.warn("knowledge graph: processing " + item.docId + " failed: " + code + " " + message, e);
        } else {
            LOG.warn("knowledge graph: processing " + item.docId + " failed: " + code + " " + message);
        }
        if (last) {
            this.counters.failedDocs.incrementAndGet();
            try {
                this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
                    WorkQueue.complete(tx, item);
                    KgStore.event(tx, 2, "doc_failed", item.docId + ": " + code, now);
                    return null;
                });
            } catch (final KgException ignored) {
                // stays queued
            }
            return true;
        }
        return release(item, now + backoff(item.attempts + 2), true);
    }

    // ------------------------------------------------------------ checkpoint

    /**
     * Every minute after a complete drain: stores the highest Solr version
     * seen at least 30 s earlier. After an unclean stop, every document at or
     * above it is enqueued first ({@code _version_} catch-up).
     */
    private void checkpoint(final long now) {
        if (now - this.lastSample >= SAMPLE_EVERY_MILLIS) {
            this.lastSample = now;
            this.samples.addLast(new long[] {now, this.dirty.maxVersion()});
            while (this.samples.size() > 64) {
                this.samples.removeFirst();
            }
        }
        if (now - this.lastCheckpoint < CHECKPOINT_EVERY_MILLIS || this.dirty.size() > 0) {
            return;
        }
        long version = 0L;
        for (final long[] s : this.samples) {
            if (s[0] <= now - CHECKPOINT_LAG_MILLIS) {
                version = Math.max(version, s[1]);
            }
        }
        if (version <= this.versionCheckpoint) {
            return;
        }
        final long v = version;
        try {
            this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
                KgStore.putMeta(tx, KgSchema.META_VERSION_CHECKPOINT, Long.toString(v));
                return null;
            });
            this.versionCheckpoint = v;
            this.lastCheckpoint = now;
        } catch (final KgException e) {
            // next minute
        }
    }

    // --------------------------------------------------------------- control

    /** Requests a full reconcile ({@code POST /kg/control {"action":"reconcile"}}, resume). */
    public void requestReconcile(final String reason) {
        this.reconciler.request(reason, this.clock.getAsLong());
    }

    /** Lets a reconcile stopped by the mass-deletion brake delete. */
    public void confirmReconcile() throws KgException {
        this.reconciler.confirm();
    }

    // ---------------------------------------------------------------- status

    public JSONObject status() {
        Long queued = null;
        Long oldest = null;
        try {
            final long[] q = this.store.read(c -> new long[] {WorkQueue.size(c), WorkQueue.oldest(c)});
            queued = q[0];
            oldest = q[1] > 0L ? Math.max(0L, (this.clock.getAsLong() - q[1]) / 1000L) : 0L;
        } catch (final KgException e) {
            // unknown
        }
        final long pending = (queued == null ? 0L : queued) + this.dirty.size();
        final long t = this.clock.getAsLong();
        if (this.pendingByType == null || t - this.pendingAt >= PENDING_EVERY_MILLIS) {
            try {
                final long[] k = this.store.read(WorkQueue::pendingByType);
                this.pendingByType = KgJson.obj("new", k[0], "update", k[1], "delete", k[2], "reconcile", k[3],
                        "captured", (long) this.dirty.size());
                this.pendingAt = t;
            } catch (final KgException e) {
                // unknown: the last count stays
            }
        }
        return KgJson.obj("state", this.state, "reason", this.reason, "initialized", this.initialized,
                "resetInProgress", this.resetting, "upgradeHold", this.upgradeHold, "lastError", this.lastError, "gate", this.gateClosed,
                "growthBlocked", this.clock.getAsLong() < this.growthBlockedUntil || this.growthRefusal != null,
                "growthRefusal", this.growthRefusal,
                "changes", this.dirty.status(), "queue", KgJson.obj("items", queued, "maxItems", this.queueMax,
                        "oldestAgeSeconds", oldest),
                "processed", this.counters.json(), "versionCheckpoint", this.versionCheckpoint > 0L ? this.versionCheckpoint : null,
                "reconcile", this.reconciler.status(), "relationRecheck", this.recheckRelations.size(),
                "relationRecheckLlmJobs", this.llmJobs, "retention", this.retention.status(),
                "lag", KgJson.obj("pending", pending, "oldest_pending_age_s", oldest, "reconcile_pending", this.reconciler.pending(),
                        "byType", this.pendingByType));
    }

    /**
     * Ends the wait of an upgrade without its copy once a verified backup
     * exists: records the new extractor identity and re-extracts every page
     * with the current vocabulary (low priority, behind new pages, paused like
     * every enrichment). Returns false if nothing was held.
     */
    public boolean releaseUpgradeHold() throws KgException {
        if (this.upgradeHold == null || !this.initialized) {
            return false;
        }
        final long now = this.clock.getAsLong();
        final String extractors = this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
            final String last = KgStore.getMeta(tx, KgSchema.META_EXTRACTORS);
            KgStore.deleteMeta(tx, KgSchema.META_UPGRADE_HOLD);
            KgStore.putMeta(tx, KgSchema.META_EXTRACTORS, SolrDoc.extractors());
            KgStore.event(tx, 1, "upgrade_released", "a verified backup exists; every page is extracted again", now);
            return last;
        });
        this.upgradeHold = null;
        if (extractors == null || !extractors.equals(SolrDoc.extractors())) {
            this.reconciler.reextractAll();
            this.reconciler.request(Reconciler.REASON_EXTRACTORS, now);
        }
        return true;
    }

    /** Why the upgrade's re-extraction waits; null if it does not. */
    public String upgradeHold() {
        return this.upgradeHold;
    }

    // ----------------------------------------------------------------- tests

    /** For tests: a smaller queue cap than the configuration allows. */
    void queueMax(final long max) {
        this.queueMax = max;
        this.reconciler.queueMax = max;
    }

    Reconciler reconciler() {
        return this.reconciler;
    }

    Retention retention() {
        return this.retention;
    }

    public boolean initialized() {
        return this.initialized;
    }

    boolean resetting() {
        return this.resetting;
    }
}
