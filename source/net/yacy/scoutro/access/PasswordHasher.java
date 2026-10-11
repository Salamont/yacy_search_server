/*
 *  PasswordHasher
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

package net.yacy.scoutro.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Password hashes of Scoutro accounts: Argon2id in the PHC string format
 * {@code $argon2id$v=19$m=<KiB>,t=<iterations>,p=<lanes>$<salt>$<hash>}.
 * Parameters follow the OWASP minimum for Argon2id (19 MiB, 2 iterations,
 * 1 lane). Clear-text passwords are never stored or logged.
 */
public final class PasswordHasher {

    static final int MEMORY_KIB = 19 * 1024;
    static final int ITERATIONS = 2;
    static final int PARALLELISM = 1;
    static final int SALT_BYTES = 16;
    static final int HASH_BYTES = 32;

    private static final Pattern PHC = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=(\\d{1,7}),t=(\\d{1,2}),p=(\\d{1,2})\\$([A-Za-z0-9+/]{16,64})\\$([A-Za-z0-9+/]{16,128})");
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private final SecureRandom random = new SecureRandom();
    private final int memoryKib;
    private final int iterations;
    /** Verified against unknown user names so that their timing matches a real check. */
    private final String dummy;

    public PasswordHasher() {
        this(MEMORY_KIB, ITERATIONS);
    }

    /** For tests: cheaper parameters. */
    PasswordHasher(final int memoryKib, final int iterations) {
        this.memoryKib = memoryKib;
        this.iterations = iterations;
        this.dummy = hash("scoutro-unknown-user-" + this.random.nextLong());
    }

    public String hash(final String password) {
        final byte[] salt = new byte[SALT_BYTES];
        this.random.nextBytes(salt);
        final byte[] out = derive(password, salt, this.memoryKib, this.iterations, PARALLELISM, HASH_BYTES);
        return "$argon2id$v=19$m=" + this.memoryKib + ",t=" + this.iterations + ",p=" + PARALLELISM + "$"
                + B64.encodeToString(salt) + "$" + B64.encodeToString(out);
    }

    /** Constant-time comparison; false for malformed hashes. */
    public boolean verify(final String password, final String phc) {
        if (password == null || phc == null) {
            return false;
        }
        final Matcher m = PHC.matcher(phc);
        if (!m.matches()) {
            return false;
        }
        final int memory = Integer.parseInt(m.group(1));
        final int iter = Integer.parseInt(m.group(2));
        final int lanes = Integer.parseInt(m.group(3));
        if (memory < 8 * lanes || memory > 1024 * 1024 || iter < 1 || lanes < 1) {
            return false;
        }
        final byte[] salt;
        final byte[] expected;
        try {
            salt = B64D.decode(m.group(4));
            expected = B64D.decode(m.group(5));
        } catch (final IllegalArgumentException e) {
            return false;
        }
        final byte[] actual = derive(password, salt, memory, iter, lanes, expected.length);
        return MessageDigest.isEqual(expected, actual);
    }

    /** Spends the time of one verification without any account (unknown user names). */
    public void verifyDummy(final String password) {
        verify(password == null ? "" : password, this.dummy);
    }

    /** True when a stored hash uses weaker parameters than the current ones. */
    public boolean needsRehash(final String phc) {
        final Matcher m = phc == null ? null : PHC.matcher(phc);
        if (m == null || !m.matches()) {
            return true;
        }
        return Integer.parseInt(m.group(1)) < this.memoryKib || Integer.parseInt(m.group(2)) < this.iterations;
    }

    private static byte[] derive(final String password, final byte[] salt, final int memoryKib, final int iterations,
            final int lanes, final int length) {
        final Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(lanes)
                .withSalt(salt)
                .build();
        final Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        final byte[] out = new byte[length];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return out;
    }
}
