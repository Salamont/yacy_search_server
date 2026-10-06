/*
 *  Mention
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

import java.util.Map;
import java.util.TreeMap;

/**
 * Something an extractor found: an organisation, facility, site or service,
 * not yet resolved to an entity. Its identity keys decide resolution
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 4.3); {@link #ref} is stable within one
 * document and extractor and becomes the {@code doc_local} key.
 */
public final class Mention {

    public final String ref;
    public final String type;
    public final int tier;
    public String name;
    /** Facility kind (lower-cased schema.org type); different kinds never merge. */
    public String subkind;
    /** Strong identifiers: scheme -> normalised key value. */
    public final Map<String, String> strongKeys = new TreeMap<>();
    /** Absolute JSON-LD {@code @id}. */
    public String ldId;
    /** Declared operator of the site (imprint, publisher, provider); needs a legal name. */
    public boolean siteOperator;
    /** Legal name including its legal form, if known. */
    public String legalName;
    /**
     * The unnamed operator of the page's site (version 2): a services, prices or careers page that does not say who
     * offers them; resolved by the {@code domain_operator} key to the declared site operator.
     */
    public boolean domainOperator;
    /** A key of the {@code job_posting} scheme (employer, title, location), within the registrable domain. */
    public String jobKey;
    /** A key of the {@code place_name} scheme ({@code de|state|bayern}); global. */
    public String placeKey;
    public Address address;

    public Mention(final String ref, final String type, final int tier) {
        this.ref = ref;
        this.type = type;
        this.tier = tier;
    }

    @Override
    public String toString() {
        return this.type + ":" + this.ref + "(" + this.name + ")";
    }
}
