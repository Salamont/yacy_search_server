/*
 *  RefusalSamples
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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;

/**
 * Samples of what the LLM tier dropped or refused (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.7), to see why answers of a
 * model are lost: per sample the reason, the kind of item, the predicate, the types and states of its references, the
 * page and chunk, and an excerpt of at most {@link net.yacy.scoutro.knowledge.extract.LlmExtractor#SAMPLE_CHARS}
 * characters without person names, e-mail addresses or phone numbers. Bounded: in memory only (a restart clears it,
 * nothing goes into the graph, an export or a backup), at most {@link #MAX} samples, {@link #PER_REASON} per reason and
 * {@link #MAX_BYTES} in all, none older than {@link #KEEP_MILLIS}; the oldest go first. No prompt and no whole answer is
 * kept.
 */
final class RefusalSamples {

    static final int MAX = 500;
    static final int PER_REASON = 20;
    static final long MAX_BYTES = 128L * 1024L;
    static final long KEEP_MILLIS = 7L * 24L * 3600_000L;

    private static final class Sample {
        final long at;
        final String reason;
        final JSONObject json;
        final int bytes;

        Sample(final long at, final String reason, final JSONObject json) {
            this.at = at;
            this.reason = reason;
            this.json = json;
            this.bytes = json.toString().length() * 2;
        }
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private final Map<String, Integer> perReason = new HashMap<>();
    private long bytes;
    private long added;

    /** Adds a sample of the validator ({@link net.yacy.scoutro.knowledge.extract.LlmExtractor.Result#samples}). */
    synchronized void add(final long now, final String docId, final int chunkOffset, final String domain, final String promptHash,
            final JSONObject sample) {
        final String reason = sample.optString("reason", "unknown");
        JSONObject json;
        try {
            json = new JSONObject(sample.toString());
        } catch (final org.json.JSONException e) {
            json = new JSONObject();
        }
        KgJson.put(json, "at", now);
        KgJson.put(json, "docId", docId);
        KgJson.put(json, "chunkOffset", chunkOffset);
        KgJson.put(json, "domain", domain);
        KgJson.put(json, "promptHash", promptHash);
        final Sample s = new Sample(now, reason, json);
        expire(now);
        if (this.perReason.getOrDefault(reason, 0) >= PER_REASON) {
            removeOldest(reason);
        }
        while (!this.samples.isEmpty() && (this.samples.size() >= MAX || this.bytes + s.bytes > MAX_BYTES)) {
            removeOldest(null);
        }
        this.samples.addLast(s);
        this.perReason.merge(reason, 1, Integer::sum);
        this.bytes += s.bytes;
        this.added++;
    }

    /** The newest samples first, of one reason or all, at most {@code limit}. */
    synchronized JSONObject list(final long now, final String reason, final int limit) {
        expire(now);
        final JSONArray out = new JSONArray();
        for (final Iterator<Sample> it = this.samples.descendingIterator(); it.hasNext() && out.length() < limit;) {
            final Sample s = it.next();
            if (reason == null || reason.equals(s.reason)) {
                out.put(s.json);
            }
        }
        return KgJson.obj("samples", out, "summary", summaryLocked());
    }

    /** Size and bounds, without excerpts (for the status). */
    synchronized JSONObject summary(final long now) {
        expire(now);
        return summaryLocked();
    }

    private JSONObject summaryLocked() {
        final JSONObject by = new JSONObject();
        for (final Map.Entry<String, Integer> e : this.perReason.entrySet()) {
            KgJson.put(by, e.getKey(), e.getValue());
        }
        return KgJson.obj("size", this.samples.size(), "bytes", this.bytes, "added", this.added, "oldestAt",
                this.samples.isEmpty() ? null : this.samples.peekFirst().at, "byReason", by, "max", MAX, "perReason", PER_REASON,
                "maxBytes", MAX_BYTES, "keepHours", KEEP_MILLIS / 3600_000L);
    }

    private void expire(final long now) {
        while (!this.samples.isEmpty() && this.samples.peekFirst().at < now - KEEP_MILLIS) {
            removeOldest(null);
        }
    }

    /** Removes the oldest sample (of {@code reason}, or of any reason). */
    private void removeOldest(final String reason) {
        for (final Iterator<Sample> it = this.samples.iterator(); it.hasNext();) {
            final Sample s = it.next();
            if (reason == null || reason.equals(s.reason)) {
                it.remove();
                this.bytes -= s.bytes;
                this.perReason.computeIfPresent(s.reason, (k, n) -> n <= 1 ? null : n - 1);
                return;
            }
        }
    }
}
