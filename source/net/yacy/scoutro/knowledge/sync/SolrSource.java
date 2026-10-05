/*
 *  SolrSource
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
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Read access to the embedded Solr core {@code collection1}; the graph never
 * writes to Solr (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 5.6).
 */
public interface SolrSource {

    /** Maximum IDs per real-time get. */
    int MAX_GET = 100;

    /** One page of a scan in ascending ID order. */
    final class Page {
        public final List<SolrDoc> docs;
        /** Solr reported partial results (a time limit or a shard failed): the page must not be trusted. */
        public final boolean partial;

        public Page(final List<SolrDoc> docs, final boolean partial) {
            this.docs = docs;
            this.partial = partial;
        }
    }

    /**
     * Real-time get (Solr's {@code /get}, served from the update log): sees
     * uncommitted adds and deletes. At most {@link #MAX_GET} IDs.
     *
     * @return the documents found, by ID; a missing ID is absent from Solr
     */
    Map<String, SolrDoc> get(Collection<String> ids, List<String> fields) throws IOException;

    /** The stored text of one document for tier 2, or null if absent. */
    String text(String id) throws IOException;

    /**
     * Committed documents with an ID greater than {@code after} (all if null)
     * in ascending byte order, with {@link SolrDoc#SCAN_FIELDS}.
     *
     * @param collections the followed collections; null for any document that has a collection
     * @param minVersion   only documents with {@code _version_ >= minVersion}; 0 for all
     */
    Page scan(String after, int rows, Collection<String> collections, long minVersion) throws IOException;
}
