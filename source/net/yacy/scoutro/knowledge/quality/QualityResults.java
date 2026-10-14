/*
 *  QualityResults
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

package net.yacy.scoutro.knowledge.quality;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;

/**
 * The last result of the quality check per candidate, one JSON file each in its own directory
 * ({@code DATA/SCOUTRO/llm-quality}): the only thing the check writes. A file holds no key and no prompt text; it is
 * replaced atomically by the next check of the same candidate.
 */
final class QualityResults {

    /** A stored result is never read beyond this size. */
    static final long MAX_BYTES = 1L << 20;

    private final File dir;

    QualityResults(final File dir) {
        this.dir = dir;
    }

    private File file(final String key) {
        return new File(this.dir, ModelQualityCheck.sha(key) + ".json");
    }

    synchronized void save(final String key, final JSONObject result) throws IOException {
        if (!this.dir.isDirectory() && !this.dir.mkdirs()) {
            throw new IOException("cannot create " + this.dir);
        }
        final File f = file(key);
        final File tmp = new File(this.dir, f.getName() + ".tmp");
        Files.write(tmp.toPath(), KgJson.obj("key", key, "result", result).toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The stored result of the candidate, or null (none, unreadable, or of another key). */
    synchronized JSONObject load(final String key) {
        final File f = file(key);
        if (!f.isFile() || f.length() > MAX_BYTES) {
            return null;
        }
        try {
            final JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return key.equals(o.optString("key")) ? o.optJSONObject("result") : null;
        } catch (final IOException | org.json.JSONException e) {
            return null;
        }
    }
}
