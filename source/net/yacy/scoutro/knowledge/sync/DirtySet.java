/*
 *  DirtySet
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgIds;
import net.yacy.scoutro.knowledge.KgJson;

/**
 * The bounded, version-carrying change set filled by the Solr update
 * processor (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 5.1).
 * <p>
 * One entry per document ID, coalesced: the highest Solr version wins, and a
 * delete wins over an older add. Each update costs one {@link ConcurrentHashMap}
 * operation; nothing here blocks or fails a Solr update. On overflow new IDs
 * are dropped and {@link #overflowed()} is set, which makes the runtime run a
 * full reconcile. Delete-by-query and a full clear ({@code *:*}) are counted
 * as signals, not resolved to IDs (no search inside Solr's update lock).
 */
public class DirtySet {

    /** One pending change. {@code version} is the absolute Solr version (deletes carry negative versions in Solr). */
    public static final class Event {
        public final String id;
        public final long version;
        public final boolean delete;

        Event(final String id, final long version, final boolean delete) {
            this.id = id;
            this.version = version;
            this.delete = delete;
        }

        @Override
        public String toString() {
            return (this.delete ? "delete " : "add ") + this.id + "@" + this.version;
        }
    }

    private final int capacity;
    private final ConcurrentHashMap<String, Event> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean overflow = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong ignoredIds = new AtomicLong();
    private final AtomicLong queryDeletes = new AtomicLong();
    private final AtomicLong lastQueryDeleteAt = new AtomicLong();
    private final AtomicLong fullResets = new AtomicLong();
    private final AtomicLong maxVersion = new AtomicLong();

    public DirtySet(final int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** An add or replace of {@code id} with Solr {@code version} (after Solr applied it). */
    public void added(final String id, final long version) {
        record(id, version, false);
    }

    /** A delete by ID of {@code id} with Solr {@code version}. */
    public void deleted(final String id, final long version) {
        record(id, version, true);
    }

    private void record(final String id, final long rawVersion, final boolean delete) {
        if (!KgIds.isDocId(id)) {
            // not a YaCy document ID (12 characters of YaCy's Base64): nothing the graph could track
            this.ignoredIds.incrementAndGet();
            return;
        }
        final long version = Math.abs(rawVersion);
        this.maxVersion.accumulateAndGet(version, Math::max);
        if (!this.pending.containsKey(id) && this.pending.size() >= this.capacity) {
            this.dropped.incrementAndGet();
            this.overflow.set(true);
            return;
        }
        this.recorded.incrementAndGet();
        this.pending.merge(id, new Event(id, version, delete), DirtySet::newer);
    }

    /** Coalescing rule: the higher version wins; at equal versions a delete wins. */
    static Event newer(final Event a, final Event b) {
        if (b.version > a.version) {
            return b;
        }
        if (b.version == a.version && b.delete && !a.delete) {
            return b;
        }
        return a;
    }

    /** A delete by query; {@code *:*} is a full clear of the index. */
    public void deletedByQuery(final String query, final long now) {
        final String q = query == null ? "" : query.trim();
        if ("*:*".equals(q) || "*".equals(q)) {
            this.fullResets.incrementAndGet();
        } else {
            this.queryDeletes.incrementAndGet();
        }
        this.lastQueryDeleteAt.set(now);
    }

    /**
     * Takes up to {@code max} events out of the set. An entry that is replaced
     * concurrently by a newer event stays in the set and is taken by a later
     * call, so no event is lost.
     */
    public List<Event> drain(final int max) {
        final List<Event> out = new ArrayList<>(Math.min(max, this.pending.size()));
        final Iterator<Map.Entry<String, Event>> it = this.pending.entrySet().iterator();
        while (it.hasNext() && out.size() < max) {
            final Map.Entry<String, Event> e = it.next();
            if (this.pending.remove(e.getKey(), e.getValue())) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** Puts events back that could not be persisted (the storage guard refused the drain). */
    public void restore(final List<Event> events) {
        for (final Event e : events) {
            this.pending.merge(e.id, e, DirtySet::newer);
        }
    }

    /** True if a change of {@code id} newer than {@code version} is pending (checked right before publishing). */
    public boolean hasNewer(final String id, final long version) {
        final Event e = this.pending.get(id);
        return e != null && e.version > version;
    }

    public int size() {
        return this.pending.size();
    }

    public int capacity() {
        return this.capacity;
    }

    public boolean overflowed() {
        return this.overflow.get();
    }

    /** Clears the overflow flag after a reconcile was scheduled for it. */
    public boolean takeOverflow() {
        return this.overflow.getAndSet(false);
    }

    public long queryDeletes() {
        return this.queryDeletes.get();
    }

    public long lastQueryDeleteAt() {
        return this.lastQueryDeleteAt.get();
    }

    public long fullResets() {
        return this.fullResets.get();
    }

    /** Highest Solr version seen so far (adds and deletes). */
    public long maxVersion() {
        return this.maxVersion.get();
    }

    public JSONObject status() {
        return KgJson.obj("pending", this.pending.size(), "capacity", this.capacity, "overflow", this.overflow.get(),
                "recorded", this.recorded.get(), "dropped", this.dropped.get(), "ignoredIds", this.ignoredIds.get(),
                "queryDeletes", this.queryDeletes.get(), "fullResets", this.fullResets.get(),
                "maxVersion", this.maxVersion.get());
    }
}
