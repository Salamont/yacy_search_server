/*
 *  KgCaptureProcessorFactory
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

package net.yacy.scoutro.knowledge.solr;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.DeleteUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;

import net.yacy.scoutro.knowledge.sync.Capture;
import net.yacy.scoutro.knowledge.sync.DirtySet;

/**
 * Records changed and deleted document IDs of the {@code collection1} core,
 * with the version Solr assigned, for the knowledge graph
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 5.1). Configured in
 * {@code defaults/solr/solrconfig.xml} after
 * {@code DistributedUpdateProcessorFactory}, which assigns the version.
 * <p>
 * Safe in Solr's update path: the event is recorded only after the next
 * processor (the index write) succeeded; recording is one bounded map
 * operation; every {@link Throwable} of the recording is swallowed, so a
 * Solr update never fails because of the graph. The processor is skipped on
 * every other core (webgraph shares the configuration) and while the graph is
 * not running.
 */
public class KgCaptureProcessorFactory extends UpdateRequestProcessorFactory {

    /** The only core the graph follows. */
    public static final String CORE = "collection1";

    private static final AtomicLong FAILURES = new AtomicLong();

    @Override
    public UpdateRequestProcessor getInstance(final SolrQueryRequest req, final SolrQueryResponse rsp,
            final UpdateRequestProcessor next) {
        final DirtySet set = Capture.active();
        if (set == null || req == null || req.getCore() == null || !CORE.equals(req.getCore().getName())) {
            return next;
        }
        return new Processor(next, set);
    }

    /** Recording failures since start (never propagated to Solr). */
    public static long failures() {
        return FAILURES.get();
    }

    private static final class Processor extends UpdateRequestProcessor {
        private final DirtySet set;

        Processor(final UpdateRequestProcessor next, final DirtySet set) {
            super(next);
            this.set = set;
        }

        @Override
        public void processAdd(final AddUpdateCommand cmd) throws IOException {
            super.processAdd(cmd);
            try {
                this.set.added(cmd.getIndexedIdStr(), cmd.getVersion());
            } catch (final Throwable t) {
                FAILURES.incrementAndGet();
            }
        }

        @Override
        public void processDelete(final DeleteUpdateCommand cmd) throws IOException {
            super.processDelete(cmd);
            try {
                if (cmd.isDeleteById()) {
                    this.set.deleted(cmd.getId(), cmd.getVersion());
                } else {
                    this.set.deletedByQuery(cmd.getQuery(), System.currentTimeMillis());
                }
            } catch (final Throwable t) {
                FAILURES.incrementAndGet();
            }
        }
    }
}
