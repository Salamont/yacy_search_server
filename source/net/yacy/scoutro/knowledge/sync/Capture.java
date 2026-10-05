/*
 *  Capture
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

/**
 * Process-wide link between Solr's update processor and the running graph.
 * <p>
 * Solr creates {@code KgCaptureProcessorFactory} from {@code solrconfig.xml}
 * on its own; the factory finds the change set of the running graph here.
 * While the graph is disabled or stopped there is none, and the processor is
 * skipped: Solr updates then cost one volatile read. Changes made meanwhile
 * are found by the full reconcile that runs at every start.
 */
public final class Capture {

    private static volatile DirtySet active;

    private Capture() {}

    /** The change set of the running graph, or null. */
    public static DirtySet active() {
        return active;
    }

    /** Called by the runtime when the graph starts to process changes. */
    public static void activate(final DirtySet set) {
        active = set;
    }

    /** Called by the runtime when it stops; only the given set is removed. */
    public static void deactivate(final DirtySet set) {
        if (active == set) {
            active = null;
        }
    }
}
