/*
 *  JsonLdExtractor
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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.scoutro.knowledge.resolve.OrganisationIds;

/**
 * Tier 1, structured data: schema.org JSON-LD blocks of a page
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.1). Reads organisations, facilities
 * (LocalBusiness and subtypes), sites, services, their names, identifiers,
 * addresses, contact values and the relations between them. Persons are
 * ignored. Every claim keeps its location in the block ({@code jsonld:0/...}).
 * <p>
 * Operator rule: an organisation is the declared operator of the site
 * ({@code site_operator} key) only if it is the {@code publisher} or
 * {@code provider} of a WebSite/WebPage, or describes itself with a
 * {@code url} or {@code sameAs} on the page's own registrable domain, and its
 * name carries a legal form; on its own domain without a legal form (a short
 * name) it is the domain's operator ({@code domain_operator} key, 0.10.6),
 * joined to the domain's one declared operator. Version 7 (0.13.0): an
 * organisation without a {@code url} whose {@code @id} is the root of the page's
 * own registrable domain ({@code https://www.muster.de/#organization}, language
 * segments aside) describes itself on its own domain as well, if it is the only
 * organisation of a root {@code @id} on the page; an {@code @id} below the root (a
 * company's profile on a portal) does not (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 41).
 * <p>
 * Version 3 (vocabulary 2) adds: offers and prices ({@code Offer},
 * {@code price}, {@code priceCurrency}, {@code priceSpecification},
 * {@code priceRange}, {@code makesOffer}, {@code hasOfferCatalog}, a
 * service's {@code offers}); service categories from {@code name} and
 * {@code serviceType}; the industry of a business type; the relations
 * {@code memberOf}, {@code parentOrganization}, {@code subOrganization},
 * {@code sponsor}, {@code funder}, {@code brand}; contact points, fax and
 * company profiles in {@code sameAs}; {@code JobPosting} (if jobs are on for
 * the document's collection); the declared audience ({@code audience},
 * {@code BusinessAudience}, {@code PeopleAudience}, {@code areaServed},
 * {@code eligibleCustomerType}, {@code eligibleRegion}). Every value comes
 * from a property of the block, quoted in the evidence.
 */
public final class JsonLdExtractor {

    public static final String NAME = "jsonld";
    public static final String VERSION = "7"; // 7: the root @id of the own domain without url (0.13.0); 6: money and product roles (WP-F)
    /**
     * Prefix of the synthetic {@code ld_id} of a brand or maker without an {@code @id} (0.11.5): its name, scoped to the host.
     * No version of its own in 0.11.5 (the version is part of every page's extractor key, a new one reads every page again);
     * version 7 comes with the re-extraction of {@code rule/9}, which reads every page once anyway.
     */
    public static final String BRAND_ID = "brand:";

    private final int maxExcerpt;

    public JsonLdExtractor(final int maxExcerpt) {
        this.maxExcerpt = maxExcerpt;
    }

    /** Context of one extraction run. */
    private final class Run {
        final String pageUrl;
        final String host;
        final String domain;
        final String callingCode;
        final String country;
        final Extraction out;
        final ExtractContext ctx;
        /** The described nodes of each {@code @id}: one id may name nodes of several kinds (an error of the source). */
        final Map<String, List<JSONObject>> byId = new HashMap<>();
        /** The mention of each node with an {@code @id}, by id and graph type ({@link #idKey}). */
        final Map<String, String> refById = new HashMap<>();
        /** The names of the organisations and sites the blocks describe, and of the site's domain, as keys: no position title. */
        final Set<String> names = new HashSet<>();
        /** Products and services a seller of the page offers (WP-F): no own-shop offer of the operator. */
        final Set<String> sold = new HashSet<>();
        /**
         * The {@code @id}s of the organisations the blocks describe at the root of the page's own registrable domain (0.13.0): the
         * site's own organisation by its {@code @id} only if there is exactly one ({@code ownRoot}).
         */
        final Set<String> rootIds = new HashSet<>();
        /** The key of the domain's label ("drolshagens" of drolshagens.de), or null. */
        final String domainLabel;
        /**
         * Above zero while the nodes of a {@code brand} or {@code manufacturer} property are read (0.11.5): a brand or a maker is
         * never the site's operator by its own {@code url}, gets no social profiles and one host-wide identity by its name.
         */
        int brandDepth;
        /** While true, {@code sameAs} profiles of a node are not stored (a brand's profiles are not the operator's). */
        boolean profilesOff;

        Run(final String pageUrl, final String host, final String language, final Extraction out, final ExtractContext ctx) {
            this.pageUrl = pageUrl;
            this.host = host;
            this.domain = Normalizers.registrableDomain(host);
            this.callingCode = Normalizers.countryCallingCode(host, language);
            this.country = BusinessFacts.country(host);
            this.out = out;
            this.ctx = ctx == null ? ExtractContext.none() : ctx;
            final int dot = this.domain == null ? -1 : this.domain.indexOf('.');
            final String label = dot > 0 ? Normalizers.key(this.domain.substring(0, dot)) : null;
            this.domainLabel = label;
            if (label != null) {
                this.names.add(label);
            }
        }

        boolean inBrand() {
            return this.brandDepth > 0;
        }
    }

    public void extract(final List<String> blocks, final String pageUrl, final String host, final String language,
            final Extraction out) {
        extract(blocks, pageUrl, host, language, out, ExtractContext.none());
    }

