/*
 *  PasswordPolicy
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

import java.util.Locale;
import java.util.Set;

/**
 * Length-based password rules (no composition rules): 10 to 256 characters,
 * not the user name and not one of a few very common passwords.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 256;

    private static final Set<String> COMMON = Set.of("1234567890", "0123456789", "12345678910", "password12",
            "password123", "passwort12", "passwort123", "qwertzuiop", "qwertyuiop", "administrator", "yacyyacyyacy",
            "scoutro123", "scoutroscoutro", "1111111111", "abcdefghij");

    private PasswordPolicy() {
    }

    /** @throws AccessException with field {@code field} when the password is not acceptable */
    public static void check(final String username, final String password, final String field) throws AccessException {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            throw new AccessException(400, "password_too_short",
                    "The password must have at least " + MIN_LENGTH + " characters.", field);
        }
        if (password.length() > MAX_LENGTH) {
            throw new AccessException(400, "password_too_long",
                    "The password must not have more than " + MAX_LENGTH + " characters.", field);
        }
        final String lower = password.toLowerCase(Locale.ROOT);
        if (username != null && lower.equals(username.toLowerCase(Locale.ROOT))) {
            throw new AccessException(400, "password_equals_username", "The password must not be the user name.", field);
        }
        if (COMMON.contains(lower)) {
            throw new AccessException(400, "password_too_common", "This password is too common.", field);
        }
    }
}
