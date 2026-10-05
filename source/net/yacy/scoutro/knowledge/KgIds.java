/*
 *  KgIds
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

package net.yacy.scoutro.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Stable public identifiers of the knowledge graph.
 * <p>
 * Entity and statement IDs are derived once, when the object is created, and
 * stored; they are never recomputed. The derivation is deterministic, so a
 * rebuild from the same data normally yields the same IDs. Every input part is
 * NFC-normalised and separated by a NUL byte, so different part boundaries can
 * never produce the same input.
 */
public final class KgIds {

    public static final String ENTITY_PREFIX = "kge_";
    public static final String STATEMENT_PREFIX = "kgs_";
    /** Number of base32 characters after the prefix (100 bits). */
    public static final int ID_CHARS = 20;
    public static final int ID_LENGTH = 4 + ID_CHARS;

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final Pattern ENTITY = Pattern.compile("^kge_[a-z2-7]{20}$");
    private static final Pattern STATEMENT = Pattern.compile("^kgs_[a-z2-7]{20}$");
    /** Solr document id of YaCy: the 12-character URL hash in YaCy's Base64 alphabet. */
    private static final Pattern DOC_ID = Pattern.compile("^[A-Za-z0-9_-]{12}$");
    private static final Pattern EPOCH = Pattern.compile("^[0-9a-f]{16}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private KgIds() {}

    /**
     * ID of a new entity from its type and the identity key it was created with
     * (scheme, scope, normalised value), see the identity rules in the plan.
     */
    public static String entityId(final String type, final String scheme, final String scope, final String value) {
        return ENTITY_PREFIX + base32(sha256("scoutro-kg/entity/v1", type, scheme, scope, value), ID_CHARS);
    }

    /** ID of a new statement from the subject ID at creation, the predicate and the canonical object. */
    public static String statementId(final String subjectId, final String predicate, final String canonicalObject) {
        return STATEMENT_PREFIX + base32(sha256("scoutro-kg/statement/v1", subjectId, predicate, canonicalObject), ID_CHARS);
    }

    /** 16-byte key of a canonical object value, used for statement uniqueness. */
    public static byte[] objectKey(final String canonicalObject) {
        return Arrays.copyOf(sha256("scoutro-kg/object/v1", canonicalObject), 16);
    }

    /** Random 64-bit dataset epoch as 16 hex characters; a new epoch invalidates every change cursor. */
    public static String newEpoch() {
        final byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        final StringBuilder sb = new StringBuilder(16);
        for (final byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    public static boolean isEntityId(final String s) {
        return s != null && ENTITY.matcher(s).matches();
    }

    public static boolean isStatementId(final String s) {
        return s != null && STATEMENT.matcher(s).matches();
    }

    public static boolean isDocId(final String s) {
        return s != null && DOC_ID.matcher(s).matches();
    }

    public static boolean isEpoch(final String s) {
        return s != null && EPOCH.matcher(s).matches();
    }

    static byte[] sha256(final String... parts) {
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                md.update((byte) 0);
            }
            final String p = parts[i] == null ? "" : Normalizer.normalize(parts[i], Normalizer.Form.NFC);
            md.update(p.getBytes(StandardCharsets.UTF_8));
        }
        return md.digest();
    }

    static String base32(final byte[] bytes, final int chars) {
        final StringBuilder sb = new StringBuilder(chars);
        int buffer = 0;
        int bits = 0;
        int i = 0;
        while (sb.length() < chars) {
            if (bits < 5) {
                buffer = ((buffer << 8) | (bytes[i++] & 0xff)) & 0xffff;
                bits += 8;
            }
            sb.append(BASE32[(buffer >> (bits - 5)) & 0x1f]);
            bits -= 5;
        }
        return sb.toString();
    }
}
