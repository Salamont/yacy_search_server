/*
 *  LlmBreaker
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

package net.yacy.scoutro.knowledge.extract;

import java.util.function.LongSupplier;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;

/**
 * Circuit breaker of the LLM tier (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3).
 * <p>
 * After {@code failures} consecutive transport failures (timeouts, connection
 * errors, HTTP errors) the tier pauses for 5 minutes, doubling with every
 * further open up to {@code maxBackoffMillis}. When the pause ends one trial
 * call is allowed; its success closes the breaker and resets the backoff, its
 * failure opens it again. An invalid answer is not a transport failure: the
 * model is reachable, its output is only refused. Tiers 1 and 2 never wait
 * for this breaker.
 */
public final class LlmBreaker {

    static final long FIRST_BACKOFF_MILLIS = 5L * 60_000L;

    private final int failures;
    private final long maxBackoffMillis;
    private final LongSupplier clock;

    private int consecutive;
    private long openUntil;
    private long backoff;
    private boolean trial;
    private long opened;
    private String lastFailure;

    public LlmBreaker(final int failures, final long maxBackoffMillis, final LongSupplier clock) {
        this.failures = Math.max(1, failures);
        this.maxBackoffMillis = Math.max(FIRST_BACKOFF_MILLIS, maxBackoffMillis);
        this.clock = clock;
    }

    /**
     * True if a call may be made now. While open: false; once the pause has
     * ended, true for exactly one trial call until its result is reported.
     */
    public synchronized boolean allow() {
        if (this.openUntil == 0L) {
            return true;
        }
        if (this.clock.getAsLong() < this.openUntil || this.trial) {
            return false;
        }
        this.trial = true;
        return true;
    }

    public synchronized void success() {
        this.consecutive = 0;
        this.openUntil = 0L;
        this.backoff = 0L;
        this.trial = false;
    }

    /** A reserved half-open trial was deferred before a transport verdict; not a failure or a retry. */
    public synchronized void deferred() { this.trial=false; }

    /** A transport failure (timeout, refused connection, HTTP error). */
    public synchronized void failure(final String reason) {
        this.lastFailure = reason;
        this.consecutive++;
        if (this.trial || this.consecutive >= this.failures) {
            this.backoff = this.backoff == 0L ? FIRST_BACKOFF_MILLIS : Math.min(this.maxBackoffMillis, this.backoff * 2L);
            this.openUntil = this.clock.getAsLong() + this.backoff;
            this.opened++;
            this.trial = false;
        }
    }

    public synchronized boolean open() {
        return this.openUntil != 0L && (this.clock.getAsLong() < this.openUntil || this.trial);
    }

    public synchronized JSONObject status() {
        final boolean isOpen = open();
        return KgJson.obj("open", isOpen, "consecutiveFailures", this.consecutive, "failuresToOpen", this.failures,
                "openUntil", isOpen ? this.openUntil : null, "backoffMillis", this.backoff, "timesOpened", this.opened,
                "lastFailure", this.lastFailure);
    }
}
