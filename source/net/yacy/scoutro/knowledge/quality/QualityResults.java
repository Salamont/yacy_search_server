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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;

/**
 * The last result of the quality check per candidate, one JSON file each in its own directory
 * ({@code DATA/SCOUTRO/llm-quality}): the only thing the check writes. A file holds no key and no prompt text; it is
 * replaced atomically by the next check of the same candidate, which keeps the one before unchanged in
 * {@code archive/} (an earlier result is never evaluated again: its evaluator version stays readable with it). Nothing
 * there is deleted by the check.
 */
final class QualityResults {

    /** A stored result is never read beyond this size (a result with its diagnosis stays well below it). */
    static final long MAX_BYTES = 4L << 20;
    /** Earlier results listed per candidate (the newest ones; the files stay). */
    static final int MAX_EARLIER = 20;

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
        if (f.isFile()) {
            // the result before: kept as it was, under the time it was replaced
            final File archive = new File(this.dir, "archive");
            if (!archive.isDirectory() && !archive.mkdirs()) {
                throw new IOException("cannot create " + archive);
            }
            final String base = f.getName().substring(0, f.getName().length() - ".json".length());
            File a = new File(archive, base + "-" + System.currentTimeMillis() + ".json");
            for (int i = 1; a.exists(); i++) {
                a = new File(archive, base + "-" + System.currentTimeMillis() + "-" + i + ".json");
            }
            Files.move(f.toPath(), a.toPath(), StandardCopyOption.ATOMIC_MOVE);
        }
        final File tmp = new File(this.dir, f.getName() + ".tmp");
        Files.write(tmp.toPath(), KgJson.obj("key", key, "result", result).toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The earlier results of the candidate, newest first: when each was replaced, its status, its finish time and the
     * versions it was evaluated with. Read only; an unreadable one is listed as such.
     */
    synchronized JSONArray earlier(final String key) {
        final JSONArray out = new JSONArray();
        final File archive = new File(this.dir, "archive");
        final String base = file(key).getName().replace(".json", "") + "-";
        final File[] files = archive.listFiles((d, n) -> n.startsWith(base) && n.endsWith(".json"));
        if (files == null) {
            return out;
        }
        final List<File> list = new ArrayList<>(List.of(files));
        list.sort(Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName, Comparator.reverseOrder()));
        for (final File f : list) {
            if (out.length() >= MAX_EARLIER) {
                break;
            }
            final String stamp = f.getName().substring(base.length(), f.getName().length() - ".json".length()).replaceAll("-\\d+$", "");
            JSONObject r = null;
            if (f.length() <= MAX_BYTES) {
                try {
                    final JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                    r = key.equals(o.optString("key")) ? o.optJSONObject("result") : null;
                } catch (final IOException | org.json.JSONException e) {
                    r = null;
                }
            }
            final JSONObject prov = r == null ? null : r.optJSONObject("provenance");
            out.put(KgJson.obj("file", "archive/" + f.getName(), "replacedAt", parse(stamp), "readable", r != null,
                    "status", r == null ? null : r.optString("status", null), "finishedAt", r == null ? null : r.opt("finishedAt"),
                    "testset", prov == null ? null : prov.optString("testset", null),
                    "evaluator", prov == null ? null : prov.optString("evaluator", null),
                    "functional", r == null || r.optJSONObject("functional") == null ? null : counts(r.optJSONObject("functional"))));
        }
        return out;
    }

    /** How many earlier results of the candidate are kept (file names only; for the status, which is polled). */
    synchronized int earlierCount(final String key) {
        final String base = file(key).getName().replace(".json", "") + "-";
        final File[] files = new File(this.dir, "archive").listFiles((d, n) -> n.startsWith(base) && n.endsWith(".json"));
        return files == null ? 0 : files.length;
    }

    private static Long parse(final String s) {
        try {
            return Long.valueOf(s);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static JSONObject counts(final JSONObject functional) {
        final JSONObject o = new JSONObject();
        for (final String k : List.of("correct", "missing", "forbidden", "wrongType", "wrongRelation", "evidence", "allowed", "notRated",
                "repeatsRules")) {
            if (functional.has(k)) {
                KgJson.put(o, k, functional.optInt(k));
            }
        }
        return o;
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
