/*
 *  AgentTokens
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

package net.yacy.scoutro.agents;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The agent token format and its keyed hash.
 * <p>
 * A token is {@code sca_<publicId>.<secret>}: the public id (16 base32
 * characters, 80 random bits) identifies the stored record and may appear in
 * the audit log; the secret (32 random bytes, base64url) is never stored.
 * Scoutro keeps only {@code HMAC-SHA256(pepper, secret)} and compares it in
 * constant time.
 */
public final class AgentTokens {

    public static final String PREFIX = "sca_";
    private static final Pattern FORMAT = Pattern.compile("sca_([a-z2-7]{16})\\.([A-Za-z0-9_-]{43})");
    private static final Pattern PUBLIC_ID = Pattern.compile("[a-z2-7]{16}");
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private AgentTokens() {
    }

    /** A freshly generated token; the plain text exists only in this object. */
    public static final class Issued {
        public final String publicId;
        public final String secret;

        Issued(final String publicId, final String secret) {
            this.publicId = publicId;
            this.secret = secret;
        }

        /** The complete token as handed to the agent exactly once. */
        public String plainText() {
            return PREFIX + this.publicId + "." + this.secret;
        }

        @Override
        public String toString() {
            return PREFIX + this.publicId + ".***"; // never print the secret
        }
    }

    /** A syntactically valid presented token. */
    public static final class Presented {
        public final String publicId;
        final String secret;

        Presented(final String publicId, final String secret) {
            this.publicId = publicId;
            this.secret = secret;
        }

        @Override
        public String toString() {
            return PREFIX + this.publicId + ".***";
        }
    }

    public static Issued generate() {
        final byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return new Issued(randomBase32(16), Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    }

    /** Parse a presented token; null if it does not have the token format. */
    public static Presented parse(final String token) {
        if (token == null) {
            return null;
        }
        final Matcher m = FORMAT.matcher(token.trim());
        return m.matches() ? new Presented(m.group(1), m.group(2)) : null;
    }

    public static boolean isPublicId(final String id) {
        return id != null && PUBLIC_ID.matcher(id).matches();
    }

    /** Lower-case hex of HMAC-SHA256(pepper, secret). */
    public static String hash(final byte[] pepper, final String secret) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return hex(mac.doFinal(secret.getBytes(StandardCharsets.US_ASCII)));
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** Constant-time comparison of two hex hashes. */
    public static boolean matches(final String expectedHex, final String actualHex) {
        if (expectedHex == null || actualHex == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedHex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                actualHex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    static String randomBase32(final int length) {
        final char[] c = new char[length];
        for (int i = 0; i < length; i++) {
            c[i] = BASE32[RANDOM.nextInt(32)];
        }
        return new String(c);
    }

    static String hex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    static byte[] unhex(final String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
