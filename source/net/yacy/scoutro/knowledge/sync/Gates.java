/*
 *  Gates
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

import net.yacy.scoutro.knowledge.KgConfig;

/**
 * The gates for scans and extraction (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.5):
 * YaCy's indexing queue, the system load, free heap and, for Solr scans,
 * YaCy's online caution. Lifecycle work (removals, state changes) is never
 * gated.
 */
public final class Gates {

    public static final String INDEXING_QUEUE = "indexing_queue";
    public static final String LOAD = "load";
    public static final String HEAP = "heap";
    public static final String ONLINE_CAUTION = "online_caution";

    /** What the gates read from YaCy and the JVM; replaceable in tests. */
    public interface Probe {
        int indexingQueue();

        /** System load average; negative if unknown. */
        double load();

        long freeHeapBytes();

        /** YaCy's online caution (proxy, local or remote search active), or null. */
        String onlineCaution();
    }

    /** A probe that never closes a gate. */
    public static final Probe IDLE = new Probe() {
        @Override
        public int indexingQueue() {
            return 0;
        }

        @Override
        public double load() {
            return -1d;
        }

        @Override
        public long freeHeapBytes() {
            return Long.MAX_VALUE;
        }

        @Override
        public String onlineCaution() {
            return null;
        }
    };

    private final KgConfig cfg;
    private final Probe probe;

    public Gates(final KgConfig cfg, final Probe probe) {
        this.cfg = cfg;
        this.probe = probe;
    }

    /**
     * @param scan true for a Solr scan (also yields to YaCy's online caution)
     * @return null if the work may run, otherwise the reason
     */
    public String closed(final boolean scan) {
        try {
            if (this.probe.indexingQueue() >= this.cfg.gateMaxIndexingQueue) {
                return INDEXING_QUEUE;
            }
            final double load = this.probe.load();
            if (load >= 0d && load >= this.cfg.gateMaxLoad) {
                return LOAD;
            }
            if (this.probe.freeHeapBytes() < this.cfg.gateMinFreeHeapBytes) {
                return HEAP;
            }
            if (scan && this.probe.onlineCaution() != null) {
                return ONLINE_CAUTION;
            }
            return null;
        } catch (final RuntimeException e) {
            return null; // a failing probe must not stop the graph forever; the guard still bounds the writes
        }
    }
}
