/*
 *  AccessException
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

/**
 * A refused account or access operation with an HTTP status, a stable error
 * code and, for invalid input, the offending field.
 */
public final class AccessException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final String field;

    public AccessException(final int status, final String code, final String message) {
        this(status, code, message, null);
    }

    public AccessException(final int status, final String code, final String message, final String field) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
    }

    public static AccessException invalid(final String field, final String message) {
        return new AccessException(400, "invalid_request", message, field);
    }

    public int status() {
        return this.status;
    }

    public String code() {
        return this.code;
    }

    /** The invalid field, or null. */
    public String field() {
        return this.field;
    }
}
