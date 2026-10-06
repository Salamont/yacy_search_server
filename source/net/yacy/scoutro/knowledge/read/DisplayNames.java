/*
 *  DisplayNames
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

package net.yacy.scoutro.knowledge.read;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * The name an entity is shown with (package 6.1), for one viewer: never a
 * technical ID. In this order: the visible {@code name} ({@code fact}); a
 * visible alias that is a legal name ({@code legal}); for the unnamed operator
 * of a site (the {@code domain_operator} placeholder of a services or careers
 * page) the visible name of the one declared operator of that domain
 * ({@code operator}); for an organisation a name derived from the host of the
 * viewer's pages, or the host itself ({@code domain}, with
 * {@code display_host}); else none ({@code fallback}, the page shows a typed
 * "unnamed organisation").
 * <p>
 * Only {@code fact} is a name the sources state. The others are presentation:
 * never stored, never a key, never used to merge, never given to the chat as a
 * fact; {@code name} stays the stated name (or null). A stated name that turns
 * up later replaces them at the next read.
 */
final class DisplayNames {

    static final String FACT = "fact";
    static final String LEGAL = "legal";
    static final String OPERATOR = "operator";
    static final String DOMAIN = "domain";
    static final String FALLBACK = "fallback";

    /** Second-level labels under which the registrable label is one further left (co.uk, com.au, ...). */
    private static final Set<String> SECOND_LEVEL = Set.of("co", "com", "org", "net", "ac", "gv", "or", "gov", "edu", "ltd", "plc");
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private DisplayNames() {
    }

    /** A display name, its source and, for a name from the domain, the host. */
    static final class Name {
        final String name;
        final String source;
        final String host;

        Name(final String name, final String source, final String host) {
            this.name = name;
            this.source = source;
            this.host = host;
        }
    }

    /**
     * The display name of an entity; {@code factName} is its visible name
     * (null: none), {@code aliases} its visible aliases if already read
     * (null: read here). Without a stated name it costs a few bounded reads.
     */
    static Name of(final Connection c, final long ent, final String type, final String factName, final Collection<String> aliases,
            final Viewer v) throws SQLException {
        if (factName != null && !factName.isBlank()) {
            return new Name(factName, FACT, null);
        }
        for (final String alias : aliases != null ? aliases : aliases(c, ent, v)) {
            if (alias != null && Normalizers.legalName(alias) != null) {
                return new Name(alias, LEGAL, null);
            }
        }
        if (Vocabulary.ORGANIZATION.equals(type)) {
            final String operator = operator(c, ent, v);
            if (operator != null) {
                return new Name(operator, OPERATOR, null);
            }
            final String host = mainHost(c, ent, v);
            if (host != null) {
                final String shown = host.startsWith("www.") ? host.substring(4) : host;
                final String derived = fromHost(host);
                return new Name(derived != null ? derived : shown, DOMAIN, shown);
            }
        }
        return new Name(null, FALLBACK, null);
    }

    /** The display name of an entity reference (type and visible name read here). */
    static Name ref(final Connection c, final long ent, final Viewer v) throws SQLException {
        return known(c, ent, KgReader.visibleName(c, ent, v), v);
    }

    /** The display name of an entity whose visible name is already known (null: it has none). */
    static Name known(final Connection c, final long ent, final String name, final Viewer v) throws SQLException {
        if (name != null) {
            return new Name(name, FACT, null);
        }
        String type = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT t.name FROM kg_entity e JOIN kg_vocab t ON t.term_id = e.type WHERE e.ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    type = rs.getString(1);
                }
            }
        }
        return of(c, ent, type, null, null, v);
    }

    /** Adds {@code display_name}, {@code display_name_source} and, for {@code domain}, {@code display_host}. */
    static JSONObject put(final JSONObject o, final Name n, final String prefix) {
        KgJson.put(o, prefix + "display_name", n.name);
        KgJson.put(o, prefix + "display_name_source", n.source);
        if (n.host != null) {
            KgJson.put(o, prefix + "display_host", n.host);
        }
        return o;
    }

    static JSONObject put(final JSONObject o, final Name n) {
        return put(o, n, "");
    }

    private static List<String> aliases(final Connection c, final long ent, final Viewer v) throws SQLException {
        final List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT s.obj_val FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE s.subj = ? AND v.kind = 2 AND v.name = 'alias' AND " + KgReader.visibleStatement(v, "s")
                + " ORDER BY s.quality, s.current_sources DESC LIMIT 20")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /**
     * For the unnamed operator of a site: the visible name of the declared
     * operator of the same domain, if the domain has exactly one (the one the
     * resolver joins it with); never a guess between several.
     */
    private static String operator(final Connection c, final long ent, final Viewer v) throws SQLException {
        final List<Long> found = new ArrayList<>();
        boolean visible = false;
        try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT k2.ent_rowid FROM kg_entity_key k1 JOIN kg_entity_key k2"
                + " ON k2.scope = k1.scope AND k2.scheme = (SELECT term_id FROM kg_vocab WHERE kind = 3 AND name = 'site_operator')"
                + " JOIN kg_entity e ON e.ent_rowid = k2.ent_rowid WHERE k1.ent_rowid = ? AND k1.scheme = (SELECT term_id FROM kg_vocab"
                + " WHERE kind = 3 AND name = 'domain_operator') AND k2.ent_rowid <> ? AND e.status = 1 LIMIT 2")) {
            ps.setLong(1, ent);
            ps.setLong(2, ent);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getLong(1));
                }
            }
        }
        if (found.size() != 1) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kg_entity e WHERE e.ent_rowid = ? AND " + KgReader.visibleEntity(v, "e"))) {
            ps.setLong(1, found.get(0));
            try (ResultSet rs = ps.executeQuery()) {
                visible = rs.next();
            }
        }
        return visible ? KgReader.visibleName(c, found.get(0), v) : null;
    }

    /** The host of most of the viewer's pages with evidence for the entity. */
    private static String mainHost(final Connection c, final long ent, final Viewer v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT min(d.url) FROM kg_statement s JOIN kg_evidence e ON e.stmt_rowid = s.stmt_rowid"
                + " JOIN kg_doc d ON d.doc_rowid = e.doc_rowid WHERE s.subj = ? AND " + KgReader.visibleDoc(v, "d")
                + " GROUP BY d.host_id ORDER BY count(DISTINCT d.doc_rowid) DESC, 1 LIMIT 1")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? KgReader.host(rs.getString(1)) : null;
            }
        }
    }

    /**
     * A readable name from a host: its registrable label, words at hyphens,
     * each capitalised ({@code www.zimmerei-boehmer.de} → "Zimmerei Boehmer");
     * null for an address, a punycode label or a label of digits.
     */
    static String fromHost(final String host) {
        if (host == null) {
            return null;
        }
        final String h = host.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        final String[] labels = h.split("\\.");
        if (labels.length < 2) {
            return null;
        }
        int i = labels.length - 2;
        if (labels.length >= 3 && SECOND_LEVEL.contains(labels[i]) && labels[labels.length - 1].length() == 2) {
            i--;
        }
        final String label = labels[i];
        if ("www".equals(label) || label.startsWith("xn--") || !LABEL.matcher(label).matches() || label.matches("^[0-9-]+$")) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        for (final String w : label.split("-+")) {
            if (w.isEmpty()) {
                continue;
            }
            sb.append(sb.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
