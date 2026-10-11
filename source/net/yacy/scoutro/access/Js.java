/*
 *  Js
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

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** JSON and file helpers of the access package (YaCy's org.json throws checked exceptions). */
public final class Js {

    private Js() {
    }

    public static JSONObject obj(final Object... keysAndValues) {
        final JSONObject o = new JSONObject(true);
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            put(o, (String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return o;
    }

    public static JSONObject put(final JSONObject o, final String key, final Object value) {
        try {
            o.put(key, value == null ? JSONObject.NULL : value);
        } catch (final JSONException e) {
            throw new IllegalArgumentException("cannot store " + key + " in JSON", e);
        }
        return o;
    }

    public static JSONArray arr(final Collection<?> values) {
        final JSONArray a = new JSONArray();
        if (values != null) {
            for (final Object v : values) {
                a.put(v);
            }
        }
        return a;
    }

    public static JSONObject parse(final String text) throws IOException {
        try {
            return new JSONObject(text);
        } catch (final JSONException e) {
            throw new IOException("damaged JSON: " + e.getMessage());
        }
    }

    /** Temporary file plus rename, owner-only permissions (like the agent store). */
    static void writeAtomically(final Path target, final byte[] content) throws IOException {
        final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        ownerOnly(tmp);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void ownerOnly(final Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (final UnsupportedOperationException | IOException e) {
            // not a POSIX file system: rely on the directory permissions
        }
    }
}
