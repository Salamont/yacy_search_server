/*
 *  LlmService
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
import java.net.SocketTimeoutException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.json.JSONObject;

import net.yacy.ai.PromptGuard;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.KnowledgePrompt;
import net.yacy.scoutro.knowledge.extract.LlmBreaker;
import net.yacy.scoutro.knowledge.extract.LlmClient;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.RequestBudget;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.scoutro.knowledge.store.KgSchema;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The LLM tier (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3 to 6.5), run by the
 * {@code ScoutroKG.extract} threads. Optional: without a model selected for
 * the usage {@code knowledge}, or without {@code llm.collections}, it does
 * nothing and the graph is built from tiers 1 and 2 alone.
 * <p>
 * One step handles at most one document:
 * <ol>
 * <li>the scan queues documents to do of the LLM collections, bounded by the
 * queue size and the per-host cap, by priority (imprint and about pages,
 * services, locations, home page);</li>
 * <li>the worker reads the document from Solr, checks that its input is
 * still the one the graph published, reads tiers 1 and 2 again as the
 * page's known entities, and asks the model chunk by chunk (cache first);
 * nothing is locked while it waits for the model;</li>
 * <li>each answer is validated ({@link LlmExtractor#validate}): what is not
 * quoted verbatim from the page is dropped;</li>
 * <li>the result is published in one transaction with a compare-and-set on
 * the input hash ({@link Publisher#applyLlm}).</li>
 * </ol>
 * A transport failure (timeout, refused connection, HTTP error) counts for
 * the circuit breaker and as an attempt; after {@code llm.maxAttempts} the
 * document is marked failed until its content changes or an admin retries.
 * An invalid answer is no transport failure: it is counted and cached, so the
 * same text is not asked again. The worker never retries without bound, never
 * blocks tiers 1 and 2, and holds no lock while a call runs.
 */
public final class LlmService {

    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-KG");

    static final int QUEUE_MAX = 10_000;
    static final int QUEUE_LOW = 100;
    static final int SCAN_BATCH = 200;
    /** Of every scan pass, at most this many pages come from the revisits behind the frontiers. */
    static final int REVISIT_BATCH = 50;
    /** After a revisit swept up to its frontier, the next sweep of that collection waits this long. */
    static final long REVISIT_PAUSE_MILLIS = 10L * 60_000L;
    /** The refill counts items due within this time; items waiting in a longer backoff do not hold it back. */
    static final long READY_HORIZON_MILLIS = 60_000L;
    static final long SCAN_PAUSE_MILLIS = 10_000L;
    static final long IDLE_MILLIS = 5_000L;
    static final long NOT_CONFIGURED_MILLIS = 30_000L;
    static final long GATE_MILLIS = 10_000L;
    static final long REFUSED_MILLIS = 60_000L;
    static final long SOLR_RETRY_MILLIS = 30_000L;
    static final long CHANGED_RETRY_MILLIS = 60_000L;
    /** Retries beyond {@code llm.maxAttempts} for an input the worker cannot confirm, and their longest backoff. */
    static final int UNCONFIRMED_EXTRA_ATTEMPTS = 6;
    /** Reason of a document whose request does not fit the model's context window even split. */
    static final String CONTEXT_BUDGET = "context_budget";
    static final long UNCONFIRMED_MAX_BACKOFF_MILLIS = 30L * 60_000L;
    static final long FIRST_RETRY_MILLIS = 60_000L;
    static final long COUNTS_MILLIS = 30_000L;
    static final long EVICT_MILLIS = 60_000L;
    private static final long SMALL = 64L * 1024L;
    private static final long PUBLISH_ESTIMATE = 512L * 1024L;

    /**
     * Counters for the status: in memory, per instance, since the start (nothing is persisted or rebuilt). The item
     * counters (entities, claims, values, dropped) count the items of the answers of fresh calls, not cache hits.
     */
    static final class Counters {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong requestStarts = new AtomicLong();
        final AtomicLong callFailures = new AtomicLong();
        final AtomicLong timeouts = new AtomicLong();
        final AtomicLong callMillis = new AtomicLong();
        /** Model requests that have ended (retries included); {@link #callMillis} holds their measured time. */
        final AtomicLong requestsFinished = new AtomicLong();
        final AtomicLong answersAccepted = new AtomicLong();
        final AtomicLong answersRefused = new AtomicLong();
        final Map<String, AtomicLong> refusedBy = new ConcurrentHashMap<>();
        final AtomicLong entities = new AtomicLong();
        final AtomicLong claims = new AtomicLong();
        final AtomicLong values = new AtomicLong();
        final AtomicLong droppedUngrounded = new AtomicLong();
        /** Dropped items, not refused answers; with its reasons, guarded by itself so the reasons always add up to it. */
        final AtomicLong droppedInvalid = new AtomicLong();
        final Map<String, AtomicLong> droppedInvalidByReason = new ConcurrentHashMap<>();
        final AtomicLong cacheHits = new AtomicLong();
        final AtomicLong cacheMisses = new AtomicLong();
        final AtomicLong cacheWritesRefused = new AtomicLong();
        final AtomicLong published = new AtomicLong();
        final AtomicLong statements = new AtomicLong();
        final AtomicLong changedAborts = new AtomicLong();
        /** Items kept in the queue with a backoff because the input could not be confirmed (never dropped unmarked). */
        final AtomicLong unconfirmedRetries = new AtomicLong();
        /** Documents marked failed ({@link LlmQueue#INPUT_UNCONFIRMED}) after the bounded retries. */
        final AtomicLong inputUnconfirmed = new AtomicLong();
        final AtomicLong failedDocs = new AtomicLong();
        final AtomicLong skippedNotCandidate = new AtomicLong();
        final AtomicLong skippedNotSelected = new AtomicLong();
        final AtomicLong skippedHostCap = new AtomicLong();
        final AtomicLong queued = new AtomicLong();
        /** Pages the revisits found to do again behind a frontier, without a restart. */
        final AtomicLong requeuedBehindFrontier = new AtomicLong();
        /** Scan passes that refilled the queue. */
        final AtomicLong scanPasses = new AtomicLong();
        final AtomicLong growthRefused = new AtomicLong();
        /** Known entities left out of a request because the full list did not fit (not named in the chunk). */
        final AtomicLong knownTrimmed = new AtomicLong();
        /** Chunks split so a request fits the context window. */
        final AtomicLong chunksSplit = new AtomicLong();
        /** Documents not asked because a request does not fit even split ({@link #CONTEXT_BUDGET}). */
        final AtomicLong contextRefused = new AtomicLong();
        /** The largest estimated prompt of a planned request. */
        final AtomicLong promptTokensEstimatedMax = new AtomicLong();
        /** Long pages whose recurring navigation was moved behind their content, and the characters moved. */
        final AtomicLong navigationMoved = new AtomicLong();
        final AtomicLong navigationMovedChars = new AtomicLong();

        Counters() {
            for (final String reason : LlmExtractor.INVALID_REASONS) {
                this.droppedInvalidByReason.put(reason, new AtomicLong());
            }
        }

        /** The items of one accepted answer. */
        void accepted(final LlmExtractor.Result r) {
            this.answersAccepted.incrementAndGet();
            this.entities.addAndGet(r.entities);
            this.claims.addAndGet(r.claims);
            this.values.addAndGet(r.values);
            this.droppedUngrounded.addAndGet(r.droppedUngrounded);
            synchronized (this.droppedInvalid) {
                this.droppedInvalid.addAndGet(r.droppedInvalid);
                for (final Map.Entry<String, Integer> e : r.droppedInvalidByReason.entrySet()) {
                    this.droppedInvalidByReason.computeIfAbsent(e.getKey(), k -> new AtomicLong()).addAndGet(e.getValue());
                }
            }
        }

        void refused(final String reason) {
            this.answersRefused.incrementAndGet();
            this.refusedBy.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
        }

