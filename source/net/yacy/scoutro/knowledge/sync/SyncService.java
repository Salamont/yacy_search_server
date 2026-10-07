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
import net.yacy.scoutro.knowledge.extract.Extraction;
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
            WorkQueue.resetClaims(tx);
            final String collections = KgStore.getMeta(tx, KgSchema.META_COLLECTIONS);
            final String extractors = KgStore.getMeta(tx, KgSchema.META_EXTRACTORS);
            final String hold = KgStore.getMeta(tx, KgSchema.META_UPGRADE_HOLD);
            if (hold == null) {
                KgStore.putMeta(tx, KgSchema.META_EXTRACTORS, SolrDoc.extractors());
            } // an upgrade without its copy: the old identity stays, so the re-extraction runs once the hold is released
            this.reconciler.onStart(tx, now);
            return new String[] {collections, extractors, KgStore.getMeta(tx, KgSchema.META_RESET_IN_PROGRESS),
                    KgStore.getMeta(tx, KgSchema.META_VERSION_CHECKPOINT), hold};
        });
        final boolean collectionsChanged = meta[0] != null && !meta[0].equals(this.cfg.collectionsKey());
        final boolean extractorsChanged = meta[1] != null && !meta[1].equals(SolrDoc.extractors());
        this.upgradeHold = meta[4];
        this.reconciler.request(collectionsChanged ? Reconciler.REASON_COLLECTIONS
                : this.uncleanStart ? Reconciler.REASON_UNCLEAN_START : Reconciler.REASON_START, now);
        if (extractorsChanged && this.upgradeHold == null) {
            this.reconciler.reextractAll();
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
     * One bounded step of the sync thread. Never throws.
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
            try {
                a = this.store.write(WriteClass.MAINTENANCE, SMALL + events.size() * 256L,
                        tx -> WorkQueue.events(tx, events, now, this.queueMax));
            } catch (final KgException e) {
                this.dirty.restore(events);
                this.counters.drainRefused.incrementAndGet();
                return;
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
        } else if (cur != null && Arrays.equals(input, cur.inputHash)) {
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
                ex = extract(d);
            } catch (final IOException e) {
                solrFailed(e);
                release(item, now + SOLR_RETRY_MILLIS, false);
                return false;
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
        this.counters.extractions.incrementAndGet();
        return this.tiers.extract(d, new BaseTiers.Text(this.solr, d.id));
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
        final Object[] outcome = new Object[1];
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
                outcome[0] = r.outcome;
                return null;
            });
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
                this.counters.published.incrementAndGet();
                JsonLdCapture.synced(item.docId);
                final Runnable wake = this.llmWake;
                if (wake != null && doc.state == Aggregates.STATE_ACTIVE && llmCollection(doc.collections)) {
                    wake.run();
                }
                break;
            case UNCHANGED:
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
        this.lastError = e.code() + ": " + e.getMessage();
        LOG.warn("knowledge graph: processing " + item.docId + " failed: " + e.code() + " " + e.getMessage());
        if (item.attempts + 1 >= MAX_FAILURES) {
            this.counters.failedDocs.incrementAndGet();
            try {
                this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
                    WorkQueue.complete(tx, item);
                    KgStore.event(tx, 2, "doc_failed", item.docId + ": " + e.code(), now);
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
                "reconcile", this.reconciler.status(), "retention", this.retention.status(),
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
