/*
 *  JsonLdCapturePolicy
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

package net.yacy.scoutro.knowledge.budget;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgJson;

/**
 * Decides whether JSON-LD blocks may be captured into the Solr field
 * {@code ld_json_txt} (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, "JSON-LD capture").
 * <p>
 * The field lives in the Solr index, outside the graph directory, so it has
 * its own budget. The runtime evaluates this policy when it measures and sets
 * the flag that the parser reads per document ({@code JsonLdCapture}); when
 * the policy says no, the document is indexed normally without the field, so
 * crawling and indexing are never blocked. The estimate is the sum of
 * {@code kg_doc.jsonld_bytes} plus the bytes captured but not yet
 * synchronised; {@code null} while unknown (graph or sync not running).
 */
public final class JsonLdCapturePolicy {

    public enum State { OFF, ACTIVE, PAUSED }

    public static final String KG_DISABLED = "kg_disabled";
    public static final String JSONLD_DISABLED = "jsonld_disabled";
    public static final String KG_NOT_RUNNING = "kg_not_running";
    public static final String JSONLD_BUDGET = "jsonld_budget";
    public static final String DISK_RESERVE = "disk_reserve";

    private final KgConfig cfg;
    private State state = State.OFF;
    private String reason = KG_DISABLED;
    private Long estimatedBytes;

    public JsonLdCapturePolicy(final KgConfig cfg) {
        this.cfg = cfg;
    }

    public long pauseAtBytes() {
        return this.cfg.jsonldMaxTotalBytes / 100L * this.cfg.pausePercent;
    }

    public long resumeAtBytes() {
        return this.cfg.jsonldMaxTotalBytes / 100L * this.cfg.resumePercent;
    }

    /**
     * Re-evaluates the state.
     *
     * @param kgRunning      whether the graph runtime is running
     * @param estimatedBytes bytes of {@code ld_json_txt} currently in the index (upper bound), null if unknown
     * @param usableBytes    free bytes of the DATA filesystem
     */
    public synchronized State evaluate(final boolean kgRunning, final Long estimatedBytes, final long usableBytes) {
        this.estimatedBytes = estimatedBytes;
        if (!this.cfg.enabled) {
            return set(State.OFF, KG_DISABLED);
        }
        if (!this.cfg.jsonldEnabled) {
            return set(State.OFF, JSONLD_DISABLED);
        }
        if (!kgRunning) {
            return set(State.OFF, KG_NOT_RUNNING);
        }
        final boolean wasPausedForBudget = this.state == State.PAUSED && JSONLD_BUDGET.equals(this.reason);
        final boolean wasPausedForDisk = this.state == State.PAUSED && DISK_RESERVE.equals(this.reason);
        if (estimatedBytes != null
                && (wasPausedForBudget ? estimatedBytes > resumeAtBytes() : estimatedBytes >= pauseAtBytes())) {
            return set(State.PAUSED, JSONLD_BUDGET);
        }
        if (wasPausedForDisk ? usableBytes < this.cfg.growthResumeFloorBytes() : usableBytes < this.cfg.growthFloorBytes()) {
            return set(State.PAUSED, DISK_RESERVE);
        }
        return set(State.ACTIVE, null);
    }

    private State set(final State s, final String r) {
        this.state = s;
        this.reason = r;
        return s;
    }

    public synchronized State state() {
        return this.state;
    }

    public synchronized JSONObject status() {
        return KgJson.obj("configured", this.cfg.jsonldEnabled, "state", this.state.name().toLowerCase(),
                "reason", this.reason, "captureImplemented", true,
                "estimatedBytes", this.estimatedBytes, "maxTotalBytes", this.cfg.jsonldMaxTotalBytes,
                "pauseAtBytes", pauseAtBytes(), "resumeAtBytes", resumeAtBytes(),
                "maxBytesPerDoc", this.cfg.jsonldMaxBytesPerDoc, "maxBlocksPerDoc", this.cfg.jsonldMaxBlocksPerDoc);
    }
}
