/*
 *  OrganisationIds
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

package net.yacy.scoutro.knowledge.resolve;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.yacy.scoutro.knowledge.extract.Vocabulary;

/**
 * 0.13.0 (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 41): an organisation by the {@code @id} of its structured data on its own registrable
 * domain. General rules, no rule of a domain; shared by {@link IdentityResolver} and the read-only gate that lists the merges an
 * update would make.
 * <ul>
 * <li>An {@code @id} whose path, without language segments, is the root of the page's own registrable domain ({@link #root})
 * names the site's own organisation: without a {@code url} of its own it is the domain's operator, as an organisation whose
 * {@code url} is the site.</li>
 * <li>Two {@code @id}s of one host that differ only by language segments of the path or by the fragment
 * ({@code https://www.muster.de/#organization}, {@code https://www.muster.de/en/#organization/}) name one organisation
 * ({@link #canonical}). They are no discriminator against each other only if the names agree and no legal name, legal form or
 * other discriminator differs ({@link #apart}); otherwise the two stay apart. In doubt, two.</li>
 * </ul>
 */
public final class OrganisationIds {

    /** Why two organisations of one {@code @id} stay apart: their {@code @id}s name two organisations. */
    public static final String OTHER_ID = "other_id";
    public static final String NAMES_DIFFER = "names_differ";
    public static final String LEGAL_NAME_DIFFERS = "legal_name_differs";
    public static final String LEGAL_FORM_DIFFERS = "legal_form_differs";

    /** Key schemes that find an entity but never tell two apart. */
    static final Set<String> NOT_DISCRIMINATING = Set.of(Vocabulary.DOC_LOCAL, Vocabulary.SITE_OPERATOR, Vocabulary.OPERATOR_NAME,
            Vocabulary.DOMAIN_OPERATOR, Vocabulary.CLIENT_NAME);

    private static final Set<String> LANGUAGES = Set.of(Locale.getISOLanguages());
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    /** A language (ISO 639-1) with a region (ISO 3166-1) or without: {@code en}, {@code de-at}, {@code en_US}. */
    private static final Pattern LANGUAGE = Pattern.compile("([a-z]{2})(?:[-_]([a-z]{2}))?");

    private OrganisationIds() {}

    /** True if a segment of a path names a language: {@code en}, {@code de}, {@code de-at}, {@code en_US}. */
    static boolean language(final String segment) {
        final Matcher m = LANGUAGE.matcher(segment.toLowerCase(Locale.ROOT));
        return m.matches() && LANGUAGES.contains(m.group(1)) && (m.group(2) == null || COUNTRIES.contains(m.group(2).toUpperCase(Locale.ROOT)));
    }