        JSONObject json() {
            final JSONObject by = new JSONObject();
            for (final Map.Entry<String, AtomicLong> e : this.refusedBy.entrySet()) {
                KgJson.put(by, e.getKey(), e.getValue().get());
            }
            final JSONObject invalidBy = new JSONObject();
            final long invalid;
            synchronized (this.droppedInvalid) {
                invalid = this.droppedInvalid.get();
                for (final Map.Entry<String, AtomicLong> e : this.droppedInvalidByReason.entrySet()) {
                    KgJson.put(invalidBy, e.getKey(), e.getValue().get());
                }
            }
            final long n = this.calls.get();
            final long finished = this.requestsFinished.get(), millis = this.callMillis.get();
            return KgJson.obj(
                    "calls",
                    n,
                    "requestStarts",
                    this.requestStarts.get(),
                    "callFailures",
                    this.callFailures.get(),
                    "timeouts",
                    this.timeouts.get(),
                    "averageCallMillis",
                    n == 0 ? null : this.callMillis.get() / n,
                    "requestsFinished",
                    finished,
                    // measured: only requests that have ended, never a request still running
                    "averageRequestMillis",
                    finished == 0 ? null : millis / finished,
                    "answersAccepted",
                    this.answersAccepted.get(),
                    "answersRefused",
                    this.answersRefused.get(),
                    "refusedBy",
                    by,
                    "entitiesAccepted",
                    this.entities.get(),
                    "claimsAccepted",
                    this.claims.get(),
                    "valuesAccepted",
                    this.values.get(),
                    "droppedUngrounded",
                    this.droppedUngrounded.get(),
                    "droppedInvalid",
                    invalid,
                    "droppedInvalidByReason",
                    invalidBy,
                    "cacheHits",
                    this.cacheHits.get(),
                    "cacheMisses",
                    this.cacheMisses.get(),
                    "cacheWritesRefused",
                    this.cacheWritesRefused.get(),
                    "published",
                    this.published.get(),
                    "statements",
                    this.statements.get(),
                    "abortedChanged",
                    this.changedAborts.get(),
                    "unconfirmedRetries",
                    this.unconfirmedRetries.get(),
                    "inputUnconfirmed",
                    this.inputUnconfirmed.get(),
                    "failedDocs",
                    this.failedDocs.get(),
                    "skippedNotCandidate",
                    this.skippedNotCandidate.get(),
                    "skippedNotSelected",
                    this.skippedNotSelected.get(),
                    "skippedHostCap",
                    this.skippedHostCap.get(),
                    "queued",
                    this.queued.get(),
                    "requeuedBehindFrontier",
                    this.requeuedBehindFrontier.get(),
                    "scanPasses",
                    this.scanPasses.get(),
                    "growthRefused",
                    this.growthRefused.get(),
                    "knownTrimmed",
                    this.knownTrimmed.get(),
                    "chunksSplit",
                    this.chunksSplit.get(),
                    "contextRefused",
                    this.contextRefused.get(),
                    "navigationMoved",
                    this.navigationMoved.get(),
                    "navigationMovedChars",
                    this.navigationMovedChars.get());
        }
    }

    private final KgConfig cfg;
    private final KgStore store;
    private final SolrSource solr;
    private final Gates gates;
    private final LlmClient client;
    private final LongSupplier clock;
    private final LlmBreaker breaker;
    private final LlmTiming timing;
    private static final String LAST_START="llm_last_actual_start";
    private final Map<String,PendingProgress> pendingProgress=new ConcurrentHashMap<>();
    private final Object progressLock=new Object();
    private volatile long pendingDocuments;
    private volatile long pruneAt;
    private static final class PendingProgress {
        LlmQueue.Item item;LlmProgress progress;
        PendingProgress(LlmQueue.Item item,LlmProgress progress){this.item=item;this.progress=progress;}
    }
    private final BaseTiers tiers;
    private final Terms terms = new Terms();
    private final Publisher publisher;
    final Counters counters = new Counters();

