/*
 *  MetadataExtractor
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

package net.yacy.scoutro.knowledge.extract;

import java.util.List;

import net.yacy.scoutro.knowledge.resolve.Normalizers;

/**
 * Tier 1, page metadata already in the Solr document
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.1): the document publisher, if it is
 * a legal name, as the declared operator of the site; the page coordinate
 * for the single organisation or facility the JSON-LD of the page describes.
 * Runs after {@link JsonLdExtractor}.
 */
public final class MetadataExtractor {

    public static final String NAME = "metadata";
    public static final String VERSION = "1";

    public static final String PUBLISHER_REF = "meta:publisher";

    private final int maxExcerpt;

    public MetadataExtractor(final int maxExcerpt) {
        this.maxExcerpt = maxExcerpt;
    }

    public void extract(final String publisher, final double[] coordinate, final Extraction out) {
        // the subjects of the structured data, before the publisher is added
        final List<Mention> subjects = out.subjects(1);
        final String legal = Normalizers.legalName(publisher);
        if (legal != null) {
            final Mention m = out.add(new Mention(PUBLISHER_REF, Vocabulary.ORGANIZATION, 1));
            m.name = legal;
            m.legalName = legal;
            m.siteOperator = true;
            final String excerpt = Normalizers.clip("publisher: " + publisher, this.maxExcerpt);
            out.add(new Claim(m.ref, Vocabulary.NAME, null, legal, 1, Claim.KIND_METADATA, false, "field:publisher_t", excerpt));
            out.add(new Claim(m.ref, Vocabulary.LEGAL_FORM, null, Normalizers.legalForm(legal), 1, Claim.KIND_METADATA, false,
                    "field:publisher_t", excerpt));
        }
        if (coordinate != null && coordinate.length == 2) {
            final String geo = Normalizers.geo(coordinate[0], coordinate[1]);
            if (geo != null && subjects.size() == 1 && !hasGeo(out, subjects.get(0).ref)) {
                out.add(new Claim(subjects.get(0).ref, Vocabulary.GEO, null, geo, 1, Claim.KIND_METADATA, false,
                        "field:coordinate_p", Normalizers.clip("coordinate: " + geo, this.maxExcerpt)));
            }
        }
        out.ranTier(1);
    }

    private static boolean hasGeo(final Extraction out, final String ref) {
        for (final Claim c : out.claims()) {
            if (c.subject.equals(ref) && Vocabulary.GEO.equals(c.predicate)) {
                return true;
            }
        }
        return false;
    }
}