    public void extract(final List<String> blocks, final String pageUrl, final String host, final String language,
            final Extraction out, final ExtractContext ctx) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        final Run run = new Run(pageUrl, host, language, out, ctx);
        final List<Object> roots = new ArrayList<>();
        for (final String block : blocks) {
            final Object root = JsonLdBlocks.parse(block);
            roots.add(root);
            if (root == null) {
                out.invalidBlock();
            } else {
                index(run, root, 0);
            }
        }
        for (int i = 0; i < roots.size(); i++) {
            final Object root = roots.get(i);
            if (root != null) {
                top(run, root, "jsonld:" + i);
            }
        }
        ownOffers(run);
        uniqueProductUrls(out);
        BusinessFacts.ownPlaces(out, host, 1);
        out.ranTier(1);
    }

    /**
     * A product page names one product (WP-F): a page address that several services of this page declare (a list page, the
     * page itself) is no product page and no {@code product_url} key of any of them.
     */
    static void uniqueProductUrls(final Extraction out) {
        final Map<String, Integer> seen = new HashMap<>();
        for (final Mention m : out.mentions()) {
            if (m.productUrl != null) {
                seen.merge(m.productUrl, 1, Integer::sum);
            }
        }
        for (final Mention m : out.mentions()) {
            if (m.productUrl != null && seen.get(m.productUrl) > 1) {
                m.productUrl = null;
            }
        }
    }

    /** Collects nodes with an {@code @id}, so that bare references can be followed. */
    private void index(final Run run, final Object v, final int depth) {
        if (depth > JsonLdBlocks.MAX_DEPTH) {
            return;
        }
        if (v instanceof JSONArray) {
            final JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) {
                index(run, a.opt(i), depth + 1);
            }
        } else if (v instanceof JSONObject) {
            final JSONObject o = (JSONObject) v;
            final String id = absoluteId(run, o.optString("@id", null));
            if (id != null && o.length() > 1) {
                run.byId.computeIfAbsent(id, k -> new ArrayList<>()).add(o);
            }
            final String kind = graphType(JsonLdBlocks.types(o));
            if (id != null && Vocabulary.ORGANIZATION.equals(kind) && OrganisationIds.root(id, run.domain)) {
                run.rootIds.add(id);
            }
            boolean site = false;
            for (final String t : JsonLdBlocks.types(o)) {
                site |= "website".equals(bare(t));
            }
            if (site || Vocabulary.ORGANIZATION.equals(kind) || Vocabulary.FACILITY.equals(kind)) {
                for (final String key : new String[] {"name", "legalName", "alternateName"}) {
                    for (final String n : strings(o.opt(key))) {
                        final String k = Normalizers.key(n);
                        if (k != null) {
                            run.names.add(k);
                        }
                    }
                }
            }
            for (final String k : o.keySet()) {
                final Object c = o.opt(k);
                if (c instanceof JSONObject || c instanceof JSONArray) {
                    index(run, c, depth + 1);
                }
            }
        }
    }

    private void top(final Run run, final Object v, final String path) {
        if (v instanceof JSONArray) {
            final JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) {
                top(run, a.opt(i), path + "/" + i);
            }
            return;
        }
        if (!(v instanceof JSONObject)) {
            return;
        }
        final JSONObject o = (JSONObject) v;
        final Object graph = o.opt("@graph");
        if (graph instanceof JSONArray) {
            final JSONArray a = (JSONArray) graph;
            for (int i = 0; i < a.length(); i++) {
                top(run, a.opt(i), path + "/@graph/" + i);
            }
            return;
        }
        node(run, o, path, 0, null);
    }

    /**
     * Handles one node and returns the ref of its mention, or null if it is
     * not an entity of the vocabulary. {@code forcedType} reads a typeless
     * node with an address as a site (the {@code location} of a facility).
     */
    private String node(final Run run, final JSONObject in, final String path, final int depth, final String forcedType) {
        if (depth > JsonLdBlocks.MAX_DEPTH) {
            return null;
        }
        JSONObject o = in;
        String p = path;
        final String id = absoluteId(run, o.optString("@id", null));
        if (id != null && o.length() <= 2) {
            // a bare {"@id": ...} reference to a node described elsewhere on the page: the node of the kind the reference expects
            // (0.10.6); an id that names only nodes of another kind (the posting itself, a page) names no such node
            final String expected = graphType(JsonLdBlocks.types(o)) != null ? graphType(JsonLdBlocks.types(o)) : forcedType;
            final String known = known(run, id, expected);
            if (known != null) {
                return known;
            }
            final List<JSONObject> described = run.byId.get(id);
            if (described != null) {
                JSONObject full = null;
                boolean other = false;
                for (final JSONObject d : described) {
                    if (d == o) {
                        continue;
                    }
                    if (!compatible(expected, d)) {
                        other = true;
                    } else if (full == null || rank(graphType(JsonLdBlocks.types(d))) > rank(graphType(JsonLdBlocks.types(full)))) {
                        full = d;
                    }
                }
                if (full != null) {
                    o = full;
                } else if (other) {
                    return null;
                }
            }
        }
        String type = null;
        String schemaType = null;
        boolean page = false;
        final List<String> declared = JsonLdBlocks.types(o);
        for (final String t : declared) {
            if ("person".equals(bare(t)) || "patient".equals(bare(t))) {
                return null; // persons are never entities (O7), whatever property names them
            }
        }
        for (final String t : declared) {
            final String g = Vocabulary.typeOf(t);
            if (g != null && (type == null || rank(g) > rank(type))) {
                type = g;
                schemaType = t;
            }
            page |= JsonLdBlocks.pageType(t);
        }
        if (type == null && page) {
            pageNode(run, o, p, depth);
            return null;
        }
        if (type == null && (declared.isEmpty() || Vocabulary.SITE.equals(forcedType) || Vocabulary.SERVICE.equals(forcedType))) {
            // a typeless node takes the type its property implies; a node of an unknown type only as a site or service
            type = forcedType;
        }
        if (type == null) {
            return null;
        }
        if (Vocabulary.JOB.equals(type) && !run.ctx.jobs) {
            if (run.ctx.jobSignals) {
                jobHints(run, o, p, depth); // the software hints of the advertisement, for its declared employer only
            }
            return null; // job objects are switched on per collection
        }
        if (id != null && run.refById.get(idKey(id, type)) != null) {
            return run.refById.get(idKey(id, type)); // the same node again (nested and at the top); another kind is another node
        }
        final Mention existing = run.out.mention(p);
        if (existing != null) {
            return existing.ref;
        }
        final Mention m = run.out.add(new Mention(p, type, 1));
        if (id != null) {
            run.refById.put(idKey(id, type), m.ref);
            m.ldId = id;
        }
        m.subkind = Vocabulary.FACILITY.equals(type) ? Vocabulary.facilityKind(schemaType) : null;

        String name = Normalizers.clip(string(o.opt(Vocabulary.JOB.equals(type) && o.has("title") ? "title" : "name")), 300);
        if (Vocabulary.JOB.equals(type) && name != null && run.names.contains(Normalizers.key(name))) {
            name = null; // the title names the organisation, its site or its domain: no position (0.10.6); the posting stays
        }
        final String legal = Vocabulary.JOB.equals(type) ? null
                : Normalizers.legalName(string(o.opt("legalName")) != null ? string(o.opt("legalName")) : name);
        m.name = name != null ? name : legal;
        m.legalName = legal;
        literal(run, m, Vocabulary.NAME, m.name, p + "/name", "name: " + m.name);
        if (legal != null && !legal.equals(m.name)) {
            literal(run, m, Vocabulary.ALIAS, legal, p + "/legalName", "legalName: " + legal);
        }
        if (legal != null && Vocabulary.ORGANIZATION.equals(type)) {
            literal(run, m, Vocabulary.LEGAL_FORM, Normalizers.legalForm(legal), p + "/legalName", "legalName: " + legal);
        }
        for (final String alt : strings(o.opt("alternateName"))) {
            literal(run, m, Vocabulary.ALIAS, Normalizers.clip(alt, 300), p + "/alternateName", "alternateName: " + alt);
        }

        // 0.11.5: a node under "brand" or "manufacturer" is a brand or a maker, not the operator, unless it carries the domain's own
        // name (production: a product's brand "Golden-Ingwer" with the shop's url became the shop's operator and took its profiles);
        // without an @id its name is its identity on this host, so every product page names the same brand
        final boolean brandNode = run.inBrand() && Vocabulary.ORGANIZATION.equals(type)
                && !(m.name != null && run.domainLabel != null && run.domainLabel.equals(Normalizers.key(m.name)));
        if (brandNode && id == null && m.name != null && Normalizers.key(m.name) != null) {
            m.ldId = BRAND_ID + Normalizers.key(m.name);
        }

        identifiers(run, m, o, p);
        run.profilesOff = brandNode;
        try {
            contact(run, m, o, p);
        } finally {
            run.profilesOff = false;
        }
        business(run, m, o, p, schemaType, depth);

        // the organisation that describes itself on its own domain ({@code url} or {@code sameAs}, or without a url the root @id of
        // the domain, 0.13.0), or a declared publisher, is the site operator; without a legal form it is the domain's operator by the
        // name it uses, a short name (0.10.6)
        if (Vocabulary.ORGANIZATION.equals(type) && !brandNode && (ownDomain(run, o) || ownRoot(run, o, id))) {
            if (legal != null) {
                m.siteOperator = true;
            } else if (m.name != null) {
                m.domainOperator = true;
            }
        }

        relations(run, m, o, p, depth);
        return m.ref;
    }

    /**
     * The brand {@code brand} names is the organisation's own: it links the page's own domain, or a word of its name (four letters
     * or more) is a word of the organisation's name, its legal name or the domain's label, or starts one ("MusterCRM" of "Muster
     * Software GmbH", "Pianohaus Edition" of "Das Pianohaus GmbH"; not "SCHIMMEL").
     */
    private static boolean ownBrand(final Run run, final Mention owner, final Mention brand, final JSONObject brandNode) {
        if (ownDomain(run, brandNode)) {
            return true;
        }
        final String key = Normalizers.key(brand.name);
        if (key == null) {
            return false;
        }
        for (final String own : new String[] {owner.name, owner.legalName, run.domainLabel}) {
            final String ok = own == null ? null : Normalizers.key(own);
            if (ok == null) {
                continue;
            }
            for (final String w : key.split(" ")) {
                for (final String o : ok.split(" ")) {
                    if (Math.min(w.length(), o.length()) >= 4 && (w.startsWith(o) || o.startsWith(w))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 0.13.0: the node without a {@code url} is the site's own organisation by its {@code @id}: the root of the page's own registrable
     * domain, and the only organisation the page's blocks describe there (two organisations of root {@code @id}s, the site's and a
     * partner's {@code #beispiel-ag}, leave both apart).
     */
    private static boolean ownRoot(final Run run, final JSONObject o, final String id) {
        return id != null && strings(o.opt("url")).isEmpty() && run.rootIds.size() == 1 && run.rootIds.contains(id);
    }

    /** The node links the page's own registrable domain with its {@code url} or its {@code sameAs}. */
    private static boolean ownDomain(final Run run, final JSONObject o) {
        if (run.domain == null) {
            return false;
        }
        final List<String> links = new ArrayList<>(strings(o.opt("url")));
        links.addAll(strings(o.opt("sameAs")));
        for (final String link : links) {
            final String url = Normalizers.url(absolute(run, link));
            try {
                if (url != null && run.domain.equals(Normalizers.registrableDomain(URI.create(url).getHost()))) {
                    return true;
                }
            } catch (final IllegalArgumentException e) {
                // no address
            }
        }
        return false;
    }

    /** The graph type of the declared types of a node, the highest by {@link #rank}; null if none is one. */
    private static String graphType(final List<String> declared) {
        String type = null;
        for (final String t : declared) {
            final String g = Vocabulary.typeOf(t);
            if (g != null && (type == null || rank(g) > rank(type))) {
                type = g;
            }
        }
        return type;
    }

    /** {@code id} and graph {@code type}: the key of a node's mention in {@link Run#refById}. */
    private static String idKey(final String id, final String type) {
        return id + "\u0000" + type;
    }

    /** The graph types a reference expecting {@code expected} may name: an organisation may be a facility, a place too. */
    private static List<String> accepted(final String expected) {
        if (Vocabulary.ORGANIZATION.equals(expected)) {
            return List.of(Vocabulary.FACILITY, Vocabulary.ORGANIZATION);
        }
        if (Vocabulary.SITE.equals(expected)) {
            return List.of(Vocabulary.FACILITY, Vocabulary.SITE);
        }
        return expected == null ? List.of(Vocabulary.FACILITY, Vocabulary.ORGANIZATION, Vocabulary.SITE, Vocabulary.SERVICE, Vocabulary.JOB)
                : List.of(expected);
    }

    /** The mention of a node with {@code id} of a kind the reference expects; null if none is known yet. */
    private static String known(final Run run, final String id, final String expected) {
        for (final String type : accepted(expected)) {
            final String ref = run.refById.get(idKey(id, type));
            if (ref != null) {
                return ref;
            }
        }
        return null;
    }

    /** A described node may stand for a reference that expects {@code expected}: an untyped node always, a page never. */
    private static boolean compatible(final String expected, final JSONObject node) {
        final List<String> declared = JsonLdBlocks.types(node);
        if (declared.isEmpty()) {
            return true;
        }
        final String type = graphType(declared);
        return type == null ? expected == null : accepted(expected).contains(type);
    }

    /** Graph types win over each other in this order when a node has several types. */
    private static int rank(final String type) {
        if (type == null) {
            return 0;
        }
        switch (type) {
            case Vocabulary.FACILITY:
                return 4;
            case Vocabulary.ORGANIZATION:
                return 3;
            case Vocabulary.SITE:
                return 2;
            default:
                return 1;
        }
    }

    /** WebSite/WebPage: its publisher or provider is the declared operator of the site. */
    private void pageNode(final Run run, final JSONObject o, final String p, final int depth) {
        for (final String key : new String[] {"publisher", "provider"}) {
            for (final Object v : values(o.opt(key))) {
                if (v instanceof JSONObject) {
                    final String ref = node(run, (JSONObject) v, p + "/" + key, depth + 1, null);
                    final Mention m = ref == null ? null : run.out.mention(ref);
                    if (m != null && Vocabulary.ORGANIZATION.equals(m.type) && m.legalName != null) {
                        m.siteOperator = true;
                    }
                }
            }
        }
        for (final String key : new String[] {"about", "mainEntity"}) {
            for (final Object v : values(o.opt(key))) {
                if (v instanceof JSONObject) {
                    node(run, (JSONObject) v, p + "/" + key, depth + 1, null);
                }
            }
        }
    }

    private void identifiers(final Run run, final Mention m, final JSONObject o, final String p) {
        final String vat = Normalizers.vat(string(o.opt("vatID")));
        if (vat != null) {
            m.strongKeys.put(Vocabulary.VAT, vat);
            literal(run, m, Vocabulary.ID_VAT, vat, p + "/vatID", "vatID: " + vat);
        }
        final String lei = Normalizers.lei(string(o.opt("leiCode")));
        if (lei != null) {
            m.strongKeys.put(Vocabulary.LEI, lei);
            literal(run, m, Vocabulary.ID_LEI, lei, p + "/leiCode", "leiCode: " + lei);
        }
        for (final String same : strings(o.opt("sameAs"))) {
            final String q = Normalizers.wikidata(same);
            if (q != null && !m.strongKeys.containsKey(Vocabulary.WIKIDATA)) {
                m.strongKeys.put(Vocabulary.WIKIDATA, q);
                literal(run, m, Vocabulary.ID_WIKIDATA, q, p + "/sameAs", "sameAs: " + same);
            }
        }
        for (final Object v : values(o.opt("identifier"))) {
            if (!(v instanceof JSONObject)) {
                continue;
            }
            final JSONObject pv = (JSONObject) v;
            final String prop = string(pv.opt("propertyID"));
            final String value = string(pv.opt("value"));
            if (prop == null || value == null) {
                continue;
            }
            final String lp = prop.toLowerCase(java.util.Locale.ROOT);
            if (lp.contains("ik") && lp.length() <= 32 && !lp.contains("wiki")) {
                final String ik = Normalizers.ik(value);
                if (ik != null) {
                    m.strongKeys.put(Vocabulary.IK, ik);
                    literal(run, m, Vocabulary.ID_IK, ik, p + "/identifier", prop + ": " + value);
                }
            } else if (lp.contains("register") || lp.matches(".*\\b(hra|hrb|vr|gnr|pr)\\b.*")) {
                final Normalizers.Register r = RuleExtractor.register(value);
                if (r != null) {
                    m.strongKeys.put(Vocabulary.REGISTER, r.key);
                    literal(run, m, Vocabulary.ID_REGISTER, r.display, p + "/identifier", prop + ": " + value);
                }
            }
        }
    }

    private void contact(final Run run, final Mention m, final JSONObject o, final String p) {
        for (final Object v : values(o.opt("address"))) {
            if (v instanceof JSONObject) {
                final JSONObject a = (JSONObject) v;
                final Address addr = Address.of(string(a.opt("streetAddress")), Normalizers.postalCode(string(a.opt("postalCode"))),
                        string(a.opt("addressLocality")), country(a.opt("addressCountry")));
                address(run, m, addr, p + "/address");
            } else if (v instanceof String) {
                literal(run, m, Vocabulary.ADDRESS, Normalizers.clip((String) v, 300), p + "/address", "address: " + v);
            }
        }
        final Object geo = o.opt("geo");
        if (geo instanceof JSONObject) {
            final JSONObject g = (JSONObject) geo;
            final double lat = number(g.opt("latitude"));
            final double lon = number(g.opt("longitude"));
            final String gv = Normalizers.geo(lat, lon);
            literal(run, m, Vocabulary.GEO, gv, p + "/geo", "geo: " + lat + ", " + lon);
        }
        for (final String t : strings(o.opt("telephone"))) {
            literal(run, m, Vocabulary.PHONE, Normalizers.phone(t, run.callingCode), p + "/telephone", "telephone: " + t);
        }
        for (final String e : strings(o.opt("email"))) {
            final String mail = Normalizers.email(e);
            if (Normalizers.roleEmail(mail)) { // no addresses of persons (O7)
                literal(run, m, Vocabulary.EMAIL, mail, p + "/email", "email: " + mail);
            }
        }
        for (final String u : strings(o.opt("url"))) {
            literal(run, m, Vocabulary.WEBSITE, Normalizers.url(absolute(run, u)), p + "/url", "url: " + u);
            if (Vocabulary.SERVICE.equals(m.type) && m.productUrl == null) {
                m.productUrl = Normalizers.productUrl(absolute(run, u), run.domain); // WP-F: its own page, if it is on this site
            }
        }
        for (final String h : strings(o.opt("openingHours"))) {
            literal(run, m, Vocabulary.OPENING_HOURS, Normalizers.clip(h, 200), p + "/openingHours", "openingHours: " + h);
        }
        if (!(Vocabulary.ORGANIZATION.equals(m.type) || Vocabulary.FACILITY.equals(m.type))) {
            return;
        }
        for (final String f : strings(o.opt("faxNumber"))) {
            literal(run, m, Vocabulary.FAX, Normalizers.phone(f, run.callingCode), p + "/faxNumber", "faxNumber: " + f);
        }
        for (final String same : run.profilesOff ? List.<String>of() : strings(o.opt("sameAs"))) {
            final String profile = BusinessFacts.socialProfile(same);
            if (profile != null) {
                literal(run, m, Vocabulary.SOCIAL_PROFILE, profile, p + "/sameAs", "sameAs: " + same);
            }
        }
        for (final String map : strings(o.opt("hasMap"))) {
            final String url = Normalizers.url(absolute(run, map));
            if (url != null) {
                literal(run, m, Vocabulary.DIRECTIONS, url, p + "/hasMap", "hasMap: " + url);
            }
        }
        for (final Object v : values(o.opt("contactPoint"))) {
            if (v instanceof JSONObject) {
                contactPoint(run, m, (JSONObject) v, p + "/contactPoint");
            }
        }
    }

    /** A department or function with its role contacts; a person's name, e-mail or extension is never kept (O7). */
    private void contactPoint(final Run run, final Mention m, final JSONObject cp, final String p) {
        final String function = Normalizers.clip(string(cp.opt("contactType")), 80);
        if (function == null || BusinessFacts.personLike(function)) {
            return;
        }
        final Map<String, Object> f = new java.util.TreeMap<>();
        f.put("function", function);
        final String phone = Normalizers.phone(string(cp.opt("telephone")), run.callingCode);
        f.put("phone", phone);
        final String mail = Normalizers.email(string(cp.opt("email")));
        if (mail != null && Normalizers.roleEmail(mail)) {
            f.put("email", mail);
        }
        f.put("fax", Normalizers.phone(string(cp.opt("faxNumber")), run.callingCode));
        final String hours = string(cp.opt("hoursAvailable"));
        if (hours != null) {
            f.put("hours", Normalizers.clip(hours, 120));
        }
        if (f.size() > 1) {
            literal(run, m, Vocabulary.CONTACT_POINT, Values.canonical(f), p, "contactPoint: " + function + (phone == null ? "" : " " + phone)
                    + (f.get("email") == null ? "" : " " + f.get("email")));
        }
    }

    private void address(final Run run, final Mention m, final Address a, final String p) {
        if (a.display() == null) {
            return;
        }
        if (m.address == null && a.complete()) {
            m.address = a;
        }
        literal(run, m, Vocabulary.ADDRESS, a.display(), p, "address: " + a.display());
        literal(run, m, Vocabulary.POSTAL_CODE, a.postalCode, p + "/postalCode", "postalCode: " + a.postalCode);
        literal(run, m, Vocabulary.LOCALITY, a.locality, p + "/addressLocality", "addressLocality: " + a.locality);
    }

    private void relations(final Run run, final Mention m, final JSONObject o, final String p, final int depth) {
        for (final Object v : values(o.opt("parentOrganization"))) {
            final String parent = v instanceof JSONObject ? node(run, (JSONObject) v, p + "/parentOrganization", depth + 1, null) : null;
            final Mention pm = parent == null ? null : run.out.mention(parent);
            if (pm != null && Vocabulary.ORGANIZATION.equals(pm.type)) {
                // a facility is operated by its parent organisation; an organisation is its subsidiary (vocabulary 2)
                relation(run, Vocabulary.FACILITY.equals(m.type) ? parent : m.ref,
                        Vocabulary.FACILITY.equals(m.type) ? Vocabulary.OPERATES : Vocabulary.SUBSIDIARY_OF,
                        Vocabulary.FACILITY.equals(m.type) ? m.ref : parent, p + "/parentOrganization");
            }
        }
        for (final String key : new String[] {"subOrganization", "department"}) {
            for (final Object v : values(o.opt(key))) {
                final String child = v instanceof JSONObject ? node(run, (JSONObject) v, p + "/" + key, depth + 1, null) : null;
                final Mention cm = child == null ? null : run.out.mention(child);
                if (cm == null || !Vocabulary.ORGANIZATION.equals(m.type)) {
                    continue;
                }
                if (Vocabulary.FACILITY.equals(cm.type)) {
                    relation(run, m.ref, Vocabulary.OPERATES, child, p + "/" + key);
                } else if (Vocabulary.ORGANIZATION.equals(cm.type)) {
                    if ("department".equals(key)) {
                        relation(run, child, Vocabulary.PART_OF, m.ref, p + "/" + key);
                    } else {
                        relation(run, m.ref, Vocabulary.PARENT_OF, child, p + "/" + key);
                    }
                }
            }
        }
        if (Vocabulary.ORGANIZATION.equals(m.type) || Vocabulary.FACILITY.equals(m.type)) {
            organisations(run, m, o.opt("memberOf"), p + "/memberOf", depth, Vocabulary.MEMBER_OF, false);
            organisations(run, m, o.opt("sponsor"), p + "/sponsor", depth, Vocabulary.SPONSORED_BY, false);
            organisations(run, m, o.opt("funder"), p + "/funder", depth, Vocabulary.FUNDED_BY, false);
            // "brand": the brand is a brand of this organisation
            organisations(run, m, o.opt("brand"), p + "/brand", depth, Vocabulary.BRAND_OF, true);
            for (final Object v : values(o.opt("hasCredential"))) {
                if (v instanceof JSONObject) {
                    final JSONObject cred = (JSONObject) v;
                    final String credName = Normalizers.clip(string(cred.opt("name")), 200);
                    final int before = run.out.claims().size();
                    organisations(run, m, cred.opt("recognizedBy"), p + "/hasCredential/recognizedBy", depth, Vocabulary.CERTIFIED_BY,
                            false);
                    if (credName != null && run.out.claims().size() == before) {
                        literal(run, m, Vocabulary.CERTIFICATION, credName, p + "/hasCredential", "hasCredential: " + credName);
                    }
                } else if (v instanceof String) {
                    literal(run, m, Vocabulary.CERTIFICATION, Normalizers.clip(Normalizers.text((String) v), 200), p + "/hasCredential",
                            "hasCredential: " + v);
                }
            }
        }
        for (final Object v : values(o.opt("location"))) {
            if (v instanceof JSONObject) {
                final JSONObject l = (JSONObject) v;
                final String site = node(run, l, p + "/location", depth + 1, l.has("address") || l.has("geo") ? Vocabulary.SITE : null);
                final Mention sm = site == null ? null : run.out.mention(site);
                if (sm != null && Vocabulary.SITE.equals(sm.type)) {
                    relation(run, m.ref, Vocabulary.LOCATED_AT, site, p + "/location");
                }
            }
        }
        final List<Object> offers = values(o.opt("makesOffer"));
        for (int i = 0; i < offers.size(); i++) {
            if (offers.get(i) instanceof JSONObject) {
                // each offer has its own path: several offers are several services (version 2 fix: they collapsed into one)
                offer(run, m, (JSONObject) offers.get(i), p + "/makesOffer" + (offers.size() == 1 ? "" : "/" + i), depth + 1);
            }
        }
        final Object catalog = o.opt("hasOfferCatalog");
        if (catalog instanceof JSONObject) {
            int i = 0;
            for (final Object v : values(((JSONObject) catalog).opt("itemListElement"))) {
                if (v instanceof JSONObject && i++ < 50) {
                    offer(run, m, (JSONObject) v, p + "/hasOfferCatalog/itemListElement/" + (i - 1), depth + 2);
                }
            }
        }
        if (Vocabulary.SERVICE.equals(m.type)) {
            for (final Object v : values(o.opt("provider"))) {
                final String provider = v instanceof JSONObject ? node(run, (JSONObject) v, p + "/provider", depth + 1, null) : null;
                final Mention pm = provider == null ? null : run.out.mention(provider);
                if (pm != null && (Vocabulary.ORGANIZATION.equals(pm.type) || Vocabulary.FACILITY.equals(pm.type))) {
                    relation(run, provider, Vocabulary.OFFERS, m.ref, p + "/provider");
                    if (m.serviceKey == null && m.name != null && Mention.providerKey(pm) != null) {
                        // WP-F: the service of its declared provider, as an offer of the provider is (identity change, switched)
                        m.serviceKey = serviceKey(run.ctx, Mention.providerKey(pm), m.name);
                        m.providerServiceKey = true;
                    }
                }
            }
        }
    }

    private void offer(final Run run, final Mention m, final JSONObject offer, final String p, final int depth) {
        if (!(Vocabulary.ORGANIZATION.equals(m.type) || Vocabulary.FACILITY.equals(m.type))) {
            return;
        }
        final Object item = offer.opt("itemOffered");
        final JSONObject service = item instanceof JSONObject ? (JSONObject) item : offer;
        final String ref = node(run, service, item instanceof JSONObject ? p + "/itemOffered" : p, depth, Vocabulary.SERVICE);
        final Mention sm = ref == null ? null : run.out.mention(ref);
        if (sm != null && Vocabulary.SERVICE.equals(sm.type) && sm.name != null) {
            relation(run, m.ref, Vocabulary.OFFERS, ref, p);
            final String provider = Mention.providerKey(m);
            if ((sm.serviceKey == null || sm.providerServiceKey) && provider != null) {
                // the same service of the same provider on another page of the site is the same service
                sm.serviceKey = serviceKey(run.ctx, provider, sm.name);
                sm.providerServiceKey = false;
            }
            if (item instanceof JSONObject) {
                prices(run, sm, offer, p); // the offer's price is the service's price
            }
            audience(run, sm, offer, p);
        }
    }

    /**
     * The {@code service_name} key of a service of {@code provider} ({@link Mention#providerKey}): its category if its name is
     * the category's name, else its name.
     */
    public static String serviceKey(final ExtractContext ctx, final String provider, final String name) {
        final java.util.List<net.yacy.scoutro.knowledge.vocab.TermMatcher.Hit<net.yacy.scoutro.knowledge.vocab.Categories.Entry>> cat
                = BusinessFacts.categories(ctx == null ? ExtractContext.none() : ctx, name, 0, name.length());
        return provider + "|" + (cat.size() == 1 && Normalizers.key(name).equals(Normalizers.key(cat.get(0).entry.de))
                ? cat.get(0).entry.id : "label:" + Normalizers.key(name));
    }

    // ------------------------------------------------------------ vocabulary 2

    /** Business facts of a node: industry of its type, categories and prices of a service, a job's fields, the audience. */
    private void business(final Run run, final Mention m, final JSONObject o, final String p, final String schemaType, final int depth) {
        if(Vocabulary.ORGANIZATION.equals(m.type)||Vocabulary.FACILITY.equals(m.type))
            BusinessSignals.extract(string(o.opt("description")),m,run.out,1,Claim.KIND_JSONLD,"company",p+"/description");
        if (Vocabulary.ORGANIZATION.equals(m.type) || Vocabulary.FACILITY.equals(m.type)) {
            final String nace = BusinessFacts.industryOfType(m.subkind != null ? m.subkind : bare(schemaType));
            if (nace != null && run.ctx.nace.valid(nace)) {
                run.out.add(new Claim(m.ref, Vocabulary.INDUSTRY, null, nace, 1, Claim.KIND_JSONLD, false, p + "/@type",
                        Normalizers.clip("@type: " + schemaType + " -> NACE " + nace, this.maxExcerpt), 0.8));
            }
            final String range = string(o.opt("priceRange"));
            if (range != null) {
                // a business's general price range: kept only if it states numbers and a currency ("€€" is no price)
                for (final Values.Price pr : Values.prices(range, 0, range.length())) {
                    literal(run, m, Vocabulary.PRICE, withScope(pr, "general"), p + "/priceRange", "priceRange: " + range);
                }
            }
            audience(run, m, o, p);
        }
        if (Vocabulary.SERVICE.equals(m.type)) {
            final StringBuilder named = new StringBuilder();
            if (m.name != null) {
                named.append(m.name);
            }
            for (final String t : strings(o.opt("serviceType"))) {
                named.append(named.length() == 0 ? "" : " | ").append(t);
            }
            for (final String t : strings(o.opt("category"))) {
                named.append(named.length() == 0 ? "" : " | ").append(t);
            }
            if (named.length() > 0) {
                BusinessFacts.categorize(run.out, run.ctx, m, Normalizers.clip(named.toString(), 300), 1, Claim.KIND_JSONLD,
                        p + "/serviceType", "name/serviceType: ", this.maxExcerpt);
            }
            final String description = Normalizers.clip(string(o.opt("description")), 300);
            if (description != null) {
                literal(run, m, Vocabulary.DESCRIPTION, Normalizers.redactPersons(description), p + "/description",
                        "description: " + description);
            }
            if (o.has("price") || o.has("priceSpecification")) {
                prices(run, m, o, p); // a node that is an Offer itself
            }
            final List<Object> productOffers = values(o.opt("offers"));
            for (int i = 0; i < productOffers.size(); i++) {
                if (productOffers.get(i) instanceof JSONObject) {
                    final JSONObject offer = (JSONObject) productOffers.get(i);
                    final String at = p + "/offers" + (productOffers.size() == 1 ? "" : "/" + i);
                    prices(run, m, offer, at);
                    sellers(run, m, offer, at, depth); // WP-F: the seller offers it
                }
            }
            sellers(run, m, o, p, depth);
            // WP-F: the maker and the brand of a product are never its providers
            organisations(run, m, o.opt("manufacturer"), p + "/manufacturer", depth, Vocabulary.MANUFACTURER_OF, true);
            organisations(run, m, o.opt("brand"), p + "/brand", depth, Vocabulary.HAS_BRAND, false);
            audience(run, m, o, p);
        }
        if (Vocabulary.JOB.equals(m.type)) {
            job(run, m, o, p, depth);
        }
    }

    /** {@code seller} and {@code offeredBy} of an offer (or a node that is an offer): organisations and facilities that offer it. */
    private void sellers(final Run run, final Mention product, final JSONObject offer, final String p, final int depth) {
        for (final String key : new String[] {"seller", "offeredBy"}) {
            final List<Object> items = values(offer.opt(key));
            for (int i = 0; i < items.size(); i++) {
                if (!(items.get(i) instanceof JSONObject)) {
                    continue;
                }
                final String at = p + "/" + key + (items.size() == 1 ? "" : "/" + i);
                final String ref = node(run, (JSONObject) items.get(i), at, depth + 1, Vocabulary.ORGANIZATION);
                final Mention sm = ref == null ? null : run.out.mention(ref);
                if (sm != null && sm.name != null && (Vocabulary.ORGANIZATION.equals(sm.type) || Vocabulary.FACILITY.equals(sm.type))) {
                    relation(run, ref, Vocabulary.OFFERS, product.ref, at);
                    run.sold.add(product.ref);
                }
            }
        }
    }

    /**
     * Own-shop offers (WP-F): a product or service of the page with a price that no organisation offers is offered by the
     * site's declared operator, if the page declares exactly one, with a lower confidence and that reason as the excerpt.
     * A manufacturer or a brand never becomes a provider this way (a stated manufacturer does not stop the shop's offer).
     */
    private void ownOffers(final Run run) {
        Mention operator = null;
        for (final Mention m : run.out.mentions()) {
            if (m.siteOperator && m.name != null && (Vocabulary.ORGANIZATION.equals(m.type) || Vocabulary.FACILITY.equals(m.type))) {
                if (operator != null && !operator.ref.equals(m.ref)) {
                    return; // two operators: whose offer is it?
                }
                operator = m;
            }
        }
        if (operator == null) {
            return;
        }
        final java.util.Set<String> offered = new java.util.HashSet<>(), priced = new java.util.LinkedHashSet<>();
        for (final Claim c : run.out.claims()) {
            if (c.tier != 1) {
                continue;
            }
            if (Vocabulary.OFFERS.equals(c.predicate) && c.object != null) {
                offered.add(c.object);
            } else if (Vocabulary.PRICE.equals(c.predicate)) {
                priced.add(c.subject);
            }
        }
        for (final String ref : priced) {
            final Mention sm = run.out.mention(ref);
            if (offered.contains(ref) || run.sold.contains(ref) || sm == null || !Vocabulary.SERVICE.equals(sm.type) || sm.name == null) {
                continue;
            }
            run.out.add(new Claim(operator.ref, Vocabulary.OFFERS, ref, null, 1, Claim.KIND_JSONLD, false, "jsonld:own_offer",
                    Normalizers.clip("own offer: " + sm.name + " (priced on the operator's page, no seller stated)", this.maxExcerpt), 0.6));
        }
    }

    private static String bare(final String schemaType) {
        if (schemaType == null) {
            return null;
        }
        final int slash = Math.max(schemaType.lastIndexOf('/'), schemaType.lastIndexOf(':'));
        return (slash >= 0 ? schemaType.substring(slash + 1) : schemaType).toLowerCase(java.util.Locale.ROOT);
    }

    private static String withScope(final Values.Price pr, final String scope) {
        final Map<String, Object> f = new java.util.TreeMap<>(pr.fields);
        f.put("scope", scope);
        return Values.canonical(f);
    }

    /**
     * Prices of an {@code Offer} (or a node with {@code price}): {@code price}
     * or {@code priceSpecification} ({@code price}, {@code minPrice},
     * {@code maxPrice}, {@code unitCode}/{@code unitText},
     * {@code referenceQuantity}, {@code valueAddedTaxIncluded},
     * {@code validFrom}/{@code validThrough}); always with a currency.
     */
    private void prices(final Run run, final Mention service, final JSONObject offer, final String p) {
        final String offerCurrency = Values.currency(string(offer.opt("priceCurrency")));
        final String validThrough = Values.date(string(offer.opt("priceValidUntil")) != null ? string(offer.opt("priceValidUntil"))
                : string(offer.opt("validThrough")));
        final String validFrom = Values.date(string(offer.opt("validFrom")));
        final String eligible = customerTypes(offer.opt("eligibleCustomerType"));
        // WP-F: what the amount is, from the names and descriptions around it (a benefit, a donation, a subsidy is no price)
        final String context = String.join(" ", java.util.Arrays.asList(service.name, string(offer.opt("name")),
                string(offer.opt("description")), string(offer.opt("category"))).stream().filter(java.util.Objects::nonNull)
                .toArray(String[]::new));
        final List<JSONObject> specs = new ArrayList<>();
        for (final Object v : values(offer.opt("priceSpecification"))) {
            if (v instanceof JSONObject) {
                specs.add((JSONObject) v);
            }
        }
        if (specs.isEmpty() && (offer.has("price") || offer.has("lowPrice") || offer.has("highPrice"))) {
            specs.add(offer);
        }
        for (final JSONObject spec : specs) {
            final String currency = Values.currency(string(spec.opt("priceCurrency"))) != null ? Values.currency(string(spec.opt("priceCurrency")))
                    : offerCurrency;
            if (currency == null) {
                continue; // a number without a currency is no price
            }
            final String price = Values.ldAmount(ldValue(spec.opt("price")));
            final String min = Values.ldAmount(ldValue(spec.opt(spec.has("minPrice") ? "minPrice" : "lowPrice")));
            final String max = Values.ldAmount(ldValue(spec.opt(spec.has("maxPrice") ? "maxPrice" : "highPrice")));
            final Map<String, Object> f = new java.util.TreeMap<>();
            f.put("currency", currency);
            if (min != null && max != null && new java.math.BigDecimal(min).compareTo(new java.math.BigDecimal(max)) < 0) {
                f.put("kind", Values.KIND_RANGE);
                f.put("min", min);
                f.put("max", max);
            } else if (price != null) {
                f.put("kind", Values.KIND_FIXED);
                f.put("amount", price);
            } else if (min != null) {
                f.put("kind", Values.KIND_FROM);
                f.put("amount", min);
            } else if (max != null) {
                f.put("kind", Values.KIND_UP_TO);
                f.put("amount", max);
            } else {
                continue;
            }
            String unit = Values.unitOf(string(spec.opt("unitCode")));
            String unitText = null;
            if (unit == null) {
                unit = Values.unitOf(string(spec.opt("unitText")));
            }
            final Object ref = spec.opt("referenceQuantity");
            if (unit == null && ref instanceof JSONObject) {
                unit = Values.unitOf(string(((JSONObject) ref).opt("unitCode")));
                if (unit == null) {
                    unit = Values.unitOf(string(((JSONObject) ref).opt("unitText")));
                }
            }
            if (unit == null && string(spec.opt("unitText")) != null) {
                unit = Values.UNIT_OTHER;
                unitText = Normalizers.clip(string(spec.opt("unitText")), 60);
            }
            if (unit == null && spec.has("billingDuration")) {
                unit = Values.unitOf(string(spec.opt("billingDuration")));
            }
            f.put("unit", unit);
            f.put("unit_text", unitText);
            final Object vat = spec.opt("valueAddedTaxIncluded");
            if (vat instanceof Boolean) {
                f.put("vat", (Boolean) vat ? "incl" : "excl");
            }
            f.put("valid_from", Values.date(string(spec.opt("validFrom"))) != null ? Values.date(string(spec.opt("validFrom"))) : validFrom);
            f.put("valid_through", Values.date(string(spec.opt("validThrough"))) != null ? Values.date(string(spec.opt("validThrough")))
                    : validThrough);
            final String conditions = string(spec.opt("eligibleQuantity") instanceof JSONObject
                    ? ((JSONObject) spec.opt("eligibleQuantity")).opt("description") : spec.opt("description"));
            if (conditions != null) {
                f.put("conditions", Normalizers.clip(conditions, 140));
            }
            f.put("eligible", eligible);
            final String role = MoneyRoles.role(spec == offer ? context : context + " " + java.util.Objects.toString(string(spec.opt("name")), "")
                    + " " + java.util.Objects.toString(string(spec.opt("description")), ""));
            if (role != null) {
                f.put("role", role);
            }
            literal(run, service, role == null ? Vocabulary.PRICE : Vocabulary.MONETARY_AMOUNT, Values.canonical(f),
                    p + (spec == offer ? "/price" : "/priceSpecification"), priceExcerpt(service, offer, spec, currency));
        }
    }

    /** What the source states for a price: the product or service it is for, the amount, its terms and validity (WP-F). */
    private static String priceExcerpt(final Mention service, final JSONObject offer, final JSONObject spec, final String currency) {
        final StringBuilder sb = new StringBuilder();
        if (service.name != null) {
            sb.append("name: ").append(service.name);
        }
        for (final String k : new String[] {"price", "minPrice", "maxPrice", "lowPrice", "highPrice", "priceCurrency", "unitCode", "unitText",
            "valueAddedTaxIncluded", "validFrom", "validThrough", "priceValidUntil", "description"}) {
            final Object v = spec.opt(k) != null ? spec.opt(k) : spec != offer && !"price".equals(k) && !"priceCurrency".equals(k)
                    && !"description".equals(k) ? offer.opt(k) : null;
            if (v != null && !(v instanceof JSONObject) && !(v instanceof JSONArray)) {
                sb.append(sb.length() == 0 ? "" : ", ").append(k).append(": ").append(Normalizers.clip(String.valueOf(v), 120));
            }
        }
        if (!spec.has("priceCurrency")) {
            sb.append(", priceCurrency: ").append(currency);
        }
        return sb.toString();
    }

    /** schema.org BusinessEntityType values as customer types, joined; null if none. */
    private static String customerTypes(final Object v) {
        final java.util.Set<String> out = new java.util.TreeSet<>();
        for (final String s : strings(v)) {
            final String t = s.replaceFirst("(?i)^https?://(?:www\\.)?(?:schema\\.org|purl\\.org/goodrelations/v1)[#/]", "")
                    .toLowerCase(java.util.Locale.ROOT);
            if (t.equals("business") || t.equals("reseller")) {
                out.add("b2b");
            } else if (t.equals("enduser")) {
                out.add("b2c");
            } else if (t.equals("publicinstitution")) {
                out.add("public_sector");
            }
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    /**
     * The declared audience: {@code audience} (Audience, BusinessAudience,
     * PeopleAudience with {@code audienceType}), {@code areaServed},
     * {@code eligibleCustomerType}, {@code eligibleRegion}; customer types and
     * segments only as the vocabulary names them.
     */
    private void audience(final Run run, final Mention m, final JSONObject o, final String p) {
        for (final Object v : values(o.opt("audience"))) {
            if (!(v instanceof JSONObject)) {
                continue;
            }
            final JSONObject a = (JSONObject) v;
            boolean business = false;
            boolean people = false;
            for (final String t : JsonLdBlocks.types(a)) {
                final String b = bare(t);
                business |= "businessaudience".equals(b);
                people |= "peopleaudience".equals(b) || "medicalaudience".equals(b) || "patient".equals(b);
            }
            final String type = string(a.opt("audienceType"));
            final String excerpt = "audience: " + (business ? "BusinessAudience " : people ? "PeopleAudience " : "") + (type == null ? "" : type);
            if (business) {
                literal(run, m, Vocabulary.CUSTOMER_TYPE, "b2b", p + "/audience", excerpt);
            } else if (people) {
                literal(run, m, Vocabulary.CUSTOMER_TYPE, "b2c", p + "/audience", excerpt);
            }
            if (type != null) {
                for (final net.yacy.scoutro.knowledge.vocab.TermMatcher.Hit<net.yacy.scoutro.knowledge.vocab.Categories.Entry> h
                        : run.ctx.categories.customerTypes(type, 0, type.length())) {
                    literal(run, m, Vocabulary.CUSTOMER_TYPE, h.entry.id, p + "/audience/audienceType", excerpt);
                }
                for (final net.yacy.scoutro.knowledge.vocab.Categories.Vocab voc : run.ctx.vocabularies) {
                    for (final net.yacy.scoutro.knowledge.vocab.TermMatcher.Hit<net.yacy.scoutro.knowledge.vocab.Categories.Entry> h
                            : voc.segments(type, 0, type.length())) {
                        literal(run, m, Vocabulary.AUDIENCE_SEGMENT, h.entry.id, p + "/audience/audienceType", excerpt);
                    }
                }
                for (final net.yacy.scoutro.knowledge.vocab.TermMatcher.Hit<net.yacy.scoutro.knowledge.vocab.Categories.Entry> h
                        : run.ctx.categories.companySizes(type, 0, type.length())) {
                    literal(run, m, Vocabulary.COMPANY_SIZE, h.entry.id, p + "/audience/audienceType", excerpt);
                }
            }
            final Object employees = a.opt("numberOfEmployees");
            if (employees instanceof JSONObject) {
                final String lo = string(((JSONObject) employees).opt("minValue"));
                final String hi = string(((JSONObject) employees).opt("maxValue"));
                if (lo != null || hi != null) {
                    literal(run, m, Vocabulary.COMPANY_SIZE, "employees:" + (lo == null ? "" : lo) + "-" + (hi == null ? "" : hi),
                            p + "/audience/numberOfEmployees", "numberOfEmployees: " + (lo == null ? "" : lo) + "-" + (hi == null ? "" : hi));
                }
            }
            areas(run, m, a.opt("geographicArea"), p + "/audience/geographicArea");
        }
        final String eligible = customerTypes(o.opt("eligibleCustomerType"));
        if (eligible != null) {
            for (final String t : eligible.split(",")) {
                literal(run, m, Vocabulary.CUSTOMER_TYPE, t, p + "/eligibleCustomerType", "eligibleCustomerType: " + o.opt("eligibleCustomerType"));
            }
        }
        areas(run, m, o.opt("areaServed"), p + "/areaServed");
        areas(run, m, o.opt("eligibleRegion"), p + "/eligibleRegion");
    }

    /** {@code areaServed}/{@code eligibleRegion}: a country, state or place name, or {@code GeoCircle} with a radius. */
    private void areas(final Run run, final Mention m, final Object v, final String p) {
        for (final Object a : values(v)) {
            String name = null;
            String radius = null;
            if (a instanceof String) {
                name = Normalizers.text((String) a);
            } else if (a instanceof JSONObject) {
                final JSONObject ao = (JSONObject) a;
                name = string(ao.opt("name"));
                final Object r = ao.opt("geoRadius");
                if (r != null) {
                    final double raw = number(r instanceof JSONObject ? ((JSONObject) r).opt("value") : r);
                    // schema.org geoRadius is in metres when it is a bare number; a QuantitativeValue states its unit
                    final String unit = r instanceof JSONObject ? string(((JSONObject) r).opt("unitCode")) : null;
                    final double km = r instanceof JSONObject && unit != null && unit.equalsIgnoreCase("KMT") ? raw : raw / 1000.0;
                    if (!Double.isNaN(km) && km >= 1 && km < 5000) {
                        radius = Long.toString(Math.round(km));
                    }
                }
                final Object mid = ao.opt("geoMidpoint");
                if (name == null && mid instanceof JSONObject) {
                    name = string(((JSONObject) mid).opt("name"));
                }
            }
            if (name == null && radius == null) {
                continue;
            }
            name = name == null ? null : Normalizers.clip(name, 80);
            final String excerpt = p.substring(p.lastIndexOf('/') + 1) + ": " + (name == null ? "" : name) + (radius == null ? "" : " radius " + radius);
            final Map<String, Object> f = new java.util.TreeMap<>();
            final String[] state = name == null ? null : BusinessFacts.state(name);
            final String country = name == null ? null : countryCode(name);
            Mention place = null;
            if (radius != null) {
                f.put("kind", "radius");
                f.put("radius_km", radius);
                f.put("around", name);
            } else if (country != null) {
                f.put("kind", "national");
                f.put("name", BusinessFacts.countryName(country));
                place = BusinessFacts.place(run.out, country, "country", BusinessFacts.countryName(country), 1, Claim.KIND_JSONLD, p, excerpt);
            } else if (state != null) {
                f.put("kind", "state");
                f.put("name", state[1]);
                place = BusinessFacts.place(run.out, state[0], "state", state[1], 1, Claim.KIND_JSONLD, p, excerpt);
            } else {
                f.put("kind", "place");
                f.put("name", name);
                if (run.country != null) {
                    place = BusinessFacts.place(run.out, run.country, "locality", name, 1, Claim.KIND_JSONLD, p, excerpt);
                }
            }
            literal(run, m, Vocabulary.SERVICE_AREA, Values.canonical(f), p, excerpt);
            if (place != null) {
                relation(run, m.ref, Vocabulary.SERVES_PLACE, place.ref, p);
            }
        }
    }

    private static String countryCode(final String name) {
        final String k = Normalizers.key(name);
        if (k == null) {
            return null;
        }
        switch (k) {
            case "de":
            case "deu":
            case "deutschland":
            case "germany":
                return "de";
            case "at":
            case "aut":
            case "osterreich":
            case "österreich":
            case "austria":
                return "at";
            case "ch":
            case "che":
            case "schweiz":
            case "switzerland":
                return "ch";
            default:
                return null;
        }
    }

    /** Organisations of a relation property: {@code subject -predicate-> organisation}, or reversed. */
    private void organisations(final Run run, final Mention m, final Object v, final String p, final int depth, final String predicate,
            final boolean reversed) {
        final List<Object> items = values(v);
        for (int i = 0; i < items.size(); i++) {
            final Object x = items.get(i);
            final String at = items.size() == 1 ? p : p + "/" + i;
            JSONObject node = null;
            if (x instanceof JSONObject) {
                node = (JSONObject) x;
                final JSONObject host = node.optJSONObject("hostingOrganization"); // a ProgramMembership
                if (host != null && JsonLdBlocks.types(node).stream().anyMatch(t -> "programmembership".equals(bare(t)))) {
                    node = host;
                }
            } else if (x instanceof String && !((String) x).startsWith("http")) {
                try {
                    node = new JSONObject().put("@type", "Organization").put("name", x);
                } catch (final org.json.JSONException e) {
                    node = null;
                }
            }
            if (node == null) {
                continue;
            }
            final boolean brand = Vocabulary.BRAND_OF.equals(predicate) || Vocabulary.HAS_BRAND.equals(predicate)
                    || Vocabulary.MANUFACTURER_OF.equals(predicate);
            final String ref;
            if (brand) {
                run.brandDepth++;
            }
            try {
                ref = node(run, node, at, depth + 1, Vocabulary.ORGANIZATION);
            } finally {
                if (brand) {
                    run.brandDepth--;
                }
            }
            final Mention om = ref == null ? null : run.out.mention(ref);
            if (om == null || om.name == null || BusinessFacts.personLike(om.name) || !Vocabulary.ORGANIZATION.equals(om.type)) {
                continue;
            }
            String pred = predicate;
            if (Vocabulary.BRAND_OF.equals(predicate) && !ownBrand(run, m, om, node)) {
                // 0.11.5: "brand" of an organisation that names another maker's brand (a dealer: "SCHIMMEL" of a piano shop) is
                // no brand of the organisation; the brand supplies it. An own brand shares the name or the domain.
                pred = Vocabulary.SUPPLIER_OF;
            }
            if (reversed) {
                relation(run, ref, pred, m.ref, at);
            } else {
                relation(run, m.ref, pred, ref, at);
            }
        }
    }

    /**
     * A {@code JobPosting}: employer, place, employment types, the published
     * salary with its unit, dates and the application route; never a
     * recruiter's name or a personal e-mail address.
     */
    private void job(final Run run, final Mention m, final JSONObject o, final String p, final int depth) {
        final String description=string(o.opt("description"));
        final boolean intermediary=description!=null && BusinessSignals.INTERMEDIARY.matcher(description).find();
        String employer = null;
        for (final Object v : values(o.opt("hiringOrganization"))) {
            final String ref = v instanceof JSONObject ? node(run, (JSONObject) v, p + "/hiringOrganization", depth + 1, Vocabulary.ORGANIZATION)
                    : null;
            final Mention om = ref == null ? null : run.out.mention(ref);
            if (om != null && (Vocabulary.ORGANIZATION.equals(om.type) || Vocabulary.FACILITY.equals(om.type))) {
                relation(run, m.ref, intermediary ? Vocabulary.RECRUITING_ORGANIZATION : Vocabulary.HIRING_ORGANIZATION, ref, p + "/hiringOrganization");
                employer = om.name;
            }
        }
        String location = null;
        for (final Object v : values(o.opt("jobLocation"))) {
            if (v instanceof JSONObject) {
                final JSONObject l = (JSONObject) v;
                final String ref = node(run, l, p + "/jobLocation", depth + 1, Vocabulary.SITE);
                final Mention lm = ref == null ? null : run.out.mention(ref);
                if (lm != null && (Vocabulary.SITE.equals(lm.type) || Vocabulary.FACILITY.equals(lm.type))) {
                    relation(run, m.ref, Vocabulary.JOB_LOCATION, ref, p + "/jobLocation");
                    location = lm.address != null ? lm.address.locality : lm.name;
                    if (location == null) {
                        final Object addr = l.opt("address");
                        location = addr instanceof JSONObject ? string(((JSONObject) addr).opt("addressLocality")) : null;
                    }
                }
            }
        }
        m.postingUrl = Normalizers.url(absolute(run, string(o.opt("url"))));
        m.jobPlace = location == null ? null : Normalizers.key(location);
        if (o.optString("jobLocationType", "").toUpperCase(java.util.Locale.ROOT).contains("TELECOMMUTE")) {
            literal(run, m, Vocabulary.EMPLOYMENT_TYPE, "remote", p + "/jobLocationType", "jobLocationType: TELECOMMUTE");
        }
        m.jobKey = (employer == null||intermediary ? "unresolved:"+java.util.HexFormat.of().formatHex(net.yacy.scoutro.knowledge.KgIds.objectKey(run.pageUrl+"|"+p)) : Normalizers.key(employer)) + "|" + (m.name == null ? "" : Normalizers.key(m.name)) + "|"
                + (location == null ? "" : Normalizers.key(location));
        final String postingId=string(o.opt("@id"));
        final String posting=postingId==null?run.pageUrl+"|"+p:absoluteId(run,postingId);
        m.jobKey+="|posting:"+java.util.HexFormat.of().formatHex(net.yacy.scoutro.knowledge.KgIds.objectKey(posting));
        for (final String t : strings(o.opt("employmentType"))) {
            final String et = BusinessFacts.employmentType(t);
            if (et != null) {
                literal(run, m, Vocabulary.EMPLOYMENT_TYPE, et, p + "/employmentType", "employmentType: " + t);
            }
        }
        final String field = Normalizers.clip(string(o.opt("occupationalCategory")), 120);
        if (field != null) {
            literal(run, m, Vocabulary.OCCUPATIONAL_FIELD, field, p + "/occupationalCategory", "occupationalCategory: " + field);
        }
        final String industry = string(o.opt("industry"));
        if (industry != null) {
            for (final net.yacy.scoutro.knowledge.vocab.TermMatcher.Hit<net.yacy.scoutro.knowledge.vocab.Categories.Entry> h
                    : BusinessFacts.categories(run.ctx, industry, 0, industry.length())) {
                if (h.entry.nace != null) {
                    run.out.add(new Claim(m.ref, Vocabulary.INDUSTRY, null, h.entry.nace, 1, Claim.KIND_JSONLD, false, p + "/industry",
                            Normalizers.clip("industry: " + industry, this.maxExcerpt), 0.7));
                }
            }
        }
        for (final String[] d : new String[][] {{"datePosted", Vocabulary.DATE_POSTED}, {"validThrough", Vocabulary.VALID_THROUGH},
            {"jobStartDate", Vocabulary.START_DATE}}) {
            final String raw = string(o.opt(d[0]));
            final String date = Values.date(raw);
            if (date != null) {
                literal(run, m, d[1], date, p + "/" + d[0], d[0] + ": " + raw);
            }
        }
        salary(run, m, o.opt("baseSalary"), p + "/baseSalary");
        final Object contact = o.opt("applicationContact");
        if (contact instanceof JSONObject) {
            final String mail = Normalizers.email(string(((JSONObject) contact).opt("email")));
            if (mail != null && Normalizers.roleEmail(mail)) {
                literal(run, m, Vocabulary.APPLICATION_ROUTE, "mailto:" + mail, p + "/applicationContact", "applicationContact email: " + mail);
            }
            final String url = Normalizers.url(absolute(run, string(((JSONObject) contact).opt("url"))));
            if (url != null) {
                literal(run, m, Vocabulary.APPLICATION_ROUTE, url, p + "/applicationContact", "applicationContact url: " + url);
            }
        }
        if (o.opt("directApply") instanceof Boolean && (Boolean) o.opt("directApply") && run.pageUrl != null) {
            literal(run, m, Vocabulary.APPLICATION_ROUTE, Normalizers.url(run.pageUrl), p + "/directApply", "directApply: true");
        }
        if (run.ctx.jobSignals) {
            for(String signalField:List.of("description","skills","qualifications")) {
                for(String text:strings(o.opt(signalField)))
                    BusinessSignals.extract(text,m,run.out,1,Claim.KIND_JSONLD,signalField,p+"/"+signalField);
            }
        }
        for(String[] role:new String[][]{{"publisher",Vocabulary.ADVERTISED_BY},{"provider",Vocabulary.RECRUITING_ORGANIZATION},
                {"employmentUnit",Vocabulary.DEPLOYMENT_ORGANIZATION}}) {
            for(Object value:values(o.opt(role[0]))) if(value instanceof JSONObject) {
                String ref=node(run,(JSONObject)value,p+"/"+role[0],depth+1,Vocabulary.ORGANIZATION);
                if(ref!=null)relation(run,m.ref,role[1],ref,p+"/"+role[0]);
            }
        }
    }

    /**
     * The software and system hints of a {@code JobPosting} without a job object (0.10.2): attributed only to the one
     * declared {@code hiringOrganization} with a name, never to a recruiter, a publisher or the client an intermediary
     * advertises for. The section ({@code job_ad_description}, {@code job_ad_skills}, {@code job_ad_qualifications})
     * keeps the advertisement as their source: a required skill stays a requirement, never the employer's own use.
     */
    private void jobHints(final Run run, final JSONObject o, final String p, final int depth) {
        final String description = string(o.opt("description"));
        if (description != null && BusinessSignals.INTERMEDIARY.matcher(description).find()) {
            return; // whose systems these are stays unclear
        }
        final List<Object> hiring = values(o.opt("hiringOrganization"));
        if (hiring.size() != 1 || !(hiring.get(0) instanceof JSONObject)) {
            return;
        }
        final String ref = node(run, (JSONObject) hiring.get(0), p + "/hiringOrganization", depth + 1, Vocabulary.ORGANIZATION);
        final Mention employer = ref == null ? null : run.out.mention(ref);
        if (employer == null || employer.name == null
                || !(Vocabulary.ORGANIZATION.equals(employer.type) || Vocabulary.FACILITY.equals(employer.type))) {
            return;
        }
        for (final String field : List.of("description", "skills", "qualifications")) {
            for (final String text : strings(o.opt(field))) {
                BusinessSignals.extract(text, employer, run.out, 1, Claim.KIND_JSONLD, BusinessSignals.JOB_AD + field, p + "/" + field);
            }
        }
    }

    /** {@code baseSalary}/{@code estimatedSalary} is never read: only a published MonetaryAmount with a currency. */
    private void salary(final Run run, final Mention m, final Object v, final String p) {
        if (!(v instanceof JSONObject)) {
            return;
        }
        final JSONObject s = (JSONObject) v;
        final String currency = Values.currency(string(s.opt("currency")));
        if (currency == null) {
            return;
        }
        final Object value = s.opt("value");
        final JSONObject q = value instanceof JSONObject ? (JSONObject) value : s;
        final String amount = Values.ldAmount(ldValue(q.opt("value") != null ? q.opt("value") : value instanceof JSONObject ? null : value));
        final String min = Values.ldAmount(ldValue(q.opt("minValue")));
        final String max = Values.ldAmount(ldValue(q.opt("maxValue")));
        final Map<String, Object> f = new java.util.TreeMap<>();
        f.put("currency", currency);
        if (min != null && max != null && new java.math.BigDecimal(min).compareTo(new java.math.BigDecimal(max)) < 0) {
            f.put("kind", Values.KIND_RANGE);
            f.put("min", min);
            f.put("max", max);
        } else if (amount != null) {
            f.put("kind", Values.KIND_FIXED);
            f.put("amount", amount);
        } else if (min != null) {
            f.put("kind", Values.KIND_FROM);
            f.put("amount", min);
        } else if (max != null) {
            f.put("kind", Values.KIND_UP_TO);
            f.put("amount", max);
        } else {
            return;
        }
        final String unit = Values.unitOf(string(q.opt("unitText")));
        if (unit == null) {
            return; // a salary without its unit is not shown (no guessing per month or per year)
        }
        f.put("unit", unit);
        literal(run, m, Vocabulary.SALARY, Values.canonical(f), p, "baseSalary: " + currency + " " + (amount != null ? amount
                : (min == null ? "" : min) + "-" + (max == null ? "" : max)) + " " + string(q.opt("unitText")));
    }

    private void literal(final Run run, final Mention m, final String predicate, final String value, final String locator,
            final String excerpt) {
        if (value == null) {
            return;
        }
        run.out.add(new Claim(m.ref, predicate, null, value, 1, Claim.KIND_JSONLD, false, locator,
                Normalizers.clip(excerpt, this.maxExcerpt)));
    }

    private void relation(final Run run, final String subject, final String predicate, final String object, final String locator) {
        if (subject == null || object == null || subject.equals(object)) {
            return;
        }
        run.out.add(new Claim(subject, predicate, object, null, 1, Claim.KIND_JSONLD, false, locator, null));
    }

    // ------------------------------------------------------------ helpers

    private static String absoluteId(final Run run, final String id) {
        if (id == null || id.isEmpty() || id.startsWith("_:")) {
            return null;
        }
        final String abs = absolute(run, id);
        return abs != null && abs.length() <= 512 ? abs : null;
    }

    private static String absolute(final Run run, final String ref) {
        if (ref == null) {
            return null;
        }
        try {
            final URI u = run.pageUrl == null ? new URI(ref.trim()) : new URI(run.pageUrl).resolve(ref.trim());
            return u.isAbsolute() ? u.toString() : null;
        } catch (final Exception e) {
            return null;
        }
    }

    private static List<Object> values(final Object v) {
        final List<Object> out = new ArrayList<>();
        if (v instanceof JSONArray) {
            final JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length() && i < 50; i++) {
                out.add(a.opt(i));
            }
        } else if (v != null && v != JSONObject.NULL) {
            out.add(v);
        }
        return out;
    }

    private static List<String> strings(final Object v) {
        final List<String> out = new ArrayList<>();
        for (final Object x : values(v)) {
            final String s = string(x);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    private static String string(final Object v) {
        if (v instanceof String) {
            return Normalizers.text((String) v);
        }
        if (v instanceof Number) {
            return v.toString();
        }
        if (v instanceof JSONObject) {
            final JSONObject o = (JSONObject) v;
            final Object val = o.opt("@value");
            return val instanceof String ? Normalizers.text((String) val) : null;
        }
        return null;
    }

    /** A number as it is, a string or an {@code @value} string as text: the input of {@link Values#ldAmount}. */
    private static Object ldValue(final Object v) {
        return v instanceof Number ? v : string(v);
    }

    private static String country(final Object v) {
        if (v instanceof JSONObject) {
            return string(((JSONObject) v).opt("name"));
        }
        return string(v);
    }

    private static double number(final Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim().replace(',', '.'));
            } catch (final NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }
}
