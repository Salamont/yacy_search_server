/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.knowledge.sync;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.LlmClient;

import org.json.JSONObject;

import java.io.IOException;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * One admission controller shared by both workers, including transport fallbacks. No blocking
 * sleeps.
 */
public final class LlmTiming {
    public static final int DEFAULT_DOCUMENTS = 25, DEFAULT_REQUESTS = 100;
    public static final long MANUAL_DURATION = 3600_000L;

    public static final class Deferred extends LlmClient.Deferred {
        private static final long serialVersionUID = 1L;

        public Deferred(String reason) {
            super(reason);
        }
    }

    public static final class Session {
        final String document;
        long manual;

        Session(String document) {
            this.document = document;
        }
    }

    @FunctionalInterface
    public interface Protection {
        void check() throws IOException;
    }

    @FunctionalInterface
    public interface Recorder {
        void record(long at) throws IOException;
    }

    private LlmSchedule plan;
    private final LongSupplier clock;
    private Long lastStart;
    private int requests;
    private long generation;
    private Manual manual = new Manual();

    private static final class Manual {
        long id, started, ends;
        int maxDocuments, maxRequests, starts, active;
        String state = "not_running";
        final Set<String> documents = new HashSet<>(), unfinished = new HashSet<>();
    }

    public LlmTiming(LlmSchedule plan, LongSupplier clock) {
        this.plan = plan;
        this.clock = clock;
    }

    public synchronized void restoreLastStart(Long at) {
        if (at != null && (lastStart == null || at > lastStart)) lastStart = at;
    }

    public synchronized void update(LlmSchedule plan) {
        this.plan = plan;
    }

    private void expire() {
        if ("running".equals(manual.state) && clock.getAsLong() >= manual.ends)
            manual.state = "expired";
    }

    private boolean override() {
        expire();
        return "running".equals(manual.state);
    }

    public synchronized JSONObject run(int documents, int starts) {
        if (documents < 1 || documents > 100 || starts < 1 || starts > 1000)
            throw new IllegalArgumentException(
                    "Manual limits: 1..100 documents and 1..1000 request starts.");
        if (override() || manual.active > 0)
            throw new IllegalStateException("A manual run is already active.");
        manual = new Manual();
        manual.id = ++generation;
        manual.started = clock.getAsLong();
        manual.ends = manual.started + MANUAL_DURATION;
        manual.maxDocuments = documents;
        manual.maxRequests = starts;
        manual.state = "running";
        return manualJson();
    }

    public synchronized JSONObject stop() {
        if (override()) manual.state = manual.active > 0 ? "stopping" : "stopped";
        return manualJson();
    }

    /**
     * null: unrestricted queue selection; otherwise only unfinished documents already in the
     * bounded run.
     */
    public synchronized Set<String> claimOnly() {
        if (override() && manual.documents.size() >= manual.maxDocuments)
            return new HashSet<>(manual.unfinished);
        return null;
    }

    public synchronized Session begin(String document) throws Deferred {
        String reason = workReason();
        if (reason != null) throw new Deferred(reason);
        Session s = new Session(document);
        adopt(s);
        return s;
    }

    private void adopt(Session s) throws Deferred {
        if (s.manual != 0 && (!override() || s.manual != manual.id))
            throw new Deferred("manual_stopped");
        if (override()) {
            if (!manual.documents.contains(s.document)) {
                if (manual.documents.size() >= manual.maxDocuments)
                    throw new Deferred("manual_document_limit");
                manual.documents.add(s.document);
                manual.unfinished.add(s.document);
            }
            s.manual = manual.id;
        }
    }

    /** Temporal work gate, distinct from request spacing: cache hits consume no request start. */
    public synchronized String workReason() {
        if (plan.error != null) return "invalid_schedule";
        return override() || plan.windowOpen(clock.getAsLong())
                ? null
                : LlmSchedule.MANUAL.equals(plan.mode) ? "manual_only" : "time_window";
    }

    public synchronized void chunk(Session s) throws Deferred {
        adopt(s);
        String reason = workReason();
        if (reason != null) throw new Deferred(reason);
    }

    public synchronized LlmClient.RequestPermit start(
            Session s, Protection protection, Recorder recorder) throws IOException {
        adopt(s);
        String reason = workReason();
        if (reason != null) throw new Deferred(reason);
        long now = clock.getAsLong();
        if (plan.minStartMillis > 0 && lastStart != null && now < lastStart + plan.minStartMillis)
            throw new Deferred("minimum_interval");
        if (override() && manual.starts >= manual.maxRequests) {
            manual.state = "request_limit";
            throw new Deferred("manual_request_limit");
        }
        protection.check();
        // Protection can include store reads: re-evaluate time before reserving this actual
        // transport start.
        now = clock.getAsLong();
        reason = workReason();
        if (reason != null) throw new Deferred(reason);
        if (plan.minStartMillis > 0 && lastStart != null && now < lastStart + plan.minStartMillis)
            throw new Deferred("minimum_interval");
        recorder.record(now);
        lastStart = now;
        requests++;
        final Manual owner = override() ? manual : null;
        if (owner != null) {
            owner.starts++;
            owner.active++;
        }
        return () -> {
            synchronized (LlmTiming.this) {
                requests--;
                if (owner != null) {
                    owner.active--;
                    if (owner.active == 0 && "stopping".equals(owner.state))
                        owner.state = "stopped";
                    else if (owner.active == 0
                            && "running".equals(owner.state)
                            && owner.starts >= owner.maxRequests) owner.state = "request_limit";
                }
            }
        };
    }

    public synchronized void finished(Session s, boolean complete) {
        if (s != null && s.manual == manual.id && complete) {
            manual.unfinished.remove(s.document);
            if (override()
                    && manual.documents.size() >= manual.maxDocuments
                    && manual.unfinished.isEmpty()) manual.state = "document_limit";
        }
    }

    public synchronized void empty(boolean completelyEmpty) {
        if (completelyEmpty && override() && manual.unfinished.isEmpty())
            manual.state = "completed";
    }

    private JSONObject manualJson() {
        expire();
        return KgJson.obj(
                "id",
                manual.id == 0 ? null : manual.id,
                "state",
                manual.state,
                "startedAt",
                manual.id == 0 ? null : manual.started,
                "expiresAt",
                manual.id == 0 ? null : manual.ends,
                "maxDocuments",
                manual.maxDocuments,
                "maxRequests",
                manual.maxRequests,
                "documents",
                manual.documents.size(),
                "unfinishedDocuments",
                manual.unfinished.size(),
                "requestStarts",
                manual.starts,
                "runningRequests",
                manual.active);
    }

    public synchronized JSONObject status(String reason) {
        long now = clock.getAsLong(),
                floor =
                        lastStart == null || plan.minStartMillis == 0
                                ? now
                                : Math.max(now, lastStart + plan.minStartMillis);
        return KgJson.obj(
                "plan",
                plan.json(),
                "valid",
                plan.error == null,
                "validationError",
                plan.error,
                "windowOpen",
                plan.windowOpen(now),
                "nextAllowedStart",
                override() ? Long.valueOf(floor) : plan.nextWindow(floor),
                "lastActualStart",
                lastStart,
                "waitReason",
                reason,
                "runningRequests",
                requests,
                "manual",
                manualJson(),
                "note",
                "Allowed starts only; resource availability and completion times are not"
                    + " guaranteed.");
    }
}
