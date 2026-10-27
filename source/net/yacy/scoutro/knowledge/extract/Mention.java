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
    /**
     * 0.13.0: a client the page documents for its operator ({@code BusinessRules.references}): resolved by the {@code client_name}
     * key, its name without the legal form within the registrable domain of the provider page.
     */
    public boolean client;
    /** A key of the {@code job_posting} scheme (employer, title, location), within the registrable domain. */
    public String jobKey;
    /**
     * A JSON-LD job posting (0.10.6): the page its {@code url} declares, and the key of its place of work; what it must not
     * contradict to join the advertisement of its page.
     */
    public String postingUrl;
    public String jobPlace;
    /** A key of the {@code place_name} scheme ({@code de|state|bayern}); global. */
    public String placeKey;
    /** A key of the {@code service_name} scheme (provider and category or name), within the registrable domain. */
    public String serviceKey;
    /**
     * The service key comes from a provider the rules do not see (the JSON-LD {@code provider} of a service, an offer of the
     * LLM tier): it enters the resolution only with {@code scoutro.kg.identity.providerServiceKey} (WP-F, identity change).
     */
    public boolean providerServiceKey;
    /**
     * A key of the {@code product_url} scheme ({@link net.yacy.scoutro.knowledge.resolve.Normalizers#productUrl}): the page of the
     * product or service its node declares, unique among the services of its page; used with
     * {@code scoutro.kg.identity.productUrl} (WP-F, identity change).
     */
    public String productUrl;
    /**
     * 0.14.0: the key of the {@code facility_kind} scheme of a facility named only by its kind (its kind and its place,
     * {@link PlaceEvidence#placeKey}), within the registrable domain.
     */
    public String kindKey;

    /** The provider part of a service key: the site's operator, or the named organisation or facility; null if unnamed. */
    public static String providerKey(final Mention provider) {
        if (provider == null) {
            return null;
        }
        if (provider.siteOperator || provider.domainOperator) {
            return "operator";
        }
        final String k = net.yacy.scoutro.knowledge.resolve.Normalizers.key(provider.name);
        return k == null ? null : provider.type + ":" + (provider.subkind == null ? "" : provider.subkind + ":") + k;
    }
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
