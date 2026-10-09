/*
 *  KgVocabularies
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

package net.yacy.scoutro.knowledge.vocab;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.json.JSONObject;

/**
 * The business vocabularies in force (NACE and the categories), loaded once
 * from the application's {@code defaults/scoutro/knowledge} directory and the
 * operator's {@code DATA/SCOUTRO/knowledge/vocabulary} overrides. The runtime
 * configures the directories at start; without that (tests, tools) the
 * defaults are read relative to the working directory. A missing or broken
 * file never stops the graph: the snapshot is then empty or partial and
 * lists its problems in the status.
 */
public final class KgVocabularies {

    public static final String DEFAULTS = "defaults/scoutro/knowledge";
    public static final String NACE_FILE = "nace-2.1.csv";
    public static final String CATEGORIES_FILE = "categories.json";
    public static final String OVERRIDES = "vocabulary";
    static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;

    /** One loaded state. */
    public static final class Snapshot {
        public final Nace nace;
        public final Categories categories;
        public final Signals signals;
        public final List<String> problems;
        public final List<String> files;

        Snapshot(final Nace nace, final Categories categories, final Signals signals, final List<String> problems, final List<String> files) {
            this.nace = nace;
            this.categories = categories;
            this.signals = signals;
            this.problems = Collections.unmodifiableList(problems);
            this.files = Collections.unmodifiableList(files);
        }

        /** Part of the extractor identity: a changed vocabulary or classification re-extracts. */
        public String version() {
            return this.categories.version + "-" + this.nace.size()+"-signals-"+this.signals.version;
        }
    }

    private static volatile File defaultsDir = new File(DEFAULTS);
    private static volatile File overridesDir;
    private static volatile Snapshot snapshot;

    private KgVocabularies() {}

    /** Sets the directories (runtime start) and drops the loaded snapshot. */
    public static synchronized void configure(final File defaults, final File overrides) {
        defaultsDir = defaults;
        overridesDir = overrides;
        snapshot = null;
    }

    /** Sets the operator's override directory (graph start); reloads only if it changed. */
    public static synchronized void overrides(final File overrides) {
        if (overrides == null ? overridesDir != null : !overrides.equals(overridesDir)) {
            overridesDir = overrides;
            snapshot = null;
        }
    }

    /** The snapshot in force, loaded on first use. */
    public static Snapshot get() {
        Snapshot s = snapshot;
        if (s == null) {
            synchronized (KgVocabularies.class) {
                s = snapshot;
                if (s == null) {
                    s = load(defaultsDir, overridesDir);
                    snapshot = s;
                }
            }
        }
        return s;
    }

    /** Reloads from disk (after an operator changed an override file). */
    public static synchronized Snapshot reload() {
        snapshot = null;
        return get();
    }

    static Snapshot load(final File defaults, final File overrides) {
        final List<String> problems = new ArrayList<>();
        final List<String> files = new ArrayList<>();
        Nace nace = Nace.EMPTY;
        final File naceFile = new File(defaults, NACE_FILE);
        try (InputStream in = new FileInputStream(naceFile)) {
            nace = Nace.read(in);
            files.add(DEFAULTS + "/" + NACE_FILE);
        } catch (final IOException e) {
            problems.add(NACE_FILE + ": " + e.getMessage());
        }
        final List<JSONObject> jsons = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        read(new File(defaults, CATEGORIES_FILE), DEFAULTS + "/" + CATEGORIES_FILE, jsons, names, problems);
        if (overrides != null && overrides.isDirectory()) {
            final File[] list = overrides.listFiles((d, n) -> n.endsWith(".json") && !n.equals("products.json") && !n.equals("needs.json"));
            if (list != null) {
                Arrays.sort(list);
                for (final File f : list) {
                    read(f, OVERRIDES + "/" + f.getName(), jsons, names, problems);
                }
            }
        }
        files.addAll(names);
        final Categories.Problems p = new Categories.Problems();
        final Categories categories = jsons.isEmpty() ? Categories.EMPTY : Categories.read(jsons, names, nace, p);
        problems.addAll(p.list);
        final Signals signals=Signals.load(defaults,overrides,problems);
        files.add(DEFAULTS+"/products.json");files.add(DEFAULTS+"/needs.json");
        return new Snapshot(nace, categories, signals, problems, files);
    }

    private static void read(final File f, final String name, final List<JSONObject> out, final List<String> names,
            final List<String> problems) {
        if (!f.isFile()) {
            problems.add(name + ": missing");
            return;
        }
        if (f.length() > MAX_FILE_BYTES) {
            problems.add(name + ": larger than " + MAX_FILE_BYTES + " bytes");
            return;
        }
        try {
            out.add(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            names.add(name);
        } catch (final IOException | org.json.JSONException | RuntimeException e) {
            problems.add(name + ": " + e.getClass().getSimpleName());
        }
    }
}
