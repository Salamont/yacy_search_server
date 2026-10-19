// EntityRoles.java
// Copyright 2026 Scoutro
//
// This file is part of YaCy / Scoutro.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.

package net.yacy.scoutro.knowledge.read;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;

import net.yacy.scoutro.knowledge.extract.BusinessRules;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * The role an organisation plays for the reader, computed at read time from what is stored (0.11.5): nothing is rewritten.
 * <ul>
 * <li>{@link #OPERATOR}: the entity holds a {@code site_operator} or {@code domain_operator} key of a domain.</li>
 * <li>{@link #BRAND} / {@link #MANUFACTURER}: the entity is the brand of a product or the maker of one, was named by a
 * {@code brand} or {@code manufacturer} property, or carries a brand identity.</li>
 * <li>{@link #GENERIC}: the entity has only page-local keys and its name is no name of an organisation (a navigation label such as
 * "Bildergalerie" or "Showroom", a kind of organisation such as "Banken"). Such facts are physically in the graph from pages read
 * before 0.11.5; the business view hides them, the object view labels them.</li>
 * </ul>
 * An operator is never shown as a brand: a brand that is the operator's own name is the operator.
 */
final class EntityRoles {

    static final String OPERATOR = "operator";
    static final String BRAND = "brand";
    static final String MANUFACTURER = "manufacturer";
    static final String GENERIC = "generic";

    private static final Pattern BRAND_LOCATOR = Pattern.compile("/(brand|manufacturer)(?:/[0-9]+)?/name$");

    private EntityRoles() {
    }

    /** The role of entity {@code ent} of {@code type} named {@code name}, or null when none applies. */
    static String of(final Connection c, final long ent, final String type, final String name, final Viewer v) throws SQLException {
        if (!Vocabulary.ORGANIZATION.equals(type)) {
            return null;
        }
        boolean operator = false;
        boolean brandKey = false;
        boolean localOnly = true;
        boolean anyKey = false;
        try (PreparedStatement ps = c.prepareStatement("SELECT v.name, k.value FROM kg_entity_key k JOIN kg_vocab v ON v.term_id = k.scheme"
                + " WHERE k.ent_rowid = ?")) {
            ps.setLong(1, ent);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    anyKey = true;
                    final String scheme = rs.getString(1);
                    final String value = rs.getString(2);
                    switch (scheme) {
                        case Vocabulary.SITE_OPERATOR:
                        case Vocabulary.DOMAIN_OPERATOR:
                            operator = true;
                            localOnly = false;
                            break;
                        case Vocabulary.LD_ID:
                            brandKey |= value != null && value.startsWith(JsonLdExtractor.BRAND_ID);
                            localOnly = false;
                            break;
                        case Vocabulary.DOC_LOCAL:
                        case Vocabulary.OPERATOR_NAME:
                            break;
                        default:
                            localOnly = false;
                            break;
                    }
                }
            }
        }
        if (operator) {
            return OPERATOR;
        }
        String role = null;
        try (PreparedStatement ps = c.prepareStatement("SELECT v.name FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE ((s.obj_ent = ? AND v.name = '" + Vocabulary.HAS_BRAND + "') OR (s.subj = ? AND v.name IN ('"
                + Vocabulary.MANUFACTURER_OF + "', '" + Vocabulary.BRAND_OF + "'))) AND " + KgReader.visibleStatement(v, "s")
                + " ORDER BY CASE v.name WHEN '" + Vocabulary.MANUFACTURER_OF + "' THEN 0 ELSE 1 END LIMIT 1")) {
            ps.setLong(1, ent);
            ps.setLong(2, ent);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    role = Vocabulary.MANUFACTURER_OF.equals(rs.getString(1)) ? MANUFACTURER : BRAND;
                }
            }
        }
        if (role == null) {
            try (PreparedStatement ps = c.prepareStatement("SELECT ev.locator FROM kg_statement s JOIN kg_vocab v ON v.term_id = s.pred"
                    + " JOIN kg_evidence ev ON ev.stmt_rowid = s.stmt_rowid WHERE s.subj = ? AND v.name = '" + Vocabulary.NAME
                    + "' AND ev.locator IS NOT NULL AND " + KgReader.visibleStatement(v, "s") + " LIMIT 50")) {
                ps.setLong(1, ent);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && role == null) {
                        final java.util.regex.Matcher m = BRAND_LOCATOR.matcher(rs.getString(1));
                        if (m.find()) {
                            role = "brand".equals(m.group(1)) ? BRAND : MANUFACTURER;
                        }
                    }
                }
            }
        }
        if (role != null) {
            return role;
        }
        if (brandKey) {
            return BRAND;
        }
        if (localOnly && anyKey && BusinessRules.genericName(name)) {
            return GENERIC;
        }
        return null;
    }
}
