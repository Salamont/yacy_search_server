/*
 *  JsonLdCapture
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.JsonLdBlocks;

/**
 * The bounded JSON-LD capture (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.2), the
 * only change in YaCy's parse and index path.
 * <p>
 * {@code ContentScraper} offers each {@code <script type="application/ld+json">}
 * block while the capture is active; at most {@code maxBlocks} blocks and
 * {@code maxBytes} per document are kept, a block that would exceed them is
 * dropped whole, and only blocks that parse within the depth and node limits
 * and carry a relevant {@code @type} are kept. {@code yacy2solr} then writes
 * the field {@code ld_json_txt} only for documents of followed collections.
 * <p>
 * Both sides read one volatile field per document; nothing here waits, so a
 * paused capture never blocks crawling or indexing: the document is indexed
 * without the field, and its ID goes to a bounded set that the sync turns into
 * {@code kg_doc.jsonld_skipped}. The bytes written but not yet synchronised
 * are tracked per document for the budget estimate.
 */
public final class JsonLdCapture {

    /** The settings while the capture is active. */
    static final class Active {
        final int maxBlocks;
        final int maxBytes;
        final boolean allCollections;
        final Set<String> collections;

        Active(final int maxBlocks, final int maxBytes, final boolean allCollections, final Set<String> collections) {
            this.maxBlocks = maxBlocks;
            this.maxBytes = maxBytes;
            this.allCollections = allCollections;
            this.collections = collections;
        }

        boolean followsAny(final Collection<?> cs) {
            if (cs == null) {
                return false;
            }
            for (final Object c : cs) {
                if (c != null && (this.allCollections || this.collections.contains(c.toString()))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The blocks collected from one document (owned by its scraper). */
    public static final class Blocks {
        private final List<String> blocks = new ArrayList<>(2);
        private int bytes;
        private int dropped;

        public List<String> list() {
            return Collections.unmodifiableList(this.blocks);
        }

        public int bytes() {
            return this.bytes;
        }

        public int dropped() {
            return this.dropped;
        }
    }

    /** Capacity of the skipped-ID and pending-bytes maps. */
    static final int MAX_TRACKED = 100_000;

    private static volatile Active active;
    /** Followed collections while the graph runs but the capture is paused (skips are recorded only for these). */
    private static volatile Active paused;

    private static final Map<String, Integer> PENDING = new ConcurrentHashMap<>();
    private static final AtomicLong PENDING_OVERFLOW_BYTES = new AtomicLong();
    private static final Set<String> SKIPPED = ConcurrentHashMap.newKeySet();
    private static final AtomicLong CAPTURED_DOCS = new AtomicLong();
    private static final AtomicLong SKIPPED_DOCS = new AtomicLong();
    private static final AtomicLong DROPPED_BLOCKS = new AtomicLong();
    private static final AtomicLong INVALID_BLOCKS = new AtomicLong();

    private JsonLdCapture() {
    }

    /** Activates the capture with these limits for the followed collections. */
    public static void activate(final int maxBlocks, final int maxBytes, final boolean allCollections, final Set<String> collections) {
        active = new Active(maxBlocks, maxBytes, allCollections, collections);
        paused = null;
    }

    /** Pauses the capture (budget or disk reserve); documents of followed collections are recorded as skipped. */
    public static void pause(final boolean allCollections, final Set<String> collections) {
        paused = new Active(0, 0, allCollections, collections);
        active = null;
    }

    /** Switches the capture off (graph or capture disabled, runtime stopped). */
    public static void off() {
        active = null;
        paused = null;
    }

    public static boolean isActive() {
        return active != null;
    }

    /**
     * Called by the scraper for one script block: keeps it if the capture is
     * active, the limits allow it and it is relevant JSON-LD.
     *
     * @param blocks the document's blocks so far, null before the first
     * @return the blocks (created on demand), or {@code blocks} unchanged
     */
    public static Blocks offer(final Blocks blocks, final char[] content) {
        final Active a = active;
        if (a == null || content == null) {
            return blocks;
        }
        final Blocks b = blocks == null ? new Blocks() : blocks;
        if (b.blocks.size() >= a.maxBlocks || content.length > a.maxBytes) {
            b.dropped++;
            DROPPED_BLOCKS.incrementAndGet();
            return b;
        }
        final String text = new String(content).trim();
        final int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (b.bytes + bytes > a.maxBytes) {
            b.dropped++;
            DROPPED_BLOCKS.incrementAndGet();
            return b;
        }
        if (!JsonLdBlocks.accept(text)) {
            INVALID_BLOCKS.incrementAndGet();
            return b;
        }
        b.blocks.add(text);
        b.bytes += bytes;
        return b;
    }

    /**
     * Called by {@code yacy2solr}: the value of {@code ld_json_txt} for a
     * document, or null if the field is not written.
     *
     * @param collections the document's {@code collection_sxt}
     * @param scraped     whether the capture was active while the document was parsed
     */
    public static List<String> field(final String id, final Collection<?> collections, final Blocks blocks, final boolean scraped) {
        final Active a = active;
        if (a == null || !scraped) {
            final Active p = a == null ? paused : a;
            if (p != null && id != null && p.followsAny(collections)) {
                skipped(id);
            }
            return null;
        }
        if (id == null || !a.followsAny(collections) || blocks == null || blocks.blocks.isEmpty()) {
            return null;
        }
        if (PENDING.size() < MAX_TRACKED || PENDING.containsKey(id)) {
            PENDING.put(id, blocks.bytes);
        } else {
            PENDING_OVERFLOW_BYTES.addAndGet(blocks.bytes);
        }
        SKIPPED.remove(id);
        CAPTURED_DOCS.incrementAndGet();
        return new ArrayList<>(blocks.blocks);
    }

    private static void skipped(final String id) {
        SKIPPED_DOCS.incrementAndGet();
        if (SKIPPED.size() < MAX_TRACKED) {
            SKIPPED.add(id);
        }
    }

    /** Whether the capture skipped {@code id} (paused while it was indexed). */
    public static boolean wasSkipped(final String id) {
        return SKIPPED.contains(id);
    }

    /** The sync processed {@code id}: its bytes are now counted in {@code kg_doc}; returns whether it was skipped. */
    public static boolean synced(final String id) {
        PENDING.remove(id);
        return SKIPPED.remove(id);
    }

    /** Bytes written to Solr but not yet synchronised (upper bound). */
    public static long pendingBytes() {
        long n = PENDING_OVERFLOW_BYTES.get();
        for (final Integer b : PENDING.values()) {
            n += b;
        }
        return n;
    }

    /** After a completed reconcile every captured document was synchronised: forget the overflow estimate. */
    public static void reconciled() {
        PENDING_OVERFLOW_BYTES.set(0L);
    }

    public static JSONObject status() {
        return KgJson.obj("capturedDocs", CAPTURED_DOCS.get(), "skippedDocs", SKIPPED_DOCS.get(),
                "skippedPending", SKIPPED.size(), "droppedBlocks", DROPPED_BLOCKS.get(), "invalidBlocks", INVALID_BLOCKS.get(),
                "pendingBytes", pendingBytes());
    }

    /** For tests: forget all state. */
    public static void clear() {
        off();
        PENDING.clear();
        PENDING_OVERFLOW_BYTES.set(0L);
        SKIPPED.clear();
        CAPTURED_DOCS.set(0L);
        SKIPPED_DOCS.set(0L);
        DROPPED_BLOCKS.set(0L);
        INVALID_BLOCKS.set(0L);
    }
}
