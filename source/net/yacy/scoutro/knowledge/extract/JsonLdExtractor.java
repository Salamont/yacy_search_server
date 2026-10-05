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
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.resolve.Normalizers;

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
 * {@code url} on the page's own registrable domain, and its name carries a
 * legal form.
 */
public final class JsonLdExtractor {

    public static final String NAME = "jsonld";
    public static final String VERSION = "2";

    private final int maxExcerpt;

    public JsonLdExtractor(final int maxExcerpt) {
        this.maxExcerpt = maxExcerpt;
    }

    /** Context of one extraction run. */
    private final class Run {
        final String pageUrl;
        final String domain;
        final String callingCode;
        final Extraction out;
        final Map<String, JSONObject> byId = new HashMap<>();
        final Map<String, String> refById = new HashMap<>();

        Run(final String pageUrl, final String host, final String language, final Extraction out) {
            this.pageUrl = pageUrl;
            this.domain = Normalizers.registrableDomain(host);
            this.callingCode = Normalizers.countryCallingCode(host, language);
            this.out = out;
        }
    }

    public void extract(final List<String> blocks, final String pageUrl, final String host, final String language,
            final Extraction out) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        final Run run = new Run(pageUrl, host, language, out);
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
        out.ranTier(1);
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
            if (id != null && o.length() > 1 && !run.byId.containsKey(id)) {
                run.byId.put(id, o);
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
        if (id != null) {
            final String known = run.refById.get(id);
            if (known != null) {
                return known;
            }
            final JSONObject full = run.byId.get(id);
            if (full != null && full != o && o.length() <= 2) {
                o = full; // a bare {"@id": ...} reference to a node described elsewhere on the page
            }
        }
        String type = null;
        String schemaType = null;
        boolean page = false;
        for (final String t : JsonLdBlocks.types(o)) {
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
        if (type == null) {
            type = forcedType;
        }
        if (type == null) {
            return null;
        }
        final Mention existing = run.out.mention(p);
        if (existing != null) {
            return existing.ref;
        }
        final Mention m = run.out.add(new Mention(p, type, 1));
        if (id != null) {
            run.refById.put(id, m.ref);
            m.ldId = id;
        }
        m.subkind = Vocabulary.FACILITY.equals(type) ? Vocabulary.facilityKind(schemaType) : null;

        final String name = Normalizers.clip(string(o.opt("name")), 300);
        final String legal = Normalizers.legalName(string(o.opt("legalName")) != null ? string(o.opt("legalName")) : name);
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

        identifiers(run, m, o, p);
        contact(run, m, o, p);

        // the organisation that describes itself on its own domain, or a declared publisher, is the site operator
        if (Vocabulary.ORGANIZATION.equals(type) && legal != null) {
            final String url = Normalizers.url(absolute(run, string(o.opt("url"))));
            if (url != null && run.domain != null && run.domain.equals(Normalizers.registrableDomain(URI.create(url).getHost()))) {
                m.siteOperator = true;
            }
        }

        relations(run, m, o, p, depth);
        return m.ref;
    }

    /** Graph types win over each other in this order when a node has several types. */
    private static int rank(final String type) {
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
        }
        for (final String h : strings(o.opt("openingHours"))) {
            literal(run, m, Vocabulary.OPENING_HOURS, Normalizers.clip(h, 200), p + "/openingHours", "openingHours: " + h);
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
                relation(run, Vocabulary.FACILITY.equals(m.type) ? parent : m.ref,
                        Vocabulary.FACILITY.equals(m.type) ? Vocabulary.OPERATES : Vocabulary.PART_OF,
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
                    relation(run, child, Vocabulary.PART_OF, m.ref, p + "/" + key);
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
        for (final Object v : values(o.opt("makesOffer"))) {
            if (v instanceof JSONObject) {
                offer(run, m, (JSONObject) v, p + "/makesOffer", depth + 1);
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
        }
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