    private volatile boolean stopping;
    private volatile boolean initialized;
    private volatile String state = "starting";
    private volatile String reason;
    private volatile String lastError;
    private volatile String model;
    /** The active knowledge prompt (kg_meta, else the compiled-in default); a document is asked with one version throughout. */
    private volatile KnowledgePrompt prompt = KnowledgePrompt.defaults();
    private final Object scanLock = new Object();
    private long scanPausedUntil;
    /** Rotates the collection that is served first, so the shares are fair over the passes. */
    private long scanRound;
    /** When the next revisit sweep of a collection (by coll_id) may start. */
    private final Map<Integer, Long> revisitPausedUntil = new ConcurrentHashMap<>();
    /** Pages queued per collection since the start (the fair shares in the status). */
    private final Map<String, AtomicLong> queuedByCollection = new ConcurrentHashMap<>();
    /** Recurring lines per host and compared pages (a bounded cache: a host's long pages share it). */
    private final Map<String, Set<String>> recurringLines = Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Set<String>>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, Set<String>> eldest) {
                    return size() > 128;
                }
            });
    /** The scan positions as of the last pass (status). */
    private volatile List<LlmQueue.Lane> lanes = Collections.emptyList();
    private volatile long nextAt;
    private volatile long countsAt;
    private volatile long[] docCounts = new long[3];
    private volatile long queueSize;
    private volatile long queueClaimed;
    private volatile Long queueOldest;
    private volatile long cacheBytes = -1L;
    private volatile long cacheEntries;
    private volatile long evictAt;

    public LlmService(final KgConfig cfg, final KgStore store, final SolrSource solr, final Gates gates, final LlmClient client,
            final LongSupplier clock) {
        this.cfg = cfg;
        this.store = store;
        this.solr = solr;
        this.gates = gates;
        this.client = client;
        this.clock = clock;
        this.breaker = new LlmBreaker(cfg.llmBreakerFailures, cfg.llmBreakerMaxBackoffMillis, clock);
        this.timing=new LlmTiming(cfg.llmSchedule,clock);
        this.tiers = new BaseTiers(cfg);
        this.publisher = new Publisher(cfg, this.terms);
    }

    public void requestStop() {
        this.stopping = true;
    }

    /** New input to look at (the sync published a document of an LLM collection): the next step scans at once. */
    public void wake() {
        this.countsAt=0L;
        synchronized (this.scanLock) {
            this.scanPausedUntil = 0L;
        }
        if ("idle".equals(this.state)) {
            this.nextAt = 0L;
        }
    }

    /**
     * Start-up work: the vocabulary, claims of the last run, and documents a
     * changed selection (LLM collections, per-host cap) must look at again.
     */
    private synchronized void init() throws KgException {
        if(this.initialized)return;
        final String key = this.cfg.llmSelectionKey();
        this.store.write(
                WriteClass.MAINTENANCE,
                SMALL,
                tx -> {
                    this.terms.seed(tx);
                    LlmQueue.resetClaims(tx);
                    LlmProgress.prune(tx);
                    String lastStart = KgStore.getMeta(tx, LAST_START);
                    if (lastStart != null) this.timing.restoreLastStart(Long.valueOf(lastStart));
                    final String last = KgStore.getMeta(tx, KgSchema.META_LLM_SELECTION);
                    if (last != null && !last.equals(key)) {
                        final int n = LlmQueue.reopen(tx, LlmQueue.STATUS_SKIPPED);
                        LlmQueue.resetCursors(tx);
                        if (n > 0) {
                            KgStore.event(
                                    tx,
                                    1,
                                    "llm_selection_changed",
                                    n + " skipped documents are examined again",
                                    this.clock.getAsLong());
                        }
                    }
                    KgStore.putMeta(tx, KgSchema.META_LLM_SELECTION, key);
                    this.prompt =
                            KnowledgePrompt.fromMeta(
                                    KgStore.getMeta(tx, KnowledgePrompt.META_ACTIVE));
                    if (KgStore.getMeta(tx, KgSchema.META_UPGRADE_HOLD) == null) {
                        reexamineIfChanged(tx);
                    } // an upgrade without its copy: the documents are examined again once the hold
                      // is released
                    return null;
                });
        this.pruneAt=this.clock.getAsLong()+60_000L;
        this.initialized = true;
    }

    /**
     * A new extractor version (vocabulary 2) reads every document again; the
     * old evidence stays until replaced. A graph upgraded from a version
     * without this record (Scoutro 0.7) counts as changed. A new prompt (a
     * new compiled-in default or an activated one, {@link KnowledgePrompt})
     * changes the prompt hash, the extractor identity and the cache key, but
     * reads no document again: new and changed pages are asked with it.
     */
    private void reexamineIfChanged(final java.sql.Connection tx) throws java.sql.SQLException {
        final String version = LlmExtractor.NAME + "/" + LlmExtractor.VERSION + "/";
        final String extractor = version + this.prompt.hash;
        final String lastExtractor = KgStore.getMeta(tx, KgSchema.META_LLM_EXTRACTOR);
        final boolean upgraded = lastExtractor == null && KgStore.getMeta(tx, KgSchema.META_UPGRADE) != null;
        if (upgraded || lastExtractor != null && !lastExtractor.startsWith(version)) {
            final int n = LlmQueue.reexamine(tx, LlmQueue.STATUS_DONE) + LlmQueue.reexamine(tx, LlmQueue.STATUS_FAILED);
            if (n > 0) {
                LlmQueue.resetCursors(tx);
                KgStore.event(tx, 1, "llm_extractor_changed", n + " documents are examined again by " + extractor,
                        this.clock.getAsLong());
            }
        } else if (lastExtractor != null && !lastExtractor.equals(extractor)) {
            KgStore.event(tx, 1, "llm_prompt_changed", "new and changed pages are asked by " + extractor
                    + "; documents already done are not read again", this.clock.getAsLong());
        }
        KgStore.putMeta(tx, KgSchema.META_LLM_EXTRACTOR, extractor);
    }

    /** Uses an activated prompt from the next document on (KgRuntime.promptActivate has stored it). */
    public void usePrompt(final KnowledgePrompt p) {
        if (p != null) {
            this.prompt = p;
        }
    }

    /** The prompt the tier asks with. */
    public KnowledgePrompt prompt() {
        return this.prompt;
    }

    /** After the upgrade hold was released: the re-examination it held back. */
    public void releaseUpgradeHold() throws KgException {
        if (!this.initialized) {
            return;
        }
        this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
            reexamineIfChanged(tx);
            return null;
        });
        wake();
    }

    // ------------------------------------------------------------------ step

    /**
     * One bounded step of an extract thread: at most one document. Never throws.
     *
     * @return true if more work is due at once
     */
    public boolean step() {
        if (this.stopping) {
            return false;
        }
        final long now = this.clock.getAsLong();
        if (now < this.nextAt) {
            return false;
        }
        try {
            if (!this.cfg.llmEnabled()) {
                return idle("off", null, NOT_CONFIGURED_MILLIS);
            }
            final String m = this.client.model();
            this.model = m;
            if (m == null) {
                return idle("not_configured", "no model is selected for the knowledge usage (LLM selection)", NOT_CONFIGURED_MILLIS);
            }
            if (!this.initialized) {
                init();
            }
            refreshCounts(now);
            if (this.breaker.open()) {
                return idle("paused", "circuit_breaker", GATE_MILLIS);
            }
            final String gate = this.gates.closed(false);
            if (gate != null) {
                return idle("waiting", gate, GATE_MILLIS);
            }
            if (resetInProgress()) {
                return idle("waiting", "full_reset", GATE_MILLIS);
            }
            final String growth = this.store.growthRefusal();
            if (growth != null) {
                // the manual pause (or the budget, disk or integrity): no queue fill, no claim, no model call
                return idle("paused", growth, GATE_MILLIS);
            }
            if(now>=this.pruneAt){
                this.pruneAt=now+60_000L;
                this.store.write(WriteClass.MAINTENANCE,SMALL,tx->{LlmProgress.prune(tx);return null;});
            }
            if(!flushProgress())return idle("waiting","checkpoint_storage",GATE_MILLIS);
            final String temporal=this.timing.workReason();
            if (temporal != null)
                return idle(
                        "waiting",
                        this.pendingDocuments == 0 && this.queueSize == 0
                                ? "backlog_empty"
                                : temporal,
                        500L);
            scan(now);
            final Set<String> only=this.timing.claimOnly();
            final LlmQueue.Item item = this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> LlmQueue.claim(tx, now,only));
            if (item == null) {
                this.countsAt=0;refreshCounts(this.clock.getAsLong());
                this.timing.empty(this.queueClaimed==0&&this.pendingDocuments==0);
                return idle("idle", null, IDLE_MILLIS);
            }
            this.state = "running";
            this.reason = null;
            LlmTiming.Session session;
            try{session=this.timing.begin(item.docId);}
            catch(LlmTiming.Deferred e){release(item,now,false);
                return idle("waiting", e.reason, 500L);
            }
            try{process(item,m,session);}
            finally{boolean complete=this.store.read(c->!queued(c,item.docId));this.timing.finished(session,complete);}
            this.countsAt = 0L; // the status shows the document's outcome at once
            if(this.pendingProgress.isEmpty())this.lastError = null;
            return !this.stopping;
        } catch (final KgException e) {
            this.lastError = e.code() + (e.reason() == null ? "" : ": " + e.reason());
            if (KgException.WRITE_REFUSED.equals(e.code())) {
                this.counters.growthRefused.incrementAndGet();
                return idle("waiting", "write_refused", REFUSED_MILLIS);
            }
            return idle(this.state, this.reason, REFUSED_MILLIS);
        } catch (final RuntimeException e) {
            this.lastError = e.getClass().getSimpleName();
            LOG.warn("knowledge graph LLM step failed: " + e);
            return idle(this.state, this.reason, REFUSED_MILLIS);
        }
    }

    private boolean idle(final String s, final String r, final long millis) {
        this.state = s;
        this.reason = r;
        this.nextAt = this.clock.getAsLong() + millis;
        return false;
    }

    private boolean resetInProgress() throws KgException {
        return "1".equals(this.store.read(c -> KgStore.getMeta(c, KgSchema.META_RESET_IN_PROGRESS)));
    }

    // ------------------------------------------------------------------ scan

    /**
     * Fills the queue while few items are due; one pass per call, one thread at a time (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
     * 6.3). Every followed collection has its own scan: a frontier that walks its pages in row order and keeps its
     * position across restarts, and a revisit that walks behind the frontier for pages that became to do again there.
     * A pass gives the revisits up to {@link #REVISIT_BATCH} pages and every collection an equal share of the rest;
     * a share a collection cannot use goes to the others. The page priorities, the per-host cap, the schedule and the
     * gates are those of the queue and the worker, as before.
     */
    private void scan(final long now) throws KgException {
        synchronized (this.scanLock) {
            if (now < this.scanPausedUntil) {
                return;
            }
            if (this.store.read(c -> LlmQueue.ready(c, now + READY_HORIZON_MILLIS)) > QUEUE_LOW) {
                return;
            }
            final List<LlmQueue.Lane> lanesNow = this.store.read(c -> LlmQueue.lanes(c, this.cfg::llmFollows));
            final List<LlmQueue.Lane> all = new ArrayList<>();
            final List<LlmQueue.Lane> others = new ArrayList<>();
            for (final LlmQueue.Lane lane : lanesNow) {
                (lane.followed ? all : others).add(lane);
            }
            if (!others.isEmpty()) {
                markOthers(others, now);
            }
            if (all.isEmpty()) {
                this.lanes = all;
                this.scanPausedUntil = now + SCAN_PAUSE_MILLIS;
                return;
            }
            final int k = all.size();
            final List<LlmQueue.Lane> order = new ArrayList<>(k);
            final int first = (int) (this.scanRound++ % k);
            for (int i = 0; i < k; i++) {
                order.add(all.get((first + i) % k));
            }
            // the pages of this pass, per collection: revisit first (older work), then the frontier
            final Map<Integer, List<LlmQueue.Todo>> behind = new java.util.HashMap<>();
            final Map<Integer, List<LlmQueue.Todo>> ahead = new java.util.HashMap<>();
            final Map<Integer, Long> lastRow = new java.util.HashMap<>();
            // true if a query returned fewer pages than it asked for: nothing more (unqueued and to do) in that range
            final java.util.Set<Integer> behindDone = new java.util.HashSet<>();
            final java.util.Set<Integer> aheadDone = new java.util.HashSet<>();
            this.store.read(c -> {
                final int revisitShare = (REVISIT_BATCH + k - 1) / k;
                for (final LlmQueue.Lane lane : order) {
                    lastRow.put(lane.collId, LlmQueue.lastRow(c, lane.collId));
                    if (lane.frontier > 0L && now >= this.revisitPausedUntil.getOrDefault(lane.collId, 0L)) {
                        final List<LlmQueue.Todo> t = LlmQueue.laneTodo(c, lane.collId, lane.revisit, lane.frontier, revisitShare);
                        behind.put(lane.collId, t);
                        if (t.size() < revisitShare) {
                            behindDone.add(lane.collId);
                        }
                    }
                }
                final int frontierBatch = SCAN_BATCH - behind.values().stream().mapToInt(List::size).sum();
                final int share = Math.max(1, (frontierBatch + k - 1) / k);
                int left = frontierBatch;
                final List<LlmQueue.Lane> full = new ArrayList<>();
                for (final LlmQueue.Lane lane : order) {
                    final int ask = Math.min(share, Math.max(0, left));
                    final List<LlmQueue.Todo> t = LlmQueue.laneTodo(c, lane.collId, lane.frontier, Long.MAX_VALUE, ask);
                    ahead.put(lane.collId, new ArrayList<>(t));
                    left -= t.size();
                    if (ask > 0 && t.size() < ask) {
                        aheadDone.add(lane.collId);
                    } else if (ask > 0) {
                        full.add(lane);
                    }
                }
                // a share a collection could not use goes to those that had more
                for (final LlmQueue.Lane lane : full) {
                    if (left <= 0) {
                        break;
                    }
                    final List<LlmQueue.Todo> mine = ahead.get(lane.collId);
                    final int ask = Math.min(left, (left + full.size() - 1) / full.size());
                    final List<LlmQueue.Todo> more = LlmQueue.laneTodo(c, lane.collId, mine.get(mine.size() - 1).rowid, Long.MAX_VALUE, ask);
                    mine.addAll(more);
                    left -= more.size();
                    if (more.size() < ask) {
                        aheadDone.add(lane.collId);
                    }
                }
                return null;
            });
            final long[] added = new long[4];
            final boolean[] any = new boolean[1];
            this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
                long size = LlmQueue.size(tx);
                final Map<String, Long> load = new java.util.HashMap<>();
                for (final LlmQueue.Lane lane : order) {
                    final List<LlmQueue.Todo> b = behind.get(lane.collId);
                    if (b != null) {
                        final long[] r = offer(tx, b, lane, load, size, now, added);
                        size = r[0];
                        if (r[1] >= 0L) {
                            lane.revisit = r[1];
                        }
                        this.counters.requeuedBehindFrontier.addAndGet(r[2]);
                        if (r[3] == 1L && behindDone.contains(lane.collId)) {
                            // the sweep reached the frontier: the next one starts from the beginning, after a pause
                            lane.revisit = 0L;
                            this.revisitPausedUntil.put(lane.collId, now + REVISIT_PAUSE_MILLIS);
                        }
                        any[0] |= !b.isEmpty();
                    }
                    final List<LlmQueue.Todo> a = ahead.get(lane.collId);
                    final long[] r = offer(tx, a, lane, load, size, now, added);
                    size = r[0];
                    if (r[1] >= 0L) {
                        lane.frontier = r[1];
                    }
                    if (r[3] == 1L && aheadDone.contains(lane.collId)) {
                        // the collection has nothing more ahead: rows added later get higher row IDs
                        lane.frontier = Math.max(lane.frontier, lastRow.getOrDefault(lane.collId, 0L));
                    }
                    any[0] |= !a.isEmpty();
                    KgStore.putMeta(tx, lane.key(), lane.value());
                }
                return null;
            });
            this.lanes = order;
            if (!any[0]) {
                this.scanPausedUntil = now + SCAN_PAUSE_MILLIS;
            }
            if (added[0] + added[1] + added[2] > 0L) {
                this.countsAt = 0L;
                this.counters.scanPasses.incrementAndGet();
            }
            this.counters.queued.addAndGet(added[0]);
            this.counters.skippedNotSelected.addAndGet(added[1]);
            this.counters.skippedHostCap.addAndGet(added[2]);
            this.queueSize += added[0];
        }
    }

    /**
     * The collections the graph follows but the LLM tier does not: their pages are marked skipped (not selected), as the
     * single scan of earlier versions did, so they show as such and a later selection reopens them. No model call, no
     * share: a bounded batch per collection and pass, frontier and revisit as for the followed collections.
     */
    private void markOthers(final List<LlmQueue.Lane> others, final long now) throws KgException {
        final Map<Integer, List<LlmQueue.Todo>> todo = new java.util.HashMap<>();
        final java.util.Set<Integer> done = new java.util.HashSet<>();
        final Map<Integer, Long> lastRow = new java.util.HashMap<>();
        this.store.read(c -> {
            for (final LlmQueue.Lane lane : others) {
                lastRow.put(lane.collId, LlmQueue.lastRow(c, lane.collId));
                final boolean revisit = lane.frontier > 0L && now >= this.revisitPausedUntil.getOrDefault(lane.collId, 0L);
                final List<LlmQueue.Todo> t = revisit ? LlmQueue.laneTodo(c, lane.collId, lane.revisit, lane.frontier, SCAN_BATCH)
                        : LlmQueue.laneTodo(c, lane.collId, lane.frontier, Long.MAX_VALUE, SCAN_BATCH);
                todo.put(lane.collId, t);
                if (t.size() < SCAN_BATCH) {
                    done.add(lane.collId);
                }
            }
            return null;
        });
        final long[] marked = new long[1];
        this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
            for (final LlmQueue.Lane lane : others) {
                final boolean revisit = lane.frontier > 0L && now >= this.revisitPausedUntil.getOrDefault(lane.collId, 0L);
                long last = -1L;
                for (final LlmQueue.Todo t : todo.get(lane.collId)) {
                    last = t.rowid;
                    if (!selected(t.collections) && LlmQueue.mark(tx, t.docId, t.llmInput, LlmQueue.STATUS_SKIPPED, LlmQueue.SKIP_NOT_SELECTED)) {
                        marked[0]++;
                    } // a page also in a followed collection is that collection's
                }
                if (revisit) {
                    lane.revisit = done.contains(lane.collId) ? 0L : Math.max(lane.revisit, last);
                    if (done.contains(lane.collId)) {
                        this.revisitPausedUntil.put(lane.collId, now + REVISIT_PAUSE_MILLIS);
                    }
                } else {
                    lane.frontier = done.contains(lane.collId) ? Math.max(Math.max(lane.frontier, last), lastRow.get(lane.collId))
                            : Math.max(lane.frontier, last);
                }
                KgStore.putMeta(tx, lane.key(), lane.value());
            }
            return null;
        });
        this.counters.skippedNotSelected.addAndGet(marked[0]);
        if (marked[0] > 0L) {
            this.countsAt = 0L;
        }
    }

    /**
     * Queues or marks the pages of one collection in row order, as the scan always did (selection, host, per-host cap,
     * priority). Stops at the queue's maximum.
     *
     * @return {new queue size, the last row handled or -1, pages queued, 1 if every page was handled}
     */
    private long[] offer(final java.sql.Connection tx, final List<LlmQueue.Todo> todo, final LlmQueue.Lane lane,
            final Map<String, Long> load, final long sizeBefore, final long now, final long[] added) throws java.sql.SQLException {
        long size = sizeBefore;
        long last = -1L;
        long queuedHere = 0L;
        for (final LlmQueue.Todo t : todo) {
            if (size >= QUEUE_MAX) {
                return new long[] {size, last, queuedHere, 0L};
            }
            last = t.rowid;
            if (!selected(t.collections)) {
                LlmQueue.mark(tx, t.docId, t.llmInput, LlmQueue.STATUS_SKIPPED, LlmQueue.SKIP_NOT_SELECTED);
                added[1]++;
                continue;
            }
            if (t.hostId == null) {
                LlmQueue.mark(tx, t.docId, t.llmInput, LlmQueue.STATUS_SKIPPED, LlmQueue.SKIP_NO_HOST);
                added[1]++;
                continue;
            }
            Long n = load.get(t.hostId);
            if (n == null) {
                n = LlmQueue.hostLoad(tx, t.hostId);
            }
            if (n >= this.cfg.llmMaxDocsPerHost) {
                // queued documents of the host are kept; the cap counts them
                if (!queued(tx, t.docId)) {
                    LlmQueue.mark(tx, t.docId, t.llmInput, LlmQueue.STATUS_SKIPPED, LlmQueue.SKIP_HOST_CAP);
                    added[2]++;
                }
                load.put(t.hostId, n);
                continue;
            }
            if (LlmQueue.offer(tx, t.docId, t.hostId, LlmQueue.priority(t.url), now)) {
                size++;
                n++;
                added[0]++;
                queuedHere++;
                this.queuedByCollection.computeIfAbsent(lane.name, x -> new AtomicLong()).incrementAndGet();
            }
            load.put(t.hostId, n);
        }
        return new long[] {size, last, queuedHere, 1L};
    }

    private boolean selected(final List<String> collections) {
        for (final String c : collections) {
            if (this.cfg.llmFollows(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean queued(final java.sql.Connection c, final String docId) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kg_llm_work WHERE doc_id = ?")) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // --------------------------------------------------------------- process

    /** The graph's row of a queued document. */
    private static final class DocRow {
        String url;
        String hostId;
        byte[] inputHash;
        /** What tiers 1 and 2 read without the extractor versions (schema 4); null for a page published before. */
        byte[] contentHash;
        int state;
        Integer llmStatus;
        List<String> collections = Collections.emptyList();

        /** {@link LlmQueue#LLM_INPUT}: the identity of what the LLM tier reads. */
        byte[] llmInput() {
            return this.contentHash != null ? this.contentHash : this.inputHash;
        }

        /**
         * True if Solr holds the input the graph published, as far as the LLM tier reads it: the content hash where the
         * graph has one (a new vocabulary or extractor version changes the input hash, not the page), else the input hash.
         */
        boolean sameInput(final SolrDoc d) {
            return this.contentHash != null ? Arrays.equals(d.contentHash(), this.contentHash) : Arrays.equals(d.inputHash(), this.inputHash);
        }
    }

    private DocRow row(final String docId) throws KgException {
        return this.store.read(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT d.url, d.host_id, d.input_hash, d.state, d.llm_status, d.content_hash,"
                                        + " (SELECT group_concat(k.name, ',') FROM"
                                        + " kg_doc_collection dc JOIN kg_collection k ON k.coll_id"
                                        + " = dc.coll_id WHERE dc.doc_rowid = d.doc_rowid) FROM"
                                        + " kg_doc d WHERE d.doc_id = ?")) {
                        ps.setString(1, docId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return null;
                            }
                            final DocRow r = new DocRow();
                            r.url = rs.getString(1);
                            r.hostId = rs.getString(2);
                            r.inputHash = rs.getBytes(3);
                            r.state = rs.getInt(4);
                            r.llmStatus = rs.getObject(5) == null ? null : rs.getInt(5);
                            r.contentHash = rs.getBytes(6);
                            final String colls = rs.getString(7);
                            if (colls != null && !colls.isEmpty()) {
                                r.collections = Arrays.asList(colls.split(","));
                            }
                            return r;
                        }
                    }
                });
    }

    private void process(final LlmQueue.Item item, final String m, final LlmTiming.Session session) throws KgException {
        final long now = this.clock.getAsLong();
        final KnowledgePrompt p = this.prompt; // one version for the whole document: text, cache key and extractor
        final DocRow row = row(item.docId);
        if (row == null || row.state != 1 || row.inputHash == null || row.llmStatus != null || !selected(row.collections)) {
            complete(item); // removed, unavailable, already marked or out of the selection: nothing to do
            return;
        }
        final SolrDoc d;
        try {
            d = this.solr.get(Collections.singletonList(item.docId), SolrDoc.PROCESS_FIELDS).get(item.docId);
        } catch (final IOException e) {
            this.lastError = "solr: " + e.getMessage();
            release(item, now + SOLR_RETRY_MILLIS, false);
            return;
        }
        if (d == null) {
            unconfirmed(item, row, false); // Solr lacks the page: the sync removes it, or it is not visible yet
            return;
        }
        if (!row.sameInput(d)) {
            // Solr is ahead of the graph: the sync publishes the new input first, which resets the mark
            unconfirmed(item, row, true);
            return;
        }
        final BaseTiers.Text page = new BaseTiers.Text(this.solr, item.docId);
        final Extraction base;
        final PageText pageText;
        try {
            base = this.tiers.extract(d, page);
            if (!RuleExtractor.candidate(d.url, d.titles, base)) {
                this.counters.skippedNotCandidate.incrementAndGet();
                markAndComplete(item, row.llmInput(), LlmQueue.STATUS_SKIPPED, LlmQueue.SKIP_NOT_CANDIDATE);
                return;
            }
            pageText = layout(item.docId, row.hostId, page.get());
        } catch (final IOException e) {
            this.lastError = "solr: " + e.getMessage();
            release(item, now + SOLR_RETRY_MILLIS, false);
            return;
        }
        final List<LlmExtractor.Known> known = LlmExtractor.known(base);
        final Set<String> kinds = this.cfg.llmKinds(row.collections);
        final String host = BaseTiers.host(d);
        final String domain = Normalizers.registrableDomain(host);
        final String title = d.titles.isEmpty() ? null : d.titles.get(0);
        final List<LlmExtractor.Chunk> chunks = LlmExtractor.chunks(pageText.text, this.cfg.extractMaxInputChars);
        final RequestBudget budget = budget(p);
        final RequestBudget.Plan plan = budget.plan(chunks, known, title, domain, d.language, kinds);
        if (plan.refused) {
            // a request that does not fit the window is never sent (the model would drop the start of the prompt)
            this.counters.contextRefused.incrementAndGet();
            markAndComplete(item, row.llmInput(), LlmQueue.STATUS_FAILED, CONTEXT_BUDGET);
            return;
        }
        this.counters.knownTrimmed.addAndGet(plan.knownTrimmed);
        this.counters.chunksSplit.addAndGet(plan.splits);
        this.counters.promptTokensEstimatedMax.accumulateAndGet(plan.maxPromptTokens, Math::max);
        final List<JSONObject> accepted = new ArrayList<>();
        final List<List<LlmExtractor.Known>> acceptedKnown = new ArrayList<>();
        final List<byte[]> keys = new ArrayList<>();
        final Set<String> requiredKeys=new java.util.HashSet<>();
        for(RequestBudget.Part part:plan.parts)requiredKeys.add(LlmProgress.key(LlmExtractor.cacheKey(p.hash,m,part.chunk,title,domain,d.language,part.known,kinds)));
        final LlmProgress progress;
        try{progress=this.store.read(c->LlmProgress.load(c,item.docId,requiredKeys));}
        catch(KgException e){
            this.state = "waiting";
            this.reason = "checkpoint_storage";
            release(item,now+REFUSED_MILLIS,false);throw e;}
        for (final RequestBudget.Part part : plan.parts) {
            final LlmExtractor.Chunk chunk = part.chunk;
            final List<LlmExtractor.Known> partKnown = part.known;
            try {
                if(this.stopping)throw new LlmTiming.Deferred("stopping");
                this.timing.chunk(session);
            }catch(LlmTiming.Deferred e){defer(item,progress,e.reason);return;}
            final byte[] key = LlmExtractor.cacheKey(p.hash, m, chunk, title, domain, d.language, partKnown, kinds);
            keys.add(key);
            final ExtractionCache.Entry resumed=progress.get(key);
            final ExtractionCache.Entry cached = resumed!=null?resumed:this.store.read(c -> ExtractionCache.get(c, key));
            if (cached != null) {
                if(resumed==null)this.counters.cacheHits.incrementAndGet();
                progress.put(key,cached.status,cached.value);
                if (cached.status == ExtractionCache.STATUS_OK) {
                    accepted.add(cached.value);
                    acceptedKnown.add(partKnown);
                }
                continue;
            }
            this.counters.cacheMisses.incrementAndGet();
            final String answer;
            final java.util.concurrent.atomic.AtomicBoolean breakerLease=new java.util.concurrent.atomic.AtomicBoolean();
            final java.util.concurrent.atomic.AtomicBoolean logicalCounted=new java.util.concurrent.atomic.AtomicBoolean();
            try {
                answer = this.client.complete(p.text,
                        LlmExtractor.userPrompt(new PromptGuard(), chunk, title, domain, d.language, partKnown, kinds),
                        LlmExtractor.SCHEMA, this.cfg.llmTimeoutMillis, this.cfg.llmStructuredOutput,
                        ()->startRequest(item,row,m,session,breakerLease,logicalCounted));
            } catch (LlmClient.Deferred e) {
                if(breakerLease.get())this.breaker.deferred();
                defer(item,progress,e.reason);return;
            } catch (final IOException e) {
                try{checkpoint(item,progress);}catch(KgException refused){
                    defer(item, progress, "checkpoint_storage");
                }
                transportFailure(item, row.llmInput(), e);
                return;
            }
            this.breaker.success();
            final LlmExtractor.Result r = LlmExtractor.validate(answer, chunk, partKnown, kinds);
            final JSONObject value;
            final int status;
            if (r.refused != null) {
                this.counters.refused(r.refused);
                value = KgJson.obj("refused", r.refused);
                status = ExtractionCache.STATUS_REFUSED;
            } else {
                this.counters.accepted(r);
                value = r.accepted;
                status = ExtractionCache.STATUS_OK;
                accepted.add(value);
                acceptedKnown.add(partKnown);
            }
            progress.put(key,status,value);
            try{checkpoint(item,progress);}catch(KgException e){
                defer(item, progress, "checkpoint_storage");
                return;}
            cachePut(key, m, p.hash, status, value);
        }
        final Extraction ex = new Extraction(this.cfg.extractMaxStatementsPerDoc);
        final net.yacy.scoutro.knowledge.extract.ExtractContext ctx = BaseTiers.context(this.cfg, d);
        for (int i = 0; i < accepted.size(); i++) {
            LlmExtractor.apply(atOrigin(accepted.get(i), pageText), acceptedKnown.get(i), ex, ctx);
        }
        ex.ranTier(LlmExtractor.TIER);
        try {
            publish(item, d, row, ex, keys, m, p.hash, PUBLISH_ESTIMATE + progress.estimate());
        } finally {
            if (this.store.read(c -> queued(c, item.docId)))
                defer(item, progress, this.reason == null ? "publication_deferred" : this.reason);
        }
    }

    /**
     * The budget of the requests (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3): the model's window (the service's configured
     * {@code ai.service_num_ctx}, else Ollama's default of 4096 assumed), the answer reserve (at most the row's output
     * cap), the active system prompt and, where the endpoint may count it as prompt, the schema.
     */
    private RequestBudget budget(final KnowledgePrompt p) {
        final LlmClient.Window w = this.client.window();
        final int window = w == null ? RequestBudget.DEFAULT_WINDOW : w.tokens;
        final int reserve = w == null ? this.cfg.llmAnswerReserveTokens : Math.min(this.cfg.llmAnswerReserveTokens, w.maxOutputTokens);
        return new RequestBudget(window, reserve, p.text, w == null || w.schemaInPrompt ? LlmExtractor.SCHEMA : null);
    }

    /**
     * The page as the tier reads it ({@link PageText}): a page longer than the input with blocks of navigation that
     * recur on up to three other pages of its host (the first by document ID, so the same page is read the same way)
     * gets them behind its own content; every other page is read unchanged.
     */
    private PageText layout(final String docId, final String hostId, final String text) throws IOException, KgException {
        if (text == null || text.length() <= this.cfg.extractMaxInputChars || hostId == null) {
            return PageText.of(text);
        }
        final List<String> ids = this.store.read(c -> {
            final List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT doc_id FROM kg_doc WHERE host_id = ? AND state = 1 AND doc_id <> ?"
                    + " ORDER BY doc_id LIMIT " + PageText.SIBLINGS)) {
                ps.setString(1, hostId);
                ps.setString(2, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
        if (ids.isEmpty()) {
            return PageText.of(text);
        }
        final String key = hostId + "|" + String.join(",", ids);
        Set<String> recurring = this.recurringLines.get(key);
        if (recurring == null) {
            final List<Set<String>> sets = new ArrayList<>();
            for (final String id : ids) {
                sets.add(PageText.keys(this.solr.text(id)));
            }
            recurring = PageText.recurring(sets);
            this.recurringLines.put(key, recurring);
        }
        final PageText pt = PageText.layout(text, recurring, this.cfg.extractMaxInputChars);
        if (pt.moved > 0) {
            this.counters.navigationMoved.incrementAndGet();
            this.counters.navigationMovedChars.addAndGet(pt.moved);
        }
        return pt;
    }

    /** The accepted items with their quote positions in the stored text (the cached answer keeps those of the input). */
    private static JSONObject atOrigin(final JSONObject accepted, final PageText pt) {
        if (pt.moved == 0) {
            return accepted;
        }
        try {
            final JSONObject out = new JSONObject(accepted.toString());
            for (final String field : List.of("entities", "claims", "values")) {
                final org.json.JSONArray items = out.optJSONArray(field);
                if (items == null) {
                    continue;
                }
                for (int i = 0; i < items.length(); i++) {
                    final JSONObject item = items.optJSONObject(i);
                    if (item != null && item.has("at")) {
                        item.put("at", pt.origin(item.optInt("at")));
                    }
                }
            }
            return out;
        } catch (final org.json.JSONException e) {
            return accepted; // the positions stay those of the input
        }
    }

    /** Rechecked for every transport, including the second request after a format rejection. */
    private LlmClient.RequestPermit startRequest(
            LlmQueue.Item item,
            DocRow expected,
            String model,
            LlmTiming.Session session,
            java.util.concurrent.atomic.AtomicBoolean breakerLease,
            java.util.concurrent.atomic.AtomicBoolean logicalCounted)
            throws IOException {
        LlmClient.RequestPermit admitted =
                this.timing.start(
                        session,
                        () -> {
                            if (this.stopping) throw new LlmTiming.Deferred("stopping");
                            if (!this.pendingProgress.isEmpty())
                                throw new LlmTiming.Deferred("checkpoint_storage");
                            String gate = this.gates.closed(false);
                            if (gate != null) throw new LlmTiming.Deferred(gate);
                            try {
                                String growth = this.store.growthRefusal();
                                if (growth != null) throw new LlmTiming.Deferred(growth);
                                if (resetInProgress()) throw new LlmTiming.Deferred("full_reset");
                                DocRow current = row(item.docId);
                                if (current == null
                                        || current.state != 1
                                        || current.llmStatus != null
                                        || !selected(current.collections))
                                    throw new LlmTiming.Deferred("collection_not_selected");
                                if (!Arrays.equals(current.llmInput(), expected.llmInput()))
                                    throw new LlmTiming.Deferred("input_changed");
                                if (!model.equals(this.client.model()))
                                    throw new LlmTiming.Deferred("model_changed");
                                if (!breakerLease.get()) {
                                    if (!this.breaker.allow())
                                        throw new LlmTiming.Deferred("circuit_breaker");
                                    breakerLease.set(true);
                                }
                            } catch (KgException e) {
                                throw new LlmTiming.Deferred(
                                        e.reason() == null ? e.code() : e.reason());
                            }
                        },
                        at -> {
                            try {
                                this.store.write(
                                        WriteClass.SYSTEM,
                                        1024,
                                        tx -> {
                                            KgStore.putMeta(tx, LAST_START, Long.toString(at));
                                            return null;
                                        });
                            } catch (KgException e) {
                                throw new LlmTiming.Deferred(
                                        e.reason() == null ? e.code() : e.reason());
                            }
                        });
        final long started = this.clock.getAsLong();
        this.counters.requestStarts.incrementAndGet();
        if (!logicalCounted.getAndSet(true)) this.counters.calls.incrementAndGet();
        return () -> {
            // the count first, then its time: a status read in between never divides a time by fewer requests
            this.counters.requestsFinished.incrementAndGet();
            this.counters.callMillis.addAndGet(Math.max(0, this.clock.getAsLong() - started));
            admitted.close();
        };
    }

    private void checkpoint(LlmQueue.Item item, LlmProgress progress) throws KgException {
        if (!progress.entries.isEmpty())
            this.store.write(
                    WriteClass.MAINTENANCE,
                    progress.estimate(),
                    tx -> {
                        if (queued(tx, item.docId)) progress.save(tx, item.docId);
                        return null;
                    });
    }

    private void defer(LlmQueue.Item item, LlmProgress progress, String reason) {
        this.pendingProgress.put(item.docId, new PendingProgress(item, progress));
        boolean saved = flushProgress();
        this.state = "waiting";
        this.reason = saved ? reason : "checkpoint_storage";
        this.nextAt = this.clock.getAsLong() + 500L;
    }

    private boolean flushProgress() {
        synchronized (this.progressLock) {
            for (PendingProgress p : new ArrayList<>(this.pendingProgress.values())) {
                try {
                    this.store.write(
                            WriteClass.MAINTENANCE,
                            p.progress.estimate(),
                            tx -> {
                                // The queue may have been cleared by lifecycle work while the model
                                // ran.
                                if (queued(tx, p.item.docId)) {
                                    p.progress.save(tx, p.item.docId);
                                    LlmQueue.release(
                                            tx, p.item.docId, this.clock.getAsLong(), false);
                                }
                                return null;
                            });
                    this.pendingProgress.remove(p.item.docId, p);
                } catch (KgException e) {
                    this.lastError = e.code();
                    return false;
                }
            }
            return true;
        }
    }

    public void updateSchedule(LlmSchedule schedule) {
        this.timing.update(schedule);
        this.cfg.llmSchedule = schedule;
        this.nextAt = 0L;
    }

    public JSONObject manualRun(int documents, int starts) {
        JSONObject run = this.timing.run(documents, starts);
        this.nextAt = 0L;
        wake();
        return run;
    }

    public JSONObject manualStop() {
        JSONObject run = this.timing.stop();
        this.nextAt = 0L;
        return run;
    }

    private void transportFailure(
            final LlmQueue.Item item, final byte[] llmInput, final IOException e)
            throws KgException {
        final String why =
                e instanceof SocketTimeoutException ? "timeout" : e.getClass().getSimpleName();
        this.counters.callFailures.incrementAndGet();
        if (e instanceof SocketTimeoutException) {
            this.counters.timeouts.incrementAndGet();
        }
        this.breaker.failure(why);
        this.lastError = "llm: " + why;
        if (item.attempts + 1 >= this.cfg.llmMaxAttempts) {
            this.counters.failedDocs.incrementAndGet();
            markAndComplete(item, llmInput, LlmQueue.STATUS_FAILED, LlmQueue.FAILED);
        } else {
            release(item, this.clock.getAsLong() + (FIRST_RETRY_MILLIS << Math.min(6, item.attempts)), true);
        }
    }

    private void cachePut(final byte[] key, final String m, final String promptHash, final int status, final JSONObject value) {
        if (this.cfg.cacheMaxBytes() <= 0L) {
            return;
        }
        final long now = this.clock.getAsLong();
        try {
            this.store.write(WriteClass.GROWTH, SMALL, tx -> {
                ExtractionCache.put(tx, key, this.terms.llmExtractor(tx, m, promptHash), status, value, now);
                if (now >= this.evictAt) {
                    this.evictAt = now + EVICT_MILLIS;
                    this.cacheBytes = ExtractionCache.evict(tx, this.cfg.cacheMaxBytes(), 50);
                }
                return null;
            });
        } catch (final KgException e) {
            this.counters.cacheWritesRefused.incrementAndGet(); // optional: the result is still published
        }
    }

    private void publish(final LlmQueue.Item item, final SolrDoc d, final DocRow row, final Extraction ex, final List<byte[]> keys,
            final String m, final String promptHash,final long estimate) throws KgException {
        final long now = this.clock.getAsLong();
        if (this.stopping) {
            release(item, now, false);
            return;
        }
        final Publisher.Doc doc = new Publisher.Doc();
        doc.docId = d.id;
        doc.url = d.url;
        doc.host = BaseTiers.host(d);
        doc.hostId = d.hostId;
        doc.language = d.language;
        doc.loadedAt = d.loadDate;
        doc.contentHash = d.contentHash();
        doc.accessCollections = d.collections;
        final Publisher.Result[] result = new Publisher.Result[1];
        final boolean[] kept = new boolean[2];
        try {
            this.store.write(WriteClass.GROWTH, estimate, tx -> {
                if ("1".equals(KgStore.getMeta(tx, KgSchema.META_RESET_IN_PROGRESS))) {
                    LlmQueue.release(tx, item.docId, now + GATE_MILLIS, false);
                    return null;
                }
                result[0] = this.publisher.applyLlm(tx, doc, row.llmInput(), ex, this.terms.llmExtractor(tx, m, promptHash), now);
                ExtractionCache.touch(tx, keys, now);
                if (result[0].outcome != Publisher.Outcome.PUBLISHED && LlmQueue.toDo(tx, item.docId)) {
                    // the page changed during the call and is still to do: never dropped from the queue without a mark
                    if (item.attempts + 1 >= unconfirmedLimit()) {
                        kept[1] = LlmQueue.mark(tx, item.docId, currentInput(tx, item.docId), LlmQueue.STATUS_FAILED,
                                LlmQueue.INPUT_UNCONFIRMED);
                        LlmQueue.complete(tx, item.docId);
                    } else {
                        LlmQueue.release(tx, item.docId, now, true);
                        kept[0] = true;
                    }
                    return null;
                }
                LlmQueue.complete(tx, item.docId);
                return null;
            });
        } catch (final KgException e) {
            this.terms.forgetCollections();
            if (KgException.WRITE_REFUSED.equals(e.code())) {
                this.counters.growthRefused.incrementAndGet();
                release(item, now + REFUSED_MILLIS, false);
                this.state = "waiting";
                this.reason = "write_refused";
                return;
            }
            throw e;
        }
        if (result[0] == null) {
            return;
        }
        if (result[0].outcome == Publisher.Outcome.PUBLISHED) {
            this.counters.published.incrementAndGet();
            this.counters.statements.addAndGet(result[0].statements);
        } else {
            this.counters.changedAborts.incrementAndGet();
            if (kept[0]) {
                this.counters.unconfirmedRetries.incrementAndGet();
            } else if (kept[1]) {
                this.counters.inputUnconfirmed.incrementAndGet();
            }
        }
    }

    /** {@link LlmQueue#LLM_INPUT} of the document as the graph holds it now; null if it is not tracked. */
    private static byte[] currentInput(final java.sql.Connection c, final String docId) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + LlmQueue.LLM_INPUT + " FROM kg_doc WHERE doc_id = ?")) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    /** Tries for an input the worker cannot confirm (attempts of transport failures included). */
    private int unconfirmedLimit() {
        return this.cfg.llmMaxAttempts + UNCONFIRMED_EXTRA_ATTEMPTS;
    }

    /**
     * Solr lacks the page or holds another input than the graph published. The item is never dropped while its page is
     * still to do (the scan would have to find it again behind its position): it stays queued with a growing backoff
     * until the sync has caught up, and after {@link #unconfirmedLimit()} tries the page is marked failed
     * ({@link LlmQueue#INPUT_UNCONFIRMED}) so it shows in the status instead of waiting unseen. A new input resets the
     * mark (the publisher), the admin retry too.
     */
    private void unconfirmed(final LlmQueue.Item item, final DocRow row, final boolean changed) throws KgException {
        if (changed) {
            this.counters.changedAborts.incrementAndGet();
        }
        if (item.attempts + 1 >= unconfirmedLimit()) {
            this.counters.inputUnconfirmed.incrementAndGet();
            markAndComplete(item, row.llmInput(), LlmQueue.STATUS_FAILED, LlmQueue.INPUT_UNCONFIRMED);
            return;
        }
        this.counters.unconfirmedRetries.incrementAndGet();
        release(item, this.clock.getAsLong() + Math.min(UNCONFIRMED_MAX_BACKOFF_MILLIS, CHANGED_RETRY_MILLIS << Math.min(6, item.attempts)),
                true);
    }

    private void complete(final LlmQueue.Item item) throws KgException {
        long estimate=SMALL+this.store.read(c->LlmProgress.storedBytes(c,item.docId));
        this.store.write(WriteClass.MAINTENANCE, estimate, tx -> {
            LlmQueue.complete(tx, item.docId);
            return null;
        });
    }

    /**
     * Marks the document for the input it was examined with and completes its item. If the input changed meanwhile the
     * mark does not apply: the item stays queued (due at once) for the new input instead of being dropped unmarked.
     */
    private void markAndComplete(final LlmQueue.Item item, final byte[] llmInput, final int status, final String why)
            throws KgException {
        long estimate=SMALL+this.store.read(c->LlmProgress.storedBytes(c,item.docId));
        this.store.write(WriteClass.MAINTENANCE, estimate, tx -> {
            if (!LlmQueue.mark(tx, item.docId, llmInput, status, why) && LlmQueue.toDo(tx, item.docId)) {
                LlmQueue.release(tx, item.docId, this.clock.getAsLong(), false);
                return null;
            }
            LlmQueue.complete(tx, item.docId,status==LlmQueue.STATUS_FAILED);
            return null;
        });
    }

    private void release(final LlmQueue.Item item, final long notBefore, final boolean attempt) throws KgException {
        this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
            LlmQueue.release(tx, item.docId, notBefore, attempt);
            return null;
        });
    }

    // --------------------------------------------------------------- control

    /** Makes failed documents due again (admin retry); returns their number. */
    public int retryFailed() throws KgException {
        final long now = this.clock.getAsLong();
        final int n = this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
            final int reopened = LlmQueue.reopen(tx, LlmQueue.STATUS_FAILED);
            if (reopened > 0) {
                LlmQueue.resetCursors(tx); // the frontiers walk again: faster than the revisits for pages reopened in bulk
            }
            KgStore.event(tx, 1, "llm_retry", reopened + " failed documents are examined again", now);
            return reopened;
        });
        synchronized (this.scanLock) {
            this.scanPausedUntil = 0L;
        }
        this.breaker.success();
        this.nextAt = 0L;
        this.countsAt = 0L;
        return n;
    }

    private void refreshCounts(final long now) {
        if (now < this.countsAt) {
            return;
        }
        this.countsAt = now + COUNTS_MILLIS;
        try {
            this.store.read(c -> {
                this.docCounts = LlmQueue.counts(c);
                this.pendingDocuments=LlmQueue.pending(c,this.cfg::llmFollows);
                this.queueSize = LlmQueue.size(c);
                this.queueClaimed = LlmQueue.claimed(c);
                this.queueOldest = LlmQueue.oldest(c);
                this.cacheEntries = ExtractionCache.entries(c);
                this.cacheBytes = ExtractionCache.bytes(c);
                return null;
            });
        } catch (final KgException e) {
            // the next refresh tries again
        }
    }

    // ---------------------------------------------------------------- status

    public JSONObject status() {
        refreshCounts(this.clock.getAsLong());
        final long[] docs = this.docCounts;
        return KgJson.obj(
                "state",
                this.state,
                "reason",
                this.reason,
                "model",
                this.model,
                "enabled",
                this.cfg.llmEnabled(),
                "parallel",
                this.cfg.llmParallel,
                "timeoutSeconds",
                this.cfg.llmTimeoutMillis / 1000L,
                "maxAttempts",
                this.cfg.llmMaxAttempts,
                "maxDocsPerHost",
                this.cfg.llmMaxDocsPerHost,
                "maxInputChars",
                this.cfg.extractMaxInputChars,
                "queue",
                KgJson.obj(
                        "items",
                        this.queueSize,
                        "claimed",
                        this.queueClaimed,
                        "oldestEnqueuedAt",
                        this.queueOldest,
                        "max",
                        QUEUE_MAX),
                "documents",
                // done/failed/skipped: the current LLM status of the pages in the graph; pending: pages of the LLM
                // collections not examined yet (the queue included), the backlog the queue is refilled from
                KgJson.obj("done", docs[0], "failed", docs[1], "skipped", docs[2], "pending", this.pendingDocuments),
                "cache",
                KgJson.obj(
                        "entries",
                        this.cacheEntries,
                        "bytes",
                        this.cacheBytes < 0L ? null : this.cacheBytes,
                        "maxBytes",
                        this.cfg.cacheMaxBytes()),
                "selection",
                selectionStatus(),
                "context",
                contextStatus(),
                "breaker",
                this.breaker.status(),
                "processed",
                this.counters.json(),
                "timing",
                this.timing.status(actualWaitReason()),
                "pendingResultCheckpoints",
                this.pendingProgress.size(),
                "structuredOutput",
                this.client.structuredOutput(this.cfg.llmStructuredOutput),
                "prompt",
                this.prompt.json(),
                "lastError",
                this.lastError);
    }

    /**
     * The fair, resumable selection (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3): per followed collection its frontier and
     * revisit positions (row IDs, as of the last scan pass) and the pages it queued since the start.
     */
    private JSONObject selectionStatus() {
        final org.json.JSONArray out = new org.json.JSONArray();
        for (final LlmQueue.Lane lane : this.lanes) {
            final AtomicLong q = this.queuedByCollection.get(lane.name);
            out.put(KgJson.obj("collection", lane.name, "frontier", lane.frontier, "revisit", lane.revisit, "queued", q == null ? 0L : q.get()));
        }
        return KgJson.obj("collections", out, "revisitBatch", REVISIT_BATCH, "scanBatch", SCAN_BATCH, "agingMinutes",
                LlmQueue.AGING_MILLIS / 60_000L);
    }

    /**
     * The context budget (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3): the window the requests are planned against and
     * whether it is the service's configured one (then it is sent as {@code num_ctx}) or Ollama's assumed default, the
     * row's output cap ({@code num_predict}), the answer reserve, the fixed part of every request, the largest planned
     * prompt, and what the endpoint reported.
     */
    private JSONObject contextStatus() {
        final LlmClient.Window w = this.client.window();
        final RequestBudget b = budget(this.prompt);
        return KgJson.obj("windowTokens", b.window, "windowSource", w == null || !w.configured ? "assumed_default" : "configured",
                "numCtxSent", w != null && w.configured, "maxOutputTokens", w == null ? null : w.maxOutputTokens,
                "answerReserveTokens", b.reserve, "fixedPromptTokens", b.fixedTokens, "schemaCounted", w == null || w.schemaInPrompt,
                "promptTokensEstimatedMax", this.counters.promptTokensEstimatedMax.get(), "measured", this.client.usage());
    }

    private String actualWaitReason() {
        if(!this.pendingProgress.isEmpty())return "checkpoint_storage";
        if(!this.cfg.llmEnabled())return "no_llm_collections";
        if(this.client.model()==null)return "no_model";
        String growth=this.store.growthRefusal();if(growth!=null)return growth;
        String gate=this.gates.closed(false);if(gate!=null)return gate;
        if(this.breaker.open())return "circuit_breaker";
        if (this.queueSize == 0 && this.pendingDocuments == 0)
            return this.queueClaimed > 0 ? "in_flight" : "backlog_empty";
        String temporal=this.timing.workReason();if(temporal!=null)return temporal;
        JSONObject time=this.timing.status(null);Long next=time.optLong("nextAllowedStart",0);
        if(next>this.clock.getAsLong())return "minimum_interval";
        if(time.optInt("runningRequests")>0)return "in_flight";
        if (Set.of(
                        "time_window",
                        "manual_only",
                        "minimum_interval",
                        "manual_stopped",
                        "manual_request_limit",
                        "manual_document_limit")
                .contains(this.reason == null ? "" : this.reason)) return null;
        return this.reason;
    }

    /** True while a call or a step may be running (tests). */
    boolean stopping() {
        return this.stopping;
    }
}
