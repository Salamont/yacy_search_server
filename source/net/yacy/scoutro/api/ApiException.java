/*
 *  ApiException
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

package net.yacy.scoutro.api;

import org.json.JSONObject;

/**
 * An error that is reported to the API client as
 * <code>{"error": {"code": ..., "message": ..., "details": ...}}</code>
 * with the given HTTP status.
 */
public class ApiException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final transient JSONObject details;

    public ApiException(final int status, final String code, final String message) {
        this(status, code, message, null);
    }

    public ApiException(final int status, final String code, final String message, final JSONObject details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int status() {
        return this.status;
    }

    public String code() {
        return this.code;
    }

    public JSONObject toJson() {
        final JSONObject error = Json.obj("code", this.code, "message", getMessage());
        if (this.details != null) {
            Json.put(error, "details", this.details);
        }
        return Json.obj("error", error);
    }

    /** 400 with the name of the offending field in the details. */
    public static ApiException invalid(final String field, final String message) {
        return new ApiException(400, "invalid_request", message, Json.obj("field", field));
    }
}