    /**
     * The organisation an {@code @id} names on the registrable domain {@code domain}: the {@code @id} without the language
     * segments of its path, without its fragment and without a trailing slash; scheme and host in lower case, everything else
     * as written ({@code https://www.muster.de/en/#organization/} gives {@code https://www.muster.de/}). Null if the {@code @id}
     * is no web address or lies on another registrable domain.
     */
    public static String canonical(final String ldId, final String domain) {
        if (ldId == null || domain == null) {
            return null;
        }
        final URI u;
        try {
            u = new URI(ldId.trim());
        } catch (final URISyntaxException e) {
            return null;
        }
        final String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
        final String host = u.getHost() == null ? null : u.getHost().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || host == null || !domain.equals(Normalizers.registrableDomain(host))) {
            return null;
        }
        final List<String> path = new ArrayList<>();
        for (final String s : (u.getRawPath() == null ? "" : u.getRawPath()).split("/")) {
            if (!s.isEmpty() && !language(s)) {
                path.add(s);
            }
        }
        return scheme + "://" + host + (u.getPort() < 0 ? "" : ":" + u.getPort()) + "/" + String.join("/", path)
                + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    }

    /** True if the {@code @id} is the root of its own registrable domain {@code domain}: its path holds language segments only. */
    public static boolean root(final String ldId, final String domain) {
        final String c = canonical(ldId, domain);
        return c != null && c.indexOf('/', c.indexOf("://") + 3) == c.length() - 1;
    }

    /**
     * The scheme of the first discriminator two entities conflict on (their keys by {@code scheme\u0000scope}), or null: a strong
     * identifier, an {@code @id} per host, a facility's address, … with values that share none. {@code variants}: two
     * {@code @id}s of one scope that name one organisation ({@link #canonical}) do not conflict.
     */
    public static String blocking(final Map<String, List<String>> a, final Map<String, List<String>> b, final boolean variants) {
        for (final Map.Entry<String, List<String>> e : a.entrySet()) {
            final String scheme = e.getKey().substring(0, e.getKey().indexOf('\u0000'));
            if (NOT_DISCRIMINATING.contains(scheme)) {
                continue;
            }
            final List<String> other = b.get(e.getKey());
            if (other == null || !Collections.disjoint(other, e.getValue())) {
                continue;
            }
            if (variants && Vocabulary.LD_ID.equals(scheme) && oneOrganisation(e.getKey(), e.getValue(), other)) {
                continue;
            }
            return scheme;
        }
        return null;
    }

    /** Every {@code @id} of both sides in one scope ({@code <type>@<host>}) names the same organisation on the host's domain. */
    private static boolean oneOrganisation(final String key, final List<String> a, final List<String> b) {
        final String scope = key.substring(key.indexOf('\u0000') + 1);
        final int at = scope.indexOf('@');
        if (at < 0 || !Vocabulary.ORGANIZATION.equals(scope.substring(0, at))) {
            return false;
        }
        final String domain = Normalizers.registrableDomain(scope.substring(at + 1));
        final Set<String> ids = new HashSet<>();
        final List<String> all = new ArrayList<>(a);
        all.addAll(b);
        for (final String v : all) {
            final String c = canonical(v, domain);
            if (c == null) {
                return false;
            }
            ids.add(c);
        }
        return ids.size() == 1;
    }

    /** True if both have {@code @id}s in a common scope, and every common scope's {@code @id}s name one organisation. */
    static boolean variants(final Map<String, List<String>> a, final Map<String, List<String>> b) {
        boolean any = false;
        for (final Map.Entry<String, List<String>> e : a.entrySet()) {
            if (!e.getKey().startsWith(Vocabulary.LD_ID + "\u0000") || !b.containsKey(e.getKey())) {
                continue;
            }
            if (!oneOrganisation(e.getKey(), e.getValue(), b.get(e.getKey()))) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** The legal names of keys: the declared operator's ({@code site_operator}) and those named per page ({@code operator_name}). */
    public static Set<String> legalNames(final Map<String, List<String>> keys) {
        final Set<String> out = new TreeSet<>();
        for (final Map.Entry<String, List<String>> e : keys.entrySet()) {
            if (e.getKey().startsWith(Vocabulary.SITE_OPERATOR + "\u0000")) {
                out.addAll(e.getValue());
            } else if (e.getKey().startsWith(Vocabulary.OPERATOR_NAME + "\u0000")) {
                for (final String v : e.getValue()) {
                    out.add(IdentityResolver.operatorName(v));
                }
            }
        }
        return out;
    }

    /**
     * Whether two organisations whose {@code @id}s may name one organisation stay apart: null if they are one (their
     * {@code @id}s name one organisation, a name in common ({@link Normalizers#key}), no legal name, legal form or other
     * discriminator that differs), else the reason they stay two: {@link #OTHER_ID}, {@link #NAMES_DIFFER},
     * {@link #LEGAL_NAME_DIFFERS}, {@link #LEGAL_FORM_DIFFERS} or the scheme of the conflicting discriminator (a strong
     * identifier: {@code register}, {@code vat}, …).
     */
    public static String apart(final Map<String, List<String>> a, final Collection<String> namesA, final Collection<String> formsA,
            final Map<String, List<String>> b, final Collection<String> namesB, final Collection<String> formsB) {
        if (!variants(a, b)) {
            return OTHER_ID;
        }
        if (namesA.isEmpty() || namesB.isEmpty() || Collections.disjoint(namesA, namesB)) {
            return NAMES_DIFFER;
        }
        final Set<String> la = legalNames(a);
        final Set<String> lb = legalNames(b);
        if (!la.isEmpty() && !lb.isEmpty() && Collections.disjoint(la, lb)) {
            return LEGAL_NAME_DIFFERS;
        }
        if (!formsA.isEmpty() && !formsB.isEmpty() && Collections.disjoint(formsA, formsB)) {
            return LEGAL_FORM_DIFFERS;
        }
        return blocking(a, b, true);
    }
}
