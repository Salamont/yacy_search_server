/*
 *  EmbeddedSolrSource
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;

/**
 * {@link SolrSource} on the embedded {@code collection1} core. The client is
 * resolved for every call, because YaCy can reconnect its index; a missing
 * embedded core (remote Solr only) is reported as {@link Unsupported}.
 */
public final class EmbeddedSolrSource implements SolrSource {

    /** The embedded core is not available (remote Solr only, or the index is being switched). */
    public static final class Unsupported extends IOException {
        private static final long serialVersionUID = 1L;

        public Unsupported(final String message) {
            super(message);
        }
    }

    private final Supplier<SolrClient> client;

    public EmbeddedSolrSource(final Supplier<SolrClient> client) {
        this.client = client;
    }

    private SolrClient client() throws Unsupported {
        final SolrClient c = this.client.get();
        if (c == null) {
            throw new Unsupported("no embedded Solr core");
        }
        return c;
    }

    @Override
    public Map<String, SolrDoc> get(final Collection<String> ids, final List<String> fields) throws IOException {
        final Map<String, SolrDoc> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        if (ids.size() > MAX_GET) {
            throw new IllegalArgumentException("at most " + MAX_GET + " IDs per real-time get");
        }
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("fl", String.join(",", fields));
        try {
            final SolrDocumentList l = client().getById(ids, p);
            for (final SolrDocument d : l) {
                final SolrDoc s = SolrDoc.of(d);
                if (s.id != null) {
                    out.put(s.id, s);
                }
            }
        } catch (final SolrServerException | RuntimeException e) {
            throw new IOException("real-time get failed: " + describe(e), e);
        }
        return out;
    }

    @Override
    public String text(final String id) throws IOException {
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("fl", SolrDoc.ID + "," + SolrDoc.TEXT);
        try {
            final SolrDocument d = client().getById(id, p);
            if (d == null) {
                return null;
            }
            final Object t = d.getFieldValue(SolrDoc.TEXT);
            return t == null ? "" : t.toString();
        } catch (final SolrServerException | RuntimeException e) {
            throw new IOException("real-time get failed: " + describe(e), e);
        }
    }

    @Override
    public Page scan(final String after, final int rows, final Collection<String> collections, final long minVersion) throws IOException {
        if (collections != null && collections.isEmpty()) {
            return new Page(new ArrayList<>(), false); // no collection followed: nothing in scope
        }
        final SolrQuery q = new SolrQuery("*:*");
        if (after != null) {
            q.addFilterQuery(SolrDoc.ID + ":{\"" + after + "\" TO *]");
        }
        q.addFilterQuery(collectionFilter(collections));
        if (minVersion > 0L) {
            q.addFilterQuery(SolrDoc.VERSION + ":[" + minVersion + " TO *]");
        }
        q.setSort(SolrDoc.ID, SolrQuery.ORDER.asc);
        q.setRows(rows);
        q.setFields(SolrDoc.SCAN_FIELDS.toArray(new String[0]));
        try {
            final QueryResponse r = client().query(q);
            final NamedList<?> header = r.getResponseHeader();
            final boolean partial = header != null && Boolean.TRUE.equals(header.get("partialResults"));
            final List<SolrDoc> docs = new ArrayList<>();
            if (r.getResults() != null) {
                for (final SolrDocument d : r.getResults()) {
                    docs.add(SolrDoc.of(d));
                }
            }
            return new Page(docs, partial);
        } catch (final SolrServerException | RuntimeException e) {
            throw new IOException("scan failed: " + describe(e), e);
        }
    }

    /** Exception class and message, bounded (status and events). */
    static String describe(final Exception e) {
        final String m = e.getMessage();
        final String s = e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    /** {@code collection_sxt:("a" OR "b")}, or any collection; names are validated by KgConfig ([A-Za-z0-9_-]). */
    static String collectionFilter(final Collection<String> collections) {
        if (collections == null) {
            return SolrDoc.COLLECTIONS + ":[* TO *]";
        }
        final StringBuilder sb = new StringBuilder(SolrDoc.COLLECTIONS).append(":(");
        boolean first = true;
        for (final String c : collections) {
            if (!first) {
                sb.append(" OR ");
            }
            sb.append('"').append(c).append('"');
            first = false;
        }
        return sb.append(')').toString();
    }
}
