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
    static final long SCAN_PAUSE_MILLIS = 10_000L;
    static final long IDLE_MILLIS = 5_000L;
    static final long NOT_CONFIGURED_MILLIS = 30_000L;
    static final long GATE_MILLIS = 10_000L;
    static final long REFUSED_MILLIS = 60_000L;
    static final long SOLR_RETRY_MILLIS = 30_000L;
    static final long CHANGED_RETRY_MILLIS = 60_000L;
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
        final AtomicLong callFailures = new AtomicLong();
        final AtomicLong timeouts = new AtomicLong();
        final AtomicLong callMillis = new AtomicLong();
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
        final AtomicLong failedDocs = new AtomicLong();
        final AtomicLong skippedNotCandidate = new AtomicLong();
        final AtomicLong skippedNotSelected = new AtomicLong();
        final AtomicLong skippedHostCap = new AtomicLong();
        final AtomicLong queued = new AtomicLong();
        final AtomicLong growthRefused = new AtomicLong();

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
            return KgJson.obj("calls", n, "callFailures", this.callFailures.get(), "timeouts", this.timeouts.get(),
                    "averageCallMillis", n == 0 ? null : this.callMillis.get() / n,
                    "answersAccepted", this.answersAccepted.get(), "answersRefused", this.answersRefused.get(), "refusedBy", by,
                    "entitiesAccepted", this.entities.get(), "claimsAccepted", this.claims.get(), "valuesAccepted", this.values.get(),
                    "droppedUngrounded", this.droppedUngrounded.get(), "droppedInvalid", invalid,
                    "droppedInvalidByReason", invalidBy,
                    "cacheHits", this.cacheHits.get(), "cacheMisses", this.cacheMisses.get(),
                    "cacheWritesRefused", this.cacheWritesRefused.get(), "published", this.published.get(),
                    "statements", this.statements.get(), "abortedChanged", this.changedAborts.get(),
                    "failedDocs", this.failedDocs.get(), "skippedNotCandidate", this.skippedNotCandidate.get(),
                    "skippedNotSelected", this.skippedNotSelected.get(), "skippedHostCap", this.skippedHostCap.get(),
                    "queued", this.queued.get(), "growthRefused", this.growthRefused.get());
        }
    }

    private final KgConfig cfg;
    private final KgStore store;
    private final SolrSource solr;
    private final Gates gates;
    private final LlmClient client;
    private final LongSupplier clock;
    private final LlmBreaker breaker;
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
    private long scanCursor;
    private long scanPausedUntil;
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
        this.tiers = new BaseTiers(cfg);
        this.publisher = new Publisher(cfg, this.terms);
    }

    public void requestStop() {
        this.stopping = true;
    }

    /** New input to look at (the sync published a document of an LLM collection): the next step scans at once. */
    public void wake() {
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
    private void init() throws KgException {
        final String key = this.cfg.llmSelectionKey();
        this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
            this.terms.seed(tx);
            LlmQueue.resetClaims(tx);
            final String last = KgStore.getMeta(tx, KgSchema.META_LLM_SELECTION);
            if (last != null && !last.equals(key)) {
                final int n = LlmQueue.reopen(tx, LlmQueue.STATUS_SKIPPED);
                if (n > 0) {
                    KgStore.event(tx, 1, "llm_selection_changed", n + " skipped documents are examined again", this.clock.getAsLong());
                }
            }
            KgStore.putMeta(tx, KgSchema.META_LLM_SELECTION, key);
            this.prompt = KnowledgePrompt.fromMeta(KgStore.getMeta(tx, KnowledgePrompt.META_ACTIVE));
            if (KgStore.getMeta(tx, KgSchema.META_UPGRADE_HOLD) == null) {
                reexamineIfChanged(tx);
            } // an upgrade without its copy: the documents are examined again once the hold is released
            return null;
        });
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
            scan(now);
            final LlmQueue.Item item = this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> LlmQueue.claim(tx, now));
            if (item == null) {
                return idle("idle", null, IDLE_MILLIS);
            }
            this.state = "running";
            this.reason = null;
            process(item, m);
            this.countsAt = 0L; // the status shows the document's outcome at once
            this.lastError = null;
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

    /** Fills the queue from the documents to do while it is short; one batch per call, one thread at a time. */
    private void scan(final long now) throws KgException {
        synchronized (this.scanLock) {
            if (now < this.scanPausedUntil || this.queueSize > QUEUE_LOW) {
                return;
            }
            final long after = this.scanCursor;
            final List<LlmQueue.Todo> todo = this.store.read(c -> LlmQueue.todo(c, after, SCAN_BATCH));
            if (todo.isEmpty()) {
                this.scanCursor = 0L;
                this.scanPausedUntil = now + SCAN_PAUSE_MILLIS;
                return;
            }
            final long[] added = new long[4];
            final long last = this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
                long size = LlmQueue.size(tx);
                long cursor = after;
                final Map<String, Long> load = new java.util.HashMap<>();
                for (final LlmQueue.Todo t : todo) {
                    if (size >= QUEUE_MAX) {
                        break;
                    }
                    cursor = t.rowid;
                    if (!selected(t.collections)) {
                        LlmQueue.mark(tx, t.docId, t.inputHash, LlmQueue.STATUS_SKIPPED, LlmQueue.SKIP_NOT_SELECTED);
                        added[1]++;
                        continue;
                    }
                    if (t.hostId == null) {
                        LlmQueue.mark(tx, t.docId, t.inputHash, LlmQueue.STATUS_SKIPPED, LlmQueue.SKIP_NO_HOST);
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
                            LlmQueue.mark(tx, t.docId, t.inputHash, LlmQueue.STATUS_SKIPPED, LlmQueue.SKIP_HOST_CAP);
                            added[2]++;
                        }
                        load.put(t.hostId, n);
                        continue;
                    }
                    if (LlmQueue.offer(tx, t.docId, t.hostId, LlmQueue.priority(t.url), now)) {
                        size++;
                        n++;
                        added[0]++;
                    }
                    load.put(t.hostId, n);
                }
                return cursor;
            });
            this.scanCursor = last;
            if (added[0] + added[1] + added[2] > 0L) {
                this.countsAt = 0L;
            }
            this.counters.queued.addAndGet(added[0]);
            this.counters.skippedNotSelected.addAndGet(added[1]);
            this.counters.skippedHostCap.addAndGet(added[2]);
            this.queueSize += added[0];
        }
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
        int state;
        Integer llmStatus;
        List<String> collections = Collections.emptyList();
    }

    private DocRow row(final String docId) throws KgException {
        return this.store.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT d.url, d.host_id, d.input_hash, d.state, d.llm_status,"
                    + " (SELECT group_concat(k.name, ',') FROM kg_doc_collection dc JOIN kg_collection k ON k.coll_id = dc.coll_id"
                    + " WHERE dc.doc_rowid = d.doc_rowid) FROM kg_doc d WHERE d.doc_id = ?")) {
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
                    final String colls = rs.getString(6);
                    if (colls != null && !colls.isEmpty()) {
                        r.collections = Arrays.asList(colls.split(","));
                    }
                    return r;
                }
            }
        });
    }

    private void process(final LlmQueue.Item item, final String m) throws KgException {
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
            complete(item); // the sync removes it
            return;
        }
        if (!Arrays.equals(d.inputHash(), row.inputHash)) {
            // Solr is ahead of the graph: the sync publishes the new input first, which resets the mark
            this.counters.changedAborts.incrementAndGet();
            if (item.attempts + 1 >= this.cfg.llmMaxAttempts + 2) {
                complete(item); // the scan finds it again once the graph has caught up
            } else {
                release(item, now + CHANGED_RETRY_MILLIS, true);
            }
            return;
        }
        final BaseTiers.Text page = new BaseTiers.Text(this.solr, item.docId);
        final Extraction base;
        final String text;
        try {
            base = this.tiers.extract(d, page);
            if (!RuleExtractor.candidate(d.url, d.titles, base)) {
                this.counters.skippedNotCandidate.incrementAndGet();
                markAndComplete(item, row.inputHash, LlmQueue.STATUS_SKIPPED, LlmQueue.SKIP_NOT_CANDIDATE);
                return;
            }
            text = page.get();
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
        final List<LlmExtractor.Chunk> chunks = LlmExtractor.chunks(text, this.cfg.extractMaxInputChars);
        final List<JSONObject> accepted = new ArrayList<>();
        final List<byte[]> keys = new ArrayList<>();
        for (final LlmExtractor.Chunk chunk : chunks) {
            if (this.stopping) {
                release(item, now, false);
                return;
            }
            final byte[] key = LlmExtractor.cacheKey(p.hash, m, chunk, title, domain, d.language, known, kinds);
            keys.add(key);
            final ExtractionCache.Entry cached = this.store.read(c -> ExtractionCache.get(c, key));
            if (cached != null) {
                this.counters.cacheHits.incrementAndGet();
                if (cached.status == ExtractionCache.STATUS_OK) {
                    accepted.add(cached.value);
                }
                continue;
            }
            this.counters.cacheMisses.incrementAndGet();
            if (!this.breaker.allow()) {
                release(item, now + GATE_MILLIS, false);
                return;
            }
            final String answer;
            final long t0 = this.clock.getAsLong();
            try {
                this.counters.calls.incrementAndGet();
                answer = this.client.complete(p.text,
                        LlmExtractor.userPrompt(new PromptGuard(), chunk, title, domain, d.language, known, kinds),
                        LlmExtractor.SCHEMA, this.cfg.llmTimeoutMillis, this.cfg.llmStructuredOutput);
            } catch (final IOException e) {
                this.counters.callMillis.addAndGet(Math.max(0L, this.clock.getAsLong() - t0));
                transportFailure(item, row.inputHash, e);
                return;
            }
            this.counters.callMillis.addAndGet(Math.max(0L, this.clock.getAsLong() - t0));
            this.breaker.success();
            final LlmExtractor.Result r = LlmExtractor.validate(answer, chunk, known, kinds);
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
            }
            cachePut(key, m, p.hash, status, value);
        }
        final Extraction ex = new Extraction(this.cfg.extractMaxStatementsPerDoc);
        final net.yacy.scoutro.knowledge.extract.ExtractContext ctx = BaseTiers.context(this.cfg, d);
        for (final JSONObject a : accepted) {
            LlmExtractor.apply(a, known, ex, ctx);
        }
        ex.ranTier(LlmExtractor.TIER);
        publish(item, d, row, ex, keys, m, p.hash);
    }

    private void transportFailure(final LlmQueue.Item item, final byte[] inputHash, final IOException e) throws KgException {
        final String why = e instanceof SocketTimeoutException ? "timeout" : e.getClass().getSimpleName();
        this.counters.callFailures.incrementAndGet();
        if (e instanceof SocketTimeoutException) {
            this.counters.timeouts.incrementAndGet();
        }
        this.breaker.failure(why);
        this.lastError = "llm: " + why;
        if (item.attempts + 1 >= this.cfg.llmMaxAttempts) {
            this.counters.failedDocs.incrementAndGet();
            markAndComplete(item, inputHash, LlmQueue.STATUS_FAILED, LlmQueue.FAILED);
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
            final String m, final String promptHash) throws KgException {
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
        try {
            this.store.write(WriteClass.GROWTH, PUBLISH_ESTIMATE, tx -> {
                if ("1".equals(KgStore.getMeta(tx, KgSchema.META_RESET_IN_PROGRESS))) {
                    LlmQueue.release(tx, item.docId, now + GATE_MILLIS, false);
                    return null;
                }
                result[0] = this.publisher.applyLlm(tx, doc, row.inputHash, ex, this.terms.llmExtractor(tx, m, promptHash), now);
                ExtractionCache.touch(tx, keys, now);
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
        }
    }

    private void complete(final LlmQueue.Item item) throws KgException {
        this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
            LlmQueue.complete(tx, item.docId);
            return null;
        });
    }

    private void markAndComplete(final LlmQueue.Item item, final byte[] inputHash, final int status, final String why)
            throws KgException {
        this.store.write(WriteClass.MAINTENANCE, SMALL, tx -> {
            LlmQueue.mark(tx, item.docId, inputHash, status, why);
            LlmQueue.complete(tx, item.docId);
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
            KgStore.event(tx, 1, "llm_retry", reopened + " failed documents are examined again", now);
            return reopened;
        });
        synchronized (this.scanLock) {
            this.scanCursor = 0L;
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
        final long[] docs = this.docCounts;
        return KgJson.obj("state", this.state, "reason", this.reason, "model", this.model,
                "enabled", this.cfg.llmEnabled(), "parallel", this.cfg.llmParallel,
                "timeoutSeconds", this.cfg.llmTimeoutMillis / 1000L, "maxAttempts", this.cfg.llmMaxAttempts,
                "maxDocsPerHost", this.cfg.llmMaxDocsPerHost, "maxInputChars", this.cfg.extractMaxInputChars,
                "queue", KgJson.obj("items", this.queueSize, "claimed", this.queueClaimed, "oldestEnqueuedAt", this.queueOldest,
                        "max", QUEUE_MAX),
                "documents", KgJson.obj("done", docs[0], "failed", docs[1], "skipped", docs[2]),
                "cache", KgJson.obj("entries", this.cacheEntries, "bytes", this.cacheBytes < 0L ? null : this.cacheBytes,
                        "maxBytes", this.cfg.cacheMaxBytes()),
                "breaker", this.breaker.status(), "processed", this.counters.json(),
                "structuredOutput", this.client.structuredOutput(this.cfg.llmStructuredOutput),
                "prompt", this.prompt.json(), "lastError", this.lastError);
    }

    /** True while a call or a step may be running (tests). */
    boolean stopping() {
        return this.stopping;
    }
}
