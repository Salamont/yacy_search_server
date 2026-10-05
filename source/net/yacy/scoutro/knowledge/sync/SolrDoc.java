/*
 *  SolrDoc
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.MetadataExtractor;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;

/**
 * The fields of one Solr document the graph reads (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 5.2), from a real-time get or a scan page. Nothing is ever written back.
 */
public final class SolrDoc {

    public static final String ID = "id";
    public static final String VERSION = "_version_";
    public static final String SKU = "sku";
    public static final String HOST = "host_s";
    public static final String HOST_ID = "host_id_s";
    public static final String HTTPSTATUS = "httpstatus_i";
    public static final String FAILTYPE = "failtype_s";
    public static final String SIGNATURE = "exact_signature_l";
    public static final String COLLECTIONS = "collection_sxt";
    public static final String LANGUAGE = "language_s";
    public static final String LOAD_DATE = "load_date_dt";
    public static final String LD_JSON = "ld_json_txt";
    public static final String TITLE = "title";
    public static final String DESCRIPTION = "description_txt";
    public static final String PUBLISHER = "publisher_t";
    public static final String COORDINATE = "coordinate_p";
    public static final String TEXT = "text_t";

    /** Fields of a scan page: enough for the token, the version and the state. */
    public static final List<String> SCAN_FIELDS = List.of(ID, VERSION, SKU, HTTPSTATUS, FAILTYPE, SIGNATURE, COLLECTIONS, LANGUAGE);

    /** Fields of a real-time get for processing (the text is read separately, for tier 2 candidates only). */
    public static final List<String> PROCESS_FIELDS = List.of(ID, VERSION, SKU, HOST, HOST_ID, HTTPSTATUS, FAILTYPE, SIGNATURE,
            COLLECTIONS, LANGUAGE, LOAD_DATE, LD_JSON, TITLE, DESCRIPTION, PUBLISHER, COORDINATE);

    /** Extractor versions in the input hash: a new version re-extracts at the next processing. */
    static final String EXTRACTORS = JsonLdExtractor.NAME + "/" + JsonLdExtractor.VERSION + "," + MetadataExtractor.NAME + "/"
            + MetadataExtractor.VERSION + "," + RuleExtractor.NAME + "/" + RuleExtractor.VERSION;

    public String id;
    public long version;
    public String url;
    public String host;
    public String hostId;
    public Integer httpstatus;
    public String failtype;
    public Long signature;
    public List<String> collections = Collections.emptyList();
    public String language;
    public Long loadDate;
    public List<String> ldJson = Collections.emptyList();
    public List<String> titles = Collections.emptyList();
    public List<String> descriptions = Collections.emptyList();
    public String publisher;
    public String coordinate;

    /** Reads a Solr document given as a field map (SolrDocument implements {@code Map<String, Object>}). */
    public static SolrDoc of(final Map<String, Object> d) {
        final SolrDoc s = new SolrDoc();
        s.id = string(d.get(ID));
        final Object v = d.get(VERSION);
        s.version = v instanceof Number ? Math.abs(((Number) v).longValue()) : 0L;
        s.url = string(d.get(SKU));
        s.host = string(d.get(HOST));
        s.hostId = string(d.get(HOST_ID));
        final Object st = d.get(HTTPSTATUS);
        s.httpstatus = st instanceof Number ? ((Number) st).intValue() : null;
        s.failtype = string(d.get(FAILTYPE));
        final Object sig = d.get(SIGNATURE);
        s.signature = sig instanceof Number ? ((Number) sig).longValue() : null;
        s.collections = sorted(strings(d.get(COLLECTIONS)));
        s.language = string(d.get(LANGUAGE));
        final Object ld = d.get(LOAD_DATE);
        if (ld instanceof Date) {
            s.loadDate = ((Date) ld).getTime();
        } else if (ld instanceof Number) {
            s.loadDate = ((Number) ld).longValue();
        }
        s.ldJson = strings(d.get(LD_JSON));
        s.titles = strings(d.get(TITLE));
        s.descriptions = strings(d.get(DESCRIPTION));
        s.publisher = string(d.get(PUBLISHER));
        s.coordinate = string(d.get(COORDINATE));
        return s;
    }

    private static String string(final Object o) {
        if (o instanceof Collection) {
            final Collection<?> c = (Collection<?>) o;
            return c.isEmpty() ? null : string(c.iterator().next());
        }
        return o == null ? null : o.toString();
    }

    private static List<String> strings(final Object o) {
        if (o == null) {
            return Collections.emptyList();
        }
        final List<String> out = new ArrayList<>();
        if (o instanceof Collection) {
            for (final Object e : (Collection<?>) o) {
                if (e != null) {
                    out.add(e.toString());
                }
            }
        } else if (o instanceof Object[]) {
            for (final Object e : (Object[]) o) {
                if (e != null) {
                    out.add(e.toString());
                }
            }
        } else {
            out.add(o.toString());
        }
        return out;
    }

    private static List<String> sorted(final List<String> l) {
        return l.isEmpty() ? l : new ArrayList<>(new TreeSet<>(l));
    }

    /** The collections of this document that the graph follows, sorted. */
    public List<String> followed(final KgConfig cfg) {
        final List<String> out = new ArrayList<>();
        for (final String c : this.collections) {
            if (cfg.follows(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Document state by the rule of 4.4: {@code failtype_s=excl} or HTTP
     * 404/410 is gone; any other fail document, or another non-200 status, is
     * unavailable; everything else is active.
     */
    public int state() {
        final int status = this.httpstatus == null ? 200 : this.httpstatus;
        if ("excl".equals(this.failtype) || status == 404 || status == 410) {
            return Aggregates.STATE_GONE;
        }
        if (this.failtype != null || (status != 200 && status != 0)) {
            return Aggregates.STATE_UNAVAILABLE;
        }
        return Aggregates.STATE_ACTIVE;
    }

    /**
     * 8 bytes of SHA-256 over {@code sku | httpstatus_i | exact_signature_l |
     * sorted(collection_sxt) | language_s}: what the reconcile compares.
     */
    public byte[] token() {
        final MessageDigest md = sha256();
        update(md, this.url);
        update(md, this.httpstatus == null ? "" : this.httpstatus.toString());
        update(md, this.signature == null ? "" : this.signature.toString());
        update(md, String.join(",", this.collections));
        update(md, this.language);
        return Arrays.copyOf(md.digest(), 8);
    }

    /**
     * 16 bytes of SHA-256 over everything tiers 1 and 2 read, with the
     * extractor versions; the text enters through {@code exact_signature_l}.
     * Equal hashes mean an equal extraction, so only the lifecycle changes.
     */
    public byte[] inputHash() {
        final MessageDigest md = sha256();
        update(md, EXTRACTORS);
        update(md, this.url);
        update(md, this.host);
        update(md, this.language);
        update(md, this.signature == null ? "" : this.signature.toString());
        for (final String b : this.ldJson) {
            update(md, b);
        }
        update(md, "\u0002");
        for (final String t : this.titles) {
            update(md, t);
        }
        update(md, "\u0002");
        update(md, this.publisher);
        update(md, this.coordinate);
        return Arrays.copyOf(md.digest(), 16);
    }

    /** UTF-8 bytes of the captured JSON-LD (the estimate of the field in Solr). */
    public long jsonLdBytes() {
        long n = 0L;
        for (final String b : this.ldJson) {
            n += b.getBytes(StandardCharsets.UTF_8).length;
        }
        return n;
    }

    /** {@code coordinate_p} ("lat,lon") as two doubles; null if absent or malformed. */
    public double[] coordinates() {
        if (this.coordinate == null) {
            return null;
        }
        final String[] p = this.coordinate.split(",");
        if (p.length != 2) {
            return null;
        }
        try {
            return new double[] {Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())};
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(final MessageDigest md, final String s) {
        if (s != null) {
            md.update(s.getBytes(StandardCharsets.UTF_8));
        }
        md.update((byte) 0);
    }
}
